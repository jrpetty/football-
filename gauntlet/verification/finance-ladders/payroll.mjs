// work.payroll-ladder — "Check the Payslips: Ladder".
// Ten frozen levels of the same monthly payroll check as work.payroll-check, built with the SAME payroll engine,
// draft-payslip mistakes and rates text (verification/realworld-business/payroll.mjs). A level prints only the rule
// families it uses, sentence for sentence as in the full rates block, and each level adds families and staff:
//   L01  4 staff: pay, pension, cumulative tax, NI category A, overtime and student loans
//   L05  the full rates block at the size of a hard Check the Payslips case
//   L10  the full rates block, 120 staff, most of them combining several features (salary sacrifice with a
//        student loan, a late coding notice on an overtime worker, a P45 starter with a loan, ...).
// Every key is the engine's correct payslip; a draft is wrong exactly when one of its six figures differs from it.
// Cross-checks as work.payroll-check: every planted mistake must change the draft, every net pay must add up, the
// K-code limit, NI categories and higher rate must bite where planted; and (new here) every employee feature and
// every planted mistake must belong to a rule family the level prints.

import * as pay from '../realworld-business/payroll.mjs';
import { rng } from '../realworld-business/lib.mjs';

const F = pay.FAMILIES; // overtime, student-loans, ni-categories, tax-codes, salary-sacrifice, ssp, starters, late-items, directors, pension-sacrifice, sunday-overtime
const FAMS = { 1: F.slice(0, 2), 2: F.slice(0, 4), 3: F.slice(0, 6), 4: F.slice(0, 8) };
for (let l = 5; l <= 10; l++) FAMS[l] = F.slice();

/** Mistakes that can be planted on each feature (the bureau's real-world errors). */
const MISTAKES_FOR = {
  plain: ['pension-full-pay', 'net-typo'],
  overtime: ['overtime-1x', 'hourly-rounded-early'],
  'overtime-late-fix': ['overtime-uncorrected'],
  'plain-sl1': ['sl-wrong-plan'],
  'plain-sl2': ['sl-rounded-penny'],
  'plain-sl4': ['sl-rounded-penny'],
  'plain-sl2-pgl': ['pgl-missing'],
  'over-pension-age': ['ni-cat-a-for-c'],
  apprentice: ['employer-ni-apprentice'],
  'apprentice-turned-25': ['apprentice-still-h'],
  'opted-out': ['pension-opted-out'],
  sacrifice: ['sacrifice-ni-before'],
  'k-code-cap': ['k-no-cap'],
  'starter-m1': ['m1-as-cumulative'],
  'starter-p45': ['p45-ignored'],
  'mid-month-starter': ['starter-calendar-days'],
  ssp: ['ssp-no-waiting', 'ssp-full-pay'],
  'code-change': ['old-code'],
  'higher-rate': ['higher-rate-flat'],
  director: ['director-ni-monthly'],
  'pension-sacrifice': ['pension-sacrifice-net-pay'],
  'sunday-ot': ['sunday-ot-1.5'],
  'half-penny': ['employer-ni-half-up'],
  hourly: [],
  'br-second-job': [],
  'k-code': [],
};

/** Features combined on one employee (levels 6-10): two rules acting on the same payslip. */
const COMBOS = [
  'sacrifice+plain-sl2', 'code-change+overtime', 'starter-p45+plain-sl1', 'pension-sacrifice+plain-sl2-pgl', 'ssp+plain-sl4',
  'k-code+sacrifice', 'mid-month-starter+plain-sl2', 'overtime-late-fix+sacrifice', 'higher-rate+sacrifice', 'over-pension-age+overtime',
  'code-change+plain-sl1', 'apprentice+sunday-ot', 'director+pension-sacrifice', 'ssp+overtime', 'starter-m1+plain-sl4',
];
const SINGLES = Object.keys(MISTAKES_FOR);

const famsOfKind = (k) => [...new Set(k.split('+').flatMap((x) => pay.KIND_FAMILIES[x]))];

