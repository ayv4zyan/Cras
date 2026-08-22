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
        assertTrue(isNetworkError(IOException("Failed to create_task: 401 Unauthorized")))
        assertTrue(isNetworkError(IOException("Failed to create_task: 429 Too Many Requests")))
        assertTrue(isNetworkError(IOException("Failed to create_task: 500 Internal Server Error")))
        assertTrue(isNetworkError(IOException("Failed to create_task: 503 Service Unavailable")))

        assertFalse(isNetworkError(TaskConflictException("Task version conflict")))
        assertFalse(isNetworkError(IOException("Failed to create_task: 403 Permission denied")))
        assertFalse(isNetworkError(IOException("Failed to create_task: 400 Bad Request")))
        assertFalse(isNetworkError(IOException("Failed to create_task: 404 Not Found")))
        assertFalse(isNetworkError(IllegalArgumentException("Invalid params")))
        assertFalse(isNetworkError(RuntimeException("Internal error")))
    }

    @Test
    fun testApplyOutboxToTasksPreservesFifoOrderForMultiplePendingCreates() {
        val canonical1Id = UUID.randomUUID().toString()
        val canonical2Id = UUID.randomUUID().toString()
        val canonical1 = createTask(id = canonical1Id, title = "Canonical 1")
        val canonical2 = createTask(id = canonical2Id, title = "Canonical 2")
        val canonicalList = listOf(canonical1, canonical2)

        val task1Id = UUID.randomUUID().toString()
        val task2Id = UUID.randomUUID().toString()
        val task3Id = UUID.randomUUID().toString()
        val task1 = createTask(id = task1Id, title = "Pending 1")
        val task2 = createTask(id = task2Id, title = "Pending 2")
        val task3 = createTask(id = task3Id, title = "Pending 3")

        val outbox = listOf(
            OutboxItem.Create(id = task1.id, task = task1, params = CreateTaskParams(id = task1.id, title = task1.title), createdAt = "2026-08-21T10:00:00Z"),
            OutboxItem.Create(id = task2.id, task = task2, params = CreateTaskParams(id = task2.id, title = task2.title), createdAt = "2026-08-21T10:01:00Z"),
            OutboxItem.Create(id = task3.id, task = task3, params = CreateTaskParams(id = task3.id, title = task3.title), createdAt = "2026-08-21T10:02:00Z")
        )

        val applied = applyOutboxToTasks(canonicalList, outbox)
        assertEquals(5, applied.size)
        assertEquals(task1Id, applied[0].id)
        assertEquals(task2Id, applied[1].id)
        assertEquals(task3Id, applied[2].id)
        assertEquals(canonical1Id, applied[3].id)
        assertEquals(canonical2Id, applied[4].id)
    }

    @Test
    fun testSharedPreferencesOutboxStorePlanRoundTrip() {
        val fakePrefs = FakeSharedPreferences()
        val store = SharedPreferencesOutboxStore(fakePrefs)
        val operatorId = session.operatorId

        val instantTask = createTask(id = UUID.randomUUID().toString(), title = "Instant Task")
        val floatingTask = createTask(id = UUID.randomUUID().toString(), title = "Floating Task")
        val dateOnlyTask = createTask(id = UUID.randomUUID().toString(), title = "DateOnly Task")
        val noPlanTask = createTask(id = UUID.randomUUID().toString(), title = "No Plan Task")

        val items = listOf(
            OutboxItem.Create(
                id = instantTask.id,
                task = instantTask,
                params = CreateTaskParams(id = instantTask.id, title = instantTask.title, plan = Plan.Instant("2026-08-21T15:00:00Z")),
                createdAt = "2026-08-21T10:00:00Z"
            ),
            OutboxItem.Create(
                id = floatingTask.id,
                task = floatingTask,
                params = CreateTaskParams(id = floatingTask.id, title = floatingTask.title, plan = Plan.Floating("2026-08-22", "09:00")),
                createdAt = "2026-08-21T10:01:00Z"
            ),
            OutboxItem.Create(
                id = dateOnlyTask.id,
                task = dateOnlyTask,
                params = CreateTaskParams(id = dateOnlyTask.id, title = dateOnlyTask.title, plan = Plan.DateOnly("2026-08-23")),
                createdAt = "2026-08-21T10:02:00Z"
            ),
            OutboxItem.Create(
                id = noPlanTask.id,
                task = noPlanTask,
                params = CreateTaskParams(id = noPlanTask.id, title = noPlanTask.title, plan = null),
                createdAt = "2026-08-21T10:04:00Z"
            )
        )

        for (item in items) {
            store.enqueue(operatorId, item)
        }

        // Read from fresh store instance sharing same SharedPreferences
        val store2 = SharedPreferencesOutboxStore(fakePrefs)
        val loaded = store2.getOutbox(operatorId)
        assertEquals(4, loaded.size)

        val loadedInstant = loaded[0] as OutboxItem.Create
        assertEquals(Plan.Instant("2026-08-21T15:00:00Z"), loadedInstant.params.plan)

        val loadedFloating = loaded[1] as OutboxItem.Create
        assertEquals(Plan.Floating("2026-08-22", "09:00"), loadedFloating.params.plan)

        val loadedDateOnly = loaded[2] as OutboxItem.Create
        assertEquals(Plan.DateOnly("2026-08-23"), loadedDateOnly.params.plan)

        val loadedNoPlan = loaded[3] as OutboxItem.Create
        assertEquals(null, loadedNoPlan.params.plan)
    }

    @Test
    fun testSharedPreferencesOutboxStoreFallbackAndRecovery() {
        val fakePrefs = FakeSharedPreferences()
        val store = SharedPreferencesOutboxStore(fakePrefs)
        val operatorId = session.operatorId

        val task1 = createTask(title = "Task 1")
        val item1 = OutboxItem.Create(
            id = task1.id,
            task = task1,
            params = CreateTaskParams(id = task1.id, title = task1.title),
            createdAt = "2026-08-21T10:00:00Z"
        )
        store.enqueue(operatorId, item1)
        assertEquals(1, store.getOutbox(operatorId).size)

        // Make SharedPreferences writes fail
        fakePrefs.shouldFailWrite = true
        val task2 = createTask(title = "Task 2")
        val item2 = OutboxItem.Create(
            id = task2.id,
            task = task2,
            params = CreateTaskParams(id = task2.id, title = task2.title),
            createdAt = "2026-08-21T10:01:00Z"
        )
        store.enqueue(operatorId, item2)

        // Reading from store returns in-memory fallback containing both items
        val currentOutbox = store.getOutbox(operatorId)
        assertEquals(2, currentOutbox.size)
        assertEquals(item1.id, currentOutbox[0].id)
        assertEquals(item2.id, currentOutbox[1].id)

        // Restore SharedPreferences writes and save
        fakePrefs.shouldFailWrite = false
        store.remove(operatorId, item1.id)

        // Now store is no longer stale, persisted store matches in-memory
        assertEquals(listOf(item2), store.getOutbox(operatorId))
        val storeAfterRecovery = SharedPreferencesOutboxStore(fakePrefs)
        assertEquals(listOf(item2), storeAfterRecovery.getOutbox(operatorId))
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
    fun testDrainerInvokesOnNetworkErrorWhenDrainStops() = runTest {
        val store = InMemoryOutboxStore()
        val completeTask1 = createTask(title = "Task 1", completedAt = "2026-08-21T10:05:00Z")
        val completeItem = OutboxItem.Complete(
            id = UUID.randomUUID().toString(),
            taskId = completeTask1.id,
            expectedVersion = 2,
            completedAt = "2026-08-21T10:05:00Z",
            createdAt = "2026-08-21T10:05:30Z"
        )
        store.enqueue(session.operatorId, completeItem)

        var isOffline = false
        val fakeTaskService = object : TaskService {
            override suspend fun fetchTasks(session: OperatorSession): List<Task> = emptyList()
            override suspend fun fetchTaskById(session: OperatorSession, taskId: String): Task? =
                if (taskId == completeTask1.id) completeTask1 else null

            override suspend fun createTask(session: OperatorSession, params: CreateTaskParams): Task = throw NotImplementedError()

            override suspend fun updateTask(session: OperatorSession, params: UpdateTaskParams): Task = throw NotImplementedError()

            override suspend fun completeTask(session: OperatorSession, taskId: String, expectedVersion: Int, completedAt: String?): Task {
                if (isOffline) throw UnknownHostException("Offline")
                return createTask(id = taskId, completedAt = completedAt)
                    .copy(version = expectedVersion + 1)
            }

            override suspend fun uncompleteTask(session: OperatorSession, taskId: String, expectedVersion: Int): Task = throw NotImplementedError()
        }

        val drainer = OutboxDrainer(fakeTaskService, store)
        val networkErrors = mutableListOf<Pair<Throwable, OutboxItem>>()
        val callbacks = object : OutboxDrainCallbacks {
            override suspend fun onNetworkError(error: Throwable, item: OutboxItem) {
                networkErrors.add(error to item)
            }
        }

        // Online: completes without any network callback.
        drainer.drain(session, callbacks)
        assertEquals(0, store.getOutbox(session.operatorId).size)
        assertTrue(networkErrors.isEmpty())

        // Offline with a queued completion: drain stops and reports the network error.
        isOffline = true
        val offlineComplete = OutboxItem.Complete(
            id = UUID.randomUUID().toString(),
            taskId = completeTask1.id,
            expectedVersion = 3,
            completedAt = "2026-08-21T10:06:00Z",
            createdAt = "2026-08-21T10:06:30Z"
        )
        store.enqueue(session.operatorId, offlineComplete)

        drainer.drain(session, callbacks)
        assertEquals(1, networkErrors.size)
        assertEquals(offlineComplete, networkErrors[0].second)
        // The item is retained for a later retry.
        assertEquals(1, store.getOutbox(session.operatorId).size)
    }

    @Test
    fun testDrainerRetriesOn401And429() = runTest {
        val store = InMemoryOutboxStore()
        val task1 = createTask(title = "Task 1")
        val createItem = OutboxItem.Create(
            id = task1.id,
            task = task1,
            params = CreateTaskParams(id = task1.id, title = task1.title),
            createdAt = "2026-08-21T10:00:00Z"
        )
        store.enqueue(session.operatorId, createItem)

        var errorToThrow: Throwable? = IOException("Failed to create_task: 401 Unauthorized")

        val fakeTaskService = object : TaskService {
            override suspend fun fetchTasks(session: OperatorSession): List<Task> = emptyList()
            override suspend fun fetchTaskById(session: OperatorSession, taskId: String): Task? = null
            override suspend fun createTask(session: OperatorSession, params: CreateTaskParams): Task {
                errorToThrow?.let { throw it }
                return task1
            }
            override suspend fun updateTask(session: OperatorSession, params: UpdateTaskParams): Task = throw NotImplementedError()
            override suspend fun completeTask(session: OperatorSession, taskId: String, expectedVersion: Int, completedAt: String?): Task = throw NotImplementedError()
            override suspend fun uncompleteTask(session: OperatorSession, taskId: String, expectedVersion: Int): Task = throw NotImplementedError()
        }

        val drainer = OutboxDrainer(fakeTaskService, store)
        var errorCalled = false
        val callbacks = object : OutboxDrainCallbacks {
            override suspend fun onError(error: Throwable, item: OutboxItem) {
                errorCalled = true
            }
        }

        // First attempt with 401: retained in outbox, onError not called
        drainer.drain(session, callbacks)
        assertEquals(1, store.getOutbox(session.operatorId).size)
        assertFalse(errorCalled)

        // Second attempt with 429: retained in outbox, onError not called
        errorToThrow = IOException("Failed to create_task: 429 Too Many Requests")
        drainer.drain(session, callbacks)
        assertEquals(1, store.getOutbox(session.operatorId).size)
        assertFalse(errorCalled)

        // Third attempt succeeds
        errorToThrow = null
        drainer.drain(session, callbacks)
        assertEquals(0, store.getOutbox(session.operatorId).size)
        assertFalse(errorCalled)
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

    @Test
    fun testDrainerReconcilesAmbiguousCompletionRetry() = runTest {
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

        val completedTaskOnServer = createTask(
            id = taskId,
            title = "Task already completed on server",
            completedAt = "2026-08-21T10:05:00Z"
        ).copy(version = 2)

        val fakeTaskService = object : TaskService {
            override suspend fun fetchTasks(session: OperatorSession): List<Task> = listOf(completedTaskOnServer)
            override suspend fun fetchTaskById(session: OperatorSession, taskId: String): Task? = completedTaskOnServer
            override suspend fun createTask(session: OperatorSession, params: CreateTaskParams): Task = throw NotImplementedError()
            override suspend fun updateTask(session: OperatorSession, params: UpdateTaskParams): Task = throw NotImplementedError()
            override suspend fun completeTask(session: OperatorSession, taskId: String, expectedVersion: Int, completedAt: String?): Task {
                // First request succeeded on server, but client timed out; retry encounters version conflict
                throw TaskConflictException("Task version conflict: expected 1, found 2")
            }
            override suspend fun uncompleteTask(session: OperatorSession, taskId: String, expectedVersion: Int): Task = throw NotImplementedError()
        }

        val drainer = OutboxDrainer(fakeTaskService, store)
        var completedReported: Task? = null
        var conflictReported: Throwable? = null
        val drainCallbacks = object : OutboxDrainCallbacks {
            override suspend fun onTaskCompleted(task: Task) {
                completedReported = task
            }
            override suspend fun onConflict(error: Throwable, item: OutboxItem) {
                conflictReported = error
            }
        }

        drainer.drain(session, drainCallbacks)

        // Item is removed from outbox and onTaskCompleted is invoked without reporting conflict
        assertEquals(0, store.getOutbox(session.operatorId).size)
        assertEquals(completedTaskOnServer, completedReported)
        assertEquals(null, conflictReported)
    }

    private class FakeSharedPreferences(
        private val data: MutableMap<String, String?> = mutableMapOf(),
        var shouldFailWrite: Boolean = false,
        var shouldFailRead: Boolean = false
    ) : android.content.SharedPreferences {
        override fun getAll(): MutableMap<String, *> = data.toMutableMap()
        override fun getString(key: String?, defValue: String?): String? {
            if (shouldFailRead) throw RuntimeException("Read failed")
            return data[key] ?: defValue
        }
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = throw NotImplementedError()
        override fun getInt(key: String?, defValue: Int): Int = throw NotImplementedError()
        override fun getLong(key: String?, defValue: Long): Long = throw NotImplementedError()
        override fun getFloat(key: String?, defValue: Float): Float = throw NotImplementedError()
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = throw NotImplementedError()
        override fun contains(key: String?): Boolean = data.containsKey(key)
        override fun edit(): android.content.SharedPreferences.Editor = FakeEditor()
        override fun registerOnSharedPreferenceChangeListener(listener: android.content.SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: android.content.SharedPreferences.OnSharedPreferenceChangeListener?) {}

        private inner class FakeEditor : android.content.SharedPreferences.Editor {
            private val pending = mutableMapOf<String, String?>()
            private val toRemove = mutableSetOf<String>()
            private var clear = false

            override fun putString(key: String?, value: String?): android.content.SharedPreferences.Editor {
                if (key != null) pending[key] = value
                return this
            }
            override fun putStringSet(key: String?, values: MutableSet<String>?): android.content.SharedPreferences.Editor = throw NotImplementedError()
            override fun putInt(key: String?, value: Int): android.content.SharedPreferences.Editor = throw NotImplementedError()
            override fun putLong(key: String?, value: Long): android.content.SharedPreferences.Editor = throw NotImplementedError()
            override fun putFloat(key: String?, value: Float): android.content.SharedPreferences.Editor = throw NotImplementedError()
            override fun putBoolean(key: String?, value: Boolean): android.content.SharedPreferences.Editor = throw NotImplementedError()
            override fun remove(key: String?): android.content.SharedPreferences.Editor {
                if (key != null) toRemove.add(key)
                return this
            }
            override fun clear(): android.content.SharedPreferences.Editor {
                clear = true
                return this
            }
            override fun commit(): Boolean {
                if (shouldFailWrite) return false
                if (clear) data.clear()
                for (k in toRemove) data.remove(k)
                for ((k, v) in pending) data[k] = v
                return true
            }
            override fun apply() {
                if (shouldFailWrite) throw RuntimeException("Write failed")
                if (clear) data.clear()
                for (k in toRemove) data.remove(k)
                for ((k, v) in pending) data[k] = v
            }
        }
    }
}
