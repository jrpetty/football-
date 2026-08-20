import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { buildEngineData } from '../src/core/catalogue.ts';
import type { DetectedSystem, Measurement } from '../src/core/health.ts';
import {
  configChanges, detectDegradation, machineId, matchMeasurements, measurementKey,
  seriesFor, trackableKeys, verifyFixes, type HealthSession,
} from '../src/core/history.ts';
import { comparePeers, findPeers } from '../src/core/peers.ts';
import { guideFor, FIX_GUIDES } from '../src/core/fixguides.ts';
import type { PerfRecord } from '../src/core/types.ts';

const ROOT = new URL('..', import.meta.url).pathname;
const load = (p: string) => JSON.parse(readFileSync(join(ROOT, p), 'utf8'));
const data = buildEngineData({
  gpus: load('data/catalogue/gpus.json'), cpus: load('data/catalogue/cpus.json'),
  games: load('data/catalogue/games.json'), references: load('data/catalogue/references.json'),
});

const sys = (over: Partial<DetectedSystem['build']> = {}, extra: Partial<DetectedSystem> = {}): DetectedSystem => ({
  build: {
    id: 'm', cpuId: 'amd-ryzen-5-5600', gpuId: 'nvidia-geforce-rtx-4070',
    ram: { totalGB: 16, channels: 1, speedMTs: 2133, type: 'DDR4' },
    storage: 'nvme-gen4', target: { resolution: '1440p', refreshHz: 144 }, ...over,
  },
  cooling: { airflow: 'good' }, ...extra,
});

const m = (avg: number, over: Partial<Measurement> = {}): Measurement => ({
  gameId: 'cyberpunk-2077', resolution: '1440p', preset: 'high', avgFps: avg, ...over,
});

const session = (at: string, measurements: Measurement[], over: Partial<HealthSession> = {}): HealthSession => ({
  id: at, machineId: machineId(sys()), machineLabel: 'my pc', at,
  system: sys(), measurements, findings: [], fixesApplied: [], ...over,
});

describe('machine identity', () => {
  it('survives a memory or storage change, because that is the thing being fixed', () => {
    const a = machineId(sys({ ram: { totalGB: 16, channels: 1, speedMTs: 2133, type: 'DDR4' } }));
    const b = machineId(sys({ ram: { totalGB: 32, channels: 2, speedMTs: 3600, type: 'DDR4' }, storage: 'hdd' }));
    expect(a).toBe(b);
  });

  it('changes when the CPU or GPU changes, because that is a different machine', () => {
    expect(machineId(sys())).not.toBe(machineId(sys({ gpuId: 'nvidia-geforce-rtx-4090' })));
    expect(machineId(sys())).not.toBe(machineId(sys({ cpuId: 'amd-ryzen-7-7800x3d' })));
  });
});

describe('measurement matching', () => {
  it('refuses to compare measurements taken at different settings', () => {
    // A confident wrong answer about degradation sends someone dismantling a
    // machine that is fine.
    const before = session('2026-01-01', [m(80, { preset: 'high' })]);
    const after = session('2026-06-01', [m(60, { preset: 'ultra' })]);
    const { matched, unmatchable } = matchMeasurements(before, after);
    expect(matched).toEqual([]);
    expect(unmatchable[0].reason).toMatch(/different settings/);
  });

  it('says which case an unmatched measurement is', () => {
    const before = session('2026-01-01', [m(80)]);
    const after = session('2026-06-01', [m(200, { gameId: 'counter-strike-2' })]);
    expect(matchMeasurements(before, after).unmatchable[0].reason).toMatch(/not measured in the earlier session/);
  });

  it('matches on every axis that changes what a measurement means', () => {
    const a = m(80, { rtTier: 'on' });
    const b = m(80, { rtTier: 'off' });
    expect(measurementKey(a)).not.toBe(measurementKey(b));
    const c = m(80, { upscaling: { tech: 'dlss', quality: 'quality', frameGen: false } as never });
    expect(measurementKey(c)).not.toBe(measurementKey(m(80)));
  });

  it('computes the delta and the days between', () => {
    const p = matchMeasurements(session('2026-01-01', [m(80)]), session('2026-01-31', [m(100)])).matched[0];
    expect(p.deltaPct).toBeCloseTo(0.25);
    expect(p.daysApart).toBe(30);
  });
});

