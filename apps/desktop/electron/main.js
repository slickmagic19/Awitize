const { app, BrowserWindow } = require('electron');
const path = require('node:path');
const { spawn } = require('node:child_process');

let apiProc = null;

// One live window: a second launch focuses the first instead of stacking
// dead windows and duplicated backends.
const gotLock = app.requestSingleInstanceLock();
if (!gotLock) {
  app.quit();
} else {
  app.on('second-instance', () => {
    const win = BrowserWindow.getAllWindows()[0];
    if (win) {
      if (win.isMinimized()) win.restore();
      win.focus();
    }
  });

  async function startApi() {
    try {
      const res = await fetch('http://127.0.0.1:8765/health');
      if (res.ok) { console.log('[api] already running, reusing it'); return; }
    } catch { /* not running — start it below */ }
  const apiDir = path.join(__dirname, '..', '..', '..', 'services', 'music-api');
  // Prefer the repo venv (python.org build): Store Python cannot do DPAPI
  // cookie reads, which the resolver needs for authenticated fallbacks.
  let py = 'python';
  try {
    const fs = require('node:fs');
    const venvPy = path.join(apiDir, '.venv', 'Scripts', 'python.exe');
    if (fs.existsSync(venvPy)) py = venvPy;
  } catch { /* fall back to PATH python */ }
  apiProc = spawn(py, ['main.py'], { cwd: apiDir, windowsHide: true });
    apiProc.stdout?.on('data', (d) => console.log('[api]', String(d).slice(0, 200)));
    apiProc.stderr?.on('data', (d) => console.log('[api:err]', String(d).slice(0, 200)));
  }

  function createWindow() {
    const win = new BrowserWindow({
      width: 1280,
      height: 800,
      minWidth: 1100,
      minHeight: 700,
      backgroundColor: '#0B1020',
      autoHideMenuBar: true,
      webPreferences: {
        preload: path.join(__dirname, 'preload.js'),
        contextIsolation: true,
        nodeIntegration: false,
      },
    });
    const dev = !app.isPackaged;
    if (dev) win.loadURL('http://127.0.0.1:5173');
    else win.loadFile(path.join(__dirname, '..', 'dist', 'index.html'));
  }

  app.whenReady().then(() => {
    startApi();
    createWindow();
    app.on('activate', () => {
      if (BrowserWindow.getAllWindows().length === 0) createWindow();
    });
  });

  app.on('will-quit', () => {
    if (apiProc) apiProc.kill();
  });
  app.on('window-all-closed', () => {
    if (process.platform !== 'darwin') app.quit();
  });
}
