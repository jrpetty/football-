// Screenshots of the Presenter "game show" pass (mock mode) at 1920×1080:
// bar-race frames, the podium reveal, reference lines, sound flash and auto ring.
// Usage: node verification/show/screens.mts <baseUrl> [outDir]
//   (server started with `node src/cli.ts serve --port <port>`)
import { chromium } from 'playwright-core';
import type { Page } from 'playwright-core';
import { mkdirSync, readdirSync, existsSync } from 'node:fs';
import { join } from 'node:path';

const base = process.argv[2] ?? 'http://127.0.0.1:7841';
const out = process.argv[3] ?? join(import.meta.dirname, '..', '..', 'docs', 'screenshots', 'next-level', 'show');
const only = process.argv[4];
mkdirSync(out, { recursive: true });

function chromiumPath(): string | undefined {
  const root = '/opt/pw-browsers';
  if (!existsSync(root)) return undefined;
  const dir = readdirSync(root).find((d) => /^chromium-\d+$/.test(d));
  return dir ? join(root, dir, 'chrome-linux', 'chrome') : undefined;
}

const RUN = process.env.RUN ?? 'run-2026-09-21-core';
const browser = await chromium.launch({ executablePath: chromiumPath() });

interface Opts {
  broadcast?: boolean;
  theme?: 'light' | 'dark';
  reduced?: boolean;
  /** Screenshots at these ms after the slide opened (or after the last press). */
  frames?: Array<[string, number]>;
  presses?: number;
  pressGap?: number;
  before?: (p: Page) => Promise<void>;
  query?: string;
}

async function open(slide: number, o: Opts): Promise<Page> {
  const page = await browser.newPage({ viewport: { width: 1920, height: 1080 }, reducedMotion: o.reduced ? 'reduce' : 'no-preference' });
  page.on('pageerror', (e) => console.log('pageerror', e.message));
  await page.addInitScript(
    ({ broadcast, theme }) => {
      if (broadcast) sessionStorage.setItem('gauntlet.broadcast', '1');
      if (theme) localStorage.setItem('gauntlet.theme', theme);
    },
    { broadcast: !!o.broadcast, theme: o.theme },
  );
  // Land on the slide before, then step forward, so the slide plays its entrance like on camera.
  await page.goto(`${base}/?mock=1#/present/${RUN}?s=${slide - 1}${o.query ?? ''}`);
  await page.waitForSelector('.deck-stage');
  await page.waitForTimeout(1500);
  // Leave the reveal steps of the previous slide.
  await page.goto(`${base}/?mock=1#/present/${RUN}?s=${slide}${o.query ?? ''}`);
  return page;
}

async function shoot(name: string, slide: number, o: Opts = {}) {
  if (only && !name.includes(only)) return;
  const page = await open(slide, o);
  await page.waitForSelector('.deck-stage');
  if (o.before) await o.before(page);
  for (let i = 0; i < (o.presses ?? 0); i++) {
    await page.waitForTimeout(o.pressGap ?? 900);
    await page.keyboard.press('ArrowRight');
  }
  const t0 = Date.now();
  for (const [suffix, at] of o.frames ?? [['', 2600]]) {
    const wait = at - (Date.now() - t0);
    if (wait > 0) await page.waitForTimeout(wait);
    await page.screenshot({ path: join(out, `${name}${suffix}.png`) });
  }
  await page.close();
}

// BEFORE=1 against a server running the previous build: the slides this pass changed.
if (process.env.BEFORE === '1') {
  const FINAL = Number(process.env.FINAL_SLIDE ?? 57);
  await shoot('before-result', 4, { frames: [['', 2600]] });
  await shoot('before-final-table', FINAL, { presses: 6, pressGap: 500, frames: [['', 2200]] });
  await shoot('before-scatter', FINAL + 1, { frames: [['', 2200]] });
  await browser.close();
  process.exit(0);
}

const RACE = Number(process.env.RACE_SLIDE ?? 8);
const PODIUM = Number(process.env.PODIUM_SLIDE ?? 80);

// Bar race: previous standings → bars growing → rows re-sorting → called out.
await shoot('race', RACE, {
  frames: [
    ['-1-previous', 350],
    ['-2-bars-growing', 1350],
    ['-3-reordering', 2500],
    ['-4-final', 4200],
  ],
});
await shoot('race-late', Number(process.env.RACE_LATE_SLIDE ?? 57), { frames: [['-final', 4200]] });
await shoot('race-broadcast-light', RACE, { broadcast: true, theme: 'light', frames: [['', 4200]] });
await shoot('race-reduced-motion', RACE, { reduced: true, frames: [['', 800]] });

// Podium reveal: intro, 6th…4th, 3rd, 2nd, drumroll, winner (+ mid count-up and confetti frames).
await shoot('podium-0-intro', PODIUM, { frames: [['', 1400]] });
await shoot('podium-1-last-place', PODIUM, { presses: 1, frames: [['-counting', 500], ['', 2400]] });
await shoot('podium-3-fourth', PODIUM, { presses: 3, pressGap: 700, frames: [['', 2400]] });
await shoot('podium-4-third', PODIUM, { presses: 4, pressGap: 700, frames: [['', 2400]] });
await shoot('podium-5-second', PODIUM, { presses: 5, pressGap: 700, frames: [['', 2400]] });
await shoot('podium-6-drumroll', PODIUM, { presses: 6, pressGap: 700, frames: [['', 1200]] });
await shoot('podium-7-winner', PODIUM, { presses: 7, pressGap: 700, frames: [['-confetti', 700], ['-confetti-2', 1600], ['', 6000]] });
await shoot('podium-7-winner-broadcast-light', PODIUM, { broadcast: true, theme: 'light', presses: 7, pressGap: 600, frames: [['', 1500]] });
await shoot('podium-7-winner-reduced-motion', PODIUM, { reduced: true, presses: 7, pressGap: 500, frames: [['', 800]] });

// Reference lines on existing slides (after): result bars, final table, scatter.
await shoot('ref-result', 4, { frames: [['', 2600]] });
await shoot('ref-final-table', PODIUM + 1, { frames: [['', 2200]] });
await shoot('ref-scatter', PODIUM + 2, { frames: [['', 2200]] });

// Sound flash (S) with auto-play ring running.
await shoot('sound-flash-auto-ring', 4, {
  before: async (p) => {
    await p.waitForTimeout(600);
    await p.keyboard.press('a');
    await p.waitForTimeout(2200);
    await p.keyboard.press('s');
  },
  frames: [['', 250]],
});
// Every sound effect fires without errors (sound on, then the whole podium reveal + a trick-free bar race).
await shoot('sound-podium-winner', PODIUM, {
  before: async (p) => {
    await p.keyboard.press('s');
    const state = await p.evaluate(() => localStorage.getItem('gauntlet.present.sfx'));
    if (state !== '1') console.log('sound setting not remembered:', state);
  },
  presses: 7,
  pressGap: 800,
  frames: [['', 1200]],
});
// Auto-play really advances past a bar race (hold ≈ 8.5 s + animation) and reports where it got to.
if (!only || only === 'auto') {
  const p = await open(RACE, {});
  await p.waitForSelector('.deck-stage[data-slide="standings"]');
  await p.keyboard.press('a');
  await p.waitForTimeout(12000);
  console.log('auto-play: after 12 s on the race slide we are on', await p.locator('.deck-stage').getAttribute('data-slide'));
  await p.close();
}
await shoot('help-keys', 4, {
  before: async (p) => {
    await p.waitForTimeout(600);
    await p.keyboard.press('?');
  },
  frames: [['', 600]],
});

await browser.close();
