/**
 * "Ask the Database" (work.sql-questions, work.sql-hard) and the `sql` scorer.
 *
 *  - the generator reproduces both test files byte for byte (it also re-proves every key with a second solver);
 *  - every reference query scores 100 through the real scorer (sandboxed worker, all four databases);
 *  - equivalent rewrites score 100 (aliases, column order, ORDER BY when not asked, numbers as text or rounded,
 *    a trailing semicolon, no code fence), careless queries lose marks, hard-coded answers fail on the hidden copies;
 *  - unsafe statements are refused before anything runs, infinite queries are stopped, huge results are cut off;
 *  - random filler, the Random Baseline and SELECT * score 0; scoring is deterministic.
 */
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { getTest, renderCase } from '../src/core/registry.ts';
import { TESTS_DIR } from '../src/core/paths.ts';
import { scoreResponse } from '../src/scoring/index.ts';
import { checkReply } from '../src/scoring/integrity.ts';
import { compareResults, extractSqlCandidates, referenceResults, scoreSql, unsafeReason, type ResultSet } from '../src/scoring/sql.ts';
import { createMockAdapter } from '../src/providers/mock.ts';
import type { ArtifactRef, PromptTest, SqlExpected, SqlScorerSpec } from '../src/core/types.ts';
import { buildPack, PACKS } from '../verification/realworld-sql/build.mts';
import * as shop from '../verification/realworld-sql/shop.mts';
import * as warehouse from '../verification/realworld-sql/warehouse.mts';

const IDS = ['work.sql-questions', 'work.sql-hard'] as const;
const def = (id: string) => getTest(id)!.definition as PromptTest;
const spec = (id: string) => def(id).scorer as SqlScorerSpec;
const expectedOf = (id: string, caseId: string) => def(id).cases.find((c) => c.id === caseId)!.expected as SqlExpected;
const fenced = (sql: string) => `Here is the query.\n\n\`\`\`sql\n${sql}\n\`\`\``;
const noJudges = { ids: [], ask: async () => [] };
const noArtifacts = (name: string): ArtifactRef => ({ name, kind: 'text', file: name, bytes: 0 });

async function score(id: string, caseId: string, reply: string): Promise<number> {
  const d = def(id);
  const c = d.cases.find((x) => x.id === caseId)!;
  const out = await scoreResponse({ scorer: d.scorer, expected: c.expected, response: reply, stopReason: 'end', taskText: renderCase(d, c).turns.join('\n'), judges: noJudges, saveArtifact: noArtifacts, signal: new AbortController().signal });
  return out.score ?? 0;
}

test('the generator reproduces both test files byte for byte', () => {
  for (const pack of PACKS) {
    const built = buildPack(pack);
    assert.equal(built.text, readFileSync(join(TESTS_DIR, 'work', pack.file), 'utf8'), pack.file);
  }
});

