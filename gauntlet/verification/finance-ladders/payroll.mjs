// work.payroll-ladder — "Check the Payslips: Ladder".
// Ten frozen levels of checking a UK payroll bureau's draft payslips, built on the payroll engine, staff generator,
// rates text and planted-mistake flags of work.payroll-check (verification/realworld-business/payroll.mjs).
// Levels 1-2 are one month. From level 3 a level is SEVERAL consecutive months of the same payroll, and every month
// rests on the ones before it:
//   * PAYE is cumulative: each month's tax is worked out from the CORRECT pay and tax of the earlier months, so a
//     wrong earlier payslip changes the next month's correct tax (and the bureau, which runs on its own wrong
//     year-to-date figures, gets every later month wrong too);
//   * directors' NI runs on the annual earnings period, cumulative through the months;
//   * K codes collect tax the 50% limit left uncollected in later months;
//   * an HMRC coding notice mid-period changes the code from its month on (refunds through the cumulative basis),
//     a month-1 starter moves to the cumulative basis when HMRC says so, a P45 starter's figures stay in the
//     year-to-date, a 25th birthday moves an apprentice from NI category H to A;
//   * sickness periods 56 days or less apart are linked, and the 3 waiting days are served once per linked series.
// The answer carries the state: every month's wrong payslips, their corrected figures and the employer NI total,
// and every employee's taxable pay and tax to date at the end (a quarter of the points).
//
// Key: the engine (calc) month by month along the correct chain. Second derivation: check_payroll.py, an
// independent Python payroll that recomputes every payslip of every month and the year-to-date chain from the
// month inputs (payroll-inputs.json, written by build.mjs) and must agree to the penny.

import * as pay from '../realworld-business/payroll.mjs';
import { rng as mkRng, D, addDays, dMonY, dmy, gbp, num2, isWeekend, assert, ONE_ANSWER, monthLong } from '../realworld-business/lib.mjs';

const F0 = pay.FAMILIES; // overtime, student-loans, ni-categories, tax-codes, salary-sacrifice, ssp, starters, late-items, directors, pension-sacrifice, sunday-overtime
export const FAMILIES = [...F0.slice(0, 4), 'months', ...F0.slice(4), 'ssp-linking'];
// level: months, families printed, staff kinds, planted mistakes
const L = (months, nf, staff, mistakes) => ({ months, nf, staff, mistakes });
const LEVELS = {
  1: L(1, 2, ['plain', 'overtime', 'plain-sl2', 'plain'], 1),
  2: L(1, 4, ['plain', 'br-second-job', 'over-pension-age', 'apprentice', 'k-code', 'plain-sl1'], 2),
  3: L(2, 5, ['plain', 'overtime', 'k-code-cap', 'plain-sl2', 'apprentice', 'higher-rate'], 3),
  4: L(3, 7, ['plain', 'sacrifice', 'ssp', 'overtime', 'k-code-cap', 'apprentice', 'higher-rate', 'opted-out'], 4),
  5: L(3, 13, ['plain', 'starter-p45', 'starter-m1', 'code-change', 'overtime-late-fix', 'ssp', 'k-code-cap', 'director', 'sacrifice+plain-sl2', 'apprentice-turned-25'], 6),
  6: L(4, 13, ['plain', 'starter-p45', 'starter-m1', 'code-change', 'overtime-late-fix', 'ssp', 'k-code-cap', 'director', 'pension-sacrifice', 'sunday-ot', 'sacrifice+plain-sl2', 'apprentice-turned-25'], 7),
  7: L(5, 13, ['plain', 'starter-p45+plain-sl1', 'starter-m1', 'code-change+overtime', 'overtime-late-fix', 'ssp', 'ssp+plain-sl4', 'k-code-cap', 'director', 'director+pension-sacrifice', 'sunday-ot', 'sacrifice+plain-sl2', 'apprentice-turned-25', 'mid-month-starter'], 8),
  8: L(5, 13, ['plain', 'starter-p45+plain-sl1', 'starter-m1', 'code-change+overtime', 'code-change', 'overtime-late-fix', 'ssp', 'ssp+plain-sl4', 'k-code-cap', 'k-code', 'director', 'director+pension-sacrifice', 'sunday-ot', 'sacrifice+plain-sl2', 'apprentice-turned-25', 'mid-month-starter'], 10),
  9: L(6, 13, ['plain', 'starter-p45+plain-sl1', 'starter-m1', 'starter-m1+plain-sl4', 'code-change+overtime', 'code-change', 'overtime-late-fix', 'ssp', 'ssp+plain-sl4', 'ssp+overtime', 'k-code-cap', 'k-code', 'director', 'director+pension-sacrifice', 'sunday-ot', 'sacrifice+plain-sl2', 'apprentice-turned-25', 'mid-month-starter'], 12),
  10: L(6, 13, ['plain', 'starter-p45+plain-sl1', 'starter-p45', 'starter-m1', 'starter-m1+plain-sl4', 'code-change+overtime', 'code-change', 'code-change+plain-sl1', 'overtime-late-fix', 'ssp', 'ssp+plain-sl4', 'ssp+overtime', 'k-code-cap', 'k-code-cap', 'k-code', 'director', 'director+pension-sacrifice', 'sunday-ot', 'sacrifice+plain-sl2', 'higher-rate+sacrifice', 'apprentice-turned-25', 'mid-month-starter'], 15),
};
const EMPLOYERS = [0, 1, 2, 0, 1, 2, 0, 1, 2, 0];
// First pay day (UK pay days, last Friday or so) and its tax month; later months follow monthly.
const STARTS = [[2026, 11, 27, 8], [2026, 11, 27, 8], [2026, 11, 27, 8], [2026, 10, 30, 7], [2026, 10, 30, 7], [2026, 9, 25, 6], [2026, 9, 25, 6], [2026, 8, 28, 5], [2026, 7, 31, 4], [2026, 7, 31, 4]];

