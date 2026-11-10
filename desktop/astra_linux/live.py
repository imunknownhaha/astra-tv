"""Traditional category/channel/preview Live TV layout, with one delayed preview."""
from datetime import datetime
import json
import time
import gi
gi.require_version("Gtk", "3.0")
gi.require_version("Gdk", "3.0")
from gi.repository import Gtk, Gdk, GLib, Pango
from .core import UserError
from .preview import Preview
from .streams import candidates


class HoverRequest:
    """Coalesce pointer motion; only the final channel under the pointer opens."""
    def __init__(self, schedule, cancel, open_channel):
        self.schedule, self.cancel_timer, self.open_channel = schedule, cancel, open_channel
        self.timer = 0
        self.pending = ""

    def request(self, item):
        if self.pending == item["id"]: return
        self.cancel()
        self.pending = item["id"]
        def fire():
            self.timer = 0
            self.open_channel(item)
            return False
        self.timer = self.schedule(500, fire)

    def cancel(self):
        if self.timer: self.cancel_timer(self.timer)
        self.timer = 0; self.pending = ""


def text(value, style=None):
    widget = Gtk.Label(label=value, xalign=0)
    widget.set_ellipsize(Pango.EllipsizeMode.END)
    if style: widget.get_style_context().add_class(style)
    return widget


def scrolled(child):
    widget = Gtk.ScrolledWindow()
    widget.set_policy(Gtk.PolicyType.AUTOMATIC, Gtk.PolicyType.AUTOMATIC)
    widget.add(child)
    return widget


