"""Exercise normal import order before any harness preloads GTK or GDK."""
import os
from pathlib import Path
import subprocess
import sys
import unittest


class StartupTest(unittest.TestCase):
    def test_fresh_process_loads_matching_gtk_and_gdk(self):
        desktop = Path(__file__).resolve().parents[1]
        script = """
import sys
sys.path.insert(0, sys.argv[1])
from astra_linux import app
import gi
from gi.repository import Gtk, Gdk
assert gi.get_required_version('Gtk') == '3.0'
assert gi.get_required_version('Gdk') == '3.0'
assert Gtk.get_major_version() == 3
print('fresh startup import passed')
"""
        env = dict(os.environ)
        env.pop("DISPLAY", None)
        env.pop("WAYLAND_DISPLAY", None)
        result = subprocess.run([sys.executable, "-c", script, str(desktop)], env=env,
                                capture_output=True, text=True, timeout=15)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("fresh startup import passed", result.stdout)


if __name__ == "__main__":
    unittest.main()
