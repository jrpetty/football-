/**
 * Record a price you saw, in one line.
 *
 *   npm run price -- "i7 7700" used 40
 *   npm run price -- "rtx 3070" used 192 --basis sold --source ebay-uk --n 14
 *   npm run price -- "rtx 5070" new 549 --basis retail --source scan-uk
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
import { appendFileSync, existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { buildEngineData, search } from '../src/core/catalogue.ts';
import { snapshotWeek } from '../src/core/pricetrend.ts';
import { main as importPrices } from './import-prices.ts';

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
const [query, conditionRaw, priceRaw] = positional;
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

const load = (p: string) => JSON.parse(readFileSync(join(ROOT, p), 'utf8'));
const data = buildEngineData({
  gpus: load('data/catalogue/gpus.json'), cpus: load('data/catalogue/cpus.json'),
  games: load('data/catalogue/games.json'), references: load('data/catalogue/references.json'),
});

let id = flags.id;
let label = '';
if (id) {
  const rec = data.gpus.get(id) ?? data.cpus.get(id);
  if (!rec) usage(`"${id}" is not a catalogue id`);
  label = rec.fullName;
} else {
  const hits = search(query, data, 8);
  if (!hits.length) usage(`nothing in the catalogue matches "${query}"`);
  // A query that IS a part's name wins outright: "rtx 3070" is the RTX 3070,
  // not a toss-up with the 3070 Ti because the two score a few points apart.
  // "7700" ends four different names and stays ambiguous, which is right.
  const norm = (t: string) => t.toLowerCase().replace(/[^a-z0-9]+/g, ' ').trim();
  const exact = hits.filter((h) => { const l = norm(h.label); const q = norm(query); return l === q || l.endsWith(` ${q}`); });
  if (exact.length === 1) { id = exact[0].id; label = exact[0].label; }
  else {
    // Otherwise refuse to guess. A price filed against the wrong part is worse
    // than no price.
    const pool = exact.length > 1 ? exact : hits;
    const [a, b] = pool;
    if (b && a.score - b.score < 10) {
      console.error(`\n"${query}" is ambiguous. Say which, with --id:\n`);
      for (const h of pool) console.error(`  ${h.id.padEnd(36)} ${h.label}  ${h.disambiguator ?? ''}`);
      process.exit(1);
    }
    id = a.id; label = a.label;
  }
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
