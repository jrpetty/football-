// Before/after screenshots of the "answer vs truth" case visuals (mock mode) at 1920×1080.
// "Before" is the same result with ?plain=1 (the plain score view the visuals fall back to).
// Usage: node verification/case_visual_screens.mts <baseUrl> [outDir] [filter]
//   (server started with `node src/cli.ts serve --port <port>`)
import { chromium, type Page } from 'playwright-core';
import { mkdirSync, readdirSync, existsSync, readFileSync } from 'node:fs';
import { join } from 'node:path';

const base = process.argv[2] ?? 'http://127.0.0.1:7777';
const out = process.argv[3] || join(import.meta.dirname, '..', 'docs', 'screenshots', 'visual-pass', 'answer-vs-truth');
const filter = process.argv[4] ?? '';
mkdirSync(out, { recursive: true });

function chromiumPath(): string | undefined {
  const root = '/opt/pw-browsers';
  if (!existsSync(root)) return undefined;
  const dir = readdirSync(root).find((d) => /^chromium-\d+$/.test(d));
  return dir ? join(root, dir, 'chrome-linux', 'chrome') : undefined;
}

const RUN = 'run-2026-09-24-truth';
const fx = JSON.parse(readFileSync(join(import.meta.dirname, '..', 'ui', 'src', 'mock', 'caseVisualsFixtures.json'), 'utf8')) as { tests: Array<{ id: string; cases: Array<{ id: string }> }> };

// Mirror of the mock's deterministic right/wrong choice (ui/src/mock/caseVisualsMock.ts), to find a model that missed.
const SKILL: Record<string, number> = { 'meridian-atlas-4-ultra': 0.78, 'kestrel-kite-reasoner': 0.84, 'helios-nova-3-pro': 0.62, 'obsidian-sable-large': 0.45, 'helios-quill-flash': 0.35 };
function hash(s: string): number {
  let h = 2166136261;
  for (let i = 0; i < s.length; i++) {
    h ^= s.charCodeAt(i);
    h = Math.imul(h, 16777619);
  }
  return (h >>> 0) / 4294967296;
}
const wrong = (c: string, t: string, k: string) => hash(`${c}|${t}|${k}`) * 0.85 + hash(`${c}|${t}|${k}|0`) * 0.15 >= (SKILL[c] ?? 0.5);
const MODELS = ['kestrel-kite-reasoner', 'meridian-atlas-4-ultra', 'helios-nova-3-pro', 'obsidian-sable-large', 'helios-quill-flash'];

const browser = await chromium.launch({ executablePath: chromiumPath() });

async function open(url: string, opts: { broadcast?: boolean; theme?: 'light' | 'dark' } = {}): Promise<{ page: Page; errors: string[] }> {
  const page = await browser.newPage({ viewport: { width: 1920, height: 1080 } });
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
  await page.goto(url);
  await page.waitForTimeout(2200);
  return { page, errors };
}

async function inspector(name: string, testId: string, caseId: string, contestant: string, opts: { broadcast?: boolean; theme?: 'light' | 'dark'; plain?: boolean; scroll?: boolean; play?: number; run?: string } = {}) {
  const key = `${contestant}::${testId}::${caseId}::r0`;
  const url = `${base}/?mock=1${opts.plain ? '&plain=1' : ''}#/runs/${opts.run ?? RUN}?tab=matrix&test=${encodeURIComponent(testId)}&c=${contestant}&key=${encodeURIComponent(key)}`;
  const { page, errors } = await open(url, opts);
  await page.waitForSelector('.insp-detail', { timeout: 15000 }).catch(() => undefined);
  await page.waitForTimeout(1200);
  if (opts.play) {
    await page.locator('.vz-plan-player').focus().catch(() => undefined);
    for (let i = 0; i < opts.play; i++) await page.keyboard.press('ArrowRight');
    await page.waitForTimeout(900);
  }
  const overflow = await page.evaluate(() => {
    const el = document.querySelector('.vz');
    if (!el) return 'no visual';
    const bad: string[] = [];
    el.querySelectorAll<HTMLElement>('*').forEach((n) => {
      if (n.scrollWidth > n.clientWidth + 2 && getComputedStyle(n).overflowX === 'visible' && n.clientWidth > 0) bad.push(n.className.toString().slice(0, 40));
    });
    return bad.slice(0, 5).join(', ');
  });
  await page.screenshot({ path: join(out, `${name}.png`) });
  console.log(name, errors.length ? `errors: ${errors.join(' | ')}` : 'ok', overflow ? `overflow: ${overflow}` : '');
  await page.close();
}

async function slide(name: string, s: number, opts: { theme?: 'light' | 'dark' } = {}) {
  const { page, errors } = await open(`${base}/?mock=1#/present/${RUN}?truth=1&s=${s}`, opts);
  await page.waitForTimeout(3500);
  await page.screenshot({ path: join(out, `${name}.png`) });
  console.log(name, errors.length ? `errors: ${errors.join(' | ')}` : 'ok');
  await page.close();
}

