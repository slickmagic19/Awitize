"""Awitize music-api — ytmusicapi (metadata) + yt-dlp (stream URLs)."""
import os
import re
import socket
import time
import urllib.parse
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from fastapi import FastAPI, HTTPException, Request
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse, Response, StreamingResponse
from ytmusicapi import YTMusic
import requests
import yt_dlp

app = FastAPI(title="Awitize music-api")
app.add_middleware(CORSMiddleware, allow_origins=["*"], allow_methods=["*"], allow_headers=["*"])

yt = YTMusic()  # unauthenticated for v1; add oauth.json later for library sync
_cache: dict[str, tuple[float, dict]] = {}
CACHE_TTL = 4 * 3600  # 4h, URLs expire ~6h
_search_cache: dict[str, tuple[float, dict]] = {}
SEARCH_TTL = 10 * 60  # 10min, metadata barely changes
_all_cache: dict[str, tuple[float, dict]] = {}
EXPLORE_TTL = 30 * 60  # 30min, shelves change slowly
_explore_cache: dict[str, tuple[float, dict]] = {}

# Offline library — persisted under AWITIZE_DATA_DIR (the desktop shell points
# this at Electron userData); falls back to ./offline when run standalone.
OF_DIR = Path(os.environ.get("AWITIZE_DATA_DIR") or (Path(__file__).resolve().parent / "offline"))
OF_DIR.mkdir(parents=True, exist_ok=True)
_VID_ID = re.compile(r"^[A-Za-z0-9_-]{6,20}$")
_OFF_MIME = {".m4a": "audio/mp4", ".webm": "audio/webm", ".opus": "audio/ogg",
             ".ogg": "audio/ogg", ".weba": "audio/webm", ".mp3": "audio/mpeg",
             ".aac": "audio/aac", ".mp4": "audio/mp4"}


def _thumb(o: dict) -> str | None:
    ts = o.get("thumbnails") or []
    return ts[-1].get("url") if ts else None


def _artist_names(o: dict) -> list[str]:
    out = []
    for a in o.get("artists") or []:
        n = a.get("name") if isinstance(a, dict) else a
        if n:
            out.append(n)
    return out


def _norm(r: dict) -> dict:
    album = r.get("album")
    # artist results carry the name in 'artist', not 'title'
    title = r.get("title") or (r.get("artist") if r.get("resultType") == "artist" else None)
    return {
        "id": r.get("videoId") or r.get("browseId"),
        "type": r.get("resultType"),
        "title": title,
        "artists": _artist_names(r),
        "album": album.get("name") if isinstance(album, dict) else album,
        "duration": r.get("duration"),
        "year": r.get("year"),
        "artwork": _thumb(r),
        "videoId": r.get("videoId"),
        "browseId": r.get("browseId"),
        "playlistId": r.get("playlistId"),
        "views": r.get("views"),
    }


@app.get("/health")
def health():
    return {"ok": True, "ytmusic": True}


@app.get("/connectivity")
def connectivity():
    """Real reachability probe for the desktop shell: the renderer polls this
    at startup, on OS online/offline events, and every ~10s while offline to
    switch between the full UI and downloads-only mode. Never errors — the
    shell distinguishes 'no internet' ({online: false}) from 'backend down'."""
    try:
        with socket.create_connection(("www.youtube.com", 443), timeout=2.5):
            return {"online": True}
    except Exception:
        return {"online": False}


@app.get("/search")
def search(q: str, filter: str | None = None, limit: int = 25):
    limit = max(1, min(limit, 100))
    key = f"{q.strip().lower()}|{filter}|{limit}"
    now = time.time()
    if key in _search_cache and now - _search_cache[key][0] < SEARCH_TTL:
        return _search_cache[key][1]
    last_err: Exception | None = None
    for _ in range(2):  # one retry — YouTube throttles burst searches
        try:
            results = yt.search(q, filter=filter, limit=limit) if filter else yt.search(q, limit=limit)
            last_err = None
            break
        except Exception as e:
            last_err = e
            time.sleep(1.5)
    if last_err is not None:
        raise HTTPException(502, f"YouTube throttled the search. Wait a few seconds and retry. ({last_err})")
    out = [_norm(r) for r in results[:limit]]
    payload = {"q": q, "items": out}
    _search_cache[key] = (now, payload)
    return payload


