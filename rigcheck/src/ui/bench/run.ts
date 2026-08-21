/**
 * The measurement side of the in-browser benchmark.
 *
 * Everything here touches the GPU, workers or the clock, so none of it is
 * unit-testable; the decisions it feeds are in core/browserbench.ts, which is.
 * What lives here is the part that has to be right about the platform rather
 * than about hardware.
 *
 * Four platform problems drive the whole design:
 *
 * **WebGL is asynchronous.** Issuing draw calls and timing the loop measures
 * how fast commands are queued, not how fast they run — a mistake that makes
 * every GPU look identical and infinitely fast. Each timed pass therefore ends
 * with a one-pixel `readPixels`, which blocks until the GPU has actually
 * finished the work.
 *
 * **requestAnimationFrame is capped by the display.** Anything paced to frames
 * measures the monitor, not the card. Nothing here uses rAF for timing; work
 * is submitted and fenced directly, so a 60Hz panel does not cap the result.
 *
 * **Background tabs are throttled on purpose.** Timers slow and frames stop
 * when a tab is hidden, so a run measured in the background reads low for a
 * reason that has nothing to do with the machine. Visibility is watched
 * throughout and the whole run is flagged if it is ever lost.
 *
 * **A shader measures one thing.** The first version of this ran a single
 * arithmetic loop and called the result "the GPU". Three workloads run now —
 * arithmetic, memory and the raster back end — because the interesting reading
 * is the shape across them, and because a card whose memory clock is stuck
 * looks perfectly healthy to an arithmetic test.
 */

import type {
  AdapterInfo, BenchResult, BenchWindow, CpuProbe, GpuProbe, GpuWorkload, LatencyPoint,
  MemoryProbe, ScalingPoint,
} from '../../core/browserbench.ts';

export interface Progress {
  phase:
    | 'inspect' | 'gpu-calibrate' | 'gpu-sustained' | 'gpu-bandwidth' | 'gpu-fill'
    | 'cpu-scaling' | 'cpu-sustained' | 'cpu-latency' | 'memory' | 'done';
  /** 0..1 within the whole run. */
  fraction: number;
  label: string;
}

/**
 * How long the run should take, and therefore how much it can see.
 *
 * The three exist because the useful length depends on the question. Catching
 * software rendering or the wrong adapter needs seconds; catching a cooling
 * problem needs minutes, because a part that throttles does so once it is warm
 * and a thirty-second run finishes before that happens. Offering only the long
 * version means people stop it early and get nothing.
 */
export type Depth = 'quick' | 'standard' | 'thorough';

export interface BenchOptions {
  depth: Depth;
  onProgress?: (p: Progress) => void;
  /** Cooperative cancellation. */
  signal?: AbortSignal;
}

interface Plan {
  /** Seconds of sustained arithmetic load on the GPU. */
  gpuSustainedS: number;
  /** Seconds for each of the memory and fill-rate workloads. */
  gpuShortS: number;
  /** Seconds at each width of the thread-scaling ladder. */
  cpuLevelS: number;
  /** Seconds of sustained all-threads load, which is where decline shows. */
  cpuSustainedS: number;
  /** How many times the single-thread figure is repeated, for the noise floor. */
  repeats: number;
  /** Largest working set in the latency sweep, in bytes. */
  latencyMaxBytes: number;
}

const PLANS: Record<Depth, Plan> = {
  quick: { gpuSustainedS: 8, gpuShortS: 2.5, cpuLevelS: 1.6, cpuSustainedS: 6, repeats: 2, latencyMaxBytes: 16 << 20 },
  standard: { gpuSustainedS: 30, gpuShortS: 4, cpuLevelS: 2.2, cpuSustainedS: 18, repeats: 3, latencyMaxBytes: 64 << 20 },
  thorough: { gpuSustainedS: 110, gpuShortS: 6, cpuLevelS: 3, cpuSustainedS: 60, repeats: 4, latencyMaxBytes: 128 << 20 },
};

export const DEFAULT_DEPTH: Depth = 'standard';

/**
 * Roughly how long a depth takes, in seconds. Shown before the button is pressed.
 *
 * The ladder contributes only its MIDDLE widths: one thread is covered by the
 * repeats and the full width is covered by the sustained phase, so counting all
 * of them double-charges both ends. The two constants are calibration slack for
 * auto-ranging, warm-up, worker startup and the latency sweep, checked against
 * a measured run rather than guessed — a quick run estimated at 34s took 27.
 */
export function estimateSeconds(depth: Depth, threads = navigator.hardwareConcurrency || 4): number {
  const p = PLANS[depth];
  const middle = Math.max(0, ladder(threads).length - 2);
  return Math.round(
    p.gpuSustainedS + 2 * p.gpuShortS + 3 +
      p.cpuLevelS * (middle + p.repeats) + p.cpuSustainedS + 2,
  );
}

const sleep = (ms: number) => new Promise<void>((r) => setTimeout(r, ms));

/**
 * Hand the page back so it can paint, and get control back as soon as it has.
 *
 * `setTimeout(0)` is not zero: browsers clamp nested timeouts to about four
 * milliseconds, and a pass loop that yields that way spends a fifth of a
 * twenty-second run doing nothing. A `MessageChannel` message is a task like
 * any other — the browser can paint between tasks — but carries no clamp, so
 * the same run collects meaningfully more samples, which is what the percentile
 * read is made of.
 */
const yieldToPage: () => Promise<void> =
  typeof MessageChannel === 'function'
    ? () =>
        new Promise<void>((resolve) => {
          const ch = new MessageChannel();
          ch.port1.onmessage = () => {
            ch.port1.close();
            resolve();
          };
          ch.port2.postMessage(0);
        })
    : () => sleep(0);

