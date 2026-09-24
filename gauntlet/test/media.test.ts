import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdirSync, mkdtempSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { createServer as createNetServer, type AddressInfo } from 'node:net';

// Isolate run storage before any Gauntlet module that reads paths is loaded.
const sandbox = mkdtempSync(join(tmpdir(), 'gauntlet-media-'));
process.env.GAUNTLET_DATA_DIR = join(sandbox, 'data');
process.env.GAUNTLET_NO_BROWSER = '1';

const { craftedRun, sweepRun } = await import('./helpers/studio-fixture.ts');
const { findHighlights } = await import('../src/media/highlights.ts');
const { presenterSlides, runningOrder } = await import('../src/media/slides.ts');
const { allowedNumbers, buildScript, scriptToMarkdown, scriptToText, unverifiedNumbers } = await import('../src/media/script.ts');
const { cardData, cardFileName, cardSize, defaultCards, renderCardHtml, CARD_KINDS, CARD_STYLES } = await import('../src/media/cards.ts');
const { overlayData } = await import('../src/media/overlay.ts');
const { numberTokens } = await import('../src/media/common.ts');
const { chromiumCandidates } = await import('../src/scoring/browser.ts');
const { polishEstimate } = await import('../src/media/studio.ts');

const byType = <T extends { type: string }>(hs: T[], type: string): T[] => hs.filter((h) => h.type === type);

// ─────────────────────────────── Highlights ───────────────────────────────

