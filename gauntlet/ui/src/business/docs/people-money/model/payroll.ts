/**
 * Payslips (Check the Payslips, work.payroll-check, and its ladder, work.payroll-ladder): the bureau's draft
 * payslips read into payslips (payments, deductions, net pay, year to date), month by month, with the employee
 * records and the month's changes. The correct figures come only from the answer key; a field is circled where
 * the bureau's draft differs from the key, and the model's own flag and corrections are judged against the key.
 */
import type { CaseVisualInput } from '../../../../../../src/presenter/visuals/common.ts';
import { idsOf, isObj, keyOf, num, pick, pickId, readAnswer, sameId, type Obj } from './common.ts';

export const SLIP_FIELDS = ['gross', 'tax', 'ni', 'pension', 'student_loan', 'net'] as const;
export type SlipField = (typeof SLIP_FIELDS)[number];
export const SLIP_LABEL: Record<SlipField, string> = { gross: 'Gross pay', tax: 'PAYE tax', ni: 'Employee NI', pension: 'Pension', student_loan: 'Student loan', net: 'Net pay' };
const SLIP_LINE: Record<SlipField, RegExp> = { gross: /^GROSS PAY/i, tax: /^PAYE tax/i, ni: /^Employee NI/i, pension: /^Pension \(employee\)/i, student_loan: /^Student loan/i, net: /^NET PAY/i };

export interface Payslip {
  id: string;
  name: string;
  company: string;
  period: string;
  payDate: string;
  taxCode: string;
  niCat: string;
  /** Payment lines before GROSS PAY ("Basic pay 3445.83", "Overtime 9 hrs @ 1.0 190.85"). */
  payments: Array<{ label: string; amount: number }>;
  draft: Record<SlipField, number | null>;
  ytd: string;
}

export interface PayMonth {
  month: string;
  slips: Payslip[];
  bureauNi: number | null;
  changes: string[];
}

export interface Employee {
  id: string;
  name: string;
  role: string;
  record: string;
}

export interface PayrollQuestion {
  company: string;
  from: string;
  staff: Employee[];
  months: PayMonth[];
  late: string[];
  ladder: boolean;
}

function readSlip(block: string): Payslip | null {
  const lines = block.split('\n').filter((l) => l.trim());
  const head = lines[0]?.split('|').map((x) => x.trim()) ?? [];
  if (head.length < 6 || !/Payslip/.test(head[1] ?? '')) return null;
  const who = head[2]!.match(/^(E\d+)\s+(.+)$/);
  if (!who) return null;
  const draft = { gross: null, tax: null, ni: null, pension: null, student_loan: null, net: null } as Record<SlipField, number | null>;
  const payments: Payslip['payments'] = [];
  let ytd = '';
  let before = true;
  for (const l of lines.slice(1)) {
    const t = l.trim();
    if (/^Year to date/i.test(t)) {
      ytd = t;
      continue;
    }
    const m = t.match(/^(.+?)\s{2,}(-?[\d,]+\.\d\d)$/);
    if (!m) continue;
    const f = SLIP_FIELDS.find((k) => SLIP_LINE[k].test(m[1]!));
    const v = Number(m[2]!.replace(/,/g, ''));
    if (f) {
      draft[f] = v;
      if (f === 'gross') before = false;
    } else if (before) payments.push({ label: m[1]!.trim(), amount: v });
  }
  return {
    id: who[1]!,
    name: who[2]!.trim(),
    company: head[0]!,
    period: head[3]!,
    payDate: head[4]!.replace(/^Pay date\s*/, ''),
    taxCode: head[5]!.replace(/^Tax code\s*/, ''),
    niCat: (head[6] ?? '').replace(/^NI\s*/, ''),
    payments,
    draft,
    ytd,
  };
}

function block(prompt: string, head: RegExp): string {
  const m = prompt.match(head);
  if (!m || m.index === undefined) return '';
  const rest = prompt.slice(m.index + m[0].length);
  const end = rest.search(/\n\n[A-Z][A-Z ,()0-9/-]{8,}\n|\n\nYOUR ANSWER/);
  return end < 0 ? rest : rest.slice(0, end);
}

