# Linux Live TV verification — 0.1.4

Twelve desktop unit tests passed, including a 20,000-channel indexed library,
Xtream/M3U/guide import regressions, cold GTK/GDK imports and hover cancellation.

The native integration check (`desktop/tests/live_ui_smoke.py`) ran against the
exact extracted RPM payload with GTK 3, Xvfb, Mesa OpenGL and libmpv. It generated
its own test video and 200 fixture channels. Thirty-one rendered frames and 256
red-channel values confirmed decoded video reached the embedded framebuffer.
The check asserts only the last rapidly hovered channel opens, current/next guide
details match, previews add no history, the narrow layout actually stacks by
widget coordinates, category filters work, disabling/navigation releases the
player, and fullscreen handoff creates one history entry. Wide and narrow
screenshots were inspected. RPM payload sources match the tested source tree;
RPM digests pass and dependencies include mpv-libs and libglvnd-egl.

This does not measure provider buffering, physical device performance, or every
GPU/Wayland configuration. No provider credentials or media are included.
