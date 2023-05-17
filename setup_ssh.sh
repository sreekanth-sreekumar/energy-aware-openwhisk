#!/bin/bash

# List of remote machines
machines=(
    "192.168.0.11" "192.168.0.12" "192.168.0.13" "192.168.0.14"
    "192.168.0.15" "192.168.0.16" "192.168.0.17"
     "192.168.0.18" "192.168.0.19"
)
username="pi"


# Generate SSH key pair if it doesn't exist
if [ ! -f ~/.ssh/marvin1_id_rsa ]; then
    ssh-keygen -t rsa -N "" -f ~/.ssh/marvin1_id_rsa
fi

# Copy the public key to remote machines
for machine in "${machines[@]}"; do
    ssh-copy-id -i ~/.ssh/marvin1_id_rsa.pub "$username@$machine"
done
