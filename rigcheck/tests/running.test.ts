import { describe, expect, it } from 'vitest';
import {
  DEFAULT_USAGE, PSU_EFFICIENCY, efficiencyPayback, psuEfficiency, runningCost,
  totalCostOfOwnership, type PsuTier, type UsageProfile,
} from '../src/core/running.ts';

const usage: UsageProfile = { ...DEFAULT_USAGE, gamingHoursPerWeek: 20, idleHoursPerWeek: 20, pencePerKwh: 25, ownershipYears: 4 };

describe('PSU efficiency curve', () => {
  it('hits the certified points', () => {
    for (const tier of Object.keys(PSU_EFFICIENCY) as PsuTier[]) {
      expect(psuEfficiency(tier, 0.2)).toBeCloseTo(PSU_EFFICIENCY[tier].at20, 3);
      expect(psuEfficiency(tier, 0.5)).toBeCloseTo(PSU_EFFICIENCY[tier].at50, 3);
      expect(psuEfficiency(tier, 1.0)).toBeCloseTo(PSU_EFFICIENCY[tier].at100, 3);
    }
  });

  it('peaks near half load, as the standard describes', () => {
    for (const tier of ['bronze', 'gold', 'titanium'] as PsuTier[]) {
      expect(psuEfficiency(tier, 0.5)).toBeGreaterThan(psuEfficiency(tier, 0.2));
      expect(psuEfficiency(tier, 0.5)).toBeGreaterThan(psuEfficiency(tier, 1.0));
    }
  });

  it('falls away below 20% load rather than holding flat', () => {
    // Holding it flat would make a wildly oversized supply look free.
    expect(psuEfficiency('gold', 0.08)).toBeLessThan(psuEfficiency('gold', 0.2));
    expect(psuEfficiency('gold', 0.02)).toBeGreaterThan(0.5);
  });

  it('orders the tiers correctly at every load', () => {
    for (const f of [0.1, 0.3, 0.5, 0.8, 1.0]) {
      expect(psuEfficiency('titanium', f)).toBeGreaterThan(psuEfficiency('gold', f));
      expect(psuEfficiency('gold', f)).toBeGreaterThan(psuEfficiency('bronze', f));
      expect(psuEfficiency('bronze', f)).toBeGreaterThan(psuEfficiency('none', f));
    }
  });

  it('does not pretend to model operation above the rating', () => {
    expect(psuEfficiency('gold', 1.4)).toBe(PSU_EFFICIENCY.gold.at100);
  });
});

describe('running cost', () => {
  it('charges for the power supply, not just the components', () => {
    // The single most common omission in "what does my PC cost to run".
    const r = runningCost(400, 750, 'gold', usage);
    expect(r.loadWallW).toBeGreaterThan(r.loadComponentW);
    expect(r.loadWallW).toBeCloseTo(400 / r.efficiencyAtLoad, 1);
  });

  it('makes a better tier cheaper to run at the same components', () => {
    const bronze = runningCost(400, 750, 'bronze', usage);
    const titanium = runningCost(400, 750, 'titanium', usage);
    expect(titanium.costPerYear).toBeLessThan(bronze.costPerYear);
    expect(titanium.loadWallW).toBeLessThan(bronze.loadWallW);
  });

  it('idles far below load rather than scaling from it', () => {
    // A 4090 idles near 20W, not near 90. Scaling the load figure would be wrong.
    const r = runningCost(600, 1000, 'gold', usage);
    expect(r.idleComponentW).toBeLessThan(r.loadComponentW * 0.25);
    expect(r.idleComponentW).toBeGreaterThan(20);
  });

  it('penalises a wildly oversized supply', () => {
    const right = runningCost(300, 650, 'gold', usage);
    const huge = runningCost(300, 1600, 'gold', usage);
    expect(huge.efficiencyAtLoad).toBeLessThan(right.efficiencyAtLoad);
    expect(huge.costPerYear).toBeGreaterThan(right.costPerYear);
    expect(huge.notes.join(' ')).toMatch(/below the point 80 Plus certifies/);
  });

  it('warns when the supply is near its limit under sustained load', () => {
    expect(runningCost(600, 650, 'gold', usage).notes.join(' ')).toMatch(/past its peak/);
  });

  it('scales with the tariff and the hours', () => {
    const cheap = runningCost(400, 750, 'gold', { ...usage, pencePerKwh: 10 });
    const dear = runningCost(400, 750, 'gold', { ...usage, pencePerKwh: 40 });
    expect(dear.costPerYear / cheap.costPerYear).toBeCloseTo(4, 1);
    const more = runningCost(400, 750, 'gold', { ...usage, gamingHoursPerWeek: 40 });
    expect(more.kwhPerYear).toBeGreaterThan(runningCost(400, 750, 'gold', usage).kwhPerYear);
  });

  it('produces a figure in the right order of magnitude', () => {
    // 400W of components, 20h gaming + 20h idle a week, 25p/kWh. Sanity, not precision.
    const r = runningCost(400, 750, 'gold', usage);
    expect(r.costPerYear).toBeGreaterThan(20);
    expect(r.costPerYear).toBeLessThan(200);
  });

  it('separates out what the power supply itself wastes', () => {
    const r = runningCost(500, 850, 'bronze', usage);
    expect(r.psuWasteCostOverOwnership).toBeGreaterThan(0);
    expect(r.psuWasteCostOverOwnership).toBeLessThan(r.costOverOwnership);
  });
});

