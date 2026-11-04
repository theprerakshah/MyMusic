#!/usr/bin/env bash
# End-to-end robustness tests for MyMusic, run against a real phone over adb.
#
#   ./gradlew assembleQa && adb install -r app/build/outputs/apk/qa/app-qa.apk
#   scripts/device-test.sh [--core]
#
# Phone must be unlocked and logged in. Takes about 10 minutes, plays music out
# loud, and briefly changes the proxy, font size and rotation (restored on exit,
# even if the script fails). --core skips everything needing the qa test hooks,
# so it can also run against the release build. The screen-off test is last
# because the phone then needs its PIN.
export MSYS_NO_PATHCONV=1
ADB="${ADB:-$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe}"
PKG=com.prerak.mymusic
ACT=$PKG/.MainActivity
CORE_ONLY=false; [ "$1" = "--core" ] && CORE_ONLY=true
PASS=0; FAIL=0; FAILED=()

sh_() { "$ADB" shell "$@" | tr -d '\r'; }
wait_s() { sh_ "sleep $1"; }
pid() { sh_ pidof $PKG; }
focus() { sh_ dumpsys window | grep mCurrentFocus | grep -oE '[a-zA-Z.]+/[a-zA-Z.]+' | head -1; }
in_front() { focus | grep -q "^$PKG/"; }
state() { sh_ dumpsys media_session | grep -A12 "package=$PKG" | grep -oE 'state=(PLAYING|PAUSED|NONE|STOPPED|BUFFERING)' | head -1 | cut -d= -f2; }
fatals() { sh_ logcat -b crash -d | grep -c 'FATAL EXCEPTION'; }
open_app() { sh_ am start -n $ACT >/dev/null 2>&1; }
hook() { sh_ "am start -n $ACT $1" >/dev/null 2>&1; }
js() { hook "--es hook_js \"$1\""; }
screen_xml() { sh_ uiautomator dump /sdcard/mymusic-ui.xml >/dev/null 2>&1; sh_ cat /sdcard/mymusic-ui.xml; sh_ rm -f /sdcard/mymusic-ui.xml; }
screen_has() { screen_xml | grep -q "$1"; }

