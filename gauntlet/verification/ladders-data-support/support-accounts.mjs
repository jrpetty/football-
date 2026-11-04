// The support ladder's account layer (levels 3-10): state that carries across a customer's tickets.
//
// The business pack's engine (support.mjs) decides every ticket on its own facts. On top of it the ladder adds the
// handbook's section 4.15 "Account limits", applied to each customer's tickets in the order they were received,
// counting the account history AND the final outcome of the customer's earlier tickets in this queue (an outcome
// changed by 4.15 counts as changed). So a ticket's decision, refund, priority and SLA can depend on a chain of
// earlier decisions: a declined return does not use the change-of-mind allowance, an escalated one does not either
// (it is not a refund), a goodwill payment cut to the remaining cap leaves nothing for the next one, an escalation
// raises the priority (and so the SLA) of every later ticket from that customer.
//
//   applyAccounts()  the engine: one pass in received order with a running state per customer
//   checkAccounts()  the independent check: for every ticket, recount its customer's events from scratch over the
//                    whole history (windows, calendar years, membership dates) and confirm the outcome
import { rng as mkRng, D, addDays, diffDays, iso, dMonY, gbp, assert } from '../realworld-business/lib.mjs';
import { utcToUk, slaDue } from '../realworld-business/support.mjs';

export const RULE_TEXT = {
  a: `(a) Change-of-mind allowance. A change-of-mind refund (4.2) is allowed only if the customer has had fewer than 2 change-of-mind refunds in the 90 days up to and including the ticket date (the 90 days before it and the ticket date itself), counting the account history and this customer's earlier tickets in this queue. Otherwise the ticket is escalated to the Returns team: decision escalate, refund 0.`,
  b: `(b) Goodwill cap. The £5.00 partial refund for a late express order (4.6) is goodwill. A customer gets at most £10.00 of goodwill in a calendar year (by UK date), counting the account history and earlier tickets in this queue. If less than £5.00 of the year's cap is left, pay what is left (decision partial, that amount); if nothing is left, decline.`,
  c: `(c) Repeat faults. A faulty-goods ticket (4.3) whose decision would be refund, replace or repair is escalated to the Quality team instead (decision escalate, refund 0) if the customer already had 2 or more faulty-goods outcomes (refund, replace or repair) in the 180 days up to and including the ticket date. Safety issues are escalated under 4.3 anyway and keep P1.`,
  d: `(d) Escalation memory. A ticket escalated under (a) or (c) is at least P2. Any later ticket in this queue from a customer whose earlier ticket in this queue was escalated (for any reason) is also at least P2. A higher priority shortens the SLA (4.12) as usual.`,
  e: `(e) Fernbrook Plus members (shown in the account list with the date their membership runs to): (a) does not apply to them and their goodwill cap under (b) is £20.00 a year; (c) and (d) apply as to everyone. A membership counts only on dates up to and including the date it runs to; after that the customer is treated as a normal customer, also for the rest of that year's cap.`,
};

