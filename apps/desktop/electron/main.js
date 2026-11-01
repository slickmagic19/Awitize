const { app, BrowserWindow, ipcMain } = require('electron');
const { spawn } = require('node:child_process');
const fs = require('node:fs');
const path = require('node:path');
const updater = require('./updater');
const sync = require('./sync');

let apiProc = null;
let win = null;

const API_HEALTH_URL = 'http://127.0.0.1:8765/health';
const DEV_URL = process.env.AWITIZE_DEV_URL || 'http://127.0.0.1:5173';

function resolveApi() {
  const apiDir = app.isPackaged
    ? path.join(process.resourcesPath, 'music-api')
    : path.join(__dirname, '..', '..', '..', 'services', 'music-api');
  const candidates = [
    path.join(apiDir, 'vendor', 'python', 'python.exe'), // portable build
    path.join(apiDir, '.venv', 'Scripts', 'python.exe'), // dev venv
  ];
  let python = 'python';
  for (const c of candidates) {
    try {
      if (fs.existsSync(c)) { python = c; break; }
    } catch { /* keep looking */ }
  }
  return { apiDir, python };
}

async function startApi() {
  try {
    const res = await fetch(API_HEALTH_URL, { signal: AbortSignal.timeout(1500) });
    if (res.ok) return;
  } catch { /* not running yet */ }
  const { apiDir, python } = resolveApi();
  apiProc = spawn(python, ['main.py'], {
    cwd: apiDir,
    windowsHide: true,
    env: { ...process.env, PYTHONDONTWRITEBYTECODE: '1', AWITIZE_DATA_DIR: app.getPath('userData') },
  });
  apiProc.stdout?.on('data', (d) => console.log('[api]', String(d).slice(0, 200)));
  apiProc.stderr?.on('data', (d) => console.log('[api:err]', String(d).slice(0, 200)));
  apiProc.on('error', (err) => console.error('[awitize] music-api failed to start:', err));
}

function createWindow() {
  win = new BrowserWindow({
    width: 1280,
    height: 800,
    minWidth: 1100,
    minHeight: 700,
    backgroundColor: '#0B1020',
    autoHideMenuBar: true,
    // dev runs from electron.exe, whose exe icon is Electron's; packaged
    // builds also get the icon embedded into Awitize.exe by electron-builder
    icon: path.join(__dirname, '..', 'build', 'icon.ico'),
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      contextIsolation: true,
      nodeIntegration: false,
    },
  });
  win.on('closed', () => { win = null; void stopSync(); });
  const dev = !app.isPackaged;
  if (dev) win.loadURL(DEV_URL);
  else win.loadFile(path.join(__dirname, '..', 'dist', 'index.html'));
}

// ---------------------------------------------------------------- updater

const UPDATE_REPO = 'slickmagic19/Awitize';
const UPDATE_ASSET = updater.REPLACE_BASENAME;
let updateCache = null; // { at, info }

function cmpVersions(a, b) {
  const pa = String(a).replace(/^v/, '').split('.').map(Number);
  const pb = String(b).replace(/^v/, '').split('.').map(Number);
  for (let i = 0; i < Math.max(pa.length, pb.length); i += 1) {
    const x = pa[i] || 0;
    const y = pb[i] || 0;
    if (x !== y) return x > y ? 1 : -1;
  }
  return 0;
}

function originalExePath() {
  const p = process.env.PORTABLE_EXECUTABLE_FILE;
  return typeof p === 'string' && p && fs.existsSync(p) ? p : null;
}

async function checkForUpdates({ force = false } = {}) {
  if (!app.isPackaged) return { packaged: false };
  if (!force && updateCache && Date.now() - updateCache.at < 60 * 60 * 1000) return updateCache.info;
  const res = await fetch(`https://api.github.com/repos/${UPDATE_REPO}/releases/latest`, {
    headers: { 'user-agent': 'awitize-updater', accept: 'application/vnd.github+json' },
    signal: AbortSignal.timeout(8000),
  });
  if (res.status === 404) { // repo has no releases yet
    updateCache = { at: Date.now(), info: { packaged: true, update: false } };
    return updateCache.info;
  }
  if (!res.ok) throw new Error(`update check failed (HTTP ${res.status})`);
  const rel = await res.json();
  const version = String(rel.tag_name || rel.name || '').replace(/^v/, '');
  const asset = (rel.assets || []).find((a) => UPDATE_ASSET.test(a.name));
  const info = {
    packaged: true,
    update: Boolean(version && asset && cmpVersions(version, app.getVersion()) > 0),
    version,
    name: rel.name || rel.tag_name,
    notes: typeof rel.body === 'string' ? rel.body.slice(0, 2000) : '',
    asset: asset ? { name: asset.name, size: asset.size, url: asset.browser_download_url, sha256: asset.digest || null } : null,
    replaceTarget: originalExePath(),
  };
  updateCache = { at: Date.now(), info };
  return info;
}

let download = null; // single-flight: { promise, dest, received, total }

function progress(received, total, extra = {}) {
  if (!win || win.isDestroyed()) return;
  win.webContents.send('app:update-progress', { received, total, ...extra });
}

