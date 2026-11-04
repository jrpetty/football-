// "Ask the Database: Hard" (work.sql-hard): the analytics warehouse of Copperline Coffee, a UK roaster that sells
// beans and brewing kit through its website and app. Messier than the shop: UTC timestamps with UK local-time rules
// (the BST change on 29 March), a slowly changing price dimension, a many-to-many promotions table, an event stream
// with duplicate deliveries, a daily subscription snapshot and a wallet ledger with reversals.
import { addDays, createRng, daysBetween, fromMin, money, names, toMin, type Cell, type Question, type Rng, type Tables } from './lib.mts';

export const SEEDS = [20260415, 55127, 81233, 96011];

export const META = {
  id: 'work.sql-hard',
  name: 'Ask the Database: Hard',
  description:
    'Nine analyst questions on a messy coffee-company warehouse, each answered with one SQLite query: UTC timestamps read in UK local time across the clock change, price and category history (slowly changing dimensions), duplicated app events, a daily snapshot that lost a day, promotions with channel and basket rules, and a wallet ledger whose reversals can themselves be reversed. Easy: orders per UK day, deduplicated event counts, revenue at historical prices and categories. Medium: top-N per group with ties across a category move, one- and two-month cohort retention, pause streaks through the lost day. Hard: lowest and end-of-day wallet balances through reversal chains, the best single promotion per line under four conditions, and sessions with checkouts after deduplication and fast-phone-clock correction. The query runs read-only on the live database and on three hidden copies with different data and must match the reference query on each, so a hard-coded answer fails.',
  difficulty: 'extreme',
  tags: ['sql', 'sqlite', 'data-analysis', 'window-functions', 'time-zones', 'real-world', 'work', 'frontier'],
  hook: 'Time zones, price history, duplicate events, reversals: nine analyst questions where one slip shows.',
};

export const SCHEMA = `CREATE TABLE dim_customer (
  customer_id    INTEGER PRIMARY KEY,
  full_name      TEXT NOT NULL,
  country        TEXT NOT NULL,
  created_at_utc TEXT NOT NULL    -- when the account was created (UTC)
);
CREATE TABLE dim_product (
  product_id INTEGER PRIMARY KEY,
  sku        TEXT NOT NULL,
  name       TEXT NOT NULL,
  category   TEXT NOT NULL        -- the CURRENT category: 'Beans', 'Brewing kit' or 'Gifts'
);
CREATE TABLE dim_product_category_history (  -- slowly changing dimension: products sometimes move category
  product_id     INTEGER NOT NULL REFERENCES dim_product(product_id),
  category       TEXT NOT NULL,
  valid_from_utc TEXT NOT NULL,   -- inclusive
  valid_to_utc   TEXT             -- exclusive; NULL = current
);
CREATE TABLE dim_price_history (  -- slowly changing dimension: one row per price period
  product_id     INTEGER NOT NULL REFERENCES dim_product(product_id),
  price_gbp      REAL NOT NULL,
  valid_from_utc TEXT NOT NULL,   -- the price applies from this moment (inclusive) ...
  valid_to_utc   TEXT             -- ... until this moment (exclusive); NULL = current price
);
CREATE TABLE fact_orders (
  order_id       INTEGER PRIMARY KEY,
  customer_id    INTEGER NOT NULL REFERENCES dim_customer(customer_id),
  ordered_at_utc TEXT NOT NULL,
  channel        TEXT NOT NULL,   -- 'web' or 'app'
  status         TEXT NOT NULL    -- 'paid', 'payment_failed' or 'cancelled'
);
CREATE TABLE fact_order_lines (
  order_id   INTEGER NOT NULL REFERENCES fact_orders(order_id),
  line_no    INTEGER NOT NULL,
  product_id INTEGER NOT NULL REFERENCES dim_product(product_id),
  quantity   INTEGER NOT NULL,    -- no price here: see dim_price_history
  PRIMARY KEY (order_id, line_no)
);
CREATE TABLE promotions (
  promo_id       INTEGER PRIMARY KEY,
  code           TEXT NOT NULL,
  pct_off        INTEGER NOT NULL,  -- e.g. 15 = 15% off
  channel        TEXT,              -- 'web' or 'app' = only orders from that channel; NULL = both
  min_basket_gbp REAL,              -- only orders whose line revenue (rule 4) totals at least this; NULL = no minimum
  starts_on      TEXT NOT NULL,     -- UK local date, inclusive
  ends_on        TEXT NOT NULL      -- UK local date, inclusive
);
CREATE TABLE promotion_products ( -- which products each promotion covers (many-to-many)
  promo_id   INTEGER NOT NULL REFERENCES promotions(promo_id),
  product_id INTEGER NOT NULL REFERENCES dim_product(product_id),
  PRIMARY KEY (promo_id, product_id)
);
CREATE TABLE events_raw (         -- app tracking, as delivered by the pipeline
  event_id        INTEGER PRIMARY KEY,
  customer_id     INTEGER NOT NULL,
  event_name      TEXT NOT NULL,  -- 'app_open', 'view_product', 'add_to_basket' or 'checkout'
  event_ts_utc    TEXT NOT NULL,  -- when it happened on the phone (UTC)
  ingested_at_utc TEXT NOT NULL   -- when the pipeline stored it
);
CREATE TABLE subscription_status_daily (  -- one row per subscriber per UK day
  customer_id   INTEGER NOT NULL,
  snapshot_date TEXT NOT NULL,    -- UK local date 'YYYY-MM-DD'
  status        TEXT NOT NULL,    -- 'active', 'paused' or 'cancelled'
  PRIMARY KEY (customer_id, snapshot_date)
);
CREATE TABLE wallet_ledger (      -- prepaid coffee wallet
  entry_id          INTEGER PRIMARY KEY,
  customer_id       INTEGER NOT NULL,
  posted_at_utc     TEXT NOT NULL,
  amount_pence      INTEGER NOT NULL,  -- money in is positive, money out negative
  entry_type        TEXT NOT NULL,     -- 'topup', 'purchase', 'refund' or 'reversal'
  reverses_entry_id INTEGER            -- for a 'reversal': the entry it cancels; otherwise NULL
);`;

export const RULES = `Warehouse rules (every report follows them):
1. Every column ending in _utc is a UTC timestamp stored as text 'YYYY-MM-DD HH:MM:SS'. Reports use UK local time: in this data UK local time equals UTC, except from 2026-03-29 01:00:00 UTC (inclusive) to 2026-10-25 01:00:00 UTC (exclusive), when it is UTC + 1 hour (British Summer Time).
2. Days, months and quarters are UK local ones (an order placed at 23:30 UTC on 31 March 2026 was placed on 1 April, UK time). Columns without a time (starts_on, ends_on, snapshot_date) are already UK local dates.
3. Only orders with status 'paid' count as orders or revenue.
4. The category of a product on an order is the dim_product_category_history row whose period contains the order moment (valid_from_utc <= ordered_at_utc < valid_to_utc, NULL = no end); dim_product.category is only today's category. The price of an order line is the dim_price_history row of its product whose period contains the order moment: valid_from_utc <= ordered_at_utc < valid_to_utc (valid_to_utc NULL = no end). Line revenue = quantity × that price, in GBP, before any promotion.
5. events_raw contains duplicate deliveries: rows with the same customer_id, event_name and event_ts_utc are one event (their event_id and ingested_at_utc differ).
6. In wallet_ledger a 'reversal' cancels the entry named in its reverses_entry_id - unless that reversal is itself cancelled by a later reversal, in which case the entry it pointed at counts again (the payment provider undid the chargeback). Chains can be several reversals deep. Reversal rows never change a balance themselves: a balance is the sum of the amounts of the non-reversal entries that are not cancelled. Entries are applied in order of posted_at_utc, then entry_id.
7. subscription_status_daily lost a whole day: there are no rows at all for 2026-04-14. Treat a customer who has rows on both 2026-04-13 and 2026-04-15 as having had their 2026-04-13 status on 2026-04-14.`;

export const COMPANY = `You are an analyst at Copperline Coffee, a UK coffee roaster that sells beans, brewing kit and gifts through its website and app. You query its analytics warehouse, which is SQLite. Below are the tables, the warehouse rules and a few sample rows. Today is Wednesday 1 July 2026.`;

// ─────────────────────────────── Time ───────────────────────────────

const BST_FROM = '2026-03-29 01:00:00';
const BST_TO = '2026-10-25 01:00:00';
/** UK local timestamp of a UTC timestamp (rule 1). */
export function ukLocal(ts: string): string {
  if (ts >= BST_FROM && ts < BST_TO) {
    const d = new Date(`${ts.replace(' ', 'T')}Z`);
    d.setUTCHours(d.getUTCHours() + 1);
    return d.toISOString().slice(0, 19).replace('T', ' ');
  }
  return ts;
}
const secs = (ts: string) => Date.parse(`${ts.replace(' ', 'T')}Z`) / 1000;
const LOCAL = (col: string) => `datetime(${col}, CASE WHEN ${col} >= '${BST_FROM}' AND ${col} < '${BST_TO}' THEN '+1 hour' ELSE '+0 hours' END)`;

