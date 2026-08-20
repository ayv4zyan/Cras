package com.cras.app.models

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.ResolverStyle
import java.util.UUID

private val DATE_REGEX = Regex("""^\d{4}-\d{2}-\d{2}$""")
private val TIME_REGEX = Regex("""^(?:[01]\d|2[0-3]):[0-5]\d(?::[0-5]\d)?$""")
private val ISO_DATE_TIME_REGEX = Regex("""^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?(?:Z|[+-]\d{2}:\d{2})$""")
private val UUID_REGEX = Regex("""^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$""")
private val ISO_DATE_TIME_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ISO_OFFSET_DATE_TIME.withResolverStyle(ResolverStyle.STRICT)

fun isValidUuid(value: String): Boolean {
    if (!UUID_REGEX.matches(value)) return false
    return try {
        UUID.fromString(value)
        true
    } catch (_: IllegalArgumentException) {
        false
    }
}

private fun isValidIsoDateTime(value: String): Boolean {
    if (!ISO_DATE_TIME_REGEX.matches(value)) return false
    return try {
        OffsetDateTime.parse(value, ISO_DATE_TIME_FORMATTER)
        true
    } catch (_: DateTimeParseException) {
        false
    } catch (_: Exception) {
        false
    }
}

@Serializable(with = PlanSerializer::class)
sealed interface Plan {
    @Serializable
    data class DateOnly(val date: String) : Plan {
        init {
            require(DATE_REGEX.matches(date)) { "Date-only plan date must match YYYY-MM-DD: $date" }
        }
    }

    @Serializable
    data class Floating(val date: String, val time: String) : Plan {
        init {
            require(DATE_REGEX.matches(date)) { "Floating plan date must match YYYY-MM-DD: $date" }
            require(TIME_REGEX.matches(time)) { "Floating plan time must match HH:mm or HH:mm:ss: $time" }
        }
    }

    @Serializable
    data class Instant(val at: String) : Plan {
        init {
            require(isValidIsoDateTime(at)) { "Instant plan at must be a valid ISO 8601 / RFC 3339 date-time: $at" }
        }
    }
}

object PlanSerializer : KSerializer<Plan> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("Plan") {
        element<String?>("type", isOptional = true)
        element<String?>("date", isOptional = true)
        element<String?>("time", isOptional = true)
        element<String?>("at", isOptional = true)
    }

    override fun deserialize(decoder: Decoder): Plan {
        val jsonDecoder = decoder as? JsonDecoder
            ?: throw IllegalStateException("PlanSerializer requires JsonDecoder")
        val element = jsonDecoder.decodeJsonElement()
        if (element !is JsonObject) {
            throw SerializationException("Plan must be a JSON object")
        }

        val allowedFloatingKeys = setOf("type", "date", "time")
        val allowedInstantKeys = setOf("type", "at")
        val allowedDateOnlyKeys = setOf("date")

        val typePrimitive = (element["type"] as? JsonPrimitive)
            ?.takeUnless { it is JsonNull }
            ?.content

        return when {
            typePrimitive == "floating" -> {
                val unknownKeys = element.keys - allowedFloatingKeys
                if (unknownKeys.isNotEmpty()) {
                    throw SerializationException("Floating plan contains unexpected keys: $unknownKeys")
                }
                val date = element["date"]?.jsonPrimitive?.content
                    ?: throw SerializationException("Floating plan requires 'date'")
                val time = element["time"]?.jsonPrimitive?.content
                    ?: throw SerializationException("Floating plan requires 'time'")
                Plan.Floating(date = date, time = time)
            }
            typePrimitive == "instant" -> {
                val unknownKeys = element.keys - allowedInstantKeys
                if (unknownKeys.isNotEmpty()) {
                    throw SerializationException("Instant plan contains unexpected keys: $unknownKeys")
                }
                val at = element["at"]?.jsonPrimitive?.content
                    ?: throw SerializationException("Instant plan requires 'at'")
                Plan.Instant(at = at)
            }
            typePrimitive == null -> {
                val hasExplicitType = element.containsKey("type")
                if (hasExplicitType && element["type"] !is JsonNull) {
                    throw SerializationException("Unknown or invalid plan type: ${element["type"]}")
                }
                val allowedKeys = if (hasExplicitType) setOf("date", "type") else allowedDateOnlyKeys
                val unknownKeys = element.keys - allowedKeys
                if (unknownKeys.isNotEmpty()) {
                    throw SerializationException("Date-only plan contains unexpected keys: $unknownKeys")
                }
                val date = element["date"]?.jsonPrimitive?.content
                    ?: throw SerializationException("Date-only plan requires 'date'")
                Plan.DateOnly(date = date)
            }
            else -> throw SerializationException("Unknown or invalid plan type: $typePrimitive")
        }
    }

    override fun serialize(encoder: Encoder, value: Plan) {
        val jsonElement: JsonElement = when (value) {
            is Plan.DateOnly -> buildJsonObject {
                put("date", JsonPrimitive(value.date))
            }
            is Plan.Floating -> buildJsonObject {
                put("type", JsonPrimitive("floating"))
                put("date", JsonPrimitive(value.date))
                put("time", JsonPrimitive(value.time))
            }
            is Plan.Instant -> buildJsonObject {
                put("type", JsonPrimitive("instant"))
                put("at", JsonPrimitive(value.at))
            }
        }
        val jsonEncoder = encoder as? kotlinx.serialization.json.JsonEncoder
            ?: throw IllegalStateException("PlanSerializer requires JsonEncoder")
        jsonEncoder.encodeJsonElement(jsonElement)
    }
}

