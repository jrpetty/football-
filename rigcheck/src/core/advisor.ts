/**
 * Settings advisor: what should this machine actually be set to?
 *
 * Every other query in this project answers "how fast is this build". A player
 * has the opposite question — "I have a 144Hz 1440p monitor and I want to hit
 * it, what do I turn down?" — and answering it means searching the settings
 * space rather than evaluating one point in it.
 *
 * The search is over preset x upscaling x ray tracing, ranked by a visual
 * quality score, keeping the HIGHEST quality configuration that still clears
 * the target. That ordering is the whole point: anyone can find a configuration
 * that hits 144fps by turning everything to low, and it is the wrong answer.
 *
 * Two honesty constraints run through this file. Preset multipliers are priors
 * with no fixture support (see PRESET_MODEL), so every recommendation carries
 * that caveat. And frame generation is never counted towards a target: it
 * raises the frame counter while latency RISES, so treating it as though it
 * met a 144fps goal would be actively misleading. It is offered separately,
 * with the latency stated.
 */
import { estimate, type EngineData, type EstimateOptions } from './engine.ts';
import { estimateLatency, latencyVerdict } from './physics.ts';
import { PRESETS, presetEffect, type Preset } from './presets.ts';
import { UPSCALE_RENDER_SCALE } from './types.ts';
import type {
  Build,
  FpsEstimate,
  Resolution,
  UpscalingQuality,
  UpscalingSetting,
  UpscalingTech,
} from './types.ts';

export interface SettingsConfig {
  preset: Preset;
  upscaling: UpscalingSetting;
  rtTier: 'on' | 'off';
}

export interface SettingsCandidate {
  config: SettingsConfig;
  avgFps: number;
  low1PctFps?: number;
  /** 0-100ish. Higher is prettier. Used only to ORDER candidates. */
  quality: number;
  meetsTarget: boolean;
  /** Fraction above the target. -0.2 means 20% short. */
  headroom: number;
  estimate: FpsEstimate;
  label: string;
  /**
   * Why this candidate is in the options list. A UI needs to say "this is our
   * pick" differently from "this is the prettiest it can go", and a flat list
   * of six configurations with no roles is a wall of numbers.
   */
  role?: 'pick' | 'prettiest' | 'fastest' | 'one-step-prettier' | 'one-step-safer';
}

export interface SettingsRecommendation {
  gameId: string;
  gameName: string;
  resolution: Resolution;
  targetFps: number;
  /** Highest-quality configuration that clears the target. Null if none do. */
  best: SettingsCandidate | null;
  /** Fastest configuration available. Null only when nothing could be estimated. */
  fastest: SettingsCandidate | null;
  /** Prettiest configuration available. Null only when nothing could be estimated. */
  prettiest: SettingsCandidate | null;
  /** True when the engine's own cap, not the hardware, is what limits the result. */
  capLimited: boolean;
  /** A short ladder worth showing in a UI, best first, de-duplicated. */
  options: SettingsCandidate[];
  /** Plain-language answer to "so what do I set it to". */
  verdict: string;
  /** Caveats that apply to this specific recommendation. */
  notes: string[];
  /** Frame generation, offered separately and never counted toward the target. */
  frameGen?: {
    candidate: SettingsCandidate;
    presentedFps: number;
    latencyMs: number;
    verdict: string;
    caution: string;
  };
}

/**
 * Visual quality, on a scale only this file needs to agree with itself about.
 *
 * The shape matters more than the numbers: preset gains flatten at the top
 * (ultra to highest is a much smaller visual step than medium to high, for a
 * much larger cost), ray tracing is worth roughly a preset step and a half
 * where a game genuinely uses it, and upscaling penalties accelerate as the
 * render resolution drops — Quality mode is close to free, Ultra Performance is
 * not remotely free.
 */
const PRESET_QUALITY: Record<Preset, number> = {
  low: 0,
  medium: 25,
  high: 50,
  ultra: 68,
  highest: 78,
};

const UPSCALE_QUALITY_PENALTY: Record<UpscalingQuality, number> = {
  native: 0,
  quality: -3,
  balanced: -9,
  performance: -18,
  'ultra-performance': -32,
};

const RT_QUALITY_BONUS = 16;

function qualityScore(c: SettingsConfig): number {
  return (
    PRESET_QUALITY[c.preset] +
    (c.rtTier === 'on' ? RT_QUALITY_BONUS : 0) +
    UPSCALE_QUALITY_PENALTY[c.upscaling.quality]
  );
}

/**
 * Pick the upscaler this build should actually use for this game.
 *
 * Not a free choice: DLSS is NVIDIA-only and needs an RTX card, XeSS runs
 * everywhere but is at its best on Arc, FSR runs on anything, and TSR is
 * whatever the Unreal title ships. Preference order is by image quality on the
 * hardware in question, intersected with what the game supports.
 */
