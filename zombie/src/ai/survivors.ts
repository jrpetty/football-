// The living. Each person has a past, a temperament, skills that grow with use, friends, moods and a mind of
// their own: they path around buildings, keep a daily routine, chat, fight or run according to their nerve,
// get bitten, sicken and — if nobody helps — turn.
import * as THREE from 'three';
import { RNG, angleDiff, clamp } from '../core/util';
import { World } from '../world/world';
import type { Zombies, HumanPose, Zombie } from './zombies';
import { Inventory } from '../items/inventory';
import { rollLoot, ContainerKind } from '../items/loot';
import { audio } from '../audio/Audio';
import type { Particles } from '../render/particles';
import { Persona, PSkill, ACT, TOOL, makePersona, has, lvl, level, maxHp, gainXp, think, prune, line, PSKILLS, rankName, P_XP } from './people';
import { findPath, straight } from './pathing';

export type Order = 'follow' | 'guard' | 'scavenge' | 'wait' | 'work';
export type Job = 'idle' | 'farmer' | 'cook' | 'guard' | 'lumberjack' | 'mechanic' | 'medic';
export type Mode = 'routine' | 'fight' | 'flee';
export interface Task { kind: string; x: number; z: number; t: number; phase: number; ref?: number }
export interface Survivor {
  id: number; name: string; role: 'shooter' | 'brawler' | 'scavenger'; pos: THREE.Vector3; yaw: number; speed: number; hp: number;
  recruited: boolean; order: Order; ammo: number; hungry: number; away: number; pose: HumanPose; anchor: THREE.Vector3;
  target: Zombie | null; fireT: number; senseT: number; dead: boolean; kind: 'survivor'; bark: number;
  /** town life */ job: Job; home: number; work: number; happy: number; thirsty: number; gloom: number; meal: boolean; jobLock: boolean;
  /** the individual */ p: Persona;
  doing: string; say: string; sayT: number; greetT: number;
  mode: Mode; goal: TownGoal | null; hidden: boolean;
  path: number[] | null; pathI: number; pathGoal: THREE.Vector3; pathT: number; stuckT: number; lastX: number; lastZ: number;
  /** 0 = clean … 1 = turns */ infect: number; antibio: number;
  /** seconds a corpse lies before it gets back up (0 = no body) */ body: number; willTurn: boolean;
  chat: { with: number; t: number; lead: boolean } | null; chatCd: number;
  task: Task | null; nightShift: boolean; threat: number;
}
export interface TownGoal { pos: THREE.Vector3; want: number; hide?: boolean; face?: number; perch?: boolean; act?: number; tool?: number; doing?: string; social?: boolean; arrive?: number }
export interface PeopleCtx {
  player: THREE.Vector3; playerYaw?: number; hub: THREE.Vector3 | null; particles: Particles;
  notify: (m: string, k?: string) => void; deposit: (inv: Inventory) => void;
  townGoal?: (s: Survivor) => TownGoal | null;
  /** game minutes since the start, hour of day, 0..1 daylight */ now?: number; hours?: number; daylight?: number;
  /** someone saw the dead inside town */ alarm?: (at: THREE.Vector3) => void;
}

const JOB_TOOL: Record<Job, number> = { idle: TOOL.none, farmer: TOOL.hoe, cook: TOOL.ladle, guard: TOOL.gun, lumberjack: TOOL.axe, mechanic: TOOL.wrench, medic: TOOL.medbag };
const JOB_MELEE: Record<Job, number> = { idle: 0, farmer: 5, cook: 2, guard: 4, lumberjack: 12, mechanic: 6, medic: 0 };

export class Survivors {
  list: Survivor[] = [];
  private rng: RNG; private nextId = 1;
  private now = 0; private hours = 12; private daylight = 1;
  private pathBudget = 0;
  /** until zombies.ts reports its own hits on people, the dead hurt whoever they touch */
  contactDamage = true;
  /** hooks for the game */
  onLevelUp: ((s: Survivor, skill: PSkill | null, lvl: number) => void) | null = null;
  constructor(private world: World, private zombies: Zombies, seed: number) { this.rng = new RNG(seed + 4141); }

