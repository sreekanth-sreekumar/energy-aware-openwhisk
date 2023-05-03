#!/bin/bash

# List of machines to run the script on
machines=(
    "192.168.0.11" "invoker0" "192.168.0.12" "invoker1" "192.168.0.13" "invoker2" "192.168.0.14"
     "invoker3" "192.168.0.15" "invoker4" "192.168.0.16" "invoker5" "192.168.0.17" "invoker6"
     "192.168.0.18" "invoker7" "192.168.0.19" "invoker8"
)
username="pi"

image="ssreekmr/invoker:latest"

redis_image="redis:4.0"

for ((i=0; i<${#machines[@]}; i+=2)); do

  ip="${machines[$i]}"
  machine="${machines[$i+1]}"
  ssh "${username}@${ip}" << EOF
    rm -f /var/tmp/wsklogs/${machine}/${machine}_logs.log
    container_ids=\$(docker ps -q -f "ancestor=${image}")
    if [ -z "\$container_ids" ]; then
      echo "No containers found for image ${image}"
    else
      docker stop \$container_ids
      docker rm \$container_ids
      echo "Stopped and removed containers for image ${image}"
    fi
EOF

rm -f /var/tmp/wsklogs/controller0/controller0_logs.log
container_ids=$(docker ps -q -f "ancestor=${redis_image}")
if [ -z "$container_ids" ]; then
  echo "No containers found for image ${redis_image}"
else
  docker stop "$container_ids"
  docker rm "$container_ids"
  echo "Stopped and removed containers for image ${redis_image}"
fi
done