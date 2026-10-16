---
name: electron-desktop-quality
description: Build Awitize desktop shell correctly with Electron Vite React. Enforces main-preload-renderer separation, performance, and native feel.
license: MIT
compatibility: opencode
metadata:
  audience: desktop
  workflow: electron
---

## What I do
- Keep Electron secure, fast, and native-feeling on Windows first.

## Rules
- `main/`: BrowserWindow only, `contextIsolation: true`, `nodeIntegration: false`, no web `fetch` to YouTube directly.
- `preload/`: expose minimal `window.awitize` API (search, resolveStream, queue) via contextBridge. Nothing else.
- `renderer/`: React + Vite only. Audio element is the ONLY playback path for v1. No Web Audio graph yet.
- Window: 1280x800 min, 1200x700 min-size, dark backgroundColor `#0B1020` to avoid white flash, custom titlebar with `-webkit-app-region: drag`.
- Performance: virtualize lists >100 rows, art via `loading=lazy`, cache stream URLs in-memory (expire 4h, refresh on 403), prefetch next track only.
- Native: support Space, Ctrl+K (search), Ctrl+Left/Right (prev/next), media keys via globalShortcut or MPRIS shim later. Persist volume + last query in localStorage.
- Packaging: electron-builder, NSIS x64 Windows first. Python sidecar spawned as hidden child process on `app.ready`, killed on `will-quit`. Healthcheck `GET /health` before showing UI.
- Never: disable webSecurity, use `remote`, ship sourcemaps with secrets, hardcode `localhost:8000` without fallback env `AWITIZE_API_URL`.

## Verify
- `npm run dev` opens window <3s with no white flash.
- Play -> pause -> next works with keyboard only.
- Kill + restart keeps volume and queue order.