/* ------------------------------------------------------------------- GPU -- */

const VERT = `#version 300 es
in vec2 aPos;
void main() { gl_Position = vec4(aPos, 0.0, 1.0); }`;

/**
 * Arithmetic. A genuinely serial inner loop.
 *
 * The loop must not be reducible to a closed form or the driver's optimiser
 * will delete it and report an absurd score. Each iteration therefore feeds
 * the previous one through a transcendental, and the result is written to the
 * output so nothing is dead code.
 */
const FRAG_ALU = `#version 300 es
precision highp float;
uniform int uIter;
uniform float uSeed;
out vec4 outColor;
void main() {
  vec2 p = (gl_FragCoord.xy + uSeed) * 0.001;
  float acc = 0.0;
  vec2 z = p;
  for (int i = 0; i < uIter; i++) {
    // Serial: z depends on the previous z, so this cannot be unrolled away.
    z = vec2(z.x * z.x - z.y * z.y, 2.0 * z.x * z.y) + p;
    acc += sin(z.x) * cos(z.y) + sqrt(abs(z.x) + 1.0);
    z = clamp(z, -2.0, 2.0);
  }
  outColor = vec4(fract(acc * 0.001), fract(acc * 0.01), fract(acc), 1.0);
}`;

/**
 * Memory. Scattered reads from a texture far larger than any GPU cache.
 *
 * The tap coordinates come from a multiplicative hash of the previous one, so
 * consecutive taps land anywhere in a 16MB texture rather than in the same
 * neighbourhood. That is the whole point: a texture read from cache measures
 * the cache, and every GPU has enough of it to serve a tidy access pattern
 * indefinitely.
 *
 * `uint` rather than `int` because the hash relies on wrapping, which GLSL ES
 * defines for unsigned arithmetic and leaves open for signed.
 */
const FRAG_BANDWIDTH = `#version 300 es
precision highp float;
precision highp int;
uniform highp sampler2D uTex;
uniform int uTaps;
uniform float uSeed;
out vec4 outColor;
void main() {
  ivec2 sz = textureSize(uTex, 0);
  uint idx = uint(gl_FragCoord.x) + uint(gl_FragCoord.y) * 7919u + uint(uSeed);
  vec4 acc = vec4(0.0);
  for (int i = 0; i < uTaps; i++) {
    idx = idx * 1664525u + 1013904223u;
    ivec2 c = ivec2(int(idx % uint(sz.x)), int((idx / uint(sz.x)) % uint(sz.y)));
    acc += texelFetch(uTex, c, 0);
  }
  outColor = acc * (1.0 / float(uTaps));
}`;

/**
 * Raster back end. Trivial shading, blending on, drawn as instances.
 *
 * Blending forces a read-modify-write of the framebuffer for every fragment,
 * which is what stops the driver from collapsing overlapping full-screen passes
 * into one. What this measures is how fast the card can actually put pixels
 * down — a different bottleneck from either of the others, and the one that
 * moves most with render resolution.
 *
 * The overdraw comes from ONE instanced draw rather than from a loop issuing
 * thousands of draw calls. Each `drawArrays` costs the driver a few
 * microseconds of CPU work, and at the four thousand layers a fast card needs
 * to fill a 22ms budget that is over ten milliseconds of submission sitting
 * inside the timed region, measuring the driver instead of the card.
 *
 * The instance id reaches the fragment shader so no two layers are identical —
 * a driver is entitled to notice it is drawing the same thing repeatedly, and
 * this removes the question.
 */
const VERT_FILL = `#version 300 es
in vec2 aPos;
flat out int vInstance;
void main() {
  vInstance = gl_InstanceID;
  gl_Position = vec4(aPos, 0.0, 1.0);
}`;

const FRAG_FILL = `#version 300 es
precision mediump float;
flat in int vInstance;
uniform float uSeed;
out vec4 outColor;
void main() {
  float k = fract((uSeed + float(vInstance)) * 0.013);
  outColor = vec4(k, gl_FragCoord.x * 0.0005, gl_FragCoord.y * 0.0005, 0.04);
}`;

const ALU_SIDE = 512;
const FILL_SIDE = 1024;
const TEX_SIDE = 2048; // 2048^2 RGBA8 = 16MB, past any GPU's last-level cache.

function compile(gl: WebGL2RenderingContext, type: number, src: string): WebGLShader {
  const sh = gl.createShader(type)!;
  gl.shaderSource(sh, src);
  gl.compileShader(sh);
  if (!gl.getShaderParameter(sh, gl.COMPILE_STATUS)) {
    throw new Error(`shader: ${gl.getShaderInfoLog(sh)}`);
  }
  return sh;
}

function link(gl: WebGL2RenderingContext, frag: string, vert = VERT): WebGLProgram {
  const prog = gl.createProgram()!;
  gl.attachShader(prog, compile(gl, gl.VERTEX_SHADER, vert));
  gl.attachShader(prog, compile(gl, gl.FRAGMENT_SHADER, frag));
  gl.linkProgram(prog);
  if (!gl.getProgramParameter(prog, gl.LINK_STATUS)) throw new Error(`link: ${gl.getProgramInfoLog(prog)}`);
  return prog;
}

class GpuHarness {
  private gl: WebGL2RenderingContext;
  private progs: Record<'alu' | 'bandwidth' | 'fill', WebGLProgram>;
  private px = new Uint8Array(4);
  private seed = 0;
  private tex: WebGLTexture | null = null;
  private canvas: HTMLCanvasElement;
  // Looked up once. `getUniformLocation` is a driver call with a string lookup
  // behind it, and calling it three times per pass on a run of a thousand
  // passes is thousands of avoidable round trips between the passes being
  // timed.
  private uniforms!: Record<'alu' | 'bandwidth' | 'fill', Record<string, WebGLUniformLocation | null>>;

