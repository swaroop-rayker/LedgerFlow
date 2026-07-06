package com.ledgerflow.presentation.features.developer

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.ExitToApp
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ledgerflow.core.common.util.DatabaseRecoveryState
import com.ledgerflow.domain.repository.DatabaseRecoveryRepository
import com.ledgerflow.domain.model.Result
import kotlinx.coroutines.launch
import kotlin.system.exitProcess

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DatabaseRecoveryScreen(
    info: DatabaseRecoveryState.IncompatibilityInfo,
    repository: DatabaseRecoveryRepository,
    onResetSuccess: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showDiagnosticsDialog by remember { mutableStateOf(false) }
    var showResetConfirmationDialog by remember { mutableStateOf(false) }
    var resetConfirmationInput by remember { mutableStateOf("") }
    
    // SAF Backup Launcher
    val backupLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/octet-stream"),
        onResult = { uri ->
            if (uri != null) {
                scope.launch {
                    val result = repository.backupDatabaseFile(uri)
                    if (result is Result.Success) {
                        Toast.makeText(context, "Encrypted database backup created successfully!", Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(context, "Failed to create database backup.", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    )

    // SAF JSON Export Launcher
    val jsonLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json"),
        onResult = { uri ->
            if (uri != null) {
                scope.launch {
                    val result = repository.exportDatabaseToJson(uri)
                    if (result is Result.Success) {
                        Toast.makeText(context, "JSON data backup exported successfully!", Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(context, "Failed to export data to JSON.", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Database Compatibility Manager", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    titleContentColor = MaterialTheme.colorScheme.onErrorContainer
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(innerPadding)
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(64.dp)
            )

            Text(
                text = "Incompatible Database Detected",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.error
            )

            Text(
                text = "The database file version or schema does not match the current app configuration. To prevent data corruption, startup has been paused.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    DetailRow(label = "Current Version", value = if (info.currentVersion == 0) "Unknown/Corrupt" else info.currentVersion.toString())
                    DetailRow(label = "Expected Version", value = info.expectedVersion.toString())
                    DetailRow(label = "Reason", value = info.reason)
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Recovery Actions",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.Start)
            )

            Button(
                onClick = { backupLauncher.launch("ledgerflow_backup_${System.currentTimeMillis()}.lfb") },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp)
            ) {
                Icon(Icons.Default.Share, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Backup Database File (.lfb)")
            }

            Button(
                onClick = { jsonLauncher.launch("ledgerflow_data_${System.currentTimeMillis()}.json") },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp)
            ) {
                Icon(Icons.Default.Info, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Export Readable Data (JSON)")
            }

            Button(
                onClick = { showResetConfirmationDialog = true },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error
                )
            ) {
                Icon(Icons.Default.Build, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Reset Local Database (Wipe)")
            }

            OutlinedButton(
                onClick = { showDiagnosticsDialog = true },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("View Detailed Diagnostics")
            }

            Button(
                onClick = { exitProcess(0) },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
            ) {
                Icon(Icons.Default.ExitToApp, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Exit Application")
            }
        }
    }

    if (showDiagnosticsDialog) {
        AlertDialog(
            onDismissRequest = { showDiagnosticsDialog = false },
            title = { Text("Diagnostics Log") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Technical reason detail:",
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = info.reason,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.background(MaterialTheme.colorScheme.surfaceVariant).padding(8.dp)
                    )
                    Text("System path: ledgerflow_secure.db")
                }
            },
            confirmButton = {
                TextButton(onClick = { showDiagnosticsDialog = false }) {
                    Text("Close")
                }
            }
        )
    }

    if (showResetConfirmationDialog) {
        AlertDialog(
            onDismissRequest = {
                showResetConfirmationDialog = false
                resetConfirmationInput = ""
            },
            title = { Text("Confirm Database WIPE") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("This action will permanently delete your local database, categories, subcategories, budgets, and all expense logs. This is irreversible.", color = MaterialTheme.colorScheme.error)
                    Text("Type WIPE in all caps to authorize database recreation:")
                    OutlinedTextField(
                        value = resetConfirmationInput,
                        onValueChange = { resetConfirmationInput = it },
                        singleLine = true,
                        placeholder = { Text("WIPE") }
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            val result = repository.resetDatabase()
                            showResetConfirmationDialog = false
                            resetConfirmationInput = ""
                            if (result is Result.Success) {
                                Toast.makeText(context, "Database wiped successfully.", Toast.LENGTH_LONG).show()
                                onResetSuccess()
                            } else {
                                Toast.makeText(context, "Wipe failed.", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    enabled = resetConfirmationInput == "WIPE"
                ) {
                    Text("RESET", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showResetConfirmationDialog = false
                    resetConfirmationInput = ""
                }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
        Text(text = value, style = MaterialTheme.typography.bodyMedium)
    }
}
