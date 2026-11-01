import { Component, useEffect, useRef, useState } from 'react';
import type { ReactNode } from 'react';
import {
  AudioLines, Home, Compass, Search, LibraryBig, ChevronLeft, ChevronRight,
  CircleUserRound, Play, Pause, SkipBack, SkipForward, Shuffle, Repeat,
  Heart, Volume2, VolumeX, ListMusic, Maximize2, TriangleAlert, X,
  LoaderCircle, Music2, MoreHorizontal, ListPlus, Clock3, Trash2,
  Minimize2, Plus, ChevronsUpDown, TrendingUp, MicVocal, Disc3, Smile,
  Download, RefreshCw, Sparkles, Check, Info, CloudOff, ImagePlus, Pencil, UserX,
  Smartphone, Palette,
} from 'lucide-react';
import QRCode from 'qrcode';
import { buildSyncSnapshot, mergeIncoming, syncSummary } from './syncCore';
import type { SyncSnapshot, SyncStats } from './syncCore';
import './tokens.css';

const API = 'http://127.0.0.1:8765';
const TAB_FILTER: Record<string, string> = { songs: 'songs', albums: 'albums', videos: 'videos', artists: 'artists' };
const COLORS = ['#2E7CF6', '#8B5CF6', '#22C55E', '#F59E0B', '#EF4444', '#06B6D3'];
// Theme presets — the id drives [data-theme] in tokens.css, which swaps the
// whole palette (colors + brand typeface). Persisted via localStorage.
type ThemeId = 'awitize' | 'spotify' | 'youtube' | 'soundcloud';
const THEMES: { id: ThemeId; name: string; swatch: string }[] = [
  { id: 'awitize', name: 'Awitize Blue', swatch: '#2E7CF6' },
  { id: 'spotify', name: 'Spotify', swatch: '#1DB954' },
  { id: 'youtube', name: 'YouTube', swatch: '#FF0033' },
  { id: 'soundcloud', name: 'SoundCloud', swatch: '#FF5500' },
];
const MOODS = ['#4CAF50', '#FF6B6B', '#FFA726', '#BA68C8', '#4DD0E1', '#F06292', '#7986CB', '#AED581'];
const STARTERS = ['Owl City', 'Jose Mari Chan', 'Taylor Swift', 'The Beatles', 'Eraserheads', 'Christmas songs'];

// Brand play mark: Lucide's triangle carries its visual mass left of center,
// so it always reads off-center in a circle. This triangle's centroid sits
// on the canvas center by construction — no optical nudge needed.
function PlayGlyph({ size = 18 }: { size?: number }) {
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" fill="currentColor" aria-hidden="true">
      <path d="M8 5.5v13l11-6.5z" />
    </svg>
  );
}
const SEARCH_TTL = 10 * 60 * 1000;
const OFFLINE_NAV_MSG = "You're offline — only your downloads are available.";

type Item = { videoId?: string; browseId?: string; playlistId?: string; title?: string; artists?: string[]; album?: string; year?: string; views?: string; artwork?: string; duration?: string };
type Playlist = { id: string; name: string; tracks: Item[] };
type View = 'home' | 'search' | 'library' | 'liked' | 'explore';
type Tab = 'all' | 'songs' | 'albums' | 'videos' | 'artists';
type Profile = { id: string; name: string; color: string; photo?: string };
type ProfileForm = { id?: string; name: string; color: string; photo?: string };
type AlertView = { browseId: string; kind?: string; title?: string; artists?: string[]; year?: string; artwork?: string; tracks: Item[] };
type ArtistView = { browseId: string; name?: string; artwork?: string; songs: Item[]; albums: Item[] };
type MoodView = { title: string; playlists: Item[] };
// A full navigation snapshot: the top-level view plus whatever detail page is
// open on top of it. Back/forward walk these so an album opened from Search
// returns to those exact results.
type NavEntry = { view: View; q: string; tab: Tab; album: AlertView | null; artist: ArtistView | null; mood: MoodView | null };
type PlayEvent = { id: string; ts: number; n: number };
// bridge exposed by electron/preload.js — absent in a plain browser tab
type AppBridge = {
  checkUpdates: () => Promise<{ packaged: boolean; update?: boolean; version?: string; replaceTarget?: string | null }>;
  downloadUpdate: () => Promise<{ path: string; size: number }>;
  applyUpdate: (source: string, target: string) => Promise<{ ok: boolean }>;
  onUpdateProgress: (cb: (ev: { received?: number; total?: number; replaced?: boolean; replaceError?: string }) => void) => () => void;
};
type UpdState = { phase: 'idle' | 'available' | 'downloading' | 'ready' | 'applying' | 'error'; version?: string; path?: string; replace?: string; pct?: number; error?: string };
type Toast = { id: number; kind: 'ok' | 'err' | 'info'; text: string };
type SyncBridge = {
  start: (opts: { name?: string }) => Promise<{ host: string; port: number; token: string; ips: string[] }>;
  stop: () => Promise<{ ok: boolean }>;
  onEvent: (cb: (ev: { type: string; stats?: SyncStats | null; sender?: string | null }) => void) => () => void;
  onRequest: (cb: (req: { reqId: number; kind: 'snapshot' | 'merge'; payload?: unknown }) => void) => () => void;
  respond: (reqId: number, data: unknown) => void;
};
type SyncPhase = 'idle' | 'starting' | 'waiting' | 'linked' | 'error';

function fmt(sec: number) {
  if (!isFinite(sec) || sec < 0) return '0:00';
  return `${Math.floor(sec / 60)}:${String(Math.floor(sec % 60)).padStart(2, '0')}`;
}
function fmtBytes(n: number) {
  if (!n || n < 0) return '';
  if (n >= 1 << 30) return `${(n / (1 << 30)).toFixed(1)} GB`;
  if (n >= 1 << 20) return `${(n / (1 << 20)).toFixed(1)} MB`;
  return `${Math.max(1, Math.round(n / 1024))} KB`;
}
function load<T>(k: string, fb: T): T {
  try { const v = localStorage.getItem(k); return v ? JSON.parse(v) as T : fb; }
  catch { return fb; }
}
function save(k: string, v: unknown) {
  try { localStorage.setItem(k, JSON.stringify(v)); } catch { /* full */ }
}

// ---- phone sync bridge (protocol + merge logic live in syncCore.ts) -------
function syncBridge(): SyncBridge | null {
  return (window as unknown as { awitize?: { sync?: SyncBridge } }).awitize?.sync ?? null;
}

// Converts any render crash into a visible error with one-click recovery
// instead of a blank window. If you ever see this screen, screenshot it.
class ShellBoundary extends Component<{ children: ReactNode }, { error: Error | null }> {
  declare props: { children: ReactNode };
  state = { error: null as Error | null };
  static getDerivedStateFromError(error: Error) { return { error }; }
  componentDidCatch(error: Error) { console.error('[awitize] render crash:', error); }
  render() {
    if (this.state.error) {
      return (
        <div className="aw-gate">
          <div className="aw-logo aw-gate-logo">
            <span className="aw-logo-mark"><AudioLines size={18} strokeWidth={2.5} /></span>
            Awitize
          </div>
          <h1 className="aw-gate-title">Something broke</h1>
          <p style={{ color: 'var(--aw-text-dim)', maxWidth: 560, textAlign: 'center' }}>{this.state.error.message}</p>
          <button className="aw-mini-btn" onClick={() => window.location.reload()}>Reload app</button>
        </div>
      );
    }
    return this.props.children;
  }
}

