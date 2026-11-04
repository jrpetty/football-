// work.payroll-check — "Check the Payslips".
// A UK small business's monthly payroll. Every rate and threshold is stated in the prompt as this exercise's
// figures. calc() is the payroll engine (integer pence, stated rounding at every step). Draft payslips are produced
// by the SAME engine with planted mistake flags switched on for some employees (the way a mis-set payroll package
// goes wrong), so a draft is "wrong" exactly when one of its six figures differs from the correct calculation.

import { rng, D, addDays, iso, dmy, dMonY, gbp, num2, divHalfUp, divHalfDown, isWeekend, assert, ONE_ANSWER, monthLong } from './lib.mjs';

// ───────────────────────────── This exercise's figures (stated in the prompt) ─────────────────────────────
export const RATES = {
  bands: [[3770000, 2000], [12514000, 4000], [Infinity, 4500]], // annual taxable-pay band tops (pence) and rates (bp)
  pt: 104800, ust: 418900, st: 41700, ni8: 800, ni2: 200, er: 1500, // monthly NI thresholds; rates in bp
  qeLow: 52000, qeHigh: 418900, eePen: 500, erPen: 300, // pension qualifying earnings (monthly), rates in bp
  sl: { 1: 217200, 2: 237200, 4: 272800, PGL: 175000 },
  ssp: 11875, sspDay: 2375, sspLel: 12500,
  pensionAge: 66,
};

const FIRST = ['Marek', 'Aisha', 'Daniel', 'Siobhan', 'Kwame', 'Lucy', 'Tomasz', 'Hiba', 'Ryan', 'Ngozi', 'Ewa', 'Callum', 'Parveen', 'Jordan', 'Mei', 'Owain', 'Sana', 'Liam', 'Grace', 'Bilal', 'Kerry', 'Femi', 'Joanna', 'Arash',
  'Chloe', 'Dev', 'Ines', 'Kieran', 'Layla', 'Mateusz', 'Nia', 'Omar', 'Pippa', 'Rashid', 'Sophie', 'Theo', 'Uche', 'Vikram', 'Wendy', 'Yasmin', 'Zoltan', 'Bethan', 'Craig', 'Delia'];
const LAST = ['Nowak', 'Begum', 'Ferreira', 'Kelly', 'Mensah', 'Hartley', 'Lis', 'Saleh', 'Thornton', 'Okafor', 'Wisniewska', 'Fraser', 'Kaur', 'Pryce', 'Lin', 'Evans', 'Iqbal', 'Doyle', 'Asante', 'Rashid', 'Holroyd', 'Adebayo', 'Szymanska', 'Karimi',
  'Barlow', 'Chowdhury', 'Dimitrov', 'Ellis', 'Farrell', 'Gomez', 'Haworth', 'Ibe', 'Jansen', 'Kowalski', 'Lowe', 'Moran', 'Nash', 'Oduya', 'Pilling', 'Quigley', 'Rourke', 'Sato', 'Tan', 'Varga'];

export const EMPLOYERS = [
  { name: 'Pennine Plumbing & Heating Ltd', town: 'Huddersfield', boss: 'Gareth Lloyd', bureau: 'Colne Valley Payroll Bureau' },
  { name: 'Harbour & Hearth Coffee Ltd', town: 'Bristol', boss: 'Priya Raman', bureau: 'Avon Payroll Services' },
  { name: 'Brightsmile Dental Care Ltd', town: 'Leicester', boss: 'Rhiannon Price', bureau: 'Soar Payroll Ltd' },
];

