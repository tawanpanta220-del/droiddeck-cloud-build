"""Phone-local preview supervisor; requires the previously verified diagnostic runtime."""
import base64
import fcntl
import hashlib
import importlib.util
import json
import os
import signal
import socket
import subprocess
import sys
import time
from pathlib import Path

TOOLS = Path(__file__).resolve().parent
STATE = Path(sys.argv[1])
BASE = Path('/root/.cache/droiddeck-local-vm')
STOP = STATE / 'stop'
STATE.mkdir(parents=True, exist_ok=True, mode=0o700)
lock = (STATE / 'supervisor.lock').open('w')
fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)


def atomic_file(path, data):
    temporary = path.with_name(path.name + '.tmp')
    fd = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_TRUNC | os.O_NOFOLLOW, 0o600)
    with os.fdopen(fd, 'wb') as stream:
        stream.write(data)
    temporary.replace(path)


def status(phase, error=None):
    atomic_file(STATE / 'status.json', json.dumps({'phase': phase, 'error': error,
                                                 'updated_at': time.time()}).encode())
    print('chrome-preview:' + phase, flush=True)


def stopped():
    if STOP.exists():
        raise InterruptedError('Stop requested')


def listening(port):
    with socket.socket() as connection:
        return connection.connect_ex(('127.0.0.1', port)) == 0


def console(script, timeout=90):
    process = subprocess.run([sys.executable, str(TOOLS / 'console.py'), '--timeout', str(timeout)],
                             input=script, text=True, capture_output=True, timeout=timeout + 5)
    if process.returncode:
        raise RuntimeError('Guest console transport failed')
    result = json.loads(process.stdout)
    if not result['complete'] or result['exit'] != 0:
        raise RuntimeError('Guest command did not complete')
    return result


def qmp_quit():
    endpoint = Path('/root/.cache/dv/m')
    if not endpoint.is_socket():
        return
    with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as connection:
        connection.settimeout(5)
        connection.connect(str(endpoint))
        stream = connection.makefile('rwb', buffering=0)
        json.loads(stream.readline())
        stream.write(b'{"execute":"qmp_capabilities"}\n')
        while 'return' not in json.loads(stream.readline()):
            pass
        stream.write(b'{"execute":"quit"}\n')


