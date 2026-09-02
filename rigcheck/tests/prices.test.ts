import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { CURRENT_WINDOW_DAYS, aggregate, isObservationFile, parseObservations, weightedMedian, type Observation } from '../scripts/import-prices.ts';

const ROOT = new URL('..', import.meta.url).pathname;
const load = (p: string) => JSON.parse(readFileSync(join(ROOT, p), 'utf8'));
const validIds = new Set<string>([
  ...load('data/catalogue/gpus.json').records.map((r: { id: string }) => r.id),
  ...load('data/catalogue/cpus.json').records.map((r: { id: string }) => r.id),
]);

const HEAD = 'part_id,condition,basis,price,currency,source,observed_date,sample_size,note';
const obs = (over: Partial<Observation> = {}): Observation => ({
  partId: 'nvidia-geforce-rtx-3070', condition: 'used', basis: 'sold', price: 200,
  currency: 'GBP', source: 'ebay-uk', observedDate: '2026-08-15', sampleSize: 10,
  file: 't.csv', line: 2, ...over,
});
const TODAY = new Date('2026-08-20T00:00:00Z');

describe('price observation import', () => {
  it('accepts a well-formed row', () => {
    const { rows, rejected } = parseObservations(`${HEAD}\nnvidia-geforce-rtx-3070,used,sold,192,GBP,ebay-uk,2026-08-18,14,`, 't.csv', validIds);
    expect(rejected).toEqual([]);
    expect(rows[0]).toMatchObject({ partId: 'nvidia-geforce-rtx-3070', price: 192, sampleSize: 14, basis: 'sold' });
  });

  it('rejects a part id that is not in the catalogue, by name', () => {
    const { rows, rejected } = parseObservations(`${HEAD}\nrtx-3070,used,sold,192,GBP,ebay,2026-08-18,5,`, 't.csv', validIds);
    expect(rows).toEqual([]);
    expect(rejected[0].reason).toMatch(/not in the catalogue/);
  });

  it('rejects rather than guesses on every malformed field', () => {
    const bad = [
      'nvidia-geforce-rtx-3070,refurb,sold,192,GBP,e,2026-08-18,5,',
      'nvidia-geforce-rtx-3070,used,rumour,192,GBP,e,2026-08-18,5,',
      'nvidia-geforce-rtx-3070,used,sold,-5,GBP,e,2026-08-18,5,',
      'nvidia-geforce-rtx-3070,used,sold,192,BTC,e,2026-08-18,5,',
      'nvidia-geforce-rtx-3070,used,sold,192,GBP,e,18/08/2026,5,',
      'nvidia-geforce-rtx-3070,used,sold,192,GBP,e,2026-08-18,0,',
    ];
    for (const line of bad) {
      const { rows, rejected } = parseObservations(`${HEAD}\n${line}`, 't.csv', validIds);
      expect(rows).toEqual([]);
      expect(rejected).toHaveLength(1);
      expect(rejected[0].reason.length).toBeGreaterThan(10);
    }
  });

  it('rejects a price that is obviously a whole system', () => {
    const { rejected } = parseObservations(`${HEAD}\nnvidia-geforce-rtx-3070,used,sold,25000,GBP,e,2026-08-18,1,`, 't.csv', validIds);
    expect(rejected[0].reason).toMatch(/whole system or a bundle/);
  });

  it('names the missing columns rather than failing silently', () => {
    const { rejected } = parseObservations('part_id,price\nnvidia-geforce-rtx-3070,192', 't.csv', validIds);
    expect(rejected[0].reason).toMatch(/missing required column/);
    expect(rejected[0].reason).toMatch(/condition/);
  });

  it('treats example files as documentation, never data', () => {
    // A shipped example.csv of invented eBay medians was once imported as real
    // observations, and the app labelled them "sourced". Examples live in the
    // README now, and a file named example-anything is skipped by name.
    expect(isObservationFile('example.csv')).toBe(false);
    expect(isObservationFile('EXAMPLE-old.csv')).toBe(false);
    expect(isObservationFile('_scratch.csv')).toBe(false);
    expect(isObservationFile('2026-08-31.csv')).toBe(true);
    expect(isObservationFile('notes.txt')).toBe(false);
  });

  it('imports the real snapshot file cleanly', () => {
    const { rows, rejected } = parseObservations(readFileSync(join(ROOT, 'data/prices-observed/2026-08-31.csv'), 'utf8'), '2026-08-31.csv', validIds);
    expect(rejected).toEqual([]);
    expect(rows[0]).toMatchObject({ partId: 'intel-core-i7-7700', condition: 'used', basis: 'sold', price: 40 });
  });
});

