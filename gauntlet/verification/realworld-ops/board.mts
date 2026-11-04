/**
 * Numbers for the Board (work.board-report): three messy exports (orders, refunds, people and payroll) and the
 * finance memo that defines every KPI. The generator keeps the structured rows; `kpis()` applies the memo's
 * definitions in exact integer arithmetic (pence; rationals for VAT, FTE and percentages, rounded half up only at
 * the stated step) and refuses any figure that lands exactly on a rounding halfway point (so there is one right
 * answer whatever rounding habit a solver has). Careless readings (VAT left in, refunds by order date, cancelled
 * spellings missed, duplicates counted, the contractor counted as staff, the old Q2 figure…) are the same engine
 * with one definition misread.
 */
import { Rng, money, type Careless, type Difficulty } from './common.mts';

const Q_START = '2026-07-01';
const Q_END = '2026-09-30';
const Q_DAYS = 92;
const EXPORT_DATE = '2026-10-12';

interface Order {
  id: string;
  date: string;
  channel: 'Web' | 'Wholesale' | 'Internal';
  customer: string;
  status: 'Completed' | 'Cancelled' | 'CANCELED' | 'Void' | 'Dispatched';
  /** Pence as charged: Web incl. VAT, Wholesale ex VAT. */
  amount: number;
  cost: number;
  paid: string | null;
  /** Printed twice by the export. */
  dupe?: boolean;
  /** A late correction says this Web order was really Wholesale (amount ex VAT). */
  rekey?: boolean;
  /** A Wholesale order invoiced in euros: the amount (and any refund on it) is euro cents, ex VAT. */
  eur?: boolean;
}

interface Refund {
  id: string;
  order: string;
  date: string;
  /** Pence: incl. VAT for Web orders, ex VAT for Wholesale. */
  amount: number;
  status: 'Processed' | 'Reversed';
}

interface Person {
  name: string;
  dept: string;
  type: 'Employee' | 'Contractor';
  /** Contracted hours a week. */
  hours: number;
  start: string;
  leave: string | null;
  /** Payroll cost per month Jul, Aug, Sep (pence). */
  pay: [number, number, number];
  /** Unpaid leave inside the quarter (both days included): not employment for FTE. */
  unpaid?: [string, string];
}

interface Payline {
  name: string;
  month: 'Jul' | 'Aug' | 'Sep';
  amount: number;
  note?: string;
}

export interface Misread {
  vatLeftIn?: boolean;
  refundsByOrderDate?: boolean;
  cancelExactOnly?: boolean;
  dupesTwice?: boolean;
  testIncluded?: boolean;
  contractorCounted?: boolean;
  fullQuarterFte?: boolean;
  oldQ2?: boolean;
  rerunIncluded?: boolean;
  activeAnyOrder?: boolean;
  cashOrderDate?: boolean;
  reversedCounted?: boolean;
  rekeyMissed?: boolean;
  eurNotConverted?: boolean;
  mergesIgnored?: boolean;
  oncostsMissed?: boolean;
  unpaidLeaveIgnored?: boolean;
}

interface Data {
  orders: Order[];
  refunds: Refund[];
  people: Person[];
  payroll: Payline[];
  q2Restated: number;
  q2Old: number;
  /** Account pairs that are the same customer: [kept, duplicate]. */
  merges: Array<[string, string]>;
  /** Employer on-costs (NI and pension) per person and month. */
  oncosts: Payline[];
}

const inQ = (d: string) => d >= Q_START && d <= Q_END;

/** round(n / d) half up, for n, d > 0 integers; throws when exactly halfway. */
function divRound(n: number, d: number, what: string): number {
  if (d <= 0) throw new Error(`${what}: divide by ${d}`);
  const neg = n < 0;
  const a = Math.abs(n);
  if ((2 * a) % (2 * d) === d) throw new Error(`${what}: exactly halfway`);
  const r = Math.floor((2 * a + d) / (2 * d));
  return neg ? -r : r;
}

/** Ex-VAT pence of a Web amount: ÷ 1.2, rounded to the penny (half up). */
function exVat(p: number): number {
  return divRound(p * 5, 6, 'ex VAT');
}

function days(a: string, b: string): number {
  return Math.round((Date.parse(`${b}T00:00:00Z`) - Date.parse(`${a}T00:00:00Z`)) / 86400000) + 1;
}

