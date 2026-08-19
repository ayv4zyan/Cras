package com.cras.app.ui.labels

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cras.app.models.Label

fun parseHexColor(hex: String, fallback: Color = Color.Gray): Color {
    return try {
        val cleanHex = hex.removePrefix("#")
        val colorInt = cleanHex.toLong(16)
        if (cleanHex.length == 6) {
            Color(colorInt or 0xFF000000)
        } else {
            Color(colorInt)
        }
    } catch (_: Exception) {
        fallback
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TaskLabelBadges(
    labelIds: List<String>,
    allLabels: List<Label>,
    modifier: Modifier = Modifier
) {
    if (labelIds.isEmpty()) return

    val labelMap = remember(allLabels) {
        allLabels.associateBy { it.id }
    }

    val matchingLabels = labelIds.mapNotNull { labelMap[it] }
    if (matchingLabels.isEmpty()) return

    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        matchingLabels.forEach { label ->
            val labelColor = parseHexColor(label.color)
            Surface(
                shape = RoundedCornerShape(4.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                border = androidx.compose.foundation.BorderStroke(
                    0.5.dp,
                    MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                )
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
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
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}
