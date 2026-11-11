// REKINDLE procedural audio engine.
// Everything is synthesised with the Web Audio API: one-shots are pre-rendered
// (several variations each) through OfflineAudioContext in the background after
// init(); loops are pre-rendered seamless buffers; music and ambience are live
// graphs driven by a small scheduler. No audio files, no network.

import * as THREE from 'three';
import { SFX, INTERNAL, type SfxName, type BufName, type SfxDef, type InternalDef } from './sfx';
import { LOOPS, type LoopName, type LoopDef } from './loops';
import { Music } from './music';
import { Ambience } from './ambience';
import { bq, clamp, finalize, gain, genPiano, genPluck, makeImpulse, makeLoopable, osc, smoothstep } from './dsp';

export type { SfxName } from './sfx';
export type { LoopName } from './loops';

export interface PlayOpts {
  pos?: THREE.Vector3;
  volume?: number;
  /** playback-rate multiplier; random pitch jitter (~±6%) is applied on top by default */
  pitch?: number;
}

export interface AmbienceOpts { wind: number; rain: number; night: number; indoor: boolean; insideBase?: boolean }
export interface VolumeOpts { master?: number; sfx?: number; music?: number; ambience?: number }

interface Vec { x: number; y: number; z: number }

interface Voice {
  src: AudioBufferSourceNode;
  g: GainNode;
  name: SfxName;
  est: number;
  start: number;
  dead: boolean;
}

interface LoopActive { src: AudioBufferSourceNode; g: GainNode; panner: PannerNode | null }
interface LoopState { id: string; name: LoopName; pos: Vec | null; volume: number; active: LoopActive | null }

type Job = { kind: 'sfx'; name: SfxName; v: number; prio: number }
  | { kind: 'int'; name: string; v: number; prio: number }
  | { kind: 'loop'; name: LoopName; v: number; prio: number };

const MAX_VOICES = 32;
const LOOP_CULL = 62;
const LOOP_RESUME = 56;
const MAX_LOOPS = 16;
const RENDER_WORKERS = 3;
const RATE_WINDOW = 0.1;

function finite(n: number | undefined, d: number): number {
  return typeof n === 'number' && Number.isFinite(n) ? n : d;
}

function normalizePeak(buf: AudioBuffer, peak: number): AudioBuffer {
  let m = 0;
  for (let c = 0; c < buf.numberOfChannels; c++) {
    const d = buf.getChannelData(c);
    for (let i = 0; i < d.length; i++) m = Math.max(m, Math.abs(d[i]));
  }
  if (m > 1e-6) {
    const k = peak / m;
    for (let c = 0; c < buf.numberOfChannels; c++) {
      const d = buf.getChannelData(c);
      for (let i = 0; i < d.length; i++) d[i] *= k;
    }
  }
  return buf;
}

export class AudioEngine {
  private ctx: AudioContext | null = null;
  private _ready = false;
  private initPromise: Promise<void> | null = null;
  private userSuspended = false;
  private timer: ReturnType<typeof setInterval> | null = null;
  private tickCount = 0;
  private lastTick = 0;

  // buses
  private masterOut: GainNode | null = null;
  private sfxBus: GainNode | null = null;
  private musicBus: GainNode | null = null;
  private ambBus: GainNode | null = null;
  private sfxReverb: GainNode | null = null;
  private ambRev: GainNode | null = null;
  private hallIn: GainNode | null = null;
  private outdoorWet: GainNode | null = null;
  private indoorWet: GainNode | null = null;
  private lhFilter: BiquadFilterNode | null = null;
  private postIn: AudioNode | null = null;
  private tinnitus: GainNode | null = null;

  private music: Music | null = null;
  private ambience: Ambience | null = null;

  // buffers & rendering
  private buffers = new Map<string, AudioBuffer[]>();
  private queue: Job[] = [];
  private lastVariant = new Map<string, number>();

  // runtime state (kept even before init so it applies once ready)
  private voices: Voice[] = [];
  private recent = new Map<string, number[]>();
  private loops = new Map<string, LoopState>();
  private listener: Vec = { x: 0, y: 0, z: 0 };
  private vol = { master: 0.8, sfx: 1, music: 0.4, ambience: 0.7 };
  private amb: AmbienceOpts = { wind: 0.3, rain: 0, night: 0, indoor: false, insideBase: false };
  private musicTarget = 0;
  private lowHealthTarget = 0;
  private lowHealth = 0;
  private nextBeat = 0;

