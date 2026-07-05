package com.ledgerflow

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.ledgerflow.core.ui.theme.LedgerFlowTheme
import com.ledgerflow.presentation.navigation.LedgerFlowNavHost
import com.ledgerflow.presentation.navigation.Screen
import dagger.hilt.android.AndroidEntryPoint

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Prevent screenshots and screen recording for security/privacy
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        
        setContent {
            LedgerFlowTheme {
                // Request Notification permission for Android 13+ (SDK 33+)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    val requestPermissionLauncher = rememberLauncherForActivityResult(
                        contract = ActivityResultContracts.RequestPermission(),
                        onResult = {}
                    )
                    LaunchedEffect(Unit) {
                        requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }

                val navController = rememberNavController()
                val navBackStackEntry by navController.currentBackStackEntryAsState()
                val currentRoute = navBackStackEntry?.destination?.route
                
                val currentScreenName = currentRoute?.substringAfterLast('.') ?: ""
                val showBottomBar = when (currentScreenName) {
                    "Dashboard", "TransactionList", "Reports", "BudgetSetup", "Settings" -> true
                    // Matches fully qualified names containing the simple names
                    else -> currentRoute?.let { route ->
                        route.contains("Dashboard") || 
                        route.contains("TransactionList") || 
                        route.contains("Reports") || 
                        route.contains("BudgetSetup") || 
                        route.contains("Settings")
                    } ?: false
                }
                
                Scaffold(
                    bottomBar = {
                        if (showBottomBar) {
                            NavigationBar {
                                val items = listOf(
                                    Triple("Home", Screen.Dashboard, Icons.Default.Home),
                                    Triple("Transactions", Screen.TransactionList, Icons.Default.List),
                                    Triple("Analytics", Screen.Reports, Icons.Default.Info),
                                    Triple("Budgets", Screen.BudgetSetup, Icons.Default.Star),
                                    Triple("More", Screen.Settings, Icons.Default.Settings)
                                )
                                
                                items.forEach { (label, screen, icon) ->
                                    val isSelected = currentRoute?.contains(screen::class.simpleName ?: "") == true
                                    
                                    NavigationBarItem(
                                        selected = isSelected,
                                        onClick = {
                                            if (!isSelected) {
                                                navController.navigate(screen) {
                                                    popUpTo(navController.graph.findStartDestination().id) {
                                                        saveState = true
                                                    }
                                                    launchSingleTop = true
                                                    restoreState = true
                                                }
                                            }
                                        },
                                        icon = { Icon(icon, contentDescription = label) },
                                        label = { Text(label) }
                                    )
                                }
                            }
                        }
                    }
                ) { innerPadding ->
                    Surface(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding),
                        color = MaterialTheme.colorScheme.background
                    ) {
                        LedgerFlowNavHost(navController = navController)
                    }
                }
            }
        }
    }
}