const lastFriday = (y, m) => {
  let d = new Date(Date.UTC(y, m, 0));
  while (d.getUTCDay() !== 5) d = addDays(d, -1);
  return d;
};
const monthLabel = (d) => `${monthLong(d.getUTCMonth() + 1)} ${d.getUTCFullYear()}`;
const kindsOf = (e) => e.kind.split('+');
const has = (e, k) => kindsOf(e).includes(k);

/** Families an employee kind or a mistake needs in the rates text. */
const KIND_FAM = (k) => [...pay.KIND_FAMILIES[k], ...(k === 'ssp' ? [] : [])];
const NEW_MISTAKES = { 'ssp-unlinked': ['ssp', 'ssp-linking'], 'm1-kept': ['starters', 'late-items'], 'p45-dropped': ['starters', 'months'] };
const mistakeFamilies = (m) => NEW_MISTAKES[m] ?? pay.MISTAKE_FAMILIES[m];

/** Mistakes the bureau makes on each feature, and whether it repeats every month (persistent) or once. */
const MISTAKES_FOR = {
  plain: [['pension-full-pay', 1], ['net-typo', 1]],
  overtime: [['overtime-1x', 1], ['hourly-rounded-early', 1]],
  'overtime-late-fix': [['overtime-uncorrected', 1]],
  'plain-sl1': [['sl-wrong-plan', 'all']],
  'plain-sl2': [['sl-rounded-penny', 'all']],
  'plain-sl4': [['sl-rounded-penny', 'all']],
  'over-pension-age': [['ni-cat-a-for-c', 'all']],
  apprentice: [['employer-ni-apprentice', 'all']],
  'apprentice-turned-25': [['apprentice-still-h', 'all']],
  'opted-out': [['pension-opted-out', 'all']],
  sacrifice: [['sacrifice-ni-before', 'all']],
  'k-code-cap': [['k-no-cap', 'all']],
  'starter-m1': [['m1-kept', 'all']],
  'starter-p45': [['p45-dropped', 'all']],
  'mid-month-starter': [['starter-calendar-days', 1]],
  ssp: [['ssp-unlinked', 1], ['ssp-no-waiting', 1]],
  'code-change': [['old-code', 'all']],
  'higher-rate': [['higher-rate-flat', 1]],
  director: [['director-ni-monthly', 'all']],
  'pension-sacrifice': [['pension-sacrifice-net-pay', 'all']],
  'sunday-ot': [['sunday-ot-1.5', 1]],
  'br-second-job': [], 'k-code': [], hourly: [], 'half-penny': [['employer-ni-half-up', 1]],
};

export const SPECS = Array.from({ length: 10 }, (_, i) => {
  const level = i + 1;
  const spec = LEVELS[level];
  const fams = FAMILIES.slice(0, spec.nf);
  for (const k of spec.staff) for (const x of k.split('+')) for (const f of KIND_FAM(x)) if (!fams.includes(f)) throw new Error(`L${level}: ${x} needs ${f}`);
  const [y, m, d, t] = STARTS[i];
  return { id: `L${String(level).padStart(2, '0')}`, level, seed: 9300 + level * 17, emp: EMPLOYERS[i], first: [y, m, d], month: t, months: spec.months, staff: spec.staff, nMistakes: spec.mistakes, families: fams };
});

// ───────────────────────────── the months ─────────────────────────────

/**
 * Builds a level: the staff (from the payroll-check generator), every month's inputs, the correct chain and the
 * bureau's drafts (its own year-to-date chain, with planted mistakes). Returns null when a planted mistake changes
 * nothing or a rule it is meant to exercise does not bite (the caller tries the next salt).
 */
