import { Component, useEffect, useRef, useState } from 'react';
import type { ReactNode } from 'react';
import {
  AudioLines, Home, Compass, Search, LibraryBig, ChevronLeft, ChevronRight,
  CircleUserRound, Play, Pause, SkipBack, SkipForward, Shuffle, Repeat,
  Heart, Volume2, VolumeX, ListMusic, Maximize2, TriangleAlert, X,
  LoaderCircle, Music2, MoreHorizontal, ListPlus, Clock3, Trash2,
  Minimize2, Plus, ChevronsUpDown, TrendingUp, MicVocal, Disc3, Smile,
} from 'lucide-react';
import './tokens.css';

const API = 'http://127.0.0.1:8765';
const TAB_FILTER: Record<string, string> = { songs: 'songs', albums: 'albums', videos: 'videos', artists: 'artists' };
const COLORS = ['#2E7CF6', '#8B5CF6', '#22C55E', '#F59E0B', '#EF4444', '#06B6D3'];
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

type Item = { videoId?: string; browseId?: string; playlistId?: string; title?: string; artists?: string[]; album?: string; year?: string; views?: string; artwork?: string; duration?: string };
type Playlist = { id: string; name: string; tracks: Item[] };
type View = 'home' | 'search' | 'library' | 'liked' | 'explore';
type Tab = 'all' | 'songs' | 'albums' | 'videos' | 'artists';
type Profile = { id: string; name: string; color: string };