export function preferredUpscaler(
  gpuVendor: string,
  gpuSupports: UpscalingTech[],
  gameSupports: UpscalingTech[],
): UpscalingTech | null {
  const order: UpscalingTech[] =
    gpuVendor === 'nvidia' ? ['dlss', 'xess', 'fsr', 'tsr']
    : gpuVendor === 'intel' ? ['xess', 'fsr', 'tsr']
    : ['fsr', 'xess', 'tsr'];
  for (const t of order) {
    if (gameSupports.includes(t) && (t === 'tsr' || gpuSupports.includes(t))) return t;
  }
  // TSR is an engine feature, so a game that lists it can use it on any GPU.
  if (gameSupports.includes('tsr')) return 'tsr';
  return null;
}

function describe(c: SettingsConfig): string {
  const up =
    c.upscaling.tech === 'none' || c.upscaling.quality === 'native'
      ? 'native'
      : `${c.upscaling.tech.toUpperCase()} ${c.upscaling.quality}`;
  return `${c.preset}, ${up}${c.rtTier === 'on' ? ', RT on' : ''}`;
}

/**
 * Search the settings space for the best configuration meeting a target.
 *
 * `targetFps` is the number to clear. It should be the monitor's refresh rate
 * for a smooth-feeling result, or a lower figure if the player would rather
 * have the visuals — both are legitimate and the caller decides which.
 */
