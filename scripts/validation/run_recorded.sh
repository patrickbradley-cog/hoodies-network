#!/usr/bin/env bash
# Emulator validation loop with desktop + on-device recording.
# Usage: scripts/validation/run_recorded.sh <workstream> <avd-serial> -- <gradle args...>
# Env: JAVA_HOME, ANDROID_HOME, DISPLAY (an X server with the emulator window visible),
#      GRADLE_INIT (optional extra -I init script).
set -uo pipefail
WS="$1"; SERIAL="$2"; shift 2; [ "${1:-}" = "--" ] && shift
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
OUT="$ROOT/validation/$WS"; mkdir -p "$OUT/device-video" "$OUT/junit"
ADB="$ANDROID_HOME/platform-tools/adb -s $SERIAL"
ANN="$OUT/annotations.txt"; LOG="$OUT/gradle.log"; TL="$OUT/timeline.log"
annotate() { printf '%s' "$*" > "$ANN.tmp" && mv "$ANN.tmp" "$ANN"; echo "$(date -u +%H:%M:%S) $*" | tee -a "$TL"; }
: > "$TL"; : > "$LOG"
annotate "[$WS] START  device=$SERIAL  api=$($ADB shell getprop ro.build.version.sdk | tr -d '\r')"

# Desktop recording (emulator window left, live terminal right) with an annotation banner.
ffmpeg -loglevel error -y -f x11grab -framerate 10 -video_size "${REC_SIZE:-1920x1080}" -i "$DISPLAY" \
  -vf "drawtext=fontfile=/usr/share/fonts/truetype/dejavu/DejaVuSansMono-Bold.ttf:textfile=$ANN:reload=1:x=420:y=10:fontsize=26:fontcolor=yellow:box=1:boxcolor=black@0.7" \
  -c:v libx264 -preset veryfast -pix_fmt yuv420p "$OUT/desktop.mp4" &
FFPID=$!

# On-device screenrecord in 3-minute segments.
( i=0; while [ ! -f "$OUT/.done" ]; do i=$((i+1)); $ADB shell screenrecord --time-limit 180 "/sdcard/run_$i.mp4" >/dev/null 2>&1; done ) &
SRPID=$!

annotate "[$WS] RUN  ./gradlew $*"
xterm -geometry 150x60+400+0 -fa 'DejaVu Sans Mono' -fs 9 -T "validation $WS" -e bash -c \
  "cd '$ROOT' && ./gradlew ${GRADLE_INIT:+-I $GRADLE_INIT} $* --console=plain 2>&1 | tee '$LOG'; echo \${PIPESTATUS[0]} > '$OUT/.exit'; sleep 5"
RC=$(cat "$OUT/.exit" 2>/dev/null || echo 99)

rm -rf "$OUT/junit"/*; find "$ROOT/Hoodies-Network/build/outputs/androidTest-results/connected" -name '*.xml' -exec cp {} "$OUT/junit/" \; 2>/dev/null
SUM=$(python3 - "$OUT/junit" <<'PY'
import glob,sys,xml.etree.ElementTree as ET
t=f=s=0
for p in glob.glob(sys.argv[1]+'/*.xml'):
    for ts in ET.parse(p).getroot().iter('testsuite'):
        t+=int(ts.get('tests',0)); f+=int(ts.get('failures',0))+int(ts.get('errors',0)); s+=int(ts.get('skipped',0))
print(f"tests={t} failed={f} skipped={s}")
PY
)
annotate "[$WS] RESULT gradle_exit=$RC $SUM"
sleep 4
touch "$OUT/.done"; $ADB shell pkill -INT screenrecord >/dev/null 2>&1; sleep 3; kill $SRPID 2>/dev/null
kill -INT $FFPID; wait $FFPID 2>/dev/null
for f in $($ADB shell ls /sdcard/ | tr -d '\r' | grep '^run_.*\.mp4$'); do $ADB pull "/sdcard/$f" "$OUT/device-video/$f" >/dev/null && $ADB shell rm "/sdcard/$f"; done
rm -f "$OUT/.done" "$OUT/.exit" "$ANN.tmp"
cp -r "$ROOT/Hoodies-Network/build/reports/lint-results"* "$OUT/" 2>/dev/null || true
echo "exit=$RC; artifacts in $OUT"
exit "$RC"