function ts(r: Rng, fromDay: string, toDay: string, hours: [number, number] = [0, 24]): string {
  const day = addDays(fromDay, r.int(0, daysBetween(fromDay, toDay) - 1));
  return fromMin(toMin(`${day} 00:00:00`) + r.int(hours[0] * 60, hours[1] * 60 - 1), r.int(0, 59));
}
const plusSec = (t: string, s: number) => new Date(Date.parse(`${t.replace(' ', 'T')}Z`) + s * 1000).toISOString().slice(0, 19).replace('T', ' ');

// ─────────────────────────────── Data ───────────────────────────────

const PRODUCTS: Array<[string, string, string, number]> = [
  ['BN-ETH', 'Ethiopia Yirgacheffe 250 g', 'Beans', 9.5],
  ['BN-COL', 'Colombia Huila 250 g', 'Beans', 8.75],
  ['BN-HSE', 'House Espresso 1 kg', 'Beans', 27],
  ['BN-DEC', 'Decaf Swiss Water 250 g', 'Beans', 9],
  ['BN-KEN', 'Kenya AA 250 g', 'Beans', 11.25],
  ['BN-GUA', 'Guatemala Antigua 250 g', 'Beans', 8.95],
  ['KT-V60', 'V60 Dripper', 'Brewing kit', 22],
  ['KT-GRD', 'Hand Grinder', 'Brewing kit', 64],
  ['KT-KTL', 'Gooseneck Kettle', 'Brewing kit', 48],
  ['KT-FLT', 'Paper Filters (100)', 'Brewing kit', 6],
  ['GF-BOX', 'Tasting Box', 'Gifts', 29],
  ['GF-MUG', 'Enamel Mug', 'Gifts', 12],
  ['GF-TOT', 'Canvas Tote', 'Gifts', 10],
];