  spawn(x: number, z: number, recruited = false, persona?: Persona): Survivor {
    const p = persona ?? makePersona(this.rng, n => this.list.some(o => !o.dead && o.name === n), 1);
    const pose: HumanPose = { pos: new THREE.Vector3(x, this.world.groundY(x, z), z), yaw: this.rng.range(0, 6.28), move: 0, phase: 0, arms: 0, atk: 0, seed: this.rng.next() * 100, type: 4, hit: 0, visible: true };
    this.zombies.humans.push(pose);
    const role = lvl(p, 'shooting') >= 3 ? 'shooter' : lvl(p, 'melee') >= 3 ? 'brawler' : lvl(p, 'scavenging') >= 3 ? 'scavenger' : this.rng.pick(['shooter', 'brawler', 'scavenger'] as const);
    const s: Survivor = {
      id: this.nextId++, name: p.first, role, pos: pose.pos, yaw: pose.yaw, speed: 0, hp: maxHp(p),
      recruited, order: recruited ? 'follow' : 'wait', ammo: role === 'shooter' ? 18 + lvl(p, 'shooting') * 3 : 0, hungry: 0, away: 0, pose, anchor: new THREE.Vector3(x, 0, z),
      target: null, fireT: 0, senseT: this.rng.next() * 0.4, dead: false, kind: 'survivor', bark: 0,
      job: 'idle', home: -1, work: -1, happy: 60, thirsty: 0, gloom: 0, meal: false, jobLock: false,
      p, doing: 'Keeping their head down', say: '', sayT: 0, greetT: 0, mode: 'routine', goal: null, hidden: false,
      path: null, pathI: 0, pathGoal: new THREE.Vector3(), pathT: 0, stuckT: 0, lastX: x, lastZ: z,
      infect: 0, antibio: 0, body: 0, willTurn: false, chat: null, chatCd: this.rng.range(20, 90), task: null, nightShift: false, threat: 0,
    };
    Object.assign(pose, { look: p.look, owner: s, name: p.first, prey: true });
    this.list.push(s);
    return s;
  }
  /** a few survivors holed up around the map */
  populate() {
    const spots = this.world.town.buildings.filter(b => ['police', 'clinic', 'barn', 'church', 'school', 'cabin'].includes(b.kind) && Math.hypot(b.x + 176, b.z + 206) > 20);
    for (let i = 0; i < Math.min(5, spots.length); i++) {
      const b = spots[(i * 3) % spots.length];
      const sp = b.zombieSpawns[0] ?? new THREE.Vector3(b.x, b.floorY, b.z);
      this.spawn(sp.x, sp.z);
      // clear zombies right on top of them
      for (const z of this.zombies.list) if (z.pos.distanceTo(sp) < 6) z.hp = 0, z.state = 'dead', z.death = 1, z.corpse = null;
    }
  }
  line(kind: string, s?: Survivor) { return line(this.rng, kind, s?.p); }
  speak(s: Survivor, kind: string, dur = 3.6) { this.sayText(s, this.line(kind, s), dur); }
  sayText(s: Survivor, text: string, dur = 3.6) { s.say = text; s.sayT = dur; }
  byId(id: number) { return this.list.find(s => s.id === id) ?? null; }

  nearest(p: THREE.Vector3, r: number) { let best: Survivor | null = null, bd = r * r; for (const s of this.list) { if (s.dead || s.away > 0 || s.hidden) continue; const d = s.pos.distanceToSquared(p); if (d < bd) { bd = d; best = s; } } return best; }
  /** a fallen resident who hasn't got back up yet */
  bodyNear(p: THREE.Vector3, r: number) { let best: Survivor | null = null, bd = r * r; for (const s of this.list) { if (!s.dead || s.body <= 0) continue; const d = s.pos.distanceToSquared(p); if (d < bd) { bd = d; best = s; } } return best; }

  // ------------------------------------------------------------------ experience
  xp(s: Survivor, skill: PSkill, amount: number, ctx?: { notify: (m: string, k?: string) => void }) {
    const before = level(s.p);
    const up = gainXp(s.p, skill, amount);
    if (up) {
      const def = PSKILLS.find(q => q.id === skill)!;
      if (s.recruited) ctx?.notify(`${def.icon} ${s.name} is now ${def.name} ${up}.`, 'good');
      if (!s.hidden && s.away <= 0) this.speak(s, 'levelup');
      think(s.p, this.now, 'learned', `Getting better at ${def.name.toLowerCase()}`, 4, 12);
      this.onLevelUp?.(s, skill, up);
    }
    const after = level(s.p);
    if (after > before) {
      const mh = maxHp(s.p); s.hp = Math.min(mh, s.hp + 10);
      if (s.recruited) ctx?.notify(`⭐ ${s.name} reached level ${after} — ${rankName(after)}.`, 'good');
      this.onLevelUp?.(s, null, after);
    }
  }
  /** bond between two people grows or sours */
  bond(a: Survivor, b: Survivor, d: number) {
    a.p.bonds[b.id] = clamp((a.p.bonds[b.id] ?? 0) + d, -100, 100);
    b.p.bonds[a.id] = clamp((b.p.bonds[a.id] ?? 0) + d, -100, 100);
  }

