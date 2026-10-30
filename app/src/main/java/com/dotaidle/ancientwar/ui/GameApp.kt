package com.dotaidle.ancientwar.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dotaidle.ancientwar.BuildConfig
import com.dotaidle.ancientwar.ui.components.Chip
import com.dotaidle.ancientwar.ui.components.FloaterLayer
import com.dotaidle.ancientwar.ui.components.GameButton
import com.dotaidle.ancientwar.ui.components.LocalFrameClock
import com.dotaidle.ancientwar.ui.components.Muted
import com.dotaidle.ancientwar.ui.screens.BattleScreen
import com.dotaidle.ancientwar.ui.screens.HeroesScreen
import com.dotaidle.ancientwar.ui.screens.LanesScreen
import com.dotaidle.ancientwar.ui.screens.RebirthScreen
import com.dotaidle.ancientwar.ui.screens.ShopScreen
import com.dotaidle.ancientwar.ui.screens.TalentsScreen
import com.dotaidle.ancientwar.ui.theme.GameColors
import com.dotaidle.core.engine.GameEngine
import com.dotaidle.core.i18n.tr
import com.dotaidle.core.util.NumberFormat
import com.dotaidle.core.util.fmt
import kotlinx.coroutines.delay

@Composable
fun GameApp(vm: GameViewModel) {
    val frame = remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) withFrameNanos { frame.longValue = it }
    }
    CompositionLocalProvider(LocalFrameClock provides frame) {
        Box(Modifier.fillMaxSize().background(GameColors.Bg)) {
            Column(Modifier.fillMaxSize()) {
                TopBar(vm)
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when (vm.tab) {
                        Tab.LANES -> LanesScreen(vm)
                        Tab.HEROES -> HeroesScreen(vm)
                        Tab.SHOP -> ShopScreen(vm)
                        Tab.BATTLE -> BattleScreen(vm)
                        Tab.REBIRTH -> RebirthScreen(vm)
                        Tab.TALENTS -> TalentsScreen(vm)
                    }
                }
                BottomNav(vm)
            }
            TooltipOverlay(vm, Modifier.align(Alignment.BottomCenter).padding(bottom = 72.dp))
            ToastOverlay(vm, Modifier.align(Alignment.BottomCenter).padding(bottom = 72.dp))
            BannerOverlay(vm, Modifier.align(Alignment.Center))
            OfflineDialog(vm)
            if (vm.showSettings) SettingsDialog(vm)
            AdOverlay(vm)
        }
    }
}

@Composable
private fun TopBar(vm: GameViewModel) {
    vm.version
    val e = vm.engine
    val s = vm.state
    Box(Modifier.fillMaxWidth().background(GameColors.Surface)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("🪙 ${s.gold.fmt()}", color = GameColors.Gold, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(tr("per_sec", e.goldPerSec().fmt()), color = GameColors.Gold.copy(alpha = 0.8f), fontSize = 13.sp)
                    Text("⚔ ${e.teamDps().fmt()} ${tr("dps")}", color = GameColors.RedBright, fontSize = 13.sp)
                }
                Text(
                    "⚙",
                    fontSize = 26.sp,
                    color = GameColors.Text,
                    modifier = Modifier.clip(CircleShape).clickable { vm.showSettings = true }.padding(8.dp),
                )
            }
            val chips = buildList {
                if (s.buffs.goldX2Left > 0) add(tr("buff_x2", NumberFormat.duration(s.buffs.goldX2Left)) to GameColors.Gold)
                if (s.battle.aegisLeft > 0) add(tr("buff_aegis", NumberFormat.duration(s.battle.aegisLeft)) to GameColors.Blue)
                if (s.buffs.rageLeft > 0) add(tr("buff_rage", NumberFormat.duration(s.buffs.rageLeft)) to GameColors.RedBright)
                if (s.buffs.hasteLeft > 0) add(tr("buff_haste", NumberFormat.duration(s.buffs.hasteLeft)) to GameColors.Green)
            }
            if (chips.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
                    chips.forEach { (t, c) -> Chip(t, c) }
                }
            }
        }
        FloaterLayer(vm, FloatArea.TOP, Modifier.matchParentSize())
    }
}

