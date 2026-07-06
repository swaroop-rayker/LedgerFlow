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
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
import com.ledgerflow.domain.model.Transaction
import com.ledgerflow.presentation.components.CategorySelector
import com.ledgerflow.presentation.components.SubcategorySelector
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
    
    // Dialog / Sheet states
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
                Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
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
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    item { Spacer(modifier = Modifier.height(2.dp)) }

                    // 1. Premium Header Card
                    item {
                        BaseCard(modifier = Modifier.fillMaxWidth(), contentPadding = 20.dp) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                // Merchant Logo / Icon
                                Box(
                                    modifier = Modifier
                                        .size(72.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    val firstChar = tx.merchant.firstOrNull()?.uppercaseChar() ?: 'E'
                                    Text(
                                        text = firstChar.toString(),
                                        style = MaterialTheme.typography.headlineLarge.copy(
                                            color = MaterialTheme.colorScheme.primary,
                                            fontWeight = FontWeight.Bold
                                        )
                                    )
                                }
                                
                                Spacer(modifier = Modifier.height(16.dp))
                                
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
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Icon(
                                            imageVector = Icons.Default.Info,
                                            contentDescription = null,
                                            modifier = Modifier.size(12.dp).padding(start = 2.dp),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    
                                    AnimatedVisibility(visible = rawMerchantExpanded) {
                                        Text(
                                            text = tx.rawMerchant ?: "",
                                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                                            color = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.padding(top = 4.dp),
                                            textAlign = TextAlign.Center
                                        )
                                    }
                                }
                                
                                Spacer(modifier = Modifier.height(16.dp))
                                
                                Text(
                                    text = CurrencyUtils.formatCents(tx.amount),
                                    style = MaterialTheme.typography.displaySmall.copy(
                                        fontWeight = FontWeight.ExtraBold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                )
                                
                                Spacer(modifier = Modifier.height(12.dp))
                                
                                Text(
                                    text = sdf.format(Date(tx.timestamp)),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                
                                Spacer(modifier = Modifier.height(16.dp))
                                
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    // Payment Method badge
                                    SuggestionChip(
                                        onClick = {},
                                        label = { Text(tx.paymentMethod ?: "Cash") }
                                    )
                                    // Confidence badge
                                    val isConfidenceHigh = uiState.merchantInfo != null
                                    SuggestionChip(
                                        onClick = {},
                                        label = { Text(if (isConfidenceHigh) "Highly Reliable Match" else "Standard Match") },
                                        colors = SuggestionChipDefaults.suggestionChipColors(
                                            labelColor = if (isConfidenceHigh) Color(0xFF10B981) else MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    )
                                }
                            }
                        }
                    }

                    // 2. Transaction Information Section
                    item {
                        BaseCard(modifier = Modifier.fillMaxWidth(), contentPadding = 16.dp) {
                            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("Transaction Information", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold))
                                    IconButton(
                                        onClick = { showEditCategory = true },
                                        modifier = Modifier.size(24.dp)
                                    ) {
                                        Icon(Icons.Default.Edit, contentDescription = "Edit Category", modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                                    }
                                }

                                Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f))

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
                                            text = tx.notes ?: "Tap to attach note...",
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
                                
                                // Source details (Debug only)
                                if (!tx.rawMerchant.isNullOrBlank()) {
                                    DetailRow(label = "SMS Source Type", value = "Standard SMS Alert")
                                }
                            }
                        }
                    }

                    // 3. Merchant Intelligence Section
                    item {
                        BaseCard(modifier = Modifier.fillMaxWidth(), contentPadding = 16.dp) {
                            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("Merchant Intelligence", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold))
                                    IconButton(
                                        onClick = { showEditMerchant = true },
                                        modifier = Modifier.size(24.dp)
                                    ) {
                                        Icon(Icons.Default.Edit, contentDescription = "Edit Merchant", modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                                    }
                                }

                                Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f))

                                val matchRule = when {
                                    uiState.merchantInfo?.regexPatterns?.isNotEmpty() == true -> "Regex Patterns Match"
                                    uiState.merchantInfo?.aliases?.isNotEmpty() == true -> "Canonical Alias Match"
                                    uiState.merchantInfo != null -> "Exact Match"
                                    else -> "Learned History Match"
                                }
                                
                                DetailRow(label = "Match Rule Used", value = matchRule)
                                DetailRow(label = "Usage Count", value = "${(uiState.merchantInfo?.usageCount ?: 0).coerceAtLeast(1)} logs")
                                DetailRow(label = "First Seen", value = if (uiState.firstSeen > 0) SimpleDateFormat("MMM dd, yyyy", Locale.getDefault()).format(Date(uiState.firstSeen)) else "Today")
                                DetailRow(label = "Last Seen", value = if (uiState.lastSeen > 0) SimpleDateFormat("MMM dd, yyyy", Locale.getDefault()).format(Date(uiState.lastSeen)) else "Today")
                                DetailRow(label = "Average Size", value = CurrencyUtils.formatCents(uiState.averageExpense))
                                DetailRow(label = "Highest Expense", value = CurrencyUtils.formatCents(uiState.highestExpense))
                                DetailRow(label = "Total spent", value = CurrencyUtils.formatCents(uiState.totalSpent))
                            }
                        }
                    }

                    // 4. Attachments Section
                    item {
                        BaseCard(modifier = Modifier.fillMaxWidth(), contentPadding = 16.dp) {
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("Attachments & Receipts", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold))
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

                                Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f))

                                if (uiState.attachments.isEmpty()) {
                                    Box(
                                        modifier = Modifier.fillMaxWidth().height(60.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = "No receipt attachments yet.",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                        )
                                    }
                                } else {
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        uiState.attachments.forEach { attach ->
                                            Box(
                                                modifier = Modifier
                                                    .size(64.dp)
                                                    .clip(RoundedCornerShape(8.dp))
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

                    // 5. Related Activity & Trend
                    if (uiState.previousExpenses.isNotEmpty() || uiState.similarTransactions.isNotEmpty()) {
                        item {
                            BaseCard(modifier = Modifier.fillMaxWidth(), contentPadding = 16.dp) {
                                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                    Text("Merchant Trend & Similar History", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold))

                                    Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f))

                                    // Spending trend mini-chart
                                    MerchantTrendChart(uiState.previousExpenses + tx)

                                    if (uiState.previousExpenses.isNotEmpty()) {
                                        Text(
                                            text = "Previous expenses:",
                                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        uiState.previousExpenses.take(3).forEach { prev ->
                                            Row(
                                                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                                horizontalArrangement = Arrangement.SpaceBetween
                                            ) {
                                                Text(
                                                    text = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault()).format(Date(prev.timestamp)),
                                                    style = MaterialTheme.typography.bodyMedium
                                                )
                                                Text(
                                                    text = CurrencyUtils.formatCents(prev.amount),
                                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // 6. Chronological Life Timeline
                    item {
                        BaseCard(modifier = Modifier.fillMaxWidth(), contentPadding = 16.dp) {
                            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                Text("Lifecycle Trail", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold))
                                
                                Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f))

                                val tdf = SimpleDateFormat("HH:mm, MMM d", Locale.getDefault())
                                
                                TimelineItem(
                                    title = "Transaction Tracked",
                                    subtitle = "Imported into database via ${if (tx.rawMerchant != null) "SMS Parser" else "Manual Log"}",
                                    timestamp = tdf.format(Date(tx.timestamp)),
                                    isFirst = true,
                                    isLast = false
                                )
                                
                                TimelineItem(
                                    title = "Merchant Map Resolved",
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

    // Modal Dialog: Edit Category & Subcategory
    if (showEditCategory && tx != null) {
        var tempCat by remember { mutableStateOf(tx.category) }
        var tempSub by remember { mutableStateOf(tx.subcategory) }
        
        AlertDialog(
            onDismissRequest = { showEditCategory = false },
            title = { Text("Categorization details") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    CategorySelector(
                        categories = uiState.categories,
                        selectedCategoryName = tempCat,
                        onCategorySelected = {
                            tempCat = it
                            tempSub = null
                        }
                    )
                    
                    val activeSubList = uiState.categories
                        .firstOrNull { it.category.name.equals(tempCat, ignoreCase = true) }
                        ?.subcategories ?: emptyList()
                    
                    if (activeSubList.isNotEmpty()) {
                        SubcategorySelector(
                            subcategories = activeSubList,
                            selectedSubcategoryName = tempSub,
                            onSubcategorySelected = { tempSub = it }
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.updateCategory(tempCat, tempSub)
                    showEditCategory = false
                }) {
                    Text("Apply Map")
                }
            },
            dismissButton = {
                TextButton(onClick = { showEditCategory = false }) {
                    Text("Cancel")
                }
            }
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
fun MerchantTrendChart(expenses: List<Transaction>) {
    if (expenses.isEmpty()) return
    val amounts = expenses.map { it.amount }
    val maxVal = amounts.maxOrNull() ?: 1L
    val minVal = amounts.minOrNull() ?: 0L
    val range = (maxVal - minVal).coerceAtLeast(1L)
    
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "30-Day Payee Spending Line Trend",
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(6.dp))
        Card(
            modifier = Modifier.fillMaxWidth().height(100.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)),
            shape = RoundedCornerShape(12.dp)
        ) {
            Box(modifier = Modifier.fillMaxSize().padding(12.dp)) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val width = size.width
                    val height = size.height
                    val points = expenses.reversed().mapIndexed { idx, tx ->
                        val x = if (expenses.size > 1) idx * (width / (expenses.size - 1)) else width / 2
                        val yFraction = (tx.amount - minVal).toFloat() / range.toFloat()
                        val y = height - (yFraction * height * 0.8f) - (height * 0.1f)
                        androidx.compose.ui.geometry.Offset(x, y)
                    }
                    
                    val path = androidx.compose.ui.graphics.Path().apply {
                        if (points.isNotEmpty()) {
                            moveTo(points[0].x, points[0].y)
                            for (i in 1 until points.size) {
                                lineTo(points[i].x, points[i].y)
                            }
                        }
                    }
                    drawPath(
                        path = path,
                        color = androidx.compose.ui.graphics.Color(0xFF10B981),
                        style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
                    )
                    
                    points.forEach { pt ->
                        drawCircle(
                            color = androidx.compose.ui.graphics.Color(0xFF10B981),
                            radius = 4.dp.toPx(),
                            center = pt
                        )
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(10.dp))
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