export interface Kpis {
  net_revenue: number;
  gross_sales: number;
  refund_rate_pct: number;
  gross_margin_pct: number;
  orders: number;
  average_order_value: number;
  active_customers: number;
  net_revenue_growth_pct: number;
  average_fte: number;
  revenue_per_fte: number;
  payroll_cost: number;
  cash_conversion_pct: number;
}

/** The memo's definitions, applied exactly (or with one misread). */
export function kpis(d: Data, m: Misread = {}): Kpis {
  const cancelled = (o: Order) => (m.cancelExactOnly ? o.status === 'Cancelled' : o.status === 'Cancelled' || o.status === 'CANCELED' || o.status === 'Void');
  const test = (o: Order) => !m.testIncluded && (o.channel === 'Internal' || o.customer.startsWith('TEST'));
  const isWeb = (o: Order) => o.channel === 'Web' && !(o.rekey && !m.rekeyMissed);
  const conv = (o: Order, amt: number) => (o.eur && !m.eurNotConverted ? divRound(amt * 85, 100, 'EUR') : amt);
  const ex = (o: Order) => (isWeb(o) && !m.vatLeftIn ? exVat(o.amount) : conv(o, o.amount));
  const rows = m.dupesTwice ? d.orders.flatMap((o) => (o.dupe ? [o, o] : [o])) : d.orders;
  const valid = rows.filter((o) => inQ(o.date) && !cancelled(o) && !test(o));
  const byId = new Map(d.orders.map((o) => [o.id, o]));
  const refundEx = (r: Refund) => {
    const o = byId.get(r.order)!;
    return isWeb(o) && !m.vatLeftIn ? exVat(r.amount) : conv(o, r.amount);
  };
  const counted = (r: Refund) => (m.reversedCounted || r.status === 'Processed') && !test(byId.get(r.order)!);
  const qRefunds = d.refunds.filter((r) => counted(r) && (m.refundsByOrderDate ? inQ(byId.get(r.order)!.date) : inQ(r.date)));
  const gross = valid.reduce((a, o) => a + ex(o), 0);
  const refunds = qRefunds.reduce((a, r) => a + refundEx(r), 0);
  const net = gross - refunds;
  const cogs = valid.reduce((a, o) => a + o.cost, 0);
  // Active: a valid quarter order not refunded in full (refunds on it up to the export date).
  const refundedOn = (id: string) => d.refunds.filter((r) => r.order === id && r.status === 'Processed').reduce((a, r) => a + r.amount, 0);
  const canon = new Map(m.mergesIgnored ? [] : d.merges.map(([keep, dup]) => [dup, keep]));
  const activeSet = new Set(valid.filter((o) => m.activeAnyOrder || refundedOn(o.id) < o.amount).map((o) => canon.get(o.customer) ?? o.customer));
  // FTE: contracted hours ÷ 37.5, weighted by days employed in the quarter ÷ 92; contractors excluded.
  let fteNum = 0; // Σ hours×2 × days
  for (const p of d.people) {
    if (p.type === 'Contractor' && !m.contractorCounted) continue;
    const from = m.fullQuarterFte || p.start < Q_START ? Q_START : p.start;
    const to = m.fullQuarterFte || !p.leave || p.leave > Q_END ? Q_END : p.leave;
    if (from > Q_END || to < Q_START) continue;
    let worked = days(from, to);
    if (p.unpaid && !m.unpaidLeaveIgnored && !m.fullQuarterFte) worked -= days(p.unpaid[0], p.unpaid[1]);
    fteNum += p.hours * 2 * worked;
  }
  const fteDen = 75 * Q_DAYS; // 37.5 × 2 × 92
  const staffLine = (l: Payline) => m.contractorCounted || d.people.find((p) => p.name === l.name)?.type !== 'Contractor';
  const payroll =
    d.payroll.filter((l) => (m.rerunIncluded || l.note !== 'RERUN') && staffLine(l)).reduce((a, l) => a + l.amount, 0) +
    (m.oncostsMissed ? 0 : d.oncosts.filter(staffLine).reduce((a, l) => a + l.amount, 0));
  // Cash: ex-VAT value of every order paid in the quarter (whatever its order date), less refunds processed in the quarter.
  const paidRows = d.orders.filter((o) => !test(o) && o.paid && (m.cashOrderDate ? inQ(o.date) : inQ(o.paid)));
  const cash = paidRows.reduce((a, o) => a + ex(o), 0) - refunds;
  const q2 = m.oldQ2 ? d.q2Old : d.q2Restated;
  const pct1 = (a: number, b: number, what: string) => divRound(1000 * a, b, what) / 10;
  return {
    net_revenue: net / 100,
    gross_sales: gross / 100,
    refund_rate_pct: pct1(refunds, gross, 'refund rate'),
    gross_margin_pct: pct1(net - cogs, net, 'gross margin'),
    orders: valid.length,
    average_order_value: divRound(gross, valid.length, 'AOV') / 100,
    active_customers: activeSet.size,
    net_revenue_growth_pct: pct1(net - q2, q2, 'growth'),
    average_fte: divRound(100 * fteNum, fteDen, 'FTE') / 100,
    // Net revenue (£) ÷ unrounded average FTE, to the nearest pound: net/100 ÷ (fteNum/fteDen).
    revenue_per_fte: divRound(net * fteDen, 100 * fteNum, 'revenue per FTE'),
    payroll_cost: payroll / 100,
    cash_conversion_pct: pct1(cash, net, 'cash conversion'),
  };
}

