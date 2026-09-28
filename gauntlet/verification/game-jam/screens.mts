// Screenshots of The Game Jam screens (mock mode) at 1920×1080: the cabinet wall, a game card in the wall,
// result inspector cards (working, frozen, cut off), and every Presenter jam slide, in normal, Broadcast and light.
// Usage: node verification/game-jam/screens.mts <baseUrl> [outDir] [only-substring]
// (server started with `node src/cli.ts serve --port <port>`)
import { chromium } from 'playwright-core';
import { existsSync, mkdirSync, readdirSync } from 'node:fs';
import { join } from 'node:path';

const base = process.argv[2] ?? 'http://127.0.0.1:7777';
const out = process.argv[3] ?? join(import.meta.dirname, '..', '..', 'docs', 'screenshots', 'game-jam');
const only = process.argv[4];
mkdirSync(out, { recursive: true });

function chromiumPath(): string | undefined {
  const root = '/opt/pw-browsers';
  if (!existsSync(root)) return undefined;
  const dir = readdirSync(root).find((d) => /^chromium-\d+$/.test(d));
  return dir ? join(root, dir, 'chrome-linux', 'chrome') : undefined;
}

const RUN = 'run-2026-09-28-game-jam';
const T = 'creative.game-jam';
const key = (c: string, cs: string) => `${c}::${T}::${cs}::r0`;
const insp = (c: string, cs: string) => `${base}/?mock=1#/runs/${RUN}?test=${encodeURIComponent(T)}&c=${encodeURIComponent(c)}&key=${encodeURIComponent(key(c, cs))}`;
const wall = (q = '') => `${base}/?mock=1#/runs/${RUN}/jam${q}`;

interface Shot {
  name: string;
  url: string;
  broadcast?: boolean;
  theme?: 'light' | 'dark';
  scroll?: number;
}

const shots: Shot[] = [
  { name: 'wall', url: wall() },
  { name: 'wall-broadcast', url: wall(), broadcast: true },
  { name: 'wall-light', url: wall(), theme: 'light' },
  { name: 'wall-card-flappy', url: wall(`?game=${encodeURIComponent(key('meridian-atlas-4-ultra', 'j1-flappy'))}`) },
  { name: 'wall-card-racing-broadcast', url: wall(`?game=${encodeURIComponent(key('meridian-atlas-4-ultra', 'j5-racing'))}`), broadcast: true },
  { name: 'inspector-flappy', url: insp('meridian-atlas-4-ultra', 'j1-flappy') },
  { name: 'inspector-flappy-lower', url: insp('meridian-atlas-4-ultra', 'j1-flappy'), scroll: 900 },
  { name: 'inspector-frozen-broadcast', url: insp('kestrel-kite-reasoner', 'j2-rts'), broadcast: true },
  { name: 'inspector-out-of-space-light', url: insp('kestrel-kite-reasoner', 'j4-zombie'), theme: 'light' },
  { name: 'inspector-racing-light', url: insp('meridian-atlas-4-ultra', 'j5-racing'), theme: 'light' },
  { name: 'run-detail', url: `${base}/?mock=1#/runs/${RUN}` },
];

const browser = await chromium.launch({ executablePath: chromiumPath() });

async function shoot(s: Shot) {
  const page = await browser.newPage({ viewport: { width: 1920, height: 1080 } });
  const errors: string[] = [];
  page.on('pageerror', (e) => errors.push(e.message));
  await page.addInitScript(
    ({ broadcast, theme }) => {
      sessionStorage.setItem('gauntlet.broadcast', broadcast ? '1' : '0');
      localStorage.setItem('gauntlet.theme', theme ?? 'dark');
    },
    { broadcast: !!s.broadcast, theme: s.theme },
  );
  await page.goto(s.url);
  await page.waitForSelector('.page, .deck-stage, main', { timeout: 15000 }).catch(() => undefined);
  await page.waitForTimeout(2600);
  if (s.scroll) {
    await page.evaluate((y) => {
      const el = document.querySelector('.inspector-body, .drawer-body, .insp-body, main') as HTMLElement | null;
      const scrollers = Array.from(document.querySelectorAll('*')).filter((e) => (e as HTMLElement).scrollHeight > (e as HTMLElement).clientHeight + 50 && getComputedStyle(e).overflowY !== 'visible') as HTMLElement[];
      for (const x of scrollers) x.scrollTop = y;
      el?.scrollBy(0, 0);
      window.scrollTo(0, y);
    }, s.scroll);
    await page.waitForTimeout(800);
  }
  await page.screenshot({ path: join(out, `${s.name}.png`) });
  if (errors.length) console.log(s.name, 'errors:', errors);
  await page.close();
}

for (const s of shots) if (!only || s.name.includes(only)) await shoot(s);

// Presenter: every Game Jam slide of the demo run (dark), plus one of each in Broadcast / light.
if (!only || only.startsWith('present')) {
  const probe = await browser.newPage({ viewport: { width: 1920, height: 1080 } });
  let n = 0;
  for (let s = 1; s <= 30; s++) {
    await probe.goto(`${base}/?mock=1#/present/${RUN}?s=${s}`);
    await probe.waitForTimeout(450);
    const kind = await probe.locator('.deck-stage').first().getAttribute('data-slide').catch(() => null);
    if (kind === 'jam-genre' || kind === 'jam-winner' || kind === 'intro' || kind === 'result') {
      n++;
      await shoot({ name: `present-${String(s).padStart(2, '0')}-${kind}`, url: `${base}/?mock=1#/present/${RUN}?s=${s}` });
      if (kind === 'jam-winner') await shoot({ name: `present-${String(s).padStart(2, '0')}-${kind}-light`, url: `${base}/?mock=1#/present/${RUN}?s=${s}`, theme: 'light' });
      if (s === 5) await shoot({ name: `present-${String(s).padStart(2, '0')}-${kind}-light`, url: `${base}/?mock=1#/present/${RUN}?s=${s}`, theme: 'light' });
    }
  }
  console.log(`${n} presenter slides`);
  await probe.close();
}
await browser.close();
