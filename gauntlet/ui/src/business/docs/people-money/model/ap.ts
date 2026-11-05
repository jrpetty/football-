/**
 * Accounts payable as documents (Invoices to Pay, work.invoice-matching, and its ladder, work.invoices-ladder).
 *
 * The prompt is read into the paperwork a clerk has on the desk: the supplier master file, the paid ledger, the
 * purchase orders, the goods-received notes, the AP inbox (every invoice and credit note with its lines, totals
 * and bank details) and the emails since Monday. For each document the three-way match is laid out line by line
 * (ordered and its price on the PO, received on the GRNs, invoiced) with the plain differences marked; the
 * policy decision itself (approve, hold, reject) comes only from the answer key and the model's answer.
 */
import type { CaseVisualInput } from '../../../../../../src/presenter/visuals/common.ts';
import { isObj, keyOf, num, pick, pickId, readAnswer, type Obj } from './common.ts';

export interface PoLine {
  code: string;
  desc: string;
  ordered: number;
  price: number;
  vat: 'S' | 'R' | 'Z' | string;
  invoicedToDate: number;
}
export interface Po {
  id: string;
  supplier: string;
  raised: string;
  by: string;
  site: string;
  lines: PoLine[];
}
export interface Grn {
  id: string;
  po: string;
  date: string;
  site: string;
  signed: string;
  lines: Array<{ code: string; qty: number }>;
  note: string | null;
}
export interface DocItem {
  code: string;
  desc: string;
  qty: number;
  price: number;
  vatPct: number;
  value: number;
}
export interface ApDoc {
  id: string;
  received: string;
  source: string;
  kind: 'invoice' | 'credit';
  supplier: string;
  number: string;
  date: string;
  to: string;
  poRef: string | null;
  items: DocItem[];
  net: number | null;
  vat: number | null;
  total: number | null;
  sort: string | null;
  account: string | null;
  against: string | null;
  reason: string | null;
  /** The document's text as received. */
  text: string[];
}
export interface ApEmail {
  from: string;
  sent: string;
  subject: string;
  body: string;
}
export interface ApQuestion {
  company: string;
  from: string;
  run: string;
  nextRun: string;
  master: Array<{ name: string; terms: string; sort: string; account: string }>;
  ledger: string[];
  pos: Po[];
  grns: Grn[];
  docs: ApDoc[];
  emails: ApEmail[];
}

const normName = (s: string) =>
  s
    .toLowerCase()
    .replace(/\+/g, '&')
    .replace(/[.,]/g, '')
    .replace(/\s+/g, ' ')
    .trim();
const normRef = (s: string) => s.toUpperCase().replace(/O/g, '0').replace(/\s+/g, '');
const money = (s: string) => Number(s.replace(/[£,\s]/g, ''));

function section(prompt: string, head: string): string {
  const i = prompt.indexOf(`\n${head}`);
  if (i < 0) return '';
  const rest = prompt.slice(i + head.length + 1);
  const end = rest.search(/\n\n[A-Z][A-Z ()-]{6,}\n|\n\nYOUR ANSWER/);
  return end < 0 ? rest : rest.slice(0, end);
}

