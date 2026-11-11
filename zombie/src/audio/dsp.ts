// Low-level procedural DSP helpers shared by the REKINDLE audio engine.
// Everything here works with any BaseAudioContext (realtime or offline).

export type Ctx = BaseAudioContext;
export type F32 = Float32Array<ArrayBuffer>;
export type Pt = [number, number] | [number, number, 'l' | 'e' | 's'];
export type NoiseColor = 'white' | 'pink' | 'brown';

export const rand = (a: number, b: number): number => a + Math.random() * (b - a);
export const randi = (a: number, b: number): number => Math.floor(a + Math.random() * (b - a + 1));
export function pick<T>(arr: readonly T[]): T {
  return arr[Math.floor(Math.random() * arr.length)];
}
export const clamp = (x: number, a: number, b: number): number => (x < a ? a : x > b ? b : x);
export const lerp = (a: number, b: number, t: number): number => a + (b - a) * t;
export function smoothstep(a: number, b: number, x: number): number {
  const t = clamp((x - a) / (b - a), 0, 1);
  return t * t * (3 - 2 * t);
}
export const mtof = (m: number): number => 440 * Math.pow(2, (m - 69) / 12);

function interp(points: readonly number[], x: number): number {
  if (points.length === 1) return points[0];
  const p = clamp(x, 0, 1) * (points.length - 1);
  const i = Math.min(points.length - 2, Math.floor(p));
  return lerp(points[i], points[i + 1], p - i);
}

// ---------------------------------------------------------------------------
// Noise buffers (context independent, cached per sample rate)
// ---------------------------------------------------------------------------
const noiseCache = new Map<string, AudioBuffer>();

export function noiseBuffer(sampleRate: number, color: NoiseColor): AudioBuffer {
  const key = color + ':' + sampleRate;
  const hit = noiseCache.get(key);
  if (hit) return hit;
  const len = Math.floor(sampleRate * 4);
  const fade = Math.floor(sampleRate * 0.05);
  const raw = new Float32Array(len + fade);
  let b0 = 0, b1 = 0, b2 = 0, b3 = 0, b4 = 0, b5 = 0, b6 = 0, last = 0;
  for (let i = 0; i < raw.length; i++) {
    const w = Math.random() * 2 - 1;
    if (color === 'white') raw[i] = w;
    else if (color === 'pink') {
      b0 = 0.99886 * b0 + w * 0.0555179;
      b1 = 0.99332 * b1 + w * 0.0750759;
      b2 = 0.969 * b2 + w * 0.153852;
      b3 = 0.8665 * b3 + w * 0.3104856;
      b4 = 0.55 * b4 + w * 0.5329522;
      b5 = -0.7616 * b5 - w * 0.016898;
      raw[i] = (b0 + b1 + b2 + b3 + b4 + b5 + b6 + w * 0.5362) * 0.11;
      b6 = w * 0.115926;
    } else {
      last = (last + 0.02 * w) / 1.02;
      raw[i] = last * 3.5;
    }
  }
  const buf = new AudioBuffer({ length: len, sampleRate, numberOfChannels: 1 });
  const d = buf.getChannelData(0);
  let sum = 0;
  for (let i = 0; i < len; i++) {
    let v = raw[i];
    if (i < fade) {
      const x = i / fade;
      v = raw[i] * Math.sqrt(x) + raw[len + i] * Math.sqrt(1 - x);
    }
    d[i] = v;
    sum += v * v;
  }
  // RMS-normalise so all colours sit at a comparable loudness.
  const rms = Math.sqrt(sum / len) || 1;
  const k = 0.3 / rms;
  for (let i = 0; i < len; i++) d[i] = clamp(d[i] * k, -1, 1);
  noiseCache.set(key, buf);
  return buf;
}

// ---------------------------------------------------------------------------
// Node helpers
// ---------------------------------------------------------------------------
export function osc(ctx: Ctx, type: OscillatorType, freq: number, t0: number, t1?: number): OscillatorNode {
  const o = ctx.createOscillator();
  o.type = type;
  o.frequency.value = freq;
  o.start(t0);
  if (t1 !== undefined) o.stop(t1);
  return o;
}

export function noise(ctx: Ctx, color: NoiseColor, t0: number, t1?: number, rate = 1): AudioBufferSourceNode {
  const s = ctx.createBufferSource();
  s.buffer = noiseBuffer(ctx.sampleRate, color);
  s.loop = true;
  s.playbackRate.value = rate;
  s.start(t0, Math.random() * 3.5);
  if (t1 !== undefined) s.stop(t1);
  return s;
}

