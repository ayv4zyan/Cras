package com.cras.app.voice

import com.cras.app.auth.OperatorSession
import com.cras.app.config.PublicSupabaseConfig
import com.cras.app.domain.CreatePlanParams
import com.cras.app.domain.TimedPlanType
import com.cras.app.domain.createPlanFromInputs
import com.cras.app.models.Plan
import com.cras.app.models.PlanSerializer
import com.cras.app.models.Task
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.time.Instant
import java.time.ZoneId

/**
 * A failed Voice capture attempt. Mirrors the web VoiceError shape so clients
 * can distinguish allowance, circuit-breaker, provider and network failures.
 */
class VoiceError(
    val status: Int,
    val code: String?,
    override val message: String,
    val earliestRetryAt: String? = null,
    val retryAfterSeconds: Int? = null,
    val isNetworkError: Boolean = false,
) : Exception(message)

data class VoiceCaptureRequestOptions(
    val audioWav: ByteArray,
    val recordingStartTime: Instant,
    val timezone: String,
    val focusedTask: Task? = null,
    val existingDrafts: List<DraftTask>? = null,
    val effectiveDefaultTimedPlanType: TimedPlanType = TimedPlanType.INSTANT,
)

data class VoiceCaptureResult(
    val transcript: String,
    val mode: VoiceCaptureMode,
    val drafts: List<DraftTask>,
    val editProposal: DraftTask? = null,
)

/** Sends Voice captures to the shared secure boundary (voice-capture Edge Function). */
interface VoiceCaptureApi {
    suspend fun sendVoiceCapture(
        session: OperatorSession,
        options: VoiceCaptureRequestOptions,
    ): VoiceCaptureResult
}

