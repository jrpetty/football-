// Screenshots of the long-context, drawing and picture visuals (mock mode) at 1920×1080.
// Usage: node verification/visual-pass/screens.mts <baseUrl> <outDir> [only-substring]
// (server started with `node src/cli.ts serve --port <port>`)
import { chromium } from 'playwright-core';
import { mkdirSync, readdirSync, existsSync } from 'node:fs';
import { join } from 'node:path';

const base = process.argv[2] ?? 'http://127.0.0.1:7777';
const out = process.argv[3] ?? join(import.meta.dirname, '..', '..', 'docs', 'screenshots', 'visual-pass', 'long-context-drawing-vision', 'after');
const only = process.argv[4];
mkdirSync(out, { recursive: true });

function chromiumPath(): string | undefined {
  const root = '/opt/pw-browsers';
  if (!existsSync(root)) return undefined;
  const dir = readdirSync(root).find((d) => /^chromium-\d+$/.test(d));
  return dir ? join(root, dir, 'chrome-linux', 'chrome') : undefined;
}

const RUN = 'run-2026-09-25-longctx-drawing';
const VRUN = 'run-2026-09-22-vision';
const key = (c: string, t: string, cs: string) => `${c}::${t}::${cs}::r0`;
const insp = (run: string, c: string, t: string, cs: string) => `${base}/?mock=1#/runs/${run}?test=${encodeURIComponent(t)}&c=${encodeURIComponent(c)}&key=${encodeURIComponent(key(c, t, cs))}`;

interface Shot {
  name: string;
  url: string;
  broadcast?: boolean;
  theme?: 'light' | 'dark';
  /** Replay steps forward (ArrowRight on the replay). */
  steps?: number;
  /** Click a tab / button with this exact text first. */
  click?: string[];
  /** Presenter: right-arrow presses. */
  presses?: number;
  scrollTo?: string;
  /** Inspector: open the first attempt of this case that scored 0 (a wrong answer). */
  wrong?: string;
}

