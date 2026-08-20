/**
 * Tests for the specification-table -> record mapping layer.
 *
 * IMPORTANT: the HTML fixtures under `tests/fixtures/` are SYNTHETIC. Wikipedia
 * is egress-blocked here, so they imitate the real table shapes from memory
 * rather than sampling them. A green run therefore proves the mapper handles
 * the shapes we BELIEVE the sources use — colspan header groups, rowspan
 * merges, footnote superscripts, in-cell units, ranges and "N/A" cells — not
 * that it handles the real pages. The first real cached page will need another
 * pass over the alias tables; see agents/log/spec-mapper.md.
 */
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';
import type { CpuRecord, GpuRecord } from '../src/core/types.ts';
import {
  architectureFromSeries,
  cellOf,
  deriveGpuCaps,
  detectArchitecture,
  extractSocket,
  headingChain,
  sectionContext,
  isMissing,
  mapColumns,
  normaliseUnit,
  parseCoresThreads,
  parseMemorySupport,
  parsePcieInterface,
  parseSpecPage,
  parseTflops,
  rowToGpu,
  slugify,
  splitVariant,
  unwrapMediaWiki,
} from '../src/parse/spec-mapper.ts';

const fixture = (name: string) =>
  readFileSync(fileURLToPath(new URL(`./fixtures/${name}`, import.meta.url)), 'utf8');

const NVIDIA_HTML = fixture('nvidia-gpu-list.synthetic.html');
const AMD_GPU_HTML = fixture('amd-gpu-list.synthetic.html');
const INTEL_CPU_HTML = fixture('intel-cpu-list.synthetic.html');
const AMD_CPU_HTML = fixture('amd-cpu-list.synthetic.html');

// ---------------------------------------------------------------------------
// Column identification
// ---------------------------------------------------------------------------

describe('mapColumns — GPU headers', () => {
  it('reads a two-row colspan group as compound headers', () => {
    const headers = [
      'Model',
      'Launch',
      'Code name',
      'Fab (nm)',
      'Core config',
      'Clock speeds Base (MHz)',
      'Clock speeds Boost (MHz)',
      'Memory Size',
      'Memory Bandwidth (GB/s)',
      'Memory Bus type',
      'Memory Bus width (bit)',
      'TDP (Watts)',
    ];
    expect(mapColumns(headers, 'gpu')).toEqual({
      name: 0,
      launchDate: 1,
      chip: 2,
      processNm: 3,
      shaders: 4,
      baseClockMHz: 5,
      boostClockMHz: 6,
      vramGB: 7,
      memBandwidthGBs: 8,
      vramType: 9,
      memBusBits: 10,
      tdpW: 11,
    });
  });

  it.each([
    ['Core config', 4],
    ['Shading units', 4],
    ['Stream processors', 4],
    ['CUDA cores', 4],
    ['Unified shaders', 4],
  ])('maps the shader-count alias %s', (header) => {
    const cols = mapColumns(['Model', 'Launch', 'Base clock', 'Boost clock', header], 'gpu');
    expect(cols.shaders).toBe(4);
  });

  it.each(['TDP', 'TBP', 'TGP', 'Board power', 'Power (W)'])('maps the power alias %s to tdpW', (header) => {
    expect(mapColumns(['Model', header], 'gpu').tdpW).toBe(1);
  });

  it.each(['Launch', 'Release date', 'Released', 'Availability', 'Introduction'])(
    'maps the date alias %s to launchDate',
    (header) => {
      expect(mapColumns(['Model', header], 'gpu').launchDate).toBe(1);
    },
  );

  it('does not let a memory clock steal the core clock column', () => {
    const cols = mapColumns(['Model', 'Memory clock (MHz)', 'Base clock (MHz)'], 'gpu');
    expect(cols.baseClockMHz).toBe(2);
  });

  it('separates bus type from bus width', () => {
    const cols = mapColumns(['Model', 'Memory Bus type', 'Memory Bus width (bit)'], 'gpu');
    expect(cols.vramType).toBe(1);
    expect(cols.memBusBits).toBe(2);
  });

  it('does not read "Processing power" as the process node', () => {
    const cols = mapColumns(['Model', 'Processing power (GFLOPS) Single precision', 'Fab (nm)'], 'gpu');
    expect(cols.fp32TFLOPS).toBe(1);
    expect(cols.processNm).toBe(2);
  });

  it('never assigns one column to two fields', () => {
    const headers = ['Model', 'Memory Size', 'Memory Bandwidth (GB/s)', 'Memory Bus width (bit)'];
    const cols = mapColumns(headers, 'gpu');
    const used = Object.values(cols);
    expect(new Set(used).size).toBe(used.length);
  });

  it('omits fields the table has no column for, so callers can report the gap', () => {
    const cols = mapColumns(['Model', 'Launch', 'TDP'], 'gpu');
    expect(cols.name).toBe(0);
    expect(cols.shaders).toBeUndefined();
    expect(cols.vramGB).toBeUndefined();
    expect(cols.memBandwidthGBs).toBeUndefined();
  });

  it('finds no name column in a legend table', () => {
    expect(mapColumns(['Colour', 'Meaning'], 'gpu').name).toBeUndefined();
  });
});

