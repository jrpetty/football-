import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { buildEngineData } from '../src/core/catalogue.ts';
import { diagnose, type DetectedSystem, type Measurement } from '../src/core/health.ts';

const ROOT = new URL('..', import.meta.url).pathname;
const load = (p: string) => JSON.parse(readFileSync(join(ROOT, p), 'utf8'));
const data = buildEngineData({
  gpus: load('data/catalogue/gpus.json'),
  cpus: load('data/catalogue/cpus.json'),
  games: load('data/catalogue/games.json'),
  references: load('data/catalogue/references.json'),
});

const healthy: DetectedSystem = {
  build: {
    id: 'h', cpuId: 'amd-ryzen-7-7800x3d', gpuId: 'nvidia-geforce-rtx-4070',
    ram: { totalGB: 32, channels: 2, speedMTs: 6000, type: 'DDR5' },
    storage: 'nvme-gen4', target: { resolution: '1440p', refreshHz: 144 },
  },
  memory: { modulesPopulated: 2, slotsTotal: 4, ratedSpeedMTs: 6000, configuredSpeedMTs: 6000 },
  pcieLink: { gen: 4, width: 16 },
  cooling: { airflow: 'good' },
  psuWatts: 850,
  uptimeDays: 1,
};

const sick: DetectedSystem = {
  build: {
    id: 's', cpuId: 'amd-ryzen-5-5600', gpuId: 'nvidia-geforce-rtx-4070',
    ram: { totalGB: 16, channels: 1, speedMTs: 2133, type: 'DDR4' },
    storage: 'hdd', target: { resolution: '1440p', refreshHz: 144 },
  },
  memory: { modulesPopulated: 1, slotsTotal: 4, ratedSpeedMTs: 3600, configuredSpeedMTs: 2133 },
  pcieLink: { gen: 3, width: 4 },
  cooling: { airflow: 'restricted' },
  psuWatts: 400,
  driverDate: '2023-06-01',
  uptimeDays: 30,
};

