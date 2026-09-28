import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { ROOT } from '../src/core/paths.ts';
import { getTest, loadTests, validateTest } from '../src/core/registry.ts';
import { scoreResponse, type JudgePanel } from '../src/scoring/index.ts';
import { buildJudgePrompt, consensus, extractJamHtml, fidelityOf, jamTotal, parseJamVerdict, JAM_WEIGHTS } from '../src/scoring/game-jam.ts';
import { GAME_JAM_PROTOCOL, JAM_GENRES, checklistLine, gameJamOf, jamCaseSpec, numberedRequirements, validateGameJamCase } from '../src/scoring/game-jam-shared.ts';
import { GENRES, runPlaytest } from '../src/scoring/playtest.ts';
import { browserAvailable, closeBrowser } from '../src/scoring/browser.ts';
import { effectiveMaxOutputTokens } from '../src/engine/recorder.ts';
import { estimateRun } from '../src/engine/runner.ts';
import { explainerFor } from '../src/core/explainers.ts';
import type { PromptTest, ScorerSpec } from '../src/core/types.ts';

/**
 * The Game Jam (creative.game-jam): briefs, verdict parsing, weighting, truncation, caps, output limits and a
 * real (browser) playtest when Chromium is available. The full sample-game pipeline is
 * verification/game-jam/e2e.mts.
 */

const jam = getTest('creative.game-jam')!.definition as PromptTest;

test('the jam has five genre briefs, each with a matching requirement checklist', () => {
  assert.equal(jam.cases.length, 5);
  assert.deepEqual(jam.cases.map((c) => jamCaseSpec(c.expected).genre), JAM_GENRES.map((g) => g.id));
  for (const c of jam.cases) {
    const spec = jamCaseSpec(c.expected);
    assert.equal(numberedRequirements(c.prompt!), spec.requirements.length, `${c.id}: numbered requirements vs labels`);
    for (const must of ['surprise us', 'Creativity and originality is the single largest part', 'Definition of done', 'single ```html code block', 'Pressing Enter there must start', 'try/catch', 'under 500 kB', GENRES[spec.genre].inputs]) {
      assert.ok(c.prompt!.includes(must), `${c.id}: prompt mentions "${must.slice(0, 40)}"`);
    }
  }
  assert.equal(validateTest(jam).length, 0);
  assert.equal(jam.maxOutputTokens, 64000);
  assert.equal(jam.scorer.type === 'artifact' && jam.scorer.playtest?.protocol, GAME_JAM_PROTOCOL);
});

test('the zombie round asks for balanced base building, checked by its own requirement', () => {
  const z = jam.cases.find((c) => jamCaseSpec(c.expected).genre === 'zombie')!;
  const labels = jamCaseSpec(z.expected).requirements;
  assert.ok(labels.some((l) => /base building/i.test(l)));
  assert.ok(labels.some((l) => /balanced, not a win button/i.test(l)));
  for (const must of ['reinforced door', 'spike trap', 'watchtower', 'workbench', 'rain collector', 'generator', 'green where valid and red', 'build limit or an upkeep cost', 'can and do breach', 'at least five buildable structure types', 'readable materials HUD']) assert.ok(z.prompt!.includes(must), must);
  assert.ok((jam.scorer as { rubric: string }).rubric.includes('not a win button'));
});

test('validation catches a stale protocol and a checklist that does not match the prompt', () => {
  const scorer = { ...(jam.scorer as Extract<ScorerSpec, { type: 'artifact' }>), playtest: { protocol: GAME_JAM_PROTOCOL + 1 } };
  assert.ok(validateGameJamCase(scorer, jam.cases[0]!.expected, jam.cases[0]!.prompt, 'case').some((e) => /protocol/.test(e)));
  const short = { genre: 'flappy', requirements: ['only one'] };
  assert.ok(validateGameJamCase(jam.scorer, short, jam.cases[0]!.prompt, 'case').some((e) => /numbered requirements/.test(e)));
  assert.ok(validateGameJamCase(jam.scorer, { genre: 'golf', requirements: ['x'] }, undefined, 'case').some((e) => /genre/.test(e)));
});

const GOOD = (n: number) => [...Array.from({ length: n }, (_, i) => `R${i + 1}: ${i % 4 === 3 ? 'PARTIAL' : 'PASS'} — fine`), 'PLAYS: 8', 'FEEL: 7', 'CREATIVITY: 9', 'POLISH: 6', 'AMBITION: 5', 'VERDICT: A lovely moth game.'].join('\n');

test('judge verdicts parse leniently (markdown, restated lines) and reject incomplete ones', () => {
  const v = parseJamVerdict(`Draft...\nCREATIVITY: 3\n**R1:** PASS — ok\n${GOOD(4)}`, 4);
  assert.ok(typeof v !== 'string');
  assert.equal(v.criteria.creativity, 0.9, 'the last occurrence wins');
  assert.equal(v.requirements[3]!.verdict, 'partial');
  assert.equal(v.verdict, 'A lovely moth game.');
  assert.match(parseJamVerdict('R1: PASS\nPLAYS: 5', 1) as string, /missing/);
  assert.match(parseJamVerdict(GOOD(2), 10) as string, /graded only 2 of 10/);
  assert.match(parseJamVerdict(GOOD(1).replace('FEEL: 7', 'FEEL: 14'), 1) as string, /FEEL/);
  assert.match(parseJamVerdict('I refuse to grade this.', 3) as string, /missing/);
});