_ALL_FILTERS = ["songs", "albums", "videos", "artists"]


def _filtered(q: str, f: str, limit: int) -> list:
    try:
        return YTMusic().search(q, filter=f, limit=limit)
    except Exception:
        return []


@app.get("/search/all")
def search_all(q: str, limit: int = 12):
    """YTM-style grouped search: one shelf per category, fanned out in parallel."""
    limit = max(1, min(limit, 30))
    key = q.strip().lower()
    now = time.time()
    if key in _all_cache and now - _all_cache[key][0] < SEARCH_TTL:
        return _all_cache[key][1]
    with ThreadPoolExecutor(max_workers=4) as ex:
        futs = {f: ex.submit(_filtered, q, f, limit) for f in _ALL_FILTERS}
        raw = {f: futs[f].result() for f in _ALL_FILTERS}
    payload = {"q": q, "sections": {f: [_norm(r) for r in raw[f]] for f in _ALL_FILTERS}}
    _all_cache[key] = (now, payload)
    return payload


@app.get("/album/{browse_id}")
def album(browse_id: str):
    try:
        a = yt.get_album(browse_id)
    except Exception as e:
        raise HTTPException(502, f"Album fetch failed. Retry in a bit. ({e})")
    art = _thumb(a)
    tracks = [{
        "videoId": t.get("videoId"), "title": t.get("title"),
        "artists": _artist_names(t), "album": a.get("title"),
        "duration": t.get("duration"), "artwork": art, "type": "song",
    } for t in a.get("tracks", []) or []]
    return {"title": a.get("title"), "artists": _artist_names(a),
            "year": a.get("year"), "artwork": art, "tracks": tracks}


@app.get("/artist/{browse_id}")
def artist(browse_id: str):
    try:
        a = yt.get_artist(browse_id)
    except Exception as e:
        raise HTTPException(502, f"Artist fetch failed. Retry in a bit. ({e})")
    songs = [_norm(t) for t in ((a.get("songs") or {}).get("results") or [])]
    albums = [_norm(r) for r in ((a.get("albums") or {}).get("results") or [])]
    return {"name": a.get("name"), "artwork": _thumb(a),
            "songs": songs, "albums": albums}


# Resolve ladder: unauthenticated fast path first, then the user's own
# Firefox session (auto-read, stays on this machine), then slower fallbacks.
_STRATEGIES = [
    {"name": "android_music",
     "extractor_args": {"youtube": {"player_client": ["android_music"]}}},
    {"name": "firefox",
     "cookies": ("firefox",),
     "extractor_args": {"youtube": {"player_client": ["web", "android"]}}},
    {"name": "web_music",
     "extractor_args": {"youtube": {"player_client": ["web_music"],
                                    "player_skip": ["webpage"]}}},
    {"name": "default", "extractor_args": {}},
]


def _classify(msg: str) -> HTTPException:
    m = msg.lower()
    if any(k in m for k in ["login", "sign in", "confirm your age", "age",
                            "private", "deleted", "unavailable", "not available",
                            "removed", "region", "blocked", "copyright"]):
        return HTTPException(404, {"code": "UNPLAYABLE",
            "message": "YouTube won't stream this track here (region-locked, removed, or login-walled). Try another."})
    if "429" in m or "too many" in m or "rate-limit" in m:
        return HTTPException(429, {"code": "THROTTLED",
            "message": "YouTube throttled us. Wait a few seconds and retry."})
    return HTTPException(502, {"code": "RESOLVE_FAILED",
        "message": f"Couldn't resolve a stream ({msg[:120]}). Retry or pick another track."})


_host_status: dict[str, tuple[bool, float]] = {}
_ip_status: dict[str, tuple[bool, float]] = {}
_served_ips: set[str] = set()  # IPs already proven to serve bytes
_HOST_TTL = 600  # a blocked ISP node stays blocked for a while — remember it


def _reachable_ip(ip: str, timeout: float = 0.8) -> bool:
    try:
        with socket.create_connection((ip, 443), timeout=timeout):
            return True
    except Exception:
        return False


