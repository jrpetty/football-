/**
 * The Horizon tier: the ladder scorer (big integers, replayed sliding plans, whole grids), the frozen
 * ladders themselves (ten levels, keys that score 100%, strictly growing work), the random floor, and
 * the ladder maths behind the "how far up the ladder" visual.
 */
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { loadTests, renderCase, caseScorer, validateTest } from '../src/core/registry.ts';
import { scoreResponse } from '../src/scoring/index.ts';
import { parseBigInteger, parseGrid, replayPlan, scoreLadder, SUBOPTIMAL_PLAN_CREDIT, type PlanKey } from '../src/scoring/ladder.ts';
import { horizonVisual, ladderClimbs, ladderLevel, rungCaption, rungHeadline } from '../src/presenter/visuals/horizon.ts';
import { createRng } from '../src/core/rng.ts';
import type { PromptTest, ScorerSpec } from '../src/core/types.ts';

const HORIZON = loadTests().filter((t) => t.definition.id.startsWith('horizon.'));
const defs = HORIZON.map((t) => t.definition as PromptTest);
const byId = (id: string) => defs.find((d) => d.id === id)!;

const noJudges = { ids: [], ask: async () => [] };
const art = (name: string) => ({ name, kind: 'text' as const, file: name, bytes: 0 });
async function score(scorer: ScorerSpec, expected: unknown, response: string) {
  return scoreResponse({ scorer, expected, response, stopReason: 'end', taskText: '', judges: noJudges, saveArtifact: art, signal: new AbortController().signal });
}

// ─── the scorer ─────────────────────────────────────────────────────────────────────────────────────

test('integer rungs compare big numbers exactly, digit by digit', () => {
  const key = '98608358886145059183622';
  assert.equal(scoreLadder('integer', key, `work...\nFINAL ANSWER: ${key}`).score, 1);
  assert.equal(scoreLadder('integer', key, 'FINAL ANSWER: 98,608,358,886,145,059,183,622').score, 1);
  assert.equal(scoreLadder('integer', key, 'FINAL ANSWER: 98 608 358 886 145 059 183 622').score, 1);
  assert.equal(scoreLadder('integer', key, `FINAL ANSWER: **${key}**.`).score, 1);
  assert.equal(scoreLadder('integer', key, `FINAL ANSWER: x = ${key}`).score, 1);
  // one digit off: 0 (a float comparison would call this equal)
  assert.equal(scoreLadder('integer', key, 'FINAL ANSWER: 98608358886145059183623').score, 0);
  assert.equal(scoreLadder('integer', key, 'FINAL ANSWER: 9.86e22').score, 0);
  assert.equal(scoreLadder('integer', '42', 'FINAL ANSWER: 42 or 43').score, 0);
  assert.equal(scoreLadder('integer', '42', 'FINAL ANSWER: -42').score, 0);
  assert.equal(scoreLadder('integer', '42', 'I cannot compute this without a calculator.').score, 0);
  assert.equal(parseBigInteger('0042'), '42');
  assert.equal(parseBigInteger('12 and 13'), null);
});

const PUZZLE: PlanKey = { rows: 2, cols: 3, start: [1, 2, 3, 4, 0, 5], optimal: 1, plan: [5] };