export function payrollQuestion(prompt: string): PayrollQuestion | null {
  if (!/DRAFT PAYSLIPS FROM THE BUREAU/.test(prompt)) return null;
  const from = prompt.match(/^From:\s*(.+)$/m)?.[1]?.trim() ?? '';
  const staff: Employee[] = [...block(prompt, /\nEMPLOYEE RECORDS[^\n]*\n/).matchAll(/^\s{2}(E\d+) (.+?) \(([^)]+)\), (.+)$/gm)].map((m) => ({ id: m[1]!, name: m[2]!, role: m[3]!, record: m[4]! }));
  const months: PayMonth[] = [];
  const re = /\nDRAFT PAYSLIPS FROM THE BUREAU(?:, ([A-Z]+ \d{4}))?\n/g;
  for (const m of prompt.matchAll(re)) {
    const start = m.index! + m[0].length;
    const rest = prompt.slice(start);
    const end = rest.search(/\n\n(?:CHANGES|DRAFT PAYSLIPS|LATE ITEMS|YOUR ANSWER)/);
    const body = end < 0 ? rest : rest.slice(0, end);
    const slips = body
      .split(/\n\s*\n/)
      .map(readSlip)
      .filter((x): x is Payslip => !!x);
    const monthName = m[1] ? m[1].charAt(0) + m[1].slice(1).toLowerCase() : (slips[0]?.period.match(/\(([^)]+)\)/)?.[1] ?? '');
    const ni = body.match(/Bureau summary[^:]*: employer NI(?: for the month)? £([\d,]+\.\d\d)/)?.[1];
    // The month's changes: the CHANGES block just before this month's drafts.
    const before = prompt.slice(0, m.index!);
    const ch = before.lastIndexOf('\nCHANGES');
    const changes = ch < 0 ? [] : before.slice(ch).split('\n').slice(2).map((l) => l.trim()).filter(Boolean);
    months.push({ month: monthName.replace(/^(\w)(\w+)/, (_, a: string, b: string) => a.toUpperCase() + b.toLowerCase()), slips, bureauNi: ni ? Number(ni.replace(/,/g, '')) : null, changes });
  }
  if (!months.length || !months[0]!.slips.length) return null;
  const late = block(prompt, /\nLATE ITEMS[^\n]*\n/).split('\n').map((l) => l.trim()).filter((l) => l && l !== 'none');
  return { company: months[0]!.slips[0]!.company, from, staff, months, late, ladder: months.length > 1 || /"months"/.test(prompt) };
}

export interface SlipVerdict {
  month: string;
  slip: Payslip;
  /** The key says the bureau's draft is wrong. */
  keyWrong: boolean;
  correct: Record<SlipField, number | null>;
  /** The model listed it as wrong. */
  flagged: boolean | null;
  given: Record<SlipField, number | null> | null;
  fieldOk: Record<SlipField, boolean | null>;
  right: boolean;
  /** Correct net minus the draft's net (negative: the draft overpays). */
  netGap: number | null;
}

export interface MonthVerdict {
  month: string;
  slips: SlipVerdict[];
  keyNi: number | null;
  modelNi: number | null;
  niOk: boolean | null;
  bureauNi: number | null;
}

export interface PayrollAnswer {
  answered: boolean;
  months: MonthVerdict[];
  ytd: Array<{ id: string; want: Obj; got: Obj | null; ok: boolean | null }>;
}

function monthObj(root: unknown, month: string, ladder: boolean): unknown {
  if (!ladder) return root;
  const ms = pick(root, 'months');
  if (!isObj(ms)) return undefined;
  return ms[month] ?? Object.entries(ms).find(([k]) => k.toLowerCase().replace(/\s+/g, ' ') === month.toLowerCase())?.[1];
}

