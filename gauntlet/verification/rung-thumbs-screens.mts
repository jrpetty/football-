// Screenshots of the rung-thumbnail review page (ui/rung-thumbs.html, dev server only) at deviceScaleFactor 2:
// every ladder's L1–L10 strip in dark, light and broadcast, plus a 32/96 px size check.
// Usage: node verification/rung-thumbs-screens.mts <baseUrl> <outDir>
//   (UI served by `npx vite --config ui/vite.config.ts`; the page is not in the production build)
import { chromium } from 'playwright-core';
import { existsSync, mkdirSync, readdirSync } from 'node:fs';
import { join } from 'node:path';

const base = process.argv[2] ?? 'http://127.0.0.1:5173';
const out = process.argv[3] ?? join(import.meta.dirname, '..', 'docs', 'screenshots', 'rung-thumbs');
mkdirSync(out, { recursive: true });

function chromiumPath(): string | undefined {
  const root = '/opt/pw-browsers';
  if (!existsSync(root)) return undefined;
  const dir = readdirSync(root).find((d) => /^chromium-\d+$/.test(d));
  if (!dir) return undefined;
  return ['chrome-linux64', 'chrome-linux'].map((d) => join(root, dir, d, 'chrome')).find((p) => existsSync(p));
}

const browser = await chromium.launch({ executablePath: chromiumPath() });
const shots: Array<{ name: string; theme: 'dark' | 'light'; query: string }> = [
  { name: 'dark', theme: 'dark', query: '' },
  { name: 'light', theme: 'light', query: '' },
  { name: 'broadcast-dark', theme: 'dark', query: '&tone=broadcast&size=64' },
  { name: 'size-32-dark', theme: 'dark', query: '&size=32' },
  { name: 'size-96-light', theme: 'light', query: '&size=96' },
];
const errors: string[] = [];
for (const s of shots) {
  for (const group of ['horizon', 'frontier', 'business']) {
    const page = await browser.newPage({ viewport: { width: 1500, height: 900 }, deviceScaleFactor: 2 });
    page.on('pageerror', (e) => errors.push(`${s.name}/${group}: ${e.message}`));
    page.on('console', (m) => m.type() === 'error' && errors.push(`${s.name}/${group}: ${m.text()}`));
    await page.addInitScript((theme) => localStorage.setItem('gauntlet.theme', theme), s.theme);
    await page.goto(`${base}/rung-thumbs.html#/?group=${group}${s.query}`);
    await page.waitForSelector('.rung-thumb', { timeout: 20000 });
    await page.waitForTimeout(400);
    const file = join(out, `${s.name}-${group}.png`);
    await page.screenshot({ path: file, fullPage: true });
    console.log(file);
    await page.close();
  }
}
await browser.close();
if (errors.length) {
  console.error(errors.join('\n'));
  process.exit(1);
}
