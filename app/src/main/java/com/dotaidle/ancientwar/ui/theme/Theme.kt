package com.dotaidle.ancientwar.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.dotaidle.core.content.Rarity

object GameColors {
    val Bg = Color(0xFF0E0F13)
    val Surface = Color(0xFF181A20)
    val SurfaceHi = Color(0xFF22252D)
    val Border = Color(0xFF3A2A24)
    val Red = Color(0xFFB8321F)
    val RedBright = Color(0xFFFF5A3D)
    val Gold = Color(0xFFE8B64C)
    val GoldDim = Color(0xFF8C6B2A)
    val Text = Color(0xFFE6E2D8)
    val Muted = Color(0xFF8A8F98)
    val Green = Color(0xFF5DBB63)
    val Blue = Color(0xFF4A90E2)
    val Disabled = Color(0xFF2C2E35)

    fun rarity(r: Rarity): Color = when (r) {
        Rarity.COMMON -> Color(0xFFB0B0B0)
        Rarity.RARE -> Color(0xFF4A90E2)
        Rarity.EPIC -> Color(0xFFA35BE0)
        Rarity.LEGENDARY -> Color(0xFFF0A030)
    }
}

private val scheme = darkColorScheme(
    primary = GameColors.Red,
    onPrimary = GameColors.Text,
    secondary = GameColors.Gold,
    onSecondary = GameColors.Bg,
    background = GameColors.Bg,
    onBackground = GameColors.Text,
    surface = GameColors.Surface,
    onSurface = GameColors.Text,
    surfaceVariant = GameColors.SurfaceHi,
    onSurfaceVariant = GameColors.Muted,
)

@Composable
fun DotaIdleTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, content = content)
}