export function payrollAnswer(q: PayrollQuestion, input: CaseVisualInput): PayrollAnswer {
  const key = keyOf(input) ?? {};
  const ra = input.replies.length ? readAnswer(input) : null;
  const ans = ra?.parsed;
  const answered = isObj(ans);
  const months = q.months.map((pm): MonthVerdict => {
    const km = monthObj(key, pm.month, q.ladder);
    const mm = answered ? monthObj(ans, pm.month, q.ladder) : undefined;
    const kWrong = idsOf(pick(km, 'wrong_payslips'));
    const mWrong = answered ? idsOf(pick(mm, 'wrong_payslips')) : [];
    const kCorr = pick(km, 'corrected');
    const mCorr = answered ? pick(mm, 'corrected') : undefined;
    const prefix = q.ladder ? `months.${pm.month}.` : '';
    const slips = pm.slips.map((slip): SlipVerdict => {
      const keyWrong = kWrong.some((x) => sameId(x, slip.id));
      const kc = pickId(kCorr, slip.id);
      const correct = Object.fromEntries(SLIP_FIELDS.map((f) => [f, keyWrong ? num(pick(kc, f)) : slip.draft[f]])) as Record<SlipField, number | null>;
      const flagged = !answered ? null : mWrong.some((x) => sameId(x, slip.id));
      const mc = pickId(mCorr, slip.id);
      const given = isObj(mc) ? (Object.fromEntries(SLIP_FIELDS.map((f) => [f, num(pick(mc, f))])) as Record<SlipField, number | null>) : null;
      const fieldOk = Object.fromEntries(
        SLIP_FIELDS.map((f) => {
          if (!answered || !keyWrong) return [f, null];
          const v = ra!.verdict(`${prefix}corrected.${slip.id}.${f}`);
          return [f, v ?? (given?.[f] !== null && given?.[f] !== undefined && correct[f] !== null && Math.abs(given[f]! - correct[f]!) < 0.005)];
        }),
      ) as Record<SlipField, boolean | null>;
      const right = answered && flagged === keyWrong && (!keyWrong || SLIP_FIELDS.every((f) => fieldOk[f]));
      const netGap = keyWrong && correct.net !== null && slip.draft.net !== null ? Math.round((correct.net - slip.draft.net) * 100) / 100 : null;
      return { month: pm.month, slip, keyWrong, correct, flagged, given, fieldOk, right, netGap };
    });
    const keyNi = num(pick(km, 'employer_ni_total'));
    const modelNi = answered ? num(pick(mm, 'employer_ni_total')) : null;
    const niOk = !answered ? null : (ra!.verdict(`${prefix}employer_ni_total`) ?? (modelNi !== null && keyNi !== null && Math.abs(modelNi - keyNi) < 0.005));
    return { month: pm.month, slips, keyNi, modelNi, niOk, bureauNi: pm.bureauNi };
  });
  const ky = isObj(key.year_to_date) ? key.year_to_date : null;
  const my = answered ? pick(ans, 'year_to_date') : undefined;
  const ytd = ky
    ? Object.entries(ky).map(([id, want]) => {
        const got = pickId(my, id);
        const ok = !answered ? null : isObj(got) && isObj(want) && Object.keys(want).every((f) => ra!.verdict(`year_to_date.${id}.${f}`) ?? num(pick(got, f)) === num(want[f]));
        return { id, want: want as Obj, got: isObj(got) ? got : null, ok };
      })
    : [];
  return { answered, months, ytd };
}

export const gapText = (g: number) => (g > 0 ? `underpaid by £${g.toFixed(2)}` : `overpaid by £${(-g).toFixed(2)}`);

export function payrollSentence(a: PayrollAnswer): string {
  if (!a.answered) return 'No answer could be read from the reply, so nothing scored.';
  const all = a.months.flatMap((m) => m.slips);
  const bad = all.filter((s) => s.keyWrong);
  const caught = bad.filter((s) => s.flagged).length;
  const fixed = bad.filter((s) => s.right).length;
  const falseAlarms = all.filter((s) => !s.keyWrong && s.flagged).length;
  const niWrong = a.months.filter((m) => m.niOk === false).length;
  const where = a.months.length > 1 ? ` over ${a.months.length} months` : '';
  if (all.every((s) => s.right) && !niWrong && a.ytd.every((y) => y.ok !== false)) return `Found all ${bad.length} wrong payslips${where} and put every figure right to the penny, employer NI included.`;
  const bits = [`caught ${caught} of the ${bad.length} wrong payslips${where}`];
  if (caught > fixed) bits.push(`${caught - fixed} with a figure still wrong`);
  if (falseAlarms) bits.push(`flagged ${falseAlarms} correct one${falseAlarms > 1 ? 's' : ''}`);
  const ytdWrong = a.ytd.filter((y) => y.ok === false).length;
  if (ytdWrong) bits.push(`got ${ytdWrong} year-to-date total${ytdWrong > 1 ? 's' : ''} wrong`);
  if (niWrong) bits.push(`employer NI wrong in ${niWrong === a.months.length && niWrong === 1 ? 'the month' : `${niWrong} month${niWrong > 1 ? 's' : ''}`}`);
  return `It ${bits.join(', ')}.`;
}
