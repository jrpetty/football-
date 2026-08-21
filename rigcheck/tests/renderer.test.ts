import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { buildEngineData } from '../src/core/catalogue.ts';
import { detectHardware } from '../src/core/detect.ts';
import { cleanDeviceName, parseRenderer } from '../src/core/renderer.ts';

const ROOT = new URL('..', import.meta.url).pathname;
const load = (p: string) => JSON.parse(readFileSync(join(ROOT, p), 'utf8'));
const data = buildEngineData({
  gpus: load('data/catalogue/gpus.json'),
  cpus: load('data/catalogue/cpus.json'),
  games: load('data/catalogue/games.json'),
  references: load('data/catalogue/references.json'),
});

/**
 * Renderer-string formats.
 *
 * These are fixtures, not observations: the machine this was written on has no
 * GPU and reports SwiftShader for every context it can create. They are stable,
 * long-standing platform formats, and the parser is built to pass the original
 * string through when it meets one it does not know — so an unrecognised format
 * degrades to "the detector did not recognise this", never to a wrong card.
 */
const STRINGS: [string, string][] = [
  ['ANGLE (NVIDIA, NVIDIA GeForce RTX 3060 Direct3D11 vs_5_0 ps_5_0, D3D11)', 'NVIDIA GeForce RTX 3060'],
  ['ANGLE (NVIDIA, NVIDIA GeForce RTX 4070 Ti SUPER Direct3D11 vs_5_0 ps_5_0, D3D11)', 'NVIDIA GeForce RTX 4070 Ti SUPER'],
  ['ANGLE (AMD, AMD Radeon RX 6800 XT Direct3D11 vs_5_0 ps_5_0, D3D11)', 'AMD Radeon RX 6800 XT'],
  ['ANGLE (Intel, Intel(R) UHD Graphics 770 Direct3D11 vs_5_0 ps_5_0, D3D11)', 'Intel(R) UHD Graphics 770'],
  ['ANGLE (NVIDIA GeForce GTX 1060 6GB Direct3D11 vs_5_0 ps_5_0)', 'NVIDIA GeForce GTX 1060 6GB'],
  ['ANGLE (Apple, ANGLE Metal Renderer: Apple M2 Pro, Unspecified Version)', 'Apple M2 Pro'],
  ['ANGLE (AMD, AMD Radeon RX 6600 (radeonsi, navi23, LLVM 15.0.7), OpenGL 4.6)', 'AMD Radeon RX 6600'],
  ['ANGLE (NVIDIA, NVIDIA GeForce RTX 4090 (0x00002684), Vulkan 1.3.224)', 'NVIDIA GeForce RTX 4090'],
  ['NVIDIA GeForce RTX 3060/PCIe/SSE2', 'NVIDIA GeForce RTX 3060'],
  ['AMD Radeon RX 6600 (radeonsi, navi23, LLVM 15.0.7, DRM 3.49, 6.2.0)', 'AMD Radeon RX 6600'],
  ['Apple M1 Pro', 'Apple M1 Pro'],
];

describe('renderer strings', () => {
  for (const [raw, want] of STRINGS) {
    it(`extracts the device from ${JSON.stringify(raw.slice(0, 44))}…`, () => {
      expect(parseRenderer(raw).device).toBe(want);
    });
  }

  it('names the vendor and the backend', () => {
    const r = parseRenderer('ANGLE (NVIDIA, NVIDIA GeForce RTX 3060 Direct3D11 vs_5_0 ps_5_0, D3D11)');
    expect(r.vendor).toBe('nvidia');
    expect(r.backend).toBe('d3d11');
    expect(r.software).toBe(false);
  });

  it('flags software rendering and keeps the string intact', () => {
    const r = parseRenderer('ANGLE (Google, Vulkan 1.3.0 (SwiftShader Device (Subzero) (0x0000C0DE)), SwiftShader driver)');
    expect(r.software).toBe(true);
    expect(r.device).toContain('SwiftShader');
  });

  it('passes an unrecognised format through rather than guessing', () => {
    // The rule the whole module rests on: a format this does not know yields
    // the original text for the detector to accept or reject, never a wrong card.
    const odd = 'Some Future Renderer Format 9000';
    const r = parseRenderer(odd);
    expect(r.device).toBe(odd);
    expect(r.confidence).toBe('bare');
  });

  it('survives an empty string', () => {
    expect(parseRenderer('').device).toBe('');
    expect(parseRenderer('').confidence).toBe('raw');
  });

  it('strips the trademark marks the catalogue does not use', () => {
    expect(cleanDeviceName('Intel(R) UHD Graphics 770')).toBe('Intel UHD Graphics 770');
    expect(cleanDeviceName('AMD Radeon(TM) Graphics')).toBe('AMD Radeon Graphics');
  });
});

describe('renderer strings reach the catalogue', () => {
  // The point of the module: the browser names the card, and the app finds it.
  const CASES: [string, string][] = [
    ['ANGLE (NVIDIA, NVIDIA GeForce RTX 3060 Direct3D11 vs_5_0 ps_5_0, D3D11)', 'nvidia-geforce-rtx-3060'],
    ['ANGLE (AMD, AMD Radeon RX 6800 XT Direct3D11 vs_5_0 ps_5_0, D3D11)', 'amd-radeon-rx-6800-xt'],
    ['ANGLE (NVIDIA, NVIDIA GeForce RTX 4090 (0x00002684), Vulkan 1.3.224)', 'nvidia-geforce-rtx-4090'],
    ['NVIDIA GeForce RTX 3060/PCIe/SSE2', 'nvidia-geforce-rtx-3060'],
    ['AMD Radeon RX 6600 (radeonsi, navi23, LLVM 15.0.7, DRM 3.49, 6.2.0)', 'amd-radeon-rx-6600'],
  ];

  for (const [raw, idPrefix] of CASES) {
    it(`finds ${idPrefix} from its renderer string`, () => {
      const { device } = parseRenderer(raw);
      const hit = detectHardware(cleanDeviceName(device), data).gpu[0];
      expect(hit, `no catalogue match for "${device}"`).toBeDefined();
      expect(hit.record.id).toContain(idPrefix);
    });
  }

  it('does not silently pick one of an ambiguous pair', () => {
    // "GTX 1060" alone spans the 3GB and 6GB cards, which are different silicon.
    const { device } = parseRenderer('ANGLE (NVIDIA GeForce GTX 1060 Direct3D11 vs_5_0 ps_5_0)');
    const d = detectHardware(cleanDeviceName(device), data);
    expect(d.gpu.length).toBeGreaterThan(1);
    expect(d.warnings.join(' ')).toMatch(/ambiguous/i);
  });

  it('reports nothing rather than something wrong for a software renderer', () => {
    const { device, software } = parseRenderer('ANGLE (Google, Vulkan 1.3.0 (SwiftShader Device (Subzero)), SwiftShader driver)');
    expect(software).toBe(true);
    const d = detectHardware(cleanDeviceName(device), data);
    expect(d.gpu.length).toBe(0);
  });
});
