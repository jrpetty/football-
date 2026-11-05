/**
 * Bank reconciliation (Reconcile the Account, work.bank-reconciliation, and its ladder,
 * work.reconciliation-ladder): the bank statement and the cash book read line by line (month by month for the
 * ladder, with last month's outstanding items), and the links between them: the key's and the model's, each
 * statement line matched to its cash-book entries or given a reason, and every difference and balance.
 */
import type { CaseVisualInput } from '../../../../../../src/presenter/visuals/common.ts';
import { isObj, keyOf, num, pick, pickId, readAnswer, type Obj } from './common.ts';

export interface BankLine {
  id: string;
  date: string;
  desc: string;
  out: number | null;
  inn: number | null;
  balance: number | null;
  month: string;
}

export interface BookLine {
  id: string;
  date: string;
  ref: string;
  details: string;
  pay: number | null;
  rec: number | null;
  month: string;
  /** Last month's outstanding item (P1, P2…). */
  prior: boolean;
}

export interface RecQuestion {
  company: string;
  months: string[];
  bank: BankLine[];
  book: BookLine[];
  closing: Record<string, { bank: number | null; book: number | null }>;
  opening: string;
}

const money = (s: string | undefined) => (s && s.trim() ? Number(s.replace(/[£,\s]/g, '')) : null);

export function recQuestion(prompt: string): RecQuestion | null {
  if (!/Bank statement,/.test(prompt) || !/Cash book,/.test(prompt)) return null;
  const company = prompt.match(/bank reconciliation for ([^?,]+?)[?,]/)?.[1] ?? prompt.match(/for ([A-Z][^.?]+? (?:Ltd|plc|LLP))/)?.[1] ?? '';
  const bank: BankLine[] = [];
  const book: BookLine[] = [];
  const months: string[] = [];
  const closing: RecQuestion['closing'] = {};
  let side: 'bank' | 'book' | null = null;
  let month = '';
  for (const line of prompt.split('\n')) {
    const bs = line.match(/^Bank statement, ([A-Z][a-z]+ \d{4})/);
    if (bs) {
      side = 'bank';
      month = bs[1]!;
      if (!months.includes(month)) months.push(month);
      continue;
    }
    const cb = line.match(/^Cash book, ([A-Z][a-z]+ \d{4})/);
    if (cb) {
      side = 'book';
      month = cb[1]!;
      continue;
    }
    const cl = line.match(/^Closing balance £([\d,.-]+)/);
    if (cl) {
      (closing[month] ??= { bank: null, book: null }).bank = money(cl[1]);
      continue;
    }
    const cbl = line.match(/^Cash-book closing balance £([\d,.-]+)/);
    if (cbl) {
      (closing[month] ??= { bank: null, book: null }).book = money(cbl[1]);
      side = null;
      continue;
    }
    const pr = line.match(/^\s+(P\d+) (\w{3} \d+ \w{3}) \| ([^|]*) \| ([^|]*) \| (payment|receipt) ([\d,.]+)/);
    if (pr) {
      book.push({ id: pr[1]!, date: pr[2]!, ref: pr[3]!.trim(), details: pr[4]!.trim(), pay: pr[5] === 'payment' ? money(pr[6]) : null, rec: pr[5] === 'receipt' ? money(pr[6]) : null, month: 'brought forward', prior: true });
      continue;
    }
    if (side === 'bank') {
      const m = line.match(/^(B\d+) (.+?) \| (.+?) \| ([\d,.]*)\s*\| ([\d,.]*)\s*\| ([\d,.-]+)\s*$/);
      if (m) bank.push({ id: m[1]!, date: m[2]!.trim(), desc: m[3]!.trim(), out: money(m[4]), inn: money(m[5]), balance: money(m[6]), month });
    } else if (side === 'book') {
      const m = line.match(/^(L\d+) (.+?) \| (.*?) \| (.+?) \| ([\d,.]*)\s*\| ?([\d,.]*)\s*$/);
      if (m) book.push({ id: m[1]!, date: m[2]!.trim(), ref: m[3]!.trim(), details: m[4]!.trim(), pay: money(m[5]), rec: money(m[6]), month, prior: false });
    }
  }
  if (!bank.length || !book.length) return null;
  const opening = prompt.match(/opened the month at £([\d,.]+)/)?.[1] ?? '';
  return { company: company.trim(), months, bank, book, closing, opening };
}

export const REASON_LABEL: Record<string, string> = {
  bank_charge: 'Bank charge',
  interest: 'Interest',
  unrecorded_direct_debit: 'Direct debit not in the cash book',
  duplicate_payment: 'Paid twice by the bank',
  reversed_charge: 'Charge and its reversal',
  returned_cheque: 'Cheque returned unpaid',
  unpresented_cheque: 'Cheque not yet cleared',
  deposit_in_transit: 'Paid in, not yet on the statement',
  duplicate_entry: 'Entered twice in the cash book',
  keying_error: 'Keyed wrongly (digits swapped)',
  fx_difference: 'Exchange-rate difference',
};

