// REKINDLE — main game orchestrator.
import * as THREE from 'three';
import { Renderer, Quality, QUALITY } from './render/renderer';
import { initTextures } from './render/textures';
import { Atmosphere } from './render/sky';
import { Weather } from './render/weather';
import { Particles } from './render/particles';
import { LightPool, Emitter } from './render/lights';
import { World, OreNode, Drop } from './world/world';
import { SPAWN, CABIN } from './world/layout';
import { Zombies, Zombie } from './ai/zombies';
import { Factory, Machine } from './factory/factory';
import { MACHINES, PIECES, MILESTONES } from './factory/defs';
import { Base, Piece } from './build/base';
import { Placer } from './build/placer';
import { Player } from './player/player';
import { ViewModel } from './player/viewmodel';
import { PARTS, PartId, WoundType } from './player/stats';
import { OCCUPATIONS, SkillId, SKILLS } from './player/skills';
import { Input } from './core/input';
import { audio } from './audio/Audio';
import { Inventory, Stack, makeStack } from './items/inventory';
import { item, ItemDef } from './items/items';
import { rollLoot } from './items/loot';
import { RECIPES, Recipe, Station } from './items/recipes';
import { Container, Door, Win } from './world/town';
import { TreeInst, windUniforms } from './world/vegetation';
import { RNG, clamp, bus, damp, lerp, smoothstep } from './core/util';
import { UI } from './ui/ui';
import { DistantMountains, CloudClusters } from './render/scenery';
import { AmbientParticles } from './render/ambient';
import { Animals, Animal } from './ai/animals';
import { Survivors, Survivor } from './ai/survivors';
import { Vehicles, Vehicle } from './vehicles/vehicles';
import type { TownCar } from './world/town';
import type { HumanPose } from './ai/zombies';

export type GameState = 'loading' | 'menu' | 'cc' | 'play' | 'dead' | 'victory';
export interface Interact { kind: string; label: string; sub?: string; key?: string; target: any; t: number; alt?: { label: string; act: () => void } }
export interface TimedAction { label: string; dur: number; t: number; cancelOnMove: boolean; done: () => void; icon?: string }
export interface Settings { quality: Quality; fov: number; sens: number; master: number; music: number; sfx: number; dayLen: number; creative: boolean; keys?: Record<string, string> }

const SAVE_KEY = 'rekindle_save_v1';
const SETTINGS_KEY = 'rekindle_settings_v1';
export const SEED = 20261111;

export const OBJECTIVES: { id: string; title: string; desc: string }[] = [
  { id: 'weapon', title: 'Arm yourself', desc: 'Search the cabin and find something to fight with. Equip it from the inventory (Tab) or hotbar (1-8).' },
  { id: 'supplies', title: 'Food & water', desc: 'Scavenge food and drink. Kitchens, fridges and stores are your best bet. Mind your moodles.' },
  { id: 'workbench', title: 'Set up a workbench', desc: 'Open the Build menu (B) and place a Workbench. Planks come from logs (axe/saw) or scavenging; nails from scrap.' },
  { id: 'shelter', title: 'Fortify a shelter', desc: 'Build at least 4 walls and a door — or barricade a house (hold a hammer, E on windows & doors).' },
  { id: 'hub', title: 'Establish the Outpost', desc: 'Build the Survivor Outpost (B → Special). It becomes your HQ and tech tree. Noise from your base will draw hordes.' },
  { id: 'm1', title: 'Milestone: Foothold', desc: 'Deliver planks, nails, scrap metal and electronics to the Outpost to unlock power and industry.' },
  { id: 'power', title: 'Spark the grid', desc: 'Place a Biomass Burner, fuel it, and connect machines with Power Poles.' },
  { id: 'mine', title: 'First automation', desc: 'Put a Miner on an ore deposit, run a Conveyor Belt into a Smelter, and collect ingots.' },
  { id: 'm2', title: 'Milestone: Industry', desc: 'Deliver ingots and sheet metal. Unlocks Constructors, Coal Generators, electric fences and metal walls.' },
  { id: 'm3', title: 'Milestone: Fortify', desc: 'Mass-produce plates, screws, wire and cable. Unlocks Assemblers and Auto Turrets.' },
  { id: 'm4', title: 'Milestone: Automation', desc: 'Complex parts: reinforced plates, rotors, circuits, concrete. Unlocks the Radio Tower.' },
  { id: 'm5', title: 'Milestone: Rekindle', desc: 'Frames, steel, circuits and radio parts. Unlocks the Medical Lab and the Grid Uplink.' },
  { id: 'cure', title: 'The Cure', desc: 'Collect Infected Tissue from the dead. Synthesise 10 Antiviral Serum in the Lab and deliver them to the Outpost.' },
  { id: 'done', title: 'Civilisation rekindled', desc: 'The lights are on. The broadcast is out. Keep building.' },
];

export class Game {
  canvas: HTMLCanvasElement;
  settings: Settings;
  renderer!: Renderer;
  scene = new THREE.Scene();
  camera: THREE.PerspectiveCamera;
  vm!: ViewModel;
  atmo!: Atmosphere;
  weather!: Weather;
  particles!: Particles;
  lights!: LightPool;
  mountains!: DistantMountains;
  cloudClusters!: CloudClusters;
  ambient!: AmbientParticles;
  world!: World;
  zombies!: Zombies;
  factory!: Factory;
  base!: Base;
  placer!: Placer;
  vehicles!: Vehicles;
  animals!: Animals;
  survivors!: Survivors;
  private lastFeedDay = 0;
  private loudT = 0;
  driving: Vehicle | null = null;
  thirdPerson = false;
  carCam = true;
  private avatar: HumanPose = { pos: new THREE.Vector3(), yaw: 0, move: 0, phase: 0, arms: 0, atk: 0, seed: 42.7, type: 3, hit: 0, visible: false };
  private camSmooth = new THREE.Vector3();
  player = new Player();
  input: Input;
  ui!: UI;
  state: GameState = 'loading';
  gameMin = 8 * 60;
  timeMul = 1;
  paused = false;
  buildMode = false;
  action: TimedAction | null = null;
  interact: Interact | null = null;
  flashlight: THREE.SpotLight;
  objective = 0;
  rng = new RNG(777);
  private last = 0;
  private time = 0;
  private menuT = 0;
  private swingHitAt = -1; private swingStack: Stack | null = null; private swingTimer = 0;
  private fireCooldown = 0; private reloading = 0; private reloadStack: Stack | null = null;
  private shoveCD = 0;
  private attackers = 0; private overwhelmT = 0;
  private townLampEmitters: Emitter[] = [];
  private streetEmitters: Emitter[] = [];
  private buildingEmitters = new Map<any, Emitter[]>();
  poweredBuildings = new Set<any>();
  private spoilT = 0;
  private genNoiseT = 0;
  private lastSave = 0;
  private music = 0;
  private fallingTrees: ((dt: number) => boolean)[] = [];
  combatHeat = 0;
  stats = { kills: 0, crafted: 0, built: 0, daysSurvived: 0 };
  hordeWarning = 0;
  openContainer: { inv: Inventory; label: string; ref: any } | null = null;
  openMachine: Machine | null = null;
  openPiece: Piece | null = null;
  station: Station = 'hand';
  explored = new Uint8Array(64 * 64);
  private thunderQ: { t: number; v: number }[] = [];
  private builtCounts: Record<string, number> = {};

  constructor(canvas: HTMLCanvasElement) {
    this.canvas = canvas;
    this.settings = { quality: 'high', fov: 75, sens: 1, master: 0.8, music: 0.45, sfx: 1, dayLen: 24, creative: false };
    try { const s = localStorage.getItem(SETTINGS_KEY); if (s) Object.assign(this.settings, JSON.parse(s)); } catch { /* */ }
    this.camera = new THREE.PerspectiveCamera(this.settings.fov, innerWidth / innerHeight, 0.08, 3000);
    this.input = new Input(canvas);
    if (this.settings.keys) this.input.setBindings(this.settings.keys);
    this.flashlight = new THREE.SpotLight(0xfff4e0, 0, 45, 0.45, 0.5, 2);
    this.flashlight.castShadow = true; this.flashlight.shadow.mapSize.set(512, 512); this.flashlight.shadow.bias = -0.0005;
    this.scene.add(this.flashlight); this.scene.add(this.flashlight.target);
  }

  saveSettings() { try { localStorage.setItem(SETTINGS_KEY, JSON.stringify(this.settings)); } catch { /* */ } }
  hasSave() { try { return !!localStorage.getItem(SAVE_KEY); } catch { return false; } }

  async boot(progress: (p: number, l: string) => void) {
    const cfg = QUALITY[this.settings.quality];
    this.renderer = new Renderer(this.canvas, this.settings.quality);
    progress(0.02, 'Generating textures');
    await initTextures('low', (p, l) => progress(0.02 + p * 0.3, l)); // low-poly style only needs a few utility textures
    const yieldF = () => new Promise(r => setTimeout(r, 0));
    this.atmo = new Atmosphere(this.scene, this.renderer.renderer, cfg.shadow, cfg.shadowExtent, cfg.far);
    this.weather = new Weather(this.scene, SEED);
    this.weather.onThunder = (delay, v) => this.thunderQ.push({ t: delay, v });
    this.particles = new Particles(this.scene);
    this.lights = new LightPool(this.scene, 8);
    this.mountains = new DistantMountains(this.scene, SEED);
    this.cloudClusters = new CloudClusters(this.scene, SEED);
    this.ambient = new AmbientParticles(this.scene);
    await yieldF();
    this.world = new World(this.scene, this.renderer.renderer, SEED, cfg, (p, l) => progress(0.32 + p * 0.55, l));
    await yieldF();
    progress(0.88, 'Waking the dead');
    this.zombies = new Zombies(this.scene, this.world, SEED);
    this.factory = new Factory(this.world, this.lights, this.scene);
    this.base = new Base(this.world, this.lights, this.scene);
    this.placer = new Placer(this.scene, this.world, this.factory, this.base);
    this.vehicles = new Vehicles(this.world, this.scene);
    this.animals = new Animals(this.world, this.scene, SEED);
    this.survivors = new Survivors(this.world, this.zombies, SEED);
    this.zombies.humans.push(this.avatar);
    this.vm = new ViewModel(this.settings.fov);
    this.renderer.setup(this.scene, this.camera, this.vm.scene, this.vm.camera);
    this.zombies.onHorde = (n, toBase) => {
      this.hordeWarning = 8;
      bus.emit('notify', toBase ? `☠ A horde of ~${n} is heading for your base! Your factory noise drew them in.` : `☠ A horde of ~${n} is migrating through the area. Stay quiet.`, 'bad');
      audio.play('zombie_alert', { volume: 0.4 });
    };
    // town lamp emitters (dark until the Grid Uplink is powered)
    for (const p of this.world.town.streetLamps) this.streetEmitters.push(this.lights.add({ pos: p.clone(), color: new THREE.Color(0xffd29a), intensity: 0, distance: 22, on: false }));
    for (const b of this.world.town.buildings) { const list: Emitter[] = []; for (const p of b.lamps) list.push(this.lights.add({ pos: p.clone(), color: new THREE.Color(0xffe0b0), intensity: 0, distance: 9, on: false })); this.buildingEmitters.set(b, list); }
    progress(0.94, 'Compiling shaders');
    this.ui = new UI(this);
    this.wireEvents();
    // warm-up render to compile shaders
    this.camera.position.set(0, 40, 120); this.camera.lookAt(0, 0, 0);
    this.atmo.update(9, 0.016, this.camera, this.camera.position, 0);
    this.renderer.renderer.compile(this.scene, this.camera);
    progress(1, 'Ready');
    addEventListener('resize', () => { this.camera.aspect = innerWidth / innerHeight; this.camera.updateProjectionMatrix(); this.renderer.resize(); });
    this.state = 'menu';
    this.ui.showMenu();
    requestAnimationFrame(t => this.frame(t));
  }

  private wireEvents() {
    bus.on('notify', (msg: string, kind?: string) => this.ui.notify(msg, kind));
    bus.on('milestone', (ms: any) => {
      this.ui.notify(`🏆 Milestone complete: ${ms.name}! Unlocked: ${ms.unlocks.map((u: string) => (MACHINES[u] ?? PIECES[u])?.name ?? u).join(', ') || '—'}`, 'good');
      this.checkObjectives();
    });
    bus.on('victory', () => { this.state = 'victory'; this.input.unlock(); this.ui.showVictory(); audio.play('milestone'); });
    this.player.skills.onLevelUp = (id, lvl) => {
      this.ui.notify(`⬆ ${SKILLS.find(s => s.id === id)!.name} reached level ${lvl}`, 'xp'); audio.play('objective');
      if (id === 'engineering') this.factory.engineering = lvl;
      if (id === 'carpentry') this.base.carpentry = lvl;
    };
    this.canvas.addEventListener('click', () => { if (this.state === 'play' && !this.paused && !this.ui.anyOpen() && !this.input.locked) this.input.lock(); });
    this.input.onLockChange = (l) => { if (!l && this.state === 'play' && !this.ui.anyOpen() && !this.paused) this.pause(true); };
  }

  // ------------------------------------------------------------------ game start
  newGame(cfg: { name: string; occupation: string; traits: string[] }) {
    try { localStorage.removeItem(SAVE_KEY); } catch { /* */ }
    const p = this.player;
    p.name = cfg.name || 'Survivor'; p.occupation = cfg.occupation; p.traits = new Set(cfg.traits);
    const occ = OCCUPATIONS.find(o => o.id === cfg.occupation)!;
    for (const k in occ.skills) p.skills.bonus[k as SkillId] = occ.skills[k as SkillId]!;
    if (p.traits.has('athletic')) p.skills.bonus.fitness += 2; if (p.traits.has('unfit')) p.skills.bonus.fitness -= 2;
    if (p.traits.has('strong')) p.skills.bonus.strength += 2; if (p.traits.has('weak')) p.skills.bonus.strength -= 2;
    if (p.traits.has('handy')) { p.skills.bonus.carpentry += 1; p.skills.bonus.engineering += 1; }
    for (const k in p.skills.bonus) p.skills.bonus[k as SkillId] = Math.max(0, p.skills.bonus[k as SkillId]);
    this.factory.engineering = p.skills.level('engineering'); this.base.carpentry = p.skills.level('carpentry');
    // starting kit
    p.inv.add('water_bottle', 1); p.inv.add('chips', 1); p.inv.add('flashlight', 1); p.inv.add('battery', 2); p.inv.add('rag', 2);
    for (const [id, n] of occ.items) p.inv.add(id, n);
    const wear = (id: string) => { const s = makeStack(id); p.worn[item(id).cloth!.slot] = s; };
    wear('jeans'); wear('hoodie'); wear('sneakers');
    p.hotbar = [null, null, null, null, null, null, null, null];
    let hi = 0; for (const s of p.inv.stacks) if (item(s.id).weapon && hi < 8) p.hotbar[hi++] = s.uid;
    // seed the spawn cabin with a few essentials
    const cabin = this.world.town.buildings.find(b => Math.hypot(b.x - CABIN.x, b.z - CABIN.z) < 3);
    if (cabin) {
      const c = cabin.containers;
      const r = new RNG(5);
      for (const ct of c) { ct.generated = true; }
      const give = (id: string, n = 1) => { const ct = c[r.int(0, c.length - 1)]; if (ct) ct.inv.add(id, n); };
      give('bat'); give('canned_beans', 2); give('water_bottle', 1); give('saw'); give('hammer'); give('nails', 24); give('plank', 6); give('lighter'); give('apple', 2); give('bandage', 1); give('school_bag');
    }
    p.pos.set(SPAWN.x, this.world.groundY(SPAWN.x, SPAWN.z) + 0.1, SPAWN.z);
    p.yaw = Math.PI * 0.75;
    this.zombies.populate();
    this.animals.populate();
    this.survivors.populate();
    this.gameMin = 8 * 60 + 30;
    this.objective = 0;
    this.startPlay();
    this.ui.notify(`Day 1. The outbreak was three weeks ago. The radio went silent yesterday. You are ${p.name}.`, 'good');
    setTimeout(() => this.ui.notify('Tip: Tab inventory · E interact · B build · H health · M map · F flashlight · C crouch', ''), 2500);
  }

