package de.eichberger.heyclaude

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.content.res.XmlResourceParser
import android.net.Uri
import android.os.Process
import android.util.Log
import org.xmlpull.v1.XmlPullParser

/**
 * Öffnet nach der Erkennung direkt die Claude-App, möglichst im Sprachmodus.
 * Der Standardassistent des Systems (z. B. Gemini) wird nicht mehr angefasst.
 *
 * Kaskade, jede Stufe mit Try/Catch, damit immer mindestens die Claude-App aufgeht:
 *  a) Sprach-Shortcut der Claude-App. Die statischen Shortcuts stehen in der XML-Datei, auf die
 *     das Meta-Datum „android.app.shortcuts“ der Launcher-Activity zeigt. Diese Datei wird zur
 *     Laufzeit aus der installierten Claude-App gelesen (keine geratenen IDs). Gestartet wird
 *     a1) per LauncherApps.startShortcut (geht nur, wenn diese App Standard-Launcher wäre) oder
 *     a2) per explizitem Intent aus der Shortcut-Definition (geht, wenn die Ziel-Activity exportiert ist).
 *     Dynamische Shortcuts, die die App erst zur Laufzeit anlegt, sind für Fremd-Apps nicht sichtbar.
 *  b) Intent direkt in den Sprachmodus. Ein Deep-Link dafür ist nicht dokumentiert (die Hilfe nennt
 *     nur claude://code-Routen), deshalb wird keiner geraten. Stattdessen werden die Sprach-Intents
 *     ASSIST und VOICE_COMMAND gezielt an die Claude-App adressiert (setPackage). Das ruft nur dann
 *     etwas auf, wenn Claude selbst eine passende Activity anbietet, und berührt den Standard-
 *     assistenten (z. B. Gemini) nicht: Es wird nichts registriert und kein Systemdialog geöffnet.
 *  c) claude://claude.ai/new, dann https://claude.ai/new (App-Link), dann normaler Launch-Intent.
 */
object ClaudeLauncher {

    private const val TAG = "ClaudeLauncher"
    const val CLAUDE_PACKAGE = "com.anthropic.claude"
    private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"

    /** Schlüsselwörter, an denen der Sprach-Shortcut erkannt wird (ID oder Beschriftung). */
    private val VOICE_KEYWORDS = listOf("voice", "sprach", "talk", "speak", "gespräch")

    data class Shortcut(
        val id: String,
        val label: String,
        val enabled: Boolean,
        val action: String?,
        val targetPackage: String?,
        val targetClass: String?,
        val data: String?,
        val extras: Map<String, String>,
    ) {
        val looksLikeVoice: Boolean
            get() = VOICE_KEYWORDS.any { id.contains(it, true) || label.contains(it, true) }

        fun describe(): String = buildString {
            append(id)
            if (label.isNotBlank()) append(" („").append(label).append("“)")
            if (!enabled) append(" [deaktiviert]")
            append("\n   → ")
            append(action ?: "(keine Action)")
            targetClass?.let { append(", ").append(it) }
            data?.let { append(", ").append(it) }
            if (extras.isNotEmpty()) append(", Extras ").append(extras)
        }
    }

    data class Result(val stage: String, val detail: String)

