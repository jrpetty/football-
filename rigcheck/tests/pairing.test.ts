import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { buildEngineData } from '../src/core/catalogue.ts';
import { rank, resolveAll, resolveOne } from '../src/core/resolve.ts';
import { ceilingCpu, convergence, median, pairingReport, CEILING_CPUS } from '../src/core/pairing.ts';
import { rigData, rigVerdict } from '../marketing/scripts/lib/rig.ts';

const ROOT = new URL('..', import.meta.url).pathname;
const load = (p: string) => JSON.parse(readFileSync(join(ROOT, p), 'utf8'));
const data = buildEngineData({
  gpus: load('data/catalogue/gpus.json'), cpus: load('data/catalogue/cpus.json'),
  games: load('data/catalogue/games.json'), references: load('data/catalogue/references.json'),
});
const gpuLabels = new Map((load('data/catalogue/gpus.json').records as { id: string; fullName: string }[]).map((r) => [r.id, r.fullName]));
const cpuLabels = new Map((load('data/catalogue/cpus.json').records as { id: string; fullName: string }[]).map((r) => [r.id, r.fullName]));

describe('name resolution', () => {
  it('does not let a one-character token match inside another word', () => {
    // "x" is inside "rtx" and "gtx". Before the word-start rule, a query of
    // "titan x" scored the Titan RTX above every actual Titan X.
    const names = rank('titan x', gpuLabels).map((h) => h.label);
    expect(names).not.toContain('NVIDIA Titan RTX');
    expect(names).not.toContain('NVIDIA GeForce GTX Titan');
  });

  it('still reaches Xp from x, because the prefix is what matters', () => {
    expect(rank('titan x', gpuLabels).map((h) => h.label)).toContain('NVIDIA Titan Xp');
  });

  it('returns the whole family for a name several cards carry', () => {
    // The operator asked about "the Titan X 12GB". There are three, a third
    // apart in speed, and picking one silently is how you analyse the wrong
    // hardware.
    const ids = resolveAll('titan x', gpuLabels).map((h) => h.id).sort();
    expect(ids).toEqual(['nvidia-geforce-gtx-titan-x', 'nvidia-titan-x-pascal', 'nvidia-titan-xp']);
  });

  it('resolves an unambiguous name to exactly one part', () => {
    expect(resolveAll('2060 super', gpuLabels).map((h) => h.id)).toEqual(['nvidia-geforce-rtx-2060-super']);
    const r = resolveOne('i7 7700', cpuLabels);
    expect(r.ok && r.id).toBe('intel-core-i7-7700');
  });

  it('prefers the exact name over longer names containing it', () => {
    // "GTX 1080" must not resolve to the 1080 Ti.
    const r = resolveOne('geforce gtx 1080', gpuLabels);
    expect(r.ok && r.id).toBe('nvidia-geforce-gtx-1080');
  });

  it('reports nothing rather than guessing when no token matches', () => {
    const r = resolveOne('quantum blaster 9000', gpuLabels);
    expect(r.ok).toBe(false);
    expect(!r.ok && r.reason).toBe('none');
    expect(resolveAll('quantum blaster 9000', gpuLabels)).toEqual([]);
  });
});

describe('pairing report', () => {
  const rep = pairingReport('intel-core-i7-7700', 'nvidia-geforce-gtx-1080-ti', data);

  it('keeps a game that will not launch out of every average', () => {
    const aw = rep.rows.find((r) => r.gameId === 'alan-wake-2');
    expect(aw?.status).toBe('WILL_NOT_RUN');
    expect(aw?.blockedBy).toContain('MESH_SHADER_REQUIRED');
    // A blocked game has no frame rate, so it cannot contribute headroom and
    // must not be counted among the games that ran.
    expect(aw?.fps).toBeNull();
    expect(aw?.headroomPct).toBeNull();
    expect(rep.counts.ok).toBe(rep.rows.filter((r) => r.fps != null).length);
    expect(rep.counts.ok + rep.counts.blocked).toBe(rep.counts.total);
  });

  it('calls a 2017 quad-core under a fast card a processor wall', () => {
    expect(rep.verdict).toBe('cpu-wall');
    expect(rep.counts.cpuBound).toBeGreaterThan(rep.counts.gpuBound);
  });

  it('measures headroom against a different processor than the one under test', () => {
    expect(rep.ceiling).not.toBeNull();
    expect(rep.ceiling!.id).not.toBe('intel-core-i7-7700');
    // And the reference platform brings its own memory, because DDR4 does not
    // go into an AM5 board. Claiming otherwise would price a chip swap.
    expect(rep.ceiling!.ram.type).toBe('DDR5');
  });

  it('never picks the processor under test as its own ceiling', () => {
    const self = CEILING_CPUS.find((c) => data.cpus.has(c))!;
    expect(ceilingCpu(data, self)?.id).not.toBe(self);
  });

  it('reports the worst game, and it is the worst', () => {
    const worst = rep.worst!;
    const all = rep.rows.filter((r) => r.headroomPct != null).map((r) => r.headroomPct!);
    expect(worst.headroomPct).toBe(Math.max(...all));
  });

  it('refuses a part that is not in the catalogue rather than estimating one', () => {
    expect(() => pairingReport('not-a-cpu', 'nvidia-geforce-gtx-1080-ti', data)).toThrow(/not a processor/);
    expect(() => pairingReport('intel-core-i7-7700', 'not-a-gpu', data)).toThrow(/not a graphics card/);
  });

  it('finds a modern pairing balanced or card-limited, not processor-limited', () => {
    // The guard against a verdict that always says "cpu-wall": put a fast chip
    // under a card and the answer has to change.
    const modern = pairingReport('amd-ryzen-7-7800x3d', 'nvidia-geforce-rtx-4070', data, {
      resolution: '1440p', ram: { totalGB: 32, channels: 2, speedMTs: 6000, type: 'DDR5' },
    });
    expect(modern.verdict).not.toBe('cpu-wall');
  });
});

