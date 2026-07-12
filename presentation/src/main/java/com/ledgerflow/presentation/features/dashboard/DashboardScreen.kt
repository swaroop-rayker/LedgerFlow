package com.ledgerflow.presentation.features.dashboard

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
    val scrollState = rememberLazyListState()
    
    val isFabExpanded by remember {
        derivedStateOf {
            scrollState.firstVisibleItemIndex == 0 && scrollState.firstVisibleItemScrollOffset < 100
        }
    }

    val greeting = remember {
        when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
            in 0..11 -> "Good morning"
            in 12..16 -> "Good afternoon"
            else -> "Good evening"
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    Column {
                        Text(
                            text = greeting.uppercase(),
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.sp
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                        Spacer(modifier = Modifier.height(2.dp))
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
            FloatingActionButton(
                onClick = onNavigateToAddTransaction,
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                shape = RoundedCornerShape(CornerRadius.l)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = Spacing.l),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Add Expense")
                    AnimatedVisibility(
                        visible = isFabExpanded,
                        enter = fadeIn() + expandHorizontally(),
                        exit = fadeOut() + shrinkHorizontally()
                    ) {
                        Text(
                            text = "Add Expense",
                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                            modifier = Modifier.padding(start = Spacing.s)
                        )
                    }
                }
            }
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

                    var showBudgetsSection by remember { mutableStateOf(true) }
                    var showCategoriesSection by remember { mutableStateOf(true) }
                    var showRecentSection by remember { mutableStateOf(true) }

                    LazyColumn(
                        state = scrollState,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = Spacing.l),
                        verticalArrangement = Arrangement.spacedBy(Spacing.l)
                    ) {
                        item { Spacer(modifier = Modifier.height(Spacing.xxs)) }

                        // 1. Premium Wallet-Style Card
                        item {
                            val cardBgGradient = Brush.verticalGradient(
                                colors = listOf(
                                    MaterialTheme.colorScheme.primary,
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.9f)
                                )
                            )
                            
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(210.dp),
                                shape = RoundedCornerShape(CornerRadius.xl),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary),
                                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(cardBgGradient)
                                        .padding(Spacing.xl)
                                ) {
                                    Column(modifier = Modifier.fillMaxSize()) {
                                        Text(
                                            text = "TOTAL SPENT THIS MONTH",
                                            style = MaterialTheme.typography.labelSmall.copy(
                                                color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.6f),
                                                fontWeight = FontWeight.Bold,
                                                letterSpacing = 1.sp
                                            )
                                        )
                                        Spacer(modifier = Modifier.height(Spacing.xs))
                                        Text(
                                            text = CurrencyUtils.formatCents(summary.thisMonthSpending),
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
                                                    text = "This Week",
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
                                                    text = "Total Logs",
                                                    style = MaterialTheme.typography.labelSmall.copy(
                                                        color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.6f),
                                                        fontWeight = FontWeight.Bold
                                                    )
                                                )
                                                Text(
                                                    text = "${summary.recentExpenses.size} items",
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
                                        containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.2f)
                                    ),
                                    shape = RoundedCornerShape(CornerRadius.l),
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.15f)),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { onNavigateToPendingList() }
                                ) {
                                    Row(
                                        modifier = Modifier.padding(Spacing.l),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "📝",
                                            style = MaterialTheme.typography.titleMedium
                                        )
                                        Spacer(modifier = Modifier.width(Spacing.s))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = "Drafts Pending Review",
                                                fontWeight = FontWeight.Bold,
                                                style = MaterialTheme.typography.titleSmall,
                                                color = MaterialTheme.colorScheme.onErrorContainer
                                            )
                                            Text(
                                                text = "You have ${summary.pendingCount} auto-detected drafts.",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f)
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

                        // 1.5. Quick Actions Pill Row
                        item {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(Spacing.s)
                            ) {
                                ActionChip(
                                    label = "Add Expense",
                                    icon = Icons.Default.Add,
                                    onClick = onNavigateToAddTransaction
                                )
                                ActionChip(
                                    label = "Budgets",
                                    icon = Icons.Default.DateRange,
                                    onClick = onNavigateToBudgetSetup
                                )
                                ActionChip(
                                    label = "Categories",
                                    icon = Icons.Default.List,
                                    onClick = onNavigateToCategories
                                )
                                ActionChip(
                                    label = "Analytics",
                                    icon = Icons.Default.Info,
                                    onClick = onNavigateToReports
                                )
                                if (summary.pendingCount > 0) {
                                    ActionChip(
                                        label = "Drafts (${summary.pendingCount})",
                                        icon = Icons.Default.Edit,
                                        onClick = onNavigateToPendingList
                                    )
                                }
                            }
                        }

                        // 1.7. Trend Chart Widget
                        item {
                            BaseCard(modifier = Modifier.fillMaxWidth()) {
                                Column(modifier = Modifier.fillMaxWidth()) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "SPENDING TREND",
                                            style = MaterialTheme.typography.labelSmall.copy(
                                                fontWeight = FontWeight.Bold,
                                                letterSpacing = 1.sp
                                            ),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Text(
                                            text = "Last 5 Logs",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(Spacing.m))
                                    DashboardCanvasChart(summary.recentExpenses.take(5))
                                }
                            }
                        }

                        // 1.8. Intelligence Cards Row
                        item {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(Spacing.s)
                            ) {
                                // Top Merchant Card
                                Card(
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(CornerRadius.l),
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.08f))
                                ) {
                                    Column(modifier = Modifier.padding(Spacing.m)) {
                                        Text("Top Payee", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = summary.topMerchant ?: "None",
                                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = "Highest volume",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                        )
                                    }
                                }

                                // Top Category Card
                                Card(
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(CornerRadius.l),
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.08f))
                                ) {
                                    Column(modifier = Modifier.padding(Spacing.m)) {
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
                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                        )
                                    }
                                }
                            }
                        }

                        // 2. Budgets Section (Collapsible)
                        if (summary.budgetProgress.isNotEmpty()) {
                            item {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { showBudgetsSection = !showBudgetsSection }
                                        .padding(vertical = Spacing.xs),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    GroupedSectionHeader(title = "Budgets Gauges", modifier = Modifier.weight(1f))
                                    Icon(
                                        imageVector = if (showBudgetsSection) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            if (showBudgetsSection) {
                                item {
                                    BaseCard(modifier = Modifier.fillMaxWidth()) {
                                        Column(verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
                                            summary.budgetProgress.forEach { progress ->
                                                val fraction = if (progress.budget.amount > 0) {
                                                    (progress.spentAmount.toFloat() / progress.budget.amount.toFloat()).coerceIn(0f, 1f)
                                                } else {
                                                    0f
                                                }
                                                val categoryColor = CuratedColors.getOrDefault(progress.category.color, MaterialTheme.colorScheme.primary)
                                                
                                                Column {
                                                    Row(
                                                        modifier = Modifier.fillMaxWidth(),
                                                        horizontalArrangement = Arrangement.SpaceBetween,
                                                        verticalAlignment = Alignment.CenterVertically
                                                    ) {
                                                        Text(
                                                            text = "${progress.category.icon ?: "📁"} ${progress.category.name}", 
                                                            fontWeight = FontWeight.SemiBold,
                                                            style = MaterialTheme.typography.bodyMedium
                                                        )
                                                        Text(
                                                            text = "${CurrencyUtils.formatCents(progress.spentAmount)} of ${CurrencyUtils.formatCents(progress.budget.amount)}",
                                                            style = NumericLabel,
                                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                                        )
                                                    }
                                                    Spacer(modifier = Modifier.height(Spacing.xs))
                                                    
                                                    val progressColor = when {
                                                        fraction > 0.9f -> MaterialTheme.colorScheme.error
                                                        fraction > 0.75f -> WarningColor
                                                        else -> categoryColor
                                                    }

                                                    LinearProgressIndicator(
                                                        progress = { fraction },
                                                        modifier = Modifier
                                                            .fillMaxWidth()
                                                            .height(8.dp)
                                                            .clip(CircleShape),
                                                        color = progressColor,
                                                        trackColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f)
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // 3. Top Categories Section (Collapsible)
                        if (summary.topCategories.isNotEmpty()) {
                            item {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { showCategoriesSection = !showCategoriesSection }
                                        .padding(vertical = Spacing.xs),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    GroupedSectionHeader(title = "Top Allocations", modifier = Modifier.weight(1f))
                                    Icon(
                                        imageVector = if (showCategoriesSection) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            if (showCategoriesSection) {
                                item {
                                    BaseCard(modifier = Modifier.fillMaxWidth()) {
                                        Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                                            summary.topCategories.forEach { categorySpending ->
                                                val catIcon = categorySpending.icon ?: "📁"
                                                val progress = if (summary.totalExpenses > 0) {
                                                    categorySpending.amount.toFloat() / summary.totalExpenses.toFloat()
                                                } else 0f
                                                val displayColor = CuratedColors.getOrDefault(categorySpending.color, MaterialTheme.colorScheme.primary)
                                                
                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Text(
                                                        text = "$catIcon ${categorySpending.categoryName}",
                                                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                                        modifier = Modifier.width(130.dp),
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                    
                                                    Box(
                                                        modifier = Modifier
                                                            .weight(1f)
                                                            .height(8.dp)
                                                            .clip(CircleShape)
                                                            .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.1f))
                                                    ) {
                                                        Box(
                                                            modifier = Modifier
                                                                .fillMaxHeight()
                                                                .fillMaxWidth(progress.coerceIn(0f, 1f))
                                                                .background(displayColor)
                                                        )
                                                    }
                                                    
                                                    Spacer(modifier = Modifier.width(Spacing.s))
                                                    
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
                        }

                        // 4. Recent Activity Section (Collapsible)
                        item {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { showRecentSection = !showRecentSection }
                                    .padding(vertical = Spacing.xs),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                GroupedSectionHeader(title = "Recent Activity", modifier = Modifier.weight(1f))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = "See All",
                                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier
                                            .clickable { onNavigateToTransactionList() }
                                            .padding(end = Spacing.s)
                                    )
                                    Icon(
                                        imageVector = if (showRecentSection) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }

                        if (showRecentSection) {
                            if (summary.recentExpenses.isEmpty()) {
                                item {
                                    BaseCard(
                                        modifier = Modifier.fillMaxWidth(),
                                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.15f)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(vertical = Spacing.l),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = "No expenses logged yet.", 
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                            )
                                        }
                                    }
                                }
                            } else {
                                items(summary.recentExpenses.take(5), key = { it.id }) { txn ->
                                    ExpenseRow(transaction = txn, onClick = { onNavigateToTransactionDetail(txn.id) })
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
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.error.copy(alpha = 0.08f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "↓",
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 18.sp,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                Spacer(modifier = Modifier.width(Spacing.s))
                Column {
                    Text(
                        text = transaction.merchant, 
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    val sdf = remember { SimpleDateFormat("MMM d, yyyy", Locale.getDefault()) }
                    val dateStr = sdf.format(Date(transaction.timestamp))
                    Text(
                        text = "${transaction.category} • ${transaction.paymentMethod ?: "Expense"} • $dateStr",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            val isCredit = transaction.amount < 0
            val amountText = if (isCredit) {
                "+" + CurrencyUtils.formatCents(-transaction.amount)
            } else {
                "-" + CurrencyUtils.formatCents(transaction.amount)
            }
            val amountColor = if (isCredit) {
                Color(0xFF2E7D32) // Forest Green for Inflow
            } else {
                MaterialTheme.colorScheme.onSurface
            }
            Text(
                text = amountText,
                style = FinancialAmount,
                color = amountColor
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
        shape = RoundedCornerShape(CornerRadius.m),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.08f)),
        modifier = Modifier.height(44.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Spacing.m, vertical = Spacing.s),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp)
            )
            Text(
                text = label,
                maxLines = 1,
                softWrap = false,
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
fun DashboardCanvasChart(recentExpenses: List<Transaction>) {
    com.ledgerflow.presentation.components.PolishedSpendChart(
        transactions = recentExpenses,
        modifier = Modifier.fillMaxWidth().height(220.dp)
    )
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
            .padding(horizontal = Spacing.l),
        verticalArrangement = Arrangement.spacedBy(Spacing.l)
    ) {
        Spacer(modifier = Modifier.height(Spacing.xxs))
        
        // Hero card skeleton
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp)
                .clip(RoundedCornerShape(CornerRadius.xl))
                .background(shimmerBrush)
        )
        
        // Row of stats skeleton
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.m)
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(80.dp)
                    .clip(RoundedCornerShape(CornerRadius.l))
                    .background(shimmerBrush)
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(80.dp)
                    .clip(RoundedCornerShape(CornerRadius.l))
                    .background(shimmerBrush)
            )
        }
        
        // Section header skeleton
        Box(
            modifier = Modifier
                .width(120.dp)
                .height(24.dp)
                .clip(RoundedCornerShape(CornerRadius.s))
                .background(shimmerBrush)
        )
        
        // List skeleton items
        repeat(3) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(72.dp)
                    .clip(RoundedCornerShape(CornerRadius.l))
                    .background(shimmerBrush)
            ) {}
        }
    }
}
