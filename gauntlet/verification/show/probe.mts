// Lists every slide kind of a Presenter deck (mock mode) and any page errors.
// Usage: node verification/show/probe.mts <baseUrl> [runId]
import { chromium } from 'playwright-core';
import { readdirSync } from 'node:fs';
import { join } from 'node:path';

const base = process.argv[2] ?? 'http://127.0.0.1:7841';
const run = process.argv[3] ?? 'run-2026-09-21-core';
const dir = readdirSync('/opt/pw-browsers').find((d) => /^chromium-\d+$/.test(d))!;
const browser = await chromium.launch({ executablePath: join('/opt/pw-browsers', dir, 'chrome-linux', 'chrome') });
const page = await browser.newPage({ viewport: { width: 1920, height: 1080 } });
const errs: string[] = [];
page.on('pageerror', (e) => errs.push(e.message));
page.on('console', (m) => {
  if (m.type() === 'error') errs.push(m.text());
});
await page.goto(`${base}/?mock=1#/present/${run}?s=1`);
await page.waitForSelector('.deck-stage');
const total = await page.locator('.d-prog .tnum').first().textContent();
const n = Number(total!.split('/')[1]);
const kinds: string[] = [];
for (let s = 1; s <= n; s++) {
  await page.goto(`${base}/?mock=1#/present/${run}?s=${s}`);
  await page.waitForTimeout(120);
  kinds.push(`${s}:${await page.locator('.deck-stage').getAttribute('data-slide')}`);
}
console.log(kinds.join(' '));
console.log('errors', errs.slice(0, 5));
await browser.close();
