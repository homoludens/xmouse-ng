package com.stripe1.xmouse.secrets

/** Legacy Activity request codes, disjoint from the durable importer's 0x4000+ range. */
internal class ReentryPickerCodes(initial: Int = FIRST) {
    private var next = initial.also { require(it in FIRST..LAST) }

    /** Retire codes for one Activity owner, including cancelled/expired requests and duplicate results. */
    @Synchronized fun allocate(issued: MutableSet<Int>): Int? {
        repeat(LAST - FIRST + 1) {
            val code = next
            next = if (code == LAST) FIRST else code + 1
            if (issued.add(code)) return code
        }
        // No unsafe reuse after 4096 pickers in one owner; cancellation allows reopening the screen.
        return null
    }

    companion object {
        const val FIRST = 0x3000
        const val LAST = 0x3fff
    }
}
