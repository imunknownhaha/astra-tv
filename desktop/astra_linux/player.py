"""One reusable mpv player, controlled over an anonymous socket pair.

URLs and credentials never appear in argv, shell commands or log messages.
mpv owns the video window and provides its native audio/subtitle/seek controls.
"""
import json
import os
import shutil
import socket
import subprocess
import threading
import time

from .core import UserError, address


class Player:
    def __init__(self, progress=lambda *args: None, status=lambda text: None, ended=lambda: None):
        self.progress, self.status, self.ended = progress, status, ended
        self.process = self.sock = None
        self.lock = threading.RLock()
        self.item = self.profile = None
        self.position = self.duration = 0.0
        self.last_save = 0.0
        self.closed = False
        self.accept_position = False

    def start(self):
        if self.process and self.process.poll() is None: return
        binary = shutil.which("mpv")
        if not binary: raise UserError("mpv is missing. Install the RPM with dnf to include its dependencies.")
        client, child = socket.socketpair()
        try:
            self.process = subprocess.Popen([
                binary, "--no-config", "--load-scripts=no", "--ytdl=no", "--load-unsafe-playlists=no",
                "--idle=yes", "--force-window=yes", "--hwdec=auto-safe", "--cache=yes", "--cache-secs=15",
                "--demuxer-max-bytes=32MiB", "--demuxer-max-back-bytes=8MiB", "--save-position-on-quit=no",
                "--input-terminal=no", "--msg-level=all=no", "--title=Astra", "--input-ipc-client=fd://%d" % child.fileno()
            ], pass_fds=(child.fileno(),), stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        except Exception:
            client.close()
            raise
        finally:
            child.close()
        self.sock = client
        self.closed = False
        threading.Thread(target=self._read, args=(client,), daemon=True).start()
        self.command("observe_property", 1, "time-pos")
        self.command("observe_property", 2, "duration")

    def command(self, *args):
        with self.lock:
            if not self.sock: return
            try: self.sock.sendall(json.dumps({"command": list(args)}).encode() + b"\n")
            except OSError: self.status("Playback closed. Select a title to reopen it.")

    def play(self, item, payload, profile, resume=0, allow_http=False, url=None):
        stream = address(url or payload.get("url", ""), allow_http)
        # Save before replacing identity; updates must belong to the item that played.
        self.save()
        with self.lock:
            self.start()
            self.accept_position = False
            self.item, self.profile = item, profile
            self.position, self.duration = float(resume), 0.0
            options = {"start": str(max(0, resume))}
            if payload.get("agent"): options["user-agent"] = payload["agent"]
            if payload.get("referrer"): options["referrer"] = payload["referrer"]
            self.command("loadfile", stream, "replace", -1, options)
        self.status("Opening playback window…")

    def save(self):
        with self.lock:
            if self.item:
                try: self.progress(self.item["id"], self.profile, self.position, self.duration)
                except Exception: self.status("Could not save viewing progress. Check available disk space.")

    def _read(self, client):
        try:
            with client.makefile("rb") as reader:
                for line in reader:
                    if len(line) > 1024 * 1024: break
                    try: event = json.loads(line)
                    except (ValueError, TypeError): continue
                    if event.get("event") == "property-change":
                        value = event.get("data")
                        with self.lock:
                            if self.accept_position and isinstance(value, (int, float)):
                                if event.get("name") == "time-pos": self.position = max(0, value)
                                if event.get("name") == "duration": self.duration = max(0, value)
                        if time.monotonic() - self.last_save > 10:
                            self.save(); self.last_save = time.monotonic()
                    elif event.get("event") == "file-loaded":
                        self.accept_position = True
                        self.status("Playing in mpv · Space: pause · F: full screen · J: subtitles · #: audio")
                    elif event.get("event") == "end-file":
                        if event.get("reason") in ("eof", "error"): self.save()
                        if event.get("reason") == "error": self.status("Playback failed. Check your connection or codec support, then select Retry playback.")
                        elif event.get("reason") == "eof": self.status("Playback finished."); self.ended()
        except OSError:
            pass
        finally:
            if client is self.sock: self.save()
            if not self.closed and client is self.sock: self.status("Playback window closed. Select a title to play again.")

    def close(self):
        self.save()
        self.closed = True
        self.command("quit")
        process, sock = self.process, self.sock
        if process:
            try: process.wait(timeout=3)
            except subprocess.TimeoutExpired: process.terminate()
        if sock: sock.close()
        self.sock = self.process = None
