/**
 * Business ladders (work.*-ladder): big JSON answers (up to about a thousand records) summarised for a viewer
 * instead of listed leaf by leaf: points right per section of the answer, one cell per record (right, partly
 * wrong, missing), and the wrong fields grouped by record with the key's value against the model's. Everything is
 * read from the scorer's own per-field verdicts (scoreDetail.items), so the picture can never disagree with the
 * marking. The question side (what the model was handed) reads only the prompt, never the answer key.
 *
 * The SQL ladder is marked per database (the reference query's rows against the model's); it gets the same shape
 * with one "section" per database.
 */
import type { ScoreBreakdownItem } from '../../core/types.ts';
import { extractCodeBlock } from '../../core/extract.ts';
import { finalReply, type CaseVisualInput } from './common.ts';

export type BizFieldKind = 'value' | 'listed' | 'extra' | 'pairs' | 'count' | 'empty';

export interface BizField {
  /** Full dotted path as the scorer printed it. */
  path: string;
  section: string;
  /** Record the field belongs to ("DOC-30551", "July 2026 · E03"), or '' for a top-level figure. */
  entity: string;
  field: string;
  kind: BizFieldKind;
  points: number;
  /** Points earned (a grouping earns its F1 share). */
  earned: number;
  ok: boolean;
  expected: string | null;
  got: string | null;
  /** The scorer's own words when there is no expected/got pair ("missing", "pairwise F1 82%"). */
  note: string | null;
}

export interface BizSection {
  key: string;
  label: string;
  earned: number;
  points: number;
  fields: number;
  wrong: number;
}

export interface BizEntity {
  section: string;
  id: string;
  fields: number;
  wrong: number;
  /** Every field of the record missing from the answer. */
  missing: boolean;
}

export interface BizWrongGroup {
  section: string;
  entity: string;
  fields: BizField[];
}

export interface BusinessVisual {
  kind: 'json' | 'sql';
  sections: BizSection[];
  entities: BizEntity[];
  /** Wrong fields grouped by record, in the order the scorer listed them. */
  wrong: BizWrongGroup[];
  wrongFields: number;
  fields: number;
  earned: number;
  points: number;
  /** SQL ladder: the model's query. */
  query?: string;
}

const W_SUFFIX = /\s\(×(\d+(?:\.\d+)?)\)$/;

/** "total_payable_this_run" → "Total payable this run". */
const ABBREV: Record<string, string> = { ni: 'NI', vat: 'VAT', sla: 'SLA', po: 'PO', grn: 'GRN', ytd: 'YTD', id: 'ID' };

/** Answer sections whose key name says little to a viewer. */
const SECTION_LABEL: Record<string, string> = { golden: 'Surviving records', clusters: 'Duplicate groups', non_compliant: 'Lines cut or refused', remaining_received: 'Quantity left', year_to_date: 'Year to date' };

export function sectionLabel(key: string): string {
  return SECTION_LABEL[key] ?? humanize(key);
}

export function humanize(key: string): string {
  const s = key
    .replace(/[_-]+/g, ' ')
    .replace(/\s+/g, ' ')
    .trim()
    .replace(/\b[a-z]+\b/g, (w) => ABBREV[w] ?? w);
  return s ? s[0]!.toUpperCase() + s.slice(1) : key;
}

/** Split a scorer path into segments: dots and [i] indexes, keeping keys with spaces ("July 2026") whole. */
function segments(path: string): string[] {
  return path
    .replace(/\[(\d+)\]/g, '.#$1')
    .split('.')
    .filter(Boolean);
}

/** A record's label: its path inside the section, without container words ("September 2026 · E07", not "· corrected ·"). */
const CONTAINERS = new Set(['corrected', 'golden', 'items', 'lines', 'entries']);
function entityOf(segs: string[]): string {
  return segs.filter((x, i) => i === segs.length - 1 || !CONTAINERS.has(x)).join(' · ');
}