  // ------------------------------------------------------------------ harm
  /** a zombie landed a hit on this person */
  attacked(s: Survivor, z: Zombie | null, dmg: number, ctx?: { notify: (m: string, k?: string) => void }) {
    if (s.dead || s.hidden) return;
    const tough = has(s.p, 'tough');
    s.hp -= dmg * (tough ? 0.75 : 1); s.pose.hit = 1; s.p.wounds++;
    audio.play('player_hurt', { pos: s.pos, pitch: 1.15 + (s.id % 5) * 0.04, volume: 0.7 });
    if (this.rng.chance(0.3)) this.speak(s, 'hurt', 2.2);
    think(s.p, this.now, 'hurt', 'Was mauled by the dead', -6, 18);
    // did it break the skin?
    if (s.infect <= 0 && z && this.rng.chance((z.type === 2 ? 0.34 : 0.22) * (tough ? 0.5 : 1))) {
      s.infect = 0.02;
      if (s.recruited) { ctx?.notify(`🩸 ${s.name} has been bitten. Antiviral serum will save them; antibiotics at the clinic only buy time.`, 'bad'); this.speak(s, 'bitten', 5); }
      think(s.p, this.now, 'bitten', 'Bitten. Knows what that means', -18, 72);
    }
    if (s.hp <= 0) this.die(s, true, ctx);
  }
  die(s: Survivor, byZombie: boolean, ctx?: { notify: (m: string, k?: string) => void }) {
    if (s.dead) return;
    s.dead = true; s.hp = 0; s.target = null; s.chat = null; s.hidden = false; s.speed = 0;
    s.willTurn = byZombie || s.infect > 0;
    s.body = s.willTurn ? this.rng.range(45, 100) : 240;
    Object.assign(s.pose, { prey: false, act: ACT.lie, tool: TOOL.none, sick: 1 }); s.pose.move = 0; s.pose.arms = 0; s.pose.visible = true;
    if (s.recruited) ctx?.notify(`💀 ${s.p.first} ${s.p.last} is dead.${s.willTurn ? ' They won\'t stay down for long — lay them to rest before they rise.' : ''}`, 'bad');
    this.mourn(s, false);
  }
  /** the fever wins */
  private turn(s: Survivor, ctx: { notify: (m: string, k?: string) => void }) {
    if (!s.dead) { s.dead = true; s.hp = 0; this.mourn(s, true); if (s.recruited) ctx.notify(`🧟 ${s.p.first} ${s.p.last} has turned.`, 'bad'); }
    else if (s.recruited) ctx.notify(`🧟 ${s.name}'s body is getting back up.`, 'bad');
    s.body = 0; s.pose.visible = false; (s.pose as any).prey = false;
    const zs = this.zombies as any;
    if (typeof zs.spawnTurned === 'function') zs.spawnTurned(s.pose);
    else { const z = this.zombies.spawn(s.pos.x, s.pos.z, 1); if (z) z.state = 'chase'; }
    audio.play('zombie_alert', { pos: s.pos });
  }
  /** player puts a body beyond the reach of the fever */
  layToRest(s: Survivor) {
    if (!s.dead || s.body <= 0) return false;
    s.body = 0; s.willTurn = false; s.pose.visible = false;
    for (const o of this.list) if (!o.dead && o.recruited) think(o.p, this.now, 'rest' + s.id, `${s.name} was laid to rest properly`, 3, 36);
    return true;
  }
  private mourn(s: Survivor, turned: boolean) {
    for (const o of this.list) {
      if (o === s || o.dead || !o.recruited || !s.recruited) continue;
      const b = o.p.bonds[s.id] ?? 0;
      const v = -(5 + Math.max(0, b) / 7) * (has(o.p, 'gloomy') ? 1.3 : 1) + (b < -30 ? 4 : 0);
      think(o.p, this.now, 'death' + s.id, b > 40 ? `Lost a friend: ${s.name}${turned ? ' turned' : ''}` : `${s.name} ${turned ? 'turned' : 'died'}`, Math.round(v), 60);
      if (b > 40 && !o.hidden && o.pos.distanceToSquared(s.pos) < 900) this.speak(o, 'grief', 5);
    }
  }
  /** give the antiviral: clears the infection */
  cure(s: Survivor) { const was = s.infect > 0; s.infect = 0; s.antibio = 0; Object.assign(s.pose, { sick: 0 }); s.p.thoughts = s.p.thoughts.filter(t => t.id !== 'bitten' && t.id !== 'fever'); if (was) think(s.p, this.now, 'cured', 'Was pulled back from the fever', 14, 72); return was; }

