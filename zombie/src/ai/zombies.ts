// The dead. Director + per-zombie AI: senses (sight, hearing, the smell of blood), a day/night rhythm, loose
// packs, a life cycle (fresh -> shambler -> husk), special strains, and hunting of every living thing — the
// player and any human pose flagged as prey. Rendering goes through the shared rig (bodyRig / zombieMesh).
import * as THREE from 'three';
import { RNG, clamp, angleDiff, HALF_WORLD } from '../core/util';
import { World } from '../world/world';
import { NAV_N, NAV_SOLID, NAV_FREE } from '../world/collision';
import { BodyRenderer } from './zombieMesh';
import { ACT, TOOL, ZACT, KIND, makeDef, refreshDef, defStamp, newAnim, randomLook as rigRandomLook, lookFromSeed, gaitFor, strideLen, hash1 } from './bodyRig';
import type { BodyDef, HumanLook } from './bodyRig';
import { TOWN } from '../world/layout';
import { Inventory } from '../items/inventory';
import { rollLoot } from '../items/loot';
import { audio } from '../audio/Audio';

export { ACT, TOOL };
export type { HumanLook };
/** a random living person's look (skin / clothes / hair colours, build, height, hair style) */
export function randomLook(rng: RNG): HumanLook { return rigRandomLook(rng); }

/** 0 walker, 1 runner, 2 brute, 3 howler, 4 bloat, 5 breaker */
export type ZType = 0 | 1 | 2 | 3 | 4 | 5;
export const ZTYPE = { walker: 0, runner: 1, brute: 2, howler: 3, bloat: 4, breaker: 5 } as const;
export const ZTYPE_NAMES = ['Walker', 'Runner', 'Brute', 'Howler', 'Bloat', 'Breaker'] as const;
export type ZState = 'idle' | 'wander' | 'investigate' | 'chase' | 'attack' | 'bash' | 'stagger' | 'dead'
  | 'dormant' | 'feed' | 'search' | 'scream' | 'down' | 'rise';
export type ZStage = 'fresh' | 'shambler' | 'husk';

export interface Zombie {
  id: number; type: ZType; pos: THREE.Vector3; vel: THREE.Vector3; yaw: number; hp: number; maxHp: number;
  state: ZState; target: THREE.Vector3; stateT: number; phase: number; move: number; attackT: number; atkBlend: number;
  hit: number; arms: number; death: number; deathDir: number; seed: number; scale: number; lean: number; limp: number;
  speed: number; runSpeed: number; senseT: number; alerted: number; lastSeen: THREE.Vector3; horde: number; groanT: number;
  bashRef: any; corpse: Inventory | null; corpseAge: number; frozen: boolean; stuckT: number; lastPos: THREE.Vector3; knock: THREE.Vector3;
  lookYaw: number; lookPitch: number; hitSide: number; crawl: number; crawler: boolean;
  // ---- life cycle
  /** in-game days since it turned */
  age: number;
  /** 0 fresh (fast, strong) .. 1 husk (slow, brittle); drives speed, hp and look */
  decay: number;
  /** who this was, if we know */
  name?: string;
  /** true if it was one of the living we watched die */
  turned?: boolean;
  def: BodyDef;
  // ---- hunting
  /** the human it is after; null while hunting = the player */
  prey: HumanPose | null;
  // ---- animation / internals
  hitFwd: number; lunge: number; atkT: number; atkPeriod: number; struck: boolean; pivot: boolean; turnAmt: number; avoid: number; avoidT: number;
  down: number; downMode: number; downFace: number; downOff: number; downT: number; lieT: number; riseDur: number; after: ZState;
  act: number; actW: number; act2: number; act2W: number; aux: number; deathStyle: number;
  lying: boolean; sated: number; feedAt: Carrion | null; enraged: number; screamCd: number; screamed: boolean; scanT: number; ageT: number; ageAcc: number;
  baseHp: number; baseSpeed: number; baseRun: number; dmgMul: number; burst: boolean; idleAct: number; pause: number; wakeT: number; fwdSpeed: number; still: number; settle: number; ignore: HumanPose | null; ignoreT: number;
}
export interface HumanPose {
  pos: THREE.Vector3; yaw: number; move: number; phase: number; arms: number; atk: number; seed: number; type: 3 | 4; hit: number; visible: boolean; lookYaw?: number; lookPitch?: number;
  /** appearance; humans without one get a stable look from their seed */
  look?: HumanLook;
  /** ACT.* body pose, cross-faded */
  act?: number;
  /** TOOL.* shown in the right hand */
  tool?: number;
  /** 0..1 infection: pallor, hunched, unsteady */
  sick?: number;
  /** zombies perceive and attack humans with prey === true */
  prey?: boolean;
  owner?: unknown; name?: string;
}
export interface Horde { id: number; target: THREE.Vector3; members: number; t: number; toBase: boolean }
export interface Carrion { pos: THREE.Vector3; until: number; feeders: number; pose: HumanPose | null }

const MAX = 700;
const FIELD = 112;          // flow field window (cells, 1 m)
const TWO_PI = Math.PI * 2;
const TYPE_SCALE = [1, 1, 1.24, 1.13, 0.98, 1.08];
/** base stats per type: hp range, walk range, run range, damage to people, damage to structures, turn rate, attack wind-up */
const STATS: { hp: [number, number]; walk: [number, number]; run: [number, number]; dmg: number; wreck: number; turn: number; wind: number; period: number }[] = [
  { hp: [90, 130], walk: [0.7, 1.3], run: [1.7, 2.6], dmg: 9, wreck: 9, turn: 2.6, wind: 0.42, period: 1.3 },
  { hp: [80, 92], walk: [1.0, 1.4], run: [4.6, 5.6], dmg: 8, wreck: 6, turn: 4.4, wind: 0.3, period: 1.1 },
  { hp: [410, 440], walk: [0.95, 1.05], run: [2.5, 2.7], dmg: 25, wreck: 45, turn: 2.0, wind: 0.62, period: 1.6 },
  { hp: [50, 62], walk: [1.0, 1.2], run: [2.9, 3.3], dmg: 6, wreck: 4, turn: 3.2, wind: 0.4, period: 1.4 },
  { hp: [160, 190], walk: [0.55, 0.7], run: [1.2, 1.4], dmg: 8, wreck: 6, turn: 1.5, wind: 0.5, period: 1.7 },
  { hp: [250, 280], walk: [0.85, 1.0], run: [2.2, 2.5], dmg: 22, wreck: 85, turn: 2.0, wind: 0.75, period: 1.9 },
];

export interface ZombieHost {
  playerPos: THREE.Vector3; playerEye: THREE.Vector3; playerNoise: number; playerVisibility: number; playerAlive: boolean; playerInside: boolean;
  onPlayerAttacked(z: Zombie): void;
  onStructureHit(ref: any, dmg: number, z: Zombie): void;
  onKill(z: Zombie, byPlayer: boolean): void;
  baseCenter: THREE.Vector3 | null; baseHeat: number; daylight: number; hours: number; day: number; luck: number;
  /** optional: the player has an open wound — the dead smell it from a long way off */
  playerBleeding?: boolean;
}

interface HState { def: BodyDef; look: HumanLook | undefined; act: number; actW: number; act2: number; act2W: number; hunters: number }

export class Zombies {
  list: Zombie[] = [];
  hordes: Horde[] = [];
  mesh: THREE.InstancedMesh;
  private bodies: BodyRenderer; private anim = newAnim();
  private hstate = new WeakMap<HumanPose, HState>();
  private time = { value: 0 };
  private rng: RNG;
  private nextId = 1;
  private grid = new Map<number, Zombie[]>();
  // flow field
  private dist = new Uint16Array(FIELD * FIELD);
  private fieldOX = 0; private fieldOZ = 0; private fieldT = 0;
  private fieldValid = false;
  private hordeT = 0; private respawnT = 0; private nextHordeId = 1;
  kills = 0;
  private tmpV = new THREE.Vector3(); private tmpV2 = new THREE.Vector3();
  noiseEvents: { pos: THREE.Vector3; r: number }[] = [];
  /** non-zombie humans drawn with the same GPU body (player avatar in third person, survivors) */
  humans: HumanPose[] = [];
  /** things the dead will kneel and feed on (bodies of the living are picked up automatically) */
  carrion: Carrion[] = [];
  private packs = new Map<number, { x: number; z: number; t: number }>();
  private prey: HumanPose[] = [];
  private carrionT = 0; private dayNow = 1; private lastDt = 0.016;
  private lastRay: { z: Zombie; part: number; side: number; at: number } | null = null;
  host: ZombieHost | null = null;

  // ---- hooks (all optional)
  onHorde: ((n: number, toBase: boolean) => void) | null = null;
  /** a zombie's blow landed on a human pose flagged as prey; apply damage / bites on your side */
  onHumanAttacked: ((pose: HumanPose, z: Zombie, dmg: number) => void) | null = null;
  /** a bloat ruptured: hurt the player / people / anything else within radius */
  onBurst: ((pos: THREE.Vector3, radius: number, z: Zombie) => void) | null = null;
  /** a howler screamed, calling everything within radius */
  onHowl: ((pos: THREE.Vector3, radius: number, z: Zombie) => void) | null = null;

  constructor(scene: THREE.Scene, private world: World, seed: number) {
    this.rng = new RNG(seed + 31);
    this.bodies = new BodyRenderer(scene, MAX);
    this.mesh = this.bodies.mesh;
  }

  // ------------------------------------------------------------------ spawning
  populate() {
    const rng = this.rng;
    // town population
    for (const b of this.world.town.buildings) {
      const inTown = b.x > TOWN.x0 && b.x < TOWN.x1 && b.z > TOWN.z0 && b.z < TOWN.z1;
      if (b.kind === 'cabin' && Math.hypot(b.x + 176, b.z + 206) < 10) continue; // keep the spawn cabin clear
      for (const s of b.zombieSpawns) if (rng.chance(inTown ? 0.55 : 0.3)) this.spawn(s.x + rng.range(-1, 1), s.z + rng.range(-1, 1), rng.chance(0.08) ? 1 : 0);
    }
    for (let i = 0; i < 170; i++) {
      const x = rng.range(TOWN.x0, TOWN.x1), z = rng.range(TOWN.z0, TOWN.z1);
      if (this.world.town.isOccupied(x, z)) continue;
      this.spawn(x, z, rng.chance(0.1) ? 1 : rng.chance(0.03) ? 2 : 0);
    }
    // wilderness stragglers
    for (let i = 0; i < 110; i++) {
      const x = rng.range(-HALF_WORLD + 40, HALF_WORLD - 40), z = rng.range(-HALF_WORLD + 40, HALF_WORLD - 40);
      if (Math.hypot(x + 170, z + 200) < 60) continue;
      this.spawn(x, z, rng.chance(0.15) ? 1 : 0);
    }
  }

  /** which strain appears: the special ones creep in as the days pass */
  pickType(day: number, siege = false): ZType {
    const r = this.rng.next(); let a = 0;
    const w: [ZType, number][] = [
      [1, Math.min(0.2, 0.09 + day * 0.008)],
      [2, Math.min(0.08, 0.025 + day * 0.004)],
      [3, day >= 3 ? Math.min(0.07, 0.02 + (day - 3) * 0.006) : 0],
      [4, day >= 5 ? Math.min(0.08, 0.02 + (day - 5) * 0.006) : 0],
      [5, day >= 8 ? Math.min(0.06, 0.015 + (day - 8) * 0.005) * (siege ? 2 : 1) : 0],
    ];
    for (const [t, p] of w) { a += p; if (r < a) return t; }
    return 0;
  }