function unquote(s: string): string {
  const t = s.trim();
  if (/^".*"$/.test(t)) {
    try {
      return String(JSON.parse(t));
    } catch {
      return t.slice(1, -1);
    }
  }
  return t;
}

/** One scorer item → a field. */
export function parseBizItem(it: ScoreBreakdownItem): BizField {
  let label = it.label;
  const wm = label.match(W_SUFFIX);
  const w = wm ? Number(wm[1]) : 1;
  if (wm) label = label.slice(0, wm.index);
  const detail = it.detail ?? null;
  const base = { ok: it.passed, note: null as string | null, expected: null as string | null, got: null as string | null };

  const pairs = label.match(/^(.+?): (\d+) of (\d+) same-group pairs found, (\d+) wrong pairs$/);
  if (pairs) {
    const total = Math.max(1, Number(pairs[3]));
    const f1 = it.passed ? 1 : Number((detail?.match(/F1 (\d+)%/) ?? [])[1] ?? 0) / 100;
    const seg = segments(pairs[1]!);
    return { ...base, path: pairs[1]!, section: seg[0] ?? pairs[1]!, entity: '', field: `${pairs[2]} of ${pairs[3]} pairs found, ${pairs[4]} wrong`, kind: 'pairs', points: total, earned: total * f1, note: detail };
  }
  const extra = label.match(/^(.+?): extra item (".*?"|\S+) \(not in the answer key\)$/);
  if (extra) {
    const cost = Number((detail?.match(/costs (\d+(?:\.\d+)?) point/) ?? [])[1] ?? 1);
    const seg = segments(extra[1]!);
    return { ...base, path: extra[1]!, section: seg[0] ?? extra[1]!, entity: entityOf([...seg.slice(1), unquote(extra[2]!)]), field: 'listed but should not be', kind: 'extra', points: cost, earned: 0, note: detail, got: 'listed', expected: 'not listed' };
  }
  const count = label.match(/^(.+?) has (\d+) items$/);
  if (count) {
    const seg = segments(count[1]!);
    return { ...base, path: count[1]!, section: seg[0] ?? '$', entity: entityOf(seg.slice(1)), field: 'number of items', kind: 'count', points: w, earned: it.passed ? w : 0, expected: count[2]!, got: it.passed ? count[2]! : (detail?.replace(/^got\s*/, '') ?? null) };
  }
  const empty = label.match(/^(.+?) (?:has no items|is \{\})$/);
  if (empty) {
    const seg = segments(empty[1]!);
    return { ...base, path: empty[1]!, section: seg[0] ?? '$', entity: entityOf(seg.slice(1)), field: 'should be empty', kind: 'empty', points: w, earned: it.passed ? w : 0, note: detail };
  }
  const listed = label.match(/^(.+?): (.+)$/);
  if (listed) {
    const seg = segments(listed[1]!);
    return { ...base, path: listed[1]!, section: seg[0] ?? listed[1]!, entity: entityOf([...seg.slice(1, -1), listed[2]!]) || listed[2]!, field: seg.length > 1 ? `listed under ${humanize(seg[seg.length - 1]!).toLowerCase()}` : 'listed', kind: 'listed', points: w, earned: it.passed ? w : 0, expected: 'listed', got: it.passed ? 'listed' : 'not listed' };
  }
  const seg = segments(label);
  const ev = detail?.match(/^expected ([\s\S]*), got ([\s\S]*)$/);
  return {
    ...base,
    path: label,
    section: seg[0] ?? label,
    entity: entityOf(seg.slice(1, -1)),
    field: seg.length > 1 ? seg[seg.length - 1]! : seg[0] ?? label,
    kind: 'value',
    points: w,
    earned: it.passed ? w : 0,
    expected: ev ? unquote(ev[1]!) : null,
    got: ev ? unquote(ev[2]!) : null,
    note: ev ? null : detail,
  };
}