  constructor(canvas: HTMLCanvasElement) {
    const gl = canvas.getContext('webgl2', {
      antialias: false,
      depth: false,
      stencil: false,
      powerPreference: 'high-performance',
      preserveDrawingBuffer: false,
    });
    if (!gl) throw new Error('WebGL2 is not available in this browser.');
    this.gl = gl;
    this.canvas = canvas;
    canvas.width = ALU_SIDE;
    canvas.height = ALU_SIDE;

    this.progs = {
      alu: link(gl, FRAG_ALU),
      bandwidth: link(gl, FRAG_BANDWIDTH),
      fill: link(gl, FRAG_FILL, VERT_FILL),
    };

    // One full-screen triangle, shared by all three programs. The attribute
    // sits at the same location in each because each is linked from the same
    // vertex shader, so the buffer is bound once and never rebound.
    const buf = gl.createBuffer();
    gl.bindBuffer(gl.ARRAY_BUFFER, buf);
    gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([-1, -1, 3, -1, -1, 3]), gl.STATIC_DRAW);
    for (const prog of Object.values(this.progs)) {
      gl.useProgram(prog);
      const loc = gl.getAttribLocation(prog, 'aPos');
      if (loc >= 0) {
        gl.enableVertexAttribArray(loc);
        gl.vertexAttribPointer(loc, 2, gl.FLOAT, false, 0, 0);
      }
    }
    this.uniforms = {
      alu: { uSeed: gl.getUniformLocation(this.progs.alu, 'uSeed'), uIter: gl.getUniformLocation(this.progs.alu, 'uIter') },
      bandwidth: {
        uSeed: gl.getUniformLocation(this.progs.bandwidth, 'uSeed'),
        uTaps: gl.getUniformLocation(this.progs.bandwidth, 'uTaps'),
        uTex: gl.getUniformLocation(this.progs.bandwidth, 'uTex'),
      },
      fill: { uSeed: gl.getUniformLocation(this.progs.fill, 'uSeed') },
    };
  }

  identity(): { renderer: string; vendor: string; maxTextureSize: number } {
    const gl = this.gl;
    const dbg = gl.getExtension('WEBGL_debug_renderer_info');
    // The unmasked strings are the whole point: the masked ones say "WebKit"
    // and identify nothing. Some browsers withhold them for fingerprinting
    // reasons, in which case the masked value is used and reported as such.
    const renderer = dbg
      ? String(gl.getParameter(dbg.UNMASKED_RENDERER_WEBGL) ?? '')
      : String(gl.getParameter(gl.RENDERER) ?? '');
    const vendor = dbg
      ? String(gl.getParameter(dbg.UNMASKED_VENDOR_WEBGL) ?? '')
      : String(gl.getParameter(gl.VENDOR) ?? '');
    return { renderer, vendor, maxTextureSize: gl.getParameter(gl.MAX_TEXTURE_SIZE) as number };
  }

  /**
   * Upload the texture the bandwidth pass reads.
   *
   * Filled with noise rather than a gradient: a driver is entitled to store a
   * constant or trivially compressible texture in a form that never touches
   * memory, and several do. Noise cannot be compressed, so every tap is a real
   * read.
   */
  private ensureTexture(): boolean {
    const gl = this.gl;
    if (this.tex) return true;
    const max = gl.getParameter(gl.MAX_TEXTURE_SIZE) as number;
    const side = Math.min(TEX_SIDE, max);
    if (side < 512) return false;
    const data = new Uint8Array(side * side * 4);
    // Written a word at a time. Byte by byte this is sixteen million iterations
    // on the main thread with nothing painting, which is a visible freeze on a
    // slow machine for no benefit — one xorshift produces four bytes of noise
    // just as well as four do.
    const words = new Uint32Array(data.buffer);
    let s = 0x9e3779b9;
    for (let i = 0; i < words.length; i++) {
      s ^= s << 13; s >>>= 0; s ^= s >>> 17; s ^= s << 5; s >>>= 0;
      words[i] = s;
    }
    const tex = gl.createTexture();
    gl.bindTexture(gl.TEXTURE_2D, tex);
    gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA8, side, side, 0, gl.RGBA, gl.UNSIGNED_BYTE, data);
    // NEAREST with no mipmaps: filtering would average neighbours and hand the
    // texture cache exactly the locality this pass exists to avoid.
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.NEAREST);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.NEAREST);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);
    this.tex = tex;
    this.texSide = side;
    return true;
  }

  private texSide = TEX_SIDE;

  /**
   * One fenced pass of one workload. Returns milliseconds of real GPU time.
   *
   * `amount` means iterations for the arithmetic pass, taps for the memory
   * pass, and overdrawn layers for the fill pass.
   */
  pass(kind: 'alu' | 'bandwidth' | 'fill', amount: number): number {
    const gl = this.gl;
    const prog = this.progs[kind];
    const side = kind === 'fill' ? FILL_SIDE : ALU_SIDE;
    if (this.canvas.width !== side) {
      this.canvas.width = side;
      this.canvas.height = side;
    }
    gl.viewport(0, 0, side, side);
    gl.useProgram(prog);
    const u = this.uniforms[kind];
    const seed = (this.seed = (this.seed + 1) % 997);
    gl.uniform1f(u.uSeed, seed);

    if (kind === 'alu') {
      gl.disable(gl.BLEND);
      gl.uniform1i(u.uIter, amount);
    } else if (kind === 'bandwidth') {
      gl.disable(gl.BLEND);
      gl.activeTexture(gl.TEXTURE0);
      gl.bindTexture(gl.TEXTURE_2D, this.tex);
      gl.uniform1i(u.uTex, 0);
      gl.uniform1i(u.uTaps, amount);
    } else {
      gl.enable(gl.BLEND);
      gl.blendFunc(gl.SRC_ALPHA, gl.ONE_MINUS_SRC_ALPHA);
    }

    const t0 = performance.now();
    // The fill pass gets its work from instances rather than from a loop inside
    // the shader — overdraw is the thing being measured, and a shader loop would
    // measure arithmetic again.
    if (kind === 'fill') gl.drawArraysInstanced(gl.TRIANGLES, 0, 3, amount);
    else gl.drawArrays(gl.TRIANGLES, 0, 3);
    // Blocks until the draws have actually completed. Without this the timing
    // measures command submission and every GPU looks the same.
    gl.readPixels(0, 0, 1, 1, gl.RGBA, gl.UNSIGNED_BYTE, this.px);
    return performance.now() - t0;
  }

  /** Work completed by one pass at this size, in the workload's own units. */
  workPerPass(kind: 'alu' | 'bandwidth' | 'fill', amount: number): number {
    if (kind === 'alu') return ALU_SIDE * ALU_SIDE * amount;
    // Four bytes per texel, RGBA8.
    if (kind === 'bandwidth') return ALU_SIDE * ALU_SIDE * amount * 4;
    return FILL_SIDE * FILL_SIDE * amount;
  }

  hasTexture(): boolean {
    return this.ensureTexture();
  }

  /** The bandwidth texture's real side length, once clamped to the device limit. */
  textureSide(): number {
    return this.texSide;
  }

  dispose() {
    for (const p of Object.values(this.progs)) this.gl.deleteProgram(p);
    if (this.tex) this.gl.deleteTexture(this.tex);
    this.gl.getExtension('WEBGL_lose_context')?.loseContext();
  }
}

