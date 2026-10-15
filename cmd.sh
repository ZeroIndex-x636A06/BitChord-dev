click 315 105 2
pause 6
shot nwa-songs
click 600 160 2
pause 25
shot nwa-after
grep -n "explicit:\|CleanVersions\|clean version" $HOME/app.log | tail -6
click 73 867 2
click 1080 121
typetext "clean"
pause 2
shot settings-on
click 1195 230 2
shot settings-off
click 110 53
key ctrl+a
typetext "marshall mathers lp2"
key Return
pause 9
click 465 105 2
pause 6
shot editions-off