// Feature kinds an employee can have; mistakes are planted on top.
export const SPECS = [
  { id: 'p01', tier: 'easy', seed: 51, emp: 0, pay: [2026, 11, 27], month: 8, staff: ['plain', 'plain-sl2', 'overtime', 'plain'], mistakes: { 2: 'overtime-1x' } },
  { id: 'p02', tier: 'easy', seed: 52, emp: 1, pay: [2026, 11, 27], month: 8, staff: ['plain', 'br-second-job', 'over-pension-age', 'hourly', 'plain-sl1'], mistakes: { 2: 'ni-cat-a-for-c', 4: 'sl-wrong-plan' } },
  { id: 'p03', tier: 'easy', seed: 53, emp: 2, pay: [2026, 12, 18], month: 9, staff: ['plain', 'apprentice', 'opted-out', 'overtime', 'plain-sl4'], mistakes: { 2: 'pension-opted-out', 3: 'pension-full-pay' } },
  { id: 'p04', tier: 'medium', seed: 54, emp: 0, pay: [2026, 12, 23], month: 9, staff: ['plain', 'sacrifice', 'k-code', 'plain-sl2-pgl', 'starter-m1', 'apprentice', 'overtime', 'director', 'half-penny'], mistakes: { 1: 'sacrifice-ni-before', 4: 'm1-as-cumulative', 5: 'employer-ni-apprentice', 7: 'director-ni-monthly', 8: 'employer-ni-half-up' } },
  { id: 'p05', tier: 'medium', seed: 55, emp: 1, pay: [2027, 1, 29], month: 10, staff: ['plain', 'ssp', 'mid-month-starter', 'br-second-job', 'plain-sl1', 'sacrifice', 'hourly', 'over-pension-age', 'pension-sacrifice', 'sunday-ot'], mistakes: { 1: 'ssp-no-waiting', 2: 'starter-calendar-days', 4: 'sl-rounded-penny', 8: 'pension-sacrifice-net-pay' } },
  { id: 'p06', tier: 'medium', seed: 56, emp: 2, pay: [2027, 1, 29], month: 10, staff: ['plain', 'k-code', 'opted-out', 'overtime', 'apprentice-turned-25', 'plain-sl2-pgl', 'code-change', 'sacrifice', 'sunday-ot', 'director'], mistakes: { 3: 'hourly-rounded-early', 4: 'apprentice-still-h', 6: 'old-code', 8: 'sunday-ot-1.5' } },
  { id: 'p07', tier: 'hard', seed: 57, emp: 0, pay: [2027, 2, 26], month: 11, staff: ['plain', 'k-code-cap', 'ssp', 'mid-month-starter', 'starter-p45', 'sacrifice', 'plain-sl4', 'over-pension-age', 'overtime-late-fix', 'opted-out', 'director', 'pension-sacrifice', 'sunday-ot', 'half-penny', 'higher-rate', 'apprentice'], mistakes: { 1: 'k-no-cap', 2: 'ssp-no-waiting', 4: 'p45-ignored', 8: 'overtime-uncorrected', 10: 'director-ni-monthly', 12: 'hourly-rounded-early', 13: 'employer-ni-half-up' } },
  { id: 'p08', tier: 'hard', seed: 58, emp: 1, pay: [2027, 2, 26], month: 11, staff: ['plain', 'code-change', 'apprentice', 'br-second-job', 'starter-m1', 'plain-sl2-pgl', 'sacrifice', 'ssp', 'hourly', 'over-pension-age', 'higher-rate', 'director', 'pension-sacrifice', 'sunday-ot', 'k-code', 'half-penny'], mistakes: { 1: 'old-code', 2: 'employer-ni-apprentice', 5: 'pgl-missing', 7: 'ssp-full-pay', 10: 'higher-rate-flat', 12: 'pension-sacrifice-net-pay', 13: 'sunday-ot-1.5' } },
  { id: 'p09', tier: 'hard', seed: 59, emp: 2, pay: [2027, 3, 26], month: 12, staff: ['plain', 'k-code-cap', 'mid-month-starter', 'starter-p45', 'apprentice-turned-25', 'overtime-late-fix', 'sacrifice', 'plain-sl1', 'opted-out', 'higher-rate', 'ssp', 'over-pension-age', 'director', 'pension-sacrifice', 'sunday-ot', 'half-penny'], mistakes: { 1: 'k-no-cap', 2: 'starter-calendar-days', 4: 'apprentice-still-h', 5: 'overtime-uncorrected', 6: 'sacrifice-ni-before', 9: 'net-typo', 12: 'director-ni-monthly', 15: 'employer-ni-half-up' } },
  { id: 'p10', tier: 'hard', seed: 60, emp: 0, pay: [2027, 3, 26], month: 12, staff: ['plain', 'code-change', 'k-code', 'starter-m1', 'starter-p45', 'apprentice', 'plain-sl2-pgl', 'sacrifice', 'ssp', 'higher-rate', 'overtime-late-fix', 'hourly', 'director', 'director', 'pension-sacrifice', 'pension-sacrifice', 'sunday-ot', 'sunday-ot', 'half-penny', 'half-penny', 'k-code-cap', 'mid-month-starter', 'apprentice-turned-25', 'over-pension-age', 'plain-sl1', 'opted-out', 'overtime', 'plain-sl4', 'plain', 'plain', 'overtime', 'plain-sl2', 'br-second-job', 'code-change', 'ssp', 'higher-rate', 'sacrifice', 'plain', 'apprentice', 'plain-sl1'], mistakes: { 1: 'old-code', 3: 'm1-as-cumulative', 5: 'employer-ni-apprentice', 6: 'pgl-missing', 8: 'ssp-no-waiting', 10: 'overtime-uncorrected', 12: 'director-ni-monthly', 14: 'pension-sacrifice-net-pay', 16: 'sunday-ot-1.5', 17: 'hourly-rounded-early', 18: 'employer-ni-half-up', 20: 'k-no-cap', 21: 'starter-calendar-days', 22: 'apprentice-still-h', 23: 'ni-cat-a-for-c', 24: 'sl-wrong-plan', 25: 'pension-opted-out', 26: 'overtime-1x', 27: 'sl-rounded-penny', 35: 'higher-rate-flat', 36: 'sacrifice-ni-before' } },
];

// ───────────────────────────── Payroll engine ─────────────────────────────
const ageOn = (dob, day) => {
  let a = day.getUTCFullYear() - dob.getUTCFullYear();
  if (day.getUTCMonth() < dob.getUTCMonth() || (day.getUTCMonth() === dob.getUTCMonth() && day.getUTCDate() < dob.getUTCDate())) a--;
  return a;
};

function workingDays(from, to) {
  let n = 0;
  for (let d = from; d <= to; d = addDays(d, 1)) if (!isWeekend(d)) n++;
  return n;
}

/** Tax due to date on taxable pay to date (pence, already rounded down to whole pounds), cumulative over m months. */
function taxToDate(taxable, m) {
  let tax = 0;
  let prevTop = 0;
  for (const [top, bp] of RATES.bands) {
    const lim = top === Infinity ? Infinity : Math.ceil((top / 100) * m / 12) * 100;
    const slice = Math.max(0, Math.min(taxable, lim) - prevTop);
    tax += (slice * bp) / 10000;
    prevTop = lim;
    if (taxable <= lim) break;
  }
  return Math.floor(tax);
}

function parseCode(code) {
  const m = /^(K)?(\d+)([LMNT])?$/.exec(code);
  if (code === 'BR' || code === 'D0' || code === 'NT' || code === '0T') return { kind: code };
  assert(m, `code ${code}`);
  return { kind: m[1] ? 'K' : 'L', num: Number(m[2]) };
}

/**
 * One employee's payslip. `f` = mistake flags (all off for the correct figures).
 * Returns pence: { gross, tax, ni, pension, sl, net, erNi, erPen, parts }.
 */