export function gain(ctx: Ctx, v = 1): GainNode {
  const g = ctx.createGain();
  g.gain.value = v;
  return g;
}

export function bq(ctx: Ctx, type: BiquadFilterType, f: number, Q = 0.707, dB = 0): BiquadFilterNode {
  const b = ctx.createBiquadFilter();
  b.type = type;
  b.frequency.value = f;
  b.Q.value = Q;
  b.gain.value = dB;
  return b;
}

const curveCache = new Map<number, F32>();
export function shaperCurve(drive: number): F32 {
  const key = Math.round(drive * 100);
  const hit = curveCache.get(key);
  if (hit) return hit;
  const n = 2048;
  const c = new Float32Array(n);
  const norm = Math.tanh(drive);
  for (let i = 0; i < n; i++) {
    const x = (i / (n - 1)) * 2 - 1;
    c[i] = Math.tanh(drive * x) / norm;
  }
  curveCache.set(key, c);
  return c;
}

export function shaper(ctx: Ctx, drive: number): WaveShaperNode {
  const s = ctx.createWaveShaper();
  s.curve = shaperCurve(drive);
  s.oversample = '2x';
  return s;
}

/** Apply a list of automation points relative to t0. 'l' linear, 'e' exponential, 's' set. */
export function seg(p: AudioParam, t0: number, pts: readonly Pt[]): void {
  let first = true;
  for (const pt of pts) {
    const t = t0 + pt[0];
    const v = pt[1];
    const m = pt[2] ?? 'l';
    if (first || m === 's') {
      p.setValueAtTime(v, t);
      first = false;
    } else if (m === 'e') p.exponentialRampToValueAtTime(Math.max(v, 1e-4), t);
    else p.linearRampToValueAtTime(v, t);
  }
}

/** Gain node with attack → decay envelope. */
export function envGain(ctx: Ctx, t: number, attack: number, decay: number, amp: number, curve: 'e' | 'l' = 'e'): GainNode {
  const g = ctx.createGain();
  g.gain.value = 0;
  const a = Math.max(0.0005, attack);
  const pk = Math.max(1e-4, amp);
  seg(g.gain, t, [[0, 0], [a, pk], [a + decay, curve === 'e' ? 1e-4 : 0, curve], [a + decay + 0.005, 0]]);
  return g;
}

/** Random-walk curve around a contour. */
export function contour(points: readonly number[], n: number, jitter: number, smooth = 0.85, min = 1): F32 {
  const c = new Float32Array(Math.max(2, n));
  let w = 0;
  for (let i = 0; i < c.length; i++) {
    w = w * smooth + (Math.random() * 2 - 1) * jitter;
    c[i] = Math.max(min, interp(points, i / (c.length - 1)) * (1 + w));
  }
  return c;
}

// ---------------------------------------------------------------------------
// Synthesis building blocks
// ---------------------------------------------------------------------------
export interface BurstOpts {
  dur: number;
  color?: NoiseColor;
  type?: BiquadFilterType;
  f?: number;
  f1?: number;
  Q?: number;
  amp?: number;
  attack?: number;
  curve?: 'e' | 'l';
  rate?: number;
}

export function burst(ctx: Ctx, out: AudioNode, t: number, o: BurstOpts): void {
  const attack = o.attack ?? 0.001;
  const end = t + attack + o.dur;
  const n = noise(ctx, o.color ?? 'white', t, end + 0.03, o.rate ?? 1);
  const f0 = o.f ?? 1000;
  const f = bq(ctx, o.type ?? 'bandpass', f0, o.Q ?? 0.7);
  if (o.f1 !== undefined) {
    f.frequency.setValueAtTime(f0, t);
    f.frequency.exponentialRampToValueAtTime(Math.max(20, o.f1), end);
  }
  const g = envGain(ctx, t, attack, o.dur, o.amp ?? 1, o.curve ?? 'e');
  n.connect(f).connect(g).connect(out);
}

/** Sine with downward pitch sweep: kick / body thump. */
export function thump(ctx: Ctx, out: AudioNode, t: number, f0: number, f1: number, dur: number, amp: number): void {
  const o = osc(ctx, 'sine', f0, t, t + dur + 0.05);
  o.frequency.setValueAtTime(f0, t);
  o.frequency.exponentialRampToValueAtTime(Math.max(10, f1), t + dur);
  const g = envGain(ctx, t, 0.002, dur, amp);
  o.connect(g).connect(out);
}