const SHOTS: Array<[string, string, string]> = [
  ['grid', 'reasoning.deduction-grid', 'c09'],
  ['grid-extreme', 'reasoning.deduction-grid-extreme', 'x01'],
  ['islanders', 'reasoning.truth-tellers', 'c11'],
  ['islanders-extreme', 'reasoning.truth-tellers-extreme', 'y01'],
  ['plan-jugs', 'reasoning.planning', 'p01'],
  ['plan-bridge', 'reasoning.planning', 'p03'],
  ['plan-gondola', 'reasoning.planning', 'p04'],
  ['plan-hanoi', 'reasoning.planning', 'p05'],
  ['plan-sliding', 'reasoning.planning', 'p06'],
  ['plan-lights', 'reasoning.planning', 'p07'],
  ['plan-pancakes', 'reasoning.planning', 'p10'],
  ['plan-maze', 'reasoning.planning', 'p12'],
  ['plan-traffic', 'reasoning.planning-extreme', 'p06'],
  ['plan-burnt-pancakes', 'reasoning.planning-extreme', 'p09'],
  ['plan-notes-fallback', 'reasoning.planning-extreme', 'p02'],
  ['maths-competition', 'math.competition', 'c06'],
  ['maths-olympiad', 'math.olympiad', 'o01'],
  ['maths-receipt', 'math.word-problems', 'w01'],
  ['maths-payslip', 'math.word-problems', 'w07'],
  ['instruction-precision', 'instruction.precision-formatting', 'f01'],
  ['instruction-extreme', 'instruction.extreme-constraints', 'x01'],
  ['instruction-character', 'instruction.system-prompt-adherence', 's03'],
  ['instruction-adversarial', 'instruction.adversarial-system', 'a01'],
  ['extraction-json', 'extraction.structured-json', 'e01'],
  ['extraction-frontier', 'extraction.frontier', 'x01'],
  ['honesty-trap', 'honesty.pressure-traps', 'p01'],
  ['honesty-real', 'honesty.pressure-traps', 'p02'],
  ['code-algorithms', 'coding.algorithms', 'a1'],
  ['code-edge-cases', 'coding.debug-and-edge-cases', 'e1'],
];

// "s=17" shoots one Presenter slide only (quick checks).
if (filter.startsWith('s=')) {
  await slide(`check-slide-${filter.slice(2)}`, Number(filter.slice(2)));
  await browser.close();
  process.exit(0);
}

for (const [name, testId, caseId] of SHOTS) {
  if (filter && !name.includes(filter)) continue;
  if (!fx.tests.some((t) => t.id === testId && t.cases.some((c) => c.id === caseId))) throw new Error(`${testId} ${caseId} not in fixtures`);
  const who = MODELS.find((m) => wrong(m, testId, caseId)) ?? MODELS[0]!;
  await inspector(`${name}-after`, testId, caseId, who, { play: name.startsWith('plan-') && name !== 'plan-notes-fallback' ? 3 : 0 });
  if (!filter || filter === name) await inspector(`${name}-before`, testId, caseId, who, { plain: true });
}
if (!filter || filter === 'trick') {
  await inspector('trick-after', 'trick.modified-classics', 'm02', 'helios-quill-flash', { run: 'run-2026-09-22-trick' });
  await inspector('trick-before', 'trick.modified-classics', 'm02', 'helios-quill-flash', { plain: true, run: 'run-2026-09-22-trick' });
}
if (!filter || filter === 'extra') {
  await inspector('grid-light-after', 'reasoning.deduction-grid', 'c01', 'helios-quill-flash', { theme: 'light' });
  await inspector('islanders-broadcast-after', 'reasoning.truth-tellers', 'c05', 'helios-quill-flash', { broadcast: true });
  await inspector('instruction-light-after', 'instruction.extreme-constraints', 'x01', 'helios-quill-flash', { theme: 'light' });
}
if (!filter || filter === 'slides') {
  // Find the truth slides in the deck.
  const probe = await browser.newPage({ viewport: { width: 1920, height: 1080 } });
  const found: number[] = [];
  for (let s = 1; s <= 80; s++) {
    await probe.goto(`${base}/?mock=1#/present/${RUN}?truth=1&s=${s}`);
    await probe.waitForTimeout(300);
    if ((await probe.locator('.deck-stage[data-slide="truth"]').count()) > 0) found.push(s);
    if ((await probe.locator('.deck-stage[data-slide="outro"]').count()) > 0) break;
  }
  await probe.close();
  console.log('truth slides', found.join(','));
  for (const [i, s] of found.entries()) await slide(`present-truth-${String(i + 1).padStart(2, '0')}`, s);
  if (found[0]) await slide('present-truth-01-light', found[0], { theme: 'light' });
}
await browser.close();
