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
      if (k === 2 && rng.chance(0.6)) left = addDays('2026-02-10', rng.int(0, 38)); // left during the year
      if (k === 1 && (store === 1 || rng.chance(0.4))) started = addDays(store === 1 ? '2026-01-03' : '2026-01-10', rng.int(0, 50)); // new starter
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
  const custNames = names(cr, 190);
  const customers: Cell[][] = [];
  const cinfo = new Map<number, { home: number; country: string; signed: string; real: boolean }>();
  const N = 150 + cr.int(0, 20);
  const febExtra = cr.int(0, 14);
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
    for (let k = 0; k < 60 + r.int(0, 15); k++) {
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
    // The Leeds new starter has a strong first month (so the lower first-month commission threshold matters).
    {
      const starter = salesByStore.get(1)![2]!;
      const first = staffInfo.get(starter)!.started;
      const monthEnd = addDays(`${first.slice(0, 7)}-01`, 31).slice(0, 7) + '-01';
      for (let k = 0; k < 3; k++) {
        const at = randomTs(r, first, monthEnd, [10, 17]);
        if (at.slice(0, 7) !== first.slice(0, 7)) continue;
        const cust = r.pick([...cinfo.entries()].filter(([, v]) => v.real && v.signed < first).map(([id]) => id));
        makeOrder(cust, 1, at, 'completed', r, { staff: starter });
      }
    }
    // Website sales in the last hour of each month (the finance memo books them in the next month), with a
    // control sale just before 23:00.
    const realOld = [...cinfo.entries()].filter(([, v]) => v.real && v.signed < '2025-12-01').map(([k]) => k);
    for (const end of ['2025-12-31', '2026-01-31', '2026-02-28', '2026-03-31', '2026-04-30', '2026-05-31', '2026-06-30']) {
      const n = r.int(1, 3);
      for (let k = 0; k < n; k++) makeOrder(k === 0 && r.chance(0.5) ? null : r.pick(realOld), 5, fromMin(toMin(`${end} 23:00:00`) + r.int(0, 58), r.int(0, 59)), 'completed', r);
      makeOrder(r.pick(realOld), 5, fromMin(toMin(`${end} 22:00:00`) + r.int(30, 59), r.int(0, 59)), 'completed', r);
    }
    // Full refunds near the 14-day line: within 14 calendar days but more than 14 x 24 hours later, well within,
    // and a full refund split into one refund inside and one outside the window.
    const fullRefund = (o: { id: number; value: number }, at: string) => {
      if (at <= '2026-06-30 23:59:59') refunds.push([rid++, o.id, at, o.value, r.pick(['faulty', 'changed mind', 'wrong size'])]);
    };
    for (let k = 0; k < 3 + r.int(0, 3); k++) {
      const at = randomTs(r, '2026-01-02', '2026-06-10', [9, 12]);
      const cust = r.pick(realOld);
      const store = r.pick([1, 2, 4, 5]);
      const o = makeOrder(cust, store, at, 'completed', r);
      const kind = k % 3;
      if (kind === 0) fullRefund(o, fromMin(toMin(`${addDays(at.slice(0, 10), 14)} 15:00:00`) + r.int(0, 180), r.int(0, 59)));
      else if (kind === 1) fullRefund(o, fromMin(toMin(at) + r.int(2, 6) * 1440 + r.int(0, 300)));
      else {
        const half = money(o.value / 2);
        refunds.push([rid++, o.id, fromMin(toMin(at) + 4 * 1440), half, 'faulty']);
        const late = fromMin(toMin(at) + r.int(16, 25) * 1440);
        if (late <= '2026-06-30 23:59:59') refunds.push([rid++, o.id, late, money(o.value - half), 'faulty']);
      }
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
      if (s2 <= '2026-06-25') {
        const e2 = sr.chance(0.4) ? addDays(s2, sr.int(30, 120)) : null;
        addSub(cid, plan === 'monthly' ? 'annual' : 'monthly', s2, e2 !== null && e2 > '2026-06-30' ? null : e2);
        // Some come back a third time.
        if (e2 !== null && e2 <= '2026-06-01' && sr.chance(0.5)) {
          const s3 = addDays(e2, sr.pick([3, 20, 30, 31, 60]));
          if (s3 <= '2026-06-25') addSub(cid, 'monthly', s3, null);
        }
      }
    }
  }
  // Planted boundary cases for 31 March and for the churn question.
  const realIds = [...cinfo.entries()].filter(([, v]) => v.real && v.signed < '2025-11-01').map(([k]) => k);
  const pr = sr.fork('planted');
  // Planted members are customers without any other subscription, so each planted situation stands on its own.
  const free = realIds.filter((id) => !subs.some((x) => x[1] === id));
  const pickReal = () => {
    const id = pr.pick(free.length ? free : realIds);
    free.splice(free.indexOf(id), 1);
    return id;
  };
  addSub(pickReal(), 'monthly', '2025-11-14', '2026-03-31'); // ends on 31 March: not active that day
  {
    // A long annual plan with a short monthly one inside it: a later plan that starts more than 30 days after the
    // short one ended but within 30 days of the annual one's end still belongs to the same spell.
    const c = pickReal();
    const a0 = addDays('2025-05-01', pr.int(0, 40));
    addSub(c, 'annual', a0, addDays(a0, 365));
    const b0 = addDays(a0, pr.int(60, 90));
    addSub(c, 'monthly', b0, addDays(b0, 60));
    addSub(c, 'monthly', addDays(a0, 365 + pr.int(-20, 25)), null);
  }
  {
    // Still running, then an extra plan bought months later: one spell with no end.
    const c = pickReal();
    const a0 = addDays('2025-09-01', pr.int(0, 60));
    addSub(c, 'monthly', a0, null);
    addSub(c, 'annual', addDays(a0, pr.int(120, 200)), null);
  }
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

