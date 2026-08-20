/**
 * The measurement side of the in-browser benchmark.
 *
 * Everything here touches the GPU, workers or the clock, so none of it is
 * unit-testable; the decisions it feeds are in core/browserbench.ts, which is.
 * What lives here is the part that has to be right about the platform rather
 * than about hardware.
 *
 * Three platform problems drive the whole design:
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
 */

import type { BenchResult, BenchWindow, CpuProbe, GpuProbe, MemoryProbe } from '../../core/browserbench.ts';

export interface Progress {
  phase: 'gpu-calibrate' | 'gpu-sustained' | 'cpu-single' | 'cpu-multi' | 'memory' | 'done';
  /** 0..1 within the whole run. */
  fraction: number;
  label: string;
}

export interface BenchOptions {
  /** Seconds of sustained GPU load. Longer finds thermal decline a short run misses. */
  gpuSustainedS: number;
  /** Seconds of sustained CPU load. */
  cpuSustainedS: number;
  onProgress?: (p: Progress) => void;
  /** Cooperative cancellation. */
  signal?: AbortSignal;
}

export const DEFAULT_BENCH: Omit<BenchOptions, 'onProgress' | 'signal'> = {
  gpuSustainedS: 40,
  cpuSustainedS: 20,
};

const sleep = (ms: number) => new Promise<void>((r) => setTimeout(r, ms));

/* ------------------------------------------------------------------- GPU -- */

/**
 * A fragment shader with a genuinely serial inner loop.
 *
 * The loop must not be reducible to a closed form or the driver's optimiser
 * will delete it and report an absurd score. Each iteration therefore feeds
 * the previous one through a transcendental, and the result is written to the
 * output so nothing is dead code.
 */
const FRAG = `#version 300 es
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

const VERT = `#version 300 es
in vec2 aPos;
void main() { gl_Position = vec4(aPos, 0.0, 1.0); }`;

const GPU_SIDE = 512;

function compile(gl: WebGL2RenderingContext, type: number, src: string): WebGLShader {
  const sh = gl.createShader(type)!;
  gl.shaderSource(sh, src);
  gl.compileShader(sh);
  if (!gl.getShaderParameter(sh, gl.COMPILE_STATUS)) {
    throw new Error(`shader: ${gl.getShaderInfoLog(sh)}`);
  }
  return sh;
}

class GpuHarness {
  private gl: WebGL2RenderingContext;
  private prog: WebGLProgram;
  private iterLoc: WebGLUniformLocation;
  private seedLoc: WebGLUniformLocation;
  private px = new Uint8Array(4);
  private seed = 0;

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
    canvas.width = GPU_SIDE;
    canvas.height = GPU_SIDE;

    const prog = gl.createProgram()!;
    gl.attachShader(prog, compile(gl, gl.VERTEX_SHADER, VERT));
    gl.attachShader(prog, compile(gl, gl.FRAGMENT_SHADER, FRAG));
    gl.linkProgram(prog);
    if (!gl.getProgramParameter(prog, gl.LINK_STATUS)) {
      throw new Error(`link: ${gl.getProgramInfoLog(prog)}`);
    }
    this.prog = prog;
    gl.useProgram(prog);
    this.iterLoc = gl.getUniformLocation(prog, 'uIter')!;
    this.seedLoc = gl.getUniformLocation(prog, 'uSeed')!;

    const buf = gl.createBuffer();
    gl.bindBuffer(gl.ARRAY_BUFFER, buf);
    gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([-1, -1, 3, -1, -1, 3]), gl.STATIC_DRAW);
    const loc = gl.getAttribLocation(prog, 'aPos');
    gl.enableVertexAttribArray(loc);
    gl.vertexAttribPointer(loc, 2, gl.FLOAT, false, 0, 0);
    gl.viewport(0, 0, GPU_SIDE, GPU_SIDE);
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

  /** One fenced pass. Returns milliseconds of real GPU time. */
  pass(iter: number): number {
    const gl = this.gl;
    gl.uniform1i(this.iterLoc, iter);
    gl.uniform1f(this.seedLoc, (this.seed = (this.seed + 1) % 997));
    const t0 = performance.now();
    gl.drawArrays(gl.TRIANGLES, 0, 3);
    // Blocks until the draw has actually completed. Without this the timing
    // measures command submission and every GPU looks the same.
    gl.readPixels(0, 0, 1, 1, gl.RGBA, gl.UNSIGNED_BYTE, this.px);
    return performance.now() - t0;
  }

  /**
   * Find an iteration count that makes one pass take roughly 25ms.
   *
   * Fixing the iteration count instead would either finish instantly on a fast
   * card or hang a slow one for seconds at a time, and a browser that stops
   * painting for seconds looks broken. Auto-ranging keeps every machine on the
   * same wall-clock budget per pass, so the comparison is throughput.
   */
  calibrate(): number {
    let iter = 32;
    for (let i = 0; i < 24; i++) {
      const ms = this.pass(iter);
      if (ms > 22 && ms < 60) return iter;
      if (ms >= 60) return Math.max(8, Math.floor(iter * (25 / ms)));
      const scale = Math.min(6, Math.max(1.5, 25 / Math.max(ms, 0.2)));
      iter = Math.min(4_000_000, Math.floor(iter * scale));
    }
    return iter;
  }

  throughputFor(iter: number, ms: number): number {
    // Shader invocations per second: pixels x loop iterations, over the time.
    return (GPU_SIDE * GPU_SIDE * iter) / (ms / 1000);
  }

  dispose() {
    this.gl.deleteProgram(this.prog);
    const lose = this.gl.getExtension('WEBGL_lose_context');
    lose?.loseContext();
  }
}

async function runGpu(opts: BenchOptions, notes: string[], hidden: () => boolean): Promise<GpuProbe | null> {
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

    opts.onProgress?.({ phase: 'gpu-calibrate', fraction: 0.02, label: 'sizing the graphics workload' });
    const iter = h.calibrate();
    // Warm-up: the first passes after calibration run at idle clocks, and
    // including them would make every machine look like it throttles.
    for (let i = 0; i < 6; i++) h.pass(iter);

    const windows: BenchWindow[] = [];
    const t0 = performance.now();
    const totalMs = opts.gpuSustainedS * 1000;
    let best = 0;

    while (performance.now() - t0 < totalMs) {
      if (opts.signal?.aborted) break;
      const at = performance.now() - t0;
      const ms = h.pass(iter);
      const thr = h.throughputFor(iter, ms);
      windows.push({ atMs: at, throughput: thr });
      if (thr > best) best = thr;
      opts.onProgress?.({
        phase: 'gpu-sustained',
        fraction: 0.02 + 0.5 * Math.min(1, at / totalMs),
        label: `holding graphics load — ${Math.round((totalMs - at) / 1000)}s left`,
      });
      // Yield so the page keeps painting. Without this the tab locks up and
      // the browser offers to kill it.
      await sleep(0);
      if (hidden()) break;
    }

    return {
      renderer: id.renderer,
      vendor: id.vendor,
      api: 'webgl2',
      // Best rather than mean: the peak is the machine at its clocks, and the
      // decline away from it is reported separately as its own finding rather
      // than being averaged into a single number that hides both.
      throughput: best,
      windows,
      maxTextureSize: id.maxTextureSize,
    };
  } finally {
    h.dispose();
  }
}

/* ------------------------------------------------------------------- CPU -- */

/**
 * The worker body, inlined as a blob so the standalone single-file build keeps
 * working — a separate worker file cannot be fetched from a `file://` page.
 *
 * The workload is integer-heavy and serial for the same reason as the shader:
 * anything a JIT can hoist out of the loop gets hoisted, and then the test
 * measures the optimiser.
 */
