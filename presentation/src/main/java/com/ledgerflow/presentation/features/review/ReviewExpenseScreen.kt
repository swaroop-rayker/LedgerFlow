package com.ledgerflow.presentation.features.review

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.ledgerflow.core.common.util.CurrencyUtils
import com.ledgerflow.core.ui.components.BaseCard
import com.ledgerflow.core.ui.components.ConfirmationDialog
import com.ledgerflow.core.ui.components.PremiumButton
import com.ledgerflow.core.ui.components.PremiumTextField
import com.ledgerflow.presentation.components.CategorySelector
import com.ledgerflow.presentation.components.SubcategorySelector
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewExpenseScreen(
    pendingId: Long,
    onNavigateBack: () -> Unit,
    viewModel: ReviewExpenseViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val categories by viewModel.categories.collectAsState()

    LaunchedEffect(pendingId) {
        viewModel.loadPendingTransaction(pendingId)
    }

    LaunchedEffect(uiState.isApproved, uiState.isDiscarded, uiState.isPostponed) {
        if (uiState.isApproved || uiState.isDiscarded || uiState.isPostponed) {
            onNavigateBack()
        }
    }

    val selectedCategoryTree = remember(categories, uiState.category) {
        categories.find { it.category.name.equals(uiState.category, ignoreCase = true) }
    }
    val activeSubcategories = selectedCategoryTree?.subcategories ?: emptyList()

    if (uiState.showDuplicateDialog) {
        AlertDialog(
            onDismissRequest = { viewModel.hideDuplicateDialog() },
            title = { Text("Duplicate Detected") },
            text = { Text("This expense may already exist in your records.") },
            confirmButton = {
                Button(
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    onClick = {
                        viewModel.hideDuplicateDialog()
                        viewModel.approveTransaction(force = true)
                    }
                ) {
                    Text("Approve Anyway")
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = {
                        viewModel.hideDuplicateDialog()
                        viewModel.discardTransaction()
                    }
                ) {
                    Text("Discard")
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    Text(
                        text = "Review Expense Draft",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    ) 
                },
                navigationIcon = {
                    IconButton(onClick = { viewModel.postponeReview() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { innerPadding ->
        if (uiState.isLoading && uiState.pendingTransaction == null) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(strokeWidth = 3.dp)
            }
        } else if (uiState.errorMessage != null && uiState.pendingTransaction == null) {
            Box(modifier = Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(text = uiState.errorMessage ?: "Failed to load draft.")
                    Button(onClick = { viewModel.loadPendingTransaction(pendingId) }) {
                        Text("Retry")
                    }
                }
            }
        } else {
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
                    val confidenceText = when {
                        uiState.confidence >= 80 -> "High Confidence"
                        uiState.confidence >= 50 -> "Medium Confidence"
                        else -> "Low Confidence"
                    }
                    val confidenceColor = when {
                        uiState.confidence >= 80 -> Color(0xFF4CAF50)
                        uiState.confidence >= 50 -> Color(0xFFFF9800)
                        else -> Color(0xFFF44336)
                    }
                    val confidenceBg = confidenceColor.copy(alpha = 0.12f)

                    Card(
                        colors = CardDefaults.cardColors(containerColor = confidenceBg),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = "Parser Confidence Score",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Card(
                                    colors = CardDefaults.cardColors(containerColor = confidenceColor),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text(
                                        text = "$confidenceText (${uiState.confidence}%)",
                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                        color = Color.White,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }
                            }
                            
                            if (uiState.confidence < 80) {
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    text = "Review suggested fields carefully:",
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                if (uiState.merchant.contains("Unknown", ignoreCase = true) || uiState.merchant.contains("Bank", ignoreCase = true)) {
                                    Text(
                                        text = "⚠️ Merchant name may be generic or uncertain.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color(0xFFFF9800)
                                    )
                                }
                                if (uiState.category == "Others") {
                                    Text(
                                        text = "⚠️ Suggested Category defaulted to Others (guessed).",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color(0xFFFF9800)
                                    )
                                }
                            }
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
                                text = "DETAILED AMOUNT",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    letterSpacing = 1.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = CurrencyUtils.formatCents((uiState.amount * 100).toLong()),
                                style = MaterialTheme.typography.displayMedium.copy(fontWeight = FontWeight.ExtraBold),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }

                item {
                    PremiumTextField(
                        value = uiState.merchant,
                        onValueChange = { viewModel.onMerchantChanged(it) },
                        label = "Merchant Name",
                        placeholder = "Who is the payee?"
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
                            categories = categories,
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
                        label = "Reference Number (optional)",
                        placeholder = "Txn ID, Check number, etc."
                    )
                }

                item {
                    PremiumTextField(
                        value = uiState.notes,
                        onValueChange = { viewModel.onNotesChanged(it) },
                        label = "Notes (optional)",
                        placeholder = "Add remarks..."
                    )
                }

                item {
                    val dateFormatted = remember(uiState.pendingTransaction?.timestamp) {
                        val timestamp = uiState.pendingTransaction?.timestamp ?: System.currentTimeMillis()
                        SimpleDateFormat("MMMM dd, yyyy 'at' hh:mm a", Locale.getDefault()).format(Date(timestamp))
                    }
                    Text(
                        text = "Parsed Date: $dateFormatted",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center
                    )
                }

                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { viewModel.postponeReview() },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Review Later")
                        }
                        
                        OutlinedButton(
                            onClick = { viewModel.discardTransaction() },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Discard")
                        }
                    }
                }

                item {
                    PremiumButton(
                        text = "Approve & Log",
                        onClick = { viewModel.approveTransaction(force = false) },
                        enabled = uiState.merchant.isNotBlank() && !uiState.isLoading,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                item { Spacer(modifier = Modifier.height(32.dp)) }
            }
        }
    }
}
