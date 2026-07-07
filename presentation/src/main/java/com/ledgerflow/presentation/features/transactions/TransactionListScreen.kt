package com.ledgerflow.presentation.features.transactions

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.ledgerflow.core.common.util.CurrencyUtils
import com.ledgerflow.core.ui.components.BaseCard
import com.ledgerflow.core.ui.components.EmptyStateView
import com.ledgerflow.core.ui.components.GroupedSectionHeader
import com.ledgerflow.core.ui.theme.FinancialAmount
import com.ledgerflow.core.ui.theme.Spacing
import com.ledgerflow.core.ui.theme.CornerRadius
import com.ledgerflow.domain.model.Transaction
import kotlinx.coroutines.launch
import java.util.Calendar

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun TransactionListScreen(
    onNavigateToAddTransaction: () -> Unit,
    onNavigateToTransactionDetail: (Long) -> Unit,
    onNavigateToSearch: () -> Unit,
    viewModel: TransactionListViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var searchQuery by remember { mutableStateOf("") }
    var isSearchActive by remember { mutableStateOf(false) }
    var selectedFilter by remember { mutableStateOf<String?>(null) } // UPI, Credit Card, Cash

    // Filter transactions locally
    val filteredTransactions = remember(uiState.transactions, searchQuery, selectedFilter) {
        uiState.transactions.filter { txn ->
            val matchesQuery = searchQuery.isBlank() || 
                txn.merchant.contains(searchQuery, ignoreCase = true) ||
                txn.category.contains(searchQuery, ignoreCase = true)
            val matchesFilter = selectedFilter == null || txn.paymentMethod.equals(selectedFilter, ignoreCase = true)
            matchesQuery && matchesFilter
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            AnimatedContent(
                targetState = uiState.isMultiSelectMode,
                label = "TopAppBarTransition"
            ) { isMultiSelect ->
                if (isMultiSelect) {
                    TopAppBar(
                        title = {
                            Text(
                                text = "${uiState.selectedIds.size} selected",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                            )
                        },
                        navigationIcon = {
                            IconButton(onClick = { viewModel.clearSelection() }) {
                                Icon(Icons.Default.Close, contentDescription = "Cancel Selection")
                            }
                        },
                        actions = {
                            IconButton(onClick = { viewModel.bulkDelete() }) {
                                Icon(Icons.Default.Delete, contentDescription = "Bulk Delete", tint = MaterialTheme.colorScheme.error)
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer
                        )
                    )
                } else {
                    TopAppBar(
                        title = {
                            if (isSearchActive) {
                                OutlinedTextField(
                                    value = searchQuery,
                                    onValueChange = { searchQuery = it },
                                    placeholder = { Text("Search transactions...", style = MaterialTheme.typography.bodyMedium) },
                                    singleLine = true,
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = Color.Transparent,
                                        unfocusedBorderColor = Color.Transparent
                                    ),
                                    textStyle = MaterialTheme.typography.bodyLarge,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            } else {
                                Text(
                                    text = "Expenses", 
                                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                                )
                            }
                        },
                        navigationIcon = {
                            if (isSearchActive) {
                                IconButton(onClick = {
                                    isSearchActive = false
                                    searchQuery = ""
                                }) {
                                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Close Search")
                                }
                            } else null
                        },
                        actions = {
                            IconButton(onClick = { isSearchActive = !isSearchActive }) {
                                Icon(
                                    imageVector = if (isSearchActive) Icons.Default.Close else Icons.Default.Search, 
                                    contentDescription = "Search",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.background
                        )
                    )
                }
            }
        },
        floatingActionButton = {
            if (!uiState.isMultiSelectMode) {
                ExtendedFloatingActionButton(
                    onClick = onNavigateToAddTransaction,
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    shape = RoundedCornerShape(CornerRadius.l),
                    icon = { Icon(Icons.Default.Add, contentDescription = null) },
                    text = { Text("Add Expense") }
                )
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(innerPadding)
                .padding(horizontal = Spacing.l)
        ) {
            // Filter chips panel
            if (!uiState.isMultiSelectMode) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(vertical = Spacing.s),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.s)
                ) {
                    listOf("Cash", "UPI", "Credit Card", "Debit Card", "Net Banking", "Wallet", "Bank Transfer", "Others").forEach { filter ->
                        val isSelected = selectedFilter == filter
                        FilterChip(
                            selected = isSelected,
                            onClick = { selectedFilter = if (isSelected) null else filter },
                            label = { Text(filter, maxLines = 1, style = MaterialTheme.typography.labelMedium) }
                        )
                    }
                }
            }

            Box(
                modifier = Modifier.weight(1f)
            ) {
                if (uiState.isLoading) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(strokeWidth = 3.dp, color = MaterialTheme.colorScheme.primary)
                    }
                } else if (uiState.errorMessage != null) {
                    EmptyStateView(
                        title = "Database Disrupted",
                        description = uiState.errorMessage ?: "Failed to read records.",
                        iconEmoji = "⚠️"
                    )
                } else if (filteredTransactions.isEmpty()) {
                    EmptyStateView(
                        title = if (searchQuery.isNotBlank() || selectedFilter != null) "No Matches Found" else "No Expense History",
                        description = if (searchQuery.isNotBlank() || selectedFilter != null) "Refine search or clear filters." else "Log your expenses to unlock premium financial audits.",
                        iconEmoji = "📁",
                        primaryActionText = "Log First Expense",
                        onPrimaryActionClick = onNavigateToAddTransaction
                    )
                } else {
                    val groupedTransactions = remember(filteredTransactions) {
                        filteredTransactions
                            .groupBy { item ->
                                val today = Calendar.getInstance().apply {
                                    set(Calendar.HOUR_OF_DAY, 0)
                                    set(Calendar.MINUTE, 0)
                                    set(Calendar.SECOND, 0)
                                    set(Calendar.MILLISECOND, 0)
                                }
                                val yesterday = Calendar.getInstance().apply {
                                    timeInMillis = today.timeInMillis
                                    add(Calendar.DATE, -1)
                                }
                                val startOfWeek = Calendar.getInstance().apply {
                                    timeInMillis = today.timeInMillis
                                    set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
                                }
                                
                                when {
                                    item.timestamp >= today.timeInMillis -> "Today"
                                    item.timestamp >= yesterday.timeInMillis -> "Yesterday"
                                    item.timestamp >= startOfWeek.timeInMillis -> "This Week"
                                    else -> "Earlier"
                                }
                            }
                            .entries
                            .sortedBy { entry ->
                                val order = listOf("Today", "Yesterday", "This Week", "Earlier")
                                order.indexOf(entry.key)
                            }
                    }

                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(Spacing.s)
                    ) {
                        item { Spacer(modifier = Modifier.height(Spacing.xxs)) }

                        groupedTransactions.forEach { (dateGroup, list) ->
                            val groupTotal = list.sumOf { it.amount }
                            stickyHeader(key = dateGroup) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(MaterialTheme.colorScheme.background)
                                        .padding(vertical = Spacing.xs),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    GroupedSectionHeader(title = dateGroup, modifier = Modifier.weight(1f))
                                    Text(
                                        text = CurrencyUtils.formatCents(groupTotal),
                                        style = MaterialTheme.typography.labelLarge.copy(
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    )
                                }
                            }

                            items(list, key = { it.id }) { item ->
                                val isSelected = uiState.selectedIds.contains(item.id)
                                val swipeDismissState = rememberSwipeToDismissBoxState(
                                    confirmValueChange = { value ->
                                        if (value == SwipeToDismissBoxValue.EndToStart) {
                                            viewModel.deleteTransaction(item.id)
                                            // Trigger Snackbar
                                            scope.launch {
                                                val result = snackbarHostState.showSnackbar(
                                                    message = "Expense deleted",
                                                    actionLabel = "Undo",
                                                    duration = SnackbarDuration.Short
                                                )
                                                if (result == SnackbarResult.ActionPerformed) {
                                                    viewModel.undoDelete()
                                                }
                                            }
                                            true
                                        } else if (value == SwipeToDismissBoxValue.StartToEnd) {
                                            viewModel.duplicateTransaction(item)
                                            scope.launch {
                                                snackbarHostState.showSnackbar("Transaction duplicated")
                                            }
                                            false // Don't dismiss fully
                                        } else false
                                    }
                                )

                                SwipeToDismissBox(
                                    state = swipeDismissState,
                                    backgroundContent = {
                                        val color = when (swipeDismissState.dismissDirection) {
                                            SwipeToDismissBoxValue.StartToEnd -> Color(0xFF10B981) // Emerald Green for Duplicate
                                            SwipeToDismissBoxValue.EndToStart -> MaterialTheme.colorScheme.error // Red for Delete
                                            else -> Color.Transparent
                                        }
                                        Box(
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .clip(RoundedCornerShape(CornerRadius.m))
                                                .background(color)
                                                .padding(horizontal = Spacing.l),
                                            contentAlignment = if (swipeDismissState.dismissDirection == SwipeToDismissBoxValue.StartToEnd) {
                                                Alignment.CenterStart
                                            } else Alignment.CenterEnd
                                        ) {
                                            Icon(
                                                imageVector = if (swipeDismissState.dismissDirection == SwipeToDismissBoxValue.StartToEnd) {
                                                    Icons.Default.Add
                                                } else Icons.Default.Delete,
                                                contentDescription = null,
                                                tint = Color.White
                                            )
                                        }
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .animateItemPlacement()
                                ) {
                                    val catIcon = when (item.category.lowercase()) {
                                        "food" -> "🍔"
                                        "transport" -> "🚗"
                                        "shopping" -> "🛍️"
                                        "bills" -> "💡"
                                        "entertainment" -> "🎬"
                                        "healthcare" -> "🏥"
                                        "education" -> "🎓"
                                        "travel" -> "✈️"
                                        "groceries" -> "🛒"
                                        "subscriptions" -> "💳"
                                        "investment" -> "📈"
                                        else -> "📁"
                                    }

                                    // Timeline Row structure
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        // Visual Timeline thread indicator
                                        Column(
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            modifier = Modifier.width(28.dp)
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .size(10.dp)
                                                    .clip(CircleShape)
                                                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.5f))
                                            )
                                            Box(
                                                modifier = Modifier
                                                    .width(2.dp)
                                                    .height(60.dp)
                                                    .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))
                                            )
                                        }
                                        Spacer(modifier = Modifier.width(Spacing.s))
                                        
                                        BaseCard(
                                            modifier = Modifier
                                                .weight(1f)
                                                .combinedClickable(
                                                    onClick = {
                                                        if (uiState.isMultiSelectMode) {
                                                            viewModel.toggleSelection(item.id)
                                                        } else {
                                                            onNavigateToTransactionDetail(item.id)
                                                        }
                                                    },
                                                    onLongClick = {
                                                        viewModel.toggleSelection(item.id)
                                                    }
                                                ),
                                            containerColor = if (isSelected) {
                                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                                            } else MaterialTheme.colorScheme.surface,
                                            borderColor = if (isSelected) {
                                                MaterialTheme.colorScheme.primary
                                            } else MaterialTheme.colorScheme.outline.copy(alpha = 0.12f),
                                            contentPadding = Spacing.m
                                        ) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    modifier = Modifier.weight(1f)
                                                ) {
                                                    // Category avatar
                                                    Box(
                                                        modifier = Modifier
                                                            .size(40.dp)
                                                            .clip(RoundedCornerShape(CornerRadius.s))
                                                            .background(MaterialTheme.colorScheme.surfaceVariant),
                                                        contentAlignment = Alignment.Center
                                                    ) {
                                                        Text(
                                                            text = catIcon,
                                                            style = MaterialTheme.typography.titleMedium
                                                        )
                                                    }
                                                    Spacer(modifier = Modifier.width(Spacing.s))
                                                    Column {
                                                        Text(
                                                            text = item.merchant,
                                                            fontWeight = FontWeight.SemiBold,
                                                            style = MaterialTheme.typography.bodyLarge,
                                                            maxLines = 1,
                                                            overflow = TextOverflow.Ellipsis
                                                        )
                                                        Spacer(modifier = Modifier.height(2.dp))
                                                        Row(
                                                            verticalAlignment = Alignment.CenterVertically,
                                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                                        ) {
                                                            Text(
                                                                text = "${item.category}${if (!item.subcategory.isNullOrBlank()) " • ${item.subcategory}" else ""}",
                                                                style = MaterialTheme.typography.bodySmall,
                                                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                                                            )
                                                            val notesText = item.notes
                                                            if (!notesText.isNullOrBlank()) {
                                                                Text("•", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                                                                Text(
                                                                    text = notesText,
                                                                    style = MaterialTheme.typography.bodySmall,
                                                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                                                    maxLines = 1,
                                                                    overflow = TextOverflow.Ellipsis,
                                                                    modifier = Modifier.weight(1f, fill = false)
                                                                )
                                                            }
                                                        }
                                                    }
                                                }
                                                
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(Spacing.s)
                                                ) {
                                                    val isCredit = item.amount < 0
                                                    val amountText = if (isCredit) {
                                                        "+" + CurrencyUtils.formatCents(-item.amount)
                                                    } else {
                                                        "-" + CurrencyUtils.formatCents(item.amount)
                                                    }
                                                    val amountColor = if (isCredit) {
                                                        Color(0xFF2E7D32) // Elegant Green for Inflow
                                                    } else {
                                                        MaterialTheme.colorScheme.onSurface
                                                    }
                                                    Text(
                                                        text = amountText,
                                                        style = FinancialAmount,
                                                        color = amountColor
                                                    )
                                                    if (uiState.isMultiSelectMode) {
                                                        Checkbox(
                                                            checked = isSelected,
                                                            onCheckedChange = { viewModel.toggleSelection(item.id) }
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        item { Spacer(modifier = Modifier.height(80.dp)) }
                    }
                }
            }
        }
    }
}
