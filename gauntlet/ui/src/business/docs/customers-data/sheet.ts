/**
 * Read the Spreadsheet (work.sheet-lookup): the sales-orders sheet read from the prompt, the rows and cells each
 * question needs (by the filters the question names), the sheet's traps (repeated rows, subtotal rows, corrections)
 * and the model's answers against the key and the naive answers the case notes record. Pure; tested in
 * test/customers-data-docs.test.ts.
 */
import type { CaseVisualInput } from '../../../../../src/presenter/visuals/common.ts';
import { pick, replyJson, sameValue } from './kit.ts';

export interface SheetRow {
  /** 1-based spreadsheet row number (the header is row 1). */
  n: number;
  cells: string[];
  /** repeat: an Order ID seen earlier · subtotal · correction · replaced: an order a later correction replaces */
  trap: 'repeat' | 'subtotal' | 'correction' | 'replaced' | null;
}

export interface SheetQuestion {
  id: string;
  text: string;
  /** Rows (by number) this question reads, and the column it adds up or reads. */
  rows: number[];
  col: number;
  key: unknown;
}

export interface SheetDoc {
  header: string[];
  rows: SheetRow[];
  notes: string[];
  questions: SheetQuestion[];
  naive: Record<string, unknown>;
  noCorrections: Record<string, unknown>;
}

const MONTHS = ['january', 'february', 'march', 'april', 'may', 'june', 'july', 'august', 'september', 'october', 'november', 'december'];

function json(notes: string, re: RegExp): Record<string, unknown> {
  const m = notes.match(re);
  if (!m) return {};
  try {
    return JSON.parse(m[1]!) as Record<string, unknown>;
  } catch {
    return {};
  }
}

export function parseSheet(prompt: string, expected?: unknown, caseNotes?: string): SheetDoc | null {
  const a = prompt.indexOf('<<<');
  const b = prompt.indexOf('>>>', a);
  if (a < 0 || b < 0) return null;
  const lines = prompt.slice(a + 3, b).trim().split('\n');
  const header = lines[0]!.split(',');
  const col = (name: RegExp) => header.findIndex((h) => name.test(h));
  const cId = col(/^Order ID/i);
  const cStatus = col(/^Status/i);
  const seen = new Set<string>();
  const corrected = new Set<string>();
  const rows: SheetRow[] = lines.slice(1).map((l, i) => ({ n: i + 2, cells: l.split(','), trap: null }));
  for (const r of rows) if (/^Correction$/i.test(r.cells[cStatus] ?? '')) corrected.add(r.cells[cId]!);
  for (const r of rows) {
    const id = r.cells[cId] ?? '';
    if (/^SUBTOTAL/i.test(id)) r.trap = 'subtotal';
    else if (/^Correction$/i.test(r.cells[cStatus] ?? '')) r.trap = 'correction';
    else if (seen.has(id)) r.trap = 'repeat';
    else if (corrected.has(id)) r.trap = 'replaced';
    seen.add(id);
  }
  const notes = (prompt.slice(b).match(/Notes on the sheet:\n([\s\S]*?)\n\n/)?.[1] ?? '').split('\n').map((l) => l.replace(/^-\s*/, '').trim()).filter(Boolean);
  const key = (expected ?? {}) as Record<string, unknown>;
  const questions: SheetQuestion[] = [];
  for (const m of prompt.slice(b).matchAll(/^(q\d+):\s*(.+)$/gm)) questions.push(locate(m[1]!, m[2]!, header, rows, key[m[1]!]));
  if (!questions.length) return null;
  return { header, rows, notes, questions, naive: json(caseNotes ?? '', /naive literal answers (\{[^}]*\})/), noCorrections: json(caseNotes ?? '', /answers without corrections (\{[^}]*\})/) };
}

/** The rows a question reads: every row whose region, rep, product, month and status match what the question names. */
function locate(id: string, text: string, header: string[], rows: SheetRow[], key: unknown): SheetQuestion {
  const idx = (re: RegExp) => header.findIndex((h) => re.test(h));
  const cDate = idx(/^Date/i);
  const cRegion = idx(/^Region/i);
  const cRep = idx(/^Rep/i);
  const cProduct = idx(/^Product/i);
  const cQty = idx(/^Qty/i);
  const cTotal = idx(/^Total/i);
  const cStatus = idx(/^Status/i);
  const values = (c: number) => [...new Set(rows.filter((r) => r.trap !== 'subtotal').map((r) => r.cells[c] ?? ''))].filter(Boolean);
  const region = values(cRegion).filter((v) => new RegExp(`\\b(?:in|of) the ${v} region`, 'i').test(text));
  const rep = values(cRep).filter((v) => text.includes(v));
  const product = (text.match(/"([^"]+)"/) ?? [])[1];
  const month = MONTHS.findIndex((mo) => new RegExp(`dated in ${mo}`, 'i').test(text));
  const statuses = new Set<string>(['completed', 'correction']);
  if (/\bcancelled\b/i.test(text) && /how many orders/i.test(text)) {
    statuses.clear();
    statuses.add('cancelled');
  }
  if (/refunded/i.test(text)) statuses.add('refunded');
  const min = text.match(/Total of £([\d,.]+) or more/);
  const out: number[] = [];
  for (const r of rows) {
    if (r.trap === 'subtotal') continue;
    const st = (r.cells[cStatus] ?? '').toLowerCase();
    if (!statuses.has(st)) continue;
    if (region.length && !region.includes(r.cells[cRegion] ?? '')) continue;
    if (rep.length && !rep.includes(r.cells[cRep] ?? '')) continue;
    if (product && r.cells[cProduct] !== product) continue;
    if (month >= 0 && Number((r.cells[cDate] ?? '').split('/')[1]) !== month + 1) continue;
    if (min && Number(r.cells[cTotal]) < Number(min[1]!.replace(/,/g, ''))) continue;
    out.push(r.n);
  }
  return { id, text, rows: out, col: /\bunits\b|Qty column/i.test(text) ? cQty : /^How many orders/i.test(text) ? 0 : /which rep/i.test(text) ? cRep : cTotal, key };
}

export const SHEET_ALIASES: Record<string, string> = { Jan: 'January', 'January 2026': 'January', Feb: 'February', 'February 2026': 'February', Mar: 'March', 'March 2026': 'March', Apr: 'April', 'April 2026': 'April', May: 'May', 'May 2026': 'May', Jun: 'June', 'June 2026': 'June' };

export interface SheetMark {
  id: string;
  model: unknown;
  key: unknown;
  ok: boolean;
  /** The model gave the answer of a known shortcut ("counted the repeated rows"…). */
  trap: string | null;
}

export function sheetAnswer(input: CaseVisualInput, doc: SheetDoc): SheetMark[] | null {
  const j = replyJson(input);
  if (!j) return null;
  return doc.questions.map((q) => {
    const model = pick(j, q.id) ?? null;
    const ok = sameValue(q.key, model, { numberTolerance: 1e-8, aliases: SHEET_ALIASES });
    let trap: string | null = null;
    if (!ok && model !== null) {
      const same = (v: unknown) => v !== undefined && sameValue(v, model, { numberTolerance: 1e-8, aliases: SHEET_ALIASES });
      if (same(doc.naive[`${q.id}_subtotal`])) trap = 'added in the SUBTOTAL rows as if they were orders';
      else if (same(doc.noCorrections[q.id])) trap = 'ignored the correction rows, so the corrected orders kept their old values';
      else if (same(doc.naive[q.id])) trap = 'took the sheet literally: repeated rows counted twice, or rows outside the filter included';
    }
    return { id: q.id, model, key: q.key, ok, trap };
  });
}
