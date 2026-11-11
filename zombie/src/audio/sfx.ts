// One-shot sound recipes. Each recipe builds a Web Audio graph into an
// OfflineAudioContext; the engine renders a few random variations of each
// at start-up so that playback is just BufferSource → gain → panner.

import {
  type Ctx, bell, bq, burst, crackles, creak, echoes, envGain, gain, modal, noise, osc, rand, randi,
  seg, shaper, thump, voice, whoosh, contour, mtof,
} from './dsp';

export type SfxName =
  'footstep_grass' | 'footstep_road' | 'footstep_wood' | 'footstep_metal' |
  'swing' | 'swing_heavy' | 'hit_flesh' | 'hit_wood' | 'hit_metal' | 'headshot' | 'bone_crack' |
  'gunshot_pistol' | 'gunshot_shotgun' | 'gunshot_rifle' | 'reload' | 'empty_click' |
  'zombie_groan' | 'zombie_alert' | 'zombie_attack' | 'zombie_death' | 'zombie_bang' |
  'player_hurt' | 'player_death' | 'heartbeat' | 'breath' |
  'door_open' | 'door_close' | 'glass_break' | 'wood_break' |
  'pickup' | 'drop' | 'eat' | 'drink' | 'bandage' | 'zipper' | 'search' |
  'craft' | 'build_place' | 'build_hammer' | 'deconstruct' | 'machine_place' |
  'turret_fire' | 'power_on' | 'power_off' | 'fuse_blow' | 'milestone' | 'objective' |
  'ui_click' | 'ui_open' | 'ui_close' | 'ui_error' | 'ui_hover' |
  'thunder' | 'sleep' | 'fire_ignite' | 'splash' | 'chop' | 'mine_hit' | 'zap';

/** Internal buffers used by ambience and music (not part of the public API). */
export type InternalName =
  'amb_owl' | 'amb_dog' | 'amb_bird' | 'amb_scream' | 'amb_gunshot' | 'amb_creak' | 'amb_horde' | 'amb_drop' |
  'm_taiko' | 'm_kick' | 'm_tick' | 'm_clank' | 'm_string' | 'm_braam';

export type BufName = SfxName | InternalName;

export type Recipe = (ctx: Ctx, out: AudioNode, v: number) => void;

export interface SfxDef {
  /** render length in seconds */
  dur: number;
  /** number of random variations */
  vars: number;
  /** base gain */
  vol: number;
  /** max audible distance (m) for positional playback */
  maxDist: number;
  /** inverse-distance rolloff factor */
  rolloff: number;
  ref?: number;
  /** random pitch jitter (fraction) */
  jitter?: number;
  /** max triggers per 100 ms */
  rate?: number;
  /** max concurrent voices of this name */
  maxVoices?: number;
  /** reverb send */
  wet?: number;
  /** render priority (lower first) */
  prio: number;
  stereo?: boolean;
  build: Recipe;
}

const T = 0.005;

// ---------------------------------------------------------------------------
// shared sub-recipes
// ---------------------------------------------------------------------------
function woodModes(f: number, dec = 1): Array<[number, number, number]> {
  return [
    [f * rand(0.95, 1.05), 0.12 * dec, 1],
    [f * rand(2.2, 2.6), 0.07 * dec, 0.55],
    [f * rand(4.6, 5.4), 0.04 * dec, 0.3],
    [f * rand(8, 9.5), 0.02 * dec, 0.15],
  ];
}

function metalModes(f: number, dec = 1, amp = 1): Array<[number, number, number]> {
  return [
    [f, 0.6 * dec, 0.6 * amp],
    [f * 2.76, 0.45 * dec, 0.45 * amp],
    [f * 5.40, 0.3 * dec, 0.3 * amp],
    [f * 8.93, 0.2 * dec, 0.18 * amp],
    [f * 13.3, 0.12 * dec, 0.1 * amp],
  ];
}

function click(ctx: Ctx, out: AudioNode, t: number, amp: number, f = 3000): void {
  burst(ctx, out, t, { dur: 0.006, type: 'highpass', f, Q: 0.7, amp });
}

function rustle(ctx: Ctx, out: AudioNode, t: number, dur: number, amp: number, f = 2500): void {
  burst(ctx, out, t, { dur, color: 'pink', type: 'bandpass', f, Q: 0.8, amp, attack: dur * 0.3 });
  crackles(ctx, out, t, dur, Math.floor(dur * 50), f * 0.8, f * 2.4, amp * 0.3);
}

function bodyFall(ctx: Ctx, out: AudioNode, t: number, amp: number): void {
  thump(ctx, out, t, 75, 35, 0.25, amp);
  burst(ctx, out, t, { dur: 0.25, color: 'brown', type: 'lowpass', f: 300, amp: amp * 0.9 });
  burst(ctx, out, t + 0.08, { dur: 0.15, color: 'pink', type: 'lowpass', f: 600, amp: amp * 0.4 });
  thump(ctx, out, t + 0.12, 90, 45, 0.12, amp * 0.4);
}

interface GunOpts {
  crackF: number; crackAmp: number; boom0: number; boom1: number; boomDur: number;
  body: number; bodyDur: number; tail: number; tailLp: number;
  echoes: ReadonlyArray<readonly [number, number, number]>;
}

function gun(ctx: Ctx, out: AudioNode, o: GunOpts): void {
  const bus = echoes(ctx, out, o.echoes);
  const crackBus = shaper(ctx, 4);
  const cg = gain(ctx, 0.9);
  crackBus.connect(cg).connect(bus);
  // supersonic crack / muzzle blast transient
  burst(ctx, crackBus, T, { dur: 0.004, type: 'highpass', f: o.crackF, amp: o.crackAmp, attack: 0.0003 });
  burst(ctx, crackBus, T + 0.001, { dur: 0.02, type: 'bandpass', f: o.crackF * 0.7, Q: 0.6, amp: o.crackAmp * 0.6 });
  // muzzle body
  burst(ctx, bus, T, { dur: o.bodyDur, type: 'bandpass', f: o.body, Q: 0.6, amp: 0.9, attack: 0.001 });
  // low boom
  thump(ctx, bus, T, o.boom0, o.boom1, o.boomDur, 1.1);
  burst(ctx, bus, T, { dur: o.boomDur * 1.2, color: 'brown', type: 'lowpass', f: 260, amp: 0.9 });
  // reverberant tail (environment)
  burst(ctx, out, T + 0.02, { dur: o.tail, color: 'pink', type: 'lowpass', f: o.tailLp, f1: o.tailLp * 0.35, amp: 0.16, attack: 0.04 });
}

function groanVowels(): Array<[number, number, number]> {
  return [
    [rand(450, 620), rand(880, 1100), rand(2350, 2650)],
    [rand(300, 420), rand(650, 850), rand(2200, 2450)],
    [rand(500, 680), rand(950, 1200), rand(2450, 2750)],
  ];
}

function ostring(ctx: Ctx, out: AudioNode, t: number, f: number, dur: number, amp: number): void {
  const sum = gain(ctx, 1);
  for (const det of [-9, 0, 8]) {
    const o = osc(ctx, 'sawtooth', f, t, t + dur + 0.1);
    o.detune.value = det;
    o.connect(sum);
  }
  const lp = bq(ctx, 'lowpass', 2400, 0.8);
  const pk = bq(ctx, 'peaking', 1100, 1.5, 6);
  const env = envGain(ctx, t, 0.012, dur, amp);
  sum.connect(lp).connect(pk).connect(env).connect(out);
  burst(ctx, out, t, { dur: 0.05, type: 'bandpass', f: 3000, Q: 1, amp: amp * 0.15 });
}