describe('mapColumns — CPU headers', () => {
  it('identifies a combined cores/threads column without also claiming cores', () => {
    const cols = mapColumns(['Model', 'Cores (threads)', 'TDP'], 'cpu');
    expect(cols.coresThreads).toBe(1);
    expect(cols.cores).toBeUndefined();
  });

  it('accepts the slash spelling of the same column', () => {
    expect(mapColumns(['Model', 'Cores / threads'], 'cpu').coresThreads).toBe(1);
  });

  it('keeps P-cores and E-cores out of the plain core-count slot', () => {
    const cols = mapColumns(['Model', 'Cores', 'P-cores', 'E-cores'], 'cpu');
    expect(cols).toMatchObject({ cores: 1, pCores: 2, eCores: 3 });
  });

  it.each([
    ['Max turbo frequency', 'boostClockMHz'],
    ['Turbo boost', 'boostClockMHz'],
    ['Base frequency', 'baseClockMHz'],
    ['L3 cache', 'l3CacheMB'],
    ['L2 cache', 'l2CacheMB'],
    ['Socket', 'socket'],
    ['Memory', 'memorySupport'],
    ['Processor base power', 'tdpW'],
  ])('maps %s to %s', (header, field) => {
    expect(mapColumns(['Model', header], 'cpu')[field]).toBe(1);
  });

  it('does not treat "Memory channels" as memory support', () => {
    const cols = mapColumns(['Model', 'Memory channels', 'Memory'], 'cpu');
    expect(cols.maxMemChannels).toBe(1);
    expect(cols.memorySupport).toBe(2);
  });
});

// ---------------------------------------------------------------------------
// Unit normalisation — every unit, plus the never-fabricate rule
// ---------------------------------------------------------------------------

describe('normaliseUnit', () => {
  it('MHz: keeps MHz, converts GHz, strips separators, takes a range upper bound', () => {
    expect(normaliseUnit('1,506 MHz', 'MHz')).toBe(1506);
    expect(normaliseUnit('1.5 GHz', 'MHz')).toBe(1500);
    expect(normaliseUnit('1815–2105 MHz', 'MHz')).toBe(2105);
    expect(normaliseUnit('1506', 'MHz')).toBe(1506);
    // Unitless and small: the column header said GHz.
    expect(normaliseUnit('4.7', 'MHz')).toBe(4700);
  });

  it('GHz: keeps GHz and converts MHz', () => {
    expect(normaliseUnit('5.2 GHz', 'GHz')).toBe(5.2);
    expect(normaliseUnit('3,200 MHz', 'GHz')).toBe(3.2);
    expect(normaliseUnit('4.5', 'GHz')).toBe(4.5);
  });

  it('GB: converts MB and TB, and reads a unitless megabyte figure', () => {
    expect(normaliseUnit('8 GB', 'GB')).toBe(8);
    expect(normaliseUnit('768 MB', 'GB')).toBe(0.75);
    expect(normaliseUnit('8192 MB', 'GB')).toBe(8);
    expect(normaliseUnit('1.5 GB', 'GB')).toBe(1.5);
    expect(normaliseUnit('512', 'GB')).toBe(0.5);
  });

  it('MB: converts KB and GB, and totals a per-unit cache quote', () => {
    expect(normaliseUnit('32 MB', 'MB')).toBe(32);
    expect(normaliseUnit('512 KB', 'MB')).toBe(0.5);
    expect(normaliseUnit('1 GB', 'MB')).toBe(1024);
    expect(normaliseUnit('4 × 512 KB', 'MB')).toBe(2);
    expect(normaliseUnit('9.5 MB', 'MB')).toBe(9.5);
  });

  it('bit: reads a hyphenated width and a multiplied stack', () => {
    expect(normaliseUnit('256-bit', 'bit')).toBe(256);
    expect(normaliseUnit('192 bit', 'bit')).toBe(192);
    expect(normaliseUnit('4 × 1024-bit', 'bit')).toBe(4096);
  });

  it('W: reads watts and ignores a parenthesised second figure', () => {
    expect(normaliseUnit('180 W', 'W')).toBe(180);
    expect(normaliseUnit('180W', 'W')).toBe(180);
    expect(normaliseUnit('65 W (88 W PPT)', 'W')).toBe(65);
    expect(normaliseUnit('45–65 W', 'W')).toBe(65);
  });

  it('nm: reads a node figure but refuses Intel marketing node names', () => {
    expect(normaliseUnit('TSMC 5 nm (4N)', 'nm')).toBe(5);
    expect(normaliseUnit('14 nm++', 'nm')).toBe(14);
    expect(normaliseUnit('TSMC N7', 'nm')).toBe(7);
    // "Intel 7" is a 10 nm-class process — the digit is a brand, not a length.
    expect(normaliseUnit('Intel 7', 'nm')).toBeNull();
  });

  it('GB/s: reads bandwidth and scales TB/s', () => {
    expect(normaliseUnit('1,008 GB/s', 'GB/s')).toBe(1008);
    expect(normaliseUnit('320.3', 'GB/s')).toBe(320.3);
    expect(normaliseUnit('2 TB/s', 'GB/s')).toBe(2000);
  });

  it('count: reads separated integers and the first field of a core config', () => {
    expect(normaliseUnit('1,280', 'count')).toBe(1280);
    expect(normaliseUnit('2560:160:64', 'count')).toBe(2560);
    expect(normaliseUnit('16', 'count')).toBe(16);
  });

  it('date: normalises the spellings these pages use, degrading rather than guessing', () => {
    expect(normaliseUnit('May 27, 2016', 'date')).toBe('2016-05-27');
    expect(normaliseUnit('27 May 2016', 'date')).toBe('2016-05-27');
    expect(normaliseUnit('2016-05-27', 'date')).toBe('2016-05-27');
    expect(normaliseUnit('October 2011', 'date')).toBe('2011-10');
    expect(normaliseUnit('2016', 'date')).toBe('2016');
    // A quarter names no month, so no month is invented.
    expect(normaliseUnit('Q3 2016', 'date')).toBe('2016');
    expect(normaliseUnit('Never released', 'date')).toBeNull();
  });

  it('refuses to choose between two configurations quoted in one cell', () => {
    expect(normaliseUnit('3 GB or 6 GB', 'GB')).toBeNull();
    expect(normaliseUnit('1506/1683 MHz', 'MHz')).toBeNull();
    // ...but one size with two memory technologies is still one size.
    expect(normaliseUnit('8 GB GDDR6/GDDR6X', 'GB')).toBe(8);
  });

  const UNITS = ['MHz', 'GHz', 'GB', 'MB', 'bit', 'W', 'nm', 'GB/s', 'count', 'date'] as const;
  it.each(['N/A', 'n/a', '?', '??', 'Unknown', 'TBA', 'None', '—', '-', '', '   '])(
    'returns null (never 0) for the missing-value cell %s',
    (cell) => {
      for (const unit of UNITS) {
        const value = normaliseUnit(cell, unit);
        expect(value).toBeNull();
        expect(value).not.toBe(0);
      }
    },
  );

  it('recognises missing-value cells directly', () => {
    expect(isMissing('N/A')).toBe(true);
    expect(isMissing('?')).toBe(true);
    expect(isMissing('Unknown')).toBe(true);
    expect(isMissing('')).toBe(true);
    expect(isMissing('0')).toBe(false);
    expect(isMissing('8 GB')).toBe(false);
  });
});

