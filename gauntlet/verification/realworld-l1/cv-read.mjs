// work.cv-read: "Read a CV" (Business Ladder, Hiring & People, Level 1).
// One CV per case for a Payroll Officer vacancy; the screener fills in a form by written rules. Relevant
// experience is counted month by month from the role list (overlaps once), so the key is exact.
import { rngFor, person, monYear, HEDGE_RULE, assert } from './lib.mjs';

const MON = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
const FULL = ['January', 'February', 'March', 'April', 'May', 'June', 'July', 'August', 'September', 'October', 'November', 'December'];
const EMPLOYERS = ['Calder Valley Housing Association', 'Brightline Logistics Ltd', 'Stanmore Academy Trust', 'Northgate Foods plc', 'Pennine Health Partnership', 'Severn Leisure Ltd', 'Harbour Insurance Services', 'Larchwood Care Homes', 'Kestrel Engineering Ltd', 'Marlow District Council', 'Thornbury Retail Group', 'Ashby & Webb LLP'];

const isRelevant = (title) => /payroll|pensions/i.test(title);
const mi = ([y, m]) => y * 12 + (m - 1);

/** Months covered by relevant paid roles, each month once. */
function relevantMonths(roles, cvDate) {
  const set = new Set();
  for (const r of roles) {
    if (!isRelevant(r.title)) continue;
    const end = r.end === 'present' ? cvDate : r.end;
    for (let k = mi(r.start); k <= mi(end); k++) set.add(k);
  }
  return set.size;
}

const fmtStyles = {
  short: ([y, m]) => monYear(y, m),
  long: ([y, m]) => `${FULL[m - 1]} ${y}`,
  num: ([y, m]) => `${String(m).padStart(2, '0')}/${y}`,
};

