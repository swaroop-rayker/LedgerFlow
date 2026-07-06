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
import com.ledgerflow.presentation.features.transactions.ExpenseDetailsScreen
import com.ledgerflow.presentation.features.review.ReviewExpenseScreen
import com.ledgerflow.presentation.features.review.PendingListScreen
import com.ledgerflow.presentation.features.developer.DeveloperSettingsScreen
import com.ledgerflow.presentation.features.developer.DatabaseToolsScreen
import com.ledgerflow.presentation.features.search.GlobalSearchScreen

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
                },
                onNavigateToPendingList = {
                    navController.navigate(Screen.PendingList)
                },
                onNavigateToTransactionDetail = { id ->
                    navController.navigate(Screen.ExpenseDetails(id))
                }
            )
        }
        composable<Screen.TransactionList> {
            TransactionListScreen(
                onNavigateToAddTransaction = { navController.navigate(Screen.TransactionDetail(0L)) },
                onNavigateToTransactionDetail = { id -> navController.navigate(Screen.ExpenseDetails(id)) },
                onNavigateToSearch = { navController.navigate(Screen.GlobalSearch) }
            )
        }
        composable<Screen.TransactionDetail> { backStackEntry ->
            val route = backStackEntry.toRoute<Screen.TransactionDetail>()
            TransactionDetailScreen(
                transactionId = route.id,
                onNavigateBack = { navController.popBackStack() }
            )
        }
        composable<Screen.ExpenseDetails> { backStackEntry ->
            val route = backStackEntry.toRoute<Screen.ExpenseDetails>()
            ExpenseDetailsScreen(
                transactionId = route.transactionId,
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
                onNavigateBack = { navController.popBackStack() },
                onNavigateToTransactionDetail = { id -> navController.navigate(Screen.ExpenseDetails(id)) }
            )
        }
        composable<Screen.Settings> {
            SettingsScreen(
                onNavigateBack = { navController.popBackStack() },
                onNavigateToDeveloperSettings = { navController.navigate(Screen.DeveloperSettings) }
            )
        }
        composable<Screen.DeveloperSettings> {
            DeveloperSettingsScreen(
                onNavigateBack = { navController.popBackStack() },
                onNavigateToDatabaseTools = { navController.navigate(Screen.DatabaseTools) }
            )
        }
        composable<Screen.DatabaseTools> {
            DatabaseToolsScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }
        composable<Screen.ReviewExpense> { backStackEntry ->
            val route = backStackEntry.toRoute<Screen.ReviewExpense>()
            ReviewExpenseScreen(
                pendingId = route.pendingId,
                onNavigateBack = { navController.popBackStack() }
            )
        }
        composable<Screen.PendingList> {
            PendingListScreen(
                onNavigateToReview = { id -> navController.navigate(Screen.ReviewExpense(id)) },
                onNavigateBack = { navController.popBackStack() }
            )
        }
        composable<Screen.GlobalSearch> {
            GlobalSearchScreen(
                onNavigateBack = { navController.popBackStack() },
                onNavigateToTransactionDetail = { id -> navController.navigate(Screen.ExpenseDetails(id)) }
            )
        }
    }
}
