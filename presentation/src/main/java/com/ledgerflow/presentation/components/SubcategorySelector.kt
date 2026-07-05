package com.ledgerflow.presentation.components

import androidx.compose.animation.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.ledgerflow.domain.model.Category

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubcategorySelector(
    subcategories: List<Category>,
    selectedSubcategoryName: String?,
    onSubcategorySelected: (String?) -> Unit,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()

    AnimatedVisibility(
        visible = subcategories.isNotEmpty(),
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "Subcategory",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(scrollState),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                subcategories.forEach { subcat ->
                    val isSelected = subcat.name.equals(selectedSubcategoryName, ignoreCase = true)

                    FilterChip(
                        selected = isSelected,
                        onClick = {
                            onSubcategorySelected(if (isSelected) null else subcat.name)
                        },
                        label = {
                            Text(
                                text = "${subcat.icon ?: "🔹"} ${subcat.name}",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        },
                        leadingIcon = if (isSelected) {
                            {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        } else null,
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer,
                            selectedLeadingIconColor = MaterialTheme.colorScheme.onSecondaryContainer,
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                            labelColor = MaterialTheme.colorScheme.onSurfaceVariant
                        ),
                        elevation = if (isSelected) FilterChipDefaults.filterChipElevation(elevation = 4.dp) else null,
                        modifier = Modifier.semantics {
                            contentDescription = "Subcategory ${subcat.name}, ${if (isSelected) "selected" else "not selected"}"
                        }
                    )
                }
            }
        }
    }
}
