package com.dotaidle.ancientwar.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dotaidle.ancientwar.ui.GameViewModel
import com.dotaidle.ancientwar.ui.components.GameCard
import com.dotaidle.ancientwar.ui.components.Muted
import com.dotaidle.ancientwar.ui.theme.GameColors
import com.dotaidle.core.content.Talents
import com.dotaidle.core.engine.GameEngine.TalentStatus
import com.dotaidle.core.i18n.tr

@Composable
fun TalentsScreen(vm: GameViewModel) {
    vm.version
    val e = vm.engine
    Column(Modifier.verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(tr("talent_points", vm.state.talentPoints, vm.state.mmr), color = GameColors.Gold, fontWeight = FontWeight.Bold, fontSize = 17.sp)
        Muted(tr("talents_hint"))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (column in 0..2) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        tr("branch_$column"), color = GameColors.Gold, fontWeight = FontWeight.Bold,
                        modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
                    )
                    Talents.all.filter { it.column == column }.sortedBy { it.tier }.forEach { t ->
                        val status = e.talentStatus(t.id)
                        val accent = when (status) {
                            TalentStatus.OWNED -> GameColors.Green
                            TalentStatus.AVAILABLE -> GameColors.Gold
                            TalentStatus.NO_POINTS -> GameColors.GoldDim
                            TalentStatus.LOCKED -> GameColors.Border
                        }
                        GameCard(
                            modifier = Modifier.heightIn(min = 96.dp),
                            accent = accent,
                            tooltip = "${t.name.get()} — ${tr("talent_cost", t.cost, t.mmrRequired)}",
                            vm = vm,
                            onClick = { vm.act { buyTalent(t.id) } },
                        ) {
                            Text(
                                t.name.get(),
                                color = if (status == TalentStatus.LOCKED) GameColors.Muted else GameColors.Text,
                                fontSize = 12.sp, fontWeight = FontWeight.Bold,
                            )
                            Text(
                                if (status == TalentStatus.OWNED) tr("talent_owned") else tr("talent_cost", t.cost, t.mmrRequired),
                                color = accent.takeIf { status != TalentStatus.LOCKED } ?: GameColors.Muted,
                                fontSize = 11.sp,
                                modifier = Modifier.align(Alignment.Start).padding(top = 4.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