const shots: Shot[] = [
  { name: 'needle-replay', url: insp(RUN, 'helios-nova-3-pro', 'long-context.needle-haystack', 'seed-101'), steps: 7 },
  { name: 'needle-replay-broadcast', url: insp(RUN, 'helios-quill-flash', 'long-context.needle-haystack-hard', 'seed-101'), steps: 5, broadcast: true },
  { name: 'needle-replay-light', url: insp(RUN, 'meridian-atlas-4-ultra', 'long-context.needle-haystack', 'seed-101'), steps: 9, theme: 'light' },
  { name: 'whispers-replay', url: insp(RUN, 'kestrel-kite-reasoner', 'long-context.chain-of-whispers', 'seed-404'), steps: 3 },
  { name: 'whispers-final-broadcast', url: insp(RUN, 'helios-quill-flash', 'long-context.chain-of-whispers', 'seed-404'), steps: 6, broadcast: true },
  { name: 'whispers-hard-replay', url: insp(RUN, 'meridian-atlas-4-ultra', 'long-context.chain-of-whispers-hard', 'seed-404'), steps: 4 },
  { name: 'draw-describe', url: insp(RUN, 'helios-quill-flash', 'visual.draw-it-blind', 'seed-5'), steps: 0 },
  { name: 'draw-compare', url: insp(RUN, 'helios-nova-3-pro', 'visual.draw-it-blind', 'seed-5'), steps: 1 },
  { name: 'draw-shape-broadcast', url: insp(RUN, 'helios-nova-3-pro', 'visual.draw-it-blind', 'seed-5'), steps: 3, broadcast: true },
  { name: 'draw-hard-light', url: insp(RUN, 'meridian-atlas-4-ultra', 'visual.draw-it-blind-hard', 'seed-101'), steps: 1, theme: 'light' },
  { name: 'svg-clock', url: insp(RUN, 'helios-nova-3-pro', 'visual.svg-illustration', 'v02') },
  { name: 'svg-chess-broadcast', url: insp(RUN, 'helios-nova-3-pro', 'visual.svg-illustration', 'v04'), broadcast: true },
  { name: 'svg-bars-light', url: insp(RUN, 'helios-quill-flash', 'visual.svg-illustration', 'v03'), theme: 'light' },
  { name: 'game-broken', url: insp(RUN, 'helios-quill-flash', 'creative.one-shot-games', 'g01') },
  { name: 'game-good-broadcast', url: insp(RUN, 'meridian-atlas-4-ultra', 'creative.one-shot-games', 'g03'), broadcast: true },
  { name: 'vision-spot', url: insp(VRUN, 'helios-quill-flash', 'vision.spot-the-difference', 's01') },
  { name: 'vision-spot-typed', url: insp(VRUN, 'helios-nova-3-pro', 'vision.spot-the-difference', 's04') },
  { name: 'vision-count-scatter', url: insp(VRUN, 'helios-quill-flash', 'vision.count-and-locate', 'k01'), wrong: 'k01' },
  { name: 'vision-count-board-broadcast', url: insp(VRUN, 'helios-quill-flash', 'vision.count-and-locate', 'k02'), broadcast: true },
  { name: 'vision-chart', url: insp(VRUN, 'helios-quill-flash', 'vision.read-the-chart', 'c01') },
  { name: 'vision-chart-lines-light', url: insp(VRUN, 'meridian-atlas-4-ultra', 'vision.read-the-chart', 'c03'), theme: 'light' },
  { name: 'vision-handwriting', url: insp(VRUN, 'helios-quill-flash', 'vision.handwritten-maths', 'h01'), wrong: 'h01' },
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
  await page.waitForTimeout(2200);
  if (s.wrong) {
    const item = page.locator('.insp-item', { hasText: s.wrong }).filter({ hasText: /\b0%/ }).first();
    if (await item.count()) {
      await item.click();
      await page.waitForTimeout(900);
    } else console.log(s.name, `no wrong attempt of ${s.wrong}; showing the first one`);
  }
  for (const text of s.click ?? []) {
    await page.getByRole('tab', { name: text }).first().click().catch(() => page.getByText(text, { exact: true }).first().click().catch(() => undefined));
    await page.waitForTimeout(600);
  }
  if (s.steps !== undefined) {
    const replay = page.locator('.replay').first();
    if (await replay.count()) {
      await replay.focus();
      await page.keyboard.press('Home');
      // Broadcast mode auto-plays: pause first.
      if (s.broadcast) await page.keyboard.press(' ');
      await page.keyboard.press('Home');
      for (let i = 0; i < s.steps; i++) {
        await page.keyboard.press('ArrowRight');
        await page.waitForTimeout(150);
      }
    }
  }
  for (let i = 0; i < (s.presses ?? 0); i++) {
    await page.keyboard.press('ArrowRight');
    await page.waitForTimeout(500);
  }
  await page.waitForTimeout(1200);
  await page.screenshot({ path: join(out, `${s.name}.png`) });
  if (errors.length) console.log(s.name, 'errors:', errors);
  await page.close();
}

for (const s of shots) if (!only || s.name.includes(only)) await shoot(s);

// Presenter: every "answer vs truth" slide of the demo runs.
if (!only || only.startsWith('present')) {
  for (const run of [RUN, VRUN]) {
    const probe = await browser.newPage({ viewport: { width: 1920, height: 1080 } });
    let n = 0;
    for (let s = 1; s <= 40; s++) {
      await probe.goto(`${base}/?mock=1#/present/${run}?s=${s}`);
      await probe.waitForTimeout(400);
      if ((await probe.locator('.deck-stage[data-slide="moment"]').count()) > 0) {
        const kind = (await probe.locator('[data-moment]').first().getAttribute('data-moment').catch(() => null)) ?? String(s);
        await shoot({ name: `present-${kind}-${++n}`, url: `${base}/?mock=1#/present/${run}?s=${s}` });
      }
    }
    await probe.close();
  }
}
await browser.close();