function fmt(sec: number) {
  if (!isFinite(sec) || sec < 0) return '0:00';
  return `${Math.floor(sec / 60)}:${String(Math.floor(sec % 60)).padStart(2, '0')}`;
}
function load<T>(k: string, fb: T): T {
  try { const v = localStorage.getItem(k); return v ? JSON.parse(v) as T : fb; }
  catch { return fb; }
}
function save(k: string, v: unknown) {
  try { localStorage.setItem(k, JSON.stringify(v)); } catch { /* full */ }
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
  const [muted, setMuted] = useState(false);
  const [shuffle, setShuffle] = useState(false);
  const [repeat, setRepeat] = useState(false);
  const [likedIds, setLikedIds] = useState<string[]>([]);
  const [trackmap, setTrackmap] = useState<Record<string, Item>>(() => load('aw-tracks', {}));
  const [playlists, setPlaylists] = useState<Playlist[]>([]);
  const [historyQ, setHistoryQ] = useState<string[]>([]);
  const [menuFor, setMenuFor] = useState<string | null>(null);
  const [newPlFor, setNewPlFor] = useState<string | null>(null);
  const [newPlName, setNewPlName] = useState('');
  const [queueOpen, setQueueOpen] = useState(false);
  const [focusOpen, setFocusOpen] = useState(false);
  const [avatarOpen, setAvatarOpen] = useState(false);
  const [apiHealth, setApiHealth] = useState('');
  const [pageSize, setPageSize] = useState(25);
  const [searchFailed, setSearchFailed] = useState(false);
  const [exhausted, setExhausted] = useState(false);
  const [tab, setTab] = useState<Tab>('all');
  const [sections, setSections] = useState<Record<string, Item[]>>({});
  const [albumView, setAlbumView] = useState<{ browseId: string; kind?: string; title?: string; artists?: string[]; year?: string; artwork?: string; tracks: Item[] } | null>(null);
  const [artistView, setArtistView] = useState<{ browseId: string; name?: string; artwork?: string; songs: Item[]; albums: Item[] } | null>(null);
  const [detailLoading, setDetailLoading] = useState(false);
  const [lyricsOpen, setLyricsOpen] = useState(false);
  const [lyrics, setLyrics] = useState<{ synced: boolean; lines: { t: number; text: string; words?: { t: number; text: string }[] }[] } | null>(null);
  const [lyricsLoading, setLyricsLoading] = useState(false);

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
  const [profiles, setProfiles] = useState<Profile[]>([]);
  const [activePid, setActivePid] = useState<string | null>(null);
  const [screen, setScreen] = useState<'gate' | 'app'>('gate');
  const [managing, setManaging] = useState(false);
  const [adding, setAdding] = useState(false);
  const [newName, setNewName] = useState('');
  const [newColor, setNewColor] = useState(COLORS[0]);
  const activeProfile = profiles.find((p) => p.id === activePid) ?? null;

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

  function loadProfile(pid: string) {
    const g = <T,>(k: string, fb: T): T => load(`aw:${pid}:${k}`, fb);
    setLikedIds(g<string[]>('liked', []));
    setPlaylists(g<Playlist[]>('playlists', []));
    setHistoryQ(g<string[]>('history', []));
    setQ(g<string>('q', ''));
    setView('home');
    setTab('all');
    setItems([]);
    setSections({});
    setAlbumView(null);
    setArtistView(null);
    setBanner('');
    setSearchFailed(false);
    setCurrent(null);
    setPlaying(false);
    setQueueOpen(false);
    setFocusOpen(false);
    setMenuFor(null);
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

  function addProfile(name: string, color: string) {
    const p = { id: crypto.randomUUID(), name: name.trim().slice(0, 16) || `Listener ${profiles.length + 1}`, color };
    const next = [...profiles, p];
    setProfiles(next);
    save('aw-profiles', next);
  }

  function confirmAdd() {
    if (!newName.trim()) return;
    addProfile(newName, newColor);
    setAdding(false);
    setNewName('');
  }

  function deleteProfile(id: string) {
    const next = profiles.filter((p) => p.id !== id);
    setProfiles(next);
    save('aw-profiles', next);
    const prefix = `aw:${id}:`;
    Object.keys(localStorage).filter((k) => k.startsWith(prefix)).forEach((k) => localStorage.removeItem(k));
    if (activePid === id) setActivePid(null);
  }

  const navPast = useRef<{ view: View; q: string; tab: Tab }[]>([]);
  const navFuture = useRef<{ view: View; q: string; tab: Tab }[]>([]);

  // ---- persistence ----
  useEffect(() => {
    localStorage.setItem('aw-vol', String(vol));
    if (audioRef.current) audioRef.current.volume = muted ? 0 : vol;
  }, [vol, muted]);
  useEffect(() => { if (activePid) localStorage.setItem(`aw:${activePid}:q`, q); }, [q, activePid]);
  useEffect(() => { if (activePid) save(`aw:${activePid}:liked`, likedIds); }, [likedIds, activePid]);
  useEffect(() => { save('aw-tracks', trackmap); }, [trackmap]);
  useEffect(() => { if (activePid) save(`aw:${activePid}:playlists`, playlists); }, [playlists, activePid]);
  useEffect(() => { if (activePid) save(`aw:${activePid}:history`, historyQ); }, [historyQ, activePid]);

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
    setAlbumView(null);
    setArtistView(null);
    setDetailLoading(true);
    try {
      const a = await (await fetch(`${API}/album/${it.browseId}`)).json();
      if (a.tracks) {
        (a.tracks as Item[]).forEach(rememberTrack);
        setAlbumView({ ...a, browseId: it.browseId });
      } else throw new Error('empty');
    } catch { setBanner('Could not open that album. Retry in a bit.'); }
    finally { setDetailLoading(false); }
  }

  async function openArtist(it: Item) {
    if (!it.browseId) return;
    setAlbumView(null);
    setArtistView(null);
    setDetailLoading(true);
    try {
      const a = await (await fetch(`${API}/artist/${it.browseId}`)).json();
      if (a.name) {
        ((a.songs || []) as Item[]).forEach(rememberTrack);
        setArtistView({ ...a, browseId: it.browseId });
      } else throw new Error('empty');
    } catch { setBanner('Could not open that artist. Retry in a bit.'); }
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
  }, [lyricsOpen, current?.videoId]);

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
  function navTo(v: View, query?: string) {
    navPast.current.push({ view, q, tab });
    if (navPast.current.length > 40) navPast.current.shift();
    navFuture.current = [];
    setView(v);
    if (query !== undefined) {
      setQ(query);
      if (v === 'search' && query.trim()) runSearch(query);
    }
  }
  function goBack() {
    const prev = navPast.current.pop();
    if (!prev) return;
    navFuture.current.push({ view, q, tab });
    setView(prev.view);
    setQ(prev.q);
    setTab(prev.tab);
    if (prev.view === 'search' && prev.q.trim() && `${prev.q.trim().toLowerCase()}|${pageSizeRef.current}|${prev.tab === 'all' ? 'all' : TAB_FILTER[prev.tab]}` !== lastCombo.current) {
      if (prev.tab === 'all') fetchAll(prev.q, true);
      else runSearch(prev.q, false, true, TAB_FILTER[prev.tab]);
    }
  }
  function goFwd() {
    const nxt = navFuture.current.pop();
    if (!nxt) return;
    navPast.current.push({ view, q, tab });
    setView(nxt.view);
    setQ(nxt.q);
    setTab(nxt.tab);
    if (nxt.view === 'search' && nxt.q.trim() && `${nxt.q.trim().toLowerCase()}|${pageSizeRef.current}|${nxt.tab === 'all' ? 'all' : TAB_FILTER[nxt.tab]}` !== lastCombo.current) {
      if (nxt.tab === 'all') fetchAll(nxt.q, true);
      else runSearch(nxt.q, false, true, TAB_FILTER[nxt.tab]);
    }
  }

  // ---- playback + prefetch ----
  async function play(item: Item, list?: Item[]) {
    if (!item.videoId) return;
    if (list?.length) queueRef.current = list;
    rememberTrack(item);
    setCurrent(item);
    setResolvingId(item.videoId);
    setBanner('');
    try {
      const r = await fetch(`${API}/stream/${item.videoId}`);
      const j = await r.json().catch(() => ({}));
      if (!r.ok) throw new Error(j?.detail?.message || 'Could not play this track.');
      const el = audioRef.current!;
      el.src = j.url;
      await el.play();
      setPlaying(true);
      if (stallRef.current) clearTimeout(stallRef.current);
      stallRef.current = window.setTimeout(() => {
        const a = audioRef.current;
        if (a && a.currentTime < 0.2 && a.readyState < 3) {
          setBanner('Audio never started — your connection is likely blocking YouTube\'s media servers. Connect a VPN or switch network, then press play again.');
        }
      }, 10000);
      // warm the next track while this one plays — Next becomes instant
      const qi = queueRef.current.findIndex((x) => x.videoId === item.videoId);
      const nxt = queueRef.current[(qi + 1) % queueRef.current.length];
      if (nxt?.videoId && nxt.videoId !== item.videoId) fetch(`${API}/stream/${nxt.videoId}`).catch(() => {});
    } catch (e) {
      setPlaying(false);
      setBanner(e instanceof Error ? e.message : 'Could not play this track.');
    } finally {
      setResolvingId(null);
    }
  }
  function step(dir: 1 | -1) {
    const list = queueRef.current.length ? queueRef.current : items;
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
    setLikedIds((prev) => prev.includes(it.videoId!) ? prev.filter((x) => x !== it.videoId) : [...prev, it.videoId!]);
  }
  function createPlaylist(name: string, first?: Item): Playlist | null {
    const n = name.trim();
    if (!n) return null;
    const pl = { id: crypto.randomUUID(), name: n, tracks: first?.videoId ? [first] : [] };
    setPlaylists((p) => [pl, ...p]);
    return pl;
  }
  function addToPlaylist(id: string, it: Item) {
    if (!it.videoId) return;
    rememberTrack(it);
    setPlaylists((ps) => ps.map((p) => p.id === id && !p.tracks.some((t) => t.videoId === it.videoId)
      ? { ...p, tracks: [...p.tracks, it] } : p));
    setMenuFor(null);
  }
  function removeFromPlaylist(id: string, videoId: string) {
    setPlaylists((ps) => ps.map((p) => p.id === id ? { ...p, tracks: p.tracks.filter((t) => t.videoId !== videoId) } : p));
  }
  function deletePlaylist(id: string) {
    setPlaylists((ps) => ps.filter((p) => p.id !== id));
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
    return (
      <>
        <button className="aw-menu-item" onClick={() => { setMenuFor(null); play(it, list); }}><Play size={15} />Play</button>
        <button className="aw-menu-item" onClick={() => { setMenuFor(null); toggleLike(it); }}>
          <Heart size={15} />{likedIds.includes(it.videoId!) ? 'Unlike' : 'Like'}
        </button>
        <button className="aw-menu-item" onClick={() => { setMenuFor(null); if (it.videoId !== current?.videoId) play(it, list); setLyricsOpen(true); }}>
          <MicVocal size={15} />Lyrics
        </button>
        <div className="aw-menu-sep" />
        <div className="aw-menu-head"><ListPlus size={15} />Add to playlist</div>
        {playlists.map((p) => (
          <button key={p.id} className="aw-menu-item sub" onClick={() => addToPlaylist(p.id, it)}>{p.name}</button>
        ))}
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

  function cardMenu(it: Item) {
    if (menuFor !== it.videoId) return null;
    return <div className="aw-menu" onClick={(e) => e.stopPropagation()}>{menuBody(it, items)}</div>;
  }

  function rowMenu(it: Item, list: Item[]) {
    if (menuFor !== it.videoId) return null;
    return <div className="aw-menu row" onClick={(e) => e.stopPropagation()}>{menuBody(it, list)}</div>;
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
  const [moodView, setMoodView] = useState<{ title: string; playlists: Item[] } | null>(null);
  const [moodLoading, setMoodLoading] = useState(false);
  const newRef = useRef<HTMLDivElement>(null);
  const trendRef = useRef<HTMLDivElement>(null);
  const moodsRef = useRef<HTMLDivElement>(null);
  const vidsRef = useRef<HTMLDivElement>(null);
  const railNewRef = useRef<HTMLDivElement>(null);
  const railVidRef = useRef<HTMLDivElement>(null);

  async function fetchExplore() {
    if (explore || exploreLoading) return;
    setExploreLoading(true);
    setBanner('');
    try {
      const j = await (await fetch(`${API}/explore`)).json();
      for (const list of [j.new_releases, j.trending, j.new_videos, j.chart_artists]) (list || []).forEach(rememberTrack);
      setExplore(j);
    } catch {
      setBanner('Explore is unreachable — check the backend and retry.');
    } finally {
      setExploreLoading(false);
    }
  }

  useEffect(() => {
    if (view === 'explore' && !explore && !exploreLoading) fetchExplore();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [view]);

  async function openMood(title: string, params: string) {
    setMoodView(null);
    setMoodLoading(true);
    try {
      const j = await (await fetch(`${API}/mood?params=${encodeURIComponent(params)}`)).json();
      setMoodView({ title, playlists: j.playlists || [] });
    } catch { setBanner('Could not open that mood. Retry in a bit.'); }
    finally { setMoodLoading(false); }
  }

  async function openPlaylist(it: Item) {
    const pid = it.playlistId || it.browseId;
    if (!pid) return;
    setDetailLoading(true);
    try {
      const p = await (await fetch(`${API}/playlist/${pid}`)).json();
      const tracks = (p.tracks || []) as Item[];
      tracks.forEach(rememberTrack);
      setMoodView(null);
      setAlbumView({ browseId: pid, kind: 'playlist', title: p.title, artists: p.author ? [p.author] : [], artwork: p.artwork, tracks });
    } catch { setBanner('Could not open that playlist. Retry in a bit.'); }
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
        <div className="aw-rank-body">{trackRow(it, undefined, list)}</div>
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
            <div key={p.id} className="aw-tile" onClick={() => { if (!managing) selectProfile(p.id); }}>
              <div className="aw-tile-face" style={{ background: p.color }}>
                {managing && profiles.length > 1 && (
                  <button className="aw-tile-del" aria-label={`delete ${p.name}`} onClick={(e) => { e.stopPropagation(); deleteProfile(p.id); }}><X size={14} /></button>
                )}
                <span>{p.name[0]?.toUpperCase()}</span>
              </div>
              <div className="aw-tile-name">{p.name}</div>
            </div>
          ))}
          {adding ? (
            <div className="aw-tile">
              <div className="aw-tile-face form" style={{ background: newColor }}>
                <input autoFocus maxLength={16} placeholder="Name" value={newName} onChange={(e) => setNewName(e.target.value)}
                  onKeyDown={(e) => { if (e.key === 'Enter') confirmAdd(); if (e.key === 'Escape') setAdding(false); }} />
              </div>
              <div className="aw-dots">
                {COLORS.map((c) => (
                  <button key={c} className={`aw-dot${newColor === c ? ' sel' : ''}`} style={{ background: c }} onClick={() => setNewColor(c)} aria-label={c} />
                ))}
              </div>
              <div className="aw-tile-actions">
                <button className="aw-mini-btn" onClick={confirmAdd}>Add</button>
                <button className="aw-icon-btn" onClick={() => setAdding(false)} aria-label="cancel"><X size={16} /></button>
              </div>
            </div>
          ) : (
            profiles.length < 6 && (
              <div className="aw-tile" onClick={() => { setAdding(true); setNewName(''); setNewColor(COLORS[profiles.length % COLORS.length]); }}>
                <div className="aw-tile-face add"><Plus size={34} /></div>
                <div className="aw-tile-name">Add profile</div>
              </div>
            )
          )}
        </div>
        {profiles.length > 0 && (
          <button className="aw-gate-manage" onClick={() => setManaging((m) => !m)}>{managing ? 'Done' : 'Manage profiles'}</button>
        )}
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

  function trackCard(it: Item) {
    const isCardCurrent = !!it.videoId && it.videoId === current?.videoId;
    return (
      <div key={it.videoId} className="aw-card" onClick={() => play(it, items)}>
        {it.artwork ? <img className="aw-art" src={it.artwork} loading="lazy" alt="" /> : <div className="aw-art aw-art-ph"><Music2 size={30} /></div>}
        <div className="aw-title">{it.title}</div>
        <div className="aw-sub">{(it.artists || []).join(', ')}</div>
        <button
          className="aw-fab"
          aria-label={`${isCardCurrent && playing ? 'pause' : 'play'} ${it.title}`}
          onClick={(e) => { e.stopPropagation(); if (isCardCurrent) toggle(); else play(it, items); }}
        >
          {resolvingId === it.videoId ? <LoaderCircle size={18} className="spin" /> : isCardCurrent && playing ? <Pause size={18} fill="currentColor" /> : <PlayGlyph size={18} />}
        </button>
        <button className="aw-cardmenu" aria-label="more" onClick={(e) => { e.stopPropagation(); setMenuFor(menuFor === it.videoId ? null : it.videoId!); setNewPlFor(null); }}>
          <MoreHorizontal size={17} />
        </button>
        {cardMenu(it)}
      </div>
    );
  }

  function trackRow(it: Item, extra?: ReactNode, list?: Item[]) {
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
        <button className="aw-icon-btn" aria-label="more options" onClick={(e) => { e.stopPropagation(); setMenuFor(menuFor === it.videoId ? null : it.videoId!); setNewPlFor(null); }}>
          <MoreHorizontal size={16} />
        </button>
        {rowMenu(it, qList)}
        {extra}
      </div>
    );
  }

  // Gate renders from here: every hook above runs on every render,
  // so hook order can never diverge between gate and app screens.
  if (screen === 'gate') return gateView();

  return (
    <ShellBoundary>
    <div className="aw-shell" onClick={() => { if (menuFor) setMenuFor(null); }}>
      <aside className="aw-side">
        <div className="aw-logo">
          <span className="aw-logo-mark"><AudioLines size={18} strokeWidth={2.5} /></span>
          Awitize
        </div>
        <button className={`aw-nav${view === 'home' ? ' active' : ''}`} onClick={() => navTo('home')}><Home size={18} />Home</button>
        <button className={`aw-nav${view === 'explore' ? ' active' : ''}`} onClick={() => navTo('explore')}><Compass size={18} />Explore</button>
        <button className={`aw-nav${view === 'search' ? ' active' : ''}`} onClick={() => { navTo('search'); setTimeout(() => searchRef.current?.focus(), 0); }}><Search size={18} />Search</button>
        <button className={`aw-nav${view === 'library' ? ' active' : ''}`} onClick={() => navTo('library')}><LibraryBig size={18} />Your Library</button>
        <div className="aw-label">Playlists</div>
        <button className={`aw-nav${view === 'liked' ? ' active' : ''}`} onClick={() => navTo('liked')}><Heart size={18} />Liked Songs</button>
        {playlists.map((p) => (
          <button key={p.id} className="aw-nav aw-pl" onClick={() => navTo('library')}><ListMusic size={18} />{p.name}</button>
        ))}
        <div style={{ flex: 1 }} />
        <button className="aw-user" onClick={() => setScreen('gate')} title="Switch profile">
          <span className="aw-user-face" style={activeProfile ? { background: activeProfile.color } : undefined}>{activeProfile?.name[0]?.toUpperCase() ?? 'G'}</span>
          <div><div className="aw-user-name">{activeProfile?.name ?? 'Guest'}</div><div className="aw-user-sub">Switch profile</div></div>
          <ChevronsUpDown size={16} className="aw-user-chev" />
        </button>
      </aside>

      <main className="aw-main">
        <div className="aw-head-stick">
        <div className="aw-top">
          <button className="aw-topbtn" aria-label="back" onClick={goBack}><ChevronLeft size={18} /></button>
          <button className="aw-topbtn" aria-label="forward" onClick={goFwd}><ChevronRight size={18} /></button>
          <div className="aw-search-wrap">
            <Search size={16} className="aw-search-icon" />
            <input
              ref={searchRef}
              className="aw-search"
              placeholder="Search songs, artists — Ctrl+K"
              value={q}
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
          <div className="aw-ava-wrap">
            <button className="aw-avatar" style={activeProfile ? { background: activeProfile.color } : undefined} onClick={(e) => { e.stopPropagation(); setAvatarOpen((a) => !a); }}>{activeProfile?.name[0]?.toUpperCase() ?? 'G'}</button>
            {avatarOpen && (
              <div className="aw-menu aw-ava-menu" onClick={(e) => e.stopPropagation()}>
                <div className="aw-menu-note">{activeProfile ? `Listening as ${activeProfile.name}` : 'No profile'}</div>
                <button className="aw-menu-item" onClick={() => { setAvatarOpen(false); setScreen('gate'); }}><CircleUserRound size={15} />Switch profile</button>
                <div className="aw-menu-sep" />
                <button className="aw-menu-item" onClick={async () => {
                  try { const h = await (await fetch(`${API}/health`)).json(); setApiHealth(h.ok ? 'Backend healthy.' : 'Backend odd.'); }
                  catch { setApiHealth('Backend unreachable.'); }
                }}><AudioLines size={15} />Check backend</button>
                <button className="aw-menu-item" onClick={clearCaches}><Trash2 size={15} />Clear caches</button>
                <div className="aw-menu-sep" />
                <div className="aw-menu-note">{apiHealth || 'Awitize 0.1.0 — local build'}</div>
              </div>
            )}
          </div>
        </div>
        </div>

        {view === 'home' && (
          <>
            <div className="aw-sec-row"><h2 className="aw-sec-title">Good to see you</h2></div>
            {!!historyQ.length && (
              <>
                <div className="aw-label" style={{ padding: '0 0 8px' }}>Jump back in</div>
                <div className="aw-chips">
                  {historyQ.map((h) => (
                    <button key={h} className="aw-chip" onClick={() => runQuery(h)}><Clock3 size={14} />{h}</button>
                  ))}
                </div>
              </>
            )}
            <div className="aw-label" style={{ padding: '18px 0 8px' }}>Liked preview</div>
            {likedTracks.length ? (
              <div className="aw-grid">{likedTracks.slice(0, 4).map(trackCard)}</div>
            ) : (
              <div className="aw-empty"><Heart size={26} /><p>Like any track and it will wait for you here.</p></div>
            )}
          </>
        )}

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
                    <div className="aw-rows">{songsPreview.map((t) => trackRow(t))}</div>
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
                    <div className="aw-grid">{videosPreview.map(trackCard)}</div>
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
                {items.length ? <div className="aw-rows">{items.map((t) => trackRow(t))}</div>
                  : !banner && (<div className="aw-empty"><Music2 size={28} /><p>No songs in this shelf — try another tab.</p><div className="aw-empty-actions"><button className="aw-mini-btn ghost" onClick={() => switchTab('all')}>Back to All</button></div></div>)}
                {moreBlock}
              </>
            ) : tab === 'videos' ? (
              <>
                {items.length ? <div className="aw-grid">{items.map(trackCard)}</div>
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
            {likedTracks.length ? <div className="aw-rows">{likedTracks.map((t) => trackRow(t))}</div>
              : <div className="aw-empty"><Heart size={28} /><p>Nothing liked yet — hover any card for the menu.</p></div>}
          </>
        )}

        {view === 'library' && (
          <>
            <div className="aw-sec-row"><h2 className="aw-sec-title">Your Library</h2><span className="aw-count">{playlists.length} playlists</span></div>
            {!playlists.length && (
              <div className="aw-empty"><ListMusic size={28} /><p>No playlists yet — use the ••• menu on any card to create one.</p></div>
            )}
            {playlists.map((p) => (
              <div key={p.id} className="aw-pl-block">
                <div className="aw-pl-head">
                  <span className="aw-pl-name">{p.name}</span>
                  <span className="aw-count">{p.tracks.length} tracks</span>
                  <div style={{ flex: 1 }} />
                  {p.tracks[0] && <button className="aw-mini-btn" onClick={() => play(p.tracks[0], p.tracks)}><PlayGlyph size={13} />Play</button>}
                  <button className="aw-icon-btn" aria-label={`delete ${p.name}`} onClick={() => deletePlaylist(p.id)}><Trash2 size={15} /></button>
                </div>
                <div className="aw-rows">
                  {p.tracks.map((t) => trackRow(t,
                    <button key={`rm-${t.videoId}`} className="aw-icon-btn" aria-label="remove" onClick={(e) => { e.stopPropagation(); removeFromPlaylist(p.id, t.videoId!); }}><X size={15} /></button>))}
                </div>
              </div>
            ))}
          </>
        )}
      </main>

      {(albumView || artistView || detailLoading) && (
        <div className="aw-overlay">
          <button className="aw-icon-btn aw-focus-x" aria-label="close details" onClick={() => { setAlbumView(null); setArtistView(null); setDetailLoading(false); }}><X size={20} /></button>
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
                  {albumView.tracks[0] && <button className="aw-mini-btn" onClick={() => play(albumView.tracks[0], albumView.tracks)}><PlayGlyph size={13} />Play</button>}
                </div>
              </div>
              <div className="aw-rows">{albumView.tracks.map((t) => trackRow(t, undefined, albumView.tracks))}</div>
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
              <div className="aw-rows">{artistView.songs.filter((t) => t.videoId).map((t) => trackRow(t, undefined, artistView.songs))}</div>
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
            {lyricsLoading ? (
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
          <button className="aw-icon-btn aw-focus-x" aria-label="close" onClick={() => setMoodView(null)}><X size={20} /></button>
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
          ref={audioRef}
          preload="auto"
          onPlay={() => setPlaying(true)}
          onPause={() => setPlaying(false)}
          onTimeUpdate={(e) => { setPos(e.currentTarget.currentTime); if (e.currentTarget.currentTime > 0.3 && stallRef.current) { clearTimeout(stallRef.current); stallRef.current = null; } }}
          onLoadedMetadata={(e) => { setDur(e.currentTarget.duration || 0); }}
          onEnded={() => {
            if (repeat && audioRef.current) { audioRef.current.currentTime = 0; audioRef.current.play(); return; }
            setPlaying(false);
            if (queue.length > 1 || shuffle) step(1);
          }}
          onError={() => {
            setPlaying(false);
            setBanner('Stream expired before playback started — press play again to refresh the URL.');
          }}
        />
      </footer>
    </div>
    </ShellBoundary>
  );
}
