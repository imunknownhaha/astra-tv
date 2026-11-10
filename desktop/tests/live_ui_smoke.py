"""Native Live TV integration check with generated media and a private X display.

Run: python3 desktop/tests/live_ui_smoke.py --output /tmp/astra-live-evidence
Requires Xvfb, ffmpeg, Mesa/OpenGL and the normal RPM runtime dependencies.
"""
import argparse
import locale
from urllib.parse import urlsplit, parse_qs
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import time


def child(output):
    # Cold-import the application, never preload a different GI namespace.
    sys.path.insert(0, os.environ.get("ASTRA_APP_ROOT", str(Path(__file__).resolve().parents[1])))
    from astra_linux.app import Application
    from astra_linux.core import Library
    from gi.repository import GLib, Gdk, Gtk
    import ctypes as C
    import json
    from functools import partial
    from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
    import threading
    requests = []
    server_mode = {"fail": False}
    class Handler(SimpleHTTPRequestHandler):
        def log_message(self, *_): pass
        def do_GET(self):
            requests.append(self.path)
            if server_mode['fail']:
                self.send_error(404); return
            if self.path.startswith('/live'):
                parsed = urlsplit(self.path)
                if parsed.path.endswith('.ts'):
                    self.send_error(404); return
                self.send_response(302)
                self.send_header('Location', '/hls/index.m3u8?' + parsed.query)
                self.end_headers(); return
            super().do_GET()
    server = ThreadingHTTPServer(('127.0.0.1', 0), partial(Handler, directory=str(output)))
    threading.Thread(target=server.serve_forever, daemon=True).start()
    lib = Library()
    lib.set_setting('allow_http', True); lib.set_setting('guide_refresh', False)
    fixture = output/'fixture.m3u'
    fixture.write_text('#EXTM3U\n' + ''.join('#EXTINF:-1 tvg-id="fixture-%d" group-title="%s",Fixture channel %03d\nhttp://127.0.0.1:%d/test.mp4?channel=%d\n' % (i, 'News' if i % 2 == 0 else 'Sport', i, server.server_address[1], i) for i in range(200)))
    lib.import_source('Generated UI fixture', {'type':'FILE', 'path':str(fixture)})
    with lib.db() as db:
        for item in db.execute('SELECT id,secret FROM items').fetchall():
            payload = lib.open(item['secret'])
            channel = parse_qs(urlsplit(payload['url']).query)['channel'][0]
            payload['provider_id'] = channel
            payload['url'] = 'http://127.0.0.1:%d/live/fixture/fixture/%s.ts?channel=%s' % (server.server_address[1],channel,channel)
            db.execute('UPDATE items SET secret=? WHERE id=?',(lib.seal(payload),item['id']))
    now = int(time.time()); source = lib.sources()[0]['id']
    with lib.db() as db:
        for i in range(200):
            db.execute('INSERT INTO guide VALUES(?,?,?,?,?,?)', (source, 'fixture-%d' % i, now-300, now+300, 'Fixture programme', 'Generated test video used to verify the native preview.'))
            db.execute('INSERT INTO guide VALUES(?,?,?,?,?,?)', (source, 'fixture-%d' % i, now+300, now+1800, 'Fixture next programme', ''))
    app = Application()
    state = {'phase':0, 'ok':False}; started = time.monotonic(); report = {}
    def capture(w, name):
        Gdk.pixbuf_get_from_window(w.get_window(), 0, 0, w.get_allocated_width(), w.get_allocated_height()).savev(str(output/name), 'png', [], [])
    def check():
        w = app.window; live = w.live_view
        try:
            if time.monotonic()-started > 25: raise AssertionError(('Native UI check timed out', state['phase'], w.get_allocated_width(), live.layout))
            if state['phase'] == 0 and len(live.model) == 200:
                assert live.groups.get_visible() and live.group_scroll.get_child().get_mapped()
                locale.setlocale(locale.LC_NUMERIC, os.environ.get('ASTRA_TEST_NUMERIC_LOCALE', 'C'))
                for i in [0,1,2,2]: live.queue(live.model[i][3])
                state['phase'] = 1
            elif state['phase'] == 1 and live.preview.frames >= 30:
                assert locale.setlocale(locale.LC_NUMERIC) == 'C'
                assert live.preview.active_id == live.model[2][3]['id']
                assert all(parse_qs(urlsplit(p).query).get('channel', ['2']) == ['2'] for p in requests), requests
                assert live.preview.stream_index == 1, 'Preview must recover from TS 404 to HLS'
                report.update(hlsFallback=True, redirectedH264Aac=True)
                assert live.now_title.get_text() == 'Fixture programme'
                assert live.next_title.get_text() == 'Fixture next programme'
                assert not lib.browse('History'), 'Hover previews must not create history'
                live.preview.make_current(); live.preview.attach_buffers()
                width, height = live.preview.get_allocated_width(), live.preview.get_allocated_height()
                pixels = (C.c_ubyte * (width*height*4))()
                read = C.CFUNCTYPE(None,C.c_int,C.c_int,C.c_int,C.c_int,C.c_uint,C.c_uint,C.c_void_p)(live.preview.egl.eglGetProcAddress(b'glReadPixels'))
                pixelstore = C.CFUNCTYPE(None,C.c_uint,C.c_int)(live.preview.egl.eglGetProcAddress(b'glPixelStorei'))
                pixelstore(0x0D05,1); pixelstore(0x0D02,0)
                read(0,0,width,height,0x1908,0x1401,C.cast(pixels,C.c_void_p))
                colors = len(set(bytes(pixels)[0::4]))
                assert colors > 20, ('No decoded video pixels', colors)
                report.update(decodedFrames=live.preview.frames, redChannelValues=colors, hoverOpenedOnlyFinalChannel=True, guideDetails=True)
                capture(w, 'live-tv-wide.png')
                w.resize(820,1000); state['resized'] = time.monotonic(); state['phase'] = 2
            elif state['phase'] == 2 and live.layout == 'stack' and time.monotonic()-state['resized'] > 1:
                assert live.main.get_orientation() == Gtk.Orientation.VERTICAL
                assert live.preview.translate_coordinates(live,0,0)[1] > live.tree.translate_coordinates(live,0,0)[1] + 150, 'Preview must stack below channels'
                assert not live.group_scroll.get_visible()
                assert live.category_combo.get_visible()
                capture(w, 'live-tv-narrow.png')
                live.enabled.set_active(False)
                assert live.preview.handle is None and live.preview.timer == 0
                old = live
                w.nav.select_row(w.nav.get_row_at_index(2))
                assert old.closed and old.hover.timer == 0
                w.nav.select_row(w.nav.get_row_at_index(0))
                state['phase'] = 3
            elif state['phase'] == 3 and len(live.model) == 200:
                assert not live.enabled.get_active()
                live.choose_category('Sport'); state['phase'] = 4
            elif state['phase'] == 4 and len(live.model) == 100:
                assert all(row[3]['category'] == 'Sport' for row in live.model)
                live.enabled.set_active(True); live.queue(live.model[0][3]); state['phase'] = 5
            elif state['phase'] == 5 and live.preview.frames >= 3 and live.preview.loaded:
                target = live.selected_item['id']
                live.watch_button.clicked()
                assert live.preview.handle is None and live.preview.timer == 0
                assert w.player.item['id'] == target and w.player.process.poll() is None
                assert len(lib.browse('History')) == 1
                report.update(narrowLayout=True, categoryFilter=True, toggleAndNavigationStopPreview=True, fullscreenHandoff=True)
                state['phase'] = 6
            elif state['phase'] == 6 and w.player.accept_position and w.player.position > 1:
                assert w.player.stream_index == 1, 'Fullscreen must recover from TS 404 to HLS'
                report.update(fullscreenVideoPlaying=True)
                w.player.close(); server_mode['fail'] = True
                live.retry(); state['phase'] = 7
            elif state['phase'] == 7 and 'HTTP 404' in live.preview_status.get_text():
                assert live.preview.handle is None
                live.watch_button.clicked(); state['phase'] = 8
            elif state['phase'] == 8 and 'HTTP 404' in w.status.get_text():
                assert not w.player.accept_position
                assert '127.0.0.1' not in w.status.get_text()
                report.update(failedPreviewAndFullscreenShowHttpError=True)
                state['ok'] = True
                w.closing(); app.quit(); return False
        except Exception:
            import traceback; traceback.print_exc()
            w.closing(); app.quit(); return False
        return True
    GLib.timeout_add(150, check)
    app.run([])
    server.shutdown(); server.server_close()
    assert state['ok'], state
    (output/'ui-verification.json').write_text(json.dumps(report, indent=2)+'\n')
    print(json.dumps(report, indent=2))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--child', action='store_true')
    args = parser.parse_args(); output = args.output.resolve(); output.mkdir(parents=True, exist_ok=True)
    if args.child: child(output); return
    subprocess.run([shutil.which('ffmpeg') or 'ffmpeg', '-hide_banner','-loglevel','error','-f','lavfi','-i','testsrc2=size=320x180:rate=15','-t','25','-c:v','mpeg4','-movflags','+faststart','-y',str(output/'test.mp4')], check=True, timeout=30)
    (output/'hls').mkdir(exist_ok=True)
    subprocess.run([shutil.which('ffmpeg') or 'ffmpeg', '-hide_banner','-loglevel','error','-f','lavfi','-i','testsrc2=size=320x180:rate=15','-f','lavfi','-i','sine=frequency=440:sample_rate=48000','-t','25','-c:v','libx264','-preset','ultrafast','-g','30','-pix_fmt','yuv420p','-c:a','aac','-f','hls','-hls_time','2','-hls_list_size','0','-y',str(output/'hls/index.m3u8')], check=True, timeout=30)
    xvfb = subprocess.Popen([os.environ.get('ASTRA_XVFB', 'Xvfb'), '-displayfd','1','-screen','0','1280x1100x24','-nolisten','tcp'], stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True)
    try:
        display = xvfb.stdout.readline().strip()
        assert display, 'Xvfb failed to start'
        with tempfile.TemporaryDirectory(prefix='astra-live-test-') as data:
            env = dict(os.environ, DISPLAY=':'+display, GDK_BACKEND='x11', XDG_DATA_HOME=data)
            env.pop('WAYLAND_DISPLAY', None)
            subprocess.run([sys.executable, str(Path(__file__).resolve()), '--output', str(output), '--child'], env=env, check=True, timeout=35)
    finally:
        xvfb.terminate(); xvfb.wait(timeout=3)


if __name__ == '__main__': main()