export function businessFromItems(items: readonly ScoreBreakdownItem[]): BusinessVisual | null {
  if (!items.length) return null;
  const fields = items.map(parseBizItem);
  const sections = new Map<string, BizSection>();
  const entities = new Map<string, BizEntity>();
  const wrong = new Map<string, BizWrongGroup>();
  for (const f of fields) {
    const s = sections.get(f.section) ?? { key: f.section, label: sectionLabel(f.section), earned: 0, points: 0, fields: 0, wrong: 0 };
    s.earned += f.earned;
    s.points += f.points;
    s.fields++;
    if (!f.ok) s.wrong++;
    sections.set(f.section, s);
    if (f.entity && f.kind !== 'extra') {
      const k = `${f.section}\u0000${f.entity}`;
      const e = entities.get(k) ?? { section: f.section, id: f.entity, fields: 0, wrong: 0, missing: true };
      e.fields++;
      if (!f.ok) e.wrong++;
      if (f.ok || (f.got !== null && f.got !== 'missing' && f.got !== 'not listed')) e.missing = false;
      entities.set(k, e);
    }
    if (!f.ok) {
      const k = `${f.section}\u0000${f.entity}`;
      const g = wrong.get(k) ?? { section: f.section, entity: f.entity, fields: [] };
      g.fields.push(f);
      wrong.set(k, g);
    }
  }
  for (const e of entities.values()) if (e.wrong < e.fields) e.missing = false;
  const all = [...sections.values()];
  return {
    kind: 'json',
    sections: all,
    entities: [...entities.values()],
    wrong: [...wrong.values()],
    wrongFields: fields.filter((f) => !f.ok).length,
    fields: fields.length,
    earned: all.reduce((a, s) => a + s.earned, 0),
    points: all.reduce((a, s) => a + s.points, 0),
  };
}

/** SQL ladder: one section per database ("hidden copy A: 37 of 42 rows right (88%)"). */
export function sqlFromItems(items: readonly ScoreBreakdownItem[], query: string): BusinessVisual | null {
  if (!items.length) return null;
  const sections: BizSection[] = items.map((it) => {
    const share = it.passed ? 1 : /^(\d+) of (\d+) rows right/.test(it.detail ?? '') ? Number((it.detail!.match(/\((\d+)%\)/) ?? [])[1] ?? 0) / 100 || Number(it.detail!.match(/^(\d+) of (\d+)/)![1]) / Math.max(1, Number(it.detail!.match(/^(\d+) of (\d+)/)![2])) : /half marks/.test(it.detail ?? '') ? 0.5 : 0;
    return { key: it.label, label: humanize(it.label.replace(/\s*\(sampled in the prompt\)/, ' (the sample in the prompt)')), earned: share, points: 1, fields: 1, wrong: it.passed ? 0 : 1 };
  });
  const wrong: BizWrongGroup[] = items
    .filter((it) => !it.passed)
    .map((it) => ({ section: it.label, entity: it.label, fields: [{ path: it.label, section: it.label, entity: it.label, field: 'rows returned', kind: 'value', points: 1, earned: 0, ok: false, expected: 'the reference rows', got: null, note: it.detail ?? null }] }));
  return {
    kind: 'sql',
    sections,
    entities: [],
    wrong,
    wrongFields: wrong.length,
    fields: items.length,
    earned: sections.reduce((a, s) => a + s.earned, 0),
    points: sections.length,
    query,
  };
}

export function businessVisual(input: CaseVisualInput): BusinessVisual | null {
  const items = input.detail.items ?? [];
  if (input.scorerType === 'sql' || input.testId === 'work.sql-ladder') {
    const q = typeof input.detail.extracted === 'string' ? input.detail.extracted : extractCodeBlock(finalReply(input), ['sql', ''])?.code ?? '';
    return sqlFromItems(items, q);
  }
  return businessFromItems(items);
}

