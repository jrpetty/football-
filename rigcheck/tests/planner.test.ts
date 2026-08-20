import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { buildEngineData } from '../src/core/catalogue.ts';
import { planBuild, type ComponentPrices, type PlanRequest } from '../src/core/planner.ts';
import { PRESETS } from '../src/core/presets.ts';

const ROOT = new URL('..', import.meta.url).pathname;
const load = (p: string) => JSON.parse(readFileSync(join(ROOT, p), 'utf8'));
const data = buildEngineData({
  gpus: load('data/catalogue/gpus.json'),
  cpus: load('data/catalogue/cpus.json'),
  games: load('data/catalogue/games.json'),
  references: load('data/catalogue/references.json'),
});
const prices = { newP: load('data/pricing/gbp-new.json').prices, usedP: load('data/pricing/gbp-used.json').prices };
const comp = load('data/pricing/components-gbp.json') as ComponentPrices;

const req = (over: Partial<PlanRequest> = {}): PlanRequest => ({
  budget: 1200,
  condition: 'new',
  resolution: '1440p',
  refreshHz: 144,
  gameIds: ['counter-strike-2', 'cyberpunk-2077', 'baldurs-gate-3'],
  ...over,
});

describe('build planner', () => {
  it('never recommends a build over budget', () => {
    for (const b of [600, 900, 1200, 2000, 3000]) {
      const r = planBuild(req({ budget: b }), data, prices, comp);
      if (r.pick) expect(r.pick.total).toBeLessThanOrEqual(b);
      for (const c of r.candidates) expect(c.total).toBeLessThanOrEqual(b);
      if (r.maxOut) expect(r.maxOut.total).toBeLessThanOrEqual(b);
    }
  });

  it('prices the whole machine, not just the CPU and GPU', () => {
    // A plan that forgets the board, memory, storage, PSU, case and cooler
    // understates a build by hundreds and leaves someone unable to finish it.
    const r = planBuild(req(), data, prices, comp);
    const cats = new Set(r.pick!.bom.map((l) => l.category));
    for (const c of ['GPU', 'CPU', 'Motherboard', 'Memory', 'Storage', 'PSU', 'Case', 'Cooler']) {
      expect(cats).toContain(c);
    }
    expect(r.pick!.bom.reduce((s, l) => s + l.price, 0)).toBe(r.pick!.total);
  });

  it('never budgets a free cooler', () => {
    // Whether a SKU ships with one is not in this catalogue, and a plan that is
    // short a cooler on arrival is worse than one that is 32 over.
    for (const b of [700, 1200, 2500]) {
      const r = planBuild(req({ budget: b }), data, prices, comp);
      const cooler = r.pick?.bom.find((l) => l.category === 'Cooler');
      if (cooler) expect(cooler.price).toBeGreaterThan(0);
    }
  });

  it('sizes the PSU from the transient peak, not the average', () => {
    const r = planBuild(req({ budget: 2500, resolution: '2160p', refreshHz: 120 }), data, prices, comp);
    const psu = r.pick!.bom.find((l) => l.category === 'PSU')!;
    const watts = Number(/(\d+)W/.exec(psu.label)![1]);
    expect(watts).toBeGreaterThanOrEqual(r.pick!.transientPeakW);
    expect(watts).toBeGreaterThan(r.pick!.powerW);
  });

  it('matches the motherboard to the CPU socket', () => {
    const r = planBuild(req(), data, prices, comp);
    expect(r.pick!.bom.find((l) => l.category === 'Motherboard')!.label).toContain(r.pick!.cpu.socket);
  });

  it('matches the memory type to the platform', () => {
    // DDR4 on AM5 is impossible and DDR5 on AM4 is impossible; getting this
    // wrong produces a parts list that cannot be assembled.
    for (const b of [700, 1500, 2500]) {
      const r = planBuild(req({ budget: b }), data, prices, comp);
      if (!r.pick) continue;
      const mem = r.pick.bom.find((l) => l.category === 'Memory')!;
      const type = /DDR\d/.exec(mem.label)![0];
      expect(r.pick.cpu.memoryType).toContain(type);
      expect(r.pick.build.ram.type).toBe(type);
    }
  });

  it('honours the quality floor rather than hitting the target at low settings', () => {
    // Without a floor, "cheapest build that clears 120fps at 4K" is satisfied by
    // a mid card running everything at low. That meets the letter of the request
    // and none of its intent.
    const r = planBuild(
      req({ budget: 2500, resolution: '2160p', refreshHz: 120, minPreset: 'high' }),
      data,
      prices,
      comp,
    );
    expect(r.pick).not.toBeNull();
    expect(PRESETS.indexOf(r.pick!.typicalPreset as never)).toBeGreaterThanOrEqual(PRESETS.indexOf('high'));
  });

  it('says so when it had to relax the quality floor', () => {
    const r = planBuild(
      req({ budget: 700, resolution: '2160p', refreshHz: 144, minPreset: 'highest' }),
      data,
      prices,
      comp,
    );
    if (r.pick && PRESETS.indexOf(r.pick.typicalPreset as never) < PRESETS.indexOf('highest')) {
      expect(r.pickBasis).toMatch(/relaxed/i);
    }
  });

  it('recommends the cheapest build that meets the goal, not the most expensive one that fits', () => {
    const r = planBuild(req({ budget: 3000, resolution: '1080p', refreshHz: 60, gameIds: ['counter-strike-2'] }), data, prices, comp);
    expect(r.pick).not.toBeNull();
    // 60fps at 1080p in an esports title does not need three thousand pounds.
    expect(r.pick!.total).toBeLessThan(1500);
    expect(r.pickBasis).toMatch(/Cheapest build/);
  });

  it('explains what the rest of the budget would buy, in quality rather than frames', () => {
    const r = planBuild(req({ budget: 2500, resolution: '2160p', refreshHz: 120 }), data, prices, comp);
    if (r.maxOut) {
      expect(r.maxOut.total).toBeGreaterThan(r.pick!.total);
      expect(r.maxOutVerdict).toBeTruthy();
      // Once every build clears the target, extra money buys looks, not frames,
      // and the copy must not claim otherwise.
      if (r.maxOut.targetHitRate <= r.pick!.targetHitRate + 0.01) {
        expect(r.maxOutVerdict).toMatch(/does not buy more frames|visual headroom/i);
      }
    }
  });

  it('refuses an impossible budget with the real minimum, not an empty result', () => {
    const r = planBuild(req({ budget: 250 }), data, prices, comp);
    expect(r.pick).toBeNull();
    expect(r.minimumViableTotal).toBeGreaterThan(250);
    expect(r.warnings.join(' ')).toMatch(/cheapest complete machine/i);
  });

  it('reports a request with no games rather than planning against nothing', () => {
    const r = planBuild(req({ gameIds: [] }), data, prices, comp);
    expect(r.warnings.join(' ')).toMatch(/no games/i);
  });

  it('finishes fast enough to run on a keystroke', () => {
    const t0 = Date.now();
    planBuild(req({ budget: 2000 }), data, prices, comp);
    expect(Date.now() - t0).toBeLessThan(1500);
  });
});
