/**
 * Analyse a real machine: one processor, one or more graphics cards.
 *
 *   npm run analyse -- --cpu "i7 7700" --gpu "2060 super" --gpu "1080 ti"
 *   npm run analyse -- --cpu "ryzen 5 3600" --gpu "rx 6600" --resolution 1440p
 *   npm run analyse -- --cpu "i7 7700" --gpu "titan x"        (names three cards, runs all three)
 *
 * Answers the question someone actually has — "is this card worth putting in
 * my machine" — rather than the one a spec sheet answers. Prints the per-game
 * table with limiters, what a current platform would give the same card, the
 * games that will not launch at all and why, power and PSU, and where several
 * cards stop being different from each other.
 *
 * This was a hand-written throwaway script once. It is a command now because
 * an analysis you cannot re-run is one you cannot check, and the numbers in it
 * end up in posts.
 */
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { buildEngineData } from '../src/core/catalogue.ts';
import { resolveAll, resolveOne } from '../src/core/resolve.ts';
import { convergence, pairingReport, LEGACY_RAM, CEILING_RAM } from '../src/core/pairing.ts';
import type { RamConfig, Resolution } from '../src/core/types.ts';
import type { CpuRecord, GpuRecord } from '../src/core/types.ts';

const ROOT = new URL('..', import.meta.url).pathname;
const load = (p: string) => JSON.parse(readFileSync(join(ROOT, p), 'utf8'));

function usage(msg?: string): never {
  if (msg) console.error(`\n${msg}\n`);
  console.error(`usage: npm run analyse -- --cpu <name> --gpu <name> [--gpu <name> ...] [options]

  --cpu <name>          the processor, by name ("i7 7700", "ryzen 5 3600")
  --gpu <name>          a graphics card; repeat for a comparison. A name that
                        matches several cards runs all of them.
  --resolution <r>      1080p (default), 1440p or 2160p
  --games a,b,c         catalogue game ids; defaults to the standard eight
  --ram <n>             system memory in GB (default 16 with an older chip)
  --ram-type DDR4|DDR5  default DDR4
  --ram-speed <MT/s>    default 2400
  --json                machine-readable output instead of the tables
`);
  process.exit(1);
}

const argv = process.argv.slice(2);
const flags: Record<string, string> = {};
const gpuQueries: string[] = [];
for (let i = 0; i < argv.length; i++) {
  const a = argv[i];
  if (!a.startsWith('--')) usage(`unexpected argument "${a}" — every value needs its flag`);
  const key = a.slice(2);
  if (key === 'json') { flags.json = '1'; continue; }
  const val = argv[++i];
  if (val == null) usage(`--${key} needs a value`);
  if (key === 'gpu') gpuQueries.push(val); else flags[key] = val;
}
if (!flags.cpu) usage('--cpu is required');
if (!gpuQueries.length) usage('at least one --gpu is required');

const data = buildEngineData({
  gpus: load('data/catalogue/gpus.json'), cpus: load('data/catalogue/cpus.json'),
  games: load('data/catalogue/games.json'), references: load('data/catalogue/references.json'),
});

const cpuLabels = new Map((load('data/catalogue/cpus.json').records as CpuRecord[]).map((r) => [r.id, r.fullName]));
const gpuLabels = new Map((load('data/catalogue/gpus.json').records as GpuRecord[]).map((r) => [r.id, r.fullName]));

const cpuHit = resolveOne(flags.cpu, cpuLabels);
if (!cpuHit.ok) {
  if (cpuHit.reason === 'none') usage(`no processor matches "${flags.cpu}"`);
  console.error(`\n"${flags.cpu}" matches several processors. Be more specific:\n`);
  for (const c of cpuHit.candidates) console.error(`  ${c.label}`);
  process.exit(1);
}

/* A name that lands on several cards is not an error here. "titan x" is three
   cards a third apart in speed, and someone comparing them is better served by
   seeing all three than by being told to pick one blind. */
const MAX_CARDS = 6;
const gpuIds: string[] = [];
for (const q of gpuQueries) {
  const hits = resolveAll(q, gpuLabels);
  if (!hits.length) usage(`no graphics card matches "${q}"`);
  if (hits.length > MAX_CARDS) {
    console.error(`\n"${q}" matches ${hits.length} cards — too broad to run. Narrow it, or name them one at a time:\n`);
    for (const h of hits.slice(0, 12)) console.error(`  ${h.label}`);
    if (hits.length > 12) console.error(`  ... and ${hits.length - 12} more`);
    process.exit(1);
  }
  if (hits.length > 1) console.error(`note: "${q}" names ${hits.length} cards — running all of them: ${hits.map((h) => h.label).join(', ')}`);
  for (const h of hits) if (!gpuIds.includes(h.id)) gpuIds.push(h.id);
}

const resolution = (flags.resolution ?? '1080p') as Resolution;
if (!['1080p', '1440p', '2160p'].includes(resolution)) usage(`--resolution must be 1080p, 1440p or 2160p`);
const ramType = (flags['ram-type'] ?? 'DDR4') as RamConfig['type'];
const ram: RamConfig = {
  totalGB: Number(flags.ram ?? LEGACY_RAM.totalGB),
  channels: 2,
  speedMTs: Number(flags['ram-speed'] ?? (ramType === 'DDR5' ? CEILING_RAM.speedMTs : LEGACY_RAM.speedMTs)),
  type: ramType,
};
if (!Number.isFinite(ram.totalGB) || ram.totalGB < 1) usage('--ram must be a number of gigabytes');
if (!Number.isFinite(ram.speedMTs) || ram.speedMTs < 1) usage('--ram-speed must be a number');

