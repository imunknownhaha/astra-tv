package com.astratv.player

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.room.withTransaction
import com.astratv.player.core.database.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Separate processes prevent Robolectric resets from retaining a stale Room tracker. */
abstract class PhoneUiScenario {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    @After fun releaseActivity() { compose.activityRule.scenario.close() }
    protected fun tab(name: String)=compose.onNode(hasText(name) and hasAnyAncestor(hasTestTag("phone-navigation")))
    protected fun drawer(name: String)=compose.onNode(hasText(name) and hasAnyAncestor(hasTestTag("phone-drawer")))
    protected fun ready() { compose.waitUntil(15000) { compose.onAllNodesWithText("Add a playlist").fetchSemanticsNodes().isNotEmpty() } }
    protected fun seed() {
        ready()
        lateinit var app: AstraApplication
        compose.activityRule.scenario.onActivity { app=it.application as AstraApplication }
        runBlocking { app.library.db.withTransaction {
            app.library.dao.putItems((0..19).map { ItemEntity("mobile-$it","mobile-source","Channel $it","LIVE","News","","","","","",0,"",0,0,"","","",1) })
            app.library.dao.putSource(SourceEntity("mobile-source","Phone fixture","M3U",""))
        } }
        compose.waitUntil(15000) {
            compose.mainClock.advanceTimeBy(250)
            compose.onAllNodesWithText("Channel 0").fetchSemanticsNodes().isNotEmpty()
        }
    }
}

@RunWith(RobolectricTestRunner::class)
// Robolectric #8460: text-field dialogs oscillate above its default 320dp width.
// This exercises a smaller phone, while the separate library case uses 360dp.
@Config(sdk=[34],application=AstraApplication::class,qualifiers="w320dp-h800dp-port")
class PhonePortraitUiTest: PhoneUiScenario() {
    @Test fun touchImportFavoritesSearchAndBack() {
        assertTrue(BuildConfig.IS_PHONE)
        assertTrue(BuildConfig.APPLICATION_ID.endsWith(".mobile"))
        ready()
        compose.onNodeWithText("Add a playlist").performClick()
        compose.onNodeWithText("Xtream",useUnmergedTree=true).performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithText("Password").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Cancel").performScrollTo().performClick()
        seed()
        compose.onAllNodesWithText("Save")[0].performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText("Saved").fetchSemanticsNodes().isNotEmpty() }
        tab("Favorites").performClick()
        compose.waitUntil(5000) { compose.mainClock.advanceTimeBy(250);compose.onAllNodesWithText("Channel 0").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Channel 0").assertIsDisplayed()
        compose.onNodeWithContentDescription("Open navigation").performClick()
        drawer("Search").performClick()
        compose.onNodeWithText("Search channels, titles and categories").assertIsDisplayed()
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithText("Live").assertIsDisplayed()
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],application=AstraApplication::class,qualifiers="w800dp-h360dp-land")
class PhoneLandscapeUiTest: PhoneUiScenario() {
    @Test fun railAndDrawerKeepDestinationsAccessible() {
        ready()
        tab("Movies").assertIsDisplayed().performClick()
        compose.onNodeWithContentDescription("Open navigation").performClick()
        drawer("Guide").assertIsDisplayed().performClick()
        compose.onNodeWithText("Bring your own channels.").assertIsDisplayed()
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],application=AstraApplication::class,qualifiers="w360dp-h800dp-port")
class PhoneLargeTextUiTest: PhoneUiScenario() {
    @Test fun enlargedTextKeepsLibraryAndActionsVisible() {
        org.robolectric.RuntimeEnvironment.setFontScale(1.3f)
        seed()
        compose.onNodeWithText("Channel 0").assertIsDisplayed()
        compose.onAllNodesWithText("Save")[0].assertIsDisplayed()
        compose.onNodeWithContentDescription("Open navigation").assertIsDisplayed()
        tab("Series").assertIsDisplayed()
        compose.onNodeWithText("All categories").assertIsDisplayed()
    }
}
