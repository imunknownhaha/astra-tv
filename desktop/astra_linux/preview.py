"""A single muted libmpv preview rendered directly into a GTK OpenGL surface.

The render callback only signals an Event; GTK owns every GL call. Commands are
asynchronous, so the render thread never waits for the mpv playback core.
"""
import ctypes as C
import ctypes.util
import locale
import threading
import time

import gi
gi.require_version("Gtk", "3.0")
gi.require_version("Gdk", "3.0")
from gi.repository import Gtk, GLib
from .core import address, UserError, VERSION


class Param(C.Structure):
    _fields_ = [("kind", C.c_int), ("data", C.c_void_p)]


class Fbo(C.Structure):
    _fields_ = [("fbo", C.c_int), ("width", C.c_int), ("height", C.c_int), ("format", C.c_int)]


GET_PROC = C.CFUNCTYPE(C.c_void_p, C.c_void_p, C.c_char_p)
UPDATE = C.CFUNCTYPE(None, C.c_void_p)


class GlInit(C.Structure):
    _fields_ = [("get_proc", GET_PROC), ("context", C.c_void_p)]


class Event(C.Structure):
    _fields_ = [("event_id", C.c_int), ("error", C.c_int), ("request", C.c_uint64), ("data", C.c_void_p)]


def binding(lib, name, result, *args):
    fn = getattr(lib, name)
    fn.restype, fn.argtypes = result, list(args)
    return fn


def create_handle(lib):
    # GTK adopts the desktop locale. libmpv requires exactly C for numeric parsing,
    # even en_US (which also uses a decimal point) makes mpv_create return NULL.
    # Keep this category pinned for the lifetime of all libmpv worker threads.
    locale.setlocale(locale.LC_NUMERIC, "C")
    return lib.mpv_create()


