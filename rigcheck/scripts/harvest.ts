/**
 * Wave 2 — Harvester.
 *
 * Fetches raw source responses into a permanent cache. Does NOT parse: parsing
 * is Wave 3 and runs as pure functions over these cached files, so it can be
 * re-run indefinitely without touching the network.
 *
 * Sources this session's egress policy blocks are declared here anyway, marked
 * SOURCE_RESTRICTED. That is deliberate: the manifest is the record of what the
 * pipeline WANTS, so running this in an unrestricted environment fills the gaps
 * once a spec-parsing layer exists (see scripts/parse.ts, currently a stub — the
 * table expander is written, the column-to-record mapping is not). Blocked
 * sources are logged and skipped, never retried and
 * never routed around.
 */
import { mkdirSync, writeFileSync, existsSync, readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';

const ROOT = new URL('..', import.meta.url).pathname;
const CACHE = join(ROOT, 'cache/raw');
const MANIFEST = join(ROOT, 'cache/manifest.json');

type SourceStatus = 'OK' | 'SOURCE_RESTRICTED' | 'BLOCKED' | 'ERROR' | 'CACHED';

interface Source {
  id: string;
  url: string;
  licence: string;
  purpose: string;
  /** Known-blocked by egress policy in this environment. */
  restricted?: boolean;
  /** Restricted by the source's own terms — never fetch, per spec §2.2. */
  termsRestricted?: boolean;
}

const SOURCES: Source[] = [
  // --- Reachable: identity / alias data -----------------------------------
  {
    id: 'pci-ids',
    url: 'https://raw.githubusercontent.com/pciutils/pciids/master/pci.ids',
    licence: 'GPL-2.0-or-later OR BSD-3-Clause',
    purpose: 'PCI vendor/device ID to marketing name. Primary alias seed.',
  },
  {
    id: 'linux-amdgpu-drv',
    url: 'https://raw.githubusercontent.com/torvalds/linux/master/drivers/gpu/drm/amd/amdgpu/amdgpu_drv.c',
    licence: 'MIT (file header) / GPL-2.0 (kernel)',
    purpose: 'AMD PCI device id to ASIC family mapping.',
  },
  {
    id: 'nvidia-open-pci-table',
    url: 'https://raw.githubusercontent.com/NVIDIA/open-gpu-kernel-modules/main/kernel-open/nvidia/nv-pci-table.c',
    licence: 'MIT/GPL-2.0 dual',
    purpose: 'Nvidia supported PCI device ids.',
  },

  // --- Blocked by this session's egress policy ----------------------------
  {
    id: 'wikipedia-nvidia-gpus',
    url: 'https://en.wikipedia.org/w/api.php?action=parse&format=json&page=List_of_Nvidia_graphics_processing_units',
    licence: 'CC BY-SA 4.0',
    purpose: 'Nvidia GPU specifications.',
    restricted: true,
  },
  {
    id: 'wikipedia-amd-gpus',
    url: 'https://en.wikipedia.org/w/api.php?action=parse&format=json&page=List_of_AMD_graphics_processing_units',
    licence: 'CC BY-SA 4.0',
    purpose: 'AMD GPU specifications.',
    restricted: true,
  },
  {
    id: 'wikipedia-intel-cpus',
    url: 'https://en.wikipedia.org/w/api.php?action=parse&format=json&page=List_of_Intel_processors',
    licence: 'CC BY-SA 4.0',
    purpose: 'Intel CPU specifications.',
    restricted: true,
  },
  {
    id: 'wikipedia-amd-cpus',
    url: 'https://en.wikipedia.org/w/api.php?action=parse&format=json&page=List_of_AMD_processors',
    licence: 'CC BY-SA 4.0',
    purpose: 'AMD CPU specifications.',
    restricted: true,
  },
  {
    id: 'vulkan-gpuinfo',
    url: 'https://vulkan.gpuinfo.org/api/v3/reports.php',
    licence: 'Community submitted; see site terms',
    purpose: 'Capability gates: feature levels, extensions, limits.',
    restricted: true,
  },
  {
    id: 'openbenchmarking',
    url: 'https://openbenchmarking.org/api/index',
    licence: 'Open results',
    purpose: 'CPU throughput anchoring (compute workloads, not game FPS).',
    restricted: true,
  },

  // --- Never fetched: restricted by the source's own terms ----------------
  // Declared so the manifest records the decision explicitly rather than
  // leaving a silent gap. Two of these would also actively harm the model:
  // UserBenchmark's composite deliberately down-weights multi-core results.
  { id: 'userbenchmark', url: 'https://www.userbenchmark.com/', licence: 'RESTRICTED', purpose: 'never ingested', termsRestricted: true },
  { id: 'passmark', url: 'https://www.cpubenchmark.net/', licence: 'RESTRICTED', purpose: 'never ingested', termsRestricted: true },
  { id: 'geekbench', url: 'https://browser.geekbench.com/', licence: 'RESTRICTED', purpose: 'never ingested', termsRestricted: true },
  { id: 'notebookcheck', url: 'https://www.notebookcheck.net/', licence: 'RESTRICTED', purpose: 'never ingested', termsRestricted: true },
  { id: 'techpowerup', url: 'https://www.techpowerup.com/gpu-specs/', licence: 'RESTRICTED', purpose: 'never ingested', termsRestricted: true },
  { id: 'youtube', url: 'https://www.youtube.com/', licence: 'RESTRICTED', purpose: 'never ingested (figures are pixels in overlays)', termsRestricted: true },
];

interface ManifestEntry {
  id: string;
  url: string;
  licence: string;
  purpose: string;
  status: SourceStatus;
  bytes?: number;
  retrievedAt?: string;
  detail?: string;
  path?: string;
}

async function fetchOne(s: Source): Promise<ManifestEntry> {
  const base: ManifestEntry = { id: s.id, url: s.url, licence: s.licence, purpose: s.purpose, status: 'OK' };

  if (s.termsRestricted) {
    return { ...base, status: 'SOURCE_RESTRICTED', detail: "Source terms restrict automated collection. Never fetched." };
  }

  const path = join(CACHE, `${s.id}.raw`);
  if (existsSync(path)) {
    const bytes = readFileSync(path).byteLength;
    return { ...base, status: 'CACHED', bytes, path: `cache/raw/${s.id}.raw` };
  }

  if (s.restricted) {
    return {
      ...base,
      status: 'BLOCKED',
      detail: 'Egress policy denied (403 at proxy). Not retried, not routed around. Re-run harvest in an unrestricted environment to fill this.',
    };
  }

  try {
    const res = await fetch(s.url, { signal: AbortSignal.timeout(60_000) });
    if (!res.ok) return { ...base, status: 'ERROR', detail: `HTTP ${res.status}` };
    const body = Buffer.from(await res.arrayBuffer());
    mkdirSync(dirname(path), { recursive: true });
    writeFileSync(path, body);
    return {
      ...base,
      status: 'OK',
      bytes: body.byteLength,
      retrievedAt: new Date().toISOString(),
      path: `cache/raw/${s.id}.raw`,
    };
  } catch (err) {
    return { ...base, status: 'ERROR', detail: String(err instanceof Error ? err.message : err) };
  }
}

async function main() {
  mkdirSync(CACHE, { recursive: true });
  const entries: ManifestEntry[] = [];
  for (const s of SOURCES) {
    const e = await fetchOne(s);
    entries.push(e);
    const size = e.bytes ? ` ${(e.bytes / 1024).toFixed(0)}KB` : '';
    console.log(`${e.status.padEnd(18)} ${e.id}${size}${e.detail ? ` — ${e.detail}` : ''}`);
  }
  writeFileSync(MANIFEST, JSON.stringify({ generatedAt: new Date().toISOString(), entries }, null, 2));

  const ok = entries.filter((e) => e.status === 'OK' || e.status === 'CACHED').length;
  const blocked = entries.filter((e) => e.status === 'BLOCKED').length;
  const restricted = entries.filter((e) => e.status === 'SOURCE_RESTRICTED').length;
  console.log(`\n${ok} cached, ${blocked} egress-blocked, ${restricted} terms-restricted (never fetched), ${entries.length} declared.`);
}

main();