export function build(spec, salt = 0) {
  const seed = spec.seed + salt * 1000003;
  const r = mkRng(seed * 7 + 3);
  const fams = new Set(spec.families);
  const multi = spec.months > 1;
  const base = pay.generate({ seed, emp: spec.emp, pay: spec.first, month: spec.month, staff: spec.staff, mistakes: {} });
  const pays = [];
  for (let k = 0; k < spec.months; k++) {
    const y = spec.first[0] + Math.floor((spec.first[1] - 1 + k) / 12);
    const m = ((spec.first[1] - 1 + k) % 12) + 1;
    pays.push(k === 0 ? D(...spec.first) : lastFriday(y, m));
  }
  const months = pays.map((p, k) => ({ pay: p, month: spec.month + k, label: monthLabel(p) }));
  const staff = base.staff.map((e) => ({ ...e, draft: undefined, correct: undefined, mistake: undefined }));
  // when things happen (month indexes)
  const pick = (lo, hi) => r.int(Math.max(0, lo), Math.max(0, Math.min(spec.months - 1, hi)));
  for (const e of staff) {
    e.join = 0;
    if (has(e, 'mid-month-starter')) e.join = multi ? pick(1, spec.months - 1) : 0;
    if (has(e, 'code-change')) e.codeFrom = multi ? pick(1, spec.months - 1) : 0;
    if (has(e, 'starter-m1')) e.cumFrom = multi ? pick(1, spec.months - 1) : 99;
    if (has(e, 'overtime-late-fix')) e.fixIn = pick(0, spec.months - 1);
    if (has(e, 'director')) e.bonusIn = pick(0, spec.months - 1);
    if (has(e, 'apprentice-turned-25') && multi) {
      const kb = pick(1, spec.months - 1);
      const b = addDays(months[kb].pay, -r.int(3, 20));
      e.dob = D(b.getUTCFullYear() - 25, b.getUTCMonth() + 1, b.getUTCDate());
    }
    if (has(e, 'ssp')) {
      // one or two periods of sickness; the second within 56 days of the first (linked) when there are months enough
      const k1 = pick(0, spec.months - 2);
      const spells = [{ k: k1, days: r.int(4, 6) }];
      const k2 = k1 + r.pick([1, 2]);
      if (multi && k2 < spec.months) spells.push({ k: k2, days: r.int(4, 8) });
      e.spells = spells;
      e.awe = r.int(450, 800) * 100;
    }
  }
  // the inputs of every employee in every month (everything but the year-to-date chain)
  const inputs = months.map((mo, k) => {
    const first = D(mo.pay.getUTCFullYear(), mo.pay.getUTCMonth() + 1, 1);
    const last = new Date(Date.UTC(mo.pay.getUTCFullYear(), mo.pay.getUTCMonth() + 1, 0));
    return staff.filter((e) => k >= e.join).map((e) => {
      const x = { ...e, start: null, ot: 0, otSun: 0, otDraft: undefined, sick: 0, waitingLeft: 3, bonus: 0 };
      if (has(e, 'mid-month-starter') && k === e.join) {
        let s = addDays(last, -r.int(9, 18));
        while (isWeekend(s)) s = addDays(s, 1);
        x.start = s;
      }
      if (has(e, 'overtime') || has(e, 'overtime-late-fix') || has(e, 'sunday-ot')) x.ot = r.pick([4.5, 6, 7.5, 9, 10.5, 12]);
      if (has(e, 'sunday-ot')) x.otSun = r.pick([3, 4.5, 6, 7.5, 8]);
      if (has(e, 'overtime-late-fix') && k === e.fixIn) {
        x.otDraft = x.ot;
        x.ot = x.ot + r.pick([1.5, 3, -1.5]);
      }
      if (e.hourly) x.hours = r.pick([96, 112, 120.5, 138, 151.5]);
      if (has(e, 'director')) x.bonus = k === e.bonusIn ? r.int(10, 40) * 10000 : 0;
      if (has(e, 'code-change')) {
        x.code = k >= e.codeFrom ? base.staff.find((b) => b.id === e.id).code : '1257L';
        x.oldCode = k >= e.codeFrom ? '1257L' : undefined;
      }
      if (has(e, 'starter-m1')) x.m1 = k < e.cumFrom;
      const sp = (e.spells ?? []).find((s) => s.k === k);
      if (sp) {
        let d0 = addDays(first, r.int(2, 14));
        while (isWeekend(d0)) d0 = addDays(d0, 1);
        sp.start = d0;
        let end = d0;
        for (let n = 1; n < sp.days; ) {
          end = addDays(end, 1);
          if (!isWeekend(end)) n++;
        }
        sp.end = end;
        x.sick = sp.days;
        x.spell = sp;
      }
      return x;
    });
  });
  // SSP linking: waiting days left for each period
  for (const e of staff) {
    let served = 0;
    let prevEnd = null;
    for (const sp of e.spells ?? []) {
      const linked = prevEnd && (sp.start - prevEnd) / 86400000 <= 56;
      if (!linked) served = 0;
      sp.waitingLeft = Math.max(0, 3 - served);
      sp.linked = !!linked;
      served += Math.min(sp.days, sp.waitingLeft);
      prevEnd = sp.end;
    }
  }
  for (const xs of inputs) for (const x of xs) if (x.spell) x.waitingLeft = x.spell.waitingLeft;

  // planted mistakes: one per chosen employee, with the month(s) it is made in
  const cands = r.shuffle(staff.filter((e) => e.id !== 'E01')).map((e) => [e, r.shuffle(kindsOf(e).flatMap((k) => MISTAKES_FOR[k] ?? []))]).filter(([, c]) => c.length);
  const plan = [];
  for (const [e, c] of cands) {
    if (plan.length >= spec.nMistakes) break;
    const [m, when] = c[0];
    if (mistakeFamilies(m).some((f) => !fams.has(f))) continue;
    const ks = [];
    for (let k = e.join; k < spec.months; k++) ks.push(k);
    let onK = when === 'all' ? ks : [r.pick(ks)];
    if (m === 'overtime-uncorrected') onK = [e.fixIn];
    if (m === 'ssp-unlinked' || m === 'ssp-no-waiting') onK = (e.spells ?? []).filter((s) => m === 'ssp-no-waiting' || s.linked).map((s) => s.k);
    if (m === 'starter-calendar-days') onK = [e.join];
    if (!onK.length) continue;
    e.mistake = m;
    plan.push({ id: e.id, m, months: onK });
  }
  if (!plan.length) return null;

  // the correct chain and the bureau's chain, month by month
  const chain = (bureau) => {
    const ytd = {};
    for (const e of staff) {
      const p45 = !!e.p45;
      ytd[e.id] = {
        pay: e.prevPay ?? 0, tax: e.prevTax ?? 0,
        // the bureau that drops the P45 starts the starter at zero
        ...(bureau && e.mistake === 'p45-dropped' ? { pay: 0, tax: 0 } : {}),
        niable: e.prevNiable ?? 0, ni: e.prevNi ?? 0, erNi: e.prevErNi ?? 0, p45,
      };
    }
    return months.map((mo, k) => {
      const world = { pay: mo.pay, month: mo.month };
      const out = {};
      for (const x0 of inputs[k]) {
        const y = ytd[x0.id];
        const x = { ...x0, prevPay: y.pay, prevTax: y.tax, prevNiable: y.niable, prevNi: y.ni, prevErNi: y.erNi, p45: false };
        let flags = {};
        if (bureau) {
          const pl = plan.find((p) => p.id === x.id && p.months.includes(k));
          if (pl) {
            if (pl.m === 'ssp-unlinked') x.waitingLeft = 3;
            else if (pl.m === 'm1-kept') x.m1 = true;
            else if (pl.m === 'p45-dropped') flags = {};
            else flags = pay.MISTAKE_FLAGS[pl.m];
          }
        }
        const p = pay.calc(world, x, flags);
        out[x.id] = p;
        y.pay += p.gross - p.pension;
        y.tax += p.tax;
        if (x.director) {
          y.niable += p.gross;
          y.ni += p.ni;
          y.erNi += p.erNi;
        }
      }
      return out;
    });
  };
  const correct = chain(false);
  const drafts = chain(true);
  const SIX = ['gross', 'tax', 'ni', 'pension', 'sl', 'net'];
  const key = { months: {}, year_to_date: {} };
  months.forEach((mo, k) => {
    const wrong = inputs[k].map((x) => x.id).filter((id) => SIX.some((f) => correct[k][id][f] !== drafts[k][id][f]));
    key.months[mo.label] = {
      wrong_payslips: wrong,
      corrected: Object.fromEntries(wrong.map((id) => [id, { gross: correct[k][id].gross / 100, tax: correct[k][id].tax / 100, ni: correct[k][id].ni / 100, pension: correct[k][id].pension / 100, student_loan: correct[k][id].sl / 100, net: correct[k][id].net / 100 }])),
      employer_ni_total: Object.values(correct[k]).reduce((s, p) => s + p.erNi, 0) / 100,
    };
  });
  if (fams.has('months')) {
    for (const e of staff) {
      let p0 = e.prevPay ?? 0;
      let t0 = e.prevTax ?? 0;
      months.forEach((_, k) => {
        if (correct[k][e.id]) {
          p0 += correct[k][e.id].gross - correct[k][e.id].pension;
          t0 += correct[k][e.id].tax;
        }
      });
      key.year_to_date[e.id] = { taxable_pay: p0 / 100, tax: t0 / 100 };
    }
  } else delete key.year_to_date;
  const lv = { spec, seed, salt, fams, months, staff, inputs, correct, drafts, plan, key, base };
  return check(lv) ? lv : null;
}

