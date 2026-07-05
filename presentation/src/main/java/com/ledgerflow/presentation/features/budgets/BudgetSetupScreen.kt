package com.ledgerflow.presentation.features.budgets

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.ledgerflow.core.common.util.CurrencyUtils
import com.ledgerflow.core.ui.components.BaseCard
import com.ledgerflow.core.ui.components.ConfirmationDialog
import com.ledgerflow.core.ui.components.EmptyStateView
import com.ledgerflow.core.ui.components.GroupedSectionHeader
import com.ledgerflow.core.ui.components.PremiumButton
import com.ledgerflow.core.ui.components.PremiumTextField
import com.ledgerflow.core.ui.theme.SuccessColor

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BudgetSetupScreen(
    onNavigateBack: () -> Unit,
    viewModel: BudgetSetupViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    var selectedCategoryId by remember { mutableStateOf(0L) }
    var budgetAmountString by remember { mutableStateOf("") }
    var showBottomSheet by remember { mutableStateOf(false) }
    var budgetToDeleteId by remember { mutableStateOf<Long?>(null) }

    LaunchedEffect(uiState.isOperationSuccess) {
        if (uiState.isOperationSuccess) {
            budgetAmountString = ""
            selectedCategoryId = 0L
            viewModel.resetOperationSuccess()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    Text(
                        text = "Monthly Budgets", 
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    ) 
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item { Spacer(modifier = Modifier.height(2.dp)) }

            // Error display banner
            uiState.errorMessage?.let { error ->
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = error,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(16.dp),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }

            // Card: Create / Update Budget Form
            item {
                BaseCard(modifier = Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Set Spending Limit",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                        )
                    }
                    Spacer(modifier = Modifier.height(16.dp))

                    if (uiState.categories.isEmpty()) {
                        Text(
                            text = "Please configure some categories first before creating budgets.", 
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        if (selectedCategoryId == 0L) {
                            selectedCategoryId = uiState.categories.first().id
                        }

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            // Category Selection Button (triggers bottom sheet)
                            Column(modifier = Modifier.weight(1.2f)) {
                                Text(
                                    text = "Category",
                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(bottom = 6.dp)
                                )
                                val currentCatName = uiState.categories.find { it.id == selectedCategoryId }?.name ?: "Select Category"
                                OutlinedButton(
                                    onClick = { showBottomSheet = true },
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(10.dp),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = currentCatName, 
                                            style = MaterialTheme.typography.bodyMedium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f)
                                        )
                                        Icon(Icons.Default.KeyboardArrowDown, contentDescription = null)
                                    }
                                }
                            }

                            // Amount Input (Converts Double input into Cents under the hood)
                            PremiumTextField(
                                value = budgetAmountString,
                                onValueChange = { budgetAmountString = it },
                                label = "Limit",
                                placeholder = "0.00",
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Decimal,
                                    imeAction = ImeAction.Done
                                ),
                                modifier = Modifier.weight(0.8f),
                                prefix = { Text("₹ ", fontWeight = FontWeight.Bold) }
                            )
                        }
                        
                        Spacer(modifier = Modifier.height(16.dp))

                        val limitDouble = budgetAmountString.toDoubleOrNull() ?: 0.0
                        val isValidAmount = limitDouble > 0.0

                        PremiumButton(
                            onClick = {
                                if (isValidAmount) {
                                    val amountCents = CurrencyUtils.doubleToCents(limitDouble)
                                    viewModel.setBudget(selectedCategoryId, amountCents)
                                }
                            },
                            text = "Establish Limit",
                            icon = Icons.Default.Done,
                            enabled = isValidAmount,
                            modifier = Modifier.align(Alignment.End)
                        )
                    }
                }
            }

            // Section Header: Active Budgets
            item {
                GroupedSectionHeader(title = "Configured Budgets")
            }

            if (uiState.budgets.isEmpty()) {
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
                                text = "No active budgets configured.", 
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            } else {
                items(uiState.budgets) { budget ->
                    val categoryName = uiState.categories.find { it.id == budget.categoryId }?.name ?: "Category #${budget.categoryId}"
                    BaseCard(
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = 12.dp
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = categoryName,
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.bodyLarge
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "Monthly limit: ${CurrencyUtils.formatCents(budget.amount)}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            IconButton(
                                onClick = { budgetToDeleteId = budget.categoryId },
                                colors = IconButtonDefaults.iconButtonColors(contentColor = MaterialTheme.colorScheme.error)
                            ) {
                                Icon(Icons.Default.Delete, contentDescription = "Delete Budget")
                            }
                        }
                    }
                }
            }

            item { Spacer(modifier = Modifier.height(24.dp)) }
        }
    }

    if (showBottomSheet) {
        CategorySelectionBottomSheet(
            categories = uiState.categories,
            selectedCategoryId = selectedCategoryId,
            onCategorySelected = { catId ->
                selectedCategoryId = catId
                showBottomSheet = false
            },
            onDismissRequest = { showBottomSheet = false }
        )
    }

    if (budgetToDeleteId != null) {
        ConfirmationDialog(
            onDismissRequest = { budgetToDeleteId = null },
            onConfirm = {
                budgetToDeleteId?.let { id ->
                    viewModel.deleteBudget(id)
                }
            },
            title = "Delete Budget Limit",
            text = "Are you sure you want to remove this category budget limit?",
            confirmText = "Delete",
            isDestructive = true
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategorySelectionBottomSheet(
    categories: List<com.ledgerflow.domain.model.Category>,
    selectedCategoryId: Long,
    onCategorySelected: (Long) -> Unit,
    onDismissRequest: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        dragHandle = { BottomSheetDefaults.DragHandle() },
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp)
        ) {
            Text(
                text = "Select Category",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                modifier = Modifier.padding(bottom = 16.dp)
            )

            // Search Bar
            var searchQuery by remember { mutableStateOf("") }
            PremiumTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                label = "",
                placeholder = "Search categories..."
            )
            Spacer(modifier = Modifier.height(12.dp))

            // Tree list of categories
            val filteredCategories = categories.filter { 
                it.name.contains(searchQuery, ignoreCase = true) 
            }

            var expandedParentIds by remember { mutableStateOf(emptySet<Long>()) }
            val parentCategories = filteredCategories.filter { it.parentId == null }

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                parentCategories.forEach { parent ->
                    val children = filteredCategories.filter { it.parentId == parent.id }
                    val hasChildren = children.isNotEmpty()
                    val isExpanded = expandedParentIds.contains(parent.id)
                    val isSelected = selectedCategoryId == parent.id

                    item(key = parent.id) {
                        val rotationState by animateFloatAsState(
                            targetValue = if (isExpanded) 180f else 0f,
                            label = "ChevronRotation"
                        )
                        Surface(
                            onClick = {
                                if (hasChildren) {
                                    expandedParentIds = if (isExpanded) {
                                        expandedParentIds - parent.id
                                    } else {
                                        expandedParentIds + parent.id
                                    }
                                } else {
                                    onCategorySelected(parent.id)
                                }
                            },
                            shape = RoundedCornerShape(12.dp),
                            color = if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else Color.Transparent,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 12.dp, horizontal = 16.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = "${parent.icon ?: "📁"}  ${parent.name}",
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                    )
                                }
                                if (hasChildren) {
                                    Icon(
                                        imageVector = Icons.Default.KeyboardArrowDown,
                                        contentDescription = "Expand",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.rotate(rotationState)
                                    )
                                }
                            }
                        }
                    }

                    if (isExpanded && hasChildren) {
                        children.forEach { child ->
                            val isChildSelected = selectedCategoryId == child.id
                            item(key = child.id) {
                                Surface(
                                    onClick = { onCategorySelected(child.id) },
                                    shape = RoundedCornerShape(12.dp),
                                    color = if (isChildSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else Color.Transparent,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(start = 24.dp)
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 10.dp, horizontal = 12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "    ${child.icon ?: "📄"}  ${child.name}",
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = if (isChildSelected) FontWeight.Bold else FontWeight.Normal,
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
    }
}

