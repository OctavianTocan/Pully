package com.octaviantocan.pulls.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import com.octaviantocan.pulls.ActiveAccountTheme
import com.octaviantocan.pulls.BuildConfig
import com.octaviantocan.pulls.data.Account
import com.octaviantocan.pulls.data.NativeEntry
import com.octaviantocan.pulls.data.NativeWorkspaceBinding
import com.octaviantocan.pulls.ui.explore.NativeEntryMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Exercises the real Android entry loader and PullsApp, before choosing either workspace. */
class NativeEntryStartupTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Before fun stagingVariant() {
        assumeTrue(BuildConfig.UPDATE_CHANNEL == "staging/")
    }

    @Test fun upgradedBuiltInStagingOffersGitHubBeforeLegacyEmail() {
        launch(BuildConfig.DEFAULT_SERVER, verified = true, welcomeSeen = true)
        expectGitHub()
        compose.runOnIdle {
            assertEquals(NativeEntryMode.Explore, NativeEntry.get(compose.activity).mode)
            assertEquals(BuildConfig.DEFAULT_SERVER, compose.activity.getSharedPreferences("pulls-server", 0).getString("url", null))
            assertEquals("fixture-preserved", compose.activity.getSharedPreferences("pulls-server", 0).getString("model", null))
            assertEquals(BuildConfig.DEFAULT_SERVER, compose.activity.getSharedPreferences("pully-account", 0).getString("verifiedServer", null))
        }
    }

    @Test fun oldPublicWelcomeDoesNotHideTheNewGitHubEntry() {
        launch(BuildConfig.DEFAULT_SERVER, verified = false, welcomeSeen = true)
        expectGitHub()
    }

    @Test fun freshStagingOffersGitHubWithoutEmail() {
        launch(BuildConfig.DEFAULT_SERVER, verified = false, welcomeSeen = false)
        expectGitHub()
    }

    @Test fun customServerKeepsItsExistingConnectionAndEmailFlow() {
        launch("https://custom.invalid", verified = true, welcomeSeen = true)
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Email").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Email").assertIsDisplayed()
        compose.onNodeWithText("Connect GitHub").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(NativeEntryMode.ExistingServer, NativeEntry.get(compose.activity).mode)
            assertEquals("https://custom.invalid", compose.activity.getSharedPreferences("pulls-server", 0).getString("url", null))
            assertFalse(compose.activity.getSharedPreferences("pully-entry", 0).getBoolean("githubEntryIntroduced", false))
        }
    }

    @Test fun dismissedGitHubIntroductionIsNotRepeated() {
        launch(BuildConfig.DEFAULT_SERVER, verified = false, welcomeSeen = true, introduced = true)
        compose.runOnIdle { assertEquals(NativeEntryMode.Explore, NativeEntry.get(compose.activity).mode) }
        compose.onNodeWithText("Connect GitHub").assertDoesNotExist()
        compose.onNodeWithText("Email").assertDoesNotExist()
    }

    private fun expectGitHub() {
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Connect GitHub").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Connect GitHub").assertIsDisplayed()
        compose.onNodeWithText("Use an existing Pully account").assertDoesNotExist()
        compose.onNodeWithText("Email").assertDoesNotExist()
    }

    private fun launch(server: String, verified: Boolean, welcomeSeen: Boolean, introduced: Boolean = false) {
        compose.runOnUiThread {
            val context = compose.activity
            NativeWorkspaceBinding.active?.close()
            Commands.close()
            context.getSharedPreferences("pulls-server", 0).edit().clear()
                .putString("url", server).putString("model", "fixture-preserved").commit()
            context.getSharedPreferences("pully-account", 0).edit().clear().apply {
                if (verified) putBoolean("hasAccount", true).putString("statusServer", server).putString("verifiedServer", server)
            }.commit()
            context.getSharedPreferences("pully-entry", 0).edit().clear()
                .putString("mode", if (verified) NativeEntryMode.ExistingServer.name else NativeEntryMode.Explore.name)
                .putBoolean("githubEntryIntroduced", introduced).commit()
            context.getSharedPreferences("pully-explore", 0).edit().clear().putBoolean("welcomeSeen", welcomeSeen).commit()
            for (name in listOf("pulls-ui", "pulls-ui-workspace-explore")) {
                context.getSharedPreferences(name, 0).edit().putString("locale", "en").putBoolean("updateNotifications", false).commit()
            }
            // A process restart recreates these two cached snapshots from the persisted values above.
            NativeEntry::class.java.getDeclaredField("state").apply { isAccessible = true }.set(null, null)
            Account::class.java.getDeclaredField("loaded").apply { isAccessible = true }.setBoolean(null, false)
        }
        compose.setContent { ActiveAccountTheme { PullsApp(null) } }
    }
}