  continueGame() {
    if (!this.load()) { this.ui.notify('Save could not be loaded.', 'bad'); return; }
    this.startPlay();
    this.ui.notify(`Welcome back, ${this.player.name}. Day ${this.day()}.`, 'good');
  }

  private startPlay() {
    this.state = 'play';
    this.ui.hideMenu();
    this.ui.showHud(true);
    audio.init().then(() => audio.resume());
    this.input.lock();
    this.world.veg.forceRebuild(this.player.pos, this.world.veg.farDist);
    this.world.town.props.update(this.player.pos, true);
    this.checkObjectives();
  }

  pause(p: boolean) {
    this.paused = p;
    if (p) { this.input.unlock(); this.ui.showPause(true); audio.suspend(); }
    else { this.ui.showPause(false); this.input.lock(); audio.resume(); }
  }

  // ------------------------------------------------------------------ time
  day() { return Math.floor(this.gameMin / 1440) + 1; }
  hours() { return (this.gameMin % 1440) / 60; }
  clock() { const h = Math.floor(this.hours()), m = Math.floor(this.gameMin % 60); return `${String(h).padStart(2, '0')}:${String(m).padStart(2, '0')}`; }
  isNight() { const h = this.hours(); return h >= 20.5 || h < 5.5; }

  // ------------------------------------------------------------------ main loop
  private frame(t: number) {
    requestAnimationFrame(tt => this.frame(tt));
    let dt = Math.min(0.05, (t - this.last) / 1000 || 0.016); this.last = t;
    if (this.state === 'menu' || this.state === 'cc' || this.state === 'loading') { this.menuFrame(dt); return; }
    if (this.paused) { this.renderer.render(dt); this.input.endFrame(); return; }
    this.time += dt;
    const p = this.player;
    const minPerSec = 1440 / (this.settings.dayLen * 60);
    this.timeMul = p.sleeping ? 45 : 1;
    const dtGame = dt * minPerSec * this.timeMul;
    if (this.state === 'play') this.gameMin += dtGame;
    const uiOpen = this.ui.anyOpen();
    const hrs = this.hours();

    if (this.state === 'play') {
      if (this.driving) this.driveFrame(dt, uiOpen);
      else p.update(dt, this.input, this.world, this.gameMin, uiOpen);
      this.handleInput(dt, uiOpen);
      this.updateAction(dt);
    }
    // camera
    const eye = p.eye();
    this.camera.position.copy(eye).add(p.headBob.clone().applyAxisAngle(new THREE.Vector3(0, 1, 0), p.yaw));
    if (p.camShake > 0) this.camera.position.add(new THREE.Vector3((Math.random() - 0.5) * p.camShake * 0.1, (Math.random() - 0.5) * p.camShake * 0.1, 0));
    this.camera.rotation.set(p.pitch, p.yaw, 0, 'YXZ');
    if (p.sleeping) this.camera.rotation.set(0.3, p.yaw, 0.1, 'YXZ');
    else if (!this.driving && !this.thirdPerson) {
      // first-person feel: springs from the viewmodel rig (landing dips, recoil, flinches, strafe roll)
      if (p.landed > 2) this.vm.land(p.landed); if (p.jumped) this.vm.jump(); if (p.stepped && p.sprinting) this.vm.footstep();
      const fx = this.vm.camRot;
      this.camera.rotation.set(p.pitch + fx.x, p.yaw + fx.y, fx.z, 'YXZ');
      this.camera.position.add(this.vm.camOffset.clone().applyEuler(new THREE.Euler(0, p.yaw, 0)));
    }
    p.landed = 0; p.jumped = false; p.stepped = false;
    this.updateCameraMode(dt, eye);
    const targetFov = this.settings.fov * (this.vm.ads > 0.5 && this.heldWeapon()?.ammo === 'ammo_rifle' ? 0.45 : 1 - this.vm.ads * 0.18) * (p.sprinting ? 1.06 : 1);
    this.camera.fov = damp(this.camera.fov, targetFov, 10, dt); this.camera.updateProjectionMatrix();
    this.camera.updateMatrixWorld();

    // world systems
    const daylight = this.atmo.daylight;
    const zr = this.zombies.update(dt, dtGame, {
      playerPos: p.pos, playerEye: eye, playerNoise: p.noise, playerVisibility: p.visibility * (this.flashlight.intensity > 0 ? 1.4 : 1), playerAlive: this.state === 'play', playerInside: p.inside,
      onPlayerAttacked: z => this.playerAttacked(z), onStructureHit: (ref, dmg, z) => this.structureHit(ref, dmg, z), onKill: (z, by) => this.onKill(z, by),
      baseCenter: this.factory.hub ? new THREE.Vector3(this.factory.hub.cx, 0, this.factory.hub.cz) : null, baseHeat: this.factory.heat + this.combatHeat,
      daylight, hours: hrs, day: this.day(), luck: this.luck(),
    }, this.camera.position);
    this.loudT = Math.max(0, this.loudT - dt * 30);
    this.animals.update(dt, { pos: p.pos, noise: Math.max(p.noise, this.loudT, this.driving ? 30 : 0), crouch: p.crouch, alive: this.state === 'play' }, 1 - daylight, a => this.wolfBite(a));
    if (this.state === 'play') {
      const hub = this.factory.hub ? new THREE.Vector3(this.factory.hub.cx, 0, this.factory.hub.cz) : null;
      this.survivors.update(dt, dtGame, { player: p.pos, hub, particles: this.particles, notify: (m, k) => this.ui.notify(m, k), deposit: inv => this.world.dropItems((hub ?? p.pos).clone().add(new THREE.Vector3(4, 0, 0)), inv) });
      if (hrs >= 18 && this.day() > this.lastFeedDay) { this.lastFeedDay = this.day(); this.survivors.feed(() => this.takeBaseFood(), (m, k) => this.ui.notify(m, k)); }
    }
    this.factory.update(dt * Math.min(this.timeMul, 20), { daylight, overcast: this.weather.overcast, wind: this.weather.wind, playerPos: p.pos, zombies: this.zombies, particles: this.particles, hours: hrs, day: this.day(), onDrop: pos => this.supplyDrop(pos) });
    this.weather.seasonTemp = this.season().temp;
    this.base.update(dt, dtGame / 60, { raining: this.weather.rain, particles: this.particles, playerPos: p.pos, temp: this.weather.temperature(hrs) });
    this.combatHeat = Math.max(0, this.combatHeat - dt * 0.5);

    // survival
    if (this.state === 'play') {
      const nearFire = this.base.pieces.some(q => q.fire?.lit && Math.hypot(q.cx - p.pos.x, q.cz - p.pos.z) < 4);
      const ev = p.stats.update(dt, dtGame / 60, {
        moving: p.moving, sprinting: p.sprinting, exertion: p.sprinting ? 1 : this.swingTimer > 0 ? 0.6 : p.moving ? 0.2 : 0, ambientTemp: this.weather.temperature(hrs),
        insulation: p.insulation(), nearFire, indoors: p.inside || this.underRoof(), raining: this.weather.rain, rainProtect: p.rainProtect(), sleeping: p.sleeping,
        fitness: p.skills.level('fitness'), strength: p.skills.level('strength'), traits: p.traits, weightRatio: p.weightRatio(), zombiesNear: zr.nearCount, zombiesSeen: zr.seenCount,
        inLight: this.townLit() || nearFire, bloodyHands: p.bloody > 0,
      });
      for (const e of ev) {
        if (e === 'dead') this.die(p.stats.deathCause);
        else if (e === 'cured') this.ui.notify('🧪 The serum worked. The infection is gone.', 'good');
        else if (e.startsWith('healed:')) this.ui.notify(`A wound on your ${e.slice(7)} has healed.`, 'good');
      }
      // flashlight battery
      const fl = p.inv.stacks.find(s => s.id === 'flashlight');
      if (p.flashlightOn && fl) { fl.dur = Math.max(0, (fl.dur ?? 100) - dtGame * 0.05); if ((fl.dur ?? 0) <= 0) { p.flashlightOn = false; this.ui.notify('Flashlight batteries are dead. Replace them (use a Battery).', 'bad'); } }
      if (!fl) p.flashlightOn = false;
      // overwhelmed by the horde (Zomboid-style)
      if (this.attackers >= 4 && p.stats.stamina < p.stats.maxStamina * 0.5) { this.overwhelmT += dt; if (this.overwhelmT > 2.2) { p.stats.dead = true; this.die('Overwhelmed and dragged down by the horde.'); } }
      else this.overwhelmT = Math.max(0, this.overwhelmT - dt);
      this.attackers = 0;
      // sleeping
      if (p.sleeping) {
        if (p.stats.fatigue <= 2 || (hrs > 7 && hrs < 8 && p.stats.fatigue < 30) || zr.nearCount > 0 && this.zombies.nearest(p.pos, 6)) this.wake(zr.nearCount > 0 && !!this.zombies.nearest(p.pos, 6));
      }
      // explore map
      const ex = Math.floor((p.pos.x + 400) / 12.5), ez = Math.floor((p.pos.z + 400) / 12.5);
      for (let i = -2; i <= 2; i++) for (let j = -2; j <= 2; j++) { const x = ex + i, z = ez + j; if (x >= 0 && z >= 0 && x < 64 && z < 64) this.explored[z * 64 + x] = 1; }
      // autosave every 3 game hours
      if (this.gameMin - this.lastSave > 180) { this.lastSave = this.gameMin; this.save(true); }
      this.stats.daysSurvived = Math.floor(p.stats.hoursSurvived / 24);
      if (this.frameCount++ % 30 === 0) this.checkObjectives();
    }

    // environment
    this.weather.update(dt, dtGame, this.camera.position, this.time, daylight, p.inside);
    this.atmo.overcast = this.weather.overcast; this.atmo.cloudCover = this.weather.cover; this.atmo.fogBoost = this.weather.fog; this.atmo.lightning = this.weather.lightning;
    this.updateScenery(dt, p.inside || this.underRoof());
    this.atmo.indoorDim = damp(this.atmo.indoorDim, p.inside || this.underRoof() ? 0.62 : 1, 3, dt);
    this.atmo.update(hrs, dt, this.camera, p.pos, this.time);
    this.renderer.renderer.toneMappingExposure = this.atmo.exposure() * (p.traits.has('night_owl') ? 1.15 : 1);
    (this.world.terrain.material.userData.uniforms as any).uWet.value = this.weather.wetness * 0.8;
    (this.world.terrain.material.userData.uniforms as any).uSnow.value = this.weather.snowCover;
    windUniforms.uSnow.value = this.weather.snowCover;
    this.world.update(dt, this.camera.position, this.atmo.sunDir, this.time, this.weather.wind);
    for (let i = this.fallingTrees.length - 1; i >= 0; i--) if (!this.fallingTrees[i](dt)) this.fallingTrees.splice(i, 1);
    for (let i = this.thunderQ.length - 1; i >= 0; i--) { this.thunderQ[i].t -= dt; if (this.thunderQ[i].t <= 0) { audio.play('thunder', { volume: this.thunderQ[i].v }); this.thunderQ.splice(i, 1); } }
    // town lights
    const lit = this.townLit();
    for (const e of this.streetEmitters) { e.on = lit; e.intensity = lit ? 160 : 0; }
    this.poweredBuildings.clear();
    for (const pc of this.base.pieces) if (pc.gen?.on) { const b = this.world.town.buildingAt(pc.cx, pc.cz); if (b) this.poweredBuildings.add(b); }
    const dark = this.atmo.daylight < 0.6;
    for (const [b, list] of this.buildingEmitters) { const on = dark && (this.factory.townPowered || this.poweredBuildings.has(b)); for (const e of list) { e.on = on; e.intensity = on ? 30 : 0; } }
    // generators are loud
    this.genNoiseT -= dt;
    if (this.genNoiseT <= 0) { this.genNoiseT = 3; for (const pc of this.base.pieces) if (pc.gen?.on) this.zombies.noise(new THREE.Vector3(pc.cx, pc.y, pc.cz), 26); }
    // spoilage, every 10 game minutes
    this.spoilT += dtGame;
    if (this.spoilT >= 10) { this.ageFood(this.spoilT); this.spoilT = 0; }
    // flashlight
    const fwd = p.forward();
    if (p.flashlightOn) {
      this.flashlight.intensity = 420; this.vm.flash.intensity = Math.max(this.vm.flash.intensity, 0.8);
      this.vm.lensWorld(this.flashlight.position);
      if (this.vm.heldId !== 'flashlight') this.flashlight.position.copy(this.camera.position).add(new THREE.Vector3(0.25, -0.25, 0).applyQuaternion(this.camera.quaternion));
      this.flashlight.target.position.copy(this.camera.position).addScaledVector(fwd, 10);
    } else this.flashlight.intensity = 0;
    this.flashlight.target.updateMatrixWorld();
    this.particles.update(dt);
    this.lights.update(dt, this.camera.position);
    // build mode
    this.placer.update(this.camera, p.inv, p.skills.level('carpentry'), p.skills.level('engineering'), this.settings.creative);
    // viewmodel
    const st = p.equippedStack(); const w = st ? item(st.id).weapon : undefined;
    const kind = !st ? 'none' : w?.kind === 'firearm' ? 'gun' : w ? 'melee' : 'tool';
    this.vm.setItem(st && w ? w.model : st?.id === 'flashlight' ? 'flashlight' : null, kind === 'tool' && !w ? 'none' : kind as any, !!w?.twoHanded);
    const strafe = p.vel.x * Math.cos(p.yaw) - p.vel.z * Math.sin(p.yaw);
    this.vm.update(dt, this.camera, { strafe, speed: p.speed, sprint: p.sprinting, crouch: p.crouch, lookDX: this.input.mouseDX, lookDY: this.input.mouseDY, aim: this.input.mouse[2] && !this.buildMode && !uiOpen, sun: this.atmo.sun, hemi: this.atmo.hemi, env: this.scene.environment, envI: (this.scene as any).environmentIntensity ?? 1, sway: p.stats.aimSway(), time: this.time, fov: this.camera.fov });
    this.vm.root.visible = !this.driving && !this.thirdPerson;
    // audio
    audio.setListener(this.camera.position, fwd, new THREE.Vector3(0, 1, 0));
    audio.setAmbience({ wind: this.weather.wind * (p.inside ? 0.3 : 1), rain: this.weather.rain, night: 1 - daylight, indoor: p.inside, insideBase: !!this.factory.hub && p.pos.distanceTo(new THREE.Vector3(this.factory.hub.cx, p.pos.y, this.factory.hub.cz)) < 40 });
    const chase = clamp(zr.chasing / 4, 0, 1);
    this.music = damp(this.music, Math.max(chase, this.hordeWarning > 0 ? 0.6 : 0, zr.seenCount > 0 ? 0.45 : 0), 0.8, dt);
    audio.setMusicIntensity(this.music);
    const hp = p.stats.health();
    audio.setLowHealth(hp < 35 ? 1 - hp / 35 : 0);
    this.hordeWarning = Math.max(0, this.hordeWarning - dt);
    // post fx
    const g = this.renderer.grade.uniforms;
    g.uTime.value = this.time;
    g.uDamage.value = Math.max(0, g.uDamage.value - dt * 1.6);
    g.uLowHealth.value = hp < 40 ? 1 - hp / 40 : 0;
    g.uBlur.value = clamp((p.stats.pain - 50) / 80, 0, 0.6) + clamp((p.stats.fatigue - 80) / 40, 0, 0.5);
    g.uPanic.value = clamp((p.stats.panic - 40) / 100, 0, 0.5);
    g.uSick.value = clamp(Math.max(p.stats.sickness, p.stats.knoxInfected ? p.stats.knox * 100 : 0) / 100, 0, 1);
    g.uFade.value = damp(g.uFade.value, p.sleeping ? 0.92 : this.state === 'dead' ? 0.6 : 0, 2, dt);
    this.ui.update(dt);
    this.renderer.render(dt);
    this.input.endFrame();
  }
  private frameCount = 0;