export function generateWarehouse(seed: number): { tables: Tables } {
  const rng = createRng(seed);
  // ── customers ──
  const cr = rng.fork('customers');
  const nCust = 58 + cr.int(0, 10);
  const custNames = names(cr, nCust);
  const customers: Cell[][] = custNames.map((n, i) => [i + 1, n, cr.pick(['GB', 'GB', 'GB', 'GB', 'IE', 'FR', 'NL', 'US']), ts(cr, '2025-06-01', '2026-05-20', [6, 23])]);

  // ── prices (SCD2) ──
  const pr = rng.fork('prices');
  const prices: Cell[][] = [];
  const priceRows: Array<{ p: number; price: number; from: string; to: string | null }> = [];
  PRODUCTS.forEach(([, , , base], i) => {
    const p = i + 1;
    const changes: string[] = [];
    const n = pr.pick([0, 1, 1, 2, 2, 3]);
    const cand = ['2026-01-15 09:00:00', '2026-03-01 00:00:00', '2026-03-29 01:00:00', '2026-04-01 00:00:00', '2026-04-20 08:30:00', '2026-05-12 06:00:00', '2026-06-01 00:00:00'];
    for (const c of pr.shuffle(cand).slice(0, n).sort()) changes.push(c);
    let from = '2025-01-01 00:00:00';
    let price = base;
    for (const c of changes) {
      priceRows.push({ p, price, from, to: c });
      from = c;
      price = money(price * pr.pick([1.05, 1.08, 1.1, 0.9, 1.12]));
    }
    priceRows.push({ p, price, from, to: null });
  });
  for (const r of priceRows) prices.push([r.p, r.price, r.from, r.to]);
  const changeMoments = [...new Set(priceRows.filter((r) => r.to).map((r) => r.to!))];
  // Category moves in Q2: two or three products change category at a random moment.
  const kr = rng.fork('categories');
  const moves = new Map<number, { to: string; at: string }>();
  const candidates: Array<[number, string]> = [[11, 'Beans'], [10, 'Gifts'], [12, 'Brewing kit'], [4, 'Gifts'], [13, 'Brewing kit']];
  for (const [p, to] of kr.shuffle(candidates).slice(0, kr.int(2, 3))) moves.set(p, { to, at: ts(kr, '2026-04-12', '2026-06-12', [0, 24]) });
  const catHist: Cell[][] = [];
  PRODUCTS.forEach(([, , cat], i) => {
    const m = moves.get(i + 1);
    if (m) catHist.push([i + 1, cat, '2025-01-01 00:00:00', m.at], [i + 1, m.to, m.at, null]);
    else catHist.push([i + 1, cat, '2025-01-01 00:00:00', null]);
  });

  // ── orders ──
  const or = rng.fork('orders');
  const orders: Cell[][] = [];
  const lines: Cell[][] = [];
  let oid = 70000 + or.int(0, 900);
  const addOrder = (cust: number, at: string, r: Rng, status?: string, forced?: Array<[number, number]>) => {
    const id = oid++;
    orders.push([id, cust, at, r.chance(0.55) ? 'app' : 'web', status ?? (r.chance(0.9) ? 'paid' : r.chance(0.6) ? 'payment_failed' : 'cancelled')]);
    const chosen: Array<[number, number]> = forced ?? [];
    if (!forced) {
      const n = r.pick([1, 1, 2, 2, 3]);
      while (chosen.length < n) {
        const p = r.chance(0.6) ? r.int(1, 6) : r.int(7, 13);
        if (!chosen.some((c) => c[0] === p)) chosen.push([p, p <= 6 ? r.pick([1, 1, 2, 2, 3]) : r.pick([1, 1, 1, 2])]);
      }
    }
    chosen.forEach(([p, q], k) => lines.push([id, k + 1, p, q]));
    return id;
  };
  for (const c of customers) {
    const cid = Number(c[0]);
    const r = or.fork(`c${cid}`);
    const created = String(c[3]);
    const start = created > '2025-12-01' ? created.slice(0, 10) : '2025-12-01';
    if (start >= '2026-06-29') continue;
    const n = r.pick([0, 1, 1, 2, 2, 3, 3, 4, 5, 6, 7]);
    for (let k = 0; k < n; k++) addOrder(cid, ts(r, start, '2026-07-01', [6, 24]), r);
  }
  const pick = (r: Rng) => r.int(1, nCust);
  {
    const r = or.fork('planted');
    // Late-evening orders around the end of March and the clock change, and at the end of other months.
    for (const day of ['2026-03-26', '2026-03-27', '2026-03-28', '2026-03-29', '2026-03-30', '2026-03-31']) {
      for (let k = 0; k < 2 + r.int(0, 2); k++) addOrder(pick(r), ts(r, day, addDays(day, 1), [8, 22]), r);
      addOrder(pick(r), fromMin(toMin(`${day} 23:00:00`) + r.int(0, 59), r.int(0, 59)), r, 'paid');
      if (r.chance(0.6)) addOrder(pick(r), fromMin(toMin(`${day} 00:00:00`) + r.int(0, 59), r.int(0, 59)), r, 'paid');
    }
    for (const day of ['2026-04-30', '2026-05-31', '2026-06-30', '2026-01-31', '2026-02-28']) addOrder(pick(r), fromMin(toMin(`${day} 23:00:00`) + r.int(5, 55), r.int(0, 59)), r, 'paid');
    // Orders in the hours either side of each category move.
    for (const [p, m] of moves) {
      addOrder(pick(r), plusSec(m.at, -r.int(60, 7200)), r, 'paid', [[p, r.int(2, 4)]]);
      addOrder(pick(r), plusSec(m.at, r.int(0, 7200)), r, 'paid', [[p, r.int(2, 4)]]);
      if (r.chance(0.5)) addOrder(pick(r), m.at, r, 'paid', [[p, r.int(1, 3)]]);
    }
    // Orders placed at the exact moment a price changed (the new price applies).
    for (const m of changeMoments) {
      const prod = priceRows.find((x) => x.to === m)!.p;
      if (r.chance(0.7)) addOrder(pick(r), m, r, 'paid', [[prod, r.int(1, 3)]]);
    }
  }

  // ── promotions ──
  const mr = rng.fork('promos');
  const beans = [1, 2, 3, 4, 5, 6];
  const kit = [7, 8, 9, 10];
  const gifts = [11, 12, 13];
  const sh = (d: string, k: number) => addDays(d, mr.int(-k, k));
  const promos: Array<[number, string, number, string, string, number[], string | null, number | null]> = [
    [1, 'NEWYEAR15', 15, '2026-01-02', '2026-01-18', beans, null, null],
    [2, 'SPRING10', 10, sh('2026-04-01', 0), sh('2026-04-28', 2), beans, null, null],
    [3, 'APPESPRESSO20', mr.pick([15, 20]), sh('2026-04-10', 3), sh('2026-05-10', 3), [3], 'app', null],
    [4, 'KIT20', 20, '2026-05-01', sh('2026-05-31', 0), kit, null, mr.pick([30, 40, 50])],
    [5, 'MAYDAY25', 25, '2026-05-01', '2026-05-03', [...beans, ...kit, ...gifts], null, mr.pick([25, 30, 35])],
    [6, 'SUMMER10', 10, '2026-06-01', '2026-06-30', [...beans, ...gifts], null, null],
    [7, 'APPBEANS10', 10, sh('2026-06-12', 2), sh('2026-06-22', 2), beans, 'app', null],
    [8, 'FATHERS20', 20, '2026-06-15', '2026-06-21', [...kit, 11], 'web', mr.pick([40, 60])],
  ];
  const promotions: Cell[][] = promos.map(([id, code, pct, s, e, , ch, min]) => [id, code, pct, ch, min, s, e]);
  const promoProducts: Cell[][] = promos.flatMap(([id, , , , , ps]) => ps.map((p) => [id, p]));
  // Extra planted orders on promotion boundary days (late UTC evening = next UK day).
  {
    const r = mr.fork('edges');
    for (const [, , , s, e] of promos.slice(1)) {
      addOrder(pick(r), fromMin(toMin(`${addDays(s, -1)} 23:00:00`) + r.int(5, 55), r.int(0, 59)), r, 'paid');
      addOrder(pick(r), fromMin(toMin(`${e} 23:00:00`) + r.int(5, 55), r.int(0, 59)), r, 'paid');
    }
  }

  // Top-2 ties: in some copies, top up the third product of a category until it ties with the second in Q2.
  {
    const r = rng.fork('ties');
    const q2 = (t: string) => {
      const l = ukLocal(t);
      return l >= '2026-04-01' && l < '2026-07-01';
    };
    const paid = new Map(orders.filter((o) => o[4] === 'paid' && q2(String(o[2]))).map((o) => [Number(o[0]), o]));
    const units = new Map<number, number>();
    for (const l of lines) if (paid.has(Number(l[0]))) units.set(Number(l[2]), (units.get(Number(l[2])) ?? 0) + Number(l[3]));
    for (const cat of [beans, kit, gifts]) {
      const mode = r.pick(['none', 'second', 'second', 'first']);
      if (mode === 'none') continue;
      const ranked = cat.map((p) => [p, units.get(p) ?? 0] as [number, number]).sort((a, b) => b[1] - a[1] || a[0] - b[0]);
      // 'second': the third product catches up with the second; 'first': the second catches up with the first.
      const [hi, lo] = mode === 'second' ? [ranked[1], ranked[2]] : [ranked[0], ranked[1]];
      if (!hi || !lo || hi[1] - lo[1] > 8 || hi[1] === lo[1]) continue;
      addOrder(pick(r), ts(r, '2026-04-05', '2026-06-25', [9, 20]), r, 'paid', [[lo[0], hi[1] - lo[1]]]);
    }
  }
  orders.sort((a, b) => Number(a[0]) - Number(b[0]));

  // ── events ──
  const er = rng.fork('events');
  const events: Cell[][] = [];
  let eid = 5_000_000 + er.int(0, 50000);
  const push = (cust: number, name: string, t: string, skewSec = er.chance(0.05) ? er.int(300, 5400) : 0, dup = false) => {
    // A phone whose clock runs fast stamps the event later than it really happened (and later than it was stored).
    const stamped = plusSec(t, skewSec);
    events.push([eid++, cust, name, stamped, plusSec(t, er.int(2, skewSec ? 120 : 900))]);
    if (dup || er.chance(0.08)) events.push([eid++ + er.int(0, 3), cust, name, stamped, plusSec(t, er.int(dup ? 7200 : 1000, 90000))]);
  };
  const appUsers = er.shuffle(customers.map((c) => Number(c[0]))).slice(0, 22 + er.int(0, 18));
  for (const cust of appUsers) {
    const nSessions = er.int(1, 9);
    for (let s = 0; s < nSessions; s++) {
      let t = ts(er, '2026-03-27', '2026-05-04', [6, 24]);
      const n = er.int(1, 7);
      for (let k = 0; k < n; k++) {
        push(cust, k === 0 ? 'app_open' : er.pick(['view_product', 'view_product', 'add_to_basket', 'checkout']), t);
        t = plusSec(t, er.pick([40, 95, 180, 300, 420, 600, 900, 1500, 1740]) + er.int(0, 50));
      }
    }
  }
  {
    // Planted: gaps of exactly 30 minutes (same session) and 30 minutes 1 second (new session) in the week of
    // 6–12 April; events either side of the week's edges and of the April boundary in UTC vs UK time.
    const r = er.fork('planted');
    for (let k = 0; k < 3; k++) {
      const cust = r.pick(appUsers);
      const t0 = ts(r, '2026-04-06', '2026-04-12', [7, 20]);
      push(cust, 'app_open', t0);
      push(cust, 'view_product', plusSec(t0, 1800));
      push(cust, 'add_to_basket', plusSec(t0, 3601));
    }
    // Fast phone clocks in the week: a stamp that looks like a new session (or a later day) but is not.
    for (let k = 0; k < 4; k++) {
      const cust = r.pick(appUsers);
      const t0 = ts(r, '2026-04-06', '2026-04-12', [8, 20]);
      push(cust, 'app_open', t0, 0);
      push(cust, 'view_product', plusSec(t0, 600), 0);
      push(cust, k % 2 ? 'checkout' : 'add_to_basket', plusSec(t0, 1200), r.int(2400, 4800), true);
      push(cust, 'view_product', plusSec(t0, 1500), 0);
    }
    {
      const cust = r.pick(appUsers);
      push(cust, 'app_open', '2026-04-12 22:40:00', 0);
      push(cust, 'checkout', '2026-04-12 22:52:00', 1800);
    }
    for (const edge of ['2026-04-05 22:40:00', '2026-04-12 22:50:00', '2026-03-31 23:20:00', '2026-04-30 23:25:00']) {
      const cust = r.pick(appUsers);
      const t0 = plusSec(edge, r.int(0, 600));
      push(cust, 'app_open', t0);
      push(cust, 'view_product', plusSec(t0, r.int(300, 1200)));
    }
  }
  events.sort((a, b) => Number(a[0]) - Number(b[0]));
  // Re-number duplicates that collided (ids must stay unique).
  const seen = new Set<number>();
  for (const e of events) {
    let id = Number(e[0]);
    while (seen.has(id)) id++;
    seen.add(id);
    e[0] = id;
  }
  events.sort((a, b) => Number(a[0]) - Number(b[0]));

  // ── subscription snapshots ──
  const sr = rng.fork('subs');
  const snaps: Cell[][] = [];
  const subscribers = sr.shuffle(customers.map((c) => Number(c[0]))).slice(0, 14 + sr.int(0, 6));
  subscribers.forEach((cust, idx) => {
    let day = addDays('2026-02-15', sr.int(0, 30));
    const statuses: string[] = [];
    const total = daysBetween(day, '2026-07-01');
    while (statuses.length < total) {
      const runActive = sr.int(3, 30);
      for (let k = 0; k < runActive; k++) statuses.push('active');
      if (sr.chance(0.75)) {
        const len = idx === 0 ? 7 : idx === 1 ? 6 : sr.pick([2, 3, 5, 6, 7, 8, 10, 14, 21]);
        for (let k = 0; k < len; k++) statuses.push('paused');
        if (sr.chance(0.3)) {
          statuses.push('active'); // one active day between two pauses
          for (let k = 0; k < sr.int(3, 9); k++) statuses.push('paused');
        }
      }
    }
    // Planted pauses around the lost day (14 April): one that only reaches 7 days through it, one that starts on
    // the 15th after an active 13th, one that ends on the 13th.
    const at = (d: string) => daysBetween(day, d);
    const setRun = (from: string, to: string, st: string) => {
      for (let k = at(from); k <= at(to); k++) if (k >= 0 && k < statuses.length) statuses[k] = st;
    };
    if (idx === 2) { setRun('2026-04-08', '2026-04-08', 'active'); setRun('2026-04-09', '2026-04-17', 'active'); setRun('2026-04-11', '2026-04-17', 'paused'); setRun('2026-04-18', '2026-04-19', 'active'); }
    if (idx === 3) { setRun('2026-04-10', '2026-04-13', 'active'); setRun('2026-04-15', '2026-04-21', 'paused'); setRun('2026-04-22', '2026-04-23', 'active'); }
    if (idx === 4) { setRun('2026-04-06', '2026-04-13', 'paused'); setRun('2026-04-05', '2026-04-05', 'active'); setRun('2026-04-15', '2026-04-16', 'active'); }
    const cancelAt = idx <= 4 ? total : sr.chance(0.25) ? sr.int(40, total - 1) : total;
    for (let k = 0; k < Math.min(total, cancelAt + 1); k++) {
      snaps.push([cust, day, k === cancelAt ? 'cancelled' : statuses[k]!]);
      day = addDays(day, 1);
    }
  });
  snaps.sort((a, b) => Number(a[0]) - Number(b[0]) || (String(a[1]) < String(b[1]) ? -1 : 1));
  // The pipeline lost 14 April for everyone.
  for (let i = snaps.length - 1; i >= 0; i--) if (snaps[i]![1] === '2026-04-14') snaps.splice(i, 1);

  // ── wallet ──
  const wr = rng.fork('wallet');
  const ledger: Cell[][] = [];
  let entry = 900 + wr.int(0, 100);
  const holders = wr.shuffle(customers.map((c) => Number(c[0]))).slice(0, 16 + wr.int(0, 6));
  for (const cust of holders) {
    let t = ts(wr, '2026-01-05', '2026-03-01', [7, 21]);
    const mine: Cell[][] = [];
    const add = (amount: number, type: string, at: string, rev: number | null = null) => {
      const row: Cell[] = [entry++, cust, at, amount, type, rev];
      mine.push(row);
      return row;
    };
    add(wr.pick([1000, 2000, 2500, 5000]), 'topup', t);
    const n = wr.int(8, 18);
    for (let k = 0; k < n; k++) {
      t = plusSec(t, wr.int(1, 9) * 86400 + wr.int(0, 30000));
      const kind = wr.pick(['purchase', 'purchase', 'purchase', 'topup', 'refund', 'auto']);
      if (kind === 'purchase') add(-wr.pick([350, 420, 875, 950, 1125, 1800, 2700]), 'purchase', t);
      else if (kind === 'topup') add(wr.pick([1000, 2000, 2500]), 'topup', t);
      else if (kind === 'refund') add(wr.pick([350, 420, 875]), 'refund', t);
      else {
        // An auto top-up posted in the same second as the purchase that triggered it (purchase first).
        add(-wr.pick([1800, 2700, 3200]), 'purchase', t);
        add(2000, 'topup', t);
      }
      if (wr.chance(0.2) && mine.length > 1) {
        const options = mine.filter((m) => m[4] !== 'reversal' && String(m[2]) <= t && !mine.some((x) => x[5] === m[0]));
        const target = options.length ? wr.pick(options) : null;
        if (target) {
          let rev = add(-Number(target[3]), 'reversal', plusSec(t, wr.int(3600, 5 * 86400)), Number(target[0]));
          // The chargeback is sometimes undone (a reversal of the reversal), and rarely undone again.
          for (let depth = 0; depth < 2 && wr.chance(depth === 0 ? 0.45 : 0.35); depth++) rev = add(-Number(rev[3]), 'reversal', plusSec(String(rev[2]), wr.int(3600, 3 * 86400)), Number(rev[0]));
        }
      }
      if (t > '2026-03-29 01:00:00' && wr.chance(0.25)) {
        // A late-evening purchase: after 23:00 UTC it belongs to the next UK day.
        add(-wr.pick([420, 875, 1125]), 'purchase', `${t.slice(0, 10)} 23:${String(wr.int(5, 55)).padStart(2, '0')}:${String(wr.int(0, 59)).padStart(2, '0')}`);
      }
    }
    if (wr.chance(0.5)) {
      // A big late-evening purchase in British Summer Time (the next UK day), topped up the next morning.
      const d = addDays('2026-04-02', wr.int(0, 50));
      add(-wr.pick([3800, 4500, 5200]), 'purchase', `${d} 23:${String(wr.int(10, 50)).padStart(2, '0')}:${String(wr.int(0, 59)).padStart(2, '0')}`);
      add(5000, 'topup', `${addDays(d, 1)} 08:${String(wr.int(0, 50)).padStart(2, '0')}:00`);
    }
    mine.sort((a, b) => (String(a[2]) < String(b[2]) ? -1 : String(a[2]) > String(b[2]) ? 1 : Number(a[0]) - Number(b[0])));
    ledger.push(...mine);
  }
  ledger.sort((a, b) => Number(a[0]) - Number(b[0]));

  return {
    tables: {
      dim_customer: { columns: ['customer_id', 'full_name', 'country', 'created_at_utc'], rows: customers },
      dim_product: { columns: ['product_id', 'sku', 'name', 'category'], rows: PRODUCTS.map(([sku, n, c], i) => [i + 1, sku, n, moves.get(i + 1)?.to ?? c]) },
      dim_product_category_history: { columns: ['product_id', 'category', 'valid_from_utc', 'valid_to_utc'], rows: catHist },
      dim_price_history: { columns: ['product_id', 'price_gbp', 'valid_from_utc', 'valid_to_utc'], rows: prices },
      fact_orders: { columns: ['order_id', 'customer_id', 'ordered_at_utc', 'channel', 'status'], rows: orders },
      fact_order_lines: { columns: ['order_id', 'line_no', 'product_id', 'quantity'], rows: lines.sort((a, b) => Number(a[0]) - Number(b[0]) || Number(a[1]) - Number(b[1])) },
      promotions: { columns: ['promo_id', 'code', 'pct_off', 'channel', 'min_basket_gbp', 'starts_on', 'ends_on'], rows: promotions },
      promotion_products: { columns: ['promo_id', 'product_id'], rows: promoProducts },
      events_raw: { columns: ['event_id', 'customer_id', 'event_name', 'event_ts_utc', 'ingested_at_utc'], rows: events },
      subscription_status_daily: { columns: ['customer_id', 'snapshot_date', 'status'], rows: snaps },
      wallet_ledger: { columns: ['entry_id', 'customer_id', 'posted_at_utc', 'amount_pence', 'entry_type', 'reverses_entry_id'], rows: ledger },
    },
  };
}

