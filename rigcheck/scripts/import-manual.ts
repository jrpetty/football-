/**
 * Manual import lane. Runs on every build; see data/manual/README.md.
 *
 * Validation is strict on the columns that change what a measurement MEANS and
 * forgiving on the rest. Rows missing fingerprint columns are ingested at
 * reduced weight rather than dropped, and every rejection is reported with a
 * reason — nothing fails silently.
 */
import { existsSync, mkdirSync, readFileSync, readdirSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import type { PerfRecord, RamConfig, Resolution, SettingsFingerprint, UpscalingQuality, UpscalingTech } from '../src/core/types.ts';

const ROOT = new URL('..', import.meta.url).pathname;
const MANUAL = join(ROOT, 'data/manual');
const OUT = join(ROOT, 'data/measured');

const RESOLUTIONS = new Set(['1080p', '1440p', '2160p', '3440x1440']);
const TECHS = new Set(['none', 'dlss', 'fsr', 'xess', 'tsr']);
const QUALITIES = new Set(['native', 'quality', 'balanced', 'performance', 'ultra-performance']);
const STORAGE = new Set(['hdd', 'sata-ssd', 'nvme-gen3', 'nvme-gen4']);

export interface ImportRejection {
  file: string;
  line: number;
  reason: string;
  raw: string;
}

/** Minimal RFC4180-ish CSV parser: handles quoted fields and embedded commas. */
export function parseCsv(text: string): string[][] {
  const rows: string[][] = [];
  let row: string[] = [];
  let field = '';
  let inQuotes = false;

  for (let i = 0; i < text.length; i++) {
    const ch = text[i];
    if (inQuotes) {
      if (ch === '"') {
        if (text[i + 1] === '"') { field += '"'; i++; }
        else inQuotes = false;
      } else field += ch;
      continue;
    }
    if (ch === '"') { inQuotes = true; continue; }
    if (ch === ',') { row.push(field); field = ''; continue; }
    if (ch === '\r') continue;
    if (ch === '\n') { row.push(field); rows.push(row); row = []; field = ''; continue; }
    field += ch;
  }
  if (field.length > 0 || row.length > 0) { row.push(field); rows.push(row); }
  return rows.filter((r) => r.some((c) => c.trim() !== ''));
}

function num(v: string | undefined): number | undefined {
  if (v == null || v.trim() === '') return undefined;
  const n = Number(v);
  return Number.isFinite(n) ? n : undefined;
}

export interface ImportResult {
  records: PerfRecord[];
  rejections: ImportRejection[];
  filesProcessed: string[];
  weightReductions: { reason: string; count: number }[];
}

export function importRows(file: string, text: string, knownIds?: { gpus: Set<string>; cpus: Set<string>; games: Set<string> }): ImportResult {
  const rows = parseCsv(text);
  const rejections: ImportRejection[] = [];
  const records: PerfRecord[] = [];
  const reductions = new Map<string, number>();

  if (rows.length < 2) {
    return { records, rejections: [{ file, line: 0, reason: 'File has no data rows.', raw: '' }], filesProcessed: [file], weightReductions: [] };
  }

  const header = rows[0].map((h) => h.trim().toLowerCase());
  const idx = (name: string) => header.indexOf(name);
  const required = ['gpu_id', 'cpu_id', 'game_id', 'resolution', 'preset', 'avg_fps'];
  const missing = required.filter((r) => idx(r) === -1);
  if (missing.length) {
    return {
      records,
      rejections: [{ file, line: 1, reason: `Missing required column(s): ${missing.join(', ')}`, raw: rows[0].join(',') }],
      filesProcessed: [file],
      weightReductions: [],
    };
  }

  for (let i = 1; i < rows.length; i++) {
    const r = rows[i];
    const get = (name: string) => (idx(name) >= 0 ? r[idx(name)]?.trim() : undefined);
    const raw = r.join(',');
    const fail = (reason: string) => rejections.push({ file, line: i + 1, reason, raw });

    const gpuId = get('gpu_id');
    const cpuId = get('cpu_id');
    const gameId = get('game_id');
    const resolution = get('resolution') as Resolution | undefined;
    const preset = get('preset');
    const avgFps = num(get('avg_fps'));

    if (!gpuId || !cpuId || !gameId) { fail('gpu_id, cpu_id and game_id are all required.'); continue; }
    if (!resolution || !RESOLUTIONS.has(resolution)) { fail(`resolution "${resolution}" is not one of ${[...RESOLUTIONS].join(', ')}.`); continue; }
    if (!preset) { fail('preset is required.'); continue; }
    if (avgFps == null || avgFps <= 0) { fail(`avg_fps "${get('avg_fps')}" is not a positive number.`); continue; }
    if (avgFps > 2000) { fail(`avg_fps ${avgFps} is implausible (>2000).`); continue; }

    if (knownIds) {
      if (!knownIds.gpus.has(gpuId)) { fail(`gpu_id "${gpuId}" is not in the catalogue.`); continue; }
      if (!knownIds.cpus.has(cpuId)) { fail(`cpu_id "${cpuId}" is not in the catalogue.`); continue; }
      if (!knownIds.games.has(gameId)) { fail(`game_id "${gameId}" is not in the catalogue.`); continue; }
    }

    const tech = (get('upscaling_tech') || 'none') as UpscalingTech;
    if (!TECHS.has(tech)) { fail(`upscaling_tech "${tech}" is not valid.`); continue; }
    const quality = (get('upscaling_quality') || (tech === 'none' ? 'native' : 'quality')) as UpscalingQuality;
    if (!QUALITIES.has(quality)) { fail(`upscaling_quality "${quality}" is not valid.`); continue; }

    const storage = get('storage');
    if (storage && !STORAGE.has(storage)) { fail(`storage "${storage}" is not valid.`); continue; }

    const low1 = num(get('low_1pct'));
    if (low1 != null && low1 > avgFps) { fail(`low_1pct ${low1} exceeds avg_fps ${avgFps}.`); continue; }

    const ramGb = num(get('ram_gb'));
    const ramChannels = num(get('ram_channels'));
    if (ramChannels != null && ![1, 2, 4, 8].includes(ramChannels)) { fail(`ram_channels ${ramChannels} must be 1, 2, 4 or 8.`); continue; }

    const ram: RamConfig | undefined = ramGb
      ? {
          totalGB: ramGb,
          channels: (ramChannels ?? 2) as RamConfig['channels'],
          speedMTs: num(get('ram_mts')) ?? 3200,
          timings: num(get('ram_cl')) ? { cl: num(get('ram_cl'))!, trcd: 0, trp: 0, tras: 0 } : undefined,
        }
      : undefined;

    // Weight reduction for an incomplete fingerprint. Recorded per reason so the
    // report says WHY the corpus is weak, not just that it is.
    let weight = 1;
    const dock = (reason: string, factor: number) => {
      weight *= factor;
      reductions.set(reason, (reductions.get(reason) ?? 0) + 1);
    };
    if (!get('game_build')) dock('missing game_build', 0.9);
    if (!get('driver_version')) dock('missing driver_version', 0.92);
    if (!get('date')) dock('missing date', 0.9);
    if (idx('upscaling_tech') === -1) dock('upscaling column absent — assumed native', 0.85);
    if (low1 == null) dock('missing low_1pct', 0.95);

    const fingerprint: SettingsFingerprint = {
      gameBuild: get('game_build') || undefined,
      preset,
      upscaling: { tech, quality, frameGen: (get('frame_gen') || '').toLowerCase() === 'true' },
      rtTier: get('rt_tier') || undefined,
      api: get('api') || undefined,
      driverVersion: get('driver_version') || undefined,
      date: get('date') || undefined,
    };

    records.push({
      id: `${file}:${i + 1}`,
      gpuId,
      cpuId,
      gameId,
      resolution,
      avgFps,
      low1Pct: low1,
      low01Pct: num(get('low_01pct')),
      ram,
      fingerprint,
      sourceNote: get('source_note') || `manual import: ${file}`,
      provenanceId: 'manual-import',
      weight,
    });
  }

  return {
    records,
    rejections,
    filesProcessed: [file],
    weightReductions: [...reductions.entries()].map(([reason, count]) => ({ reason, count })),
  };
}

function loadKnownIds() {
  const dir = join(ROOT, 'data/catalogue');
  const read = (f: string): Set<string> => {
    const p = join(dir, f);
    if (!existsSync(p)) return new Set();
    const d = JSON.parse(readFileSync(p, 'utf8')) as { records: { id: string }[] };
    return new Set(d.records.map((r) => r.id));
  };
  const gpus = read('gpus.json');
  const cpus = read('cpus.json');
  const games = read('games.json');
  if (!gpus.size || !cpus.size || !games.size) return undefined;
  return { gpus, cpus, games };
}

function main() {
  mkdirSync(OUT, { recursive: true });
  mkdirSync(MANUAL, { recursive: true });
  const known = loadKnownIds();
  if (!known) console.log('NOTE: catalogue not built yet — id validation skipped this run.');

  const files = readdirSync(MANUAL).filter((f) => f.toLowerCase().endsWith('.csv'));
  const all: PerfRecord[] = [];
  const allRejections: ImportRejection[] = [];
  const allReductions = new Map<string, number>();

  for (const f of files) {
    const res = importRows(f, readFileSync(join(MANUAL, f), 'utf8'), known);
    all.push(...res.records);
    allRejections.push(...res.rejections);
    for (const w of res.weightReductions) allReductions.set(w.reason, (allReductions.get(w.reason) ?? 0) + w.count);
    console.log(`${f}: ${res.records.length} accepted, ${res.rejections.length} rejected`);
  }

  writeFileSync(
    join(OUT, 'records.json'),
    JSON.stringify(
      {
        generatedAt: new Date().toISOString(),
        provenance: {
          'manual-import': {
            id: 'manual-import',
            source: 'Operator manual import',
            url: '',
            licence: 'operator-supplied',
            retrievedAt: new Date().toISOString().slice(0, 10),
          },
        },
        count: all.length,
        records: all,
      },
      null,
      2,
    ),
  );

  if (files.length === 0) {
    console.log('No CSV files in data/manual/. The lane is wired and will pick them up on the next run.');
  }
  console.log(`\nTotal: ${all.length} measured record(s) from ${files.length} file(s), ${allRejections.length} rejection(s).`);
  for (const r of allRejections.slice(0, 25)) console.log(`  REJECT ${r.file}:${r.line} — ${r.reason}`);
  if (allRejections.length > 25) console.log(`  ... and ${allRejections.length - 25} more.`);
  if (allReductions.size) {
    console.log('\nWeight reductions (fingerprint completeness):');
    for (const [reason, count] of allReductions) console.log(`  ${count.toString().padStart(5)} rows — ${reason}`);
  }
}

if (process.argv[1]?.endsWith('import-manual.ts')) main();
