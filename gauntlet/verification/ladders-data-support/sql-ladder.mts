// "Ask the Database: Ladder" (work.sql-ladder): ten frozen questions on Copperline Coffee's warehouse (the schema,
// rules and generator of work.sql-hard, verification/realworld-sql/warehouse.mts), each one query deeper than the
// one before. Growth is query depth, not table size: tables joined, warehouse rules and memo amendments applied,
// query stages.
//
//   L01  1 table, 1 rule        paid orders and customers per channel
//   L02  1 table, 2 rules       deduplicated app events in a UK-local week
//   L03  4 tables, 3 rules      orders and revenue per UK day over the clock change, historical prices (= sql-hard h01)
//   L04  5 tables, 4 rules      top two per category with ties across a category move (= h04)
//   L05  6 tables, 8 rules      the best single promotion per line under four conditions (= h08, the current hard case)
//   L06  6 tables, 10 rules     a per-customer Q2 review: gross, best-promotion discount, net; memo item 1
//   L07  6 tables, 12 rules     + the 23:00 quarter cut-off and "no promotion on gift lines" by the category history
//   L08  8 tables, 15 rules     + UK and Ireland only, wallet spend through reversal chains in UK local time
//   L09  9 tables, 19 rules     + subscriber orders through the lost snapshot day, an ordered segment rule list with
//                               an exception (memo item 5)
//   L10  10 tables, 22 rules    + deduplicated, clock-corrected app checkouts and a rank within each segment
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
  return { tables };
}

// ─────────────────────────────── SQL building blocks ───────────────────────────────

const BST_FROM = '2026-03-29 01:00:00';
const BST_TO = '2026-10-25 01:00:00';
const LOCAL = (col: string) => `datetime(${col}, CASE WHEN ${col} >= '${BST_FROM}' AND ${col} < '${BST_TO}' THEN '+1 hour' ELSE '+0 hours' END)`;
const W_FROM = '2026-03-31 23:00:00';
const W_TO = '2026-06-30 23:00:00';

