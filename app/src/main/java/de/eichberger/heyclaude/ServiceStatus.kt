package de.eichberger.heyclaude

import android.os.Handler
import android.os.Looper

/** Zustand des Dienstes, damit der Hauptbildschirm ihn anzeigen kann. */
object ServiceStatus {

    enum class State { STOPPED, STARTING, LISTENING, PAUSED, WAITING, ERROR }

    fun interface Listener {
        fun onStatusChanged(state: State, message: String?)
    }

    @Volatile
    var state: State = State.STOPPED
        private set

    @Volatile
    var message: String? = null
        private set

    /** Höchster Erkennungswert der letzten Sekunde, für die Feineinstellung der Schwelle. */
    @Volatile
    var recentScore: Float = 0f

    /** Höchster Erkennungswert seit Beginn des Lauschens. */
    @Volatile
    var maxScore: Float = 0f

    /** Lautester Mikrofonpegel der letzten Sekunde in dBFS (-100 = völlige Stille). */
    @Volatile
    var recentLevelDb: Float = -100f

    private val main = Handler(Looper.getMainLooper())
    private val listeners = mutableSetOf<Listener>()

    fun set(newState: State, newMessage: String? = null) {
        state = newState
        message = newMessage
        main.post { listeners.toList().forEach { it.onStatusChanged(newState, newMessage) } }
    }

    fun addListener(l: Listener) {
        listeners.add(l)
        l.onStatusChanged(state, message)
    }

    fun removeListener(l: Listener) {
        listeners.remove(l)
    }
}