    fun isInstalled(context: Context): Boolean = try {
        context.packageManager.getPackageInfo(CLAUDE_PACKAGE, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    /** Startet Claude über die Kaskade a → b → c. Liefert die Stufe, die gegriffen hat, oder null. */
    fun launch(context: Context): Result? {
        // Bedienungshilfe scharf schalten, bevor Claude aufgeht (tippt dann auf das Sprachmodus-Symbol).
        val autoTap = ClaudeVoiceTapService.armIfRunning()
        Prefs.setLastTap(context, if (autoTap) "läuft …" else "Bedienungshilfe aus")

        val voice = try {
            readStaticShortcuts(context).firstOrNull { it.enabled && it.looksLikeVoice }
        } catch (e: Exception) {
            Log.w(TAG, "Shortcuts der Claude-App nicht lesbar", e)
            null
        }

        // Stufe a1: offizieller Weg für Launcher. Klappt nur mit Launcher-Rechten.
        if (voice != null) {
            try {
                val la = context.getSystemService(LauncherApps::class.java)
                if (la != null && la.hasShortcutHostPermission()) {
                    la.startShortcut(CLAUDE_PACKAGE, voice.id, null, null, Process.myUserHandle())
                    return remember(context, Result("a1", "LauncherApps-Shortcut ${voice.id}"))
                }
            } catch (e: Exception) {
                Log.w(TAG, "Stufe a1 fehlgeschlagen", e)
            }

            // Stufe a2 (und ggf. b): Intent aus der Shortcut-Definition explizit starten.
            val intent = Intent(voice.action ?: Intent.ACTION_VIEW).apply {
                val pkg = voice.targetPackage ?: CLAUDE_PACKAGE
                if (voice.targetClass != null) component = ComponentName(pkg, voice.targetClass) else setPackage(pkg)
                voice.data?.let { data = Uri.parse(it) }
                voice.extras.forEach { (k, v) -> putExtra(k, v) }
            }
            val stage = if (voice.data != null && voice.targetClass == null) "b" else "a2"
            if (tryStart(context, intent)) {
                return remember(context, Result(stage, "Shortcut-Intent ${voice.id}"))
            }
        }

        // Mit aktiver Bedienungshilfe zuerst der normale neue Chat (c): Dort sitzt laut Claude-Hilfe
        // das Sprachmodus-Symbol im Eingabefeld. Ohne Bedienungshilfe zuerst das Assistenten-Fenster (b).
        if (autoTap) {
            startNewChat(context)?.let { return it }
            startVoiceIntents(context)?.let { return it }
        } else {
            startVoiceIntents(context)?.let { return it }
            startNewChat(context)?.let { return it }
        }
        val launch = context.packageManager.getLaunchIntentForPackage(CLAUDE_PACKAGE)
        if (launch != null && tryStart(context, launch)) {
            return remember(context, Result("c", "Launch-Intent"))
        }
        Prefs.setLastLaunch(context, "keine Stufe hat gegriffen")
        return null
    }

    /** Stufe b: Sprach-Intents gezielt an die Claude-App. */
    private fun startVoiceIntents(context: Context): Result? {
        for ((action, label) in VOICE_ACTIONS) {
            if (tryStart(context, Intent(action).setPackage(CLAUDE_PACKAGE))) {
                return remember(context, Result("b", label))
            }
        }
        return null
    }

    /** Stufe c (ohne letzten Fallback): neuer Chat per Deep-Link bzw. App-Link. */
    private fun startNewChat(context: Context): Result? {
        for (url in listOf("claude://claude.ai/new", "https://claude.ai/new")) {
            if (tryStart(context, Intent(Intent.ACTION_VIEW, Uri.parse(url)).setPackage(CLAUDE_PACKAGE))) {
                return remember(context, Result("c", url))
            }
        }
        return null
    }

    /** Sprach-Intents, die gezielt an Claude gehen (Stufe b). */
    private val VOICE_ACTIONS = listOf(
        Intent.ACTION_ASSIST to "ASSIST an Claude",
        Intent.ACTION_VOICE_COMMAND to "VOICE_COMMAND an Claude",
    )

    /** Diagnose: Welche Einstiege bietet die installierte Claude-App für die Stufen b und c an? */
    fun describeVoiceEntryPoints(context: Context): String {
        val pm = context.packageManager
        val probes = VOICE_ACTIONS.map { (action, label) -> label to Intent(action).setPackage(CLAUDE_PACKAGE) } +
            listOf(
                "claude://claude.ai/new" to Intent(Intent.ACTION_VIEW, Uri.parse("claude://claude.ai/new")).setPackage(CLAUDE_PACKAGE),
                "https://claude.ai/new" to Intent(Intent.ACTION_VIEW, Uri.parse("https://claude.ai/new")).setPackage(CLAUDE_PACKAGE),
            )
        return probes.joinToString("\n") { (label, intent) ->
            val hits = pm.queryIntentActivities(intent, 0).map { it.activityInfo.name.substringAfterLast('.') }
            "• $label: " + if (hits.isEmpty()) "nicht angeboten" else hits.joinToString()
        }
    }

    /** Liest die statischen Shortcuts aller Launcher-Activities der Claude-App. */
    fun readStaticShortcuts(context: Context): List<Shortcut> {
        val pm = context.packageManager
        val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(CLAUDE_PACKAGE)
        val res = pm.getResourcesForApplication(CLAUDE_PACKAGE)
        val result = mutableListOf<Shortcut>()
        for (ri in pm.queryIntentActivities(query, PackageManager.GET_META_DATA)) {
            val xmlId = ri.activityInfo.metaData?.getInt("android.app.shortcuts", 0) ?: 0
            if (xmlId == 0) continue
            res.getXml(xmlId).use { parser -> result += parseShortcuts(parser, res) }
        }
        return result
    }

    private fun parseShortcuts(p: XmlResourceParser, res: android.content.res.Resources): List<Shortcut> {
        val out = mutableListOf<Shortcut>()
        var id: String? = null
        var label = ""
        var enabled = true
        var action: String? = null
        var tPkg: String? = null
        var tCls: String? = null
        var data: String? = null
        var extras = mutableMapOf<String, String>()
        var inShortcut = false

        fun attr(name: String): String? {
            val resId = p.getAttributeResourceValue(ANDROID_NS, name, 0)
            if (resId != 0) return try { res.getString(resId) } catch (e: Exception) { null }
            return p.getAttributeValue(ANDROID_NS, name)
        }

        var event = p.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (p.name) {
                    "shortcut" -> {
                        inShortcut = true
                        id = attr("shortcutId")
                        label = attr("shortcutShortLabel") ?: attr("shortcutLongLabel") ?: ""
                        enabled = attr("enabled")?.toBooleanStrictOrNull() ?: true
                        action = null; tPkg = null; tCls = null; data = null
                        extras = mutableMapOf()
                    }
                    // Mehrere <intent> bilden einen Back-Stack; der letzte ist die sichtbare Activity.
                    "intent" -> if (inShortcut) {
                        action = attr("action")
                        tPkg = attr("targetPackage")
                        tCls = attr("targetClass")
                        data = attr("data")
                    }
                    "extra" -> if (inShortcut) {
                        val k = attr("name")
                        val v = attr("value")
                        if (k != null && v != null) extras[k] = v
                    }
                }
                XmlPullParser.END_TAG -> if (p.name == "shortcut" && inShortcut) {
                    id?.let { out += Shortcut(it, label, enabled, action, tPkg, tCls, data, extras.toMap()) }
                    inShortcut = false
                }
            }
            event = p.next()
        }
        return out
    }

    private fun remember(context: Context, r: Result): Result {
        Prefs.setLastLaunch(context, "Stufe ${r.stage}: ${r.detail}")
        return r
    }

    private fun tryStart(context: Context, intent: Intent): Boolean = try {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        true
    } catch (e: ActivityNotFoundException) {
        Log.w(TAG, "Nicht verfügbar: $intent")
        false
    } catch (e: SecurityException) {
        Log.w(TAG, "Nicht erlaubt: $intent", e)
        false
    } catch (e: Exception) {
        Log.w(TAG, "Start fehlgeschlagen: $intent", e)
        false
    }
}
