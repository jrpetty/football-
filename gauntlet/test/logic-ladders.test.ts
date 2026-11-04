/**
 * The logic ladders: reasoning.grid-ladder, reasoning.truth-ladder and reasoning.plans-ladder. Ten frozen levels
 * each (L01..L10), the same task and rules on every level, strictly growing size; keys that score 100 through the
 * real scoring path; independent re-proofs of the keys (JavaScript, parsed back from the prompt text); the expected
 * spread (reference / near-miss / careless / do-nothing / hedge) on every level; random filler ~0; the writers
 * reproduce the committed files byte for byte; and the answer-vs-truth visuals read every level.
 */
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { join } from 'node:path';
import { loadTests, renderCase, caseScorer, validateTest } from '../src/core/registry.ts';
import { scoreResponse } from '../src/scoring/index.ts';
import { createRng } from '../src/core/rng.ts';
import { explainerFor } from '../src/core/explainers.ts';
import { gridVisual } from '../src/presenter/visuals/grid.ts';
import { islandVisual } from '../src/presenter/visuals/truth.ts';
import { notesPlan, parsePlanPuzzle, planVisual } from '../src/presenter/visuals/planning.ts';
import type { CaseVisualInput } from '../src/presenter/visuals/common.ts';
import type { PromptTest } from '../src/core/types.ts';
// @ts-expect-error plain JavaScript checker (no types)
import { solveTruth } from '../verification/truth_ladder/verify.mjs';
// @ts-expect-error plain JavaScript checker (no types)
import { parseHanoi, shortest, FRAME_STEWART } from '../verification/plans_ladder/verify.mjs';

const IDS = ['reasoning.grid-ladder', 'reasoning.truth-ladder', 'reasoning.plans-ladder'];
const LEVELS = Array.from({ length: 10 }, (_, i) => `L${String(i + 1).padStart(2, '0')}`);
const all = loadTests();
const def = (id: string) => all.find((t) => t.definition.id === id)!.definition as PromptTest;
const noJudges = { ids: [], ask: async () => [] };
const art = (name: string) => ({ name, kind: 'text' as const, file: name, bytes: 0 });

async function score(d: PromptTest, caseId: string, response: string): Promise<number> {
  const c = d.cases.find((x) => x.id === caseId)!;
  const out = await scoreResponse({ scorer: caseScorer(d, c), expected: c.expected, response, stopReason: 'end', taskText: renderCase(d, c).turns.join('\n'), judges: noJudges, saveArtifact: art, signal: new AbortController().signal });
  return out.score ?? 0;
}
const key = (d: PromptTest, i: number) => {
  const e = d.cases[i]!.expected;
  return Array.isArray(e) ? String(e[0]) : String(e);
};
const num = (s: string, re: RegExp) => Number(re.exec(s)![1]!.replace(/,/g, ''));
const increasing = (xs: number[], what: string) => xs.forEach((x, i) => i && assert.ok(x > xs[i - 1]!, `${what}: level ${i + 1} (${x}) must exceed level ${i} (${xs[i - 1]})`));

test('three ladder tests, each a valid ten-level ladder L01..L10 with Horizon-style budgets and an explainer', () => {
  for (const id of IDS) {
    const t = all.find((x) => x.definition.id === id);
    assert.ok(t, `${id} exists`);
    const d = t.definition as PromptTest;
    assert.deepEqual(validateTest(d, all, { selfFile: t.file }), [], id);
    assert.equal(d.category, 'reasoning');
    assert.deepEqual(d.cases.map((c) => c.id), LEVELS, id);
    assert.equal(d.maxOutputTokens, 'model-max', id);
    assert.equal(d.timeLimitSec, 3600, id);
    assert.equal(d.maxRetries, 1, id);
    assert.ok(d.estimate && d.estimate.outputTokens >= 40000, `${id}: realistic output estimate`);
    assert.ok(explainerFor(id), `${id}: explainer`);
  }
  assert.deepEqual(def('reasoning.grid-ladder').scorer, { type: 'exact', normalize: 'alnum' });
  assert.deepEqual(def('reasoning.truth-ladder').scorer, { type: 'exact', normalize: 'alnum' });
  assert.deepEqual(def('reasoning.plans-ladder').scorer, { type: 'number', tolerance: 0 });
});