/** Every planted mistake changes some draft; every rule the level is meant to exercise bites. */
function check(lv) {
  const { staff, correct, drafts, plan, months, inputs } = lv;
  for (const p of plan) {
    const changed = p.months.some((k) => ['gross', 'tax', 'ni', 'pension', 'sl', 'net', 'erNi'].some((f) => correct[k][p.id][f] !== drafts[k][p.id][f]));
    if (!changed) return false;
  }
  for (let k = 0; k < months.length; k++) for (const x of inputs[k]) if (correct[k][x.id].net <= 0) return false;
  for (const e of staff) {
    if (has(e, 'k-code-cap')) {
      const capped = months.some((_, k) => correct[k][e.id] && correct[k][e.id].tax === Math.floor((correct[k][e.id].gross - correct[k][e.id].pension) / 2));
      if (!capped) return false;
    }
    if (has(e, 'apprentice-turned-25') && months.length > 1 && !(correct[0][e.id].cat === 'H' && correct[months.length - 1][e.id].cat === 'A')) return false;
    if (has(e, 'over-pension-age') && correct[0][e.id].cat !== 'C') return false;
    if (has(e, 'ssp') && e.awe < pay.RATES.sspLel) return false;
  }
  // with several months, a wrong earlier month must spread: some correct later payslip differs from what it would be
  // if the earlier months had been the bureau's (the cumulative basis really is cumulative here)
  if (months.length > 1) {
    const spread = plan.some((p) => p.months.length === 1 && p.months[0] < months.length - 1);
    const persistent = plan.some((p) => p.months.length > 1);
    if (!spread && !persistent) return false;
  }
  if (!Object.values(lv.key.months).every((m) => m.wrong_payslips.length)) return false;
  return true;
}

