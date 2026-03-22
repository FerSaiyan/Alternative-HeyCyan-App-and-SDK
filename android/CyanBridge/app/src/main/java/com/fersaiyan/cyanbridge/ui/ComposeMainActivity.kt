package com.fersaiyan.cyanbridge.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.fersaiyan.cyanbridge.ui.chat.ChatScreen
import com.fersaiyan.cyanbridge.ui.history.HistoryScreen
import com.fersaiyan.cyanbridge.ui.localmodels.LocalModelsScreen
import com.fersaiyan.cyanbridge.ui.navigation.Routes
import com.fersaiyan.cyanbridge.ui.navigation.bottomNavItems
import com.fersaiyan.cyanbridge.ui.notes.NotesScreen
import com.fersaiyan.cyanbridge.ui.onboarding.BatteryOptimizationScreen
import com.fersaiyan.cyanbridge.ui.onboarding.WelcomeScreen
import com.fersaiyan.cyanbridge.ui.plugins.PluginsScreen
import com.fersaiyan.cyanbridge.ui.pro.ProScreen
import com.fersaiyan.cyanbridge.ui.recordings.RecordingsScreen
import com.fersaiyan.cyanbridge.ui.settings.AboutScreen
import com.fersaiyan.cyanbridge.ui.settings.SettingsScreen
import com.fersaiyan.cyanbridge.ui.theme.CyanBridgeTheme

private const val PREFS = "cyanbridge_prefs"
private const val KEY_ONBOARDING_COMPLETED = "onboarding_completed"

class ComposeMainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val isOnboarded = getSharedPreferences(PREFS, MODE_PRIVATE)
            .getBoolean(KEY_ONBOARDING_COMPLETED, false)

        setContent {
            CyanBridgeTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    ComposeNavHost(
                        startDestination = if (isOnboarded) Routes.CHAT else Routes.WELCOME,
                        context = this,
                    )
                }
            }
        }
    }
}

@Composable
fun ComposeNavHost(
    startDestination: String,
    context: android.content.Context,
) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    val showBottomBar = currentDestination?.hierarchy?.any { dest ->
        bottomNavItems.any { it.route == dest.route }
    } == true

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    bottomNavItems.forEach { item ->
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
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    bottom = if (showBottomBar) innerPadding.calculateBottomPadding() else 0.dp
                ),
        ) {
            NavHost(
                navController = navController,
                startDestination = startDestination,
            ) {
                composable(Routes.CHAT) {
                    ChatScreen()
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
                    SettingsScreen(onNavigate = { route -> navController.navigate(route) })
                }
                composable(Routes.ABOUT) {
                    AboutScreen()
                }
                composable(Routes.PRO) {
                    ProScreen(
                        onNavigateToSettings = {
                            navController.navigate(Routes.SETTINGS) {
                                popUpTo(navController.graph.startDestinationId) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
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
                            context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
                                .edit()
                                .putBoolean(KEY_ONBOARDING_COMPLETED, true)
                                .apply()
                            navController.navigate(Routes.CHAT) {
                                popUpTo(Routes.WELCOME) { inclusive = true }
                            }
                        },
                    )
                }
                composable(Routes.PLUGINS) { PluginsScreen() }
                composable(Routes.RECORDINGS) { RecordingsScreen() }
                composable(Routes.NOTES) { NotesScreen() }
                composable(Routes.LOCAL_MODELS) { LocalModelsScreen() }
                composable(Routes.PRO_SETTINGS) {
                    LegacyScreenPlaceholder("Pro Settings")
                }
                composable(Routes.DAILY_FACTS) {
                    LegacyScreenPlaceholder("Daily Facts")
                }
                composable(Routes.DAILY_SUMMARY) {
                    LegacyScreenPlaceholder("Daily Summary")
                }
            }
        }
    }
}

@Composable
fun LegacyScreenPlaceholder(title: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Opening legacy screen...",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