  get ready(): boolean {
    return this._ready;
  }

  // -------------------------------------------------------------------------
  // lifecycle
  // -------------------------------------------------------------------------
  init(): Promise<void> {
    if (this.initPromise) {
      this.tryResume();
      return this.initPromise;
    }
    this.initPromise = this.doInit().catch((e: unknown) => {
      console.warn('[audio] init failed', e);
    });
    return this.initPromise;
  }

  suspend(): void {
    this.userSuspended = true;
    try {
      const ctx = this.ctx;
      if (ctx && ctx.state !== 'closed') {
        ctx.suspend().then(() => {
          // a resume() may have arrived while the suspend was in flight
          if (!this.userSuspended) this.tryResume();
        }).catch(() => undefined);
      }
    } catch { /* ignore */ }
  }

  resume(): void {
    this.userSuspended = false;
    this.tryResume();
  }

  private tryResume(): void {
    try {
      const ctx = this.ctx;
      if (ctx && ctx.state !== 'closed' && !this.userSuspended) ctx.resume().catch(() => undefined);
    } catch { /* ignore */ }
  }

  private async doInit(): Promise<void> {
    if (typeof window === 'undefined') return;
    const w = window as unknown as { AudioContext?: typeof AudioContext; webkitAudioContext?: typeof AudioContext };
    const AC = w.AudioContext ?? w.webkitAudioContext;
    if (!AC) return;
    const ctx = new AC({ latencyHint: 'interactive' });
    this.ctx = ctx;
    const sr = ctx.sampleRate;

    // master chain: buses → masterIn → low-health LPF → compressor → limiter → master volume
    const masterIn = gain(ctx, 1);
    this.lhFilter = bq(ctx, 'lowpass', 20000, 0.6);
    const comp = ctx.createDynamicsCompressor();
    comp.threshold.value = -16;
    comp.knee.value = 8;
    comp.ratio.value = 3.5;
    comp.attack.value = 0.004;
    comp.release.value = 0.2;
    const limiter = ctx.createDynamicsCompressor();
    limiter.threshold.value = -2;
    limiter.knee.value = 0;
    limiter.ratio.value = 20;
    limiter.attack.value = 0.001;
    limiter.release.value = 0.08;
    this.masterOut = gain(ctx, this.vol.master);
    masterIn.connect(this.lhFilter).connect(comp).connect(limiter).connect(this.masterOut).connect(ctx.destination);
    this.postIn = comp;

    this.sfxBus = gain(ctx, this.vol.sfx);
    this.musicBus = gain(ctx, this.vol.music);
    this.ambBus = gain(ctx, this.vol.ambience);
    this.sfxBus.connect(masterIn);
    this.musicBus.connect(masterIn);
    this.ambBus.connect(masterIn);

    // reverbs (generated impulses)
    const outdoor = ctx.createConvolver();
    outdoor.buffer = makeImpulse(sr, 2.4, { lpStart: 6000, lpEnd: 900, predelay: 0.02, early: [[0.045, 0.5], [0.09, 0.3], [0.21, 0.25], [0.37, 0.15]] });
    const indoor = ctx.createConvolver();
    indoor.buffer = makeImpulse(sr, 0.85, { lpStart: 7000, lpEnd: 2000, predelay: 0.004, early: [[0.008, 0.6], [0.013, 0.5], [0.021, 0.4], [0.03, 0.3]] });
    const hall = ctx.createConvolver();
    hall.buffer = makeImpulse(sr, 3.6, { lpStart: 5000, lpEnd: 700, predelay: 0.03 });
    this.sfxReverb = gain(ctx, 1);
    this.outdoorWet = gain(ctx, 1);
    this.indoorWet = gain(ctx, 0);
    const revReturn = gain(ctx, 0.55);
    this.sfxReverb.connect(this.outdoorWet).connect(outdoor).connect(revReturn);
    this.sfxReverb.connect(this.indoorWet).connect(indoor).connect(revReturn);
    revReturn.connect(masterIn);
    this.hallIn = gain(ctx, 1);
    const hallReturn = gain(ctx, 0.6);
    this.hallIn.connect(hall).connect(hallReturn).connect(this.musicBus);

    // ambience reverb send goes into the sfx reverb (scaled by ambience volume)
    const ambRevNode = gain(ctx, this.vol.ambience);
    ambRevNode.connect(this.sfxReverb);
    this.ambRev = ambRevNode;

    // low-health tinnitus
    this.tinnitus = gain(ctx, 0);
    osc(ctx, 'sine', 3700, ctx.currentTime).connect(this.tinnitus).connect(this.postIn);

    // instrument samples for the score (pure JS, fast)
    this.buffers.set('m_piano', [genPiano(sr, 293.66, 3.2)]);
    this.buffers.set('m_pluck', [genPluck(sr, 293.66, 2.6)]);

    const getBuf = (name: string): AudioBuffer | null => this.getBuffer(name);
    this.music = new Music(ctx, this.musicBus, this.hallIn, getBuf);
    this.music.setTarget(this.musicTarget);
    this.ambience = new Ambience(ctx, this.ambBus, ambRevNode, getBuf);
    this.ambience.set(this.amb);
    this.applyIndoor();

    // resume on gesture (autoplay policies)
    const kick = (): void => {
      if (ctx.state !== 'running') this.tryResume();
    };
    for (const ev of ['pointerdown', 'keydown', 'touchstart', 'mousedown']) {
      window.addEventListener(ev, kick, { passive: true, capture: true });
    }
    if (ctx.state !== 'running' && !this.userSuspended) {
      await Promise.race([ctx.resume().catch(() => undefined), new Promise<void>((r) => setTimeout(r, 300))]);
    }
    if (this.userSuspended) ctx.suspend().catch(() => undefined);

    this.lastTick = ctx.currentTime;
    this.timer = setInterval(this.tick, 50);
    this._ready = true;

    // background rendering; wait briefly for the most essential sounds
    this.buildQueue();
    const essentials = this.queue.filter((j) => j.prio < 1 && j.v === 0).length;
    const first = this.runRender(essentials);
    for (let i = 0; i < RENDER_WORKERS; i++) void this.runRender(Infinity);
    await Promise.race([first, new Promise<void>((r) => setTimeout(r, 900))]);
  }