test('plan rungs are replayed: minimum = 1, longer but valid ≤ 0.25, illegal or unfinished = 0', () => {
  assert.equal(scoreLadder('plan', PUZZLE, 'FINAL ANSWER: 5').score, 1);
  // 5 moves that also solve it (5 out, back, then around): 1 → 5 → ... build one by replaying
  const long = [5, 5, 5];
  assert.equal(replayPlan(PUZZLE, long).solved, true);
  const s = scoreLadder('plan', PUZZLE, `FINAL ANSWER: ${long.join(' ')}`);
  assert.equal(s.score, Math.round(SUBOPTIMAL_PLAN_CREDIT * (1 / 3) * 10000) / 10000);
  assert.ok(s.score > 0 && s.score <= SUBOPTIMAL_PLAN_CREDIT);
  assert.equal(scoreLadder('plan', PUZZLE, 'FINAL ANSWER: 1').score, 0, 'tile 1 is not next to the gap');
  assert.equal(scoreLadder('plan', PUZZLE, 'FINAL ANSWER: 2 5').score, 0, 'legal but does not end at the goal');
  assert.equal(scoreLadder('plan', PUZZLE, 'FINAL ANSWER: 5, 5').score, 0);
  assert.equal(scoreLadder('plan', PUZZLE, 'no idea').score, 0);
  assert.equal(scoreLadder('plan', PUZZLE, 'FINAL ANSWER: [5]').score, 1, 'decoration around the numbers is ignored');
});

test('grid rungs need the whole grid, on lines or separated by slashes', () => {
  const key = ['#.#', '###', '..#'];
  assert.equal(scoreLadder('grid', key, 'Reasoning...\nFINAL ANSWER:\n#.#\n###\n..#').score, 1);
  assert.equal(scoreLadder('grid', key, 'FINAL ANSWER: #.#/###/..#').score, 1);
  assert.equal(scoreLadder('grid', key, 'FINAL ANSWER:\n```\n# . #\n# # #\n. . #\n```').score, 1);
  assert.equal(scoreLadder('grid', key, 'FINAL ANSWER:\nX0X\nXXX\n00X').score, 1);
  const wrong = scoreLadder('grid', key, 'FINAL ANSWER:\n#.#\n###\n.##');
  assert.equal(wrong.score, 0);
  assert.equal(wrong.detail.wrongCells, 1);
  assert.equal(scoreLadder('grid', key, 'FINAL ANSWER:\n#.#\n###').score, 0, 'missing row');
  assert.equal(parseGrid('the grid is\n#.#\n###\n..#', 3, 3), null, 'no FINAL ANSWER marker, no grid');
  // an earlier draft grid does not count: only the grid after the last marker
  assert.equal(scoreLadder('grid', key, 'FINAL ANSWER:\n#.#\n###\n..#\nWait, that is wrong.\nFINAL ANSWER:\n###\n###\n###').score, 0);
});

// ─── the frozen ladders ─────────────────────────────────────────────────────────────────────────────

test('there are five Horizon tests, each a valid ten-level ladder L01..L10', () => {
  assert.equal(defs.length, 5);
  for (const t of HORIZON) {
    const d = t.definition as PromptTest;
    assert.deepEqual(validateTest(d, loadTests(), { selfFile: t.file }), [], d.id);
    assert.equal(d.category, 'horizon');
    assert.deepEqual(d.cases.map((c) => c.id), ['L01', 'L02', 'L03', 'L04', 'L05', 'L06', 'L07', 'L08', 'L09', 'L10'], d.id);
    assert.equal(d.scorer.type, 'ladder');
    assert.ok((d.maxOutputTokens ?? 0) >= 64000, `${d.id}: generous output budget`);
  }
});

test('every Horizon answer key scores 100% when stated in the required format', async () => {
  for (const d of defs) {
    for (const c of d.cases) {
      const sc = caseScorer(d, c) as Extract<ScorerSpec, { type: 'ladder' }>;
      const reply =
        sc.answer === 'integer'
          ? `FINAL ANSWER: ${c.expected}`
          : sc.answer === 'plan'
            ? `FINAL ANSWER: ${(c.expected as PlanKey).plan!.join(' ')}`
            : `FINAL ANSWER:\n${(c.expected as string[]).join('\n')}`;
      const out = await score(sc, c.expected, reply);
      assert.equal(out.score, 1, `${d.id}/${c.id}: ${out.summary}`);
    }
  }
});

