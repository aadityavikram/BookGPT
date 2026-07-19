package com.bookgpt.android.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.bookgpt.android.ui.chat.ChatScreen
import com.bookgpt.android.ui.library.LibraryScreen
import com.bookgpt.android.ui.navigation.Routes
import com.bookgpt.android.ui.settings.SettingsScreen

@Composable
fun BookGptNavHost() {
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val current = backStack?.destination?.route ?: Routes.LIBRARY
    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val hideBottomBar = current == Routes.CHAT && imeVisible

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            if (!hideBottomBar) {
                NavigationBar {
                    NavigationBarItem(
                        selected = current == Routes.LIBRARY,
                        onClick = {
                            navController.navigate(Routes.LIBRARY) {
                                popUpTo(Routes.LIBRARY) { inclusive = false }
                                launchSingleTop = true
                            }
                        },
                        icon = { Icon(Icons.AutoMirrored.Filled.LibraryBooks, contentDescription = null) },
                        label = { Text("Library") },
                    )
                    NavigationBarItem(
                        selected = current == Routes.CHAT,
                        onClick = {
                            navController.navigate(Routes.CHAT) {
                                launchSingleTop = true
                            }
                        },
                        icon = { Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = null) },
                        label = { Text("Chat") },
                    )
                    NavigationBarItem(
                        selected = current == Routes.SETTINGS,
                        onClick = {
                            navController.navigate(Routes.SETTINGS) {
                                launchSingleTop = true
                            }
                        },
                        icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                        label = { Text("Settings") },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Routes.LIBRARY,
            modifier = Modifier.padding(padding),
        ) {
            composable(Routes.LIBRARY) { LibraryScreen() }
            composable(Routes.CHAT) {
                ChatScreen(
                    onOpenLibrary = {
                        navController.navigate(Routes.LIBRARY) {
                            popUpTo(Routes.LIBRARY) { inclusive = false }
                            launchSingleTop = true
                        }
                    },
                )
            }
            composable(Routes.SETTINGS) { SettingsScreen() }
        }
    }
}
