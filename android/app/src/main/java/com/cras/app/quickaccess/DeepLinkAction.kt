package com.cras.app.quickaccess

import android.content.Intent
import android.net.Uri

/**
 * An action that arrived via a Launchpad widget tap, a Shortcut, or a widget
 * completion button.
 *
 * Deep-link schemes:
 *   cras://open/today        → [OpenToday]
 *   cras://open/upcoming     → [OpenUpcoming]
 *   cras://open/voice        → [OpenVoice]
 *   cras://open/create       → [OpenCreate]
 *   cras://open/task/{id}    → [OpenTask]
 *   cras://complete/task/{id}→ [CompleteTask]
 */
sealed interface DeepLinkAction {
    /** Navigate to the Today view. */
    object OpenToday : DeepLinkAction

    /** Navigate to the Upcoming view. */
    object OpenUpcoming : DeepLinkAction

    /** Open the Voice capture dialog. */
    object OpenVoice : DeepLinkAction

    /** Open the typed Create task input in the current view. */
    object OpenCreate : DeepLinkAction

    /** Open the Task detail dialog for [taskId]. */
    data class OpenTask(val taskId: String) : DeepLinkAction

    /** Complete the Task identified by [taskId]. */
    data class CompleteTask(val taskId: String) : DeepLinkAction
}

const val DEEP_LINK_SCHEME = "cras"
const val DEEP_LINK_HOST_OPEN = "open"
const val DEEP_LINK_HOST_COMPLETE = "complete"
const val DEEP_LINK_HOST = DEEP_LINK_HOST_OPEN

/**
 * Core parsing logic: maps URI components to a [DeepLinkAction]. Pure function
 * with no Android framework dependency — usable from JVM unit tests.
 *
 * @param scheme The URI scheme (e.g. "cras").
 * @param host The URI host (e.g. "open" or "complete").
 * @param pathSegments Ordered path segments (e.g. ["today"] or ["task", "123"]).
 */
fun parseDeepLinkUri(
    scheme: String?,
    host: String?,
    pathSegments: List<String>
): DeepLinkAction? {
    if (scheme != DEEP_LINK_SCHEME) return null
    return when (host) {
        DEEP_LINK_HOST_OPEN -> when (pathSegments.firstOrNull()) {
            "today" -> DeepLinkAction.OpenToday
            "upcoming" -> DeepLinkAction.OpenUpcoming
            "voice" -> DeepLinkAction.OpenVoice
            "create" -> DeepLinkAction.OpenCreate
            "task" -> {
                val taskId = pathSegments.getOrNull(1)?.takeIf { it.isNotBlank() }
                    ?: return null
                DeepLinkAction.OpenTask(taskId)
            }
            else -> null
        }
        DEEP_LINK_HOST_COMPLETE -> when (pathSegments.firstOrNull()) {
            "task" -> {
                val taskId = pathSegments.getOrNull(1)?.takeIf { it.isNotBlank() }
                    ?: return null
                DeepLinkAction.CompleteTask(taskId)
            }
            else -> null
        }
        else -> null
    }
}

/**
 * Parses a [DeepLinkAction] from an [Intent], or returns null when the intent
 * carries no recognised Cras deep-link. Delegates to [parseDeepLinkUri].
 */
fun parseDeepLinkAction(intent: Intent?): DeepLinkAction? {
    val data: Uri = intent?.data ?: return null
    return parseDeepLinkUri(data.scheme, data.host, data.pathSegments)
}
