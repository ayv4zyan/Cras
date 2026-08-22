package com.cras.app.ui.voice

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cras.app.domain.CreatePlanParams
import com.cras.app.domain.TimedPlanType
import com.cras.app.domain.createPlanFromInputs
import com.cras.app.models.Plan
import com.cras.app.models.TaskPriorities
import com.cras.app.voice.DraftTask
import com.cras.app.voice.RetainedRecording
import com.cras.app.voice.formatPlanDate
import com.cras.app.voice.formatPlanTime
import java.time.ZoneId

/**
 * Functional-but-plain Voice capture dialog covering recording, processing,
 * Draft (create/edit/correction), rejection via per-draft validation errors,
 * and every unavailable-Voice state. Visual polish is a later designer pass.
 */
@Composable
fun VoiceCaptureDialog(
    uiState: VoiceUiState,
    effectiveDefault: TimedPlanType,
    retainedRecordings: List<RetainedRecording>,
    focusedTaskTitle: String?,
    onStartRecording: () -> Unit,
    onStopAndProcess: () -> Unit,
    onCancelRecording: () -> Unit,
    onRetryProcessing: () -> Unit,
    onCorrectByVoice: () -> Unit,
    onStartOver: () -> Unit,
    onDraftChange: (index: Int, draft: DraftTask) -> Unit,
    onSwitchDraftPlanType: (Int, TimedPlanType) -> Unit,
    onRemoveDraft: (Int) -> Unit,
    onDeleteRetained: (String) -> Unit,
    onDeleteAllRetained: () -> Unit,
    onRequestMicPermission: () -> Unit,
    onAcceptCreate: (List<DraftTask>) -> Unit,
    onAcceptEdit: (DraftTask) -> Unit,
    onDismiss: () -> Unit,
    zoneId: ZoneId = ZoneId.systemDefault(),
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Mic, contentDescription = null)
                Spacer(Modifier.padding(4.dp))
                Text(
                    text = when {
                        focusedTaskTitle != null -> "Voice Edit Task"
                        else -> "Voice Capture"
                    },
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                when (val state = uiState) {
                    VoiceUiState.Idle -> {
                        Text("Ready to record.")
                        Button(onClick = onStartRecording, modifier = Modifier.fillMaxWidth()) {
                            Text("Start recording")
                        }
                        if (retainedRecordings.isNotEmpty()) {
                            RetainedSection(
                                retainedRecordings = retainedRecordings,
                                onDeleteRetained = onDeleteRetained,
                                onDeleteAllRetained = onDeleteAllRetained,
                            )
                        }
                    }

                    is VoiceUiState.Recording -> {
                        RecordingBody(state = state)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = onStopAndProcess) { Text("Done") }
                            OutlinedButton(onClick = onCancelRecording) { Text("Cancel") }
                        }
                    }

                    VoiceUiState.Processing -> ProcessingBody()

                    is VoiceUiState.Drafts -> DraftsBody(
                        state = state,
                        effectiveDefault = effectiveDefault,
                        focusedTaskTitle = focusedTaskTitle,
                        zoneId = zoneId,
                        onCorrectByVoice = onCorrectByVoice,
                        onStartOver = onStartOver,
                        onDraftChange = onDraftChange,
                        onSwitchDraftPlanType = onSwitchDraftPlanType,
                        onRemoveDraft = onRemoveDraft,
                        onAcceptCreate = onAcceptCreate,
                        onAcceptEdit = onAcceptEdit,
                    )

                    is VoiceUiState.Failed -> FailedBody(
                        failure = state.failure,
                        canRetryWithSavedAudio = state.canRetryWithSavedAudio,
                        retainedRecordings = retainedRecordings,
                        onRetryProcessing = onRetryProcessing,
                        onStartRecording = onStartRecording,
                        onRequestMicPermission = onRequestMicPermission,
                        onDeleteRetained = onDeleteRetained,
                        onDeleteAllRetained = onDeleteAllRetained,
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
    )
}

@Composable
private fun RecordingBody(state: VoiceUiState.Recording) {
    val totalSeconds = state.elapsedMs / 1000
    val maxSeconds = state.maxDurationMs / 1000
    val remaining = (maxSeconds - totalSeconds).coerceAtLeast(0)
    Text(
        text = "Listening... %02d:%02d / %02d:%02d".format(
            totalSeconds / 60, totalSeconds % 60, maxSeconds / 60, maxSeconds % 60
        ),
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.SemiBold,
    )
    Text(
        text = "Auto-stops in ${remaining}s. Speak naturally with titles, dates and priorities.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun ProcessingBody() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CircularProgressIndicator(modifier = Modifier.height(24.dp))
        Text("Transcribing and extracting task metadata...")
    }
}

