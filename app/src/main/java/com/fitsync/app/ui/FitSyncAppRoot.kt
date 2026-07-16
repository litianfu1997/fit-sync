package com.fitsync.app.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SyncAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.fitsync.app.ui.screens.ActivitiesScreen
import com.fitsync.app.ui.screens.ActivityDetailScreen
import com.fitsync.app.ui.screens.HomeScreen
import com.fitsync.app.ui.screens.PlatformsScreen
import com.fitsync.app.ui.screens.SettingsScreen
import com.fitsync.app.ui.screens.SyncJobFilter
import com.fitsync.app.ui.screens.SyncJobsScreen
import com.fitsync.app.ui.screens.SyncRulesScreen

private data class MainDestination(
    val route: String,
    val label: String,
    val icon: ImageVector,
)

private val destinations = listOf(
    MainDestination("home", "首页", Icons.Default.Home),
    MainDestination("activities", "活动", Icons.AutoMirrored.Filled.List),
    MainDestination("rules", "规则", Icons.Default.SyncAlt),
    MainDestination("platforms", "平台", Icons.Default.Devices),
    MainDestination("settings", "设置", Icons.Default.Settings),
)

@Composable
fun FitSyncAppRoot() {
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val current = backStack?.destination?.route
    val showBottomBar = destinations.any { it.route == current }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            if (showBottomBar) {
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surface,
                    tonalElevation = 8.dp,
                ) {
                    destinations.forEach { destination ->
                        val selected = current == destination.route
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                navController.navigate(destination.route) {
                                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(destination.icon, contentDescription = destination.label) },
                            label = { Text(destination.label, style = MaterialTheme.typography.labelMedium) },
                            colors = NavigationBarItemDefaults.colors(
                                indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                selectedIconColor = MaterialTheme.colorScheme.primary,
                                selectedTextColor = MaterialTheme.colorScheme.primary,
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = "home",
            modifier = Modifier.padding(padding),
        ) {
            composable("home") {
                HomeScreen(
                    onOpenActivities = { navController.navigate("activities") },
                    onOpenJobs = { filter -> navController.navigate("jobs/${filter.routeValue}") },
                )
            }
            composable("activities") {
                ActivitiesScreen { id -> navController.navigate("activity/$id") }
            }
            composable("rules") { SyncRulesScreen() }
            composable("platforms") { PlatformsScreen() }
            composable("settings") { SettingsScreen() }
            composable(
                route = "jobs/{filter}",
                arguments = listOf(navArgument("filter") { type = NavType.StringType }),
            ) { entry ->
                SyncJobsScreen(
                    filter = SyncJobFilter.fromRoute(entry.arguments?.getString("filter")),
                    onBack = { navController.popBackStack() },
                    onOpenActivity = { id -> navController.navigate("activity/$id") },
                )
            }
            composable(
                route = "activity/{activityId}",
                arguments = listOf(navArgument("activityId") { type = NavType.LongType }),
            ) { entry ->
                ActivityDetailScreen(
                    activityId = entry.arguments?.getLong("activityId") ?: return@composable,
                    onBack = { navController.popBackStack() },
                )
            }
        }
    }
}