  /**
   * Spawn a zombie. opts.decay sets how far gone it is (default: mostly shamblers, some husks, a few fresh);
   * opts.look / opts.seed pin its appearance.
   */
  spawn(x: number, z: number, type: ZType, horde = 0, opts?: { decay?: number; seed?: number; look?: HumanLook; name?: string }): Zombie | null {
    if (this.list.length >= MAX - 5) return null;
    const rng = this.rng;
    if (!(type >= 0 && type <= 5)) type = 0;
    const y = this.world.groundY(x, z);
    const st = STATS[type];
    const seed = opts?.seed ?? rng.next() * 100;
    const r0 = rng.next();
    let decay = opts?.decay ?? (r0 < 0.05 ? rng.range(0, 0.15) : r0 < 0.24 ? rng.range(0.78, 1) : rng.range(0.25, 0.72));
    if (opts?.decay === undefined && type >= 2) decay = rng.range(0.3, 0.6);
    if (opts?.decay === undefined && type === 1) decay = rng.range(0.05, 0.4);
    const def = makeDef(type, seed, opts?.look ?? lookFromSeed(seed));
    def.gait = gaitFor(type, seed); def.decay = decay;
    if (type === 0 && decay > 0.4 && rng.chance(0.07)) { if (rng.chance(0.5)) def.armLossL = true; else def.armLossR = true; }
    const zb: Zombie = {
      id: this.nextId++, type, pos: new THREE.Vector3(x, y, z), vel: new THREE.Vector3(), yaw: rng.range(0, 6.28), hp: 1, maxHp: 1,
      state: 'idle', target: new THREE.Vector3(x, y, z), stateT: rng.range(0, 5), phase: rng.range(0, 6.28), move: 0, attackT: 0, atkBlend: 0,
      hit: 0, arms: 0, death: 0, deathDir: 1, seed, scale: def.height * TYPE_SCALE[type], lean: def.lean, limp: def.gait === 2 ? 1 : rng.chance(0.25) ? rng.range(0.3, 0.7) : 0,
      speed: 1, runSpeed: 2, senseT: rng.range(0, 0.5), alerted: 0, lastSeen: new THREE.Vector3(x, y, z), horde, groanT: rng.range(2, 12), bashRef: null, corpse: null, corpseAge: 0,
      frozen: false, stuckT: 0, lastPos: new THREE.Vector3(x, y, z), knock: new THREE.Vector3(),
      lookYaw: 0, lookPitch: 0, hitSide: 0, crawl: 0, crawler: false,
      age: decayToAge(decay), decay, name: opts?.name, turned: false, def, prey: null,
      hitFwd: 1, lunge: 0, atkT: 0, atkPeriod: st.period, struck: false, pivot: false, turnAmt: 0, avoid: 0, avoidT: 0,
      down: 0, downMode: 0, downFace: 1, downOff: 0, downT: 0, lieT: 0, riseDur: 2, after: 'idle',
      act: 0, actW: 0, act2: 0, act2W: 0, aux: 0, deathStyle: 0,
      lying: false, sated: 0, feedAt: null, enraged: 0, screamCd: rng.range(0, 6), screamed: false, scanT: rng.range(0, 2), ageT: rng.range(2, 12), ageAcc: 0,
      baseHp: rng.range(st.hp[0], st.hp[1]), baseSpeed: rng.range(st.walk[0], st.walk[1]), baseRun: rng.range(st.run[0], st.run[1]), dmgMul: 1, burst: false,
      idleAct: 0, pause: 4 + rng.range(0, 6), wakeT: 0, fwdSpeed: 0, still: 0, settle: 30, ignore: null, ignoreT: 0,
    };
    this.applyDecay(zb, true);
    zb.hp = zb.maxHp;
    this.list.push(zb);
    return zb;
  }

  /**
   * A human who died infected gets back up where they fell: same face, clothes and build, skin gone grey.
   * It starts flat on its back and rises over a few seconds — fresh, fast and strong.
   */
  spawnTurned(pose: HumanPose): Zombie | null {
    const look = pose.look ?? this.hstate.get(pose)?.look ?? lookFromSeed(pose.seed);
    const z = this.spawn(pose.pos.x, pose.pos.z, 0, 0, { decay: 0, seed: pose.seed, look, name: pose.name });
    if (!z) return null;
    z.turned = true; z.yaw = pose.yaw; z.pos.y = pose.pos.y; z.lastPos.copy(z.pos);
    z.limp = 0; z.def.gait = hash1(pose.seed * 6.13) < 0.5 ? 0 : 1; z.def.armLossL = z.def.armLossR = false; refreshDef(z.def);
    z.state = 'rise'; z.stateT = 0; z.down = 1; z.downMode = 1; z.downFace = 1; z.downOff = 0; z.lieT = this.rng.range(0.6, 1.4); z.riseDur = this.rng.range(2.4, 3.2); z.after = 'idle';
    z.pause = 0.5;
    return z;
  }

  spawnHorde(cx: number, cz: number, n: number, target: THREE.Vector3, toBase = false) {
    const h: Horde = { id: this.nextHordeId++, target: target.clone(), members: 0, t: 0, toBase };
    for (let i = 0; i < n; i++) {
      const a = this.rng.range(0, 6.28), r = Math.sqrt(this.rng.next()) * (6 + n * 0.12);
      const x = cx + Math.cos(a) * r, z = cz + Math.sin(a) * r;
      if (!this.world.inBounds(x, z)) continue;
      const zb = this.spawn(x, z, this.pickType(this.dayNow, toBase), h.id);
      if (zb) { zb.state = 'investigate'; zb.target.copy(target).add(new THREE.Vector3(this.rng.range(-8, 8), 0, this.rng.range(-8, 8))); h.members++; }
    }
    this.hordes.push(h);
    return h;
  }

  /** emit a sound that zombies may hear */
  noise(pos: THREE.Vector3, radius: number) { if (radius > 0.5) this.noiseEvents.push({ pos: pos.clone(), r: radius }); }
  /** mark something the dead will gather round and eat for a while (a kill, a dropped carcass) */
  addCarrion(pos: THREE.Vector3, seconds = 90) { this.carrion.push({ pos: pos.clone(), until: this.time.value + seconds, feeders: 0, pose: null }); }

  stage(z: Zombie): ZStage { return z.decay < 0.2 ? 'fresh' : z.decay < 0.75 ? 'shambler' : 'husk'; }
  /** short description for UI: "Howler", "husk", "what's left of Marcus" */
  describe(z: Zombie): string {
    if (z.name) return `what's left of ${z.name}`;
    if (z.type !== 0) return ZTYPE_NAMES[z.type];
    return z.decay < 0.2 ? 'Fresh walker' : z.decay < 0.75 ? 'Walker' : 'Husk';
  }

  /** stats and look follow decay: fresh are fast and strong, husks slow and brittle */
  private applyDecay(z: Zombie, init = false) {
    const d = z.decay, special = z.type >= 2;
    const fresh = 1 - smooth(0.05, 0.3, d), husk = smooth(0.62, 1, d);
    const k = special ? 0.4 : z.type === 1 ? 0.25 : 1;
    const sp = 1 + (0.4 * fresh - 0.38 * husk) * k, hpM = 1 + (0.25 * fresh - 0.5 * husk) * k;
    const ratio = init ? 1 : z.hp / Math.max(1, z.maxHp);
    z.maxHp = Math.round(z.baseHp * hpM); z.hp = init ? z.maxHp : Math.max(1, z.maxHp * ratio);
    z.speed = z.baseSpeed * (1 + (0.15 * fresh - 0.3 * husk) * k);
    // a fresh walker can break into a run; a husk can barely hurry
    z.runSpeed = z.baseRun * sp + (z.type === 0 ? fresh * 1.5 : 0);
    z.dmgMul = (1 + (0.3 * fresh - 0.3 * husk) * k) * (z.def.armLossL ? 0.65 : 1) * (z.def.armLossR ? 0.65 : 1);
    if (z.crawler) { z.speed = 0.45; z.runSpeed = 0.9 + fresh * 0.4; }
    z.def.decay = d;
    if (defStamp(z.def) !== z.def.stamp) refreshDef(z.def);
  }

