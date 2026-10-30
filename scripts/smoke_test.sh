#!/usr/bin/env bash
# End-to-end check on a device/emulator:
#   1. the APK installs and launches without crashing,
#   2. the game autosaves on pause (save.json exists, gold > 0),
#   3. offline progress is credited after rewinding the save clock by 2 hours,
#   4. every tab opens.
# Usage: scripts/smoke_test.sh path/to/app-debug.apk [artifact-dir]
set -euo pipefail

APK="${1:-app/build/outputs/apk/debug/app-debug.apk}"
OUT="${2:-smoke-artifacts}"
PKG=com.dotaidle.ancientwar
SAVE=/data/data/$PKG/files/save.json
mkdir -p "$OUT"

fail() { echo "SMOKE FAIL: $*"; adb logcat -d | grep -E "AndroidRuntime|$PKG" | tail -80 || true; exit 1; }
shot() { adb exec-out screencap -p > "$OUT/$1.png" || true; }
ui_dump() { adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1 || true; adb pull /sdcard/ui.xml "$OUT/$1.xml" >/dev/null 2>&1 || true; }
read_save() { adb shell run-as $PKG cat $SAVE; }
json_num() { python3 -c "import json,sys; print(json.load(sys.stdin)['$1'])"; }
alive() { adb shell pidof $PKG >/dev/null 2>&1; }

# Tap the centre of the first node whose text contains $1 (uses the accessibility tree).
tap_text() {
  ui_dump tmp
  local xy
  xy=$(python3 - "$OUT/tmp.xml" "$1" <<'PY'
import re, sys
xml = open(sys.argv[1], encoding="utf-8").read()
for m in re.finditer(r'<node [^>]*>', xml):
    node = m.group(0)
    t = re.search(r' text="([^"]*)"', node)
    if t and sys.argv[2] in t.group(1):
        b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', node)
        x1, y1, x2, y2 = map(int, b.groups())
        print((x1 + x2) // 2, (y1 + y2) // 2)
        break
PY
)
  [ -n "$xy" ] || fail "text '$1' not found on screen"
  adb shell input tap $xy
}

adb logcat -c
echo "== install"
adb install -r "$APK"

echo "== first launch"
adb shell am start -W -n $PKG/.MainActivity
sleep 15
alive || fail "app is not running after launch"
shot 01_first_launch
ui_dump 01_first_launch
grep -q "Upgrade Wave" "$OUT/01_first_launch.xml" || fail "lanes screen not rendered"

echo "== play a bit: upgrade a lane"
tap_text "Upgrade Wave" || true
sleep 3

echo "== every tab opens"
for tab in Heroes Shop Battle Rebirth Talents Lanes; do
  tap_text "$tab"
  sleep 2
  alive || fail "crash after opening tab $tab"
  shot "02_tab_$tab"
done

echo "== save on pause"
adb shell input keyevent KEYCODE_HOME
sleep 3
read_save > "$OUT/save_after_pause.json" || fail "save.json missing after onPause"
GOLD1=$(json_num runGold < "$OUT/save_after_pause.json")
LAST=$(json_num lastSaveMillis < "$OUT/save_after_pause.json")
echo "runGold=$GOLD1 lastSaveMillis=$LAST"
python3 -c "import sys; sys.exit(0 if float('$GOLD1') > 0 and int('$LAST') > 0 else 1)" || fail "save has no progress"

echo "== offline progress: rewind save clock by 2h and relaunch"
adb shell am force-stop $PKG
NEW=$((LAST - 2 * 3600 * 1000))
adb shell "run-as $PKG sed -i 's/\"lastSaveMillis\":[0-9]*/\"lastSaveMillis\":$NEW/' $SAVE"
adb shell am start -W -n $PKG/.MainActivity
sleep 8
alive || fail "app crashed on relaunch"
shot 03_offline_dialog
ui_dump 03_offline_dialog
grep -q "Welcome back" "$OUT/03_offline_dialog.xml" || fail "offline progress dialog not shown"
grep -o 'text="You were away[^"]*"' "$OUT/03_offline_dialog.xml" || true
tap_text "OK"
sleep 2
adb shell input keyevent KEYCODE_HOME
sleep 3
read_save > "$OUT/save_after_offline.json"
GOLD2=$(json_num runGold < "$OUT/save_after_offline.json")
echo "runGold before=$GOLD1 after offline=$GOLD2"
python3 -c "import sys; sys.exit(0 if float('$GOLD2') > float('$GOLD1') * 1.5 else 1)" || fail "offline gold was not credited"

echo "== language switch persists"
adb shell am start -W -n $PKG/.MainActivity
sleep 4
tap_text "⚙"
sleep 1
tap_text "Русский"
sleep 1
ui_dump 04_settings_ru
grep -q "Настройки" "$OUT/04_settings_ru.xml" || fail "settings not translated to Russian"
adb shell input keyevent KEYCODE_BACK
sleep 1
ui_dump 04_russian
grep -q "Улучшить волну" "$OUT/04_russian.xml" || fail "lanes screen not translated to Russian"
shot 04_russian
adb shell input keyevent KEYCODE_HOME
sleep 2
read_save | grep -q '"language":"ru"' || fail "language not saved"

if adb logcat -d | grep -q "FATAL EXCEPTION"; then fail "fatal exception in logcat"; fi
echo "SMOKE OK: launch, autosave, offline progress, tabs, settings"
