package com.cras.app.data

import com.cras.app.auth.OperatorSession
import com.cras.app.models.Plan
import com.cras.app.models.Task
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class OutboxServiceTest {

    private val session = OperatorSession(
        operatorId = "550e8400-e29b-41d4-a716-446655440001",
        email = "alice@cras.app",
        accessToken = "test-token"
    )

    private fun createTask(id: String = UUID.randomUUID().toString(), title: String = "Test Task", completedAt: String? = null): Task {
        return Task(
            id = id,
            title = title,
            description = null,
            priority = 4,
            plan = null,
            labels = emptyList(),
            parentId = null,
            completedAt = completedAt,
            createdAt = "2026-08-21T10:00:00Z",
            updatedAt = "2026-08-21T10:00:00Z",
            version = 1
        )
    }

    @Test
    fun testInMemoryOutboxStoreOperations() {
        val store = InMemoryOutboxStore()
        val operatorId = session.operatorId

        assertEquals(emptyList<OutboxItem>(), store.getOutbox(operatorId))

        val task1 = createTask(title = "Task 1")
        val createItem = OutboxItem.Create(
            id = task1.id,
            task = task1,
            params = CreateTaskParams(id = task1.id, title = task1.title),
            createdAt = "2026-08-21T10:00:00Z"
        )
        store.enqueue(operatorId, createItem)
        assertEquals(listOf(createItem), store.getOutbox(operatorId))

        val completeItem = OutboxItem.Complete(
            id = UUID.randomUUID().toString(),
            taskId = task1.id,
            expectedVersion = 1,
            completedAt = "2026-08-21T10:05:00Z",
            createdAt = "2026-08-21T10:05:00Z"
        )
        store.enqueue(operatorId, completeItem)
        assertEquals(listOf(createItem, completeItem), store.getOutbox(operatorId))

        store.remove(operatorId, createItem.id)
        assertEquals(listOf(completeItem), store.getOutbox(operatorId))

        store.clear(operatorId)
        assertEquals(emptyList<OutboxItem>(), store.getOutbox(operatorId))
    }

    @Test
    fun testApplyOutboxToTasks() {
        val canonicalTask1 = createTask(id = "550e8400-e29b-41d4-a716-446655440001", title = "Canonical 1")
        val canonicalTask2 = createTask(id = "550e8400-e29b-41d4-a716-446655440002", title = "Canonical 2")
        val canonicalList = listOf(canonicalTask1, canonicalTask2)

        val pendingCreateTask = createTask(id = "550e8400-e29b-41d4-a716-446655440003", title = "Pending Create")
        val createItem = OutboxItem.Create(
            id = pendingCreateTask.id,
            task = pendingCreateTask,
            params = CreateTaskParams(id = pendingCreateTask.id, title = pendingCreateTask.title),
            createdAt = "2026-08-21T10:00:00Z"
        )

        val completeItem = OutboxItem.Complete(
            id = UUID.randomUUID().toString(),
            taskId = canonicalTask1.id,
            expectedVersion = 1,
            completedAt = "2026-08-21T10:05:00Z",
            createdAt = "2026-08-21T10:05:00Z"
        )

        val applied = applyOutboxToTasks(canonicalList, listOf(createItem, completeItem))

        // Create is prepended
        assertEquals(3, applied.size)
        assertEquals(pendingCreateTask.id, applied[0].id)
        // Completion marks task as completed
        val modifiedTask1 = applied.find { it.id == canonicalTask1.id }
        assertEquals("2026-08-21T10:05:00Z", modifiedTask1?.completedAt)
        // Other task unchanged
        val unmodifiedTask2 = applied.find { it.id == canonicalTask2.id }
        assertEquals(null, unmodifiedTask2?.completedAt)
    }

    @Test
    fun testIsNetworkErrorIdentification() {
        assertTrue(isNetworkError(UnknownHostException("Unable to resolve host")))
        assertTrue(isNetworkError(ConnectException("Connection refused")))
        assertTrue(isNetworkError(SocketTimeoutException("timeout")))
        assertTrue(isNetworkError(IOException("Failed to fetch")))
        assertTrue(isNetworkError(IOException("Network error occurred")))
        assertTrue(isNetworkError(IOException("offline")))

        assertFalse(isNetworkError(TaskConflictException("Task version conflict")))
        assertFalse(isNetworkError(IOException("Failed to create_task: 403 Permission denied")))
        assertFalse(isNetworkError(IllegalArgumentException("Invalid params")))
        assertFalse(isNetworkError(RuntimeException("Internal error")))
    }

    @Test
    fun testDrainerDrainsFifoAndHandlesNetworkError() = runTest {
        val store = InMemoryOutboxStore()
        val task1 = createTask(title = "Task 1")
        val createItem1 = OutboxItem.Create(
            id = task1.id,
            task = task1,
            params = CreateTaskParams(id = task1.id, title = task1.title),
            createdAt = "2026-08-21T10:00:00Z"
        )
        val task2 = createTask(title = "Task 2")
        val createItem2 = OutboxItem.Create(
            id = task2.id,
            task = task2,
            params = CreateTaskParams(id = task2.id, title = task2.title),
            createdAt = "2026-08-21T10:01:00Z"
        )

        store.enqueue(session.operatorId, createItem1)
        store.enqueue(session.operatorId, createItem2)

        var isOffline = false
        val createdOnServer = mutableListOf<Task>()

        val fakeTaskService = object : TaskService {
            override suspend fun fetchTasks(session: OperatorSession): List<Task> = createdOnServer
            override suspend fun fetchTaskById(session: OperatorSession, taskId: String): Task? =
                createdOnServer.find { it.id == taskId }

            override suspend fun createTask(session: OperatorSession, params: CreateTaskParams): Task {
                if (isOffline) throw UnknownHostException("Offline")
                val created = Task(
                    id = params.id ?: UUID.randomUUID().toString(),
                    title = params.title,
                    description = params.description,
                    priority = params.priority,
                    plan = params.plan,
                    labels = params.labels,
                    parentId = params.parentId,
                    completedAt = null,
                    createdAt = "2026-08-21T10:00:00Z",
                    updatedAt = "2026-08-21T10:00:00Z",
                    version = 1
                )
                createdOnServer.add(created)
                return created
            }

            override suspend fun updateTask(session: OperatorSession, params: UpdateTaskParams): Task = throw NotImplementedError()
            override suspend fun completeTask(session: OperatorSession, taskId: String, expectedVersion: Int, completedAt: String?): Task = throw NotImplementedError()
            override suspend fun uncompleteTask(session: OperatorSession, taskId: String, expectedVersion: Int): Task = throw NotImplementedError()
        }

        val drainer = OutboxDrainer(fakeTaskService, store)
        val drainCallbacks = object : OutboxDrainCallbacks {}

        // Drain while online
        drainer.drain(session, drainCallbacks)
        assertEquals(0, store.getOutbox(session.operatorId).size)
        assertEquals(2, createdOnServer.size)

        // Enqueue item while offline
        isOffline = true
        val task3 = createTask(title = "Task 3")
        val createItem3 = OutboxItem.Create(
            id = task3.id,
            task = task3,
            params = CreateTaskParams(id = task3.id, title = task3.title),
            createdAt = "2026-08-21T10:02:00Z"
        )
        store.enqueue(session.operatorId, createItem3)

        drainer.drain(session, drainCallbacks)
        // Item is retained in outbox on network error
        assertEquals(1, store.getOutbox(session.operatorId).size)
        assertEquals(2, createdOnServer.size)

        // Reconnect
        isOffline = false
        drainer.drain(session, drainCallbacks)
        assertEquals(0, store.getOutbox(session.operatorId).size)
        assertEquals(3, createdOnServer.size)
    }

    @Test
    fun testDrainerConflictCallbackOnCompletionConflict() = runTest {
        val store = InMemoryOutboxStore()
        val taskId = "550e8400-e29b-41d4-a716-446655440001"
        val completeItem = OutboxItem.Complete(
            id = UUID.randomUUID().toString(),
            taskId = taskId,
            expectedVersion = 1,
            completedAt = "2026-08-21T10:05:00Z",
            createdAt = "2026-08-21T10:05:00Z"
        )
        store.enqueue(session.operatorId, completeItem)

        val fakeTaskService = object : TaskService {
            override suspend fun fetchTasks(session: OperatorSession): List<Task> = emptyList()
            override suspend fun fetchTaskById(session: OperatorSession, taskId: String): Task? = null
            override suspend fun createTask(session: OperatorSession, params: CreateTaskParams): Task = throw NotImplementedError()
            override suspend fun updateTask(session: OperatorSession, params: UpdateTaskParams): Task = throw NotImplementedError()
            override suspend fun completeTask(session: OperatorSession, taskId: String, expectedVersion: Int, completedAt: String?): Task {
                throw TaskConflictException("Task version conflict: expected 1, found 2")
            }
            override suspend fun uncompleteTask(session: OperatorSession, taskId: String, expectedVersion: Int): Task = throw NotImplementedError()
        }

        val drainer = OutboxDrainer(fakeTaskService, store)
        var conflictReported: Throwable? = null
        val drainCallbacks = object : OutboxDrainCallbacks {
            override suspend fun onConflict(error: Throwable, item: OutboxItem) {
                conflictReported = error
            }
        }

        drainer.drain(session, drainCallbacks)

        // Outbox item removed, conflict callback fired
        assertEquals(0, store.getOutbox(session.operatorId).size)
        assertTrue(conflictReported is TaskConflictException)
    }
}