export function buildLevel(spec) {
  for (let salt = 0; salt < 300; salt++) {
    const lv = build(spec, salt);
    if (lv) return lv;
  }
  throw new Error(`${spec.id}: no valid payroll in 300 salts`);
}

// ───────────────────────────── text ─────────────────────────────

const LADDER_RULES = {
  months: 'Several months. Work out every month from the CORRECT figures of the months before it: previous taxable pay and tax this tax year are the figures before the first month (from the records or a P45) plus the correct figures of the earlier months below, never the year-to-date figures printed on the bureau\'s drafts (those are only right if the bureau\'s earlier payslips were). A director\'s NI to date works the same way. A coding notice, a starter, a birthday or a sickness takes effect in the month it falls in, and stays in effect for the later months.',
  'ssp-linking': 'Linked sickness. A period of sickness that starts 56 days or less after the last day of the previous period is linked to it: over a run of linked periods the 3 waiting days are served only once (a linked period has only the waiting days the earlier ones did not use; a period that is not linked starts again with 3).',
};

export function rates(fams) {
  const lines = [pay.RATES_TEXT(new Set([...fams].filter((f) => F0.includes(f))))];
  for (const f of ['months', 'ssp-linking']) if (fams.has(f)) lines.push(LADDER_RULES[f]);
  return lines.join('\n');
}

function codeText(x) {
  return x.m1 ? `${x.code} M1 (week 1/month 1 basis)` : x.code;
}