// ---------------------------------------------------------------------------
// Ids, names and variants
// ---------------------------------------------------------------------------

describe('slugify', () => {
  it('matches the catalogue convention for split-memory SKUs', () => {
    expect(slugify('nvidia', 'GeForce GTX 1060', '6 GB')).toBe('nvidia-geforce-gtx-1060-6gb');
    expect(slugify('nvidia', 'GeForce GTX 1060', '3GB')).toBe('nvidia-geforce-gtx-1060-3gb');
    expect(slugify('amd', 'Radeon RX 480', '8GB')).toBe('amd-radeon-rx-480-8gb');
    expect(slugify('nvidia', 'GeForce GTX 460', '768 MB')).toBe('nvidia-geforce-gtx-460-768mb');
  });

  it('does not repeat the vendor already present in the marketing name', () => {
    expect(slugify('intel', 'Intel Arc A770', '16GB')).toBe('intel-arc-a770-16gb');
    expect(slugify('nvidia', 'NVIDIA Titan Xp')).toBe('nvidia-titan-xp');
    expect(slugify('nvidia', 'NVIDIA Titan X', 'Pascal')).toBe('nvidia-titan-x-pascal');
  });

  it('drops Intel’s filler word "Graphics" as the catalogue does', () => {
    expect(slugify('intel', 'Intel UHD Graphics 770')).toBe('intel-uhd-770');
    expect(slugify('intel', 'Intel Iris Pro Graphics 5200')).toBe('intel-iris-pro-5200');
  });

  it('handles CPU names and memory-technology variants', () => {
    expect(slugify('intel', 'Core i7-2700K')).toBe('intel-core-i7-2700k');
    expect(slugify('amd', 'Ryzen 7 5800X3D')).toBe('amd-ryzen-7-5800x3d');
    expect(slugify('nvidia', 'GeForce GT 1030', 'DDR4')).toBe('nvidia-geforce-gt-1030-ddr4');
  });

  it('does not double the variant when the name already carries it', () => {
    expect(slugify('nvidia', 'GeForce GTX 1060 6GB', '6GB')).toBe('nvidia-geforce-gtx-1060-6gb');
  });
});

describe('splitVariant', () => {
  it('splits a trailing memory size', () => {
    expect(splitVariant('GeForce GTX 1060 6 GB')).toEqual({ base: 'GeForce GTX 1060', variant: '6GB' });
    expect(splitVariant('GeForce GTX 460 768 MB')).toEqual({ base: 'GeForce GTX 460', variant: '768MB' });
    expect(splitVariant('Radeon RX 5500 XT 4GB')).toEqual({ base: 'Radeon RX 5500 XT', variant: '4GB' });
  });

  it('splits a trailing memory technology', () => {
    expect(splitVariant('GeForce GT 1030 DDR4')).toEqual({ base: 'GeForce GT 1030', variant: 'DDR4' });
    expect(splitVariant('GeForce RTX 3060 Ti GDDR6X')).toEqual({ base: 'GeForce RTX 3060 Ti', variant: 'GDDR6X' });
  });

  it('splits a short trailing parenthetical', () => {
    expect(splitVariant('NVIDIA Titan X (Pascal)')).toEqual({ base: 'NVIDIA Titan X', variant: 'Pascal' });
  });

  it('leaves a part with no variant alone', () => {
    expect(splitVariant('GeForce GTX 1080 Ti')).toEqual({ base: 'GeForce GTX 1080 Ti' });
    expect(splitVariant('Radeon RX 7900 XTX')).toEqual({ base: 'Radeon RX 7900 XTX' });
  });

  it('treats an edition suffix as decoration, not identity', () => {
    expect(splitVariant('Intel Arc A750 Limited Edition')).toEqual({ base: 'Intel Arc A750' });
  });
});

