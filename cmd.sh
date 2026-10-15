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
click 110 53
typetext "costa rica bankrol hayden"
key Return
pause 9
click 315 105 2
pause 6
shot songs-clean-mode
click 400 160 2
pause 25
click 1375 51 2
pause 2
shot queue
grep -n "CleanVersions\|explicit:" $HOME/app.log | tail -8