const WORKER_SRC = `
self.onmessage = (e) => {
  // A DURATION, never a deadline. performance.now() inside a worker counts
  // from when that worker was created, not from the page's origin, so an
  // absolute timestamp computed on the main thread means nothing here. Passing
  // one made every worker run for the main thread's full uptime instead of the
  // few seconds asked for: a 70-second benchmark took 203.
  const { runMs, windowMs } = e.data;
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
  self.postMessage({ opsPerSec: total / elapsed, windows, sink: acc });
};`;

function spawn(): Worker {
  const blob = new Blob([WORKER_SRC], { type: 'application/javascript' });
  const url = URL.createObjectURL(blob);
  const w = new Worker(url);
  // Safe immediately: the worker already holds its own reference to the blob.
  URL.revokeObjectURL(url);
  return w;
}

function runWorkers(count: number, seconds: number): Promise<{ opsPerSec: number; windows: BenchWindow[] }[]> {
  const workers = Array.from({ length: count }, spawn);
  return Promise.all(
    workers.map(
      (w) =>
        new Promise<{ opsPerSec: number; windows: BenchWindow[] }>((resolve, reject) => {
          w.onmessage = (e) => {
            resolve(e.data as { opsPerSec: number; windows: BenchWindow[] });
            w.terminate();
          };
          w.onerror = (err) => {
            w.terminate();
            reject(new Error(err.message || 'worker failed'));
          };
          w.postMessage({ runMs: seconds * 1000, windowMs: 1000 });
        }),
    ),
  );
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

async function runCpu(opts: BenchOptions, notes: string[]): Promise<CpuProbe | null> {
  const threads = Math.max(1, navigator.hardwareConcurrency || 1);
  const singleS = Math.max(4, opts.cpuSustainedS / 3);
  let tick: ReturnType<typeof setInterval> | undefined;
  try {
    tick = ticker(opts, 'cpu-single', 0.55, 0.68, singleS, 'one core, at full speed');
    const single = await runWorkers(1, singleS);
    clearInterval(tick);

    tick = ticker(opts, 'cpu-multi', 0.68, 0.9, opts.cpuSustainedS, `all ${threads} threads, sustained`);
    const multi = await runWorkers(threads, opts.cpuSustainedS);
    clearInterval(tick);

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

    return {
      singleThread: single[0].opsPerSec,
      multiThread: multiTotal,
      reportedThreads: threads,
      workersUsed: threads,
      windows,
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
    opts.onProgress?.({ phase: 'memory', fraction: 0.92, label: 'memory bandwidth' });
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
  const opts: BenchOptions = { ...DEFAULT_BENCH, ...options };
  const notes: string[] = [];
  const startedAt = new Date().toISOString();
  const t0 = performance.now();

  let interrupted = document.visibilityState === 'hidden';
  const onVis = () => {
    if (document.visibilityState === 'hidden') interrupted = true;
  };
  document.addEventListener('visibilitychange', onVis);

  try {
    const gpu = await runGpu(opts, notes, () => false);
    const cpu = await runCpu(opts, notes);
    const memory = runMemory(opts, notes);
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
