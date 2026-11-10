"""Bounded Xtream format fallback and credential-free playback errors."""
import re
from urllib.parse import urlsplit, urlunsplit, quote


def candidates(item, payload, url=None, preference='auto'):
    original = url or payload.get('url', '')
    if url or item.get('kind') != 'LIVE' or not payload.get('provider_id'):
        return [original]
    parsed = urlsplit(original)
    # Rewrite only URLs created by our Xtream importer, never arbitrary M3U URLs.
    suffix = '/' + quote(str(payload['provider_id']), safe='')
    if '/live/' not in parsed.path or not parsed.path.endswith((suffix+'.ts', suffix+'.m3u8')):
        return [original]
    base = parsed.path.rsplit('.', 1)[0]
    formats = ['m3u8', 'ts'] if preference == 'hls' else ['ts', 'm3u8']
    if preference == 'ts': formats = ['ts']
    return list(dict.fromkeys(urlunsplit(parsed._replace(path=base+'.'+ext)) for ext in formats))


def http_status(message):
    # Raw mpv messages may include authenticated URLs. Return only an HTTP code.
    match = re.search(r'\bHTTP(?:\s+error|\s+status(?:\s+code)?|\s+Error)?[\s:]+([45]\d\d)\b', str(message), re.I)
    return int(match.group(1)) if match else None


def failure_message(code=None, timeout=False):
    if code: return 'Stream could not open (HTTP %d). Try another stream format in Settings, or retry playback.' % code
    if timeout: return 'No video arrived before the playback timeout. Try HLS in Settings, or retry playback.'
    return 'The stream could not be decoded or opened. Try HLS in Settings, or retry playback.'