// ---------------------------------------------------------------------------
// Composite cells
// ---------------------------------------------------------------------------

describe('composite cell parsers', () => {
  it('reads cores and threads from either spelling', () => {
    expect(parseCoresThreads('16 (24)')).toEqual({ cores: 16, threads: 24 });
    expect(parseCoresThreads('8 / 16')).toEqual({ cores: 8, threads: 16 });
    expect(parseCoresThreads('6')).toEqual({ cores: 6, threads: null });
    expect(parseCoresThreads('N/A')).toEqual({ cores: null, threads: null });
  });

  it('reads PCIe generation and lane count', () => {
    expect(parsePcieInterface('PCIe 4.0 ×16')).toEqual({ gen: 4, lanes: 16 });
    expect(parsePcieInterface('PCI Express 3.0 x4')).toEqual({ gen: 3, lanes: 4 });
    expect(parsePcieInterface('Unknown')).toEqual({ gen: null, lanes: null });
  });

  it('reads memory support', () => {
    expect(parseMemorySupport('DDR4-3200 / DDR5-4800 (dual channel)')).toEqual({
      types: ['DDR4', 'DDR5'],
      mts: 4800,
      channels: 2,
    });
    expect(parseMemorySupport('DDR5-5200 (dual channel)')).toEqual({ types: ['DDR5'], mts: 5200, channels: 2 });
    expect(parseMemorySupport('N/A')).toEqual({ types: [], mts: null, channels: null });
  });

  it('reads FP32 throughput in either unit', () => {
    expect(parseTflops('8,873')).toBe(8.873);
    expect(parseTflops('6,463 GFLOPS')).toBe(6.463);
    expect(parseTflops('10.07 TFLOPS')).toBe(10.07);
    expect(parseTflops('N/A')).toBeNull();
  });

  it('recognises sockets and architectures', () => {
    expect(extractSocket('LGA 1700')).toBe('LGA1700');
    expect(extractSocket('AM5')).toBe('AM5');
    expect(extractSocket('Unknown')).toBeNull();
    expect(detectArchitecture('12th generation (Alder Lake)')).toBe('Alder Lake');
    expect(detectArchitecture('Radeon RX 6000 series (RDNA 2)')).toBe('RDNA 2');
    // A marketing series is not an architecture and must not masquerade as one.
    expect(detectArchitecture('GeForce 10 series')).toBeNull();
  });
});

// ---------------------------------------------------------------------------
// Full page parse — Nvidia fixture
// ---------------------------------------------------------------------------

describe('parseSpecPage — synthetic Nvidia GPU page', () => {
  const report = parseSpecPage(NVIDIA_HTML, 'gpu', { vendor: 'nvidia', provenanceId: 'fixture' });
  const byId = new Map(report.records.map((r) => [r.id!, r as Partial<GpuRecord>]));

  it('produces one record per usable row across both specification tables', () => {
    expect([...byId.keys()]).toEqual([
      'nvidia-geforce-gtx-1080',
      'nvidia-geforce-gtx-1070',
      'nvidia-geforce-gtx-1060-6gb',
      'nvidia-geforce-gtx-1060-3gb',
      'nvidia-geforce-gtx-1050-ti',
      'nvidia-geforce-gt-1030-ddr4',
      'nvidia-geforce-rtx-2080',
      'nvidia-geforce-rtx-2070',
      'nvidia-geforce-rtx-2060',
    ]);
  });

  it('maps a full row into canonical fields with units resolved', () => {
    expect(byId.get('nvidia-geforce-gtx-1080')).toMatchObject({
      vendor: 'nvidia',
      brand: 'GeForce GTX 1080',
      fullName: 'NVIDIA GeForce GTX 1080',
      formFactor: 'desktop',
      chip: 'GP104',
      launchDate: '2016-05-27',
      shaders: 2560,
      baseClockMHz: 1607,
      boostClockMHz: 1733,
      vramGB: 8,
      vramType: 'GDDR5X',
      memBusBits: 256,
      memBandwidthGBs: 320.3,
      fp32TFLOPS: 8.873,
      tdpW: 180,
      processNm: 16,
      pcieGen: 3,
      pcieLanes: 16,
    });
  });

  it('splits one marketing name into two records with distinct ids and specs', () => {
    const six = byId.get('nvidia-geforce-gtx-1060-6gb')!;
    const three = byId.get('nvidia-geforce-gtx-1060-3gb')!;
    expect(six.variant).toBe('6GB');
    expect(three.variant).toBe('3GB');
    expect(six.brand).toBe(three.brand);
    expect(six.shaders).toBe(1280);
    expect(three.shaders).toBe(1152);
    expect(six.vramGB).toBe(6);
    expect(three.vramGB).toBe(3);
  });

  it('carries a rowspan-merged launch date onto the row that inherits it', () => {
    expect(byId.get('nvidia-geforce-gtx-1060-3gb')!.launchDate).toBe('2016-07-19');
  });

  it('strips a footnote superscript out of a numeric cell', () => {
    expect(byId.get('nvidia-geforce-gtx-1070')!.boostClockMHz).toBe(1683);
  });

  it('takes the memory technology from the name when it is the variant axis', () => {
    const gt1030 = byId.get('nvidia-geforce-gt-1030-ddr4')!;
    expect(gt1030.variant).toBe('DDR4');
    expect(gt1030.vramType).toBe('DDR4');
    expect(gt1030.pcieLanes).toBe(4);
  });

  it('derives capabilities where the page names the architecture', () => {
    // The Turing section heading names it, so the gate-relevant caps follow.
    expect(byId.get('nvidia-geforce-rtx-2080')!.architecture).toBe('Turing');
    expect(byId.get('nvidia-geforce-rtx-2080')!.caps).toMatchObject({
      meshShaders: true,
      rayTracing: true,
      dxFeatureLevel: '12_2',
    });
  });

  it('resolves a marketing-series heading to an architecture', () => {
    // "GeForce 10 series" is not an architecture, and detectArchitecture still
    // refuses it. The series map supplies Pascal, which is what unblocks caps
    // and therefore reconciler acceptance for an entire Nvidia page.
    expect(byId.get('nvidia-geforce-gtx-1080')!.architecture).toBe('Pascal');
    expect(byId.get('nvidia-geforce-gtx-1080')!.caps).toMatchObject({
      meshShaders: false,
      rayTracing: false,
    });
  });

  it('reports the architecture column as missing rather than inventing one', () => {
    expect(report.columnsMissing).toContain('architecture');
  });

  it('records every skipped row with a reason', () => {
    const reasons = report.skipped.map((s) => s.reason);
    expect(reasons).toContain('section divider row, not a part');
    expect(reasons.some((r) => r.includes('two configurations'))).toBe(true);
    expect(reasons.some((r) => r.includes('no usable model name'))).toBe(true);
    expect(reasons.some((r) => r.includes('no model-name column'))).toBe(true);
    // Every skip names the row it dropped.
    for (const s of report.skipped) expect(s.row.length).toBeGreaterThan(0);
  });

  it('reports the legend table as unusable instead of parsing it', () => {
    const legend = report.tables!.find((t) => t.section === 'Legend')!;
    expect(legend.used).toBe(false);
    expect(legend.reason).toMatch(/no model-name column/);
  });

  it('reports which columns it found, for the primary table', () => {
    expect(report.columnsFound).toMatchObject({ name: 0, launchDate: 1, shaders: 6, tdpW: 14 });
  });
});