  private menuFrame(dt: number) {
    this.time += dt; this.menuT += dt;
    if (!this.renderer?.composer) return;
    const a = this.menuT * 0.02;
    const cx = Math.cos(a) * 140, cz = Math.sin(a) * 140;
    this.camera.position.set(cx, this.world.groundY(cx, cz) + 26, cz);
    this.camera.lookAt(0, 6, 0);
    this.camera.updateMatrixWorld();
    const hrs = 17.6 + Math.sin(this.menuT * 0.01) * 0.3;
    this.weather.update(dt, 0, this.camera.position, this.time, this.atmo.daylight, false);
    this.atmo.overcast = 0.35; this.atmo.cloudCover = 0.6; this.atmo.fogBoost = 0.25;
    this.atmo.update(hrs, dt, this.camera, this.camera.position, this.time);
    this.renderer.renderer.toneMappingExposure = this.atmo.exposure();
    this.updateScenery(dt, false);
    this.world.update(dt, this.camera.position, this.atmo.sunDir, this.time, 0.4);
    this.zombies.update(dt, 0, { playerPos: new THREE.Vector3(9999, 0, 9999), playerEye: new THREE.Vector3(9999, 0, 9999), playerNoise: 0, playerVisibility: 0, playerAlive: false, playerInside: false, onPlayerAttacked: () => {}, onStructureHit: () => {}, onKill: () => {}, baseCenter: null, baseHeat: 0, daylight: 1, hours: hrs, day: 1, luck: 0 }, this.camera.position);
    this.lights.update(dt, this.camera.position);
    this.vm.root.visible = false;
    this.renderer.grade.uniforms.uTime.value = this.time;
    this.renderer.render(dt);
    this.vm.root.visible = true;
  }


  // ------------------------------------------------------------------ vehicles & camera
  private driveFrame(dt: number, uiOpen: boolean) {
    const v = this.driving!, p = this.player, I = this.input;
    const inp = { throttle: 0, steer: 0, brake: false };
    if (!uiOpen) {
      if (I.down('KeyW')) inp.throttle += 1; if (I.down('KeyS')) inp.throttle -= 1;
      if (I.down('KeyA')) inp.steer += 1; if (I.down('KeyD')) inp.steer -= 1;
      inp.brake = I.down('Space');
      p.yaw -= I.mouseDX * I.sensitivity; p.pitch = clamp(p.pitch - I.mouseDY * I.sensitivity, -1.2, 1.0);
    }
    const impact = this.vehicles.drive(v, dt, inp, this.zombies, this.particles, this.atmo.daylight < 0.5);
    if (impact > 12) { const dmg = (impact - 12) * 2.5; p.stats.addWound('head', dmg > 15 ? 'laceration' : 'scratch', dmg); p.camShake = 0.8; this.renderer.grade.uniforms.uDamage.value = 0.8; this.ui.notify('You slam into the wheel!', 'bad'); }
    p.pos.copy(v.pos).setY(v.pos.y + 0.4);
    p.speed = Math.abs(v.speed); p.moving = false; p.sprinting = false; p.noise = 0; p.inside = false;
    if (!this.vehicles.canStart(v) && Math.abs(v.speed) < 0.2 && (I.hit('KeyW'))) this.ui.notify(v.fuel <= 0 ? 'Out of fuel.' : v.condition <= 0 ? 'The engine is wrecked.' : 'It won\'t start — no keys.', 'bad');
  }
  private updateCameraMode(dt: number, eye: THREE.Vector3) {
    const p = this.player;
    this.avatar.visible = false;
    if (this.driving) {
      const v = this.driving; const f = this.vehicles.forward(v);
      if (this.carCam) {
        const yaw = v.yaw + Math.PI + (p.yaw - (v.yaw + Math.PI)) * 0; // chase behind the car, mouse orbit below
        const orbit = p.yaw - v.yaw - Math.PI;
        const dir = new THREE.Vector3(Math.sin(v.yaw + orbit * 0.6), 0, Math.cos(v.yaw + orbit * 0.6));
        void yaw;
        const target = v.pos.clone().setY(v.pos.y + 1.4);
        const want = target.clone().addScaledVector(dir, -(v.size[2] * 1.3 + 3)).setY(target.y + 2.2 - p.pitch * 3);
        const gy = this.world.groundY(want.x, want.z, want.y) + 0.6; if (want.y < gy) want.y = gy;
        if (this.camSmooth.lengthSq() === 0 || this.camSmooth.distanceTo(want) > 20) this.camSmooth.copy(want);
        this.camSmooth.lerp(want, 1 - Math.exp(-dt * 6));
        this.camera.position.copy(this.camSmooth);
        this.camera.lookAt(target.addScaledVector(f, 3));
      } else {
        const r = new THREE.Vector3(f.z, 0, -f.x);
        this.camera.position.copy(v.pos).addScaledVector(r, -0.38).addScaledVector(f, 0.1).setY(v.pos.y + 1.25);
        this.camera.rotation.set(p.pitch, p.yaw, 0, 'YXZ');
      }
      return;
    }
    if (this.thirdPerson && !p.sleeping) {
      const fwd = p.forward(); const right = new THREE.Vector3(Math.cos(p.yaw), 0, -Math.sin(p.yaw));
      const pivot = eye.clone().addScaledVector(right, 0.55).setY(eye.y + 0.15);
      const back = fwd.clone().multiplyScalar(-1);
      const hit = this.world.col.raycast(pivot, back, 3.4);
      const dist = Math.min(3.2, hit.t - 0.25);
      this.camera.position.copy(pivot).addScaledVector(back, Math.max(0.4, dist));
      const a = this.avatar;
      a.visible = true; a.pos.copy(p.pos); a.yaw = p.yaw + Math.PI;
      a.move = p.speed < 0.2 ? 0 : p.speed < 4 ? 1 : 1 + Math.min(1, (p.speed - 4) / 2);
      a.phase += dt * (p.speed * 3.2 + 0.1);
      a.arms = this.heldWeapon()?.kind === 'firearm' && this.input.mouse[2] ? 1 : 0;
      a.atk = Math.max(0, a.atk - dt * 3);
      a.lookPitch = clamp(p.pitch, -0.8, 0.8); a.lookYaw = 0;
      a.hit = this.renderer.grade.uniforms.uDamage.value;
    }
  }
  enterVehicle(target: TownCar | Vehicle) {
    const p = this.player;
    let v: Vehicle;
    if (target.kind === 'towncar') {
      if (target.variant === 'wreck') { this.ui.notify('A burnt-out wreck. Nothing left but scrap.', ''); return; }
      v = this.vehicles.fromTown(target);
    } else v = target;
    if (!v.keys && !v.hotwired && p.inv.count('car_keys') > 0 && this.rng.chance(0.4)) { p.inv.remove('car_keys', 1); v.keys = true; this.ui.notify('🔑 One of your keys fits!', 'good'); }
    this.vehicles.unpark(v);
    this.driving = v;
    p.equipped = null; p.flashlightOn = false; p.crouch = false;
    p.yaw = v.yaw + Math.PI; p.pitch = -0.1;
    audio.play('door_close', { pos: v.pos });
    if (this.vehicles.canStart(v)) { audio.startLoop('engine', 'generator', { pos: v.pos, volume: 0.4 }); this.ui.notify(`Engine running · fuel ${Math.round(v.fuel * 100)}% · condition ${Math.round(v.condition)}%. W/S drive · A/D steer · Space brake · V camera · E exit`, 'good'); }
    else this.ui.notify(v.fuel <= 0 ? 'No fuel. Bring a gas can (use it next to the car).' : !v.keys && !v.hotwired ? 'No keys. Hotwire it (T from outside) with a screwdriver and Engineering 1, or find keys.' : 'The engine is too damaged to run.', 'bad');
  }
  exitVehicle() {
    const v = this.driving; if (!v) return;
    if (Math.abs(v.speed) > 4) { this.ui.notify('Slow down first!', ''); return; }
    const p = this.player;
    v.speed = 0;
    const pt = this.vehicles.exitPoint(v);
    p.pos.copy(pt); p.vel.set(0, 0, 0); p.fallVel = 0;
    this.vehicles.park(v); this.vehicles.headlight.intensity = 0;
    audio.stopLoop('engine'); audio.play('door_open', { pos: v.pos });
    this.driving = null; this.camSmooth.set(0, 0, 0);
  }
  private hotwire(target: TownCar | Vehicle) {
    const p = this.player;
    if (!p.inv.hasTool('screwdriver') || p.skills.level('engineering') < 1) { this.ui.notify('Hotwiring needs a screwdriver and Engineering 1.', 'bad'); return; }
    const v = target.kind === 'towncar' ? (target.variant === 'wreck' ? null : this.vehicles.fromTown(target)) : target;
    if (!v) return;
    this.startAction({ label: 'Hotwiring…', dur: 6 * (1 - p.skills.level('engineering') * 0.06), cancelOnMove: true, done: () => {
      if (this.rng.chance(0.75 + p.skills.level('engineering') * 0.05)) { v.hotwired = true; this.ui.notify('The engine coughs to life. Hotwired!', 'good'); p.gainXP('engineering', 10, this.gameMin); }
      else { this.ui.notify('Sparks — it didn\'t take. Try again.', 'bad'); this.particles.sparks(v.pos.clone().setY(v.pos.y + 1), 8); }
      this.zombies.noise(p.pos, 10);
    } });
  }
  private carAlt(target: TownCar | Vehicle): Interact['alt'] {
    const p = this.player;
    const fuel = target.fuel;
    if (p.inv.count('gas_can') && fuel < 0.98 && target.variant !== 'wreck') return { label: 'Refuel with gas can', act: () => this.startAction({ label: 'Refuelling…', dur: 3, cancelOnMove: true, done: () => { if (!p.inv.remove('gas_can', 1)) return; target.fuel = Math.min(1, target.fuel + 0.5); p.inv.add('gas_can_empty'); audio.play('splash', { volume: 0.5 }); this.ui.notify(`Fuel ${Math.round(target.fuel * 100)}%`, 'good'); } }) };
    if (p.inv.count('gas_can_empty') && fuel > 0.1) return { label: 'Siphon fuel into gas can', act: () => this.startAction({ label: 'Siphoning… (it tastes awful)', dur: 5, cancelOnMove: true, done: () => { if (!p.inv.remove('gas_can_empty', 1)) return; const take = Math.min(0.5, target.fuel); target.fuel -= take; p.inv.add(take >= 0.45 ? 'gas_can' : 'gas_can_empty'); audio.play('splash', { volume: 0.5 }); if (take < 0.45) this.ui.notify('Not enough fuel left to fill the can.', ''); else this.ui.notify('Filled a gas can.', 'good'); } }) };
    if (!target.keys && !(target as Vehicle).hotwired && target.variant !== 'wreck') return { label: 'Hotwire', act: () => this.hotwire(target) };
    return undefined;
  }

  private npcInteract(s: Survivor, t: number): Interact {
    const p = this.player;
    const roleTxt = s.role === 'shooter' ? `shooter · ${s.ammo} rounds` : s.role === 'brawler' ? 'brawler' : 'scavenger';
    if (!s.recruited) return { kind: 'npc', label: `Talk to ${s.name}`, sub: `Survivor (${roleTxt}) · ${p.inv.stacks.some(x => item(x.id).cat === 'food') ? 'offer food to recruit' : 'they want food'}`, target: s, t };
    const next: Record<string, string> = { follow: 'Guard the Outpost', guard: 'Go scavenging (6 h)', scavenge: 'Wait here', wait: 'Follow me' };
    const alt = s.role === 'shooter' && p.inv.count('ammo_9mm') ? { label: 'Give 9mm ammo', act: () => { const k = Math.min(30, p.inv.count('ammo_9mm')); p.inv.remove('ammo_9mm', k); s.ammo += k; this.ui.notify(`${s.name} now has ${s.ammo} rounds.`, ''); } } : undefined;
    return { kind: 'npc', label: `${s.name}: ${next[s.order]}`, sub: `${roleTxt} · ${s.order} · health ${Math.round(s.hp)}%`, target: s, t, alt };
  }
  private talkTo(s: Survivor) {
    const p = this.player;
    if (!s.recruited) {
      const food = p.inv.stacks.filter(x => item(x.id).cat === 'food' && x.id !== 'rotten_food').sort((a, b) => (item(a.id).hunger ?? 0) - (item(b.id).hunger ?? 0))[0];
      if (!food) { this.ui.notify(`${s.name}: "${this.survivors.line('recruit')}"`, ''); return; }
      p.inv.removeUid(food.uid, 1);
      s.recruited = true; s.order = 'follow';
      this.ui.notify(`${s.name} takes the ${item(food.id).name}. "${this.survivors.line('joined')}" (E to give orders)`, 'good');
      audio.play('objective');
      return;
    }
    const cycle: Record<string, Survivor['order']> = { follow: 'guard', guard: 'scavenge', scavenge: 'wait', wait: 'follow' };
    let o = cycle[s.order];
    if (o === 'guard' && !this.factory.hub) o = 'scavenge';
    if (o === 'scavenge' && !this.factory.hub) o = 'wait';
    s.order = o;
    if (o === 'scavenge') { s.away = 360; this.ui.notify(`${s.name} heads out to scavenge. Back in ~6 hours.`, ''); }
    else if (o === 'wait') { s.anchor.copy(s.pos); this.ui.notify(`${s.name} will wait here.`, ''); }
    else this.ui.notify(`${s.name}: ${o === 'follow' ? '"Right behind you."' : '"I\'ll keep watch at the Outpost."'}`, '');
  }
  /** take one food item from storage near the Outpost (crates, storage containers) */
  private takeBaseFood(): boolean {
    const hub = this.factory.hub; if (!hub) return false;
    const invs: Inventory[] = [];
    for (const pc of this.base.pieces) if (pc.container && Math.hypot(pc.cx - hub.cx, pc.cz - hub.cz) < 40) invs.push(pc.container.inv);
    for (const m of this.factory.machines) if (m.type === 'storage' && m.inv && Math.hypot(m.cx - hub.cx, m.cz - hub.cz) < 40) invs.push(m.inv);
    for (const inv of invs) { const s = inv.stacks.find(x => item(x.id).cat === 'food' && x.id !== 'rotten_food'); if (s) { inv.removeUid(s.uid, 1); return true; } }
    return false;
  }

  private wolfBite(a: Animal) {
    const p = this.player;
    if (this.driving) return;
    if (!this.rng.chance(p.sprinting ? 0.45 : 0.7)) return;
    const part = this.rng.weighted<PartId>([['legL', 30], ['legR', 30], ['armL', 15], ['armR', 15], ['torso', 10]]);
    const prot = p.protection(part.startsWith('leg') ? 'legs' : 'torso');
    audio.play('player_hurt'); this.renderer.grade.uniforms.uDamage.value = 0.7; p.camShake = 0.4; this.vm.hurt();
    if (this.rng.chance(prot.bite)) { this.ui.notify('The wolf\'s teeth catch on your clothes.', ''); return; }
    p.stats.addWound(part, this.rng.chance(0.6) ? 'laceration' : 'scratch', this.rng.range(8, 15));
    this.ui.notify(`🐺 A wolf mauls your ${PARTS.find(q => q.id === part)!.name.toLowerCase()}!`, 'bad');
    this.cancelAction();
  }

