package com.ledgerflow.presentation.features.transactions

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Search
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
import androidx.compose.ui.semantics.semantics
import androidx.hilt.navigation.compose.hiltViewModel
import com.ledgerflow.core.common.util.CurrencyUtils
import com.ledgerflow.core.ui.components.BaseCard
import com.ledgerflow.core.ui.components.EmptyStateView
import com.ledgerflow.core.ui.components.GroupedSectionHeader
import com.ledgerflow.core.ui.theme.FinancialAmount
import com.ledgerflow.core.ui.theme.SuccessColor
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransactionListScreen(
    onNavigateToAddTransaction: () -> Unit,
    onNavigateToTransactionDetail: (Long) -> Unit,
    viewModel: TransactionListViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    var searchQuery by remember { mutableStateOf("") }
    var isSearchActive by remember { mutableStateOf(false) }
    var selectedFilter by remember { mutableStateOf("ALL") }

    Scaffold(
        topBar = {
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
                            text = "Transactions", 
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { 
                        isSearchActive = !isSearchActive 
                        if (!isSearchActive) searchQuery = ""
                    }) {
                        Icon(
                            imageVector = if (isSearchActive) Icons.Default.Add else Icons.Default.Search, 
                            contentDescription = "Search",
                            modifier = Modifier.clip(CircleShape),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onNavigateToAddTransaction,
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                shape = RoundedCornerShape(16.dp),
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("Log Cash") }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
        ) {
            // Transaction Type filter chips row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf("ALL", "EXPENSE", "INCOME").forEach { filter ->
                    val isSelected = selectedFilter == filter
                    val label = when (filter) {
                        "ALL" -> "All Flows"
                        "EXPENSE" -> "Expenses"
                        "INCOME" -> "Income"
                        else -> filter
                    }
                    FilterChip(
                        selected = isSelected,
                        onClick = { selectedFilter = filter },
                        label = { Text(label, style = MaterialTheme.typography.labelMedium) }
                    )
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
            } else if (uiState.transactions.isEmpty()) {
                EmptyStateView(
                    title = "Zero Transaction History",
                    description = "Log your financial flows to unlock premium intelligence audits.",
                    iconEmoji = "📁",
                    primaryActionText = "Log First Entry",
                    onPrimaryActionClick = onNavigateToAddTransaction
                )
            } else {
                // Group transactions dynamically by formatted dates (Today, Yesterday, or Calendar date)
                val groupedTransactions = remember(uiState.transactions, searchQuery, selectedFilter) {
                    uiState.transactions
                        .filter { item ->
                            val query = searchQuery.trim()
                            val matchesQuery = query.isEmpty() ||
                                (item.merchant?.displayName?.contains(query, ignoreCase = true) == true) ||
                                (item.transaction.notes?.contains(query, ignoreCase = true) == true)
                            val matchesFilter = selectedFilter == "ALL" || item.transaction.type.name == selectedFilter
                            matchesQuery && matchesFilter
                        }
                        .groupBy { item ->
                            val date = Date(item.transaction.timestamp)
                            val todayStr = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date())
                            val itemStr = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(date)
                            
                            when (itemStr) {
                                todayStr -> "Today"
                                else -> {
                                    val calYesterday = Calendar.getInstance().apply { add(Calendar.DATE, -1) }
                                    val yesterdayStr = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(calYesterday.time)
                                    if (itemStr == yesterdayStr) {
                                        "Yesterday"
                                    } else {
                                        SimpleDateFormat("MMMM dd, yyyy", Locale.getDefault()).format(date)
                                    }
                                }
                            }
                        }
                }

                if (groupedTransactions.isEmpty()) {
                    EmptyStateView(
                        title = "No Matches Found",
                        description = "Verify spelling or refine search details.",
                        iconEmoji = "🔍"
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        item { Spacer(modifier = Modifier.height(2.dp)) }

                        groupedTransactions.forEach { (dateGroup, list) ->
                            item {
                                GroupedSectionHeader(title = dateGroup)
                            }

                            items(list) { item ->
                                val firstSplit = item.splits.firstOrNull()
                                val category = firstSplit?.let { split -> uiState.categories.find { it.id == split.categoryId } }
                                val paymentMethod = uiState.paymentMethods.find { it.id == item.transaction.paymentMethodId }
                                val isExpense = item.transaction.type.name == "EXPENSE"
                                val statusColor = if (isExpense) MaterialTheme.colorScheme.onSurface else SuccessColor

                                BaseCard(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .semantics(mergeDescendants = true) {},
                                    onClick = { onNavigateToTransactionDetail(item.transaction.id) },
                                    contentPadding = 12.dp
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
                                            Box(
                                                modifier = Modifier
                                                    .size(44.dp)
                                                    .clip(RoundedCornerShape(10.dp))
                                                    .background(MaterialTheme.colorScheme.surfaceVariant),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Text(
                                                    text = category?.icon ?: "📁",
                                                    style = MaterialTheme.typography.titleMedium
                                                )
                                            }
                                            Spacer(modifier = Modifier.width(12.dp))
                                            Column {
                                                Text(
                                                    text = item.merchant?.displayName ?: "General Ledger",
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
                                                        text = paymentMethod?.name ?: "Cash",
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                                    )
                                                    val notes = item.transaction.notes
                                                    if (!notes.isNullOrBlank()) {
                                                        Text("•", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                                                        Text(
                                                            text = notes,
                                                            style = MaterialTheme.typography.bodySmall,
                                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                            maxLines = 1,
                                                            overflow = TextOverflow.Ellipsis,
                                                            modifier = Modifier.weight(1f, fill = false)
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                        Text(
                                            text = (if (isExpense) "-" else "+") + CurrencyUtils.formatCents(item.transaction.totalAmount),
                                            style = FinancialAmount,
                                            color = statusColor
                                        )
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
}

