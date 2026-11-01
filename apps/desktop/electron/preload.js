const { contextBridge, ipcRenderer } = require('electron');

const BASE = process.env.AWITIZE_API_URL || 'http://127.0.0.1:8765';

// Main-process handlers return { ok, ... }; { ok: false, error } becomes a
// thrown Error so the renderer can try/catch like any other async call.
async function invoke(channel, ...args) {
  const res = await ipcRenderer.invoke(channel, ...args);
  if (res && res.ok === false) throw new Error(res.error || 'request failed');
  return res;
}

contextBridge.exposeInMainWorld('awitize', {
  search: async (q) => (await fetch(`${BASE}/search?q=${encodeURIComponent(q)}`)).json(),
  stream: async (videoId) => (await fetch(`${BASE}/stream/${videoId}`)).json(),
  health: async () => (await fetch(`${BASE}/health`)).json(),
  app: {
    checkUpdates: (opts) => invoke('app:check-updates', opts),
    downloadUpdate: () => invoke('app:download-update'),
    applyUpdate: (source, target) => invoke('app:apply-update', source, target),
    onUpdateProgress: (cb) => {
      const listener = (_ev, data) => cb(data);
      ipcRenderer.on('app:update-progress', listener);
      return () => ipcRenderer.removeListener('app:update-progress', listener);
    },
  },
  sync: {
    start: (opts) => invoke('sync:start', opts),
    stop: () => invoke('sync:stop'),
    onEvent: (cb) => {
      const listener = (_ev, data) => cb(data);
      ipcRenderer.on('sync:event', listener);
      return () => ipcRenderer.removeListener('sync:event', listener);
    },
    onRequest: (cb) => {
      const listener = (_ev, req) => cb(req);
      ipcRenderer.on('sync:request', listener);
      return () => ipcRenderer.removeListener('sync:request', listener);
    },
    respond: (reqId, data) => ipcRenderer.send('sync:response', reqId, data),
  },
});
