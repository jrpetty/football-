// Machine / environment loop recipes. Each is rendered offline once to a
// seamlessly-looping buffer (render L + crossfade tail, then fold the tail
// onto the head), so any number of loop instances is cheap to play.

import type { Recipe } from './sfx';
import { type Ctx, bq, burst, crackles, gain, modal, noise, osc, rand, shaper, thump } from './dsp';

export type LoopName = 'machine_hum' | 'smelter' | 'drill' | 'generator' | 'campfire' | 'belt' | 'turbine' | 'fence_buzz';

export interface LoopDef {
  /** loop length in seconds (tail of 0.25 s is rendered on top for the crossfade) */
  len: number;
  vol: number;
  rolloff: number;
  ref?: number;
  prio: number;
  build: Recipe;
}

const X = 0.3; // render overhang for crossfade

/** Random amplitude curve for flicker / AM. */
function flicker(n: number, lo: number, hi: number, smooth: number): Float32Array<ArrayBuffer> {
  const c = new Float32Array(n);
  let v = (lo + hi) / 2;
  for (let i = 0; i < n; i++) {
    const target = rand(lo, hi);
    v = v * smooth + target * (1 - smooth);
    c[i] = v;
  }
  return c;
}

function hum(ctx: Ctx, out: AudioNode, end: number, partials: ReadonlyArray<readonly [number, number]>): void {
  for (const [f, a] of partials) {
    const o = osc(ctx, 'sine', f, 0, end);
    const g = gain(ctx, a);
    o.connect(g).connect(out);
  }
}