export function settingsForTarget(
  build: Build,
  gameId: string,
  resolution: Resolution,
  targetFps: number,
  data: EngineData,
  opts: { airflowTier?: EstimateOptions['airflowTier']; allowRt?: boolean } = {},
): SettingsRecommendation | null {
  const game = data.games.get(gameId);
  const gpu = data.gpus.get(build.gpuId);
  const cpu = data.cpus.get(build.cpuId);
  if (!game || !gpu || !cpu) return null;

  const notes: string[] = [];

  // The game's own cap is a hard ceiling on the whole exercise. Recommending
  // settings to reach 144fps in a title locked at 60 would be nonsense.
  const cap = game.fpsCap ?? null;
  if (cap != null && targetFps > cap) {
    notes.push(
      `${game.name} is capped at ${cap}fps by the engine, so ${targetFps}fps is not reachable at any setting. The target below is treated as ${cap}.`,
    );
  }
  const effectiveTarget = cap != null ? Math.min(targetFps, cap) : targetFps;

  const tech = preferredUpscaler(gpu.vendor, gpu.caps.upscaling ?? [], game.upscalingSupport ?? []);
  const rtPossible =
    (opts.allowRt ?? true) && gpu.caps.rayTracing && (game.requirements?.rayTracingRequired || game.archetype === 'aaa-rt');

  const qualities: UpscalingQuality[] = tech ? ['native', 'quality', 'balanced', 'performance'] : ['native'];
  const rtTiers: ('on' | 'off')[] = rtPossible ? ['off', 'on'] : ['off'];

  const candidates: SettingsCandidate[] = [];
  for (const preset of PRESETS) {
    for (const q of qualities) {
      for (const rt of rtTiers) {
        const upscaling: UpscalingSetting =
          q === 'native' || !tech
            ? { tech: 'none', quality: 'native', frameGen: false }
            : { tech, quality: q, frameGen: false };
        const config: SettingsConfig = { preset, upscaling, rtTier: rt };
        const est = estimate(build, gameId, resolution, data, {
          preset,
          upscaling,
          rtTier: rt,
          airflowTier: opts.airflowTier,
        });
        if (est.status !== 'ok' || est.avgFps == null) continue;
        candidates.push({
          config,
          avgFps: est.avgFps,
          low1PctFps: est.low1PctFps,
          quality: qualityScore(config),
          meetsTarget: est.avgFps >= effectiveTarget,
          headroom: est.avgFps / effectiveTarget - 1,
          estimate: est,
          label: describe(config),
        });
      }
    }
  }

  if (!candidates.length) {
    // Every configuration was blocked. That is a fact about the build, and the
    // caller needs the reason rather than an empty list.
    const probe = estimate(build, gameId, resolution, data, { airflowTier: opts.airflowTier });
    return {
      gameId,
      gameName: game.name,
      resolution,
      targetFps,
      best: null,
      fastest: null,
      prettiest: null,
      capLimited: false,
      options: [],
      verdict:
        probe.status === 'WILL_NOT_RUN'
          ? `${game.name} will not run on this build: ${probe.gateFailures[0]?.detail ?? 'a hardware capability gate fired'}`
          : `No estimate is possible for ${game.name} on this build — ${probe.terms?.[0]?.explain ?? 'the catalogue record is incomplete'}`,
      notes,
    };
  }

  // Highest quality among those that clear the target; ties broken by headroom
  // so a comfortable configuration wins over one that scrapes it.
  const meeting = candidates.filter((c) => c.meetsTarget);
  const best =
    meeting.sort((a, b) => b.quality - a.quality || b.headroom - a.headroom)[0] ?? null;
  const fastest = [...candidates].sort((a, b) => b.avgFps - a.avgFps)[0];
  const prettiest = [...candidates].sort((a, b) => b.quality - a.quality || b.avgFps - a.avgFps)[0];

  // A cap changes what "just scraping it" means. Sitting exactly on a 60fps
  // engine limit is the best possible outcome, not a near miss, and saying
  // "only just" there would send someone lowering settings for no reason.
  const capLimited = cap != null && !!best && best.avgFps >= cap - 1;

  // A short, purposeful ladder rather than the whole search. Each entry answers
  // a question someone actually asks: what should I use, what if I want it
  // prettier, what if I want it smoother.
  const options: SettingsCandidate[] = [];
  const push = (c: SettingsCandidate | null | undefined, role: SettingsCandidate['role']) => {
    if (!c) return;
    if (options.some((o) => o.label === c.label)) return;
    // A "prettier" option that cannot hold a playable frame rate is not an
    // option, it is a distraction. Half the target is the floor for showing it.
    if (role === 'prettiest' && c.avgFps < effectiveTarget * 0.5) return;
    options.push({ ...c, role });
  };
  push(best, 'pick');
  if (best) {
    const prettier = candidates
      .filter((c) => c.quality > best.quality && c.avgFps >= effectiveTarget * 0.8)
      .sort((a, b) => a.quality - b.quality)[0];
    push(prettier, 'one-step-prettier');
    const safer = candidates
      .filter((c) => c.quality < best.quality && c.avgFps > best.avgFps * 1.1)
      .sort((a, b) => b.quality - a.quality)[0];
    push(safer, 'one-step-safer');
  }
  if (!best) {
    // Nothing clears the target, so the useful thing is the realistic ladder of
    // what this build CAN do — showing one row would leave someone with no idea
    // what they are choosing between.
    for (const c of [...candidates].sort((a, b) => b.quality - a.quality)) {
      if (options.length >= 4) break;
      if (c.avgFps >= fastest.avgFps * 0.55) push(c, 'prettiest');
    }
    push(fastest, 'fastest');
  } else if (!capLimited) {
    push(prettiest, 'prettiest');
    push(fastest, 'fastest');
  }

  let verdict: string;
  if (!best) {
    const short = ((1 - fastest.avgFps / effectiveTarget) * 100).toFixed(0);
    verdict = `Even at the lightest settings this build reaches about ${fastest.avgFps.toFixed(0)}fps — ${short}% short of ${effectiveTarget}. Either accept a lower frame rate, drop to a lower resolution, or the hardware is the answer rather than the settings.`;
  } else if (capLimited) {
    verdict = `${describe(best.config)}. ${game.name} is locked to ${cap}fps by its engine and this build holds that at the highest settings it will accept — there is nothing to tune here.`;
  } else if (best.config.preset === 'highest' && best.config.upscaling.tech === 'none' && best.headroom > 0.25) {
    verdict = `Comfortable. Maximum settings, native resolution, and still ${(best.headroom * 100).toFixed(0)}% of headroom over ${effectiveTarget}fps — you could drive a faster panel or a higher resolution with this.`;
  } else if (best.headroom < 0.08) {
    verdict = `${describe(best.config)} clears ${effectiveTarget}fps, but only just (${best.avgFps.toFixed(0)}fps). Expect to fall below the target in the heaviest scenes; one step down would make it consistent.`;
  } else {
    verdict = `Set it to ${describe(best.config)} — about ${best.avgFps.toFixed(0)}fps, ${(best.headroom * 100).toFixed(0)}% clear of your ${effectiveTarget}fps target.`;
  }

  if (capLimited) {
    notes.push(`Held at the engine's ${cap}fps cap, so the frame rate figure says nothing about headroom. A faster GPU would change nothing in this title.`);
  }
  if (best && best.config.upscaling.tech !== 'none') {
    const scale = UPSCALE_RENDER_SCALE[best.config.upscaling.quality];
    notes.push(
      `${best.config.upscaling.tech.toUpperCase()} ${best.config.upscaling.quality} renders at ${(scale * 100).toFixed(0)}% resolution and reconstructs the rest. At ${resolution} this is usually hard to fault; below Quality mode it becomes visible in fine detail and motion.`,
    );
  }
  if (best && best.config.rtTier === 'on') {
    notes.push('Ray tracing is on in this pick. It is the single most expensive setting here — turning it off is worth roughly two preset steps of frame rate.');
  }
  const presetSteps = best && !capLimited ? Math.abs(presetEffect(best.config.preset, game.archetype).steps) : 0;
  if (presetSteps > 0) {
    notes.push(
      `Preset scaling is the least-evidenced part of this model: the multipliers are priors, not fitted values, because the fixture corpus contains no controlled preset comparison. Treat the frame rate as directionally right and the exact figure as soft.`,
    );
  }

  // Frame generation, deliberately outside the target logic.
  let frameGen: SettingsRecommendation['frameGen'];
  const fgTech = tech;
  const fgSupported =
    fgTech === 'dlss' ? gpu.architecture === 'Ada Lovelace' || gpu.architecture === 'Blackwell'
    : fgTech === 'fsr' ? true
    : false;
  if (best && fgSupported && fgTech && !capLimited) {
    const fgConfig: SettingsConfig = {
      ...best.config,
      upscaling: { ...best.config.upscaling, tech: fgTech, frameGen: true },
    };
    const est = estimate(build, gameId, resolution, data, {
      preset: fgConfig.preset,
      upscaling: fgConfig.upscaling,
      rtTier: fgConfig.rtTier,
      airflowTier: opts.airflowTier,
    });
    if (est.status === 'ok' && est.presentedFps != null) {
      const lat = estimateLatency(est.avgFps ?? best.avgFps, {
        frameGen: true,
        refreshHz: build.target.refreshHz,
      });
      frameGen = {
        candidate: {
          config: fgConfig,
          avgFps: est.avgFps ?? best.avgFps,
          low1PctFps: est.low1PctFps,
          quality: qualityScore(fgConfig),
          meetsTarget: false,
          headroom: 0,
          estimate: est,
          label: `${describe(fgConfig)} + frame generation`,
        },
        presentedFps: est.presentedFps,
        latencyMs: lat.totalMs,
        verdict: latencyVerdict(lat.totalMs).label,
        caution:
          `Frame generation would show ${est.presentedFps.toFixed(0)}fps on the counter from ${(est.avgFps ?? best.avgFps).toFixed(0)} real frames. It does NOT make the game more responsive — latency goes UP to about ${lat.totalMs.toFixed(0)}ms, because a generated frame is interpolated between two real ones. It is worth having for smoothness once you are already at a playable frame rate, and worth avoiding below roughly 60fps real, where the added latency is felt before the smoothness is.`,
      };
    }
  }

  return { gameId, gameName: game.name, resolution, targetFps, best, fastest, prettiest, capLimited, options, verdict, notes, frameGen };
}

