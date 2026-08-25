package com.cras.app.quickaccess

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.state.GlanceStateDefinition
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import androidx.compose.ui.unit.dp

import androidx.glance.appwidget.action.actionStartActivity
import com.cras.app.MainActivity
import com.cras.app.R

/** Glance Preferences key holding the JSON-serialised list of [TodayGlanceRow]s. */
val KEY_TODAY_ROWS = stringPreferencesKey("cras_today_rows")

/** Preferences key for the deep-link URI carried to action callbacks. */
val KEY_TASK_ID = ActionParameters.Key<String>("cras.task_id")

/**
 * Serialises a list of [TodayGlanceRow]s to a JSON string suitable for storing
 * in Glance Preferences state.
 */
fun encodeTodayRows(rows: List<TodayGlanceRow>): String =
    Json.encodeToString(ListSerializer(TodayGlanceRow.serializer()), rows)

/**
 * Deserialises a list of [TodayGlanceRow]s previously encoded with [encodeTodayRows].
 * Returns an empty list on any parse error.
 */
fun decodeTodayRows(json: String): List<TodayGlanceRow> {
    return try {
        Json.decodeFromString(ListSerializer(TodayGlanceRow.serializer()), json)
    } catch (_: Exception) {
        emptyList()
    }
}

/**
 * [ActionCallback] that opens a Task by firing a `cras://open/task/{id}` deep-link.
 */
class TodayGlanceOpenTaskCallback : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters
    ) {
        val taskId = parameters[KEY_TASK_ID] ?: return
        val intent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = Uri.parse("cras://open/task/$taskId")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        context.startActivity(intent)
    }
}

/**
 * [ActionCallback] that opens the Today view via `cras://open/today` deep-link.
 */
class TodayGlanceOpenTodayCallback : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters
    ) {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = Uri.parse("cras://open/today")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        context.startActivity(intent)
    }
}

/**
 * [ActionCallback] that opens the typed Create task input via `cras://open/create`.
 */
class TodayGlanceCreateTaskCallback : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters
    ) {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = Uri.parse("cras://open/create")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        context.startActivity(intent)
    }
}

/**
 * [ActionCallback] that completes a Task by sending the Cras app a
 * `cras://complete/task/{id}` intent. The app's [MainActivity] receives
 * this and routes it through [InboxViewModel.completeRoutedTask],
 * which retains the intent if authentication or task data is loading.
 */
class TodayGlanceCompleteTaskCallback : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters
    ) {
        val taskId = parameters[KEY_TASK_ID] ?: return
        val intent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = Uri.parse("cras://complete/task/$taskId")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        context.startActivity(intent)
    }
}

/**
 * Jetpack Glance implementation of the Today Glance widget. It reads the cached
 * today task rows from Glance Preferences state (written by [updateTodayWidgets]
 * whenever the task list changes in the running app).
 */
class TodayGlanceWidget : GlanceAppWidget() {

    override val stateDefinition: GlanceStateDefinition<*> = PreferencesGlanceStateDefinition

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            GlanceTheme {
                TodayGlanceContent()
            }
        }
    }
}

@Composable
internal fun TodayGlanceContent(
    prefs: Preferences = androidx.glance.currentState()
) {
    val context = androidx.glance.LocalContext.current
    val rowsJson = prefs[KEY_TODAY_ROWS]
    val rows = if (rowsJson != null) decodeTodayRows(rowsJson) else emptyList()

    val openTodayIntent = Intent(context, MainActivity::class.java).apply {
        action = Intent.ACTION_VIEW
        data = Uri.parse("cras://open/today")
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }
    val openCreateIntent = Intent(context, MainActivity::class.java).apply {
        action = Intent.ACTION_VIEW
        data = Uri.parse("cras://open/create")
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }

    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(GlanceTheme.colors.surface)
    ) {
        // Header row: "Today" label + create action button
        Row(
            modifier = GlanceModifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp)
                .clickable(actionStartActivity(openTodayIntent)),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = context.getString(R.string.today_glance_header),
                style = TextStyle(
                    color = GlanceTheme.colors.onSurface,
                    fontWeight = FontWeight.Bold
                ),
                modifier = GlanceModifier.defaultWeight()
            )
            Text(
                text = "+",
                style = TextStyle(
                    color = GlanceTheme.colors.primary,
                    fontWeight = FontWeight.Bold
                ),
                modifier = GlanceModifier
                    .padding(start = 8.dp)
                    .clickable(actionStartActivity(openCreateIntent))
            )
        }

        if (rows.isEmpty()) {
            Row(
                modifier = GlanceModifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Text(
                    text = context.getString(R.string.today_glance_empty),
                    style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant)
                )
            }
        } else {
            for (row in rows) {
                TodayGlanceRowItem(row)
            }
        }
    }
}

@Composable
private fun TodayGlanceRowItem(row: TodayGlanceRow) {
    val context = androidx.glance.LocalContext.current
    val openTaskIntent = Intent(context, MainActivity::class.java).apply {
        action = Intent.ACTION_VIEW
        data = Uri.parse("cras://open/task/${row.taskId}")
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }

    Row(
        modifier = GlanceModifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clickable(actionStartActivity(openTaskIntent)),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Completion circle — routes through canonical complete path
        Text(
            text = "○",
            style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant),
            modifier = GlanceModifier
                .padding(end = 8.dp)
                .clickable(
                    actionRunCallback<TodayGlanceCompleteTaskCallback>(
                        actionParametersOf(KEY_TASK_ID to row.taskId)
                    )
                )
        )

        Text(
            text = row.title,
            style = TextStyle(
                color = if (row.isSubtask) {
                    GlanceTheme.colors.onSurfaceVariant
                } else {
                    GlanceTheme.colors.onSurface
                }
            ),
            modifier = GlanceModifier.defaultWeight()
        )
    }
}

/** Broadcast receiver that ties [TodayGlanceWidget] to the Android widget host. */
class TodayGlanceWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TodayGlanceWidget()
}

/**
 * Helper called by the running app whenever the Today task list changes. Stores
 * the current rows in Glance Preferences state and requests a widget redraw.
 *
 * Safe to call from any coroutine scope; no-op if no widget instances exist.
 */
suspend fun updateTodayWidgets(context: Context, rows: List<TodayGlanceRow>) {
    val manager = GlanceAppWidgetManager(context)
    val ids = manager.getGlanceIds(TodayGlanceWidget::class.java)
    val encoded = encodeTodayRows(rows)
    for (id in ids) {
        updateAppWidgetState(context, PreferencesGlanceStateDefinition, id) { prefs ->
            prefs.toMutablePreferences().apply {
                set(KEY_TODAY_ROWS, encoded)
            }
        }
        TodayGlanceWidget().update(context, id)
    }
}
