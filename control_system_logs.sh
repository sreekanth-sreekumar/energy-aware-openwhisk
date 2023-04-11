#!/bin/bash

# List of machines to run the script on
machines=("192.168.0.11")
username="pi"
bash_script_dir="./collect_stat.sh"

for MACHINE in "${machines[@]}"; do
  echo "Transferring to $MACHINE"
  scp "$bash_script_dir" "$username@$MACHINE:collect_stat.sh"
done

# Function to start the script on a machine
function start_script() {
  echo "Starting collect script on $1"
  ssh "$1" "nohup bash /home/pi/collect_stat.sh > /dev/null 2>&1 &"
  echo "Started collect script on $1"
}

# Loop through the machines and start the script on each one
for MACHINE in "${machines[@]}"; do
  start_script "$username@$MACHINE"
done

# Function to stop the script on all machines
function stop_script() {
  echo "Stopping collect script on all machines..."
  for MACHINE in "${machines[@]}"; do
    ssh "$username@$MACHINE" "pkill -f /home/pi/collect_stat.sh"
    echo "Stopped collect script on $MACHINE"
  done
  mkdir ~/ow_exps/col_warm/
  scp pi@192.168.0.11:/home/pi/system_stats.log ~/ow_exps/col_warm/system_stats.log
  scp pi@192.168.0.11:/var/tmp/wsklogs/invoker0/invoker0_logs.log ~/ow_exps/col_warm/invoker.log
  cp /var/tmp/wsklogs/controller0/controller0_logs.log ~/ow_exps/col_warm/controller.log

  rm -f /var/tmp/wsklogs/controller0/controller0_logs.log

  for MACHINE in "${machines[@]}"; do
    ssh "$username@$MACHINE" "rm -f /var/tmp/wsklogs/invoker0/invoker0_logs.log /home/pi/system_stats.log /home/pi/collect_stat.sh"
    echo "Cleaned up $MACHINE"
  done
  pid=$$
  kill $pid
}

# Register the stop_script function to be called when a SIGINT signal is received
trap stop_script SIGINT

# Wait for the script to be interrupted by a SIGINT signal
echo "Press CTRL+C to stop the script on all machines"
while true; do sleep 1; done