test('the writers reproduce the committed test files byte for byte', { timeout: 120_000 }, () => {
  const py = spawnSync('python3', ['--version']);
  if (py.status !== 0) return; // no Python on this machine: nothing to compare
  for (const dir of ['grid_ladder', 'truth_ladder', 'plans_ladder']) {
    const r = spawnSync('python3', ['write.py', '--check'], { cwd: join(import.meta.dirname, '..', 'verification', dir), encoding: 'utf8' });
    assert.equal(r.status, 0, `${dir}: ${r.stdout}${r.stderr}`);
    assert.match(r.stdout, /reproduced byte for byte/);
  }
});

// ─── growth: every level strictly harder along its documented parameter ────────────────────────────────────

test('Deduction Grid: Ladder grows in grid size and clue count, and the same rules apply on every level', () => {
  const d = def('reasoning.grid-ladder');
  const cells = d.cases.map((c) => num(c.notes!, /= (\d+) attribute values/));
  const clues = d.cases.map((c) => num(c.notes!, /attribute values, (\d+) clues/));
  increasing(cells, 'grid cells');
  increasing(clues, 'clues');
  assert.deepEqual([cells[0], cells[9]], [12, 96]);
  assert.ok(clues[9]! >= 150, `top level has ${clues[9]} clues`);
  for (const c of d.cases) {
    const n = num(c.prompt!, /numbered 1 to (\d+) from left to right/);
    const listed = c.prompt!.split('Clues:\n')[1]!.split('\n\n')[0]!.split('\n').length;
    assert.equal(listed, num(c.notes!, /attribute values, (\d+) clues/), `${c.id}: every clue is in the prompt`);
    assert.match(c.prompt!, new RegExp(`Exactly one arrangement satisfies all ${listed} clues, and every clue is needed`));
    assert.equal(key(d, d.cases.indexOf(c)).split('; ').length, 2);
    for (const half of key(d, d.cases.indexOf(c)).split('; ')) assert.equal(half.split(', ').length, n, `${c.id}: complete orderings`);
    // the same definitions on every level (only the place word and the size change)
    assert.match(c.prompt!, /"If P, then Q" is false only when P is true and Q is false/);
    assert.match(c.prompt!, /"Exactly one of these two statements is true: \(a\) \.\.\.; \(b\) \.\.\." means/);
  }
  // clue depth: compound clues appear from level 3 and never become fewer
  const compound = d.cases.map((c) => num(c.notes!, /clues \((\d+) compound\)/));
  assert.deepEqual(compound.slice(0, 2), [0, 0]);
  compound.forEach((x, i) => i > 2 && assert.ok(x >= compound[i - 1]!, `compound clues: level ${i + 1}`));
  assert.ok(compound[2]! > 0 && compound[9]! > 5 * compound[2]!);
});

test('Knights & Knaves: Ladder grows in islanders, statements and line-ups, under identical conventions', () => {
  const d = def('reasoning.truth-ladder');
  const islanders = d.cases.map((c) => c.prompt!.match(/You meet [\w-]+ inhabitants: (.+?)\. Each/)![1]!.split(/, | and /).length);
  const statements = d.cases.map((c) => num(c.notes!, /, (\d+) statements, nesting/));
  const lineups = d.cases.map((c) => num(c.notes!, /([\d,]+) type assignments obey the stated counts/));
  increasing(islanders, 'islanders');
  increasing(statements, 'statements');
  increasing(lineups, 'line-ups');
  assert.deepEqual([islanders[0], islanders[9]], [4, 24]);
  const conventions = (p: string) => p.split('Conventions:\n')[1]!.split('\n\nStatements:')[0]!.replace(/all [\w-]+ of these/, 'all N of these');
  for (const c of d.cases) assert.equal(conventions(c.prompt!), conventions(d.cases[0]!.prompt!), `${c.id}: same conventions`);
  // nesting: "would say" chains and statements about statements deepen
  const would = d.cases.map((c) => num(c.notes!, /"would say" (\d+)/));
  const meta = d.cases.map((c) => num(c.notes!, /statements about statements (\d+)/));
  assert.deepEqual([would[0], would[9], meta[0], meta[9]], [0, 3, 0, 3]);
  would.forEach((x, i) => i && assert.ok(x >= would[i - 1]!));
  meta.forEach((x, i) => i && assert.ok(x >= meta[i - 1]!));
});

test('Shortest Plans: Ladder grows in disks, positions and proven minimum', () => {
  const d = def('reasoning.plans-ladder');
  const disks = d.cases.map((c) => parseHanoi(c.prompt!).n as number);
  const positions = d.cases.map((c) => num(c.notes!, /= ([\d,]+) positions/));
  const minima = d.cases.map((c) => Number(c.expected));
  increasing(disks, 'disks');
  increasing(positions, 'positions');
  increasing(minima, 'minimum');
  assert.deepEqual([minima[0], minima[9], positions[9]], [6, 70, 4 ** 13]);
  for (const c of d.cases) assert.match(c.prompt!, /A move consists of taking the top disk from any peg and placing it on top of another peg\. A disk may never be placed on top of a smaller disk\./);
});