const Q09_REFERENCE = `WITH ${SALES},
${VALUE},
rated AS (
  SELECT o.order_id, o.customer_id, o.store_id, o.ordered_at, v.value,
         ${RATE} AS rate,
         CASE WHEN o.store_id = 5 AND substr(o.ordered_at, 12) >= '23:00:00'
                   AND strftime('%d', date(o.ordered_at, '+1 day')) = '01'
              THEN strftime('%Y-%m', date(o.ordered_at, '+1 day'))
              ELSE substr(o.ordered_at, 1, 7) END AS book_month
  FROM sales o JOIN order_value v ON v.order_id = o.order_id
),
early AS (
  SELECT r.order_id, SUM(r.amount) AS amount
  FROM refunds r JOIN rated o ON o.order_id = r.order_id
  WHERE date(r.refunded_at) <= date(o.ordered_at, '+14 days')
  GROUP BY r.order_id
),
counted AS (
  SELECT o.* FROM rated o LEFT JOIN early e ON e.order_id = o.order_id
  WHERE e.amount IS NULL OR ROUND(e.amount, 2) < ROUND(o.value, 2)
),
firsts AS (
  SELECT customer_id, MIN(book_month) AS first_month FROM counted WHERE customer_id IS NOT NULL GROUP BY customer_id
),
refunds_made AS (
  SELECT substr(r.refunded_at, 1, 7) AS month, SUM(r.amount * o.rate) AS refunds_gbp
  FROM refunds r JOIN rated o ON o.order_id = r.order_id
  GROUP BY substr(r.refunded_at, 1, 7)
),
booked AS (
  SELECT book_month AS month, COUNT(*) AS sales, SUM(value * rate) AS gross_gbp, COUNT(DISTINCT customer_id) AS active
  FROM counted GROUP BY book_month
)
SELECT b.month, b.sales, b.gross_gbp, COALESCE(m.refunds_gbp, 0) AS refunds_gbp,
       b.gross_gbp - COALESCE(m.refunds_gbp, 0) AS net_gbp, b.active,
       (SELECT COUNT(*) FROM firsts f WHERE f.first_month = b.month) AS new_customers
FROM booked b LEFT JOIN refunds_made m ON m.month = b.month
WHERE b.month BETWEEN '2026-01' AND '2026-06'
ORDER BY b.month`;

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
    text: `From Hannah (finance): "Net revenue in pounds for every location, month by month, for Q1 2026."

Return one row per store and month (2026-01, 2026-02, 2026-03) in which the store made at least one sale, with three columns: the store name, the month as 'YYYY-MM', and the store's net revenue in GBP for that month (rules 6 and 7: net of all refunds on those sales, whenever made; compared to within ±0.01). Order the rows by store name A–Z, then by month.`,
    ordered: true,
    reference: `WITH ${SALES},
${VALUE},
${REFUNDED}
SELECT s.name, substr(o.ordered_at, 1, 7) AS month, SUM(${NET}) AS net_revenue_gbp
FROM sales o
JOIN stores s ON s.store_id = o.store_id
LEFT JOIN order_value v ON v.order_id = o.order_id
LEFT JOIN order_refunds r ON r.order_id = o.order_id
WHERE o.ordered_at >= '2026-01-01' AND o.ordered_at < '2026-04-01'
GROUP BY s.store_id, s.name, substr(o.ordered_at, 1, 7)
ORDER BY s.name, month`,
    solve: (t) => {
      const c = ctx(t);
      const by = new Map<string, number>();
      for (const s of c.sales) if (inRange(s.at, '2026-01-01', '2026-04-01')) {
        const k = `${c.storeName.get(s.store)}|${s.at.slice(0, 7)}`;
        by.set(k, (by.get(k) ?? 0) + netGbp(c, s));
      }
      return [...by.entries()].sort((a, b) => (a[0] < b[0] ? -1 : 1)).map(([k, v]) => [...k.split('|'), r2(v)]);
    },
    careless: [],
    notes: 'Aggregate items and refunds separately per order before joining (fan-out), COALESCE the NULL discounts, convert by the order currency at the order month rate, sort by name then month.',
  },
  {
    id: 'q06',
    difficulty: 'medium',
    text: `From Siobhan (Trailhead Club): "How did monthly recurring revenue move through the first half of 2026? I want the figure on the last day of every month."

Monthly recurring revenue on a day = the total monthly value of the real customers' subscriptions active on that day (rules 1 and 9). Return six rows, one for the last day of each month from January to June 2026, in date order, with two columns: the date ('YYYY-MM-DD') and the amount in GBP (±0.01).`,
    ordered: true,
    reference: `WITH month_ends(day) AS (
  VALUES ('2026-01-31'), ('2026-02-28'), ('2026-03-31'), ('2026-04-30'), ('2026-05-31'), ('2026-06-30')
)
SELECT m.day, SUM(CASE WHEN s.plan = 'annual' THEN s.price_gbp / 12.0 ELSE s.price_gbp END) AS mrr_gbp
FROM month_ends m
JOIN subscriptions s ON s.started_on <= m.day AND (s.ended_on IS NULL OR s.ended_on > m.day)
JOIN customers c ON c.customer_id = s.customer_id
WHERE c.is_test = 0 AND c.deleted_at IS NULL
GROUP BY m.day
ORDER BY m.day`,
    solve: (t) => {
      const c = ctx(t);
      return ['2026-01-31', '2026-02-28', '2026-03-31', '2026-04-30', '2026-05-31', '2026-06-30'].map((d) => {
        let sum = 0;
        for (const [, cust, plan, price, start, end] of t.subscriptions!.rows) {
          if (!c.real.has(Number(cust))) continue;
          if (String(start) <= d && (end === null || String(end) > d)) sum += plan === 'annual' ? Number(price) / 12 : Number(price);
        }
        return [d, r2(sum)];
      });
    },
    careless: [],
    notes: 'Active-on-a-day test per month end. Planted: a subscription ending on 31 March (not active that day), one starting on 31 March (active), legacy £5.99 monthly prices, annual plans at £69 per year.',
  },
  {
    id: 'q07',
    difficulty: 'medium',
    text: `From Callum (head of retail): "Early refund rate by location for Q1 and Q2 2026 - I only care about refunds that came back within 30 days of the sale."

For each store and quarter (Q1 = January–March, Q2 = April–June 2026): early refund rate = (refunds made less than 30 × 24 hours after the sale's ordered_at, on the store's sales placed in that quarter) ÷ (the total order value of those same sales), both in GBP (rule 7), as a percentage. Return one row per store and quarter (ten rows) with three columns: the store name, the quarter as 'Q1' or 'Q2', and the rate as a percentage (for example 7.4 for 7.4%; compared to within ±0.06).`,
    ordered: false,
    tolerance: 0.06,
    reference: `WITH ${SALES},
${VALUE},
early AS (
  SELECT r.order_id, SUM(r.amount) AS refunded
  FROM refunds r JOIN orders o ON o.order_id = r.order_id
  WHERE r.refunded_at < datetime(o.ordered_at, '+30 days')
  GROUP BY r.order_id
),
per_store AS (
  SELECT o.store_id, CASE WHEN o.ordered_at < '2026-04-01' THEN 'Q1' ELSE 'Q2' END AS quarter,
         SUM(COALESCE(e.refunded, 0) * ${RATE}) AS refunded_gbp,
         SUM(COALESCE(v.value, 0) * ${RATE}) AS value_gbp
  FROM sales o
  LEFT JOIN order_value v ON v.order_id = o.order_id
  LEFT JOIN early e ON e.order_id = o.order_id
  WHERE o.ordered_at >= '2026-01-01' AND o.ordered_at < '2026-07-01'
  GROUP BY o.store_id, quarter
)
SELECT s.name, p.quarter, 100.0 * p.refunded_gbp / p.value_gbp AS early_refund_rate_pct
FROM per_store p JOIN stores s ON s.store_id = p.store_id`,
    solve: (t) => {
      const c = ctx(t);
      const early = new Map<number, number>();
      const orderAt = new Map(c.sales.map((s) => [s.id, s.at]));
      for (const r of c.refundRows) {
        const at = orderAt.get(r.order);
        if (at && toMin(r.at) < toMin(at) + 30 * 1440) early.set(r.order, (early.get(r.order) ?? 0) + r.amount);
      }
      const agg = new Map<string, [number, number]>();
      for (const s of c.sales) {
        if (!inRange(s.at, '2026-01-01', '2026-07-01')) continue;
        const k = `${c.storeName.get(s.store)}|${s.at < '2026-04-01' ? 'Q1' : 'Q2'}`;
        const rate = c.rate(s.currency, s.at);
        const a = agg.get(k) ?? [0, 0];
        a[0] += (early.get(s.id) ?? 0) * rate;
        a[1] += (c.value.get(s.id) ?? 0) * rate;
        agg.set(k, a);
      }
      return [...agg.entries()].map(([k, [rf, v]]) => [...k.split('|'), r1((100 * rf) / v)]);
    },
    careless: [],
    notes: 'Ratio of sums per store and quarter, items and refunds aggregated per order before joining, a 30-day window on the refund time, both sides in GBP. Planted: December orders refunded in January, refunds either side of the 30-day line.',
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
    text: `From Hannah (finance): "Please build the monthly KPI table for the board pack, January to June 2026. Use the definitions in the finance memo below - they differ from the everyday rules in places, and the amendments at the end override the main text."

FINANCE MEMO FN-07 - Board KPI definitions (version 3)
1. Booking month. A sale is booked in the calendar month of its ordered_at.
2. Counted sales. Every sale (rules 1-4) counts, except a sale that was fully refunded within 14 days: the refunds made on it within 14 days of its ordered_at (refunded_at earlier than ordered_at + 14 × 24 hours) add up to at least its order value (rule 5), comparing both rounded to the penny. A sale that is not counted is left out of every KPI except refunds (section 4).
3. Gross revenue = the total order value (rule 5) of the counted sales booked in the month, in GBP (rule 7).
4. Refunds = the total of all refunds MADE in the month (by refunded_at), on any sale - counted or not, booked in any month - converted to GBP at the rate for the month of that sale's ordered_at. Refunds on orders that are not sales (cancelled, pending, deleted, or belonging to test or deleted accounts) never count.
5. Net revenue = gross revenue - refunds (section 3 minus section 4). This replaces rule 6 for the board pack.
6. Active customers = the number of distinct real customers with at least one counted sale booked in the month. Guest checkouts are not customers.
7. New customers = the active customers of the month whose first counted sale ever (from December 2025 on) is booked in that month.
Amendment A (5 May 2026). The website closes its books at 23:00. A website sale (store_id 5) placed at 23:00:00 or later on the last day of a month is booked in the following month. This applies to sections 1, 3, 6 and 7; refunds stay in the month of their refunded_at.
Amendment B (12 June 2026). Customers complained that "14 days" was being counted to the minute. From now on a refund is "within 14 days" when date(refunded_at) is no later than date(ordered_at) + 14 calendar days, whatever the time of day. This replaces the window in section 2.

Return six rows, one per month from 2026-01 to 2026-06 in month order, with seven columns: the month ('YYYY-MM'), the number of counted sales, gross revenue, refunds, net revenue (the three money columns in GBP, ±0.01), active customers and new customers.`,
    ordered: true,
    reference: Q09_REFERENCE,
    solve: (t) => {
      const c = ctx(t);
      const book = (s: Ctx['sales'][number]) => {
        if (s.store === 5 && s.at.slice(11) >= '23:00:00' && addDays(s.at.slice(0, 10), 1).endsWith('-01')) return addDays(s.at.slice(0, 10), 1).slice(0, 7);
        return s.at.slice(0, 7);
      };
      const early = new Map<number, number>();
      const byId = new Map(c.sales.map((s) => [s.id, s]));
      for (const r of c.refundRows) {
        const s = byId.get(r.order);
        if (s && r.at.slice(0, 10) <= addDays(s.at.slice(0, 10), 14)) early.set(r.order, (early.get(r.order) ?? 0) + r.amount);
      }
      const counted = c.sales.filter((s) => !(early.has(s.id) && r2(early.get(s.id)!) >= r2(c.value.get(s.id) ?? 0)));
      const first = new Map<number, string>();
      for (const s of counted) if (s.cust !== null) {
        const m = book(s);
        if (!first.has(s.cust) || m < first.get(s.cust)!) first.set(s.cust, m);
      }
      const refundsBy = new Map<string, number>();
      for (const r of c.refundRows) {
        const s = byId.get(r.order);
        if (s) refundsBy.set(r.at.slice(0, 7), (refundsBy.get(r.at.slice(0, 7)) ?? 0) + r.amount * c.rate(s.currency, s.at));
      }
      return ['2026-01', '2026-02', '2026-03', '2026-04', '2026-05', '2026-06'].map((m) => {
        const ms = counted.filter((s) => book(s) === m);
        const gross = ms.reduce((a, s) => a + (c.value.get(s.id) ?? 0) * c.rate(s.currency, s.at), 0);
        const rf = refundsBy.get(m) ?? 0;
        const active = new Set(ms.filter((s) => s.cust !== null).map((s) => s.cust));
        const fresh = [...active].filter((cu) => first.get(cu!) === m).length;
        return [m, ms.length, r2(gross), r2(rf), r2(gross - rf), active.size, fresh];
      });
    },
    careless: [],
    notes: 'Booking month with the 23:00 website cut-off on month ends, the counted-sale test with a calendar-day 14-day window and penny rounding, refunds by the month they were made at the order-month rate, distinct customers and first counted sale. Planted: website sales in the last hour of every month (and a control just before 23:00), full refunds on day 14 in the afternoon, full refunds split across the window, December orders refunded in January.',
  },
  {
    id: 'q10',
    difficulty: 'hard',
    text: `From Kwame (CRM): "For the summer campaign I need every customer who has ever bought from us, with their segment as of 30 June 2026. The CRM segment rules are below."

CRM SEGMENT RULES (as at the end of 30 June 2026). For every real customer with at least one sale (rules 1-4; guest checkouts are not customers):
- lifetime net = the total net value (rule 6) of all their sales, in GBP (rule 7);
- gross = the total order value (rule 5) of all their sales in GBP; refunded = the total of all refunds on their sales in GBP;
- recent sales = the number of their sales placed on or after 2026-04-01;
- last sale = their latest sale (by ordered_at), and the shop it was made in.
The segments are checked in this order and the first one that applies wins:
1. 'Refund risk': refunded is at least 30% of gross, and they have at least 2 sales (a customer with a single sale is never 'Refund risk').
2. 'VIP': lifetime net is at least £350.00 and recent sales is at least 2.
3. 'Club': they have a subscription active on 2026-06-30 (rule 9).
4. 'Lapsed': their last sale was placed before 2026-04-01 - except that a customer whose last sale was made in the Dublin shop (store_id 4) is only 'Lapsed' when it was placed before 2026-03-01.
5. 'Regular': everyone else.

Kwame needs the campaign sizes: return one row per segment that has at least one customer, with three columns: the segment name exactly as written above, the number of customers in it, and their total lifetime net in GBP (±0.01).`,
    ordered: false,
    reference: `WITH ${SALES},
${VALUE},
${REFUNDED},
per_customer AS (
  SELECT o.customer_id,
         SUM(${NET}) AS lifetime_net,
         SUM(COALESCE(v.value, 0) * ${RATE}) AS gross,
         SUM(COALESCE(r.refunded, 0) * ${RATE}) AS refunded,
         COUNT(*) AS n_sales,
         SUM(o.ordered_at >= '2026-04-01') AS recent_sales,
         MAX(o.ordered_at) AS last_at
  FROM sales o
  LEFT JOIN order_value v ON v.order_id = o.order_id
  LEFT JOIN order_refunds r ON r.order_id = o.order_id
  WHERE o.customer_id IS NOT NULL
  GROUP BY o.customer_id
),
last_shop AS (
  SELECT o.customer_id, o.store_id
  FROM sales o JOIN per_customer p ON p.customer_id = o.customer_id AND o.ordered_at = p.last_at
),
club AS (
  SELECT DISTINCT customer_id FROM subscriptions
  WHERE started_on <= '2026-06-30' AND (ended_on IS NULL OR ended_on > '2026-06-30')
),
segmented AS (
SELECT p.customer_id, p.lifetime_net,
       CASE
         WHEN p.n_sales >= 2 AND p.refunded >= 0.3 * p.gross THEN 'Refund risk'
         WHEN p.lifetime_net >= 350 AND p.recent_sales >= 2 THEN 'VIP'
         WHEN p.customer_id IN (SELECT customer_id FROM club) THEN 'Club'
         WHEN (ls.store_id = 4 AND p.last_at < '2026-03-01') OR (ls.store_id <> 4 AND p.last_at < '2026-04-01') THEN 'Lapsed'
         ELSE 'Regular'
       END AS segment
FROM per_customer p JOIN last_shop ls ON ls.customer_id = p.customer_id
)
SELECT segment, COUNT(*) AS customers, SUM(lifetime_net) AS lifetime_net_gbp
FROM segmented GROUP BY segment`,
    solve: (t) => {
      const c = ctx(t);
      const club = new Set(t.subscriptions!.rows.filter((r) => String(r[4]) <= '2026-06-30' && (r[5] === null || String(r[5]) > '2026-06-30')).map((r) => Number(r[1])));
      const by = new Map<number, Ctx['sales']>();
      for (const s of c.sales) if (s.cust !== null) by.set(s.cust, [...(by.get(s.cust) ?? []), s]);
      const out: Array<[string, number]> = [];
      for (const [cu, list] of by) {
        let net = 0;
        let gross = 0;
        let refunded = 0;
        for (const s of list) {
          const k = c.rate(s.currency, s.at);
          net += netGbp(c, s);
          gross += (c.value.get(s.id) ?? 0) * k;
          refunded += (c.refunded.get(s.id) ?? 0) * k;
        }
        const sorted = [...list].sort((a, b) => (a.at < b.at ? -1 : 1));
        const last = sorted[sorted.length - 1]!;
        if (sorted.length > 1 && sorted[sorted.length - 2]!.at === last.at) throw new Error('tied last sale');
        const recent = list.filter((s) => s.at >= '2026-04-01').length;
        let seg = 'Regular';
        if (list.length >= 2 && refunded >= 0.3 * gross) seg = 'Refund risk';
        else if (net >= 350 && recent >= 2) seg = 'VIP';
        else if (club.has(cu)) seg = 'Club';
        else if (last.at < (last.store === 4 ? '2026-03-01' : '2026-04-01')) seg = 'Lapsed';
        out.push([seg, net]);
      }
      const agg = new Map<string, [number, number]>();
      for (const [seg, net] of out) {
        const a = agg.get(String(seg)) ?? [0, 0];
        a[0]++;
        a[1] += Number(net);
        agg.set(String(seg), a);
      }
      return [...agg.entries()].map(([k, [n, v]]) => [k, n, r2(v)]);
    },
    careless: [],
    notes: 'Per-customer aggregates with fan-out-safe joins and currency conversion, the shop of the last sale, and an ordered rule list with two exceptions (single-sale customers are never Refund risk; Dublin customers lapse a month earlier).',
  },
  {
    id: 'q11',
    difficulty: 'hard',
    text: `From Dmitri (area manager): "Can you work out Q1 2026 sales commission for every shop employee, month by month, using the commission scheme?"

COMMISSION SCHEME 2026 (shops only; website sales have no staff_id and pay no commission)
1. Credited sales. Each sale with a staff_id is credited to the person who served it, at its net value (rule 6: all refunds on it, whenever made) in GBP (rule 7), in the calendar month of its ordered_at. When the person served it while covering at a shop other than their own (orders.store_id is not their staff.store_id), only 50% of that net value is credited to them.
2. Sales staff (role 'sales'): commission for a month = 3% of their credited sales in that month up to the threshold, plus 5% of the part above the threshold. The threshold is £500.00, except in a person's first calendar month of employment (the month of their started_on), when it is £250.00.
3. Managers (role 'manager') earn no commission on their own sales. Instead they get an override of 1% of the total credited sales, in that month, of every person whose manager_id is theirs.
4. Leavers: a person whose left_on is on or before the last day of a month gets no commission (sections 2 and 3) for that month. Their credited sales still count towards their manager's override.
5. A commission below £0.00 (more refunds than sales) counts as £0.00.

Dmitri needs the commission bill per shop: work out each person's commission for each month on its own (sections 1-5, no rounding), then add up the people who belong to each shop (staff.store_id). Return one row per shop and month (2026-01, 2026-02, 2026-03) whose total, rounded to the penny, is above £0.00, with three columns: the shop name, the month ('YYYY-MM') and the total commission in GBP (±0.01).`,
    ordered: false,
    reference: `WITH ${SALES},
${VALUE},
${REFUNDED},
credited AS (
  SELECT o.staff_id, substr(o.ordered_at, 1, 7) AS month,
         SUM(${NET} * CASE WHEN o.store_id <> p.store_id THEN 0.5 ELSE 1.0 END) AS credited_gbp
  FROM sales o
  JOIN staff p ON p.staff_id = o.staff_id
  LEFT JOIN order_value v ON v.order_id = o.order_id
  LEFT JOIN order_refunds r ON r.order_id = o.order_id
  WHERE o.ordered_at >= '2026-01-01' AND o.ordered_at < '2026-04-01'
  GROUP BY o.staff_id, substr(o.ordered_at, 1, 7)
),
months(month, last_day) AS (
  VALUES ('2026-01', '2026-01-31'), ('2026-02', '2026-02-28'), ('2026-03', '2026-03-31')
),
people AS (
  SELECT p.staff_id, p.store_id, p.role, p.left_on, m.month, m.last_day,
         CASE WHEN substr(p.started_on, 1, 7) = m.month THEN 250.0 ELSE 500.0 END AS threshold
  FROM staff p CROSS JOIN months m
  WHERE p.role IN ('sales', 'manager')
),
commission AS (
  SELECT pe.staff_id, pe.store_id, pe.month, pe.left_on, pe.last_day,
         CASE WHEN pe.role = 'sales' THEN
                0.03 * MIN(MAX(COALESCE(c.credited_gbp, 0), 0), pe.threshold)
              + 0.05 * MAX(COALESCE(c.credited_gbp, 0) - pe.threshold, 0)
              ELSE 0.01 * COALESCE((SELECT SUM(t.credited_gbp) FROM credited t JOIN staff s ON s.staff_id = t.staff_id
                                    WHERE s.manager_id = pe.staff_id AND t.month = pe.month), 0)
         END AS amount
  FROM people pe
  LEFT JOIN credited c ON c.staff_id = pe.staff_id AND c.month = pe.month
)
SELECT st.name AS shop, cm.month, SUM(MAX(cm.amount, 0)) AS commission_gbp
FROM commission cm JOIN stores st ON st.store_id = cm.store_id
WHERE (cm.left_on IS NULL OR cm.left_on > cm.last_day)
GROUP BY st.store_id, st.name, cm.month
HAVING ROUND(SUM(MAX(cm.amount, 0)), 2) > 0`,
    solve: (t) => {
      const c = ctx(t);
      const staff = new Map(t.staff!.rows.map((r) => [Number(r[0]), { store: Number(r[1]), role: String(r[3]), mgr: r[4] === null ? null : Number(r[4]), started: String(r[5]), left: r[6] === null ? null : String(r[6]) }]));
      const cred = new Map<string, number>();
      for (const s of c.sales) {
        if (s.staff === null || !inRange(s.at, '2026-01-01', '2026-04-01')) continue;
        const k = `${s.staff}|${s.at.slice(0, 7)}`;
        cred.set(k, (cred.get(k) ?? 0) + netGbp(c, s) * (s.store !== staff.get(s.staff)!.store ? 0.5 : 1));
      }
      const shopTotal = new Map<string, number>();
      const ends: Record<string, string> = { '2026-01': '2026-01-31', '2026-02': '2026-02-28', '2026-03': '2026-03-31' };
      for (const [id, p] of staff) {
        if (p.role !== 'sales' && p.role !== 'manager') continue;
        for (const m of Object.keys(ends)) {
          if (p.left !== null && p.left <= ends[m]!) continue;
          let amt: number;
          if (p.role === 'sales') {
            const th = p.started.slice(0, 7) === m ? 250 : 500;
            const x = cred.get(`${id}|${m}`) ?? 0;
            amt = 0.03 * Math.min(Math.max(x, 0), th) + 0.05 * Math.max(x - th, 0);
          } else {
            let team = 0;
            for (const [oid, o] of staff) if (o.mgr === id) team += cred.get(`${oid}|${m}`) ?? 0;
            amt = 0.01 * team;
          }
          amt = Math.max(amt, 0);
          const k = `${c.storeName.get(p.store)}|${m}`;
          shopTotal.set(k, (shopTotal.get(k) ?? 0) + amt);
        }
      }
      return [...shopTotal.entries()].filter(([, v]) => r2(v) > 0).map(([k, v]) => [...k.split('|'), r2(v)]);
    },
    careless: [],
    notes: 'Credited sales per person and month with the 50% cover-shift rule, a marginal two-band rate with a lower first-month threshold, manager overrides on their team (leavers included), leavers excluded from their own commission, floors at zero.',
  },
  {
    id: 'q12',
    difficulty: 'hard',
    text: `From Siobhan (Trailhead Club): "For the club's anniversary mailing I need every member's membership spells - how long they've really been with us, and what they've paid."

A membership spell is one continuous stretch of membership made of one or more of a real customer's subscriptions:
- Take the customer's subscriptions in order of started_on (ties: lower subscription_id first). The first one starts a spell.
- Each next subscription joins the current spell when its started_on is no more than 30 days after the spell's current end (started_on <= end + 30 days). The spell's current end is the latest ended_on among the subscriptions already in it; if any of them has ended_on NULL, the spell has no end and every later subscription joins it.
- Otherwise the subscription starts a new spell.
Paid value of a subscription: cover runs from started_on to ended_on (or to 2026-07-01 if ended_on is NULL); a monthly plan pays price_gbp for every started 30-day period of cover, an annual plan pays price_gbp for every started 365-day period (so 31 days of a monthly plan pay twice, and 365 days of an annual plan pay once).

Only customers with at least two subscriptions are wanted. Return one row per spell of those customers with five columns: customer_id, the spell's start (its earliest started_on), its end (the latest ended_on of its subscriptions, or NULL if any of them is still running), the number of subscriptions in it, and the total paid value of those subscriptions in GBP (±0.01).`,
    ordered: false,
    reference: `WITH subs AS (
  SELECT s.*, CASE WHEN s.plan = 'annual' THEN 365 ELSE 30 END AS period
  FROM subscriptions s JOIN customers c ON c.customer_id = s.customer_id
  WHERE c.is_test = 0 AND c.deleted_at IS NULL
    AND (SELECT COUNT(*) FROM subscriptions x WHERE x.customer_id = s.customer_id) >= 2
),
with_prev AS (
  SELECT subs.*,
         MAX(COALESCE(ended_on, '9999-12-31')) OVER (
           PARTITION BY customer_id ORDER BY started_on, subscription_id
           ROWS BETWEEN UNBOUNDED PRECEDING AND 1 PRECEDING) AS prev_end
  FROM subs
),
flagged AS (
  SELECT *, CASE WHEN prev_end IS NULL THEN 1
                 WHEN prev_end = '9999-12-31' THEN 0
                 WHEN started_on <= date(prev_end, '+30 days') THEN 0
                 ELSE 1 END AS starts_spell
  FROM with_prev
),
numbered AS (
  SELECT *, SUM(starts_spell) OVER (PARTITION BY customer_id ORDER BY started_on, subscription_id ROWS UNBOUNDED PRECEDING) AS spell_no
  FROM flagged
)
SELECT customer_id,
       MIN(started_on) AS spell_start,
       CASE WHEN SUM(ended_on IS NULL) > 0 THEN NULL ELSE MAX(ended_on) END AS spell_end,
       COUNT(*) AS subscriptions,
       SUM(price_gbp * ((CAST(julianday(COALESCE(ended_on, '2026-07-01')) - julianday(started_on) AS INTEGER) + period - 1) / period)) AS paid_gbp
FROM numbered
GROUP BY customer_id, spell_no`,
    solve: (t) => {
      const c = ctx(t);
      const by = new Map<number, Array<{ id: number; plan: string; price: number; start: string; end: string | null }>>();
      for (const r of t.subscriptions!.rows) {
        const cu = Number(r[1]);
        if (!c.real.has(cu)) continue;
        by.set(cu, [...(by.get(cu) ?? []), { id: Number(r[0]), plan: String(r[2]), price: Number(r[3]), start: String(r[4]), end: r[5] === null ? null : String(r[5]) }]);
      }
      const out: Cell[][] = [];
      for (const [cu, list] of by) {
        if (list.length < 2) continue;
        list.sort((a, b) => (a.start < b.start ? -1 : a.start > b.start ? 1 : a.id - b.id));
        let spell: { start: string; end: string | null; open: boolean; n: number; paid: number } | null = null;
        const flush = () => {
          if (spell) out.push([cu, spell.start, spell.open ? null : spell.end, spell.n, r2(spell.paid)]);
        };
        for (const s of list) {
          const days = daysBetween(s.start, s.end ?? '2026-07-01');
          const period = s.plan === 'annual' ? 365 : 30;
          const paid = s.price * Math.ceil(days / period);
          const joins = spell !== null && (spell.open || s.start <= addDays(spell.end!, 30));
          if (!joins) {
            flush();
            spell = { start: s.start, end: s.end, open: s.end === null, n: 0, paid: 0 };
          }
          spell!.n++;
          spell!.paid += paid;
          if (s.end === null) spell!.open = true;
          else if (spell!.end === null || s.end > spell!.end) spell!.end = s.end;
        }
        flush();
      }
      return out;
    },
    careless: [],
    notes: 'Gaps and islands over date ranges: a running maximum of earlier end dates (NULL = open, which absorbs everything after), a 30-day joining window, then a ceiling division for the started billing periods. Planted: returns after exactly 30 and 31 days, overlapping plan switches, third subscriptions and still-running plans.',
  },
];

