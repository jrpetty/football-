import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { ANCHOR_RAM, buildEngineData } from '../src/core/catalogue.ts';
import { estimate } from '../src/core/engine.ts';
import { preferredUpscaler, recommendForLibrary, settingsForTarget } from '../src/core/advisor.ts';
import { isKnownPreset, normalisePreset, presetEffect } from '../src/core/presets.ts';
import type { Build, Resolution } from '../src/core/types.ts';

const ROOT = new URL('..', import.meta.url).pathname;
const load = (p: string) => JSON.parse(readFileSync(join(ROOT, p), 'utf8'));
const data = buildEngineData({
  gpus: load('data/catalogue/gpus.json'),
  cpus: load('data/catalogue/cpus.json'),
  games: load('data/catalogue/games.json'),
  references: load('data/catalogue/references.json'),
});

const rig = (cpuId: string, gpuId: string, resolution: Resolution, refreshHz: number): Build => ({
  id: 'test', cpuId, gpuId, ram: ANCHOR_RAM, storage: 'nvme-gen4', target: { resolution, refreshHz },
});

describe('preset model', () => {
  it('makes the reference preset an exact identity', () => {
    // An unspecified preset must not move any number, or every existing
    // estimate in the tool would silently shift.
    for (const a of ['esports', 'aaa-raster', 'ue5', 'vram-heavy', 'sim-cpu', 'aaa-rt']) {
      const e = presetEffect('high', a);
      expect(e.gpu).toBe(1);
      expect(e.cpu).toBe(1);
      expect(e.vram).toBe(1);
      expect(e.steps).toBe(0);
    }
    expect(presetEffect(undefined, 'aaa-raster').gpu).toBe(1);
  });

  it('leaves an unspecified preset estimate byte-identical to a "high" one', () => {
    const b = rig('amd-ryzen-5-3600', 'nvidia-geforce-rtx-3060-12gb', '1440p', 144);
    const a = estimate(b, 'cyberpunk-2077', '1440p', data);
    const c = estimate(b, 'cyberpunk-2077', '1440p', data, { preset: 'high' });
    expect(c.avgFps).toBe(a.avgFps);
  });

  it('orders the ladder monotonically within every archetype', () => {
    for (const a of ['esports', 'aaa-raster', 'ue5', 'vram-heavy', 'sim-cpu', 'aaa-rt']) {
      const fps = ['low', 'medium', 'high', 'ultra', 'highest'].map((p) => presetEffect(p, a).gpu);
      for (let i = 1; i < fps.length; i++) expect(fps[i]).toBeLessThan(fps[i - 1]);
      const vram = ['low', 'medium', 'high', 'ultra', 'highest'].map((p) => presetEffect(p, a).vram);
      for (let i = 1; i < vram.length; i++) expect(vram[i]).toBeGreaterThan(vram[i - 1]);
    }
  });

  it('spreads least in esports titles and most in UE5 ones', () => {
    // The whole reason the table is per-archetype: a competitive shooter is
    // drawing flat surfaces either way, Lumen and Nanite are not.
    const spread = (a: string) => presetEffect('low', a).gpu / presetEffect('highest', a).gpu;
    expect(spread('esports')).toBeLessThan(spread('aaa-raster'));
    expect(spread('aaa-raster')).toBeLessThan(spread('ue5'));
  });

  it('moves the GPU side far more than the CPU side', () => {
    // Presets change shading work, not draw calls.
    const g = presetEffect('low', 'aaa-raster').gpu - 1;
    const c = presetEffect('low', 'aaa-raster').cpu - 1;
    expect(g).toBeGreaterThan(c * 2);
  });

  it('maps the names games actually use', () => {
    expect(normalisePreset('Epic')).toBe('highest');
    expect(normalisePreset('Very High')).toBe('ultra');
    expect(normalisePreset('MAXIMUM')).toBe('highest');
    expect(normalisePreset('Ultra Nightmare')).toBe('highest');
    expect(normalisePreset('normal')).toBe('medium');
  });

  it('falls back to the reference preset and admits it did', () => {
    expect(normalisePreset('sparkly')).toBe('high');
    expect(isKnownPreset('sparkly')).toBe(false);
    expect(isKnownPreset('epic')).toBe(true);
  });

  it('actually changes the estimate when the preset changes', () => {
    const b = rig('amd-ryzen-7-7800x3d', 'nvidia-geforce-rtx-4070', '1440p', 144);
    const low = estimate(b, 'stalker-2', '1440p', data, { preset: 'low' });
    const high = estimate(b, 'stalker-2', '1440p', data, { preset: 'high' });
    const top = estimate(b, 'stalker-2', '1440p', data, { preset: 'highest' });
    expect(low.avgFps!).toBeGreaterThan(high.avgFps!);
    expect(top.avgFps!).toBeLessThan(high.avgFps!);
  });

  it('widens the band away from the reference preset', () => {
    // The multipliers are priors, so a non-reference preset must be less
    // certain, not equally certain.
    const b = rig('amd-ryzen-7-7800x3d', 'nvidia-geforce-rtx-4070', '1440p', 144);
    const high = estimate(b, 'stalker-2', '1440p', data, { preset: 'high' });
    const low = estimate(b, 'stalker-2', '1440p', data, { preset: 'low' });
    expect(low.uncertainty!).toBeGreaterThan(high.uncertainty!);
  });
});

