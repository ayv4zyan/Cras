package com.cras.app.data

import com.cras.app.auth.OperatorSession
import com.cras.app.models.Task
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.IOException

@Serializable
sealed interface OutboxItem {
    val id: String
    val createdAt: String

    @Serializable
    @SerialName("create")
    data class Create(
        override val id: String,
        val task: Task,
        val params: CreateTaskParams,
        override val createdAt: String
    ) : OutboxItem

    @Serializable
    @SerialName("complete")
    data class Complete(
        override val id: String,
        val taskId: String,
        val expectedVersion: Int,
        val completedAt: String,
        override val createdAt: String
    ) : OutboxItem
}

interface OutboxStore {
    fun getOutbox(operatorId: String): List<OutboxItem>
    fun saveOutbox(operatorId: String, items: List<OutboxItem>)
    fun enqueue(operatorId: String, item: OutboxItem) {
        val current = getOutbox(operatorId)
        saveOutbox(operatorId, current + item)
    }
    fun remove(operatorId: String, itemId: String) {
        val current = getOutbox(operatorId)
        saveOutbox(operatorId, current.filterNot { it.id == itemId })
    }
    fun clear(operatorId: String) {
        saveOutbox(operatorId, emptyList())
    }
}

class InMemoryOutboxStore : OutboxStore {
    private val store = mutableMapOf<String, List<OutboxItem>>()

    @Synchronized
    override fun getOutbox(operatorId: String): List<OutboxItem> =
        store[operatorId]?.toList() ?: emptyList()

    @Synchronized
    override fun saveOutbox(operatorId: String, items: List<OutboxItem>) {
        store[operatorId] = items.toList()
    }

    @Synchronized
    override fun enqueue(operatorId: String, item: OutboxItem) {
        val current = store[operatorId] ?: emptyList()
        store[operatorId] = current + item
    }

    @Synchronized
    override fun remove(operatorId: String, itemId: String) {
        val current = store[operatorId] ?: emptyList()
        store[operatorId] = current.filterNot { it.id == itemId }
    }

    @Synchronized
    override fun clear(operatorId: String) {
        store.remove(operatorId)
    }
}

