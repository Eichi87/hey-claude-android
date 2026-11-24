package de.eichberger.heyclaude

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * Autostart nach dem Neustart, soweit Android das für Mikrofon-Dienste erlaubt.
 *
 * Begründung:
 *  - Bis Android 13 darf eine App direkt aus BOOT_COMPLETED einen Vordergrunddienst starten.
 *  - Ab Android 14 (API 34) dürfen Apps mit targetSdk 34 aus BOOT_COMPLETED keinen
 *    Vordergrunddienst vom Typ „microphone“ mehr starten; der Versuch endet mit einer Exception.
 *    Mikrofonzugriff gibt es für Vordergrunddienste nur, wenn sie gestartet werden, während die
 *    App sichtbar ist („while-in-use“-Berechtigung).
 *  - Deshalb zeigen wir ab Android 14 nur eine Benachrichtigung „Tippen zum Aktivieren“. Das Tippen
 *    öffnet die App im Vordergrund, und die startet den Dienst dann regulär.
 *  - Scheitert der Direktstart auch unter Android 13 oder älter (z. B. Herstellerbeschränkungen),
 *    fällt die App ebenfalls auf die Benachrichtigung zurück.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!Prefs.enabled(context)) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            Notifications.showActivatePrompt(context)
            return
        }
        try {
            WakeWordService.start(context)
        } catch (e: Exception) {
            Log.w("BootReceiver", "Direktstart nicht erlaubt, zeige Hinweis", e)
            Notifications.showActivatePrompt(context)
        }
    }
}
