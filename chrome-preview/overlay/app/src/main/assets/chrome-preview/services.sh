set -eu
hostname droiddeck-vm
grep -q '^127.0.1.1 droiddeck-vm$' /etc/hosts || printf '\n127.0.1.1 droiddeck-vm\n' >> /etc/hosts
mountpoint -q /proc || mount -t proc proc /proc
mountpoint -q /sys || mount -t sysfs sysfs /sys
mkdir -p /dev/pts /dev/shm /run/dbus /run/user/1000 /run/systemd/resolve
mountpoint -q /dev/pts || mount -t devpts devpts /dev/pts
mountpoint -q /dev/shm || mount -t tmpfs -o mode=1777,size=128m tmpfs /dev/shm
chmod 700 /run/user/1000
chown 1000:1000 /run/user/1000
iface=$(ls /sys/class/net | grep -v '^lo$' | head -1)
ip link set lo up
ip link set "$iface" up
ip addr replace 10.0.2.15/24 dev "$iface"
ip route replace default via 10.0.2.2
printf 'nameserver 10.0.2.3\n' > /run/systemd/resolve/stub-resolv.conf
test -s /etc/machine-id || dbus-uuidgen --ensure=/etc/machine-id
dbus-daemon --system --fork
uname -a
id droiddeck
free -m
