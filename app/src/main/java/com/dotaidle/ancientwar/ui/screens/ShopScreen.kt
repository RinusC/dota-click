package com.dotaidle.ancientwar.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dotaidle.ancientwar.ui.GameViewModel
import com.dotaidle.ancientwar.ui.components.Chip
import com.dotaidle.ancientwar.ui.components.GameButton
import com.dotaidle.ancientwar.ui.components.GameCard
import com.dotaidle.ancientwar.ui.components.Muted
import com.dotaidle.ancientwar.ui.theme.GameColors
import com.dotaidle.core.content.Items
import com.dotaidle.core.i18n.tr
import com.dotaidle.core.util.fmt

@Composable
fun ShopScreen(vm: GameViewModel) {
    vm.version
    val e = vm.engine
    LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Muted(tr("shop_hint"))
            Spacer(Modifier.height(4.dp))
            Text(tr("inventory_count", vm.state.inventory.size), color = GameColors.Gold, fontWeight = FontWeight.Bold)
        }
        items(Items.all, key = { it.id }) { item ->
            val color = GameColors.rarity(item.rarity)
            val cost = e.itemCost(item.id)
            GameCard(accent = color.copy(alpha = 0.6f), tooltip = "${item.name.get()}: ${itemBonusText(item)}", vm = vm) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(item.name.get(), color = color, fontWeight = FontWeight.Bold, fontSize = 17.sp, modifier = Modifier.weight(1f))
                    Chip(item.rarity.label.get(), color)
                }
                Text(itemBonusText(item), color = GameColors.Text, fontSize = 14.sp)
                Muted(tr("owned", vm.state.itemsBought[item.id] ?: 0, vm.state.inventory.count { it == item.id }))
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.width(0.dp).weight(1f))
                    GameButton(
                        tr("buy", cost.fmt()), onClick = { vm.act { buyItem(item.id) } },
                        enabled = vm.state.gold >= cost, modifier = Modifier.weight(1.2f),
                    )
                }
            }
        }
    }
}