/** Group tickets into customers and write account histories. Mutates the world; returns the account list. */
export function addAccounts(world, cfg) {
  const r = mkRng(cfg.seed * 7717);
  const tickets = world.tickets;
  const order = (t) => world.orders.find((o) => o.no === t.order);
  const ukDay = (t) => {
    const u = utcToUk(t.utc);
    return D(u.y, u.mo, u.d);
  };
  // Customers: shuffle the tickets and cut them into groups of 1..max; tickets of one group become one person.
  // Serial returners and repeat complainers: a group is filled with tickets of the same issue more often than chance.
  const pool = r.shuffle(tickets.filter((t) => !t.kind.startsWith('welsh')));
  const accounts = [];
  while (pool.length) {
    const n = r.chance(cfg.groupShare) ? r.int(2, cfg.maxPer) : 1;
    const group = [pool.shift()];
    while (group.length < n && pool.length) {
      const same = pool.findIndex((x) => x.issue === group[0].issue);
      group.push(pool.splice(same >= 0 && r.chance(0.6) ? same : 0, 1)[0]);
    }
    if (group.length < 2 && !r.chance(cfg.historyShare)) continue;
    const owner = group[0].customer;
    for (const t of group) {
      t.customer = owner;
      order(t).email = owner.email;
    }
    const first = group.map(ukDay).sort((a, b) => a - b)[0];
    const hist = [];
    const back = (lo, hi) => addDays(first, -r.int(lo, hi));
    if (cfg.rules.includes('a')) for (let k = r.int(0, 3); k > 0; k--) hist.push({ date: back(5, 100), type: r.chance(0.8) ? 'com' : 'com-declined' });
    if (cfg.rules.includes('c')) for (let k = r.int(0, 3); k > 0; k--) hist.push({ date: back(10, 200), type: 'fault', how: r.pick(['refunded', 'replaced', 'repaired']) });
    if (cfg.rules.includes('b')) for (let k = r.int(0, 3); k > 0; k--) hist.push({ date: back(3, 300), type: 'goodwill', amount: r.pick([500, 500, 250, 300]) });
    hist.sort((a, b) => a.date - b.date);
    const acct = { email: owner.email, name: `${owner.first} ${owner.last}`, history: hist, plus: null };
    if (cfg.rules.includes('e') && r.chance(0.35)) acct.plus = cfg.rules.includes('x') && r.chance(0.5) ? addDays(first, r.int(-20, 2)) : addDays(first, r.int(20, 300));
    accounts.push(acct);
  }
  world.accounts = accounts;
  return accounts;
}

const PRI = { P1: 1, P2: 2, P3: 3 };
const atLeastP2 = (p) => (p === 'P3' ? 'P2' : p);
const HOURS = { P1: 4, P2: 8, P3: 16 };

/**
 * The engine: tickets in received order, a running state per customer. `flags` switch single rules off for the
 * scripted slips: countDeclined (a declined return uses the allowance too), noMemory (rule d ignored),
 * plusForever (an expired membership still counts), lastYear (last year's goodwill counts against this year's cap).
 */