/**
 * Find an amount that makes one pass take roughly 22ms.
 *
 * Fixing the amount instead would either finish instantly on a fast card or
 * hang a slow one for seconds at a time, and a browser that stops painting for
 * seconds looks broken. Auto-ranging keeps every machine on the same
 * wall-clock budget per pass, so the comparison is throughput.
 *
 * The returned amount is also the unit of work the percentile read is taken
 * over, which is why it matters that every pass be the same size: a pass that
 * grew mid-run would show up as a tail that is not there.
 */
async function autoRange(
  run: (amount: number) => number,
  start: number,
  max: number,
  targetMs = 22,
): Promise<number> {
  // Three passes, take the middle one, and yield between them exactly as the
  // sustained phase will.
  //
  // Both parts matter. A single sample decides the size of every pass in the
  // run, and pass times are a distribution rather than a number — a headless
  // run whose passes sat at 14.5ms typically and 20ms at the 99th percentile
  // accepted the first size it tried because that one sample landed near the
  // top. And back-to-back passes with no yield do not behave like passes with
  // one, so calibrating without the yield sizes the workload for conditions
  // the run never experiences.
  const measure = async (amount: number) => {
    const xs: number[] = [];
    for (let i = 0; i < 3; i++) {
      xs.push(run(amount));
      await yieldToPage();
    }
    return xs.sort((a, b) => a - b)[1];
  };
  let amount = start;
  for (let i = 0; i < 24; i++) {
    const ms = await measure(amount);
    if (ms > targetMs * 0.85 && ms < targetMs * 2.5) return amount;
    if (ms >= targetMs * 2.5) return Math.max(1, Math.floor(amount * (targetMs / ms)));
    const scale = Math.min(6, Math.max(1.5, targetMs / Math.max(ms, 0.15)));
    amount = Math.min(max, Math.floor(amount * scale));
    if (amount >= max) return max;
  }
  return amount;
}

/**
 * Hold one workload for a fixed time and record every pass.
 *
 * Both the reduced windows and the raw pass timings come back: the windows show
 * the trend, and the raw list is the only thing that can show the tail, because
 * a reduction has by definition thrown the outliers away.
 *
 * The time recorded includes the fence — the one-pixel `readPixels` that waits
 * for the GPU to finish — so every throughput figure here is slightly lower
 * than the card's true rate by whatever that round trip costs. It is not
 * subtracted out, because the cost is not measurable separately without
 * assuming something about the driver. It is the same cost on every pass and
 * on the same machine next month, so it cancels in every comparison this module
 * actually makes.
 */
async function sustain(
  h: GpuHarness,
  kind: 'alu' | 'bandwidth' | 'fill',
  amount: number,
  seconds: number,
  opts: BenchOptions,
  phase: Progress['phase'],
  from: number,
  to: number,
  label: string,
): Promise<{ passMs: number[]; windows: BenchWindow[]; peak: number }> {
  const work = h.workPerPass(kind, amount);
  const passMs: number[] = [];
  const windows: BenchWindow[] = [];
  const totalMs = seconds * 1000;
  const t0 = performance.now();
  let peak = 0;

  while (performance.now() - t0 < totalMs) {
    if (opts.signal?.aborted) break;
    const at = performance.now() - t0;
    const ms = h.pass(kind, amount);
    if (!(ms > 0)) continue;
    passMs.push(ms);
    const thr = work / (ms / 1000);
    windows.push({ atMs: at, throughput: thr });
    if (thr > peak) peak = thr;
    opts.onProgress?.({
      phase,
      fraction: from + (to - from) * Math.min(1, at / totalMs),
      label: `${label} — ${Math.max(0, Math.round((totalMs - at) / 1000))}s left`,
    });
    // Yield so the page keeps painting. Without this the tab locks up and the
    // browser offers to kill it.
    await yieldToPage();
  }
  return { passMs, windows, peak };
}

/* ---------------------------------------------------------------- WebGPU -- */

