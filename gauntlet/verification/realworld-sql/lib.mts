// Shared helpers for the "Ask the Database" generators: seeded data, in-process SQLite checks and prompt rendering.
// Everything here is deterministic: the same seed always produces the same rows, so the test files regenerate
// byte-identically.
import { DatabaseSync } from 'node:sqlite';
import { createRng } from '../../src/core/rng.ts';
import type { Rng } from '../../src/core/types.ts';
import { compareResults, type ResultSet } from '../../src/scoring/sql.ts';

export { createRng };
export type { Rng, ResultSet };

export type Cell = string | number | null;
export interface Table {
  columns: string[];
  rows: Cell[][];
}
export type Tables = Record<string, Table>;

export interface Question {
  id: string;
  difficulty: 'easy' | 'medium' | 'hard';
  /** The question as the manager asks it, plus the exact columns wanted. */
  text: string;
  ordered: boolean;
  tolerance?: number;
  reference: string;
  /** An independent JavaScript computation of the same answer from the raw rows (a second solver). */
  solve: (t: Tables) => Cell[][];
  /** Plausible careless queries (each one a real-world mistake); each must score below 1. */
  careless: Array<{ why: string; sql: string }>;
  notes: string;
}

// ─────────────────────────────── Dates ───────────────────────────────

const pad = (n: number, w = 2) => String(n).padStart(w, '0');

/** Minutes since 2000-01-01 00:00 (naive calendar arithmetic in UTC, which has no clock changes). */
export function toMin(ts: string): number {
  const [d, t = '00:00:00'] = ts.split(' ');
  const [y, m, day] = d!.split('-').map(Number);
  const [hh, mm, ss = 0] = t.split(':').map(Number);
  return Math.round((Date.UTC(y!, m! - 1, day!, hh!, mm!, ss) - Date.UTC(2000, 0, 1)) / 60000);
}

export function fromMin(min: number, withSeconds = 0): string {
  const d = new Date(Date.UTC(2000, 0, 1) + min * 60000 + withSeconds * 1000);
  return `${d.getUTCFullYear()}-${pad(d.getUTCMonth() + 1)}-${pad(d.getUTCDate())} ${pad(d.getUTCHours())}:${pad(d.getUTCMinutes())}:${pad(d.getUTCSeconds())}`;
}

export function dayOf(ts: string): string {
  return ts.slice(0, 10);
}

export function addDays(day: string, n: number): string {
  return fromMin(toMin(`${day} 00:00:00`) + n * 1440).slice(0, 10);
}

export function daysBetween(a: string, b: string): number {
  return Math.round((toMin(`${b} 00:00:00`) - toMin(`${a} 00:00:00`)) / 1440);
}

/** A random timestamp in [from, to) at whole seconds, with shop-hours weighting when `hours` is given. */
export function randomTs(rng: Rng, fromDay: string, toDay: string, hours: [number, number] = [0, 24]): string {
  const days = daysBetween(fromDay, toDay);
  const day = addDays(fromDay, rng.int(0, days - 1));
  const minute = rng.int(hours[0] * 60, hours[1] * 60 - 1);
  return fromMin(toMin(`${day} 00:00:00`) + minute, rng.int(0, 59));
}

export const money = (x: number) => Math.round(x * 100) / 100;

// ─────────────────────────────── Names ───────────────────────────────

export const FIRST = [
  'Amara', 'Ravi', 'Siobhan', 'Tomasz', 'Mei', 'Kwame', 'Fatima', 'Oliver', 'Niamh', 'Hamza', 'Ewan', 'Priya', 'Chloe', 'Dmitri',
  'Aisha', 'Callum', 'Yusuf', 'Isla', 'Mateo', 'Zainab', 'Rhys', 'Ana', 'Kofi', 'Leah', 'Arjun', 'Freya', 'Bilal', 'Grace',
  'Luca', 'Ngozi', 'Finlay', 'Sana', 'Jakub', 'Imogen', 'Tariq', 'Erin', 'Hiro', 'Maryam', 'Declan', 'Lucia', 'Femi', 'Ines',
  'Rowan', 'Anjali', 'Owen', 'Yara', 'Kieran', 'Sofia', 'Joon', 'Bethan',
];
export const LAST = [
  'Okafor', 'Patel', 'Byrne', 'Nowak', 'Chen', 'Mensah', 'Rahman', 'Hughes', 'Murphy', 'Iqbal', 'Fraser', 'Shah', 'Davies',
  'Petrov', 'Ali', 'MacLeod', 'Kaya', 'Campbell', 'Rossi', 'Bello', 'Evans', 'Santos', 'Asante', 'Cohen', 'Nair', 'Ross',
  'Hussain', 'Wright', 'Romano', 'Eze', 'Douglas', 'Begum', 'Kowalski', 'Hart', 'Malik', 'Doyle', 'Sato', 'Karimi', 'Quinn',
  'Moreno', 'Adeyemi', 'Lindqvist', 'Oduya', 'Brennan', 'Varga', 'Kim', 'Ahmed', 'Price', 'Costa', 'Gallagher',
];