export function calc(world, e, f = {}) {
  const { pay, month } = world;
  const parts = [];
  let basic = 0;
  if (e.hourly) basic = Math.round(e.hours * e.rate);
  else {
    const monthly = divHalfUp(e.salary, 12);
    if (e.start) {
      const first = D(pay.getUTCFullYear(), pay.getUTCMonth() + 1, 1);
      const last = new Date(Date.UTC(pay.getUTCFullYear(), pay.getUTCMonth() + 1, 0));
      if (f.starterCalendarDays) basic = divHalfUp(monthly * (last.getUTCDate() - e.start.getUTCDate() + 1), last.getUTCDate());
      else basic = divHalfUp(monthly * workingDays(e.start, last), workingDays(first, last));
    } else basic = monthly;
  }
  parts.push(['Basic pay', basic]);
  let gross = basic;
  const otHours = f.overtimeUncorrected ? e.otDraft ?? e.ot : e.ot;
  const otPay = (hours, mult2) => {
    // mult2 = the multiplier x 2 (1.5x = 3, 2x = 4)
    if (f.hourlyRoundedEarly) return divHalfUp(divHalfUp(e.salary * 2, 52 * Math.round(e.weekly * 2)) * Math.round(hours * 2) * mult2, 4);
    return divHalfUp(e.salary * Math.round(hours * 2) * mult2, 52 * Math.round(e.weekly * 2) * 2);
  };
  if (otHours) {
    const mult = f.overtime1x ? 2 : 3;
    const ot = otPay(otHours, mult);
    parts.push([`Overtime ${otHours} hrs @ ${f.overtime1x ? '1.0' : '1.5'}`, ot]);
    gross += ot;
  }
  if (e.otSun) {
    const mult = f.sundayAs15 ? 3 : 4;
    const ot = otPay(e.otSun, mult);
    parts.push([`Overtime (Sunday/bank holiday) ${e.otSun} hrs @ ${f.sundayAs15 ? '1.5' : '2.0'}`, ot]);
    gross += ot;
  }
  if (e.sick) {
    const dayRate = { n: e.salary * e.sick, d: 260 };
    const ded = divHalfUp(dayRate.n, dayRate.d);
    if (!f.sspFullPay) {
      parts.push([`Unpaid sick days (${e.sick})`, -ded]);
      gross -= ded;
      const payable = f.sspNoWaiting ? e.sick : Math.max(0, e.sick - e.waitingLeft);
      const ssp = e.awe >= RATES.sspLel ? payable * RATES.sspDay : 0;
      parts.push([`SSP (${payable} day${payable === 1 ? '' : 's'})`, ssp]);
      gross += ssp;
    }
  }
  if (e.bonus) {
    parts.push(['Bonus', e.bonus]);
    gross += e.bonus;
  }
  let niable = gross;
  if (e.sacrifice) {
    parts.push([e.sacrificeName, -e.sacrifice]);
    gross -= e.sacrifice;
    niable = f.sacrificeNiBefore ? gross + e.sacrifice : gross;
  }
  // Pension (net pay arrangement): qualifying earnings on gross after sacrifice.
  let pension = 0;
  let erPen = 0;
  if (e.pensionSacrifice && !f.sacrificeAsNetPay) {
    // Pension by salary sacrifice: pay is reduced by 5% of qualifying earnings worked out before the sacrifice.
    const qe = Math.max(0, Math.min(gross, RATES.qeHigh) - RATES.qeLow);
    const sac = divHalfUp(qe * 500, 10000);
    parts.push(['Pension salary sacrifice (5%)', -sac]);
    gross -= sac;
    niable -= sac;
    erPen = divHalfUp(qe * 800, 10000);
  } else if (e.pension === 'enrolled' || e.pensionSacrifice || f.pensionOptedOut) {
    const base = f.pensionFullPay ? gross : Math.max(0, Math.min(gross, RATES.qeHigh) - RATES.qeLow);
    pension = divHalfUp(base * RATES.eePen, 10000);
    erPen = divHalfUp(base * RATES.erPen, 10000);
  }
  const taxablePay = gross - pension;
  // Income tax
  let tax = 0;
  const code = f.oldCode && e.oldCode ? e.oldCode : e.code;
  const c = parseCode(code);
  const nonCum = (e.m1 && !f.m1AsCumulative) || c.kind === 'BR' || c.kind === 'D0' || c.kind === 'NT';
  const prevPay = f.p45Ignored ? e.prevPayOwn : e.prevPay;
  const prevTax = f.p45Ignored ? e.prevTaxOwn : e.prevTax;
  if (c.kind === 'BR') tax = Math.floor((taxablePay * 2000) / 10000);
  else if (c.kind === 'D0') tax = Math.floor((taxablePay * 4000) / 10000);
  else if (c.kind === 'NT') tax = 0;
  else {
    const m = nonCum ? 1 : month;
    const payTD = nonCum ? taxablePay : prevPay + taxablePay;
    const allowAnnual = c.kind === '0T' ? 0 : c.num * 1000;
    const allowTD = divHalfUp(allowAnnual * m, 12);
    let taxableTD = c.kind === 'K' ? payTD + allowTD : payTD - allowTD;
    taxableTD = Math.max(0, Math.floor(taxableTD / 100) * 100);
    const due = f.higherRateFlat ? Math.floor(taxableTD * 0.2) : taxToDate(taxableTD, m);
    tax = due - (nonCum ? 0 : prevTax);
    if (c.kind === 'K' && !f.kNoCap) tax = Math.min(tax, Math.floor(taxablePay / 2));
  }
  // National Insurance on NIable pay
  const age = ageOn(e.dob, pay);
  let cat = age >= RATES.pensionAge ? 'C' : e.apprentice && age < 25 ? 'H' : 'A';
  if (f.niCatAForC) cat = 'A';
  if (f.apprenticeStillH) cat = 'H';
  const between = Math.max(0, Math.min(niable, RATES.ust) - RATES.pt);
  const above = Math.max(0, niable - RATES.ust);
  let ni = cat === 'C' ? 0 : divHalfDown(between * RATES.ni8 + above * RATES.ni2, 10000);
  const erCat = f.employerNiApprentice ? 'A' : cat;
  const erBase = erCat === 'H' ? Math.max(0, niable - RATES.ust) : Math.max(0, niable - RATES.st);
  let erNi = (f.employerHalfUp ? divHalfUp : divHalfDown)(erBase * RATES.er, 10000);
  if (e.director && !f.directorMonthly) {
    // Directors: annual earnings period, cumulative to date.
    const td = e.prevNiable + niable;
    ni = divHalfDown(Math.max(0, Math.min(td, 5027000) - 1257000) * RATES.ni8 + Math.max(0, td - 5027000) * RATES.ni2, 10000) - e.prevNi;
    erNi = divHalfDown(Math.max(0, td - 500000) * RATES.er, 10000) - e.prevErNi;
  }
  // Student loans on NIable pay
  let sl = 0;
  for (const plan of e.loans) {
    const usePlan = f.slWrongPlan && plan === 1 ? 2 : plan;
    const thr = RATES.sl[usePlan];
    const rate = plan === 'PGL' ? 600 : 900;
    if (plan === 'PGL' && f.pglMissing) continue;
    const raw = (Math.max(0, niable - thr) * rate) / 10000;
    sl += f.slRoundedPenny ? Math.round(raw) : Math.floor(raw / 100) * 100;
  }
  let net = gross - tax - ni - pension - sl;
  if (f.netTypo) net += 1000;
  return { gross, tax, ni, pension, sl, net, erNi, erPen, cat, parts, code };
}

