// The customer-list ladder's merge layer (levels 3-10): merges decided one pair at a time, in a fixed order, where
// each decision depends on the groups the earlier decisions built.
//
// The business pack (crm.mjs) groups records by union-find over matching pairs, with a pairwise date-of-birth block.
// The ladder states a merge PROCEDURE instead: matching pairs are processed in order (rule R1 first ... R5, then by
// the lower id, then the higher id) and a merge is refused when it would put two records with different dates of
// birth, or a record and one it is noted "not the same person as", into one group. Planted "link records" (an app
// booking with one person's email and a namesake's phone, chains of them) match two different people, so which group
// a link record ends up in, and which merges are refused, depend on the order. Survivorship gains precedence rules
// with exceptions (a mobile beats a landline, the address comes from the most recent MemberHub record that is not
// "gone away", a corporate-scheme VIP record is the master), so the golden record of a group depends on every merge
// decision before it.
//
//   mergeGroups()  the engine: union-find with per-group date-of-birth and note sets
//   mergeCheck()   the independent check: replays the same ordered pairs with explicit Set groups and a from-scratch
//                  conflict test over every record pair of the two groups
import { rng as mkRng, D, addDays, iso } from '../realworld-business/lib.mjs';
import { firstMatch, surnameMatch, phoneNorm } from '../realworld-business/crm.mjs';

const SHARED = ['office', 'info', 'accounts', 'admin', 'reception', 'hello', 'enquiries', 'bookings', 'hr', 'team'];
const emailNorm = (s) => (s ? s.trim().toLowerCase() : '');
const pcNorm = (s) => (s ? s.toUpperCase().replace(/\s/g, '') : '');
const addrNorm = (s) => (s ? s.toLowerCase().replace(/[^a-z0-9]/g, '') : '');
const isShared = (e) => SHARED.includes(emailNorm(e).split('@')[0]);
const namesMatch = (a, b) => (firstMatch(a.first, b.first) && surnameMatch(a.last, b.last)) || (firstMatch(a.first, b.last) && surnameMatch(a.last, b.first));
const firstOf = (a, b) => firstMatch(a.first, b.first) || firstMatch(a.first, b.last) || firstMatch(a.last, b.first);

/** The rule (1-5) under which two records match, ignoring dates of birth and notes (the procedure handles those). */
export function matchRule(a, b) {
  const e = emailNorm(a.email);
  if (e && e === emailNorm(b.email) && !isShared(a.email) && firstOf(a, b)) return 1;
  const p = phoneNorm(a.phone);
  const samePhone = p && p === phoneNorm(b.phone);
  if (samePhone && namesMatch(a, b)) return 2;
  if (a.dob && a.dob === b.dob && namesMatch(a, b)) return 3;
  if (samePhone && a.dob && a.dob === b.dob && firstOf(a, b)) return 4;
  if (namesMatch(a, b) && pcNorm(a.postcode) && pcNorm(a.postcode) === pcNorm(b.postcode) && addrNorm(a.address) && addrNorm(a.address) === addrNorm(b.address)) return 5;
  return 0;
}

/** Every matching pair, in processing order: rule, then the lower id, then the higher id. */
export function orderedPairs(records) {
  const out = [];
  for (let i = 0; i < records.length; i++)
    for (let j = i + 1; j < records.length; j++) {
      const k = matchRule(records[i], records[j]);
      if (!k) continue;
      const [a, b] = records[i].id < records[j].id ? [records[i], records[j]] : [records[j], records[i]];
      out.push({ rule: k, a, b });
    }
  return out.sort((x, y) => x.rule - y.rule || x.a.id.localeCompare(y.a.id) || x.b.id.localeCompare(y.b.id));
}
const notedAgainst = (x, y) => !!(x.note && x.note.includes(`not the same person as ${y.id}`));

