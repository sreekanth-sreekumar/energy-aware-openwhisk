#!/usr/bin/zsh

machines=("192.168.0.17"
  "192.168.0.18" "192.168.0.19")
username="pi"
images=("invoker" "whisk/invoker")
images_save_dir="/home/sreekanth/saved_imgs"

echo "Saving image: invoker in controller"
docker save -o "$images_save_dir/invoker.tar" "invoker"
echo "Saving image: whisk/invoker in controller"
docker save -o "$images_save_dir/whisk_invoker.tar" "whisk/invoker"

for machine in "${machines[@]}"
do
    echo "Transferring docker image to: $machine"

    # Copy the saved images to the target machine
    scp "$images_save_dir/whisk_invoker.tar" "$username@$machine:/tmp/whisk_invoker.tar"
    scp "$images_save_dir/invoker.tar" "$username@$machine:/tmp/invoker.tar"

    # Install fuse-overlayfs package
    ssh "$username@$machine" "sudo apt update && sudo apt install -y fuse-overlayfs"
    ssh "$username@$machine" "sudo modprobe overlay"

    # Load the Docker images on the target machine
    ssh "$username@$machine" "sudo systemctl restart docker.service && sudo docker load -i /tmp/whisk_invoker.tar && sudo docker load -i /tmp/invoker.tar"
done
