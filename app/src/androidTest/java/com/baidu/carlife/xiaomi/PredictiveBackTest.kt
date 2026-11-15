package com.baidu.carlife.xiaomi

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.navigationevent.NavigationEvent
import androidx.navigationevent.NavigationEventInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PredictiveBackTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Before
    fun dismissStartupDialogs() {
        compose.onNodeWithText("安全驾驶须知").assertIsDisplayed()
        tap("确定")
        compose.onNodeWithText("免责声明").assertIsDisplayed()
        tap("确定")
    }

    @Test
    fun cancelledBackKeepsTabsAndSettingsDetailInteractive() {
        val input = BackGestureInput()
        compose.runOnIdle { compose.activity.navigationEventDispatcher.addInput(input) }
        try {
            tap("检测")
            cancelBack(input)
            tap("帮助")
            cancelBack(input)
            tap("设置")
            cancelBack(input)
            openDeveloperSettings()

            cancelBack(input)
            compose.onNodeWithContentDescription("返回设置").performTouchInput { click() }
            compose.onNode(hasText("设置") and hasClickAction()).assertIsDisplayed()
            tap("连接")
            compose.onNodeWithText("CarWith").assertIsDisplayed()
        } finally {
            compose.runOnIdle { compose.activity.navigationEventDispatcher.removeInput(input) }
        }
    }

    @Test
    fun cancellationBeforeFirstAnimationFrameKeepsCurrentPageInteractive() {
        val input = BackGestureInput()
        compose.runOnIdle { compose.activity.navigationEventDispatcher.addInput(input) }
        try {
            tap("设置")
            compose.runOnIdle {
                input.start()
                input.moveHalfway()
                input.cancel()
            }
            openDeveloperSettings()
        } finally {
            compose.runOnIdle { compose.activity.navigationEventDispatcher.removeInput(input) }
        }
    }

    private fun cancelBack(input: BackGestureInput) {
        compose.runOnIdle { input.start() }
        compose.runOnIdle { input.moveHalfway() }
        compose.runOnIdle { input.cancel() }
    }

    private fun tap(label: String) {
        compose.onNode(hasText(label) and hasClickAction()).performTouchInput { click() }
    }

    private fun openDeveloperSettings() {
        compose.onNode(hasText("开发者选项") and hasClickAction())
            .performScrollTo()
            .performTouchInput { click() }
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onNodeWithContentDescription("返回设置").isDisplayed()
        }
    }

    private class BackGestureInput : NavigationEventInput() {
        fun start() = dispatchOnBackStarted(NavigationEvent(swipeEdge = NavigationEvent.EDGE_LEFT))
        fun moveHalfway() = dispatchOnBackProgressed(
            NavigationEvent(swipeEdge = NavigationEvent.EDGE_LEFT, progress = 0.5f),
        )
        fun cancel() = dispatchOnBackCancelled()
    }
}
