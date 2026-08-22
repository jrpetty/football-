/**
 * Data the person using this adds themselves.
 *
 * ## Why this exists
 *
 * Almost every number in this catalogue is a model's recollection. The
 * provenance banner says so, the Data Explorer shows it field by field, and the
 * validation run puts a figure on how far that gets you. What none of that did
 * was offer a way OUT of it.
 *
 * The routes to better data existed but were all outside the app. Prices come
 * from `npm run import:prices`, measurements from `npm run import:manual` —
 * both of which need a terminal, a checkout and Node. The thing people actually
 * have is a single HTML file. So the only person who could improve the data was
 * someone already set up to develop the tool, and the numbers stayed recalled.
 *
 * Two gaps made that concrete:
 *
 *  - **64 of 279 graphics cards carry a price, and 55 of 442 processors.** The
 *    build planner can only recommend from what it can cost, so three quarters
 *    of the catalogue was invisible to it — silently, with no note saying so.
 *  - **The measured corpus is empty.** Peer comparison — finding other machines
 *    with the same two parts running the same game — is written, tested and
 *    wired into the health report, and has never once had a record to compare
 *    against.
 *
 * Everything here is the lane that fixes both from inside the page: a price
 * somebody actually paid, a frame rate they actually captured, kept locally and
 * exportable as a file that drops straight into `data/`.
 *
 * ## What it is careful about
 *
 * An imported bundle is untrusted input. It may be hand-edited, half-written by
 * a script, or from a newer version of this app. Validation is therefore
 * per-row and non-throwing: a bad row is rejected and named, and the rest of
 * the file still lands. An import that throws away nine good rows because the
 * tenth had a typo is an import nobody uses twice.
 *
 * Contributed data is also never quietly promoted. A user price is an
 * `operator-override` — the strongest tier, because somebody stating what they
 * can buy a part for is better evidence than anything recalled — and a user
 * measurement enters the corpus carrying its own provenance id, so the peer
 * panel can say "this is your own capture" rather than implying a population.
 */

import type { PerfRecord, RamConfig, Resolution } from './types.ts';

/** Bumped when the bundle shape changes in a way an older reader cannot handle. */
export const USERDATA_VERSION = 1;

const RESOLUTIONS: Resolution[] = ['1080p', '1440p', '2160p', '3440x1440'];

/** A price somebody actually saw, for one part in one condition. */
export interface UserPrice {
  partId: string;
  condition: 'new' | 'used';
  /** In the catalogue's currency, which is GBP throughout. */
  gbp: number;
  /** ISO date. Prices go off; the age is shown rather than hidden. */
  at: string;
  /** Where it was seen — "eBay sold", a retailer, "local listing". */
  where?: string;
  note?: string;
}

/** A frame rate somebody actually captured, on a machine they can describe. */
export interface UserMeasurement {
  id: string;
  cpuId: string;
  gpuId: string;
  gameId: string;
  resolution: Resolution;
  preset?: string;
  avgFps: number;
  low1PctFps?: number;
  ram?: RamConfig;
  at: string;
  note?: string;
}

export interface UserData {
  version: number;
  prices: UserPrice[];
  measurements: UserMeasurement[];
}

export function emptyUserData(): UserData {
  return { version: USERDATA_VERSION, prices: [], measurements: [] };
}

/* ------------------------------------------------------------ validation -- */

const isRec = (v: unknown): v is Record<string, unknown> =>
  typeof v === 'object' && v !== null && !Array.isArray(v);

const num = (v: unknown): number | null =>
  typeof v === 'number' && Number.isFinite(v) ? v : null;

const str = (v: unknown): string | null => (typeof v === 'string' && v.trim() ? v.trim() : null);

/** An ISO-ish date that actually parses. Anything else is not a date. */
const date = (v: unknown): string | null => {
  const s = str(v);
  if (!s) return null;
  const t = Date.parse(s);
  return Number.isNaN(t) ? null : s;
};

export interface ImportResult {
  data: UserData;
  /** One line per row that could not be read, saying which and why. */
  rejected: string[];
  /**
   * Rows that WERE imported but look wrong.
   *
   * Separate from `rejected` because the two are different judgements and
   * collapsing them loses data. A price of 32,000 pounds is probably 320 pounds
   * typed in pence — but a workstation card really can cost thousands, and this
   * has no way to tell those apart from the number alone. Refusing the row
   * would silently discard a real price; importing it silently would let a
   * hundredfold error rewrite every budget recommendation. So it goes in, and
   * it gets named.
   */
  warnings: string[];
  /** True when the file was structurally unreadable and nothing was imported. */
  unreadable: boolean;
}

/**
 * Above this, the figure is not a price at all.
 *
 * Set high on purpose. The tempting bound is somewhere near the most expensive
 * real component, but that is exactly where a hundredfold entry error lands: a
 * 320-pound card typed in pence is 32,000, which a tight bound would reject as
 * impossible when it is the very case worth showing somebody. Rejection is
 * reserved for arithmetic nonsense, and the judgement call is handed over
 * rather than made silently.
 */