class LiveTv(Gtk.Box):
    def __init__(self, window):
        super().__init__(orientation=Gtk.Orientation.VERTICAL, spacing=12)
        self.window, self.library = window, window.library
        self.closed = False
        self.generation = 0
        self.limit = 200
        self.category_name = ""
        self.selected_item = None
        self.layout = None
        self.hover = HoverRequest(GLib.timeout_add, GLib.source_remove, self.preview_channel)
        self.layout_pending = False
        self.connect("size-allocate", self.adapt)
        toolbar = Gtk.Box(spacing=8)
        self.pack_start(toolbar, False, False, 0)
        self.search = Gtk.SearchEntry(placeholder_text="Search live channels")
        self.search.connect("search-changed", lambda _: self.reload())
        toolbar.pack_start(self.search, True, True, 0)
        groups = self.library.groups("LIVE")
        self.category_combo = Gtk.ComboBoxText()
        self.category_combo.append("", "All categories")
        for group in groups: self.category_combo.append(group, group)
        self.category_combo.set_active(0)
        self.category_combo.show_all()
        self.category_combo.set_no_show_all(True)
        self.category_combo.connect("changed", lambda combo: self.choose_category(combo.get_active_id() or ""))
        toolbar.pack_start(self.category_combo, False, False, 0)
        self.enabled = Gtk.CheckButton(label="Hover previews")
        self.enabled.set_active(self.library.setting("hover_previews", True))
        self.enabled.connect("toggled", self.toggle_previews)
        toolbar.pack_start(self.enabled, False, False, 0)
        sources = self.library.sources()
        if len(sources) > 1:
            chooser = Gtk.ComboBoxText(); chooser.append("", "All playlists")
            for source in sources: chooser.append(source["id"], source["name"])
            chooser.set_active_id(window.source_filter)
            chooser.connect("changed", self.choose_source)
            self.pack_start(chooser, False, False, 0)
        self.columns = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=12)
        self.pack_start(self.columns, True, True, 0)
        self.groups = Gtk.ListBox(selection_mode=Gtk.SelectionMode.SINGLE)
        for group in [""] + groups:
            row = Gtk.ListBoxRow(); row.category_name = group
            title = text(group or "All categories"); title.set_max_width_chars(18)
            title.set_margin_start(12); title.set_margin_end(12)
            title.set_margin_top(12); title.set_margin_bottom(12)
            row.add(title); self.groups.add(row)
        self.groups.show_all()
        self.groups.select_row(self.groups.get_row_at_index(0))
        self.groups.connect("row-selected", lambda _, row: self.choose_category(row.category_name) if row else None)
        self.group_scroll = scrolled(self.groups)
        self.group_scroll.set_size_request(142, -1)
        self.group_scroll.show_all()
        self.group_scroll.set_no_show_all(True)
        self.columns.pack_start(self.group_scroll, False, False, 0)
        self.main = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=12)
        self.columns.pack_start(self.main, True, True, 0)
        channel_box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=8)
        channel_box.set_size_request(220, -1)
        self.main.pack_start(channel_box, True, True, 0)
        self.model = Gtk.ListStore(bool, str, str, object)
        self.tree = Gtk.TreeView(model=self.model)
        self.tree.set_activate_on_single_click(True)
        self.tree.set_headers_visible(True)
        self.tree.add_events(Gdk.EventMask.POINTER_MOTION_MASK | Gdk.EventMask.LEAVE_NOTIFY_MASK)
        self.tree.connect("motion-notify-event", self.motion)
        self.tree.connect("leave-notify-event", lambda *_: self.cancel_pending())
        self.tree.get_selection().connect("changed", self.selection_changed)
        toggle = Gtk.CellRendererToggle()
        toggle.connect("toggled", self.favorite)
        self.saved_column = Gtk.TreeViewColumn("Saved", toggle, active=0)
        self.tree.append_column(self.saved_column)
        cell = Gtk.CellRendererText(ellipsize=Pango.EllipsizeMode.END, ypad=10)
        channel_column = Gtk.TreeViewColumn("Channel", cell, markup=1)
        channel_column.set_expand(True); channel_column.set_min_width(140)
        self.tree.append_column(channel_column)
        self.tree.connect("row-activated", lambda _, path, column: self.watch(self.model[path][3]) if column != self.saved_column else None)
        channel_box.pack_start(scrolled(self.tree), True, True, 0)
        footer = Gtk.Box(spacing=8)
        self.count = text("Loading channels…", "muted")
        footer.pack_start(self.count, True, True, 0)
        more = Gtk.Button(label="Load more"); more.connect("clicked", self.more)
        footer.pack_end(more, False, False, 0)
        channel_box.pack_end(footer, False, False, 0)
        details = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=12)
        details.set_size_request(260, -1); details.set_margin_start(12)
        self.main.pack_start(details, True, True, 0)
        self.preview_status = text("Hover over a channel to see what’s playing.", "muted")
        self.preview_status.set_line_wrap(True)
        self.preview_status.set_lines(2)
        self.preview = Preview(self.status)
        self.preview.set_size_request(240, 135)
        self.preview.connect("size-allocate", self.video_size)
        details.pack_start(self.preview, False, True, 0)
        self.channel_title = text("Channel preview", "preview-title")
        self.channel_title.set_line_wrap(True); self.channel_title.set_lines(2)
        details.pack_start(self.channel_title, False, False, 0)
        details.pack_start(self.preview_status, False, False, 0)
        self.watch_button = Gtk.Button(label="Watch full screen")
        self.watch_button.get_style_context().add_class("suggested-action")
        self.watch_button.set_sensitive(False)
        self.watch_button.connect("clicked", lambda _: self.watch(self.selected_item))
        details.pack_start(self.watch_button, False, False, 0)
        self.now_time = text("On now", "muted")
        self.now_title = text("No programme information")
        self.now_title.set_line_wrap(True); self.now_title.set_lines(2)
        self.description = text("Import an XMLTV guide in Playlists to see programme details.", "muted")
        self.description.set_line_wrap(True); self.description.set_lines(3)
        self.next_time = text("Up next", "muted")
        self.next_title = text("No upcoming programme supplied")
        self.next_title.set_line_wrap(True); self.next_title.set_lines(2)
        info = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=8)
        for widget in [self.now_time, self.now_title, self.description, self.next_time, self.next_title]: info.pack_start(widget, False, False, 0)
        details.pack_start(scrolled(info), True, True, 0)
        retry = Gtk.Button(label="Retry preview"); retry.connect("clicked", lambda _: self.retry())
        details.pack_end(retry, False, False, 0)
        self.reload()

    def status(self, message):
        if not self.closed: self.preview_status.set_text(message)

    def adapt(self, *_):
        # GTK ignores resize requests made during allocation; apply on the next idle.
        if not self.layout_pending:
            self.layout_pending = True
            GLib.idle_add(self.apply_layout)

    def apply_layout(self):
        self.layout_pending = False
        if self.closed: return False
        width = self.window.get_allocated_width()
        mode = "stack" if width < 1050 else "wide"
        categories = width >= 1050
        self.group_scroll.set_visible(categories)
        self.category_combo.set_visible(not categories)
        if self.layout != mode:
            self.layout = mode
            self.main.set_orientation(Gtk.Orientation.VERTICAL if mode == "stack" else Gtk.Orientation.HORIZONTAL)
            self.main.queue_resize()
        return False

    def video_size(self, widget, allocation):
        height = min(180 if self.layout == "stack" else 300, max(135, round(allocation.width * 9 / 16)))
        if widget.get_size_request()[1] != height: widget.set_size_request(240, height)

    def choose_category(self, category):
        if category == self.category_name: return
        self.category_name = category; self.limit = 200
        self.category_combo.set_active_id(category)
        for row in self.groups.get_children():
            if row.category_name == category: self.groups.select_row(row); break
        self.pause()
        self.reload()

    def choose_source(self, combo):
        self.window.source_filter = combo.get_active_id() or ""
        self.limit = 200; self.pause(); self.reload()

    def reload(self):
        self.generation += 1
        generation = self.generation
        query, category, limit, source = self.search.get_text(), self.category_name, self.limit, self.window.source_filter
        self.hover.cancel()
        self.pause()
        self.selected_item = None
        self.watch_button.set_sensitive(False)
        self.channel_title.set_text("Channel preview")
        def read():
            if self.closed or generation != self.generation: return None
            rows = self.library.browse("Live TV", query, category, limit, source)
            return rows, self.library.live_now(rows)
        def complete(future):
            def apply():
                if self.closed or generation != self.generation: return False
                try:
                    result = future.result()
                    if result is None: return False
                    rows, now = result
                    self.model.clear()
                    for item in rows:
                        title = GLib.markup_escape_text(item["name"].replace("\n", " ")[:512])
                        subtitle = GLib.markup_escape_text(now.get((item["source"], item["guide"]), item["category"]).replace("\n", " ")[:512])
                        self.model.append([bool(item["favorite"]), '<b>' + title + '</b>\n<span foreground="#9caab7" size="small">' + subtitle + '</span>', item["name"], item])
                    self.count.set_text("%s channels" % len(rows) if rows else "No matching channels")
                except Exception: self.status("Could not load channels. Check your library and try again.")
                return False
            GLib.idle_add(apply)
        self.window.queries.submit(read).add_done_callback(complete)

    def more(self, *_): self.limit = min(20000, self.limit + 200); self.reload()

    def motion(self, tree, event):
        result = tree.get_path_at_pos(int(event.x), int(event.y))
        if result:
            path, column, *_ = result
            if column != self.saved_column:
                tree.get_selection().select_path(path)
                self.queue(self.model[path][3])
        return False

    def selection_changed(self, selection):
        model, row = selection.get_selected()
        if row: self.queue(model[row][3])

    def queue(self, item):
        if self.closed: return
        if item["id"] != (self.selected_item or {}).get("id"):
            self.preview.release()
            self.selected_item = item
            self.channel_title.set_text(item["name"])
            self.watch_button.set_sensitive(True)
            self.now_time.set_text("On now"); self.now_title.set_text("Loading programme information…")
            self.next_time.set_text("Up next"); self.next_title.set_text(""); self.description.set_text("")
        self.hover.request(item)

    def preview_channel(self, item):
        if self.closed or not self.selected_item or self.selected_item["id"] != item["id"]: return
        self.load_programs(item)
        if not self.enabled.get_active(): self.status("Hover previews off · click to watch"); return
        if self.window.player.process and self.window.player.process.poll() is None:
            self.status("Full playback is open. Close its window to resume previews."); return
        if item["category"] in json.loads(self.library.profile()["restricted"]): return
        try:
            payload = self.library.open(item["secret"])
            self.preview.play(item["id"], payload, self.library.setting("allow_http", False), candidates(item, payload, preference=self.library.setting("live_format", "auto")))
        except UserError as exc: self.status(str(exc))
        except Exception: self.status("Preview unavailable. Try opening full playback.")

    def load_programs(self, item):
        def done(future):
            def apply():
                if self.closed or not self.selected_item or self.selected_item["id"] != item["id"]: return False
                try:
                    programmes = future.result(); now = int(time.time())
                    current = next((p for p in programmes if p["start"] <= now < p["stop"]), None)
                    following = next((p for p in programmes if p["start"] > now), None)
                    clock = lambda p: datetime.fromtimestamp(p["start"]).strftime("%H:%M") + " – " + datetime.fromtimestamp(p["stop"]).strftime("%H:%M")
                    self.now_time.set_text("On now" + (" · " + clock(current) if current else ""))
                    self.now_title.set_text(current["title"] if current else "No programme information")
                    self.description.set_text(current["description"] if current else "Import an XMLTV guide in Playlists to see programme details.")
                    self.next_time.set_text("Up next" + (" · " + clock(following) if following else ""))
                    self.next_title.set_text(following["title"] if following else "No upcoming programme supplied")
                except Exception:
                    self.now_title.set_text("Guide information unavailable")
                return False
            GLib.idle_add(apply)
        self.window.queries.submit(lambda: self.library.programs(item)).add_done_callback(done)

    def favorite(self, _, path):
        row = self.model[path]; self.library.favorite(row[3]); row[0] = not row[0]

    def watch(self, item):
        if not item: return
        self.pause()
        self.window.play(item)
        self.window.player.command("set_property", "fullscreen", True)

    def retry(self):
        if self.selected_item:
            self.hover.cancel(); self.preview.release()
            self.preview_channel(self.selected_item)

    def cancel_pending(self): self.hover.cancel(); return False

    def pause(self):
        self.hover.cancel()
        self.preview.release()
        self.status("Hover over a channel to preview · muted")

    def toggle_previews(self, widget):
        self.library.set_setting("hover_previews", widget.get_active())
        if not widget.get_active(): self.pause(); self.status("Hover previews off · click to watch")

    def close(self):
        if self.closed: return
        self.closed = True; self.generation += 1
        self.hover.cancel(); self.preview.release()
