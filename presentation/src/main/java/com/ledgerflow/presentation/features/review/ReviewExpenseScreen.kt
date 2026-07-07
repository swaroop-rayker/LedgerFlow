package com.ledgerflow.presentation.features.review

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import com.ledgerflow.presentation.components.CategoryPickerBottomSheet
import com.ledgerflow.core.ui.theme.Spacing
import com.ledgerflow.core.ui.theme.CornerRadius
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
    var showCategoryPicker by remember { mutableStateOf(false) }

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
    val categoryIcon = selectedCategoryTree?.category?.icon ?: "📁"

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Review Draft",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
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
        ) {
            if (uiState.isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(strokeWidth = 3.dp, color = MaterialTheme.colorScheme.primary)
                }
            } else if (uiState.errorMessage != null && uiState.pendingTransaction == null) {
                Box(modifier = Modifier.fillMaxSize().padding(Spacing.xl), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Spacing.l)) {
                        Text("⚠️", fontSize = 48.sp)
                        Text(
                            text = uiState.errorMessage ?: "Failed to read draft detail",
                            style = MaterialTheme.typography.titleMedium,
                            textAlign = TextAlign.Center
                        )
                        Button(onClick = { viewModel.loadPendingTransaction(pendingId) }) {
                            Text("Retry")
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = Spacing.l),
                    verticalArrangement = Arrangement.spacedBy(Spacing.l)
                ) {
                    item { Spacer(modifier = Modifier.height(Spacing.xxs)) }

                    // Draft warning card if duplicate detected
                    if (uiState.isDuplicate) {
                        item {
                            Card(
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.2f)),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.15f)),
                                shape = RoundedCornerShape(CornerRadius.m),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(Spacing.m),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(Spacing.s)
                                ) {
                                    Text("⚠️", fontSize = 24.sp)
                                    Column {
                                        Text(
                                            text = "Possible Duplicate",
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onErrorContainer
                                        )
                                        Text(
                                            text = "A transaction with similar amount and merchant is already logged.",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Hero amount card
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
                                Spacer(modifier = Modifier.height(Spacing.s))
                                Text(
                                    text = CurrencyUtils.formatCents((uiState.amount * 100).toLong()),
                                    style = MaterialTheme.typography.displayMedium.copy(fontWeight = FontWeight.ExtraBold),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                
                                Spacer(modifier = Modifier.height(Spacing.s))

                                // Credit / Debit selector
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(40.dp)
                                        .clip(RoundedCornerShape(CornerRadius.m))
                                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                                        .padding(4.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .fillMaxHeight()
                                            .clip(RoundedCornerShape(CornerRadius.s))
                                            .background(if (!uiState.isCredit) MaterialTheme.colorScheme.primary else Color.Transparent)
                                            .clickable { viewModel.onDirectionChanged(false) },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = "Debit (Outflow)",
                                            maxLines = 1,
                                            softWrap = false,
                                            style = MaterialTheme.typography.bodyMedium.copy(
                                                fontWeight = FontWeight.Bold,
                                                color = if (!uiState.isCredit) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        )
                                    }
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .fillMaxHeight()
                                            .clip(RoundedCornerShape(CornerRadius.s))
                                            .background(if (uiState.isCredit) MaterialTheme.colorScheme.primary else Color.Transparent)
                                            .clickable { viewModel.onDirectionChanged(true) },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = "Credit (Inflow)",
                                            maxLines = 1,
                                            softWrap = false,
                                            style = MaterialTheme.typography.bodyMedium.copy(
                                                fontWeight = FontWeight.Bold,
                                                color = if (uiState.isCredit) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        )
                                    }
                                }
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

                    // Category selection card picker
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                            Text(
                                text = "Category (Mandatory)",
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            BaseCard(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { showCategoryPicker = true },
                                contentPadding = Spacing.m
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Box(
                                            modifier = Modifier
                                                .size(36.dp)
                                                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(CornerRadius.s)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(categoryIcon, fontSize = 18.sp)
                                        }
                                        Spacer(modifier = Modifier.width(Spacing.s))
                                        Text(
                                            text = uiState.category + if (uiState.subcategory != null) " • ${uiState.subcategory}" else "",
                                            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold)
                                        )
                                    }
                                    Icon(Icons.Default.KeyboardArrowRight, contentDescription = "Select Category")
                                }
                            }
                        }
                    }

                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                            Text(
                                text = "Payment Method",
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(Spacing.s)
                            ) {
                                listOf("Cash", "UPI", "Credit Card", "Debit Card", "Net Banking", "Wallet", "Bank Transfer", "Others").forEach { method ->
                                    val isSelected = uiState.paymentMethod == method
                                    FilterChip(
                                        selected = isSelected,
                                        onClick = { viewModel.onPaymentMethodChanged(method) },
                                        label = {
                                            Text(
                                                text = method,
                                                maxLines = 1,
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
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Center
                        )
                    }

                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(Spacing.s)
                        ) {
                            OutlinedButton(
                                onClick = { viewModel.postponeReview() },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(CornerRadius.s)
                            ) {
                                Text("Review Later")
                            }
                            
                            OutlinedButton(
                                onClick = { viewModel.discardTransaction() },
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(CornerRadius.s)
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

    if (showCategoryPicker) {
        CategoryPickerBottomSheet(
            categories = categories,
            selectedCategoryName = uiState.category,
            selectedSubcategoryName = uiState.subcategory,
            onCategorySelected = { cat, sub ->
                viewModel.onCategoryChanged(cat)
                viewModel.onSubcategoryChanged(sub)
                showCategoryPicker = false
            },
            onAddCategory = { _, _, _, _ -> },
            onUpdateCategory = {},
            onDismissRequest = { showCategoryPicker = false }
        )
    }

    // Dialog: Duplicate Warning
    if (uiState.showDuplicateDialog) {
        ConfirmationDialog(
            onDismissRequest = { viewModel.hideDuplicateDialog() },
            onConfirm = { viewModel.approveTransaction(force = true) },
            title = "Warning: Duplicate Match",
            text = "An expense with this exact amount and merchant already exists. Do you want to log it anyway?",
            confirmText = "Log Anyway",
            dismissText = "Cancel",
            isDestructive = false
        )
    }
}
