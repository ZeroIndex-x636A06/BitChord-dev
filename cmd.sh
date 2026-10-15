click 110 53
key ctrl+a
typetext "dracula tame impala jennie"
key Return
pause 9
shot search
click 600 199 2
pause 20
click 1375 51 2
pause 2
shot queue
grep -n "CleanVersions\|explicit:\|JioSaavn\|matched '" $HOME/app.log | tail -12