const PRICE_IMPOSSIBLE_GBP = 100000;
/** Above this, plausible but worth a second look before it moves a budget. */
const PRICE_SUSPICIOUS_GBP = 5000;

/**
 * Read a bundle that may be anything at all.
 *
 * `known` lets the caller supply the catalogue ids in play so a row naming a
 * part this build does not have is rejected with a useful message rather than
 * imported and then silently ignored by every screen. It is optional: a bundle
 * can be validated for shape without a catalogue to hand.
 */
export function readUserData(
  raw: unknown,
  known?: { parts?: Set<string>; games?: Set<string> },
): ImportResult {
  const rejected: string[] = [];
  const warnings: string[] = [];
  if (!isRec(raw)) {
    return { data: emptyUserData(), rejected: ['The file is not an object.'], warnings: [], unreadable: true };
  }

  const version = num(raw.version);
  if (version != null && version > USERDATA_VERSION) {
    rejected.push(
      `The file says version ${version} and this build reads version ${USERDATA_VERSION}. Rows it does not ` +
        `recognise have been skipped; anything it does understand has still been imported.`,
    );
  }

  const prices: UserPrice[] = [];
  const rawPrices = Array.isArray(raw.prices) ? raw.prices : [];
  rawPrices.forEach((row, i) => {
    if (!isRec(row)) return void rejected.push(`Price ${i + 1}: not an object.`);
    const partId = str(row.partId);
    const gbp = num(row.gbp);
    const condition = row.condition === 'new' || row.condition === 'used' ? row.condition : null;
    if (!partId) return void rejected.push(`Price ${i + 1}: no partId.`);
    if (!condition) return void rejected.push(`Price ${i + 1} (${partId}): condition must be "new" or "used".`);
    // Zero is not a price and a negative one is not either.
    if (gbp == null || gbp <= 0 || gbp > PRICE_IMPOSSIBLE_GBP) {
      return void rejected.push(`Price ${i + 1} (${partId}): "${String(row.gbp)}" is not a plausible price in pounds.`);
    }
    if (gbp > PRICE_SUSPICIOUS_GBP) {
      warnings.push(
        `Price ${i + 1} (${partId}): ${gbp} pounds is high for a single component. If that was meant to be ` +
          `${(gbp / 100).toFixed(2)} it has been imported a hundred times too large — check it before it ` +
          `moves anything on the budget screens.`,
      );
    }
    if (known?.parts && !known.parts.has(partId)) {
      return void rejected.push(`Price ${i + 1}: "${partId}" is not a part in this catalogue.`);
    }
    prices.push({
      partId,
      condition,
      gbp,
      at: date(row.at) ?? new Date(0).toISOString(),
      where: str(row.where) ?? undefined,
      note: str(row.note) ?? undefined,
    });
  });

  const measurements: UserMeasurement[] = [];
  const rawMeas = Array.isArray(raw.measurements) ? raw.measurements : [];
  rawMeas.forEach((row, i) => {
    if (!isRec(row)) return void rejected.push(`Measurement ${i + 1}: not an object.`);
    const cpuId = str(row.cpuId);
    const gpuId = str(row.gpuId);
    const gameId = str(row.gameId);
    const avgFps = num(row.avgFps);
    const resolution = RESOLUTIONS.find((r) => r === row.resolution) ?? null;
    const where = `Measurement ${i + 1}${gameId ? ` (${gameId})` : ''}`;
    if (!cpuId || !gpuId || !gameId) return void rejected.push(`${where}: needs cpuId, gpuId and gameId.`);
    if (!resolution) return void rejected.push(`${where}: resolution must be one of ${RESOLUTIONS.join(', ')}.`);
    // 1000fps is past anything a real capture produces at these resolutions,
    // and a figure that high is a units mistake rather than a fast machine.
    if (avgFps == null || avgFps <= 0 || avgFps > 1000) {
      return void rejected.push(`${where}: "${String(row.avgFps)}" is not a plausible frame rate.`);
    }
    if (known?.parts && (!known.parts.has(cpuId) || !known.parts.has(gpuId))) {
      return void rejected.push(`${where}: names a part this catalogue does not have.`);
    }
    if (known?.games && !known.games.has(gameId)) {
      return void rejected.push(`${where}: "${gameId}" is not a game in this catalogue.`);
    }
    const low = num(row.low1PctFps);
    measurements.push({
      id: str(row.id) ?? `um-${cpuId}-${gpuId}-${gameId}-${resolution}-${i}`,
      cpuId,
      gpuId,
      gameId,
      resolution,
      preset: str(row.preset) ?? undefined,
      avgFps,
      // A 1% low above the average is arithmetically impossible, so it is
      // dropped rather than imported — one transposed pair of fields would
      // otherwise put a nonsense figure in front of somebody as measured data.
      low1PctFps: low != null && low > 0 && low <= avgFps ? low : undefined,
      ram: isRec(row.ram) ? (row.ram as unknown as RamConfig) : undefined,
      at: date(row.at) ?? new Date(0).toISOString(),
      note: str(row.note) ?? undefined,
    });
    if (low != null && low > avgFps) {
      rejected.push(`${where}: the 1% low (${low}) was above the average (${avgFps}), so it was left out.`);
    }
  });

  return { data: { version: USERDATA_VERSION, prices, measurements }, rejected, warnings, unreadable: false };
}

