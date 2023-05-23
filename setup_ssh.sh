#!/bin/bash

# List of remote machines
machines=(
    "192.168.0.11" "192.168.0.12" "192.168.0.13" "192.168.0.14"
    "192.168.0.15" "192.168.0.16" "192.168.0.17"
     "192.168.0.18" "192.168.0.19"
)
username="pi"
# Generate SSH key pair (if not already generated)
if [ ! -f ~/.ssh/id_rsa ]; then
    ssh-keygen -t rsa -N "" -f ~/.ssh/id_rsa
fi

# Install sshpass utility (if not already installed)
if ! command -v sshpass >/dev/null 2>&1; then
    echo "sshpass utility not found. Installing..."
    sudo apt-get update
    sudo apt-get install -y sshpass
fi

# Iterate over each machine and copy SSH public key
for machine in "${machines[@]}"; do
    # Copy SSH public key to remote machine using sshpass
    sshpass -p "password" ssh-copy-id -o StrictHostKeyChecking=no -i ~/.ssh/id_rsa.pub "$username@$machine"
done