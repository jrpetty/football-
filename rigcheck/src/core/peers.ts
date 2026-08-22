/**
 * Peer comparison: how does this machine compare with others running the same
 * parts?
 *
 * The honest version of this feature is mostly about what it refuses to do.
 * There is no server behind this tool and no telemetry, so the only peers that
 * exist are measurements somebody put here deliberately: the bundled
 * `data/measured/` corpus, which ships empty, plus whatever the person using it
 * has captured themselves. A screen that rendered "compared against 0 peers" as
 * a comparison, or that quietly substituted the model's own prediction and
 * called it a peer, would be inventing a social proof that does not exist. Both
 * are worse than saying there is nothing to compare against.
 *
 * For most of this project's life the bundled corpus was the only route in, and
 * it needed a checkout and a CLI — so on the single HTML file everyone actually
 * uses, this returned the `none` tier every time it was called, for every
 * machine, without exception. Captures added from inside the page now feed the
 * same search, which is what makes the first tier reachable at all.
 *
 * So there are three tiers and they are never conflated:
 *
 *   REAL PEERS   other people's measurements of the same CPU and GPU. Evidence.
 *   OWN HISTORY  your own earlier measurements of this machine. Also evidence,
 *                but n=1 machine — it tells you about drift, not about whether
 *                your machine is normal.
 *   NONE         neither exists. Said plainly, with what would create some.
 */
import type { PerfRecord, Resolution } from './types.ts';

export interface PeerSample {
  avgFps: number;
  low1PctFps?: number;
  sourceNote: string;
  /** Import weight — reduced for records with an incomplete settings fingerprint. */
  weight: number;
  /** True when the peer's settings match exactly rather than approximately. */
  exactSettings: boolean;
}

export type PeerTier = 'real-peers' | 'own-history' | 'none';

export interface PeerComparison {
  tier: PeerTier;
  gameId: string;
  resolution: Resolution;
  samples: PeerSample[];
  /** Median of the peer samples. Null when there are none. */
  peerMedian: number | null;
  /** Where this machine sits among them, 0-1. Null when there are none. */
  percentile: number | null;
  detail: string;
  /** What the operator could do to make this comparison possible or better. */
  nextStep?: string;
}

function median(xs: number[]): number | null {
  if (!xs.length) return null;
  const s = [...xs].sort((a, b) => a - b);
  const i = Math.floor(s.length / 2);
  return s.length % 2 ? s[i] : (s[i - 1] + s[i]) / 2;
}

/**
 * Find measurements of the same hardware running the same game.
 *
 * Matching is on CPU, GPU, game and resolution, with the settings fingerprint
 * used to grade rather than to exclude: a peer at a different preset is still
 * informative about the hardware, it is just weaker evidence, and dropping it
 * entirely would usually leave nothing at all. The distinction is carried
 * through to the caller rather than averaged away.
 */
export function findPeers(
  records: PerfRecord[],
  opts: { cpuId: string; gpuId: string; gameId: string; resolution: Resolution; preset?: string },
): PeerSample[] {
  return records
    .filter(
      (r) => r.cpuId === opts.cpuId && r.gpuId === opts.gpuId && r.gameId === opts.gameId && r.resolution === opts.resolution,
    )
    .map((r) => ({
      avgFps: r.avgFps,
      low1PctFps: r.low1Pct,
      sourceNote: r.sourceNote,
      weight: r.weight,
      exactSettings:
        (r.fingerprint?.preset ?? '').toLowerCase() === (opts.preset ?? 'high').toLowerCase(),
    }));
}

export function comparePeers(
  mine: { avgFps: number; gameId: string; resolution: Resolution; preset?: string },
  peers: PeerSample[],
  ownHistory: number[],
): PeerComparison {
  const base = { gameId: mine.gameId, resolution: mine.resolution };

  if (peers.length) {
    const exact = peers.filter((p) => p.exactSettings);
    const use = exact.length ? exact : peers;
    const med = median(use.map((p) => p.avgFps))!;
    const below = use.filter((p) => p.avgFps < mine.avgFps).length;
    const percentile = use.length > 1 ? below / use.length : null;
    const pct = ((mine.avgFps / med - 1) * 100).toFixed(0);
    return {
      ...base,
      tier: 'real-peers',
      samples: use,
      peerMedian: med,
      percentile,
      detail:
        `${use.length} other measurement${use.length === 1 ? '' : 's'} of this exact CPU and GPU pairing ${use.length === 1 ? 'exists' : 'exist'} in the imported corpus, at a median of ${med.toFixed(0)}fps. You are at ${mine.avgFps.toFixed(0)}fps — ${Number(pct) >= 0 ? `${pct}% above` : `${Math.abs(Number(pct))}% below`} them.` +
        (exact.length === 0
          ? ' None of them used the same preset as you, so this compares the hardware rather than the configuration and is weaker for it.'
          : '') +
        (use.length < 3 ? ' With this few peers, a difference of less than about 15% means very little.' : ''),
      nextStep: use.length < 5 ? 'More measurements of this pairing would make this comparison worth something. Add one under "add your own data" in the Data Explorer, or import a batch into data/manual/.' : undefined,
    };
  }

  if (ownHistory.length) {
    const med = median(ownHistory)!;
    const pct = ((mine.avgFps / med - 1) * 100).toFixed(0);
    return {
      ...base,
      tier: 'own-history',
      samples: [],
      peerMedian: med,
      percentile: null,
      detail:
        `No measurement of this CPU and GPU pairing by anyone else has been imported, so there are no peers to compare against. What there is: ${ownHistory.length} earlier measurement${ownHistory.length === 1 ? '' : 's'} of THIS machine at these settings, median ${med.toFixed(0)}fps, against your current ${mine.avgFps.toFixed(0)} — ${Number(pct) >= 0 ? `${pct}% above` : `${Math.abs(Number(pct))}% below`}. That tells you whether the machine has drifted. It cannot tell you whether it is normal for the hardware.`,
      nextStep: 'Peer comparison needs measurements from other machines. Add them under "add your own data" in the Data Explorer, or import a batch into data/manual/, and they become peers.',
    };
  }

  return {
    ...base,
    tier: 'none',
    samples: [],
    peerMedian: null,
    percentile: null,
    detail:
      'There is nothing to compare against. This tool has no server and collects no telemetry, so the only peers that can exist are measurements someone has imported — and none match this hardware and game. The frame-rate expectation elsewhere in this report comes from the model, which is a different thing from other people\'s machines and is not presented as one.',
    nextStep:
      'Capture a frame rate on this machine and add it under "add your own data" in the Data Explorer, and it becomes the first peer. A second machine with the same parts makes the comparison real.',
  };
}
