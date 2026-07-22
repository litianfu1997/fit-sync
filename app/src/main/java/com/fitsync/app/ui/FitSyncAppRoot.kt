package com.fitsync.app.ui

import androidx.compose.animation.core.snap
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import com.fitsync.app.ui.screens.ActivityFilter
import com.fitsync.app.ui.screens.ActivityDetailScreen
import com.fitsync.app.ui.screens.BackgroundSettingsScreen
import com.fitsync.app.ui.screens.HomeScreen
import com.fitsync.app.ui.screens.PlatformsScreen
import com.fitsync.app.ui.screens.PrivacyAboutScreen
import com.fitsync.app.ui.screens.SettingsScreen
import com.fitsync.app.ui.screens.StorageSettingsScreen
import com.fitsync.app.ui.screens.SyncJobFilter
import com.fitsync.app.ui.screens.SyncJobsScreen
import com.fitsync.app.ui.screens.SyncRulesScreen

private data class MainDestination(
    val route: String,
    val label: String,
    val icon: ImageVector,
)

private val destinations = listOf(
    MainDestination("home", "概览", Icons.Default.Home),
    MainDestination("activities", "活动", Icons.AutoMirrored.Filled.List),
    MainDestination("settings", "设置", Icons.Default.Settings),
)

@Composable
fun FitSyncAppRoot() {
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val current = backStack?.destination?.route
    val showBottomBar = destinations.any { it.route == current }
    var activityFilter by rememberSaveable { mutableStateOf(ActivityFilter.ALL) }

    fun navigateToMainDestination(route: String) {
        if (route == current) return
        if (route == "home" && navController.popBackStack("home", inclusive = false)) return

        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            if (showBottomBar) {
                Box(
                    modifier = Modifier.background(MaterialTheme.colorScheme.background)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    NavigationBar(
                        modifier = Modifier.height(70.dp).clip(MaterialTheme.shapes.large),
                        containerColor = MaterialTheme.colorScheme.surface,
                        tonalElevation = 0.dp,
                    ) {
                        destinations.forEach { destination ->
                            val selected = current == destination.route
                            NavigationBarItem(
                                selected = selected,
                                onClick = { navigateToMainDestination(destination.route) },
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
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = "home",
            modifier = Modifier.padding(padding),
            // 使用零时长 alpha 切换，视觉上没有动画，同时确保旧页面图层立即清除，避免拖影。
            enterTransition = { fadeIn(animationSpec = snap()) },
            exitTransition = { fadeOut(animationSpec = snap()) },
            popEnterTransition = { fadeIn(animationSpec = snap()) },
            popExitTransition = { fadeOut(animationSpec = snap()) },
        ) {
            composable("home") {
                HomeScreen(
                    onOpenActivities = { navigateToMainDestination("activities") },
                    onOpenJobs = { filter -> navController.navigate("jobs/${filter.routeValue}") },
                    onOpenPlatforms = { navController.navigate("settings/platforms") },
                )
            }
            composable("activities") {
                ActivitiesScreen(
                    filter = activityFilter,
                    onFilterChange = { activityFilter = it },
                    onOpenActivity = { id -> navController.navigate("activity/$id") },
                    onOpenPlatforms = { navController.navigate("settings/platforms") },
                )
            }
            composable("settings") {
                SettingsScreen(
                    onOpenPlatforms = { navController.navigate("settings/platforms") },
                    onOpenRules = { navController.navigate("settings/rules") },
                    onOpenBackground = { navController.navigate("settings/background") },
                    onOpenStorage = { navController.navigate("settings/storage") },
                    onOpenPrivacy = { navController.navigate("settings/privacy") },
                )
            }
            composable("settings/platforms") {
                PlatformsScreen(onBack = { navController.popBackStack() })
            }
            composable("settings/rules") {
                SyncRulesScreen(
                    onBack = { navController.popBackStack() },
                    onOpenPlatforms = { navController.navigate("settings/platforms") },
                )
            }
            composable("settings/background") {
                BackgroundSettingsScreen(onBack = { navController.popBackStack() })
            }
            composable("settings/storage") {
                StorageSettingsScreen(onBack = { navController.popBackStack() })
            }
            composable("settings/privacy") {
                PrivacyAboutScreen(onBack = { navController.popBackStack() })
            }
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