/** The engine. `flags.unordered` = plain union-find with only the pairwise block (the business pack's procedure). */
export function mergeGroups(records, flags = {}) {
  const parent = new Map(records.map((r) => [r.id, r.id]));
  const dobs = new Map(records.map((r) => [r.id, new Set(r.dob ? [r.dob] : [])]));
  const members = new Map(records.map((r) => [r.id, [r]]));
  const find = (id) => (parent.get(id) === id ? id : find(parent.get(id)));
  let refused = 0;
  for (const { a, b } of orderedPairs(records)) {
    const ra = find(a.id);
    const rb = find(b.id);
    if (ra === rb) continue;
    let clash;
    if (flags.unordered) clash = (a.dob && b.dob && a.dob !== b.dob) || notedAgainst(a, b) || notedAgainst(b, a);
    else {
      const da = dobs.get(ra);
      const db = dobs.get(rb);
      clash = (da.size && db.size && [...da].some((x) => !db.has(x))) || members.get(ra).some((x) => members.get(rb).some((y) => notedAgainst(x, y) || notedAgainst(y, x)));
    }
    if (clash) {
      refused++;
      continue;
    }
    parent.set(rb, ra);
    for (const d of dobs.get(rb)) dobs.get(ra).add(d);
    members.get(ra).push(...members.get(rb));
  }
  const groups = new Map();
  for (const r of records) {
    const k = find(r.id);
    if (!groups.has(k)) groups.set(k, []);
    groups.get(k).push(r);
  }
  return { groups: [...groups.values()], refused };
}

/** Independent replay: explicit Set groups, conflicts tested pair by pair from scratch. Returns the partition. */
export function mergeCheck(records) {
  const groupOf = new Map(records.map((r) => [r.id, new Set([r.id])]));
  const byId = new Map(records.map((r) => [r.id, r]));
  for (const { a, b } of orderedPairs(records)) {
    const ga = groupOf.get(a.id);
    const gb = groupOf.get(b.id);
    if (ga === gb) continue;
    let ok = true;
    for (const x of ga)
      for (const y of gb) {
        const rx = byId.get(x);
        const ry = byId.get(y);
        if ((rx.dob && ry.dob && rx.dob !== ry.dob) || notedAgainst(rx, ry) || notedAgainst(ry, rx)) ok = false;
      }
    if (!ok) continue;
    const merged = new Set([...ga, ...gb]);
    for (const id of merged) groupOf.set(id, merged);
  }
  return [...new Set(groupOf.values())].map((g) => [...g].sort()).filter((g) => g.length > 1).sort((x, y) => x[0].localeCompare(y[0]));
}

// ───────────── Survivorship ─────────────
const recency = (a, b) => b.activity.localeCompare(a.activity) || (a.id[0] === 'M' ? 0 : 1) - (b.id[0] === 'M' ? 0 : 1) || a.id.localeCompare(b.id);
const isMobile = (p) => phoneNorm(p).startsWith('07');

export function masterOf(group, lv) {
  const live = group.filter((x) => x.status !== 'gone away');
  const pool = (live.length ? live : group).slice().sort(recency);
  if (lv.vip) {
    const vip = pool.filter((x) => x.status !== 'gone away' && (x.note ?? '').includes('VIP - corporate scheme'));
    if (vip.length) return vip[0];
  }
  return pool[0];
}

export function goldenOf(group, lv) {
  const order = group.slice().sort(recency);
  const em = order.find((x) => x.email && !isShared(x.email));
  const out = { email: em ? emailNorm(em.email) : null };
  if (lv.survive) {
    const ph = order.find((x) => x.phone && isMobile(x.phone)) ?? order.find((x) => x.phone);
    out.phone = ph ? phoneNorm(ph.phone) : null;
    const withAddr = order.filter((x) => x.address && x.postcode && x.status !== 'gone away');
    const ad = withAddr.find((x) => x.id[0] === 'M') ?? withAddr[0];
    out.address = ad ? ad.address : null;
    out.postcode = ad ? ad.postcode : null;
  } else {
    const ph = order.find((x) => x.phone);
    out.phone = ph ? phoneNorm(ph.phone) : null;
  }
  return out;
}

