/**
 * Screenshots of the Grading Station, the output viewer and the 30-word summaries, in mock mode (no API keys).
 *
 *   node src/cli.ts serve --port 7843 &   # with a built UI (npm run build:ui)
 *   node verification/grading/screenshots.mjs http://127.0.0.1:7843 docs/screenshots/grading
 */
import { mkdirSync, existsSync, readdirSync } from 'node:fs';
import { join } from 'node:path';

const [base = 'http://127.0.0.1:7843', out = 'docs/screenshots/grading'] = process.argv.slice(2);
mkdirSync(out, { recursive: true });
const { chromium } = await import('playwright-core');
const exe = process.env.GAUNTLET_CHROMIUM ?? (existsSync('/opt/pw-browsers') ? readdirSync('/opt/pw-browsers').filter((d) => /^chromium-\d/.test(d)).map((d) => join('/opt/pw-browsers', d, 'chrome-linux', 'chrome'))[0] : undefined);
const browser = await chromium.launch({ executablePath: exe });

const K = (key, run = 'run-grading-demo') => `${base}/?mock=1#/grading?run=${run}&key=${encodeURIComponent(key)}&show=all`;
const GAME = K('helios-nova-3-pro::creative.one-shot-games::g01::r0');
const JAM = K('meridian-atlas-4-ultra::creative.game-jam::j1-flappy::r0', 'run-2026-09-28-game-jam');
const PAINTING = K('meridian-canvas-2::art.gallery-masterpiece::seed-1::r0', 'run-2026-09-28-gallery');
const SVG_PAINTING = K('meridian-atlas-4-ultra::art.gallery-painted-in-code::seed-2::r0', 'run-2026-09-28-gallery');
const shots = [
  // name, url, mode, steps
  ['station-game', GAME, 'dark', [{ key: '3' }, { key: '1' }, { key: '1' }, { key: '1' }, { key: '0' }, { wait: 300 }]],
  ['station-game-broadcast', GAME, 'broadcast', [{ key: '3' }, { key: '1' }, { key: '1' }, { wait: 300 }]],
  ['station-game-light', GAME, 'light', [{ key: '3' }, { key: '1' }, { wait: 300 }]],
  ['station-game-playing', GAME, 'dark', [{ key: 'p' }, { wait: 1200 }, { click: '.uv-html-stage' }, { wait: 1500 }]],
  ['station-svg-both', K('meridian-atlas-4-ultra::visual.svg-illustration::v01::r0'), 'dark', [{ click: 'button[title*="side by side"]' }, { wait: 300 }, { key: '1' }, { key: '1' }, { key: '2' }, { key: '1' }, { key: '1' }, { key: '1' }, { key: '2' }, { wait: 300 }]],
  ['station-gamejam', JAM, 'dark', [{ key: '8' }, { key: '7' }, { wait: 300 }]],
  ['station-gamejam-broadcast', JAM, 'broadcast', [{ key: '8' }, { wait: 300 }]],
  ['station-gamejam-light', JAM, 'light', [{ key: '8' }, { wait: 300 }]],
  ['station-gamejam-ai-cost', JAM, 'dark', [{ click: 'button[title*="AI judges from"]' }, { wait: 300 }, { key: 'a' }, { wait: 1200 }]],
  ['station-gallery', PAINTING, 'dark', [{ key: '8' }, { key: '7' }, { key: '8' }, { wait: 300 }]],
  ['station-gallery-broadcast', PAINTING, 'broadcast', [{ key: '8' }, { wait: 300 }]],
  ['station-gallery-light', PAINTING, 'light', [{ key: '8' }, { wait: 300 }]],
  ['station-gallery-both', PAINTING, 'dark', [{ click: 'button[title*="side by side"]' }, { wait: 300 }, ...Array.from({ length: 6 }, () => ({ key: '7' })), { wait: 300 }]],
  ['station-gallery-code', SVG_PAINTING, 'dark', [{ wait: 600 }]],
  ['station-image-chart', K('meridian-atlas-4-ultra::vision.read-the-chart::c01::r0'), 'dark', [{ scroll: '.gs-brief' }]],
  ['station-json-key', K('helios-nova-3-pro::extraction.structured-json::e01::r0'), 'dark', []],
  ['station-json-key-light', K('helios-nova-3-pro::extraction.structured-json::e01::r0'), 'light', []],
  ['station-sim-replay-broadcast', K('kestrel-kite-reasoner::agentic.escape-room::seed-1::r0'), 'broadcast', [{ wait: 800 }]],
  ['station-sim-replay', K('kestrel-kite-reasoner::agentic.escape-room::seed-1::r0'), 'dark', [{ wait: 800 }]],
  ['station-honesty-labels', K('kestrel-kite-reasoner::honesty.honesty-trap::r01::r0'), 'dark', [{ key: '1' }, { wait: 200 }]],
  ['station-ai-cost', K('kestrel-kite-reasoner::honesty.honesty-trap::r01::r0'), 'dark', [{ click: 'button[title*="AI judges from"]' }, { wait: 300 }, { key: 'a' }, { wait: 900 }]],
  ['station-ai-verdicts', K('kestrel-kite-reasoner::honesty.honesty-trap::r01::r0'), 'dark', [{ click: 'button[title*="side by side"]' }, { wait: 300 }, { key: 'a' }, { wait: 900 }, { click: '.modal-foot .btn.primary' }, { wait: 1800 }, { key: '1' }, { wait: 300 }]],
  ['station-shortcuts', GAME, 'dark', [{ key: '?' }, { wait: 300 }]],
  ['station-media-pdf', K('meridian-atlas-4-ultra::creative.launch-kit::k1::r0'), 'dark', [{ click: '.uv-tab[title="launch-plan.pdf"]' }, { wait: 2500 }, { scroll: '.gs-item-head' }]],
  ['station-media-audio', K('meridian-atlas-4-ultra::creative.launch-kit::k1::r0'), 'dark', [{ click: '.uv-tab[title="jingle.wav"]' }, { wait: 1500 }, { scroll: '.gs-item-head' }]],
  ['station-media-video', K('meridian-atlas-4-ultra::creative.launch-kit::k1::r0'), 'dark', [{ click: '.uv-tab[title="trailer.webm"]' }, { wait: 1500 }, { scroll: '.gs-item-head' }]],
  ['station-media-light', K('meridian-atlas-4-ultra::creative.launch-kit::k1::r0'), 'light', [{ click: '.uv-tab[title="press-sheet.csv"]' }, { wait: 800 }, { scroll: '.gs-item-head' }]],
  ['run-detail-summaries', `${base}/?mock=1#/runs/run-2026-09-12-creative?tab=matrix`, 'dark', [{ scroll: '.sum-toolbar' }]],
  ['run-detail-summaries-broadcast', `${base}/?mock=1#/runs/run-2026-09-12-creative?tab=matrix`, 'broadcast', [{ scroll: '.sum-toolbar' }]],
  ['run-detail-summaries-light', `${base}/?mock=1#/runs/run-2026-09-12-creative?tab=matrix`, 'light', [{ scroll: '.sum-toolbar' }]],
  ['inspector-summary', `${base}/?mock=1#/runs/run-2026-09-12-creative?tab=matrix&test=creative.one-shot-game&c=helios-nova-3-pro`, 'dark', [{ wait: 800 }]],
  ['presenter-summaries', `${base}/?mock=1#/present/run-2026-09-12-creative`, 'dark', [{ key: 'ArrowRight' }, { wait: 300 }, { key: 'ArrowRight' }, { wait: 300 }, { key: 'ArrowRight' }, { wait: 2600 }]],
  ['presenter-summaries-broadcast', `${base}/?mock=1#/present/run-2026-09-12-creative`, 'broadcast', [{ key: 'ArrowRight' }, { wait: 300 }, { key: 'ArrowRight' }, { wait: 300 }, { key: 'ArrowRight' }, { wait: 2600 }]],
  ['presenter-summaries-light', `${base}/?mock=1#/present/run-2026-09-12-creative`, 'light', [{ key: 'ArrowRight' }, { wait: 300 }, { key: 'ArrowRight' }, { wait: 300 }, { key: 'ArrowRight' }, { wait: 2600 }]],
  ['studio-facts', `${base}/?mock=1#/studio/run-2026-09-12-creative`, 'dark', [{ click: 'button[role="tab"]:has-text("Video script")' }, { wait: 800 }]],
  ['inbox-attach-files', `${base}/?mock=1#/inbox`, 'dark', [{ wait: 800 }]],
];