// ─────────────────────────────── Careless queries (each one real slip, applied to the reference) ───────────────────────────────

const SLIPS: Record<string, Array<[why: string, from: string, to: string, all?: boolean]>> = {
  q05: [
    ['a NULL discount makes the whole line NULL, so those lines vanish', 'quantity * unit_price - COALESCE(line_discount, 0)', 'quantity * unit_price - line_discount'],
    ['converts by the store currency, so euro website orders stay in euros', "CASE WHEN o.currency = 'GBP' THEN 1.0", "CASE WHEN s.currency = 'GBP' THEN 1.0"],
    ['keeps test accounts and deleted accounts', '(o.customer_id IS NULL OR (c.is_test = 0 AND c.deleted_at IS NULL))', '1 = 1'],
    ['sorted by revenue instead of by store and month', 'ORDER BY s.name, month', 'ORDER BY net_revenue_gbp DESC'],
  ],
  q06: [
    ['counts the annual price as if it were monthly', 's.price_gbp / 12.0', 's.price_gbp'],
    ['treats ended_on as the last covered day', 's.ended_on > m.day', 's.ended_on >= m.day'],
    ['includes test and deleted accounts', 'WHERE c.is_test = 0 AND c.deleted_at IS NULL', 'WHERE 1 = 1'],
  ],
  q07: [
    ['counts every refund, not only the early ones', "WHERE r.refunded_at < datetime(o.ordered_at, '+30 days')", 'WHERE 1 = 1'],
    ['a 30-calendar-day window instead of 30 × 24 hours', "WHERE r.refunded_at < datetime(o.ordered_at, '+30 days')", "WHERE date(r.refunded_at) <= date(o.ordered_at, '+30 days')"],
    ['no currency conversion', RATE, '1.0', true],
  ],
  q09: [
    ['no 23:00 website cut-off at month end (Amendment A)', "CASE WHEN o.store_id = 5 AND substr(o.ordered_at, 12) >= '23:00:00'", "CASE WHEN 0 AND substr(o.ordered_at, 12) >= '23:00:00'"],
    ['the 14-day window measured to the minute (ignores Amendment B)', "WHERE date(r.refunded_at) <= date(o.ordered_at, '+14 days')", "WHERE r.refunded_at < datetime(o.ordered_at, '+14 days')"],
    ['refunds placed in the month of the sale (the everyday rule 6), not the month they were made', 'SELECT substr(r.refunded_at, 1, 7) AS month, SUM(r.amount * o.rate) AS refunds_gbp', 'SELECT o.book_month AS month, SUM(r.amount * o.rate) AS refunds_gbp'],
    ['keeps the fully refunded sales', 'WHERE e.amount IS NULL OR ROUND(e.amount, 2) < ROUND(o.value, 2)', 'WHERE 1 = 1'],
  ],
  q10: [
    ['forgets the Dublin exception for Lapsed', "(ls.store_id = 4 AND p.last_at < '2026-03-01') OR (ls.store_id <> 4 AND p.last_at < '2026-04-01')", "p.last_at < '2026-04-01'"],
    ['checks Club before VIP', "WHEN p.lifetime_net >= 350 AND p.recent_sales >= 2 THEN 'VIP'\n         WHEN p.customer_id IN (SELECT customer_id FROM club) THEN 'Club'", "WHEN p.customer_id IN (SELECT customer_id FROM club) THEN 'Club'\n         WHEN p.lifetime_net >= 350 AND p.recent_sales >= 2 THEN 'VIP'"],
    ['single-sale customers can be Refund risk', 'WHEN p.n_sales >= 2 AND p.refunded >= 0.3 * p.gross', 'WHEN p.refunded >= 0.3 * p.gross'],
  ],
  q11: [
    ['credits cover-shift sales in full', 'CASE WHEN o.store_id <> p.store_id THEN 0.5 ELSE 1.0 END', '1.0'],
    ['no lower threshold in the first month', 'CASE WHEN substr(p.started_on, 1, 7) = m.month THEN 250.0 ELSE 500.0 END', '500.0'],
    ['pays leavers for the month they left', 'WHERE (cm.left_on IS NULL OR cm.left_on > cm.last_day)', 'WHERE 1 = 1'],
    ['one flat rate on the whole amount instead of marginal bands', '0.03 * MIN(MAX(COALESCE(c.credited_gbp, 0), 0), pe.threshold)\n              + 0.05 * MAX(COALESCE(c.credited_gbp, 0) - pe.threshold, 0)', 'CASE WHEN COALESCE(c.credited_gbp, 0) > pe.threshold THEN 0.05 ELSE 0.03 END * MAX(COALESCE(c.credited_gbp, 0), 0)'],
  ],
  q12: [
    ['compares with the previous subscription only, not the latest end so far', 'MAX(COALESCE(ended_on, \'9999-12-31\')) OVER (\n           PARTITION BY customer_id ORDER BY started_on, subscription_id\n           ROWS BETWEEN UNBOUNDED PRECEDING AND 1 PRECEDING)', 'LAG(COALESCE(ended_on, \'9999-12-31\')) OVER (PARTITION BY customer_id ORDER BY started_on, subscription_id)'],
    ['a still-running subscription does not absorb the later ones', "WHEN prev_end = '9999-12-31' THEN 0\n                 ", ''],
    ['counts whole billing periods only (rounds down)', '+ period - 1) / period', ') / period'],
    ['a 30-day window that excludes day 30', "started_on <= date(prev_end, '+30 days')", "started_on < date(prev_end, '+30 days')"],
  ],
};

for (const q of QUESTIONS) {
  for (const [why, from, to, all] of SLIPS[q.id] ?? []) {
    if (!q.reference.includes(from)) throw new Error(`${q.id}: slip "${why}" does not apply`);
    q.careless.push({ why, sql: all ? q.reference.split(from).join(to) : q.reference.replace(from, to) });
  }
}


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
