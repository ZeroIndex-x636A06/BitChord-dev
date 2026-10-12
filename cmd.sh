pkill -f "BitChord/lib" ; pkill -f "BitChord/bin" ; sleep 4
(cd "$HOME" && DISPLAY=:99 JAVA_TOOL_OPTIONS="-Dskiko.renderApi=SOFTWARE_FAST" nohup "$HOME/BitChord/bin/BitChord" >> "$HOME/app.log" 2>&1 &)
sleep 30
w=$(xdotool search --name "BitChord" | head -1); [ -n "$w" ] && xdotool windowactivate $w && xdotool key super+Up
pause 2
shot relaunched
key Escape
click 73 867 3
click 1080 121
key ctrl+a
typetext "explicit"
pause 2
shot settings-persisted
click 110 53
pause 2
shot recent-searches
