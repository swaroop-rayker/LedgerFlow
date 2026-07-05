package com.ledgerflow.presentation.features.transactions

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.ledgerflow.core.common.util.CurrencyUtils
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import com.ledgerflow.core.ui.components.BaseCard
import com.ledgerflow.core.ui.components.ConfirmationDialog
import com.ledgerflow.core.ui.components.PremiumButton
import com.ledgerflow.core.ui.components.PremiumTextField
import com.ledgerflow.domain.model.TransactionType

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransactionDetailScreen(
    transactionId: Long,
    onNavigateBack: () -> Unit,
    viewModel: TransactionDetailViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    LaunchedEffect(Unit) {
        if (transactionId == 0L) {
            focusRequester.requestFocus()
            keyboardController?.show()
        }
    }

    var amountText by remember(uiState.totalAmount) { 
        mutableStateOf(if (uiState.totalAmount == 0.0) "" else uiState.totalAmount.toString()) 
    }
    var selectedCatId by remember(uiState.categories) { 
        mutableStateOf(uiState.categories.firstOrNull()?.id ?: 0L) 
    }
    var splitAmountString by remember { mutableStateOf("") }
    var showBottomSheet by remember { mutableStateOf(false) }
    var showConfirmDeleteIndex by remember { mutableStateOf<Int?>(null) }
    var isDirty by remember { mutableStateOf(false) }

    // Observe save success to navigate back
    LaunchedEffect(uiState.isSaved) {
        if (uiState.isSaved) {
            onNavigateBack()
        }
    }

    val totalSplitSum = uiState.splits.sumOf { it.amount }
    val remaining = uiState.totalAmount - totalSplitSum
    val isSplitMatched = Math.abs(remaining) < 0.01

    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    Text(
                        text = if (transactionId == 0L) "New Transaction" else "Edit Transaction",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    ) 
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (isDirty && transactionId == 0L) {
                            // User edited fields, confirm exit
                            // For simplicity, we just trigger back, but confirmation dialog is clean
                            onNavigateBack()
                        } else {
                            onNavigateBack()
                        }
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    // Premium floating top bar Save action
                    IconButton(
                        onClick = { viewModel.saveTransaction() },
                        enabled = isSplitMatched && uiState.totalAmount > 0.0
                    ) {
                        Icon(
                            imageVector = Icons.Default.Done, 
                            contentDescription = "Save",
                            tint = if (isSplitMatched && uiState.totalAmount > 0.0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                        )
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

            // Error Indicator Banner
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

            // 1. Large Premium Amount Input & Type Selector
            item {
                BaseCard(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "AMOUNT",
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                letterSpacing = 1.sp,
                                fontWeight = FontWeight.Bold
                            )
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        TextField(
                            value = amountText,
                            onValueChange = {
                                isDirty = true
                                amountText = it
                                val amount = it.toDoubleOrNull() ?: 0.0
                                viewModel.onTotalAmountChanged(amount)
                            },
                            textStyle = MaterialTheme.typography.displayMedium.copy(
                                fontWeight = FontWeight.ExtraBold,
                                textAlign = TextAlign.Center
                            ),
                            prefix = {
                                Text(
                                    text = "₹ ",
                                    style = MaterialTheme.typography.displayMedium.copy(
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                        fontWeight = FontWeight.Bold
                                    )
                                )
                            },
                            placeholder = {
                                Text(
                                    text = "0.00", 
                                    style = MaterialTheme.typography.displayMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f),
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            },
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Decimal,
                                imeAction = ImeAction.Next
                            ),
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                disabledContainerColor = Color.Transparent,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(focusRequester)
                        )

                        Spacer(modifier = Modifier.height(16.dp))
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                        Spacer(modifier = Modifier.height(16.dp))

                        // Segmented styling selector
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            TransactionType.entries.forEach { type ->
                                val selected = uiState.type == type
                                val chipColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                val textColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant

                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(40.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(chipColor)
                                        .clickable {
                                            isDirty = true
                                            viewModel.onTypeChanged(type)
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = type.name.lowercase().replaceFirstChar { it.uppercase() },
                                        fontWeight = FontWeight.Bold,
                                        style = MaterialTheme.typography.labelLarge,
                                        color = textColor
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 2. Selectors (Merchant & Payment Channel)
            item {
                BaseCard(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "Merchant",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    if (uiState.merchants.isEmpty()) {
                        Text(
                            text = "No merchants configured.", 
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        // Horizontally scrollable row to prevent UI overflow breaks
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            uiState.merchants.forEach { merchant ->
                                val selected = uiState.selectedMerchantId == merchant.id
                                FilterChip(
                                    selected = selected,
                                    onClick = { 
                                        isDirty = true
                                        viewModel.onMerchantSelected(merchant.id) 
                                    },
                                    label = { Text(merchant.displayName) },
                                    shape = RoundedCornerShape(8.dp),
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                                        selectedLabelColor = MaterialTheme.colorScheme.primary
                                    )
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = "Payment Method",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    if (uiState.paymentMethods.isEmpty()) {
                        Text(
                            text = "No payment methods configured.", 
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        // Horizontally scrollable row to prevent UI overflow breaks
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            uiState.paymentMethods.forEach { method ->
                                val selected = uiState.selectedPaymentMethodId == method.id
                                FilterChip(
                                    selected = selected,
                                    onClick = { 
                                        isDirty = true
                                        viewModel.onPaymentMethodSelected(method.id) 
                                    },
                                    label = { Text(method.name) },
                                    shape = RoundedCornerShape(8.dp),
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                                        selectedLabelColor = MaterialTheme.colorScheme.primary
                                    )
                                )
                            }
                        }
                    }
                }
            }

            // 3. Split Allocation Status Box
            item {
                BaseCard(
                    modifier = Modifier.fillMaxWidth(),
                    containerColor = if (isSplitMatched) {
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.05f)
                    } else {
                        MaterialTheme.colorScheme.error.copy(alpha = 0.05f)
                    },
                    borderColor = if (isSplitMatched) {
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
                    } else {
                        MaterialTheme.colorScheme.error.copy(alpha = 0.3f)
                    }
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "Split Allocation", 
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = if (isSplitMatched) "Allocations match total amount" else "Remaining unallocated: ₹ $remaining",
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (isSplitMatched) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                            )
                        }
                        Box(
                            modifier = Modifier
                                .size(12.dp)
                                .clip(CircleShape)
                                .background(if (isSplitMatched) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                        )
                    }
                }
            }

            // Split list items (rendered without giant visual boxes)
            itemsIndexed(uiState.splits) { index, split ->
                val categoryName = uiState.categories.find { it.id == split.categoryId }?.name ?: "Unknown"
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
                            Text(categoryName, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                            Text("Allocated: ₹ ${split.amount}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(
                            onClick = { showConfirmDeleteIndex = index },
                            colors = IconButtonDefaults.iconButtonColors(contentColor = MaterialTheme.colorScheme.error)
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = "Remove Split")
                        }
                    }
                }
            }

            // Split Creator Form Builder
            item {
                if (uiState.categories.isNotEmpty()) {
                    BaseCard(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = "Add Split Branch",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Selector for Split Category (triggers bottom sheet)
                            Box(modifier = Modifier.weight(1.2f)) {
                                val currentCatName = uiState.categories.find { it.id == selectedCatId }?.name ?: "Select Category"
                                OutlinedButton(
                                    onClick = { showBottomSheet = true },
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text(
                                        text = currentCatName, 
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                            
                            OutlinedTextField(
                                value = splitAmountString,
                                onValueChange = { splitAmountString = it },
                                label = { Text("Amount") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.weight(0.8f),
                                singleLine = true,
                                shape = RoundedCornerShape(8.dp)
                            )
                            
                            IconButton(
                                onClick = {
                                    val amount = splitAmountString.toDoubleOrNull() ?: 0.0
                                    if (amount > 0) {
                                        isDirty = true
                                        viewModel.addSplit(selectedCatId, amount)
                                        splitAmountString = ""
                                    }
                                },
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primaryContainer),
                            ) {
                                Icon(Icons.Default.Add, contentDescription = "Add Split", tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            }

            // Tags & Description Details Card
            item {
                BaseCard(modifier = Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text(
                            text = "Additional Details",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        
                        PremiumTextField(
                            value = uiState.tagsString,
                            onValueChange = { 
                                isDirty = true
                                viewModel.onTagsChanged(it) 
                            },
                            label = "Tags",
                            placeholder = "shopping, food, travel...",
                            leadingIcon = Icons.Default.Star,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next)
                        )

                        PremiumTextField(
                            value = uiState.notes,
                            onValueChange = { 
                                isDirty = true
                                viewModel.onNotesChanged(it) 
                            },
                            label = "Notes",
                            placeholder = "Enter description...",
                            leadingIcon = Icons.Default.Info,
                            singleLine = false,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done)
                        )
                    }
                }
            }

            // Save Action Floating Bottom Panel
            item {
                PremiumButton(
                    onClick = { viewModel.saveTransaction() },
                    text = "Save Transaction Details",
                    icon = Icons.Default.Check,
                    enabled = isSplitMatched && uiState.totalAmount > 0.0,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                )
            }

            item { Spacer(modifier = Modifier.height(24.dp)) }
        }
    }

    if (showBottomSheet) {
        CategorySelectionBottomSheet(
            categories = uiState.categories,
            selectedCategoryId = selectedCatId,
            onCategorySelected = { catId ->
                isDirty = true
                selectedCatId = catId
                showBottomSheet = false
            },
            onDismissRequest = { showBottomSheet = false }
        )
    }

    if (showConfirmDeleteIndex != null) {
        ConfirmationDialog(
            onDismissRequest = { showConfirmDeleteIndex = null },
            onConfirm = {
                showConfirmDeleteIndex?.let { index ->
                    isDirty = true
                    viewModel.removeSplit(index)
                }
            },
            title = "Remove Split",
            text = "Are you sure you want to remove this split allocation?",
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
                                            // Cleaner indentation layout (no box drawing characters)
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