test('highlights: every planted moment type is found', () => {
  const { input } = craftedRun();
  const hs = findHighlights(input);
  const ids = hs.map((h) => h.id);

  // Upset: the budget model beats the flagship on maths while spending 50× less.
  const upset = hs.find((h) => h.id === 'upset:math.x:budget:flagship');
  assert.ok(upset, `upset missing: ${ids.join(', ')}`);
  assert.deepEqual(upset.contestantIds, ['budget', 'flagship']);
  assert.equal(upset.evidence.find((e) => e.label === 'Price ratio')?.display, '50×');

  // Catastrophe: died on day 2 of 30, deep link to the replay step where it went wrong.
  const death = hs.find((h) => h.id === 'catastrophe:agentic.island:flagship');
  assert.ok(death);
  assert.match(death.title, /dies on day 2 of Survival Island/);
  assert.equal(death.replayStep, 3);
  assert.equal(death.cue, '[Replay: Survival Island, Flagship Ultra, step 3]');
  assert.match(death.link, /^#\/runs\/studio-run\?tab=matrix&test=agentic\.island&c=flagship&key=.+&step=3$/);
  assert.equal(death.clipSec, 25);
  // The baseline dying is not news.
  assert.ok(!ids.some((id) => id.startsWith('catastrophe:') && id.endsWith(':random-baseline')));

  // Confident wrong: both judges labelled the honesty reply HALLUCINATED; the rationale is quoted.
  const lie = hs.find((h) => h.type === 'confident-wrong' && h.contestantIds[0] === 'mid');
  assert.ok(lie);
  assert.match(lie.title, /falls for a trap on The Honesty Trap/);
  assert.match(lie.why, /1987 Zurich accord/);
  // …and the plain wrong final answer ("11" instead of "12").
  assert.ok(hs.some((h) => h.type === 'confident-wrong' && /"11"/.test(h.title)));

  // Fastest correct answer: 0.4 s against a 5.0 s median.
  const fast = byType(hs, 'fastest-correct')[0] as (typeof hs)[0];
  assert.equal(fast.contestantIds[0], 'budget');
  assert.deepEqual(fast.evidence.map((e) => e.display), ['0.4 s', '5.0 s', '13×']);

  // Most expensive wrong answer (one per model), with the cheapest correct answer to the same case.
  const pricey = byType(hs, 'expensive-wrong');
  assert.ok(pricey.length >= 1);
  assert.ok(pricey.every((h) => h.evidence[0]!.value > 0));

  // Best value: points per dollar.
  const value = byType(hs, 'best-value')[0] as (typeof hs)[0];
  assert.equal(value.contestantIds[0], 'budget');
  assert.equal(value.evidence.find((e) => e.label === 'Index points per dollar')?.display, String(Math.round(75 / 0.026)));

  // Inconsistency: 100 on one attempt, 0 on the other.
  const flip = byType(hs, 'inconsistent')[0] as (typeof hs)[0];
  assert.equal(flip.contestantIds[0], 'mid');
  assert.deepEqual(flip.evidence.map((e) => e.display), ['100', '0', '100']);

  // Per-test close race (62 vs 60) and a flawless test.
  assert.ok(hs.some((h) => h.id === 'close-race:agentic.island:mid:budget'));
  assert.ok(hs.some((h) => h.id === 'clean-sweep:math.x:budget'));

  // Sorted by drama, highest first; one clip per case.
  for (let i = 1; i < hs.length; i++) assert.ok(hs[i - 1]!.drama >= hs[i]!.drama);
  const keys = hs.filter((h) => h.key).map((h) => h.key);
  assert.equal(new Set(keys).size, keys.length);
});

test('highlights: overall clean sweep and photo finish', () => {
  const hs = findHighlights(sweepRun());
  const sweep = hs.find((h) => h.id === 'clean-sweep:overall:flagship');
  assert.ok(sweep);
  assert.equal(hs[0]!.id, sweep.id, 'a clean sweep is the top story');
  const close = hs.find((h) => h.id === 'close-race:overall:flagship:mid');
  assert.ok(close);
  assert.deepEqual(close.evidence.map((e) => e.display), ['80.0', '78.0', '2.0']);
  assert.match(close.link, /^#\/present\/sweep-run\?s=\d+$/);
});

test('highlights: unknown test types fall back to generic score moments', () => {
  const { input } = craftedRun();
  const hs = findHighlights(input);
  const generic = hs.filter((h) => h.testId === 'creative.future-game');
  assert.ok(generic.length >= 1);
  assert.ok(generic.some((h) => h.type === 'big-win' && /wins Future Game by 40 points/.test(h.title)));
});

test('highlights: deterministic and independent of result order', () => {
  const a = craftedRun();
  const b = craftedRun();
  b.input.results = [...b.input.results].reverse();
  assert.deepEqual(findHighlights(a.input), findHighlights(b.input));
});

test('highlights: every number in a title or explanation is backed by data or evidence', () => {
  for (const input of [craftedRun().input, sweepRun()]) {
    const hs = findHighlights(input);
    const allowed = allowedNumbers(input, hs);
    for (const h of hs) {
      assert.ok(h.rule.length > 20, `${h.id} explains its rule`);
      assert.ok(h.evidence.length >= 1, `${h.id} shows its numbers`);
      assert.deepEqual(unverifiedNumbers(`${h.title} ${h.why}`, allowed), [], h.id);
    }
  }
});

test('highlights: an empty run produces no moments and no crash', () => {
  const { input } = craftedRun();
  const empty = { ...input, results: [], leaderboard: null };
  assert.deepEqual(findHighlights(empty), []);
  const s = buildScript(empty, []);
  assert.ok(s.sections.some((x) => x.id === 'outro'));
});

// ─────────────────────────────── Slides & script ───────────────────────────────

test('slides: same running order and numbering as the Presenter', () => {
  const { input } = craftedRun();
  // categories.json order: reasoning, math, honesty, agentic, creative (for this fixture)
  assert.deepEqual(runningOrder(input), ['math.x', 'honesty.trap', 'agentic.island', 'creative.future-game']);
  const slides = presenterSlides(input);
  assert.deepEqual(
    slides.map((s) => `${s.n}:${s.kind}${s.testId ? `:${s.testId}` : ''}`),
    [
      '1:title',
      '2:how',
      '3:explainer:math.x',
      '4:result:math.x',
      '5:explainer:honesty.trap',
      '6:result:honesty.trap',
      '7:explainer:agentic.island',
      '8:result:agentic.island',
      '9:explainer:creative.future-game',
      '10:result:creative.future-game',
      '11:final',
      '12:scatter',
      '13:medals',
      '14:outro',
    ],
  );
});

test('script: hook, intro with prices, per-test segments with slide cues, recap, reveal, outro', () => {
  const { input } = craftedRun();
  const hs = findHighlights(input);
  const s = buildScript(input, hs);
  assert.deepEqual(
    s.sections.map((x) => x.id),
    ['hook', 'intro', 'test-math.x', 'test-honesty.trap', 'recap', 'test-agentic.island', 'test-creative.future-game', 'final', 'value', 'medals', 'outro'],
  );
  const text = (id: string) => s.sections.find((x) => x.id === id)!.lines.join('\n');
  assert.match(text('hook'), /^\[(Case|Replay|Slide)/);
  assert.ok(s.sections[0]!.estSec <= 15, 'hook fits the first ~10 seconds');
  assert.match(text('intro'), /Flagship Ultra from Anthropic, priced at \$10 per million input tokens and \$50 per million output tokens/);
  assert.match(text('intro'), /Random Baseline/);
  assert.match(text('test-math.x'), /^\[Slide 3\] Test one: Competition Maths\. Twenty contest problems\. No partial credit\./);
  assert.match(text('test-math.x'), /\[Slide 4\] Budget Mini takes it with 100, ahead of Mid Pro on 83\./);
  assert.match(text('test-agentic.island'), /\[Replay: Survival Island, Flagship Ultra, step 3\] Flagship Ultra dies on day 2/);
  assert.match(text('final'), /^\[Slide 11\]/);
  assert.match(text('final'), /And the winner: Budget Mini, with 75\.0!/);
  // The reveal goes from last place to first.
  assert.ok(text('final').indexOf('Flagship Ultra') < text('final').indexOf('Mid Pro'));
  assert.match(text('outro'), /^\[Slide 14\]/);
  // Each test gets at most one highlight; no highlight is used twice.
  const cues = s.sections.flatMap((x) => x.lines).join('\n').match(/\[(Replay|Case):[^\]]+\]/g) ?? [];
  assert.equal(new Set(cues).size, cues.length);
});

test('script: contains no number that is not in the data (Markdown and plain text)', () => {
  for (const input of [craftedRun().input, sweepRun()]) {
    const hs = findHighlights(input);
    const s = buildScript(input, hs);
    const allowed = allowedNumbers(input, hs);
    const md = scriptToMarkdown(s);
    const txt = scriptToText(s);
    assert.ok(numberTokens(md).length > 10);
    assert.deepEqual(unverifiedNumbers(md, allowed), []);
    assert.deepEqual(unverifiedNumbers(txt, allowed), []);
    // An invented statistic is caught (this is what flags an AI polish that made numbers up).
    assert.deepEqual(unverifiedNumbers(`${txt}\nBudget Mini is 42.7% smarter and won 9876 games.`, allowed), ['42.7', '9876']);
  }
});

test('script: markdown bolds cues; plain text keeps them verbatim', () => {
  const { input } = craftedRun();
  const s = buildScript(input, findHighlights(input));
  assert.match(scriptToMarkdown(s), /\*\*\[Slide 3\]\*\*/);
  assert.match(scriptToText(s), /^\[Slide 3\] /m);
  assert.ok(s.wordCount > 100 && s.estSec > 30);
});

test('polish: the cost estimate uses the model price and grows with the draft', () => {
  const { manifest } = craftedRun();
  const c = manifest.contestants[0]!;
  const small = polishEstimate(c, 'x'.repeat(4000));
  const big = polishEstimate(c, 'x'.repeat(40000));
  assert.ok(small.costUsd > 0 && big.costUsd > small.costUsd * 5);
  assert.ok(small.costUsdHigh > small.costUsd);
  assert.equal(small.inputTokens > 1000, true);
});

// ─────────────────────────────── Cards ───────────────────────────────

test('cards: every kind and style renders a self-contained page at its fixed size', () => {
  const { input } = craftedRun();
  const hs = findHighlights(input);
  const data = cardData(input, hs);
  assert.equal(data.contestants[0]!.id, 'budget', 'standings order');
  assert.equal(data.contestants.at(-1)!.id, 'random-baseline', 'baseline last');
  for (const k of CARD_KINDS) {
    for (const st of CARD_STYLES) {
      const spec = { kind: k.id, style: st.id, testId: 'honesty.trap', highlightId: hs[0]!.id };
      const html = renderCardHtml(data, spec);
      const { width, height } = cardSize(k.id);
      assert.match(html, new RegExp(`width:${width}px;height:${height}px`));
      assert.doesNotMatch(html, /<script|https?:\/\//i, 'no scripts, no network');
    }
  }
  const thumb = renderCardHtml(data, { kind: 'thumbnail', style: 'versus' });
  assert.match(thumb, /BUDGET vs MID/i);
  assert.match(thumb, />75</);
  assert.match(thumb, /class="crown"/);
  const custom = renderCardHtml(data, { kind: 'thumbnail', style: 'bold', headline: 'Can <b>it</b> win?' });
  assert.match(custom, /CAN &lt;B&gt;IT&lt;\/B&gt; WIN\?/, "headline is escaped");
  const q = renderCardHtml(data, { kind: 'short-question', style: 'clean', testId: 'honesty.trap' });
  assert.match(q, /Half these questions are lies/);
  const specs = defaultCards(data);
  assert.equal(specs.filter((s) => s.kind === 'thumbnail').length, CARD_STYLES.length);
  const names = specs.map((s, i) => cardFileName(s, i));
  assert.equal(new Set(names).size, names.length);
  for (const n of names) assert.match(n, /^[a-z0-9.-]+\.png$/, 'Windows-safe file name');
});

// ─────────────────────────────── Chromium discovery ───────────────────────────────

test('browser: finds installed Chrome / Edge on Windows, macOS and Linux', () => {
  const none = () => [] as string[];
  const win = chromiumCandidates({
    env: { PROGRAMFILES: 'C:\\Program Files', 'PROGRAMFILES(X86)': 'C:\\Program Files (x86)', LOCALAPPDATA: 'C:\\Users\\Ann\\AppData\\Local' },
    platform: 'win32',
    home: 'C:\\Users\\Ann',
    listDir: none,
  });
  for (const p of [
    'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe',
    'C:\\Program Files (x86)\\Google\\Chrome\\Application\\chrome.exe',
    'C:\\Users\\Ann\\AppData\\Local\\Google\\Chrome\\Application\\chrome.exe',
    'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe',
    'C:\\Program Files\\Microsoft\\Edge\\Application\\msedge.exe',
  ]) {
    assert.ok(win.includes(p), p);
  }
  assert.ok(win.indexOf('C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe') < win.indexOf('C:\\Program Files\\Microsoft\\Edge\\Application\\msedge.exe'), 'Chrome before Edge');
  assert.ok(!win.some((p) => p.startsWith('/')), 'no POSIX paths on Windows');

  const mac = chromiumCandidates({ env: {}, platform: 'darwin', home: '/Users/ann', listDir: none });
  assert.ok(mac.includes('/Applications/Google Chrome.app/Contents/MacOS/Google Chrome'));
  assert.ok(mac.includes('/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge'));

  const linux = chromiumCandidates({ env: { PATH: '/usr/local/bin:/usr/bin' }, platform: 'linux', home: '/home/ann', listDir: none });
  for (const p of ['/usr/local/bin/google-chrome', '/usr/bin/chromium', '/usr/bin/microsoft-edge']) assert.ok(linux.includes(p), p);

  // GAUNTLET_CHROMIUM wins, then Playwright's own downloads, then the installed browsers.
  const withPw = chromiumCandidates({
    env: { GAUNTLET_CHROMIUM: 'D:\\chrome\\chrome.exe', LOCALAPPDATA: 'C:\\L' },
    platform: 'win32',
    home: 'C:\\Users\\Ann',
    listDir: (d) => (d === 'C:\\L\\ms-playwright' ? ['chromium-1100', 'chromium-1200', 'ffmpeg-1'] : []),
  });
  assert.equal(withPw[0], 'D:\\chrome\\chrome.exe');
  assert.equal(withPw[1], 'C:\\L\\ms-playwright\\chromium-1200\\chrome-linux\\chrome');
  assert.ok(withPw.indexOf('C:\\L\\ms-playwright\\chromium-1200\\chrome-win\\chrome.exe') < withPw.indexOf('C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe'));
});

// ─────────────────────────────── Overlays ───────────────────────────────

test('overlay data: standings, ticker, now testing, per-test winners', () => {
  const { input, manifest, results } = craftedRun();
  const o = overlayData(manifest, results, input.leaderboard, input.tests, false);
  assert.equal(o.scoreKind, 'index');
  assert.deepEqual(o.standings.map((s) => s.id).slice(0, 3), ['budget', 'mid', 'flagship']);
  assert.equal(o.standings[0]!.score, 75);
  assert.equal(o.ticker.length, 12);
  assert.ok(o.ticker[0]!.at >= o.ticker[1]!.at);
  assert.equal(o.now, null, 'nothing left to test');
  assert.equal(o.tests.find((t) => t.id === 'creative.future-game')!.winner!.id, 'flagship');

  // Live: half the results in, averages instead of the Index, "now testing" points at an unfinished test.
  const live = overlayData({ ...manifest, status: 'running' }, results.filter((r) => r.repeat === 0), null, input.tests, true);
  assert.equal(live.scoreKind, 'average');
  assert.equal(live.status, 'running');
  assert.ok(live.now);
  assert.ok(live.progress.completed < live.progress.total);
});

test('overlay + studio routes over HTTP (latest alias, redirect, render fallback)', async () => {
  const { manifest, results } = craftedRun('20260920-100000-abcd');
  // Use real library test ids for one test so hooks come from the library; the rest fall back to the manifest.
  const dir = join(sandbox, 'data', 'runs', manifest.id);
  mkdirSync(join(dir, 'artifacts'), { recursive: true });
  writeFileSync(join(dir, 'manifest.json'), JSON.stringify(manifest));
  writeFileSync(join(dir, 'results.jsonl'), results.map((r) => JSON.stringify(r)).join('\n') + '\n');
  const { startServer } = await import('../src/server/index.ts');
  const port = await new Promise<number>((resolve) => {
    const probe = createNetServer().listen(0, '127.0.0.1', () => {
      const p = (probe.address() as AddressInfo).port;
      probe.close(() => resolve(p));
    });
  });
  const srv = await startServer({ port, host: '127.0.0.1' });
  const base = srv.url;
  try {
    const get = async (p: string, init?: RequestInit) => fetch(base + p, { redirect: 'manual', ...init });
    const ov = (await (await get('/api/overlay/latest')).json()) as { runId: string; standings: Array<{ id: string }> };
    assert.equal(ov.runId, manifest.id);
    assert.equal(ov.standings[0]!.id, 'budget');
    const st = (await (await get(`/api/studio/${manifest.id}`)).json()) as { highlights: unknown[]; markdown: string; browser: { available: boolean } };
    assert.ok(st.highlights.length > 5);
    assert.match(st.markdown, /\[Slide 3\]/);
    assert.equal(st.browser.available, false);
    const render = await get(`/api/studio/${manifest.id}/render`, { method: 'POST', body: JSON.stringify({ spec: { kind: 'thumbnail', style: 'versus' } }), headers: { 'content-type': 'application/json' } });
    assert.equal(render.status, 409, 'no browser → the UI renders the PNG itself');
    const redirect = await get('/overlay/latest?view=ticker&theme=glass');
    assert.equal(redirect.status, 302);
    assert.equal(redirect.headers.get('location'), '/#/overlay/latest?view=ticker&theme=glass');
    assert.equal((await get('/api/overlay/nope')).status, 404);
  } finally {
    await srv.close();
  }
});