const MISTAKE_FLAGS = {
  'overtime-1x': { overtime1x: true },
  'ni-cat-a-for-c': { niCatAForC: true },
  'sl-wrong-plan': { slWrongPlan: true },
  'pension-opted-out': { pensionOptedOut: true },
  'pension-full-pay': { pensionFullPay: true },
  'sacrifice-ni-before': { sacrificeNiBefore: true },
  'm1-as-cumulative': { m1AsCumulative: true },
  'employer-ni-apprentice': { employerNiApprentice: true },
  'ssp-no-waiting': { sspNoWaiting: true },
  'ssp-full-pay': { sspFullPay: true },
  'starter-calendar-days': { starterCalendarDays: true },
  'sl-rounded-penny': { slRoundedPenny: true },
  'apprentice-still-h': { apprenticeStillH: true },
  'old-code': { oldCode: true },
  'k-no-cap': { kNoCap: true },
  'p45-ignored': { p45Ignored: true },
  'overtime-uncorrected': { overtimeUncorrected: true },
  'pgl-missing': { pglMissing: true },
  'higher-rate-flat': { higherRateFlat: true },
  'net-typo': { netTypo: true },
  'director-ni-monthly': { directorMonthly: true },
  'pension-sacrifice-net-pay': { sacrificeAsNetPay: true },
  'sunday-ot-1.5': { sundayAs15: true },
  'hourly-rounded-early': { hourlyRoundedEarly: true },
  'employer-ni-half-up': { employerHalfUp: true },
};

