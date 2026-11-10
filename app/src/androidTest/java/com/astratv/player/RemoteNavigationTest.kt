@file:OptIn(androidx.media3.common.util.UnstableApi::class,androidx.compose.ui.ExperimentalComposeUiApi::class)
package com.astratv.player

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Run on an empty installation: ./gradlew connectedDebugAndroidTest. No paid source needed. */
@RunWith(AndroidJUnit4::class)
class RemoteNavigationTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    @Test fun dpadNavigatesDestinationsAndBackReturnsToLive() {
        val live=compose.onNodeWithText("Live TV",useUnmergedTree=false)
        live.performSemanticsAction(SemanticsActions.RequestFocus)
        live.performKeyInput { keyDown(Key.DirectionDown);keyUp(Key.DirectionDown) }
        compose.onAllNodesWithText("Guide")[0].assertIsFocused()
        compose.onAllNodesWithText("Guide")[0].performKeyInput { keyDown(Key.DirectionCenter);keyUp(Key.DirectionCenter) }
        compose.onAllNodesWithText("Guide").assertCountEquals(2)
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithText("Your television, together.").assertIsDisplayed()
    }
    @Test fun initialSetupOpensActualImportControls() {
        compose.onNodeWithText("Add a playlist").performSemanticsAction(SemanticsActions.RequestFocus)
        compose.onNodeWithText("Add a playlist").performKeyInput { keyDown(Key.DirectionCenter);keyUp(Key.DirectionCenter) }
        compose.onNodeWithText("Add playlist").assertIsDisplayed()
        compose.onNodeWithText("Playlist name").assertExists()
        compose.onNodeWithText("Xtream").assertExists()
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithText("Bring your own channels.").assertIsDisplayed()
    }
}