// ─────────────────────────────── generator ───────────────────────────────

interface Spec {
  id: string;
  difficulty: Difficulty;
  seed: number;
  orders: number;
  refunds: number;
  people: number;
  mixedDates: boolean;
  moneyStrings: boolean;
  dupes: number;
  tests: number;
  cancels: number;
  reversed: number;
  contractor: boolean;
  rerun: boolean;
  oldQ2: boolean;
  rekey: boolean;
  allRefunded: boolean;
  /** Hard cases: euro invoices, merged customer accounts, employer on-costs, unpaid leave. */
  eur?: number;
  merges?: number;
  oncosts?: boolean;
  unpaid?: boolean;
}

const COMPANY = 'Northbank Roasters Ltd';

const FIRST = ['Amara', 'Bilal', 'Chloe', 'Dev', 'Eilidh', 'Femi', 'Grace', 'Hamza', 'Ines', 'Jun', 'Kofi', 'Leah', 'Mateusz', 'Niamh', 'Omar', 'Priya', 'Rhys', 'Sana', 'Tomasz', 'Uche', 'Wen', 'Yasmin', 'Zara', 'Aoife'];
const LAST = ['Okafor', 'Shah', 'Bennett', 'Nowak', 'MacLeod', 'Adeyemi', 'Hughes', 'Rahman', 'Silva', 'Chen', 'Mensah', 'Carter', 'Kowalski', 'Byrne', 'Haddad', 'Patel', 'Evans', 'Iqbal', 'Wójcik', 'Eze', 'Li', 'Karimi', 'Malik', 'Doyle'];
const DEPTS = ['Roastery', 'Packing', 'Sales', 'Finance', 'Customer service', 'Warehouse'];

function iso(base: string, add: number): string {
  return new Date(Date.parse(`${base}T00:00:00Z`) + add * 86400000).toISOString().slice(0, 10);
}

