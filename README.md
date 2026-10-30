# Dota Idle: Ancient War

An idle / incremental auto-battler for Android in a MOBA style. Kotlin + Jetpack Compose, minSdk 26 (Android 8.0).
All art is original placeholders (Canvas shapes, emoji). Heroes, items and bosses use parody names. No Valve assets.

## Why Kotlin + Compose (option B)

Unity (option A) needs the editor and a licence to build, and it can't be built headlessly in CI without extra setup.
The Compose project builds with one Gradle command, and the game logic is a pure-Kotlin module.
That module is covered by unit tests and a bot balance simulation that run on any JVM, no Android SDK needed.

## Building the APK

Requirements: JDK 17 and the Android SDK (Platform 34). Android Studio Ladybug or newer installs both.

```bash
echo "sdk.dir=$HOME/Android/Sdk" > local.properties
```

```bash
./gradlew assembleDebug
```

The APK ends up at `app/build/outputs/apk/debug/app-debug.apk`.

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

In Android Studio: **Open** the repo folder, then **Build › Build App Bundle(s)/APK(s) › Build APK(s)**.
`./gradlew assembleRelease` builds a release APK, signed with the debug key so it installs without a keystore
(add your own `signingConfig` before publishing).

Game core tests and balance simulation (no Android SDK needed):

```bash
./gradlew -p core test
```

End-to-end check on a connected device or emulator (install → launch → save → offline progress → tabs → language switch):

```bash
bash scripts/smoke_test.sh app/build/outputs/apk/debug/app-debug.apk
```

### CI

`.github/workflows/android.yml` runs on every push:
1. `core` tests + balance simulation;
2. `assembleDebug`, with the APK uploaded as the **dota-idle-debug-apk** artifact;
3. an Android 11 emulator smoke test (`scripts/smoke_test.sh`), with screenshots in the **smoke-test-screenshots** artifact.

## Structure

```
core/                         pure Kotlin/JVM, a standalone Gradle build (includeBuild)
  balance/Balance.kt          ALL formulas and constants
  content/                    Heroes (13, 5 roles), Items (13, 4 rarities), Enemies (match layout), Talents (15)
  engine/GameEngine.kt        rules: tick, lanes, battle, abilities, purchases, rebirth, talents, offline
  model/GameState.kt          save model (@Serializable)
  save/SaveCodec.kt           JSON (kotlinx.serialization), tolerant of corrupt/old saves
  i18n/                       EN/RU strings
  util/NumberFormat.kt        1.23K / 45.6M / 789B / 1.00T / 1.00aa…, durations
  src/test/                   formulas, save, offline, i18n, bot balance simulation
app/                          Android
  data/SaveRepository.kt      Data: save.json in filesDir, atomic write + backup
  services/                   Services: AudioService (SoundPool/MediaPlayer + synth), AdService (+stub), BillingService (+stub)
  ui/GameViewModel.kt         10 Hz tick loop, autosave, offline, events → sounds/floating texts
  ui/GameApp.kt               scaffold: top bar, 6-tab navigation, dialogs (offline, settings, ad), tooltips
  ui/screens/                 Lanes, Heroes, Shop, Battle, Rebirth, Talents
  ui/components/              buttons, cards, 60 FPS SmoothBar, FloaterLayer (+gold)
scripts/smoke_test.sh         adb end-to-end check
```

Layers: **Core** (logic, no Android) ← **Data** (storage) ← **UI** (Compose) / **Services** (audio, ads, IAP).
Everything runs on the main thread, so the engine needs no locks.

### Tick system
- Logic runs at **10 ticks/sec** (`dt = 0.1`, accumulator with up to 5 s of catch-up).
- The UI recomposes after each tick. Progress bars, the lane and floating texts are drawn **every frame (60 FPS)**,
  extrapolating between ticks (`SmoothBar`, `LocalFrameClock`).

### Save / load
- JSON in `files/save.json` (temp file → rename, previous copy kept in `save.bak.json`).
- Autosave every **15 s** and on `onPause` (synchronous).
- A corrupt save falls back to the backup, then to a new game. Unknown fields are ignored, and `normalize()` repairs the rest.

### Offline progress
On launch/resume: `away = now − lastSaveMillis` (0 if the clock went backwards).
`credited = min(away, 8 h + talents)`. The player gets `credited × 50 %` of steady online speed (no temporary buffs):
lane gold **and** battle damage (enemies die and pay bounties). The "Dream Farm" talent raises the rate to 75 %,
and "Long Nap" adds +4 h to the cap. After ≥ 60 s away, a "Welcome back" dialog appears with an option to double the gold for a rewarded ad.

## Balance formulas (`core/.../balance/Balance.kt`)

