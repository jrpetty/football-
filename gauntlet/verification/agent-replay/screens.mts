// Screenshots of the agent / social simulation replays (Survival Island, Escape Room,
// Startup, Liar's Table) at 1920×1080: the inspector, the final frame, Broadcast mode
// and the light theme. Needs a run of all eight tests (e.g. with the Random Baseline).
// Usage: node screens.mts <baseUrl> <runId> <prefix> [outDir]
// Environment: ONLY=name,name to take a subset; CONTESTANT=<id> (default random-baseline).
import { chromium } from 'playwright-core';
import { mkdirSync, readdirSync, existsSync } from 'node:fs';
import { join } from 'node:path';

const base = process.argv[2] ?? 'http://127.0.0.1:7777';
const run = process.argv[3]!;
const prefix = process.argv[4] ?? 'after';
const out = process.argv[5] ?? join(import.meta.dirname, '..', '..', 'docs', 'screenshots', 'visual-pass', 'agent-sims');
const contestant = process.env.CONTESTANT ?? 'random-baseline';
mkdirSync(out, { recursive: true });

function chromiumPath(): string | undefined {
  const root = '/opt/pw-browsers';
  if (!existsSync(root)) return undefined;
  const dir = readdirSync(root).find((d) => /^chromium-\d+$/.test(d));
  return dir ? join(root, dir, 'chrome-linux', 'chrome') : undefined;
}

const browser = await chromium.launch({ executablePath: chromiumPath() });
const only = process.env.ONLY ? new Set(process.env.ONLY.split(',')) : null;

interface ShotOpts {
  broadcast?: boolean;
  theme?: 'light' | 'dark';
  /** Arrow-right presses in the replay after it loads. */
  steps?: number;
  /** Jump to the last frame. */
  end?: boolean;
  /** Press F (full screen) first. */
  fs?: boolean;
}

async function shot(name: string, url: string, o: ShotOpts = {}) {
  if (only && !only.has(name)) return;
  const page = await browser.newPage({ viewport: { width: 1920, height: 1080 } });
  const errors: string[] = [];
  page.on('pageerror', (e) => errors.push(e.message));
  page.on('console', (m) => {
    if (m.type() === 'error') errors.push(m.text());
  });
  await page.addInitScript(
    ({ broadcast, theme }) => {
      if (broadcast) sessionStorage.setItem('gauntlet.broadcast', '1');
      localStorage.setItem('gauntlet.theme', theme);
    },
    { broadcast: !!o.broadcast, theme: o.theme ?? 'dark' },
  );
  await page.goto(url);
  await page.waitForSelector('.replay', { timeout: 15000 }).catch(() => undefined);
  await page.waitForTimeout(1000);
  const rp = page.locator('.replay').first();
  if (await rp.count()) {
    await rp.focus();
    if (o.fs) {
      await page.keyboard.press('f');
      await page.waitForTimeout(600);
    }
    // Pause any auto-play, then position the replay.
    if (await page.locator('.replay .play-btn[aria-label="Pause"]').count()) await page.keyboard.press(' ');
    await page.keyboard.press(o.end ? 'End' : 'Home');
    for (let i = 0; i < (o.steps ?? 0); i++) await page.keyboard.press('ArrowRight');
  }
  await page.waitForTimeout(1600);
  await page.screenshot({ path: join(out, `${prefix}-${name}.png`) });
  if (errors.length) console.log(name, 'errors:', errors.slice(0, 5));
  await page.close();
}

const key = (test: string, seed: number) => encodeURIComponent(`${contestant}::${test}::seed-${seed}::r0`);
// MOCK=1: mock mode (?mock=1); the inspector opens the contestant's first case of the test.
const mockMode = process.env.MOCK === '1';
const at = (test: string, seed: number) =>
  mockMode ? `${base}/?mock=1#/runs/${run}?tab=matrix&test=${test}&c=${contestant}` : `${base}/#/runs/${run}?tab=matrix&test=${test}&c=${contestant}&key=${key(test, seed)}`;

const cases: Array<[string, string, number, number]> = [
  ['island', 'agentic.survival-island', 101, 7],
  ['island-hard', 'agentic.survival-island-hard', 101, 4],
  ['escape', 'agentic.escape-room', 101, 12],
  ['escape-hard', 'agentic.escape-room-hard', 101, 12],
  ['startup', 'agentic.startup-sim', 101, 6],
  ['startup-hard', 'agentic.startup-sim-hard', 101, 6],
  ['liars', 'social.liars-table', 606, 4],
  ['liars-hard', 'social.liars-table-hard', 606, 3],
];
// SEEDS="island=1,island-hard=25" picks other cases (e.g. the rescued islands of a scripted run).
for (const kv of (process.env.SEEDS ?? '').split(',').filter(Boolean)) {
  const [name, seed] = kv.split('=');
  const c = cases.find((x) => x[0] === name);
  if (c) c[2] = Number(seed);
}
// STEPS="island=20" changes how far into the replay the step shots are taken.
for (const kv of (process.env.STEPS ?? '').split(',').filter(Boolean)) {
  const [name, n] = kv.split('=');
  const c = cases.find((x) => x[0] === name);
  if (c) c[3] = Number(n);
}
for (const [name, test, seed, steps] of cases) {
  await shot(`${name}-inspector`, at(test, seed), { steps });
  await shot(`${name}-final`, at(test, seed), { end: true });
}
for (const [name, test, seed, steps] of cases.filter((c) => !c[0].endsWith('-hard'))) {
  await shot(`${name}-broadcast`, at(test, seed), { broadcast: true, steps });
  await shot(`${name}-fullscreen`, at(test, seed), { fs: true, steps });
  await shot(`${name}-light`, at(test, seed), { theme: 'light', steps });
}
await browser.close();
