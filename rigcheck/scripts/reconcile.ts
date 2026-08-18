/**
 * Wave 4 — Reconciler. Single-threaded by design: it is the one place where
 * ordering matters, and the only merge point in the pipeline.
 *
 * Responsibilities:
 *  1. Merge every agent output into canonical catalogue files.
 *  2. Enforce the internal-consistency rules from agents/BRIEF.md and REJECT
 *     records that violate them — a wrong number silently corrupts the fitted
 *     model, which is worse than a documented gap.
 *  3. Cross-check SKU names against the harvested PCI device registry.
 *  4. Detect duplicate ids across agents and record conflicts with provenance.
 */
import { existsSync, mkdirSync, readFileSync, readdirSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import type { CpuRecord, GameRecord, GpuRecord, ProvenanceTable } from '../src/core/types.ts';
import type { GameReference } from '../src/core/engine.ts';

const ROOT = new URL('..', import.meta.url).pathname;
const OUT_DIR = join(ROOT, 'agents/out');
const DATA = join(ROOT, 'data/catalogue');

interface AgentFile {
  agentId: string;
  kind: 'gpu' | 'cpu' | 'game' | 'game-ref' | 'fixture';
  provenance: ProvenanceTable;
  records: unknown[];
  expectedCount?: number;
  actualCount?: number;
  gaps?: string[];
  uncertainties?: string[];
}

interface Rejection {
  agentId: string;
  recordId: string;
  rule: string;
  detail: string;
}

const rejections: Rejection[] = [];

function reject(agentId: string, recordId: string, rule: string, detail: string) {
  rejections.push({ agentId, recordId, rule, detail });
}

/** Expand the `{"*": [...]}` shorthand into a per-field provenance map. */
function expandProv(rec: Record<string, unknown>): Record<string, string[]> {
  const p = (rec._prov ?? {}) as Record<string, string[]>;
  if (p['*']) {
    const out: Record<string, string[]> = {};
    for (const k of Object.keys(rec)) if (!k.startsWith('_')) out[k] = p['*'];
    return out;
  }
  return p;
}

function validateGpu(agentId: string, r: GpuRecord): boolean {
  let ok = true;
  if (!r.id || !r.vendor || !r.architecture) {
    reject(agentId, r.id ?? '(no id)', 'required-fields', 'Missing id, vendor or architecture.');
    return false;
  }
  if (!r.caps || typeof r.caps.meshShaders !== 'boolean') {
    reject(agentId, r.id, 'caps-required', 'caps.meshShaders is required — it drives WILL_NOT_RUN.');
    ok = false;
  }
  if (r.shaders != null && r.boostClockMHz != null && r.fp32TFLOPS != null) {
    const calc = (r.shaders * r.boostClockMHz * 2) / 1e6;
    const drift = Math.abs(calc - r.fp32TFLOPS) / r.fp32TFLOPS;
    if (drift > 0.08) {
      reject(agentId, r.id, 'tflops-consistency', `stated ${r.fp32TFLOPS} vs computed ${calc.toFixed(2)} (${(drift * 100).toFixed(0)}% drift)`);
      ok = false;
    }
  }
  if (r.memBusBits != null && r.memBandwidthGBs != null && r.memBusBits > 0) {
    // Implied effective memory clock must land in a physically plausible range.
    const impliedMts = (r.memBandwidthGBs * 8 * 1000) / r.memBusBits;
    if (impliedMts < 500 || impliedMts > 40000) {
      reject(agentId, r.id, 'bandwidth-plausibility', `bus ${r.memBusBits}b with ${r.memBandwidthGBs}GB/s implies ${impliedMts.toFixed(0)} MT/s`);
      ok = false;
    }
  }
  if (r.vramGB != null && (r.vramGB <= 0 || r.vramGB > 64)) {
    reject(agentId, r.id, 'vram-range', `vramGB ${r.vramGB} outside plausible range`);
    ok = false;
  }
  return ok;
}

function validateCpu(agentId: string, r: CpuRecord): boolean {
  let ok = true;
  if (!r.id || !r.vendor) {
    reject(agentId, r.id ?? '(no id)', 'required-fields', 'Missing id or vendor.');
    return false;
  }
  if (!r.socket) {
    reject(agentId, r.id, 'socket-required', 'socket must never be null — the inventory optimiser is unsound without it.');
    ok = false;
  }
  if (!Array.isArray(r.memoryType) || r.memoryType.length === 0) {
    reject(agentId, r.id, 'memory-type-required', 'memoryType must never be empty.');
    ok = false;
  }
  if (!r.cores || !r.threads || r.threads < r.cores) {
    reject(agentId, r.id, 'core-thread-consistency', `cores ${r.cores}, threads ${r.threads}`);
    ok = false;
  }
  if (r.pCores != null && r.eCores != null) {
    if (r.pCores + r.eCores !== r.cores) {
      reject(agentId, r.id, 'hybrid-topology', `pCores ${r.pCores} + eCores ${r.eCores} != cores ${r.cores}`);
      ok = false;
    }
  }
  if (r.vcache && !/x3d/i.test(r.id) && !/x3d/i.test(r.fullName ?? '')) {
    reject(agentId, r.id, 'vcache-flag', 'vcache set on a part whose name is not an X3D part.');
    ok = false;
  }
  return ok;
}

function validateGameRef(agentId: string, r: GameReference): boolean {
  let ok = true;
  if (!r.gameId) {
    reject(agentId, '(no gameId)', 'required-fields', 'gameId is required.');
    return false;
  }
  if (!r.cpuBound || r.cpuBound <= 0) {
    reject(agentId, r.gameId, 'cpu-bound-positive', `cpuBound ${r.cpuBound} must be positive.`);
    ok = false;
  }
  const order: (keyof typeof r.gpuBound)[] = ['1080p', '1440p', '2160p'];
  const vals = order.map((k) => r.gpuBound?.[k]).filter((v): v is number => v != null);
  if (vals.some((v) => v <= 0)) {
    reject(agentId, r.gameId, 'gpu-bound-positive', 'gpuBound values must all be positive.');
    ok = false;
  }
  // Higher resolution must never be faster: a violation means the record is
  // transposed, which would silently invert every resolution comparison.
  for (let i = 1; i < vals.length; i++) {
    if (vals[i] >= vals[i - 1]) {
      reject(agentId, r.gameId, 'resolution-monotonicity', `gpuBound not decreasing with resolution: ${vals.join(' -> ')}`);
      ok = false;
      break;
    }
  }
  if (r.gpuScalingExponent != null && (r.gpuScalingExponent < 0.8 || r.gpuScalingExponent > 1.15)) {
    reject(agentId, r.gameId, 'scaling-exponent-range', `gpuScalingExponent ${r.gpuScalingExponent} outside 0.80-1.15.`);
    ok = false;
  }
  return ok;
}

function validateGame(agentId: string, r: GameRecord): boolean {
  if (!r.id || !r.name || !r.archetype) {
    reject(agentId, r.id ?? '(no id)', 'required-fields', 'Missing id, name or archetype.');
    return false;
  }
  if (!r.requirements) {
    reject(agentId, r.id, 'requirements-required', 'requirements block is required — it drives the gates.');
    return false;
  }
  return true;
}

function main() {
  mkdirSync(DATA, { recursive: true });
  if (!existsSync(OUT_DIR)) {
    console.error('No agents/out directory. Run the catalogue agents first.');
    process.exit(1);
  }

  const files = readdirSync(OUT_DIR).filter((f) => f.endsWith('.json'));
  const gpus = new Map<string, GpuRecord>();
  const cpus = new Map<string, CpuRecord>();
  const games = new Map<string, GameRecord>();
  const refs = new Map<string, GameReference>();
  const fixtures: unknown[] = [];
  const provenance: ProvenanceTable = {};
  const duplicates: { id: string; agents: string[] }[] = [];
  const owner = new Map<string, string>();
  const agentSummaries: { agentId: string; kind: string; expected?: number; accepted: number; rejected: number; gaps: string[] }[] = [];

  for (const f of files) {
    let parsed: AgentFile;
    try {
      parsed = JSON.parse(readFileSync(join(OUT_DIR, f), 'utf8')) as AgentFile;
    } catch (e) {
      console.error(`SKIP ${f}: invalid JSON — ${e instanceof Error ? e.message : e}`);
      continue;
    }
    Object.assign(provenance, parsed.provenance ?? {});

    let accepted = 0;
    const before = rejections.length;

    // Game references and fixtures are keyed differently and are not part of
    // the id-uniqueness namespace, so they are handled before the main loop.
    if (parsed.kind === 'game-ref') {
      for (const raw of parsed.records ?? []) {
        const r = raw as GameReference;
        if (!validateGameRef(parsed.agentId, r)) continue;
        refs.set(r.gameId, r);
      }
      agentSummaries.push({
        agentId: parsed.agentId,
        kind: parsed.kind,
        expected: parsed.expectedCount,
        accepted: refs.size,
        rejected: rejections.length - before,
        gaps: parsed.gaps ?? [],
      });
      continue;
    }
    if (parsed.kind === 'fixture') {
      fixtures.push(...(parsed.records ?? []));
      agentSummaries.push({
        agentId: parsed.agentId,
        kind: parsed.kind,
        expected: parsed.expectedCount,
        accepted: (parsed.records ?? []).length,
        rejected: 0,
        gaps: parsed.gaps ?? [],
      });
      continue;
    }

    for (const raw of parsed.records ?? []) {
      const rec = raw as Record<string, unknown>;
      rec._prov = expandProv(rec);
      const id = String(rec.id ?? '');

      if (owner.has(id)) {
        const existing = duplicates.find((d) => d.id === id);
        if (existing) existing.agents.push(parsed.agentId);
        else duplicates.push({ id, agents: [owner.get(id)!, parsed.agentId] });
        continue;
      }

      if (parsed.kind === 'gpu') {
        if (!validateGpu(parsed.agentId, rec as unknown as GpuRecord)) continue;
        gpus.set(id, rec as unknown as GpuRecord);
      } else if (parsed.kind === 'cpu') {
        if (!validateCpu(parsed.agentId, rec as unknown as CpuRecord)) continue;
        cpus.set(id, rec as unknown as CpuRecord);
      } else if (parsed.kind === 'game') {
        if (!validateGame(parsed.agentId, rec as unknown as GameRecord)) continue;
        games.set(id, rec as unknown as GameRecord);
      } else {
        continue;
      }
      owner.set(id, parsed.agentId);
      accepted++;
    }

    agentSummaries.push({
      agentId: parsed.agentId,
      kind: parsed.kind,
      expected: parsed.expectedCount,
      accepted,
      rejected: rejections.length - before,
      gaps: parsed.gaps ?? [],
    });
  }

  // Cross-check against the harvested PCI registry: a GPU whose marketing name
  // appears nowhere in the device registry is flagged (not rejected — OEM and
  // regional variants legitimately go missing from pci.ids).
  let unverified: string[] = [];
  const aliasPath = join(ROOT, 'data/aliases/pci-devices.json');
  if (existsSync(aliasPath)) {
    const alias = JSON.parse(readFileSync(aliasPath, 'utf8')) as { devices: { name: string; vendor: string }[] };
    const haystack = alias.devices.map((d) => d.name.toLowerCase());
    for (const g of gpus.values()) {
      if (g.formFactor === 'igpu') continue;
      const key = g.brand.toLowerCase().replace(/^(nvidia|amd|intel)\s+/, '');
      if (!haystack.some((n) => n.includes(key))) unverified.push(g.id);
    }
  }

  // Dangling igpuId references. NOT a rejection: nulling the field would assert
  // "this CPU has no integrated graphics", which is false and worse than a
  // recorded gap. Reported so the missing iGPU records get harvested.
  const danglingIgpu = new Map<string, number>();
  for (const c of cpus.values()) {
    if (c.igpuId && !gpus.has(c.igpuId)) {
      danglingIgpu.set(c.igpuId, (danglingIgpu.get(c.igpuId) ?? 0) + 1);
    }
  }

  const stamp = new Date().toISOString();
  writeFileSync(join(DATA, 'gpus.json'), JSON.stringify({ generatedAt: stamp, provenance, count: gpus.size, records: [...gpus.values()] }, null, 2));
  writeFileSync(join(DATA, 'cpus.json'), JSON.stringify({ generatedAt: stamp, provenance, count: cpus.size, records: [...cpus.values()] }, null, 2));
  writeFileSync(join(DATA, 'games.json'), JSON.stringify({ generatedAt: stamp, provenance, count: games.size, records: [...games.values()] }, null, 2));
  writeFileSync(join(DATA, 'references.json'), JSON.stringify({ generatedAt: stamp, provenance, count: refs.size, records: [...refs.values()] }, null, 2));

  // References without a matching game (or vice versa) mean the engine will
  // refuse to estimate rather than guess, so surface both directions.
  const refsWithoutGame = [...refs.keys()].filter((id) => !games.has(id));
  const gamesWithoutRef = [...games.keys()].filter((id) => !refs.has(id));

  if (fixtures.length) {
    mkdirSync(join(ROOT, 'data/fixtures'), { recursive: true });
    writeFileSync(
      join(ROOT, 'data/fixtures/fixtures.json'),
      JSON.stringify({ generatedAt: stamp, count: fixtures.length, records: fixtures }, null, 2),
    );
  }

  const report = {
    generatedAt: stamp,
    counts: { gpus: gpus.size, cpus: cpus.size, games: games.size },
    agents: agentSummaries,
    rejections,
    duplicates,
    unverifiedAgainstPciRegistry: unverified,
    danglingIgpuReferences: [...danglingIgpu.entries()].map(([id, count]) => ({ missingGpuId: id, referencedByCpus: count })),
    referencesWithoutGame: refsWithoutGame,
    gamesWithoutReference: gamesWithoutRef,
    fixtures: fixtures.length,
  };
  writeFileSync(join(ROOT, 'data/reconcile-report.json'), JSON.stringify(report, null, 2));

  console.log(`GPUs        ${gpus.size}`);
  console.log(`CPUs        ${cpus.size}`);
  console.log(`Games       ${games.size}`);
  console.log(`References  ${refs.size}${gamesWithoutRef.length ? `  (${gamesWithoutRef.length} game(s) with no reference — the engine will refuse to estimate these rather than guess)` : ''}`);
  console.log(`Fixtures    ${fixtures.length}`);
  console.log(`Rejected ${rejections.length} record(s); ${duplicates.length} duplicate id(s); ${unverified.length} GPU name(s) not found in the PCI registry.`);
  if (danglingIgpu.size) {
    const affected = [...danglingIgpu.values()].reduce((a, b) => a + b, 0);
    console.log(`\n${danglingIgpu.size} dangling igpuId reference(s) across ${affected} CPU record(s) — missing iGPU records:`);
    for (const [id, count] of [...danglingIgpu.entries()].sort((a, b) => b[1] - a[1]).slice(0, 10)) {
      console.log(`  ${String(count).padStart(4)} CPUs reference missing ${id}`);
    }
  }
  for (const r of rejections.slice(0, 15)) console.log(`  REJECT ${r.recordId} [${r.rule}] ${r.detail}`);
  if (rejections.length > 15) console.log(`  ... and ${rejections.length - 15} more (see data/reconcile-report.json)`);
}

main();
