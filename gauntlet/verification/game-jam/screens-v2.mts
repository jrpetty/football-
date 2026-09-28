// Screenshots for the Game Jam v2 upgrade (mock mode, 1920×1080): New Run with the pound spending-limit presets and
// estimate, the live spend in pounds (run page, Watch it think, overlay), a run stopped by its spending limit, the
// Cost Planner, the new scorecard, and the Presenter (jam slides and the methods line with the limits).
// Usage: node verification/game-jam/screens-v2.mts <baseUrl> [outDir] [only-substring]
import { chromium, type Page } from 'playwright-core';
import { existsSync, mkdirSync, readdirSync } from 'node:fs';
import { join } from 'node:path';

const base = process.argv[2] ?? 'http://127.0.0.1:7777';
const out = process.argv[3] ?? join(import.meta.dirname, '..', '..', 'docs', 'screenshots', 'game-jam-v2');
const only = process.argv[4];
mkdirSync(out, { recursive: true });

function chromiumPath(): string | undefined {
  const root = '/opt/pw-browsers';
  if (!existsSync(root)) return undefined;
  const dir = readdirSync(root).find((d) => /^chromium-\d+$/.test(d));
  return dir ? join(root, dir, 'chrome-linux', 'chrome') : undefined;
}

const JAM = 'run-2026-09-28-game-jam';
const LIVE = 'run-2026-09-28-live';
const T = 'creative.game-jam';
const key = (c: string, cs: string) => `${c}::${T}::${cs}::r0`;
const insp = (c: string, cs: string) => `${base}/?mock=1#/runs/${JAM}?test=${encodeURIComponent(T)}&c=${encodeURIComponent(c)}&key=${encodeURIComponent(key(c, cs))}`;

interface Shot {
  name: string;
  url: string;
  broadcast?: boolean;
  theme?: 'light' | 'dark';
  act?: (p: Page) => Promise<void>;
}

async function limits(p: Page, opts: { cap?: string; perAnswer?: string; same?: boolean }) {
  const pickers = p.locator('.sl-picker');
  await p.locator('.sl-section').scrollIntoViewIfNeeded();
  if (opts.cap) await pickers.nth(0).getByRole('button', { name: opts.cap, exact: true }).click();
  if (opts.perAnswer) await pickers.nth(1).getByRole('button', { name: opts.perAnswer, exact: true }).click();
  if (opts.same) await p.getByRole('button', { name: 'Same token limit for every model' }).click();
  await p.waitForTimeout(1500);
  await p.evaluate(() => {
    const el = document.querySelector('.sl-section');
    el?.closest('section')?.scrollIntoView({ block: 'end' });
  });
  await p.waitForTimeout(500);
}

const shots: Shot[] = [
  { name: 'new-run-pound-presets', url: `${base}/?mock=1#/run/new?tests=${T}`, act: (p) => limits(p, { cap: '£30', perAnswer: '£2' }) },
  { name: 'new-run-same-tokens-light', url: `${base}/?mock=1#/run/new?tests=${T}`, theme: 'light', act: (p) => limits(p, { cap: '£10', same: true }) },
  { name: 'new-run-no-limit-broadcast', url: `${base}/?mock=1#/run/new?tests=${T}`, broadcast: true, act: (p) => limits(p, {}) },
  { name: 'run-live-spend', url: `${base}/?mock=1#/runs/${LIVE}` },
  { name: 'watch-live-spend', url: `${base}/?mock=1#/runs/${LIVE}/watch` },
  { name: 'overlay-live-spend', url: `${base}/?mock=1#/overlay/${LIVE}?view=live` },
  { name: 'run-stopped-spend-limit', url: `${base}/?mock=1#/runs/run-2026-08-30-core` },
  { name: 'cost-planner-games', url: `${base}/?mock=1#/costs?suite=games&repeats=1` },
  { name: 'game-jam-run-limits', url: `${base}/?mock=1#/runs/${JAM}` },
  { name: 'scorecard-flappy', url: insp('meridian-atlas-4-ultra', 'j1-flappy') },
  { name: 'scorecard-flappy-motion-strip', url: insp('meridian-atlas-4-ultra', 'j1-flappy'), act: async (p) => { await p.locator('.jam-motion').first().scrollIntoViewIfNeeded(); await p.waitForTimeout(800); } },
  { name: 'new-run-estimate', url: `${base}/?mock=1#/run/new?tests=${T}`, act: async (p) => { await limits(p, { cap: '£30' }); await p.evaluate(() => { for (const e of Array.from(document.querySelectorAll('*')) as HTMLElement[]) if (e.scrollTop) e.scrollTop = 0; window.scrollTo(0, 0); }); await p.waitForTimeout(600); } },
  { name: 'scorecard-racing-light', url: insp('meridian-atlas-4-ultra', 'j5-racing'), theme: 'light' },
  { name: 'arcade-wall', url: `${base}/?mock=1#/runs/${JAM}/jam` },
  { name: 'explainer', url: `${base}/?mock=1#/tests/${T}` },
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
  if (s.act) await s.act(page);
  await page.screenshot({ path: join(out, `${s.name}.png`) });
  if (errors.length) console.log(s.name, 'errors:', errors);
  await page.close();
}

for (const s of shots) if (!only || s.name.includes(only)) await shoot(s);

// Presenter: the first jam genre slide, the winner slide, and the methods slide with the limits line.
if (!only || only.startsWith('present')) {
  const probe = await browser.newPage({ viewport: { width: 1920, height: 1080 } });
  const want = new Set(['jam-genre', 'jam-winner', 'outro']);
  for (let s = 1; s <= 40 && want.size; s++) {
    await probe.goto(`${base}/?mock=1#/present/${JAM}?s=${s}`);
    await probe.waitForTimeout(450);
    const kind = await probe.locator('.deck-stage').first().getAttribute('data-slide').catch(() => null);
    if (kind && want.has(kind)) {
      want.delete(kind);
      await shoot({ name: `present-${kind}`, url: `${base}/?mock=1#/present/${JAM}?s=${s}` });
      if (kind === 'jam-genre') await shoot({ name: `present-${kind}-broadcast`, url: `${base}/?mock=1#/present/${JAM}?s=${s}`, broadcast: true });
    }
  }
  await probe.close();
}
await browser.close();