describe('price aggregation', () => {
  it('takes a median, not a mean, so one collector sale cannot move it', () => {
    const rows = [obs({ price: 190, sampleSize: 20 }), obs({ price: 600, sampleSize: 1 })];
    const a = aggregate(rows, TODAY)[0];
    expect(a.price).toBe(190);
    expect(weightedMedian([{ value: 190, weight: 20 }, { value: 600, weight: 1 }])).toBe(190);
  });

  it('weights by sample size', () => {
    // Thirty sales at 150 outweigh one at 400, even though there are two rows.
    const a = aggregate([obs({ price: 150, sampleSize: 30 }), obs({ price: 400, sampleSize: 1 })], TODAY)[0];
    expect(a.price).toBe(150);
    expect(a.totalSamples).toBe(31);
  });

  it('discounts an all-asking group and says so', () => {
    const a = aggregate([obs({ basis: 'asking', price: 200, sampleSize: 8 })], TODAY)[0];
    expect(a.observedMedian).toBe(200);
    expect(a.price).toBe(170); // 15% off
    expect(a.containsAsking).toBe(true);
    expect(a.warnings.join(' ')).toMatch(/ASKING price, not a sale/);
  });

  it('does NOT discount when real sales are in the mix', () => {
    // The sold rows already anchor the median; discounting them too would push
    // the figure below what anyone actually paid.
    const a = aggregate([obs({ basis: 'sold', price: 200, sampleSize: 10 }), obs({ basis: 'asking', price: 260, sampleSize: 3 })], TODAY)[0];
    expect(a.price).toBe(a.observedMedian);
    expect(a.warnings.join(' ')).toMatch(/sold rows anchor the median/);
  });

  it('flags a thin sample', () => {
    expect(aggregate([obs({ sampleSize: 2 })], TODAY)[0].warnings.join(' ')).toMatch(/Only 2 sales/);
    expect(aggregate([obs({ sampleSize: 20 })], TODAY)[0].warnings.join(' ')).not.toMatch(/Only/);
  });

  it('flags a spread wide enough to mean the listings were not comparable', () => {
    const a = aggregate([obs({ price: 100 }), obs({ price: 300 })], TODAY)[0];
    expect(a.warnings.join(' ')).toMatch(/not comparable/);
    expect(a.spread).toEqual({ low: 100, high: 300 });
  });

  it('marks a price stale past 90 days and reports its age', () => {
    const fresh = aggregate([obs({ observedDate: '2026-08-15' })], TODAY)[0];
    expect(fresh.stale).toBe(false);
    expect(fresh.ageDays).toBe(5);
    const old = aggregate([obs({ observedDate: '2026-01-01' })], TODAY)[0];
    expect(old.stale).toBe(true);
    expect(old.warnings.join(' ')).toMatch(/historical note/);
  });

  it('keeps new and used as separate figures for the same part', () => {
    const a = aggregate([obs({ condition: 'used', price: 200 }), obs({ condition: 'new', price: 400 })], TODAY);
    expect(a).toHaveLength(2);
    expect(a.find((x) => x.condition === 'used')!.price).toBe(200);
    expect(a.find((x) => x.condition === 'new')!.price).toBe(400);
  });

  it('carries every source through so a figure stays traceable', () => {
    const a = aggregate([obs({ source: 'ebay-uk' }), obs({ source: 'cex' })], TODAY)[0];
    expect(a.sources.sort()).toEqual(['cex', 'ebay-uk']);
  });

  it('keeps one point per observation date, oldest first, so a trend can be read', () => {
    const a = aggregate([obs({ observedDate: '2026-08-15', price: 190 }), obs({ observedDate: '2026-07-01', price: 220 })], TODAY)[0];
    expect(a.series.map((p) => [p.date, p.price])).toEqual([['2026-07-01', 220], ['2026-08-15', 190]]);
    expect(a.firstDate).toBe('2026-07-01');
    expect(a.newestDate).toBe('2026-08-15');
  });

  it('prices from the newest window, so an old observation cannot drag the current figure', () => {
    // Fifty sales in June at 300 and five in August at 190. The June rows are
    // outside the window measured back from the newest observation: they stay
    // in the series as history and do not vote on what the part costs now.
    expect(CURRENT_WINDOW_DAYS).toBe(45);
    const a = aggregate([obs({ observedDate: '2026-06-01', price: 300, sampleSize: 50 }), obs({ observedDate: '2026-08-15', price: 190, sampleSize: 5 })], TODAY)[0];
    expect(a.price).toBe(190);
    expect(a.totalSamples).toBe(5);
    expect(a.series).toHaveLength(2);
  });

  it('discounts a date on which every row was an asking price, and only that date', () => {
    const a = aggregate([obs({ observedDate: '2026-08-01', basis: 'asking', price: 200 }), obs({ observedDate: '2026-08-15', basis: 'sold', price: 180 })], TODAY)[0];
    expect(a.series[0]).toMatchObject({ price: 170, basis: 'asking' });
    expect(a.series[1]).toMatchObject({ price: 180, basis: 'sold' });
  });
});