export function applyAccounts(world, base, cfg, flags = {}) {
  const tickets = {};
  const state = new Map(); // email -> { events: [{date, type, amount}], escalated: bool }
  const acct = new Map((world.accounts ?? []).map((a) => [a.email, a]));
  for (const a of world.accounts ?? []) state.set(a.email, { events: a.history.filter((h) => flags.countDeclined || h.type !== 'com-declined').map((h) => ({ ...h, type: h.type === 'com-declined' ? 'com' : h.type })), escalated: false });
  const on = (k) => cfg.rules.includes(k);
  for (const t of world.tickets) {
    const b = base.tickets[t.id];
    const res = { ...b };
    const email = t.customer.email;
    const st = state.get(email) ?? { events: [], escalated: false };
    state.set(email, st);
    const u = utcToUk(t.utc);
    const day = D(u.y, u.mo, u.d);
    const a = acct.get(email);
    const plus = on('e') && a?.plus && (flags.plusForever || day <= a.plus);
    const within = (type, days) => st.events.filter((e) => e.type === type && diffDays(day, e.date) >= 0 && diffDays(day, e.date) <= days).length;
    let raised = false;
    if (on('a') && b.category === 'change-of-mind' && b.decision === 'refund' && !plus && within('com', 90) >= 2) {
      res.decision = 'escalate';
      res.refund = 0;
      res.why = 'a';
      raised = true;
    }
    if (on('c') && b.category === 'faulty' && ['refund', 'replace', 'repair'].includes(b.decision) && within('fault', 180) >= 2) {
      res.decision = 'escalate';
      res.refund = 0;
      res.why = 'c';
      raised = true;
    }
    if (on('b') && b.decision === 'partial' && b.category === 'late-delivery') {
      const cap = plus ? 2000 : 1000;
      const used = st.events.filter((e) => e.type === 'goodwill' && (flags.lastYear || e.date.getUTCFullYear() === day.getUTCFullYear()) && e.date <= day).reduce((x, e) => x + e.amount, 0);
      const left = cap - used;
      if (left <= 0) {
        res.decision = 'decline';
        res.refund = 0;
      } else if (left < 500) res.refund = left / 100;
      res.why ??= res.refund !== b.refund || res.decision !== b.decision ? 'b' : undefined;
    }
    if (on('d') && (raised || (st.escalated && !flags.noMemory))) res.priority = atLeastP2(res.priority);
    if (res.priority !== b.priority) res.sla_due = slaDue(t.reopened ? t.reopened.utc : t.utc, HOURS[res.priority]);
    // Record the final outcome.
    if (res.category === 'change-of-mind' && res.decision === 'refund') st.events.push({ date: day, type: 'com' });
    if (flags.countDeclined && res.category === 'change-of-mind' && res.decision === 'decline') st.events.push({ date: day, type: 'com' });
    if (res.category === 'faulty' && ['refund', 'replace', 'repair'].includes(res.decision)) st.events.push({ date: day, type: 'fault' });
    if (res.decision === 'partial' && res.category === 'late-delivery') st.events.push({ date: day, type: 'goodwill', amount: Math.round(res.refund * 100) });
    if (res.decision === 'escalate') st.escalated = true;
    tickets[t.id] = res;
  }
  const accounts = {};
  const [ny, nm, nd] = world.spec.now;
  const today = D(ny, nm, nd);
  for (const a of world.accounts ?? []) {
    const mine = world.tickets.filter((t) => t.customer.email === a.email);
    if (!mine.length) continue;
    const entry = { refunded: Math.round(mine.reduce((x, t) => x + tickets[t.id].refund * 100, 0)) / 100 };
    if (on('b')) {
      const plus = on('e') && a.plus && (flags.plusForever || today <= a.plus);
      const used = state.get(a.email).events.filter((e) => e.type === 'goodwill' && (flags.lastYear || e.date.getUTCFullYear() === today.getUTCFullYear())).reduce((x, e) => x + e.amount, 0);
      entry.goodwill_left = Math.max(0, (plus ? 2000 : 1000) - used) / 100;
    }
    accounts[a.email] = entry;
  }
  for (const v of Object.values(tickets)) delete v.why;
  return { tickets, accounts };
}

/** Independent check of the engine's result: for every ticket, recount from scratch over the account history and the
 *  result's own earlier tickets (no running state), and confirm decision, refund and priority. */
