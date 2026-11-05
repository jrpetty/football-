/**
 * What Changed in the Contract? (work.contract-redline) and its ladder (work.contract-ladder): the two versions
 * read clause by clause from the prompt, a word-level redline between them, and the model's list of changes against
 * the key (found, wrong detail, missed, invented). Pure; tested in test/customers-data-docs.test.ts.
 */
import type { CaseVisualInput } from '../../../../../src/presenter/visuals/common.ts';
import { byId, normSetId, pick, replyJson, sameValue } from './kit.ts';

export interface Clause {
  no: string;
  text: string;
  section: string;
}

export interface Change {
  type: string;
  old: unknown;
  new: unknown;
}

export interface ContractDoc {
  title1: string;
  title2: string;
  v1: Clause[];
  v2: Clause[];
  /** Ladder: the customer's response and the supplier's note, by clause number. */
  talk: Record<string, string[]>;
  ladder: boolean;
  key: Record<string, Change> | null;
  /** Clause numbers of the key in their original spelling, by normalised id. */
  keyNo: Record<string, string>;
}

function clauses(block: string): { title: string; list: Clause[] } {
  const lines = block.split('\n');
  const title = lines.find((l) => l.trim())?.trim() ?? '';
  const list: Clause[] = [];
  let section = '';
  for (const l of lines) {
    const sec = l.match(/^(\d+\.|SCHEDULE \d+)\s*[—-]?\s*(.+)$/);
    const c = l.match(/^((?:S\d+|\d+)\.\d+)\s+(.+)$/);
    if (c) list.push({ no: c[1]!, text: c[2]!.trim(), section });
    else if (sec && !/^\d+\.\d/.test(l)) section = l.trim();
  }
  return { title, list };
}

export function parseContract(prompt: string, expected?: unknown): ContractDoc | null {
  const b1 = prompt.match(/<<< VERSION 1\n([\s\S]*?)\n>>>/);
  const b2 = prompt.match(/<<< VERSION 2\n([\s\S]*?)\n>>>/);
  if (!b1 || !b2) return null;
  const c1 = clauses(b1[1]!);
  const c2 = clauses(b2[1]!);
  if (!c1.list.length || !c2.list.length) return null;
  const talk: Record<string, string[]> = {};
  const rest = prompt.slice(prompt.indexOf(b2[0]) + b2[0].length);
  const rItems = new Map<string, string>();
  for (const m of rest.matchAll(/^(R\d+)\.\s+(.+)$/gm)) {
    rItems.set(m[1]!, m[2]!);
    const cl = m[2]!.match(/clause (\S+?):/i);
    if (cl) (talk[normSetId(cl[1]!)] ??= []).push(`Customer: ${m[2]!.replace(/^.*?:\s*/, '')}`);
  }
  for (const m of rest.matchAll(/^(N\d+)\.\s+(.+)$/gm)) {
    const r = m[2]!.match(/^(R\d+):/);
    const cl = r ? rItems.get(r[1]!)?.match(/clause (\S+?):/i)?.[1] : m[2]!.match(/clause (\S+?)\.?$/i)?.[1];
    if (cl) (talk[normSetId(cl)] ??= []).push(`Supplier: ${m[2]!.replace(/^R\d+:\s*/, '')}`);
    for (const x of m[2]!.matchAll(/clauses? ([\dS.,\s]+?)(?: only| stand|$)/gi)) for (const n of x[1]!.split(/,\s*/)) if (n.trim()) (talk[normSetId(n.trim())] ??= []).push(`Supplier: ${m[2]!}`);
  }
  let key: ContractDoc['key'] = null;
  const keyNo: Record<string, string> = {};
  const ch = (expected as { changes?: Record<string, Change> } | undefined)?.changes;
  if (ch) {
    key = {};
    for (const [k, v] of Object.entries(ch)) {
      key[normSetId(k)] = v;
      keyNo[normSetId(k)] = k;
    }
  }
  return { title1: c1.title, title2: c2.title, v1: c1.list, v2: c2.list, talk, ladder: /AGREED version/.test(prompt), key, keyNo };
}