def _memo(m: dict, key: str, val: bool) -> None:
    m[key] = (val, time.time())
    if len(m) > 1024:  # bound the memo
        for k in list(m)[:-512]:
            m.pop(k, None)


def _host_ok(host: str) -> bool:
    """Memoized by host AND by resolved IP — media node names rotate per
    request, but the ISP's dead cache IPs stay put, so we learn them once."""
    now = time.time()
    st = _host_status.get(host)
    if st and now - st[1] < _HOST_TTL:
        return st[0]
    try:
        ip = socket.gethostbyname(host)
    except Exception:
        _memo(_host_status, host, False)
        return False
    st = _ip_status.get(ip)
    if st and now - st[1] < _HOST_TTL:
        ok = st[0]
        _memo(_host_status, host, ok)
        return ok
    ok = _reachable_ip(ip)
    _memo(_ip_status, ip, ok)
    _memo(_host_status, host, ok)
    return ok


def _fetch_ok(url: str) -> bool:
    try:
        r = requests.get(url, headers={"Range": "bytes=0-2047",
                                       "User-Agent": "Mozilla/5.0"},
                         timeout=10, stream=True)
        ok = r.status_code in (200, 206) and bool(r.raw.read(2048))
        r.close()
        return ok
    except Exception:
        return False


def _fix_host(url: str) -> str:
    """ISPs sometimes blackhole the media node they hand out (their local
    cache), while Google's global edges stay reachable. A googlevideo URL
    authorizes every node in `mn=` — hop to one that actually answers.
    Candidates are probed in parallel and results memoized, so the dead
    node costs us once, never again."""
    try:
        p = urllib.parse.urlparse(url)
        host = p.hostname or ""
        if not host:
            return url
        nodes = [n for n in urllib.parse.parse_qs(p.query)
                 .get("mn", [""])[0].split(",") if n]
        cands = [host] + [f"rr{i}---{n}.googlevideo.com"
                          for n in nodes for i in (1, 2, 3)]
        with ThreadPoolExecutor(max_workers=8) as ex:
            live = list(ex.map(_host_ok, cands))
        if live[0]:
            return url
        for cand, ok in zip(cands[1:], live[1:]):
            if not ok:
                continue
            alt = url.replace(host, cand, 1)
            try:
                ip = socket.gethostbyname(cand)
            except Exception:
                ip = ""
            if ip and ip in _served_ips:  # already proven to serve audio
                return alt
            if _fetch_ok(alt):
                if ip:
                    _served_ips.add(ip)
                return alt
    except Exception:
        pass
    return url


def _resolve(video_id: str) -> dict:
    url = f"https://www.youtube.com/watch?v={video_id}"
    last_err = "empty response from YouTube"
    for s in _STRATEGIES:
        opts = {
            # Chromium plays opus/webm fine; don't narrow to m4a.
            "format": "bestaudio/best",
            "quiet": True,
            "noplaylist": True,
            "skip_download": True,
            "retries": 2,
            "socket_timeout": 15,
            "extractor_args": s["extractor_args"],
        }
        if "cookies" in s:
            opts["cookiesfrombrowser"] = s["cookies"]
        try:
            with yt_dlp.YoutubeDL(opts) as ydl:
                info = ydl.extract_info(url, download=False)
            stream_url = (info or {}).get("url")
            if not stream_url:
                fmts = (info or {}).get("requested_formats") or []
                stream_url = (fmts[0].get("url") if fmts else None)
            if stream_url:
                return {"url": _fix_host(stream_url), "via": s["name"],
                        "mime": (info or {}).get("ext"),
                        "bitrate": (info or {}).get("abr"),
                        "duration": (info or {}).get("duration"),
                        "expiresIn": 6 * 3600}
            last_err = f"{s['name']}: no usable format"
        except Exception as e:
            last_err = f"{s['name']}: {str(e).splitlines()[0][:200]}"
    raise _classify(last_err)


@app.get("/stream/{video_id}")
def stream(video_id: str, fresh: int = 0):
    """Resolve a playable audio URL. ?fresh=1 skips the URL cache so a caller
    whose cached URL expired (403/410 mid-playback) gets a brand-new one."""
    now = time.time()
    if not fresh and video_id in _cache and now - _cache[video_id][0] < CACHE_TTL:
        return _cache[video_id][1]
    payload = _resolve(video_id)
    _cache[video_id] = (now, payload)
    return payload


