package com.ledgerflow.navigation

import androidx.compose.runtime.getValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import com.ledgerflow.feature.tools.calculator.CalculatorScreen
import com.ledgerflow.feature.tools.calculator.CalculatorViewModel
import com.ledgerflow.feature.tools.converter.ConverterScreen
import com.ledgerflow.feature.tools.converter.ConverterViewModel

/**
 * Utilities (V2-7, V2-PLAN §4.4): the calculator and the converter. Split
 * expense joins with debts, which it builds on (owner, 2026-11-10).
 */
internal fun NavGraphBuilder.utilityDestinations(navController: NavHostController) {
    composable<Destination.Calculator> {
        val viewModel: CalculatorViewModel = hiltViewModel()
        val state by viewModel.state.collectAsStateWithLifecycle()
        CalculatorScreen(
            state = state,
            onEvent = viewModel::onEvent,
            // A new manual entry with the answer typed in; Back from it returns here.
            onUseAmount = { minor -> navController.navigate(Destination.Entry(amount = minor.toString())) },
            onBack = navController::popBackStack,
        )
    }
    composable<Destination.Converter> {
        val viewModel: ConverterViewModel = hiltViewModel()
        val state by viewModel.state.collectAsStateWithLifecycle()
        ConverterScreen(state = state, onEvent = viewModel::onEvent, onBack = navController::popBackStack)
    }
}