function genData(spec: Spec, rng: Rng): Data {
  const customers = Array.from({ length: Math.max(6, Math.round(spec.orders * 0.55)) }, (_, i) => `C${String(1001 + i * 7)}`);
  const orders: Order[] = [];
  for (let i = 0; i < spec.orders; i++) {
    const wholesale = rng.next() < 0.5;
    const date = iso('2026-06-20', rng.int(0, 110)); // 20 Jun – 8 Oct
    // A Web amount whose ÷ 1.2 lands on exactly half a penny is nudged by 1p, so per-order VAT rounding is never a coin toss.
    const raw = wholesale ? rng.int(150000, 950000) : rng.int(1800, 14500);
    const amount = !wholesale && raw % 6 === 3 ? raw + 1 : raw;
    const cost = Math.round((wholesale ? amount : (amount * 5) / 6) * (0.42 + 0.18 * rng.next()));
    const lag = wholesale ? rng.int(14, 45) : 0;
    const paidDate = iso(date, lag);
    const paid = wholesale && paidDate > EXPORT_DATE ? null : paidDate;
    orders.push({ id: `NB-${30100 + i * 3 + rng.int(0, 2)}`, date, channel: wholesale ? 'Wholesale' : 'Web', customer: rng.pick(customers), status: rng.next() < 0.5 ? 'Completed' : 'Dispatched', amount, cost, paid });
  }
  orders.sort((a, b) => a.date.localeCompare(b.date) || a.id.localeCompare(b.id));
  const qIdx = () => orders.map((o, i) => (inQ(o.date) ? i : -1)).filter((i) => i >= 0);
  // Cancellations, in all three spellings for the harder cases.
  const spellings: Order['status'][] = spec.difficulty === 'easy' ? ['Cancelled'] : ['Cancelled', 'CANCELED', 'Void'];
  for (const i of rng.sample(qIdx(), spec.cancels)) {
    orders[i]!.status = spellings[i % spellings.length]!;
    orders[i]!.paid = null;
  }
  for (let t = 0; t < spec.tests; t++) {
    const i = rng.pick(qIdx());
    orders.splice(i + 1, 0, { id: `NB-T${900 + t}`, date: orders[i]!.date, channel: t % 2 ? 'Internal' : 'Web', customer: t % 2 ? 'C0000' : 'TEST-QA', status: 'Completed', amount: rng.int(1000, 5000), cost: 0, paid: orders[i]!.date });
  }
  const live = () => orders.map((o, i) => (inQ(o.date) && o.status !== 'Cancelled' && o.status !== 'CANCELED' && o.status !== 'Void' && o.channel !== 'Internal' && !o.customer.startsWith('TEST') ? i : -1)).filter((i) => i >= 0);
  for (const i of rng.sample(live(), spec.dupes)) orders[i]!.dupe = true;
  if (spec.eur) {
    // Irish wholesale customers invoiced in euros (amounts nudged so £ conversion never lands on half a penny).
    for (const o of rng.sample(orders.filter((x) => x.channel === 'Wholesale' && !x.rekey), spec.eur)) {
      o.eur = true;
      o.amount = Math.round(o.amount / 0.85);
      if ((o.amount * 85) % 100 === 50) o.amount += 1;
      o.customer = `IE${o.customer.slice(1)}`;
    }
  }
  if (spec.rekey) {
    const web = live().filter((i) => orders[i]!.channel === 'Web' && orders[i]!.amount > 6000);
    orders[rng.pick(web)]!.rekey = true;
  }
  // Refunds: some on Q2 orders processed in July, some on Q3 orders processed in October, partial and full.
  const refunds: Refund[] = [];
  const refundable = orders.filter((o) => o.status !== 'Cancelled' && o.status !== 'CANCELED' && o.status !== 'Void' && o.channel !== 'Internal' && !o.customer.startsWith('TEST'));
  const picks = rng.sample(refundable, Math.min(spec.refunds, refundable.length));
  picks.forEach((o, k) => {
    const full = k % 3 === 0;
    const part = Math.round(o.amount * (0.1 + 0.4 * rng.next()));
    let amount = full ? o.amount : part % 6 === 3 ? part + 1 : part;
    if (o.eur && !full && (amount * 85) % 100 === 50) amount += 1;
    const date = iso(o.date, rng.int(3, 25));
    refunds.push({ id: `RF-${7700 + k * 5}`, order: o.id, date, amount, status: 'Processed' });
  });
  for (let k = 0; k < spec.reversed && k < refunds.length; k++) refunds[refunds.length - 1 - k]!.status = 'Reversed';
  if (spec.allRefunded) {
    // One customer whose only quarter order is refunded in full (so they are not active).
    const lone = live().map((i) => orders[i]!).filter((o) => orders.filter((x) => x.customer === o.customer && inQ(x.date)).length === 1 && !refunds.some((r) => r.order === o.id));
    const o = lone[0];
    if (o) refunds.push({ id: `RF-${7700 + refunds.length * 5}`, order: o.id, date: iso(o.date, 6) > Q_END ? Q_END : iso(o.date, 6), amount: o.amount, status: 'Processed' });
  }
  refunds.sort((a, b) => a.date.localeCompare(b.date) || a.id.localeCompare(b.id));
  // People.
  const people: Person[] = [];
  const names = rng.sample(FIRST, spec.people).map((f, i) => `${f} ${LAST[(i * 5 + spec.seed) % LAST.length]}`);
  names.forEach((name, i) => {
    const hours = rng.pick([37.5, 37.5, 37.5, 30, 22.5, 16, 24]);
    let start = iso('2024-01-01', rng.int(0, 500));
    let leave: string | null = null;
    if (i === 1 && spec.people > 4) leave = iso(Q_START, rng.int(20, 70));
    if (i === 2 && spec.people > 6) start = iso(Q_START, rng.int(10, 60));
    const monthly = Math.round((hours / 37.5) * rng.int(210000, 380000));
    const share = (from: string, to: string, mStart: string, mEnd: string) => {
      const a = from > mStart ? from : mStart;
      const b = to < mEnd ? to : mEnd;
      return a > b ? 0 : days(a, b) / days(mStart, mEnd);
    };
    const end = leave ?? '2099-01-01';
    const pay: [number, number, number] = [
      Math.round(monthly * share(start, end, '2026-07-01', '2026-07-31')),
      Math.round(monthly * share(start, end, '2026-08-01', '2026-08-31')),
      Math.round(monthly * share(start, end, '2026-09-01', '2026-09-30')),
    ];
    people.push({ name, dept: rng.pick(DEPTS), type: 'Employee', hours, start, leave, pay });
  });
  if (spec.contractor) people.push({ name: `${rng.pick(FIRST)} ${rng.pick(LAST)}`, dept: 'Sales', type: 'Contractor', hours: 37.5, start: '2026-05-01', leave: null, pay: [480000, 480000, 480000] });
  const payroll: Payline[] = [];
  for (const p of people) (['Jul', 'Aug', 'Sep'] as const).forEach((mo, k) => p.pay[k]! > 0 && payroll.push({ name: p.name, month: mo, amount: p.pay[k]! }));
  if (spec.rerun) {
    const aug = payroll.filter((l) => l.month === 'Aug');
    const r = aug[Math.min(2, aug.length - 1)]!;
    payroll.splice(payroll.indexOf(r) + 1, 0, { ...r, note: 'RERUN' });
  }
  if (spec.unpaid) {
    const p = people.find((x, i) => i >= 3 && x.type === 'Employee' && !x.leave && x.start < Q_START)!;
    const from = iso(Q_START, rng.int(15, 50));
    p.unpaid = [from, iso(from, rng.int(9, 24))];
  }
  const oncosts: Payline[] = [];
  if (spec.oncosts) for (const l of payroll) if (l.note !== 'RERUN') oncosts.push({ name: l.name, month: l.month, amount: Math.round(l.amount * (0.13 + 0.03 * rng.next())) });
  // Merged accounts: a customer who also ordered under a second account number.
  const merges: Array<[string, string]> = [];
  if (spec.merges) {
    const quarterCustomers = [...new Set(live().map((i) => orders[i]!.customer))];
    const pairs = rng.sample(quarterCustomers, Math.min(quarterCustomers.length - 1, spec.merges * 2));
    for (let k = 0; k + 1 < pairs.length && merges.length < spec.merges; k += 2) merges.push([pairs[k]!, pairs[k + 1]!]);
  }
  const tmp: Data = { orders, refunds, people, payroll, q2Restated: 0, q2Old: 0, merges, oncosts };
  const k = kpis({ ...tmp, q2Restated: 100, q2Old: 100 });
  const q2Restated = Math.round(k.net_revenue * 100 * (0.82 + 0.3 * rng.next()));
  const q2Old = q2Restated + rng.int(80000, 300000);
  return { ...tmp, q2Restated, q2Old };
}

