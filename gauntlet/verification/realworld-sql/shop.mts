// "Ask the Database" (work.sql-questions): Northbound Outdoor, a UK outdoor-gear retailer with four shops and a
// website. One generator builds the live database (sampled in the prompt) and the hidden copies from different
// seeds; every question has a reference query, an independent JavaScript solver and a list of careless queries.
import { addDays, createRng, dayOf, daysBetween, fromMin, money, names, randomTs, toMin, type Cell, type Question, type Rng, type Tables } from './lib.mts';

export const SCHEMA = `CREATE TABLE stores (
  store_id   INTEGER PRIMARY KEY,
  name       TEXT NOT NULL,
  city       TEXT,            -- NULL for the website
  currency   TEXT NOT NULL,   -- 'GBP' or 'EUR' (the Dublin shop sells in euros)
  opened_on  TEXT NOT NULL    -- 'YYYY-MM-DD'
);
CREATE TABLE staff (
  staff_id   INTEGER PRIMARY KEY,
  store_id   INTEGER NOT NULL REFERENCES stores(store_id),  -- the shop the person belongs to
  full_name  TEXT NOT NULL,
  role       TEXT NOT NULL,   -- 'manager', 'sales' or 'stock'
  manager_id INTEGER REFERENCES staff(staff_id),  -- NULL for managers
  started_on TEXT NOT NULL,   -- 'YYYY-MM-DD'
  left_on    TEXT             -- NULL while still employed
);
CREATE TABLE customers (
  customer_id  INTEGER PRIMARY KEY,
  full_name    TEXT NOT NULL,
  email        TEXT NOT NULL,
  country      TEXT NOT NULL,   -- 'GB' or 'IE'
  signed_up_at TEXT NOT NULL,   -- 'YYYY-MM-DD HH:MM:SS', UK local time
  is_test      INTEGER NOT NULL DEFAULT 0,  -- 1 = internal test account
  deleted_at   TEXT             -- set when the account was deleted (soft delete)
);
CREATE TABLE products (
  product_id     INTEGER PRIMARY KEY,
  sku            TEXT NOT NULL,
  name           TEXT NOT NULL,
  category       TEXT NOT NULL,
  list_price_gbp REAL NOT NULL
);
CREATE TABLE orders (
  order_id    INTEGER PRIMARY KEY,
  customer_id INTEGER REFERENCES customers(customer_id),  -- NULL for guest checkouts on the website
  store_id    INTEGER NOT NULL REFERENCES stores(store_id),
  staff_id    INTEGER REFERENCES staff(staff_id),  -- who served the sale in a shop; NULL for the website
  ordered_at  TEXT NOT NULL,   -- 'YYYY-MM-DD HH:MM:SS', UK local time
  status      TEXT NOT NULL,   -- 'completed', 'cancelled' or 'pending'
  currency    TEXT NOT NULL,   -- currency of every amount on this order and its refunds
  deleted_at  TEXT             -- set when the order was entered by mistake (soft delete)
);
CREATE TABLE order_items (
  order_id      INTEGER NOT NULL REFERENCES orders(order_id),
  line_no       INTEGER NOT NULL,
  product_id    INTEGER NOT NULL REFERENCES products(product_id),
  quantity      INTEGER NOT NULL,
  unit_price    REAL NOT NULL,   -- in the order's currency
  line_discount REAL,            -- money off the whole line, in the order's currency; NULL = no discount
  PRIMARY KEY (order_id, line_no)
);
CREATE TABLE refunds (
  refund_id   INTEGER PRIMARY KEY,
  order_id    INTEGER NOT NULL REFERENCES orders(order_id),
  refunded_at TEXT NOT NULL,   -- 'YYYY-MM-DD HH:MM:SS', UK local time
  amount      REAL NOT NULL,   -- in the order's currency
  reason      TEXT
);
CREATE TABLE subscriptions (   -- Trailhead Club membership
  subscription_id INTEGER PRIMARY KEY,
  customer_id     INTEGER NOT NULL REFERENCES customers(customer_id),
  plan            TEXT NOT NULL,   -- 'monthly' or 'annual'
  price_gbp       REAL NOT NULL,   -- price per billing period (per month, or per year)
  started_on      TEXT NOT NULL,   -- 'YYYY-MM-DD', first day of cover
  ended_on        TEXT             -- 'YYYY-MM-DD', first day WITHOUT cover; NULL = still running
);
CREATE TABLE fx_rates (
  month        TEXT NOT NULL,   -- 'YYYY-MM'
  currency     TEXT NOT NULL,   -- only 'EUR' (there are no GBP rows)
  gbp_per_unit REAL NOT NULL,   -- pounds per euro for that calendar month
  PRIMARY KEY (month, currency)
);`;

export const RULES = `Business rules (every report follows them):
1. Real customers only. Ignore test accounts (is_test = 1) and deleted accounts (deleted_at set), together with everything that belongs to them: their orders, refunds and subscriptions.
2. Guest checkouts (orders.customer_id IS NULL) are real website sales: count them in sales and revenue, even though they belong to no customer.
3. Ignore orders with deleted_at set (they were entered by mistake), together with their items and refunds.
4. A sale is an order with status 'completed'. Cancelled and pending orders are never sales.
5. Order value = the sum over its lines of quantity × unit_price − line_discount (a NULL line_discount means no discount), in the order's currency.
6. Refunds belong to the order they refund and are in the order's currency. Net value of an order = order value − all its refunds, whenever they were made. Revenue in a period is the net value of the sales placed in that period.
7. Money in pounds: GBP amounts as they are; EUR amounts × fx_rates.gbp_per_unit for the calendar month of the order's ordered_at. The order's own currency column decides (some website orders are in euros).
8. Every time is UK local time, stored as text 'YYYY-MM-DD HH:MM:SS'. A day, month or quarter of an order means by its ordered_at. Quarters are calendar quarters (Q1 = January to March).
9. A subscription is active on day D when started_on <= D and (ended_on IS NULL or ended_on > D). Its monthly value is price_gbp for the monthly plan and price_gbp / 12 for the annual plan.`;

// ─────────────────────────────── Catalogue ───────────────────────────────

const PRODUCTS: Array<[string, string, string, number]> = [
  ['TNT-RL2', 'Ridgeline 2-Person Tent', 'Tents & Sleeping', 189],
  ['SLP-DN5', 'Down Sleeping Bag -5C', 'Tents & Sleeping', 210],
  ['MAT-INF', 'Inflatable Sleeping Mat', 'Tents & Sleeping', 74],
  ['JKT-STP', 'Storm Pro Waterproof Jacket', 'Clothing', 165],
  ['TRS-WPF', 'Waterproof Trousers', 'Clothing', 85],
  ['BSL-MER', 'Merino Base Layer Top', 'Clothing', 55],
  ['FLC-MID', 'Fleece Midlayer', 'Clothing', 60],
  ['SHO-TRL', 'Trail Running Shoes', 'Footwear', 110],
  ['BOT-HIK', 'Leather Hiking Boots', 'Footwear', 145],
  ['SOX-3PK', 'Hiking Socks (3-pack)', 'Footwear', 18],
  ['STV-CMP', 'Compact Camping Stove', 'Equipment', 45],
  ['GAS-230', 'Gas Canister 230 g', 'Equipment', 6.5],
  ['TRK-POL', 'Trekking Poles (pair)', 'Equipment', 48],
  ['PCK-22L', 'Daypack 22 L', 'Equipment', 65],
  ['TCH-400', 'Head Torch 400 lm', 'Accessories', 32],
  ['FLK-750', 'Insulated Flask 750 ml', 'Accessories', 24],
  ['AID-KIT', 'First Aid Kit', 'Accessories', 19.5],
  ['BAG-D20', 'Dry Bag 20 L', 'Accessories', 16],
];
// Product ids (1-based) that are often bought together with another one.
const COMPANIONS: Record<number, Array<[number, number]>> = {
  11: [[12, 0.7]], // stove → gas
  1: [[2, 0.35], [3, 0.3]], // tent → sleeping bag, mat
  4: [[5, 0.35]], // jacket → trousers
  8: [[10, 0.4]], // shoes → socks
  9: [[10, 0.45]], // boots → socks
};
const EUR_PRICE = (gbp: number) => money(Math.round(gbp * 1.17) - 0.01);

const STORES: Cell[][] = [
  [1, 'Leeds Briggate', 'Leeds', 'GBP', '2019-04-06'],
  [2, 'Bristol Harbourside', 'Bristol', 'GBP', '2020-09-12'],
  [3, 'Glasgow Buchanan St', 'Glasgow', 'GBP', '2021-03-20'],
  [4, 'Dublin Grafton St', 'Dublin', 'EUR', '2022-05-14'],
  [5, 'Website', null, 'GBP', '2018-11-01'],
];

interface Gen {
  tables: Tables;
}

const DOMAINS = ['mailbox.co.uk', 'postmail.com', 'inbox.ie', 'webmail.net', 'fastmail.uk'];

