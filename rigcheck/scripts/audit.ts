/**
 * Data-quality audit. Checks everything the reconciler's per-record validation
 * cannot see: cross-record consistency, family ordering, catalogue/fixture
 * coherence, and — most importantly — a full engine sweep proving that every
 * part in the catalogue produces a finite, sane estimate rather than a NaN,
 * a negative, or a crash. Exit code 1 on any hard failure.
 *
 * Hard failures are things that would corrupt an answer. Warnings are gaps or
 * oddities that degrade confidence but are visibly handled (nulls widen bands).
 */
import { existsSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { buildEngineData, ANCHOR_RAM, search } from '../src/core/catalogue.ts';
import { estimate } from '../src/core/engine.ts';
import { compareBuilds, sweepComponent, bestFromInventory, futureProofing, priceToTarget } from '../src/core/queries.ts';
import type { Build, CpuRecord, GpuRecord } from '../src/core/types.ts';
import type { Fixture } from './validate.ts';

const ROOT = new URL('..', import.meta.url).pathname;
const load = <T,>(p: string): T => JSON.parse(readFileSync(join(ROOT, p), 'utf8')) as T;

const failures: string[] = [];
const warnings: string[] = [];
const fail = (msg: string) => failures.push(msg);
const warn = (msg: string) => warnings.push(msg);

function main() {
  const gpusFile = load<{ records: GpuRecord[] }>('data/catalogue/gpus.json');
  const cpusFile = load<{ records: CpuRecord[] }>('data/catalogue/cpus.json');
  const gamesFile = load<{ records: any[] }>('data/catalogue/games.json');
  const refsFile = load<{ records: any[] }>('data/catalogue/references.json');
  const fixtures = load<{ records: Fixture[] }>('data/fixtures/fixtures.json').records;
  const data = buildEngineData({ gpus: gpusFile, cpus: cpusFile, games: gamesFile, references: refsFile } as never);

  // ------------------------------------------------------------------ GPUs --
  const socketMem: Record<string, string[]> = {
    LGA1155: ['DDR3'], LGA1150: ['DDR3'], LGA1151: ['DDR4', 'DDR3'], 'LGA1151-v2': ['DDR4'],
    LGA1200: ['DDR4'], LGA1700: ['DDR4', 'DDR5'], LGA1851: ['DDR5'],
    LGA2011: ['DDR3'], 'LGA2011-v3': ['DDR4'], LGA2066: ['DDR4'],
    'AM3+': ['DDR3'], FM2: ['DDR3'], 'FM2+': ['DDR3'], AM4: ['DDR4'], AM5: ['DDR5'],
    sTR4: ['DDR4'], sTRX4: ['DDR4'], sWRX8: ['DDR4'], sTR5: ['DDR5'],
  };

  const seenNames = new Map<string, string>();
  for (const g of gpusFile.records) {
    const id = g.id;
    // Duplicate display identity: two records may share a name only if variant differs.
    const nameKey = `${g.fullName}|${g.variant ?? ''}`;
    if (seenNames.has(nameKey)) fail(`GPU duplicate display identity: ${id} and ${seenNames.get(nameKey)} share "${nameKey}"`);
    seenNames.set(nameKey, id);

    if (g.caps.rayTracing && !g.caps.meshShaders) {
      fail(`GPU ${id}: rayTracing=true with meshShaders=false — no shipping architecture has that combination`);
    }
    if (g.formFactor === 'desktop') {
      if (g.vramGB == null) fail(`GPU ${id}: desktop card with null vramGB — the VRAM cliff cannot evaluate`);
      if (g.shaders == null && g.fp32TFLOPS == null) warn(`GPU ${id}: no compute figure; index will refuse`);
    }
    // ISO-8601 reduced precision (YYYY or YYYY-MM) is valid; only garbage fails.
    if (g.launchDate && !/^\d{4}(-\d{2}){0,2}$/.test(g.launchDate)) warn(`GPU ${id}: malformed launchDate "${g.launchDate}"`);
    if (g.boostClockMHz != null && g.baseClockMHz != null && g.boostClockMHz < g.baseClockMHz) {
      fail(`GPU ${id}: boost ${g.boostClockMHz} below base ${g.baseClockMHz}`);
    }
    if (g.caps.meshShaders && g.caps.dxFeatureLevel !== '12_2') {
      warn(`GPU ${id}: meshShaders=true but dxFeatureLevel ${g.caps.dxFeatureLevel} (expected 12_2)`);
    }
  }

  // Family monotonicity spot-checks: within a chip family and generation,
  // a higher tier must not have fewer shaders. Checked via known ladders.
  const ladder = (ids: string[], field: (g: GpuRecord) => number | null | undefined, label: string) => {
    let prev: number | null = null;
    let prevId = '';
    for (const id of ids) {
      const g = data.gpus.get(id);
      if (!g) continue;
      const v = field(g);
      if (v == null) continue;
      if (prev != null && v < prev) fail(`${label}: ${id} (${v}) below ${prevId} (${prev})`);
      prev = v;
      prevId = id;
    }
  };
  ladder(['nvidia-geforce-rtx-3060-12gb', 'nvidia-geforce-rtx-3060-ti-gddr6', 'nvidia-geforce-rtx-3070', 'nvidia-geforce-rtx-3080-10gb', 'nvidia-geforce-rtx-3090'], (g) => g.shaders, 'Ampere shader ladder');
  ladder(['nvidia-geforce-rtx-4060', 'nvidia-geforce-rtx-4070', 'nvidia-geforce-rtx-4080', 'nvidia-geforce-rtx-4090'], (g) => g.shaders, 'Ada shader ladder');
  ladder(['amd-radeon-rx-6600', 'amd-radeon-rx-6700-xt', 'amd-radeon-rx-6800-xt', 'amd-radeon-rx-6900-xt'], (g) => g.shaders, 'RDNA2 shader ladder');

  // ------------------------------------------------------------------ CPUs --
  for (const c of cpusFile.records) {
    const allowed = socketMem[c.socket];
    if (!allowed) warn(`CPU ${c.id}: socket ${c.socket} not in the audit's socket->memory table`);
    else if (!c.memoryType.every((m) => allowed.includes(m))) {
      fail(`CPU ${c.id}: socket ${c.socket} claims memory ${c.memoryType.join('/')}; plausible set is ${allowed.join('/')}`);
    }
    if (c.boostClockMHz != null && c.baseClockMHz != null && c.boostClockMHz < c.baseClockMHz) {
      fail(`CPU ${c.id}: boost ${c.boostClockMHz} below base ${c.baseClockMHz}`);
    }
    if (c.igpuId && !data.gpus.has(c.igpuId)) warn(`CPU ${c.id}: dangling igpuId ${c.igpuId} (known gap; nulling would falsely assert no iGPU)`);
    if (c.vcache && ![96, 128].includes(c.l3CacheMB ?? 0)) fail(`CPU ${c.id}: vcache with L3 ${c.l3CacheMB}MB — every shipping X3D part is 96 or 128`);
  }

  // ----------------------------------------------------------------- games --
  const refIds = new Set(refsFile.records.map((r: any) => r.gameId));
  for (const g of gamesFile.records) {
    if (!refIds.has(g.id)) fail(`game ${g.id}: no reference — the engine will refuse to estimate it`);
    const d = g.vramDemandGB ?? {};
    const seq = ['1080p', '1440p', '2160p'].map((r) => d[r]).filter((v: number) => v != null);
    for (let i = 1; i < seq.length; i++) if (seq[i] < seq[i - 1]) fail(`game ${g.id}: vramDemandGB not monotonic: ${seq.join(' -> ')}`);
    if (g.requirements?.minThreads != null && g.requirements?.minCores != null && g.requirements.minThreads < g.requirements.minCores) {
      fail(`game ${g.id}: minThreads ${g.requirements.minThreads} < minCores ${g.requirements.minCores}`);
    }
    // Upscaling arrives by PATCH, frequently years after launch, so a game's
    // release year says nothing about whether it supports DLSS — Fortnite
    // shipped in 2017 and got DLSS in 2021. The rule that used to live here
    // compared `year` against 2018 and flagged three CORRECT records as
    // suspect, which is worse than no rule: it trains the reader to ignore the
    // warning list. What is genuinely checkable is a definitional
    // contradiction, and there is exactly one.
    const upscalers: string[] = g.upscalingSupport ?? [];
    // 'none' is the sentinel for a title with no upscaler, and must be alone:
    // ['none', 'fsr'] is a contradiction, not a longer list.
    const KNOWN_UPSCALERS = ['none', 'dlss', 'fsr', 'xess', 'tsr'];
    for (const u of upscalers) {
      if (!KNOWN_UPSCALERS.includes(u)) fail(`game ${g.id}: unknown upscaling technology "${u}"`);
    }
    if (new Set(upscalers).size !== upscalers.length) fail(`game ${g.id}: duplicate entries in upscalingSupport`);
    if (upscalers.includes('none') && upscalers.length > 1) {
      fail(`game ${g.id}: upscalingSupport lists 'none' alongside ${upscalers.filter((u) => u !== 'none').join(', ')}`);
    }
    // TSR is an Unreal Engine feature. A non-Unreal title cannot have it, and
    // claiming it would apply an upscaling gain the game cannot deliver.
    if (upscalers.includes('tsr') && !/unreal engine/i.test(String(g.engine ?? ''))) {
      fail(`game ${g.id}: claims TSR but the engine is "${g.engine}". TSR is an Unreal Engine feature.`);
    }
  }
  for (const r of refsFile.records) {
    const seq = ['1080p', '1440p', '2160p'].map((res) => r.gpuBound?.[res]).filter((v: number) => v != null);
    for (let i = 1; i < seq.length; i++) if (seq[i] >= seq[i - 1]) fail(`reference ${r.gameId}: gpuBound not decreasing: ${seq.join(' -> ')}`);
    if (!(r.cpuBound > 0)) fail(`reference ${r.gameId}: cpuBound ${r.cpuBound}`);
  }

  // -------------------------------------------------------------- fixtures --
  const fxIds = new Set<string>();
  for (const f of fixtures) {
    if (fxIds.has(f.id)) fail(`fixture ${f.id}: duplicate id`);
    fxIds.add(f.id);
    if (!data.gpus.has(f.gpuId)) fail(`fixture ${f.id}: unknown gpu ${f.gpuId}`);
    if (!data.cpus.has(f.cpuId)) fail(`fixture ${f.id}: unknown cpu ${f.cpuId}`);
    if (!data.games.has(f.gameId)) fail(`fixture ${f.id}: unknown game ${f.gameId}`);
    if (f.low1Pct != null && f.low1Pct > f.avgFps) fail(`fixture ${f.id}: low1Pct ${f.low1Pct} above avg ${f.avgFps}`);
    if (!(f.avgFps > 0 && f.avgFps < 2000)) fail(`fixture ${f.id}: implausible avgFps ${f.avgFps}`);
    const cpu = data.cpus.get(f.cpuId);
    if (cpu && f.ram?.type && !cpu.memoryType.includes(f.ram.type)) {
      fail(`fixture ${f.id}: ${f.cpuId} (${cpu.socket}) cannot take ${f.ram.type}`);
    }
  }


  // ------------------------------------------------------- physical sanity --
  //
  // These check specifications against physics rather than against each other.
  // A record can be perfectly self-consistent and still describe a card that
  // could not be manufactured, and the three checks below are the ones that
  // catch it: a memory system's bandwidth is fixed by its bus width and the
  // data rate its chips run at, a frame buffer has to be built out of chips
  // that exist, and a hybrid CPU's thread count follows from its core layout.
  //
  // Every one of these fired on first run and every one was WRONG — the ranges
  // assumed modern parts and the catalogue reaches back to 2010. The bounds
  // below are the corrected ones, and they are wide on purpose: the job is to
  // catch the impossible, not to second-guess the unusual.

  // Per-pin data rates each memory technology actually shipped at, across its
  // whole life. Early GDDR5 ran at 3.2 Gbps on Fermi; late GDDR6 reaches 20 on
  // RDNA 4. A single narrow "typical" figure would flag a third of the
  // catalogue as broken.
  const MEM_RATE_GBPS: Record<string, [number, number]> = {
    GDDR3: [1.6, 2.5], GDDR5: [3.0, 8.2], GDDR5X: [10.0, 11.5],
    GDDR6: [12.0, 20.5], GDDR6X: [19.0, 24.0], GDDR7: [26.0, 32.5],
    HBM: [0.8, 1.2], HBM2: [1.6, 2.5], HBM2E: [2.4, 3.6],
    DDR3: [1.2, 2.2], DDR4: [1.8, 3.4], LPDDR5: [5.5, 8.5],
  };
  // GDDR chip densities per 32-bit channel: 1Gbit through 16Gbit. Clamshell
  // puts two chips on a channel and doubles any of them.
  const CHIP_MB = [128, 256, 512, 1024, 2048];
  const VALID_PER_CHANNEL_MB = new Set([...CHIP_MB, ...CHIP_MB.map((d) => d * 2)]);

  for (const g of gpusFile.records) {
    const type = (g.vramType ?? '').toUpperCase();
    const range = MEM_RATE_GBPS[type];
    if (g.memBandwidthGBs && g.memBusBits && range) {
      const rate = (g.memBandwidthGBs * 8) / g.memBusBits;
      if (rate < range[0] || rate > range[1]) {
        fail(
          `GPU ${g.id}: ${g.memBandwidthGBs}GB/s across ${g.memBusBits} bits implies ${rate.toFixed(2)} Gbps per pin, outside the ${range[0]}-${range[1]} range ${type} ever shipped at. One of bandwidth, bus width or memory type is wrong.`,
        );
      }
    }
    if (g.vramGB && g.memBusBits && !type.startsWith('HBM')) {
      const channels = Math.floor(g.memBusBits / 32);
      const perChannelMB = (g.vramGB * 1024) / channels;
      if (!VALID_PER_CHANNEL_MB.has(perChannelMB)) {
        // A non-integer figure means an asymmetric memory system, which four
        // Nvidia cards genuinely had (GTX 550 Ti, 650 Ti Boost, 660, 660 Ti).
        // Real, deliberate, and not something to hard-fail.
        if (Number.isInteger(perChannelMB)) {
          fail(
            `GPU ${g.id}: ${g.vramGB}GB across ${channels} channels needs ${perChannelMB}MB chips, which is not a density that exists.`,
          );
        } else {
          warn(
            `GPU ${g.id}: ${g.vramGB}GB on a ${g.memBusBits}-bit bus is an asymmetric memory configuration (${perChannelMB.toFixed(1)}MB per channel). Real on a handful of Nvidia parts; verify it is one of them.`,
          );
        }
      }
    }
  }

  for (const c of cpusFile.records) {
    const { cores, threads, pCores, eCores } = c as unknown as {
      cores?: number; threads?: number; pCores?: number; eCores?: number;
    };
    if (cores != null && threads != null) {
      if (threads < cores) fail(`CPU ${c.id}: ${threads} threads on ${cores} cores — fewer threads than cores is impossible.`);
      if (threads > cores * 2) fail(`CPU ${c.id}: ${threads} threads on ${cores} cores — more than two threads per core does not exist on x86.`);
    }
    if (pCores != null && eCores != null) {
      if (cores != null && pCores + eCores !== cores) {
        fail(`CPU ${c.id}: ${pCores}P + ${eCores}E = ${pCores + eCores}, but the record says ${cores} cores.`);
      }
      if (threads != null && cores != null) {
        // SMT is inferred rather than assumed: Arrow Lake is hybrid AND has no
        // hyper-threading, so 8P+16E is 24 threads, not 32. Hard-coding
        // "P-cores are SMT" flagged all 13 Arrow Lake parts as broken.
        const smt = threads > cores;
        const want = smt ? pCores * 2 + eCores : pCores + eCores;
        if (want !== threads) {
          fail(`CPU ${c.id}: ${pCores}P + ${eCores}E with SMT ${smt ? 'on' : 'off'} gives ${want} threads, but the record says ${threads}.`);
        }
      }
    }
    const base = (c as unknown as { baseClockMHz?: number }).baseClockMHz;
    const boost = (c as unknown as { boostClockMHz?: number }).boostClockMHz;
    if (base && boost && boost < base) fail(`CPU ${c.id}: boost ${boost}MHz is below base ${base}MHz.`);
  }

  // Socket determines memory generation. A record that disagrees describes a
  // machine that cannot be assembled, and the build planner would emit it as a
  // parts list.
  const SOCKET_MEM: Record<string, string[]> = {
    AM4: ['DDR4'], AM5: ['DDR5'], 'AM3+': ['DDR3'], FM2: ['DDR3'], 'FM2+': ['DDR3'],
    LGA1700: ['DDR4', 'DDR5'], LGA1851: ['DDR5'], LGA1200: ['DDR4'],
    LGA1151: ['DDR3', 'DDR4'], LGA1150: ['DDR3'], LGA1155: ['DDR3'],
    LGA2066: ['DDR4'], LGA2011: ['DDR3'], 'LGA2011-3': ['DDR4'],
    STR4: ['DDR4'], STRX4: ['DDR4'], STR5: ['DDR5'], SP3: ['DDR4'],
  };
  for (const c of cpusFile.records) {
    const allowed = SOCKET_MEM[c.socket];
    if (!allowed) continue;
    for (const m of c.memoryType ?? []) {
      if (!allowed.includes(m)) {
        fail(`CPU ${c.id}: socket ${c.socket} cannot take ${m} (it takes ${allowed.join(' or ')}).`);
      }
    }
  }


  // ------------------------------------------------ identity cross-check ----
  //
  // The one check in this file that consults something OUTSIDE the project: the
  // PCI ID Repository, which is the registry vendors actually submit their
  // device names to. Everything else here proves the catalogue is consistent
  // with itself; this proves the parts exist.
  //
  // It is name-based and therefore imperfect in one direction only — a card
  // whose registry entry omits a suffix reads as missing even though it is
  // there (the RX 5500 XT shares a device ID with the plain 5500 and the
  // registry spells only the latter). So a miss is a warning, never a failure:
  // a false alarm here must not be able to fail a build.
  const pciPath = join(ROOT, 'data/aliases/pci-devices.json');
  if (existsSync(pciPath)) {
    const pci = load<{ devices: { vendor: string; name: string; subsystems: { name: string }[] }[] }>(
      'data/aliases/pci-devices.json',
    );
    const byVendor = new Map<string, string>();
    const acc: Record<string, string[]> = {};
    for (const d of pci.devices) {
      (acc[d.vendor] ??= []).push(d.name.toLowerCase());
      for (const s of d.subsystems) acc[d.vendor].push(s.name.toLowerCase());
    }
    for (const [v, list] of Object.entries(acc)) byVendor.set(v, list.join(' || '));

    // Longest suffix first, or XTX truncates to XT and matches the wrong card.
    const KEY = /\b(?:rtx|gtx|gts|gt|rx|arc)\s*([a-z]?\d{3,4})(?:\s*(xtx|gre|ti\s*super|super|ti|xt))?/i;
    let confirmed = 0;
    const unmatched: string[] = [];
    for (const g of gpusFile.records) {
      if (g.formFactor === 'igpu') continue;
      const m = KEY.exec(g.fullName);
      if (!m) continue;
      const num = m[1].toLowerCase();
      const suffix = (m[2] ?? '').toLowerCase();
      // Build the pattern from TOKENS. Escaping the whole string first and then
      // substituting the whitespace produced a literal backslash followed by
      // "s*", which matches nothing — it silently reported 57 real cards as
      // absent from the registry.
      const pattern =
        num.replace(/[.*+?^${}()|[\]\\]/g, '\\$&') +
        (suffix ? `\\s*${suffix.split(/\s+/).map((t) => t.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')).join('\\s*')}` : '');
      if (new RegExp(pattern).test(byVendor.get(g.vendor) ?? '')) confirmed++;
      else unmatched.push(`${g.id} (searched for "${num}${suffix ? ` ${suffix}` : ''}")`);
    }
    console.log(`  PCI registry: ${confirmed} discrete GPU model numbers confirmed present, ${unmatched.length} not found`);
    for (const u of unmatched) {
      warn(`GPU identity: ${u} does not appear in the PCI registry. Usually a registry naming quirk rather than a wrong record — check before assuming the part is fictitious.`);
    }
  } else {
    warn('PCI registry cross-check skipped: data/aliases/pci-devices.json is missing. Run `npm run parse` after `npm run harvest`.');
  }

  // ------------------------------------------------- engine full-part sweep --
  // Every part must produce a finite, positive estimate (or a legitimate gate
  // block) in a neutral title. NaN, negative, or a throw is a hard failure.
  const sweepGame = 'cyberpunk-2077';
  let gateBlockedGpus = 0;
  for (const g of data.gpus.values()) {
    const b: Build = {
      id: `audit-${g.id}`, cpuId: 'amd-ryzen-7-5800x', gpuId: g.id,
      ram: { ...ANCHOR_RAM }, storage: 'nvme-gen3', target: { resolution: '1080p', refreshHz: 144 },
    };
    try {
      const est = estimate(b, sweepGame, '1080p', data);
      if (est.status === 'ok') {
        if (!Number.isFinite(est.avgFps!) || est.avgFps! <= 0) fail(`engine sweep GPU ${g.id}: avgFps ${est.avgFps}`);
        if (!Number.isFinite(est.low1PctFps!) || est.low1PctFps! < 0) fail(`engine sweep GPU ${g.id}: low1Pct ${est.low1PctFps}`);
        if (est.avgFps! > 3000) fail(`engine sweep GPU ${g.id}: absurd ${est.avgFps!.toFixed(0)}fps`);
        if (!Number.isFinite(est.uncertainty!) || est.uncertainty! <= 0) fail(`engine sweep GPU ${g.id}: uncertainty ${est.uncertainty}`);
      } else if (est.status === 'NO_ESTIMATE') {
        warn(`engine sweep GPU ${g.id}: NO_ESTIMATE (record incomplete — fields pending harvest)`);
      } else gateBlockedGpus++;
    } catch (e) {
      fail(`engine sweep GPU ${g.id}: threw ${(e as Error).message}`);
    }
  }
  let gateBlockedCpus = 0;
  for (const c of data.cpus.values()) {
    const ramType = c.memoryType.includes('DDR4') ? 'DDR4' : c.memoryType[0];
    const speed = ramType === 'DDR5' ? 6000 : ramType === 'DDR4' ? 3200 : 1600;
    const b: Build = {
      id: `audit-${c.id}`, cpuId: c.id, gpuId: 'nvidia-geforce-rtx-3060-12gb',
      ram: { totalGB: 16, channels: 2, speedMTs: speed, type: ramType as never },
      storage: 'nvme-gen3', target: { resolution: '1080p', refreshHz: 144 },
    };
    try {
      const est = estimate(b, sweepGame, '1080p', data);
      if (est.status === 'ok') {
        if (!Number.isFinite(est.avgFps!) || est.avgFps! <= 0) fail(`engine sweep CPU ${c.id}: avgFps ${est.avgFps}`);
        if (est.avgFps! > 3000) fail(`engine sweep CPU ${c.id}: absurd ${est.avgFps!.toFixed(0)}fps`);
      } else if (est.status === 'NO_ESTIMATE') {
        warn(`engine sweep CPU ${c.id}: NO_ESTIMATE (record incomplete — fields pending harvest)`);
      } else gateBlockedCpus++;
    } catch (e) {
      fail(`engine sweep CPU ${c.id}: threw ${(e as Error).message}`);
    }
  }

  // Sanity ordering on the sweep: a 4090 must out-index a 1060; a 9800X3D must
  // beat an FX-8350 in a modern title. Cheap canaries against silent inversion.
  const fps = (cpuId: string, gpuId: string) => {
    const est = estimate({ id: 'x', cpuId, gpuId, ram: { ...ANCHOR_RAM }, storage: 'nvme-gen3', target: { resolution: '1440p', refreshHz: 144 } }, sweepGame, '1440p', data);
    return est.status === 'ok' ? est.avgFps! : NaN;
  };
  const canaries: [string, number, number][] = [
    ['4090 > 1060', fps('amd-ryzen-7-5800x', 'nvidia-geforce-rtx-4090'), fps('amd-ryzen-7-5800x', 'nvidia-geforce-gtx-1060-6gb')],
    ['4090 > 3060', fps('amd-ryzen-7-5800x', 'nvidia-geforce-rtx-4090'), fps('amd-ryzen-7-5800x', 'nvidia-geforce-rtx-3060-12gb')],
    ['9800X3D > FX-8350', fps('amd-ryzen-7-9800x3d', 'nvidia-geforce-rtx-4090'), fps('amd-fx-8350', 'nvidia-geforce-rtx-4090')],
    ['7800X3D > 2600K', fps('amd-ryzen-7-7800x3d', 'nvidia-geforce-rtx-4090'), fps('intel-core-i7-2600k', 'nvidia-geforce-rtx-4090')],
  ];
  for (const [label, a, b] of canaries) {
    if (!(a > b)) fail(`ordering canary "${label}" inverted: ${a?.toFixed(1)} vs ${b?.toFixed(1)}`);
  }

  // ------------------------------------------------------ query surface -----
  try {
    const b1: Build = { id: 'q1', cpuId: 'amd-ryzen-5-3600', gpuId: 'nvidia-geforce-rtx-3060-12gb', ram: { ...ANCHOR_RAM }, storage: 'nvme-gen3', target: { resolution: '1440p', refreshHz: 144 } };
    const b2: Build = { id: 'q2', cpuId: 'amd-ryzen-7-5800x3d', gpuId: 'amd-radeon-rx-6800-xt', ram: { ...ANCHOR_RAM }, storage: 'nvme-gen3', target: { resolution: '1440p', refreshHz: 144 } };
    const games = [...data.games.keys()].slice(0, 6);
    const m = compareBuilds([b1, b2], games, ['1080p', '1440p'], data, { baselineBuildId: 'q1' });
    if (m.cells.length !== 2 * games.length * 2) fail(`compareBuilds: expected ${2 * games.length * 2} cells, got ${m.cells.length}`);
    if (!m.summary.every((s) => Number.isFinite(s.geomeanFps))) fail('compareBuilds: non-finite geomean');

    const sweepRes = sweepComponent(b1, 'gpu', ['nvidia-geforce-rtx-3060-ti-gddr6', 'nvidia-geforce-rtx-3070', 'nvidia-geforce-rtx-3080-10gb'], games.slice(0, 3), ['1440p'], data);
    if (sweepRes.points.length !== 3) fail('sweepComponent: wrong point count');

    const inv = bestFromInventory(
      { cpuIds: ['amd-ryzen-5-3600', 'intel-core-i7-2600k'], gpuIds: ['nvidia-geforce-rtx-3060-12gb', 'nvidia-geforce-gtx-1060-6gb'], ramKits: [{ totalGB: 16, channels: 2, speedMTs: 3200, type: 'DDR4' }, { totalGB: 8, channels: 2, speedMTs: 1600, type: 'DDR3' }], storage: ['nvme-gen3'] },
      games.slice(0, 3), ['1080p'], data,
    );
    if (!inv.build) fail('bestFromInventory: assembled nothing from a viable pile');
    else if (inv.build.cpuId === 'intel-core-i7-2600k' && inv.build.ram.type === 'DDR4') fail('bestFromInventory: paired DDR4 with an LGA1155 part');

    const fp = futureProofing(b1, data);
    if (!(fp.factors.length >= 4)) fail('futureProofing: too few factors');

    const p2t = priceToTarget(games.slice(0, 2), '1080p', 60, 1000, { cpuIds: ['amd-ryzen-5-3600'], gpuIds: ['nvidia-geforce-rtx-3060-12gb'] }, data, () => undefined);
    if (!p2t.unmetReason) fail('priceToTarget: no prices supplied yet it claims success');

    const hits = search('7700', data);
    const kinds = new Set(hits.map((h) => h.kind));
    if (!(kinds.has('cpu') && kinds.has('gpu'))) fail('search("7700"): does not surface both CPU and GPU namesakes');
  } catch (e) {
    fail(`query surface threw: ${(e as Error).message}`);
  }

  // ------------------------------------------------------------------ report --
  console.log(`audited: ${gpusFile.records.length} GPUs, ${cpusFile.records.length} CPUs, ${gamesFile.records.length} games, ${refsFile.records.length} references, ${fixtures.length} fixtures`);
  console.log(`engine sweep: every part estimated; gate-blocked ${gateBlockedGpus} GPU(s) / ${gateBlockedCpus} CPU(s) (legitimate capability blocks in ${sweepGame})`);
  console.log(`\n${failures.length} hard failure(s), ${warnings.length} warning(s)`);
  for (const f of failures) console.log(`  FAIL ${f}`);
  const shownWarnings = process.env.AUDIT_ALL ? warnings : warnings.slice(0, 15);
  for (const w of shownWarnings) console.log(`  warn ${w}`);
  if (warnings.length > shownWarnings.length) console.log(`  ... and ${warnings.length - shownWarnings.length} more warnings`);
  process.exit(failures.length ? 1 : 0);
}

main();