/** A link value: a list of line ids, or a reason. */
export interface Link {
  ids: string[];
  reason: string | null;
}

function link(v: unknown): Link | null {
  if (v === undefined || v === null) return null;
  const xs = Array.isArray(v) ? v.map(String) : [String(v)];
  const ids = xs.map((x) => x.trim().toUpperCase()).filter((x) => /^[BLP]\d+$/.test(x));
  const reason = xs.length === 1 && !ids.length ? xs[0]!.trim().toLowerCase().replace(/[\s-]+/g, '_') : null;
  return { ids: ids.sort(), reason };
}

const sameLink = (a: Link | null, b: Link | null) => !!a && !!b && a.reason === b.reason && a.ids.join() === b.ids.join();

export interface SideVerdict {
  id: string;
  key: Link | null;
  model: Link | null;
  ok: boolean | null;
}

export interface RecAnswer {
  answered: boolean;
  bank: Map<string, SideVerdict>;
  book: Map<string, SideVerdict>;
  diffs: Array<{ id: string; want: { reason: string; amount: number | null } | null; got: { reason: string; amount: number | null } | null; ok: boolean | null }>;
  balances: Array<{ month: string; want: number | null; got: number | null; ok: boolean | null }>;
}

export function recAnswer(q: RecQuestion, input: CaseVisualInput): RecAnswer {
  const key = keyOf(input) ?? {};
  const ra = input.replies.length ? readAnswer(input) : null;
  const ans = ra?.parsed;
  const answered = isObj(ans);
  const side = (k: string, lines: Array<{ id: string; prior?: boolean }>): Map<string, SideVerdict> => {
    const out = new Map<string, SideVerdict>();
    for (const l of lines) {
      const kk = l.prior ? 'prior' : k;
      const kl = link(pickId(key[kk], l.id));
      const ml = answered ? link(pickId(pick(ans, kk), l.id)) : null;
      const v = answered ? ra!.verdict(`${kk}.${l.id}`) : null;
      out.set(l.id, { id: l.id, key: kl, model: ml, ok: !answered ? null : (v ?? sameLink(kl, ml)) });
    }
    return out;
  };
  const kd = isObj(key.differences) ? key.differences : {};
  const md = answered ? pick(ans, 'differences') : undefined;
  const ids = new Set([...Object.keys(kd), ...(isObj(md) ? Object.keys(md).map((x) => x.toUpperCase()) : [])]);
  const diff = (v: unknown) => (isObj(v) ? { reason: String(pick(v, 'reason') ?? '').toLowerCase(), amount: num(pick(v, 'amount')) } : null);
  const diffs = [...ids].map((id) => {
    const want = diff(kd[id]);
    const got = answered ? diff(pickId(md, id)) : null;
    const ok = !answered ? null : !!want && !!got && want.reason === got.reason && got.amount !== null && Math.abs(got.amount - (want.amount ?? 0)) < 0.005;
    return { id, want, got, ok };
  });
  const kb = key.adjusted_balance;
  const mb = answered ? pick(ans, 'adjusted_balance') : undefined;
  const balances = isObj(kb)
    ? Object.entries(kb).map(([month, w]) => {
        const got = isObj(mb) ? num(pick(mb, month)) : null;
        const want = num(w);
        return { month, want, got, ok: !answered ? null : (ra!.verdict(`adjusted_balance.${month}`) ?? (got !== null && want !== null && Math.abs(got - want) < 0.005)) };
      })
    : [{ month: q.months[0] ?? '', want: num(kb), got: answered ? num(mb) : null, ok: !answered ? null : (ra!.verdict('adjusted_balance') ?? (num(mb) !== null && Math.abs(num(mb)! - (num(kb) ?? 0)) < 0.005)) }];
  return { answered, bank: side('bank', q.bank), book: side('ledger', q.book), diffs, balances };
}

export function recSentence(a: RecAnswer): string {
  if (!a.answered) return 'No answer could be read from the reply, so nothing scored.';
  const all = [...a.bank.values(), ...a.book.values()];
  const wrong = all.filter((v) => v.ok === false).length;
  const dWrong = a.diffs.filter((d) => d.ok === false).length;
  const bWrong = a.balances.filter((b) => b.ok === false);
  if (!wrong && !dWrong && !bWrong.length) return `Matched or explained all ${all.length} lines and landed ${a.balances.length > 1 ? `every month’s balance` : 'the adjusted balance'} to the penny.`;
  const bits: string[] = [];
  if (wrong) bits.push(`${wrong} of ${all.length} lines matched or explained wrongly`);
  if (dWrong) bits.push(`${dWrong} difference${dWrong > 1 ? 's' : ''} wrong`);
  if (bWrong.length) bits.push(a.balances.length > 1 ? `${bWrong.length} of ${a.balances.length} month balances wrong` : `adjusted balance £${bWrong[0]!.got?.toFixed(2) ?? '—'} instead of £${bWrong[0]!.want?.toFixed(2)}`);
  return `${bits.join('; ').replace(/^./, (c) => c.toUpperCase())}.`;
}

export type { Obj };