/** Modal synthesis: decaying sines [freq, decaySeconds, amp]. */
export function modal(ctx: Ctx, out: AudioNode, t: number, modes: ReadonlyArray<readonly [number, number, number]>, attack = 0.0008): void {
  for (const [f, d, a] of modes) {
    if (f >= ctx.sampleRate / 2.1) continue;
    const o = osc(ctx, 'sine', f, t, t + d + attack + 0.02);
    const g = envGain(ctx, t, attack, d, a);
    o.connect(g).connect(out);
  }
}

export function crackles(ctx: Ctx, out: AudioNode, t: number, span: number, count: number, fLo: number, fHi: number, amp: number): void {
  for (let i = 0; i < count; i++) {
    const tt = t + Math.random() * span;
    burst(ctx, out, tt, { dur: rand(0.002, 0.012), type: 'bandpass', f: rand(fLo, fHi), Q: 1.4, amp: amp * rand(0.3, 1) });
  }
}

export function whoosh(ctx: Ctx, out: AudioNode, t: number, dur: number, f0: number, fp: number, f1: number, amp: number, Q = 1.4): void {
  const n = noise(ctx, 'white', t, t + dur + 0.05);
  const f = bq(ctx, 'bandpass', f0, Q);
  f.frequency.setValueAtTime(f0, t);
  f.frequency.exponentialRampToValueAtTime(fp, t + dur * 0.45);
  f.frequency.exponentialRampToValueAtTime(f1, t + dur);
  const g = gain(ctx, 0);
  seg(g.gain, t, [[0, 0], [dur * 0.45, amp], [dur, 0]]);
  n.connect(f).connect(g).connect(out);
  const n2 = noise(ctx, 'pink', t, t + dur + 0.05);
  const f2 = bq(ctx, 'lowpass', fp * 0.6, 0.7);
  const g2 = gain(ctx, 0);
  seg(g2.gain, t, [[0, 0], [dur * 0.5, amp * 0.5], [dur, 0]]);
  n2.connect(f2).connect(g2).connect(out);
}

export interface VoiceOpts {
  f0: readonly number[];
  jitter: number;
  breath: number;
  vowels: ReadonlyArray<readonly [number, number, number]>;
  gurgle: number;
  gurgleRate: number;
  drive: number;
  amp?: number;
  attack: number;
  release: number;
  sub?: number;
}

/** Source-filter voice model: jittery sawtooth + breath → drive → formant bank → gurgle AM. */
export function voice(ctx: Ctx, out: AudioNode, t: number, dur: number, o: VoiceOpts): void {
  const end = t + dur + 0.05;
  const n = Math.max(24, Math.floor(dur * 70));
  const pitch = contour(o.f0, n, o.jitter, 0.8, 20);
  const src = osc(ctx, 'sawtooth', o.f0[0], t, end);
  src.frequency.setValueCurveAtTime(pitch, t, dur);
  const mix = gain(ctx, 1);
  src.connect(mix);
  // Sub-harmonic / vocal fry layer
  const sub = osc(ctx, 'sawtooth', o.f0[0] * 0.5, t, end);
  const subCurve = new Float32Array(pitch.length);
  for (let i = 0; i < pitch.length; i++) subCurve[i] = pitch[i] * 0.5 * (1 + (Math.random() - 0.5) * o.jitter);
  sub.frequency.setValueCurveAtTime(subCurve, t, dur);
  const subG = gain(ctx, o.sub ?? 0.35);
  sub.connect(subG).connect(mix);
  const nz = noise(ctx, 'white', t, end);
  const nzG = gain(ctx, o.breath * 1.6);
  nz.connect(nzG).connect(mix);
  const drive = shaper(ctx, o.drive);
  mix.connect(drive);
  const fsum = gain(ctx, 1);
  const Qs = [5, 7, 9];
  const amps = [1, 0.7, 0.3];
  for (let k = 0; k < 3; k++) {
    const pts = o.vowels.map((v) => v[k]);
    const bp = bq(ctx, 'bandpass', pts[0], Qs[k]);
    if (pts.length > 1) bp.frequency.setValueCurveAtTime(contour(pts, 32, 0.03, 0.7, 60), t, dur);
    const g = gain(ctx, amps[k] * 2.2);
    drive.connect(bp).connect(g).connect(fsum);
  }
  // Gurgle: sine LFO + random low-passed noise AM
  const am = gain(ctx, 1 - o.gurgle * 0.55);
  const lfo = osc(ctx, 'sine', o.gurgleRate, t, end);
  lfo.frequency.setValueCurveAtTime(contour([o.gurgleRate], 16, 0.25, 0.6, 1), t, dur);
  const lfoG = gain(ctx, o.gurgle * 0.4);
  lfo.connect(lfoG).connect(am.gain);
  const rn = noise(ctx, 'brown', t, end);
  const rnF = bq(ctx, 'lowpass', 25, 0.7);
  const rnG = gain(ctx, o.gurgle * 1.2);
  rn.connect(rnF).connect(rnG).connect(am.gain);
  const env = gain(ctx, 0);
  const amp = o.amp ?? 1;
  seg(env.gain, t, [[0, 0], [o.attack, amp], [Math.max(o.attack + 0.01, dur - o.release), amp * 0.8], [dur, 0]]);
  fsum.connect(am).connect(env).connect(out);
}