  // -------------------------------------------------------------------------
  // rendering
  // -------------------------------------------------------------------------
  private buildQueue(): void {
    const jobs: Job[] = [];
    for (const name of Object.keys(SFX) as SfxName[]) {
      const def = SFX[name];
      for (let v = 0; v < def.vars; v++) jobs.push({ kind: 'sfx', name, v, prio: v === 0 ? def.prio : 10 + def.prio + v });
    }
    for (const name of Object.keys(INTERNAL) as Array<keyof typeof INTERNAL>) {
      const def = INTERNAL[name];
      for (let v = 0; v < def.vars; v++) jobs.push({ kind: 'int', name, v, prio: v === 0 ? def.prio : 10 + def.prio + v });
    }
    for (const name of Object.keys(LOOPS) as LoopName[]) jobs.push({ kind: 'loop', name, v: 0, prio: LOOPS[name].prio + 0.5 });
    jobs.sort((a, b) => a.prio - b.prio);
    this.queue = jobs;
  }

  private bump(key: string): void {
    const idx = this.queue.findIndex((j) => (j.kind === 'loop' ? 'loop:' + j.name : j.name) === key);
    if (idx > 0) {
      const [j] = this.queue.splice(idx, 1);
      this.queue.unshift(j);
    }
  }

  private async runRender(maxJobs: number): Promise<void> {
    let n = 0;
    while (n < maxJobs) {
      const job = this.queue.shift();
      if (!job) return;
      n++;
      try {
        await this.renderJob(job);
      } catch (e) {
        console.warn('[audio] render failed', job.name, e);
      }
    }
  }

  private async renderJob(job: Job): Promise<void> {
    const ctx = this.ctx;
    if (!ctx) return;
    const sr = ctx.sampleRate;
    if (job.kind === 'loop') {
      const def: LoopDef = LOOPS[job.name];
      const off = new OfflineAudioContext(1, Math.ceil((def.len + 0.3) * sr), sr);
      const out = off.createGain();
      out.connect(off.destination);
      def.build(off, out, 0);
      const raw = await off.startRendering();
      this.buffers.set('loop:' + job.name, [normalizePeak(makeLoopable(raw, def.len), 0.85)]);
      return;
    }
    const def: SfxDef | InternalDef = job.kind === 'sfx' ? SFX[job.name] : INTERNAL[job.name as keyof typeof INTERNAL];
    const off = new OfflineAudioContext(def.stereo ? 2 : 1, Math.ceil(def.dur * sr), sr);
    const out = off.createGain();
    out.connect(off.destination);
    def.build(off, out, job.v);
    const raw = await off.startRendering();
    const buf = finalize(raw, 0.89);
    const list = this.buffers.get(job.name);
    if (list) list.push(buf);
    else this.buffers.set(job.name, [buf]);
  }

