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
