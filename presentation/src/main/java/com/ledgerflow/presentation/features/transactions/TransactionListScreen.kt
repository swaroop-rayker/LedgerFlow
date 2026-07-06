package com.ledgerflow.presentation.features.transactions

import androidx.compose.foundation.ExperimentalFoundationApi
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
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun TransactionListScreen(
    onNavigateToAddTransaction: () -> Unit,
    onNavigateToTransactionDetail: (Long) -> Unit,
    onNavigateToSearch: () -> Unit,
    viewModel: TransactionListViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Expenses", 
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                    )
                },
                actions = {
                    IconButton(onClick = onNavigateToSearch) {
                        Icon(
                            imageVector = Icons.Default.Search, 
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
                text = { Text("Add Expense") }
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
                        title = "No Expense History",
                        description = "Log your expenses to unlock premium financial audits.",
                        iconEmoji = "📁",
                        primaryActionText = "Log First Expense",
                        onPrimaryActionClick = onNavigateToAddTransaction
                    )
                } else {
                    val groupedTransactions = remember(uiState.transactions) {
                        uiState.transactions
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
                                stickyHeader(key = dateGroup) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .background(MaterialTheme.colorScheme.background)
                                    ) {
                                        GroupedSectionHeader(title = dateGroup)
                                    }
                                }

                                items(list, key = { it.id }) { item ->
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

                                    BaseCard(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .semantics(mergeDescendants = true) {},
                                        onClick = { onNavigateToTransactionDetail(item.id) },
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
                                                        text = catIcon,
                                                        style = MaterialTheme.typography.titleMedium
                                                    )
                                                }
                                                Spacer(modifier = Modifier.width(12.dp))
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
                                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                                        )
                                                        val notes = item.notes
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
                                                text = "-" + CurrencyUtils.formatCents(item.amount),
                                                style = FinancialAmount,
                                                color = MaterialTheme.colorScheme.onSurface
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