// ───────────────────────────── Generator ─────────────────────────────
export function generate(spec) {
  const r = rng(spec.seed * 6151);
  const pay = D(...spec.pay);
  const employer = EMPLOYERS[spec.emp];
  const world = { spec, employer, pay, month: spec.month, staff: [], notices: [] };
  const first = r.shuffle(FIRST);
  const last = r.shuffle(LAST);
  const priorMonths = spec.month - 1;
  spec.staff.forEach((kind, i) => {
    const e = {
      id: `E${String(i + 1).padStart(2, '0')}`,
      nino: `QQ ${String(r.int(10, 99))} ${String(r.int(10, 99))} ${String(r.int(10, 99))} ${r.pick(['A', 'B', 'C', 'D'])}`,
      name: `${first[i]} ${last[i]}`,
      kind,
      role: r.pick(['Office administrator', 'Service engineer', 'Supervisor', 'Assistant', 'Coordinator', 'Technician', 'Team member', 'Accounts assistant']),
      dob: D(r.int(1972, 1999), r.int(1, 12), r.int(1, 28)),
      salary: r.int(230, 420) * 10000 + r.pick([0, 5000]),
      weekly: r.pick([37.5, 40, 37.5, 35]),
      code: '1257L',
      m1: false,
      loans: [],
      pension: 'enrolled',
      apprentice: false,
      ot: 0,
      sick: 0,
      bonus: 0,
      sacrifice: 0,
      start: null,
      p45: null,
    };
    const ageAt = (years) => addDays(D(pay.getUTCFullYear() - years, pay.getUTCMonth() + 1, Math.min(28, pay.getUTCDate())), -r.int(20, 300));
    switch (kind) {
      case 'plain-sl1': e.loans = [1]; break;
      case 'plain-sl2': e.loans = [2]; break;
      case 'plain-sl4': e.loans = [4]; break;
      case 'plain-sl2-pgl': e.loans = [2, 'PGL']; e.salary = r.int(330, 420) * 10000; break;
      case 'overtime':
      case 'overtime-late-fix':
        e.ot = r.pick([6, 7.5, 9, 10.5, 12]);
        if (kind === 'overtime-late-fix') {
          e.otDraft = e.ot;
          e.ot = e.ot + r.pick([1.5, 3, -1.5]);
          world.notices.push({ kind: 'ot-fix', id: e.id, was: e.otDraft, now: e.ot });
        }
        break;
      case 'br-second-job': e.code = 'BR'; e.salary = r.int(120, 180) * 10000; e.weekly = 20; break;
      case 'over-pension-age': e.dob = ageAt(r.int(66, 69)); break;
      case 'hourly': e.hourly = true; e.rate = r.pick([1221, 1250, 1305, 1340]); e.hours = r.pick([96, 112, 120.5, 138, 151.5]); e.salary = Math.round(e.rate * 37.5 * 52); break;
      case 'apprentice': e.apprentice = true; e.dob = ageAt(r.int(18, 23)); e.salary = r.int(150, 210) * 10000; e.role = 'Apprentice'; break;
      case 'apprentice-turned-25': {
        e.apprentice = true;
        e.role = 'Apprentice';
        e.salary = r.int(170, 220) * 10000;
        const b = addDays(pay, -r.int(3, 20));
        e.dob = D(b.getUTCFullYear() - 25, b.getUTCMonth() + 1, b.getUTCDate());
        break;
      }
      case 'opted-out': e.pension = 'opted out'; break;
      case 'sacrifice': e.sacrifice = r.pick([4167, 5000, 6250, 8333]); e.sacrificeName = 'Cycle to Work salary sacrifice'; break;
      case 'k-code': e.code = `K${r.int(150, 480)}`; break;
      case 'k-code-cap':
        // A part-time older worker whose State Pension is collected through a K code: the 50% limit bites.
        e.code = `K${r.int(1100, 1500)}`;
        e.salary = r.int(60, 84) * 10000;
        e.weekly = 16;
        e.dob = ageAt(r.int(67, 70));
        e.pension = 'not enrolled (earns below the £833 a month trigger)';
        e.role = 'Part-time ' + e.role.toLowerCase();
        break;
      case 'starter-m1': e.code = '1257L'; e.m1 = true; e.starter = 'B'; e.salary = r.int(240, 330) * 10000; break;
      case 'starter-p45': e.p45 = true; e.salary = r.int(260, 360) * 10000; break;
      case 'mid-month-starter': {
        const last2 = new Date(Date.UTC(pay.getUTCFullYear(), pay.getUTCMonth() + 1, 0));
        let s = addDays(last2, -r.int(9, 18));
        while (isWeekend(s)) s = addDays(s, 1);
        e.start = s;
        e.starter = 'A';
        break;
      }
      case 'ssp': e.sick = r.int(4, 8); e.awe = r.int(450, 800) * 100; e.waitingLeft = 3; break;
      case 'code-change': e.oldCode = '1257L'; e.code = r.pick(['1185L', '1100L', '1310L', '1383L']); world.notices.push({ kind: 'code', id: e.id, code: e.code }); break;
      case 'higher-rate': e.salary = r.int(560, 760) * 10000; e.loans = r.chance(0.5) ? [] : [2]; break;
      case 'director':
        e.director = true;
        e.role = 'Director';
        e.salary = r.int(18, 34) * 100000;
        if (r.chance(0.5)) e.bonus = r.int(10, 40) * 10000;
        break;
      case 'pension-sacrifice': e.pensionSacrifice = true; e.salary = r.int(260, 420) * 10000; break;
      case 'sunday-ot': e.ot = r.pick([4, 6, 7.5, 9]); e.otSun = r.pick([3, 4.5, 6, 7.5, 8]); break;
      case 'half-penny':
        // Pay chosen so that employer NI comes to an exact half penny (it rounds DOWN).
        for (let k = 0; k < 400; k++) {
          const sal = e.salary + k * 12;
          if (((divHalfUp(sal, 12) - RATES.st) * 15) % 100 === 50) {
            e.salary = sal;
            break;
          }
        }
        break;
      default: break;
    }
    // Year-to-date (previous periods of this tax year) from the payroll records.
    const monthlyGuess = divHalfUp(e.salary, 12);
    if (e.start || e.starter === 'B') {
      e.prevPay = 0;
      e.prevTax = 0;
    } else if (e.p45) {
      // Joined at the start of this month from another job: previous pay and tax come from the P45.
      e.prevPay = r.int(Math.floor(priorMonths * 1900), Math.floor(priorMonths * 2900)) * 100;
      e.prevTax = Math.max(0, taxToDate(Math.max(0, Math.floor((e.prevPay - divHalfUp(1257000 * priorMonths, 12)) / 100) * 100), priorMonths) - r.int(0, 3) * 100);
      e.prevPayOwn = 0;
      e.prevTaxOwn = 0;
      e.p45Leave = addDays(D(pay.getUTCFullYear(), pay.getUTCMonth() + 1, 1), -r.int(3, 10));
    } else {
      const prevTaxable = (monthlyGuess - (e.sacrifice || 0)) * priorMonths - (e.pension === 'enrolled' ? Math.round(priorMonths * Math.max(0, Math.min(monthlyGuess, RATES.qeHigh) - RATES.qeLow) * 0.05) : 0) + r.int(0, 3) * 2500;
      e.prevPay = prevTaxable;
      const pc = parseCode(e.oldCode ?? e.code);
      if (pc.kind === 'BR') e.prevTax = Math.floor(prevTaxable * 0.2);
      else {
        const allow = divHalfUp(pc.num * 1000 * priorMonths, 12);
        const t = pc.kind === 'K' ? prevTaxable + allow : prevTaxable - allow;
        e.prevTax = taxToDate(Math.max(0, Math.floor(t / 100) * 100), priorMonths);
        if (pc.kind === 'K') e.prevTax = Math.min(e.prevTax, Math.floor(prevTaxable / 2));
      }
    }
    if (e.director) {
      // Earlier months at the same salary (the bonus is this month's).
      e.prevNiable = monthlyGuess * priorMonths;
      e.prevPay = monthlyGuess * priorMonths - (e.pension === 'enrolled' ? Math.round(priorMonths * Math.max(0, Math.min(monthlyGuess, RATES.qeHigh) - RATES.qeLow) * 0.05) : 0);
      e.prevTax = taxToDate(Math.max(0, Math.floor((e.prevPay - divHalfUp(1257000 * priorMonths, 12)) / 100) * 100), priorMonths);
      e.prevNi = divHalfDown(Math.max(0, Math.min(e.prevNiable, 5027000) - 1257000) * RATES.ni8 + Math.max(0, e.prevNiable - 5027000) * RATES.ni2, 10000);
      e.prevErNi = divHalfDown(Math.max(0, e.prevNiable - 500000) * RATES.er, 10000);
    }
    if (e.pensionSacrifice) {
      const sac = divHalfUp(Math.max(0, Math.min(monthlyGuess, RATES.qeHigh) - RATES.qeLow) * 500, 10000);
      e.prevPay = (monthlyGuess - sac) * priorMonths;
      e.prevTax = taxToDate(Math.max(0, Math.floor((e.prevPay - divHalfUp(1257000 * priorMonths, 12)) / 100) * 100), priorMonths);
    }
    world.staff.push(e);
  });
  // Draft payslips: the correct engine with this case's planted mistakes switched on.
  for (const [idx, m] of Object.entries(spec.mistakes)) {
    const e = world.staff[Number(idx)];
    assert(MISTAKE_FLAGS[m], `mistake ${m}`);
    e.mistake = m;
  }
  for (const e of world.staff) {
    e.correct = calc(world, e);
    e.draft = calc(world, e, e.mistake ? MISTAKE_FLAGS[e.mistake] : {});
  }
  return world;
}