  // ------------------------------------------------------------------ flow field (toward the player)
  private buildField(px: number, pz: number) {
    const col = this.world.col;
    const ox = Math.floor(px + HALF_WORLD) - FIELD / 2, oz = Math.floor(pz + HALF_WORLD) - FIELD / 2;
    this.fieldOX = ox; this.fieldOZ = oz;
    const dist = this.dist; dist.fill(65535);
    // Dial's algorithm with small integer costs
    const buckets: number[][] = []; const maxCost = 64;
    for (let i = 0; i <= maxCost; i++) buckets.push([]);
    const sx = Math.floor(px + HALF_WORLD) - ox, sz = Math.floor(pz + HALF_WORLD) - oz;
    if (sx < 0 || sz < 0 || sx >= FIELD || sz >= FIELD) return;
    dist[sz * FIELD + sx] = 0; buckets[0].push(sz * FIELD + sx);
    let cur = 0, processed = 0, empty = 0;
    const dx = [1, -1, 0, 0, 1, 1, -1, -1], dz = [0, 0, 1, -1, 1, -1, 1, -1], dc = [10, 10, 10, 10, 14, 14, 14, 14];
    while (empty <= maxCost && processed < FIELD * FIELD * 2) {
      const bk = buckets[cur % (maxCost + 1)];
      if (!bk.length) { empty++; cur++; continue; }
      empty = 0;
      const i = bk.pop()!; processed++;
      const d = dist[i]; if (d !== cur) continue;
      const cx = i % FIELD, cz = (i / FIELD) | 0;
      for (let k = 0; k < 8; k++) {
        const nx = cx + dx[k], nz = cz + dz[k];
        if (nx < 0 || nz < 0 || nx >= FIELD || nz >= FIELD) continue;
        const gx = ox + nx, gz = oz + nz; if (gx < 0 || gz < 0 || gx >= NAV_N || gz >= NAV_N) continue;
        const nv = col.nav[gz * NAV_N + gx];
        if (nv === NAV_SOLID) continue;
        if (k >= 4) { // no corner cutting
          if (col.nav[(oz + cz) * NAV_N + gx] === NAV_SOLID || col.nav[gz * NAV_N + ox + cx] === NAV_SOLID) continue;
        }
        const step = Math.round((dc[k] + (nv === NAV_FREE ? 0 : nv * 3)) / 10);
        const nd = d + Math.max(1, step);
        const ni = nz * FIELD + nx;
        if (nd < dist[ni]) { dist[ni] = nd; buckets[nd % (maxCost + 1)].push(ni); }
      }
    }
    this.fieldValid = true;
  }
  private fd = { ok: false, blockRef: null as any };
  /** desired direction from flow field */
  private flowDir(x: number, z: number, out: THREE.Vector3): { ok: boolean; blockRef: any } {
    const r = this.fd; r.ok = false; r.blockRef = null;
    if (!this.fieldValid) return r;
    const gx = Math.floor(x + HALF_WORLD), gz = Math.floor(z + HALF_WORLD);
    const cx = gx - this.fieldOX, cz = gz - this.fieldOZ;
    if (cx < 1 || cz < 1 || cx >= FIELD - 1 || cz >= FIELD - 1) return r;
    const here = this.dist[cz * FIELD + cx]; if (here === 65535) return r;
    let best = here, bx = 0, bz = 0;
    for (let k = -1; k <= 1; k++) for (let j = -1; j <= 1; j++) {
      if (!k && !j) continue;
      const d = this.dist[(cz + j) * FIELD + cx + k];
      if (d < best) { best = d; bx = k; bz = j; }
    }
    if (bx === 0 && bz === 0) return r;
    out.set(bx, 0, bz).normalize();
    // is the next cell a destructible?
    const ng = (gz + bz) * NAV_N + gx + bx;
    const v = this.world.col.nav[ng];
    r.ok = true; r.blockRef = v !== NAV_FREE && v !== NAV_SOLID ? this.world.col.navRef.get(ng) ?? null : null;
    return r;
  }
  private navAt(x: number, z: number) {
    const gx = Math.floor(x + HALF_WORLD), gz = Math.floor(z + HALF_WORLD);
    if (gx < 0 || gz < 0 || gx >= NAV_N || gz >= NAV_N) return NAV_SOLID;
    return this.world.col.nav[gz * NAV_N + gx];
  }
  private refAt(x: number, z: number) {
    const gx = Math.floor(x + HALF_WORLD), gz = Math.floor(z + HALF_WORLD);
    return this.world.col.navRef.get(gz * NAV_N + gx) ?? null;
  }
  /**
   * Straight-line pursuit with local avoidance (for targets the player-centred flow field can't serve):
   * probe the nav grid ahead and fan out to whichever side is clear. Returns a destructible that is squarely
   * in the way, if any, so the caller can decide to break through it.
   */
  private steer(z: Zombie, tx: number, tz: number, out: THREE.Vector3): any {
    let dx = tx - z.pos.x, dz = tz - z.pos.z; const d = Math.hypot(dx, dz);
    if (d < 1e-4) { out.set(0, 0, 0); return null; }
    dx /= d; dz /= d;
    const reach = Math.min(d, 1.5);
    let block: any = null;
    const n0 = this.navAt(z.pos.x + dx * Math.min(d, 0.8), z.pos.z + dz * Math.min(d, 0.8)), n1 = this.navAt(z.pos.x + dx * reach, z.pos.z + dz * reach);
    if (n0 === NAV_FREE && n1 === NAV_FREE) { out.set(dx, 0, dz); if (z.avoidT <= 0) z.avoid = 0; return null; }
    if (n0 !== NAV_SOLID && n0 !== NAV_FREE) block = this.refAt(z.pos.x + dx * Math.min(d, 0.8), z.pos.z + dz * Math.min(d, 0.8));
    else if (n0 === NAV_FREE && n1 !== NAV_SOLID) block = this.refAt(z.pos.x + dx * reach, z.pos.z + dz * reach);
    const a0 = Math.atan2(dx, dz);
    const first = z.avoid || (hash1(z.seed + Math.floor(this.time.value * 0.2)) < 0.5 ? 1 : -1);
    for (let k = 1; k <= 4; k++) for (let s = 0; s < 2; s++) {
      const sg = s === 0 ? first : -first;
      if (s === 1 && z.avoid !== 0 && k < 3) continue;       // stick with the side already chosen
      const a = a0 + sg * k * 0.55, ax = Math.sin(a), az = Math.cos(a);
      if (this.navAt(z.pos.x + ax * 0.8, z.pos.z + az * 0.8) === NAV_FREE && this.navAt(z.pos.x + ax * 1.5, z.pos.z + az * 1.5) === NAV_FREE) {
        out.set(ax, 0, az); z.avoid = sg; z.avoidT = 1.6; return block;
      }
    }
    out.set(dx, 0, dz);
    return block;
  }

  // ------------------------------------------------------------------ queries for combat
  /** hitscan against zombie capsules. part: 0 head, 1 torso, 2 arm, 3 legs */
  raycast(o: THREE.Vector3, d: THREE.Vector3, maxT: number): { z: Zombie; t: number; head: boolean; part: number } | null {
    let best: { z: Zombie; t: number; head: boolean; part: number } | null = null;
    for (const z of this.list) {
      if (z.state === 'dead' || z.frozen) continue;
      const s = z.scale;
      const ddx = z.pos.x - o.x, ddz = z.pos.z - o.z;
      if (ddx * ddx + ddz * ddz > (maxT + 3) * (maxT + 3)) continue;
      const fx = Math.sin(z.yaw), fz = Math.cos(z.yaw);
      if (z.crawl > 0.5 || z.down > 0.6) {
        // on the ground: head at one end, body laid out along its facing
        const dirS = z.crawl > 0.5 ? 1 : (z.downFace >= 0 ? -1 : 1), off = z.crawl > 0.5 ? 0 : z.downOff;
        const hx = z.pos.x + fx * (off + dirS * 0.75) * s, hz = z.pos.z + fz * (off + dirS * 0.75) * s;
        const th = raySphere(o, d, hx, z.pos.y + 0.26 * s, hz, 0.17 * s);
        if (th > 0 && th < maxT && (!best || th < best.t)) best = { z, t: th, head: true, part: 0 };
        for (const k of [0.3, -0.1, -0.5]) { const t = raySphere(o, d, z.pos.x + fx * (off + dirS * k) * s, z.pos.y + 0.22 * s, z.pos.z + fz * (off + dirS * k) * s, 0.25 * s); if (t > 0 && t < maxT && (!best || t < best.t - 0.05)) best = { z, t, head: false, part: k < -0.2 ? 3 : 1 }; }
        continue;
      }
      // head sphere
      const hx = z.pos.x + fx * 0.1 * s, hy = z.pos.y + 1.64 * s, hz = z.pos.z + fz * 0.1 * s;
      const th = raySphere(o, d, hx, hy, hz, 0.15 * s);
      if (th > 0 && th < maxT && (!best || th < best.t)) best = { z, t: th, head: true, part: 0 };
      // body: stacked spheres (wider for the heavy strains)
      const wide = z.type === 2 ? 1.25 : z.type === 4 ? 1.45 : 1;
      for (let i = 0; i < 4; i++) {
        const yy = BODY_Y[i], r = BODY_R[i] * (i < 2 ? wide : 1);
        const t = raySphere(o, d, z.pos.x + fx * 0.08 * s, z.pos.y + yy * s, z.pos.z + fz * 0.08 * s, r * s);
        if (t > 0 && t < maxT && (!best || t < best.t - 0.05)) best = { z, t, head: false, part: i < 2 ? 1 : 3 };
      }
    }
    if (best) {
      const z = best.z; let side = 0;
      if (best.part === 1) {
        // a torso hit well off the centre line is an arm
        const px = o.x + d.x * best.t - z.pos.x, pz = o.z + d.z * best.t - z.pos.z;
        const lat = px * Math.cos(z.yaw) - pz * Math.sin(z.yaw);
        if (Math.abs(lat) > 0.15 * z.scale) { best.part = 2; side = lat > 0 ? 1 : -1; }
      }
      this.lastRay = { z, part: best.part, side, at: this.time.value };
    }
    return best;
  }
  /** zombies in a melee arc */
  inArc(o: THREE.Vector3, dir: THREE.Vector3, range: number, cos: number): Zombie[] {
    const out: Zombie[] = [];
    for (const z of this.list) {
      if (z.state === 'dead') continue;
      this.tmpV.set(z.pos.x - o.x, 0, z.pos.z - o.z);
      const dist = this.tmpV.length(); if (dist > range + 0.35 * z.scale) continue;
      if (Math.abs(z.pos.y + 1 - o.y) > 1.6) continue;
      this.tmpV.normalize();
      const fd = this.tmpV.x * dir.x + this.tmpV.z * dir.z;
      if (fd < cos && dist > 0.6) continue;
      out.push(z);
    }
    out.sort((a, b) => a.pos.distanceToSquared(o) - b.pos.distanceToSquared(o));
    return out;
  }
  nearest(p: THREE.Vector3, r: number) { let best: Zombie | null = null, bd = r * r; for (const z of this.list) { if (z.state === 'dead') continue; const d = z.pos.distanceToSquared(p); if (d < bd) { bd = d; best = z; } } return best; }
  corpseNear(p: THREE.Vector3, r: number) { let best: Zombie | null = null, bd = r * r; for (const z of this.list) { if (z.state !== 'dead' || !z.corpse) continue; const d = z.pos.distanceToSquared(p); if (d < bd) { bd = d; best = z; } } return best; }

