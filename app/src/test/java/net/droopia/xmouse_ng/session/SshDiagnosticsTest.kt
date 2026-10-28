package net.droopia.xmouse_ng.session

import net.droopia.xmouse_ng.connection.ConnectionFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SshDiagnosticsTest {
    @Test fun diagnosticPayloadIsTypedAndRendersOnlyAllowlistedCodes() {
        val event = SshDiagnosticEvent(
            SshDiagnosticStage.AUTHENTICATING,
            SshAuthenticationMode.PASSWORD,
            SshAuthenticationMethod.KEYBOARD_INTERACTIVE,
            ConnectionFailure.Authentication(ConnectionFailure.AuthenticationReason.REJECTED),
        )
        assertEquals(
            "stage=AUTHENTICATING auth_mode=PASSWORD auth_method=KEYBOARD_INTERACTIVE " +
                "failure_category=AUTHENTICATION failure_reason=REJECTED",
            event.toSafeLogMessage(),
        )

        val hostileValues = listOf("NOT_REAL_user", "desktop.local", "password=NOT_REAL_secret",
            "ssh-ed25519 PRIVATE_KEY_BYTES", "server prompt", "command=xdotool", "raw provider exception")
        val output = event.toSafeLogMessage() + event
        hostileValues.forEach { assertFalse("diagnostic included $it", output.contains(it)) }

        val fields = SshDiagnosticEvent::class.java.declaredFields.filterNot {
            it.isSynthetic || it.name == "\$stable"
        }
        assertEquals(setOf("stage", "authMode", "authMethod", "failure"), fields.map { it.name }.toSet())
        assertTrue(fields.all {
            it.type.isEnum || it.type == ConnectionFailure::class.java
        })
    }

    @Test fun aFailingDiagnosticSinkCannotEscapeIntoConnectionFlow() {
        val diagnostics = SshDiagnostics { throw IllegalStateException("NOT_REAL_diagnostic_sink_failure") }
        diagnostics.record(SshDiagnosticEvent(SshDiagnosticStage.CONNECTING))
    }
}
