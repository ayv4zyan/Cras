package com.cras.app.data

import com.cras.app.auth.OperatorSession
import com.cras.app.config.PublicSupabaseConfig
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@Serializable
data class InvalidationPayload(
    val resource: String,
    val id: String,
    val operation: String,
    val parentId: String? = null,
    val taskId: String? = null
)

/**
 * Validates and sanitizes a raw event payload, ensuring only resource identity,
 * operation, and necessary parent identity are extracted without revealing
 * private domain content (titles, notes, descriptions, etc.).
 */
fun parseInvalidationPayload(element: JsonElement?): InvalidationPayload? {
    if (element !is JsonObject) return null

    val resource = (element["resource"] as? JsonPrimitive)?.content ?: return null
    if (resource !in setOf("task", "label", "comment")) return null

    val id = (element["id"] as? JsonPrimitive)?.content ?: return null
    if (id.isBlank()) return null

    val operation = (element["operation"] as? JsonPrimitive)?.content ?: return null
    if (operation !in setOf("created", "updated", "deleted")) return null

    val parentId = (element["parentId"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
    val taskId = (element["taskId"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }

    return InvalidationPayload(
        resource = resource,
        id = id,
        operation = operation,
        parentId = parentId,
        taskId = taskId
    )
}

fun parseInvalidationPayload(rawJson: String?): InvalidationPayload? {
    if (rawJson.isNullOrBlank()) return null
    return try {
        val json = Json { ignoreUnknownKeys = true }
        val element = json.parseToJsonElement(rawJson)
        parseInvalidationPayload(element)
    } catch (_: Exception) {
        null
    }
}

interface RealtimeSubscription {
    fun unsubscribe()
}

interface RealtimeService {
    fun subscribeToInvalidations(
        session: OperatorSession,
        onInvalidate: (InvalidationPayload) -> Unit,
        onReconnect: (() -> Unit)? = null
    ): RealtimeSubscription
}

class SupabaseRealtimeService(
    private val config: PublicSupabaseConfig,
    private val httpClient: OkHttpClient = OkHttpClient(),
    private val json: Json = Json { ignoreUnknownKeys = true }
) : RealtimeService {

    override fun subscribeToInvalidations(
        session: OperatorSession,
        onInvalidate: (InvalidationPayload) -> Unit,
        onReconnect: (() -> Unit)?
    ): RealtimeSubscription {
        val wsUrl = buildWebSocketUrl(config.url, config.publishableKey)
        val topic = "realtime:operator:${session.operatorId}"

        var wasDisconnected = false
        var isInitialConnect = true
        var isCancelled = false

        val joinRef = "1"
        val refCounter = AtomicInteger(1)

        val request = Request.Builder()
            .url(wsUrl)
            .build()

        val socketRef = AtomicReference<WebSocket?>(null)

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (isCancelled) {
                    webSocket.close(1000, "Unsubscribed")
                    return
                }

                // Send phx_join to operator topic
                val joinMessage = buildJsonObject {
                    put("topic", topic)
                    put("event", "phx_join")
                    put("payload", buildJsonObject {
                        putJsonObject("config") {
                            putJsonObject("broadcast") {
                                put("ack", false)
                                put("self", false)
                            }
                            putJsonObject("presence") {
                                put("key", "")
                                put("enabled", false)
                            }
                            put("private", true)
                        }
                        put("access_token", session.accessToken)
                    })
                    put("ref", refCounter.getAndIncrement().toString())
                    put("join_ref", joinRef)
                }.toString()

                webSocket.send(joinMessage)

                if (!isInitialConnect && wasDisconnected) {
                    onReconnect?.invoke()
                }
                isInitialConnect = false
                wasDisconnected = false
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (isCancelled) return
                try {
                    val root = json.parseToJsonElement(text)
                    if (root is JsonObject) {
                        val event = (root["event"] as? JsonPrimitive)?.content
                        val payload = root["payload"]

                        if (event == "broadcast" || event == "invalidate") {
                            val innerPayload = if (payload is JsonObject) {
                                (payload["payload"] as? JsonObject) ?: payload
                            } else null

                            val parsed = parseInvalidationPayload(innerPayload)
                            if (parsed != null) {
                                onInvalidate(parsed)
                            }
                        }
                    } else if (root is JsonArray && root.size >= 5) {
                        val event = (root[3] as? JsonPrimitive)?.content
                        val payload = root[4]
                        if (event == "broadcast" || event == "invalidate") {
                            val innerPayload = if (payload is JsonObject) {
                                (payload["payload"] as? JsonObject) ?: payload
                            } else null
                            val parsed = parseInvalidationPayload(innerPayload)
                            if (parsed != null) {
                                onInvalidate(parsed)
                            }
                        }
                    }
                } catch (_: Exception) {
                    // Ignore malformed message
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                wasDisconnected = true
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                wasDisconnected = true
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                wasDisconnected = true
            }
        }

        val webSocket = httpClient.newWebSocket(request, listener)
        socketRef.set(webSocket)

        return object : RealtimeSubscription {
            override fun unsubscribe() {
                isCancelled = true
                val ws = socketRef.getAndSet(null)
                if (ws != null) {
                    try {
                        val leaveMessage = buildJsonObject {
                            put("topic", topic)
                            put("event", "phx_leave")
                            put("payload", buildJsonObject {})
                            put("ref", refCounter.getAndIncrement().toString())
                            put("join_ref", joinRef)
                        }.toString()
                        ws.send(leaveMessage)
                    } catch (_: Exception) {}
                    try {
                        ws.close(1000, "Unsubscribed")
                    } catch (_: Exception) {}
                }
            }
        }
    }

    private fun buildWebSocketUrl(baseUrl: String, apiKey: String): String {
        val wsUrl = baseUrl
            .replaceFirst(Regex("^http://", RegexOption.IGNORE_CASE), "ws://")
            .replaceFirst(Regex("^https://", RegexOption.IGNORE_CASE), "wss://")
            .removeSuffix("/")
        return "$wsUrl/realtime/v1/websocket?apikey=$apiKey&vsn=1.0.0"
    }
}
