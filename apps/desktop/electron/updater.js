// Self-update file replacement (pure Node — no Electron imports, so this can
// be unit-tested with plain `node`).
//
// The portable launcher extracts the app to a temp dir and runs it from
// there, so process.execPath is NOT the user's downloaded exe and argv is not
// reliable. Electron exposes the real portable exe path via the
// PORTABLE_EXECUTABLE_FILE env var.
//
// Quit cannot be delayed until a full copy finishes: the user (or the OS)
// may kill the process earlier, leaving the exe half-written. So applying an
// update is two steps: (1) spawn the freshly downloaded portable and quit,
// writing a marker file first; (2) on the next boot, copy the downloaded exe
// over the original (the old process is gone by then).
const fs = require('node:fs/promises');
const path = require('node:path');
const os = require('node:os');

// Only ever replace the portable exe we shipped (Awitize-<version>-win.exe) —
// never an arbitrary target.
const REPLACE_BASENAME = /Awitize-.*-win\.exe$/i;
const MARKER_NAME = 'awitize-replace.json';
const MARKER_MAX_AGE_MS = 30 * 60 * 1000;

function markerPath() {
  return path.join(os.tmpdir(), MARKER_NAME);
}

function validateReplaceArgs(source, target) {
  const isAbs = (p) => typeof p === 'string' && p.length > 0 && path.isAbsolute(p);
  const okName = (p) => isAbs(p) && REPLACE_BASENAME.test(path.basename(p));
  if (!okName(source) || !okName(target)) {
    throw new Error('update paths failed validation');
  }
  if (path.resolve(source) === path.resolve(target)) {
    throw new Error('update source and target are the same file');
  }
  return { source, target };
}

async function writeReplaceMarker(source, target) {
  validateReplaceArgs(source, target);
  await fs.writeFile(markerPath(), JSON.stringify({ source, target, at: Date.now() }), 'utf8');
}

async function readReplaceMarker() {
  try {
    const parsed = JSON.parse(await fs.readFile(markerPath(), 'utf8'));
    if (Date.now() - Number(parsed.at) > MARKER_MAX_AGE_MS) return null;
    return validateReplaceArgs(parsed.source, parsed.target);
  } catch {
    return null;
  }
}

async function clearReplaceMarker() {
  try {
    await fs.unlink(markerPath());
  } catch {
    /* already gone */
  }
}

// target is the user's real portable exe; it may still be locked for a few
// hundred ms after the old process exited, and antivirus can hold it briefly,
// so retry the copy before giving up.
async function replaceOriginalExe(target, source) {
  try {
    if (path.resolve(target) === path.resolve(source)) return false;
    const stat = await fs.stat(source);
    if (!stat.size || stat.size < 10 * 1024 * 1024) throw new Error('downloaded update looks truncated');
    let lastErr = null;
    for (let i = 0; i < 12; i += 1) {
      try {
        await fs.copyFile(source, target);
        return true;
      } catch (err) {
        lastErr = err;
        await new Promise((r) => setTimeout(r, 250));
      }
    }
    throw lastErr;
  } finally {
    await clearReplaceMarker();
  }
}

module.exports = {
  REPLACE_BASENAME,
  validateReplaceArgs,
  writeReplaceMarker,
  readReplaceMarker,
  clearReplaceMarker,
  replaceOriginalExe,
};
