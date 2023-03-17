#!/usr/bin/zsh

machines=("192.168.0.11" "192.168.0.12" "192.168.0.13" "192.168.0.14" "192.168.0.15" "192.168.0.16" "192.168.0.17"
  "192.168.0.18" "192.168.0.19")
username="pi"

for machine in "${machines[@]}"
do
    echo "Installing prereqs on : $machine"

    # Load the Docker images on the target machine
    ssh "$username@$machine" "pip install ansible==2.5.2 jinja2==2.9.6"
done
