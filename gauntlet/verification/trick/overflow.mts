// Layout check: opens every trick highlight slide of the mock run (?traps=30) at 1920×1080 and 1080×1920,
// reveals all steps and reports any slide whose content overflows its card or the stage.
// Usage: node overflow.mts <baseUrl>
import { chromium } from 'playwright-core';
import { readdirSync, existsSync } from 'node:fs';
import { join } from 'node:path';

const base = process.argv[2] ?? 'http://127.0.0.1:7777';
const root = '/opt/pw-browsers';
const dir = existsSync(root) ? readdirSync(root).find((d) => /^chromium-\d+$/.test(d)) : undefined;
const browser = await chromium.launch({ executablePath: dir ? join(root, dir, 'chrome-linux', 'chrome') : undefined });
const RUN = 'run-2026-09-22-trick';
let problems = 0;
for (const [w, h, v] of [
  [1920, 1080, ''],
  [1080, 1920, '&vertical=1'],
] as const) {
  const page = await browser.newPage({ viewport: { width: w, height: h } });
  for (let s = 1; s <= 200; s++) {
    await page.goto(`${base}/?mock=1#/present/${RUN}?traps=30${v}&s=${s}`);
    await page.waitForTimeout(s === 1 ? 2500 : 400);
    const kind = await page.locator('.deck-stage').getAttribute('data-slide');
    const label = await page.locator('.d-prog .tnum').innerText();
    if (kind === 'trick') {
      await page.keyboard.press('ArrowRight');
      await page.keyboard.press('ArrowRight');
      await page.waitForTimeout(900);
      const r = await page.evaluate(() => {
        const q = document.querySelector('.tk-q-card') as HTMLElement;
        const body = document.querySelector('.d-body') as HTMLElement;
        const slide = document.querySelector('.s-trick') as HTMLElement;
        const models = document.querySelector('.tk-models') as HTMLElement;
        const last = models.lastElementChild as HTMLElement;
        return {
          qOverflow: q.scrollHeight - q.clientHeight,
          slideOverflow: slide.scrollHeight - slide.clientHeight,
          rowsBottom: last.getBoundingClientRect().bottom - body.getBoundingClientRect().bottom,
          chars: (document.querySelector('.tk-q') as HTMLElement).innerText.length,
        };
      });
      const bad = r.qOverflow > 1 || r.slideOverflow > 1 || r.rowsBottom > 1;
      if (bad) problems++;
      console.log(`${w}x${h} ${label} chars=${r.chars} q+${r.qOverflow} slide+${r.slideOverflow} rows${r.rowsBottom > 0 ? '+' : ''}${Math.round(r.rowsBottom)}${bad ? '  <-- OVERFLOW' : ''}`);
    }
    const [i, n] = label.split('/').map((x) => Number(x.trim()));
    if (i === n) break;
  }
  await page.close();
}
await browser.close();
console.log(problems ? `${problems} overflowing slides` : 'no overflow');