describe('fix verification', () => {
  const fix = { findingId: 'mem-single-channel', title: 'single channel', predictedGainPct: 0.47, appliedAt: '2026-02-01' };

  it('confirms a fix that delivered what was predicted', () => {
    const v = verifyFixes(session('2026-01-01', [m(80)]), session('2026-02-02', [m(118)], { fixesApplied: [fix] }))[0];
    expect(v.outcome).toBe('delivered');
    expect(v.actualGainPct!).toBeCloseTo(0.475, 2);
    expect(v.modelDisagreement).toBe(false);
  });

  it('flags a fix that delivered far less as evidence about the MODEL', () => {
    // The point of the feature: a prediction that misses badly is a fact about
    // the tool, not just about the machine, and hiding it would be the one
    // thing this project exists to avoid.
    const v = verifyFixes(session('2026-01-01', [m(80)]), session('2026-02-02', [m(89)], { fixesApplied: [fix] }))[0];
    expect(v.outcome).toBe('partial');
    expect(v.modelDisagreement).toBe(true);
    expect(v.detail).toMatch(/well short of the prediction/);
  });

  it('reports no change rather than rounding it into success', () => {
    const v = verifyFixes(session('2026-01-01', [m(80)]), session('2026-02-02', [m(80.5)], { fixesApplied: [fix] }))[0];
    expect(v.outcome).toBe('no-change');
    expect(v.detail).toMatch(/did not take effect|not the constraint/);
  });

  it('reports a regression', () => {
    const v = verifyFixes(session('2026-01-01', [m(80)]), session('2026-02-02', [m(70)], { fixesApplied: [fix] }))[0];
    expect(v.outcome).toBe('worse');
    expect(v.detail).toMatch(/SLOWER/);
  });

  it('refuses to attribute when several fixes were applied together', () => {
    const two = [fix, { findingId: 'storage-hdd', title: 'hdd', predictedGainPct: 0.04, appliedAt: '2026-02-01' }];
    const v = verifyFixes(session('2026-01-01', [m(80)]), session('2026-02-02', [m(118)], { fixesApplied: two }));
    expect(v).toHaveLength(2);
    for (const x of v) expect(x.detail).toMatch(/cannot be split between them/);
  });

  it('says unverifiable rather than guessing when nothing matches', () => {
    const v = verifyFixes(session('2026-01-01', [m(80)]), session('2026-02-02', [m(118, { preset: 'low' })], { fixesApplied: [fix] }))[0];
    expect(v.outcome).toBe('unverifiable');
    expect(v.actualGainPct).toBeNull();
  });
});

