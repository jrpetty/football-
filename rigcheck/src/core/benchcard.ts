/**
 * What a shareable result card is allowed to say.
 *
 * Somebody who has just run the benchmark has the one thing this whole project
 * otherwise lacks: a measurement of real hardware. A card they can post is
 * worth building — but it is also the point where the temptation to invent a
 * score is strongest, because a score is what makes a card shareable and
 * nothing here can produce one honestly.
 *
 * So this builds the card's CONTENT and refuses the score. There is no
 * composite, no rank, no percentile, no comparison against parts nobody
 * measured. What goes on a card is what the run actually established:
 *
 *   - which adapter was rendering, which is a fact and often a surprise
 *   - whether it held its clocks, which is the machine against itself
 *   - what shape the processor is, read off its own scaling curve
 *   - where its cache levels end, read off its own latency sweep
 *
 * All four are self-referential or reported. None needs a corpus. A card built
 * from them says something true about that machine and nothing at all about
 * whether it beats anybody else's — which is the honest version of this
 * feature, and the only version this project can ship.
 *
 * The drawing lives in ui/bench/drawcard.ts because it touches a canvas. This
 * half is pure, so what the card claims can be tested.
 */

import type { BenchResult } from './browserbench.ts';
import {
  cacheReading, coreInference, rendererClass, scalingVerdict, stabilityVerdict, steadinessVerdict,
  throttleVerdict, workloadSummary,
} from './browserbench.ts';
import { cleanDeviceName, parseRenderer } from './renderer.ts';

/** One measured line on the card. */
export interface CardStat {
  label: string;
  value: string;
  /** Sub-line, for the unit or the caveat. */
  note?: string;
  /** Drives the accent. `null` when the figure carries no judgement. */
  tone: 'good' | 'bad' | null;
}

export interface BenchCard {
  /** The adapter that was actually rendering, cleaned up for display. */
  device: string;
  /** 'discrete' | 'integrated' | 'software' | 'unknown'. */
  deviceClass: string;
  /** Present when the run was on a rasteriser rather than a card. */
  softwareWarning: string | null;
  stats: CardStat[];
  /** Longer lines under the stats — what the run established in words. */
  findings: string[];
  /** Shown instead of `findings` when a run found nothing. What it cannot see. */
  limits: string[];
  /** The provenance line. Never optional, never abbreviated away. */
  footer: string;
  /** ISO date the run started, for the corner. */
  at: string;
  /** True when nothing measurable came back and a card would be misleading. */
  empty: boolean;
}

/**
 * The rasteriser's name, for a headline.
 *
 * Matched against the same list `rendererClass` uses, so a string this calls
 * software is a string that list recognised. Falls back to the plain words
 * rather than to a guess.
 */
function softwareName(renderer: string): string {
  const r = renderer.toLowerCase();
  for (const [needle, name] of [
    ['swiftshader', 'SwiftShader'],
    ['llvmpipe', 'llvmpipe'],
    ['lavapipe', 'lavapipe'],
    ['softpipe', 'softpipe'],
    ['microsoft basic', 'Microsoft Basic Render'],
  ] as const) {
    if (r.includes(needle)) return name;
  }
  return 'Software rendering';
}

const pct = (x: number) => `${Math.round(x * 100)}%`;