export function render(lv, ytdWeight) {
  const { spec, months, staff, inputs, drafts, fams } = lv;
  const emp = pay.EMPLOYERS[spec.emp];
  const multi = months.length > 1;
  const names = months.map((m) => m.label);
  const out = [];
  out.push(`From: ${emp.boss} (${emp.name})
To: you
Subject: ${multi ? `Payroll ${names[0]} to ${names[names.length - 1]}` : `${names[0]} payroll`} - please check the bureau's drafts before I approve the FPS

Hi, ${emp.bureau} have sent the draft payslips for ${multi ? `${names[0]} to ${names[names.length - 1]} (tax months ${months[0].month} to ${months[months.length - 1].month}; our bookkeeper is off, so nothing has been checked since)` : `${names[0]} (tax month ${months[0].month}, pay day ${dMonY(months[0].pay)})`}. Can you check every payslip against the employee records and each month's changes, using the figures below? Tell me which payslips are wrong${multi ? ' in each month' : ''}, what the correct figures are, and the correct employer NI total${multi ? ' for each month' : ' for the month'}. Everything you need is in this email.`);
  out.push(rates(fams));
  const rows = staff.map((e) => {
    const x0 = inputs[e.join].find((x) => x.id === e.id);
    const bits = [];
    bits.push(e.hourly ? `hourly £${num2(e.rate)} (paid for hours worked)` : `salary ${gbp(e.salary)} a year, ${e.weekly} hrs/week`);
    bits.push(`DOB ${dmy(e.dob)}`);
    bits.push(`tax code on file ${e.p45 ? 'see P45' : has(e, 'code-change') ? '1257L' : codeText(x0)}`);
    bits.push(`pension: ${e.pension}`);
    bits.push(`student loan: ${e.loans.length ? e.loans.map((l) => (l === 'PGL' ? 'Postgraduate Loan' : `Plan ${l}`)).join(' + ') : 'none'}`);
    if (e.apprentice) bits.push('apprentice (on an approved apprenticeship)');
    if (e.director) bits.push(`company director (NI on the annual earnings period): NIable pay to date before ${names[0]} ${gbp(e.prevNiable)}, employee NI paid ${gbp(e.prevNi)}, employer NI paid ${gbp(e.prevErNi)}`);
    if (e.pensionSacrifice) bits.push('pension by salary sacrifice (5% of qualifying earnings)');
    if (e.sacrifice) bits.push(`${e.sacrificeName} ${gbp(e.sacrifice)}/month`);
    const joined = e.start || e.starter === 'B' || e.p45 || has(e, 'mid-month-starter');
    const ytd = joined ? 'no previous pay with us this tax year' : `previous taxable pay this tax year (before ${names[0]}) ${gbp(e.prevPay)}, tax paid ${gbp(e.prevTax)}`;
    return `  ${e.id} ${e.name} (${e.role}), NI number ${e.nino}: ${bits.join('; ')}; ${ytd}`;
  });
  out.push('EMPLOYEE RECORDS' + (multi ? ` (as at the start of ${names[0]})` : '') + '\n' + rows.join('\n'));
  months.forEach((mo, k) => {
    const first = D(mo.pay.getUTCFullYear(), mo.pay.getUTCMonth() + 1, 1);
    const ch = [];
    for (const x of inputs[k]) {
      const e = staff.find((s) => s.id === x.id);
      if (x.otSun) ch.push(`  ${x.id}: ${x.otSun} hours on Sunday 2x overtime (timesheet signed off).`);
      if (x.bonus) ch.push(`  ${x.id}: annual bonus ${gbp(x.bonus)} paid with this month's salary (board minute).`);
      if (x.ot) ch.push(`  ${x.id}: ${x.otDraft ?? x.ot} hours overtime (timesheet signed off).`);
      if (x.otDraft !== undefined) ch.push(`  ${x.id}: email from the site supervisor, ${dMonY(addDays(mo.pay, -2))}, after the bureau ran the drafts: "Sorry - ${x.id}'s timesheet was wrong, it should be ${x.ot} hours overtime not ${x.otDraft}. I've re-signed it."`);
      if (x.hourly) ch.push(`  ${x.id}: ${x.hours} hours worked this month.`);
      if (x.spell) ch.push(`  ${x.id}: off sick from ${dMonY(x.spell.start)} to ${dMonY(x.spell.end)}, ${x.sick} consecutive working days (fit note received); average weekly earnings ${gbp(e.awe)}.`);
      if (x.start) ch.push(`  ${x.id}: new starter, first day ${dMonY(x.start)}; no P45, starter checklist statement A.`);
      if (k === 0 && e.starter === 'B') ch.push(`  ${x.id}: new starter from ${dMonY(first)}; no P45, starter checklist statement B.`);
      if (k === 0 && e.p45) ch.push(`  ${x.id}: new starter from ${dMonY(first)} with a P45 from the previous employer: leaving date ${dMonY(e.p45Leave)}, tax code 1257L, total pay to date ${gbp(e.prevPay)}, total tax to date ${gbp(e.prevTax)}.`);
      if (k === 0 && e.code === 'BR') ch.push(`  ${x.id}: this is a second job (code BR on file).`);
      if (has(e, 'code-change') && k === e.codeFrom) ch.push(`  ${x.id}: HMRC coding notice (P6), dated ${dMonY(addDays(mo.pay, -12))}, received ${dMonY(addDays(mo.pay, -9))}: new tax code ${x.code}, cumulative, to be used from the next payroll.`);
      if (has(e, 'starter-m1') && k === e.cumFrom) ch.push(`  ${x.id}: HMRC coding notice (P6), dated ${dMonY(addDays(mo.pay, -12))}, received ${dMonY(addDays(mo.pay, -9))}: tax code 1257L, cumulative (no longer week 1/month 1), to be used from the next payroll.`);
    }
    out.push(`CHANGES IN ${mo.label.toUpperCase()} (from the timesheets, HR and HMRC; pay day ${dMonY(mo.pay)}, tax month ${mo.month})\n${ch.join('\n') || '  none'}`);
    const slips = inputs[k].map((x) => {
      const p = drafts[k][x.id];
      const e = staff.find((s) => s.id === x.id);
      const lines = p.parts.map(([l, v]) => `    ${l.padEnd(36)} ${num2(v).padStart(10)}`);
      const prevB = drafts.slice(0, k).reduce((s, d) => (d[x.id] ? { pay: s.pay + d[x.id].gross - d[x.id].pension, tax: s.tax + d[x.id].tax } : s), { pay: 0, tax: 0 });
      const own = e.start || e.starter === 'B' || e.p45 || has(e, 'mid-month-starter') ? { pay: 0, tax: 0 } : { pay: e.prevPay, tax: e.prevTax };
      return [
        `  ${emp.name} | Payslip | ${x.id} ${x.name} | Period ${mo.month} (${mo.label}) | Pay date ${dmy(mo.pay)} | Tax code ${p.code}${x.m1 || lv.plan.some((q) => q.id === x.id && q.m === 'm1-kept' && q.months.includes(k)) ? ' M1' : ''} | NI ${p.cat}`,
        ...lines,
        `    ${'GROSS PAY'.padEnd(36)} ${num2(p.gross).padStart(10)}`,
        `    ${'PAYE tax'.padEnd(36)} ${num2(p.tax).padStart(10)}`,
        `    ${'Employee NI'.padEnd(36)} ${num2(p.ni).padStart(10)}`,
        `    ${'Pension (employee)'.padEnd(36)} ${num2(p.pension).padStart(10)}`,
        `    ${'Student loan'.padEnd(36)} ${num2(p.sl).padStart(10)}`,
        `    ${'NET PAY'.padEnd(36)} ${num2(p.net).padStart(10)}`,
        `    Year to date: taxable pay ${num2(own.pay + prevB.pay + p.gross - p.pension)}, tax ${num2(own.tax + prevB.tax + p.tax)} (this employment)  |  Employer pension this period ${num2(p.erPen)}`,
      ].join('\n');
    });
    out.push(`DRAFT PAYSLIPS FROM THE BUREAU, ${mo.label.toUpperCase()}\n` + slips.join('\n\n') + `\n\n  Bureau summary for ${mo.label}: employer NI ${gbp(inputs[k].reduce((s, x) => s + drafts[k][x.id].erNi, 0))}.`);
  });
  const mk = names.slice(0, 2).map((n) => `"${n}": { "wrong_payslips": ["E00"], "corrected": { "E00": { "gross": 0.00, "tax": 0.00, "ni": 0.00, "pension": 0.00, "student_loan": 0.00, "net": 0.00 } }, "employer_ni_total": 0.00 }`);
  out.push(`YOUR ANSWER
Reply with one JSON object:
{
  "months": {
    ${mk.join(',\n    ')}${multi ? ',\n    ...' : ''}
  }${fams.has('months') ? ',\n  "year_to_date": { "E00": { "taxable_pay": 0.00, "tax": 0.00 }, ... }' : ''}
}
months: one entry per month, keyed by the month as written above. wrong_payslips: every payslip of that month where at least one of gross pay, PAYE tax, employee NI, employee pension, student loan or net pay is wrong. corrected: the correct six figures for each payslip you list (ni = employee NI; tax negative if it is a refund). employer_ni_total: the correct total employer NI for everyone paid that month.${fams.has('months') ? ` year_to_date: for every employee, the correct taxable pay to date and tax to date this tax year after the ${names[names.length - 1]} payroll, as used for PAYE (P45 figures included; worth ${ytdWeight} points each, together about a quarter of the points).` : ''} Amounts in pounds as numbers. ${ONE_ANSWER} Each list is marked as a set (a payslip wrongly listed costs as much as one missed) and every figure is marked separately; the wrong payslips, tax and NI are worth 2 points each and each employer NI total 4.`);
  return out.join('\n\n');
}

