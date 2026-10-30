package com.dotaidle.ancientwar.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dotaidle.ancientwar.ui.FloatArea
import com.dotaidle.ancientwar.ui.GameViewModel
import com.dotaidle.ancientwar.ui.components.Chip
import com.dotaidle.ancientwar.ui.components.FloaterLayer
import com.dotaidle.ancientwar.ui.components.GameButton
import com.dotaidle.ancientwar.ui.components.GameCard
import com.dotaidle.ancientwar.ui.components.Muted
import com.dotaidle.ancientwar.ui.components.SectionTitle
import com.dotaidle.ancientwar.ui.components.SmoothBar
import com.dotaidle.ancientwar.ui.theme.GameColors
import com.dotaidle.core.balance.Balance
import com.dotaidle.core.content.Enemies
import com.dotaidle.core.content.EnemyType
import com.dotaidle.core.i18n.tr
import com.dotaidle.core.util.NumberFormat
import com.dotaidle.core.util.fmt

@Composable
fun BattleScreen(vm: GameViewModel) {
    vm.version
    val e = vm.engine
    val b = vm.state.battle
    val enemy = Enemies.at(b.enemyIndex)
    val maxHp = Balance.enemyHp(b.enemyIndex)
    val hp = b.enemyHp
    val dps = e.teamDps()
    LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            val accent = when (enemy.type) {
                EnemyType.ROSHAN -> GameColors.Blue
                EnemyType.ANCIENT -> GameColors.Gold
                EnemyType.TOWER, EnemyType.BARRACKS -> GameColors.RedBright
                else -> GameColors.Border
            }
            Box {
                GameCard(accent = accent) {
                    Muted(tr("match_n", enemy.match + 1, enemy.local + 1, Enemies.PER_MATCH))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(enemy.type.icon, fontSize = 56.sp, modifier = Modifier.padding(end = 12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(enemy.name.get(), color = GameColors.Text, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                            Chip(enemy.type.label.get(), accent.takeIf { it != GameColors.Border } ?: GameColors.Muted)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    SmoothBar(vm, fraction = { since -> ((hp - dps * since) / maxHp).toFloat() }, color = GameColors.RedBright, height = 16.dp)
                    Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                        Text("${hp.fmt()} / ${maxHp.fmt()}", color = GameColors.Text, fontSize = 13.sp, modifier = Modifier.weight(1f))
                        Muted(tr("time_to_kill", if (dps > 0) NumberFormat.duration(hp / dps) else "∞"))
                    }
                    Muted(tr("bounty", (Balance.bounty(b.enemyIndex) * e.goldMultiplier()).fmt()))
                    Muted(tr("team_dps", dps.fmt()))
                }
                FloaterLayer(vm, FloatArea.BATTLE, Modifier.matchParentSize())
            }
        }
        item {
            GameButton(
                tr("attack"), onClick = { vm.tapAttack() }, modifier = Modifier.fillMaxWidth(),
                minHeight = 64.dp, fontSize = 20, tooltip = tr("tip_attack"), vm = vm,
            )
        }
        item {
            GameCard(tooltip = tr("aegis_hint"), vm = vm) {
                SectionTitle(tr("roadmap"))
                Enemies.milestones.forEach { local ->
                    val target = Enemies.at(enemy.match * Enemies.PER_MATCH + local)
                    val (mark, color) = when {
                        local < enemy.local -> "✔" to GameColors.Green
                        local == enemy.local -> "▶" to GameColors.Gold
                        else -> "•" to GameColors.Muted
                    }
                    Row(Modifier.padding(vertical = 2.dp)) {
                        Text("$mark ${target.type.icon} ${target.name.get()}", color = color, fontSize = 14.sp, modifier = Modifier.weight(1f))
                        Muted("#${local + 1}")
                    }
                }
                Muted(tr("aegis_hint"), Modifier.padding(top = 4.dp))
            }
        }
        item {
            GameCard {
                SectionTitle(tr("battle_log"))
                vm.engine.log.take(15).forEach { entry ->
                    Text("[${NumberFormat.duration(entry.time)}] ${entry.text()}", color = GameColors.Text, fontSize = 13.sp)
                }
            }
        }
    }
}