export const LOOPS: Record<LoopName, LoopDef> = {
  machine_hum: {
    len: 4, vol: 0.32, rolloff: 1.3, prio: 2,
    build(ctx, out) {
      const end = 4 + X;
      hum(ctx, out, end, [[50, 0.45], [100, 0.35], [100.5, 0.12], [150, 0.14], [200, 0.1], [300, 0.04]]);
      const buzz = osc(ctx, 'sawtooth', 100, 0, end);
      buzz.connect(bq(ctx, 'bandpass', 420, 2)).connect(gain(ctx, 0.08)).connect(out);
      // fan blades
      const fan = noise(ctx, 'pink', 0, end);
      const fg = gain(ctx, 0.05);
      const lfo = osc(ctx, 'sine', 4, 0, end);
      lfo.connect(gain(ctx, 0.03)).connect(fg.gain);
      fan.connect(bq(ctx, 'bandpass', 800, 0.6)).connect(fg).connect(out);
      // mechanical rhythm: clunk every second, tick every half
      for (let t = 0; t < end; t += 1) {
        modal(ctx, out, t + 0.01, [[220, 0.15, 0.22], [610, 0.08, 0.12], [1400, 0.04, 0.05]]);
        thump(ctx, out, t + 0.01, 85, 50, 0.08, 0.2);
        modal(ctx, out, t + 0.51, [[1900, 0.03, 0.05], [3100, 0.02, 0.03]]);
      }
    },
  },
  smelter: {
    len: 4, vol: 0.5, rolloff: 1.2, prio: 2,
    build(ctx, out) {
      const end = 4 + X;
      const roar = noise(ctx, 'brown', 0, end);
      const rg = gain(ctx, 0);
      rg.gain.setValueCurveAtTime(flicker(200, 0.5, 1.0, 0.8), 0, end);
      roar.connect(bq(ctx, 'lowpass', 380, 0.9)).connect(rg).connect(out);
      const mid = noise(ctx, 'pink', 0, end);
      const mg = gain(ctx, 0);
      mg.gain.setValueCurveAtTime(flicker(120, 0.15, 0.35, 0.7), 0, end);
      mid.connect(bq(ctx, 'bandpass', 700, 0.8)).connect(mg).connect(out);
      const hiss = noise(ctx, 'white', 0, end);
      hiss.connect(bq(ctx, 'bandpass', 2600, 0.6)).connect(gain(ctx, 0.06)).connect(out);
      hum(ctx, out, end, [[60, 0.25], [120, 0.12], [180, 0.05]]);
      crackles(ctx, out, 0, end, 30, 1500, 5000, 0.18);
      // gas whoomph
      burst(ctx, out, 1.9, { dur: 0.6, color: 'brown', type: 'lowpass', f: 200, f1: 600, amp: 0.5, attack: 0.25 });
    },
  },
  drill: {
    len: 4, vol: 0.55, rolloff: 1.2, prio: 2,
    build(ctx, out) {
      const end = 4 + X;
      const m = osc(ctx, 'sawtooth', 180, 0, end);
      const wob = osc(ctx, 'sine', 0.5, 0, end);
      wob.connect(gain(ctx, 4)).connect(m.frequency);
      m.connect(bq(ctx, 'lowpass', 1100, 1)).connect(gain(ctx, 0.12)).connect(out);
      const m2 = osc(ctx, 'square', 360, 0, end);
      m2.connect(bq(ctx, 'bandpass', 1400, 4)).connect(gain(ctx, 0.03)).connect(out);
      // rotating grind
      const g = noise(ctx, 'white', 0, end);
      const gg = gain(ctx, 0.35);
      const rot = osc(ctx, 'sine', 4, 0, end);
      rot.connect(gain(ctx, 0.3)).connect(gg.gain);
      const drv = shaper(ctx, 3);
      g.connect(bq(ctx, 'bandpass', 950, 1.5)).connect(drv).connect(gg).connect(out);
      crackles(ctx, out, 0, end, 80, 800, 3500, 0.2);
      // percussive hammer each 0.5 s
      for (let t = 0; t < end; t += 0.5) {
        thump(ctx, out, t, 95, 50, 0.1, 0.6);
        burst(ctx, out, t, { dur: 0.06, type: 'lowpass', f: 700, amp: 0.4 });
      }
      noise(ctx, 'brown', 0, end).connect(bq(ctx, 'lowpass', 150)).connect(gain(ctx, 0.5)).connect(out);
    },
  },
  generator: {
    len: 4, vol: 0.55, rolloff: 1.1, prio: 2,
    build(ctx, out) {
      const end = 4 + X;
      const rate = 12.5;
      const accents = [1, 0.7, 0.85, 0.6];
      let i = 0;
      for (let t = 0; t < end; t += 1 / rate, i++) {
        const tt = t + rand(-0.003, 0.003);
        const a = accents[i % 4] * rand(0.85, 1);
        thump(ctx, out, Math.max(0, tt), 72, 40, 0.06, 0.7 * a);
        burst(ctx, out, Math.max(0, tt), { dur: 0.05, color: 'brown', type: 'lowpass', f: 520, amp: 0.6 * a });
        burst(ctx, out, Math.max(0, tt + 0.005), { dur: 0.03, type: 'bandpass', f: 1800, Q: 2, amp: 0.1 * a });
      }
      hum(ctx, out, end, [[50, 0.18], [100, 0.12], [150, 0.06]]);
      const rattle = noise(ctx, 'white', 0, end);
      const rg = gain(ctx, 0.05);
      rattle.connect(bq(ctx, 'bandpass', 3000, 3)).connect(rg).connect(out);
      crackles(ctx, out, 0, end, 25, 2500, 6000, 0.06);
    },
  },
  campfire: {
    len: 6, vol: 0.5, rolloff: 1.4, prio: 2,
    build(ctx, out) {
      const end = 6 + X;
      const roar = noise(ctx, 'brown', 0, end);
      const rg = gain(ctx, 0);
      rg.gain.setValueCurveAtTime(flicker(240, 0.2, 0.55, 0.85), 0, end);
      roar.connect(bq(ctx, 'lowpass', 520)).connect(rg).connect(out);
      noise(ctx, 'pink', 0, end).connect(bq(ctx, 'bandpass', 1500, 0.7)).connect(gain(ctx, 0.06)).connect(out);
      for (let i = 0; i < 55; i++) {
        const t = rand(0, end - 0.05);
        const big = Math.random() < 0.12;
        burst(ctx, out, t, { dur: rand(0.003, 0.02), type: 'bandpass', f: rand(1500, 6000), Q: 1.2, amp: big ? rand(0.6, 1) : rand(0.08, 0.45) });
        if (big) thump(ctx, out, t, 300, 120, 0.03, 0.25);
      }
    },
  },
  belt: {
    len: 4, vol: 0.32, rolloff: 1.4, prio: 2,
    build(ctx, out) {
      const end = 4 + X;
      hum(ctx, out, end, [[50, 0.1], [100, 0.06]]);
      for (let t = 0; t < end; t += 0.1) {
        const tt = Math.max(0, t + rand(-0.008, 0.008));
        modal(ctx, out, tt, [[rand(1500, 2500), 0.02, rand(0.08, 0.25)], [rand(3500, 4500), 0.01, 0.06]]);
        if (Math.random() < 0.4) burst(ctx, out, tt, { dur: 0.004, type: 'highpass', f: 3000, amp: rand(0.05, 0.2) });
      }
      const rat = noise(ctx, 'white', 0, end);
      const rg = gain(ctx, 0.05);
      const lfo = osc(ctx, 'square', 10, 0, end);
      lfo.connect(gain(ctx, 0.03)).connect(rg.gain);
      rat.connect(bq(ctx, 'bandpass', 3000, 3)).connect(rg).connect(out);
      noise(ctx, 'pink', 0, end).connect(bq(ctx, 'lowpass', 300)).connect(gain(ctx, 0.2)).connect(out);
      // roller thunk every second
      for (let t = 0; t < end; t += 1) thump(ctx, out, t, 110, 70, 0.05, 0.2);
    },
  },
  turbine: {
    len: 4, vol: 0.45, rolloff: 1.1, prio: 2,
    build(ctx, out) {
      const end = 4 + X;
      for (const [f, a] of [[880, 0.06], [1320, 0.03], [1760, 0.02]] as const) {
        const o = osc(ctx, 'sine', f, 0, end);
        const w = osc(ctx, 'sine', 0.5, 0, end);
        w.connect(gain(ctx, f * 0.004)).connect(o.frequency);
        o.connect(gain(ctx, a)).connect(out);
      }
      const air = noise(ctx, 'pink', 0, end);
      const ag = gain(ctx, 0.35);
      const blade = osc(ctx, 'sine', 8, 0, end);
      blade.connect(gain(ctx, 0.12)).connect(ag.gain);
      air.connect(bq(ctx, 'bandpass', 1200, 0.5)).connect(ag).connect(out);
      noise(ctx, 'brown', 0, end).connect(bq(ctx, 'lowpass', 200)).connect(gain(ctx, 0.45)).connect(out);
      hum(ctx, out, end, [[50, 0.1], [100, 0.08]]);
    },
  },
  fence_buzz: {
    len: 3, vol: 0.3, rolloff: 1.6, prio: 3,
    build(ctx, out) {
      const end = 3 + X;
      const s = osc(ctx, 'sawtooth', 100, 0, end);
      const q = osc(ctx, 'square', 50, 0, end);
      const mix = gain(ctx, 0.5);
      s.connect(mix);
      q.connect(gain(ctx, 0.3)).connect(mix);
      mix.connect(shaper(ctx, 4)).connect(bq(ctx, 'bandpass', 1200, 1)).connect(gain(ctx, 0.3)).connect(out);
      hum(ctx, out, end, [[100, 0.2], [200, 0.06]]);
      for (let i = 0; i < 5; i++) {
        const t = rand(0, 3);
        burst(ctx, out, t, { dur: 0.01, type: 'highpass', f: 3000, amp: rand(0.3, 0.7) });
        crackles(ctx, out, t, 0.05, 4, 3000, 8000, 0.3);
      }
    },
  },
};
