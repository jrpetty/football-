/**
 * Ask the Database (work.sql-questions, work.sql-hard, work.sql-ladder): the colleague's question, the tables and
 * their joins from the schema, the model's query, the correct result table on the live database (worked out from
 * the reference query when the data was built: sqlResults.json) and, where recorded, the model's own result rows,
 * plus the scorer's verdict database by database. Pure; tested in test/customers-data-docs.test.ts.
 */
import type { CaseVisualInput } from '../../../../../src/presenter/visuals/common.ts';
import { extractSqlCandidatesBrowser } from './sqlExtract.ts';

export type Cell = string | number | null;
export interface ResultTable {
  columns: string[];
  rows: Cell[][];
  total: number;
}

export interface SqlTable {
  name: string;
  columns: Array<{ name: string; note: string; ref: string | null }>;
}

export interface SqlDoc {
  company: string;
  asker: string;
  question: string;
  detail: string;
  tables: SqlTable[];
  reference: string;
  ordered: boolean;
  correct: ResultTable | null;
  /** The slips the case notes list ("counts lines instead of units"). */
  slips: string[];
}

export function parseSchema(preamble: string): SqlTable[] {
  const out: SqlTable[] = [];
  for (const m of preamble.matchAll(/CREATE TABLE (\w+) \(([\s\S]*?)\n\);/g)) {
    const columns: SqlTable['columns'] = [];
    for (const line of m[2]!.split('\n')) {
      const c = line.match(/^\s+(\w+)\s+(?:INTEGER|TEXT|REAL|NUMERIC)\b/);
      if (c && !/^(PRIMARY|UNIQUE|FOREIGN)$/i.test(c[1]!)) columns.push({ name: c[1]!, ref: line.match(/REFERENCES (\w+)\s*\(/)?.[1] ?? null, note: (line.split('--')[1] ?? '').trim() });
    }
    out.push({ name: m[1]!, columns });
  }
  return out;
}

/** `results`: sqlResults.json (test id → case id → the correct live result). */
export function parseSql(input: CaseVisualInput, results: unknown = {}): SqlDoc | null {
  const text = input.turns.join('\n\n');
  const q = text.match(/== QUESTION ==\s*\nFrom ([^:]+):\s*"([^"]+)"\s*\n+([\s\S]*?)$/);
  if (!q) return null;
  const e = input.expected as { reference?: string; ordered?: boolean } | string | undefined;
  const reference = typeof e === 'string' ? e : (e?.reference ?? '');
  const correct = ((results as unknown as Record<string, Record<string, { live: ResultTable; ordered: boolean }>>)[input.testId]?.[input.caseId]?.live ?? null) as ResultTable | null;
  const slips = (input.notes?.match(/Careless queries and their mean scores:\s*([^]*?)\.?$/)?.[1] ?? '').split(/;\s*/).map((s) => s.replace(/\s*\([\d.]+\)\s*$/, '').trim()).filter(Boolean);
  return {
    company: (text.match(/^You (?:are helping the team at|are an analyst at) ([^,]+),/m) ?? [])[1] ?? '',
    asker: q[1]!.trim(),
    question: q[2]!.trim(),
    detail: q[3]!.split(/\n== /)[0]!.trim(),
    tables: parseSchema(text),
    reference,
    ordered: typeof e === 'object' && !!e?.ordered,
    correct,
    slips,
  };
}

/** The tables a query reads (FROM / JOIN names that are tables of the schema). */
export function tablesIn(sql: string, tables: SqlTable[]): string[] {
  const names = new Set(tables.map((t) => t.name));
  const out = new Set<string>();
  for (const m of sql.matchAll(/\b(?:FROM|JOIN)\s+(\w+)/gi)) if (names.has(m[1]!)) out.add(m[1]!);
  return [...out];
}

export interface SqlAnswer {
  sql: string | null;
  dbs: Array<{ label: string; passed: boolean; detail: string }>;
  right: number;
  rows: ResultTable | null;
}

export function sqlAnswer(input: CaseVisualInput): SqlAnswer | null {
  const extracted = typeof input.detail.extracted === 'string' ? input.detail.extracted : null;
  const sql = extracted ?? extractSqlCandidatesBrowser(input.replies[input.replies.length - 1] ?? '')[0] ?? null;
  const items = (input.detail.items ?? []) as Array<{ label: string; passed: boolean; detail?: string }>;
  if (!sql && !items.length) return null;
  const live = input.detail.liveRows as ResultTable | undefined;
  return { sql, dbs: items.map((i) => ({ label: i.label, passed: i.passed, detail: i.detail ?? '' })), right: items.filter((i) => i.passed).length, rows: live && Array.isArray(live.rows) ? live : null };
}

const near = (a: Cell, b: Cell) => {
  if (a === null || b === null) return a === b;
  const x = typeof a === 'number' ? a : Number(a);
  const y = typeof b === 'number' ? b : Number(b);
  if (typeof a === 'number' || typeof b === 'number') return Number.isFinite(x) && Number.isFinite(y) && Math.abs(x - y) <= 0.01 + 1e-9;
  return String(a).trim() === String(b).trim();
};

/** Which model rows match a correct row (each correct row used once), and which correct rows nobody matched. */
export function matchRows(correct: Cell[][], got: Cell[][]): { gotOk: boolean[]; missing: number[] } {
  const used = new Set<number>();
  const gotOk = got.map((r) => {
    const i = correct.findIndex((c, k) => !used.has(k) && c.length === r.length && c.every((v, j) => near(v, r[j]!)));
    if (i >= 0) used.add(i);
    return i >= 0;
  });
  return { gotOk, missing: correct.map((_, k) => k).filter((k) => !used.has(k)) };
}
