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
 * Run: node marketing/scripts/verify.mjs
 */
import { readFileSync, writeFileSync, copyFileSync, mkdtempSync } from 'node:fs';
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
const fps = (t, game) => B(t).rows.find((r) => r.game === game).fps;
const lad = (gpu, game) => bottleneck.find((s) => new RegExp(gpu, 'i').test(s.gpuShort)).games.find((g) => g.game === game);
const old = (name) => pillars.stillGood.cards.find((c) => new RegExp(name, 'i').test(c.gpu));
const oldFps = (name, game) => old(name).rows.find((r) => new RegExp(game, 'i').test(r.game)).fps;

const CLAIMS = [
  { file: 'instagram.md', quote: '£293 more buys 14 more frames',
    check: () => B(2012).total - B(1719).total === 293 && B(2012).cyberpunk1440 - B(1719).cyberpunk1440 === 14 },
  { file: 'blog-four-builds.md', quote: '14 more frames — about 12%, for 17% more money',
    check: () => Math.round((B(2012).cyberpunk1440 / B(1719).cyberpunk1440 - 1) * 100) === 12
              && Math.round((B(2012).total / B(1719).total - 1) * 100) === 17 },
  { file: 'blog-four-builds.md', quote: 'It came in £588 under a £2,600 budget',
    check: () => B(2012).budget - B(2012).total === 588 && B(2012).budget === 2600 },
  { file: 'blog-four-builds.md', quote: 'the same number the £582 machine\nmanages at 1080p',
    check: () => fps(2012, 'Cyberpunk 2077') === fps(582, 'Cyberpunk 2077') },
  { file: 'blog-four-builds.md', quote: 'The two competitive titles clear 165fps with room to spare',
    // The claim it replaced said "everything except Cyberpunk", which was wrong
    // on three of six rows. Pin the exact shape so it cannot regress.
    check: () => {
      const over = B(1719).rows.filter((r) => r.fps >= 165).map((r) => r.game);
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
  { file: 'instagram.md', quote: 'Your PC might be 46% slower than it should be',
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

console.log(`\n${failures === 0 ? 'PASS' : `${failures} FAILURE${failures > 1 ? 'S' : ''}`} — ${CLAIMS.length} claims, ${builds.length} builds, ${bottleneck.length} ladders`);
if (failures) console.log('Published copy disagrees with the data behind it. Fix the copy or re-render, then re-run.');
process.exit(failures ? 1 : 0);
