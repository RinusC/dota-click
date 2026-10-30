package com.dotaidle.ancientwar.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dotaidle.ancientwar.ui.GameViewModel
import com.dotaidle.ancientwar.ui.components.Chip
import com.dotaidle.ancientwar.ui.components.GameButton
import com.dotaidle.ancientwar.ui.components.GameCard
import com.dotaidle.ancientwar.ui.components.Muted
import com.dotaidle.ancientwar.ui.components.SmoothBar
import com.dotaidle.ancientwar.ui.theme.GameColors
import com.dotaidle.core.balance.Balance
import com.dotaidle.core.content.AbilityType
import com.dotaidle.core.content.Ability
import com.dotaidle.core.content.Heroes
import com.dotaidle.core.content.ItemDef
import com.dotaidle.core.content.Items
import com.dotaidle.core.i18n.tr
import com.dotaidle.core.util.NumberFormat
import com.dotaidle.core.util.fmt
import java.util.Locale

@Composable
fun HeroesScreen(vm: GameViewModel) {
    vm.version
    val e = vm.engine
    var equipFor by remember { mutableStateOf<Int?>(null) }
    LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Text(tr("team_dps", e.teamDps().fmt()), color = GameColors.RedBright, fontWeight = FontWeight.Bold, fontSize = 17.sp)
            Muted(if (e.autoCastEnabled()) tr("auto_cast_on") else tr("auto_cast_off"))
            val c = vm.state.collection.size
            Muted(tr("collection", c, Heroes.all.size, (c * Balance.COLLECTION_BONUS_PER_HERO * 100).toInt()))
        }
        items(vm.state.heroes.size) { slot -> HeroCard(vm, slot) { equipFor = slot } }
    }
    equipFor?.let { slot -> EquipDialog(vm, slot) { equipFor = null } }
}

fun itemBonusText(item: ItemDef): String = buildList {
    if (item.dmg > 0) add(tr("bonus_dmg", (item.dmg * 100).toInt()))
    if (item.gold > 0) add(tr("bonus_gold", (item.gold * 100).toInt()))
    if (item.farm > 0) add(tr("bonus_farm", (item.farm * 100).toInt()))
}.joinToString(" · ")