const games = flags.games?.split(',').map((g) => g.trim()).filter(Boolean);
const opts = { resolution, ram, games };
const reports = gpuIds.map((id) => pairingReport(cpuHit.id, id, data, opts));
const conv = gpuIds.length > 1 ? convergence(cpuHit.id, gpuIds, data, opts) : null;

if (flags.json) {
  console.log(JSON.stringify({ reports, convergence: conv }, null, 2));
  process.exit(0);
}

/* ---- tables ------------------------------------------------------------- */

/** "GeForce RTX 2060 Super" truncates to "GeForce RTX 2" in a column. The
    family name is the same on every row; the model is what distinguishes them. */
const model = (s: string) => s.replace(/^(GeForce|Radeon|Arc|Core|Ryzen)\s+/, '');

const MARK: Record<string, string> = { cpu: '·cpu', gpu: '', balanced: '·bal', vram: '·vram', 'engine-cap': '·cap', thermal: '·hot' };
const cell = (r: { fps: number | null; limiter: string | undefined; status: string }) =>
  r.fps == null ? (r.status === 'WILL_NOT_RUN' ? 'blocked' : 'no data') : `${r.fps}${MARK[r.limiter ?? ''] ?? ''}`;

const first = reports[0];
console.log(`\n${first.cpu.name} — ${ram.totalGB}GB ${ram.type}-${ram.speedMTs} dual channel`);
console.log(`${resolution}, high preset, no upscaling. ·cpu = processor is the limit, ·bal = neither dominates, no mark = the card is.\n`);

const W = 15;
console.log('GAME'.padEnd(26) + reports.map((r) => model(r.gpu.short).slice(0, W - 2).padStart(W)).join(''));
for (let i = 0; i < first.rows.length; i++) {
  console.log(first.rows[i].game.padEnd(26) + reports.map((r) => cell(r.rows[i]).padStart(W)).join(''));
}

/* Blocked games first: "will not launch" outranks any frame rate on the card. */
const blocked = first.rows.filter((_, i) => reports.some((r) => r.rows[i].status === 'WILL_NOT_RUN'));
if (blocked.length) {
  console.log(`\nWILL NOT LAUNCH`);
  for (const row of blocked) {
    const idx = first.rows.indexOf(row);
    const bad = reports.filter((r) => r.rows[idx].status === 'WILL_NOT_RUN');
    const okd = reports.filter((r) => r.rows[idx].status === 'ok');
    console.log(`  ${row.game}: ${bad.map((r) => model(r.gpu.short)).join(', ')}`);
    console.log(`    ${bad[0].rows[idx].blockedDetail ?? bad[0].rows[idx].blockedBy.join(', ')}`);
    if (okd.length) console.log(`    Runs on: ${okd.map((r) => `${model(r.gpu.short)} (${r.rows[idx].fps}fps)`).join(', ')}`);
  }
}

if (conv && conv.comparableCount) {
  console.log(`\nWHERE THE CARDS STOP BEING DIFFERENT — within ${conv.toleranceFps}fps of each other`);
  console.log(`  ${conv.convergedCount} of ${conv.comparableCount} games that ran on more than one card.`);
  for (const row of conv.rows.filter((r) => r.spreadFps != null)) {
    console.log(`  ${row.game.padEnd(26)} spread ${String(row.spreadFps).padStart(3)}fps (${String(row.spreadPct).padStart(3)}%)  ${row.converged ? 'same card, effectively' : ''}`);
  }
}

console.log(`\nWHAT THE PROCESSOR IS COSTING EACH CARD`);
if (!first.ceiling) console.log('  No current-platform reference in the catalogue.');
else {
  console.log(`  Against the same card on ${/^[AEIOU]/.test(first.ceiling.name) ? 'an' : 'a'} ${first.ceiling.name} with ${first.ceiling.ram.totalGB}GB ${first.ceiling.ram.type}-${first.ceiling.ram.speedMTs}.`);
  console.log(`  That is a platform change — board and memory as well as the chip — because DDR${ramType === 'DDR5' ? '5' : '4'} does not carry over.\n`);
  console.log('CARD'.padEnd(24) + 'VERDICT'.padEnd(11) + 'MEDIAN'.padStart(8) + '   WORST GAME');
  for (const r of reports) {
    const w = r.worst;
    console.log(model(r.gpu.short).slice(0, 23).padEnd(24) + r.verdict.padEnd(11) +
      (r.medianHeadroomPct == null ? '—' : `+${r.medianHeadroomPct}%`).padStart(8) +
      (w ? `   ${w.game} ${w.fps} → ${w.ceilingFps} (+${w.headroomFps}, +${w.headroomPct}%)` : ''));
  }
}

console.log(`\nPOWER AND PSU`);
/* A range across every game mixes a 220fps shooter with a 42fps role-playing
   game and says nothing. The heaviest game that ran on every card is where a
   1% low is worth reading, so that is the one reported. */
const heavy = first.rows
  .filter((_, i) => reports.every((r) => r.rows[i].fps != null))
  .sort((a, b) => a.fps! - b.fps!)[0];
console.log('CARD'.padEnd(24) + 'DRAW'.padStart(7) + 'PSU'.padStart(7) +
  (heavy ? `   1% LOW, ${heavy.game}` : ''));
for (const r of reports) {
  const row = heavy ? r.rows.find((x) => x.gameId === heavy.gameId) : null;
  console.log(model(r.gpu.short).slice(0, 23).padEnd(24) +
    (r.power ? `${r.power.totalW}W`.padStart(7) + `${r.power.psuW}W`.padStart(7) : '—'.padStart(14)) +
    (row?.low1PctFps != null ? `   ${row.fps} avg / ${row.low1PctFps} low` : ''));
}
console.log(`\nModelled, not measured. Frame rates come from the estimator, not from running the games.\n`);
