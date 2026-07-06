package com.ledgerflow.presentation.features.dashboard

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.animation.core.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
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
import com.ledgerflow.domain.model.Category
import com.ledgerflow.domain.model.Transaction
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
    onNavigateToPendingList: () -> Unit,
    onNavigateToTransactionDetail: (Long) -> Unit,
    viewModel: DashboardViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

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
                text = { Text("Add Expense") }
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
                    DashboardSkeleton()
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

                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        item { Spacer(modifier = Modifier.height(2.dp)) }

                        // 1. Premium Expense Hero Widget
                        item {
                            val cardBgGradient = Brush.verticalGradient(
                                colors = listOf(
                                    MaterialTheme.colorScheme.primary,
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.85f)
                                )
                            )
                            
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(200.dp),
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
                                            text = "TOTAL EXPENSES",
                                            style = MaterialTheme.typography.labelSmall.copy(
                                                color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f),
                                                fontWeight = FontWeight.Bold,
                                                letterSpacing = 1.sp
                                            )
                                        )
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Text(
                                            text = CurrencyUtils.formatCents(summary.totalExpenses),
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
                                                    text = "Today",
                                                    style = MaterialTheme.typography.labelSmall.copy(
                                                        color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.6f),
                                                        fontWeight = FontWeight.Bold
                                                    )
                                                )
                                                Text(
                                                    text = CurrencyUtils.formatCents(summary.todaySpending),
                                                    style = FinancialAmount,
                                                    color = MaterialTheme.colorScheme.onPrimary
                                                )
                                            }
                                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                                Text(
                                                    text = "Week",
                                                    style = MaterialTheme.typography.labelSmall.copy(
                                                        color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.6f),
                                                        fontWeight = FontWeight.Bold
                                                    )
                                                )
                                                Text(
                                                    text = CurrencyUtils.formatCents(summary.thisWeekSpending),
                                                    style = FinancialAmount,
                                                    color = MaterialTheme.colorScheme.onPrimary
                                                )
                                            }
                                            Column(horizontalAlignment = Alignment.End) {
                                                Text(
                                                    text = "Month",
                                                    style = MaterialTheme.typography.labelSmall.copy(
                                                        color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.6f),
                                                        fontWeight = FontWeight.Bold
                                                    )
                                                )
                                                Text(
                                                    text = CurrencyUtils.formatCents(summary.thisMonthSpending),
                                                    style = FinancialAmount,
                                                    color = MaterialTheme.colorScheme.onPrimary
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // 1.2. Pending review drafts banner
                        if (summary.pendingCount > 0) {
                            item {
                                Card(
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.25f)
                                    ),
                                    shape = RoundedCornerShape(16.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { onNavigateToPendingList() }
                                ) {
                                    Row(
                                        modifier = Modifier.padding(16.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "📝",
                                            style = MaterialTheme.typography.titleMedium
                                        )
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = "Pending Review Drafts",
                                                fontWeight = FontWeight.Bold,
                                                style = MaterialTheme.typography.titleSmall,
                                                color = MaterialTheme.colorScheme.onErrorContainer
                                            )
                                            Text(
                                                text = "You have ${summary.pendingCount} auto-detected expense drafts to approve or discard.",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onErrorContainer
                                            )
                                        }
                                        Text(
                                            text = "Review",
                                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                            color = MaterialTheme.colorScheme.error
                                        )
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
                                    label = "Add Expense",
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
                                if (summary.pendingCount > 0) {
                                    ActionChip(
                                        label = "Drafts (${summary.pendingCount})",
                                        icon = Icons.Default.Star,
                                        onClick = onNavigateToPendingList
                                    )
                                }
                            }
                        }

                        // 1.8. Intelligence Cards Row
                        item {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                // Largest Expense
                                Card(
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(16.dp),
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                                ) {
                                    Column(modifier = Modifier.padding(12.dp)) {
                                        Text("Largest Expense", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = summary.largestExpense?.let { CurrencyUtils.formatCents(it.amount) } ?: "₹0.00",
                                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                                        )
                                        Text(
                                            text = summary.largestExpense?.merchant ?: "None",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }

                                // Top Merchant
                                Card(
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(16.dp),
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                                ) {
                                    Column(modifier = Modifier.padding(12.dp)) {
                                        Text("Top Merchant", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = summary.topMerchant ?: "None",
                                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = "Highest spent",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }

                                // Top Category
                                Card(
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(16.dp),
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                                ) {
                                    Column(modifier = Modifier.padding(12.dp)) {
                                        Text("Top Category", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = summary.topCategory ?: "None",
                                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = "Max allocation",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }

                        // 2. Budgets Section
                        if (summary.budgetProgress.isNotEmpty()) {
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

                        // 3. Top Categories Section
                        if (summary.topCategories.isNotEmpty()) {
                            item {
                                GroupedSectionHeader(title = "Top Categories")
                            }

                            item {
                                BaseCard(
                                    modifier = Modifier.fillMaxWidth(),
                                    contentPadding = 12.dp
                                ) {
                                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        summary.topCategories.forEach { categorySpending ->
                                            val catIcon = categorySpending.icon ?: "📁"
                                            val progress = if (summary.totalExpenses > 0) {
                                                categorySpending.amount.toFloat() / summary.totalExpenses.toFloat()
                                            } else 0f
                                            
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text(
                                                    text = "$catIcon ${categorySpending.categoryName}",
                                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                                    modifier = Modifier.width(120.dp),
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                
                                                Box(
                                                    modifier = Modifier
                                                        .weight(1f)
                                                        .height(8.dp)
                                                        .clip(RoundedCornerShape(4.dp))
                                                        .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))
                                                ) {
                                                    val colorHex = categorySpending.color ?: "#3B82F6"
                                                    val displayColor = try { Color(android.graphics.Color.parseColor(colorHex)) } catch (e: Exception) { MaterialTheme.colorScheme.primary }
                                                    
                                                    Box(
                                                        modifier = Modifier
                                                            .fillMaxHeight()
                                                            .fillMaxWidth(progress.coerceIn(0f, 1f))
                                                            .background(displayColor)
                                                    )
                                                }
                                                
                                                Spacer(modifier = Modifier.width(12.dp))
                                                
                                                Text(
                                                    text = CurrencyUtils.formatCents(categorySpending.amount),
                                                    style = NumericLabel,
                                                    modifier = Modifier.width(80.dp),
                                                    textAlign = TextAlign.End
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // 4. Recent Activity Section
                        item {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                GroupedSectionHeader(title = "Recent Expenses", modifier = Modifier.weight(1f))
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

                        if (summary.recentExpenses.isEmpty()) {
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
                                            text = "No expenses logged yet.", 
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        } else {
                            items(summary.recentExpenses) { txn ->
                                ExpenseRow(transaction = txn, onClick = { onNavigateToTransactionDetail(txn.id) })
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
private fun ExpenseRow(transaction: Transaction, onClick: () -> Unit) {
    BaseCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
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
                        .background(MaterialTheme.colorScheme.error.copy(alpha = 0.1f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "↓",
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 18.sp,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = transaction.merchant, 
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${transaction.category} • ${transaction.paymentMethod ?: "Expense"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Text(
                text = CurrencyUtils.formatCents(transaction.amount),
                style = FinancialAmount,
                color = MaterialTheme.colorScheme.onSurface
            )
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

@Composable
fun ShimmerBrush(): Brush {
    val shimmerColors = listOf(
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f),
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
    )

    val transition = rememberInfiniteTransition(label = "shimmer")
    val translateAnim = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1000f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "translate"
    )

    return Brush.linearGradient(
        colors = shimmerColors,
        start = Offset.Zero,
        end = Offset(x = translateAnim.value, y = translateAnim.value)
    )
}

@Composable
fun DashboardSkeleton() {
    val shimmerBrush = ShimmerBrush()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Spacer(modifier = Modifier.height(2.dp))
        
        // Hero card skeleton
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(shimmerBrush)
        )
        
        // Row of stats skeleton
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(80.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(shimmerBrush)
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(80.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(shimmerBrush)
            )
        }
        
        // Section header skeleton
        Box(
            modifier = Modifier
                .width(120.dp)
                .height(24.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(shimmerBrush)
        )
        
        // List skeleton items
        repeat(3) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(72.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(shimmerBrush)
            ) {}
        }
    }
}
