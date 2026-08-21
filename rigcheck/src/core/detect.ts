/**
 * Hardware detection: free text in, catalogue ids out.
 *
 * Removes the main friction in using the tool — nobody wants to hand-pick two
 * parts from a 707-record catalogue when the machine can say what it is. Accepts
 * whatever the operator can get: a dxdiag dump, `systeminfo`, the JSON written
 * by harness/detect-hardware.ps1, a retailer's spec blurb, or a line typed from
 * memory.
 *
 * Deliberately returns CANDIDATES with confidence rather than a single answer.
 * Silently picking the wrong 1060 variant would corrupt every number downstream,
 * so an ambiguous match is surfaced for the operator to resolve.
 */
import type { EngineData } from './engine.ts';
import { RESOLUTIONS } from './types.ts';
import type { Build, CpuRecord, GpuRecord, MemoryType, RamConfig, Resolution, Storage } from './types.ts';

export interface DetectedPart<T> {
  record: T;
  confidence: number;
  matchedOn: string;
}

export interface DetectionResult {
  cpu: DetectedPart<CpuRecord>[];
  gpu: DetectedPart<GpuRecord>[];
  ram?: Partial<RamConfig>;
  storage?: Storage;
  resolution?: Resolution;
  refreshHz?: number;
  driverVersion?: string;
  /** Anything recognised as hardware but not matched to a catalogue record. */
  unmatched: string[];
  /** Things the operator should check, in plain language. */
  warnings: string[];
}

function normalise(s: string): string {
  return s.toLowerCase().replace(/\(r\)|\(tm\)|®|™/g, ' ').replace(/[^a-z0-9. ]+/g, ' ').replace(/\s+/g, ' ').trim();
}

/**
 * Score a catalogue name against a detected string.
 *
 * Model numbers carry nearly all the signal ("3060", "5800x3d"), so they are
 * weighted heavily and their absence is disqualifying — otherwise "NVIDIA
 * GeForce" alone would match a hundred cards equally well.
 */
function scoreName(detected: string, candidate: string): number {
  const d = normalise(detected);
  const c = normalise(candidate);
  if (!d || !c) return 0;
  if (d === c) return 100;

  const modelTokens = (s: string): string[] => s.match(/\b[a-z]*\d{3,5}[a-z0-9]*\b/g) ?? [];
  const dModels = modelTokens(d);
  const cModels = modelTokens(c);
  if (!dModels.length || !cModels.length) return 0;

  const exactModel = dModels.some((m) => cModels.includes(m));
  if (!exactModel) return 0;

  let score = 55;
  // Suffixes are what separate a 4080 from a 4080 Super, or a 5800X from a 5800X3D.
  for (const suffix of ['ti', 'super', 'xt', 'xtx', 'x3d', 'ks', 'kf', 'k', 'f', 'g', 'ge', 'gre', 'le']) {
    const inD = new RegExp(`\\b\\w*${suffix}\\b`).test(d);
    const inC = new RegExp(`\\b\\w*${suffix}\\b`).test(c);
    if (inD === inC) score += 4;
    else score -= 12;
  }
  // Capacity disambiguates variants that are otherwise identical strings.
  const capD = /(\d+)\s*gb/.exec(d);
  const capC = /(\d+)\s*gb/.exec(c);
  if (capD && capC) score += capD[1] === capC[1] ? 18 : -25;

  const brandWords = ['geforce', 'radeon', 'arc', 'ryzen', 'core', 'athlon', 'pentium', 'celeron', 'threadripper'];
  for (const w of brandWords) if (d.includes(w) && c.includes(w)) score += 3;

  return Math.max(0, Math.min(99, score));
}

