/**
 * Record a price you saw, in one line.
 *
 *   npm run price -- "i7 7700" used 40
 *   npm run price -- "rtx 3070" used 192 --basis sold --source ebay-uk --n 14
 *   npm run price -- "rtx 5070" new 549 --basis retail --source scan-uk
 *   npm run price -- "ddr5 32" new 89 --basis retail --source scan-uk     (an allowance: memory.DDR5.32)
 *   npm run price -- "650w" new 68 --basis retail                          (psu.650)
 *   npm run price -- --id case.good new 92 --basis retail                  (any key or id, exactly)
 *   npm run price -- "fractal north" new 110 --basis retail                (a case from the catalogue)
 *
 * Resolves the part by search, refuses to guess between close matches, appends
 * the row to this week's snapshot file under data/prices-observed/, and re-runs
 * the importer so the app sees it immediately. Ten seconds from "I just bought
 * one" to a sourced figure with a date on it — because the alternative, a
 * recalled price on an old part, is the one that says a £40 chip costs £300.
 *
 * Defaults: basis "sold" (you paid it, or you read it off sold listings),
 * source "operator", one sample, today's date, GBP. Anything read from live
 * listings that have not sold should say --basis asking.
 */
import { appendFileSync, existsSync, mkdirSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { snapshotWeek } from '../src/core/pricetrend.ts';
import { resolveOne } from '../src/core/resolve.ts';
import { main as importPrices, priceableIds } from './import-prices.ts';

const ROOT = new URL('..', import.meta.url).pathname;
const DIR = join(ROOT, 'data/prices-observed');
const HEADER = 'part_id,condition,basis,price,currency,source,observed_date,sample_size,note';

function usage(msg?: string): never {
  if (msg) console.error(`\n${msg}\n`);
  console.error(`usage: npm run price -- "<part>" <new|used> <price> [--basis sold|asking|retail] [--source name] [--n sales] [--date YYYY-MM-DD] [--note "..."] [--currency GBP] [--id catalogue-id]`);
  process.exit(1);
}

const argv = process.argv.slice(2);
const flags: Record<string, string> = {};
const positional: string[] = [];
for (let i = 0; i < argv.length; i++) {
  const a = argv[i];
  if (a.startsWith('--')) { flags[a.slice(2)] = argv[i + 1] ?? ''; i++; } else positional.push(a);
}
// With --id the query is the id itself, so the positionals are condition and price.
const [query, conditionRaw, priceRaw] = flags.id ? [flags.id, ...positional] : positional;
if (!query || !conditionRaw || !priceRaw) usage();
const condition = conditionRaw.toLowerCase();
if (condition !== 'new' && condition !== 'used') usage(`condition must be new or used, not "${conditionRaw}"`);
const price = Number(priceRaw.replace(/[£,]/g, ''));
if (!Number.isFinite(price) || price <= 0) usage(`price must be a positive number, not "${priceRaw}"`);
const basis = (flags.basis ?? 'sold').toLowerCase();
if (!['sold', 'asking', 'retail'].includes(basis)) usage(`basis must be sold, asking or retail`);
const date = flags.date ?? new Date().toISOString().slice(0, 10);
if (!/^\d{4}-\d{2}-\d{2}$/.test(date) || Number.isNaN(Date.parse(date))) usage(`date must be YYYY-MM-DD`);
const n = Number(flags.n ?? '1');
if (!Number.isInteger(n) || n < 1) usage(`--n must be a whole number of sales, 1 or more`);
const currency = (flags.currency ?? 'GBP').toUpperCase();
const source = flags.source ?? 'operator';

const { ids, labels, kinds } = priceableIds();

/**
 * Resolve a query against everything priceable: processors, graphics cards,
 * cases and monitors by name, allowances by their label ("32GB DDR5 kit",
 * "650W power supply", "case, mesh-front airflow case"). The matching itself
 * lives in src/core/resolve.ts so this command and `npm run analyse` cannot
 * drift apart about what a given name means.
 */
let id = flags.id;
let label = '';
if (id) {
  if (!ids.has(id)) usage(`"${id}" is not a catalogue id or an allowance key — see data/prices-observed/README.md`);
  label = labels.get(id)!;
} else {
  const r = resolveOne(query, labels);
  if (!r.ok && r.reason === 'none') usage(`nothing priceable matches "${query}"`);
  if (!r.ok) {
    console.error(`\n"${query}" is ambiguous. Say which, with --id:\n`);
    for (const h of r.candidates) console.error(`  ${h.id.padEnd(36)} ${h.label}  [${kinds.get(h.id)}]`);
    process.exit(1);
  }
  id = r.id; label = r.label;
}

const note = (flags.note ?? '').replace(/"/g, '""');
const row = [id, condition, basis, price, currency, source, date, n, note.includes(',') || note.includes('"') ? `"${note}"` : note].join(',');
const file = join(DIR, `${snapshotWeek(date)}.csv`);
mkdirSync(DIR, { recursive: true });
if (!existsSync(file)) writeFileSync(file, `${HEADER}\n`);
appendFileSync(file, `${row}\n`);

console.log(`recorded  ${label}  (${id})`);
console.log(`          ${condition} · ${basis} · ${currency} ${price} · ${n} sale${n === 1 ? '' : 's'} · ${source} · ${date}`);
console.log(`in        data/prices-observed/${snapshotWeek(date)}.csv\n`);
importPrices();