  /**
   * Hurt a zombie. Headshots do x3. Leg hits can cripple it into a crawler, arm hits can take the arm off
   * (weaker attacks), hard knocks stagger it or put it on the ground. Returns true if this killed it.
   */
  damage(z: Zombie, dmg: number, dir: THREE.Vector3, head: boolean, knock: number, byPlayer = true) {
    if (z.state === 'dead') return false;
    const lr = this.lastRay; let part = head ? 0 : 1, side = 0;
    if (lr && lr.z === z && this.time.value - lr.at < 0.05) { part = head ? 0 : lr.part; side = lr.side; }
    this.lastRay = null;
    z.hp -= dmg * (head ? 3 : 1);
    z.hit = 1;
    const dl = Math.hypot(dir.x, dir.z) || 1, nx = dir.x / dl, nz = dir.z / dl;
    z.hitSide = Math.sign(nx * Math.cos(z.yaw) - nz * Math.sin(z.yaw)) || 1;
    z.hitFwd = -(nx * Math.sin(z.yaw) + nz * Math.cos(z.yaw)) >= -0.2 ? 1 : -1;
    const heavy = z.type === 2 || z.type === 5; let crippled = false;
    // limbs
    if (z.hp > 0 && !z.crawler && !heavy && z.type !== 4) {
      const frac = dmg / z.maxHp;
      if ((part === 3 && this.rng.chance(Math.min(0.8, frac * 2.4))) || (z.hp < z.maxHp * 0.32 && this.rng.chance(0.3))) crippled = true;
    }
    if (z.hp > 0 && part === 2 && z.type !== 2 && this.rng.chance(Math.min(0.7, dmg / z.maxHp * 1.8))) {
      const left = side !== 0 ? side > 0 : this.rng.chance(0.5);
      if (left && !z.def.armLossL && !(z.type === 5)) { z.def.armLossL = true; this.applyDecay(z); }
      else if (!left && !z.def.armLossR && z.type !== 5) { z.def.armLossR = true; this.applyDecay(z); }
    }
    z.knock.set(nx, 0, nz).multiplyScalar(knock * (heavy ? 0.35 : z.type === 4 ? 0.6 : 1));
    // who did that?
    const hunting = z.state === 'chase' || z.state === 'attack' || z.state === 'bash';
    if (byPlayer) { z.alerted = 8; z.prey = null; if (this.host) z.lastSeen.copy(this.host.playerPos); }
    else if (!hunting) { z.target.set(z.pos.x - nx * 14, z.pos.y, z.pos.z - nz * 14); }
    if (z.hp <= 0) {
      if (z.type === 4 && !head) z.burst = true;
      this.kill(z, dir, byPlayer); return true;
    }
    const wasRunning = Math.hypot(z.vel.x, z.vel.z) > 3.4;
    const next: ZState = byPlayer || z.prey ? 'chase' : hunting ? z.state : 'investigate';
    if (crippled) { this.cripple(z); z.after = next === 'investigate' ? 'investigate' : 'chase'; return false; }
    if (z.state === 'down' || z.state === 'rise') { z.after = next === 'bash' || next === 'attack' ? 'chase' : next; if (z.state === 'rise' && z.lying) z.lying = false; return false; }
    if (z.state === 'dormant' && z.lying) { this.getUp(z, next === 'investigate' ? 'investigate' : 'chase', 0.1); return false; }
    // knocked off its feet / tripped / staggered
    const floorIt = !heavy && !z.crawler && (knock >= 2.6 || (knock > 1.5 && this.rng.chance(z.type === 4 ? 0.15 : 0.35)) || (wasRunning && this.rng.chance(0.4)));
    if (floorIt) { this.knockDown(z, wasRunning && knock < 1.5 ? -1 : z.hitFwd >= 0 ? 1 : -1, next === 'investigate' ? 'investigate' : 'chase'); return false; }
    if (knock > 1.2 && !heavy) { z.state = 'stagger'; z.stateT = -Math.min(0.9, knock * 0.15); z.after = next === 'investigate' ? 'investigate' : 'chase'; z.bashRef = null; return false; }
    if (!hunting) this.setState(z, next === 'bash' || next === 'attack' ? 'chase' : next);
    else if (z.state === 'bash' && byPlayer) z.after = 'chase';
    return false;
  }
  private cripple(z: Zombie) {
    z.crawler = true; z.speed = 0.45; z.runSpeed = 0.9; z.down = 0; z.pivot = false;
    this.applyDecay(z);
    if (z.state !== 'dead') { this.setState(z, 'stagger'); z.stateT = 0; }
  }
  /** put it on the ground: face = 1 onto its back, -1 onto its front */
  private knockDown(z: Zombie, face: number, after: ZState) {
    z.state = 'down'; z.stateT = 0; z.down = Math.max(z.down, 0.02); z.downMode = 0; z.downFace = face; z.downOff = face >= 0 ? -0.55 : 0.55;
    z.downT = 0; z.lieT = this.rng.range(0.5, 1.5) * (1 + z.decay * 0.6); z.riseDur = this.rng.range(1.5, 2.1) * (0.85 + z.decay * 0.6); z.after = after;
    z.bashRef = null; z.pivot = false; z.lying = false;
  }
  private getUp(z: Zombie, after: ZState, delay: number) {
    z.state = 'rise'; z.stateT = 0; z.downMode = 1; z.down = 1; z.lieT = delay; z.riseDur = this.rng.range(1.7, 2.5) * (0.85 + z.decay * 0.5); z.after = after;
  }
  kill(z: Zombie, dir: THREE.Vector3, byPlayer: boolean) {
    const fwd = Math.sin(z.yaw) * dir.x + Math.cos(z.yaw) * dir.z;   // > 0: struck from behind
    z.deathDir = fwd > 0 ? 1 : -1;
    const side = Math.abs(dir.x * Math.cos(z.yaw) - dir.z * Math.sin(z.yaw));
    if (z.crawl > 0.5 || z.down > 0.5) z.deathStyle = 5;
    else if (z.type === 4 || z.type === 2) z.deathStyle = fwd > 0 ? 0 : this.rng.chance(0.5) ? 1 : 4;
    else if (side > 0.75) z.deathStyle = 2;
    else if (fwd > 0) z.deathStyle = this.rng.chance(0.5) ? 0 : 3;
    else z.deathStyle = this.rng.weighted([[1, 4], [4, 3], [0, 2], [2, 1]]);
    z.state = 'dead'; z.stateT = 0; z.hp = 0; z.prey = null; z.bashRef = null; z.feedAt = null;
    z.corpse = new Inventory(20);
    rollLoot('corpse', this.rng, this.host?.luck ?? 0, z.corpse);
    audio.play('zombie_death', { pos: z.pos, pitch: z.type === 2 || z.type === 5 ? 0.7 : z.type === 3 ? 1.4 : 1 });
    this.kills += byPlayer ? 1 : 0;
    this.host?.onKill(z, byPlayer);
    if (z.burst) this.rupture(z);
    // cap corpses
    let corpses = 0, oldest: Zombie | null = null;
    for (const q of this.list) if (q.state === 'dead') { corpses++; if (!oldest || q.corpseAge > oldest.corpseAge) oldest = q; }
    if (corpses > 70 && oldest && oldest !== z) this.remove(oldest);
  }
  /** a bloat going off: gore and gas over everything close */
  private rupture(z: Zombie) {
    const R = 4.6;
    z.burst = false; z.aux = -3.4;
    audio.play('zombie_death', { pos: z.pos, pitch: 0.55, volume: 1.6 }); audio.play('splash', { pos: z.pos, volume: 1.3, pitch: 0.7 });
    this.noise(z.pos, 42);
    const hits: Zombie[] = [];
    for (const o of this.list) { if (o === z || o.state === 'dead' || o.frozen) continue; if (o.pos.distanceToSquared(z.pos) < R * R) hits.push(o); }
    for (const o of hits) {
      const d = o.pos.distanceTo(z.pos), k = 1 - d / R;
      this.tmpV2.set(o.pos.x - z.pos.x, 0, o.pos.z - z.pos.z);
      this.damage(o, 70 * k + 10, this.tmpV2, false, 3.2 * k, false);
    }
    // it eats at walls and doors too
    if (this.host) {
      const seen = new Set<any>();
      for (let i = -3; i <= 3; i++) for (let j = -3; j <= 3; j++) {
        if (i * i + j * j > 10) continue;
        if (this.navAt(z.pos.x + i, z.pos.z + j) === NAV_FREE) continue;
        const ref = this.refAt(z.pos.x + i, z.pos.z + j); if (ref && !seen.has(ref)) { seen.add(ref); this.host.onStructureHit(ref, 55, z); }
      }
    }
    this.onBurst?.(z.pos.clone(), R, z);
  }
  remove(z: Zombie) { const i = this.list.indexOf(z); if (i >= 0) this.list.splice(i, 1); }

  private setState(z: Zombie, s: ZState) {
    if (z.state === s) return;
    if (z.state === 'feed' && z.feedAt) { z.feedAt.feeders = Math.max(0, z.feedAt.feeders - 1); z.feedAt = null; }
    z.state = s; z.stateT = 0; z.pivot = false;
    if (s !== 'bash') z.bashRef = null;
    if (s === 'attack' || s === 'bash') { z.atkT = 0; z.struck = false; z.atkPeriod = STATS[z.type].period * (0.92 + this.rng.next() * 0.25); }
    if (s === 'idle') {
      z.pause = (4 + this.rng.range(0, 6)) * (0.5 + 0.5 * (this.host?.daylight ?? 1));
      // fetched up against a wall: paw at it for a while; otherwise maybe scent the air
      const wall = !z.crawler && this.navAt(z.pos.x + Math.sin(z.yaw) * 0.9, z.pos.z + Math.cos(z.yaw) * 0.9) !== NAV_FREE;
      z.idleAct = wall && this.rng.chance(0.65) ? ZACT.paw : this.rng.chance(0.12) ? ZACT.sniff : 0;
      if (z.idleAct === ZACT.paw) z.pause += 4;
    }
  }
  /** start hunting: h = a human pose, or null for the player */
  private engage(z: Zombie, h: HumanPose | null, tp: THREE.Vector3, quiet = false) {
    const was = z.state;
    z.prey = h; z.lastSeen.copy(tp); z.alerted = 6;
    if (was === 'chase' || was === 'attack') return;
    if (was === 'bash') { z.after = 'chase'; return; }
    if (was === 'dormant' && z.lying) { this.getUp(z, 'chase', 0.05 + this.rng.next() * 0.3); return; }
    if (was === 'down' || was === 'rise') { z.after = 'chase'; return; }
    if (was === 'stagger') { z.after = 'chase'; return; }
    if (z.type === 3 && z.screamCd <= 0) { this.setState(z, 'scream'); z.screamed = false; return; }
    this.setState(z, 'chase');
    if (!quiet && this.rng.chance(0.6)) audio.play('zombie_alert', { pos: z.pos, pitch: z.type === 2 ? 0.75 : 1 });
    if (!quiet) this.alertNeighbors(z, h, tp);
  }
  private alertNeighbors(z: Zombie, h: HumanPose | null, tp: THREE.Vector3) {
    const R = h ? 12 : 18; let n = 0;
    for (const o of this.list) {
      if (o === z || o.state === 'dead' || o.state === 'chase' || o.state === 'attack' || o.state === 'down' || o.state === 'rise' || o.state === 'scream' || o.frozen) continue;
      const d2 = o.pos.distanceToSquared(z.pos);
      if (d2 > R * R) continue;
      if (o.state === 'dormant') { if (d2 > 36) continue; if (o.lying) { this.getUp(o, 'investigate', this.rng.next() * 0.8); o.target.copy(tp); continue; } }
      if (h && ++n > 4) break;
      this.setState(o, 'investigate'); o.target.copy(tp); o.stateT = 0;
    }
  }
  /** the howler's call: everything in earshot comes, and comes angry */
  private howl(z: Zombie) {
    const R = 46, tp = z.lastSeen;
    audio.play('zombie_alert', { pos: z.pos, pitch: 1.7, volume: 1.8 }); audio.play('zombie_alert', { pos: z.pos, pitch: 1.25, volume: 1.4 });
    for (const o of this.list) {
      if (o === z || o.state === 'dead' || o.frozen) continue;
      const d2 = o.pos.distanceToSquared(z.pos); if (d2 > R * R) continue;
      o.enraged = 10;
      if (o.state === 'chase' || o.state === 'attack' || o.state === 'bash' || o.state === 'down' || o.state === 'scream') continue;
      if (o.state === 'rise') { o.after = 'investigate'; o.target.copy(tp); continue; }
      if (o.state === 'dormant' && o.lying) { this.getUp(o, 'investigate', this.rng.next() * 1.2); o.target.copy(tp); continue; }
      this.setState(o, 'investigate'); o.target.set(tp.x + this.rng.range(-4, 4), tp.y, tp.z + this.rng.range(-4, 4));
    }
    this.onHowl?.(z.pos.clone(), R, z);
  }