// ─────────────────────────────── Independent solver helpers ───────────────────────────────

interface Line {
  order: number;
  cust: number;
  local: string;
  utc: string;
  product: number;
  qty: number;
  price: number;
  category: string;
}

function paidLines(t: Tables): Line[] {
  const prices = t.dim_price_history!.rows.map((r) => ({ p: Number(r[0]), price: Number(r[1]), from: String(r[2]), to: r[3] === null ? null : String(r[3]) }));
  const orders = new Map(t.fact_orders!.rows.filter((o) => o[4] === 'paid').map((o) => [Number(o[0]), o]));
  const cats = t.dim_product_category_history!.rows.map((r) => ({ p: Number(r[0]), cat: String(r[1]), from: String(r[2]), to: r[3] === null ? null : String(r[3]) }));
  const out: Line[] = [];
  for (const [o, , p, q] of t.fact_order_lines!.rows) {
    const ord = orders.get(Number(o));
    if (!ord) continue;
    const utc = String(ord[2]);
    const hits = prices.filter((x) => x.p === Number(p) && x.from <= utc && (x.to === null || utc < x.to));
    if (hits.length !== 1) throw new Error(`price lookup for product ${p} at ${utc}: ${hits.length} rows`);
    const cat = cats.filter((x) => x.p === Number(p) && x.from <= utc && (x.to === null || utc < x.to));
    if (cat.length !== 1) throw new Error(`category lookup for product ${p} at ${utc}: ${cat.length} rows`);
    out.push({ order: Number(o), cust: Number(ord[1]), local: ukLocal(utc), utc, product: Number(p), qty: Number(q), price: hits[0]!.price, category: cat[0]!.cat });
  }
  return out;
}
const r1 = (x: number) => Math.round(x * 10) / 10;
const r2 = (x: number) => Math.round(x * 100) / 100;
const nextMonth = (m: string) => {
  const [y, mo] = m.split('-').map(Number);
  return mo === 12 ? `${y! + 1}-01` : `${y}-${String(mo! + 1).padStart(2, '0')}`;
};
const productName = (t: Tables) => new Map(t.dim_product!.rows.map((r) => [Number(r[0]), String(r[2])]));
function dedupedEvents(t: Tables): Array<{ cust: number; name: string; utc: string }> {
  const seen = new Set<string>();
  const out: Array<{ cust: number; name: string; utc: string }> = [];
  for (const [, c, n, u] of t.events_raw!.rows) {
    const k = `${c}|${n}|${u}`;
    if (seen.has(k)) continue;
    seen.add(k);
    out.push({ cust: Number(c), name: String(n), utc: String(u) });
  }
  return out;
}

// ─────────────────────────────── Questions ───────────────────────────────

const PAID_LINES = `paid_lines AS (
  SELECT o.order_id, o.customer_id, o.ordered_at_utc,
         ${LOCAL('o.ordered_at_utc')} AS ordered_at_local,
         l.line_no, l.product_id, l.quantity, h.price_gbp, ch.category AS category
  FROM fact_orders o
  JOIN fact_order_lines l ON l.order_id = o.order_id
  JOIN dim_price_history h ON h.product_id = l.product_id
   AND h.valid_from_utc <= o.ordered_at_utc
   AND (h.valid_to_utc IS NULL OR o.ordered_at_utc < h.valid_to_utc)
  JOIN dim_product_category_history ch ON ch.product_id = l.product_id
   AND ch.valid_from_utc <= o.ordered_at_utc
   AND (ch.valid_to_utc IS NULL OR o.ordered_at_utc < ch.valid_to_utc)
  WHERE o.status = 'paid'
)`;

