#!/usr/bin/env python3
"""Diagnostic X11 display inside the phone; requires TigerVNC packages."""
import argparse
import json
import os
import secrets
import signal
import socket
import socketserver
import struct
import threading
import subprocess
from pathlib import Path

BASE = Path.home() / '.cache/droiddeck-local-display'


def environment():
    env = dict(os.environ, DISPLAY=':2', LIBGL_ALWAYS_SOFTWARE='1',
               GALLIUM_DRIVER='llvmpipe', QT_QPA_PLATFORM='xcb',
               QT_QUICK_BACKEND='software', XAUTHORITY=str(BASE / 'Xauthority'))
    env.pop('WAYLAND_DISPLAY', None)
    return env


def start(unix_path=None):
    BASE.mkdir(parents=True, exist_ok=True)
    if unix_path is None:
        with socket.socket() as probe:
            if probe.connect_ex(('127.0.0.1', 5902)) == 0:
                raise RuntimeError('Port 5902 is already in use')
    else:
        unix_path = Path(unix_path)
        if unix_path.is_socket():
            with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as probe:
                if probe.connect_ex(str(unix_path)) == 0:
                    raise RuntimeError('The private display is already running')
            unix_path.unlink()
    password = secrets.token_urlsafe(6)[:8]
    private = BASE / 'password.private'
    private.touch(mode=0o600, exist_ok=True)
    private.chmod(0o600)
    private.write_text(password)
    encoded = subprocess.run(['tigervncpasswd', '-f'], input=(password + '\n').encode(),
                             capture_output=True, check=True).stdout
    password_file = BASE / 'passwd'
    password_file.touch(mode=0o600, exist_ok=True)
    password_file.chmod(0o600)
    password_file.write_bytes(encoded)
    def field(data):
        return struct.pack('!H', len(data)) + data
    authority = BASE / 'Xauthority'
    authority.touch(mode=0o600, exist_ok=True)
    authority.chmod(0o600)
    authority.write_bytes(struct.pack('!H', 65535) + field(b'') + field(b'2') +
                          field(b'MIT-MAGIC-COOKIE-1') + field(secrets.token_bytes(16)))
    env = environment()
    # Bound /tmp rejects hard-link locks; -nolock needs DroidDeck's guest root.
    argv = ['sudo', 'Xtigervnc', ':2', '-nolock', '-geometry', '1280x720', '-depth', '24',
            '-localhost', 'yes', '-SecurityTypes', 'VncAuth', '-PasswordFile',
            str(password_file), '-auth', str(authority), '-rendernode', '', '-nolisten', 'tcp',
            '-desktop', 'DroidDeck Phone-local Linux', '-AlwaysShared',
            '-AcceptCutText', '0', '-SendCutText', '0']
    if unix_path is not None:
        argv += ['-rfbport', '-1', '-rfbunixpath', str(unix_path), '-rfbunixmode', '384']
    with (BASE / 'xvnc.log').open('w') as log:
        process = subprocess.Popen(argv, env=env, stdin=subprocess.DEVNULL,
                                   stdout=log, stderr=subprocess.STDOUT,
                                   start_new_session=True)
    (BASE / 'xvnc.pid').write_text(str(process.pid))
    return {'pid': process.pid, 'endpoint': 'unix:' + str(unix_path) if unix_path else '127.0.0.1:5902', 'display': ':2',
            'authentication': 'VncAuth', 'log': str(BASE / 'xvnc.log')}


def window():
    env = environment()
    with (BASE / 'window.log').open('w') as log:
        subprocess.run(['xsetroot', '-solid', '#16334c'], env=env,
                       stdout=log, stderr=subprocess.STDOUT, check=True)
        process = subprocess.Popen(
            ['xmessage', '-geometry', '850x300+150+150', '-bg', '#f3f7fb',
             '-fg', '#132d46', '-title', 'DroidDeck: Linux on this phone',
             '-buttons', 'Linux display works:0',
             'LINUX DESKTOP DISPLAY TEST\n\n'
             'This Linux application is running on your Mate 20 Pro.\n'
             'The image reaches Android through localhost VNC.\n\n'
             'Google Chrome sandbox verification is a separate step.'],
            env=env, stdin=subprocess.DEVNULL, stdout=log, stderr=subprocess.STDOUT,
            start_new_session=True)
    return {'window_pid': process.pid}


def desktop():
    env = environment()
    subprocess.run(['xsetroot', '-solid', '#16334c'], env=env, check=True)
    with (BASE / 'openbox.log').open('w') as log:
        process = subprocess.Popen(['openbox'], env=env, stdin=subprocess.DEVNULL,
                                   stdout=log, stderr=subprocess.STDOUT,
                                   start_new_session=True)
    return {'window_manager_pid': process.pid}


class X11Relay(socketserver.BaseRequestHandler):
    def handle(self):
        self.request.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        upstream = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        try:
            upstream.connect('/tmp/.X11-unix/X2')
            def relay(source, destination):
                try:
                    while block := source.recv(65536):
                        destination.sendall(block)
                except OSError:
                    pass
                finally:
                    for endpoint in (source, destination):
                        try:
                            endpoint.shutdown(socket.SHUT_RDWR)
                        except OSError:
                            pass
            forward = threading.Thread(target=relay, args=(self.request, upstream), daemon=True)
            forward.start()
            relay(upstream, self.request)
            forward.join()
        except (ConnectionError, OSError):
            pass
        finally:
            upstream.close()


def proxy():
    if not (BASE / 'Xauthority').is_file():
        raise RuntimeError('Start the authenticated display first')
    class Server(socketserver.ThreadingTCPServer):
        allow_reuse_address = True
        daemon_threads = True
    with Server(('127.0.0.1', 6002), X11Relay) as server:
        (BASE / 'proxy.pid').write_text(str(os.getpid()))
        print(json.dumps({'x11_proxy': '127.0.0.1:6002', 'authentication': 'X server MIT cookie'}), flush=True)
        server.serve_forever()


def stop():
    stopped = []
    for entry in Path('/proc').iterdir():
        if not entry.name.isdigit():
            continue
        try:
            if entry.stat().st_uid != os.getuid():
                continue
            args = (entry / 'cmdline').read_bytes().split(b'\0')
            if len(args) > 1 and os.path.basename(args[0]) in (b'Xtigervnc', b'Xvnc') and args[1] == b':2':
                os.kill(int(entry.name), signal.SIGTERM)
                stopped.append(int(entry.name))
        except (OSError, ProcessLookupError):
            continue
    pid_file = BASE / 'proxy.pid'
    if pid_file.exists():
        pid = int(pid_file.read_text())
        command = Path('/proc') / str(pid) / 'cmdline'
        if command.exists() and b'local_display.py\0proxy\0' in command.read_bytes():
            os.kill(pid, signal.SIGTERM)
            stopped.append(pid)
    return {'stopped': stopped}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['start', 'window', 'desktop', 'proxy', 'stop'])
    args = parser.parse_args()
    print(json.dumps(globals()[args.action]()))