const SIX = ['gross', 'tax', 'ni', 'pension', 'sl', 'net'];
const differs = (a, b) => SIX.some((k) => a[k] !== b[k]);
const out6 = (p) => ({ gross: p.gross / 100, tax: p.tax / 100, ni: p.ni / 100, pension: p.pension / 100, student_loan: p.sl / 100, net: p.net / 100 });

export function solve(world) {
  const wrong = world.staff.filter((e) => differs(e.correct, e.draft));
  return {
    wrong_payslips: wrong.map((e) => e.id),
    corrected: Object.fromEntries(wrong.map((e) => [e.id, out6(e.correct)])),
    employer_ni_total: world.staff.reduce((s, e) => s + e.correct.erNi, 0) / 100,
  };
}

/** Careless reviewer: trusts the drafts entirely. */
export function careless(world) {
  return { wrong_payslips: [], corrected: {}, employer_ni_total: world.staff.reduce((s, e) => s + e.draft.erNi, 0) / 100 };
}

/** Flawed-but-diligent reviewer: recomputes everything but with chosen rules wrong. */
export function flawed(world, flags) {
  const wrong = [];
  const corrected = {};
  let er = 0;
  for (const e of world.staff) {
    const p = calc(world, e, flags);
    er += p.erNi;
    if (differs(p, e.draft)) {
      wrong.push(e.id);
      corrected[e.id] = out6(p);
    }
  }
  return { wrong_payslips: wrong, corrected, employer_ni_total: er / 100 };
}

/** Hard skills weigh more: each wrong payslip found is worth 2, its tax and NI 2 each, the employer NI total 4. */
export function caseScorer(world, base) {
  const sol = solve(world);
  const weights = { wrong_payslips: 2, employer_ni_total: 4 };
  for (const id of sol.wrong_payslips) {
    weights[`corrected.${id}.tax`] = 2;
    weights[`corrected.${id}.ni`] = 2;
  }
  return { ...base, weights };
}

export function scripted(world, sol) {
  return {
    careless: careless(world),
    flawed_new_rules: flawed(world, { directorMonthly: true, sacrificeAsNetPay: true, sundayAs15: true, employerHalfUp: true }),
    careful_human_slips: flawed(world, { employerHalfUp: true, hourlyRoundedEarly: true }),
    flawed_no_m1_no_cap: flawed(world, { m1AsCumulative: true, kNoCap: true }),
    flawed_sacrifice_and_overtime: flawed(world, { sacrificeNiBefore: true, overtimeUncorrected: true }),
    flawed_ssp_and_age: flawed(world, { sspNoWaiting: true, apprenticeStillH: true, oldCode: true }),
    flag_everything: { wrong_payslips: world.staff.map((e) => e.id), corrected: sol.corrected, employer_ni_total: sol.employer_ni_total },
  };
}

export function selfCheck(world, sol) {
  const bad = [];
  for (const e of world.staff) {
    const p = e.correct;
    if (p.net !== p.gross - p.tax - p.ni - p.pension - p.sl) bad.push(`${e.id} net`);
    if (p.net <= 0) bad.push(`${e.id} net not positive`);
    if (e.mistake && !['employer-ni-apprentice', 'apprentice-still-h', 'employer-ni-half-up'].includes(e.mistake) && !differs(e.correct, e.draft)) bad.push(`${e.id}: mistake ${e.mistake} changes nothing`);
    const erOnly = ['employer-ni-apprentice', 'apprentice-still-h', 'employer-ni-half-up'].includes(e.mistake);
    if (erOnly && e.correct.erNi === e.draft.erNi) bad.push(`${e.id}: ${e.mistake} changes nothing`);
    if (e.kind === 'k-code-cap' && calc(world, e, { kNoCap: true }).tax === p.tax) bad.push(`${e.id}: K cap does not bind`);
    if (e.kind === 'apprentice-turned-25' && p.cat !== 'A') bad.push(`${e.id}: should be A`);
    if (e.kind === 'over-pension-age' && p.cat !== 'C') bad.push(`${e.id}: should be C`);
    if (e.kind === 'higher-rate' && calc(world, e, { higherRateFlat: true }).tax === p.tax) bad.push(`${e.id}: not higher rate`);
    if (e.kind === 'ssp' && e.awe < RATES.sspLel) bad.push(`${e.id}: SSP not payable`);
  }
  if (!sol.wrong_payslips.length) bad.push('no wrong payslips');
  return bad;
}

export function summary(world, sol) {
  return `${world.staff.length} staff, wrong ${sol.wrong_payslips.join(' ')} (${world.staff.filter((e) => e.mistake).map((e) => `${e.id}:${e.mistake}`).join(', ')}), employer NI ${sol.employer_ni_total}`;
}

// ───────────────────────────── Rendering ─────────────────────────────
function codeText(e) {
  if (e.m1) return `${e.code} M1 (week 1/month 1 basis)`;
  return e.code;
}

