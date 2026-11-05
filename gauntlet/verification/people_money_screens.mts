// Screenshots of the people-and-money Business visuals (ui/src/business/docs/people-money/) in demo mode
// (?mock=1): each test's close-up opened from the Business tests page ("See an example"), question only and
// with a demo model's answer, plus the Result Inspector picture. Dark by default; light and phone for a few.
// Usage: node verification/people_money_screens.mts <baseUrl> <outDir> [filter] [--light] [--phone] [--broadcast]
//   (e.g. `npx vite --config ui/vite.config.ts --port 5291` for the base URL)
import { chromium, type Page } from 'playwright-core';
import { mkdirSync, readdirSync, existsSync } from 'node:fs';
import { join } from 'node:path';

const args = process.argv.slice(2).filter((a) => !a.startsWith('--'));
const flags = new Set(process.argv.slice(2).filter((a) => a.startsWith('--')));
const base = args[0] ?? 'http://127.0.0.1:5291';
const out = args[1] ?? join(import.meta.dirname, '..', 'docs', 'screenshots', 'people-money');
const filter = args[2] ?? '';
mkdirSync(out, { recursive: true });

function chromiumPath(): string | undefined {
  const root = '/opt/pw-browsers';
  if (!existsSync(root)) return undefined;
  const dir = readdirSync(root).find((d) => /^chromium-\d+$/.test(d));
  if (!dir) return undefined;
  return ['chrome-linux64', 'chrome-linux'].map((d) => join(root, dir, d, 'chrome')).find((p) => existsSync(p));
}

/** test id → [case, demo model, ladder?] */
const SHOTS: Array<{ test: string; c: string; model: string; ladder?: boolean }> = [
  { test: 'work.cv-read', c: 'c09', model: 'meridian-atlas-4-mini' },
  { test: 'work.cv-shortlist', c: 'v01', model: 'obsidian-sable-large' },
  { test: 'work.invoice-read', c: 'c09', model: 'helios-quill-flash' },
  { test: 'work.invoice-matching', c: 'c04', model: 'meridian-atlas-4-mini' },
  { test: 'work.invoices-ladder', c: 'L04', model: 'helios-nova-3-pro', ladder: true },
  { test: 'work.delivery-note', c: 'c08', model: 'helios-quill-flash' },
  { test: 'work.payroll-check', c: 'p04', model: 'obsidian-sable-large' },
  { test: 'work.payroll-ladder', c: 'L04', model: 'helios-nova-3-pro', ladder: true },
  { test: 'work.expense-audit', c: 'x04', model: 'meridian-atlas-4-mini' },
  { test: 'work.expenses-ladder', c: 'L03', model: 'helios-nova-3-pro', ladder: true },
  { test: 'work.bank-reconciliation', c: 'r04', model: 'meridian-atlas-4-mini' },
  { test: 'work.reconciliation-ladder', c: 'L03', model: 'helios-nova-3-pro', ladder: true },
  { test: 'work.board-report', c: 'b04', model: 'meridian-atlas-4-mini' },
  { test: 'work.stock-reorder', c: 'o04', model: 'meridian-atlas-4-mini' },
];

const browser = await chromium.launch({ executablePath: chromiumPath() });
const theme = flags.has('--light') ? 'light' : 'dark';
const phone = flags.has('--phone');
const broadcast = flags.has('--broadcast');
const suffix = `${theme}${phone ? '-phone' : ''}${broadcast ? '-broadcast' : ''}`;

async function newPage(): Promise<{ page: Page; errors: string[] }> {
  const page = await browser.newPage({ viewport: phone ? { width: 360, height: 780 } : { width: 1600, height: 1000 }, deviceScaleFactor: 2 });
  const errors: string[] = [];
  page.on('pageerror', (e) => errors.push(e.message));
  page.on('console', (m) => m.type() === 'error' && errors.push(m.text()));
  await page.addInitScript(
    ({ b, th }) => {
      if (b) sessionStorage.setItem('gauntlet.broadcast', '1');
      localStorage.setItem('gauntlet.theme', th);
    },
    { b: broadcast, th: theme },
  );
  return { page, errors };
}

/** Full-length shot of the close-up's scrolling body (the dialog grows to fit). */
async function shootDialog(page: Page, file: string) {
  await page.waitForSelector('.lc-dialog', { timeout: 20000 });
  await page.waitForTimeout(900);
  await page.evaluate(() => {
    const d = document.querySelector<HTMLElement>('.lc-dialog');
    const root = document.querySelector<HTMLElement>('.lc-root');
    const body = document.querySelector<HTMLElement>('.lc-body');
    if (d && root && body) {
      root.style.position = 'absolute';
      root.style.height = 'auto';
      root.style.bottom = 'auto';
      d.style.maxHeight = 'none';
      d.style.height = 'auto';
      body.style.overflow = 'visible';
      document.body.style.overflow = 'visible';
    }
  });
  await page.waitForTimeout(300);
  const box = await page.locator('.lc-dialog').boundingBox();
  await page.setViewportSize({ width: page.viewportSize()!.width, height: Math.ceil((box?.height ?? 1000) + 40) });
  await page.waitForTimeout(300);
  // No horizontal page scroll: wide documents must scroll inside their own box.
  const over = await page.evaluate(() => {
    const d = document.querySelector<HTMLElement>('.lc-dialog')!;
    return d.scrollWidth - d.clientWidth;
  });
  if (over > 1) console.log(`  WARNING: ${file} overflows sideways by ${over}px`);
  await page.locator('.lc-dialog').screenshot({ path: file });
}

for (const s of SHOTS) {
  if (filter && !s.test.includes(filter)) continue;
  const name = s.test.replace('work.', '');
  for (const withModel of [false, true]) {
    const { page, errors } = await newPage();
    const q = s.ladder ? `level=${Number(s.c.slice(1))}${withModel ? `&c=${s.model}` : ''}` : `example=${s.test}&case=${s.c}${withModel ? `&c=${s.model}` : ''}`;
    const url = s.ladder ? `${base}/?mock=1#/tests/${s.test}?${q}` : `${base}/?mock=1#/business/tests?${q}`;
    await page.goto(url);
    try {
      if (!withModel) {
        await page.waitForSelector('.lc-reveal', { timeout: 20000 });
        await page.click('.lc-reveal');
      } else await page.waitForSelector('.b-result, .lc-empty', { timeout: 30000 });
      await shootDialog(page, join(out, `${name}-${withModel ? 'answer' : 'question'}-${suffix}.png`));
      console.log('shot', name, withModel ? 'answer' : 'question', errors.length ? `ERRORS: ${errors.join(' | ').slice(0, 400)}` : '');
    } catch (e) {
      console.log('FAILED', name, withModel, String(e).slice(0, 200), errors.join(' | ').slice(0, 300));
      await page.screenshot({ path: join(out, `${name}-${withModel ? 'answer' : 'question'}-${suffix}-FAILED.png`) });
    }
    await page.close();
  }
}
await browser.close();
