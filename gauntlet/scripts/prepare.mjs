// First-run and update helper for the Windows launcher (start-gauntlet.bat); also works on macOS/Linux.
// Checks Node's version, installs dependencies when package-lock.json changed, rebuilds the dashboard when its
// source changed (so unzipping a new version over an old folder always shows the new dashboard), and reports
// whether Gauntlet is already running.
// Exit codes: 0 = ready to start · 3 = already running (just open the browser) · 1 = failed.
import { createHash } from 'node:crypto';
import { existsSync, readFileSync, readdirSync, statSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { spawnSync } from 'node:child_process';
import { request } from 'node:http';

const root = join(import.meta.dirname, '..');
const PORT = Number(process.env.GAUNTLET_PORT ?? 7777);

function say(msg) {
  console.log(`  ${msg}`);
}

const [major, minor] = process.versions.node.split('.').map(Number);
if (major < 22 || (major === 22 && minor < 18)) {
  say(`Your Node.js is version ${process.versions.node}; Gauntlet needs 22.18 or newer.`);
  say('Install the LTS version from https://nodejs.org (or run: winget install -e --id OpenJS.NodeJS.LTS), then try again.');
  process.exit(1);
}

/** Resolves true when something already answers on the Gauntlet port. */
function alreadyRunning() {
  return new Promise((resolve) => {
    const req = request({ host: '127.0.0.1', port: PORT, path: '/api/meta', timeout: 800 }, (res) => {
      res.resume();
      resolve(true);
    });
    req.on('error', () => resolve(false));
    req.on('timeout', () => (req.destroy(), resolve(false)));
    req.end();
  });
}

if (await alreadyRunning()) process.exit(3);

/** Hash of every file under the given paths (skipping build output and dependencies). */
function hashOf(paths) {
  const h = createHash('sha256');
  const walk = (p) => {
    if (!existsSync(p)) return;
    const st = statSync(p);
    if (st.isDirectory()) {
      for (const name of readdirSync(p).sort()) {
        if (name === 'node_modules' || name === 'dist') continue;
        walk(join(p, name));
      }
    } else {
      h.update(p.slice(root.length));
      h.update(readFileSync(p));
    }
  };
  for (const p of paths) walk(join(root, p));
  return h.digest('hex');
}

function stale(stampFile, hash) {
  try {
    return readFileSync(stampFile, 'utf8').trim() !== hash;
  } catch {
    return true;
  }
}

function npm(args) {
  // npm is npm.cmd on Windows; shell: true lets the system find it either way.
  const r = spawnSync('npm', args, { cwd: root, stdio: 'inherit', shell: true });
  return r.status === 0;
}

const depsHash = hashOf(['package.json', 'package-lock.json']);
const depsStamp = join(root, 'node_modules', '.gauntlet-deps');
if (!existsSync(join(root, 'node_modules')) || stale(depsStamp, depsHash)) {
  say('Installing Gauntlet\'s building blocks (first run or after an update). This takes a minute...');
  if (!npm(['install', '--no-audit', '--no-fund'])) process.exit(1);
  writeFileSync(depsStamp, depsHash);
}

// Everything the dashboard is built from: its own source plus the shared code it imports.
const uiHash = hashOf(['ui', 'src', 'config', 'tests', 'suites', 'package-lock.json']);
const uiStamp = join(root, 'ui', 'dist', '.gauntlet-build');
if (!existsSync(join(root, 'ui', 'dist', 'index.html')) || stale(uiStamp, uiHash)) {
  say('Building the dashboard (first run or after an update)...');
  if (!npm(['run', 'build:ui'])) process.exit(1);
  writeFileSync(uiStamp, uiHash);
}
process.exit(0);