interface GpuAdapterLike {
  info?: Record<string, unknown>;
  requestAdapterInfo?: () => Promise<Record<string, unknown>>;
  isFallbackAdapter?: boolean;
  limits?: Record<string, number>;
}

/**
 * What `navigator.gpu` will say about the adapter.
 *
 * Worth asking even though the benchmark itself runs on WebGL2: WebGPU reports
 * the vendor, the architecture and the device as three separate fields, where
 * WebGL hands over one string that has to be taken apart. When both are
 * available the WebGPU answer is the better identity.
 *
 * No compute shader runs here. Writing one that has never been executed against
 * a real adapter — this machine has none, and `requestAdapter()` returns null on
 * it — would mean shipping numbers nobody has ever seen be correct. Identity is
 * the part that can be taken honestly without hardware to check it against,
 * because it is reported rather than measured.
 */
async function readAdapter(notes: string[]): Promise<AdapterInfo | null> {
  const gpu = (navigator as Navigator & { gpu?: { requestAdapter: (o?: unknown) => Promise<GpuAdapterLike | null> } }).gpu;
  if (!gpu) return null;
  try {
    const adapter = await gpu.requestAdapter({ powerPreference: 'high-performance' });
    if (!adapter) {
      notes.push('This browser has WebGPU but could not provide an adapter, so its identity was not available.');
      return null;
    }
    // `info` is the current shape; `requestAdapterInfo()` is the older one and
    // is still what some shipping versions have. Either is fine.
    const info = adapter.info ?? (await adapter.requestAdapterInfo?.()) ?? {};
    const str = (k: string) => String(info[k] ?? '').trim();
    return {
      vendor: str('vendor'),
      architecture: str('architecture'),
      device: str('device'),
      description: str('description'),
      fallback: adapter.isFallbackAdapter === true,
      maxBufferSizeMB: adapter.limits?.maxBufferSize ? adapter.limits.maxBufferSize / 1024 / 1024 : undefined,
      maxComputeInvocations: adapter.limits?.maxComputeInvocationsPerWorkgroup,
    };
  } catch {
    // A browser that refuses is a legitimate answer, not a fault to report.
    return null;
  }
}

/* --------------------------------------------------------------- GPU run -- */

async function runGpu(opts: BenchOptions, plan: Plan, notes: string[]): Promise<GpuProbe | null> {
  const canvas = document.createElement('canvas');
  let h: GpuHarness;
  try {
    h = new GpuHarness(canvas);
  } catch (e) {
    notes.push(`WebGL2 test skipped: ${e instanceof Error ? e.message : String(e)}`);
    return null;
  }
  try {
    const id = h.identity();
    if (!id.renderer) notes.push('The browser withheld the GPU name, so the adapter could not be identified.');
    const adapter = await readAdapter(notes);

    const workloads: GpuWorkload[] = [];

    /* -- arithmetic, the long one ---------------------------------------- */
    opts.onProgress?.({ phase: 'gpu-calibrate', fraction: 0.02, label: 'sizing the graphics workload' });
    // Warm up BEFORE calibrating, not after. The first passes on a fresh
    // context pay for shader compilation and a GPU still at idle clocks, and
    // calibrating against those picks a workload sized for a machine slower
    // than the one being measured — a headless run chose 32 iterations from a
    // cold pass reading 20ms, then ran the whole sustained phase at 14.8ms per
    // pass, well under the budget it was supposed to fill.
    for (let i = 0; i < 6; i++) h.pass('alu', 64);
    const iter = await autoRange((n) => h.pass('alu', n), 32, 4_000_000);
    // And again at the chosen size, so the sustained phase starts from clocks
    // that have already settled rather than showing the ramp as a decline.
    for (let i = 0; i < 4; i++) h.pass('alu', iter);

    const alu = await sustain(h, 'alu', iter, plan.gpuSustainedS, opts, 'gpu-sustained', 0.04, 0.36,
      'holding graphics load');
    workloads.push({ kind: 'alu', peak: alu.peak, unit: 'shader operations/s', passMs: alu.passMs, workPerPass: h.workPerPass('alu', iter) });

    /* -- memory ----------------------------------------------------------- */
    if (!opts.signal?.aborted) {
      opts.onProgress?.({ phase: 'gpu-bandwidth', fraction: 0.36, label: 'preparing the memory workload' });
      if (h.hasTexture()) {
        if (h.textureSide() < TEX_SIDE) {
          notes.push(
            `The memory test used a ${h.textureSide()}px texture rather than ${TEX_SIDE}px, because that is ` +
              `this device's limit. A smaller texture is easier for the cache to hold, so the figure reads high.`,
          );
        }
        for (let i = 0; i < 3; i++) h.pass('bandwidth', 8);
        const taps = await autoRange((n) => h.pass('bandwidth', n), 8, 8192);
        for (let i = 0; i < 3; i++) h.pass('bandwidth', taps);
        const bw = await sustain(h, 'bandwidth', taps, plan.gpuShortS, opts, 'gpu-bandwidth', 0.36, 0.44,
          'reading scattered memory');
        workloads.push({ kind: 'bandwidth', peak: bw.peak, unit: 'bytes/s', passMs: bw.passMs, workPerPass: h.workPerPass('bandwidth', taps) });
      } else {
        notes.push('The memory test was skipped: this device could not hold a texture large enough for it to mean anything.');
      }
    }

    /* -- fill rate -------------------------------------------------------- */
    if (!opts.signal?.aborted) {
      opts.onProgress?.({ phase: 'gpu-fill', fraction: 0.44, label: 'sizing the fill-rate workload' });
      for (let i = 0; i < 3; i++) h.pass('fill', 8);
      const layers = await autoRange((n) => h.pass('fill', n), 4, 4096);
      for (let i = 0; i < 3; i++) h.pass('fill', layers);
      const fill = await sustain(h, 'fill', layers, plan.gpuShortS, opts, 'gpu-fill', 0.44, 0.5,
        'drawing overlapping pixels');
      workloads.push({ kind: 'fill', peak: fill.peak, unit: 'pixels/s', passMs: fill.passMs, workPerPass: h.workPerPass('fill', layers) });
    }

    return {
      renderer: id.renderer,
      vendor: id.vendor,
      api: 'webgl2',
      // Best rather than mean: the peak is the machine at its clocks, and the
      // decline away from it is reported separately as its own finding rather
      // than being averaged into a single number that hides both.
      throughput: alu.peak,
      windows: alu.windows,
      passMs: alu.passMs,
      maxTextureSize: id.maxTextureSize,
      workloads,
      adapter,
    };
  } finally {
    h.dispose();
    canvas.remove();
  }
}