const KEYS: Array<keyof Kpis> = ['net_revenue', 'gross_sales', 'refund_rate_pct', 'gross_margin_pct', 'orders', 'average_order_value', 'active_customers', 'net_revenue_growth_pct', 'average_fte', 'revenue_per_fte', 'payroll_cost', 'cash_conversion_pct'];

/** Points per KPI: where the traps sit (net revenue, active customers, FTE, cash) is worth more. */
export const BOARD_WEIGHTS: Record<string, number> = { net_revenue: 3, active_customers: 2, average_fte: 2, cash_conversion_pct: 2 };

function fmtDate(isoD: string, style: number): string {
  const [y, mo, dd] = isoD.split('-').map(Number);
  const M = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
  if (style === 1) return `${String(dd).padStart(2, '0')}/${String(mo).padStart(2, '0')}/${y}`;
  if (style === 2) return `${dd} ${M[mo! - 1]} ${y}`;
  return isoD;
}

function fmtMoney(p: number, asString: boolean): string {
  return asString ? money(p) : (p / 100).toFixed(2);
}

function writePrompt(spec: Spec, d: Data, rng: Rng): string {
  const L: string[] = [];
  const ds = (x: string) => (spec.mixedDates ? fmtDate(x, rng.int(0, 2)) : x);
  L.push(`From: Folasade Adebayo (Finance Director, ${COMPANY})`);
  L.push('Subject: Q3 board pack: the KPI table');
  L.push('');
  L.push('Hi, I need the KPI table for the Q3 board pack (quarter = 1 July to 30 September 2026, 92 days). The three exports are below, exactly as the systems produced them (exported Mon 12 Oct 2026). Please use the definitions in this memo, not anything the exports imply.');
  L.push('');
  L.push('FINANCE MEMO: KPI DEFINITIONS (these override everything else)');
  L.push('1. Valid order: an order dated in the quarter (by order date) whose status is not cancelled. The order system writes a cancellation as "Cancelled", "CANCELED" or "Void": all three are cancelled. Test orders (customer TEST-QA, or channel Internal) are never counted anywhere. The export sometimes prints the same order twice: an order ID counts once.');
  L.push('2. Amounts: Web amounts (orders and their refunds) include VAT at 20%; Wholesale amounts are already ex VAT. Every KPI uses ex-VAT money: for a Web amount, divide by 1.2 and round to the penny, order by order and refund by refund.');
  L.push('3. Gross sales = the ex-VAT amounts of all valid orders.');
  L.push('4. Refunds count in the quarter the refund was PROCESSED (refund date), whatever the order date. Only refunds with status Processed count; a Reversed refund was cancelled and counts nowhere.');
  L.push('5. Net revenue = gross sales − refunds processed in the quarter (ex VAT).');
  L.push('6. Refund rate = refunds processed in the quarter ÷ gross sales × 100.');
  L.push('7. Gross margin = (net revenue − cost of goods of the valid orders) ÷ net revenue × 100. Refunded goods are not restocked, so their cost stays in.');
  L.push('8. Orders = the number of valid orders. Average order value = gross sales ÷ orders.');
  L.push('9. Active customers = customers with at least one valid order in the quarter that has not been refunded in full (count every Processed refund on that order up to the export date).');
  L.push(`10. Net revenue growth = (Q3 net revenue − Q2 net revenue) ÷ Q2 net revenue × 100. Q2 net revenue, restated after the June credit note, is ${money(d.q2Restated)}.${spec.oldQ2 ? ` (Last quarter’s pack said ${money(d.q2Old)}; that figure was before the restatement and is wrong.)` : ''}`);
  L.push('11. Average FTE = for each employee, contracted hours a week ÷ 37.5 × days employed in the quarter (from their start date or 1 July, to their leaving date or 30 September, both days included) ÷ 92, added up. Contractors are not employees and are excluded from FTE and payroll.');
  L.push('12. Revenue per FTE = net revenue ÷ average FTE (use the unrounded average FTE).');
  L.push('13. Payroll cost = the payroll export’s lines for employees for July, August and September. A line marked RERUN is a duplicate of the line above it and is not counted.');
  L.push('14. Cash conversion = cash collected ÷ net revenue × 100, where cash collected = the ex-VAT amounts of every non-test order whose payment date falls in the quarter (whatever its order date; cancelled orders have no payment), less refunds processed in the quarter.');
  const eur = d.orders.some((o) => o.eur);
  if (eur) L.push('15. Euro invoices: our Irish wholesale customers are invoiced in euros (currency EUR in the orders export). An EUR amount, and any refund on that order, is in euros and ex VAT: convert it at €1 = £0.85, rounding to the penny order by order and refund by refund. Cost of goods is always in pounds. Everything else in this memo then applies as usual.');
  if (d.merges.length) L.push(`16. Duplicate customer accounts: the CRM clean-up found these pairs of accounts belong to the same customer, so each pair counts as ONE customer wherever customers are counted: ${d.merges.map(([a, b]) => `${a} = ${b}`).join('; ')}.`);
  if (d.oncosts.length) L.push('17. Payroll cost also includes the employer on-costs (employer National Insurance and pension) in export 3c, for employees only.');
  if (d.people.some((p) => p.unpaid)) L.push('18. Unpaid leave (export 3a, last column, both days included) is not employment: take those days out of that person’s days employed for FTE. Their payroll lines already reflect it.');
  L.push('Rounding: money to the penny, revenue per FTE to the nearest pound, percentages to 1 decimal place, average FTE to 2 decimal places; round only the final figure (half up).');
  L.push('');
  L.push(`EXPORT 1: ORDERS (order_id, order_date, channel, customer, status, amount${eur ? ', currency' : ''}, cost_of_goods_ex_vat, payment_date)`);
  for (const o of d.orders) {
    const amt = o.eur ? (o.amount / 100).toFixed(2) : fmtMoney(o.amount, spec.moneyStrings && rng.next() < 0.5);
    const line = `${o.id}, ${ds(o.date)}, ${o.channel}, ${o.customer}, ${o.status}, ${amt}${eur ? `, ${o.eur ? 'EUR' : 'GBP'}` : ''}, ${fmtMoney(o.cost, false)}, ${o.paid ? ds(o.paid) : ''}`;
    L.push(line);
    if (o.dupe) L.push(line);
  }
  L.push('');
  L.push('EXPORT 2: REFUNDS (refund_id, order_id, refund_date, amount, status)');
  const eurOrder = new Set(d.orders.filter((o) => o.eur).map((o) => o.id));
  for (const r of d.refunds) L.push(`${r.id}, ${r.order}, ${ds(r.date)}, ${eurOrder.has(r.order) ? `€${(r.amount / 100).toFixed(2)}` : fmtMoney(r.amount, spec.moneyStrings && rng.next() < 0.5)}, ${r.status}`);
  L.push('');
  const unpaidCol = d.people.some((p) => p.unpaid);
  L.push(`EXPORT 3a: PEOPLE (name, department, type, contracted_hours_per_week, start_date, leaving_date${unpaidCol ? ', unpaid_leave' : ''})`);
  for (const p of d.people) L.push(`${p.name}, ${p.dept}, ${p.type}, ${p.hours}, ${ds(p.start)}, ${p.leave ? ds(p.leave) : ''}${unpaidCol ? `, ${p.unpaid ? `${ds(p.unpaid[0])} to ${ds(p.unpaid[1])}` : ''}` : ''}`);
  L.push('');
  L.push('EXPORT 3b: PAYROLL (name, month, payroll_cost, note)');
  for (const l of d.payroll) L.push(`${l.name}, ${l.month} 2026, ${fmtMoney(l.amount, spec.moneyStrings && rng.next() < 0.5)}, ${l.note ?? ''}`);
  if (d.oncosts.length) {
    L.push('');
    L.push('EXPORT 3c: EMPLOYER ON-COSTS (name, month, employer_ni_and_pension)');
    for (const l of d.oncosts) L.push(`${l.name}, ${l.month} 2026, ${fmtMoney(l.amount, false)}`);
  }
  const rekeyed = d.orders.find((o) => o.rekey);
  if (rekeyed) {
    L.push('');
    L.push(`PS from Folasade, 10 minutes later: order ${rekeyed.id} was keyed as Web by mistake. It is a Wholesale order, so its amount (and any refund on it) is already ex VAT.`);
  }
  L.push('');
  L.push('Answer with the KPI table in this JSON format (money in pounds, percentages as numbers such as 12.3):');
  L.push('```json');
  L.push(`{${KEYS.map((k) => `"${k}": 0`).join(', ')}}`);
  L.push('```');
  L.push('Give exactly one answer as a single JSON block, one number per KPI. Do not offer alternatives: a value that gives two numbers, a range, or says "or" is marked wrong. You may explain your working before the JSON.');
  return L.join('\n');
}