  private updateScenery(dt: number, indoor: boolean) {
    const a = this.atmo; const cam = this.camera.position;
    const golden = smoothstep(0.35, 0.02, a.sunDir.y) * a.daylight;
    this.mountains.update(cam, a.fogColor, a.sunDir, a.night, this.weather.snowCover);
    const sunCol = a.sun.color.clone();
    this.cloudClusters.update(cam, this.time, this.weather.cover, sunCol, a.daylight, a.night, this.weather.windDir.clone().multiplyScalar(0.5 + this.weather.wind));
    const season = this.state === 'play' ? this.season().idx : 0;
    this.ambient.update(this.time, cam, { season, night: a.night, daylight: a.daylight, rain: this.weather.rain, indoor, wind: this.weather.windDir.clone().multiplyScalar(this.weather.wind), forest: this.world.terrain.forestDensity(cam.x, cam.z) });
    windUniforms.uFlowers.value = [0.3, 0, 1, 0.75][season];
    // cinematic grade drifts with the time of day: warm golden hours, teal-blue nights
    const g = this.renderer.grade.uniforms;
    g.uGain.value.set(1.03 + golden * 0.07, 1.0 + golden * 0.01, 0.95 - golden * 0.07 + a.night * 0.05);
    g.uLift.value.set(0.018 - a.night * 0.008, 0.022 + a.night * 0.006, 0.034 + a.night * 0.02);
    g.uSat.value = 0.97 + golden * 0.08 - a.night * 0.25;
    void dt;
  }

  // ------------------------------------------------------------------ seasons & food
  static SEASON_DAYS = 7;
  season() {
    const names = ['Autumn', 'Winter', 'Spring', 'Summer'], temps = [0, -13, 3, 10];
    const d = (this.gameMin / 1440) / Game.SEASON_DAYS;
    const i = Math.floor(d) % 4, f = d - Math.floor(d);
    const prev = temps[(i + 3) % 4];
    return { name: names[i], idx: i, temp: lerp(prev, temps[i], Math.min(1, f * Game.SEASON_DAYS)) };
  }
  private ageFood(minutes: number) {
    const days = minutes / 1440;
    const outdoorCold = this.weather.temperature(this.hours()) < 4 ? 0.5 : 1;
    const age = (inv: Inventory, mul: number) => {
      for (let i = inv.stacks.length - 1; i >= 0; i--) {
        const s = inv.stacks[i]; const d = item(s.id);
        if (!d.spoilDays) continue;
        s.age = (s.age ?? 0) + days * mul;
        if (s.age > d.spoilDays * 1.6) { const n = s.n; inv.stacks.splice(i, 1); inv.add('rotten_food', n); }
      }
    };
    age(this.player.inv, outdoorCold);
    for (const c of this.world.town.containers) {
      if (!c.generated || !c.inv.stacks.length) continue;
      const cold = c.kind === 'fridge' && (this.factory.townPowered || (c.building && this.poweredBuildings.has(c.building)));
      age(c.inv, cold ? 0.14 : outdoorCold);
    }
    for (const pc of this.base.pieces) if (pc.container) age(pc.container.inv, outdoorCold);
    for (const d of this.world.drops) age(d.inv, outdoorCold);
    for (const v of this.vehicles.list) age(v.container.inv, outdoorCold);
    for (const m of this.factory.machines) if (m.inv) age(m.inv, m.type === 'storage' ? outdoorCold : 1);
  }

  // ------------------------------------------------------------------ helpers
  luck() { return this.player.skills.level('scavenging') * 0.07 + (this.player.traits.has('lucky') ? 0.3 : 0); }
  townLit() { return this.factory.townPowered && this.atmo.daylight < 0.6; }
  underRoof() { const p = this.player.pos; return this.base.pieces.some(q => q.def.kind === 'roof' && Math.abs(q.cx - p.x) < 1 && Math.abs(q.cz - p.z) < 1 && q.y > p.y); }
  heldWeapon() { const s = this.player.equippedStack(); return s ? item(s.id).weapon : undefined; }
  now() { return this.gameMin; }

  // ------------------------------------------------------------------ input
  private handleInput(dt: number, uiOpen: boolean) {
    const I = this.input, p = this.player;
    if (I.hit('Escape')) {
      if (this.buildMode) this.toggleBuild(false);
      else if (uiOpen) this.ui.closeAll();
      else this.pause(true);
      return;
    }
    if (p.sleeping) { if (I.hit('KeyE') || I.hit('Space')) this.wake(false); return; }
    if (I.hit('KeyV') && !uiOpen) { if (this.driving) this.carCam = !this.carCam; else this.thirdPerson = !this.thirdPerson; audio.play('ui_click'); }
    if (I.hit('Tab') || I.hit('KeyI')) this.ui.toggle('inventory');
    if (I.hit('KeyH')) this.ui.toggle('health');
    if (I.hit('KeyK')) this.ui.toggle('skills');
    if (I.hit('KeyJ')) this.ui.toggle('journal');
    if (I.hit('KeyM')) this.ui.toggle('map');
    if (I.hit('KeyB')) { if (uiOpen && this.ui.open === 'build') this.ui.closeAll(); else { this.ui.closeAll(); this.ui.toggle('build'); } }
    if (I.hit('F5')) this.save(false);
    if (uiOpen) return;
    if (this.driving) { if (I.hit('KeyE')) this.exitVehicle(); this.interact = null; return; }
    // hotbar
    for (let i = 0; i < 8; i++) if (I.hit('Digit' + (i + 1))) this.selectHotbar(i);
    if (I.wheel && !this.buildMode) { const cur = p.hotbar.indexOf(p.equipped); let n = cur; for (let k = 0; k < 8; k++) { n = (n + (I.wheel > 0 ? 1 : 7)) % 8; if (p.hotbar[n] !== null && p.inv.byUid(p.hotbar[n]!)) break; } if (p.hotbar[n] !== null) this.selectHotbar(n); }
    if (I.hit('KeyF')) { const fl = p.inv.find('flashlight'); if (fl && (fl.dur ?? 0) > 0) { p.flashlightOn = !p.flashlightOn; audio.play('ui_click'); } else if (fl) this.ui.notify('Flashlight is dead — use a Battery.', 'bad'); }
    if (I.hit('KeyQ')) this.quickHeal();
    if (I.hit('KeyR') && !this.buildMode) this.startReload();
    if (I.hit('KeyR') && this.buildMode) { this.placer.rot = (this.placer.rot + 1) % 4; audio.play('ui_click'); }
    if (I.hit('KeyX') && this.buildMode) { this.placer.dismantle = !this.placer.dismantle; audio.play('ui_click'); }
    if (I.hit('KeyG')) { const st = p.equippedStack(); if (st) this.dropStack(st.uid, st.n); }
    // interact
    this.interact = this.findInteract();
    if (I.hit('KeyE') && this.interact) this.doInteract(this.interact);
    if (I.hit('KeyT') && this.interact?.alt) this.interact.alt.act();
    if (this.buildMode) {
      if (I.mousePressed[0]) this.buildClick();
      if (I.mouseReleased[0]) this.buildRelease();
      if (I.mousePressed[2]) this.toggleBuild(false);
      return;
    }
    // combat
    this.fireCooldown -= dt; this.shoveCD -= dt;
    if (this.reloading > 0) { this.reloading -= dt; if (this.reloading <= 0) this.finishReload(); }
    const st = p.equippedStack(); const w = st ? item(st.id).weapon : undefined;
    if (this.swingTimer > 0) {
      this.swingTimer -= dt;
      if (this.swingHitAt > 0) { this.swingHitAt -= dt; if (this.swingHitAt <= 0) this.meleeHit(); }
    }
    if (I.mouse[0] && !this.action) {
      if (w?.kind === 'firearm') { if (this.fireCooldown <= 0 && this.reloading <= 0 && (w.auto || I.mousePressed[0])) this.fire(st!); }
      else if (this.swingTimer <= 0 && (I.mousePressed[0] || true)) this.startSwing(st);
    }
    if (I.mousePressed[2] && w?.kind !== 'firearm' && this.shoveCD <= 0) this.shove();
  }

  selectHotbar(i: number) {
    const p = this.player; const uid = p.hotbar[i];
    if (uid === null || !p.inv.byUid(uid)) { p.hotbar[i] = null; return; }
    const s = p.inv.byUid(uid)!;
    const d = item(s.id);
    if (d.weapon || d.id === 'flashlight') { p.equipped = p.equipped === uid ? null : uid; audio.play('zipper', { volume: 0.4 }); this.cancelReload(); }
    else this.useItem(uid);
  }

  // ------------------------------------------------------------------ combat
  private startSwing(st: Stack | null) {
    const p = this.player; const w = st ? item(st.id).weapon : undefined;
    const cost = w?.stamina ?? 2;
    if (p.stats.stamina < cost * 0.5) { audio.play('breath', { volume: 0.5 }); return; }
    const skill = w ? p.skills.level(w.kind === 'blade' ? 'blade' : 'blunt') : 0;
    const speed = (w?.speed ?? 2.2) * (1 + skill * 0.04) * (p.stats.stamina < 10 ? 0.6 : 1);
    const dur = 1 / speed;
    this.swingTimer = dur; this.swingHitAt = dur * 0.42; this.swingStack = st;
    p.stats.stamina -= cost;
    this.vm.swing(dur); this.avatar.atk = 1;
    audio.play(w && w.speed < 0.9 ? 'swing_heavy' : 'swing', { volume: 0.7 });
  }
  private meleeHit() {
    const p = this.player; const st = this.swingStack; const w = st ? item(st.id).weapon : undefined;
    const eye = p.eye(), fwd = p.forward();
    const flat = new THREE.Vector3(fwd.x, 0, fwd.z).normalize();
    const range = w?.range ?? 1.4;
    const targets = this.zombies.inArc(eye, flat, range, 0.55);
    const skillId: SkillId = w?.kind === 'blade' ? 'blade' : 'blunt';
    const skill = w ? p.skills.level(skillId) : 0;
    if (targets.length) {
      const maxHits = w && (w.model === 'machete' || w.model === 'sledgehammer' || w.model === 'bat' || w.model === 'bat_nails') ? 2 : 1;
      for (let i = 0; i < Math.min(maxHits, targets.length); i++) {
        const z = targets[i];
        const ray = this.zombies.raycast(eye, fwd, range + 0.6);
        const head = !!ray && ray.z === z && ray.head || this.rng.chance(0.08 + skill * 0.015);
        let dmg = (w?.damage ?? 9) * (1 + skill * 0.07) * (1 + p.skills.level('strength') * 0.04) * p.stats.meleeMult() * this.rng.range(0.85, 1.15) * (i > 0 ? 0.5 : 1);
        if (w?.kind === 'blade' && head) dmg *= 1.3;
        const dir = new THREE.Vector3(z.pos.x - p.pos.x, 0, z.pos.z - p.pos.z).normalize();
        const killed = this.zombies.damage(z, dmg, dir, head, (w?.knock ?? 0.5) * 1.5);
        const at = new THREE.Vector3(z.pos.x, z.pos.y + (head ? 1.6 : 1.2) * z.scale, z.pos.z);
        this.particles.blood(at, dir.clone().setY(0.3), head ? 1.5 : 1);
        if (this.rng.chance(0.4)) this.particles.bloodDecal(z.pos.x + dir.x * 0.5, this.world.groundY(z.pos.x, z.pos.z, z.pos.y), z.pos.z + dir.z * 0.5, 0.9);
        audio.play(head ? 'headshot' : w?.kind === 'blade' ? 'hit_flesh' : 'hit_flesh', { pos: at });
        if (head && killed) audio.play('bone_crack', { pos: at, volume: 0.7 });
        this.ui.hitmark(killed); this.vm.impact(head ? 1.3 : 1);
        p.gainXP(skillId, killed ? 8 : 3, this.gameMin); p.gainXP('strength', 1, this.gameMin);
        p.bloody = Math.min(1, p.bloody + 0.1);
      }
      this.zombies.noise(p.pos, w?.noise ?? 4);
      if (st && w) this.wear(st, 1);
      p.camShake = Math.max(p.camShake, 0.15);
      return;
    }
    const beasts = this.animals.inArc(eye, flat, range);
    if (beasts.length) {
      const a = beasts[0];
      const dmg = (w?.damage ?? 9) * (1 + skill * 0.07) * p.stats.meleeMult() * this.rng.range(0.85, 1.15);
      const killed = this.animals.damage(a, dmg, p.pos);
      this.particles.blood(a.pos.clone().setY(a.pos.y + 0.6), flat, 0.8); audio.play('hit_flesh', { pos: a.pos });
      this.ui.hitmark(killed); if (st && w) this.wear(st, 1);
      return;
    }
    // environment targets
    const hit = this.world.col.raycast(eye, fwd, range + 0.3);
    if (hit.obj) {
      const ref = (hit.obj as any).ref;
      const at = eye.clone().addScaledVector(fwd, hit.t);
      if (ref && 'kind' in ref && (ref as TreeInst).kind && 'alive' in ref) { this.hitVegetation(ref as TreeInst, w, at); if (st && w) this.wear(st, 1); return; }
      if (ref && (ref as OreNode).type && 'purity' in ref) { this.hitOre(ref as OreNode, w, at); if (st && w) this.wear(st, 1); return; }
      // generic surface hit
      audio.play(hit.obj && (hit.obj as any).tag === 'car' ? 'hit_metal' : 'hit_wood', { pos: at, volume: 0.7 });
      this.particles.dust(at, 4);
      if (ref && 'hp' in ref && 'pivot' in ref && p.equippedStack() && item(p.equippedStack()!.id).tools?.includes('demolish')) this.structureHit(ref, 60, null);
      this.zombies.noise(p.pos, 5);
    } else {
      // bushes have no collider
      const b = this.world.veg.nearest(eye.x + fwd.x * 1.2, eye.z + fwd.z * 1.2, 1.4, ['bush']);
      if (b) this.hitVegetation(b, w, new THREE.Vector3(b.x, b.y + 0.6, b.z));
    }
  }
  private hitVegetation(t: TreeInst, w: any, at: THREE.Vector3) {
    const p = this.player;
    if (t.kind === 'tree') {
      if (!w?.tree) { audio.play('hit_wood', { pos: at }); this.ui.notify('You need an axe to fell trees.', ''); return; }
      t.hp -= 20 * (1 + p.skills.level('strength') * 0.05);
      audio.play('chop', { pos: at }); this.particles.dust(at, 6, [0.5, 0.4, 0.28]);
      this.zombies.noise(p.pos, 14);
      if (t.hp <= 0) {
        const dir = new THREE.Vector3(t.x - p.pos.x, 0, t.z - p.pos.z).normalize();
        this.fallingTrees.push(this.world.veg.fallingTree(t, dir.x, dir.z, this.scene));
        this.world.veg.kill(t, p.pos);
        audio.play('wood_break', { pos: at });
        const logs = 2 + Math.floor(t.s * 2);
        const inv = new Inventory(); inv.add('log', logs); inv.add('leaves', 6 + this.rng.int(0, 6));
        setTimeout(() => this.world.dropItems(new THREE.Vector3(t.x + dir.x * 4, 0, t.z + dir.z * 4), inv), 2500);
        p.gainXP('strength', 4, this.gameMin);
        this.ui.notify(`Timber! ${logs} logs dropped where it fell.`, '');
      }
    } else if (t.kind === 'bush') {
      audio.play('search', { pos: at }); this.particles.dust(at, 6, [0.25, 0.35, 0.15]);
      t.hp -= 10;
      if (t.hp <= 0) { this.world.veg.kill(t, p.pos); const inv = new Inventory(); inv.add('leaves', 4 + this.rng.int(0, 4)); if (this.rng.chance(0.25)) inv.add('apple', 1); if (this.rng.chance(0.1)) inv.add('seeds_carrot', 2); this.giveOrDrop(inv); }
    } else if (t.kind === 'rock') {
      if (!w?.mine) { audio.play('hit_metal', { pos: at, volume: 0.6 }); return; }
      audio.play('mine_hit', { pos: at }); this.particles.dust(at, 8, [0.5, 0.5, 0.48]); this.particles.sparks(at, 4);
      this.giveOrDrop(this.invOf('stone', this.rng.int(1, 2)));
    } else if (t.kind === 'log') {
      if (!w?.tree) return;
      audio.play('chop', { pos: at }); t.hp -= 25;
      if (t.hp <= 0) { this.world.veg.kill(t, p.pos); this.giveOrDrop(this.invOf('log', 2)); }
    }
  }
  private hitOre(o: OreNode, w: any, at: THREE.Vector3) {
    const p = this.player;
    if (o.type === 'f_crude') { this.ui.notify('Oil seeps from the ground here. You need an Oil Pumpjack (milestone 5).', ''); return; }
    if (!w?.mine) { audio.play('hit_metal', { pos: at, volume: 0.6 }); this.ui.notify('You need a pickaxe to mine ore.', ''); return; }
    audio.play('mine_hit', { pos: at }); this.particles.sparks(at, 8); this.particles.dust(at, 6, [0.4, 0.38, 0.35]);
    this.zombies.noise(p.pos, 12);
    const n = 1 + (this.rng.chance(0.3 + p.skills.level('strength') * 0.03) ? 1 : 0);
    this.giveOrDrop(this.invOf(o.type, n));
    p.gainXP('strength', 1.5, this.gameMin);
  }
  private invOf(id: string, n: number) { const i = new Inventory(); i.add(id, n); return i; }
  giveOrDrop(inv: Inventory) {
    const p = this.player;
    for (const s of inv.stacks) { p.inv.addStack(s); this.ui.notify(`+${s.n} ${item(s.id).name}`, ''); }
    audio.play('pickup', { volume: 0.5 });
    this.ui.refresh();
  }

