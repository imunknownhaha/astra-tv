#!/usr/bin/env bash
set -euo pipefail
# Synthetic video/audio only. No third-party channel or subscription is used.
fixture_dir="${1:-fixtures/generated}"
mkdir -p "$fixture_dir"
ffmpeg -hide_banner -loglevel warning -y -f lavfi -i 'testsrc2=size=640x360:rate=25' \
  -f lavfi -i 'sine=frequency=440:sample_rate=48000' -t 30 \
  -c:v libx264 -preset ultrafast -pix_fmt yuv420p -g 50 -c:a aac -b:a 96k \
  -f hls -hls_time 2 -hls_list_size 0 -hls_playlist_type vod "$fixture_dir/pattern.m3u8"
python3 - "$fixture_dir" <<'PY'
import datetime as dt
import pathlib,sys
p=pathlib.Path(sys.argv[1])
p.joinpath('playlist.m3u').write_text('''#EXTM3U x-tvg-url="http://10.0.2.2:8765/guide.xml"
#EXTINF:-1 tvg-id="synthetic.pattern" group-title="Synthetic tests",Test pattern
http://10.0.2.2:8765/pattern.m3u8
#EXTINF:-1 media-type="movie" group-title="Synthetic tests",30 second test pattern
http://10.0.2.2:8765/pattern.m3u8
''')
start=dt.datetime.now(dt.timezone.utc).replace(minute=0,second=0,microsecond=0)
fmt=lambda v:v.strftime('%Y%m%d%H%M%S %z')
p.joinpath('guide.xml').write_text('<?xml version="1.0"?><tv><channel id="synthetic.pattern"><display-name>Test pattern</display-name></channel>'+''.join(f'<programme channel="synthetic.pattern" start="{fmt(start+dt.timedelta(hours=i))}" stop="{fmt(start+dt.timedelta(hours=i+1))}"><title>Synthetic program {i+1}</title><desc>Locally generated metadata for guide testing.</desc></programme>' for i in range(-3,6))+'</tv>')
PY
printf 'Enable HTTP sources in the test app, then import http://10.0.2.2:8765/playlist.m3u\n'
printf 'Serve with: python3 -m http.server 8765 --bind 127.0.0.1 --directory %q\n' "$fixture_dir"
