#!/usr/bin/env python3
"""Anonymous, bounded music API diagnostics (Python standard library only).

No account cookies, login, purchases, saved audio files or TLS overrides.
Protocol references: metowolf/Meting providers; Yyyangshenghao/simple-music
QQ documentation; Yuncan050115/ourcraft-music-api; provider-owned API docs.
Responses with audio magic bytes are observations, not playback guarantees.
"""
import argparse
import base64
from datetime import datetime
import html
import ipaddress
import json
import os
from pathlib import Path
import re
import socket
import subprocess
import time
from urllib.error import HTTPError, URLError
from urllib.parse import parse_qs, urlencode, urljoin, urlsplit, urlunsplit
from urllib.request import HTTPRedirectHandler, ProxyHandler, Request, build_opener
import uuid


REFERENCES = {
    "gd": "https://music-api.gdstudio.xyz/api.php",
    "injahow": "https://api.injahow.cn/meting/index.php",
    "i-meto": "https://github.com/metowolf/MetingJS/blob/master/src/Meting.js",
    "ourcraft": "https://github.com/Yuncan050115/ourcraft-music-api",
    "qq": "https://github.com/Yyyangshenghao/simple-music/blob/master/docs/qq-music-api.md",
    "qq-legacy": "https://github.com/metowolf/Meting/blob/1c2f4c98eed749200d9d7ff5cab329c4308f4268/src/providers/tencent.js",
    "kugou": "https://github.com/metowolf/Meting/blob/1c2f4c98eed749200d9d7ff5cab329c4308f4268/src/providers/kugou.js",
    "kuwo": "https://github.com/metowolf/Meting/blob/1c2f4c98eed749200d9d7ff5cab329c4308f4268/src/providers/kuwo.js",
    "vkeys": "https://doc.vkeys.cn/v3/音乐模块/QQ音乐/点歌相关接口/2-link.html",
}
TIMESTAMP = re.compile(r"\[\d{1,3}:\d{2}(?:[.:]\d+)?\]")


def validate_url(url):
    p = urlsplit(url)
    if p.scheme not in ("http", "https") or not p.hostname or p.username or p.password:
        raise ValueError("Invalid HTTP address")
    host = p.hostname.lower()
    if host == "localhost" or host.endswith((".localhost", ".local")):
        raise ValueError("Local addresses are not API targets")
    try:
        address = ipaddress.ip_address(host)
    except ValueError:
        address = None
    if address is not None and not address.is_global:
        raise ValueError("Private addresses are not API targets")
    return p


def url_label(url, *, media=False):
    """Drop credentials/query strings and opaque audio paths from reports."""
    p = urlsplit(url)
    host = p.hostname or "invalid"
    if ":" in host:
        host = "[" + host + "]"
    if p.port:
        host += ":" + str(p.port)
    suffix = Path(p.path).suffix.lower()
    path = "/<audio>" + (suffix if suffix in (".mp3", ".m4a", ".flac", ".ogg", ".wav", ".aac") else "") if media else p.path[:180]
    return urlunsplit((p.scheme, host, path, "", ""))


def parse_payload(body):
    text = body.decode("utf-8-sig", "replace").strip()
    try:
        return json.loads(text)
    except ValueError:
        match = re.fullmatch(r"[\w.]+\(\s*([\s\S]+)\s*\)\s*;?", text)
        if not match:
            raise ValueError("Not JSON or JSONP") from None
        value = json.loads(match.group(1))
        if not isinstance(value, (dict, list)):
            raise ValueError("JSONP must contain an object or array")
        return value


def classify_response(status, data):
    if status == 429:
        return "rate_limited"
    if status in (401, 402):
        return "requires_auth"
    if status in (403, 451):
        return "blocked"
    if isinstance(data, dict):
        message = str(data.get("detail", data.get("message", data.get("msg", data.get("error", "")))))
        if "not supported" in message.lower():
            return "unsupported"
        if data.get("error_code") in (152, 20006) or data.get("error") in ("login_required", "paid_required", "token_required"):
            return "requires_auth"
        if any(word in message.lower() for word in ("login required", "购买", "需要登录", "请登录", "需要会员", "需要付费")):
            return "requires_auth"
        if data.get("ok") is False:
            return "unavailable"
        if data.get("success") is False:
            return "api_error"
        if data.get("code") not in (None, 0, 200, "0", "200") or data.get("error_code") not in (None, 0):
            return "api_error"
        for key, value in data.items():
            if (key == "req_0" or key.startswith("music.")) and isinstance(value, dict):
                if value.get("code") not in (None, 0):
                    return "api_error"
    if status < 200 or status >= 300:
        return "http_error"
    return "ok"


