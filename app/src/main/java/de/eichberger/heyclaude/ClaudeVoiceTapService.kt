package de.eichberger.heyclaude

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Context
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Bedienungshilfe, die nach „Hey Claude“ in der Claude-App auf das Sprachmodus-Symbol tippt.
 *
 * Bewusst eng begrenzt:
 *  - Sie erhält laut Konfiguration nur Ereignisse der Claude-App (com.anthropic.claude).
 *  - Sie handelt nur, wenn sie „scharf“ geschaltet wurde, also für [ARM_WINDOW_MS] nach einem
 *    Start durch diese App, und tippt höchstens einmal.
 *  - Sonst liest und tut sie nichts.
 *
 * Erkannt wird das Symbol an Beschriftung, Inhaltsbeschreibung oder View-ID (z. B. „Voice mode“,
 * „Sprachmodus“). Die Diktier-Taste (Mikrofon) wird ausdrücklich ausgeschlossen. Findet sie nichts,
 * speichert sie die sichtbaren Knöpfe, damit man die Erkennung anpassen kann.
 */
class ClaudeVoiceTapService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var armedUntil = 0L
    private var lastDump = ""
    private val retry = Runnable { tryTap() }

    override fun onServiceConnected() {
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.packageName?.toString() == ClaudeLauncher.CLAUDE_PACKAGE && isArmed()) {
            handler.removeCallbacks(retry)
            handler.postDelayed(retry, EVENT_DEBOUNCE_MS)
        }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (instance === this) instance = null
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun isArmed() = SystemClock.elapsedRealtime() < armedUntil

    private fun arm() {
        armedUntil = SystemClock.elapsedRealtime() + ARM_WINDOW_MS
        lastDump = ""
        handler.removeCallbacks(retry)
        handler.postDelayed(retry, FIRST_TRY_MS)
    }

    private fun disarm() {
        armedUntil = 0L
        handler.removeCallbacks(retry)
    }

    private fun tryTap() {
        if (!isArmed()) {
            if (armedUntil != 0L) {
                Prefs.setLastTap(this, getString(R.string.tap_not_found, lastDump.ifBlank { "–" }))
                disarm()
            }
            return
        }
        val root = claudeRoot()
        if (root != null) {
            val match = findVoiceButton(root)
            if (match != null && click(match.first)) {
                Prefs.setLastTap(this, getString(R.string.tap_done, match.second))
                disarm()
                return
            }
            lastDump = describeClickables(root)
        }
        handler.postDelayed(retry, RETRY_MS)
    }

    /** Wurzelknoten des Claude-Fensters, auch wenn ein anderes Fenster gerade aktiv ist. */
    private fun claudeRoot(): AccessibilityNodeInfo? {
        rootInActiveWindow?.let { if (it.packageName == ClaudeLauncher.CLAUDE_PACKAGE) return it }
        return windows.mapNotNull { it.root }.firstOrNull { it.packageName == ClaudeLauncher.CLAUDE_PACKAGE }
    }

    /** Sucht den Sprachmodus-Knopf; liefert Knoten und gefundene Beschriftung. */
    private fun findVoiceButton(root: AccessibilityNodeInfo): Pair<AccessibilityNodeInfo, String>? {
        var best: Pair<AccessibilityNodeInfo, String>? = null
        var bestScore = 0
        walk(root) { node ->
            val label = labelOf(node)
            val lower = label.lowercase()
            if (lower.isBlank() || EXCLUDE.any { lower.contains(it) }) return@walk
            val score = when {
                STRONG.any { lower.contains(it) } -> 2
                WEAK.any { lower.contains(it) } -> 1
                else -> 0
            }
            if (score > bestScore && node.isVisibleToUser) {
                bestScore = score
                best = node to label
            }
        }
        return best
    }

    private fun click(node: AccessibilityNodeInfo): Boolean {
        var n: AccessibilityNodeInfo? = node
        while (n != null) {
            if (n.isClickable && n.isEnabled) return n.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            n = n.parent
        }
        // Kein klickbarer Knoten (z. B. Compose ohne Click-Semantik): auf die Mitte tippen.
        val r = Rect()
        node.getBoundsInScreen(r)
        if (r.isEmpty) return false
        val path = Path().apply { moveTo(r.exactCenterX(), r.exactCenterY()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, TAP_MS))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    private fun describeClickables(root: AccessibilityNodeInfo): String {
        val items = mutableListOf<String>()
        walk(root) { node ->
            if (node.isClickable && items.size < MAX_DUMP) {
                val label = labelOf(node)
                if (label.isNotBlank()) items += label
            }
        }
        return items.joinToString(" | ")
    }

    private fun labelOf(node: AccessibilityNodeInfo): String = listOfNotNull(
        node.contentDescription?.toString(),
        node.text?.toString(),
        node.viewIdResourceName?.substringAfterLast('/'),
    ).filter { it.isNotBlank() }.joinToString(" / ").take(MAX_LABEL)

    private fun walk(root: AccessibilityNodeInfo, visit: (AccessibilityNodeInfo) -> Unit) {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.addLast(root)
        var count = 0
        while (queue.isNotEmpty() && count < MAX_NODES) {
            val n = queue.removeFirst()
            count++
            visit(n)
            for (i in 0 until n.childCount) n.getChild(i)?.let { queue.addLast(it) }
        }
    }

    companion object {
        private const val ARM_WINDOW_MS = 10_000L
        private const val FIRST_TRY_MS = 700L
        private const val RETRY_MS = 400L
        private const val EVENT_DEBOUNCE_MS = 250L
        private const val TAP_MS = 60L
        private const val MAX_NODES = 600
        private const val MAX_DUMP = 15
        private const val MAX_LABEL = 60

        private val STRONG = listOf("voice mode", "sprachmodus", "start voice", "voice_mode", "voicemode")
        private val WEAK = listOf("voice", "sprach", "gespräch", "conversation mode")
        private val EXCLUDE = listOf(
            "dictat", "diktier", "mikrofon", "microphone", "setting", "einstellung", "auswählen", "select",
        )

        @Volatile
        private var instance: ClaudeVoiceTapService? = null

        /** Schaltet die Bedienungshilfe für einen Start scharf. Liefert false, wenn sie nicht läuft. */
        fun armIfRunning(): Boolean {
            val s = instance ?: return false
            s.handler.post { s.arm() }
            return true
        }

        fun isEnabled(context: Context): Boolean {
            val me = ComponentName(context, ClaudeVoiceTapService::class.java).flattenToString()
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: return false
            return enabled.split(':').any { it.equals(me, ignoreCase = true) }
        }
    }
}