/** Weights: as work.payroll-check (wrong payslips 2, tax and NI 2, employer NI 4), year-to-date chain ~25%. */
export function scorer(lv, base) {
  const weights = {};
  const sets = {};
  let other = 0;
  for (const [label, m] of Object.entries(lv.key.months)) {
    sets[`months.${label}.wrong_payslips`] = 1;
    weights[`months.${label}.wrong_payslips`] = 2;
    weights[`months.${label}.employer_ni_total`] = 4;
    other += 2 * m.wrong_payslips.length + 4;
    for (const id of m.wrong_payslips) {
      weights[`months.${label}.corrected.${id}.tax`] = 2;
      weights[`months.${label}.corrected.${id}.ni`] = 2;
      other += 8;
    }
  }
  let ytdW = 0;
  if (lv.key.year_to_date) {
    const n = Object.keys(lv.key.year_to_date).length * 2;
    ytdW = Math.max(2, Math.round(other / 3 / n));
    weights.year_to_date = ytdW;
  }
  return { sc: { ...base, sets, weights }, ytdW };
}

export function careless(lv) {
  // trusts the drafts: no payslip wrong, the bureau's employer NI, and the bureau's year-to-date figures
  const out = { months: {} };
  lv.months.forEach((mo, k) => {
    out.months[mo.label] = { wrong_payslips: [], corrected: {}, employer_ni_total: Object.values(lv.drafts[k]).reduce((s, p) => s + p.erNi, 0) / 100 };
  });
  if (lv.key.year_to_date) {
    out.year_to_date = {};
    for (const e of lv.staff) {
      const own = e.start || e.starter === 'B' || has(e, 'mid-month-starter') ? { pay: 0, tax: 0 } : e.p45 ? { pay: 0, tax: 0 } : { pay: e.prevPay, tax: e.prevTax };
      const s = lv.drafts.reduce((a, d) => (d[e.id] ? { pay: a.pay + d[e.id].gross - d[e.id].pension, tax: a.tax + d[e.id].tax } : a), own);
      out.year_to_date[e.id] = { taxable_pay: s.pay / 100, tax: s.tax / 100 };
    }
  }
  return out;
}

/**
 * A careful checker with one slip: in the first month, E01 (a plain salaried employee) gets their tax wrong by
 * £1.00 (an arithmetic slip), and every later month of that employee is worked out from that wrong figure (as the
 * cumulative basis makes it: the next month's tax is £1.00 the other way).
 */
export function slip(lv) {
  const out = JSON.parse(JSON.stringify(lv.key));
  const k0 = 0;
  const label = lv.months[k0].label;
  const id = 'E01'; // always a plain salaried employee on the cumulative basis
  const fix = (k, delta) => {
    const m = out.months[lv.months[k].label];
    const c = lv.correct[k][id];
    if (!m.corrected[id]) {
      m.wrong_payslips.push(id);
      m.corrected[id] = { gross: c.gross / 100, tax: c.tax / 100, ni: c.ni / 100, pension: c.pension / 100, student_loan: c.sl / 100, net: c.net / 100 };
    }
    m.corrected[id].tax = Math.round(m.corrected[id].tax * 100 + delta) / 100;
    m.corrected[id].net = Math.round(m.corrected[id].net * 100 - delta) / 100;
  };
  fix(k0, 100);
  if (lv.months.length > 1) fix(1, -100);
  void label;
  return out;
}

/**
 * The reasoning error that spreads: every rule right, but each later month worked out from the bureau's year-to-date
 * figures (its earlier drafts) instead of the corrected ones.
 */
