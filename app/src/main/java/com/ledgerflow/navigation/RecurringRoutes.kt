package com.ledgerflow.navigation

import androidx.compose.runtime.getValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import com.ledgerflow.feature.tools.recurring.RecurringEditorScreen
import com.ledgerflow.feature.tools.recurring.RecurringEditorViewModel
import com.ledgerflow.feature.tools.recurring.RecurringItemScreen
import com.ledgerflow.feature.tools.recurring.RecurringItemViewModel
import com.ledgerflow.feature.tools.recurring.RecurringListViewModel
import com.ledgerflow.feature.tools.recurring.RecurringScreen

/** Recurring items (V2-7, V2-PLAN §4.2): the list, one item, and the form. */
internal fun NavGraphBuilder.recurringDestinations(navController: NavHostController) {
    composable<Destination.Recurring> {
        val viewModel: RecurringListViewModel = hiltViewModel()
        val state by viewModel.state.collectAsStateWithLifecycle()
        RecurringScreen(
            state = state,
            onEvent = viewModel::onEvent,
            onNew = { navController.navigate(Destination.RecurringEditor()) },
            onAddSuggestion = { merchantId, ledger ->
                navController.navigate(Destination.RecurringEditor(fromMerchant = merchantId, fromLedger = ledger.name))
            },
            onOpen = { id -> navController.navigate(Destination.RecurringItem(id)) },
            onBack = navController::popBackStack,
        )
    }
    composable<Destination.RecurringItem> { entry ->
        val itemId = entry.toRoute<Destination.RecurringItem>().itemId
        val viewModel: RecurringItemViewModel = hiltViewModel()
        val state by viewModel.state.collectAsStateWithLifecycle()
        RecurringItemScreen(
            state = state,
            onEvent = viewModel::onEvent,
            onEdit = { navController.navigate(Destination.RecurringEditor(itemId = itemId)) },
            onBack = navController::popBackStack,
        )
    }
    composable<Destination.RecurringEditor> {
        val viewModel: RecurringEditorViewModel = hiltViewModel()
        val state by viewModel.state.collectAsStateWithLifecycle()
        RecurringEditorScreen(state = state, onEvent = viewModel::onEvent, onBack = navController::popBackStack)
    }
}
