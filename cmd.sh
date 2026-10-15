click 780 631 2
dclick 658 57 3
click 73 867 2
click 1080 121
typetext "explicit"
pause 2
click 1195 230 2
click 1080 121
key ctrl+a
typetext "clean"
pause 2
click 1195 230 2
shot settings
click 110 53
typetext "humble kendrick lamar"
key Return
pause 9
click 600 199 2
pause 15
shot humble
grep -n "explicit:\|CleanVersions\|JioSaavn\|matched\|racing\|asking for" $HOME/app.log | tail -15