class Preview(Gtk.GLArea):
    def __init__(self, status=lambda text: None):
        super().__init__()
        self.status = status
        self.set_required_version(3, 2)
        self.set_has_depth_buffer(False)
        self.set_auto_render(False)
        self.set_hexpand(True); self.set_vexpand(False)
        self.connect("render", self._render)
        self.connect("unrealize", self.release)
        self.lib = self.handle = self.context = None
        self.active_id = ""
        self.timer = 0
        self.ready_frame = threading.Event()
        self.frames = 0
        self.loaded = False
        self.started = 0
        self.update_cb = UPDATE(lambda _: self.ready_frame.set())

    def _ensure(self):
        if self.context: return
        self.make_current()
        if self.get_error(): raise UserError("Preview needs OpenGL support. You can still open full playback.")
        try:
            self.lib = C.CDLL(ctypes.util.find_library("mpv") or "libmpv.so.2")
            self.egl = C.CDLL(ctypes.util.find_library("EGL") or "libEGL.so.1")
        except OSError:
            raise UserError("Install the latest Astra RPM with dnf to enable channel previews.") from None
        lib = self.lib
        binding(lib, "mpv_create", C.c_void_p)
        binding(lib, "mpv_initialize", C.c_int, C.c_void_p)
        binding(lib, "mpv_set_option_string", C.c_int, C.c_void_p, C.c_char_p, C.c_char_p)
        binding(lib, "mpv_command_async", C.c_int, C.c_void_p, C.c_uint64, C.POINTER(C.c_char_p))
        binding(lib, "mpv_wait_event", C.POINTER(Event), C.c_void_p, C.c_double)
        binding(lib, "mpv_render_context_create", C.c_int, C.POINTER(C.c_void_p), C.c_void_p, C.POINTER(Param))
        binding(lib, "mpv_render_context_set_update_callback", None, C.c_void_p, UPDATE, C.c_void_p)
        binding(lib, "mpv_render_context_update", C.c_uint64, C.c_void_p)
        binding(lib, "mpv_render_context_render", C.c_int, C.c_void_p, C.POINTER(Param))
        binding(lib, "mpv_render_context_report_swap", None, C.c_void_p)
        binding(lib, "mpv_render_context_free", None, C.c_void_p)
        binding(lib, "mpv_terminate_destroy", None, C.c_void_p)
        binding(self.egl, "eglGetProcAddress", C.c_void_p, C.c_char_p)
        self.get_proc = GET_PROC(lambda _, name: self.egl.eglGetProcAddress(name))
        self.gl_get = C.CFUNCTYPE(None, C.c_uint, C.POINTER(C.c_int))(self.egl.eglGetProcAddress(b"glGetIntegerv"))
        self.gl_clear_color = C.CFUNCTYPE(None, C.c_float, C.c_float, C.c_float, C.c_float)(self.egl.eglGetProcAddress(b"glClearColor"))
        self.gl_clear = C.CFUNCTYPE(None, C.c_uint)(self.egl.eglGetProcAddress(b"glClear"))
        self.handle = create_handle(lib)
        if not self.handle: raise UserError("Could not create the preview player.")
        options = {"vo":"libmpv", "config":"no", "load-scripts":"no", "ytdl":"no", "load-unsafe-playlists":"no",
                   "audio":"no", "sub":"no", "osc":"no", "osd-level":"0", "terminal":"no", "msg-level":"all=no",
                   "idle":"yes", "hwdec":"auto-safe", "vd-lavc-threads":"2", "vf":"fps=15", "cache-secs":"3",
                   "dither-depth":"no", "deband":"no", "interpolation":"no", "scale":"bilinear", "cscale":"bilinear", "dscale":"bilinear",
                   "demuxer-max-bytes":"12MiB", "demuxer-max-back-bytes":"0", "network-timeout":"8"}
        try:
            for key, value in options.items():
                if lib.mpv_set_option_string(self.handle, key.encode(), value.encode()) < 0:
                    raise UserError("The system mpv library cannot initialize previews. Update mpv and retry.")
            if lib.mpv_initialize(self.handle) < 0: raise UserError("Could not initialize the preview player.")
            init = GlInit(self.get_proc, None)
            api = C.create_string_buffer(b"opengl")
            advanced = C.c_int(1)
            params = (Param * 4)(Param(1, C.cast(api, C.c_void_p)), Param(2, C.cast(C.pointer(init), C.c_void_p)),
                                 Param(10, C.cast(C.pointer(advanced), C.c_void_p)), Param())
            context = C.c_void_p()
            if lib.mpv_render_context_create(C.byref(context), self.handle, params) < 0:
                raise UserError("OpenGL preview is unavailable. Full playback is still available.")
            self.context = context
            lib.mpv_render_context_set_update_callback(context, self.update_cb, None)
        except Exception:
            lib.mpv_terminate_destroy(self.handle); self.handle = None
            raise

    def _command(self, *args):
        if not self.handle: return
        if any("\x00" in str(value) for value in args): raise UserError("The source contains an invalid address or header.")
        values = (C.c_char_p * (len(args) + 1))(*(str(value).encode() for value in args), None)
        if self.lib.mpv_command_async(self.handle, 0, values) < 0:
            raise UserError("Could not open this channel preview. Try full playback.")

    def play(self, item_id, payload, allow_http=False):
        if item_id == self.active_id: return
        url = address(payload.get("url", ""), allow_http)
        self._ensure()
        self._command("set", "user-agent", payload.get("agent") or "Astra/" + VERSION)
        self._command("set", "referrer", payload.get("referrer") or "")
        self.active_id = item_id
        self.loaded = False
        self.started = time.monotonic()
        self._command("loadfile", url, "replace")
        if not self.timer: self.timer = GLib.timeout_add(50, self._tick)
        self.status("Connecting · muted preview")
        self.queue_render()

    def _tick(self):
        if not self.context or not self.active_id: self.timer = 0; return False
        for _ in range(32):
            event = self.lib.mpv_wait_event(self.handle, 0).contents
            if not event.event_id: break
            if event.event_id == 8:
                self.loaded = True; self.status("Live preview · muted")
            elif event.event_id == 7 and event.data:
                reason = C.cast(event.data, C.POINTER(C.c_int))[0]
                if reason == 4:
                    self.status("Preview unavailable. Try opening full playback.")
                    self.release(); return False
        if time.monotonic() - self.started > 15 and not self.loaded:
            self.status("Preview timed out. Try opening full playback.")
            self.release(); return False
        if self.ready_frame.is_set():
            self.ready_frame.clear(); self.queue_render()
        return True

    def _render(self, *_):
        if not self.context:
            if hasattr(self, "gl_clear_color"):
                self.gl_clear_color(0, 0, 0, 1); self.gl_clear(0x4000)
            return True
        if not self.active_id or not self.loaded:
            self.gl_clear_color(0, 0, 0, 1); self.gl_clear(0x4000)
            # Acknowledge queued render work even during a channel switch.
        fbo = C.c_int()
        self.gl_get(0x8CA6, C.byref(fbo))
        scale = self.get_scale_factor()
        target = Fbo(fbo.value, max(1, self.get_allocated_width() * scale), max(1, self.get_allocated_height() * scale), 0)
        flip, block = C.c_int(1), C.c_int(0)
        params = (Param * 4)(Param(3, C.cast(C.pointer(target), C.c_void_p)),
                            Param(4, C.cast(C.pointer(flip), C.c_void_p)), Param(12, C.cast(C.pointer(block), C.c_void_p)), Param())
        flags = self.lib.mpv_render_context_update(self.context)
        if self.active_id and (flags & 1 or self.loaded):
            result = self.lib.mpv_render_context_render(self.context, params)
            if result < 0:
                self.status("Preview rendering is unavailable. Try full playback.")
                return True
            self.lib.mpv_render_context_report_swap(self.context)
            self.frames += 1
        return True

    def stop(self):
        self.active_id = ""
        self.loaded = False
        if self.timer: GLib.source_remove(self.timer); self.timer = 0
        if self.handle: self._command("stop")
        self.queue_render()

    def release(self, *_):
        self.stop()
        if self.context:
            self.make_current()
            self.lib.mpv_render_context_free(self.context); self.context = None
        if self.handle:
            self.lib.mpv_terminate_destroy(self.handle); self.handle = None
