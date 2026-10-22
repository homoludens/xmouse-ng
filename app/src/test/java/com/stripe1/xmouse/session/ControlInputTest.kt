package com.stripe1.xmouse.session

import org.junit.Assert.*
import org.junit.Test

class ControlInputTest {
    private class Sensors(var available: Boolean = true, var acceptsRegistration: Boolean = true) :
        GyroscopeSamplingGate.SensorFacade {
        val registered = mutableListOf<GyroscopeSamplingGate.Listener>()
        val unregistered = mutableListOf<GyroscopeSamplingGate.Listener>()
        override fun hasGyroscope() = available
        override fun register(listener: GyroscopeSamplingGate.Listener): Boolean {
            if (!acceptsRegistration) return false
            registered += listener
            return true
        }
        override fun unregister(listener: GyroscopeSamplingGate.Listener) { unregistered += listener }
    }

    @Test fun gyroRegistersOnlyForResumedEnabledSurfaceAndRejectsLateCallbacks() {
        val sensors = Sensors()
        val delivered = mutableListOf<Pair<InputSurfaceToken, Pair<Float, Float>>>()
        val gate = GyroscopeSamplingGate(sensors, GyroscopeSamplingGate.MotionCallback { token, y, z ->
            delivered += token to (y to z)
        })
        val first = InputSurfaceToken()
        gate.update(resumed = false, controlSurface = first, userEnabled = true)
        assertFalse(gate.isSampling)
        assertTrue(sensors.registered.isEmpty())

        gate.update(resumed = true, controlSurface = first, userEnabled = true)
        assertTrue(gate.isSampling)
        val oldCallback = sensors.registered.single()
        oldCallback.onSample(1f, 2f)
        assertEquals(listOf(first to (1f to 2f)), delivered)

        gate.update(resumed = false, controlSurface = first, userEnabled = true)
        assertFalse(gate.isSampling)
        val second = InputSurfaceToken()
        gate.update(resumed = true, controlSurface = second, userEnabled = true)
        val newCallback = sensors.registered.last()
        oldCallback.onSample(3f, 4f)
        newCallback.onSample(5f, 6f)

        assertEquals("callbacks from an unregistered surface cannot emit after reactivation",
            listOf(first to (1f to 2f), second to (5f to 6f)), delivered)
        assertTrue(sensors.unregistered.contains(oldCallback))
    }

    @Test fun missingSensorAndRejectedRegistrationAreSafeAndDoNotEraseEnableChoice() {
        val sensors = Sensors(available = false)
        var delivered = 0
        val gate = GyroscopeSamplingGate(sensors, GyroscopeSamplingGate.MotionCallback { _, _, _ -> delivered++ })
        val token = InputSurfaceToken()
        gate.update(resumed = true, controlSurface = token, userEnabled = true)
        assertFalse(gate.isSampling)
        assertTrue(sensors.registered.isEmpty())

        sensors.available = true
        sensors.acceptsRegistration = false
        gate.update(resumed = true, controlSurface = token, userEnabled = true)
        assertFalse(gate.isSampling)
        assertEquals("the UI preference remains enabled; registration failure is only a sampling failure",
            0, delivered)

        sensors.acceptsRegistration = true
        gate.update(resumed = true, controlSurface = token, userEnabled = true)
        assertTrue(gate.isSampling)
        sensors.registered.last().onSample(1f, 1f)
        assertEquals(1, delivered)
        gate.update(resumed = true, controlSurface = token, userEnabled = false)
        assertFalse(gate.isSampling)
    }

    @Test fun heldParserRecognizesOnlyKnownAdapterTranslations() {
        assertEquals(
            TrackedMouseButtonCommand("xdotool:1", true, "xdotool mouseup 1"),
            TrackedInputCommand.parse("xdotool mousedown 1"),
        )
        assertEquals(
            TrackedMouseButtonCommand("ydotool:0", true, "ydotool click 0x80"),
            TrackedInputCommand.parse("ydotool click 0x40"),
        )
        assertEquals(
            TrackedMouseButtonCommand("ydotool:1", false, "ydotool click 0x81"),
            TrackedInputCommand.parse("ydotool click 0x81"),
        )
        listOf(
            "xdotool mousedown 4", "xdotool mouseup 0", "xdotool mousedown 1; arbitrary",
            "printf 'xdotool mousedown 1'", "ydotool click 0xC0", "ydotool click 0x400",
        ).forEach { assertNull("arbitrary shell and non-held commands remain opaque: $it", TrackedInputCommand.parse(it)) }
    }
}
