package com.fersaiyan.cyanbridge.ui.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.ui.res.vectorResource
import com.fersaiyan.cyanbridge.R
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavHostController
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.fersaiyan.cyanbridge.ui.chat.ChatScreen
import com.fersaiyan.cyanbridge.ui.glasses.GlassesScreen
import com.fersaiyan.cyanbridge.ui.history.HistoryScreen
import com.fersaiyan.cyanbridge.ui.onboarding.BatteryOptimizationScreen
import com.fersaiyan.cyanbridge.ui.onboarding.PermissionRequestScreen
import com.fersaiyan.cyanbridge.ui.onboarding.WelcomeScreen
import com.fersaiyan.cyanbridge.ui.plugins.PluginsScreen
import com.fersaiyan.cyanbridge.ui.pro.ProScreen
import com.fersaiyan.cyanbridge.ui.pro.ProSubscriptionSettingsScreen
import com.fersaiyan.cyanbridge.ui.localmodels.LocalModelsScreen
import com.fersaiyan.cyanbridge.ui.localagent.DailyFactsScreen
import com.fersaiyan.cyanbridge.ui.localagent.DailySummaryScreen
import com.fersaiyan.cyanbridge.ui.localagent.AppBlacklistScreen
import com.fersaiyan.cyanbridge.ui.localagent.ScreenCapturesScreen
import com.fersaiyan.cyanbridge.ui.localagent.PendingActionsScreen
import com.fersaiyan.cyanbridge.ui.localagent.SyncedMediaGalleryScreen
import com.fersaiyan.cyanbridge.ui.notes.NoteDetailScreen
import com.fersaiyan.cyanbridge.ui.notes.NotesListScreen
import com.fersaiyan.cyanbridge.ui.recordings.RecordingsScreen

data class BottomNavItem(
    val route: String,
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
)

