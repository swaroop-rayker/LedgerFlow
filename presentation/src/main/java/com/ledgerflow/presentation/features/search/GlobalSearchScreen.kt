package com.ledgerflow.presentation.features.search

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.ledgerflow.core.common.util.CurrencyUtils
import com.ledgerflow.core.ui.components.BaseCard
import com.ledgerflow.core.ui.components.EmptyStateView
import com.ledgerflow.core.ui.components.GroupedSectionHeader
import com.ledgerflow.domain.model.Transaction
import com.ledgerflow.core.ui.theme.Spacing
import com.ledgerflow.core.ui.theme.CornerRadius

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun GlobalSearchScreen(
    onNavigateBack: () -> Unit,
    onNavigateToTransactionDetail: (Long) -> Unit,
    viewModel: GlobalSearchViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val highlightColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)

    var selectedPaymentFilter by remember { mutableStateOf<String?>(null) }
    var selectedAmountFilter by remember { mutableStateOf<String?>(null) } // "> 100", "> 500"

    // Dynamically filter search results in-memory
    val filteredSearchResults = remember(uiState.searchResults, selectedPaymentFilter, selectedAmountFilter) {
        val raw = uiState.searchResults ?: return@remember null
        val txs = raw.transactions.filter { txn ->
            val matchesPayment = selectedPaymentFilter == null || txn.paymentMethod.equals(selectedPaymentFilter, ignoreCase = true)
            
            val matchesAmount = when (selectedAmountFilter) {
                "> ₹100" -> txn.amount > 10000L // 10000 paise/cents
                "> ₹500" -> txn.amount > 50000L
                else -> true
            }
            matchesPayment && matchesAmount
        }
        raw.copy(transactions = txs)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                },
                title = {
                    OutlinedTextField(
                        value = uiState.query,
                        onValueChange = { viewModel.updateQuery(it) },
                        placeholder = { Text("Search transactions, payees...", style = MaterialTheme.typography.bodyMedium) },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color.Transparent,
                            unfocusedBorderColor = Color.Transparent,
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent
                        ),
                        textStyle = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.fillMaxWidth()
                    )
                },
                actions = {
                    if (uiState.query.isNotEmpty()) {
                        val isSaved = uiState.savedSearches.contains(uiState.query.trim())
                        IconButton(onClick = { viewModel.saveCurrentSearch() }) {
                            Icon(
                                imageVector = Icons.Filled.Star,
                                contentDescription = "Save Search",
                                tint = if (isSaved) Color(0xFFFFC107) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                            )
                        }
                        IconButton(onClick = { viewModel.updateQuery("") }) {
                            Icon(
                                imageVector = Icons.Filled.Clear,
                                contentDescription = "Clear",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(innerPadding)
                .padding(horizontal = Spacing.l)
        ) {
            // Filter chips panel (Payment method & amount filters)
            if (uiState.query.trim().isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(vertical = Spacing.s),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.s)
                ) {
                    // Payment Method Filter
                    listOf("Cash", "UPI", "Credit Card", "Debit Card", "Net Banking", "Wallet", "Bank Transfer", "Others").forEach { method ->
                        val isSelected = selectedPaymentFilter == method
                        FilterChip(
                            selected = isSelected,
                            onClick = { selectedPaymentFilter = if (isSelected) null else method },
                            label = { Text(method, maxLines = 1, style = MaterialTheme.typography.labelMedium) }
                        )
                    }

                    // Amount Filter
                    listOf("> ₹100", "> ₹500").forEach { amountLabel ->
                        val isSelected = selectedAmountFilter == amountLabel
                        FilterChip(
                            selected = isSelected,
                            onClick = { selectedAmountFilter = if (isSelected) null else amountLabel },
                            label = { Text(amountLabel, maxLines = 1, style = MaterialTheme.typography.labelMedium) }
                        )
                    }
                }
            }

            if (uiState.isLoading) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    color = MaterialTheme.colorScheme.primary
                )
            }

            AnimatedVisibility(
                visible = uiState.query.trim().isEmpty(),
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    if (uiState.recentSearches.isEmpty() && uiState.savedSearches.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            EmptyStateView(
                                title = "Global Search Desk",
                                description = "Query transaction histories, payees, notes, or categories.",
                                iconEmoji = "🔎"
                            )
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(Spacing.s)
                        ) {
                            if (uiState.savedSearches.isNotEmpty()) {
                                item {
                                    GroupedSectionHeader(title = "Pinned Searches")
                                }
                                items(uiState.savedSearches) { saved ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(CornerRadius.m))
                                            .background(MaterialTheme.colorScheme.surface)
                                            .clickable { viewModel.updateQuery(saved) }
                                            .padding(vertical = Spacing.s, horizontal = Spacing.m),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(
                                                imageVector = Icons.Filled.Star,
                                                contentDescription = null,
                                                tint = Color(0xFFFFC107),
                                                modifier = Modifier.size(20.dp)
                                            )
                                            Spacer(modifier = Modifier.width(Spacing.s))
                                            Text(
                                                text = saved,
                                                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold)
                                            )
                                        }
                                        IconButton(onClick = { viewModel.deleteSavedSearch(saved) }) {
                                            Icon(
                                                imageVector = Icons.Filled.Delete,
                                                contentDescription = "Delete",
                                                tint = MaterialTheme.colorScheme.error
                                            )
                                        }
                                    }
                                }
                            }

                            if (uiState.recentSearches.isNotEmpty()) {
                                item {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        GroupedSectionHeader(title = "Recent Search Trail")
                                        TextButton(onClick = { viewModel.clearSearchHistory() }) {
                                            Text("Clear History", color = MaterialTheme.colorScheme.primary)
                                        }
                                    }
                                }
                                items(uiState.recentSearches) { recent ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(CornerRadius.m))
                                            .background(MaterialTheme.colorScheme.surface)
                                            .clickable { viewModel.updateQuery(recent) }
                                            .padding(vertical = Spacing.m, horizontal = Spacing.m),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(text = "🕒", fontSize = 16.sp)
                                        Spacer(modifier = Modifier.width(Spacing.s))
                                        Text(
                                            text = recent,
                                            style = MaterialTheme.typography.bodyLarge,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            AnimatedVisibility(
                visible = uiState.query.trim().isNotEmpty(),
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                val results = filteredSearchResults
                if (results == null && !uiState.isLoading) {
                    if (uiState.errorMessage != null) {
                        EmptyStateView(
                            title = "Search Error",
                            description = uiState.errorMessage ?: "Search failed.",
                            iconEmoji = "⚠️"
                        )
                    }
                } else if (results != null) {
                    val isEmpty = results.transactions.isEmpty() &&
                            results.categories.isEmpty() &&
                            results.merchants.isEmpty()

                    if (isEmpty && !uiState.isLoading) {
                        EmptyStateView(
                            title = "No Matches Found",
                            description = "Refine search keywords or change filter options.",
                            iconEmoji = "🔍"
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(Spacing.s)
                        ) {
                            if (results.merchants.isNotEmpty()) {
                                stickyHeader(key = "header_merchants") {
                                    Box(modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background)) {
                                        GroupedSectionHeader(title = "Payees")
                                    }
                                }
                                items(results.merchants) { merchant ->
                                    BaseCard(
                                        modifier = Modifier.fillMaxWidth(),
                                        onClick = {},
                                        contentPadding = Spacing.m
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text("🏪", fontSize = 20.sp)
                                            Spacer(modifier = Modifier.width(Spacing.s))
                                            Text(
                                                text = highlightText(merchant.displayName, uiState.query, highlightColor),
                                                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold)
                                            )
                                        }
                                    }
                                }
                            }

                            if (results.categories.isNotEmpty()) {
                                stickyHeader(key = "header_categories") {
                                    Box(modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background)) {
                                        GroupedSectionHeader(title = "Categories")
                                    }
                                }
                                items(results.categories) { category ->
                                    BaseCard(
                                        modifier = Modifier.fillMaxWidth(),
                                        onClick = {},
                                        contentPadding = Spacing.m
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(category.icon ?: "📁", fontSize = 20.sp)
                                            Spacer(modifier = Modifier.width(Spacing.s))
                                            Text(
                                                text = highlightText(category.name, uiState.query, highlightColor),
                                                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold)
                                            )
                                        }
                                    }
                                }
                            }

                            if (results.transactions.isNotEmpty()) {
                                stickyHeader(key = "header_expenses") {
                                    Box(modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background)) {
                                        GroupedSectionHeader(title = "Expenses")
                                    }
                                }
                                items(results.transactions, key = { it.id }) { tx ->
                                    val catIcon = when (tx.category.lowercase()) {
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
                                        modifier = Modifier.fillMaxWidth(),
                                        onClick = { onNavigateToTransactionDetail(tx.id) },
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
                                                Box(
                                                    modifier = Modifier
                                                        .size(42.dp)
                                                        .clip(RoundedCornerShape(CornerRadius.s))
                                                        .background(MaterialTheme.colorScheme.surfaceVariant),
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    Text(text = catIcon, style = MaterialTheme.typography.titleMedium)
                                                }
                                                Spacer(modifier = Modifier.width(Spacing.s))
                                                Column {
                                                    Text(
                                                        text = highlightText(tx.merchant, uiState.query, highlightColor),
                                                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                                                        maxLines = 1
                                                    )
                                                    if (!tx.notes.isNullOrEmpty()) {
                                                        Text(
                                                            text = highlightText(tx.notes ?: "", uiState.query, highlightColor),
                                                            style = MaterialTheme.typography.bodyMedium,
                                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                                            maxLines = 1
                                                        )
                                                    }
                                                }
                                            }
                                            val isCredit = tx.amount < 0
                                            val amountText = if (isCredit) {
                                                "+" + CurrencyUtils.formatCents(-tx.amount)
                                            } else {
                                                "-" + CurrencyUtils.formatCents(tx.amount)
                                            }
                                            val amountColor = if (isCredit) {
                                                Color(0xFF2E7D32) // Forest Green for Inflow
                                            } else {
                                                MaterialTheme.colorScheme.primary // Outflow
                                            }
                                            Text(
                                                text = amountText,
                                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                                color = amountColor
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

private fun highlightText(text: String, query: String, highlightColor: Color): AnnotatedString {
    if (query.trim().isEmpty()) return AnnotatedString(text)
    val trimmedQuery = query.trim()
    return buildAnnotatedString {
        var startIdx = 0
        while (true) {
            val idx = text.indexOf(trimmedQuery, startIdx, ignoreCase = true)
            if (idx == -1) {
                append(text.substring(startIdx))
                break
            }
            append(text.substring(startIdx, idx))
            pushStyle(SpanStyle(background = highlightColor, fontWeight = FontWeight.Bold))
            append(text.substring(idx, idx + trimmedQuery.length))
            pop()
            startIdx = idx + trimmedQuery.length
        }
    }
}