// ---------------------------------------------------------------------------
// recipes
// ---------------------------------------------------------------------------
export const SFX: Record<SfxName, SfxDef> = {
  // --- footsteps -----------------------------------------------------------
  footstep_grass: {
    dur: 0.3, vars: 4, vol: 0.32, maxDist: 18, rolloff: 1.6, rate: 4, maxVoices: 8, wet: 0.05, prio: 0,
    build(ctx, out) {
      for (let i = 0; i < 3; i++) {
        burst(ctx, out, T + i * rand(0.012, 0.03), { dur: rand(0.06, 0.14), type: 'bandpass', f: rand(2500, 5200), Q: 0.8, amp: rand(0.3, 0.55), attack: rand(0.006, 0.02) });
      }
      crackles(ctx, out, T, 0.12, 7, 2500, 7000, 0.25);
      const lp = bq(ctx, 'lowpass', 300);
      lp.connect(out);
      thump(ctx, lp, T, 95, 50, 0.08, 0.45);
    },
  },
  footstep_road: {
    dur: 0.25, vars: 4, vol: 0.36, maxDist: 22, rolloff: 1.5, rate: 4, maxVoices: 8, wet: 0.08, prio: 0,
    build(ctx, out, v) {
      click(ctx, out, T, 0.6, 2200);
      burst(ctx, out, T + 0.003, { dur: 0.045, type: 'bandpass', f: rand(1500, 2400), Q: 1.4, amp: 0.4 });
      thump(ctx, out, T, 125, 60, 0.05, 0.6);
      const toe = T + rand(0.05, 0.08);
      click(ctx, out, toe, 0.25, 2600);
      burst(ctx, out, toe, { dur: 0.03, type: 'bandpass', f: 2000, Q: 1.2, amp: 0.2 });
      if (v % 2 === 1) burst(ctx, out, T + 0.02, { dur: 0.1, type: 'bandpass', f: 3200, Q: 1, amp: 0.08, attack: 0.03 });
    },
  },
  footstep_wood: {
    dur: 0.4, vars: 4, vol: 0.4, maxDist: 20, rolloff: 1.5, rate: 4, maxVoices: 8, wet: 0.1, prio: 0,
    build(ctx, out, v) {
      modal(ctx, out, T, [[rand(165, 205), 0.11, 1], [rand(370, 440), 0.07, 0.5], [rand(800, 920), 0.035, 0.3], [rand(1700, 1900), 0.015, 0.15]]);
      burst(ctx, out, T, { dur: 0.05, type: 'bandpass', f: 230, Q: 4, amp: 0.8 });
      click(ctx, out, T, 0.25, 1500);
      thump(ctx, out, T, 105, 60, 0.07, 0.4);
      if (v === 3) creak(ctx, out, T + 0.03, 0.2, 40, 70, 0.12, [600, 1200, 2100], 10);
    },
  },
  footstep_metal: {
    dur: 0.5, vars: 4, vol: 0.38, maxDist: 24, rolloff: 1.4, rate: 4, maxVoices: 8, wet: 0.12, prio: 0,
    build(ctx, out) {
      const f = rand(470, 540);
      modal(ctx, out, T, [[f, 0.25, 0.4], [f * rand(2.3, 2.5), 0.18, 0.35], [f * rand(4.1, 4.3), 0.12, 0.25], [f * rand(6.7, 7), 0.08, 0.18], [f * rand(10.5, 11), 0.05, 0.1]]);
      click(ctx, out, T, 0.5, 2500);
      thump(ctx, out, T, 95, 55, 0.06, 0.5);
      burst(ctx, out, T, { dur: 0.15, type: 'bandpass', f: 160, Q: 3, amp: 0.4 });
    },
  },

  // --- melee ---------------------------------------------------------------
  swing: {
    dur: 0.35, vars: 3, vol: 0.45, maxDist: 15, rolloff: 1.6, wet: 0.05, prio: 1,
    build(ctx, out) {
      whoosh(ctx, out, T, rand(0.18, 0.26), rand(280, 380), rand(1400, 2000), rand(450, 600), 0.9, 1.3);
    },
  },
  swing_heavy: {
    dur: 0.6, vars: 3, vol: 0.55, maxDist: 18, rolloff: 1.5, wet: 0.05, prio: 1,
    build(ctx, out) {
      whoosh(ctx, out, T, rand(0.36, 0.45), rand(160, 220), rand(800, 1100), rand(220, 300), 1, 1.1);
      const lp = bq(ctx, 'lowpass', 200);
      const g = gain(ctx, 0);
      seg(g.gain, T, [[0, 0], [0.2, 0.5], [0.42, 0]]);
      noise(ctx, 'brown', T, T + 0.5).connect(lp).connect(g).connect(out);
    },
  },
  hit_flesh: {
    dur: 0.4, vars: 4, vol: 0.5, maxDist: 30, rolloff: 1.3, rate: 3, wet: 0.08, prio: 1,
    build(ctx, out) {
      const drv = shaper(ctx, 2.5);
      drv.connect(out);
      thump(ctx, drv, T, rand(85, 100), 40, 0.13, 1);
      burst(ctx, drv, T, { dur: 0.1, color: 'pink', type: 'lowpass', f: 420, amp: 0.8 });
      burst(ctx, drv, T + 0.004, { dur: 0.12, type: 'bandpass', f: rand(650, 1000), f1: 350, Q: 2.5, amp: 0.55 });
      burst(ctx, drv, T + 0.02, { dur: 0.1, type: 'bandpass', f: rand(1100, 1500), f1: 600, Q: 3, amp: 0.3 });
      crackles(ctx, out, T + 0.004, 0.03, 4, 1500, 4000, 0.35);
    },
  },
  hit_wood: {
    dur: 0.4, vars: 3, vol: 0.75, maxDist: 35, rolloff: 1.2, rate: 3, wet: 0.1, prio: 2,
    build(ctx, out) {
      modal(ctx, out, T, woodModes(rand(150, 230)));
      click(ctx, out, T, 0.6, 1800);
      crackles(ctx, out, T, 0.04, 5, 1200, 4000, 0.3);
      thump(ctx, out, T, 120, 55, 0.08, 0.6);
    },
  },
  hit_metal: {
    dur: 1.0, vars: 3, vol: 0.7, maxDist: 45, rolloff: 1.1, rate: 3, wet: 0.15, prio: 2,
    build(ctx, out) {
      click(ctx, out, T, 0.7, 2500);
      modal(ctx, out, T, metalModes(rand(560, 720), 1.1));
      thump(ctx, out, T, 140, 70, 0.06, 0.5);
    },
  },
  headshot: {
    dur: 0.6, vars: 3, vol: 0.6, maxDist: 32, rolloff: 1.3, rate: 3, wet: 0.08, prio: 1,
    build(ctx, out) {
      const drv = shaper(ctx, 3);
      drv.connect(out);
      thump(ctx, drv, T, 110, 38, 0.16, 1);
      burst(ctx, drv, T, { dur: 0.12, color: 'pink', type: 'lowpass', f: 500, amp: 0.8 });
      crackles(ctx, out, T, 0.05, 12, 1500, 5500, 0.7);
      burst(ctx, out, T + 0.01, { dur: 0.22, type: 'bandpass', f: 1600, f1: 300, Q: 1.5, amp: 0.7 });
      burst(ctx, out, T + 0.06, { dur: 0.25, type: 'bandpass', f: 900, f1: 250, Q: 2, amp: 0.3, attack: 0.02 });
    },
  },
  bone_crack: {
    dur: 0.3, vars: 3, vol: 0.7, maxDist: 25, rolloff: 1.4, wet: 0.06, prio: 2,
    build(ctx, out) {
      click(ctx, out, T, 1, 2500);
      for (let i = 0; i < 6; i++) {
        burst(ctx, out, T + Math.pow(Math.random(), 1.5) * 0.06, { dur: rand(0.003, 0.008), type: 'bandpass', f: rand(1500, 5000), Q: 2, amp: rand(0.4, 0.9) });
      }
      thump(ctx, out, T, 220, 80, 0.04, 0.4);
    },
  },

  // --- firearms ------------------------------------------------------------
  gunshot_pistol: {
    dur: 2.6, vars: 3, vol: 1.5, maxDist: 150, rolloff: 0.35, rate: 4, wet: 0.3, prio: 1,
    build(ctx, out) {
      gun(ctx, out, {
        crackF: 1600, crackAmp: 1, boom0: rand(150, 175), boom1: 45, boomDur: 0.18, body: rand(800, 1000), bodyDur: 0.08,
        tail: 1.4, tailLp: 1300, echoes: [[rand(0.33, 0.42), 0.22, 1100], [rand(0.85, 0.95), 0.11, 700], [rand(1.5, 1.7), 0.05, 480]],
      });
      modal(ctx, out, T + 0.07, [[rand(2400, 2800), 0.03, 0.1], [rand(4100, 4500), 0.02, 0.06]]);
    },
  },
  gunshot_shotgun: {
    dur: 3.0, vars: 3, vol: 1.7, maxDist: 160, rolloff: 0.33, rate: 3, wet: 0.32, prio: 1,
    build(ctx, out) {
      gun(ctx, out, {
        crackF: 1200, crackAmp: 1, boom0: rand(115, 130), boom1: 32, boomDur: 0.35, body: rand(550, 700), bodyDur: 0.16,
        tail: 2.0, tailLp: 1000, echoes: [[rand(0.36, 0.45), 0.25, 900], [rand(0.9, 1.0), 0.13, 600], [rand(1.6, 1.8), 0.06, 420]],
      });
      burst(ctx, out, T + 0.002, { dur: 0.1, type: 'bandpass', f: 2200, Q: 0.5, amp: 0.4 });
    },
  },
  gunshot_rifle: {
    dur: 3.2, vars: 3, vol: 1.6, maxDist: 180, rolloff: 0.3, rate: 4, wet: 0.3, prio: 1,
    build(ctx, out) {
      gun(ctx, out, {
        crackF: 3000, crackAmp: 1, boom0: rand(135, 150), boom1: 40, boomDur: 0.26, body: rand(1100, 1400), bodyDur: 0.07,
        tail: 2.2, tailLp: 1500, echoes: [[rand(0.42, 0.5), 0.24, 1200], [rand(1.0, 1.15), 0.12, 750], [rand(1.8, 2.0), 0.06, 500]],
      });
    },
  },
  turret_fire: {
    dur: 1.2, vars: 3, vol: 0.75, maxDist: 120, rolloff: 0.45, rate: 4, maxVoices: 10, wet: 0.2, prio: 3,
    build(ctx, out) {
      gun(ctx, out, {
        crackF: 2600, crackAmp: 0.9, boom0: 200, boom1: 60, boomDur: 0.1, body: 1500, bodyDur: 0.05,
        tail: 0.7, tailLp: 1600, echoes: [[0.3, 0.15, 1000]],
      });
      modal(ctx, out, T + 0.05, [[rand(1300, 1500), 0.06, 0.25], [rand(2800, 3100), 0.04, 0.15]]);
      const o = osc(ctx, 'sawtooth', 380, T + 0.04, T + 0.2);
      o.frequency.linearRampToValueAtTime(260, T + 0.2);
      const f = bq(ctx, 'bandpass', 1200, 3);
      const g = envGain(ctx, T + 0.04, 0.01, 0.14, 0.05);
      o.connect(f).connect(g).connect(out);
    },
  },
  reload: {
    dur: 1.15, vars: 2, vol: 0.7, maxDist: 15, rolloff: 1.6, wet: 0.05, prio: 2,
    build(ctx, out) {
      modal(ctx, out, T + 0.05, [[3100, 0.025, 0.5], [4300, 0.02, 0.3]]);
      click(ctx, out, T + 0.05, 0.3);
      burst(ctx, out, T + 0.12, { dur: 0.09, type: 'bandpass', f: 2500, f1: 1800, Q: 2, amp: 0.25, attack: 0.02 });
      const ins = T + rand(0.42, 0.5);
      burst(ctx, out, ins - 0.06, { dur: 0.06, type: 'bandpass', f: 1800, f1: 2600, Q: 2, amp: 0.2, attack: 0.02 });
      modal(ctx, out, ins, [[900, 0.05, 0.6], [2200, 0.04, 0.4], [3500, 0.03, 0.25]]);
      thump(ctx, out, ins, 220, 110, 0.04, 0.4);
      click(ctx, out, ins, 0.5);
      const sl = T + rand(0.72, 0.78);
      burst(ctx, out, sl, { dur: 0.07, type: 'bandpass', f: 2800, f1: 3800, Q: 2, amp: 0.3, attack: 0.03 });
      const sn = sl + 0.12;
      click(ctx, out, sn, 0.7);
      modal(ctx, out, sn, [[1500, 0.06, 0.5], [2800, 0.05, 0.4], [4100, 0.03, 0.3]]);
      thump(ctx, out, sn, 180, 90, 0.04, 0.4);
    },
  },
  empty_click: {
    dur: 0.15, vars: 2, vol: 0.7, maxDist: 10, rolloff: 1.8, prio: 2,
    build(ctx, out) {
      modal(ctx, out, T, [[rand(3100, 3300), 0.02, 1], [rand(4600, 4900), 0.015, 0.6], [1800, 0.03, 0.3]]);
      click(ctx, out, T, 0.5);
    },
  },

  // --- zombies -------------------------------------------------------------
  zombie_groan: {
    dur: 2.6, vars: 5, vol: 0.6, maxDist: 35, rolloff: 1.0, rate: 2, maxVoices: 8, wet: 0.12, prio: 1,
    build(ctx, out) {
      const dur = rand(1.3, 2.3);
      voice(ctx, out, T, dur, {
        f0: [rand(70, 95), rand(85, 115), rand(60, 80)], jitter: 0.045, breath: 0.35, vowels: groanVowels(),
        gurgle: rand(0.45, 0.75), gurgleRate: rand(6, 11), drive: 3, attack: 0.25, release: 0.55,
      });
    },
  },
  zombie_alert: {
    dur: 1.4, vars: 3, vol: 0.85, maxDist: 50, rolloff: 0.8, rate: 2, maxVoices: 5, wet: 0.15, prio: 1,
    build(ctx, out) {
      // short inhale then shriek
      burst(ctx, out, T, { dur: 0.15, color: 'pink', type: 'bandpass', f: 1500, Q: 1, amp: 0.25, attack: 0.12 });
      voice(ctx, out, T + 0.18, rand(0.8, 1.05), {
        f0: [rand(130, 160), rand(250, 300), rand(210, 240), rand(160, 190)], jitter: 0.07, breath: 0.55,
        vowels: [[750, 1200, 2600], [rand(600, 680), rand(1700, 1950), 2900], [550, 1400, 2700]],
        gurgle: 0.3, gurgleRate: rand(15, 22), drive: 6, attack: 0.05, release: 0.3, sub: 0.25,
      });
    },
  },
  zombie_attack: {
    dur: 0.9, vars: 3, vol: 0.8, maxDist: 30, rolloff: 1.1, rate: 2, maxVoices: 5, wet: 0.1, prio: 1,
    build(ctx, out) {
      voice(ctx, out, T, rand(0.5, 0.7), {
        f0: [rand(100, 120), rand(160, 190), rand(120, 140)], jitter: 0.12, breath: 0.65,
        vowels: [[700, 1150, 2500], [650, 1100, 2400]], gurgle: 0.7, gurgleRate: rand(20, 28), drive: 8, attack: 0.03, release: 0.2,
      });
      modal(ctx, out, T + rand(0.35, 0.5), [[2600, 0.02, 0.3], [4100, 0.015, 0.2]]);
    },
  },
  zombie_death: {
    dur: 2.2, vars: 3, vol: 0.75, maxDist: 35, rolloff: 1.0, rate: 2, maxVoices: 5, wet: 0.12, prio: 2,
    build(ctx, out) {
      voice(ctx, out, T, rand(1.3, 1.6), {
        f0: [rand(95, 110), rand(75, 90), rand(45, 55)], jitter: 0.05, breath: 0.4,
        vowels: [[420, 800, 2400], [550, 950, 2500], [350, 700, 2300]], gurgle: 0.9, gurgleRate: rand(6, 9), drive: 4, attack: 0.08, release: 0.8,
      });
      bodyFall(ctx, out, T + rand(1.1, 1.35), 0.8);
    },
  },
  zombie_bang: {
    dur: 1.5, vars: 3, vol: 0.7, maxDist: 40, rolloff: 1.0, rate: 2, maxVoices: 4, wet: 0.15, prio: 2,
    build(ctx, out) {
      const n = randi(2, 3);
      let t = T;
      for (let i = 0; i < n; i++) {
        const a = i === 0 ? 1 : rand(0.7, 1);
        thump(ctx, out, t, 85, 45, 0.15, a);
        modal(ctx, out, t, [[rand(105, 120), 0.2, a], [rand(230, 260), 0.13, 0.6 * a], [rand(500, 560), 0.07, 0.3 * a]]);
        burst(ctx, out, t, { dur: 0.08, type: 'lowpass', f: 700, amp: 0.6 * a });
        crackles(ctx, out, t + 0.01, 0.08, 6, 1500, 4000, 0.2 * a);
        t += rand(0.28, 0.42);
      }
    },
  },

  // --- player --------------------------------------------------------------
  player_hurt: {
    dur: 0.5, vars: 4, vol: 0.7, maxDist: 25, rolloff: 1.4, rate: 2, maxVoices: 2, wet: 0.05, prio: 1, jitter: 0.04,
    build(ctx, out) {
      const d = rand(0.24, 0.36);
      voice(ctx, out, T, d, {
        f0: [rand(140, 170), rand(105, 125)], jitter: 0.03, breath: 0.45,
        vowels: [[rand(650, 750), rand(1100, 1250), 2600], [600, 1000, 2500]], gurgle: 0.1, gurgleRate: 5, drive: 2, attack: 0.012, release: 0.15, sub: 0.15,
      });
      thump(ctx, out, T, 80, 40, 0.1, 0.5);
    },
  },
  player_death: {
    dur: 2.8, vars: 2, vol: 0.8, maxDist: 25, rolloff: 1.4, rate: 1, maxVoices: 1, wet: 0.15, prio: 3, jitter: 0.02,
    build(ctx, out) {
      voice(ctx, out, T, 1.4, {
        f0: [165, 135, 90], jitter: 0.035, breath: 0.55,
        vowels: [[700, 1200, 2600], [500, 900, 2450], [380, 750, 2300]], gurgle: 0.3, gurgleRate: 6, drive: 2.5, attack: 0.03, release: 0.8, sub: 0.2,
      });
      bodyFall(ctx, out, T + 1.05, 0.9);
      burst(ctx, out, T + 1.5, { dur: 0.8, color: 'pink', type: 'bandpass', f: 900, Q: 1, amp: 0.2, attack: 0.12 });
    },
  },
  heartbeat: {
    dur: 0.65, vars: 1, vol: 0.6, maxDist: 5, rolloff: 2, jitter: 0, prio: 0,
    build(ctx, out) {
      thump(ctx, out, T, 62, 34, 0.14, 1);
      burst(ctx, out, T, { dur: 0.1, color: 'brown', type: 'lowpass', f: 120, amp: 0.5 });
      thump(ctx, out, T + 0.27, 58, 32, 0.16, 0.7);
      burst(ctx, out, T + 0.27, { dur: 0.12, color: 'brown', type: 'lowpass', f: 110, amp: 0.35 });
    },
  },
  breath: {
    dur: 1.7, vars: 3, vol: 0.38, maxDist: 8, rolloff: 2, rate: 1, maxVoices: 1, prio: 3, jitter: 0.03,
    build(ctx, out) {
      const ind = rand(0.45, 0.6);
      burst(ctx, out, T, { dur: ind * 0.3, color: 'pink', type: 'bandpass', f: rand(1300, 1600), Q: 1.2, amp: 0.35, attack: ind * 0.7, curve: 'l' });
      const ex = T + ind + rand(0.1, 0.2);
      burst(ctx, out, ex, { dur: 0.6, color: 'pink', type: 'bandpass', f: rand(800, 1000), Q: 1, amp: 0.45, attack: 0.06 });
      burst(ctx, out, ex, { dur: 0.5, color: 'white', type: 'bandpass', f: 2400, Q: 2, amp: 0.12, attack: 0.05 });
    },
  },

  // --- world interaction ---------------------------------------------------
  door_open: {
    dur: 1.1, vars: 3, vol: 0.6, maxDist: 25, rolloff: 1.3, wet: 0.15, prio: 3,
    build(ctx, out) {
      modal(ctx, out, T, [[2200, 0.03, 0.4], [3400, 0.02, 0.25]]);
      click(ctx, out, T, 0.4);
      creak(ctx, out, T + 0.08, rand(0.55, 0.8), rand(30, 45), rand(70, 110), 0.5, [rand(600, 800), rand(1300, 1600), 2500], 14);
      burst(ctx, out, T + 0.1, { dur: 0.5, color: 'pink', type: 'lowpass', f: 400, amp: 0.15, attack: 0.2 });
    },
  },
  door_close: {
    dur: 0.9, vars: 3, vol: 0.55, maxDist: 30, rolloff: 1.2, wet: 0.18, prio: 3,
    build(ctx, out) {
      burst(ctx, out, T, { dur: 0.1, color: 'pink', type: 'lowpass', f: 500, amp: 0.15, attack: 0.08 });
      const t = T + 0.12;
      thump(ctx, out, t, 90, 45, 0.2, 1);
      modal(ctx, out, t, [[rand(90, 105), 0.25, 1], [rand(200, 225), 0.15, 0.5], [rand(440, 480), 0.08, 0.3]]);
      modal(ctx, out, t + 0.04, [[2300, 0.04, 0.4], [3600, 0.03, 0.25]]);
      click(ctx, out, t + 0.04, 0.5);
    },
  },
  glass_break: {
    dur: 1.3, vars: 3, vol: 0.8, maxDist: 45, rolloff: 1.0, wet: 0.15, prio: 3,
    build(ctx, out) {
      click(ctx, out, T, 1, 2000);
      burst(ctx, out, T, { dur: 0.3, type: 'bandpass', f: 4000, Q: 0.5, amp: 0.6 });
      burst(ctx, out, T, { dur: 0.08, color: 'pink', type: 'lowpass', f: 900, amp: 0.4 });
      for (let i = 0; i < 28; i++) {
        const t = T + Math.pow(Math.random(), 2) * 0.8;
        modal(ctx, out, t, [[rand(2500, 8000), rand(0.02, 0.12), rand(0.08, 0.35)], [rand(5000, 9000), rand(0.01, 0.05), rand(0.04, 0.15)]]);
        if (Math.random() < 0.5) burst(ctx, out, t, { dur: 0.004, type: 'highpass', f: 5000, amp: 0.2 });
      }
    },
  },
  wood_break: {
    dur: 1.1, vars: 3, vol: 0.7, maxDist: 40, rolloff: 1.0, wet: 0.15, prio: 3,
    build(ctx, out) {
      const drv = shaper(ctx, 3);
      drv.connect(out);
      burst(ctx, drv, T, { dur: 0.05, type: 'bandpass', f: 1500, Q: 0.8, amp: 1 });
      crackles(ctx, out, T, 0.25, 18, 1000, 5000, 0.5);
      modal(ctx, out, T, woodModes(rand(110, 150), 1.4));
      thump(ctx, out, T, 100, 45, 0.15, 0.7);
      for (let i = 0; i < randi(3, 5); i++) {
        modal(ctx, out, T + rand(0.15, 0.7), woodModes(rand(300, 700), 0.5).map(([f, d, a]) => [f, d, a * rand(0.15, 0.35)] as [number, number, number]));
      }
    },
  },
  pickup: {
    dur: 0.3, vars: 3, vol: 0.45, maxDist: 10, rolloff: 1.8, rate: 3, prio: 1,
    build(ctx, out) {
      rustle(ctx, out, T, 0.12, 0.4, rand(2000, 3000));
      modal(ctx, out, T + 0.05, [[rand(1600, 2000), 0.04, 0.25], [rand(3200, 3800), 0.02, 0.1]]);
      thump(ctx, out, T + 0.04, 180, 100, 0.04, 0.2);
    },
  },
  drop: {
    dur: 0.4, vars: 3, vol: 0.5, maxDist: 12, rolloff: 1.7, rate: 3, prio: 2,
    build(ctx, out) {
      thump(ctx, out, T, rand(130, 160), 70, 0.08, 0.8);
      burst(ctx, out, T, { dur: 0.08, color: 'pink', type: 'lowpass', f: 900, amp: 0.5 });
      crackles(ctx, out, T + 0.01, 0.1, 5, 1500, 4000, 0.15);
    },
  },
  eat: {
    dur: 1.1, vars: 2, vol: 0.5, maxDist: 8, rolloff: 2, rate: 1, prio: 3,
    build(ctx, out) {
      let t = T;
      for (let i = 0; i < 3; i++) {
        crackles(ctx, out, t, 0.07, 9, 1500, 5000, 0.5);
        burst(ctx, out, t, { dur: 0.08, color: 'pink', type: 'bandpass', f: 900, Q: 1, amp: 0.4 });
        thump(ctx, out, t, 130, 70, 0.04, 0.2);
        t += rand(0.22, 0.32);
      }
    },
  },
  drink: {
    dur: 1.3, vars: 2, vol: 0.5, maxDist: 8, rolloff: 2, rate: 1, prio: 3,
    build(ctx, out) {
      let t = T + 0.05;
      for (let i = 0; i < 3; i++) {
        const o = osc(ctx, 'sine', 180, t, t + 0.12);
        o.frequency.setValueAtTime(rand(170, 200), t);
        o.frequency.exponentialRampToValueAtTime(rand(380, 460), t + 0.07);
        const g = envGain(ctx, t, 0.01, 0.08, 0.6);
        o.connect(g).connect(out);
        burst(ctx, out, t, { dur: 0.06, type: 'lowpass', f: 500, amp: 0.4 });
        for (let b = 0; b < 3; b++) {
          const tb = t + rand(0.02, 0.15);
          const ob = osc(ctx, 'sine', 600, tb, tb + 0.05);
          ob.frequency.setValueAtTime(rand(500, 800), tb);
          ob.frequency.exponentialRampToValueAtTime(rand(1000, 1500), tb + 0.03);
          const gb = envGain(ctx, tb, 0.003, 0.03, 0.12);
          ob.connect(gb).connect(out);
        }
        t += rand(0.3, 0.38);
      }
    },
  },
  bandage: {
    dur: 1.2, vars: 2, vol: 0.5, maxDist: 8, rolloff: 2, rate: 1, prio: 3,
    build(ctx, out) {
      burst(ctx, out, T, { dur: 0.35, type: 'bandpass', f: 3000, Q: 0.7, amp: 0.3, attack: 0.02, curve: 'l' });
      crackles(ctx, out, T, 0.38, 45, 1800, 6000, 0.5);
      rustle(ctx, out, T + 0.5, 0.25, 0.35, 1800);
      rustle(ctx, out, T + 0.8, 0.25, 0.3, 2000);
    },
  },
  zipper: {
    dur: 0.6, vars: 2, vol: 0.7, maxDist: 8, rolloff: 2, rate: 1, prio: 3,
    build(ctx, out) {
      const n = 34;
      let t = T;
      for (let i = 0; i < n; i++) {
        burst(ctx, out, t, { dur: 0.004, type: 'highpass', f: 2500, amp: rand(0.3, 0.55) });
        t += 0.016 - 0.006 * Math.sin((i / n) * Math.PI);
      }
      burst(ctx, out, T, { dur: t - T, type: 'bandpass', f: 4000, f1: 5000, Q: 1.5, amp: 0.1, attack: 0.03, curve: 'l' });
    },
  },
  search: {
    dur: 1.2, vars: 3, vol: 0.45, maxDist: 12, rolloff: 1.7, rate: 1, prio: 3,
    build(ctx, out) {
      for (let i = 0; i < 6; i++) rustle(ctx, out, T + rand(0, 0.85), rand(0.08, 0.2), rand(0.2, 0.45), rand(1500, 4000));
      for (let i = 0; i < 3; i++) {
        const t = T + rand(0.1, 0.9);
        if (Math.random() < 0.5) modal(ctx, out, t, woodModes(rand(400, 700), 0.5).map(([f, d, a]) => [f, d, a * 0.3] as [number, number, number]));
        else modal(ctx, out, t, [[rand(1800, 2600), 0.05, 0.12], [rand(4000, 5000), 0.03, 0.06]]);
      }
    },
  },

  // --- building / factory --------------------------------------------------
  craft: {
    dur: 1.1, vars: 2, vol: 0.5, maxDist: 10, rolloff: 1.8, rate: 1, prio: 3,
    build(ctx, out) {
      rustle(ctx, out, T, 0.2, 0.3);
      for (let i = 0; i < 3; i++) modal(ctx, out, T + 0.15 + i * rand(0.12, 0.18), woodModes(rand(350, 500), 0.6).map(([f, d, a]) => [f, d, a * 0.5] as [number, number, number]));
      for (let i = 0; i < 8; i++) burst(ctx, out, T + 0.6 + i * 0.025, { dur: 0.004, type: 'bandpass', f: 3500, Q: 2, amp: 0.3 });
      bell(ctx, out, T + 0.85, 1320, 0.4, 0.15);
    },
  },
  build_place: {
    dur: 0.8, vars: 3, vol: 0.55, maxDist: 30, rolloff: 1.2, wet: 0.12, prio: 3,
    build(ctx, out) {
      thump(ctx, out, T, 100, 48, 0.2, 1);
      modal(ctx, out, T, [[rand(115, 130), 0.2, 0.8], [rand(250, 280), 0.12, 0.45], [rand(520, 580), 0.06, 0.25]]);
      burst(ctx, out, T + 0.01, { dur: 0.4, color: 'pink', type: 'lowpass', f: 2000, amp: 0.2, attack: 0.03 });
      crackles(ctx, out, T + 0.02, 0.2, 6, 1500, 4000, 0.12);
    },
  },
  build_hammer: {
    dur: 0.5, vars: 4, vol: 0.7, maxDist: 40, rolloff: 1.1, rate: 3, wet: 0.15, prio: 3,
    build(ctx, out) {
      click(ctx, out, T, 0.8, 2500);
      modal(ctx, out, T, [[rand(1100, 1300), 0.08, 0.6], [rand(2500, 2900), 0.05, 0.4], [rand(320, 380), 0.1, 0.8], [rand(700, 800), 0.06, 0.4]]);
      thump(ctx, out, T, 150, 70, 0.06, 0.6);
    },
  },
  deconstruct: {
    dur: 1.3, vars: 2, vol: 0.7, maxDist: 30, rolloff: 1.2, wet: 0.12, prio: 4,
    build(ctx, out) {
      creak(ctx, out, T, 0.4, 25, 60, 0.4, [500, 1100, 1900], 10);
      const t = T + 0.35;
      burst(ctx, out, t, { dur: 0.05, type: 'bandpass', f: 1400, Q: 0.8, amp: 0.8 });
      crackles(ctx, out, t, 0.2, 14, 1000, 5000, 0.45);
      modal(ctx, out, t, woodModes(rand(130, 170), 1.2));
      for (let i = 0; i < 3; i++) modal(ctx, out, t + rand(0.2, 0.7), woodModes(rand(300, 600), 0.5).map(([f, d, a]) => [f, d, a * 0.3] as [number, number, number]));
    },
  },
  machine_place: {
    dur: 1.4, vars: 2, vol: 0.65, maxDist: 35, rolloff: 1.1, wet: 0.15, prio: 4,
    build(ctx, out) {
      thump(ctx, out, T, 70, 34, 0.3, 1);
      modal(ctx, out, T, metalModes(rand(170, 200), 0.6, 0.8));
      burst(ctx, out, T, { dur: 0.15, color: 'brown', type: 'lowpass', f: 300, amp: 0.7 });
      burst(ctx, out, T + 0.15, { dur: 0.45, type: 'highpass', f: 3000, amp: 0.15, attack: 0.05 });
      const hum = gain(ctx, 0);
      seg(hum.gain, T + 0.45, [[0, 0], [0.25, 0.15], [0.8, 0]]);
      osc(ctx, 'sine', 100, T + 0.45, T + 1.3).connect(hum);
      osc(ctx, 'sawtooth', 50, T + 0.45, T + 1.3).connect(bq(ctx, 'lowpass', 400)).connect(hum);
      hum.connect(out);
    },
  },
  power_on: {
    dur: 1.9, vars: 1, vol: 0.4, maxDist: 40, rolloff: 1.1, wet: 0.15, prio: 4, jitter: 0.02,
    build(ctx, out) {
      modal(ctx, out, T, [[420, 0.08, 0.6], [1250, 0.05, 0.4]]);
      thump(ctx, out, T, 120, 60, 0.06, 0.6);
      const o = osc(ctx, 'sawtooth', 40, T, T + 1.8);
      o.frequency.setValueAtTime(40, T);
      o.frequency.exponentialRampToValueAtTime(120, T + 1.2);
      const o2 = osc(ctx, 'sine', 80, T, T + 1.8);
      o2.frequency.setValueAtTime(80, T);
      o2.frequency.exponentialRampToValueAtTime(240, T + 1.2);
      const lp = bq(ctx, 'lowpass', 200, 2);
      lp.frequency.setValueAtTime(200, T);
      lp.frequency.exponentialRampToValueAtTime(2200, T + 1.2);
      const g = gain(ctx, 0);
      seg(g.gain, T, [[0, 0], [0.4, 0.5], [1.2, 0.45], [1.75, 0]]);
      o.connect(lp);
      o2.connect(lp);
      lp.connect(g).connect(out);
      crackles(ctx, out, T + 0.2, 0.8, 8, 3000, 8000, 0.12);
    },
  },
  power_off: {
    dur: 1.7, vars: 1, vol: 0.55, maxDist: 40, rolloff: 1.1, wet: 0.15, prio: 4, jitter: 0.02,
    build(ctx, out) {
      modal(ctx, out, T, [[400, 0.08, 0.6], [1150, 0.05, 0.4]]);
      thump(ctx, out, T, 110, 55, 0.06, 0.6);
      const o = osc(ctx, 'sawtooth', 120, T, T + 1.6);
      o.frequency.setValueAtTime(120, T);
      o.frequency.exponentialRampToValueAtTime(25, T + 1.5);
      const lp = bq(ctx, 'lowpass', 2000, 2);
      lp.frequency.setValueAtTime(2000, T);
      lp.frequency.exponentialRampToValueAtTime(120, T + 1.5);
      const g = gain(ctx, 0);
      seg(g.gain, T, [[0, 0], [0.03, 0.5], [1.55, 0]]);
      o.connect(lp).connect(g).connect(out);
    },
  },
  fuse_blow: {
    dur: 1.3, vars: 2, vol: 0.85, maxDist: 50, rolloff: 1.0, wet: 0.15, prio: 4,
    build(ctx, out) {
      const drv = shaper(ctx, 8);
      drv.connect(out);
      burst(ctx, drv, T, { dur: 0.05, type: 'lowpass', f: 3000, amp: 1 });
      thump(ctx, out, T, 130, 40, 0.15, 0.9);
      zapInto(ctx, out, T + 0.02, 0.3, 0.6);
      burst(ctx, out, T + 0.05, { dur: 0.8, type: 'highpass', f: 5000, amp: 0.15, attack: 0.02 });
      crackles(ctx, out, T + 0.1, 0.7, 12, 3000, 8000, 0.2);
    },
  },
  zap: {
    dur: 0.5, vars: 3, vol: 0.6, maxDist: 30, rolloff: 1.3, rate: 3, wet: 0.08, prio: 4,
    build(ctx, out) {
      zapInto(ctx, out, T, rand(0.25, 0.4), 1);
    },
  },
  milestone: {
    dur: 3.8, vars: 1, vol: 0.55, maxDist: 10, rolloff: 1, jitter: 0, wet: 0.35, prio: 4, stereo: true,
    build(ctx, out) {
      const notes = [50, 57, 62, 66, 69, 76];
      const lp = bq(ctx, 'lowpass', 400, 1);
      lp.frequency.setValueAtTime(400, T);
      lp.frequency.exponentialRampToValueAtTime(3200, T + 1.0);
      lp.frequency.exponentialRampToValueAtTime(1200, T + 3.2);
      const env = gain(ctx, 0);
      seg(env.gain, T, [[0, 0], [0.7, 0.35], [1.6, 0.3], [3.5, 0]]);
      lp.connect(env);
      const merger = ctx.createChannelMerger(2);
      env.connect(merger, 0, 0);
      const d = ctx.createDelay(0.1);
      d.delayTime.value = 0.017;
      env.connect(d).connect(merger, 0, 1);
      merger.connect(out);
      for (const m of notes) {
        for (const det of [-7, 6]) {
          const o = osc(ctx, 'sawtooth', mtof(m), T, T + 3.6);
          o.detune.value = det;
          o.connect(lp);
        }
      }
      const bells = [74, 78, 81, 86];
      bells.forEach((m, i) => bell(ctx, out, T + 0.3 + i * 0.13, mtof(m), 1.6, 0.18));
    },
  },
  objective: {
    dur: 1.9, vars: 1, vol: 0.45, maxDist: 10, rolloff: 1, jitter: 0, wet: 0.3, prio: 4,
    build(ctx, out) {
      bell(ctx, out, T, mtof(69), 1.2, 0.5);
      bell(ctx, out, T + 0.18, mtof(74), 1.5, 0.55);
      const o = osc(ctx, 'sine', mtof(50), T, T + 1.6);
      const g = gain(ctx, 0);
      seg(g.gain, T, [[0, 0], [0.3, 0.15], [1.5, 0]]);
      o.connect(g).connect(out);
    },
  },

  // --- UI ------------------------------------------------------------------
  ui_click: {
    dur: 0.08, vars: 2, vol: 0.36, maxDist: 5, rolloff: 1, jitter: 0.03, rate: 3, prio: 0,
    build(ctx, out) {
      const o = osc(ctx, 'sine', 1500, T, T + 0.06);
      o.frequency.setValueAtTime(rand(1450, 1600), T);
      o.frequency.exponentialRampToValueAtTime(900, T + 0.03);
      o.connect(envGain(ctx, T, 0.002, 0.04, 0.8)).connect(out);
      burst(ctx, out, T, { dur: 0.005, type: 'highpass', f: 4000, amp: 0.15 });
    },
  },
  ui_hover: {
    dur: 0.05, vars: 1, vol: 0.18, maxDist: 5, rolloff: 1, jitter: 0.02, rate: 2, prio: 0,
    build(ctx, out) {
      osc(ctx, 'sine', 2400, T, T + 0.04).connect(envGain(ctx, T, 0.002, 0.025, 0.6)).connect(out);
    },
  },
  ui_open: {
    dur: 0.25, vars: 1, vol: 0.32, maxDist: 5, rolloff: 1, jitter: 0.01, rate: 2, prio: 0,
    build(ctx, out) {
      osc(ctx, 'sine', 660, T, T + 0.12).connect(envGain(ctx, T, 0.004, 0.08, 0.6)).connect(out);
      osc(ctx, 'sine', 990, T + 0.05, T + 0.2).connect(envGain(ctx, T + 0.05, 0.004, 0.1, 0.55)).connect(out);
      burst(ctx, out, T, { dur: 0.12, type: 'bandpass', f: 1500, f1: 3500, Q: 1, amp: 0.12, attack: 0.04 });
    },
  },
  ui_close: {
    dur: 0.25, vars: 1, vol: 0.3, maxDist: 5, rolloff: 1, jitter: 0.01, rate: 2, prio: 0,
    build(ctx, out) {
      osc(ctx, 'sine', 990, T, T + 0.12).connect(envGain(ctx, T, 0.004, 0.07, 0.55)).connect(out);
      osc(ctx, 'sine', 620, T + 0.05, T + 0.2).connect(envGain(ctx, T + 0.05, 0.004, 0.1, 0.55)).connect(out);
      burst(ctx, out, T, { dur: 0.12, type: 'bandpass', f: 3500, f1: 1400, Q: 1, amp: 0.1, attack: 0.03 });
    },
  },
  ui_error: {
    dur: 0.3, vars: 1, vol: 0.13, maxDist: 5, rolloff: 1, jitter: 0, rate: 2, prio: 0,
    build(ctx, out) {
      const lp = bq(ctx, 'lowpass', 900, 0.7);
      lp.connect(out);
      for (let i = 0; i < 2; i++) {
        const t = T + i * 0.13;
        const o = osc(ctx, 'square', 150, t, t + 0.11);
        const g = gain(ctx, 0);
        seg(g.gain, t, [[0, 0], [0.005, 0.5], [0.08, 0.45], [0.1, 0]]);
        o.connect(g).connect(lp);
      }
    },
  },

  // --- environment / misc --------------------------------------------------
  thunder: {
    dur: 7.5, vars: 3, vol: 0.9, maxDist: 2000, rolloff: 0.05, jitter: 0.05, rate: 1, maxVoices: 2, wet: 0.2, prio: 5, stereo: true,
    build(ctx, out, v) {
      const merger = ctx.createChannelMerger(2);
      merger.connect(out);
      const close = v === 0;
      if (close) {
        const drv = shaper(ctx, 3);
        drv.connect(out);
        burst(ctx, drv, T, { dur: 0.18, type: 'highpass', f: 900, amp: 0.8 });
        crackles(ctx, out, T, 0.3, 20, 1500, 6000, 0.6);
      }
      const start = T + (close ? 0.05 : rand(0.1, 0.4));
      for (let ch = 0; ch < 2; ch++) {
        for (const [color, f, a] of [['brown', 220, 1], ['pink', 600, 0.35]] as const) {
          const n = noise(ctx, color, start, start + 7);
          const lp = bq(ctx, 'lowpass', f, 0.7);
          const g = gain(ctx, 0);
          const N = 120;
          const c = new Float32Array(N);
          let lvl = 0;
          for (let i = 0; i < N; i++) {
            const x = i / (N - 1);
            const env = Math.min(1, x * 12) * Math.pow(1 - x, 1.6);
            if (Math.random() < 0.12) lvl = rand(0.5, 1);
            lvl *= 0.93;
            c[i] = a * env * (0.35 + lvl);
          }
          c[N - 1] = 0;
          g.gain.setValueCurveAtTime(c, start, 6.8);
          n.connect(lp).connect(g).connect(merger, 0, ch);
        }
      }
    },
  },
  sleep: {
    dur: 3.4, vars: 1, vol: 0.45, maxDist: 5, rolloff: 1, jitter: 0, wet: 0.4, prio: 5, stereo: true,
    build(ctx, out) {
      const notes = [81, 77, 74, 69];
      notes.forEach((m, i) => bell(ctx, out, T + i * 0.38, mtof(m), 1.8, 0.3));
      const g = gain(ctx, 0);
      seg(g.gain, T, [[0, 0], [1.2, 0.25], [3.2, 0]]);
      osc(ctx, 'sine', mtof(50), T, T + 3.3).connect(g);
      osc(ctx, 'sine', mtof(57), T, T + 3.3).connect(g);
      g.connect(out);
    },
  },
  fire_ignite: {
    dur: 1.5, vars: 2, vol: 0.4, maxDist: 25, rolloff: 1.3, wet: 0.08, prio: 4,
    build(ctx, out) {
      burst(ctx, out, T, { dur: 0.4, color: 'pink', type: 'lowpass', f: 300, f1: 3000, amp: 0.7, attack: 0.25 });
      burst(ctx, out, T + 0.1, { dur: 0.9, color: 'brown', type: 'lowpass', f: 450, amp: 0.8, attack: 0.2 });
      crackles(ctx, out, T + 0.3, 1.0, 16, 1500, 6000, 0.4);
    },
  },
  splash: {
    dur: 1.0, vars: 3, vol: 0.7, maxDist: 30, rolloff: 1.2, wet: 0.08, prio: 4,
    build(ctx, out) {
      burst(ctx, out, T, { dur: 0.3, type: 'bandpass', f: 1600, f1: 380, Q: 0.8, amp: 1, attack: 0.005 });
      burst(ctx, out, T, { dur: 0.12, color: 'brown', type: 'lowpass', f: 300, amp: 0.6 });
      for (let i = 0; i < 10; i++) {
        const t = T + rand(0.05, 0.6);
        const f = rand(300, 900);
        const o = osc(ctx, 'sine', f, t, t + 0.08);
        o.frequency.setValueAtTime(f, t);
        o.frequency.exponentialRampToValueAtTime(f * rand(1.8, 2.6), t + rand(0.03, 0.06));
        o.connect(envGain(ctx, t, 0.003, 0.05, rand(0.05, 0.2))).connect(out);
      }
    },
  },
  chop: {
    dur: 0.6, vars: 4, vol: 0.8, maxDist: 40, rolloff: 1.1, rate: 3, wet: 0.15, prio: 3,
    build(ctx, out, v) {
      click(ctx, out, T, 0.7, 2000);
      modal(ctx, out, T, [[rand(200, 260), 0.1, 1], [rand(520, 600), 0.06, 0.6], [rand(1100, 1300), 0.04, 0.3]]);
      thump(ctx, out, T, 110, 50, 0.1, 0.8);
      crackles(ctx, out, T + 0.005, 0.06, 6, 1500, 5000, 0.3);
      if (v === 3) crackles(ctx, out, T + 0.03, 0.15, 10, 1000, 4000, 0.35);
    },
  },
  mine_hit: {
    dur: 0.6, vars: 4, vol: 0.8, maxDist: 40, rolloff: 1.1, rate: 3, wet: 0.15, prio: 3,
    build(ctx, out) {
      modal(ctx, out, T, [[rand(2200, 2600), 0.12, 0.6], [rand(3700, 4200), 0.08, 0.4], [rand(1300, 1500), 0.1, 0.3]]);
      const drv = shaper(ctx, 3);
      drv.connect(out);
      burst(ctx, drv, T, { dur: 0.06, type: 'bandpass', f: 1200, Q: 1, amp: 0.8 });
      crackles(ctx, out, T + 0.01, 0.2, 10, 800, 3000, 0.25);
      thump(ctx, out, T, 90, 45, 0.08, 0.6);
    },
  },
};

