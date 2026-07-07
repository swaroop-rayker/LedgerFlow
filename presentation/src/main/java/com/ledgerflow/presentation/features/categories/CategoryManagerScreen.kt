package com.ledgerflow.presentation.features.categories

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.ledgerflow.core.ui.components.ConfirmationDialog
import com.ledgerflow.core.ui.components.EmptyStateView
import com.ledgerflow.core.ui.components.PremiumButton
import com.ledgerflow.core.ui.components.PremiumTextField
import com.ledgerflow.domain.model.Category
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoryManagerScreen(
    onNavigateBack: () -> Unit,
    viewModel: CategoryManagerViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    var searchQuery by remember { mutableStateOf("") }
    var isSearchActive by remember { mutableStateOf(false) }

    var showAddSheet by remember { mutableStateOf(false) }
    var showMergeSheet by remember { mutableStateOf(false) }
    var categoryToEdit by remember { mutableStateOf<Category?>(null) }
    var selectedCategoryForActions by remember { mutableStateOf<Category?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var categoryToDelete by remember { mutableStateOf<Category?>(null) }
    var expandedParentIds by remember { mutableStateOf(emptySet<Long>()) }
    var selectedCategoryId by remember { mutableStateOf<Long?>(null) }

    val filteredCategories = remember(uiState.categories, searchQuery) {
        if (searchQuery.isBlank()) {
            uiState.categories
        } else {
            uiState.categories.filter { it.name.contains(searchQuery, ignoreCase = true) }
        }
    }

    LaunchedEffect(searchQuery, uiState.categories) {
        if (searchQuery.isNotBlank()) {
            val matchingChildParentIds = uiState.categories
                .filter { it.parentId != null && it.name.contains(searchQuery, ignoreCase = true) }
                .mapNotNull { it.parentId }
                .toSet()
            expandedParentIds = expandedParentIds + matchingChildParentIds
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    if (isSearchActive) {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            placeholder = { Text("Search categories...", style = MaterialTheme.typography.bodyMedium) },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color.Transparent,
                                unfocusedBorderColor = Color.Transparent
                            ),
                            textStyle = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        Text(
                            text = "Category Manager", 
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { 
                        isSearchActive = !isSearchActive 
                        if (!isSearchActive) searchQuery = ""
                    }) {
                        Icon(
                            imageVector = if (isSearchActive) Icons.Default.Add else Icons.Default.Search, 
                            contentDescription = "Search",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                    IconButton(onClick = { showMergeSheet = true }) {
                        Icon(
                            imageVector = Icons.Default.Share, 
                            contentDescription = "Merge Categories",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showAddSheet = true },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                shape = RoundedCornerShape(16.dp),
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("Category") }
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
        ) {
            if (uiState.isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(strokeWidth = 3.dp, color = MaterialTheme.colorScheme.primary)
                }
            } else if (uiState.errorMessage != null) {
                EmptyStateView(
                    title = "Integrity Alert",
                    description = uiState.errorMessage ?: "Failed to handle category actions.",
                    iconEmoji = "⚠️"
                )
            } else if (uiState.categories.isEmpty()) {
                EmptyStateView(
                    title = "No Categories Formed",
                    description = "Establish spending branches to index split allocations.",
                    iconEmoji = "📁",
                    primaryActionText = "Create Category",
                    onPrimaryActionClick = { showAddSheet = true }
                )
            } else {
                val parentCategories = filteredCategories.filter { it.parentId == null }
                
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    item { Spacer(modifier = Modifier.height(2.dp)) }

                    parentCategories.forEach { parent ->
                        val children = filteredCategories.filter { it.parentId == parent.id }
                        val hasChildren = children.isNotEmpty()
                        val isExpanded = expandedParentIds.contains(parent.id)
                        val isSelected = selectedCategoryId == parent.id

                        item(key = parent.id) {
                            val rotationState by animateFloatAsState(
                                targetValue = if (isExpanded) 90f else 0f,
                                label = "ChevronRotation"
                            )
                            Surface(
                                onClick = {
                                    if (hasChildren) {
                                        expandedParentIds = if (isExpanded) {
                                            expandedParentIds - parent.id
                                        } else {
                                            expandedParentIds + parent.id
                                        }
                                    } else {
                                        selectedCategoryForActions = parent
                                    }
                                },
                                shape = RoundedCornerShape(8.dp),
                                color = Color.Transparent,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(horizontal = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    // Expand/Collapse Chevron
                                    Box(
                                        modifier = Modifier.size(24.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        if (hasChildren) {
                                            Icon(
                                                imageVector = Icons.Default.KeyboardArrowRight,
                                                contentDescription = null,
                                                modifier = Modifier.rotate(rotationState),
                                                tint = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = "${parent.icon ?: "📁"}  ${parent.name}",
                                        style = MaterialTheme.typography.bodyLarge,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                    IconButton(
                                        onClick = { selectedCategoryForActions = parent },
                                        modifier = Modifier.size(36.dp)
                                    ) {
                                        Icon(Icons.Default.MoreVert, contentDescription = "Actions", modifier = Modifier.size(18.dp))
                                    }
                                }
                            }
                        }

                        if (isExpanded && hasChildren) {
                            items(children) { child ->
                                Surface(
                                    onClick = { selectedCategoryForActions = child },
                                    shape = RoundedCornerShape(8.dp),
                                    color = Color.Transparent,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(48.dp)
                                        .padding(start = 24.dp)
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .padding(horizontal = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        // Visual spacer line alignment
                                        Box(
                                            modifier = Modifier
                                                .width(1.dp)
                                                .fillMaxHeight()
                                                .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
                                        )
                                        Spacer(modifier = Modifier.width(16.dp))
                                        Text(
                                            text = "${child.icon ?: "📄"}  ${child.name}",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f)
                                        )
                                        IconButton(
                                            onClick = { selectedCategoryForActions = child },
                                            modifier = Modifier.size(36.dp)
                                        ) {
                                            Icon(Icons.Default.MoreVert, contentDescription = "Actions", modifier = Modifier.size(18.dp))
                                        }
                                    }
                                }
                            }
                        }
                    }

                    item { Spacer(modifier = Modifier.height(80.dp)) }
                }
            }
        }
    }

    // Sheet: Add Category
    if (showAddSheet) {
        AddCategoryBottomSheet(
            categories = uiState.categories.filter { it.parentId == null },
            onAddCategory = { name, parentId ->
                viewModel.addCategory(name, parentId)
                showAddSheet = false
            },
            onDismissRequest = { showAddSheet = false }
        )
    }

    // Sheet: Merge Categories
    if (showMergeSheet && uiState.categories.size >= 2) {
        MergeCategoriesBottomSheet(
            categories = uiState.categories,
            onMerge = { source, target ->
                viewModel.mergeCategories(source, target)
                showMergeSheet = false
            },
            onDismissRequest = { showMergeSheet = false }
        )
    }

    // Context Action Bottom Sheet
    if (selectedCategoryForActions != null) {
        val cat = selectedCategoryForActions!!
        CategoryActionsBottomSheet(
            category = cat,
            categories = uiState.categories,
            onEdit = { categoryToEdit = cat },
            onPinToggle = { 
                viewModel.updateCategory(cat.copy(isPinned = !cat.isPinned))
            },
            onMerge = { targetId -> 
                viewModel.mergeCategories(cat.id, targetId)
            },
            onDelete = { categoryToDelete = cat },
            onDismissRequest = { selectedCategoryForActions = null }
        )
    }

    // Dialog: Delete Confirmation
    if (categoryToDelete != null) {
        val cat = categoryToDelete!!
        ConfirmationDialog(
            onDismissRequest = { categoryToDelete = null },
            onConfirm = {
                val name = cat.name
                val parentId = cat.parentId
                val color = cat.color
                val icon = cat.icon
                viewModel.deleteCategory(cat.id)
                categoryToDelete = null
                
                scope.launch {
                    val result = snackbarHostState.showSnackbar(
                        message = "Category '$name' deleted",
                        actionLabel = "Undo",
                        duration = SnackbarDuration.Long
                    )
                    if (result == SnackbarResult.ActionPerformed) {
                        viewModel.addCategory(name, parentId, color, icon)
                    }
                }
            },
            title = "Delete Category",
            text = "Are you sure you want to delete '${cat.name}'? Note: Categories referenced by transactions cannot be deleted.",
            confirmText = "Delete",
            isDestructive = true
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddCategoryBottomSheet(
    categories: List<Category>,
    onAddCategory: (String, Long?) -> Unit,
    onDismissRequest: () -> Unit
) {
    var categoryName by remember { mutableStateOf("") }
    var selectedParentId by remember { mutableStateOf<Long?>(null) }
    var isDropdownExpanded by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        dragHandle = { BottomSheetDefaults.DragHandle() },
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
                .padding(bottom = 32.dp)
        ) {
            Text(
                text = "Add New Category",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                modifier = Modifier.padding(bottom = 16.dp)
            )

            PremiumTextField(
                value = categoryName,
                onValueChange = { categoryName = it },
                label = "Category Name",
                placeholder = "e.g., Groceries, Rent"
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Parent Branch (Optional)",
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 6.dp)
            )

            Box(modifier = Modifier.fillMaxWidth()) {
                val currentParentName = categories.find { it.id == selectedParentId }?.name ?: "None (Create as Parent)"
                OutlinedButton(
                    onClick = { isDropdownExpanded = true },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.onSurface
                    )
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(currentParentName, style = MaterialTheme.typography.bodyMedium)
                        Icon(Icons.Default.KeyboardArrowRight, contentDescription = null, modifier = Modifier.rotate(90f))
                    }
                }
                
                DropdownMenu(
                    expanded = isDropdownExpanded,
                    onDismissRequest = { isDropdownExpanded = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("None (Create as Parent)") },
                        onClick = {
                            selectedParentId = null
                            isDropdownExpanded = false
                        }
                    )
                    categories.forEach { parent ->
                        DropdownMenuItem(
                            text = { Text(parent.name) },
                            onClick = {
                                selectedParentId = parent.id
                                isDropdownExpanded = false
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            PremiumButton(
                onClick = {
                    if (categoryName.isNotBlank()) {
                        onAddCategory(categoryName.trim(), selectedParentId)
                    }
                },
                text = "Create Category",
                enabled = categoryName.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MergeCategoriesBottomSheet(
    categories: List<Category>,
    onMerge: (Long, Long) -> Unit,
    onDismissRequest: () -> Unit
) {
    var sourceId by remember { mutableStateOf<Long?>(null) }
    var targetId by remember { mutableStateOf<Long?>(null) }

    var expandedSource by remember { mutableStateOf(false) }
    var expandedTarget by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        dragHandle = { BottomSheetDefaults.DragHandle() },
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
                .padding(bottom = 32.dp)
        ) {
            Text(
                text = "Merge Branches",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                modifier = Modifier.padding(bottom = 16.dp)
            )
            
            Text(
                text = "This redirects all transactions associated with the source category to the target category. Source category will be removed.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Source Dropdown Picker
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Source Category",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                    Box(modifier = Modifier.fillMaxWidth()) {
                        val sourceName = categories.find { it.id == sourceId }?.name ?: "Select branch"
                        OutlinedButton(
                            onClick = { expandedSource = true },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface)
                        ) {
                            Text(
                                text = sourceName, 
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        DropdownMenu(expanded = expandedSource, onDismissRequest = { expandedSource = false }) {
                            categories.forEach { cat ->
                                DropdownMenuItem(
                                    text = { Text(cat.name) },
                                    onClick = {
                                        sourceId = cat.id
                                        expandedSource = false
                                    }
                                )
                            }
                        }
                    }
                }

                // Target Dropdown Picker
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Target Category",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                    Box(modifier = Modifier.fillMaxWidth()) {
                        val targetName = categories.find { it.id == targetId }?.name ?: "Select branch"
                        OutlinedButton(
                            onClick = { expandedTarget = true },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface)
                        ) {
                            Text(
                                text = targetName, 
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        DropdownMenu(expanded = expandedTarget, onDismissRequest = { expandedTarget = false }) {
                            categories.forEach { cat ->
                                DropdownMenuItem(
                                    text = { Text(cat.name) },
                                    onClick = {
                                        targetId = cat.id
                                        expandedTarget = false
                                    }
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            val isValidMerge = sourceId != null && targetId != null && sourceId != targetId

            PremiumButton(
                onClick = {
                    if (isValidMerge) {
                        onMerge(sourceId!!, targetId!!)
                    }
                },
                text = "Merge & Delete Source",
                enabled = isValidMerge,
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoryActionsBottomSheet(
    category: Category,
    categories: List<Category>,
    onEdit: () -> Unit,
    onPinToggle: () -> Unit,
    onMerge: (Long) -> Unit,
    onDelete: () -> Unit,
    onDismissRequest: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        dragHandle = { BottomSheetDefaults.DragHandle() },
        containerColor = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = "Manage ${category.name}",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(start = 8.dp, end = 8.dp, bottom = 4.dp)
            )

            // Pin / Favorite
            ListItem(
                headlineContent = { Text(if (category.isPinned) "Remove from Favorites" else "Add to Favorites") },
                leadingContent = { 
                    Icon(
                        imageVector = Icons.Default.Star, 
                        contentDescription = null, 
                        tint = if (category.isPinned) Color(0xFFFFC107) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                    ) 
                },
                modifier = Modifier.clickable {
                    onPinToggle()
                    onDismissRequest()
                }
            )

            // Edit
            ListItem(
                headlineContent = { Text("Edit Details & Style") },
                leadingContent = { Icon(Icons.Default.Edit, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                modifier = Modifier.clickable {
                    onEdit()
                    onDismissRequest()
                }
            )

            // Merge
            var showMergeDropdown by remember { mutableStateOf(false) }
            Box(modifier = Modifier.fillMaxWidth()) {
                ListItem(
                    headlineContent = { Text("Merge into Another") },
                    leadingContent = { Icon(Icons.Default.Share, contentDescription = null) },
                    modifier = Modifier.clickable { showMergeDropdown = true }
                )
                DropdownMenu(
                    expanded = showMergeDropdown,
                    onDismissRequest = { showMergeDropdown = false }
                ) {
                    val mergeTargets = categories.filter { it.id != category.id && (it.parentId == null) == (category.parentId == null) }
                    if (mergeTargets.isEmpty()) {
                        DropdownMenuItem(text = { Text("No merge targets available") }, onClick = {})
                    } else {
                        mergeTargets.forEach { target ->
                            DropdownMenuItem(
                                text = { Text("Merge with: ${target.name}") },
                                onClick = {
                                    onMerge(target.id)
                                    showMergeDropdown = false
                                    onDismissRequest()
                                }
                            )
                        }
                    }
                }
            }

            Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.08f))

            // Delete
            ListItem(
                headlineContent = { Text("Delete Category", color = MaterialTheme.colorScheme.error) },
                leadingContent = { Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                modifier = Modifier.clickable {
                    onDelete()
                    onDismissRequest()
                }
            )
        }
    }
}

