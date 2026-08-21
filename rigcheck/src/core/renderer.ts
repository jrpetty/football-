/**
 * Reading the graphics card's name out of a WebGL renderer string.
 *
 * The browser knows exactly which card is rendering. `WEBGL_debug_renderer_info`
 * returns something like:
 *
 *     ANGLE (NVIDIA, NVIDIA GeForce RTX 4070 Ti SUPER Direct3D11 vs_5_0 ps_5_0, D3D11)
 *
 * That names a part this catalogue already has, and until now nothing connected
 * the two — the benchmark reported the string and the specification form still
 * asked the user to find the card in a list of 279.
 *
 * This module does ONE job: strip the wrapper and hand the bare device name to
 * the detector that already exists. It deliberately does not do its own
 * matching. `detectHardware` already scores candidates, disambiguates the four
 * different parts called "7700", knows that "GTX 1060" could be the 3GB or the
 * 6GB card and says so rather than guessing. Writing a second matcher here
 * would mean a second set of those decisions, drifting apart from the first.
 *
 * **On the format table below.** The machine this was written on has no GPU —
 * every context it can create reports SwiftShader — so the wrapper formats are
 * recalled rather than collected here, and the tests encode them as fixtures
 * rather than as observations. They are stable, long-standing formats, but that
 * is the provenance and it is why every rule is written to fall back to the raw
 * string rather than to a guess: a format this does not recognise yields the
 * original text, which the detector then either matches or reports as
 * unrecognised. Nothing is invented on the way through.
 */

/** What a renderer string turned out to describe. */
export interface RendererIdentity {
  /** The bare device name, ready for the catalogue detector. */
  device: string;
  /** Vendor as the driver reported it, lowercased: nvidia, amd, intel, apple, google, unknown. */
  vendor: string;
  /** The backend the browser went through, when the string says. */
  backend: 'd3d11' | 'd3d9' | 'vulkan' | 'metal' | 'opengl' | 'unknown';
  /** True when no GPU is involved at all. */
  software: boolean;
  /**
   * How much of the string was understood.
   *
   * `parsed` — a known wrapper was recognised and stripped.
   * `bare`   — no wrapper; the string already looked like a device name.
   * `raw`    — nothing was recognised, so the original is passed through
   *            unchanged for the detector to accept or reject on its own.
   */
  confidence: 'parsed' | 'bare' | 'raw';
}

const SOFTWARE = ['swiftshader', 'llvmpipe', 'softpipe', 'microsoft basic render', 'generic renderer', 'lavapipe'];

/**
 * Trailing platform noise. Each of these follows the device name and none of it
 * is part of the name: the Direct3D shader-model suffix, the Mesa driver
 * triple, the PCI device id, the OpenGL bus tags.
 */
const TAIL = [
  /\s+Direct3D\d+(?:\s+vs_\d+_\d+)?(?:\s+ps_\d+_\d+)?.*$/i,
  /\s+D3D\d+.*$/i,
  /\s*\(0x[0-9a-f]{2,8}\).*$/i,
  /\s*\((?:radeonsi|iris|crocus|i965|nouveau|zink|virgl|llvmpipe)[^)]*\).*$/i,
  /\s*\(LLVM[^)]*\).*$/i,
  /\s*\/(?:PCIe|AGP|SSE2|integrated)[\w/]*$/i,
  /,\s*(?:OpenGL|Vulkan|Metal|D3D)\s.*$/i,
  /\s+OpenGL\s+(?:ES\s+)?[\d.]+.*$/i,
  /\s+\(Compatibility Profile\).*$/i,
  /\s+Unspecified Version$/i,
];

const VENDORS: [RegExp, string][] = [
  [/\bnvidia\b/i, 'nvidia'],
  [/\b(?:amd|ati|advanced micro devices|radeon)\b/i, 'amd'],
  [/\bintel\b/i, 'intel'],
  [/\bapple\b/i, 'apple'],
  [/\b(?:google|swiftshader)\b/i, 'google'],
  [/\b(?:qualcomm|adreno)\b/i, 'qualcomm'],
  [/\b(?:arm|mali)\b/i, 'arm'],
];

