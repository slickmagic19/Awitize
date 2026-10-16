const { contextBridge } = require('electron');

const BASE = process.env.AWITIZE_API_URL || 'http://127.0.0.1:8765';

contextBridge.exposeInMainWorld('awitize', {
  search: async (q) => (await fetch(`${BASE}/search?q=${encodeURIComponent(q)}`)).json(),
  stream: async (videoId) => (await fetch(`${BASE}/stream/${videoId}`)).json(),
  health: async () => (await fetch(`${BASE}/health`)).json(),
});