// Family names match on their own: people write "Ryzen 5 3600 / RTX 3060" as
// often as they paste a full dxdiag block, and requiring a vendor prefix meant
// the common shorthand silently found nothing.
const CPU_LINE =
  /((?:ryzen|core\s*(?:ultra\s*)?i?\d|core\s*ultra|athlon|pentium|celeron|threadripper|fx)[\s-]*[^\n,;(/|]*)/i;
const GPU_LINE =
  /((?:geforce|radeon|intel\s+arc|arc\s+[ab]\d|rtx|gtx|rx)\s*[^\n,;(/|]*)/i;

/**
 * Finding the SYSTEM memory in free text.
 *
 * Three different things in a spec line are written as a number and "GB", and
 * only one of them is the answer:
 *
 *     Ryzen 7 5800X, RTX 3070 8GB, 16GB DDR4, 2x 512GB NVMe SSD
 *                             ^^^  ^^^^          ^^^^^
 *                             VRAM  RAM          storage
 *
 * Two earlier attempts got this wrong in different ways. Scanning the line for
 * the first number that fits took the graphics card's figure whenever the card
 * was written first, which is the usual ordering. Splitting into clauses and
 * preferring the one that mentions memory then failed on its own terms: a card
 * clause reading "GT 1030 2GB DDR5" does mention DDR5, so it was promoted to
 * the top; "2x 512GB SSD" looks exactly like a memory kit and reported 1024GB
 * of RAM; and joining clauses back together to search them defeated a guard
 * that relied on line breaks.
 *
 * So candidates are SCORED by their surroundings rather than filtered by the
 * clause they fell in. Every figure is a candidate; being near "DDR4" or "RAM"
 * argues for it, being near "RTX" or "SSD" argues against, separators block a
 * marker from reaching across into the next part of the sentence, and the best
 * positive score wins. Nothing positive means the text does not state the
 * system memory, in which case nothing is returned rather than a figure
 * borrowed from the graphics card.
 */

/** How far a marker's influence reaches, in characters. */
const NEAR = 24;

const MARK = {
  ram: /\b(?:ddr[2345]|lpddr[345]x?|ram|memory|dimm|sodimm|installed physical)\b/gi,
  gpu: /\b(?:rtx|gtx|gt\s*\d{3,4}|geforce|radeon|quadro|vram|graphics|video|gddr\d?x?|iris|arc\s*a\d|rx\s*\d{3,4})\b/gi,
  storage: /\b(?:ssd|nvme|hdd|m\.2|sata|hard\s*drive|storage|drive|gen\d|tb)\b/gi,
  /** A figure introduced as a capability is what the board takes, not what is fitted. */
  capability: /\b(?:supports?|up\s*to|max(?:imum)?|capable|oc|overclock)\b/gi,
};

/** Phrases naming the graphics card's memory outright. These beat proximity. */
const VRAM_PHRASE = /\b(?:graphics|video|dedicated)\s+memory\b/gi;

/**
 * A separator ends one part of a spec and begins the next, so a marker on the
 * far side of one is not describing this figure. Without this the DDR4 in
 * "GTX 1660 Super 6GB • 16GB DDR4" reached back across the bullet and claimed
 * the card's 6GB.
 */
const BARRIER = /[,;/|\n•]| - | \+ /g;

type Span = [number, number];

function marks(text: string, re: RegExp): Span[] {
  const out: Span[] = [];
  re.lastIndex = 0;
  for (let m = re.exec(text); m; m = re.exec(text)) out.push([m.index, m.index + m[0].length]);
  return out;
}

interface RamContext {
  ram: Span[];
  gpu: Span[];
  storage: Span[];
  vram: Span[];
  bars: number[];
}

function crosses(a: number, b: number, bars: number[]): boolean {
  const lo = Math.min(a, b);
  const hi = Math.max(a, b);
  return bars.some((x) => x > lo && x < hi);
}

/**
 * How strongly the surroundings argue that a bare capacity is system memory.
 *
 * A marker belongs to the clause it STARTS in, so the barrier test uses its
 * start offset — testing its near edge instead put a marker ending exactly at a
 * comma on the same side as the figure after it, which let "GDDR6X," claim the
 * "64GB" that followed.
 */
function memoryScore(at: number, ctx: RamContext): number {
  const dist = (ms: Span[]): number => {
    const ds = ms
      .filter(([a]) => !crosses(at, a, ctx.bars))
      .map(([a, b]) => (at < a ? a - at : at > b ? at - b : 0));
    return ds.length ? Math.min(...ds) : Infinity;
  };
  // A label precedes its value, so "graphics memory" disqualifies only figures
  // after it and before the next separator.
  if (ctx.vram.some(([, b]) => at > b && at - b <= NEAR && !crosses(b, at, ctx.bars))) return -10;

  const dRam = dist(ctx.ram);
  if (dRam === Infinity || dRam > NEAR) return -1;
  const rival = Math.min(dist(ctx.gpu), dist(ctx.storage));
  if (rival === Infinity) return 3;
  // The rival must be clearly closer to take it; otherwise the memory word
  // keeps it, because "16GB DDR4" beside "RTX 3060 12GB" belongs to the DDR4.
  return dRam <= rival ? 3 : rival * 1.6 < dRam ? -5 : 1;
}

/**
 * Score a DDR token, which IS the memory evidence and so needs no memory word
 * beside it. The only question is whether something else has a better claim:
 * "GT 1030 2GB DDR5" states the card's memory generation, not the machine's.
 */
function ddrScore(at: number, ctx: RamContext): number {
  const near = (ms: Span[], within: number) =>
    ms.some(([a, b]) => {
      if (crosses(at, a, ctx.bars)) return false;
      const d = at < a ? a - at : at > b ? at - b : 0;
      return d <= within;
    });
  return near(ctx.gpu, 14) || near(ctx.storage, 14) ? -5 : 3;
}

/** Parse a memory description such as "32GB DDR5-6000 (2x16GB)". */
function parseRam(text: string): Partial<RamConfig> | undefined {
  const ctx: RamContext = {
    ram: marks(text, MARK.ram),
    gpu: marks(text, MARK.gpu),
    storage: marks(text, MARK.storage),
    vram: marks(text, VRAM_PHRASE),
    bars: marks(text, BARRIER).map(([a]) => a),
  };
  const capability = marks(text, MARK.capability);
  const out: Partial<RamConfig> = {};

  // --- capacity ---------------------------------------------------------
  // The kit is resolved first: "2x32GB" states the total outright.
  const kitRe = /(\d{1,2})\s*x\s*(\d{1,3})(?:\.\d+)?\s*gb\b/gi;
  let bestKit: { sticks: number; each: number; score: number } | null = null;
  for (let m = kitRe.exec(text); m; m = kitRe.exec(text)) {
    const sticks = Number(m[1]);
    const each = Number(m[2]);
    // Physical bound: consumer modules stop well short of 128GB and boards at
    // eight slots. "2x 512GB" is a pair of drives, and reading it as memory
    // reported a machine with 1024GB of RAM.
    if (each > 128 || sticks > 8) continue;
    const score = memoryScore(m.index, ctx);
    if (score > 0 && (!bestKit || score > bestKit.score)) bestKit = { sticks, each, score };
  }
  if (bestKit) {
    out.channels = (bestKit.sticks >= 4 ? 4 : bestKit.sticks >= 2 ? 2 : 1) as RamConfig['channels'];
    out.totalGB = bestKit.sticks * bestKit.each;
  }

  if (!out.totalGB) {
    // The decimal is optional but must be CONSUMED: "16.0 GB", which is how
    // Windows prints it, otherwise matches at the "0" and reports 0GB — a
    // figure that then slips past every "no memory recorded" fallback.
    const gbRe = /(\d{1,3})(?:\.\d+)?\s*gb\b/gi;
    let best: { gb: number; score: number } | null = null;
    for (let m = gbRe.exec(text); m; m = gbRe.exec(text)) {
      if (/\d\s*x\s*$/i.test(text.slice(Math.max(0, m.index - 5), m.index))) continue;
      const gb = Number(m[1]);
      if (!gb) continue;
      const score = memoryScore(m.index, ctx);
      if (score > 0 && (!best || score > best.score)) best = { gb, score };
    }
    if (best) out.totalGB = best.gb;
  }

  // --- generation -------------------------------------------------------
  // GDDR cannot match: there is no word boundary between the G and the D.
  const typeRe = /\b(?:lp)?(ddr[345])x?\b/gi;
  let bestType: { t: string; score: number } | null = null;
  for (let m = typeRe.exec(text); m; m = typeRe.exec(text)) {
    const score = ddrScore(m.index, ctx);
    if (score > 0 && (!bestType || score > bestType.score)) bestType = { t: m[1], score };
  }
  if (bestType) out.type = bestType.t.toUpperCase() as MemoryType;

  // --- speed ------------------------------------------------------------
  // Anchored on either side: a bare four-digit number is a part number far more
  // often than a memory speed.
  const speedRe = /\b(?:lp)?ddr[345]x?[-\s]?(\d{4,5})\b|\b(\d{4,5})\s*(?:mhz|mt\/s)\b/gi;
  let bestSpeed: { mts: number; score: number } | null = null;
  for (let m = speedRe.exec(text); m; m = speedRe.exec(text)) {
    const mts = Number(m[1] ?? m[2]);
    if (!(mts >= 1066 && mts <= 9000)) continue;
    // A DDR-prefixed figure is the strongest signal there is; a bare one with a
    // unit still has to earn it from its surroundings.
    let score = m[1] != null ? ddrScore(m.index, ctx) + 2 : memoryScore(m.index, ctx);
    if (capability.some(([a, b]) => m.index > b && m.index - b <= 20 && !crosses(a, m.index, ctx.bars))) {
      score -= 4;
    }
    if (score > 0 && (!bestSpeed || score > bestSpeed.score)) bestSpeed = { mts, score };
  }
  if (bestSpeed) out.speedMTs = bestSpeed.mts;

  return Object.keys(out).length ? out : undefined;
}

/**
 * The harness writes two different JSON shapes and both land here.
 *
 *  - `harness/detect-hardware.ps1` emits a nested document tagged
 *    `rigcheck-hardware/1`: `cpu.name`, `gpu.vramGB`, `memory.totalGB`,
 *    `storage.system.class`, `display.refreshHz`, plus a `catalogueMapping`
 *    block and a `bestEffort` list of everything it had to guess.
 *  - `Get-HardwareProfile` in `harness/lib/rigcheck-common.ps1` emits a flat
 *    PascalCase row — `CpuName`, `RamGB`, `Storage` — because that is the
 *    minimum a data/manual/ CSV needs and the runner prints it inline.
 *
 * Normalising both into one internal shape here is the only place that has to
 * know the difference. Reading just one of them silently produced a build with
 * no CPU, no GPU and default 16GB/3200 RAM, which looks like a successful
 * detection rather than a parse failure.
 */
interface StructuredHardware {
  cpuName: string;
  gpuName: string;
  /** Nested shape only: the flat profile cannot report VRAM (AdapterRAM wraps at 4GB). */
  gpuVramGB?: number;
  /** Only ever the operator-CONFIRMED id. Suggestions are never used automatically. */
  confirmedCpuId?: string;
  confirmedGpuId?: string;
  ram?: Partial<RamConfig>;
  storage?: Storage;
  resolution?: Resolution;
  refreshHz?: number;
  driverVersion?: string;
  /** Everything the detector said it had to guess, verbatim. */
  notes: string[];
  shape: 'nested' | 'flat';
}

const STORAGE_CLASSES = new Set<string>(['hdd', 'sata-ssd', 'nvme-gen3', 'nvme-gen4']);
const MEMORY_TYPES = new Set<string>(['DDR3', 'DDR4', 'DDR5']);

const asObject = (v: unknown): Record<string, unknown> | undefined =>
  v !== null && typeof v === 'object' && !Array.isArray(v) ? (v as Record<string, unknown>) : undefined;
const asString = (v: unknown): string => (v === null || v === undefined ? '' : String(v).trim());
const asPositive = (v: unknown): number | undefined => {
  const n = Number(v);
  return Number.isFinite(n) && n > 0 ? n : undefined;
};

/** Map a pixel width to the four resolutions the catalogue actually has references for. */
function widthToResolution(width: number): Resolution | undefined {
  if (width >= 3840) return '2160p';
  if (width >= 3440) return '3440x1440';
  if (width >= 2560) return '1440p';
  if (width >= 1280) return '1080p';
  return undefined;
}

function readNested(raw: Record<string, unknown>): StructuredHardware {
  const cpu = asObject(raw.cpu) ?? {};
  const gpu = asObject(raw.gpu) ?? {};
  const memory = asObject(raw.memory) ?? {};
  const storage = asObject(raw.storage) ?? {};
  const display = asObject(raw.display) ?? {};
  const mapping = asObject(raw.catalogueMapping) ?? {};

  const notes: string[] = [];
  for (const entry of Array.isArray(raw.bestEffort) ? raw.bestEffort : []) {
    const e = asObject(entry);
    if (!e) continue;
    const field = asString(e.field);
    const why = asString(e.why);
    if (why) notes.push(field ? `${field}: ${why}` : why);
  }

  // The games drive is the one that matters for streaming and load stutter, so
  // it wins over the system drive when the detector found both.
  const games = asObject(storage.games);
  const system = asObject(storage.system);
  const storageClass = [asString(games?.class), asString(system?.class)].find((c) => STORAGE_CLASSES.has(c));

  const ram: Partial<RamConfig> = {};
  const totalGB = asPositive(memory.totalGB);
  if (totalGB) ram.totalGB = totalGB;
  const channels = asPositive(memory.channels);
  if (channels) ram.channels = (channels >= 8 ? 8 : channels >= 4 ? 4 : channels >= 2 ? 2 : 1) as RamConfig['channels'];
  const speed = asPositive(memory.speedMTs);
  if (speed) ram.speedMTs = speed;
  const memType = asString(memory.type).toUpperCase().replace(/^LP/, '');
  if (MEMORY_TYPES.has(memType)) ram.type = memType as MemoryType;
  const timings = asObject(memory.timings);
  if (timings) {
    const cl = asPositive(timings.cl);
    const trcd = asPositive(timings.trcd);
    const trp = asPositive(timings.trp);
    const tras = asPositive(timings.tras);
    if (cl && trcd && trp && tras) ram.timings = { cl, trcd, trp, tras };
  }

  const catalogueRes = asString(display.catalogueResolution);
  const widthPx = asPositive(display.widthPx);
  const resolution =
    catalogueRes && RESOLUTIONS.includes(catalogueRes as Resolution)
      ? (catalogueRes as Resolution)
      : widthPx
        ? widthToResolution(widthPx)
        : undefined;

  return {
    cpuName: asString(cpu.name),
    gpuName: asString(gpu.name),
    gpuVramGB: asPositive(gpu.vramGB),
    confirmedCpuId: asString(mapping.confirmedCpuId) || undefined,
    confirmedGpuId: asString(mapping.confirmedGpuId) || undefined,
    ram: Object.keys(ram).length ? ram : undefined,
    storage: storageClass as Storage | undefined,
    resolution,
    refreshHz: asPositive(display.refreshHz),
    driverVersion: asString(gpu.driverVersion) || undefined,
    notes,
    shape: 'nested',
  };
}

function readFlat(raw: Record<string, unknown>): StructuredHardware {
  const notes = (Array.isArray(raw.Warnings) ? raw.Warnings : []).map(asString).filter(Boolean);
  const storageClass = asString(raw.Storage);

  const ram: Partial<RamConfig> = {};
  const totalGB = asPositive(raw.RamGB);
  if (totalGB) ram.totalGB = totalGB;
  const channels = asPositive(raw.RamChannels);
  if (channels) ram.channels = (channels >= 8 ? 8 : channels >= 4 ? 4 : channels >= 2 ? 2 : 1) as RamConfig['channels'];
  const speed = asPositive(raw.RamMTs);
  if (speed) ram.speedMTs = speed;

  const res = asString(raw.Resolution);
  return {
    cpuName: asString(raw.CpuName),
    gpuName: asString(raw.GpuName),
    ram: Object.keys(ram).length ? ram : undefined,
    storage: STORAGE_CLASSES.has(storageClass) ? (storageClass as Storage) : undefined,
    resolution: RESOLUTIONS.includes(res as Resolution) ? (res as Resolution) : undefined,
    refreshHz: asPositive(raw.RefreshHz),
    driverVersion: asString(raw.DriverVersion) || undefined,
    notes,
    shape: 'flat',
  };
}

/** Tell the two harness payloads apart. Anything else is treated as free text. */
function readStructured(raw: Record<string, unknown>): StructuredHardware | null {
  const nested = asString(raw.schema).startsWith('rigcheck-hardware/') || !!asObject(raw.cpu)?.name || !!asObject(raw.gpu)?.name;
  if (nested) return readNested(raw);
  if (raw.CpuName || raw.GpuName || raw.RamGB) return readFlat(raw);
  return null;
}

export function detectHardware(text: string, data: EngineData): DetectionResult {
  const warnings: string[] = [];
  const unmatched: string[] = [];

  // Structured JSON from one of the two harness detectors, if that is what we
  // were given. Free text falls through to the regex path below.
  let structured: StructuredHardware | null = null;
  const trimmed = text.trim();
  if (trimmed.startsWith('{')) {
    try {
      const raw = JSON.parse(trimmed) as Record<string, unknown>;
      structured = readStructured(raw);
      if (!structured) {
        warnings.push('Input parsed as JSON but matched neither harness shape (no cpu.name and no CpuName); fell back to text matching.');
      }
    } catch {
      warnings.push('Input looked like JSON but did not parse; fell back to text matching.');
    }
  }

  const cpuText = structured?.cpuName || (CPU_LINE.exec(text)?.[1] ?? '');
  const gpuText = structured?.gpuName || (GPU_LINE.exec(text)?.[1] ?? '');

  const rank = <T extends { fullName: string; brand: string }>(pool: T[], detected: string): DetectedPart<T>[] => {
    if (!detected.trim()) return [];
    return pool
      .map((record) => ({ record, confidence: scoreName(detected, `${record.fullName} ${record.brand}`), matchedOn: detected.trim() }))
      .filter((h) => h.confidence > 40)
      .sort((a, b) => b.confidence - a.confidence)
      .slice(0, 5);
  };

  /**
   * An id the operator confirmed by hand beats anything name matching can do,
   * so it short-circuits — but only if it is really in the catalogue. A typo
   * silently falling back to a fuzzy match is exactly the failure the confirm
   * step exists to prevent, so it is said out loud.
   */
  const confirmed = <T>(pool: Map<string, T>, id: string | undefined, kind: 'CPU' | 'GPU'): DetectedPart<T>[] | null => {
    if (!id) return null;
    const record = pool.get(id);
    if (record) return [{ record, confidence: 100, matchedOn: `confirmed${kind === 'CPU' ? 'Cpu' : 'Gpu'}Id "${id}"` }];
    warnings.push(`The harness confirmed ${kind} id "${id}", but no such record is in the catalogue — falling back to matching on the detected name. Check the id against data/catalogue/.`);
    return null;
  };

  const cpu = confirmed(data.cpus, structured?.confirmedCpuId, 'CPU') ?? rank([...data.cpus.values()], cpuText);
  let gpu = confirmed(data.gpus, structured?.confirmedGpuId, 'GPU') ?? rank([...data.gpus.values()], gpuText);

  // Detected VRAM is the single best tiebreak between variants that share a
  // name — 1060 3GB vs 6GB, 3080 10GB vs 12GB — and it is the difference the
  // detector itself calls out as mattering most.
  if (structured?.gpuVramGB && gpu.length > 1 && gpu[0].confidence < 100) {
    const detectedVram = structured.gpuVramGB;
    gpu = gpu
      .map((h) => {
        const v = h.record.vramGB;
        if (!v) return h;
        // Reported VRAM is a rounded byte count, so allow half a gigabyte of slop.
        const matches = Math.abs(v - detectedVram) <= 0.5;
        return {
          ...h,
          confidence: Math.max(0, Math.min(99, h.confidence + (matches ? 15 : -30))),
          matchedOn: matches ? `${h.matchedOn} + ${detectedVram}GB VRAM` : h.matchedOn,
        };
      })
      .sort((a, b) => b.confidence - a.confidence);
  }

  if (cpuText && !cpu.length) unmatched.push(`CPU: "${cpuText.trim()}"`);
  if (gpuText && !gpu.length) unmatched.push(`GPU: "${gpuText.trim()}"`);
  if (!cpuText && !cpu.length) warnings.push('No CPU line recognised in the input.');
  if (!gpuText && !gpu.length) warnings.push('No GPU line recognised in the input.');

  // An ambiguous top match matters more than a missing one: picking silently
  // between a 1060 3GB and 6GB would corrupt everything downstream.
  if (cpu.length > 1 && cpu[0].confidence - cpu[1].confidence < 8) {
    warnings.push(`CPU match is ambiguous between "${cpu[0].record.fullName}" and "${cpu[1].record.fullName}" — confirm which it is.`);
  }
  if (gpu.length > 1 && gpu[0].confidence - gpu[1].confidence < 8) {
    warnings.push(`GPU match is ambiguous between "${gpu[0].record.fullName}" and "${gpu[1].record.fullName}" — often a VRAM-variant difference, which materially changes the result.`);
  }

  const ram = structured?.ram ?? parseRam(text);

  if (ram?.channels === 1) {
    warnings.push('Detected a single memory channel. Verify this — channel count is inferred from populated slots and is the single easiest thing to get wrong, while also being one of the largest performance effects.');
  }

  let storage = structured?.storage;
  if (!storage) {
    const storageText = text;
    storage = /nvme|pcie\s*4/i.test(storageText)
      ? /gen\s*4|pcie\s*4/i.test(storageText)
        ? 'nvme-gen4'
        : 'nvme-gen3'
      : /ssd/i.test(storageText)
        ? 'sata-ssd'
        : /hdd|hard\s*disk|7200\s*rpm/i.test(storageText)
          ? 'hdd'
          : undefined;
  }

  const res = /(\d{3,4})\s*[x×]\s*(\d{3,4})/.exec(text);
  const resolution = structured?.resolution ?? (res ? widthToResolution(Number(res[1])) : undefined);

  const hz = /(\d{2,3})\s*hz/i.exec(text);

  // Everything the detector had to guess is carried through rather than
  // dropped: those are the fields most likely to be wrong.
  for (const note of structured?.notes ?? []) warnings.push(`Detector best-effort — ${note}`);

  return {
    cpu,
    gpu,
    ram,
    storage,
    resolution,
    refreshHz: structured?.refreshHz ?? (hz ? Number(hz[1]) : undefined),
    driverVersion: structured?.driverVersion,
    unmatched,
    warnings,
  };
}

/** Compose a Build from a detection, using the top candidates. */
export function detectionToBuild(d: DetectionResult, id = 'detected'): Build | null {
  if (!d.cpu.length || !d.gpu.length) return null;
  const cpu = d.cpu[0].record;
  return {
    id,
    label: 'detected machine',
    cpuId: cpu.id,
    gpuId: d.gpu[0].record.id,
    ram: {
      totalGB: d.ram?.totalGB ?? 16,
      channels: d.ram?.channels ?? 2,
      speedMTs: d.ram?.speedMTs ?? 3200,
      type: d.ram?.type ?? cpu.memoryType[0],
    },
    storage: d.storage ?? 'nvme-gen3',
    target: {
      resolution: d.resolution ?? '1080p',
      refreshHz: d.refreshHz ?? 60,
    },
  };
}
