package net.droopia.xmouse_ng

import net.droopia.xmouse_ng.secrets.CredentialHost
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class ComputerMetadataSnapshotTest {
    private fun host(id: Long, name: String, port: String = "2222") =
        CredentialHost(id, "Computer $id", name, "user-$id", port, "opaque-reference", false)

    @Test fun selectionAndListUseExactlyOneFreshMetadataSnapshot() {
        val reads = AtomicInteger()
        val rows = mutableListOf(host(1, "first.local"), host(2, "second.local"))
        val preferences = mutableMapOf("hostPreferenceList" to "2", "setting_host" to "fallback.local",
            "setting_user" to "fallback-user", "setting_port" to "22")
        val first = ComputerMetadataSnapshot.load({ reads.incrementAndGet(); rows }, preferences)
        assertEquals(1, reads.get())
        assertSame(rows[1], first.selected)
        assertEquals("second.local", first.host)
        assertEquals("user-2", first.user)
        assertEquals("2222", first.port)

        rows[1] = host(2, "edited.local", "2200")
        preferences["hostPreferenceList"] = "1"
        val next = ComputerMetadataSnapshot.load({ reads.incrementAndGet(); rows }, preferences)
        assertEquals(2, reads.get())
        assertEquals("first.local", next.host)
        assertEquals("second.local", first.hosts[1].host)
        preferences["hostPreferenceList"] = "2"
        val edited = ComputerMetadataSnapshot.load({ reads.incrementAndGet(); rows }, preferences)
        assertEquals("edited.local", edited.host)
        assertEquals("2200", edited.port)
    }

    @Test fun missingDeletedOrMalformedSelectionRetainsLegacyPreferenceFallbacksAndDefaults() {
        for (selected in listOf("0", "99", "not-an-id")) {
            val fallback = ComputerMetadataSnapshot.load({ listOf(host(1, "saved.local")) }, mapOf(
                "hostPreferenceList" to selected, "setting_host" to "manual.local",
                "setting_user" to "manual-user", "setting_port" to "2201"))
            assertNull(fallback.selected)
            assertEquals("manual.local", fallback.host)
            assertEquals("manual-user", fallback.user)
            assertEquals("2201", fallback.port)
        }
        val empty = ComputerMetadataSnapshot.load({ emptyList() }, emptyMap<String, String>())
        assertNull(empty.selected)
        assertEquals("", empty.host)
        assertEquals("", empty.user)
        assertEquals("22", empty.port)
        val malformed = ComputerMetadataSnapshot.load({ listOf(host(1, "repair.local", "bad-port")) },
            mapOf("hostPreferenceList" to "1"))
        assertEquals("repair.local", malformed.host)
        assertEquals("bad-port", malformed.port)
        assertEquals("bad-port", malformed.hosts.single().port)
    }

    @Test fun mainPreferenceReloadDoesNotOpenDatabaseAndWorkerRetainsStaleResultGuards() {
        val source = root().resolve("app/src/main/java/net/droopia/xmouse_ng/MainActivity.java").readText()
        val preferences = source.substringAfter("public void getPreferences(){")
            .substringBefore("public void xMouseKeyboardSend(")
        assertFalse(preferences.contains("db."))
        assertFalse(preferences.contains("getRowWithId"))
        val reload = source.substringAfter("private void loadComputerMetadata() {")
            .substringBefore("private String computerName(")
        assertTrue(reload.indexOf("repository.execute(() ->") < reload.indexOf("ComputerMetadataSnapshot.load("))
        assertTrue(reload.contains("repository::hosts"))
        assertFalse(reload.contains("repository.selectedHost()"))
        assertFalse(reload.contains("prepare()") || reload.contains("readPassword"))
        assertTrue(reload.contains("generation != computerLoadGeneration || isFinishing() || isDestroyed()"))
        val resume = source.substringAfter("protected void onResume() {").substringBefore("private void requestAutomaticForegroundSession(")
        assertTrue(resume.contains("initMenu();"))
        assertTrue(resume.contains("scheduleAutomaticForegroundSessionAfterVisibleFrame("))
        assertTrue(source.contains("sessionSubscription = sessionBinder.observe("))
    }

    private fun root(): File = generateSequence(File(System.getProperty("user.dir") ?: ".")) { it.parentFile }
        .first { File(it, "app/src/main/java/net/droopia/xmouse_ng/MainActivity.java").isFile }
}
