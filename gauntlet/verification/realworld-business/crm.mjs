// work.crm-dedupe — "Clean the Customer List".
// Invented people get one to four records across two systems, each extra record made by a realistic variation
// (nickname, Mc/Mac, a one-letter typo, swapped name fields, +44 phone format, a move, a married-name change, a
// missing DOB). Distractors are planted on purpose: a shared office mailbox, the same name at another company, a
// father and son at one address. solve() applies ONLY the written matching rules (union-find over matching pairs,
// with the DOB block) and the master rule; build.mjs asserts the result equals the planted people exactly.

import { rng, D, addDays, iso, assert, ONE_ANSWER } from './lib.mjs';

export const NICK = [
  ['Robert', 'Bob', 'Rob', 'Bobby'], ['William', 'Bill', 'Will'], ['Elizabeth', 'Liz', 'Beth', 'Lizzie'], ['Katherine', 'Kate', 'Kathy'],
  ['Michael', 'Mike'], ['James', 'Jim', 'Jamie'], ['David', 'Dave'], ['Anthony', 'Tony'], ['Margaret', 'Maggie', 'Peggy'], ['Jonathan', 'Jon'],
  ['Alexander', 'Alex'], ['Christopher', 'Chris'], ['Thomas', 'Tom'], ['Daniel', 'Dan'], ['Rebecca', 'Becky', 'Becca'], ['Victoria', 'Vicky', 'Tori'],
  ['Patrick', 'Paddy'], ['Abdullah', 'Abdul'], ['Mohammed', 'Mo'], ['Oluwaseun', 'Seun'], ['Aleksandra', 'Ola'],
];
const FIRSTS = [
  'Robert', 'William', 'Elizabeth', 'Katherine', 'Michael', 'James', 'David', 'Anthony', 'Margaret', 'Jonathan', 'Alexander', 'Christopher', 'Thomas',
  'Daniel', 'Rebecca', 'Victoria', 'Patrick', 'Abdullah', 'Mohammed', 'Oluwaseun', 'Aleksandra', 'Priya', 'Sinead', 'Hamza', 'Leah', 'Kofi', 'Ingrid',
  'Yusuf', 'Chloe', 'Arjun', 'Fatima', 'Megan', 'Rhys', 'Zara', 'Darius', 'Niamh', 'Hassan', 'Lauren', 'Tariq', 'Aoife', 'Ewan', 'Sophie', 'Imogen',
  'Kwabena', 'Anika', 'Callum', 'Bethany', 'Jasmine', 'Oscar', 'Harriet', 'Ravi', 'Agata', 'Shauna', 'Tomasz', 'Nia', 'Adeel', 'Grace', 'Ellis',
];
const LASTS = [
  'McDonald', 'MacKenzie', 'McLean', 'MacLeod', "O'Neill", 'Thompson', 'Patel', 'Khan', 'Kowalski', 'Okafor', 'Hughes', 'Barker', 'Fletcher', 'Ahmed',
  'Gallagher', 'Sutcliffe', 'Haigh', 'Lindqvist', 'Chowdhury', 'Brennan', 'Asante', 'Whitworth', 'Ramsden', 'Nowak', 'Iqbal', 'Pemberton', 'Shaw',
  'Hirst', 'Booth', 'Armitage', 'Robertson', 'Kaur', 'Dlamini', 'Ferguson', 'Lockwood', 'Mistry', 'Holroyd', 'Wilkinson', 'Begum', 'Charlton',
  'Sandhu', 'Eastwood', 'Ibrahim', 'Marsden', 'Quinn', 'Stewart', 'Taylor-Briggs', 'Varga', 'Yildiz', 'Zhang', 'Dobson', 'Greenwood', 'Hussain',
];
const STREETS = ['Wakefield Road', 'Bradford Road', 'Halifax Old Road', 'Lindley Moor Road', 'Trinity Street', 'New Hey Road', 'Penistone Road', 'Leeds Road', 'Manchester Road', 'Birkby Hall Road', 'Lidget Street', 'Almondbury Bank', 'Fenay Lane', 'Thornhill Road', 'Kirkgate'];
const DISTRICTS = ['HD1', 'HD2', 'HD3', 'HD4', 'HD5', 'HD7', 'HD8', 'HD9', 'WF14', 'HX5'];
const EMPLOYERS = ['Calder Engineering Ltd', 'Kirklees Council', 'Huddersfield Royal Infirmary', 'Pennine Print Works', 'Colne Valley Mills', 'Holme Logistics', 'University of Huddersfield', 'Lindley Dental Group', null, null, null, null];
const SHARED = ['office', 'info', 'accounts', 'admin', 'reception', 'hello', 'enquiries', 'bookings', 'hr', 'team'];