export interface LibraryRecommendation {
  perGame: SettingsRecommendation[];
  /** Games that clear the target at the reference preset or better. */
  comfortable: number;
  /** Games needing compromises below the reference preset. */
  compromised: number;
  /** Games that cannot reach the target at all. */
  unreachable: number;
  summary: string;
}

/** Run the advisor across a whole library and summarise the result. */
export function recommendForLibrary(
  build: Build,
  gameIds: string[],
  resolution: Resolution,
  targetFps: number,
  data: EngineData,
  opts: { airflowTier?: EstimateOptions['airflowTier'] } = {},
): LibraryRecommendation {
  const perGame = gameIds
    .map((id) => settingsForTarget(build, id, resolution, targetFps, data, opts))
    .filter((r): r is SettingsRecommendation => r !== null);

  const refIndex = PRESETS.indexOf('high');
  let comfortable = 0;
  let compromised = 0;
  let unreachable = 0;
  for (const r of perGame) {
    if (!r.best) unreachable++;
    else if (PRESETS.indexOf(r.best.config.preset) >= refIndex && r.best.config.upscaling.tech === 'none') comfortable++;
    else compromised++;
  }

  const n = perGame.length;
  const summary =
    n === 0
      ? 'No games selected.'
      : unreachable === 0 && compromised === 0
        ? `Every one of your ${n} games clears ${targetFps}fps at ${resolution} on high or better, natively. This build is comfortably ahead of what you are asking of it.`
        : unreachable === 0
          ? `All ${n} games reach ${targetFps}fps at ${resolution}, ${comfortable} of them at high or better natively and ${compromised} with settings or upscaling turned down.`
          : `${n - unreachable} of your ${n} games reach ${targetFps}fps at ${resolution}. ${unreachable} cannot at any setting — those are the ones to check before you buy.`;

  return { perGame, comfortable, compromised, unreachable, summary };
}