  // ------------------------------------------------------------------ update
  update(dt: number, dtGameMin: number, host: ZombieHost, camPos: THREE.Vector3) {
    this.host = host; this.dayNow = host.day; this.lastDt = dt;
    this.time.value += dt;
    const now = this.time.value;
    const P = host.playerPos;
    // flow field refresh
    this.fieldT -= dt;
    if (this.fieldT <= 0) { this.fieldT = 0.35; this.buildField(P.x, P.z); }
    // spatial hash for separation
    this.grid.clear();
    for (const z of this.list) { if (z.state === 'dead' || z.frozen) continue; const k = (Math.floor(z.pos.x / 2) + 1000) * 4096 + Math.floor(z.pos.z / 2) + 1000; let a = this.grid.get(k); if (!a) { a = []; this.grid.set(k, a); } a.push(z); }
    // the living they can hunt, and how many are already on each
    const prey = this.prey; prey.length = 0;
    for (const h of this.humans) { if (h.prey === true && h.visible) { prey.push(h); this.hs(h).hunters = 0; } }
    for (const z of this.list) if (z.prey && (z.state === 'chase' || z.state === 'attack')) { const s = this.hstate.get(z.prey); if (s) s.hunters++; }
    this.updateCarrion(dt);

    const night = 1 - host.daylight;
    const sightBase = host.playerInside ? 12 : 26;
    let chasing = 0, nearCount = 0, seenCount = 0;

    // noise events → investigate (the dormant are harder to rouse)
    for (const ev of this.noiseEvents) {
      for (const z of this.list) {
        if (z.state === 'dead' || z.frozen || z.state === 'chase' || z.state === 'attack' || z.state === 'down' || z.state === 'scream' || z.state === 'stagger') continue;
        const dx = z.pos.x - ev.pos.x, dz = z.pos.z - ev.pos.z, r = z.state === 'dormant' ? ev.r * 0.55 : z.state === 'feed' ? ev.r * 0.7 : ev.r;
        if (dx * dx + dz * dz >= r * r) continue;
        z.target.set(ev.pos.x + this.rng.range(-3, 3), ev.pos.y, ev.pos.z + this.rng.range(-3, 3)); z.alerted = Math.max(z.alerted, 2);
        if (z.state === 'rise') { z.after = 'investigate'; continue; }
        if (z.state === 'dormant' && z.lying) { this.getUp(z, 'investigate', this.rng.next() * 1.5); continue; }
        this.setState(z, 'investigate'); z.stateT = 0;
        const pk = this.packs.get(packOf(z)); if (pk) { pk.x += (ev.pos.x - pk.x) * 0.3; pk.z += (ev.pos.z - pk.z) * 0.3; }
      }
    }
    this.noiseEvents.length = 0;

    const dir = this.tmpV;
    for (let zi = this.list.length - 1; zi >= 0; zi--) {
      const z = this.list[zi];
      const dP = hyp(z.pos.x - P.x, z.pos.z - P.z);
      z.frozen = dP > 230;
      if (z.state === 'dead') {
        z.death = Math.min(1, z.death + dt * (z.deathStyle === 3 ? 1.0 : z.deathStyle === 4 ? 0.8 : 1.15)); z.corpseAge += dtGameMin;
        z.hit = Math.max(0, z.hit - dt * 3); z.atkBlend = 0; z.arms = Math.max(0, z.arms - dt * 4); z.actW = Math.max(0, z.actW - dt * 4); z.act2W = 0;
        if (z.burst) this.rupture(z);
        if (z.corpseAge > 60 * 24 * 2) this.remove(z);
        continue;
      }
      // ---- the slow rot
      z.ageAcc += dtGameMin; z.ageT -= dt;
      if (z.ageT <= 0) {
        z.ageT = 9 + (z.seed % 5);
        if (z.ageAcc > 0) { z.age += z.ageAcc / 1440; z.ageAcc = 0; const nd = ageToDecay(z.age); if (nd - z.decay > 0.012) { z.decay = nd; this.applyDecay(z); } }
      }
      if (z.frozen) continue;
      if (dP < 25) nearCount++;
      z.stateT += dt;
      z.hit = Math.max(0, z.hit - dt * 2.6);
      z.alerted = Math.max(0, z.alerted - dt); z.enraged = Math.max(0, z.enraged - dt); z.screamCd = Math.max(0, z.screamCd - dt);
      z.sated = Math.max(0, z.sated - dt); z.avoidT = Math.max(0, z.avoidT - dt); z.ignoreT = Math.max(0, z.ignoreT - dt);
      let h = z.prey;
      if (h && !(h.prey === true && h.visible)) {
        // the quarry is gone (hidden, or dead on the ground)
        z.prey = null; h = null;
        if (z.state === 'chase' || z.state === 'attack') { this.setState(z, 'search'); z.alerted = 0; }
      }
      const hunting = z.state === 'chase' || z.state === 'attack' || z.state === 'bash';
      // ---- senses
      z.senseT -= dt;
      if (z.senseT <= 0) {
        const dormant = z.state === 'dormant', feeding = z.state === 'feed' && z.actW > 0.5;
        z.senseT = (0.25 + this.rng.next() * 0.35) * (dormant ? 2 : 1);
        if (z.state !== 'down' && z.state !== 'scream') this.sense(z, host, dP, sightBase, night, dormant, feeding, hunting);
        h = z.prey;
      }
      const T = h ? h.pos : P;                       // current quarry position
      const dT = h ? hyp(z.pos.x - T.x, z.pos.z - T.z) : dP;
      const eyeY = h ? h.pos.y + 1.5 : host.playerEye.y;
      if (!h && z.state === 'chase' && z.alerted > 0 && dP < 40) seenCount++;

      // ---- head tracking
      {
        let wantY = 0, wantP = 0;
        const track = (hunting || z.state === 'investigate' || z.state === 'search' || (dP < 8 && z.state !== 'dormant' && z.state !== 'feed')) && (h ? true : host.playerAlive);
        const lx = hunting && h ? T : P, ld = hunting && h ? dT : dP;
        if (track && ld < 22 && z.state !== 'down' && z.state !== 'rise') {
          wantY = clamp(angleDiff(z.yaw, Math.atan2(lx.x - z.pos.x, lx.z - z.pos.z)), -1.2, 1.2);
          wantP = clamp(Math.atan2((hunting && h ? eyeY : host.playerEye.y) - (z.pos.y + 1.6 * z.scale), Math.max(0.5, ld)), -0.6, 0.6);
        }
        z.lookYaw += (wantY - z.lookYaw) * Math.min(1, dt * 6); z.lookPitch += (wantP - z.lookPitch) * Math.min(1, dt * 6);
        z.crawl += ((z.crawler ? 1 : 0) - z.crawl) * Math.min(1, dt * 3);
      }

      // ---- state machine
      let want = 0;                 // desired speed
      let reach = 0;                // arms out
      let act = 0, atkOn = false, faceYaw = NaN, auxWant = 0;
      dir.set(0, 0, 0);
      const st = STATS[z.type];
      const paceDay = 0.78 + night * 0.4;          // sluggish under the sun, restless in the dark
      const rage = z.enraged > 0 ? 1.18 : 1;
      switch (z.state) {
        case 'idle': {
          act = z.idleAct;
          if (z.stateT > z.pause) {
            if (z.horde) { const hd = this.hordes.find(q => q.id === z.horde); if (hd) { this.setState(z, 'investigate'); z.target.copy(hd.target).add(this.tmpV2.set(this.rng.range(-10, 10), 0, this.rng.range(-10, 10))); break; } z.horde = 0; }
            // by day many simply stop; some lie down among the dead
            const sleepy = hash1(z.seed * 4.7) < 0.62 && !z.crawler && z.type !== 1 && z.decay > 0.2;
            if (host.daylight > 0.6 && sleepy && z.enraged <= 0 && this.rng.chance(0.5)) {
              this.setState(z, 'dormant'); z.wakeT = this.rng.range(0, 40);
              z.lying = z.type === 0 && hash1(z.seed * 9.9) < 0.3 && this.navAt(z.pos.x, z.pos.z) === NAV_FREE;
              if (z.lying) { z.downFace = hash1(z.seed * 2.3) < 0.5 ? 1 : -1; z.downOff = 0; z.downMode = 1; }
              break;
            }
            const c = z.sated <= 0 && z.type !== 3 ? this.findCarrion(z, 14 + night * 8) : null;
            if (c) { this.setState(z, 'feed'); z.feedAt = c; c.feeders++; break; }
            this.setState(z, 'wander');
            // drift with the pack: loose groups share a slowly wandering anchor
            const pk = this.pack(z, night);
            const a = this.rng.range(0, 6.28), far = night > 0.5 ? this.rng.range(6, 26) : this.rng.range(3, 11);
            if (this.rng.chance(0.7)) { const r = this.rng.range(1, 8); z.target.set(pk.x + Math.cos(a) * r, 0, pk.z + Math.sin(a) * r); }
            else z.target.set(z.pos.x + Math.cos(a) * far, 0, z.pos.z + Math.sin(a) * far);
          }
          break;
        }
        case 'dormant': {
          act = z.lying ? 0 : ZACT.slump;
          if (z.lying) { z.down = Math.min(1, z.down + dt * 0.9); z.downMode = z.down < 0.99 ? 0 : 1; }
          if (host.daylight < 0.4 || z.enraged > 0) { z.wakeT -= dt * (z.enraged > 0 ? 30 : 1); if (z.wakeT <= 0) { if (z.lying) this.getUp(z, 'idle', 0); else this.setState(z, 'idle'); } }
          break;
        }
        case 'wander': case 'investigate': {
          const d = hyp(z.target.x - z.pos.x, z.target.z - z.pos.z);
          if (d < 1.2 || z.stateT > 40) { if (z.state === 'investigate' && z.alerted > 0 && !z.horde) { z.lastSeen.copy(z.target); this.setState(z, 'search'); } else this.setState(z, 'idle'); break; }
          const blk = this.steer(z, z.target.x, z.target.z, dir);
          if (blk && z.state === 'investigate' && (z.horde || z.type === 5) && this.bashable(blk)) { this.setState(z, 'bash'); z.bashRef = blk; z.after = 'investigate'; break; }
          want = z.speed * (z.state === 'investigate' ? 1.25 : 0.7 * paceDay) * (1 + night * 0.1) * rage;
          if (z.state === 'investigate' && z.type === 5 && z.horde) this.seekStructure(z, dt);
          break;
        }
        case 'feed': {
          const c = z.feedAt;
          if (!c || c.until < now) { z.sated = 60; this.setState(z, 'idle'); break; }
          const d = hyp(c.pos.x - z.pos.x, c.pos.z - z.pos.z);
          if (d > 1.0) { this.steer(z, c.pos.x, c.pos.z, dir); want = z.speed * 1.1; z.stateT = Math.min(z.stateT, 30); if (z.stateT >= 30) { z.sated = 30; this.setState(z, 'idle'); } }
          else {
            act = ZACT.feed; faceYaw = Math.atan2(c.pos.x - z.pos.x, c.pos.z - z.pos.z);
            if (this.rng.chance(dt * 0.25) && dP < 25) audio.play('eat', { pos: z.pos, volume: 0.5, pitch: 0.7 });
            if (z.stateT > 75 + (z.seed % 40)) { z.sated = 120; this.setState(z, 'idle'); }
          }
          break;
        }
        case 'search': {
          // lost it: go to where it was last seen, cast about, then give up
          const dl = hyp(z.lastSeen.x - z.pos.x, z.lastSeen.z - z.pos.z);
          if (z.stateT > 9 + (z.seed % 5)) { this.setState(z, 'idle'); break; }
          const leg = Math.floor(z.stateT / 2.6);
          if (leg % 2 === 0 || dl > 9) {
            const a = hash1(z.seed + leg * 3.1) * 6.28, r = leg === 0 ? 0 : 2 + hash1(z.seed * 2 + leg) * 4;
            const tx = z.lastSeen.x + Math.cos(a) * r, tz = z.lastSeen.z + Math.sin(a) * r;
            if (hyp(tx - z.pos.x, tz - z.pos.z) > 0.9) { this.steer(z, tx, tz, dir); want = z.speed * 1.2; } else act = ZACT.sniff;
          } else act = ZACT.sniff;
          reach = 0.3;
          break;
        }
        case 'scream': {
          act = ZACT.scream; auxWant = 1; faceYaw = Math.atan2(z.lastSeen.x - z.pos.x, z.lastSeen.z - z.pos.z);
          if (!z.screamed && z.stateT > 0.55) { z.screamed = true; z.screamCd = 24 + this.rng.range(0, 8); this.howl(z); }
          if (z.stateT > 1.9) this.setState(z, 'chase');
          break;
        }
        case 'chase': {
          if (!h) chasing++;
          reach = 1;
          if (!h && !host.playerAlive) { this.setState(z, 'wander'); break; }
          const seen = z.alerted > 4.5;            // knows exactly where it is only while it can see it
          const tgt = seen ? T : z.lastSeen;
          const d = hyp(tgt.x - z.pos.x, tgt.z - z.pos.z);
          if (!seen && (d < 1.6 || z.alerted <= 0)) { this.setState(z, 'search'); break; }
          let blk: any = null;
          if (!h && seen) {
            const fd = this.flowDir(z.pos.x, z.pos.z, dir);
            if (d < 2.2 || !fd.ok || (seen && dP < 3)) dir.set(tgt.x - z.pos.x, 0, tgt.z - z.pos.z).normalize();
            else blk = fd.blockRef;
          } else blk = this.steer(z, tgt.x, tgt.z, dir);
          if (blk && this.bashable(blk) && (!h || d < 16)) { this.setState(z, 'bash'); z.bashRef = blk; z.after = 'chase'; break; }
          if (h && seen && dT < 2.2 && Math.abs(T.y - z.pos.y) >= 1.4) {
            // quarry is up on something: tear at whatever it stands on, or give up on it for a while
            let ref: any = null;
            for (let i = -1; i <= 1 && !ref; i++) for (let j = -1; j <= 1; j++) { const r = this.navAt(T.x + i, T.z + j) !== NAV_FREE ? this.refAt(T.x + i, T.z + j) : null; if (r && this.bashable(r)) { ref = r; break; } }
            if (ref) { this.setState(z, 'bash'); z.bashRef = ref; z.after = 'chase'; break; }
            z.ignore = h; z.ignoreT = 30; z.prey = null; z.alerted = 0; this.setState(z, 'search'); break;
          }
          want = (dT > 30 ? z.speed * 1.4 : z.runSpeed * (0.94 + night * 0.2)) * rage;
          if (z.type === 5) this.seekStructure(z, dt);
          if (z.type === 3 && seen) {
            // howlers hang back and keep calling
            if (z.screamCd <= 0 && dT < 30) { this.setState(z, 'scream'); z.screamed = false; z.lastSeen.copy(T); break; }
            if (dT < 6 && dT > 2.4) { dir.multiplyScalar(-1); want = z.runSpeed * 0.8; }
            else if (dT < 11 && dT >= 6) { want = 0; faceYaw = Math.atan2(T.x - z.pos.x, T.z - z.pos.z); }
          }
          // sprinting dead trip over their own feet now and then
          if ((z.type === 1 || z.decay < 0.2) && !z.crawler && z.fwdSpeed > 3.6 && this.rng.chance(dt * 0.035)) { this.knockDown(z, -1, 'chase'); z.lieT *= 0.5; break; }
          if (seen && dT < 1.25 + 0.3 * z.scale && Math.abs(z.pos.y - T.y) < 1.4) this.setState(z, 'attack');
          break;
        }
        case 'attack': {
          reach = 1; atkOn = true;
          if (!h && !host.playerAlive) { this.setState(z, 'wander'); break; }
          faceYaw = Math.atan2(T.x - z.pos.x, T.z - z.pos.z);
          z.attackT = z.atkPeriod - z.atkT;
          const strikeAt = st.wind + 0.07;
          if (!z.struck && z.atkT >= strikeAt) {
            z.struck = true;
            z.vel.x += Math.sin(z.yaw) * 1.4; z.vel.z += Math.cos(z.yaw) * 1.4;
            if (dT < 1.65 * z.scale + (z.type === 5 ? 0.3 : 0) && Math.abs(angleDiff(z.yaw, faceYaw)) < 1.0) {
              if (h) { this.onHumanAttacked?.(h, z, st.dmg * z.dmgMul); }
              else if (host.playerAlive) host.onPlayerAttacked(z);
              audio.play('zombie_attack', { pos: z.pos, pitch: z.type === 2 || z.type === 5 ? 0.75 : 1 });
            }
          }
          if (z.atkT >= z.atkPeriod) { z.atkT = 0; z.struck = false; z.atkPeriod = st.period * (0.92 + this.rng.next() * 0.25); if (dT > 1.9 * z.scale) this.setState(z, 'chase'); }
          else if (dT > 2.6 * z.scale) this.setState(z, 'chase');
          break;
        }
        case 'bash': {
          reach = 1; atkOn = true;
          const ref = z.bashRef;
          if (!ref || ref.destroyed || (ref.open === true) || (ref.broken === true && (ref.barricade ?? 0) <= 0 && ref.hp === undefined) || z.stateT > 20) { this.setState(z, z.after === 'investigate' ? 'investigate' : 'chase'); break; }
          const rx = ref.cx ?? ref.x ?? z.pos.x, rz = ref.cz ?? ref.z ?? z.pos.z;
          faceYaw = Math.atan2(rx - z.pos.x, rz - z.pos.z);
          if (!z.struck && z.atkT >= st.wind + 0.07) {
            z.struck = true;
            host.onStructureHit(ref, st.wreck * (z.type === 0 || z.type === 1 ? z.dmgMul : 1), z);
            audio.play('zombie_bang', { pos: z.pos, volume: z.type === 5 ? 1.3 : 0.8, pitch: z.type === 5 || z.type === 2 ? 0.7 : 1 });
          }
          if (z.after !== 'investigate' && dT < 1.5 && (h || host.playerAlive)) this.setState(z, 'attack');
          else if (z.atkT >= z.atkPeriod) { const back: ZState = z.after === 'investigate' ? 'investigate' : 'chase'; const tx = z.target.x, tz = z.target.z; this.setState(z, back); z.target.x = tx; z.target.z = tz; z.atkBlend = 1; }
          break;
        }
        case 'stagger':
          if (z.stateT > 0.7) this.setState(z, z.after === 'investigate' ? 'investigate' : 'chase');
          break;
        case 'down': {
          z.downT += dt;
          if (z.downMode < 0.5) { z.down = Math.min(1, z.down + dt / 0.55); if (z.down >= 1 && z.downT > 0.55 + z.lieT) { z.downMode = 1; z.downT = 0; } }
          else { z.down = Math.max(0, 1 - z.downT / z.riseDur); if (z.down <= 0) { z.down = 0; this.setState(z, z.after === 'investigate' ? 'investigate' : z.after === 'idle' ? 'idle' : 'chase'); } }
          break;
        }
        case 'rise': {
          z.downMode = 1;
          if (z.stateT < z.lieT) z.down = 1;
          else { z.down = Math.max(0, 1 - (z.stateT - z.lieT) / z.riseDur); if (z.down <= 0) { z.down = 0; z.lying = false; const nx: ZState = z.after === 'investigate' ? 'investigate' : z.after === 'chase' ? 'chase' : 'idle'; this.setState(z, nx); if (nx === 'idle') z.pause = 0.6; } }
          break;
        }
      }
      // attack cycle: wind-up (lunge < 0), strike (> 0), recover
      if (atkOn) {
        z.atkT += dt; z.atkBlend = Math.min(1, z.atkBlend + dt * 5);
        const w = st.wind, t = z.atkT;
        z.lunge = t < w ? -smooth(0, w, t) : t < w + 0.1 ? -1 + 2 * (t - w) / 0.1 : t < w + 0.3 ? 1 : Math.max(0, 1 - (t - w - 0.3) / 0.35);
      } else { z.atkBlend = Math.max(0, z.atkBlend - dt * 3); z.lunge *= Math.exp(-dt * 8); }
      z.arms += ((z.def.gait === 3 && z.state !== 'dormant' ? Math.max(reach, 0.75) : reach) - z.arms) * Math.min(1, dt * 3);
      z.aux += (auxWant - z.aux) * Math.min(1, dt * (auxWant > z.aux ? 3.5 : 1.5));
      if (z.type === 4) z.aux = Math.max(z.aux, (1 - z.hp / z.maxHp) * 0.9);
      // pose cross-fade
      if (act !== z.act) { if (z.actW > 0.02 && z.act !== 0) { z.act2 = z.act; z.act2W = z.actW; } z.act = act; z.actW = 0; }
      z.actW = z.act ? Math.min(1, z.actW + dt * (z.act === ZACT.feed ? 1.2 : 2.5)) : 0; z.act2W = Math.max(0, z.act2W - dt * 2.2);
      if (z.state !== 'dormant' && z.state !== 'down' && z.state !== 'rise' && z.down > 0) z.down = Math.max(0, z.down - dt * 2);

      // ---- movement: turn on the spot first, then walk; feet set the pace
      const canMove = z.state !== 'down' && z.state !== 'rise' && z.state !== 'stagger';
      let turnAmt = 0;
      const rate = st.turn * (hunting ? 1.5 : 1) * (1.15 - z.decay * 0.4) * (z.crawler ? 0.6 : 1);
      if (canMove && want > 0 && (dir.x || dir.z)) {
        const tyaw = Math.atan2(dir.x, dir.z), df = angleDiff(z.yaw, tyaw), ad = Math.abs(df);
        if (ad > 1.15) z.pivot = true; else if (ad < 0.35) z.pivot = false;
        const stp = clamp(df, -rate * dt, rate * dt); z.yaw += stp; turnAmt = Math.abs(stp) / Math.max(dt, 1e-4);
        const fwd = z.pivot ? 0 : Math.max(0, Math.cos(df));
        const sp = want * fwd * fwd * (z.limp ? 1 - z.limp * 0.25 : 1);
        const acc = Math.min(1, dt * (z.type === 1 ? 5 : 3.2));
        z.vel.x += (Math.sin(z.yaw) * sp - z.vel.x) * acc; z.vel.z += (Math.cos(z.yaw) * sp - z.vel.z) * acc;
      } else {
        z.pivot = false;
        const k = Math.exp(-dt * 6); z.vel.x *= k; z.vel.z *= k;
        if (canMove && !Number.isNaN(faceYaw)) { const stp = clamp(angleDiff(z.yaw, faceYaw), -rate * 1.6 * dt, rate * 1.6 * dt); z.yaw += stp; turnAmt = Math.abs(stp) / Math.max(dt, 1e-4); }
      }
      z.turnAmt += (Math.min(1, turnAmt / 1.2) * (z.pivot || want <= 0 ? 1 : 0) - z.turnAmt) * Math.min(1, dt * 8);
      // separation
      let shoved = false;
      const gx = Math.floor(z.pos.x / 2), gz = Math.floor(z.pos.z / 2);
      for (let i = -1; i <= 1; i++) for (let j = -1; j <= 1; j++) {
        const a = this.grid.get((gx + i + 1000) * 4096 + gz + j + 1000); if (!a) continue;
        for (const o of a) {
          if (o === z) continue;
          const ddx = z.pos.x - o.pos.x, ddz = z.pos.z - o.pos.z; const d2 = ddx * ddx + ddz * ddz;
          const rr = (z.type === 4 ? 0.46 : 0.32) * z.scale + (o.type === 4 ? 0.46 : 0.32) * o.scale;
          if (d2 < rr * rr && d2 > 1e-6) { const d = Math.sqrt(d2); const push = (rr - d) * 0.5; z.pos.x += ddx / d * push; z.pos.z += ddz / d * push; shoved = true; }
        }
      }
      // the living are solid too
      const pdx = z.pos.x - P.x, pdz = z.pos.z - P.z, pd = hyp(pdx, pdz);
      if (pd < 0.6 && pd > 1e-4) { z.pos.x = P.x + pdx / pd * 0.6; z.pos.z = P.z + pdz / pd * 0.6; }
      if (h && dT < 0.55 && dT > 1e-4) { z.pos.x = T.x + (z.pos.x - T.x) / dT * 0.55; z.pos.z = T.z + (z.pos.z - T.z) / dT * 0.55; }

      const kn = z.knock;
      const mvx = z.vel.x + kn.x, mvz = z.vel.z + kn.z;
      if (shoved || mvx * mvx + mvz * mvz > 0.0004 || z.settle > 0) {
        // only bodies that actually move need the world resolved under them
        z.settle = shoved || mvx * mvx + mvz * mvz > 0.0004 ? 6 : z.settle - 1;
        z.pos.x += mvx * dt; z.pos.z += mvz * dt;
        kn.multiplyScalar(Math.exp(-dt * 6));
        this.world.col.resolveCylinder(z.pos, 0.3 * z.scale, 1.7, 0.45, 'window_broken');
        z.pos.y += (this.world.groundY(z.pos.x, z.pos.z, z.pos.y) - z.pos.y) * Math.min(1, dt * 12);
      }
      // signed forward speed (so a zombie knocked backward steps backward) drives the gait
      const fs = (z.vel.x + kn.x) * Math.sin(z.yaw) + (z.vel.z + kn.z) * Math.cos(z.yaw);
      z.fwdSpeed += (fs - z.fwdSpeed) * Math.min(1, dt * 10);
      const speedNow = Math.abs(z.fwdSpeed);
      z.move = speedNow < 0.15 ? speedNow / 0.15 * 0.2 : speedNow < 2.2 ? 1 : 1 + clamp((speedNow - 2.2) / 2.5, 0, 1);
      if (z.crawler) z.phase += dt * (hyp(z.vel.x, z.vel.z) * 9 + 0.1);
      else z.phase += dt * (TWO_PI * z.fwdSpeed / strideLen(z.def, z.fwdSpeed) + z.turnAmt * 5.5);
      // stuck detection
      if (z.state === 'wander' || z.state === 'investigate' || z.state === 'feed') {
        if (z.pos.distanceToSquared(z.lastPos) < 0.0144 * dt * dt && want > 0 && !z.pivot) { z.stuckT += dt; if (z.stuckT > 2) { z.stuckT = 0; if (z.state === 'feed') z.sated = 20; this.setState(z, 'idle'); } } else z.stuckT = 0;
      }
      z.lastPos.copy(z.pos);
      // groans
      z.groanT -= dt;
      if (z.groanT <= 0) {
        z.groanT = this.rng.range(4, 14) * (hunting ? 0.5 : z.state === 'dormant' ? 3 : 1);
        if (dP < 35) audio.play('zombie_groan', { pos: this.tmpV2.copy(z.pos).setY(z.pos.y + 1.6), volume: z.type === 2 ? 1.2 : z.state === 'dormant' ? 0.4 : 0.8, pitch: z.type === 2 || z.type === 5 ? 0.7 : z.type === 1 ? 1.15 : z.type === 3 ? 1.5 : z.type === 4 ? 0.8 : 1 - (z.decay - 0.5) * 0.2 });
      }
    }

    this.director(dt, dtGameMin, host);
    this.render(camPos, host);
    return { chasing, nearCount, seenCount };
  }