describe('total cost of ownership', () => {
  it('adds electricity to the purchase price and says what share it is', () => {
    const r = runningCost(450, 850, 'gold', usage);
    const t = totalCostOfOwnership(1200, r, usage);
    expect(t.total).toBeCloseTo(1200 + r.costOverOwnership, 5);
    expect(t.runningShare).toBeGreaterThan(0);
    expect(t.runningShare).toBeLessThan(1);
    expect(t.verdict).toMatch(/a year to run/);
  });

  it('tells you when a better power supply would NOT pay for itself', () => {
    // The honest direction. Most of the time it does not.
    const p = efficiencyPayback(300, 650, 'gold', 'titanium', 60, usage);
    expect(p.worthIt).toBe(false);
    expect(p.detail).toMatch(/Buy it for the build quality/);
  });

  it('tells you when it would', () => {
    // High draw, many hours, expensive electricity, cheap upgrade.
    const heavy: UsageProfile = { gamingHoursPerWeek: 45, idleHoursPerWeek: 60, pencePerKwh: 40, ownershipYears: 6 };
    const p = efficiencyPayback(600, 1000, 'none', 'gold', 40, heavy);
    expect(p.worthIt).toBe(true);
    expect(p.paybackYears!).toBeLessThan(6);
  });

  it('quotes an annual saving that multiplies back to the headline total', () => {
    // The quoted per-year figure and the over-ownership total are two separate
    // roundings of the same quantity. Rounding the small one to whole pounds
    // made them disagree on screen — "saves £2 a year" beside "£9 over 4
    // years" — which reads as an arithmetic error to anyone who checks.
    for (const [load, watts, from, to] of [
      [300, 650, 'gold', 'platinum'],
      [180, 550, 'gold', 'platinum'],
      [600, 1000, 'none', 'bronze'],
      [450, 850, 'bronze', 'silver'],
    ] as [number, number, PsuTier, PsuTier][]) {
      const p = efficiencyPayback(load, watts, from, to, 30, usage);
      const quoted = Number(/saves £([\d.]+) a year/.exec(p.detail)?.[1] ?? NaN);
      expect(Number.isFinite(quoted)).toBe(true);
      // Whole pounds either way: the two displayed numbers must agree.
      expect(Math.round(quoted * usage.ownershipYears)).toBe(Math.round(p.savingOverOwnership));
    }
  });

  it('never claims a payback when the better tier saves nothing', () => {
    const idle: UsageProfile = { gamingHoursPerWeek: 0, idleHoursPerWeek: 0, pencePerKwh: 25, ownershipYears: 4 };
    const p = efficiencyPayback(300, 650, 'gold', 'titanium', 30, idle);
    expect(p.paybackYears).toBeNull();
    expect(p.worthIt).toBe(false);
    expect(p.detail).toMatch(/saves nothing measurable/);
  });
});
