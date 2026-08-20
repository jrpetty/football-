import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { buildEngineData, ANCHOR_RAM } from '../src/core/catalogue.ts';
import { estimate } from '../src/core/engine.ts';
import { estimateLatency, estimateNoise, estimatePower, estimateThermals, latencyVerdict } from '../src/core/physics.ts';

import { bottleneckMap, machineReport, sensitivity, batchScore } from '../src/core/analysis.ts';
import type { Build } from '../src/core/types.ts';

const ROOT = new URL('..', import.meta.url).pathname;
const load = (p: string) => JSON.parse(readFileSync(join(ROOT, p), 'utf8'));
const data = buildEngineData({
  gpus: load('data/catalogue/gpus.json'),
  cpus: load('data/catalogue/cpus.json'),
  games: load('data/catalogue/games.json'),
  references: load('data/catalogue/references.json'),
});

const build = (cpuId: string, gpuId: string, over: Partial<Build> = {}): Build => ({
  id: 'test', cpuId, gpuId, ram: ANCHOR_RAM, storage: 'nvme-gen3',
  target: { resolution: '1440p', refreshHz: 144 }, ...over,
});

describe('power', () => {
  it('reports gaming draw well below the CPU rating', () => {
    // A 253W-rated part does not draw 253W while feeding a GPU; games never
    // saturate every core, and quoting the rating would oversize every PSU.
    const cpu = data.cpus.get('intel-core-i9-13900k') ?? data.cpus.get('amd-ryzen-5-3600')!;
    const gpu = data.gpus.get('nvidia-geforce-rtx-3060-12gb')!;
    const p = estimatePower(gpu, cpu, build(cpu.id, gpu.id));
    expect(p.cpuW).toBeLessThan(cpu.tdpW ?? 65);
    expect(p.totalW).toBeGreaterThan(p.cpuW + p.gpuW);
  });

  it('puts the transient peak above sustained draw — the figure that trips a PSU', () => {
    const gpu = data.gpus.get('nvidia-geforce-rtx-3080-10gb')!;
    const cpu = data.cpus.get('amd-ryzen-7-5800x')!;
    const p = estimatePower(gpu, cpu, build(cpu.id, gpu.id));
    expect(p.transientPeakW).toBeGreaterThan(p.totalW);
    expect(p.recommendedPsuW).toBeGreaterThanOrEqual(p.totalW);
  });

  it('scales running cost with hours played', () => {
    const gpu = data.gpus.get('nvidia-geforce-rtx-3060-12gb')!;
    const cpu = data.cpus.get('amd-ryzen-5-3600')!;
    const p = estimatePower(gpu, cpu, build(cpu.id, gpu.id));
    expect(p.costPerYearGBP(20)).toBeCloseTo(p.costPerYearGBP(10) * 2, 1);
  });
});

describe('thermals', () => {
  it('throttles a hot CPU on a stock cooler and not on an AIO', () => {
    const cpu = data.cpus.get('intel-core-i9-13900k') ?? data.cpus.get('amd-ryzen-9-7950x')!;
    const gpu = data.gpus.get('nvidia-geforce-rtx-3060-12gb')!;
    const stock = estimateThermals(gpu, cpu, build(cpu.id, gpu.id, { cooling: 'stock' }));
    const aio = estimateThermals(gpu, cpu, build(cpu.id, gpu.id, { cooling: 'aio' }));
    expect(stock.cpuClockFactor).toBeLessThanOrEqual(aio.cpuClockFactor);
  });

  it('feeds throttling back into the frame rate', () => {
    const cpu = data.cpus.get('intel-core-i9-13900k') ?? data.cpus.get('amd-ryzen-9-7950x')!;
    const gpu = data.gpus.get('nvidia-geforce-rtx-4090')!;
    const good = estimate(build(cpu.id, gpu.id, { cooling: 'aio' }), 'counter-strike-2', '1080p', data, { airflowTier: 'excellent' });
    const bad = estimate(build(cpu.id, gpu.id, { cooling: 'stock' }), 'counter-strike-2', '1080p', data, { airflowTier: 'restricted' });
    expect(bad.avgFps!).toBeLessThan(good.avgFps!);
  });
});

describe('noise', () => {
  it('gets louder as heat load rises, and quieter in a silent case', () => {
    const gpu = data.gpus.get('nvidia-geforce-rtx-4090')!;
    const cpu = data.cpus.get('amd-ryzen-9-7950x') ?? data.cpus.get('amd-ryzen-7-5800x')!;
    const b = build(cpu.id, gpu.id, { cooling: 'stock' });
    const th = estimateThermals(gpu, cpu, b, 'restricted');
    const pw = estimatePower(gpu, cpu, b);
    const loud = estimateNoise(th, pw, b, 'loud');
    const silent = estimateNoise(th, pw, b, 'silent');
    expect(loud.dba).toBeGreaterThan(silent.dba);
    expect(silent.descriptor.length).toBeGreaterThan(0);
  });
});

