/**
 * Catalogue loading and search.
 *
 * Kept dependency-free and isomorphic so the same loader serves the CLI scripts
 * and the browser bundle.
 */
import type { CpuRecord, GameRecord, GpuRecord, ProvenanceTable, RamConfig } from './types.ts';
import type { EngineData, GameReference } from './engine.ts';
import { ANCHORS } from './constants.ts';

export interface CatalogueFiles {
  gpus: { records: GpuRecord[]; provenance: ProvenanceTable };
  cpus: { records: CpuRecord[]; provenance: ProvenanceTable };
  games: { records: GameRecord[]; provenance: ProvenanceTable };
  references: { records: GameReference[] };
}

/** The anchor build's memory configuration. Part of the index definition. */
export const ANCHOR_RAM: RamConfig = {
  totalGB: 16,
  channels: 2,
  speedMTs: 3200,
  type: 'DDR4',
  timings: { cl: 16, trcd: 18, trp: 18, tras: 36 },
};

export function buildEngineData(files: CatalogueFiles): EngineData {
  const gpus = new Map(files.gpus.records.map((r) => [r.id, r]));
  const cpus = new Map(files.cpus.records.map((r) => [r.id, r]));
  const games = new Map(files.games.records.map((r) => [r.id, r]));
  const references = new Map(files.references.records.map((r) => [r.gameId, r]));

  const anchorGpu = gpus.get(ANCHORS.gpuId);
  const anchorCpu = cpus.get(ANCHORS.cpuId);
  if (!anchorGpu) throw new Error(`Anchor GPU ${ANCHORS.gpuId} missing from catalogue — the index has no defined scale without it.`);
  if (!anchorCpu) throw new Error(`Anchor CPU ${ANCHORS.cpuId} missing from catalogue — the index has no defined scale without it.`);

  return { gpus, cpus, games, references, anchorGpu, anchorCpu, anchorRam: ANCHOR_RAM };
}

// ---------------------------------------------------------------------------
// Search / disambiguation
// ---------------------------------------------------------------------------

export interface SearchHit {
  id: string;
  kind: 'cpu' | 'gpu';
  label: string;
  /** Text that makes this hit unmistakable against near-identical names. */
  disambiguator: string;
  score: number;
}

function normalise(s: string): string {
  return s.toLowerCase().replace(/[^a-z0-9]+/g, ' ').trim();
}

/**
 * Fuzzy search that must disambiguate clearly: typing "7700" has to surface
 * i7-7700, i7-7700K, Ryzen 7 7700X and RX 7700 XT, each unmistakable. That means
 * every hit carries a disambiguator built from the fields that actually differ.
 */
export function search(query: string, data: EngineData, limit = 20): SearchHit[] {
  const q = normalise(query);
  if (!q) return [];
  const tokens = q.split(' ');
  const hits: SearchHit[] = [];

  const score = (haystack: string): number => {
    const h = normalise(haystack);
    if (h === q) return 1000;
    let s = 0;
    for (const t of tokens) {
      if (!h.includes(t)) return 0;
      // Prefer matches on word boundaries over mid-token substrings.
      s += new RegExp(`\\b${t}\\b`).test(h) ? 20 : 8;
    }
    // Shorter names that still match are usually the more canonical part.
    return s + Math.max(0, 30 - h.length / 2);
  };

  for (const g of data.gpus.values()) {
    const sc = score(`${g.fullName} ${g.brand} ${g.variant ?? ''} ${g.chip ?? ''}`);
    if (sc > 0) {
      hits.push({
        id: g.id,
        kind: 'gpu',
        label: g.fullName,
        disambiguator: [
          g.architecture,
          g.vramGB != null ? `${g.vramGB}GB ${g.vramType ?? ''}`.trim() : null,
          g.memBusBits != null ? `${g.memBusBits}-bit` : null,
          g.chip,
          g.formFactor === 'igpu' ? 'integrated' : null,
          g.launchDate?.slice(0, 4),
        ]
          .filter(Boolean)
          .join(' · '),
        score: sc,
      });
    }
  }

  for (const c of data.cpus.values()) {
    const sc = score(`${c.fullName} ${c.brand} ${c.variant ?? ''} ${c.codename ?? ''}`);
    if (sc > 0) {
      hits.push({
        id: c.id,
        kind: 'cpu',
        label: c.fullName,
        disambiguator: [
          c.architecture,
          `${c.cores}C/${c.threads}T`,
          c.socket,
          c.vcache ? '3D V-Cache' : null,
          c.launchDate?.slice(0, 4),
        ]
          .filter(Boolean)
          .join(' · '),
        score: sc,
      });
    }
  }

  return hits.sort((a, b) => b.score - a.score).slice(0, limit);
}