export function benchCard(result: BenchResult): BenchCard {
  const { gpu, cpu } = result;
  const noise = cpu?.variation ?? 0;
  const identity = gpu?.renderer ? parseRenderer(gpu.renderer) : null;
  const deviceClass = gpu?.renderer ? rendererClass(gpu.renderer) : 'unknown';
  // `parseRenderer` hands back the WHOLE string for a software rasteriser, on
  // purpose — it has no device name to extract and inventing one would be
  // worse. That is right for a detector and wrong for a headline: it puts
  // "ANGLE (Google, Vulkan 1.3.0 (SwiftShader Device (Subzero) (0x0000C0DE)),
  // SwiftShader driver)" across four lines in 52px type. The card names the
  // rasteriser and keeps the raw string in the warning underneath it.
  const device = identity?.software
    ? `${softwareName(gpu?.renderer ?? '')} — no graphics card`
    : identity?.device
      ? cleanDeviceName(identity.device)
      : '';

  const stats: CardStat[] = [];
  const findings: string[] = [];

  /* -- did it hold up ---------------------------------------------------- */

  const gpuThr = gpu ? throttleVerdict(gpu.windows, noise) : null;
  if (gpuThr) {
    stats.push({
      label: 'graphics held',
      value: pct(gpuThr.retained),
      // Above 100% means it ended faster than it started, which is a machine
      // still warming up rather than a fault — but "102% of its opening rate"
      // reads as an arithmetic bug to anybody who has not thought about it, so
      // the note says what it means instead of leaving the number to explain
      // itself. The figure is not clamped: it is what was measured.
      note: gpuThr.retained >= 1 ? 'no decline at all' : 'of its opening rate',
      tone: gpuThr.declined ? 'bad' : 'good',
    });
    if (gpuThr.declined) findings.push(gpuThr.detail);
  }

  const cpuThr = cpu ? throttleVerdict(cpu.windows, noise) : null;
  if (cpuThr) {
    stats.push({
      label: 'processor held',
      value: pct(cpuThr.retained),
      note: cpuThr.retained >= 1 ? 'no decline at all' : 'under sustained load',
      tone: cpuThr.declined ? 'bad' : 'good',
    });
  }

  /* -- what shape the processor is --------------------------------------- */

  const cores = cpu?.scaling ? coreInference(cpu.scaling, cpu.reportedThreads) : null;
  if (cores?.physicalCores) {
    stats.push({
      label: 'cores measured',
      value:
        cores.shape === 'smt'
          ? `${cores.physicalCores}c / ${cores.reported}t`
          : `${cores.physicalCores} cores`,
      note: cores.shape === 'smt' ? 'two threads per core' : 'one thread each',
      tone: null,
    });
  } else if (cpu?.reportedThreads) {
    stats.push({
      label: 'threads',
      value: `${cpu.reportedThreads}`,
      note: 'as the browser sees them',
      tone: null,
    });
  }

  const sc = cpu ? scalingVerdict(cpu) : null;
  if (sc && !sc.healthy) findings.push(sc.detail);

  /* -- the cache staircase ------------------------------------------------ */

  const cache = cpu?.latencyCurve ? cacheReading(cpu.latencyCurve) : null;
  if (cache) {
    const mb = cache.lastLevelBytes ? cache.lastLevelBytes / 1024 / 1024 : null;
    stats.push({
      label: 'memory latency',
      value: `${cache.slowestNs.toFixed(0)}ns`,
      note: mb ? `cache runs out near ${mb.toFixed(0)}MB` : 'no clear cache step',
      tone: null,
    });
  }

  /* -- evenness, and how much any of it can be trusted --------------------- */

  const steady = steadinessVerdict(gpu?.passMs ?? []);
  if (steady && !steady.steady && deviceClass !== 'software') findings.push(steady.detail);

  const stab = stabilityVerdict(cpu?.variation);
  if (stab) {
    stats.push({
      label: 'repeatability',
      value: `±${(stab.variation * 100).toFixed(1)}%`,
      note: stab.reliable ? 'quiet machine' : 'something else was running',
      tone: stab.reliable ? 'good' : 'bad',
    });
    if (!stab.reliable) findings.push(stab.detail);
  }

  if (result.interrupted) {
    findings.push(
      'The tab lost focus partway through, so the timings above are not trustworthy. Browsers throttle ' +
        'background tabs deliberately.',
    );
  }

  /* -- the three workloads, described and not scored ---------------------- */

  // The figure goes in the big type and the unit goes underneath. Together they
  // are "975M shader operations/s", which does not fit a stat box at any size
  // worth reading.
  for (const w of workloadSummary(gpu?.workloads)) {
    const gap = w.value.indexOf(' ');
    const figure = gap > 0 ? w.value.slice(0, gap) : w.value;
    const unit = gap > 0 ? w.value.slice(gap + 1) : '';
    stats.push({ label: w.label, value: figure, note: unit ? `${unit} · own units` : "this benchmark's own units", tone: null });
  }

  const softwareWarning =
    deviceClass === 'software'
      ? 'Every graphics figure below describes a processor emulating a graphics card. The browser reports ' +
        `its renderer as "${gpu?.renderer ?? ''}".`
      : null;

  // Shown in place of findings when a run turned nothing up — which is the
  // common case, and would otherwise leave half the card blank. Not filler:
  // the limits are the most important thing on a card somebody will read
  // without any of the surrounding context.
  const limits = [
    'It cannot tell you a frame rate. A shader in a browser tab and a game engine share almost nothing, ' +
      'and nothing here has been converted into one.',
    'It cannot rank this machine against anyone else\'s. No measured corpus exists for these units, and ' +
      'inventing thresholds is the error this whole project refuses to make.',
  ];

  return {
    limits,
    device: device || 'adapter not named by the browser',
    deviceClass,
    softwareWarning,
    stats,
    findings,
    // The one line that cannot be dropped for space. A card with numbers on it
    // and no provenance is exactly the artefact this project exists not to
    // produce, and a card is the most likely thing to be seen without its
    // context.
    footer:
      'Measured in a browser on this machine. Not a score, not a rank, and not comparable with anybody ' +
      "else's — the units are this benchmark's own. What it does establish is which adapter is rendering, " +
      'whether the machine holds its clocks, and what shape its processor is.',
    at: result.startedAt,
    empty: !gpu && !cpu,
  };
}

/** A short line for a share sheet or a filename. */
export function cardHeadline(card: BenchCard): string {
  if (card.empty) return 'Nothing could be measured in this browser';
  if (card.softwareWarning) return 'No graphics card in use — rendered on the processor';
  const held = card.stats.find((s) => s.label === 'graphics held');
  const cores = card.stats.find((s) => s.label === 'cores measured');
  return [card.device, cores && cores.value, held && `held ${held.value} under load`]
    .filter(Boolean)
    .join(' · ');
}
