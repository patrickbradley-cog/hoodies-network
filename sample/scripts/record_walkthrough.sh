#!/usr/bin/env bash
# Manual walkthrough of the :sample app with desktop + on-device recording.
# Installs the sample, launches it with `adb shell am start`, then drives every screen
# (GET, POST, Image, Cache, Interceptor) through `adb shell input`, saving a screenshot per screen.
# Usage: sample/scripts/record_walkthrough.sh <name> <avd-serial>
# Env: ANDROID_HOME, DISPLAY (X server with the emulator window visible), GRADLE_INIT (optional -I init script).
# Output: validation/<name>/{desktop.mp4,device-video/*.mp4,screens/*.png,walkthrough.log}
set -uo pipefail
NAME="$1"; SERIAL="$2"
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
OUT="$ROOT/validation/$NAME"
ADB="$ANDROID_HOME/platform-tools/adb -s $SERIAL"
PKG="com.gap.hoodies_network.sample"
ANN="$OUT/annotations.txt"; LOG="$OUT/walkthrough.log"

annotate() { printf '%s' "$*" > "$ANN.tmp" && mv "$ANN.tmp" "$ANN"; echo "$(date -u +%H:%M:%S) $*" | tee -a "$LOG"; }

ui_dump() { $ADB exec-out uiautomator dump /dev/tty 2>/dev/null | sed 's/UI hierchary dumped to.*//'; }