  private getBuffer(name: string): AudioBuffer | null {
    const list = this.buffers.get(name);
    if (!list || list.length === 0) {
      this.bump(name);
      return null;
    }
    if (list.length === 1) return list[0];
    const last = this.lastVariant.get(name) ?? -1;
    let i = Math.floor(Math.random() * list.length);
    if (i === last) i = (i + 1) % list.length;
    this.lastVariant.set(name, i);
    return list[i];
  }

  // -------------------------------------------------------------------------
  // listener
  // -------------------------------------------------------------------------
  setListener(pos: THREE.Vector3, forward: THREE.Vector3, up: THREE.Vector3): void {
    try {
      if (!pos || !Number.isFinite(pos.x) || !Number.isFinite(pos.y) || !Number.isFinite(pos.z)) return;
      this.listener.x = pos.x;
      this.listener.y = pos.y;
      this.listener.z = pos.z;
      const ctx = this.ctx;
      if (!ctx || !this._ready) return;
      const l = ctx.listener;
      const fx = finite(forward?.x, 0), fy = finite(forward?.y, 0), fz = finite(forward?.z, -1);
      const ux = finite(up?.x, 0), uy = finite(up?.y, 1), uz = finite(up?.z, 0);
      if (l.positionX) {
        l.positionX.value = pos.x;
        l.positionY.value = pos.y;
        l.positionZ.value = pos.z;
        l.forwardX.value = fx;
        l.forwardY.value = fy;
        l.forwardZ.value = fz;
        l.upX.value = ux;
        l.upY.value = uy;
        l.upZ.value = uz;
      } else {
        l.setPosition(pos.x, pos.y, pos.z);
        l.setOrientation(fx, fy, fz, ux, uy, uz);
      }
    } catch { /* ignore */ }
  }

  private dist(p: Vec): number {
    const dx = p.x - this.listener.x, dy = p.y - this.listener.y, dz = p.z - this.listener.z;
    return Math.sqrt(dx * dx + dy * dy + dz * dz);
  }

  private makePanner(ctx: AudioContext, p: Vec, ref: number, rolloff: number, hrtf: boolean): PannerNode {
    const panner = ctx.createPanner();
    panner.panningModel = hrtf ? 'HRTF' : 'equalpower';
    panner.distanceModel = 'inverse';
    panner.refDistance = ref;
    panner.maxDistance = 10000;
    panner.rolloffFactor = rolloff;
    setPannerPos(panner, p);
    return panner;
  }

