import base64
import gzip
import hashlib
import json
import os
import time


VERIFIED_KERNEL_IMAGES = {
    '353bab8eeaad9caf931dd85fd7a7bf8800d2669d759c0369ffaba7abc03a7aff':
        'ce78832564a2705c98d474fe487f11dbd6823eb0c5f5c162ebf1e760089b504f',
}


def prepare_kernel(source, destination, known_images=VERIFIED_KERNEL_IMAGES):
    packed = source.read_bytes()
    source_digest = hashlib.sha256(packed).hexdigest()
    expected = known_images.get(source_digest)
    existing = None
    if destination.is_file() and not destination.is_symlink():
        with destination.open('rb') as stream:
            existing = hashlib.file_digest(stream, 'sha256').hexdigest()
    # A reused image must match the digest derived from the verified source, not its mtime.
    if expected is not None and existing == expected:
        return {'kernel_cache_hit': True, 'kernel_bytes_written': 0}
    image = gzip.decompress(packed) if packed[:2] == b'\x1f\x8b' else packed
    digest = hashlib.sha256(image).hexdigest()
    if expected is not None and digest != expected:
        raise RuntimeError('Verified kernel image digest mismatch')
    if existing == digest:
        return {'kernel_cache_hit': False, 'kernel_bytes_written': 0}
    temporary = destination.with_name(destination.name + '.tmp')
    fd = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_TRUNC | os.O_NOFOLLOW, 0o600)
    with os.fdopen(fd, 'wb') as stream:
        stream.write(image)
        stream.flush()
        os.fsync(stream.fileno())
    temporary.replace(destination)
    return {'kernel_cache_hit': False, 'kernel_bytes_written': len(image)}


def read_tail(path, limit=4096):
    with path.open('rb') as stream:
        stream.seek(0, os.SEEK_END)
        stream.seek(max(0, stream.tell() - limit))
        return stream.read(limit).decode(errors='replace')


def initial_request(directory):
    requests = sorted(directory.glob('*.json'))
    if not requests:
        return None
    path = requests[0]
    value = json.loads(path.read_text())
    if not isinstance(value.get('url'), str):
        raise ValueError('Invalid browser request')
    return path, value['url']


def browser_command(action, url):
    if action not in ('start', 'open'):
        raise ValueError('Invalid browser action')
    encoded = base64.b64encode(json.dumps(url).encode()).decode()
    return ("python3 - <<'PY'\nimport base64,json,subprocess\n"
            "url=json.loads(base64.b64decode('" + encoded + "'))\n"
            "subprocess.run(['python3','/root/chrome-preview.py','" + action +
            "','--url',url],check=True)\nPY\n")


class PhaseTimings:
    def __init__(self, clock=time.monotonic):
        self.clock = clock
        self.started = clock()
        self.current = None
        self.phase_started = self.started
        self.completed = {}
        self.entered = {}

    def snapshot(self, phase):
        now = self.clock()
        if phase != self.current:
            if self.current is not None:
                self.completed[self.current] = self.completed.get(self.current, 0) + now - self.phase_started
            self.current = phase
            self.phase_started = now
            self.entered.setdefault(phase, round(now - self.started, 3))
        durations = dict(self.completed)
        durations[phase] = durations.get(phase, 0) + now - self.phase_started
        return {'elapsed_seconds': round(now - self.started, 3),
                'phase_entered_seconds': dict(self.entered),
                'phase_durations_seconds': {key: round(value, 3) for key, value in durations.items()}}
