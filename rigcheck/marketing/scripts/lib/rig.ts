/**
 * The "check my rig" post format.
 *
 * The most engaging thing this engine can do on social is not rank hardware in
 * the abstract — it is take the machine somebody actually owns and show them,
 * per game, what the weaker half is costing the stronger half. That is
 * personal, it is specific, and it is the one question every comment section
 * is already asking in worse words ("is my CPU bottlenecking my GPU?").
 *
 * It is also a format that never runs out: every follower who comments a spec
 * is another post. The other card formats are a fixed rotation of subjects we
 * choose; this one is an inbox.
 *
 * Everything here is a thin shaping layer over src/core/pairing.ts, so the
 * post and the `npm run analyse` table cannot disagree.
 */
import { pairingReport, type PairingReport, type PairingRow } from '../../../src/core/pairing.ts';
import type { RamConfig, Resolution } from '../../../src/core/types.ts';
import type { buildEngineData } from '../../../src/core/catalogue.ts';

type EngineData = ReturnType<typeof buildEngineData>;

export interface RigRow {
  game: string;
  /** What the machine as it stands gets. */
  before: number;
  /** The same card on a current platform. */
  after: number;
  gainPct: number;
  limiter: string | undefined;
}

export interface RigData {
  cpu: string;
  gpu: string;
  cpuFull: string;
  gpuFull: string;
  resolution: Resolution;
  ram: RamConfig;
  ceiling: string;
  ceilingRam: RamConfig;
  /** Sorted worst-first: the biggest loss leads, because it is the argument. */
  rows: RigRow[];
  /** Games that will not launch on this card at all, with the reason. */
  blocked: { game: string; reason: string }[];
  verdict: PairingReport['verdict'];
  medianGainPct: number;
  cpuBound: number;
  ran: number;
  power: { totalW: number; psuW: number } | null;
  /**
   * The headline: which part to spend on. Derived from the limiter count, not
   * asserted — a card that is the limit in most games is the part worth
   * replacing, and a processor that is the limit in most games means a faster
   * card changes nothing.
   */
  spendOn: 'cpu' | 'gpu' | 'either';
}

/** Games worth putting on a card: the ones that ran, worst loss first. */
const usable = (rows: PairingRow[]) => rows.filter((r) => r.fps != null && r.headroomPct != null);

export function rigData(
  cpuId: string, gpuId: string, data: EngineData,
  opts: { resolution?: Resolution; games?: string[]; ram?: RamConfig } = {},
): RigData {
  const rep = pairingReport(cpuId, gpuId, data, opts);
  if (!rep.ceiling) throw new Error('no current-platform reference in the catalogue to measure against');
  const rows: RigRow[] = usable(rep.rows)
    .map((r) => ({ game: r.game, before: r.fps!, after: r.ceilingFps!, gainPct: r.headroomPct!, limiter: r.limiter }))
    .sort((a, b) => b.gainPct - a.gainPct);
  return {
    cpu: rep.cpu.short, gpu: rep.gpu.short, cpuFull: rep.cpu.name, gpuFull: rep.gpu.name,
    resolution: rep.resolution, ram: rep.ram,
    ceiling: rep.ceiling.short, ceilingRam: rep.ceiling.ram,
    rows,
    blocked: rep.rows.filter((r) => r.status === 'WILL_NOT_RUN')
      .map((r) => ({ game: r.game, reason: r.blockedDetail ?? r.blockedBy.join(', ') })),
    verdict: rep.verdict,
    medianGainPct: rep.medianHeadroomPct ?? 0,
    cpuBound: rep.counts.cpuBound, ran: rep.counts.ok,
    power: rep.power,
    spendOn: rep.verdict === 'cpu-wall' ? 'cpu' : rep.verdict === 'gpu-wall' ? 'gpu' : 'either',
  };
}

/**
 * The single sentence the post is built around.
 *
 * Written from the numbers rather than chosen from a list of phrasings, so it
 * stays true when the pairing changes: a balanced machine gets a "nothing is
 * obviously wrong" line rather than a manufactured problem.
 */
export function rigVerdict(r: RigData): string {
  const worst = r.rows[0];
  if (r.spendOn === 'cpu') {
    return `The ${r.cpu} is the limit in ${r.cpuBound} of ${r.ran} games. A faster card changes almost nothing until it is replaced.`;
  }
  if (r.spendOn === 'gpu') {
    return `The ${r.gpu} is the limit in most of these games — which is the good case, because the card is the easy part to replace.`;
  }
  return worst
    ? `Neither part dominates: the ${r.cpu} leads in some games and the ${r.gpu} in others.`
    : `Nothing here runs, so there is nothing to compare.`;
}
