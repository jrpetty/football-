// SQL sandbox worker (src/scoring/sql.ts). Runs in its own Node process under the permission model (read access to
// this file only, no child processes, no workers, an empty environment), killed by the parent after a hard time limit.
//
// Input (stdin, one JSON object): { schema, tables, reference, candidates, maxRows, maxCellChars }
//   schema      trusted DDL (CREATE TABLE …) from the test file
//   tables      { name: { columns: [...], rows: [[...], …] } } from the test file
//   reference   the trusted reference query (null to skip)
//   candidates  the contestant's query, possibly in several cut-offs of an unfenced reply (the first that compiles runs)
// Output (stdout, one JSON line per step):
//   {"ref": {columns, rows} | {error}}   then   {"model": {columns, rows, sql} | {error}}
// The database is an in-memory copy built from the test data. Before the contestant's query runs, the connection is
// switched to query_only (no writes), extensions stay disabled (node:sqlite's default), and at most maxRows rows are
// read: a query that returns more is stopped and reported as too many rows.
import { DatabaseSync } from 'node:sqlite';

const MAX_OUT = 4_000_000;

function readStdin() {
  return new Promise((resolve, reject) => {
    let data = '';
    process.stdin.setEncoding('utf8');
    process.stdin.on('data', (d) => {
      data += d;
    });
    process.stdin.on('end', () => resolve(data));
    process.stdin.on('error', reject);
  });
}

function cell(v, maxCellChars) {
  if (v === null || v === undefined) return null;
  if (typeof v === 'bigint') return Number(v);
  if (typeof v === 'number') return Number.isFinite(v) ? v : String(v);
  if (typeof v === 'string') return v.length > maxCellChars ? v.slice(0, maxCellChars) : v;
  if (v instanceof Uint8Array) return `x'${Buffer.from(v.subarray(0, 64)).toString('hex')}'`;
  return String(v);
}

function run(db, sql, maxRows, maxCellChars) {
  const stmt = db.prepare(sql);
  stmt.setReturnArrays(true);
  stmt.setReadBigInts(true);
  const columns = stmt.columns().map((c) => String(c.name));
  if (columns.length === 0) return { error: 'the statement returns no columns' };
  const rows = [];
  let chars = 0;
  for (const r of stmt.iterate()) {
    if (rows.length >= maxRows) return { error: `more than ${maxRows} rows returned`, tooMany: true, columns };
    const row = r.map((v) => cell(v, maxCellChars));
    chars += JSON.stringify(row).length;
    if (chars > MAX_OUT) return { error: 'the result is too large', tooMany: true, columns };
    rows.push(row);
  }
  return { columns, rows };
}

function send(obj) {
  process.stdout.write(`${JSON.stringify(obj)}\n`);
}

const input = JSON.parse(await readStdin());
const db = new DatabaseSync(':memory:');
db.exec(input.schema);
db.exec('BEGIN');
for (const [name, t] of Object.entries(input.tables)) {
  if (!t.rows.length) continue;
  const cols = t.columns.map((c) => `"${c}"`).join(', ');
  const marks = t.columns.map(() => '?').join(', ');
  const ins = db.prepare(`INSERT INTO "${name}" (${cols}) VALUES (${marks})`);
  for (const row of t.rows) ins.run(...row);
}
db.exec('COMMIT');

const maxRows = input.maxRows ?? 5000;
const maxCellChars = input.maxCellChars ?? 500;
if (input.reference) {
  try {
    send({ ref: run(db, input.reference, 100_000, 100_000) });
  } catch (e) {
    send({ ref: { error: String(e?.message ?? e) } });
  }
}

db.exec('PRAGMA query_only = ON');
let firstError = null;
let done = false;
for (const sql of input.candidates ?? []) {
  try {
    const out = run(db, sql, maxRows, maxCellChars);
    send({ model: { ...out, sql } });
    done = true;
    break;
  } catch (e) {
    firstError ??= String(e?.message ?? e);
  }
}
if (!done) send({ model: { error: firstError ?? 'no query to run' } });
db.close();
