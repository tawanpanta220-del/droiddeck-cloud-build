import ast
import os
from pathlib import Path
import re
import unittest

SOURCE = Path(os.environ.get('CHROME_PREVIEW_GUEST',
              Path(__file__).parent / 'DroidDeck/app/src/main/assets/chrome-preview/guest_chrome.py'))
PROFILE = '/home/droiddeck/.config/droiddeck-chrome-preview'
EXE = '/opt/google/chrome/chrome'


class BrowserIdentityTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        tree = ast.parse(SOURCE.read_text())
        node = next(n for n in tree.body if isinstance(n, ast.FunctionDef) and n.name == 'is_profile_browser')
        scope = {'re': re}
        exec(compile(ast.Module(body=[node], type_ignores=[]), str(SOURCE), 'exec'), scope)
        cls.matches = staticmethod(scope['is_profile_browser'])

    def command(self, separator=b'\0', profile=PROFILE, extra=b''):
        return separator.join([EXE.encode(), b'--disable-gpu', b'--user-data-dir=' + profile.encode(), extra])

    def test_original_argv(self):
        self.assertTrue(self.matches(EXE, self.command(), 1000, PROFILE))

    def test_flattened_chromium_process_title(self):
        self.assertTrue(self.matches(EXE, self.command(b' ') + b'\0', 1000, PROFILE))

    def test_separate_profile_argument(self):
        self.assertTrue(self.matches(EXE, (EXE + ' --user-data-dir ' + PROFILE).encode(), 1000, PROFILE))

    def test_renderer_is_not_the_browser(self):
        for separator in (b'\0', b' '):
            with self.subTest(separator=separator):
                self.assertFalse(self.matches(EXE, self.command(separator, extra=b'--type=renderer'), 1000, PROFILE))

    def test_other_executable_cannot_match_by_command_text(self):
        self.assertFalse(self.matches('/usr/bin/python3', self.command(b' '), 1000, PROFILE))

    def test_another_profile_and_prefix_collision(self):
        for path in ('/home/droiddeck/.config/other', PROFILE + '-other'):
            self.assertFalse(self.matches(EXE, self.command(profile=path), 1000, PROFILE))

    def test_root_process_is_not_the_browser(self):
        self.assertFalse(self.matches(EXE, self.command(), 0, PROFILE))

    def test_deleted_original_executable_still_belongs_to_browser(self):
        self.assertTrue(self.matches(EXE + ' (deleted)', self.command(b' '), 1000, PROFILE))

    def test_missing_profile_cannot_match(self):
        self.assertFalse(self.matches(EXE, EXE.encode(), 1000, PROFILE))


if __name__ == '__main__':
    unittest.main(verbosity=2)
