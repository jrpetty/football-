// Builds ui/src/components/viz/rung-thumbs/data.ts: the real per-level parameters the ladder rung thumbnails draw.
// Every number is read from the ladder's test file (case prompts and notes) or from the builders' verification data;
// a pattern that does not match all ten levels stops the build, so the thumbnails can never drift from the tests.
// Usage: node scripts/rung-thumbs-data.ts          (writes data.ts)
//        node scripts/rung-thumbs-data.ts --check  (exit 1 if data.ts is out of date)
// test/rung-thumbs.test.ts runs the same build and also checks the result against config/ladders.json's rung labels.
import { readFileSync, writeFileSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import type { RungThumbDataMap } from '../ui/src/components/viz/rung-thumbs/types.ts';

const ROOT = join(import.meta.dirname, '..');
export const DATA_FILE = join(ROOT, 'ui', 'src', 'components', 'viz', 'rung-thumbs', 'data.ts');

interface Case {
  id: string;
  prompt: string;
  notes?: string;
}

function cases(rel: string): Case[] {
  const t = JSON.parse(readFileSync(join(ROOT, rel), 'utf8')) as { cases: Case[] };
  const out = t.cases.filter((c) => /^L\d\d$/.test(c.id));
  if (out.length !== 10) throw new Error(`${rel}: expected cases L01..L10, found ${out.length}`);
  return out;
}

function json<T>(rel: string): T {
  return JSON.parse(readFileSync(join(ROOT, rel), 'utf8')) as T;
}

/** The capture groups of `re` in `text`, as numbers (commas removed). Throws when it does not match. */
function nums(text: string, re: RegExp, where: string): number[] {
  const m = re.exec(text);
  if (!m) throw new Error(`${where}: no match for ${re}`);
  return m.slice(1).map((s) => Number(String(s ?? '0').replace(/,/g, '')));
}

function num(text: string, re: RegExp, where: string): number {
  return nums(text, re, where)[0]!;
}

const notes = (c: Case) => c.notes ?? '';

function mindRunner(): RungThumbDataMap['horizon.mind-runner'] {
  return cases('tests/horizon/mind-runner.json').map((c) => {
    const [lines, steps] = nums(notes(c), /(\d+) lines, (\d+) statements executed/, `mind-runner ${c.id}`);
    return { lines: lines!, steps: steps! };
  });
}

function modpow(): RungThumbDataMap['horizon.modpow-ladder'] {
  return cases('tests/horizon/modpow-ladder.json').map((c) => {
    const [m, e, k] = nums(notes(c), /m has (\d+) digits, e has (\d+) digits \(\d+ bits\), so about (\d+) modular multiplications/, `modpow ${c.id}`);
    return { mDigits: m!, eDigits: e!, multiplications: k! };
  });
}

function sliding(): RungThumbDataMap['horizon.sliding-ladder'] {
  return cases('tests/horizon/sliding-ladder.json').map((c) => {
    const where = `sliding ${c.id}`;
    const [rows, cols] = nums(c.prompt, /board with (\d+) rows and (\d+) columns/, where);
    const moves = num(notes(c), /proven minimum (\d+) moves/, where);
    const start = /Start:\n([\s\S]*?)\n\n/.exec(c.prompt);
    if (!start) throw new Error(`${where}: no start board`);
    const grid = start[1]!.split('\n').map((l) => l.trim().split(/\s+/));
    if (grid.length !== rows || grid.some((r) => r.length !== cols)) throw new Error(`${where}: start board is not ${rows}x${cols}`);
    const r = grid.findIndex((row) => row.includes('_'));
    return { rows: rows!, cols: cols!, blank: [r, grid[r]!.indexOf('_')], moves };
  });
}

function runsOf(block: string, label: string, n: number, where: string): number[] {
  const out: number[] = [];
  for (let i = 1; i <= n; i++) {
    const m = new RegExp(`^${label} ${i}: ([\\d ]+)$`, 'm').exec(block);
    if (!m) throw new Error(`${where}: no clue for ${label} ${i}`);
    out.push(m[1]!.trim().split(/\s+/).filter((s) => s !== '0').length);
  }
  return out;
}

function nonogram(): RungThumbDataMap['horizon.nonogram-ladder'] {
  return cases('tests/horizon/nonogram-ladder.json').map((c) => {
    const where = `nonogram ${c.id}`;
    const [rows, cols] = nums(c.prompt, /grid has (\d+) rows and (\d+) columns/, where);
    if (rows !== cols) throw new Error(`${where}: not square`);
    const filled = num(notes(c), /\d+x\d+, (\d+) filled cells/, where);
    return { size: rows!, filled, rowRuns: runsOf(c.prompt, 'Row', rows!, where), colRuns: runsOf(c.prompt, 'Column', cols!, where) };
  });
}

function tiling(): RungThumbDataMap['horizon.tiling-count'] {
  return cases('tests/horizon/tiling-count.json').map((c) => {
    const where = `tiling ${c.id}`;
    const [rows, cols] = nums(c.prompt, /board with (\d+) rows and (\d+) columns/, where);
    const squares = num(c.prompt, /the (\d+) marked squares/, where);
    const lines = c.prompt.split('\n').filter((l) => /^[#.]+$/.test(l));
    if (lines.length !== rows || lines.some((l) => l.length !== cols)) throw new Error(`${where}: board is not ${rows}x${cols}`);
    const holes: number[] = [];
    lines.forEach((l, r) => [...l].forEach((ch, k) => ch === '.' && holes.push(r * cols! + k)));
    if (rows! * cols! - holes.length !== squares) throw new Error(`${where}: ${squares} squares but ${rows! * cols! - holes.length} drawn`);
    return { rows: rows!, cols: cols!, squares, holes };
  });
}

function grid(): RungThumbDataMap['reasoning.grid-ladder'] {
  return cases('tests/reasoning/grid-ladder.json').map((c) => {
    const [p, k, clues, compound] = nums(notes(c), /(\d+) positions x (\d+) categories = \d+ attribute values, (\d+) clues \((\d+) compound\)/, `grid ${c.id}`);
    return { positions: p!, categories: k!, clues: clues!, compound: compound! };
  });
}

function truth(): RungThumbDataMap['reasoning.truth-ladder'] {
  return cases('tests/reasoning/truth-ladder.json').map((c) => {
    const [n, s, a, st] = nums(notes(c), /(\d+) islanders \((\d+) sp(?:y|ies), (\d+) alternators?\), (\d+) statements/, `truth ${c.id}`);
    return { islanders: n!, spies: s!, alternators: a!, statements: st! };
  });
}

function plans(): RungThumbDataMap['reasoning.plans-ladder'] {
  const src = json<Array<{ level: number; disks: number; minimum: number; start: number[][] }>>('verification/plans_ladder/cases.json');
  return cases('tests/reasoning/plans-ladder.json').map((c, i) => {
    const where = `plans ${c.id}`;
    const [disks, moves] = nums(notes(c), /(\d+) disks on four pegs: .*? proven minimum (\d+) moves/, where);
    const v = src.find((s) => s.level === i + 1);
    if (!v || v.disks !== disks || v.minimum !== moves || v.start.length !== 4) throw new Error(`${where}: verification/plans_ladder/cases.json disagrees with the test notes`);
    if (v.start.flat().length !== disks) throw new Error(`${where}: start does not hold ${disks} disks`);
    // The prompt states the same start, peg by peg.
    v.start.forEach((peg, k) => {
      const line = new RegExp(`Peg ${'ABCD'[k]}: ([^\\n]*)`).exec(c.prompt)?.[1] ?? '';
      const listed = (line.match(/\d+/g) ?? []).map(Number);
      if (listed.join(',') !== peg.join(',')) throw new Error(`${where}: peg ${'ABCD'[k]} is ${line} in the prompt, ${peg} in the verification data`);
    });
    return { disks: disks!, moves: moves!, pegs: v.start };
  });
}

function exact(): RungThumbDataMap['math.exact-ladder'] {
  const src = json<Array<{ level: number; N: number; d: number; ladders: Array<[number, number]>; snakes: Array<[number, number]>; traps: number[]; digits: [number, number] }>>(
    'verification/exact-ladder/snakes_cases.json',
  );
  return cases('tests/math/exact-ladder.json').map((c, i) => {
    const where = `exact ${c.id}`;
    const [n, d, ladders, snakes, traps, den] = nums(notes(c), /track 0\.\.(\d+), spinner 1-(\d+), (\d+) ladders, (\d+) snakes, (\d+) traps .*?and a (\d+)-digit denominator/, where);
    const v = src.find((s) => s.level === i + 1);
    if (!v || v.N !== n || v.d !== d || v.ladders.length !== ladders || v.snakes.length !== snakes || v.traps.length !== traps || v.digits[1] !== den) {
      throw new Error(`${where}: verification/exact-ladder/snakes_cases.json disagrees with the test notes`);
    }
    return { squares: n!, spinner: d!, ladders: v.ladders, snakes: v.snakes, traps: v.traps, denominatorDigits: den! };
  });
}

function engineering(): RungThumbDataMap['coding.engineering-ladder'] {
  const ops: string[] = [];
  return cases('tests/coding/engineering-ladder.json').map((c) => {
    const where = `engineering ${c.id}`;
    const m = /Adds ([a-z, ]+) \((\d+) operations in all, (\d+) kinds? of update\)/.exec(notes(c));
    if (!m) throw new Error(`${where}: no "Adds …" in the notes`);
    const added = m[1]!.split(',').map((s) => s.trim());
    ops.push(...added);
    if (ops.length !== Number(m[2])) throw new Error(`${where}: ${ops.length} operations so far, notes say ${m[2]}`);
    const n = /up to n = ([\d,]+)/.exec(notes(c)) ?? /n and operations up to ([\d,]+)/.exec(notes(c));
    if (!n) throw new Error(`${where}: no array size in the notes`);
    return { ops: [...ops], added, updateKinds: Number(m[3]), n: Number(n[1]!.replace(/,/g, '')) };
  });
}

function invoices(): RungThumbDataMap['work.invoices-ladder'] {
  return cases('tests/work/invoices-ladder.json').map((c) => {
    const where = `invoices ${c.id}`;
    const fam = num(notes(c), /the basics \+ (\d+) families/, where);
    const pools = num(notes(c), /(\d+) pools/, where);
    const size = pools ? num(notes(c), /pools \((\d+) documents each\)/, where) : 0;
    const docs = num(notes(c), /(\d+) docs, total/, where);
    return { docs, pools, poolSize: size, families: fam };
  });
}

function payroll(): RungThumbDataMap['work.payroll-ladder'] {
  return cases('tests/work/payroll-ladder.json').map((c) => {
    const where = `payroll ${c.id}`;
    const [months, staff] = nums(notes(c), /(\d+) months?, (\d+) staff/, where);
    const wrong = /wrong payslips per month ([\d /]+);/.exec(notes(c));
    if (!wrong) throw new Error(`${where}: no wrong payslips per month`);
    const per = wrong[1]!.trim().split(/\s*\/\s*/).map(Number);
    if (per.length !== months) throw new Error(`${where}: ${per.length} monthly counts for ${months} months`);
    return { months: months!, staff: staff!, wrongPerMonth: per };
  });
}

function expenses(): RungThumbDataMap['work.expenses-ladder'] {
  return cases('tests/work/expenses-ladder.json').map((c) => {
    const where = `expenses ${c.id}`;
    const [months, lines, people, claims] = nums(notes(c), /(\d+) months?, (\d+) claim lines from (\d+) employees in (\d+) claims/, where);
    // Monthly batches are headed "══ <Month> <year> batch ══"; a one-month level has no heading.
    const parts = c.prompt.split(/^══ .* batch ══$/m);
    const batches = parts.length === 1 ? parts : parts.slice(1);
    const per = batches.map((b) => (b.match(/^\s+L\d+ \|/gm) ?? []).length);
    if (per.length !== months) throw new Error(`${where}: ${per.length} batches, notes say ${months} months`);
    if (per.reduce((a, b) => a + b, 0) !== lines) throw new Error(`${where}: ${per.join('+')} lines, notes say ${lines}`);
    return { linesPerMonth: per, people: people!, claims: claims! };
  });
}

function reconciliation(): RungThumbDataMap['work.reconciliation-ladder'] {
  return cases('tests/work/reconciliation-ladder.json').map((c) => {
    const where = `reconciliation ${c.id}`;
    const [months, bank, books, later] = nums(notes(c), /(\d+) months?, (\d+) statement lines and (\d+) cash-book entries, (\d+) entries cleared in a later month/, where);
    const sections = c.prompt.split(/^(?=Bank statement, |Cash book, )/m).slice(1);
    const bankPer = sections.filter((s) => s.startsWith('Bank statement')).map((s) => (s.match(/^B\d+ /gm) ?? []).length);
    const booksPer = sections.filter((s) => s.startsWith('Cash book')).map((s) => (s.match(/^L\d+ /gm) ?? []).length);
    const sum = (a: number[]) => a.reduce((x, y) => x + y, 0);
    if (bankPer.length !== months || booksPer.length !== months) throw new Error(`${where}: ${bankPer.length}/${booksPer.length} monthly sections, notes say ${months}`);
    if (sum(bankPer) !== bank || sum(booksPer) !== books) throw new Error(`${where}: ${sum(bankPer)}/${sum(booksPer)} lines, notes say ${bank}/${books}`);
    return { bankPerMonth: bankPer, booksPerMonth: booksPer, clearedLater: later! };
  });
}

function crm(): RungThumbDataMap['work.crm-ladder'] {
  const growth = json<Record<string, Array<{ level: string; records: number; groups: number; refusedMerges: number }>>>('verification/ladders-data-support/growth.json')['work.crm-ladder']!;
  return cases('tests/work/crm-ladder.json').map((c) => {
    const where = `crm ${c.id}`;
    const records = num(notes(c), /(\d+) records/, where);
    const groups = num(notes(c), /(\d+) duplicate groups/, where);
    const sizes = /duplicate groups (\{[^}]*\})/.exec(notes(c));
    if (!sizes) throw new Error(`${where}: no group sizes`);
    const groupSizes = JSON.parse(sizes[1]!) as Record<string, number>;
    const refused = /(\d+) merges refused/.exec(notes(c));
    const g = growth.find((r) => r.level === c.id);
    if (!g || g.records !== records || g.groups !== groups || (refused && g.refusedMerges !== Number(refused[1]))) {
      throw new Error(`${where}: verification/ladders-data-support/growth.json disagrees with the test notes`);
    }
    if (Object.values(groupSizes).reduce((a, b) => a + b, 0) !== groups) throw new Error(`${where}: group sizes do not add up to ${groups}`);
    return { records, groupSizes, refusedMerges: g.refusedMerges };
  });
}

function contract(): RungThumbDataMap['work.contract-ladder'] {
  return cases('tests/work/contract-ladder.json').map((c) => {
    const [words, clauses, changes, resp, note] = nums(
      notes(c),
      /(\d+) words across both drafts, (\d+) clauses and rows in version 2, (\d+) changes in version 2, (\d+) response items and (\d+) note items/,
      `contract ${c.id}`,
    );
    return { clauses: clauses!, changes: changes!, replies: resp! + note!, words: words! };
  });
}

function sql(): RungThumbDataMap['work.sql-ladder'] {
  return cases('tests/work/sql-ladder.json').map((c) => {
    const [rules, tables] = nums(notes(c), /Depth: (\d+) semantics? to compose in one query, over (\d+) tables?/, `sql ${c.id}`);
    return { rules: rules!, tables: tables! };
  });
}

function support(): RungThumbDataMap['work.support-ladder'] {
  const growth = json<Record<string, Array<{ level: string; tickets: number; accounts: number; families: number; changedByAccounts: number }>>>(
    'verification/ladders-data-support/growth.json',
  )['work.support-ladder']!;
  return cases('tests/work/support-ladder.json').map((c) => {
    const where = `support ${c.id}`;
    const tickets = num(notes(c), /(\d+) tickets/, where);
    const acc = /over (\d+) accounts change (\d+) tickets/.exec(notes(c));
    const g = growth.find((r) => r.level === c.id);
    if (!g || g.tickets !== tickets || g.accounts !== (acc ? Number(acc[1]) : 0) || g.changedByAccounts !== (acc ? Number(acc[2]) : 0)) {
      throw new Error(`${where}: verification/ladders-data-support/growth.json disagrees with the test notes`);
    }
    return { tickets, accounts: g.accounts, families: g.families, changedByAccounts: g.changedByAccounts };
  });
}

export function buildRungThumbData(): RungThumbDataMap {
  return {
    'horizon.mind-runner': mindRunner(),
    'horizon.modpow-ladder': modpow(),
    'horizon.sliding-ladder': sliding(),
    'horizon.nonogram-ladder': nonogram(),
    'horizon.tiling-count': tiling(),
    'reasoning.grid-ladder': grid(),
    'reasoning.truth-ladder': truth(),
    'reasoning.plans-ladder': plans(),
    'math.exact-ladder': exact(),
    'coding.engineering-ladder': engineering(),
    'work.invoices-ladder': invoices(),
    'work.payroll-ladder': payroll(),
    'work.expenses-ladder': expenses(),
    'work.reconciliation-ladder': reconciliation(),
    'work.crm-ladder': crm(),
    'work.contract-ladder': contract(),
    'work.sql-ladder': sql(),
    'work.support-ladder': support(),
  };
}

/** data.ts, one level per line. */
export function renderDataModule(data: RungThumbDataMap): string {
  const body = Object.entries(data)
    .map(([id, levels]) => `  '${id}': [\n${(levels as unknown[]).map((l) => `    ${JSON.stringify(l)},`).join('\n')}\n  ],`)
    .join('\n');
  return `// GENERATED by scripts/rung-thumbs-data.ts from the ladder test files and verification data. Do not edit:
// run \`node scripts/rung-thumbs-data.ts\` after a ladder changes. test/rung-thumbs.test.ts fails when this is stale.
import type { RungThumbDataMap } from './types.ts';

export const RUNG_THUMB_DATA: RungThumbDataMap = {
${body}
};
`;
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const text = renderDataModule(buildRungThumbData());
  if (process.argv.includes('--check')) {
    const now = readFileSync(DATA_FILE, 'utf8');
    if (now !== text) {
      console.error('rung-thumbs data.ts is out of date: run node scripts/rung-thumbs-data.ts');
      process.exit(1);
    }
    console.log('rung-thumbs data.ts is up to date');
  } else {
    writeFileSync(DATA_FILE, text);
    console.log(`wrote ${DATA_FILE}`);
  }
}