test('the stored optimal plans really are as long as the proven minimum and solve the start board', () => {
  for (const c of byId('horizon.sliding-ladder').cases) {
    const k = c.expected as PlanKey;
    assert.equal(k.plan!.length, k.optimal, c.id);
    assert.equal(replayPlan(k, k.plan!).solved, true, c.id);
  }
});

test('each level is strictly more work than the one below it', () => {
  const num = (s: string, re: RegExp) => Number(re.exec(s)?.[1]?.replace(/,/g, ''));
  const series = (id: string, f: (c: PromptTest['cases'][number]) => number) => byId(id).cases.map(f);
  const increasing = (xs: number[], id: string) => xs.forEach((x, i) => i && assert.ok(x > xs[i - 1]!, `${id}: level ${i + 1} (${x}) must exceed level ${i} (${xs[i - 1]})`));
  increasing(series('horizon.mind-runner', (c) => num(c.notes!, /(\d+) statements executed/)), 'mind-runner');
  increasing(series('horizon.modpow-ladder', (c) => { const m = /^m = (\d+)$/m.exec(c.prompt!)![1]!; const e = /^e = (\d+)$/m.exec(c.prompt!)![1]!; return BigInt(e).toString(2).length * m.length ** 2; }), 'modpow');
  increasing(series('horizon.sliding-ladder', (c) => (c.expected as PlanKey).optimal), 'sliding');
  increasing(series('horizon.nonogram-ladder', (c) => (c.expected as string[]).length ** 2), 'nonogram');
  increasing(series('horizon.tiling-count', (c) => num(c.prompt!, /The (\d+) marked squares|the (\d+) marked squares/i) || num(c.prompt!, /covered completely with (\d+) dominoes/)), 'tilings');
});

