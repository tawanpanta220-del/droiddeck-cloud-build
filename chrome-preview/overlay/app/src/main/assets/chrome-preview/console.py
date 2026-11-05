import argparse
import base64
import json
import re
import secrets
import socket
import sys
import time
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument('--timeout', type=int, default=35)
parser.add_argument('--recover', action='store_true')
args = parser.parse_args()
payload = base64.b64encode(sys.stdin.read().encode()).decode()
token = secrets.token_hex(6)
start = '__DD_START_' + token + '__'
end = '__DD_END_' + token + '__'
path = '/root/dd-command-' + token + '.sh'
data = bytearray()
result = None
endpoint = Path('/root/.cache/dv/s')
if endpoint.is_socket():
    connection = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    connection.settimeout(5)
    connection.connect(str(endpoint))
else:
    connection = socket.create_connection(('127.0.0.1', 4422), timeout=5)
with connection:
    connection.settimeout(1)
    deadline = time.monotonic() + args.timeout

    def until(pattern):
        while time.monotonic() < deadline:
            match = re.search(pattern, data)
            if match:
                return match
            try:
                block = connection.recv(65536)
            except socket.timeout:
                continue
            if not block:
                break
            data.extend(block)
        return None

    if args.recover:
        connection.sendall(b'\x03\n')
        time.sleep(0.2)
    encoded_path = path + '.b64'
    connection.sendall(("stty -echo; set +o emacs; set +o vi; : > " + encoded_path +
                        "; printf '\\n" + start + "\\n'\n").encode())
    if not until(rb'\r?\n' + start.encode() + rb'\r?\n'):
        raise RuntimeError('Console did not acknowledge the command')
    # Bound each canonical terminal line and wait for the guest to consume it.
    for offset in range(0, len(payload), 512):
        ack = '__DD_CHUNK_' + token + '_' + str(offset) + '__'
        chunk = payload[offset:offset + 512]
        connection.sendall(("printf '%s' '" + chunk + "' >> " + encoded_path +
                            "; printf '\\n" + ack + "\\n'\n").encode())
        if not until(rb'\r?\n' + ack.encode() + rb'\r?\n'):
            raise RuntimeError('Console did not acknowledge a script chunk')
    command = ('base64 -d ' + encoded_path + ' > ' + path + '; /bin/bash ' + path +
               "; rc=$?; printf '\\n" + end + ":%s\\n' \"$rc\"\n")
    connection.sendall(command.encode())
    while time.monotonic() < deadline:
        try:
            block = connection.recv(65536)
        except socket.timeout:
            continue
        if not block:
            break
        data.extend(block)
        result = re.search(rb'(?:\r?\n)' + end.encode() + rb':(\d+)\r?\n', data)
        if result:
            break
text = data.decode(errors='replace')
begin = re.search(r'(?:\r?\n)' + start + r'\r?\n', text)
if begin:
    text = text[begin.end():]
finish = re.search(r'(?:\r?\n)' + end + r':\d+\r?\n', text)
if finish:
    text = text[:finish.start()]
text = re.sub(r'\r?\n__DD_CHUNK_' + token + r'_\d+__\r?\n', '', text)
print(json.dumps({'complete': result is not None,
                  'exit': int(result.group(1)) if result else None,
                  'stdout': text[-15000:]}))