@Composable
private fun BottomNav(vm: GameViewModel) {
    vm.version
    val e = vm.engine
    Row(Modifier.fillMaxWidth().background(GameColors.Surface).padding(vertical = 4.dp)) {
        Tab.entries.forEach { t ->
            val selected = vm.tab == t
            val badge = when (t) {
                Tab.REBIRTH -> e.canRebirth()
                Tab.TALENTS -> com.dotaidle.core.content.Talents.all.any { e.talentStatus(it.id) == GameEngine.TalentStatus.AVAILABLE }
                else -> false
            }
            Column(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (selected) GameColors.Red.copy(alpha = 0.35f) else GameColors.Surface)
                    .clickable { vm.selectTab(t) }
                    .padding(vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box {
                    Text(t.icon, fontSize = 20.sp)
                    if (badge) {
                        Box(Modifier.align(Alignment.TopEnd).size(8.dp).clip(CircleShape).background(GameColors.Gold))
                    }
                }
                Text(
                    tr(t.key),
                    fontSize = 10.sp,
                    maxLines = 1,
                    color = if (selected) GameColors.Gold else GameColors.Muted,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                )
            }
        }
    }
}

@Composable
private fun TooltipOverlay(vm: GameViewModel, modifier: Modifier) {
    val text = vm.tooltip
    LaunchedEffect(vm.tooltipNonce) {
        if (text != null) {
            delay(4000)
            vm.tooltip = null
        }
    }
    AnimatedVisibility(text != null, modifier = modifier.padding(horizontal = 16.dp), enter = fadeIn(), exit = fadeOut()) {
        Text(
            text ?: "",
            color = GameColors.Text,
            fontSize = 14.sp,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(GameColors.SurfaceHi)
                .border(1.dp, GameColors.Gold, RoundedCornerShape(10.dp))
                .clickable { vm.tooltip = null }
                .padding(14.dp),
        )
    }
}