describe('degradation detection', () => {
  it('needs two sessions before saying anything', () => {
    const d = detectDegradation([session('2026-01-01', [m(80)])]);
    expect(d.degraded).toBe(false);
    expect(d.detail).toMatch(/Only one session/);
  });

  it('blames a configuration change before blaming physics', () => {
    // Someone who pulled a memory stick has a slower machine for a known
    // reason. Calling that thermal degradation sends them cleaning a heatsink
    // that is fine.
    const before = session('2026-01-01', [m(100)]);
    const after: HealthSession = {
      ...session('2026-06-01', [m(70)]),
      system: sys({ ram: { totalGB: 8, channels: 1, speedMTs: 2133, type: 'DDR4' } }),
    };
    const d = detectDegradation([before, after]);
    expect(d.cause).toBe('configuration-changed');
    expect(d.detail).toMatch(/not the same machine/);
  });

  it('raises a driver change when nothing else moved', () => {
    const before: HealthSession = { ...session('2026-01-01', [m(100)]), system: sys({}, { driverVersion: '551.23' }) };
    const after: HealthSession = { ...session('2026-06-01', [m(88)]), system: sys({}, { driverVersion: '566.14' }) };
    const d = detectDegradation([before, after]);
    expect(d.cause).toBe('driver-changed');
    expect(d.detail).toMatch(/[Rr]olling back/);
  });

  it('points at storage when the 1% lows fell further than the average', () => {
    const before = session('2026-01-01', [m(100, { low1PctFps: 75 })]);
    const after = session('2026-06-01', [m(90, { low1PctFps: 50 })]);
    const d = detectDegradation([before, after]);
    expect(d.cause).toBe('storage');
    expect(d.detail).toMatch(/free space/);
  });

  it('lands on heat when nothing changed and the lows track the average', () => {
    const before = session('2026-01-01', [m(100, { low1PctFps: 75 })]);
    const after = session('2026-06-01', [m(88, { low1PctFps: 66 })]);
    const d = detectDegradation([before, after]);
    expect(d.cause).toBe('thermal');
    expect(d.detail).toMatch(/sustained clock/i);
  });

  it('says so when a machine got faster', () => {
    const d = detectDegradation([session('2026-01-01', [m(80)]), session('2026-06-01', [m(100)])]);
    expect(d.degraded).toBe(false);
    expect(d.detail).toMatch(/FASTER/);
  });

  it('treats small movement as normal variation rather than decline', () => {
    const d = detectDegradation([session('2026-01-01', [m(100)]), session('2026-06-01', [m(98)])]);
    expect(d.degraded).toBe(false);
    expect(d.detail).toMatch(/normal run-to-run variation/);
  });

  it('lists what changed even when it does not blame it', () => {
    const before: HealthSession = { ...session('2026-01-01', [m(100)]), system: sys({ storage: 'hdd' }) };
    const after = session('2026-06-01', [m(101)]);
    expect(detectDegradation([before, after]).changes.join(' ')).toMatch(/games drive/);
  });
});

describe('config diff', () => {
  it('names every change a user would care about', () => {
    const c = configChanges(
      sys({ ram: { totalGB: 16, channels: 1, speedMTs: 2133, type: 'DDR4' }, storage: 'hdd' }, { psuWatts: 450, pcieLink: { width: 4 } }),
      sys({ ram: { totalGB: 32, channels: 2, speedMTs: 3600, type: 'DDR4' }, storage: 'nvme-gen4' }, { psuWatts: 850, pcieLink: { width: 16 } }),
    );
    expect(c.join(' | ')).toMatch(/channels 1 → 2/);
    expect(c.join(' | ')).toMatch(/speed 2133 → 3600/);
    expect(c.join(' | ')).toMatch(/capacity 16 → 32/);
    expect(c.join(' | ')).toMatch(/hdd → nvme-gen4/);
    expect(c.join(' | ')).toMatch(/x4 → x16/);
    expect(c.join(' | ')).toMatch(/450W → 850W/);
  });
});

describe('tracking series', () => {
  it('only offers keys measured more than once', () => {
    const s = [session('2026-01-01', [m(80), m(200, { gameId: 'counter-strike-2' })]), session('2026-02-01', [m(85)])];
    const t = trackableKeys(s);
    expect(t).toHaveLength(1);
    expect(t[0].gameId).toBe('cyberpunk-2077');
  });

  it('returns a chronological series for a chart', () => {
    const s = [session('2026-03-01', [m(70)]), session('2026-01-01', [m(90)]), session('2026-02-01', [m(80)])];
    expect(seriesFor(s, measurementKey(m(0))).map((x) => x.avgFps)).toEqual([90, 80, 70]);
  });
});