// ─── independent re-proofs (parsed back from the prompt text) ───────────────────────────────────────────────

test('truth ladder: an exhaustive JavaScript search finds exactly the key on levels 1-7', () => {
  const d = def('reasoning.truth-ladder');
  let prev = 0;
  for (const c of d.cases.slice(0, 7)) {
    const { solutions, nodes } = solveTruth(c.prompt!);
    assert.deepEqual(solutions, [key(d, d.cases.indexOf(c))], c.id);
    assert.ok(nodes > prev, `${c.id}: the search grows (${nodes} nodes)`);
    prev = nodes;
  }
});

test('plans ladder: a forward BFS re-proves levels 1-8 and every stored optimal plan replays to the goal in exactly the minimum', () => {
  for (let n = 1; n <= 8; n++) assert.equal(shortest(n, [Array.from({ length: n }, (_, i) => n - i), [], [], []], 3), FRAME_STEWART[n]);
  const d = def('reasoning.plans-ladder');
  d.cases.forEach((c, i) => {
    const z = parseHanoi(c.prompt!);
    if (i < 8) assert.equal(shortest(z.n, z.stacks, z.goal), c.expected, c.id);
    const steps = notesPlan(c.notes)!;
    assert.equal(steps.length, c.expected, `${c.id}: plan length`);
    const pegs: number[][] = z.stacks.map((s: number[]) => s.slice());
    for (const s of steps) {
      const [, disk, from, to] = s.match(/^disk (\d+) ([A-D]) to ([A-D])$/)!;
      const a = 'ABCD'.indexOf(from!);
      const b = 'ABCD'.indexOf(to!);
      assert.equal(pegs[a]!.at(-1), Number(disk), `${c.id}: ${s} moves a top disk`);
      assert.ok(!pegs[b]!.length || pegs[b]!.at(-1)! > Number(disk), `${c.id}: ${s} lands on a bigger disk`);
      pegs[b]!.push(pegs[a]!.pop()!);
    }
    assert.equal(pegs[3]!.length, z.n, `${c.id}: plan ends with every disk on D`);
    // the largest disk is not already home, so every disk matters
    assert.ok(!z.stacks[3].includes(z.n), c.id);
  });
});

// ─── scoring: the key scores 100, everything else 0, on every level ─────────────────────────────────────────

const swapTwo = (list: string, i: number, j: number) => {
  const p = list.split(', ');
  [p[i], p[j]] = [p[j]!, p[i]!];
  return p.join(', ');
};

async function spread(id: string) {
  const d = def(id);
  const rows: Array<{ level: string; reference: number; slip: number; careless: number; nothing: number; hedge: number }> = [];
  for (const [i, c] of d.cases.entries()) {
    const k = key(d, i);
    let slip: string;
    let careless: string;
    let hedge: string;
    if (id === 'reasoning.grid-ladder') {
      const [a, b] = k.split('; ');
      const n = a!.split(', ').length;
      slip = `${swapTwo(a!, n - 2, n - 1)}; ${b}`; // two neighbours swapped in the first list: one slip
      // careless: both lists in the order the legend prints them
      const legend = c.prompt!.split('Categories (with how the clues refer to them):\n')[1]!.split('\n\n')[0]!.split('\n');
      const values = legend.map((l) => l.match(/^- .+?: (.+?)\. Clue wording/)![1]!);
      const asked = c.prompt!.match(/Question: What are the (.+?) and the (.+?) of the people/)!.slice(1);
      const pick = (name: string) => values[legend.findIndex((l) => l.toLowerCase().startsWith(`- ${name}:`))]!;
      careless = `${pick(asked[0]!)}; ${pick(asked[1]!)}`;
      hedge = `${k} or ${slip}`;
    } else if (id === 'reasoning.truth-ladder') {
      const names = k.split(', ').map((p) => p.split(': ')[0]!);
      const types = k.split(', ').map((p) => p.split(': ')[1]!);
      const j = types.findIndex((t) => t === 'knight' || t === 'knave');
      slip = names.map((nm, x) => `${nm}: ${x === j ? (types[x] === 'knight' ? 'knave' : 'knight') : types[x]}`).join(', ');
      careless = names.map((nm) => `${nm}: knight`).join(', ');
      hedge = `${k}, or ${slip}`;
    } else {
      const z = parseHanoi(c.prompt!);
      slip = String(Number(k) + 1); // a valid plan one move too long
      careless = String(FRAME_STEWART[z.n]); // the textbook full-tower number
      hedge = `${k} or ${Number(k) + 1}`;
    }
    rows.push({
      level: c.id,
      reference: await score(d, c.id, `Working it through...\nFINAL ANSWER: ${k}`),
      slip: await score(d, c.id, `FINAL ANSWER: ${slip}`),
      careless: await score(d, c.id, `FINAL ANSWER: ${careless}`),
      nothing: await score(d, c.id, ''),
      hedge: await score(d, c.id, `FINAL ANSWER: ${hedge}`),
    });
  }
  return rows;
}