export interface BoardCase {
  id: string;
  difficulty: Difficulty;
  prompt: string;
  notes: string;
  expected: Kpis;
  weights: Record<string, number>;
  careless: Careless[];
}

function buildCase(spec: Spec): BoardCase {
  for (let attempt = 0; attempt < 40; attempt++) {
    const rng = new Rng(spec.seed + attempt * 131);
    const d = genData(spec, rng);
    let expected: Kpis;
    const careless: Careless[] = [];
    try {
      expected = kpis(d);
      const mis: Array<[string, Misread]> = [
        ['VAT left in the Web amounts', { vatLeftIn: true }],
        ['refunds counted by order date', { refundsByOrderDate: true }],
        ['only "Cancelled" treated as cancelled', { cancelExactOnly: true }],
        ['duplicate export rows counted twice', { dupesTwice: true }],
        ['test orders included', { testIncluded: true }],
        ['contractor counted as staff', { contractorCounted: true }],
        ['every employee counted for the whole quarter', { fullQuarterFte: true }],
        ['old Q2 figure used for growth', { oldQ2: true }],
        ['RERUN payroll line counted', { rerunIncluded: true }],
        ['customers counted from any valid order', { activeAnyOrder: true }],
        ['cash taken from orders dated in the quarter', { cashOrderDate: true }],
        ['reversed refund counted', { reversedCounted: true }],
        ['late re-keying note missed', { rekeyMissed: true }],
        ['euro invoices taken as pounds', { eurNotConverted: true }],
        ['merged customer accounts counted twice', { mergesIgnored: true }],
        ['employer on-costs left out of payroll', { oncostsMissed: true }],
        ['unpaid leave counted as employment', { unpaidLeaveIgnored: true }],
      ];
      for (const [label, m] of mis) {
        const k = kpis(d, m);
        if (JSON.stringify(k) !== JSON.stringify(expected)) careless.push({ label, answer: { ...k } });
      }
      // The careless reader who makes every slip at once.
      careless.push({ label: 'every slip at once', answer: { ...kpis(d, Object.fromEntries(mis.map(([, m]) => [Object.keys(m)[0], true])) as Misread) } });
    } catch {
      continue; // a rounding halfway point (or empty set): try the next attempt
    }
    if (spec.difficulty !== 'easy' && careless.length < 8) continue;
    // No KPI may be 0 (a template of zeros must earn nothing).
    if (Object.values(expected).some((v) => v === 0)) continue;
    const prompt = writePrompt(spec, d, new Rng(spec.seed + attempt * 131 + 1));
    const notes = `[${spec.difficulty}] ${d.orders.length} order rows${d.orders.some((o) => o.dupe) ? ' (+ duplicates)' : ''}, ${d.refunds.length} refunds, ${d.people.length} people, ${d.payroll.length} payroll lines (generator attempt ${attempt}). Net revenue ${money(Math.round(expected.net_revenue * 100))}; misreadings that change the table: ${careless.length - 1}.`;
    return { id: spec.id, difficulty: spec.difficulty, prompt, notes, expected, weights: BOARD_WEIGHTS, careless };
  }
  throw new Error(`${spec.id}: no clean data set`);
}