test('shape: 12 and 9 questions, a third easy / medium / hard, live database first and three hidden copies', () => {
  for (const [id, qs, n] of [['work.sql-questions', shop.QUESTIONS, 12], ['work.sql-hard', warehouse.QUESTIONS, 9]] as const) {
    const d = def(id);
    assert.equal(d.cases.length, n);
    assert.deepEqual(spec(id).databases.map((x) => x.id), ['live', 'A', 'B', 'C']);
    const counts = { easy: 0, medium: 0, hard: 0 };
    for (const q of qs) counts[q.difficulty]++;
    assert.deepEqual(counts, { easy: n / 3, medium: n / 3, hard: n / 3 }, id);
    // Easy to hard in case order.
    const order = { easy: 0, medium: 1, hard: 2 };
    for (let i = 1; i < qs.length; i++) assert.ok(order[qs[i]!.difficulty] >= order[qs[i - 1]!.difficulty]);
    // Prompts: schema, rules, sample rows (15-40 rows) and the answer rules; never the reference query.
    for (const c of d.cases) {
      const text = renderCase(d, c).turns.join('\n');
      assert.match(text, /CREATE TABLE/);
      assert.match(text, /```sql code block/);
      assert.ok(!text.includes((c.expected as SqlExpected).reference), `${id}/${c.id} leaks its reference query`);
    }
    const sampleRows = (d.preamble!.match(/; (\d+) shown\)/g) ?? []).reduce((s, m) => s + Number(/\d+/.exec(m)![0]), 0);
    assert.ok(sampleRows >= 15 && sampleRows <= 40, `${id}: ${sampleRows} sample rows`);
  }
});

test('every reference query scores 100 on all four databases, and triggers no fair-play rule', async () => {
  for (const id of IDS) {
    for (const c of def(id).cases) {
      const reply = fenced(`${(c.expected as SqlExpected).reference};`);
      assert.equal(await score(id, c.id, reply), 1, `${id}/${c.id}`);
      assert.equal(checkReply(reply, { taskText: renderCase(def(id), c).turns.join('\n') }), null);
    }
  }
});

test('style never costs marks: aliases, column order, unasked ORDER BY, rounding, numbers as text, no fence, trailing prose', async () => {
  const id = 'work.sql-questions';
  // q01 written another way, unfenced, lower case, with a FINAL ANSWER label and prose after it.
  assert.equal(await score(id, 'q01', `final answer: select count(customer_id) as n from customers where is_test = 0 and deleted_at is null and strftime('%Y-%m', signed_up_at) = '2026-02';\n\nThat counts February sign-ups.`), 1);
  // q02 with the columns in another order, ORDER BY although no order was asked, a count as text and items as a real.
  assert.equal(
    await score(id, 'q02', fenced(`SELECT CAST(COUNT(DISTINCT o.order_id) AS TEXT) AS n, st.name AS location, TOTAL(oi.quantity) AS items
FROM stores st
LEFT JOIN orders o ON o.store_id = st.store_id AND o.status = 'completed' AND o.deleted_at IS NULL
  AND o.ordered_at >= '2026-05-01' AND o.ordered_at < '2026-06-01'
  AND NOT EXISTS (SELECT 1 FROM customers c WHERE c.customer_id = o.customer_id AND (c.is_test = 1 OR c.deleted_at IS NOT NULL))
LEFT JOIN order_items oi ON oi.order_id = o.order_id
GROUP BY st.store_id ORDER BY n DESC;`)),
    1,
  );
  // q06 rounded with printf (text), the month ends from a recursive CTE, columns swapped.
  assert.equal(
    await score(id, 'q06', fenced(`WITH RECURSIVE m(first) AS (SELECT '2026-02-01' UNION ALL SELECT date(first, '+1 month') FROM m WHERE first < '2026-07-01')
SELECT printf('%.2f', SUM(CASE plan WHEN 'annual' THEN price_gbp / 12 ELSE price_gbp END)), date(m.first, '-1 day') AS d
FROM m JOIN subscriptions s ON s.started_on <= date(m.first, '-1 day') AND COALESCE(s.ended_on, '9999-12-31') > date(m.first, '-1 day')
JOIN customers c USING (customer_id)
WHERE c.is_test = 0 AND c.deleted_at IS NULL
GROUP BY d ORDER BY d`)),
    1,
  );
  // Two SQL blocks: only the last one counts (a hedge is not two chances).
  const q = expectedOf(id, 'q01').reference;
  assert.equal(await score(id, 'q01', `${fenced('SELECT 0')}\n\nActually:\n${fenced(q)}`), 1);
  assert.equal(await score(id, 'q01', `${fenced(q)}\n\nOr maybe:\n${fenced('SELECT 0')}`), 0);
});

test('a specific careless query: converting by the shop currency is wrong only where the website sold in euros', async () => {
  const slip = shop.QUESTIONS.find((q) => q.id === 'q05')!.careless.find((c) => /store currency/.test(c.why))!;
  const s = await score('work.sql-questions', 'q05', fenced(slip.sql));
  assert.ok(s > 0 && s < 1, `store-currency slip scored ${s}`);
});

test('a hard-coded answer earns (almost) nothing beyond the one database it was copied from', async () => {
  // The generator guarantees the hidden copies share at most a fifth of their rows with the live answer (mean 0.1).
  for (const id of IDS) {
    for (const c of def(id).cases) {
      const e = c.expected as SqlExpected;
      const [live] = await referenceResults(spec(id), e.reference);
      const lit = (v: unknown) => (v === null ? 'NULL' : typeof v === 'number' ? String(v) : `'${String(v).replace(/'/g, "''")}'`);
      const sql = live!.rows.map((r) => `SELECT ${r.map(lit).join(', ')}`).join('\nUNION ALL\n');
      const s = await score(id, c.id, fenced(sql));
      assert.ok(s <= 0.325 + 1e-9, `${id}/${c.id}: hard-coded live answer scored ${s}`);
    }
  }
  assert.equal(await score('work.sql-questions', 'q01', fenced('SELECT 42')), 0);
});