  private fire(st: Stack) {
    const p = this.player; const w = item(st.id).weapon!;
    if ((st.ammo ?? 0) <= 0) { audio.play('empty_click'); this.fireCooldown = 0.3; if (p.inv.count(w.ammo!) > 0) this.startReload(); return; }
    st.ammo!--;
    this.fireCooldown = 1 / w.speed;
    const eye = p.eye(); const fwd = p.forward();
    const skill = p.skills.level('firearms');
    const moving = p.speed > 1 ? 1.8 : 1;
    const ads = this.vm.ads > 0.5 ? 0.35 : 1;
    const spread = (w.spread ?? 0.02) * (1 - skill * 0.06) * moving * ads + p.stats.aimSway() * 0.6 * ads;
    let hitAny = false, killedAny = false;
    for (let i = 0; i < (w.pellets ?? 1); i++) {
      const d = fwd.clone().add(new THREE.Vector3((Math.random() - 0.5) * 2 * spread, (Math.random() - 0.5) * 2 * spread, (Math.random() - 0.5) * 2 * spread)).normalize();
      const wh = this.world.col.raycast(eye, d, w.range);
      const zh = this.zombies.raycast(eye, d, Math.min(w.range, wh.t));
      const ah = this.animals.raycast(eye, d, Math.min(w.range, wh.t, zh ? zh.t : Infinity));
      if (ah) {
        const killed = this.animals.damage(ah.a, w.damage * (w.pellets ? clamp(1.2 - ah.t / w.range, 0.3, 1) : 1), eye);
        this.particles.blood(eye.clone().addScaledVector(d, ah.t), d, 0.7); hitAny = true; killedAny = killedAny || killed;
        if (killed) p.gainXP('firearms', 4, this.gameMin);
      } else if (zh) {
        const falloff = w.pellets ? clamp(1.2 - zh.t / w.range, 0.3, 1) : 1;
        const dmg = w.damage * falloff * (1 + skill * 0.05) * this.rng.range(0.9, 1.1);
        const killed = this.zombies.damage(zh.z, dmg, d, zh.head, w.knock);
        const at = eye.clone().addScaledVector(d, zh.t);
        this.particles.blood(at, d, zh.head ? 1.6 : 0.8);
        if (this.rng.chance(0.5)) this.particles.bloodDecal(at.x + d.x, this.world.groundY(at.x, at.z, at.y), at.z + d.z, 1);
        if (zh.head && killed) audio.play('headshot', { pos: at, volume: 0.8 });
        hitAny = true; killedAny = killedAny || killed;
        if (killed) p.gainXP('firearms', 6, this.gameMin);
      } else if (wh.t < w.range) {
        const at = eye.clone().addScaledVector(d, wh.t - 0.02);
        this.particles.sparks(at, 5); this.particles.dust(at, 3);
      } else {
        // terrain impact
        for (let t = 1; t < Math.min(60, w.range); t += 1) { const q = eye.clone().addScaledVector(d, t); if (q.y < this.world.groundY(q.x, q.z, q.y)) { this.particles.dust(q, 5); break; } }
      }
    }
    if (hitAny) this.ui.hitmark(killedAny);
    p.gainXP('firearms', 1, this.gameMin);
    this.vm.recoil(w.recoil! * 8); this.vm.muzzleFlash();
    p.recoilPitch += w.recoil! * (1 - skill * 0.05) * (this.vm.ads > 0.5 ? 0.6 : 1);
    p.yaw += (Math.random() - 0.5) * w.recoil! * 0.5;
    p.camShake = Math.max(p.camShake, w.recoil! * 3);
    const mz = this.vm.muzzle.clone();
    this.particles.muzzle(mz, fwd);
    this.lights.flash(mz.clone().addScaledVector(fwd, 0.3), 0xffb050, 160, 16, 0.06);
    audio.play(w.ammo === 'ammo_shells' ? 'gunshot_shotgun' : w.ammo === 'ammo_rifle' ? 'gunshot_rifle' : 'gunshot_pistol');
    this.zombies.noise(p.pos, w.noise * (1 - p.skills.level('firearms') * 0.0));
    this.loudT = Math.max(this.loudT, w.noise);
    this.combatHeat += 1.5;
    this.wear(st, 1);
  }
  private startReload() {
    const p = this.player; const st = p.equippedStack(); if (!st) return; const w = item(st.id).weapon;
    if (!w || w.kind !== 'firearm' || this.reloading > 0) return;
    if ((st.ammo ?? 0) >= w.mag! || p.inv.count(w.ammo!) <= 0) { if (p.inv.count(w.ammo!) <= 0) this.ui.notify(`No ${item(w.ammo!).name}.`, 'bad'); return; }
    const t = w.reload! * (1 - p.skills.level('firearms') * 0.05);
    this.reloading = t; this.reloadStack = st; this.vm.reload(t);
    audio.play('reload');
  }
  private finishReload() {
    const p = this.player; const st = this.reloadStack; this.reloadStack = null; if (!st || !p.inv.byUid(st.uid)) return;
    const w = item(st.id).weapon!;
    const need = w.mag! - (st.ammo ?? 0); const k = Math.min(need, p.inv.count(w.ammo!));
    p.inv.remove(w.ammo!, k); st.ammo = (st.ammo ?? 0) + k;
    p.gainXP('firearms', 0.5, this.gameMin);
    this.ui.refresh();
  }
  private cancelReload() { this.reloading = 0; this.reloadStack = null; }
  private shove() {
    const p = this.player;
    if (p.stats.stamina < 4) return;
    this.shoveCD = 0.6; p.stats.stamina -= 4; this.vm.push();
    const eye = p.eye(); const fwd = p.forward(); const flat = new THREE.Vector3(fwd.x, 0, fwd.z).normalize();
    for (const z of this.zombies.inArc(eye, flat, 1.7, 0.4).slice(0, 3)) {
      const dir = new THREE.Vector3(z.pos.x - p.pos.x, 0, z.pos.z - p.pos.z).normalize();
      this.zombies.damage(z, 2, dir, false, z.type === 2 ? 0.8 : 3.2);
      audio.play('hit_flesh', { pos: z.pos, volume: 0.4 });
    }
    audio.play('swing', { volume: 0.4 });
  }
  private wear(st: Stack, amt: number) {
    if (st.dur === undefined) return;
    st.dur -= amt * (this.rng.chance(0.5) ? 1 : 0);
    if (st.dur <= 0) {
      this.player.inv.removeUid(st.uid, 1);
      if (this.player.equipped === st.uid) this.player.equipped = null;
      this.ui.notify(`Your ${item(st.id).name} broke!`, 'bad'); audio.play('wood_break', { volume: 0.6 });
      this.ui.refresh();
    }
  }

  private playerAttacked(z: Zombie) {
    const p = this.player;
    if (this.driving) { const v = this.driving; if (Math.abs(v.speed) > 2.5 || this.rng.chance(0.7)) { v.condition = Math.max(0, v.condition - 1.5); audio.play('zombie_bang', { pos: v.pos }); return; } }
    this.attackers++;
    // facing a zombie and fresh stamina gives a chance to dodge/defend
    const toZ = Math.atan2(z.pos.x - p.pos.x, z.pos.z - p.pos.z);
    const facing = Math.abs(Math.atan2(Math.sin(toZ - (p.yaw + Math.PI)), Math.cos(toZ - (p.yaw + Math.PI)))) < 0.8;
    let hitChance = 0.72 + (z.type === 2 ? 0.1 : 0) - (facing ? 0.15 : 0) + (p.sprinting ? -0.2 : 0);
    if (this.swingTimer > 0 && facing) hitChance -= 0.15;
    if (!this.rng.chance(hitChance)) { audio.play('swing', { volume: 0.3 }); return; }
    this.cancelAction('You were attacked!');
    if (p.sleeping) this.wake(true);
    const parts: [PartId, number][] = [['armL', 22], ['armR', 22], ['torso', 26], ['head', 10], ['legL', 10], ['legR', 10]];
    const part = this.rng.weighted(parts);
    const slot = part === 'head' ? 'head' : part === 'torso' ? 'torso' : part.startsWith('arm') ? 'torso' : 'legs';
    const prot = p.protection(slot as any);
    const handProt = part.startsWith('arm') ? p.protection('hands') : { bite: 0, scratch: 0 };
    let type: WoundType = this.rng.weighted([['scratch', 55], ['laceration', 30], ['bite', z.type === 2 ? 25 : 15]]);
    const block = type === 'bite' ? Math.max(prot.bite, handProt.bite * 0.5) : Math.max(prot.scratch, handProt.scratch * 0.5);
    const thick = p.traits.has('thick_skin') ? 0.3 : 0;
    const g = this.renderer.grade.uniforms;
    g.uDamage.value = Math.min(1, g.uDamage.value + 0.6);
    p.camShake = 0.4; this.vm.hurt();
    audio.play('player_hurt');
    if (this.rng.chance(block + thick * (1 - block))) {
      p.stats.parts[part].hp = Math.max(0, p.stats.parts[part].hp - 3);
      this.ui.notify(`Your clothing stopped a ${type} to the ${PARTS.find(q => q.id === part)!.name.toLowerCase()}.`, '');
      return;
    }
    const dmg = type === 'bite' ? this.rng.range(16, 26) : type === 'laceration' ? this.rng.range(10, 18) : this.rng.range(5, 9);
    p.stats.addWound(part, type, dmg * (z.type === 2 ? 1.6 : 1));
    const infectChance = type === 'bite' ? 1 : type === 'laceration' ? 0.25 : 0.07;
    if (this.rng.chance(infectChance) && !p.stats.knoxInfected) p.stats.knoxInfected = true;
    this.ui.notify(`${type === 'bite' ? '🩸 BITTEN' : type === 'laceration' ? 'Lacerated' : 'Scratched'} on the ${PARTS.find(q => q.id === part)!.name.toLowerCase()}!${type === 'bite' ? ' That is not good.' : ''}`, 'bad');
    p.vel.add(new THREE.Vector3(p.pos.x - z.pos.x, 0, p.pos.z - z.pos.z).normalize().multiplyScalar(2));
  }

  private structureHit(ref: any, dmg: number, z: Zombie | null) {
    if (!ref) return;
    // town door / window
    if ('pivot' in ref && 'locked' in ref) {
      const d = ref as Door;
      if (d.barricade > 0) { d.barricade -= dmg; if (d.barricade <= 0) { d.barricade = 0; if (d.barricadeMesh) { d.barricadeMesh.parent?.remove(d.barricadeMesh); d.barricadeMesh = null; } audio.play('wood_break', { pos: new THREE.Vector3(d.cx, d.y + 1, d.cz) }); if (d.open) this.world.col.setSolid(d.box, false); } return; }
      d.hp -= dmg;
      if (d.hp <= 0) { d.open = true; d.target = Math.PI / 2; d.angle = Math.PI / 2 * 0.95; this.world.town.applyDoor(d); this.world.col.setSolid(d.box, false); d.locked = false; audio.play('wood_break', { pos: new THREE.Vector3(d.cx, d.y + 1, d.cz) }); }
      return;
    }
    if ('glass' in ref) {
      const w = ref as Win;
      if (w.barricade > 0) { w.barricade -= dmg; if (w.barricade <= 0) { w.barricade = 0; if (w.barricadeMesh) { w.barricadeMesh.parent?.remove(w.barricadeMesh); w.barricadeMesh = null; } audio.play('wood_break', { pos: new THREE.Vector3(w.cx, w.y + 1, w.cz) }); this.openWindowNav(w); } return; }
      if (!w.broken) this.breakWindow(w);
      return;
    }
    if ('def' in ref && 'tx' in ref) { this.base.damage(ref as Piece, dmg); return; }
    if ('def' in ref && 'cx' in ref && 'tiles' in ref) {
      const m = ref as Machine;
      this.factory.damageMachine(m, dmg);
      if (m.type === 'electric_fence' && m.powered && z) { this.zombies.damage(z, 30, new THREE.Vector3(z.pos.x - m.cx, 0, z.pos.z - m.cz), false, 2, false); this.particles.electric(z.pos); audio.play('zap', { pos: z.pos }); }
    }
  }
  breakWindow(w: Win) {
    w.broken = true; w.glass.visible = false;
    audio.play('glass_break', { pos: new THREE.Vector3(w.cx, w.y + 0.6, w.cz) });
    this.zombies.noise(new THREE.Vector3(w.cx, w.y, w.cz), 22);
    this.particles.sparks(new THREE.Vector3(w.cx, w.y + 0.6, w.cz), 10);
    this.openWindowNav(w);
  }
  private openWindowNav(w: Win) {
    if (w.barricade > 0) return;
    w.box.tag = 'window_broken';
    this.world.col.setSolid(w.box, true, 12);
  }

  private onKill(z: Zombie, byPlayer: boolean) {
    if (byPlayer) { this.player.kills++; this.stats.kills++; }
    this.particles.bloodDecal(z.pos.x, this.world.groundY(z.pos.x, z.pos.z, z.pos.y), z.pos.z, 1.4);
  }