describe('peer comparison', () => {
  const peer = (avg: number, preset = 'high'): PerfRecord => ({
    id: `p${avg}`, cpuId: 'amd-ryzen-5-5600', gpuId: 'nvidia-geforce-rtx-4070',
    gameId: 'cyberpunk-2077', resolution: '1440p', avgFps: avg,
    fingerprint: { preset, upscaling: { tech: 'none', quality: 'native', frameGen: false } },
    sourceNote: 'harness', provenanceId: 'manual-import', weight: 1,
  });

  it('never invents peers when the corpus is empty', () => {
    // The shipped corpus has zero records. Rendering the model's own
    // prediction as a "peer" would be inventing social proof.
    const c = comparePeers({ avgFps: 80, gameId: 'cyberpunk-2077', resolution: '1440p' }, [], []);
    expect(c.tier).toBe('none');
    expect(c.peerMedian).toBeNull();
    expect(c.detail).toMatch(/no server and collects no telemetry/);
    expect(c.nextStep).toBeTruthy();
  });

  it('falls back to your own history and says what that can and cannot tell you', () => {
    const c = comparePeers({ avgFps: 80, gameId: 'cyberpunk-2077', resolution: '1440p' }, [], [95, 92]);
    expect(c.tier).toBe('own-history');
    expect(c.detail).toMatch(/whether the machine has drifted/);
    expect(c.detail).toMatch(/cannot tell you whether it is normal/);
  });

  it('compares against real peers when they exist', () => {
    const peers = findPeers([peer(70), peer(80), peer(90)], { cpuId: 'amd-ryzen-5-5600', gpuId: 'nvidia-geforce-rtx-4070', gameId: 'cyberpunk-2077', resolution: '1440p', preset: 'high' });
    const c = comparePeers({ avgFps: 95, gameId: 'cyberpunk-2077', resolution: '1440p', preset: 'high' }, peers, []);
    expect(c.tier).toBe('real-peers');
    expect(c.peerMedian).toBe(80);
    expect(c.percentile).toBe(1);
    expect(c.detail).toMatch(/19% above/);
  });

  it('warns that a handful of peers proves little', () => {
    const peers = findPeers([peer(80)], { cpuId: 'amd-ryzen-5-5600', gpuId: 'nvidia-geforce-rtx-4070', gameId: 'cyberpunk-2077', resolution: '1440p', preset: 'high' });
    expect(comparePeers({ avgFps: 90, gameId: 'cyberpunk-2077', resolution: '1440p', preset: 'high' }, peers, []).detail).toMatch(/means very little/);
  });

  it('flags when no peer used the same preset', () => {
    const peers = findPeers([peer(80, 'ultra')], { cpuId: 'amd-ryzen-5-5600', gpuId: 'nvidia-geforce-rtx-4070', gameId: 'cyberpunk-2077', resolution: '1440p', preset: 'high' });
    expect(comparePeers({ avgFps: 90, gameId: 'cyberpunk-2077', resolution: '1440p', preset: 'high' }, peers, []).detail).toMatch(/compares the hardware rather than the configuration/);
  });

  it('does not match peers on different hardware', () => {
    expect(findPeers([peer(80)], { cpuId: 'amd-ryzen-7-7800x3d', gpuId: 'nvidia-geforce-rtx-4070', gameId: 'cyberpunk-2077', resolution: '1440p' })).toEqual([]);
  });
});

describe('fix guides', () => {
  it('covers every finding that has a remedy worth walking through', () => {
    for (const id of ['mem-single-channel', 'mem-xmp-off', 'pcie-width', 'storage-hdd', 'thermal-throttle', 'psu-transient', 'driver-age', 'uptime', 'mem-capacity']) {
      expect(guideFor(id), `missing guide for ${id}`).toBeDefined();
    }
  });

  it('gives every guide steps, a way to verify, and the real failure modes', () => {
    for (const [id, g] of Object.entries(FIX_GUIDES)) {
      expect(g.steps.length, id).toBeGreaterThan(1);
      expect(g.verify.length, id).toBeGreaterThan(30);
      expect(g.risks.length, id).toBeGreaterThan(0);
      expect(g.minutes, id).toBeGreaterThan(0);
      for (const s of g.steps) expect(s.do.length, `${id} step`).toBeGreaterThan(10);
    }
  });

  it('warns about the two mistakes that cost money', () => {
    // Reusing modular PSU cables and pulling a CPU out of an AM4 socket are
    // the expensive ones; both must be called out where they apply.
    expect(FIX_GUIDES['psu-transient'].risks.join(' ')).toMatch(/modular cables/i);
    expect(FIX_GUIDES['thermal-throttle'].risks.join(' ')).toMatch(/out of its socket/i);
  });

  it('tells someone what to have ready before they start', () => {
    expect(FIX_GUIDES['mem-single-channel'].needs.join(' ')).toMatch(/screwdriver/i);
    expect(FIX_GUIDES['mem-xmp-off'].needs.join(' ')).toMatch(/Nothing/i);
  });

  it('knows the games catalogue is available for labelling', () => {
    expect(data.games.size).toBeGreaterThan(0);
  });
});