describe('system health', () => {
  it('finds nothing wrong with a correctly configured machine', () => {
    const r = diagnose(healthy, [], data);
    expect(r.findings.filter((f) => f.severity === 'critical' || f.severity === 'major')).toEqual([]);
    expect(r.healthy.length).toBeGreaterThan(2);
  });

  it('never claims a clean report proves the machine is healthy', () => {
    // Overclaiming here would be the worst thing this screen could do.
    const r = diagnose(healthy, [], data);
    expect(r.verdict).toMatch(/nothing has been verified|not the same as proof/i);
  });

  it('says a configuration-only review is not a health check', () => {
    const r = diagnose(healthy, [], data);
    expect(r.notChecked[0]).toMatch(/without at least one benchmark/i);
  });

  it('catches every classic invisible fault on a badly configured machine', () => {
    const r = diagnose(sick, [], data);
    const ids = r.findings.map((f) => f.id);
    expect(ids).toContain('mem-single-channel');
    expect(ids).toContain('mem-xmp-off');
    expect(ids).toContain('pcie-width');
    expect(ids).toContain('storage-hdd');
    expect(ids).toContain('driver-age');
    expect(ids).toContain('uptime');
  });

  it('ranks critical findings above minor ones', () => {
    const r = diagnose(sick, [], data);
    const rank = { critical: 0, major: 1, minor: 2, unknown: 3, ok: 4 } as const;
    for (let i = 1; i < r.findings.length; i++) {
      expect(rank[r.findings[i].severity]).toBeGreaterThanOrEqual(rank[r.findings[i - 1].severity]);
    }
    expect(r.findings[0].id).toBe('mem-single-channel');
  });

  it('compounds recoverable performance rather than summing it', () => {
    // Two 10% faults are not a 20% fault, and saying so would oversell the fix.
    const r = diagnose(sick, [], data);
    const summed = r.findings.reduce((s, f) => s + (f.estimatedGainPct ?? 0), 0);
    const compounded = r.findings.reduce((a, f) => a * (1 + (f.estimatedGainPct ?? 0)), 1) - 1;
    expect(r.recoverablePct).toBeCloseTo(compounded, 5);
    expect(r.recoverablePct).not.toBeCloseTo(summed, 5);
  });

  it('gives every finding evidence and a remedy', () => {
    const r = diagnose(sick, [], data);
    for (const f of r.findings) {
      expect(f.evidence.length).toBeGreaterThan(10);
      expect(f.impact.length).toBeGreaterThan(20);
      // Only a finding with genuinely nothing to do may omit a remedy, and
      // none of the ones on this machine qualify.
      expect(f.remedy).toBeTruthy();
    }
  });

  it('places a measurement against the model with an explicit band', () => {
    const m: Measurement[] = [{ gameId: 'cyberpunk-2077', resolution: '1440p', preset: 'high', avgFps: 80 }];
    const r = diagnose(healthy, m, data);
    const c = r.comparisons[0];
    expect(c.expectedFps).toBeGreaterThan(0);
    expect(c.bandLow!).toBeLessThan(c.expectedFps!);
    expect(c.bandHigh!).toBeGreaterThan(c.expectedFps!);
    expect(c.detail).toContain('band');
  });

  it('says "as expected" rather than "healthy" when a measurement lands in the band', () => {
    const probe = diagnose(healthy, [{ gameId: 'cyberpunk-2077', resolution: '1440p', preset: 'high', avgFps: 1 }], data);
    const expected = probe.comparisons[0].expectedFps!;
    const r = diagnose(healthy, [{ gameId: 'cyberpunk-2077', resolution: '1440p', preset: 'high', avgFps: expected }], data);
    expect(r.comparisons[0].verdict).toBe('as expected');
    expect(r.comparisons[0].detail).toMatch(/not proof that the machine is healthy|nothing detectable is wrong/i);
  });

  it('never says the measurements agree when one of them does not', () => {
    // Found in the browser: a single capture 40% below expectation produced no
    // finding (the aggregate rule needs two) and the verdict then claimed the
    // measurements landed where the model expects. Flatly untrue.
    const expected = diagnose(healthy, [{ gameId: 'cyberpunk-2077', resolution: '1440p', preset: 'high', avgFps: 1 }], data).comparisons[0].expectedFps!;
    const r = diagnose(healthy, [{ gameId: 'cyberpunk-2077', resolution: '1440p', preset: 'high', avgFps: expected * 0.5 }], data);
    expect(r.comparisons[0].verdict).toBe('far below expectation');
    expect(r.verdict).not.toMatch(/land where the model expects/i);
    expect(r.findings.map((f) => f.id)).toContain('measured-short-cyberpunk-2077');
  });

  it('never quotes a recoverable percentage it did not quantify', () => {
    // "worth roughly 0% together" next to a list of real problems reads as
    // though they do not matter.
    const expected = diagnose(healthy, [{ gameId: 'cyberpunk-2077', resolution: '1440p', preset: 'high', avgFps: 1 }], data).comparisons[0].expectedFps!;
    const r = diagnose(healthy, [{ gameId: 'cyberpunk-2077', resolution: '1440p', preset: 'high', avgFps: expected * 0.5 }], data);
    expect(r.recoverablePct).toBeLessThan(0.005);
    expect(r.verdict).not.toMatch(/roughly 0%|about 0%/);
    expect(r.findings.length).toBeGreaterThan(0);
  });

  it('tells a single short title apart from a machine-wide problem', () => {
    const expected = diagnose(healthy, [{ gameId: 'cyberpunk-2077', resolution: '1440p', preset: 'high', avgFps: 1 }], data).comparisons[0].expectedFps!;
    const one = diagnose(healthy, [{ gameId: 'cyberpunk-2077', resolution: '1440p', preset: 'high', avgFps: expected * 0.5 }], data);
    const f = one.findings.find((x) => x.id === 'measured-short-cyberpunk-2077')!;
    expect(f.impact).toMatch(/cannot separate a problem with this title from a problem with the machine/i);
  });

  it('flags a broad shortfall differently from a single slow title', () => {
    const probe = (g: string) => diagnose(healthy, [{ gameId: g, resolution: '1440p', preset: 'high', avgFps: 1 }], data).comparisons[0].expectedFps!;
    const games = ['cyberpunk-2077', 'counter-strike-2', 'baldurs-gate-3'];
    const broad = diagnose(healthy, games.map((g) => ({ gameId: g, resolution: '1440p' as const, preset: 'high', avgFps: probe(g) * 0.5 })), data);
    expect(broad.findings.map((f) => f.id)).toContain('measured-broad-shortfall');

    const one = diagnose(healthy, games.map((g, i) => ({ gameId: g, resolution: '1440p' as const, preset: 'high', avgFps: probe(g) * (i === 0 ? 0.5 : 1) })), data);
    expect(one.findings.map((f) => f.id)).not.toContain('measured-broad-shortfall');
  });

  it('separates a frame-pacing problem from a low average', () => {
    // "Good numbers, feels bad" is a different fault from "slow", and the
    // report has to tell them apart.
    const expected = diagnose(healthy, [{ gameId: 'cyberpunk-2077', resolution: '1440p', preset: 'high', avgFps: 1 }], data).comparisons[0].expectedFps!;
    const r = diagnose(healthy, [{ gameId: 'cyberpunk-2077', resolution: '1440p', preset: 'high', avgFps: expected, low1PctFps: expected * 0.3 }], data);
    expect(r.findings.map((f) => f.id)).toContain('stutter-cyberpunk-2077');
    expect(r.comparisons[0].verdict).toBe('as expected');
  });

  it('reports rather than guesses when the hardware is unknown', () => {
    const r = diagnose({ ...healthy, build: { ...healthy.build, gpuId: 'not-a-real-card' } }, [], data);
    expect(r.findings).toEqual([]);
    expect(r.verdict).toMatch(/not in the catalogue/i);
  });

  it('adds to notChecked what the detector could not see', () => {
    const r = diagnose({ ...healthy, pcieLink: undefined, memory: undefined }, [], data);
    expect(r.notChecked.join(' ')).toMatch(/PCIe link/i);
    expect(r.notChecked.join(' ')).toMatch(/XMP\/EXPO/i);
  });
});
