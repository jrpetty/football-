import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { ANCHOR_RAM, browseParts, buildEngineData, search } from '../src/core/catalogue.ts';
import { deriveCpuIndex, deriveGpuIndex } from '../src/core/indices.ts';

const ROOT = new URL('..', import.meta.url).pathname;
const load = (p: string) => JSON.parse(readFileSync(join(ROOT, p), 'utf8'));
const data = buildEngineData({
  gpus: load('data/catalogue/gpus.json'),
  cpus: load('data/catalogue/cpus.json'),
  games: load('data/catalogue/games.json'),
  references: load('data/catalogue/references.json'),
});

const hitFor = (id: string, kind: 'cpu' | 'gpu') =>
  browseParts(kind, data).flatMap((g) => g.hits).find((h) => h.id === id)!;

describe('incomplete-record flags', () => {
  it('says nothing at all about a complete record', () => {
    const h = hitFor('amd-ryzen-5-3600', 'cpu');
    expect(h.incomplete).toBeUndefined();
    expect(h.estimable).toBeUndefined();
  });

  it('separates a record with a gap from one that cannot be estimated', () => {
    // The Athlon is missing only its boost clock and still derives a real
    // index; the Core Ultra 9 285 is missing both clocks and derives zero.
    // Flagging both the same way would cry wolf on the one that works.
    const gap = hitFor('amd-athlon-3000g', 'cpu');
    expect(gap.incomplete).toEqual(['boostClockMHz']);
    expect(gap.estimable).toBe(true);
    expect(deriveCpuIndex(data.cpus.get('amd-athlon-3000g')!, ANCHOR_RAM, data.anchorCpu, ANCHOR_RAM).index.throughput).toBeGreaterThan(0);

    const dead = hitFor('intel-core-ultra-9-285', 'cpu');
    expect(dead.incomplete).toEqual(expect.arrayContaining(['baseClockMHz', 'boostClockMHz']));
    expect(dead.estimable).toBe(false);
  });

  it('flags the one GPU that cannot be estimated', () => {
    const h = hitFor('amd-radeon-rx-9070-gre', 'gpu');
    expect(h.estimable).toBe(false);
    expect(deriveGpuIndex(data.gpus.get('amd-radeon-rx-9070-gre')!, data.anchorGpu, ANCHOR_RAM).index.raster).toBe(0);
  });

  it('agrees with the engine about which parts are unusable', () => {
    // `estimable: false` must mean exactly what the engine's NO_ESTIMATE test
    // means, or the picker's warning is decorative.
    const deadCpus = [...data.cpus.values()].filter(
      (c) => deriveCpuIndex(c, ANCHOR_RAM, data.anchorCpu, ANCHOR_RAM).index.throughput <= 0,
    );
    const flagged = browseParts('cpu', data).flatMap((g) => g.hits).filter((h) => h.estimable === false);
    expect(flagged.map((h) => h.id).sort()).toEqual(deadCpus.map((c) => c.id).sort());
  });

  it('carries the same flags through search, not just browse', () => {
    const hit = search('Ultra 9 285', data, 50).find((h) => h.id === 'intel-core-ultra-9-285')!;
    expect(hit.estimable).toBe(false);
    expect(hit.incomplete?.length).toBeGreaterThan(0);
  });
});

describe('search scoping', () => {
  it('returns only the kind asked for, and never touches the other catalogue', () => {
    const gpus = search('7700', data, 50, 'gpu');
    const cpus = search('7700', data, 50, 'cpu');
    expect(gpus.length).toBeGreaterThan(0);
    expect(cpus.length).toBeGreaterThan(0);
    expect(gpus.every((h) => h.kind === 'gpu')).toBe(true);
    expect(cpus.every((h) => h.kind === 'cpu')).toBe(true);
  });

  it('applies the limit AFTER narrowing, so a caller gets what it asked for', () => {
    // The bug: the picker searched both catalogues with limit 200 and then
    // filtered by kind, so a query matching many parts of the other kind could
    // silently return fewer results than the limit allowed.
    const unscoped = search('r', data, 6).filter((h) => h.kind === 'cpu');
    const scoped = search('r', data, 6, 'cpu');
    expect(scoped).toHaveLength(6);
    expect(scoped.length).toBeGreaterThanOrEqual(unscoped.length);
  });

  it('still finds both kinds when no kind is given', () => {
    const both = search('7700', data, 50);
    expect(new Set(both.map((h) => h.kind)).size).toBe(2);
  });
});
