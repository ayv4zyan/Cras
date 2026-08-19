package com.cras.app.ui.labels

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Sell
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.cras.app.models.Label
import com.cras.app.models.LabelColors

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LabelManagerDialog(
    labels: List<Label>,
    onDismiss: () -> Unit,
    onCreateLabel: (name: String, color: String, onSuccess: (Label) -> Unit, onError: (String) -> Unit) -> Unit,
    onUpdateLabel: (id: String, name: String?, color: String?, onSuccess: (Label) -> Unit, onError: (String) -> Unit) -> Unit,
    onDeleteLabel: (id: String, onSuccess: () -> Unit, onError: (String) -> Unit) -> Unit
) {
    var newName by remember { mutableStateOf("") }
    var newColor by remember { mutableStateOf(LabelColors.DEFAULT) }
    var isCreating by remember { mutableStateOf(false) }
    var createError by remember { mutableStateOf<String?>(null) }

    var editingId by remember { mutableStateOf<String?>(null) }
    var editName by remember { mutableStateOf("") }
    var editColor by remember { mutableStateOf("") }
    var isUpdating by remember { mutableStateOf(false) }
    var editError by remember { mutableStateOf<String?>(null) }

    var deletingId by remember { mutableStateOf<String?>(null) }

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
                    .verticalScroll(rememberScrollState())
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Sell,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Labels",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close dialog"
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Create Section
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(
                            text = "Create new label",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        OutlinedTextField(
                            value = newName,
                            onValueChange = {
                                newName = it
                                createError = null
                            },
                            placeholder = { Text("Label name (e.g. Urgent, Work)") },
                            singleLine = true,
                            enabled = !isCreating,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = MaterialTheme.colorScheme.primary,
                                unfocusedBorderColor = MaterialTheme.colorScheme.outline
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        Text(
                            text = "Color",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(6.dp))

                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            LabelColors.ALL.forEach { colorOpt ->
                                val isSelected = newColor.equals(colorOpt.value, ignoreCase = true)
                                val col = parseHexColor(colorOpt.value)
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier
                                        .size(30.dp)
                                        .clip(CircleShape)
                                        .background(col)
                                        .border(
                                            width = if (isSelected) 2.5.dp else 1.dp,
                                            color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
                                            shape = CircleShape
                                        )
                                        .clickable(enabled = !isCreating) {
                                            newColor = colorOpt.value
                                        }
                                ) {
                                    if (isSelected) {
                                        Icon(
                                            imageVector = Icons.Default.Check,
                                            contentDescription = "Selected color",
                                            tint = Color.White,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }
                        }

                        if (createError != null) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = createError ?: "",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        Button(
                            onClick = {
                                val trimmed = newName.trim()
                                if (trimmed.isEmpty()) {
                                    createError = "Label name cannot be empty"
                                    return@Button
                                }
                                isCreating = true
                                createError = null
                                onCreateLabel(
                                    trimmed,
                                    newColor,
                                    {
                                        isCreating = false
                                        newName = ""
                                        newColor = LabelColors.DEFAULT
                                    },
                                    { err ->
                                        isCreating = false
                                        createError = err
                                    }
                                )
                            },
                            enabled = newName.trim().isNotEmpty() && !isCreating,
                            modifier = Modifier.align(Alignment.End)
                        ) {
                            if (isCreating) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                            } else {
                                Icon(
                                    imageVector = Icons.Default.Add,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                            }
                            Text("Create Label")
                        }
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(16.dp))

                // Labels List
                Text(
                    text = "Existing Labels (${labels.size})",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(10.dp))

                if (labels.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No labels created yet. Add your first label above.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        labels.forEach { label ->
                            val isEditingThis = editingId == label.id
                            val isDeletingThis = deletingId == label.id

                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                if (isEditingThis) {
                                    Column(modifier = Modifier.padding(12.dp)) {
                                        OutlinedTextField(
                                            value = editName,
                                            onValueChange = {
                                                editName = it
                                                editError = null
                                            },
                                            placeholder = { Text("Label name") },
                                            singleLine = true,
                                            enabled = !isUpdating,
                                            modifier = Modifier.fillMaxWidth()
                                        )

                                        Spacer(modifier = Modifier.height(8.dp))

                                        FlowRow(
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                            verticalArrangement = Arrangement.spacedBy(6.dp),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            LabelColors.ALL.forEach { colorOpt ->
                                                val isSelected = editColor.equals(colorOpt.value, ignoreCase = true)
                                                val col = parseHexColor(colorOpt.value)
                                                Box(
                                                    contentAlignment = Alignment.Center,
                                                    modifier = Modifier
                                                        .size(26.dp)
                                                        .clip(CircleShape)
                                                        .background(col)
                                                        .border(
                                                            width = if (isSelected) 2.dp else 1.dp,
                                                            color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
                                                            shape = CircleShape
                                                        )
                                                        .clickable(enabled = !isUpdating) {
                                                            editColor = colorOpt.value
                                                        }
                                                ) {
                                                    if (isSelected) {
                                                        Icon(
                                                            imageVector = Icons.Default.Check,
                                                            contentDescription = "Selected color",
                                                            tint = Color.White,
                                                            modifier = Modifier.size(14.dp)
                                                        )
                                                    }
                                                }
                                            }
                                        }

                                        if (editError != null) {
                                            Spacer(modifier = Modifier.height(6.dp))
                                            Text(
                                                text = editError ?: "",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.error
                                            )
                                        }

                                        Spacer(modifier = Modifier.height(10.dp))

                                        Row(
                                            horizontalArrangement = Arrangement.End,
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            OutlinedButton(
                                                onClick = { editingId = null },
                                                enabled = !isUpdating
                                            ) {
                                                Text("Cancel")
                                            }
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Button(
                                                onClick = {
                                                    val trimmed = editName.trim()
                                                    if (trimmed.isEmpty()) {
                                                        editError = "Label name cannot be empty"
                                                        return@Button
                                                    }
                                                    isUpdating = true
                                                    editError = null
                                                    onUpdateLabel(
                                                        label.id,
                                                        trimmed,
                                                        editColor,
                                                        {
                                                            isUpdating = false
                                                            editingId = null
                                                        },
                                                        { err ->
                                                            isUpdating = false
                                                            editError = err
                                                        }
                                                    )
                                                },
                                                enabled = editName.trim().isNotEmpty() && !isUpdating
                                            ) {
                                                if (isUpdating) {
                                                    CircularProgressIndicator(
                                                        modifier = Modifier.size(16.dp),
                                                        strokeWidth = 2.dp
                                                    )
                                                    Spacer(modifier = Modifier.width(6.dp))
                                                }
                                                Text("Save")
                                            }
                                        }
                                    }
                                } else {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 12.dp, vertical = 8.dp)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(12.dp)
                                                .clip(CircleShape)
                                                .background(parseHexColor(label.color))
                                        )
                                        Spacer(modifier = Modifier.width(10.dp))
                                        Text(
                                            text = label.name,
                                            style = MaterialTheme.typography.bodyLarge,
                                            fontWeight = FontWeight.Medium,
                                            modifier = Modifier.weight(1f)
                                        )

                                        IconButton(
                                            onClick = {
                                                editingId = label.id
                                                editName = label.name
                                                editColor = label.color
                                                editError = null
                                            },
                                            modifier = Modifier.size(32.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Edit,
                                                contentDescription = "Edit label",
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }

                                        IconButton(
                                            onClick = {
                                                deletingId = label.id
                                                onDeleteLabel(
                                                    label.id,
                                                    { deletingId = null },
                                                    { deletingId = null }
                                                )
                                            },
                                            enabled = !isDeletingThis,
                                            modifier = Modifier.size(32.dp)
                                        ) {
                                            if (isDeletingThis) {
                                                CircularProgressIndicator(
                                                    modifier = Modifier.size(16.dp),
                                                    strokeWidth = 2.dp
                                                )
                                            } else {
                                                Icon(
                                                    imageVector = Icons.Default.Delete,
                                                    contentDescription = "Delete label",
                                                    tint = MaterialTheme.colorScheme.error,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