// ---------------------------------------------------------------------------
// Never fabricate
// ---------------------------------------------------------------------------

describe('never fabricate', () => {
  const report = parseSpecPage(NVIDIA_HTML, 'gpu', { vendor: 'nvidia', provenanceId: 'fixture' });
  const byId = new Map(report.records.map((r) => [r.id!, r as Partial<GpuRecord>]));

  it('turns "N/A" and "?" cells into absent fields, not zeros', () => {
    const card = byId.get('nvidia-geforce-gtx-1050-ti')!;
    expect(card.memBandwidthGBs).toBeUndefined();
    expect(card.memBandwidthGBs).not.toBe(0);
    expect(card.tdpW).toBeUndefined();
    expect(card.tdpW).not.toBe(0);
    // The rest of the row still lands: a gap in one cell is not a lost SKU.
    expect(card.shaders).toBe(768);
    expect(card.vramGB).toBe(4);
  });

  it('drops a stated FP32 figure that contradicts shaders × clock, keeping the record', () => {
    const rtx2070 = byId.get('nvidia-geforce-rtx-2070')!;
    expect(rtx2070.fp32TFLOPS).toBeUndefined();
    expect(rtx2070.shaders).toBe(2304);
    expect(rtx2070._conflicts).toEqual([
      { field: 'fp32TFLOPS', value: 12.5, provenanceId: 'fixture' },
    ]);
  });

  it('emits nothing that would fail the reconciler’s 8% TFLOPS rule', () => {
    for (const r of byId.values()) {
      if (r.shaders == null || r.boostClockMHz == null || r.fp32TFLOPS == null) continue;
      const calc = (r.shaders * r.boostClockMHz * 2) / 1e6;
      expect(Math.abs(calc - r.fp32TFLOPS) / r.fp32TFLOPS).toBeLessThanOrEqual(0.08);
    }
  });

  it('emits nothing that would fail the reconciler’s bandwidth-plausibility rule', () => {
    for (const r of byId.values()) {
      if (r.memBusBits == null || r.memBandwidthGBs == null) continue;
      const impliedMts = (r.memBandwidthGBs * 8 * 1000) / r.memBusBits;
      expect(impliedMts).toBeGreaterThanOrEqual(500);
      expect(impliedMts).toBeLessThanOrEqual(40000);
    }
  });

  it('returns null from a row whose name cell is unusable', () => {
    const row = { Model: 'N/A', TDP: '180 W' };
    const cols = { name: 0, tdpW: 1 };
    expect(cellOf(row, cols, 'tdpW')).toBe('180 W');
    expect(rowToGpu(row, cols, { vendor: 'nvidia' })).toBeNull();
  });

  it('derives no capabilities for an architecture it does not know', () => {
    expect(deriveGpuCaps('Some Future Architecture', 'nvidia')).toBeUndefined();
    expect(deriveGpuCaps(undefined, 'nvidia')).toBeUndefined();
  });
});

// ---------------------------------------------------------------------------
// Full page parse — AMD GPU, Intel CPU, AMD CPU
// ---------------------------------------------------------------------------