/** Second derivation of the golden record (sorting each field's candidates by its own precedence key). */
export function goldenCheck(group, lv) {
  const key = (x) => [x.activity, x.id[0] === 'M' ? 1 : 0, -x.id.charCodeAt(2), x.id];
  const best = (xs) => xs.slice().sort((a, b) => (a.activity !== b.activity ? (a.activity < b.activity ? 1 : -1) : a.id[0] !== b.id[0] ? (a.id[0] === 'M' ? -1 : 1) : a.id < b.id ? -1 : 1))[0];
  void key;
  const em = best(group.filter((x) => x.email && !isShared(x.email)));
  const out = { email: em ? em.email.trim().toLowerCase() : null };
  if (lv.survive) {
    const mob = group.filter((x) => x.phone && phoneNorm(x.phone).startsWith('07'));
    const ph = best(mob.length ? mob : group.filter((x) => x.phone));
    out.phone = ph ? phoneNorm(ph.phone) : null;
    const live = group.filter((x) => x.address && x.postcode && x.status !== 'gone away');
    const mh = live.filter((x) => x.id.startsWith('M-'));
    const ad = best(mh.length ? mh : live);
    out.address = ad ? ad.address : null;
    out.postcode = ad ? ad.postcode : null;
  } else {
    const ph = best(group.filter((x) => x.phone));
    out.phone = ph ? phoneNorm(ph.phone) : null;
  }
  return out;
}

export function solveLadder(records, lv, flags = {}) {
  const { groups, refused } = mergeGroups(records, flags);
  const multi = groups.filter((g) => g.length > 1);
  const clusters = multi.map((g) => g.map((x) => x.id).sort()).sort((a, b) => a[0].localeCompare(b[0]));
  const glv = { ...lv, survive: lv.survive && !flags.oldSurvivorship, vip: lv.vip && !flags.noVip };
  const golden = Object.fromEntries(multi.map((g) => [masterOf(g, glv).id, goldenOf(g, glv)]).sort((a, b) => a[0].localeCompare(b[0])));
  return { sol: { clusters, golden }, refused };
}

// ───────────── Link records (planted) ─────────────
/**
 * Plant `n` conflicts: two different people with the same name and different dates of birth (both MemberHub, with
 * DOB), joined by link records that match one of them by one rule and the other by another rule. Shapes:
 *  - single: L has P's email (R1 with P) and Q's phone (R2 with Q)
 *  - chain:  L1 has P's email; L2 has L1's phone (R2 with L1) and Q's address (R5 with Q)
 *  - note:   as single, but the two people have the same DOB and a staff note on Q says "not the same person as" P
 *  - swap:   L has Q's email and P's phone (Q's side is processed first)
 *  - chainMid: L1 -R1- P, L2 -R2- Q, L1 -R5- L2 (the chain splits in the middle)
 *  - deep:   L1 -R1- P; L1 -R2- L2; L2 -R5- L3; L3 -R2- L4; L4 -R5- Q (where it splits depends on the record ids)
 */