function zapInto(ctx: Ctx, out: AudioNode, t: number, dur: number, amp: number): void {
  const end = t + dur + 0.05;
  const o = osc(ctx, 'sawtooth', 120, t, end);
  o.frequency.setValueCurveAtTime(contour([90, 200, 120], Math.floor(dur * 120), 0.6, 0.3, 30), t, dur);
  const drv = shaper(ctx, 10);
  const bp = bq(ctx, 'bandpass', 2500, 0.7);
  const am = gain(ctx, 0);
  const N = Math.floor(dur * 160);
  const c = new Float32Array(N);
  for (let i = 0; i < N; i++) {
    const x = i / (N - 1);
    c[i] = (Math.random() < 0.55 ? rand(0.4, 1) : 0) * amp * Math.min(1, (1 - x) * 4);
  }
  c[N - 1] = 0;
  am.gain.setValueCurveAtTime(c, t, dur);
  o.connect(drv).connect(bp).connect(am).connect(out);
  const n = noise(ctx, 'white', t, end);
  const hp = bq(ctx, 'highpass', 4000);
  const am2 = gain(ctx, 0);
  am2.gain.setValueCurveAtTime(c.map((x) => x * 0.6), t, dur);
  n.connect(hp).connect(am2).connect(out);
}

// ---------------------------------------------------------------------------
// internal buffers for ambience and music
// ---------------------------------------------------------------------------
export interface InternalDef { dur: number; vars: number; prio: number; stereo?: boolean; build: Recipe }

