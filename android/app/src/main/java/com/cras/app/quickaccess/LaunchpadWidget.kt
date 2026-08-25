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
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Row
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.compose.ui.unit.dp

/**
 * Key for the [ActionParameters] carrying the deep-link URI string to the
 * [LaunchpadActionCallback].
 */
val KEY_DEEP_LINK_URI = ActionParameters.Key<String>("cras.deep_link_uri")

/**
 * [ActionCallback] that opens the Cras app via the supplied deep-link URI. This
 * is the single callback used by all four Launchpad buttons so they share one
 * code path into the app rather than four separate broadcast receivers.
 */
class LaunchpadActionCallback : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters
    ) {
        val uriString = parameters[KEY_DEEP_LINK_URI] ?: return
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uriString)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}

/**
 * Jetpack Glance implementation of the Launchpad widget: four action buttons
 * — Today, Upcoming, Voice capture, Create task — arranged in a single row.
 */
class LaunchpadWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            GlanceTheme {
                LaunchpadContent()
            }
        }
    }
}

@Composable
internal fun LaunchpadContent() {
    Row(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(GlanceTheme.colors.surface)
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        LaunchpadButton(
            label = "Today",
            deepLinkUri = "cras://open/today",
            modifier = GlanceModifier.defaultWeight()
        )
        LaunchpadButton(
            label = "Upcoming",
            deepLinkUri = "cras://open/upcoming",
            modifier = GlanceModifier.defaultWeight()
        )
        LaunchpadButton(
            label = "Voice",
            deepLinkUri = "cras://open/voice",
            modifier = GlanceModifier.defaultWeight()
        )
        LaunchpadButton(
            label = "Create",
            deepLinkUri = "cras://open/create",
            modifier = GlanceModifier.defaultWeight()
        )
    }
}

@Composable
internal fun LaunchpadButton(
    label: String,
    deepLinkUri: String,
    modifier: GlanceModifier = GlanceModifier
) {
    Box(
        modifier = modifier
            .padding(horizontal = 4.dp, vertical = 2.dp)
            .cornerRadius(12.dp)
            .background(GlanceTheme.colors.primaryContainer)
            .clickable(
                actionRunCallback<LaunchpadActionCallback>(
                    actionParametersOf(KEY_DEEP_LINK_URI to deepLinkUri)
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = TextStyle(
                color = GlanceTheme.colors.onPrimaryContainer,
                fontWeight = FontWeight.Medium
            ),
            modifier = GlanceModifier.padding(horizontal = 8.dp, vertical = 10.dp)
        )
    }
}

/** Broadcast receiver that ties [LaunchpadWidget] to the Android widget host. */
class LaunchpadWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = LaunchpadWidget()
}
