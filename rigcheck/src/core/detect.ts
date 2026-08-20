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
import type { Build, CpuRecord, GpuRecord, MemoryType, RamConfig } from './types.ts';

export interface DetectedPart<T> {
  record: T;
  confidence: number;
  matchedOn: string;
}

export interface DetectionResult {
  cpu: DetectedPart<CpuRecord>[];
  gpu: DetectedPart<GpuRecord>[];
  ram?: Partial<RamConfig>;
  storage?: Build['storage'];
  resolution?: string;
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

/** Parse a memory description such as "32GB DDR5-6000 (2x16GB)". */
function parseRam(text: string): Partial<RamConfig> | undefined {
  const out: Partial<RamConfig> = {};
  const total = /(\d{1,3})\s*gb\b(?![^\n]*(?:vram|graphics))/i.exec(text);
  if (total) out.totalGB = Number(total[1]);
  const type = /\b(ddr[345])\b/i.exec(text);
  if (type) out.type = type[1].toUpperCase() as MemoryType;
  const speed = /\b(?:ddr[345][-\s]?)?(\d{4,5})\s*(?:mhz|mt\/s)?\b/i.exec(text);
  if (speed && Number(speed[1]) >= 1066 && Number(speed[1]) <= 9000) out.speedMTs = Number(speed[1]);
  const kit = /(\d)\s*x\s*(\d{1,2})\s*gb/i.exec(text);
  if (kit) {
    const sticks = Number(kit[1]);
    out.channels = (sticks >= 4 ? 4 : sticks >= 2 ? 2 : 1) as RamConfig['channels'];
    if (!out.totalGB) out.totalGB = sticks * Number(kit[2]);
  }
  return Object.keys(out).length ? out : undefined;
}

export function detectHardware(text: string, data: EngineData): DetectionResult {
  const warnings: string[] = [];
  const unmatched: string[] = [];

  // Structured JSON from the harness detector, if that is what we were given.
  let structured: Record<string, unknown> | null = null;
  const trimmed = text.trim();
  if (trimmed.startsWith('{')) {
    try {
      structured = JSON.parse(trimmed) as Record<string, unknown>;
    } catch {
      warnings.push('Input looked like JSON but did not parse; fell back to text matching.');
    }
  }

  const cpuText = structured?.CpuName ? String(structured.CpuName) : (CPU_LINE.exec(text)?.[1] ?? '');
  const gpuText = structured?.GpuName ? String(structured.GpuName) : (GPU_LINE.exec(text)?.[1] ?? '');

  const rank = <T extends { fullName: string; brand: string }>(pool: T[], detected: string): DetectedPart<T>[] => {
    if (!detected.trim()) return [];
    return pool
      .map((record) => ({ record, confidence: scoreName(detected, `${record.fullName} ${record.brand}`), matchedOn: detected.trim() }))
      .filter((h) => h.confidence > 40)
      .sort((a, b) => b.confidence - a.confidence)
      .slice(0, 5);
  };

  const cpu = rank([...data.cpus.values()], cpuText);
  const gpu = rank([...data.gpus.values()], gpuText);

  if (cpuText && !cpu.length) unmatched.push(`CPU: "${cpuText.trim()}"`);
  if (gpuText && !gpu.length) unmatched.push(`GPU: "${gpuText.trim()}"`);
  if (!cpuText) warnings.push('No CPU line recognised in the input.');
  if (!gpuText) warnings.push('No GPU line recognised in the input.');

  // An ambiguous top match matters more than a missing one: picking silently
  // between a 1060 3GB and 6GB would corrupt everything downstream.
  if (cpu.length > 1 && cpu[0].confidence - cpu[1].confidence < 8) {
    warnings.push(`CPU match is ambiguous between "${cpu[0].record.fullName}" and "${cpu[1].record.fullName}" — confirm which it is.`);
  }
  if (gpu.length > 1 && gpu[0].confidence - gpu[1].confidence < 8) {
    warnings.push(`GPU match is ambiguous between "${gpu[0].record.fullName}" and "${gpu[1].record.fullName}" — often a VRAM-variant difference, which materially changes the result.`);
  }

  const ram = structured?.RamGB
    ? {
        totalGB: Number(structured.RamGB),
        channels: (Number(structured.RamChannels) || 2) as RamConfig['channels'],
        speedMTs: Number(structured.RamMTs) || 3200,
      }
    : parseRam(text);

  if (ram?.channels === 1) {
    warnings.push('Detected a single memory channel. Verify this — channel count is inferred from populated slots and is the single easiest thing to get wrong, while also being one of the largest performance effects.');
  }

  const storageText = structured?.Storage ? String(structured.Storage) : text;
  const storage: Build['storage'] | undefined = /nvme|pcie\s*4/i.test(storageText)
    ? /gen\s*4|pcie\s*4/i.test(storageText) ? 'nvme-gen4' : 'nvme-gen3'
    : /ssd/i.test(storageText)
      ? 'sata-ssd'
      : /hdd|hard\s*disk|7200\s*rpm/i.test(storageText)
        ? 'hdd'
        : undefined;

  const res = /(\d{3,4})\s*[x×]\s*(\d{3,4})/.exec(text);
  const resolution = structured?.Resolution
    ? String(structured.Resolution)
    : res
      ? Number(res[1]) >= 3840 ? '2160p' : Number(res[1]) >= 3440 ? '3440x1440' : Number(res[1]) >= 2560 ? '1440p' : '1080p'
      : undefined;

  const hz = /(\d{2,3})\s*hz/i.exec(text);

  return {
    cpu,
    gpu,
    ram,
    storage,
    resolution,
    refreshHz: structured?.RefreshHz ? Number(structured.RefreshHz) : hz ? Number(hz[1]) : undefined,
    driverVersion: structured?.DriverVersion ? String(structured.DriverVersion) : undefined,
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
      resolution: (d.resolution as Build['target']['resolution']) ?? '1080p',
      refreshHz: d.refreshHz ?? 60,
    },
  };
}
