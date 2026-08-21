/**
 * Asking the browser what machine it is running on.
 *
 * The gathering half of `core/browserspec.ts`. Everything here touches WebGL,
 * `navigator`, the screen or the clock, so none of it is unit-testable; the
 * decisions it feeds are in browserspec.ts and renderer.ts, which are.
 *
 * The point of it: the specification form asks for a processor, a graphics
 * card, memory, storage and a display, and the browser can answer the graphics
 * card outright, measure the display, and narrow the processor. Every field it
 * fills is one nobody has to find in a list of hundreds — and the graphics card
 * is the field most worth filling, because it is the one a person is most
 * likely to get subtly wrong (the 3GB 1060 rather than the 6GB, the 8GB 3060
 * rather than the 12GB).
 *
 * Nothing here fills a field it is not entitled to fill. The two APIs that look
 * more precise than they are — `deviceMemory` and `hardwareConcurrency` — are
 * handled in browserspec.ts, and this module records what they said without
 * promoting it.
 */

import type { EngineData } from '../../core/engine.ts';
import type { Resolution } from '../../core/types.ts';
import { detectHardware } from '../../core/detect.ts';
import { cleanDeviceName, parseRenderer, type RendererIdentity } from '../../core/renderer.ts';
import {
  DEVICE_MEMORY_CAP_GB,
  resolutionFor,
  standardRefresh,
  type BrowserInspection,
  type SpecField,
} from '../../core/browserspec.ts';

/** What the inspection concluded about the graphics card, ready to apply. */
export interface GpuIdentification {
  identity: RendererIdentity;
  /** Catalogue id, when the detector matched one confidently. */
  gpuId: string | null;
  /** Catalogue full name for that id. */
  gpuName: string | null;
  /** Runner-up ids, when the name did not narrow to one part. */
  alternatives: { id: string; name: string }[];
  /** Anything the detector wanted flagged — the 1060 3GB/6GB case lands here. */
  warnings: string[];
}

/* ------------------------------------------------------------------- gpu -- */

/**
 * Read the renderer string without running a benchmark.
 *
 * A throwaway 1x1 context: creating one is cheap, and it is discarded
 * immediately because browsers cap the number of live WebGL contexts and
 * silently kill the oldest when the cap is hit — which would take out the
 * benchmark's context if this one were left holding a slot.
 */
export function readRenderer(): { renderer: string; vendor: string; masked: boolean } | null {
  let canvas: HTMLCanvasElement | null = null;
  try {
    canvas = document.createElement('canvas');
    canvas.width = 1;
    canvas.height = 1;
    const gl =
      (canvas.getContext('webgl2') as WebGL2RenderingContext | null) ??
      (canvas.getContext('webgl') as WebGLRenderingContext | null);
    if (!gl) return null;
    const dbg = gl.getExtension('WEBGL_debug_renderer_info');
    const renderer = dbg
      ? String(gl.getParameter(dbg.UNMASKED_RENDERER_WEBGL) ?? '')
      : String(gl.getParameter(gl.RENDERER) ?? '');
    const vendor = dbg
      ? String(gl.getParameter(dbg.UNMASKED_VENDOR_WEBGL) ?? '')
      : String(gl.getParameter(gl.VENDOR) ?? '');
    gl.getExtension('WEBGL_lose_context')?.loseContext();
    return { renderer, vendor, masked: !dbg };
  } catch {
    return null;
  } finally {
    canvas?.remove();
  }
}

/**
 * Turn a renderer string into a catalogue part.
 *
 * The detector is handed a `GPU:` line rather than the bare name because that
 * is the shape it already parses, and it is the same code path a pasted dxdiag
 * dump takes — so a renderer string and a pasted spec sheet are matched by one
 * matcher with one set of tie-breaks, not two that can disagree.
 */
export function identifyGpu(renderer: string, data: EngineData): GpuIdentification {
  const identity = parseRenderer(renderer);
  const empty: GpuIdentification = {
    identity,
    gpuId: null,
    gpuName: null,
    alternatives: [],
    warnings: [],
  };
  if (!identity.device || identity.software) return empty;

  const name = cleanDeviceName(identity.device);
  const det = detectHardware(`GPU: ${name}`, data);
  const best = det.gpu[0];
  if (!best) return { ...empty, warnings: det.warnings };

  return {
    identity,
    gpuId: best.record.id,
    gpuName: best.record.fullName,
    alternatives: det.gpu.slice(1, 4).map((c) => ({ id: c.record.id, name: c.record.fullName })),
    warnings: det.warnings,
  };
}

/* --------------------------------------------------------------- display -- */