  /** sight (cone, daylight, cover), hearing (footsteps) and smell (blood) for one zombie */
  private sense(z: Zombie, host: ZombieHost, dP: number, sightBase: number, night: number, dormant: boolean, feeding: boolean, hunting: boolean) {
    const P = host.playerPos;
    const light = 0.35 + 0.65 * host.daylight;
    const keen = (z.type === 3 ? 1.3 : 1) * (1 - Math.max(0, z.decay - 0.7) * 0.8) * (dormant ? 0.4 : feeding ? 0.55 : 1) * (z.crawler ? 0.8 : 1);
    const cone = hunting ? 3.2 : dormant ? 0.8 : feeding ? 0.7 : 1.1;
    const close = dormant ? 1.6 : 3;
    const eyeY = z.pos.y + (z.crawl > 0.5 || z.down > 0.5 ? 0.4 : 1.6 * z.scale);
    const cur = z.prey;
    // ---- the player
    let seeP = false;
    if (host.playerAlive) {
      const sight = sightBase * light * (0.5 + host.playerVisibility) * (hunting && !cur ? 1.6 : 1) * keen * (host.playerBleeding ? 1.2 : 1);
      if (dP < sight) {
        const toP = Math.atan2(P.x - z.pos.x, P.z - z.pos.z);
        if ((Math.abs(angleDiff(z.yaw, toP)) < cone || dP < close) && !this.world.col.blocked(z.pos.x, eyeY, z.pos.z, host.playerEye.x, host.playerEye.y, host.playerEye.z)) seeP = true;
      }
    }
    // ---- other people: the best of those in view
    let bestH: HumanPose | null = null, bestS = 1e9;
    if (z.state !== 'attack') {
      for (const hp of this.prey) {
        const dx = hp.pos.x - z.pos.x, dz = hp.pos.z - z.pos.z, d2 = dx * dx + dz * dz;
        if (d2 > 900) continue;
        const d = Math.sqrt(d2);
        if (hp === z.ignore && z.ignoreT > 0) continue;                  // already found it can't reach that one
        const quiet = hp.act === ACT.cower || hp.act === ACT.lie || hp.act === ACT.sit;
        const sight = 24 * light * (quiet ? 0.55 : hp.move > 1.2 ? 1.15 : 1) * (hunting && cur === hp ? 1.6 : 1) * keen;
        const hears = !dormant && hp.move > 0.5 && d < 5;
        if (d > sight && !hears) continue;
        if (!(Math.abs(angleDiff(z.yaw, Math.atan2(dx, dz))) < cone || d < close || hears)) continue;
        const n = this.hstate.get(hp)?.hunters ?? 0;
        if (cur !== hp && n >= 6 && d > 7) continue;                     // enough on that one already
        const s = d * (1 + 0.22 * (cur === hp ? Math.max(0, n - 1) : n)) * (cur === hp ? 0.6 : 1);
        if (s < bestS) { bestS = s; bestH = hp; }
      }
      if (bestH && this.world.col.blocked(z.pos.x, eyeY, z.pos.z, bestH.pos.x, bestH.pos.y + 1.4, bestH.pos.z)) {
        // can't actually see the best one; keep the current quarry if it was a candidate at all
        bestH = null; bestS = 1e9;
      }
    }
    // ---- decide
    const sP = seeP ? dP * 0.85 * (hunting && !cur ? 0.6 : 1) : 1e9;
    if (seeP && sP <= bestS) {
      if (hunting && cur && z.alerted > 2 && sP > bestS * 0.6) { /* stay on the human */ }
      else { this.engage(z, null, P); return; }
    }
    if (bestH) {
      if (hunting && !cur && z.alerted > 2 && !(bestS < dP * 0.5)) { if (seeP) { z.lastSeen.copy(P); z.alerted = 6; } return; }
      this.engage(z, bestH, bestH.pos); return;
    }
    if (seeP) { this.engage(z, null, P); return; }
    if (hunting) return;
    // ---- no sight: sound and smell only draw them in
    if (host.playerAlive && z.state !== 'rise' && z.state !== 'stagger') {
      const hear = dP < host.playerNoise * (dormant ? 0.55 : feeding ? 0.7 : 1);
      const smell = !!host.playerBleeding && dP < (16 + night * 8) * (dormant ? 0.7 : 1);
      if (hear || smell) {
        z.target.set(P.x + (smell && !hear ? this.rng.range(-4, 4) : 0), P.y, P.z + (smell && !hear ? this.rng.range(-4, 4) : 0)); z.alerted = Math.max(z.alerted, 2);
        if (z.state === 'dormant' && z.lying) this.getUp(z, 'investigate', this.rng.next());
        else { this.setState(z, 'investigate'); z.stateT = 0; }
      }
    }
  }

