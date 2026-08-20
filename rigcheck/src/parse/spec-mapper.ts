/**
 * spec-mapper — the missing layer between an expanded specification table and a
 * canonical `GpuRecord` / `CpuRecord`.
 *
 * `wikitable.ts` turns a page into aligned rows. This module turns those rows
 * into records: it identifies which column holds which field, normalises units,
 * slugs stable ids, splits dual-configuration SKUs into separate records, and
 * reports — never silently drops — everything it could not use.
 *
 * ---------------------------------------------------------------------------
 * HONESTY NOTE — read before trusting any output of this file.
 *
 * Wikipedia (and every other specification source) is egress-blocked in the
 * environment this was written in, so this mapping has NEVER been run against a
 * real page. It is tested against SYNTHETIC fixtures in `tests/fixtures/` that
 * imitate the real table shapes from memory: colspan header groups, rowspan
 * launch dates, footnote superscripts inside numbers, units inside cells,
 * ranges and "N/A" cells. Those fixtures are a model of the real pages, not a
 * sample of them.
 *
 * What that means concretely:
 *  - The header ALIAS tables below are the part most likely to be incomplete.
 *    They are cheap to extend and every unmatched column is reported in
 *    `columnsMissing`, so the first real run tells you exactly what to add.
 *  - The generated ids follow the catalogue's convention, but a handful of
 *    hand-curated ids in `data/catalogue/` are shortened by hand (e.g.
 *    `nvidia-geforce-gt-730-gddr5` for variant "GDDR5 64-bit",
 *    `intel-uhd-770-adl` for variant "Alder Lake"). Those will NOT round-trip
 *    and will look like new records to the reconciler until a manual id-alias
 *    map exists. See `agents/log/spec-mapper.md`.
 *  - Nothing here is allowed to invent a number. Unparseable, "N/A", "?",
 *    "Unknown" and empty cells become `null`/absent, never `0`.
 * ---------------------------------------------------------------------------
 */
import type {
  Conflict,
  CpuCapabilities,
  CpuRecord,
  CpuSegment,
  GpuCapabilities,
  GpuRecord,
  MemoryType,
  UpscalingTech,
  Vendor,
} from '../core/types.ts';
import { cleanCellText, expandTable, extractTables, gridToRecords, parseNumeric } from './wikitable.ts';

// ---------------------------------------------------------------------------
// Public surface
// ---------------------------------------------------------------------------

/** A column-identification result: which grid column holds which record field. */
export interface ColumnMap {
  [field: string]: number;
}

/** Units a raw cell can be normalised into. `count` is a bare integer. */
export type SpecUnit = 'MHz' | 'GHz' | 'GB' | 'MB' | 'bit' | 'W' | 'nm' | 'GB/s' | 'count' | 'date';

/**
 * Context a page carries that individual rows do not.
 *
 * The platform fields are inherited from the section headings above a table
 * (see `sectionContext`). A row column always wins over them: the heading is
 * the general case, the column is the specific one.
 */
export interface MapContext {
  vendor: string;
  /** Fallback architecture when the table has no architecture column. */
  architecture?: string;
  /** Section-heading fallback for a CPU's socket, e.g. "Socket AM4". */
  socket?: string;
  /** Section-heading fallback for supported memory types. */
  memoryType?: MemoryType[];
  /** Section-heading fallback for the memory controller's channel count. */
  maxMemChannels?: 1 | 2 | 4 | 8;
  /** The raw heading each inherited value came from, for the parse report. */
  inheritedFrom?: Record<string, string>;
  /** Provenance key stamped onto `_prov` and `_conflicts`. */
  provenanceId?: string;
}

export interface SkippedRow {
  row: string;
  reason: string;
}

export interface SpecPageReport {
  records: Partial<GpuRecord | CpuRecord>[];
  skipped: SkippedRow[];
  columnsFound: ColumnMap;
  columnsMissing: string[];
  /** Per-table diagnostics. Extra to the required shape; ignore if not needed. */
  tables?: {
    index: number;
    headers: string[];
    dataRows: number;
    accepted: number;
    used: boolean;
    reason?: string;
    /** The nearest heading above the table. */
    section?: string;
    /**
     * The full ancestor path, e.g. "Socket AM4 > Ryzen 5000 series". The leaf
     * alone hides where an inherited socket came from, which is exactly what
     * you need when a whole section maps to the wrong platform.
     */
    sectionPath?: string;
    /** Fields this table inherited from headings, and the heading each came from. */
    inherited?: Record<string, string>;
  }[];
  /** Free-text observations (merged duplicates, values rejected as implausible). */
  notes?: string[];
}

// ---------------------------------------------------------------------------
// Missing-value handling. The single most important rule in this file: a cell we
// cannot read becomes null. Never 0, never a guess.
// ---------------------------------------------------------------------------

const SENTINELS = new Set([
  'na',
  'n',
  'unknown',
  'unk',
  'undisclosed',
  'unspecified',
  'notapplicable',
  'notavailable',
  'nodata',
  'notlisted',
  'tba',
  'tbd',
  'tbc',
  'none',
  'no',
  'nil',
  'varies',
  'various',
  'cancelled',
  'canceled',
  'neverreleased',
  'unreleased',
]);

/** True when a cell carries no usable value ("N/A", "?", "—", "", "Unknown"). */
export function isMissing(raw: string | undefined | null): boolean {
  if (raw == null) return true;
  const key = raw.toLowerCase().replace(/[^a-z0-9]/g, '');
  return key === '' || SENTINELS.has(key);
}

/**
 * Detect a cell that states two alternative configurations ("3 GB or 6 GB",
 * "1506/1683 MHz"). Picking either one would be a coin flip presented as a
 * fact, so these resolve to null and the row is reported instead.
 *
 * Deliberately narrow: the numbers must be whitespace/start delimited so that
 * "8 GB GDDR6/GDDR6X" (one size, two memory technologies) still parses.
 */
const ALTERNATIVES_RE =
  /(?:^|\s)\d[\d.,]*\s*(?:gb|mb|w|mhz|ghz|bit)?\s*(?:or|\/)\s*\d[\d.,]*\s*(?:gb|mb|w|mhz|ghz|bit)?(?:\s|$)/i;

function hasAlternatives(text: string): boolean {
  return ALTERNATIVES_RE.test(text);
}

// ---------------------------------------------------------------------------
// Unit normalisation
// ---------------------------------------------------------------------------

const MONTHS: Record<string, number> = {
  jan: 1, january: 1, feb: 2, february: 2, mar: 3, march: 3, apr: 4, april: 4,
  may: 5, jun: 6, june: 6, jul: 7, july: 7, aug: 8, august: 8, sep: 9, sept: 9,
  september: 9, oct: 10, october: 10, nov: 11, november: 11, dec: 12, december: 12,
};

const pad = (n: number) => String(n).padStart(2, '0');

/**
 * Launch dates. The catalogue uses `YYYY-MM-DD`, `YYYY-MM` or `YYYY` depending
 * on how precise the source is; a quarter ("Q3 2016") degrades to the year
 * rather than inventing a month.
 */
export function normaliseDate(raw: string): string | null {
  const text = cleanCellText(raw).replace(/ /g, ' ').trim();
  if (isMissing(text)) return null;

  let m = /(\d{4})-(\d{1,2})-(\d{1,2})/.exec(text);
  if (m) return `${m[1]}-${pad(Number(m[2]))}-${pad(Number(m[3]))}`;

  // "March 26, 2010" / "Mar 26 2010"
  m = /([A-Za-z]{3,9})\.?\s+(\d{1,2})(?:st|nd|rd|th)?,?\s+(\d{4})/.exec(text);
  if (m && MONTHS[m[1].toLowerCase()]) {
    return `${m[3]}-${pad(MONTHS[m[1].toLowerCase()])}-${pad(Number(m[2]))}`;
  }

  // "26 March 2010"
  m = /(\d{1,2})(?:st|nd|rd|th)?\s+([A-Za-z]{3,9})\.?,?\s+(\d{4})/.exec(text);
  if (m && MONTHS[m[2].toLowerCase()]) {
    return `${m[3]}-${pad(MONTHS[m[2].toLowerCase()])}-${pad(Number(m[1]))}`;
  }

  // "March 2010"
  m = /([A-Za-z]{3,9})\.?\s+(\d{4})/.exec(text);
  if (m && MONTHS[m[1].toLowerCase()]) return `${m[2]}-${pad(MONTHS[m[1].toLowerCase()])}`;

  // "2010-03" (no day)
  m = /(\d{4})-(\d{1,2})(?!\d)/.exec(text);
  if (m && Number(m[2]) >= 1 && Number(m[2]) <= 12) return `${m[1]}-${pad(Number(m[2]))}`;

  // Bare year, or a quarter we refuse to turn into a month.
  m = /(?:^|\D)((?:19|20)\d{2})(?:$|\D)/.exec(text);
  if (m) return m[1];

  return null;
}

const round = (n: number, dp: number) => {
  const f = 10 ** dp;
  return Math.round(n * f) / f;
};

/**
 * Normalise a raw cell into a typed value with its unit resolved.
 *
 * The `unit` argument is the unit the CALLER wants back, not the unit in the
 * cell: `normaliseUnit('1.5 GHz', 'MHz')` is 1500. Where a cell carries no unit
 * at all (the header carried it), a domain heuristic decides — documented at
 * each site, and each one is safe for the hardware in scope but would not be
 * safe for, say, 1990s parts.
 */
