package com.ledgerflow.presentation.features.reports

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import android.content.Intent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.ledgerflow.core.common.util.CurrencyUtils
import com.ledgerflow.core.ui.components.BaseCard
import com.ledgerflow.core.ui.components.GroupedSectionHeader
import com.ledgerflow.core.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportsScreen(
    onNavigateBack: () -> Unit,
    onNavigateToTransactionDetail: (Long) -> Unit,
    viewModel: ReportsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    val chartColors = listOf(
        Color(0xFF10B981), // Emerald
        Color(0xFF3B82F6), // Blue
        Color(0xFFF59E0B), // Amber
        Color(0xFFEC4899), // Pink
        Color(0xFF8B5CF6), // Purple
        Color(0xFFEF4444), // Red
        Color(0xFF06B6D4)  // Cyan
    )

    Scaffold(
        topBar = {
            val context = LocalContext.current
            TopAppBar(
                title = { 
                    Text(
                        text = "Analytics Dashboard", 
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    ) 
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        viewModel.exportTransactionsToCsv { csvContent ->
                            val sendIntent = Intent().apply {
                                action = Intent.ACTION_SEND
                                putExtra(Intent.EXTRA_TEXT, csvContent)
                                type = "text/csv"
                            }
                            val shareIntent = Intent.createChooser(sendIntent, "Export Transactions CSV")
                            context.startActivity(shareIntent)
                        }
                    }) {
                        Icon(
                            imageVector = Icons.Default.Share,
                            contentDescription = "Export CSV",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
        ) {
            if (uiState.isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(strokeWidth = 3.dp, color = MaterialTheme.colorScheme.primary)
                }
            } else if (uiState.errorMessage != null) {
                Text(
                    text = uiState.errorMessage ?: "Failed to generate report.",
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.align(Alignment.Center)
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    item { Spacer(modifier = Modifier.height(2.dp)) }

                    // 1. High-Level Summary Card (Total & Average Daily Spend)
                    item {
                        BaseCard(modifier = Modifier.fillMaxWidth()) {
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.Info,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "30-Day Expense Cockpit",
                                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                                    )
                                }
                                
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column {
                                        Text(
                                            text = "TOTAL EXPENSES",
                                            style = MaterialTheme.typography.labelSmall.copy(
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                fontWeight = FontWeight.Bold
                                            )
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = CurrencyUtils.formatCents(uiState.totalExpense),
                                            style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.ExtraBold),
                                            color = MaterialTheme.colorScheme.error
                                        )
                                    }

                                    Column(horizontalAlignment = Alignment.End) {
                                        Text(
                                            text = "DAILY AVERAGE",
                                            style = MaterialTheme.typography.labelSmall.copy(
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                fontWeight = FontWeight.Bold
                                            )
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = CurrencyUtils.formatCents(uiState.averageDailySpend),
                                            style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.ExtraBold),
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // 2. Daily Trend Bar Chart representation
                    if (uiState.dailySpend.isNotEmpty()) {
                        item {
                            GroupedSectionHeader(title = "Daily Spend Trend")
                            Spacer(modifier = Modifier.height(6.dp))
                            BaseCard(modifier = Modifier.fillMaxWidth()) {
                                val maxVal = uiState.dailySpend.maxOfOrNull { it.amount } ?: 1L
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(140.dp)
                                        .padding(top = 16.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.Bottom
                                ) {
                                    // Plot every 3rd day to prevent clutter on small screens
                                    uiState.dailySpend.forEachIndexed { index, daily ->
                                        val barHeightFraction = (daily.amount.toFloat() / maxVal.toFloat()).coerceIn(0.05f, 1f)
                                        Column(
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxHeight(barHeightFraction)
                                                    .width(6.dp)
                                                    .clip(RoundedCornerShape(3.dp))
                                                    .background(
                                                        if (daily.amount > 0) MaterialTheme.colorScheme.primary 
                                                        else MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
                                                    )
                                            )
                                            if (index % 5 == 0) {
                                                Spacer(modifier = Modifier.height(6.dp))
                                                Text(
                                                    text = daily.dateStr.substringAfter(" "),
                                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.sp),
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // 3. Category Breakdown with Donut Canvas Chart
                    if (uiState.categorySummaries.isNotEmpty()) {
                        item {
                            GroupedSectionHeader(title = "Category Share")
                            Spacer(modifier = Modifier.height(6.dp))
                            BaseCard(
                                modifier = Modifier.fillMaxWidth(),
                                contentPadding = 20.dp
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Box(
                                        modifier = Modifier.size(160.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Canvas(modifier = Modifier.fillMaxSize()) {
                                            var startAngle = -90f
                                            uiState.categorySummaries.forEachIndexed { idx, summary ->
                                                val sweepAngle = summary.percentage * 360f
                                                val color = chartColors[idx % chartColors.size]
                                                drawArc(
                                                    color = color,
                                                    startAngle = startAngle,
                                                    sweepAngle = sweepAngle,
                                                    useCenter = false,
                                                    style = Stroke(width = 14.dp.toPx(), cap = StrokeCap.Round)
                                                )
                                                startAngle += sweepAngle
                                            }
                                        }

                                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                            Text(
                                                text = "Categories",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                            Text(
                                                text = "${uiState.categorySummaries.size} Active",
                                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(20.dp))

                                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        uiState.categorySummaries.forEachIndexed { idx, summary ->
                                            val color = chartColors[idx % chartColors.size]
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                                    Box(
                                                        modifier = Modifier
                                                            .size(8.dp)
                                                            .clip(CircleShape)
                                                            .background(color)
                                                    )
                                                    Spacer(modifier = Modifier.width(8.dp))
                                                    Text(
                                                        text = summary.categoryName,
                                                        fontWeight = FontWeight.SemiBold,
                                                        style = MaterialTheme.typography.bodyMedium,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                }
                                                Text(
                                                    text = "${CurrencyUtils.formatCents(summary.amount)} (${(summary.percentage * 100).toInt()}%)",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // 4. Merchant Breakdown
                    if (uiState.merchantBreakdown.isNotEmpty()) {
                        item {
                            GroupedSectionHeader(title = "Top Payees / Merchants")
                            Spacer(modifier = Modifier.height(6.dp))
                            BaseCard(modifier = Modifier.fillMaxWidth()) {
                                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    uiState.merchantBreakdown.forEach { item ->
                                        Column {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween
                                            ) {
                                                Text(
                                                    text = item.merchant,
                                                    fontWeight = FontWeight.SemiBold,
                                                    style = MaterialTheme.typography.bodyMedium
                                                )
                                                Text(
                                                    text = "${CurrencyUtils.formatCents(item.amount)} (${(item.percentage * 100).toInt()}%)",
                                                    style = NumericLabel,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                            Spacer(modifier = Modifier.height(4.dp))
                                            LinearProgressIndicator(
                                                progress = { item.percentage },
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .height(6.dp)
                                                    .clip(RoundedCornerShape(3.dp)),
                                                color = MaterialTheme.colorScheme.primary,
                                                trackColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // 5. Payment Method Distribution
                    if (uiState.paymentMethodDistribution.isNotEmpty()) {
                        item {
                            GroupedSectionHeader(title = "Payment Methods")
                            Spacer(modifier = Modifier.height(6.dp))
                            BaseCard(modifier = Modifier.fillMaxWidth()) {
                                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    uiState.paymentMethodDistribution.forEach { item ->
                                        Column {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween
                                            ) {
                                                Text(
                                                    text = item.paymentMethod,
                                                    fontWeight = FontWeight.SemiBold,
                                                    style = MaterialTheme.typography.bodyMedium
                                                )
                                                Text(
                                                    text = "${CurrencyUtils.formatCents(item.amount)} (${(item.percentage * 100).toInt()}%)",
                                                    style = NumericLabel,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                            Spacer(modifier = Modifier.height(4.dp))
                                            LinearProgressIndicator(
                                                progress = { item.percentage },
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .height(6.dp)
                                                    .clip(RoundedCornerShape(3.dp)),
                                                color = MaterialTheme.colorScheme.secondary,
                                                trackColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // 6. Largest Purchases
                    if (uiState.largestPurchases.isNotEmpty()) {
                        item {
                            GroupedSectionHeader(title = "Largest Purchases")
                            Spacer(modifier = Modifier.height(6.dp))
                            BaseCard(modifier = Modifier.fillMaxWidth()) {
                                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    uiState.largestPurchases.forEach { txn ->
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable { onNavigateToTransactionDetail(txn.id) },
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = txn.merchant,
                                                    fontWeight = FontWeight.SemiBold,
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                Text(
                                                    text = txn.category,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                            Text(
                                                text = CurrencyUtils.formatCents(txn.amount),
                                                style = FinancialAmount,
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // 7. Recurring Merchants
                    if (uiState.recurringMerchants.isNotEmpty()) {
                        item {
                            GroupedSectionHeader(title = "Frequent Merchants")
                            Spacer(modifier = Modifier.height(6.dp))
                            BaseCard(modifier = Modifier.fillMaxWidth()) {
                                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    uiState.recurringMerchants.forEach { m ->
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = m.merchant,
                                                    fontWeight = FontWeight.SemiBold,
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                Text(
                                                    text = "${m.count} visits / transits",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                            Text(
                                                text = CurrencyUtils.formatCents(m.totalAmount),
                                                style = FinancialAmount,
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // 8. Budget Utilization
                    if (uiState.budgetUtilization.isNotEmpty()) {
                        item {
                            GroupedSectionHeader(title = "Budget Exhaustion Share")
                            Spacer(modifier = Modifier.height(6.dp))
                            BaseCard(modifier = Modifier.fillMaxWidth()) {
                                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    uiState.budgetUtilization.forEach { b ->
                                        Column {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween
                                            ) {
                                                Text(
                                                    text = b.categoryName,
                                                    fontWeight = FontWeight.SemiBold,
                                                    style = MaterialTheme.typography.bodyMedium
                                                )
                                                Text(
                                                    text = "${CurrencyUtils.formatCents(b.spentAmount)} / ${CurrencyUtils.formatCents(b.limitAmount)}",
                                                    style = NumericLabel,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                            Spacer(modifier = Modifier.height(4.dp))
                                            LinearProgressIndicator(
                                                progress = { b.percentage },
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .height(6.dp)
                                                    .clip(RoundedCornerShape(3.dp)),
                                                color = if (b.percentage > 0.9f) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                                                trackColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f)
                                            )
                                        }
                                    }
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