tap_retry() {
    local b
    b=$(screen_xml | tr '>' '>\n' | grep 'text="Try again"' | grep -oE 'bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"' | grep -oE '[0-9]+')
    # shellcheck disable=SC2086
    set -- $b
    [ $# -eq 4 ] && sh_ input tap $(( ($1 + $3) / 2 )) $(( ($2 + $4) / 2 ))
}

wait_for() { # wait_for <seconds> <command...>
    local limit=$1; shift
    for _ in $(seq 1 $(( limit / 2 ))); do "$@" && return 0; wait_s 2; done
    return 1
}
page_loaded() { screen_has '127916'; } # the injected audio/video toggle
is_playing() { [ "$(state)" = PLAYING ]; }
state_is() { [ "$(state)" = "$1" ]; }
has_retry() { screen_has "Try again"; }
has_loop_msg() { screen_has "keeps crashing"; }
new_process() { [ -n "$(pid)" ] && [ "$(pid)" != "$PID0" ]; }
no_process() { [ -z "$(pid)" ]; }
notif_stopped() { sh_ dumpsys notification --noredact | grep -q "MyMusic stopped unexpectedly"; }

play_song() {
    is_playing && return 0
    wait_for 30 page_loaded
    if $CORE_ONLY; then
        sh_ input tap 214 1659
    else
        js "document.querySelector('ytmusic-play-button-renderer').click()"
    fi
    wait_for 30 is_playing
}

check() { # check <name> <shell condition>
    local name=$1; shift
    if eval "$1"; then PASS=$((PASS + 1)); echo "PASS  $name"
    else FAIL=$((FAIL + 1)); FAILED+=("$name"); echo "FAIL  $name   [pid=$(pid) focus=$(focus) state=$(state) fatals=$(fatals)]"; fi
}
begin() { sh_ logcat -b crash -c; PID0=$(pid); }
same_process() { [ -n "$PID0" ] && [ "$(pid)" = "$PID0" ] && [ "$(fatals)" = 0 ]; }
no_fatal() { [ "$(fatals)" = 0 ]; }
fresh() { sh_ am force-stop $PKG; open_app; wait_for 30 page_loaded; }
let_crash_window_expire() { wait_s 61; }

ORIG_FONT=$(sh_ settings get system font_scale)
ORIG_PROXY=$(sh_ settings get global http_proxy)
ORIG_AUTOROTATE=$(sh_ settings get system accelerometer_rotation)
ORIG_ROTATION=$(sh_ settings get system user_rotation)
restore_proxy() { if [ "$ORIG_PROXY" = null ] || [ -z "$ORIG_PROXY" ]; then sh_ settings put global http_proxy :0; else sh_ settings put global http_proxy "$ORIG_PROXY"; fi; }
restore() {
    restore_proxy
    sh_ settings put system font_scale "$ORIG_FONT"
    sh_ settings put system user_rotation "$ORIG_ROTATION"
    sh_ settings put system accelerometer_rotation "$ORIG_AUTOROTATE"
    sh_ cmd media_session dispatch pause >/dev/null 2>&1
}
trap restore EXIT

echo "== Lifecycle (the original crash) =="
fresh; begin
check "cold start shows the page" 'page_loaded'
check "song starts playing" 'play_song'
begin; sh_ input keyevent KEYCODE_HOME; wait_s 6
check "Home with song playing: alive, playing" 'same_process && is_playing'
open_app; wait_s 3
check "return from Home: on screen, playing" 'in_front && is_playing'
begin; sh_ am start -a android.settings.SETTINGS >/dev/null; wait_s 6
check "switch to another app: alive, playing" 'same_process && is_playing'
sh_ input keyevent KEYCODE_APP_SWITCH; wait_s 3; open_app; wait_s 3
check "through Recents and back: alive, playing" 'same_process && is_playing'
begin; for _ in 1 2 3 4 5 6 7 8 9 10; do sh_ input keyevent KEYCODE_HOME; wait_s 1; open_app; wait_s 1; done
check "Home/reopen 10x quickly: alive, playing" 'same_process && is_playing'
begin; sh_ cmd statusbar expand-notifications; wait_s 2; sh_ cmd statusbar collapse; wait_s 2
check "notification shade open/close" 'same_process && in_front'

echo "== Configuration changes =="
begin; sh_ settings put system accelerometer_rotation 0; sh_ settings put system user_rotation 1; wait_s 4
sh_ settings put system user_rotation 0; wait_s 4
check "rotate and back: same process, playing" 'same_process && is_playing'
sh_ settings put system accelerometer_rotation "$ORIG_AUTOROTATE"; sh_ settings put system user_rotation "$ORIG_ROTATION"
begin; sh_ settings put system font_scale 1.01; wait_s 3; sh_ settings put system font_scale "$ORIG_FONT"; wait_s 15
check "activity rebuilt by font change: reloads" 'no_fatal && in_front && page_loaded'

echo "== Transport controls =="
begin; play_song
sh_ cmd media_session dispatch pause; wait_s 4
check "media pause" 'state_is PAUSED'
sh_ cmd media_session dispatch play; wait_s 4
check "media play" 'is_playing'
sh_ cmd media_session dispatch next; wait_s 6
check "media next" 'same_process && is_playing'
sh_ cmd media_session dispatch previous; wait_s 6
check "media previous" 'same_process && is_playing'

echo "== Memory pressure =="
begin; sh_ am send-trim-memory $PKG RUNNING_CRITICAL >/dev/null; wait_s 2
check "trim memory in foreground" 'same_process'
sh_ input keyevent KEYCODE_HOME; wait_s 2; sh_ am send-trim-memory $PKG COMPLETE >/dev/null; wait_s 3
check "trim memory in background" 'same_process'
open_app; wait_s 2

echo "== Exit paths =="
begin; for _ in 1 2 3 4 5 6; do sh_ input keyevent KEYCODE_BACK; wait_s 1; done; wait_s 2
check "Back out of the app: clean exit" 'no_fatal'
open_app; wait_for 30 page_loaded
check "reopen after Back" 'in_front'
begin; TASK=$(sh_ am stack list 2>/dev/null | grep "$PKG" | grep -oE 'taskId=[0-9]+' | head -1 | cut -d= -f2)
[ -z "$TASK" ] && TASK=$(sh_ dumpsys activity recents | grep -E "Recent #0.*$PKG" | grep -oE '#[0-9]+ type' | grep -oE '[0-9]+')
[ -n "$TASK" ] && sh_ am stack remove "$TASK"; wait_s 4
check "swipe away from Recents: clean exit" 'no_fatal'
begin; sh_ cmd media_session dispatch play >/dev/null 2>&1; wait_s 5
check "media button with the app closed" 'no_fatal'

if ! $CORE_ONLY; then
echo "== Bad data from the page =="
fresh; begin
js "AndroidMedia.onState(null,null,null,null,true,0,0)"; wait_s 2
check "null song fields" 'same_process'
js "AndroidMedia.onState('x','y','z','not a url',true,-5,-1)"; wait_s 2
check "invalid art URL, negative times" 'same_process'
js "AndroidMedia.onState('x','y','z','file:///etc/hosts',true,0,0)"; wait_s 2
check "non-http art URL" 'same_process'
js "AndroidMedia.onState('x'.repeat(2000000),'y','z','',true,0,0)"; wait_s 4
check "2 MB title (over the Binder limit)" 'same_process'
js "AndroidMedia.onState('x','y','z','https://picsum.photos/6000/6000',true,0,0)"; wait_s 15
check "6000x6000 album art" 'same_process'
js "AndroidMedia.onState('x','y','z','https://music.youtube.com/',true,0,0)"; wait_s 4
check "art URL that isn't an image" 'same_process'
js "throw new Error('page error')"; wait_s 1
check "exception in page script" 'same_process'

echo "== Network failures =="
sh_ am force-stop $PKG; sh_ settings put global http_proxy 127.0.0.1:1; wait_s 2; begin; open_app; wait_s 8
check "no connection at launch: error screen" 'has_retry'
PID0=$(pid); tap_retry; wait_s 6
check "retry while still offline: error stays" 'same_process && has_retry'
restore_proxy; wait_s 2; tap_retry
check "retry once back online: page loads" 'wait_for 30 page_loaded'
check "   ...and the error screen is gone" '! has_retry'
play_song; begin; sh_ settings put global http_proxy 127.0.0.1:1; wait_s 8; sh_ input keyevent KEYCODE_HOME; wait_s 4
check "connection lost mid-song, then Home" 'same_process'
restore_proxy; open_app; wait_s 4
begin; hook "--es hook_url https://expired.badssl.com/"; wait_s 8
check "expired HTTPS certificate: error screen" 'same_process && has_retry'
fresh; begin; hook "--es hook_url https://httpbin.org/status/503"; wait_s 8
check "HTTP 503 from server: error screen" 'same_process && has_retry'

echo "== WebView failures =="
fresh; play_song; begin; hook "--es hook_url chrome://crash"; wait_s 3
check "page process crash: app survives" 'same_process'
check "   ...and the page comes back" 'wait_for 30 page_loaded'
let_crash_window_expire; begin
hook "--el hook_delay_ms 4000 --es hook_url chrome://kill"; sh_ input keyevent KEYCODE_HOME; wait_s 8
check "page process killed in background" 'same_process'
check "   ...notification no longer says playing" '! is_playing'
open_app
check "   ...reloads on return" 'wait_for 30 page_loaded'
let_crash_window_expire; begin
for _ in 1 2 3; do hook "--es hook_url chrome://crash"; wait_s 6; done
check "page crashes 3x: still recovering" 'same_process && ! has_loop_msg'
hook "--es hook_url chrome://crash"; wait_s 6
check "page crashes 4x: stops, offers retry" 'same_process && has_loop_msg'
tap_retry
check "   ...retry works" 'wait_for 30 page_loaded'
sh_ am force-stop $PKG; begin; hook "--ez hook_no_webview true"; wait_s 4
check "WebView unavailable: error, not a crash" 'no_fatal && has_retry'
tap_retry
check "   ...retry works" 'wait_for 30 page_loaded'

echo "== Uncaught exceptions =="
let_crash_window_expire; fresh; begin
hook "--ez hook_throw true"; wait_s 6
check "main-thread crash on screen: relaunches" 'new_process && in_front'
check "   ...and the page loads" 'wait_for 30 page_loaded'
begin; hook "--ez hook_throw_thread true"; wait_s 6
check "worker-thread crash: relaunches" 'new_process && in_front'
wait_for 30 page_loaded
let_crash_window_expire; begin
hook "--el hook_delay_ms 4000 --ez hook_throw true"; sh_ input keyevent KEYCODE_HOME; wait_s 8
check "crash in background: process ends" 'no_process'
check "   ...tap-to-reopen notification appears" 'notif_stopped'
sh_ cmd notification cancel_all >/dev/null 2>&1
let_crash_window_expire; fresh
for _ in 1 2 3 4; do hook "--ez hook_throw true"; wait_s 6; done
check "crash loop: stops relaunching" 'no_process'
sh_ input keyevent KEYCODE_BACK; wait_s 1
fi

echo "== Screen off (phone will need its PIN afterwards) =="
let_crash_window_expire; fresh; play_song; begin; sh_ input keyevent KEYCODE_SLEEP; wait_s 12
check "screen off and locked: alive, playing" 'same_process && is_playing'

echo
echo "RESULT: $PASS passed, $FAIL failed"
for f in "${FAILED[@]}"; do echo "  failed: $f"; done
[ "$FAIL" = 0 ]
