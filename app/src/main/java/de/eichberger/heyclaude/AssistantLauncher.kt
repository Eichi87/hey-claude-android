package de.eichberger.heyclaude

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.util.Log

/** Startet nach der Erkennung den Sprachassistenten (Standard: Claude). */
object AssistantLauncher {

    private const val TAG = "AssistantLauncher"
    const val CLAUDE_PACKAGE = "com.anthropic.claude"

    /** Liefert eine kurze Beschreibung, was gestartet wurde, oder null, wenn nichts ging. */
    fun launch(context: Context): String? {
        if (Prefs.openClaudeDirectly(context)) {
            return launchClaudeApp(context) ?: launchVoiceIntents(context)
        }
        return launchVoiceIntents(context) ?: launchClaudeApp(context)
    }

    private fun launchVoiceIntents(context: Context): String? {
        val candidates = listOf(
            Intent.ACTION_VOICE_COMMAND to "Sprachbefehl",
            Intent.ACTION_ASSIST to "Assistent",
        )
        for ((action, label) in candidates) {
            if (tryStart(context, Intent(action))) return label
        }
        return null
    }

    private fun launchClaudeApp(context: Context): String? {
        val intent = context.packageManager.getLaunchIntentForPackage(CLAUDE_PACKAGE) ?: return null
        return if (tryStart(context, intent)) "Claude-App" else null
    }

    private fun tryStart(context: Context, intent: Intent): Boolean = try {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        true
    } catch (e: ActivityNotFoundException) {
        Log.w(TAG, "Nicht verfügbar: ${intent.action ?: intent.`package`}")
        false
    } catch (e: SecurityException) {
        Log.w(TAG, "Nicht erlaubt: ${intent.action ?: intent.`package`}", e)
        false
    }
}