@Serializable
data class Task(
    val id: String,
    val title: String,
    val description: String?,
    val priority: Int,
    val plan: Plan?,
    val labels: List<String> = emptyList(),
    val parentId: String?,
    val completedAt: String?,
    val createdAt: String,
    val updatedAt: String,
    val version: Int
) {
    init {
        require(isValidUuid(id)) { "Task id must be a valid UUID: $id" }
        require(title.trim().isNotEmpty()) { "Task title must not be empty" }
        require(priority in 1..4) { "Task priority must be between 1 and 4: $priority" }
        require(parentId == null || isValidUuid(parentId)) { "Task parentId must be a valid UUID: $parentId" }
        require(completedAt == null || isValidIsoDateTime(completedAt)) { "completedAt must be a valid ISO 8601 date-time: $completedAt" }
        require(isValidIsoDateTime(createdAt)) { "createdAt must be a valid ISO 8601 date-time: $createdAt" }
        require(isValidIsoDateTime(updatedAt)) { "updatedAt must be a valid ISO 8601 date-time: $updatedAt" }
        require(labels.distinct().size == labels.size) { "Task labels must be unique" }
        labels.forEach { labelId ->
            require(isValidUuid(labelId)) { "Task label must be a valid UUID: $labelId" }
        }
        require(version >= 1) { "Task version must be at least 1: $version" }
    }
}

@Serializable
data class Comment(
    val id: String,
    val taskId: String,
    val content: String,
    val createdAt: String
) {
    init {
        require(isValidUuid(id)) { "Comment id must be a valid UUID: $id" }
        require(isValidUuid(taskId)) { "Comment taskId must be a valid UUID: $taskId" }
        require(content.trim().isNotEmpty()) { "Comment content must not be empty" }
        require(isValidIsoDateTime(createdAt)) { "Comment createdAt must be a valid ISO 8601 date-time: $createdAt" }
    }
}

val HEX_COLOR_REGEX = Regex("""^#[0-9a-fA-F]{6}$""")
fun isValidHexColor(value: String): Boolean = HEX_COLOR_REGEX.matches(value)

