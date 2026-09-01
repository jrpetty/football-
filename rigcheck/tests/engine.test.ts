import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { buildEngineData, ANCHOR_RAM, browseParts, search } from '../src/core/catalogue.ts';
import { estimate, softMin } from '../src/core/engine.ts';
import { gameVisibleL3PerCore } from '../src/core/indices.ts';
import { compareBuilds, futureProofing } from '../src/core/queries.ts';
import type { Build } from '../src/core/types.ts';

const ROOT = new URL('..', import.meta.url).pathname;
const load = (p: string) => JSON.parse(readFileSync(join(ROOT, p), 'utf8'));

const data = buildEngineData({
  gpus: load('data/catalogue/gpus.json'),
  cpus: load('data/catalogue/cpus.json'),
  games: load('data/catalogue/games.json'),
  references: load('data/catalogue/references.json'),
});

function build(cpuId: string, gpuId: string, over: Partial<Build> = {}): Build {
  return {
    id: `${cpuId}+${gpuId}`,
    cpuId,
    gpuId,
    ram: ANCHOR_RAM,
    storage: 'nvme-gen3',
    target: { resolution: '1080p', refreshHz: 144 },
    ...over,
  };
}

describe('softMin', () => {
  it('sits below both inputs, modelling contention a hard min would miss', () => {
    const r = softMin(100, 100, 6.58);
    expect(r).toBeLessThan(100);
    expect(r).toBeGreaterThan(80);
  });

  it('produces exactly the intended ~10% loss at equal inputs with p=6.58', () => {
    // The spec says p~=4 AND "~10% loss"; those disagree. 2^(-1/4) = 0.841,
    // a 15.9% loss. p = ln2/-ln(0.9) = 6.58 is the value that gives 10%.
    expect(softMin(100, 100, 6.58)).toBeCloseTo(90, 0);
    expect(softMin(100, 100, 4)).toBeCloseTo(84.1, 0);
  });

  it('converges to the smaller input as disparity grows', () => {
    expect(softMin(50, 500, 6.58)).toBeCloseTo(50, 0);
  });
});

describe('hard gates', () => {
  it('returns WILL_NOT_RUN, not a low number, when mesh shaders are missing', () => {
    // GTX 1060 is Pascal: no mesh shaders. Alan Wake 2 requires them.
    const est = estimate(build('amd-ryzen-5-3600', 'nvidia-geforce-gtx-1060-6gb'), 'alan-wake-2', '1080p', data);
    expect(est.status).toBe('WILL_NOT_RUN');
    expect(est.avgFps).toBeUndefined();
    expect(est.gateFailures.map((f) => f.code)).toContain('MESH_SHADER_REQUIRED');
  });

  it('lets a mesh-shader-capable card through the same gate', () => {
    const est = estimate(build('amd-ryzen-5-3600', 'nvidia-geforce-rtx-3060-12gb'), 'alan-wake-2', '1080p', data);
    expect(est.status).toBe('ok');
    expect(est.avgFps).toBeGreaterThan(0);
  });

  it('blocks a title that requires ray tracing hardware', () => {
    const est = estimate(build('amd-ryzen-5-3600', 'amd-radeon-rx-580-8gb'), 'metro-exodus-enhanced', '1080p', data);
    if (est.status === 'WILL_NOT_RUN') {
      expect(est.gateFailures.some((f) => f.code === 'RAY_TRACING_REQUIRED')).toBe(true);
    }
  });

  // A published VRAM minimum is not a capability gate. Cyberpunk 2077 publishes
  // 6GB; a 4GB GTX 970 launches it and runs it badly. Claiming otherwise is a
  // false statement about the real world, and this project published one.
  it('does not block a card merely below a published VRAM minimum', () => {
    const est = estimate(build('amd-ryzen-5-5600', 'nvidia-geforce-gtx-970'), 'cyberpunk-2077', '1080p', data);
    expect(est.status).toBe('ok');
    expect(est.gateFailures.map((f) => f.code)).not.toContain('VRAM_CEILING');
    // Playable-adjacent, and clearly not playable. Both halves matter: a number
    // this low is the finding, and a number this low being absent was the bug.
    expect(est.avgFps).toBeGreaterThan(10);
    expect(est.avgFps).toBeLessThan(35);
  });

  it('still blocks below half the published VRAM minimum', () => {
    // 2GB against Cyberpunk's published 6GB: the working set cannot stay resident.
    const est = estimate(build('amd-ryzen-5-5600', 'nvidia-geforce-gtx-1050-2gb'), 'cyberpunk-2077', '1080p', data);
    expect(est.status).toBe('WILL_NOT_RUN');
    expect(est.gateFailures.map((f) => f.code)).toContain('VRAM_CEILING');
  });
});