const errors = [];
for (const [name, url, mode, steps] of shots) {
  const ctx = await browser.newContext({ viewport: { width: 1920, height: 1080 } });
  await ctx.addInitScript((m) => {
    try {
      if (m === 'broadcast') sessionStorage.setItem('gauntlet.broadcast', '1');
      localStorage.setItem('gauntlet.theme', m === 'light' ? 'light' : 'dark');
      localStorage.setItem('gauntlet.rater', '"mika"');
    } catch {}
  }, mode);
  const page = await ctx.newPage();
  page.on('pageerror', (e) => errors.push(`${name}: ${e}`));
  await page.goto(url);
  await page.waitForTimeout(2600);
  for (const s of steps) {
    if (s.click) await page.click(s.click, { timeout: 8000 }).catch((e) => errors.push(`${name}: ${e.message.split('\n')[0]}`));
    if (s.key) await page.keyboard.press(s.key);
    if (s.wait) await page.waitForTimeout(s.wait);
    if (s.scroll) await page.evaluate((sel) => document.querySelector(sel)?.scrollIntoView({ block: 'start' }), s.scroll);
  }
  await page.waitForTimeout(600);
  await page.screenshot({ path: join(out, `${name}.png`) });
  await ctx.close();
  console.log('✓', name);
}
await browser.close();
if (errors.length) {
  console.log(errors.join('\n'));
  process.exitCode = 1;
}
