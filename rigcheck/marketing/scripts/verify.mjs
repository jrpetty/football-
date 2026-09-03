/**
 * Fact-check the published marketing content against the data it claims to
 * come from.
 *
 * pillars.md states the rule — "no invented numbers, every figure traces to
 * builds.json, bottleneck.json or pillars.json" — and for a while nothing
 * enforced it. An audit found six violations in published copy: a card count
 * that disagreed with the table under it, a "won't start" that was false about
 * the real world, a stale accuracy figure, a PSU header that undercut the
 * parts list, and two universal claims ("everything clears 165fps", "every
 * single number went up") that the data contradicts on 3 of 6 and 6 of 24 rows
 * respectively. Every one reads fine. That is the point: prose degrades
 * silently as the model behind it improves, and only a machine notices.
 *
 * Three checks:
 *   1. REGENERATION — the committed JSON still matches what the app produces.
 *   2. TABLES — every fps figure in a markdown table exists in that JSON.
 *   3. CLAIMS — a register of quantified prose claims, each paired with an
 *      assertion. A claim fails if its quote has drifted OR if the data no
 *      longer supports it. Both directions matter: stale prose and stale
 *      checks are the same bug.
 *
 *   4. TEMPLATES — no typed-in figure in the card renderer.
 *   5. IMAGES — every image captioned with the sentence printed on it.
 *   6. PRICE MOVEMENT — a caption saying "down 12% since August" is computed
 *      from the observed series, by the same function the app uses.
 *
 * Run: npm run marketing:verify   (tsx, so it can import the app's own modules)
 */
import { readFileSync, writeFileSync, copyFileSync, mkdtempSync } from 'node:fs';
import { changeSinceMonth, describeChange, monthLabel } from '../../src/core/pricetrend.ts';
import { MEMORY_CAVEAT } from './lib/captions.ts';
import { execFileSync } from 'node:child_process';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

const R = 'marketing';
const read = (f) => readFileSync(`${R}/${f}`, 'utf8');
const json = (f) => JSON.parse(read(f));

let failures = 0;
const fail = (what, detail) => { failures++; console.log(`  FAIL  ${what}\n        ${detail}`); };
const pass = (what) => console.log(`  ok    ${what}`);

// --- 1. regeneration -------------------------------------------------------
console.log('\nREGENERATION — does the committed data still match the app?');
{
  const stash = mkdtempSync(join(tmpdir(), 'rigcheck-verify-'));
  const files = ['builds.json', 'bottleneck.json', 'pillars.json'];
  for (const f of files) copyFileSync(`${R}/${f}`, join(stash, f));
  const before = Object.fromEntries(files.map((f) => [f, read(f)]));
  for (const s of ['plans', 'bottleneck', 'pillars']) {
    execFileSync('npx', ['tsx', `${R}/scripts/${s}.ts`], { stdio: 'pipe' });
  }
  for (const f of files) {
    if (read(f) === before[f]) pass(`${f} regenerates identically`);
    else fail(`${f} has drifted`, 'the committed file is not what the app now produces — re-render the images too');
  }
}

const builds = json('builds.json');
const sourcedIds = new Set((JSON.parse(readFileSync('data/pricing/observed.json', 'utf8')).prices ?? []).map((o) => o.partId));
const bottleneck = json('bottleneck.json');
const pillars = json('pillars.json');

// --- 2. tables -------------------------------------------------------------
console.log('\nTABLES — does every quoted fps figure exist in the data?');
{
  const md = read('blog-four-builds.md');
  let bad = 0, n = 0;
  for (const b of builds) for (const r of b.rows) {
    n++;
    const esc = r.game.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
    if (!new RegExp(`\\|\\s*${esc}\\s*\\|\\s*${r.fps} fps\\s*\\|\\s*${r.low1} fps`).test(md)) {
      fail(`${b.name} / ${r.game}`, `data says ${r.fps}/${r.low1}, the table does not`);
      bad++;
    }
  }
  if (!bad) pass(`all ${n} build-table rows match builds.json`);

  const cpu = read('blog-cpu-bottleneck.md');
  bad = 0; n = 0;
  for (const s of bottleneck) for (const g of s.games) {
    n++;
    const cells = g.points.map((p) => p.fps).join(' | ');
    if (!cpu.includes(cells)) { fail(`${s.gpuShort} / ${g.game}`, `data row "${cells}" is not in the article`); bad++; }
  }
  if (!bad) pass(`all ${n} bottleneck-table rows match bottleneck.json`);
}

