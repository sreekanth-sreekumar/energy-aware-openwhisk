#!/bin/bash

# List of machines to run the script on
machines=(
    "192.168.0.11" "invoker0" "192.168.0.12" "invoker1" "192.168.0.13" "invoker2" "192.168.0.14"
     "invoker3" "192.168.0.15" "invoker4" "192.168.0.16" "invoker5" "192.168.0.17" "invoker6"
     "192.168.0.18" "invoker7" "192.168.0.19" "invoker8"
)
username="pi"

mkdir /home/sreekanth/Projects/energy-aware-openwhisk/ow_exps/weighted_dist_logs_new/
# Loop through the machines and start the script on each one
for ((i=0; i<${#machines[@]}; i+=2)); do
  ip="${machines[$i]}"
  machine="${machines[$i+1]}"
  echo "${machine}"
  echo "${ip}"
  source_path="${username}@${ip}:/var/tmp/wsklogs/${machine}/${machine}_logs.log"
  echo "$source_path"
  dest_path="/home/sreekanth/Projects/energy-aware-openwhisk/ow_exps/weighted_dist_logs_new/"
  scp "$source_path" "$dest_path"
  echo "Copied $machine"
  done

cp /var/tmp/wsklogs/controller0/controller0_logs.log /home/sreekanth/Projects/energy-aware-openwhisk/ow_exps/weighted_dist_logs_new/
