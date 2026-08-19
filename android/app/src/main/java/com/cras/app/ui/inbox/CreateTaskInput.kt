package com.cras.app.ui.inbox

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.Sell
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.cras.app.models.Label
import com.cras.app.models.TaskPriorities
import com.cras.app.ui.labels.parseHexColor

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CreateTaskInput(
    onCreateTask: (title: String, description: String?, priority: Int, labels: List<String>) -> Unit,
    availableLabels: List<Label> = emptyList(),
    isSubmitting: Boolean = false,
    errorMessage: String? = null,
    modifier: Modifier = Modifier
) {
    var title by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var priority by remember { mutableIntStateOf(4) }
    var selectedLabels by remember { mutableStateOf(emptyList<String>()) }
    var isExpanded by remember { mutableStateOf(false) }

    val submit = {
        val trimmedTitle = title.trim()
        if (trimmedTitle.isNotEmpty() && !isSubmitting) {
            val desc = description.trim().ifEmpty { null }
            onCreateTask(trimmedTitle, desc, priority, selectedLabels)
            title = ""
            description = ""
            priority = 4
            selectedLabels = emptyList()
            isExpanded = false
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
                        text = "Create a task in Inbox...",
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
                modifier = Modifier.weight(1f)
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
