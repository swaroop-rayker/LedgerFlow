package com.ledgerflow.presentation.features.transactions

import android.content.Intent
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.ledgerflow.core.common.util.CurrencyUtils
import com.ledgerflow.core.ui.components.BaseCard
import com.ledgerflow.core.ui.components.ConfirmationDialog
import com.ledgerflow.core.ui.components.PremiumButton
import com.ledgerflow.core.ui.components.PremiumTextField
import com.ledgerflow.presentation.components.CategoryPickerBottomSheet
import com.ledgerflow.domain.model.Transaction
import com.ledgerflow.core.ui.theme.Spacing
import com.ledgerflow.core.ui.theme.CornerRadius
import com.ledgerflow.core.ui.theme.CuratedColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExpenseDetailsScreen(
    transactionId: Long,
    onNavigateBack: () -> Unit,
    viewModel: ExpenseDetailsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    
    var showEditNotes by remember { mutableStateOf(false) }
    var showEditMerchant by remember { mutableStateOf(false) }
    var showEditCategory by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var rawMerchantExpanded by remember { mutableStateOf(false) }

    LaunchedEffect(transactionId) {
        viewModel.loadExpenseDetails(transactionId)
    }

    LaunchedEffect(uiState.isDeleted) {
        if (uiState.isDeleted) {
            val result = snackbarHostState.showSnackbar(
                message = "Expense deleted successfully",
                actionLabel = "Undo",
                duration = SnackbarDuration.Long
            )
            if (result == SnackbarResult.ActionPerformed) {
                viewModel.undoDelete()
            } else {
                onNavigateBack()
            }
        }
    }

    val tx = uiState.transaction
    val sdf = remember { SimpleDateFormat("EEEE, d MMMM yyyy, h:mm a", Locale.getDefault()) }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { 
                    Text(
                        text = "Expense Profile", 
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    ) 
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (tx != null) {
                        IconButton(onClick = {
                            val shareText = "LedgerFlow Expense:\nMerchant: ${tx.merchant}\nAmount: ${CurrencyUtils.formatCents(tx.amount)}\nCategory: ${tx.category}\nDate: ${sdf.format(Date(tx.timestamp))}"
                            val intent = Intent().apply {
                                action = Intent.ACTION_SEND
                                putExtra(Intent.EXTRA_TEXT, shareText)
                                type = "text/plain"
                            }
                            context.startActivity(Intent.createChooser(intent, "Share Expense"))
                        }) {
                            Icon(Icons.Default.Share, contentDescription = "Share", tint = MaterialTheme.colorScheme.primary)
                        }
                        IconButton(onClick = { showDeleteConfirm = true }) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error)
                        }
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
            } else if (uiState.errorMessage != null && tx == null) {
                Box(modifier = Modifier.fillMaxSize().padding(Spacing.xl), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Spacing.l)) {
                        Text("⚠️", fontSize = 48.sp)
                        Text(
                            text = uiState.errorMessage ?: "Database details error",
                            style = MaterialTheme.typography.titleMedium,
                            textAlign = TextAlign.Center
                        )
                        Button(onClick = { viewModel.loadExpenseDetails(transactionId) }) {
                            Text("Retry Load")
                        }
                    }
                }
            } else if (tx != null) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = Spacing.l),
                    verticalArrangement = Arrangement.spacedBy(Spacing.l)
                ) {
                    item { Spacer(modifier = Modifier.height(Spacing.xxs)) }

                    // 1. Premium Hero Header Card
                    item {
                        val accentColor = remember(tx.category, uiState.categories) {
                            val hex = uiState.categories.find { it.category.name.equals(tx.category, ignoreCase = true) }?.category?.color
                            CuratedColors.getOrDefault(hex, Color(0xFF10B981))
                        }

                        BaseCard(
                            modifier = Modifier.fillMaxWidth(),
                            contentPadding = Spacing.xl
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                // Merchant Logo / Icon
                                Box(
                                    modifier = Modifier
                                        .size(80.dp)
                                        .clip(CircleShape)
                                        .background(accentColor.copy(alpha = 0.12f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    val firstChar = tx.merchant.firstOrNull()?.uppercaseChar() ?: 'E'
                                    Text(
                                        text = firstChar.toString(),
                                        style = MaterialTheme.typography.displayMedium.copy(
                                            color = accentColor,
                                            fontWeight = FontWeight.Bold
                                        )
                                    )
                                }
                                
                                Spacer(modifier = Modifier.height(Spacing.m))
                                
                                Text(
                                    text = tx.merchant,
                                    style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                                    textAlign = TextAlign.Center
                                )
                                
                                // Expandable Raw Merchant Name
                                if (!tx.rawMerchant.isNullOrBlank() && tx.rawMerchant != tx.merchant) {
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Row(
                                        modifier = Modifier.clickable { rawMerchantExpanded = !rawMerchantExpanded },
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "Source Name",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                        )
                                        Icon(
                                            imageVector = Icons.Default.Info,
                                            contentDescription = null,
                                            modifier = Modifier.size(12.dp).padding(start = 2.dp),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                        )
                                    }
                                    
                                    AnimatedVisibility(visible = rawMerchantExpanded) {
                                        Text(
                                            text = tx.rawMerchant ?: "",
                                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                                            color = accentColor,
                                            modifier = Modifier.padding(top = 4.dp),
                                            textAlign = TextAlign.Center
                                        )
                                    }
                                }
                                
                                Spacer(modifier = Modifier.height(Spacing.m))
                                
                                val isCredit = tx.amount < 0
                                val amountText = if (isCredit) {
                                    "+" + CurrencyUtils.formatCents(-tx.amount)
                                } else {
                                    "-" + CurrencyUtils.formatCents(tx.amount)
                                }
                                val amountColor = if (isCredit) {
                                    Color(0xFF2E7D32)
                                } else {
                                    MaterialTheme.colorScheme.primary
                                }
                                Text(
                                    text = amountText,
                                    style = MaterialTheme.typography.displayMedium.copy(
                                        fontWeight = FontWeight.ExtraBold,
                                        color = amountColor
                                    )
                                )
                                
                                Spacer(modifier = Modifier.height(Spacing.s))
                                
                                Text(
                                    text = sdf.format(Date(tx.timestamp)),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                                )
                                
                                Spacer(modifier = Modifier.height(Spacing.m))
                                
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(Spacing.s),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    SuggestionChip(
                                        onClick = {},
                                        label = { Text(tx.paymentMethod ?: "Cash") }
                                    )
                                    SuggestionChip(
                                        onClick = {},
                                        label = { Text("High Match Confidence") },
                                        colors = SuggestionChipDefaults.suggestionChipColors(
                                            labelColor = Color(0xFF10B981)
                                        )
                                    )
                                }
                            }
                        }
                    }

                    // 2. Transaction Information Section
                    item {
                        BaseCard(modifier = Modifier.fillMaxWidth()) {
                            Column(verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("Categorization", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold))
                                    IconButton(
                                        onClick = { showEditCategory = true },
                                        modifier = Modifier.size(24.dp)
                                    ) {
                                        Icon(Icons.Default.Edit, contentDescription = "Edit Category", modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                                    }
                                }

                                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.08f))

                                DetailRow(label = "Category", value = tx.category)
                                DetailRow(label = "Subcategory", value = tx.subcategory ?: "None")
                                
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.Top
                                ) {
                                    Text("Notes", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.clickable { showEditNotes = true }
                                    ) {
                                        Text(
                                            text = tx.notes ?: "Tap to add note...",
                                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                            textAlign = TextAlign.End,
                                            modifier = Modifier.widthIn(max = 200.dp),
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Icon(Icons.Default.Edit, contentDescription = "Edit Note", modifier = Modifier.size(12.dp).padding(start = 4.dp), tint = MaterialTheme.colorScheme.primary)
                                    }
                                }

                                DetailRow(label = "Reference / UTR", value = tx.reference ?: "N/A")
                            }
                        }
                    }

                    // 3. Merchant Intelligence Section (Redesigned with stats cards)
                    item {
                        BaseCard(modifier = Modifier.fillMaxWidth()) {
                            Column(verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("Payee Statistics", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold))
                                    IconButton(
                                        onClick = { showEditMerchant = true },
                                        modifier = Modifier.size(24.dp)
                                    ) {
                                        Icon(Icons.Default.Edit, contentDescription = "Edit Payee", modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                                    }
                                }

                                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.08f))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(Spacing.s)
                                ) {
                                    Card(
                                        modifier = Modifier.weight(1f),
                                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                                    ) {
                                        Column(modifier = Modifier.padding(Spacing.s)) {
                                            Text("Average Spent", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            Text(CurrencyUtils.formatCents(uiState.averageExpense), style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold))
                                        }
                                    }
                                    Card(
                                        modifier = Modifier.weight(1f),
                                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                                    ) {
                                        Column(modifier = Modifier.padding(Spacing.s)) {
                                            Text("Highest Spent", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            Text(CurrencyUtils.formatCents(uiState.highestExpense), style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold))
                                        }
                                    }
                                }

                                DetailRow(label = "Total spent", value = CurrencyUtils.formatCents(uiState.totalSpent))
                                DetailRow(label = "Logs Frequency", value = "${(uiState.merchantInfo?.usageCount ?: 0).coerceAtLeast(1)} logs")
                            }
                        }
                    }

                    // 4. Attachments Section
                    item {
                        BaseCard(modifier = Modifier.fillMaxWidth()) {
                            Column(verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("Receipt Attachments", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold))
                                    IconButton(
                                        onClick = {
                                            viewModel.addAttachment(
                                                filePath = "content://media/external/images/mock_${System.currentTimeMillis()}.jpg",
                                                fileType = "image"
                                            )
                                        },
                                        modifier = Modifier.size(24.dp)
                                    ) {
                                        Icon(Icons.Default.Add, contentDescription = "Add Receipt", modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                                    }
                                }

                                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.08f))

                                if (uiState.attachments.isEmpty()) {
                                    Box(
                                        modifier = Modifier.fillMaxWidth().height(60.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = "No receipt attachments.",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                        )
                                    }
                                } else {
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(Spacing.s),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        uiState.attachments.forEach { attach ->
                                            Box(
                                                modifier = Modifier
                                                    .size(64.dp)
                                                    .clip(RoundedCornerShape(CornerRadius.s))
                                                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f))
                                                    .clickable { viewModel.deleteAttachment(attach.id) },
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                                    Text("📄", fontSize = 18.sp)
                                                    Text(
                                                        text = "Receipt", 
                                                        style = MaterialTheme.typography.labelSmall,
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

                    // 5. Payee Trend Line Chart
                    if (uiState.previousExpenses.isNotEmpty()) {
                        item {
                            BaseCard(modifier = Modifier.fillMaxWidth()) {
                                Column(modifier = Modifier.fillMaxWidth()) {
                                    Text(
                                        text = "Payee Spending Trend",
                                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                                    )
                                    Spacer(modifier = Modifier.height(Spacing.m))
                                    MerchantHistoryChart(uiState.previousExpenses + tx)
                                }
                            }
                        }
                    }

                    // 6. Chronological Life Timeline
                    item {
                        BaseCard(modifier = Modifier.fillMaxWidth()) {
                            Column(verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
                                Text("Timeline Lifecycle Trail", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold))
                                
                                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.08f))

                                val tdf = SimpleDateFormat("HH:mm, MMM d", Locale.getDefault())
                                
                                TimelineItem(
                                    title = "Transaction Tracked",
                                    subtitle = "Imported into database via ${if (tx.rawMerchant != null) "SMS Parser" else "Manual Log"}",
                                    timestamp = tdf.format(Date(tx.timestamp)),
                                    isFirst = true,
                                    isLast = false
                                )
                                
                                TimelineItem(
                                    title = "Payee Map Resolved",
                                    subtitle = "Canonical identifier parsed as '${tx.merchant}'",
                                    timestamp = tdf.format(Date(tx.timestamp)),
                                    isFirst = false,
                                    isLast = tx.notes == null
                                )
                                
                                if (tx.notes != null) {
                                    TimelineItem(
                                        title = "User Audit Edits",
                                        subtitle = "Notes and descriptors updated",
                                        timestamp = "Recent",
                                        isFirst = false,
                                        isLast = true
                                    )
                                }
                            }
                        }
                    }

                    item { Spacer(modifier = Modifier.height(24.dp)) }
                }
            }
        }
    }

    // Modal Dialog: Edit Notes
    if (showEditNotes && tx != null) {
        var tempNotes by remember { mutableStateOf(tx.notes ?: "") }
        AlertDialog(
            onDismissRequest = { showEditNotes = false },
            title = { Text("Update Transaction Notes") },
            text = {
                PremiumTextField(
                    value = tempNotes,
                    onValueChange = { tempNotes = it },
                    label = "Notes",
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.updateNotes(tempNotes)
                    showEditNotes = false
                }) {
                    Text("Save Notes")
                }
            },
            dismissButton = {
                TextButton(onClick = { showEditNotes = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Modal Dialog: Edit Merchant Name
    if (showEditMerchant && tx != null) {
        var tempMerchant by remember { mutableStateOf(tx.merchant) }
        AlertDialog(
            onDismissRequest = { showEditMerchant = false },
            title = { Text("Update Merchant Map") },
            text = {
                PremiumTextField(
                    value = tempMerchant,
                    onValueChange = { tempMerchant = it },
                    label = "Merchant Name",
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.updateMerchant(tempMerchant)
                    showEditMerchant = false
                }) {
                    Text("Update Map")
                }
            },
            dismissButton = {
                TextButton(onClick = { showEditMerchant = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Sheet Category Selector using new Premium Picker Bottom Sheet
    if (showEditCategory && tx != null) {
        CategoryPickerBottomSheet(
            categories = uiState.categories,
            selectedCategoryName = tx.category,
            selectedSubcategoryName = tx.subcategory,
            onCategorySelected = { cat, sub ->
                viewModel.updateCategory(cat, sub)
                showEditCategory = false
            },
            onAddCategory = { _, _, _, _ -> },
            onUpdateCategory = {},
            onDismissRequest = { showEditCategory = false }
        )
    }

    // Dialog: Delete Confirmation
    if (showDeleteConfirm) {
        ConfirmationDialog(
            onDismissRequest = { showDeleteConfirm = false },
            onConfirm = {
                viewModel.deleteTransaction()
                showDeleteConfirm = false
            },
            title = "Delete Expense Profile",
            text = "Are you sure you want to permanently delete this expense? This action can be undone immediately via the snackbar.",
            confirmText = "Delete Profile",
            isDestructive = true
        )
    }
}

@Composable
fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text = value, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold))
    }
}

@Composable
fun TimelineItem(title: String, subtitle: String, timestamp: String, isFirst: Boolean, isLast: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary)
            )
            if (!isLast) {
                Box(
                    modifier = Modifier
                        .width(2.dp)
                        .height(40.dp)
                        .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                )
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
            Text(text = subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(text = timestamp, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}

@Composable
fun MerchantHistoryChart(expenses: List<Transaction>) {
    if (expenses.isEmpty()) return
    val amounts = expenses.map { it.amount.toFloat() }
    val maxVal = amounts.maxOrNull() ?: 1f
    val minVal = amounts.minOrNull() ?: 0f
    val range = (maxVal - minVal).coerceAtLeast(1f)

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(100.dp)
            .padding(vertical = Spacing.s)
    ) {
        val width = size.width
        val height = size.height
        
        val points = expenses.reversed().mapIndexed { idx, tx ->
            val x = if (expenses.size > 1) idx * (width / (expenses.size - 1)) else width / 2
            val fraction = (tx.amount.toFloat() - minVal) / range
            val y = height - (fraction * height * 0.7f) - (height * 0.15f)
            Offset(x, y)
        }

        val strokePath = Path().apply {
            if (points.isNotEmpty()) {
                moveTo(points[0].x, points[0].y)
                for (i in 1 until points.size) {
                    lineTo(points[i].x, points[i].y)
                }
            }
        }

        drawPath(
            path = strokePath,
            color = Color(0xFF10B981),
            style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
        )
        
        points.forEach { pt ->
            drawCircle(
                color = Color(0xFF10B981),
                radius = 5.dp.toPx(),
                center = pt
            )
            drawCircle(
                color = Color.White,
                radius = 2.dp.toPx(),
                center = pt
            )
        }
    }
}
