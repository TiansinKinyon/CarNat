package com.baidu.carlife.xiaomi.ui

import android.content.res.Configuration
import androidx.annotation.DrawableRes
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigationevent.NavigationEventTransitionState
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import com.baidu.carlife.xiaomi.R
import com.baidu.carlife.xiaomi.ui.help.HelpScreen
import com.baidu.carlife.xiaomi.ui.monitoring.MonitoringScreen
import com.baidu.carlife.xiaomi.ui.projection.ProjectionReadyPreviewContent
import com.baidu.carlife.xiaomi.ui.projection.ProjectionScreen
import com.baidu.carlife.xiaomi.ui.settings.DeveloperSettingsScreen
import com.baidu.carlife.xiaomi.ui.settings.AboutScreen
import com.baidu.carlife.xiaomi.ui.settings.ProjectionLogScreen
import com.baidu.carlife.xiaomi.ui.settings.SettingEntry
import com.baidu.carlife.xiaomi.ui.settings.UpdateScreen
import com.baidu.carlife.xiaomi.ui.settings.SettingsScreen
import com.baidu.carlife.xiaomi.ui.theme.CarNatTheme

private enum class Destination(
    val route: String,
    val label: String,
    @get:DrawableRes val iconRes: Int,
) {
    CONNECT("connect", "连接", R.drawable.ic_connect),

    MONITORING("monitoring", "检测", R.drawable.ic_monitoring),
    HELP("help", "帮助", R.drawable.ic_help),
    SETTINGS("settings", "设置", R.drawable.ic_settings),
}

@Composable
internal fun CarNatApp() {
    CarNatNavigation { active -> ProjectionScreen(active = active) }
}

