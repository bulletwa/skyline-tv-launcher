#!/bin/bash
# For each scene: what the launcher says it measured and chose, versus what a screenshot of the same moment shows.
#   tools/inkcheck.sh day night rain ...      (no args = every scene)
cd "$(dirname "$0")/.."; export PATH=/opt/homebrew/bin:$PATH
a(){ adb -s 192.168.1.6:5555 "$@"; }
for sc in ${@:-sunrise day golden dusk night overcast rain storm fog nightfog smog snow}; do
  a shell am start -n com.tvlauncher/.MainActivity --es scene $sc >/dev/null; sleep 4.5
  a shell am broadcast -a com.tvlauncher.DUMP --es id 9 --es frame 1 >/dev/null; sleep 0.3
  a exec-out screencap -p > shots/w/ic_$sc.png; sleep 0.6
  a pull /sdcard/Android/data/com.tvlauncher/files/frame.png shots/w/frame.png >/dev/null 2>&1
  st=$(a logcat -d -s State:I | grep " 9 " | sed -E 's/.*State *: 9 //'); a logcat -c
  ink=$(echo "$st" | grep "ink:" | sed 's/ *ink: //'); tr=$(echo "$st" | sed -n 's/.*rects: text \(.*\) btn .*/\1/p'); br=$(echo "$st" | sed -n 's/.*btn \([0-9,;]*\)$/\1/p')
  echo "== $sc  logger: $ink"
  for g in "text:$tr" "btn:$br"; do n=${g%%:*}; r=${g#*:}; best=0; worst=1
    for one in ${r//;/ }; do v=$(python3 tools/rectstat.py shots/w/frame.png 2 "$one"); b=$(echo $v | sed 's/bright=\([0-9.]*\).*/\1/'); d=$(echo $v | sed 's/.*dark=\([0-9.]*\).*/\1/')
      best=$(echo "$b $best" | awk '{print ($1>$2)?$1:$2}'); worst=$(echo "$d $worst" | awk '{print ($1<$2)?$1:$2}'); done
    echo "   screen $n: bright=$best dark=$worst"; done
done
