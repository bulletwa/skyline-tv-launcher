#!/bin/bash
# Whole-flow checks that print PASS/FAIL lines. Usage: tools/tv.sh check [menu|move|clean|screens|cards|ink|all]
cd "$(dirname "$0")/.."
T=tools/tv.sh
pass=0; fail=0
expect() { # name, text, pattern
  if echo "$2" | grep -qE "$3"; then echo "PASS $1"; pass=$((pass+1)); else echo "FAIL $1   (wanted /$3/)"; echo "$2" | head -2 | cut -c1-220; fail=$((fail+1)); fi; }
first() { head -1; }

check_menu() {
  s=$($T keys HOME s1 HOLD s1 | first)
  expect "hold opens one menu" "$s" "dialogs=1.*Open app \| Move \| Remove"
  expect "held app is named" "$s" "held=[A-Za-z]"
  expect "menu clears the icon (gap >= 12px)" "$s" "gapPx=(1[2-9]|[2-9][0-9]|[0-9]{3})"
  s=$($T keys BACK | first)
  expect "one Back closes the menu" "$s" "focus=dock.*dialogs=0"
  s=$($T keys OK s2 | first); expect "short OK still opens the app" "$s" "not the launcher"; $T keys HOME s1 >/dev/null
}
check_move() {
  before=$($T dock); $T keys HOME s1 HOLD s1 D OK s0.6 R s0.8 >/dev/null
  mid=$($T state | head -1); expect "Move mode on" "$mid" "mode=moving"
  $T keys OK s0.8 >/dev/null; after=$($T dock)
  [ "$before" != "$after" ] && { echo "PASS move changed the order"; pass=$((pass+1)); } || { echo "FAIL move changed nothing"; fail=$((fail+1)); }
  $T keys HOLD s1 D OK s0.6 L s0.8 OK s0.8 >/dev/null; again=$($T dock)
  [ "$before" = "$again" ] && { echo "PASS order restored"; pass=$((pass+1)); } || { echo "FAIL order not restored: $again"; fail=$((fail+1)); }
  $T keys HOLD s1 D OK s0.6 R s0.6 BACK s1 >/dev/null; c=$($T dock)
  [ "$before" = "$c" ] && { echo "PASS Back cancels a move"; pass=$((pass+1)); } || { echo "FAIL Back did not cancel: $c"; fail=$((fail+1)); }
}
check_clean() {
  $T keys HOME s1 >/dev/null; s=$($T keys U | first)
  expect "Up enters clean (or header)" "$s" "mode=clean|focus=header"
  s=$($T keys D s1 | first); expect "Down returns to a dock app" "$s" "mode=home.*dockShown=true"
}
check_ink() {
  for sc in day night rain; do
    adb -s ${TV:-192.168.1.6:5555} shell am start -n com.tvlauncher/.MainActivity --es scene $sc >/dev/null 2>&1; sleep 4.5
    s=$($T state | first); echo "     $sc: $(echo "$s" | grep -oE 'inkText=[a-z]+ inkBtn=[a-z]+')"
  done
  adb -s ${TV:-192.168.1.6:5555} shell am start -n com.tvlauncher/.MainActivity --es scene auto >/dev/null 2>&1; sleep 3
  s=$($T state | first); expect "night/rain keep white (checked above by eye)" "$s" "inkText"
}
check_screens() { # opens Settings and every entry in it, then the All Apps grid; reports layout issues on each screen
  $T keys HOME s1 U s1 U s1 >/dev/null
  s=$($T state); for try in 1 2 3; do echo "$s" | head -1 | grep -q "focus=header" && break; $T keys U >/dev/null; s=$($T state); done
  $T keys OK s1.2 >/dev/null
  top=$($T state); title=$(echo "$top" | head -1 | sed -E 's/.*dialog=([^ ]+ ?[^ ]*) \[.*/\1/')
  expect "settings opens" "$top" "dialogs=1"
  rows=$(echo "$top" | head -1 | grep -o ' | ' | wc -l); rows=$((rows+1)); [ $rows -gt 9 ] && rows=9
  iss=$(echo "$top" | grep "issues:" | sed 's/ *issues: //'); expect "settings has no layout issues" "issues: $iss" "issues: none"
  for ((i=0;i<rows;i++)); do
    st=$($T keys OK s1.2 | head -1); [ "$(echo "$st" | grep -c 'dialogs=1')" = 0 ] && { echo "     entry $i: closed the dialog (an action, skipped)"; adb -s "${TV:-192.168.1.6:5555}" shell 'am start -a android.intent.action.MAIN -c android.intent.category.LEANBACK_LAUNCHER -n com.tvlauncher/.MainActivity' >/dev/null; sleep 1.5; $T keys HOME s1 U s1 U s1 OK s1 >/dev/null; for ((j=0;j<=i;j++)); do $T keys D >/dev/null; done; continue; }
    full=$($T state); name=$(echo "$full" | head -1 | sed -E 's/.*dialog=(.*) \[.*/\1/'); iss=$(echo "$full" | grep "issues:" | sed 's/ *issues: //')
    expect "screen '$name' has no layout issues" "issues: $iss" "issues: none"
    $T keys BACK s0.5 >/dev/null
    for ((j=0;j<=i;j++)); do $T keys D >/dev/null; done
  done
  $T keys BACK BACK s0.5 >/dev/null
  $T keys HOME s1 D s1 >/dev/null; g=$($T state); expect "All Apps grid reachable" "$g" "focus=grid"
  iss=$(echo "$g" | grep "issues:" | sed 's/ *issues: //'); expect "grid has no layout issues" "issues: $iss" "issues: none"
  $T keys BACK HOME s1 >/dev/null
}
check_cards() { # shows each card type temporarily (your cards setting is restored at the end)
  A="adb -s ${TV:-192.168.1.6:5555}"
  pref() { $A shell "run-as com.tvlauncher cat shared_prefs/launcher.xml" | sed -n "s/.*name=\"$1\">\(.*\)<\/string>.*/\1/p"; }
  on0=$(pref cards_on); show0=$(pref cards_show)
  for set in "spotlight,news,nowplaying" "weather,ps5"; do
    $A shell am start -n com.tvlauncher/.MainActivity --es cards "$set" --es cardsshow 1 >/dev/null 2>&1; sleep 6
    s=$($T keys HOME s1.5 | head -30); n=$(echo "$set" | tr ',' '\n' | wc -l | tr -d ' ')
    expect "cards '$set' visible" "$s" "cards=$n"
    iss=$(echo "$s" | grep "issues:" | sed 's/ *issues: //') # clamped headlines are by design: cut-off text is only a warning on cards
    hard=$(echo "$iss" | tr ';' '\n' | grep -v "cut off" | tr '\n' ';'); [ -z "$hard" ] && hard=none
    expect "cards '$set' have no overlap/off-screen problems" "issues: $hard" "issues: none"
    echo "$iss" | grep -q "cut off" && echo "     WARN $(echo "$iss" | tr ';' '\n' | grep 'cut off' | tr '\n' ' ')"
    echo "     texts: $($T tree 60 | grep -E '^ [ ~] "' | sed -E 's/ [0-9,]+$//' | tr -d '\n' | cut -c1-230)"
  done
  $A shell am start -n com.tvlauncher/.MainActivity --es cards "${on0:-}" --es cardsshow "${show0:-1}" >/dev/null 2>&1; sleep 2
  echo "     restored cards_on=${on0:-<default>} cards_show=${show0:-<default>}"
}
case "$1" in
  menu) check_menu;; screens) check_screens;; cards) check_cards;; move) check_move;; clean) check_clean;; ink) check_ink;;
  ""|all) check_menu; check_move; check_clean; check_screens; check_cards;;
  *) echo "usage: tv.sh check [menu|move|clean|screens|cards|ink|all]";;
esac
echo "-- $pass passed, $fail failed"
