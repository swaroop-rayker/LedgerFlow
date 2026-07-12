package com.ledgerflow.presentation.features.transactions

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.ledgerflow.core.common.util.CurrencyUtils
import com.ledgerflow.core.ui.components.BaseCard
import com.ledgerflow.core.ui.components.PremiumButton
import com.ledgerflow.core.ui.components.PremiumTextField
import com.ledgerflow.core.ui.theme.CornerRadius
import com.ledgerflow.core.ui.theme.Spacing
import com.ledgerflow.presentation.components.CategoryPickerBottomSheet

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MultiCategorySplitScreen(
    onNavigateBack: () -> Unit,
    viewModel: MultiCategorySplitViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val categoriesState by viewModel.categories.collectAsState()
    val context = LocalContext.current

    var activeCategorizingItem by remember { mutableStateOf<SplitItem?>(null) }
    var showCategoryPicker by remember { mutableStateOf(false) }
    var isOcrLoading by remember { mutableStateOf(false) }

    val ocrLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            isOcrLoading = true
            try {
                val image = InputImage.fromFilePath(context, uri)
                val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                recognizer.process(image)
                    .addOnSuccessListener { visionText ->
                        isOcrLoading = false
                        viewModel.parseOcrText(visionText.text)
                    }
                    .addOnFailureListener { _ ->
                        isOcrLoading = false
                        // Fallback message handled in state
                    }
            } catch (e: Exception) {
                isOcrLoading = false
            }
        }
    }

    val totalAmount = remember(uiState.items) {
        uiState.items.sumOf { it.amount }
    }

    val canSave = remember(uiState.merchant, uiState.items, totalAmount) {
        uiState.merchant.isNotBlank() && uiState.items.isNotEmpty() && uiState.items.all { it.amount > 0.0 }
    }

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
                        text = "Multi-Category Split",
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
                        onClick = { viewModel.saveSplits() },
                        enabled = canSave && !uiState.isLoading
                    ) {
                        Icon(
                            imageVector = Icons.Default.Done,
                            contentDescription = "Save Splits",
                            tint = if (canSave) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
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
                .padding(horizontal = Spacing.l),
            verticalArrangement = Arrangement.spacedBy(Spacing.l)
        ) {
            item { Spacer(modifier = Modifier.height(Spacing.xxs)) }

            // Error Display Card
            uiState.errorMessage?.let { error ->
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                        shape = RoundedCornerShape(CornerRadius.m),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = error,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(Spacing.l),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }

            // 1. Basic Details Card (Merchant & Payment Method)
            item {
                BaseCard(modifier = Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
                        PremiumTextField(
                            value = uiState.merchant,
                            onValueChange = { viewModel.onMerchantChanged(it) },
                            label = "Merchant *",
                            placeholder = "Store/Payee name"
                        )

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
                                    label = { Text(text = method) }
                                )
                            }
                        }
                    }
                }
            }

            // 2. Tab Mode Selector (Manual vs OCR)
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .clip(RoundedCornerShape(CornerRadius.m))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                        .padding(4.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(CornerRadius.s))
                            .background(if (uiState.mode == SplitMode.MANUAL) MaterialTheme.colorScheme.primary else Color.Transparent)
                            .clickable { viewModel.onModeChanged(SplitMode.MANUAL) },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Manual Entry",
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontWeight = FontWeight.Bold,
                                color = if (uiState.mode == SplitMode.MANUAL) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        )
                    }

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(CornerRadius.s))
                            .background(if (uiState.mode == SplitMode.OCR) MaterialTheme.colorScheme.primary else Color.Transparent)
                            .clickable { viewModel.onModeChanged(SplitMode.OCR) },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "OCR Scan (Auto)",
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontWeight = FontWeight.Bold,
                                color = if (uiState.mode == SplitMode.OCR) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        )
                    }
                }
            }

            // Mode-specific Items Sections
            if (uiState.mode == SplitMode.MANUAL) {
                // MANUAL SPLITS LIST
                items(uiState.items, key = { it.id }) { item ->
                    Card(
                        shape = RoundedCornerShape(CornerRadius.m),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.12f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(Spacing.m),
                            verticalArrangement = Arrangement.spacedBy(Spacing.s)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = item.name,
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold)
                                )
                                IconButton(onClick = { viewModel.removeItem(item.id) }) {
                                    Icon(
                                        imageVector = Icons.Default.Delete,
                                        contentDescription = "Delete",
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(Spacing.s)
                            ) {
                                // Amount field
                                Box(modifier = Modifier.weight(1.2f)) {
                                    PremiumTextField(
                                        value = if (item.amount == 0.0) "" else item.amount.toString(),
                                        onValueChange = {
                                            val amt = it.toDoubleOrNull() ?: 0.0
                                            viewModel.updateItemAmount(item.id, amt)
                                        },
                                        label = "Amount (₹)",
                                        placeholder = "0.00",
                                        keyboardOptions = KeyboardOptions(
                                            keyboardType = KeyboardType.Decimal,
                                            imeAction = ImeAction.Done
                                        )
                                    )
                                }

                                // Category Picker selector
                                Box(
                                    modifier = Modifier
                                        .weight(1.8f)
                                        .align(Alignment.Bottom)
                                ) {
                                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text(
                                            text = "Category",
                                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        OutlinedCard(
                                            shape = RoundedCornerShape(10.dp),
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(56.dp)
                                                .clickable {
                                                    activeCategorizingItem = item
                                                    showCategoryPicker = true
                                                },
                                            colors = CardDefaults.outlinedCardColors(
                                                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f)
                                            )
                                        ) {
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxSize()
                                                    .padding(horizontal = Spacing.m),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                val catIcon = categoriesState.find { it.category.name.equals(item.category, ignoreCase = true) }?.category?.icon ?: "📁"
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    Text(catIcon, modifier = Modifier.padding(end = 6.dp))
                                                    Text(
                                                        text = item.category + if (item.subcategory != null) " • ${item.subcategory}" else "",
                                                        style = MaterialTheme.typography.bodyMedium,
                                                        maxLines = 1
                                                    )
                                                }
                                                Icon(Icons.Default.KeyboardArrowRight, contentDescription = null, modifier = Modifier.size(16.dp))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                item {
                    PremiumButton(
                        onClick = { viewModel.addManualSplit() },
                        text = "Add Split Allocation",
                        icon = Icons.Default.Add,
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            } else {
                // OCR MODE SECTION
                item {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(Spacing.s)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(Spacing.s)
                        ) {
                            PremiumButton(
                                onClick = { ocrLauncher.launch("image/*") },
                                text = "Pick Receipt File",
                                isLoading = isOcrLoading,
                                modifier = Modifier.weight(1f)
                            )
                            PremiumButton(
                                onClick = { viewModel.loadDemoOcr() },
                                text = "Load Demo Receipt",
                                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }

                if (uiState.items.isEmpty()) {
                    item {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = Spacing.m),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f))
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(Spacing.xl),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Text("🧾", fontSize = 48.sp)
                                Spacer(modifier = Modifier.height(Spacing.s))
                                Text(
                                    text = "Scan a receipt or choose load demo",
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.titleMedium,
                                    textAlign = TextAlign.Center
                                )
                                Text(
                                    text = "Items and prices will be auto-filled, then you can classify them manually.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.padding(top = 4.dp)
                                )
                            }
                        }
                    }
                } else {
                    // Extract items listed
                    items(uiState.items, key = { it.id }) { item ->
                        Card(
                            shape = RoundedCornerShape(CornerRadius.m),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.12f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(Spacing.m),
                                verticalArrangement = Arrangement.spacedBy(Spacing.s)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    IconButton(
                                        onClick = { viewModel.removeItem(item.id) },
                                        modifier = Modifier.size(24.dp)
                                    ) {
                                        Icon(Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error)
                                    }
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(Spacing.s)
                                ) {
                                    Box(modifier = Modifier.weight(1.4f)) {
                                        PremiumTextField(
                                            value = item.name,
                                            onValueChange = { viewModel.updateItemName(item.id, it) },
                                            label = "Item Name",
                                            placeholder = "Item name"
                                        )
                                    }
                                    Box(modifier = Modifier.weight(0.8f)) {
                                        PremiumTextField(
                                            value = if (item.amount == 0.0) "" else item.amount.toString(),
                                            onValueChange = {
                                                val amt = it.toDoubleOrNull() ?: 0.0
                                                viewModel.updateItemAmount(item.id, amt)
                                            },
                                            label = "Price (₹)",
                                            placeholder = "0.00",
                                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                                        )
                                    }
                                }

                                // Category Picker Row
                                OutlinedCard(
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(48.dp)
                                        .clickable {
                                            activeCategorizingItem = item
                                            showCategoryPicker = true
                                        },
                                    colors = CardDefaults.outlinedCardColors(
                                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f)
                                    )
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .padding(horizontal = Spacing.m),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        val catIcon = categoriesState.find { it.category.name.equals(item.category, ignoreCase = true) }?.category?.icon ?: "📁"
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(catIcon, modifier = Modifier.padding(end = 6.dp))
                                            Text(
                                                text = "Manual Sort Category: " + item.category + if (item.subcategory != null) " • ${item.subcategory}" else "",
                                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                                maxLines = 1
                                            )
                                        }
                                        Icon(Icons.Default.KeyboardArrowRight, contentDescription = null, modifier = Modifier.size(16.dp))
                                    }
                                }
                            }
                        }
                    }

                    item {
                        PremiumButton(
                            onClick = {
                                val customItem = SplitItem(
                                    name = "Custom Item #${uiState.items.size + 1}",
                                    amount = 0.0,
                                    category = "Others"
                                )
                                viewModel.onMerchantChanged(uiState.merchant) // keep merchant
                                viewModel.addManualSplit() // internally adds item
                            },
                            text = "Add Custom Item",
                            icon = Icons.Default.Add,
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }

            // 3. Premium Summary total Card
            if (uiState.items.isNotEmpty()) {
                item {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = Spacing.s),
                        shape = RoundedCornerShape(CornerRadius.m),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                    ) {
                        Column(modifier = Modifier.padding(Spacing.l)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "TOTAL SPENT",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.6f)
                                    )
                                )
                                Text(
                                    text = "${uiState.items.size} allocations",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                                )
                            }
                            Spacer(modifier = Modifier.height(Spacing.xs))
                            Text(
                                text = CurrencyUtils.formatCents((totalAmount * 100).toLong()),
                                style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.ExtraBold),
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                }
            }

            item { Spacer(modifier = Modifier.height(Spacing.xl)) }
        }
    }

    if (showCategoryPicker && activeCategorizingItem != null) {
        CategoryPickerBottomSheet(
            categories = categoriesState,
            selectedCategoryName = activeCategorizingItem?.category,
            selectedSubcategoryName = activeCategorizingItem?.subcategory,
            onCategorySelected = { cat, sub ->
                activeCategorizingItem?.let { item ->
                    viewModel.updateItemCategory(item.id, cat, sub)
                }
                showCategoryPicker = false
                activeCategorizingItem = null
            },
            onAddCategory = { _, _, _, _ -> },
            onUpdateCategory = {},
            onDismissRequest = {
                showCategoryPicker = false
                activeCategorizingItem = null
            }
        )
    }
}