  // ------------------------------------------------------------------ interaction
  private findInteract(): Interact | null {
    const p = this.player; const eye = p.eye(); const fwd = p.forward();
    const range = 2.6;
    let best: Interact | null = null;
    const consider = (it: Interact) => { if (it.t < range && (!best || it.t < best.t)) best = it; };
    const hit = this.world.col.raycast(eye, fwd, range + 0.5);
    const wallT = hit.t;
    if (hit.obj && (hit.obj as any).ref) {
      const ref = (hit.obj as any).ref;
      if ('pivot' in ref && 'locked' in ref) {
        const d = ref as Door; const hammer = p.inv.hasTool('hammer') && item(p.equippedStack()?.id ?? '').tools?.includes('hammer');
        if (hammer) consider({ kind: 'barricade', label: d.barricade > 0 ? 'Reinforce barricade' : 'Barricade door', sub: '2 planks · 3 nails', target: d, t: hit.t });
        else consider({ kind: 'door', label: d.locked ? 'Locked door' : d.open ? 'Close door' : 'Open door', sub: d.locked ? (p.inv.hasTool('pry') ? 'Pry open with crowbar' : 'Find a crowbar, or break it down') : d.barricade > 0 ? 'Barricaded' : undefined, target: d, t: hit.t });
      } else if ('glass' in ref) {
        const w = ref as Win; const hammer = item(p.equippedStack()?.id ?? '').tools?.includes('hammer');
        if (hammer) consider({ kind: 'barricade', label: w.barricade > 0 ? 'Reinforce barricade' : 'Barricade window', sub: '2 planks · 3 nails', target: w, t: hit.t });
        else consider({ kind: 'window', label: w.barricade > 0 ? 'Barricaded window' : w.broken ? 'Climb through window' : 'Smash window', sub: w.broken ? undefined : 'Loud!', target: w, t: hit.t });
      } else if ('def' in ref && 'tiles' in ref) {
        const m = ref as Machine;
        const net = this.factory.netOf(m);
        consider({ kind: 'machine', label: m.def.name, sub: net?.tripped ? '⚡ Fuse tripped — E to reset' : m.def.power > 0 ? (m.powered ? 'Powered' : 'No power') : undefined, target: m, t: hit.t });
      } else if ('def' in ref && 'tx' in ref) {
        const pc = ref as Piece;
        if (pc.door) consider({ kind: 'pdoor', label: pc.door.open ? 'Close' : 'Open', target: pc, t: hit.t });
        else if (pc.def.kind === 'station') consider(this.stationInteract(pc, hit.t));
        else if (pc.def.kind === 'tower') consider({ kind: 'ladder', label: 'Climb ladder', target: pc, t: hit.t });
      } else if (ref.kind === 'towncar' || ref.kind === 'vehicle') {
        const c = ref as TownCar | Vehicle;
        const wreck = c.variant === 'wreck';
        consider({ kind: 'car', label: wreck ? 'Burnt-out wreck' : 'Get in', sub: wreck ? undefined : `Fuel ${Math.round(c.fuel * 100)}% · ${c.keys || (c as Vehicle).hotwired ? 'keys in ignition' : 'no keys'} · condition ${Math.round(c.condition)}%`, target: c, t: hit.t, alt: this.carAlt(c) });
      } else if (ref.kind === 'pump') {
        consider({ kind: 'pump', label: this.factory.townPowered ? 'Fill gas can' : 'Gas pump (no power)', sub: this.factory.townPowered ? (p.inv.count('gas_can_empty') ? undefined : 'Bring an empty gas can') : 'Restore the town grid with the Grid Uplink', target: ref, t: hit.t });
      } else if ('purity' in ref) {
        const o = ref as OreNode;
        consider({ kind: 'ore', label: `${item(o.type).name} deposit (${o.purity > 1 ? 'Pure' : 'Normal'})`, sub: o.occupied ? 'Being mined' : 'Mine with a pickaxe, or place a Miner', target: o, t: hit.t });
      }
    }
    // containers (AABB)
    const tmp = new THREE.Box3(); const ray = new THREE.Ray(eye, fwd); const hp = new THREE.Vector3();
    for (const c of this.world.town.containers) {
      if (Math.abs(c.pos.x - p.pos.x) > 4 || Math.abs(c.pos.z - p.pos.z) > 4) continue;
      tmp.min.copy(c.pos).sub(c.half); tmp.max.copy(c.pos).add(c.half);
      if (ray.intersectBox(tmp, hp)) { const t = hp.distanceTo(eye); if (t < wallT + 0.6) consider({ kind: 'container', label: c.label, sub: c.generated ? (c.inv.stacks.length ? `${c.inv.stacks.length} items` : 'Empty') : 'Search', target: c, t }); }
    }
    for (const pc of this.base.pieces) {
      if (pc.def.kind !== 'station' || Math.abs(pc.cx - p.pos.x) > 4 || Math.abs(pc.cz - p.pos.z) > 4) continue;
      tmp.min.set(pc.cx - 0.9, pc.y, pc.cz - 0.9); tmp.max.set(pc.cx + 0.9, pc.y + 1.2, pc.cz + 0.9);
      if (ray.intersectBox(tmp, hp)) consider(this.stationInteract(pc, hp.distanceTo(eye)));
    }
    for (const d of this.world.drops) {
      if (d.pos.distanceTo(p.pos) > 3) continue;
      tmp.min.copy(d.pos).sub(new THREE.Vector3(0.45, 0.1, 0.45)); tmp.max.copy(d.pos).add(new THREE.Vector3(0.45, 0.5, 0.45));
      if (ray.intersectBox(tmp, hp)) consider({ kind: 'drop', label: 'Items', sub: `${d.inv.stacks.length} stacks`, target: d, t: hp.distanceTo(eye) });
    }
    const npc = this.survivors.nearest(p.pos, 3);
    if (npc) {
      tmp.min.set(npc.pos.x - 0.4, npc.pos.y, npc.pos.z - 0.4); tmp.max.set(npc.pos.x + 0.4, npc.pos.y + 1.8, npc.pos.z + 0.4);
      if (ray.intersectBox(tmp, hp)) consider(this.npcInteract(npc, hp.distanceTo(eye)));
    }
    const carcass = this.animals.carcassNear(p.pos, 2.6);
    if (carcass) consider({ kind: 'butcher', label: `Butcher ${carcass.type}`, sub: p.inv.hasTool('knife') ? `${this.animals.meat(carcass)} raw meat` : 'Needs a knife or machete', target: carcass, t: Math.max(0.5, carcass.pos.distanceTo(eye) - 0.8) });
    const corpse = this.zombies.corpseNear(p.pos, 2.4);
    if (corpse) {
      tmp.min.set(corpse.pos.x - 0.9, corpse.pos.y - 0.1, corpse.pos.z - 0.9); tmp.max.set(corpse.pos.x + 0.9, corpse.pos.y + 0.6, corpse.pos.z + 0.9);
      if (ray.intersectBox(tmp, hp)) consider({ kind: 'corpse', label: 'Search corpse', sub: corpse.corpse!.stacks.length ? '' : 'Empty', target: corpse, t: hp.distanceTo(eye) });
    }
    // machines by footprint (belts etc)
    if (!best) {
      for (let t = 0.5; t < range; t += 0.3) {
        const q = eye.clone().addScaledVector(fwd, t);
        if (q.y < this.world.groundY(q.x, q.z, q.y) + 1.2) {
          const m = this.factory.machineAtPos(q.x, q.z);
          if (m && (m.type === 'belt' || m.type === 'belt2' || m.type === 'splitter' || m.type === 'merger' || m.type === 'spike_trap')) { consider({ kind: 'machine', label: m.def.name, sub: m.items.length ? `${m.items.length} items` : undefined, target: m, t }); break; }
          if (q.y < this.world.groundY(q.x, q.z, q.y) + 0.1) break;
        }
      }
    }
    // water
    if (!best && this.world.nearWater(p.pos.x + fwd.x * 1.5, p.pos.z + fwd.z * 1.5) && fwd.y < -0.2) {
      const fish = p.inv.hasTool('fishing');
      consider({ kind: fish ? 'fish' : 'water', label: fish ? 'Fish' : p.inv.count('empty_bottle') ? 'Fill bottle (dirty water)' : 'Drink from the lake', sub: fish ? 'Patience…' : 'Untreated water can make you sick', target: null, t: 2, alt: fish ? { label: p.inv.count('empty_bottle') ? 'Fill bottle' : 'Drink', act: () => this.doInteract({ kind: 'water', label: '', target: null, t: 0 }) } : undefined });
    }
    return best;
  }
  private stationInteract(pc: Piece, t: number): Interact {
    const s = pc.def.station;
    if (s === 'workbench') return { kind: 'station', label: 'Workbench', sub: 'Craft', target: pc, t };
    if (s === 'campfire') return { kind: 'station', label: pc.fire!.lit ? 'Campfire (burning)' : 'Campfire', sub: pc.fire!.lit ? `Fuel: ${Math.round(pc.fire!.fuel)} min · cook` : 'Add fuel & light it', target: pc, t };
    if (s === 'crate') return { kind: 'pcontainer', label: 'Wooden Crate', target: pc, t };
    if (s === 'bed') return { kind: 'bed', label: 'Sleep', sub: this.player.stats.fatigue < 30 ? "You're not tired" : this.zombies.nearest(this.player.pos, 15) ? 'Zombies nearby!' : 'Rest until rested', target: pc, t };
    if (s === 'rain') return { kind: 'rain', label: 'Rain Collector', sub: `${pc.rain!.water.toFixed(1)} / 10 water · fill bottle / drink`, target: pc, t };
    if (s === 'generator') { const g = pc.gen!; return { kind: 'gen', label: g.on ? 'Switch generator off' : 'Start generator', sub: `Fuel ${g.fuel.toFixed(1)} h${g.fuel <= 0 ? ' — refuel with a gas can (T)' : ''}`, target: pc, t, alt: this.player.inv.count('gas_can') ? { label: 'Refuel (gas can)', act: () => this.startAction({ label: 'Refuelling generator…', dur: 2.5, cancelOnMove: true, done: () => { if (!this.player.inv.remove('gas_can', 1)) return; g.fuel = Math.min(24, g.fuel + 12); this.player.inv.add('gas_can_empty'); audio.play('splash', { volume: 0.5 }); } }) } : undefined }; }
    if (s === 'farm') { const f = pc.farm!; return { kind: 'farm', label: f.crop ? (f.growth >= 1 ? `Harvest ${item(f.crop).name}` : `${item(f.crop).name} — ${Math.round(f.growth * 100)}% grown`) : 'Plant seeds', sub: f.crop ? `Water ${Math.round(f.water * 100)}%` : undefined, target: pc, t }; }
    return { kind: 'station', label: pc.def.name, target: pc, t };
  }

  private doInteract(it: Interact) {
    const p = this.player;
    switch (it.kind) {
      case 'door': {
        const d = it.target as Door;
        if (d.barricade > 0) { this.ui.notify('It is barricaded. Break the barricade (attack it) or come in another way.', ''); return; }
        if (d.locked) {
          if (p.inv.hasTool('pry')) { this.startAction({ label: 'Prying the door…', dur: 3, cancelOnMove: true, done: () => { d.locked = false; audio.play('wood_break', { pos: new THREE.Vector3(d.cx, d.y + 1, d.cz) }); this.zombies.noise(p.pos, 12); } }); }
          else { audio.play('zombie_bang', { volume: 0.4 }); this.ui.notify('Locked. A crowbar would get you in — or smash a window.', ''); }
          return;
        }
        d.open = !d.open; d.target = d.open ? Math.PI / 2 : 0;
        const tick = () => { d.angle += (d.target - d.angle) * 0.25; this.world.town.applyDoor(d); if (Math.abs(d.target - d.angle) > 0.01) requestAnimationFrame(tick); };
        tick();
        this.world.col.setSolid(d.box, !d.open, 30);
        audio.play(d.open ? 'door_open' : 'door_close', { pos: new THREE.Vector3(d.cx, d.y + 1, d.cz) });
        break;
      }
      case 'window': {
        const w = it.target as Win;
        if (w.barricade > 0) return;
        if (!w.broken) { this.breakWindow(w); if (!p.worn.hands && this.rng.chance(0.25)) { p.stats.addWound(this.rng.chance(0.5) ? 'armL' : 'armR', 'laceration', 6); this.ui.notify('You cut yourself on the glass.', 'bad'); } return; }
        // vault through
        const n = w.axis === 'x' ? new THREE.Vector3(0, 0, 1) : new THREE.Vector3(1, 0, 0);
        const side = Math.sign((p.pos.x - w.cx) * n.x + (p.pos.z - w.cz) * n.z) || 1;
        this.startAction({ label: 'Climbing through…', dur: 0.9, cancelOnMove: false, done: () => { p.pos.set(w.cx - n.x * side * 0.75, p.pos.y, w.cz - n.z * side * 0.75); p.pos.y = this.world.groundY(p.pos.x, p.pos.z, p.pos.y + 1); if (!p.worn.torso && this.rng.chance(0.15)) p.stats.addWound('torso', 'scratch', 3); } });
        break;
      }
      case 'barricade': {
        const t = it.target as Door | Win;
        if (!p.inv.hasAll({ plank: 2, nails: 3 })) { this.ui.notify('Barricading needs 2 planks and 3 nails.', 'bad'); audio.play('ui_error'); return; }
        if ('glass' in t && !t.broken && false) return;
        this.startAction({ label: 'Barricading…', dur: 3.5 * (1 - p.skills.level('carpentry') * 0.06), cancelOnMove: true, done: () => {
          if (!p.inv.takeAll({ plank: 2, nails: 3 })) return;
          this.base.barricade(t, p.skills.level('carpentry'), this.world.group);
          if ('pivot' in t) { (t as Door).open = false; (t as Door).target = 0; (t as Door).angle = 0; this.world.town.applyDoor(t as Door); }
          audio.play('build_hammer'); this.zombies.noise(p.pos, 10);
          p.gainXP('carpentry', 6, this.gameMin); this.builtCounts.barricade = (this.builtCounts.barricade ?? 0) + 1;
        } });
        audio.play('build_hammer');
        break;
      }
      case 'container': {
        const c = it.target as Container;
        if (!c.generated) {
          const dur = 1.4 * (1 - p.skills.level('scavenging') * 0.05);
          audio.play('search', { pos: c.pos });
          this.startAction({ label: `Searching ${c.label.toLowerCase()}…`, dur, cancelOnMove: true, done: () => {
            c.generated = true;
            rollLoot(c.kind, this.rng, this.luck(), c.inv);
            p.gainXP('scavenging', 3, this.gameMin);
            this.openLoot(c.inv, c.label, c);
          } });
        } else this.openLoot(c.inv, c.label, c);
        break;
      }
      case 'pcontainer': { const pc = it.target as Piece; this.openLoot(pc.container!.inv, 'Wooden Crate', pc.container); break; }
      case 'drop': { const d = it.target as Drop; this.openLoot(d.inv, 'Ground', d); break; }
      case 'corpse': { const z = it.target as Zombie; audio.play('search', { pos: z.pos }); this.startAction({ label: 'Searching the body…', dur: 1.2, cancelOnMove: true, done: () => this.openLoot(z.corpse!, 'Corpse', z) }); break; }
      case 'machine': {
        const m = it.target as Machine;
        if (this.factory.resetFuse(m)) { this.ui.notify('⚡ Fuse reset.', 'good'); return; }
        this.openMachine = m; this.ui.open = null; this.ui.toggle(m.type === 'hub' ? 'hub' : 'machine'); audio.play('ui_open');
        break;
      }
      case 'pdoor': this.base.toggleDoor(it.target as Piece); break;
      case 'ladder': { const pc = it.target as Piece; const top = pc.y + 5; if (p.pos.y < top - 1) { p.pos.set(pc.cx, top + 0.05, pc.cz); } else { p.pos.set(pc.cx + 1.6, this.world.groundY(pc.cx + 1.6, pc.cz), pc.cz); } audio.play('footstep_wood'); break; }
      case 'station': {
        const pc = it.target as Piece;
        this.openPiece = pc;
        if (pc.def.station === 'workbench') { this.station = 'workbench'; this.ui.toggle('crafting'); }
        else if (pc.def.station === 'campfire') { this.station = 'campfire'; this.ui.toggle('campfire'); }
        audio.play('ui_open');
        break;
      }
      case 'bed': this.trySleep(); break;
      case 'rain': {
        const pc = it.target as Piece;
        if (pc.rain!.water < 1) { this.ui.notify('The collector is empty. Wait for rain.', ''); return; }
        if (p.inv.count('empty_bottle')) { p.inv.remove('empty_bottle'); p.inv.add('water_bottle'); pc.rain!.water -= 1; audio.play('splash'); this.ui.notify('Filled a bottle with clean rainwater.', 'good'); }
        else { pc.rain!.water -= 1; p.stats.thirst = Math.min(100, p.stats.thirst + 30); audio.play('drink'); }
        break;
      }
      case 'farm': this.farmInteract(it.target as Piece); break;
      case 'gen': { const g = (it.target as Piece).gen!; if (!g.on && g.fuel <= 0) { this.ui.notify('No fuel. Pour in a gas can (T).', ''); break; } g.on = !g.on; audio.play(g.on ? 'power_on' : 'power_off', { pos: (it.target as Piece).obj.position }); if (g.on && !this.world.town.buildingAt((it.target as Piece).cx, (it.target as Piece).cz)) this.ui.notify('Running — but it only powers a building it sits inside.', ''); break; }
      case 'water': {
        if (p.inv.count('empty_bottle')) { p.inv.remove('empty_bottle'); p.inv.add('dirty_water'); audio.play('splash'); this.ui.notify('Filled a bottle with lake water. Boil it before drinking.', ''); }
        else this.startAction({ label: 'Drinking…', dur: 2.5, cancelOnMove: true, done: () => { p.stats.thirst = Math.min(100, p.stats.thirst + 35); if (this.rng.chance(p.traits.has('iron_gut') ? 0.15 : 0.4)) p.stats.foodPoison = Math.min(1, p.stats.foodPoison + 0.4); audio.play('drink'); } });
        break;
      }
      case 'car': this.enterVehicle(it.target); break;
      case 'npc': this.talkTo(it.target as Survivor); break;
      case 'butcher': {
        const a = it.target as Animal;
        if (!p.inv.hasTool('knife')) { this.ui.notify('You need a knife or machete to butcher it.', ''); break; }
        this.startAction({ label: `Butchering the ${a.type}…`, dur: a.type === 'rabbit' ? 3 : 7, cancelOnMove: true, done: () => { if (a.butchered) return; a.butchered = true; const inv = new Inventory(); inv.add('raw_meat', this.animals.meat(a)); if (a.type !== 'rabbit') inv.add('rag', 1); this.giveOrDrop(inv); p.bloody = 1; } });
        audio.play('search');
        break;
      }
      case 'fish': {
        const dur = this.rng.range(8, 18);
        this.startAction({ label: 'Fishing… (stay still)', dur, cancelOnMove: true, done: () => {
          if (this.rng.chance(0.5 + this.luck() * 0.3)) { p.inv.add('raw_fish', 1); audio.play('splash'); this.ui.notify('🐟 Caught a fish!', 'good'); }
          else this.ui.notify('Nothing bites. Try again.', '');
        } });
        audio.play('splash', { volume: 0.4 });
        break;
      }
      case 'pump': {
        if (!this.factory.townPowered) { this.ui.notify('The pumps are dead. Siphon fuel from cars, or restore the grid.', ''); break; }
        const n = p.inv.count('gas_can_empty'); if (!n) { this.ui.notify('You need an empty gas can.', ''); break; }
        this.startAction({ label: 'Pumping fuel…', dur: 3, cancelOnMove: true, done: () => { p.inv.remove('gas_can_empty', n); p.inv.add('gas_can', n); audio.play('splash'); } });
        break;
      }
      case 'ore': this.ui.notify('Equip a pickaxe and swing at the deposit, or build a Miner on it (B).', ''); break;
    }
  }

