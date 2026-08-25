package com.cras.app.ui.inbox

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.cras.app.domain.CreatePlanParams
import com.cras.app.domain.TimedPlanType
import com.cras.app.domain.createPlanFromInputs
import com.cras.app.domain.getDeviceLocalDate
import com.cras.app.models.Label
import com.cras.app.models.Plan
import com.cras.app.models.TaskPriorities
import com.cras.app.ui.labels.parseHexColor
import java.time.Instant
import java.time.LocalDate

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CreateTaskInput(
    onCreateTask: (title: String, description: String?, priority: Int, labels: List<String>, plan: Plan?, onSuccess: () -> Unit) -> Unit,
    availableLabels: List<Label> = emptyList(),
    isSubmitting: Boolean = false,
    errorMessage: String? = null,
    placeholder: String = "Create a task in Inbox...",
    defaultDate: String? = null,
    effectiveDefault: TimedPlanType = TimedPlanType.INSTANT,
    isFocused: Boolean = false,
    onFocusHandled: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var title by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var priority by remember { mutableIntStateOf(TaskPriorities.P4) }
    var selectedLabels by remember { mutableStateOf(emptyList<String>()) }
    var isExpanded by remember { mutableStateOf(false) }

    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(isFocused) {
        if (isFocused) {
            focusRequester.requestFocus()
            onFocusHandled()
        }
    }

    var planDate by remember(defaultDate) { mutableStateOf(defaultDate ?: "") }
    var planTime by remember { mutableStateOf("") }
    var selectedTimedType by remember { mutableStateOf<TimedPlanType?>(null) }
    var isTypeMenuExpanded by remember { mutableStateOf(false) }

    val todayDate = remember(defaultDate, isExpanded) { defaultDate ?: getDeviceLocalDate() }
    val tomorrowDate = remember(todayDate) {
        try {
            LocalDate.parse(todayDate).plusDays(1).toString()
        } catch (_: Exception) {
            LocalDate.now().plusDays(1).toString()
        }
    }

    val submit = {
        val trimmedTitle = title.trim()
        if (trimmedTitle.isNotEmpty() && !isSubmitting) {
            val desc = description.trim().ifEmpty { null }
            val plan = if (planDate.isNotBlank()) {
                createPlanFromInputs(
                    CreatePlanParams(
                        date = planDate.trim(),
                        time = planTime.trim().ifEmpty { null },
                        type = selectedTimedType,
                        effectiveDefault = effectiveDefault
                    )
                )
            } else null

            onCreateTask(trimmedTitle, desc, priority, selectedLabels, plan) {
                title = ""
                description = ""
                priority = TaskPriorities.P4
                selectedLabels = emptyList()
                planDate = defaultDate ?: ""
                planTime = ""
                selectedTimedType = null
                isExpanded = false
            }
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                placeholder = {
                    Text(
                        text = placeholder,
                        style = MaterialTheme.typography.bodyMedium
                    )
                },
                singleLine = true,
                enabled = !isSubmitting,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submit() }),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline
                ),
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focusRequester)
            )

            Spacer(modifier = Modifier.width(4.dp))

            IconButton(
                onClick = { isExpanded = !isExpanded },
                modifier = Modifier.size(40.dp)
            ) {
                Icon(
                    imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = if (isExpanded) "Hide details" else "Add details",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            IconButton(
                onClick = { submit() },
                enabled = title.trim().isNotEmpty() && !isSubmitting,
                modifier = Modifier.size(44.dp)
            ) {
                if (isSubmitting) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "Create task",
                        tint = if (title.trim().isNotEmpty()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                    )
                }
            }
        }

        if (isExpanded) {
            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = description,
                onValueChange = { description = it },
                placeholder = {
                    Text(
                        text = "Add optional description...",
                        style = MaterialTheme.typography.bodySmall
                    )
                },
                enabled = !isSubmitting,
                minLines = 2,
                maxLines = 3,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline
                ),
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Plan Date Section
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.CalendarMonth,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Plan Date:",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(8.dp))

                // Quick Date Buttons: Inbox (no date), Today, Tomorrow
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    val isInboxSelected = planDate.isBlank()
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = if (isInboxSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable(enabled = !isSubmitting) {
                                planDate = ""
                                planTime = ""
                            }
                    ) {
                        Text(
                            text = "Inbox",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = if (isInboxSelected) FontWeight.Bold else FontWeight.Normal,
                            color = if (isInboxSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }

                    val isTodaySelected = planDate == todayDate
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = if (isTodaySelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable(enabled = !isSubmitting) {
                                planDate = todayDate
                            }
                    ) {
                        Text(
                            text = "Today",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = if (isTodaySelected) FontWeight.Bold else FontWeight.Normal,
                            color = if (isTodaySelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }

                    val isTomorrowSelected = planDate == tomorrowDate
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = if (isTomorrowSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable(enabled = !isSubmitting) {
                                planDate = tomorrowDate
                            }
                    ) {
                        Text(
                            text = "Tomorrow",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = if (isTomorrowSelected) FontWeight.Bold else FontWeight.Normal,
                            color = if (isTomorrowSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Custom Date & Time Text Fields
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = planDate,
                    onValueChange = { planDate = it },
                    placeholder = { Text("YYYY-MM-DD", style = MaterialTheme.typography.labelSmall) },
                    singleLine = true,
                    enabled = !isSubmitting,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline
                    ),
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.weight(1.2f)
                )

                if (planDate.isNotBlank()) {
                    OutlinedTextField(
                        value = planTime,
                        onValueChange = { planTime = it },
                        placeholder = { Text("HH:mm", style = MaterialTheme.typography.labelSmall) },
                        singleLine = true,
                        enabled = !isSubmitting,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outline
                        ),
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.weight(1f)
                    )

                    if (planTime.isNotBlank()) {
                        Box {
                            val typeLabel = when (selectedTimedType) {
                                TimedPlanType.INSTANT -> "Instant"
                                TimedPlanType.FLOATING -> "Floating"
                                null -> "Default (${if (effectiveDefault == TimedPlanType.INSTANT) "Instant" else "Floating"})"
                            }
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = MaterialTheme.colorScheme.secondaryContainer,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable(enabled = !isSubmitting) { isTypeMenuExpanded = true }
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp)
                                ) {
                                    Text(
                                        text = typeLabel,
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
                                expanded = isTypeMenuExpanded,
                                onDismissRequest = { isTypeMenuExpanded = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Default (${if (effectiveDefault == TimedPlanType.INSTANT) "Instant" else "Floating"})") },
                                    onClick = {
                                        selectedTimedType = null
                                        isTypeMenuExpanded = false
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Instant") },
                                    onClick = {
                                        selectedTimedType = TimedPlanType.INSTANT
                                        isTypeMenuExpanded = false
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Floating") },
                                    onClick = {
                                        selectedTimedType = TimedPlanType.FLOATING
                                        isTypeMenuExpanded = false
                                    }
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Priority Selection
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Priority:",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TaskPriorities.ALL.forEach { opt ->
                    val isSelected = priority == opt.value
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable(enabled = !isSubmitting) { priority = opt.value }
                    ) {
                        Box(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                            Text(
                                text = if (opt.value == TaskPriorities.P4) "P4 (None)" else "P${opt.value}",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            if (availableLabels.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Labels:",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        availableLabels.forEach { label ->
                            val isSelected = selectedLabels.contains(label.id)
                            val labelColor = parseHexColor(label.color)
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                                border = if (isSelected) {
                                    androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary)
                                } else null,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable(enabled = !isSubmitting) {
                                        selectedLabels = if (isSelected) {
                                            selectedLabels.filterNot { it == label.id }
                                        } else {
                                            selectedLabels + label.id
                                        }
                                    }
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(6.dp)
                                            .clip(CircleShape)
                                            .background(labelColor)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = label.name,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                        color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        if (errorMessage != null) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = errorMessage,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(start = 4.dp)
            )
        }
    }
}