class SupabaseVoiceCaptureApi(
    private val config: PublicSupabaseConfig,
    private val httpClient: OkHttpClient = OkHttpClient(),
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val zoneIdProvider: () -> ZoneId = { ZoneId.systemDefault() },
) : VoiceCaptureApi {

    override suspend fun sendVoiceCapture(
        session: OperatorSession,
        options: VoiceCaptureRequestOptions,
    ): VoiceCaptureResult = withContext(Dispatchers.IO) {
        val endpoint = config.url.trimEnd('/') + "/functions/v1/voice-capture"

        val requestBody = okhttp3.MultipartBody.Builder()
            .setType(okhttp3.MultipartBody.FORM)
            .addFormDataPart(
                "audio",
                "audio.wav",
                options.audioWav.toRequestBody(AUDIO_MEDIA_TYPE),
            )
            .addFormDataPart("recording_start_time", options.recordingStartTime.toString())
            .addFormDataPart("timezone", options.timezone)

        options.focusedTask?.let { task ->
            requestBody.addFormDataPart("focused_task", focusedTaskJson(task))
        }

        val draftsSummary = options.existingDrafts
        if (!draftsSummary.isNullOrEmpty()) {
            requestBody.addFormDataPart("existing_drafts", existingDraftsJson(draftsSummary))
        }

        val request = Request.Builder()
            .url(endpoint)
            .header("Authorization", "Bearer ${session.accessToken}")
            .post(requestBody.build())
            .build()

        val response = try {
            httpClient.newCall(request).execute()
        } catch (_: IOException) {
            throw VoiceError(
                status = 0,
                code = "network_error",
                message = "Network error: unable to reach Voice service. Your recording is preserved.",
                isNetworkError = true,
            )
        }

        response.use { resp ->
            val bodyString = resp.body?.string().orEmpty()

            if (!resp.isSuccessful) {
                throw parseErrorResponse(resp.code, bodyString)
            }

            parseSuccessResponse(bodyString, options)
        }
    }

    private fun parseErrorResponse(status: Int, bodyString: String): VoiceError {
        val obj = runCatching { json.parseToJsonElement(bodyString).jsonObject }.getOrNull()

        val errorCode = obj?.get("code")?.jsonPrimitive?.contentOrNull
        val errorMessage = obj?.get("error")?.jsonPrimitive?.contentOrNull
            ?: when (status) {
                503 -> "Voice capture is temporarily unavailable. Please try again later."
                429 -> "Voice allowance or rate limit reached."
                else -> "Voice capture failed."
            }

        return VoiceError(
            status = status,
            code = errorCode ?: "voice_error",
            message = errorMessage,
            earliestRetryAt = obj?.get("earliest_retry_at")?.jsonPrimitive?.contentOrNull,
            retryAfterSeconds = obj?.get("retry_after_seconds")?.jsonPrimitive?.intOrNull,
        )
    }

    private fun parseSuccessResponse(
        bodyString: String,
        options: VoiceCaptureRequestOptions,
    ): VoiceCaptureResult {
        val root = runCatching { json.parseToJsonElement(bodyString).jsonObject }.getOrNull()
            ?: throw VoiceError(500, "voice_error", "Voice capture returned an unreadable response.")

        val transcript = root["transcript"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val rawMode = root["mode"]?.jsonPrimitive?.contentOrNull
        val mode = if (rawMode == null && options.focusedTask != null) {
            VoiceCaptureMode.EDIT
        } else {
            VoiceCaptureMode.fromValue(rawMode)
        }

        val extractedDrafts = root["drafts"]
            ?.takeIf { it !is JsonNull }
            ?.jsonArray
            ?.mapNotNull { element ->
                runCatching {
                    json.decodeFromJsonElement<ExtractedDraftPayload>(element)
                }.getOrNull()
            }
            .orEmpty()

        val extractedEdit = root["edit"]
            ?.takeIf { it !is JsonNull }
            ?.let { element ->
                runCatching {
                    json.decodeFromJsonElement<ExtractedEditPayload>(element)
                }.getOrNull()
            }

        return buildVoiceCaptureResult(
            transcript = transcript,
            mode = mode,
            extractedDrafts = extractedDrafts,
            extractedEdit = extractedEdit,
            focusedTask = options.focusedTask,
            existingDrafts = options.existingDrafts,
            effectiveDefaultTimedPlanType = options.effectiveDefaultTimedPlanType,
            zoneId = zoneIdProvider(),
        )
    }

    private fun focusedTaskJson(task: Task): String = buildJsonObject {
        put("id", task.id)
        put("title", task.title)
        put("description", task.description)
        put("priority", task.priority)
        put(
            "plan",
            task.plan?.let { json.encodeToJsonElement(PlanSerializer, it) } ?: JsonNull
        )
    }.toString()

    private fun existingDraftsJson(drafts: List<DraftTask>): String = buildJsonArray {
        drafts.forEachIndexed { index, draft ->
            add(
                buildJsonObject {
                    put("target_draft_index", index)
                    put("title", draft.title)
                    put("description", draft.description)
                    put("priority", draft.priority)
                    put("plan_date", formatPlanDate(draft.plan))
                    put("plan_time", formatPlanTime(draft.plan))
                    put("plan_type", timedPlanTypeOf(draft.plan)?.value)
                }
            )
        }
    }.toString()

    companion object {
        private val AUDIO_MEDIA_TYPE = "audio/wav".toMediaType()
    }
}

/**
 * Maps a successful boundary response into DraftTasks, mirroring voiceService.ts:
 * - Edit mode builds an edit proposal against the focused Task, preserving its
 *   existing timed plan type unless speech explicitly changed it.
 * - Create/correction mode merges updates into existing drafts by
 *   target_draft_index or case-insensitive title and appends brand-new drafts.
 */
fun buildVoiceCaptureResult(
    transcript: String,
    mode: VoiceCaptureMode,
    extractedDrafts: List<ExtractedDraftPayload>,
    extractedEdit: ExtractedEditPayload?,
    focusedTask: Task?,
    existingDrafts: List<DraftTask>?,
    effectiveDefaultTimedPlanType: TimedPlanType,
    zoneId: ZoneId = ZoneId.systemDefault(),
    newId: () -> String = ::defaultDraftId,
): VoiceCaptureResult {
    if (mode == VoiceCaptureMode.EDIT && focusedTask != null) {
        val editDraft = if (extractedEdit != null) {
            buildEditDraftFromExtracted(
                editPayload = extractedEdit,
                focusedTask = focusedTask,
                effectiveDefaultTimedPlanType = effectiveDefaultTimedPlanType,
                zoneId = zoneId,
                newId = newId,
            )
        } else {
            // No usable extraction: propose keeping the focused Task untouched.
            DraftTask(
                id = newId(),
                title = focusedTask.title,
                description = focusedTask.description,
                priority = focusedTask.priority,
                plan = focusedTask.plan,
                labels = focusedTask.labels.toList(),
                parentId = focusedTask.parentId,
                originalTaskId = focusedTask.id,
                validationError = null,
            )
        }

        return VoiceCaptureResult(
            transcript = transcript,
            mode = VoiceCaptureMode.EDIT,
            drafts = listOf(editDraft),
            editProposal = editDraft,
        )
    }

    // Create or Correction mode
    val previousDrafts = existingDrafts.orEmpty()
    val finalDrafts: List<DraftTask> = if (previousDrafts.isNotEmpty()) {
        // Merge Voice correction updates into existing drafts
        val merged = previousDrafts.mapIndexed { index, prevDraft ->
            val match = extractedDrafts.firstOrNull { ed ->
                ed.target_draft_index == index ||
                    ed.title?.lowercase() == prevDraft.title.lowercase()
            }
            if (match != null) {
                createDraftTaskFromExtracted(
                    payload = match,
                    effectiveDefault = effectiveDefaultTimedPlanType,
                    originalTaskId = prevDraft.originalTaskId,
                    zoneId = zoneId,
                    newId = newId,
                )
            } else {
                prevDraft
            }
        }.toMutableList()

        // Add any completely new drafts
        val newItems = extractedDrafts.filter {
            it.target_draft_index == null || it.target_draft_index >= previousDrafts.size
        }
        for (item in newItems) {
            merged += createDraftTaskFromExtracted(
                payload = item,
                effectiveDefault = effectiveDefaultTimedPlanType,
                originalTaskId = null,
                zoneId = zoneId,
                newId = newId,
            )
        }
        merged.toList()
    } else {
        extractedDrafts.map { payload ->
            createDraftTaskFromExtracted(
                payload = payload,
                effectiveDefault = effectiveDefaultTimedPlanType,
                originalTaskId = null,
                zoneId = zoneId,
                newId = newId,
            )
        }
    }

    return VoiceCaptureResult(
        transcript = transcript,
        mode = VoiceCaptureMode.CREATE,
        drafts = finalDrafts,
    )
}

private fun buildEditDraftFromExtracted(
    editPayload: ExtractedEditPayload,
    focusedTask: Task,
    effectiveDefaultTimedPlanType: TimedPlanType,
    zoneId: ZoneId,
    newId: () -> String,
): DraftTask {
    val newTitle = editPayload.title?.trim()?.ifEmpty { null } ?: focusedTask.title
    // The strict wire schema always carries description/priority/plan fields;
    // null means "not stated by speech", falling back to the focused Task.
    val newDescription = editPayload.description ?: focusedTask.description
    val newPriority = editPayload.priority?.takeIf { it in 1..4 } ?: focusedTask.priority

    var newPlan = focusedTask.plan
    var validationError: String? = null

    if (editPayload.clear_plan == true) {
        newPlan = null
    } else if (!editPayload.plan_date.isNullOrBlank()) {
        val date = editPayload.plan_date.trim()
        val time = editPayload.plan_time?.trim()?.ifEmpty { null }
        // Preserves existing Instant/Floating type if already timed unless speech explicitly changed it
        val existingType = timedPlanTypeOf(focusedTask.plan)
        val chosenType = TimedPlanType.fromValue(editPayload.plan_type)
            ?: existingType
            ?: effectiveDefaultTimedPlanType

        if (time != null) {
            newPlan = createPlanFromInputs(
                CreatePlanParams(
                    date = date,
                    time = time,
                    type = chosenType,
                    effectiveDefault = effectiveDefaultTimedPlanType,
                    zoneId = zoneId,
                )
            ) ?: newPlan
        } else {
            if (TimedPlanType.fromValue(editPayload.plan_type) != null) {
                validationError =
                    "An explicit Instant or Floating plan requires a clock time."
            }
            newPlan = Plan.DateOnly(date = date)
        }
    }

    return DraftTask(
        id = newId(),
        title = newTitle,
        description = newDescription,
        priority = newPriority,
        plan = newPlan,
        labels = focusedTask.labels.toList(),
        parentId = focusedTask.parentId,
        originalTaskId = focusedTask.id,
        validationError = validationError,
    )
}
