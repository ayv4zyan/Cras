package com.cras.app.ui.voice

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.cras.app.domain.CreatePlanParams
import com.cras.app.domain.TimedPlanType
import com.cras.app.domain.createPlanFromInputs
import com.cras.app.models.Plan
import com.cras.app.models.TaskPriorities
import com.cras.app.voice.DraftTask
import com.cras.app.voice.RetainedRecording
import com.cras.app.voice.VoiceCaptureMode
import com.cras.app.voice.formatPlanDate
import com.cras.app.voice.formatPlanTime
import java.time.ZoneId

/**
 * Material3 Voice capture dialog covering recording, processing,
 * Draft (create/edit/correction), rejection via per-draft validation errors,
 * and every unavailable-Voice state.
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
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .padding(16.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.Mic,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (focusedTaskTitle != null) "Voice Edit Task" else "Voice Capture",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )
                            if (focusedTaskTitle != null) {
                                Text(
                                    text = "Target: \"$focusedTaskTitle\"",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close dialog"
                        )
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                when (val state = uiState) {
                    VoiceUiState.Idle -> {
                        IdleBody(
                            onStartRecording = onStartRecording,
                            retainedRecordings = retainedRecordings,
                            onDeleteRetained = onDeleteRetained,
                            onDeleteAllRetained = onDeleteAllRetained,
                        )
                    }

                    is VoiceUiState.Recording -> {
                        RecordingBody(
                            state = state,
                            onStopAndProcess = onStopAndProcess,
                            onCancelRecording = onCancelRecording,
                        )
                    }

                    VoiceUiState.Processing -> {
                        ProcessingBody()
                    }

                    is VoiceUiState.Drafts -> {
                        DraftsBody(
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
                    }

                    is VoiceUiState.Failed -> {
                        FailedBody(
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
            }
        }
    }
}

@Composable
private fun IdleBody(
    onStartRecording: () -> Unit,
    retainedRecordings: List<RetainedRecording>,
    onDeleteRetained: (String) -> Unit,
    onDeleteAllRetained: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .size(60.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onStartRecording)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.Mic,
                        contentDescription = "Start recording",
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "Ready to record",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "Speak naturally with task titles, dates, times, and priorities (e.g. \"Buy groceries tomorrow at 5pm P1\").",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }

            Button(
                onClick = onStartRecording,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = Icons.Default.Mic,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Start Recording")
            }
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

@Composable
private fun RecordingBody(
    state: VoiceUiState.Recording,
    onStopAndProcess: () -> Unit,
    onCancelRecording: () -> Unit,
) {
    val totalSeconds = state.elapsedMs / 1000
    val maxSeconds = state.maxDurationMs / 1000
    val remaining = (maxSeconds - totalSeconds).coerceAtLeast(0)
    val progress = (state.elapsedMs.toFloat() / state.maxDurationMs.toFloat()).coerceIn(0f, 1f)

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.7f)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.error)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "LISTENING",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }

                Text(
                    text = "Auto-stops in ${remaining}s",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (remaining <= 10) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = if (remaining <= 10) FontWeight.Bold else FontWeight.Normal
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom
            ) {
                Text(
                    text = "%02d:%02d".format(totalSeconds / 60, totalSeconds % 60),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Max %02d:%02d".format(maxSeconds / 60, maxSeconds % 60),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp)),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )

            Text(
                text = "Speak naturally. Tap Done when finished or let the recording auto-stop.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        OutlinedButton(
            onClick = onCancelRecording,
            modifier = Modifier.weight(1f)
        ) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = null,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text("Cancel")
        }
        Button(
            onClick = onStopAndProcess,
            modifier = Modifier.weight(1f)
        ) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = null,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text("Done")
        }
    }
}

@Composable
private fun ProcessingBody() {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(40.dp),
                strokeWidth = 3.dp
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Processing Speech",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Transcribing audio and extracting task metadata...",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
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
    val isEditMode = state.mode == VoiceCaptureMode.EDIT && state.editProposal != null

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(
                text = if (focusedTaskTitle != null || isEditMode) {
                    "Proposed Change"
                } else {
                    "Proposed Tasks (${state.drafts.size})"
                },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Review and edit before saving",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (state.isCorrection) {
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = MaterialTheme.colorScheme.primaryContainer
            ) {
                Text(
                    text = "Corrected",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                )
            }
        }
    }

    if (state.transcript.isNotBlank()) {
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(10.dp),
                verticalAlignment = Alignment.Top
            ) {
                Icon(
                    imageVector = Icons.Default.GraphicEq,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text(
                        text = "Heard",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "\"${state.transcript}\"",
                        style = MaterialTheme.typography.bodySmall,
                        fontStyle = FontStyle.Italic,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }

    // Rejection banner: invalid plan entries must be corrected before accept.
    if (state.hasValidationErrors) {
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.3f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(10.dp),
                verticalAlignment = Alignment.Top
            ) {
                Icon(
                    imageVector = Icons.Default.ErrorOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Please correct invalid plan entries below (timed tasks require a clock time in HH:mm) before accepting.",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }
    }

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

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        OutlinedButton(
            onClick = onCorrectByVoice,
            modifier = Modifier.weight(1f)
        ) {
            Icon(
                imageVector = Icons.Default.Mic,
                contentDescription = null,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text("Correct by Voice")
        }
        OutlinedButton(
            onClick = onStartOver,
            modifier = Modifier.weight(1f)
        ) {
            Icon(
                imageVector = Icons.Default.Refresh,
                contentDescription = null,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text("Start Over")
        }
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
        modifier = Modifier.fillMaxWidth()
    ) {
        Icon(
            imageVector = Icons.Default.Check,
            contentDescription = null,
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = when {
                isEditMode -> "Accept Changes"
                state.drafts.size > 1 -> "Accept All (${state.drafts.size} Tasks)"
                else -> "Accept Task"
            }
        )
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
    var typeMenuOpen by remember { mutableStateOf(false) }

    // Raw field text lives in local state keyed to the draft: while typed
    // input is incomplete, rebuildPlan yields a null plan and deriving the
    // display value from it would wipe the field mid-edit. (Web never hits
    // this because native date/time inputs only commit complete values.)
    var planDate by remember(draft.id) {
        mutableStateOf(formatPlanDate(draft.plan, zoneId).orEmpty())
    }
    var planTime by remember(draft.id) {
        mutableStateOf(formatPlanTime(draft.plan, zoneId).orEmpty())
    }
    val currentType = when (draft.plan) {
        is Plan.Instant -> TimedPlanType.INSTANT
        is Plan.Floating -> TimedPlanType.FLOATING
        else -> effectiveDefault
    }
    val hasError = draft.validationError != null

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        border = if (hasError) {
            BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.6f))
        } else {
            BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
        },
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer
                ) {
                    Text(
                        text = "Task #${index + 1}",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }

                if (showRemove) {
                    IconButton(
                        onClick = { onRemoveDraft(index) },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.DeleteOutline,
                            contentDescription = "Remove draft",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            OutlinedTextField(
                value = draft.title,
                onValueChange = { value ->
                    onDraftChange(index, draft.copy(title = value))
                },
                placeholder = { Text("Task title") },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline
                ),
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = draft.description.orEmpty(),
                onValueChange = { value ->
                    onDraftChange(index, draft.copy(description = value.ifBlank { null }))
                },
                placeholder = { Text("Optional description...") },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline
                ),
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
                maxLines = 4
            )

            // Priority Selection (Chips mirroring TaskDetailDialog)
            Text(
                text = "Priority",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                TaskPriorities.ALL.forEach { opt ->
                    val isSelected = draft.priority == opt.value
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = if (isSelected) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                        border = if (isSelected) {
                            BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary)
                        } else null,
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(6.dp))
                            .clickable {
                                onDraftChange(index, draft.copy(priority = opt.value))
                            }
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.padding(vertical = 6.dp)
                        ) {
                            Text(
                                text = "P${opt.value}",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                color = if (isSelected) {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                        }
                    }
                }
            }

            // Date & Time
            Text(
                text = "Plan Date & Time",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = planDate,
                    onValueChange = { value ->
                        planDate = value
                        val plan = rebuildPlan(
                            value,
                            planTime.ifBlank { null },
                            currentType.takeIf { planTime.isNotBlank() },
                            effectiveDefault,
                            zoneId
                        )
                        onDraftChange(index, draft.copy(plan = plan, validationError = null))
                    },
                    placeholder = { Text("YYYY-MM-DD", style = MaterialTheme.typography.labelSmall) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline
                    ),
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.weight(1.2f)
                )

                OutlinedTextField(
                    value = planTime,
                    onValueChange = { value ->
                        planTime = value
                        val plan = if (planDate.isBlank()) {
                            null
                        } else {
                            rebuildPlan(
                                planDate,
                                value.ifBlank { null },
                                currentType.takeIf { value.isNotBlank() },
                                effectiveDefault,
                                zoneId
                            )
                        }
                        onDraftChange(index, draft.copy(plan = plan, validationError = null))
                    },
                    placeholder = { Text("HH:mm", style = MaterialTheme.typography.labelSmall) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline
                    ),
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.weight(1f)
                )

                if (planDate.isNotBlank() && planTime.isNotBlank()) {
                    Box {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .clickable { typeMenuOpen = true }
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp)
                            ) {
                                Text(
                                    text = if (currentType == TimedPlanType.INSTANT) "Instant" else "Floating",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                                Icon(
                                    imageVector = Icons.Default.KeyboardArrowDown,
                                    contentDescription = "Select type",
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        }

                        DropdownMenu(
                            expanded = typeMenuOpen,
                            onDismissRequest = { typeMenuOpen = false }
                        ) {
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
            }

            if (draft.validationError != null) {
                Text(
                    text = draft.validationError ?: "",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }
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
    val (headline, icon) = when (failure) {
        VoiceFailure.MicPermissionMissing -> "Microphone Permission Needed" to Icons.Default.MicOff
        VoiceFailure.CircuitBreakerTripped -> "Voice Temporarily Paused" to Icons.Default.Warning
        VoiceFailure.VoiceDisabled -> "Voice Capture Disabled" to Icons.Default.MicOff
        is VoiceFailure.AllowanceExhausted -> "Voice Allowance Reached" to Icons.Default.HourglassEmpty
        VoiceFailure.ProviderFailed -> "Voice Processing Issue" to Icons.Default.ErrorOutline
        is VoiceFailure.NetworkError -> "Network Connection Error" to Icons.Default.CloudOff
        is VoiceFailure.InvalidAudio -> "Audio Recording Rejected" to Icons.Default.ErrorOutline
        is VoiceFailure.Unknown -> "Voice Capture Unavailable" to Icons.Default.ErrorOutline
    }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.3f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.Top
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(22.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = headline,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
                Text(
                    text = when (failure) {
                        VoiceFailure.MicPermissionMissing ->
                            "Grant microphone access to create and edit tasks with Voice. Ordinary task management remains unaffected."
                        is VoiceFailure.AllowanceExhausted -> {
                            val retryAt = failure.earliestRetryAt?.let { " Earliest retry: $it." }.orEmpty()
                            val retryIn = failure.retryAfterSeconds?.let { " Retry in ${it}s." }.orEmpty()
                            "${messageFor(failure)}$retryAt$retryIn Ordinary task management remains unaffected."
                        }
                        else -> messageFor(failure)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.85f)
                )
            }
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        when (failure) {
            VoiceFailure.MicPermissionMissing -> {
                Button(
                    onClick = onRequestMicPermission,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.Default.Mic,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Grant Permission")
                }
            }
            else -> {
                if (canRetryWithSavedAudio) {
                    Button(
                        onClick = onRetryProcessing,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Retry Saved")
                    }
                    OutlinedButton(
                        onClick = onStartRecording,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Mic,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Record Again")
                    }
                } else {
                    Button(
                        onClick = onStartRecording,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            imageVector = Icons.Default.Mic,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Record Again")
                    }
                }
            }
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

private fun messageFor(failure: VoiceFailure): String = when (failure) {
    is VoiceFailure.NetworkError -> failure.message
    is VoiceFailure.InvalidAudio -> failure.message
    is VoiceFailure.Unknown -> failure.message
    VoiceFailure.CircuitBreakerTripped ->
        "The Deployment spending boundary tripped. Voice is paused for everyone; ordinary tasks continue working normally."
    VoiceFailure.VoiceDisabled ->
        "Voice is disabled for this Deployment. Ordinary task management is unaffected."
    VoiceFailure.ProviderFailed ->
        "Voice processing failed upstream. Your recording is preserved locally so you can retry."
    VoiceFailure.MicPermissionMissing -> ""
    is VoiceFailure.AllowanceExhausted -> "Voice allowance or rate limit reached."
}

@Composable
private fun RetainedSection(
    retainedRecordings: List<RetainedRecording>,
    onDeleteRetained: (String) -> Unit,
    onDeleteAllRetained: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Audiotrack,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Saved Recordings (${retainedRecordings.size})",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer
                ) {
                    Text(
                        text = "Offline backup",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            Text(
                text = "Recordings preserved locally for retry when offline or interrupted.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                retainedRecordings.forEach { recording ->
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surface,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Mic,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = recording.fileName,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "${recording.sizeBytes / 1024} KB",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            IconButton(
                                onClick = { onDeleteRetained(recording.id) },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.DeleteOutline,
                                    contentDescription = "Delete recording",
                                    tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f),
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }
            }

            TextButton(
                onClick = onDeleteAllRetained,
                modifier = Modifier.align(Alignment.End)
            ) {
                Icon(
                    imageVector = Icons.Default.DeleteOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "Delete All Recordings",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}
