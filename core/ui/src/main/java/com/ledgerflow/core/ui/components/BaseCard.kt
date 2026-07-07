package com.ledgerflow.core.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

import androidx.compose.foundation.clickable
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import com.ledgerflow.core.ui.theme.Spacing
import com.ledgerflow.core.ui.theme.CornerRadius

@Composable
fun BaseCard(
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surface,
    borderColor: Color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f),
    contentPadding: Dp = Spacing.l,
    shape: Shape = RoundedCornerShape(CornerRadius.l),
    elevation: Dp = 0.dp,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val cardModifier = if (onClick != null) {
        modifier.clickable(onClick = onClick)
    } else {
        modifier
    }

    Card(
        modifier = cardModifier,
        colors = CardDefaults.cardColors(containerColor = containerColor),
        border = BorderStroke(1.dp, borderColor),
        shape = shape,
        elevation = CardDefaults.cardElevation(defaultElevation = elevation)
    ) {
        Column(modifier = Modifier.padding(contentPadding)) {
            content()
        }
    }
}

