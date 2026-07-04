package com.ledgerflow.presentation.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import com.ledgerflow.presentation.features.budgets.BudgetSetupScreen
import com.ledgerflow.presentation.features.categories.CategoryManagerScreen
import com.ledgerflow.presentation.features.dashboard.DashboardScreen
import com.ledgerflow.presentation.features.reports.ReportsScreen
import com.ledgerflow.presentation.features.settings.SettingsScreen
import com.ledgerflow.presentation.features.transactions.TransactionDetailScreen
import com.ledgerflow.presentation.features.transactions.TransactionListScreen

@Composable
fun LedgerFlowNavHost(
    navController: NavHostController,
    modifier: Modifier = Modifier
) {
    NavHost(
        navController = navController,
        startDestination = Screen.Dashboard,
        modifier = modifier
    ) {
        composable<Screen.Dashboard> {
            DashboardScreen(
                onNavigateToAddTransaction = {
                    navController.navigate(Screen.TransactionDetail(id = 0))
                },
                onNavigateToSettings = {
                    navController.navigate(Screen.Settings)
                },
                onNavigateToCategories = {
                    navController.navigate(Screen.CategoryManager)
                },
                onNavigateToTransactionList = {
                    navController.navigate(Screen.TransactionList)
                },
                onNavigateToBudgetSetup = {
                    navController.navigate(Screen.BudgetSetup)
                },
                onNavigateToReports = {
                    navController.navigate(Screen.Reports)
                }
            )
        }
        composable<Screen.TransactionList> {
            TransactionListScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }
        composable<Screen.TransactionDetail> { backStackEntry ->
            val route = backStackEntry.toRoute<Screen.TransactionDetail>()
            TransactionDetailScreen(
                transactionId = route.id,
                onNavigateBack = { navController.popBackStack() }
            )
        }
        composable<Screen.CategoryManager> {
            CategoryManagerScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }
        composable<Screen.BudgetSetup> {
            BudgetSetupScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }
        composable<Screen.Reports> {
            ReportsScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }
        composable<Screen.Settings> {
            SettingsScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }
    }
}
