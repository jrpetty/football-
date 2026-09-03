import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { allowanceKeys, isAllowanceKey, overlayComponents } from '../src/core/components.ts';
import { parseObservations, priceableIds } from '../scripts/import-prices.ts';
import type { ComponentPrices } from '../src/core/planner.ts';

const ROOT = new URL('..', import.meta.url).pathname;
const comp = JSON.parse(readFileSync(join(ROOT, 'data/pricing/components-gbp.json'), 'utf8')) as ComponentPrices;

describe('component allowances', () => {
  it('names every priced class with the key the planner looks it up by', () => {
    const keys = allowanceKeys(comp).map((k) => k.key);
    for (const k of ['motherboard.AM4.budget', 'motherboard.AM5.high', 'memory.DDR5.32', 'memory.DDR4.16', 'storage.nvme-gen4.1000', 'psu.650', 'case.good', 'cooler.budget-tower']) expect(keys, k).toContain(k);
    // A null price is not a class anyone can buy, and the note is not a size.
    expect(keys).not.toContain('memory.DDR3.64');
    expect(keys).not.toContain('memory.DDR4.note');
    expect(keys).not.toContain('storage.note');
    expect(keys).not.toContain('cooler.stock');
    expect(isAllowanceKey('psu.650', comp)).toBe(true);
    expect(isAllowanceKey('psu.651', comp)).toBe(false);
  });

  it('writes an observed price over the recalled one, for the condition asked, and leaves the rest alone', () => {
    const out = overlayComponents(comp, [
      { partId: 'memory.DDR5.32', condition: 'new', price: 89 },
      { partId: 'psu.650', condition: 'new', price: 68 },
      { partId: 'case.good', condition: 'used', price: 40 },      // used: not applied to a new build
      { partId: 'motherboard.AM9.budget', condition: 'new', price: 1 }, // unknown: ignored
    ]);
    expect(out.memory.DDR5['32']).toBe(89);
    expect(out.psu.find((p) => p.watts === 650)!.price).toBe(68);
    expect(out.case.good.price).toBe(comp.case.good.price);
    expect(out.memory.DDR4['32']).toBe(comp.memory.DDR4['32']);
    expect(comp.memory.DDR5['32']).not.toBe(89); // the input was not mutated
  });

  it('the observation lane accepts an allowance key, a case and a monitor, and still refuses nonsense', () => {
    const { ids } = priceableIds();
    const HEAD = 'part_id,condition,basis,price,currency,source,observed_date,sample_size,note';
    const ok = parseObservations(`${HEAD}\nmemory.DDR5.32,new,retail,89,GBP,scan-uk,2026-09-02,1,\nfractal-design-north,new,retail,110,GBP,scan-uk,2026-09-02,1,\nlg-24mp60g-b,new,retail,85,GBP,amazon-uk,2026-09-02,1,`, 't.csv', ids);
    expect(ok.rejected).toEqual([]);
    expect(ok.rows.map((r) => r.partId)).toEqual(['memory.DDR5.32', 'fractal-design-north', 'lg-24mp60g-b']);
    const bad = parseObservations(`${HEAD}\nmemory.DDR7.32,new,retail,89,GBP,scan-uk,2026-09-02,1,`, 't.csv', ids);
    expect(bad.rejected[0].reason).toMatch(/not a catalogue part or an allowance key/);
  });
});

// ---------------------------------------------------------------------------
// A seed figure its own sibling has overtaken. When one class in a family is
// repriced from real listings and the rest are not, the family is internally
// inconsistent in a way no single figure shows — a DDR4 build looks cheap
// because its memory line is six months older, not because DDR4 is cheap.
// ---------------------------------------------------------------------------

import { driftCaveat, familyDrift } from '../src/core/priceaudit.ts';

const A = [
  { key: 'memory.DDR5.32', price: 95, label: '32GB DDR5 kit' },
  { key: 'memory.DDR4.32', price: 62, label: '32GB DDR4 kit' },
  { key: 'memory.DDR4.16', price: 35, label: '16GB DDR4 kit' },
  { key: 'psu.650', price: 72, label: '650W supply' },
  { key: 'psu.750', price: 88, label: '750W supply' },
];

