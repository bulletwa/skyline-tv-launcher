#!/bin/bash
# Build, install on the TV, apply grants and make sure the Home-watcher accessibility service is bound.
# Usage: tools/deploy.sh [tv-ip:port]   (default 192.168.1.6:5555)
set -e
cd "$(dirname "$0")/.."
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
export PATH=$JAVA_HOME/bin:/opt/homebrew/bin:$PATH
S=${1:-192.168.1.6:5555}
P=com.tvlauncher
SVC=$P/$P.HomeWatcherService

./gradlew assembleDebug -q
adb connect "$S" >/dev/null
adb -s "$S" install -r app/build/outputs/apk/debug/app-debug.apk | tail -1
adb -s "$S" shell appops set $P SYSTEM_ALERT_WINDOW allow
adb -s "$S" shell pm grant $P android.permission.WRITE_SECURE_SETTINGS 2>/dev/null || true

# The service only binds reliably while our process is alive, so start the app first.
adb -s "$S" shell am start -n $P/.MainActivity >/dev/null
sleep 2

bound() { adb -s "$S" shell dumpsys accessibility | grep -q "Bound services:{Service\[label=Skyline Home"; }
if ! bound; then
  # Re-add (append, never overwrite) and force a rebind.
  CUR=$(adb -s "$S" shell settings get secure enabled_accessibility_services | tr -d '\r')
  case "$CUR" in
    *"$SVC"*) NEW=$CUR ;;
    null|"") NEW=$SVC ;;
    *) NEW="$CUR:$SVC" ;;
  esac
  adb -s "$S" shell settings delete secure enabled_accessibility_services >/dev/null
  sleep 1
  adb -s "$S" shell settings put secure enabled_accessibility_services "$NEW"
  adb -s "$S" shell settings put secure accessibility_enabled 1
  sleep 3
fi
bound && echo "accessibility: bound" || echo "accessibility: NOT bound"

adb -s "$S" shell am start -n $P/.MainActivity >/dev/null
