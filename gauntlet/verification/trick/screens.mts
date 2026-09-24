// Screenshots of the "Can It Be Fooled?" screens (mock mode) at 1920×1080 and 1080×1920.
// Usage: node screens.mts <baseUrl> [outDir]   (server started with `node src/cli.ts serve`)
import { chromium } from 'playwright-core';
import { mkdirSync, readdirSync, existsSync } from 'node:fs';
import { join } from 'node:path';

const base = process.argv[2] ?? 'http://127.0.0.1:7777';
const out = process.argv[3] ?? join(import.meta.dirname, '..', '..', 'docs', 'screenshots', 'trick');
mkdirSync(out, { recursive: true });

function chromiumPath(): string | undefined {
  const root = '/opt/pw-browsers';
  if (!existsSync(root)) return undefined;
  const dir = readdirSync(root).find((d) => /^chromium-\d+$/.test(d));
  return dir ? join(root, dir, 'chrome-linux', 'chrome') : undefined;
}

const RUN = 'run-2026-09-22-trick';
const browser = await chromium.launch({ executablePath: chromiumPath() });

async function shot(name: string, url: string, opts: { w?: number; h?: number; broadcast?: boolean; presses?: number; theme?: 'light' | 'dark' } = {}) {
  const page = await browser.newPage({ viewport: { width: opts.w ?? 1920, height: opts.h ?? 1080 } });
  const errors: string[] = [];
  page.on('pageerror', (e) => errors.push(e.message));
  await page.addInitScript(
    ({ broadcast, theme }) => {
      if (broadcast) sessionStorage.setItem('gauntlet.broadcast', '1');
      if (theme) localStorage.setItem('gauntlet.theme', theme);
    },
    { broadcast: !!opts.broadcast, theme: opts.theme },
  );
  await page.goto(url);
  await page.waitForSelector('.deck-stage, .page, main', { timeout: 15000 }).catch(() => undefined);
  await page.waitForTimeout(2500);
  for (let i = 0; i < (opts.presses ?? 0); i++) {
    await page.keyboard.press('ArrowRight');
    await page.waitForTimeout(600);
  }
  await page.waitForTimeout(1800);
  await page.screenshot({ path: join(out, `${name}.png`) });
  if (errors.length) console.log(name, 'errors:', errors);
  await page.close();
}

// Find the first trick slide in the horizontal deck.
const probe = await browser.newPage({ viewport: { width: 1920, height: 1080 } });
await probe.goto(`${base}/?mock=1#/present/${RUN}?s=1`);
await probe.waitForTimeout(1500);
let first = 0;
for (let s = 1; s <= 30; s++) {
  await probe.goto(`${base}/?mock=1#/present/${RUN}?s=${s}`);
  await probe.waitForTimeout(350);
  if ((await probe.locator('.deck-stage[data-slide="trick"]').count()) > 0) {
    first = s;
    break;
  }
}
await probe.close();
console.log('first trick slide', first);

await shot('present-trick-question', `${base}/?mock=1#/present/${RUN}?s=${first}`);
await shot('present-trick-answers', `${base}/?mock=1#/present/${RUN}?s=${first}`, { presses: 1 });
await shot('present-trick-verdicts', `${base}/?mock=1#/present/${RUN}?s=${first}`, { presses: 2 });
await shot('present-trick-verdicts-light', `${base}/?mock=1#/present/${RUN}?s=${first}`, { presses: 2, theme: 'light' });
await shot('present-trick-lightning', `${base}/?mock=1#/present/${RUN}?s=${first + 11}`, { presses: 2 });
await shot('present-trick-broadcast', `${base}/?mock=1#/present/${RUN}?s=${first + 5}`, { presses: 2, broadcast: true });
await shot('shorts-vertical-question', `${base}/?mock=1#/present/${RUN}?vertical=1&s=1`, { w: 1080, h: 1920 });
await shot('shorts-vertical-verdicts', `${base}/?mock=1#/present/${RUN}?vertical=1&s=1`, { w: 1080, h: 1920, presses: 2 });
await shot('shorts-vertical-lightning', `${base}/?mock=1#/present/${RUN}?vertical=1&s=8`, { w: 1080, h: 1920, presses: 2 });
await shot('shorts-vertical-empty', `${base}/?mock=1#/present/run-2026-09-18-quick?vertical=1`, { w: 1080, h: 1920 });
await shot('test-detail-lightning', `${base}/?mock=1#/tests/trick.lightning-traps`);
await shot('run-detail-trick', `${base}/?mock=1#/runs/${RUN}`);
await shot('real-test-detail-lightning', `${base}/#/tests/trick.lightning-traps`, { broadcast: true });
await browser.close();
