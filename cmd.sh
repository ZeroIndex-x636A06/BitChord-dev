hover 1300 230 1
for i in $(seq 1 15); do xdotool click 7; sleep 0.1; done
sleep 1
shot qp-h7
xdotool keydown shift
for i in $(seq 1 15); do xdotool click 5; sleep 0.1; done
xdotool keyup shift
sleep 1
shot qp-shift