  // -------------------------------------------------------------------------
  // one-shots
  // -------------------------------------------------------------------------
  play(name: SfxName, opts?: PlayOpts): void {
    try {
      const ctx = this.ctx;
      if (!ctx || !this._ready || ctx.state !== 'running') return;
      const def = SFX[name];
      if (!def) return;
      const now = ctx.currentTime;

      // distance gating first (cheap)
      const pos = opts?.pos && Number.isFinite(opts.pos.x) && Number.isFinite(opts.pos.y) && Number.isFinite(opts.pos.z) ? opts.pos : null;
      const ref = def.ref ?? 3;
      let distGain = 1;
      let edge = 1;
      let d = 0;
      if (pos) {
        d = this.dist(pos);
        if (d > def.maxDist) return;
        distGain = ref / (ref + def.rolloff * (Math.max(d, ref) - ref));
        edge = 1 - smoothstep(def.maxDist * 0.75, def.maxDist, d);
      }
      const vol = Math.max(0, finite(opts?.volume, 1)) * def.vol * edge;
      const est = vol * distGain;
      if (est < 0.002) return;

      const bufs = this.buffers.get(name);
      if (!bufs || bufs.length === 0) {
        this.bump(name);
        return;
      }

      // rate limit identical names
      const limit = def.rate ?? 4;
      let rec = this.recent.get(name);
      if (!rec) {
        rec = [];
        this.recent.set(name, rec);
      }
      while (rec.length && rec[0] < now - RATE_WINDOW) rec.shift();
      if (rec.length >= limit) return;

      // per-name voice cap
      if (def.maxVoices !== undefined) {
        let count = 0;
        let weakest: Voice | null = null;
        for (const v of this.voices) {
          if (v.name !== name) continue;
          count++;
          if (!weakest || v.est < weakest.est || (v.est === weakest.est && v.start < weakest.start)) weakest = v;
        }
        if (count >= def.maxVoices && weakest) {
          if (weakest.est > est) return;
          this.steal(weakest, now);
        }
      }
      // global voice cap: drop quietest / oldest
      if (this.voices.length >= MAX_VOICES) {
        let victim: Voice | null = null;
        let score = Infinity;
        for (const v of this.voices) {
          const age = now - v.start;
          const s = v.est / (1 + age * 0.5);
          if (s < score) {
            score = s;
            victim = v;
          }
        }
        if (!victim || score > est) return;
        this.steal(victim, now);
      }
      rec.push(now);

      const buf = this.getBuffer(name);
      if (!buf) return;
      const src = ctx.createBufferSource();
      src.buffer = buf;
      const jit = def.jitter ?? 0.06;
      const pitch = clamp(finite(opts?.pitch, 1), 0.1, 4);
      src.playbackRate.value = pitch * (1 + (Math.random() * 2 - 1) * jit);
      const g = gain(ctx, vol);
      src.connect(g);
      const wet = def.wet ?? 0.04;
      if (pos) {
        let tail: AudioNode = g;
        if (d > 15) {
          const lp = bq(ctx, 'lowpass', clamp(20000 * Math.exp(-d / 45), 900, 20000), 0.5);
          g.connect(lp);
          tail = lp;
        }
        const panner = this.makePanner(ctx, pos, ref, def.rolloff, d < 25);
        tail.connect(panner).connect(this.sfxBus as GainNode);
        if (wet > 0 && this.sfxReverb) {
          const wg = gain(ctx, wet * Math.sqrt(distGain));
          tail.connect(wg).connect(this.sfxReverb);
        }
      } else {
        g.connect(this.sfxBus as GainNode);
        if (wet > 0 && this.sfxReverb) {
          const wg = gain(ctx, wet * 0.6);
          g.connect(wg).connect(this.sfxReverb);
        }
      }
      const voice: Voice = { src, g, name, est, start: now, dead: false };
      this.voices.push(voice);
      src.onended = (): void => {
        this.removeVoice(voice);
        try { src.disconnect(); g.disconnect(); } catch { /* ignore */ }
      };
      src.start(now);
    } catch (e) {
      console.warn('[audio] play failed', name, e);
    }
  }

  private steal(v: Voice, now: number): void {
    if (v.dead) return;
    v.dead = true;
    this.removeVoice(v);
    try {
      v.g.gain.cancelScheduledValues(now);
      v.g.gain.setValueAtTime(v.g.gain.value, now);
      v.g.gain.linearRampToValueAtTime(0, now + 0.03);
      v.src.stop(now + 0.04);
    } catch { /* ignore */ }
  }

  private removeVoice(v: Voice): void {
    const i = this.voices.indexOf(v);
    if (i >= 0) this.voices.splice(i, 1);
  }

  // -------------------------------------------------------------------------
  // loops
  // -------------------------------------------------------------------------
  startLoop(id: string, name: LoopName, opts?: { pos?: THREE.Vector3; volume?: number }): void {
    try {
      if (!LOOPS[name]) return;
      const existing = this.loops.get(id);
      if (existing) {
        if (existing.name !== name) {
          this.deactivateLoop(existing);
          existing.name = name;
        }
        this.updateLoop(id, opts ?? {});
        return;
      }
      const st: LoopState = {
        id, name, pos: opts?.pos ? { x: opts.pos.x, y: opts.pos.y, z: opts.pos.z } : null,
        volume: Math.max(0, finite(opts?.volume, 1)), active: null,
      };
      this.loops.set(id, st);
      if (this.ctx && this._ready) this.updateLoops();
    } catch { /* ignore */ }
  }