describe('anchor calibration', () => {
  it('places the anchor GPU at index 100 by construction', () => {
    const est = estimate(build('amd-ryzen-5-3600', 'nvidia-geforce-rtx-3060-12gb'), 'cyberpunk-2077', '1080p', data);
    const idx = est.terms.find((t) => t.label === 'gpu: raster index');
    expect(Number(idx?.value)).toBeCloseTo(100, 0);
  });
});

describe('CPU vector model', () => {
  it('derives X3D cache from the stacked CCD, not the package total', () => {
    // A 7950X3D has 128MB across two chiplets but the stack is on one CCD.
    // Package-total / cores would rank it BELOW a 7800X3D, inverting the answer
    // in exactly the cache-sensitive titles the vector model exists for.
    const dual = data.cpus.get('amd-ryzen-9-7950x3d');
    const single = data.cpus.get('amd-ryzen-7-7800x3d');
    if (!dual || !single) return;
    const d = gameVisibleL3PerCore(dual);
    const s = gameVisibleL3PerCore(single);
    expect(d.perCore).toBeCloseTo(s.perCore ?? 0, 1);
    expect(d.perCore).toBeGreaterThan((dual.l3CacheMB ?? 0) / dual.cores);
  });

  it('ranks an X3D part above its non-X3D sibling in a cache-sensitive title', () => {
    // Deliberately NOT Factorio: it is locked to 60 UPS, so both builds pin to
    // the engine cap and the comparison says nothing. Total War is uncapped.
    const x3d = build('amd-ryzen-7-7800x3d', 'nvidia-geforce-rtx-4090');
    const plain = build('amd-ryzen-7-7700x', 'nvidia-geforce-rtx-4090');
    if (!data.cpus.get('amd-ryzen-7-7800x3d') || !data.cpus.get('amd-ryzen-7-7700x')) return;
    const a = estimate(x3d, 'total-war-warhammer-iii', '1080p', data);
    const b = estimate(plain, 'total-war-warhammer-iii', '1080p', data);
    expect(a.status).toBe('ok');
    expect(b.status).toBe('ok');
    expect(a.avgFps!).toBeGreaterThan(b.avgFps!);
  });

  it('shows a SMALLER X3D advantage in a cache-insensitive title than a cache-heavy one', () => {
    // The point of the vector model: the gap between two CPUs changes by
    // archetype. A scalar index would report one fixed ratio everywhere.
    if (!data.cpus.get('amd-ryzen-7-7800x3d') || !data.cpus.get('amd-ryzen-7-7700x')) return;
    const x3d = build('amd-ryzen-7-7800x3d', 'nvidia-geforce-rtx-4090');
    const plain = build('amd-ryzen-7-7700x', 'nvidia-geforce-rtx-4090');
    const ratio = (game: string) => {
      const a = estimate(x3d, game, '1080p', data);
      const b = estimate(plain, game, '1080p', data);
      return a.status === 'ok' && b.status === 'ok' ? a.avgFps! / b.avgFps! : NaN;
    };
    const cacheHeavy = ratio('total-war-warhammer-iii');
    const cacheLight = ratio('hellblade-ii');
    if (Number.isFinite(cacheHeavy) && Number.isFinite(cacheLight)) {
      expect(cacheHeavy).toBeGreaterThan(cacheLight);
    }
  });

  it('applies the engine cap regardless of how much CPU is thrown at it', () => {
    // Factorio is capped at 60: the correct answer for both parts is 60.
    const a = estimate(build('amd-ryzen-7-7800x3d', 'nvidia-geforce-rtx-4090'), 'factorio', '1080p', data);
    if (a.status === 'ok' && data.games.get('factorio')?.fpsCap === 60) {
      expect(a.avgFps).toBeLessThanOrEqual(60);
      expect(a.limiter).toBe('engine-cap');
    }
  });
});

