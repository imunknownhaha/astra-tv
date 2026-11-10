"""Disk-backed library, bounded imports, and local credential encryption.

Each worker opens its own SQLite connection. WAL lets browsing continue while an
import runs. A failed refresh rolls back without destroying the existing library.
"""
import base64
import gzip
import hashlib
import io
import json
import os
from pathlib import Path
import re
import secrets
import sqlite3
import time
import urllib.parse as urlparse
import urllib.request as request
from urllib.error import HTTPError
from contextlib import contextmanager
from datetime import datetime, timezone

import ijson
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from defusedxml.ElementTree import iterparse

VERSION = "0.1.5"
MAX_DOWNLOAD = 128 * 1024 * 1024


class UserError(Exception):
    """Safe, intentionally authored error suitable for the interface."""


def address(value, allow_http=False):
    value = str(value).strip()
    p = urlparse.urlsplit(value)
    if p.scheme not in ("https", "http") or not p.hostname or p.username or p.password:
        raise UserError("Enter an HTTP or HTTPS address without embedded user information.")
    if p.scheme == "http" and not allow_http:
        raise UserError("This source uses HTTP. Enable HTTP sources in Settings if your provider requires it.")
    return value


class SafeRedirect(request.HTTPRedirectHandler):
    def __init__(self, allow_http):
        self.allow_http = allow_http

    def redirect_request(self, req, fp, code, msg, headers, newurl):
        address(newurl, self.allow_http)
        return super().redirect_request(req, fp, code, msg, headers, newurl)


class BoundedReader:
    def __init__(self, stream, limit=MAX_DOWNLOAD):
        self.stream, self.left = stream, limit

    def read(self, size=-1):
        data = self.stream.read(min(self.left + 1, size) if size >= 0 else self.left + 1)
        self.left -= len(data)
        if self.left < 0:
            raise UserError("Source exceeds the 128 MiB import limit. Use a smaller playlist or guide.")
        return data

    def readinto(self, buf):
        data = self.read(len(buf))
        buf[:len(data)] = data
        return len(data)


@contextmanager
def remote(url, allow_http=False):
    url = address(url, allow_http)
    opener = request.build_opener(SafeRedirect(allow_http))
    req = request.Request(url, headers={"User-Agent": "Astra/" + VERSION, "Accept-Encoding": "identity"})
    try:
        response = opener.open(req, timeout=25)
    except HTTPError as exc:
        exc.close()
        raise UserError("The provider rejected the request. Check the source or retry later.") from None
    with response:
        address(response.url, allow_http)
        yield BoundedReader(response)


def identity(*values):
    return hashlib.sha256("\x00".join(map(str, values)).encode()).hexdigest()


def pin_hash(pin):
    if not re.fullmatch(r"[0-9]{4,8}", pin):
        raise UserError("Use a PIN of 4–8 digits.")
    salt = secrets.token_bytes(16)
    digest = hashlib.pbkdf2_hmac("sha256", pin.encode(), salt, 210000)
    return base64.b64encode(salt + digest).decode()


def pin_verify(pin, saved):
    try:
        raw = base64.b64decode(saved, validate=True)
        return len(raw) == 48 and secrets.compare_digest(raw[16:], hashlib.pbkdf2_hmac("sha256", pin.encode(), raw[:16], 210000))
    except (ValueError, TypeError):
        return False


