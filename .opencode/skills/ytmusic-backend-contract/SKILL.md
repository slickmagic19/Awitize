---
name: ytmusic-backend-contract
description: Python FastAPI backend using ytmusicapi for metadata plus yt-dlp for ad-free stream URLs. Defines endpoints, caching, and failure modes.
license: MIT
compatibility: opencode
metadata:
  audience: backend
  workflow: ytmusicapi
---

## What I do
- ytmusicapi = metadata only (search, album, artist, playlist, lyrics). NEVER trust it for stable playback URLs.
- yt-dlp = stream resolver (android_music / web_music client, audio-only itag 140/141). URLs expire ~6h, cache 4h.

## Endpoints (FastAPI, port 8765)
- `GET /health` -> `{ok:true, ytmusic:true}`
- `GET /search?q=&filter=songs|albums|artists|playlists&limit=25` -> passthrough `YTMusic().search` (limit 1-100, capped 100), normalized to `{id,title,artists,duration,artwork,videoId}`. 10-min server cache keyed by query+filter+limit — frontend pages with Show more.
- `GET /song/:videoId` -> `get_song` metadata + `streamingData.adaptiveFormats` filtered audio-only, sorted by bitrate
- `GET /stream/:videoId` -> yt-dlp resolve, returns `{url, expiresAt, mime, bitrate}`. Refresh on 403. Never proxy bytes in v1, return URL for `<audio>` tag.
- `GET /lyrics/full?videoId=&title=&artists=&album=&duration=` -> synced LRC lines via LRCLIB (`{synced, lines:[{t,text}]}`), plain-text fallback via ytmusicapi `get_lyrics`, else `{synced:false, lines:[]}`.

## Rules
- Start unauthenticated: `YTMusic()` no oauth.json. Add auth only for library sync v2.
- yt-dlp resolve ladder (no single client works for every video — try in order, fastest first): `android_music`, then `firefox` cookies (`player_client: [web, android]`), then `web_music` + `player_skip: [webpage]`, then yt-dlp defaults. `format: bestaudio/best` (Chromium plays opus/webm; never narrow to m4a). `retries: 2`, `socket_timeout: 15`. Update yt-dlp monthly, Google breaks clients.
- `GET /explore?country=PH` -> YTM Explore shelves in parallel (`new_releases`, `moods` with params, `trending`, `new_videos`, `chart_artists`), 30-min cache.
- `GET /mood?params=` -> mood playlists; `GET /playlist/:id` -> full track list (playable). Both open in the collection overlay.
- Errors are classified, never raw tracebacks: `UNPLAYABLE` (404: region-locked/removed/login-walled), `THROTTLED` (429), `RESOLVE_FAILED` (502) as `{detail: {code, message}}`.
- Interpreter MUST be the repo venv (`services/music-api/.venv`, python.org build): Windows Store Python cannot do DPAPI cookie reads. Electron spawns the venv automatically. The `firefox` rung reads the local Firefox profile read-only; cookies never leave the machine.
- Media-node hop (`_fix_host` in main.py): ISPs hand out a local cache node that may be blackholed (TCP timeout) while Google's global edges work. Every googlevideo URL authorizes all nodes in its `mn=` query param, so if the primary host is unreachable, rewrite it to `rr<N>---<mn-node>.googlevideo.com` that answers (TCP 443 + ranged GET check). This is what keeps playback working when the ISP cache dies — do not remove.
- Playback never blocks on a network check: `/stream` always returns the best URL found; the `<audio>` tag plays it. If audio doesn't start in ~10s the frontend shows a non-blocking hint.
- Cache: in-memory dict videoId->url with timestamp. Serve stale-while-revalidate, background refresh for queued next track.
- Errors: 404 region-locked, 410 expired (auto-retry once with fresh resolve), 429 rate-limited (backoff + tell UI "YouTube throttled, retry").
- Legal: no download-to-disk, no redistribution, stream-only for personal playback. Surface in README. Premium 141 high-res needs login, don't promise it.