export function normaliseUnit(raw: string, unit: SpecUnit): number | string | null {
  const text = cleanCellText(raw ?? '');
  if (isMissing(text)) return null;
  if (unit === 'date') return normaliseDate(text);
  // "3 GB or 6 GB" is two SKUs in one cell, not a value.
  if (unit !== 'GB/s' && hasAlternatives(text)) return null;

  switch (unit) {
    case 'MHz': {
      const n = parseNumeric(text);
      if (n == null) return null;
      if (/\bghz\b/i.test(text)) return Math.round(n * 1000);
      if (/\bmhz\b/i.test(text)) return Math.round(n);
      // Unitless: no part in scope clocks below 100 MHz, so a small number is GHz.
      return n < 100 ? Math.round(n * 1000) : Math.round(n);
    }
    case 'GHz': {
      const n = parseNumeric(text);
      if (n == null) return null;
      if (/\bmhz\b/i.test(text)) return round(n / 1000, 4);
      if (/\bghz\b/i.test(text)) return round(n, 4);
      return n > 100 ? round(n / 1000, 4) : round(n, 4);
    }
    case 'GB': {
      const n = parseNumeric(text);
      if (n == null) return null;
      if (/\b(mb|mib)\b/i.test(text)) return round(n / 1024, 5);
      if (/\b(kb|kib)\b/i.test(text)) return round(n / 1048576, 6);
      if (/\b(tb|tib)\b/i.test(text)) return round(n * 1024, 5);
      if (/\b(gb|gib)\b/i.test(text)) return round(n, 5);
      // Unitless: no gaming part has 128 GB of VRAM, but plenty had 128 MB.
      return n >= 128 ? round(n / 1024, 5) : round(n, 5);
    }
    case 'MB': {
      // Cache is often quoted as a per-unit count: "4 × 512 KB", "8 x 1 MB".
      const mult = /(\d+)\s*[x×*]\s*(\d+(?:\.\d+)?)\s*(kb|mb|gb)\b/i.exec(text);
      if (mult) {
        const each = Number(mult[2]);
        const scale = mult[3].toLowerCase() === 'kb' ? 1 / 1024 : mult[3].toLowerCase() === 'gb' ? 1024 : 1;
        return round(Number(mult[1]) * each * scale, 5);
      }
      const n = parseNumeric(text);
      if (n == null) return null;
      if (/\b(kb|kib)\b/i.test(text)) return round(n / 1024, 5);
      if (/\b(gb|gib)\b/i.test(text)) return round(n * 1024, 5);
      return round(n, 5);
    }
    case 'bit': {
      const mult = /(\d+)\s*[x×*]\s*(\d+)\s*-?\s*bit/i.exec(text);
      if (mult) return Number(mult[1]) * Number(mult[2]);
      const n = parseNumeric(text);
      return n == null ? null : Math.round(n);
    }
    case 'W': {
      const n = parseNumeric(text);
      return n == null ? null : round(n, 2);
    }
    case 'nm': {
      // "TSMC N7", "TSMC 5 nm (4N)", "14 nm++", "Samsung 8 nm" all state a
      // nanometre-class figure. Intel's post-2021 node NAMES do not: "Intel 7"
      // is a 10 nm-class process and "Intel 4" a 7 nm-class one. Reading the
      // digit would write a number that is simply false, so these resolve to
      // null — a recorded gap instead of a wrong figure.
      if (/\bintel\s+\d+[a-z]?\b/i.test(text) && !/\bnm\b/i.test(text)) return null;
      const n = parseNumeric(text);
      return n == null ? null : round(n, 2);
    }
    case 'GB/s': {
      const n = parseNumeric(text);
      if (n == null) return null;
      if (/\btb\s*\/?\s*s\b/i.test(text)) return round(n * 1000, 3);
      return round(n, 3);
    }
    case 'count': {
      // Core configs read "1280:80:48" (shaders:TMUs:ROPs) — the first field is
      // the shader count and the rest must not be mistaken for a range.
      const cfg = /(\d[\d,]*)\s*:\s*\d/.exec(text);
      if (cfg) return Number(cfg[1].replace(/,/g, ''));
      const n = parseNumeric(text);
      return n == null ? null : Math.round(n);
    }
    default:
      return null;
  }
}

// ---------------------------------------------------------------------------
// Header aliasing
//
// Real tables name the same field a dozen ways. Each rule is a regex over the
// NORMALISED header text (lowercased, whitespace-collapsed, parent and child of
// a colspan group joined by a space, e.g. "Memory Bus width (bit)"). `not`
// blocks the near-misses that would otherwise steal a column — "Memory clock"
// must never win the base-clock slot, "Processing power" must never win the
// process-node slot.
//
// Scores resolve competition: every (field, column) candidate is collected,
// sorted by score, and assigned greedily so one column serves one field.
// ---------------------------------------------------------------------------

interface HeaderRule {
  field: string;
  test: RegExp;
  not?: RegExp;
  score: number;
}

/** Fields a GPU table is expected to yield. Anything missing is reported. */
export const GPU_FIELDS = [
  'name',
  'launchDate',
  'chip',
  'architecture',
  'processNm',
  'shaders',
  'baseClockMHz',
  'boostClockMHz',
  'vramGB',
  'vramType',
  'memBusBits',
  'memBandwidthGBs',
  'fp32TFLOPS',
  'tdpW',
  'pcieInterface',
] as const;

/** Fields a CPU table is expected to yield. */
export const CPU_FIELDS = [
  'name',
  'launchDate',
  'codename',
  'architecture',
  'processNm',
  'coresThreads',
  'cores',
  'threads',
  'pCores',
  'eCores',
  'baseClockMHz',
  'boostClockMHz',
  'l2CacheMB',
  'l3CacheMB',
  'tdpW',
  'socket',
  'memorySupport',
  'maxMemChannels',
] as const;

const GPU_RULES: HeaderRule[] = [
  { field: 'name', test: /^model(\s|$)/, score: 96 },
  { field: 'name', test: /\b(model|board)\s*(name|number)?\b/, not: /clock|memory|config/, score: 90 },
  { field: 'name', test: /^(name|product|sku|card|graphics card|board)\b/, score: 84 },

  { field: 'launchDate', test: /\b(launch|release[d]?|introduc\w*|announce\w*|availab\w*)\b/, not: /price/, score: 92 },
  { field: 'launchDate', test: /\bdate\b/, score: 70 },

  { field: 'chip', test: /\bcode\s?name\b/, score: 94 },
  { field: 'chip', test: /^(chip|gpu|die|core)\b/, not: /clock|config|count|speed|size|area|\bmm\b/, score: 62 },

  { field: 'architecture', test: /\b(micro)?architecture\b/, score: 96 },

  { field: 'processNm', test: /\b(fab(rication)?|lithograph\w*|process node|node)\b/, not: /processing|processor/, score: 90 },
  { field: 'processNm', test: /\bprocess\b/, not: /processing|processor/, score: 86 },
  { field: 'processNm', test: /\(nm\)|\bnm\b/, score: 72 },

  { field: 'shaders', test: /\b(shading units|shader (processors|units|cores)|unified shaders|stream processors|cuda cores)\b/, score: 96 },
  { field: 'shaders', test: /\bcore config\w*\b/, score: 90 },
  { field: 'shaders', test: /\bshaders?\b/, score: 80 },
  { field: 'shaders', test: /\b(sp count|alus)\b/, score: 74 },

  { field: 'baseClockMHz', test: /\bbase\b/, not: /memory|mem\b|power|tdp/, score: 94 },
  { field: 'baseClockMHz', test: /^(core|gpu|graphics)?\s*clock\b/, not: /memory|boost|turbo|game|max/, score: 70 },
  { field: 'baseClockMHz', test: /\bclock (rate|speed)s?\b/, not: /memory|boost|turbo|max/, score: 66 },

  { field: 'boostClockMHz', test: /\b(boost|turbo)\b/, not: /memory|power/, score: 94 },
  { field: 'boostClockMHz', test: /\bmax\w*\.?\s*(clock|frequency|speed)\b/, not: /memory/, score: 90 },
  { field: 'boostClockMHz', test: /\bgame clock\b/, score: 72 },

  { field: 'vramGB', test: /\b(memory|vram|dram)\b.*\b(size|capacity|amount)\b/, not: /bandwidth|bus|clock|type/, score: 96 },
  { field: 'vramGB', test: /\bstandard memory config\w*\b/, score: 94 },
  { field: 'vramGB', test: /^size\b/, score: 82 },
  { field: 'vramGB', test: /^(memory|vram)( \(gb\)| \(mb\))?$/, score: 76 },

  { field: 'vramType', test: /\b(memory|dram|vram|bus)\b.*\btype\b/, score: 96 },
  { field: 'vramType', test: /^type\b/, score: 80 },

  { field: 'memBusBits', test: /\b(bus|interface|memory)\b.*\bwidth\b/, not: /type|bandwidth/, score: 96 },
  { field: 'memBusBits', test: /\bwidth\b/, not: /type|bandwidth/, score: 84 },
  { field: 'memBusBits', test: /\bmemory interface\b/, score: 80 },

  { field: 'memBandwidthGBs', test: /\bbandwidth\b/, not: /type/, score: 96 },

  { field: 'fp32TFLOPS', test: /\b(single[- ]precision|fp\s?32|sp fp32)\b/, not: /double|half|fp\s?64|fp\s?16/, score: 96 },
  { field: 'fp32TFLOPS', test: /\b(processing power|tflops|gflops|flops)\b/, not: /double|half|fp\s?64|fp\s?16|int8|tensor|texture|pixel/, score: 74 },

  { field: 'tdpW', test: /\b(tdp|tbp|tgp)\b/, score: 96 },
  { field: 'tdpW', test: /\bpower\b/, not: /connector|supply|psu|max turbo/, score: 86 },
  { field: 'tdpW', test: /\bwatt\w*\b/, score: 72 },

  { field: 'pcieInterface', test: /\bbus interface\b/, score: 90 },
  { field: 'pcieInterface', test: /\bpci\s?-?e(xpress)?\b/, not: /width/, score: 80 },
  { field: 'pcieInterface', test: /^interface$/, score: 70 },
];

