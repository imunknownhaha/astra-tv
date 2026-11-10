"""Regression for libmpv refusing GTK's desktop numeric locale."""
import ctypes.util
import os
from pathlib import Path
import subprocess
import sys
import unittest


@unittest.skipUnless(ctypes.util.find_library('mpv'), 'libmpv is not installed')
class PreviewLocaleTest(unittest.TestCase):
    def test_real_player_creation_after_gtk_adopts_desktop_locale(self):
        script = '''
import ctypes as C
import locale
import sys
sys.path.insert(0, sys.argv[1])
from astra_linux.app import Application
from astra_linux.preview import binding, create_handle
lib = C.CDLL('libmpv.so.2')
binding(lib, 'mpv_create', C.c_void_p)
binding(lib, 'mpv_terminate_destroy', None, C.c_void_p)
# Fedora's en_US locale reproduces the real report, comma decimals cover another case.
for name in ('en_US.UTF-8', 'de_DE.UTF-8'):
    try: locale.setlocale(locale.LC_NUMERIC, name)
    except locale.Error: continue
    assert lib.mpv_create() is None, 'Expected original locale failure'
    before = locale.setlocale(locale.LC_TIME)
    handle = create_handle(lib)
    assert handle, 'Preview creation must recover from the desktop numeric locale'
    assert locale.setlocale(locale.LC_NUMERIC) == 'C'
    assert locale.setlocale(locale.LC_TIME) == before
    lib.mpv_terminate_destroy(handle)
    print('verified', name)
'''
        env = dict(os.environ)
        env.pop('DISPLAY', None); env.pop('WAYLAND_DISPLAY', None)
        result = subprocess.run([sys.executable, '-c', script, str(Path(__file__).resolve().parents[1])], env=env, capture_output=True, text=True, timeout=15)
        self.assertEqual(result.returncode, 0, result.stderr)
        if 'verified' not in result.stdout:
            self.skipTest('en_US/de_DE locale data unavailable')