@Composable
private fun CarNatNavigation(connectionPage: @Composable (Boolean) -> Unit) {
    val navController = rememberNavController()
    val currentEntry by navController.currentBackStackEntryAsState()
    val hostLifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val navigationEventDispatcher = checkNotNull(LocalNavigationEventDispatcherOwner.current)
        .navigationEventDispatcher
    val navigationTransition by navigationEventDispatcher.transitionState.collectAsState()

    fun isPageActive(entry: NavBackStackEntry): Boolean {
        // Cancelling a seek can leave the entry in STARTED even though it is still on top.
        return entry.id == currentEntry?.id &&
            hostLifecycle == Lifecycle.State.RESUMED &&
            navigationTransition == NavigationEventTransitionState.Idle
    }

    NavHost(
        navController = navController,
        startDestination = Destination.CONNECT.route,
        modifier = Modifier.fillMaxSize().clipToBounds(),
        enterTransition = {
            if (targetState.isSettingDetail) slideInHorizontally(tween(280)) { it }
            else fadeIn(tween(180))
        },
        exitTransition = { ExitTransition.None },
        popEnterTransition = { EnterTransition.None },
        popExitTransition = {
            if (initialState.isSettingDetail) slideOutHorizontally(tween(240)) { it }
            else fadeOut(tween(180))
        },
        predictivePopEnterTransition = { EnterTransition.None },
        predictivePopExitTransition = {
            if (initialState.isSettingDetail) slideOutHorizontally(tween(240, easing = LinearEasing)) { it }
            else fadeOut(tween(180, easing = LinearEasing))
        },
    ) {
        Destination.entries.forEach { destination ->
            composable(destination.route) { entry ->
                NavigationPage(
                    active = isPageActive(entry),
                    title = destination.label,
                    selectedDestination = destination,
                    onNavigate = { item ->
                        if (item != destination) {
                            navController.navigate(item.route) {
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    },
                ) { active ->
                    when (destination) {
                        Destination.CONNECT -> connectionPage(active)
                        Destination.HELP -> HelpScreen()
                        Destination.MONITORING -> MonitoringScreen(active)
                        Destination.SETTINGS -> SettingsScreen(active) { setting ->
                            if (active) navController.navigate(setting.route) { launchSingleTop = true }
                        }
                    }
                }
            }
        }
        SettingEntry.entries.forEach { setting ->
            composable(setting.route) { entry ->
                if (setting != SettingEntry.DEVELOPER) {
                    NavigationPage(
                        active = isPageActive(entry),
                        title = setting.title,
                        onBack = { navController.popBackStack() },
                        content = { active ->
                            when (setting) {
                                SettingEntry.LOGS -> ProjectionLogScreen(active)
                                SettingEntry.ABOUT -> AboutScreen(active)
                                SettingEntry.UPDATE -> UpdateScreen(active)
                                SettingEntry.DEVELOPER -> Unit
                            }
                        },
                    )
                } else DeveloperSettingsScreen { actions, body ->
                    NavigationPage(
                        active = isPageActive(entry),
                        title = setting.title,
                        onBack = { navController.popBackStack() },
                        topBarActions = actions,
                        content = body,
                    )
                }
            }
        }
    }
}

private val NavBackStackEntry.isSettingDetail: Boolean
    get() = SettingEntry.entries.any { it.route == destination.route }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NavigationPage(
    active: Boolean,
    title: String,
    selectedDestination: Destination? = null,
    onNavigate: (Destination) -> Unit = {},
    onBack: (() -> Unit)? = null,
    topBarActions: @Composable (Boolean) -> Unit = {},
    content: @Composable (Boolean) -> Unit = {},
) {
    val latestActive = rememberUpdatedState(active)
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .gatePageInput { latestActive.value }
            .then(if (active) Modifier else Modifier.clearAndSetSemantics { hideFromAccessibility() }),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Row(Modifier.fillMaxSize()) {
            if (landscape && selectedDestination != null) {
                NavigationRail(
                    modifier = Modifier.fillMaxHeight(),
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                ) {
                    Destination.entries.forEach { item ->
                        NavigationRailItem(
                            selected = selectedDestination == item,
                            onClick = { if (active) onNavigate(item) },
                            icon = { DestinationIcon(item) },
                            label = { Text(item.label) },
                        )
                    }
                }
            }
            Scaffold(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                topBar = {
                    TopAppBar(
                        title = { Text(title) },
                        actions = { topBarActions(active) },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainer,
                        ),
                        navigationIcon = {
                            if (onBack != null) {
                                IconButton(onClick = { if (active) onBack() }) {
                                    Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = "返回设置")
                                }
                            }
                        },
                    )
                },
                bottomBar = {
                    if (!landscape && selectedDestination != null) {
                        NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                            Destination.entries.forEach { item ->
                                NavigationBarItem(
                                    selected = selectedDestination == item,
                                    onClick = { if (active) onNavigate(item) },
                                    icon = { DestinationIcon(item) },
                                    label = { Text(item.label) },
                                )
                            }
                        }
                    }
                },
            ) { innerPadding ->
                Box(Modifier.fillMaxSize().padding(innerPadding).consumeWindowInsets(innerPadding)) {
                    content(active)
                }
            }
        }
    }
}

private fun Modifier.gatePageInput(isActive: () -> Boolean): Modifier = this
    .onPreviewKeyEvent { !isActive() }
    .pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            var blockedGesture = false
            var event = currentEvent
            while (true) {
                // A gesture blocked during a transition stays blocked through the last pointer up.
                if (!isActive()) blockedGesture = true
                if (blockedGesture) event.changes.forEach { it.consume() }
                if (event.changes.none { it.pressed }) break
                event = awaitPointerEvent(PointerEventPass.Initial)
            }
        }
    }

@Composable
private fun DestinationIcon(destination: Destination) {
    Icon(painterResource(destination.iconRes), contentDescription = null)
}

@Preview(showBackground = true, widthDp = 360, heightDp = 720)
@Composable
private fun CarNatAppPreview() {
    CarNatTheme { CarNatNavigation { ProjectionReadyPreviewContent() } }
}
