package com.ledgerflow.presentation.features.categories

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.ledgerflow.core.ui.components.BaseCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoryManagerScreen(
    onNavigateBack: () -> Unit,
    viewModel: CategoryManagerViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    var newCategoryName by remember { mutableStateOf("") }
    var selectedParentId by remember { mutableStateOf<Long?>(null) }
    
    // Merge states
    var mergeSourceId by remember { mutableStateOf<Long?>(null) }
    var mergeTargetId by remember { mutableStateOf<Long?>(null) }

    LaunchedEffect(uiState.isOperationSuccess) {
        if (uiState.isOperationSuccess) {
            newCategoryName = ""
            selectedParentId = null
            viewModel.resetOperationSuccess()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    Text(
                        "Category Manager", 
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
            // Spacer for top rhythm
            item { Spacer(modifier = Modifier.height(2.dp)) }

            // Error Display banner
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

            // Section: Add New Category
            item {
                BaseCard(modifier = Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Add New Category",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = newCategoryName,
                        onValueChange = { newCategoryName = it },
                        label = { Text("Category Name") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        shape = RoundedCornerShape(8.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    // Subcategory Dropdown Picker
                    val parentCategories = uiState.categories.filter { it.parentId == null }
                    if (parentCategories.isNotEmpty()) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                "Parent Category (Optional):", 
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            var expandedParentMenu by remember { mutableStateOf(false) }
                            Box {
                                OutlinedButton(
                                    onClick = { expandedParentMenu = true },
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    val currentParentLabel = parentCategories.find { it.id == selectedParentId }?.name ?: "None"
                                    Text(currentParentLabel)
                                }
                                DropdownMenu(
                                    expanded = expandedParentMenu,
                                    onDismissRequest = { expandedParentMenu = false }
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("None (Create Parent)") },
                                        onClick = {
                                            selectedParentId = null
                                            expandedParentMenu = false
                                        }
                                    )
                                    parentCategories.forEach { parent ->
                                        DropdownMenuItem(
                                            text = { Text(parent.name) },
                                            onClick = {
                                                selectedParentId = parent.id
                                                expandedParentMenu = false
                                            }
                                        )
                                    }
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                    }

                    Button(
                        onClick = {
                            if (newCategoryName.isNotBlank()) {
                                viewModel.addCategory(newCategoryName.trim(), selectedParentId)
                            }
                        },
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        Text("Create")
                    }
                }
            }

            // Section: Merge Categories
            if (uiState.categories.size >= 2) {
                item {
                    BaseCard(modifier = Modifier.fillMaxWidth()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Share,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.secondary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Merge Categories",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            // Source Picker Dropdown
                            var expandedSource by remember { mutableStateOf(false) }
                            Box(modifier = Modifier.weight(1f)) {
                                OutlinedButton(
                                    onClick = { expandedSource = true },
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text(uiState.categories.find { it.id == mergeSourceId }?.name ?: "Source Branch")
                                }
                                DropdownMenu(expanded = expandedSource, onDismissRequest = { expandedSource = false }) {
                                    uiState.categories.forEach { cat ->
                                        DropdownMenuItem(
                                            text = { Text(cat.name) },
                                            onClick = {
                                                mergeSourceId = cat.id
                                                expandedSource = false
                                            }
                                        )
                                    }
                                }
                            }

                            // Target Picker Dropdown
                            var expandedTarget by remember { mutableStateOf(false) }
                            Box(modifier = Modifier.weight(1f)) {
                                OutlinedButton(
                                    onClick = { expandedTarget = true },
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text(uiState.categories.find { it.id == mergeTargetId }?.name ?: "Target Branch")
                                }
                                DropdownMenu(expanded = expandedTarget, onDismissRequest = { expandedTarget = false }) {
                                    uiState.categories.forEach { cat ->
                                        DropdownMenuItem(
                                            text = { Text(cat.name) },
                                            onClick = {
                                                mergeTargetId = cat.id
                                                expandedTarget = false
                                            }
                                        )
                                    }
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(
                            onClick = {
                                val source = mergeSourceId
                                val target = mergeTargetId
                                if (source != null && target != null && source != target) {
                                    viewModel.mergeCategories(source, target)
                                    mergeSourceId = null
                                    mergeTargetId = null
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.align(Alignment.End)
                        ) {
                            Text("Execute Merge")
                        }
                    }
                }
            }

            // Section: Categories Tree Hierarchy Header
            item {
                Text(
                    "Hierarchy Tree", 
                    style = MaterialTheme.typography.titleMedium, 
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            // Render category list hierarchy trees
            val parents = uiState.categories.filter { it.parentId == null }
            val subcategoriesGrouped = uiState.categories.filter { it.parentId != null }.groupBy { it.parentId }

            parents.forEach { parent ->
                item {
                    BaseCard(
                        modifier = Modifier.fillMaxWidth(),
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = parent.name, 
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            IconButton(
                                onClick = { viewModel.deleteCategory(parent.id) },
                                colors = IconButtonDefaults.iconButtonColors(contentColor = MaterialTheme.colorScheme.error)
                            ) {
                                Icon(Icons.Default.Delete, contentDescription = "Delete")
                            }
                        }
                    }
                }

                val subBranches = subcategoriesGrouped[parent.id] ?: emptyList()
                items(subBranches) { sub ->
                    BaseCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 24.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "└─ ", 
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = sub.name,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                            IconButton(
                                onClick = { viewModel.deleteCategory(sub.id) },
                                colors = IconButtonDefaults.iconButtonColors(contentColor = MaterialTheme.colorScheme.error)
                            ) {
                                Icon(Icons.Default.Delete, contentDescription = "Delete Sub")
                            }
                        }
                    }
                }
            }

            // Spacer for end of tree scrolling list
            item { Spacer(modifier = Modifier.height(24.dp)) }
        }
    }
}