export function checkAccounts(world, base, cfg, sol) {
  const bad = [];
  const on = (k) => cfg.rules.includes(k);
  const dayOf = (t) => {
    const u = utcToUk(t.utc);
    return iso(D(u.y, u.mo, u.d));
  };
  const minus = (d, n) => iso(addDays(D(+d.slice(0, 4), +d.slice(5, 7), +d.slice(8, 10)), -n));
  for (const t of world.tickets) {
    const a = (world.accounts ?? []).find((x) => x.email === t.customer.email);
    const d = dayOf(t);
    const earlier = world.tickets.filter((x) => x.customer.email === t.customer.email && x.utc < t.utc);
    const hist = (type) => (a?.history ?? []).filter((h) => h.type === type).map((h) => ({ d: iso(h.date), amount: h.amount }));
    const fromQueue = (pred) => earlier.filter((x) => pred(sol.tickets[x.id], x)).map((x) => ({ d: dayOf(x), amount: Math.round(sol.tickets[x.id].refund * 100) }));
    const plus = on('e') && a?.plus && d <= iso(a.plus);
    const b = base.tickets[t.id];
    let want = { decision: b.decision, refund: b.refund, priority: b.priority };
    const com = [...hist('com'), ...fromQueue((s) => s.category === 'change-of-mind' && s.decision === 'refund')].filter((e) => e.d >= minus(d, 90) && e.d <= d).length;
    const faults = [...hist('fault'), ...fromQueue((s) => s.category === 'faulty' && ['refund', 'replace', 'repair'].includes(s.decision))].filter((e) => e.d >= minus(d, 180) && e.d <= d).length;
    let raised = false;
    if (on('a') && b.category === 'change-of-mind' && b.decision === 'refund' && !plus && com >= 2) (want = { ...want, decision: 'escalate', refund: 0 }), (raised = true);
    if (on('c') && b.category === 'faulty' && ['refund', 'replace', 'repair'].includes(b.decision) && faults >= 2) (want = { ...want, decision: 'escalate', refund: 0 }), (raised = true);
    if (on('b') && b.decision === 'partial' && b.category === 'late-delivery') {
      const used = [...hist('goodwill'), ...fromQueue((s) => s.decision === 'partial' && s.category === 'late-delivery')].filter((e) => e.d.slice(0, 4) === d.slice(0, 4) && e.d <= d).reduce((x, e) => x + e.amount, 0);
      const left = (plus ? 2000 : 1000) - used;
      want = left <= 0 ? { ...want, decision: 'decline', refund: 0 } : left < 500 ? { ...want, refund: left / 100 } : want;
    }
    const memory = earlier.some((x) => sol.tickets[x.id].decision === 'escalate');
    if (on('d') && (raised || memory) && want.priority === 'P3') want.priority = 'P2';
    const got = sol.tickets[t.id];
    for (const k of ['decision', 'refund', 'priority']) if (got[k] !== want[k]) bad.push(`${t.id} ${k}: engine ${got[k]}, recount ${want[k]}`);
  }
  return bad;
}

/** Ticket ids whose outcome the account rules changed (they carry trap weights). */
export function changedByAccounts(base, sol) {
  return Object.keys(sol.tickets).filter((id) => JSON.stringify(base.tickets[id]) !== JSON.stringify(sol.tickets[id]));
}

export function rulesText(cfg) {
  const ks = ['a', 'b', 'c', 'd', 'e'].filter((k) => cfg.rules.includes(k));
  return `4.15 Account limits. These are applied after 4.1 to 4.14, to each customer's tickets in the order they were received (oldest first). They count the account history below AND the final outcome of the same customer's earlier tickets in this queue, including outcomes that these limits changed (an escalated or declined ticket is not a refund). A customer is identified by their email address.
${ks.map((k) => RULE_TEXT[k]).join('\n')}`;
}

export function renderAccounts(world) {
  const desc = (h) =>
    h.type === 'com' ? 'change-of-mind return, refunded' : h.type === 'com-declined' ? 'change-of-mind return requested, declined (outside 14 days)' : h.type === 'fault' ? `faulty item ${h.how}` : `late-delivery goodwill ${gbp(h.amount)}`;
  const lines = (world.accounts ?? []).map((a) => {
    const plus = a.plus ? ` - Fernbrook Plus member until ${dMonY(a.plus)}` : '';
    const hist = a.history.length ? a.history.map((h) => `${dMonY(h.date)} ${desc(h)}`).join('; ') : 'no earlier tickets';
    return `  ${a.email} (${a.name})${plus}\n    History: ${hist}`;
  });
  return `ACCOUNTS (account notes and the history of earlier tickets, for customers with an account note or more than one ticket in this queue)\n${lines.join('\n')}`;
}

export function answerText(cfg) {
  return `Also give, for every customer listed under ACCOUNTS, an entry under "accounts" keyed by email: "refunded" = the total refund (pounds) of that customer's tickets in this queue${cfg.rules.includes('b') ? ', and "goodwill_left" = the goodwill cap still unused for the calendar year of today\'s date, after this queue (pounds)' : ''}. Example: "accounts": { "name@example.com": { "refunded": 12.50${cfg.rules.includes('b') ? ', "goodwill_left": 5.00' : ''} } }. These totals are marked like the ticket fields.`;
}

void assert;
