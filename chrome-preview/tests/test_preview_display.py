import ast
import os
from pathlib import Path
import re
import types
import unittest


SUPERVISOR = Path(os.environ.get('CHROME_PREVIEW_SUPERVISOR',
                  Path(__file__).parent / 'DroidDeck/app/src/main/assets/chrome-preview/supervisor.py'))
SOURCE = SUPERVISOR.with_name('local_display.py')


class WindowDetectionTests(unittest.TestCase):
    def detect(self, root, windows):
        calls = []
        def identity(argv, **kwargs):
            calls.append(kwargs)
            return types.SimpleNamespace(returncode=windows[argv[2]][0], stdout=windows[argv[2]][1])
        tree = ast.parse(SOURCE.read_text())
        function = next(n for n in tree.body if isinstance(n, ast.FunctionDef) and n.name == 'browser_windows')
        scope = {'re': re, 'environment': lambda: {}, 'subprocess': types.SimpleNamespace(
            check_output=lambda *args, **kwargs: root, run=identity)}
        exec(compile(ast.Module(body=[function], type_ignores=[]), str(SOURCE), 'exec'), scope)
        result = scope['browser_windows']()
        self.assertTrue(all(call['timeout'] == 5 for call in calls))
        return result

    def test_process_without_managed_window_is_not_ready(self):
        self.assertEqual(self.detect('_NET_CLIENT_LIST: no such atom', {}), [])

    def test_only_real_chrome_class_is_counted(self):
        self.assertEqual(self.detect('_NET_CLIENT_LIST(WINDOW): window id # 0x100, 0x200', {
            '0x100': (0, 'WM_CLASS(STRING) = "konsole", "konsole"'),
            '0x200': (0, 'WM_CLASS(STRING) = "google-chrome", "Google-chrome"'),
        }), [512])

    def test_disappearing_window_does_not_count_as_ready(self):
        self.assertEqual(self.detect('_NET_CLIENT_LIST = 0x200', {
            '0x200': (1, '"google-chrome"'),
        }), [])

    def test_lookalike_application_class_is_excluded(self):
        self.assertEqual(self.detect('_NET_CLIENT_LIST = 0x200', {
            '0x200': (0, 'WM_CLASS = "not-google-chrome", "other"'),
        }), [])


if __name__ == '__main__': unittest.main(verbosity=2)