export function render(world) {
  const { employer: emp, pay, month, spec } = world;
  const mon = monthLong(pay.getUTCMonth() + 1);
  const yr = pay.getUTCFullYear();
  const last = new Date(Date.UTC(yr, pay.getUTCMonth() + 1, 0));
  const firstDay = D(yr, pay.getUTCMonth() + 1, 1);
  const out = [];
  out.push(`From: ${emp.boss} (${emp.name})
To: you
Subject: ${mon} payroll - please check the bureau's drafts before I approve the FPS

Hi, ${emp.bureau} have sent the draft payslips for ${mon} ${yr} (tax month ${month}, pay day ${dMonY(pay)}). Our bookkeeper is off, so can you check every payslip against the employee records and this month's changes, using the figures below? Tell me which payslips are wrong, what the correct figures are, and the correct employer NI total for the month. Everything you need is in this email.`);
  out.push(`THIS EXERCISE'S RATES AND RULES (use these exactly; they may differ from HMRC's current figures)
Pay. Monthly salary = annual salary / 12, rounded to the penny (half up). Hourly rate for overtime = annual salary / 52 / contracted weekly hours (not rounded); overtime pay = hours x hourly rate x 1.5, rounded to the penny. Hourly-paid staff: hours x rate, rounded to the penny. A starter who joins during the month gets monthly salary x (working days from the start date to the month end) / (working days in the month); working days are Monday to Friday, bank holidays included; rounded to the penny. Unpaid sick day deduction = annual salary x days / 260, rounded to the penny.
SSP (company sick pay is SSP only). SSP is paid at £23.75 a day (£118.75 a week, 5 qualifying days) for qualifying days of sickness after the first 3 qualifying days of a period of sickness (waiting days), and only if average weekly earnings are at least £125.00. The sick days themselves are deducted as unpaid days.
Hours worked on a Sunday or a bank holiday are overtime at 2x the same hourly rate (not rounded); that overtime pay = hours x hourly rate x 2, rounded to the penny. Salary sacrifice (Cycle to Work) is taken off gross pay before tax, NI, pension and student loan. Gross pay on the payslip = pay after salary sacrifice. Pension by salary sacrifice (staff marked so): instead of paying 5% themselves, their pay is reduced by 5% of their qualifying earnings worked out on pay BEFORE that sacrifice (rounded to the penny, half up); the reduction is taken off gross pay before tax, NI and student loan, no employee pension is deducted, and the employer pays 8% of those qualifying earnings.
Pension (auto-enrolment, net pay arrangement). Qualifying earnings for the month = the part of gross pay above £520.00, counting pay only up to £4,189.00 (so: the lower of gross pay and £4,189.00, minus £520.00, never below zero). The employee pays 5% of qualifying earnings and the employer pays 3% of qualifying earnings, each rounded to the penny (half up). Opted-out staff pay nothing. Employee pension is deducted before tax (not before NI or student loan).
Income tax. Taxable pay = gross pay - employee pension. L codes: annual allowance = code number x £10 (1257L = £12,570). K codes: the code number x £10 is ADDED to taxable pay instead. Cumulative basis (normal): allowance to date = annual allowance x tax month / 12 (rounded to the penny); taxable pay to date = previous taxable pay this tax year + this month's taxable pay - allowance to date (K: + the K amount to date), rounded DOWN to the whole pound (never below zero). Bands to date, on taxable pay to date: 20% on the part up to the basic limit (£37,700 x tax month / 12); 40% on the part above the basic limit up to the higher limit (£125,140 x tax month / 12); 45% on the part above the higher limit. Each limit is rounded UP to the whole pound. Tax to date is rounded DOWN to the penny; this month's tax = tax to date - tax already paid this tax year (it can be negative: a refund). Week 1/month 1 (M1) basis: the same with tax month = 1 and no previous pay or tax. BR: 20% of this month's taxable pay; D0: 40% of this month's taxable pay; each rounded down to the penny, with no allowance and no previous pay or tax. K codes: this month's tax may not exceed 50% of this month's taxable pay (the regulatory limit), rounded down to the penny. A coding notice from HMRC applies from the payroll it arrives before, on the cumulative basis. New starters: with a P45, use its pay and tax to date as previous figures and its code (cumulative); without a P45, starter checklist statement A: 1257L cumulative, statement B: 1257L M1, statement C: BR.
National Insurance (monthly, on gross pay). Category A: employee NI = 8% of the part of gross pay above £1,048.00 and up to £4,189.00, plus 2% of the part above £4,189.00; employer NI = 15% of the part of gross pay above £417.00. Category C (employee aged 66 or over on pay day): no employee NI; employer as A. Category H (apprentice under 25 on pay day): employee as A; employer NI = 15% of the part of gross pay above £4,189.00 (nothing on pay up to £4,189.00). Use the employee's age on pay day. Company directors use the annual earnings period instead (category A): employee NI to date = 8% of the part of NIable pay to date above £12,570 and up to £50,270, plus 2% of the part above £50,270; employer NI to date = 15% of the part of NIable pay to date above £5,000; this month's NI = NI to date (NIable pay to date before this month plus this month's) minus the NI already paid this tax year, for each of the two. Round each employee and employer NI figure (each NI to date for directors) to the nearest penny, half a penny rounding DOWN.
Student loans (on gross pay, monthly thresholds). Plans 1, 2 and 4 are each deducted at 9% of gross pay above their monthly threshold (Plan 1 £2,172; Plan 2 £2,372; Plan 4 £2,728). The Postgraduate Loan is deducted at 6% of gross pay above £1,750, as well as any plan. Each deduction is rounded DOWN to the whole pound.
Net pay = gross pay - tax - employee NI - employee pension - student loan.`);
  const rows = world.staff.map((e) => {
    const bits = [];
    bits.push(e.hourly ? `hourly £${num2(e.rate)} (paid for hours worked)` : `salary ${gbp(e.salary)} a year, ${e.weekly} hrs/week`);
    bits.push(`DOB ${dmy(e.dob)}`);
    bits.push(`tax code on file ${e.oldCode ?? (e.p45 ? 'see P45' : codeText(e))}`);
    bits.push(`pension: ${e.pension}`);
    bits.push(`student loan: ${e.loans.length ? e.loans.map((l) => (l === 'PGL' ? 'Postgraduate Loan' : `Plan ${l}`)).join(' + ') : 'none'}`);
    if (e.apprentice) bits.push('apprentice (on an approved apprenticeship)');
    if (e.director) bits.push(`company director (NI on the annual earnings period): NIable pay to date before this month ${gbp(e.prevNiable)}, employee NI paid ${gbp(e.prevNi)}, employer NI paid ${gbp(e.prevErNi)}`);
    if (e.pensionSacrifice) bits.push('pension by salary sacrifice (5% of qualifying earnings)');
    if (e.sacrifice) bits.push(`${e.sacrificeName} ${gbp(e.sacrifice)}/month`);
    if (e.start || e.starter === 'B' || e.p45) bits.push(e.p45 ? `joined ${dMonY(firstDay)}` : `joined ${dMonY(e.start ?? firstDay)}`);
    const ytd = e.start || e.starter === 'B' || e.p45 ? 'no previous pay with us this tax year' : `previous taxable pay this tax year ${gbp(e.prevPay)}, tax paid ${gbp(e.prevTax)}`;
    return `  ${e.id} ${e.name} (${e.role}), NI number ${e.nino}: ${bits.join('; ')}; ${ytd}`;
  });
  out.push('EMPLOYEE RECORDS\n' + rows.join('\n'));
  const changes = [];
  for (const e of world.staff) {
    if (e.otSun) changes.push(`  ${e.id}: ${e.otSun} hours on Sunday 2x overtime (timesheet signed off).`);
    if (e.bonus) changes.push(`  ${e.id}: annual bonus ${gbp(e.bonus)} paid with this month's salary (board minute).`);
    if (e.ot && e.kind !== 'overtime-late-fix') changes.push(`  ${e.id}: ${e.ot} hours overtime (timesheet signed off).`);
    if (e.kind === 'overtime-late-fix') changes.push(`  ${e.id}: ${e.otDraft} hours overtime (timesheet signed off).`);
    if (e.hourly) changes.push(`  ${e.id}: ${e.hours} hours worked this month.`);
    if (e.sick) changes.push(`  ${e.id}: off sick for ${e.sick} consecutive working days this month (fit note received), first absence this year; average weekly earnings ${gbp(e.awe)}.`);
    if (e.start) changes.push(`  ${e.id}: new starter, first day ${ukDow(e.start)}; no P45, starter checklist statement ${e.starter}.`);
    if (e.starter === 'B') changes.push(`  ${e.id}: new starter from ${dMonY(firstDay)}; no P45, starter checklist statement B.`);
    if (e.p45) changes.push(`  ${e.id}: new starter from ${dMonY(firstDay)} with a P45 from the previous employer: leaving date ${dMonY(e.p45Leave)}, tax code 1257L, total pay to date ${gbp(e.prevPay)}, total tax to date ${gbp(e.prevTax)}.`);
    if (e.code === 'BR') changes.push(`  ${e.id}: this is a second job (code BR on file).`);
  }
  out.push(`CHANGES THIS MONTH (from the timesheets and HR)\n${changes.join('\n') || '  none'}`);
  out.push(
    'DRAFT PAYSLIPS FROM THE BUREAU\n' +
      world.staff
        .map((e) => {
          const p = e.draft;
          const lines = p.parts.map(([l, v]) => `    ${l.padEnd(36)} ${num2(v).padStart(10)}`);
          return [
            `  ${emp.name} | Payslip | ${e.id} ${e.name} | Period ${month} (${mon} ${yr}) | Pay date ${dmy(pay)} | Tax code ${p.code}${e.m1 && !e.mistake?.startsWith('m1') ? ' M1' : ''} | NI ${p.cat}`,
            ...lines,
            `    ${'GROSS PAY'.padEnd(36)} ${num2(p.gross).padStart(10)}`,
            `    ${'PAYE tax'.padEnd(36)} ${num2(p.tax).padStart(10)}`,
            `    ${'Employee NI'.padEnd(36)} ${num2(p.ni).padStart(10)}`,
            `    ${'Pension (employee)'.padEnd(36)} ${num2(p.pension).padStart(10)}`,
            `    ${'Student loan'.padEnd(36)} ${num2(p.sl).padStart(10)}`,
            `    ${'NET PAY'.padEnd(36)} ${num2(p.net).padStart(10)}`,
            `    Year to date: taxable pay ${num2((e.start || e.starter === 'B' || e.p45 ? 0 : e.prevPay) + p.gross - p.pension)}, tax ${num2((e.start || e.starter === 'B' || e.p45 ? 0 : e.prevTax) + p.tax)} (this employment)  |  Employer pension this period ${num2(p.erPen)}  |  Pay method BACS`,
          ].join('\n');
        })
        .join('\n\n') +
      `\n\n  Bureau summary: employer NI for the month ${gbp(world.staff.reduce((s, e) => s + e.draft.erNi, 0))}; employer pension ${gbp(world.staff.reduce((s, e) => s + e.draft.erPen, 0))}.`,
  );
  const late = [];
  for (const n of world.notices) {
    if (n.kind === 'code') late.push(`  HMRC coding notice (P6), dated ${dMonY(addDays(pay, -9))}, received ${dMonY(addDays(pay, -6))}: ${n.id} ${world.staff.find((e) => e.id === n.id).name}, new tax code ${n.code}, cumulative, to be used from the next payroll.`);
    if (n.kind === 'ot-fix') late.push(`  Email from the site supervisor, ${dMonY(addDays(pay, -2))}: "Sorry - ${n.id}'s timesheet was wrong, it should be ${n.now} hours overtime not ${n.was}. I've re-signed it."`);
  }
  out.push(`LATE ITEMS (arrived after the bureau ran the drafts)\n${late.join('\n') || '  none'}`);
  out.push(`YOUR ANSWER
Reply with one JSON object:
{
  "wrong_payslips": ["E00"],
  "corrected": { "E00": { "gross": 0.00, "tax": 0.00, "ni": 0.00, "pension": 0.00, "student_loan": 0.00, "net": 0.00 } },
  "employer_ni_total": 0.00
}
wrong_payslips: every payslip where at least one of gross pay, PAYE tax, employee NI, employee pension, student loan or net pay is wrong. corrected: the correct six figures for each payslip you list (ni = employee NI; tax negative if it is a refund). employer_ni_total: the correct total employer NI for all ${world.staff.length} employees. Amounts in pounds as numbers. ${ONE_ANSWER} The list is marked as a set (a payslip wrongly listed costs as much as one missed) and every figure is marked separately.`);
  void spec;
  void last;
  return out.join('\n\n');
}

function ukDow(d) {
  return `${['Sunday', 'Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday'][d.getUTCDay()]} ${dMonY(d)}`;
}
void iso;
