#!/bin/bash
# Low-output helper for driving the TV. Every command prints a few lines of text, never an image unless asked.
#   tools/tv.sh state              what is on screen, as text (focus, mode, dialog rows, dock order, texts)
#   tools/tv.sh keys U D OK s1 ... send keys, then print the state. U D L R OK BACK HOME MENU HOLD; sN = wait N s
#   tools/tv.sh tree [n]           every visible text + rect on screen or in the open dialog (* focus, ~ cut off)
#   tools/tv.sh shot [width] [l,t,r,b]  small JPEG (default 480px) of the screen or a crop (screen px) in shots/w/last.jpg; use when text can't settle it
#   tools/tv.sh deploy             build + install, prints one line (or the compile errors)
#   tools/tv.sh test               unit tests, prints one line (or the failures)
#   tools/tv.sh dock               saved dock order, readable
#   tools/tv.sh logs               new errors / launcher stats since the last call, at most 12 lines
#   tools/tv.sh check [menu|move|clean|screens|cards|ink|all]  whole-flow PASS/FAIL checks (tools/scenarios.sh)
#   tools/tv.sh perf               frame time, launcher CPU and memory
cd "$(dirname "$0")/.."
export PATH=/opt/homebrew/bin:$PATH
S=${TV:-192.168.1.6:5555}
A="adb -s $S"
P=com.tvlauncher

state() {
  local id=$RANDOM top
  top=$($A shell dumpsys activity activities | grep -m1 mResumedActivity | sed -E 's/.* ([a-zA-Z0-9_.]+\/[^ ]+) .*/\1/')
  if [ "$top" != "$P/.MainActivity" ]; then
    case "$top" in *[Dd]ream*|*[Ss]creensaver*) echo "foreground: $top (SCREENSAVER: the next key only wakes it)";; *) echo "foreground: $top (not the launcher)";; esac
    return
  fi
  $A shell am broadcast -a com.tvlauncher.DUMP --es id $id >/dev/null
  sleep 0.4
  $A logcat -d -s State:I | grep " $id " | sed -E "s/.*State *: $id //" | grep -v '#T#' | head -25
}

key() {
  case "$1" in
    U) $A shell input keyevent KEYCODE_DPAD_UP;; D) $A shell input keyevent KEYCODE_DPAD_DOWN;;
    L) $A shell input keyevent KEYCODE_DPAD_LEFT;; R) $A shell input keyevent KEYCODE_DPAD_RIGHT;;
    OK) $A shell input keyevent KEYCODE_DPAD_CENTER;; BACK) $A shell input keyevent KEYCODE_BACK;;
    HOME) $A shell input keyevent KEYCODE_HOME; sleep 2.5;; MENU) $A shell input keyevent KEYCODE_MENU;;
    HOLD) $A shell input keyevent --longpress KEYCODE_DPAD_CENTER;;
    s*) sleep "${1#s}"; return;;
    *) echo "unknown key $1"; return;;
  esac
  sleep 0.7
}

case "$1" in
  state) state;;
  tree) # every visible text with its screen rectangle (* focused, ~ cut off); works for any screen or dialog
    id=$RANDOM; $A shell am broadcast -a com.tvlauncher.DUMP --es id $id --es tree 1 >/dev/null; sleep 0.5
    $A logcat -d -s State:I | grep " $id #T#" | sed -E "s/.*State *: $id #T#//" | head -${2:-40};;
  keys) shift
    # a screensaver swallows the first key; wake it and go Home first, so the keys land on the launcher
    if $A shell dumpsys activity activities | grep -m1 mResumedActivity | grep -qiE "dream|screensaver"; then
      $A shell input keyevent KEYCODE_WAKEUP; sleep 1; $A shell input keyevent KEYCODE_HOME; sleep 2.5
    fi
    for k in "$@"; do key "$k"; done; state;;
  shot)
    mkdir -p shots/w; $A exec-out screencap -p > shots/w/last.png
    if [ -n "$3" ]; then # crop to l,t,r,b (screen px): pay only for the part that matters
      IFS=, read -r l t r b <<< "$3"
      sips -c $((b-t)) $((r-l)) --cropOffset $t $l shots/w/last.png --out shots/w/last.png >/dev/null
    fi
    sips -Z "${2:-480}" -s format jpeg -s formatOptions 55 shots/w/last.png --out shots/w/last.jpg >/dev/null
    echo "$(pwd)/shots/w/last.jpg";;
  deploy)
    out=$(tools/deploy.sh "$S" 2>&1); rc=$?
    if [ $rc -ne 0 ]; then echo "$out" | grep -E "error|FAILED|warning: \[" | head -12; echo "deploy FAILED"; else echo "$out" | grep -E "accessibility|Success" | tr '\n' ' '; echo; fi;;
  test)
    out=$(JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew testDebugUnitTest -q 2>&1); rc=$?
    if [ $rc -eq 0 ]; then echo "tests: all pass"; else echo "$out" | grep -E "tests completed|error:" | head -3; grep -h -B1 "<failure" app/build/test-results/testDebugUnitTest/*.xml | grep -oE 'testcase name="[^"]*" classname="[^"]*"|message="[^"]{0,120}' | head -8; fi;;
  dock)
    $A shell "run-as $P cat shared_prefs/launcher.xml" | sed -n 's/.*name="dock">\(.*\)<\/string>.*/\1/p' | tr ',' '\n' | sed 's/.*\.//' | tr '\n' ' '; echo;;
  logs)
    $A logcat -d -t 400 -v time AndroidRuntime:E ActivityManager:E "SkyView:I" "Focus:D" "HomeWatcher:I" "*:S" \
      | grep -E "FATAL|Exception|ANR|HomeWatcher|SkyView.*(frames in)" | tail -12 | cut -c1-170
    $A logcat -c;;
  check) shift; tools/scenarios.sh "$@";;
  perf)
    $A logcat -d -s SkyView:I | tail -1 | cut -c1-120
    $A shell top -b -n 1 | grep -E "com.tvlauncher" | head -1
    $A shell dumpsys meminfo $P | grep -E "TOTAL PSS|TOTAL:" | head -1;;
  *) sed -n 2,14p "$0";;
esac