# ---------------------------------------------------------------- offline

def _off_file(video_id: str) -> Path | None:
    for f in OF_DIR.glob(f"{video_id}.*"):
        if f.is_file() and f.suffix.lower() in _OFF_MIME:
            return f
    return None


def _download_audio(video_id: str) -> Path:
    """Fetch the bestaudio file with the same strategy ladder as /stream:
    android_music, then the user's Firefox session, then web_music, default."""
    done = _off_file(video_id)
    if done:
        return done
    url = f"https://www.youtube.com/watch?v={video_id}"
    last_err = "empty response from YouTube"
    for s in _STRATEGIES:
        opts = {
            # keep the container yt-dlp hands us — Chromium plays m4a/webm/opus
            "format": "bestaudio[ext=m4a]/bestaudio/best",
            "outtmpl": str(OF_DIR / f"{video_id}.%(ext)s"),
            "quiet": True,
            "noplaylist": True,
            "retries": 2,
            "socket_timeout": 15,
            "extractor_args": s["extractor_args"],
        }
        if "cookies" in s:
            opts["cookiesfrombrowser"] = s["cookies"]
        try:
            with yt_dlp.YoutubeDL(opts) as ydl:
                ydl.download([url])
            got = _off_file(video_id)
            if got:
                return got
            last_err = f"{s['name']}: no file produced"
        except Exception as e:
            for junk in OF_DIR.glob(f"{video_id}.*"):
                if junk.suffix in (".part", ".ytdl") or ".part" in junk.name:
                    try:
                        junk.unlink()
                    except OSError:
                        pass
            last_err = f"{s['name']}: {str(e).splitlines()[0][:200]}"
    raise _classify(last_err)


@app.post("/offline/save/{video_id}")
def offline_save(video_id: str):
    if not _VID_ID.match(video_id):
        raise HTTPException(400, {"code": "BAD_ID", "message": "Invalid video id."})
    f = _download_audio(video_id)
    return {"videoId": video_id, "saved": True, "bytes": f.stat().st_size}


@app.get("/offline/list")
def offline_list():
    items = []
    for f in OF_DIR.iterdir():
        if f.is_file() and f.suffix.lower() in _OFF_MIME:
            items.append({"videoId": f.stem, "bytes": f.stat().st_size})
    return {"items": items}


@app.get("/offline/{video_id}")
def offline_get(video_id: str, request: Request):
    f = _off_file(video_id)
    if not f:
        raise HTTPException(404, "Not saved offline.")
    size = f.stat().st_size
    mime = _OFF_MIME.get(f.suffix.lower(), "application/octet-stream")
    range_h = request.headers.get("range") or ""
    m = re.match(r"bytes=(\d*)-(\d*)", range_h)
    if m and size:
        start = int(m.group(1)) if m.group(1) else 0
        end = int(m.group(2)) if m.group(2) else size - 1
        end = min(end, size - 1)
        if start > end:
            return Response(status_code=416, headers={"Content-Range": f"bytes */{size}"})

        def chunks():
            with open(f, "rb") as fh:
                fh.seek(start)
                left = end - start + 1
                while left > 0:
                    blk = fh.read(min(65536, left))
                    if not blk:
                        break
                    left -= len(blk)
                    yield blk

        return StreamingResponse(chunks(), status_code=206, media_type=mime,
                                 headers={"Content-Range": f"bytes {start}-{end}/{size}",
                                          "Accept-Ranges": "bytes",
                                          "Content-Length": str(end - start + 1)})
    return FileResponse(f, media_type=mime, headers={"Accept-Ranges": "bytes"})


@app.delete("/offline/{video_id}")
def offline_delete(video_id: str):
    removed, busy = 0, False
    for f in OF_DIR.glob(f"{video_id}.*"):
        if not f.is_file():
            continue
        try:
            f.unlink()
            removed += 1
        except OSError:
            busy = True
    if removed == 0 and busy:
        raise HTTPException(409, {"code": "FILE_BUSY",
            "message": "That download is in use right now — stop playback (or wait a moment) and try again."})
    return {"removed": removed > 0}


