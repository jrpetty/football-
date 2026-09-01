import { describe, expect, it } from 'vitest';
import { benchCard, cardHeadline } from '../src/core/benchcard.ts';
import type { BenchResult, CpuProbe, GpuProbe } from '../src/core/browserbench.ts';

const win = (xs: number[]) => xs.map((t, i) => ({ atMs: i * 1000, throughput: t }));

const gpu = (over: Partial<GpuProbe> = {}): GpuProbe => ({
  renderer: 'ANGLE (NVIDIA, NVIDIA GeForce RTX 3060 Direct3D11 vs_5_0 ps_5_0, D3D11)',
  vendor: 'NVIDIA',
  api: 'webgl2',
  throughput: 2.4e11,
  windows: win([100, 100, 99, 99, 98, 98, 97, 97]),
  passMs: Array.from({ length: 200 }, () => 15),
  workloads: [
    { kind: 'alu', peak: 2.4e11, unit: 'shader operations/s', passMs: [], workPerPass: 1 },
    { kind: 'bandwidth', peak: 3.1e11, unit: 'bytes/s', passMs: [], workPerPass: 1 },
    { kind: 'fill', peak: 8.8e9, unit: 'pixels/s', passMs: [], workPerPass: 1 },
  ],
  ...over,
});

/** An 8-core part with two threads per core, as the analyser reads one. */
const smtLadder = [1, 2, 4, 8, 16].map((threads) => ({
  threads,
  totalOps: 1e8 * (Math.min(threads, 8) * 0.97 + Math.max(0, threads - 8) * 0.25),
}));

const cpu = (over: Partial<CpuProbe> = {}): CpuProbe => ({
  singleThread: 4.3e8,
  multiThread: 3e9,
  reportedThreads: 16,
  workersUsed: 16,
  windows: win([100, 100, 99, 99, 98, 98, 98, 97]),
  scaling: smtLadder,
  latencyCurve: [
    { bytes: 8192, latencyNs: 2.0 }, { bytes: 32768, latencyNs: 2.1 },
    { bytes: 131072, latencyNs: 3.6 }, { bytes: 524288, latencyNs: 5.6 },
    { bytes: 2097152, latencyNs: 15.9 }, { bytes: 8388608, latencyNs: 112.5 },
    { bytes: 16777216, latencyNs: 146 },
  ],
  variation: 0.015,
  ...over,
});

const result = (over: Partial<BenchResult> = {}): BenchResult => ({
  startedAt: '2026-09-01T10:00:00.000Z',
  durationS: 80,
  gpu: gpu(),
  cpu: cpu(),
  memory: null,
  notes: [],
  interrupted: false,
  ...over,
});

/* ------------------------------------------------------- what it refuses -- */