describe('settings advisor', () => {
  it('returns the PRETTIEST configuration that clears the target, not the fastest', () => {
    // Anyone can hit a target by setting everything to low. That is the wrong
    // answer, and getting it right is the entire point of this module.
    const b = rig('amd-ryzen-7-7800x3d', 'nvidia-geforce-rtx-4090', '1440p', 144);
    const r = settingsForTarget(b, 'the-witcher-3-next-gen', '1440p', 100, data)!;
    expect(r.best).not.toBeNull();
    expect(r.best!.avgFps).toBeGreaterThanOrEqual(100);
    // nothing prettier also clears the target
    const prettierAndMeeting = r.options.filter((o) => o.quality > r.best!.quality && o.meetsTarget);
    expect(prettierAndMeeting).toEqual([]);
  });

  it('reports honestly when no setting reaches the target', () => {
    const b = rig('amd-ryzen-5-3600', 'nvidia-geforce-gtx-1060-3gb', '2160p', 144);
    const r = settingsForTarget(b, 'alan-wake-2', '2160p', 144, data)!;
    expect(r.best).toBeNull();
    expect(r.verdict).toMatch(/short of|will not run|No estimate/i);
    // and still shows what the build CAN do rather than an empty list
    if (r.fastest) expect(r.options.length).toBeGreaterThan(0);
  });

  it('treats an engine frame cap as the ceiling it is', () => {
    // Elden Ring is locked to 60. Asking for 144 must not produce advice to
    // lower settings, because settings cannot buy frames the engine refuses.
    const b = rig('amd-ryzen-7-7800x3d', 'nvidia-geforce-rtx-4090', '1440p', 144);
    const r = settingsForTarget(b, 'elden-ring', '1440p', 144, data)!;
    expect(r.capLimited).toBe(true);
    expect(r.best!.config.preset).toBe('highest');
    expect(r.verdict).toMatch(/locked to 60fps/);
    expect(r.verdict).not.toMatch(/only just/);
    expect(r.notes.join(' ')).toMatch(/A faster GPU would change nothing/);
  });

  it('never counts frame generation towards the target', () => {
    // Generated frames raise the counter while latency RISES. Treating them as
    // having met a target would be actively misleading.
    const b = rig('amd-ryzen-7-7800x3d', 'nvidia-geforce-rtx-4090', '2160p', 144);
    const r = settingsForTarget(b, 'alan-wake-2', '2160p', 120, data)!;
    if (r.frameGen) {
      expect(r.frameGen.candidate.meetsTarget).toBe(false);
      expect(r.frameGen.presentedFps).toBeGreaterThan(r.frameGen.candidate.avgFps);
      expect(r.frameGen.caution).toMatch(/does NOT make the game more responsive/);
    }
    expect(r.best!.estimate.frameGenActive).toBeFalsy();
  });

  it('offers no frame generation on a capped title', () => {
    const b = rig('amd-ryzen-7-7800x3d', 'nvidia-geforce-rtx-4090', '1440p', 144);
    expect(settingsForTarget(b, 'elden-ring', '1440p', 60, data)!.frameGen).toBeUndefined();
  });

  it('picks an upscaler the hardware and the game both support', () => {
    expect(preferredUpscaler('nvidia', ['dlss', 'fsr'], ['dlss', 'fsr'])).toBe('dlss');
    expect(preferredUpscaler('amd', ['fsr'], ['dlss', 'fsr'])).toBe('fsr');
    // an NVIDIA card in a game with no DLSS falls back rather than inventing it
    expect(preferredUpscaler('nvidia', ['dlss', 'fsr'], ['fsr'])).toBe('fsr');
    expect(preferredUpscaler('intel', ['xess', 'fsr'], ['fsr', 'xess'])).toBe('xess');
    expect(preferredUpscaler('nvidia', ['dlss'], [])).toBeNull();
  });

  it('never recommends an upscaler the game does not have', () => {
    const b = rig('amd-ryzen-7-7800x3d', 'nvidia-geforce-rtx-4090', '1440p', 240);
    for (const id of [...data.games.keys()]) {
      const r = settingsForTarget(b, id, '1440p', 60, data);
      if (!r?.best) continue;
      const t = r.best.config.upscaling.tech;
      if (t !== 'none') expect(data.games.get(id)!.upscalingSupport).toContain(t);
    }
  });

  it('summarises a whole library', () => {
    const b = rig('amd-ryzen-5-5600', 'nvidia-geforce-rtx-3060-12gb', '1080p', 144);
    const lib = recommendForLibrary(b, ['counter-strike-2', 'cyberpunk-2077', 'stalker-2'], '1080p', 60, data);
    expect(lib.perGame.length).toBe(3);
    expect(lib.comfortable + lib.compromised + lib.unreachable).toBe(3);
    expect(lib.summary).toContain('60fps');
  });
});
