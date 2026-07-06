package com.ledgerflow.presentation.features.search

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import com.ledgerflow.domain.model.Category
import com.ledgerflow.domain.model.Merchant

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun GlobalSearchScreen(
    onNavigateBack: () -> Unit,
    onNavigateToTransactionDetail: (Long) -> Unit,
    viewModel: GlobalSearchViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val highlightColor = MaterialTheme.colorScheme.primaryContainer

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Navigate Back",
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
                                contentDescription = "Clear Input",
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
                .padding(horizontal = 16.dp)
        ) {
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
                                title = "Unified Global Search",
                                description = "Type to search across transactions, categories, payees, and notes.",
                                iconEmoji = "🔎"
                            )
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            if (uiState.savedSearches.isNotEmpty()) {
                                item {
                                    GroupedSectionHeader(title = "Saved Searches")
                                }
                                items(uiState.savedSearches) { saved ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(8.dp))
                                            .clickable { viewModel.updateQuery(saved) }
                                            .padding(vertical = 8.dp, horizontal = 4.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(
                                                imageVector = Icons.Filled.Star,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(20.dp)
                                            )
                                            Spacer(modifier = Modifier.width(12.dp))
                                            Text(
                                                text = saved,
                                                style = MaterialTheme.typography.bodyLarge
                                            )
                                        }
                                        IconButton(onClick = { viewModel.deleteSavedSearch(saved) }) {
                                            Icon(
                                                imageVector = Icons.Filled.Delete,
                                                contentDescription = "Delete Saved Search",
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
                                        GroupedSectionHeader(title = "Recent Searches")
                                        TextButton(onClick = { viewModel.clearSearchHistory() }) {
                                            Text("Clear All", color = MaterialTheme.colorScheme.primary)
                                        }
                                    }
                                }
                                items(uiState.recentSearches) { recent ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { viewModel.updateQuery(recent) }
                                            .padding(vertical = 12.dp, horizontal = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "🕒",
                                            fontSize = 16.sp
                                        )
                                        Spacer(modifier = Modifier.width(12.dp))
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
                val results = uiState.searchResults
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
                            description = "Refine search keywords or try different payees.",
                            iconEmoji = "🔍"
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            if (results.merchants.isNotEmpty()) {
                                stickyHeader(key = "header_merchants") {
                                    Box(modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background)) {
                                        GroupedSectionHeader(title = "Payees / Merchants")
                                    }
                                }
                                items(results.merchants) { merchant ->
                                    BaseCard(
                                        modifier = Modifier.fillMaxWidth(),
                                        onClick = {},
                                        contentPadding = 12.dp
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text("🏪", fontSize = 20.sp)
                                            Spacer(modifier = Modifier.width(12.dp))
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
                                        contentPadding = 12.dp
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(category.icon ?: "📁", fontSize = 20.sp)
                                            Spacer(modifier = Modifier.width(12.dp))
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
                                items(results.transactions) { tx ->
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
                                                    Text(text = catIcon, style = MaterialTheme.typography.titleMedium)
                                                }
                                                Spacer(modifier = Modifier.width(12.dp))
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
                                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                            maxLines = 1
                                                        )
                                                    }
                                                }
                                            }
                                            Text(
                                                text = CurrencyUtils.formatCents(tx.amount),
                                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                                color = MaterialTheme.colorScheme.primary
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
            pushStyle(SpanStyle(background = highlightColor))
            append(text.substring(idx, idx + trimmedQuery.length))
            pop()
            startIdx = idx + trimmedQuery.length
        }
    }
}
