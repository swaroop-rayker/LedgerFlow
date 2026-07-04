package com.ledgerflow.presentation.features.transactions

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.ledgerflow.core.common.util.CurrencyUtils
import com.ledgerflow.core.ui.components.BaseCard
import com.ledgerflow.domain.model.TransactionType

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransactionDetailScreen(
    transactionId: Long,
    onNavigateBack: () -> Unit,
    viewModel: TransactionDetailViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    // Observe save success to navigate back
    LaunchedEffect(uiState.isSaved) {
        if (uiState.isSaved) {
            onNavigateBack()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    Text(
                        if (transactionId == 0L) "New Transaction" else "Edit Transaction",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
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
            // Spacer for layout rhythm
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

            // Transaction Type Selector (Segmented Chips style)
            item {
                BaseCard(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        "Transaction Type",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        TransactionType.entries.forEach { type ->
                            FilterChip(
                                selected = uiState.type == type,
                                onClick = { viewModel.onTypeChanged(type) },
                                label = { Text(type.name) },
                                shape = RoundedCornerShape(20.dp),
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                                    selectedLabelColor = MaterialTheme.colorScheme.primary
                                )
                            )
                        }
                    }
                }
            }

            // Large Premium Amount Input Card
            item {
                BaseCard(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "Amount",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        var amountText by remember { 
                            mutableStateOf(if (uiState.totalAmount == 0.0) "" else uiState.totalAmount.toString()) 
                        }
                        
                        TextField(
                            value = amountText,
                            onValueChange = {
                                amountText = it
                                val amount = it.toDoubleOrNull() ?: 0.0
                                viewModel.onTotalAmountChanged(amount)
                            },
                            textStyle = MaterialTheme.typography.displayMedium.copy(
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center
                            ),
                            placeholder = {
                                Text(
                                    "0.00", 
                                    style = MaterialTheme.typography.displayMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                                unfocusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                                disabledContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                                focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                                unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }

            // Merchant Card
            item {
                BaseCard(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        "Merchant Selector",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    if (uiState.merchants.isEmpty()) {
                        Text(
                            "No merchants configured.", 
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            uiState.merchants.forEach { merchant ->
                                FilterChip(
                                    selected = uiState.selectedMerchantId == merchant.id,
                                    onClick = { viewModel.onMerchantSelected(merchant.id) },
                                    label = { Text(merchant.displayName) },
                                    shape = RoundedCornerShape(8.dp)
                                )
                            }
                        }
                    }
                }
            }

            // Payment Method Card
            item {
                BaseCard(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        "Payment Channel",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    if (uiState.paymentMethods.isEmpty()) {
                        Text(
                            "No payment methods configured.", 
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            uiState.paymentMethods.forEach { method ->
                                FilterChip(
                                    selected = uiState.selectedPaymentMethodId == method.id,
                                    onClick = { viewModel.onPaymentMethodSelected(method.id) },
                                    label = { Text(method.name) },
                                    shape = RoundedCornerShape(8.dp)
                                )
                            }
                        }
                    }
                }
            }

            // Splits Allocation Header & Status Box
            item {
                val totalSplitSum = uiState.splits.sumOf { it.amount }
                val remaining = uiState.totalAmount - totalSplitSum
                val isSplitMatched = remaining == 0.0

                BaseCard(
                    modifier = Modifier.fillMaxWidth(),
                    containerColor = if (isSplitMatched) {
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.05f)
                    } else {
                        MaterialTheme.colorScheme.error.copy(alpha = 0.05f)
                    }
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                "Split Allocation", 
                                style = MaterialTheme.typography.titleMedium, 
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = if (isSplitMatched) "Allocations match total amount" else "Remaining unallocated: $remaining",
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

            // Split list items
            itemsIndexed(uiState.splits) { index, split ->
                val categoryName = uiState.categories.find { it.id == split.categoryId }?.name ?: "Unknown"
                BaseCard(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(categoryName, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall)
                            Text("Amount: ${split.amount}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(
                            onClick = { viewModel.removeSplit(index) },
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
                    var selectedCatId by remember { mutableStateOf(uiState.categories.first().id) }
                    var splitAmountString by remember { mutableStateOf("") }
                    var expandedDropdown by remember { mutableStateOf(false) }

                    BaseCard(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            "Add Split Branch",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Dropdown Selector for Split Category
                            Box(modifier = Modifier.weight(1.2f)) {
                                val currentCatName = uiState.categories.find { it.id == selectedCatId }?.name ?: "Select"
                                OutlinedButton(
                                    onClick = { expandedDropdown = true },
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text(currentCatName, style = MaterialTheme.typography.bodyMedium)
                                }
                                DropdownMenu(
                                    expanded = expandedDropdown,
                                    onDismissRequest = { expandedDropdown = false }
                                ) {
                                    uiState.categories.forEach { cat ->
                                        DropdownMenuItem(
                                            text = { Text(cat.name) },
                                            onClick = {
                                                selectedCatId = cat.id
                                                expandedDropdown = false
                                            }
                                        )
                                    }
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
                            
                            Button(
                                onClick = {
                                    val amount = splitAmountString.toDoubleOrNull() ?: 0.0
                                    if (amount > 0) {
                                        viewModel.addSplit(selectedCatId, amount)
                                        splitAmountString = ""
                                    }
                                },
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text("Add")
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
                            "Additional Information",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        
                        OutlinedTextField(
                            value = uiState.tagsString,
                            onValueChange = { viewModel.onTagsChanged(it) },
                            label = { Text("Tags (comma separated)") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            shape = RoundedCornerShape(8.dp)
                        )

                        OutlinedTextField(
                            value = uiState.notes,
                            onValueChange = { viewModel.onNotesChanged(it) },
                            label = { Text("Transaction Notes") },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 3,
                            shape = RoundedCornerShape(8.dp)
                        )
                    }
                }
            }

            // Save Action Floating Bottom Panel
            item {
                Button(
                    onClick = { viewModel.saveTransaction() },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp)
                ) {
                    Text(
                        "Save Transaction Details", 
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // End layout padding spacer
            item { Spacer(modifier = Modifier.height(24.dp)) }
        }
    }
}