/** Ten CVs, easiest first. Each plan lists roles newest first, qualifications, notice and right-to-work text. */
const PLANS = [
  {
    cv: [2026, 4], fmt: 'short',
    roles: [
      { title: 'Payroll Officer', start: [2022, 6], end: 'present', bullets: ['Run the monthly payroll for 380 staff on Sage 200', 'Process starters, leavers and statutory sick pay'] },
      { title: 'Payroll Assistant', start: [2019, 9], end: [2022, 5], bullets: ['Checked timesheets and overtime claims', 'Answered payslip queries'] },
    ],
    quals: ['BSc (Hons) Accounting and Finance, University of Leeds, 2019', 'A levels: Maths (A), Business (B), Economics (B)'],
    highest: "Bachelor's degree",
    notice: ['Notice period: 4 weeks', 4, 'weeks'],
    rtw: ['Right to work: I have the right to work in the UK.', 'yes'],
  },
  {
    cv: [2026, 2], fmt: 'short',
    roles: [
      { title: 'Payroll Administrator', start: [2023, 1], end: 'present', bullets: ['Weekly and monthly payrolls for 3 client companies', 'RTI submissions to HMRC'] },
      { title: 'Receptionist', start: [2020, 3], end: [2022, 8], bullets: ['Front desk for a busy dental practice', 'Took card payments and handled the petty cash'] },
    ],
    quals: ['A levels: Business Studies (B), English Literature (C), Psychology (C)', '9 GCSEs including Maths and English'],
    highest: 'A level',
    notice: ['Notice period: one month', 1, 'months'],
    rtw: null,
  },
  {
    cv: [2026, 5], fmt: 'long',
    roles: [
      { title: 'Senior Payroll Administrator', start: [2024, 2], end: 'present', bullets: ['Lead on payroll year-end and P11D returns'] },
      { title: 'Accounts Assistant', start: [2022, 7], end: [2024, 1], bullets: ['Purchase ledger, supplier statements and bank reconciliations'] },
      { title: 'Pensions Assistant', start: [2020, 10], end: [2022, 6], bullets: ['Auto-enrolment assessments and pension contribution files'] },
    ],
    quals: ['MSc Accounting, University of Strathclyde, 2020', 'BA (Hons) Economics, University of Strathclyde, 2019'],
    highest: "Master's degree",
    notice: ['Available immediately.', 0, 'weeks'],
    rtw: ['Visa: I will need sponsorship under the Skilled Worker route.', 'needs sponsorship'],
  },
  {
    cv: [2026, 3], fmt: 'short',
    roles: [
      { title: 'Payroll Officer', start: [2021, 4], end: 'present', bullets: ['End-to-end monthly payroll for 600 staff'] },
      { title: 'Payroll Clerk (part-time, evenings)', start: [2020, 1], end: [2021, 9], bullets: ['Kept on for six months after starting my full-time role to train my replacement'] },
    ],
    quals: ['MSc Human Resource Management (Open University, in progress, expected 2027)', 'BA (Hons) Business Management, Sheffield Hallam University, 2019'],
    highest: "Bachelor's degree",
    notice: ['Notice: 8 weeks', 8, 'weeks'],
    rtw: ['I am a British citizen.', 'yes'],
  },
  {
    cv: [2026, 6], fmt: 'long',
    roles: [
      { title: 'Treasurer (voluntary)', start: [2022, 1], end: 'present', voluntary: true, bullets: ['Keep the accounts for a junior football club'] },
      { title: 'Office Manager', employer: 'Payroll Partners Ltd', start: [2023, 5], end: 'present', bullets: ['Run the office of a payroll bureau: facilities, IT and suppliers', 'No payroll processing myself'] },
      { title: 'Payroll Assistant', start: [2018, 2], end: [2023, 4], bullets: ['BACS runs, deductions and attachment of earnings orders'] },
    ],
    quals: ['HND Business, Edinburgh College, 2017'],
    highest: 'HND or foundation degree',
    notice: ['Notice period: 1 month', 1, 'months'],
    rtw: ['Right to work in the UK: yes', 'yes'],
  },
  {
    cv: [2026, 1], fmt: 'short',
    roles: [
      { title: 'Payroll and Benefits Specialist', start: [2021, 11], end: 'present', bullets: ['Payroll for 1,200 employees across Spain and Portugal'] },
      { title: 'HR Administrator', start: [2019, 6], end: [2021, 10], bullets: ['Contracts, absence records and onboarding'] },
      { title: 'Payroll Technician', start: [2017, 9], end: [2019, 5], bullets: ['Monthly payroll and social security filings'] },
    ],
    quals: ['Licenciatura en Administración y Dirección de Empresas, Universidad Complutense de Madrid, 2017 (equivalent to a UK bachelor’s degree)'],
    highest: "Bachelor's degree",
    notice: ['Notice period: three months', 3, 'months'],
    rtw: null,
    extra: 'Languages: Spanish (native), English (fluent), Portuguese (good). Relocating to Bristol in the spring.',
  },
  {
    cv: [2026, 4], fmt: 'num',
    roles: [
      { title: 'Payroll Officer', employer: 'Severn Leisure Ltd', start: [2022, 1], end: 'present', bullets: ['Monthly payroll for 450 staff', 'Statutory payments and salary sacrifice schemes'] },
      { title: 'Finance Assistant', start: [2019, 9], end: [2021, 12], bullets: ['Ran the weekly payroll for 40 warehouse staff', 'Sales ledger and credit control'] },
    ],
    quals: ['A levels: Accounting (A), Maths (B)', 'CIPP Foundation Degree in Payroll Management (completed 2023)'],
    highest: 'HND or foundation degree',
    notice: ['Notice period: 1 month', 1, 'months'],
    rtw: ['Right to work: settled status under the EU Settlement Scheme.', 'yes'],
    update: 'Update: since writing this CV I have been promoted to Payroll Team Leader at the same company, and my notice period is now 2 months.',
    updateRole: 'Payroll Team Leader',
    updateNotice: [2, 'months'],
  },
  {
    cv: [2026, 3], fmt: 'num',
    roles: [
      { title: 'Payroll Supervisor', start: [2023, 9], end: 'present', bullets: ['Supervise a team of 3; sign off the monthly payroll'] },
      { title: 'Pensions Administrator (maternity cover)', start: [2023, 1], end: [2023, 12], bullets: ['Covered a 12-month maternity leave'] },
      { title: 'Payroll Administrator', start: [2019, 2], end: [2022, 7], bullets: ['Payroll for a group of 6 schools'] },
    ],
    careerBreak: 'Aug 2022 – Dec 2022: career break (travelling).',
    quals: ['PhD in Medieval History, University of York, 2018', 'MA History, University of York, 2014'],
    highest: 'Doctorate',
    notice: ['Notice period: 6 weeks', 6, 'weeks'],
    rtw: ['No visa sponsorship required.', 'yes'],
  },
  {
    cv: [2026, 7], fmt: 'long',
    roles: [
      { title: 'Payroll Manager', start: [2024, 6], end: 'present', bullets: ['Own payroll for 2,000 staff; migrated us to a new payroll system'] },
      { title: 'Interim Payroll Consultant (self-employed)', start: [2023, 3], end: [2024, 9], bullets: ['Short contracts while job-hunting; overlapped my first months as manager'] },
      { title: 'Credit Controller', start: [2021, 1], end: [2023, 2], bullets: ['Chased overdue invoices; worked with the payroll team on expenses'] },
      { title: 'Pensions Officer', start: [2018, 4], end: [2020, 11], bullets: ['Scheme administration for a local authority fund'] },
    ],
    quals: ['BSc Mathematics, Cardiff University, 2017', 'MBA (Cardiff Business School), started 2025, not completed'],
    highest: "Bachelor's degree",
    notice: ['Notice period: 3 months (I could negotiate an earlier release)', 3, 'months'],
    rtw: ['I would need sponsorship to work in the UK.', 'needs sponsorship'],
  },
  {
    cv: [2026, 2], fmt: 'short',
    roles: [
      { title: 'Payroll & Pensions Lead', employer: 'Larchwood Care Homes', start: [2024, 11], end: 'present', bullets: ['Payroll and pensions for a group of 9 care homes'] },
      { title: 'Pensions Assistant (part-time)', employer: 'Stanmore Academy Trust', start: [2023, 10], end: [2025, 3], bullets: ['Kept two days a week at my previous employer during handover'] },
      { title: 'Payroll Officer', employer: 'Stanmore Academy Trust', start: [2021, 5], end: [2024, 10], bullets: ['Monthly payroll for 700 staff'] },
      { title: 'Finance Assistant', employer: 'Ashby & Webb LLP', start: [2019, 8], end: [2021, 4], bullets: ['Processed the payroll for the head office alongside the purchase ledger'] },
      { title: 'Volunteer Payroll Mentor (voluntary)', employer: 'Payroll Futures (charity)', start: [2022, 1], end: 'present', voluntary: true, bullets: ['Mentor two apprentices through a charity scheme'] },
    ],
    quals: ['BA (Hons) Accounting, University of Ulster, 2019', 'Level 4 CIPP Diploma in Payroll Practice (in progress)'],
    highest: "Bachelor's degree",
    notice: ['Notice period: 12 weeks', 12, 'weeks'],
    rtw: null,
    extra: 'Previously lived and studied in Lagos; moved to the UK in 2015.',
    update: 'Note added 3 Feb 2026: my notice period has changed to 1 month following a contract change.',
    updateNotice: [1, 'months'],
  },
];

