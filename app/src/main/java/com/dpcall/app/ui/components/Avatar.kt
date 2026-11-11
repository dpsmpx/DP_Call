package com.dpcall.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.absoluteValue

private val AvatarPalette = listOf(
    Color(0xFF1E88E5) to Color(0xFF1565C0),
    Color(0xFF26A69A) to Color(0xFF00796B),
    Color(0xFF7E57C2) to Color(0xFF5E35B1),
    Color(0xFFEC407A) to Color(0xFFC2185B),
    Color(0xFFFFA726) to Color(0xFFEF6C00),
    Color(0xFF66BB6A) to Color(0xFF2E7D32),
    Color(0xFF5C6BC0) to Color(0xFF3949AB),
    Color(0xFF8D6E63) to Color(0xFF5D4037)
)

/** Первые буквы имени (до двух слов). */
fun initialsOf(name: String): String {
    val words = name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    val letters = words.take(2).mapNotNull { w -> w.firstOrNull { it.isLetterOrDigit() } }
    return letters.joinToString("").uppercase().ifEmpty { "?" }
}

/** Круглый аватар с инициалами; цвет стабильно выводится из [seed] (кода контакта). */
@Composable
fun Avatar(name: String, seed: String, size: Dp = 44.dp, modifier: Modifier = Modifier) {
    val (top, bottom) = AvatarPalette[(seed.ifBlank { name }.hashCode().absoluteValue) % AvatarPalette.size]
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(Brush.verticalGradient(listOf(top, bottom))),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = initialsOf(name),
            color = Color.White,
            fontWeight = FontWeight.SemiBold,
            fontSize = (size.value * 0.38f).sp
        )
    }
}