export default function App() {
  const [q, setQ] = useState('');
  const [view, setView] = useState<View>('home');
  const [items, setItems] = useState<Item[]>([]);
  const [searching, setSearching] = useState(false);
  const [banner, setBanner] = useState('');
  const [current, setCurrent] = useState<Item | null>(null);
  const [playing, setPlaying] = useState(false);
  const [resolvingId, setResolvingId] = useState<string | null>(null);
  const [pos, setPos] = useState(0);
  const [dur, setDur] = useState(0);
  const [vol, setVol] = useState(() => Number(localStorage.getItem('aw-vol') ?? '0.9'));
  const [theme, setTheme] = useState<ThemeId>(() => load<ThemeId>('aw-theme', 'awitize'));
  const [muted, setMuted] = useState(false);
  const [shuffle, setShuffle] = useState(false);
  const [repeat, setRepeat] = useState(false);
  const [likedIds, setLikedIds] = useState<string[]>([]);
  const [trackmap, setTrackmap] = useState<Record<string, Item>>(() => load('aw-tracks', {}));
  const [playlists, setPlaylists] = useState<Playlist[]>([]);
  const [historyQ, setHistoryQ] = useState<string[]>([]);
  const [plays, setPlays] = useState<PlayEvent[]>([]);
  const [menuFor, setMenuFor] = useState<string | null>(null);
  const [menuPos, setMenuPos] = useState<{ up: boolean; maxH: number }>({ up: false, maxH: 420 });
  const [newPlFor, setNewPlFor] = useState<string | null>(null);
  const [newPlName, setNewPlName] = useState('');
  const [queueOpen, setQueueOpen] = useState(false);
  const [focusOpen, setFocusOpen] = useState(false);
  const [avatarOpen, setAvatarOpen] = useState(false);
  const [syncOpen, setSyncOpen] = useState(false);
  const [syncPhase, setSyncPhase] = useState<SyncPhase>('idle');
  const [syncErr, setSyncErr] = useState('');
  const [syncQr, setSyncQr] = useState('');
  const [syncInfo, setSyncInfo] = useState<{ host: string; port: number; ips: string[] } | null>(null);
  const [syncStats, setSyncStats] = useState<SyncStats | null>(null);
  const [syncSender, setSyncSender] = useState('');
  const [apiHealth, setApiHealth] = useState('');
  const [pageSize, setPageSize] = useState(25);
  const [searchFailed, setSearchFailed] = useState(false);
  const [exhausted, setExhausted] = useState(false);
  const [tab, setTab] = useState<Tab>('all');
  const [sections, setSections] = useState<Record<string, Item[]>>({});
  const [albumView, setAlbumView] = useState<AlertView | null>(null);
  const [artistView, setArtistView] = useState<ArtistView | null>(null);
  const [detailLoading, setDetailLoading] = useState(false);
  const [lyricsOpen, setLyricsOpen] = useState(false);
  const [lyrics, setLyrics] = useState<{ synced: boolean; lines: { t: number; text: string; words?: { t: number; text: string }[] }[] } | null>(null);
  const [lyricsLoading, setLyricsLoading] = useState(false);
  const [upd, setUpd] = useState<UpdState>({ phase: 'idle' });
  const [toasts, setToasts] = useState<Toast[]>([]);
  const toastSeq = useRef(0);
  const [offline, setOffline] = useState<Record<string, number>>({});
  const [savingId, setSavingId] = useState<string | null>(null);
  const [dl, setDl] = useState<{ key: string; done: number; total: number } | null>(null);
  const offlineRef = useRef(offline);
  const savingRef = useRef(false);
  const dlRef = useRef(false);
  offlineRef.current = offline;
  const [online, setOnline] = useState(() => navigator.onLine);
  const onlineRef = useRef(online);
  onlineRef.current = online;
  const probeRef = useRef<() => void>(() => {});
  const prevViewRef = useRef<View | null>(null);

  const audioRef = useRef<HTMLAudioElement>(null);
  const searchRef = useRef<HTMLInputElement>(null);
  const reqId = useRef(0);
  const abortRef = useRef<AbortController | null>(null);
  const lastQ = useRef('');
  const cacheRef = useRef(new Map<string, { t: number; items: Item[] }>());
  const pageSizeRef = useRef(25);
  const lastCombo = useRef('');
  const lastBase = useRef(''); // tab|query shown on screen (no page size) — drives skeletons
  const sentinelRef = useRef<HTMLDivElement>(null);
  const queueRef = useRef<Item[]>([]);
  const stallRef = useRef<number | null>(null);
  // live library snapshot for phone-sync requests (reads must never see stale state)
  const syncLibRef = useRef({ name: 'Guest', likedIds: [] as string[], trackmap: {} as Record<string, Item>, playlists: [] as Playlist[], plays: [] as PlayEvent[] });
  const [profiles, setProfiles] = useState<Profile[]>([]);
  const [activePid, setActivePid] = useState<string | null>(null);
  const [screen, setScreen] = useState<'gate' | 'app'>('gate');
  const [managing, setManaging] = useState(false);
  const [form, setForm] = useState<ProfileForm | null>(null);
  const [gateDel, setGateDel] = useState<string | null>(null);
  const [menuDel, setMenuDel] = useState(false);
  const photoRef = useRef<HTMLInputElement>(null);
  const activeProfile = profiles.find((p) => p.id === activePid) ?? null;
  syncLibRef.current = { name: activeProfile?.name ?? 'Guest', likedIds, trackmap, playlists, plays };

  // one-time: seed profiles, migrating the legacy single-user data if present
  useEffect(() => {
    let profs = load<Profile[]>('aw-profiles', []);
    if (!profs.length) {
      const liked = load<string[]>('aw-liked', []);
      const pls = load<Playlist[]>('aw-playlists', []);
      const hist = load<string[]>('aw-history', []);
      const qq = localStorage.getItem('aw-q') ?? '';
      const pid = crypto.randomUUID();
      profs = [{ id: pid, name: (liked.length || pls.length || hist.length) ? 'Guest' : 'Listener 1', color: COLORS[0] }];
      save('aw-profiles', profs);
      if (liked.length) save(`aw:${pid}:liked`, liked);
      if (pls.length) save(`aw:${pid}:playlists`, pls);
      if (hist.length) save(`aw:${pid}:history`, hist);
      if (qq) save(`aw:${pid}:q`, qq);
    }
    setProfiles(profs);
  }, []);

  // self-update: check shortly after launch, then show the download button
  // only in packaged builds when a newer release exists (dev/browser: no-op)
  useEffect(() => {
    const bridge = (window as unknown as { awitize?: { app?: AppBridge } }).awitize?.app;
    if (!bridge) return;
    const off = bridge.onUpdateProgress((ev) => {
      if (ev.replaced) return;
      if (ev.replaceError) {
        setUpd((u) => ({ ...u, phase: 'error', error: `Update installed, but the original file could not be replaced (${ev.replaceError})` }));
        return;
      }
      if (typeof ev.received === 'number') {
        const pct = ev.total && ev.total > 0 ? Math.min(100, Math.floor((ev.received / ev.total) * 100)) : 0;
        setUpd((u) => (u.phase === 'downloading' ? { ...u, pct } : u));
      }
    });
    const timer = window.setTimeout(async () => {
      try {
        const info = await bridge.checkUpdates();
        if (info.packaged && info.update) {
          setUpd({ phase: 'available', version: info.version, replace: info.replaceTarget ?? undefined });
        }
      } catch { /* offline or rate-limited — try again next launch */ }
    }, 2500);
    return () => { window.clearTimeout(timer); off(); };
  }, []);

  async function downloadFlow() {
    const bridge = (window as unknown as { awitize?: { app?: AppBridge } }).awitize?.app;
    if (!bridge) return;
    setUpd((u) => ({ ...u, phase: 'downloading', pct: 0, error: undefined }));
    try {
      const res = await bridge.downloadUpdate();
      setUpd((u) => ({ ...u, phase: 'ready', path: res.path }));
    } catch (err) {
      setUpd((u) => ({ ...u, phase: 'error', error: `Update download failed (${(err as Error).message || err})` }));
    }
  }

  async function applyFlow() {
    const bridge = (window as unknown as { awitize?: { app?: AppBridge } }).awitize?.app;
    if (!bridge) return;
    if (!upd.path) { setUpd((u) => ({ ...u, phase: 'error', error: 'Update file missing — click to download it again' })); return; }
    if (!upd.replace) { setUpd((u) => ({ ...u, phase: 'error', error: 'Renamed portable exe detected — keep it named Awitize-x.y.z-win.exe to enable updates' })); return; }
    setUpd((u) => ({ ...u, phase: 'applying', error: undefined }));
    try {
      await bridge.applyUpdate(upd.path, upd.replace);
      // main quits for the swap in ~800ms; keep the spinner until then
    } catch (err) {
      setUpd((u) => ({ ...u, phase: 'error', error: `Update failed (${(err as Error).message || err})` }));
    }
  }

  function onUpdateClick() {
    if (upd.phase === 'available' || upd.phase === 'error') void downloadFlow();
    else if (upd.phase === 'ready') void applyFlow();
  }

  function updTitle() {
    if (upd.phase === 'available') return `Update ${upd.version} available — click to download`;
    if (upd.phase === 'downloading') return `Downloading update… ${upd.pct ?? 0}%`;
    if (upd.phase === 'ready') return `Restart to update to ${upd.version}`;
    if (upd.phase === 'applying') return 'Restarting…';
    if (upd.phase === 'error') return `${upd.error || 'Update failed'} — click to retry`;
    return '';
  }

  // transient confirmations — "Added to X", download results, …
  function toast(text: string, kind: Toast['kind'] = 'ok') {
    const id = ++toastSeq.current;
    setToasts((t) => [...t.slice(-3), { id, kind, text }]);
    window.setTimeout(() => setToasts((t) => t.filter((x) => x.id !== id)), 3400);
  }

  function toastView() {
    return (
      <div className="aw-toasts" aria-live="polite">
        {toasts.map((t) => (
          <div key={t.id} className={`aw-toast ${t.kind}`}>
            {t.kind === 'ok' ? <Check size={15} /> : t.kind === 'err' ? <TriangleAlert size={15} /> : <Info size={15} />}
            <span>{t.text}</span>
          </div>
        ))}
      </div>
    );
  }

  function syncView() {
    if (!syncOpen) return null;
    return (
      <div className="aw-sync-back" role="dialog" aria-label="Sync with phone" onClick={closeSync}>
        <div className="aw-sync" onClick={(e) => e.stopPropagation()}>
          <div className="aw-sync-head">
            <span className="aw-sync-title"><Smartphone size={17} />Sync with phone</span>
            <button className="aw-icon-btn" aria-label="close sync" onClick={closeSync}><X size={17} /></button>
          </div>

          {syncPhase === 'starting' && (
            <div className="aw-sync-bar"><LoaderCircle size={18} className="spin" /><span>Preparing pairing…</span></div>
          )}

          {syncPhase === 'error' && (
            <>
              <div className="aw-sync-bar err"><TriangleAlert size={16} /><span>{syncErr || 'Something went wrong.'}</span></div>
              <button className="aw-mini-btn" onClick={() => void openSync()}>Try again</button>
            </>
          )}

          {(syncPhase === 'waiting' || syncPhase === 'linked') && syncQr && (
            <>
              <div className="aw-sync-qr"><img src={syncQr} width={248} height={248} alt="Pairing QR code" /></div>
              {syncPhase === 'waiting' ? (
                <div className="aw-sync-bar"><LoaderCircle size={15} className="spin" /><span>Open Awitize on Android → Sync → scan this code</span></div>
              ) : (
                <div className="aw-sync-bar linked"><Check size={15} /><span>Phone connected</span></div>
              )}
              <div className="aw-sync-meta">Phone and PC must be on the same Wi-Fi · This PC: {syncInfo?.host}:{syncInfo?.port}</div>
              {!!(syncInfo && syncInfo.ips.length > 1) && (
                <div className="aw-sync-meta">If the scan fails, another address on this PC: {syncInfo.ips.slice(1).join(', ')}</div>
              )}
              {syncPhase === 'waiting' && (
                <div className="aw-sync-meta">If Windows asks whether to allow Awitize through the firewall, click Allow — pairing needs it.</div>
              )}
            </>
          )}

          {syncStats && (
            <div className="aw-sync-stats">
              <div className="aw-sync-stats-title">Received from {syncSender ? `${syncSender}'s phone` : 'phone'}</div>
              {[
                [syncStats.likesAdded, 'liked songs'],
                [syncStats.playlistsAdded, 'new playlists'],
                [syncStats.playlistsMerged, 'playlists updated'],
                [syncStats.tracksAdded, 'playlist tracks'],
                [syncStats.playsAdded, 'listens'],
              ].filter(([n]) => (n as number) > 0).map(([n, label]) => (
                <div key={label as string} className="aw-sync-stat"><Plus size={13} />{n as number} {label as string}</div>
              ))}
              {!syncStats.likesAdded && !syncStats.playlistsAdded && !syncStats.playlistsMerged && !syncStats.tracksAdded && !syncStats.playsAdded && (
                <div className="aw-sync-stat none">Already in sync — nothing new.</div>
              )}
            </div>
          )}

          <div className="aw-sync-foot">Sync only adds — nothing on either device is ever deleted. Downloads stay on the device that made them.</div>
          <button className="aw-mini-btn ghost" onClick={closeSync}>Done</button>
        </div>
      </div>
    );
  }

  // phone sync modal — opens a LAN pairing server and shows the QR the
  // Android app scans; the QR carries host/port/one-time token
  async function openSync() {
    setSyncOpen(true);
    setSyncErr('');
    setSyncQr('');
    setSyncInfo(null);
    setSyncStats(null);
    setSyncSender('');
    setSyncPhase('starting');
    const bridge = syncBridge();
    if (!bridge) {
      setSyncPhase('error');
      setSyncErr('Phone sync needs the Awitize desktop app.');
      return;
    }
    try {
      const res = await bridge.start({ name: activeProfile?.name ?? 'Guest' });
      const payload = { aw: 'sync', v: 1, host: res.host, port: res.port, token: res.token, name: activeProfile?.name ?? 'Guest' };
      const qr = await QRCode.toDataURL(JSON.stringify(payload), {
        margin: 2, width: 280, errorCorrectionLevel: 'M',
        color: { dark: '#0b1020', light: '#ffffff' },
      });
      setSyncQr(qr);
      setSyncInfo({ host: res.host, port: res.port, ips: res.ips ?? [] });
      setSyncPhase('waiting');
    } catch (err) {
      setSyncPhase('error');
      setSyncErr(String((err as Error)?.message || err));
    }
  }

  function closeSync() {
    setSyncOpen(false);
    setSyncPhase('idle');
    setSyncQr('');
    const bridge = syncBridge();
    if (bridge) void bridge.stop().catch(() => {});
  }

  function loadProfile(pid: string) {
    const g = <T,>(k: string, fb: T): T => load(`aw:${pid}:${k}`, fb);
    setLikedIds(g<string[]>('liked', []));
    setPlaylists(g<Playlist[]>('playlists', []));
    setHistoryQ(g<string[]>('history', []));
    setPlays(g<PlayEvent[]>('plays', []));
    setQ(g<string>('q', ''));
    setView(onlineRef.current ? 'home' : 'library');
    setTab('all');
    setItems([]);
    setSections({});
    closeOverlays();
    setBanner('');
    setSearchFailed(false);
    setCurrent(null);
    setPlaying(false);
    setQueueOpen(false);
    setFocusOpen(false);
    setMenuFor(null);
    closeSync();
    lastCombo.current = '';
    lastBase.current = '';
    audioRef.current?.pause();
  }

  function selectProfile(pid: string) {
    setActivePid(pid);
    loadProfile(pid);
    setManaging(false);
    setScreen('app');
  }

  function startAddProfile() {
    setForm({ name: '', color: COLORS[profiles.length % COLORS.length] });
  }

  function startEditProfile(p: Profile) {
    setForm({ id: p.id, name: p.name, color: p.color, photo: p.photo });
  }

  function saveForm() {
    if (!form) return;
    const name = form.name.trim().slice(0, 16) || `Listener ${profiles.length + 1}`;
    if (form.id) {
      const next = profiles.map((p) => (p.id === form.id ? { ...p, name, color: form.color, photo: form.photo } : p));
      setProfiles(next);
      save('aw-profiles', next);
      toast(`Profile "${name}" updated.`);
    } else {
      const next: Profile[] = [...profiles, { id: crypto.randomUUID(), name, color: form.color, photo: form.photo }];
      setProfiles(next);
      save('aw-profiles', next);
      toast(`Profile "${name}" added.`);
    }
    setForm(null);
  }

  // profile photos are cover-cropped to 256px square JPEG before they ever
  // touch localStorage — a 4MB phone photo lands at ~20KB
  async function readProfilePhoto(f: File) {
    try {
      const url = URL.createObjectURL(f);
      const img = new Image();
      await new Promise((res, rej) => { img.onload = res; img.onerror = rej; img.src = url; });
      const S = 256;
      const c = document.createElement('canvas');
      c.width = S;
      c.height = S;
      const ctx = c.getContext('2d');
      if (!ctx) throw new Error('no canvas');
      const side = Math.min(img.width, img.height);
      ctx.drawImage(img, (img.width - side) / 2, (img.height - side) / 2, side, side, 0, 0, S, S);
      URL.revokeObjectURL(url);
      const photo = c.toDataURL('image/jpeg', 0.85);
      setForm((fm) => (fm ? { ...fm, photo } : fm));
    } catch {
      toast('Could not read that image — try a JPG or PNG.', 'err');
    }
  }

  function deleteProfile(id: string) {
    const next = profiles.filter((p) => p.id !== id);
    setProfiles(next);
    save('aw-profiles', next);
    const prefix = `aw:${id}:`;
    Object.keys(localStorage).filter((k) => k.startsWith(prefix)).forEach((k) => localStorage.removeItem(k));
    if (activePid === id) setActivePid(null);
  }

  const navPast = useRef<NavEntry[]>([]);
  const navFuture = useRef<NavEntry[]>([]);
  const [navDepth, setNavDepth] = useState({ back: 0, fwd: 0 });
  const overlayGen = useRef(0);

  // ---- persistence ----
  useEffect(() => {
    localStorage.setItem('aw-vol', String(vol));
    if (audioRef.current) audioRef.current.volume = muted ? 0 : vol;
  }, [vol, muted]);
  useEffect(() => { if (activePid) localStorage.setItem(`aw:${activePid}:q`, q); }, [q, activePid]);
  // theme: swap the CSS palette and remember the choice for next launch
  useEffect(() => { document.documentElement.dataset.theme = theme; save('aw-theme', theme); }, [theme]);
  useEffect(() => { if (activePid) save(`aw:${activePid}:liked`, likedIds); }, [likedIds, activePid]);
  useEffect(() => { save('aw-tracks', trackmap); }, [trackmap]);
  useEffect(() => { if (activePid) save(`aw:${activePid}:playlists`, playlists); }, [playlists, activePid]);
  useEffect(() => { if (activePid) save(`aw:${activePid}:history`, historyQ); }, [historyQ, activePid]);
  useEffect(() => { if (activePid) save(`aw:${activePid}:plays`, plays); }, [plays, activePid]);
  useEffect(() => { void refreshOffline(); }, []);
  // re-pull when the library opens so removals/saves from a previous session show up
  useEffect(() => { if (view === 'library') void refreshOffline(); }, [view]);

  // phone sync: answer the phone's snapshot/merge requests from live data and
  // reflect pairing progress in the modal (merge is add-only — see mergeIncoming)
  useEffect(() => {
    const bridge = syncBridge();
    if (!bridge) return;
    const offReq = bridge.onRequest((req) => {
      try {
        const lib = syncLibRef.current;
        if (req.kind === 'snapshot') {
          bridge.respond(req.reqId, { snapshot: buildSyncSnapshot(lib) });
          return;
        }
        if (req.kind === 'merge') {
          const incoming = (req.payload || {}) as Partial<SyncSnapshot>;
          const { next, stats } = mergeIncoming(lib, incoming);
          setLikedIds(next.likedIds);
          setTrackmap(next.trackmap);
          setPlaylists(next.playlists);
          setPlays(next.plays);
          setSyncStats(stats);
          setSyncSender(String(incoming.name || '').slice(0, 48));
          const who = String(incoming.name || '').trim();
          toast(`${who ? `${who}'s phone` : 'Phone'}: ${syncSummary(stats)}`);
          bridge.respond(req.reqId, { stats });
        }
      } catch {
        bridge.respond(req.reqId, {});
      }
    });
    const offEvt = bridge.onEvent((ev) => {
      if (ev.type === 'linked') setSyncPhase((p) => (p === 'waiting' ? 'linked' : p));
      else if (ev.type === 'pulled') setSyncPhase('linked');
      else if (ev.type === 'merged') {
        setSyncPhase('linked');
        if (ev.stats) setSyncStats(ev.stats);
      } else if (ev.type === 'expired') {
        setSyncPhase('error');
        setSyncErr('Pairing window expired. Open sync again for a fresh QR code.');
      }
    });
    return () => { offReq(); offEvt(); };
  }, []);

  // connectivity: OS online/offline events + a real reachability probe through
  // the local backend (which TCP-tests YouTube). If the backend itself is down
  // we fall back to the browser's own signal instead of pretending we're offline.
  useEffect(() => {
    let dead = false;
    const probe = async () => {
      const ctl = new AbortController();
      const t = window.setTimeout(() => ctl.abort(), 5000);
      try {
        const r = await fetch(`${API}/connectivity`, { signal: ctl.signal });
        const j = await r.json().catch(() => ({}));
        // a stale backend without /connectivity returns a non-boolean — trust the OS signal
        if (!dead) setOnline(typeof j.online === 'boolean' ? j.online : navigator.onLine);
      } catch {
        if (!dead) setOnline(navigator.onLine);
      } finally {
        window.clearTimeout(t);
      }
    };
    probeRef.current = () => void probe();
    const onUp = () => { void probe(); };
    const onDown = () => setOnline(false);
    window.addEventListener('online', onUp);
    window.addEventListener('offline', onDown);
    void probe();
    // while offline, poll fast so the UI flips back the moment the net returns
    const iv = window.setInterval(() => { if (!onlineRef.current) void probe(); }, 10000);
    return () => {
      dead = true;
      window.clearInterval(iv);
      window.removeEventListener('online', onUp);
      window.removeEventListener('offline', onDown);
    };
  }, []);

  // most-recent-first listening log — powers Quick picks, Listen again, etc.
  function notePlay(id: string) {
    setPlays((prev) => {
      const hit = prev.find((p) => p.id === id);
      const next = hit
        ? [{ ...hit, ts: Date.now(), n: hit.n + 1 }, ...prev.filter((p) => p.id !== id)]
        : [{ id, ts: Date.now(), n: 1 }, ...prev];
      return next.slice(0, 120);
    });
  }

  function rememberTrack(it: Item) {
    if (!it.videoId) return;
    setTrackmap((prev) => {
      if (prev[it.videoId!]?.artwork) return prev;
      const next = { ...prev, [it.videoId!]: it };
      const keys = Object.keys(next);
      if (keys.length > 300) delete next[keys[0]];
      return next;
    });
  }

  // ---- race-free search with paging ----
  async function runSearch(value: string, pushHist = true, force = false, filter = 'songs') {
    const key = value.trim();
    const lim = pageSizeRef.current;
    const combo = `${key.toLowerCase()}|${lim}|${filter}`;
    if (!key || (combo === lastCombo.current && !force)) return;
    const hit = cacheRef.current.get(combo);
    if (hit && Date.now() - hit.t < SEARCH_TTL && !force) {
      lastCombo.current = combo;
      lastQ.current = key;
      setItems(hit.items);
      setBanner(hit.items.length ? '' : 'No results for that query. Try another artist or song.');
      return;
    }
    const my = ++reqId.current;
    abortRef.current?.abort();
    const ctl = new AbortController();
    abortRef.current = ctl;
    setSearching(true);
    setBanner('');
    setSearchFailed(false);
    try {
      const r = await fetch(`${API}/search?q=${encodeURIComponent(key)}&limit=${lim}&filter=${filter}`, { signal: ctl.signal });
      const j = await r.json().catch(() => ({}));
      if (my !== reqId.current) return; // stale response — drop it
      if (!r.ok) throw new Error(typeof j?.detail === 'string' ? j.detail : j?.detail?.message || `Search failed (${r.status}).`);
      // songs/videos are playable via videoId; albums/artists open via browseId
      const keepAll = filter === 'albums' || filter === 'artists';
      const songs = ((j.items || []) as Item[]).filter((x) => (keepAll ? (x.videoId || x.browseId) : x.videoId));
      songs.forEach(rememberTrack);
      lastCombo.current = combo;
      lastQ.current = key;
      lastBase.current = `${filter}|${key.toLowerCase()}`;
      setExhausted(songs.length < lim);
      setItems(songs);
      cacheRef.current.set(combo, { t: Date.now(), items: songs });
      if (pushHist) {
        setHistoryQ((h) => [key, ...h.filter((x) => x !== key)].slice(0, 8));
      }
      setBanner(songs.length ? '' : 'No results for that query. Try another artist or song.');
    } catch (e) {
      if ((e as Error).name === 'AbortError' || my !== reqId.current) return;
      setSearchFailed(true);
      const msg = e instanceof Error ? e.message : 'Search failed.';
      setBanner(msg.includes('Failed to fetch') ? 'Backend offline — start services/music-api (python main.py).' : msg);
    } finally {
      if (my === reqId.current) setSearching(false);
    }
  }

  // YTM-style grouped search: songs + albums + videos + artists fanned out
  // in parallel by the backend — this is where the "many results" come from.
  async function fetchAll(value: string, force = false, pushHist = false) {
    const key = value.trim();
    if (!key) return;
    const combo = `all|${key.toLowerCase()}`;
    if (combo === lastCombo.current && !force) return;
    const my = ++reqId.current;
    abortRef.current?.abort();
    const ctl = new AbortController();
    abortRef.current = ctl;
    setSearching(true);
    setBanner('');
    setSearchFailed(false);
    try {
      const r = await fetch(`${API}/search/all?q=${encodeURIComponent(key)}`, { signal: ctl.signal });
      const j = await r.json().catch(() => ({}));
      if (my !== reqId.current) return;
      if (!r.ok) throw new Error('Grouped search failed. Retry in a bit.');
      const secs = (j.sections || {}) as Record<string, Item[]>;
      Object.values(secs).forEach((list) => (list || []).forEach(rememberTrack));
      lastCombo.current = combo;
      lastQ.current = key;
      lastBase.current = `all|${key.toLowerCase()}`;
      setSections(secs);
      const songs = (secs.songs || []).filter((x) => x.videoId);
      setItems(songs);
      setExhausted(true); // All is a preview — real paging lives under each tab
      if (pushHist) setHistoryQ((h) => [key, ...h.filter((x) => x !== key)].slice(0, 8));
      const empty = !songs.length && !Object.values(secs).some((s) => (s || []).length);
      setBanner(empty ? 'No results for that query. Try another artist or song.' : '');
    } catch (e) {
      if ((e as Error).name === 'AbortError' || my !== reqId.current) return;
      setSearchFailed(true);
      setBanner(e instanceof Error ? e.message : 'Search failed.');
    } finally {
      if (my === reqId.current) setSearching(false);
    }
  }

  function searchCurrent(force = false, tabUse = tab, pushHist = false) {
    const key = q.trim() || lastQ.current;
    if (!key) return;
    if (tabUse === 'all') fetchAll(key, force, pushHist);
    else runSearch(key, pushHist, force, TAB_FILTER[tabUse]);
  }

  function runQuery(hh: string) {
    if (!hh.trim()) return;
    navTo('search');
    setQ(hh);
    if (tab === 'all') fetchAll(hh, true, true);
    else runSearch(hh, true, true, TAB_FILTER[tab]);
  }

  function switchTab(t: Tab) {
    setTab(t);
    setBanner('');
    if (view !== 'search') return;
    const key = q.trim() || lastQ.current;
    if (!key) return;
    if (t === 'all') fetchAll(key, true);
    else runSearch(key, false, true, TAB_FILTER[t]);
  }

  async function openAlbum(it: Item) {
    if (!it.browseId) return;
    pushNav();
    commitDepth();
    setAlbumView(null);
    setArtistView(null);
    setDetailLoading(true);
    const gen = overlayGen.current;
    try {
      const a = await (await fetch(`${API}/album/${it.browseId}`)).json();
      if (overlayGen.current !== gen) return;
      if (a.tracks) {
        (a.tracks as Item[]).forEach(rememberTrack);
        setAlbumView({ ...a, browseId: it.browseId });
      } else throw new Error('empty');
    } catch { if (overlayGen.current === gen) setBanner('Could not open that album. Retry in a bit.'); }
    finally { setDetailLoading(false); }
  }

  async function openArtist(it: Item) {
    if (!it.browseId) return;
    pushNav();
    commitDepth();
    setAlbumView(null);
    setArtistView(null);
    setDetailLoading(true);
    const gen = overlayGen.current;
    try {
      const a = await (await fetch(`${API}/artist/${it.browseId}`)).json();
      if (overlayGen.current !== gen) return;
      if (a.name) {
        ((a.songs || []) as Item[]).forEach(rememberTrack);
        setArtistView({ ...a, browseId: it.browseId });
      } else throw new Error('empty');
    } catch { if (overlayGen.current === gen) setBanner('Could not open that artist. Retry in a bit.'); }
    finally { setDetailLoading(false); }
  }

  function retrySearch() {
    searchCurrent(true);
  }

  function loadMore() {
    if (tab === 'all') return; // All is a preview — page under the Songs tab
    const query = q.trim() || lastQ.current;
    if (!query) return;
    const next = Math.min(pageSizeRef.current + 25, 100);
    pageSizeRef.current = next;
    setPageSize(next);
    runSearch(query, false, true, TAB_FILTER[tab]);
  }

  // lyrics: (re)fetch whenever the panel is open for a track
  const lineRefs = useRef<(HTMLButtonElement | null)[]>([]);
  const lyrListRef = useRef<HTMLDivElement>(null);
  useEffect(() => {
    if (!lyricsOpen || !current?.videoId) { setLyrics(null); return; }
    if (!online) { setLyrics(null); setLyricsLoading(false); return; }
    let dead = false;
    setLyricsLoading(true);
    const params = new URLSearchParams({
      videoId: current.videoId, title: current.title || '',
      artists: (current.artists || []).join(', '), album: current.album || '',
      duration: String(Math.round(dur || 0)),
    });
    fetch(`${API}/lyrics/full?${params}`).then((r) => r.json()).then((j) => {
      if (!dead) setLyrics({ synced: !!j.synced, lines: j.lines || [] });
    }).catch(() => { if (!dead) setLyrics({ synced: false, lines: [] }); })
      .finally(() => { if (!dead) setLyricsLoading(false); });
    return () => { dead = true; };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [lyricsOpen, current?.videoId, online]);

  const activeIdx = (() => {
    if (!lyrics?.synced) return -1;
    let idx = 0;
    for (let i = 0; i < lyrics.lines.length; i++) {
      if (lyrics.lines[i].t <= pos + 0.15) idx = i; else break;
    }
    return idx;
  })();

  // zone-based auto-scroll: only moves when the active line leaves the
  // comfortable middle band — no constant recentering yanks, no dead gap
  useEffect(() => {
    if (!lyricsOpen || activeIdx < 0) return;
    const list = lyrListRef.current;
    const lineEl = lineRefs.current[activeIdx];
    if (!list || !lineEl) return;
    const lr = list.getBoundingClientRect();
    const rel = lineEl.getBoundingClientRect().top - lr.top;
    if (rel < lr.height * 0.22 || rel > lr.height * 0.68) {
      list.scrollTo({ top: list.scrollTop + rel - lr.height * 0.38, behavior: 'smooth' });
    }
  }, [activeIdx, lyricsOpen]);

  // 60fps karaoke fill straight from the audio clock — writes a CSS var on
  // the active line itself, so zero re-renders and exact sync after seeks
  useEffect(() => {
    if (!lyricsOpen || !lyrics?.synced || activeIdx < 0) return;
    let raf = 0;
    const tick = () => {
      const el = audioRef.current;
      const lineEl = lineRefs.current[activeIdx];
      const ln = lyrics.lines[activeIdx];
      if (el && lineEl && ln && !ln.words?.length) {
        const nextT = lyrics.lines[activeIdx + 1]?.t ?? el.duration ?? ln.t + 4;
        const pct = Math.min(100, Math.max(0, ((el.currentTime - ln.t) / Math.max(0.5, nextT - ln.t)) * 100));
        lineEl.style.setProperty('--fill', `${pct}%`);
      }
      raf = requestAnimationFrame(tick);
    };
    raf = requestAnimationFrame(tick);
    return () => cancelAnimationFrame(raf);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [lyricsOpen, activeIdx, lyrics]);

  function seekLyric(t: number) {
    if (audioRef.current) audioRef.current.currentTime = t;
  }

  // infinite scroll: when the grid bottom scrolls into view, pull the
  // next page automatically so results keep coming without hunting
  // for a button. Stops when the pool is exhausted or limit hits 100.
  useEffect(() => {
    if (view !== 'search' || !items.length || exhausted || pageSizeRef.current >= 100 || searching) return;
    const el = sentinelRef.current;
    if (!el) return;
    const obs = new IntersectionObserver((es) => {
      if (es.some((e) => e.isIntersecting)) loadMore();
    }, { rootMargin: '400px' });
    obs.observe(el);
    return () => obs.disconnect();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [view, items, exhausted, searching, pageSize]);

  // ---- navigation (all menu items work) ----
  function closeOverlays() {
    overlayGen.current++;
    setAlbumView(null);
    setArtistView(null);
    setMoodView(null);
    setDetailLoading(false);
    setMoodLoading(false);
  }

  function snapshot(): NavEntry {
    return { view, q, tab, album: albumView, artist: artistView, mood: moodView };
  }

  function commitDepth() {
    setNavDepth({ back: navPast.current.length, fwd: navFuture.current.length });
  }

  // Push the current place onto the back stack before moving somewhere new.
  function pushNav() {
    navPast.current.push(snapshot());
    if (navPast.current.length > 60) navPast.current.shift();
    navFuture.current = [];
  }

  function searchCombo(e: { q: string; tab: Tab }) {
    return `${e.q.trim().toLowerCase()}|${pageSizeRef.current}|${e.tab === 'all' ? 'all' : TAB_FILTER[e.tab]}`;
  }

  // Re-run the search only when the restored query/tab differs from what is
  // already on screen — otherwise the results are still there and untouched.
  function restoreSearch(e: NavEntry) {
    if (e.view !== 'search' || !e.q.trim() || searchCombo(e) === lastCombo.current) return;
    if (e.tab === 'all') fetchAll(e.q, true);
    else runSearch(e.q, false, true, TAB_FILTER[e.tab]);
  }

  function restore(e: NavEntry) {
    closeOverlays();
    setView(e.view);
    setQ(e.q);
    setTab(e.tab);
    setAlbumView(e.album);
    setArtistView(e.artist);
    setMoodView(e.mood);
    restoreSearch(e);
  }

  function navTo(v: View, query?: string) {
    if (!onlineRef.current && v !== 'library') { toast(OFFLINE_NAV_MSG, 'info'); return; }
    pushNav();
    commitDepth();
    closeOverlays();
    setView(v);
    if (query !== undefined) {
      setQ(query);
      if (v === 'search' && query.trim()) runSearch(query);
    }
  }
  function goBack() {
    const prev = navPast.current[navPast.current.length - 1];
    if (!prev) return;
    if (!onlineRef.current && prev.view !== 'library') { toast(OFFLINE_NAV_MSG, 'info'); return; }
    navPast.current.pop();
    navFuture.current.push(snapshot());
    restore(prev);
    commitDepth();
  }
  function goFwd() {
    const nxt = navFuture.current[navFuture.current.length - 1];
    if (!nxt) return;
    if (!onlineRef.current && nxt.view !== 'library') { toast(OFFLINE_NAV_MSG, 'info'); return; }
    navFuture.current.pop();
    navPast.current.push(snapshot());
    restore(nxt);
    commitDepth();
  }

  // keyboard back/forward (Alt+← / Alt+→) — routed through a ref so the
  // window listener always sees the latest state closures
  const navFnRef = useRef({ back: () => {}, fwd: () => {} });
  navFnRef.current = { back: goBack, fwd: goFwd };
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (!e.altKey) return;
      if (e.key === 'ArrowLeft') { e.preventDefault(); navFnRef.current.back(); }
      else if (e.key === 'ArrowRight') { e.preventDefault(); navFnRef.current.fwd(); }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, []);

  // ---- playback + prefetch ----
  // A YouTube media URL can be rejected mid-session (403/410) or closed early
  // by the CDN. Both look like "the song stopped", so every failure path first
  // re-resolves a brand-new URL and resumes from the same spot before it gives
  // up (and only then skips, so a queue never stalls forever).
  async function resolveStreamUrl(videoId: string, fresh: boolean) {
    const r = await fetch(`${API}/stream/${videoId}${fresh ? `?fresh=1&t=${Date.now()}` : ''}`);
    const j = await r.json().catch(() => ({}));
    if (!r.ok) throw new Error(j?.detail?.message || 'Could not play this track.');
    return j.url as string;
  }

  const recoverRef = useRef<{ id: string; tries: number }>({ id: '', tries: 0 });

  // Re-resolve the current track and resume where it left off. Returns true if
  // playback came back; false when we've exhausted the retries.
  async function recoverTrack(resumeAt?: number): Promise<boolean> {
    const el = audioRef.current;
    const item = current;
    if (!el || !item?.videoId) return false;
    const offlineHit = offlineRef.current[item.videoId] != null;
    const tries = recoverRef.current.id === item.videoId ? recoverRef.current.tries : 0;
    if (tries >= 2) return false;
    recoverRef.current = { id: item.videoId, tries: tries + 1 };
    const at = resumeAt ?? (Number.isFinite(el.currentTime) ? el.currentTime : 0);
    try {
      const url = offlineHit ? `${API}/offline/${item.videoId}` : await resolveStreamUrl(item.videoId, true);
      el.src = url;
      await new Promise<void>((res) => {
        const done = () => { el.removeEventListener('loadedmetadata', done); res(); };
        el.addEventListener('loadedmetadata', done);
        window.setTimeout(res, 4000);
      });
      try { if (at > 0.3) el.currentTime = at; } catch { /* seek best-effort */ }
      await el.play();
      setPlaying(true);
      setBanner(tries > 0 ? '' : 'Stream refreshed — resumed where it left off.');
      return true;
    } catch {
      return false;
    }
  }

  async function play(item: Item, list?: Item[]) {
    if (!item.videoId) return;
    if (!onlineRef.current && offlineRef.current[item.videoId] == null) {
      toast("You're offline — only downloaded tracks can play.", 'info');
      return;
    }
    if (list?.length) queueRef.current = list;
    rememberTrack(item);
    setCurrent(item);
    setResolvingId(item.videoId);
    setBanner('');
    recoverRef.current = { id: item.videoId, tries: 0 };
    try {
      const el = audioRef.current!;
      // a saved copy plays straight from disk — no resolver, no media-node
      // lottery, works with the network down
      const usedOffline = offlineRef.current[item.videoId] != null;
      if (usedOffline) {
        el.src = `${API}/offline/${item.videoId}`;
      } else {
        try {
          el.src = await resolveStreamUrl(item.videoId, false);
        } catch {
          // a cached URL can be stale — one immediate retry with a fresh one
          el.src = await resolveStreamUrl(item.videoId, true);
        }
      }
      await el.play();
      setPlaying(true);
      notePlay(item.videoId);
      if (stallRef.current) clearTimeout(stallRef.current);
      if (!usedOffline) stallRef.current = window.setTimeout(() => {
        const a = audioRef.current;
        if (a && a.currentTime < 0.2 && a.readyState < 3) {
          setBanner('Audio never started — your connection is likely blocking YouTube\'s media servers. Connect a VPN or switch network, then press play again.');
        }
      }, 10000);
      // warm the next track while this one plays — Next becomes instant
      const qi = queueRef.current.findIndex((x) => x.videoId === item.videoId);
      const nxt = queueRef.current[(qi + 1) % queueRef.current.length];
      if (onlineRef.current && nxt?.videoId && nxt.videoId !== item.videoId && offlineRef.current[nxt.videoId] == null) fetch(`${API}/stream/${nxt.videoId}`).catch(() => {});
    } catch (e) {
      setPlaying(false);
      if (e instanceof TypeError) probeRef.current(); // fetch failed — re-check connectivity
      setBanner(e instanceof Error ? e.message : 'Could not play this track.');
    } finally {
      setResolvingId(null);
    }
  }
  function step(dir: 1 | -1) {
    let list = queueRef.current.length ? queueRef.current : items;
    if (!onlineRef.current) list = list.filter((x) => x.videoId && offlineRef.current[x.videoId] != null);
    if (!list.length) return;
    if (!current) { play(list[0], list); return; }
    const i = list.findIndex((x) => x.videoId === current.videoId);
    const next = dir === 1
      ? list[shuffle ? Math.floor(Math.random() * list.length) : (i + 1) % list.length]
      : list[(i - 1 + list.length) % list.length];
    if (next) play(next, list);
  }
  function toggle() {
    const el = audioRef.current!;
    if (!el.src) {
      const list = queueRef.current.length ? queueRef.current : items;
      if (list[0]) play(list[0], list);
      return;
    }
    if (el.paused) { el.play(); setPlaying(true); } else { el.pause(); setPlaying(false); }
  }

  // ---- likes + playlists ----
  function toggleLike(it: Item) {
    if (!it.videoId) return;
    rememberTrack(it);
    const wasLiked = likedIds.includes(it.videoId);
    setLikedIds((prev) => prev.includes(it.videoId!) ? prev.filter((x) => x !== it.videoId) : [...prev, it.videoId!]);
    toast(wasLiked ? `Removed "${it.title}" from Liked Songs.` : `Added "${it.title}" to Liked Songs.`, wasLiked ? 'info' : 'ok');
  }
  function createPlaylist(name: string, first?: Item): Playlist | null {
    const n = name.trim();
    if (!n) return null;
    const pl = { id: crypto.randomUUID(), name: n, tracks: first?.videoId ? [first] : [] };
    setPlaylists((p) => [pl, ...p]);
    toast(first?.videoId ? `Created "${n}" — track added.` : `Playlist "${n}" created.`);
    return pl;
  }
  function addToPlaylist(id: string, it: Item) {
    if (!it.videoId) return;
    const pl = playlists.find((p) => p.id === id);
    if (!pl || pl.tracks.some((t) => t.videoId === it.videoId)) return; // menu row toggles to remove instead
    rememberTrack(it);
    setPlaylists((ps) => ps.map((p) => p.id === id ? { ...p, tracks: [...p.tracks, it] } : p));
    toast(`Added to "${pl.name}".`);
  }
  function removeFromPlaylist(id: string, videoId: string, label?: string) {
    const pl = playlists.find((p) => p.id === id);
    setPlaylists((ps) => ps.map((p) => p.id === id ? { ...p, tracks: p.tracks.filter((t) => t.videoId !== videoId) } : p));
    toast(`Removed${label ? ` "${label}"` : ' track'} from "${pl?.name ?? 'playlist'}".`, 'info');
  }
  function deletePlaylist(id: string) {
    const pl = playlists.find((p) => p.id === id);
    setPlaylists((ps) => ps.filter((p) => p.id !== id));
    if (pl) toast(`Playlist "${pl.name}" deleted.`, 'info');
  }

  // ---- offline downloads (served by the local backend from userData) ----
  async function refreshOffline() {
    try {
      const r = await fetch(`${API}/offline/list`);
      const j = await r.json().catch(() => ({}));
      const map: Record<string, number> = {};
      ((j.items || []) as { videoId: string; bytes: number }[]).forEach((x) => { map[x.videoId] = x.bytes; });
      setOffline(map);
    } catch { /* backend starting — keep the last known list */ }
  }

  async function saveOffline(it: Item, quiet = false): Promise<boolean> {
    if (!it.videoId) return false;
    if (!onlineRef.current) {
      if (!quiet) toast("You're offline — downloads need a connection.", 'info');
      return false;
    }
    if (offlineRef.current[it.videoId] != null) {
      if (!quiet) toast(`"${it.title}" is already saved offline.`, 'info');
      return true;
    }
    if (savingRef.current || (dlRef.current && !quiet)) {
      if (!quiet) toast('A download is already running — give it a moment.', 'info');
      return false;
    }
    savingRef.current = true;
    setSavingId(it.videoId);
    try {
      const r = await fetch(`${API}/offline/save/${it.videoId}`, { method: 'POST' });
      const j = await r.json().catch(() => ({}));
      if (!r.ok) throw new Error(j?.detail?.message || `Couldn't save "${it.title}" offline.`);
      rememberTrack(it);
      setOffline((o) => ({ ...o, [it.videoId!]: j.bytes || 0 }));
      if (!quiet) toast(`Saved "${it.title}" offline.`);
      return true;
    } catch (e) {
      if (!quiet) toast(e instanceof Error ? e.message : 'Download failed.', 'err');
      return false;
    } finally {
      savingRef.current = false;
      setSavingId(null);
    }
  }

  async function removeOffline(videoId: string, label?: string) {
    try {
      const r = await fetch(`${API}/offline/${videoId}`, { method: 'DELETE' });
      if (!r.ok) {
        const j = await r.json().catch(() => ({}));
        const msg = typeof j?.detail === 'string' ? j.detail : j?.detail?.message;
        throw new Error(msg || 'Could not remove the download.');
      }
      setOffline((o) => { const next = { ...o }; delete next[videoId]; return next; });
      toast(label ? `Removed the offline copy of "${label}".` : 'Offline copy removed.', 'info');
    } catch (e) {
      toast(e instanceof Error ? e.message : 'Could not remove the download.', 'err');
    }
  }

  async function downloadAll(key: string, tracks: Item[]) {
    if (!onlineRef.current) { toast("You're offline — downloads need a connection.", 'info'); return; }
    if (dlRef.current || savingRef.current) { toast('A download is already running — give it a moment.', 'info'); return; }
    const todo = tracks.filter((t) => t.videoId && offlineRef.current[t.videoId] == null);
    if (!todo.length) { toast('Everything here is already saved offline.', 'info'); return; }
    dlRef.current = true;
    setDl({ key, done: 0, total: todo.length });
    let ok = 0, fail = 0;
    try {
      for (const t of todo) {
        if (await saveOffline(t, true)) ok += 1; else fail += 1;
        setDl((d) => (d ? { ...d, done: d.done + 1 } : d));
      }
    } finally {
      dlRef.current = false;
      setDl(null);
    }
    if (!fail) toast(`Saved ${ok} track${ok === 1 ? '' : 's'} offline.`);
    else if (!ok) toast(`Couldn't save any of the ${todo.length} tracks — check your connection and retry.`, 'err');
    else toast(`Saved ${ok} of ${todo.length} tracks — ${fail} failed, retry those individually.`, 'info');
  }

  async function clearCaches() {
    cacheRef.current.clear();
    try { await fetch(`${API}/cache`, { method: 'DELETE' }); setApiHealth('Backend cache cleared.'); }
    catch { setApiHealth('Backend unreachable.'); }
  }

  // ---- effects ----
  useEffect(() => {
    if (view !== 'search') return;
    const t = setTimeout(() => { searchCurrent(false, tab, false); }, 700);
    return () => clearTimeout(t);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [q, view, tab]);

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      const tag = (e.target as HTMLElement)?.tagName;
      if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'k') {
        e.preventDefault();
        navTo('search');
        setTimeout(() => searchRef.current?.focus(), 0);
      } else if (e.code === 'Space' && tag !== 'INPUT') {
        e.preventDefault();
        toggle();
      }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [items, current, shuffle, view, q]);

  const likedTracks = likedIds.map((id) => trackmap[id]).filter(Boolean);
  const offlineTracks = Object.keys(offline).map((id) => trackmap[id]).filter((x): x is Item => !!x?.videoId);
  const offlineBytes = Object.values(offline as Record<string, number>).reduce((a, b) => a + b, 0);
  const queue = queueRef.current.length ? queueRef.current : items;
  const songsPreview = (sections.songs || []).filter((x) => x.videoId);
  const albumsPreview = sections.albums || [];
  const videosPreview = (sections.videos || []).filter((x) => x.videoId);
  const artistsPreview = sections.artists || [];
  const hasPreview = songsPreview.length + albumsPreview.length + videosPreview.length + artistsPreview.length > 0;
  function baseCombo() {
    const key = (q.trim() || lastQ.current).toLowerCase();
    return tab === 'all' ? `all|${key}` : `${TAB_FILTER[tab]}|${key}`;
  }
  const moreBlock = (
    <>
      {!!items.length && !exhausted && pageSize < 100 && (
        <div className="aw-more-wrap">
          <button className="aw-more" onClick={loadMore} disabled={searching}>
            {searching ? <LoaderCircle size={15} className="spin" /> : <Plus size={15} />}
            Show more
          </button>
        </div>
      )}
      {exhausted && !!items.length && tab !== 'all' && <div className="aw-end">That's everything for this search.</div>}
      <div ref={sentinelRef} className="aw-sentinel" />
    </>
  );

  function menuBody(it: Item, list: Item[]) {
    const isOffline = !!it.videoId && offline[it.videoId] != null;
    const isSaving = !!it.videoId && savingId === it.videoId;
    return (
      <>
        <button className="aw-menu-item" onClick={() => { setMenuFor(null); play(it, list); }}><Play size={15} />Play</button>
        <button className="aw-menu-item" onClick={() => { setMenuFor(null); toggleLike(it); }}>
          <Heart size={15} />{likedIds.includes(it.videoId!) ? 'Unlike' : 'Like'}
        </button>
        {!!it.videoId && (isOffline ? (
          <button className="aw-menu-item" onClick={() => removeOffline(it.videoId!, it.title)}><Trash2 size={15} />Remove download</button>
        ) : (
          <button className="aw-menu-item" disabled={isSaving} onClick={() => void saveOffline(it)}>
            {isSaving ? <LoaderCircle size={15} className="spin" /> : <Download size={15} />}
            {isSaving ? 'Saving for offline…' : 'Save offline'}
          </button>
        ))}
        <button className="aw-menu-item" onClick={() => { setMenuFor(null); if (it.videoId !== current?.videoId) play(it, list); setLyricsOpen(true); }}>
          <MicVocal size={15} />Lyrics
        </button>
        <div className="aw-menu-sep" />
        <div className="aw-menu-head"><ListPlus size={15} />Add to playlist</div>
        {playlists.map((p) => {
          const inList = !!it.videoId && p.tracks.some((t) => t.videoId === it.videoId);
          return (
            <button
              key={p.id}
              className={`aw-menu-item sub${inList ? ' in' : ''}`}
              onClick={() => { if (inList) removeFromPlaylist(p.id, it.videoId!, it.title); else addToPlaylist(p.id, it); }}
            >
              {inList ? <Check size={14} /> : <Plus size={14} />}
              <span className="aw-menu-name">{p.name}</span>
              {inList && <span className="aw-menu-pill">Added</span>}
            </button>
          );
        })}
        {newPlFor === it.videoId ? (
          <div className="aw-menu-new">
            <input autoFocus placeholder="Playlist name" value={newPlName} onChange={(e) => setNewPlName(e.target.value)}
              onKeyDown={(e) => { if (e.key === 'Enter' && createPlaylist(newPlName, it)) { setNewPlFor(null); setNewPlName(''); setMenuFor(null); } }} />
            <button className="aw-menu-item" onClick={() => { if (createPlaylist(newPlName, it)) { setNewPlFor(null); setNewPlName(''); setMenuFor(null); } }}><Plus size={15} />Create</button>
          </div>
        ) : (
          <button className="aw-menu-item" onClick={() => setNewPlFor(it.videoId!)}><Plus size={15} />New playlist</button>
        )}
      </>
    );
  }

  function openMenu(ctx: string, it: Item, btn?: HTMLElement | null) {
    const key = `${ctx}:${it.videoId}`;
    const opening = menuFor !== key;
    if (opening) {
      const row = btn?.closest('.aw-row');
      if (row) {
        const r = row.getBoundingClientRect();
        const below = window.innerHeight - 90 - r.bottom - 10;
        const above = r.top - 70 - 10;
        const up = below < 340 && above > below;
        setMenuPos({ up, maxH: Math.max(200, Math.floor(up ? above : below)) });
      } else {
        setMenuPos({ up: false, maxH: 420 });
      }
    }
    setMenuFor(opening ? key : null);
    setNewPlFor(null);
    setAvatarOpen(false);
  }

  function cardMenu(it: Item, list: Item[] | undefined, ctx: string) {
    if (menuFor !== `${ctx}:${it.videoId}`) return null;
    return <div className="aw-menu" onClick={(e) => e.stopPropagation()}>{menuBody(it, list ?? items)}</div>;
  }

  function rowMenu(it: Item, list: Item[], ctx: string) {
    if (menuFor !== `${ctx}:${it.videoId}`) return null;
    return <div className={`aw-menu row${menuPos.up ? ' up' : ''}`} style={{ maxHeight: menuPos.maxH }} onClick={(e) => e.stopPropagation()}>{menuBody(it, list)}</div>;
  }

  function albumCard(it: Item) {
    return (
      <div key={it.browseId || it.videoId} className="aw-card" onClick={() => openAlbum(it)}>
        {it.artwork ? <img className="aw-art" src={it.artwork} loading="lazy" alt="" /> : <div className="aw-art aw-art-ph"><Music2 size={30} /></div>}
        <div className="aw-title">{it.title}</div>
        <div className="aw-sub">{[...(it.artists || []), it.year].filter(Boolean).join(' • ')}</div>
      </div>
    );
  }

  function artistCard(it: Item) {
    return (
      <div key={it.browseId || it.videoId} className="aw-artist" onClick={() => openArtist(it)}>
        {it.artwork ? <img className="aw-artist-img" src={it.artwork} loading="lazy" alt="" /> : <div className="aw-artist-img aw-art-ph"><Music2 size={26} /></div>}
        <div className="aw-artist-name">{it.title}</div>
      </div>
    );
  }

  function searchEmpty() {
    return (
      <div className="aw-empty">
        <div className="aw-empty-ring"><Search size={30} /></div>
        <h3 className="aw-empty-title">Find your sound</h3>
        <p>Search millions of tracks on YouTube Music — they play here ad-free.</p>
        {!!historyQ.length && (
          <>
            <div className="aw-label" style={{ padding: '14px 0 0' }}>Recent</div>
            <div className="aw-chips center">
              {historyQ.slice(0, 5).map((h) => (
                <button key={h} className="aw-chip" onClick={() => runQuery(h)}><Clock3 size={14} />{h}</button>
              ))}
            </div>
          </>
        )}
        <div className="aw-label" style={{ padding: '14px 0 0' }}>Try</div>
        <div className="aw-chips center">
          {STARTERS.map((s) => (
            <button key={s} className="aw-chip" onClick={() => runQuery(s)}><TrendingUp size={14} />{s}</button>
          ))}
        </div>
        <div className="aw-empty-actions">
          <button className="aw-mini-btn ghost" onClick={() => navTo('liked')}><Heart size={13} />Liked Songs</button>
          <button className="aw-mini-btn ghost" onClick={() => navTo('library')}><LibraryBig size={13} />Your Library</button>
        </div>
      </div>
    );
  }

  const [explore, setExplore] = useState<{ new_releases: Item[]; moods: { title: string; params: string }[]; trending: Item[]; new_videos: Item[]; chart_artists: Item[] } | null>(null);
  const [exploreLoading, setExploreLoading] = useState(false);
  const [becauseArtist, setBecauseArtist] = useState('');
  const [becauseTracks, setBecauseTracks] = useState<Item[]>([]);
  const becauseCache = useRef(new Map<string, Item[]>());
  const [moodView, setMoodView] = useState<MoodView | null>(null);
  const [moodLoading, setMoodLoading] = useState(false);
  const newRef = useRef<HTMLDivElement>(null);
  const trendRef = useRef<HTMLDivElement>(null);
  const moodsRef = useRef<HTMLDivElement>(null);
  const vidsRef = useRef<HTMLDivElement>(null);
  const railNewRef = useRef<HTMLDivElement>(null);
  const railVidRef = useRef<HTMLDivElement>(null);
  const railListenRef = useRef<HTMLDivElement>(null);
  const railForgotRef = useRef<HTMLDivElement>(null);
  const railBecauseRef = useRef<HTMLDivElement>(null);

  async function fetchExplore(silent = false) {
    if (explore || exploreLoading) return;
    setExploreLoading(true);
    if (!silent) setBanner('');
    try {
      const j = await (await fetch(`${API}/explore`)).json();
      for (const list of [j.new_releases, j.trending, j.new_videos, j.chart_artists]) (list || []).forEach(rememberTrack);
      setExplore(j);
    } catch {
      if (!silent) setBanner('Explore is unreachable — check the backend and retry.');
    } finally {
      setExploreLoading(false);
    }
  }

  useEffect(() => {
    // home wants moods/trending for its shelves — fetch quietly so a dead
    // backend doesn't throw a search banner over the personalized page
    if ((view === 'explore' || view === 'home') && !explore && !exploreLoading) fetchExplore(view === 'home');
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [view]);

  // offline mode: stash where the user was, close every remote-backed overlay
  // and land on the Downloads-only Library; when the probe says we're back,
  // the stashed view is restored and the full UI returns.
  useEffect(() => {
    if (!online) {
      if (prevViewRef.current == null && view !== 'library') prevViewRef.current = view;
      setView('library');
      setAlbumView(null);
      setArtistView(null);
      setMoodView(null);
      setDetailLoading(false);
      setBanner('');
    } else if (prevViewRef.current) {
      const back = prevViewRef.current;
      prevViewRef.current = null;
      setView(back);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [online]);

  // strongest artist signal: plays weigh double, likes single — needs a real
  // signal (3 points) before we claim "because you liked X"
  function topArtistName() {
    const score = new Map<string, number>();
    const bump = (id: string, w: number) => {
      const a = trackmap[id]?.artists?.[0];
      if (a) score.set(a, (score.get(a) || 0) + w);
    };
    plays.forEach((p) => bump(p.id, 2));
    likedIds.forEach((id) => bump(id, 1));
    let best = '', bestN = 0;
    score.forEach((n, a) => { if (n > bestN) { bestN = n; best = a; } });
    return bestN >= 3 ? best : '';
  }

  // "Because you liked X" — artist songs resolved via search, cached per artist
  useEffect(() => {
    if (view !== 'home') return;
    const name = topArtistName();
    if (!name || name === becauseArtist) return;
    const hit = becauseCache.current.get(name);
    if (hit) { setBecauseArtist(name); setBecauseTracks(hit); return; }
    let dead = false;
    (async () => {
      try {
        const r = await fetch(`${API}/search?q=${encodeURIComponent(name)}&filter=songs&limit=14`);
        const j = await r.json().catch(() => ({}));
        const list = ((j.items || []) as Item[]).filter((x) => x.videoId);
        list.forEach(rememberTrack);
        becauseCache.current.set(name, list);
        if (!dead && list.length) { setBecauseArtist(name); setBecauseTracks(list); }
      } catch { /* the shelf stays hidden */ }
    })();
    return () => { dead = true; };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [view, plays, likedIds]);

  async function openMood(title: string, params: string) {
    pushNav();
    commitDepth();
    setMoodView(null);
    setMoodLoading(true);
    const gen = overlayGen.current;
    try {
      const j = await (await fetch(`${API}/mood?params=${encodeURIComponent(params)}`)).json();
      if (overlayGen.current !== gen) return;
      setMoodView({ title, playlists: j.playlists || [] });
    } catch { if (overlayGen.current === gen) setBanner('Could not open that mood. Retry in a bit.'); }
    finally { setMoodLoading(false); }
  }

  async function openPlaylist(it: Item) {
    const pid = it.playlistId || it.browseId;
    if (!pid) return;
    pushNav();
    commitDepth();
    setMoodView(null);
    setMoodLoading(false);
    setDetailLoading(true);
    const gen = overlayGen.current;
    try {
      const p = await (await fetch(`${API}/playlist/${pid}`)).json();
      if (overlayGen.current !== gen) return;
      const tracks = (p.tracks || []) as Item[];
      tracks.forEach(rememberTrack);
      setAlbumView({ browseId: pid, kind: 'playlist', title: p.title, artists: p.author ? [p.author] : [], artwork: p.artwork, tracks });
    } catch { if (overlayGen.current === gen) setBanner('Could not open that playlist. Retry in a bit.'); }
    finally { setDetailLoading(false); }
  }

  function scrollToRef(r: { current: HTMLDivElement | null }) {
    r.current?.scrollIntoView({ behavior: 'smooth', block: 'start' });
  }

  function railScroll(r: { current: HTMLDivElement | null }, dir: 1 | -1) {
    r.current?.scrollBy({ left: dir * 640, behavior: 'smooth' });
  }

  function rankRow(it: Item, i: number, list: Item[]) {
    return (
      <div key={it.videoId || it.browseId || i} className="aw-rank-row">
        <span className="aw-rank">{i + 1}</span>
        <div className="aw-rank-body">{trackRow(it, undefined, list, 'rank')}</div>
      </div>
    );
  }

  function videoCard(it: Item, list: Item[]) {
    return (
      <div key={it.videoId} className="aw-vidcard" onClick={() => play(it, list)}>
        <div className="aw-vid-art">
          {it.artwork && <img src={it.artwork} loading="lazy" alt="" />}
          <span className="aw-vid-play">{resolvingId === it.videoId ? <LoaderCircle size={22} className="spin" /> : <PlayGlyph size={22} />}</span>
        </div>
        <div className="aw-title">{it.title}</div>
        <div className="aw-sub">{[(it.artists || []).join(', '), it.views ? `${it.views} views` : ''].filter(Boolean).join(' • ')}</div>
      </div>
    );
  }

  function gateView() {
    return (
      <div className="aw-gate">
        <div className="aw-logo aw-gate-logo">
          <span className="aw-logo-mark"><AudioLines size={18} strokeWidth={2.5} /></span>
          Awitize
        </div>
        <h1 className="aw-gate-title">Who's listening?</h1>
        <div className="aw-tiles">
          {profiles.map((p) => (
            <div key={p.id} className="aw-tile" onClick={() => { if (!form) { if (managing) { setGateDel(null); startEditProfile(p); } else selectProfile(p.id); } }}>
              <div className="aw-tile-face" style={p.photo ? undefined : { background: p.color }}>
                {p.photo ? <img className="aw-tile-photo" src={p.photo} alt="" /> : <span>{p.name[0]?.toUpperCase()}</span>}
                {managing && (
                  <>
                    <button className="aw-tile-edit" aria-label={`edit ${p.name}`} title="Edit profile" onClick={(e) => { e.stopPropagation(); setGateDel(null); startEditProfile(p); }}><Pencil size={13} /></button>
                    {gateDel === p.id ? (
                      <button className="aw-tile-del arm" aria-label={`confirm delete ${p.name}`} title="Click again to permanently delete this profile and its data" onClick={(e) => { e.stopPropagation(); deleteProfile(p.id); setGateDel(null); }}><Check size={14} /></button>
                    ) : (
                      <button className="aw-tile-del" aria-label={`delete ${p.name}`} onClick={(e) => { e.stopPropagation(); setGateDel(p.id); }}><X size={14} /></button>
                    )}
                  </>
                )}
              </div>
              <div className="aw-tile-name">{gateDel === p.id ? 'Click ✓ to delete' : p.name}</div>
            </div>
          ))}
          {form ? (
            <div className="aw-tile">
              <div className="aw-tile-face form" style={form.photo ? undefined : { background: form.color }}>
                {form.photo
                  ? <img className="aw-tile-photo" src={form.photo} alt="" />
                  : <span>{form.name[0]?.toUpperCase() ?? '?'}</span>}
                <button className="aw-photo-btn" aria-label="choose photo" title={form.photo ? 'Change photo' : 'Add photo'} onClick={() => photoRef.current?.click()}><ImagePlus size={15} /></button>
              </div>
              <input
                className="aw-tile-input" autoFocus maxLength={16} placeholder="Name" value={form.name}
                onChange={(e) => setForm((f) => (f ? { ...f, name: e.target.value } : f))}
                onKeyDown={(e) => { if (e.key === 'Enter') saveForm(); if (e.key === 'Escape') setForm(null); }}
              />
              <div className="aw-dots">
                {COLORS.map((c) => (
                  <button key={c} className={`aw-dot${form.color === c ? ' sel' : ''}`} style={{ background: c }} onClick={() => setForm((f) => (f ? { ...f, color: c } : f))} aria-label={c} />
                ))}
                {form.photo && (
                  <button className="aw-dot aw-dot-photo" onClick={() => setForm((f) => (f ? { ...f, photo: undefined } : f))} aria-label="remove photo" title="Remove photo"><X size={11} /></button>
                )}
              </div>
              <div className="aw-tile-actions">
                <button className="aw-mini-btn" onClick={saveForm}>{form.id ? 'Save' : 'Add'}</button>
                <button className="aw-icon-btn" onClick={() => setForm(null)} aria-label="cancel"><X size={16} /></button>
              </div>
            </div>
          ) : (
            profiles.length < 6 && (
              <div className="aw-tile" onClick={startAddProfile}>
                <div className="aw-tile-face add"><Plus size={34} /></div>
                <div className="aw-tile-name">Add profile</div>
              </div>
            )
          )}
        </div>
        {profiles.length > 0 && (
          <button className="aw-gate-manage" onClick={() => { setManaging((m) => !m); setGateDel(null); setForm(null); }}>{managing ? 'Done' : 'Manage profiles'}</button>
        )}
        <input
          ref={photoRef} type="file" accept="image/*" style={{ display: 'none' }}
          onChange={(e) => { const f = e.target.files?.[0]; if (f) void readProfilePhoto(f); e.target.value = ''; }}
        />
      {syncView()}

      {toastView()}
      </div>
    );
  }

  function skeletonFor(t: Tab) {
    if (t === 'songs') {
      return (
        <div className="aw-rows">
          {Array.from({ length: 8 }).map((_, i) => (
            <div key={i} className="aw-row skel"><div className="aw-row-art aw-skel-block" /><div className="aw-skel-lines"><div className="aw-skel-line" /><div className="aw-skel-line short" /></div></div>
          ))}
        </div>
      );
    }
    if (t === 'artists') {
      return (
        <div className="aw-grid">
          {Array.from({ length: 8 }).map((_, i) => (
            <div key={i} className="aw-artist skel"><div className="aw-artist-img aw-skel-block" /><div className="aw-skel-line center" /></div>
          ))}
        </div>
      );
    }
    if (t === 'all') {
      return (
        <>
          <div className="aw-label" style={{ padding: '4px 0 8px' }}>Songs</div>
          <div className="aw-rows">
            {Array.from({ length: 4 }).map((_, i) => (
              <div key={i} className="aw-row skel"><div className="aw-row-art aw-skel-block" /><div className="aw-skel-lines"><div className="aw-skel-line" /><div className="aw-skel-line short" /></div></div>
            ))}
          </div>
          <div className="aw-label" style={{ padding: '18px 0 8px' }}>Loading more shelves…</div>
          <div className="aw-grid">
            {Array.from({ length: 4 }).map((_, i) => (
              <div key={i} className="aw-card aw-skel-card"><div className="aw-skel-art" /><div className="aw-skel-line" /></div>
            ))}
          </div>
        </>
      );
    }
    return (
      <div className="aw-grid">
        {Array.from({ length: 8 }).map((_, i) => (
          <div key={i} className="aw-card aw-skel-card"><div className="aw-skel-art" /><div className="aw-skel-line" /><div className="aw-skel-line short" /></div>
        ))}
      </div>
    );
  }

  // Tailored home — the YTM formula: greeting, mood chips, then shelves built
  // from real local signals (plays, likes) topped up with live YTM trending.
  function homeView() {
    const hr = new Date().getHours();
    const greet = hr < 12 ? 'Good morning' : hr < 18 ? 'Good afternoon' : 'Good evening';
    const recentPlayed = plays.map((p) => trackmap[p.id]).filter((x): x is Item => !!x?.videoId);
    const fresh = (explore?.trending || []).filter((x) => x.videoId);
    const hasSignal = recentPlayed.length > 0 || likedTracks.length > 0;
    const explorePending = !explore && exploreLoading;

    // Quick picks: round-robin recent plays → likes → trending, deduped.
    // 8 = exactly two tidy rows at the default window width (YTM's look).
    const quickPicks: Item[] = (() => {
      const seen = new Set<string>();
      const out: Item[] = [];
      const cols = [recentPlayed.slice(0, 8), likedTracks.slice(0, 8), fresh.slice(0, 8)];
      for (let i = 0; i < 8 && out.length < 8; i++) {
        for (const c of cols) {
          const t = c[i];
          if (t?.videoId && !seen.has(t.videoId)) { seen.add(t.videoId); out.push(t); }
          if (out.length >= 8) break;
        }
      }
      return out;
    })();

    const listenAgain = recentPlayed.slice(0, 10);
    const recentIds = new Set(plays.slice(0, 15).map((p) => p.id));
    const forgotten = likedTracks.filter((t) => t.videoId && !recentIds.has(t.videoId)).slice(0, 10);

    return (
      <>
        <div className="aw-hero">
          <h1 className="aw-hero-title">{greet}{activeProfile ? `, ${activeProfile.name}` : ''}</h1>
          <p className="aw-hero-sub">
            {hasSignal
              ? `${likedTracks.length} liked · ${plays.length} played — this page learns from both.`
              : 'Play and like anything — this page tailors itself to you.'}
          </p>
        </div>

        {(!!explore?.moods.length || explorePending) && (
          <div className="aw-chip-row" aria-label="Moods and genres">
            {explore
              ? explore.moods.slice(0, 14).map((m) => (
                  <button key={m.params} className="aw-chip" onClick={() => openMood(m.title, m.params)}>{m.title}</button>
                ))
              : Array.from({ length: 9 }).map((_, i) => <span key={i} className="aw-skel-chip" />)}
          </div>
        )}

        {quickPicks.length >= 4 && (
          <>
            <div className="aw-sec-row" style={{ paddingTop: 10 }}>
              <h2 className="aw-shelf-title">Quick picks</h2>
              <span className="aw-count">{hasSignal ? 'Fresh mix from your plays and likes' : 'Popular picks to start your rotation'}</span>
              <div style={{ flex: 1 }} />
              <button className="aw-mini-btn ghost" onClick={() => play(quickPicks[0], quickPicks)}><PlayGlyph size={12} />Play all</button>
            </div>
            <div className="aw-grid">{quickPicks.map((t) => trackCard(t, quickPicks, 'qp'))}</div>
          </>
        )}

        {listenAgain.length >= 3 && (
          <>
            <div className="aw-label">Listen again</div>
            <div className="aw-rail-wrap">
              <button className="aw-rail-btn" aria-label="scroll left" onClick={() => railScroll(railListenRef, -1)}><ChevronLeft size={18} /></button>
              <div className="aw-rail" ref={railListenRef}>{listenAgain.map((t) => trackCard(t, listenAgain, 'la'))}</div>
              <button className="aw-rail-btn" aria-label="scroll right" onClick={() => railScroll(railListenRef, 1)}><ChevronRight size={18} /></button>
            </div>
          </>
        )}

        {forgotten.length >= 4 && (
          <>
            <div className="aw-label">Forgotten favorites</div>
            <div className="aw-rail-wrap">
              <button className="aw-rail-btn" aria-label="scroll left" onClick={() => railScroll(railForgotRef, -1)}><ChevronLeft size={18} /></button>
              <div className="aw-rail" ref={railForgotRef}>{forgotten.map((t) => trackCard(t, forgotten, 'ff'))}</div>
              <button className="aw-rail-btn" aria-label="scroll right" onClick={() => railScroll(railForgotRef, 1)}><ChevronRight size={18} /></button>
            </div>
          </>
        )}

        {becauseTracks.length >= 4 && (
          <>
            <div className="aw-label">Because you liked {becauseArtist}</div>
            <div className="aw-rail-wrap">
              <button className="aw-rail-btn" aria-label="scroll left" onClick={() => railScroll(railBecauseRef, -1)}><ChevronLeft size={18} /></button>
              <div className="aw-rail" ref={railBecauseRef}>{becauseTracks.map((t) => trackCard(t, becauseTracks, 'bz'))}</div>
              <button className="aw-rail-btn" aria-label="scroll right" onClick={() => railScroll(railBecauseRef, 1)}><ChevronRight size={18} /></button>
            </div>
          </>
        )}

        {!hasSignal && !explorePending && !quickPicks.length && (
          <div className="aw-empty">
            <div className="aw-empty-ring"><Sparkles size={30} /></div>
            <h3 className="aw-empty-title">Nothing here yet — and that's fine</h3>
            <p>Play and like tracks and this page fills with quick picks, forgotten favorites, and shelves built from your taste.</p>
            <div className="aw-chips center">
              {STARTERS.slice(0, 4).map((s) => (
                <button key={s} className="aw-chip" onClick={() => runQuery(s)}><TrendingUp size={14} />{s}</button>
              ))}
            </div>
            <div className="aw-empty-actions">
              <button className="aw-mini-btn ghost" onClick={() => { navTo('search'); setTimeout(() => searchRef.current?.focus(), 0); }}><Search size={13} />Search songs</button>
              <button className="aw-mini-btn ghost" onClick={() => navTo('explore')}><Compass size={13} />Explore</button>
            </div>
          </div>
        )}

        {explore && (
          <>
            {!!explore.trending.length && (
              <>
                <div className="aw-label">Trending now</div>
                <div className="aw-rank-grid">{explore.trending.slice(0, 6).map((t, i, l) => rankRow(t, i, l))}</div>
              </>
            )}
            {!!explore.new_releases.length && (
              <>
                <div className="aw-label">New releases for you</div>
                <div className="aw-rail-wrap">
                  <button className="aw-rail-btn" aria-label="scroll left" onClick={() => railScroll(railNewRef, -1)}><ChevronLeft size={18} /></button>
                  <div className="aw-rail" ref={railNewRef}>{explore.new_releases.slice(0, 14).map(albumCard)}</div>
                  <button className="aw-rail-btn" aria-label="scroll right" onClick={() => railScroll(railNewRef, 1)}><ChevronRight size={18} /></button>
                </div>
              </>
            )}
          </>
        )}

        {!explore && !exploreLoading && (
          <div className="aw-label">Trending and moods need the backend — start services/music-api.</div>
        )}
      </>
    );
  }

  function trackCard(it: Item, list?: Item[], ctx = 'card') {
    const ql = list ?? items;
    const isCardCurrent = !!it.videoId && it.videoId === current?.videoId;
    return (
      <div key={it.videoId} className="aw-card" onClick={() => play(it, ql)}>
        {it.artwork ? <img className="aw-art" src={it.artwork} loading="lazy" alt="" /> : <div className="aw-art aw-art-ph"><Music2 size={30} /></div>}
        <div className="aw-title">{it.title}</div>
        <div className="aw-sub">{(it.artists || []).join(', ')}</div>
        <button
          className="aw-fab"
          aria-label={`${isCardCurrent && playing ? 'pause' : 'play'} ${it.title}`}
          onClick={(e) => { e.stopPropagation(); if (isCardCurrent) toggle(); else play(it, ql); }}
        >
          {resolvingId === it.videoId ? <LoaderCircle size={18} className="spin" /> : isCardCurrent && playing ? <Pause size={18} fill="currentColor" /> : <PlayGlyph size={18} />}
        </button>
        <button className="aw-cardmenu" aria-label="more" onClick={(e) => { e.stopPropagation(); openMenu(ctx, it, e.currentTarget); }}>
          <MoreHorizontal size={17} />
        </button>
        {cardMenu(it, list, ctx)}
      </div>
    );
  }

  function trackRow(it: Item, extra?: ReactNode, list?: Item[], ctx = 'main') {
    const liked = likedIds.includes(it.videoId!);
    const qList = list ?? (queueRef.current.length ? queueRef.current : items);
    const isCurrent = !!it.videoId && it.videoId === current?.videoId;
    return (
      <div key={it.videoId} className="aw-row" onClick={() => { if (isCurrent) toggle(); else play(it, qList); }}>
        {it.artwork ? <img src={it.artwork} width={44} height={44} className="aw-row-art" loading="lazy" alt="" /> : <div className="aw-row-art aw-row-ph"><Music2 size={18} /></div>}
        <div className="aw-row-meta">
          <div className="aw-row-title">{it.title}</div>
          <div className="aw-row-sub">{[(it.artists || []).join(', '), it.album, it.duration, it.views ? `${it.views} views` : ''].filter(Boolean).join(' • ')}</div>
        </div>
        {offline[it.videoId!] != null && <span className="aw-off-badge" title="Saved offline"><Download size={13} /></span>}
        {it.duration && <span className="aw-row-dur">{it.duration}</span>}
        <button className={`aw-icon-btn${liked ? ' liked' : ''}`} aria-label="like" onClick={(e) => { e.stopPropagation(); toggleLike(it); }}>
          <Heart size={16} fill={liked ? 'currentColor' : 'none'} />
        </button>
        {isCurrent ? (
          <button className="aw-icon-btn" aria-label={playing ? 'pause' : 'resume'} onClick={(e) => { e.stopPropagation(); toggle(); }}>
            <span className={`aw-eq${playing ? '' : ' paused'}`}><span /><span /><span /></span>
          </button>
        ) : resolvingId === it.videoId ? (
          <LoaderCircle size={16} className="spin" />
        ) : (
          <button className="aw-icon-btn" aria-label="play" onClick={(e) => { e.stopPropagation(); play(it, qList); }}><PlayGlyph size={16} /></button>
        )}
        <button className="aw-icon-btn" aria-label="more options" onClick={(e) => { e.stopPropagation(); openMenu(ctx, it, e.currentTarget); }}>
          <MoreHorizontal size={16} />
        </button>
        {rowMenu(it, qList, ctx)}
        {extra}
      </div>
    );
  }

  // Gate renders from here: every hook above runs on every render,
  // so hook order can never diverge between gate and app screens.
  if (screen === 'gate') return gateView();

  return (
    <ShellBoundary>
    <div className="aw-shell" onClick={() => { if (menuFor) setMenuFor(null); if (avatarOpen) setAvatarOpen(false); }}>
      <aside className="aw-side">
        <div className="aw-logo">
          <span className="aw-logo-mark"><AudioLines size={18} strokeWidth={2.5} /></span>
          Awitize
        </div>
        <button className={`aw-nav${view === 'home' ? ' active' : ''}${online ? '' : ' locked'}`} onClick={() => navTo('home')}><Home size={18} />Home</button>
        <button className={`aw-nav${view === 'explore' ? ' active' : ''}${online ? '' : ' locked'}`} onClick={() => navTo('explore')}><Compass size={18} />Explore</button>
        <button className={`aw-nav${view === 'search' ? ' active' : ''}${online ? '' : ' locked'}`} onClick={() => { navTo('search'); setTimeout(() => searchRef.current?.focus(), 0); }}><Search size={18} />Search</button>
        <button className={`aw-nav${view === 'library' ? ' active' : ''}`} onClick={() => navTo('library')}><LibraryBig size={18} />Your Library</button>
        {online && (
          <>
            <div className="aw-label">Playlists</div>
            <button className={`aw-nav${view === 'liked' ? ' active' : ''}`} onClick={() => navTo('liked')}><Heart size={18} />Liked Songs</button>
            {playlists.map((p) => (
              <button key={p.id} className="aw-nav aw-pl" onClick={() => navTo('library')}><ListMusic size={18} />{p.name}</button>
            ))}
          </>
        )}
        <div style={{ flex: 1 }} />
        <button className="aw-sync-entry" onClick={() => void openSync()} title="Pair this PC with the Awitize phone app">
          <Smartphone size={16} />
          <span>Sync with phone</span>
        </button>
        <button className="aw-user" onClick={() => setScreen('gate')} title="Switch profile">
          <span className="aw-user-face" style={activeProfile && !activeProfile.photo ? { background: activeProfile.color } : undefined}>
            {activeProfile?.photo ? <img className="aw-face-img" src={activeProfile.photo} alt="" /> : activeProfile?.name[0]?.toUpperCase() ?? 'G'}
          </span>
          <div><div className="aw-user-name">{activeProfile?.name ?? 'Guest'}</div><div className="aw-user-sub">Switch profile</div></div>
          <ChevronsUpDown size={16} className="aw-user-chev" />
        </button>
      </aside>

      <main className="aw-main">
        <div className="aw-head-stick">
        <div className="aw-top">
          <button className="aw-topbtn" aria-label="back" title="Back (Alt+←)" disabled={!navDepth.back && !albumView && !artistView && !moodView} onClick={goBack}><ChevronLeft size={18} /></button>
          <button className="aw-topbtn" aria-label="forward" title="Forward (Alt+→)" disabled={!navDepth.fwd} onClick={goFwd}><ChevronRight size={18} /></button>
          <div className="aw-search-wrap">
            <Search size={16} className="aw-search-icon" />
            <input
              ref={searchRef}
              className="aw-search"
              placeholder={online ? 'Search songs, artists — Ctrl+K' : 'Offline — play from your downloads'}
              value={q}
              disabled={!online}
              onChange={(e) => { setQ(e.target.value); if (view !== 'search') navTo('search'); }}
              onKeyDown={(e) => {
                if (e.key === 'Enter' && q.trim()) {
                  navTo('search');
                  searchCurrent(true, tab, true);
                  setQ('');
                }
              }}
            />
          </div>
          <div style={{ flex: 1 }} />
          {!online && <span className="aw-off-chip" title="You're offline — downloads only"><CloudOff size={13} />Offline</span>}
          <div className="aw-ava-wrap">
            <button
              className="aw-avatar"
              style={activeProfile && !activeProfile.photo ? { background: activeProfile.color } : undefined}
              onClick={(e) => { e.stopPropagation(); setAvatarOpen((a) => !a); setMenuDel(false); setMenuFor(null); }}
            >
              {activeProfile?.photo ? <img className="aw-face-img" src={activeProfile.photo} alt="" /> : activeProfile?.name[0]?.toUpperCase() ?? 'G'}
            </button>
            {avatarOpen && (
              <div className="aw-menu aw-ava-menu" onClick={(e) => e.stopPropagation()}>
                <div className="aw-menu-note">{activeProfile ? `Listening as ${activeProfile.name}` : 'No profile'}</div>
                <button className="aw-menu-item" onClick={() => { setAvatarOpen(false); setScreen('gate'); }}><CircleUserRound size={15} />Switch profile</button>
                {activeProfile && (menuDel ? (
                  <button className="aw-menu-item danger" onClick={() => {
                    const name = activeProfile.name;
                    deleteProfile(activeProfile.id);
                    setMenuDel(false);
                    setAvatarOpen(false);
                    setScreen('gate');
                    toast(`Profile "${name}" and its data were deleted.`, 'info');
                  }}><TriangleAlert size={15} />Really delete "{activeProfile.name}"?</button>
                ) : (
                  <button className="aw-menu-item" onClick={() => setMenuDel(true)}><UserX size={15} />Delete profile</button>
                ))}
                <div className="aw-menu-sep" />
                <button className="aw-menu-item" onClick={() => { setAvatarOpen(false); void openSync(); }}><Smartphone size={15} />Sync with phone</button>
                <button className="aw-menu-item" onClick={async () => {
                  try { const h = await (await fetch(`${API}/health`)).json(); setApiHealth(h.ok ? 'Backend healthy.' : 'Backend odd.'); }
                  catch { setApiHealth('Backend unreachable.'); }
                }}><AudioLines size={15} />Check backend</button>
                <button className="aw-menu-item" onClick={clearCaches}><Trash2 size={15} />Clear caches</button>
                <div className="aw-menu-sep" />
                <div className="aw-menu-head"><Palette size={14} />Theme</div>
                {THEMES.map((t) => (
                  <button
                    key={t.id}
                    className={`aw-menu-item aw-menu-theme${theme === t.id ? ' in' : ''}`}
                    onClick={() => { setTheme(t.id); toast(`${t.name} theme applied.`); }}
                  >
                    <span className="aw-swatch" style={{ background: t.swatch }} />
                    <span className="aw-menu-name">{t.name}</span>
                    {theme === t.id && <Check className="aw-check" size={15} />}
                  </button>
                ))}
                <div className="aw-menu-sep" />
                <div className="aw-menu-note">{apiHealth || 'Awitize 0.3.0 — local build'}</div>
              </div>
            )}
          </div>
        </div>
        </div>

        {view === 'home' && homeView()}

        {view === 'search' && (
          <>
            <div className="aw-sec-stick">
              <div className="aw-sec-row">
                <h2 className="aw-sec-title">{lastQ.current ? `Results for "${lastQ.current}"` : 'Search to start'}</h2>
                {tab !== 'all' && !!items.length && <span className="aw-count">Showing {items.length} tracks{!exhausted && pageSize < 100 ? ' — scroll for more' : ''}</span>}
              </div>
              <div className="aw-tabs" role="tablist">
                {(['all', 'songs', 'albums', 'videos', 'artists'] as Tab[]).map((t) => (
                  <button key={t} role="tab" aria-selected={tab === t} className={`aw-pill${tab === t ? ' active' : ''}`} onClick={() => switchTab(t)}>
                    {t === 'all' ? 'All' : t[0].toUpperCase() + t.slice(1)}
                  </button>
                ))}
              </div>
            </div>
            {banner && (
              <div className="aw-banner" role="alert">
                <TriangleAlert size={16} /><span>{banner}</span>
                {searchFailed && <button className="aw-banner-retry" onClick={retrySearch}>Retry</button>}
                <button className="aw-banner-x" onClick={() => setBanner('')} aria-label="dismiss"><X size={14} /></button>
              </div>
            )}
            {searching && lastBase.current !== baseCombo() ? (
              skeletonFor(tab)
            ) : tab === 'all' ? (
              <>
                {!!songsPreview.length && (
                  <>
                    <div className="aw-label" style={{ padding: '4px 0 8px' }}>Songs</div>
                    <div className="aw-rows">{songsPreview.map((t) => trackRow(t, undefined, undefined, 'sp'))}</div>
                  </>
                )}
                {!!albumsPreview.length && (
                  <>
                    <div className="aw-label" style={{ padding: '18px 0 8px' }}>Albums</div>
                    <div className="aw-grid">{albumsPreview.map(albumCard)}</div>
                  </>
                )}
                {!!videosPreview.length && (
                  <>
                    <div className="aw-label" style={{ padding: '18px 0 8px' }}>Videos</div>
                    <div className="aw-grid">{videosPreview.map((t, _i, l) => trackCard(t, l, 'vp'))}</div>
                  </>
                )}
                {!!artistsPreview.length && (
                  <>
                    <div className="aw-label" style={{ padding: '18px 0 8px' }}>Artists</div>
                    <div className="aw-grid">{artistsPreview.map(artistCard)}</div>
                  </>
                )}
                {!hasPreview && !banner && searchEmpty()}
              </>
            ) : tab === 'songs' ? (
              <>
                {items.length ? <div className="aw-rows">{items.map((t) => trackRow(t, undefined, undefined, 'sr'))}</div>
                  : !banner && (<div className="aw-empty"><Music2 size={28} /><p>No songs in this shelf — try another tab.</p><div className="aw-empty-actions"><button className="aw-mini-btn ghost" onClick={() => switchTab('all')}>Back to All</button></div></div>)}
                {moreBlock}
              </>
            ) : tab === 'videos' ? (
              <>
                {items.length ? <div className="aw-grid">{items.map((t, _i, l) => trackCard(t, l, 'sv'))}</div>
                  : !banner && (<div className="aw-empty"><Music2 size={28} /><p>No videos here — try another tab.</p><div className="aw-empty-actions"><button className="aw-mini-btn ghost" onClick={() => switchTab('all')}>Back to All</button></div></div>)}
                {moreBlock}
              </>
            ) : tab === 'albums' ? (
              <>
                {items.length ? <div className="aw-grid">{items.map(albumCard)}</div>
                  : !banner && (<div className="aw-empty"><Music2 size={28} /><p>No albums here — try another tab.</p><div className="aw-empty-actions"><button className="aw-mini-btn ghost" onClick={() => switchTab('all')}>Back to All</button></div></div>)}
                {moreBlock}
              </>
            ) : (
              <>
                {items.length ? <div className="aw-grid">{items.map(artistCard)}</div>
                  : !banner && (<div className="aw-empty"><Music2 size={28} /><p>No artists here — try another tab.</p><div className="aw-empty-actions"><button className="aw-mini-btn ghost" onClick={() => switchTab('all')}>Back to All</button></div></div>)}
                {moreBlock}
              </>
            )}
          </>
        )}

        {view === 'explore' && (
          <>
            <div className="aw-sec-row">
              <h2 className="aw-sec-title">Explore</h2>
              <span className="aw-count">New releases, trending and moods</span>
            </div>
            {!explore && banner && (
              <div className="aw-banner" role="alert">
                <TriangleAlert size={16} /><span>{banner}</span>
                <button className="aw-banner-x" onClick={() => setBanner('')} aria-label="dismiss"><X size={14} /></button>
              </div>
            )}
            {exploreLoading && !explore ? (
              <div className="aw-grid">
                {Array.from({ length: 8 }).map((_, i) => (
                  <div key={i} className="aw-card aw-skel-card"><div className="aw-skel-art" /><div className="aw-skel-line" /><div className="aw-skel-line short" /></div>
                ))}
              </div>
            ) : explore ? (
              <>
                <div className="aw-trio">
                  <button className="aw-trio-card" onClick={() => scrollToRef(newRef)}><Disc3 size={22} />New releases</button>
                  <button className="aw-trio-card" onClick={() => scrollToRef(trendRef)}><TrendingUp size={22} />Trending</button>
                  <button className="aw-trio-card" onClick={() => scrollToRef(moodsRef)}><Smile size={22} />Moods &amp; genres</button>
                </div>
                <div ref={newRef} className="aw-label" style={{ padding: '18px 0 8px' }}>New albums &amp; singles</div>
                <div className="aw-rail-wrap">
                  <button className="aw-rail-btn" aria-label="scroll left" onClick={() => railScroll(railNewRef, -1)}><ChevronLeft size={18} /></button>
                  <div className="aw-rail" ref={railNewRef}>{explore.new_releases.map(albumCard)}</div>
                  <button className="aw-rail-btn" aria-label="scroll right" onClick={() => railScroll(railNewRef, 1)}><ChevronRight size={18} /></button>
                </div>
                <div ref={trendRef} className="aw-label" style={{ padding: '18px 0 8px' }}>Trending now</div>
                <div className="aw-rank-grid">{explore.trending.map((t, i) => rankRow(t, i, explore.trending))}</div>
                {!!explore.chart_artists.length && (
                  <>
                    <div className="aw-label" style={{ padding: '18px 0 8px' }}>Top artists</div>
                    <div className="aw-rail">{explore.chart_artists.map(artistCard)}</div>
                  </>
                )}
                <div ref={moodsRef} className="aw-label" style={{ padding: '18px 0 8px' }}>Moods &amp; genres</div>
                <div className="aw-mood-grid">
                  {explore.moods.map((m, i) => (
                    <button key={m.params} className="aw-mood" style={{ borderLeftColor: MOODS[i % MOODS.length] }} onClick={() => openMood(m.title, m.params)}>{m.title}</button>
                  ))}
                </div>
                <div ref={vidsRef} className="aw-label" style={{ padding: '18px 0 8px' }}>New music videos</div>
                <div className="aw-rail-wrap">
                  <button className="aw-rail-btn" aria-label="scroll left" onClick={() => railScroll(railVidRef, -1)}><ChevronLeft size={18} /></button>
                  <div className="aw-rail" ref={railVidRef}>{explore.new_videos.map((v) => videoCard(v, explore.new_videos))}</div>
                  <button className="aw-rail-btn" aria-label="scroll right" onClick={() => railScroll(railVidRef, 1)}><ChevronRight size={18} /></button>
                </div>
              </>
            ) : !banner && (
              <div className="aw-empty"><Compass size={28} /><p>Explore needs the backend — start services/music-api.</p></div>
            )}
          </>
        )}

        {view === 'liked' && (
          <>
            <div className="aw-sec-row"><h2 className="aw-sec-title">Liked Songs</h2><span className="aw-count">{likedTracks.length} tracks</span></div>
            {likedTracks.length ? <div className="aw-rows">{likedTracks.map((t) => trackRow(t, undefined, undefined, 'lk'))}</div>
              : <div className="aw-empty"><Heart size={28} /><p>Nothing liked yet — hover any card for the menu.</p></div>}
          </>
        )}

        {view === 'library' && (
          <>
            {!online && (
              <div className="aw-off-hero">
                <CloudOff size={22} />
                <div>
                  <div className="aw-off-hero-title">You're offline — downloads only</div>
                  <div className="aw-off-hero-sub">The full library and search come back the moment you're online again.</div>
                </div>
              </div>
            )}
            <div className="aw-sec-row">
              <h2 className="aw-sec-title">Your Library</h2>
              <span className="aw-count">{online ? `${playlists.length} playlists` : `${offlineTracks.length} downloaded track${offlineTracks.length === 1 ? '' : 's'}`}</span>
            </div>
            {!!offlineTracks.length && (
              <div className="aw-pl-block">
                <div className="aw-pl-head">
                  <span className="aw-pl-name">Downloads</span>
                  <span className="aw-count">{offlineTracks.length} track{offlineTracks.length === 1 ? '' : 's'}{offlineBytes ? ` · ${fmtBytes(offlineBytes)}` : ''} — stored on this device</span>
                  <div style={{ flex: 1 }} />
                  <button className="aw-mini-btn" onClick={() => play(offlineTracks[0], offlineTracks)}><PlayGlyph size={13} />Play</button>
                </div>
                <div className="aw-rows">
                  {offlineTracks.map((t) => trackRow(t, undefined, offlineTracks, 'dl'))}
                </div>
              </div>
            )}
            {!online && !offlineTracks.length && (
              <div className="aw-empty">
                <CloudOff size={28} />
                <p>Nothing downloaded yet. While online, open any track's ••• menu and pick "Save offline" — downloads play here without a connection.</p>
              </div>
            )}
            {online && !playlists.length && (
              <div className="aw-empty"><ListMusic size={28} /><p>No playlists yet — use the ••• menu on any card to create one, then Download the whole list for offline.</p></div>
            )}
            {online && playlists.map((p) => (
              <div key={p.id} className="aw-pl-block">
                <div className="aw-pl-head">
                  <span className="aw-pl-name">{p.name}</span>
                  <span className="aw-count">{p.tracks.length} tracks</span>
                  <div style={{ flex: 1 }} />
                  {!!p.tracks.length && (
                    <button className="aw-mini-btn ghost" disabled={dl?.key === `pl:${p.id}` ? false : (!!dl || savingId != null)} onClick={() => void downloadAll(`pl:${p.id}`, p.tracks)}>
                      {dl?.key === `pl:${p.id}`
                        ? <><LoaderCircle size={13} className="spin" />Saving {Math.min(dl.done + 1, dl.total)}/{dl.total}</>
                        : <><Download size={13} />Download</>}
                    </button>
                  )}
                  {p.tracks[0] && <button className="aw-mini-btn" onClick={() => play(p.tracks[0], p.tracks)}><PlayGlyph size={13} />Play</button>}
                  <button className="aw-icon-btn" aria-label={`delete ${p.name}`} onClick={() => deletePlaylist(p.id)}><Trash2 size={15} /></button>
                </div>
                <div className="aw-rows">
                  {p.tracks.map((t) => trackRow(t,
                    <button key={`rm-${t.videoId}`} className="aw-icon-btn" aria-label="remove" onClick={(e) => { e.stopPropagation(); removeFromPlaylist(p.id, t.videoId!, t.title); }}><X size={15} /></button>, undefined, `pl:${p.id}`))}
                </div>
              </div>
            ))}
          </>
        )}
      </main>

      {(albumView || artistView || detailLoading) && (
        <div className="aw-overlay">
          <button className="aw-icon-btn aw-focus-x" aria-label="close details" onClick={() => goBack()}><X size={20} /></button>
          {detailLoading || (!albumView && !artistView) ? (
            <div className="aw-grid">
              {Array.from({ length: 4 }).map((_, i) => (
                <div key={i} className="aw-card aw-skel-card"><div className="aw-skel-art" /><div className="aw-skel-line" /></div>
              ))}
            </div>
          ) : albumView ? (
            <>
              <div className="aw-det-head">
                {albumView.artwork && <img src={albumView.artwork} className="aw-det-art" alt="" />}
                <div>
                  <div className="aw-det-kicker">{albumView.kind === 'playlist' ? 'Playlist' : 'Album'}{(albumView.year ? ` • ${albumView.year}` : '')}</div>
                  <div className="aw-det-title">{albumView.title}</div>
                  <div className="aw-det-sub">{(albumView.artists || []).join(', ')}</div>
                  {albumView.tracks[0] && (
                    <div className="aw-det-actions">
                      <button className="aw-mini-btn" onClick={() => play(albumView.tracks[0], albumView.tracks)}><PlayGlyph size={13} />Play</button>
                      <button className="aw-mini-btn ghost" disabled={dl?.key === `av:${albumView.browseId}` ? false : (!!dl || savingId != null)} onClick={() => void downloadAll(`av:${albumView.browseId}`, albumView.tracks)}>
                        {dl?.key === `av:${albumView.browseId}`
                          ? <><LoaderCircle size={13} className="spin" />Saving {Math.min(dl.done + 1, dl.total)}/{dl.total}</>
                          : <><Download size={13} />Download all</>}
                      </button>
                    </div>
                  )}
                </div>
              </div>
              <div className="aw-rows">{albumView.tracks.map((t) => trackRow(t, undefined, albumView.tracks, 'al'))}</div>
            </>
          ) : artistView ? (
            <>
              <div className="aw-det-head">
                {artistView.artwork && <img src={artistView.artwork} className="aw-det-art round" alt="" />}
                <div>
                  <div className="aw-det-kicker">Artist</div>
                  <div className="aw-det-title">{artistView.name}</div>
                  <div className="aw-det-sub">{artistView.songs.length} top songs • {artistView.albums.length} albums</div>
                  {artistView.songs[0] && <button className="aw-mini-btn" onClick={() => play(artistView.songs[0], artistView.songs)}><PlayGlyph size={13} />Play</button>}
                </div>
              </div>
              <div className="aw-label" style={{ padding: '18px 0 8px' }}>Top songs</div>
              <div className="aw-rows">{artistView.songs.filter((t) => t.videoId).map((t) => trackRow(t, undefined, artistView.songs, 'ar'))}</div>
              {!!artistView.albums.length && (
                <>
                  <div className="aw-label" style={{ padding: '18px 0 8px' }}>Albums</div>
                  <div className="aw-grid">{artistView.albums.map(albumCard)}</div>
                </>
              )}
            </>
          ) : null}
        </div>
      )}

      {lyricsOpen && (
        <div className="aw-lyr">
          {current?.artwork && <img src={current.artwork} className="aw-lyr-bg" alt="" />}
          <div className="aw-lyr-shade" />
          <div className="aw-lyr-head">
            <div>
              <div className="aw-det-kicker">Lyrics</div>
              <div className="aw-lyr-track">{current ? `${current.title} • ${(current.artists || []).join(', ')}` : 'Nothing playing'}</div>
            </div>
            <div style={{ flex: 1 }} />
            <button className="aw-icon-btn" aria-label="close lyrics" onClick={() => setLyricsOpen(false)}><X size={20} /></button>
          </div>
          <div className="aw-lyr-list" ref={lyrListRef}>
            {!online ? (
              <div className="aw-empty"><CloudOff size={28} /><p>Lyrics need a connection — they come back when you're online.</p></div>
            ) : lyricsLoading ? (
              Array.from({ length: 6 }).map((_, i) => <div key={i} className="aw-skel-line" style={{ height: 20, width: `${85 - i * 7}%` }} />)
            ) : lyrics?.lines.length ? (
              lyrics.lines.map((ln, i) => {
                const isActive = lyrics.synced && i === activeIdx;
                return (
                <button
                  key={i}
                  ref={(el) => { lineRefs.current[i] = el; }}
                  className={`aw-lyr-line${isActive ? ' active' : ''}${lyrics.synced && i < activeIdx ? ' past' : ''}`}
                  onClick={() => seekLyric(ln.t)}
                >
                  {ln.words?.length && lyrics.synced ? (
                    ln.words.map((w, wi) => (
                      <span key={wi} className={w.t <= pos + 0.15 ? 'aw-w-on' : 'aw-w-off'}>{w.text}</span>
                    ))
                  ) : ln.text}
                </button>
                );
              })
            ) : (
              <div className="aw-empty"><Music2 size={28} /><p>No lyrics found for this track yet.</p></div>
            )}
          </div>
        </div>
      )}

      {(moodView || moodLoading) && (
        <div className="aw-overlay">
          <button className="aw-icon-btn aw-focus-x" aria-label="close" onClick={() => goBack()}><X size={20} /></button>
          {moodLoading || !moodView ? (
            <div className="aw-grid">
              {Array.from({ length: 4 }).map((_, i) => (
                <div key={i} className="aw-card aw-skel-card"><div className="aw-skel-art" /><div className="aw-skel-line" /></div>
              ))}
            </div>
          ) : (
            <>
              <div className="aw-det-head">
                <div>
                  <div className="aw-det-kicker">Mood &amp; genre</div>
                  <div className="aw-det-title">{moodView.title}</div>
                  <div className="aw-det-sub">{moodView.playlists.length} playlists</div>
                </div>
              </div>
              <div className="aw-grid">
                {moodView.playlists.map((p) => (
                  <div key={p.playlistId || p.browseId} className="aw-card" onClick={() => openPlaylist(p)}>
                    {p.artwork ? <img className="aw-art" src={p.artwork} loading="lazy" alt="" /> : <div className="aw-art aw-art-ph"><Music2 size={30} /></div>}
                    <div className="aw-title">{p.title}</div>
                    <div className="aw-sub">Playlist</div>
                  </div>
                ))}
              </div>
            </>
          )}
        </div>
      )}

      {queueOpen && (
        <div className="aw-queue">
          <div className="aw-queue-head"><span>Up next</span><button className="aw-icon-btn" aria-label="close queue" onClick={() => setQueueOpen(false)}><X size={16} /></button></div>
          <div className="aw-queue-list">
            {queue.map((t) => (
              <div key={t.videoId} className={`aw-row small${t.videoId === current?.videoId ? ' current' : ''}`} onClick={() => play(t, queue)}>
                {t.artwork ? <img src={t.artwork} width={36} height={36} className="aw-row-art" loading="lazy" alt="" /> : <div className="aw-row-art aw-row-ph"><Music2 size={15} /></div>}
                <div className="aw-row-meta"><div className="aw-row-title">{t.title}</div><div className="aw-row-sub">{(t.artists || []).join(', ')}</div></div>
              </div>
            ))}
          </div>
        </div>
      )}

      {focusOpen && current && (
        <div className="aw-focus">
          <button className="aw-icon-btn aw-focus-x" aria-label="exit focus" onClick={() => setFocusOpen(false)}><Minimize2 size={20} /></button>
          {current.artwork && <img src={current.artwork} className="aw-focus-art" alt="" />}
          <div className="aw-focus-title">{current.title}</div>
          <div className="aw-focus-artist">{(current.artists || []).join(', ')}</div>
          <div className="aw-controls">
            <button className="aw-icon-btn" onClick={() => step(-1)} aria-label="previous"><SkipBack size={22} fill="currentColor" /></button>
            <button className="aw-play big" onClick={toggle} aria-label={playing ? 'pause' : 'play'}>
              {playing ? <Pause size={24} fill="currentColor" /> : <PlayGlyph size={24} />}
            </button>
            <button className="aw-icon-btn" onClick={() => step(1)} aria-label="next"><SkipForward size={22} fill="currentColor" /></button>
          </div>
        </div>
      )}

      <footer className="aw-player">
        <div className="aw-now">
          {current?.artwork
            ? <img src={current.artwork} width={56} height={56} className="aw-now-art" alt="" />
            : <div className="aw-now-art aw-now-ph"><Music2 size={22} /></div>}
          <div className="aw-now-meta">
            <div className="aw-now-title">{current?.title ?? 'Nothing playing'}</div>
            <div className="aw-now-artist">{(current?.artists || []).join(', ')}</div>
          </div>
          <button className={`aw-icon-btn${current && likedIds.includes(current.videoId!) ? ' liked' : ''}`} onClick={() => current && toggleLike(current)} aria-label="like" disabled={!current}>
            <Heart size={17} fill={current && likedIds.includes(current.videoId!) ? 'currentColor' : 'none'} />
          </button>
        </div>

        <div className="aw-center">
          <div className="aw-controls">
            <button className={`aw-icon-btn${shuffle ? ' active' : ''}`} onClick={() => setShuffle((s) => !s)} aria-label="shuffle"><Shuffle size={17} /></button>
            <button className="aw-icon-btn" onClick={() => step(-1)} aria-label="previous"><SkipBack size={19} fill="currentColor" /></button>
            <button className="aw-play" onClick={toggle} aria-label={playing ? 'pause' : 'play'}>
              {resolvingId ? <LoaderCircle size={19} className="spin" /> : playing ? <Pause size={19} fill="currentColor" /> : <PlayGlyph size={19} />}
            </button>
            <button className="aw-icon-btn" onClick={() => step(1)} aria-label="next"><SkipForward size={19} fill="currentColor" /></button>
            <button className={`aw-icon-btn${repeat ? ' active' : ''}`} onClick={() => setRepeat((r) => !r)} aria-label="repeat"><Repeat size={17} /></button>
          </div>
          <div className="aw-prow">
            <span className="aw-time">{fmt(pos)}</span>
            <div
              className="aw-progress"
              onClick={(e) => {
                const el = audioRef.current!;
                if (!el.duration) return;
                const rect = (e.currentTarget as HTMLDivElement).getBoundingClientRect();
                el.currentTime = ((e.clientX - rect.left) / rect.width) * el.duration;
              }}
            >
              <div style={{ width: `${dur ? (pos / dur) * 100 : 0}%` }} />
            </div>
            <span className="aw-time">{fmt(dur)}</span>
          </div>
        </div>

        <div className="aw-right">
          {upd.phase !== 'idle' && (
            <button
              className={`aw-icon-btn aw-update${upd.phase === 'ready' ? ' ready' : ''}${upd.phase === 'error' ? ' error' : ''}`}
              onClick={onUpdateClick}
              disabled={upd.phase === 'downloading' || upd.phase === 'applying'}
              aria-label="app update"
              title={updTitle()}
            >
              {upd.phase === 'downloading'
                ? <span className="aw-update-pct">{upd.pct ?? 0}%</span>
                : upd.phase === 'applying'
                  ? <LoaderCircle size={17} className="spin" />
                  : upd.phase === 'ready'
                    ? <RefreshCw size={16} />
                    : <Download size={17} />}
            </button>
          )}
          <button className={`aw-icon-btn${lyricsOpen ? ' active' : ''}`} onClick={() => setLyricsOpen((o) => !o)} aria-label="lyrics"><MicVocal size={17} /></button>
          <button className={`aw-icon-btn${queueOpen ? ' active' : ''}`} onClick={() => setQueueOpen((o) => !o)} aria-label="queue"><ListMusic size={17} /></button>
          <button className="aw-icon-btn" onClick={() => setMuted((m) => !m)} aria-label="mute">
            {muted || vol === 0 ? <VolumeX size={17} /> : <Volume2 size={17} />}
          </button>
          <input
            className="aw-vol" type="range" min={0} max={1} step={0.01} value={muted ? 0 : vol}
            onChange={(e) => { setVol(Number(e.target.value)); setMuted(false); }}
            aria-label="volume"
          />
          <button className="aw-icon-btn" onClick={() => current && setFocusOpen(true)} aria-label="focus mode"><Maximize2 size={17} /></button>
        </div>

        <audio
          ref={(el) => { audioRef.current = el; if (el) el.volume = muted ? 0 : vol; }}
          preload="auto"
          onPlay={() => setPlaying(true)}
          onPause={() => setPlaying(false)}
          onTimeUpdate={(e) => { setPos(e.currentTarget.currentTime); if (e.currentTarget.currentTime > 0.3 && stallRef.current) { clearTimeout(stallRef.current); stallRef.current = null; } }}
          onLoadedMetadata={(e) => { setDur(e.currentTarget.duration || 0); }}
          onEnded={(e) => {
            const el = e.currentTarget;
            const d = el.duration || dur || 0;
            // "ended" well before the actual duration means the CDN dropped the
            // stream — refresh the URL and resume instead of skipping the song
            if (d > 0 && Number.isFinite(d) && el.currentTime < d - 1.5) {
              void recoverTrack(el.currentTime).then((ok) => {
                if (!ok) {
                  setPlaying(false);
                  setBanner('This track kept cutting out — skipping to the next one.');
                  if (queue.length > 1 || shuffle) step(1);
                }
              });
              return;
            }
            if (repeat && audioRef.current) { audioRef.current.currentTime = 0; audioRef.current.play(); return; }
            setPlaying(false);
            if (queue.length > 1 || shuffle) step(1);
          }}
          onError={() => {
            setPlaying(false);
            void recoverTrack().then((ok) => {
              if (ok) return;
              if (queue.length > 1 || shuffle) {
                setBanner('Stream expired — skipping to the next track.');
                step(1);
              } else {
                setBanner('Stream expired before playback started — press play again to refresh the URL.');
              }
            });
          }}
        />
      </footer>

      {syncView()}
      {toastView()}
    </div>
    </ShellBoundary>
  );
}
