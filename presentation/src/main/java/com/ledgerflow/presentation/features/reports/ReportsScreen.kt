package com.ledgerflow.presentation.features.reports

import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import android.content.Intent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
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
import com.ledgerflow.presentation.components.PolishedSpendChart
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.BorderStroke

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportsScreen(
    onNavigateBack: () -> Unit,
    onNavigateToTransactionDetail: (Long) -> Unit,
    viewModel: ReportsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    var isFilterPanelExpanded by remember { mutableStateOf(false) }

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
                        text = "Advanced Analytics", 
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    ) 
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { isFilterPanelExpanded = !isFilterPanelExpanded }) {
                        Icon(
                            imageVector = Icons.Default.Menu,
                            contentDescription = "Filters",
                            tint = if (isFilterPanelExpanded) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
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
                .padding(horizontal = Spacing.l)
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
                    verticalArrangement = Arrangement.spacedBy(Spacing.m)
                ) {
                    // Collapsible Filter & Configuration Section
                    item {
                        AnimatedVisibility(
                            visible = isFilterPanelExpanded,
                            enter = fadeIn() + expandVertically(),
                            exit = fadeOut() + shrinkVertically()
                        ) {
                            BaseCard(
                                modifier = Modifier.fillMaxWidth(),
                                contentPadding = 12.dp
                            ) {
                                Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                                    // Section Header
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "Filters & Engine Configuration",
                                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                        TextButton(onClick = { viewModel.clearFilters() }) {
                                            Text("Reset", style = MaterialTheme.typography.bodySmall)
                                        }
                                    }

                                    // 1. Transaction Direction Selector (Segmented buttons replacement)
                                    Column {
                                        Text("Transaction Direction", style = MaterialTheme.typography.labelSmall)
                                        Spacer(modifier = Modifier.height(Spacing.xs))
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
                                        ) {
                                            val directions = listOf("All", "Debit", "Credit")
                                            directions.forEach { direction ->
                                                val isSelected = uiState.selectedDirection == direction
                                                Card(
                                                    colors = CardDefaults.cardColors(
                                                        containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                                                    ),
                                                    modifier = Modifier
                                                        .weight(1f)
                                                        .clickable { viewModel.setDirectionFilter(direction) }
                                                ) {
                                                    Box(
                                                        modifier = Modifier
                                                            .fillMaxWidth()
                                                            .padding(vertical = Spacing.xs),
                                                        contentAlignment = Alignment.Center
                                                    ) {
                                                        Text(
                                                            text = direction,
                                                            maxLines = 1,
                                                            softWrap = false,
                                                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                                            color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }

                                    // 2. Multi-Select Category Chips
                                    Column {
                                        Text("Filter by Categories", style = MaterialTheme.typography.labelSmall)
                                        Spacer(modifier = Modifier.height(Spacing.xs))
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .horizontalScroll(rememberScrollState()),
                                            horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
                                        ) {
                                            uiState.allCategories.forEach { catName ->
                                                val isSelected = uiState.selectedCategories.contains(catName)
                                                FilterChip(
                                                    selected = isSelected,
                                                    onClick = { viewModel.toggleCategoryFilter(catName) },
                                                    label = { Text(catName, style = MaterialTheme.typography.bodySmall) }
                                                )
                                            }
                                        }
                                    }

                                    // 3. Multi-Select Subcategory Chips
                                    if (uiState.allSubcategories.isNotEmpty()) {
                                        Column {
                                            Text("Filter by Subcategories", style = MaterialTheme.typography.labelSmall)
                                            Spacer(modifier = Modifier.height(Spacing.xs))
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .horizontalScroll(rememberScrollState()),
                                                horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
                                            ) {
                                                uiState.allSubcategories.forEach { subName ->
                                                    val isSelected = uiState.selectedSubcategories.contains(subName)
                                                    FilterChip(
                                                        selected = isSelected,
                                                        onClick = { viewModel.toggleSubcategoryFilter(subName) },
                                                        label = { Text(subName, style = MaterialTheme.typography.bodySmall) }
                                                    )
                                                }
                                            }
                                        }
                                    }

                                    // Filter by Payment Methods (including Cash)
                                    val paymentFilters = remember(uiState.allPaymentMethods) {
                                        val defaults = listOf("Cash", "UPI", "Credit Card", "Debit Card", "Net Banking", "Wallet", "Bank Transfer", "Others")
                                        (uiState.allPaymentMethods + defaults).distinct()
                                    }
                                    Column {
                                        Text("Filter by Payment Methods", style = MaterialTheme.typography.labelSmall)
                                        Spacer(modifier = Modifier.height(Spacing.xs))
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .horizontalScroll(rememberScrollState()),
                                            horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
                                        ) {
                                            paymentFilters.forEach { pm ->
                                                val isSelected = uiState.selectedPaymentMethods.contains(pm)
                                                FilterChip(
                                                    selected = isSelected,
                                                    onClick = { viewModel.togglePaymentMethodFilter(pm) },
                                                    label = { Text(pm, style = MaterialTheme.typography.bodySmall) }
                                                )
                                            }
                                        }
                                    }

                                    Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f))

                                    // 4. Grouping & Aggregation controls
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(Spacing.s)
                                    ) {
                                        // Group By Dropdown
                                        var groupExpanded by remember { mutableStateOf(false) }
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text("Group By", style = MaterialTheme.typography.labelSmall)
                                            Spacer(modifier = Modifier.height(Spacing.xs))
                                            OutlinedButton(
                                                onClick = { groupExpanded = true },
                                                modifier = Modifier.fillMaxWidth(),
                                                shape = RoundedCornerShape(CornerRadius.s),
                                                contentPadding = PaddingValues(horizontal = Spacing.s, vertical = Spacing.xs)
                                            ) {
                                                Text(uiState.activeGrouping, style = MaterialTheme.typography.bodySmall)
                                                Spacer(modifier = Modifier.weight(1f))
                                                Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                                            }
                                            DropdownMenu(expanded = groupExpanded, onDismissRequest = { groupExpanded = false }) {
                                                val groups = listOf("Category", "Subcategory", "Merchant", "Payment Method", "Day", "Week", "Month")
                                                groups.forEach { g ->
                                                    DropdownMenuItem(
                                                        text = { Text(g) },
                                                        onClick = {
                                                            viewModel.setGrouping(g)
                                                            groupExpanded = false
                                                        }
                                                    )
                                                }
                                            }
                                        }

                                        // Aggregate By Dropdown
                                        var aggExpanded by remember { mutableStateOf(false) }
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text("Aggregation", style = MaterialTheme.typography.labelSmall)
                                            Spacer(modifier = Modifier.height(Spacing.xs))
                                            OutlinedButton(
                                                onClick = { aggExpanded = true },
                                                modifier = Modifier.fillMaxWidth(),
                                                shape = RoundedCornerShape(CornerRadius.s),
                                                contentPadding = PaddingValues(horizontal = Spacing.s, vertical = Spacing.xs)
                                            ) {
                                                Text(uiState.activeAggregation, style = MaterialTheme.typography.bodySmall)
                                                Spacer(modifier = Modifier.weight(1f))
                                                Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                                            }
                                            DropdownMenu(expanded = aggExpanded, onDismissRequest = { aggExpanded = false }) {
                                                val aggs = listOf("Total Spend", "Average", "Median", "Min", "Max", "Count")
                                                aggs.forEach { a ->
                                                    DropdownMenuItem(
                                                        text = { Text(a) },
                                                        onClick = {
                                                            viewModel.setAggregation(a)
                                                            aggExpanded = false
                                                        }
                                                    )
                                                }
                                            }
                                        }
                                    }

                                    // 5. Visualization Type Selector
                                    Column {
                                        Text("Visualization Mode", style = MaterialTheme.typography.labelSmall)
                                        Spacer(modifier = Modifier.height(Spacing.xs))
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
                                        ) {
                                            val visModes = listOf("Donut", "Horizontal Bar", "Line")
                                            visModes.forEach { mode ->
                                                val isSelected = uiState.activeVisualization == mode
                                                OutlinedButton(
                                                    onClick = { viewModel.setVisualization(mode) },
                                                    shape = RoundedCornerShape(CornerRadius.s),
                                                    modifier = Modifier.weight(1f),
                                                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
                                                    colors = ButtonDefaults.outlinedButtonColors(
                                                        containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
                                                    ),
                                                    border = BorderStroke(1.dp, if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                                                ) {
                                                    Text(
                                                        text = mode,
                                                        maxLines = 1,
                                                        softWrap = false,
                                                        overflow = TextOverflow.Ellipsis,
                                                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                                                        color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // 1. High-Level Summary Card (Total Expense & Income)
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
                                        text = "Executive Summary",
                                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                                    )
                                }
                                
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column {
                                        Text(
                                            text = "TOTAL DEBITS",
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
                                            text = "TOTAL CREDITS",
                                            style = MaterialTheme.typography.labelSmall.copy(
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                fontWeight = FontWeight.Bold
                                            )
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = CurrencyUtils.formatCents(uiState.totalIncome),
                                            style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.ExtraBold),
                                            color = Color(0xFF10B981)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // 2. Interactive Charts rendering
                    item {
                        GroupedSectionHeader(title = "Spend & Income Analysis")
                        Spacer(modifier = Modifier.height(4.dp))
                        when (uiState.activeVisualization) {
                            "Line" -> {
                                PolishedSpendChart(
                                    transactions = uiState.filteredTransactions,
                                    modifier = Modifier.fillMaxWidth().height(260.dp)
                                )
                            }
                            "Horizontal Bar" -> {
                                BaseCard(modifier = Modifier.fillMaxWidth()) {
                                    Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                                        uiState.groupedData.take(8).forEachIndexed { idx, item ->
                                            val color = chartColors[idx % chartColors.size]
                                            Column {
                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    horizontalArrangement = Arrangement.SpaceBetween,
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Text(
                                                        text = item.key,
                                                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
                                                    )
                                                    Text(
                                                        text = if (uiState.activeAggregation == "Count") {
                                                            "${(item.value / 100)} Txns"
                                                        } else {
                                                            CurrencyUtils.formatCents(item.value)
                                                        },
                                                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold)
                                                    )
                                                }
                                                Spacer(modifier = Modifier.height(4.dp))
                                                LinearProgressIndicator(
                                                    progress = { item.percentage },
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .height(8.dp)
                                                        .clip(RoundedCornerShape(4.dp)),
                                                    color = color,
                                                    trackColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                            else -> { // Donut Chart (Default)
                                BaseCard(
                                    modifier = Modifier.fillMaxWidth(),
                                    contentPadding = 20.dp
                                ) {
                                    var activeSliceIndex by remember { mutableStateOf<Int?>(null) }
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Box(
                                            modifier = Modifier.size(170.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Canvas(
                                                modifier = Modifier
                                                    .fillMaxSize()
                                                    .pointerInput(uiState.groupedData) {
                                                        val w = size.width.toFloat()
                                                        val h = size.height.toFloat()
                                                        detectTapGestures { offset ->
                                                            val center = Offset(w / 2f, h / 2f)
                                                            val x = offset.x - center.x
                                                            val y = offset.y - center.y
                                                            var angle = Math.toDegrees(Math.atan2(y.toDouble(), x.toDouble())).toFloat()
                                                            if (angle < 0) angle += 360f
                                                            // Normalize to start at -90 degrees (top)
                                                            val normalizedAngle = (angle + 90f) % 360f

                                                            var accumulatedAngle = 0f
                                                            var clickedIndex: Int? = null
                                                            uiState.groupedData.forEachIndexed { index, item ->
                                                                val sweepAngle = item.percentage * 360f
                                                                if (normalizedAngle >= accumulatedAngle && normalizedAngle <= accumulatedAngle + sweepAngle) {
                                                                    clickedIndex = index
                                                                }
                                                                accumulatedAngle += sweepAngle
                                                            }
                                                            activeSliceIndex = if (activeSliceIndex == clickedIndex) null else clickedIndex
                                                        }
                                                    }
                                            ) {
                                                var startAngle = -90f
                                                uiState.groupedData.forEachIndexed { idx, summary ->
                                                    val sweepAngle = summary.percentage * 360f
                                                    val color = chartColors[idx % chartColors.size]
                                                    val isSelected = activeSliceIndex == idx
                                                    val strokeWidth = if (isSelected) 22.dp.toPx() else 14.dp.toPx()

                                                    drawArc(
                                                        color = color,
                                                        startAngle = startAngle,
                                                        sweepAngle = sweepAngle,
                                                        useCenter = false,
                                                        style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                                                    )
                                                    startAngle += sweepAngle
                                                }
                                            }

                                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                                if (activeSliceIndex != null && activeSliceIndex!! < uiState.groupedData.size) {
                                                    val selectedItem = uiState.groupedData[activeSliceIndex!!]
                                                    Text(
                                                        text = selectedItem.key,
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                    Text(
                                                        text = "${(selectedItem.percentage * 100).toInt()}%",
                                                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                                                    )
                                                } else {
                                                    Text(
                                                        text = uiState.activeGrouping,
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                                    )
                                                    Text(
                                                        text = "${uiState.groupedData.size} Groups",
                                                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                                        color = MaterialTheme.colorScheme.onSurface
                                                    )
                                                }
                                            }
                                        }

                                        Spacer(modifier = Modifier.height(20.dp))

                                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                            uiState.groupedData.forEachIndexed { idx, summary ->
                                                val color = chartColors[idx % chartColors.size]
                                                val isSelected = activeSliceIndex == idx
                                                Row(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .clip(RoundedCornerShape(CornerRadius.s))
                                                        .background(if (isSelected) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f) else Color.Transparent)
                                                        .clickable { activeSliceIndex = if (isSelected) null else idx }
                                                        .padding(Spacing.xs),
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
                                                            text = summary.key,
                                                            fontWeight = FontWeight.SemiBold,
                                                            style = MaterialTheme.typography.bodyMedium,
                                                            maxLines = 1,
                                                            overflow = TextOverflow.Ellipsis
                                                        )
                                                    }
                                                    Text(
                                                        text = if (uiState.activeAggregation == "Count") {
                                                            "${(summary.value / 100)} Txns (${(summary.percentage * 100).toInt()}%)"
                                                        } else {
                                                            "${CurrencyUtils.formatCents(summary.value)} (${(summary.percentage * 100).toInt()}%)"
                                                        },
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
                    }

                    // 3. Budgets Allocation progress
                    if (uiState.budgetUtilization.isNotEmpty()) {
                        item {
                            GroupedSectionHeader(title = "Budget Thresholds")
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
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = if (b.percentage >= 0.9f) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                            Spacer(modifier = Modifier.height(4.dp))
                                            LinearProgressIndicator(
                                                progress = { b.percentage },
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .height(6.dp)
                                                    .clip(RoundedCornerShape(3.dp)),
                                                color = if (b.percentage >= 0.9f) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                                                trackColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // 4. Largest Purchases
                    if (uiState.largestPurchases.isNotEmpty()) {
                        item {
                            GroupedSectionHeader(title = "Largest Transactions")
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
                                                text = if (txn.amount < 0) {
                                                    "+" + CurrencyUtils.formatCents(kotlin.math.abs(txn.amount))
                                                } else {
                                                    "-" + CurrencyUtils.formatCents(txn.amount)
                                                },
                                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                                color = if (txn.amount < 0) Color(0xFF10B981) else MaterialTheme.colorScheme.onSurface
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
