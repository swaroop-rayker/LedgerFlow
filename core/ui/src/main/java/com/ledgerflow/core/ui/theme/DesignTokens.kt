package com.ledgerflow.core.ui.theme

import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color

object Spacing {
    val xxs = 2.dp
    val xs = 4.dp
    val s = 8.dp
    val m = 12.dp
    val l = 16.dp
    val xl = 24.dp
    val xxl = 32.dp
    val xxxl = 48.dp
}

object CornerRadius {
    val s = 8.dp
    val m = 12.dp
    val l = 16.dp
    val xl = 24.dp
    val xxl = 32.dp
}

object CuratedColors {
    // 2026 sleek premium UI colors for categories
    val Red = "#EF4444"
    val Orange = "#F59E0B"
    val Amber = "#D97706"
    val Green = "#10B981"
    val Teal = "#14B8A6"
    val Blue = "#3B82F6"
    val Indigo = "#6366F1"
    val Violet = "#8B5CF6"
    val Purple = "#A855F7"
    val Pink = "#EC4899"
    val Rose = "#F43F5E"
    val Slate = "#64748B"

    val all = listOf(
        Blue to "Blue",
        Green to "Green",
        Teal to "Teal",
        Indigo to "Indigo",
        Violet to "Violet",
        Purple to "Purple",
        Amber to "Amber",
        Orange to "Orange",
        Red to "Red",
        Pink to "Pink",
        Rose to "Rose",
        Slate to "Slate"
    )

    fun getOrDefault(hex: String?, defaultColor: Color): Color {
        if (hex == null) return defaultColor
        return try {
            Color(android.graphics.Color.parseColor(hex))
        } catch (e: Exception) {
            defaultColor
        }
    }
}
