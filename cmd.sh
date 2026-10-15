click 110 53
key ctrl+a
typetext "marshall mathers lp2"
key Return
pause 9
shot editions
click 465 105 2
pause 6
shot editions-albums
click 110 53
key ctrl+a
typetext "fuck tha police nwa"
key Return
pause 9
shot nwa-results
click 600 437 2
pause 25
shot nwa-after
grep -n "explicit:\|CleanVersions\|clean version" $HOME/app.log | tail -12