describe('parseSpecPage — synthetic AMD GPU page', () => {
  const report = parseSpecPage(AMD_GPU_HTML, 'gpu', { vendor: 'amd', provenanceId: 'fixture' });
  const byId = new Map(report.records.map((r) => [r.id!, r as Partial<GpuRecord>]));

  it('reads an architecture column and derives the gate-relevant capabilities', () => {
    const xt = byId.get('amd-radeon-rx-6800-xt')!;
    expect(xt.architecture).toBe('RDNA 2');
    expect(xt.caps).toMatchObject({ meshShaders: true, rayTracing: true, dxFeatureLevel: '12_2' });
    expect(xt.chip).toBe('Navi 21');
  });

  it('takes the boost column, never the middle "Game" column', () => {
    expect(byId.get('amd-radeon-rx-6800-xt')!.baseClockMHz).toBe(1825);
    expect(byId.get('amd-radeon-rx-6800-xt')!.boostClockMHz).toBe(2250);
  });

  it('reads a model cell that is a row header rather than a data cell', () => {
    expect(byId.get('amd-radeon-rx-6700')!.shaders).toBe(2304);
    expect(byId.get('amd-radeon-rx-6700')!.launchDate).toBe('2021-03-18');
  });

  it('takes the upper bound of a clock range', () => {
    expect(byId.get('amd-radeon-rx-6600')!.boostClockMHz).toBe(2491);
  });

  it('splits a memory-size variant on an AMD name too', () => {
    expect(byId.get('amd-radeon-rx-6500-xt-4gb')).toMatchObject({ variant: '4GB', vramGB: 4 });
  });

  it('skips nothing on a clean table', () => {
    expect(report.skipped).toEqual([]);
  });
});

describe('parseSpecPage — synthetic Intel CPU page', () => {
  const report = parseSpecPage(INTEL_CPU_HTML, 'cpu', { vendor: 'intel', provenanceId: 'fixture' });
  const byId = new Map(report.records.map((r) => [r.id!, r as Partial<CpuRecord>]));

  it('maps a hybrid part completely, including span-merged socket and memory', () => {
    expect(byId.get('intel-core-i9-12900k')).toMatchObject({
      vendor: 'intel',
      brand: 'Core i9-12900K',
      architecture: 'Alder Lake',
      launchDate: '2021-11-04',
      segment: 'enthusiast',
      cores: 16,
      threads: 24,
      pCores: 8,
      eCores: 8,
      baseClockMHz: 3200,
      boostClockMHz: 5200,
      l2CacheMB: 14,
      l3CacheMB: 30,
      tdpW: 125,
      socket: 'LGA1700',
      memoryType: ['DDR4', 'DDR5'],
      officialMemMTs: 4800,
      maxMemChannels: 2,
      caps: { avx2: true, avx512: false, hybrid: true },
    });
  });

  it('satisfies the reconciler’s hybrid-topology rule', () => {
    for (const r of byId.values()) {
      if (r.pCores == null || r.eCores == null) continue;
      expect(r.pCores + r.eCores).toBe(r.cores);
      expect(r.threads).toBeGreaterThanOrEqual(r.cores!);
    }
  });

  it('records zero E-cores as zero, not as a missing value', () => {
    expect(byId.get('intel-core-i5-12400')!.eCores).toBe(0);
    expect(byId.get('intel-core-i5-12400')!.cores).toBe(6);
  });

  it('leaves an unknown TDP and an unknown date absent', () => {
    const i3 = byId.get('intel-core-i3-12100')!;
    expect(i3.tdpW).toBeUndefined();
    expect(i3.launchDate).toBeUndefined();
    expect(i3.cores).toBe(4);
  });

  it('leaves socket and memory absent when the table has no such column', () => {
    // The reconciler REQUIRES both, so these records will be rejected — with a
    // reason — rather than being completed by guesswork.
    const i9 = byId.get('intel-core-i9-13900k')!;
    expect(i9.architecture).toBe('Raptor Lake');
    expect(i9.socket).toBeUndefined();
    expect(i9.memoryType).toBeUndefined();
  });
});

describe('parseSpecPage — synthetic AMD CPU page', () => {
  const report = parseSpecPage(AMD_CPU_HTML, 'cpu', { vendor: 'amd', provenanceId: 'fixture' });
  const byId = new Map(report.records.map((r) => [r.id!, r as Partial<CpuRecord>]));

  it('flags stacked cache from the part name, which the reconciler cross-checks', () => {
    expect(byId.get('amd-ryzen-7-7800x3d')!.vcache).toBe(true);
    expect(byId.get('amd-ryzen-5-7600x')!.vcache).toBe(false);
  });

  it('maps a slash-spelled core/thread column and GHz clocks', () => {
    expect(byId.get('amd-ryzen-9-7950x')).toMatchObject({
      cores: 16,
      threads: 32,
      baseClockMHz: 4500,
      boostClockMHz: 5700,
      l3CacheMB: 64,
      socket: 'AM5',
      memoryType: ['DDR5'],
      processNm: 5,
    });
  });

  it('ignores a second wattage quoted in the same cell', () => {
    expect(byId.get('amd-ryzen-5-7600')!.tdpW).toBe(65);
  });
});

// ---------------------------------------------------------------------------
// Envelope handling and robustness
// ---------------------------------------------------------------------------

