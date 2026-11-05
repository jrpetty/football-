/**
 * Read an Invoice (work.invoice-read): one supplier invoice as copied from a PDF, and the accounts-system form.
 * The invoice is split into lines so every form field can point at the line it comes from; the traps the case's
 * notes name (our own VAT number, a delivery-note number, an earlier balance, the old bank account, amendments)
 * are marked on the invoice.
 */
import type { CaseVisualInput } from '../../../../../../src/presenter/visuals/common.ts';
import { gbp, isObj, keyOf, niceDate, pick, readAnswer, looseEq } from './common.ts';

export interface InvLine {
  i: number;
  text: string;
  kind: 'letterhead' | 'title' | 'meta' | 'table-head' | 'item' | 'total' | 'amend' | 'text' | 'blank';
  /** A trap the notes name on this line ("our own VAT number", "earlier balance"…). */
  trap: string | null;
}

export interface InvoiceDoc {
  lines: InvLine[];
  company: string;
}

export const INV_FIELDS: Array<{ path: string; label: string; money?: boolean }> = [
  { path: 'supplier', label: 'Supplier' },
  { path: 'invoice_number', label: 'Invoice number' },
  { path: 'invoice_date', label: 'Invoice date' },
  { path: 'due_date', label: 'Due date' },
  { path: 'net', label: 'Net', money: true },
  { path: 'vat', label: 'VAT', money: true },
  { path: 'gross', label: 'Gross (this invoice)', money: true },
  { path: 'supplier_vat_number', label: 'Supplier’s VAT number' },
  { path: 'po_reference', label: 'Our PO number' },
  { path: 'bank_details_changed', label: 'Bank details changed?' },
  { path: 'sort_code', label: 'Sort code to pay' },
  { path: 'account_number', label: 'Account to pay' },
  { path: 'vat_breakdown.standard_net', label: 'Net at 20%', money: true },
  { path: 'vat_breakdown.standard_vat', label: 'VAT at 20%', money: true },
  { path: 'vat_breakdown.reduced_net', label: 'Net at 5%', money: true },
  { path: 'vat_breakdown.reduced_vat', label: 'VAT at 5%', money: true },
  { path: 'vat_breakdown.zero_rated_net', label: 'Net at 0%', money: true },
];

const TRAPS: Array<[RegExp, string]> = [
  [/Customer VAT No/i, 'our own VAT number, not the supplier’s'],
  [/TOTAL NOW DUE|Previous balance/i, 'includes an earlier balance'],
  [/Delivery Note:|Our Ref:/i, 'not the invoice number'],
  [/Our old account|is closed/i, 'the old, closed account'],
  [/MM\/DD\/YYYY/i, 'US date order'],
  [/AMENDMENT/i, 'amendment: recalculate'],
  [/have NOT been reissued/i, 'printed totals are out of date'],
  [/settlement discount|discount of/i, 'only offered: ignore'],
];

