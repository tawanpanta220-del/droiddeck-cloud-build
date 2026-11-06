import ast
import json
import os
from pathlib import Path
import subprocess
import sys
import types
import unittest


SOURCE = Path(os.environ.get('CHROME_PREVIEW_SUPERVISOR',
              Path(__file__).parent / 'DroidDeck/app/src/main/assets/chrome-preview/supervisor.py'))


class ConsoleResultTests(unittest.TestCase):
    def setUp(self):
        tree = ast.parse(SOURCE.read_text())
        selected = [node for node in tree.body if isinstance(node, (ast.ClassDef, ast.FunctionDef))
                    and node.name in ('GuestCommandError', 'console')]
        self.response = types.SimpleNamespace(returncode=0, stdout='', stderr='')
        self.scope = {'subprocess': types.SimpleNamespace(run=lambda *a, **kw: self.response),
                      'sys': sys, 'TOOLS': SOURCE.parent, 'json': json}
        exec(compile(ast.Module(body=selected, type_ignores=[]), str(SOURCE), 'exec'), self.scope)

    def test_nonzero_guest_exit_is_distinct_from_timeout(self):
        self.response.stdout = json.dumps({'complete': True, 'exit': 1, 'stdout': 'profile not Normal'})
        with self.assertRaises(self.scope['GuestCommandError']) as error:
            self.scope['console']('stop')
        self.assertEqual(error.exception.exit_code, 1)

    def test_incomplete_command_cannot_be_reported_as_clean(self):
        self.response.stdout = json.dumps({'complete': False, 'exit': None, 'stdout': ''})
        with self.assertRaises(TimeoutError):
            self.scope['console']('stop')

    def test_transport_diagnostic_does_not_disclose_stderr(self):
        self.response.returncode = 1
        self.response.stderr = 'private-cookie=DO_NOT_EXPOSE; Console did not acknowledge the command'
        with self.assertRaises(RuntimeError) as error:
            self.scope['console']('stop')
        self.assertIn('acknowledge', str(error.exception))
        self.assertNotIn('DO_NOT_EXPOSE', str(error.exception))

    def test_success_preserves_result(self):
        result = {'complete': True, 'exit': 0, 'stdout': 'saved'}
        self.response.stdout = json.dumps(result)
        self.assertEqual(self.scope['console']('sync'), result)

    def test_invalid_response_is_not_success(self):
        self.response.stdout = 'incomplete protocol response'
        with self.assertRaises(json.JSONDecodeError):
            self.scope['console']('stop')


if __name__ == '__main__':
    unittest.main(verbosity=2)
