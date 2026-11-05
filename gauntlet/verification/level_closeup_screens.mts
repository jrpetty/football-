// Screenshots of the Level close-up (ui/src/components/viz/LevelCloseUp.tsx) in demo mode (?mock=1), opened from
// a ladder test's page (#/tests/<id>?level=N&c=<model>&run=<run>). Picks, from the demo runs, a model whose attempt
// matches each wanted story (failed, passed, one move over, 16/17, 88%), then shoots dark, light, phone and broadcast.
// Usage: node verification/level_closeup_screens.mts <baseUrl> <outDir> [filter]
//   (e.g. `npx vite --config ui/vite.config.ts --port 5191` for the base URL)
import { chromium, type Page } from 'playwright-core';
import { mkdirSync, readdirSync, existsSync } from 'node:fs';
import { join } from 'node:path';

const base = process.argv[2] ?? 'http://127.0.0.1:5191';
const out = process.argv[3] ?? join(import.meta.dirname, '..', 'docs', 'screenshots', 'level-closeup');
const filter = process.argv[4] ?? '';
mkdirSync(out, { recursive: true });

function chromiumPath(): string | undefined {
  const root = '/opt/pw-browsers';
  if (!existsSync(root)) return undefined;
  const dir = readdirSync(root).find((d) => /^chromium-\d+$/.test(d));
  if (!dir) return undefined;
  const exe = ['chrome-linux64', 'chrome-linux'].map((d) => join(root, dir, d, 'chrome')).find((p) => existsSync(p));
  return exe;
}

const FRONTIER = 'run-2026-10-20-frontier-ladders';
const BUSINESS = 'run-2026-10-21-business-ladders';
const browser = await chromium.launch({ executablePath: chromiumPath() });

interface Lite {
  key: string;
  contestantId: string;
  testId: string;
  caseId: string;
  repeat: number;
  score: number | null;
  passed: boolean | null;
}
interface Full {
  score: number | null;
  scoreDetail: { extracted?: string; expected?: unknown; items?: Array<{ passed: boolean }> };
}

/** Ask the demo server (inside the page) for a run's results and full results. */
async function demo(page: Page, run: string): Promise<{ lites: Lite[]; full: (key: string) => Promise<Full> }> {
  const lites = (await page.evaluate(async (id) => {
    const m = await import('/src/mock/mockServer.ts' as string);
    return ((await m.handle('GET', `/api/runs/${id}`)) as { results: Lite[] }).results;
  }, run)) as Lite[];
  const full = (key: string) =>
    page.evaluate(
      async ([id, k]) => {
        const m = await import('/src/mock/mockServer.ts' as string);
        return (await m.handle('GET', `/api/runs/${id}/results/${encodeURIComponent(k!)}`)) as Full;
      },
      [run, key],
    ) as Promise<Full>;
  return { lites, full };
}

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

const probe = await newPage();
await probe.page.goto(`${base}/?mock=1#/`);
await probe.page.waitForTimeout(1500);
const fr = await demo(probe.page, FRONTIER);
const bz = await demo(probe.page, BUSINESS);

async function pick(d: typeof fr, testId: string, caseId: string, want: (l: Lite, f: () => Promise<Full>) => Promise<boolean> | boolean): Promise<Lite | null> {
  for (const l of d.lites.filter((x) => x.testId === testId && x.caseId === caseId && x.contestantId !== 'random-baseline').sort((a, b) => a.repeat - b.repeat)) {
    if (await want(l, () => d.full(l.key))) return l;
  }
  return null;
}

interface Shot {
  name: string;
  testId: string;
  level: number;
  run?: string;
  lite?: Lite | null;
  variants: Array<{ suffix: string; theme?: 'light' | 'dark'; broadcast?: boolean; phone?: boolean; reveal?: boolean }>;
  scrollTo?: string;
}