test('weights: creativity counts most, and totals and consensus are computed from the checklist', () => {
  const max = Math.max(...Object.values(JAM_WEIGHTS));
  assert.equal(JAM_WEIGHTS.creativity, max);
  assert.ok(Math.abs(Object.values(JAM_WEIGHTS).reduce((a, b) => a + b, 0) - 1) < 1e-9);
  const reqs = [{ verdict: 'pass' as const }, { verdict: 'partial' as const }, null, { verdict: 'fail' as const }];
  assert.equal(fidelityOf(reqs), 0.5);
  assert.equal(jamTotal({ fidelity: 1, plays: 1, feel: 1, creativity: 1, polish: 1, ambition: 1 }), 1);
  const parsed = (vs: string[]) => ({ requirements: vs.map((v) => ({ verdict: v as 'pass' })), criteria: { plays: 1, feel: 1, creativity: 1, polish: 1, ambition: 1 } });
  const cons = consensus(['a', 'b'], [{ judgeId: 'j1', parsed: parsed(['pass', 'fail']) }, { judgeId: 'j2', parsed: parsed(['pass', 'pass']) }]);
  assert.equal(cons[0]!.verdict, 'pass');
  assert.equal(cons[0]!.split, false);
  assert.equal(cons[1]!.verdict, 'partial');
  assert.equal(cons[1]!.split, true);
  assert.equal(checklistLine(cons), '1 of 2 requirements met, 1 partly');
});

test('extraction: a finished reply, a reply cut off mid-file, and no code at all', () => {
  assert.ok(extractJamHtml('```html\n<!doctype html><canvas></canvas><script>1</script>\n```', false));
  const cut = 'Here you go\n```html\n<!doctype html><html><body><canvas></canvas><script>let a = [1, 2';
  assert.match(extractJamHtml(cut, true)!, /^<!doctype html>/);
  assert.equal(extractJamHtml('Sorry, I cannot help with that.', true), null);
});

function panel(text: string | null, calls: string[] = []): JudgePanel {
  return {
    ids: ['a@judge', 'b@judge'],
    async ask(_s, user, _l, opts) {
      calls.push(user);
      if (text === null) return [{ judgeId: 'a@judge', text: '', error: 'HTTP 500' }];
      return [
        { judgeId: 'a@judge', text, sawImages: Boolean(opts?.images?.length) },
        { judgeId: 'b@judge', text: text.replace('CREATIVITY: 9', 'CREATIVITY: 2').replace(/PASS/g, 'FAIL'), sawImages: false },
      ];
    },
  };
}
const noBrowser = () => {
  const prev = process.env.GAUNTLET_NO_BROWSER;
  process.env.GAUNTLET_NO_BROWSER = '1';
  return () => (prev === undefined ? delete process.env.GAUNTLET_NO_BROWSER : (process.env.GAUNTLET_NO_BROWSER = prev));
};
const flappy = jam.cases[0]!;
const input = (response: string, stopReason: 'end' | 'max_tokens', judges: JudgePanel) => ({
  scorer: jam.scorer,
  expected: flappy.expected,
  response,
  stopReason,
  taskText: flappy.prompt!,
  judges,
  saveArtifact: (name: string, kind: 'html' | 'png' | 'svg' | 'text' | 'json') => ({ name, kind, file: name, bytes: 1 }),
  signal: new AbortController().signal,
});

test('scoring without a browser: checks fall back to static ones, judges combine, disagreement is flagged', async () => {
  const restore = noBrowser();
  try {
    const n = jamCaseSpec(flappy.expected).requirements.length;
    const calls: string[] = [];
    const out = await scoreResponse(input('```html\n<!doctype html><body><canvas></canvas><script>requestAnimationFrame(()=>{})</script></body>\n```', 'end', panel(GOOD(n), calls)));
    const gj = gameJamOf(out.detail)!;
    assert.equal(gj.genre, 'flappy');
    assert.equal(gj.judges.length, 2);
    assert.ok(gj.spread! > 0.3 && out.detail.judgeDisagreement, 'a 9 vs 2 creativity split and pass vs fail checklist is flagged');
    assert.equal(gj.requirements[0]!.split, true);
    assert.ok(out.score! > 0 && out.score! <= 1);
    assert.match(out.summary, /creativity/);
    assert.ok(calls[0]!.includes('No headless browser was available'));
    assert.deepEqual(out.detail.skippedChecks, ['runs_without_errors', 'has_canvas_or_svg', 'responds_to_input', 'playtest']);
  } finally {
    restore();
  }
});