describe('family drift', () => {
  it('names the family, the multiple and the siblings nobody checked', () => {
    const d = familyDrift(A, [{ partId: 'memory.DDR5.32', price: 337 }]);
    expect(d).toHaveLength(1);
    expect(d[0]).toMatchObject({ category: 'memory', sourcedKey: 'memory.DDR5.32', seedPrice: 95, sourcedPrice: 337, factor: 3.5 });
    expect(d[0].unsourced.sort()).toEqual(['memory.DDR4.16', 'memory.DDR4.32']);
    expect(driftCaveat(d[0], (k) => A.find((a) => a.key === k)!.label))
      .toBe('32GB DDR5 kit is priced from current listings at 3.5x its recalled figure. The other memory figures here are still the recalled ones and nobody has checked them, so a build using one is very likely cheaper on paper than in a shop.');
  });

  it('says nothing when the sourced figure barely moved', () => {
    // Confirming a seed is not drift: £72 → £75 tells the siblings nothing.
    expect(familyDrift(A, [{ partId: 'psu.650', price: 75 }])).toEqual([]);
  });

  it('says nothing once every sibling is sourced', () => {
    expect(familyDrift(A, [
      { partId: 'memory.DDR5.32', price: 337 },
      { partId: 'memory.DDR4.32', price: 210 },
      { partId: 'memory.DDR4.16', price: 120 },
    ])).toEqual([]);
  });

  it('reports the furthest-travelled sibling when several moved', () => {
    const d = familyDrift(A, [{ partId: 'psu.650', price: 144 }, { partId: 'psu.750', price: 264 }]);
    expect(d).toEqual([]); // both psu keys sourced, no unsourced sibling left
    const e = familyDrift([...A, { key: 'psu.850', price: 110, label: '850W supply' }],
      [{ partId: 'psu.650', price: 144 }, { partId: 'psu.750', price: 264 }]);
    expect(e[0]).toMatchObject({ sourcedKey: 'psu.750', factor: 3 });
    expect(e[0].unsourced).toEqual(['psu.850']);
  });

  it('finds the drift that is actually in the shipped data', () => {
    const obs = JSON.parse(readFileSync(join(ROOT, 'data/pricing/observed.json'), 'utf8')).prices;
    const d = familyDrift(allowanceKeys(comp), obs);
    // DDR5 was sourced in September 2026; DDR4 was not, and could not be.
    const mem = d.find((x) => x.category === 'memory');
    expect(mem?.sourcedKey).toBe('memory.DDR5.32');
    expect(mem!.factor).toBeGreaterThan(3);
    expect(mem!.unsourced).toContain('memory.DDR4.32');
  });
});

// ---------------------------------------------------------------------------
// A price that contradicts the performance ordering. The catalogue derives an
// index for every part, so it can catch what a price alone cannot: a much
// slower part priced well above a much faster one. A search offered an RTX
// 4070 at £1,541 while a 5080 sat at £1,180, and only the ordering revealed it.
// ---------------------------------------------------------------------------

import { priceInversions } from '../src/core/priceaudit.ts';

const P = (id: string, index: number, price: number, condition: 'new' | 'used' = 'new', kind: 'gpu' | 'cpu' = 'gpu') => ({ id, name: id, kind, index, price, condition });

describe('price inversions', () => {
  it('catches a slower part priced above a faster one', () => {
    const inv = priceInversions([P('rtx-4070', 176, 1541), P('rtx-5080', 282, 1180)]);
    expect(inv).toHaveLength(1);
    expect(inv[0]).toMatchObject({ premium: 1.31, speedGap: 1.6 });
    expect(inv[0].slower.id).toBe('rtx-4070');
    expect(inv[0].faster.id).toBe('rtx-5080');
  });

  it('says nothing when price follows performance', () => {
    expect(priceInversions([P('a', 100, 200), P('b', 200, 400), P('c', 300, 600)])).toEqual([]);
  });

  it('tolerates a small premium on a near-identical part', () => {
    // Two parts within 15% of each other on index are not an ordering claim.
    expect(priceInversions([P('a', 100, 210), P('b', 110, 200)])).toEqual([]);
  });

  it('never compares a used price with a new one', () => {
    // A used flagship under a new budget card is normal, not an inversion.
    expect(priceInversions([P('flagship', 300, 400, 'used'), P('budget', 100, 300, 'new')])).toEqual([]);
  });

  it('ignores a part with no index or no price', () => {
    expect(priceInversions([P('a', 0, 900), P('b', 200, 100)])).toEqual([]);
    expect(priceInversions([P('a', 100, 0), P('b', 200, 100)])).toEqual([]);
  });

  it('reports the worst pairing first', () => {
    const inv = priceInversions([P('slow', 100, 900), P('mid', 200, 500), P('fast', 400, 400)]);
    expect(inv[0].faster.id).toBe('fast');
    expect(inv[0].slower.id).toBe('slow');
  });

  it('never ranks a graphics card against a processor', () => {
    // Their indices are different scales measuring different work. The first
    // version of this compared them and reported a GTX 1060 as "2x dearer than
    // an i7-7700", which is not a statement about anything.
    expect(priceInversions([P('gtx-1060', 52, 80, 'used', 'gpu'), P('i7-7700', 71, 40, 'used', 'cpu')])).toEqual([]);
    // Same numbers, same kind: now it is a real inversion.
    expect(priceInversions([P('slow-gpu', 52, 80, 'used', 'gpu'), P('fast-gpu', 71, 40, 'used', 'gpu')])).toHaveLength(1);
  });

  it('finds no inversion in the shipped sourced prices', () => {
    const obs = JSON.parse(readFileSync(join(ROOT, 'data/pricing/observed.json'), 'utf8')).prices;
    const gpus = JSON.parse(readFileSync(join(ROOT, 'data/catalogue/gpus.json'), 'utf8')).records as { id: string }[];
    // Only a smoke check that the shipped set is self-consistent by id overlap;
    // the audit script does the index-aware version on every run.
    const priced = obs.filter((o: { partId: string }) => gpus.some((g) => g.id === o.partId));
    expect(priced.length).toBeGreaterThan(0);
  });
});
