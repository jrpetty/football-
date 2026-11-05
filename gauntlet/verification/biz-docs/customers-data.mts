// Builds the data the customers, comms and data document pictures need beyond the test files:
//
//   ui/src/business/docs/customers-data/sqlResults.json  the correct result table of every SQL case on the live
//       database (the reference query of the test file, run with node:sqlite), shown beside the model's query;
//   ui/src/mock/customersDataDemo.json  demo-only (?mock=1) material for realistic wrong answers: the generators'
//       own scripted careless and flawed answers (verification/*/scripted.json) and, for SQL, each case's careless
//       queries (from the generators) with their result tables and per-database verdicts worked out by the real
//       comparison (src/scoring/sql.ts compareResults).
//
//   node verification/biz-docs/customers-data.mts           write both files
//   node verification/biz-docs/customers-data.mts --check   fail if a committed file differs
import { readFileSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { compareResults } from '../../src/scoring/sql.ts';
import { openDb, query, type Tables } from '../realworld-sql/lib.mts';
import * as shop from '../realworld-sql/shop.mts';
import * as warehouse from '../realworld-sql/warehouse.mts';
import { LADDER_QUESTIONS } from '../ladders-data-support/sql-ladder.mts';

const HERE = dirname(fileURLToPath(import.meta.url));
const ROOT = join(HERE, '..', '..');
const MAX_ROWS = 25;

type Cell = string | number | null;
interface SqlCase {
  id: string;
  expected: { reference: string; ordered?: boolean };
}
interface SqlTest {
  id: string;
  cases: SqlCase[];
  scorer: { schema: string; tolerance?: number; databases: Array<{ id: string; tables: Tables }> };
}

const SQL_TESTS: Array<[string, Array<{ id: string; careless: Array<{ why: string; sql: string }> }>]> = [
  ['sql-questions', shop.QUESTIONS],
  ['sql-hard', warehouse.QUESTIONS],
  ['sql-ladder', LADDER_QUESTIONS],
];

const round = (v: Cell): Cell => (typeof v === 'number' && !Number.isInteger(v) ? Math.round(v * 1e6) / 1e6 : v);
const table = (r: { columns: string[]; rows: Cell[][] }, max = MAX_ROWS) => ({ columns: r.columns, rows: r.rows.slice(0, max).map((row) => row.map(round)), total: r.rows.length });

const results: Record<string, Record<string, unknown>> = {};
const demo: Record<string, Record<string, unknown>> = {};

for (const [file, questions] of SQL_TESTS) {
  const t = JSON.parse(readFileSync(join(ROOT, 'tests', 'work', `${file}.json`), 'utf8')) as SqlTest;
  const dbs = t.scorer.databases.map((d) => ({ id: d.id, db: openDb(t.scorer.schema, d.tables) }));
  const tol = t.scorer.tolerance ?? 0.01;
  results[t.id] = {};
  demo[t.id] = {};
  for (const c of t.cases) {
    const ordered = !!c.expected.ordered;
    const refs = dbs.map((d) => query(d.db, c.expected.reference));
    results[t.id]![c.id] = { ordered, live: table(refs[0]!) };
    const q = questions.find((x) => x.id === c.id);
    const slips = (q?.careless ?? []).slice(0, 2).map((slip) => {
      const items = dbs.map((d, i) => {
        const label = d.id === 'live' ? 'live database (sampled in the prompt)' : `hidden copy ${d.id}`;
        try {
          const got = query(d.db, slip.sql);
          const cmp = compareResults(refs[i]!, got, { ordered, tolerance: tol });
          return { label, passed: cmp.score === 1, detail: `${cmp.reason}${cmp.score > 0 && cmp.score < 1 ? ` (${Math.round(cmp.score * 100)}%)` : ''}`, score: cmp.score, rows: i === 0 ? table(got, 12) : undefined };
        } catch (err) {
          return { label, passed: false, detail: `error: ${(err as Error).message.slice(0, 200)}`, score: 0, rows: undefined };
        }
      });
      const score = Math.round((items.reduce((s, x) => s + x.score, 0) / items.length) * 10000) / 10000;
      return { why: slip.why, sql: slip.sql, score, live: items[0]!.rows, items: items.map(({ label, passed, detail }) => ({ label, passed, detail })) };
    });
    demo[t.id]![c.id] = { slips };
  }
}

// Scripted wrong answers of the JSON tests (the generators' careless and flawed players).
const SCRIPTED: Array<[string, string[]]> = [
  ['verification/realworld-work/scripted.json', ['work.inbox-triage', 'work.meeting-scheduler', 'work.contract-redline']],
  ['verification/realworld-business/scripted.json', ['work.support-refunds', 'work.crm-dedupe']],
  ['verification/ladders-data-support/scripted.json', ['work.crm-ladder', 'work.support-ladder']],
  ['verification/ladders-data-support/contract-scripted.json', ['work.contract-ladder']],
];
const KEEP = /^(careless|slip|careful_human_slips)$/
for (const [f, ids] of SCRIPTED) {
  const all = JSON.parse(readFileSync(join(ROOT, f), 'utf8')) as Record<string, Record<string, Record<string, unknown>>>;
  for (const id of ids) {
    demo[id] = {};
    for (const [caseId, players] of Object.entries(all[id] ?? {})) {
      const variants: Record<string, unknown> = {};
      for (const [name, ans] of Object.entries(players)) if (KEEP.test(name) && (name === 'careless' || !id.endsWith('-ladder')) && ans && typeof ans === 'object') variants[name] = ans;
      demo[id]![caseId] = { variants };
    }
  }
}

const outputs: Array<[string, unknown]> = [
  [join(ROOT, 'ui', 'src', 'business', 'docs', 'customers-data', 'sqlResults.json'), results],
  [join(ROOT, 'ui', 'src', 'mock', 'customersDataDemo.json'), demo],
];
let bad = 0;
for (const [path, obj] of outputs) {
  const text = `${JSON.stringify(obj)}\n`;
  if (process.argv.includes('--check')) {
    if (readFileSync(path, 'utf8') !== text) {
      console.error(`${path} differs from the generator output`);
      bad++;
    } else console.log(`${path} reproduces byte for byte`);
  } else {
    writeFileSync(path, text);
    console.log(`wrote ${path} (${(text.length / 1024).toFixed(0)} KB)`);
  }
}
if (bad) process.exit(1);
