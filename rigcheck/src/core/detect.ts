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
 * Things that mark a clause as being about the graphics card rather than the
 * system memory. A card carries its own gigabytes and its own four-digit model
 * number, and both look exactly like a memory figure out of context.
 */
const GPU_CLAUSE = /\b(?:rtx|gtx|geforce|radeon|rx\s*\d|arc\s*a\d|quadro|vram|graphics|video)\b/i;
/** Things that mark a clause as being about system memory. */
const RAM_CLAUSE = /\b(?:ddr[345]|lpddr[345]|ram|memory|dimm|sodimm)\b/i;

/**
 * Parse a memory description such as "32GB DDR5-6000 (2x16GB)".
 *
 * Read CLAUSE BY CLAUSE, not by scanning the whole line for the first number
 * that fits. The old version did the latter and was wrong on most real input,
 * because people write the parts in the order they think of them:
 *
 *   "Ryzen 5 3600 / RTX 3060 12GB / 16GB DDR4-3200"
 *
 * gave 12GB of memory — the card's VRAM — running at 3600 MT/s, which is the
 * processor's model number. "RX 6800 XT" produced 6800 MT/s of DDR4, which is
 * not a speed DDR4 can reach. Both figures feed the processor index and the
 * VRAM headroom check, so the whole estimate moved with them.
 *
 * Splitting on the separators people actually use, then preferring the clause
 * that mentions memory and skipping the one that mentions a card, fixes the
 * ordering problem at its root rather than by adding another bound.
 */
function parseRam(text: string): Partial<RamConfig> | undefined {
  const clauses = text.split(/[,/|\n]|\s\+\s/).map((c) => c.trim()).filter(Boolean);
  const ramClauses = clauses.filter((c) => RAM_CLAUSE.test(c));
  const neutral = clauses.filter((c) => !GPU_CLAUSE.test(c));
  // Most specific first: a clause that names memory, then any clause that at
  // least does not name a graphics card, then the raw text as a last resort.
  const scopes = [ramClauses.join(' ; '), neutral.join(' ; '), text].filter(Boolean);

  const firstMatch = (re: RegExp): RegExpExecArray | null => {
    for (const scope of scopes) {
      const m = re.exec(scope);
      if (m) return m;
    }
    return null;
  };

  const out: Partial<RamConfig> = {};

  // The kit is resolved FIRST, because "2x32GB" states the capacity outright and
  // does it more reliably than any standalone figure elsewhere in the line.
  const kit = firstMatch(/(\d)\s*x\s*(\d{1,3})\s*gb/i);
  if (kit) {
    const sticks = Number(kit[1]);
    out.channels = (sticks >= 4 ? 4 : sticks >= 2 ? 2 : 1) as RamConfig['channels'];
    out.totalGB = sticks * Number(kit[2]);
  }

  // A capacity that is the SIZE OF ONE STICK — the 32 in "2x32GB" — is not the
  // total, and taking it as one quarters the machine's memory. Skipped by
  // scanning rather than with a lookbehind, which older Safari rejects when the
  // regex is constructed and would take the whole module down with it.
  const totalRe = /(\d{1,3})\s*gb\b(?![^\n]*(?:vram|graphics))/gi;
  outer: for (const scope of out.totalGB ? [] : scopes) {
    totalRe.lastIndex = 0;
    for (let m = totalRe.exec(scope); m; m = totalRe.exec(scope)) {
      if (/\d\s*x\s*$/i.test(scope.slice(Math.max(0, m.index - 4), m.index))) continue;
      out.totalGB = Number(m[1]);
      break outer;
    }
  }

  const type = /\b(?:lp)?(ddr[345])\b/i.exec(text);
  if (type) out.type = type[1].toUpperCase() as MemoryType;

  // Anchored on both attempts: a bare four-digit number is a model number far
  // more often than it is a memory speed. The DDR prefix is tried across the
  // whole text first because it is the strongest signal there is.
  const speed =
    /\b(?:lp)?ddr[345][-\s]?(\d{4,5})\b/i.exec(text) ??
    firstMatch(/\b(\d{4,5})\s*(?:mhz|mt\/s)\b/i);
  if (speed && Number(speed[1]) >= 1066 && Number(speed[1]) <= 9000) out.speedMTs = Number(speed[1]);

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