/** One database. Every copy gets the same planted situations (with different values) so each trap is live in each. */
export function generateShop(seed: number): Gen {
  const rng: Rng = createRng(seed);
  // ── staff ──
  const staff: Cell[][] = [];
  const staffNames = names(rng.fork('staff'), 26);
  let sid = 0;
  const salesByStore = new Map<number, number[]>();
  const staffInfo = new Map<number, { store: number; started: string; left: string | null; role: string }>();
  for (const store of [1, 2, 3, 4, 5]) {
    const mgr = ++sid;
    staff.push([mgr, store, staffNames[sid - 1]!, 'manager', null, addDays('2019-06-01', rng.int(0, 1500)), null]);
    staffInfo.set(mgr, { store, started: String(staff.at(-1)![5]), left: null, role: 'manager' });
    if (store === 5) continue;
    const sellers = [mgr];
    for (let k = 0; k < 3; k++) {
      const id = ++sid;
      let started = addDays('2021-01-01', rng.int(0, 1700));
      let left: string | null = null;
      if (k === 2 && rng.chance(0.5)) left = addDays('2026-02-01', rng.int(0, 40)); // left during the year
      if (k === 1 && rng.chance(0.4)) started = addDays('2026-01-10', rng.int(0, 50)); // new starter
      staff.push([id, store, staffNames[sid - 1]!, 'sales', mgr, started, left]);
      staffInfo.set(id, { store, started, left, role: 'sales' });
      sellers.push(id);
    }
    const stock = ++sid;
    staff.push([stock, store, staffNames[sid - 1]!, 'stock', mgr, addDays('2022-01-01', rng.int(0, 1200)), null]);
    staffInfo.set(stock, { store, started: String(staff.at(-1)![5]), left: null, role: 'stock' });
    salesByStore.set(store, sellers);
  }
  const sellerAt = (store: number, day: string, r: Rng): number => {
    const ok = (salesByStore.get(store) ?? []).filter((id) => {
      const s = staffInfo.get(id)!;
      return s.started <= day && (s.left === null || s.left > day);
    });
    return r.pick(ok);
  };

  // ── customers ──
  const cr = rng.fork('customers');
  const custNames = names(cr, 80);
  const customers: Cell[][] = [];
  const cinfo = new Map<number, { home: number; country: string; signed: string; real: boolean }>();
  const N = 66 + cr.int(0, 8);
  const febExtra = cr.int(0, 10);
  for (let i = 1; i <= N; i++) {
    const home = cr.pick([1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 5]);
    const country = home === 4 || (home === 5 && cr.chance(0.18)) ? 'IE' : 'GB';
    let signed = randomTs(cr, '2025-01-05', '2026-06-26', [7, 23]);
    if (i === 3) signed = fromMin(toMin('2026-02-28 00:00:00') + cr.int(13 * 60, 21 * 60), cr.int(0, 59)); // 28 Feb, afternoon
    if (i === 7) signed = fromMin(toMin('2026-03-01 00:00:00') + cr.int(1, 50), cr.int(0, 59)); // just after midnight, 1 Mar
    if (i === 11) signed = randomTs(cr, '2025-02-01', '2025-03-01', [8, 22]); // February of the previous year
    if (i === 15) signed = randomTs(cr, '2026-02-01', '2026-02-28', [8, 22]);
    if (i === 16) signed = randomTs(cr, '2026-02-01', '2026-02-28', [8, 22]);
    if (i === 21) signed = randomTs(cr, '2026-06-01', '2026-06-25', [8, 22]); // after the end of May
    if (i >= 40 && i < 40 + febExtra) signed = randomTs(cr, '2026-02-01', '2026-02-28', [7, 23]);
    const name = custNames[i - 1]!;
    const email = `${name.toLowerCase().replace(/[^a-z]+/g, '.')}${cr.int(1, 99)}@${cr.pick(DOMAINS)}`;
    const deleted = i === 5 || i === 18 || (i === 29 && cr.chance(0.6)) ? addDays(dayOf(signed) < '2026-04-01' ? '2026-04-01' : dayOf(signed), cr.int(1, 50)) + ' 10:00:00' : null;
    if (i === 18) signed = randomTs(cr, '2026-02-01', '2026-02-28', [8, 22]);
    customers.push([i, name, email, country, signed, 0, deleted]);
    cinfo.set(i, { home, country, signed, real: deleted === null });
  }
  const tests: Array<[string, number]> = [['QA Checkout Bot', 5], ['Store Test Leeds', 1], ['Till Test Glasgow', 3], ['Payments Sandbox', 5]];
  for (const [name, home] of tests) {
    const id = customers.length + 1;
    const signed = id === N + 3 ? randomTs(cr, '2026-02-01', '2026-02-28', [8, 18]) : randomTs(cr, '2025-03-01', '2026-01-31', [8, 18]);
    customers.push([id, name, `qa+${id}@northbound-outdoor.co.uk`, home === 4 ? 'IE' : 'GB', signed, 1, null]);
    cinfo.set(id, { home, country: 'GB', signed, real: false });
  }
  // A customer whose only order was a mistaken entry, and one who only ever cancelled.
  // (Planted below via special order generation.)

  // ── fx rates ──
  const months = ['2025-12', '2026-01', '2026-02', '2026-03', '2026-04', '2026-05', '2026-06'];
  const fxr = rng.fork('fx');
  const base = 0.84 + fxr.int(0, 30) / 1000;
  const fx: Cell[][] = months.map((m) => [m, 'EUR', Math.round((base + fxr.int(-12, 12) / 1000) * 10000) / 10000]);

  // ── orders ──
  const or = rng.fork('orders');
  const orders: Cell[][] = [];
  const items: Cell[][] = [];
  const refunds: Cell[][] = [];
  let oid = 1000 + or.int(0, 400);
  // How strongly the usual companions are bought together varies from copy to copy.
  const affinity = 0.55 + or.fork("affinity").int(0, 60) / 100;
  const pickProduct = (r: Rng): number => {
    const weights = [3, 2, 2, 3, 2, 4, 4, 4, 3, 5, 3, 3, 3, 3, 4, 4, 2, 3];
    const total = weights.reduce((a, b) => a + b, 0);
    let x = r.next() * total;
    for (let i = 0; i < weights.length; i++) if ((x -= weights[i]!) < 0) return i + 1;
    return 18;
  };
  const addItems = (orderId: number, currency: string, r: Rng): number => {
    const nLines = r.pick([1, 1, 1, 2, 2, 2, 3, 3, 4]);
    const chosen: number[] = [];
    while (chosen.length < nLines) {
      const p = pickProduct(r);
      if (!chosen.includes(p)) chosen.push(p);
      for (const [q, prob] of COMPANIONS[p] ?? []) if (!chosen.includes(q) && r.chance(prob * affinity)) chosen.push(q);
    }
    if (chosen.includes(12) && r.chance(0.25)) chosen.push(12); // a second gas line (quantity added at the till)
    let line = 0;
    let total = 0;
    for (const p of chosen) {
      const list = PRODUCTS[p - 1]![3];
      let unit = currency === 'EUR' ? EUR_PRICE(list) : list;
      if (r.chance(0.1)) unit = money(unit * 0.8);
      const qty = p === 12 || p === 10 ? r.pick([1, 1, 2, 3, 4]) : r.pick([1, 1, 1, 1, 2]);
      let disc: number | null = null;
      if (r.chance(0.2)) disc = r.pick([5, 10, money(qty * unit * 0.15)]);
      if (disc !== null && disc >= qty * unit) disc = null;
      items.push([orderId, ++line, p, qty, unit, disc]);
      total += qty * unit - (disc ?? 0);
    }
    return money(total);
  };
  let rid = 1;
  const addRefund = (orderId: number, at: string, value: number, r: Rng) => {
    if (at > '2026-06-30 23:59:59') return;
    const amount = r.chance(0.5) ? money(value * r.pick([0.25, 0.5, 1])) : money(Math.min(value, r.pick([10, 15, 20, 25, 40])));
    if (amount <= 0) return;
    refunds.push([rid++, orderId, at, amount, r.pick(['faulty', 'wrong size', 'changed mind', 'late delivery', 'damaged in transit', null])]);
  };
  const makeOrder = (cust: number | null, store: number, at: string, status: string, r: Rng, opts: { deleted?: string | null; currency?: string; staff?: number | null } = {}) => {
    const id = oid++;
    const country = cust === null ? (r.chance(0.15) ? 'IE' : 'GB') : cinfo.get(cust)!.country;
    const currency = opts.currency ?? (store === 4 || (store === 5 && country === 'IE') ? 'EUR' : 'GBP');
    let staffId: number | null = null;
    if (store !== 5) staffId = opts.staff !== undefined ? opts.staff : r.chance(0.12) ? sellerAt(r.pick([1, 2, 3, 4].filter((s) => s !== store)), dayOf(at), r) : sellerAt(store, dayOf(at), r);
    orders.push([id, cust, store, staffId, at, status, currency, opts.deleted ?? null]);
    const value = addItems(id, currency, r);
    return { id, value, currency };
  };
  // In some copies the Glasgow shop is shut for a refit all May (so it has no sales that month).
  const refit = rng.fork('refit').chance(0.5);
  const statusFor = (at: string, r: Rng) => (r.chance(0.87) ? 'completed' : at >= '2026-06-22' ? 'pending' : 'cancelled');

  for (const [cidStr, info] of [...cinfo.entries()].sort((a, b) => a[0] - b[0])) {
    const cid = Number(cidStr);
    const r = or.fork(`c${cid}`);
    const startDay = dayOf(info.signed) > '2025-12-01' ? dayOf(info.signed) : '2025-12-01';
    if (startDay >= '2026-06-30') continue;
    let n = r.pick([0, 0, 1, 1, 1, 2, 2, 3, 3, 4, 5, 6]);
    if (cid === 21) n = 0;
    if (cid === 9 || cid === 13) n = 0; // planted below
    const times = Array.from({ length: n }, () => randomTs(r, startDay, '2026-07-01', [8, 22])).sort();
    let stopAfterRefund = cid % 5 === 2 && n >= 2; // loyal buyers who stop after a bad experience
    for (const at of times) {
      const store = r.chance(0.72) ? info.home : 5;
      if (refit && store === 3 && at >= '2026-05-01' && at < '2026-06-01') continue; // Glasgow is shut for a refit in May
      const status = statusFor(at, r);
      const o = makeOrder(cid, store, at, status, r);
      if (status === 'completed' && (r.chance(0.13) || (stopAfterRefund && times.indexOf(at) === times.length - 1))) {
        addRefund(o.id, fromMin(toMin(at) + r.int(2, 30) * 1440 + r.int(0, 600)), o.value, r);
        if (r.chance(0.15)) addRefund(o.id, fromMin(toMin(at) + r.int(31, 50) * 1440), o.value, r);
        stopAfterRefund = false;
      }
      if (r.chance(0.04)) {
        // A double submission: the same basket again two minutes later, deleted afterwards.
        const dup = makeOrder(cid, store, fromMin(toMin(at) + 2, 7), status, r, { deleted: fromMin(toMin(at) + 1440) });
        if (r.chance(0.5)) addRefund(dup.id, fromMin(toMin(at) + 3 * 1440), dup.value, r);
      }
    }
  }
  // Planted: customer 9 has a single order that was entered by mistake (deleted); customer 13 only ever cancelled.
  {
    const r = or.fork('planted');
    const a = randomTs(r, '2026-02-01', '2026-05-01', [9, 18]);
    if (cinfo.get(9)!.signed < a) makeOrder(9, cinfo.get(9)!.home === 3 ? 1 : cinfo.get(9)!.home, a, 'completed', r, { deleted: fromMin(toMin(a) + 600) });
    const b = randomTs(r, '2026-01-10', '2026-06-01', [9, 18]);
    if (cinfo.get(13)!.signed < b) makeOrder(13, 5, b, 'cancelled', r);
    // Glasgow during the refit: only till tests by test accounts, and one cancelled real order.
    const till = N + 3;
    if (refit) for (let k = 0; k < 2; k++) makeOrder(till, 3, randomTs(r, '2026-05-04', '2026-05-30', [9, 17]), 'completed', r, { staff: salesByStore.get(3)![0]! });
    makeOrder(r.int(30, 60), 3, randomTs(r, '2026-05-04', '2026-05-30', [10, 16]), 'cancelled', r);
    // Test orders elsewhere, some refunded.
    for (let k = 0; k < 6; k++) {
      const cust = N + 1 + r.int(0, 3);
      const at = randomTs(r, '2025-12-01', '2026-07-01', [0, 24]);
      const o = makeOrder(cust, cinfo.get(cust)!.home, at, 'completed', r);
      if (r.chance(0.5)) addRefund(o.id, fromMin(toMin(at) + 2880), o.value, r);
    }
    // Guest checkouts on the website.
    for (let k = 0; k < 28 + r.int(0, 8); k++) {
      const at = randomTs(r, '2025-12-01', '2026-07-01', [0, 24]);
      const st = statusFor(at, r);
      const o = makeOrder(null, 5, at, st, r);
      if (st === 'completed' && r.chance(0.12)) addRefund(o.id, fromMin(toMin(at) + r.int(3, 25) * 1440), o.value, r);
    }
    // December refunds landing in January (so "refunds made in Q1" differs from "refunds on Q1 orders").
    for (let k = 0; k < 2; k++) {
      const at = randomTs(r, '2025-12-15', '2026-01-01', [10, 18]);
      const cust = r.int(30, 60);
      if (!cinfo.get(cust)!.real || cinfo.get(cust)!.signed > at) continue;
      const o = makeOrder(cust, cinfo.get(cust)!.home === 3 ? 2 : cinfo.get(cust)!.home, at, 'completed', r);
      addRefund(o.id, fromMin(toMin('2026-01-05 00:00:00') + r.int(0, 20) * 1440 + 600), o.value, r);
    }
  }
  // In about half of the copies, top up the runner-up product pair with plain two-item website sales until it ties
  // for "most often bought together", so a query that keeps only one pair is wrong there.
  {
    const r = or.fork('tie');
    if (r.chance(0.5)) {
      const realCust = (c: Cell) => c === null || cinfo.get(Number(c))!.real;
      const saleIds = new Set(orders.filter((o) => o[5] === 'completed' && o[7] === null && realCust(o[1]) && String(o[4]) >= '2026-01-01' && String(o[4]) < '2026-07-01').map((o) => Number(o[0])));
      const per = new Map<number, Set<number>>();
      for (const it of items) if (saleIds.has(Number(it[0]))) per.set(Number(it[0]), (per.get(Number(it[0])) ?? new Set()).add(Number(it[2])));
      const cnt = new Map<string, number>();
      for (const set of per.values()) {
        const ps = [...set].sort((a, b) => a - b);
        for (let i = 0; i < ps.length; i++) for (let j = i + 1; j < ps.length; j++) cnt.set(`${ps[i]}|${ps[j]}`, (cnt.get(`${ps[i]}|${ps[j]}`) ?? 0) + 1);
      }
      const ranked = [...cnt.entries()].sort((a, b) => b[1] - a[1] || (a[0] < b[0] ? -1 : 1));
      const [top, second] = ranked;
      if (top && second && top[1] - second[1] <= 6) {
        const [a, b] = second[0].split('|').map(Number);
        for (let k = 0; k < top[1] - second[1]; k++) {
          const id = oid++;
          orders.push([id, null, 5, null, randomTs(r, '2026-02-01', '2026-06-28', [0, 24]), 'completed', 'GBP', null]);
          items.push([id, 1, a!, 1, PRODUCTS[a! - 1]![3], null], [id, 2, b!, 1, PRODUCTS[b! - 1]![3], null]);
        }
      }
    }
  }
  orders.sort((a, b) => Number(a[0]) - Number(b[0]));
  items.sort((a, b) => Number(a[0]) - Number(b[0]) || Number(a[1]) - Number(b[1]));

  // ── subscriptions ──
  const sr = rng.fork('subs');
  const subs: Cell[][] = [];
  let subId = 500 + sr.int(0, 100);
  const addSub = (cust: number, plan: string, start: string, end: string | null) => {
    const price = plan === 'annual' ? 69 : start < '2025-09-01' ? 5.99 : 6.99;
    subs.push([subId++, cust, plan, price, start, end]);
  };
  for (const [cid, info] of cinfo) {
    if (!sr.chance(info.real ? 0.45 : 0.5)) continue;
    const plan = sr.chance(0.6) ? 'monthly' : 'annual';
    const start = addDays(dayOf(info.signed), sr.int(0, 60));
    if (start > '2026-06-25') continue;
    let end: string | null = null;
    if (sr.chance(0.55)) {
      end = plan === 'annual' ? (sr.chance(0.5) ? addDays(start, 365) : addDays(start, sr.int(60, 300))) : addDays(start, sr.int(1, 9) * 30 + sr.int(-3, 3));
      if (end > '2026-06-30') end = null;
    }
    addSub(cid, plan, start, end);
    if (end && sr.chance(0.35)) {
      // Switched or came back: a new subscription starting around the end of the old one.
      const gap = sr.pick([-2, 0, 0, 1, 5, 12, 29, 30, 31, 45]);
      const s2 = addDays(end, gap);
      if (s2 <= '2026-06-25') addSub(cid, plan === 'monthly' ? 'annual' : 'monthly', s2, sr.chance(0.25) ? addDays(s2, sr.int(30, 120)) : null);
    }
  }
  // Planted boundary cases for 31 March and for the churn question.
  const realIds = [...cinfo.entries()].filter(([, v]) => v.real && v.signed < '2025-11-01').map(([k]) => k);
  const pr = sr.fork('planted');
  const pickReal = () => pr.pick(realIds);
  addSub(pickReal(), 'monthly', '2025-11-14', '2026-03-31'); // ends on 31 March: not active that day
  addSub(pickReal(), 'annual', '2026-03-31', null); // starts on 31 March: active
  {
    const c = pickReal();
    addSub(c, 'monthly', '2025-10-03', '2026-02-10');
    addSub(c, 'annual', '2026-03-12', null); // back exactly 30 days later: not churned
  }
  {
    const c = pickReal();
    addSub(c, 'monthly', '2025-09-20', '2026-01-17');
    addSub(c, 'monthly', '2026-02-17', null); // back after 31 days: churned
  }
  {
    const c = pickReal();
    addSub(c, 'monthly', '2025-12-01', '2026-03-05');
    addSub(c, 'annual', '2026-03-02', null); // the new plan started before the old one ended: not churned
  }
  subs.sort((a, b) => Number(a[0]) - Number(b[0]));

  const products: Cell[][] = PRODUCTS.map(([sku, name, cat, price], i) => [i + 1, sku, name, cat, price]);
  return {
    tables: {
      stores: { columns: ['store_id', 'name', 'city', 'currency', 'opened_on'], rows: STORES },
      staff: { columns: ['staff_id', 'store_id', 'full_name', 'role', 'manager_id', 'started_on', 'left_on'], rows: staff },
      customers: { columns: ['customer_id', 'full_name', 'email', 'country', 'signed_up_at', 'is_test', 'deleted_at'], rows: customers },
      products: { columns: ['product_id', 'sku', 'name', 'category', 'list_price_gbp'], rows: products },
      orders: { columns: ['order_id', 'customer_id', 'store_id', 'staff_id', 'ordered_at', 'status', 'currency', 'deleted_at'], rows: orders },
      order_items: { columns: ['order_id', 'line_no', 'product_id', 'quantity', 'unit_price', 'line_discount'], rows: items },
      refunds: { columns: ['refund_id', 'order_id', 'refunded_at', 'amount', 'reason'], rows: refunds },
      subscriptions: { columns: ['subscription_id', 'customer_id', 'plan', 'price_gbp', 'started_on', 'ended_on'], rows: subs },
      fx_rates: { columns: ['month', 'currency', 'gbp_per_unit'], rows: fx },
    },
  };
}