/* --------------------------------------------------------------- merging -- */

/** One price per part per condition — the same part twice is an update. */
const priceKey = (p: UserPrice) => `${p.partId}::${p.condition}`;

/**
 * Two bundles into one, newest wins.
 *
 * Prices collapse by part and condition because a second price for the same
 * part is a correction, not a second data point — nobody wants their own
 * six-month-old figure averaged into today's. Measurements do NOT collapse that
 * way: two captures of the same game on the same machine are two runs, and
 * having both is what makes a spread visible. They deduplicate on id alone.
 */
export function mergeUserData(base: UserData, incoming: UserData): UserData {
  const prices = new Map(base.prices.map((p) => [priceKey(p), p]));
  for (const p of incoming.prices) {
    const existing = prices.get(priceKey(p));
    if (!existing || Date.parse(p.at) >= Date.parse(existing.at)) prices.set(priceKey(p), p);
  }
  const measurements = new Map(base.measurements.map((m) => [m.id, m]));
  for (const m of incoming.measurements) measurements.set(m.id, m);
  return {
    version: USERDATA_VERSION,
    prices: [...prices.values()].sort((a, b) => Date.parse(b.at) - Date.parse(a.at)),
    measurements: [...measurements.values()].sort((a, b) => Date.parse(b.at) - Date.parse(a.at)),
  };
}

/* -------------------------------------------------------------- overlays -- */

/**
 * User prices as the flat tables the pricing layer already speaks.
 *
 * Split by condition rather than flattened into one map. The pricing layer's
 * override tier was a single `Record<string, number>` applied to both
 * conditions at once, which would take a used-market figure somebody entered
 * and answer "what does this cost new?" with it.
 */
export function priceOverlay(data: UserData): { new: Record<string, number>; used: Record<string, number> } {
  const out = { new: {} as Record<string, number>, used: {} as Record<string, number> };
  for (const p of data.prices) out[p.condition][p.partId] = p.gbp;
  return out;
}

/**
 * User measurements as records the peer comparison can read.
 *
 * `weight` is 1 and the provenance id says whose they are. This is the one
 * place a captured frame rate becomes indistinguishable in TYPE from the
 * bundled corpus, so it deliberately stays distinguishable in CONTENT: the
 * source note names the capture, and the peer panel already says out loud when
 * a comparison rests on one machine rather than a population.
 */
export function toPerfRecords(data: UserData): PerfRecord[] {
  return data.measurements.map((m) => ({
    id: m.id,
    cpuId: m.cpuId,
    gpuId: m.gpuId,
    gameId: m.gameId,
    resolution: m.resolution,
    avgFps: m.avgFps,
    low1Pct: m.low1PctFps,
    ram: m.ram,
    fingerprint: { preset: m.preset ?? 'high' },
    sourceNote: `Captured by you on ${m.at.slice(0, 10)}${m.note ? ` — ${m.note}` : ''}`,
    provenanceId: 'user-capture',
    weight: 1,
  })) as PerfRecord[];
}

/* -------------------------------------------------------------- coverage -- */

export interface Coverage {
  total: number;
  covered: number;
  share: number;
  /** How many of the covered ones came from the user rather than the seed. */
  fromUser: number;
}

/**
 * How much of a catalogue a price table actually reaches.
 *
 * Worth computing because the number is bad and nothing said so. A planner
 * working from a quarter of the catalogue is not choosing the best part for a
 * budget — it is choosing the best part it happens to have a price for, and
 * those are two different claims.
 */
export function priceCoverageOf(
  ids: Iterable<string>,
  seeded: Iterable<string>,
  user: UserData,
): Coverage {
  const all = [...ids];
  const have = new Set(seeded);
  const mine = new Set(user.prices.map((p) => p.partId));
  const covered = all.filter((id) => have.has(id) || mine.has(id));
  return {
    total: all.length,
    covered: covered.length,
    share: all.length ? covered.length / all.length : 0,
    fromUser: covered.filter((id) => mine.has(id) && !have.has(id)).length,
  };
}

/* ---------------------------------------------------------------- export -- */

/**
 * The bundle, as a file.
 *
 * Shaped to be droppable: the same keys the import lane reads, sorted, with a
 * generation stamp. Somebody who wants their prices in the repository can put
 * this in `data/` and run the existing scripts against it; somebody who just
 * wants a backup has one.
 */
export function exportBundle(data: UserData, now: string): string {
  return JSON.stringify(
    {
      version: USERDATA_VERSION,
      generatedAt: now,
      note:
        'Contributed from the RIGCHECK page. Prices are in GBP. Import this back through the Data Explorer, ' +
        'or keep it as a backup — nothing here left your browser unless you moved this file yourself.',
      prices: [...data.prices].sort((a, b) => a.partId.localeCompare(b.partId)),
      measurements: [...data.measurements].sort((a, b) => a.gameId.localeCompare(b.gameId)),
    },
    null,
    2,
  );
}
