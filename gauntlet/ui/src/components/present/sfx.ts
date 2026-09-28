/**
 * Presenter sound effects, synthesised live with WebAudio: no audio files, no
 * dependencies. Off by default; the on/off choice and the master volume are
 * remembered in localStorage (inside try/catch: private windows may block it).
 *
 *   tick      soft click for a reveal step
 *   ding      correct answer / new leader
 *   buzz      wrong answer
 *   whoosh    slide change
 *   drumroll  build-up before the winner
 *   fanfare   the winner
 *
 * Every sound goes through one master gain, kept deliberately quiet so it sits
 * under a voice-over.
 */
import { useSyncExternalStore } from 'react';
import { DEFAULT_VOLUME, parseSfxPrefs } from './sfxPrefs.ts';

export { DEFAULT_VOLUME };

const KEY_ON = 'gauntlet.present.sfx';
const KEY_VOL = 'gauntlet.present.sfxVolume';
/** Output gain at volume 1. */
const MAX_GAIN = 0.32;

function readStore(key: string): string | null {
  try {
    return window.localStorage.getItem(key);
  } catch {
    return null;
  }
}
function writeStore(key: string, v: string): void {
  try {
    window.localStorage.setItem(key, v);
  } catch {
    /* storage blocked: the setting simply isn't remembered */
  }
}


type AnyWindow = Window & { webkitAudioContext?: typeof AudioContext };

let state = typeof window === 'undefined' ? { on: false, volume: DEFAULT_VOLUME } : parseSfxPrefs(readStore(KEY_ON), readStore(KEY_VOL));
let ctx: AudioContext | null = null;
let master: GainNode | null = null;
let noiseBuf: AudioBuffer | null = null;
const listeners = new Set<() => void>();

function emit() {
  for (const l of listeners) l();
}

function audio(): { ac: AudioContext; out: GainNode } | null {
  if (!state.on || typeof window === 'undefined') return null;
  try {
    if (!ctx) {
      const Ctor = window.AudioContext ?? (window as AnyWindow).webkitAudioContext;
      if (!Ctor) return null;
      ctx = new Ctor();
      master = ctx.createGain();
      master.gain.value = state.volume * MAX_GAIN;
      const comp = ctx.createDynamicsCompressor();
      comp.threshold.value = -18;
      comp.ratio.value = 4;
      master.connect(comp).connect(ctx.destination);
    }
    if (ctx.state === 'suspended') void ctx.resume().catch(() => undefined);
    return { ac: ctx, out: master! };
  } catch {
    return null;
  }
}

function noise(ac: AudioContext): AudioBuffer {
  if (noiseBuf && noiseBuf.sampleRate === ac.sampleRate) return noiseBuf;
  const len = Math.floor(ac.sampleRate * 1.5);
  const buf = ac.createBuffer(1, len, ac.sampleRate);
  const d = buf.getChannelData(0);
  let seed = 12345;
  for (let i = 0; i < len; i++) {
    seed = (seed * 1664525 + 1013904223) >>> 0;
    d[i] = (seed / 4294967296) * 2 - 1;
  }
  noiseBuf = buf;
  return buf;
}

/** One enveloped oscillator note. */
function tone(a: { ac: AudioContext; out: AudioNode }, o: { freq: number; to?: number; type?: OscillatorType; at?: number; dur: number; gain: number; attack?: number; lowpass?: number }) {
  const { ac, out } = a;
  const t0 = ac.currentTime + (o.at ?? 0);
  const osc = ac.createOscillator();
  osc.type = o.type ?? 'sine';
  osc.frequency.setValueAtTime(o.freq, t0);
  if (o.to) osc.frequency.exponentialRampToValueAtTime(o.to, t0 + o.dur);
  const g = ac.createGain();
  const atk = o.attack ?? 0.005;
  g.gain.setValueAtTime(0.0001, t0);
  g.gain.exponentialRampToValueAtTime(o.gain, t0 + atk);
  g.gain.exponentialRampToValueAtTime(0.0001, t0 + o.dur);
  let node: AudioNode = osc;
  if (o.lowpass) {
    const f = ac.createBiquadFilter();
    f.type = 'lowpass';
    f.frequency.value = o.lowpass;
    node.connect(f);
    node = f;
  }
  node.connect(g).connect(out);
  osc.start(t0);
  osc.stop(t0 + o.dur + 0.05);
}

/** One burst of filtered noise. */
function hiss(a: { ac: AudioContext; out: AudioNode }, o: { at?: number; dur: number; gain: number; type: BiquadFilterType; freq: number; to?: number; q?: number; attack?: number }) {
  const { ac, out } = a;
  const t0 = ac.currentTime + (o.at ?? 0);
  const src = ac.createBufferSource();
  src.buffer = noise(ac);
  const f = ac.createBiquadFilter();
  f.type = o.type;
  f.Q.value = o.q ?? 1;
  f.frequency.setValueAtTime(o.freq, t0);
  if (o.to) f.frequency.exponentialRampToValueAtTime(o.to, t0 + o.dur);
  const g = ac.createGain();
  const atk = o.attack ?? 0.004;
  g.gain.setValueAtTime(0.0001, t0);
  g.gain.exponentialRampToValueAtTime(o.gain, t0 + atk);
  g.gain.exponentialRampToValueAtTime(0.0001, t0 + o.dur);
  src.connect(f).connect(g).connect(out);
  src.start(t0, Math.random() * 0.5);
  src.stop(t0 + o.dur + 0.05);
}

