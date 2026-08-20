/**
 * Wave 3 parser — identity/alias table.
 *
 * Pure function over the cached pci.ids file plus the two driver tables. No
 * network. Re-runnable.
 *
 * Output feeds two things:
 *  1. The fuzzy search disambiguator, so typing "7700" surfaces i7-7700,
 *     7700K, 7700X and RX 7700 XT unmistakably (spec §5.1).
 *  2. A cross-check on the seed catalogue: a SKU name that appears nowhere in
 *     the PCI registry is flagged for review.
 */
import { readFileSync, writeFileSync, existsSync, mkdirSync } from 'node:fs';
import { join } from 'node:path';

const ROOT = new URL('..', import.meta.url).pathname;
const RAW = join(ROOT, 'cache/raw');
const OUT = join(ROOT, 'data/aliases');

const GPU_VENDORS: Record<string, 'nvidia' | 'amd' | 'intel'> = {
  '10de': 'nvidia',
  '1002': 'amd',
  '1022': 'amd', // AMD host bridges; some APU graphics appear here
  '8086': 'intel',
};

export interface PciDevice {
  vendorId: string;
  deviceId: string;
  vendor: 'nvidia' | 'amd' | 'intel';
  name: string;
  subsystems: { subvendor: string; subdevice: string; name: string }[];
}

/**
 * pci.ids layout: vendor at column 0, device indented one tab, subsystem two.
 * Comments start with '#'. Blank lines ignored. The file also contains a
 * device-class section after a line starting with 'C ' which we must stop at.
 */
export function parsePciIds(text: string): PciDevice[] {
  const out: PciDevice[] = [];
  let vendorId: string | null = null;
  let current: PciDevice | null = null;

  for (const line of text.split('\n')) {
    if (!line.trim() || line.startsWith('#')) continue;
    // Class section marks the end of the vendor/device section.
    if (/^C\s/.test(line)) break;

    if (!line.startsWith('\t')) {
      const m = /^([0-9a-fA-F]{4})\s+(.*)$/.exec(line);
      vendorId = m ? m[1].toLowerCase() : null;
      current = null;
      continue;
    }
    if (!vendorId || !GPU_VENDORS[vendorId]) continue;

    if (line.startsWith('\t\t')) {
      const m = /^\t\t([0-9a-fA-F]{4})\s+([0-9a-fA-F]{4})\s+(.*)$/.exec(line);
      if (m && current) current.subsystems.push({ subvendor: m[1], subdevice: m[2], name: m[3].trim() });
      continue;
    }

    const m = /^\t([0-9a-fA-F]{4})\s+(.*)$/.exec(line);
    if (!m) continue;
    current = {
      vendorId,
      deviceId: m[1].toLowerCase(),
      vendor: GPU_VENDORS[vendorId],
      name: m[2].trim(),
      subsystems: [],
    };
    out.push(current);
  }
  return out;
}

/** Extract `CHIP_...` family markers alongside device ids from the amdgpu driver. */
export function parseAmdgpuFamilies(text: string): { deviceId: string; family: string }[] {
  const out: { deviceId: string; family: string }[] = [];
  const re = /0x([0-9A-Fa-f]{4}),\s*(?:PCI_ANY_ID,\s*PCI_ANY_ID,\s*)?[^,]*,\s*[^,]*,\s*CHIP_([A-Z0-9_]+)/g;
  let m: RegExpExecArray | null;
  while ((m = re.exec(text))) out.push({ deviceId: m[1].toLowerCase(), family: m[2] });
  // Fallback pattern: `{0x1002, 0x1234, ..., CHIP_NAVI10}`
  const re2 = /\{\s*0x1002\s*,\s*0x([0-9A-Fa-f]{4})[^}]*CHIP_([A-Z0-9_]+)/g;
  while ((m = re2.exec(text))) out.push({ deviceId: m[1].toLowerCase(), family: m[2] });
  return out;
}

/**
 * Nvidia's open kernel module PCI table.
 *
 * Note: this table matches by device CLASS with PCI_ANY_ID wildcards rather than
 * enumerating device ids, so it yields no per-SKU list. Recorded explicitly so
 * the coverage report states the real reason rather than showing a near-zero
 * count that looks like a parser bug. Nvidia identity therefore comes from
 * pci.ids alone, which carries 1,900+ named Nvidia devices.
 */