test('a reply cut off by the output limit fails gracefully: clear note, no judges, automatic share only', async () => {
  const restore = noBrowser();
  try {
    const calls: string[] = [];
    const out = await scoreResponse(input('```html\n<!doctype html><body><canvas></canvas><script>const huge = [1,2,', 'max_tokens', panel(GOOD(14), calls)));
    assert.match(out.summary, /Ran out of output space/);
    assert.equal(calls.length, 0);
    assert.equal(out.passed, false);
    assert.ok(out.score! <= 0.25);
    assert.ok(gameJamOf(out.detail)!.truncated);
    const none = await scoreResponse(input('I will now write the game. ```html\n', 'max_tokens', panel(GOOD(14))));
    assert.equal(none.score, 0);
    assert.match(none.summary, /no game code arrived/);
  } finally {
    restore();
  }
});

test('all judges failing is an error (like every judged test); a missing game is a plain 0', async () => {
  const restore = noBrowser();
  try {
    await assert.rejects(scoreResponse(input('```html\n<!doctype html><body><canvas></canvas><script></script></body>\n```', 'end', panel(null))), /All judges failed/);
    const out = await scoreResponse(input('Here is my plan for the game, in words.', 'end', panel(GOOD(14))));
    assert.equal(out.score, 0);
    assert.match(out.summary, /No HTML game/);
  } finally {
    restore();
  }
});

test('the judge prompt tells text-only judges they get no pictures, and quotes the playtest inputs', () => {
  const spec = jamCaseSpec(flappy.expected);
  const args = { task: 'brief', rubric: 'RUBRIC', spec, artifact: '<canvas>', playtest: null, items: [{ label: 'HTML document parses', passed: true }] };
  const p = buildJudgePrompt({ ...args, withImages: false });
  assert.ok(p.includes('R14. ') && p.includes('RUBRIC') && p.includes('PASS: HTML document parses'));
  assert.ok(p.includes(`R${spec.requirements.length}: PASS | PARTIAL | FAIL`));
});

test('output limits: a model\'s own maximum lowers the request, and cost estimates respect it', async () => {
  assert.equal(effectiveMaxOutputTokens({ maxOutputTokens: 16384 }, 64000), 16384);
  assert.equal(effectiveMaxOutputTokens({ options: { maxOutputTokensCap: 32000 }, maxOutputTokens: 100000 }, 64000), 32000);
  assert.equal(effectiveMaxOutputTokens({}, 64000), 64000);
  const est = await estimateRun({ testIds: ['creative.game-jam'], contestantIds: ['gpt-4o', 'claude-opus-5-5'], repeats: 1 });
  const cost = (id: string) => est.perContestant.find((p) => p.contestantId === id)!.estCostUsd;
  // gpt-4o can write at most 16,384 tokens per reply, so its estimate is capped there.
  assert.ok(cost('gpt-4o') < 5 * (3000 * 2.5 + 16384 * 10) / 1e6 + 0.01);
  assert.ok(cost('claude-opus-5-5') > 3, 'a 64k-token game jam costs dollars, not cents, for a flagship model');
  assert.ok(est.judgeCostUsd > 0);
});

test('the jam has an explainer and sits in the games suite', () => {
  assert.ok(explainerFor('creative.game-jam'));
  const games = JSON.parse(readFileSync(join(ROOT, 'suites', 'games.json'), 'utf8')) as { tests: Array<{ id: string }> };
  assert.ok(games.tests.some((t) => t.id === 'creative.game-jam'));
  assert.ok(loadTests().some((t) => t.definition.id === 'creative.one-shot-games'), 'the one-shot games test is untouched and still loads');
});

test('real playtest: repeatable, and it tells a moving game from a frozen one', { timeout: 120_000 }, async (t) => {
  if (!(await browserAvailable())) return t.skip('no headless Chromium on this machine');
  const game = `<!doctype html><body style="margin:0"><canvas id=c width=1280 height=720></canvas><script>
    const x=c.getContext('2d');let px=100,started=false;addEventListener('keydown',e=>{if(e.code==='Enter')started=true;if(e.code==='Space')px+=30});
    function f(t){x.fillStyle='#124';x.fillRect(0,0,1280,720);x.fillStyle='#fc0';x.fillRect((px+(started?t/10:0))%1280,300+Math.sin(t/300)*80,60,60);x.fillStyle='#fff';x.font='40px sans-serif';x.fillText(started?'PLAY':'TITLE',40,60);requestAnimationFrame(f)}requestAnimationFrame(f)</script>`;
  try {
    const a = await runPlaytest(game, 'flappy');
    const b = await runPlaytest(game, 'flappy');
    assert.ok(a && b);
    assert.deepEqual(a.frames.map((f) => f.png.equals(b.frames.find((g) => g.t === f.t)!.png)), [true, true, true, true, true], 'same file → same screenshots (fixed clock and seed)');
    for (const k of ['drawsPicture', 'keepsMoving', 'reacts', 'stillRunning', 'noPlayErrors'] as const) assert.ok(a.summary[k].passed, k);
    const frozen = await runPlaytest(game.replace("if(e.code==='Enter')started=true;", "if(e.code==='Enter'){started=true;while(true){}}"), 'flappy');
    assert.ok(frozen!.summary.hung);
    assert.equal(frozen!.summary.stillRunning.passed, false);
  } finally {
    await closeBrowser();
  }
});