private fun abilityText(a: Ability, cooldown: Double): String {
    val p = String.format(Locale.US, "%.0f", a.power)
    val cd = String.format(Locale.US, "%.0f", cooldown)
    return a.name.get() + ": " + when (a.type) {
        AbilityType.NUKE -> tr("ability_nuke", p, cd)
        AbilityType.GOLD -> tr("ability_gold", p, cd)
        AbilityType.RAGE -> tr("ability_rage", p, cd)
        AbilityType.HASTE -> tr("ability_haste", p, cd)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HeroCard(vm: GameViewModel, slot: Int, onEquip: () -> Unit) {
    vm.version
    val e = vm.engine
    val h = vm.state.heroes[slot]
    val role = e.roleOf(slot)
    val def = h.heroId?.let { Heroes[it] }
    if (def == null) {
        val cost = e.hireCost()
        GameCard(accent = GameColors.Border) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Chip(role.label.get(), GameColors.Muted)
                Spacer(Modifier.size(8.dp))
                Muted(tr("empty_slot", role.label.get()))
            }
            Spacer(Modifier.height(8.dp))
            GameButton(
                tr("hire_hero", cost.fmt()), onClick = { vm.act { hireHero(slot) } },
                modifier = Modifier.fillMaxWidth(), enabled = vm.state.gold >= cost,
                tooltip = tr("tip_hire"), vm = vm,
            )
        }
        return
    }
    GameCard(accent = GameColors.GoldDim) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Chip(role.label.get(), GameColors.Gold)
            Spacer(Modifier.size(8.dp))
            Text(def.name.get(), color = GameColors.Text, fontWeight = FontWeight.Bold, fontSize = 17.sp, modifier = Modifier.weight(1f))
            Text(tr("level_short", h.level), color = GameColors.Gold, fontWeight = FontWeight.Bold)
        }
        Muted(tr("hero_dps", e.heroDps(h).fmt(), String.format(Locale.US, "%.2f", e.itemMultiplier(h))))
        Spacer(Modifier.height(6.dp))
        // 6 item slots
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            h.items.forEachIndexed { idx, id ->
                val item = id?.let { Items[it] }
                val color = item?.let { GameColors.rarity(it.rarity) } ?: GameColors.Border
                Box(
                    Modifier
                        .weight(1f)
                        .heightIn(min = 44.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(GameColors.SurfaceHi)
                        .border(1.dp, color, RoundedCornerShape(6.dp))
                        .combinedClickable(
                            onClick = { if (item != null) vm.act { unequipItem(slot, idx) } else onEquip() },
                            onLongClick = item?.let { { vm.showTooltip("${it.name.get()} (${it.rarity.label.get()}): ${itemBonusText(it)}. ${tr("tap_to_unequip")}") } },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        item?.name?.get()?.split(" ")?.joinToString("") { it.take(1) }?.take(3) ?: "+",
                        color = if (item != null) color else GameColors.Muted,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        // Ability with cooldown
        val cdMax = e.abilityCooldown(h)
        val cd = h.cooldown
        val ready = cd <= 0.0
        GameButton(
            if (ready) tr("ability_ready", def.ability.name.get()) else tr("ability_cd", def.ability.name.get(), NumberFormat.duration(cd + 0.99)),
            onClick = { vm.act { castAbility(slot) } },
            modifier = Modifier.fillMaxWidth(),
            enabled = ready,
            color = GameColors.Blue.copy(alpha = 0.7f),
            tooltip = abilityText(def.ability, cdMax),
            vm = vm,
            minHeight = 44.dp,
        )
        if (!ready) {
            SmoothBar(vm, fraction = { since -> (1.0 - (cd - since) / cdMax).toFloat() }, color = GameColors.Blue, height = 4.dp, modifier = Modifier.padding(top = 4.dp))
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val cost = e.heroLevelCost(slot)
            GameButton(
                tr("level_up", cost.fmt()), onClick = { vm.act { levelUpHero(slot) } },
                modifier = Modifier.weight(1.4f), enabled = vm.state.gold >= cost,
                tooltip = tr("tip_level_up"), vm = vm,
            )
            GameButton(
                tr("equip_item"), onClick = onEquip, modifier = Modifier.weight(1f),
                color = GameColors.SurfaceHi, enabled = vm.state.inventory.isNotEmpty(),
            )
        }
    }
}

@Composable
private fun EquipDialog(vm: GameViewModel, slot: Int, onClose: () -> Unit) {
    vm.version
    val h = vm.state.heroes[slot]
    val name = h.heroId?.let { Heroes[it]?.name?.get() } ?: ""
    val grouped = vm.state.inventory.groupingBy { it }.eachCount()
    AlertDialog(
        onDismissRequest = onClose,
        containerColor = GameColors.Surface,
        title = { Text(tr("pick_item", name), color = GameColors.Gold, fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    grouped.isEmpty() -> Muted(tr("no_items"))
                    h.items.none { it == null } -> Muted(tr("slots_full"))
                    else -> grouped.entries.sortedByDescending { Items[it.key]?.baseCost ?: 0.0 }.forEach { (id, count) ->
                        val item = Items[id] ?: return@forEach
                        GameCard(accent = GameColors.rarity(item.rarity), onClick = {
                            vm.act { equipItem(slot, id) }
                            if (vm.state.heroes[slot].items.none { it == null } || vm.state.inventory.isEmpty()) onClose()
                        }) {
                            Text("${item.name.get()} ×$count", color = GameColors.rarity(item.rarity), fontWeight = FontWeight.Bold)
                            Muted(itemBonusText(item))
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text(tr("close"), color = GameColors.Gold) } },
    )
}