class SharedPreferencesOutboxStore(
    private val preferences: android.content.SharedPreferences,
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : OutboxStore {
    private val inMemoryFallback = InMemoryOutboxStore()
    private val staleOperators = mutableSetOf<String>()

    private fun getStorageKey(operatorId: String) = "cras_outbox_$operatorId"

    private fun readPersisted(operatorId: String): List<OutboxItem>? {
        val serialized = try {
            preferences.getString(getStorageKey(operatorId), null)
        } catch (_: Exception) {
            return null
        } ?: return null

        return try {
            json.decodeFromString<List<OutboxItem>>(serialized)
        } catch (_: Exception) {
            null
        }
    }

    private fun writePersisted(operatorId: String, items: List<OutboxItem>): Boolean {
        return try {
            val serialized = json.encodeToString(items)
            runBlocking(ioDispatcher) {
                preferences.edit().putString(getStorageKey(operatorId), serialized).commit()
            }
        } catch (_: Exception) {
            false
        }
    }

    @Synchronized
    override fun getOutbox(operatorId: String): List<OutboxItem> {
        if (staleOperators.contains(operatorId)) {
            return inMemoryFallback.getOutbox(operatorId)
        }
        val persisted = readPersisted(operatorId)
        return persisted ?: inMemoryFallback.getOutbox(operatorId)
    }

    @Synchronized
    override fun saveOutbox(operatorId: String, items: List<OutboxItem>) {
        inMemoryFallback.saveOutbox(operatorId, items)
        if (writePersisted(operatorId, items)) {
            staleOperators.remove(operatorId)
        } else {
            staleOperators.add(operatorId)
        }
    }

    @Synchronized
    override fun enqueue(operatorId: String, item: OutboxItem) {
        val current = getOutbox(operatorId)
        saveOutbox(operatorId, current + item)
    }

    @Synchronized
    override fun remove(operatorId: String, itemId: String) {
        val current = getOutbox(operatorId)
        saveOutbox(operatorId, current.filterNot { it.id == itemId })
    }

    @Synchronized
    override fun clear(operatorId: String) {
        inMemoryFallback.clear(operatorId)
        staleOperators.remove(operatorId)
        try {
            runBlocking(ioDispatcher) {
                preferences.edit().remove(getStorageKey(operatorId)).commit()
            }
        } catch (_: Exception) {
            // Ignore
        }
    }
}

fun applyOutboxToTasks(
    canonicalTasks: List<Task>,
    outboxItems: List<OutboxItem>
): List<Task> {
    var result = canonicalTasks.toMutableList()
    var insertIndex = 0

    for (item in outboxItems) {
        when (item) {
            is OutboxItem.Create -> {
                val exists = result.any { it.id == item.task.id }
                if (!exists) {
                    result.add(insertIndex++, item.task)
                }
            }
            is OutboxItem.Complete -> {
                result = result.map { task ->
                    if (task.id == item.taskId && task.completedAt == null) {
                        task.copy(
                            completedAt = item.completedAt,
                            updatedAt = item.completedAt
                        )
                    } else {
                        task
                    }
                }.toMutableList()
            }
        }
    }

    return result
}

fun isNetworkError(error: Throwable): Boolean {
    if (error is CancellationException) return false
    if (error is TaskConflictException) return false
    if (error is IllegalArgumentException) return false

    val msg = error.message ?: ""
    val httpCodeMatch = Regex("""Failed to .*: (\d{3}) """).find(msg)
    if (httpCodeMatch != null) {
        val code = httpCodeMatch.groupValues[1].toIntOrNull()
        if (code == 401 || code == 429) {
            return true
        }
        if (code != null && code in 400..499) {
            return false
        }
        if (code != null && code in 500..599) {
            return true
        }
    }

    if (error is IOException) {
        return true
    }

    val lowerMsg = msg.lowercase()
    if (lowerMsg.contains("failed to fetch") ||
        lowerMsg.contains("network error") ||
        lowerMsg.contains("networkerror") ||
        lowerMsg.contains("networkrequestfailed") ||
        lowerMsg.contains("fetcherror") ||
        lowerMsg.contains("load failed") ||
        lowerMsg.contains("timed out") ||
        lowerMsg.contains("timeout") ||
        lowerMsg.contains("offline") ||
        lowerMsg.contains("connection refused") ||
        lowerMsg.contains("no route to host") ||
        lowerMsg.contains("host unreachable") ||
        lowerMsg.contains("broken pipe") ||
        lowerMsg.contains("unexpected end of stream") ||
        lowerMsg.contains("socket closed") ||
        lowerMsg.contains("connection reset") ||
        lowerMsg.contains("stream was reset")
    ) {
        return true
    }
    return false
}

interface OutboxDrainCallbacks {
    suspend fun onTaskCreated(task: Task) {}
    suspend fun onTaskCompleted(task: Task) {}
    suspend fun onConflict(error: Throwable, item: OutboxItem) {}
    suspend fun onError(error: Throwable, item: OutboxItem) {}

    /** Invoked when draining stops because an item hit a network failure. */
    suspend fun onNetworkError(error: Throwable, item: OutboxItem) {}
}

class OutboxDrainer(
    private val taskService: TaskService,
    private val outboxStore: OutboxStore
) {
    private val mutex = Mutex()

    suspend fun drain(
        session: OperatorSession,
        callbacks: OutboxDrainCallbacks
    ) {
        mutex.withLock {
            while (true) {
                val items = outboxStore.getOutbox(session.operatorId)
                if (items.isEmpty()) break

                val item = items.first()
                when (item) {
                    is OutboxItem.Create -> {
                        try {
                            val created = taskService.createTask(session, item.params)
                            outboxStore.remove(session.operatorId, item.id)
                            callbacks.onTaskCreated(created)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Throwable) {
                            if (isNetworkError(e)) {
                                callbacks.onNetworkError(e, item)
                                break
                            }
                            // Check if task already exists on server (e.g. prior unacknowledged attempt)
                            val existing = try {
                                taskService.fetchTaskById(session, item.task.id)
                            } catch (ce: CancellationException) {
                                throw ce
                            } catch (_: Throwable) {
                                null
                            }

                            if (existing != null) {
                                outboxStore.remove(session.operatorId, item.id)
                                callbacks.onTaskCreated(existing)
                                continue
                            }

                            outboxStore.remove(session.operatorId, item.id)
                            callbacks.onError(e, item)
                        }
                    }
                    is OutboxItem.Complete -> {
                        try {
                            val completed = taskService.completeTask(
                                session = session,
                                taskId = item.taskId,
                                expectedVersion = item.expectedVersion,
                                completedAt = item.completedAt
                            )
                            outboxStore.remove(session.operatorId, item.id)
                            callbacks.onTaskCompleted(completed)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Throwable) {
                            if (isNetworkError(e)) {
                                callbacks.onNetworkError(e, item)
                                break
                            }
                            if (e is TaskConflictException || (e.message?.contains("version conflict", ignoreCase = true) == true)) {
                                // Check if task is already completed on server (e.g. prior unacknowledged attempt committed before transport failure)
                                val existing = try {
                                    taskService.fetchTaskById(session, item.taskId)
                                } catch (ce: CancellationException) {
                                    throw ce
                                } catch (_: Throwable) {
                                    null
                                }

                                if (existing != null && existing.completedAt != null) {
                                    outboxStore.remove(session.operatorId, item.id)
                                    callbacks.onTaskCompleted(existing)
                                    continue
                                }

                                outboxStore.remove(session.operatorId, item.id)
                                callbacks.onConflict(e, item)
                            } else {
                                outboxStore.remove(session.operatorId, item.id)
                                callbacks.onError(e, item)
                            }
                        }
                    }
                }
            }
        }
    }
}