const CPU_RULES: HeaderRule[] = [
  { field: 'name', test: /^(model|processor|cpu)\s*(number|name)?\b/, not: /clock|cache|family/, score: 96 },
  { field: 'name', test: /\bmodel\b/, not: /clock|cache/, score: 88 },
  { field: 'name', test: /^(name|sku|brand|part)\b/, score: 82 },

  { field: 'launchDate', test: /\b(launch|release[d]?|introduc\w*|announce\w*|availab\w*)\b/, not: /price/, score: 92 },
  { field: 'launchDate', test: /\bdate\b/, score: 70 },

  { field: 'codename', test: /\bcode\s?name\b/, score: 96 },
  { field: 'architecture', test: /\b(micro)?architecture\b/, score: 96 },

  { field: 'processNm', test: /\b(fab(rication)?|lithograph\w*|process node|node)\b/, not: /processing|processor/, score: 90 },
  { field: 'processNm', test: /\bprocess\b/, not: /processing|processor/, score: 86 },
  { field: 'processNm', test: /\(nm\)|\bnm\b/, score: 72 },

  // "Cores (threads)" is one column holding two fields.
  { field: 'coresThreads', test: /\bcores?\b[^a-z]*\(?\s*threads?\s*\)?/, score: 98 },
  { field: 'coresThreads', test: /\bcores?\s*\/\s*threads?\b/, score: 98 },
  { field: 'cores', test: /\bcores?\b/, not: /thread|e-?core|p-?core|efficien|performance core|per core/, score: 92 },
  { field: 'threads', test: /\bthreads?\b/, not: /core/, score: 92 },
  { field: 'pCores', test: /\b(p-?cores?|performance cores?)\b/, score: 96 },
  { field: 'eCores', test: /\b(e-?cores?|efficien\w* cores?)\b/, score: 96 },

  { field: 'baseClockMHz', test: /\bbase\b/, not: /memory|power|tdp/, score: 94 },
  { field: 'baseClockMHz', test: /^(cpu|core)?\s*(clock|frequency)\b/, not: /memory|boost|turbo|max/, score: 70 },
  { field: 'baseClockMHz', test: /\bclock (rate|speed)s?\b/, not: /memory|boost|turbo|max/, score: 66 },

  { field: 'boostClockMHz', test: /\b(boost|turbo)\b/, not: /memory|power|tdp/, score: 94 },
  { field: 'boostClockMHz', test: /\bmax\w*\.?\s*(clock|frequency|speed)\b/, not: /memory/, score: 90 },

  { field: 'l2CacheMB', test: /\bl2\b/, score: 96 },
  { field: 'l3CacheMB', test: /\b(l3|smart cache)\b/, score: 96 },

  { field: 'tdpW', test: /\b(tdp|pbp|base power|processor base power)\b/, not: /max turbo|pl2/, score: 96 },
  { field: 'tdpW', test: /\bpower\b/, not: /max turbo|pl2|connector|supply/, score: 84 },
  { field: 'tdpW', test: /\bwatt\w*\b/, score: 72 },

  { field: 'socket', test: /\bsocket\b/, score: 96 },
  { field: 'socket', test: /^package\b/, score: 72 },

  { field: 'maxMemChannels', test: /\bmemory channels?\b|\bchannels?\b/, score: 92 },
  { field: 'memorySupport', test: /\bmemory\b/, not: /channel|bandwidth|controller/, score: 86 },
];

/** Lowercase, collapse whitespace, keep punctuation (units live in it). */
export function normaliseHeader(h: string): string {
  return cleanCellText(h ?? '')
    .toLowerCase()
    .replace(/\s+/g, ' ')
    .trim();
}

/**
 * Identify columns by matching header text against known aliases.
 *
 * Greedy highest-score-first assignment: one column serves at most one field,
 * and a field takes at most one column (the first/leftmost on a score tie,
 * which is what tables that repeat a header per architecture group want).
 */
export function mapColumns(headers: string[], kind: 'gpu' | 'cpu'): ColumnMap {
  const rules = kind === 'gpu' ? GPU_RULES : CPU_RULES;
  const normalised = headers.map(normaliseHeader);

  const candidates: { field: string; col: number; score: number }[] = [];
  for (let col = 0; col < normalised.length; col++) {
    const h = normalised[col];
    if (!h) continue;
    for (const rule of rules) {
      if (!rule.test.test(h)) continue;
      if (rule.not && rule.not.test(h)) continue;
      candidates.push({ field: rule.field, col, score: rule.score });
    }
  }

  candidates.sort((a, b) => b.score - a.score || a.col - b.col);

  const out: ColumnMap = {};
  const takenCols = new Set<number>();
  for (const c of candidates) {
    if (out[c.field] !== undefined || takenCols.has(c.col)) continue;
    out[c.field] = c.col;
    takenCols.add(c.col);
  }
  return out;
}

// ---------------------------------------------------------------------------
// Naming, variants and ids
// ---------------------------------------------------------------------------

const VENDOR_DISPLAY: Record<Vendor, string> = { nvidia: 'NVIDIA', amd: 'AMD', intel: 'Intel' };

export function normaliseVendor(vendor: string): Vendor | null {
  const v = (vendor ?? '').toLowerCase().trim();
  if (v.startsWith('nvidia') || v === 'nv') return 'nvidia';
  if (v.startsWith('amd') || v.startsWith('ati') || v.startsWith('radeon')) return 'amd';
  if (v.startsWith('intel')) return 'intel';
  return null;
}

const slugPart = (s: string) =>
  (s ?? '')
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-+|-+$/g, '');

/** "6 GB" -> "6GB", "768 mb" -> "768MB"; anything else keeps its own shape. */
export function normaliseVariant(variant: string): string {
  const t = cleanCellText(variant).trim();
  const m = /^(\d+(?:\.\d+)?)\s*(gb|mb|tb)$/i.exec(t);
  if (m) return `${m[1]}${m[2].toUpperCase()}`;
  return t.replace(/\s+/g, ' ');
}

/**
 * Turn a marketing name into a stable catalogue id.
 *
 * Convention taken from `data/catalogue/gpus.json`:
 *   nvidia + "GeForce GTX 1060" + "6 GB" -> "nvidia-geforce-gtx-1060-6gb"
 *   amd    + "Radeon RX 480"    + "8GB"  -> "amd-radeon-rx-480-8gb"
 *   intel  + "Intel Arc A770"   + "16GB" -> "intel-arc-a770-16gb"
 *   intel  + "Core i7-2700K"             -> "intel-core-i7-2700k"
 *
 * A leading vendor word in the name is dropped so Intel parts (whose marketing
 * names include "Intel") do not become "intel-intel-...". For Intel the filler
 * word "Graphics" is also dropped, matching `intel-uhd-770` for
 * "Intel UHD Graphics 770".
 */
export function slugify(vendor: string, name: string, variant?: string): string {
  const v = normaliseVendor(vendor) ?? slugPart(vendor);
  let base = cleanCellText(name ?? '').trim();
  base = base.replace(/^(nvidia|amd|ati|intel)\s+/i, '');
  if (v === 'intel') {
    const dropped = base.replace(/\bgraphics\b/gi, ' ').replace(/\s+/g, ' ').trim();
    if (slugPart(dropped)) base = dropped;
  }
  let slug = slugPart(base);
  const varSlug = variant ? slugPart(normaliseVariant(variant)) : '';
  // Callers sometimes pass the full name AND the variant; do not repeat it.
  if (varSlug && slug.endsWith(`-${varSlug}`)) slug = slug.slice(0, -(varSlug.length + 1));
  return [v, slug, varSlug].filter(Boolean).join('-');
}

/**
 * A name that covers two configurations at once ("GTX 1060 3 GB / 6 GB").
 * These must be split by hand — silently picking one would fabricate a SKU.
 */
export function isMultiConfigName(name: string): boolean {
  return hasAlternatives(cleanCellText(name ?? ''));
}

