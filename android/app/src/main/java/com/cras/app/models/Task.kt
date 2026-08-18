package com.cras.app.models

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive

@Serializable(with = PlanSerializer::class)
sealed interface Plan {
    @Serializable
    data class DateOnly(val date: String) : Plan

    @Serializable
    data class Floating(val date: String, val time: String) : Plan

    @Serializable
    data class Instant(val at: String) : Plan
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
            throw IllegalArgumentException("Plan must be a JSON object")
        }

        val typePrimitive = element["type"]?.jsonPrimitive?.content
        return when (typePrimitive) {
            "floating" -> {
                val date = element["date"]?.jsonPrimitive?.content
                    ?: throw IllegalArgumentException("Floating plan requires 'date'")
                val time = element["time"]?.jsonPrimitive?.content
                    ?: throw IllegalArgumentException("Floating plan requires 'time'")
                Plan.Floating(date = date, time = time)
            }
            "instant" -> {
                val at = element["at"]?.jsonPrimitive?.content
                    ?: throw IllegalArgumentException("Instant plan requires 'at'")
                Plan.Instant(at = at)
            }
            null -> {
                val date = element["date"]?.jsonPrimitive?.content
                    ?: throw IllegalArgumentException("Date-only plan requires 'date'")
                Plan.DateOnly(date = date)
            }
            else -> throw IllegalArgumentException("Unknown plan type: $typePrimitive")
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
    val description: String? = null,
    val priority: Int,
    val plan: Plan? = null,
    val labels: List<String> = emptyList(),
    val parentId: String? = null,
    val completedAt: String? = null,
    val createdAt: String,
    val updatedAt: String,
    val version: Int
)
