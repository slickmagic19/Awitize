// Vendors a self-contained Python into services/music-api/vendor/python so
// the portable build ships the FastAPI sidecar out of the box (no system
// Python needed on the user's machine). Embeddable CPython + `pip --target`
// install of requirements.txt. The embeddable minor version MUST match the
// dev venv's minor (binary wheels like pydantic_core are cp313-tagged).
// Usage: node scripts/fetch-python.js [--force]
const { spawnSync } = require('node:child_process');
const crypto = require('node:crypto');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');

const ROOT = path.resolve(__dirname, '..');
const API_DIR = path.join(ROOT, 'services', 'music-api');
const VENDOR_DIR = path.join(API_DIR, 'vendor');
const PY_DIR = path.join(VENDOR_DIR, 'python');
const STAMP = path.join(VENDOR_DIR, '.stamp.json');
const PY_VERSION = process.env.AWITIZE_PY_VERSION || '3.13.7';
const EMBED_URL = `https://www.python.org/ftp/python/${PY_VERSION}/python-${PY_VERSION}-embed-amd64.zip`;
const FORCE = process.argv.includes('--force');

const log = (...a) => console.log('[vendor]', ...a);

function reqHash() {
  const raw = fs.readFileSync(path.join(API_DIR, 'requirements.txt'));
  return crypto.createHash('sha256').update(raw).digest('hex').slice(0, 16);
}

function pyExe() {
  return path.join(PY_DIR, 'python.exe');
}

function importsOk() {
  const r = spawnSync(pyExe(), ['-c', 'import fastapi, uvicorn, ytmusicapi, yt_dlp, requests, pydantic_core'], {
    cwd: PY_DIR,
    encoding: 'utf8',
    timeout: 120000,
    env: { ...process.env, PYTHONDONTWRITEBYTECODE: '1' },
  });
  if (r.status !== 0) log('import check failed:', String(r.stderr || r.error || '').trim().slice(0, 400));
  return r.status === 0;
}

function alreadyVendored() {
  try {
    const stamp = JSON.parse(fs.readFileSync(STAMP, 'utf8'));
    if (stamp.python !== PY_VERSION || stamp.reqs !== reqHash()) return false;
  } catch {
    return false;
  }
  return fs.existsSync(pyExe()) && importsOk();
}

async function download(url, dest) {
  log('downloading', url);
  const res = await fetch(url, { redirect: 'follow' });
  if (!res.ok || !res.body) throw new Error(`download HTTP ${res.status}`);
  const fh = await fs.promises.open(dest, 'w');
  try {
    const reader = res.body.getReader();
    let received = 0;
    for (;;) {
      const { done, value } = await reader.read();
      if (done) break;
      await fh.write(value);
      received += value.length;
    }
    if (!received) throw new Error('empty download');
  } finally {
    await fh.close();
  }
}

function extractZip(zip, dest) {
  // bsdtar ships with Windows 10+ and handles .zip directly.
  let r = spawnSync('tar', ['-xf', zip, '-C', dest], { stdio: 'inherit' });
  if (r.status === 0) return;
  log('tar failed — falling back to PowerShell Expand-Archive');
  r = spawnSync('powershell.exe',
    ['-NoProfile', '-Command', `Expand-Archive -LiteralPath '${zip}' -DestinationPath '${dest}' -Force`],
    { stdio: 'inherit' });
  if (r.status !== 0) throw new Error('zip extraction failed');
}

function devVenvPython() {
  const p = path.join(API_DIR, '.venv', 'Scripts', 'python.exe');
  if (!fs.existsSync(p)) {
    throw new Error('Dev venv missing — run the README Quick start first (creates services/music-api/.venv)');
  }
  return p;
}

function patchPth() {
  const pth = fs.readdirSync(PY_DIR).find((f) => /^python3\d+\._pth$/i.test(f));
  if (!pth) throw new Error('python3xx._pth not found in embed zip');
  const fp = path.join(PY_DIR, pth);
  const lines = fs.readFileSync(fp, 'utf8').split(/\r?\n/).filter((l) => l.trim());
  if (!lines.some((l) => /^Lib\\site-packages$/i.test(l.trim()))) {
    const dot = lines.findIndex((l) => l.trim() === '.');
    lines.splice(dot >= 0 ? dot + 1 : lines.length, 0, 'Lib\\site-packages');
  }
  const siteIdx = lines.findIndex((l) => /^#?\s*import site$/.test(l));
  if (siteIdx >= 0) lines[siteIdx] = 'import site';
  else lines.push('import site');
  fs.writeFileSync(fp, lines.join('\r\n') + '\r\n');
}

function installDeps() {
  fs.mkdirSync(path.join(PY_DIR, 'Lib', 'site-packages'), { recursive: true });
  log('pip install --target (deps)');
  const r = spawnSync(devVenvPython(), [
    '-m', 'pip', 'install',
    '--no-input', '--disable-pip-version-check', '--no-warn-script-location',
    '--upgrade', '--target', path.join(PY_DIR, 'Lib', 'site-packages'),
    '-r', path.join(API_DIR, 'requirements.txt'),
  ], { stdio: 'inherit' });
  if (r.status !== 0) throw new Error('pip install failed');
}

async function main() {
  if (!FORCE && alreadyVendored()) {
    log(`python ${PY_VERSION} + deps already vendored (${PY_DIR})`);
    return;
  }
  fs.rmSync(VENDOR_DIR, { recursive: true, force: true });
  fs.mkdirSync(PY_DIR, { recursive: true });

  const zip = path.join(os.tmpdir(), `python-${PY_VERSION}-embed-amd64.zip`);
  if (!fs.existsSync(zip) || fs.statSync(zip).size === 0) await download(EMBED_URL, zip);
  else log('reusing cached download', zip);

  log('extracting embeddable python');
  extractZip(zip, PY_DIR);
  patchPth();
  installDeps();

  if (!importsOk()) throw new Error('vendored python failed the import check');
  fs.writeFileSync(STAMP, JSON.stringify({ python: PY_VERSION, reqs: reqHash(), at: Date.now() }, null, 2));
  const mb = (function walk(dir) {
    let s = 0;
    for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
      s += e.isDirectory() ? walk(path.join(dir, e.name)) : fs.statSync(path.join(dir, e.name)).size;
    }
    return s;
  })(PY_DIR);
  log(`OK: ${PY_DIR} (${(mb / 1048576).toFixed(1)} MB)`);
}

main().catch((e) => {
  console.error('[vendor] FAILED:', e.message);
  process.exit(1);
});
