import importlib.util
import gzip
import hashlib
import json
import os
from pathlib import Path
import tempfile
import types
import unittest
from unittest.mock import patch


SOURCE = Path(os.environ.get('CHROME_PREVIEW_SUPPORT',
              Path(__file__).parent / 'DroidDeck/app/src/main/assets/chrome-preview/runtime_support.py'))
spec = importlib.util.spec_from_file_location('preview_support', SOURCE)
support = importlib.util.module_from_spec(spec)
spec.loader.exec_module(support)


class RuntimeSupportTests(unittest.TestCase):
    def setUp(self):
        temporary_root = Path(__file__).resolve().parent
        self.temporary = tempfile.TemporaryDirectory(dir=temporary_root)
        assert Path(self.temporary.name).resolve().is_relative_to(temporary_root)
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.image = b'verified kernel image' * 100
        self.source = self.root / 'kernel.gz'
        self.source.write_bytes(gzip.compress(self.image))
        self.destination = self.root / 'Image'
        self.pins = {hashlib.sha256(self.source.read_bytes()).hexdigest(): hashlib.sha256(self.image).hexdigest()}
        self.flags = patch.object(support.os, 'O_NOFOLLOW', getattr(os, 'O_NOFOLLOW', 0), create=True)
        self.flags.start()
        self.addCleanup(self.flags.stop)

    def test_first_kernel_build_has_verified_bytes(self):
        result = support.prepare_kernel(self.source, self.destination, self.pins)
        self.assertEqual(self.destination.read_bytes(), self.image)
        self.assertFalse(result['kernel_cache_hit'])
        self.assertEqual(result['kernel_bytes_written'], len(self.image))

    def test_valid_cache_avoids_decompression_and_rewrite(self):
        self.destination.write_bytes(self.image)
        before = self.destination.stat().st_mtime_ns
        with patch.object(support.gzip, 'decompress', side_effect=AssertionError('cache must not decompress')):
            result = support.prepare_kernel(self.source, self.destination, self.pins)
        self.assertTrue(result['kernel_cache_hit'])
        self.assertEqual(result['kernel_bytes_written'], 0)
        self.assertEqual(self.destination.stat().st_mtime_ns, before)

    def test_tampered_cache_is_rebuilt(self):
        self.destination.write_bytes(b'corrupt cached image')
        result = support.prepare_kernel(self.source, self.destination, self.pins)
        self.assertFalse(result['kernel_cache_hit'])
        self.assertEqual(self.destination.read_bytes(), self.image)

    def test_source_change_cannot_reuse_old_pinned_image(self):
        self.destination.write_bytes(self.image)
        self.source.write_bytes(gzip.compress(b'different source'))
        result = support.prepare_kernel(self.source, self.destination, self.pins)
        self.assertFalse(result['kernel_cache_hit'])
        self.assertEqual(self.destination.read_bytes(), b'different source')

    def test_wrong_decompressed_pin_fails_without_overwriting(self):
        self.destination.write_bytes(b'previous image')
        bad = {next(iter(self.pins)): '0' * 64}
        with self.assertRaisesRegex(RuntimeError, 'digest mismatch'):
            support.prepare_kernel(self.source, self.destination, bad)
        self.assertEqual(self.destination.read_bytes(), b'previous image')

    def test_unknown_verified_source_remains_supported(self):
        result = support.prepare_kernel(self.source, self.destination, {})
        self.assertFalse(result['kernel_cache_hit'])
        self.assertEqual(self.destination.read_bytes(), self.image)

    def test_uncompressed_source_is_supported(self):
        self.source.write_bytes(self.image)
        support.prepare_kernel(self.source, self.destination, {})
        self.assertEqual(self.destination.read_bytes(), self.image)

    def test_tail_is_bounded_and_retains_prompt(self):
        log = self.root / 'console.log'
        log.write_bytes(b'old private output' + b'x' * 100000 + b'\nroot@(none):/# ')
        value = support.read_tail(log, 128)
        self.assertLessEqual(len(value), 128)
        self.assertNotIn('private', value)
        self.assertTrue(value.endswith('root@(none):/# '))

    def test_first_url_is_selected_without_deleting_or_reordering_rest(self):
        (self.root / '002.json').write_text(json.dumps({'url': 'https://example.com/two'}))
        first = self.root / '001.json'
        first.write_text(json.dumps({'url': 'https://example.com/one'}))
        self.assertEqual(support.initial_request(self.root), (first, 'https://example.com/one'))
        self.assertTrue(first.exists())

    def test_no_initial_url_and_invalid_request(self):
        self.assertIsNone(support.initial_request(self.root))
        (self.root / 'bad.json').write_text('{"url":42}')
        with self.assertRaises(ValueError): support.initial_request(self.root)

    def test_browser_command_passes_url_as_data_to_single_launch(self):
        url = "https://example.com/?q=';touch /tmp/DO_NOT_RUN;#"
        shell = support.browser_command('start', url)
        self.assertNotIn(url, shell)
        python = shell.split('\n', 1)[1].rsplit('\nPY', 1)[0]
        calls = []
        fake = types.SimpleNamespace(run=lambda argv, **kw: calls.append((argv, kw)))
        with patch.dict('sys.modules', {'subprocess': fake}): exec(python, {})
        self.assertEqual(calls, [(['python3', '/root/chrome-preview.py', 'start', '--url', url], {'check': True})])
        with self.assertRaises(ValueError): support.browser_command('delete', url)

    def test_phase_timings_do_not_reset_same_phase(self):
        tick = [10.0]
        phases = support.PhaseTimings(lambda: tick[0])
        phases.snapshot('kernel')
        tick[0] = 12
        phases.snapshot('kernel')
        tick[0] = 15
        phases.snapshot('chrome_starting')
        tick[0] = 19
        result = phases.snapshot('running')
        self.assertEqual(result['elapsed_seconds'], 9)
        self.assertEqual(result['phase_durations_seconds']['kernel'], 5)
        self.assertEqual(result['phase_durations_seconds']['chrome_starting'], 4)
        self.assertEqual(result['phase_entered_seconds']['running'], 9)


if __name__ == '__main__': unittest.main(verbosity=2)
