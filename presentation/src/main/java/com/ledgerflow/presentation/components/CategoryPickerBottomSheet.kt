package com.ledgerflow.presentation.components

import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ledgerflow.domain.model.Category
import com.ledgerflow.domain.model.CategoryWithSubcategories
import com.ledgerflow.core.ui.theme.Spacing
import com.ledgerflow.core.ui.theme.CornerRadius
import com.ledgerflow.core.ui.theme.CuratedColors
import com.ledgerflow.core.ui.components.PremiumTextField

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun CategoryPickerBottomSheet(
    categories: List<CategoryWithSubcategories>,
    selectedCategoryName: String?,
    selectedSubcategoryName: String?,
    onCategorySelected: (String, String?) -> Unit,
    onAddCategory: (String, Long?, String?, String?) -> Unit, // name, parentId, color, icon
    onUpdateCategory: (Category) -> Unit, // For Pin/Favorite, Rename, Color, Icon
    onDismissRequest: () -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    var isQuickCreateExpanded by remember { mutableStateOf(false) }
    var newCatName by remember { mutableStateOf("") }
    var newCatParentId by remember { mutableStateOf<Long?>(null) }
    var newCatColor by remember { mutableStateOf(CuratedColors.Blue) }
    var newCatIcon by remember { mutableStateOf("📁") }
    
    // Inline edit category state
    var editingCategory by remember { mutableStateOf<Category?>(null) }
    var showEditMenu by remember { mutableStateOf(false) }

    val filteredCategoryTrees = remember(categories, searchQuery) {
        if (searchQuery.isBlank()) {
            categories
        } else {
            categories.mapNotNull { catTree ->
                val matchesParent = catTree.category.name.contains(searchQuery, ignoreCase = true)
                val matchingSubcats = catTree.subcategories.filter {
                    it.name.contains(searchQuery, ignoreCase = true)
                }
                if (matchesParent || matchingSubcats.isNotEmpty()) {
                    CategoryWithSubcategories(catTree.category, matchingSubcats)
                } else null
            }
        }
    }

    val pinnedCategories = remember(categories) {
        categories.map { it.category }.filter { it.isPinned }
    }

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        dragHandle = { BottomSheetDefaults.DragHandle() },
        containerColor = MaterialTheme.colorScheme.background,
        modifier = Modifier.fillMaxHeight(0.85f)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = Spacing.l)
        ) {
            // Title & Quick Action Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Select Category",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onBackground
                )
                IconButton(onClick = { isQuickCreateExpanded = !isQuickCreateExpanded }) {
                    Icon(
                        imageVector = if (isQuickCreateExpanded) Icons.Default.Close else Icons.Default.Add,
                        contentDescription = "Quick Create",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }

            // Quick Create Inline Panel
            AnimatedVisibility(
                visible = isQuickCreateExpanded,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
                    shape = RoundedCornerShape(CornerRadius.m),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.15f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = Spacing.s)
                ) {
                    Column(modifier = Modifier.padding(Spacing.m)) {
                        Text(
                            text = "Quick Create Category / Subcategory",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(Spacing.s))
                        OutlinedTextField(
                            value = newCatName,
                            onValueChange = { newCatName = it },
                            placeholder = { Text("Category name...", style = MaterialTheme.typography.bodyMedium) },
                            singleLine = true,
                            shape = RoundedCornerShape(CornerRadius.s),
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = MaterialTheme.colorScheme.surface,
                                unfocusedContainerColor = MaterialTheme.colorScheme.surface
                            )
                        )
                        Spacer(modifier = Modifier.height(Spacing.s))
                        
                        // Select Parent, Icon, Color Row
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(Spacing.s),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Parent selection
                            var parentDropdownExpanded by remember { mutableStateOf(false) }
                            Box(modifier = Modifier.weight(1f)) {
                                OutlinedButton(
                                    onClick = { parentDropdownExpanded = true },
                                    shape = RoundedCornerShape(CornerRadius.s),
                                    modifier = Modifier.fillMaxWidth(),
                                    contentPadding = PaddingValues(horizontal = Spacing.s, vertical = Spacing.xs)
                                ) {
                                    val parentName = categories.find { it.category.id == newCatParentId }?.category?.name ?: "Parent (None)"
                                    Text(
                                        text = parentName,
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                DropdownMenu(
                                    expanded = parentDropdownExpanded,
                                    onDismissRequest = { parentDropdownExpanded = false }
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("None (Create as Parent)") },
                                        onClick = {
                                            newCatParentId = null
                                            parentDropdownExpanded = false
                                        }
                                    )
                                    categories.forEach { cat ->
                                        DropdownMenuItem(
                                            text = { Text(cat.category.name) },
                                            onClick = {
                                                newCatParentId = cat.category.id
                                                parentDropdownExpanded = false
                                            }
                                        )
                                    }
                                }
                            }

                            // Emojis Selection
                            var emojiDropdownExpanded by remember { mutableStateOf(false) }
                            Box {
                                OutlinedButton(
                                    onClick = { emojiDropdownExpanded = true },
                                    shape = RoundedCornerShape(CornerRadius.s)
                                ) {
                                    Text(newCatIcon)
                                }
                                DropdownMenu(
                                    expanded = emojiDropdownExpanded,
                                    onDismissRequest = { emojiDropdownExpanded = false }
                                ) {
                                    val emojis = listOf("📁", "🍔", "🛍️", "🚗", "💡", "🎬", "🏥", "🎓", "✈️", "🛒", "📈", "🔹", "🏠", "🍕", "🎮", "👕", "🚌")
                                    Row(modifier = Modifier.width(180.dp).padding(4.dp)) {
                                        LazyVerticalGrid(
                                            columns = GridCells.Fixed(4),
                                            modifier = Modifier.height(150.dp)
                                        ) {
                                            items(emojis) { emoji ->
                                                Text(
                                                    text = emoji,
                                                    modifier = Modifier
                                                        .clickable {
                                                            newCatIcon = emoji
                                                            emojiDropdownExpanded = false
                                                        }
                                                        .padding(Spacing.xs),
                                                    fontSize = 18.sp,
                                                    textAlign = TextAlign.Center
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            // Curated Color Selection
                            var colorDropdownExpanded by remember { mutableStateOf(false) }
                            Box {
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(CircleShape)
                                        .background(Color(android.graphics.Color.parseColor(newCatColor)))
                                        .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                                        .clickable { colorDropdownExpanded = true }
                                )
                                DropdownMenu(
                                    expanded = colorDropdownExpanded,
                                    onDismissRequest = { colorDropdownExpanded = false }
                                ) {
                                    Row(modifier = Modifier.width(180.dp).padding(4.dp)) {
                                        LazyVerticalGrid(
                                            columns = GridCells.Fixed(4),
                                            modifier = Modifier.height(150.dp)
                                        ) {
                                            items(CuratedColors.all) { (hex, name) ->
                                                Box(
                                                    modifier = Modifier
                                                        .size(30.dp)
                                                        .padding(4.dp)
                                                        .clip(CircleShape)
                                                        .background(Color(android.graphics.Color.parseColor(hex)))
                                                        .clickable {
                                                            newCatColor = hex
                                                            colorDropdownExpanded = false
                                                        }
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(Spacing.s))
                        Button(
                            onClick = {
                                if (newCatName.isNotBlank()) {
                                    onAddCategory(newCatName.trim(), newCatParentId, newCatColor, newCatIcon)
                                    newCatName = ""
                                    isQuickCreateExpanded = false
                                }
                            },
                            enabled = newCatName.isNotBlank(),
                            shape = RoundedCornerShape(CornerRadius.s),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Create & Add")
                        }
                    }
                }
            }

            // Search Bar
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Search categories or subcategories...") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Search Icon") },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = Spacing.s),
                singleLine = true,
                shape = RoundedCornerShape(CornerRadius.m),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f),
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.15f),
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.15f)
                )
            )

            // Pinned/Favorites row
            if (pinnedCategories.isNotEmpty() && searchQuery.isBlank()) {
                Text(
                    text = "Favorites",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = Spacing.s, bottom = Spacing.xs)
                )
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(110.dp),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.s),
                    verticalArrangement = Arrangement.spacedBy(Spacing.s),
                    modifier = Modifier.fillMaxWidth().wrapContentHeight()
                ) {
                    items(pinnedCategories, key = { "pinned_" + it.id }) { cat ->
                        val isSelected = cat.name.equals(selectedCategoryName, ignoreCase = true)
                        val catColor = CuratedColors.getOrDefault(cat.color, MaterialTheme.colorScheme.primary)
                        
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = if (isSelected) catColor.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surface
                            ),
                            border = BorderStroke(
                                1.dp,
                                if (isSelected) catColor else MaterialTheme.colorScheme.outline.copy(alpha = 0.1f)
                            ),
                            shape = RoundedCornerShape(CornerRadius.m),
                            modifier = Modifier
                                .fillMaxWidth()
                                .combinedClickable(
                                    onClick = { onCategorySelected(cat.name, null) },
                                    onLongClick = {
                                        editingCategory = cat
                                        showEditMenu = true
                                    }
                                )
                        ) {
                            Column(
                                modifier = Modifier
                                    .padding(Spacing.s)
                                    .fillMaxWidth(),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(CircleShape)
                                        .background(catColor.copy(alpha = 0.1f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(cat.icon ?: "📁", fontSize = 18.sp)
                                }
                                Spacer(modifier = Modifier.height(Spacing.xs))
                                Text(
                                    text = cat.name,
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(Spacing.s))
            }

            // Categories List
            Text(
                text = "All Categories",
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = Spacing.xs)
            )

            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(Spacing.xs)
            ) {
                items(filteredCategoryTrees, key = { "tree_" + it.category.id }) { catTree ->
                    val parent = catTree.category
                    val isParentSelected = parent.name.equals(selectedCategoryName, ignoreCase = true)
                    val parentColor = CuratedColors.getOrDefault(parent.color, MaterialTheme.colorScheme.primary)
                    
                    var isExpanded by remember { mutableStateOf(selectedCategoryName.equals(parent.name, ignoreCase = true)) }

                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
                        border = null,
                        shape = RoundedCornerShape(CornerRadius.m),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column {
                            // Parent row
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .combinedClickable(
                                        onClick = {
                                            if (catTree.subcategories.isNotEmpty()) {
                                                isExpanded = !isExpanded
                                            } else {
                                                onCategorySelected(parent.name, null)
                                            }
                                        },
                                        onLongClick = {
                                            editingCategory = parent
                                            showEditMenu = true
                                        }
                                    )
                                    .padding(vertical = Spacing.s, horizontal = Spacing.xs),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(32.dp)
                                        .clip(CircleShape)
                                        .background(parentColor.copy(alpha = 0.1f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(parent.icon ?: "📁", fontSize = 16.sp)
                                }
                                Spacer(modifier = Modifier.width(Spacing.s))
                                Text(
                                    text = parent.name,
                                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                                    modifier = Modifier.weight(1f)
                                )
                                
                                if (parent.isPinned) {
                                    Icon(
                                        imageVector = Icons.Default.Star,
                                        contentDescription = "Pinned",
                                        tint = Color(0xFFFFC107),
                                        modifier = Modifier.size(16.dp).padding(end = Spacing.xs)
                                    )
                                }
                                
                                if (catTree.subcategories.isNotEmpty()) {
                                    Icon(
                                        imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                } else {
                                    if (isParentSelected && selectedSubcategoryName == null) {
                                        Icon(
                                            imageVector = Icons.Default.Check,
                                            contentDescription = "Selected",
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                            }

                            // Subcategories list
                            AnimatedVisibility(
                                visible = isExpanded && catTree.subcategories.isNotEmpty(),
                                enter = fadeIn() + expandVertically(),
                                exit = fadeOut() + shrinkVertically()
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(start = Spacing.xl)
                                ) {
                                    catTree.subcategories.forEach { sub ->
                                        val isSubSelected = sub.name.equals(selectedSubcategoryName, ignoreCase = true)
                                        val subColor = CuratedColors.getOrDefault(sub.color, MaterialTheme.colorScheme.secondary)

                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .combinedClickable(
                                                    onClick = { onCategorySelected(parent.name, sub.name) },
                                                    onLongClick = {
                                                        editingCategory = sub
                                                        showEditMenu = true
                                                    }
                                                )
                                                .padding(vertical = Spacing.s, horizontal = Spacing.xs),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .size(24.dp)
                                                    .clip(CircleShape)
                                                    .background(subColor.copy(alpha = 0.1f)),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Text(sub.icon ?: "🔹", fontSize = 12.sp)
                                            }
                                            Spacer(modifier = Modifier.width(Spacing.s))
                                            Text(
                                                text = sub.name,
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.weight(1f)
                                            )
                                            if (isSubSelected) {
                                                Icon(
                                                    imageVector = Icons.Default.Check,
                                                    contentDescription = "Selected",
                                                    tint = MaterialTheme.colorScheme.primary
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Category Edit Context Dialog
    if (showEditMenu && editingCategory != null) {
        val cat = editingCategory!!
        var tempName by remember(cat) { mutableStateOf(cat.name) }
        var tempIcon by remember(cat) { mutableStateOf(cat.icon ?: "📁") }
        var tempColor by remember(cat) { mutableStateOf(cat.color ?: CuratedColors.Blue) }
        
        AlertDialog(
            onDismissRequest = {
                showEditMenu = false
                editingCategory = null
            },
            title = { Text("Edit ${if (cat.parentId != null) "Subcategory" else "Category"}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                    PremiumTextField(
                        value = tempName,
                        onValueChange = { tempName = it },
                        label = "Name"
                    )
                    Spacer(modifier = Modifier.height(Spacing.xs))
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.s),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Emoji dropdown inside dialog
                        var dEmojiExpanded by remember { mutableStateOf(false) }
                        Box(modifier = Modifier.weight(1f)) {
                            OutlinedButton(
                                onClick = { dEmojiExpanded = true },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(CornerRadius.s)
                            ) {
                                Text("Icon: $tempIcon")
                            }
                            DropdownMenu(expanded = dEmojiExpanded, onDismissRequest = { dEmojiExpanded = false }) {
                                val emojis = listOf("📁", "🍔", "🛍️", "🚗", "💡", "🎬", "🏥", "🎓", "✈️", "🛒", "📈", "🔹", "🏠", "🍕", "🎮", "👕", "🍔", "🍷")
                                Row(modifier = Modifier.width(180.dp).padding(4.dp)) {
                                    LazyVerticalGrid(columns = GridCells.Fixed(4), modifier = Modifier.height(120.dp)) {
                                        items(emojis) { emoji ->
                                            Text(
                                                text = emoji,
                                                modifier = Modifier
                                                    .clickable {
                                                        tempIcon = emoji
                                                        dEmojiExpanded = false
                                                    }
                                                    .padding(Spacing.xs),
                                                fontSize = 18.sp,
                                                textAlign = TextAlign.Center
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        // Color dropdown inside dialog
                        var dColorExpanded by remember { mutableStateOf(false) }
                        Box {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(Color(android.graphics.Color.parseColor(tempColor)))
                                    .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                                    .clickable { dColorExpanded = true }
                            )
                            DropdownMenu(expanded = dColorExpanded, onDismissRequest = { dColorExpanded = false }) {
                                Row(modifier = Modifier.width(180.dp).padding(4.dp)) {
                                    LazyVerticalGrid(columns = GridCells.Fixed(4), modifier = Modifier.height(120.dp)) {
                                        items(CuratedColors.all) { (hex, name) ->
                                            Box(
                                                modifier = Modifier
                                                    .size(30.dp)
                                                    .padding(4.dp)
                                                    .clip(CircleShape)
                                                    .background(Color(android.graphics.Color.parseColor(hex)))
                                                    .clickable {
                                                        tempColor = hex
                                                        dColorExpanded = false
                                                    }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val updated = cat.copy(name = tempName.trim(), icon = tempIcon, color = tempColor)
                        onUpdateCategory(updated)
                        showEditMenu = false
                        editingCategory = null
                    },
                    enabled = tempName.isNotBlank()
                ) {
                    Text("Save Changes")
                }
            },
            dismissButton = {
                // Favorite Toggle Option
                TextButton(
                    onClick = {
                        val updated = cat.copy(isPinned = !cat.isPinned)
                        onUpdateCategory(updated)
                        showEditMenu = false
                        editingCategory = null
                    }
                ) {
                    Text(if (cat.isPinned) "Unfavorite" else "Favorite")
                }
            }
        )
    }
}
