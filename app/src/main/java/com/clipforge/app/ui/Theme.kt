package com.clipforge.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Dp
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

val Bg = Color(0xFF0A0F24)
val Bg2 = Color(0xFF141B3A)          // solid surfaces (bars, sheets, dialogs)
val CardBg = Color(0xFF1E2750)       // solid raised surface (selected tab, track)
val TextPrimary = Color(0xFFFFFFFF)
val TextSecondary = Color(0xFFB4BBD6)
val Accent = Color(0xFF0A84FF)       // iOS blue
val Accent2 = Color(0xFF64D2FF)      // iOS cyan
val Danger = Color(0xFFFF6B6B)

val AccentBrush = Brush.horizontalGradient(listOf(Accent, Accent2))

// Frosted-glass look: translucent white fill with a bright edge, over the colourful backdrop.
val GlassFill = Color(0x1FFFFFFF)
val GlassFillStrong = Color(0x2EFFFFFF)
private val GlassEdge = Brush.linearGradient(listOf(Color(0x66FFFFFF), Color(0x14FFFFFF), Color(0x33FFFFFF)))

/** A frosted-glass panel: rounded, translucent, with a light border. */
fun Modifier.glass(radius: Dp, strong: Boolean = false): Modifier {
    val shape = RoundedCornerShape(radius)
    return this
        .clip(shape)
        .background(if (strong) GlassFillStrong else GlassFill)
        .border(1.dp, GlassEdge, shape)
}

/** Deep blue-violet backdrop with soft glowing blobs, so the glass panels have something to frost. */
fun Modifier.appBackdrop(): Modifier = this.drawBehind {
    drawRect(Brush.verticalGradient(listOf(Color(0xFF0A0F24), Color(0xFF111738), Color(0xFF0B1B33))))
    drawCircle(
        Brush.radialGradient(listOf(Color(0x660A84FF), Color.Transparent), center = Offset(size.width * 0.15f, size.height * 0.12f), radius = size.width * 0.8f),
        radius = size.width * 0.8f, center = Offset(size.width * 0.15f, size.height * 0.12f)
    )
    drawCircle(
        Brush.radialGradient(listOf(Color(0x55BF5AF2), Color.Transparent), center = Offset(size.width * 0.95f, size.height * 0.5f), radius = size.width * 0.7f),
        radius = size.width * 0.7f, center = Offset(size.width * 0.95f, size.height * 0.5f)
    )
    drawCircle(
        Brush.radialGradient(listOf(Color(0x4430D5C8), Color.Transparent), center = Offset(size.width * 0.2f, size.height * 0.95f), radius = size.width * 0.75f),
        radius = size.width * 0.75f, center = Offset(size.width * 0.2f, size.height * 0.95f)
    )
}

@Composable
fun ClipForgeTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Accent,
            onPrimary = Color.White,
            secondary = Accent2,
            background = Bg,
            onBackground = TextPrimary,
            surface = Bg2,
            onSurface = TextPrimary,
            surfaceVariant = CardBg,
            onSurfaceVariant = TextSecondary,
            error = Danger,
            outline = Color(0x40FFFFFF)
        ),
        shapes = Shapes(
            small = RoundedCornerShape(14.dp),
            medium = RoundedCornerShape(20.dp),
            large = RoundedCornerShape(28.dp)
        ),
        content = content
    )
}
