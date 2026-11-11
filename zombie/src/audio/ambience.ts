// Continuous ambience beds (wind, rain, insects, room tone, base hum) plus
// occasional one-shots (birds, owls, dog howls, distant unsettling events).

import { bq, clamp, gain, genCrickets, noise, osc, pick, rand } from './dsp';

type GetBuf = (name: string, variant?: number) => AudioBuffer | null;

export interface AmbienceState { wind: number; rain: number; night: number; indoor: boolean; insideBase?: boolean }

export class Ambience {
  private ctx: AudioContext;
  private getBuf: GetBuf;
  private reverb: AudioNode;
  private state: AmbienceState = { wind: 0.3, rain: 0, night: 0, indoor: false, insideBase: false };

  private outdoorFilter: BiquadFilterNode; // muffles outside sounds when indoors
  private windGain: GainNode;
  private windLow: GainNode;
  private windHi: GainNode;
  private whistle: BiquadFilterNode;
  private rainGain: GainNode;
  private rainFilter: BiquadFilterNode;
  private roofGain: GainNode;
  private insectGain: GainNode;
  private roomGain: GainNode;
  private baseGain: GainNode;
  private oneShots: GainNode;

  private nextGust = 0;
  private nextDrop = 0;
  private nextBird = 0;
  private nextNight = 0;
  private nextUnsettling = 0;

  constructor(ctx: AudioContext, out: AudioNode, reverb: AudioNode, getBuf: GetBuf) {
    this.ctx = ctx;
    this.getBuf = getBuf;
    this.reverb = reverb;
    const t = ctx.currentTime;

    this.outdoorFilter = bq(ctx, 'lowpass', 18000, 0.5);
    this.outdoorFilter.connect(out);

    // wind: low rumble + whistling band
    this.windGain = gain(ctx, 0);
    this.windGain.connect(this.outdoorFilter);
    this.windLow = gain(ctx, 0.8);
    noise(ctx, 'brown', t).connect(bq(ctx, 'lowpass', 380, 0.7)).connect(this.windLow).connect(this.windGain);
    this.windHi = gain(ctx, 0.25);
    this.whistle = bq(ctx, 'bandpass', 900, 3.5);
    noise(ctx, 'pink', t).connect(this.whistle).connect(this.windHi).connect(this.windGain);
    const hiss = gain(ctx, 0.12);
    noise(ctx, 'white', t).connect(bq(ctx, 'bandpass', 2500, 0.6)).connect(hiss).connect(this.windGain);

    // rain: hiss + wash + roof drumming; droplets added separately
    this.rainFilter = bq(ctx, 'lowpass', 9000, 0.5);
    this.rainGain = gain(ctx, 0);
    this.rainFilter.connect(this.rainGain).connect(out);
    noise(ctx, 'white', t).connect(bq(ctx, 'highpass', 500, 0.6)).connect(bq(ctx, 'lowpass', 7000, 0.5)).connect(gain(ctx, 0.35)).connect(this.rainFilter);
    noise(ctx, 'pink', t).connect(bq(ctx, 'bandpass', 1200, 0.5)).connect(gain(ctx, 0.5)).connect(this.rainFilter);
    this.roofGain = gain(ctx, 0);
    noise(ctx, 'brown', t).connect(bq(ctx, 'lowpass', 320, 0.7)).connect(this.roofGain).connect(out);

    // insects: two crickets loops of different lengths (long combined period)
    this.insectGain = gain(ctx, 0);
    this.insectGain.connect(this.outdoorFilter);
    for (const [len, n, g] of [[6, 10, 0.7], [7.3, 8, 0.5]] as const) {
      const src = ctx.createBufferSource();
      src.buffer = genCrickets(ctx.sampleRate, len, n);
      src.loop = true;
      const lg = gain(ctx, g);
      src.connect(lg).connect(this.insectGain);
      src.start(t, Math.random() * len);
    }

    // indoor room tone
    this.roomGain = gain(ctx, 0);
    noise(ctx, 'brown', t).connect(bq(ctx, 'lowpass', 180, 0.7)).connect(this.roomGain).connect(out);

    // base: electrical hum + ventilation
    this.baseGain = gain(ctx, 0);
    this.baseGain.connect(out);
    for (const [f, a] of [[50, 0.25], [100, 0.18], [150, 0.06], [200, 0.04]] as const) {
      osc(ctx, 'sine', f, t).connect(gain(ctx, a)).connect(this.baseGain);
    }
    noise(ctx, 'pink', t).connect(bq(ctx, 'bandpass', 600, 0.7)).connect(gain(ctx, 0.12)).connect(this.baseGain);

    this.oneShots = gain(ctx, 1);
    this.oneShots.connect(this.outdoorFilter);
    const send = gain(ctx, 0.7);
    this.oneShots.connect(send).connect(reverb);

    this.nextBird = t + rand(4, 12);
    this.nextNight = t + rand(10, 25);
    this.nextUnsettling = t + rand(40, 120);
  }

