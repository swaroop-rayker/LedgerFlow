package com.ledgerflow.presentation.features.dashboard

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
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
import com.ledgerflow.core.ui.theme.*
import java.util.Calendar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    onNavigateToAddTransaction: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToCategories: () -> Unit,
    onNavigateToTransactionList: () -> Unit,
    onNavigateToBudgetSetup: () -> Unit,
    onNavigateToReports: () -> Unit,
    viewModel: DashboardViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    // Determine premium greeting based on time of day
    val greeting = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
        in 0..11 -> "Good morning"
        in 12..16 -> "Good afternoon"
        else -> "Good evening"
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    Column {
                        Text(
                            text = greeting,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "LedgerFlow", 
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.ExtraBold)
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onNavigateToCategories) {
                        Icon(
                            imageVector = Icons.Default.List, 
                            contentDescription = "Categories",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(
                            imageVector = Icons.Default.Settings, 
                            contentDescription = "Settings",
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
                text = { Text("Transaction") }
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(MaterialTheme.colorScheme.background)
        ) {
            when (val state = uiState) {
                is DashboardUiState.Loading -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(
                            strokeWidth = 3.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                is DashboardUiState.Error -> {
                    EmptyStateView(
                        title = "System Disrupted",
                        description = state.message,
                        iconEmoji = "⚠️",
                        primaryActionText = "Retry Load",
                        onPrimaryActionClick = { viewModel.loadDashboardSummary() }
                    )
                }
                is DashboardUiState.Success -> {
                    val summary = state.summary
                    val netBalance = summary.monthlyIncome - summary.monthlyExpense

                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        item { Spacer(modifier = Modifier.height(2.dp)) }

                        // 1. Premium Net Flow Cockpit Card
                        item {
                            val cardBgGradient = Brush.verticalGradient(
                                colors = if (netBalance >= 0) {
                                    listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.primary.copy(alpha = 0.85f))
                                } else {
                                    listOf(MaterialTheme.colorScheme.error, MaterialTheme.colorScheme.error.copy(alpha = 0.85f))
                                }
                            )
                            
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(180.dp),
                                shape = RoundedCornerShape(24.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary),
                                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(cardBgGradient)
                                        .padding(20.dp)
                                ) {
                                    Column(modifier = Modifier.fillMaxSize()) {
                                        Text(
                                            text = "MONTHLY NET BALANCE",
                                            style = MaterialTheme.typography.labelSmall.copy(
                                                color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f),
                                                fontWeight = FontWeight.Bold,
                                                letterSpacing = 1.sp
                                            )
                                        )
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Text(
                                            text = CurrencyUtils.formatCents(netBalance),
                                            style = FinancialTotal,
                                            color = MaterialTheme.colorScheme.onPrimary
                                        )
                                        Spacer(modifier = Modifier.weight(1f))
                                        
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Column {
                                                Text(
                                                    text = "INCOME",
                                                    style = MaterialTheme.typography.labelSmall.copy(
                                                        color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.6f),
                                                        fontWeight = FontWeight.Bold
                                                    )
                                                )
                                                Text(
                                                    text = CurrencyUtils.formatCents(summary.monthlyIncome),
                                                    style = FinancialAmount,
                                                    color = MaterialTheme.colorScheme.onPrimary
                                                )
                                            }
                                            Column(horizontalAlignment = Alignment.End) {
                                                Text(
                                                    text = "EXPENSE",
                                                    style = MaterialTheme.typography.labelSmall.copy(
                                                        color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.6f),
                                                        fontWeight = FontWeight.Bold
                                                    )
                                                )
                                                Text(
                                                    text = CurrencyUtils.formatCents(summary.monthlyExpense),
                                                    style = FinancialAmount,
                                                    color = MaterialTheme.colorScheme.onPrimary
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // 1.5. Premium Quick Actions Pill Row
                        item {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                ActionChip(
                                    label = "Add Entry",
                                    icon = Icons.Default.Add,
                                    onClick = onNavigateToAddTransaction
                                )
                                ActionChip(
                                    label = "Setup Budget",
                                    icon = Icons.Default.Info,
                                    onClick = onNavigateToBudgetSetup
                                )
                                ActionChip(
                                    label = "Categories",
                                    icon = Icons.Default.List,
                                    onClick = onNavigateToCategories
                                )
                                ActionChip(
                                    label = "Analytics",
                                    icon = Icons.Default.Settings,
                                    onClick = onNavigateToReports
                                )
                            }
                        }

                        // 2. Budgets Section
                        item {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                GroupedSectionHeader(title = "Monthly Budgets", modifier = Modifier.weight(1f))
                                Text(
                                    text = "See All",
                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier
                                        .clickable { onNavigateToBudgetSetup() }
                                        .padding(8.dp)
                                )
                            }
                        }

                        if (summary.budgetProgress.isEmpty()) {
                            item {
                                BaseCard(
                                    modifier = Modifier.fillMaxWidth(),
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                                ) {
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally, 
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 12.dp)
                                    ) {
                                        Text(
                                            text = "No category limits active",
                                            fontWeight = FontWeight.Bold,
                                            style = MaterialTheme.typography.titleSmall
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = "Control leaks by establishing limits.",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        } else {
                            item {
                                BaseCard(
                                    modifier = Modifier.fillMaxWidth(),
                                    contentPadding = 8.dp
                                ) {
                                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                        summary.budgetProgress.forEach { progress ->
                                            val fraction = if (progress.budget.amount > 0) {
                                                (progress.spentAmount.toFloat() / progress.budget.amount.toFloat()).coerceIn(0f, 1f)
                                            } else {
                                                0f
                                            }
                                            
                                            Column(modifier = Modifier.padding(8.dp)) {
                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    horizontalArrangement = Arrangement.SpaceBetween,
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Text(
                                                        text = "${progress.category.icon ?: "📁"} ${progress.category.name}", 
                                                        fontWeight = FontWeight.SemiBold,
                                                        style = MaterialTheme.typography.titleSmall
                                                    )
                                                    Text(
                                                        text = "${CurrencyUtils.formatCents(progress.spentAmount)} of ${CurrencyUtils.formatCents(progress.budget.amount)}",
                                                        style = NumericLabel,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                                    )
                                                }
                                                Spacer(modifier = Modifier.height(6.dp))
                                                
                                                // Dynamic warning colors
                                                val progressColor = when {
                                                    fraction > 0.9f -> MaterialTheme.colorScheme.error
                                                    fraction > 0.75f -> WarningColor
                                                    else -> SuccessColor
                                                }

                                                LinearProgressIndicator(
                                                    progress = { fraction },
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .height(6.dp)
                                                        .clip(RoundedCornerShape(3.dp)),
                                                    color = progressColor,
                                                    trackColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // 3. Recent Activity Section
                        item {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                GroupedSectionHeader(title = "Recent Activity", modifier = Modifier.weight(1f))
                                Text(
                                    text = "View All",
                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier
                                        .clickable { onNavigateToTransactionList() }
                                        .padding(8.dp)
                                )
                            }
                        }

                        if (summary.recentTransactions.isEmpty()) {
                            item {
                                BaseCard(
                                    modifier = Modifier.fillMaxWidth(),
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 16.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = "No transactions logged yet.", 
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        } else {
                            items(summary.recentTransactions) { item ->
                                val isExpense = item.transaction.type.name == "EXPENSE"
                                val iconColor = if (isExpense) MaterialTheme.colorScheme.error else SuccessColor
                                
                                BaseCard(
                                    modifier = Modifier.fillMaxWidth(),
                                    onClick = { /* Clicking transaction handles in detail redesign */ }
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
                                                    .size(40.dp)
                                                    .clip(CircleShape)
                                                    .background(iconColor.copy(alpha = 0.1f)),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                // Polished system indicators rather than raw unicode arrows
                                                Text(
                                                    text = if (isExpense) "↓" else "↑",
                                                    fontWeight = FontWeight.ExtraBold,
                                                    fontSize = 18.sp,
                                                    color = iconColor
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
                                                item.transaction.notes?.let { notes ->
                                                    if (notes.isNotBlank()) {
                                                        Text(
                                                            text = notes, 
                                                            style = MaterialTheme.typography.bodySmall,
                                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                            maxLines = 1,
                                                            overflow = TextOverflow.Ellipsis
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                        Text(
                                            text = CurrencyUtils.formatCents(item.transaction.totalAmount),
                                            style = FinancialAmount,
                                            color = if (isExpense) MaterialTheme.colorScheme.onSurface else SuccessColor
                                        )
                                    }
                                }
                            }
                        }

                        item { Spacer(modifier = Modifier.height(24.dp)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ActionChip(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)),
        modifier = Modifier.height(40.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp)
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

