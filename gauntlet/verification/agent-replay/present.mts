// Screenshots of the Presenter's "best moment" slides for the simulation tests.
// Usage: node present.mts <baseUrl> <runId> <prefix> [--mock] [--light]
import { chromium } from 'playwright-core';
import { mkdirSync, readdirSync, existsSync } from 'node:fs';
import { join } from 'node:path';

const base = process.argv[2] ?? 'http://127.0.0.1:7777';
const run = process.argv[3]!;
const prefix = process.argv[4] ?? 'present';
const mock = process.argv.includes('--mock');
const light = process.argv.includes('--light');
const out = join(import.meta.dirname, '..', '..', 'docs', 'screenshots', 'visual-pass', 'agent-sims');
mkdirSync(out, { recursive: true });

function chromiumPath(): string | undefined {
  const root = '/opt/pw-browsers';
  if (!existsSync(root)) return undefined;
  const dir = readdirSync(root).find((d) => /^chromium-\d+$/.test(d));
  return dir ? join(root, dir, 'chrome-linux', 'chrome') : undefined;
}

const browser = await chromium.launch({ executablePath: chromiumPath() });
const page = await browser.newPage({ viewport: { width: 1920, height: 1080 } });
const errors: string[] = [];
page.on('pageerror', (e) => errors.push(e.message));
await page.addInitScript((theme) => localStorage.setItem('gauntlet.theme', theme), light ? 'light' : 'dark');
const url = (s: number) => `${base}/${mock ? '?mock=1' : ''}#/present/${run}?s=${s}`;
let n = 0;
for (let s = 1; s <= 60; s++) {
  await page.goto(url(s));
  await page.waitForTimeout(s === 1 ? 2500 : 500);
  const kind = await page.locator('.deck-stage').getAttribute('data-slide').catch(() => null);
  if (kind === null) break;
  if (kind !== 'sim') continue;
  await page.waitForTimeout(1500);
  const name = (await page.locator('.d-section').first().textContent().catch(() => '')) ?? '';
  n++;
  await page.screenshot({ path: join(out, `${prefix}-${n}.png`) });
  console.log(`slide ${s}: ${name}`);
}
if (errors.length) console.log('errors:', errors.slice(0, 5));
await browser.close();