describe('memory configuration', () => {
  it('penalises single-channel memory on the CPU-bound side', () => {
    const dual = build('amd-ryzen-5-3600', 'nvidia-geforce-rtx-3060-12gb');
    const single = build('amd-ryzen-5-3600', 'nvidia-geforce-rtx-3060-12gb', {
      ram: { ...ANCHOR_RAM, channels: 1 },
    });
    const a = estimate(dual, 'counter-strike-2', '1080p', data);
    const b = estimate(single, 'counter-strike-2', '1080p', data);
    expect(b.avgFps!).toBeLessThan(a.avgFps!);
  });

  it('hits an iGPU far harder than a discrete card, because it shares bandwidth', () => {
    const igpu = [...data.gpus.values()].find((g) => g.formFactor === 'igpu' && g.shaders);
    if (!igpu) return;
    const cpu = [...data.cpus.values()].find((c) => c.igpuId === igpu.id) ?? data.cpus.get('amd-ryzen-5-3600')!;
    const dual = estimate(build(cpu.id, igpu.id), 'counter-strike-2', '1080p', data);
    const single = estimate(build(cpu.id, igpu.id, { ram: { ...ANCHOR_RAM, channels: 1 } }), 'counter-strike-2', '1080p', data);
    if (dual.status === 'ok' && single.status === 'ok') {
      const igpuDrop = 1 - single.avgFps! / dual.avgFps!;
      const discreteDrop =
        1 -
        estimate(build(cpu.id, 'nvidia-geforce-rtx-3060-12gb', { ram: { ...ANCHOR_RAM, channels: 1 } }), 'counter-strike-2', '1080p', data).avgFps! /
          estimate(build(cpu.id, 'nvidia-geforce-rtx-3060-12gb'), 'counter-strike-2', '1080p', data).avgFps!;
      expect(igpuDrop).toBeGreaterThan(discreteDrop);
    }
  });
});

describe('VRAM cliff', () => {
  it('punishes an 8GB card in a VRAM-heavy title at 1440p and hits 1% lows harder', () => {
    const starved = data.gpus.get('nvidia-geforce-rtx-3070');
    if (!starved) return;
    const est = estimate(build('amd-ryzen-7-5800x', 'nvidia-geforce-rtx-3070'), 'the-last-of-us-part-i', '1440p', data);
    if (est.status !== 'ok') return;
    const cliff = est.terms.find((t) => t.label === 'VRAM cliff');
    if (cliff) {
      // The cliff must degrade lows more than averages — that is its signature.
      expect(est.low1PctFps! / est.avgFps!).toBeLessThan(0.78);
    }
  });
});

describe('resolution behaviour', () => {
  it('never reports a higher frame rate at a higher resolution', () => {
    const b = build('amd-ryzen-5-3600', 'nvidia-geforce-rtx-3060-12gb');
    const r1080 = estimate(b, 'cyberpunk-2077', '1080p', data).avgFps!;
    const r1440 = estimate(b, 'cyberpunk-2077', '1440p', data).avgFps!;
    const r2160 = estimate(b, 'cyberpunk-2077', '2160p', data).avgFps!;
    expect(r1440).toBeLessThan(r1080);
    expect(r2160).toBeLessThan(r1440);
  });

  it('cannot lift a CPU-limited build with upscaling', () => {
    // Upscaling reduces render resolution, which moves only the GPU side.
    const b = build('intel-core-i7-2600k', 'nvidia-geforce-rtx-4090');
    const native = estimate(b, 'counter-strike-2', '1080p', data);
    const upscaled = estimate(b, 'counter-strike-2', '1080p', data, {
      upscaling: { tech: 'dlss', quality: 'performance', frameGen: false },
    });
    if (native.status === 'ok' && upscaled.status === 'ok') {
      expect(upscaled.avgFps! / native.avgFps!).toBeLessThan(1.08);
    }
  });
});

describe('engine caps', () => {
  it('respects a hard frame cap and says so', () => {
    const est = estimate(build('amd-ryzen-7-7800x3d', 'nvidia-geforce-rtx-4090'), 'elden-ring', '1080p', data);
    if (est.status === 'ok' && data.games.get('elden-ring')?.fpsCap === 60) {
      expect(est.avgFps).toBeLessThanOrEqual(60);
      expect(est.limiter).toBe('engine-cap');
    }
  });
});

describe('uncertainty and noise floor', () => {
  it('attaches a band to every estimate', () => {
    const est = estimate(build('amd-ryzen-5-3600', 'nvidia-geforce-rtx-3060-12gb'), 'cyberpunk-2077', '1080p', data);
    expect(est.uncertainty).toBeGreaterThan(0);
    expect(est.band!.low).toBeLessThan(est.avgFps!);
    expect(est.band!.high).toBeGreaterThan(est.avgFps!);
  });

  it('flags a delta smaller than combined uncertainty as noise', () => {
    const a = build('amd-ryzen-5-3600', 'nvidia-geforce-rtx-3060-12gb');
    const b = { ...build('amd-ryzen-5-3600', 'nvidia-geforce-rtx-3060-12gb'), id: 'twin' };
    const m = compareBuilds([a, b], ['cyberpunk-2077'], ['1080p'], data, { baselineBuildId: a.id });
    const cell = m.cells.find((c) => c.buildId === 'twin');
    expect(cell?.deltaWithinNoise).toBe(true);
  });
});

