// Screenshots of the Horizon tier (mock mode, 1920×1080): the ladder in the Result Inspector, one rung of
// every ladder (answer vs truth), the Presenter's "How far up the ladder" slides, Broadcast mode and the
// light theme. Usage: node verification/horizon/screens.mts <baseUrl> [outDir] [filter]
//   (server started with `node src/cli.ts serve --port <port>`)
import { chromium, type Page } from 'playwright-core';
import { mkdirSync, readdirSync, existsSync } from 'node:fs';
import { join } from 'node:path';

const base = process.argv[2] ?? 'http://127.0.0.1:7777';
const out = process.argv[3] || join(import.meta.dirname, '..', '..', 'docs', 'screenshots', 'horizon');
const filter = process.argv[4] ?? '';
mkdirSync(out, { recursive: true });
const RUN = 'run-2026-09-28-horizon';

function chromiumPath(): string | undefined {
  const root = '/opt/pw-browsers';
  if (!existsSync(root)) return undefined;
  const dir = readdirSync(root).find((d) => /^chromium-\d+$/.test(d));
  return dir ? join(root, dir, 'chrome-linux', 'chrome') : undefined;
}
const browser = await chromium.launch({ executablePath: chromiumPath() });

async function open(url: string, opts: { broadcast?: boolean; theme?: 'light' | 'dark' } = {}): Promise<{ page: Page; errors: string[] }> {
  const page = await browser.newPage({ viewport: { width: 1920, height: 1080 } });
  const errors: string[] = [];
  page.on('pageerror', (e) => errors.push(e.message));
  page.on('console', (m) => m.type() === 'error' && errors.push(m.text()));
  await page.addInitScript(
    ({ broadcast, theme }) => {
      if (broadcast) sessionStorage.setItem('gauntlet.broadcast', '1');
      localStorage.setItem('gauntlet.theme', theme ?? 'dark');
    },
    { broadcast: !!opts.broadcast, theme: opts.theme },
  );
  await page.goto(url);
  await page.waitForTimeout(2400);
  return { page, errors };
}

async function overflowOf(page: Page, sel: string): Promise<string> {
  return page.evaluate((s) => {
    const bad: string[] = [];
    document.querySelectorAll<HTMLElement>(`${s} *`).forEach((n) => {
      if (n instanceof SVGElement) return;
      if (n.scrollWidth > n.clientWidth + 2 && getComputedStyle(n).overflowX === 'visible' && n.clientWidth > 0) bad.push(n.className.toString().slice(0, 40));
    });
    return bad.slice(0, 5).join(', ');
  }, sel);
}

async function inspector(name: string, testId: string, caseId: string, contestant: string, opts: { broadcast?: boolean; theme?: 'light' | 'dark'; scrollTo?: string } = {}) {
  if (filter && !name.includes(filter)) return;
  const key = `${contestant}::${testId}::${caseId}::r0`;
  const { page, errors } = await open(`${base}/?mock=1#/runs/${RUN}?tab=matrix&test=${encodeURIComponent(testId)}&c=${contestant}&key=${encodeURIComponent(key)}`, opts);
  await page.waitForSelector('.insp-detail', { timeout: 15000 }).catch(() => undefined);
  await page.waitForTimeout(1800);
  if (opts.scrollTo) await page.locator(opts.scrollTo).first().scrollIntoViewIfNeeded().catch(() => undefined);
  await page.waitForTimeout(600);
  const of = await overflowOf(page, '.drawer');
  await page.screenshot({ path: join(out, `${name}.png`) });
  console.log(name, errors.length ? `errors: ${errors.join(' | ')}` : 'ok', of ? `overflow: ${of}` : '');
  await page.close();
}

async function slide(name: string, s: number, opts: { theme?: 'light' | 'dark' } = {}) {
  const { page, errors } = await open(`${base}/?mock=1#/present/${RUN}?s=${s}`, opts);
  await page.waitForTimeout(3000);
  await page.screenshot({ path: join(out, `${name}.png`) });
  console.log(name, errors.length ? `errors: ${errors.join(' | ')}` : 'ok');
  await page.close();
}

// The ladder panel (top of the inspector) and one rung of every ladder, right and wrong.
await inspector('inspector-ladder-mind-runner', 'horizon.mind-runner', 'L04', 'kestrel-kite-reasoner');
await inspector('inspector-ladder-broadcast', 'horizon.modpow-ladder', 'L05', 'kestrel-kite-reasoner', { broadcast: true });
await inspector('inspector-ladder-light', 'horizon.nonogram-ladder', 'L03', 'meridian-atlas-4-ultra', { theme: 'light' });
await inspector('rung-modpow-wrong', 'horizon.modpow-ladder', 'L06', 'kestrel-kite-reasoner', { scrollTo: '.hz-rung-card' });
await inspector('rung-mind-runner-right', 'horizon.mind-runner', 'L02', 'meridian-atlas-4-ultra', { scrollTo: '.hz-rung-card' });
await inspector('rung-sliding-optimal', 'horizon.sliding-ladder', 'L02', 'kestrel-kite-reasoner', { scrollTo: '.hz-rung-card' });
await inspector('rung-sliding-detour-or-illegal', 'horizon.sliding-ladder', 'L03', 'kestrel-kite-reasoner', { scrollTo: '.hz-rung-card' });
await inspector('rung-sliding-other', 'horizon.sliding-ladder', 'L02', 'meridian-atlas-4-ultra', { scrollTo: '.hz-rung-card' });
await inspector('rung-nonogram-wrong', 'horizon.nonogram-ladder', 'L04', 'kestrel-kite-reasoner', { scrollTo: '.hz-rung-card' });
await inspector('rung-nonogram-right', 'horizon.nonogram-ladder', 'L02', 'meridian-atlas-4-ultra', { scrollTo: '.hz-rung-card' });
await inspector('rung-tiling-wrong', 'horizon.tiling-count', 'L04', 'kestrel-kite-reasoner', { scrollTo: '.hz-rung-card' });

if (!filter || filter === 'slides') {
  const probe = await browser.newPage({ viewport: { width: 1920, height: 1080 } });
  const found: number[] = [];
  const intro: number[] = [];
  for (let s = 1; s <= 60; s++) {
    await probe.goto(`${base}/?mock=1#/present/${RUN}?s=${s}`);
    await probe.waitForTimeout(350);
    if ((await probe.locator('.deck-stage[data-slide="ladder"]').count()) > 0) found.push(s);
    if ((await probe.locator('.deck-stage[data-slide="intro"]').count()) > 0) intro.push(s);
    if ((await probe.locator('.deck-stage[data-slide="outro"]').count()) > 0) break;
  }
  await probe.close();
  console.log('ladder slides', found.join(','), 'intro slides', intro.join(','));
  for (const [i, s] of found.entries()) await slide(`present-ladder-${String(i + 1).padStart(2, '0')}`, s);
  if (found[0]) await slide('present-ladder-01-light', found[0], { theme: 'light' });
  if (intro[0]) await slide('present-intro-01', intro[0]);
}
await browser.close();
