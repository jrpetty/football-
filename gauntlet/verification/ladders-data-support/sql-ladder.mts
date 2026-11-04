// "Ask the Database: Ladder" (work.sql-ladder): ten frozen questions on Copperline Coffee's warehouse (the schema,
// rules and generator of work.sql-hard, verification/realworld-sql/warehouse.mts), each one query deeper than the
// one before. Growth is query depth, not table size: the number of subtle semantics one query has to compose
// correctly, and from level 6 state that carries from row to row (a calendar, as-of chain resolution, sessions, a
// budget run where every pick changes the later ones).
//
//   L01  paid orders and customers per channel (1 rule)
//   L02  deduplicated app events in a UK-local week (2)
//   L03  top two per category with ties across a category move (= sql-hard h04) (4)
//   L04  lowest and end-of-UK-day wallet balances through reversal chains (= h07) (5)
//   L05  app sessions after deduplication with the earliest ingestion and fast-clock correction (= h09) (6)
//   L06  balances as known each April evening vs restated with every later reversal: a calendar with carried
//        balances and rule 6 evaluated as of each date (7)
//   L07  sessions of L05 joined to the paid app orders that fall in [first event, last event + 30 min], at
//        historical prices, by the UK date the session started (8)
//   L08  promotion budgets used up line by line in time order: each line takes the best promotion that still fits
//        and falls back otherwise (a recursive CTE carrying seven budgets) (10)
//   L09  + one use per customer per promotion (later orders blocked, the same order not), gift lines by the category
//        at the order moment, a memo override (13)
//   L10  + partial last use of a budget unless under £0.50 (then skipped but kept for smaller lines), the 23:00 UK
//        quarter cut-off (16)
//
// The scorer is the `sql` scorer with hidden variant databases, exactly as for the other SQL tests: buildPack()
// (verification/realworld-sql/build.mts) runs every check on every level: the reference query equals an independent
// JavaScript solver on all four databases, the result does not depend on scan order, differs between every pair of
// databases, shares at most a fifth of its rows with the live answer on any hidden copy (mean at most a tenth), and
// every careless query (one realistic slip) is wrong on at least one database.
//
//   node verification/ladders-data-support/sql-ladder.mts           write tests/work/sql-ladder.json
//   node verification/ladders-data-support/sql-ladder.mts --check   fail if the committed file differs
import { readFileSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { buildPack, standardPreamble, type Pack } from '../realworld-sql/build.mts';
import { addDays, createRng, names, serialiseTest, tok, type Cell, type Question, type Tables } from '../realworld-sql/lib.mts';

const addDays1 = (d: string) => addDays(d, 1);
import * as wh from '../realworld-sql/warehouse.mts';

const HERE = dirname(fileURLToPath(import.meta.url));
export const LADDER_FILE = join(HERE, '..', '..', 'tests', 'work', 'sql-ladder.json');
export const LADDER_SEEDS = [20261104, 61403, 77219, 88651];

// ─────────────────────────────── Data: the warehouse, plus a few planted rows that make every new rule live ───────────────────────────────

/** generateWarehouse(seed) plus planted rows (only in this ladder's databases), so that every rule a level adds
 *  changes the answer for several customers: orders either side of the 23:00 quarter cut-offs, big baskets on
 *  3 May (MAYDAY25 withdrawn), orders of products that later move in or out of Gifts inside a promotion window,
 *  wallet purchases whose chargeback was undone and purchases either side of the cut-offs, subscriber orders on the
 *  lost day 14 April and at 23:xx UTC before a status change, big spenders with a cancelled order, fast-clock
 *  checkouts either side of the cut-offs, and pairs of new customers with identical baskets (ties in a rank). */
export function generateLadderWarehouse(seed: number): { tables: Tables } {
  const { tables } = wh.generateWarehouse(seed);
  const r = createRng(seed).fork('ladder-plants');
  const cust = tables.dim_customer!.rows;
  const orders = tables.fact_orders!.rows;
  const lines = tables.fact_order_lines!.rows;
  const local = cust.filter((c) => c[2] === 'GB' || c[2] === 'IE').map((c) => Number(c[0]));
  const q2Buyers = [...new Set(orders.filter((o) => o[4] === 'paid' && String(o[2]) >= '2026-04-02' && String(o[2]) < '2026-06-29').map((o) => Number(o[1])))].filter((c) => local.includes(c));
  let oid = Math.max(...orders.map((o) => Number(o[0]))) + 1;
  const add = (c: number, at: string, status: string, ls: Array<[number, number]>, channel = r.chance(0.5) ? 'app' : 'web') => {
    orders.push([oid, c, at, channel, status]);
    ls.forEach(([p, q], k) => lines.push([oid, k + 1, p, q]));
    return oid++;
  };
  const mm = () => String(r.int(5, 55)).padStart(2, '0');
  const hh = (lo: number, hi: number) => String(r.int(lo, hi)).padStart(2, '0');
  const beans = () => r.int(1, 6);
  // 1. The 23:00 UK cut-offs (BST: 22:xx UTC is 23:xx UK time): four orders just inside Q2 at its start, four just
  //    outside it at its end, and one control either side.
  for (let k = 0; k < 4; k++) add(r.pick(q2Buyers), `2026-03-31 22:${mm()}:${hh(0, 59)}`, 'paid', [[beans(), r.int(1, 2)], [r.int(7, 10), 1]]);
  for (let k = 0; k < 4; k++) add(r.pick(q2Buyers), `2026-06-30 22:${mm()}:${hh(0, 59)}`, 'paid', [[beans(), r.int(1, 2)], [r.pick([11, 12, 13]), 1]]);
  add(r.pick(q2Buyers), `2026-03-31 21:${mm()}:40`, 'paid', [[beans(), 1]]);
  add(r.pick(q2Buyers), `2026-06-30 21:${mm()}:50`, 'paid', [[r.int(7, 10), 1], [12, 1]]);
  // 2. MAYDAY25 withdrawn on 3 May (memo item 1): big baskets that day fall back to KIT20 or to no promotion.
  for (let k = 0; k < 5; k++) add(r.pick(q2Buyers), `2026-05-03 ${hh(8, 20)}:${mm()}:05`, 'paid', [[8, 1], [r.pick([7, 9]), 1], [beans(), 2]], r.pick(['web', 'app']));
  // 3. Products that move category during the quarter, ordered inside a promotion window before the move: the
  //    category at the order moment decides the gift rule, not today's category.
  const promos = tables.promotions!.rows.map((p) => ({ id: Number(p[0]), code: String(p[1]), ch: p[3] === null ? null : String(p[3]), s: String(p[5]), e: String(p[1]) === 'MAYDAY25' ? '2026-05-02' : String(p[6]) }));
  const covers = new Set(tables.promotion_products!.rows.map((x) => `${x[0]}|${x[1]}`));
  for (const m of tables.dim_product_category_history!.rows.filter((x) => x[3] !== null)) {
    const p = Number(m[0]);
    const moveAt = String(m[3]);
    const days: string[] = [];
    for (const pr of promos) {
      if (!covers.has(`${pr.id}|${p}`)) continue;
      for (let d = pr.s; d <= pr.e && d < '2026-07-01'; d = addDays1(d)) if (d >= '2026-04-01' && `${d} 12:00:00` < moveAt) days.push(`${d}|${pr.ch ?? 'web'}`);
    }
    for (const dc of r.shuffle([...new Set(days)]).slice(0, 2)) {
      const [d, ch] = dc.split('|') as [string, string];
      add(r.pick(q2Buyers), `${d} ${hh(9, 11)}:${mm()}:00`, 'paid', [[p, r.int(1, 2)], [8, 1]], ch);
    }
  }
  // 4. Subscriber orders: on the lost day (rule 7) for every UK subscriber whose 13 and 15 April rows exist, and at
  //    23:xx UTC on a day before their status changes (the order's UK date is the next day).
  const subs = tables.subscription_status_daily!.rows;
  const status = new Map(subs.map((s) => [`${s[0]}|${s[1]}`, String(s[2])]));
  const subscribers = [...new Set(subs.map((s) => Number(s[0])))].filter((c) => local.includes(c));
  for (const c of subscribers) if (status.has(`${c}|2026-04-13`) && status.has(`${c}|2026-04-15`)) add(c, `2026-04-14 ${hh(9, 15)}:${mm()}:00`, 'paid', [[beans(), 1]]);
  let edges = 0;
  for (const c of r.shuffle(subscribers)) {
    if (edges >= 4) break;
    for (let d = '2026-04-20'; d < '2026-06-28'; d = addDays1(d)) {
      const a = status.get(`${c}|${d}`);
      const b = status.get(`${c}|${addDays1(d)}`);
      if (a !== undefined && (a === 'active') !== (b === 'active')) {
        add(c, `${d} 23:${mm()}:30`, 'paid', [[beans(), 1]]);
        edges++;
        break;
      }
    }
  }
  // 5. Big spenders with a cancelled order in Q2 (memo item 5), and two without.
  const big = r.shuffle(q2Buyers).slice(0, 6);
  for (const c of big) add(c, `2026-06-${hh(2, 12)} ${hh(9, 18)}:${mm()}:00`, 'paid', [[8, 2], [9, 1]], 'web');
  for (const c of big.slice(0, 4)) add(c, `2026-05-${hh(4, 28)} ${hh(9, 18)}:${mm()}:00`, 'cancelled', [[beans(), 1]]);
  // 6. Pairs of new customers with identical single baskets on the same day (ties in a rank).
  let cid = Math.max(...cust.map((c) => Number(c[0]))) + 1;
  const newNames = names(r, 6);
  for (let k = 0; k < 3; k++) {
    const day = `2026-04-${hh(15, 27)}`;
    const basket: Array<[number, number]> = [[r.pick([7, 9]), 1], [beans(), r.int(1, 2)]];
    for (let j = 0; j < 2; j++) {
      cust.push([cid, newNames[2 * k + j]!, 'GB', `2026-02-${hh(2, 26)} ${hh(8, 20)}:${mm()}:00`]);
      add(cid++, `${day} ${hh(9, 19)}:${mm()}:00`, 'paid', basket, 'web');
    }
  }
  orders.sort((a, b) => Number(a[0]) - Number(b[0]));
  lines.sort((a, b) => Number(a[0]) - Number(b[0]) || Number(a[1]) - Number(b[1]));
  // 7. Wallet: chargebacks that were undone (the purchase counts again), and purchases either side of the cut-offs.
  const ledger = tables.wallet_ledger!.rows;
  let eid = Math.max(...ledger.map((x) => Number(x[0]))) + 1;
  for (const c of r.shuffle(q2Buyers).slice(0, 5)) {
    const d = `2026-05-${hh(4, 20)}`;
    const amt = -r.pick([950, 1125, 1800]);
    const p = eid++;
    ledger.push([p, c, `${d} 10:${mm()}:00`, amt, 'purchase', null]);
    const rv = eid++;
    ledger.push([rv, c, `${addDays1(d)} 11:${mm()}:00`, -amt, 'reversal', p]);
    ledger.push([eid++, c, `${addDays(d, 2)} 12:${mm()}:00`, amt, 'reversal', rv]);
  }
  for (const [day, h] of [['2026-03-31', '22'], ['2026-03-31', '22'], ['2026-03-31', '21'], ['2026-06-30', '22'], ['2026-06-30', '22'], ['2026-06-30', '21']] as const)
    ledger.push([eid++, r.pick(q2Buyers), `${day} ${h}:${mm()}:00`, -r.pick([420, 875, 1125, 2700]), 'purchase', null]);
  ledger.sort((a, b) => Number(a[0]) - Number(b[0]));
  // 8. Fast-clock checkouts either side of the cut-offs: the stamp says one quarter, the corrected time the other.
  const events = tables.events_raw!.rows;
  let evid = Math.max(...events.map((x) => Number(x[0]))) + 1;
  for (const [real, fast] of [['2026-03-31 21:4', '2026-03-31 22:2'], ['2026-03-31 21:3', '2026-03-31 22:1'], ['2026-06-30 21:4', '2026-06-30 22:2'], ['2026-06-30 21:3', '2026-06-30 22:1']] as const) {
    const c = r.pick(q2Buyers);
    const s = r.int(0, 9);
    events.push([evid++, c, 'app_open', `${real}${s}:00`, `${real}${s}:05`]);
    events.push([evid++, c, 'checkout', `${fast}${s}:30`, `${real}${s}:40`]);
  }
  events.sort((a, b) => Number(a[0]) - Number(b[0]));
  // 9. April chargebacks for the restated balances (L06): a purchase that takes a wallet below zero for the evening,
  //    reversed the next day, and in some wallets the reversal undone (and once undone again) a few days later; plus a
  //    late-evening purchase in BST (23:xx UTC is the next UK date).
  {
    const r9 = createRng(seed).fork('ladder-plants-wallet');
    const two = (n: number) => String(n).padStart(2, '0');
    const holders9 = [...new Set(ledger.filter((x) => String(x[2]) < '2026-03-30').map((x) => Number(x[1])))];
    r9.shuffle(holders9).slice(0, 7).forEach((c, k) => {
      const d = `2026-04-${two(r9.int(3, 20))}`;
      const bal = ledger.filter((x) => Number(x[1]) === c && String(x[2]) < d && x[4] !== 'reversal').reduce((a, x) => a + Number(x[3]), 0);
      const amt = -(Math.max(0, bal) + r9.pick([300, 800, 1500]));
      const p = eid++;
      ledger.push([p, c, `${d} 17:${two(r9.int(0, 59))}:00`, amt, 'purchase', null]);
      const rv1 = eid++;
      ledger.push([rv1, c, `${addDays(d, k % 3 === 2 ? 0 : 1)} 2${r9.int(0, 1)}:${two(r9.int(0, 59))}:00`, -amt, 'reversal', p]);
      if (k % 2 === 0) {
        const rv2 = eid++;
        ledger.push([rv2, c, `${addDays(d, r9.int(2, 4))} 09:${two(r9.int(0, 59))}:00`, amt, 'reversal', rv1]);
        if (k === 0) ledger.push([eid++, c, `${addDays(d, 6)} 10:${two(r9.int(0, 59))}:00`, -amt, 'reversal', rv2]);
      }
      ledger.push([eid++, c, `${addDays(d, r9.int(1, 6))} 23:${two(r9.int(5, 55))}:00`, -r9.pick([420, 875, 1125]), 'purchase', null]);
    });
    const fresh = r9.shuffle(local.filter((c) => !ledger.some((x) => Number(x[1]) === c))).slice(0, 2);
    for (const c of fresh) {
      ledger.push([eid++, c, `2026-04-${two(r9.int(6, 10))} 10:${two(r9.int(0, 59))}:00`, 1000, 'topup', null]);
      ledger.push([eid++, c, `2026-04-${two(r9.int(12, 16))} 15:${two(r9.int(0, 59))}:00`, -r9.pick([1500, 1800, 2700]), 'purchase', null]);
    }
    ledger.sort((a, b) => Number(a[0]) - Number(b[0]));
  }
  // 10. Paid orders around the app sessions of 6-12 April (L07): a few minutes after the last event, exactly 30:00
  //     and 30:01 after it, web orders inside a session window, and app orders just after a fast-clock event (only
  //     the corrected time puts them inside the session).
  {
    const r10 = createRng(seed).fork('ladder-plants-sessions');
    const iso = (s: number) => new Date(s * 1000).toISOString().slice(0, 19).replace('T', ' ');
    const week = sessionsOf(tables).filter((s) => wh.ukLocal(s.first) >= '2026-04-06' && wh.ukLocal(s.first) < '2026-04-13');
    r10.shuffle(week).slice(0, 16).forEach((s, k) => {
      const off = k % 8 === 0 ? 1800 : k % 8 === 1 ? 1801 : r10.int(30, 1700);
      add(s.cust, iso(s.t1 + off), 'paid', [[r10.int(1, 13), r10.int(1, 2)]], k % 8 === 2 ? 'web' : 'app');
    });
    const fast = events.filter((e) => String(e[3]) > String(e[4]) && wh.ukLocal(String(e[4])) >= '2026-04-06' && wh.ukLocal(String(e[4])) < '2026-04-12');
    for (const e of r10.shuffle(fast).slice(0, 4)) add(Number(e[1]), iso(secs(String(e[4])) + 1700 + r10.int(0, 90)), 'paid', [[r10.int(1, 6), 1]], 'app');
    orders.sort((a, b) => Number(a[0]) - Number(b[0]));
    lines.sort((a, b) => Number(a[0]) - Number(b[0]) || Number(a[1]) - Number(b[1]));
  }
  // 11. Ties in Q2 units (L03, sql-hard h04): in two categories the third product is topped up to tie with the
  //     second, in one the second to tie with the first (the extra order is placed a minute after one of that
  //     product's own orders in that category, so it is counted in the same category).
  {
    const r11 = createRng(seed).fork('ladder-plants-ties');
    const iso = (s: number) => new Date(s * 1000).toISOString().slice(0, 19).replace('T', ' ');
    const q2 = paidLines(tables).filter((l) => l.local >= '2026-04-01' && l.local < '2026-07-01');
    const units = new Map<string, { n: number; at: string }>();
    for (const l of q2) {
      const k = `${l.category}|${l.product}`;
      const cur = units.get(k);
      units.set(k, { n: (cur?.n ?? 0) + l.qty, at: cur && cur.at < l.utc ? cur.at : l.utc });
    }
    const cats = r11.shuffle(['Beans', 'Brewing kit', 'Gifts']);
    cats.forEach((cat, i) => {
      const ranked = [...units.entries()].filter(([k]) => k.startsWith(`${cat}|`)).sort((a, b) => b[1].n - a[1].n || (a[0] < b[0] ? -1 : 1));
      const [hi, lo] = i === 0 ? [ranked[0], ranked[1]] : [ranked[1], ranked[2]];
      if (!hi || !lo || hi[1].n === lo[1].n) return;
      add(r11.pick(q2Buyers), iso(secs(lo[1].at) + 60), 'paid', [[Number(lo[0].split('|')[1]), hi[1].n - lo[1].n]]);
    });
    orders.sort((a, b) => Number(a[0]) - Number(b[0]));
    lines.sort((a, b) => Number(a[0]) - Number(b[0]) || Number(a[1]) - Number(b[1]));
  }
  return { tables };
}

// ─────────────────────────────── SQL building blocks ───────────────────────────────

const BST_FROM = '2026-03-29 01:00:00';
const BST_TO = '2026-10-25 01:00:00';
const LOCAL = (col: string) => `datetime(${col}, CASE WHEN ${col} >= '${BST_FROM}' AND ${col} < '${BST_TO}' THEN '+1 hour' ELSE '+0 hours' END)`;
const W_FROM = '2026-03-31 23:00:00';
const W_TO = '2026-06-30 23:00:00';
const secs = (ts: string) => Date.parse(`${ts.replace(' ', 'T')}Z`) / 1000;
const r2 = (x: number) => Math.round(x * 100) / 100;

// ─────────────────────────────── L06: balances as known that evening vs restated ───────────────────────────────

export function restatedSql(slip = ''): string {
  const day = slip === 'utc-days' ? 'date(posted_at_utc)' : `date(${LOCAL('posted_at_utc')})`;
  const known = slip === 'known-uses-today' ? 'k.all_n' : 'k.known_n';
  const counts = (n: string) => (slip === 'drop-reversed' ? `${n} = 0` : `${n} % 2 = 0`);
  const grid =
    slip === 'entry-days-only'
      ? `grid AS (
  SELECT DISTINCT h.customer_id, l.uk_day AS d FROM holders h JOIN ledger l ON l.customer_id = h.customer_id
  WHERE l.uk_day BETWEEN '2026-04-01' AND '2026-04-30'
)`
      : `grid AS (
  SELECT h.customer_id, days.d FROM holders h CROSS JOIN days
)`;
  return `WITH RECURSIVE days(d) AS (
  SELECT '2026-04-01'
  UNION ALL
  SELECT date(d, '+1 day') FROM days WHERE d < '2026-04-30'
),
ledger AS MATERIALIZED (
  SELECT entry_id, customer_id, amount_pence, entry_type, reverses_entry_id, ${day} AS uk_day
  FROM wallet_ledger
),
chain(entry_id, base_id) AS (
  SELECT l.entry_id, l.reverses_entry_id FROM ledger l
  WHERE l.entry_type = 'reversal'
    AND (SELECT b.entry_type FROM ledger b WHERE b.entry_id = l.reverses_entry_id) <> 'reversal'
  UNION ALL
  SELECT l.entry_id, c.base_id FROM ledger l JOIN chain c ON l.reverses_entry_id = c.entry_id
),
members AS MATERIALIZED (
  SELECT c.base_id, r.uk_day FROM chain c JOIN ledger r ON r.entry_id = c.entry_id
),
holders AS (
  SELECT DISTINCT customer_id FROM ledger WHERE uk_day < '2026-04-01'${slip === 'april-starters' ? " OR uk_day <= '2026-04-30'" : ''}
),
${grid},
counts AS MATERIALIZED (
  SELECT e.entry_id, e.customer_id, e.amount_pence, g.d,
         SUM(CASE WHEN m.uk_day <= g.d THEN 1 ELSE 0 END) AS known_n, COUNT(m.base_id) AS all_n
  FROM grid g
  JOIN ledger e ON e.customer_id = g.customer_id AND e.entry_type <> 'reversal' AND e.uk_day <= g.d
  LEFT JOIN members m ON m.base_id = e.entry_id
  GROUP BY e.entry_id, e.customer_id, e.amount_pence, g.d
),
balances AS (
  SELECT g.customer_id, g.d,
         COALESCE(SUM(CASE WHEN ${counts(known)} THEN k.amount_pence END), 0) AS known_p,
         COALESCE(SUM(CASE WHEN ${counts('k.all_n')} THEN k.amount_pence END), 0) AS restated_p
  FROM grid g
  LEFT JOIN counts k ON k.customer_id = g.customer_id AND k.d = g.d
  GROUP BY g.customer_id, g.d
)
SELECT customer_id, SUM(known_p < 0) AS days_negative_known, SUM(restated_p < 0) AS days_negative_restated,
       SUM(known_p <> restated_p) AS days_differ
FROM balances GROUP BY customer_id`;
}

export function restatedSolve(t: Tables): Cell[][] {
  const rows = t.wallet_ledger!.rows.map((r) => ({ id: Number(r[0]), cust: Number(r[1]), day: wh.ukLocal(String(r[2])).slice(0, 10), amt: Number(r[3]), type: String(r[4]), rev: r[5] === null ? null : Number(r[5]) }));
  const byId = new Map(rows.map((r) => [r.id, r]));
  const reversedBy = new Map<number, (typeof rows)[number]>();
  for (const r of rows) if (r.type === 'reversal' && r.rev !== null) reversedBy.set(r.rev, r);
  /** Does entry e count, when only the reversals for which `known` holds have happened? (rule 6, walked down the chain) */
  const counts = (e: (typeof rows)[number], known: (r: (typeof rows)[number]) => boolean): boolean => {
    const by = reversedBy.get(e.id);
    return !by || !known(by) || !counts(by, known);
  };
  void byId;
  const holders = [...new Set(rows.filter((r) => r.day < '2026-04-01').map((r) => r.cust))];
  const out: Cell[][] = [];
  for (const c of holders) {
    const mine = rows.filter((r) => r.cust === c && r.type !== 'reversal');
    let neg1 = 0;
    let neg2 = 0;
    let diff = 0;
    for (let d = '2026-04-01'; d <= '2026-04-30'; d = addDays1(d)) {
      const upto = mine.filter((r) => r.day <= d);
      const known = upto.filter((e) => counts(e, (r) => r.day <= d)).reduce((a, e) => a + e.amt, 0);
      const restated = upto.filter((e) => counts(e, () => true)).reduce((a, e) => a + e.amt, 0);
      if (known < 0) neg1++;
      if (restated < 0) neg2++;
      if (known !== restated) diff++;
    }
    out.push([c, neg1, neg2, diff]);
  }
  return out;
}

// ─────────────────────────────── L07: app sessions and the paid app orders they led to ───────────────────────────────

export function sessionsSql(slip = ''): string {
  const at = slip === 'no-clock-fix' ? 'event_ts_utc' : 'CASE WHEN event_ts_utc > ing THEN ing ELSE event_ts_utc END';
  const priceJoin =
    slip === 'todays-price'
      ? 'JOIN dim_price_history h ON h.product_id = l.product_id AND h.valid_to_utc IS NULL'
      : 'JOIN dim_price_history h ON h.product_id = l.product_id AND h.valid_from_utc <= o.ordered_at_utc AND (h.valid_to_utc IS NULL OR o.ordered_at_utc < h.valid_to_utc)';
  const startDay = slip === 'utc-week' ? 'first_utc' : LOCAL('first_utc');
  return `WITH e AS (
  SELECT customer_id, event_name, event_ts_utc, ${slip === 'latest-ingest' ? 'MAX' : 'MIN'}(ingested_at_utc) AS ing
  FROM events_raw GROUP BY customer_id, event_name, event_ts_utc
),
timed AS (
  SELECT customer_id, ${at} AS at_utc FROM e
),
marked AS (
  SELECT customer_id, at_utc, CAST(strftime('%s', at_utc) AS INTEGER) AS t,
         CASE WHEN CAST(strftime('%s', at_utc) AS INTEGER) - LAG(CAST(strftime('%s', at_utc) AS INTEGER)) OVER (PARTITION BY customer_id ORDER BY at_utc) <= 1800 THEN 0 ELSE 1 END AS new_session
  FROM timed
),
numbered AS (
  SELECT customer_id, at_utc, t, SUM(new_session) OVER (PARTITION BY customer_id ORDER BY t ROWS UNBOUNDED PRECEDING) AS session_no
  FROM marked
),
sessions AS (
  SELECT customer_id, session_no, MIN(t) AS t0, MAX(t) AS t1, MIN(at_utc) AS first_utc
  FROM numbered GROUP BY customer_id, session_no
),
wk AS (
  SELECT customer_id, session_no, t0, t1, date(${startDay}) AS uk_day
  FROM sessions
  WHERE ${startDay} >= '2026-04-06' AND ${startDay} < '2026-04-13'
),
app_orders AS (
  SELECT o.order_id, o.customer_id, CAST(strftime('%s', o.ordered_at_utc) AS INTEGER) AS t, SUM(l.quantity * h.price_gbp) AS revenue
  FROM fact_orders o
  JOIN fact_order_lines l ON l.order_id = o.order_id
  ${priceJoin}
  WHERE o.status = 'paid'${slip === 'any-channel' ? '' : " AND o.channel = 'app'"}
  GROUP BY o.order_id, o.customer_id, o.ordered_at_utc
),
per_session AS (
  SELECT w.uk_day, w.customer_id, w.session_no, COUNT(a.order_id) AS orders, COALESCE(SUM(a.revenue), 0) AS revenue
  FROM wk w
  LEFT JOIN app_orders a ON a.customer_id = w.customer_id AND a.t >= w.t0 AND a.t ${slip === 'window-exclusive' ? '<' : '<='} w.t1 + 1800
  GROUP BY w.uk_day, w.customer_id, w.session_no
)
SELECT uk_day, COUNT(*) AS sessions, SUM(orders > 0) AS converting_sessions, SUM(revenue) AS revenue_gbp
FROM per_session GROUP BY uk_day`;
}

interface Session {
  cust: number;
  t0: number;
  t1: number;
  first: string;
}
/** Sessions over every corrected, deduplicated event (h09's preparation, all days). */
export function sessionsOf(t: Tables): Session[] {
  const ev = new Map<string, { cust: number; ts: string; ing: string }>();
  for (const [, c, n, u, ing] of t.events_raw!.rows) {
    const k = `${c}|${n}|${u}`;
    const cur = ev.get(k);
    if (!cur || String(ing) < cur.ing) ev.set(k, { cust: Number(c), ts: String(u), ing: String(ing) });
  }
  const by = new Map<number, string[]>();
  for (const e of ev.values()) by.set(e.cust, [...(by.get(e.cust) ?? []), e.ts > e.ing ? e.ing : e.ts]);
  const out: Session[] = [];
  for (const [c, times] of by) {
    times.sort();
    let cur: Session | null = null;
    for (const at of times) {
      const s = secs(at);
      if (cur && s - cur.t1 <= 1800) cur.t1 = s;
      else {
        cur = { cust: c, t0: s, t1: s, first: at };
        out.push(cur);
      }
    }
  }
  return out;
}

export function sessionsSolve(t: Tables): Cell[][] {
  const lines = paidLines(t);
  const rev = new Map<number, number>();
  for (const l of lines) if (l.channel === 'app') rev.set(l.order, (rev.get(l.order) ?? 0) + l.qty * l.price);
  const orders = t.fact_orders!.rows.filter((o) => o[4] === 'paid' && o[3] === 'app').map((o) => ({ id: Number(o[0]), cust: Number(o[1]), t: secs(String(o[2])) }));
  const by = new Map<string, [number, number, number]>();
  for (const s of sessionsOf(t)) {
    const local = wh.ukLocal(s.first);
    if (local < '2026-04-06' || local >= '2026-04-13') continue;
    const day = local.slice(0, 10);
    const mine = orders.filter((o) => o.cust === s.cust && o.t >= s.t0 && o.t <= s.t1 + 1800);
    const a = by.get(day) ?? [0, 0, 0];
    a[0]++;
    if (mine.length) a[1]++;
    a[2] += mine.reduce((x, o) => x + (rev.get(o.id) ?? 0), 0);
    by.set(day, a);
  }
  return [...by.entries()].map(([d, [n, c, v]]) => [d, n, c, r2(v)]);
}

// ─────────────────────────────── L08-L10: promotion budgets, used up in order ───────────────────────────────

export const BUDGET_PROMOS = [2, 3, 4, 5, 6, 7, 8];

/** The promotion lines of the budget run, in the order the budgets are used up. */
function budgetCandidates(t: Tables, lv: number, slip = ''): Array<{ order: number; line: number; cust: number; utc: string; cands: Array<{ id: number; pct: number; disc: number }> }> {
  const cut = lv >= 10 && slip !== 'no-cutoff';
  const gifts = lv >= 9 && slip !== 'gifts-discounted';
  const promos = t.promotions!.rows.map((r) => ({ id: Number(r[0]), pct: Number(r[2]), ch: r[3] === null ? null : String(r[3]), min: r[4] === null ? null : Number(r[4]), s: String(r[5]), e: lv >= 9 && slip !== 'no-memo-1' && r[1] === 'MAYDAY25' ? '2026-05-02' : String(r[6]) }));
  const covers = new Set(t.promotion_products!.rows.map((r) => `${r[0]}|${r[1]}`));
  const lines = paidLines(t).filter((l) => (cut ? l.local >= W_FROM && l.local < W_TO : l.local >= '2026-04-01' && l.local < '2026-07-01'));
  const pence = (l: PLine) => Math.round(l.qty * l.price * 100);
  const basket = new Map<number, number>();
  for (const l of lines) basket.set(l.order, (basket.get(l.order) ?? 0) + pence(l));
  const out = [];
  for (const l of lines) {
    if (gifts && l.category === 'Gifts') continue;
    const day = l.local.slice(0, 10);
    const cands = promos
      .filter((p) => BUDGET_PROMOS.includes(p.id) && covers.has(`${p.id}|${l.product}`) && day >= p.s && day <= p.e && (p.ch === null || p.ch === l.channel) && (p.min === null || basket.get(l.order)! >= p.min * 100))
      .sort((a, b) => b.pct - a.pct || a.id - b.id)
      .map((p) => ({ id: p.id, pct: p.pct, disc: Math.floor((pence(l) * p.pct + 50) / 100) }));
    if (cands.length) out.push({ order: l.order, line: l.line, cust: l.cust, utc: l.utc, cands });
  }
  out.sort((a, b) => (a.utc < b.utc ? -1 : a.utc > b.utc ? 1 : a.order - b.order || a.line - b.line));
  if (slip === 'order-id-order') out.sort((a, b) => a.order - b.order || a.line - b.line);
  return out;
}

/** The budget run (independent JavaScript): remaining budgets carried line by line. */
export function budgetSolve(t: Tables, lv: number, budgets: Record<number, number>): Cell[][] {
  const left = { ...budgets };
  const used = new Map<string, number>(); // customer|promo -> the order that used it
  const lines = new Map<number, number>();
  const disc = new Map<number, number>();
  for (const l of budgetCandidates(t, lv)) {
    for (const c of l.cands) {
      if (lv >= 9) {
        const u = used.get(`${l.cust}|${c.id}`);
        if (u !== undefined && u !== l.order) continue;
      }
      let take = 0;
      if (left[c.id]! >= c.disc) take = c.disc;
      else if (lv >= 10 && left[c.id]! >= 50) take = left[c.id]!;
      else continue;
      left[c.id]! -= take;
      lines.set(c.id, (lines.get(c.id) ?? 0) + 1);
      disc.set(c.id, (disc.get(c.id) ?? 0) + take);
      if (!used.has(`${l.cust}|${c.id}`)) used.set(`${l.cust}|${c.id}`, l.order);
      break;
    }
  }
  const code = new Map(t.promotions!.rows.map((r) => [Number(r[0]), String(r[1])]));
  return BUDGET_PROMOS.filter((p) => lines.has(p)).map((p) => [code.get(p)!, lines.get(p)!, disc.get(p)! / 100, left[p]! / 100]);
}

/** Unconstrained discount per promotion (pence), used to set budgets that bind on every database. */
export function unconstrained(t: Tables): Record<number, number> {
  const out: Record<number, number> = Object.fromEntries(BUDGET_PROMOS.map((p) => [p, 0]));
  for (const l of budgetCandidates(t, 8)) out[l.cands[0]!.id]! += l.cands[0]!.disc;
  return out;
}

export function budgetSql(lv: number, budgets: Record<number, number>, slip = ''): string {
  const cut = lv >= 10 && slip !== 'no-cutoff';
  const gifts = lv >= 9 && slip !== 'gifts-discounted';
  const oneUse = lv >= 9 && slip !== 'one-use-ignored';
  const partial = lv >= 10 && slip !== 'no-partial';
  const window = cut ? `pl.ordered_at_local >= '${W_FROM}' AND pl.ordered_at_local < '${W_TO}'` : `pl.ordered_at_local >= '2026-04-01' AND pl.ordered_at_local < '2026-07-01'`;
  const bud = (c: string) => `CASE ${c} ${BUDGET_PROMOS.map((p) => `WHEN ${p} THEN w.b${p}`).join(' ')} END`;
  const fits = (c: string) =>
    slip === 'no-budget' ? '1' : partial ? `${bud(c)} >= MIN(${c === 'c.promo_id' ? 'c.disc' : 'x.disc'}, ${slip === 'partial-any' ? 1 : 50})` : `${bud(c)} >= c.disc`;
  const useOk = oneUse
    ? slip === 'one-use-per-line'
      ? ` AND instr(w.used, ',' || s.customer_id || '|' || c.promo_id || '|') = 0`
      : ` AND (instr(w.used, ',' || s.customer_id || '|' || c.promo_id || '|') = 0 OR instr(w.used, ',' || s.customer_id || '|' || c.promo_id || '|' || s.order_id || ',') > 0)`
    : '';
  const pickWhere = slip === 'no-fallback' ? `c.order_id = s.order_id AND c.line_no = s.line_no AND c.rnk = 1 AND ${fits('c.promo_id')}${useOk}` : `c.order_id = s.order_id AND c.line_no = s.line_no AND ${fits('c.promo_id')}${useOk}`;
  const pick = `(SELECT c.promo_id FROM cand c WHERE ${pickWhere} ORDER BY c.pct_off DESC, c.promo_id LIMIT 1)`;
  const taken = `(SELECT ${partial ? `MIN(c.disc, ${bud('c.promo_id')})` : 'c.disc'} FROM cand c WHERE ${pickWhere} ORDER BY c.pct_off DESC, c.promo_id LIMIT 1)`;
  const memo = lv >= 9 && slip !== 'no-memo-1' ? "CASE WHEN p.code = 'MAYDAY25' THEN '2026-05-02' ELSE p.ends_on END" : 'p.ends_on';
  const category = slip === 'gift-today' ? '(SELECT dp.category FROM dim_product dp WHERE dp.product_id = l.product_id)' : 'ch.category';
  return `WITH RECURSIVE paid_lines AS (
  SELECT o.order_id, o.customer_id, o.channel, o.ordered_at_utc,
         ${LOCAL('o.ordered_at_utc')} AS ordered_at_local,
         l.line_no, l.product_id, CAST(ROUND(l.quantity * h.price_gbp * 100) AS INTEGER) AS line_p, ${category} AS category
  FROM fact_orders o
  JOIN fact_order_lines l ON l.order_id = o.order_id
  JOIN dim_price_history h ON h.product_id = l.product_id
   AND h.valid_from_utc <= o.ordered_at_utc
   AND (h.valid_to_utc IS NULL OR o.ordered_at_utc < h.valid_to_utc)
  JOIN dim_product_category_history ch ON ch.product_id = l.product_id
   AND ch.valid_from_utc <= o.ordered_at_utc
   AND (ch.valid_to_utc IS NULL OR o.ordered_at_utc < ch.valid_to_utc)
  WHERE o.status = 'paid'
),
q2 AS (
  SELECT pl.*, date(pl.ordered_at_local) AS uk_day,
         ${slip === 'basket-line' ? 'pl.line_p' : 'SUM(pl.line_p) OVER (PARTITION BY pl.order_id)'} AS basket_p
  FROM paid_lines pl
  WHERE ${window}
),
cand AS (
  SELECT q2.order_id, q2.line_no, q2.customer_id, q2.ordered_at_utc, p.promo_id, p.pct_off,
         (q2.line_p * p.pct_off + 50) / 100 AS disc,
         RANK() OVER (PARTITION BY q2.order_id, q2.line_no ORDER BY p.pct_off DESC, p.promo_id) AS rnk
  FROM q2
  JOIN promotion_products pp ON pp.product_id = q2.product_id
  JOIN promotions p ON p.promo_id = pp.promo_id
   AND p.promo_id IN (${BUDGET_PROMOS.join(', ')})
   AND q2.uk_day BETWEEN p.starts_on AND ${memo}
   AND (p.channel IS NULL OR p.channel = q2.channel)
   AND (p.min_basket_gbp IS NULL OR q2.basket_p >= p.min_basket_gbp * 100)${gifts ? "\n  WHERE q2.category <> 'Gifts'" : ''}
),
seq AS (
  SELECT order_id, line_no, customer_id,
         ROW_NUMBER() OVER (ORDER BY ${slip === 'order-id-order' ? 'order_id, line_no' : 'ordered_at_utc, order_id, line_no'}) AS n
  FROM (SELECT DISTINCT order_id, line_no, customer_id, ordered_at_utc FROM cand)
),
walk(n, ${BUDGET_PROMOS.map((p) => `b${p}`).join(', ')}, used, pick, taken) AS (
  SELECT 0, ${BUDGET_PROMOS.map((p) => budgets[p]).join(', ')}, ',', NULL, 0
  UNION ALL
  SELECT s.n,
         ${BUDGET_PROMOS.map((p) => `w.b${p} - CASE WHEN ${pick} = ${p} THEN ${taken} ELSE 0 END`).join(',\n         ')},
         w.used || CASE WHEN ${pick} IS NULL THEN '' ELSE s.customer_id || '|' || ${pick} || '|' || s.order_id || ',' END,
         ${pick},
         COALESCE(${taken}, 0)
  FROM walk w JOIN seq s ON s.n = w.n + 1
),
budgets(promo_id, budget_p) AS (
  VALUES ${BUDGET_PROMOS.map((p) => `(${p}, ${budgets[p]})`).join(', ')}
)
SELECT p.code, COUNT(w.pick) AS lines, COALESCE(SUM(w.taken), 0) / 100.0 AS discount_gbp,
       (b.budget_p - COALESCE(SUM(w.taken), 0)) / 100.0 AS remaining_gbp
FROM budgets b
JOIN promotions p ON p.promo_id = b.promo_id
JOIN walk w ON w.pick = b.promo_id
GROUP BY b.promo_id, p.code, b.budget_p`;
}

// ─────────────────────────────── Independent JavaScript solver ───────────────────────────────

interface PLine {
  order: number;
  line: number;
  cust: number;
  channel: string;
  utc: string;
  local: string;
  product: number;
  qty: number;
  price: number;
  category: string;
}
function paidLines(t: Tables): PLine[] {
  const prices = t.dim_price_history!.rows.map((r) => ({ p: Number(r[0]), price: Number(r[1]), from: String(r[2]), to: r[3] === null ? null : String(r[3]) }));
  const cats = t.dim_product_category_history!.rows.map((r) => ({ p: Number(r[0]), cat: String(r[1]), from: String(r[2]), to: r[3] === null ? null : String(r[3]) }));
  const orders = new Map(t.fact_orders!.rows.filter((o) => o[4] === 'paid').map((o) => [Number(o[0]), o]));
  const out: PLine[] = [];
  for (const [o, ln, p, q] of t.fact_order_lines!.rows) {
    const ord = orders.get(Number(o));
    if (!ord) continue;
    const utc = String(ord[2]);
    const within = (x: { p: number; from: string; to: string | null }) => x.p === Number(p) && x.from <= utc && (x.to === null || utc < x.to);
    const pr = prices.filter(within);
    const ct = cats.filter(within);
    if (pr.length !== 1 || ct.length !== 1) throw new Error(`lookup for product ${p} at ${utc}`);
    out.push({ order: Number(o), line: Number(ln), cust: Number(ord[1]), channel: String(ord[3]), utc, local: wh.ukLocal(utc), product: Number(p), qty: Number(q), price: pr[0]!.price, category: (ct[0] as { cat: string }).cat });
  }
  return out;
}

/** The review rows computed from the raw tables, without SQL. */
// ─────────────────────────────── The questions ───────────────────────────────

const PROMO_RULES = `(a) its product is in promotion_products for that promotion; (b) the order's UK local date is between the promotion's starts_on and ends_on (inclusive); (c) the promotion's channel is NULL or equals the order's channel; (d) the promotion's min_basket_gbp is NULL, or the order's basket - the total line revenue (rule 4) of ALL the order's lines, before any promotion - is at least min_basket_gbp`;

const RESTATED_TEXT = `From Kofi (finance): "In April, how often did each wallet look overdrawn at the end of the day as we saw it that evening - and how often does it look overdrawn now that every chargeback and its undoing is on the books?"

For every customer with at least one wallet entry posted before 1 April 2026 (UK local time), and for every UK local date from 1 to 30 April 2026 (all 30 dates, including dates with no entries), work out two balances:
- As known that evening: take only the wallet entries posted up to the end of that UK date (posted_at_utc read in UK local time). Apply rule 6 to those entries alone: a reversal posted later had not happened yet, so the entry it cancels still counted that evening, and a reversal of a reversal posted later had not yet reinstated anything. The balance is the sum of the amount_pence of the counting non-reversal entries among them.
- Restated: the same entries (posted up to the end of that UK date), but which of them count is decided by rule 6 applied to the WHOLE ledger as it stands today.
Return one row per such customer with four columns: customer_id; the number of those April dates on which the balance as known that evening was below zero; the number on which the restated balance was below zero; and the number on which the two balances differ.`;

const SESSIONS_TEXT = `From Joon (product): "In the week of Monday 6 April 2026, how many app sessions did we get each day, how many of them led to a paid app order, and what did those orders bring in?"

Prepare the events in this order:
1. Duplicates (rule 5): rows with the same customer_id, event_name and event_ts_utc are one event; its ingested_at_utc is the EARLIEST of theirs.
2. Event time: some phones' clocks run fast. An event's time is its event_ts_utc, except when that is later than its ingested_at_utc (from step 1) - then its time is that ingested_at_utc.
Sessions: each customer's events (all of them, whatever the date), in order of time, form sessions; an event starts a new session when its time is more than 30 minutes after that customer's previous event (exactly 30 minutes later is the same session). A session belongs to the UK local date of its first event; keep the sessions whose first event falls from Monday 6 April 2026 00:00 to Sunday 12 April 2026 23:59:59 UK local time.
A paid order placed through the app (channel 'app') belongs to a session of the same customer when its ordered_at_utc is no earlier than the session's first event and no later than 30 minutes after the session's last event (both ends included). A session "led to an order" when at least one such order belongs to it; an order's revenue is its line revenue (rule 4).
Return one row per UK date with four columns: the date as text 'YYYY-MM-DD', the number of sessions that started that day, the number of them that led to an order, and the revenue of the orders belonging to them in GBP (±0.01).`;

function budgetText(lv: number, budgets: Record<number, number>, codes: Record<number, string>): string {
  const gbp = (p: number) => `£${(p / 100).toFixed(2)}`;
  const rules = [
    `1. A line's qualifying promotions are the promotions in the budget sheet for which all of these hold: ${PROMO_RULES}.${lv >= 9 ? ' Memo items 1 and 2 below change this.' : ''}`,
    "2. The line's discount at a promotion = its line revenue (rule 4) × pct_off / 100, rounded to the nearest penny (half a penny rounds up).",
    lv >= 10
      ? '3. Try the qualifying promotions from the highest pct_off down (ties: lowest promo_id first). The line takes the first one whose remaining budget is at least its discount at it, and that discount comes off the promotion\'s remaining budget. Exception: if a promotion\'s remaining budget is less than the line\'s discount but at least £0.50, the line takes that whole remaining budget as its discount and the promotion is used up. A promotion whose remaining budget is below £0.50 and below the line\'s discount is skipped for that line. If no promotion can be used, the line gets none.'
      : "3. Try the qualifying promotions from the highest pct_off down (ties: lowest promo_id first). The line takes the first one whose remaining budget is at least its discount at it, and that discount comes off the promotion's remaining budget. A promotion whose remaining budget is too small is skipped for this line (it may still fit a smaller line later). If none fits, the line gets no promotion.",
  ];
  if (lv >= 9) rules.push("4. One use per customer: once one of a customer's orders has used a promotion, that customer's LATER orders cannot use that promotion (treat it as not qualifying); other lines of the same order can.");
  const memo = lv >= 9
    ? `\n\nMemo from Kofi (finance) - it overrides the tables and the rules above:
1. MAYDAY25 was withdrawn early: it ended on 2 May 2026, whatever ends_on says in the promotions table.
2. Gift lines get no promotion: a line whose product was in the category 'Gifts' at the moment of the order (rule 4) qualifies for no promotion. It still counts in its order's basket for (d).${lv >= 10 ? "\n3. Finance closes each quarter at 23:00 UK local time on its last day: an order placed at or after 23:00 UK time on 31 March 2026 is a Q2 order, and one placed at or after 23:00 UK time on 30 June 2026 is not. (This cut-off only decides the quarter; the promotion dates in (b) still use the order's UK local date.)" : ''}`
    : '';
  const intro = [
    `From Kofi (finance): "Each Q2 promotion had a budget this year. Once a promotion's remaining budget was too small for a line, that line got the next best promotion instead. How did the budgets go?"`,
    `From Kofi (finance): "The Q2 promotion budgets again, now with the one-use-per-customer rule and my memo."`,
    `From Kofi (finance): "The Q2 promotion budget run as finance will book it: one use per customer, partial last uses and my whole memo."`,
  ][lv - 8]!;
  return `${intro}

Budget sheet - the most each promotion may give away in Q2 2026: ${BUDGET_PROMOS.map((p) => `${codes[p]} ${gbp(budgets[p]!)}`).join(', ')}. (NEWYEAR15 ended in January and is not part of the run.)

Work through the order lines of paid orders placed in Q2 2026 (${lv >= 10 ? 'with the cut-offs of memo item 3' : 'from 1 April to 30 June 2026, UK local dates'}) one at a time, in order of the order's ordered_at_utc, then order_id, then line_no, carrying each promotion's remaining budget from line to line:
${rules.join('\n')}${memo}

Return one row per promotion in the budget sheet that was applied to at least one line, with four columns: the promotion code, the number of lines it was applied to, the total discount it gave in GBP and its remaining budget at the end of the run in GBP (both ±0.01).`;
}

const SLIP_TEXT: Record<string, string> = {
  'known-uses-today': 'decides which entries counted that evening with reversals posted later',
  'entry-days-only': 'only looks at the dates on which an entry was posted (no carrying balances forward)',
  'utc-days': 'uses UTC dates for the end of each day',
  'drop-reversed': 'drops every reversed entry, even when the reversal was itself undone',
  'april-starters': 'also includes wallets opened in April',
  'no-clock-fix': 'no correction for fast phone clocks',
  'latest-ingest': 'corrects fast clocks with the latest delivery instead of the earliest',
  'window-exclusive': 'an order exactly 30 minutes after the last event is left out',
  'utc-week': 'the week and the session day in UTC',
  'todays-price': 'prices the orders at today’s price',
  'any-channel': 'counts web orders as well as app orders',
  'no-budget': 'ignores the budgets (the best promotion every time)',
  'no-fallback': 'a line whose best promotion has run out gets no promotion instead of the next best',
  'order-id-order': 'uses the budgets up in order of order_id instead of the order time',
  'basket-line': 'checks the minimum basket against the line instead of the whole order',
  'one-use-ignored': 'ignores the one-use-per-customer rule',
  'one-use-per-line': 'blocks the other lines of the same order once one line has used a promotion',
  'gifts-discounted': 'ignores memo item 2, so gift lines get promotions',
  'gift-today': 'decides gift lines by today’s category in dim_product',
  'no-memo-1': 'ignores memo item 1, so MAYDAY25 still runs on 3 May',
  'no-partial': 'never uses a remaining budget smaller than the discount',
  'partial-any': 'uses any remaining budget, even below £0.50',
  'no-cutoff': 'ignores the 23:00 quarter cut-off',
};

let budgetCache: { budgets: Record<number, number>; codes: Record<number, string> } | null = null;
/** Budgets are fixed in the question text: about 55% of the smallest unconstrained Q2 spend of each promotion over the
 *  four databases (rounded to £5, at least £5), so every budget runs out part-way through the quarter on every copy. */
export function budgetSheet(): { budgets: Record<number, number>; codes: Record<number, string> } {
  if (budgetCache) return budgetCache;
  const dbs = LADDER_SEEDS.map((s) => generateLadderWarehouse(s).tables);
  const totals = dbs.map(unconstrained);
  const budgets = Object.fromEntries(BUDGET_PROMOS.map((p) => [p, Math.max(500, Math.round((0.55 * Math.min(...totals.map((x) => x[p]!))) / 500) * 500)]));
  const codes = Object.fromEntries(dbs[0]!.promotions!.rows.map((r) => [Number(r[0]), String(r[1])]));
  budgetCache = { budgets, codes };
  return budgetCache;
}

function q(id: string, text: string, reference: string, solve: (t: Tables) => Cell[][], slips: Array<[string, string]>, notes: string, tolerance?: number): Question {
  return { id, difficulty: 'hard', text, ordered: false, ...(tolerance !== undefined ? { tolerance } : {}), reference, solve, careless: slips.map(([s, sql]) => ({ why: SLIP_TEXT[s]!, sql })), notes };
}

function deepQuestions(): Question[] {
  const { budgets, codes } = budgetSheet();
  const budget = (lv: number, slips: string[], notes: string) =>
    q(`L${String(lv).padStart(2, '0')}`, budgetText(lv, budgets, codes), budgetSql(lv, budgets), (t) => budgetSolve(t, lv, budgets), slips.map((s) => [s, budgetSql(lv, budgets, s)]), notes);
  return [
    q('L06', RESTATED_TEXT, restatedSql(), restatedSolve, ['known-uses-today', 'entry-days-only', 'utc-days', 'drop-reversed', 'april-starters'].map((s) => [s, restatedSql(s)]),
      'Bitemporal balances: a calendar of every April date per wallet (balances carried over empty days), rule 6 applied twice per date - to the entries known that evening only, and with every reversal known today - by the parity of each chain’s known length. Planted: April chargebacks undone days later, purchases that dip a wallet below zero for the evening, late-evening entries in BST.'),
    q('L07', SESSIONS_TEXT, sessionsSql(), sessionsSolve, ['no-clock-fix', 'latest-ingest', 'window-exclusive', 'utc-week', 'todays-price', 'any-channel'].map((s) => [s, sessionsSql(s)]),
      'Sessions over all deduplicated, clock-corrected events (earliest ingestion), kept by the UK date of their first event, then an interval join of paid app orders into [first event, last event + 30 min] with revenue at historical prices. Planted: app orders a few minutes after checkouts, exactly 30:00 and 30:01 after a session’s last event, web orders inside session windows, orders right after fast-clock events.'),
    budget(8, ['no-budget', 'no-fallback', 'order-id-order', 'basket-line'],
      'Sequential state: every promotion’s remaining budget is carried line by line in time order (a recursive CTE), each line takes the best promotion that still fits and falls back otherwise, so one wrong pick changes every later pick. Budgets are fixed in the text at about 55% of the smallest unconstrained spend over the four databases, so they run out part-way on every copy.'),
    budget(9, ['one-use-ignored', 'one-use-per-line', 'gifts-discounted', 'gift-today', 'no-memo-1', 'no-fallback'],
      'Adds state of a second kind - which customer has used which promotion, and in which order (a later order is blocked, the same order is not) - plus gift lines excluded by the category at the order moment and the memo’s early end of MAYDAY25.'),
    budget(10, ['no-partial', 'partial-any', 'no-cutoff', 'one-use-per-line', 'gift-today', 'no-fallback'],
      'Adds an exception to an exception (a too-small remaining budget is used up in part, unless it is under £0.50, when it is skipped for that line but stays available to smaller lines) and the 23:00 UK quarter cut-off.'),
  ];
}

const L01: Question = {
  id: 'L01',
  difficulty: 'easy',
  text: `From Yara (operations): "How many paid orders have we had through the website and through the app, and from how many different customers?"

Count every paid order in the warehouse (rule 3), whenever it was placed. Return one row per channel with three columns: the channel, the number of paid orders and the number of different customers who placed them.`,
  ordered: false,
  reference: `SELECT channel, COUNT(*) AS paid_orders, COUNT(DISTINCT customer_id) AS customers
FROM fact_orders
WHERE status = 'paid'
GROUP BY channel`,
  solve: (t) => {
    const by = new Map<string, { n: number; c: Set<number> }>();
    for (const o of t.fact_orders!.rows) {
      if (o[4] !== 'paid') continue;
      const a = by.get(String(o[3])) ?? { n: 0, c: new Set<number>() };
      a.n++;
      a.c.add(Number(o[1]));
      by.set(String(o[3]), a);
    }
    return [...by.entries()].map(([ch, a]) => [ch, a.n, a.c.size]);
  },
  careless: [
    { why: 'counts every order, whatever its status', sql: `SELECT channel, COUNT(*), COUNT(DISTINCT customer_id) FROM fact_orders GROUP BY channel` },
    { why: 'counts customers once per order', sql: `SELECT channel, COUNT(*), COUNT(customer_id) FROM fact_orders WHERE status = 'paid' GROUP BY channel` },
  ],
  notes: 'One table, one rule (only paid orders count). About a tenth of orders failed or were cancelled; most customers order more than once.',
};

const L02: Question = {
  id: 'L02',
  difficulty: 'easy',
  text: `From Joon (product): "How many of each kind of app event did we get in the first week of April?"

Count the events that happened from Wednesday 1 April to Tuesday 7 April 2026 inclusive, UK local time (by event_ts_utc), each duplicate delivery counted once (rule 5). Return one row per event_name with three columns: the event_name, the number of events and the number of different customers who had at least one of them.`,
  ordered: false,
  reference: `WITH e AS (
  SELECT DISTINCT customer_id, event_name, event_ts_utc FROM events_raw
)
SELECT event_name, COUNT(*) AS events, COUNT(DISTINCT customer_id) AS customers
FROM e
WHERE ${LOCAL('event_ts_utc')} >= '2026-04-01' AND ${LOCAL('event_ts_utc')} < '2026-04-08'
GROUP BY event_name`,
  solve: (t) => {
    const seen = new Set<string>();
    const by = new Map<string, { n: number; c: Set<number> }>();
    for (const [, c, n, u] of t.events_raw!.rows) {
      const k = `${c}|${n}|${u}`;
      if (seen.has(k)) continue;
      seen.add(k);
      const l = wh.ukLocal(String(u));
      if (l < '2026-04-01' || l >= '2026-04-08') continue;
      const a = by.get(String(n)) ?? { n: 0, c: new Set<number>() };
      a.n++;
      a.c.add(Number(c));
      by.set(String(n), a);
    }
    return [...by.entries()].map(([n, a]) => [n, a.n, a.c.size]);
  },
  careless: [
    { why: 'counts duplicate deliveries', sql: `SELECT event_name, COUNT(*), COUNT(DISTINCT customer_id) FROM events_raw WHERE ${LOCAL('event_ts_utc')} >= '2026-04-01' AND ${LOCAL('event_ts_utc')} < '2026-04-08' GROUP BY event_name` },
    { why: 'the week in UTC instead of UK time', sql: `SELECT event_name, COUNT(*), COUNT(DISTINCT customer_id) FROM (SELECT DISTINCT customer_id, event_name, event_ts_utc FROM events_raw) WHERE event_ts_utc >= '2026-04-01' AND event_ts_utc < '2026-04-08' GROUP BY event_name` },
    { why: 'BETWEEN on the dates, which drops most of 7 April', sql: `SELECT event_name, COUNT(*), COUNT(DISTINCT customer_id) FROM (SELECT DISTINCT customer_id, event_name, event_ts_utc FROM events_raw) WHERE ${LOCAL('event_ts_utc')} BETWEEN '2026-04-01' AND '2026-04-07' GROUP BY event_name` },
  ],
  notes: 'One table, two rules (duplicates are one event; days are UK local). Planted: events late on 31 March UTC (1 April in the UK); about 8% of events delivered twice.',
};

const reuse = (id: string, as: string): Question => ({ ...wh.QUESTIONS.find((q) => q.id === id)!, id: as, careless: [...wh.QUESTIONS.find((q) => q.id === id)!.careless] });

export const LADDER_QUESTIONS: Question[] = [L01, L02, reuse('h04', 'L03'), reuse('h07', 'L04'), reuse('h09', 'L05'), ...deepQuestions()];

/** Depth per level: the tables the reference reads and the semantics it has to compose (see the header). */
export const DEPTH = [
  { level: 'L01', tables: 1, rules: 1 },
  { level: 'L02', tables: 1, rules: 2 },
  { level: 'L03', tables: 5, rules: 4 },
  { level: 'L04', tables: 1, rules: 5 },
  { level: 'L05', tables: 1, rules: 6 },
  { level: 'L06', tables: 1, rules: 7 },
  { level: 'L07', tables: 4, rules: 8 },
  { level: 'L08', tables: 6, rules: 10 },
  { level: 'L09', tables: 6, rules: 13 },
  { level: 'L10', tables: 6, rules: 16 },
];

export const META = {
  id: 'work.sql-ladder',
  name: 'Ask the Database: Ladder',
  description:
    'Ten frozen analyst questions on a messy coffee-company warehouse, each answered with one SQLite query, each composing more subtle semantics than the last: a count, deduplicated events in UK time, then three hard questions of Ask the Database: Hard (top two with ties across a category move, balances through reversal chains, sessions after deduplication and fast-clock correction), then queries that carry state from row to row: April wallet balances as known each evening against the restated books (rule 6 evaluated as of every date, on a calendar that carries balances over empty days), app sessions joined to the paid orders that fall in their windows at historical prices, and a promotion budget run in which every line takes the best promotion that still fits its remaining budget, with one use per customer, gift lines decided by the category at the order moment, partial last uses unless under 50p, and the 23:00 quarter cut-off, so one wrong pick changes every later one. Every query runs read-only on the live database and three hidden copies and must match the reference on each, so hard-coding fails; a level is passed at 90%.',
  difficulty: 'extreme',
  tags: ['sql', 'sqlite', 'data-analysis', 'window-functions', 'time-zones', 'real-world', 'work', 'ladder'],
  hook: 'From one count to a budget run where every pick changes the next: how deep a query can it get right?',
};

export const PACK: Pack = {
  file: 'sql-ladder.json',
  meta: META,
  schema: wh.SCHEMA,
  seeds: LADDER_SEEDS,
  generate: generateLadderWarehouse,
  questions: LADDER_QUESTIONS,
  preamble: (t) => standardPreamble(wh.COMPANY, wh.SCHEMA, wh.RULES, t, wh.sampleIds(t)),
  tolerance: 0.01,
};

/** The test file: buildPack() with every check, then the ladder's notes, output budget and per-level token estimate. */
export function buildLadder(opts: { collect?: string[] } = {}): { text: string; report: ReturnType<typeof buildPack>['report'] } {
  const built = buildPack(PACK, opts);
  const obj = JSON.parse(built.text) as { cases: Array<{ id: string; prompt: string; notes: string }>; estimate: { inputTokens: number; outputTokens: number }; maxOutputTokens: number; preamble: string };
  obj.cases.forEach((c, i) => {
    const d = DEPTH[i]!;
    c.notes = c.notes.replace(/^\[(easy|medium|hard)\]/, `[level ${i + 1}] Depth: ${d.rules} semantics to compose in one query, over ${d.tables} table${d.tables > 1 ? 's' : ''}.`);
  });
  obj.maxOutputTokens = 32000;
  obj.estimate = { inputTokens: tok(obj.preamble) + Math.round(obj.cases.reduce((s, c) => s + tok(c.prompt), 0) / obj.cases.length), outputTokens: 6000 };
  return { text: serialiseTest(obj as unknown as Record<string, unknown>), report: built.report };
}

const isMain = process.argv[1] && fileURLToPath(import.meta.url) === process.argv[1];
if (isMain) {
  const { text, report } = buildLadder();
  for (const r of report) console.log(`${r.id} rows ${r.rows.join('/')} · careless ${r.careless.map((c) => `${c.score}`).join(', ')}`);
  if (process.argv.includes('--check')) {
    if (readFileSync(LADDER_FILE, 'utf8') !== text) {
      console.error(`${LADDER_FILE} differs from the generator output`);
      process.exit(1);
    }
    console.log(`${LADDER_FILE} reproduces byte for byte`);
  } else {
    writeFileSync(LADDER_FILE, text);
    console.log(`wrote ${LADDER_FILE} (${(text.length / 1024).toFixed(0)} KB)`);
  }
}