// ---------------------------------------------------------------------------
// The three-tier lookup the UI renders from. An operator override beats a
// sourced observation beats the recalled seed, and the ORIGIN travels with the
// number so a screen can show that they are not the same kind of thing.
// ---------------------------------------------------------------------------

import { findObserved, plannerTables, priceCoverage, pricedLookup, type ObservedPrice, type PriceTable } from '../src/ui/pricing.ts';

const observedRow = (over: Partial<ObservedPrice> = {}): ObservedPrice => ({
  partId: 'nvidia-geforce-rtx-3070', condition: 'used', price: 192, observedMedian: 192,
  currency: 'GBP', totalSamples: 14, observations: 1, spread: { low: 180, high: 210 },
  newestDate: '2026-08-18', firstDate: '2026-08-18', ageDays: 2, stale: false, containsAsking: false,
  sources: ['ebay-uk'], warnings: [],
  series: [{ date: '2026-08-18', price: over.price ?? 192, sampleSize: 14, basis: 'sold' }],
  ...over,
});

type Override = { new: Record<string, number>; used: Record<string, number> };

const tables = (over: Partial<{ newP: PriceTable; usedP: PriceTable; override: Override; observed: ObservedPrice[] }> = {}) => ({
  newP: { currency: 'GBP', updated: '2026-05', prices: { 'nvidia-geforce-rtx-3070': 400 } } as PriceTable,
  usedP: { currency: 'GBP', updated: '2026-05', prices: { 'nvidia-geforce-rtx-3070': 230 } } as PriceTable,
  override: { new: {}, used: {} } as Override,
  observed: [],
  ...over,
});

describe('price provenance', () => {
  it('prefers a sourced observation over the recalled seed', () => {
    const look = pricedLookup(tables({ observed: [observedRow()] }), 'used');
    const r = look('nvidia-geforce-rtx-3070');
    expect(r.value).toBe(192);
    expect(r.origin).toBe('observed');
    expect(r.observed?.totalSamples).toBe(14);
  });

  it('falls back to the seed and labels it as recalled', () => {
    const r = pricedLookup(tables(), 'used')('nvidia-geforce-rtx-3070');
    expect(r.value).toBe(230);
    expect(r.origin).toBe('recalled-seed');
    expect(r.observed).toBeUndefined();
  });

  it('lets an operator override beat everything, because they know their own price', () => {
    const r = pricedLookup(tables({ observed: [observedRow()], override: { new: {}, used: { 'nvidia-geforce-rtx-3070': 100 } } }), 'used')('nvidia-geforce-rtx-3070');
    expect(r.value).toBe(100);
    expect(r.origin).toBe('operator-override');
  });

  it('reports no price rather than zero for an unpriced part', () => {
    expect(pricedLookup(tables(), 'used')('amd-radeon-rx-9070-xt').origin).toBe('none');
  });

  it('does not answer a new-condition question with the operator\'s used price', () => {
    // The override used to be one flat map covering both conditions, so a used
    // figure somebody typed became the new price too — and on the Trade Desk,
    // where the whole screen is the gap between the two, that closed the gap to
    // zero and reported every part as worth exactly what it costs new.
    const t = tables({ override: { new: {}, used: { 'nvidia-geforce-rtx-3070': 100 } } });
    expect(pricedLookup(t, 'used')('nvidia-geforce-rtx-3070').value).toBe(100);
    const asNew = pricedLookup(t, 'new')('nvidia-geforce-rtx-3070');
    expect(asNew.value).toBe(400);
    expect(asNew.origin).toBe('recalled-seed');
  });

  it('uses the other condition before falling back to a recalled figure', () => {
    // A sourced new price beats a recalled used one: sourced-but-wrong-condition
    // is still evidence, and the seed is not.
    const r = pricedLookup(tables({ observed: [observedRow({ condition: 'new', price: 305 })] }), 'used')('nvidia-geforce-rtx-3070');
    expect(r.origin).toBe('observed');
    expect(r.value).toBe(305);
  });

  it('merges observations over the seed for the planner', () => {
    const t = plannerTables(tables({ observed: [observedRow()] }));
    expect(t.usedP['nvidia-geforce-rtx-3070']).toBe(192);
    expect(t.newP['nvidia-geforce-rtx-3070']).toBe(400);
  });

  it('reports how much of the price data is actually sourced', () => {
    const c = priceCoverage(tables({ observed: [observedRow(), observedRow({ stale: true, partId: 'nvidia-geforce-rtx-3070' })] }));
    expect(c.seeded).toBe(1);
    expect(c.sourced).toBe(1);
    expect(c.sourcedShare).toBe(1);
    expect(c.staleCount).toBe(1);
  });

  it('finds an observation by part and condition, not by part alone', () => {
    const list = [observedRow({ condition: 'used', price: 192 }), observedRow({ condition: 'new', price: 400 })];
    expect(findObserved(list, 'nvidia-geforce-rtx-3070', 'used')!.price).toBe(192);
    expect(findObserved(list, 'nvidia-geforce-rtx-3070', 'new')!.price).toBe(400);
    expect(findObserved(list, 'nope', 'used')).toBeUndefined();
  });
});

