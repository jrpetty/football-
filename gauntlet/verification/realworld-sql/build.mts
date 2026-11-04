// Builds tests/work/sql-questions.json and tests/work/sql-hard.json from fixed seeds and checks every answer key.
//
//   node verification/realworld-sql/build.mts          write both test files
//   node verification/realworld-sql/build.mts --check  regenerate in memory and fail if a committed file differs
//
// For every question and every database (the live one the prompt samples, plus the hidden copies) it asserts:
//   1. the reference query and an independent JavaScript solver over the raw rows give the same result;
//   2. the reference result does not depend on SQLite's scan order (re-run with reverse_unordered_selects);
//   3. the result is non-empty, at most 200 rows, and differs between every pair of databases (so a query that
//      hard-codes one database's answer cannot score on the others);
//   4. every careless query (a real-world mistake) scores below 1 on average and is wrong on at least one database.
import { readFileSync, writeFileSync, mkdirSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { unsafeReason } from '../../src/scoring/sql.ts';
import { openDb, query, same, sampleTable, score, serialiseTest, tok, type Cell, type Question, type Tables } from './lib.mts';
import * as shop from './shop.mts';
import * as warehouse from './warehouse.mts';

const HERE = dirname(fileURLToPath(import.meta.url));
const TESTS = join(HERE, '..', '..', 'tests', 'work');

export interface PackMeta {
  id: string;
  name: string;
  description: string;
  difficulty: string;
  tags: string[];
  hook: string;
}

export interface Pack {
  file: string;
  meta: PackMeta;
  schema: string;
  seeds: number[];
  generate: (seed: number) => { tables: Tables };
  questions: Question[];
  preamble: (live: Tables) => string;
  tolerance: number;
}

export const ANSWER_RULES = `== HOW TO ANSWER ==
- Reply with ONE SQLite query that answers the question (SQLite 3.50: CTEs, window functions, date(), datetime(), strftime() and julianday() all work; text values go in single quotes). Put it in a \`\`\`sql code block. If you write more than one SQL block, only the last one is run, so give exactly one answer.
- It must be a single read-only SELECT (or WITH ... SELECT) statement. It is run on the live database (the sample rows above are only a small part of it) and also on other copies of the database with the same tables but different data, so it has to compute the answer from the tables: hard-coded values only fit one copy.
- Return exactly the columns the question asks for. Column names and the order of the columns don't matter. Sort the rows only when the question asks for an order.
- Numbers are compared to within the tolerance the question states (rounding as asked is always fine). NULL only matches NULL.
- Each database scores the share of result rows that match the correct result (half of that if the question asks for an order and the rows are in the wrong order); the question scores the average over the databases. You may explain your reasoning before the code block.`;

/** Tables whose sample rows are named by their first two columns. */
const COMPOSITE = new Set(['order_items', 'fx_rates', 'fact_order_lines', 'dim_price_history', 'promotion_products', 'subscription_status_daily', 'dim_product_category_history']);

function samples(tables: Tables, ids: Record<string, Array<string | number>>): string {
  const parts: string[] = [];
  for (const [name, t] of Object.entries(tables)) {
    const want = new Set((ids[name] ?? []).map(String));
    const key = (r: Cell[]) => (COMPOSITE.has(name) ? `${r[0]}|${r[1]}` : String(r[0]));
    const rows = t.rows.filter((r) => want.has(key(r)));
    parts.push(sampleTable(name, t, rows));
  }
  return parts.join('\n\n');
}

export function standardPreamble(company: string, schema: string, rules: string, tables: Tables, ids: Record<string, Array<string | number>>): string {
  return `${company}\n\n== TABLES (SQLite) ==\n${schema}\n\n== ${rules.split('\n')[0]!.replace(/:$/, '').toUpperCase()} ==\n${rules.split('\n').slice(1).join('\n')}\n\n== SAMPLE ROWS (from the live database) ==\n${samples(tables, ids)}\n\n${ANSWER_RULES}`;
}

export const PACKS: Pack[] = [
  {
    file: 'sql-questions.json',
    meta: shopMeta(),
    schema: shop.SCHEMA,
    seeds: [20260701, 41117, 73003, 90521],
    generate: shop.generateShop,
    questions: shop.QUESTIONS,
    preamble: (t) => standardPreamble(shop.COMPANY, shop.SCHEMA, shop.RULES, t, shop.sampleIds(t)),
    tolerance: 0.01,
  },
  {
    file: 'sql-hard.json',
    meta: warehouse.META,
    schema: warehouse.SCHEMA,
    seeds: warehouse.SEEDS,
    generate: warehouse.generateWarehouse,
    questions: warehouse.QUESTIONS,
    preamble: (t) => standardPreamble(warehouse.COMPANY, warehouse.SCHEMA, warehouse.RULES, t, warehouse.sampleIds(t)),
    tolerance: 0.01,
  },
];

function shopMeta(): PackMeta {
  return {
    id: 'work.sql-questions',
    name: 'Ask the Database',
    description:
      'Twelve questions a manager at a UK outdoor-gear retailer actually asks, answered with one SQLite query each. The prompt shows the schema, the business rules (test accounts, soft deletes, guest checkouts, euros, refunds, what counts as active) and about thirty sample rows; the query is run read-only on the full live database and on three hidden copies with different data and compared with a reference query on each, so hard-coding fails. Easy: date ranges, anti-joins, a location with no sales. Medium: revenue per store and month without fan-out, MRR at every month end, early-refund rates per quarter, a RANK() leaderboard. Hard: a board KPI table built from a finance memo with two amendments (a 23:00 website cut-off, fully refunded sales, refunds by the month they were made), customer segments from an ordered rule list with exceptions, a commission run with cover shifts, bands, first-month thresholds, manager overrides and leavers, and membership spells merged from overlapping subscriptions.',
    difficulty: 'hard',
    tags: ['sql', 'sqlite', 'data-analysis', 'business', 'real-world', 'work'],
    hook: 'Twelve questions from the boss, one database, no hard-coding: the query runs on hidden copies too.',
  };
}

export interface Built {
  text: string;
  report: Array<{ id: string; difficulty: string; rows: number[]; careless: Array<{ why: string; score: number }> }>;
}

export function buildPack(pack: Pack, opts: { collect?: string[] } = {}): Built {
  const fail = (msg: string) => {
    if (opts.collect) opts.collect.push(msg);
    else throw new Error(msg);
  };
  const dbs = pack.seeds.map((s, i) => ({ id: i === 0 ? 'live' : String.fromCharCode(65 + i - 1), tables: pack.generate(s).tables }));
  const report: Built['report'] = [];
  const cases: unknown[] = [];
  const opened = dbs.map((d) => openDb(pack.schema, d.tables));
  const reversed = dbs.map((d) => openDb(pack.schema, d.tables, { reverseUnordered: true }));
  for (const q of pack.questions) {
    const tol = q.tolerance ?? pack.tolerance;
    const why = unsafeReason(q.reference);
    if (why) fail(`${pack.file} ${q.id}: reference refused (${why})`);
    const results = opened.map((db) => query(db, q.reference));
    results.forEach((res, i) => {
      const solved = { columns: res.columns, rows: q.solve(dbs[i]!.tables) };
      if (!same(res, solved, q.ordered, tol)) fail(`${pack.file} ${q.id} on ${dbs[i]!.id}: reference and solver disagree\nSQL: ${JSON.stringify(res.rows.slice(0, 8))}\nJS:  ${JSON.stringify(solved.rows.slice(0, 8))}`);
      const rev = query(reversed[i]!, q.reference);
      if (q.ordered ? JSON.stringify(rev.rows) !== JSON.stringify(res.rows) : !same(res, rev, false, 0)) fail(`${pack.file} ${q.id} on ${dbs[i]!.id}: result depends on scan order`);
      if (!res.rows.length || res.rows.length > 200) fail(`${pack.file} ${q.id} on ${dbs[i]!.id}: ${res.rows.length} rows`);
      if (res.rows.some((r) => r.some((v) => v === null)) && !/LAG|NULL/i.test(q.text)) fail(`${pack.file} ${q.id} on ${dbs[i]!.id}: NULL in the reference result`);
    });
    for (let i = 0; i < results.length; i++)
      for (let j = i + 1; j < results.length; j++)
        if (score(results[i]!, results[j]!, q.ordered, tol) >= 1) fail(`${pack.file} ${q.id}: databases ${dbs[i]!.id} and ${dbs[j]!.id} give the same answer`);
    // A query that hard-codes the live answer must earn (almost) nothing on the hidden copies: at most one row in
    // five may coincide on any copy, and the mean overlap must stay at or under 0.1.
    const overlap = results.slice(1).map((r) => score(r, results[0]!, q.ordered, tol));
    if (overlap.some((o) => o > 0.2) || overlap.reduce((a, b) => a + b, 0) / overlap.length > 0.1) fail(`${pack.file} ${q.id}: the hidden copies share too many answer rows with the live one (${overlap.map((o) => o.toFixed(2)).join(', ')})`);
    const careless = q.careless.map((c) => {
      const per = opened.map((db, i) => {
        try {
          return score(results[i]!, query(db, c.sql), q.ordered, tol);
        } catch (e) {
          throw new Error(`${pack.file} ${q.id} careless "${c.why}" failed to run: ${(e as Error).message}`);
        }
      });
      const mean = per.reduce((a, b) => a + b, 0) / per.length;
      if (mean >= 1 || per.every((p) => p === 1)) fail(`${pack.file} ${q.id}: careless query "${c.why}" scores ${mean}`);
      return { why: c.why, score: Math.round(mean * 1000) / 1000 };
    });
    report.push({ id: q.id, difficulty: q.difficulty, rows: results.map((r) => r.rows.length), careless });
    cases.push({
      id: q.id,
      prompt: `== QUESTION ==\n${q.text}`,
      expected: { reference: q.reference, ordered: q.ordered, ...(q.tolerance !== undefined ? { tolerance: q.tolerance } : {}) },
      notes: `[${q.difficulty}] ${q.notes} Reference verified by an independent JavaScript solver on all ${dbs.length} databases (verification/realworld-sql). Careless queries and their mean scores: ${careless.map((c) => `${c.why} (${c.score})`).join('; ')}.`,
    });
  }
  for (const db of [...opened, ...reversed]) db.close();
  const preamble = pack.preamble(dbs[0]!.tables);
  const longest = Math.max(...pack.questions.map((q) => q.text.length));
  const obj = {
    kind: 'prompt',
    ...pack.meta,
    category: 'work',
    version: '1.0.0',
    maxOutputTokens: 16000,
    estimate: { inputTokens: tok(preamble) + Math.round(tok('x'.repeat(longest)) * 0.8), outputTokens: pack.meta.id === 'work.sql-hard' ? 8000 : 5000 },
    author: 'Gauntlet Core',
    createdAt: '2026-11-04',
    preamble,
    scorer: { type: 'sql', dialect: 'sqlite', tolerance: pack.tolerance, schema: pack.schema, databases: dbs },
    cases,
  };
  const ordered = {
    kind: obj.kind,
    id: obj.id,
    version: obj.version,
    name: obj.name,
    category: obj.category,
    description: obj.description,
    difficulty: obj.difficulty,
    tags: obj.tags,
    hook: obj.hook,
    maxOutputTokens: obj.maxOutputTokens,
    estimate: obj.estimate,
    author: obj.author,
    createdAt: obj.createdAt,
    preamble: obj.preamble,
    cases: obj.cases,
    scorer: obj.scorer,
  };
  return { text: serialiseTest(ordered), report };
}

const isMain = process.argv[1] && fileURLToPath(import.meta.url) === process.argv[1];
if (isMain) {
  const check = process.argv.includes('--check');
  mkdirSync(TESTS, { recursive: true });
  let bad = 0;
  for (const pack of PACKS) {
    const built = buildPack(pack);
    const path = join(TESTS, pack.file);
    for (const r of built.report) console.log(`${pack.file} ${r.id} [${r.difficulty}] rows ${r.rows.join('/')} · careless ${r.careless.map((c) => c.score).join(', ')}`);
    if (check) {
      const now = readFileSync(path, 'utf8');
      if (now !== built.text) {
        console.error(`${path} differs from the generator output`);
        bad++;
      } else console.log(`${path} reproduces byte for byte`);
    } else {
      writeFileSync(path, built.text);
      console.log(`wrote ${path} (${(built.text.length / 1024).toFixed(0)} KB)`);
    }
  }
  if (bad) process.exit(1);
}