function build(plan, i) {
  const rng = rngFor(`cv-read/c${i + 1}`);
  const name = person(rng);
  const emps = rng.sample(EMPLOYERS, plan.roles.length);
  const roles = plan.roles.map((r, k) => ({ ...r, employer: r.employer ?? emps[k] }));
  const months = relevantMonths(roles.filter((r) => !r.voluntary), plan.cv);
  const current = roles.filter((r) => r.end === 'present' && !r.voluntary);
  assert(current.length === 1, 'exactly one current paid job');
  const f = fmtStyles[plan.fmt];
  const lines = [];
  lines.push(name.toUpperCase());
  lines.push(`${rng.pick(['Leeds', 'Bristol', 'Glasgow', 'Cardiff', 'Belfast', 'Norwich', 'Leicester'])} · ${name.split(' ')[0].toLowerCase()}.${name.split(' ')[1].toLowerCase()}@example.com · CV updated ${FULL[plan.cv[1] - 1]} ${plan.cv[0]}`);
  lines.push('');
  lines.push('PROFILE');
  lines.push(rng.pick(['Organised, accurate payroll professional who enjoys getting the detail right.', 'Calm under deadline pressure, with a strong eye for detail and a love of a reconciled payroll.', 'Detail-focused payroll specialist looking for a role with more responsibility.']));
  lines.push('');
  lines.push('EXPERIENCE');
  for (const r of roles) {
    lines.push(`${r.title} — ${r.employer}, ${f(r.start)} – ${r.end === 'present' ? 'Present' : f(r.end)}`);
    for (const b of r.bullets) lines.push(`  • ${b}`);
  }
  if (plan.careerBreak) lines.push(plan.careerBreak);
  lines.push('');
  lines.push('EDUCATION & QUALIFICATIONS');
  for (const q of plan.quals) lines.push(`  • ${q}`);
  lines.push('');
  lines.push('OTHER');
  lines.push(`  • ${plan.notice[0]}`);
  if (plan.rtw) lines.push(`  • ${plan.rtw[0]}`);
  if (plan.extra) lines.push(`  • ${plan.extra}`);
  lines.push('  • Full UK driving licence');
  if (plan.update) {
    lines.push('');
    lines.push(plan.update);
  }
  const cur = current[0];
  const expected = {
    current_role: plan.updateRole ?? cur.title,
    current_employer: cur.employer,
    relevant_experience_months: months,
    highest_qualification: plan.highest,
    notice_period: { amount: (plan.updateNotice ?? plan.notice.slice(1))[0], unit: (plan.updateNotice ?? plan.notice.slice(1))[1] },
    right_to_work: plan.rtw ? plan.rtw[1] : 'not stated',
  };
  // Decoy: the naive sum of relevant role lengths (double-counting overlaps) must differ when roles overlap.
  const naive = roles
    .filter((r) => !r.voluntary && isRelevant(r.title))
    .reduce((s, r) => s + mi(r.end === 'present' ? plan.cv : r.end) - mi(r.start) + 1, 0);
  const prompt = [
    `We are hiring a Payroll Officer. Read this CV and fill in the screening form using the rules below. Use only what the CV says.`,
    '',
    'CV:',
    '<<<',
    lines.join('\n'),
    '>>>',
    '',
    'Rules:',
    '- current_role and current_employer: their current PAID job (the one running to "Present"). Ignore voluntary roles. If the CV says their job title has since changed, use the new title.',
    `- relevant_experience_months: count the calendar months worked in paid roles whose JOB TITLE contains "Payroll" or "Pensions" (any capitals). The start month and the end month both count ("Mar 2020 – Jun 2020" is 4 months); "Present" means up to and including the month the CV was updated (${FULL[plan.cv[1] - 1]} ${plan.cv[0]}). A month covered by two relevant roles at once counts only once. Duties listed under other job titles, and the employer’s name, do not count.`,
    `- highest_qualification: their highest COMPLETED qualification, one of: "GCSE", "A level", "HND or foundation degree", "Bachelor's degree", "Master's degree", "Doctorate". (BSc/BA = bachelor's; MSc/MA/MBA = master's; PhD = doctorate.) A qualification in progress or not completed does not count. Professional diplomas that are not degrees do not count.`,
    '- notice_period: their current notice period as written, as {"amount": number, "unit": "weeks" or "months"}; "available immediately" is {"amount": 0, "unit": "weeks"}. If the CV says it has changed, use the new one.',
    '- right_to_work: "yes" if the CV states they can work in the UK without sponsorship (any wording, e.g. "British citizen", "settled status", "no sponsorship required"); "needs sponsorship" if it says they would need a visa sponsor; "not stated" if it says neither. Never infer it from a name, nationality, languages or where someone studied or worked.',
    '',
    'Reply with ONE JSON object (a ```json block is fine):',
    '{"current_role": string, "current_employer": string, "relevant_experience_months": number, "highest_qualification": string, "notice_period": {"amount": number, "unit": string}, "right_to_work": string}',
    `${HEDGE_RULE} Every field is checked separately.`,
  ].join('\n');
  const notes = `relevant months ${months} (naive sum without merging overlaps ${naive}); roles: ${roles.map((r) => `${r.title} ${f(r.start)}-${r.end === 'present' ? 'present' : f(r.end)}${r.voluntary ? ' (voluntary)' : ''}${isRelevant(r.title) && !r.voluntary ? ' [relevant]' : ''}`).join('; ')}`;
  // A careless screener: adds up role lengths (overlaps twice), counts any listed degree, assumes the right to work,
  // ignores the update note and takes the employer with "Payroll" in its name as payroll experience.
  const listed = plan.quals.join(' ');
  const careless = {
    ...expected,
    current_role: cur.title,
    relevant_experience_months: naive + roles.filter((r) => !r.voluntary && !isRelevant(r.title) && /payroll/i.test(r.employer + r.bullets.join(' '))).reduce((s, r) => s + mi(r.end === 'present' ? plan.cv : r.end) - mi(r.start) + 1, 0),
    highest_qualification: /PhD/.test(listed) ? 'Doctorate' : /\bM(Sc|A|BA)\b/.test(listed) ? "Master's degree" : expected.highest_qualification,
    notice_period: { amount: plan.notice[1], unit: plan.notice[2] },
    right_to_work: expected.right_to_work === 'not stated' ? 'yes' : expected.right_to_work,
  };
  return { case: { id: `c${String(i + 1).padStart(2, '0')}`, prompt, expected, notes }, careless, naive };
}