/* ------------------------------------------------------------------- CPU -- */

/**
 * The worker body, inlined as a blob so the standalone single-file build keeps
 * working — a separate worker file cannot be fetched from a `file://` page.
 *
 * Two jobs, chosen by `mode`:
 *
 * `throughput` is integer-heavy and serial, for the same reason as the shader:
 * anything a JIT can hoist out of the loop gets hoisted, and then the test
 * measures the optimiser.
 *
 * `latency` chases a pointer around a randomly permuted array. Each load's
 * address is only known once the previous load has returned, so the hardware
 * prefetcher has nothing to work with and every access pays the real cost of
 * whichever level of memory holds it. Sweeping the array size turns the cache
 * hierarchy into a staircase.
 */
const WORKER_SRC = `
function rng(seed) {
  let s = seed >>> 0;
  return function () { s ^= s << 13; s >>>= 0; s ^= s >>> 17; s ^= s << 5; s >>>= 0; return s; };
}

function throughput(runMs, windowMs) {
  // A DURATION, never a deadline. performance.now() inside a worker counts
  // from when that worker was created, not from the page's origin, so an
  // absolute timestamp computed on the main thread means nothing here. Passing
  // one made every worker run for the main thread's full uptime instead of the
  // few seconds asked for: a 70-second benchmark took 203.
  const windows = [];
  const t0 = performance.now();
  const untilMs = t0 + runMs;
  let winStart = t0, winOps = 0, total = 0;
  let x = 123456789 >>> 0, y = 362436069 >>> 0, acc = 0;
  while (performance.now() < untilMs) {
    // A fixed block between clock reads: calling performance.now() every
    // iteration would measure the clock rather than the processor.
    for (let i = 0; i < 20000; i++) {
      x ^= x << 13; x >>>= 0; x ^= x >>> 17; x ^= x << 5; x >>>= 0;
      y = (y + 0x9e3779b9) >>> 0;
      acc = (acc + Math.imul(x ^ y, 0x85ebca6b)) >>> 0;
    }
    winOps += 20000; total += 20000;
    const now = performance.now();
    if (now - winStart >= windowMs) {
      windows.push({ atMs: winStart - t0, throughput: winOps / ((now - winStart) / 1000) });
      winStart = now; winOps = 0;
    }
  }
  const elapsed = (performance.now() - t0) / 1000;
  return { opsPerSec: total / elapsed, windows: windows, sink: acc };
}

function chaseOne(bytes) {
  const n = Math.max(256, Math.floor(bytes / 4));
  const a = new Int32Array(n);
  for (let i = 0; i < n; i++) a[i] = i;
  // Sattolo's algorithm — the j < i bound is what makes it a SINGLE cycle
  // through every slot rather than a permutation with short loops in it. A
  // short loop would sit in cache no matter how big the array is, which would
  // quietly turn the whole sweep into a measurement of L1.
  const r = rng(0x9e3779b9 ^ n);
  for (let i = n - 1; i > 0; i--) {
    const j = r() % i;
    const t = a[i]; a[i] = a[j]; a[j] = t;
  }

  // Settle the JIT, then size the timed run so it lasts tens of milliseconds —
  // long enough that the clock's resolution, which browsers deliberately
  // coarsen, cannot affect the answer.
  //
  // Capped. A full lap of a 128MB array is 32 million dependent loads at main
  // memory latency, which is three seconds spent on warm-up alone, and it buys
  // nothing: the shuffle above just wrote every element, so every page is
  // already resident and only the JIT still needs convincing.
  let p = 0;
  const warm = Math.min(n, 2000000);
  for (let i = 0; i < warm; i++) p = a[p];
  let steps = 200000;
  let dt = 0;
  for (let attempt = 0; attempt < 4; attempt++) {
    const t0 = performance.now();
    for (let i = 0; i < steps; i++) p = a[p];
    dt = performance.now() - t0;
    if (dt >= 25) break;
    steps = Math.min(60000000, Math.ceil(steps * Math.max(2, 30 / Math.max(dt, 0.5))));
  }
  return { bytes: bytes, latencyNs: (dt * 1e6) / steps, sink: p };
}

function latencySweep(sizes) {
  const out = [];
  for (let i = 0; i < sizes.length; i++) {
    try { out.push(chaseOne(sizes[i])); }
    catch (e) { break; }  // Out of memory at this size: stop, keep what we have.
  }
  return { curve: out };
}

self.onmessage = function (e) {
  const d = e.data;
  if (d.mode === 'latency') { self.postMessage(latencySweep(d.sizes)); return; }
  self.postMessage(throughput(d.runMs, d.windowMs));
};`;

