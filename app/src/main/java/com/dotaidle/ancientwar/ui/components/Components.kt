package com.dotaidle.ancientwar.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableLongState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dotaidle.ancientwar.ui.GameViewModel
import com.dotaidle.ancientwar.ui.theme.GameColors

/** Frame clock (updated every display frame, ~60 FPS). Read it only inside draw lambdas. */
val LocalFrameClock = staticCompositionLocalOf<MutableLongState> { error("no frame clock") }

private val shape = RoundedCornerShape(10.dp)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GameButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    color: Color = GameColors.Red,
    tooltip: String? = null,
    vm: GameViewModel? = null,
    minHeight: Dp = 52.dp,
    fontSize: Int = 15,
) {
    val bg = if (enabled) color else GameColors.Disabled
    Box(
        modifier
            .heightIn(min = minHeight)
            .clip(shape)
            .background(bg)
            .border(1.dp, if (enabled) GameColors.Gold.copy(alpha = 0.45f) else GameColors.Border, shape)
            .combinedClickable(
                onClick = { if (enabled) onClick() },
                onLongClick = tooltip?.let { t -> { vm?.showTooltip(t) } },
            )
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = if (enabled) GameColors.Text else GameColors.Muted,
            fontWeight = FontWeight.Bold,
            fontSize = fontSize.sp,
            textAlign = TextAlign.Center,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GameCard(
    modifier: Modifier = Modifier,
    tooltip: String? = null,
    vm: GameViewModel? = null,
    accent: Color = GameColors.Border,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    var m = modifier
        .fillMaxWidth()
        .clip(shape)
        .background(GameColors.Surface)
        .border(1.dp, accent, shape)
    if (tooltip != null || onClick != null) {
        m = m.combinedClickable(onClick = { onClick?.invoke() }, onLongClick = tooltip?.let { t -> { vm?.showTooltip(t) } })
    }
    Column(m.padding(12.dp), content = content)
}

/**
 * Progress bar redrawn every frame. [fraction] receives "seconds since last tick" so callers can
 * extrapolate between 10 Hz logic ticks for a smooth 60 FPS animation.
 */
@Composable
fun SmoothBar(
    vm: GameViewModel,
    fraction: (sinceTick: Double) -> Float,
    modifier: Modifier = Modifier,
    color: Color = GameColors.Gold,
    height: Dp = 10.dp,
) {
    val clock = LocalFrameClock.current
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .drawBehind {
                clock.longValue // subscribe to frames (draw phase only, no recomposition)
                val since = (System.nanoTime() - vm.lastTickNanos) / 1e9
                val f = fraction(since).coerceIn(0f, 1f)
                val r = CornerRadius(size.height / 2)
                drawRoundRect(GameColors.Disabled, cornerRadius = r)
                drawRoundRect(color, size = Size(size.width * f, size.height), cornerRadius = r)
            }
    )
}

/** Draws "+gold" texts that float up and fade for the given area. */
@OptIn(ExperimentalTextApi::class)
@Composable
fun FloaterLayer(vm: GameViewModel, area: Int, modifier: Modifier = Modifier) {
    val clock = LocalFrameClock.current
    val measurer = rememberTextMeasurer()
    Box(
        modifier.drawBehind {
            clock.longValue
            val now = System.nanoTime()
            for (f in vm.floaters) {
                if (f.area != area) continue
                val age = (now - f.bornNanos) / 1e9f
                if (age < 0 || age > 1.2f) continue
                val alpha = (1f - age / 1.2f).coerceIn(0f, 1f)
                val layout = measurer.measure(
                    f.text,
                    TextStyle(color = f.color.copy(alpha = alpha), fontSize = 18.sp, fontWeight = FontWeight.ExtraBold),
                )
                val x = size.width * (0.35f + 0.4f * f.xJitter) - layout.size.width / 2
                val y = size.height * 0.55f - age * 70.dp.toPx() - layout.size.height / 2
                drawText(layout, topLeft = Offset(x, y))
            }
        }
    )
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier.padding(top = 4.dp, bottom = 2.dp),
        color = GameColors.Gold,
        fontWeight = FontWeight.Bold,
        fontSize = 17.sp,
    )
}

@Composable
fun Muted(text: String, modifier: Modifier = Modifier, size: Int = 13) {
    Text(text, modifier = modifier, color = GameColors.Muted, fontSize = size.sp)
}

@Composable
fun Chip(text: String, color: Color) {
    Text(
        text,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.18f))
            .border(1.dp, color.copy(alpha = 0.6f), RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
        color = color,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
    )
}

@Composable
fun Spacer8() = androidx.compose.foundation.layout.Spacer(Modifier.height(8.dp))