// ─────────────────────────────── Independent solver helpers (plain JavaScript over the rows) ───────────────────────────────

interface Ctx {
  real: Set<number>;
  custName: Map<number, string>;
  sales: Array<{ id: number; cust: number | null; store: number; staff: number | null; at: string; currency: string }>;
  value: Map<number, number>;
  refunded: Map<number, number>;
  refundRows: Array<{ order: number; at: string; amount: number }>;
  rate: (currency: string, at: string) => number;
  storeName: Map<number, string>;
  productName: Map<number, string>;
  t: Tables;
}

function ctx(t: Tables): Ctx {
  const real = new Set<number>();
  const custName = new Map<number, string>();
  for (const [id, name, , , , test, del] of t.customers!.rows) {
    custName.set(Number(id), String(name));
    if (test === 0 && del === null) real.add(Number(id));
  }
  const fx = new Map(t.fx_rates!.rows.map((r) => [`${r[0]}|${r[1]}`, Number(r[2])]));
  const value = new Map<number, number>();
  for (const [o, , , q, p, d] of t.order_items!.rows) value.set(Number(o), (value.get(Number(o)) ?? 0) + Number(q) * Number(p) - (d === null ? 0 : Number(d)));
  const refunded = new Map<number, number>();
  const refundRows: Ctx['refundRows'] = [];
  for (const [, o, at, amt] of t.refunds!.rows) {
    refunded.set(Number(o), (refunded.get(Number(o)) ?? 0) + Number(amt));
    refundRows.push({ order: Number(o), at: String(at), amount: Number(amt) });
  }
  const sales = t.orders!.rows
    .filter((r) => r[5] === 'completed' && r[7] === null && (r[1] === null || real.has(Number(r[1]))))
    .map((r) => ({ id: Number(r[0]), cust: r[1] === null ? null : Number(r[1]), store: Number(r[2]), staff: r[3] === null ? null : Number(r[3]), at: String(r[4]), currency: String(r[6]) }));
  return {
    real,
    custName,
    sales,
    value,
    refunded,
    refundRows,
    rate: (c, at) => (c === 'GBP' ? 1 : fx.get(`${at.slice(0, 7)}|${c}`)!),
    storeName: new Map(t.stores!.rows.map((r) => [Number(r[0]), String(r[1])])),
    productName: new Map(t.products!.rows.map((r) => [Number(r[0]), String(r[2])])),
    t,
  };
}

const netGbp = (c: Ctx, s: Ctx['sales'][number]) => ((c.value.get(s.id) ?? 0) - (c.refunded.get(s.id) ?? 0)) * c.rate(s.currency, s.at);
const inRange = (at: string, from: string, to: string) => at >= from && at < to;
const r2 = (x: number) => Math.round(x * 100) / 100;
const r1 = (x: number) => Math.round(x * 10) / 10;

// ─────────────────────────────── Questions ───────────────────────────────