export const META = {
  id: 'work.cv-read',
  name: 'Read a CV',
  description:
    'Business Ladder, Hiring & People, Level 1 (Assist). One CV per case for a Payroll Officer job: fill in the screening form by written rules (current paid job, months of relevant experience by a stated counting rule with overlaps counted once, highest completed qualification, notice period, and the right-to-work statement exactly as written). Later CVs add the real traps: a degree still in progress, a voluntary role running to "Present", an employer with "Payroll" in its name, payroll duties under another title, overlapping part-time roles, a promotion and a changed notice period noted at the end, and candidates whose right to work must never be guessed from their background.',
  difficulty: 'easy',
  tags: ['business-ladder', 'hiring', 'level-1', 'extraction', 'json', 'dates', 'fairness', 'uk'],
  hook: 'One CV, six boxes. Count the experience properly and never guess what isn’t written.',
  outputTokens: 900,
  scorer: { type: 'json', lenient: true, aliases: { 'bachelors degree': "Bachelor's degree", 'bachelor’s degree': "Bachelor's degree", "bachelor's": "Bachelor's degree", 'masters degree': "Master's degree", 'master’s degree': "Master's degree", "master's": "Master's degree", 'a-level': 'A level', 'a levels': 'A level', 'phd': 'Doctorate', 'week': 'weeks', 'month': 'months', 'not specified': 'not stated' } },
};

export function generate() {
  return PLANS.map((p, i) => build(p, i));
}