// --- 3. claims register ----------------------------------------------------
// Each entry: the exact published sentence, and what has to be true for it.
console.log('\nCLAIMS — is each quantified prose claim still true?');
const B = (t) => builds.find((b) => b.total === t);
const T = (budget) => builds.find((b) => b.budget === budget);
const fps = (t, game) => B(t).rows.find((r) => r.game === game).fps;
const lad = (gpu, game) => bottleneck.find((s) => new RegExp(gpu, 'i').test(s.gpuShort)).games.find((g) => g.game === game);
const old = (name) => pillars.stillGood.cards.find((c) => new RegExp(name, 'i').test(c.gpu));
const oldFps = (name, game) => old(name).rows.find((r) => new RegExp(game, 'i').test(r.game)).fps;

const CLAIMS = [
  // The two AM5 tiers are addressed by BUDGET, not by total: totals move with prices.
  { file: 'instagram.md', quote: '£473 more buys 18 more frames',
    check: () => T(2600).total - T(1800).total === 473 && T(2600).cyberpunk1440 - T(1800).cyberpunk1440 === 18 },
  { file: 'blog-four-builds.md', quote: '18 more frames — about 16%, for 27% more money',
    check: () => Math.round((T(2600).cyberpunk1440 / T(1800).cyberpunk1440 - 1) * 100) === 16
              && Math.round((T(2600).total / T(1800).total - 1) * 100) === 27 },
  { file: 'blog-four-builds.md', quote: 'It came in £346 under a £2,600 budget',
    check: () => T(2600).budget - T(2600).total === 346 && T(2600).budget === 2600 },
  { file: 'blog-four-builds.md', quote: 'the same number the £582 machine\nmanages at 1080p',
    check: () => T(2600).rows.find((r) => r.game === 'Cyberpunk 2077').fps === fps(582, 'Cyberpunk 2077') },
  { file: 'blog-four-builds.md', quote: 'The two competitive titles clear 165fps with room to spare',
    // The claim it replaced said "everything except Cyberpunk", which was wrong
    // on three of six rows. Pin the exact shape so it cannot regress.
    check: () => {
      const over = T(1800).rows.filter((r) => r.fps >= 165).map((r) => r.game);
      return over.length === 3 && over.includes('Counter-Strike 2')
          && over.includes('Call of Duty Black Ops 6') && over.includes('Fortnite');
    } },
  { file: 'blog-cpu-bottleneck.md', quote: 'The Ryzen 5 3600 reads 60 in\nBaldur\'s Gate 3 and 71 in Total War on *both* cards — identical, to the frame.',
    check: () => {
      const at = (gpu, game) => lad(gpu, game).points.find((p) => p.cpu === 'Ryzen 5 3600').fps;
      return at('4070', "Baldur's Gate 3") === 60 && at('4090', "Baldur's Gate 3") === 60
          && at('4070', 'Total War Warhammer III') === 71 && at('4090', 'Total War Warhammer III') === 71;
    } },
  { file: 'blog-cpu-bottleneck.md', quote: 'lands one frame off the best part on this list — 140 against 141',
    check: () => { const p = lad('4070', 'Fortnite').points;
      return p.find((x) => x.cpu === 'Ryzen 5 5600').fps === 140 && p[p.length - 1].fps === 141; } },
  { file: 'blog-cpu-bottleneck.md', quote: 'the same processor leaves 52 frames on the table.',
    check: () => { const p = lad('4090', 'Total War Warhammer III').points;
      return p[p.length - 1].fps - p.find((x) => x.cpu === 'Ryzen 5 5600').fps === 52; } },
  { file: 'instagram.md', quote: 'The best processor on this list is worth +67% in Baldur\'s Gate 3\nand +4% in Fortnite.',
    check: () => lad('4070', "Baldur's Gate 3").gainPct === 67 && lad('4070', 'Fortnite').gainPct === 4 },
  { file: 'instagram.md', quote: 'A second stick of RAM is worth up to +46%',
    check: () => Math.max(...pillars.silentTax.rows.map((r) => r.gainPct)) === 46 },
  { file: 'instagram.md', quote: 'Same chip, sold with 8GB and with 16GB.',
    // Not a number — a fact about the parts. If these ever stop being one die
    // the whole post is void, so assert the silicon, not the frame rates.
    check: () => pillars.vram.a.chip === pillars.vram.b.chip
              && pillars.vram.a.shaders === pillars.vram.b.shaders
              && pillars.vram.a.vramGB !== pillars.vram.b.vramGB },
  { file: 'instagram.md', quote: 'Every one of them still clears 60 in a shooter.',
    check: () => pillars.stillGood.cards.every((c) =>
      c.rows.filter((r) => !/cyberpunk/i.test(r.game)).every((r) => r.fps >= 60)) },
  { file: 'instagram.md', quote: '21 to 66 across four cards',
    check: () => { const f = pillars.stillGood.cards.map((c) => oldFps(c.gpu, 'cyberpunk')).filter(Boolean);
      return f.length === 4 && Math.min(...f) === 21 && Math.max(...f) === 66; } },
  { file: 'instagram.md', quote: 'the GTX 970 is under Cyberpunk\'s published 6GB minimum, so 21fps is\nthe honest answer rather than a refusal',
    // The bug this whole register exists because of: the model used to return
    // WILL_NOT_RUN here and the copy said the game "just refused". It does not.
    check: () => old('970').vram === 4 && oldFps('970', 'cyberpunk') === 21 },
  // Prices are the softest data in the project and the loudest thing in the
  // copy. Every disclaimer used to be about frame rates only, while the
  // headline claim was "£582 vs £2,012". If a post quotes a price it has to
  // say where the price came from.
  { file: 'blog-four-builds.md', quote: 'recalled UK street price',
    check: () => readFileSync('data/pricing/gbp-new.json', 'utf8').includes('SEED PRICES') },
  { file: 'instagram.md', quote: 'recalled UK street prices, not scraped from any retailer',
    check: () => readFileSync('data/pricing/gbp-new.json', 'utf8').includes('SEED PRICES') },
  // A BOM that does not sum to its own total is a build sheet nobody can buy.
  { file: 'blog-four-builds.md', quote: '## The four builds',
    check: () => builds.every((b) => b.bom.reduce((t, x) => t + x.price, 0) === b.total) },
  // The header quotes the PSU a reader would actually buy, not the model's
  // floor. Publishing the floor next to a bigger unit in the parts list is how
  // somebody ends up buying a 400W supply for this machine.
  { file: 'blog-four-builds.md', quote: '260W draw, 550W supply',
    check: () => B(582).psuPartW === 550 && B(582).powerW === 260 && B(582).psuPartW >= B(582).psuW },
  // While one memory type is sourced and the other is not, a DDR4 build shown
  // beside a DDR5 one is cheaper than reality by an unknown amount, and every
  // build post has to say so. The sentence is defined once, in captions.ts.
  ...(sourcedIds.has('memory.DDR5.32') && !sourcedIds.has('memory.DDR4.32')
    ? [{ file: 'instagram.md', quote: MEMORY_CAVEAT, check: () => read('instagram.md').split(MEMORY_CAVEAT).length - 1 >= 5 },
       { file: 'blog-four-builds.md', quote: MEMORY_CAVEAT, check: () => true }]
    : []),
  { file: 'blog-four-builds.md', quote: 'a median error of about 11% against its',
    check: (v) => v.medianApe != null && Math.round(v.medianApe) === 11,
    needsValidation: true },
  { file: 'instagram.md', quote: 'Mine says 11% median error against its validation set',
    check: (v) => v.medianApe != null && Math.round(v.medianApe) === 11,
    needsValidation: true },
];

