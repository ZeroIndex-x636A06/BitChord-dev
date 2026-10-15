click 800 160 1
pause 2
shot tap-2s
pause 20
shot tap-22s
grep -n "explicit:\|CleanVersions\|clean version" $HOME/app.log | tail -6
