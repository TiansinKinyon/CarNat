package com.baidu.carlife.xiaomi.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.baidu.carlife.xiaomi.R
import com.baidu.carlife.xiaomi.ui.help.HelpScreen
import com.baidu.carlife.xiaomi.ui.projection.ProjectionReadyPreviewContent
import com.baidu.carlife.xiaomi.ui.projection.ProjectionScreen
import com.baidu.carlife.xiaomi.ui.settings.SettingDetailScreen
import com.baidu.carlife.xiaomi.ui.settings.SettingEntry
import com.baidu.carlife.xiaomi.ui.settings.SettingsScreen
import com.baidu.carlife.xiaomi.ui.theme.CarNatTheme

private enum class Destination(
    val route: String,
    val label: String,
    @get:DrawableRes val iconRes: Int,
) {
    CONNECT("connect", "连接", R.drawable.ic_connect),
    HELP("help", "帮助", R.drawable.ic_help),
    SETTINGS("settings", "设置", R.drawable.ic_settings),
}

@Composable
internal fun CarNatApp() {
    CarNatNavigation { ProjectionScreen() }
}

@Composable
private fun CarNatNavigation(connectionPage: @Composable () -> Unit) {
    val navController = rememberNavController()
    val entry by navController.currentBackStackEntryAsState()
    val route = entry?.destination?.route ?: Destination.CONNECT.route

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            NavigationBar {
                Destination.entries.forEach { destination ->
                    val selected = route == destination.route ||
                        (destination == Destination.SETTINGS && route.startsWith("settings/"))
                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            if (!selected) {
                                navController.navigate(destination.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        },
                        icon = {
                            Icon(painterResource(destination.iconRes), contentDescription = null)
                        },
                        label = { Text(destination.label) },
                    )
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Destination.CONNECT.route,
            modifier = Modifier.padding(innerPadding).consumeWindowInsets(innerPadding),
        ) {
            composable(Destination.CONNECT.route) { connectionPage() }
            composable(Destination.HELP.route) { HelpScreen() }
            composable(Destination.SETTINGS.route) {
                SettingsScreen(onOpen = { navController.navigate(it.route) { launchSingleTop = true } })
            }
            SettingEntry.entries.forEach { setting ->
                composable(setting.route) {
                    SettingDetailScreen(setting, onBack = { navController.popBackStack() })
                }
            }
        }
    }
}

@Preview(name = "CarNat 底部导航", showBackground = true, widthDp = 360, heightDp = 720)
@Composable
private fun CarNatAppPreview() {
    CarNatTheme { CarNatNavigation { ProjectionReadyPreviewContent() } }
}
