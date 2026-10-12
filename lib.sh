# Helpers for driving the app on the virtual screen. Sourced by run_cmd.
export DISPLAY=:99
n=0
shot() { n=$((n+1)); import -window root "$(printf %02d $n)-${1:-shot}.png"; }
click() { xdotool mousemove "$1" "$2" click 1; sleep "${3:-1.5}"; }
dclick() { xdotool mousemove "$1" "$2" click --repeat 2 1; sleep "${3:-1.5}"; }
rclick() { xdotool mousemove "$1" "$2" click 3; sleep 1.5; }
hover() { xdotool mousemove "$1" "$2"; sleep "${3:-1}"; }
typetext() { xdotool type --delay 50 "$1"; sleep 1; }
key() { xdotool key "$@"; sleep 1; }
# scroll X Y COUNT [up]
scroll() { xdotool mousemove "$1" "$2"; b=5; [ "${4:-}" = up ] && b=4; for _ in $(seq "${3:-3}"); do xdotool click $b; sleep 0.15; done; sleep 1; }
pause() { sleep "$1"; }