/** Hand-made small levels; generated large ones. */
const HAND = {
  1: { staff: ['plain', 'overtime', 'plain-sl2', 'plain'], mistakes: { 1: 'overtime-1x' } },
  2: { staff: ['plain', 'br-second-job', 'over-pension-age', 'apprentice', 'k-code', 'plain-sl1'], mistakes: { 2: 'ni-cat-a-for-c', 5: 'sl-wrong-plan' } },
  3: { staff: ['plain', 'sacrifice', 'ssp', 'overtime', 'k-code', 'apprentice', 'plain-sl4', 'hourly', 'opted-out'], mistakes: { 1: 'sacrifice-ni-before', 2: 'ssp-no-waiting', 5: 'employer-ni-apprentice', 8: 'pension-opted-out' } },
  4: {
    staff: ['plain', 'starter-m1', 'starter-p45', 'mid-month-starter', 'code-change', 'overtime-late-fix', 'sacrifice', 'ssp', 'k-code-cap', 'over-pension-age', 'plain-sl2-pgl', 'higher-rate'],
    mistakes: { 1: 'm1-as-cumulative', 2: 'p45-ignored', 4: 'old-code', 5: 'overtime-uncorrected', 8: 'k-no-cap', 10: 'pgl-missing' },
  },
};
// level: [staff, share of combined employees, share with a planted mistake]
const SIZE = { 5: [20, 0, 0.45], 6: [32, 0.3, 0.45], 7: [46, 0.45, 0.45], 8: [64, 0.6, 0.45], 9: [88, 0.7, 0.45], 10: [120, 0.8, 0.45] };

function generated(level) {
  const [n, comboShare, mistakeShare] = SIZE[level];
  const r = rng(91000 + level * 101);
  const staff = [];
  const nCombo = Math.round(n * comboShare);
  // every single feature at least once, then the rest dealt at random; combos spread through the list
  const singles = SINGLES.slice();
  while (singles.length < n - nCombo) singles.push(r.pick(SINGLES));
  const combos = [];
  for (let i = 0; i < nCombo; i++) combos.push(COMBOS[i % COMBOS.length]);
  const all = r.shuffle([...singles.slice(0, n - nCombo), ...combos]);
  // the first two staff are always plain salaried staff (as on every real payroll)
  staff.push('plain', 'plain', ...all.filter((x, i) => !(i < 2 && x === 'plain')).slice(0, n - 2));
  // Who gets a mistake is decided here (a shuffled order); WHICH mistake is chosen once the employee's figures are
  // known (plantMistakes): the first of its candidates that really changes the draft.
  const want = Math.round(n * mistakeShare);
  const order = r.shuffle(staff.map((_, i) => i).slice(1)).map((i) => [i, r.shuffle(staff[i].split('+').flatMap((k) => MISTAKES_FOR[k]))]).filter(([, c]) => c.length);
  return { staff, mistakes: null, order, want };
}

const ER_ONLY = new Set(['employer-ni-apprentice', 'apprentice-still-h', 'employer-ni-half-up']);
const SIXF = ['gross', 'tax', 'ni', 'pension', 'sl', 'net'];
/** Plants `want` mistakes in the given order, each the first candidate that changes that employee's draft. */
function plantMistakes(spec) {
  const w0 = pay.generate({ ...spec, mistakes: {} });
  const mistakes = {};
  for (const [i, cands] of spec.order) {
    if (Object.keys(mistakes).length >= spec.want) break;
    const e = w0.staff[i];
    const ok = cands.find((m) => {
      const d = pay.calc(w0, e, pay.MISTAKE_FLAGS[m]);
      return ER_ONLY.has(m) ? d.erNi !== e.correct.erNi : SIXF.some((k) => d[k] !== e.correct[k]);
    });
    if (ok) mistakes[i] = ok;
  }
  return mistakes;
}

// Employer, pay day and tax month of every level.
const WHEN = [
  [0, [2026, 11, 27], 8], [1, [2026, 11, 27], 8], [2, [2026, 12, 18], 9], [0, [2026, 12, 23], 9], [1, [2027, 1, 29], 10],
  [2, [2027, 1, 29], 10], [0, [2027, 2, 26], 11], [1, [2027, 2, 26], 11], [2, [2027, 3, 26], 12], [0, [2027, 3, 26], 12],
];

