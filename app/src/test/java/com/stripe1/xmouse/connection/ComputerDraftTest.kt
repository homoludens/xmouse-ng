package com.stripe1.xmouse.connection

import org.junit.Assert.*
import org.junit.Test

class ComputerDraftTest {
    @Test fun accountIsNeverSilentlyTrimmedAndBlankOrControlInputIsRejected() {
        for (user in listOf("", " ", " user", "user ", "\tuser", "user\n", "u\u0000ser", "\u2003user")) {
            assertFalse(ComputerDraft.validUsername(user))
            assertThrows(IllegalArgumentException::class.java) { ComputerDraft.requireUsername(user) }
        }
        for (user in listOf("root", "fixture-user", "user@example", "用户")) assertTrue(ComputerDraft.validUsername(user))
    }
    @Test fun actualEndpointParserRejectsInvalidHostAndPortWithoutChangingSpelling() {
        for (port in listOf("0", "65536", "-1", "ssh", "", "22x"))
            assertThrows(IllegalArgumentException::class.java) { Endpoint.manual("fixture.invalid", port) }
        for (host in listOf("", "host name", "https://host", "host\n"))
            assertThrows(IllegalArgumentException::class.java) { Endpoint.manual(host, "22") }
        assertEquals(2222, Endpoint.manual("Desktop.local", "2222").port)
    }
}
