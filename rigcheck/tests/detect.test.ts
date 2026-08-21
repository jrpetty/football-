import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { buildEngineData } from '../src/core/catalogue.ts';
import { detectHardware, detectionToBuild } from '../src/core/detect.ts';

const ROOT = new URL('..', import.meta.url).pathname;
const load = (p: string) => JSON.parse(readFileSync(join(ROOT, p), 'utf8'));
const data = buildEngineData({
  gpus: load('data/catalogue/gpus.json'),
  cpus: load('data/catalogue/cpus.json'),
  games: load('data/catalogue/games.json'),
  references: load('data/catalogue/references.json'),
});

describe('hardware detection', () => {
  it('reads a dxdiag-style dump', () => {
    const d = detectHardware(
      `Operating System: Windows 11 Pro 64-bit
       Processor: AMD Ryzen 5 3600 6-Core Processor (12 CPUs), ~3.6GHz
       Memory: 16384MB RAM
       Card name: NVIDIA GeForce RTX 3060
       Display Mode: 2560 x 1440 (144Hz)`,
      data,
    );
    expect(d.cpu[0].record.id).toBe('amd-ryzen-5-3600');
    expect(d.gpu[0].record.id).toContain('rtx-3060');
    expect(d.resolution).toBe('1440p');
    expect(d.refreshHz).toBe(144);
  });

  it('reads the harness detector JSON', () => {
    const d = detectHardware(
      JSON.stringify({ CpuName: 'Intel Core i5-12400F', GpuName: 'NVIDIA GeForce RTX 4060', RamGB: 32, RamChannels: 2, RamMTs: 3200, Storage: 'nvme-gen4' }),
      data,
    );
    expect(d.cpu[0].record.id).toBe('intel-core-i5-12400f');
    expect(d.ram?.totalGB).toBe(32);
    expect(d.storage).toBe('nvme-gen4');
  });

  it('distinguishes X3D from its non-X3D sibling', () => {
    const x3d = detectHardware('AMD Ryzen 7 7800X3D', data);
    const plain = detectHardware('AMD Ryzen 7 7700X', data);
    expect(x3d.cpu[0].record.id).toBe('amd-ryzen-7-7800x3d');
    expect(plain.cpu[0].record.vcache).toBeFalsy();
  });

  it('warns rather than guessing when a VRAM variant is ambiguous', () => {
    // "GTX 1060" alone cannot distinguish the 3GB and 6GB cards, which are
    // different silicon. Guessing here would corrupt everything downstream.
    const d = detectHardware('NVIDIA GeForce GTX 1060', data);
    expect(d.gpu.length).toBeGreaterThan(1);
    expect(d.warnings.join(' ')).toMatch(/ambiguous/i);
  });

  it('flags a single-channel detection as needing confirmation', () => {
    const d = detectHardware('Ryzen 5 5600, RTX 3060, 1x16GB DDR4-3200', data);
    expect(d.ram?.channels).toBe(1);
    expect(d.warnings.join(' ')).toMatch(/single memory channel/i);
  });

  it('reports unmatched hardware instead of silently returning nothing', () => {
    const d = detectHardware('Card name: Voodoo 3dfx Banshee 9000', data);
    expect(d.gpu.length).toBe(0);
    expect(d.unmatched.length + d.warnings.length).toBeGreaterThan(0);
  });


  // --- harness/detect-hardware.ps1 payloads --------------------------------
  //
  // The nested `rigcheck-hardware/1` document and the flat Get-HardwareProfile
  // row are different shapes from the same harness. Reading only one of them
  // produced a build with no parts and silent 16GB/3200 defaults, which looks
  // like a successful detection.

  const nestedPayload = (over: Record<string, unknown> = {}) => ({
    schema: 'rigcheck-hardware/1',
    generatedBy: 'harness/detect-hardware.ps1',
    cpu: { name: 'AMD Ryzen 5 3600 6-Core Processor', vendor: 'AuthenticAMD', cores: 6, threads: 12 },
    gpu: { name: 'NVIDIA GeForce RTX 3060', vendor: 'nvidia', vramGB: 12, vramSource: 'registry qwMemorySize', driverVersion: '560.94' },
    memory: { totalGB: 32, channels: 2, speedMTs: 3600, type: 'DDR4', modulesPopulated: 2, slotsTotal: 4 },
    storage: { system: { path: 'C:', class: 'sata-ssd' }, games: { path: 'D:\\Steam', class: 'nvme-gen4' } },
    display: { widthPx: 2560, heightPx: 1440, refreshHz: 165, catalogueResolution: '1440p' },
    catalogueMapping: { suggestedCpuId: '', suggestedGpuId: '', confirmedCpuId: '', confirmedGpuId: '' },
    bestEffort: [],
    ...over,
  });

  it('reads the nested rigcheck-hardware/1 document', () => {
    const d = detectHardware(JSON.stringify(nestedPayload()), data);
    expect(d.cpu[0].record.id).toBe('amd-ryzen-5-3600');
    expect(d.gpu[0].record.id).toContain('rtx-3060');
    expect(d.ram?.totalGB).toBe(32);
    expect(d.ram?.channels).toBe(2);
    expect(d.ram?.speedMTs).toBe(3600);
    expect(d.ram?.type).toBe('DDR4');
    expect(d.resolution).toBe('1440p');
    expect(d.refreshHz).toBe(165);
    expect(d.driverVersion).toBe('560.94');
  });

  it('prefers the games drive over the system drive', () => {
    // It is the games drive that governs streaming and load stutter, so a
    // fast games SSD behind a slow boot drive must not be recorded as slow.
    const d = detectHardware(JSON.stringify(nestedPayload()), data);
    expect(d.storage).toBe('nvme-gen4');
  });

  it('honours a confirmed catalogue id over name matching', () => {
    // "RTX 3080" cannot distinguish the 10GB and 12GB cards. A confirmed id
    // is the operator's answer to exactly that, so it must win outright.
    const d = detectHardware(
      JSON.stringify(
        nestedPayload({
          gpu: { name: 'NVIDIA GeForce RTX 3080', vendor: 'nvidia', vramGB: 10 },
          catalogueMapping: { confirmedCpuId: 'amd-ryzen-7-5800x3d', confirmedGpuId: 'nvidia-geforce-rtx-3080-12gb' },
        }),
      ),
      data,
    );
    expect(d.cpu[0].record.id).toBe('amd-ryzen-7-5800x3d');
    expect(d.cpu[0].confidence).toBe(100);
    expect(d.gpu[0].record.id).toBe('nvidia-geforce-rtx-3080-12gb');
    expect(d.gpu.length).toBe(1);
  });

  it('says so loudly when a confirmed id is not in the catalogue', () => {
    const d = detectHardware(
      JSON.stringify(nestedPayload({ catalogueMapping: { confirmedGpuId: 'nvidia-geforce-rtx-9090-ti' } })),
      data,
    );
    expect(d.warnings.join(' ')).toMatch(/no such record is in the catalogue/i);
    // and it still falls back rather than returning nothing
    expect(d.gpu[0].record.id).toContain('rtx-3060');
  });

  it('uses detected VRAM to break a variant tie', () => {
    const d = detectHardware(
      JSON.stringify(nestedPayload({ gpu: { name: 'NVIDIA GeForce GTX 1060', vendor: 'nvidia', vramGB: 3 } })),
      data,
    );
    expect(d.gpu[0].record.id).toBe('nvidia-geforce-gtx-1060-3gb');
    expect(d.gpu[0].matchedOn).toMatch(/3GB VRAM/);
  });

  it('carries the detector best-effort list through as warnings', () => {
    const d = detectHardware(
      JSON.stringify(
        nestedPayload({
          bestEffort: [{ field: 'memory.channels', why: 'INFERRED from 2 populated slots; check against the board manual.' }],
        }),
      ),
      data,
    );
    expect(d.warnings.join(' ')).toMatch(/INFERRED from 2 populated slots/);
  });

  it('normalises LPDDR5 to the catalogue memory type', () => {
    const d = detectHardware(
      JSON.stringify(nestedPayload({ memory: { totalGB: 16, channels: 2, speedMTs: 6400, type: 'LPDDR5' } })),
      data,
    );
    expect(d.ram?.type).toBe('DDR5');
  });

  it('falls back to text matching for JSON that is neither harness shape', () => {
    const d = detectHardware('{"somethingElse": true}', data);
    expect(d.warnings.join(' ')).toMatch(/matched neither harness shape/i);
  });

  it('builds a usable Build from a confident detection', () => {
    const d = detectHardware('Ryzen 5 3600 / RTX 3060 12GB / 16GB DDR4-3200', data);
    const b = detectionToBuild(d);
    expect(b).not.toBeNull();
    expect(data.cpus.has(b!.cpuId)).toBe(true);
    expect(data.gpus.has(b!.gpuId)).toBe(true);
  });
});