/** `n` distinct invented full names. */
export function names(rng: Rng, n: number): string[] {
  const out = new Set<string>();
  while (out.size < n) out.add(`${rng.pick(FIRST)} ${rng.pick(LAST)}`);
  return [...out];
}

// ─────────────────────────────── SQLite (trusted, in-process) ───────────────────────────────

export function openDb(schema: string, tables: Tables, opts: { reverseUnordered?: boolean } = {}): DatabaseSync {
  const db = new DatabaseSync(':memory:');
  db.exec(schema);
  db.exec('BEGIN');
  for (const [name, t] of Object.entries(tables)) {
    if (!t.rows.length) continue;
    const ins = db.prepare(`INSERT INTO "${name}" (${t.columns.map((c) => `"${c}"`).join(', ')}) VALUES (${t.columns.map(() => '?').join(', ')})`);
    for (const r of t.rows) ins.run(...(r as Array<string | number | null>));
  }
  db.exec('COMMIT');
  if (opts.reverseUnordered) db.exec('PRAGMA reverse_unordered_selects = ON');
  return db;
}

export function query(db: DatabaseSync, sql: string): ResultSet {
  const st = db.prepare(sql);
  st.setReturnArrays(true);
  st.setReadBigInts(true);
  const columns = st.columns().map((c) => String(c.name));
  const rows = (st.all() as unknown as unknown[][]).map((r) => r.map((v) => (typeof v === 'bigint' ? Number(v) : (v as Cell))));
  return { columns, rows };
}

export function same(a: ResultSet, b: ResultSet, ordered: boolean, tolerance: number): boolean {
  return compareResults(a, b, { ordered, tolerance }).score === 1;
}

export function score(ref: ResultSet, got: ResultSet, ordered: boolean, tolerance: number): number {
  return compareResults(ref, got, { ordered, tolerance }).score;
}

// ─────────────────────────────── Prompt rendering ───────────────────────────────

function show(v: Cell): string {
  if (v === null) return 'NULL';
  if (typeof v === 'number') return String(v);
  return v;
}

/** A small text table of the chosen rows (header + rows), columns padded. */
export function sampleTable(name: string, t: Table, rows: Cell[][]): string {
  const cells = [t.columns, ...rows.map((r) => r.map(show))];
  const widths = t.columns.map((_, i) => Math.max(...cells.map((r) => String(r[i]).length)));
  const line = (r: Array<string | Cell>) => r.map((v, i) => String(v).padEnd(widths[i]!)).join(' | ').trimEnd();
  return [`${name} (${t.rows.length} rows in the live database; ${rows.length} shown)`, line(t.columns), line(widths.map((w) => '-'.repeat(w))), ...rows.map((r) => line(r.map(show)))].join('\n');
}

/** Rows of `t` whose first column is in `ids` (keeps the table's order). */
export function rowsById(t: Table, ids: Array<string | number>): Cell[][] {
  const want = new Set(ids.map(String));
  return t.rows.filter((r) => want.has(String(r[0])));
}

/** Check that a test JSON object serialises the way the committed file does (2-space JSON + newline). */
export function serialise(obj: unknown): string {
  return `${JSON.stringify(obj, null, 2)}\n`;
}

/** A compact JSON form for the big row arrays: one table row per line keeps the file readable and diffable. */
export function serialiseTest(obj: Record<string, unknown>): string {
  const text = JSON.stringify(obj, (_k, v) => v, 2);
  // Collapse every innermost array of scalars (a table row or a column list) onto one line.
  return `${text.replace(/\[\n\s+((?:(?:"(?:[^"\\]|\\.)*"|-?\d+(?:\.\d+)?(?:e[-+]?\d+)?|null|true|false),?\n\s+)*(?:"(?:[^"\\]|\\.)*"|-?\d+(?:\.\d+)?(?:e[-+]?\d+)?|null|true|false))\n\s+\]/g, (_m, inner: string) => `[${inner.split(/,\n\s+/).join(', ')}]`)}\n`;
}

export function tok(s: string): number {
  return Math.ceil(s.length / 3.6) + 20;
}