function backendOf(raw: string): RendererIdentity['backend'] {
  if (/direct3d\s*11|\bd3d11\b/i.test(raw)) return 'd3d11';
  if (/direct3d\s*9|\bd3d9\b/i.test(raw)) return 'd3d9';
  if (/\bvulkan\b/i.test(raw)) return 'vulkan';
  if (/\bmetal\b/i.test(raw)) return 'metal';
  if (/\bopengl\b/i.test(raw)) return 'opengl';
  return 'unknown';
}

function vendorOf(raw: string): string {
  for (const [re, name] of VENDORS) if (re.test(raw)) return name;
  return 'unknown';
}

/** Remove the trailing platform noise, repeatedly — a string can carry several. */
function trimTail(s: string): string {
  let out = s.trim();
  for (let pass = 0; pass < 4; pass++) {
    const before = out;
    for (const re of TAIL) out = out.replace(re, '').trim();
    if (out === before) break;
  }
  return out.replace(/[\s,]+$/, '').trim();
}

/**
 * Pull the device name out of whatever wrapper the platform used.
 *
 * The formats, in the order they are tried:
 *
 *   ANGLE (NVIDIA, NVIDIA GeForce RTX 3060 Direct3D11 vs_5_0 ps_5_0, D3D11)
 *   ANGLE (Intel, Intel(R) UHD Graphics 770 Direct3D11 vs_5_0 ps_5_0, D3D11)
 *   ANGLE (Apple, ANGLE Metal Renderer: Apple M2 Pro, Unspecified Version)
 *   ANGLE (AMD, AMD Radeon RX 6600 (radeonsi, navi23, LLVM 15) , OpenGL 4.6)
 *   ANGLE (NVIDIA GeForce GTX 1060 Direct3D11 vs_5_0 ps_5_0)      [older, no vendor field]
 *   NVIDIA GeForce RTX 3060/PCIe/SSE2                              [plain OpenGL]
 *   AMD Radeon RX 6600 (radeonsi, navi23, LLVM 15.0.7, DRM 3.49)   [Mesa]
 *   Apple M1 Pro                                                    [macOS, no wrapper]
 */
export function parseRenderer(raw: string): RendererIdentity {
  const text = (raw ?? '').trim();
  const vendor = vendorOf(text);
  const backend = backendOf(text);
  const software = SOFTWARE.some((m) => text.toLowerCase().includes(m));

  if (!text) return { device: '', vendor: 'unknown', backend: 'unknown', software: false, confidence: 'raw' };
  if (software) return { device: text, vendor, backend, software: true, confidence: 'parsed' };

  const angle = /^ANGLE\s*\((.*)\)\s*$/is.exec(text);
  if (angle) {
    let inner = angle[1];

    // Metal names the renderer explicitly and puts the device after the colon.
    const metal = /ANGLE\s+Metal\s+Renderer:\s*([^,]+)/i.exec(inner);
    if (metal) {
      return { device: trimTail(metal[1]), vendor, backend: 'metal', software: false, confidence: 'parsed' };
    }

    // Newer strings lead with a vendor field: "NVIDIA, NVIDIA GeForce ...".
    // Split only on the FIRST comma, and only when what precedes it is a bare
    // vendor word — a Mesa string's commas are inside its own parentheses.
    const firstComma = inner.indexOf(',');
    if (firstComma > 0) {
      const head = inner.slice(0, firstComma).trim();
      if (/^[A-Za-z][\w .()-]{0,24}$/.test(head) && !/\d{3}/.test(head)) inner = inner.slice(firstComma + 1).trim();
    }
    const device = trimTail(inner);
    return { device: device || text, vendor, backend, software: false, confidence: device ? 'parsed' : 'raw' };
  }

  const bare = trimTail(text);
  return {
    device: bare || text,
    vendor,
    backend,
    software: false,
    // A string that shed nothing was already a plain device name.
    confidence: bare === text ? 'bare' : 'parsed',
  };
}

/**
 * Tidy a device name for the catalogue detector.
 *
 * Intel writes "Intel(R) UHD Graphics 770" and the catalogue writes "Intel UHD
 * Graphics 770"; the registered-trademark marks are noise everywhere they
 * appear. Nothing else is touched — normalising further would start guessing at
 * the part, which is the detector's job and not this module's.
 */
export function cleanDeviceName(device: string): string {
  return device
    .replace(/\((?:R|TM|C)\)/gi, '')
    .replace(/[®™]/g, '')
    .replace(/\s{2,}/g, ' ')
    .trim();
}
