package net.droopia.xmouse_ng.session

import java.util.concurrent.atomic.AtomicBoolean

/** Opaque, single-use capabilities. Neither token retains an Activity or View. */
class InputSurfaceToken internal constructor()

class InputGestureToken internal constructor()

internal data class TrackedMouseButtonCommand(
    val backendButton: String,
    val down: Boolean,
    val releaseCommand: String,
)

/** Transport-write state shared by the original DOWN and its one best-effort release. */
internal class TrackedButtonWriteState {
    private val downMayHaveReachedRemote = AtomicBoolean()
    private val releaseMayHaveReachedRemote = AtomicBoolean()

    fun markDownWriteStarting() { downMayHaveReachedRemote.set(true) }
    fun markReleaseWriteStarting() { releaseMayHaveReachedRemote.set(true) }
    fun needsRelease(): Boolean = downMayHaveReachedRemote.get() && !releaseMayHaveReachedRemote.get()
}

/** Recognizes only the exact structured commands emitted by the existing X/YDoTool adapters. */
internal object TrackedInputCommand {
    private val xButton = Regex("^xdotool (mousedown|mouseup) ([1-3])$")
    private val yButton = Regex("^ydotool click 0x([48])([0-2])$")

    fun parse(command: String): TrackedMouseButtonCommand? {
        xButton.matchEntire(command)?.let { match ->
            val down = match.groupValues[1] == "mousedown"
            val button = match.groupValues[2]
            return TrackedMouseButtonCommand(
                backendButton = "xdotool:$button",
                down = down,
                releaseCommand = "xdotool mouseup $button",
            )
        }
        yButton.matchEntire(command)?.let { match ->
            val down = match.groupValues[1] == "4"
            val button = match.groupValues[2]
            return TrackedMouseButtonCommand(
                backendButton = "ydotool:$button",
                down = down,
                releaseCommand = "ydotool click 0x8$button",
            )
        }
        return null
    }
}
