package com.ledgerflow.presentation.navigation

import kotlinx.serialization.Serializable

sealed interface Screen {
    @Serializable
    data object Dashboard : Screen

    @Serializable
    data object TransactionList : Screen

    @Serializable
    data class TransactionDetail(val id: Long = 0) : Screen

    @Serializable
    data object CategoryManager : Screen

    @Serializable
    data object BudgetSetup : Screen

    @Serializable
    data object Reports : Screen

    @Serializable
    data object Settings : Screen

    @Serializable
    data class ReviewExpense(val pendingId: Long) : Screen

    @Serializable
    data object PendingList : Screen
}
