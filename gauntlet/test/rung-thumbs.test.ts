// Rung thumbnails (ui/src/components/viz/rung-thumbs): the per-level numbers they draw must be the tests' own.
// 1. data.ts is exactly what scripts/rung-thumbs-data.ts builds from the test files and verification data (no drift).
// 2. Every built ladder in config/ladders.json has ten levels of data.
// 3. Every number a rung label states (config/ladders.json) equals the drawn data for that level.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { buildRungThumbData, renderDataModule, DATA_FILE } from '../scripts/rung-thumbs-data.ts';
import { RUNG_THUMB_DATA } from '../ui/src/components/viz/rung-thumbs/data.ts';
import type { RungThumbDataMap, RungThumbTestId } from '../ui/src/components/viz/rung-thumbs/types.ts';

const ROOT = join(import.meta.dirname, '..');
const ladders = JSON.parse(readFileSync(join(ROOT, 'config', 'ladders.json'), 'utf8')) as {
  tests: Array<{ id: string; planned?: boolean; rungs?: string[] }>;
};

const n = (s: string | undefined) => Number(String(s).replace(/,/g, ''));
/** The number captured by `re` in `label`, or undefined when the label does not state it. */
const grab = (label: string, re: RegExp) => {
  const m = re.exec(label);
  return m ? n(m[1]) : undefined;
};

test('rung thumbnails: data.ts is regenerated from the test files without drift', () => {
  const built = buildRungThumbData();
  assert.deepEqual(RUNG_THUMB_DATA, built);
  assert.equal(readFileSync(DATA_FILE, 'utf8'), renderDataModule(built), 'run: node scripts/rung-thumbs-data.ts');
});

test('rung thumbnails: every built ladder has ten levels of data', () => {
  const built = ladders.tests.filter((t) => !t.planned && t.rungs?.length);
  assert.equal(built.length, 18);
  for (const t of built) {
    const levels = (RUNG_THUMB_DATA as unknown as Record<string, unknown[] | undefined>)[t.id];
    assert.ok(levels, `${t.id} has no thumbnail data`);
    assert.equal(levels.length, t.rungs!.length, t.id);
  }
});

type Check<K extends RungThumbTestId> = (label: string, d: RungThumbDataMap[K][number], level: number) => Array<[unknown, unknown, string]>;

/** For each ladder: [stated in the label, drawn value, what] pairs. A value the label does not state is undefined and skipped. */
const CHECKS: { [K in RungThumbTestId]: Check<K> } = {
  'horizon.mind-runner': (l, d) => [[grab(l, /^([\d,]+) steps$/), d.steps, 'steps']],
  'horizon.modpow-ladder': (l, d) => [[grab(l, /^(\d+)-digit numbers$/), d.mDigits, 'digits']],
  'horizon.sliding-ladder': (l, d) => [
    [grab(l, /^(\d+)×/), d.rows, 'rows'],
    [grab(l, /×(\d+) ·/), d.cols, 'cols'],
    [grab(l, /· (\d+) moves$/), d.moves, 'moves'],
  ],
  'horizon.nonogram-ladder': (l, d) => [
    [grab(l, /^(\d+)×/), d.size, 'rows'],
    [grab(l, /×(\d+) grid$/), d.size, 'cols'],
  ],
  'horizon.tiling-count': (l, d) => [
    [grab(l, /^(\d+)×/), d.rows, 'rows'],
    [grab(l, /×(\d+) board$/), d.cols, 'cols'],
  ],
  'reasoning.grid-ladder': (l, d) => [
    [grab(l, /^(\d+)×/), d.positions, 'positions'],
    [grab(l, /×(\d+) grid/), d.categories, 'categories'],
    [grab(l, /(\d+) clues$/), d.clues, 'clues'],
  ],
  'reasoning.truth-ladder': (l, d) => [
    [grab(l, /^(\d+) islanders/), d.islanders, 'islanders'],
    [grab(l, /(\d+) statements$/), d.statements, 'statements'],
  ],
  'reasoning.plans-ladder': (l, d) => [
    [grab(l, /^(\d+) disks/), d.disks, 'disks'],
    [grab(l, /(\d+) moves$/), d.moves, 'moves'],
    [d.pegs.flat().length, d.disks, 'disks drawn on the pegs'],
  ],
  'math.exact-ladder': (l, d) => [
    [grab(l, /^(\d+) squares/), d.squares, 'squares'],
    [grab(l, /(\d+) snakes?/) ?? 0, d.snakes.length, 'snakes'],
    [grab(l, /(\d+)-digit/), d.denominatorDigits, 'answer digits'],
  ],
  'coding.engineering-ladder': (l, d, level) => [
    [grab(l, /n ([\d,]+)$/), d.n, 'n'],
    [/n 10\^6/.test(l) ? 1_000_000 : undefined, d.n, 'n'],
    // "set + range sum" = 2 operations, "+min/max" = 2, "+range add" = 1.
    [level === 1 ? l.split(' · ')[0]!.split(' + ').length : l.replace(/ · .*/, '').split('/').length, d.added.length, 'operations added'],
  ],
  'work.invoices-ladder': (l, d) => [
    [grab(l, /^(\d+) documents/), d.docs, 'documents'],
    [grab(l, /(\d+) pools? of/), d.pools, 'pools'],
    [grab(l, /pools? of (\d+)/), d.poolSize, 'pool size'],
    [grab(l, /(\d+) rules/), d.families, 'rule families'],
  ],
  'work.payroll-ladder': (l, d) => [
    [grab(l, /^(\d+) months?/), d.months, 'months'],
    [grab(l, /(\d+) staff/), d.staff, 'staff'],
  ],
  'work.expenses-ladder': (l, d) => [
    [grab(l, /^(\d+) months?/), d.linesPerMonth.length, 'months'],
    [grab(l, /(\d+) people/), d.people, 'people'],
  ],
  'work.reconciliation-ladder': (l, d) => [
    [grab(l, /^(\d+) months?/), d.bankPerMonth.length, 'months'],
    [d.booksPerMonth.length, d.bankPerMonth.length, 'cash-book months'],
  ],
  'work.crm-ladder': (l, d) => [
    [grab(l, /^(\d+) records/), d.records, 'records'],
    [grab(l, /(\d+) refused merges/), d.refusedMerges, 'refused merges'],
  ],
  'work.contract-ladder': (l, d) => [
    [grab(l, /^(\d+) changes/), d.changes, 'changes'],
    [grab(l, /(\d+) replies/), d.replies, 'replies'],
  ],
  'work.sql-ladder': (l, d) => [[grab(l, /^(\d+) rules?/), d.rules, 'rules']],
  'work.support-ladder': (l, d) => [
    [grab(l, /^(\d+) tickets/), d.tickets, 'tickets'],
    [grab(l, /(\d+) accounts/), d.accounts, 'accounts'],
    [grab(l, /(\d+) rules/), d.families, 'rule families'],
  ],
};