// The validation figure is published in four places and drifts every time the
// model is tuned. Read it rather than trusting the copy — from the last run's
// artefact, not by re-running validate.ts, which appends to a tracked tuning
// log. A check that dirties the working tree every time it runs is a check
// people stop running.

const validation = (() => {
  try {
    const r = JSON.parse(readFileSync('data/validation/last-run.json', 'utf8'));
    return { medianApe: r.crossValidation.medianAPE * 100, at: r.generatedAt };
  } catch { return {}; }
})();

for (const c of CLAIMS) {
  const text = read(c.file);
  if (!text.includes(c.quote)) {
    fail(`${c.file}: quote has drifted`, `not found verbatim: "${c.quote.replace(/\n/g, ' ').slice(0, 78)}"`);
    continue;
  }
  if (c.needsValidation && validation.medianApe == null) {
    fail(`${c.file}: could not read the validation figure`, 'data/validation/last-run.json is missing or unreadable — run npm run validate');
    continue;
  }
  let ok = false;
  try { ok = c.check(validation); } catch (e) { fail(`${c.file}: check threw`, String(e.message)); continue; }
  if (ok) pass(`${c.file}: ${c.quote.replace(/\n/g, ' ').slice(0, 62)}`);
  else fail(`${c.file}: the data no longer supports this`, `"${c.quote.replace(/\n/g, ' ').slice(0, 78)}"`);
}


