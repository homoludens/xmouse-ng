package com.stripe1.xmouse.layout

import android.content.Context
import android.content.Intent
import android.util.AtomicFile
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.io.FileNotFoundException
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Focused Compose semantics coverage for the manager's uncapped, ID-addressed profile list. */
@RunWith(AndroidJUnit4::class)
class LayoutProfileManagerAndroidTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test
    fun listsMoreThanThreeProfilesAndSelectsByStableId() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val profileDirectory = File(context.filesDir, LayoutEditorActivity.PROFILE_DIRECTORY)
        val catalogFile = File(profileDirectory, "profiles.catalog.json")
        val originalCatalog = readAtomicSnapshot(catalogFile)
        val repository = LayoutProfileRepository(profileDirectory)
        val originalProfiles = repository.listProfiles()
        val cleanupAnchor = if (originalProfiles.isEmpty()) {
            LayoutProfile(id = "manager-ui-anchor-${UUID.randomUUID()}", name = "Manager test anchor")
        } else {
            null
        }
        val profiles = (1..5).map { index ->
            LayoutProfile(
                id = "manager-ui-${UUID.randomUUID()}",
                name = if (index <= 2) "Same name" else "Profile $index",
                buttons = listOf(LayoutButton("manager-ui-button-$index", "Button $index", "echo $index", 1)),
            )
        }
        val originalProfileIds = originalProfiles.mapTo(mutableSetOf(), LayoutProfile::id)
        val ownedProfileIds = mutableListOf<String>()

        fun createOwnedProfile(profile: LayoutProfile) {
            try {
                repository.createProfile(profile)
                ownedProfileIds += profile.id
            } catch (failure: Exception) {
                val persisted = runCatching { repository.loadProfile(profile.id) }.getOrNull()
                if (profile.id !in originalProfileIds && persisted == profile) {
                    ownedProfileIds += profile.id
                }
                throw failure
            }
        }

        try {
            cleanupAnchor?.let(::createOwnedProfile)
            profiles.forEach(::createOwnedProfile)
            repository.selectProfile(profiles.last().id)

            ActivityScenario.launch<LayoutProfileManagerActivity>(
                Intent(context, LayoutProfileManagerActivity::class.java),
            ).use {
                compose.onNodeWithText("Selected layout ID: ${profiles.last().id}").assertExists()
                profiles.forEach { profile ->
                    compose.onNodeWithTag("layout-profiles-list")
                        .performScrollToNode(hasTestTag("layout-profile-row-${profile.id}"))
                    compose.onNodeWithTag("layout-profile-row-${profile.id}").assertExists()
                }

                compose.onNodeWithTag("layout-profiles-list")
                    .performScrollToNode(hasTestTag("layout-profile-select-${profiles.first().id}"))
                compose.onNodeWithTag("layout-profile-select-${profiles.first().id}").performClick()
                compose.onNodeWithText("Selected layout ID: ${profiles.first().id}").assertExists()
                assertEquals(profiles.first().id, repository.loadSelectedProfile()?.id)
            }
        } finally {
            ownedProfileIds.forEach { profileId ->
                deleteAtomicRecord(File(profileDirectory, "$profileId.profile.json"))
                deleteAtomicRecord(File(profileDirectory, "$profileId.draft.json"))
            }
            restoreAtomicSnapshot(catalogFile, originalCatalog)
        }
    }

    private fun readAtomicSnapshot(file: File): ByteArray? = try {
        AtomicFile(file).openRead().use { input -> input.readBytes() }
    } catch (missing: FileNotFoundException) {
        if (file.exists() || File("${file.path}.bak").exists()) throw missing
        null
    }

    private fun deleteAtomicRecord(file: File) {
        AtomicFile(file).delete()
    }

    private fun restoreAtomicSnapshot(file: File, bytes: ByteArray?) {
        val atomicFile = AtomicFile(file)
        if (bytes == null) {
            atomicFile.delete()
            return
        }

        val output = atomicFile.startWrite()
        try {
            output.write(bytes)
            atomicFile.finishWrite(output)
        } catch (failure: Throwable) {
            atomicFile.failWrite(output)
            throw failure
        }
    }
}
