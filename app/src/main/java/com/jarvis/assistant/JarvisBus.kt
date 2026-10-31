package com.jarvis.assistant

import android.os.Handler
import android.os.Looper

/** Tiny in-process bridge so the service can tell the screen what Jarvis is doing. */
object JarvisBus {
    enum class State { OFF, IDLE, LISTENING, THINKING, SPEAKING }

    @Volatile var state: State = State.OFF
        private set
    @Volatile var text: String = ""
        private set

    private val main = Handler(Looper.getMainLooper())

    /** Set from the Activity (main thread). */
    var listener: ((State, String) -> Unit)? = null

    fun set(s: State, t: String = "") {
        state = s
        text = t
        main.post { listener?.invoke(s, t) }
    }
}