/** "Scored 88% (612 of 696 points): 9 of 67 records had a mistake …" */
export function businessSentence(v: BusinessVisual, score: number | null, passMark: number, noun = 'records'): string {
  const s = score === null ? null : Math.round(score * 100);
  const need = Math.round(passMark * 100);
  const passed = score !== null && score >= passMark - 1e-9;
  if (v.kind === 'sql') {
    const exact = v.sections.filter((x) => x.wrong === 0).length;
    const head = `Its query was exactly right on ${exact} of ${v.sections.length} databases`;
    if (s === null) return `${head}.`;
    return passed ? `${head}, scoring ${s}%: above the ${need}% pass mark, so the level is passed.` : `${head}, scoring ${s}%: short of the ${need}% pass mark, so the level counts as failed.`;
  }
  const recs = v.entities.length;
  const bad = v.entities.filter((e) => e.wrong > 0).length;
  const where = recs ? `${bad} of ${recs} ${noun} had a mistake` : `${v.wrongFields} of ${v.fields} checks were wrong`;
  const top = [...v.sections].filter((x) => x.wrong > 0).sort((a, b) => b.points - b.earned - (a.points - a.earned))[0];
  const most = top && v.sections.length > 1 ? `, most of the lost points in ${top.label.toLowerCase()}` : '';
  if (s === null) return `${where}${most}.`;
  if (!v.wrongFields) return `Every field matched the answer key: ${s}%, so the level is passed.`;
  return passed ? `Scored ${s}%, above the ${need}% pass mark, so the level is passed, though ${where}${most}.` : `Scored ${s}%, ${need - s} point${need - s === 1 ? '' : 's'} short of the ${need}% pass mark: ${where}${most}.`;
}

// ───────────────────────────── the question: what the model was handed ─────────────────────────────

export interface BizCard {
  id: string;
  /** The rest of the item's first line, tidied ("received 16 Nov 2026 07:38 · email body"). */
  text: string;
  /** Pipe-separated fields of a table row, when the line is one. */
  cells?: string[];
  /** The item's next line when it belongs to the item (an invoice's sender, a ticket's customer). */
  sub?: string;
}

export interface BizPile {
  label: string;
  noun: string;
  count: number;
  cards: BizCard[];
}

export interface BusinessQuestion {
  from: string | null;
  subject: string | null;
  /** First line(s) of the message, as a one-sentence summary. */
  opener: string;
  piles: BizPile[];
  /** Fields the answer must give per record (from the answer template in the prompt). */
  answerFields: string[];
  /** SQL ladder: the colleague's question and the task. */
  ask?: { who: string | null; quote: string; task: string };
}

interface PileSpec {
  label: string;
  noun: string;
  re: RegExp;
}