  // ------------------------------------------------------------------ per-frame
  update(dt: number, dtMin: number, ctx: PeopleCtx) {
    this.now = ctx.now ?? this.now + dtMin; this.hours = ctx.hours ?? this.hours; this.daylight = ctx.daylight ?? this.daylight;
    this.pathBudget = 2;
    for (const s of this.list) {
      const pose = s.pose as HumanPose & { act?: number; tool?: number; sick?: number; prey?: boolean };
      if (s.dead) {
        if (s.body > 0) { s.body -= dt; pose.visible = true; pose.act = ACT.lie; pose.move = 0; if (s.body <= 0) { if (s.willTurn) this.turn(s, ctx); else pose.visible = false; } }
        else pose.visible = false;
        continue;
      }
      // away scavenging
      if (s.away > 0) {
        s.away -= dtMin; pose.visible = false; pose.prey = false; s.doing = 'Out scavenging';
        if (s.away <= 0) this.returnFromRun(s, ctx);
        continue;
      }
      s.sayT = Math.max(0, s.sayT - dt); s.greetT = Math.max(0, s.greetT - dt); s.chatCd = Math.max(0, s.chatCd - dt);
      // the bite
      if (s.infect > 0) {
        const before = s.infect;
        s.infect += dtMin / (60 * (has(s.p, 'resistant') ? 52 : 30)) * (s.antibio > 0 ? 0.3 : 1);
        s.antibio = Math.max(0, s.antibio - dtMin / 60);
        if (before < 0.35 && s.infect >= 0.35) { think(s.p, this.now, 'fever', 'Burning with fever', -12, 60); if (s.recruited) ctx.notify(`🤒 ${s.name} is running a fever — the bite is taking hold. Serum can still save them.`, 'bad'); this.speak(s, 'fever', 5); }
        if (before < 0.75 && s.infect >= 0.75 && s.recruited) ctx.notify(`🥀 ${s.name} is fading fast. They will turn within hours.`, 'bad');
        if (s.infect >= 1) { this.turn(s, ctx); continue; }
      }
      pose.sick = s.infect > 0.3 ? clamp((s.infect - 0.3) / 0.5, 0.15, 1) : 0;
      prune(s.p, this.now);

      // ---- think a few times a second
      s.senseT -= dt;
      if (s.senseT <= 0) { s.senseT = 0.3 + this.rng.next() * 0.2; this.thinkOne(s, ctx); }

      const mh = maxHp(s.p);
      if (s.hidden) { // safe indoors
        pose.visible = false; pose.prey = false; s.speed = 0; s.target = null;
        if (s.hp < mh) s.hp = Math.min(mh, s.hp + dtMin * 0.15);
        continue;
      }
      pose.visible = true; pose.prey = true;

      // ---- act
      let dest: THREE.Vector3 | null = null, want = 0, face: number | undefined, perch: THREE.Vector3 | null = null, act: number = ACT.none, tool: number = TOOL.none, arrive = 1.1;
      const g = s.goal;
      if (g) {
        const dh = Math.hypot(s.pos.x - g.pos.x, s.pos.z - g.pos.z);
        arrive = g.arrive ?? 1.1;
        if (g.perch && dh < 2.4) { perch = g.pos; face = g.face; act = g.act ?? ACT.none; tool = g.tool ?? TOOL.none; }
        else if (dh > arrive) { dest = g.pos; want = g.want; if (g.act === ACT.carry) act = ACT.carry; }
        else { face = g.face; act = g.act ?? ACT.none; tool = g.tool ?? TOOL.none; }
      }
      const z = s.target;
      s.fireT -= dt; pose.arms = 0;
      const armed = (s.role === 'shooter' || s.job === 'guard') && s.ammo > 0;
      if (s.mode === 'fight' && z && z.state !== 'dead') {
        const d = Math.hypot(z.pos.x - s.pos.x, z.pos.z - s.pos.z);
        const toZ = Math.atan2(z.pos.x - s.pos.x, z.pos.z - s.pos.z);
        act = ACT.none;
        if (armed && d > 2.2) {
          s.yaw += angleDiff(s.yaw, toZ) * Math.min(1, dt * 8); pose.arms = 1; tool = TOOL.gun; face = undefined;
          const sk = lvl(s.p, 'shooting');
          if (s.fireT <= 0 && Math.abs(angleDiff(s.yaw, toZ)) < 0.25) {
            s.fireT = Math.max(0.45, 1.1 - sk * 0.05) * (0.85 + this.rng.next() * 0.3); s.ammo--;
            const dir = z.pos.clone().sub(s.pos).normalize();
            const dark = (1 - this.daylight) * (has(s.p, 'nightowl') ? 0.03 : 0.16);
            const hitP = clamp(0.36 + sk * 0.055 + (has(s.p, 'deadeye') ? 0.12 : 0) - d * 0.007 - dark - (pose.sick ?? 0) * 0.2, 0.08, 0.96);
            if (this.rng.chance(hitP)) {
              const head = this.rng.chance(0.08 + sk * 0.03);
              const killed = this.zombies.damage(z, 48, dir, head, 0.6, false);
              ctx.particles.blood(z.pos.clone().setY(z.pos.y + 1.3), dir, 0.6);
              this.xp(s, 'shooting', killed ? 12 : 3, ctx);
              if (killed) this.scored(s);
            } else this.xp(s, 'shooting', 0.6, ctx);
            const mz = s.pos.clone().setY(s.pos.y + 1.4).addScaledVector(dir, 0.6); ctx.particles.muzzle(mz, dir);
            audio.play('gunshot_pistol', { pos: s.pos, volume: 0.8 }); this.zombies.noise(s.pos, 55);
            if (s.ammo === 0) { this.speak(s, 'noammo', 3); if (s.recruited) ctx.notify(`${s.name}: "${s.say}"`, 'bad'); }
          }
          // keep them at arm's length
          if (d < 4.5 && !perch) { dest = s.pos.clone().add(new THREE.Vector3(s.pos.x - z.pos.x, 0, s.pos.z - z.pos.z).normalize().multiplyScalar(3)); want = 2.6; arrive = 0.3; } else { dest = null; want = 0; }
        } else if (d < 1.9 && !perch) {
          s.yaw += angleDiff(s.yaw, toZ) * Math.min(1, dt * 8); face = undefined;
          tool = s.role === 'brawler' ? TOOL.bat : JOB_TOOL[s.job] === TOOL.gun ? TOOL.none : JOB_TOOL[s.job];
          if (s.fireT <= 0) {
            const sk = lvl(s.p, 'melee');
            s.fireT = Math.max(0.55, 1.0 - sk * 0.035); pose.atk = 1;
            const dir = z.pos.clone().sub(s.pos).normalize();
            const dmg = (16 + sk * 4 + (s.role === 'brawler' ? 10 : 0) + JOB_MELEE[s.job]) * (1 - (pose.sick ?? 0) * 0.4);
            const killed = this.zombies.damage(z, dmg, dir, this.rng.chance(0.1 + sk * 0.02), 1.1 + sk * 0.05, false);
            audio.play('hit_flesh', { pos: z.pos });
            this.xp(s, 'melee', killed ? 12 : 4, ctx);
            if (killed) this.scored(s);
          }
          dest = null; want = 0;
        } else if (!perch && !armed) { dest = z.pos; want = 3.6; arrive = 1.5; tool = s.role === 'brawler' ? TOOL.bat : JOB_TOOL[s.job] === TOOL.gun ? TOOL.none : JOB_TOOL[s.job]; }
        else if (!perch) { dest = null; want = 0; }
      } else if (s.mode === 'flee') {
        act = ACT.none; tool = TOOL.none;
        if (g && Math.hypot(s.pos.x - g.pos.x, s.pos.z - g.pos.z) <= arrive) act = ACT.cower;
      }
      pose.atk = Math.max(0, pose.atk - dt * 3);
      // conversation: stand close, face each other, take turns
      if (s.chat && s.mode === 'routine') {
        const o = this.byId(s.chat.with);
        if (!o || o.dead || o.hidden || !o.chat || o.chat.with !== s.id || o.mode !== 'routine') s.chat = null;
        else {
          const d = Math.hypot(o.pos.x - s.pos.x, o.pos.z - s.pos.z);
          if (d > 1.7) { dest = o.pos; want = 1.6; arrive = 1.5; act = ACT.none; }
          else { dest = null; want = 0; face = Math.atan2(o.pos.x - s.pos.x, o.pos.z - s.pos.z); act = ACT.talk; tool = TOOL.none; s.doing = `Talking with ${o.name}`; }
          s.chat.t -= dt;
          if (s.chat.lead && d <= 2.2 && s.sayT <= 0 && o.sayT <= 0) { const who = this.rng.chance(0.5) ? s : o; const other = who === s ? o : s; this.speak(who, (s.p.bonds[o.id] ?? 0) < -30 ? 'argue' : 'chat', 3.4); if (this.rng.chance(0.5)) { other.say = this.line('chat_reply', other); other.sayT = 5.2; } }
          if (s.chat.t <= 0) { if (s.chat.lead) this.endChat(s, o); s.chat = null; s.chatCd = this.rng.range(50, 160); }
        }
      }
      // glance at the player
      {
        const pd = s.pos.distanceTo(ctx.player);
        const ty = Math.atan2(ctx.player.x - s.pos.x, ctx.player.z - s.pos.z);
        pose.lookYaw = z && z.state !== 'dead' && s.mode !== 'routine' ? 0 : clamp(angleDiff(s.yaw, ty), -1, 1) * (pd < 6 && !s.chat ? 1 : 0);
        if (pd < 3.2 && s.greetT <= 0 && s.mode === 'routine' && !s.chat && s.sayT <= 0 && Math.abs(angleDiff(s.yaw, ty)) < 1.4) {
          s.greetT = this.rng.range(70, 160);
          const k = !s.recruited ? 'stranger' : s.hungry ? 'hungry' : s.thirsty ? 'thirsty' : s.order === 'work' && s.home < 0 ? 'homeless' : s.infect > 0.35 ? 'fever' : (this.hours >= 22 || this.hours < 5) ? 'greet_night' : this.rng.chance(0.45) && s.order === 'work' ? s.job : 'greet';
          this.speak(s, k, 4);
        }
      }
      // legacy contact damage (until zombies report their own hits)
      if (this.contactDamage) for (const zz of this.zombies.list) {
        if (zz.state === 'dead' || zz.frozen) continue;
        if (zz.pos.distanceToSquared(s.pos) < 1.2 && this.rng.chance(dt * 0.9)) { this.attacked(s, zz, zz.type === 2 ? 25 : 9, ctx); if (s.dead) break; }
      }
      if (s.dead) continue;
      pose.hit = Math.max(0, pose.hit - dt * 3);
      if (s.hp < mh) s.hp = Math.min(mh, s.hp + dtMin * (act === ACT.sit || act === ACT.lie ? 0.12 : 0.04));

      // ---- move
      const slow = (1 - (pose.sick ?? 0) * 0.45) * (has(s.p, 'frail') ? 0.9 : 1) * (s.p.age > 62 ? 0.92 : 1) * (s.hp < mh * 0.35 ? 0.8 : 1);
      if (perch) { s.pos.copy(perch); s.speed = 0; s.path = null; if (face !== undefined && s.mode !== 'fight') s.yaw += angleDiff(s.yaw, face + Math.sin(this.now * 0.05 + s.id) * 0.9) * Math.min(1, dt * 1.5); }
      else {
        let steer: THREE.Vector3 | null = null;
        if (dest && want > 0) steer = this.route(s, dest, dt);
        if (steer) { s.yaw += angleDiff(s.yaw, Math.atan2(steer.x - s.pos.x, steer.z - s.pos.z)) * Math.min(1, dt * 7); }
        else if (face !== undefined && s.mode !== 'fight') s.yaw += angleDiff(s.yaw, face) * Math.min(1, dt * 3);
        const turnK = steer ? Math.max(0.25, Math.cos(angleDiff(s.yaw, Math.atan2(steer.x - s.pos.x, steer.z - s.pos.z)))) : 0;
        s.speed += ((steer ? want * slow * turnK : 0) - s.speed) * Math.min(1, dt * 5);
        if (s.speed > 0.01) {
          s.pos.x += Math.sin(s.yaw) * s.speed * dt; s.pos.z += Math.cos(s.yaw) * s.speed * dt;
          this.world.col.resolveCylinder(s.pos, 0.3, 1.7, 0.45, 'door', 'pdoor');
          // don't walk through each other or the player
          for (const o of this.list) { if (o === s || o.dead || o.hidden || o.away > 0) continue; const dx = s.pos.x - o.pos.x, dz = s.pos.z - o.pos.z, d2 = dx * dx + dz * dz; if (d2 < 0.3 && d2 > 1e-5) { const d = Math.sqrt(d2), push = (0.55 - d) * 0.5; s.pos.x += dx / d * push; s.pos.z += dz / d * push; } }
        }
        const pd = Math.hypot(s.pos.x - ctx.player.x, s.pos.z - ctx.player.z); if (pd < 0.7 && pd > 1e-3 && Math.abs(s.pos.y - ctx.player.y) < 1.6) { s.pos.x += (s.pos.x - ctx.player.x) / pd * (0.7 - pd); s.pos.z += (s.pos.z - ctx.player.z) / pd * (0.7 - pd); }
        s.pos.y += (this.world.groundY(s.pos.x, s.pos.z, s.pos.y + 0.6) - s.pos.y) * Math.min(1, dt * 10);
        // stuck?
        if (steer && want > 0.5) {
          const mv = Math.hypot(s.pos.x - s.lastX, s.pos.z - s.lastZ);
          if (mv < want * dt * 0.15) { s.stuckT += dt; if (s.stuckT > 1.2) { s.pathT = 0; s.path = null; s.yaw += this.rng.range(-1.2, 1.2); s.stuckT = 0.4; } } else s.stuckT = Math.max(0, s.stuckT - dt * 2);
        }
        s.lastX = s.pos.x; s.lastZ = s.pos.z;
      }
      // work noises
      if (act === ACT.swing && s.speed < 0.2) { s.bark -= dt; if (s.bark <= 0) { s.bark = 1.25; if (s.pos.distanceToSquared(ctx.player) < 1600) audio.play(tool === TOOL.axe ? 'chop' : 'build_hammer', { pos: s.pos, volume: 0.5 }); } }
      // pose
      pose.act = s.speed > 0.4 && act !== ACT.carry ? ACT.none : act; pose.tool = tool;
      pose.yaw = s.yaw; pose.move = s.speed < 0.2 ? 0 : s.speed < 4 ? 1 : 1.6;
      pose.phase += dt * (s.speed * 3.2 + 0.1);
    }
  }