export function invoiceDoc(prompt: string): InvoiceDoc | null {
  const body = prompt.match(/Invoice:\s*\n<<<\n([\s\S]*?)\n>>>/)?.[1];
  if (!body) return null;
  const company = prompt.match(/accounts payable at ([^.]+)\./)?.[1] ?? '';
  let inTable = false;
  const lines = body.split('\n').map((text, i): InvLine => {
    let kind: InvLine['kind'] = 'text';
    if (!text.trim()) {
      kind = 'blank';
      inTable = false;
    } else if (i <= 2) kind = 'letterhead';
    else if (/^(TAX INVOICE|INVOICE)\b/.test(text.trim())) kind = 'title';
    else if (/\|\s*Description\s*\|/i.test(text)) {
      kind = 'table-head';
      inTable = true;
    } else if (inTable && text.includes('|')) kind = 'item';
    else if (/^AMENDMENT/i.test(text)) kind = 'amend';
    else if (/^(Net total|VAT|Invoice total|Previous balance|TOTAL NOW DUE|Delivery)/i.test(text)) kind = 'total';
    else if (/^(Invoice No|Delivery Note|Your Order Ref|Invoice to|Customer VAT|FAO|\(Dates)/i.test(text)) kind = 'meta';
    const trap = TRAPS.find(([re]) => re.test(text))?.[1] ?? null;
    return { i, text, kind, trap };
  });
  return { lines, company };
}

/** Which lines each form field is read from. */
export function invoiceSources(doc: InvoiceDoc): Record<string, number[]> {
  const find = (re: RegExp) => doc.lines.filter((l) => re.test(l.text)).map((l) => l.i);
  const amend = doc.lines.filter((l) => l.kind === 'amend').map((l) => l.i);
  const changed = find(/bank details have changed/i);
  const bank = changed.length ? changed : find(/sort code/i);
  return {
    supplier: [0],
    invoice_number: find(/^Invoice No/i),
    invoice_date: [...find(/Invoice Date/i), ...find(/MM\/DD\/YYYY/)],
    due_date: find(/Payment due|terms|due by|Terms:/i),
    net: [...find(/^Net total/i), ...amend],
    vat: [...find(/^VAT[: @]/), ...amend],
    gross: [...find(/^Invoice total/i), ...amend],
    supplier_vat_number: find(/VAT Reg No/i),
    po_reference: find(/Your Order Ref/i),
    bank_details_changed: changed.length ? changed : find(/sort code/i),
    sort_code: bank,
    account_number: bank,
    'vat_breakdown.standard_net': [...find(/VAT @ 20%|^VAT:/), ...amend],
    'vat_breakdown.standard_vat': [...find(/VAT @ 20%|^VAT:/), ...amend],
    'vat_breakdown.reduced_net': find(/VAT @ 5%/),
    'vat_breakdown.reduced_vat': find(/VAT @ 5%/),
    'vat_breakdown.zero_rated_net': find(/Zero-rated/i),
  };
}

const at = (o: unknown, path: string): unknown => path.split('.').reduce<unknown>((v, k) => (isObj(v) ? pick(v, k) : undefined), o);

export function invValueText(path: string, v: unknown): string {
  if (v === undefined) return 'left blank';
  if (v === null) return 'none';
  if (typeof v === 'boolean') return v ? 'yes' : 'no';
  if (typeof v === 'number') return INV_FIELDS.find((f) => f.path === path)?.money ? gbp(v) : String(v);
  if (/date/.test(path) && typeof v === 'string') return niceDate(v);
  return String(v);
}

export interface InvRow {
  n: number;
  path: string;
  label: string;
  want: unknown;
  got: unknown;
  ok: boolean | null;
  why: string | null;
}

export function invoiceRows(_doc: InvoiceDoc, input: CaseVisualInput): { rows: InvRow[]; answered: boolean } {
  const key = keyOf(input) ?? {};
  const ra = input.replies.length ? readAnswer(input) : null;
  const ans = ra?.parsed;
  const answered = isObj(ans);
  const notes = input.notes ?? '';
  const rows = INV_FIELDS.map((f, i): InvRow => {
    const want = at(key, f.path);
    const got = answered ? at(ans, f.path) : undefined;
    const ok = !answered ? null : (ra!.verdict(f.path) ?? looseEq(want, got));
    let why: string | null = null;
    if (ok === false) {
      const g = typeof got === 'number' ? got : Number.NaN;
      const due = Number(notes.match(/total now due on account ([\d.]+)/)?.[1]);
      const printed = notes.match(/printed totals net ([\d.]+) vat ([\d.]+) gross ([\d.]+)/);
      if (f.path === 'gross' && g === due) why = 'Paid the “total now due on account”, which adds an earlier balance.';
      else if (printed && /amended totals/.test(notes) && ['net', 'vat', 'gross'].includes(f.path) && [printed[1], printed[2], printed[3]].map(Number).includes(g)) why = 'Copied the printed total: the amendments below it change the figures.';
      else if (f.path === 'supplier_vat_number' && typeof got === 'string' && notes.includes(`customer VAT ${got.replace(/\s/g, '')}`)) why = 'That is our own VAT number, printed under “Invoice to”.';
      else if ((f.path === 'sort_code' || f.path === 'account_number' || f.path === 'bank_details_changed') && /old bank/.test(notes)) why = 'The invoice says the bank details have changed: the old account is closed.';
      else if (f.path === 'invoice_number' && typeof got === 'string' && /^(DN|SO)-/.test(got)) why = 'That is the delivery-note or order number, not the invoice number.';
      else if (f.path === 'invoice_date' && /US date/.test(notes)) why = 'The invoice says its dates are month/day/year.';
      else if (/OCR/.test(notes) && typeof got === 'string' && /O/.test(got)) why = 'The scan read 0 as the letter O.';
    }
    return { n: i + 1, path: f.path, label: f.label, want, got, ok, why };
  });
  return { rows, answered };
}

export function invoiceSentence(rows: InvRow[], answered: boolean): string {
  if (!answered) return 'No form could be read from the reply, so nothing scored.';
  const wrong = rows.filter((r) => r.ok === false);
  if (!wrong.length) return `Filled in all ${rows.length} boxes exactly, traps included.`;
  const first = wrong.slice(0, 2).map((r) => `${r.label.toLowerCase()} (${invValueText(r.path, r.got)}; correct ${invValueText(r.path, r.want)})`);
  return `Filled in ${rows.length - wrong.length} of ${rows.length} boxes right; wrong: ${first.join(' and ')}${wrong.length > 2 ? ` and ${wrong.length - 2} more` : ''}.`;
}
