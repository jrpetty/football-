// Screenshots of the customers, comms and data close-ups in demo mode (?mock=1): for every test, the question
// (question-only view) and one model's answer, dark theme; light, phone and broadcast for a few. The close-up is
// opened the way a viewer opens it: "See an example" (?example=<test>) on the Business tests page, or the Level
// close-up (?level=N) on a ladder test's page. The whole dialog is captured (its scrolling body unrolled).
// Usage: node verification/biz-docs/customers-data-screens.mts <baseUrl> <outDir> [filter] [--extra]
import { chromium, type Page } from 'playwright-core';
import { existsSync, mkdirSync, readdirSync } from 'node:fs';
import { join } from 'node:path';

const base = process.argv[2] ?? 'http://127.0.0.1:5197';
const out = process.argv[3] ?? join(import.meta.dirname, 'shots');
const filter = process.argv[4] && !process.argv[4].startsWith('--') ? process.argv[4] : '';
const extra = process.argv.includes('--extra');
mkdirSync(out, { recursive: true });

function chromiumPath(): string | undefined {
  const root = '/opt/pw-browsers';
  if (!existsSync(root)) return undefined;
  const dir = readdirSync(root).find((d) => /^chromium-\d+$/.test(d));
  if (!dir) return undefined;
  return ['chrome-linux64', 'chrome-linux'].map((d) => join(root, dir, d, 'chrome')).find((p) => existsSync(p));
}

const LADDERS = new Set(['work.support-ladder', 'work.contract-ladder', 'work.crm-ladder', 'work.sql-ladder']);
const TESTS = [
  'work.inbox-triage',
  'work.email-actions',
  'work.meeting-scheduler',
  'work.ticket-tag',
  'work.support-refunds',
  'work.support-ladder',
  'work.support-replies',
  'work.contract-redline',
  'work.contract-ladder',
  'work.crm-dedupe',
  'work.crm-ladder',
  'work.sheet-lookup',
  'work.sql-questions',
  'work.sql-hard',
  'work.sql-ladder',
  'work.delivery-routes',
  'work.shift-rota',
].filter((t) => !filter || t.includes(filter));
/** Light, phone and broadcast shots for these. */
const EXTRA = new Set(['work.inbox-triage', 'work.support-refunds', 'work.crm-dedupe']);
const LADDER_LEVEL: Record<string, number> = { 'work.support-ladder': 4, 'work.contract-ladder': 3, 'work.crm-ladder': 4, 'work.sql-ladder': 3 };

const browser = await chromium.launch({ executablePath: chromiumPath() });

async function newPage(opts: { theme?: 'light' | 'dark'; broadcast?: boolean; phone?: boolean } = {}): Promise<{ page: Page; errors: string[] }> {
  const page = await browser.newPage({ viewport: opts.phone ? { width: 360, height: 780 } : { width: 1600, height: 1000 }, deviceScaleFactor: 2 });
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
  return { page, errors };
}

async function unroll(page: Page) {
  await page.addStyleTag({ content: '.lc-root{position:absolute!important;inset:0 auto auto 0!important;width:100vw!important;max-width:100vw!important;min-height:100%;display:block!important}.lc-dialog{margin:0 auto}.lc-dialog{max-height:none!important;height:auto!important;overflow:visible!important}.lc-body{overflow:visible!important}.lc-beat{animation:none!important}' });
  await page.waitForTimeout(300);
}

async function shoot(testId: string, name: string, opts: { theme?: 'light' | 'dark'; broadcast?: boolean; phone?: boolean; question?: boolean }) {
  const { page, errors } = await newPage(opts);
  const url = LADDERS.has(testId) ? `${base}/?mock=1#/tests/${testId}?level=${LADDER_LEVEL[testId] ?? 3}` : `${base}/?mock=1#/business/tests?example=${testId}`;
  await page.goto(url);
  await page.waitForSelector('.lc-dialog', { timeout: 20000 });
  if (LADDERS.has(testId) && !opts.question) {
    // Pick a model that failed this level (the most telling answer).
    await page.waitForSelector('.lc-token', { timeout: 20000 });
    const fail = page.locator('.lc-token.t-fail, .lc-token.t-partial, .lc-token.t-some').filter({ hasNotText: /Random|Baseline/i }).first();
    if (await fail.count()) await fail.click();
    else await page.locator('.lc-token').first().click();
  }
  if (!LADDERS.has(testId) && opts.question) {
    await page.waitForSelector('.lc-token.q', { timeout: 20000 });
    await page.locator('.lc-token.q').click();
  }
  await page.waitForTimeout(1500);
  await page.waitForFunction(() => !document.querySelector('.lc-loading'), null, { timeout: 30000 }).catch(() => undefined);
  if (opts.question) {
    const reveal = page.locator('.lc-reveal');
    if (await reveal.count()) await reveal.click();
  }
  await page.waitForTimeout(500);
  const wide = await page.evaluate(() => document.documentElement.scrollWidth > window.innerWidth + 1);
  await unroll(page);
  const file = join(out, `${name}.png`);
  await page.locator('.lc-dialog').screenshot({ path: file });
  console.log(`${name}${errors.length ? ` · errors: ${errors.slice(0, 3).join(' | ')}` : ''}${wide ? ' · HORIZONTAL SCROLL' : ''}`);
  await page.close();
}

for (const t of TESTS) {
  const short = t.replace(/^work\./, '');
  if (!extra) {
    await shoot(t, `${short}-question-dark`, { question: true });
    await shoot(t, `${short}-answer-dark`, {});
  }
  if (EXTRA.has(t) || extra) {
    await shoot(t, `${short}-answer-light`, { theme: 'light' });
    await shoot(t, `${short}-answer-phone`, { phone: true });
    if (EXTRA.has(t)) await shoot(t, `${short}-answer-broadcast`, { broadcast: true });
  }
}
await browser.close();