  /** follow a path to the destination; returns the point to steer at */
  private route(s: Survivor, dest: THREE.Vector3, dt: number): THREE.Vector3 | null {
    s.pathT -= dt;
    const moved = Math.hypot(dest.x - s.pathGoal.x, dest.z - s.pathGoal.z);
    if (s.pathT <= 0 || moved > 2.5) {
      const col = this.world.col;
      if (straight(col, s.pos.x, s.pos.z, dest.x, dest.z)) { s.path = null; s.pathGoal.copy(dest); s.pathT = 1.2; }
      else if (this.pathBudget > 0) {
        this.pathBudget--;
        s.path = findPath(col, s.pos.x, s.pos.z, dest.x, dest.z); s.pathI = 0; s.pathGoal.copy(dest); s.pathT = s.path ? 3.5 + this.rng.next() : 1.5;
      } else if (moved > 2.5) s.pathT = 0; // try again next frame
    }
    if (s.path) {
      while (s.pathI < s.path.length - 2 && Math.hypot(s.path[s.pathI] - s.pos.x, s.path[s.pathI + 1] - s.pos.z) < 0.6) s.pathI += 2;
      if (s.pathI >= s.path.length) { s.path = null; return dest; }
      if (s.pathI === s.path.length - 2 && Math.hypot(s.path[s.pathI] - s.pos.x, s.path[s.pathI + 1] - s.pos.z) < 0.6) { s.path = null; return dest; }
      return TMP.set(s.path[s.pathI], 0, s.path[s.pathI + 1]);
    }
    return dest;
  }