// ───────────────────────────── redline ─────────────────────────────

export interface Piece {
  t: string;
  op: 'same' | 'del' | 'ins';
}

const tokens = (s: string) => s.match(/\s+|[^\s]+/g) ?? [];

/** Word-level tracked changes from a to b (longest common subsequence of words). */
export function redline(a: string, b: string): Piece[] {
  const x = tokens(a);
  const y = tokens(b);
  const n = x.length;
  const m = y.length;
  if (n * m > 250_000) return [{ t: a, op: 'del' }, { t: b, op: 'ins' }];
  const L: number[][] = Array.from({ length: n + 1 }, () => new Array<number>(m + 1).fill(0));
  for (let i = n - 1; i >= 0; i--) for (let j = m - 1; j >= 0; j--) L[i]![j] = x[i] === y[j] ? L[i + 1]![j + 1]! + 1 : Math.max(L[i + 1]![j]!, L[i]![j + 1]!);
  const out: Piece[] = [];
  const push = (t: string, op: Piece['op']) => {
    const last = out[out.length - 1];
    if (last && last.op === op) last.t += t;
    else out.push({ t, op });
  };
  let i = 0;
  let j = 0;
  while (i < n && j < m) {
    if (x[i] === y[j]) {
      push(x[i]!, 'same');
      i++;
      j++;
    } else if (L[i + 1]![j]! >= L[i]![j + 1]!) push(x[i++]!, 'del');
    else push(y[j++]!, 'ins');
  }
  while (i < n) push(x[i++]!, 'del');
  while (j < m) push(y[j++]!, 'ins');
  // Whitespace-only changes read as unchanged.
  return out.map((p): Piece => (p.op !== 'same' && !p.t.trim() ? { ...p, op: 'same' } : p)).filter((p, k, arr) => !(p.op === 'del' && !p.t.trim() && arr[k + 1]?.op === 'ins'));
}

function words(s: string): Set<string> {
  return new Set(s.toLowerCase().match(/[a-z]{3,}/g) ?? []);
}

function similarity(a: string, b: string): number {
  const A = words(a);
  const B = words(b);
  let inter = 0;
  for (const w of A) if (B.has(w)) inter++;
  return inter / Math.max(1, A.size + B.size - inter);
}

/** The version-1 clause a version-2 clause came from (same number if alike, else the most alike one). */
export function sourceOf(doc: ContractDoc, c2: Clause): Clause | null {
  const same = doc.v1.find((c) => c.no === c2.no);
  if (same && similarity(same.text, c2.text) >= 0.45) return same;
  let best: { c: Clause; s: number } | null = null;
  for (const c of doc.v1) {
    const s = similarity(c.text, c2.text);
    if (!best || s > best.s) best = { c, s };
  }
  return best && best.s >= 0.45 ? best.c : null;
}

export interface ClauseView {
  no: string;
  /** Version 2's clause (null: deleted in version 2). */
  v2: Clause | null;
  /** Its version-1 source (null: added in version 2). */
  v1: Clause | null;
  pieces: Piece[];
  /** The words differ at all (substantive or not). */
  changed: boolean;
}

export function clauseView(doc: ContractDoc, no: string): ClauseView | null {
  const id = normSetId(no);
  const v2 = doc.v2.find((c) => normSetId(c.no) === id) ?? null;
  if (v2) {
    const v1 = sourceOf(doc, v2);
    return { no: v2.no, v2, v1, pieces: v1 ? redline(v1.text, v2.text) : [{ t: v2.text, op: 'ins' }], changed: !v1 || v1.text !== v2.text };
  }
  const v1 = doc.v1.find((c) => normSetId(c.no) === id) ?? null;
  if (!v1) return null;
  return { no: v1.no, v2: null, v1, pieces: [{ t: v1.text, op: 'del' }], changed: true };
}