/** Stick-slip friction creak (hinges, metal). r0/r1 are pulse rates in Hz. */
export function creak(ctx: Ctx, out: AudioNode, t: number, dur: number, r0: number, r1: number, amp: number,
  res: readonly number[] = [720, 1450, 2600], Q = 12): void {
  const end = t + dur + 0.05;
  const o = osc(ctx, 'sawtooth', r0, t, end);
  o.frequency.setValueCurveAtTime(contour([r0, (r0 + r1) * 0.6, r1], Math.floor(dur * 40) + 8, 0.25, 0.6, 4), t, dur);
  const hp = bq(ctx, 'highpass', 180, 0.7);
  o.connect(hp);
  const sum = gain(ctx, 1);
  for (const f of res) {
    const bp = bq(ctx, 'bandpass', f * rand(0.93, 1.07), Q);
    const g = gain(ctx, 3);
    hp.connect(bp).connect(g).connect(sum);
  }
  const env = gain(ctx, 0);
  const n = Math.floor(dur * 30) + 4;
  const c = new Float32Array(n);
  let w = 0.6;
  for (let i = 0; i < n; i++) {
    w = clamp(w + (Math.random() - 0.5) * 0.5, 0.15, 1);
    const x = i / (n - 1);
    const edge = Math.min(1, x * 8, (1 - x) * 6);
    c[i] = w * edge * amp;
  }
  c[0] = 0;
  c[n - 1] = 0;
  env.gain.setValueCurveAtTime(c, t, dur);
  sum.connect(env).connect(out);
}

/** Soft inharmonic bell / chime. */
export function bell(ctx: Ctx, out: AudioNode, t: number, f: number, dur: number, amp: number): void {
  const ratios = [1, 2, 3.01, 4.17, 5.43];
  const amps = [1, 0.4, 0.2, 0.1, 0.05];
  const decs = [1, 0.6, 0.4, 0.25, 0.15];
  modal(ctx, out, t, ratios.map((r, i) => [f * r, dur * decs[i], amp * amps[i]] as const), 0.003);
}

/** Dry + delayed low-passed echoes. Returns the input node. */
export function echoes(ctx: Ctx, out: AudioNode, taps: ReadonlyArray<readonly [number, number, number]>): GainNode {
  const input = gain(ctx, 1);
  input.connect(out);
  for (const [time, g, lp] of taps) {
    const d = ctx.createDelay(Math.max(1, time + 0.1));
    d.delayTime.value = time;
    const f = bq(ctx, 'lowpass', lp, 0.5);
    const gg = gain(ctx, g);
    input.connect(d).connect(f).connect(gg).connect(out);
  }
  return input;
}

// ---------------------------------------------------------------------------
// Buffer generation / post-processing (pure JS)
// ---------------------------------------------------------------------------
export interface ImpulseOpts {
  lpStart: number;
  lpEnd: number;
  predelay?: number;
  early?: ReadonlyArray<readonly [number, number]>;
}

