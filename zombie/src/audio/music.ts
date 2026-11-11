// Adaptive procedural score in D minor.
//  calm  (0)   : slow gliding detuned pads, sub drone, airy texture, sparse piano/guitar plucks
//  uneasy(0.5) : heartbeat pulse, clock ticks, dissonant tremolo cluster (minor 9th / tritone)
//  combat(1)   : faster tempo, taiko pattern, metallic clanks, spiccato string ostinato, braam on onset

import { clamp, gain, bq, lerp, mtof, noise, osc, pick, rand, smoothstep } from './dsp';

type GetBuf = (name: string) => AudioBuffer | null;

// 4-voice chord voicings (MIDI) and bass roots
const CHORDS: ReadonlyArray<readonly number[]> = [
  [50, 57, 62, 65], // Dm
  [46, 53, 62, 65], // Bb
  [43, 50, 58, 62], // Gm
  [45, 52, 57, 64], // Am
  [50, 57, 60, 65], // Dm7
  [46, 53, 58, 62], // Bb
  [41, 48, 57, 60], // F
  [45, 52, 61, 64], // A (harmonic-minor tension)
];
const ROOTS = [38, 34, 31, 33, 38, 34, 29, 33];
const SCALE = [0, 2, 3, 5, 7, 8, 10];
// combat ostinato (semitones over root, 16ths)
const OSTINATO = [0, 12, 0, 3, 0, 12, 0, 1, 0, 12, 0, 3, 0, 7, 6, 1];
const OST_VEL = [1, 0.5, 0.7, 0.6, 0.9, 0.5, 0.7, 0.6, 1, 0.5, 0.7, 0.6, 0.9, 0.7, 0.7, 0.8];
// taiko velocities per 16th (0 = rest)
const TAIKO = [1, 0, 0, 0.6, 0, 0, 0.8, 0, 1, 0, 0.5, 0, 0, 0.7, 0.8, 0.9];
const TAIKO_HI = [0, 0, 0.5, 0, 0, 0.4, 0, 0, 0, 0, 0, 0.5, 0, 0, 0, 0];

export class Music {
  private ctx: AudioContext;
  private getBuf: GetBuf;
  private out: GainNode;
  private hall: AudioNode;

  private target = 0;
  private level = 0;

  private calm: GainNode;
  private unease: GainNode;
  private combat: GainNode;
  private noteBus: GainNode;

  private padGain: GainNode;
  private padFilter: BiquadFilterNode;
  private padOsc: OscillatorNode[][] = [];
  private sub: OscillatorNode;
  private clusterOsc: OscillatorNode[] = [];
  private clusterGain: GainNode;
  private droneOsc: OscillatorNode;
  private texture: GainNode;

  private step = 0;
  private bar = 0;
  private chord = 0;
  private nextTime = 0;
  private phrase = 0;
  private lastNote = 62;
  private instr: 'm_piano' | 'm_pluck' = 'm_piano';
  private lastBraam = -100;
  private prevLevel = 0;