  set(a: AmbienceState): void {
    const f = (x: number): number => clamp(Number.isFinite(x) ? x : 0, 0, 1);
    this.state = { wind: f(a.wind), rain: f(a.rain), night: f(a.night), indoor: !!a.indoor, insideBase: !!a.insideBase };
  }

  update(): void {
    const ctx = this.ctx;
    const now = ctx.currentTime;
    const s = this.state;
    const ind = s.indoor;
    const tc = 1.2;

    this.outdoorFilter.frequency.setTargetAtTime(ind ? 650 : 18000, now, 0.4);
    // wind gusts
    if (now >= this.nextGust) {
      const gust = s.wind * rand(0.35, 1);
      const dur = rand(0.8, 2.5);
      this.windGain.gain.setTargetAtTime(gust * (ind ? 0.35 : 0.7), now, dur);
      this.whistle.frequency.setTargetAtTime(rand(550, 1400) * (0.7 + s.wind * 0.6), now, dur);
      this.windHi.gain.setTargetAtTime(0.1 + 0.35 * s.wind * s.wind, now, dur);
      this.nextGust = now + rand(1.5, 5);
    }
    // rain
    this.rainGain.gain.setTargetAtTime(s.rain * (ind ? 0.22 : 0.32), now, tc);
    this.rainFilter.frequency.setTargetAtTime(ind ? 700 : 9000, now, 0.4);
    this.roofGain.gain.setTargetAtTime(ind ? s.rain * 0.45 : 0, now, tc);
    this.scheduleDrops(now, s);
    // insects: at night, fewer in rain/wind
    this.insectGain.gain.setTargetAtTime(s.night * (1 - s.rain * 0.7) * (1 - s.wind * 0.4) * 0.5, now, 2);
    this.roomGain.gain.setTargetAtTime(ind ? 0.25 : 0, now, tc);
    this.baseGain.gain.setTargetAtTime(s.insideBase ? 0.12 : 0, now, tc);

    // occasional one-shots
    if (now >= this.nextBird) {
      const day = 1 - s.night;
      if (day > 0.4 && s.rain < 0.5) this.oneShot('amb_bird', 0.1 * day * (1 - s.rain), 0.25);
      this.nextBird = now + rand(6, 22);
    }
    if (now >= this.nextNight) {
      if (s.night > 0.4) this.oneShot(Math.random() < 0.6 ? 'amb_owl' : 'amb_dog', rand(0.12, 0.22) * s.night, 0.5);
      this.nextNight = now + rand(20, 60) / Math.max(0.3, s.night);
    }
    if (now >= this.nextUnsettling) {
      const name = pick(['amb_scream', 'amb_gunshot', 'amb_creak', 'amb_horde'] as const);
      this.oneShot(name, rand(0.12, 0.2) * (s.insideBase ? 0.6 : 1), 0.8);
      this.nextUnsettling = now + rand(40, 120);
    }
  }

  private scheduleDrops(now: number, s: AmbienceState): void {
    if (s.rain < 0.05) {
      this.nextDrop = now;
      return;
    }
    if (this.nextDrop < now) this.nextDrop = now;
    const rate = s.rain * 14;
    let guard = 0;
    while (this.nextDrop < now + 0.3 && guard++ < 12) {
      const buf = this.getBuf('amb_drop');
      if (!buf) break;
      const src = this.ctx.createBufferSource();
      src.buffer = buf;
      src.playbackRate.value = rand(0.7, 1.4);
      const g = gain(this.ctx, rand(0.05, 0.25) * s.rain);
      const p = this.ctx.createStereoPanner();
      p.pan.value = rand(-1, 1);
      src.connect(g).connect(p).connect(this.rainFilter);
      src.start(this.nextDrop);
      src.onended = (): void => { src.disconnect(); p.disconnect(); };
      this.nextDrop += -Math.log(1 - Math.random() * 0.999) / rate;
    }
  }

  private oneShot(name: string, vol: number, spread: number): void {
    const buf = this.getBuf(name);
    if (!buf) return;
    const ctx = this.ctx;
    const src = ctx.createBufferSource();
    src.buffer = buf;
    src.playbackRate.value = rand(0.92, 1.06);
    const g = gain(ctx, vol);
    const p = ctx.createStereoPanner();
    p.pan.value = rand(-spread, spread);
    src.connect(g).connect(p).connect(this.oneShots);
    src.start();
    src.onended = (): void => { src.disconnect(); p.disconnect(); };
  }
}