test('prompts end with the answer instruction the scorer reads', () => {
  for (const d of defs) {
    for (const c of d.cases) {
      const last = renderCase(d, c).turns.at(-1)!;
      if ((d.scorer as { answer: string }).answer === 'grid') assert.match(last, /FINAL ANSWER:\n#\.#\n###\n\.\.#$/, `${d.id}/${c.id}`);
      else assert.match(last, /FINAL ANSWER: <answer>$/, `${d.id}/${c.id}`);
    }
  }
});

test('random guessing and filler score ~0 on every ladder', async () => {
  const words = 'the answer is probably around this value after careful thought'.split(' ');
  for (const d of defs) {
    let sum = 0;
    let n = 0;
    for (const c of d.cases) {
      for (let k = 0; k < 6; k++) {
        const rng = createRng(77 + k * 13 + n);
        const filler = Array.from({ length: 30 + rng.int(0, 60) }, () => rng.pick(words)).join(' ');
        const guesses = [
          `FINAL ANSWER: ${rng.int(0, 100)}`, // what the Random Baseline sends
          `${filler}\nFINAL ANSWER: ${rng.int(0, 10 ** 9)}`,
          `FINAL ANSWER: ${Array.from({ length: 30 }, () => rng.int(1, 15)).join(' ')}`,
          `FINAL ANSWER:\n${Array.from({ length: 40 }, () => Array.from({ length: 40 }, () => (rng.next() < 0.5 ? '#' : '.')).join('')).join('\n')}`,
          filler,
          '',
        ];
        const out = await score(caseScorer(d, c), c.expected, guesses[k]!);
        sum += out.score ?? 0;
        n++;
      }
    }
    assert.ok(sum / n < 0.01, `${d.id}: filler averaged ${((sum / n) * 100).toFixed(2)}%`);
  }
});

// ─── the ladder visual ──────────────────────────────────────────────────────────────────────────────

test('ladder height = every level up to it solved reliably (2 of 3 attempts at full marks); a fluke higher up is "best"', () => {
  const r = (contestantId: string, level: number, ...scores: number[]) => scores.map((score) => ({ contestantId, testId: 'horizon.x', caseId: `L${String(level).padStart(2, '0')}`, score, status: 'ok' }));
  const results = [
    ...r('a', 1, 1, 1, 1), ...r('a', 2, 1, 1, 0), ...r('a', 3, 1, 0, 0), ...r('a', 4, 0, 0, 0),
    ...r('b', 1, 0.2), ...r('b', 2, 0),
    { contestantId: 'a', testId: 'other.test', caseId: 'L09', score: 1, status: 'ok' },
    { contestantId: 'a', testId: 'horizon.x', caseId: 'L09', score: null, status: 'error' },
  ];
  const [a, b] = ladderClimbs(results, 'horizon.x');
  assert.equal(a!.height, 2);
  assert.equal(a!.best, 3);
  assert.equal(a!.rungs[8]!.attempts, 0, 'errors are not attempts');
  assert.equal(Math.round(a!.score! * 1000), Math.round(((1 + 2 / 3 + 1 / 3 + 0) / 4) * 1000));
  assert.equal(b!.height, 0);
  const [gap] = ladderClimbs([...r('g', 1, 1), ...r('g', 2, 0), ...r('g', 3, 1)], 'horizon.x');
  assert.equal(gap!.height, 1, 'a fluke on level 3 does not lift the climber past a failed level 2');
  assert.equal(gap!.best, 3);
  assert.equal(b!.rungs[0]!.reliable, false, 'partial credit is not "solved"');
  assert.equal(ladderLevel('L10'), 10);
  assert.equal(ladderLevel('c01'), null);
});

test('rung visuals read every real case and agree with the scorer', () => {
  for (const d of defs) {
    for (const c of d.cases) {
      const sc = caseScorer(d, c) as Extract<ScorerSpec, { type: 'ladder' }>;
      const right = sc.answer === 'integer' ? `FINAL ANSWER: ${c.expected}` : sc.answer === 'plan' ? `FINAL ANSWER: ${(c.expected as PlanKey).plan!.join(' ')}` : `FINAL ANSWER:\n${(c.expected as string[]).join('\n')}`;
      const base = { testId: d.id, caseId: c.id, turns: [c.prompt!], expected: c.expected, notes: c.notes, detail: {}, score: 1, passed: true, status: 'ok' };
      const m = horizonVisual({ ...base, replies: [right] });
      assert.ok(m, `${d.id}/${c.id}`);
      assert.ok(m!.verdict === 'correct' || m!.verdict === 'optimal', `${d.id}/${c.id}: ${m!.verdict}`);
      assert.ok(rungCaption(d.id, c.prompt!, c.notes), `${d.id}/${c.id}: caption`);
      const none = horizonVisual({ ...base, replies: ['I give up.'], score: 0, passed: false });
      assert.equal(none!.verdict, 'no-answer');
    }
  }
  const mod = byId('horizon.modpow-ladder').cases[0]!;
  const key = String(mod.expected);
  const off = key.slice(0, 3) + String((Number(key[3]) + 1) % 10) + key.slice(4);
  const m = horizonVisual({ testId: 'horizon.modpow-ladder', caseId: 'L01', turns: [mod.prompt!], replies: [`FINAL ANSWER: ${off}`], expected: key, detail: {}, score: 0, passed: false, status: 'ok' })!;
  assert.equal(m.verdict, 'wrong');
  assert.equal((m.compare as { firstDiff: number }).firstDiff, 3);
  assert.match(rungHeadline(m), /1 of 8 digits wrong/);
  const short = horizonVisual({ testId: 'horizon.modpow-ladder', caseId: 'L01', turns: [mod.prompt!], replies: [`FINAL ANSWER: ${key.slice(0, 5)}`], expected: key, detail: {}, score: 0, passed: false, status: 'ok' })!;
  assert.match(rungHeadline(short), /right for 5 digits, then wrong/);
  assert.equal(horizonVisual({ testId: 'math.olympiad', caseId: 'o01', turns: [''], replies: [''], detail: {}, score: 0, passed: false, status: 'ok' }), null);
});