  constructor(ctx: AudioContext, out: AudioNode, hall: AudioNode, getBuf: GetBuf) {
    this.ctx = ctx;
    this.getBuf = getBuf;
    this.hall = hall;
    const t = ctx.currentTime;
    this.out = gain(ctx, 1);
    this.out.connect(out);

    this.calm = gain(ctx, 1);
    this.unease = gain(ctx, 0);
    this.combat = gain(ctx, 0);
    for (const g of [this.calm, this.unease, this.combat]) g.connect(this.out);
    const hallSend = gain(ctx, 0.5);
    this.out.connect(hallSend).connect(hall);

    this.noteBus = gain(ctx, 1);
    this.noteBus.connect(this.out);
    const noteHall = gain(ctx, 0.9);
    this.noteBus.connect(noteHall).connect(hall);

    // --- pads -------------------------------------------------------------
    this.padFilter = bq(ctx, 'lowpass', 650, 0.9);
    this.padGain = gain(ctx, 0);
    this.padGain.gain.setTargetAtTime(0.11, t, 3);
    this.padFilter.connect(this.padGain).connect(this.out);
    const padLfo = osc(ctx, 'sine', 0.05, t);
    padLfo.connect(gain(ctx, 280)).connect(this.padFilter.frequency);
    const chord = CHORDS[0];
    for (let v = 0; v < 4; v++) {
      const pair: OscillatorNode[] = [];
      for (const det of [-8, 7]) {
        const o = osc(ctx, 'sawtooth', mtof(chord[v]), t);
        o.detune.value = det + rand(-2, 2);
        const vg = gain(ctx, v === 0 ? 0.9 : 0.6);
        o.connect(vg).connect(this.padFilter);
        pair.push(o);
      }
      this.padOsc.push(pair);
    }
    this.sub = osc(ctx, 'sine', mtof(ROOTS[0]), t);
    this.sub.connect(gain(ctx, 0.12)).connect(this.calm);

    // airy texture (wind-like, slowly sweeping)
    this.texture = gain(ctx, 0.025);
    const tx = noise(ctx, 'pink', t);
    const txf = bq(ctx, 'bandpass', 500, 4);
    const txl = osc(ctx, 'sine', 0.03, t);
    txl.connect(gain(ctx, 300)).connect(txf.frequency);
    tx.connect(txf).connect(this.texture).connect(this.calm);

    // --- unease: dissonant tremolo cluster + low bowed drone ---------------
    this.clusterGain = gain(ctx, 0.05);
    const trem = gain(ctx, 0.6);
    const tremLfo = osc(ctx, 'sine', 5.5, t);
    tremLfo.connect(gain(ctx, 0.4)).connect(trem.gain);
    const clLp = bq(ctx, 'lowpass', 2200, 0.7);
    for (const iv of [13, 18, 24]) {
      const o = osc(ctx, 'triangle', mtof(ROOTS[0] + 24 + iv), t);
      o.detune.value = rand(-6, 6);
      o.connect(clLp);
      this.clusterOsc.push(o);
    }
    clLp.connect(trem).connect(this.clusterGain).connect(this.unease);
    this.droneOsc = osc(ctx, 'sawtooth', mtof(ROOTS[0]), t);
    const dlp = bq(ctx, 'lowpass', 260, 1.2);
    const dTrem = gain(ctx, 0.07);
    const dl = osc(ctx, 'sine', 3, t);
    dl.connect(gain(ctx, 0.03)).connect(dTrem.gain);
    this.droneOsc.connect(dlp).connect(dTrem).connect(this.unease);

    this.nextTime = t + 0.5;
  }

  setTarget(x: number): void {
    this.target = clamp(Number.isFinite(x) ? x : 0, 0, 1);
  }

  update(dt: number): void {
    const ctx = this.ctx;
    const now = ctx.currentTime;
    // asymmetric smoothing: swell in quickly, relax slowly
    const tc = this.target > this.level ? 1.3 : 6;
    this.level += (this.target - this.level) * clamp(dt / tc, 0, 1);
    const i = this.level;

    const calmLv = 1 - smoothstep(0.25, 0.75, i);
    const uneaseLv = smoothstep(0.12, 0.5, i) * (1 - 0.55 * smoothstep(0.7, 1, i));
    const combatLv = smoothstep(0.6, 0.95, i);
    this.calm.gain.setTargetAtTime(0.25 + 0.75 * calmLv, now, 0.5);
    this.unease.gain.setTargetAtTime(uneaseLv, now, 0.5);
    this.combat.gain.setTargetAtTime(combatLv, now, 0.3);
    this.padGain.gain.setTargetAtTime(0.11 * (1 - 0.5 * i), now, 0.8);
    this.padFilter.frequency.setTargetAtTime(lerp(650, 380, i), now, 1);

    // braam on combat onset
    if (i > 0.85 && this.prevLevel <= 0.85 && now - this.lastBraam > 20) {
      this.lastBraam = now;
      this.playBuf('m_braam', now + 0.05, 1, 0.6, this.combat);
    }
    this.prevLevel = i;

    // sequencer
    if (this.nextTime < now) this.nextTime = now + 0.05;
    const bpm = lerp(62, 128, smoothstep(0.3, 0.9, i));
    const stepDur = 60 / bpm / 4;
    let guard = 0;
    while (this.nextTime < now + 0.3 && guard++ < 32) {
      this.playStep(this.step, this.nextTime, calmLv, uneaseLv, combatLv);
      this.nextTime += stepDur;
      this.step++;
      if (this.step >= 16) {
        this.step = 0;
        this.bar++;
        const barsPerChord = i > 0.7 ? 2 : 4;
        if (this.bar % barsPerChord === 0) this.nextChord(this.nextTime, i);
      }
    }
  }

