import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import {
  GATES,
  SOURCE_TIER,
  STRICT_GATE_ARMS_AT,
  evidenceLadder,
  measuredShare,
  rowWeight,
  thinnestCells,
  type Fixture,
} from '../src/core/evidence.ts';

const ROOT = new URL('..', import.meta.url).pathname;
const real = (JSON.parse(readFileSync(join(ROOT, 'data/fixtures/fixtures.json'), 'utf8')) as { records: Fixture[] }).records;

const fx = (over: Partial<Fixture> = {}): Fixture => ({
  id: 'f', cpuId: 'c', gpuId: 'g', gameId: 'game', resolution: '1080p', preset: 'high', avgFps: 100, ...over,
});

describe('evidence ladder', () => {
  it('weights a measurement above any recollection', () => {
    const harness = rowWeight(fx({ provenance: 'harness' }));
    const bestRecall = rowWeight(fx({ provenance: 'model-knowledge', recallConfidence: 'high' }));
    expect(harness).toBeGreaterThan(bestRecall);
    // A measurement is a measurement regardless of how well it is remembered.
    expect(rowWeight(fx({ provenance: 'harness', recallConfidence: 'low' }))).toBe(harness);
  });

  it('scales recalled rows by stated recall confidence', () => {
    expect(rowWeight(fx({ provenance: 'model-knowledge', recallConfidence: 'high' }))).toBeCloseTo(0.25);
    expect(rowWeight(fx({ provenance: 'model-knowledge', recallConfidence: 'medium' }))).toBeCloseTo(0.15);
    expect(rowWeight(fx({ provenance: 'model-knowledge', recallConfidence: 'low' }))).toBeCloseTo(0.075);
  });

  it('treats an unrecognised provenance as recalled rather than trusting it', () => {
    // Silently defaulting an unknown tier to full weight would let a typo
    // promote the gate.
    const l = evidenceLadder([fx({ provenance: 'scraped-from-a-forum' })]);
    expect(l.measuredShare).toBe(0);
    expect(l.rungs[0].perRowWeight).toBe(0.25);
    expect(l.rungs[0].label).toMatch(/unrecognised/i);
  });

  it('reports zero measured share for an all-recalled set', () => {
    expect(measuredShare(real)).toBe(0);
    expect(evidenceLadder(real).strictGateArmed).toBe(false);
  });

  it('says how many harness rows would arm the strict gate, and is right', () => {
    const l = evidenceLadder(real);
    expect(l.harnessRowsToArmStrictGate).toBeGreaterThan(0);
    const promoted = [...real, ...Array.from({ length: l.harnessRowsToArmStrictGate }, () => fx({ provenance: 'harness' }))];
    expect(measuredShare(promoted)).toBeGreaterThanOrEqual(STRICT_GATE_ARMS_AT);
    // and one row fewer must NOT arm it, or the figure is padded
    const short = [...real, ...Array.from({ length: l.harnessRowsToArmStrictGate - 1 }, () => fx({ provenance: 'harness' }))];
    expect(measuredShare(short)).toBeLessThan(STRICT_GATE_ARMS_AT);
  });

  it('reports zero rows needed once the gate is already armed', () => {
    const armed = Array.from({ length: 10 }, () => fx({ provenance: 'harness' }));
    const l = evidenceLadder(armed);
    expect(l.strictGateArmed).toBe(true);
    expect(l.harnessRowsToArmStrictGate).toBe(0);
  });

  it('orders rungs best evidence first and shares sum to one', () => {
    const mixed = [
      fx({ provenance: 'model-knowledge' }),
      fx({ provenance: 'harness' }),
      fx({ provenance: 'reviewer' }),
      fx({ provenance: 'manual-measured' }),
    ];
    const l = evidenceLadder(mixed);
    expect(l.rungs.map((r) => r.tier)).toEqual(['harness', 'manual-measured', 'reviewer', 'model-knowledge']);
    expect(l.rungs.reduce((s, r) => s + r.weightShare, 0)).toBeCloseTo(1);
    expect(l.totalRows).toBe(4);
  });

  it('handles an empty set without dividing by zero', () => {
    const l = evidenceLadder([]);
    expect(l.measuredShare).toBe(0);
    expect(l.totalWeight).toBe(0);
    expect(l.rungs).toEqual([]);
    expect(Number.isFinite(l.harnessRowsToArmStrictGate)).toBe(true);
  });

  it('surfaces the thinnest cells first', () => {
    const cells = thinnestCells(real, (f) => f.stratum ?? 'unclassified', 3);
    expect(cells.length).toBe(3);
    // sorted ascending by evidence weight
    expect(cells[0].weight).toBeLessThanOrEqual(cells[1].weight);
    expect(cells[1].weight).toBeLessThanOrEqual(cells[2].weight);
    // and every fixture is accounted for somewhere
    const all = thinnestCells(real, (f) => f.stratum ?? 'unclassified', 999);
    expect(all.reduce((s, c) => s + c.rows, 0)).toBe(real.length);
  });

  it('shows a zero-coverage cell rather than omitting it', () => {
    // An output the tool offers but has never validated must not look the same
    // as one that does not exist.
    const set = [fx({ resolution: '1080p' }), fx({ resolution: '1440p' })];
    const withUniverse = thinnestCells(set, (f) => f.resolution, 14, ['1080p', '1440p', '2160p', '3440x1440']);
    expect(withUniverse.map((c) => c.key).sort()).toEqual(['1080p', '1440p', '2160p', '3440x1440']);
    expect(withUniverse[0].rows).toBe(0);
    expect(withUniverse.filter((c) => c.rows === 0).length).toBe(2);
    // and without the universe the untested resolutions are simply absent
    expect(thinnestCells(set, (f) => f.resolution, 14).length).toBe(2);
  });

  it('finds the real fixture set has no ultrawide coverage', () => {
    // Regression guard: 3440x1440 is offered as a target everywhere in the UI.
    // If a fixture for it ever lands, this test should be updated, not deleted.
    const byRes = thinnestCells(real, (f) => f.resolution, 14, ['1080p', '1440p', '2160p', '3440x1440']);
    const ultrawide = byRes.find((c) => c.key === '3440x1440');
    expect(ultrawide).toBeDefined();
    expect(ultrawide!.rows).toBe(0);
  });

  it('keeps the advisory tail gate looser than the strict one', () => {
    // Otherwise promotion would relax the gate rather than tighten it.
    expect(GATES.p90APEAdvisory).toBeGreaterThan(GATES.p90APE);
  });

  it('marks exactly the harness and manual tiers as measurements', () => {
    const measured = Object.entries(SOURCE_TIER).filter(([, v]) => v.measured).map(([k]) => k);
    expect(measured.sort()).toEqual(['harness', 'manual-measured']);
  });
});