export function plantLinks(world, cfg) {
  const r = mkRng(cfg.seed * 9973);
  const recs = world.records;
  const usedPhones = new Set(recs.map((x) => phoneNorm(x.phone)).filter(Boolean));
  const usedEmails = new Set(recs.map((x) => emailNorm(x.email)).filter(Boolean));
  const firsts = ['Jordan', 'Morgan', 'Casey', 'Riley', 'Avery', 'Rowan', 'Elliot', 'Jamie', 'Taylor', 'Sasha', 'Robin', 'Alex'];
  const lasts = ['Ashworth', 'Bramley', 'Crowther', 'Dyson', 'Firth', 'Garside', 'Hinchliffe', 'Kaye', 'Lumb', 'Mellor', 'Pickles', 'Sykes', 'Tolson', 'Utley', 'Wadsworth'];
  const streets = ['Wakefield Road', 'Bradford Road', 'Trinity Street', 'New Hey Road', 'Leeds Road', 'Lidget Street', 'Fenay Lane', 'Kirkgate'];
  const mobile = () => {
    for (;;) {
      const p = `07${r.int(100, 999)}${String(r.int(0, 999999)).padStart(6, '0')}`;
      if (!usedPhones.has(p)) return usedPhones.add(p), p;
    }
  };
  const email = (f, l) => {
    for (;;) {
      const e = `${f.toLowerCase()}.${l.toLowerCase()}${r.int(1, 999)}@${r.pick(['gmail.com', 'outlook.com', 'icloud.com', 'sky.com'])}`;
      if (!usedEmails.has(e)) return usedEmails.add(e), e;
    }
  };
  const addr = () => ({ address: `${r.int(1, 240)} ${r.pick(streets)}`, postcode: `HD${r.int(1, 9)} ${r.int(1, 9)}${String.fromCharCode(65 + r.int(0, 25))}${String.fromCharCode(65 + r.int(0, 25))}` });
  const day = () => iso(addDays(D(2026, 10, 31), -r.int(0, 900)));
  const dob = () => iso(D(r.int(1950, 2004), r.int(1, 12), r.int(1, 28)));
  const pid = (world.people?.length ?? 0) + 1000;
  const made = [];
  const usedNames = new Set(recs.map((x) => `${x.first}|${x.last}`));
  // 'join' links fuse two EXISTING customers that share a first name and have different dates of birth: a booking with
  // one's email and the other's phone. Union-find would merge the two whole groups; the procedure refuses.
  const existing = mergeGroups(recs).groups.filter((g) => g.length > 1 && g.some((x) => x.dob));
  const taken = new Set();
  const joins = [];
  for (const g1 of r.shuffle(existing)) {
    if (taken.has(g1)) continue;
    const p = g1.find((x) => x.email && !isShared(x.email) && x.dob);
    if (!p) continue;
    const g2 = existing.find((g) => g !== g1 && !taken.has(g) && g.some((q) => q.phone && q.dob && q.dob !== p.dob && firstMatch(q.first, p.first) && !g1.some((x) => x.dob === q.dob)));
    if (!g2) continue;
    const q = g2.find((y) => y.phone && y.dob && y.dob !== p.dob && firstMatch(y.first, p.first));
    taken.add(g1).add(g2);
    joins.push({ p, q });
  }
  for (let k = 0; k < cfg.links; k++) {
    if (cfg.shapes[k % cfg.shapes.length] === 'join' && joins.length) {
      const { p, q } = joins.shift();
      const x = { sys: 'B', first: q.first, last: q.last, dob: '', email: p.email.toUpperCase(), phone: q.phone, address: '', postcode: '', employer: '', activity: day(), status: '', note: '', pid: -1 };
      made.push({ shape: 'join', group: [x] });
      continue;
    }
    let f, l;
    do {
      f = r.pick(firsts);
      l = r.pick(lasts);
    } while (usedNames.has(`${f}|${l}`));
    usedNames.add(`${f}|${l}`);
    let shape = cfg.shapes[k % cfg.shapes.length];
    if (shape === 'join') shape = 'single';
    const pd = dob();
    let qd = dob();
    while (qd === pd) qd = dob();
    if (shape === 'note') qd = pd;
    const P = { sys: 'M', first: f, last: l, dob: pd, email: email(f, l), phone: mobile(), ...addr(), employer: '', activity: day(), status: '', note: '', pid: pid + 2 * k };
    const Q = { sys: 'M', first: f, last: l, dob: qd, email: email(f, l), phone: mobile(), ...addr(), employer: '', activity: day(), status: '', note: '', pid: pid + 2 * k + 1 };
    const link = (o) => ({ sys: 'B', first: f, last: l, dob: '', email: '', phone: '', address: '', postcode: '', employer: '', activity: day(), status: '', note: '', pid: -1, ...o });
    const group = [P, Q];
    const fmt44 = (ph) => `+44 ${ph.slice(1, 5)} ${ph.slice(5)}`;
    if (shape === 'single' || shape === 'note') group.push(link({ email: P.email.toUpperCase(), phone: fmt44(Q.phone) }));
    if (shape === 'swap') group.push(link({ email: Q.email, phone: fmt44(P.phone) }));
    if (shape === 'chain') {
      const s1 = mobile();
      group.push(link({ email: P.email, phone: s1 }), link({ phone: s1, address: Q.address, postcode: Q.postcode }));
    }
    if (shape === 'chainMid') {
      const a = addr();
      group.push(link({ email: P.email, ...a }), link({ ...a, phone: Q.phone }));
    }
    if (shape === 'deep') {
      const s1 = mobile();
      const s2 = mobile();
      const a = addr();
      group.push(link({ email: P.email, phone: s1 }), link({ phone: s1, ...a }), link({ ...a, phone: s2 }), link({ phone: s2, address: Q.address, postcode: Q.postcode }));
    }
    if (r.chance(0.3)) group[2 + r.int(0, group.length - 3)].status = 'gone away';
    made.push({ shape, group });
  }
  // Ids continue the export's numbering; the export stays sorted by id.
  let m = Math.max(...recs.filter((x) => x.id[0] === 'M').map((x) => Number(x.id.slice(2))));
  let b = Math.max(...recs.filter((x) => x.id[0] === 'B').map((x) => Number(x.id.slice(2))));
  const notes = [];
  for (const { shape, group } of made)
    for (const x of r.shuffle(group)) {
      x.id = x.sys === 'M' ? `M-${(m += r.int(1, 9))}` : `B-${(b += r.int(1, 9))}`;
      recs.push(x);
      if (shape === 'note' && x === group[1]) notes.push([x, group[0]]);
    }
  for (const [x, other] of notes) x.note = `checked by phone ${r.pick(['3 Sep', '17 Jun', '22 Oct', '9 Feb'])}: not the same person as ${other.id}`;
  // A few corporate-scheme VIP notes on records that are not masters by recency (the master exception).
  recs.sort((x, y) => x.id.localeCompare(y.id));
  return made.length;
}