export const QUESTIONS: Question[] = [
  {
    id: 'h01',
    difficulty: 'easy',
    text: `From Yara (operations): "How many orders did we take each day over the clock-change week, and what did they bring in? Use UK days."

For each UK local date from 2026-03-27 to 2026-03-31 inclusive: the date as text 'YYYY-MM-DD', the number of paid orders placed on that UK date, and their revenue (rule 4) in GBP rounded to 2 decimal places (±0.01). Return five rows in date order, with three columns.`,
    ordered: true,
    reference: `WITH ${PAID_LINES}
SELECT date(ordered_at_local) AS uk_date, COUNT(DISTINCT order_id) AS orders, SUM(quantity * price_gbp) AS revenue_gbp
FROM paid_lines
WHERE ordered_at_local >= '2026-03-27' AND ordered_at_local < '2026-04-01'
GROUP BY date(ordered_at_local)
ORDER BY uk_date`,
    solve: (t) => {
      const by = new Map<string, [Set<number>, number]>();
      for (const l of paidLines(t)) {
        const d = l.local.slice(0, 10);
        if (d < '2026-03-27' || d > '2026-03-31') continue;
        const a = by.get(d) ?? [new Set<number>(), 0];
        a[0].add(l.order);
        a[1] += l.qty * l.price;
        by.set(d, a);
      }
      return [...by.entries()].sort().map(([d, [o, v]]) => [d, o.size, r2(v)]);
    },
    careless: [
      { why: 'groups by the UTC date', sql: `WITH x AS (SELECT o.order_id, o.ordered_at_utc, l.quantity * h.price_gbp AS v FROM fact_orders o JOIN fact_order_lines l ON l.order_id = o.order_id JOIN dim_price_history h ON h.product_id = l.product_id AND h.valid_from_utc <= o.ordered_at_utc AND (h.valid_to_utc IS NULL OR o.ordered_at_utc < h.valid_to_utc) WHERE o.status = 'paid') SELECT date(ordered_at_utc), COUNT(DISTINCT order_id), ROUND(SUM(v), 2) FROM x WHERE ordered_at_utc >= '2026-03-27' AND ordered_at_utc < '2026-04-01' GROUP BY 1 ORDER BY 1` },
      { why: 'adds an hour on every day, before the clocks went forward too', sql: `WITH x AS (SELECT o.order_id, datetime(o.ordered_at_utc, '+1 hour') AS lt, l.quantity * h.price_gbp AS v FROM fact_orders o JOIN fact_order_lines l ON l.order_id = o.order_id JOIN dim_price_history h ON h.product_id = l.product_id AND h.valid_from_utc <= o.ordered_at_utc AND (h.valid_to_utc IS NULL OR o.ordered_at_utc < h.valid_to_utc) WHERE o.status = 'paid') SELECT date(lt), COUNT(DISTINCT order_id), ROUND(SUM(v), 2) FROM x WHERE lt >= '2026-03-27' AND lt < '2026-04-01' GROUP BY 1 ORDER BY 1` },
      { why: 'counts order lines instead of orders', sql: `WITH x AS (SELECT o.order_id, ${LOCAL('o.ordered_at_utc')} AS lt, l.quantity * h.price_gbp AS v FROM fact_orders o JOIN fact_order_lines l ON l.order_id = o.order_id JOIN dim_price_history h ON h.product_id = l.product_id AND h.valid_from_utc <= o.ordered_at_utc AND (h.valid_to_utc IS NULL OR o.ordered_at_utc < h.valid_to_utc) WHERE o.status = 'paid') SELECT date(lt), COUNT(*), ROUND(SUM(v), 2) FROM x WHERE lt >= '2026-03-27' AND lt < '2026-04-01' GROUP BY 1 ORDER BY 1` },
    ],
    notes: 'UTC to UK local with the 29 March 01:00 UTC switch, COUNT(DISTINCT) after the line join, price from the SCD2 row. Planted: paid orders between 23:00 and 24:00 UTC on each day (they move to the next UK day only after the change), just after midnight UTC, and at price-change moments (one of which is the clock change itself).',
  },
  {
    id: 'h02',
    difficulty: 'easy',
    text: `From Joon (product): "How many of each kind of app event did we get in April?"

Count the events that happened in April 2026 (UK local time, by event_ts_utc), each duplicate delivery counted once (rule 5). Return one row per event_name with two columns: the event_name and the number of events.`,
    ordered: false,
    reference: `WITH e AS (
  SELECT DISTINCT customer_id, event_name, event_ts_utc FROM events_raw
)
SELECT event_name, COUNT(*) AS events
FROM e
WHERE ${LOCAL('event_ts_utc')} >= '2026-04-01' AND ${LOCAL('event_ts_utc')} < '2026-05-01'
GROUP BY event_name`,
    solve: (t) => {
      const by = new Map<string, number>();
      for (const e of dedupedEvents(t)) {
        const l = ukLocal(e.utc);
        if (l >= '2026-04-01' && l < '2026-05-01') by.set(e.name, (by.get(e.name) ?? 0) + 1);
      }
      return [...by.entries()];
    },
    careless: [
      { why: 'counts duplicate deliveries', sql: `SELECT event_name, COUNT(*) FROM events_raw WHERE ${LOCAL('event_ts_utc')} >= '2026-04-01' AND ${LOCAL('event_ts_utc')} < '2026-05-01' GROUP BY event_name` },
      { why: 'deduplicates by event_id, which differs between duplicates', sql: `SELECT event_name, COUNT(DISTINCT event_id) FROM events_raw WHERE ${LOCAL('event_ts_utc')} >= '2026-04-01' AND ${LOCAL('event_ts_utc')} < '2026-05-01' GROUP BY event_name` },
      { why: 'April in UTC instead of UK time', sql: `SELECT event_name, COUNT(*) FROM (SELECT DISTINCT customer_id, event_name, event_ts_utc FROM events_raw) WHERE event_ts_utc >= '2026-04-01' AND event_ts_utc < '2026-05-01' GROUP BY event_name` },
    ],
    notes: 'DISTINCT on the natural key, then UK-local month. Planted: events late on 31 March UTC (April in the UK) and late on 30 April UTC (May in the UK); about 8% of events are delivered twice.',
  },
  {
    id: 'h03',
    difficulty: 'easy',
    text: `From Femi (finance): "What was revenue by category in March 2026, at the prices we were actually charging at the time?"

Revenue = the line revenue (rule 4) of paid orders placed in March 2026, UK local time, by the category the product had at the moment of the order (rule 4). Return one row per category with two columns: the category and its revenue in GBP rounded to 2 decimal places (compared to within ±0.01).`,
    ordered: false,
    reference: `WITH ${PAID_LINES}
SELECT pl.category, SUM(pl.quantity * pl.price_gbp) AS revenue_gbp
FROM paid_lines pl
WHERE pl.ordered_at_local >= '2026-03-01' AND pl.ordered_at_local < '2026-04-01'
GROUP BY pl.category`,
    solve: (t) => {
      const by = new Map<string, number>();
      for (const l of paidLines(t)) if (l.local >= '2026-03-01' && l.local < '2026-04-01') by.set(l.category, (by.get(l.category) ?? 0) + l.qty * l.price);
      return [...by.entries()].map(([c, v]) => [c, r2(v)]);
    },
    careless: [],
    notes: 'Range joins to the SCD2 price and category rows with half-open intervals. Planted: orders at the exact moment a price changes, late on 31 March UTC (April in the UK); products that later move category (today’s dim_product.category is not March’s).',
  },
  {
    id: 'h04',
    difficulty: 'medium',
    text: `From Ewan (buying): "Top two products in each category by units sold in Q2 2026 - and if there's a tie, show me all of them."

Units = the total quantity on paid orders placed from 1 April to 30 June 2026 (UK local time). Each unit counts in the category the product had at the moment of its order (rule 4): a product that moved category during the quarter counts in both, with the units sold before and after the move. Within each category, rank products by units with the highest first; tied products share a rank and the next rank is skipped (1, 2, 2, 4 or 1, 1, 3). Keep every product with rank 1 or 2. Return one row per kept category and product with four columns: the category, the product name, its units in that category and its rank.`,
    ordered: false,
    reference: `WITH ${PAID_LINES},
units AS (
  SELECT pl.category, p.name, SUM(pl.quantity) AS units
  FROM paid_lines pl JOIN dim_product p ON p.product_id = pl.product_id
  WHERE pl.ordered_at_local >= '2026-04-01' AND pl.ordered_at_local < '2026-07-01'
  GROUP BY pl.category, p.product_id, p.name
),
ranked AS (
  SELECT category, name, units, RANK() OVER (PARTITION BY category ORDER BY units DESC) AS rnk FROM units
)
SELECT category, name, units, rnk FROM ranked WHERE rnk <= 2`,
    solve: (t) => {
      const nm = productName(t);
      const u = new Map<string, number>();
      for (const l of paidLines(t)) if (l.local >= '2026-04-01' && l.local < '2026-07-01') u.set(`${l.category}|${l.product}`, (u.get(`${l.category}|${l.product}`) ?? 0) + l.qty);
      const out: Cell[][] = [];
      for (const [k, n] of u) {
        const [cat, p] = k.split('|');
        const rank = 1 + [...u.entries()].filter(([q, m]) => q.split('|')[0] === cat && m > n).length;
        if (rank <= 2) out.push([cat!, nm.get(Number(p))!, n, rank]);
      }
      return out;
    },
    careless: [],
    notes: 'Category from the SCD2 history at the order moment, RANK() per category, keep rank <= 2. Planted: two or three products move category mid-quarter (with orders either side of the move), and in about half the categories the third product is topped up to tie with the second (or the second with the first).',
  },
  {
    id: 'h05',
    difficulty: 'medium',
    text: `From Anjali (growth): "Cohort retention for our first-time buyers, January to April 2026 - one and two months on."

A customer's cohort is the UK local month of their first paid order ever (the data starts in December 2025). For the cohorts 2026-01, 2026-02, 2026-03 and 2026-04 give: the cohort month as 'YYYY-MM'; the number of customers in it; M1 = how many of them placed at least one paid order in the calendar month after the cohort month; M2 = how many placed at least one paid order in the second calendar month after it (whether or not they ordered in the first); and M1 and M2 as percentages of the cohort. All months are UK local. Return four rows in cohort order with six columns: cohort month, customers, M1, M2, M1 %, M2 % (percentages compared to within ±0.06).`,
    ordered: true,
    tolerance: 0.06,
    reference: `WITH paid AS (
  SELECT DISTINCT customer_id, strftime('%Y-%m', ${LOCAL('ordered_at_utc')}) AS month
  FROM fact_orders WHERE status = 'paid'
),
cohort AS (
  SELECT customer_id, MIN(month) AS cohort_month FROM paid GROUP BY customer_id
),
flags AS (
  SELECT c.customer_id, c.cohort_month,
         EXISTS (SELECT 1 FROM paid p WHERE p.customer_id = c.customer_id
                   AND p.month = strftime('%Y-%m', date(c.cohort_month || '-01', '+1 month'))) AS m1,
         EXISTS (SELECT 1 FROM paid p WHERE p.customer_id = c.customer_id
                   AND p.month = strftime('%Y-%m', date(c.cohort_month || '-01', '+2 months'))) AS m2
  FROM cohort c
)
SELECT cohort_month, COUNT(*) AS customers, SUM(m1) AS m1, SUM(m2) AS m2,
       100.0 * SUM(m1) / COUNT(*) AS m1_pct, 100.0 * SUM(m2) / COUNT(*) AS m2_pct
FROM flags
WHERE cohort_month BETWEEN '2026-01' AND '2026-04'
GROUP BY cohort_month
ORDER BY cohort_month`,
    solve: (t) => {
      const months = new Map<number, Set<string>>();
      for (const o of t.fact_orders!.rows) if (o[4] === 'paid') {
        const m = ukLocal(String(o[2])).slice(0, 7);
        months.set(Number(o[1]), (months.get(Number(o[1])) ?? new Set()).add(m));
      }
      return ['2026-01', '2026-02', '2026-03', '2026-04'].map((cm) => {
        const members = [...months.values()].filter((s) => [...s].sort()[0] === cm);
        const m1 = members.filter((s) => s.has(nextMonth(cm))).length;
        const m2 = members.filter((s) => s.has(nextMonth(nextMonth(cm)))).length;
        return [cm, members.length, m1, m2, r1((100 * m1) / members.length), r1((100 * m2) / members.length)];
      });
    },
    careless: [],
    notes: 'First paid order month per customer (UK local), then exact next-month and month-after-next matches. Planted: first orders at 23:xx UTC on 31 March (an April cohort in the UK) and on 30 April (May in the UK).',
  },
  {
    id: 'h06',
    difficulty: 'medium',
    text: `From Rowan (subscriptions): "List every subscription pause that lasted a week or more."

A pause is a run of consecutive calendar days on which a customer's status is 'paused' (any other status, or a day with no row, ends the run). Apply rule 7 for the lost day, 2026-04-14, before looking for runs. Keep the pauses of at least 7 days. Return one row per such pause with four columns: customer_id, the first paused day, the last paused day (both 'YYYY-MM-DD') and the number of days in the pause.`,
    ordered: false,
    reference: `WITH filled AS (
  SELECT customer_id, snapshot_date, status FROM subscription_status_daily
  UNION ALL
  SELECT d.customer_id, '2026-04-14', d.status
  FROM subscription_status_daily d
  WHERE d.snapshot_date = '2026-04-13'
    AND EXISTS (SELECT 1 FROM subscription_status_daily e WHERE e.customer_id = d.customer_id AND e.snapshot_date = '2026-04-15')
),
paused AS (
  SELECT customer_id, snapshot_date,
         julianday(snapshot_date) - ROW_NUMBER() OVER (PARTITION BY customer_id ORDER BY snapshot_date) AS grp
  FROM filled
  WHERE status = 'paused'
)
SELECT customer_id, MIN(snapshot_date) AS first_day, MAX(snapshot_date) AS last_day, COUNT(*) AS days
FROM paused
GROUP BY customer_id, grp
HAVING COUNT(*) >= 7`,
    solve: (t) => {
      const status = new Map<string, string>();
      for (const [c, d, st] of t.subscription_status_daily!.rows) status.set(`${c}|${d}`, String(st));
      for (const c of new Set(t.subscription_status_daily!.rows.map((r) => Number(r[0])))) {
        const before = status.get(`${c}|2026-04-13`);
        if (before !== undefined && status.has(`${c}|2026-04-15`)) status.set(`${c}|2026-04-14`, before);
      }
      const rows = [...status.entries()].filter(([, st]) => st === 'paused').map(([k]) => k.split('|')).map(([c, d]) => [Number(c), d!] as [number, string]);
      rows.sort((a, b) => a[0] - b[0] || (a[1] < b[1] ? -1 : 1));
      const out: Cell[][] = [];
      let run: [number, string, string, number] | null = null;
      const flush = () => {
        if (run && run[3] >= 7) out.push([run[0], run[1], run[2], run[3]]);
      };
      for (const [c, d] of rows) {
        if (run && run[0] === c && addDays(run[2], 1) === d) {
          run[2] = d;
          run[3]++;
        } else {
          flush();
          run = [c, d, d, 1];
        }
      }
      flush();
      return out;
    },
    careless: [],
    notes: 'Fill the lost day from the day before, then gaps and islands (date minus row number). Planted: a pause that only reaches 7 days through the lost day, one that starts on the 15th after an active 13th, one that ends on the 13th; pauses of exactly 7 and 6 days; two pauses one active day apart.',
  },
  {
    id: 'h07',
    difficulty: 'hard',
    text: `From Kofi (finance): "For each wallet, what was the lowest balance it ever reached, when, and what was its lowest end-of-day balance?"

Work out which entries count under rule 6 (follow every reversal chain to the end). Apply each customer's counting entries in order (posted_at_utc, then entry_id). The balance after an entry is the sum of all counting entries up to and including it. For every customer with at least one counting entry give:
- the lowest balance after any counting entry, and the posted_at_utc (as stored) of the entry after which it was first reached;
- the lowest end-of-day balance: for each UK local date on which the customer had at least one counting entry, the balance after the last counting entry of that UK date; the lowest of those.
Return one row per customer with four columns: customer_id, the lowest balance in pounds (amount_pence / 100, ±0.01), the posted_at_utc, and the lowest end-of-day balance in pounds (±0.01).`,
    ordered: false,
    reference: `WITH RECURSIVE chain(entry_id, counts) AS (
  SELECT w.entry_id, 1 FROM wallet_ledger w
  WHERE w.entry_type = 'reversal'
    AND NOT EXISTS (SELECT 1 FROM wallet_ledger x WHERE x.reverses_entry_id = w.entry_id)
  UNION ALL
  SELECT w.reverses_entry_id, 1 - c.counts
  FROM chain c JOIN wallet_ledger w ON w.entry_id = c.entry_id
  WHERE w.reverses_entry_id IS NOT NULL
),
applied AS (
  SELECT w.* FROM wallet_ledger w
  WHERE w.entry_type <> 'reversal'
    AND COALESCE((SELECT c.counts FROM chain c WHERE c.entry_id = w.entry_id), 1) = 1
),
balances AS (
  SELECT customer_id, entry_id, posted_at_utc,
         date(${LOCAL('posted_at_utc')}) AS uk_day,
         SUM(amount_pence) OVER (PARTITION BY customer_id ORDER BY posted_at_utc, entry_id
                                 ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW) AS balance,
         ROW_NUMBER() OVER (PARTITION BY customer_id, date(${LOCAL('posted_at_utc')}) ORDER BY posted_at_utc DESC, entry_id DESC) AS last_of_day
  FROM applied
),
lowest AS (
  SELECT *, ROW_NUMBER() OVER (PARTITION BY customer_id ORDER BY balance, posted_at_utc, entry_id) AS rn FROM balances
)
SELECT l.customer_id, l.balance / 100.0 AS lowest_gbp, l.posted_at_utc,
       (SELECT MIN(b.balance) FROM balances b WHERE b.customer_id = l.customer_id AND b.last_of_day = 1) / 100.0 AS lowest_end_of_day_gbp
FROM lowest l WHERE l.rn = 1`,
    solve: (t) => {
      const rows = t.wallet_ledger!.rows.map((r) => ({ id: Number(r[0]), cust: Number(r[1]), at: String(r[2]), amt: Number(r[3]), type: String(r[4]), rev: r[5] === null ? null : Number(r[5]) }));
      const byId = new Map(rows.map((r) => [r.id, r]));
      const reversedBy = new Map<number, number>();
      for (const r of rows) if (r.type === 'reversal' && r.rev !== null) reversedBy.set(r.rev, r.id);
      const memo = new Map<number, boolean>();
      const counts = (id: number): boolean => {
        if (memo.has(id)) return memo.get(id)!;
        const by = reversedBy.get(id);
        const v = by === undefined ? true : !counts(by);
        memo.set(id, v);
        return v;
      };
      const applied = rows.filter((r) => r.type !== 'reversal' && counts(r.id)).sort((a, b) => a.cust - b.cust || (a.at < b.at ? -1 : a.at > b.at ? 1 : a.id - b.id));
      void byId;
      const out = new Map<number, { low: number; at: string; eod: Map<string, number> }>();
      const bal = new Map<number, number>();
      for (const r of applied) {
        const b = (bal.get(r.cust) ?? 0) + r.amt;
        bal.set(r.cust, b);
        const o = out.get(r.cust) ?? { low: Infinity, at: '', eod: new Map<string, number>() };
        if (b < o.low) {
          o.low = b;
          o.at = r.at;
        }
        o.eod.set(ukLocal(r.at).slice(0, 10), b);
        out.set(r.cust, o);
      }
      return [...out.entries()].map(([c, o]) => [c, r2(o.low / 100), o.at, r2(Math.min(...o.eod.values()) / 100)]);
    },
    careless: [],
    notes: 'A recursive walk down each reversal chain decides which entries count (a reversed reversal reinstates its target), a ROWS-frame running sum gives the balances, the first minimum, and the last balance of each UK local day. Planted: reversals of reversals (some three deep), auto top-ups posted in the same second as the purchase that triggered them, purchases after 23:00 UTC in British Summer Time.',
  },
  {
    id: 'h08',
    difficulty: 'hard',
    text: `From Femi (finance): "How much discount did each promotion actually give away in Q2 2026?"

Only paid orders placed from 1 April to 30 June 2026 (UK local) count. An order line qualifies for a promotion when all of these hold:
(a) its product is in promotion_products for that promotion;
(b) the order's UK local date is between the promotion's starts_on and ends_on (inclusive);
(c) the promotion's channel is NULL or equals the order's channel;
(d) the promotion's min_basket_gbp is NULL, or the order's basket - the total line revenue (rule 4) of ALL the order's lines, before any promotion - is at least min_basket_gbp.
A line gets at most one promotion: the qualifying one with the highest pct_off; if several tie on pct_off, the one with the lowest promo_id. The discount on a line = its line revenue (rule 4) × pct_off / 100. Return one row per promotion that applied to at least one line, with three columns: the promotion code, the number of lines it applied to, and the total discount in GBP (±0.01).`,
    ordered: false,
    reference: `WITH ${PAID_LINES},
q2 AS (
  SELECT pl.*, date(pl.ordered_at_local) AS uk_day, o.channel,
         SUM(pl.quantity * pl.price_gbp) OVER (PARTITION BY pl.order_id) AS basket_gbp
  FROM paid_lines pl JOIN fact_orders o ON o.order_id = pl.order_id
  WHERE pl.ordered_at_local >= '2026-04-01' AND pl.ordered_at_local < '2026-07-01'
),
candidates AS (
  SELECT q2.order_id, q2.line_no, q2.quantity, q2.price_gbp, p.promo_id, p.code, p.pct_off,
         ROW_NUMBER() OVER (PARTITION BY q2.order_id, q2.line_no ORDER BY p.pct_off DESC, p.promo_id) AS pick
  FROM q2
  JOIN promotion_products pp ON pp.product_id = q2.product_id
  JOIN promotions p ON p.promo_id = pp.promo_id
   AND q2.uk_day BETWEEN p.starts_on AND p.ends_on
   AND (p.channel IS NULL OR p.channel = q2.channel)
   AND (p.min_basket_gbp IS NULL OR q2.basket_gbp >= p.min_basket_gbp)
)
SELECT code, COUNT(*) AS lines, SUM(quantity * price_gbp * pct_off / 100.0) AS discount_gbp
FROM candidates WHERE pick = 1
GROUP BY promo_id, code`,
    solve: (t) => {
      const promos = t.promotions!.rows.map((r) => ({ id: Number(r[0]), code: String(r[1]), pct: Number(r[2]), ch: r[3] === null ? null : String(r[3]), min: r[4] === null ? null : Number(r[4]), s: String(r[5]), e: String(r[6]) }));
      const covers = new Set(t.promotion_products!.rows.map((r) => `${r[0]}|${r[1]}`));
      const channel = new Map(t.fact_orders!.rows.map((o) => [Number(o[0]), String(o[3])]));
      const lines = paidLines(t).filter((l) => l.local >= '2026-04-01' && l.local < '2026-07-01');
      const basket = new Map<number, number>();
      for (const l of lines) basket.set(l.order, (basket.get(l.order) ?? 0) + l.qty * l.price);
      const by = new Map<string, [number, number]>();
      for (const l of lines) {
        const day = l.local.slice(0, 10);
        const ok = promos
          .filter((p) => covers.has(`${p.id}|${l.product}`) && day >= p.s && day <= p.e && (p.ch === null || p.ch === channel.get(l.order)) && (p.min === null || basket.get(l.order)! >= p.min))
          .sort((a, b) => b.pct - a.pct || a.id - b.id);
        if (!ok.length) continue;
        const w = ok[0]!;
        const a = by.get(w.code) ?? [0, 0];
        a[0]++;
        a[1] += (l.qty * l.price * w.pct) / 100;
        by.set(w.code, a);
      }
      return [...by.entries()].map(([c, [n, d]]) => [c, n, r2(d)]);
    },
    careless: [],
    notes: 'Many-to-many join with four conditions (product, UK date window, channel, basket total from a window over all the order’s lines at historical prices), one winner per line by ROW_NUMBER. Planted: overlapping promotions (MAYDAY25 over KIT20, APPESPRESSO20 over SPRING10 on the app only, SUMMER10 tied with APPBEANS10), baskets either side of the minimums, orders late in the UTC evening on promotion edges.',
  },
  {
    id: 'h09',
    difficulty: 'hard',
    text: `From Joon (product): "How many app sessions did each customer have in the week of Monday 6 April 2026, how many ended up with a checkout, and how long was their longest?"

Prepare the events in this order:
1. Duplicates (rule 5): rows with the same customer_id, event_name and event_ts_utc are one event; its ingested_at_utc is the EARLIEST of theirs.
2. Event time: some phones' clocks run fast. An event's time is its event_ts_utc, except when that is later than its ingested_at_utc (from step 1) - then its time is that ingested_at_utc.
3. The week: keep the events whose time (step 2) falls from Monday 6 April 2026 00:00 to Sunday 12 April 2026 23:59:59, UK local time.
Sessions: a customer's events of the week, in order of time, form sessions; an event starts a new session when its time is more than 30 minutes after that customer's previous event (exactly 30 minutes later is still the same session). A session's length is the time from its first to its last event, in minutes (a single-event session lasts 0). A session "has a checkout" when at least one of its events is a 'checkout'. Return one row per customer with events in the week, with four columns: customer_id, the number of sessions, the number of sessions with a checkout, and the length of the longest session in minutes rounded to 1 decimal place (±0.06).`,
    ordered: false,
    tolerance: 0.06,
    reference: `WITH e AS (
  SELECT customer_id, event_name, event_ts_utc, MIN(ingested_at_utc) AS ingested_at_utc
  FROM events_raw GROUP BY customer_id, event_name, event_ts_utc
),
timed AS (
  SELECT customer_id, event_name,
         CASE WHEN event_ts_utc > ingested_at_utc THEN ingested_at_utc ELSE event_ts_utc END AS at_utc
  FROM e
),
wk AS (
  SELECT customer_id, event_name, CAST(strftime('%s', at_utc) AS INTEGER) AS t
  FROM timed
  WHERE ${LOCAL('at_utc')} >= '2026-04-06' AND ${LOCAL('at_utc')} < '2026-04-13'
),
marked AS (
  SELECT customer_id, event_name, t,
         CASE WHEN t - LAG(t) OVER (PARTITION BY customer_id ORDER BY t) <= 1800 THEN 0 ELSE 1 END AS new_session
  FROM wk
),
numbered AS (
  SELECT customer_id, event_name, t,
         SUM(new_session) OVER (PARTITION BY customer_id ORDER BY t ROWS UNBOUNDED PRECEDING) AS session_no
  FROM marked
),
sessions AS (
  SELECT customer_id, session_no, (MAX(t) - MIN(t)) / 60.0 AS minutes, MAX(event_name = 'checkout') AS has_checkout
  FROM numbered GROUP BY customer_id, session_no
)
SELECT customer_id, COUNT(*) AS sessions, SUM(has_checkout) AS sessions_with_checkout, MAX(minutes) AS longest_minutes
FROM sessions GROUP BY customer_id`,
    solve: (t) => {
      const ev = new Map<string, { cust: number; name: string; ts: string; ing: string }>();
      for (const [, c, n, u, ing] of t.events_raw!.rows) {
        const k = `${c}|${n}|${u}`;
        const cur = ev.get(k);
        if (!cur || String(ing) < cur.ing) ev.set(k, { cust: Number(c), name: String(n), ts: String(u), ing: String(ing) });
      }
      const by = new Map<number, Array<{ t: number; name: string }>>();
      for (const e of ev.values()) {
        const at = e.ts > e.ing ? e.ing : e.ts;
        const l = ukLocal(at);
        if (l >= '2026-04-06' && l < '2026-04-13') by.set(e.cust, [...(by.get(e.cust) ?? []), { t: secs(at), name: e.name }]);
      }
      const out: Cell[][] = [];
      for (const [c, list] of by) {
        list.sort((a, b) => a.t - b.t);
        const sessions: Array<{ start: number; end: number; checkout: boolean }> = [];
        for (const x of list) {
          const cur = sessions[sessions.length - 1];
          if (cur && x.t - cur.end <= 1800) {
            cur.end = x.t;
            cur.checkout ||= x.name === 'checkout';
          } else sessions.push({ start: x.t, end: x.t, checkout: x.name === 'checkout' });
        }
        out.push([c, sessions.length, sessions.filter((s) => s.checkout).length, r1(Math.max(...sessions.map((s) => s.end - s.start)) / 60)]);
      }
      return out;
    },
    careless: [],
    notes: 'Deduplicate keeping the earliest ingestion, correct fast phone clocks, filter the UK-local week on the corrected time, LAG gap > 1800 s flags a new session, running SUM numbers them, then per-session checkout flags. Planted: fast-clock checkouts that would otherwise look like a new session, a fast clock that pushes an event past the end of the week, gaps of exactly 30:00 and 30:01, events either side of the week edges in UTC vs UK time.',
  },
];