def audio_signature(body):
    if body.startswith(b"ID3"):
        return "mp3"
    if body.startswith(b"fLaC"):
        return "flac"
    if body.startswith(b"OggS"):
        return "ogg"
    if body[:4] in (b"RIFF", b"RF64") and body[8:12] == b"WAVE":
        return "wav"
    if body[4:8] == b"ftyp":
        return "mp4"
    if len(body) >= 2 and body[0] == 255 and body[1] & 224 == 224:
        return "mpeg_or_aac"
    return None


def at(data, path, default=None):
    for key in path.split("."):
        if not isinstance(data, dict) or key not in data:
            return default
        data = data[key]
    return data


def endpoint(base, params):
    return base + ("&" if "?" in base else "?") + urlencode(params)


def system_proxy():
    """Read network configuration only, never browser/account cookies."""
    try:
        import winreg
        with winreg.OpenKey(winreg.HKEY_CURRENT_USER, r"Software\Microsoft\Windows\CurrentVersion\Internet Settings") as key:
            if not winreg.QueryValueEx(key, "ProxyEnable")[0]:
                return ""
            value = winreg.QueryValueEx(key, "ProxyServer")[0]
        if "=" in value:
            options = dict(part.split("=", 1) for part in value.split(";") if "=" in part)
            value = options.get("https", options.get("http", ""))
        return value if not value or "://" in value else "http://" + value
    except (ImportError, OSError):
        return ""


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


class Transport:
    def __init__(self, proxy, timeout, delay):
        self.opener = build_opener(ProxyHandler({"http": proxy, "https": proxy} if proxy else {}), NoRedirect)
        self.timeout = timeout
        self.delay = delay

    def fetch(self, url, *, payload=None, headers=None, limit=262144, form=False):
        validate_url(url)
        if headers and any(key.lower() in ("cookie", "authorization") for key in headers):
            raise ValueError("This probe does not accept account credentials")
        time.sleep(self.delay)
        request_headers = {"User-Agent": "FloatMusicApiProbe/1.0", "Accept-Encoding": "identity", **(headers or {})}
        body = None
        if payload is not None:
            if form:
                body = urlencode(payload).encode("utf-8")
                request_headers["Content-Type"] = "application/x-www-form-urlencoded"
            else:
                body = json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
                request_headers["Content-Type"] = "application/json"
        started = time.monotonic()
        try:
            try:
                response = self.opener.open(Request(url, data=body, headers=request_headers), timeout=self.timeout)
            except HTTPError as error:
                response = error
            with response:
                raw = response.read(limit + 1 if limit > 64 else limit)
                return {
                    "status": response.status, "body": raw[:limit], "too_large": len(raw) > limit,
                    "headers": {key.lower(): value for key, value in response.headers.items()},
                    "ms": round((time.monotonic() - started) * 1000),
                }
        except (OSError, URLError, TimeoutError) as error:
            reason = "timeout" if isinstance(error, (TimeoutError, socket.timeout)) or isinstance(getattr(error, "reason", None), (TimeoutError, socket.timeout)) else "network_error"
            return {"status": None, "body": b"", "headers": {}, "ms": round((time.monotonic() - started) * 1000), "error": reason}