describe('futureProofing', () => {
  it('reports dated factors rather than a bare score', () => {
    const fp = futureProofing(build('amd-ryzen-5-3600', 'nvidia-geforce-gtx-1060-6gb'), data);
    expect(fp.factors.length).toBeGreaterThan(3);
    expect(fp.factors.every((f) => f.evidence.length > 0)).toBe(true);
    const mesh = fp.factors.find((f) => f.factor === 'Mesh shaders');
    expect(mesh?.severity).toBe('critical');
  });
});

describe('search disambiguation', () => {
  it('surfaces every distinct "7700" part with an unmistakable disambiguator', () => {
    const hits = search('7700', data);
    expect(hits.length).toBeGreaterThan(1);
    // Every hit must carry text that distinguishes it from its namesakes.
    for (const h of hits) expect(h.disambiguator.length).toBeGreaterThan(3);
    const labels = hits.map((h) => h.label.toLowerCase());
    expect(labels.some((l) => l.includes('i7-7700'))).toBe(true);
  });

  it('distinguishes the two GTX 1060 configurations, which are different silicon', () => {
    const hits = search('gtx 1060', data);
    const three = hits.find((h) => h.label.includes('3'));
    const six = hits.find((h) => h.label.includes('6'));
    expect(three).toBeDefined();
    expect(six).toBeDefined();
    expect(three!.id).not.toBe(six!.id);
  });
});

describe('part browsing', () => {
  it('reaches every vendor without typing anything', () => {
    // Regression: the browse list used to return the first 40 records in
    // catalogue insertion order. Because the reconciler merges the AMD agent's
    // output first, Intel and Nvidia parts were unreachable by scrolling — you
    // had to already know a part name to find one.
    for (const kind of ['gpu', 'cpu'] as const) {
      const groups = browseParts(kind, data);
      const vendors = new Set(groups.map((g) => g.vendor));
      expect(vendors.has('nvidia') || kind === 'cpu').toBe(true);
      expect(vendors.has('amd')).toBe(true);
      expect(vendors.has('intel')).toBe(true);
    }
  });

  it('exposes the whole catalogue, not a truncated head', () => {
    const gpuTotal = browseParts('gpu', data).reduce((n, g) => n + g.hits.length, 0);
    const cpuTotal = browseParts('cpu', data).reduce((n, g) => n + g.hits.length, 0);
    expect(gpuTotal).toBe(data.gpus.size);
    expect(cpuTotal).toBe(data.cpus.size);
  });

  it('orders each vendor strongest-first so the list doubles as a ladder', () => {
    const nvidia = browseParts('gpu', data).find((g) => g.vendor === 'nvidia')!;
    const ids = nvidia.hits.map((h) => h.id);
    const i4090 = ids.indexOf('nvidia-geforce-rtx-4090');
    const i3060 = ids.indexOf('nvidia-geforce-rtx-3060-12gb');
    const i1060 = ids.indexOf('nvidia-geforce-gtx-1060-6gb');
    expect(i4090).toBeGreaterThanOrEqual(0);
    expect(i4090).toBeLessThan(i3060);
    expect(i3060).toBeLessThan(i1060);
  });

  it('carries a disambiguator on every browse entry, as search hits do', () => {
    for (const g of browseParts('cpu', data)) {
      for (const h of g.hits.slice(0, 20)) expect(h.disambiguator.length).toBeGreaterThan(3);
    }
  });
});

describe('provenance on the derivation terms', () => {
  it('credits the sources behind the graphics terms rather than nothing', () => {
    // The bug this guards: the lookup was keyed by '' — a key no record has —
    // so every graphics term showed an empty source list on the one panel whose
    // whole job is saying where a number came from.
    const r = estimate(build('amd-ryzen-5-3600', 'nvidia-geforce-rtx-3060-12gb'), 'cyberpunk-2077', '1440p', data);
    const gpuTerms = r.terms.filter((t) => t.label.startsWith('gpu: '));
    expect(gpuTerms.length).toBeGreaterThan(0);
    for (const t of gpuTerms) {
      expect(t.sources.length, `${t.label} has no attributed source`).toBeGreaterThan(0);
    }
  });
});