All values are `Double`.

| What | Formula |
|---|---|
| Gold per creep | `10 × waveMultiplier^wave × lineLevel × heroDPSMultiplier × goldMult`, waveMultiplier = **1.03**, wave = lineLevel − 1 |
| heroDPSMultiplier | `1 + log10(1 + team DPS without buffs)` |
| Lane wave | 4 creeps every `6 s / farmSpeed` |
| Lane upgrade ("Upgrade Wave") | `100 × 1.15^level` |
| Hero DPS | `5 × heroLevel × itemMultiplier` × power spike `2^(level/25)` × global damage multiplier |
| itemMultiplier | `1 + Σ dmg% of the hero's 6 items` |
| Hero level | `20 × 1.13^(level−1)` |
| Hiring | 0 / 100 / 1K / 8K / 60K / 400K / 3M (by number of heroes in the team) |
| Item | `baseCost × 2.2^copies bought` (Common 150–400, Rare 2.5–5K, Epic 30–60K, Legendary 0.5–1M) |
| Enemy HP | `100 × 1.12^enemyIndex` (the index runs through matches) |
| Bounty | `0.5 × HP × goldMult` |
| goldMult | `(1 + Σ gold% of items) × (1 + talents) × (1 + 0.05 × MMR) × (×2 rewarded)` |
| Damage multiplier | `(1 + talents) × (1 + 0.05 × MMR) × (1 + 0.02 × collection) × Aegis ×2 × Rage ×2` |
| MMR for rebirth | `10 × Ancients this run + ⌊√(run gold / 1e8)⌋` (also grants as many talent points) |

A **match** is 138 enemies: creeps → T1 → creeps → enemy heroes → T2 → … → **Rosh-Khan** (#88, Aegis: ×2 damage for 60 s)
→ heroes → T3 → barracks → mega creeps → T4 ×2 → **Ancient** (#138). Then the next match starts and HP keeps growing.

The `×2 per 25 levels` hero power spike and the 138-enemy match are deliberate additions to the spec formulas.
With linear DPS against exponential costs and HP, the curve goes flat (nearly every enemy falls in the first 5 minutes, then nothing).

### Checking the curve (`BalanceSimulationTest`)
A greedy bot (buys the cheapest upgrade, casts abilities as soon as they're ready):

| Scenario | Result |
|---|---|
| Active play until the first Ancient | **~29–33 min** (target 30–40) |
| Mostly offline (5 min of play, then 1 min every 30 min) | **~2 h 08 min** (target 2–3 h) |
| Second run after a rebirth (+23 MMR, 6 talents) | ~5.5 min |

The test fails if the times fall outside 25–45 min and 1.5–3.5 h.

## Features by screen
1. **Lanes**: 3 lanes with a creep-wave animation, a progress bar to the next wave, a kill counter, "Upgrade Wave", and ad buttons.
2. **Heroes**: 5 slots (Carry, Mid, Offlane, Support ×2, plus +2 from talents), level, 6 item slots (tap to unequip),
   DPS, an ability with a cooldown (tap, or auto-cast from a talent), Level Up / Equip Item / Hire New Hero, and a hero collection.
3. **Shop**: 13 items across Common/Rare/Epic/Legendary that give damage / gold / farm speed.
4. **Battle**: the enemy with an HP bar, time to kill, bounty, an "Attack!" button (25 % of DPS), the push road map, and a battle log.
5. **Rebirth**: MMR, bonus, gain preview, and "Reborn" after the first Ancient (resets gold/heroes/items/lanes; keeps MMR, talents, collection).
6. **Talents**: 15 nodes in 3 branches (Greed / War / Utility) that cost talent points and need an MMR threshold.

Ads (stub `AdService`): rewarded "×2 gold for 5 min" (stacks up to 60 min) and "Farm 4h in 5 sec" (once per hour);
interstitials every 10 minutes of active play, removable with the stub IAP "Remove ads" in settings.
There are no paid gameplay advantages.

UI/UX: dark theme with red/gold, buttons at least 52 dp tall, "+gold" floating texts, tooltips on long tap, EN/RU in settings.

Audio: if `app/src/main/res/raw/` has `music_menu`, `music_battle`, `sfx_click`, `sfx_gold`, `sfx_kill`, `sfx_cast` or `sfx_big`
(CC0 files only), those are used. Otherwise the game synthesizes two loops (menu/battle) and the sound effects itself on first launch.

## Swapping in real SDKs
- Ads: implement `AdService` (AdMob/AppLovin) and replace `StubAdService` in `GameViewModel`. The `AdOverlay` screen is then no longer needed.
- IAP: implement `BillingService` with Google Play Billing (product `remove_ads`).