function readDoc(id: string, head: string, body: string[]): ApDoc {
  const text = body.map((l) => l.replace(/^ {2}/, ''));
  const all = text.join('\n');
  const received = head.match(/received ([^-]+?) -/)?.[1]?.trim() ?? '';
  const source = head.split(' - ').slice(1).join(' - ').trim();
  const credit = /CREDIT NOTE/i.test(all);
  let number = all.match(/(?:TAX INVOICE|CREDIT NOTE)\s+(?:N[0O]:\s*)?([A-Z0-9/-]+)/i)?.[1] ?? all.match(/INVOICE\s+([A-Z0-9/-]+)\s*\|/)?.[1] ?? '';
  if (!number) number = all.match(/Subject: Invoice ([A-Z0-9/-]+)/)?.[1] ?? '';
  const date = all.match(/Date:\s*([\d/]+)/i)?.[1] ?? all.match(/DATE\s+([\d/]+)/)?.[1] ?? all.match(/dated ([^|]+?)\s*\|/)?.[1] ?? '';
  const to = all.match(/(?:Invoice to|Bill to|INVOICE T[0O]):\s*([^\n]+)/i)?.[1]?.trim() ?? '';
  const poRef = all.match(/(?:Your order ref|your PO|CUST ORDER REF):\s*([A-Z0-9O-]+)/i)?.[1] ?? null;
  const items: DocItem[] = [];
  text.forEach((l, i) => {
    const m = l.match(/^([A-Z0-9][A-Z0-9-]*)\s+(.+?)\s{2,}(-?\d+)\s+[xX]\s+(-?[\d,.]+)\s+VAT\s+(\d+(?:\.\d+)?)%\s+(-?[\d,.]+)\s*$/);
    if (m) {
      items.push({ code: m[1]!, desc: m[2]!.trim(), qty: Number(m[3]), price: money(m[4]!), vatPct: Number(m[5]), value: money(m[6]!) });
      return;
    }
    // A price adjustment on a credit note: "XRM100  Price adjustment: …" then "8 x 1.10 credit, VAT 20%, net 8.80".
    const adj = l.match(/^([A-Z0-9][A-Z0-9-]*)\s+(Price adjustment.*)$/i);
    const nx = text[i + 1]?.trim().match(/^(\d+) x ([\d.]+) credit, VAT (\d+)%, net ([\d.,]+)/);
    if (adj && nx) items.push({ code: adj[1]!, desc: adj[2]!.trim(), qty: Number(nx[1]), price: money(nx[2]!), vatPct: Number(nx[3]), value: money(nx[4]!) });
  });
  const net = all.match(/(?:\bNet|Subtotal|G[0O]{2}DS TOTAL)\s*£?\s*(-?[\d,]+\.\d\d)/i)?.[1];
  const vat = all.match(/\bVAT\s*£?\s*(-?[\d,]+\.\d\d)/)?.[1];
  const total = all.match(/(?:Invoice total|Total credit|TOTAL DUE|\| Total)\s*£?\s*(-?[\d,]+\.\d\d)/i)?.[1];
  const bank = all.match(/Sort code\s*([\d -]{6,9}?)[,\s]+(?:Account(?: no\.?)?)\s*(\d[\d ]{5,10}\d)/i);
  const sup = text[0]?.includes('|') ? text[0].split('|')[0]!.trim() : (all.match(/Thanks, [^-\n]+ - ([^\n]+)/)?.[1] ?? text[0] ?? '').trim();
  return {
    id,
    received,
    source,
    kind: credit ? 'credit' : 'invoice',
    supplier: sup.replace(/\s{2,}.*$/, ''),
    number,
    date,
    to,
    poRef,
    items,
    net: net ? money(net) : null,
    vat: vat ? money(vat) : null,
    total: total ? money(total) : null,
    sort: bank ? bank[1]!.replace(/\s/g, '') : null,
    account: bank ? bank[2]!.replace(/\s/g, '') : null,
    against: all.match(/Against invoice:\s*([A-Z0-9/-]+)/)?.[1] ?? null,
    reason: all.match(/Reason:\s*([^\n]+)/)?.[1]?.trim() ?? null,
    text,
  };
}