LRC_TAG = re.compile(r"\[(\d+):(\d+(?:\.\d+)?)\]")
WORD_TAG = re.compile(r"<(\d+):(\d+(?:\.\d+)?)>")


def _parse_lrc(s: str | None) -> list[dict]:
    """Line-level LRC plus enhanced word-level tags (<mm:ss.xx> word)."""
    out = []
    for raw in (s or "").splitlines():
        tags = LRC_TAG.findall(raw)
        inner = LRC_TAG.sub("", raw)
        text = WORD_TAG.sub("", inner).strip()
        if not tags or not text:
            continue
        words: list[dict] = []
        chunks = WORD_TAG.split(inner)
        if len(chunks) >= 4:  # at least one <time> word pair
            k, tmp, ok = 1, [], True
            while k + 2 < len(chunks):
                try:
                    wt = int(chunks[k]) * 60 + float(chunks[k + 1])
                except ValueError:
                    ok = False
                    break
                tmp.append({"t": wt, "text": chunks[k + 2]})
                k += 3
            if ok and len(tmp) >= 2:
                words = tmp
        for m, sec in tags:
            try:
                entry: dict = {"t": int(m) * 60 + float(sec), "text": text}
                if words:
                    entry["words"] = words
                out.append(entry)
            except ValueError:
                continue
    return sorted(out, key=lambda x: x["t"])


_TITLE_NOISE = re.compile(
    r"\s*[\(\[]\s*(?:official|lyrics?|lyric\s+video|music\s+video|video|audio"
    r"|visuali[sz]er|mv|hd|hq|4k|8k|explicit|clean)\b[^\)\]]*[\)\]]",
    re.IGNORECASE)


def _clean_title(title: str, artist: str) -> str:
    """YouTube titles carry noise that breaks LRCLIB matching:
    "Artist - Song (Official Music Video)" -> "Song". Without this, LRCLIB
    finds the YT-rip plain-only entry (or nothing) instead of the synced
    album lyrics, and the karaoke highlight never activates."""
    t = title.strip()
    if artist:
        low, a = t.lower(), artist.strip().lower()
        for sep in (" - ", " – ", " — ", " | ", ": "):
            if low.startswith(a + sep):
                t = t[len(a) + len(sep):]
                break
    t = _TITLE_NOISE.sub("", t)
    t = re.sub(r"\s{2,}", " ", t).strip(" -–—|")
    return t or title.strip()


@app.get("/lyrics/full")
def lyrics_full(videoId: str = "", title: str = "", artists: str = "",
                album: str = "", duration: str = ""):
    """Synced lyrics via LRCLIB (LRC timestamps), plain-text via ytmusicapi."""
    first_artist = (artists.split(",")[0] if artists else "").strip()
    if title.strip() and first_artist:
        ua = {"User-Agent": "Awitize/0.1.0 (local music player)"}

        def _get(path: str, params: dict):
            try:
                r = requests.get(f"https://lrclib.net/api{path}", params=params,
                                 headers=ua, timeout=10)
                if r.status_code == 503:  # transient overload — one retry
                    time.sleep(1.5)
                    r = requests.get(f"https://lrclib.net/api{path}", params=params,
                                     headers=ua, timeout=10)
                return r.json() if r.ok else None
            except Exception:
                return None

        def _best(cands) -> list:
            scored = []
            for c in cands or []:
                if not c.get("syncedLyrics"):
                    continue
                parsed = _parse_lrc(c["syncedLyrics"])
                if not parsed:
                    continue
                try:
                    dd = abs(float(c.get("duration") or 0) - float(duration or 0))
                except (TypeError, ValueError):
                    dd = 999
                penalty = 0 if not duration.strip() else (0 if dd <= 8 else (1 if dd <= 25 else 2))
                scored.append((penalty, parsed))
            scored.sort(key=lambda x: x[0])
            return scored[0][1] if scored else []

        def _lrclib(track: str):
            base = {"track_name": track, "artist_name": first_artist,
                    "album_name": album.strip(), "duration": duration.strip()}
            base = {k: v for k, v in base.items() if v}
            hit = _get("/get", base)
            if hit and hit.get("syncedLyrics"):
                lines = _parse_lrc(hit["syncedLyrics"])
                if lines:
                    return {"synced": True, "source": "lrclib", "lines": lines}
            cands = _get("/search", {"q": f"{track} {first_artist}"})
            lines = _best(cands)
            if lines:
                return {"synced": True, "source": "lrclib", "lines": lines}
            plain = (hit or {}).get("plainLyrics") or next(
                (c.get("plainLyrics") for c in (cands or []) if c.get("plainLyrics")), "")
            if plain:
                return {"synced": False, "source": "lrclib",
                        "lines": [{"t": 0, "text": ln} for ln in plain.splitlines() if ln.strip()]}
            return None

        clean = _clean_title(title, first_artist)
        out = _lrclib(clean)
        if clean.lower() != title.strip().lower() and not (out or {}).get("synced"):
            raw = _lrclib(title.strip())
            if raw and (raw["synced"] or not out):
                out = raw
        if out:
            return out
    # ytmusicapi plain-text fallback
    try:
        if videoId:
            watch = yt.get_watch_playlist(videoId=videoId)
            lid = (watch.get("lyrics") or {}).get("lyrics")
            if lid:
                data = yt.get_lyrics(lid)
                lines = [{"t": 0, "text": ln}
                         for ln in (data.get("lyrics") or "").splitlines() if ln.strip()]
                if lines:
                    return {"synced": False, "source": "ytmusic", "lines": lines}
    except Exception:
        pass
    return {"synced": False, "source": "none", "lines": []}