@Serializable(with = LabelSerializer::class)
data class Label(
    val id: String,
    val name: String,
    val color: String,
    val createdAt: String? = null,
    val updatedAt: String? = null
) {
    init {
        require(isValidUuid(id)) { "Label id must be a valid UUID: $id" }
        require(name.trim().isNotEmpty()) { "Label name must not be empty" }
        require(HEX_COLOR_REGEX.matches(color)) { "Label color must be a valid 6-digit hex code: $color" }
        require(createdAt == null || isValidIsoDateTime(createdAt)) { "Label createdAt must be a valid ISO 8601 date-time: $createdAt" }
        require(updatedAt == null || isValidIsoDateTime(updatedAt)) { "Label updatedAt must be a valid ISO 8601 date-time: $updatedAt" }
    }
}

object LabelSerializer : KSerializer<Label> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("Label") {
        element<String>("id")
        element<String>("name")
        element<String>("color")
        element<String?>("createdAt", isOptional = true)
        element<String?>("updatedAt", isOptional = true)
    }

    override fun deserialize(decoder: Decoder): Label {
        val jsonDecoder = decoder as? JsonDecoder
            ?: throw IllegalStateException("LabelSerializer requires JsonDecoder")
        val element = jsonDecoder.decodeJsonElement()
        if (element !is JsonObject) {
            throw SerializationException("Label must be a JSON object")
        }

        val id = element["id"]?.jsonPrimitive?.content
            ?: throw SerializationException("Label requires 'id'")
        val name = element["name"]?.jsonPrimitive?.content
            ?: throw SerializationException("Label requires 'name'")
        val color = element["color"]?.jsonPrimitive?.content
            ?: throw SerializationException("Label requires 'color'")

        val createdAt = element["createdAt"]?.jsonPrimitive?.content
            ?: element["created_at"]?.jsonPrimitive?.content
        val updatedAt = element["updatedAt"]?.jsonPrimitive?.content
            ?: element["updated_at"]?.jsonPrimitive?.content

        return Label(
            id = id,
            name = name,
            color = color,
            createdAt = createdAt,
            updatedAt = updatedAt
        )
    }

    override fun serialize(encoder: Encoder, value: Label) {
        val jsonElement = buildJsonObject {
            put("id", JsonPrimitive(value.id))
            put("name", JsonPrimitive(value.name))
            put("color", JsonPrimitive(value.color))
            if (value.createdAt != null) {
                put("createdAt", JsonPrimitive(value.createdAt))
            }
            if (value.updatedAt != null) {
                put("updatedAt", JsonPrimitive(value.updatedAt))
            }
        }
        val jsonEncoder = encoder as? kotlinx.serialization.json.JsonEncoder
            ?: throw IllegalStateException("LabelSerializer requires JsonEncoder")
        jsonEncoder.encodeJsonElement(jsonElement)
    }
}

data class LabelColorOption(val name: String, val value: String)

object LabelColors {
    val ALL = listOf(
        LabelColorOption("Red", "#ef4444"),
        LabelColorOption("Orange", "#f97316"),
        LabelColorOption("Amber", "#f59e0b"),
        LabelColorOption("Green", "#10b981"),
        LabelColorOption("Teal", "#14b8a6"),
        LabelColorOption("Blue", "#3b82f6"),
        LabelColorOption("Indigo", "#6366f1"),
        LabelColorOption("Purple", "#a855f7"),
        LabelColorOption("Pink", "#ec4899"),
        LabelColorOption("Slate", "#64748b")
    )
    val DEFAULT = ALL[0].value
}

data class TaskPriorityOption(val value: Int, val label: String)

object TaskPriorities {
    const val P1 = 1
    const val P2 = 2
    const val P3 = 3
    const val P4 = 4

    val ALL = listOf(
        TaskPriorityOption(P1, "P1 - Urgent"),
        TaskPriorityOption(P2, "P2 - High"),
        TaskPriorityOption(P3, "P3 - Medium"),
        TaskPriorityOption(P4, "P4 - None")
    )
}