/** The per-customer Q2 review (levels 6-10). `slip` switches exactly one detail off, the way a careful analyst slips. */
export function reviewSql(lv: number, slip = ''): string {
  const cut = lv >= 7;
  const gifts = lv >= 7;
  const country = lv >= 8;
  const wallet = lv >= 8;
  const subs = lv >= 9;
  const checkouts = lv >= 10;
  const inQ2 = (col: string) =>
    slip === 'no-cutoff' || !cut ? `${col} >= '2026-04-01' AND ${col} < '2026-07-01'` : `${col} >= '${W_FROM}' AND ${col} < '${W_TO}'`;
  const orderTime = slip === 'cutoff-utc' ? 'pl.ordered_at_utc' : 'pl.ordered_at_local';
  const ctes: string[] = [];
  ctes.push(`paid_lines AS (
  SELECT o.order_id, o.customer_id, o.channel, o.ordered_at_utc,
         ${LOCAL('o.ordered_at_utc')} AS ordered_at_local,
         l.line_no, l.product_id, l.quantity, h.price_gbp, ${slip === 'gift-today' ? '(SELECT dp.category FROM dim_product dp WHERE dp.product_id = l.product_id)' : 'ch.category'} AS category
  FROM fact_orders o
  JOIN fact_order_lines l ON l.order_id = o.order_id
  JOIN dim_price_history h ON h.product_id = l.product_id
   AND h.valid_from_utc <= o.ordered_at_utc
   AND (h.valid_to_utc IS NULL OR o.ordered_at_utc < h.valid_to_utc)
  JOIN dim_product_category_history ch ON ch.product_id = l.product_id
   AND ch.valid_from_utc <= o.ordered_at_utc
   AND (ch.valid_to_utc IS NULL OR o.ordered_at_utc < ch.valid_to_utc)
  WHERE o.status = 'paid'
)`);
  ctes.push(`q2 AS (
  SELECT pl.*, date(pl.ordered_at_local) AS uk_day,
         ${slip === 'basket-line' ? 'pl.quantity * pl.price_gbp' : 'SUM(pl.quantity * pl.price_gbp) OVER (PARTITION BY pl.order_id)'} AS basket_gbp
  FROM paid_lines pl${country && slip !== 'all-countries' ? '\n  JOIN dim_customer c ON c.customer_id = pl.customer_id' : ''}
  WHERE ${inQ2(orderTime)}${country && slip !== 'all-countries' ? "\n    AND c.country IN ('GB', 'IE')" : ''}
)`);
  ctes.push(`promos AS (
  SELECT promo_id, code, pct_off, channel, min_basket_gbp, starts_on,
         ${slip === 'no-memo-1' ? 'ends_on' : "CASE WHEN code = 'MAYDAY25' THEN '2026-05-02' ELSE ends_on END"} AS ends_on
  FROM promotions
)`);
  ctes.push(`best AS (
  SELECT q2.order_id, q2.line_no, p.pct_off,
         ROW_NUMBER() OVER (PARTITION BY q2.order_id, q2.line_no ORDER BY p.pct_off DESC, p.promo_id) AS pick
  FROM q2
  JOIN promotion_products pp ON pp.product_id = q2.product_id
  JOIN promos p ON p.promo_id = pp.promo_id
   AND ${slip === 'promo-utc-date' ? 'date(q2.ordered_at_utc)' : 'q2.uk_day'} BETWEEN p.starts_on AND p.ends_on
   AND (p.channel IS NULL OR p.channel = q2.channel)
   AND (p.min_basket_gbp IS NULL OR q2.basket_gbp >= p.min_basket_gbp)${gifts && slip !== 'gifts-discounted' ? "\n  WHERE q2.category <> 'Gifts'" : ''}
)`);
  ctes.push(`priced AS (
  SELECT q2.customer_id, q2.order_id, q2.uk_day, q2.quantity * q2.price_gbp AS revenue,
         COALESCE(q2.quantity * q2.price_gbp * b.pct_off / 100.0, 0) AS discount
  FROM q2 LEFT JOIN best b ON b.order_id = q2.order_id AND b.line_no = q2.line_no${slip === 'stack-promos' ? '' : ' AND b.pick = 1'}
)`);
  ctes.push(`cust AS (
  SELECT customer_id, ${slip === 'count-lines' ? 'COUNT(*)' : 'COUNT(DISTINCT order_id)'} AS orders,
         SUM(revenue) AS gross_gbp, SUM(discount) AS discount_gbp, SUM(revenue) - SUM(discount) AS net_gbp
  FROM priced GROUP BY customer_id
)`);
  if (wallet) {
    ctes.push(`chain(entry_id, counts) AS (
  SELECT w.entry_id, 1 FROM wallet_ledger w
  WHERE w.entry_type = 'reversal'
    AND NOT EXISTS (SELECT 1 FROM wallet_ledger x WHERE x.reverses_entry_id = w.entry_id)
  UNION ALL
  SELECT w.reverses_entry_id, 1 - c.counts
  FROM chain c JOIN wallet_ledger w ON w.entry_id = c.entry_id
  WHERE w.reverses_entry_id IS NOT NULL
)`);
    const counting =
      slip === 'drop-reversed'
        ? "NOT EXISTS (SELECT 1 FROM wallet_ledger r WHERE r.entry_type = 'reversal' AND r.reverses_entry_id = w.entry_id)"
        : 'COALESCE((SELECT c.counts FROM chain c WHERE c.entry_id = w.entry_id), 1) = 1';
    ctes.push(`wallet AS (
  SELECT w.customer_id, -SUM(w.amount_pence) / 100.0 AS spend_gbp
  FROM wallet_ledger w
  WHERE w.entry_type IN (${slip === 'purchases-only' ? "'purchase'" : "'purchase', 'refund'"})
    AND ${counting}
    AND ${slip === 'wallet-utc' ? `w.posted_at_utc >= '${W_FROM}' AND w.posted_at_utc < '${W_TO}'` : inQ2(LOCAL('w.posted_at_utc'))}
  GROUP BY w.customer_id
)`);
  }
  if (subs) {
    const fill =
      slip === 'no-lost-day'
        ? ''
        : slip === 'fill-from-15th'
          ? `
  UNION ALL
  SELECT d.customer_id, '2026-04-14', d.status
  FROM subscription_status_daily d
  WHERE d.snapshot_date = '2026-04-15'
    AND EXISTS (SELECT 1 FROM subscription_status_daily e WHERE e.customer_id = d.customer_id AND e.snapshot_date = '2026-04-13')`
          : `
  UNION ALL
  SELECT d.customer_id, '2026-04-14', d.status
  FROM subscription_status_daily d
  WHERE d.snapshot_date = '2026-04-13'
    AND EXISTS (SELECT 1 FROM subscription_status_daily e WHERE e.customer_id = d.customer_id AND e.snapshot_date = '2026-04-15')`;
    ctes.push(`filled AS (
  SELECT customer_id, snapshot_date, status FROM subscription_status_daily${fill}
)`);
    ctes.push(`sub_orders AS (
  SELECT d.customer_id, COUNT(*) AS n
  FROM (SELECT DISTINCT customer_id, order_id, ${slip === 'sub-utc-date' ? '(SELECT date(o.ordered_at_utc) FROM fact_orders o WHERE o.order_id = priced.order_id)' : 'uk_day'} AS uk_day FROM priced) d
  JOIN filled f ON f.customer_id = d.customer_id AND f.snapshot_date = d.uk_day AND f.status = 'active'
  GROUP BY d.customer_id
)`);
    ctes.push(`cancelled AS (
  SELECT DISTINCT o.customer_id FROM fact_orders o
  WHERE o.status = 'cancelled' AND ${inQ2(LOCAL('o.ordered_at_utc'))}
)`);
  }
  if (checkouts) {
    ctes.push(`ev AS (
  SELECT customer_id, event_name, event_ts_utc, ${slip === 'latest-ingest' ? 'MAX' : 'MIN'}(ingested_at_utc) AS ingested_at_utc
  FROM events_raw GROUP BY customer_id, event_name, event_ts_utc${slip === 'dup-checkouts' ? ', event_id' : ''}
)`);
    ctes.push(`checkouts AS (
  SELECT customer_id, COUNT(*) AS n
  FROM (SELECT customer_id, ${slip === 'no-clock-fix' ? 'event_ts_utc' : 'CASE WHEN event_ts_utc > ingested_at_utc THEN ingested_at_utc ELSE event_ts_utc END'} AS at_utc
        FROM ev WHERE event_name = 'checkout') x
  WHERE ${inQ2(LOCAL('x.at_utc'))}
  GROUP BY customer_id
)`);
  }
  const cols = ['c.customer_id', 'c.orders', 'c.gross_gbp', 'c.discount_gbp', 'c.net_gbp'];
  if (wallet) cols.push('COALESCE(w.spend_gbp, 0) AS wallet_spend_gbp');
  if (subs) cols.push('COALESCE(s.n, 0) AS subscriber_orders');
  if (checkouts) cols.push('COALESCE(k.n, 0) AS checkouts');
  if (subs)
    cols.push(`CASE WHEN ROUND(c.net_gbp, 2) >= 150${slip === 'cancelled-vip' ? '' : ' AND c.customer_id NOT IN (SELECT customer_id FROM cancelled)'} THEN 'VIP'
              WHEN COALESCE(s.n, 0) >= 1 THEN 'Subscriber'
              WHEN COALESCE(w.spend_gbp, 0) >= 20 THEN 'Wallet'
              ELSE 'Standard' END AS segment`);
  const joins = [wallet ? 'LEFT JOIN wallet w ON w.customer_id = c.customer_id' : '', subs ? 'LEFT JOIN sub_orders s ON s.customer_id = c.customer_id' : '', checkouts ? 'LEFT JOIN checkouts k ON k.customer_id = c.customer_id' : ''].filter(Boolean);
  const body = `SELECT ${cols.join(',\n       ')}\nFROM cust c${joins.length ? '\n' + joins.join('\n') : ''}`;
  const final = checkouts
    ? `,\nreport AS (\n${body}\n)\nSELECT *, ${slip === 'row-number' ? 'ROW_NUMBER()' : 'RANK()'} OVER (PARTITION BY segment ORDER BY ROUND(net_gbp, 2) DESC) AS segment_rank\nFROM report`
    : `\n${body}`;
  return `WITH${wallet ? ' RECURSIVE' : ''} ${ctes.join(',\n')}${final}`;
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
const r2 = (x: number) => Math.round(x * 100) / 100;
const inWindow = (local: string, cut: boolean) => (cut ? local >= W_FROM && local < W_TO : local >= '2026-04-01' && local < '2026-07-01');

/** The review rows computed from the raw tables, without SQL. */
export function reviewSolve(t: Tables, lv: number): Cell[][] {
  const cut = lv >= 7;
  const country = new Map(t.dim_customer!.rows.map((c) => [Number(c[0]), String(c[2])]));
  let lines = paidLines(t).filter((l) => inWindow(l.local, cut));
  if (lv >= 8) lines = lines.filter((l) => ['GB', 'IE'].includes(country.get(l.cust)!));
  const promos = t.promotions!.rows.map((r) => ({ id: Number(r[0]), code: String(r[1]), pct: Number(r[2]), ch: r[3] === null ? null : String(r[3]), min: r[4] === null ? null : Number(r[4]), s: String(r[5]), e: r[1] === 'MAYDAY25' ? '2026-05-02' : String(r[6]) }));
  const covers = new Set(t.promotion_products!.rows.map((r) => `${r[0]}|${r[1]}`));
  const basket = new Map<number, number>();
  for (const l of lines) basket.set(l.order, (basket.get(l.order) ?? 0) + l.qty * l.price);
  const acc = new Map<number, { orders: Set<number>; days: Map<number, string>; gross: number; disc: number }>();
  for (const l of lines) {
    const day = l.local.slice(0, 10);
    const ok = lv >= 7 && l.category === 'Gifts' ? [] : promos.filter((p) => covers.has(`${p.id}|${l.product}`) && day >= p.s && day <= p.e && (p.ch === null || p.ch === l.channel) && (p.min === null || basket.get(l.order)! >= p.min));
    ok.sort((a, b) => b.pct - a.pct || a.id - b.id);
    const rev = l.qty * l.price;
    const a = acc.get(l.cust) ?? { orders: new Set<number>(), days: new Map<number, string>(), gross: 0, disc: 0 };
    a.orders.add(l.order);
    a.days.set(l.order, day);
    a.gross += rev;
    a.disc += ok.length ? (rev * ok[0]!.pct) / 100 : 0;
    acc.set(l.cust, a);
  }
  // Wallet (rule 6 over the whole ledger, then the Q2 window in UK time).
  const spend = new Map<number, number>();
  if (lv >= 8) {
    const rows = t.wallet_ledger!.rows.map((r) => ({ id: Number(r[0]), cust: Number(r[1]), at: String(r[2]), amt: Number(r[3]), type: String(r[4]), rev: r[5] === null ? null : Number(r[5]) }));
    const reversedBy = new Map<number, number>();
    for (const r of rows) if (r.type === 'reversal' && r.rev !== null) reversedBy.set(r.rev, r.id);
    const counts = (id: number): boolean => {
      const by = reversedBy.get(id);
      return by === undefined ? true : !counts(by);
    };
    for (const r of rows) if ((r.type === 'purchase' || r.type === 'refund') && counts(r.id) && inWindow(wh.ukLocal(r.at), true)) spend.set(r.cust, (spend.get(r.cust) ?? 0) - r.amt);
  }
  // Subscriptions (rule 7) and cancelled orders.
  const status = new Map<string, string>();
  const cancelled = new Set<number>();
  if (lv >= 9) {
    for (const [c, d, st] of t.subscription_status_daily!.rows) status.set(`${c}|${d}`, String(st));
    for (const c of new Set(t.subscription_status_daily!.rows.map((r) => Number(r[0])))) {
      const before = status.get(`${c}|2026-04-13`);
      if (before !== undefined && status.has(`${c}|2026-04-15`)) status.set(`${c}|2026-04-14`, before);
    }
    for (const o of t.fact_orders!.rows) if (o[4] === 'cancelled' && inWindow(wh.ukLocal(String(o[2])), true)) cancelled.add(Number(o[1]));
  }
  // Checkouts (rule 5 with the earliest ingestion, fast clocks corrected).
  const chk = new Map<number, number>();
  if (lv >= 10) {
    const ev = new Map<string, { cust: number; name: string; ts: string; ing: string }>();
    for (const [, c, n, u, ing] of t.events_raw!.rows) {
      const k = `${c}|${n}|${u}`;
      const cur = ev.get(k);
      if (!cur || String(ing) < cur.ing) ev.set(k, { cust: Number(c), name: String(n), ts: String(u), ing: String(ing) });
    }
    for (const e of ev.values()) {
      if (e.name !== 'checkout') continue;
      const at = e.ts > e.ing ? e.ing : e.ts;
      if (inWindow(wh.ukLocal(at), true)) chk.set(e.cust, (chk.get(e.cust) ?? 0) + 1);
    }
  }
  const rows: Cell[][] = [];
  for (const [c, a] of acc) {
    const net = a.gross - a.disc;
    const row: Cell[] = [c, a.orders.size, r2(a.gross), r2(a.disc), r2(net)];
    const w = spend.get(c) ?? 0;
    if (lv >= 8) row.push(r2(w / 100));
    if (lv >= 9) {
      const subOrders = [...a.days.values()].filter((d) => status.get(`${c}|${d}`) === 'active').length;
      row.push(subOrders);
      if (lv >= 10) row.push(chk.get(c) ?? 0);
      row.push(r2(net) >= 150 && !cancelled.has(c) ? 'VIP' : subOrders >= 1 ? 'Subscriber' : w / 100 >= 20 ? 'Wallet' : 'Standard');
    }
    rows.push(row);
  }
  if (lv >= 10) {
    const segAt = rows[0]!.length - 1;
    for (const row of rows) row.push(1 + rows.filter((o) => o[segAt] === row[segAt] && Number(o[4]) > Number(row[4])).length);
  }
  return rows;
}

// ─────────────────────────────── The questions ───────────────────────────────

const PROMO_RULES = `An order line qualifies for a promotion when all of these hold: (a) its product is in promotion_products for that promotion; (b) the order's UK local date is between the promotion's starts_on and ends_on (inclusive); (c) the promotion's channel is NULL or equals the order's channel; (d) the promotion's min_basket_gbp is NULL, or the order's basket - the total line revenue (rule 4) of ALL the order's lines, before any promotion - is at least min_basket_gbp. A line gets at most one promotion: the qualifying one with the highest pct_off; if several tie on pct_off, the one with the lowest promo_id. The line's discount = its line revenue (rule 4) × pct_off / 100.`;

const MEMO = [
  'MAYDAY25 was withdrawn early: it ended on 2 May 2026, whatever ends_on says in the promotions table.',
  "Finance closes each quarter at 23:00 UK local time on its last day: an order placed at or after 23:00 UK time on 31 March 2026 is a Q2 order, and one placed at or after 23:00 UK time on 30 June 2026 is not (it belongs to Q3). This cut-off only decides the quarter; the promotion dates in (b) still use the order's UK local date.",
  "Gift lines get no promotion: a line whose product was in the category 'Gifts' at the moment of the order (rule 4) cannot get any promotion. It still counts in gross and in its order's basket.",
  "The review covers customers in the UK and Ireland only: leave out every customer whose country is not 'GB' or 'IE' (their orders are not part of the review at all).",
  "A customer with a cancelled order (status 'cancelled') placed in Q2 (the cut-offs of item 2, UK local time) is never 'VIP': apply the next segment rule that fits.",
];

function reviewText(lv: number): string {
  const memo = MEMO.slice(0, lv >= 9 ? 5 : lv >= 8 ? 4 : lv >= 7 ? 3 : 1);
  const q2 = lv >= 7 ? 'paid orders placed in Q2 2026 (UK local time, with the cut-offs of memo item 2)' : 'paid orders placed from 1 April to 30 June 2026 (UK local dates)';
  const defs = [
    `- Q2 orders: ${q2}.`,
    `- gross: the line revenue (rule 4) of all lines of the customer's Q2 orders, in GBP.`,
    `- discount: the total discount on those lines. ${PROMO_RULES}`,
    '- net: gross minus discount.',
  ];
  const cols = ['customer_id', 'the number of Q2 orders', 'gross', 'discount', 'net'];
  if (lv >= 8) {
    defs.push(`- wallet spend: work out which wallet entries count under rule 6, using the whole ledger (follow every reversal chain to the end). Take the customer's counting entries of type 'purchase' or 'refund' posted in Q2 (posted_at_utc read in UK local time, with the cut-offs of memo item 2). Wallet spend = minus the sum of their amount_pence, divided by 100 (purchases are negative, so spending is positive and refunds reduce it); 0 if there are none.`);
    cols.push('wallet spend');
  }
  if (lv >= 9) {
    defs.push(`- subscriber orders: how many of the customer's Q2 orders were placed on a UK local date on which the customer's status in subscription_status_daily was 'active' (apply rule 7 for 2026-04-14 first); 0 if none.`);
    cols.push('subscriber orders');
  }
  if (lv >= 10) {
    defs.push(`- checkouts: the customer's app 'checkout' events in Q2. Prepare events_raw first: rows with the same customer_id, event_name and event_ts_utc are one event (rule 5), whose ingested_at_utc is the EARLIEST of theirs; an event's time is its event_ts_utc, except when that is later than its ingested_at_utc - then its time is that ingested_at_utc (some phones' clocks run fast). Count the checkout events whose time, in UK local time, falls in Q2 (the cut-offs of memo item 2); 0 if none.`);
    cols.push('checkouts');
  }
  if (lv >= 9) {
    defs.push(`- segment: the first of these rules that applies: (1) 'VIP' if net rounded to the penny is at least 150.00; (2) 'Subscriber' if subscriber orders is at least 1; (3) 'Wallet' if wallet spend is at least 20.00; (4) 'Standard'.`);
    cols.push("segment (the text 'VIP', 'Subscriber', 'Wallet' or 'Standard')");
  }
  if (lv >= 10) {
    defs.push(`- segment rank: the customer's rank within their segment by net rounded to the penny, highest first; tied customers share a rank and the next rank is skipped (1, 2, 2, 4).`);
    cols.push('segment rank');
  }
  const intro = [
    '',
    '',
    '',
    '',
    '',
    '',
    `From Femi (finance): "I need a Q2 2026 revenue review per customer, with what each promotion really took off. Kofi's memo has a correction - apply it."`,
    `From Femi (finance): "Q2 2026 revenue review per customer again, but this time with all of Kofi's memo applied."`,
    `From Femi (finance): "The Q2 2026 customer review for the board: revenue after promotions and wallet spend, UK and Ireland only, by Kofi's memo."`,
    `From Femi (finance): "The Q2 2026 customer review with segments for the CRM team, by Kofi's memo."`,
    `From Femi (finance): "The full Q2 2026 customer review: revenue after promotions, wallet spend, subscriptions, app checkouts, segments and ranks, by Kofi's memo."`,
  ][lv]!;
  const money = ['gross', 'discount', 'net', 'wallet spend'].filter((c) => cols.includes(c));
  return `${intro}

Definitions:
${defs.join('\n')}

Memo from Kofi (finance) - it overrides the tables and the definitions above:
${memo.map((m, i) => `${i + 1}. ${m}`).join('\n')}

Return one row per customer${lv >= 8 ? ' in the review' : ''} with at least one Q2 order, with ${cols.length} columns: ${cols.join(', ')}. ${money.length > 1 ? `${money.slice(0, -1).join(', ')} and ${money[money.length - 1]} are` : `${money[0]} is`} in GBP (each compared to within ±0.01).`;
}

const SLIP_TEXT: Record<string, string> = {
  'no-memo-1': 'ignores memo item 1, so MAYDAY25 still runs on 3 May',
  'count-lines': 'counts order lines instead of orders',
  'stack-promos': 'gives a line every qualifying promotion, not just the best one',
  'basket-line': 'checks the minimum basket against the line instead of the whole order',
  'promo-utc-date': 'uses the UTC date for the promotion windows',
  'no-cutoff': 'ignores the 23:00 quarter cut-off (memo item 2)',
  'cutoff-utc': 'applies the 23:00 cut-off to the UTC timestamp',
  'gift-today': 'decides gift lines by today’s category in dim_product',
  'gifts-discounted': 'ignores memo item 3, so gift lines get promotions',
  'all-countries': 'keeps customers outside the UK and Ireland (memo item 4)',
  'drop-reversed': 'drops every reversed wallet entry, even when the reversal was itself undone',
  'purchases-only': 'leaves wallet refunds out of wallet spend',
  'wallet-utc': 'takes the wallet window in UTC',
  'no-lost-day': 'ignores the lost day, 2026-04-14',
  'fill-from-15th': 'fills the lost day from 15 April instead of 13 April',
  'sub-utc-date': 'matches the subscription status on the UTC date of the order',
  'cancelled-vip': 'ignores memo item 5, so a customer with a cancelled order can still be VIP',
  'dup-checkouts': 'counts duplicate checkout deliveries',
  'no-clock-fix': 'no correction for fast phone clocks',
  'latest-ingest': 'corrects fast clocks with the latest delivery instead of the earliest',
  'row-number': 'ROW_NUMBER instead of RANK, so tied customers get different ranks',
};
const SLIPS_BY_LEVEL: Record<number, string[]> = {
  6: ['no-memo-1', 'count-lines', 'stack-promos', 'basket-line'],
  7: ['no-cutoff', 'cutoff-utc', 'gift-today', 'gifts-discounted', 'no-memo-1'],
  8: ['all-countries', 'drop-reversed', 'purchases-only', 'wallet-utc', 'no-cutoff', 'gift-today'],
  9: ['no-lost-day', 'fill-from-15th', 'sub-utc-date', 'cancelled-vip', 'drop-reversed', 'all-countries', 'no-cutoff'],
  10: ['dup-checkouts', 'no-clock-fix', 'row-number', 'no-lost-day', 'cancelled-vip', 'drop-reversed', 'gift-today', 'no-memo-1'],
};

function reviewQuestion(lv: number): Question {
  const notes: Record<number, string> = {
    6: 'Per-customer rollup of the L05 promotion logic (many-to-many join, four conditions, one winner per line by ROW_NUMBER, basket from a window over the whole order) with a memo amendment that overrides a table value. Planted: a big kit basket on 3 May, the day MAYDAY25 no longer runs.',
    7: 'Adds a quarter defined by 23:00 UK-time cut-offs in BST (orders at 22:xx UTC on 31 March and 30 June) while promotion windows keep the UK date, and a promotion exclusion decided by the category history at the order moment (products move in and out of Gifts during the quarter).',
    8: 'Adds a country filter that removes whole customers and a wallet measure: a recursive walk down each reversal chain over the whole ledger (a reversed reversal reinstates its target), then purchases and refunds posted in the UK-time quarter. Planted: wallet purchases at 21:xx and 22:xx UTC on both cut-off days.',
    9: 'Adds the daily subscription snapshot with the lost day filled from the day before, matched on each order’s UK date, and an ordered segment rule list whose first rule has a memo exception. Planted: subscriber orders on 14 April and at 23:xx UTC on 13 April, a paused-then-active subscriber ordering on 14 April, big spenders with a cancelled order.',
    10: 'Adds app checkouts after deduplication with the earliest ingestion and fast-clock correction, filtered by the UK-time quarter, and RANK() within each segment on net rounded to the penny (ties share a rank). Ten tables, five memo items and every warehouse rule at once.',
  };
  return {
    id: `L${String(lv).padStart(2, '0')}`,
    difficulty: 'hard',
    text: reviewText(lv),
    ordered: false,
    reference: reviewSql(lv),
    solve: (t) => reviewSolve(t, lv),
    careless: SLIPS_BY_LEVEL[lv]!.map((s) => ({ why: SLIP_TEXT[s]!, sql: reviewSql(lv, s) })),
    notes: notes[lv]!,
  };
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

export const LADDER_QUESTIONS: Question[] = [L01, L02, reuse('h01', 'L03'), reuse('h04', 'L04'), reuse('h08', 'L05'), ...[6, 7, 8, 9, 10].map(reviewQuestion)];

/** Depth per level: the tables the reference reads and the rules, conditions and memo items it must apply (L05: paid,
 *  UK date, historical price, the four promotion conditions, one best promotion; each later level adds the items its
 *  notes list: L06 the customer rollup and memo item 1; L07 the cut-off and gift lines; L08 countries, reversal chains
 *  and the wallet window; L09 the lost day, the subscription match, the segment list and its exception; L10
 *  deduplication, the clock correction and ranks with ties). */
export const DEPTH = [
  { level: 'L01', tables: 1, rules: 1 },
  { level: 'L02', tables: 1, rules: 2 },
  { level: 'L03', tables: 4, rules: 3 },
  { level: 'L04', tables: 5, rules: 4 },
  { level: 'L05', tables: 6, rules: 8 },
  { level: 'L06', tables: 6, rules: 10 },
  { level: 'L07', tables: 6, rules: 12 },
  { level: 'L08', tables: 8, rules: 15 },
  { level: 'L09', tables: 9, rules: 19 },
  { level: 'L10', tables: 10, rules: 22 },
];

export const META = {
  id: 'work.sql-ladder',
  name: 'Ask the Database: Ladder',
  description:
    'Ten frozen analyst questions on a messy coffee-company warehouse, each answered with one SQLite query and each one query deeper than the last: from counting paid orders in one table, through UK local time across the clock change, price and category history and the best single promotion per line (the hardest questions of Ask the Database: Hard), up to a per-customer Q2 review that joins ten tables and applies a finance memo with five amendments (a promotion withdrawn early, 23:00 quarter cut-offs, no promotions on gift lines by the category at the order moment, UK and Ireland only, no VIP with a cancelled order), wallet spend through reversal chains, subscriber orders through a lost snapshot day, deduplicated and clock-corrected app checkouts, an ordered segment rule list and ranks with ties. Every query runs read-only on the live database and three hidden copies and must match the reference on each, so hard-coding fails; a level is passed at 90%.',
  difficulty: 'extreme',
  tags: ['sql', 'sqlite', 'data-analysis', 'window-functions', 'time-zones', 'real-world', 'work', 'ladder'],
  hook: 'From one table and one rule to ten tables and a five-point memo: how deep a query can it get right?',
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
    c.notes = c.notes.replace(/^\[(easy|medium|hard)\]/, `[level ${i + 1}] Depth: ${d.tables} table${d.tables > 1 ? 's' : ''}; ${d.rules} rule${d.rules > 1 ? 's, conditions and memo items' : ''} to apply.`);
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