function spawn(): Worker {
  const blob = new Blob([WORKER_SRC], { type: 'application/javascript' });
  const url = URL.createObjectURL(blob);
  const w = new Worker(url);
  // Safe immediately: the worker already holds its own reference to the blob.
  URL.revokeObjectURL(url);
  return w;
}

function ask<T>(payload: unknown): Promise<T> {
  const w = spawn();
  return new Promise<T>((resolve, reject) => {
    w.onmessage = (e) => {
      resolve(e.data as T);
      w.terminate();
    };
    w.onerror = (err) => {
      w.terminate();
      reject(new Error(err.message || 'worker failed'));
    };
    w.postMessage(payload);
  });
}

interface ThroughputReply { opsPerSec: number; windows: BenchWindow[] }

function runWorkers(count: number, seconds: number): Promise<ThroughputReply[]> {
  return Promise.all(
    Array.from({ length: count }, () => ask<ThroughputReply>({ runMs: seconds * 1000, windowMs: 1000 })),
  );
}

/**
 * The widths to measure at.
 *
 * Powers of two, plus half the thread count and the thread count itself. The
 * half matters: on a part with two threads per core the knee lands exactly
 * there, and on a 12-thread part — 6 cores, two threads each — a
 * powers-of-two-only ladder samples 4 and 8 and misses the 6 that would have
 * shown it.
 */
export function ladder(threads: number): number[] {
  const n = Math.max(1, Math.floor(threads));
  const set = new Set<number>([1, n]);
  for (let p = 2; p <= n; p *= 2) set.add(p);
  if (n >= 4) set.add(Math.ceil(n / 2));
  return [...set].filter((t) => t >= 1 && t <= n).sort((a, b) => a - b);
}

/**
 * Tick progress while an awaited phase runs.
 *
 * Workers report only when they finish, so without this the bar sat still for
 * the whole processor phase — which reads as a hang, and is the thing a
 * progress indicator exists to prevent.
 */
function ticker(opts: BenchOptions, phase: Progress['phase'], from: number, to: number, seconds: number, label: string) {
  const t0 = performance.now();
  return setInterval(() => {
    const f = Math.min(1, (performance.now() - t0) / (seconds * 1000));
    opts.onProgress?.({
      phase,
      fraction: from + (to - from) * f,
      label: `${label} — ${Math.max(0, Math.round(seconds - (performance.now() - t0) / 1000))}s left`,
    });
  }, 250);
}

/** Coefficient of variation: the scatter across repeats, relative to their size. */
function coefficientOfVariation(xs: number[]): number | undefined {
  const ok = xs.filter((x) => Number.isFinite(x) && x > 0);
  if (ok.length < 2) return undefined;
  const mean = ok.reduce((a, b) => a + b, 0) / ok.length;
  if (!(mean > 0)) return undefined;
  const varr = ok.reduce((a, b) => a + (b - mean) ** 2, 0) / (ok.length - 1);
  return Math.sqrt(varr) / mean;
}

/** Working-set sizes for the latency sweep, largest bounded by the plan. */
function sweepSizes(maxBytes: number): number[] {
  const sizes: number[] = [];
  for (let b = 8 << 10; b <= maxBytes; b *= 4) sizes.push(b);
  // The top of the sweep decides whether a large last-level cache can be seen
  // at all, so the bound itself is always sampled even when the x4 ladder
  // stepped over it.
  if (sizes[sizes.length - 1] !== maxBytes) sizes.push(maxBytes);
  return sizes;
}

