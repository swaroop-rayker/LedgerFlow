package com.ledgerflow.presentation.features.transactions

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Done
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.hilt.navigation.compose.hiltViewModel
import com.ledgerflow.core.ui.components.BaseCard
import com.ledgerflow.core.ui.components.PremiumTextField
import com.ledgerflow.presentation.components.CategorySelector
import com.ledgerflow.presentation.components.SubcategorySelector

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransactionDetailScreen(
    transactionId: Long,
    onNavigateBack: () -> Unit,
    viewModel: TransactionDetailViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val categoriesState by viewModel.categories.collectAsState()
    val keyboardController = LocalSoftwareKeyboardController.current

    var amountText by remember { mutableStateOf("") }

    LaunchedEffect(uiState.isSaved) {
        if (uiState.isSaved) {
            onNavigateBack()
        }
    }

    val selectedCategoryTree = remember(categoriesState, uiState.category) {
        categoriesState.find { it.category.name.equals(uiState.category, ignoreCase = true) }
    }
    val activeSubcategories = selectedCategoryTree?.subcategories ?: emptyList()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    Text(
                        text = "Add Expense",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    ) 
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(
                        onClick = { viewModel.saveTransaction() },
                        enabled = uiState.amount > 0.0 && uiState.merchant.isNotBlank()
                    ) {
                        Icon(
                            imageVector = Icons.Default.Done, 
                            contentDescription = "Save",
                            tint = if (uiState.amount > 0.0 && uiState.merchant.isNotBlank()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
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
                                amountText = it
                                val amount = it.toDoubleOrNull() ?: 0.0
                                viewModel.onAmountChanged(amount)
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
                                    textAlign = TextAlign.Center
                                )
                            },
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                disabledContainerColor = Color.Transparent,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent
                            ),
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Decimal,
                                imeAction = ImeAction.Next
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }

            item {
                PremiumTextField(
                    value = uiState.merchant,
                    onValueChange = { viewModel.onMerchantChanged(it) },
                    label = "Merchant",
                    placeholder = "Where did you spend?",
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth()
                )
            }

            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Category (Mandatory)",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    CategorySelector(
                        categories = categoriesState,
                        selectedCategoryName = uiState.category,
                        onCategorySelected = { viewModel.onCategoryChanged(it) }
                    )
                }
            }

            if (activeSubcategories.isNotEmpty()) {
                item {
                    SubcategorySelector(
                        subcategories = activeSubcategories,
                        selectedSubcategoryName = uiState.subcategory,
                        onSubcategorySelected = { viewModel.onSubcategoryChanged(it) }
                    )
                }
            }

            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Payment Method",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf("UPI / Bank", "Credit Card", "Cash").forEach { method ->
                            val isSelected = uiState.paymentMethod == method
                            FilterChip(
                                selected = isSelected,
                                onClick = { viewModel.onPaymentMethodChanged(method) },
                                label = {
                                    Text(
                                        text = method,
                                        style = MaterialTheme.typography.bodyMedium.copy(
                                            letterSpacing = 0.sp,
                                            fontWeight = FontWeight.Medium
                                        )
                                    )
                                }
                            )
                        }
                    }
                }
            }

            item {
                PremiumTextField(
                    value = uiState.reference,
                    onValueChange = { viewModel.onReferenceChanged(it) },
                    label = "Reference (optional)",
                    placeholder = "Txn ID, Check number, etc.",
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth()
                )
            }

            item {
                PremiumTextField(
                    value = uiState.notes,
                    onValueChange = { viewModel.onNotesChanged(it) },
                    label = "Notes (optional)",
                    placeholder = "Add remarks...",
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth()
                )
            }

            item { Spacer(modifier = Modifier.height(32.dp)) }
        }
    }
}