display = None
display_owned = False
proxy_pid = None
desktop_pid = None
vm_started = False
vm_pid = None
failure = None
try:
    status('checking')
    required = [BASE / 'minimal.qcow2', BASE / 'noble-server-cloudimg-arm64.img',
                BASE / 'unpacked/verified.json', BASE / 'boot.json']
    helper = Path('/root/.cache/droiddeck-chrome-proof/bin/chrome-clean-exec-arm64')
    if not all(p.is_file() for p in required) or not helper.is_file():
        raise FileNotFoundError('PREPARED_RUNTIME_REQUIRED')
    if hashlib.sha256(helper.read_bytes()).hexdigest() != 'a78cb3c9478896778a3978609688865a31ea5b57580e061c52fa0e55294f9737':
        raise RuntimeError('Prepared runtime helper verification failed')
    spec = importlib.util.spec_from_file_location('preview_display', TOOLS / 'local_display.py')
    display = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(display)
    status('display')
    endpoint = STATE / 'rfb'
    display.start(unix_path=endpoint)
    display_owned = True
    deadline = time.monotonic() + 20
    while not endpoint.is_socket():
        stopped()
        if time.monotonic() > deadline:
            raise RuntimeError('The local display did not start')
        time.sleep(0.2)
    if listening(6002):
        raise RuntimeError('The local X11 relay port is already in use')
    (display.BASE / 'proxy.pid').unlink(missing_ok=True)
    with (display.BASE / 'proxy.log').open('w') as log:
        proxy = subprocess.Popen([sys.executable, str(TOOLS / 'local_display.py'), 'proxy'],
                                 stdin=subprocess.DEVNULL, stdout=log, stderr=subprocess.STDOUT,
                                 start_new_session=True)
        proxy_pid = proxy.pid
    deadline = time.monotonic() + 20
    while not (display.BASE / 'proxy.pid').is_file():
        stopped()
        if proxy.poll() is not None or time.monotonic() > deadline:
            raise RuntimeError('The local X11 relay did not start')
        time.sleep(0.1)
    if int((display.BASE / 'proxy.pid').read_text()) != proxy.pid:
        raise RuntimeError('The local X11 relay identity is invalid')
    desktop_pid = display.desktop()['window_manager_pid']
    atomic_file(STATE / 'vnc.password', (display.BASE / 'password.private').read_bytes())
    status('kernel')
    stopped()
    boot = subprocess.run([sys.executable, str(TOOLS / 'boot.py'), '--resume', '--cpu', 'cortex-a76',
                           '--private-console', '--tcg-threads', 'single'],
                          capture_output=True, text=True, timeout=30)
    if boot.returncode:
        raise RuntimeError('Prepared virtual machine could not start')
    vm_pid = int(json.loads(boot.stdout)['pid'])
    vm_started = True
    deadline = time.monotonic() + 600
    while True:
        stopped()
        text = (BASE / 'minimal-serial.log').read_text(errors='replace')[-2000:]
        if 'root@(none):' in text:
            break
        if time.monotonic() > deadline:
            raise RuntimeError('Linux startup timed out')
        time.sleep(1)
    status('services')
    setup = 'date -u -s @' + str(int(time.time())) + '\n' + (TOOLS / 'services.sh').read_text()
    setup += '\nrm -f /etc/sudoers.d/droiddeck-local\n'
    console(setup)
    stopped()
    authority = base64.b64encode((display.BASE / 'Xauthority').read_bytes()).decode()
    program = base64.b64encode((TOOLS / 'guest_chrome.py').read_bytes()).decode()
    install = "printf '%s' '" + authority + "' | base64 -d > /home/droiddeck/.Xauthority-native\n"
    install += 'chmod 600 /home/droiddeck/.Xauthority-native\nchown 1000:1000 /home/droiddeck/.Xauthority-native\n'
    install += "printf '%s' '" + program + "' | base64 -d > /root/chrome-preview.py\n"
    console(install)
    stopped()
    status('chrome_starting')
    console('python3 /root/chrome-preview.py start\n')
    status('running')
    while not STOP.exists():
        requests = sorted((STATE / 'urls').glob('*.json'))
        for request in requests:
            stopped()
            value = json.loads(request.read_text())
            url = value['url']
            # Pass addresses as JSON data rather than shell text.
            data = base64.b64encode(json.dumps(url).encode()).decode()
            script = "python3 - <<'PY'\nimport base64,json,subprocess\n"
            script += "url=json.loads(base64.b64decode('" + data + "'))\n"
            script += "subprocess.run(['python3','/root/chrome-preview.py','open','--url',url],check=True)\nPY\n"
            console(script)
            request.unlink()
        time.sleep(0.3)
except InterruptedError:
    pass
except Exception as error:
    failure = 'PREPARED_RUNTIME_REQUIRED' if str(error) == 'PREPARED_RUNTIME_REQUIRED' else type(error).__name__ + ': ' + str(error)
finally:
    if vm_started:
        status('stopping')
        try:
            console('python3 /root/chrome-preview.py stop\nsync\n', timeout=35)
        except Exception:
            pass
        try:
            qmp_quit()
        except Exception:
            pass
        def vm_alive():
            try:
                command = (Path('/proc') / str(vm_pid) / 'cmdline').read_bytes()
                return b'qemu-system-aarch64' in command and str(BASE / 'minimal.qcow2').encode() in command
            except OSError:
                return False
        deadline = time.monotonic() + 5
        while vm_alive() and time.monotonic() < deadline:
            time.sleep(0.1)
        if vm_alive():
            try:
                os.kill(vm_pid, signal.SIGTERM)
            except ProcessLookupError:
                pass
            deadline = time.monotonic() + 3
            while vm_alive() and time.monotonic() < deadline:
                time.sleep(0.1)
        if vm_alive():
            try:
                os.kill(vm_pid, signal.SIGKILL)
                failure = failure or 'Virtual machine required a forced stop'
            except ProcessLookupError:
                pass
    if display is not None and display_owned:
        try:
            display.stop()
        except OSError:
            pass
    for pid, expected in [(proxy_pid, b'local_display.py'), (desktop_pid, b'openbox')]:
        if pid is not None:
            try:
                if expected in (Path('/proc') / str(pid) / 'cmdline').read_bytes():
                    os.kill(pid, signal.SIGTERM)
            except OSError:
                pass
    (STATE / 'vnc.password').unlink(missing_ok=True)
    status('failed' if failure else 'stopped', failure)