const cleanTail = (s: string) => s.replace(/[\s(,\-/|]+$/g, '').trim();

/**
 * Split a marketing name into its catalogue `brand` and `variant`.
 *
 * Handles the three variant shapes the catalogue actually uses: memory size
 * ("GTX 1060 6 GB" -> 6GB), memory technology ("GT 1030 DDR4" -> DDR4) and a
 * short trailing parenthetical ("Titan X (Pascal)" -> Pascal). A parenthetical
 * containing a comma is descriptive, not a variant, and is dropped from the
 * brand while remaining in `fullName`.
 */
export function splitVariant(rawName: string): { base: string; variant?: string } {
  let name = cleanCellText(rawName ?? '').replace(/\s+/g, ' ').trim();
  name = name.replace(/\b(limited|founders|founder's)\s+edition\b/gi, ' ').replace(/\s+/g, ' ').trim();

  // Descriptive parenthetical ("(Ivy Bridge, 16 EU)") — not an identity.
  const descriptive = /^(.*?)\s*\(([^()]*,[^()]*)\)\s*$/.exec(name);
  if (descriptive && descriptive[1].trim()) name = descriptive[1].trim();

  let m = /^(.*?)[\s(]+(\d+(?:\.\d+)?)\s*(GB|MB|TB)\)?$/i.exec(name);
  if (m && cleanTail(m[1])) {
    return { base: cleanTail(m[1]), variant: normaliseVariant(`${m[2]} ${m[3]}`) };
  }

  m = /^(.*?)\s+\(?((?:G?DDR\d[A-Z]?|HBM\d?[Ee]?)(?:\s+\d+-bit)?)\)?$/i.exec(name);
  if (m && cleanTail(m[1])) {
    return { base: cleanTail(m[1]), variant: m[2].toUpperCase().replace(/\s+/g, ' ') };
  }

  m = /^(.*?)\s*\(([^(),]{2,24})\)\s*$/.exec(name);
  if (m && cleanTail(m[1]) && m[2].trim().split(/\s+/).length <= 2 && !/^\d+$/.test(m[2].trim())) {
    return { base: cleanTail(m[1]), variant: m[2].trim() };
  }

  return { base: name };
}

/**
 * "NVIDIA GeForce GTX 1060 6GB" — vendor prefixed unless already present, with
 * a memory-size token compacted to the catalogue's spelling ("6 GB" -> "6GB").
 * Everything else the source says is preserved, including descriptive suffixes
 * such as "Limited Edition" which the catalogue also keeps in `fullName`.
 */
export function fullNameFor(vendor: Vendor, rawName: string): string {
  const name = cleanCellText(rawName ?? '')
    .replace(/\s+/g, ' ')
    .replace(/(\d+(?:\.\d+)?)\s+(GB|MB|TB)\b/gi, (_m, n: string, u: string) => `${n}${u.toUpperCase()}`)
    .trim();
  const display = VENDOR_DISPLAY[vendor];
  return new RegExp(`^(${display}|nvidia|amd|ati|intel)\\b`, 'i').test(name) ? name : `${display} ${name}`;
}

// ---------------------------------------------------------------------------
// Architecture detection and rule-derived capabilities
//
// These are DERIVED, not read from the page: the rules come from
// agents/BRIEF.md ("capability gates"), and the vocabulary matches the strings
// already used in data/catalogue. They exist because the reconciler rejects a
// GPU with no `caps.meshShaders`, and a spec table never states it. Every
// derived record is stamped with the `spec-derived-caps` provenance key so the
// distinction survives into the catalogue.
// ---------------------------------------------------------------------------

const ARCH_PATTERNS: { re: RegExp; name: string }[] = [
  // Nvidia
  { re: /\bada(\s+lovelace)?\b/i, name: 'Ada Lovelace' },
  { re: /\bblackwell\b/i, name: 'Blackwell' },
  { re: /\bampere\b/i, name: 'Ampere' },
  { re: /\bturing\b/i, name: 'Turing' },
  { re: /\bvolta\b/i, name: 'Volta' },
  { re: /\bpascal\b/i, name: 'Pascal' },
  { re: /\bmaxwell\s*2(\.0)?\b/i, name: 'Maxwell 2' },
  { re: /\bmaxwell\s*1(\.0)?\b/i, name: 'Maxwell 1' },
  { re: /\bmaxwell\b/i, name: 'Maxwell 2' },
  { re: /\bkepler\b/i, name: 'Kepler' },
  { re: /\bfermi\b/i, name: 'Fermi' },
  // AMD GPU
  { re: /\brdna\s*4\b/i, name: 'RDNA 4' },
  { re: /\brdna\s*3\.5\b/i, name: 'RDNA 3.5' },
  { re: /\brdna\s*3\b/i, name: 'RDNA 3' },
  { re: /\brdna\s*2\b/i, name: 'RDNA 2' },
  { re: /\brdna\s*1?\b/i, name: 'RDNA 1' },
  { re: /\bvega\b/i, name: 'GCN 5 (Vega)' },
  { re: /\bgcn\s*5(\.0)?\b/i, name: 'GCN 5.0' },
  { re: /\bgcn\s*4(\.0)?\b|\bpolaris\b/i, name: 'GCN 4.0' },
  { re: /\bgcn\s*3(\.0)?\b/i, name: 'GCN 3.0' },
  { re: /\bgcn\s*2(\.0)?\b/i, name: 'GCN 2.0' },
  { re: /\bgcn\s*1?(\.0)?\b/i, name: 'GCN 1.0' },
  // Intel GPU
  { re: /\bbattlemage\b|\bxe2-hpg\b/i, name: 'Xe2-HPG (Battlemage)' },
  { re: /\balchemist\b|\bxe-hpg\b/i, name: 'Xe-HPG (Alchemist)' },
  // AMD CPU
  { re: /\bzen\s*5\b/i, name: 'Zen 5' },
  { re: /\bzen\s*4\b/i, name: 'Zen 4' },
  { re: /\bzen\s*3\b/i, name: 'Zen 3' },
  { re: /\bzen\s*2\b/i, name: 'Zen 2' },
  { re: /\bzen\s*\+/i, name: 'Zen+' },
  { re: /\bzen\b/i, name: 'Zen' },
  { re: /\bexcavator\b/i, name: 'Excavator' },
  { re: /\bsteamroller\b/i, name: 'Steamroller' },
  { re: /\bpiledriver\b/i, name: 'Piledriver' },
  { re: /\bbulldozer\b/i, name: 'Bulldozer' },
  // Intel CPU
  { re: /\barrow\s*lake\b/i, name: 'Arrow Lake' },
  { re: /\bmeteor\s*lake\b/i, name: 'Meteor Lake' },
  { re: /\braptor\s*lake\b/i, name: 'Raptor Lake' },
  { re: /\balder\s*lake\b/i, name: 'Alder Lake' },
  { re: /\brocket\s*lake\b|\bcypress\s*cove\b/i, name: 'Cypress Cove' },
  { re: /\bcomet\s*lake\b/i, name: 'Comet Lake' },
  { re: /\bcoffee\s*lake\b/i, name: 'Coffee Lake' },
  { re: /\bkaby\s*lake\b/i, name: 'Kaby Lake' },
  { re: /\bcascade\s*lake\b/i, name: 'Cascade Lake' },
  { re: /\bskylake-?x\b/i, name: 'Skylake-X' },
  { re: /\bskylake\b/i, name: 'Skylake' },
  { re: /\bbroadwell\b/i, name: 'Broadwell' },
  { re: /\bhaswell\b/i, name: 'Haswell' },
  { re: /\bivy\s*bridge\b/i, name: 'Ivy Bridge' },
  { re: /\bsandy\s*bridge\b/i, name: 'Sandy Bridge' },
];

/**
 * Recognise an architecture name in free text (a cell or a section heading).
 * Recognition only — an unrecognised string yields null rather than being
 * passed through, because "GeForce 10 series" is not an architecture.
 */
export function detectArchitecture(text: string | undefined | null): string | null {
  if (!text) return null;
  const t = cleanCellText(text);
  for (const p of ARCH_PATTERNS) if (p.re.test(t)) return p.name;
  return null;
}


/**
 * Marketing series to architecture, for pages that head their sections with a
 * product line rather than the silicon.
 *
 * This exists because `detectArchitecture` correctly refuses to pass "GeForce
 * 10 series" through — it is not an architecture — and Nvidia's pages head
 * almost every section that way. Without this map no Nvidia record gets an
 * architecture, no architecture means no derived capabilities, and the
 * reconciler rejects the lot. That is the single largest blocker to a real
 * Nvidia harvest ever producing accepted records.
 *
 * The mapping is a DERIVATION, not something the page states, and it is only
 * safe where a series used exactly one architecture. Two rules follow:
 *
 *  - Series that genuinely mixed silicon are handled by naming the exceptions
 *    (GeForce 700: Kepler, except the three GM107 Maxwell parts).
 *  - Series whose split cannot be determined from the heading are LEFT OUT
 *    entirely rather than mapped approximately. The GeForce 600 line mixed
 *    Kepler with rebadged Fermi across the low end, and guessing which is
 *    which would write a false architecture and then derive false capability
 *    flags from it. Absent is recoverable; wrong is not.
 *
 * The AMD and Intel entries are here for the same reason — their pages head
 * sections with "Radeon RX 6000 series" just as often. AMD's R9/R7 200 and 300
 * lines are omitted on the same rebadge grounds as GeForce 600.
 */
interface SeriesRule {
  re: RegExp;
  architecture: string;
  /** Members of the series built on different silicon, matched against the model name. */
  exceptions?: { re: RegExp; architecture: string }[];
}

const SERIES_ARCH: SeriesRule[] = [
  // Nvidia. Newest first so "GeForce RTX 50" cannot be caught by a looser rule.
  { re: /\bgeforce\b[^|]*\b(rtx\s*)?50(00)?\s*(series)?\b/i, architecture: 'Blackwell' },
  { re: /\bgeforce\b[^|]*\b(rtx\s*)?40(00)?\s*(series)?\b/i, architecture: 'Ada Lovelace' },
  { re: /\bgeforce\b[^|]*\b(rtx\s*)?30(00)?\s*(series)?\b/i, architecture: 'Ampere' },
  { re: /\bgeforce\b[^|]*\b(rtx\s*)?20(00)?\s*(series)?\b/i, architecture: 'Turing' },
  { re: /\bgeforce\b[^|]*\b(gtx\s*)?16(00)?\s*(series)?\b/i, architecture: 'Turing' },
  { re: /\bgeforce\b[^|]*\b10(00)?\s*(series)?\b/i, architecture: 'Pascal' },
  { re: /\bgeforce\b[^|]*\b900\s*(series)?\b/i, architecture: 'Maxwell 2' },
  {
    re: /\bgeforce\b[^|]*\b700\s*(series)?\b/i,
    architecture: 'Kepler',
    // GM107: the only Maxwell parts to ship under a 700-series name.
    exceptions: [{ re: /\bgtx\s*7(45|50)\b/i, architecture: 'Maxwell 1' }],
  },
  { re: /\bgeforce\b[^|]*\b500\s*(series)?\b/i, architecture: 'Fermi' },
  { re: /\bgeforce\b[^|]*\b400\s*(series)?\b/i, architecture: 'Fermi' },

  // AMD
  { re: /\bradeon\b[^|]*\brx\s*9000\s*(series)?\b/i, architecture: 'RDNA 4' },
  { re: /\bradeon\b[^|]*\brx\s*7000\s*(series)?\b/i, architecture: 'RDNA 3' },
  { re: /\bradeon\b[^|]*\brx\s*6000\s*(series)?\b/i, architecture: 'RDNA 2' },
  { re: /\bradeon\b[^|]*\brx\s*5000\s*(series)?\b/i, architecture: 'RDNA 1' },
  { re: /\bradeon\b[^|]*\brx\s*[45]00\s*(series)?\b/i, architecture: 'GCN 4.0' },

  // Intel
  { re: /\barc\b[^|]*\bb-?\s*series\b|\barc\s*b\d/i, architecture: 'Xe2-HPG (Battlemage)' },
  { re: /\barc\b[^|]*\ba-?\s*series\b|\barc\s*a\d/i, architecture: 'Xe-HPG (Alchemist)' },
];

/**
 * Architecture implied by a marketing-series heading, or null.
 *
 * `modelName` is optional and only consulted to apply a series' exceptions, so
 * calling this without one is safe — it just cannot catch a mixed-silicon part.
 */
export function architectureFromSeries(heading: string | undefined | null, modelName?: string): string | null {
  if (!heading) return null;
  const h = cleanCellText(heading);
  for (const rule of SERIES_ARCH) {
    if (!rule.re.test(h)) continue;
    if (modelName) {
      for (const ex of rule.exceptions ?? []) if (ex.re.test(modelName)) return ex.architecture;
    }
    return rule.architecture;
  }
  return null;
}

const MESH_SHADER_ARCHS = new Set([
  'Turing', 'Ampere', 'Ada Lovelace', 'Blackwell',
  'RDNA 2', 'RDNA 3', 'RDNA 3.5', 'RDNA 4',
  'Xe-HPG (Alchemist)', 'Xe2-HPG (Battlemage)',
]);
const DX12_1_ARCHS = new Set(['Maxwell 2', 'Pascal', 'Volta', 'RDNA 1', 'GCN 5 (Vega)', 'GCN 5.0']);
const DX12_0_ARCHS = new Set(['GCN 2.0', 'GCN 3.0', 'GCN 4.0', 'Maxwell 1']);
const DX11_ARCHS = new Set(['Fermi', 'Kepler', 'GCN 1.0']);

function upscalingFor(vendor: Vendor, arch: string): UpscalingTech[] {
  if (DX11_ARCHS.has(arch)) return [];
  if (vendor === 'nvidia') return MESH_SHADER_ARCHS.has(arch) ? ['dlss', 'fsr', 'xess', 'tsr'] : ['fsr', 'xess', 'tsr'];
  if (vendor === 'intel') return ['xess', 'fsr'];
  return ['fsr'];
}

/**
 * Capability block derived from the architecture, per agents/BRIEF.md.
 * Returns undefined for an architecture not in the table — an unknown part gets
 * no caps and is rejected by the reconciler, which is the honest outcome.
 */
export function deriveGpuCaps(architecture: string | undefined, vendor: Vendor): GpuCapabilities | undefined {
  if (!architecture) return undefined;
  const known =
    MESH_SHADER_ARCHS.has(architecture) ||
    DX12_1_ARCHS.has(architecture) ||
    DX12_0_ARCHS.has(architecture) ||
    DX11_ARCHS.has(architecture);
  if (!known) return undefined;

  const modern = MESH_SHADER_ARCHS.has(architecture);
  const dxFeatureLevel = modern ? '12_2' : DX12_1_ARCHS.has(architecture) ? '12_1' : DX12_0_ARCHS.has(architecture) ? '12_0' : '11_0';
  const shaderModel = modern ? '6_7' : dxFeatureLevel === '11_0' ? '5_1' : '6_5';
  return {
    meshShaders: modern,
    rayTracing: modern,
    dxFeatureLevel,
    shaderModel,
    upscaling: upscalingFor(vendor, architecture),
  };
}

const CPU_AVX512 = new Set(['Zen 4', 'Zen 5', 'Cypress Cove', 'Skylake-X', 'Cascade Lake']);
const CPU_NO_AVX2 = new Set(['Bulldozer', 'Piledriver', 'Steamroller', 'Sandy Bridge', 'Ivy Bridge']);
const CPU_HYBRID = new Set(['Alder Lake', 'Raptor Lake', 'Meteor Lake', 'Arrow Lake']);
const CPU_KNOWN_ARCHS = new Set([
  ...CPU_AVX512, ...CPU_NO_AVX2, ...CPU_HYBRID,
  'Excavator', 'Zen', 'Zen+', 'Zen 2', 'Zen 3', 'Haswell', 'Broadwell', 'Skylake', 'Kaby Lake', 'Coffee Lake', 'Comet Lake',
]);

export function deriveCpuCaps(architecture: string | undefined, eCores?: number | null): CpuCapabilities | undefined {
  if (!architecture || !CPU_KNOWN_ARCHS.has(architecture)) return undefined;
  return {
    avx2: !CPU_NO_AVX2.has(architecture),
    avx512: CPU_AVX512.has(architecture),
    hybrid: CPU_HYBRID.has(architecture) || (eCores != null && eCores > 0),
  };
}

/** Coarse driver-support class, per the BRIEF's generation rules. */
export function deriveDriverStatus(architecture: string | undefined): GpuRecord['driverStatus'] | undefined {
  if (!architecture) return undefined;
  if (DX11_ARCHS.has(architecture) || architecture === 'GCN 2.0' || architecture === 'GCN 3.0') return 'eol';
  if (DX12_1_ARCHS.has(architecture) || architecture === 'GCN 4.0' || architecture === 'Maxwell 1') return 'legacy';
  if (MESH_SHADER_ARCHS.has(architecture)) return 'current';
  return undefined;
}

/** Marketing tier -> segment. Only for tiers the catalogue already uses. */
export function inferSegment(name: string): CpuSegment | undefined {
  const n = name.toLowerCase();
  if (/threadripper|\bxeon\b|\bi\d-\d{4}x\b|core i\d+-\d{4,5}x\b/.test(n)) return 'hedt';
  if (/\bi9\b|\bryzen 9\b/.test(n)) return 'enthusiast';
  if (/\bi7\b|\bryzen 7\b/.test(n)) return 'performance';
  if (/\bi5\b|\bryzen 5\b/.test(n)) return 'mainstream';
  if (/\bi3\b|\bryzen 3\b|\bpentium\b|\bceleron\b|\bathlon\b/.test(n)) return 'entry';
  return undefined;
}

// ---------------------------------------------------------------------------
// Composite-cell parsers
// ---------------------------------------------------------------------------

/** "8 (16)", "8/16", "6 (12 threads)", "8" -> cores and threads. */
export function parseCoresThreads(text: string): { cores: number | null; threads: number | null } {
  if (isMissing(text)) return { cores: null, threads: null };
  const m = /(\d+)\s*(?:\(|\/|–|-)\s*(\d+)/.exec(text);
  if (m) return { cores: Number(m[1]), threads: Number(m[2]) };
  const n = parseNumeric(text);
  return { cores: n == null ? null : Math.round(n), threads: null };
}

/** "PCIe 4.0 x16", "PCI Express 3.0 ×16" -> generation and lane count. */
export function parsePcieInterface(text: string): { gen: number | null; lanes: number | null } {
  if (isMissing(text)) return { gen: null, lanes: null };
  const g = /pci(?:e|\s*express)?\s*(?:gen\s*)?(\d(?:\.\d)?)/i.exec(text);
  const l = /[x×]\s*(\d{1,2})\b/i.exec(text);
  return {
    gen: g ? Math.floor(Number(g[1])) : null,
    lanes: l ? Number(l[1]) : null,
  };
}

const CHANNEL_WORDS: Record<string, 1 | 2 | 4 | 8> = { single: 1, dual: 2, quad: 4, octa: 8 };

/** "DDR4-3200 / DDR5-4800 (dual channel)" -> types, official rating, channels. */
export function parseMemorySupport(text: string): {
  types: MemoryType[];
  mts: number | null;
  channels: 1 | 2 | 4 | 8 | null;
} {
  if (isMissing(text)) return { types: [], mts: null, channels: null };
  // LPDDR is a mobile package type, not a socketed memory type for our purposes.
  const t = text.replace(/\blpddr/gi, ' ');
  const types: MemoryType[] = [];
  for (const m of t.matchAll(/\bddr\s?([345])\b/gi)) {
    const type = `DDR${m[1]}` as MemoryType;
    if (!types.includes(type)) types.push(type);
  }
  let mts: number | null = null;
  for (const m of t.matchAll(/\bddr\s?[345][\s-]?(\d{3,5})\b/gi)) {
    const v = Number(m[1]);
    if (mts == null || v > mts) mts = v;
  }
  let channels: 1 | 2 | 4 | 8 | null = null;
  const cw = /\b(single|dual|quad|octa)[- ]?channel\b/i.exec(t);
  if (cw) channels = CHANNEL_WORDS[cw[1].toLowerCase()];
  else {
    const cn = /\b([1248])\s*[- ]?channels?\b/i.exec(t);
    if (cn) channels = Number(cn[1]) as 1 | 2 | 4 | 8;
  }
  return { types, mts, channels };
}

/** FP32 throughput. Sources quote GFLOPS as often as TFLOPS. */
export function parseTflops(text: string): number | null {
  if (isMissing(text) || hasAlternatives(text)) return null;
  const n = parseNumeric(text);
  if (n == null) return null;
  if (/\bgflops\b/i.test(text)) return round(n / 1000, 4);
  if (/\btflops\b/i.test(text)) return round(n, 4);
  // Unitless: no consumer part reaches 1000 TFLOPS of FP32, so a big number is
  // GFLOPS. (Reads the column header's unit as stated in the alias table.)
  return n > 1000 ? round(n / 1000, 4) : round(n, 4);
}

// ---------------------------------------------------------------------------
// Row -> record
// ---------------------------------------------------------------------------

/**
 * Read the cell for `field`.
 *
 * `gridToRecords` keys rows by header TEXT, while a ColumnMap holds column
 * INDICES, so translation goes through the row's own key order — which is the
 * column order for every row of one table. (Where a table repeats an identical
 * header, `gridToRecords` collapses the duplicates; this stays consistent for
 * all rows, and `mapColumns` is fed the same collapsed key list.)
 */
export function cellOf(row: Record<string, string>, cols: ColumnMap, field: string): string {
  const idx = cols[field];
  if (idx == null) return '';
  const key = Object.keys(row)[idx];
  if (key === undefined) return '';
  return row[key] ?? '';
}

interface Range {
  min: number;
  max: number;
}

const GPU_RANGES: Record<string, Range> = {
  shaders: { min: 8, max: 65536 },
  baseClockMHz: { min: 100, max: 8000 },
  boostClockMHz: { min: 100, max: 8000 },
  vramGB: { min: 0.03, max: 64 },
  memBusBits: { min: 32, max: 8192 },
  memBandwidthGBs: { min: 1, max: 30000 },
  fp32TFLOPS: { min: 0.001, max: 1000 },
  tdpW: { min: 1, max: 1200 },
  processNm: { min: 1, max: 250 },
};

const CPU_RANGES: Record<string, Range> = {
  cores: { min: 1, max: 256 },
  threads: { min: 1, max: 512 },
  pCores: { min: 1, max: 128 },
  eCores: { min: 0, max: 256 },
  baseClockMHz: { min: 300, max: 8000 },
  boostClockMHz: { min: 300, max: 9000 },
  l2CacheMB: { min: 0.03, max: 1024 },
  l3CacheMB: { min: 0.03, max: 2048 },
  tdpW: { min: 1, max: 500 },
  processNm: { min: 1, max: 250 },
};

/**
 * Accept a parsed value only if it is physically plausible. An out-of-range
 * value is a parse failure wearing a number's clothes, so it is dropped and
 * recorded as a conflict rather than written into the record.
 */
function accept(
  value: number | string | null,
  field: string,
  ranges: Record<string, Range>,
  conflicts: Conflict[],
  provenanceId: string,
): number | null {
  if (typeof value !== 'number' || !Number.isFinite(value)) return null;
  const r = ranges[field];
  if (r && (value < r.min || value > r.max)) {
    conflicts.push({ field, value, provenanceId });
    return null;
  }
  return value;
}

const VRAM_TYPE_RE = /^(g?ddr\d[a-z]?|hbm\d?e?|sdram|sgram|lpddr\d[a-z]?)\b/i;

/**
 * Map one parsed row to a partial GPU record.
 *
 * Returns null ONLY when the row has no usable model name — every other
 * shortfall becomes an absent field, so a row that yields an id plus one or two
 * specs still produces a record. Callers must record the null case with a
 * reason (see `parseSpecPage`).
 */
export function rowToGpu(
  row: Record<string, string>,
  cols: ColumnMap,
  ctx: MapContext,
): Partial<GpuRecord> | null {
  const vendor = normaliseVendor(ctx.vendor);
  if (!vendor) return null;
  const provenanceId = ctx.provenanceId ?? 'spec-page';

  const rawName = cellOf(row, cols, 'name').trim();
  if (isMissing(rawName) || isMultiConfigName(rawName)) return null;
  const { base, variant } = splitVariant(rawName);
  if (!base || !/[a-z0-9]/i.test(base)) return null;

  const conflicts: Conflict[] = [];
  const num = (field: string, unit: SpecUnit) =>
    accept(normaliseUnit(cellOf(row, cols, field), unit), field, GPU_RANGES, conflicts, provenanceId);

  const architecture =
    detectArchitecture(cellOf(row, cols, 'architecture')) ??
    detectArchitecture(cellOf(row, cols, 'chip')) ??
    (ctx.architecture ? detectArchitecture(ctx.architecture) ?? ctx.architecture : undefined) ??
    undefined;

  const rec: Partial<GpuRecord> = {
    id: slugify(vendor, base, variant),
    vendor,
    brand: base,
    fullName: fullNameFor(vendor, rawName),
    // Page selection decides this: the desktop lists carry desktop parts. A
    // mobile marker in the name overrides it; nothing else is inferred.
    formFactor: /\b(mobile|laptop|max-?q|notebook)\b/i.test(rawName) ? 'mobile' : 'desktop',
  };
  if (variant) rec.variant = variant;
  if (architecture) rec.architecture = architecture;

  // The code-name column is kept verbatim even when it also identifies the
  // architecture ("Vega 10"): it is what the page says, and `architecture` above
  // has already taken the canonical form.
  const chip = cleanCellText(cellOf(row, cols, 'chip'));
  if (chip && !isMissing(chip)) rec.chip = chip;

  const launch = normaliseUnit(cellOf(row, cols, 'launchDate'), 'date');
  if (typeof launch === 'string') rec.launchDate = launch;

  const shaders = num('shaders', 'count');
  if (shaders != null) rec.shaders = shaders;
  const baseClock = num('baseClockMHz', 'MHz');
  if (baseClock != null) rec.baseClockMHz = baseClock;
  const boostClock = num('boostClockMHz', 'MHz');
  if (boostClock != null) rec.boostClockMHz = boostClock;

  let vram = num('vramGB', 'GB');
  // The name itself states the size for split SKUs ("GTX 1060 6 GB"); using it
  // restates the source, it does not invent anything.
  if (vram == null && variant && /^(\d+(?:\.\d+)?)(GB|MB|TB)$/i.test(variant)) {
    const v = normaliseUnit(variant.replace(/([A-Za-z]+)$/, ' $1'), 'GB');
    vram = accept(v, 'vramGB', GPU_RANGES, conflicts, provenanceId);
  }
  if (vram != null) rec.vramGB = vram;

  const vramType = cleanCellText(cellOf(row, cols, 'vramType'));
  if (vramType && !isMissing(vramType) && VRAM_TYPE_RE.test(vramType)) {
    rec.vramType = vramType.toUpperCase().split(/[\s,/]+/)[0];
  }

  const bus = num('memBusBits', 'bit');
  if (bus != null) rec.memBusBits = bus;
  let bandwidth = num('memBandwidthGBs', 'GB/s');

  // The reconciler rejects a record whose bus/bandwidth pair implies an
  // impossible memory clock. Dropping the weaker figure keeps the rest of the
  // record and preserves the disagreement as a conflict, rather than losing the
  // whole SKU to one bad cell.
  if (bus != null && bandwidth != null) {
    const impliedMts = (bandwidth * 8 * 1000) / bus;
    if (impliedMts < 500 || impliedMts > 40000) {
      conflicts.push({ field: 'memBandwidthGBs', value: bandwidth, provenanceId });
      bandwidth = null;
    }
  }
  if (bandwidth != null) rec.memBandwidthGBs = bandwidth;

  const statedTflops = accept(parseTflops(cellOf(row, cols, 'fp32TFLOPS')), 'fp32TFLOPS', GPU_RANGES, conflicts, provenanceId);
  if (statedTflops != null) {
    if (rec.shaders != null && rec.boostClockMHz != null) {
      const calc = (rec.shaders * rec.boostClockMHz * 2) / 1e6;
      // Same 8% tolerance the reconciler enforces.
      if (Math.abs(calc - statedTflops) / statedTflops > 0.08) {
        conflicts.push({ field: 'fp32TFLOPS', value: statedTflops, provenanceId });
      } else {
        rec.fp32TFLOPS = statedTflops;
      }
    } else {
      rec.fp32TFLOPS = statedTflops;
    }
  }

  const tdp = num('tdpW', 'W');
  if (tdp != null) rec.tdpW = tdp;
  const nm = num('processNm', 'nm');
  if (nm != null) rec.processNm = nm;

  const pcie = parsePcieInterface(cellOf(row, cols, 'pcieInterface'));
  if (pcie.gen != null && pcie.gen >= 1 && pcie.gen <= 7) rec.pcieGen = pcie.gen;
  if (pcie.lanes != null && [1, 4, 8, 16].includes(pcie.lanes)) rec.pcieLanes = pcie.lanes;

  const caps = deriveGpuCaps(rec.architecture, vendor);
  if (caps) rec.caps = caps;
  const driverStatus = deriveDriverStatus(rec.architecture);
  if (driverStatus) rec.driverStatus = driverStatus;

  if (conflicts.length) rec._conflicts = conflicts;
  rec._prov = { '*': [provenanceId] };
  return rec;
}

/**
 * Map one parsed row to a partial CPU record. Same contract as `rowToGpu`.
 *
 * NOTE: the reconciler REQUIRES `socket` and a non-empty `memoryType`, and
 * neither is present in most per-row specification tables — they usually live
 * in the section heading or a separate platform table. Records lacking them are
 * still emitted (with the fields absent) so the rejection is visible and
 * attributable rather than silent.
 */
export function rowToCpu(
  row: Record<string, string>,
  cols: ColumnMap,
  ctx: MapContext,
): Partial<CpuRecord> | null {
  const v = normaliseVendor(ctx.vendor);
  if (!v || v === 'nvidia') return null;
  const vendor: CpuRecord['vendor'] = v;
  const provenanceId = ctx.provenanceId ?? 'spec-page';

  const rawName = cellOf(row, cols, 'name').trim();
  if (isMissing(rawName) || isMultiConfigName(rawName)) return null;
  const { base, variant } = splitVariant(rawName);
  if (!base || !/[a-z0-9]/i.test(base)) return null;

  const conflicts: Conflict[] = [];
  const num = (field: string, unit: SpecUnit) =>
    accept(normaliseUnit(cellOf(row, cols, field), unit), field, CPU_RANGES, conflicts, provenanceId);

  const architecture =
    detectArchitecture(cellOf(row, cols, 'architecture')) ??
    detectArchitecture(cellOf(row, cols, 'codename')) ??
    (ctx.architecture ? detectArchitecture(ctx.architecture) ?? ctx.architecture : undefined) ??
    undefined;

  const rec: Partial<CpuRecord> = {
    id: slugify(vendor, base, variant),
    vendor,
    brand: base,
    fullName: fullNameFor(vendor, rawName),
  };
  if (variant) rec.variant = variant;
  if (architecture) rec.architecture = architecture;

  const codename = cleanCellText(cellOf(row, cols, 'codename'));
  if (codename && !isMissing(codename)) rec.codename = codename;

  const launch = normaliseUnit(cellOf(row, cols, 'launchDate'), 'date');
  if (typeof launch === 'string') rec.launchDate = launch;

  const segment = inferSegment(rawName);
  if (segment) rec.segment = segment;

  // Cores and threads: three possible shapes — separate columns, a combined
  // "Cores (threads)" column, or a hybrid P/E split.
  let cores = num('cores', 'count');
  let threads = num('threads', 'count');
  if (cols.coresThreads != null) {
    const ct = parseCoresThreads(cellOf(row, cols, 'coresThreads'));
    if (cores == null) cores = accept(ct.cores, 'cores', CPU_RANGES, conflicts, provenanceId);
    if (threads == null) threads = accept(ct.threads, 'threads', CPU_RANGES, conflicts, provenanceId);
  }
  const pCores = num('pCores', 'count');
  const eCores = num('eCores', 'count');
  if (pCores != null) rec.pCores = pCores;
  if (eCores != null) rec.eCores = eCores;
  if (cores == null && pCores != null && eCores != null) cores = pCores + eCores;
  if (threads == null && pCores != null && eCores != null) threads = pCores * 2 + eCores;

  // threads < cores is a transposed or misread pair; keep neither.
  if (cores != null && threads != null && threads < cores) {
    conflicts.push({ field: 'threads', value: threads, provenanceId });
    threads = null;
  }
  if (cores != null) rec.cores = cores;
  if (threads != null) rec.threads = threads;

  const baseClock = num('baseClockMHz', 'MHz');
  if (baseClock != null) rec.baseClockMHz = baseClock;
  const boostClock = num('boostClockMHz', 'MHz');
  if (boostClock != null) rec.boostClockMHz = boostClock;

  const l2 = num('l2CacheMB', 'MB');
  if (l2 != null) rec.l2CacheMB = l2;
  const l3 = num('l3CacheMB', 'MB');
  if (l3 != null) rec.l3CacheMB = l3;

  const tdp = num('tdpW', 'W');
  if (tdp != null) rec.tdpW = tdp;
  const nm = num('processNm', 'nm');
  if (nm != null) rec.processNm = nm;

  // X3D is stated by the name, and the reconciler cross-checks exactly that.
  rec.vcache = /x3d/i.test(rawName);

  // Socket and memory: the row's own column first, then whatever the section
  // headings above this table established. Both are REQUIRED by the reconciler
  // and neither is usually in the row, so without the inherited fallback every
  // record from a real page is rejected. Anything inherited is marked as such
  // in `_prov` so a wrong section map is traceable rather than mysterious.
  const inherited: string[] = [];
  const socketCell = cleanCellText(cellOf(row, cols, 'socket'));
  const socket = extractSocket(socketCell) ?? ctx.socket ?? null;
  if (socket) {
    rec.socket = socket;
    if (!extractSocket(socketCell)) inherited.push('socket');
  }

  const mem = parseMemorySupport(cellOf(row, cols, 'memorySupport'));
  if (mem.types.length) rec.memoryType = mem.types;
  else if (ctx.memoryType?.length) {
    rec.memoryType = [...ctx.memoryType];
    inherited.push('memoryType');
  }
  if (mem.mts != null && mem.mts >= 800 && mem.mts <= 20000) rec.officialMemMTs = mem.mts;
  const channelsCell = normaliseUnit(cellOf(row, cols, 'maxMemChannels'), 'count');
  const channels =
    mem.channels ??
    (typeof channelsCell === 'number' && [1, 2, 4, 8].includes(channelsCell) ? (channelsCell as 1 | 2 | 4 | 8) : null);
  if (channels != null) rec.maxMemChannels = channels;
  else if (ctx.maxMemChannels != null) {
    rec.maxMemChannels = ctx.maxMemChannels;
    inherited.push('maxMemChannels');
  }

  const caps = deriveCpuCaps(rec.architecture, rec.eCores);
  if (caps) rec.caps = caps;

  if (conflicts.length) rec._conflicts = conflicts;
  rec._prov = { '*': [provenanceId] };
  for (const f of inherited) {
    rec._prov[f] = [`${provenanceId}#section:${ctx.inheritedFrom?.[f] ?? 'heading'}`];
  }
  return rec;
}

/** Recognise a socket designator anywhere in a string. */
export function extractSocket(text: string): string | null {
  if (!text || isMissing(text)) return null;
  const m =
    /\b(LGA\s?\d{3,4}(?:-v\d)?|AM[2345]\+?|FM2\+?|FM1|TR4|sTRX4|sWRX8|sTR5|BGA\s?\d{3,4})\b/i.exec(text);
  if (!m) return null;
  return m[1].replace(/\s+/g, '').toUpperCase().replace(/^LGA/, 'LGA');
}

/**
 * Context a table inherits from the headings ABOVE it.
 *
 * The reconciler requires `socket` and a non-empty `memoryType` on every CPU
 * record, and neither appears in the per-row columns of a real specification
 * page — they live in the section heading ("Socket AM4 (DDR4)"), because
 * repeating them on every row of a 60-row table would be absurd. Reading only
 * the row therefore rejected every CPU record ever parsed.
 *
 * The chain matters as much as the reading. Pages nest as
 *
 *     h2  Socket AM4
 *       h3  Ryzen 5000 series
 *         table
 *
 * so the nearest heading is the one WITHOUT the socket. Taking only the closest
 * heading — which is what the architecture lookup did — misses the platform
 * every time. `headingChain` walks back through the ancestors instead, and
 * `sectionContext` merges them outermost-first so a more specific inner heading
 * overrides a general outer one.
 */
export interface SectionContext {
  architecture?: string;
  socket?: string;
  memoryType?: MemoryType[];
  maxMemChannels?: 1 | 2 | 4 | 8;
  /** Which heading each value was read from. Reported, not guessed at later. */
  from: Record<string, string>;
}

export interface Heading {
  at: number;
  level: number;
  title: string;
}

/**
 * The heading ancestors of a position: for each level, the last heading of that
 * level before `at`, discarding any that a later shallower heading closed.
 *
 * Without the closing rule an h3 from a previous h2's subtree would leak into
 * the next section — which is how a page's second platform silently inherits
 * the first platform's socket.
 */
export function headingChain(headings: Heading[], at: number): Heading[] {
  const stack: Heading[] = [];
  for (const h of headings) {
    if (h.at >= at) break;
    while (stack.length && stack[stack.length - 1].level >= h.level) stack.pop();
    stack.push(h);
  }
  return stack;
}

/** Merge a heading chain into inherited context, outermost first. */
export function sectionContext(chain: Heading[]): SectionContext {
  const ctx: SectionContext = { from: {} };
  for (const h of chain) {
    const arch = detectArchitecture(h.title) ?? architectureFromSeries(h.title);
    if (arch) {
      ctx.architecture = arch;
      ctx.from.architecture = h.title;
    }
    const socket = extractSocket(h.title);
    if (socket) {
      ctx.socket = socket;
      ctx.from.socket = h.title;
    }
    // Only types and channels are taken from a heading. The MT/s figure is not:
    // "Ryzen 7000 series" carries a four-digit number that has nothing to do
    // with memory, and parseMemorySupport only binds a rating that is attached
    // to a DDR token — but relying on that here would be relying on a detail of
    // another function rather than on this one being right.
    const mem = parseMemorySupport(h.title);
    if (mem.types.length) {
      ctx.memoryType = mem.types;
      ctx.from.memoryType = h.title;
    }
    if (mem.channels != null) {
      ctx.maxMemChannels = mem.channels;
      ctx.from.maxMemChannels = h.title;
    }
  }
  return ctx;
}

// ---------------------------------------------------------------------------
// Page pipeline
// ---------------------------------------------------------------------------

/**
 * Cached pages come from the MediaWiki `action=parse&format=json` endpoint, so
 * the HTML is wrapped in JSON at `parse.text['*']`. Raw HTML passes through.
 */
export function unwrapMediaWiki(raw: string): string {
  const trimmed = (raw ?? '').trim();
  if (!trimmed.startsWith('{')) return raw;
  try {
    const j = JSON.parse(trimmed) as { parse?: { text?: string | { '*'?: string } } };
    const text = j.parse?.text;
    if (typeof text === 'string') return text;
    if (text && typeof text === 'object' && typeof text['*'] === 'string') return text['*'];
  } catch {
    // Not JSON after all — fall through and treat it as markup.
  }
  return raw;
}

/**
 * Section headings with their offsets AND levels. The level is what lets
 * `headingChain` tell an ancestor from a sibling's child; without it the only
 * available heading is the nearest one, which is rarely the one carrying the
 * platform.
 */
function headingIndex(html: string): Heading[] {
  const out: Heading[] = [];
  const re = /<h([2-6])\b[^>]*>([\s\S]*?)<\/h\1>/gi;
  let m: RegExpExecArray | null;
  while ((m = re.exec(html))) {
    const title = cleanCellText(m[2]).replace(/\[\s*edit\s*\]/gi, '').trim();
    if (title) out.push({ at: m.index, level: Number(m[1]), title });
  }
  return out;
}

/** A section row expanded by colspan repeats one value across the whole row. */
function isUniformRow(row: Record<string, string>): boolean {
  const values = Object.values(row);
  return values.length > 2 && new Set(values).size === 1;
}

const truncate = (s: string, n = 120) => (s.length > n ? `${s.slice(0, n - 1)}…` : s);

const rowLabel = (row: Record<string, string>, cols: ColumnMap) => {
  const name = cellOf(row, cols, 'name').trim();
  if (name) return truncate(name);
  return truncate(Object.values(row).filter(Boolean).join(' | '));
};

/**
 * Full pipeline: cached page -> candidate records plus a report of what failed
 * and why.
 *
 * Every table is either used or reported with a reason; every row is either
 * turned into a record or reported with a reason. Nothing is dropped silently —
 * on a source this awkward, an unexplained gap is indistinguishable from a bug.
 */
export function parseSpecPage(
  html: string,
  kind: 'gpu' | 'cpu',
  ctx: MapContext,
): SpecPageReport {
  const doc = unwrapMediaWiki(html ?? '');
  const tables = extractTables(doc);
  const headings = headingIndex(doc);

  const skipped: SkippedRow[] = [];
  const notes: string[] = [];
  const tableReports: NonNullable<SpecPageReport['tables']> = [];
  const byId = new Map<string, Partial<GpuRecord | CpuRecord>>();
  const order: string[] = [];
  const expected = kind === 'gpu' ? [...GPU_FIELDS] : [...CPU_FIELDS];
  const everFound = new Set<string>();
  let best: { cols: ColumnMap; accepted: number } = { cols: {}, accepted: -1 };

  let cursor = 0;
  for (let t = 0; t < tables.length; t++) {
    const tableHtml = tables[t];
    const at = doc.indexOf(tableHtml, cursor);
    if (at >= 0) cursor = at + tableHtml.length;
    const chain = headingChain(headings, at < 0 ? Number.MAX_SAFE_INTEGER : at);
    const inherited = sectionContext(chain);
    // Reported as the full path, not just the nearest heading: "Socket AM4 >
    // Ryzen 5000 series" is what actually determined this table's context, and
    // showing only the leaf hides where a wrong socket came from.
    const section = chain[chain.length - 1]?.title;
    const sectionPath = chain.length ? chain.map((h) => h.title).join(' > ') : undefined;

    const rows = gridToRecords(expandTable(tableHtml));
    if (!rows.length) {
      tableReports.push({ index: t, headers: [], dataRows: 0, accepted: 0, used: false, reason: 'no data rows', section, sectionPath });
      continue;
    }
    const headers = Object.keys(rows[0]);
    const cols = mapColumns(headers, kind);
    for (const f of Object.keys(cols)) everFound.add(f);

    if (cols.name == null) {
      const reason = `no model-name column identified (headers: ${truncate(headers.join(' | '), 160)})`;
      tableReports.push({ index: t, headers, dataRows: rows.length, accepted: 0, used: false, reason, section, sectionPath });
      skipped.push({ row: `table ${t}${section ? ` (${section})` : ''}`, reason });
      continue;
    }
    if (Object.keys(cols).length < 3) {
      const reason = `only ${Object.keys(cols).length} identifiable column(s); not a specification table`;
      tableReports.push({ index: t, headers, dataRows: rows.length, accepted: 0, used: false, reason, section, sectionPath });
      skipped.push({ row: `table ${t}${section ? ` (${section})` : ''}`, reason });
      continue;
    }

    const architecture = inherited.architecture ?? ctx.architecture;
    const rowCtx: MapContext = {
      ...ctx,
      architecture,
      socket: inherited.socket ?? ctx.socket,
      memoryType: inherited.memoryType ?? ctx.memoryType,
      maxMemChannels: inherited.maxMemChannels ?? ctx.maxMemChannels,
      inheritedFrom: inherited.from,
    };
    let accepted = 0;

    for (const row of rows) {
      if (isUniformRow(row)) {
        skipped.push({ row: rowLabel(row, cols), reason: 'section divider row, not a part' });
        continue;
      }
      const rawName = cellOf(row, cols, 'name').trim();
      if (isMultiConfigName(rawName)) {
        skipped.push({
          row: rowLabel(row, cols),
          reason: 'name covers two configurations in one row; needs a manual split into separate SKUs',
        });
        continue;
      }
      const rec = kind === 'gpu' ? rowToGpu(row, cols, rowCtx) : rowToCpu(row, cols, rowCtx);
      if (!rec || !rec.id) {
        skipped.push({ row: rowLabel(row, cols), reason: 'no usable model name in the identified name column' });
        continue;
      }
      const existing = byId.get(rec.id);
      if (existing) {
        // Wikipedia splits one part's specs across sibling tables. Fill-only
        // merge: an already-populated field is never overwritten from a second
        // table, and the merge is reported.
        let filled = 0;
        for (const [k, val] of Object.entries(rec)) {
          if (k === '_prov' || k === '_conflicts') continue;
          if ((existing as Record<string, unknown>)[k] === undefined && val !== undefined) {
            (existing as Record<string, unknown>)[k] = val;
            filled++;
          }
        }
        notes.push(`duplicate id ${rec.id} in table ${t}: merged ${filled} field(s) into the earlier row`);
        continue;
      }
      byId.set(rec.id, rec);
      order.push(rec.id);
      accepted++;
    }

    tableReports.push({ index: t, headers, dataRows: rows.length, accepted, used: accepted > 0, section, sectionPath, inherited: Object.keys(inherited.from).length ? inherited.from : undefined });
    if (accepted > best.accepted) best = { cols, accepted };
  }

  // A composite column answers for the fields it contains: reporting "cores" as
  // missing when a "Cores (threads)" column was found would send the next
  // maintainer looking for a column that does not exist.
  const COMPOSITE_COVERS: Record<string, string[]> = {
    coresThreads: ['cores', 'threads'],
    memorySupport: ['memoryType', 'officialMemMTs'],
    pcieInterface: ['pcieGen', 'pcieLanes'],
  };
  for (const [composite, covered] of Object.entries(COMPOSITE_COVERS)) {
    if (everFound.has(composite)) for (const f of covered) everFound.add(f);
  }

  const conflicted = [...byId.values()].filter((r) => (r as { _conflicts?: unknown[] })._conflicts?.length).length;
  if (conflicted) {
    notes.push(`${conflicted} record(s) carry _conflicts: a stated value was implausible or inconsistent and was dropped rather than written`);
  }

  return {
    records: order.map((id) => byId.get(id)!),
    skipped,
    columnsFound: best.cols,
    columnsMissing: expected.filter((f) => !everFound.has(f)),
    tables: tableReports,
    notes,
  };
}
