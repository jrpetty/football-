/**
 * Expense claims (Expense Policy Check, work.expense-audit, and its ladder, work.expenses-ladder): every claim
 * form with its lines (date, merchant, what it was, amount, VAT, alcohol, receipt or not, approval reference),
 * month by month for the ladder. Which lines break the policy, and what to pay, come only from the answer key;
 * the model's flags are compared with it line by line, with the totals per person.
 */
import type { CaseVisualInput } from '../../../../../../src/presenter/visuals/common.ts';
import { idsOf, isObj, keyOf, num, pick, pickId, readAnswer, sameId, type Obj } from './common.ts';

export interface ExpLine {
  id: string;
  date: string;
  merchant: string;
  what: string;
  amount: string;
  vat: string | null;
  alcohol: string | null;
  receipt: 'yes' | 'no' | 'not needed';
  approval: string | null;
  extra: string[];
}

export interface Claim {
  id: string;
  who: string;
  dept: string;
  submitted: string;
  note: string;
  month: string;
  lines: ExpLine[];
}

export interface ExpensesQuestion {
  months: string[];
  claims: Claim[];
  register: string[];
  rates: string;
  ladder: boolean;
}

function readLine(id: string, rest: string): ExpLine {
  const parts = rest.split(' | ').map((x) => x.trim());
  const [date = '', merchant = '', what = '', amount = '', ...more] = parts;
  const l: ExpLine = { id, date, merchant, what, amount, vat: null, alcohol: null, receipt: 'no', approval: null, extra: [] };
  for (const m of more) {
    if (/^VAT /.test(m)) l.vat = m.replace(/^VAT\s*/, '');
    else if (/includes alcohol/i.test(m)) l.alcohol = m.replace(/^includes alcohol\s*/i, '');
    else if (/^receipt attached/i.test(m)) l.receipt = 'yes';
    else if (/no receipt needed/i.test(m)) l.receipt = 'not needed';
    else if (/^no receipt/i.test(m)) l.receipt = 'no';
    else if (/ref /i.test(m)) l.approval = m;
    else l.extra.push(m);
  }
  return l;
}

export function expensesQuestion(prompt: string): ExpensesQuestion | null {
  if (!/non_compliant/.test(prompt) || !/\nClaim C-\d+/.test(prompt)) return null;
  const claims: Claim[] = [];
  const months: string[] = [];
  let month = '';
  const register: string[] = [];
  for (const line of prompt.split('\n')) {
    const mh = line.match(/^══ (.+?) batch ══/);
    if (mh) {
      month = mh[1]!;
      months.push(month);
      continue;
    }
    const ch = line.match(/^Claim (C-\d+) — (.+?) \((.+?)\), submitted ([^.]+)\.\s*(.*)$/);
    if (ch) {
      claims.push({ id: ch[1]!, who: ch[2]!, dept: ch[3]!, submitted: ch[4]!, note: ch[5]!.trim(), month, lines: [] });
      continue;
    }
    const ln = line.match(/^\s+(L\d+) \| (.+)$/);
    if (ln && claims.length) {
      claims[claims.length - 1]!.lines.push(readLine(ln[1]!, ln[2]!));
      continue;
    }
    const rg = line.match(/^\s+- (.+\((?:ENT|LATE)\).*)$/);
    if (rg) register.push(rg[1]!);
  }
  if (!claims.length) return null;
  const rates = prompt.match(/Rate table[^:]*: ([^\n]+)/)?.[1] ?? '';
  return { months, claims, register, rates, ladder: months.length > 0 };
}

export const RULE_LABEL: Record<string, string> = {
  LATE: 'Late claim',
  DUPLICATE: 'Duplicate',
  NO_RECEIPT: 'No receipt',
  ALCOHOL: 'Alcohol',
  MEAL_CAP: 'Over meal limit',
  HOTEL_CAP: 'Over hotel limit',
  ENTERTAINMENT_CAP: 'Entertainment limit',
  RAIL_CLASS: 'First class',
  MILEAGE_RATE: 'Mileage rate',
};

export interface LineVerdict {
  line: ExpLine;
  claim: Claim;
  key: { rule: string; pay: number } | null;
  model: { rule: string; pay: number | null } | null;
  /** null: no answer. */
  right: boolean | null;
  ruleOk: boolean | null;
  payOk: boolean | null;
}

export interface TotalRow {
  label: string;
  want: number | null;
  got: number | null;
  ok: boolean | null;
}

export interface ExpensesAnswer {
  answered: boolean;
  lines: LineVerdict[];
  totals: TotalRow[];
}