const SALES = `sales AS (
  SELECT o.*
  FROM orders o
  LEFT JOIN customers c ON c.customer_id = o.customer_id
  WHERE o.status = 'completed' AND o.deleted_at IS NULL
    AND (o.customer_id IS NULL OR (c.is_test = 0 AND c.deleted_at IS NULL))
)`;
const VALUE = `order_value AS (
  SELECT order_id, SUM(quantity * unit_price - COALESCE(line_discount, 0)) AS value
  FROM order_items GROUP BY order_id
)`;
const REFUNDED = `order_refunds AS (
  SELECT order_id, SUM(amount) AS refunded FROM refunds GROUP BY order_id
)`;
const RATE = `CASE WHEN o.currency = 'GBP' THEN 1.0
       ELSE (SELECT f.gbp_per_unit FROM fx_rates f WHERE f.currency = o.currency AND f.month = substr(o.ordered_at, 1, 7)) END`;
const NET = `(COALESCE(v.value, 0) - COALESCE(r.refunded, 0)) * ${RATE}`;

export const QUESTIONS: Question[] = [
  {
    id: 'q01',
    difficulty: 'easy',
    text: `From Priya (marketing): "How many real customers signed up in February 2026?"

Return one row with one column: the number of customers.`,
    ordered: false,
    reference: `SELECT COUNT(*) AS customers
FROM customers
WHERE is_test = 0 AND deleted_at IS NULL
  AND signed_up_at >= '2026-02-01' AND signed_up_at < '2026-03-01'`,
    solve: (t) => [[t.customers!.rows.filter((r) => r[5] === 0 && r[6] === null && inRange(String(r[4]), '2026-02-01', '2026-03-01')).length]],
    careless: [
      { why: 'BETWEEN on the dates misses sign-ups later on 28 February', sql: `SELECT COUNT(*) FROM customers WHERE is_test = 0 AND deleted_at IS NULL AND signed_up_at BETWEEN '2026-02-01' AND '2026-02-28'` },
      { why: 'counts test and deleted accounts', sql: `SELECT COUNT(*) FROM customers WHERE signed_up_at >= '2026-02-01' AND signed_up_at < '2026-03-01'` },
      { why: 'month number only, so February 2025 counts too', sql: `SELECT COUNT(*) FROM customers WHERE is_test = 0 AND deleted_at IS NULL AND strftime('%m', signed_up_at) = '02'` },
    ],
    notes: 'Half-open date range on a timestamp column; test accounts and deleted accounts excluded. Planted: a sign-up on the afternoon of 28 Feb, one just after midnight on 1 Mar, one in Feb 2025, a test account and a deleted account in Feb 2026.',
  },
  {
    id: 'q02',
    difficulty: 'easy',
    text: `From Callum (head of retail): "How many sales did each shop and the website make in May 2026, and how many items did they sell? I want every location on the list, even one that sold nothing."

Return one row per store (all five) with three columns: the store name, the number of sales (completed orders) placed in May 2026, and the number of items sold in those sales (the total quantity on their lines). A store with no sales shows 0 in both number columns.`,
    ordered: false,
    reference: `WITH ${SALES},
may AS (
  SELECT o.store_id, COUNT(*) AS sales,
         SUM((SELECT SUM(oi.quantity) FROM order_items oi WHERE oi.order_id = o.order_id)) AS items
  FROM sales o
  WHERE o.ordered_at >= '2026-05-01' AND o.ordered_at < '2026-06-01'
  GROUP BY o.store_id
)
SELECT s.name, COALESCE(m.sales, 0) AS sales, COALESCE(m.items, 0) AS items
FROM stores s
LEFT JOIN may m ON m.store_id = s.store_id`,
    solve: (t) => {
      const c = ctx(t);
      const qty = new Map<number, number>();
      for (const [o, , , q] of t.order_items!.rows) qty.set(Number(o), (qty.get(Number(o)) ?? 0) + Number(q));
      return t.stores!.rows.map((s) => {
        const may = c.sales.filter((o) => o.store === s[0] && inRange(o.at, '2026-05-01', '2026-06-01'));
        return [s[1], may.length, may.reduce((a, o) => a + (qty.get(o.id) ?? 0), 0)];
      });
    },
    careless: [
      { why: 'COUNT(*) after joining the lines counts lines, not sales', sql: `SELECT s.name, COUNT(oi.order_id), COALESCE(SUM(oi.quantity), 0) FROM stores s LEFT JOIN orders o ON o.store_id = s.store_id AND o.status = 'completed' AND o.deleted_at IS NULL AND o.ordered_at >= '2026-05-01' AND o.ordered_at < '2026-06-01' AND (o.customer_id IS NULL OR o.customer_id IN (SELECT customer_id FROM customers WHERE is_test = 0 AND deleted_at IS NULL)) LEFT JOIN order_items oi ON oi.order_id = o.order_id GROUP BY s.store_id, s.name` },
      { why: 'an inner join drops a location with no sales', sql: `SELECT s.name, COUNT(DISTINCT o.order_id), SUM(oi.quantity) FROM orders o JOIN stores s ON s.store_id = o.store_id JOIN order_items oi ON oi.order_id = o.order_id LEFT JOIN customers c ON c.customer_id = o.customer_id WHERE o.status = 'completed' AND o.deleted_at IS NULL AND (o.customer_id IS NULL OR (c.is_test = 0 AND c.deleted_at IS NULL)) AND o.ordered_at >= '2026-05-01' AND o.ordered_at < '2026-06-01' GROUP BY s.name` },
      { why: 'counts test-account till tests and mistaken entries', sql: `SELECT s.name, COUNT(DISTINCT o.order_id), COALESCE(SUM(oi.quantity), 0) FROM stores s LEFT JOIN orders o ON o.store_id = s.store_id AND o.status = 'completed' AND o.ordered_at >= '2026-05-01' AND o.ordered_at < '2026-06-01' LEFT JOIN order_items oi ON oi.order_id = o.order_id GROUP BY s.store_id, s.name` },
    ],
    notes: 'LEFT JOIN from stores, sales counted before the line quantities are added (or COUNT DISTINCT), COALESCE for the empty location. Planted: in some copies Glasgow is shut for a refit in May; its only May orders are two completed till tests by a test account and one cancelled order, so both numbers are 0.',
  },
  {
    id: 'q03',
    difficulty: 'easy',
    text: `From Ines (buying): "What were our three best-selling products by units in Q1 2026?"

Units sold = the total quantity on the lines of sales placed in Q1 2026 (refunds don't change units sold). Return three rows, best seller first, with two columns: the product name and the units sold. If two products sold the same number of units, the one whose name comes first alphabetically ranks higher.`,
    ordered: true,
    reference: `WITH ${SALES}
SELECT p.name, SUM(oi.quantity) AS units
FROM sales o
JOIN order_items oi ON oi.order_id = o.order_id
JOIN products p ON p.product_id = oi.product_id
WHERE o.ordered_at >= '2026-01-01' AND o.ordered_at < '2026-04-01'
GROUP BY p.product_id, p.name
ORDER BY units DESC, p.name ASC
LIMIT 3`,
    solve: (t) => {
      const c = ctx(t);
      const ids = new Set(c.sales.filter((o) => inRange(o.at, '2026-01-01', '2026-04-01')).map((o) => o.id));
      const units = new Map<number, number>();
      for (const [o, , p, q] of t.order_items!.rows) if (ids.has(Number(o))) units.set(Number(p), (units.get(Number(p)) ?? 0) + Number(q));
      return [...units.entries()]
        .map(([p, u]) => [c.productName.get(p)!, u] as [string, number])
        .sort((a, b) => b[1] - a[1] || (a[0] < b[0] ? -1 : 1))
        .slice(0, 3);
    },
    careless: [
      { why: 'counts lines instead of units', sql: `SELECT p.name, COUNT(*) AS n FROM order_items oi JOIN orders o ON o.order_id = oi.order_id JOIN products p ON p.product_id = oi.product_id LEFT JOIN customers c ON c.customer_id = o.customer_id WHERE o.status = 'completed' AND o.deleted_at IS NULL AND (o.customer_id IS NULL OR (c.is_test = 0 AND c.deleted_at IS NULL)) AND o.ordered_at >= '2026-01-01' AND o.ordered_at < '2026-04-01' GROUP BY p.product_id ORDER BY n DESC, p.name LIMIT 3` },
      { why: 'includes cancelled, pending, mistaken and test orders', sql: `SELECT p.name, SUM(oi.quantity) AS units FROM order_items oi JOIN orders o ON o.order_id = oi.order_id JOIN products p ON p.product_id = oi.product_id WHERE o.ordered_at >= '2026-01-01' AND o.ordered_at < '2026-04-01' GROUP BY p.product_id ORDER BY units DESC, p.name LIMIT 3` },
    ],
    notes: 'Gas canisters and socks sell in multiples, so units and line counts rank differently.',
  },
  {
    id: 'q04',
    difficulty: 'easy',
    text: `From Kwame (CRM): "Which customers have never bought anything from us? Only people who signed up on or before 31 May 2026 - the June sign-ups haven't had a chance yet."

"Never bought" means the customer has no sale (completed order) at all; customers whose only orders were cancelled, pending or entered by mistake count as never bought. Return one row per customer with three columns: customer_id, full_name and the sign-up date as text 'YYYY-MM-DD'.`,
    ordered: false,
    reference: `SELECT c.customer_id, c.full_name, date(c.signed_up_at) AS signed_up_on
FROM customers c
WHERE c.is_test = 0 AND c.deleted_at IS NULL
  AND c.signed_up_at < '2026-06-01'
  AND NOT EXISTS (
    SELECT 1 FROM orders o
    WHERE o.customer_id = c.customer_id AND o.status = 'completed' AND o.deleted_at IS NULL
  )`,
    solve: (t) => {
      const c = ctx(t);
      const buyers = new Set(c.sales.map((s) => s.cust));
      return t.customers!.rows.filter((r) => c.real.has(Number(r[0])) && String(r[4]) < '2026-06-01' && !buyers.has(Number(r[0]))).map((r) => [r[0], r[1], String(r[4]).slice(0, 10)]);
    },
    careless: [
      { why: 'NOT IN over a column with NULLs (guest checkouts) returns nobody', sql: `SELECT customer_id, full_name, date(signed_up_at) FROM customers WHERE is_test = 0 AND deleted_at IS NULL AND signed_up_at < '2026-06-01' AND customer_id NOT IN (SELECT customer_id FROM orders WHERE status = 'completed' AND deleted_at IS NULL)` },
      { why: 'any order counts as buying (cancelled, mistaken entries)', sql: `SELECT c.customer_id, c.full_name, date(c.signed_up_at) FROM customers c LEFT JOIN orders o ON o.customer_id = c.customer_id WHERE c.is_test = 0 AND c.deleted_at IS NULL AND c.signed_up_at < '2026-06-01' AND o.order_id IS NULL` },
      { why: 'keeps the June sign-ups (<= on a timestamp)', sql: `SELECT c.customer_id, c.full_name, date(c.signed_up_at) FROM customers c WHERE c.is_test = 0 AND c.deleted_at IS NULL AND c.signed_up_at <= '2026-06-30' AND NOT EXISTS (SELECT 1 FROM orders o WHERE o.customer_id = c.customer_id AND o.status = 'completed' AND o.deleted_at IS NULL)` },
    ],
    notes: 'Anti-join with NOT EXISTS. Planted: guest checkouts have customer_id NULL (so NOT IN returns no rows), a customer whose only order is a deleted entry, a customer who only cancelled, and a June sign-up.',
  },
  {
    id: 'q05',
    difficulty: 'medium',
    text: `From Hannah (finance): "What was each location's net revenue in pounds for March 2026? Highest first."

Return one row per store that made at least one sale in March 2026, ordered from the highest net revenue to the lowest, with two columns: the store name and its net revenue in GBP rounded to 2 decimal places (net of all refunds on those sales, whenever they were made). Revenue is compared to within ±0.01.`,
    ordered: true,
    reference: `WITH ${SALES},
${VALUE},
${REFUNDED}
SELECT s.name, SUM(${NET}) AS net_revenue_gbp
FROM sales o
JOIN stores s ON s.store_id = o.store_id
LEFT JOIN order_value v ON v.order_id = o.order_id
LEFT JOIN order_refunds r ON r.order_id = o.order_id
WHERE o.ordered_at >= '2026-03-01' AND o.ordered_at < '2026-04-01'
GROUP BY s.store_id, s.name
ORDER BY net_revenue_gbp DESC`,
    solve: (t) => {
      const c = ctx(t);
      const by = new Map<number, number>();
      for (const s of c.sales) if (inRange(s.at, '2026-03-01', '2026-04-01')) by.set(s.store, (by.get(s.store) ?? 0) + netGbp(c, s));
      return [...by.entries()].sort((a, b) => b[1] - a[1]).map(([st, v]) => [c.storeName.get(st)!, r2(v)]);
    },
    careless: [
      { why: 'joins items and refunds together, so each refund repeats once per line', sql: `SELECT s.name, ROUND(SUM((oi.quantity * oi.unit_price - COALESCE(oi.line_discount, 0) - COALESCE(r.amount, 0)) * CASE WHEN o.currency = 'GBP' THEN 1 ELSE f.gbp_per_unit END), 2) AS net FROM orders o JOIN stores s ON s.store_id = o.store_id JOIN order_items oi ON oi.order_id = o.order_id LEFT JOIN refunds r ON r.order_id = o.order_id LEFT JOIN fx_rates f ON f.currency = o.currency AND f.month = substr(o.ordered_at, 1, 7) LEFT JOIN customers c ON c.customer_id = o.customer_id WHERE o.status = 'completed' AND o.deleted_at IS NULL AND (o.customer_id IS NULL OR (c.is_test = 0 AND c.deleted_at IS NULL)) AND o.ordered_at >= '2026-03-01' AND o.ordered_at < '2026-04-01' GROUP BY s.store_id ORDER BY net DESC` },
      { why: 'a NULL discount makes the whole line NULL, so discounted-free lines vanish', sql: `WITH v AS (SELECT order_id, SUM(quantity * unit_price - line_discount) AS value FROM order_items GROUP BY order_id), r AS (SELECT order_id, SUM(amount) AS refunded FROM refunds GROUP BY order_id) SELECT s.name, ROUND(SUM((COALESCE(v.value, 0) - COALESCE(r.refunded, 0)) * CASE WHEN o.currency = 'GBP' THEN 1 ELSE f.gbp_per_unit END), 2) AS net FROM orders o JOIN stores s ON s.store_id = o.store_id LEFT JOIN v ON v.order_id = o.order_id LEFT JOIN r ON r.order_id = o.order_id LEFT JOIN fx_rates f ON f.currency = o.currency AND f.month = substr(o.ordered_at, 1, 7) LEFT JOIN customers c ON c.customer_id = o.customer_id WHERE o.status = 'completed' AND o.deleted_at IS NULL AND (o.customer_id IS NULL OR (c.is_test = 0 AND c.deleted_at IS NULL)) AND o.ordered_at >= '2026-03-01' AND o.ordered_at < '2026-04-01' GROUP BY s.store_id ORDER BY net DESC` },
      { why: 'converts by the store currency, so euro website orders stay in euros', sql: `WITH v AS (SELECT order_id, SUM(quantity * unit_price - COALESCE(line_discount, 0)) AS value FROM order_items GROUP BY order_id), r AS (SELECT order_id, SUM(amount) AS refunded FROM refunds GROUP BY order_id) SELECT s.name, ROUND(SUM((COALESCE(v.value, 0) - COALESCE(r.refunded, 0)) * CASE WHEN s.currency = 'GBP' THEN 1 ELSE f.gbp_per_unit END), 2) AS net FROM orders o JOIN stores s ON s.store_id = o.store_id LEFT JOIN v ON v.order_id = o.order_id LEFT JOIN r ON r.order_id = o.order_id LEFT JOIN fx_rates f ON f.currency = 'EUR' AND f.month = substr(o.ordered_at, 1, 7) LEFT JOIN customers c ON c.customer_id = o.customer_id WHERE o.status = 'completed' AND o.deleted_at IS NULL AND (o.customer_id IS NULL OR (c.is_test = 0 AND c.deleted_at IS NULL)) AND o.ordered_at >= '2026-03-01' AND o.ordered_at < '2026-04-01' GROUP BY s.store_id ORDER BY net DESC` },
    ],
    notes: 'Aggregate items and refunds separately per order before joining (fan-out), COALESCE the NULL discounts, convert by the order currency at the order month rate.',
  },
  {
    id: 'q06',
    difficulty: 'medium',
    text: `From Siobhan (Trailhead Club): "What was our monthly recurring revenue on 31 March 2026?"

Monthly recurring revenue on a day = the total monthly value of the real customers' subscriptions active on that day (rules 1 and 9). Return one row with one column: the amount in GBP rounded to 2 decimal places (compared to within ±0.01).`,
    ordered: false,
    reference: `SELECT SUM(CASE WHEN s.plan = 'annual' THEN s.price_gbp / 12.0 ELSE s.price_gbp END) AS mrr_gbp
FROM subscriptions s
JOIN customers c ON c.customer_id = s.customer_id
WHERE c.is_test = 0 AND c.deleted_at IS NULL
  AND s.started_on <= '2026-03-31'
  AND (s.ended_on IS NULL OR s.ended_on > '2026-03-31')`,
    solve: (t) => {
      const c = ctx(t);
      let sum = 0;
      for (const [, cust, plan, price, start, end] of t.subscriptions!.rows) {
        if (!c.real.has(Number(cust))) continue;
        if (String(start) <= '2026-03-31' && (end === null || String(end) > '2026-03-31')) sum += plan === 'annual' ? Number(price) / 12 : Number(price);
      }
      return [[r2(sum)]];
    },
    careless: [
      { why: 'counts the annual price as if it were monthly', sql: `SELECT ROUND(SUM(s.price_gbp), 2) FROM subscriptions s JOIN customers c ON c.customer_id = s.customer_id WHERE c.is_test = 0 AND c.deleted_at IS NULL AND s.started_on <= '2026-03-31' AND (s.ended_on IS NULL OR s.ended_on > '2026-03-31')` },
      { why: 'treats ended_on as the last covered day', sql: `SELECT ROUND(SUM(CASE WHEN s.plan = 'annual' THEN s.price_gbp / 12.0 ELSE s.price_gbp END), 2) FROM subscriptions s JOIN customers c ON c.customer_id = s.customer_id WHERE c.is_test = 0 AND c.deleted_at IS NULL AND s.started_on <= '2026-03-31' AND (s.ended_on IS NULL OR s.ended_on >= '2026-03-31')` },
      { why: 'includes test and deleted accounts', sql: `SELECT ROUND(SUM(CASE WHEN plan = 'annual' THEN price_gbp / 12.0 ELSE price_gbp END), 2) FROM subscriptions WHERE started_on <= '2026-03-31' AND (ended_on IS NULL OR ended_on > '2026-03-31')` },
    ],
    notes: 'Planted: a subscription ending on 31 March (not active), one starting on 31 March (active), legacy £5.99 monthly prices, annual plans at £69 per year.',
  },
  {
    id: 'q07',
    difficulty: 'medium',
    text: `From Callum (head of retail): "Which location had the highest refund rate last quarter (Q1 2026)?"

Refund rate of a store = (total refunds, made at any time, on its sales placed in Q1 2026) ÷ (total order value of those same sales), both in GBP, as a percentage. Return one row with two columns: the store name and its refund rate as a percentage rounded to 1 decimal place (for example 7.4 for 7.4%; compared to within ±0.06).`,
    ordered: false,
    tolerance: 0.06,
    reference: `WITH ${SALES},
${VALUE},
${REFUNDED},
per_store AS (
  SELECT o.store_id,
         SUM(COALESCE(r.refunded, 0) * ${RATE}) AS refunded_gbp,
         SUM(COALESCE(v.value, 0) * ${RATE}) AS value_gbp
  FROM sales o
  LEFT JOIN order_value v ON v.order_id = o.order_id
  LEFT JOIN order_refunds r ON r.order_id = o.order_id
  WHERE o.ordered_at >= '2026-01-01' AND o.ordered_at < '2026-04-01'
  GROUP BY o.store_id
)
SELECT s.name, 100.0 * p.refunded_gbp / p.value_gbp AS refund_rate_pct
FROM per_store p JOIN stores s ON s.store_id = p.store_id
ORDER BY p.refunded_gbp / p.value_gbp DESC
LIMIT 1`,
    solve: (t) => {
      const c = ctx(t);
      const agg = new Map<number, [number, number]>();
      for (const s of c.sales) {
        if (!inRange(s.at, '2026-01-01', '2026-04-01')) continue;
        const k = c.rate(s.currency, s.at);
        const a = agg.get(s.store) ?? [0, 0];
        a[0] += (c.refunded.get(s.id) ?? 0) * k;
        a[1] += (c.value.get(s.id) ?? 0) * k;
        agg.set(s.store, a);
      }
      const best = [...agg.entries()].sort((a, b) => b[1][0] / b[1][1] - a[1][0] / a[1][1])[0]!;
      return [[c.storeName.get(best[0])!, r1((100 * best[1][0]) / best[1][1])]];
    },
    careless: [
      { why: 'uses refunds made in Q1 instead of refunds on Q1 sales', sql: `WITH v AS (SELECT o.store_id, SUM((oi.quantity * oi.unit_price - COALESCE(oi.line_discount, 0)) * CASE WHEN o.currency = 'GBP' THEN 1 ELSE f.gbp_per_unit END) AS value FROM orders o JOIN order_items oi ON oi.order_id = o.order_id LEFT JOIN fx_rates f ON f.currency = o.currency AND f.month = substr(o.ordered_at, 1, 7) LEFT JOIN customers c ON c.customer_id = o.customer_id WHERE o.status = 'completed' AND o.deleted_at IS NULL AND (o.customer_id IS NULL OR (c.is_test = 0 AND c.deleted_at IS NULL)) AND o.ordered_at >= '2026-01-01' AND o.ordered_at < '2026-04-01' GROUP BY o.store_id), r AS (SELECT o.store_id, SUM(rf.amount * CASE WHEN o.currency = 'GBP' THEN 1 ELSE f.gbp_per_unit END) AS refunded FROM refunds rf JOIN orders o ON o.order_id = rf.order_id LEFT JOIN fx_rates f ON f.currency = o.currency AND f.month = substr(o.ordered_at, 1, 7) LEFT JOIN customers c ON c.customer_id = o.customer_id WHERE o.status = 'completed' AND o.deleted_at IS NULL AND (o.customer_id IS NULL OR (c.is_test = 0 AND c.deleted_at IS NULL)) AND rf.refunded_at >= '2026-01-01' AND rf.refunded_at < '2026-04-01' GROUP BY o.store_id) SELECT s.name, ROUND(100.0 * r.refunded / v.value, 1) AS rate FROM v JOIN r ON r.store_id = v.store_id JOIN stores s ON s.store_id = v.store_id ORDER BY r.refunded / v.value DESC LIMIT 1` },
      { why: 'joins items and refunds together, inflating both sides', sql: `SELECT s.name, ROUND(100.0 * SUM(COALESCE(r.amount, 0)) / SUM(oi.quantity * oi.unit_price - COALESCE(oi.line_discount, 0)), 1) AS rate FROM orders o JOIN stores s ON s.store_id = o.store_id JOIN order_items oi ON oi.order_id = o.order_id LEFT JOIN refunds r ON r.order_id = o.order_id LEFT JOIN customers c ON c.customer_id = o.customer_id WHERE o.status = 'completed' AND o.deleted_at IS NULL AND (o.customer_id IS NULL OR (c.is_test = 0 AND c.deleted_at IS NULL)) AND o.ordered_at >= '2026-01-01' AND o.ordered_at < '2026-04-01' GROUP BY s.store_id ORDER BY rate DESC LIMIT 1` },
    ],
    notes: 'Ratio of sums per store, items and refunds aggregated per order before joining, both sides converted to GBP. Planted: December orders refunded in January.',
  },
  {
    id: 'q08',
    difficulty: 'medium',
    text: `From Dmitri (area manager): "Give me a Q1 2026 sales leaderboard for the shop staff, ranked within each shop."

Count every sale placed in Q1 2026 that has a staff_id, crediting the person who served it. Staff sometimes cover shifts at another shop: rank each person among the staff of the shop they belong to (staff.store_id), counting all their sales wherever they were made. Net sales = net value in GBP (rule 6 and 7). Rank 1 = highest net sales in that shop; people with equal net sales share a rank and the next rank is skipped (1, 1, 3). Include only staff with at least one such sale. Return one row per person with four columns: their shop's name, their full_name, their net sales in GBP rounded to 2 decimal places (±0.01), and their rank.`,
    ordered: false,
    reference: `WITH ${SALES},
${VALUE},
${REFUNDED},
per_person AS (
  SELECT o.staff_id, SUM(${NET}) AS net_gbp
  FROM sales o
  LEFT JOIN order_value v ON v.order_id = o.order_id
  LEFT JOIN order_refunds r ON r.order_id = o.order_id
  WHERE o.staff_id IS NOT NULL
    AND o.ordered_at >= '2026-01-01' AND o.ordered_at < '2026-04-01'
  GROUP BY o.staff_id
)
SELECT st.name AS store, p.full_name, pp.net_gbp AS net_sales_gbp,
       RANK() OVER (PARTITION BY p.store_id ORDER BY pp.net_gbp DESC) AS rank_in_store
FROM per_person pp
JOIN staff p ON p.staff_id = pp.staff_id
JOIN stores st ON st.store_id = p.store_id`,
    solve: (t) => {
      const c = ctx(t);
      const per = new Map<number, number>();
      for (const s of c.sales) if (s.staff !== null && inRange(s.at, '2026-01-01', '2026-04-01')) per.set(s.staff, (per.get(s.staff) ?? 0) + netGbp(c, s));
      const staff = new Map(t.staff!.rows.map((r) => [Number(r[0]), r]));
      const out: Cell[][] = [];
      for (const [id, v] of per) {
        const home = Number(staff.get(id)![1]);
        const rank = 1 + [...per.entries()].filter(([o, w]) => Number(staff.get(o)![1]) === home && w > v + 1e-9).length;
        out.push([c.storeName.get(home)!, staff.get(id)![2], r2(v), rank]);
      }
      return out;
    },
    careless: [
      { why: 'ranks within the shop where each sale was made, not the person’s own shop', sql: `WITH v AS (SELECT order_id, SUM(quantity * unit_price - COALESCE(line_discount, 0)) AS value FROM order_items GROUP BY order_id), r AS (SELECT order_id, SUM(amount) AS refunded FROM refunds GROUP BY order_id), x AS (SELECT o.store_id, o.staff_id, SUM((COALESCE(v.value, 0) - COALESCE(r.refunded, 0)) * CASE WHEN o.currency = 'GBP' THEN 1 ELSE (SELECT gbp_per_unit FROM fx_rates f WHERE f.currency = o.currency AND f.month = substr(o.ordered_at, 1, 7)) END) AS net FROM orders o LEFT JOIN v ON v.order_id = o.order_id LEFT JOIN r ON r.order_id = o.order_id LEFT JOIN customers c ON c.customer_id = o.customer_id WHERE o.status = 'completed' AND o.deleted_at IS NULL AND (o.customer_id IS NULL OR (c.is_test = 0 AND c.deleted_at IS NULL)) AND o.staff_id IS NOT NULL AND o.ordered_at >= '2026-01-01' AND o.ordered_at < '2026-04-01' GROUP BY o.store_id, o.staff_id) SELECT s.name, p.full_name, ROUND(x.net, 2), RANK() OVER (PARTITION BY x.store_id ORDER BY x.net DESC) FROM x JOIN staff p ON p.staff_id = x.staff_id JOIN stores s ON s.store_id = x.store_id` },
      { why: 'one overall ranking instead of a ranking per shop', sql: `WITH v AS (SELECT order_id, SUM(quantity * unit_price - COALESCE(line_discount, 0)) AS value FROM order_items GROUP BY order_id), r AS (SELECT order_id, SUM(amount) AS refunded FROM refunds GROUP BY order_id), x AS (SELECT o.staff_id, SUM((COALESCE(v.value, 0) - COALESCE(r.refunded, 0)) * CASE WHEN o.currency = 'GBP' THEN 1 ELSE (SELECT gbp_per_unit FROM fx_rates f WHERE f.currency = o.currency AND f.month = substr(o.ordered_at, 1, 7)) END) AS net FROM orders o LEFT JOIN v ON v.order_id = o.order_id LEFT JOIN r ON r.order_id = o.order_id LEFT JOIN customers c ON c.customer_id = o.customer_id WHERE o.status = 'completed' AND o.deleted_at IS NULL AND (o.customer_id IS NULL OR (c.is_test = 0 AND c.deleted_at IS NULL)) AND o.staff_id IS NOT NULL AND o.ordered_at >= '2026-01-01' AND o.ordered_at < '2026-04-01' GROUP BY o.staff_id) SELECT s.name, p.full_name, ROUND(x.net, 2), RANK() OVER (ORDER BY x.net DESC) FROM x JOIN staff p ON p.staff_id = x.staff_id JOIN stores s ON s.store_id = p.store_id` },
      { why: 'gross sales, ignoring refunds', sql: `WITH v AS (SELECT order_id, SUM(quantity * unit_price - COALESCE(line_discount, 0)) AS value FROM order_items GROUP BY order_id), x AS (SELECT o.staff_id, SUM(COALESCE(v.value, 0) * CASE WHEN o.currency = 'GBP' THEN 1 ELSE (SELECT gbp_per_unit FROM fx_rates f WHERE f.currency = o.currency AND f.month = substr(o.ordered_at, 1, 7)) END) AS net FROM orders o LEFT JOIN v ON v.order_id = o.order_id LEFT JOIN customers c ON c.customer_id = o.customer_id WHERE o.status = 'completed' AND o.deleted_at IS NULL AND (o.customer_id IS NULL OR (c.is_test = 0 AND c.deleted_at IS NULL)) AND o.staff_id IS NOT NULL AND o.ordered_at >= '2026-01-01' AND o.ordered_at < '2026-04-01' GROUP BY o.staff_id) SELECT s.name, p.full_name, ROUND(x.net, 2), RANK() OVER (PARTITION BY p.store_id ORDER BY x.net DESC) FROM x JOIN staff p ON p.staff_id = x.staff_id JOIN stores s ON s.store_id = p.store_id` },
    ],
    notes: 'RANK() partitioned by the person’s own shop; about 8% of shop sales are served by staff covering at another shop.',
  },
  {
    id: 'q09',
    difficulty: 'hard',
    text: `From Hannah (finance): "Show me net revenue per month for January to June 2026, with the change on the month before."

For each calendar month from 2026-01 to 2026-06: the month as text 'YYYY-MM', the total net revenue in GBP of all sales placed in that month rounded to 2 decimal places, and the percentage change from the previous calendar month's net revenue rounded to 1 decimal place (January is compared with December 2025). Return six rows in month order, with three columns. Values are compared to within ±0.06.`,
    ordered: true,
    tolerance: 0.06,
    reference: `WITH ${SALES},
${VALUE},
${REFUNDED},
monthly AS (
  SELECT substr(o.ordered_at, 1, 7) AS month, SUM(${NET}) AS net_gbp
  FROM sales o
  LEFT JOIN order_value v ON v.order_id = o.order_id
  LEFT JOIN order_refunds r ON r.order_id = o.order_id
  GROUP BY substr(o.ordered_at, 1, 7)
),
with_prev AS (
  SELECT month, net_gbp, LAG(net_gbp) OVER (ORDER BY month) AS prev_gbp FROM monthly
)
SELECT month, net_gbp AS net_revenue_gbp, 100.0 * (net_gbp - prev_gbp) / prev_gbp AS change_pct
FROM with_prev
WHERE month BETWEEN '2026-01' AND '2026-06'
ORDER BY month`,
    solve: (t) => {
      const c = ctx(t);
      const by = new Map<string, number>();
      for (const s of c.sales) by.set(s.at.slice(0, 7), (by.get(s.at.slice(0, 7)) ?? 0) + netGbp(c, s));
      const months = ['2025-12', '2026-01', '2026-02', '2026-03', '2026-04', '2026-05', '2026-06'];
      return months.slice(1).map((m, i) => [m, r2(by.get(m)!), r1((100 * (by.get(m)! - by.get(months[i]!)!)) / by.get(months[i]!)!)]);
    },
    careless: [
      { why: 'filters to January–June before LAG, so January has no previous month', sql: `WITH v AS (SELECT order_id, SUM(quantity * unit_price - COALESCE(line_discount, 0)) AS value FROM order_items GROUP BY order_id), r AS (SELECT order_id, SUM(amount) AS refunded FROM refunds GROUP BY order_id), m AS (SELECT substr(o.ordered_at, 1, 7) AS month, SUM((COALESCE(v.value, 0) - COALESCE(r.refunded, 0)) * CASE WHEN o.currency = 'GBP' THEN 1 ELSE (SELECT gbp_per_unit FROM fx_rates f WHERE f.currency = o.currency AND f.month = substr(o.ordered_at, 1, 7)) END) AS net FROM orders o LEFT JOIN v ON v.order_id = o.order_id LEFT JOIN r ON r.order_id = o.order_id LEFT JOIN customers c ON c.customer_id = o.customer_id WHERE o.status = 'completed' AND o.deleted_at IS NULL AND (o.customer_id IS NULL OR (c.is_test = 0 AND c.deleted_at IS NULL)) AND o.ordered_at >= '2026-01-01' GROUP BY 1) SELECT month, ROUND(net, 2), ROUND(100.0 * (net - LAG(net) OVER (ORDER BY month)) / LAG(net) OVER (ORDER BY month), 1) FROM m WHERE month <= '2026-06' ORDER BY month` },
      { why: 'change as a ratio of the new month instead of the previous one', sql: `WITH v AS (SELECT order_id, SUM(quantity * unit_price - COALESCE(line_discount, 0)) AS value FROM order_items GROUP BY order_id), r AS (SELECT order_id, SUM(amount) AS refunded FROM refunds GROUP BY order_id), m AS (SELECT substr(o.ordered_at, 1, 7) AS month, SUM((COALESCE(v.value, 0) - COALESCE(r.refunded, 0)) * CASE WHEN o.currency = 'GBP' THEN 1 ELSE (SELECT gbp_per_unit FROM fx_rates f WHERE f.currency = o.currency AND f.month = substr(o.ordered_at, 1, 7)) END) AS net FROM orders o LEFT JOIN v ON v.order_id = o.order_id LEFT JOIN r ON r.order_id = o.order_id LEFT JOIN customers c ON c.customer_id = o.customer_id WHERE o.status = 'completed' AND o.deleted_at IS NULL AND (o.customer_id IS NULL OR (c.is_test = 0 AND c.deleted_at IS NULL)) GROUP BY 1), w AS (SELECT month, net, LAG(net) OVER (ORDER BY month) AS prev FROM m) SELECT month, ROUND(net, 2), ROUND(100.0 * (net - prev) / net, 1) FROM w WHERE month BETWEEN '2026-01' AND '2026-06' ORDER BY month` },
      { why: 'gross revenue in mixed currencies (no refunds, no conversion)', sql: `WITH m AS (SELECT substr(o.ordered_at, 1, 7) AS month, SUM(oi.quantity * oi.unit_price - COALESCE(oi.line_discount, 0)) AS net FROM orders o JOIN order_items oi ON oi.order_id = o.order_id LEFT JOIN customers c ON c.customer_id = o.customer_id WHERE o.status = 'completed' AND o.deleted_at IS NULL AND (o.customer_id IS NULL OR (c.is_test = 0 AND c.deleted_at IS NULL)) GROUP BY 1), w AS (SELECT month, net, LAG(net) OVER (ORDER BY month) AS prev FROM m) SELECT month, ROUND(net, 2), ROUND(100.0 * (net - prev) / prev, 1) FROM w WHERE month BETWEEN '2026-01' AND '2026-06' ORDER BY month` },
    ],
    notes: 'LAG over every month (including December 2025), filtered afterwards.',
  },
  {
    id: 'q10',
    difficulty: 'hard',
    text: `From Kwame (CRM): "I want the customers who'd bought from us at least twice, then had a refund, and never bought again after that first refund."

A customer qualifies when (a) they have at least two sales, (b) at least one refund was made on one of their sales, and (c) none of their sales was placed after the moment (refunded_at) of their first such refund. Return one row per customer with three columns: customer_id, full_name, and the date of their first refund as text 'YYYY-MM-DD'.`,
    ordered: false,
    reference: `WITH ${SALES},
per_customer AS (
  SELECT customer_id, COUNT(*) AS n_sales, MAX(ordered_at) AS last_sale
  FROM sales WHERE customer_id IS NOT NULL GROUP BY customer_id
),
first_refund AS (
  SELECT o.customer_id, MIN(r.refunded_at) AS first_refund_at
  FROM refunds r JOIN sales o ON o.order_id = r.order_id
  WHERE o.customer_id IS NOT NULL
  GROUP BY o.customer_id
)
SELECT c.customer_id, c.full_name, date(f.first_refund_at) AS first_refund_on
FROM per_customer p
JOIN first_refund f ON f.customer_id = p.customer_id
JOIN customers c ON c.customer_id = p.customer_id
WHERE p.n_sales >= 2 AND p.last_sale <= f.first_refund_at`,
    solve: (t) => {
      const c = ctx(t);
      const by = new Map<number, string[]>();
      const saleCust = new Map<number, number>();
      for (const s of c.sales) if (s.cust !== null) {
        by.set(s.cust, [...(by.get(s.cust) ?? []), s.at]);
        saleCust.set(s.id, s.cust);
      }
      const first = new Map<number, string>();
      for (const r of c.refundRows) {
        const cu = saleCust.get(r.order);
        if (cu === undefined) continue;
        if (!first.has(cu) || r.at < first.get(cu)!) first.set(cu, r.at);
      }
      const out: Cell[][] = [];
      for (const [cu, ats] of by) {
        const f = first.get(cu);
        if (ats.length >= 2 && f && ats.every((a) => a <= f)) out.push([cu, c.custName.get(cu)!, f.slice(0, 10)]);
      }
      return out;
    },
    careless: [
      { why: 'compares with the LAST refund instead of the first', sql: `WITH s AS (SELECT o.* FROM orders o JOIN customers c ON c.customer_id = o.customer_id WHERE o.status = 'completed' AND o.deleted_at IS NULL AND c.is_test = 0 AND c.deleted_at IS NULL), p AS (SELECT customer_id, COUNT(*) AS n, MAX(ordered_at) AS last_sale FROM s GROUP BY customer_id), f AS (SELECT s.customer_id, MAX(r.refunded_at) AS fr FROM refunds r JOIN s ON s.order_id = r.order_id GROUP BY s.customer_id) SELECT c.customer_id, c.full_name, date(f.fr) FROM p JOIN f ON f.customer_id = p.customer_id JOIN customers c ON c.customer_id = p.customer_id WHERE p.n >= 2 AND p.last_sale <= f.fr` },
      { why: 'counts cancelled and mistaken orders as purchases', sql: `WITH s AS (SELECT o.* FROM orders o JOIN customers c ON c.customer_id = o.customer_id WHERE c.is_test = 0 AND c.deleted_at IS NULL), p AS (SELECT customer_id, COUNT(*) AS n, MAX(ordered_at) AS last_sale FROM s GROUP BY customer_id), f AS (SELECT s.customer_id, MIN(r.refunded_at) AS fr FROM refunds r JOIN s ON s.order_id = r.order_id GROUP BY s.customer_id) SELECT c.customer_id, c.full_name, date(f.fr) FROM p JOIN f ON f.customer_id = p.customer_id JOIN customers c ON c.customer_id = p.customer_id WHERE p.n >= 2 AND p.last_sale <= f.fr` },
      { why: 'only checks that the refunded order was their last order (ignores the refund date)', sql: `WITH s AS (SELECT o.* FROM orders o JOIN customers c ON c.customer_id = o.customer_id WHERE o.status = 'completed' AND o.deleted_at IS NULL AND c.is_test = 0 AND c.deleted_at IS NULL), p AS (SELECT customer_id, COUNT(*) AS n, MAX(ordered_at) AS last_sale FROM s GROUP BY customer_id), f AS (SELECT s.customer_id, MIN(r.refunded_at) AS fr, MAX(s.ordered_at) AS refunded_order_at FROM refunds r JOIN s ON s.order_id = r.order_id GROUP BY s.customer_id) SELECT c.customer_id, c.full_name, date(f.fr) FROM p JOIN f ON f.customer_id = p.customer_id JOIN customers c ON c.customer_id = p.customer_id WHERE p.n >= 2 AND p.last_sale = f.refunded_order_at` },
    ],
    notes: 'Per-customer aggregates of sales and of refunds (by refund time), compared. Planted: loyal customers whose last purchase is refunded, customers with refunds followed by more purchases, refunds on deleted duplicate orders.',
  },
  {
    id: 'q11',
    difficulty: 'hard',
    text: `From Ines (buying): "Which two products are most often bought together? I'm planning a bundle."

Look at the sales placed from 1 January to 30 June 2026. A pair of different products is "bought together" in a sale when both appear on that sale (a product on two lines of the same sale still counts once). Find the pair (or pairs, if several are tied) that appear together in the most sales. Return one row per such pair with three columns: the name of the product with the lower product_id, the name of the other product, and the number of sales containing both.`,
    ordered: false,
    reference: `WITH ${SALES},
sale_products AS (
  SELECT DISTINCT oi.order_id, oi.product_id
  FROM order_items oi JOIN sales o ON o.order_id = oi.order_id
  WHERE o.ordered_at >= '2026-01-01' AND o.ordered_at < '2026-07-01'
),
pairs AS (
  SELECT a.product_id AS p1, b.product_id AS p2, COUNT(*) AS n
  FROM sale_products a JOIN sale_products b ON b.order_id = a.order_id AND a.product_id < b.product_id
  GROUP BY a.product_id, b.product_id
)
SELECT x.name, y.name, p.n
FROM pairs p
JOIN products x ON x.product_id = p.p1
JOIN products y ON y.product_id = p.p2
WHERE p.n = (SELECT MAX(n) FROM pairs)`,
    solve: (t) => {
      const c = ctx(t);
      const ids = new Set(c.sales.filter((s) => inRange(s.at, '2026-01-01', '2026-07-01')).map((s) => s.id));
      const per = new Map<number, Set<number>>();
      for (const [o, , p] of t.order_items!.rows) if (ids.has(Number(o))) per.set(Number(o), (per.get(Number(o)) ?? new Set()).add(Number(p)));
      const cnt = new Map<string, number>();
      for (const set of per.values()) {
        const ps = [...set].sort((a, b) => a - b);
        for (let i = 0; i < ps.length; i++) for (let j = i + 1; j < ps.length; j++) cnt.set(`${ps[i]}|${ps[j]}`, (cnt.get(`${ps[i]}|${ps[j]}`) ?? 0) + 1);
      }
      const max = Math.max(...cnt.values());
      return [...cnt.entries()].filter(([, n]) => n === max).map(([k, n]) => {
        const [a, b] = k.split('|').map(Number);
        return [c.productName.get(a!)!, c.productName.get(b!)!, n];
      });
    },
    careless: [
      { why: 'counts line pairs, so a product on two lines counts twice', sql: `WITH s AS (SELECT o.order_id FROM orders o LEFT JOIN customers c ON c.customer_id = o.customer_id WHERE o.status = 'completed' AND o.deleted_at IS NULL AND (o.customer_id IS NULL OR (c.is_test = 0 AND c.deleted_at IS NULL)) AND o.ordered_at >= '2026-01-01' AND o.ordered_at < '2026-07-01'), p AS (SELECT a.product_id AS p1, b.product_id AS p2, COUNT(*) AS n FROM order_items a JOIN order_items b ON b.order_id = a.order_id AND a.product_id < b.product_id WHERE a.order_id IN (SELECT order_id FROM s) GROUP BY 1, 2) SELECT x.name, y.name, p.n FROM p JOIN products x ON x.product_id = p.p1 JOIN products y ON y.product_id = p.p2 WHERE p.n = (SELECT MAX(n) FROM p)` },
      { why: 'LIMIT 1 drops tied pairs', sql: `WITH s AS (SELECT o.order_id FROM orders o LEFT JOIN customers c ON c.customer_id = o.customer_id WHERE o.status = 'completed' AND o.deleted_at IS NULL AND (o.customer_id IS NULL OR (c.is_test = 0 AND c.deleted_at IS NULL)) AND o.ordered_at >= '2026-01-01' AND o.ordered_at < '2026-07-01'), sp AS (SELECT DISTINCT order_id, product_id FROM order_items WHERE order_id IN (SELECT order_id FROM s)), p AS (SELECT a.product_id AS p1, b.product_id AS p2, COUNT(*) AS n FROM sp a JOIN sp b ON b.order_id = a.order_id AND a.product_id < b.product_id GROUP BY 1, 2) SELECT x.name, y.name, p.n FROM p JOIN products x ON x.product_id = p.p1 JOIN products y ON y.product_id = p.p2 ORDER BY p.n DESC LIMIT 1` },
      { why: 'every order, including cancelled and test ones', sql: `WITH sp AS (SELECT DISTINCT oi.order_id, oi.product_id FROM order_items oi JOIN orders o ON o.order_id = oi.order_id WHERE o.ordered_at >= '2026-01-01' AND o.ordered_at < '2026-07-01'), p AS (SELECT a.product_id AS p1, b.product_id AS p2, COUNT(*) AS n FROM sp a JOIN sp b ON b.order_id = a.order_id AND a.product_id < b.product_id GROUP BY 1, 2) SELECT x.name, y.name, p.n FROM p JOIN products x ON x.product_id = p.p1 JOIN products y ON y.product_id = p.p2 WHERE p.n = (SELECT MAX(n) FROM p)` },
    ],
    notes: 'Distinct (sale, product) first, then a self-join with a.product_id < b.product_id, keeping every pair tied for the most. Planted: gas canisters often appear on two lines of one sale.',
  },
  {
    id: 'q12',
    difficulty: 'hard',
    text: `From Siobhan (Trailhead Club): "Which members churned in Q1 2026 - their membership ended and they didn't come back within a month?"

Look at the real customers' subscriptions whose ended_on falls between 2026-01-01 and 2026-03-31 inclusive. Such an ending is NOT churn when the same customer has another subscription that started on or before (ended_on + 30 days) and that had not itself ended by the old ended_on (its ended_on is NULL or later than the old ended_on) - that covers switching plans, overlapping renewals and coming back within 30 days. Every other such ending is churn. Return one row per churned ending with three columns: customer_id, full_name and the ended_on date ('YYYY-MM-DD').`,
    ordered: false,
    reference: `SELECT c.customer_id, c.full_name, s.ended_on
FROM subscriptions s
JOIN customers c ON c.customer_id = s.customer_id
WHERE c.is_test = 0 AND c.deleted_at IS NULL
  AND s.ended_on >= '2026-01-01' AND s.ended_on <= '2026-03-31'
  AND NOT EXISTS (
    SELECT 1 FROM subscriptions t
    WHERE t.customer_id = s.customer_id
      AND t.subscription_id <> s.subscription_id
      AND t.started_on <= date(s.ended_on, '+30 days')
      AND (t.ended_on IS NULL OR t.ended_on > s.ended_on)
  )`,
    solve: (t) => {
      const c = ctx(t);
      const subs = t.subscriptions!.rows.map((r) => ({ id: Number(r[0]), cust: Number(r[1]), start: String(r[4]), end: r[5] === null ? null : String(r[5]) }));
      const out: Cell[][] = [];
      for (const s of subs) {
        if (!c.real.has(s.cust) || s.end === null || s.end < '2026-01-01' || s.end > '2026-03-31') continue;
        const back = subs.some((o) => o.cust === s.cust && o.id !== s.id && o.start <= addDays(s.end!, 30) && (o.end === null || o.end > s.end!));
        if (!back) out.push([s.cust, c.custName.get(s.cust)!, s.end]);
      }
      return out;
    },
    careless: [
      { why: 'only a new subscription starting after the end counts as coming back (misses early switches)', sql: `SELECT c.customer_id, c.full_name, s.ended_on FROM subscriptions s JOIN customers c ON c.customer_id = s.customer_id WHERE c.is_test = 0 AND c.deleted_at IS NULL AND s.ended_on BETWEEN '2026-01-01' AND '2026-03-31' AND NOT EXISTS (SELECT 1 FROM subscriptions t WHERE t.customer_id = s.customer_id AND t.started_on >= s.ended_on AND t.started_on <= date(s.ended_on, '+30 days'))` },
      { why: 'a 30-day window that excludes the 30th day', sql: `SELECT c.customer_id, c.full_name, s.ended_on FROM subscriptions s JOIN customers c ON c.customer_id = s.customer_id WHERE c.is_test = 0 AND c.deleted_at IS NULL AND s.ended_on BETWEEN '2026-01-01' AND '2026-03-31' AND NOT EXISTS (SELECT 1 FROM subscriptions t WHERE t.customer_id = s.customer_id AND t.subscription_id <> s.subscription_id AND t.started_on < date(s.ended_on, '+30 days') AND (t.ended_on IS NULL OR t.ended_on > s.ended_on))` },
      { why: 'every ending in Q1 counts as churn', sql: `SELECT c.customer_id, c.full_name, s.ended_on FROM subscriptions s JOIN customers c ON c.customer_id = s.customer_id WHERE c.is_test = 0 AND c.deleted_at IS NULL AND s.ended_on BETWEEN '2026-01-01' AND '2026-03-31'` },
    ],
    notes: 'Correlated NOT EXISTS with an inclusive +30-day window and an overlap condition. Planted: a return after exactly 30 days (not churn), after 31 days (churn), and a new plan that started before the old one ended (not churn).',
  },
];