/**
 * Count animation frames to find the panel's refresh rate.
 *
 * `screen.refreshRate` does not exist; nothing in the platform reports the
 * rate, so it has to be measured. Frames are counted over a short window and
 * the median inter-frame gap is used rather than the mean: a dropped frame
 * doubles one gap and drags a mean down by several Hz, while the median is
 * unmoved by a handful of them.
 *
 * The measurement is skipped entirely when the tab is hidden — a background tab
 * is throttled to roughly 1Hz on purpose, and reporting that as the display
 * would be nonsense.
 */
export function measureRefresh(windowMs = 320): Promise<number> {
  return new Promise((resolve) => {
    if (typeof requestAnimationFrame !== 'function' || document.visibilityState === 'hidden') {
      resolve(0);
      return;
    }
    const gaps: number[] = [];
    let last = 0;
    const t0 = performance.now();
    const step = (t: number) => {
      if (last) gaps.push(t - last);
      last = t;
      if (t - t0 < windowMs && gaps.length < 240) {
        requestAnimationFrame(step);
        return;
      }
      if (gaps.length < 4) {
        resolve(0);
        return;
      }
      const sorted = gaps.slice().sort((a, b) => a - b);
      const mid = sorted[Math.floor(sorted.length / 2)];
      resolve(mid > 0 ? 1000 / mid : 0);
    };
    requestAnimationFrame(step);
  });
}

/**
 * The display's real pixel dimensions.
 *
 * `screen.width` is in CSS pixels, so a 4K panel at 150% scaling reports 2560
 * and would be filed as 1440p. Multiplying by `devicePixelRatio` recovers the
 * physical size — which is what the estimator needs, because the GPU renders
 * physical pixels regardless of what Windows scales them to afterwards.
 *
 * Browser zoom also moves `devicePixelRatio`, so this is right at 100% zoom and
 * drifts away from it as somebody zooms. That is why the value is offered for
 * confirmation rather than applied silently.
 */
export function readDisplay(): { width: number; height: number } | null {
  try {
    const dpr = window.devicePixelRatio || 1;
    const w = Math.round((window.screen?.width ?? 0) * dpr);
    const h = Math.round((window.screen?.height ?? 0) * dpr);
    return w > 0 && h > 0 ? { width: w, height: h } : null;
  } catch {
    return null;
  }
}

/* -------------------------------------------------------------- platform -- */

interface UADataLike {
  platform?: string;
  brands?: { brand: string; version: string }[];
  getHighEntropyValues?: (hints: string[]) => Promise<Record<string, unknown>>;
}

/**
 * Operating system and architecture.
 *
 * `userAgentData` is the modern surface and is Chromium-only; the low-entropy
 * fields are free, and the high-entropy ones (architecture, bitness, the real
 * platform version) need an async request that the browser may refuse. Both are
 * treated as optional, and the user-agent string is the fallback.
 *
 * Nothing here is used to fill a form field. It exists so the report can say
 * "this was read on Windows" — which matters, because half the remedies in the
 * findings are Windows-specific and offering them to somebody on a Mac is
 * worse than offering nothing.
 */
export async function readPlatform(): Promise<{ label: string; exact: boolean }> {
  const ua = (navigator as Navigator & { userAgentData?: UADataLike }).userAgentData;
  if (ua) {
    let arch = '';
    let bits = '';
    try {
      const hi = await ua.getHighEntropyValues?.(['architecture', 'bitness', 'platformVersion']);
      arch = String(hi?.architecture ?? '');
      bits = String(hi?.bitness ?? '');
    } catch {
      // Refused, which is a legitimate answer. The low-entropy platform stands.
    }
    const label = [ua.platform, arch && bits ? `${arch} ${bits}-bit` : arch].filter(Boolean).join(' ');
    if (label) return { label, exact: true };
  }
  const s = navigator.userAgent;
  const m = /\((?:([^;)]+))/.exec(s);
  return { label: m?.[1]?.trim() || 'unknown', exact: false };
}

/* -------------------------------------------------------------- assemble -- */

/**
 * Everything the browser will say, gathered once.
 *
 * Deliberately cheap: no benchmark runs here. This is what the page can know
 * within a few hundred milliseconds of being asked, so it can be offered as
 * "read this from the browser" next to the form rather than behind a minute of
 * fan noise.
 */
