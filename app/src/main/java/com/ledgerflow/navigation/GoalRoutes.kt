package com.ledgerflow.navigation

import androidx.compose.runtime.getValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import com.ledgerflow.feature.tools.goals.GoalEditorScreen
import com.ledgerflow.feature.tools.goals.GoalEditorViewModel
import com.ledgerflow.feature.tools.goals.GoalScreen
import com.ledgerflow.feature.tools.goals.GoalViewModel
import com.ledgerflow.feature.tools.goals.GoalsScreen
import com.ledgerflow.feature.tools.goals.GoalsViewModel

/** Savings goals (V2-7, V2-PLAN §4.1): the list, one goal, and the form. */
internal fun NavGraphBuilder.goalDestinations(navController: NavHostController) {
    composable<Destination.Goals> {
        val viewModel: GoalsViewModel = hiltViewModel()
        val state by viewModel.state.collectAsStateWithLifecycle()
        GoalsScreen(
            state = state,
            onNewGoal = { navController.navigate(Destination.GoalEditor()) },
            onOpenGoal = { id -> navController.navigate(Destination.Goal(id)) },
            onBack = navController::popBackStack,
        )
    }
    composable<Destination.Goal> { entry ->
        val goalId = entry.toRoute<Destination.Goal>().goalId
        val viewModel: GoalViewModel = hiltViewModel()
        val state by viewModel.state.collectAsStateWithLifecycle()
        GoalScreen(
            state = state,
            onEvent = viewModel::onEvent,
            onEdit = { navController.navigate(Destination.GoalEditor(goalId)) },
            onBack = navController::popBackStack,
        )
    }
    composable<Destination.GoalEditor> {
        val viewModel: GoalEditorViewModel = hiltViewModel()
        val state by viewModel.state.collectAsStateWithLifecycle()
        GoalEditorScreen(state = state, onEvent = viewModel::onEvent, onBack = navController::popBackStack)
    }
}