describe('memory parsed from free text, in the order people actually write it', () => {
  // The regression this guards: the parser scanned the whole line for the first
  // number that fit, so a card mentioned before the memory supplied both the
  // capacity and the speed. "RX 6800 XT" produced 6800 MT/s of DDR4.
  const cases: [string, { totalGB?: number; speedMTs?: number; type?: string; channels?: number }][] = [
    ['Ryzen 5 3600 / RTX 3060 12GB / 16GB DDR4-3200', { totalGB: 16, speedMTs: 3200, type: 'DDR4' }],
    ['i7-9700K, RTX 2070 8GB, 16GB DDR4 3600MHz', { totalGB: 16, speedMTs: 3600, type: 'DDR4' }],
    ['Ryzen 7 5800X3D / RX 6800 XT / 32GB DDR4-3600', { totalGB: 32, speedMTs: 3600, type: 'DDR4' }],
    ['i5-12400F, Arc A750, 16GB DDR5-5200', { totalGB: 16, speedMTs: 5200, type: 'DDR5' }],
    ['Ryzen 5 7600 + RTX 4070 + 32GB DDR5 6000 MT/s', { totalGB: 32, speedMTs: 6000, type: 'DDR5' }],
    // Memory first still works — the ordering that used to be the only one that did.
    ['32GB DDR4-3600, Ryzen 7 5800X3D, RTX 3080 10GB', { totalGB: 32, speedMTs: 3600, type: 'DDR4' }],
    ['Ryzen 5 5600, RTX 3060, 1x16GB DDR4-3200', { totalGB: 16, speedMTs: 3200, channels: 1 }],
    ['Ryzen 9 7950X, RTX 4090 24GB, 2x32GB DDR5-6000', { totalGB: 64, speedMTs: 6000, channels: 2 }],
  ];

  for (const [text, want] of cases) {
    it(`reads "${text}"`, () => {
      const d = detectHardware(text, data);
      if (want.totalGB != null) expect(d.ram?.totalGB, 'totalGB').toBe(want.totalGB);
      if (want.speedMTs != null) expect(d.ram?.speedMTs, 'speedMTs').toBe(want.speedMTs);
      if (want.type != null) expect(d.ram?.type, 'type').toBe(want.type);
      if (want.channels != null) expect(d.ram?.channels, 'channels').toBe(want.channels);
    });
  }

  it('never reports a memory speed a DDR generation cannot reach', () => {
    // The specific absurdity the old parser produced from a card's model number.
    const d = detectHardware('Ryzen 7 5800X3D / RX 6800 XT / 32GB DDR4-3600', data);
    expect(d.ram?.type).toBe('DDR4');
    expect(d.ram?.speedMTs).toBeLessThan(5000);
  });

  it('does not invent a speed from a bare model number with no memory mentioned', () => {
    const d = detectHardware('Ryzen 5 3600 and an RTX 3060', data);
    expect(d.ram?.speedMTs).toBeUndefined();
  });
});
