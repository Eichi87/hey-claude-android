package de.eichberger.heyclaude

import android.content.Context
import android.content.SharedPreferences

/** Einstellungen der App. */
object Prefs {
    private const val FILE = "hey_claude"
    const val KEY_THRESHOLD = "threshold"
    const val KEY_ENABLED = "enabled"
    const val KEY_LAST_LAUNCH = "last_launch"

    const val DEFAULT_THRESHOLD = 0.5f

    fun get(context: Context): SharedPreferences =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun threshold(context: Context): Float = get(context).getFloat(KEY_THRESHOLD, DEFAULT_THRESHOLD)

    fun setThreshold(context: Context, value: Float) =
        get(context).edit().putFloat(KEY_THRESHOLD, value).apply()

    /** Hat der Nutzer das Lauschen eingeschaltet? Wird für den Neustart gemerkt. */
    fun enabled(context: Context): Boolean = get(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, value: Boolean) =
        get(context).edit().putBoolean(KEY_ENABLED, value).apply()

    /** Welche Stufe der Start-Kaskade zuletzt gegriffen hat (zur Kontrolle im Hauptbildschirm). */
    fun lastLaunch(context: Context): String? = get(context).getString(KEY_LAST_LAUNCH, null)

    fun setLastLaunch(context: Context, value: String) =
        get(context).edit().putString(KEY_LAST_LAUNCH, value).apply()
}
