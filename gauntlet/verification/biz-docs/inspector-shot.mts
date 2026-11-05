// Screenshot of the Result Inspector for one demo result (the document picture in the "Score" tab).
// Usage: node verification/biz-docs/inspector-shot.mts <baseUrl> <outFile> <testId> <contestantId> [light]
import { chromium } from 'playwright-core';
import { existsSync, readdirSync } from 'node:fs';
import { join } from 'node:path';
const [base, out, testId, who, theme] = process.argv.slice(2);
const root = '/opt/pw-browsers';
const dir = readdirSync(root).find((d) => /^chromium-\d+$/.test(d))!;
const exe = ['chrome-linux64', 'chrome-linux'].map((d) => join(root, dir, d, 'chrome')).find((p) => existsSync(p));
const b = await chromium.launch({ executablePath: exe });
const p = await b.newPage({ viewport: { width: 1500, height: 1100 }, deviceScaleFactor: 2 });
const errors: string[] = [];
p.on('pageerror', (e) => errors.push(e.message));
await p.addInitScript((t) => localStorage.setItem('gauntlet.theme', t), theme ?? 'dark');
await p.goto(`${base}/?mock=1#/runs/run-2026-10-02-business?test=${testId}&c=${who}`);
await p.waitForSelector('.cd-frame, .vz', { timeout: 30000 });
await p.waitForTimeout(1500);
const el = p.locator('.cd-frame').first();
if (await el.count()) await el.screenshot({ path: out });
else await p.screenshot({ path: out });
console.log(out, errors.join(' | '));
await b.close();