  private scored(s: Survivor) {
    s.p.kills++; s.p.bonusXp += 6;
    if (this.rng.chance(0.4) && s.sayT <= 0) this.speak(s, 'kill', 2.5);
    think(s.p, this.now, 'kill', has(s.p, 'coward') ? 'Had to kill one. Still shaking' : 'Put one of the dead down', has(s.p, 'coward') ? -3 : has(s.p, 'brave') ? 5 : 2, 10);
    if (s.p.kills === 10) think(s.p, this.now, 'blooded', 'Ten kills. Not afraid of them any more', 6, 24 * 30);
  }
  private endChat(a: Survivor, b: Survivor) {
    let d = 3 + this.rng.range(-3, 4);
    for (const q of [a, b]) { if (has(q.p, 'cheerful') || has(q.p, 'sociable')) d += 1.2; if (has(q.p, 'gloomy')) d -= 1; if (has(q.p, 'loner')) d -= 0.8; }
    if (a.p.bg === b.p.bg) d += 1; if (Math.abs(a.p.age - b.p.age) < 8) d += 0.5;
    if (a.home >= 0 && a.home === b.home) d += 0.5;
    const before = a.p.bonds[b.id] ?? 0;
    this.bond(a, b, d);
    const after = a.p.bonds[b.id];
    for (const [q, o] of [[a, b], [b, a]] as [Survivor, Survivor][]) {
      if (d > 0) think(q.p, this.now, 'chat', `Good talk with ${o.name}`, has(q.p, 'sociable') ? 5 : has(q.p, 'loner') ? 1 : 3, 8);
      else think(q.p, this.now, 'row', `Argued with ${o.name}`, -3, 8);
      if (has(o.p, 'cheerful') && d > 0) think(q.p, this.now, 'cheered', `${o.name} cheered them up`, 3, 10);
    }
    if (before < 40 && after >= 40) { for (const q of [a, b]) think(q.p, this.now, 'friend', 'Made a friend', 6, 48); }
  }