const PILES: Record<string, PileSpec[]> = {
  'work.invoices-ladder': [{ label: 'Documents in the inbox', noun: 'documents', re: /^\[(DOC-\d+)\]\s*(.*)$/ }],
  'work.payroll-ladder': [{ label: 'Staff', noun: 'staff', re: /^\s*(E\d{2,3}) ([^:]+?)(?:,\s*NI number[^:]*)?:\s*(.*)$/ }],
  'work.expenses-ladder': [{ label: 'Claim lines', noun: 'claim lines', re: /^\s*(L\d{3,4}) \| (.*)$/ }],
  'work.reconciliation-ladder': [
    { label: 'Bank statement', noun: 'bank lines', re: /^(B\d{3,4}) (.*)$/ },
    { label: 'Cash book', noun: 'cash-book entries', re: /^(L\d{3,4}) (.*)$/ },
  ],
  'work.crm-ladder': [{ label: 'Customer records', noun: 'records', re: /^([MB]-\d{4,6}),(.*)$/ }],
  'work.contract-ladder': [{ label: 'Clauses and schedule rows', noun: 'clauses', re: /^\s*((?:S?\d+)\.\d+[a-z]?)\s+(.+)$/ }],
  'work.support-ladder': [{ label: 'Tickets in the queue', noun: 'tickets', re: /^--- Ticket (#\d+) \| (.*)$/ }],
};

/** What one record of the ANSWER is called, for sentences ("documents", "tickets"; "answer lines" where a record mixes kinds). */
const ANSWER_NOUN: Record<string, string> = { 'work.invoices-ladder': 'documents', 'work.support-ladder': 'tickets and accounts', 'work.crm-ladder': 'customer groups', 'work.contract-ladder': 'changes' };
export function businessNoun(testId: string): string {
  return ANSWER_NOUN[testId] ?? 'answer lines';
}

function tidy(s: string, max = 120): string {
  const t = s.replace(/\s+/g, ' ').replace(/\s*\|\s*/g, ' · ').trim();
  return t.length > max ? `${t.slice(0, max - 1)}…` : t;
}

export function businessQuestion(testId: string, prompt: string, cardsPerPile = 4): BusinessQuestion {
  const from = (prompt.match(/^From:\s*(.+)$/m) ?? [])[1] ?? null;
  const subject = (prompt.match(/^Subject:\s*(.+)$/m) ?? [])[1] ?? null;
  const body = prompt.replace(/^(?:From|To|Sent|Subject|Cc):.*\n/gm, '').trim();
  const opener = tidy((body.split(/\n\s*\n/).find((p) => !/^(?:Hi|Hello|Morning|Dear)\b[^\n]{0,30}$/i.test(p.trim())) ?? body).split(/(?<=[.?!])\s/)[0] ?? '', 220);
  const lines = prompt.split('\n');
  const piles: BizPile[] = (PILES[testId] ?? []).map((spec) => {
    const seen = new Set<string>();
    const cards: BizCard[] = [];
    for (let li = 0; li < lines.length; li++) {
      const line = lines[li]!;
      const m = line.match(spec.re);
      if (!m || seen.has(m[1]!)) continue;
      seen.add(m[1]!);
      if (cards.length < cardsPerPile) {
        const rest = m.slice(2).filter(Boolean).join(': ');
        const cells = rest.includes('|') ? rest.split('|').map((c) => c.trim()).filter(Boolean).slice(0, 5) : rest.includes(',') && testId === 'work.crm-ladder' ? rest.split(',').map((c) => c.trim()).filter(Boolean).slice(0, 4) : undefined;
        const next = (lines[li + 1] ?? '').trim();
        const sub = next && !spec.re.test(lines[li + 1]!) && /^(?:\s|From:|[A-Z][A-Z &]+[|:])/.test(lines[li + 1]!) ? tidy(next, 90) : undefined;
        cards.push({ id: m[1]!, text: tidy(rest), cells, sub });
      }
    }
    return { label: spec.label, noun: spec.noun, count: seen.size, cards };
  });
  // The answer template's field names (never the key): "outcome", "amount_to_pay", …
  const at = prompt.search(/\n(?:YOUR ANSWER|Reply with one JSON|Answer with one JSON|Reply with the JSON)/);
  const tpl = at >= 0 ? prompt.slice(at) : '';
  const answerFields = [...new Set([...tpl.matchAll(/"([a-z][a-z_]{2,30})"\s*:/g)].map((m) => m[1]!))].slice(0, 8).map((k) => humanize(k).toLowerCase());
  let ask: BusinessQuestion['ask'];
  if (testId === 'work.sql-ladder') {
    const q = prompt.split(/== QUESTION ==\s*\n/)[1] ?? prompt;
    const said = q.match(/^From ([^:]+):\s*"([^"]+)"/m);
    const task = q.replace(/^From [^\n]*\n/m, '').trim().split(/\n\s*\n/)[0] ?? '';
    ask = { who: said ? said[1]!.trim() : null, quote: said ? said[2]! : tidy(q, 200), task: tidy(task, 400) };
  }
  return { from, subject, opener, piles, answerFields, ask };
}
