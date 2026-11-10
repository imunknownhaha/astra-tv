"""Native GTK library interface; all I/O and parsing run outside the UI thread."""
import concurrent.futures
from datetime import datetime
import json
import os
from pathlib import Path
import sys
import time
import threading

import gi
gi.require_version("Gtk", "3.0")
gi.require_version("Gdk", "3.0")
from gi.repository import Gdk, GLib, Gtk, Pango

from .core import Library, UserError, VERSION, pin_hash, pin_verify
from .player import Player
from .live import LiveTv

SECTIONS = ["Live TV", "Guide", "Movies", "Series", "Catch-up", "Search", "Favorites", "History", "Continue watching", "Playlists", "Profiles", "Settings", "About"]
CSS = b"""
window { background: #0b1015; color: #f5f7fa; }
headerbar { background: #141c24; color: #f5f7fa; border-bottom: 1px solid #293743; }
.sidebar { background: #141c24; }
.sidebar row { padding: 10px 12px; border-radius: 6px; margin: 2px 8px; }
.sidebar row:selected { background: #1b2732; color: #48d6e8; }
button { min-height: 28px; padding: 5px 12px; }
button.suggested-action { background: #48d6e8; color: #0b1015; }
button:focus, entry:focus { border-color: #48d6e8; }
.title { font-size: 23px; font-weight: bold; }
.preview-title { font-size: 18px; font-weight: bold; }
glarea { background: #000000; }
.muted { color: #9caab7; }
treeview { background: #0b1015; color: #f5f7fa; }
treeview:selected { background: #1b2732; color: #f5f7fa; }
treeview header button { background: #141c24; color: #9caab7; }
.status { background: #141c24; padding: 12px 16px; }
"""


def label(text, style=None):
    widget = Gtk.Label(label=text, xalign=0)
    widget.set_line_wrap(True)
    widget.set_max_width_chars(90)
    if style: widget.get_style_context().add_class(style)
    return widget


def button(text, action, primary=False):
    widget = Gtk.Button(label=text)
    widget.connect("clicked", lambda _: action())
    if primary: widget.get_style_context().add_class("suggested-action")
    return widget


def scroll(child):
    box = Gtk.ScrolledWindow()
    box.set_policy(Gtk.PolicyType.AUTOMATIC, Gtk.PolicyType.AUTOMATIC)
    box.add(child)
    return box