  /** senses → fight / flee / carry on; then pick where to go */
  private thinkOne(s: Survivor, ctx: PeopleCtx) {
    const p = s.p; const mh = maxHp(p);
    const armed = (s.role === 'shooter' || s.job === 'guard') && s.ammo > 0;
    const perched = !!s.goal?.perch && Math.hypot(s.pos.x - s.goal.pos.x, s.pos.z - s.goal.pos.z) < 2.4;
    // ---- what can they see?
    const reach = armed ? (perched ? 36 : 22) : 15;
    let best: Zombie | null = null, bd = reach * reach, near = 0, closest = 1e9, cz: Zombie | null = null;
    if (!s.hidden) for (const z of this.zombies.list) {
      if (z.state === 'dead' || z.frozen) continue;
      const d = z.pos.distanceToSquared(s.pos);
      if (d > 1400) continue;
      if (d < 100) near++;
      if (d < closest) { closest = d; cz = z; }
      if (d < bd && !this.world.col.blocked(s.pos.x, s.pos.y + 1.5, s.pos.z, z.pos.x, z.pos.y + 1.4, z.pos.z)) { bd = d; best = z; }
    }
    closest = Math.sqrt(closest);
    s.target = best; s.threat = near;
    // ---- nerve
    let allies = 0; for (const o of this.list) if (o !== s && !o.dead && !o.hidden && o.recruited && o.pos.distanceToSquared(s.pos) < 100) allies++;
    if (s.pos.distanceToSquared(ctx.player) < 144) allies++;
    const nerve = 0.5 + (has(p, 'brave') ? 0.4 : 0) - (has(p, 'coward') ? 0.45 : 0) + lvl(p, 'melee') * 0.03 + level(p) * 0.01 + (armed ? 0.2 : 0) + allies * 0.06
      - Math.max(0, near - 1) * 0.09 - (1 - s.hp / mh) * 0.35 + (s.order === 'follow' ? 0.15 : 0) + (s.job === 'guard' ? 0.25 : 0) + (p.kills >= 10 ? 0.1 : 0) - (s.infect > 0.35 ? 0.2 : 0);
    const prev = s.mode;
    if (!best || s.hidden) s.mode = closest < 6 && nerve < 0.3 && !s.hidden ? 'flee' : 'routine';
    else {
      const d = Math.sqrt(bd);
      if (!s.recruited) s.mode = d < 1.9 && nerve > 0.5 ? 'fight' : d < 9 ? 'flee' : 'routine';
      else if (perched) s.mode = s.hp < mh * 0.25 ? 'flee' : 'fight';
      else if (s.hp < mh * 0.3 && !has(p, 'brave')) s.mode = 'flee';
      else if (nerve < 0.32) s.mode = d < 13 ? 'flee' : 'routine';
      else if (armed || d < 8 || has(p, 'brave') || s.order === 'follow' || s.job === 'guard') s.mode = 'fight';
      else s.mode = d < 5 ? 'fight' : 'routine';
    }
    if (prev === 'routine' && s.mode !== 'routine') {
      if (s.sayT <= 0) this.speak(s, s.mode === 'flee' ? (has(p, 'coward') || this.rng.chance(0.5) ? 'alarm' : 'flee') : 'fight', 3);
      if (s.recruited && s.order === 'work' && best) ctx.alarm?.(best.pos);
      s.chat = null; s.path = null; s.pathT = 0;
      if (s.mode === 'flee') think(p, this.now, 'scare', 'Ran for their life', has(p, 'coward') ? -3 : -5, 6);
    }
    // ---- where to?
    s.hidden = false;
    if (s.mode === 'flee') {
      let safe: TownGoal | null = null;
      if (s.recruited && s.order === 'work' && ctx.townGoal) { const tg = ctx.townGoal(s); if (tg && (tg.hide || tg.doing === 'Sheltering' || tg.doing === 'Running for cover')) safe = tg; }
      if (safe) { s.goal = safe; if (safe.hide) s.hidden = true; s.doing = 'Running for cover'; }
      else if (s.recruited && s.order === 'follow') { const yaw = ctx.playerYaw ?? 0; s.goal = { pos: ctx.player.clone().add(new THREE.Vector3(-Math.sin(yaw) * 2.2, 0, -Math.cos(yaw) * 2.2)), want: 5.4, arrive: 0.8 }; s.doing = 'Hiding behind you'; }
      else if (cz) { const away = new THREE.Vector3(s.pos.x - cz.pos.x, 0, s.pos.z - cz.pos.z).normalize(); s.goal = { pos: s.pos.clone().addScaledVector(away, 9), want: 5, arrive: 0.6 }; s.doing = 'Fleeing'; }
      return;
    }
    if (s.mode === 'fight') { s.doing = armed ? 'Shooting' : 'Fighting'; if (!s.goal?.perch) s.goal = null; if (s.recruited && s.order === 'work' && ctx.townGoal) { const tg = ctx.townGoal(s); if (tg?.perch) s.goal = tg; } return; }
    // routine
    if (s.recruited && s.order === 'work' && ctx.townGoal) {
      const tg = ctx.townGoal(s);
      s.goal = tg; s.doing = tg?.doing ?? 'Wandering';
      if (tg?.hide) s.hidden = true;
      // strike up a conversation when off duty
      if (tg?.social && !s.chat && s.chatCd <= 0 && !tg.hide) {
        for (const o of this.list) {
          if (o === s || o.dead || o.hidden || !o.recruited || o.chat || o.chatCd > 0 || o.mode !== 'routine' || !o.goal?.social) continue;
          if (o.pos.distanceToSquared(s.pos) > 196) continue;
          const dur = this.rng.range(14, 30); s.chat = { with: o.id, t: dur, lead: true }; o.chat = { with: s.id, t: dur, lead: false }; break;
        }
      }
    } else if (s.recruited && s.order === 'follow') {
      const followers = this.list.filter(o => !o.dead && o.recruited && o.order === 'follow' && o.away <= 0);
      const i = followers.indexOf(s); const yaw = ctx.playerYaw ?? 0;
      const side = (i % 2 ? 1 : -1) * (1.2 + Math.floor(i / 2) * 0.9), back = 2.0 + Math.floor(i / 2) * 1.2;
      const slot = new THREE.Vector3(ctx.player.x + Math.cos(yaw) * side - Math.sin(yaw) * back, ctx.player.y, ctx.player.z - Math.sin(yaw) * side - Math.cos(yaw) * back);
      const d = s.pos.distanceTo(ctx.player);
      s.goal = { pos: slot, want: d > 11 ? 6 : d > 5 ? 4.2 : 2.6, arrive: 1.2, tool: armed ? TOOL.gun : s.role === 'brawler' ? TOOL.bat : TOOL.none }; s.doing = 'Following you';
    } else if (s.recruited && s.order === 'guard') { const home = ctx.hub ?? s.anchor; s.goal = { pos: home, want: 3, arrive: 16 }; s.doing = 'Standing guard'; }
    else {
      // waiting, or a stranger holed up: stay put, shift about now and then
      if (!s.task || s.task.t < this.now) { const a = this.rng.range(0, 6.28), r = this.rng.range(0, s.recruited ? 1.5 : 3); s.task = { kind: 'loiter', x: s.anchor.x + Math.cos(a) * r, z: s.anchor.z + Math.sin(a) * r, t: this.now + this.rng.range(20, 90), phase: this.rng.chance(0.4) ? 1 : 0 }; }
      s.goal = { pos: new THREE.Vector3(s.task.x, s.pos.y, s.task.z), want: 1.3, arrive: 0.8, act: s.task.phase ? ACT.sit : ACT.none }; s.doing = s.recruited ? 'Waiting' : 'Holed up';
    }
  }