// --- 4. templates ----------------------------------------------------------
// The renderer is where numbers turn into pixels, and the markdown checks above
// cannot see pixels. The silent-tax card once said "40%" in 78-point type over
// data that said 46 — typed into the template, never derived. So: walk every
// template literal in cards.mjs (recursing into ${...} expressions, which hold
// nested templates), drop the expressions, and fail on any figure with a unit
// left in the literal text. A number the data did not put there is a number
// nobody is checking.
console.log('\nTEMPLATES — no hand-typed figures in the card renderer?');
{
  const hits = [];
  const UNIT = /(£\s*\d[\d,]*|\d[\d,]*(?:\.\d+)?\s*(?:%|fps|GB|Hz|W\b))/g;
  // Terms that carry a digit but are names, not data.
  const ALLOW = ['1% low'];
  const check = (text) => {
    // Inline styles are presentation, not content: border-radius:50% is a circle.
    let t = text.replace(/style="[^"]*"/g, ' ');
    for (const a of ALLOW) t = t.split(a).join(' ');
    for (const m of t.match(UNIT) ?? []) hits.push(`"${m}" in "${text.replace(/\s+/g, ' ').trim().slice(0, 70)}"`);
  };
  const skipString = (src, i) => { const q = src[i]; let j = i + 1; while (j < src.length && src[j] !== q) { if (src[j] === '\\') j++; j++; } return j + 1; };
  // Index of the closing backtick for a template whose body starts at i.
  const templateEnd = (src, i) => {
    while (i < src.length) {
      const c = src[i];
      if (c === '\\') { i += 2; continue; }
      if (c === '`') return i;
      if (c === '$' && src[i + 1] === '{') { i = exprEnd(src, i + 2); continue; }
      i++;
    }
    return src.length;
  };
  // Index just past the brace that closes an expression whose body starts at i.
  const exprEnd = (src, i) => {
    let depth = 1;
    while (i < src.length && depth) {
      const c = src[i];
      if (c === '`') { i = templateEnd(src, i + 1) + 1; continue; }
      if (c === "'" || c === '"') { i = skipString(src, i); continue; }
      if (c === '{') depth++; else if (c === '}') depth--;
      i++;
    }
    return i;
  };
  const scanTemplate = (t) => {
    let i = 0, text = '';
    while (i < t.length) {
      if (t[i] === '$' && t[i + 1] === '{') { const e = exprEnd(t, i + 2); scanSource(t.slice(i + 2, e - 1)); text += ' · '; i = e; continue; }
      text += t[i++];
    }
    check(text);
  };
  const scanSource = (src) => {
    let i = 0;
    while (i < src.length) {
      const c = src[i];
      if (c === '/' && src[i + 1] === '/') { const j = src.indexOf('\n', i); i = j < 0 ? src.length : j; continue; }
      if (c === '/' && src[i + 1] === '*') { const j = src.indexOf('*/', i + 2); i = j < 0 ? src.length : j + 2; continue; }
      if (c === "'" || c === '"') { i = skipString(src, i); continue; }
      if (c === '`') { const e = templateEnd(src, i + 1); scanTemplate(src.slice(i + 1, e)); i = e + 1; continue; }
      i++;
    }
  };
  for (const file of ['marketing/scripts/cardlib.mjs', 'marketing/scripts/cards.mjs']) {
    let src = readFileSync(file, 'utf8');
    // Stylesheets are not content: widths like 100% live there by design. Any
    // template literal assigned to a name containing "css" is one.
    for (;;) {
      const m = /(?:export )?const \w*(?:css|CSS)\w* = (?:\([^)]*\) => )?`/.exec(src);
      if (!m) break;
      const e = templateEnd(src, m.index + m[0].length);
      src = src.slice(0, m.index) + src.slice(e + 1);
    }
    scanSource(src);
  }
  if (hits.length) for (const h of hits) fail('the card renderer has a typed-in figure', h);
  else pass('cardlib.mjs + cards.mjs: every figure on every card comes through a data expression');
}


// --- 5. images -------------------------------------------------------------
// A picture never goes out without its caption, and the caption never
// describes a different picture. The renderer writes cards.json — every image
// it makes, with the "what this shows" sentence printed on it — and every one
// must appear in instagram.md on a line reading `images/name.png` — shows: …
// with that exact sentence. Both directions: an image with no caption fails,
// and a caption pointing at an image the renderer does not make fails.
console.log('\nIMAGES — does every image travel with its caption?');
{
  const manifest = JSON.parse(read('cards.json'));
  const ig = read('instagram.md');
  const shows = new Map();
  for (const m of ig.matchAll(/`images\/([\w-]+)\.png`[^\n]*?—\s*shows:\s*([^\n]+)/g)) shows.set(m[1], m[2].trim());
  const referenced = new Set([...ig.matchAll(/`images\/([\w-]+)\.png`/g)].map((m) => m[1]));
  let bad = 0;
  for (const c of manifest) {
    const got = shows.get(c.name);
    if (!got) { fail(`${c.file} has no caption`, 'no line in instagram.md reads `images/…png` — shows: …'); bad++; continue; }
    if (got !== c.subject) { fail(`${c.file}: the caption describes a different picture`, `card:    "${c.subject}"\n        caption: "${got}"`); bad++; }
  }
  for (const r of referenced) if (!manifest.some((c) => c.name === r)) { fail(`instagram.md points at images/${r}.png`, 'the renderer does not make it'); bad++; }
  if (!bad) pass(`${manifest.length} images, each captioned with the sentence printed on it`);

  // Per-game caption lines are the other place a figure can drift: every
  // "· Game — Nfps" line must be a row of some build.
  const rows = new Set(builds.flatMap((b) => b.rows.map((r) => `${r.game} — ${r.fps}fps`)));
  let n = 0; bad = 0;
  for (const m of ig.matchAll(/^· (.+? — \d+fps)$/gm)) { n++; if (!rows.has(m[1])) { fail('instagram.md per-game line is not in builds.json', m[1]); bad++; } }
  if (!bad) pass(`${n} per-game caption lines match builds.json`);
}


// --- 6. price movement ---------------------------------------------------------
// A price claim in a caption names a part, a condition and a month, and the
// sentence must be exactly what pricetrend.ts computes from the observed
// series. Nothing here reads the seed tables: a recalled figure has no date it
// was observed on, so it cannot start a trend, and a caption cannot say
// "since August" about a part that was not observed in August.
console.log('\nPRICE MOVEMENT — do price-change captions match the observed series?');
{
  const observed = JSON.parse(readFileSync('data/pricing/observed.json', 'utf8')).prices ?? [];
  const PRICE_CLAIMS = [
    // { file: 'instagram.md', part: 'nvidia-geforce-rtx-3070', condition: 'used', since: '2026-08' },
  ];
  let bad = 0;
  for (const c of PRICE_CLAIMS) {
    const o = observed.find((p) => p.partId === c.part && p.condition === c.condition);
    const m = o && changeSinceMonth(o.series, c.since);
    if (!m) { fail(`${c.file}: no basis for a "since ${monthLabel(c.since)}" claim on ${c.part}`, 'needs an observation in that month and a later one outside it'); bad++; continue; }
    const sentence = describeChange(m, monthLabel(c.since));
    if (!read(c.file).includes(sentence)) { fail(`${c.file}: price claim does not match the series`, `expected "${sentence}" for ${c.part} (${c.condition})`); bad++; }
    else pass(`${c.file}: "${sentence}" (${c.part}, ${c.condition})`);
  }
  const watched = observed.filter((p) => (p.series?.length ?? 0) > 1);
  if (!bad) pass(`${PRICE_CLAIMS.length} price claim(s) checked; ${watched.length} part(s) observed on more than one date could support one`);
}

console.log(`\n${failures === 0 ? 'PASS' : `${failures} FAILURE${failures > 1 ? 'S' : ''}`} — ${CLAIMS.length} claims, ${builds.length} builds, ${bottleneck.length} ladders`);
if (failures) console.log('Published copy disagrees with the data behind it. Fix the copy or re-render, then re-run.');
process.exit(failures ? 1 : 0);
