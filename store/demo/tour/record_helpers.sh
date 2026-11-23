export PATH=~/Android/sdk/platform-tools:$PATH
S=${S:-.}
P=io.github.mayueanyou.lifetracker
D="am broadcast -a com.android.systemui.demo -e command"
bar() {  # clean status bar showing the real time
  adb shell $D exit >/dev/null; adb shell $D enter >/dev/null
  adb shell $D clock -e hhmm $(adb shell date +%H%M) >/dev/null
  adb shell $D battery -e level 100 -e plugged false >/dev/null
  adb shell $D network -e wifi show -e level 4 -e fully true -e mobile hide >/dev/null
  adb shell $D notifications -e visible false >/dev/null
  adb shell $D status -e location hide -e alarm hide -e volume hide -e bluetooth hide -e mute hide >/dev/null
}
rec_start() { adb shell "screenrecord --bit-rate 8000000 --time-limit ${2:-60} /sdcard/$1.mp4" & REC=$!; sleep 1.2; }
rec_stop() { sleep 1; adb shell pkill -INT screenrecord; wait $REC 2>/dev/null; sleep 1.5; adb pull /sdcard/$1.mp4 $S/intro/$1.mp4 >/dev/null; }
tap() { $S/tap.sh "$1"; }
node_xy() {  # centre of the first node whose text contains $1
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  adb shell cat /sdcard/ui.xml | python3 -c '
import sys,re,html
t=sys.argv[1]; x=sys.stdin.read()
for m in re.finditer(r"<node [^>]*>", x):
    n=m.group(0); mt=re.search(r"text=\"([^\"]*)\"",n); tx=html.unescape(mt.group(1)) if mt else ""
    if t in tx:
        a,b,c,d=map(int,re.search(r"bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"",n).groups()); print((a+c)//2,(b+d)//2); break' "$1"
}
node_eq() {  # centre of the first node whose text is exactly $1
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  adb shell cat /sdcard/ui.xml | python3 -c '
import sys,re,html
t=sys.argv[1]; x=sys.stdin.read()
for m in re.finditer(r"<node [^>]*>", x):
    n=m.group(0); mt=re.search(r"text=\"([^\"]*)\"",n); tx=html.unescape(mt.group(1)) if mt else ""
    if t == tx:
        a,b,c,d=map(int,re.search(r"bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"",n).groups()); print((a+c)//2,(b+d)//2); break' "$1"
}
money_reset() {
  adb shell cmd notification disallow_listener $P/$P.money.NotificationReader; sleep 1; adb shell am force-stop $P
  adb shell rm -f /data/data/$P/shared_prefs/money.xml
  adb shell "sqlite3 /data/data/$P/databases/life.db \"delete from payment where pkg='com.android.shell'\""
  adb shell am start -W -n $P/.ui.MainActivity >/dev/null; sleep 3
  adb shell cmd notification allow_listener $P/$P.money.NotificationReader; sleep 3
  adb shell cmd statusbar expand-notifications; sleep 1.5; xy=$(node_eq "Clear all"); [ -n "$xy" ] && adb shell input tap $xy; sleep 1; adb shell cmd statusbar collapse; sleep 1
}
money_reset() {
  adb shell cmd notification disallow_listener $P/$P.money.NotificationReader; sleep 1
  adb shell cmd statusbar expand-notifications; sleep 1.5; xy=$(node_eq "Clear all"); [ -n "$xy" ] && adb shell input tap $xy; sleep 1; adb shell cmd statusbar collapse; sleep 1
  adb shell am force-stop $P
  adb shell rm -f /data/data/$P/shared_prefs/money.xml
  adb shell "sqlite3 /data/data/$P/databases/life.db \"delete from payment where pkg='com.android.shell'\""
  adb shell am start -W -n $P/.ui.MainActivity >/dev/null; sleep 3
  adb shell cmd notification allow_listener $P/$P.money.NotificationReader; sleep 3
}
money_reset() {
  adb shell cmd notification disallow_listener $P/$P.money.NotificationReader; sleep 1
  adb shell cmd statusbar expand-notifications; sleep 1.5; xy=$(node_eq "Clear all"); [ -n "$xy" ] && adb shell input tap $xy; sleep 1; adb shell cmd statusbar collapse; sleep 1
  adb shell am force-stop $P
  adb shell rm -f /data/data/$P/shared_prefs/money.xml
  adb shell "sqlite3 /data/data/$P/databases/life.db \"delete from payment where pkg='com.android.shell'\""
  adb shell cmd notification allow_listener $P/$P.money.NotificationReader; sleep 3
  adb shell am start -W -n $P/.ui.MainActivity >/dev/null; sleep 4
}
open_count() {  # in the open shade: expand until "Count" shows, then tap it
  for i in 1 2 3 4; do
    adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
    act=$(adb shell cat /sdcard/ui.xml | python3 -c '
import sys,re,html
x=sys.stdin.read(); nodes=[]
for m in re.finditer(r"<node [^>]*>", x):
    n=m.group(0); g=lambda k: html.unescape((re.search(k+r"=\"([^\"]*)\"",n) or [None,""])[1])
    a,b,c,d=map(int,re.search(r"bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"",n).groups()); nodes.append((g("text"),g("content-desc"),a,b,c,d))
for t,cd,a,b,c,d in nodes:
    if t=="Count": print("tap",(a+c)//2,(b+d)//2); sys.exit()
ask=[(b+d)//2 for t,cd,a,b,c,d in nodes if t.startswith("A purchase from")]
if not ask: print("none"); sys.exit()
y=ask[0]; ex=[((b+d)//2,(a+c)//2) for t,cd,a,b,c,d in nodes if cd=="Expand"]
ex.sort(key=lambda e: abs(e[0]-y)); print("tap",ex[0][1],ex[0][0]) if ex else print("none")')
    echo "open_count: $act"
    set -- $act; [ "$1" = tap ] || return 1
    adb shell input tap $2 $3; sleep 1.8
    [ "$i" -gt 0 ] && adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
    case "$act" in *) ;; esac
    if adb shell cat /sdcard/ui.xml | grep -q 'text="Count"' ; then :; else continue; fi
  done
}
