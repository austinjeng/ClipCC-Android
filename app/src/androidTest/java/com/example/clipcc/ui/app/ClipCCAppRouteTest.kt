package com.example.clipcc.ui.app

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.media3.common.util.UnstableApi
import org.junit.Rule
import org.junit.Test

@UnstableApi
class ClipCCAppRouteTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun pixel9aTab_rendersComparison_notThe7aBenchmarkScreen() {
        compose.setContent { ClipCCApp(onKeepAwake = {}) }

        compose.onNodeWithText("Pixel 9a").performClick()

        // Pixel9a-specific content (the hero "% faster" line) is shown…
        compose.onNodeWithText("% faster", substring = true).assertIsDisplayed()
        // …and the 7a-only Benchmark header ("median-of-3") is NOT rendered.
        compose.onNodeWithText("median-of-3", substring = true).assertDoesNotExist()
    }
}