export const SPECS = [
  { id: 'd01', tier: 'easy', seed: 71, n: 60, dupShare: 0.35, tricky: 0.15 },
  { id: 'd02', tier: 'easy', seed: 72, n: 80, dupShare: 0.35, tricky: 0.2 },
  { id: 'd03', tier: 'easy', seed: 73, n: 100, dupShare: 0.4, tricky: 0.25 },
  { id: 'd04', tier: 'medium', seed: 74, n: 130, dupShare: 0.42, tricky: 0.45 },
  { id: 'd05', tier: 'medium', seed: 75, n: 160, dupShare: 0.42, tricky: 0.55 },
  { id: 'd06', tier: 'medium', seed: 76, n: 180, dupShare: 0.45, tricky: 0.6 },
  { id: 'd07', tier: 'hard', seed: 77, n: 200, dupShare: 0.48, tricky: 0.75 },
  { id: 'd08', tier: 'hard', seed: 78, n: 210, dupShare: 0.5, tricky: 0.8 },
  { id: 'd09', tier: 'hard', seed: 79, n: 220, dupShare: 0.5, tricky: 0.85 },
  { id: 'd10', tier: 'hard', seed: 80, n: 520, dupShare: 0.5, tricky: 0.85 },
];

// ───────────────────────────── Normalisation and rules (shared wording with the prompt) ─────────────────────────────
const nameNorm = (s) => s.toLowerCase().replace(/[\s\-'.]/g, '');
const surnameNorm = (s) => nameNorm(s).replace(/^mac/, 'mc');
export const phoneNorm = (s) => {
  if (!s) return '';
  let d = s.replace(/\D/g, '');
  if (d.startsWith('44')) d = '0' + d.slice(2);
  return d;
};
const emailNorm = (s) => (s ? s.trim().toLowerCase() : '');
const pcNorm = (s) => (s ? s.toUpperCase().replace(/\s/g, '') : '');
const addrNorm = (s) => (s ? s.toLowerCase().replace(/[^a-z0-9]/g, '') : '');
const NICK_OF = new Map();
NICK.forEach((g, i) => g.forEach((n) => NICK_OF.set(n.toLowerCase(), i)));

function oneEdit(a, b) {
  if (a === b) return true;
  if (Math.abs(a.length - b.length) > 1) return false;
  let i = 0;
  while (i < a.length && i < b.length && a[i] === b[i]) i++;
  if (a.length === b.length) return a.slice(i + 1) === b.slice(i + 1);
  if (a.length > b.length) return a.slice(i + 1) === b.slice(i);
  return a.slice(i) === b.slice(i + 1);
}
let F = {}; // rule switches, only for scripted flawed answers
export function firstMatch(a, b) {
  const x = nameNorm(a);
  const y = nameNorm(b);
  if (x === y) return true;
  if (F.noNick) return false;
  const gx = NICK_OF.get(x);
  if (gx !== undefined && gx === NICK_OF.get(y)) return true;
  return false;
}
export function surnameMatch(a, b) {
  const x = F.noMac ? nameNorm(a) : surnameNorm(a);
  const y = F.noMac ? nameNorm(b) : surnameNorm(b);
  if (x === y) return true;
  if (F.noTypo) return false;
  return Math.min(x.length, y.length) >= 5 && oneEdit(x, y);
}
const isShared = (email) => SHARED.includes(emailNorm(email).split('@')[0]);

/** Names match as written, or with one record's first and last name fields swapped. */
function namesMatch(a, b) {
  return (firstMatch(a.first, b.first) && surnameMatch(a.last, b.last)) || (firstMatch(a.first, b.last) && surnameMatch(a.last, b.first));
}
function firstOf(a, b) {
  // The first-name comparison under the better orientation (for rules that only need first names).
  return firstMatch(a.first, b.first) || firstMatch(a.first, b.last) || firstMatch(a.last, b.first);
}

export function pairRule(a, b) {
  if (!F.noBlock && a.dob && b.dob && a.dob !== b.dob) return null; // block
  if (!F.noNotes && ((a.note && a.note.includes(b.id)) || (b.note && b.note.includes(a.id)))) return null; // checked: not the same person
  const e = emailNorm(a.email);
  if (e && e === emailNorm(b.email) && (F.sharedOk || !isShared(a.email)) && (F.sharedOk || firstOf(a, b))) return 'R1';
  const p = phoneNorm(a.phone);
  const samePhone = p && p === phoneNorm(b.phone);
  if (samePhone && namesMatch(a, b)) return 'R2';
  if (a.dob && a.dob === b.dob && namesMatch(a, b)) return 'R3';
  if (samePhone && a.dob && a.dob === b.dob && firstOf(a, b)) return 'R4';
  if (namesMatch(a, b) && pcNorm(a.postcode) && pcNorm(a.postcode) === pcNorm(b.postcode) && addrNorm(a.address) && addrNorm(a.address) === addrNorm(b.address)) return 'R5';
  return null;
}

export function clustersOf(records) {
  const parent = records.map((_, i) => i);
  const find = (i) => (parent[i] === i ? i : (parent[i] = find(parent[i])));
  for (let i = 0; i < records.length; i++) for (let j = i + 1; j < records.length; j++) if (pairRule(records[i], records[j])) parent[find(i)] = find(j);
  const groups = new Map();
  records.forEach((r, i) => {
    const k = find(i);
    if (!groups.has(k)) groups.set(k, []);
    groups.get(k).push(r);
  });
  return [...groups.values()];
}

const byRecency = (a, b) => b.activity.localeCompare(a.activity) || (a.id[0] === 'M' ? 0 : 1) - (b.id[0] === 'M' ? 0 : 1) || a.id.localeCompare(b.id);

export function masterOf(group) {
  const live = group.filter((x) => F.noGoneAway || x.status !== 'gone away');
  return (live.length ? live : group).slice().sort(byRecency)[0];
}

/** The surviving email and phone of a group: the most recent non-blank value (email: not a shared mailbox). */
export function goldenOf(group) {
  const order = F.goldenFromMaster ? [masterOf(group)] : group.slice().sort(byRecency);
  const em = order.find((x) => x.email && (F.sharedOk || !isShared(x.email)));
  const ph = order.find((x) => x.phone);
  return { email: em ? emailNorm(em.email) : null, phone: ph ? phoneNorm(ph.phone) : null };
}

// ───────────────────────────── Generator ─────────────────────────────
export function generate(spec) {
  const r = rng(spec.seed * 4099);
  const people = [];
  const records = [];
  let pid = 0;
  const usedPhones = new Set();
  const mobile = () => {
    for (;;) {
      const p = `07${r.int(100, 999)}${String(r.int(0, 999999)).padStart(6, '0')}`;
      if (!usedPhones.has(p)) return usedPhones.add(p), p;
    }
  };
  const landline = () => {
    for (;;) {
      const p = `01484${String(r.int(200000, 999999))}`;
      if (!usedPhones.has(p)) return usedPhones.add(p), p;
    }
  };
  const fmtPhone = (p, style) => {
    if (style === 0) return p;
    if (style === 1) return p.startsWith('07') ? `${p.slice(0, 5)} ${p.slice(5)}` : `${p.slice(0, 5)} ${p.slice(5)}`;
    if (style === 2) return `+44 ${p.slice(1, 5)} ${p.slice(5)}`;
    return `+44${p.slice(1)}`;
  };
  const address = () => ({ address: `${r.int(1, 240)} ${r.pick(STREETS)}`, postcode: `${r.pick(DISTRICTS)} ${r.int(1, 9)}${String.fromCharCode(65 + r.int(0, 25))}${String.fromCharCode(65 + r.int(0, 25))}` });
  const dob = () => iso(D(r.int(1950, 2005), r.int(1, 12), r.int(1, 28)));
  const emailFor = (first, last, employer) => {
    const dom = r.pick(['gmail.com', 'outlook.com', 'btinternet.com', 'yahoo.co.uk', 'hotmail.co.uk', 'icloud.com', 'sky.com']);
    const l = last.toLowerCase().replace(/[^a-z]/g, '');
    const f = first.toLowerCase();
    return r.pick([`${f}.${l}@${dom}`, `${f[0]}${l}${r.int(1, 99)}@${dom}`, `${f}${l}@${dom}`, `${l}.${f}${r.int(70, 99)}@${dom}`]);
  };
  const activity = () => iso(addDays(D(2026, 10, 31), -r.int(0, 900)));
  const newPerson = (overrides = {}) => {
    const first = overrides.first ?? r.pick(FIRSTS);
    const last = overrides.last ?? r.pick(LASTS);
    const employer = overrides.employer !== undefined ? overrides.employer : r.pick(EMPLOYERS);
    const p = { pid: pid++, first, last, dob: overrides.dob ?? dob(), email: overrides.email ?? emailFor(first, last, employer), phone: overrides.phone ?? (r.chance(0.8) ? mobile() : landline()), ...address(), employer, records: [] };
    if (overrides.address) Object.assign(p, overrides.address);
    people.push(p);
    return p;
  };
  const rec = (p, sys, f = {}) => {
    const x = {
      sys,
      first: f.first ?? p.first,
      last: f.last ?? p.last,
      dob: f.dob !== undefined ? f.dob : sys === 'B' && r.chance(0.5) ? '' : p.dob,
      email: f.email !== undefined ? f.email : p.email,
      phone: f.phone !== undefined ? f.phone : fmtPhone(p.phone, r.int(0, 3)),
      postcode: f.postcode ?? p.postcode,
      address: f.address ?? p.address,
      employer: f.employer !== undefined ? f.employer : p.employer ?? '',
      activity: f.activity ?? activity(),
      status: f.status ?? '',
      note: '',
      pid: p.pid,
    };
    p.records.push(x);
    records.push(x);
    return x;
  };
  const nickOf = (name) => {
    const g = NICK.find((x) => x.includes(name));
    return g ? r.pick(g.filter((n) => n !== name)) : null;
  };
  const typo = (s) => {
    if (s.length < 6) return null;
    const i = r.int(2, s.length - 2);
    const ops = [() => s.slice(0, i) + s.slice(i + 1), () => s.slice(0, i) + s[i] + s.slice(i), () => s.slice(0, i) + s[i + 1] + s[i] + s.slice(i + 2)];
    const k = r.int(0, 1); // deletion or doubling only (one edit)
    const out = ops[k]();
    return /[a-z]/i.test(out) ? out : null;
  };
  const macSwap = (s) => (/^Mc/.test(s) ? s.replace(/^Mc/, 'Mac') : /^Mac/.test(s) ? s.replace(/^Mac/, 'Mc') : null);

  // Variation operators for a second (or third) record of the same person, each keeping a rule-satisfying link.
  const variants = {
    copy: (p) => rec(p, r.pick(['M', 'B'])),
    nickname: (p) => rec(p, 'B', { first: nickOf(p.first) ?? p.first }),
    typo: (p) => rec(p, 'B', { last: typo(p.last) ?? p.last, email: '' }),
    mac: (p) => rec(p, 'B', { last: macSwap(p.last) ?? p.last }),
    swapped: (p) => rec(p, 'B', { first: p.last, last: p.first, email: '' }),
    phone44: (p) => rec(p, 'B', { phone: fmtPhone(p.phone, 2 + r.int(0, 1)), email: '' }),
    emailCase: (p) => rec(p, 'B', { email: p.email.toUpperCase().replace('@', '@'), phone: '' }),
    moved: (p) => {
      const a = address();
      const dobKnown = p.records.some((x) => x.dob);
      return rec(p, 'M', { ...a, phone: mobile(), email: !dobKnown || r.chance(0.5) ? p.email : emailFor(p.first, p.last), dob: p.dob });
    },
    married: (p) => {
      if (!p.records.some((x) => x.dob && phoneNorm(x.phone) === p.phone)) return variants.nickname(p);
      const nl = r.pick(LASTS.filter((l) => l !== p.last));
      return rec(p, 'M', { last: nl, email: emailFor(p.first, nl), dob: p.dob, phone: fmtPhone(p.phone, r.int(0, 3)), ...address() });
    },
  };
  // A record that links only to the person's LATEST record (a chain: A-B by one rule, B-C by another).
  variants.chain = (p) => {
    const last = p.records[p.records.length - 1];
    if (!last.phone || p.records.length < 2) return variants.moved(p);
    return rec(p, 'B', { first: nickOf(last.first) ?? last.first, last: last.last, phone: fmtPhone(phoneNorm(last.phone), r.int(0, 3)), email: '', dob: '', address: '', postcode: '' });
  };
  const easyOps = ['copy', 'nickname', 'phone44', 'emailCase', 'mac'];
  const hardOps = ['typo', 'swapped', 'moved', 'married', 'nickname', 'mac', 'chain'];

  // Planted distractors first.
  const distractors = [];
  const notes = [];
  const nDistract = Math.max(1, Math.min(4, Math.round(spec.n / 80)));
  for (let k = 0; k < nDistract; k++) {
    // Shared office mailbox: two different people at one firm.
    const firm = r.pick(['Calder Engineering Ltd', 'Pennine Print Works', 'Holme Logistics']);
    const box = `${r.pick(SHARED)}@${firm.toLowerCase().split(' ')[0]}-${firm.toLowerCase().split(' ')[1]}.co.uk`;
    const a = newPerson({ employer: firm, email: box });
    const b = newPerson({ employer: firm, email: box, first: r.pick(FIRSTS.filter((f) => !firstMatch(f, a.first))) });
    rec(a, 'B', { dob: '' });
    rec(b, 'B', { dob: '' });
    distractors.push('shared-mailbox');
    // Same name, different person at another company.
    const c = newPerson({ employer: r.pick(['Kirklees Council', 'Colne Valley Mills']) });
    const d = newPerson({ first: c.first, last: c.last, employer: 'Huddersfield Royal Infirmary' });
    rec(c, 'M');
    rec(d, 'B', { dob: '' });
    distractors.push('same-name');
    if (spec.tricky >= 0.4) {
      // Two different people with the same name and date of birth: R3 would merge them, but staff checked.
      const e1 = newPerson();
      const e2 = newPerson({ first: e1.first, last: e1.last, dob: e1.dob });
      const r1 = rec(e1, 'M', { dob: e1.dob });
      const r2 = rec(e2, 'B', { dob: e2.dob });
      notes.push([r2, r1]);
      distractors.push('same-name-same-dob');
    }
    if (spec.tricky >= 0.3) {
      // Father and son at one address with one landline; both have their DOB in MemberHub.
      const home = address();
      const phone = landline();
      const fa = newPerson({ dob: iso(D(r.int(1955, 1968), r.int(1, 12), r.int(1, 28))), phone, address: home, employer: null });
      const so = newPerson({ first: fa.first, last: fa.last, dob: iso(D(r.int(1990, 2002), r.int(1, 12), r.int(1, 28))), phone, address: home, employer: null, email: emailFor(fa.first + 'jr', fa.last) });
      rec(fa, 'M', { dob: fa.dob });
      rec(so, 'M', { dob: so.dob });
      // The son also books classes from his own mobile in the app, with his own email and no address.
      if (r.chance(0.7)) rec(so, 'B', { dob: '', phone: mobile(), email: so.email, address: '', postcode: '' });
      distractors.push('father-son');
    }
  }
  // Everyone else.
  while (records.length < spec.n) {
    const p = newPerson();
    rec(p, r.chance(0.6) ? 'M' : 'B');
    if (r.chance(spec.dupShare)) {
      const extra = r.chance(0.2) ? 2 : 1;
      for (let k = 0; k < extra && records.length < spec.n; k++) {
        const op = r.chance(spec.tricky) ? r.pick(k > 0 ? hardOps : hardOps.filter((o) => o !== 'chain')) : r.pick(easyOps);
        variants[op](p);
      }
      // Mail returned "gone away" on a group's most recent record: it can no longer be the master.
      if (p.records.length > 1 && r.chance(spec.tricky * 0.35)) p.records.slice().sort((a, b) => b.activity.localeCompare(a.activity))[0].status = 'gone away';
    } else if (r.chance(0.03)) p.records[0].status = 'gone away';
  }
  // Ids: MemberHub M-1xxxx, BookIt B-2xxxx, then shuffle the export order.
  const shuffled = r.shuffle(records);
  let m = 10000 + (spec.seed % 997) * 3;
  let b = 20000 + (spec.seed % 997) * 5;
  for (const x of shuffled) x.id = x.sys === 'M' ? `M-${(m += r.int(1, 9))}` : `B-${(b += r.int(1, 9))}`;
  shuffled.sort((x, y) => x.id.localeCompare(y.id));
  for (const [x, other] of notes) x.note = `checked by phone ${r.pick(['3 Sep', '17 Jun', '22 Oct', '9 Feb'])}: not the same person as ${other.id}`;
  for (const x of shuffled) if (!x.note && r.chance(0.04)) x.note = r.pick(['prefers email', 'VIP - corporate scheme', 'asked for paper invoices', 'photo ID seen']);
  return { spec, records: shuffled, people, distractors };
}

export function solve(world) {
  const groups = clustersOf(world.records).filter((g) => g.length > 1);
  const clusters = groups.map((g) => g.map((x) => x.id).sort()).sort((a, b) => a[0].localeCompare(b[0]));
  const golden = Object.fromEntries(groups.map((g) => [masterOf(g).id, goldenOf(g)]).sort((a, b) => a[0].localeCompare(b[0])));
  return { clusters, golden };
}

/** Careless cleaner: merges on exact email or exact phone text only, keeps the first id. */
export function careless(world) {
  const by = new Map();
  for (const x of world.records) for (const k of [x.email && `e:${x.email}`, x.phone && `p:${x.phone}`].filter(Boolean)) (by.get(k) ?? by.set(k, []).get(k)).push(x.id);
  const parent = new Map(world.records.map((x) => [x.id, x.id]));
  const find = (i) => (parent.get(i) === i ? i : find(parent.get(i)));
  for (const ids of by.values()) for (const id of ids.slice(1)) parent.set(find(id), find(ids[0]));
  const g = new Map();
  for (const x of world.records) (g.get(find(x.id)) ?? g.set(find(x.id), []).get(find(x.id))).push(x.id);
  const clusters = [...g.values()].filter((c) => c.length > 1).map((c) => c.sort());
  const recOf = (id) => world.records.find((x) => x.id === id);
  return { clusters, golden: Object.fromEntries(clusters.map((c) => [c[0], { email: recOf(c[0]).email || null, phone: recOf(c[0]).phone || null }])) };
}

export function flawedSolve(world, flags) {
  F = flags;
  try {
    return solve(world);
  } finally {
    F = {};
  }
}

export function scripted(world, sol) {
  const all = world.records.map((x) => x.id);
  return {
    careless: careless(world),
    merge_everything: { clusters: [all], golden: { [all[0]]: { email: null, phone: null } } },
    merge_nothing: { clusters: [], golden: {} },
    flawed_no_block_shared_mailbox: flawedSolve(world, { noBlock: true, sharedOk: true }),
    flawed_strict_names: flawedSolve(world, { noNick: true, noMac: true, noTypo: true }),
    flawed_new_rules: flawedSolve(world, { noNotes: true, noGoneAway: true, goldenFromMaster: true }),
    careful_human_slips: flawedSolve(world, { goldenFromMaster: true }),
    right_groups_wrong_masters: { clusters: sol.clusters, golden: Object.fromEntries(sol.clusters.map((c) => [c.find((id) => !(id in sol.golden)) ?? c[0], { email: null, phone: null }])) },
  };
}

export function selfCheck(world, sol) {
  const bad = [];
  const truth = world.people.filter((p) => p.records.length > 1).map((p) => p.records.map((x) => x.id).sort()).sort((a, b) => a[0].localeCompare(b[0]));
  if (JSON.stringify(truth) !== JSON.stringify(sol.clusters)) {
    const t = new Set(truth.map((c) => c.join()));
    const s = new Set(sol.clusters.map((c) => c.join()));
    bad.push(`rules partition differs from the planted people: only-planted ${[...t].filter((x) => !s.has(x)).slice(0, 3).join(' | ')}; only-rules ${[...s].filter((x) => !t.has(x)).slice(0, 3).join(' | ')}`);
  }
  // Master uniqueness: no tie on activity date between two records of one system in a cluster.
  for (const c of sol.clusters) {
    const recs = c.map((id) => world.records.find((x) => x.id === id)).filter((x) => x.status !== 'gone away');
    if (!recs.length) bad.push(`every record gone away in ${c.join(',')}`);
    const top = recs.map((x) => x.activity).sort().pop();
    if (recs.filter((x) => x.activity === top).length > 1) bad.push(`activity tie in ${c.join(',')}`);
  }
  for (const d of ['shared-mailbox', 'same-name']) if (!world.distractors.includes(d)) bad.push(`missing ${d}`);
  if (world.spec.tier !== 'easy') {
    const same = (f) => JSON.stringify(flawedSolve(world, f)) === JSON.stringify(sol);
    if (same({ noGoneAway: true })) bad.push('gone-away rule changes nothing');
    if (same({ goldenFromMaster: true })) bad.push('survivorship rule changes nothing');
    if (world.distractors.includes('same-name-same-dob') && same({ noNotes: true })) bad.push('notes change nothing');
  }
  return bad;
}

export function summary(world, sol) {
  const sizes = {};
  for (const c of sol.clusters) sizes[c.length] = (sizes[c.length] ?? 0) + 1;
  return `${world.records.length} records, ${sol.clusters.length} duplicate groups ${JSON.stringify(sizes)}, distractors ${world.distractors.join(',')}`;
}

// ───────────────────────────── Rendering ─────────────────────────────
export function render(world) {
  const nick = NICK.map((g) => g.join(' = ')).join('; ');
  const rows = world.records.map((x) => [x.id, x.first, x.last, x.dob, x.email, x.phone, x.postcode, x.address, x.employer, x.activity, x.status, x.note].map((v) => (/[,"]/.test(v ?? '') ? `"${v}"` : v ?? '')).join(','));
  return `From: Hollie Barker (Membership Manager, Kirklees Fitness & Swim)
Subject: Customer list clean-up before the new CRM goes live

Hi, before we move to the new CRM we need to merge duplicate customers. The export below combines MemberHub (ids M-...) and the BookIt class-booking app (ids B-...): ${world.records.length} records. Please group the records that are the same customer using exactly the rules below, and tell me which record survives in each group.

MATCHING RULES
Name comparison: ignore capitals, spaces, hyphens, apostrophes and full stops. Surnames starting "Mac" and "Mc" are the same (MacLeod = McLeod). Surnames also match if, after the clean-up above, they differ by exactly one letter added, removed or changed and both are at least 5 letters long (a typo). First names match only if they are the same or are listed together in the nickname table. A record whose first-name and last-name fields are swapped counts as having the names in the right order.
Nickname table: ${nick}.
Phones: compare digits only, and treat a leading +44 as 0 (+44 7700 900123 = 07700 900123 = 07700900123). Emails: ignore capitals. Postcodes: ignore capitals and spaces. Address line: ignore capitals, spaces and punctuation.
Shared mailboxes: an email address whose part before the @ is one of ${SHARED.join(', ')} is a shared mailbox and never links records on its own.
Two records are the same customer if ANY of these holds:
  R1. Same email address (not a shared mailbox) and matching first names.
  R2. Same phone number and matching first names and surnames.
  R3. Same date of birth and matching first names and surnames.
  R4. Same phone number and same date of birth and matching first names (surnames may differ, e.g. after marriage).
  R5. Matching first names and surnames, the same postcode and the same address line.
Block: two records that both have a date of birth, with different dates, are never the same customer, whatever else matches.
Records linked through a chain of matching pairs are all one customer (if A matches B and B matches C, then A, B and C are one customer). A blank field never matches anything.
Notes: a record whose notes say it is "not the same person as" another record never matches that record directly, whatever the rules say (staff checked).
Surviving master record: in each group, the record with the most recent last_activity date, but never a record with status "gone away" (post returned) unless every record in the group is gone away; if two share that date, the MemberHub record; then the lower id.
Golden record (what the new CRM keeps for each group, filled from ALL records of the group): email = the most recent non-blank email in the group that is not a shared mailbox, in lower case; phone = the most recent non-blank phone in the group, as digits only starting 0 (e.g. 07700900123). "Most recent" means latest last_activity, with the same tie-breaks as for the master. A gone-away record still counts for these. If there is none, null.

EXPORT (CSV: id,first_name,last_name,dob,email,phone,postcode,address,employer,last_activity,status,notes)
${rows.join('\n')}

YOUR ANSWER
Reply with one JSON object:
{
  "clusters": [["M-10001", "B-20002"], ["..."]],
  "golden": { "M-10001": { "email": "name@example.com", "phone": "07700900123" } }
}
clusters: every group of two or more records that are the same customer (leave out records with no duplicate). golden: one entry per group, keyed by the group's surviving master record id. ${ONE_ANSWER} Groups are marked pair by pair (every pair of records correctly put together counts, every wrong pair costs); golden entries are marked as a set keyed by master id (an entry under a wrong id costs as much as a missing one), and each email and phone is checked.`;
}

void assert;