  openLoot(inv: Inventory, label: string, ref: any) { this.openContainer = { inv, label, ref }; this.ui.open = null; this.ui.toggle('loot'); audio.play('ui_open'); }

  private farmInteract(pc: Piece) {
    const p = this.player; const f = pc.farm!;
    if (!f.crop) {
      const seed = p.inv.stacks.find(s => item(s.id).crop);
      if (!seed) { this.ui.notify('You have no seeds. Look in garages, sheds and stores.', ''); return; }
      this.startAction({ label: 'Planting…', dur: 2, cancelOnMove: true, done: () => { if (!p.inv.remove(seed.id, 1)) return; f.crop = item(seed.id).crop!; f.growth = 0; audio.play('search'); } });
    } else if (f.growth >= 1) {
      this.startAction({ label: 'Harvesting…', dur: 2, cancelOnMove: true, done: () => {
        const inv = new Inventory(); inv.add(f.crop!, 3 + this.rng.int(0, 3)); if (this.rng.chance(0.6)) inv.add('seeds_' + f.crop, this.rng.int(1, 3));
        f.crop = null; f.growth = 0; if (f.plant) { this.base.group.remove(f.plant); f.plant = null; } this.giveOrDrop(inv);
      } });
    } else if (p.inv.count('water_bottle') || p.inv.count('dirty_water')) {
      const id = p.inv.count('dirty_water') ? 'dirty_water' : 'water_bottle';
      p.inv.remove(id); p.inv.add('empty_bottle'); f.water = 1; audio.play('splash'); this.ui.notify('Watered the plot.', '');
    } else this.ui.notify('Growing. Rain waters it — or bring a bottle of water.', '');
  }

  // ------------------------------------------------------------------ timed actions
  startAction(a: Omit<TimedAction, 't'>) {
    if (this.action) return false;
    this.action = { ...a, t: 0 };
    return true;
  }
  private updateAction(dt: number) {
    const a = this.action; if (!a) return;
    if (a.cancelOnMove && this.player.speed > 1.2) { this.cancelAction('Interrupted'); return; }
    a.t += dt;
    if (a.t >= a.dur) { this.action = null; a.done(); this.ui.refresh(); }
  }
  cancelAction(reason?: string) { if (this.action) { this.action = null; this.vm.cancelUse(); if (reason) this.ui.notify(reason, ''); } }

  // ------------------------------------------------------------------ items
  useItem(uid: number) {
    const p = this.player; const s = p.inv.byUid(uid); if (!s) return;
    const d = item(s.id);
    if (d.weapon || d.id === 'flashlight') { p.equipped = p.equipped === uid ? null : uid; this.ui.refresh(); return; }
    if (d.cloth) { this.wearItem(uid); return; }
    if (d.cat === 'food' || d.cat === 'drink') {
      const dur = d.cat === 'drink' ? 2 : 3.5;
      this.vm.use(dur);
      audio.play(d.cat === 'drink' ? 'drink' : 'eat');
      this.startAction({ label: `${d.cat === 'drink' ? 'Drinking' : 'Eating'} ${d.name}…`, dur, cancelOnMove: false, done: () => {
        const st = p.inv.byUid(uid); if (!st) return;
        p.inv.removeUid(uid, 1);
        const spoiled = d.spoilDays && (st.age ?? 0) > d.spoilDays;
        p.stats.hunger = clamp(p.stats.hunger + (d.hunger ?? 0) * (spoiled ? 0.5 : 1), 0, 100);
        p.stats.thirst = clamp(p.stats.thirst + (d.thirst ?? 0), 0, 100);
        p.stats.unhappy = clamp(p.stats.unhappy - (d.mood ?? 0) + (spoiled ? 15 : 0), 0, 100);
        const sick = (d.sick ?? 0) + (spoiled ? 0.6 : 0);
        if (sick > 0 && this.rng.chance(sick * (p.traits.has('iron_gut') ? 0.35 : 1))) { p.stats.foodPoison = Math.min(1, p.stats.foodPoison + 0.45); this.ui.notify('Your stomach turns…', 'bad'); }
        if (d.empties) p.inv.add(d.empties);
      } });
      return;
    }
    if (d.med) {
      if (d.med === 'painkiller') { this.startAction({ label: 'Taking painkillers…', dur: 1.5, cancelOnMove: false, done: () => { p.inv.removeUid(uid, 1); p.stats.painkillerHrs = 4; } }); audio.play('drink', { volume: 0.4 }); return; }
      if (d.med === 'antibiotic') { this.startAction({ label: 'Taking antibiotics…', dur: 1.5, cancelOnMove: false, done: () => { p.inv.removeUid(uid, 1); p.stats.antibioticHrs = 12; } }); return; }
      if (d.med === 'serum') { this.startAction({ label: 'Injecting serum…', dur: 3, cancelOnMove: false, done: () => { p.inv.removeUid(uid, 1); p.stats.serumHrs = 24; if (!p.stats.knoxInfected) this.ui.notify('You are not infected. The serum is wasted on you.', ''); } }); return; }
      // auto-target worst wound
      const target = this.autoWound(d.med);
      if (!target) { this.ui.notify('No wound needs that right now. (H for the health panel)', ''); return; }
      this.treat(target[0], target[1], s.id);
      return;
    }
    if (d.skillBook) {
      this.startAction({ label: `Reading ${d.name}…`, dur: 8, cancelOnMove: true, done: () => { p.skills.bookUntil[d.skillBook as SkillId] = this.gameMin + 60 * 24; p.stats.unhappy = Math.max(0, p.stats.unhappy - 15); this.ui.notify(`📖 ${SKILLS.find(k => k.id === d.skillBook)!.name} XP ×3 for the next day.`, 'xp'); p.inv.removeUid(uid, 1); } });
      return;
    }
    if (d.id === 'repair_kit') { const v = this.vehicles.nearest(p.pos, 4); if (!v) { this.ui.notify('Use it next to a vehicle.', ''); return; } this.startAction({ label: 'Repairing the vehicle…', dur: 8, cancelOnMove: true, done: () => { if (!p.inv.remove('repair_kit', 1)) return; v.condition = Math.min(100, v.condition + 50); p.gainXP('engineering', 8, this.gameMin); this.ui.notify(`Vehicle condition ${Math.round(v.condition)}%`, 'good'); } }); audio.play('build_hammer'); return; }
    if (d.id === 'gas_can') { const v = this.vehicles.nearest(p.pos, 4); if (v) { const alt = this.carAlt(v); if (alt) alt.act(); } else this.ui.notify('Use it next to a car, or put it in a generator.', ''); return; }
    if (d.id === 'battery') { const fl = p.inv.find('flashlight'); if (fl) { fl.dur = 100; p.inv.removeUid(uid, 1); audio.play('reload', { volume: 0.4 }); this.ui.notify('Fresh batteries in the flashlight.', 'good'); } return; }
    if (d.id === 'duct_tape') { const st = p.equippedStack(); const w = st ? item(st.id).weapon : undefined; if (st && w && st.dur !== undefined) { st.dur = Math.min(w.durability, st.dur + w.durability * 0.3); p.inv.removeUid(uid, 1); this.ui.notify(`Repaired your ${item(st.id).name}.`, 'good'); } else this.ui.notify('Equip a weapon to repair it with tape.', ''); return; }
    this.ui.notify(`You can't use ${d.name} directly.`, '');
  }
  private autoWound(med: string): [PartId, number] | null {
    const p = this.player; let best: [PartId, number] | null = null, score = 0;
    for (const part of PARTS) p.stats.parts[part.id].wounds.forEach((w, i) => {
      let s = 0;
      if ((med === 'bandage' || med === 'rag') && (!w.bandaged || w.dirty)) s = w.bleed + 0.1 + (w.dirty ? 0.5 : 0);
      if (med === 'disinfect' && w.infection > 0) s = w.infection + 0.2;
      if (med === 'disinfect' && !w.bandaged && w.type !== 'fracture') s = Math.max(s, 0.05);
      if (med === 'splint' && w.type === 'fracture' && !w.splinted) s = 1;
      if (med === 'suture' && (w.type === 'laceration' || w.type === 'bite' || w.type === 'bullet') && !w.stitched) s = 0.8;
      if (s > score) { score = s; best = [part.id, i]; }
    });
    return best;
  }
  treat(part: PartId, wi: number, itemId: string) {
    const p = this.player; const d = item(itemId); const w = p.stats.parts[part].wounds[wi]; if (!w || !p.inv.count(itemId)) return;
    const fa = p.skills.level('firstaid');
    const dur = (d.med === 'suture' ? 6 : d.med === 'splint' ? 5 : 3) * (1 - fa * 0.06) * (p.traits.has('hemophobic') ? 1.5 : 1);
    audio.play('bandage');
    this.vm.use(dur);
    this.startAction({ label: `Applying ${d.name}…`, dur, cancelOnMove: true, done: () => {
      if (!p.inv.remove(itemId, 1)) return;
      if (d.med === 'bandage' || d.med === 'rag') { w.bandaged = true; w.dirty = d.med === 'rag'; w.bandageAge = 0; if (d.med === 'bandage') w.infection = Math.max(0, w.infection - 0.05); }
      if (d.med === 'disinfect') { w.infection = 0; w.dirty = false; }
      if (d.med === 'splint') w.splinted = true;
      if (d.med === 'suture') { w.stitched = true; w.bleed *= 0.2; }
      p.gainXP('firstaid', 5, this.gameMin);
    } });
  }
  quickHeal() {
    const p = this.player;
    const bleed = this.autoWound(p.inv.count('bandage') ? 'bandage' : 'rag');
    if (bleed && p.stats.parts[bleed[0]].wounds[bleed[1]].bleed > 0.01) { const id = p.inv.count('bandage') ? 'bandage' : p.inv.count('rag') ? 'rag' : null; if (id) { this.treat(bleed[0], bleed[1], id); return; } }
    this.ui.notify('Nothing to quick-treat (Q bandages your worst bleeding wound).', '');
  }
  wearItem(uid: number) {
    const p = this.player; const s = p.inv.byUid(uid); if (!s) return; const c = item(s.id).cloth!; if (!c) return;
    const old = p.worn[c.slot];
    p.inv.removeUid(uid, 1);
    p.worn[c.slot] = s;
    if (old) p.inv.addStack(old);
    audio.play('zipper'); this.ui.refresh();
  }
  takeOff(slot: keyof Player['worn']) { const p = this.player; const s = p.worn[slot]; if (!s) return; p.worn[slot] = null; p.inv.addStack(s); audio.play('zipper'); this.ui.refresh(); }
  dropStack(uid: number, n: number) {
    const p = this.player; const s = p.inv.removeUid(uid, n); if (!s) return;
    if (p.equipped === uid && !p.inv.byUid(uid)) p.equipped = null;
    const inv = new Inventory(); inv.addStack(s);
    const pos = p.pos.clone().add(p.forward().setY(0).normalize().multiplyScalar(0.8));
    this.world.dropItems(pos, inv); audio.play('drop'); this.ui.refresh();
  }
  transfer(from: Inventory, to: Inventory, uid: number, n: number) {
    const s = from.byUid(uid); if (!s) return false;
    const d = item(s.id); n = Math.min(n, s.n);
    if (to.capacity !== Infinity) { const free = to.capacity - to.weight(); n = Math.min(n, Math.floor(free / Math.max(0.001, d.weight))); if (n <= 0) { this.ui.notify('Not enough room.', 'bad'); audio.play('ui_error'); return false; } }
    const part = from.removeUid(uid, n); if (!part) return false;
    if (!to.addStack(part)) { from.addStack(part); return false; }
    if (from === this.player.inv && this.player.equipped === uid && !from.byUid(uid)) this.player.equipped = null;
    audio.play('pickup', { volume: 0.4 });
    // auto-assign weapons to free hotbar slots
    if (to === this.player.inv && (d.weapon || d.id === 'flashlight')) { const free = this.player.hotbar.findIndex(h => h === null || !this.player.inv.byUid(h)); if (free >= 0 && !this.player.hotbar.includes(part.uid)) this.player.hotbar[free] = part.uid; }
    // clean up empty drops
    if (this.openContainer?.ref && 'mesh' in this.openContainer.ref && !this.openContainer.inv.stacks.length) this.world.removeDrop(this.openContainer.ref);
    this.ui.refresh();
    return true;
  }