// ---------------------------------------------------------------------------
// Old parts are priced from the resale market or not at all. A used lookup
// used to fall through to a new price when nothing else existed, and the new
// price of a 2017 processor is a launch-era figure: that is how a chip that
// changes hands for £40 gets shown at £300.
// ---------------------------------------------------------------------------

const TODAY_26 = new Date('2026-09-02T00:00:00Z');
const years = (id: string) => (id === 'intel-core-i7-7700' ? 2017 : id === 'amd-radeon-rx-9070' ? 2025 : undefined);

describe('resale-only rule for old parts', () => {
  it('never answers a used question about an old part with a new price', () => {
    const t = tables({
      newP: { currency: 'GBP', updated: '2026-05', prices: { 'intel-core-i7-7700': 300 } },
      usedP: { currency: 'GBP', updated: '2026-05', prices: {} },
      observed: [observedRow({ partId: 'intel-core-i7-7700', condition: 'new', price: 300 })],
    });
    const r = pricedLookup(t, 'used', years, TODAY_26)('intel-core-i7-7700');
    expect(r.origin).toBe('none');
    expect(r.reason).toBe('resale-only');
    expect(r.value).toBe(0);
    // Without the rule the same lookup hands back the launch-era figure.
    expect(pricedLookup(t, 'used')('intel-core-i7-7700').value).toBe(300);
  });

  it('a resale observation answers it', () => {
    const t = tables({ observed: [observedRow({ partId: 'intel-core-i7-7700', condition: 'used', price: 40 })] });
    expect(pricedLookup(t, 'used', years, TODAY_26)('intel-core-i7-7700')).toMatchObject({ value: 40, origin: 'observed' });
  });

  it('a recalled used seed is still allowed, labelled as recalled', () => {
    const t = tables({ usedP: { currency: 'GBP', updated: '2026-05', prices: { 'intel-core-i7-7700': 55 } } });
    expect(pricedLookup(t, 'used', years, TODAY_26)('intel-core-i7-7700')).toMatchObject({ value: 55, origin: 'recalled-seed' });
  });

  it('leaves a recent part alone', () => {
    const t = tables({
      newP: { currency: 'GBP', updated: '2026-05', prices: { 'amd-radeon-rx-9070': 520 } },
      usedP: { currency: 'GBP', updated: '2026-05', prices: {} },
    });
    expect(pricedLookup(t, 'used', years, TODAY_26)('amd-radeon-rx-9070')).toMatchObject({ value: 520, origin: 'recalled-seed' });
  });

  it('does nothing when no launch-year lookup is supplied', () => {
    const t = tables({ newP: { currency: 'GBP', updated: '2026-05', prices: { 'intel-core-i7-7700': 300 } }, usedP: { currency: 'GBP', updated: '2026-05', prices: {} } });
    expect(pricedLookup(t, 'used')('intel-core-i7-7700').value).toBe(300);
  });
});