class Window(Gtk.ApplicationWindow):
    def __init__(self, app):
        super().__init__(application=app, title="Astra")
        self.set_default_size(1100, 720)
        self.set_size_request(720, 480)
        self.library = Library()
        self.jobs = concurrent.futures.ThreadPoolExecutor(max_workers=1, thread_name_prefix="astra-import")
        self.queries = concurrent.futures.ThreadPoolExecutor(max_workers=1, thread_name_prefix="astra-query")
        self.query_generation = 0
        self.busy = False
        self.closed = False
        self.management = False
        self.pin_failures = 0
        self.pin_blocked = 0
        self.section = "Live TV"
        self.limit = 200
        self.source_filter = ""
        self.live_view = None
        self.last_play = None
        self.episode_list = []
        self.player = Player(self.library.progress, lambda text: GLib.idle_add(self.set_status, text), lambda: GLib.idle_add(self.playback_ended))
        self.connect("delete-event", self.closing)
        self.connect("focus-out-event", self.pause_preview)
        header = Gtk.HeaderBar(title="Astra", subtitle="Your library, on Linux")
        header.set_show_close_button(True)
        header.pack_end(button("Add playlist", lambda: self.protect(self.add_playlist), True))
        self.set_titlebar(header)
        root = Gtk.Box(orientation=Gtk.Orientation.VERTICAL)
        self.add(root)
        split = Gtk.Box()
        root.pack_start(split, True, True, 0)
        left = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=12)
        left.set_size_request(184, -1)
        left.get_style_context().add_class("sidebar")
        self.profile_label = label(self.library.profile()["name"], "muted")
        self.profile_label.set_margin_start(20); self.profile_label.set_margin_top(20)
        left.pack_start(self.profile_label, False, False, 0)
        self.nav = Gtk.ListBox(selection_mode=Gtk.SelectionMode.SINGLE)
        for name in SECTIONS:
            row = Gtk.ListBoxRow()
            row.name = name
            row.add(label(name))
            self.nav.add(row)
        self.nav.connect("row-selected", self.navigation)
        left.pack_start(scroll(self.nav), True, True, 0)
        split.pack_start(left, False, False, 0)
        self.content = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=16)
        self.content.set_border_width(24)
        split.pack_start(self.content, True, True, 0)
        self.status = label("Select a channel or title to play." if self.library.sources() else "Connect an M3U playlist or Xtream provider to begin.")
        self.status.get_style_context().add_class("status")
        root.pack_end(self.status, False, False, 0)
        self.nav.select_row(self.nav.get_row_at_index(0))
        self.show_all()
        if self.library.setting("guide_refresh", True) and int(time.time()) - self.library.setting("guide_updated", 0) > 43200:
            GLib.timeout_add_seconds(3, self.refresh_guides)

    def set_status(self, text):
        if not self.closed: self.status.set_text(text)
        return False

    def task(self, function, done=None):
        if self.busy:
            self.set_status("Another import or library operation is running. Please wait.")
            return
        self.busy = True
        self.set_status("Working…")
        future = self.jobs.submit(function)
        def finished(f):
            def apply():
                self.busy = False
                if self.closed: return False
                try:
                    result = f.result()
                    if done: done(result)
                    else: self.set_status("Library updated."); self.render()
                except UserError as exc: self.set_status(str(exc))
                except Exception: self.set_status("Operation failed. Check the source, network and available disk space, then retry. Your existing library was kept.")
                return False
            GLib.idle_add(apply)
        future.add_done_callback(finished)

    def progress(self, text):
        GLib.idle_add(self.set_status, text)

    def protect(self, action):
        saved = self.library.setting("owner_pin", "")
        if saved and not self.management:
            def accepted():
                self.management = True; action()
            self.unlock(saved, accepted)
        else: action()

    def unlock(self, saved, action):
        if time.monotonic() < self.pin_blocked:
            self.set_status("Too many PIN attempts. Try again in one minute."); return
        fields = self.form("Enter PIN", [("pin", "PIN", "", True)], "Unlock")
        if fields is None: return
        # PBKDF2 runs in a worker, keeping keyboard and rendering responsive.
        def result(valid):
            if valid: self.pin_failures = 0; action()
            else:
                self.pin_failures += 1
                if self.pin_failures >= 5: self.pin_blocked = time.monotonic() + 60; self.pin_failures = 0
                self.set_status("Incorrect PIN.")
        self.task(lambda: pin_verify(fields["pin"], saved), result)

    def navigation(self, _, row):
        if not row: return
        target = row.name
        def navigate():
            self.section = target
            self.limit = 200
            if target not in ("Playlists", "Settings", "Profiles", "About"): self.management = False
            self.render()
        if target in ("Playlists", "Settings", "Profiles"):
            self.protect(navigate)
        else: navigate()

    def pause_preview(self, *_):
        if self.live_view: self.live_view.pause()
        return False

    def render(self):
        if self.live_view: self.live_view.close(); self.live_view = None
        self.query_generation += 1
        for child in self.content.get_children(): child.destroy()
        self.profile_label.set_text(self.library.profile()["name"])
        self.content.pack_start(label(self.section, "title"), False, False, 0)
        if self.section == "Live TV":
            self.live_view = LiveTv(self)
            self.content.pack_start(self.live_view, True, True, 0)
        elif self.section == "Playlists": self.playlists()
        elif self.section == "Settings": self.settings()
        elif self.section == "Profiles": self.profiles()
        elif self.section == "About": self.about()
        else: self.catalog()
        self.content.show_all()

    def catalog(self):
        filters = Gtk.Box(spacing=8)
        self.search = Gtk.SearchEntry(placeholder_text="Search titles and categories")
        self.search.connect("search-changed", lambda _: self.reload())
        filters.pack_start(self.search, True, True, 0)
        self.category = Gtk.ComboBoxText()
        self.category.append("", "All categories")
        for group in self.library.groups(): self.category.append(group, group)
        self.category.set_active(0)
        self.category.set_size_request(120, -1)
        self.category.connect("changed", lambda _: self.reload())
        filters.pack_start(self.category, False, False, 0)
        self.content.pack_start(filters, False, False, 0)
        sources = self.library.sources()
        if len(sources) > 1:
            chooser = Gtk.ComboBoxText()
            chooser.append("", "All playlists")
            for source in sources: chooser.append(source["id"], source["name"])
            chooser.set_active_id(self.source_filter)
            def choose(combo): self.source_filter = combo.get_active_id() or ""; self.reload()
            chooser.connect("changed", choose)
            self.content.pack_start(chooser, False, False, 0)
        self.model = Gtk.ListStore(bool, str, str, str, object)
        self.tree = Gtk.TreeView(model=self.model)
        self.tree.set_activate_on_single_click(False)
        toggle = Gtk.CellRendererToggle()
        toggle.connect("toggled", self.toggle_favorite)
        self.tree.append_column(Gtk.TreeViewColumn("Saved", toggle, active=0))
        for title, col in [("Title", 1), ("Category", 2), ("Viewing", 3)]:
            cell = Gtk.CellRendererText(ellipsize=Pango.EllipsizeMode.END, ypad=12)
            column = Gtk.TreeViewColumn(title, cell, text=col)
            column.set_resizable(True)
            column.set_expand(col == 1)
            column.set_min_width(100)
            if col == 2: column.set_max_width(180)
            self.tree.append_column(column)
        self.tree.connect("row-activated", lambda tree, path, col: self.activate(self.model[path][4]))
        self.content.pack_start(scroll(self.tree), True, True, 0)
        actions = Gtk.Box(spacing=8)
        actions.pack_start(button("Play / details", self.activate_selected, True), False, False, 0)
        actions.pack_start(button("Load more", self.more), False, False, 0)
        actions.pack_start(button("Retry playback", self.retry), False, False, 0)
        if self.episode_list: actions.pack_start(button("Next episode", self.next_episode), False, False, 0)
        self.count = label("Loading library…", "muted")
        self.content.pack_start(self.count, False, False, 0)
        self.content.pack_start(actions, False, False, 0)
        self.reload()

    def reload(self):
        if self.closed or not hasattr(self, "search"): return
        self.query_generation += 1
        generation = self.query_generation
        section, query, category, limit, source = self.section, self.search.get_text(), self.category.get_active_id() or "", self.limit, self.source_filter
        model, count = self.model, self.count
        # The single worker naturally serializes reads; stale queued searches skip work.
        def query_rows():
            if generation != self.query_generation: return None
            return self.library.browse(section, query, category, limit, source)
        future = self.queries.submit(query_rows)
        def done(f):
            def apply():
                if self.closed or generation != self.query_generation: return False
                try:
                    rows = f.result()
                    if rows is None: return False
                    model.clear()
                    for row in rows:
                        viewing = "Watched" if row["duration"] > 0 and row["position"] >= row["duration"] * .95 else "Resume %s min" % int(row["position"] // 60) if row["position"] > 0 and row["kind"] != "LIVE" else ""
                        model.append([bool(row["favorite"]), row["name"], row["category"], viewing, row])
                    count.set_text("%s items shown · Double-click or select Play / details" % len(rows) if rows else "No matching items. Add a playlist or choose another category.")
                except Exception: self.set_status("Could not read the library. Check available disk space and restart Astra.")
                return False
            GLib.idle_add(apply)
        future.add_done_callback(done)

    def more(self):
        self.limit = min(20000, self.limit + 200)
        self.reload()

    def toggle_favorite(self, _, path):
        row = self.model[path]
        self.library.favorite(row[4])
        row[0] = not row[0]
        if self.section == "Favorites": self.reload()

    def activate_selected(self):
        model, selected = self.tree.get_selection().get_selected()
        if selected: self.activate(model[selected][4])
        else: self.set_status("Select a channel or title first.")

    def activate(self, item):
        if item["kind"] == "SERIES":
            self.task(lambda: self.library.episodes(item), lambda episodes: self.show_episodes(item, episodes))
        elif self.section in ("Guide", "Catch-up"):
            self.task(lambda: self.library.programs(item, self.section == "Catch-up"), lambda programs: self.show_programs(item, programs))
        elif item["kind"] in ("MOVIE", "EPISODE") and self.library.watch(item["id"]).get("position", 0) > 0:
            self.choose(item["name"], [("Resume", lambda: self.play(item)), ("Start over", lambda: self.play(item, start_over=True))])
        else: self.play(item)

    def play(self, item, url=None, start_over=False):
        self.pause_preview()
        hidden = json.loads(self.library.profile()["restricted"])
        if item["category"] in hidden: self.set_status("This category is restricted in the active profile."); return
        try:
            payload = self.library.open(item["secret"])
            watch = self.library.watch(item["id"])
            resume = 0 if start_over or item["kind"] == "LIVE" or watch.get("duration", 0) > 0 and watch.get("position", 0) >= watch["duration"] * .95 else watch.get("position", 0)
            self.player.play(item, payload, self.library.profile()["id"], resume, self.library.setting("allow_http", False), url, self.library.setting("live_format", "auto"))
            self.last_play = item, url
            self.library.progress(item["id"], self.library.profile()["id"], resume, 0)
        except UserError as exc: self.set_status(str(exc))
        except Exception: self.set_status("Could not open playback. Check the source and that mpv is installed.")

    def retry(self):
        if self.last_play: self.play(*self.last_play)
        else: self.set_status("Select a title before retrying playback.")

    def next_episode(self):
        if not self.last_play: return
        index = next((i for i, row in enumerate(self.episode_list) if row["id"] == self.last_play[0]["id"]), -1)
        if 0 <= index < len(self.episode_list) - 1: self.play(self.episode_list[index + 1])
        else: self.set_status("No next episode is loaded.")

    def playback_ended(self):
        self.set_status("Playback finished. Select Next episode or another title to continue.")
        return False

    def show_episodes(self, item, episodes):
        self.episode_list = episodes
        if not episodes: self.set_status("The provider returned no episodes."); return
        self.choose(item["name"], [(row["name"], lambda row=row: self.play(row)) for row in episodes])
        self.render()

    def show_programs(self, item, programs):
        rows = [("Watch live", lambda: self.play(item))]
        for program in programs:
            when = datetime.fromtimestamp(program["start"]).strftime("%a %H:%M")
            def detail(program=program):
                dialog = Gtk.MessageDialog(transient_for=self, modal=True, message_type=Gtk.MessageType.INFO, buttons=Gtk.ButtonsType.CLOSE, text=program["title"])
                dialog.format_secondary_text(program["description"][:8000] or "No description supplied.")
                if program["start"] < time.time(): dialog.add_button("Watch catch-up", 1)
                answer = dialog.run(); dialog.destroy()
                if answer == 1:
                    try: self.play(item, self.library.catchup(item, program))
                    except UserError as exc: self.set_status(str(exc))
                    except Exception: self.set_status("Catch-up is unavailable for this program.")
            rows.append((when + "  " + program["title"], detail))
        if not programs: self.set_status("No guide data. Import an XMLTV guide from Playlists.")
        self.choose(item["name"], rows)

    def choose(self, title, actions):
        dialog = Gtk.Dialog(title=title, transient_for=self, modal=True)
        dialog.set_default_size(620, 480)
        dialog.add_button("Close", Gtk.ResponseType.CLOSE)
        listing = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=8)
        listing.set_border_width(16)
        for text, action in actions:
            def activate(action=action): dialog.response(Gtk.ResponseType.CLOSE); GLib.idle_add(lambda: (action(), False)[1])
            widget = button(text, activate)
            widget.get_child().set_line_wrap(True)
            listing.pack_start(widget, False, False, 0)
        dialog.get_content_area().pack_start(scroll(listing), True, True, 0)
        dialog.show_all(); dialog.run(); dialog.destroy()

    def form(self, title, fields, submit="Save", note=None, modes=None):
        dialog = Gtk.Dialog(title=title, transient_for=self, modal=True)
        dialog.set_default_size(500, -1)
        dialog.add_button("Cancel", Gtk.ResponseType.CANCEL)
        dialog.add_button(submit, Gtk.ResponseType.OK)
        dialog.set_default_response(Gtk.ResponseType.OK)
        box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=12)
        box.set_border_width(20)
        widgets = {}
        if modes:
            combo = Gtk.ComboBoxText()
            for key, text in modes: combo.append(key, text)
            combo.set_active(0)
            box.pack_start(combo, False, False, 0); widgets["type"] = combo
        for key, text, value, secret in fields:
            box.pack_start(label(text), False, False, 0)
            entry = Gtk.Entry(text=value, visibility=not secret, activates_default=True)
            box.pack_start(entry, False, False, 0); widgets[key] = entry
        if note: box.pack_start(label(note, "muted"), False, False, 0)
        dialog.get_content_area().add(box)
        dialog.show_all()
        response = dialog.run()
        values = {k: w.get_active_id() if isinstance(w, Gtk.ComboBoxText) else w.get_text() for k, w in widgets.items()}
        dialog.destroy()
        return values if response == Gtk.ResponseType.OK else None

    def add_playlist(self):
        values = self.form("Add playlist", [("name", "Playlist name", "", False), ("address", "M3U URL or Xtream server", "", True), ("username", "Xtream username (provider mode only)", "", False), ("password", "Xtream password (provider mode only)", "", True)], "Import", "Credentials and stream addresses are encrypted locally. Astra supplies no channels.", [("M3U", "M3U URL"), ("XTREAM", "Xtream provider"), ("FILE", "Local M3U file")])
        if values is None: return
        mode = values["type"]
        config = {"type": mode}
        if mode == "FILE":
            chooser = Gtk.FileChooserDialog(title="Open M3U playlist", transient_for=self, action=Gtk.FileChooserAction.OPEN)
            chooser.add_buttons("Cancel", Gtk.ResponseType.CANCEL, "Open", Gtk.ResponseType.OK)
            response = chooser.run(); path = chooser.get_filename(); chooser.destroy()
            if response != Gtk.ResponseType.OK: return
            config["path"] = path
        elif mode == "XTREAM": config.update(server=values["address"], username=values["username"], password=values["password"])
        else: config["url"] = values["address"]
        def done(result):
            count, warnings = result
            self.set_status("Imported %s items. %s" % (count, " ".join(warnings)))
            self.render()
        self.task(lambda: self.library.import_source(values["name"], config, progress=self.progress), done)

    def playlists(self):
        self.content.pack_start(button("Add playlist", self.add_playlist, True), False, False, 0)
        listing = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=24)
        for source in self.library.sources():
            box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=8)
            box.pack_start(label(source["name"]), False, False, 0)
            box.pack_start(label(source["type"] + " · Updated " + datetime.fromtimestamp(source["updated"]).strftime("%d %b %H:%M"), "muted"), False, False, 0)
            actions = Gtk.Box(spacing=8)
            actions.pack_start(button("Refresh", lambda source=source: self.task(lambda: self.library.refresh(source["id"], self.progress))), False, False, 0)
            actions.pack_start(button("Rename", lambda source=source: self.rename(source)), False, False, 0)
            actions.pack_start(button("XMLTV guide", lambda source=source: self.guide(source)), False, False, 0)
            actions.pack_start(button("Remove", lambda source=source: self.remove_source(source)), False, False, 0)
            box.pack_start(actions, False, False, 0)
            listing.pack_start(box, False, False, 0)
        self.content.pack_start(scroll(listing), True, True, 0)

    def rename(self, source):
        values = self.form("Rename playlist", [("name", "Name", source["name"], False)])
        if values and values["name"].strip():
            with self.library.db() as db: db.execute("UPDATE sources SET name=? WHERE id=?", (values["name"].strip(), source["id"]))
            self.render()

    def remove_source(self, source):
        if self.confirm("Remove " + source["name"] + "?", "Its channels, guide and viewing history will be removed."):
            self.task(lambda: self.library.delete_source(source["id"]))

    def guide(self, source):
        values = self.form("Import XMLTV guide", [("url", "XMLTV URL (blank uses provider guide)", "", True)], "Import")
        if values is not None:
            self.task(lambda: self.library.import_guide(source["id"], values["url"]), lambda count: self.set_status("Imported %s guide programs." % count))

    def refresh_guides(self):
        if self.closed or self.busy: return False
        def run():
            count = 0
            for source in self.library.sources():
                try: count += self.library.import_guide(source["id"])
                except Exception: pass
            return count
        self.task(run, lambda count: self.set_status("Guide refreshed." if count else "No guide refresh available. Configure XMLTV in Playlists."))
        return False

    def settings(self):
        box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=16)
        for key, title, default in [("allow_http", "Allow HTTP sources", False), ("guide_refresh", "Refresh guides at launch when older than 12 hours", True)]:
            check = Gtk.CheckButton(label=title)
            check.set_active(self.library.setting(key, default))
            check.connect("toggled", lambda widget, key=key: self.library.set_setting(key, widget.get_active()))
            box.pack_start(check, False, False, 0)
        box.pack_start(label("HTTP exposes provider traffic to the network. Enable it only when required by your source.", "muted"), False, False, 0)
        box.pack_start(button("Set / change management PIN", self.owner_pin), False, False, 0)
        if self.library.setting("owner_pin", ""):
            box.pack_start(button("Remove management PIN", lambda: (self.library.set_setting("owner_pin", ""), self.render())), False, False, 0)
        box.pack_start(label("Playback uses one mpv window with hardware decoding when supported. Space pauses, arrows seek, F toggles full screen, J cycles subtitles, # cycles audio, and Shift+A cycles aspect ratios. mpv also provides on-screen playback controls.", "muted"), False, False, 0)
        box.pack_start(label("Live stream format", "preview-title"), False, False, 0)
        formats = Gtk.ComboBoxText()
        for key, title in [("auto", "Automatic (MPEG-TS, then HLS)"), ("hls", "HLS first"), ("ts", "MPEG-TS only")]: formats.append(key, title)
        formats.set_active_id(self.library.setting("live_format", "auto"))
        formats.connect("changed", lambda widget: self.library.set_setting("live_format", widget.get_active_id()))
        box.pack_start(formats, False, False, 0)
        self.content.pack_start(scroll(box), True, True, 0)

    def owner_pin(self):
        values = self.form("Set management PIN", [("pin", "PIN (4–8 digits)", "", True)])
        if values:
            self.task(lambda: self.library.set_setting("owner_pin", pin_hash(values["pin"])), lambda _: (self.set_status("Management PIN saved."), self.render()))

    def profiles(self):
        self.content.pack_start(button("Add profile", lambda: self.edit_profile()), False, False, 0)
        listing = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=12)
        for profile in self.library.profiles():
            row = Gtk.Box(spacing=8)
            def switch(profile=profile):
                def apply():
                    self.player.command("stop"); self.player.save(); self.last_play = None
                    self.library.set_setting("profile", profile["id"]); self.management = False
                    self.nav.select_row(self.nav.get_row_at_index(0))
                if profile["pin"]: self.unlock(profile["pin"], apply)
                else: apply()
            row.pack_start(button(profile["name"] + (" · Active" if profile["id"] == self.library.profile()["id"] else ""), switch), True, True, 0)
            row.pack_start(button("Edit", lambda profile=profile: self.edit_profile(profile)), False, False, 0)
            if profile["id"] != "home": row.pack_start(button("Delete", lambda profile=profile: self.remove_profile(profile)), False, False, 0)
            listing.pack_start(row, False, False, 0)
        self.content.pack_start(scroll(listing), True, True, 0)

    def edit_profile(self, profile=None):
        values = self.form("Edit profile" if profile else "New profile", [("name", "Name", profile["name"] if profile else "", False), ("pin", "New entry PIN (blank preserves existing)", "", True), ("restricted", "Hidden categories (separated by semicolons)", ";".join(json.loads(profile["restricted"])) if profile else "", False)], note="Category names must match your playlist exactly. Set a management PIN before adding restrictions.")
        if values:
            restricted = [s.strip() for s in values["restricted"].split(";") if s.strip()]
            self.task(lambda: self.library.save_profile(values["name"], values["pin"], restricted, profile["id"] if profile else None))

    def remove_profile(self, profile):
        if self.confirm("Delete " + profile["name"] + "?", "This removes its favorites and history."):
            self.task(lambda: self.library.delete_profile(profile["id"]))

    def confirm(self, title, text):
        dialog = Gtk.MessageDialog(transient_for=self, modal=True, message_type=Gtk.MessageType.QUESTION, buttons=Gtk.ButtonsType.OK_CANCEL, text=title)
        dialog.format_secondary_text(text)
        result = dialog.run(); dialog.destroy()
        return result == Gtk.ResponseType.OK

    def about(self):
        self.content.pack_start(label("Astra " + VERSION), False, False, 0)
        self.content.pack_start(label("A native Linux player for your authorized IPTV sources. No channels, subscriptions or media are included.", "muted"), False, False, 0)
        self.content.pack_start(label("This is the first Fedora desktop preview. Recording and DRM playback are not included. Desktop and Android libraries are stored separately.", "muted"), False, False, 0)
        link = Gtk.LinkButton(uri="https://github.com/imunknownhaha/astra-tv/releases", label="Downloads and updates on GitHub")
        self.content.pack_start(link, False, False, 0)

    def closing(self, *_):
        if self.live_view: self.live_view.close()
        self.closed = True
        self.query_generation += 1
        self.player.close()
        self.jobs.shutdown(wait=False, cancel_futures=True)
        self.queries.shutdown(wait=False, cancel_futures=True)
        return False


class Application(Gtk.Application):
    def __init__(self):
        super().__init__(application_id="io.github.imunknownhaha.Astra")
        self.window = None

    def do_activate(self):
        if not self.window:
            Gtk.Settings.get_default().set_property("gtk-application-prefer-dark-theme", True)
            css = Gtk.CssProvider(); css.load_from_data(CSS)
            Gtk.StyleContext.add_provider_for_screen(Gdk.Screen.get_default(), css, Gtk.STYLE_PROVIDER_PRIORITY_APPLICATION)
            self.window = Window(self)
        self.window.present()


def main():
    os.umask(0o077)
    return Application().run(sys.argv)


if __name__ == "__main__":
    raise SystemExit(main())