  canCraft(r: Recipe, station: Station) {
    const p = this.player;
    if (r.station !== 'hand' && r.station !== station) return { ok: false, why: `Needs ${r.station}` };
    if (r.tier && this.factory.tier < r.tier) return { ok: false, why: `Unlocks at milestone ${r.tier}` };
    if (r.skill && p.skills.level(r.skill[0]) < r.skill[1]) return { ok: false, why: `Requires ${SKILLS.find(s => s.id === r.skill![0])!.name} ${r.skill[1]}` };
    for (const t of r.tools ?? []) if (!p.inv.hasTool(t)) return { ok: false, why: `Needs a ${t}` };
    if (r.station === 'campfire' && !this.openPiece?.fire?.lit) return { ok: false, why: 'Light the campfire first' };
    if (!this.settings.creative && !p.inv.hasAll(r.inp)) return { ok: false, why: 'Missing ingredients' };
    return { ok: true, why: '' };
  }
  craft(r: Recipe, station: Station) {
    const c = this.canCraft(r, station); if (!c.ok) { this.ui.notify(c.why, 'bad'); audio.play('ui_error'); return; }
    const p = this.player;
    const sk = r.xp ? p.skills.level(r.xp[0]) : 0;
    audio.play('craft');
    this.startAction({ label: `Crafting ${item(r.out[0]).name}…`, dur: r.time * (1 - sk * 0.05), cancelOnMove: true, done: () => {
      if (!this.settings.creative && !p.inv.takeAll(r.inp)) return;
      p.inv.add(r.out[0], r.out[1]);
      if (r.xp) p.gainXP(r.xp[0], r.xp[1], this.gameMin);
      this.stats.crafted++;
      this.ui.notify(`Crafted ${r.out[1]}× ${item(r.out[0]).name}`, 'good');
    } });
  }

  // ------------------------------------------------------------------ build
  toggleBuild(on: boolean, id?: string) {
    this.buildMode = on;
    this.placer.setActive(on);
    if (id) this.placer.select(id);
    if (on) { this.ui.closeAll(); this.input.lock(); }
    audio.play(on ? 'ui_open' : 'ui_close');
  }
  private buildClick() {
    const r = this.placer.begin(); if (!r) return;
    if (r.kind === 'invalid') { this.ui.notify(r.data || 'Cannot place here', 'bad'); return; }
    if (r.kind === 'dismantle') { this.dismantle(r.data); return; }
    if (r.kind === 'place') {
      const { id, tx, tz, cx, cz, axis, rot } = r.data;
      const cost = MACHINES[id]?.cost ?? PIECES[id].cost;
      if (!this.settings.creative && !this.player.inv.takeAll(cost)) return;
      if (MACHINES[id]) {
        const m = this.factory.add(id, cx, cz, rot);
        if (id === 'hub') this.ui.notify('🏚 Survivor Outpost established. Open it (E) to see milestones. Your base is now on the map — and on the horde\'s radar.', 'good');
        audio.play('machine_place', { pos: new THREE.Vector3(cx, m.y, cz) });
        this.player.gainXP('engineering', 4, this.gameMin);
      } else {
        this.base.place(id, tx, tz, axis, rot);
        audio.play('build_hammer', { pos: new THREE.Vector3(cx, this.player.pos.y, cz) });
        this.zombies.noise(this.player.pos, 12);
        this.player.gainXP('carpentry', 4, this.gameMin);
      }
      this.builtCounts[id] = (this.builtCounts[id] ?? 0) + 1;
      this.stats.built++;
      this.particles.dust(new THREE.Vector3(cx, this.world.groundY(cx, cz), cz), 10);
      this.checkObjectives();
    }
  }
  private buildRelease() {
    const path = this.placer.end(); if (!path || !path.length) return;
    const id = this.placer.selected!;
    const cost = MACHINES[id].cost;
    let placed = 0;
    for (const s of path) {
      if (!this.settings.creative && !this.player.inv.takeAll(cost)) { this.ui.notify('Ran out of materials for belts.', 'bad'); break; }
      const [cx, cz] = Factory.centerFor(id, s.tx, s.tz, s.rot);
      if (!this.factory.canPlace(id, cx, cz, s.rot).ok) { if (!this.settings.creative) for (const k in cost) this.player.inv.add(k, cost[k]); continue; }
      if (id === 'pipe') this.factory.addPipe(s.tx, s.tz); else this.factory.add(id, cx, cz, s.rot);
      placed++;
    }
    if (placed) { audio.play('machine_place'); this.builtCounts[id] = (this.builtCounts[id] ?? 0) + placed; this.checkObjectives(); }
  }
  private dismantle(target: Machine | Piece) {
    const p = this.player;
    if ((target as any).kind === 'pipe') { const t = target as any; this.factory.removePipe(t.tx, t.tz); if (!this.settings.creative) this.giveOrDrop(this.invOf('iron_plate', 1)); audio.play('deconstruct'); return; }
    if ('tiles' in target) {
      const m = target as Machine;
      const inv = this.factory.contents(m);
      if (!this.settings.creative) for (const k in m.def.cost) inv.add(k, m.def.cost[k]);
      this.factory.remove(m);
      this.giveOrDrop(inv);
    } else {
      const pc = target as Piece;
      const inv = new Inventory();
      if (!this.settings.creative) for (const k in pc.def.cost) inv.add(k, Math.ceil(pc.def.cost[k] * 0.75));
      if (pc.container) for (const s of pc.container.inv.stacks) inv.addStack(s);
      this.base.remove(pc);
      this.giveOrDrop(inv);
    }
    audio.play('deconstruct');
    void p;
  }

  setRecipe(m: Machine, id: string | null) {
    const old = this.factory.setRecipe(m, id);
    if (old) { const inv = new Inventory(); for (const k in old) if (old[k] > 0) inv.add(k, old[k]); m.inBuf = {}; if (inv.stacks.length) this.giveOrDrop(inv); }
    this.ui.refresh();
  }
  takeOutput(m: Machine) {
    const inv = new Inventory();
    for (const k in m.outBuf) if (m.outBuf[k] > 0) { inv.add(k, m.outBuf[k]); m.outBuf[k] = 0; }
    if (inv.stacks.length) this.giveOrDrop(inv);
  }
  feedMachine(m: Machine, id: string, n: number) {
    const p = this.player; const have = p.inv.count(id); n = Math.min(n, have); if (!n) return;
    const r = this.factory.recipe(m);
    if (m.inv) { const left = m.inv.add(id, n); p.inv.remove(id, n - left); }
    else if (r && r.inp[id]) { p.inv.remove(id, n); m.inBuf[id] = (m.inBuf[id] ?? 0) + n; }
    audio.play('pickup', { volume: 0.4 }); this.ui.refresh();
  }
  depositHub(id: string) { const n = this.player.inv.count(id); const k = this.factory.depositToHub(id, n); if (k) { this.player.inv.remove(id, k); audio.play('pickup'); this.ui.refresh(); this.checkObjectives(); } }

  // campfire
  campfireFuel(pc: Piece, id: string) { const p = this.player; const d = item(id); if (!d.fuel || !p.inv.remove(id, 1)) return; pc.fire!.fuel += d.fuel * 3; audio.play('wood_break', { volume: 0.3 }); this.ui.refresh(); }
  campfireLight(pc: Piece) {
    const p = this.player;
    if (pc.fire!.lit) return;
    if (!p.inv.hasTool('fire')) { this.ui.notify('You need a lighter.', 'bad'); return; }
    if (pc.fire!.fuel <= 0) { this.ui.notify('Add fuel first: logs, planks or biomass.', 'bad'); return; }
    pc.fire!.lit = true; audio.play('fire_ignite'); this.ui.refresh();
  }

  // sleep
  trySleep() {
    const p = this.player;
    if (p.stats.fatigue < 30) { this.ui.notify("You're not tired enough to sleep.", ''); return; }
    if (this.zombies.nearest(p.pos, 15)) { this.ui.notify("You can't sleep with zombies nearby!", 'bad'); return; }
    p.sleeping = true; this.ui.closeAll(); audio.play('sleep');
    this.ui.setFade(true, 'Sleeping…');
    this.save(true);
  }
  wake(byZombie: boolean) {
    const p = this.player; p.sleeping = false; this.ui.setFade(false);
    if (byZombie) { this.ui.notify('You wake with a start — something is here!', 'bad'); p.stats.panic = 70; }
    else this.ui.notify(`You wake up. ${this.clock()}, day ${this.day()}.`, '');
  }

  private supplyDrop(pos: THREE.Vector3) {
    const inv = new Inventory();
    const r = this.rng;
    rollLoot('gun_locker', r, 0.5, inv); rollLoot('hospital', r, 0.5, inv); rollLoot('shop_shelf', r, 0.5, inv);
    inv.add('mre', 2); inv.add('water_bottle', 2);
    this.world.dropItems(pos, inv);
    this.ui.notify('📻 Radio: "Supply drop delivered near your tower. Stay safe out there." ', 'good');
    if (this.rng.chance(0.4)) { const s = this.survivors.spawn(pos.x + 3, pos.z + 3); this.ui.notify(`📻 A survivor named ${s.name} followed the broadcast to your tower. Talk to them.`, 'good'); }
    audio.play('objective');
  }

  private die(cause: string) {
    if (this.state !== 'play') return;
    this.state = 'dead';
    this.player.stats.dead = true;
    audio.play('player_death');
    this.input.unlock();
    try { localStorage.removeItem(SAVE_KEY); } catch { /* */ }
    setTimeout(() => this.ui.showDeath(cause), 1200);
  }

  // ------------------------------------------------------------------ objectives
  checkObjectives() {
    const p = this.player;
    const o = OBJECTIVES[this.objective]; if (!o) return;
    const has = (pred: (d: ItemDef) => boolean) => p.inv.stacks.some(s => pred(item(s.id)));
    let done = false;
    switch (o.id) {
      case 'weapon': done = has(d => !!d.weapon && d.weapon.damage >= 20); break;
      case 'supplies': done = has(d => d.cat === 'food') && has(d => d.cat === 'drink') || p.stats.hunger > 95; break;
      case 'workbench': done = (this.builtCounts.workbench ?? 0) > 0 || this.base.pieces.some(q => q.def.id === 'workbench'); break;
      case 'shelter': done = this.base.pieces.filter(q => q.def.kind.includes('wall') || q.def.kind === 'gate').length >= 5 || (this.builtCounts.barricade ?? 0) >= 4; break;
      case 'hub': done = !!this.factory.hub; break;
      case 'm1': done = this.factory.tier >= 1; break;
      case 'power': done = this.factory.machines.some(m => m.def.power < 0 && m.fuel > 0) && this.factory.machines.some(m => m.type === 'power_pole'); break;
      case 'mine': done = this.factory.machines.some(m => m.type === 'miner' && m.running) && this.factory.machines.some(m => m.type === 'smelter' && (m.running || Object.values(m.outBuf).some(v => v > 0))); break;
      case 'm2': done = this.factory.tier >= 2; break;
      case 'm3': done = this.factory.tier >= 3; break;
      case 'm4': done = this.factory.tier >= 4; break;
      case 'm5': done = this.factory.tier >= 5; break;
      case 'cure': done = this.factory.tier >= 6; break;
    }
    if (done) {
      this.objective++;
      audio.play('objective');
      const n = OBJECTIVES[this.objective];
      if (n) this.ui.notify(`✔ ${o.title} — next: ${n.title}`, 'good');
      this.checkObjectives();
    }
  }

  // ------------------------------------------------------------------ save / load
  save(auto: boolean) {
    if (this.state !== 'play') return;
    try {
      const town = this.world.town;
      const data = {
        v: 1, gameMin: this.gameMin, objective: this.objective, stats: this.stats, built: this.builtCounts,
        weather: { kind: this.weather.kind, wet: this.weather.wetness },
        vehicles: this.vehicles.toJSON(), survivors: this.survivors.toJSON(), lastFeedDay: this.lastFeedDay,
        player: this.player.toJSON(), factory: this.factory.toJSON(), base: this.base.toJSON(), zombies: this.zombies.toJSON(),
        containers: town.containers.filter(c => c.generated).map(c => [c.id, c.inv.stacks]),
        doors: town.doors.map(d => [d.open ? 1 : 0, Math.round(d.hp), Math.round(d.barricade), d.locked ? 1 : 0]),
        windows: town.windows.map(w => [w.broken ? 1 : 0, Math.round(w.barricade)]),
        veg: [...this.world.veg.removed],
        drops: this.world.drops.map(d => [d.pos.x, d.pos.y, d.pos.z, d.inv.stacks]),
        explored: Array.from(this.explored),
        settings: { creative: this.settings.creative },
      };
      localStorage.setItem(SAVE_KEY, JSON.stringify(data));
      if (!auto) this.ui.notify('💾 Game saved.', 'good'); else this.ui.notify('💾 Autosaved.', '');
    } catch (e) { this.ui.notify('Save failed: ' + (e as Error).message, 'bad'); }
  }
  load(): boolean {
    try {
      const raw = localStorage.getItem(SAVE_KEY); if (!raw) return false;
      const d = JSON.parse(raw);
      this.gameMin = d.gameMin; this.objective = d.objective ?? 0; Object.assign(this.stats, d.stats ?? {}); this.builtCounts = d.built ?? {};
      this.weather.kind = d.weather?.kind ?? 'cloudy'; this.weather.wetness = d.weather?.wet ?? 0;
      this.player.load(d.player);
      this.factory.engineering = this.player.skills.level('engineering'); this.base.carpentry = this.player.skills.level('carpentry');
      this.base.load(d.base ?? []);
      this.factory.load(d.factory);
      const town = this.world.town;
      const cmap = new Map<number, any>(d.containers ?? []);
      for (const c of town.containers) { const st = cmap.get(c.id); if (st) { c.generated = true; c.inv.load(st); } }
      (d.doors ?? []).forEach((a: number[], i: number) => {
        const dr = town.doors[i]; if (!dr) return;
        dr.hp = a[1]; dr.locked = !!a[3];
        if (a[0]) { dr.open = true; dr.target = dr.angle = Math.PI / 2 * 0.95; town.applyDoor(dr); this.world.col.setSolid(dr.box, false); }
        if (a[2] > 0) { dr.barricade = 0; this.base.barricade(dr, 0, this.world.group); dr.barricade = a[2]; }
      });
      (d.windows ?? []).forEach((a: number[], i: number) => {
        const w = town.windows[i]; if (!w) return;
        if (a[0] && !w.broken) { w.broken = true; w.glass.visible = false; this.openWindowNav(w); }
        if (!a[0] && w.broken) { w.broken = false; w.glass.visible = true; w.box.tag = 'window'; }
        if (a[1] > 0) { w.barricade = 0; this.base.barricade(w, 0, this.world.group); w.barricade = a[1]; }
      });
      for (const idx of d.veg ?? []) { const t = this.world.veg.insts[idx]; if (t && t.alive) { t.alive = false; this.world.veg.removed.add(idx); if (t.col) this.world.col.removeCircle(t.col); } }
      for (const dr of d.drops ?? []) { const inv = new Inventory(); inv.load(dr[3]); this.world.dropItems(new THREE.Vector3(dr[0], dr[1], dr[2]), inv); }
      if (d.explored) this.explored.set(d.explored);
      this.vehicles.load(d.vehicles ?? []);
      this.animals.populate();
      if (d.survivors) this.survivors.load(d.survivors); else this.survivors.populate();
      this.lastFeedDay = d.lastFeedDay ?? this.day();
      this.zombies.load(d.zombies ?? []);
      if (d.settings?.creative !== undefined) this.settings.creative = d.settings.creative;
      this.lastSave = this.gameMin;
      return true;
    } catch (e) { console.error(e); return false; }
  }
}
export { lerp };
