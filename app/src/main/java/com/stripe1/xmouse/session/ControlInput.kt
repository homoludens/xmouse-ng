package com.stripe1.xmouse.session

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

/**
 * Small lifecycle gate around platform sensor registration. Each registration captures its surface
 * capability; callbacks already queued for an old registration are ignored after stop/restart.
 */
internal class GyroscopeSamplingGate(
    private val sensors: SensorFacade,
    private val onMotion: MotionCallback,
) {
    interface SensorFacade {
        fun hasGyroscope(): Boolean
        fun register(listener: Listener): Boolean
        fun unregister(listener: Listener)
    }

    fun interface Listener {
        fun onSample(y: Float, z: Float)
    }

    fun interface MotionCallback {
        fun onMotion(surface: InputSurfaceToken, y: Float, z: Float)
    }

    private var resumed = false
    private var controlSurface: InputSurfaceToken? = null
    private var userEnabled = false
    private var registeredSurface: InputSurfaceToken? = null
    private var activeListener: Listener? = null

    val isSampling: Boolean get() = activeListener != null

    fun update(resumed: Boolean, controlSurface: InputSurfaceToken?, userEnabled: Boolean) {
        this.resumed = resumed
        this.controlSurface = controlSurface
        this.userEnabled = userEnabled
        val shouldSample = resumed && controlSurface != null && userEnabled && safeHasSensor()
        if (!shouldSample) {
            stop()
            return
        }
        if (registeredSurface === controlSurface && activeListener != null) return

        stop()
        val surface = requireNotNull(controlSurface)
        lateinit var candidate: Listener
        candidate = Listener { y, z ->
            if (activeListener === candidate && registeredSurface === surface && this.resumed &&
                this.userEnabled && this.controlSurface === surface) {
                onMotion.onMotion(surface, y, z)
            }
        }
        val registered = try { sensors.register(candidate) } catch (_: RuntimeException) { false }
        if (registered) {
            registeredSurface = surface
            activeListener = candidate
        }
    }

    fun stop() {
        val old = activeListener
        // Invalidate before calling the platform so already-queued callbacks cannot emit input.
        activeListener = null
        registeredSurface = null
        if (old != null) {
            try { sensors.unregister(old) } catch (_: RuntimeException) { }
        }
    }

    private fun safeHasSensor(): Boolean = try { sensors.hasGyroscope() } catch (_: RuntimeException) { false }
}