export function chainError(lv) {
  const SIX = ['gross', 'tax', 'ni', 'pension', 'sl', 'net'];
  const out = { months: {} };
  const own = {};
  lv.months.forEach((mo, k) => {
    const world = { pay: mo.pay, month: mo.month };
    const mine = {};
    for (const x of lv.inputs[k]) {
      const e = lv.staff.find((s) => s.id === x.id);
      const prev = lv.drafts.slice(0, k).reduce((a, d) => (d[x.id] ? { pay: a.pay + d[x.id].gross - d[x.id].pension, tax: a.tax + d[x.id].tax, niable: a.niable + (x.director ? d[x.id].gross : 0), ni: a.ni + (x.director ? d[x.id].ni : 0), erNi: a.erNi + (x.director ? d[x.id].erNi : 0) } : a), { pay: e.prevPay ?? 0, tax: e.prevTax ?? 0, niable: e.prevNiable ?? 0, ni: e.prevNi ?? 0, erNi: e.prevErNi ?? 0 });
      mine[x.id] = pay.calc(world, { ...x, prevPay: prev.pay, prevTax: prev.tax, prevNiable: prev.niable, prevNi: prev.ni, prevErNi: prev.erNi, p45: false });
      const o = (own[x.id] ??= { pay: e.prevPay ?? 0, tax: e.prevTax ?? 0 });
      o.pay += mine[x.id].gross - mine[x.id].pension;
      o.tax += mine[x.id].tax;
    }
    const wrong = lv.inputs[k].map((x) => x.id).filter((id) => SIX.some((f) => mine[id][f] !== lv.drafts[k][id][f]));
    out.months[mo.label] = {
      wrong_payslips: wrong,
      corrected: Object.fromEntries(wrong.map((id) => [id, { gross: mine[id].gross / 100, tax: mine[id].tax / 100, ni: mine[id].ni / 100, pension: mine[id].pension / 100, student_loan: mine[id].sl / 100, net: mine[id].net / 100 }])),
      employer_ni_total: Object.values(mine).reduce((s, p) => s + p.erNi, 0) / 100,
    };
  });
  if (lv.key.year_to_date) out.year_to_date = Object.fromEntries(lv.staff.map((e) => [e.id, { taxable_pay: own[e.id].pay / 100, tax: own[e.id].tax / 100 }]));
  return out;
}

/** Everything check_payroll.py needs to recompute every payslip on its own: the month inputs, not the chain. */
export function inputsDump(lv) {
  const keep = ['id', 'salary', 'weekly', 'hourly', 'rate', 'hours', 'dob', 'code', 'm1', 'loans', 'pension', 'pensionSacrifice', 'apprentice', 'director', 'sacrifice', 'start', 'ot', 'otSun', 'sick', 'waitingLeft', 'awe', 'bonus'];
  const iso = (d) => (d instanceof Date ? d.toISOString().slice(0, 10) : d);
  return {
    months: lv.months.map((m, k) => ({
      pay: iso(m.pay), month: m.month,
      staff: lv.inputs[k].map((x) => Object.fromEntries(keep.filter((f) => x[f] !== undefined && x[f] !== null).map((f) => [f, iso(x[f])]))),
      correct: Object.fromEntries(Object.entries(lv.correct[k]).map(([id, p]) => [id, { gross: p.gross, tax: p.tax, ni: p.ni, pension: p.pension, sl: p.sl, net: p.net, erNi: p.erNi }])),
    })),
    opening: Object.fromEntries(lv.staff.map((e) => [e.id, { pay: e.prevPay ?? 0, tax: e.prevTax ?? 0, niable: e.prevNiable ?? 0, ni: e.prevNi ?? 0, erNi: e.prevErNi ?? 0 }])),
  };
}

export const META = {
  id: 'work.payroll-ladder',
  name: 'Check the Payslips: Ladder',
  description:
    "Ten frozen levels of checking a UK payroll bureau's draft payslips with every rate and rule given in the prompt: the wrong payslips, their six corrected figures to the penny and the employer NI total. Levels 1-2 are one month; from level 3 a level is two to six consecutive months of the same payroll, and every month rests on the ones before it: PAYE is cumulative (each month's tax comes from the correct pay and tax of the earlier months, while the bureau runs on its own wrong year-to-date figures), directors' NI runs on the annual earnings period, K codes catch up tax the 50% limit held back, a coding notice or a move off month 1 changes the code mid-period, a P45 stays in the year-to-date, an apprentice turns 25, and linked sickness serves its waiting days once. The answer includes everyone's taxable pay and tax to date at the end, so one early error spreads. A level is passed at 90% of its points; the headline is the highest level passed reliably.",
  difficulty: 'extreme',
  tags: ['real-world', 'work', 'payroll', 'paye', 'national-insurance', 'pensions', 'ladder', 'json'],
  hook: 'Six months of payslips where every month rests on the last. How far up can it put the whole payroll right?',
  maxOutputTokens: 64000,
  timeLimitSec: 1800,
  scorer: { type: 'json', numberTolerance: 1e-8, lenient: true },
};
void assert;
