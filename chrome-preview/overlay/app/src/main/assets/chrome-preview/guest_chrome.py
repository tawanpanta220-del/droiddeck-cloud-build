import argparse
import json
import os
import signal
import subprocess
import time
from pathlib import Path
from urllib.parse import urlsplit

parser = argparse.ArgumentParser()
parser.add_argument('action', choices=['start', 'open', 'stop'])
parser.add_argument('--url', default='about:blank')
options = parser.parse_args()
url = urlsplit(options.url)
if options.url not in ('about:blank', 'chrome://sandbox', 'chrome://settings'):
    if url.scheme not in ('http', 'https') or not url.hostname or url.username or url.password:
        raise RuntimeError('Unsupported browser address')
    if len(options.url) > 4096 or any(ord(c) < 32 for c in options.url):
        raise RuntimeError('Invalid browser address')

home = Path('/home/droiddeck')
profile = home / '.config/droiddeck-chrome-preview'
preload = Path('/etc/ld.so.preload')
if preload.exists() and preload.read_text().strip():
    raise RuntimeError('Guest system preloads must be empty')
helper = Path('/opt/google/chrome/chrome-sandbox').stat()
if helper.st_uid != 0 or helper.st_gid != 0 or helper.st_mode & 0o7777 != 0o4755:
    raise RuntimeError('Chrome sandbox helper ownership is invalid')


def browsers():
    result = []
    for entry in Path('/proc').iterdir():
        if not entry.name.isdigit():
            continue
        try:
            args = (entry / 'cmdline').read_bytes().split(b'\0')
            if args[0] == b'/opt/google/chrome/chrome' and not any(a.startswith(b'--type=') for a in args):
                if b'--user-data-dir=' + str(profile).encode() in args:
                    result.append(int(entry.name))
        except OSError:
            continue
    return result


def user():
    os.setgroups([])
    os.setgid(1000)
    os.setuid(1000)


if options.action == 'stop':
    deadline = time.monotonic() + 90
    while browsers() and time.monotonic() < deadline:
        time.sleep(0.2)
    for pid in browsers():
        os.kill(pid, signal.SIGTERM)
    deadline = time.monotonic() + 20
    while browsers() and time.monotonic() < deadline:
        time.sleep(0.2)
    remaining = browsers()
    preferences = profile / 'Default/Preferences'
    exit_type = None
    if preferences.is_file():
        exit_type = json.loads(preferences.read_text()).get('profile', {}).get('exit_type')
    print(json.dumps({'remaining_browser_pids': remaining, 'profile_exit_type': exit_type}))
    raise SystemExit(1 if remaining or (exit_type is not None and exit_type != 'Normal') else 0)

env = dict(os.environ, HOME=str(home), USER='droiddeck', LOGNAME='droiddeck',
           DISPLAY='10.0.2.2:2', XAUTHORITY=str(home / '.Xauthority-native'),
           XDG_RUNTIME_DIR='/run/user/1000')
for key in ['LD_PRELOAD', 'LD_AUDIT', 'ELECTRON_DISABLE_SANDBOX', 'CHROME_DEVEL_SANDBOX',
            'WAYLAND_DISPLAY', 'DBUS_SESSION_BUS_ADDRESS']:
    env.pop(key, None)

args = ['/opt/google/chrome/chrome', '--ozone-platform=x11', '--disable-gpu',
        '--no-first-run', '--no-default-browser-check', '--disable-background-mode',
        '--user-data-dir=' + str(profile), '--new-window' if options.action == 'start' else '--new-tab', options.url]
active = browsers()
if options.action == 'start' and active:
    print(json.dumps({'existing_browser_pids': active}))
    raise SystemExit(0)
if not active:
    for name in ['SingletonLock', 'SingletonSocket', 'SingletonCookie']:
        link = profile / name
        if link.is_symlink():
            link.unlink()
with open('/root/chrome-preview.log', 'a') as log:
    process = subprocess.Popen(['dbus-run-session', '--', *args], env=env, preexec_fn=user,
                               stdin=subprocess.DEVNULL, stdout=log, stderr=subprocess.STDOUT,
                               start_new_session=True)
print(json.dumps({'launcher_pid': process.pid, 'uid': 1000, 'no_sandbox_flag_used': False}))
