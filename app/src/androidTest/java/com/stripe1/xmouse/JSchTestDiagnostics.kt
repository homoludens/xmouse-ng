package com.stripe1.xmouse

import com.jcraft.jsch.JSch
import com.jcraft.jsch.Logger
import java.util.Hashtable

/** Captures selected JSch errors while instrumentation tests use synthetic keys only. */
internal object JSchTestDiagnostics {
    private val lock = Any()

    fun <T> withCapture(action: (Capture) -> T): T = synchronized(lock) {
        val previousLogger = JSch.getLogger()
        val previousConfig = JSch.getConfig()
        val capture = Capture()
        JSch.setLogger(capture)
        try {
            action(capture)
        } finally {
            try {
                JSch.setConfig(Hashtable<String, String>().apply { putAll(previousConfig) })
            } finally {
                JSch.setLogger(previousLogger)
            }
        }
    }

    class Capture : Logger {
        private val entries = mutableListOf<String>()

        override fun isEnabled(level: Int): Boolean = level == Logger.ERROR

        @Synchronized override fun log(level: Int, message: String) {
            if (level == Logger.ERROR && errorMessages.any(message::startsWith)) {
                entries += message
            }
        }

        @Synchronized fun checkpoint(): Int = entries.size

        @Synchronized fun renderSince(checkpoint: Int = 0): String =
            entries.drop(checkpoint).takeIf { it.isNotEmpty() }?.joinToString("\n--- JSch error ---\n")
                ?: "No captured JSch crypto errors."

        private companion object {
            val errorMessages = listOf(
                "failed to generate signature",
                "failed to create verifier",
                "failed to decrypt key",
                "failed to parse key",
                "failed to create cipher",
            )
        }
    }
}