const SPECS: Spec[] = [
  { id: 'b01', difficulty: 'easy', seed: 1101, orders: 18, refunds: 3, people: 4, mixedDates: false, moneyStrings: false, dupes: 0, tests: 0, cancels: 1, reversed: 0, contractor: false, rerun: false, oldQ2: false, rekey: false, allRefunded: false },
  { id: 'b02', difficulty: 'easy', seed: 1102, orders: 22, refunds: 4, people: 5, mixedDates: false, moneyStrings: false, dupes: 0, tests: 1, cancels: 1, reversed: 0, contractor: false, rerun: false, oldQ2: false, rekey: false, allRefunded: false },
  { id: 'b03', difficulty: 'easy', seed: 1103, orders: 25, refunds: 4, people: 5, mixedDates: false, moneyStrings: false, dupes: 1, tests: 1, cancels: 2, reversed: 0, contractor: true, rerun: false, oldQ2: false, rekey: false, allRefunded: false },
  { id: 'b04', difficulty: 'medium', seed: 1204, orders: 35, refunds: 6, people: 7, mixedDates: true, moneyStrings: false, dupes: 1, tests: 1, cancels: 3, reversed: 1, contractor: true, rerun: false, oldQ2: true, rekey: false, allRefunded: true },
  { id: 'b05', difficulty: 'medium', seed: 1205, orders: 40, refunds: 7, people: 8, mixedDates: true, moneyStrings: true, dupes: 2, tests: 2, cancels: 3, reversed: 1, contractor: true, rerun: true, oldQ2: true, rekey: false, allRefunded: true },
  { id: 'b06', difficulty: 'medium', seed: 1206, orders: 45, refunds: 8, people: 8, mixedDates: true, moneyStrings: true, dupes: 2, tests: 2, cancels: 4, reversed: 1, contractor: true, rerun: true, oldQ2: true, rekey: false, allRefunded: true },
  { id: 'b07', difficulty: 'hard', seed: 1307, eur: 3, merges: 2, oncosts: false, unpaid: true, orders: 70, refunds: 10, people: 10, mixedDates: true, moneyStrings: true, dupes: 3, tests: 2, cancels: 5, reversed: 2, contractor: true, rerun: true, oldQ2: true, rekey: true, allRefunded: true },
  { id: 'b08', difficulty: 'hard', seed: 1308, eur: 4, merges: 2, oncosts: true, unpaid: true, orders: 68, refunds: 12, people: 11, mixedDates: true, moneyStrings: true, dupes: 3, tests: 3, cancels: 6, reversed: 2, contractor: true, rerun: true, oldQ2: true, rekey: true, allRefunded: true },
  { id: 'b09', difficulty: 'hard', seed: 1309, eur: 5, merges: 3, oncosts: true, unpaid: true, orders: 105, refunds: 14, people: 12, mixedDates: true, moneyStrings: true, dupes: 4, tests: 3, cancels: 7, reversed: 2, contractor: true, rerun: true, oldQ2: true, rekey: true, allRefunded: true },
];

export function buildBoardCases(): BoardCase[] {
  return SPECS.map(buildCase);
}