test('rung thumbnails: every number in a rung label matches the drawn data', () => {
  for (const id of Object.keys(CHECKS) as RungThumbTestId[]) {
    const rungs = ladders.tests.find((t) => t.id === id)?.rungs;
    assert.ok(rungs, `${id} is not in config/ladders.json`);
    let compared = 0;
    rungs.forEach((label, i) => {
      const d = (RUNG_THUMB_DATA[id] as unknown[])[i];
      const pairs = (CHECKS[id] as (l: string, d: unknown, level: number) => Array<[unknown, unknown, string]>)(label, d, i + 1);
      for (const [stated, drawn, what] of pairs) {
        if (stated === undefined) continue;
        assert.equal(drawn, stated, `${id} L${String(i + 1).padStart(2, '0')} "${label}": ${what}`);
        compared++;
      }
    });
    // Every level's label states at least one number the picture draws.
    assert.ok(compared >= rungs.length, `${id}: only ${compared} label numbers checked`);
  }
});

test('rung thumbnails: the main size grows (or holds) from L1 to L10', () => {
  const size: { [K in RungThumbTestId]: (d: RungThumbDataMap[K][number]) => number } = {
    'horizon.mind-runner': (d) => d.steps,
    'horizon.modpow-ladder': (d) => d.mDigits,
    'horizon.sliding-ladder': (d) => d.moves,
    'horizon.nonogram-ladder': (d) => d.size,
    'horizon.tiling-count': (d) => d.squares,
    'reasoning.grid-ladder': (d) => d.clues,
    'reasoning.truth-ladder': (d) => d.islanders,
    'reasoning.plans-ladder': (d) => d.disks,
    'math.exact-ladder': (d) => d.squares,
    'coding.engineering-ladder': (d) => d.ops.length,
    'work.invoices-ladder': (d) => d.docs,
    'work.payroll-ladder': (d) => d.months * d.staff,
    'work.expenses-ladder': (d) => d.linesPerMonth.reduce((a, b) => a + b, 0),
    'work.reconciliation-ladder': (d) => d.bankPerMonth.reduce((a, b) => a + b, 0),
    'work.crm-ladder': (d) => d.records,
    'work.contract-ladder': (d) => d.clauses,
    'work.sql-ladder': (d) => d.rules,
    'work.support-ladder': (d) => d.tickets,
  };
  for (const id of Object.keys(size) as RungThumbTestId[]) {
    const f = size[id] as (d: unknown) => number;
    const v = (RUNG_THUMB_DATA[id] as unknown[]).map(f);
    for (let i = 1; i < v.length; i++) assert.ok(v[i]! >= v[i - 1]!, `${id}: L${i + 1} (${v[i]}) is smaller than L${i} (${v[i - 1]})`);
    assert.ok(v[9]! > v[0]!, `${id}: L10 is not bigger than L1`);
  }
});
