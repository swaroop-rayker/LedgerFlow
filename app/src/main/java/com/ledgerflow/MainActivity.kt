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
import android.content.Intent

import javax.inject.Inject
import androidx.compose.runtime.collectAsState
import com.ledgerflow.core.common.util.DatabaseRecoveryState
import com.ledgerflow.presentation.features.developer.DatabaseRecoveryScreen
import com.ledgerflow.domain.repository.DatabaseRecoveryRepository

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var databaseRecoveryRepository: DatabaseRecoveryRepository

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Prevent screenshots and screen recording for security/privacy
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        
        setContent {
            LedgerFlowTheme {
                val incompatibilityInfo by DatabaseRecoveryState.incompatibilityInfo.collectAsState()
                
                if (incompatibilityInfo != null) {
                    DatabaseRecoveryScreen(
                        info = incompatibilityInfo!!,
                        repository = databaseRecoveryRepository,
                        onResetSuccess = {
                            DatabaseRecoveryState.clear()
                            // Force app recreation to re-open the database fresh
                            recreate()
                        }
                    )
                } else {
                val permissionsToRequest = mutableListOf(Manifest.permission.RECEIVE_SMS)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
                }

                val requestPermissionsLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestMultiplePermissions(),
                    onResult = { permissions ->
                        permissions.entries.forEach {
                            timber.log.Timber.d("Permission: ${it.key} granted: ${it.value}")
                        }
                    }
                )

                LaunchedEffect(Unit) {
                    requestPermissionsLauncher.launch(permissionsToRequest.toTypedArray())
                }

                val navController = rememberNavController()
                
                // Read and route pending review notification clicks
                val activity = androidx.compose.ui.platform.LocalContext.current as? android.app.Activity
                val currentIntent = activity?.intent
                LaunchedEffect(currentIntent) {
                    if (currentIntent != null && currentIntent.hasExtra("pending_transaction_id")) {
                        val pendingId = currentIntent.getLongExtra("pending_transaction_id", 0L)
                        if (pendingId != 0L) {
                            currentIntent.removeExtra("pending_transaction_id")
                            navController.navigate(Screen.ReviewExpense(pendingId))
                        }
                    }
                }

                val navBackStackEntry by navController.currentBackStackEntryAsState()
                val currentRoute = navBackStackEntry?.destination?.route
                
                val currentScreenName = currentRoute?.substringAfterLast('.') ?: ""
                val showBottomBar = when (currentScreenName) {
                    "Dashboard", "TransactionList", "Reports", "BudgetSetup", "Settings" -> true
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
}