// ─────────────────────────────── Careless queries (each one real slip, applied to the reference) ───────────────────────────────

const SLIPS: Record<string, Array<[why: string, from: string, to: string, all?: boolean]>> = {
  h03: [
    ['uses today’s category from dim_product', 'ch.category AS category', '(SELECT dp.category FROM dim_product dp WHERE dp.product_id = l.product_id) AS category'],
    ['uses today’s price for every order', 'AND h.valid_from_utc <= o.ordered_at_utc\n   AND (h.valid_to_utc IS NULL OR o.ordered_at_utc < h.valid_to_utc)', 'AND h.valid_to_utc IS NULL'],
    ['an inclusive end, so an order at a price change matches two prices', 'o.ordered_at_utc < h.valid_to_utc', 'o.ordered_at_utc <= h.valid_to_utc'],
    ['March in UTC', "WHERE pl.ordered_at_local >= '2026-03-01' AND pl.ordered_at_local < '2026-04-01'", "WHERE pl.ordered_at_utc >= '2026-03-01' AND pl.ordered_at_utc < '2026-04-01'"],
  ],
  h04: [
    ['uses today’s category from dim_product', 'ch.category AS category', '(SELECT dp.category FROM dim_product dp WHERE dp.product_id = l.product_id) AS category'],
    ['ROW_NUMBER keeps exactly two per category and drops ties', 'RANK() OVER', 'ROW_NUMBER() OVER'],
    ['DENSE_RANK lets a third product in after a tie for first', 'RANK() OVER', 'DENSE_RANK() OVER'],
    ['Q2 in UTC', "WHERE pl.ordered_at_local >= '2026-04-01' AND pl.ordered_at_local < '2026-07-01'", "WHERE pl.ordered_at_utc >= '2026-04-01' AND pl.ordered_at_utc < '2026-07-01'"],
  ],
  h05: [
    ['months in UTC', "strftime('%Y-%m', datetime(ordered_at_utc, CASE WHEN ordered_at_utc >= '2026-03-29 01:00:00' AND ordered_at_utc < '2026-10-25 01:00:00' THEN '+1 hour' ELSE '+0 hours' END))", 'substr(ordered_at_utc, 1, 7)'],
    ['every order status counts', "FROM fact_orders WHERE status = 'paid'", 'FROM fact_orders'],
    ['M2 counts an order in either of the next two months', "AND p.month = strftime('%Y-%m', date(c.cohort_month || '-01', '+2 months'))) AS m2", "AND p.month > c.cohort_month AND p.month <= strftime('%Y-%m', date(c.cohort_month || '-01', '+2 months'))) AS m2"],
  ],
  h06: [
    ['ignores the lost day, so pauses through 14 April are cut in two', "  UNION ALL\n  SELECT d.customer_id, '2026-04-14', d.status\n  FROM subscription_status_daily d\n  WHERE d.snapshot_date = '2026-04-13'\n    AND EXISTS (SELECT 1 FROM subscription_status_daily e WHERE e.customer_id = d.customer_id AND e.snapshot_date = '2026-04-15')\n", ''],
    ['fills the lost day from 15 April instead of 13 April', "WHERE d.snapshot_date = '2026-04-13'\n    AND EXISTS (SELECT 1 FROM subscription_status_daily e WHERE e.customer_id = d.customer_id AND e.snapshot_date = '2026-04-15')", "WHERE d.snapshot_date = '2026-04-15'\n    AND EXISTS (SELECT 1 FROM subscription_status_daily e WHERE e.customer_id = d.customer_id AND e.snapshot_date = '2026-04-13')"],
    ['more than 7 days instead of at least 7', 'HAVING COUNT(*) >= 7', 'HAVING COUNT(*) > 7'],
    ['first and last paused day per customer, merging separate pauses', 'GROUP BY customer_id, grp', 'GROUP BY customer_id'],
  ],
  h07: [
    ['drops every reversed entry, even when the reversal was itself undone', 'COALESCE((SELECT c.counts FROM chain c WHERE c.entry_id = w.entry_id), 1) = 1', "NOT EXISTS (SELECT 1 FROM wallet_ledger r WHERE r.entry_type = 'reversal' AND r.reverses_entry_id = w.entry_id)"],
    ['end of day by the UTC date', "date(datetime(posted_at_utc, CASE WHEN posted_at_utc >= '2026-03-29 01:00:00' AND posted_at_utc < '2026-10-25 01:00:00' THEN '+1 hour' ELSE '+0 hours' END))", 'date(posted_at_utc)', true],
    ['orders the running sum by time only, so entries in the same second are added together', 'ORDER BY posted_at_utc, entry_id\n                                 ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW', 'ORDER BY posted_at_utc'],
    ['adds the reversal rows’ own amounts', "WHERE w.entry_type <> 'reversal'\n    AND COALESCE", 'WHERE COALESCE'],
  ],
  h08: [
    ['ignores the channel restriction', '   AND (p.channel IS NULL OR p.channel = q2.channel)\n', ''],
    ['checks the minimum against the line instead of the whole basket', 'SUM(pl.quantity * pl.price_gbp) OVER (PARTITION BY pl.order_id)', 'pl.quantity * pl.price_gbp'],
    ['gives every qualifying promotion to the line, not just the best one', 'FROM candidates WHERE pick = 1', 'FROM candidates'],
    ['uses UTC dates for the promotion windows', 'date(pl.ordered_at_local) AS uk_day', 'date(pl.ordered_at_utc) AS uk_day'],
  ],
  h09: [
    ['no correction for fast phone clocks', 'CASE WHEN event_ts_utc > ingested_at_utc THEN ingested_at_utc ELSE event_ts_utc END', 'event_ts_utc'],
    ['corrects with the latest delivery instead of the earliest', 'MIN(ingested_at_utc)', 'MAX(ingested_at_utc)'],
    ['a gap of exactly 30 minutes starts a new session', '<= 1800', '< 1800'],
    ['the week in UTC', "WHERE datetime(at_utc, CASE WHEN at_utc >= '2026-03-29 01:00:00' AND at_utc < '2026-10-25 01:00:00' THEN '+1 hour' ELSE '+0 hours' END) >= '2026-04-06' AND datetime(at_utc, CASE WHEN at_utc >= '2026-03-29 01:00:00' AND at_utc < '2026-10-25 01:00:00' THEN '+1 hour' ELSE '+0 hours' END) < '2026-04-13'", "WHERE at_utc >= '2026-04-06' AND at_utc < '2026-04-13'"],
  ],
};