/** The sample rows shown in the prompt: a few per table, chosen to show the messy parts. */
export function sampleIds(t: Tables): Record<string, Array<string | number>> {
  const orders = t.orders!.rows;
  const pickOrder = (f: (r: Cell[]) => boolean) => orders.find(f)?.[0];
  const o = [
    pickOrder((r) => r[2] === 1 && r[5] === 'completed' && r[7] === null),
    pickOrder((r) => r[1] === null && r[5] === 'completed'),
    pickOrder((r) => r[2] === 4 && r[5] === 'completed'),
    pickOrder((r) => r[2] === 5 && r[6] === 'EUR' && r[1] !== null),
    pickOrder((r) => r[5] === 'cancelled'),
    pickOrder((r) => r[7] !== null),
  ].filter((x): x is number => x !== undefined);
  const firstOrder = o[0]!;
  const itemKeys = t.order_items!.rows.filter((r) => r[0] === firstOrder || r[0] === o[2]).slice(0, 4);
  const disc = t.order_items!.rows.find((r) => r[5] !== null && !itemKeys.includes(r));
  const refundIds = [t.refunds!.rows[0]![0]!, t.refunds!.rows.find((r) => r[4] === null)?.[0] ?? t.refunds!.rows[1]![0]!];
  const cust = t.customers!.rows;
  return {
    stores: [1, 2, 3, 4, 5],
    staff: [t.staff!.rows[0]![0]!, t.staff!.rows[1]![0]!, t.staff!.rows.find((r) => r[6] !== null)?.[0] ?? t.staff!.rows[2]![0]!],
    customers: [cust[0]![0]!, cust[1]![0]!, cust.find((r) => r[6] !== null)![0]!, cust.find((r) => r[5] === 1)![0]!],
    products: [1, 11, 12],
    orders: o,
    order_items: [...itemKeys, ...(disc ? [disc] : [])].map((r) => `${r[0]}|${r[1]}`),
    refunds: refundIds,
    subscriptions: [t.subscriptions!.rows[0]![0]!, t.subscriptions!.rows.find((r) => r[2] === 'annual')![0]!, t.subscriptions!.rows.find((r) => r[5] !== null)![0]!],
    fx_rates: ['2026-03|EUR', '2026-04|EUR'],
  };
}

export const COMPANY = `You are helping the team at Northbound Outdoor, a UK outdoor-gear retailer with four shops (Leeds, Bristol, Glasgow and Dublin) and a website. Its sales database is SQLite. Below are the tables, the business rules every report must follow and a few sample rows. Today is Wednesday 1 July 2026.`;

export const DAYS = daysBetween;