test('spread on every level: reference 100; one slip, careless, hedged and empty answers 0', async () => {
  for (const id of IDS) {
    for (const r of await spread(id)) {
      assert.equal(r.reference, 1, `${id}/${r.level}: reference`);
      assert.deepEqual([r.slip, r.careless, r.nothing, r.hedge], [0, 0, 0, 0], `${id}/${r.level}: ${JSON.stringify(r)}`);
    }
  }
});

test('format is not marked: case, spacing, labels and a trailing explanation are accepted', async () => {
  const g = def('reasoning.grid-ladder');
  const k = key(g, 4);
  assert.equal(await score(g, 'L05', `FINAL ANSWER: ${k.toUpperCase()}`), 1);
  assert.equal(await score(g, 'L05', `FINAL ANSWER: ${(g.cases[4]!.expected as string[])[1]}`), 1, 'with category labels');
  const t = def('reasoning.truth-ladder');
  assert.equal(await score(t, 'L03', `FINAL ANSWER: ${(t.cases[2]!.expected as string[])[1]}`), 1, 'bare list of types');
  const p = def('reasoning.plans-ladder');
  assert.equal(await score(p, 'L04', `I searched carefully.\nFINAL ANSWER: ${p.cases[3]!.expected}`), 1);
});

test('random guessing and filler score ~0 on every level', async () => {
  const words = 'the answer is probably around this value after careful thought knight knave'.split(' ');
  for (const id of IDS) {
    const d = def(id);
    let sum = 0;
    let n = 0;
    for (const c of d.cases) {
      for (let k = 0; k < 5; k++) {
        const rng = createRng(91 + k * 17 + n);
        const filler = Array.from({ length: 30 + rng.int(0, 60) }, () => rng.pick(words)).join(' ');
        const guesses = [`FINAL ANSWER: ${rng.int(0, 100)}`, `${filler}\nFINAL ANSWER: ${rng.int(0, 10 ** 6)}`, filler, '', `FINAL ANSWER: ${filler}`];
        sum += await score(d, c.id, guesses[k]!);
        n++;
      }
    }
    assert.ok(sum / n < 0.01, `${id}: filler averaged ${((sum / n) * 100).toFixed(2)}%`);
  }
});

// ─── the answer-vs-truth visuals read every level ───────────────────────────────────────────────────────────

function input(d: PromptTest, i: number, reply: string, passed: boolean | null): CaseVisualInput {
  const c = d.cases[i]!;
  return { testId: d.id, caseId: c.id, turns: [c.prompt!], replies: [reply], expected: c.expected, notes: c.notes, detail: {}, score: passed ? 1 : 0, passed, status: 'ok' };
}

test('visuals: the grid, islanders and plan views parse every level and agree with the key', () => {
  const g = def('reasoning.grid-ladder');
  g.cases.forEach((c, i) => {
    const v = gridVisual(input(g, i, `FINAL ANSWER: ${key(g, i)}`, true));
    assert.ok(v, `${c.id}: grid parses`);
    assert.equal(v.right, v.total, c.id);
  });
  const t = def('reasoning.truth-ladder');
  t.cases.forEach((c, i) => {
    const v = islandVisual(input(t, i, `FINAL ANSWER: ${key(t, i)}`, true));
    assert.ok(v, `${c.id}: islanders parse`);
    assert.equal(v.right, v.people.length, c.id);
    assert.ok(v.rule, `${c.id}: the stated counts are shown`);
  });
  const p = def('reasoning.plans-ladder');
  p.cases.forEach((c, i) => {
    assert.equal(parsePlanPuzzle(c.prompt!)?.kind, 'hanoi', c.id);
    const v = planVisual(input(p, i, `FINAL ANSWER: ${c.expected}`, true))!;
    assert.equal(v.optimum, c.expected, c.id);
    assert.equal(v.claimed, c.expected, c.id);
  });
});