  private hs(h: HumanPose): HState {
    let s = this.hstate.get(h);
    if (!s || s.look !== h.look) {
      const def = makeDef(KIND.human, h.seed, h.look ?? null);
      s = { def, look: h.look, act: s?.act ?? 0, actW: s?.actW ?? 0, act2: 0, act2W: 0, hunters: 0 };
      this.hstate.set(h, s);
    }
    return s;
  }

  // ------------------------------------------------------------------ packs, carrion, structures
  private pack(z: Zombie, night: number) {
    const id = packOf(z);
    let p = this.packs.get(id);
    const now = this.time.value;
    if (!p) { p = { x: z.pos.x, z: z.pos.z, t: now + this.rng.range(20, 60) }; this.packs.set(id, p); if (this.packs.size > 600) this.packs.delete(this.packs.keys().next().value!); }
    else if (now > p.t) {
      // the pack's centre of interest ambles on; further and more often at night
      const a = this.rng.range(0, 6.28), r = this.rng.range(4, 12) * (1 + night * 1.6);
      p.x = clamp(z.pos.x * 0.5 + p.x * 0.5 + Math.cos(a) * r, -HALF_WORLD + 30, HALF_WORLD - 30); p.z = clamp(z.pos.z * 0.5 + p.z * 0.5 + Math.sin(a) * r, -HALF_WORLD + 30, HALF_WORLD - 30);
      p.t = now + this.rng.range(25, 70) * (1 - night * 0.5);
    }
    return p;
  }
  private updateCarrion(dt: number) {
    this.carrionT -= dt; if (this.carrionT > 0) return; this.carrionT = 1;
    const now = this.time.value;
    for (let i = this.carrion.length - 1; i >= 0; i--) {
      const c = this.carrion[i];
      if (c.pose) { const h = c.pose; if (!(h.visible && h.prey !== true && h.act === ACT.lie && this.humans.includes(h))) c.until = 0; else c.pos.copy(h.pos); }
      if (c.until < now) this.carrion.splice(i, 1);
    }
    // the bodies of people: a visible human lying down who is no longer prey is a corpse
    for (const h of this.humans) {
      if (h.type !== 4 || !h.visible || h.prey === true || h.act !== ACT.lie || (h.sick ?? 0) < 0.99) continue;
      if (!this.carrion.some(c => c.pose === h)) this.carrion.push({ pos: h.pos.clone(), until: now + 600, feeders: 0, pose: h });
    }
  }
  private findCarrion(z: Zombie, r: number): Carrion | null {
    let best: Carrion | null = null, bd = r * r;
    for (const c of this.carrion) { if (c.feeders >= 4) continue; const d = c.pos.distanceToSquared(z.pos); if (d < bd) { bd = d; best = c; } }
    return best;
  }
  private bashable(ref: any) { return !!ref && !ref.destroyed && ref.open !== true && !(ref.broken === true && (ref.barricade ?? 0) <= 0 && ref.hp === undefined); }
  /** breakers go looking for something built to pull down */
  private seekStructure(z: Zombie, dt: number) {
    z.scanT -= dt; if (z.scanT > 0) return; z.scanT = 1.2;
    let best: any = null, bd = 1e9;
    for (let i = -6; i <= 6; i++) for (let j = -6; j <= 6; j++) {
      const v = this.navAt(z.pos.x + i, z.pos.z + j); if (v === NAV_FREE || v === NAV_SOLID) continue;
      const ref = this.refAt(z.pos.x + i, z.pos.z + j); if (!ref || !ref.def || !this.bashable(ref)) continue;   // player-built things only
      const d = i * i + j * j; if (d < bd) { bd = d; best = ref; }
    }
    if (!best) return;
    if (bd <= 2.5) { const after: ZState = z.state === 'investigate' ? 'investigate' : 'chase'; this.setState(z, 'bash'); z.bashRef = best; z.after = after; }
    else if (z.state === 'investigate') z.target.set(best.cx ?? best.x ?? z.pos.x, z.pos.y, best.cz ?? best.z ?? z.pos.z);
  }

