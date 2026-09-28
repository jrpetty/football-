// Before/after screenshots of the existing screens the live pass changed (mock mode, 1920×1080).
// Usage: node verification/live/before-after.mts <baseUrl> <before|after> [outDir]
import { chromium } from 'playwright-core';
import { mkdirSync, readdirSync, existsSync } from 'node:fs';
import { join } from 'node:path';

const base = process.argv[2] ?? 'http://127.0.0.1:7777';
const tag = process.argv[3] ?? 'after';
const out = process.argv[4] || join(import.meta.dirname, '..', '..', 'docs', 'screenshots', 'next-level', 'live', 'before-after');
mkdirSync(out, { recursive: true });

function chromiumPath(): string | undefined {
  const root = '/opt/pw-browsers';
  if (!existsSync(root)) return undefined;
  const dir = readdirSync(root).find((d) => /^chromium-\d+$/.test(d));
  return dir ? join(root, dir, 'chrome-linux', 'chrome') : undefined;
}

const RUN = 'run-2026-09-24-agents';
const shots = [
  { name: 'run-page', path: `/runs/${RUN}`, scroll: 520 },
  { name: 'live-arena-header', path: `/runs/${RUN}/live`, scroll: 0 },
  { name: 'studio-overlay', path: `/studio/${RUN}?tab=overlay`, scroll: 0 },
];

const browser = await chromium.launch({ executablePath: chromiumPath() });
for (const s of shots) {
  const page = await browser.newPage({ viewport: { width: 1920, height: 1080 } });
  await page.addInitScript(() => {
    sessionStorage.setItem('gauntlet.broadcast', '0');
    localStorage.setItem('gauntlet.theme', 'dark');
  });
  await page.goto(`${base}/?mock=1#${s.path}`);
  await page.waitForTimeout(6000);
  if (s.scroll) await page.evaluate((y) => document.querySelector('.main')?.scrollTo(0, y) ?? window.scrollTo(0, y), s.scroll);
  if (s.scroll) await page.evaluate((y) => window.scrollTo(0, y), s.scroll);
  await page.waitForTimeout(400);
  await page.screenshot({ path: join(out, `${s.name}-${tag}.png`) });
  await page.close();
}
await browser.close();