export const INTERNAL: Record<InternalName, InternalDef> = {
  amb_owl: {
    dur: 2.6, vars: 2, prio: 6,
    build(ctx, out) {
      const lp = bq(ctx, 'lowpass', 1200);
      lp.connect(out);
      const f = rand(360, 400);
      const pattern: Array<[number, number, number]> = [[0, 0.22, 1], [0.35, 0.18, 0.7], [0.8, 0.55, 1]];
      for (const [dt, d, a] of pattern) {
        const t = T + dt;
        const o = osc(ctx, 'sine', f, t, t + d + 0.1);
        o.frequency.setValueAtTime(f * 1.04, t);
        o.frequency.linearRampToValueAtTime(f * 0.94, t + d);
        const o2 = osc(ctx, 'sine', f * 2, t, t + d + 0.1);
        const g2 = gain(ctx, 0.08);
        o2.connect(g2);
        const g = gain(ctx, 0);
        seg(g.gain, t, [[0, 0], [d * 0.3, a], [d, 0]]);
        o.connect(g);
        g2.connect(g);
        g.connect(lp);
        burst(ctx, lp, t, { dur: d, color: 'pink', type: 'bandpass', f: f, Q: 4, amp: 0.15 * a, attack: d * 0.3 });
      }
    },
  },
  amb_dog: {
    dur: 3.8, vars: 2, prio: 6,
    build(ctx, out) {
      const bus = echoes(ctx, out, [[0.45, 0.25, 900], [1.1, 0.12, 600]]);
      const lp = bq(ctx, 'lowpass', 1600);
      lp.connect(bus);
      const d = rand(2.2, 2.8);
      const f0 = rand(420, 500);
      const o = osc(ctx, 'triangle', f0, T, T + d + 0.1);
      o.frequency.setValueCurveAtTime(contour([f0, f0 * 1.5, f0 * 1.45, f0 * 1.2], 60, 0.008, 0.9, 50), T, d);
      const vib = osc(ctx, 'sine', 5, T, T + d + 0.1);
      const vg = gain(ctx, 6);
      vib.connect(vg).connect(o.frequency);
      const g = gain(ctx, 0);
      seg(g.gain, T, [[0, 0], [0.35, 0.8], [d - 0.6, 0.7], [d, 0]]);
      const f = bq(ctx, 'peaking', 900, 2, 6);
      o.connect(f).connect(g).connect(lp);
    },
  },
  amb_bird: {
    dur: 1.6, vars: 4, prio: 6,
    build(ctx, out) {
      const base = rand(2600, 4200);
      const n = randi(3, 7);
      let t = T;
      const trill = Math.random() < 0.35;
      for (let i = 0; i < n; i++) {
        const d = rand(0.05, 0.12);
        const o = osc(ctx, 'sine', base, t, t + d + 0.02);
        o.frequency.setValueAtTime(base * rand(0.8, 1), t);
        o.frequency.exponentialRampToValueAtTime(base * rand(1.2, 1.6), t + d * 0.5);
        o.frequency.exponentialRampToValueAtTime(base * rand(0.7, 1), t + d);
        const g = gain(ctx, 0);
        seg(g.gain, t, [[0, 0], [d * 0.2, rand(0.5, 1)], [d, 0]]);
        if (trill) {
          const am = osc(ctx, 'sine', rand(25, 40), t, t + d + 0.02);
          const ag = gain(ctx, 0.5);
          am.connect(ag).connect(g.gain);
        }
        o.connect(g).connect(out);
        t += d + rand(0.03, 0.12);
      }
    },
  },
  amb_scream: {
    dur: 4.5, vars: 2, prio: 7,
    build(ctx, out) {
      const conv = ctx.createConvolver();
      conv.buffer = farImpulse(ctx.sampleRate);
      const wet = gain(ctx, 0.8);
      const lp = bq(ctx, 'lowpass', 1400);
      const dry = gain(ctx, 0.35);
      lp.connect(dry).connect(out);
      lp.connect(conv).connect(wet).connect(out);
      voice(ctx, lp, T, rand(1.6, 2.2), {
        f0: [rand(280, 320), rand(500, 560), rand(470, 520), rand(330, 380)], jitter: 0.04, breath: 0.4,
        vowels: [[800, 1250, 2700], [600, 1900, 2900], [700, 1300, 2600]], gurgle: 0.15, gurgleRate: 7, drive: 3, attack: 0.1, release: 0.6, sub: 0.1,
      });
    },
  },
  amb_gunshot: {
    dur: 4.5, vars: 2, prio: 7,
    build(ctx, out) {
      const lp = bq(ctx, 'lowpass', 800);
      const bus = echoes(ctx, out, [[0.6, 0.4, 600], [1.4, 0.25, 450], [2.3, 0.12, 350]]);
      lp.connect(bus);
      thump(ctx, lp, T, 120, 40, 0.3, 1);
      burst(ctx, lp, T, { dur: 0.05, type: 'bandpass', f: 900, Q: 0.6, amp: 0.8 });
      burst(ctx, lp, T, { dur: 0.4, color: 'brown', type: 'lowpass', f: 250, amp: 0.8 });
      burst(ctx, out, T + 0.05, { dur: 2.5, color: 'pink', type: 'lowpass', f: 600, f1: 200, amp: 0.2, attack: 0.1 });
    },
  },
  amb_creak: {
    dur: 3.4, vars: 2, prio: 7,
    build(ctx, out) {
      const d = rand(2.0, 3.0);
      creak(ctx, out, T, d, rand(8, 14), rand(20, 35), 0.8, [rand(400, 480), rand(1100, 1250), rand(2200, 2500)], 25);
      const o = osc(ctx, 'sine', 90, T, T + d);
      o.frequency.setValueCurveAtTime(contour([85, 95, 80], 30, 0.03, 0.8, 30), T, d);
      const g = gain(ctx, 0);
      seg(g.gain, T, [[0, 0], [d * 0.4, 0.12], [d, 0]]);
      o.connect(g).connect(out);
    },
  },
  amb_horde: {
    dur: 5, vars: 2, prio: 7,
    build(ctx, out) {
      const conv = ctx.createConvolver();
      conv.buffer = farImpulse(ctx.sampleRate);
      const lp = bq(ctx, 'lowpass', 700);
      const dry = gain(ctx, 0.4);
      lp.connect(dry).connect(out);
      lp.connect(conv).connect(gain(ctx, 0.9)).connect(out);
      for (let i = 0; i < 6; i++) {
        voice(ctx, lp, T + rand(0, 1.5), rand(1.5, 2.5), {
          f0: [rand(65, 90), rand(80, 110), rand(55, 75)], jitter: 0.05, breath: 0.35, vowels: groanVowels(),
          gurgle: 0.6, gurgleRate: rand(6, 11), drive: 3, attack: 0.4, release: 0.7, amp: rand(0.5, 1),
        });
      }
    },
  },
  amb_drop: {
    dur: 0.08, vars: 6, prio: 5,
    build(ctx, out) {
      const f = rand(1800, 4200);
      const o = osc(ctx, 'sine', f, T, T + 0.04);
      o.frequency.setValueAtTime(f, T);
      o.frequency.exponentialRampToValueAtTime(f * rand(0.5, 0.7), T + 0.02);
      o.connect(envGain(ctx, T, 0.001, 0.02, 0.5)).connect(out);
      burst(ctx, out, T, { dur: 0.004, type: 'highpass', f: 3000, amp: rand(0.3, 0.8) });
    },
  },
  m_taiko: {
    dur: 1.1, vars: 2, prio: 5,
    build(ctx, out) {
      thump(ctx, out, T, 95, 48, 0.55, 1);
      burst(ctx, out, T, { dur: 0.08, type: 'lowpass', f: 1200, amp: 0.4 });
      burst(ctx, out, T, { dur: 0.4, type: 'bandpass', f: 140, Q: 3, amp: 0.7 });
    },
  },
  m_kick: {
    dur: 0.6, vars: 1, prio: 5,
    build(ctx, out) {
      thump(ctx, out, T, 65, 34, 0.38, 1);
      burst(ctx, out, T, { dur: 0.1, color: 'brown', type: 'lowpass', f: 150, amp: 0.4 });
    },
  },
  m_tick: {
    dur: 0.1, vars: 2, prio: 5,
    build(ctx, out) {
      modal(ctx, out, T, [[rand(5000, 5400), 0.02, 0.6], [rand(7100, 7500), 0.015, 0.4], [2600, 0.01, 0.2]]);
    },
  },
  m_clank: {
    dur: 0.5, vars: 2, prio: 5,
    build(ctx, out) {
      modal(ctx, out, T, [[rand(300, 340), 0.15, 0.7], [rand(840, 900), 0.1, 0.5], [rand(1500, 1700), 0.06, 0.3]]);
      burst(ctx, out, T, { dur: 0.1, type: 'bandpass', f: 1800, Q: 0.8, amp: 0.6 });
      thump(ctx, out, T, 160, 80, 0.06, 0.5);
    },
  },
  m_string: {
    dur: 0.45, vars: 2, prio: 5,
    build(ctx, out) {
      ostring(ctx, out, T, mtof(50), 0.22, 0.8);
    },
  },
  m_braam: {
    dur: 3.4, vars: 1, prio: 6,
    build(ctx, out) {
      const lp = bq(ctx, 'lowpass', 200, 2);
      lp.frequency.setValueAtTime(200, T);
      lp.frequency.exponentialRampToValueAtTime(1300, T + 0.4);
      lp.frequency.exponentialRampToValueAtTime(260, T + 3);
      const drv = shaper(ctx, 2);
      const env = gain(ctx, 0);
      seg(env.gain, T, [[0, 0], [0.12, 0.8], [1.2, 0.6], [3.2, 0]]);
      for (const m of [26, 38, 45]) {
        for (const det of [-10, 0, 9]) {
          const o = osc(ctx, 'sawtooth', mtof(m), T, T + 3.3);
          o.detune.value = det;
          o.connect(lp);
        }
      }
      lp.connect(drv).connect(env).connect(out);
      thump(ctx, out, T, 70, 30, 0.8, 0.8);
    },
  },
};

const farIRCache = new Map<number, AudioBuffer>();
function farImpulse(sr: number): AudioBuffer {
  const hit = farIRCache.get(sr);
  if (hit) return hit;
  // inline import avoided: simple mono-ish long tail
  const len = Math.floor(sr * 3);
  const b = new AudioBuffer({ length: len, sampleRate: sr, numberOfChannels: 1 });
  const d = b.getChannelData(0);
  let lp = 0;
  for (let i = 0; i < len; i++) {
    const t = i / sr;
    lp += 0.15 * (Math.random() * 2 - 1 - lp);
    d[i] = lp * Math.pow(10, -3 * t / 3) * Math.min(1, t / 0.02);
  }
  farIRCache.set(sr, b);
  return b;
}