class Probe:
    def __init__(self, args):
        self.args = args
        proxy = system_proxy() if args.proxy == "auto" else "" if args.proxy == "none" else args.proxy
        self.transport = Transport(proxy, args.timeout, args.delay)
        self.proxy_enabled = bool(proxy)
        self.results = []

    def request(self, provider, source, operation, url, *, payload=None, headers=None, text=False, form=False):
        reply = self.transport.fetch(url, payload=payload, headers=headers, form=form)
        record = {"provider": provider, "source": source, "operation": operation, "endpoint": url_label(url), "http": reply["status"], "ms": reply["ms"]}
        data = None
        if reply.get("error"):
            record["outcome"] = reply["error"]
        elif reply["too_large"]:
            record["outcome"] = "response_too_large"
        else:
            try:
                data = parse_payload(reply["body"])
            except ValueError:
                if text and not reply["body"].lstrip().startswith(b"<"):
                    data = reply["body"].decode("utf-8", "replace")
                else:
                    record["outcome"] = "invalid_payload" if reply["status"] == 200 else "http_error"
            if "outcome" not in record:
                record["outcome"] = classify_response(reply["status"], data)
        if isinstance(data, dict):
            for key in ("code", "error_code", "error", "ok"):
                if key in data and isinstance(data[key], (str, int, bool)):
                    record[key] = str(data[key])[:80]
        self.results.append(record)
        print(f"{provider}/{source} {operation}: response HTTP {record['http']} {record['outcome']}", flush=True)
        return data, record

    def audio(self, provider, source, url, label="audio", track_id=None):
        record = {"provider": provider, "source": source, "operation": label, "endpoint": url_label(url, media=True), "chain": [], "outcome": "redirect_limit"}
        if track_id is not None:
            record["track_id"] = str(track_id)
        proxy_parts = urlsplit(url)
        origin = parse_qs(proxy_parts.query).get("url", [""])[0] if proxy_parts.hostname == "music.yuncan.xyz" and proxy_parts.path == "/proxy" else ""
        start = time.monotonic()
        for unused in range(6):
            try:
                validate_url(url)
                reply = self.transport.fetch(url, headers={"Range": "bytes=0-63"}, limit=64)
            except ValueError:
                record["outcome"] = "invalid_address"
                break
            headers = reply["headers"]
            status = reply["status"]
            record["chain"].append({"endpoint": url_label(url, media=True), "http": status, "content_type": headers.get("content-type", "")})
            record["http"] = status
            if reply.get("error"):
                record["outcome"] = reply["error"]
                break
            if status in (301, 302, 303, 307, 308):
                next_url = urljoin(url, headers.get("location", ""))
                if next_url == url:
                    record["outcome"] = "invalid_redirect"
                    break
                try:
                    next_parts = validate_url(next_url)
                except ValueError:
                    record["outcome"] = "invalid_redirect"
                    break
                if urlsplit(url).scheme == "https" and next_parts.scheme == "http":
                    record["https_downgrade"] = True
                url = next_url
                continue
            signature = audio_signature(reply["body"])
            record.update({"read_bytes": len(reply["body"]), "signature": signature, "content_range": headers.get("content-range", "")})
            match = re.fullmatch(r"bytes 0-(\d+)/(\d+|\*)", record["content_range"])
            record["range_compliant"] = bool(status == 206 and match and int(match[1]) <= 63)
            record["outcome"] = "audio_header" if status in (200, 206) and signature else "not_audio" if status in (200, 206) else classify_response(status, None)
            break
        record["ms"] = round((time.monotonic() - start) * 1000)
        self.results.append(record)
        print(f"{provider}/{source} {label}: HTTP {record.get('http')} {record['outcome']}", flush=True)
        if record["outcome"] == "audio_header" and self.args.qt_probe:
            record["qt"] = self.qt_playback(url)
            print(f"{provider}/{source} {label}: Qt {record['qt'].get('outcome')}", flush=True)
        # The URL is kept only in memory for a targeted HTTPS compatibility check.
        if record.get("https_downgrade") and urlsplit(url).hostname == "aqqmusic.tc.qq.com" and label == "audio":
            parts = urlsplit(url)
            self.audio(provider, source, urlunsplit(parts._replace(scheme="https")), "audio_https_cdn", track_id)
        if origin and label == "audio" and self.args.check_origin:
            self.audio(provider, source, origin, "audio_origin", track_id)
        return record

    def qt_playback(self, url):
        environment = dict(os.environ)
        environment["QT_MEDIA_BACKEND"] = "ffmpeg"
        if self.args.qt_bin:
            environment["PATH"] = str(self.args.qt_bin) + os.pathsep + environment.get("PATH", "")
        try:
            process = subprocess.run([str(self.args.qt_probe.resolve())], input=json.dumps({"url": url, "timeout_ms": 10000}), text=True, encoding="utf-8", capture_output=True, timeout=16, env=environment)
            value = json.loads(process.stdout)
            # Only the probe's numeric/status fields are retained, never stderr.
            keys = ("outcome", "decoded", "paused", "resumed", "seek_ok", "duration_ms", "seekable", "source_codec", "decoded_frames", "decoded_after_seek_frames", "seek_target_ms", "position_after_seek_ms", "sample_rate", "channels", "error_code")
            return {key: value[key] for key in keys if key in value}
        except (OSError, ValueError, subprocess.TimeoutExpired):
            return {"outcome": "probe_process_failed", "decoded": False}

    def lyric(self, record, data):
        lyric = data if isinstance(data, str) else data.get("lyric", data.get("lrc", "")) if isinstance(data, dict) else ""
        if isinstance(lyric, str):
            record.update({"lyric_chars": len(lyric), "timestamped": bool(TIMESTAMP.search(lyric))})
            if record["outcome"] == "ok" and not lyric:
                record["outcome"] = "empty"

    def tracks(self, record, rows):
        rows = rows if isinstance(rows, list) else []
        good = [row for row in rows if isinstance(row, dict) and (row.get("id") or row.get("mid") or row.get("hash")) and (row.get("name") or row.get("title") or row.get("filename"))]
        record["count"] = len(good)
        record["samples"] = [{"id": str(row.get("id", row.get("mid", row.get("hash", "")))), "name": row.get("name", row.get("title", row.get("filename", "")))} for row in good[:3]]
        if record["outcome"] == "ok" and not good:
            record["outcome"] = "empty_or_invalid_tracks"
        return good[:self.args.max_tracks]

    def gd(self):
        base = "https://music-api.gdstudio.xyz/api.php"
        for source in self.args.sources:
            data, record = self.request("gd", source, "search", endpoint(base, {"types": "search", "source": source, "name": self.args.query, "count": 3, "pages": 1}))
            if record["outcome"] != "ok":
                continue
            for track in self.tracks(record, data):
                media, result = self.request("gd", source, "url", endpoint(base, {"types": "url", "source": source, "id": track["id"], "br": 128}))
                address = media.get("url", "") if isinstance(media, dict) else ""
                result["url_present"] = bool(address)
                result["track_id"] = str(track["id"])
                result["requested_br"] = 128
                if not address and result["outcome"] == "ok":
                    result["outcome"] = "empty_url"
                if address and result["outcome"] == "ok":
                    self.audio("gd", source, address, track_id=track["id"])
                lyric, result = self.request("gd", source, "lyrics", endpoint(base, {"types": "lyric", "source": source, "id": track.get("lyric_id", track["id"])}))
                self.lyric(result, lyric)

    def meting(self, provider):
        base = "https://api.injahow.cn/meting/" if provider == "injahow" else "https://api.i-meto.com/meting/api"
        sources = ["tencent"] if provider == "injahow" else [x for x in self.args.sources if x in ("tencent", "kugou", "kuwo")]
        for source in sources:
            query = {"server": source, "type": "song", "id": "001RGrEX3ija5X"} if provider == "injahow" else {"server": source, "type": "search", "id": self.args.query}
            data, record = self.request(provider, source, query["type"], endpoint(base, query))
            rows = data if isinstance(data, list) else []
            good = [row for row in rows if isinstance(row, dict) and (row.get("name") or row.get("title")) and (row.get("artist") or row.get("author"))]
            record["count"] = len(good)
            record["samples"] = [{"name": row.get("name", row.get("title", "")), "artist": row.get("artist", row.get("author", ""))} for row in good[:3]]
            if record["outcome"] == "ok" and not good:
                record["outcome"] = "empty_or_invalid_tracks"
            for row in good[:self.args.max_tracks]:
                if row.get("url"):
                    self.audio(provider, source, row["url"])
                if row.get("lrc"):
                    lyric, result = self.request(provider, source, "lyrics", row["lrc"], text=True)
                    self.lyric(result, lyric)

    def ourcraft(self):
        base = "https://music.yuncan.xyz/api"
        for source in (x for x in self.args.sources if x in ("kugou", "kuwo")):
            data, record = self.request("ourcraft", source, "search", endpoint(base, {"server": source, "type": "search", "id": self.args.query, "limit": 3}))
            if record["outcome"] != "ok":
                continue
            for track in self.tracks(record, data.get("songs", [])):
                media, result = self.request("ourcraft", source, "url", endpoint(base, {"server": source, "type": "url", "id": track["id"], "json": 1}))
                address = media.get("url", "") if isinstance(media, dict) else ""
                result["url_present"] = bool(address)
                result["track_id"] = str(track["id"])
                if not address and result["outcome"] == "ok":
                    result["outcome"] = "empty_url"
                if address and result["outcome"] == "ok":
                    self.audio("ourcraft", source, address, track_id=track["id"])
                lyric, result = self.request("ourcraft", source, "lyrics", endpoint(base, {"server": source, "type": "lrc", "id": track["id"]}), text=True)
                self.lyric(result, lyric)
            if source == "kugou" and self.args.playlists:
                lists, result = self.request("ourcraft", source, "playlist_search", endpoint(base, {"server": source, "type": "playlist_search", "id": self.args.playlist_query, "limit": 2}))
                rows = lists.get("playlists", []) if isinstance(lists, dict) else []
                result["count"] = len(rows) if isinstance(rows, list) else 0
                if rows and result["outcome"] == "ok":
                    songs, result = self.request("ourcraft", source, "playlist", endpoint(base, {"server": source, "type": "playlist_detail", "id": rows[0]["id"], "limit": 3, "offset": 0}))
                    result["playlist_id"] = str(rows[0]["id"])
                    if isinstance(songs, dict):
                        result["total"] = songs.get("total")
                        self.tracks(result, songs.get("songs", []))

    def qq(self, legacy=False):
        provider = "qq-legacy" if legacy else "qq"
        headers = {"Referer": "https://y.qq.com/"}
        module = "music.search.SearchCgiService"
        if legacy:
            data, record = self.request(provider, "tencent", "search", endpoint("https://c.y.qq.com/soso/fcgi-bin/client_search_cp", {"format": "json", "p": 1, "n": 3, "w": self.args.query, "aggr": 1, "lossless": 1, "cr": 1, "new_json": 1}), headers=headers)
            self.tracks(record, at(data, "data.song.list", []))
            return
        gateway = "https://u.y.qq.com/cgi-bin/musicu.fcg"
        data, record = self.request(provider, "tencent", "search", gateway, payload={module: {"module": module, "method": "DoSearchForQQMusicDesktop", "param": {"search_type": 0, "query": self.args.query, "page_num": 1, "num_per_page": 3}}}, headers=headers)
        rows = self.tracks(record, at(data.get(module, {}) if isinstance(data, dict) else {}, "data.body.song.list", []))
        if not rows:
            detail, result = self.request(provider, "tencent", "known_song", endpoint("https://c.y.qq.com/v8/fcg-bin/fcg_play_single_song.fcg", {"songmid": "001RGrEX3ija5X", "platform": "yqq", "format": "json"}), headers=headers)
            rows = self.tracks(result, detail.get("data", []) if isinstance(detail, dict) else [])
        for track in rows:
            pay = track.get("pay", {})
            if isinstance(pay, dict) and pay.get("pay_play") in (1, True):
                self.results.append({"provider": provider, "source": "tencent", "operation": "url", "outcome": "requires_auth", "reason": "pay_play", "song_id": track.get("mid")})
                continue
            mid = track.get("mid", track.get("songmid"))
            media_mid = at(track, "file.media_mid", mid)
            payload = {"req_0": {"module": "vkey.GetVkeyServer", "method": "CgiGetVkey", "param": {"guid": str(uuid.uuid4().int % 10000000000), "songmid": [mid], "filename": ["M500" + str(media_mid) + ".mp3"], "songtype": [track.get("type", 0)], "uin": "0", "loginflag": 1, "platform": "20"}}}
            media, result = self.request(provider, "tencent", "url", gateway, payload=payload, headers=headers)
            info = at(media, "req_0.data.midurlinfo", [])
            sip = at(media, "req_0.data.sip", [])
            address = urljoin(sip[0], info[0]["purl"]) if sip and info and info[0].get("purl") else ""
            result["url_present"] = bool(address)
            result["track_id"] = str(mid)
            result["requested_br"] = 128
            if not address and result["outcome"] == "ok":
                result["outcome"] = "empty_url"
            if address and result["outcome"] == "ok":
                self.audio(provider, "tencent", address, track_id=mid)
            lyric, result = self.request(provider, "tencent", "lyrics", endpoint("https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg", {"songmid": mid, "g_tk": 5381, "format": "json"}), headers=headers)
            if isinstance(lyric, dict) and lyric.get("lyric"):
                try:
                    lyric = html.unescape(base64.b64decode(lyric["lyric"]).decode("utf-8", "replace"))
                except ValueError:
                    lyric = ""
            self.lyric(result, lyric)

    def native_search(self, source):
        if source == "kugou":
            rows = []
            for scheme in ("https", "http"):
                url = endpoint(scheme + "://mobilecdn.kugou.com/api/v3/search/song", {"api_ver": 1, "area_code": 1, "correct": 1, "pagesize": 3, "plat": 2, "tag": 1, "sver": 5, "showtype": 10, "page": 1, "keyword": self.args.query, "version": 8990})
                data, record = self.request(source, source, "search_" + scheme, url, headers={"User-Agent": "IPhone-8990-searchSong"})
                found = self.tracks(record, at(data, "data.info", []))
                if found and record["outcome"] == "ok" and not rows:
                    rows = found
            for track in rows:
                media, result = self.request(source, source, "url_legacy", "http://m.kugou.com/app/i/getSongInfo.php", payload={"cmd": "playInfo", "hash": track["hash"], "from": "mkugou"}, form=True, headers={"User-Agent": "IPhone-8990-searchSong"})
                address = media.get("url", "") if isinstance(media, dict) else ""
                result["track_id"] = str(track["hash"])
                result["url_present"] = bool(address)
                if not address and result["outcome"] == "ok":
                    result["outcome"] = "empty_url"
                if address and result["outcome"] == "ok":
                    self.audio(source, source, address, track_id=track["hash"])
        else:
            url = endpoint("https://www.kuwo.cn/api/www/search/searchMusicBykeyWord", {"key": self.args.query, "pn": 1, "rn": 3, "httpsStatus": 1})
            data, record = self.request(source, source, "search", url, headers={"Referer": "https://www.kuwo.cn/"})
            self.tracks(record, at(data, "data.list", []))

    def vkeys(self):
        base = "https://api.vkeys.cn/music/tencent"
        data, record = self.request("vkeys", "tencent", "search", endpoint(base + "/search/song", {"keyword": self.args.query, "page": 1, "limit": 3}))
        raw = at(data, "data.list", [])
        rows = [{"id": row.get("songMID"), "name": row.get("title"), "pay": row.get("pay"), "type": row.get("type", 0)} for row in raw if isinstance(row, dict)] if isinstance(raw, list) else []
        for track in self.tracks(record, rows):
            if track.get("pay") in ("付费", "VIP", True, 1):
                self.results.append({"provider": "vkeys", "source": "tencent", "operation": "url", "outcome": "requires_auth", "reason": "provider_pay_label", "song_id": track["id"]})
                continue
            media, result = self.request("vkeys", "tencent", "url", endpoint(base + "/song/link", {"mid": track["id"], "quality": 4, "type": track["type"]}))
            address = at(media, "data.url", "")
            result["url_present"] = bool(address)
            result["track_id"] = str(track["id"])
            result["requested_quality"] = "standard(4)"
            if not address and result["outcome"] == "ok":
                result["outcome"] = "empty_url"
            if address and result["outcome"] == "ok":
                self.audio("vkeys", "tencent", address, track_id=track["id"])

    def save(self):
        stamp = datetime.now().astimezone().isoformat(timespec="seconds")
        output = self.args.output
        output.mkdir(parents=True, exist_ok=True)
        document = {"at": stamp, "query": self.args.query, "anonymous": True, "proxy_enabled": self.proxy_enabled, "header_sample_bytes": 64, "qt_short_playback_tested": bool(self.args.qt_probe), "whole_song_playback_tested": False, "references": {k: REFERENCES[k] for k in self.args.providers}, "results": self.results}
        (output / "results.json").write_text(json.dumps(document, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        lines = ["# 匿名音乐接口自动探测", "", "时间：" + stamp, "", "仅请求公开匿名路径；头部阶段最多读取 64 字节，可选 Qt 阶段短时加载、静音解码、暂停定位恢复。未试听整首，也未保存音频文件、URL 查询参数、账号凭据或歌词正文。", "", "| 提供方 | 平台 | 操作 | HTTP | 结果 | 观察 |", "| --- | --- | --- | --- | --- | --- |"]
        for result in self.results:
            facts = []
            for key in ("count", "url_present", "signature", "timestamped", "lyric_chars", "https_downgrade", "range_compliant", "error_code", "code"):
                if key in result:
                    facts.append(f"{key}={result[key]}")
            if result.get("qt"):
                facts.append("Qt=" + result["qt"].get("outcome", "unknown"))
            lines.append(f"| {result['provider']} | {result['source']} | {result['operation']} | {result.get('http', '—')} | {result['outcome']} | {'; '.join(facts)} |")
        lines.extend(["", "结果含义：audio_header 只表示状态及文件头符合音频；empty_url 表示没有取得地址；requires_auth 跳过登录/购买条件；blocked 不代表一定需要登录；range_compliant=false 需继续检查拖动播放和恢复位置。", "", "来源：", ""])
        lines.extend(f"- [{key}]({REFERENCES[key]})" for key in self.args.providers)
        (output / "results.md").write_text("\n".join(lines) + "\n", encoding="utf-8")
        counts = {}
        for record in self.results:
            counts[record["outcome"]] = counts.get(record["outcome"], 0) + 1
        print("Final outcomes: " + json.dumps(counts, ensure_ascii=False), flush=True)
        print("Report: " + str(output.resolve() / "results.md"), flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--providers", nargs="+", choices=list(REFERENCES), default=list(REFERENCES))
    parser.add_argument("--sources", nargs="+", choices=["netease", "tencent", "kugou", "kuwo", "joox"], default=["netease", "tencent", "kugou", "kuwo", "joox"])
    parser.add_argument("--query", default="海阔天空")
    parser.add_argument("--max-tracks", type=int, choices=range(1, 4), default=1)
    parser.add_argument("--timeout", type=float, default=8)
    parser.add_argument("--delay", type=float, default=0.3)
    parser.add_argument("--proxy", default="auto", help="auto reads enabled Windows system proxy; none uses direct network; or an explicit proxy URL")
    parser.add_argument("--qt-probe", type=Path, help="Optional compiled tests/music_api_playback_probe.cpp diagnostic")
    parser.add_argument("--qt-bin", type=Path, help="Qt runtime DLL directory for the optional playback probe")
    parser.add_argument("--check-origin", action="store_true", help="Compare the original URL explicitly included by the Ourcraft proxy (no authentication changes)")
    parser.add_argument("--playlists", action="store_true", help="Also check Ourcraft Kugou public playlist search/detail")
    parser.add_argument("--playlist-query", default="儿歌")
    parser.add_argument("--output", type=Path, default=Path("artifacts/music-api-probes") / datetime.now().strftime("%Y%m%d-%H%M%S"))
    args = parser.parse_args()
    if not args.query.strip() or len(args.query) > 100 or not args.playlist_query.strip() or len(args.playlist_query) > 100 or not 1 <= args.timeout <= 30 or not 0 <= args.delay <= 5:
        parser.error("Invalid query, timeout or delay")
    if args.qt_probe and not args.qt_probe.is_file():
        parser.error("Qt probe executable does not exist")
    probe = Probe(args)
    try:
        for provider in args.providers:
            if provider == "gd":
                probe.gd()
            elif provider in ("injahow", "i-meto"):
                probe.meting(provider)
            elif provider == "ourcraft":
                probe.ourcraft()
            elif provider in ("qq", "qq-legacy"):
                probe.qq(provider == "qq-legacy")
            elif provider == "vkeys":
                probe.vkeys()
            else:
                probe.native_search(provider)
    finally:
        probe.save()


if __name__ == "__main__":
    main()