describe('what a shareable card is not allowed to say', () => {
  /**
   * Everything on the card EXCEPT the footer.
   *
   * The footer is excluded on purpose: its whole job is to say "not a score,
   * not a rank", so a banned-word scan that includes it fails on the very
   * sentence that makes the card honest.
   */
  const body = (r: BenchResult) => {
    const c = benchCard(r);
    return [c.device, ...c.findings, ...c.stats.map((s) => `${s.label} ${s.value} ${s.note ?? ''}`)]
      .join(' ')
      .toLowerCase();
  };

  it('never puts a score, rank or percentile on the card', () => {
    // The whole temptation of a shareable card. A composite number is what
    // makes one spread, and nothing here can produce one honestly.
    const t = body(result());
    for (const word of ['score', 'rank', 'percentile', 'points', 'faster than', 'out of 100']) {
      expect(t, word).not.toContain(word);
    }
  });

  it('denies being a score in as many words, on the card itself', () => {
    expect(benchCard(result()).footer.toLowerCase()).toContain('not a score');
    expect(benchCard(result()).footer.toLowerCase()).toContain('not a rank');
  });

  it('never converts anything into a frame rate', () => {
    const t = body(result()) + ' ' + benchCard(result()).footer.toLowerCase();
    expect(t).not.toMatch(/\d+\s*fps/);
    expect(t).not.toContain('frames per second');
  });

  it('says the units are its own, on every card', () => {
    // Including the ones with nothing wrong, which are the cards most likely
    // to be posted without their context.
    expect(benchCard(result()).footer).toMatch(/not comparable/i);
    expect(benchCard(result()).footer).toMatch(/benchmark's own/i);
    expect(benchCard(result({ gpu: null, cpu: null })).footer).toMatch(/not comparable/i);
  });
});

/* ------------------------------------------------------- what it reports -- */

describe('what the card reports', () => {
  it('names the adapter that was actually rendering', () => {
    const c = benchCard(result());
    expect(c.device).toBe('NVIDIA GeForce RTX 3060');
    expect(c.deviceClass).toBe('discrete');
    expect(c.softwareWarning).toBeNull();
  });

  it('leads with software rendering, because that is the whole story', () => {
    const c = benchCard(result({
      gpu: gpu({ renderer: 'ANGLE (Google, Vulkan 1.3.0 (SwiftShader Device (Subzero)), SwiftShader driver)' }),
    }));
    expect(c.deviceClass).toBe('software');
    // The headline names the rasteriser and says there is no card. It must not
    // be the raw renderer string: "ANGLE (Google, Vulkan 1.3.0 (SwiftShader
    // Device (Subzero)), SwiftShader driver)" is four lines of 52px type and
    // tells a reader nothing.
    expect(c.device).toBe('SwiftShader — no graphics card');
    expect(c.device.length).toBeLessThan(40);
    // The raw string survives, underneath, where somebody checking can read it.
    expect(c.softwareWarning).toContain('SwiftShader Device');
    expect(cardHeadline(c)).toMatch(/rendered on the processor/i);
  });

  it('reads the core topology off the scaling curve', () => {
    const c = benchCard(result());
    const cores = c.stats.find((s) => s.label === 'cores measured')!;
    expect(cores.value).toBe('8c / 16t');
    expect(cores.note).toMatch(/two threads per core/);
  });

  it('falls back to the reported thread count when the curve cannot be read', () => {
    const c = benchCard(result({ cpu: cpu({ scaling: undefined }) }));
    expect(c.stats.find((s) => s.label === 'cores measured')).toBeUndefined();
    expect(c.stats.find((s) => s.label === 'threads')!.value).toBe('16');
  });

  it('marks a machine that could not hold its clocks', () => {
    const c = benchCard(result({ gpu: gpu({ windows: win([100, 100, 100, 100, 80, 76, 72, 70]) }) }));
    const held = c.stats.find((s) => s.label === 'graphics held')!;
    expect(held.tone).toBe('bad');
    expect(c.findings.join(' ')).toMatch(/fell/);
  });

  it('says when the run itself was not trustworthy', () => {
    const c = benchCard(result({ interrupted: true }));
    expect(c.findings.join(' ')).toMatch(/lost focus/i);
  });

  it('flags a noisy machine rather than quietly reporting its numbers', () => {
    const c = benchCard(result({ cpu: cpu({ variation: 0.18 }) }));
    const rep = c.stats.find((s) => s.label === 'repeatability')!;
    expect(rep.tone).toBe('bad');
    expect(rep.note).toMatch(/something else/);
  });

  it('produces an empty card rather than a misleading one when nothing ran', () => {
    const c = benchCard(result({ gpu: null, cpu: null }));
    expect(c.empty).toBe(true);
    expect(c.stats).toHaveLength(0);
    expect(cardHeadline(c)).toMatch(/nothing could be measured/i);
  });

  it('writes a headline from what was measured, not from a verdict', () => {
    expect(cardHeadline(benchCard(result()))).toBe('NVIDIA GeForce RTX 3060 · 8c / 16t · held 97% under load');
  });
});

/* -------------------------------------------------- the quiet-run layout -- */

describe('a run that found nothing', () => {
  it('carries the limits instead, because that is most runs', () => {
    // The common case. Without this the bottom half of a healthy machine's
    // card is blank, and a card that is half empty gets read as broken.
    const c = benchCard(result());
    expect(c.findings).toHaveLength(0);
    expect(c.limits.length).toBeGreaterThan(0);
    expect(c.limits.join(' ')).toMatch(/cannot tell you a frame rate/i);
    expect(c.limits.join(' ')).toMatch(/rank this machine/i);
  });

  it('always carries the limits, so they are on the card either way', () => {
    const busy = benchCard(result({ cpu: cpu({ variation: 0.2 }) }));
    expect(busy.findings.length).toBeGreaterThan(0);
    expect(busy.limits.length).toBeGreaterThan(0);
  });

  it('explains a retention above 100 rather than leaving it looking like a bug', () => {
    // A machine that ended faster than it started was still warming up. The
    // figure is not clamped — it is what was measured — but "102% of its
    // opening rate" reads as broken arithmetic without a word of help.
    const warming = benchCard(result({ gpu: gpu({ windows: win([90, 92, 95, 96, 99, 100, 101, 102]) }) }));
    const held = warming.stats.find((s) => s.label === 'graphics held')!;
    expect(Number(held.value.replace('%', ''))).toBeGreaterThan(100);
    expect(held.note).toBe('no decline at all');
    expect(held.tone).toBe('good');
  });

  it('applies the same wording to the processor row', () => {
    // Fixed on graphics first and missed here, which put "102% — under
    // sustained load" on a rendered card.
    const warming = benchCard(result({ cpu: cpu({ windows: win([90, 92, 95, 96, 99, 100, 101, 102]) }) }));
    expect(warming.stats.find((s) => s.label === 'processor held')!.note).toBe('no decline at all');
  });
});