@Composable
private fun ToastOverlay(vm: GameViewModel, modifier: Modifier) {
    val text = vm.toast
    LaunchedEffect(text) {
        if (text != null) {
            delay(3500)
            vm.toast = null
        }
    }
    if (text != null) {
        Text(
            text,
            color = GameColors.Bg,
            fontWeight = FontWeight.Bold,
            modifier = modifier
                .padding(horizontal = 24.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(GameColors.Gold)
                .padding(horizontal = 16.dp, vertical = 10.dp),
        )
    }
}

@Composable
private fun BannerOverlay(vm: GameViewModel, modifier: Modifier) {
    val b = vm.banner ?: return
    LaunchedEffect(b) {
        delay(2200)
        if (vm.banner == b) vm.banner = null
    }
    Text(
        b.first,
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(GameColors.Bg.copy(alpha = 0.85f))
            .border(2.dp, GameColors.Gold, RoundedCornerShape(12.dp))
            .padding(horizontal = 24.dp, vertical = 16.dp),
        color = GameColors.Gold,
        fontSize = 30.sp,
        fontWeight = FontWeight.ExtraBold,
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun OfflineDialog(vm: GameViewModel) {
    val r = vm.offlineReport ?: return
    AlertDialog(
        onDismissRequest = { vm.offlineReport = null },
        containerColor = GameColors.Surface,
        title = { Text(tr("welcome_back"), color = GameColors.Gold, fontWeight = FontWeight.Bold) },
        text = {
            Text(
                tr(
                    "offline_body",
                    NumberFormat.duration(r.awaySeconds),
                    NumberFormat.duration(r.creditedSeconds),
                    (vm.engine.offlineRate() * 100).toInt(),
                    r.gold.fmt(),
                    r.enemiesKilled,
                ),
                color = GameColors.Text,
            )
        },
        confirmButton = { TextButton(onClick = { vm.offlineReport = null }) { Text(tr("ok"), color = GameColors.Gold) } },
        dismissButton = { TextButton(onClick = { vm.doubleOffline() }) { Text(tr("offline_double"), color = GameColors.RedBright) } },
    )
}

@Composable
private fun SettingsDialog(vm: GameViewModel) {
    vm.version
    val s = vm.state.settings
    var confirmReset by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { vm.showSettings = false },
        containerColor = GameColors.Surface,
        title = { Text(tr("settings"), color = GameColors.Gold, fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(tr("language"), color = GameColors.Text)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("en" to "English", "ru" to "Русский").forEach { (code, label) ->
                        GameButton(
                            label,
                            onClick = { vm.setLanguage(code) },
                            modifier = Modifier.weight(1f),
                            color = if (s.language == code) GameColors.Red else GameColors.SurfaceHi,
                            minHeight = 44.dp,
                        )
                    }
                }
                SettingSwitch(tr("sound"), s.sound) { vm.setSound(it) }
                SettingSwitch(tr("music"), s.music) { vm.setMusic(it) }
                if (s.noAds) {
                    Text(tr("ads_removed"), color = GameColors.Green)
                } else {
                    GameButton(tr("remove_ads"), onClick = { vm.removeAds() }, modifier = Modifier.fillMaxWidth(), color = GameColors.GoldDim, minHeight = 44.dp)
                }
                GameButton(tr("save_now"), onClick = { vm.saveNow(sync = true); vm.toast = tr("saved") }, modifier = Modifier.fillMaxWidth(), color = GameColors.SurfaceHi, minHeight = 44.dp)
                if (confirmReset) {
                    Text(tr("reset_confirm"), color = GameColors.RedBright)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GameButton(tr("cancel"), onClick = { confirmReset = false }, modifier = Modifier.weight(1f), color = GameColors.SurfaceHi, minHeight = 44.dp)
                        GameButton(tr("ok"), onClick = { vm.resetProgress(); confirmReset = false; vm.showSettings = false }, modifier = Modifier.weight(1f), minHeight = 44.dp)
                    }
                } else {
                    GameButton(tr("reset_progress"), onClick = { confirmReset = true }, modifier = Modifier.fillMaxWidth(), minHeight = 44.dp)
                }
                Muted(tr("long_press_hint"))
                Muted(tr("version", BuildConfig.VERSION_NAME))
            }
        },
        confirmButton = { TextButton(onClick = { vm.showSettings = false }) { Text(tr("close"), color = GameColors.Gold) } },
    )
}

@Composable
private fun SettingSwitch(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = GameColors.Text, modifier = Modifier.weight(1f))
        Switch(checked = value, onCheckedChange = onChange)
    }
}

/** Placeholder ad: a full-screen countdown that blocks input, then grants the reward. */
@Composable
private fun AdOverlay(vm: GameViewModel) {
    val ad = vm.ads.current ?: return
    var left by remember(ad) { mutableIntStateOf(ad.seconds) }
    LaunchedEffect(ad) {
        while (left > 0) {
            delay(1000)
            left--
        }
        vm.ads.complete()
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(GameColors.Bg.copy(alpha = 0.96f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("📺", fontSize = 64.sp)
            Spacer(Modifier.height(12.dp))
            Text(tr("ad_placeholder"), color = GameColors.Gold, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text(
                tr(if (ad.rewarded) "ad_closing_in" else "ad_interstitial_in", left),
                color = GameColors.Text,
                fontSize = 16.sp,
            )
            if (!ad.rewarded) {
                Spacer(Modifier.height(8.dp))
                Muted(tr("ad_remove_hint"))
            }
        }
    }
}