describe('median', () => {
  it('is the middle of an odd list and the mean of the two middles of an even one', () => {
    expect(median([5, 1, 3])).toBe(3);
    expect(median([1, 2, 3, 4])).toBe(3); // (2+3)/2 rounds to 3
    expect(median([])).toBeNull();
  });
});

describe('convergence', () => {
  const cards = ['nvidia-geforce-rtx-2060-super', 'nvidia-titan-x-pascal', 'nvidia-titan-xp', 'nvidia-geforce-gtx-1080-ti'];
  const c = convergence('intel-core-i7-7700', cards, data);

  it('finds cards a third apart on paper landing within a few frames on a slow chip', () => {
    expect(c.convergedCount).toBeGreaterThanOrEqual(4);
    const bg3 = c.rows.find((r) => r.gameId === 'baldurs-gate-3')!;
    expect(bg3.spreadFps).toBeLessThanOrEqual(c.toleranceFps);
    expect(bg3.converged).toBe(true);
  });

  it('does not call a row converged when only one card ran it', () => {
    // Alan Wake 2 runs on the Turing card alone. One number is not agreement.
    const aw = c.rows.find((r) => r.gameId === 'alan-wake-2')!;
    expect(aw.fps.filter((f) => f != null)).toHaveLength(1);
    expect(aw.spreadFps).toBeNull();
    expect(aw.converged).toBe(false);
    expect(c.comparableCount).toBe(c.rows.filter((r) => r.spreadFps != null).length);
  });

  it('separates the same cards once the processor is out of the way', () => {
    const at1440 = convergence('intel-core-i7-7700', cards, data, { resolution: '1440p' });
    expect(at1440.convergedCount).toBeLessThan(c.convergedCount);
  });

  it('needs two cards to compare', () => {
    expect(() => convergence('intel-core-i7-7700', ['nvidia-geforce-gtx-1080-ti'], data)).toThrow(/at least two/);
  });
});

describe('check-my-rig post data', () => {
  const r = rigData('intel-core-i7-7700', 'nvidia-geforce-gtx-1080-ti', data);

  it('leads with the game losing the most', () => {
    expect(r.rows[0].gainPct).toBe(Math.max(...r.rows.map((x) => x.gainPct)));
    for (let i = 1; i < r.rows.length; i++) expect(r.rows[i - 1].gainPct).toBeGreaterThanOrEqual(r.rows[i].gainPct);
  });

  it('carries the blocked game separately from the chart rows', () => {
    expect(r.rows.map((x) => x.game)).not.toContain('Alan Wake 2');
    expect(r.blocked.map((b) => b.game)).toContain('Alan Wake 2');
    expect(r.blocked[0].reason).toMatch(/mesh shader/i);
  });

  it('says to spend on the part that is actually the limit', () => {
    expect(r.spendOn).toBe('cpu');
    expect(rigVerdict(r)).toMatch(/is the limit in \d+ of \d+ games/);
  });

  it('does not manufacture a bottleneck where the model does not find one', () => {
    const balanced = rigData('amd-ryzen-7-7800x3d', 'nvidia-geforce-rtx-4070', data, {
      resolution: '1440p', ram: { totalGB: 32, channels: 2, speedMTs: 6000, type: 'DDR5' },
    });
    expect(balanced.spendOn).not.toBe('cpu');
    expect(rigVerdict(balanced)).not.toMatch(/capping|faster card changes/i);
  });

  it('never claims a bare chip swap, because the reference brings its own memory', () => {
    expect(r.ceilingRam.type).toBe('DDR5');
    expect(r.ram.type).toBe('DDR4');
  });
});
