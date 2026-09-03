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
