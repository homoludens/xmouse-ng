package net.droopia.xmouse_ng.secrets

import org.junit.Assert.*
import org.junit.Test

class ReentryPickerCodesTest {
    @Test fun wrapsAtBoundaryWithinItsOwnLower16BitRange() {
        val codes = ReentryPickerCodes(ReentryPickerCodes.LAST)
        val issued = mutableSetOf<Int>()
        assertEquals(0x3fff, codes.allocate(issued))
        assertEquals(0x3000, codes.allocate(issued))
        assertEquals(0x3001, codes.allocate(issued))
    }

    @Test fun wrappedAllocatorSkipsRetiredCodesSoLateOrDuplicateResultsCannotMatchANewerRequest() {
        val codes = ReentryPickerCodes(ReentryPickerCodes.LAST)
        val retired = mutableSetOf(0x3fff, 0x3000)
        assertEquals(0x3001, codes.allocate(retired))
        assertEquals(setOf(0x3fff, 0x3000, 0x3001), retired)
    }

    @Test fun exhaustedOwnerCancelsRatherThanReusingCodesAndNewOwnerCanAllocate() {
        val codes = ReentryPickerCodes()
        val issued = mutableSetOf<Int>()
        repeat(4096) {
            val code = codes.allocate(issued)!!
            assertTrue(code in 0x3000..0x3fff)
            assertEquals(0, code and 0xffff.inv())
        }
        assertEquals(4096, issued.size)
        assertNull(codes.allocate(issued))
        assertEquals(0x3000, codes.allocate(mutableSetOf()))
    }
}