/** The AP paperwork of an Invoices to Pay case (or a ladder level); null when the prompt is not one. */
export function apQuestion(prompt: string): ApQuestion | null {
  const company = prompt.match(/addressed \("Invoice to" \/ "Bill to"\) to ([^.]+?)\. Anything/)?.[1] ?? prompt.match(/^From:[^(\n]*\([^,)]+, ([^)]+)\)/)?.[1];
  if (!company || !/AP INBOX/.test(prompt)) return null;
  const from = prompt.match(/^From:\s*([^<(\n]+)/)?.[1]?.trim() ?? '';
  const run = prompt.match(/before (\w+ \d+ \w+ \d{4})'s payment run/)?.[1] ?? '';
  const nextRun = prompt.match(/The next run after that is (\w+ \d+ \w+ \d{4})/)?.[1] ?? '';
  const master = [...section(prompt, 'SUPPLIER MASTER FILE').matchAll(/^\s{2}(.+?)\s{2,}terms (.+?)\s+bank ([\d-]+) \/ (\d+)/gm)].map((m) => ({ name: m[1]!.trim(), terms: m[2]!.trim(), sort: m[3]!, account: m[4]! }));
  const ledger = section(prompt, 'PAID LEDGER')
    .split('\n')
    .slice(1)
    .map((l) => l.trim())
    .filter(Boolean);
  const pos: Po[] = [];
  for (const l of section(prompt, 'PO LIST').split('\n')) {
    const h = l.match(/^\s{2}(PO-\d+)\s+(.+?) - raised (.+?) by (.+?) for (.+)$/);
    if (h) {
      pos.push({ id: h[1]!, supplier: h[2]!.trim(), raised: h[3]!, by: h[4]!, site: h[5]!.trim(), lines: [] });
      continue;
    }
    const m = l.match(/^\s{6}(\S+)\s+(.+?)\s+ordered\s+(\d+) @\s+£([\d,.]+)\s+VAT (\w)\s+invoiced to date (\d+)/);
    if (m && pos.length) pos[pos.length - 1]!.lines.push({ code: m[1]!, desc: m[2]!.trim(), ordered: Number(m[3]), price: money(m[4]!), vat: m[5]!, invoicedToDate: Number(m[6]) });
  }
  const grns: Grn[] = [];
  for (const l of section(prompt, 'GOODS RECEIVED NOTES').split('\n')) {
    const m = l.match(/^\s{2}(GRN-\d+)\s+(PO-\d+)\s+received (.+?) at (.+?), signed ([^:]+):\s*(.+?)(?:\s+\(note: (.+)\))?$/);
    if (!m) continue;
    const lines = [...m[6]!.matchAll(/(\S+) x (\d+)/g)].map((x) => ({ code: x[1]!, qty: Number(x[2]) }));
    grns.push({ id: m[1]!, po: m[2]!, date: m[3]!, site: m[4]!, signed: m[5]!.trim(), lines, note: m[7] ?? null });
  }
  const inbox = prompt.split(/\nAP INBOX[^\n]*\n/)[1]?.split(/\nEMAILS RECEIVED SINCE MONDAY/)[0] ?? '';
  const docs: ApDoc[] = [];
  const blocks = inbox.split(/\n(?=\[DOC-\d+\])/);
  for (const b of blocks) {
    const lines = b.split('\n');
    const h = lines.findIndex((x) => /^\[DOC-\d+\]/.test(x));
    if (h < 0) continue;
    const id = lines[h]!.match(/^\[(DOC-\d+)\]/)![1]!;
    docs.push(readDoc(id, lines[h]!, lines.slice(h + 1).filter((x) => x.trim())));
  }
  const emailText = prompt.split(/\nEMAILS RECEIVED SINCE MONDAY\n/)[1]?.split(/\n\nYOUR ANSWER/)[0] ?? '';
  const emails: ApEmail[] = emailText.trim() === '(none)' ? [] : emailText
        .split(/\n\n(?=From: )/)
        .map((e) => {
          const from = e.match(/^From:\s*(.+)$/m)?.[1] ?? '';
          const sent = e.match(/^Sent:\s*(.+)$/m)?.[1] ?? '';
          const subject = e.match(/^Subject:\s*(.+)$/m)?.[1] ?? '';
          const body = e
            .split('\n')
            .filter((l) => !/^(From|Sent|Subject|To):/.test(l))
            .join('\n')
            .trim();
          return { from, sent, subject, body };
        })
        .filter((e) => e.from);
  if (!docs.length) return null;
  return { company: company.trim(), from, run, nextRun, master, ledger, pos, grns, docs, emails };
}

// ───────────────────────────── the three-way match of one document ─────────────────────────────

export interface MatchRow {
  code: string;
  desc: string;
  po: PoLine | null;
  received: number | null;
  item: DocItem;
  /** Invoiced quantity is more than received minus what was invoiced before. */
  qtyOver: boolean;
  /** Invoiced value minus PO price × quantity (pence-rounded); null without a PO line. */
  priceDiff: number | null;
}

export interface DocMatch {
  doc: ApDoc;
  po: Po | null;
  grns: Grn[];
  master: ApQuestion['master'][number] | null;
  toUs: boolean;
  bankOk: boolean | null;
  rows: MatchRow[];
  /** Emails that mention this document's PO, one of its GRNs or its supplier. */
  emails: ApEmail[];
  /** Earlier inbox documents billing the same PO (they may use up the quantity first). */
  earlier: string[];
}

export function docMatch(ap: ApQuestion, doc: ApDoc): DocMatch {
  const po = doc.poRef ? (ap.pos.find((p) => normRef(p.id) === normRef(doc.poRef!)) ?? null) : null;
  const grns = po ? ap.grns.filter((g) => g.po === po.id) : [];
  const supplierName = po?.supplier ?? doc.supplier;
  const master = ap.master.find((m) => normName(m.name) === normName(supplierName)) ?? ap.master.find((m) => normName(doc.supplier).startsWith(normName(m.name).split(' ')[0]!)) ?? null;
  const toUs = normName(doc.to).startsWith(normName(ap.company));
  const bankOk = master && doc.sort ? master.sort.replace(/\D/g, '') === doc.sort.replace(/\D/g, '') && master.account === doc.account : null;
  const rows: MatchRow[] = doc.items.map((item) => {
    const pl = po?.lines.find((l) => l.code === item.code) ?? null;
    const received = po ? grns.reduce((a, g) => a + g.lines.filter((x) => x.code === item.code).reduce((s, x) => s + x.qty, 0), 0) : null;
    const left = received === null || !pl ? null : received - pl.invoicedToDate;
    return {
      code: item.code,
      desc: item.desc,
      po: pl,
      received,
      item,
      qtyOver: left !== null && doc.kind === 'invoice' && item.qty > left,
      priceDiff: pl ? Math.round((item.value - pl.price * item.qty) * 100) / 100 : null,
    };
  });
  const mentions = (e: ApEmail) => {
    const t = `${e.subject} ${e.body}`;
    return (po && t.includes(po.id)) || grns.some((g) => t.includes(g.id)) || (master && normName(e.from).includes(normName(master.name).split(' ')[0]!));
  };
  const idx = ap.docs.indexOf(doc);
  const earlier = po ? ap.docs.slice(0, idx).filter((d) => d.poRef && normRef(d.poRef) === normRef(po.id)).map((d) => d.id) : [];
  return { doc, po, grns, master, toUs, bankOk, rows, emails: ap.emails.filter(mentions), earlier };
}

// ───────────────────────────── decisions ─────────────────────────────

export interface ApDecision {
  outcome: string;
  amount: number | null;
  payBy: string | null;
}

export const OUTCOME_LABEL: Record<string, string> = {
  approve: 'Pay',
  'hold-bank-details': 'Hold — bank details',
  'hold-no-po': 'Hold — no PO',
  'hold-qty': 'Hold — quantity',
  'hold-price': 'Hold — price',
  'hold-vat': 'Hold — VAT',
  'hold-linked': 'Hold — linked invoice',
  'reject-duplicate': 'Reject — duplicate',
  'reject-wrong-entity': 'Reject — wrong company',
};

export const OUTCOME_WHY: Record<string, string> = {
  'hold-bank-details': 'its bank details differ from the master file',
  'hold-no-po': 'it quotes no purchase order we can find',
  'hold-qty': 'it bills more than was received and not yet invoiced',
  'hold-price': 'its prices are outside the tolerance',
  'hold-vat': 'the VAT charged is wrong',
  'hold-linked': 'the invoice it belongs with is held',
  'reject-duplicate': 'it is a duplicate of an invoice already billed',
  'reject-wrong-entity': 'it is addressed to another company',
};

export interface ApRow {
  id: string;
  key: ApDecision;
  model: ApDecision | null;
  outcomeOk: boolean | null;
  amountOk: boolean | null;
  payByOk: boolean | null;
  right: boolean;
}

function decision(v: unknown): ApDecision | null {
  if (!isObj(v)) return null;
  const pb = pick(v, 'pay_by');
  return { outcome: String(pick(v, 'outcome') ?? '').toLowerCase().trim().replace(/[\s_]+/g, '-'), amount: num(pick(v, 'amount_to_pay')), payBy: typeof pb === 'string' && pb.trim() && !/^(null|none|n\/a|-)$/i.test(pb.trim()) ? pb.trim() : null };
}

export interface ApAnswer {
  answered: boolean;
  rows: ApRow[];
  keyTotal: number | null;
  modelTotal: number | null;
  totalOk: boolean | null;
}

export function apAnswer(input: CaseVisualInput): ApAnswer {
  const key = keyOf(input) ?? {};
  const ra = input.replies.length ? readAnswer(input) : null;
  const ans = ra?.parsed;
  const answered = isObj(ans);
  const kdocs = isObj(key.documents) ? key.documents : {};
  const mdocs = answered ? pick(ans, 'documents') : undefined;
  const rows: ApRow[] = Object.keys(kdocs).map((id) => {
    const k = decision(kdocs[id])!;
    const m = answered ? decision(pickId(mdocs, id)) : null;
    const v = (f: string) => ra?.verdict(`documents.${id}.${f}`) ?? null;
    const outcomeOk = !answered ? null : (v('outcome') ?? (m ? m.outcome === k.outcome : false));
    const amountOk = !answered ? null : (v('amount_to_pay') ?? (m ? m.amount !== null && Math.abs(m.amount - (k.amount ?? 0)) < 0.005 : false));
    const payByOk = !answered ? null : (v('pay_by') ?? (m ? (m.payBy ?? null) === (k.payBy ?? null) : false));
    return { id, key: k, model: m, outcomeOk, amountOk, payByOk, right: !!outcomeOk && !!amountOk && !!payByOk };
  });
  const keyTotal = num(key.total_payable_this_run);
  const modelTotal = answered ? num(pick(ans, 'total_payable_this_run')) : null;
  const totalOk = !answered ? null : (ra?.verdict('total_payable_this_run') ?? (modelTotal !== null && keyTotal !== null && Math.abs(modelTotal - keyTotal) < 0.005));
  return { answered, rows, keyTotal, modelTotal, totalOk };
}

/** Pounds a wrong decision would have moved, from the key and the model's own amount (never invented). */
export function apImpact(r: ApRow, doc: ApDoc | undefined): { text: string; paidWrongly: number; heldWrongly: number } | null {
  if (!r.model || r.right) return null;
  const k = r.key;
  const m = r.model;
  const kPays = k.outcome === 'approve';
  const mPays = m.outcome === 'approve';
  if (!kPays && mPays) {
    const amt = m.amount ?? doc?.total ?? 0;
    return { text: `Would have paid £${amt.toFixed(2)} on ${doc ? `${doc.number || r.id}` : r.id}, which should be ${k.outcome.startsWith('reject') ? 'rejected' : 'held'}: ${OUTCOME_WHY[k.outcome] ?? k.outcome}.`, paidWrongly: Math.max(0, amt), heldWrongly: 0 };
  }
  if (kPays && !mPays) {
    const amt = k.amount ?? 0;
    return { text: `Held back £${amt.toFixed(2)} that ${doc ? doc.supplier : 'the supplier'} is owed (${OUTCOME_LABEL[m.outcome]?.toLowerCase() ?? m.outcome}, but the invoice passes every check).`, paidWrongly: 0, heldWrongly: Math.max(0, amt) };
  }
  if (kPays && mPays && r.amountOk === false && m.amount !== null && k.amount !== null) {
    const d = m.amount - k.amount;
    return { text: `Pays £${m.amount.toFixed(2)} instead of £${k.amount.toFixed(2)} (${d > 0 ? `£${d.toFixed(2)} too much` : `£${(-d).toFixed(2)} too little`}${doc && Math.abs((doc.total ?? 0) - m.amount) < 0.005 ? ': the early-payment discount was not taken' : ''}).`, paidWrongly: Math.max(0, d), heldWrongly: Math.max(0, -d) };
  }
  if (r.outcomeOk === false) return { text: `Gave “${OUTCOME_LABEL[m.outcome] ?? m.outcome}” where the policy says “${OUTCOME_LABEL[k.outcome] ?? k.outcome}”: ${OUTCOME_WHY[k.outcome] ?? ''}.`, paidWrongly: 0, heldWrongly: 0 };
  if (r.payByOk === false) return { text: `Pay-by date ${m.payBy ?? 'none'} instead of ${k.payBy ?? 'none'}.`, paidWrongly: 0, heldWrongly: 0 };
  return null;
}

export function apSentence(a: ApAnswer): string {
  if (!a.answered) return 'No answer could be read from the reply, so nothing scored.';
  const n = a.rows.length;
  const right = a.rows.filter((r) => r.outcomeOk).length;
  const paidWrong = a.rows.filter((r) => r.key.outcome !== 'approve' && r.model?.outcome === 'approve').length;
  const heldWrong = a.rows.filter((r) => r.key.outcome === 'approve' && r.model && r.model.outcome !== 'approve').length;
  const totalTxt = a.totalOk ? `and the payment run total is exact (£${(a.keyTotal ?? 0).toFixed(2)})` : `but the payment run total is £${(a.modelTotal ?? 0).toFixed(2)}, not £${(a.keyTotal ?? 0).toFixed(2)}`;
  if (right === n) return `Gave every one of the ${n} documents the right outcome ${totalTxt}.`;
  const bits: string[] = [];
  if (paidWrong) bits.push(`approved ${paidWrong} that should not be paid`);
  if (heldWrong) bits.push(`held or rejected ${heldWrong} that should be paid`);
  const other = n - right - paidWrong - heldWrong;
  if (other > 0) bits.push(`gave ${other} the wrong kind of hold`);
  return `Got ${right} of ${n} outcomes right: it ${bits.join(', ')}; ${totalTxt.replace(/^and /, '').replace(/^but /, '')}.`;
}

/** `Obj` re-export for views. */
export type { Obj };