@Composable
private fun DraftsBody(
    state: VoiceUiState.Drafts,
    effectiveDefault: TimedPlanType,
    focusedTaskTitle: String?,
    zoneId: ZoneId,
    onCorrectByVoice: () -> Unit,
    onStartOver: () -> Unit,
    onDraftChange: (Int, DraftTask) -> Unit,
    onSwitchDraftPlanType: (Int, TimedPlanType) -> Unit,
    onRemoveDraft: (Int) -> Unit,
    onAcceptCreate: (List<DraftTask>) -> Unit,
    onAcceptEdit: (DraftTask) -> Unit,
) {
    val isEditMode = state.mode == com.cras.app.voice.VoiceCaptureMode.EDIT && state.editProposal != null

    if (state.transcript.isNotBlank()) {
        Text(
            text = "Heard: \"${state.transcript}\"",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    // Rejection banner: invalid plan entries must be corrected before accept.
    if (state.hasValidationErrors) {
        Text(
            text = "Please correct invalid plan entries below (an explicit Instant or Floating plan requires a clock time) before accepting.",
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
        )
    }

    Text(
        text = if (focusedTaskTitle != null || isEditMode) {
            "Proposed Change (Editable Draft):"
        } else {
            "Proposed Tasks (${state.drafts.size}):"
        },
        style = MaterialTheme.typography.labelLarge,
    )
    Text(
        text = "Not saved until you click Accept",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    state.drafts.forEachIndexed { index, draft ->
        DraftCard(
            index = index,
            draft = draft,
            showRemove = !isEditMode && state.drafts.size > 1,
            effectiveDefault = effectiveDefault,
            zoneId = zoneId,
            onDraftChange = onDraftChange,
            onSwitchDraftPlanType = onSwitchDraftPlanType,
            onRemoveDraft = onRemoveDraft,
        )
    }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = onCorrectByVoice) { Text("Correct by Voice") }
        TextButton(onClick = onStartOver) { Text("Start over") }
    }

    val hasErrors = state.hasValidationErrors || state.drafts.isEmpty()
    Button(
        onClick = {
            val valid = state.drafts.filter { it.validationError == null }
            if (!isEditMode) {
                onAcceptCreate(valid)
            } else if (valid.isNotEmpty()) {
                onAcceptEdit(valid.first())
            }
        },
        enabled = !hasErrors,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(if (isEditMode) "Accept Changes" else "Accept All")
    }
}

@Composable
private fun DraftCard(
    index: Int,
    draft: DraftTask,
    showRemove: Boolean,
    effectiveDefault: TimedPlanType,
    zoneId: ZoneId,
    onDraftChange: (Int, DraftTask) -> Unit,
    onSwitchDraftPlanType: (Int, TimedPlanType) -> Unit,
    onRemoveDraft: (Int) -> Unit,
) {
    var priorityMenuOpen by remember { mutableStateOf(false) }
    var typeMenuOpen by remember { mutableStateOf(false) }

    val planDate = formatPlanDate(draft.plan, zoneId).orEmpty()
    val planTime = formatPlanTime(draft.plan, zoneId).orEmpty()
    val currentType = when (draft.plan) {
        is Plan.Instant -> TimedPlanType.INSTANT
        is Plan.Floating -> TimedPlanType.FLOATING
        else -> effectiveDefault
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = draft.title,
                onValueChange = { value ->
                    onDraftChange(index, draft.copy(title = value))
                },
                label = { Text("Title") },
                modifier = Modifier.weight(1f),
                singleLine = true,
            )
            if (showRemove) {
                IconButton(onClick = { onRemoveDraft(index) }) {
                    Icon(Icons.Default.Delete, contentDescription = "Remove draft")
                }
            }
        }

        OutlinedTextField(
            value = draft.description.orEmpty(),
            onValueChange = { value ->
                onDraftChange(index, draft.copy(description = value.ifBlank { null }))
            },
            label = { Text("Description") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            // Priority
            androidx.compose.material3.OutlinedButton(onClick = { priorityMenuOpen = true }) {
                Text(TaskPriorities.ALL.firstOrNull { it.value == draft.priority }?.label ?: "P${draft.priority}")
            }
            DropdownMenu(expanded = priorityMenuOpen, onDismissRequest = { priorityMenuOpen = false }) {
                TaskPriorities.ALL.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option.label) },
                        onClick = {
                            priorityMenuOpen = false
                            onDraftChange(index, draft.copy(priority = option.value))
                        }
                    )
                }
            }

            // Date (plain YYYY-MM-DD entry; polish comes later)
            OutlinedTextField(
                value = planDate,
                onValueChange = { value ->
                    val plan = rebuildPlan(value, planTime.ifBlank { null }, currentType.takeIf { planTime.isNotBlank() }, effectiveDefault, zoneId)
                    onDraftChange(index, draft.copy(plan = plan, validationError = null))
                },
                label = { Text("YYYY-MM-DD") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = planTime,
                onValueChange = { value ->
                    val plan = if (planDate.isBlank()) {
                        null
                    } else {
                        rebuildPlan(planDate, value.ifBlank { null }, currentType.takeIf { value.isNotBlank() }, effectiveDefault, zoneId)
                    }
                    onDraftChange(index, draft.copy(plan = plan, validationError = null))
                },
                label = { Text("HH:mm") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )

            if (planDate.isNotBlank() && planTime.isNotBlank()) {
                androidx.compose.material3.OutlinedButton(onClick = { typeMenuOpen = true }) {
                    Text(if (currentType == TimedPlanType.INSTANT) "Instant" else "Floating")
                }
                DropdownMenu(expanded = typeMenuOpen, onDismissRequest = { typeMenuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Instant") },
                        onClick = {
                            typeMenuOpen = false
                            onSwitchDraftPlanType(index, TimedPlanType.INSTANT)
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Floating") },
                        onClick = {
                            typeMenuOpen = false
                            onSwitchDraftPlanType(index, TimedPlanType.FLOATING)
                        }
                    )
                }
            }
        }

        draft.validationError?.let { error ->
            Text(text = error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** Mirrors the web modal's date/time edit handlers using createPlanFromInputs. */
private fun rebuildPlan(
    date: String?,
    time: String?,
    type: TimedPlanType?,
    effectiveDefault: TimedPlanType,
    zoneId: ZoneId,
): Plan? = createPlanFromInputs(
    CreatePlanParams(
        date = date?.trim()?.ifBlank { null },
        time = time?.trim()?.ifBlank { null },
        type = type,
        effectiveDefault = effectiveDefault,
        zoneId = zoneId,
    )
)

@Composable
private fun FailedBody(
    failure: VoiceFailure,
    canRetryWithSavedAudio: Boolean,
    retainedRecordings: List<RetainedRecording>,
    onRetryProcessing: () -> Unit,
    onStartRecording: () -> Unit,
    onRequestMicPermission: () -> Unit,
    onDeleteRetained: (String) -> Unit,
    onDeleteAllRetained: () -> Unit,
) {
    val headline = when (failure) {
        VoiceFailure.MicPermissionMissing -> "Microphone access needed"
        VoiceFailure.CircuitBreakerTripped -> "Voice capture temporarily unavailable"
        VoiceFailure.VoiceDisabled -> "Voice capture disabled"
        is VoiceFailure.AllowanceExhausted -> "Voice allowance reached"
        VoiceFailure.ProviderFailed -> "Voice processing failed"
        is VoiceFailure.NetworkError -> "Network error"
        is VoiceFailure.InvalidAudio -> "Recording rejected"
        is VoiceFailure.Unknown -> "Voice Capture Unavailable"
    }

    Text(headline, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.error)
    Text(
        text = when (failure) {
            VoiceFailure.MicPermissionMissing ->
                "Grant microphone access to use Voice capture. Ordinary tasks are unaffected."
            is VoiceFailure.AllowanceExhausted -> {
                val retryAt = failure.earliestRetryAt?.let { " Earliest retry: $it." }.orEmpty()
                val retryIn = failure.retryAfterSeconds?.let { " Retry after ${it}s." }.orEmpty()
                "${failure.messageIfPresent()}$retryAt$retryIn"
            }
            else -> messageFor(failure)
        },
        style = MaterialTheme.typography.bodySmall,
    )

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        when (failure) {
            VoiceFailure.MicPermissionMissing -> Button(onClick = onRequestMicPermission) {
                Text("Grant access")
            }
            else -> OutlinedButton(onClick = onStartRecording) { Text("Record Again") }
        }
        if (canRetryWithSavedAudio) {
            Button(onClick = onRetryProcessing) { Text("Retry with Saved Audio") }
        }
    }

    if (retainedRecordings.isNotEmpty()) {
        RetainedSection(
            retainedRecordings = retainedRecordings,
            onDeleteRetained = onDeleteRetained,
            onDeleteAllRetained = onDeleteAllRetained,
        )
    }
}

private fun VoiceFailure.AllowanceExhausted.messageIfPresent(): String =
    "Voice allowance or rate limit reached."

private fun messageFor(failure: VoiceFailure): String = when (failure) {
    is VoiceFailure.NetworkError -> failure.message
    is VoiceFailure.InvalidAudio -> failure.message
    is VoiceFailure.Unknown -> failure.message
    VoiceFailure.CircuitBreakerTripped ->
        "The Deployment spending boundary tripped. Voice is paused for everyone; ordinary tasks keep working."
    VoiceFailure.VoiceDisabled ->
        "Voice is disabled for this Deployment."
    VoiceFailure.ProviderFailed ->
        "Voice processing failed upstream. Your recording is saved so you can retry."
    VoiceFailure.MicPermissionMissing -> ""
    is VoiceFailure.AllowanceExhausted -> "Voice allowance or rate limit reached."
}

@Composable
private fun RetainedSection(
    retainedRecordings: List<RetainedRecording>,
    onDeleteRetained: (String) -> Unit,
    onDeleteAllRetained: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = "Saved recordings (${retainedRecordings.size}, bounded)",
            style = MaterialTheme.typography.labelMedium,
        )
        retainedRecordings.forEach { recording ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "${recording.fileName} · ${recording.sizeBytes / 1024} KB",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { onDeleteRetained(recording.id) }) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete recording")
                }
            }
        }
        TextButton(onClick = onDeleteAllRetained) { Text("Delete all recordings") }
    }
}