describe('page plumbing', () => {
  it('unwraps a MediaWiki action=parse JSON envelope', () => {
    const wrapped = JSON.stringify({ parse: { title: 'X', text: { '*': '<table><tr><th>Model</th></tr></table>' } } });
    expect(unwrapMediaWiki(wrapped)).toBe('<table><tr><th>Model</th></tr></table>');
    expect(unwrapMediaWiki('<table></table>')).toBe('<table></table>');
    expect(unwrapMediaWiki('{not json')).toBe('{not json');
  });

  it('parses a page delivered inside the MediaWiki envelope', () => {
    const wrapped = JSON.stringify({ parse: { text: { '*': NVIDIA_HTML } } });
    const report = parseSpecPage(wrapped, 'gpu', { vendor: 'nvidia' });
    expect(report.records.length).toBe(9);
  });

  it('returns an empty report rather than throwing on a page with no tables', () => {
    const report = parseSpecPage('<p>Nothing here.</p>', 'gpu', { vendor: 'nvidia' });
    expect(report.records).toEqual([]);
    expect(report.skipped).toEqual([]);
    expect(report.columnsMissing.length).toBeGreaterThan(0);
  });

  it('refuses a vendor it cannot resolve rather than emitting a bad id', () => {
    const report = parseSpecPage(NVIDIA_HTML, 'gpu', { vendor: 'matrox' });
    expect(report.records).toEqual([]);
    expect(report.skipped.length).toBeGreaterThan(0);
  });
});

// ---------------------------------------------------------------------------
// Section-heading context. Both of these existed as documented limitations in
// agents/log/spec-mapper.md — "CPU socket and memoryType will usually be
// missing" and "architecture is often absent on Nvidia-style pages" — and both
// caused reconciler rejection of every record from a real page.
// ---------------------------------------------------------------------------

describe('marketing series to architecture', () => {
  it('resolves the series Nvidia actually heads its sections with', () => {
    expect(architectureFromSeries('GeForce 10 series')).toBe('Pascal');
    expect(architectureFromSeries('GeForce 20 series')).toBe('Turing');
    expect(architectureFromSeries('GeForce 16 series')).toBe('Turing');
    expect(architectureFromSeries('GeForce 30 series')).toBe('Ampere');
    expect(architectureFromSeries('GeForce RTX 40 series')).toBe('Ada Lovelace');
    expect(architectureFromSeries('GeForce RTX 50 series')).toBe('Blackwell');
    expect(architectureFromSeries('GeForce 900 series')).toBe('Maxwell 2');
  });

  it('applies a series exception when the model name identifies one', () => {
    // The 700 line is Kepler apart from three GM107 parts. Without the model
    // name it can only give the series answer; with it, it gives the right one.
    expect(architectureFromSeries('GeForce 700 series')).toBe('Kepler');
    expect(architectureFromSeries('GeForce 700 series', 'GeForce GTX 760')).toBe('Kepler');
    expect(architectureFromSeries('GeForce 700 series', 'GeForce GTX 750 Ti')).toBe('Maxwell 1');
    expect(architectureFromSeries('GeForce 700 series', 'GeForce GTX 745')).toBe('Maxwell 1');
  });

  it('leaves out series whose split cannot be told from the heading', () => {
    // GeForce 600 mixed Kepler with rebadged Fermi. Absent is recoverable,
    // wrong is not — a false architecture derives false capability flags.
    expect(architectureFromSeries('GeForce 600 series')).toBeNull();
    expect(architectureFromSeries('Radeon R9 300 series')).toBeNull();
    expect(architectureFromSeries('Radeon R7 200 series')).toBeNull();
  });

  it('resolves AMD and Intel series headings too', () => {
    expect(architectureFromSeries('Radeon RX 6000 series')).toBe('RDNA 2');
    expect(architectureFromSeries('Radeon RX 7000 series')).toBe('RDNA 3');
    expect(architectureFromSeries('Radeon RX 9000 series')).toBe('RDNA 4');
    expect(architectureFromSeries('Radeon RX 500 series')).toBe('GCN 4.0');
    expect(architectureFromSeries('Intel Arc A-Series')).toBe('Xe-HPG (Alchemist)');
    expect(architectureFromSeries('Intel Arc B-Series')).toBe('Xe2-HPG (Battlemage)');
  });

  it('still refuses a heading that names nothing', () => {
    expect(architectureFromSeries('Legend')).toBeNull();
    expect(architectureFromSeries('Discontinued products')).toBeNull();
    expect(architectureFromSeries('')).toBeNull();
  });
});

describe('heading chain', () => {
  const h = (level: number, at: number, title: string) => ({ level, at, title });

  it('returns the ancestors of a position, not just the nearest heading', () => {
    // The platform lives on the h2; the nearest heading is the h3.
    const headings = [h(2, 0, 'Socket AM4'), h(3, 100, 'Ryzen 5000 series')];
    expect(headingChain(headings, 200).map((x) => x.title)).toEqual(['Socket AM4', 'Ryzen 5000 series']);
  });

  it('closes a subtree when a shallower heading follows', () => {
    // Without this, AM4's Ryzen 5000 subsection leaks into the AM5 section and
    // a whole platform's worth of records get the wrong socket.
    const headings = [
      h(2, 0, 'Socket AM4'),
      h(3, 100, 'Ryzen 5000 series'),
      h(2, 200, 'Socket AM5'),
      h(3, 300, 'Ryzen 7000 series'),
    ];
    expect(headingChain(headings, 400).map((x) => x.title)).toEqual(['Socket AM5', 'Ryzen 7000 series']);
  });

  it('drops a sibling at the same level', () => {
    const headings = [h(3, 0, 'Desktop'), h(3, 100, 'Mobile')];
    expect(headingChain(headings, 200).map((x) => x.title)).toEqual(['Mobile']);
  });

  it('is empty before the first heading', () => {
    expect(headingChain([h(2, 500, 'Later')], 100)).toEqual([]);
  });
});