class Library:
    def __init__(self, root=None):
        self.root = Path(root or Path(os.environ.get("XDG_DATA_HOME", Path.home() / ".local/share")) / "astra")
        self.root.mkdir(parents=True, exist_ok=True, mode=0o700)
        self.root.chmod(0o700)
        key = self.root / "credentials.key"
        if not key.exists():
            fd = os.open(key, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
            with os.fdopen(fd, "wb") as out:
                out.write(secrets.token_bytes(32))
        key.chmod(0o600)
        self.cipher = AESGCM(key.read_bytes())
        self.path = self.root / "library.sqlite3"
        with self.db() as db:
            db.executescript("""
                PRAGMA journal_mode=WAL;
                CREATE TABLE IF NOT EXISTS sources(id TEXT PRIMARY KEY,name TEXT NOT NULL,type TEXT NOT NULL,secret BLOB NOT NULL,updated INTEGER NOT NULL);
                CREATE TABLE IF NOT EXISTS items(id TEXT PRIMARY KEY,source TEXT NOT NULL,name TEXT NOT NULL,sort TEXT NOT NULL,kind TEXT NOT NULL,category TEXT NOT NULL,guide TEXT NOT NULL,secret BLOB NOT NULL,parent TEXT NOT NULL DEFAULT '',generation INTEGER NOT NULL);
                CREATE INDEX IF NOT EXISTS browse ON items(kind,sort,id);
                CREATE INDEX IF NOT EXISTS categories ON items(kind,category,sort,id);
                CREATE INDEX IF NOT EXISTS source_items ON items(source);
                CREATE INDEX IF NOT EXISTS parent_items ON items(parent);
                CREATE TABLE IF NOT EXISTS profiles(id TEXT PRIMARY KEY,name TEXT NOT NULL,pin TEXT NOT NULL,restricted TEXT NOT NULL);
                INSERT OR IGNORE INTO profiles VALUES('home','Home','','[]');
                CREATE TABLE IF NOT EXISTS watch(profile TEXT NOT NULL,item TEXT NOT NULL,favorite INTEGER NOT NULL DEFAULT 0,position REAL NOT NULL DEFAULT 0,duration REAL NOT NULL DEFAULT 0,updated INTEGER NOT NULL DEFAULT 0,PRIMARY KEY(profile,item));
                CREATE TABLE IF NOT EXISTS settings(key TEXT PRIMARY KEY,value TEXT NOT NULL);
                CREATE TABLE IF NOT EXISTS guide(source TEXT NOT NULL,channel TEXT NOT NULL,start INTEGER NOT NULL,stop INTEGER NOT NULL,title TEXT NOT NULL,description TEXT NOT NULL);
                CREATE INDEX IF NOT EXISTS guide_time ON guide(source,channel,start,stop);
            """)
        self.path.chmod(0o600)

    @contextmanager
    def db(self):
        con = sqlite3.connect(self.path, timeout=30)
        con.row_factory = sqlite3.Row
        try:
            with con:
                yield con
        finally:
            con.close()

    def seal(self, value):
        nonce = secrets.token_bytes(12)
        return nonce + self.cipher.encrypt(nonce, json.dumps(value).encode(), b"astra-linux-v1")

    def open(self, value):
        return json.loads(self.cipher.decrypt(value[:12], value[12:], b"astra-linux-v1"))

    def setting(self, key, default=None):
        with self.db() as db:
            row = db.execute("SELECT value FROM settings WHERE key=?", (key,)).fetchone()
            return json.loads(row[0]) if row else default

    def set_setting(self, key, value):
        with self.db() as db:
            db.execute("INSERT OR REPLACE INTO settings VALUES(?,?)", (key, json.dumps(value)))

    def sources(self):
        with self.db() as db:
            return [dict(r) for r in db.execute("SELECT id,name,type,updated FROM sources ORDER BY name COLLATE NOCASE")]

    def profiles(self):
        with self.db() as db:
            return [dict(r) for r in db.execute("SELECT * FROM profiles ORDER BY name")]

    def profile(self):
        selected = self.setting("profile", "home")
        return next((p for p in self.profiles() if p["id"] == selected), self.profiles()[0])

    def save_profile(self, name, pin="", restricted=(), profile_id=None):
        if not name.strip():
            raise UserError("Enter a profile name.")
        if restricted and not self.setting("owner_pin", ""):
            raise UserError("Set a management PIN before restricting categories.")
        profile_id = profile_id or secrets.token_hex(16)
        old = next((p for p in self.profiles() if p["id"] == profile_id), None)
        encoded = pin_hash(pin) if pin else (old["pin"] if old else "")
        with self.db() as db:
            db.execute("INSERT OR REPLACE INTO profiles VALUES(?,?,?,?)", (profile_id, name.strip(), encoded, json.dumps(list(restricted))))

    def delete_profile(self, profile_id):
        if profile_id == "home":
            raise UserError("The Home profile cannot be removed.")
        with self.db() as db:
            db.execute("DELETE FROM profiles WHERE id=?", (profile_id,))
            db.execute("DELETE FROM watch WHERE profile=?", (profile_id,))
        if self.setting("profile") == profile_id:
            self.set_setting("profile", "home")

    def groups(self, kind=""):
        clause = " WHERE kind=?" if kind else ""
        args = (kind,) if kind else ()
        hidden = json.loads(self.profile()["restricted"])
        with self.db() as db:
            return [r[0] for r in db.execute("SELECT DISTINCT category FROM items" + clause + " ORDER BY category COLLATE NOCASE", args) if r[0] not in hidden]

    def browse(self, section="Live TV", query="", category="", limit=200, source=""):
        profile = self.profile()
        hidden = json.loads(profile["restricted"])
        where, args = ["i.parent=''"], [profile["id"]]
        kinds = {"Live TV": "LIVE", "Guide": "LIVE", "Catch-up": "LIVE", "Movies": "MOVIE", "Series": "SERIES"}
        if section in kinds:
            where.append("i.kind=?"); args.append(kinds[section])
        if section == "Favorites": where.append("w.favorite=1")
        if section == "History": where.append("w.updated>0")
        if section == "Continue watching": where.append("w.position>0 AND (w.duration=0 OR w.position<w.duration*0.95) AND i.kind!='LIVE'")
        if query:
            # Literal search, so a '%' in a title cannot turn into a full-catalog wildcard.
            where.append("(i.name LIKE ? ESCAPE '\\' OR i.category LIKE ? ESCAPE '\\')")
            escaped = query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
            args.extend(["%" + escaped + "%"] * 2)
        if category: where.append("i.category=?"); args.append(category)
        if source: where.append("i.source=?"); args.append(source)
        if hidden:
            where.append("i.category NOT IN (" + ",".join("?" for _ in hidden) + ")"); args.extend(hidden)
        sort = "w.updated DESC,i.sort,i.id" if section in ("History", "Continue watching") else "i.sort,i.id"
        args.append(min(max(1, int(limit)), 20000))
        with self.db() as db:
            return [dict(r) for r in db.execute("SELECT i.*,coalesce(w.favorite,0) favorite,coalesce(w.position,0) position,coalesce(w.duration,0) duration FROM items i LEFT JOIN watch w ON w.item=i.id AND w.profile=? WHERE " + " AND ".join(where) + " ORDER BY " + sort + " LIMIT ?", args)]

    def favorite(self, item):
        with self.db() as db:
            db.execute("INSERT INTO watch(profile,item,favorite) VALUES(?,?,1) ON CONFLICT(profile,item) DO UPDATE SET favorite=1-favorite", (self.profile()["id"], item["id"]))

    def progress(self, item_id, profile_id, position, duration):
        with self.db() as db:
            db.execute("INSERT INTO watch(profile,item,position,duration,updated) VALUES(?,?,?,?,?) ON CONFLICT(profile,item) DO UPDATE SET position=excluded.position,duration=excluded.duration,updated=excluded.updated", (profile_id, item_id, max(0, position), max(0, duration), int(time.time())))

    def watch(self, item_id):
        with self.db() as db:
            row = db.execute("SELECT * FROM watch WHERE item=? AND profile=?", (item_id, self.profile()["id"])).fetchone()
            return dict(row) if row else {}

    def delete_source(self, source_id):
        with self.db() as db:
            db.execute("DELETE FROM watch WHERE item IN (SELECT id FROM items WHERE source=?)", (source_id,))
            db.execute("DELETE FROM items WHERE source=?", (source_id,))
            db.execute("DELETE FROM guide WHERE source=?", (source_id,))
            db.execute("DELETE FROM sources WHERE id=?", (source_id,))

    def _put(self, db, source_id, name, kind, category, guide, payload, key, generation, parent=""):
        item_id = identity(source_id, kind, key)
        db.execute("INSERT OR REPLACE INTO items VALUES(?,?,?,?,?,?,?,?,?,?)", (item_id, source_id, str(name or "Untitled"), str(name or "Untitled").lower(), kind, str(category or "Uncategorized"), str(guide or ""), self.seal(payload), parent, generation))
        return item_id

    def import_source(self, name, config, source_id=None, progress=lambda value: None):
        source_id = source_id or secrets.token_hex(16)
        generation = time.time_ns()
        allow = self.setting("allow_http", False)
        kind = config.get("type", "M3U")
        if kind == "XTREAM":
            return self._xtream(source_id, name, config, generation, allow, progress)
        progress("Reading playlist…")
        with self.db() as db:
            if kind == "FILE":
                with open(config["path"], "rb") as file:
                    count, epg = self._m3u(db, source_id, BoundedReader(file), generation, allow, progress)
            else:
                with remote(config["url"], allow) as stream:
                    count, epg = self._m3u(db, source_id, stream, generation, allow, progress)
            if not count: raise UserError("No playable channels found. Check that this is an extended M3U playlist.")
            saved = dict(config, guide=config.get("guide") or epg)
            db.execute("INSERT OR REPLACE INTO sources VALUES(?,?,?,?,?)", (source_id, name.strip() or "My playlist", kind, self.seal(saved), int(time.time())))
            db.execute("DELETE FROM items WHERE source=? AND generation!=?", (source_id, generation))
        return count, []

    def _m3u(self, db, source_id, stream, generation, allow, progress):
        # Incremental UTF-8 decoding bounds memory independently of playlist size.
        reader = stream
        pending, agent, referrer, count, epg = None, "", "", 0, ""
        buffer = b""
        while True:
            data = reader.read(16384)
            if not data and not buffer: break
            buffer += data
            if len(buffer) > 1024 * 1024 and b"\n" not in buffer:
                raise UserError("Playlist contains an excessively long line.")
            lines = buffer.split(b"\n")
            buffer = lines.pop() if data else b""
            if not data and lines == []: lines = [buffer]
            for raw in lines:
                line = raw.decode("utf-8-sig", errors="replace").strip()
                if line.startswith("#EXTM3U"):
                    attrs = dict(re.findall(r'([\w-]+)="([^"]*)"', line))
                    epg = attrs.get("url-tvg", attrs.get("x-tvg-url", "")).split(",")[0]
                elif line.startswith("#EXTINF:"):
                    match = re.search(r",([^\"]*)$", line)
                    name = match.group(1).strip() if match else "Channel"
                    attrs = dict(re.findall(r'([\w-]+)="([^"]*)"', line))
                    pending = (name, attrs)
                    agent = referrer = ""
                elif line.startswith("#EXTVLCOPT:http-user-agent="): agent = line.split("=", 1)[1]
                elif line.startswith("#EXTVLCOPT:http-referrer="): referrer = line.split("=", 1)[1]
                elif line and not line.startswith("#") and pending:
                    name, attrs = pending
                    url = address(line, allow)
                    self._put(db, source_id, name, "LIVE", attrs.get("group-title"), attrs.get("tvg-id"), {"url": url, "agent": agent, "referrer": referrer, "catchup": attrs.get("catchup-source", ""), "catchup_days": attrs.get("catchup-days", "0")}, url, generation)
                    pending = None; count += 1
                    if count % 128 == 0: progress("Imported %s channels…" % count)
            if not data: break
        return count, epg

    def _api(self, config, action="", **extra):
        base = config["server"].rstrip("/")
        params = {"username": config["username"], "password": config["password"]}
        if action: params["action"] = action
        params.update(extra)
        return base + "/player_api.php?" + urlparse.urlencode(params)

    def _xtream(self, source_id, name, config, generation, allow, progress):
        config = dict(config)
        base = address(config["server"], allow).rstrip("/")
        if urlparse.urlsplit(base).query or urlparse.urlsplit(base).fragment:
            raise UserError("Use only the provider server address, without a query or fragment.")
        config["server"] = base
        if not config.get("username") or not config.get("password"): raise UserError("Enter your Xtream username and password.")
        progress("Checking provider…")
        with remote(self._api(config), allow) as stream:
            auth = json.loads(stream.read())
        if str(auth.get("user_info", {}).get("auth", "0")) != "1": raise UserError("Provider rejected the login. Check your server, username and password.")
        config["guide"] = base + "/xmltv.php?" + urlparse.urlencode({"username": config["username"], "password": config["password"]})
        count, warnings = 0, []
        with self.db() as db:
            db.execute("BEGIN")
            for kind, action, category_action in [("LIVE", "get_live_streams", "get_live_categories"), ("MOVIE", "get_vod_streams", "get_vod_categories"), ("SERIES", "get_series", "get_series_categories")]:
                groups = {}
                try:
                    with remote(self._api(config, category_action), allow) as stream:
                        for row in ijson.items(stream, "item"):
                            groups[str(row.get("category_id"))] = row.get("category_name", "Uncategorized")
                except Exception:
                    pass  # Catalog import remains useful if this optional naming endpoint fails.
                progress("Importing %s…" % kind.lower())
                db.execute("SAVEPOINT catalog")
                imported = 0
                try:
                    with remote(self._api(config, action), allow) as stream:
                        # Validate array shape, avoiding a successful empty import of an error object.
                        first = stream.read(1)
                        while first and first.isspace(): first = stream.read(1)
                        if first != b"[": raise UserError("Provider returned an invalid catalog.")
                        class Prefix:
                            def __init__(self): self.prefix = first
                            def read(self, size=-1):
                                if size == 0: return b""
                                head, self.prefix = self.prefix, b""
                                return head + stream.read(size - len(head) if size >= 0 else -1)
                        for row in ijson.items(Prefix(), "item"):
                            key = str(row.get("series_id" if kind == "SERIES" else "stream_id", ""))
                            if not key: continue
                            ext = str(row.get("container_extension", "mp4"))
                            if not re.fullmatch(r"[A-Za-z0-9]{1,12}", ext): ext = "mp4"
                            user, password = [urlparse.quote(str(config[k]), safe="") for k in ("username", "password")]
                            url = base + "/" + ("live" if kind == "LIVE" else "movie") + "/%s/%s/%s.%s" % (user, password, urlparse.quote(key, safe=""), "ts" if kind == "LIVE" else ext)
                            payload = {"url": url, "provider_id": key, "description": str(row.get("plot") or ""), "catchup_days": int(row.get("tv_archive_duration") or 0)}
                            self._put(db, source_id, row.get("name"), kind, groups.get(str(row.get("category_id")), "Uncategorized"), row.get("epg_channel_id"), payload, key, generation)
                            imported += 1
                            if imported % 128 == 0: progress("Imported %s %s items…" % (imported, kind.lower()))
                    db.execute("DELETE FROM items WHERE source=? AND kind=? AND generation!=?", (source_id, kind, generation))
                    db.execute("RELEASE catalog")
                    count += imported
                except Exception:
                    db.execute("ROLLBACK TO catalog"); db.execute("RELEASE catalog")
                    if kind == "LIVE": raise UserError("Live catalog failed. Your previous library was kept. Check the provider and retry.") from None
                    warnings.append("%s catalog unavailable; previous items kept." % kind.title())
            db.execute("INSERT OR REPLACE INTO sources VALUES(?,?,?,?,?)", (source_id, name.strip() or "My provider", "XTREAM", self.seal(config), int(time.time())))
        return count, warnings

    def refresh(self, source_id, progress=lambda value: None):
        with self.db() as db:
            row = db.execute("SELECT * FROM sources WHERE id=?", (source_id,)).fetchone()
        if not row: raise UserError("Playlist no longer exists.")
        return self.import_source(row["name"], self.open(row["secret"]), source_id, progress)

    def episodes(self, item):
        with self.db() as db:
            source = db.execute("SELECT * FROM sources WHERE id=?", (item["source"],)).fetchone()
        config = self.open(source["secret"])
        with remote(self._api(config, "get_series_info", series_id=self.open(item["secret"])["provider_id"]), self.setting("allow_http", False)) as stream:
            root = json.loads(stream.read())
        generation = time.time_ns()
        count = 0
        with self.db() as db:
            for season, episodes in root.get("episodes", {}).items():
                for episode in episodes:
                    key = str(episode.get("id", ""))
                    if not key: continue
                    ext = str(episode.get("container_extension", "mp4"))
                    if not re.fullmatch(r"[A-Za-z0-9]{1,12}", ext): ext = "mp4"
                    url = config["server"].rstrip("/") + "/series/%s/%s/%s.%s" % tuple(urlparse.quote(str(v), safe="") for v in (config["username"], config["password"], key, ext))
                    title = "S%s · E%s  %s" % (season, episode.get("episode_num", ""), episode.get("title", "Episode"))
                    self._put(db, item["source"], title, "EPISODE", item["category"], "", {"url": url, "season": int(season), "episode": int(episode.get("episode_num") or 0)}, key, generation, item["id"])
                    count += 1
            if count: db.execute("DELETE FROM items WHERE parent=? AND generation!=?", (item["id"], generation))
        with self.db() as db:
            result = [dict(r) for r in db.execute("SELECT * FROM items WHERE parent=?", (item["id"],))]
        return sorted(result, key=lambda r: (self.open(r["secret"])["season"], self.open(r["secret"])["episode"]))

    def import_guide(self, source_id, url=""):
        with self.db() as db:
            source = db.execute("SELECT * FROM sources WHERE id=?", (source_id,)).fetchone()
        config = self.open(source["secret"])
        url = url or config.get("guide", "")
        if not url: raise UserError("Enter an XMLTV address for this playlist.")
        allow = self.setting("allow_http", False)
        count = 0
        with remote(url, allow) as stream:
            # gzip and plain XMLTV are accepted, with an independent decompressed cap.
            class Wrapped(io.RawIOBase):
                def readable(self): return True
                def readinto(self, b): return stream.readinto(b)
            buffered = io.BufferedReader(Wrapped())
            xml = BoundedReader(gzip.GzipFile(fileobj=buffered) if buffered.peek(2)[:2] == b"\x1f\x8b" else buffered)
            with self.db() as db:
                db.execute("DELETE FROM guide WHERE source=?", (source_id,))
                root = None
                for event, element in iterparse(xml, events=("start", "end")):
                    if root is None: root = element
                    if event != "end" or element.tag != "programme": continue
                    try:
                        start, stop = xml_time(element.get("start")), xml_time(element.get("stop"))
                        if stop > int(time.time()) - 14 * 86400 and stop > start:
                            db.execute("INSERT INTO guide VALUES(?,?,?,?,?,?)", (source_id, element.get("channel", ""), start, stop, element.findtext("title", "Untitled"), element.findtext("desc", "")))
                            count += 1
                    except (ValueError, TypeError): pass
                    element.clear()
                    root.clear()
                db.execute("UPDATE sources SET secret=? WHERE id=?", (self.seal(dict(config, guide=url)), source_id))
        self.set_setting("guide_updated", int(time.time()))
        return count

    def programs(self, item, past=False):
        now = int(time.time())
        with self.db() as db:
            return [dict(r) for r in db.execute("SELECT * FROM guide WHERE source=? AND channel=? AND stop>? AND start<? ORDER BY start LIMIT 200", (item["source"], item["guide"], now - (7 * 86400 if past else 0), now + (0 if past else 86400)))]

    def catchup(self, item, program):
        payload = self.open(item["secret"])
        days = int(payload.get("catchup_days", 0))
        now = int(time.time())
        if not days or not now - days * 86400 <= program["start"] < now:
            raise UserError("This program is outside the provider's catch-up window.")
        with self.db() as db:
            source = db.execute("SELECT * FROM sources WHERE id=?", (item["source"],)).fetchone()
        config = self.open(source["secret"])
        if source["type"] == "XTREAM":
            start = datetime.fromtimestamp(program["start"], timezone.utc).strftime("%Y-%m-%d:%H-%M")
            duration = max(1, (program["stop"] - program["start"]) // 60)
            return config["server"].rstrip("/") + "/timeshift/%s/%s/%s/%s/%s.ts" % (urlparse.quote(config["username"], safe=""), urlparse.quote(config["password"], safe=""), duration, start, urlparse.quote(payload["provider_id"], safe=""))
        template = payload.get("catchup", "")
        if not template: raise UserError("This playlist supplies no supported catch-up URL.")
        for key, value in {"utc": program["start"], "utcend": program["stop"], "duration": program["stop"] - program["start"], "offset": now - program["start"]}.items():
            template = template.replace("{" + key + "}", str(value))
        if "{" in template: raise UserError("This catch-up template uses unsupported provider placeholders.")
        return address(template, self.setting("allow_http", False))

    def live_now(self, items):
        """Read current-program titles in bounded indexed batches for the channel list."""
        now = int(time.time())
        keys = list(dict.fromkeys((row["source"], row["guide"]) for row in items if row["guide"]))
        result = {}
        with self.db() as db:
            for offset in range(0, len(keys), 100):
                batch = keys[offset:offset + 100]
                values = ",".join("(?,?)" for _ in batch)
                args = [part for pair in batch for part in pair] + [now, now]
                sql = "WITH visible(source,channel) AS (VALUES " + values + ") SELECT g.source,g.channel,g.title FROM visible v JOIN guide g ON g.source=v.source AND g.channel=v.channel WHERE g.start<=? AND g.stop>? ORDER BY g.start DESC"
                for row in db.execute(sql, args): result.setdefault((row["source"], row["channel"]), row["title"])
        return result


def xml_time(value):
    value = value.strip()
    if len(value.split()) == 1: value += " +0000"
    return int(datetime.strptime(value, "%Y%m%d%H%M%S %z").timestamp())
