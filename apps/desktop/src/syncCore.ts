// Phone-sync core: payload shape + add-only merge.
//
// This is the shared contract with the Awitize Android app (and with the JSON
// that electron/sync.js ferries over the LAN). Rule #1: sync only ever adds —
// nothing local is removed or overwritten by an incoming payload. Matching
// rows are left untouched (plays just take the max count and newest
// timestamp) and remote-only rows are appended.
//
// Kept free of React so it can be exercised directly (see the merge test in
// the repo notes); the Android Kotlin port mirrors these exact semantics.
type Item = { videoId?: string; title?: string; artists?: string[]; album?: string; year?: string; views?: string; artwork?: string; duration?: string };
type Playlist = { id: string; name: string; tracks: Item[] };
type PlayEvent = { id: string; ts: number; n: number };

export type SyncStats = { likesAdded: number; playlistsAdded: number; playlistsMerged: number; tracksAdded: number; playsAdded: number };
export type SyncSnapshot = {
  aw?: string; v?: number; name?: string;
  liked: string[];
  tracks: Record<string, Item>;
  playlists: { name: string; tracks: Item[] }[];
  plays: PlayEvent[];
};
export type SyncLib = { name: string; likedIds: string[]; trackmap: Record<string, Item>; playlists: Playlist[]; plays: PlayEvent[] };

const TRACK_CAP = 300; // same cap as rememberTrack()
const PLAY_CAP = 120; // same cap as notePlay()

export function normName(s: string) {
  return s.trim().toLowerCase().replace(/\s+/g, ' ');
}

export function buildSyncSnapshot(lib: SyncLib): SyncSnapshot {
  // only ship tracks the receiving side actually needs — trackmap histories
  // on both sides can be much larger than the referenced set
  const ids = new Set<string>(lib.likedIds);
  for (const p of lib.playlists) for (const t of p.tracks) if (t.videoId) ids.add(t.videoId);
  for (const e of lib.plays) ids.add(e.id);
  const tracks: Record<string, Item> = {};
  for (const id of ids) if (lib.trackmap[id]) tracks[id] = lib.trackmap[id];
  return {
    aw: 'sync', v: 1, name: lib.name,
    liked: lib.likedIds,
    tracks,
    playlists: lib.playlists.map((p) => ({ name: p.name, tracks: p.tracks.filter((t) => t.videoId) })),
    plays: lib.plays,
  };
}

export function mergeIncoming(lib: Pick<SyncLib, 'likedIds' | 'trackmap' | 'playlists' | 'plays'>, incoming: Partial<SyncSnapshot>) {
  const likedSet = new Set(lib.likedIds);
  let likesAdded = 0;
  for (const id of Array.isArray(incoming.liked) ? incoming.liked : []) {
    if (typeof id === 'string' && id && !likedSet.has(id)) { likedSet.add(id); likesAdded += 1; }
  }

  const trackmap: Record<string, Item> = { ...lib.trackmap };
  const incTracks = incoming.tracks && typeof incoming.tracks === 'object' ? incoming.tracks : {};
  for (const [id, it] of Object.entries(incTracks)) {
    if (!trackmap[id] && it && typeof it === 'object' && (it as Item).videoId === id) trackmap[id] = it as Item;
  }
  const keys = Object.keys(trackmap);
  if (keys.length > TRACK_CAP) for (const k of keys.slice(0, keys.length - TRACK_CAP)) delete trackmap[k];

  const playlists = lib.playlists.map((p) => ({ ...p, tracks: [...p.tracks] }));
  const byName = new Map(playlists.map((p) => [normName(p.name), p]));
  let playlistsAdded = 0;
  let playlistsMerged = 0;
  let tracksAdded = 0;
  for (const inc of Array.isArray(incoming.playlists) ? incoming.playlists : []) {
    const name = String(inc?.name ?? '').trim();
    if (!name) continue;
    const incList = (Array.isArray(inc.tracks) ? inc.tracks : []).filter((t) => t && typeof t.videoId === 'string' && t.videoId);
    const hit = byName.get(normName(name));
    if (!hit) {
      const np: Playlist = { id: crypto.randomUUID(), name, tracks: [] };
      for (const t of incList) if (!np.tracks.some((x) => x.videoId === t.videoId)) { np.tracks.push(t); tracksAdded += 1; }
      playlists.push(np);
      byName.set(normName(name), np);
      playlistsAdded += 1;
    } else {
      const have = new Set(hit.tracks.map((t) => t.videoId));
      let added = 0;
      for (const t of incList) if (!have.has(t.videoId)) { hit.tracks.push(t); have.add(t.videoId!); added += 1; }
      if (added) { playlistsMerged += 1; tracksAdded += added; }
    }
  }

  const playsMap = new Map(lib.plays.map((e) => [e.id, { ...e }]));
  let playsAdded = 0;
  for (const inc of Array.isArray(incoming.plays) ? incoming.plays : []) {
    if (!inc || typeof inc.id !== 'string' || !inc.id) continue;
    const n = Math.max(1, Math.floor(Number(inc.n) || 1));
    const ts = Number(inc.ts) || 0;
    const hit = playsMap.get(inc.id);
    if (!hit) { playsMap.set(inc.id, { id: inc.id, ts, n }); playsAdded += 1; }
    else { hit.n = Math.max(hit.n, n); hit.ts = Math.max(hit.ts, ts); }
  }
  const plays = [...playsMap.values()].sort((a, b) => b.ts - a.ts).slice(0, PLAY_CAP);

  const stats: SyncStats = { likesAdded, playlistsAdded, playlistsMerged, tracksAdded, playsAdded };
  return { next: { likedIds: [...likedSet], trackmap, playlists, plays }, stats };
}

export function syncSummary(s: SyncStats) {
  const parts: string[] = [];
  if (s.likesAdded) parts.push(`+${s.likesAdded} liked`);
  if (s.playlistsAdded) parts.push(`+${s.playlistsAdded} playlists`);
  if (s.playlistsMerged) parts.push(`${s.playlistsMerged} playlists updated`);
  if (s.tracksAdded) parts.push(`+${s.tracksAdded} tracks`);
  if (s.playsAdded) parts.push(`+${s.playsAdded} listens`);
  return parts.length ? parts.join(' · ') : 'already in sync';
}
