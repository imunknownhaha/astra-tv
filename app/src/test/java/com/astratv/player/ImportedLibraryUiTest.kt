@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
package com.astratv.player

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.room.withTransaction
import androidx.lifecycle.ViewModelProvider
import com.astratv.player.feature.MainViewModel
import com.astratv.player.core.database.*
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Exercises the formerly untested welcome -> imported TV library composition transition. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34], application=AstraApplication::class, qualifiers="w960dp-h540dp-land")
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class ImportedLibraryUiTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    @Test fun newProviderCatalogReplacesWelcomeAndFavoritesRemainFunctional() {
        compose.waitUntil(15000) { compose.onAllNodesWithText("Add a playlist").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Add a playlist").performSemanticsAction(SemanticsActions.RequestFocus)
        lateinit var app: AstraApplication
        compose.activityRule.scenario.onActivity { app=it.application as AstraApplication }
        runBlocking {
            app.library.db.withTransaction {
                app.library.dao.putItems((0..199).map { index -> ItemEntity("synthetic-$index","synthetic-source","Channel $index","LIVE","Synthetic","","","","","",0,"",0,0,"","","",1) })
                app.library.dao.putSource(SourceEntity("synthetic-source","Synthetic provider","XTREAM",""))
            }
        }
        compose.waitUntil(15000) { compose.onAllNodesWithText("Channel 0").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Channel 0").assertIsDisplayed()
        compose.onAllNodesWithText("Save")[0].performSemanticsAction(SemanticsActions.RequestFocus)
        compose.onAllNodesWithText("Save")[0].performKeyInput { keyDown(Key.DirectionCenter);keyUp(Key.DirectionCenter) }
        compose.waitUntil(5000) { compose.onAllNodesWithText("Saved").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Saved").assertIsDisplayed()
        compose.onNodeWithText("Channel 0").performSemanticsAction(SemanticsActions.RequestFocus)
        val item=runBlocking { app.library.dao.item("synthetic-0")!! }
        compose.activityRule.scenario.onActivity { ViewModelProvider(it)[MainViewModel::class.java].playing.value=item }
        compose.waitUntil(5000) { compose.onAllNodesWithText("Close player").fetchSemanticsNodes().isNotEmpty() }
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.waitUntil(5000) { compose.onAllNodesWithText("Saved").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Channel 0").assertIsFocused()
    }
}