async function runCpu(opts: BenchOptions, plan: Plan, notes: string[]): Promise<CpuProbe | null> {
  const threads = Math.max(1, navigator.hardwareConcurrency || 1);
  const widths = ladder(threads);
  let tick: ReturnType<typeof setInterval> | undefined;

  try {
    /* -- repeats at one thread, which set the noise floor ------------------ */
    const singles: number[] = [];
    let singleWindows: BenchWindow[] = [];
    for (let r = 0; r < plan.repeats; r++) {
      if (opts.signal?.aborted) break;
      const f = 0.5 + 0.06 * (r / plan.repeats);
      tick = ticker(opts, 'cpu-scaling', f, f + 0.06 / plan.repeats, plan.cpuLevelS,
        `one core, at full speed (${r + 1} of ${plan.repeats})`);
      const [reply] = await runWorkers(1, plan.cpuLevelS);
      clearInterval(tick);
      singles.push(reply.opsPerSec);
      singleWindows = reply.windows;
    }
    if (!singles.length) return null;
    // The median, not the mean: a repeat that was descheduled reads low and
    // nothing makes one read high, so a mean is dragged down by exactly the
    // samples that say least about the processor.
    const sorted = singles.slice().sort((a, b) => a - b);
    const singleThread = sorted[Math.floor(sorted.length / 2)];
    const variation = coefficientOfVariation(singles);

    /* -- the ladder -------------------------------------------------------- */
    const scaling: ScalingPoint[] = [{ threads: 1, totalOps: singleThread }];
    let multi: ThroughputReply[] = [{ opsPerSec: singleThread, windows: singleWindows }];
    const mid = widths.filter((w) => w > 1 && w < threads);

    for (let i = 0; i < mid.length; i++) {
      if (opts.signal?.aborted) break;
      const w = mid[i];
      const from = 0.56 + 0.12 * (i / Math.max(1, mid.length));
      const to = 0.56 + 0.12 * ((i + 1) / Math.max(1, mid.length));
      tick = ticker(opts, 'cpu-scaling', from, to, plan.cpuLevelS, `${w} threads at once`);
      const replies = await runWorkers(w, plan.cpuLevelS);
      clearInterval(tick);
      scaling.push({ threads: w, totalOps: replies.reduce((a, r) => a + r.opsPerSec, 0) });
    }

    /* -- all threads, held, which is where decline shows ------------------- */
    if (threads > 1 && !opts.signal?.aborted) {
      tick = ticker(opts, 'cpu-sustained', 0.68, 0.86, plan.cpuSustainedS, `all ${threads} threads, sustained`);
      multi = await runWorkers(threads, plan.cpuSustainedS);
      clearInterval(tick);
      scaling.push({ threads, totalOps: multi.reduce((a, r) => a + r.opsPerSec, 0) });
    }
    const multiTotal = multi.reduce((a, r) => a + r.opsPerSec, 0);

    // Windows are summed across workers at matching offsets, so the decline
    // seen is the machine's total throughput falling, not one worker's.
    const len = Math.min(...multi.map((r) => r.windows.length));
    const windows: BenchWindow[] = [];
    for (let i = 0; i < len; i++) {
      windows.push({
        atMs: multi[0].windows[i].atMs,
        throughput: multi.reduce((a, r) => a + r.windows[i].throughput, 0),
      });
    }

    /* -- the latency sweep -------------------------------------------------- */
    let latencyCurve: LatencyPoint[] | undefined;
    if (!opts.signal?.aborted) {
      const dm = (navigator as Navigator & { deviceMemory?: number }).deviceMemory;
      // A sweep that allocates more than the machine will comfortably give back
      // starts swapping, and a swapped read is a measurement of the disk. The
      // reported figure is capped at 8GB, so this is a floor and the cap is
      // treated as "plenty".
      const cap = typeof dm === 'number' && dm > 0 && dm < 4 ? 16 << 20 : plan.latencyMaxBytes;
      const sizes = sweepSizes(cap);
      tick = ticker(opts, 'cpu-latency', 0.86, 0.94, 4, 'walking memory to find the cache levels');
      try {
        const reply = await ask<{ curve: LatencyPoint[] }>({ mode: 'latency', sizes });
        latencyCurve = reply.curve.filter((p) => Number.isFinite(p.latencyNs) && p.latencyNs > 0);
        if (latencyCurve.length < sizes.length) {
          notes.push(
            `The memory-latency sweep stopped at ${((latencyCurve[latencyCurve.length - 1]?.bytes ?? 0) / 1024 / 1024).toFixed(0)}MB ` +
              `because the browser would not allocate more. The cache levels below that size were still read.`,
          );
        }
      } catch (e) {
        notes.push(`Memory-latency sweep skipped: ${e instanceof Error ? e.message : String(e)}`);
      } finally {
        clearInterval(tick);
      }
    }

    return {
      singleThread,
      multiThread: multiTotal,
      reportedThreads: threads,
      workersUsed: threads,
      windows,
      scaling,
      latencyCurve,
      variation,
    };
  } catch (e) {
    notes.push(`Processor test skipped: ${e instanceof Error ? e.message : String(e)}`);
    return null;
  } finally {
    clearInterval(tick);
  }
}

/* ---------------------------------------------------------------- memory -- */

function runMemory(opts: BenchOptions, notes: string[]): MemoryProbe | null {
  try {
    opts.onProgress?.({ phase: 'memory', fraction: 0.95, label: 'memory bandwidth' });
    // 32MB comfortably exceeds any consumer L3, so the sequential figure is
    // not simply cache speed.
    const n = 4 * 1024 * 1024;
    const a = new Float64Array(n);
    const b = new Float64Array(n);
    for (let i = 0; i < n; i++) a[i] = i * 1.000001;

    const bytes = n * 8;
    let t = performance.now();
    for (let r = 0; r < 4; r++) b.set(a);
    const copyGBs = (bytes * 2 * 4) / ((performance.now() - t) / 1000) / 1e9;

    // A 64-element stride skips a cache line each step, which defeats the
    // hardware prefetcher — the gap between this and the copy is what says
    // whether the memory subsystem is behaving.
    t = performance.now();
    let sink = 0;
    const stride = 64;
    for (let r = 0; r < 4; r++) for (let i = 0; i < n; i += stride) sink += a[i];
    const touched = Math.ceil(n / stride) * 4 * 64;
    const stridedGBs = touched / ((performance.now() - t) / 1000) / 1e9;
    void sink;

    return { copyGBs, stridedGBs };
  } catch (e) {
    notes.push(`Memory test skipped: ${e instanceof Error ? e.message : String(e)}`);
    return null;
  }
}

/* ------------------------------------------------------------------- run -- */

export async function runBenchmark(options: Partial<BenchOptions> = {}): Promise<BenchResult> {
  const opts: BenchOptions = { depth: DEFAULT_DEPTH, ...options };
  const plan = PLANS[opts.depth] ?? PLANS[DEFAULT_DEPTH];
  const notes: string[] = [];
  const startedAt = new Date().toISOString();
  const t0 = performance.now();

  let interrupted = document.visibilityState === 'hidden';
  const onVis = () => {
    if (document.visibilityState === 'hidden') interrupted = true;
  };
  document.addEventListener('visibilitychange', onVis);

  try {
    const gpu = await runGpu(opts, plan, notes);
    const cpu = await runCpu(opts, plan, notes);
    const memory = runMemory(opts, notes);
    if (opts.signal?.aborted) {
      notes.push(
        'The run was stopped before it finished, so any phase that had not started was skipped and any ' +
          'phase in progress has fewer samples than it wanted. Findings that depend on a sustained load — ' +
          'anything about decline or cooling — should not be read from a shortened run.',
      );
    }
    opts.onProgress?.({ phase: 'done', fraction: 1, label: 'done' });
    return {
      startedAt,
      durationS: (performance.now() - t0) / 1000,
      gpu,
      cpu,
      memory,
      notes,
      interrupted,
    };
  } finally {
    document.removeEventListener('visibilitychange', onVis);
  }
}