// ───────────── Prompt text ─────────────
const OLD_CHAIN = 'Records linked through a chain of matching pairs are all one customer (if A matches B and B matches C, then A, B and C are one customer). A blank field never matches anything.';
const OLD_BLOCK = 'Block: two records that both have a date of birth, with different dates, are never the same customer, whatever else matches.\n';
const OLD_NOTES = 'Notes: a record whose notes say it is "not the same person as" another record never matches that record directly, whatever the rules say (staff checked).';
const OLD_GOLDEN_PHONE = 'phone = the most recent non-blank phone in the group, as digits only starting 0 (e.g. 07700900123).';

export const MERGE_TEXT = `MERGING PROCEDURE (this decides the groups): list every pair of records that match under R1-R5 (a blank field never matches anything) and process the pairs in this order: by rule number (all R1 pairs first, then R2, ... R5; a pair that matches several rules is listed under its lowest-numbered rule), then by the lower of the two record ids, then by the higher id (ids compare as text, e.g. B-20017 before B-20103 before M-10044). For each pair: if the two records are already in one group, skip it; otherwise merge their two groups into one - UNLESS the merged group would contain two records that both have a date of birth with different dates, or a record whose notes say it is "not the same person as" another record of the merged group (staff checked). Such a merge is refused and the pair is skipped; later pairs may still join either group to others. Groups are therefore built step by step: a record joined to one person early can stop a later merge with someone else.`;

export function rulesFor(prompt, lv) {
  let p = prompt;
  const rep = (a, b) => {
    if (!p.includes(a)) throw new Error(`crm prompt text not found: ${a.slice(0, 40)}`);
    p = p.replace(a, b);
  };
  if (lv.merge) {
    rep(OLD_BLOCK, '');
    rep(OLD_CHAIN, MERGE_TEXT);
    rep(OLD_NOTES + '\n', '');
  }
  if (lv.survive) {
    rep(OLD_GOLDEN_PHONE, 'phone = the most recent mobile (digits starting 07 after the +44 clean-up) in the group; only if the group has no mobile, the most recent landline; as digits only starting 0 (e.g. 07700900123). address and postcode = the address line and postcode (as written) of the most recent MemberHub record of the group that has both and is not "gone away"; only if there is none, of the most recent such BookIt record; otherwise null (an address is the one field a gone-away record never supplies).');
    rep('"golden": { "M-10001": { "email": "name@example.com", "phone": "07700900123" } }', '"golden": { "M-10001": { "email": "name@example.com", "phone": "07700900123", "address": "12 Leeds Road", "postcode": "HD1 4AB" } }');
  }
  if (lv.vip) rep('Surviving master record: in each group, the record with the most recent', 'Surviving master record: in each group, a record whose notes say "VIP - corporate scheme" and that is not "gone away" (if several, the most recent of them, with the tie-breaks below); otherwise the record with the most recent');
  return p;
}