const L = (n: number) => `L${String(n).padStart(2, '0')}`;
const shots: Shot[] = [
  { name: 'grid-ladder-L06-failed', testId: 'reasoning.grid-ladder', level: 6, run: FRONTIER, lite: await pick(fr, 'reasoning.grid-ladder', L(6), (l) => l.passed === false), variants: [{ suffix: 'dark' }, { suffix: 'light', theme: 'light' }, { suffix: 'phone', phone: true }, { suffix: 'broadcast', broadcast: true }] },
  { name: 'truth-ladder-L04-passed', testId: 'reasoning.truth-ladder', level: 4, run: FRONTIER, lite: await pick(fr, 'reasoning.truth-ladder', L(4), (l) => l.passed === true), variants: [{ suffix: 'dark' }] },
  {
    name: 'plans-ladder-L06-one-over',
    testId: 'reasoning.plans-ladder',
    level: 6,
    run: FRONTIER,
    lite: await pick(fr, 'reasoning.plans-ladder', L(6), async (l, f) => l.passed === false && Number((await f()).scoreDetail.extracted) === 33),
    variants: [{ suffix: 'dark' }],
  },
  { name: 'exact-ladder-L08', testId: 'math.exact-ladder', level: 8, run: FRONTIER, lite: await pick(fr, 'math.exact-ladder', L(8), (l) => l.passed === false), variants: [{ suffix: 'dark' }] },
  {
    name: 'engineering-ladder-L06-16of17',
    testId: 'coding.engineering-ladder',
    level: 6,
    run: FRONTIER,
    lite: await pick(fr, 'coding.engineering-ladder', L(6), (l) => l.passed === false && Math.abs((l.score ?? 0) - 16 / 17) < 0.001),
    variants: [{ suffix: 'dark' }],
  },
  {
    name: 'invoices-ladder-L07-88pct',
    testId: 'work.invoices-ladder',
    level: 7,
    run: BUSINESS,
    lite: await pick(bz, 'work.invoices-ladder', L(7), (l) => l.passed === false && Math.round((l.score ?? 0) * 100) >= 85),
    variants: [{ suffix: 'dark' }, { suffix: 'light', theme: 'light' }, { suffix: 'phone', phone: true }, { suffix: 'broadcast', broadcast: true }],
  },
  { name: 'question-only-grid-ladder-L03', testId: 'reasoning.grid-ladder', level: 3, run: FRONTIER, lite: null, variants: [{ suffix: 'dark' }, { suffix: 'revealed', reveal: true }] },
  { name: 'sql-ladder-L06', testId: 'work.sql-ladder', level: 6, run: BUSINESS, lite: await pick(bz, 'work.sql-ladder', L(6), (l) => l.passed === false), variants: [{ suffix: 'dark' }] },
  { name: 'payroll-ladder-L06', testId: 'work.payroll-ladder', level: 6, run: BUSINESS, lite: await pick(bz, 'work.payroll-ladder', L(6), (l) => l.passed === false), variants: [{ suffix: 'dark' }] },
  { name: 'crm-ladder-L05', testId: 'work.crm-ladder', level: 5, run: BUSINESS, lite: await pick(bz, 'work.crm-ladder', L(5), (l) => l.passed === false), variants: [{ suffix: 'dark' }] },
  { name: 'horizon-sliding-L02', testId: 'horizon.sliding-ladder', level: 2, lite: { key: '', contestantId: 'kestrel-kite-reasoner', testId: 'horizon.sliding-ladder', caseId: 'L02', repeat: 0, score: null, passed: null }, variants: [{ suffix: 'dark' }] },
  { name: 'question-only-truth-ladder-L05', testId: 'reasoning.truth-ladder', level: 5, lite: null, variants: [{ suffix: 'phone', phone: true, reveal: true }] },
];

for (const s of shots) {
  if (filter && !s.name.includes(filter)) continue;
  if (s.lite === undefined) continue;
  console.log(s.name, s.lite ? `${s.lite.contestantId} r${s.lite.repeat} score ${s.lite.score}` : 'question only');
  for (const v of s.variants) {
    const { page, errors } = await newPage(v);
    const q = new URLSearchParams({ level: String(s.level) });
    if (s.lite) q.set('c', s.lite.contestantId);
    if (s.run) q.set('run', s.run);
    await page.goto(`${base}/?mock=1#/tests/${encodeURIComponent(s.testId)}?${q}`);
    await page.waitForSelector('.lc-beats', { timeout: 30000 }).catch(() => undefined);
    // Pick the attempt that matched.
    if (s.lite && s.lite.repeat > 0) await page.locator('.lc-att').nth(s.lite.repeat).click().catch(() => undefined);
    if (v.reveal) await page.locator('.lc-reveal').click().catch(() => undefined);
    await page.waitForTimeout(1600);
    const file = join(out, `${s.name}-${v.suffix}.png`);
    await page.screenshot({ path: file, fullPage: false });
    // A second shot of the scrolled body (phone and tall levels).
    // The body scrolls on wide screens; on a phone the whole sheet does.
    const body = page.locator(v.phone ? '.lc-dialog' : '.lc-body');
    const tall = await body.evaluate((el) => el.scrollHeight > el.clientHeight + 40).catch(() => false);
    if (tall) {
      await body.evaluate((el) => el.scrollTo(0, el.scrollHeight / 2));
      await page.waitForTimeout(500);
      await page.screenshot({ path: join(out, `${s.name}-${v.suffix}-scrolled.png`) });
      await body.evaluate((el) => el.scrollTo(0, el.scrollHeight));
      await page.waitForTimeout(400);
      await page.screenshot({ path: join(out, `${s.name}-${v.suffix}-end.png`) });
    }
    const wide = await page.evaluate(() => document.documentElement.scrollWidth > window.innerWidth + 1);
    console.log(`  ${v.suffix}: ${file}${wide ? ' · HORIZONTAL PAGE SCROLL' : ''}${errors.length ? ` · errors: ${errors.slice(0, 3).join(' | ')}` : ''}`);
    await page.close();
  }
}
await browser.close();