export const sfx = {
  /** Soft click for a reveal step. */
  tick(at = 0) {
    const a = audio();
    if (!a) return;
    tone(a, { freq: 1900, to: 1400, type: 'triangle', at, dur: 0.06, gain: 0.35 });
  },
  /** Bright two-note "correct" chime. */
  ding(at = 0) {
    const a = audio();
    if (!a) return;
    tone(a, { freq: 1046.5, at, dur: 0.5, gain: 0.35 });
    tone(a, { freq: 1568, at: at + 0.09, dur: 0.75, gain: 0.3 });
    tone(a, { freq: 3136, at: at + 0.09, dur: 0.35, gain: 0.06 });
  },
  /** Low "wrong" buzz. */
  buzz(at = 0) {
    const a = audio();
    if (!a) return;
    tone(a, { freq: 146, to: 110, type: 'sawtooth', at, dur: 0.42, gain: 0.3, lowpass: 900 });
    tone(a, { freq: 155, to: 116, type: 'square', at, dur: 0.42, gain: 0.12, lowpass: 700 });
  },
  /** Airy swoosh for a slide change. */
  whoosh(at = 0) {
    const a = audio();
    if (!a) return;
    hiss(a, { at, dur: 0.38, gain: 0.22, type: 'bandpass', freq: 500, to: 3200, q: 1.4, attack: 0.12 });
  },
  /** Snare roll that swells for `ms` and ends on a hit. */
  drumroll(ms = 2600) {
    const a = audio();
    if (!a) return;
    const dur = Math.max(0.6, ms / 1000);
    const step = 0.045;
    for (let t = 0; t < dur; t += step) {
      const p = t / dur;
      hiss(a, { at: t, dur: 0.05, gain: 0.05 + 0.3 * p * p, type: 'highpass', freq: 1400, q: 0.7, attack: 0.002 });
      if (Math.round(t / step) % 2 === 0) tone(a, { freq: 190, to: 150, at: t, dur: 0.05, gain: 0.04 + 0.12 * p, type: 'triangle' });
    }
    hiss(a, { at: dur, dur: 0.5, gain: 0.35, type: 'highpass', freq: 2500, q: 0.5, attack: 0.003 });
    tone(a, { freq: 90, to: 55, at: dur, dur: 0.35, gain: 0.45 });
  },
  /** Short brass-like fanfare: a rising arpeggio into a held major chord. */
  fanfare(at = 0) {
    const a = audio();
    if (!a) return;
    const notes = [523.25, 659.25, 783.99];
    notes.forEach((f, i) => {
      tone(a, { freq: f, type: 'sawtooth', at: at + i * 0.13, dur: 0.16, gain: 0.16, lowpass: 2400, attack: 0.012 });
      tone(a, { freq: f * 2, type: 'triangle', at: at + i * 0.13, dur: 0.16, gain: 0.06, attack: 0.012 });
    });
    const hold = at + notes.length * 0.13;
    for (const f of [523.25, 659.25, 783.99, 1046.5]) {
      tone(a, { freq: f, type: 'sawtooth', at: hold, dur: 1.5, gain: 0.12, lowpass: 2600, attack: 0.03 });
      tone(a, { freq: f * 1.003, type: 'triangle', at: hold, dur: 1.5, gain: 0.07, attack: 0.03 });
    }
    hiss(a, { at: hold, dur: 1.1, gain: 0.08, type: 'highpass', freq: 6000, q: 0.5, attack: 0.01 });
  },
};

export function sfxOn(): boolean {
  return state.on;
}

export function setSfxOn(on: boolean): void {
  state = { ...state, on };
  writeStore(KEY_ON, on ? '1' : '0');
  if (!on && ctx) void ctx.suspend().catch(() => undefined);
  emit();
  if (on) sfx.ding();
}

export function setSfxVolume(volume: number): void {
  const v = Math.max(0, Math.min(1, volume));
  state = { ...state, volume: v };
  writeStore(KEY_VOL, String(v));
  if (master && ctx) master.gain.setTargetAtTime(v * MAX_GAIN, ctx.currentTime, 0.02);
  emit();
}

function subscribe(fn: () => void): () => void {
  listeners.add(fn);
  return () => listeners.delete(fn);
}
const snapshot = () => state;

/** Live sound settings for React. */
export function useSfx(): { on: boolean; volume: number } {
  return useSyncExternalStore(subscribe, snapshot, snapshot);
}