  private returnFromRun(s: Survivor, ctx: PeopleCtx) {
    const sk = lvl(s.p, 'scavenging');
    if (this.rng.chance(Math.max(0.02, 0.14 - sk * 0.013))) { s.dead = true; s.body = 0; ctx.notify(`📻 ${s.p.first} ${s.p.last} never came back from the scavenging run.`, 'bad'); this.mourn(s, false); return; }
    const inv = new Inventory();
    const kinds: ContainerKind[] = ['counter', 'shop_shelf', 'garage', 'medicine', 'crate'];
    for (const k of kinds) rollLoot(k, this.rng, 0.25 + sk * 0.07, inv);
    for (let i = 0; i < Math.floor(sk / 3) + (has(s.p, 'packrat') ? 1 : 0); i++) rollLoot(this.rng.pick(kinds), this.rng, 0.3 + sk * 0.07, inv);
    ctx.deposit(inv);
    s.pos.copy(ctx.hub ?? ctx.player).add(new THREE.Vector3(3, 0, 3)); s.order = ctx.hub ? 'work' : 'wait'; s.anchor.copy(s.pos); s.pose.visible = true;
    s.p.runs++;
    if (this.rng.chance(Math.max(0.03, 0.16 - sk * 0.012))) { s.hp = Math.max(15, s.hp - this.rng.range(20, 50)); s.p.wounds++; think(s.p, this.now, 'hurt', 'Came back from a run bleeding', -5, 18); }
    ctx.notify(`🎒 ${s.name} is back from scavenging with ${inv.stacks.reduce((a, q) => a + q.n, 0)} items (left at the Settlement Hall).`, 'good');
    this.xp(s, 'scavenging', 45, ctx);
  }

  /** daily food: each recruited survivor outside the town roster eats one food item from base storage */
  feed(take: () => boolean, notify: (m: string, k?: string) => void, town = false) {
    for (const s of this.list) {
      if (!s.recruited || s.dead || (town && (s.order === 'work' || s.order === 'guard'))) continue; // townsfolk are fed by the Town
      if (take()) s.hungry = 0;
      else {
        s.hungry++;
        notify(`${s.name}: "${this.line('hungry', s)}"`, 'bad');
        if (s.hungry >= 3) { s.recruited = false; s.order = 'wait'; s.anchor.copy(s.pos).add(new THREE.Vector3(40, 0, 40)); notify(`${s.name} left your group — there was no food.`, 'bad'); }
      }
    }
  }

  toJSON() {
    return this.list.filter(s => !s.dead).map(s => ({
      id: s.id, name: s.name, role: s.role, x: s.pos.x, z: s.pos.z, hp: s.hp, recruited: s.recruited, order: s.order, ammo: s.ammo, hungry: s.hungry, away: s.away, ax: s.anchor.x, az: s.anchor.z,
      job: s.job, home: s.home, work: s.work, happy: s.happy, thirsty: s.thirsty, gloom: s.gloom, jobLock: s.jobLock, p: s.p, infect: s.infect, antibio: s.antibio, night: s.nightShift,
    }));
  }
  load(arr: any[]) {
    for (const s of this.list) s.pose.visible = false;
    this.list = [];
    this.zombies.humans = this.zombies.humans.filter(h => h.type !== 4);
    let maxId = 0;
    for (const o of arr ?? []) {
      let persona: Persona | undefined = o.p;
      if (!persona) { persona = makePersona(this.rng, n => this.list.some(q => q.name === n), 1); persona.first = o.name ?? persona.first; }
      else { // older saves may lack newer fields
        const fresh = makePersona(new RNG((o.id ?? 1) * 977), () => false, 1);
        persona = { ...fresh, ...persona, xp: { ...fresh.xp, ...persona.xp }, thoughts: persona.thoughts ?? [], bonds: persona.bonds ?? {} };
      }
      const s = this.spawn(o.x, o.z, o.recruited, persona);
      Object.assign(s, { name: persona.first, role: o.role ?? s.role, hp: o.hp, order: o.order, ammo: o.ammo, hungry: o.hungry, away: o.away, job: o.job ?? 'idle', home: -1, work: -1, happy: o.happy ?? 60, thirsty: o.thirsty ?? 0, gloom: o.gloom ?? 0, jobLock: o.jobLock ?? false, infect: o.infect ?? 0, antibio: o.antibio ?? 0, nightShift: !!o.night });
      if (o.id) s.id = o.id;
      maxId = Math.max(maxId, s.id);
      s.anchor.set(o.ax, 0, o.az);
    }
    this.nextId = Math.max(this.nextId, maxId + 1);
  }
  clamp = clamp;
}
const TMP = new THREE.Vector3();
export { P_XP };
