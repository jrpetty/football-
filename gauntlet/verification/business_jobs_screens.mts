// Screenshots of "Which model for this job?" (ui/src/business/jobs/) in demo mode (?mock=1): the job picker, a
// selected job, compare mode, the heat-map, the "See it do this job" dialog, the Business home teaser and the
// Presenter slide, in dark, light, phone (360 px) and broadcast. Fails on page errors and horizontal page scroll.
// Usage: node verification/business_jobs_screens.mts <baseUrl> <outDir> [filter]
//   (e.g. `npx vite --config ui/vite.config.ts --port 5197` for the base URL)
import { chromium, type Page } from 'playwright-core';
import { mkdirSync, readdirSync, existsSync } from 'node:fs';
import { join } from 'node:path';

const base = process.argv[2] ?? 'http://127.0.0.1:5197';
const out = process.argv[3] ?? join(import.meta.dirname, '..', 'docs', 'screenshots', 'business-jobs');
const filter = process.argv[4] ?? '';
mkdirSync(out, { recursive: true });

function chromiumPath(): string | undefined {
  const root = '/opt/pw-browsers';
  if (!existsSync(root)) return undefined;
  const dir = readdirSync(root).find((d) => /^chromium-\d+$/.test(d));
  if (!dir) return undefined;
  return ['chrome-linux64', 'chrome-linux'].map((d) => join(root, dir, d, 'chrome')).find((p) => existsSync(p));
}

const browser = await chromium.launch({ executablePath: chromiumPath() });
type Mode = 'dark' | 'light' | 'phone' | 'broadcast';

async function newPage(mode: Mode): Promise<{ page: Page; errors: string[] }> {
  const phone = mode === 'phone';
  const page = await browser.newPage({ viewport: phone ? { width: 360, height: 780 } : { width: 1600, height: 1000 }, deviceScaleFactor: 2 });
  const errors: string[] = [];
  page.on('pageerror', (e) => errors.push(e.message));
  page.on('console', (m) => m.type() === 'error' && errors.push(m.text()));
  await page.addInitScript(
    ({ broadcast, theme }) => {
      if (broadcast) sessionStorage.setItem('gauntlet.broadcast', '1');
      localStorage.setItem('gauntlet.theme', theme);
      localStorage.setItem('gauntlet.guides.dismissed', JSON.stringify(['business', 'business-jobs']));
    },
    { broadcast: mode === 'broadcast', theme: mode === 'light' ? 'light' : 'dark' },
  );
  return { page, errors };
}

async function hscroll(page: Page): Promise<boolean> {
  return page.evaluate(() => document.documentElement.scrollWidth > window.innerWidth + 1);
}

interface Shot {
  name: string;
  hash: string;
  full?: boolean;
  /** Scroll this selector into view and shoot the viewport (or the element with `el`). */
  scrollTo?: string;
  el?: string;
  /** Wait for this selector before shooting (default: the jobs page or the teaser). */
  wait?: string;
  act?: (page: Page) => Promise<void>;
}

const SHOTS: Shot[] = [
  { name: 'picker', hash: '#/business/jobs', full: true },
  { name: 'heatmap', hash: '#/business/jobs', el: '#overview' },
  { name: 'job-invoices', hash: '#/business/jobs?job=supplier-invoices', full: true },
  { name: 'job-applicants', hash: '#/business/jobs?job=screen-applicants', full: true },
  {
    name: 'compare',
    hash: '#/business/jobs?job=supplier-invoices',
    full: true,
    act: async (page) => {
      await page.getByRole('tab', { name: 'Compare two models on this job' }).click();
      await page.waitForTimeout(2500);
    },
  },
  {
    name: 'see-it',
    hash: '#/business/jobs?job=screen-applicants',
    act: async (page) => {
      await page.getByRole('button', { name: /See it do this job/ }).nth(0).click();
      await page.waitForTimeout(2500);
    },
  },
  {
    name: 'see-it-ladder',
    hash: '#/business/jobs?job=payroll',
    act: async (page) => {
      await page.getByRole('button', { name: /See it do this job/ }).nth(0).click();
      await page.waitForTimeout(3500);
    },
  },
  { name: 'home-teaser', hash: '#/business', el: '.bj-teaser' },
  {
    name: 'presenter',
    hash: '#/present/run-2026-10-02-business',
    wait: '.app, body',
    act: async (page) => {
      // Step through the deck until the "Which model to …?" slide is on screen.
      for (let i = 0; i < 400 && !(await page.locator('.s-bizjobs').count()); i++) {
        await page.keyboard.press('ArrowRight');
        await page.waitForTimeout(40);
      }
      await page.waitForTimeout(1200);
    },
  },
];

const modes: Mode[] = ['dark', 'light', 'phone', 'broadcast'];
let problems = 0;
for (const s of SHOTS) {
  for (const mode of modes) {
    const name = `${s.name}-${mode}`;
    if (filter && !name.includes(filter)) continue;
    const { page, errors } = await newPage(mode);
    await page.goto(`${base}/?mock=1${s.hash}`);
    await page.waitForSelector(s.wait ?? '.bj-page, .bj-teaser', { timeout: 30000 });
    await page.waitForTimeout(1800);
    if (s.act) await s.act(page);
    if (await hscroll(page)) {
      problems++;
      console.log(`✗ ${name}: the page scrolls sideways`);
    }
    const file = join(out, `${name}.png`);
    if (s.el) await page.locator(s.el).first().screenshot({ path: file });
    else await page.screenshot({ path: file, fullPage: !!s.full });
    // Tall phone pages: also the first two screens, readable at a glance.
    if (mode === 'phone' && s.full) await page.screenshot({ path: join(out, `${name}-top.png`), clip: { x: 0, y: 0, width: 360, height: 1560 } });
    if (mode === 'phone' && s.full) await page.screenshot({ path: join(out, `${name}-mid.png`), fullPage: true, clip: { x: 0, y: 1300, width: 360, height: 1560 } });
    if (errors.length) {
      problems++;
      console.log(`✗ ${name}: ${errors.slice(0, 3).join(' | ')}`);
    } else console.log(`✓ ${name}`);
    await page.close();
  }
}
await browser.close();
if (problems) process.exitCode = 1;
