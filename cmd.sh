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
typetext "dracula tame impala jennie"
key Return
pause 9
click 400 376 2
pause 20
click 1375 51 2
pause 2
shot dracula
click 1418 98 2
click 110 53
key ctrl+a
typetext "lose yourself eminem"
key Return
pause 9
shot ly-search
click 600 199 2
pause 20
grep -n "CleanVersions\|explicit:\|JioSaavn\|matched '" $HOME/app.log | tail -14