@Composable
fun PlaceholderScreen(title: String, migrationPhase: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = migrationPhase,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
fun bottomNavItems(): List<BottomNavItem> {
    val glassesIcon = ImageVector.vectorResource(R.drawable.ic_nav_glasses)
    val pluginsIcon = ImageVector.vectorResource(R.drawable.ic_nav_plugins)
    return listOf(
        BottomNavItem(
            route = Routes.GLASSES,
            label = "Glasses",
            selectedIcon = glassesIcon,
            unselectedIcon = glassesIcon,
        ),
        BottomNavItem(
            route = Routes.CHAT,
            label = "Chats",
            selectedIcon = Icons.Filled.Home,
            unselectedIcon = Icons.Outlined.Home,
        ),
        BottomNavItem(
            route = Routes.RECORDINGS,
            label = "Recordings",
            selectedIcon = Icons.AutoMirrored.Filled.List,
            unselectedIcon = Icons.AutoMirrored.Outlined.List,
        ),
        BottomNavItem(
            route = Routes.SETTINGS,
            label = "Settings",
            selectedIcon = Icons.Filled.Settings,
            unselectedIcon = Icons.Outlined.Settings,
        ),
        BottomNavItem(
            route = Routes.PLUGINS,
            label = "Plugins",
            selectedIcon = pluginsIcon,
            unselectedIcon = pluginsIcon,
        ),
    )
}

@Composable
fun MainNavScreen(
    navController: NavHostController = rememberNavController(),
    startDestination: String = Routes.CHAT,
) {
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    val items = bottomNavItems()
    val isOnboarding = currentDestination?.route in listOf(Routes.WELCOME, Routes.BATTERY_OPT, Routes.PERMISSIONS)
    val showBottomBar = !isOnboarding

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    items.forEach { item ->
                        val selected = currentDestination?.hierarchy?.any { it.route == item.route } == true
                        NavigationBarItem(
                            icon = {
                                Icon(
                                    imageVector = if (selected) item.selectedIcon else item.unselectedIcon,
                                    contentDescription = item.label,
                                )
                            },
                            label = { Text(item.label) },
                            selected = selected,
                            onClick = {
                                navController.navigate(item.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = startDestination,
            modifier = Modifier.padding(innerPadding),
        ) {
            composable(Routes.GLASSES) {
                GlassesScreen()
            }
            composable(Routes.CHAT) {
                ChatScreen(
                    onNavigateToHistory = {
                        navController.navigate(Routes.HISTORY)
                    },
                )
            }
            composable(Routes.CHAT_THREAD) { backStackEntry ->
                val chatId = backStackEntry.arguments?.getString("chatId")
                ChatScreen(threadId = chatId)
            }
            composable(Routes.HISTORY) {
                HistoryScreen(
                    onNavigateToChat = { chatId ->
                        navController.navigate(Routes.chatThread(chatId)) {
                            popUpTo(navController.graph.startDestinationId) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                )
            }
            composable(Routes.SETTINGS) {
                com.fersaiyan.cyanbridge.ui.settings.SettingsScreen(
                    onNavigate = { route -> navController.navigate(route) },
                )
            }
            composable(Routes.ABOUT) {
                com.fersaiyan.cyanbridge.ui.settings.AboutScreen()
            }
            composable(Routes.PRO) {
                ProScreen(
                    onNavigateToSettings = {
                        navController.navigate(Routes.PRO_SETTINGS)
                    },
                )
            }
            composable(Routes.PRO_SETTINGS) {
                ProSubscriptionSettingsScreen(
                    onNavigateBack = { navController.popBackStack() },
                )
            }
            composable(Routes.WELCOME) {
                WelcomeScreen(
                    onStartSetup = { navController.navigate(Routes.BATTERY_OPT) },
                )
            }
            composable(Routes.BATTERY_OPT) {
                BatteryOptimizationScreen(
                    onComplete = {
                        navController.navigate(Routes.PERMISSIONS) {
                            popUpTo(Routes.WELCOME) { inclusive = true }
                        }
                    },
                )
            }
            composable(Routes.PERMISSIONS) {
                PermissionRequestScreen(
                    onComplete = {
                        navController.navigate(Routes.GLASSES) {
                            popUpTo(Routes.WELCOME) { inclusive = true }
                        }
                    },
                )
            }
            composable(Routes.RECORDINGS) {
                RecordingsScreen()
            }
            composable(Routes.PLUGINS) {
                PluginsScreen()
            }
            composable(Routes.NOTES_LIST) {
                NotesListScreen(
                    onNavigateToNoteDetail = { noteId ->
                        navController.navigate(Routes.noteDetail(noteId?.toString() ?: "new"))
                    },
                )
            }
            composable(Routes.LOCAL_MODELS) {
                LocalModelsScreen()
            }
            composable(
                route = Routes.DAILY_FACTS,
                arguments = listOf(
                    navArgument("mode") {
                        type = NavType.StringType
                        defaultValue = "draft"
                    },
                    navArgument("date") {
                        type = NavType.StringType
                        defaultValue = ""
                    },
                ),
            ) { backStackEntry ->
                DailyFactsScreen(
                    mode = backStackEntry.arguments?.getString("mode") ?: "draft",
                    date = backStackEntry.arguments?.getString("date") ?: "",
                    onNavigateBack = { navController.popBackStack() },
                )
            }
            composable(
                route = Routes.DAILY_SUMMARY,
                arguments = listOf(
                    navArgument("date") {
                        type = NavType.StringType
                        defaultValue = ""
                    },
                ),
            ) { backStackEntry ->
                DailySummaryScreen(
                    date = backStackEntry.arguments?.getString("date") ?: "",
                    onNavigateBack = { navController.popBackStack() },
                )
            }
            composable(Routes.APP_BLACKLIST) {
                AppBlacklistScreen(
                    onNavigateBack = { navController.popBackStack() },
                )
            }
            composable(Routes.SCREEN_CAPTURES) {
                ScreenCapturesScreen(
                    onNavigateBack = { navController.popBackStack() },
                )
            }
            composable(Routes.PENDING_ACTIONS) {
                PendingActionsScreen(
                    onNavigateBack = { navController.popBackStack() },
                )
            }
            composable(Routes.SYNCED_MEDIA_GALLERY) {
                SyncedMediaGalleryScreen(
                    onNavigateBack = { navController.popBackStack() },
                )
            }
            composable("note_detail/new") {
                NoteDetailScreen(
                    noteId = null,
                    onNavigateBack = { navController.popBackStack() },
                )
            }
            composable(Routes.NOTE_DETAIL) { backStackEntry ->
                val noteId = backStackEntry.arguments?.getString("noteId")?.toLongOrNull()
                NoteDetailScreen(
                    noteId = noteId,
                    onNavigateBack = { navController.popBackStack() },
                )
            }
        }
    }
}