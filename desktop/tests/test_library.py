import gzip
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import io
import json
from pathlib import Path
import tempfile
import threading
import time
import unittest
from urllib.parse import parse_qs, urlsplit

from astra_linux.core import Library, UserError, BoundedReader, address, identity, pin_hash, pin_verify


class Handler(BaseHTTPRequestHandler):
    fail_live = False
    fail_optional = False
    xml = b""
    m3u = b""
    def log_message(self, *args): pass
    def do_GET(self):
        parts = urlsplit(self.path)
        if parts.path == "/guide.xml": data = self.xml
        elif parts.path == "/guide.gz": data = gzip.compress(self.xml)
        elif parts.path == "/playlist.m3u": data = self.m3u
        else:
            action = parse_qs(parts.query).get("action", [""])[0]
            if action == "get_live_streams" and self.fail_live or action in ("get_vod_streams", "get_series") and self.fail_optional:
                self.send_response(503); self.end_headers(); return
            values = {
                "": {"user_info": {"auth": 1}},
                "get_live_categories": [{"category_id": "1", "category_name": "News"}],
                "get_live_streams": [{"stream_id": 11, "name": "World News", "category_id": "1", "epg_channel_id": "news", "tv_archive_duration": 7}],
                "get_vod_categories": [{"category_id": "2", "category_name": "Cinema"}],
                "get_vod_streams": [{"stream_id": 22, "name": "Test Movie", "category_id": "2", "container_extension": "mp4"}],
                "get_series_categories": [{"category_id": "3", "category_name": "Shows"}],
                "get_series": [{"series_id": 33, "name": "Test Show", "category_id": "3"}],
                "get_series_info": {"episodes": {"1": [{"id": "44", "episode_num": 1, "title": "Pilot", "container_extension": "mp4"}, {"id": "45", "episode_num": 2, "title": "Next", "container_extension": "mp4"}]}}
            }
            data = json.dumps(values[action]).encode()
        self.send_response(200); self.send_header("Content-Length", str(len(data))); self.end_headers(); self.wfile.write(data)


class LibraryTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        cls.base = "http://127.0.0.1:%s" % cls.server.server_address[1]
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True); cls.thread.start()

    @classmethod
    def tearDownClass(cls): cls.server.shutdown(); cls.server.server_close()

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.library = Library(Path(self.temp.name) / "data")
        self.library.set_setting("allow_http", True)
        Handler.fail_live = Handler.fail_optional = False
        now = int(time.time())
        date = lambda t: time.strftime("%Y%m%d%H%M%S +0000", time.gmtime(t))
        Handler.xml = ('<tv><programme channel="news" start="%s" stop="%s"><title>Current bulletin</title><desc>Fixture</desc></programme><programme channel="news" start="%s" stop="%s"><title>Past bulletin</title></programme></tv>' % (date(now - 60), date(now + 600), date(now - 3600), date(now - 3000))).encode()
        self.playlist = Path(self.temp.name) / "fixture.m3u"
        self.playlist.write_text('#EXTM3U\n#EXTINF:-1 tvg-id="news" group-title="News",World News\n#EXTVLCOPT:http-user-agent=Fixture Agent\n' + self.base + '/live/secretuser/secretpass/11.ts')
        Handler.m3u = self.playlist.read_bytes()
        self.config = {"type": "XTREAM", "server": self.base, "username": "secretuser", "password": "secretpass"}

    def tearDown(self): self.temp.cleanup()

    def local(self):
        return self.library.import_source("Fixture", {"type": "FILE", "path": str(self.playlist)})

    def test_m3u_last_line_and_credential_encryption(self):
        count, warnings = self.local()
        self.assertEqual(count, 1)
        item = self.library.browse()[0]
        self.assertEqual(item["name"], "World News")
        self.assertEqual(self.library.open(item["secret"])["agent"], "Fixture Agent")
        self.assertNotIn(b"secretpass", self.library.path.read_bytes())
        self.assertEqual((self.library.root / "credentials.key").stat().st_mode & 0o777, 0o600)
        self.assertEqual(self.library.root.stat().st_mode & 0o777, 0o700)
        self.assertEqual(Library(self.library.root).open(item["secret"])["url"], self.base + '/live/secretuser/secretpass/11.ts')

    def test_refresh_keeps_favorite_progress_and_rolls_back_failure(self):
        self.local(); source = self.library.sources()[0]
        item = self.library.browse()[0]
        self.library.favorite(item); self.library.progress(item["id"], "home", 120, 600)
        self.library.refresh(source["id"])
        self.assertEqual(self.library.watch(item["id"])["favorite"], 1)
        self.assertEqual(self.library.watch(item["id"])["position"], 120)
        self.playlist.write_text("invalid playlist")
        with self.assertRaises(UserError): self.library.refresh(source["id"])
        self.assertEqual(self.library.browse()[0]["id"], item["id"])

    def test_xtream_catalogs_series_and_optional_failure(self):
        count, warnings = self.library.import_source("Provider", self.config)
        self.assertEqual(count, 3); self.assertFalse(warnings)
        source = self.library.sources()[0]
        series = self.library.browse("Series")[0]
        self.assertEqual(len(self.library.episodes(series)), 2)
        self.assertEqual(len(self.library.browse("Search")), 3)
        Handler.fail_optional = True
        count, warnings = self.library.refresh(source["id"])
        self.assertEqual(count, 1); self.assertEqual(len(warnings), 2)
        self.assertEqual(len(self.library.browse("Movies")), 1)
        Handler.fail_live = True
        with self.assertRaises(UserError): self.library.refresh(source["id"])
        self.assertEqual(len(self.library.browse("Live TV")), 1)

    def test_xmltv_plain_gzip_catchup_and_unsafe_xml_rollback(self):
        self.library.import_source("Provider", self.config)
        source = self.library.sources()[0]; item = self.library.browse()[0]
        self.assertEqual(self.library.import_guide(source["id"], self.base + "/guide.gz"), 2)
        self.assertEqual(len(self.library.programs(item)), 1)
        programs = self.library.programs(item, True)
        self.assertEqual(len(programs), 2)
        url = self.library.catchup(item, programs[0])
        self.assertIn("/timeshift/secretuser/secretpass/10/", url)
        Handler.xml = b'<!DOCTYPE tv [<!ENTITY x SYSTEM "file:///etc/passwd">]><tv><programme>&x;</programme></tv>'
        with self.assertRaises(Exception): self.library.import_guide(source["id"], self.base + "/guide.xml")
        self.assertEqual(len(self.library.programs(item)), 1)

    def test_http_opt_in_url_validation_and_download_bound(self):
        for url in ["file:///etc/passwd", "javascript:alert(1)", "https://user:password@example.com", "http://example.com"]:
            with self.assertRaises(UserError): address(url)
        self.assertEqual(address("https://example.com/playlist"), "https://example.com/playlist")
        with self.assertRaises(UserError): BoundedReader(io.BytesIO(b"12345"), 4).read()
        self.library.set_setting("allow_http", False)
        with self.assertRaises(UserError): self.local()
        self.assertFalse(self.library.sources())

    def test_profiles_restrictions_and_literal_search(self):
        self.local()
        with self.assertRaises(UserError): self.library.save_profile("Child", restricted=["News"])
        self.library.set_setting("owner_pin", pin_hash("1234"))
        self.library.save_profile("Child", "5678", ["News"], "child")
        self.library.set_setting("profile", "child")
        self.assertFalse(self.library.browse())
        self.assertFalse(self.library.groups())
        self.library.set_setting("profile", "home")
        self.assertFalse(self.library.browse("Search", "%"))
        self.assertEqual(len(self.library.browse("Search", "world")), 1)
        self.library.delete_profile("child")
        self.assertEqual(len(self.library.profiles()), 1)

    def test_pin_hashing_rejects_invalid_pins(self):
        saved = pin_hash("1234")
        self.assertTrue(pin_verify("1234", saved))
        self.assertFalse(pin_verify("0000", saved))
        self.assertFalse(pin_verify("1234", "corrupt"))
        for pin in ["abcde", "123", "123456789"]:
            with self.assertRaises(UserError): pin_hash(pin)

    def test_20k_library_is_indexed_paged_and_source_removal_cleans_state(self):
        self.local(); source = self.library.sources()[0]
        encrypted = self.library.seal({"url": "https://example.com/live"})
        with self.library.db() as db:
            db.executemany("INSERT INTO items VALUES(?,?,?,?,?,?,?,?,?,?)", [(identity(i), source["id"], "Channel %05d" % i, "channel %05d" % i, "LIVE", "News", "", encrypted, "", 1) for i in range(20000)])
            plans = [row[3] for row in db.execute("EXPLAIN QUERY PLAN SELECT * FROM items WHERE kind='LIVE' ORDER BY sort,id LIMIT 200")]
        self.assertTrue(any("browse" in row for row in plans))
        self.assertFalse(any("TEMP B-TREE" in row for row in plans))
        self.assertEqual(len(self.library.browse()), 200)
        self.assertEqual(len(self.library.browse(limit=400)), 400)
        item = self.library.browse()[0]; self.library.favorite(item)
        self.library.delete_source(source["id"])
        self.assertFalse(self.library.browse())
        self.assertFalse(self.library.watch(item["id"]))

    def test_m3u_remote_import(self):
        count, _ = self.library.import_source("Remote", {"type": "M3U", "url": self.base + "/playlist.m3u"})
        self.assertEqual(count, 1)


if __name__ == "__main__": unittest.main()