  updateLoop(id: string, opts: { pos?: THREE.Vector3; volume?: number }): void {
    try {
      const st = this.loops.get(id);
      if (!st || !opts) return;
      if (opts.pos && Number.isFinite(opts.pos.x)) {
        if (st.pos) {
          st.pos.x = opts.pos.x;
          st.pos.y = opts.pos.y;
          st.pos.z = opts.pos.z;
        } else st.pos = { x: opts.pos.x, y: opts.pos.y, z: opts.pos.z };
      }
      if (opts.volume !== undefined) st.volume = Math.max(0, finite(opts.volume, st.volume));
      const ctx = this.ctx;
      if (ctx && st.active) {
        if (st.active.panner && st.pos) setPannerPos(st.active.panner, st.pos);
        st.active.g.gain.setTargetAtTime(st.volume * LOOPS[st.name].vol, ctx.currentTime, 0.05);
      }
    } catch { /* ignore */ }
  }

  stopLoop(id: string): void {
    try {
      const st = this.loops.get(id);
      if (!st) return;
      this.loops.delete(id);
      this.deactivateLoop(st);
    } catch { /* ignore */ }
  }

  private activateLoop(st: LoopState): void {
    const ctx = this.ctx;
    if (!ctx || !this.sfxBus) return;
    const list = this.buffers.get('loop:' + st.name);
    if (!list || !list.length) {
      this.bump('loop:' + st.name);
      return;
    }
    const def = LOOPS[st.name];
    const src = ctx.createBufferSource();
    src.buffer = list[0];
    src.loop = true;
    src.playbackRate.value = 1 + (Math.random() * 2 - 1) * 0.02;
    const g = gain(ctx, 0);
    g.gain.setTargetAtTime(st.volume * def.vol, ctx.currentTime, 0.1);
    src.connect(g);
    let panner: PannerNode | null = null;
    if (st.pos) {
      panner = this.makePanner(ctx, st.pos, def.ref ?? 3, def.rolloff, true);
      g.connect(panner).connect(this.sfxBus);
    } else g.connect(this.sfxBus);
    src.start(ctx.currentTime, Math.random() * list[0].duration);
    st.active = { src, g, panner };
  }

  private deactivateLoop(st: LoopState): void {
    const a = st.active;
    st.active = null;
    const ctx = this.ctx;
    if (!a || !ctx) return;
    const now = ctx.currentTime;
    try {
      a.g.gain.cancelScheduledValues(now);
      a.g.gain.setValueAtTime(a.g.gain.value, now);
      a.g.gain.linearRampToValueAtTime(0, now + 0.3);
      a.src.stop(now + 0.35);
      a.src.onended = (): void => {
        try { a.src.disconnect(); a.g.disconnect(); a.panner?.disconnect(); } catch { /* ignore */ }
      };
    } catch { /* ignore */ }
  }

  private updateLoops(): void {
    // rank by distance; keep nearest MAX_LOOPS within range active
    const ranked: Array<{ st: LoopState; d: number }> = [];
    for (const st of this.loops.values()) {
      const d = st.pos ? this.dist(st.pos) : 0;
      const limit = st.active ? LOOP_CULL : LOOP_RESUME;
      if (d <= limit && st.volume > 0.001) ranked.push({ st, d });
      else if (st.active) this.deactivateLoop(st);
    }
    ranked.sort((a, b) => a.d - b.d);
    ranked.forEach(({ st }, i) => {
      if (i < MAX_LOOPS) {
        if (!st.active) this.activateLoop(st);
        // HRTF only for the nearest few loops (it is the expensive panning model)
        const p = st.active?.panner;
        if (p) {
          const model: PanningModelType = i < 4 ? 'HRTF' : 'equalpower';
          if (p.panningModel !== model) p.panningModel = model;
        }
      } else if (st.active) this.deactivateLoop(st);
    });
  }

  // -------------------------------------------------------------------------
  // ambience / music / mix
  // -------------------------------------------------------------------------
  setAmbience(a: AmbienceOpts): void {
    try {
      if (!a) return;
      const prevIndoor = this.amb.indoor;
      this.amb = { wind: finite(a.wind, 0), rain: finite(a.rain, 0), night: finite(a.night, 0), indoor: !!a.indoor, insideBase: !!a.insideBase };
      this.ambience?.set(this.amb);
      if (prevIndoor !== this.amb.indoor) this.applyIndoor();
    } catch { /* ignore */ }
  }

  private applyIndoor(): void {
    const ctx = this.ctx;
    if (!ctx || !this.outdoorWet || !this.indoorWet) return;
    const now = ctx.currentTime;
    this.outdoorWet.gain.setTargetAtTime(this.amb.indoor ? 0 : 1, now, 0.3);
    this.indoorWet.gain.setTargetAtTime(this.amb.indoor ? 1.3 : 0, now, 0.3);
  }