function flag(v: unknown): { rule: string; pay: number | null } | null {
  if (!isObj(v)) return null;
  return { rule: String(pick(v, 'rule') ?? '').toUpperCase().replace(/[\s-]+/g, '_'), pay: num(pick(v, 'pay')) };
}

export function expensesAnswer(q: ExpensesQuestion, input: CaseVisualInput): ExpensesAnswer {
  const key = keyOf(input) ?? {};
  const ra = input.replies.length ? readAnswer(input) : null;
  const ans = ra?.parsed;
  const answered = isObj(ans);
  const knc = isObj(key.non_compliant) ? key.non_compliant : {};
  const mnc = answered ? pick(ans, 'non_compliant') : undefined;
  const mIds = answered ? idsOf(mnc) : [];
  const lines: LineVerdict[] = q.claims.flatMap((claim) =>
    claim.lines.map((line) => {
      const k = flag(knc[line.id]);
      const listed = mIds.some((x) => sameId(x, line.id));
      const m = listed ? (flag(pickId(mnc, line.id)) ?? { rule: '?', pay: null }) : null;
      let ruleOk: boolean | null = null;
      let payOk: boolean | null = null;
      if (answered && k) {
        ruleOk = ra!.verdict(`non_compliant.${line.id}.rule`) ?? (!!m && m.rule === k.rule);
        payOk = ra!.verdict(`non_compliant.${line.id}.pay`) ?? (!!m && m.pay !== null && Math.abs(m.pay - k.pay!) < 0.005);
      }
      const right = !answered ? null : k ? !!ruleOk && !!payOk : !listed;
      return { line, claim, key: k ? { rule: k.rule, pay: k.pay ?? 0 } : null, model: m, right, ruleOk, payOk };
    }),
  );
  const totals: TotalRow[] = [];
  const add = (label: string, want: unknown, got: unknown, path: string) => {
    const w = num(want);
    const g = answered ? num(got) : null;
    totals.push({ label, want: w, got: g, ok: !answered ? null : (ra!.verdict(path) ?? (g !== null && w !== null && Math.abs(g - w) < 0.005)) });
  };
  if (isObj(key.pay_by_employee)) for (const [who, v] of Object.entries(key.pay_by_employee)) add(`Pay ${who}`, v, pick(pick(ans, 'pay_by_employee'), who), `pay_by_employee.${who}`);
  if (isObj(key.paid_out))
    for (const [mo, ppl] of Object.entries(key.paid_out)) if (isObj(ppl)) for (const [who, v] of Object.entries(ppl)) add(`${mo} · ${who}`, v, pick(pick(pick(ans, 'paid_out'), mo), who), `paid_out.${mo}.${who}`);
  if (key.total_pay !== undefined) add('Total to pay', key.total_pay, pick(ans, 'total_pay'), 'total_pay');
  if (isObj(key.advances_left)) for (const [who, v] of Object.entries(key.advances_left)) add(`Advance left · ${who}`, v, pick(pick(ans, 'advances_left'), who), `advances_left.${who}`);
  if (isObj(key.miles_to_date)) for (const [who, v] of Object.entries(key.miles_to_date)) add(`Miles to date · ${who}`, v, pick(pick(ans, 'miles_to_date'), who), `miles_to_date.${who}`);
  add('VAT to reclaim', key.vat_reclaimable, pick(ans, 'vat_reclaimable'), 'vat_reclaimable');
  return { answered, lines, totals };
}

export function expensesSentence(a: ExpensesAnswer): string {
  if (!a.answered) return 'No answer could be read from the reply, so nothing scored.';
  const bad = a.lines.filter((l) => l.key);
  const caught = bad.filter((l) => l.model).length;
  const exact = bad.filter((l) => l.right).length;
  const extra = a.lines.filter((l) => !l.key && l.model).length;
  const totWrong = a.totals.filter((t) => t.ok === false).length;
  if (exact === bad.length && !extra && !totWrong) return `Found all ${bad.length} lines that break the policy, with the right rule and amount, and every total to the penny.`;
  const bits = [`flagged ${caught} of the ${bad.length} lines that break the policy`];
  if (caught > exact) bits.push(`${caught - exact} with the wrong rule or amount`);
  if (extra) bits.push(`cut ${extra} line${extra > 1 ? 's' : ''} that should be paid in full`);
  if (totWrong) bits.push(`${totWrong} total${totWrong > 1 ? 's' : ''} wrong`);
  return `It ${bits.join(', ')}.`;
}

export type { Obj };