test('do-nothing answers score 0: SELECT *, the Random Baseline, filler, an empty reply, prose without a query', async () => {
  for (const id of IDS) {
    const d = def(id);
    const firstTable = /CREATE TABLE\s+(\w+)/.exec(spec(id).schema)![1]!;
    const adapter = createMockAdapter({ contestant: { id: 'random-baseline', model: 'random' }, provider: {}, apiKey: undefined } as never);
    for (const c of d.cases) {
      assert.equal(await score(id, c.id, fenced(`SELECT * FROM ${firstTable}`)), 0, `${id}/${c.id} SELECT *`);
      const r = renderCase(d, c);
      const reply = await adapter.complete({ system: r.system, messages: [{ role: 'user', content: r.turns[0]! }], maxOutputTokens: 100 } as never);
      assert.match(reply.text, /```sql/);
      assert.equal(await score(id, c.id, reply.text), 0, `${id}/${c.id} baseline`);
      assert.equal(await score(id, c.id, 'the model result answer benchmark data store product price.\nFINAL ANSWER: 42'), 0);
      assert.equal(await score(id, c.id, ''), 0);
    }
    assert.equal(await score(id, d.cases[0]!.id, fenced('SELECT * FROM orders')), 0);
  }
});

test('unsafe statements are refused before anything runs', () => {
  const refused = [
    'SELECT 1; DROP TABLE orders',
    'DELETE FROM orders',
    'WITH x AS (SELECT 1) DELETE FROM orders',
    'INSERT INTO stores VALUES (9, 1, 2, 3, 4)',
    "UPDATE orders SET status = 'completed'",
    'REPLACE INTO stores VALUES (1, 2, 3, 4, 5)',
    "ATTACH DATABASE '/etc/passwd' AS x",
    "SELECT * FROM orders; ATTACH ':memory:' AS y",
    'PRAGMA query_only = OFF',
    "SELECT load_extension('evil')",
    'SELECT length(randomblob(1000000000))',
    'CREATE TABLE t (a)',
    'BEGIN; SELECT 1',
    'EXPLAIN SELECT 1',
    'VACUUM',
    '',
  ];
  for (const sql of refused) assert.ok(unsafeReason(sql), `should refuse: ${sql}`);
  const allowed = [
    "SELECT ';' AS semi, 'DROP TABLE x' AS text FROM stores",
    'SELECT 1 /* ; DELETE */ -- ; INSERT\n',
    'WITH t AS (SELECT * FROM orders) SELECT COUNT(*) FROM t;',
    '(SELECT 1)',
    'SELECT deleted_at, last_update_note FROM orders',
    'SELECT "update" FROM (SELECT 1 AS "update")',
  ];
  for (const sql of allowed) assert.equal(unsafeReason(sql), null, `should allow: ${sql}`);
});

test('a refused or runaway query scores 0 without harming anything, and huge results are cut off', async () => {
  const id = 'work.sql-questions';
  assert.equal(await score(id, 'q01', fenced('SELECT 1; DROP TABLE customers')), 0);
  assert.equal(await score(id, 'q01', fenced(expectedOf(id, 'q01').reference)), 1, 'the database is rebuilt for every run');
  const quick: SqlScorerSpec = { ...spec(id), timeoutMs: 2500 };
  const t0 = Date.now();
  const loop = await scoreSql(quick, expectedOf(id, 'q01'), fenced('WITH RECURSIVE n(x) AS (SELECT 1 UNION ALL SELECT x + 1 FROM n) SELECT COUNT(*) FROM n'));
  assert.equal(loop.score, 0);
  assert.ok(Date.now() - t0 < 10_000, 'the runaway query was stopped');
  assert.match(JSON.stringify(loop.detail.items), /time limit/);
  const many = await scoreSql(spec(id), expectedOf(id, 'q01'), fenced('WITH RECURSIVE n(x) AS (SELECT 1 UNION ALL SELECT x + 1 FROM n WHERE x < 20000) SELECT x FROM n'));
  assert.equal(many.score, 0);
  assert.match(JSON.stringify(many.detail.items), /more than 5000 rows/);
  const err = await scoreSql(spec(id), expectedOf(id, 'q01'), fenced('SELECT nope FROM customers'));
  assert.equal(err.score, 0);
  assert.match(err.summary, /no such column/);
});

test('result comparison: NULL-aware, tolerant numbers, columns in any order, duplicates count, order only when asked', () => {
  const rs = (rows: unknown[][], cols = rows[0]?.length ?? 1): ResultSet => ({ columns: Array.from({ length: cols }, (_, i) => `c${i}`), rows: rows as ResultSet['rows'] });
  const ref = rs([['Leeds', 10.5, null], ['Bristol', 3, 'x']]);
  const cmp = (got: ResultSet, ordered = false, tolerance = 0.01) => compareResults(ref, got, { ordered, tolerance }).score;
  assert.equal(cmp(rs([['Bristol', 3, 'x'], ['Leeds', 10.5, null]])), 1, 'rows in another order');
  assert.equal(cmp(rs([[null, 'Leeds', 10.504], ['x', 'Bristol', '3.00']])), 1, 'columns permuted, numbers within tolerance, numeric text');
  assert.equal(cmp(rs([['Leeds', 10.5, 0], ['Bristol', 3, 'x']])), 0.5, 'NULL is not 0');
  assert.equal(cmp(rs([['Leeds', 10.5, ''], ['Bristol', 3, 'x']])), 0.5, "NULL is not ''");
  assert.equal(cmp(rs([['Leeds', 10.52, null], ['Bristol', 3, 'x']])), 0.5, 'outside the tolerance');
  assert.equal(cmp(rs([['Leeds', 10.5, null]])), 0.5, 'a missing row');
  assert.equal(cmp(rs([['Leeds', 10.5, null], ['Bristol', 3, 'x'], ['Bristol', 3, 'x']])), 2 / 3, 'an extra duplicate row costs');
  assert.equal(cmp(rs([['Leeds', 10.5]])), 0, 'wrong column count');
  assert.equal(cmp(rs([['Bristol', 3, 'x'], ['Leeds', 10.5, null]]), true), 0.5, 'wrong order when an order was asked');
  assert.equal(cmp(rs([['Leeds', 10.5, null], ['Bristol', 3, 'x']]), true), 1);
  assert.equal(compareResults(rs([[5]]), rs([[5.0]]), { ordered: false, tolerance: 0 }).score, 1, 'integer equals real');
  assert.equal(compareResults(rs([['a']]), rs([[' a ']]), { ordered: false, tolerance: 0 }).score, 1, 'text trimmed');
  assert.equal(compareResults(rs([['Leeds']]), rs([['leeds']]), { ordered: false, tolerance: 0 }).score, 0, 'text otherwise exact');
});

test('query extraction: the last SQL block, else an unfenced query, cut before prose', () => {
  assert.deepEqual(extractSqlCandidates('```sql\nSELECT 1\n```\ntext\n```sql\nSELECT 2;\n```'), ['SELECT 2;']);
  assert.deepEqual(extractSqlCandidates('```\nWITH a AS (SELECT 1) SELECT * FROM a\n```'), ['WITH a AS (SELECT 1) SELECT * FROM a']);
  const c = extractSqlCandidates('I would run:\n\nSELECT a\nFROM t\n\nThis returns the answer.');
  assert.ok(c.includes('SELECT a\nFROM t'));
  assert.deepEqual(extractSqlCandidates('No query here.'), []);
});

test('scoring is deterministic', async () => {
  const careless = shop.QUESTIONS.find((q) => q.id === 'q10')!.careless[0]!.sql;
  const a = await score('work.sql-questions', 'q10', fenced(careless));
  const b = await score('work.sql-questions', 'q10', fenced(careless));
  assert.equal(a, b);
});

test('every careless query loses marks; expected spread: do-nothing 0, careless in between, careful 100 (real scorer)', async (t) => {
  for (const [id, qs] of [['work.sql-questions', shop.QUESTIONS], ['work.sql-hard', warehouse.QUESTIONS]] as const) {
    let careless = 0;
    let carelessN = 0;
    let careful = 0;
    const byLevel: Record<string, number[]> = { easy: [], medium: [], hard: [] };
    for (const q of qs) {
      careful += await score(id, q.id, fenced(q.reference));
      let s = 0;
      for (const c of q.careless) {
        const one = await score(id, q.id, fenced(c.sql));
        assert.ok(one < 1, `${id}/${q.id} careless "${c.why}" scored ${one}`);
        s += one;
      }
      careless += s;
      carelessN += q.careless.length;
      byLevel[q.difficulty]!.push(s / q.careless.length);
    }
    const mean = careless / carelessN;
    const lv = Object.fromEntries(Object.entries(byLevel).map(([k, v]) => [k, Math.round((v.reduce((a, b) => a + b, 0) / v.length) * 100)]));
    // Profiles: "strong but error-prone" = right on easy and medium, one realistic slip on each hard question;
    // "mid" = right on easy, one slip on each medium and hard question; "careless" = a slip everywhere.
    const per = qs.map((q, i) => ({ d: q.difficulty, slip: byLevel[q.difficulty]![qs.filter((x, j) => j < i && x.difficulty === q.difficulty).length]! }));
    const profile = (right: string[]) => Math.round((per.reduce((a, x) => a + (right.includes(x.d) ? 1 : x.slip), 0) / per.length) * 100);
    t.diagnostic(`${id}: careful ${Math.round((careful / qs.length) * 100)}%, strong-but-error-prone ${profile(['easy', 'medium'])}%, mid ${profile(['easy'])}%, careless ${profile([])}% (slip scores: easy ${lv.easy}%, medium ${lv.medium}%, hard ${lv.hard}%), do-nothing 0%`);
    assert.equal(careful, qs.length);
    assert.ok(mean > 0.1 && mean < 0.8, `${id}: careless mean ${mean}`);
  }
});

test('the test hash covers the sql scorer code', async () => {
  const { computeTestHash } = await import('../src/core/registry.ts');
  const { contentHash } = await import('../src/core/hash.ts');
  const d = def('work.sql-questions');
  assert.notEqual(computeTestHash(d), contentHash({ def: d }));
  assert.equal(computeTestHash(d), getTest('work.sql-questions')!.hash);
});
