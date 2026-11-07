import argparse
import hashlib
import json
import os
import signal
import subprocess
import time
from pathlib import Path
from runtime_support import prepare_kernel

B = Path('/root/.cache/droiddeck-local-vm')
parser = argparse.ArgumentParser()
parser.add_argument('--resume', action='store_true')
parser.add_argument('--cpu', default='cortex-a72', choices=['cortex-a72', 'cortex-a76', 'max'])
parser.add_argument('--private-console', action='store_true')
parser.add_argument('--tcg-threads', choices=['single', 'multi'], default='multi')
options = parser.parse_args()
resume = options.resume
if (B / 'minimal-boot.json').exists():
    previous = json.loads((B / 'minimal-boot.json').read_text())
    cmd = Path('/proc') / str(previous['pid']) / 'cmdline'
    if cmd.exists() and b'qemu-system-aarch64' in cmd.read_bytes():
        raise RuntimeError('Minimal VM already running')
boot = json.loads((B / 'boot.json').read_text())
old = Path('/proc') / str(boot['pid'])
if old.exists() and b'qemu-system-aarch64' in (old / 'cmdline').read_bytes():
    (B / 'standard-qemu-maps.log').write_text((old / 'maps').read_text())
    os.kill(boot['pid'], signal.SIGTERM)
    for _ in range(30):
        if not old.exists():
            break
        time.sleep(0.1)
    if old.exists():
        raise RuntimeError('Previous VM has not stopped')
u = B / 'unpacked'
verified = json.loads((u / 'verified.json').read_text())
for entry in verified['files']:
    assert hashlib.sha256((u / entry['name']).read_bytes()).hexdigest() == entry['sha256']
kernel_result = prepare_kernel(u / 'noble-server-cloudimg-arm64-vmlinuz-generic', u / 'Image')
disk = B / 'minimal.qcow2'
if disk.exists() and not resume:
    raise RuntimeError('Minimal disk already exists; do not overwrite it')
if not disk.exists():
    subprocess.run(['qemu-img', 'create', '-f', 'qcow2', '-F', 'qcow2', '-b',
                str(B / 'noble-server-cloudimg-arm64.img'), str(disk), '12G'],
               capture_output=True, check=True)
console = 'host=127.0.0.1,port=4422'
monitor = ['-monitor', 'none']
if options.private_console:
    directory = Path('/root/.cache/dv')
    directory.mkdir(mode=0o700, parents=True, exist_ok=True)
    directory.chmod(0o700)
    endpoint = directory / 's'
    if endpoint.is_socket():
        endpoint.unlink()
    console = 'path=' + str(endpoint)
    monitor_endpoint = directory / 'm'
    if monitor_endpoint.is_socket():
        monitor_endpoint.unlink()
    monitor = ['-qmp', 'unix:' + str(monitor_endpoint) + ',server=on,wait=off']
argv = ['/usr/bin/qemu-system-aarch64', '-machine', 'virt', '-accel', 'tcg,thread=' + options.tcg_threads,
        '-cpu', options.cpu, '-smp', '2', '-m', '1024', '-kernel', str(u / 'Image'),
        '-initrd', str(u / 'noble-server-cloudimg-arm64-initrd-generic'),
        '-append', 'root=/dev/vda1 rw console=ttyAMA0 init=/bin/bash',
        '-drive', f'if=virtio,format=qcow2,file={disk}',
        '-netdev', 'user,id=net0,hostfwd=tcp:127.0.0.1:2222-:22,hostfwd=tcp:127.0.0.1:5903-:5901',
        '-device', 'virtio-net-pci,netdev=net0,mac=52:54:00:12:34:56,romfile=',
        '-device', 'virtio-rng-pci', '-display', 'none', *monitor,
        '-chardev', f'socket,id=console,{console},server=on,wait=off,logfile={B / "minimal-serial.log"}',
        '-serial', 'chardev:console']
preload = Path('/etc/ld.so.preload')
original = preload.read_bytes()
(B / 'preload.before-minimal').write_bytes(original)
helper = '/root/.cache/droiddeck-chrome-proof/bin/chrome-clean-exec-arm64'
env = dict(os.environ)
for name in ['LD_PRELOAD', 'LD_AUDIT', 'ELECTRON_DISABLE_SANDBOX', 'CHROME_DEVEL_SANDBOX']:
    env.pop(name, None)
try:
    preload.write_bytes(b'')
    # The supervisor must never accept a root prompt left by the previous boot.
    (B / 'minimal-serial.log').write_bytes(b'')
    with (B / 'minimal-qemu.log').open('w') as log:
        process = subprocess.Popen([helper, *argv], env=env, stdin=subprocess.DEVNULL,
                                   stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
    # Wait until the dynamic loader has consumed the temporary preload setting.
    for _ in range(100):
        maps = Path('/proc') / str(process.pid) / 'maps'
        if maps.exists() and 'libglib-2.0' in maps.read_text():
            break
        if process.poll() is not None:
            raise RuntimeError((B / 'minimal-qemu.log').read_text())
        time.sleep(0.05)
    else:
        raise RuntimeError('QEMU loader did not become ready')
    (B / 'minimal-qemu-maps.log').write_text(maps.read_text())
finally:
    preload.write_bytes(original)
    assert preload.read_bytes() == original
result = {'pid': process.pid, 'started_at': time.time(), 'argv': argv,
          'preload_restored': True, 'preload_sha256': hashlib.sha256(original).hexdigest(),
          'clean_qemu': 'libblsession.so' not in maps.read_text(), **kernel_result}
(B / 'minimal-boot.json').write_text(json.dumps(result, indent=2))
print(json.dumps(result))