  // hordes & respawn
  private director(dt: number, dtGameMin: number, host: ZombieHost) {
    this.hordeT -= dtGameMin;
    let alive = 0; for (const z of this.list) if (z.state !== 'dead') alive++;
    const night = host.hours >= 20 || host.hours < 5;
    if (this.hordeT <= 0) {
      this.hordeT = this.rng.range(60 * 5, 60 * 10) / (1 + host.baseHeat * 0.02);
      if ((night || host.baseHeat > 30) && alive < 520) {
        // horde from the forest heading to the player's base (or a random town point)
        const toBase = !!host.baseCenter && host.baseHeat > 10 && this.rng.chance(0.4 + Math.min(0.5, host.baseHeat / 200));
        const target = toBase ? host.baseCenter!.clone() : new THREE.Vector3(this.rng.range(-120, 120), 0, this.rng.range(-110, 110));
        const a = this.rng.range(0, 6.28); const r = 170;
        const cx = THREE.MathUtils.clamp(target.x + Math.cos(a) * r, -HALF_WORLD + 40, HALF_WORLD - 40), cz = THREE.MathUtils.clamp(target.z + Math.sin(a) * r, -HALF_WORLD + 40, HALF_WORLD - 40);
        const n = Math.floor(clamp(18 + host.day * 4 + host.baseHeat * 0.6, 18, 110));
        this.spawnHorde(cx, cz, n, target, toBase);
        if (this.onHorde) this.onHorde(n, toBase);
      }
    }
    // respawn stragglers out of sight
    this.respawnT -= dtGameMin;
    if (this.respawnT <= 0) {
      this.respawnT = 30;
      if (alive < 300) {
        for (let i = 0; i < 6; i++) {
          const x = this.rng.range(-HALF_WORLD + 40, HALF_WORLD - 40), z = this.rng.range(-HALF_WORLD + 40, HALF_WORLD - 40);
          const d = Math.hypot(x - host.playerPos.x, z - host.playerPos.z);
          if (d < 120) continue;
          if (host.baseCenter && Math.hypot(x - host.baseCenter.x, z - host.baseCenter.z) < 80) continue;
          if (this.world.town.isOccupied(x, z)) continue;
          this.spawn(x, z, this.pickType(host.day));
        }
      }
    }
    for (let i = this.hordes.length - 1; i >= 0; i--) { this.hordes[i].t += dtGameMin; if (this.hordes[i].t > 60 * 12) this.hordes.splice(i, 1); }
  }

  // ------------------------------------------------------------------ drawing
  private camF = new THREE.Vector3();
  private render(cam: THREE.Vector3, host: ZombieHost) {
    const R = this.bodies, A = this.anim, now = this.time.value, dt = this.lastDt;
    R.night.value = 1 - host.daylight; R.amb.value = 0.04 + 0.44 * host.daylight;
    // cheap view culling against last frame's camera (generous: shadows and fast turns)
    const c = R.cam; let cull = false; const f = this.camF;
    if (c) { c.getWorldDirection(f); cull = true; }
    // returns -1 if not drawn, else a pose LOD (0 every frame, 1 every 2nd, 2 every 3rd)
    const vis = (x: number, y: number, z: number) => {
      const dx = x - cam.x, dz = z - cam.z;
      if (dx > 170 || dx < -170 || dz > 170 || dz < -170) return -1;
      const dy = y + 1 - cam.y, d2 = dx * dx + dy * dy + dz * dz;
      if (d2 < 144) return 0;
      if (cull) { const along = dx * f.x + dy * f.y + dz * f.z; if (!(along > 0 && along * along > d2 * 0.07)) return -1; }   // within ~75° of the view axis
      return d2 < 625 ? 0 : d2 < 2500 ? 1 : d2 < 8100 ? 2 : 3;
    };
    R.begin();
    for (const z of this.list) {
      if (R.full()) break;
      if (z.frozen) continue;
      let lod = vis(z.pos.x, z.pos.y, z.pos.z); if (lod < 0) continue;
      const dead = z.state === 'dead';
      // settled corpses and the dormant lying "dead" do not move: pose them once
      if ((dead && z.death >= 1 && z.hit <= 0) || (z.state === 'dormant' && z.lying && z.down >= 1 && z.hit <= 0)) { if (z.still < 3) z.still++; else lod = -1; } else z.still = 0;
      A.t = now; A.phase = z.phase; A.speed = dead ? 0 : z.fwdSpeed; A.move = dead ? 0 : z.move; A.turn = dead ? 0 : z.turnAmt;
      A.arms = z.arms; A.atk = z.atkBlend; A.lunge = z.lunge; A.swing = 0;
      A.hit = z.hit; A.hitSide = z.hitSide; A.hitFwd = z.hitFwd; A.lookYaw = z.lookYaw; A.lookPitch = z.lookPitch;
      A.crawl = z.crawl; A.limp = z.limp; A.down = z.down; A.downMode = z.downMode; A.downFace = z.downFace; A.downOff = z.downOff;
      A.death = z.death; A.deathStyle = z.deathStyle; A.deathDir = z.deathDir;
      A.act = z.act; A.actW = z.actW; A.act2 = z.act2; A.act2W = z.act2W; A.aux = z.aux; A.jaw = 0;
      R.add(A, z.def, z.pos.x, z.pos.y, z.pos.z, z.yaw, z.scale, lod);
    }
    for (const h of this.humans) {
      if (R.full()) break;
      if (!h.visible) continue;
      const lod = vis(h.pos.x, h.pos.y, h.pos.z); if (lod < 0) continue;
      const s = this.hs(h), d = s.def;
      const tool = h.tool ?? 0, sick = clamp(h.sick ?? 0, 0, 1);
      if (d.tool !== tool || Math.abs(d.sick - sick) > 0.02) { d.tool = tool; d.sick = sick; refreshDef(d); }
      // cross-fade body poses
      const act = h.act ?? 0;
      if (act !== s.act) { if (s.actW > 0.02 && s.act !== 0) { s.act2 = s.act; s.act2W = s.actW; } s.act = act; s.actW = 0; }
      s.actW = s.act ? Math.min(1, s.actW + dt * 3) : 0; s.act2W = Math.max(0, s.act2W - dt * 3);
      A.t = now; A.phase = h.phase; A.speed = 0; A.move = h.move; A.turn = 0; A.arms = h.arms; A.atk = h.atk; A.lunge = 0; A.swing = 0;
      A.hit = h.hit; A.hitSide = 0; A.hitFwd = 1; A.lookYaw = h.lookYaw ?? 0; A.lookPitch = h.lookPitch ?? 0;
      A.crawl = 0; A.limp = 0; A.down = 0; A.downMode = 0; A.downFace = 1; A.downOff = 0; A.death = 0; A.deathStyle = 0; A.deathDir = 1;
      A.act = s.act; A.actW = s.actW; A.act2 = s.act2; A.act2W = s.act2W; A.aux = 0; A.jaw = 0;
      A.stance = h.type === 3 ? 0 : 1;
      R.add(A, d, h.pos.x, h.pos.y, h.pos.z, h.yaw, d.height, lod);
    }
    R.end();
  }

  // ------------------------------------------------------------------ persistence
  toJSON() {
    const r1 = (v: number) => Math.round(v * 10) / 10;
    return this.list.filter(z => z.state !== 'dead').map(z => {
      const flags = (z.crawler ? 1 : 0) | (z.def.armLossL ? 2 : 0) | (z.def.armLossR ? 4 : 0) | (z.turned ? 8 : 0) | (z.state === 'dormant' ? 16 : 0) | (z.state === 'dormant' && z.lying ? 32 : 0);
      const a: any[] = [r1(z.pos.x), r1(z.pos.z), z.type, Math.round(z.hp), Math.round(z.decay * 100) / 100, Math.round(z.seed * 1000) / 1000, flags];
      if (z.turned || z.name) { const d = z.def; a.push(z.name ?? 0, [d.skin, d.shirt, d.pants, d.hair, Math.round(d.build * 100) / 100, Math.round(d.height * 100) / 100, d.hairStyle]); }
      return a;
    });
  }
  /** accepts both the current rows and old saves ([x, z, type, hp]) */
  load(arr: any[][]) {
    this.list = []; this.carrion.length = 0; this.packs.clear();
    for (const a of arr ?? []) {
      if (!Array.isArray(a) || a.length < 3) continue;
      const old = a.length < 6;
      const lk = Array.isArray(a[8]) ? { skin: a[8][0], shirt: a[8][1], pants: a[8][2], hair: a[8][3], build: a[8][4], height: a[8][5], hairStyle: a[8][6] } as HumanLook : undefined;
      const z = this.spawn(a[0], a[1], a[2] as ZType, 0, old ? undefined : { decay: a[4], seed: a[5], look: lk, name: typeof a[7] === 'string' ? a[7] : undefined });
      if (!z) continue;
      if (!old) {
        const f = a[6] | 0;
        if (f & 2) z.def.armLossL = true; if (f & 4) z.def.armLossR = true;
        if (f & 1) z.crawler = true;
        if (f & 8) z.turned = true;
        if (f & 6 || f & 1) this.applyDecay(z);
        if (f & 16) { z.state = 'dormant'; z.wakeT = this.rng.range(0, 40); if (f & 32) { z.lying = true; z.down = 1; z.downMode = 1; z.downFace = hash1(z.seed * 2.3) < 0.5 ? 1 : -1; } }
      }
      z.hp = Math.min(z.maxHp, Math.max(1, a[3] ?? z.maxHp));
    }
  }
}

function hyp(x: number, z: number) { return Math.sqrt(x * x + z * z); }
const BODY_Y = [1.25, 0.95, 0.55, 0.25], BODY_R = [0.26, 0.22, 0.18, 0.16];
function smooth(a: number, b: number, x: number) { const t = x <= a ? 0 : x >= b ? 1 : (x - a) / (b - a); return t * t * (3 - 2 * t); }
function packOf(z: Zombie) { return z.horde ? 100000 + z.horde : (Math.floor(z.pos.x / 28) + 50) * 100 + Math.floor(z.pos.z / 28) + 50; }
/** fresh for about two days, a shambler for a fortnight, a husk after that */
function ageToDecay(days: number) { return days < 2 ? days * 0.11 : days < 12 ? 0.22 + (days - 2) * 0.055 : Math.min(1, 0.77 + (days - 12) * 0.02); }
function decayToAge(d: number) { return d < 0.22 ? d / 0.11 : d < 0.77 ? 2 + (d - 0.22) / 0.055 : 12 + (d - 0.77) / 0.02; }

function raySphere(o: THREE.Vector3, d: THREE.Vector3, cx: number, cy: number, cz: number, r: number) {
  const ox = o.x - cx, oy = o.y - cy, oz = o.z - cz;
  const b = ox * d.x + oy * d.y + oz * d.z; const c = ox * ox + oy * oy + oz * oz - r * r;
  const disc = b * b - c; if (disc < 0) return -1;
  return -b - Math.sqrt(disc);
}