# Prints "x y" for the centre of the first node whose text (or content-desc) equals $1.
find_text() {
  ui_dump | python3 -c '
import re, sys, xml.etree.ElementTree as ET
want = sys.argv[1]
root = ET.fromstring(sys.stdin.read())
for n in root.iter("node"):
    if want in (n.get("text"), n.get("content-desc")):
        x1, y1, x2, y2 = map(int, re.findall(r"\d+", n.get("bounds")))
        print((x1 + x2) // 2, (y1 + y2) // 2)
        break
' "$1"
}

wait_text() {
  local deadline=$((SECONDS + ${2:-30}))
  while [ $SECONDS -lt $deadline ]; do
    [ -n "$(find_text "$1")" ] && { annotate "  saw \"$1\""; return 0; }
    sleep 1
  done
  annotate "  TIMEOUT waiting for \"$1\""; return 1
}

wait_text_prefix() {
  local deadline=$((SECONDS + ${2:-30}))
  while [ $SECONDS -lt $deadline ]; do
    if ui_dump | grep -Eq "text=[\"']$1"; then annotate "  saw \"$1...\""; return 0; fi
    sleep 1
  done
  annotate "  TIMEOUT waiting for \"$1...\""; return 1
}

tap_text() {
  local xy; xy=$(find_text "$1")
  [ -z "$xy" ] && { annotate "  could not find \"$1\""; return 1; }
  annotate "  tap \"$1\""; $ADB shell input tap $xy; sleep 1.5
}

shot() { $ADB exec-out screencap -p > "$OUT/screens/$1.png"; annotate "  screenshot screens/$1.png"; }

steps() {
  local rc=0
  annotate "[$NAME] BUILD  ./gradlew :sample:installDebug"
  (cd "$ROOT" && ./gradlew ${GRADLE_INIT:+-I $GRADLE_INIT} :sample:installDebug --console=plain 2>&1 | tail -5 | tee -a "$LOG"; exit "${PIPESTATUS[0]}") || return 1

  annotate "[$NAME] LAUNCH  adb shell am start -n $PKG/.MainActivity"
  $ADB shell am force-stop "$PKG"
  $ADB shell am start -W -n "$PKG/.MainActivity" | tee -a "$LOG"
  wait_text "Send GET /greeting" 60 || return 1

  annotate "[$NAME] SCREEN 1/5  GET"
  tap_text "GET" && tap_text "Send GET /greeting" && wait_text "Hello from the Hoodies mock server" || rc=1
  shot 1-get

  annotate "[$NAME] SCREEN 2/5  POST"
  tap_text "POST" || rc=1
  local xy; xy=$(find_text "Hello")
  if [ -n "$xy" ]; then
    $ADB shell input tap $xy; $ADB shell input keyevent KEYCODE_MOVE_END
    for _ in $(seq 1 10); do $ADB shell input keyevent KEYCODE_DEL; done
    $ADB shell input text "Walkthrough%snote"; $ADB shell input keyevent KEYCODE_BACK; sleep 1
    annotate "  typed title \"Walkthrough note\""
  else rc=1; fi
  tap_text "Send POST /echo" && wait_text "Title: Walkthrough note" && wait_text "Method: POST" || rc=1
  shot 2-post

  annotate "[$NAME] SCREEN 3/5  Image"
  tap_text "Image" && tap_text "Load GET /image" && wait_text "256 x 256 px" || rc=1
  shot 3-image

  annotate "[$NAME] SCREEN 4/5  Cache"
  tap_text "Cache" || rc=1
  tap_text "GET with cache" && wait_text "Served from: network" || rc=1
  local cached=0
  for _ in $(seq 1 10); do
    tap_text "GET with cache"
    if [ -n "$(find_text "Served from: cache")" ]; then cached=1; annotate "  saw \"Served from: cache\""; break; fi
  done
  [ $cached -eq 1 ] || rc=1
  shot 4a-cache-hit
  tap_text "GET without cache" && wait_text "Served from: network" || rc=1
  shot 4b-cache-bypass

  annotate "[$NAME] SCREEN 5/5  Interceptor"
  tap_text "Interceptor" && tap_text "Send GET /secure" && wait_text "Status: authorized" \
    && wait_text "interceptResponse: success" || rc=1
  shot 5a-interceptor-token
  local sw; sw=$(ui_dump | python3 -c '
import re, sys, xml.etree.ElementTree as ET
for n in ET.fromstring(sys.stdin.read()).iter("node"):
    if n.get("checkable") == "true":
        x1, y1, x2, y2 = map(int, re.findall(r"\d+", n.get("bounds"))); print((x1 + x2) // 2, (y1 + y2) // 2); break')
  if [ -n "$sw" ]; then annotate "  toggle token switch off"; $ADB shell input tap $sw; sleep 1.5; else rc=1; fi
  tap_text "Send GET /secure" && wait_text_prefix "HTTP 401" && wait_text "interceptError: HTTP 401" || rc=1
  shot 5b-interceptor-no-token
  return $rc
}

if [ "${3:-}" = "--steps" ]; then
  steps; echo $? > "$OUT/.exit"; sleep 3; exit 0
fi

rm -rf "$OUT"; mkdir -p "$OUT/device-video" "$OUT/screens"; : > "$LOG"
annotate "[$NAME] START  device=$SERIAL  api=$($ADB shell getprop ro.build.version.sdk | tr -d '\r')"
ffmpeg -loglevel error -y -f x11grab -framerate 10 -video_size "${REC_SIZE:-1920x1080}" -i "$DISPLAY" \
  -vf "drawtext=fontfile=/usr/share/fonts/truetype/dejavu/DejaVuSansMono-Bold.ttf:textfile=$ANN:reload=1:x=420:y=10:fontsize=26:fontcolor=yellow:box=1:boxcolor=black@0.7" \
  -c:v libx264 -preset veryfast -pix_fmt yuv420p "$OUT/desktop.mp4" &
FFPID=$!
( i=0; while [ ! -f "$OUT/.done" ]; do i=$((i+1)); $ADB shell screenrecord --time-limit 180 "/sdcard/walk_$i.mp4" >/dev/null 2>&1; done ) &
SRPID=$!

xterm -geometry 150x60+400+0 -fa 'DejaVu Sans Mono' -fs 9 -T "walkthrough $NAME" -e bash -c \
  "tail -f '$LOG' & '$0' '$NAME' '$SERIAL' --steps; kill %1"
RC=$(cat "$OUT/.exit" 2>/dev/null || echo 99)
annotate "[$NAME] RESULT walkthrough_exit=$RC"
sleep 4
touch "$OUT/.done"; $ADB shell pkill -INT screenrecord >/dev/null 2>&1; sleep 3; kill $SRPID 2>/dev/null
kill -INT $FFPID; wait $FFPID 2>/dev/null
for f in $($ADB shell ls /sdcard/ | tr -d '\r' | grep '^walk_.*\.mp4$'); do $ADB pull "/sdcard/$f" "$OUT/device-video/$f" >/dev/null && $ADB shell rm "/sdcard/$f"; done
rm -f "$OUT/.done" "$OUT/.exit" "$ANN.tmp"
echo "exit=$RC; artifacts in $OUT"
exit "$RC"