  setMusicIntensity(x: number): void {
    this.musicTarget = clamp(finite(x, 0), 0, 1);
    try { this.music?.setTarget(this.musicTarget); } catch { /* ignore */ }
  }

  setLowHealth(x: number): void {
    this.lowHealthTarget = clamp(finite(x, 0), 0, 1);
  }

  setVolumes(v: VolumeOpts): void {
    try {
      if (!v) return;
      if (v.master !== undefined) this.vol.master = clamp(finite(v.master, this.vol.master), 0, 1.5);
      if (v.sfx !== undefined) this.vol.sfx = clamp(finite(v.sfx, this.vol.sfx), 0, 1.5);
      if (v.music !== undefined) this.vol.music = clamp(finite(v.music, this.vol.music), 0, 1.5);
      if (v.ambience !== undefined) this.vol.ambience = clamp(finite(v.ambience, this.vol.ambience), 0, 1.5);
      const ctx = this.ctx;
      if (!ctx) return;
      const now = ctx.currentTime;
      this.masterOut?.gain.setTargetAtTime(this.vol.master, now, 0.05);
      this.sfxBus?.gain.setTargetAtTime(this.vol.sfx, now, 0.05);
      this.musicBus?.gain.setTargetAtTime(this.vol.music, now, 0.05);
      this.ambBus?.gain.setTargetAtTime(this.vol.ambience, now, 0.05);
      this.ambRev?.gain.setTargetAtTime(this.vol.ambience, now, 0.05);
    } catch { /* ignore */ }
  }

  private tick = (): void => {
    try {
      const ctx = this.ctx;
      if (!ctx || ctx.state !== 'running') return;
      const now = ctx.currentTime;
      const dt = clamp(now - this.lastTick, 0, 0.5);
      this.lastTick = now;
      this.tickCount++;
      this.music?.update(dt);
      if (this.tickCount % 4 === 0) {
        this.ambience?.update();
        this.updateLoops();
      }
      this.updateLowHealth(now, dt);
    } catch (e) {
      if (this.tickCount % 200 === 0) console.warn('[audio] tick error', e);
    }
  };

  private updateLowHealth(now: number, dt: number): void {
    const prev = this.lowHealth;
    this.lowHealth += (this.lowHealthTarget - this.lowHealth) * clamp(dt / 0.6, 0, 1);
    const x = this.lowHealth;
    if (Math.abs(prev - x) > 1e-4 || this.tickCount % 20 === 0) {
      this.lhFilter?.frequency.setTargetAtTime(20000 * Math.pow(600 / 20000, x * x), now, 0.1);
      this.tinnitus?.gain.setTargetAtTime(Math.max(0, x - 0.65) * 0.03, now, 0.3);
    }
    if (x > 0.25 && this.postIn && this.ctx) {
      if (this.nextBeat < now) this.nextBeat = now + 0.05;
      if (this.nextBeat < now + 0.15) {
        const buf = this.buffers.get('heartbeat')?.[0];
        if (buf) {
          const src = this.ctx.createBufferSource();
          src.buffer = buf;
          const g = gain(this.ctx, smoothstep(0.25, 1, x) * 0.7);
          src.connect(g).connect(this.postIn);
          src.start(this.nextBeat);
          src.onended = (): void => { src.disconnect(); g.disconnect(); };
        }
        this.nextBeat += 60 / (70 + 60 * x);
      }
    }
  }

  /** Debug info (voices, loaded buffers, active loops). */
  stats(): { voices: number; buffers: number; pending: number; loops: number; activeLoops: number; state: string } {
    let active = 0;
    for (const l of this.loops.values()) if (l.active) active++;
    return {
      voices: this.voices.length, buffers: this.buffers.size, pending: this.queue.length,
      loops: this.loops.size, activeLoops: active, state: this.ctx ? this.ctx.state : 'none',
    };
  }
}

function setPannerPos(p: PannerNode, v: Vec): void {
  if (p.positionX) {
    p.positionX.value = v.x;
    p.positionY.value = v.y;
    p.positionZ.value = v.z;
  } else p.setPosition(v.x, v.y, v.z);
}

export const audio: AudioEngine = new AudioEngine();

export type { BufName };