def _explore_data():
    try:
        return YTMusic().get_explore()
    except Exception:
        return {}


def _charts_data(country: str):
    try:
        return YTMusic().get_charts(country)
    except Exception:
        return {}


@app.get("/explore")
def explore(country: str = "PH"):
    """YTM Explore shelves: new releases, moods, trending, videos, artists."""
    now = time.time()
    key = (country.strip().upper() or "PH")
    if key in _explore_cache and now - _explore_cache[key][0] < EXPLORE_TTL:
        return _explore_cache[key][1]
    with ThreadPoolExecutor(max_workers=2) as ex:
        f_ex = ex.submit(_explore_data)
        f_ch = ex.submit(_charts_data, key)
        raw_ex, raw_ch = f_ex.result(), f_ch.result()
    payload = {
        "new_releases": [_norm(r) for r in raw_ex.get("new_releases", []) or []],
        "moods": [{"title": m.get("title"), "params": m.get("params")}
                  for m in raw_ex.get("moods_and_genres", []) or [] if m.get("params")],
        "trending": [_norm(r) for r in (raw_ex.get("trending") or {}).get("items", []) or []],
        "new_videos": [_norm(r) for r in raw_ex.get("new_videos", []) or []],
        "chart_artists": [_norm(r) for r in raw_ch.get("artists", []) or []],
    }
    _explore_cache[key] = (now, payload)
    return payload


@app.get("/mood")
def mood(params: str):
    try:
        items = YTMusic().get_mood_playlists(params)
    except Exception as e:
        raise HTTPException(502, f"Mood fetch failed. Retry in a bit. ({e})")
    return {"playlists": [_norm({**p, "resultType": "playlist"}) for p in items or []]}


@app.get("/playlist/{pid}")
def playlist(pid: str):
    try:
        p = yt.get_playlist(pid)
    except Exception as e:
        raise HTTPException(502, f"Playlist fetch failed. Retry in a bit. ({e})")
    thumbs = p.get("thumbnails") or []
    tracks = []
    for t in p.get("tracks", []) or []:
        n = _norm(t)
        if not n.get("artwork") and thumbs:
            n["artwork"] = thumbs[-1].get("url")
        if n.get("videoId"):
            tracks.append(n)
    author = p.get("author") or {}
    return {"title": p.get("title"),
            "artwork": thumbs[-1].get("url") if thumbs else None,
            "author": author.get("name"), "tracks": tracks}


@app.delete("/cache")
def clear_cache():
    _cache.clear()
    _search_cache.clear()
    _all_cache.clear()
    _explore_cache.clear()
    return {"ok": True}


if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="127.0.0.1", port=8765)