export const SPECS = Array.from({ length: 10 }, (_, i) => {
  const level = i + 1;
  const fams = FAMS[level];
  const { staff, mistakes, order, want } = HAND[level] ?? generated(level);
  const [emp, payDay, month] = WHEN[i];
  for (const k of staff) for (const f of famsOfKind(k)) if (!fams.includes(f)) throw new Error(`L${level}: ${k} needs ${f}`);
  for (const m of [...Object.values(mistakes ?? {}), ...(order ?? []).flatMap(([, c]) => c)]) for (const f of pay.MISTAKE_FAMILIES[m]) if (!fams.includes(f)) throw new Error(`L${level}: ${m} needs ${f}`);
  return { id: `L${String(level).padStart(2, '0')}`, tier: `level ${level}`, level, seed: 8300 + level * 13, emp, pay: payDay, month, staff, mistakes, order, want, families: fams };
});

/** Every employee's features and every planted mistake must be covered by the printed rule families. */
export function familyCheck(world) {
  const bad = [];
  const fams = new Set(world.spec.families);
  for (const e of world.staff) {
    for (const f of famsOfKind(e.kind)) if (!fams.has(f)) bad.push(`${e.id} (${e.kind}) needs ${f}`);
    if (e.mistake) for (const f of pay.MISTAKE_FAMILIES[e.mistake]) if (!fams.has(f)) bad.push(`${e.id} mistake ${e.mistake} needs ${f}`);
  }
  return bad;
}

export const mod = {
  // The draft mistakes of the generated levels depend on the figures (a mistake must change the draft); the staff
  // do not depend on the mistakes, so planting them is a second pass over the same seeded world.
  generate: (spec) => (spec.mistakes ? pay.generate(spec) : pay.generate({ ...spec, mistakes: plantMistakes(spec) })),
  solve: pay.solve,
  selfCheck: (w, s) => [...pay.selfCheck(w, s), ...familyCheck(w)],
};

/** Slips of a careful checker, most subtle first: the first one that changes this level's key is its "slip" answer. */
const SLIPS = [
  ['employer NI half penny rounded up', { employerHalfUp: true }],
  ['overtime hourly rate rounded too early', { hourlyRoundedEarly: true }],
  ['student loan rounded to the penny, not down to the pound', { slRoundedPenny: true }],
  ['salary sacrifice taken after NI', { sacrificeNiBefore: true }],
];

export function scripted(world, sol) {
  const key = JSON.stringify(sol);
  for (const [kind, flags] of SLIPS) {
    const a = pay.flawed(world, flags);
    if (JSON.stringify(a) !== key) return { careless: pay.careless(world), slip: a, slip_kind: kind, flag_everything: { ...sol, wrong_payslips: world.staff.map((e) => e.id) } };
  }
  return { careless: pay.careless(world), slip: sol, slip_kind: 'none', flag_everything: { ...sol, wrong_payslips: world.staff.map((e) => e.id) } };
}

export const META = {
  id: 'work.payroll-ladder',
  name: 'Check the Payslips: Ladder',
  description:
    "Ten frozen levels of the same UK monthly payroll check: every rate and rule is given in the prompt, the bureau's draft payslips contain planted mistakes, and the answer is the set of wrong payslips, their six corrected figures to the penny and the employer NI total. Each level is strictly bigger and stacks more rule families of the same rates block: level 1 has 4 staff and the basics (salary, auto-enrolment pension, cumulative PAYE, NI category A) with overtime and student loans; level 2 adds NI categories C and H and BR/K codes; level 3 salary sacrifice and SSP; level 4 starters (P45, M1, mid-month) and late items; level 5 the full rates block (directors, pension by salary sacrifice, Sunday overtime) at the size of a hard Check the Payslips case; levels 6 to 10 grow to 120 staff, more and more of them combining several features on one payslip. A level is passed at 90% of its points; the headline is the highest level passed reliably.",
  difficulty: 'extreme',
  tags: ['real-world', 'work', 'payroll', 'paye', 'national-insurance', 'pensions', 'ladder', 'json'],
  hook: 'Ten rungs of payslips, from 4 staff to 120. How far up can it find every mistake and fix it to the penny?',
  maxOutputTokens: 64000,
  timeLimitSec: 1800,
  scorer: { type: 'json', numberTolerance: 1e-8, lenient: true, sets: { wrong_payslips: 1 } },
  engine: 'verification/realworld-business/payroll.mjs (calc)',
  check: 'the drafts are the same engine with the planted mistakes switched on, every planted mistake changes its draft, every net pay adds up, and every feature and mistake belongs to a printed rule family.',
};