describe('latency', () => {
  it('rises as frame rate falls', () => {
    expect(estimateLatency(60).totalMs).toBeGreaterThan(estimateLatency(240).totalMs);
  });

  it('gets WORSE with frame generation, even though the counter goes up', () => {
    // The entire reason frame generation is reported separately from avgFps.
    const off = estimateLatency(80, { frameGen: false });
    const on = estimateLatency(80, { frameGen: true });
    expect(on.totalMs).toBeGreaterThan(off.totalMs);
  });

  it('rewards a fast panel over a slow one at the same frame rate', () => {
    expect(estimateLatency(144, { panelType: 'OLED' }).totalMs).toBeLessThan(
      estimateLatency(144, { panelType: 'VA' }).totalMs,
    );
  });

  it('bands the result so the number means something', () => {
    expect(latencyVerdict(20).severity).toBe('ok');
    expect(latencyVerdict(120).severity).toBe('critical');
  });
});

describe('frame generation', () => {
  it('reports presented frames separately from rendered frames', () => {
    const b = build('amd-ryzen-7-7800x3d', 'nvidia-geforce-rtx-4090', {
      target: { resolution: '1440p', refreshHz: 144, upscaling: { tech: 'dlss', quality: 'quality', frameGen: true } },
    });
    const est = estimate(b, 'cyberpunk-2077', '1440p', data);
    if (est.status === 'ok' && est.frameGenActive) {
      expect(est.presentedFps!).toBeGreaterThan(est.avgFps!);
      // avgFps must remain the RENDERED rate, or comparisons become dishonest.
      expect(est.avgFps!).toBeLessThan(est.presentedFps!);
    }
  });
});

describe('stutter model', () => {
  it('reports 0.1% lows beneath 1% lows', () => {
    const est = estimate(build('amd-ryzen-5-3600', 'nvidia-geforce-rtx-3060-12gb'), 'cyberpunk-2077', '1440p', data);
    expect(est.low01PctFps!).toBeLessThan(est.low1PctFps!);
    expect(est.low1PctFps!).toBeLessThan(est.avgFps!);
  });

  it('names the causes of poor pacing rather than only lowering a number', () => {
    const starved = build('intel-core-i3-12100f', 'nvidia-geforce-rtx-4090', {
      ram: { totalGB: 8, channels: 1, speedMTs: 2400, type: 'DDR4' }, storage: 'hdd',
    });
    const est = estimate(starved, 'stalker-2', '1080p', data);
    if (est.status === 'ok') {
      expect(est.smoothness!.causes.length).toBeGreaterThan(0);
    }
  });
});

describe('analysis surface', () => {
  it('maps the bottleneck across games and resolutions', () => {
    const m = bottleneckMap(build('amd-ryzen-5-3600', 'nvidia-geforce-rtx-4090'), ['counter-strike-2', 'cyberpunk-2077'], data);
    expect(m.cells.length).toBe(8);
    expect(m.summary.length).toBeGreaterThan(10);
  });

  it('ranks sensitivity factors and compares them against the model band', () => {
    const s = sensitivity(build('amd-ryzen-5-3600', 'nvidia-geforce-rtx-3060-12gb'), 'cyberpunk-2077', '1440p', data);
    expect(s.factors.length).toBeGreaterThan(2);
    expect(s.factors.some((f) => f.factor === 'Model uncertainty')).toBe(true);
  });

  it('survives a bad row in a batch instead of throwing', () => {
    const rows = batchScore(
      [
        { label: 'good', cpuId: 'amd-ryzen-5-3600', gpuId: 'nvidia-geforce-rtx-3060-12gb' },
        { label: 'bad', cpuId: 'does-not-exist', gpuId: 'nvidia-geforce-rtx-3060-12gb' },
      ],
      ['counter-strike-2'], '1080p', data,
    );
    expect(rows).toHaveLength(2);
    expect(rows[1].error).toBeTruthy();
    expect(rows[0].geomeanFps).toBeGreaterThan(0);
  });

  it('produces a whole-machine report with a PSU verdict', () => {
    const r = machineReport(build('amd-ryzen-7-5800x', 'nvidia-geforce-rtx-3080-10gb', { psu: { watts: 550 } }), data, { fps: 100 });
    expect(r).not.toBeNull();
    // 550W against a 3080's transient behaviour is the classic under-spec.
    expect(r!.psuVerdict.ok).toBe(false);
  });
});