/** Every version-2 clause whose words differ from version 1 (the redline a lawyer would print), plus deleted ones. */
export function changedClauses(doc: ContractDoc): ClauseView[] {
  const out: ClauseView[] = [];
  const used = new Set<string>();
  for (const c of doc.v2) {
    const v = clauseView(doc, c.no)!;
    if (v.v1) used.add(v.v1.no);
    if (v.changed) out.push(v);
  }
  for (const c of doc.v1) if (!used.has(c.no) && !doc.v2.some((x) => x.text === c.text)) out.push({ no: c.no, v2: null, v1: c, pieces: [{ t: c.text, op: 'del' }], changed: true });
  return out;
}

// ───────────────────────────── the model's list ─────────────────────────────

export interface ChangeMark {
  id: string;
  no: string;
  model: Change | null;
  key: Change | null;
  state: 'right' | 'wrong' | 'missed' | 'invented';
  fields: { type: boolean; old: boolean; new: boolean } | null;
}

export interface ContractAnswer {
  marks: ChangeMark[];
  found: number;
  right: number;
  missed: number;
  invented: number;
}

function readChange(v: unknown): Change | null {
  if (!v || typeof v !== 'object') return null;
  return { type: String(pick(v, 'type') ?? ''), old: pick(v, 'old') ?? null, new: pick(v, 'new') ?? null };
}

export function contractAnswer(input: CaseVisualInput, doc: ContractDoc): ContractAnswer | null {
  const j = replyJson(input);
  if (!j || !doc.key) return null;
  const got = byId(pick(j, 'changes') ?? j, 'clause');
  const ids = [...new Set([...Object.keys(doc.key), ...got.keys()])];
  const marks = ids.map<ChangeMark>((id) => {
    const key = doc.key![id] ?? null;
    const model = readChange(got.get(id));
    const no = doc.keyNo[id] ?? [...doc.v2, ...doc.v1].find((c) => normSetId(c.no) === id)?.no ?? id;
    if (key && model) {
      const fields = { type: sameValue(key.type, model.type), old: sameValue(key.old, model.old), new: sameValue(key.new, model.new) };
      return { id, no, key, model, fields, state: fields.type && fields.old && fields.new ? 'right' : 'wrong' };
    }
    return { id, no, key, model, fields: null, state: key ? 'missed' : 'invented' };
  });
  const order = (no: string) => no.replace(/^S(\d+)/, (_, n) => String(100 + Number(n))).split('.').map(Number);
  marks.sort((a, b) => {
    const x = order(a.no);
    const y = order(b.no);
    return (x[0]! - y[0]!) || (x[1]! - y[1]!);
  });
  return {
    marks,
    found: marks.filter((m) => m.key && m.model).length,
    right: marks.filter((m) => m.state === 'right').length,
    missed: marks.filter((m) => m.state === 'missed').length,
    invented: marks.filter((m) => m.state === 'invented').length,
  };
}

const num = (v: unknown) => (typeof v === 'number' ? v.toLocaleString('en-GB') : String(v));

/** A change in plain words ("£250,000 → £1,000,000", "a “not” added or removed"). */
export function describe(c: Change): string {
  switch (c.type) {
    case 'amount':
      return `£${num(c.old)} → £${num(c.new)}`;
    case 'percent':
      return `${num(c.old)}% → ${num(c.new)}%`;
    case 'days':
    case 'months':
    case 'years':
    case 'hours':
      return `${num(c.old)} → ${num(c.new)} ${c.type}`;
    case 'number':
      return `${num(c.old)} → ${num(c.new)}`;
    case 'governing_law':
      return `law of ${num(c.old)} → ${num(c.new)}`;
    case 'negation':
      return 'a “not” added or removed: the clause now says the opposite';
    case 'obligation':
      return '“shall” ↔ “may”: a duty became optional, or the reverse';
    case 'party':
      return `the duty moved from the ${num(c.old)} to the ${num(c.new)}`;
    case 'cross_reference':
      return `now points to clause ${num(c.new)} instead of ${num(c.old)}`;
    case 'added':
      return 'a new clause';
    case 'deleted':
      return 'clause removed';
    case 'definition':
      return 'a definition changed';
    default:
      return c.type || 'changed';
  }
}