  private nextChord(t: number, i: number): void {
    // weighted random walk through the progression
    this.chord = Math.random() < 0.75 ? (this.chord + 1) % CHORDS.length : Math.floor(Math.random() * CHORDS.length);
    const ch = CHORDS[this.chord];
    const glide = i > 0.6 ? 0.25 : 1.6;
    for (let v = 0; v < 4; v++) {
      for (const o of this.padOsc[v]) o.frequency.setTargetAtTime(mtof(ch[v]), t, glide);
    }
    const root = ROOTS[this.chord];
    this.sub.frequency.setTargetAtTime(mtof(root), t, glide);
    this.droneOsc.frequency.setTargetAtTime(mtof(root), t, glide * 0.5);
    [13, 18, 24].forEach((iv, k) => this.clusterOsc[k].frequency.setTargetAtTime(mtof(root + 24 + iv + (k === 2 && Math.random() < 0.5 ? 1 : 0)), t, 0.8));
    // low piano bass note on chord change when calm
    if (i < 0.45 && Math.random() < 0.6) this.playNote(root + 12, t, 0.22 * (1 - i), 'm_piano');
  }

  private playStep(step: number, t: number, calmLv: number, uneaseLv: number, combatLv: number): void {
    const beat = step % 4 === 0;
    // calm: sparse melancholic plucks
    if (beat && calmLv > 0.05) {
      if (this.phrase > 0) {
        this.phrase--;
        if (Math.random() < 0.8) this.melodyNote(t, calmLv);
      } else if (Math.random() < 0.07 * calmLv) {
        this.phrase = Math.floor(rand(2, 6));
        this.instr = Math.random() < 0.55 ? 'm_piano' : 'm_pluck';
        this.melodyNote(t, calmLv);
      }
    } else if (!beat && step % 2 === 0 && this.phrase > 0 && Math.random() < 0.15 * calmLv) {
      this.melodyNote(t, calmLv * 0.7);
    }

    // unease: heartbeat pulse + ticks
    if (uneaseLv > 0.05) {
      if (beat && combatLv < 0.6) {
        this.playBuf('m_kick', t, 1, 0.55, this.unease);
        this.playBuf('m_kick', t + 0.18, 0.95, 0.32, this.unease);
      }
      if (step % 2 === 0) this.playBuf('m_tick', t, step % 4 === 0 ? 1 : 1.12, (step % 4 === 0 ? 0.12 : 0.07), this.unease);
    }

    // combat
    if (combatLv > 0.03) {
      const tv = TAIKO[step];
      if (tv > 0) this.playBuf('m_taiko', t, 1 + rand(-0.02, 0.02), 0.75 * tv, this.combat);
      const th = TAIKO_HI[step];
      if (th > 0) this.playBuf('m_taiko', t, 1.6, 0.4 * th, this.combat);
      if (step === 4 || step === 12) this.playBuf('m_clank', t, 1 + rand(-0.03, 0.03), 0.3, this.combat);
      const root = ROOTS[this.chord] + 12;
      const m = root + OSTINATO[step];
      this.playBuf('m_string', t, Math.pow(2, (m - 50) / 12), 0.35 * OST_VEL[step], this.combat);
    }
  }

  private melodyNote(t: number, lv: number): void {
    const ch = CHORDS[this.chord];
    // candidate notes: scale tones of D aeolian from D4..A5, favour chord tones and small steps
    const cands: number[] = [];
    for (let m = 62; m <= 81; m++) {
      if (!SCALE.includes((m - 62 + 120) % 12)) continue;
      const dist = Math.abs(m - this.lastNote);
      if (dist > 7) continue;
      const chordTone = ch.some((c) => (c - m) % 12 === 0);
      const w = chordTone ? 3 : 1;
      for (let k = 0; k < w; k++) cands.push(m);
    }
    const m = cands.length ? pick(cands) : 69;
    this.lastNote = m;
    this.playNote(m, t, rand(0.18, 0.34) * lv, this.instr);
  }

  private playNote(m: number, t: number, vol: number, instr: 'm_piano' | 'm_pluck'): void {
    const buf = this.getBuf(instr);
    if (!buf) return;
    const src = this.ctx.createBufferSource();
    src.buffer = buf;
    src.playbackRate.value = Math.pow(2, (m - 62) / 12);
    const g = gain(this.ctx, vol);
    const p = this.ctx.createStereoPanner();
    p.pan.value = rand(-0.5, 0.5);
    src.connect(g).connect(p).connect(this.noteBus);
    src.start(t);
    src.onended = (): void => { src.disconnect(); p.disconnect(); };
  }

  private playBuf(name: string, t: number, rate: number, vol: number, dest: AudioNode): void {
    if (vol < 0.003) return;
    const buf = this.getBuf(name);
    if (!buf) return;
    const src = this.ctx.createBufferSource();
    src.buffer = buf;
    src.playbackRate.value = rate;
    const g = gain(this.ctx, vol);
    src.connect(g).connect(dest);
    src.start(t);
    src.onended = (): void => { src.disconnect(); g.disconnect(); };
  }
}
