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

  it('builds a usable Build from a confident detection', () => {
    const d = detectHardware('Ryzen 5 3600 / RTX 3060 12GB / 16GB DDR4-3200', data);
    const b = detectionToBuild(d);
    expect(b).not.toBeNull();
    expect(data.cpus.has(b!.cpuId)).toBe(true);
    expect(data.gpus.has(b!.gpuId)).toBe(true);
  });
});
