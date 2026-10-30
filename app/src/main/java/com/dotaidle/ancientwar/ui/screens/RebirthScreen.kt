package com.dotaidle.ancientwar.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dotaidle.ancientwar.ui.GameViewModel
import com.dotaidle.ancientwar.ui.components.GameButton
import com.dotaidle.ancientwar.ui.components.GameCard
import com.dotaidle.ancientwar.ui.components.Muted
import com.dotaidle.ancientwar.ui.theme.GameColors
import com.dotaidle.core.balance.Balance
import com.dotaidle.core.content.Enemies
import com.dotaidle.core.i18n.tr
import com.dotaidle.core.util.NumberFormat
import com.dotaidle.core.util.fmt

@Composable
fun RebirthScreen(vm: GameViewModel) {
    vm.version
    val e = vm.engine
    val s = vm.state
    var confirm by remember { mutableStateOf(false) }
    LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            GameCard(accent = GameColors.Gold) {
                Text(tr("mmr_current", s.mmr), color = GameColors.Gold, fontSize = 34.sp, fontWeight = FontWeight.ExtraBold)
                Text(tr("mmr_bonus", (s.mmr * Balance.MMR_BONUS_PER_POINT * 100).toInt()), color = GameColors.Text)
            }
        }
        item {
            GameCard {
                val gain = e.mmrGainPreview()
                if (e.canRebirth()) {
                    Text(tr("mmr_gain", gain, gain), color = GameColors.Gold, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                } else {
                    Muted(tr("reborn_locked", s.battle.enemyIndex % Enemies.PER_MATCH + 1, Enemies.PER_MATCH))
                }
                Spacer(Modifier.height(10.dp))
                GameButton(
                    tr("reborn"), onClick = { confirm = true }, modifier = Modifier.fillMaxWidth(),
                    enabled = e.canRebirth(), minHeight = 60.dp, fontSize = 19, tooltip = tr("mmr_formula"), vm = vm,
                )
                Spacer(Modifier.height(10.dp))
                Text(tr("reborn_resets"), color = GameColors.RedBright, fontSize = 14.sp)
                Text(tr("reborn_keeps"), color = GameColors.Green, fontSize = 14.sp)
                Muted(tr("mmr_formula"))
            }
        }
        item {
            Muted(tr("stats", s.rebirths, s.totalAncients, s.lifetimeGold.fmt(), NumberFormat.duration(s.playSeconds)))
        }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            containerColor = GameColors.Surface,
            text = { Text(tr("reborn_confirm", e.mmrGainPreview()), color = GameColors.Text) },
            confirmButton = {
                TextButton(onClick = { confirm = false; vm.rebirth() }) { Text(tr("reborn"), color = GameColors.Gold) }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text(tr("cancel"), color = GameColors.Muted) } },
        )
    }
}