describe('section context', () => {
  const chain = (...titles: string[]) => titles.map((title, i) => ({ level: i + 2, at: i * 100, title }));

  it('reads the socket and memory type a CPU page states only in its heading', () => {
    const ctx = sectionContext(chain('Socket AM4 (DDR4)', 'Ryzen 5000 series'));
    expect(ctx.socket).toBe('AM4');
    expect(ctx.memoryType).toEqual(['DDR4']);
    expect(ctx.from.socket).toBe('Socket AM4 (DDR4)');
  });

  it('lets an inner heading override an outer one', () => {
    const ctx = sectionContext(chain('Socket LGA1700 (DDR4/DDR5)', 'Raptor Lake refresh'));
    expect(ctx.socket).toBe('LGA1700');
    expect(ctx.memoryType).toEqual(['DDR4', 'DDR5']);
    expect(ctx.architecture).toBe('Raptor Lake');
  });

  it('takes an architecture from either a silicon name or a series name', () => {
    expect(sectionContext(chain('Turing')).architecture).toBe('Turing');
    expect(sectionContext(chain('GeForce 10 series')).architecture).toBe('Pascal');
  });

  it('does not read a series number as a memory rating', () => {
    // "Ryzen 7000 series" carries a four-digit number that is not MT/s.
    const ctx = sectionContext(chain('Ryzen 7000 series'));
    expect(ctx.memoryType).toBeUndefined();
    expect(ctx.maxMemChannels).toBeUndefined();
  });

  it('reads a stated channel count', () => {
    expect(sectionContext(chain('Socket sTR5 (quad-channel DDR5)')).maxMemChannels).toBe(4);
  });

  it('returns nothing rather than a default when headings say nothing', () => {
    const ctx = sectionContext(chain('Specifications', 'Notes'));
    expect(ctx.socket).toBeUndefined();
    expect(ctx.memoryType).toBeUndefined();
    expect(ctx.architecture).toBeUndefined();
  });
});

describe('parseSpecPage — CPU page whose platform is only in the headings', () => {
  // The shape agents/log/spec-mapper.md predicted the real pages use: the
  // socket and memory type on an h2, the product line on an h3, and per-row
  // columns carrying neither. Before section inheritance every record from a
  // page like this was rejected by the reconciler for a missing socket.
  const HTML = `
    <h2>Socket AM4 (DDR4)</h2>
    <h3>Ryzen 5000 series</h3>
    <table class="wikitable">
      <tr><th>Model</th><th>Cores (threads)</th><th>Base clock</th><th>Boost clock</th><th>L3 cache</th><th>TDP</th></tr>
      <tr><td>Ryzen 5 5600X</td><td>6 (12)</td><td>3.7 GHz</td><td>4.6 GHz</td><td>32 MB</td><td>65 W</td></tr>
      <tr><td>Ryzen 7 5800X3D</td><td>8 (16)</td><td>3.4 GHz</td><td>4.5 GHz</td><td>96 MB</td><td>105 W</td></tr>
    </table>
    <h2>Socket AM5 (DDR5)</h2>
    <h3>Ryzen 7000 series</h3>
    <table class="wikitable">
      <tr><th>Model</th><th>Cores (threads)</th><th>Base clock</th><th>Boost clock</th><th>L3 cache</th><th>TDP</th></tr>
      <tr><td>Ryzen 7 7800X3D</td><td>8 (16)</td><td>4.2 GHz</td><td>5.0 GHz</td><td>96 MB</td><td>120 W</td></tr>
    </table>`;
  const report = parseSpecPage(HTML, 'cpu', { vendor: 'amd', provenanceId: 'test-page' });
  const byId = new Map(report.records.map((r) => [r.id!, r as Partial<CpuRecord>]));

  it('gives every record the socket from its own section', () => {
    expect(byId.get('amd-ryzen-5-5600x')!.socket).toBe('AM4');
    expect(byId.get('amd-ryzen-7-5800x3d')!.socket).toBe('AM4');
    expect(byId.get('amd-ryzen-7-7800x3d')!.socket).toBe('AM5');
  });

  it('does not leak the first platform into the second', () => {
    expect(byId.get('amd-ryzen-7-7800x3d')!.memoryType).toEqual(['DDR5']);
    expect(byId.get('amd-ryzen-5-5600x')!.memoryType).toEqual(['DDR4']);
  });

  it('marks inherited fields in provenance rather than passing them off as stated', () => {
    const prov = byId.get('amd-ryzen-5-5600x')!._prov!;
    expect(prov.socket?.[0]).toMatch(/#section:Socket AM4/);
    expect(prov.memoryType?.[0]).toMatch(/#section:Socket AM4/);
    // A field genuinely read from a column carries no section marker.
    expect(prov.cores).toBeUndefined();
  });

  it('reports the full heading path, not only the nearest heading', () => {
    const used = report.tables!.filter((t) => t.used);
    expect(used[0].sectionPath).toBe('Socket AM4 (DDR4) > Ryzen 5000 series');
    expect(used[1].sectionPath).toBe('Socket AM5 (DDR5) > Ryzen 7000 series');
    expect(used[0].inherited).toMatchObject({ socket: 'Socket AM4 (DDR4)' });
  });

  it('still reads the row columns it does have', () => {
    const r = byId.get('amd-ryzen-7-5800x3d')!;
    expect(r.cores).toBe(8);
    expect(r.threads).toBe(16);
    expect(r.l3CacheMB).toBe(96);
    expect(r.vcache).toBe(true);
    expect(r.tdpW).toBe(105);
  });
});
