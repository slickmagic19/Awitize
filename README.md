# Awitize — blue-branded Spotify-like desktop player

Unofficial YouTube Music client. Spotify layout, blue brand (`#2E7CF6`).

**Stack:** Electron + Vite + React (Windows-first) + Python FastAPI sidecar (`ytmusicapi` for metadata + `yt-dlp` for stream URLs).

**Skills installed (anti-slop):**
- `.opencode/skills/awitize-design-system/SKILL.md`
- `.opencode/skills/electron-desktop-quality/SKILL.md`
- `.opencode/skills/ytmusic-backend-contract/SKILL.md`

Load them with `skill({name:"awitize-design-system"})` before writing UI.

## Quick start
```powershell
# backend (real python.org Python — Store Python cannot read browser cookies)
cd services/music-api
& "$env:LOCALAPPDATA\Programs\Python\Python313\python.exe" -m venv .venv
.\.venv\Scripts\Activate.ps1
pip install -r requirements.txt
python main.py  # -> http://127.0.0.1:8765/health

# desktop (new terminal)
cd apps/desktop
npm install
npm run dev
```

## Legal note
Streams resolve publicly-accessible YouTube audio for personal local playback only (same as browser + ad-blocker). No download-to-disk, no redistribution, no DRM bypass. Uses unofficial APIs — Google can break it. Premium 141 high-res needs login and is not promised in v1.