for (const q of QUESTIONS) {
  for (const [why, from, to, all] of SLIPS[q.id] ?? []) {
    if (!q.reference.includes(from)) throw new Error(`${q.id}: slip "${why}" does not apply`);
    q.careless.push({ why, sql: all ? q.reference.split(from).join(to) : q.reference.replace(from, to) });
  }
}


export function sampleIds(t: Tables): Record<string, Array<string | number>> {
  const orders = t.fact_orders!.rows;
  const o = [orders[0]![0]!, orders.find((r) => r[4] === 'payment_failed')![0]!, orders.find((r) => String(r[2]).slice(11, 13) === '23' && String(r[2]) > '2026-03-29')![0]!];
  const lines = t.fact_order_lines!.rows.filter((r) => o.includes(Number(r[0]))).slice(0, 4).map((r) => `${r[0]}|${r[1]}`);
  const ev = t.events_raw!.rows;
  const dupKey = (r: Cell[]) => `${r[1]}|${r[2]}|${r[3]}`;
  const counts = new Map<string, number>();
  for (const r of ev) counts.set(dupKey(r), (counts.get(dupKey(r)) ?? 0) + 1);
  const dup = ev.filter((r) => counts.get(dupKey(r))! > 1).slice(0, 2).map((r) => r[0]!);
  const led = t.wallet_ledger!.rows;
  const rev = led.find((r) => r[4] === 'reversal')!;
  const subs = t.subscription_status_daily!.rows;
  const firstPause = Math.max(1, subs.findIndex((r) => r[2] === 'paused'));
  const k2 = (r: Cell[]) => `${r[0]}|${r[1]}`;
  const changed = t.dim_price_history!.rows.find((r) => r[3] !== null)![0];
  return {
    dim_customer: [1, 2],
    dim_product: [1, 3, 8],
    dim_price_history: t.dim_price_history!.rows.filter((r) => r[0] === changed).map(k2),
    dim_product_category_history: t.dim_product_category_history!.rows.filter((r) => r[3] !== null).slice(0, 1).flatMap((m) => t.dim_product_category_history!.rows.filter((r) => r[0] === m[0])).map(k2),
    fact_orders: o,
    fact_order_lines: lines,
    promotions: [2, 3, 5],
    promotion_products: t.promotion_products!.rows.filter((r) => r[0] === 3 || (r[0] === 5 && Number(r[1]) <= 2)).map(k2),
    events_raw: [ev[0]![0]!, ...dup],
    subscription_status_daily: subs.slice(firstPause - 1, firstPause + 2).map(k2),
    wallet_ledger: [led[0]![0]!, rev[5]!, rev[0]!],
  };
}
