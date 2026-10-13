click 846 505
pause 6
shot fdo-allowed
pkill -f "BitChord/bin" ; pkill -f "music.bitchord" ; sleep 4
cd "$HOME" && (DISPLAY=:99 JAVA_TOOL_OPTIONS="-Dskiko.renderApi=SOFTWARE_FAST" nohup "$HOME/BitChord/bin/BitChord" > "$HOME/app2.log" 2>&1 &) ; cd - >/dev/null
sleep 30
xdotool search --name "BitChord" | head -1 | xargs -r -I{} xdotool windowactivate {} windowsize {} 1440 900 windowmove {} 0 0 || true
sleep 2
shot restarted
