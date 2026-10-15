click 1195 230 2
click 110 53
typetext "humble kendrick lamar"
key Return
pause 9
shot results
click 600 199 2
pause 10
shot after-tap
click 1375 51 2
shot queue
grep -n "explicit:\|CleanVersions\|clean version" $HOME/app.log | tail -10
