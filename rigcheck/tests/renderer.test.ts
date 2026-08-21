import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { buildEngineData } from '../src/core/catalogue.ts';
import { detectHardware } from '../src/core/detect.ts';
import { cleanDeviceName, parseRenderer } from '../src/core/renderer.ts';
import { identifyGpu } from '../src/ui/bench/inspect.ts';

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

/* ------------------------------------------------- renderer to catalogue -- */

/**
 * The end of the chain: a renderer string in, a catalogue part out.
 *
 * These are the cases that decide whether the specification form fills itself,
 * and the distinction that matters is between a name the driver answered
 * precisely and one it genuinely left open. Filling the form with the 8GB card
 * when the driver only said "RTX 3060" would be a silently wrong answer, and a
 * silently wrong part is worse than an empty field.
 */
describe('identifying the card from the renderer string', () => {
  const id = (raw: string) => identifyGpu(raw, data);

  it('fills the form when exactly one part fits', () => {
    expect(id('ANGLE (NVIDIA, NVIDIA GeForce RTX 4090 (0x00002684), Vulkan 1.3.224)').gpuId)
      .toBe('nvidia-geforce-rtx-4090');
    expect(id('ANGLE (AMD, AMD Radeon RX 6800 XT Direct3D11 vs_5_0 ps_5_0, D3D11)').gpuId)
      .toBe('amd-radeon-rx-6800-xt');
  });

  it('is not put off by a runner-up that merely scores lower', () => {
    // "RTX 4070 Ti SUPER" also matches the plain Ti and the plain Super, but
    // less well. An earlier version treated any runner-up as ambiguity and so
    // filled the field almost never — the feature existed and never fired.
    const r = id('ANGLE (NVIDIA, NVIDIA GeForce RTX 4070 Ti SUPER Direct3D11 vs_5_0 ps_5_0, D3D11)');
    expect(r.gpuId).toBe('nvidia-geforce-rtx-4070-ti-super');
    expect(r.ambiguous).toHaveLength(0);
  });

  it('refuses to choose between parts the driver did not separate', () => {
    for (const [raw, count] of [
      ['ANGLE (NVIDIA, NVIDIA GeForce RTX 3060 Direct3D11 vs_5_0 ps_5_0, D3D11)', 2],
      ['ANGLE (NVIDIA GeForce GTX 1060 Direct3D11 vs_5_0 ps_5_0)', 2],
    ] as [string, number][]) {
      const r = id(raw);
      expect(r.gpuId).toBeNull();
      expect(r.ambiguous).toHaveLength(count);
      // The best guess is still named, so the user is choosing from a shortlist
      // rather than starting again.
      expect(r.gpuName).toBeTruthy();
    }
  });

  it('identifies integrated graphics, which is the case that matters most', () => {
    // The renderer string naming an integrated adapter is how you find out the
    // discrete card is idle. It used to match nothing at all: the detector's
    // GPU line had no pattern for any Intel integrated family.
    expect(id('ANGLE (Intel, Intel(R) UHD Graphics 630 Direct3D11 vs_5_0 ps_5_0, D3D11)').gpuId)
      .toBe('intel-uhd-630');
    expect(id('ANGLE (Intel, Intel(R) UHD Graphics 770 Direct3D11 vs_5_0 ps_5_0, D3D11)').gpuName)
      .toMatch(/UHD Graphics 770/);
  });

  it('never offers a card from a different vendor', () => {
    // Intel's UHD 770 and NVIDIA's GTX 770 share the token "770" and nothing
    // else, and the scorer saturates at 99 once a model matches — so they came
    // back as equally good answers and a machine on its integrated adapter was
    // offered a 2013 NVIDIA card.
    const r = id('ANGLE (Intel, Intel(R) UHD Graphics 770 Direct3D11 vs_5_0 ps_5_0, D3D11)');
    const names = [r.gpuName, ...r.ambiguous.map((a) => a.name)].join(' ');
    expect(names).not.toMatch(/NVIDIA|GeForce|GTX/);
  });

  it('says nothing at all when the renderer is a software rasteriser', () => {
    const r = id('ANGLE (Google, Vulkan 1.3.0 (SwiftShader Device (Subzero)), SwiftShader driver)');
    expect(r.gpuId).toBeNull();
    expect(r.ambiguous).toHaveLength(0);
    expect(r.identity.software).toBe(true);
  });
});
