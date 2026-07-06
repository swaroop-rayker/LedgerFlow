package com.ledgerflow.presentation.features.developer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.ledgerflow.core.ui.components.BaseCard
import com.ledgerflow.core.ui.components.PremiumButton
import com.ledgerflow.core.ui.components.PremiumTextField
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DatabaseToolsScreen(
    onNavigateBack: () -> Unit,
    viewModel: DatabaseToolsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    // Dialog state
    var activeAction by remember { mutableStateOf<String?>(null) }
    var factoryResetText by remember { mutableStateOf("") }
    
    // Selective Reset Checkbox labels mapping
    val checkboxItems = remember {
        listOf(
            "transactions" to "Expenses",
            "pending_transactions" to "Pending Transactions",
            "categories" to "Categories",
            "subcategories" to "Subcategories",
            "merchant_preferences" to "Merchant Preferences",
            "analytics" to "Analytics",
            "budgets" to "Budgets"
        )
    }

    LaunchedEffect(uiState.infoMessage, uiState.errorMessage) {
        uiState.infoMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessages()
        }
        uiState.errorMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessages()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Database Tools",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack, enabled = !uiState.isLoading) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.loadStats() }, enabled = !uiState.isLoading) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .padding(innerPadding)
                    .padding(horizontal = 16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Spacer(modifier = Modifier.height(2.dp))

                // 1. Database Info Card
                BaseCard(modifier = Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Database Information",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))

                    uiState.stats?.let { stats ->
                        InfoRow(label = "Path", value = stats.path)
                        InfoRow(label = "Size", value = formatSize(stats.sizeBytes))
                        InfoRow(label = "Schema Version", value = stats.schemaVersion.toString())
                        InfoRow(label = "Room Version", value = stats.roomVersion)
                        InfoRow(label = "App Version", value = stats.appVersion)
                        InfoRow(label = "Backup Format", value = stats.backupFormatVersion.toString())
                        InfoRow(label = "SQLite Version", value = stats.sqliteVersion)
                        InfoRow(label = "Encryption Status", value = stats.encryptionStatus)
                        InfoRow(label = "Integrity Status", value = stats.integrityStatus)
                        InfoRow(label = "Foreign Key Status", value = stats.foreignKeyStatus)
                        InfoRow(label = "WAL Mode", value = if (stats.walMode) "Enabled" else "Disabled")
                        InfoRow(label = "Page Count", value = stats.pageCount.toString())
                        InfoRow(label = "Last VACUUM", value = formatDate(stats.lastVacuum))
                        InfoRow(label = "Last Backup", value = formatDate(stats.lastBackup))
                        InfoRow(label = "Last Restore", value = formatDate(stats.lastRestore))
                        InfoRow(label = "Expenses Count", value = stats.expenseCount.toString())
                        InfoRow(label = "Pending Transactions", value = stats.pendingTransactionCount.toString())
                        InfoRow(label = "Merchants Count", value = stats.merchantCount.toString())
                        InfoRow(label = "Categories Count", value = stats.categoryCount.toString())
                        InfoRow(label = "Subcategories Count", value = stats.subcategoryCount.toString())
                        InfoRow(label = "Merchant Preferences", value = stats.merchantPreferenceCount.toString())
                        InfoRow(label = "Budgets Count", value = stats.budgetCount.toString())
                        InfoRow(label = "Audit Log Count", value = stats.auditLogCount.toString())
                        InfoRow(label = "Created At", value = formatDate(stats.createdTimestamp))
                        InfoRow(label = "Last Opened At", value = formatDate(stats.lastOpenedTimestamp))
                    } ?: run {
                        Text(
                            text = "Loading stats...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // 1b. Maintenance & Snapshots Card
                BaseCard(modifier = Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Build,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Maintenance & Snapshots",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { viewModel.runIntegrityCheck() },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("Integrity Check", style = MaterialTheme.typography.labelSmall)
                        }
                        Button(
                            onClick = { viewModel.runForeignKeyCheck() },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("FK Check", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { viewModel.runVacuum() },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("VACUUM", style = MaterialTheme.typography.labelSmall)
                        }
                        Button(
                            onClick = { viewModel.createSnapshot() },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary)
                        ) {
                            Text("Save Snapshot", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = { viewModel.restoreSnapshot() },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                    ) {
                        Text("Restore Snapshot", style = MaterialTheme.typography.labelMedium)
                    }
                }

                // 2. Table Reset Tools Section
                Text(
                    text = "Individual Table Resets",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 8.dp)
                )

                BaseCard(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "Clean or reset specific segments of data. Every reset retains constraints and re-validates DB integrity.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    val buttons = listOf(
                        "Clear Expenses" to { activeAction = "clear_expenses" },
                        "Clear Pending Transactions" to { activeAction = "clear_pending" },
                        "Clear Merchant Preferences" to { activeAction = "clear_merchant" },
                        "Clear Categories" to { activeAction = "clear_categories" },
                        "Clear Subcategories" to { activeAction = "clear_subcategories" },
                        "Reset Categories to Default" to { activeAction = "reset_categories_default" },
                        "Reset Merchant Learning" to { activeAction = "reset_merchant_learning" },
                        "Clear Reports Cache" to { activeAction = "clear_reports" },
                        "Clear Analytics Cache" to { activeAction = "clear_analytics" }
                    )

                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        buttons.forEach { (label, onClick) ->
                            OutlinedButton(
                                onClick = onClick,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(8.dp),
                                enabled = !uiState.isLoading,
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = MaterialTheme.colorScheme.error
                                )
                            ) {
                                Text(text = label, style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }

                // 3. Selective Reset Section
                Text(
                    text = "Selective Delete (Bulk Transaction)",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 8.dp)
                )

                BaseCard(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "Choose tables to clear. Selected tables will be reset within a single atomic database transaction.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    checkboxItems.forEach { (table, label) ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp)
                        ) {
                            Checkbox(
                                checked = uiState.selectedTables.contains(table),
                                onCheckedChange = { viewModel.toggleTableSelection(table) },
                                enabled = !uiState.isLoading
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(text = label, style = MaterialTheme.typography.bodyMedium)
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Button(
                        onClick = { activeAction = "selective_reset" },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        enabled = uiState.selectedTables.isNotEmpty() && !uiState.isLoading,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Delete Selected", style = MaterialTheme.typography.labelMedium)
                    }
                }

                // 4. Factory Reset / Danger Zone Section
                Text(
                    text = "Danger Zone",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 8.dp)
                )

                BaseCard(
                    modifier = Modifier.fillMaxWidth(),
                    containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.15f)
                ) {
                    Text(
                        text = "Factory Reset Database",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Permanently wipes all transaction rows, merchant lists, custom tags, budgets, categories, and resets defaults. This action is irreversible.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Spacer(modifier = Modifier.height(16.dp))

                    Button(
                        onClick = { activeAction = "factory_reset" },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        enabled = !uiState.isLoading,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Icon(Icons.Default.Build, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Factory Reset Database", style = MaterialTheme.typography.labelMedium)
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))
            }

            // Spinner Overlay when loading is occurring
            if (uiState.isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.4f)),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(strokeWidth = 3.dp, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }

    // Confirmation Dialogs
    when (activeAction) {
        "clear_expenses", "clear_pending", "clear_merchant", "clear_categories",
        "clear_subcategories", "reset_categories_default", "reset_merchant_learning",
        "clear_reports", "clear_analytics", "selective_reset" -> {
            AlertDialog(
                onDismissRequest = { activeAction = null },
                title = { Text("Confirm Database Action") },
                text = { Text("Are you sure you want to perform this data deletion? Obsolete structures will be cleaned permanently.") },
                confirmButton = {
                    TextButton(
                        onClick = {
                            val action = activeAction
                            activeAction = null
                            when (action) {
                                "clear_expenses" -> viewModel.clearExpenses()
                                "clear_pending" -> viewModel.clearPendingTransactions()
                                "clear_merchant" -> viewModel.clearMerchantPreferences()
                                "clear_categories" -> viewModel.clearCategories()
                                "clear_subcategories" -> viewModel.clearSubcategories()
                                "reset_categories_default" -> viewModel.resetCategoriesToDefault()
                                "reset_merchant_learning" -> viewModel.resetMerchantLearning()
                                "clear_reports" -> viewModel.clearReportsCache()
                                "clear_analytics" -> viewModel.clearAnalyticsCache()
                                "selective_reset" -> viewModel.selectiveReset()
                            }
                        }
                    ) {
                        Text("Confirm", color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { activeAction = null }) {
                        Text("Cancel")
                    }
                }
            )
        }
        "factory_reset" -> {
            AlertDialog(
                onDismissRequest = {
                    activeAction = null
                    factoryResetText = ""
                },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("CRITICAL FACTORY RESET")
                    }
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            text = "This will permanently delete:\n• Approved expenses\n• Pending transactions\n• Merchant preferences\n• Categories & Subcategories\n• Analytics & Budgets\n• Everything stored in the local database.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = "Type DELETE in all caps to authorize factory reset:",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold
                        )
                        PremiumTextField(
                            value = factoryResetText,
                            onValueChange = { factoryResetText = it },
                            label = "Confirmation",
                            placeholder = "DELETE"
                        )
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            activeAction = null
                            factoryResetText = ""
                            viewModel.factoryReset()
                        },
                        enabled = factoryResetText == "DELETE"
                    ) {
                        Text("FACTORY RESET", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            activeAction = null
                            factoryResetText = ""
                        }
                    ) {
                        Text("Cancel")
                    }
                }
            )
        }
    }
}

@Composable
fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium
            ),
            modifier = Modifier.padding(start = 16.dp)
        )
    }
}

private fun formatSize(bytes: Long): String {
    if (bytes <= 0) return "0 Bytes"
    val units = arrayOf("Bytes", "KB", "MB", "GB")
    val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
    return String.format(Locale.getDefault(), "%.2f %s", bytes / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
}

private fun formatDate(timestamp: Long): String {
    if (timestamp <= 0) return "Never"
    val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    return sdf.format(Date(timestamp))
}