export function parseNvidiaPciTable(text: string): { ids: string[]; wildcardMatch: boolean; note: string } {
  const ids = new Set<string>();
  const re = /0x([0-9A-Fa-f]{4})\s*,\s*0x([0-9A-Fa-f]{4})/g;
  let m: RegExpExecArray | null;
  while ((m = re.exec(text))) {
    if (m[1].toLowerCase() === '10de') ids.add(m[2].toLowerCase());
  }
  const wildcardMatch = /PCI_ANY_ID/.test(text);
  return {
    ids: [...ids],
    wildcardMatch,
    note: wildcardMatch
      ? 'Table matches by device class with PCI_ANY_ID wildcards; it does not enumerate per-SKU device ids. Nvidia identity sourced from pci.ids instead.'
      : 'Enumerated device ids extracted.',
  };
}

/**
 * Normalise a marketing name into search tokens. This is what makes "7700"
 * surface four different parts distinguishably.
 */
export function tokenise(name: string): string[] {
  return name
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, ' ')
    .trim()
    .split(' ')
    .filter(Boolean);
}

/** Exported so `scripts/parse.ts` can run this step; still self-runs when invoked directly. */
export function main() {
  mkdirSync(OUT, { recursive: true });
  const pciPath = join(RAW, 'pci-ids.raw');
  if (!existsSync(pciPath)) {
    console.error('cache/raw/pci-ids.raw missing — run `npm run harvest` first.');
    process.exit(1);
  }

  const devices = parsePciIds(readFileSync(pciPath, 'utf8'));
  const byVendor = { nvidia: 0, amd: 0, intel: 0 };
  for (const d of devices) byVendor[d.vendor]++;

  let amdFamilies: { deviceId: string; family: string }[] = [];
  const amdPath = join(RAW, 'linux-amdgpu-drv.raw');
  if (existsSync(amdPath)) amdFamilies = parseAmdgpuFamilies(readFileSync(amdPath, 'utf8'));

  let nvidia = { ids: [] as string[], wildcardMatch: false, note: 'source not cached' };
  const nvPath = join(RAW, 'nvidia-open-pci-table.raw');
  if (existsSync(nvPath)) nvidia = parseNvidiaPciTable(readFileSync(nvPath, 'utf8'));

  const payload = {
    generatedAt: new Date().toISOString(),
    provenance: {
      'pci-ids': {
        id: 'pci-ids',
        source: 'PCI ID Repository (pciutils/pciids)',
        url: 'https://raw.githubusercontent.com/pciutils/pciids/master/pci.ids',
        licence: 'GPL-2.0-or-later OR BSD-3-Clause',
        retrievedAt: new Date().toISOString().slice(0, 10),
      },
      'linux-amdgpu': {
        id: 'linux-amdgpu',
        source: 'Linux kernel amdgpu driver',
        url: 'https://raw.githubusercontent.com/torvalds/linux/master/drivers/gpu/drm/amd/amdgpu/amdgpu_drv.c',
        licence: 'MIT / GPL-2.0',
        retrievedAt: new Date().toISOString().slice(0, 10),
      },
      'nvidia-open': {
        id: 'nvidia-open',
        source: 'NVIDIA open-gpu-kernel-modules PCI table',
        url: 'https://raw.githubusercontent.com/NVIDIA/open-gpu-kernel-modules/main/kernel-open/nvidia/nv-pci-table.c',
        licence: 'MIT / GPL-2.0 dual',
        retrievedAt: new Date().toISOString().slice(0, 10),
      },
    },
    counts: { ...byVendor, total: devices.length, amdFamilies: amdFamilies.length, nvidiaSupportedIds: nvidia.ids.length },
    devices,
    amdFamilies,
    nvidiaSupportedIds: nvidia.ids,
    nvidiaTableNote: nvidia.note,
  };

  writeFileSync(join(OUT, 'pci-devices.json'), JSON.stringify(payload, null, 2));
  console.log(
    `pci.ids: ${devices.length} graphics-vendor devices (nvidia ${byVendor.nvidia}, amd ${byVendor.amd}, intel ${byVendor.intel})`,
  );
  console.log(`amdgpu families: ${amdFamilies.length}`);
  console.log(`nvidia driver table: ${nvidia.note}`);
}

if (process.argv[1] && process.argv[1].endsWith('parse-aliases.ts')) main();