/** Stereo reverb impulse: decaying noise whose brightness falls over time + early reflections. */
export function makeImpulse(sampleRate: number, seconds: number, o: ImpulseOpts): AudioBuffer {
  const len = Math.floor(seconds * sampleRate);
  const buf = new AudioBuffer({ length: len, sampleRate, numberOfChannels: 2 });
  const pre = Math.floor((o.predelay ?? 0.01) * sampleRate);
  const tail = Math.max(0.05, seconds - (o.predelay ?? 0.01));
  for (let ch = 0; ch < 2; ch++) {
    const d = buf.getChannelData(ch);
    let lp = 0;
    let lp2 = 0;
    for (let i = pre; i < len; i++) {
      const tt = (i - pre) / sampleRate;
      const env = Math.pow(10, (-3 * tt) / tail);
      const cut = o.lpStart * Math.pow(o.lpEnd / o.lpStart, tt / tail);
      const a = 1 - Math.exp((-2 * Math.PI * cut) / sampleRate);
      lp += a * (Math.random() * 2 - 1 - lp);
      lp2 += a * (lp - lp2);
      const fadeIn = Math.min(1, tt / 0.004);
      d[i] = lp2 * env * fadeIn * (1 + (1 - a) * 2);
    }
    if (o.early) {
      for (const [time, g] of o.early) {
        const idx = Math.floor((time + (ch ? rand(0.0005, 0.004) : 0)) * sampleRate);
        for (let k = 0; k < 24 && idx + k < len; k++) {
          d[idx + k] += g * (Math.random() * 2 - 1) * Math.exp(-k / 6);
        }
      }
    }
  }
  return buf;
}

/** Crossfade the tail beyond `loopSeconds` back onto the head so the buffer loops seamlessly. */
export function makeLoopable(src: AudioBuffer, loopSeconds: number): AudioBuffer {
  const sr = src.sampleRate;
  const L = Math.min(src.length, Math.floor(loopSeconds * sr));
  const F = Math.min(src.length - L, Math.floor(sr * 0.25));
  const out = new AudioBuffer({ length: L, sampleRate: sr, numberOfChannels: src.numberOfChannels });
  for (let ch = 0; ch < src.numberOfChannels; ch++) {
    const s = src.getChannelData(ch);
    const d = out.getChannelData(ch);
    for (let i = 0; i < L; i++) {
      if (i < F) {
        const x = i / F;
        d[i] = s[i] * Math.sqrt(x) + s[L + i] * Math.sqrt(1 - x);
      } else d[i] = s[i];
    }
  }
  return out;
}

/** Peak-normalise and trim trailing silence. */
export function finalize(src: AudioBuffer, peak: number, trimDb = -66): AudioBuffer {
  let max = 0;
  for (let ch = 0; ch < src.numberOfChannels; ch++) {
    const d = src.getChannelData(ch);
    for (let i = 0; i < d.length; i++) {
      const a = Math.abs(d[i]);
      if (a > max) max = a;
    }
  }
  if (max < 1e-6) return src;
  const k = peak / max;
  const thr = peak * Math.pow(10, trimDb / 20);
  let end = 0;
  for (let ch = 0; ch < src.numberOfChannels; ch++) {
    const d = src.getChannelData(ch);
    for (let i = d.length - 1; i > end; i--) {
      if (Math.abs(d[i] * k) > thr) {
        end = i;
        break;
      }
    }
  }
  const sr = src.sampleRate;
  const len = Math.max(64, Math.min(src.length, end + Math.floor(sr * 0.02)));
  const out = new AudioBuffer({ length: len, sampleRate: sr, numberOfChannels: src.numberOfChannels });
  const fade = Math.min(len, Math.floor(sr * 0.02));
  for (let ch = 0; ch < src.numberOfChannels; ch++) {
    const s = src.getChannelData(ch);
    const d = out.getChannelData(ch);
    for (let i = 0; i < len; i++) {
      let v = s[i] * k;
      const r = len - i;
      if (r < fade) v *= r / fade;
      d[i] = v;
    }
  }
  return out;
}

