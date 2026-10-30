package com.dotaidle.ancientwar.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dotaidle.ancientwar.ui.GameViewModel
import com.dotaidle.ancientwar.ui.components.FloaterLayer
import com.dotaidle.ancientwar.ui.components.GameButton
import com.dotaidle.ancientwar.ui.components.GameCard
import com.dotaidle.ancientwar.ui.components.LocalFrameClock
import com.dotaidle.ancientwar.ui.components.Muted
import com.dotaidle.ancientwar.ui.components.SmoothBar
import com.dotaidle.ancientwar.ui.theme.GameColors
import com.dotaidle.core.balance.Balance
import com.dotaidle.core.i18n.tr
import com.dotaidle.core.util.NumberFormat
import com.dotaidle.core.util.fmt
import java.util.Locale
import kotlin.math.sin

private val laneKeys = listOf("lane_top", "lane_mid", "lane_bot")

@Composable
fun LanesScreen(vm: GameViewModel) {
    vm.version
    val e = vm.engine
    LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            val now = System.currentTimeMillis()
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GameButton(
                    tr("ad_x2"), onClick = { vm.watchGoldX2() }, modifier = Modifier.weight(1f),
                    color = GameColors.GoldDim, tooltip = tr("tip_ad_x2"), vm = vm, fontSize = 13,
                )
                val ready = e.farmAdReady(now)
                GameButton(
                    if (ready) tr("ad_farm") else tr("ad_farm_cd", NumberFormat.duration((vm.state.farmAdReadyAtMillis - now) / 1000.0)),
                    onClick = { vm.watchFarm() }, modifier = Modifier.weight(1f), enabled = ready,
                    color = GameColors.GoldDim, tooltip = tr("tip_ad_farm"), vm = vm, fontSize = 13,
                )
            }
        }
        item {
            Muted(tr("lanes_total", e.goldPerSec().fmt(), String.format(Locale.US, "%.1f", e.waveInterval())))
        }
        items(vm.state.lanes.size) { i -> LaneCard(vm, i) }
    }
}

@Composable
private fun LaneCard(vm: GameViewModel, i: Int) {
    vm.version
    val e = vm.engine
    val lane = vm.state.lanes[i]
    val interval = e.waveInterval()
    val progress = lane.progress
    val cost = e.laneUpgradeCost(i)
    GameCard {
        Row {
            Text(tr(laneKeys[i]), color = GameColors.Gold, fontWeight = FontWeight.Bold, fontSize = 17.sp, modifier = Modifier.weight(1f))
            Text(tr("per_sec", e.laneGoldPerSec(i).fmt()), color = GameColors.Gold, fontSize = 14.sp)
        }
        Muted(tr("lane_info", lane.level, lane.wave, e.goldPerCreep(i).fmt()))
        Box(Modifier.fillMaxWidth().height(64.dp)) {
            LaneView(vm, progressAt = { since -> ((progress + since / interval) % 1.0).toFloat() })
            FloaterLayer(vm, i, Modifier.matchParentSize())
        }
        SmoothBar(vm, fraction = { since -> ((progress + since / interval) % 1.0).toFloat() }, color = GameColors.Red)
        Row(Modifier.fillMaxWidth()) {
            Muted(tr("lane_killed", lane.killed.toDouble().fmt()), Modifier.weight(1f))
            Muted(tr("lane_next_wave", NumberFormat.duration((1 - progress) * interval + 0.99)))
        }
        androidx.compose.foundation.layout.Spacer(Modifier.height(8.dp))
        GameButton(
            tr("upgrade_wave", cost.fmt()),
            onClick = { vm.act { upgradeLane(i) } },
            modifier = Modifier.fillMaxWidth(),
            enabled = vm.state.gold >= cost,
            tooltip = tr("tip_upgrade_wave"),
            vm = vm,
        )
    }
}

/** Mini lane: our tower (left), the hero, and a creep wave marching in until it dies. */
@Composable
private fun LaneView(vm: GameViewModel, progressAt: (Double) -> Float) {
    val clock = LocalFrameClock.current
    Box(
        Modifier.fillMaxWidth().height(64.dp).drawBehind {
            clock.longValue
            val since = (System.nanoTime() - vm.lastTickNanos) / 1e9
            val p = progressAt(since)
            val midY = size.height / 2
            drawRect(Color(0xFF2B2219), Offset(0f, midY - 6.dp.toPx()), Size(size.width, 12.dp.toPx()))
            drawRect(GameColors.Green, Offset(4.dp.toPx(), midY - 14.dp.toPx()), Size(12.dp.toPx(), 28.dp.toPx()))
            drawRect(GameColors.Red, Offset(size.width - 16.dp.toPx(), midY - 14.dp.toPx()), Size(12.dp.toPx(), 28.dp.toPx()))
            val heroX = size.width * 0.32f
            val bob = sin(System.nanoTime() / 1.5e8).toFloat() * 2.dp.toPx()
            drawCircle(GameColors.Gold, 9.dp.toPx(), Offset(heroX, midY + bob))
            // Creeps walk from the enemy tower toward the hero and die as the wave completes.
            val front = size.width * (0.92f - 0.52f * p)
            val alpha = if (p > 0.9f) (1f - (p - 0.9f) * 10f) else 1f
            for (c in 0 until Balance.CREEPS_PER_WAVE) {
                val x = front + c * 12.dp.toPx()
                val y = midY + if (c % 2 == 0) -5.dp.toPx() else 5.dp.toPx()
                drawCircle(GameColors.RedBright.copy(alpha = alpha.coerceIn(0f, 1f)), 5.dp.toPx(), Offset(x, y))
            }
            if (p > 0.6f) { // hero attack slash
                drawLine(GameColors.Gold.copy(alpha = 0.7f), Offset(heroX, midY), Offset(front, midY), strokeWidth = 2.dp.toPx())
            }
        }
    )
}