function downloadUpdate() {
  if (download) return download.promise;
  const promise = (async () => {
    const info = await checkForUpdates();
    if (!info.update || !info.asset) throw new Error('no update available');
    // Save under the release asset's own name (Awitize-x.y.z-win.exe):
    // applyUpdate validates that name before the exe may replace it.
    const dest = path.join(app.getPath('temp'), path.basename(info.asset.name));
    progress(0, info.asset.size || 0);
    const res = await fetch(info.asset.url, { redirect: 'follow' });
    if (!res.ok || !res.body) throw new Error(`download failed (HTTP ${res.status})`);
    const fh = await fs.promises.open(dest, 'w');
    let received = 0;
    try {
      const reader = res.body.getReader();
      for (;;) {
        const { done, value } = await reader.read();
        if (done) break;
        await fh.write(value);
        received += value.length;
        progress(received, info.asset.size || 0);
      }
    } finally {
      await fh.close();
    }
    return { ok: true, path: dest, size: received };
  })();
  download = { promise };
  promise.catch(() => {}).finally(() => { download = null; });
  return promise;
}

async function applyUpdate(source, replaceTarget) {
  if (!app.isPackaged) return { ok: false, error: 'self-update is disabled in dev' };
  const target = updater.validateReplaceArgs(source, replaceTarget).target;
  await fs.promises.access(source);
  if (path.resolve(target) === path.resolve(source)) throw new Error('update source and target are the same file');
  await updater.writeReplaceMarker(source, target);
  try {
    const child = spawn(source, [], { detached: true, stdio: 'ignore' });
    child.unref();
  } catch (err) {
    await updater.clearReplaceMarker();
    throw new Error(`failed to launch update: ${err.message}`);
  }
  setTimeout(() => app.quit(), 800);
  return { ok: true };
}

async function handlePendingReplace() {
  const pending = await updater.readReplaceMarker();
  if (!pending) return;
  try {
    const replaced = await updater.replaceOriginalExe(pending.target, pending.source);
    if (win && !win.isDestroyed()) {
      win.webContents.send('app:update-progress', { replaced: Boolean(replaced) });
    }
  } catch (err) {
    if (win && !win.isDestroyed()) {
      win.webContents.send('app:update-progress', { replaceError: String(err.message || err) });
    }
  }
}

ipcMain.handle('app:check-updates', (_ev, opts) => checkForUpdates(opts || {}));
ipcMain.handle('app:download-update', async () => {
  try {
    return await downloadUpdate();
  } catch (err) {
    return { ok: false, error: String(err.message || err) };
  }
});
ipcMain.handle('app:apply-update', async (_ev, source, target) => {
  try {
    return await applyUpdate(source, target);
  } catch (err) {
    return { ok: false, error: String(err.message || err) };
  }
});

// ---------------------------------------------------------------- phone sync
//
// The sync modal opens a LAN server for the phone; every data request is
// answered by the renderer (single source of truth) over IPC. Add-only merge
// semantics live in the renderer too. The server dies with the modal, with
// the window, or after 15 idle minutes.

let syncSession = null;
const syncPending = new Map(); // reqId -> { resolve, timer }
let syncSeq = 0;

function syncAskRenderer(kind, payload) {
  return new Promise((resolve, reject) => {
    if (!win || win.isDestroyed()) return reject(new Error('window is closed'));
    const reqId = ++syncSeq;
    const timer = setTimeout(() => {
      syncPending.delete(reqId);
      reject(new Error('sync request timed out'));
    }, 20000);
    syncPending.set(reqId, { resolve, timer });
    win.webContents.send('sync:request', { reqId, kind, payload });
    return undefined;
  });
}

ipcMain.on('sync:response', (_ev, reqId, data) => {
  const p = syncPending.get(reqId);
  if (!p) return;
  syncPending.delete(reqId);
  clearTimeout(p.timer);
  p.resolve(data || {});
});

function emitSyncEvent(evt) {
  if (win && !win.isDestroyed()) win.webContents.send('sync:event', evt);
}

async function stopSync() {
  if (!syncSession) return;
  const s = syncSession;
  syncSession = null;
  try { await s.stop(); } catch { /* already closed */ }
}

ipcMain.handle('sync:start', async (_ev, opts = {}) => {
  try {
    await stopSync();
    syncSession = await sync.startSyncServer({
      name: String(opts.name || '').slice(0, 48),
      onEvent: emitSyncEvent,
      onRequest: async (req) => {
        if (req.kind === 'snapshot') return (await syncAskRenderer('snapshot')).snapshot;
        return syncAskRenderer('merge', req.payload);
      },
    });
    return { ok: true, host: syncSession.host, port: syncSession.port, token: syncSession.token, ips: syncSession.ips };
  } catch (err) {
    return { ok: false, error: String(err.message || err) };
  }
});

ipcMain.handle('sync:stop', async () => {
  await stopSync();
  return { ok: true };
});

// ---------------------------------------------------------------- lifecycle

const gotLock = app.requestSingleInstanceLock();
if (!gotLock) {
  app.quit();
} else {
  app.on('second-instance', () => {
    if (win) {
      if (win.isMinimized()) win.restore();
      win.focus();
    }
  });

  app.whenReady().then(() => {
    startApi();
    createWindow();
    void handlePendingReplace();
    app.on('activate', () => {
      if (BrowserWindow.getAllWindows().length === 0) createWindow();
    });
  });

  app.on('window-all-closed', () => {
    if (process.platform !== 'darwin') app.quit();
  });

  app.on('will-quit', () => {
    void stopSync();
    if (apiProc) apiProc.kill();
  });
}
