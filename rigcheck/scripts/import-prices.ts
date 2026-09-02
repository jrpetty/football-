/**
 * Observed-price import lane.
 *
 * The seed price tables are recalled figures — a model's memory of roughly
 * mid-2026 UK street prices, never checked against a retailer. They are the
 * most perishable data in the project and the least defensible. This script is
 * how they get replaced with something real: drop marketplace observations in
 * `data/prices-observed/*.csv`, run this, and every figure supplied overrides
 * the seed with its source, date and sample size carried through.
 *
 * Two judgements are baked in, and both matter more than the arithmetic.
 *
 * **Sold prices and asking prices are different quantities.** A used card
 * listed at 280 by ten hopeful sellers may change hands at 190; the listings
 * easiest to see are the ones nobody bought, which is exactly why they are
 * still visible. Asking-basis rows are accepted, because sometimes it is all
 * anyone has, and then discounted and flagged rather than silently trusted.
 *
 * **The median, weighted by sample size.** One collector paying 600 for a
 * GTX 1080 Ti moves a mean and does nothing to a median, and a figure drawn
 * from twenty sales deserves more weight than one drawn from a single sale.
 */
import { mkdirSync, readFileSync, readdirSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { parseCsv } from './import-manual.ts';
import type { CpuRecord, GpuRecord } from '../src/core/types.ts';
import type { SeriesPoint } from '../src/core/pricetrend.ts';
import { allowanceKeys } from '../src/core/components.ts';
import type { ComponentPrices } from '../src/core/planner.ts';

/**
 * Everything a price can be recorded against: processors and graphics cards
 * by catalogue id, cases and monitors by catalogue id, and the component
 * allowances by key (memory.DDR5.32, psu.650, case.good …). Labels are for
 * the resolver in scripts/price.ts and for reports.
 */
export function priceableIds(): { ids: Set<string>; labels: Map<string, string>; kinds: Map<string, string> } {
  const j = (p: string) => JSON.parse(readFileSync(join(ROOT, p), 'utf8'));
  const labels = new Map<string, string>(), kinds = new Map<string, string>();
  for (const r of (j('data/catalogue/gpus.json') as { records: GpuRecord[] }).records) { labels.set(r.id, r.fullName); kinds.set(r.id, 'gpu'); }
  for (const r of (j('data/catalogue/cpus.json') as { records: CpuRecord[] }).records) { labels.set(r.id, r.fullName); kinds.set(r.id, 'cpu'); }
  for (const r of (j('data/catalogue/cases.json') as { records: { id: string; fullName: string }[] }).records) { labels.set(r.id, `${r.fullName} (case)`); kinds.set(r.id, 'case'); }
  for (const r of (j('data/catalogue/monitors.json') as { records: { id: string; fullName: string }[] }).records) { labels.set(r.id, `${r.fullName} (monitor)`); kinds.set(r.id, 'monitor'); }
  for (const a of allowanceKeys(j('data/pricing/components-gbp.json') as ComponentPrices)) { labels.set(a.key, a.label); kinds.set(a.key, 'allowance'); }
  return { ids: new Set(labels.keys()), labels, kinds };
}

const ROOT = new URL('..', import.meta.url).pathname;
const IN = join(ROOT, 'data/prices-observed');
const OUT = join(ROOT, 'data/pricing/observed.json');

/**
 * How much an asking price overstates a sold price.
 *
 * A prior, not a measurement, and deliberately conservative — 0.85 rather than
 * the 0.70 that some categories genuinely run at, because over-correcting an
 * asking price produces a confidently wrong low figure, which is worse than a
 * mildly high one. Anything imported this way is flagged regardless, so the
 * factor is a fallback and not a licence to treat the two as equivalent.
 */
const ASKING_TO_SOLD = 0.85;

/** Beyond this, a price is a historical note rather than a current figure. */
const STALE_DAYS = 90;

/**
 * The current figure is the median of the newest cluster of observations, not
 * of everything ever recorded. With weekly snapshots accumulating for months,
 * a median over all rows would drift toward the past and a real fall in price
 * would take a season to show. Rows older than this, measured back from the
 * newest observation, still count — they become the series a trend is read
 * from — but they do not vote on what the part costs now.
 */
export const CURRENT_WINDOW_DAYS = 45;

/**
 * Which files in data/prices-observed/ are observations. Anything named
 * example* is documentation and never data: a shipped example was once imported
 * as five real eBay medians and the app labelled them "sourced".
 */
export function isObservationFile(name: string): boolean {
  const n = name.toLowerCase();
  return n.endsWith('.csv') && !n.startsWith('example') && !n.startsWith('_') && !n.startsWith('.');
}

const CONDITIONS = new Set(['new', 'used']);
const BASES = new Set(['sold', 'asking', 'retail']);
const CURRENCIES = new Set(['GBP', 'USD', 'EUR']);

export interface Observation {
  partId: string;
  condition: 'new' | 'used';
  basis: 'sold' | 'asking' | 'retail';
  price: number;
  currency: string;
  source: string;
  observedDate: string;
  sampleSize: number;
  note?: string;
  file: string;
  line: number;
}

export interface Rejection {
  file: string;
  line: number;
  reason: string;
  raw: string;
}

/** Sample-weighted median: each observation counts once per sale behind it. */
export function weightedMedian(rows: { value: number; weight: number }[]): number {
  const sorted = [...rows].sort((a, b) => a.value - b.value);
  const total = sorted.reduce((s, r) => s + r.weight, 0);
  let cum = 0;
  for (const r of sorted) {
    cum += r.weight;
    if (cum >= total / 2) return r.value;
  }
  return sorted[sorted.length - 1]?.value ?? 0;
}

export function parseObservations(
  text: string,
  file: string,
  validIds: Set<string>,
): { rows: Observation[]; rejected: Rejection[] } {
  const rows: Observation[] = [];
  const rejected: Rejection[] = [];
  const grid = parseCsv(text).filter((r) => r.some((c) => c.trim()));
  if (!grid.length) return { rows, rejected };

  const header = grid[0].map((h) => h.trim().toLowerCase());
  const col = (name: string) => header.indexOf(name);
  const need = ['part_id', 'condition', 'basis', 'price', 'currency', 'source', 'observed_date'];
  const missing = need.filter((n) => col(n) === -1);
  if (missing.length) {
    rejected.push({ file, line: 1, reason: `header is missing required column(s): ${missing.join(', ')}`, raw: grid[0].join(',') });
    return { rows, rejected };
  }

  for (let i = 1; i < grid.length; i++) {
    const r = grid[i];
    const raw = r.join(',');
    const get = (n: string) => (col(n) === -1 ? '' : (r[col(n)] ?? '').trim());
    const reject = (reason: string) => rejected.push({ file, line: i + 1, reason, raw });

    const partId = get('part_id');
    if (!partId) { reject('part_id is empty'); continue; }
    if (!validIds.has(partId)) { reject(`part_id "${partId}" is not a catalogue part or an allowance key — see data/prices-observed/README.md`); continue; }

    const condition = get('condition').toLowerCase();
    if (!CONDITIONS.has(condition)) { reject(`condition "${condition}" must be new or used`); continue; }

    const basis = get('basis').toLowerCase();
    if (!BASES.has(basis)) { reject(`basis "${basis}" must be sold, asking or retail`); continue; }

    const price = Number(get('price'));
    if (!Number.isFinite(price) || price <= 0) { reject(`price "${get('price')}" is not a positive number`); continue; }
    if (price > 20000) { reject(`price ${price} is implausible for a single part — is this a whole system or a bundle?`); continue; }

    const currency = get('currency').toUpperCase();
    if (!CURRENCIES.has(currency)) { reject(`currency "${currency}" must be one of ${[...CURRENCIES].join(', ')}`); continue; }

    const observedDate = get('observed_date');
    if (!/^\d{4}-\d{2}-\d{2}$/.test(observedDate)) { reject(`observed_date "${observedDate}" must be YYYY-MM-DD`); continue; }
    if (Number.isNaN(Date.parse(observedDate))) { reject(`observed_date "${observedDate}" is not a real date`); continue; }

    const sampleRaw = get('sample_size');
    const sampleSize = sampleRaw ? Number(sampleRaw) : 1;
    if (!Number.isFinite(sampleSize) || sampleSize < 1) { reject(`sample_size "${sampleRaw}" must be 1 or more`); continue; }

    rows.push({
      partId, condition: condition as 'new' | 'used', basis: basis as Observation['basis'],
      price, currency, source: get('source') || 'unstated', observedDate,
      sampleSize, note: get('note') || undefined, file, line: i + 1,
    });
  }
  return { rows, rejected };
}

export interface AggregatedPrice {
  partId: string;
  condition: 'new' | 'used';
  /** The figure to use, after any asking-basis discount. */
  price: number;
  /** What the observations said before the discount. */
  observedMedian: number;
  currency: string;
  /** Total sales behind this figure, across all observations. */
  totalSamples: number;
  observations: number;
  /** Lowest and highest observed, so a wide spread is visible. */
  spread: { low: number; high: number };
  newestDate: string;
  /** Earliest observation on record, so the screen can say how long it has been watched. */
  firstDate: string;
  ageDays: number;
  stale: boolean;
  /** True when any contributing row was an asking price rather than a sale. */
  containsAsking: boolean;
  sources: string[];
  warnings: string[];
  /**
   * One point per observation date, oldest first: the material a trend is
   * read from. Each point is that date's own sample-weighted median.
   */
  series: SeriesPoint[];
}

export function aggregate(rows: Observation[], today: Date): AggregatedPrice[] {
  const groups = new Map<string, Observation[]>();
  for (const r of rows) {
    const k = `${r.partId}::${r.condition}::${r.currency}`;
    (groups.get(k) ?? groups.set(k, []).get(k)!).push(r);
  }

  const out: AggregatedPrice[] = [];
  for (const [k, all] of groups) {
    const [partId, condition, currency] = k.split('::');
    const warnings: string[] = [];

    // The series: every date gets its own median, so a trend can be read.
    const byDate = new Map<string, Observation[]>();
    for (const r of all) (byDate.get(r.observedDate) ?? byDate.set(r.observedDate, []).get(r.observedDate)!).push(r);
    const series: SeriesPoint[] = [...byDate.entries()].sort(([a], [b]) => a.localeCompare(b)).map(([date, rows]) => {
      const med = weightedMedian(rows.map((g) => ({ value: g.price, weight: g.sampleSize })));
      const asking = rows.every((g) => g.basis === 'asking');
      return {
        date,
        price: Math.round(asking ? med * ASKING_TO_SOLD : med),
        sampleSize: rows.reduce((t, g) => t + g.sampleSize, 0),
        basis: asking ? 'asking' : rows.some((g) => g.basis === 'sold') ? 'sold' : 'retail',
      };
    });
    const firstDate = series[0].date;
    const newestDate = series[series.length - 1].date;

    // The current figure votes only from the newest window.
    const cutoff = new Date(Date.parse(newestDate) - CURRENT_WINDOW_DAYS * 86400000).toISOString().slice(0, 10);
    const group = all.filter((g) => g.observedDate >= cutoff);

    const observedMedian = weightedMedian(group.map((g) => ({ value: g.price, weight: g.sampleSize })));
    // Discount is applied to the group only when every row is an asking price.
    // A mix already has real sales anchoring the median, and discounting those
    // too would push the figure below what anyone actually paid.
    const allAsking = group.every((g) => g.basis === 'asking');
    const containsAsking = group.some((g) => g.basis === 'asking');
    const price = allAsking ? Math.round(observedMedian * ASKING_TO_SOLD) : Math.round(observedMedian);

    if (allAsking) {
      warnings.push(
        `Every observation is an ASKING price, not a sale. Discounted by ${Math.round((1 - ASKING_TO_SOLD) * 100)}% to approximate what the part changes hands for, which is a prior and not a measurement. Replace with sold-listing data when you can.`,
      );
    } else if (containsAsking) {
      warnings.push('Mixed sold and asking observations. The sold rows anchor the median, so no discount was applied.');
    }

    const totalSamples = group.reduce((s, g) => s + g.sampleSize, 0);
    if (totalSamples < 5) {
      warnings.push(`Only ${totalSamples} sale${totalSamples === 1 ? '' : 's'} behind this figure. Marketplace prices scatter widely; treat anything under about five as indicative.`);
    }

    const values = group.map((g) => g.price);
    const spread = { low: Math.min(...values), high: Math.max(...values) };
    if (spread.high > spread.low * 1.6 && group.length > 1) {
      warnings.push(`Observations range from ${spread.low} to ${spread.high} — a spread that wide usually means the listings were not comparable (bundles, faulty parts, or different variants sharing a name).`);
    }

    const ageDays = Math.round((today.getTime() - Date.parse(newestDate)) / 86400000);
    const stale = ageDays > STALE_DAYS;
    if (stale) warnings.push(`Newest observation is ${ageDays} days old. Graphics-card pricing moves; this is a historical note rather than a current figure.`);

    out.push({
      partId, condition: condition as 'new' | 'used', price, observedMedian: Math.round(observedMedian),
      currency, totalSamples, observations: group.length, spread, newestDate, firstDate, ageDays, stale,
      containsAsking, sources: [...new Set(all.map((g) => g.source))], warnings, series,
    });
  }
  return out.sort((a, b) => a.partId.localeCompare(b.partId));
}

export function main() {
  const validIds = priceableIds().ids;

  mkdirSync(IN, { recursive: true });
  const files = readdirSync(IN).filter(isObservationFile).sort();

  const all: Observation[] = [];
  const rejected: Rejection[] = [];
  for (const f of files) {
    const { rows, rejected: rej } = parseObservations(readFileSync(join(IN, f), 'utf8'), f, validIds);
    all.push(...rows);
    rejected.push(...rej);
  }

  const today = new Date();
  const aggregated = aggregate(all, today);

  writeFileSync(
    OUT,
    JSON.stringify(
      {
        generatedAt: today.toISOString(),
        note:
          'Operator-supplied market observations. These OVERRIDE the recalled seed tables in gbp-new.json and gbp-used.json for any part they cover. Regenerate with `npm run import:prices`; edit the CSVs in data/prices-observed/, never this file.',
        provenance: 'operator-observed',
        sourceFiles: files,
        observationCount: all.length,
        prices: aggregated,
      },
      null,
      2,
    ) + '\n',
  );

  // --- report -------------------------------------------------------------
  console.log(`Read ${files.length} file(s) from data/prices-observed/`);
  console.log(`  ${all.length} observation(s) accepted, ${rejected.length} rejected`);
  console.log(`  ${aggregated.length} part/condition pair(s) now carry a sourced price`);
  const watched = aggregated.filter((a) => a.series.length > 1);
  console.log(`  ${watched.length} of them have been observed on more than one date, so a trend can be read`);
  if (rejected.length) {
    console.log('\nrejected rows:');
    for (const r of rejected) console.log(`  ${r.file}:${r.line}  ${r.reason}`);
  }

  const flagged = aggregated.filter((a) => a.warnings.length);
  if (flagged.length) {
    console.log(`\n${flagged.length} sourced price(s) carry a caveat:`);
    for (const a of flagged) {
      console.log(`  ${a.partId} (${a.condition}) = ${a.price} ${a.currency}`);
      for (const w of a.warnings) console.log(`      ${w}`);
    }
  }

  // What is still running on recollection is the actionable part of this report.
  const covered = new Set(aggregated.map((a) => a.partId));
  const seedNew = JSON.parse(readFileSync(join(ROOT, 'data/pricing/gbp-new.json'), 'utf8')) as { prices: Record<string, number> };
  const seedUsed = JSON.parse(readFileSync(join(ROOT, 'data/pricing/gbp-used.json'), 'utf8')) as { prices: Record<string, number> };
  const seeded = new Set([...Object.keys(seedNew.prices), ...Object.keys(seedUsed.prices)]);
  const stillRecalled = [...seeded].filter((id) => !covered.has(id)).sort();
  console.log(`\n${covered.size} of ${seeded.size} priced parts are now sourced; ${stillRecalled.length} still run on the recalled seed.`);
  if (stillRecalled.length && stillRecalled.length <= 40) {
    console.log('  ' + stillRecalled.join('\n  '));
  } else if (stillRecalled.length) {
    console.log('  ' + stillRecalled.slice(0, 20).join('\n  ') + `\n  ... and ${stillRecalled.length - 20} more`);
  }
  console.log(`\nWrote data/pricing/observed.json`);
}

if (process.argv[1]?.endsWith('import-prices.ts')) main();