/** Additive piano-ish tone with inharmonic partials, double-decay and hammer noise. */
export function genPiano(sampleRate: number, f0: number, seconds: number): AudioBuffer {
  const len = Math.floor(sampleRate * seconds);
  const buf = new AudioBuffer({ length: len, sampleRate, numberOfChannels: 1 });
  const d = buf.getChannelData(0);
  const B = 0.00035;
  const tw = (2 * Math.PI) / sampleRate;
  for (let n = 1; n <= 12; n++) {
    const fn = f0 * n * Math.sqrt(1 + B * n * n);
    if (fn > sampleRate / 2.3) break;
    const amp = (1 / Math.pow(n, 1.15)) * (1 + 0.35 * Math.sin(n * 1.9));
    const tau = 1.3 / (1 + 0.32 * (n - 1));
    const det = 1 + 0.0007 * (n % 3 === 0 ? -1 : 1);
    const ph1 = Math.random() * 6.28;
    const ph2 = Math.random() * 6.28;
    for (let i = 0; i < len; i++) {
      const t = i / sampleRate;
      const env = 0.65 * Math.exp(-t / tau) + 0.35 * Math.exp(-t / (tau * 4.5));
      d[i] += amp * env * 0.5 * (Math.sin(tw * fn * i + ph1) + Math.sin(tw * fn * det * i + ph2));
    }
  }
  let lp = 0;
  const hammer = Math.floor(sampleRate * 0.02);
  for (let i = 0; i < len; i++) {
    const t = i / sampleRate;
    d[i] *= 1 - Math.exp(-t / 0.0015);
    if (i < hammer) {
      lp += 0.2 * (Math.random() * 2 - 1 - lp);
      d[i] += lp * 0.4 * (1 - i / hammer);
    }
  }
  return finalize(buf, 0.9);
}

/** Karplus-Strong plucked string (guitar-like). */
export function genPluck(sampleRate: number, f0: number, seconds: number): AudioBuffer {
  const len = Math.floor(sampleRate * seconds);
  const buf = new AudioBuffer({ length: len, sampleRate, numberOfChannels: 1 });
  const d = buf.getChannelData(0);
  const N = Math.max(2, Math.round(sampleRate / f0));
  const ring = new Float32Array(N);
  for (let i = 0; i < N; i++) ring[i] = Math.random() * 2 - 1;
  for (let pass = 0; pass < 2; pass++) for (let i = 0; i < N; i++) ring[i] = (ring[i] + ring[(i + 1) % N]) * 0.5;
  let idx = 0;
  let lp = 0;
  let body = 0;
  let bodyV = 0;
  const bw = 2 * Math.PI * 190 / sampleRate;
  for (let i = 0; i < len; i++) {
    const cur = ring[idx];
    const nxt = ring[(idx + 1) % N];
    ring[idx] = (cur + nxt) * 0.5 * 0.9965;
    idx = (idx + 1) % N;
    lp += 0.5 * (cur - lp);
    // light body resonance (damped oscillator)
    bodyV += (lp - body) * bw * bw * 20 - bodyV * 0.02;
    body += bodyV;
    d[i] = lp + body * 0.02;
  }
  return finalize(buf, 0.9);
}

/** Loopable stereo cricket chorus. */
export function genCrickets(sampleRate: number, seconds: number, count: number): AudioBuffer {
  const len = Math.floor(sampleRate * seconds);
  const buf = new AudioBuffer({ length: len, sampleRate, numberOfChannels: 2 });
  const L = buf.getChannelData(0);
  const R = buf.getChannelData(1);
  const tw = 2 * Math.PI / sampleRate;
  for (let c = 0; c < count; c++) {
    const far = c >= count / 2;
    const f = rand(3900, 5300);
    const k = Math.max(2, Math.round(seconds / rand(0.55, 1.2)));
    const period = seconds / k;
    const pulses = randi(2, 5);
    const pulseLen = rand(0.010, 0.016);
    const pulseGap = rand(0.028, 0.04);
    const amp = (far ? rand(0.05, 0.15) : rand(0.2, 0.5));
    const pan = rand(-0.9, 0.9);
    const gl = amp * Math.sqrt((1 - pan) / 2);
    const gr = amp * Math.sqrt((1 + pan) / 2);
    const off = Math.random() * period;
    const skip = Math.random() * 0.3;
    for (let j = 0; j < k; j++) {
      if (Math.random() < skip) continue;
      const start = off + j * period + rand(-0.01, 0.01);
      for (let p = 0; p < pulses; p++) {
        const ps = start + p * pulseGap;
        const n0 = Math.floor(ps * sampleRate);
        const nl = Math.floor(pulseLen * sampleRate);
        for (let i = 0; i < nl; i++) {
          const idx = ((n0 + i) % len + len) % len;
          const w = 0.5 - 0.5 * Math.cos((2 * Math.PI * i) / nl);
          const v = Math.sin(tw * f * (n0 + i)) * w;
          L[idx] += v * gl;
          R[idx] += v * gr;
        }
      }
    }
  }
  return buf;
}
