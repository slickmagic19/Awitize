---
name: awitize-design-system
description: Spotify-like desktop UI but blue-branded. Enforces layout, tokens, typography, and anti-slop rules for Awitize.
license: MIT
compatibility: opencode
metadata:
  audience: frontend
  workflow: awitize-ui
---

## What I do
- Enforce Spotify-like structure with Awitize blue branding, not a generic AI gradient page.
- Single source of truth for colors, spacing, type, and components.

## Tokens (USE THESE, no ad-hoc hex)
- `--aw-bg-base: #0B1020` (app shell, near-black blue-tinted)
- `--aw-bg-raise: #121A30` (sidebar / cards, Spotify #181818 equivalent)
- `--aw-bg-hover: #1A2440` (hover, Spotify #282828 equivalent)
- `--aw-brand: #2E7CF6` (primary, replaces Spotify #1DB954)
- `--aw-brand-hover: #4B93FF` (hover / glow)
- `--aw-brand-deep: #0A3BB5` (active / gradient anchor only)
- `--aw-text: #F2F5FF`, `--aw-text-dim: #9AA6C4`, `--aw-border: rgba(148,175,255,0.12)`
- Radius: 8px cards, 999px pills. Spacing 4px base (4/8/12/16/24/32).
- Font: Inter, system fallback. Titles 700, body 400/500. NO other fonts.

## Layout (must match Spotify)
- Left sidebar 280px: logo (blue note mark + Awitize), Home / Search / Library, playlist list, user card bottom.
- Main: top bar (back/fwd, search, window controls), content grid (cards 180px min), bottom player bar 90px.
- Player bar: left (art 56px + title/artist + like), center (shuffle/prev/play/next/repeat + progress), right (volume, queue, fullscreen).
- Card: 16px padding, art 1:1 rounded 6px, title 14px 600, subtitle 13px dim, hover bg-hover + play FAB (blue circle, appears on hover).

## Anti-slop rules (HARD)
- No purple-blue linear gradients as backgrounds. One subtle radial glow max, opacity <12%.
- No emoji icons. Use Lucide only, 1.5px stroke, 18-20px.
- No lorem ipsum, no fake artists. Empty states say what backend returns or "Search to start".
- No glassmorphism everywhere. Flat #121A30 cards, 1px border.
- Every list must have loading skeleton, empty, and error states.
- Play button is ALWAYS `--aw-brand` circle, white icon, never square.