export async function inspectBrowser(): Promise<BrowserInspection> {
  const notes: string[] = [];

  const gl = readRenderer();
  const renderer = gl?.renderer ?? '';
  if (!gl) notes.push('No WebGL context could be created, so the graphics card could not be identified.');
  else if (gl.masked) {
    notes.push(
      'This browser withheld the unmasked renderer name — a fingerprinting protection, not a fault. ' +
        'The graphics card has to be picked by hand.',
    );
  } else if (!renderer) {
    notes.push('The browser returned an empty renderer name.');
  }

  const gpuName: SpecField<string> | null = renderer
    ? {
        value: renderer,
        confidence: 'reported',
        note: 'The graphics driver told the browser this is the adapter rendering the page. On a laptop this is the adapter in use, which is not always the one on the spec sheet.',
      }
    : null;

  const hc = navigator.hardwareConcurrency;
  const threads: SpecField<number> | null =
    typeof hc === 'number' && hc > 0
      ? {
          value: hc,
          confidence: 'reported',
          note: 'Logical processors as the browser sees them. Some privacy modes clamp this, and inside a container it counts the container rather than the machine — good evidence, not proof.',
        }
      : null;
  if (!threads) notes.push('The browser did not report a processor thread count.');

  const dm = (navigator as Navigator & { deviceMemory?: number }).deviceMemory;
  const memoryFloorGB: SpecField<number> | null =
    typeof dm === 'number' && dm > 0
      ? {
          value: dm,
          confidence: dm >= DEVICE_MEMORY_CAP_GB ? 'bound' : 'reported',
          note:
            dm >= DEVICE_MEMORY_CAP_GB
              ? `The browser reports ${dm}GB, but this figure stops counting at ${DEVICE_MEMORY_CAP_GB}GB — 8, 16, 32 and 64 all read the same. It is a floor: at least ${dm}GB is fitted.`
              : `The browser reports ${dm}GB, rounded down to a power of two. Below the ${DEVICE_MEMORY_CAP_GB}GB cap this is the real figure, rounded.`,
        }
      : null;

  const plat = await readPlatform();
  const platform: SpecField<string> = {
    value: plat.label,
    confidence: plat.exact ? 'reported' : 'derived',
    note: plat.exact
      ? 'Reported by the browser directly.'
      : 'Read out of the user-agent string, which browsers deliberately blur. Close enough to choose the right advice, not a version number to rely on.',
  };

  const px = readDisplay();
  const hz = await measureRefresh();
  const display: SpecField<{ width: number; height: number; refreshHz: number }> | null = px
    ? {
        value: { width: px.width, height: px.height, refreshHz: standardRefresh(hz) },
        confidence: 'measured',
        note:
          `${px.width}x${px.height} physical pixels` +
          (hz > 0
            ? `, and frames arrived at ${hz.toFixed(1)}Hz${standardRefresh(hz) !== Math.round(hz) ? ` — a ${standardRefresh(hz)}Hz panel` : ''}.` +
              ' A browser cannot exceed the panel, so a rate measured this way is the panel unless something else was already holding the GPU.'
            : '. The refresh rate could not be measured.'),
      }
    : null;
  if (!px) notes.push('The screen dimensions were not readable.');
  if (px && (window.devicePixelRatio || 1) !== 1) {
    notes.push(
      `Display scaling is ${Math.round((window.devicePixelRatio || 1) * 100)}%, which has been multiplied back out. ` +
        'Browser zoom moves the same number, so check the resolution below is the panel you actually have.',
    );
  }

  return {
    renderer,
    gpuName,
    threads,
    memoryFloorGB,
    platform,
    display,
    webgpu: typeof (navigator as Navigator & { gpu?: unknown }).gpu !== 'undefined',
    notes,
  };
}

/**
 * What the inspection is prepared to put in the form.
 *
 * Separate from the inspection itself so the panel can show everything that was
 * read while applying only the part of it that is safe to apply. The memory
 * floor is the clearest case: worth showing, never worth writing into a field
 * labelled "memory installed".
 */
export interface SpecPrefill {
  gpuId?: string;
  resolution?: Resolution;
  refreshHz?: number;
}

export function prefillFrom(i: BrowserInspection, gpu: GpuIdentification | null): SpecPrefill {
  const out: SpecPrefill = {};
  // Only when the name narrowed to exactly one part. An ambiguous name — the
  // 1060 that could be the 3GB or the 6GB — is shown with its alternatives and
  // left for the user, because filling it with the wrong one silently is worse
  // than not filling it at all.
  if (gpu?.gpuId && !gpu.alternatives.length && !gpu.identity.software) out.gpuId = gpu.gpuId;
  if (i.display) {
    const r = resolutionFor(i.display.value.width);
    if (r) out.resolution = r;
    if (i.display.value.refreshHz > 0) out.refreshHz = i.display.value.refreshHz;
  }
  return out;
}
