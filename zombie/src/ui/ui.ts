// DOM UI: menus, character creation, HUD, inventory/loot, crafting, health, skills, build, machines, hub, map.
import * as THREE from 'three';
import type { Game } from '../game';
import { OBJECTIVES } from '../game';
import { item, ItemDef } from '../items/items';
import { Inventory, Stack } from '../items/inventory';
import { RECIPES, Recipe } from '../items/recipes';
import { PARTS, PartId } from '../player/stats';
import { SKILLS, TRAITS, OCCUPATIONS, SkillId } from '../player/skills';
import { MACHINES, PIECES, MILESTONES, MRECIPES } from '../factory/defs';
import { audio } from '../audio/Audio';
import { HALF_WORLD, WORLD_SIZE } from '../core/util';
import { LAKE } from '../world/layout';
import type { Machine } from '../factory/factory';
import { ACTIONS } from '../core/input';

type PanelName = 'inventory' | 'crafting' | 'health' | 'skills' | 'journal' | 'loot' | 'machine' | 'hub' | 'campfire' | 'build' | 'map';
const CHAR_TABS: PanelName[] = ['inventory', 'crafting', 'health', 'skills', 'journal'];
const esc = (s: string) => s.replace(/[&<>"]/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]!));
const fmtCost = (cost: Record<string, number>, inv?: Inventory) => Object.entries(cost).map(([k, n]) => `<span class="${inv ? (inv.count(k) >= n ? 'have' : 'lack') : ''}">${item(k).icon} ${n} ${esc(item(k).name)}${inv ? ` (${inv.count(k)})` : ''}</span>`).join(' · ');

export class UI {
  root: HTMLElement;
  open: PanelName | null = null;
  private hud!: HTMLElement;
  private modal: HTMLElement | null = null;
  private sel: number | null = null;
  private selSide: 'p' | 'c' = 'p';
  private buildCat = 'structure';
  private refreshT = 0; private dirty = false;
  private mapCanvas: HTMLCanvasElement | null = null;
  private baseMap: HTMLCanvasElement | null = null;
  private els: Record<string, HTMLElement> = {};
  private hitT = 0;

  constructor(private g: Game) {
    this.root = document.getElementById('ui')!;
    this.buildHud();
    this.root.addEventListener('click', e => this.onClick(e));
    this.root.addEventListener('contextmenu', e => { const t = (e.target as HTMLElement).closest('[data-rc]') as HTMLElement | null; if (t) { e.preventDefault(); this.onRightClick(t); } });
  }

  // ------------------------------------------------------------------ menus
  showMenu() {
    const has = this.g.hasSave();
    const m = document.createElement('div'); m.className = 'menu'; m.id = 'mainmenu';
    m.innerHTML = `<div class="logo">RE<span>KINDLE</span></div><div class="tagline">survive the dead · rebuild the world</div>
      <button class="mbtn" data-a="continue" ${has ? '' : 'disabled'}>Continue</button>
      <button class="mbtn" data-a="new">New Survivor</button>
      <button class="mbtn" data-a="settings">Settings</button>
      <button class="mbtn" data-a="controls">Controls</button>
      <div class="foot">This is how you died… or how you rebuilt civilisation. Scavenge a ruined Pacific Northwest town, survive the infected and the elements, then build power grids, conveyor lines and factories — until the lights come back on and the cure goes out over the radio. Death is permanent.</div>`;
    this.root.appendChild(m);
  }
  hideMenu() { document.getElementById('mainmenu')?.remove(); document.querySelector('.cc')?.remove(); }
  showHud(on: boolean) { this.hud.classList.toggle('hidden', !on); }

  private charCreation() {
    const st = { name: 'Alex Mercer'.replace('Alex Mercer', ['Sam Rivers', 'Jo Hart', 'Casey Doyle', 'Riley Stone', 'Morgan Price'][Math.floor(Math.random() * 5)]), occ: 'unemployed', traits: new Set<string>() };
    const el = document.createElement('div'); el.className = 'cc';
    const render = () => {
      const occ = OCCUPATIONS.find(o => o.id === st.occ)!;
      let pts = occ.points; for (const t of st.traits) pts -= TRAITS.find(x => x.id === t)!.cost;
      el.innerHTML = `<div class="panel window"><div class="top"><h2 style="padding:0 8px">Create your survivor</h2><button class="close" data-a="cc-back">✕</button></div>
      <div class="body"><div class="col"><h3>Name</h3><input class="txt" id="ccname" value="${esc(st.name)}" maxlength="24"/>
      <h3>Occupation</h3><div class="scroll"><div class="occ">${OCCUPATIONS.map(o => `<div class="card ${o.id === st.occ ? 'on' : ''}" data-a="cc-occ" data-id="${o.id}"><div class="t">${o.icon} ${o.name} <span class="hint">(${o.points >= 0 ? '+' : ''}${o.points} pts)</span></div><div class="d">${esc(o.desc)}</div></div>`).join('')}</div></div></div>
      <div class="col"><h3>Traits — points available: <b style="color:${pts < 0 ? 'var(--bad)' : 'var(--good)'}">${pts}</b></h3><div class="scroll"><div class="traits">${TRAITS.map(t => {
        const on = st.traits.has(t.id); const dis = !on && (t.excl ?? []).some(x => st.traits.has(x));
        return `<div class="trait ${on ? 'on' : ''} ${dis ? 'dis' : ''}" data-a="cc-trait" data-id="${t.id}"><div><div>${t.name}</div><div class="td">${esc(t.desc)}</div></div><div class="c ${t.cost > 0 ? 'pos' : 'neg'}">${t.cost > 0 ? '-' : '+'}${Math.abs(t.cost)}</div></div>`;
      }).join('')}</div></div>
      <div class="btnrow" style="justify-content:flex-end;margin-top:12px"><button class="btn primary" data-a="cc-go" ${pts < 0 ? 'disabled' : ''} style="font-size:18px;padding:10px 26px">Begin — Day 1</button></div></div></div></div>`;
      const inp = el.querySelector('#ccname') as HTMLInputElement; inp.oninput = () => { st.name = inp.value; };
    };
    el.addEventListener('click', e => {
      const t = (e.target as HTMLElement).closest('[data-a]') as HTMLElement | null; if (!t) return;
      const a = t.dataset.a!;
      audio.play('ui_click');
      if (a === 'cc-occ') { st.occ = t.dataset.id!; render(); }
      if (a === 'cc-trait' && !t.classList.contains('dis')) { const id = t.dataset.id!; if (st.traits.has(id)) st.traits.delete(id); else st.traits.add(id); render(); }
      if (a === 'cc-back') { el.remove(); this.g.state = 'menu'; this.showMenu(); }
      if (a === 'cc-go') { el.remove(); this.g.newGame({ name: st.name, occupation: st.occ, traits: [...st.traits] }); }
    });
    render();
    this.root.appendChild(el);
  }

  private settingsModal() {
    const s = this.g.settings;
    this.showModal(`<div class="panel window" style="width:560px;height:auto;max-height:90vh"><div class="top"><h2 style="padding:0 8px">Settings</h2><button class="close" data-a="close-modal">✕</button></div>
      <div class="body" style="display:block" class="settings"><div class="settings">
      <label>Art style <select id="s-style"><option value="realistic" ${s.style !== 'lowpoly' ? 'selected' : ''}>Realistic</option><option value="lowpoly" ${s.style === 'lowpoly' ? 'selected' : ''}>Low-poly</option></select></label>
      <label>Graphics quality <select id="s-q">${['low', 'medium', 'high', 'ultra'].map(q => `<option ${q === s.quality ? 'selected' : ''}>${q}</option>`).join('')}</select></label>
      <label>Field of view <input type="range" id="s-fov" min="60" max="100" value="${s.fov}"/></label>
      <label>Mouse sensitivity <input type="range" id="s-sens" min="0.3" max="2.5" step="0.05" value="${s.sens}"/></label>
      <label>Master volume <input type="range" id="s-mv" min="0" max="1" step="0.05" value="${s.master}"/></label>
      <label>Music volume <input type="range" id="s-mu" min="0" max="1" step="0.05" value="${s.music}"/></label>
      <label>Day length (real minutes) <select id="s-day">${[16, 24, 36, 48, 72].map(d => `<option ${d === s.dayLen ? 'selected' : ''}>${d}</option>`).join('')}</select></label>
      <label>Creative mode (free building, no material cost) <input type="checkbox" id="s-cr" ${s.creative ? 'checked' : ''}/></label>
      <h3>Key bindings <span class="hint">(click, then press a key · Esc cancels)</span></h3>
      <div class="controls">${ACTIONS.map(([def, label]) => `<div><span>${label}</span><button class="btn small" data-a="rebind" data-id="${def}">${this.g.input.keyName(def)}</button></div>`).join('')}</div>
      <div class="btnrow"><button class="btn small" data-a="resetkeys">Reset keys</button></div>
      <p class="hint">Art style and graphics quality changes apply after reloading the page (your game autosaves).</p>
      <div class="btnrow"><button class="btn primary" data-a="save-settings">Apply</button><button class="btn" data-a="reload">Apply & reload</button></div></div></div></div>`);
  }
  private controlsModal() {
    const c: [string, string][] = [['Move', 'W A S D'], ['Sprint', 'Shift'], ['Crouch / sneak', 'C / Ctrl'], ['Jump', 'Space'], ['Attack / fire', 'Left mouse'], ['Aim (guns) / shove (melee)', 'Right mouse'], ['Third-person view / car camera', 'V'], ['Secondary action (refuel, hotwire, siphon…)', 'T'], ['Drive / steer / brake', 'W S / A D / Space'], ['Reload', 'R'], ['Interact / loot / open', 'E'], ['Flashlight', 'F'], ['Quick bandage', 'Q'], ['Hotbar', '1 – 8 / wheel'], ['Drop held item', 'G'], ['Inventory & crafting', 'Tab / I'], ['Health', 'H'], ['Skills', 'K'], ['Journal', 'J'], ['Map', 'M'], ['Build menu', 'B'], ['Rotate (build)', 'R'], ['Dismantle mode (build)', 'X'], ['Exit build mode', 'Right mouse / Esc'], ['Save', 'F5'], ['Pause', 'Esc']];
    this.showModal(`<div class="panel window" style="width:720px;height:auto"><div class="top"><h2 style="padding:0 8px">Controls</h2><button class="close" data-a="close-modal">✕</button></div><div class="body" style="display:block"><div class="controls">${c.map(([a, b]) => `<div><span>${a}</span><span class="kbd">${b}</span></div>`).join('')}</div>
    <h3>Survival tips</h3><p class="hint" style="line-height:1.6">Zombies hear sprinting, gunfire, chopping and machinery. Crouch to sneak. Bites infect you — clothing protects. Bandage bleeding wounds fast (Q). Boil lake water at a campfire. Barricade windows with a hammer, planks and nails. Your factory's noise ("heat") attracts hordes — build walls, electric fences and turrets before you scale up. Death is permanent.</p></div></div>`);
  }
  showPause(on: boolean) {
    document.getElementById('pausemenu')?.remove();
    if (!on) return;
    const m = document.createElement('div'); m.className = 'menu'; m.id = 'pausemenu';
    m.innerHTML = `<div class="logo" style="font-size:56px">PAUSED</div><div class="tagline">Day ${this.g.day()} · ${this.g.clock()} · ${this.g.player.kills} kills</div>
      <button class="mbtn" data-a="resume">Resume</button><button class="mbtn" data-a="save">Save game</button><button class="mbtn" data-a="settings">Settings</button><button class="mbtn" data-a="controls">Controls</button><button class="mbtn" data-a="quit">Save & quit to menu</button>`;
    this.root.appendChild(m);
  }
  showDeath(cause: string) {
    const p = this.g.player;
    const e = document.createElement('div'); e.className = 'endscreen';
    const days = Math.floor(p.stats.hoursSurvived / 24), hrs = Math.floor(p.stats.hoursSurvived % 24);
    e.innerHTML = `<h1>THIS IS HOW YOU DIED</h1><div class="cause">${esc(p.name)} — ${esc(cause)}</div>
      <div class="stats"><div>${days}d ${hrs}h<span>SURVIVED</span></div><div>${p.kills}<span>ZOMBIES KILLED</span></div><div>${this.g.stats.built}<span>THINGS BUILT</span></div><div>${this.g.factory.tier}<span>MILESTONES</span></div></div>
      <button class="btn primary" data-a="restart" style="font-size:20px;padding:12px 30px">New survivor</button>`;
    this.root.appendChild(e);
  }
  showVictory() {
    const p = this.g.player;
    const e = document.createElement('div'); e.className = 'endscreen win';
    e.innerHTML = `<h1 style="color:var(--accent2)">CIVILISATION REKINDLED</h1><div class="cause">The serum works. The formula is broadcast on every frequency. Across the valley, lights flicker back on.</div>
      <div class="stats"><div>${this.g.day()}<span>DAYS</span></div><div>${p.kills}<span>ZOMBIES KILLED</span></div><div>${this.g.factory.machines.length}<span>MACHINES</span></div><div>${this.g.stats.crafted}<span>CRAFTED</span></div></div>
      <button class="btn primary" data-a="keep-playing" style="font-size:20px;padding:12px 30px">Keep building</button>`;
    this.root.appendChild(e);
  }
  setFade(on: boolean, text = '') { const f = this.els.fade; f.style.opacity = on ? '1' : '0'; f.textContent = text; }

  private showModal(html: string) { this.closeModal(); const m = document.createElement('div'); m.className = 'modal'; m.style.zIndex = '45'; m.innerHTML = html; this.root.appendChild(m); this.modal = m; }
  private closeModal() { this.modal?.remove(); this.modal = null; }

  // ------------------------------------------------------------------ HUD
  private buildHud() {
    const h = document.createElement('div'); h.id = 'hud'; h.className = 'hidden';
    h.innerHTML = `<div id="crosshair"><i class="d"></i><i class="l"></i><i class="r"></i><i class="t"></i><i class="b"></i></div><div id="hitmark"></div>
      <div id="prompt"></div><div id="action" class="hidden"><div id="actlabel"></div><div class="bar"><div id="actbar"></div></div></div>
      <div id="topleft"><div id="clock"></div><div id="day"></div><div id="temp"></div></div>
      <div id="compass"><div class="strip" id="cstrip"></div></div>
      <div id="objective"><div class="oh">Objective</div><div class="ot" id="objt"></div><div class="od" id="objd"></div></div>
      <div id="moodles"></div><div id="notes"></div>
      <div id="vitals"><div class="vital hp"><span>HEALTH</span><div class="bar"><div id="hpbar"></div></div></div><div class="vital st"><span>STAMINA</span><div class="bar"><div id="stbar"></div></div></div><div class="vital" id="heatrow"><span>BASE HEAT</span><div class="bar"><div id="heatbar" style="background:linear-gradient(90deg,#c07a20,#e0402a)"></div></div></div></div>
      <div id="hotbar"></div><div id="ammo"></div><canvas id="minimap" width="170" height="170"></canvas>
      <div id="buildbar" class="hidden"></div><div id="horde" class="hidden">⚠ Horde approaching</div>`;
    this.root.appendChild(h); this.hud = h;
    const fade = document.createElement('div'); fade.id = 'fade'; document.body.appendChild(fade); this.els.fade = fade;
    for (const id of ['prompt', 'action', 'actlabel', 'actbar', 'clock', 'day', 'temp', 'cstrip', 'objt', 'objd', 'moodles', 'notes', 'hpbar', 'stbar', 'heatbar', 'heatrow', 'hotbar', 'ammo', 'minimap', 'buildbar', 'horde', 'hitmark']) this.els[id] = h.querySelector('#' + id)!;
    // compass strip
    const marks: string[] = [];
    for (let deg = -360; deg <= 720; deg += 15) {
      const d = ((deg % 360) + 360) % 360;
      const lab = d === 0 ? 'N' : d === 90 ? 'E' : d === 180 ? 'S' : d === 270 ? 'W' : d % 45 === 0 ? ['', 'NE', '', 'SE', '', 'SW', '', 'NW'][d / 45] : '';
      marks.push(`<div class="mk ${lab.length === 1 ? 'card' : lab ? '' : 'minor'}" style="left:${(deg + 360) * 3}px">${lab || (d % 30 === 0 ? d : '')}</div><div class="tick" style="left:${(deg + 360) * 3}px"></div>`);
    }
    this.els.cstrip.innerHTML = marks.join('') + '<div id="cpois"></div>';
  }

  notify(msg: string, kind = '') {
    const n = document.createElement('div'); n.className = 'note ' + (kind ?? ''); n.innerHTML = esc(msg);
    this.els.notes.prepend(n);
    while (this.els.notes.children.length > 6) this.els.notes.lastElementChild!.remove();
    setTimeout(() => n.classList.add('fade'), 6500); setTimeout(() => n.remove(), 7100);
  }
  hitmark(kill: boolean) { const h = this.els.hitmark; h.classList.toggle('kill', kill); h.style.opacity = '1'; this.hitT = 0.15; }
  refresh() { this.dirty = true; }

  update(dt: number) {
    const g = this.g, p = g.player;
    if (g.state !== 'play') return;
    this.hitT -= dt; if (this.hitT <= 0) this.els.hitmark.style.opacity = '0';
    this.hudT -= dt;
    if (this.hudT > 0 && !this.dirty) { this.panelTick(dt); return; }
    this.hudT = 0.1;
    this.els.clock.textContent = g.clock();
    this.els.day.textContent = `Day ${g.day()} · ${g.season().name} · ${g.weather.temperature(12) < 1 && g.weather.rain > 0.1 ? 'snow' : g.weather.kind}`;
    const t = g.weather.temperature(g.hours());
    this.els.temp.textContent = `${t.toFixed(0)}°C outside · body ${p.stats.bodyTemp.toFixed(1)}°C`;
    // compass
    const yawDeg = ((-p.yaw * 180 / Math.PI) % 360 + 360) % 360;
    this.els.cstrip.style.transform = `translateX(${260 - (yawDeg + 360) * 3}px)`;
    let pois = '';
    const poi = (x: number, z: number, icon: string) => { const a = (Math.atan2(x - p.pos.x, -(z - p.pos.z)) * 180 / Math.PI + 360) % 360; for (const off of [0, 360]) pois += `<div class="poi" style="left:${(a + off + 360 - 360 + 360) * 3 - 360 * 3 + 360 * 3}px">${icon}</div>`; };
    if (g.factory.hub) poi(g.factory.hub.cx, g.factory.hub.cz, '🏚️');
    if (p.traits.has('keen_hearing') || true) {
      for (const z of g.zombies.list) {
        if (z.state === 'dead' || z.frozen) continue;
        const d = z.pos.distanceTo(p.pos); if (d > (p.traits.has('keen_hearing') ? 18 : 10)) continue;
        const a = (Math.atan2(z.pos.x - p.pos.x, -(z.pos.z - p.pos.z)) * 180 / Math.PI + 360) % 360;
        for (const off of [0, 360]) pois += `<div class="threat" style="left:${(a + off + 360) * 3 - 360 * 3 + 360 * 3 - 360 * 3 + 360 * 3}px;opacity:${(1 - d / 18).toFixed(2)}"></div>`;
      }
    }
    const cp = document.getElementById('cpois'); if (cp) cp.innerHTML = pois;
    // objective
    const o = OBJECTIVES[g.objective]; this.els.objt.textContent = o?.title ?? ''; this.els.objd.textContent = o?.desc ?? '';
    // moodles
    const mood = p.stats.moodles(p.weightRatio());
    if (p.stats.knoxInfected && p.stats.knox > 0.15) { /* shown as sick */ }
    this.els.moodles.innerHTML = mood.map(m => `<div class="moodle ${m.bad ? 'l' + m.level : 'good'}" title="${esc(m.desc)}"><span class="ml">${esc(m.label)}</span><div class="mi">${m.icon}${m.bad ? `<span class="lv">${'I'.repeat(m.level)}</span>` : ''}</div></div>`).join('');
    (this.els.hpbar as HTMLElement).style.width = p.stats.health() + '%';
    (this.els.stbar as HTMLElement).style.width = (p.stats.stamina / Math.max(1, p.stats.maxStamina) * 100) + '%';
    this.els.heatrow.classList.toggle('hidden', !g.factory.hub);
    (this.els.heatbar as HTMLElement).style.width = Math.min(100, (g.factory.heat + g.combatHeat) * 2) + '%';
    // prompt
    const it = g.interact;
    if (g.action) { this.els.prompt.innerHTML = ''; }
    else if (it && !g.buildMode && !this.open) this.els.prompt.innerHTML = `<kbd>E</kbd>${esc(it.label)}${it.alt ? ` &nbsp; <kbd>T</kbd>${esc(it.alt.label)}` : ''}${it.sub ? `<div class="sub">${esc(it.sub)}</div>` : ''}`;
    else this.els.prompt.innerHTML = '';
    // action
    if (g.action) { this.els.action.classList.remove('hidden'); this.els.actlabel.textContent = g.action.label; (this.els.actbar as HTMLElement).style.width = (g.action.t / g.action.dur * 100) + '%'; }
    else this.els.action.classList.add('hidden');
    // hotbar
    let hb = '';
    for (let i = 0; i < 8; i++) {
      const uid = p.hotbar[i]; const s = uid !== null ? p.inv.byUid(uid) : null; const d = s ? item(s.id) : null;
      const durPct = s && d?.weapon && s.dur !== undefined ? s.dur / d.weapon.durability * 100 : -1;
      hb += `<div class="hb ${s && p.equipped === s.uid ? 'on' : ''}"><span class="k">${i + 1}</span>${d ? d.icon : ''}${s && s.n > 1 ? `<span class="n">${s.n}</span>` : ''}${durPct >= 0 ? `<div class="d" style="width:${durPct}%"></div>` : ''}</div>`;
    }
    this.els.hotbar.innerHTML = hb;
    const st = p.equippedStack(); const w = st ? item(st.id).weapon : undefined;
    if (g.driving) this.els.ammo.innerHTML = `<div class="wn">${esc(g.driving.variant)} · fuel ${Math.round(g.driving.fuel * 100)}% · condition ${Math.round(g.driving.condition)}%</div><div class="mag">${Math.round(Math.abs(g.driving.speed) * 3.6)}<span class="res"> km/h</span></div>`;
    else if (st && w?.kind === 'firearm') this.els.ammo.innerHTML = `<div class="wn">${esc(item(st.id).name)}</div><div class="mag">${st.ammo ?? 0}<span class="res"> / ${p.inv.count(w.ammo!)}</span></div>`;
    else if (st) this.els.ammo.innerHTML = `<div class="wn">${esc(item(st.id).name)}</div>${st.id === 'flashlight' ? `<div class="res">battery ${Math.round(st.dur ?? 0)}%</div>` : st.dur !== undefined && w ? `<div class="res">${Math.round(st.dur / w.durability * 100)}% condition</div>` : ''}`;
    else this.els.ammo.innerHTML = '';
    // build bar
    if (g.buildMode) {
      this.els.buildbar.classList.remove('hidden');
      const id = g.placer.selected;
      const def = id ? (MACHINES[id] ?? PIECES[id]) : null;
      this.els.buildbar.innerHTML = g.placer.dismantle ? `<div class="bn2" style="color:#ffa060">DISMANTLE MODE</div><div class="hint">Left-click a structure to dismantle it (refund) · X to switch back · RMB exit</div>`
        : def ? `<div class="bn2">${def.icon} ${esc(def.name)}</div><div class="cost">${fmtCost(def.cost, p.inv)}</div><div class="hint">${g.placer.valid ? 'LMB place' : `<span class="lack">${esc(g.placer.reason)}</span>`} · R rotate · X dismantle · B menu · RMB exit${id === 'belt' || id === 'belt2' ? ' · drag to lay a line' : ''}</div>` : '<div class="hint">Pick something from the build menu (B)</div>';
    } else this.els.buildbar.classList.add('hidden');
    this.els.horde.classList.toggle('hidden', g.hordeWarning <= 0);
    this.drawMinimap();
    this.panelTick(0.1);
  }
  private hudT = 0;
  private panelTick(dt: number) {
    // panels live refresh
    this.refreshT -= dt;
    if (this.open && (this.dirty || this.refreshT <= 0)) { this.refreshT = this.open === 'map' ? 1 : ['machine', 'hub', 'campfire', 'health'].includes(this.open) ? 0.4 : 1.5; this.dirty = false; this.renderPanel(); }
  }

  // ------------------------------------------------------------------ panel management
  anyOpen() { return !!this.open || !!this.modal; }
  toggle(name: PanelName) {
    if (this.open === name) { this.closeAll(); return; }
    this.open = name; this.sel = null;
    this.g.input.unlock();
    this.renderPanel();
    audio.play('ui_open');
  }
  closeAll() {
    if (this.open === 'loot' && this.g.openContainer?.ref && 'mesh' in this.g.openContainer.ref && !this.g.openContainer.inv.stacks.length) this.g.world.removeDrop(this.g.openContainer.ref);
    this.open = null; this.panelEl?.remove(); this.panelEl = null; this.closeModal();
    this.g.openContainer = null; this.g.openMachine = null; this.g.openPiece = null; this.g.station = 'hand';
    if (this.g.state === 'play' && !this.g.paused) this.g.input.lock();
    audio.play('ui_close');
  }
  private panelEl: HTMLElement | null = null;
  private renderPanel() {
    if (!this.open) return;
    if (!this.panelEl) { this.panelEl = document.createElement('div'); this.panelEl.className = 'modal'; this.root.appendChild(this.panelEl); }
    const scrollTops = Array.from(this.panelEl.querySelectorAll('.scroll')).map(e => e.scrollTop);
    let html = '';
    const n = this.open;
    if (CHAR_TABS.includes(n)) {
      html = `<div class="panel window"><div class="top">${CHAR_TABS.map(t => `<button class="tab ${t === n ? 'on' : ''}" data-a="tab" data-id="${t}">${{ inventory: 'Inventory', crafting: this.g.station === 'workbench' ? 'Workbench' : 'Crafting', health: 'Health', skills: 'Skills', journal: 'Journal' }[t as string]}</button>`).join('')}<button class="close" data-a="close">✕</button></div><div class="body">`;
      if (n === 'inventory') html += this.invPanel();
      if (n === 'crafting') html += this.craftPanel();
      if (n === 'health') html += this.healthPanel();
      if (n === 'skills') html += this.skillsPanel();
      if (n === 'journal') html += this.journalPanel();
      html += '</div></div>';
    } else if (n === 'loot') html = this.lootPanel();
    else if (n === 'machine') html = this.machinePanel();
    else if (n === 'hub') html = this.hubPanel();
    else if (n === 'campfire') html = this.campfirePanel();
    else if (n === 'build') html = this.buildPanel();
    else if (n === 'map') html = `<div class="panel window"><div class="top"><h2 style="padding:0 8px">Map</h2><span class="hint" style="margin-left:12px">Explored areas only · 🏚 outpost · ▲ you</span><button class="close" data-a="close">✕</button></div><div class="body" style="padding:6px"><canvas id="mapcanvas" width="1024" height="1024"></canvas></div></div>`;
    this.panelEl.innerHTML = html;
    this.panelEl.querySelectorAll('.scroll').forEach((e, i) => { e.scrollTop = scrollTops[i] ?? 0; });
    if (n === 'map') this.drawMap(this.panelEl.querySelector('#mapcanvas') as HTMLCanvasElement);
  }

  // ------------------------------------------------------------------ inventory
  private itemRow(s: Stack, side: string, extra = '') {
    const d = item(s.id); const p = this.g.player;
    const durPct = d.weapon && s.dur !== undefined ? s.dur / d.weapon.durability * 100 : d.light ? (s.dur ?? 0) : -1;
    const spoiled = d.spoilDays && (s.age ?? 0) > d.spoilDays;
    const sel = this.sel === s.uid && this.selSide === side;
    return `<div class="irow ${sel ? 'sel' : ''}" data-a="sel" data-uid="${s.uid}" data-side="${side}" data-rc="1"><span class="ic">${d.icon}</span><span class="nm">${esc(d.name)}${spoiled ? ' <span class="tag" style="color:var(--bad)">rotten</span>' : ''}</span>${p.equipped === s.uid && side === 'p' ? '<span class="tag eq">held</span>' : ''}${p.hotbar.includes(s.uid) && side === 'p' ? `<span class="tag">${p.hotbar.indexOf(s.uid) + 1}</span>` : ''}${durPct >= 0 ? `<div class="dur"><div style="width:${durPct}%;background:${durPct < 25 ? 'var(--bad)' : 'var(--good)'}"></div></div>` : ''}${d.weapon?.ammo ? `<span class="tag">${s.ammo}/${d.weapon.mag}</span>` : ''}<span class="qt">${s.n > 1 ? '×' + s.n : ''}</span><span class="wt">${(d.weight * s.n).toFixed(1)}kg</span>${extra}</div>`;
  }
  private weightBar() {
    const p = this.g.player; const w = p.weight(), c = p.capacity();
    return `<div class="wbar ${w > c ? 'over' : ''}"><span>Weight ${w.toFixed(1)} / ${c.toFixed(1)} kg</span><div class="bar"><div style="width:${Math.min(100, w / c * 100)}%"></div></div></div>`;
  }
  private equipBlock() {
    const p = this.g.player;
    const slots: [keyof typeof p.worn, string][] = [['head', 'Head'], ['torso', 'Torso'], ['legs', 'Legs'], ['feet', 'Feet'], ['hands', 'Hands'], ['back', 'Back']];
    return `<div class="equip">${slots.map(([k, l]) => { const s = p.worn[k]; return `<div class="slot" data-a="unwear" data-slot="${k}" title="Click to take off">${l}<b>${s ? item(s.id).icon + ' ' + esc(item(s.id).name) : '—'}</b></div>`; }).join('')}</div>`;
  }
  private details(s: Stack | null, side: 'p' | 'c') {
    if (!s) return `<div class="details">Select an item. <span class="hint">Double-click / right-click to use or transfer.</span></div>`;
    const d = item(s.id); const p = this.g.player;
    const lines: string[] = [];
    if (d.hunger) lines.push(`Hunger +${d.hunger}`); if (d.thirst) lines.push(`Thirst ${d.thirst > 0 ? '+' : ''}${d.thirst}`);
    if (d.weapon) { const w = d.weapon; lines.push(`${w.kind} · dmg ${w.damage}${w.pellets ? '×' + w.pellets : ''} · range ${w.range}m · ${w.kind === 'firearm' ? `mag ${w.mag} · noise ${w.noise}m` : `${w.speed.toFixed(1)} swings/s`}`); if (s.dur !== undefined) lines.push(`Condition ${Math.round(s.dur / w.durability * 100)}%`); }
    if (d.cloth) lines.push(`${d.cloth.slot} · insulation ${Math.round(d.cloth.insul * 100)} · bite ${Math.round(d.cloth.bite * 100)}% · scratch ${Math.round(d.cloth.scratch * 100)}%${d.cloth.capacity ? ` · +${d.cloth.capacity}kg` : ''}`);
    if (d.spoilDays) lines.push(`Freshness: ${Math.max(0, d.spoilDays - (s.age ?? 0)).toFixed(1)} days left`);
    if (d.fuel) lines.push(`Fuel value ${d.fuel}`);
    const btns: string[] = [];
    if (side === 'p') {
      if (d.weapon || d.id === 'flashlight') btns.push(`<button class="btn primary" data-a="use" data-uid="${s.uid}">${p.equipped === s.uid ? 'Unequip' : 'Equip'}</button>`);
      else if (d.cloth) btns.push(`<button class="btn primary" data-a="use" data-uid="${s.uid}">Wear</button>`);
      else if (['food', 'drink', 'medical', 'book'].includes(d.cat) || d.id === 'battery' || d.id === 'duct_tape') btns.push(`<button class="btn primary" data-a="use" data-uid="${s.uid}">${d.cat === 'food' ? 'Eat' : d.cat === 'drink' ? 'Drink' : d.cat === 'book' ? 'Read' : 'Use'}</button>`);
      for (let i = 0; i < 8; i++) if (d.weapon || d.cat === 'food' || d.cat === 'drink' || d.cat === 'medical' || d.id === 'flashlight') { btns.push(`<button class="btn small" data-a="hot" data-uid="${s.uid}" data-i="${i}">${i + 1}</button>`); }
      btns.push(`<button class="btn" data-a="drop" data-uid="${s.uid}" data-n="1">Drop 1</button>`);
      if (s.n > 1) btns.push(`<button class="btn" data-a="drop" data-uid="${s.uid}" data-n="${s.n}">Drop all</button>`);
      if (this.g.openContainer) btns.push(`<button class="btn" data-a="xfer" data-uid="${s.uid}" data-side="p" data-n="${s.n}">Transfer →</button>`);
    } else {
      btns.push(`<button class="btn primary" data-a="xfer" data-uid="${s.uid}" data-side="c" data-n="${s.n}">← Take</button>`);
      if (s.n > 1) btns.push(`<button class="btn" data-a="xfer" data-uid="${s.uid}" data-side="c" data-n="1">Take 1</button>`);
    }
    return `<div class="details"><div class="title">${d.icon} ${esc(d.name)}</div><div>${esc(d.desc)}</div><div style="margin-top:4px;color:var(--text)">${lines.join(' · ')}</div><div class="btnrow">${btns.join('')}</div></div>`;
  }
  private invPanel() {
    const p = this.g.player;
    const sel = this.sel !== null ? p.inv.byUid(this.sel) ?? null : null;
    return `<div class="col">${this.equipBlock()}${this.weightBar()}<div class="btnrow" style="margin:0 0 6px"><button class="btn small" data-a="sort">Sort</button></div><div class="scroll"><div class="ilist">${p.inv.stacks.map(s => this.itemRow(s, 'p')).join('') || '<div class="hint">Your pockets are empty.</div>'}</div></div>${this.details(sel, 'p')}</div>
      <div class="col" style="max-width:360px"><h3>Survivor</h3><div class="statgrid">${this.statLines()}</div><h3>Quick craft</h3><div class="scroll">${this.recipeList('hand', 6)}</div></div>`;
  }
  private statLines() {
    const p = this.g.player, s = p.stats;
    const row = (k: string, v: number, col: string) => `<div class="k">${k}</div><div class="v">${Math.round(v)}%</div><div style="grid-column:1/3"><div class="meter"><div style="width:${v}%;background:${col}"></div></div></div>`;
    return `<div class="k">Name</div><div class="v">${esc(p.name)}</div><div class="k">Occupation</div><div class="v">${OCCUPATIONS.find(o => o.id === p.occupation)?.name}</div><div class="k">Kills</div><div class="v">${p.kills}</div>` +
      row('Fullness', s.hunger, '#c8a040') + row('Hydration', s.thirst, '#40a0d0') + row('Rest', 100 - s.fatigue, '#9070c0') + row('Health', s.health(), '#d04030');
  }

  private lootPanel() {
    const g = this.g, p = g.player, c = g.openContainer; if (!c) return '';
    const sel = this.sel !== null ? (this.selSide === 'p' ? p.inv.byUid(this.sel) : c.inv.byUid(this.sel)) ?? null : null;
    const cap = c.inv.capacity === Infinity ? '' : ` · ${c.inv.weight().toFixed(1)} / ${c.inv.capacity} kg`;
    return `<div class="panel window"><div class="top"><h2 style="padding:0 8px">${esc(c.label)}</h2><span class="hint" style="margin-left:10px">Click to select · double-click to transfer</span><button class="close" data-a="close">✕</button></div><div class="body">
      <div class="col"><h3>Inventory</h3>${this.weightBar()}<div class="scroll"><div class="ilist">${p.inv.stacks.map(s => this.itemRow(s, 'p')).join('')}</div></div></div>
      <div class="col"><h3 style="display:flex;justify-content:space-between">${esc(c.label)}${cap}<button class="btn small" data-a="takeall">Take all</button></h3><div class="scroll"><div class="ilist">${c.inv.stacks.map(s => this.itemRow(s, 'c')).join('') || '<div class="hint">Empty.</div>'}</div></div>${this.details(sel, this.selSide)}</div></div></div>`;
  }

  // ------------------------------------------------------------------ crafting
  private recipeList(station: string, limit = 999) {
    const g = this.g;
    const list = RECIPES.filter(r => r.station === 'hand' || r.station === station).map(r => ({ r, c: g.canCraft(r, g.station) }));
    list.sort((a, b) => Number(b.c.ok) - Number(a.c.ok));
    return list.slice(0, limit).map(({ r, c }) => `<div class="recipe ${c.ok ? '' : 'no'}" data-a="craft" data-id="${r.id}" title="${esc(c.why)}"><span class="ic">${item(r.out[0]).icon}</span><div style="flex:1"><div class="rn">${esc(item(r.out[0]).name)} ×${r.out[1]} <span class="hint">· ${r.station}${r.tools ? ' · ' + r.tools.join(', ') : ''}</span></div><div class="req">${fmtCost(r.inp, g.player.inv)}${c.ok ? '' : ` — <span class="lack">${esc(c.why)}</span>`}</div></div></div>`).join('');
  }
  private craftPanel() {
    const st = this.g.station;
    return `<div class="col"><h3>${st === 'workbench' ? 'Workbench recipes' : 'Hand crafting'} <span class="hint">${st === 'hand' ? '— use a Workbench or Campfire for more' : ''}</span></h3><div class="scroll">${this.recipeList(st)}</div></div>
      <div class="col" style="max-width:380px"><h3>Inventory</h3>${this.weightBar()}<div class="scroll"><div class="ilist">${this.g.player.inv.stacks.map(s => this.itemRow(s, 'p')).join('')}</div></div></div>`;
  }

  // ------------------------------------------------------------------ health
  private healthPanel() {
    const p = this.g.player, s = p.stats;
    const meds = ['bandage', 'rag', 'disinfectant', 'suture_kit', 'splint'].filter(m => p.inv.count(m) > 0);
    const part = (id: PartId) => {
      const pt = s.parts[id]; const name = PARTS.find(x => x.id === id)!.name;
      const col = pt.hp > 70 ? 'var(--good)' : pt.hp > 35 ? 'var(--warn)' : 'var(--bad)';
      return `<div class="part"><div class="pn"><span>${name}</span><span style="color:${col}">${Math.round(pt.hp)}</span></div><div class="hpb"><div style="width:${pt.hp}%;background:${col}"></div></div>
        ${pt.wounds.map((w, i) => `<div class="wound"><span class="wt2">${w.type === 'bite' ? '🦷' : w.type === 'fracture' ? '🦴' : w.type === 'laceration' ? '🔪' : '〰️'} ${w.type}${w.bleed > 0.05 && !w.bandaged ? ' · <span class="lack">bleeding</span>' : ''}${w.bandaged ? (w.dirty ? ' · <span class="lack">dirty bandage</span>' : ' · bandaged') : ''}${w.infection > 0.05 ? ` · <span class="lack">infected ${Math.round(w.infection * 100)}%</span>` : ''}${w.stitched ? ' · stitched' : ''}${w.splinted ? ' · splinted' : ''} <span class="hint">${Math.round((1 - w.heal) * 100)}% healed</span></span>
        ${meds.map(m => `<button class="btn small" data-a="treat" data-part="${id}" data-w="${i}" data-item="${m}" title="${esc(item(m).name)}">${item(m).icon}</button>`).join('')}</div>`).join('') || '<div class="hint">No wounds</div>'}</div>`;
    };
    const knox = s.knoxInfected ? (s.knox < 0.15 ? '' : `<div class="note bad" style="animation:none;margin:6px 0">You feel feverish and weak. The bite… it's spreading. ${s.knox > 0.6 ? 'You do not have long.' : ''} Only the Antiviral Serum can stop it.</div>`) : '';
    return `<div class="col"><h3>Body</h3><div class="parts">${PARTS.map(x => part(x.id)).join('')}</div>${knox}</div>
      <div class="col" style="max-width:360px"><h3>Condition</h3><div class="statgrid">
      <div class="k">Overall health</div><div class="v">${Math.round(s.health())}%</div><div class="k">Blood loss</div><div class="v">${Math.round(s.bloodLoss)}%</div>
      <div class="k">Pain</div><div class="v">${Math.round(s.pain)}</div><div class="k">Panic</div><div class="v">${Math.round(s.panic)}</div>
      <div class="k">Body temperature</div><div class="v">${s.bodyTemp.toFixed(1)}°C</div><div class="k">Wetness</div><div class="v">${Math.round(s.wetness * 100)}%</div>
      <div class="k">Sickness</div><div class="v">${Math.round(s.sickness)}</div><div class="k">Unhappiness</div><div class="v">${Math.round(s.unhappy)}</div>
      <div class="k">Insulation</div><div class="v">${Math.round(p.insulation() * 100)}</div><div class="k">Carry</div><div class="v">${p.weight().toFixed(1)}/${p.capacity().toFixed(0)}kg</div>
      <div class="k">Painkillers</div><div class="v">${s.painkillerHrs > 0 ? s.painkillerHrs.toFixed(1) + 'h' : '—'}</div><div class="k">Antibiotics</div><div class="v">${s.antibioticHrs > 0 ? s.antibioticHrs.toFixed(1) + 'h' : '—'}</div>
      </div><h3>Medicine</h3><div class="ilist">${p.inv.stacks.filter(x => item(x.id).cat === 'medical').map(x => this.itemRow(x, 'p')).join('') || '<div class="hint">No medical supplies.</div>'}</div>
      <p class="hint">Bandage bleeding wounds immediately. Rags stop bleeding but get dirty — disinfect and re-bandage. Fractures need a splint to heal. Q bandages your worst wound.</p></div>`;
  }

  private skillsPanel() {
    const p = this.g.player;
    const groups = [...new Set(SKILLS.map(s => s.group))];
    return `<div class="col"><div class="scroll">${groups.map(gr => `<h3>${gr}</h3>${SKILLS.filter(s => s.group === gr).map(s => {
      const lvl = p.skills.level(s.id), prog = p.skills.progress(s.id), raw = p.skills.rawLevel(s.id);
      const boosted = (p.skills.bookUntil[s.id] ?? 0) > this.g.gameMin;
      return `<div class="skill"><span>${s.icon}</span><span>${s.name}${boosted ? ' <span class="tag" style="color:var(--blue)">×3</span>' : ''}</span><div class="pips">${Array.from({ length: 10 }, (_, i) => `<div class="pip ${i < lvl ? 'on' : ''}">${i === raw && lvl < 10 ? `<div class="fill" style="width:${prog * 100}%"></div>` : ''}</div>`).join('')}</div><span style="text-align:right;font-family:var(--head)">${lvl}</span></div>`;
    }).join('')}`).join('')}</div></div>
    <div class="col" style="max-width:340px"><h3>Traits</h3>${[...p.traits].map(t => { const d = TRAITS.find(x => x.id === t)!; return `<div class="trait on" style="margin-bottom:4px"><div><div>${d.name}</div><div class="td">${esc(d.desc)}</div></div></div>`; }).join('') || '<div class="hint">None</div>'}
    <h3>How skills grow</h3><p class="hint" style="line-height:1.6">Skills level by doing: swing blunt weapons, stitch wounds, build walls, place machines. Skill books triple XP gain for a day. Engineering speeds up every machine in your factory by 4% per level. Carpentry makes your walls tougher.</p></div>`;
  }
  private journalPanel() {
    const g = this.g;
    return `<div class="col"><h3>Objectives</h3><div class="scroll">${OBJECTIVES.map((o, i) => `<div class="ms ${i < g.objective ? 'done' : i === g.objective ? 'cur' : ''}"><div class="mn"><span>${i < g.objective ? '✔ ' : ''}${esc(o.title)}</span></div><div class="hint">${esc(o.desc)}</div></div>`).join('')}</div></div>
      <div class="col" style="max-width:380px"><h3>Record</h3><div class="statgrid"><div class="k">Days survived</div><div class="v">${Math.floor(g.player.stats.hoursSurvived / 24)}</div><div class="k">Zombies killed</div><div class="v">${g.player.kills}</div><div class="k">Items crafted</div><div class="v">${g.stats.crafted}</div><div class="k">Structures built</div><div class="v">${g.stats.built}</div><div class="k">Milestones</div><div class="v">${g.factory.tier} / ${MILESTONES.length}</div><div class="k">Machines</div><div class="v">${g.factory.machines.filter(m => !m.type.startsWith('belt')).length}</div></div>
      <h3>Notes</h3><p class="hint" style="line-height:1.6">"Day 22. The radio says the Knox virus spreads by bite. Scratches sometimes. If you get bitten, that's it — unless someone, somewhere, builds a lab and finds a cure. The power plant on the ridge has been dark for weeks. Somebody needs to get the grid back."</p></div>`;
  }

  // ------------------------------------------------------------------ build menu
  private buildPanel() {
    const g = this.g; const p = g.player;
    const cats: [string, string][] = [['structure', 'Structures'], ['station', 'Survival'], ['production', 'Production'], ['logistics', 'Logistics'], ['power', 'Power'], ['defense', 'Defence'], ['special', 'Special']];
    const entries: { id: string; name: string; icon: string; desc: string; cost: Record<string, number>; tier: number; cat: string; skill?: [SkillId, number] }[] = [];
    for (const d of Object.values(PIECES)) entries.push({ id: d.id, name: d.name, icon: d.icon, desc: d.desc, cost: d.cost, tier: d.tier, cat: d.kind === 'station' ? 'station' : 'structure', skill: d.skill });
    for (const d of Object.values(MACHINES)) entries.push({ id: d.type, name: d.name, icon: d.icon, desc: d.desc + (d.power > 0 ? ` (${d.power} MW)` : d.power < 0 ? ` (+${-d.power} MW)` : ''), cost: d.cost, tier: d.tier, cat: d.type === 'spike_trap' ? 'defense' : d.cat });
    const list = entries.filter(e => e.cat === this.buildCat);
    return `<div class="panel window"><div class="top">${cats.map(([k, l]) => `<button class="tab ${k === this.buildCat ? 'on' : ''}" data-a="bcat" data-id="${k}">${l}</button>`).join('')}<button class="close" data-a="close">✕</button></div>
      <div class="body" style="flex-direction:column"><div class="hint" style="margin-bottom:8px">Tier ${g.factory.tier} unlocked${g.settings.creative ? ' · CREATIVE (free)' : ''}. Complete milestones at the Survivor Outpost to unlock more. ${g.factory.hub ? '' : '<b style="color:var(--accent2)">Build the Survivor Outpost (Special) to start your tech tree.</b>'}</div>
      <div class="scroll"><div class="bgrid">${list.map(e => {
        const unlocked = g.factory.unlocked.has(e.id) || g.settings.creative;
        const afford = g.settings.creative || p.inv.hasAll(e.cost);
        return `<div class="bcard ${unlocked ? '' : 'lock'}" data-a="${unlocked ? 'pick' : ''}" data-id="${e.id}" title="${esc(e.desc)}"><span class="tier">${unlocked ? '' : '🔒 '}T${e.tier}</span><div class="bi">${e.icon}</div><div class="bn">${esc(e.name)}</div><div class="bc">${unlocked ? fmtCost(e.cost, p.inv) : esc(MILESTONES.find(m => m.unlocks.includes(e.id))?.name ? 'Unlocks: ' + MILESTONES.find(m => m.unlocks.includes(e.id))!.name : '')}</div>${e.skill ? `<div class="bc">Carpentry ${e.skill[1]}</div>` : ''}${unlocked && !afford ? '<div class="bc lack">missing materials</div>' : ''}</div>`;
      }).join('')}</div></div></div></div>`;
  }

  // ------------------------------------------------------------------ machine panels
  private machinePanel() {
    const g = this.g; const m = g.openMachine; if (!m) return '';
    const p = g.player; const net = g.factory.netOf(m);
    const recipes = g.factory.recipesFor(m.type);
    const r = g.factory.recipe(m);
    const power = m.def.power !== 0 || m.type === 'power_pole' || m.type === 'battery' ? `<div class="statgrid" style="margin-bottom:8px"><div class="k">Grid</div><div class="v">${net ? `${net.supply.toFixed(0)} MW supply / ${net.demand.toFixed(0)} MW demand` : '<span class="lack">Not connected — place a Power Pole within 10 m</span>'}</div>${net?.batteryCap ? `<div class="k">Batteries</div><div class="v">${net.battery.toFixed(0)} / ${net.batteryCap} MJ</div>` : ''}${net?.tripped ? `<div class="k">Fuse</div><div class="v"><button class="btn small primary" data-a="fuse">Reset fuse</button></div>` : ''}<div class="k">This machine</div><div class="v">${m.def.power > 0 ? m.def.power + ' MW' : m.def.power < 0 ? 'produces ' + (-m.def.power) + ' MW' : '—'} · ${m.powered ? '<span class="have">powered</span>' : m.def.power > 0 ? '<span class="lack">unpowered</span>' : ''}</div></div>` : '';
    let body = '';
    if (recipes.length) {
      body += `<h3>Recipe</h3><div class="ilist">${recipes.map(rc => `<div class="recipe ${m.recipe === rc.id ? 'sel' : ''}" style="${m.recipe === rc.id ? 'border-color:var(--accent)' : ''}" data-a="mrecipe" data-id="${rc.id}"><span class="ic">${item(rc.out[0]).icon}</span><div style="flex:1"><div class="rn">${esc(item(rc.out[0]).name)} ×${rc.fluidOut ? rc.fluidOut[1] : rc.out[1]} <span class="hint">· ${rc.time}s · ${((rc.fluidOut ? rc.fluidOut[1] : rc.out[1]) * 60 / rc.time).toFixed(0)}/min</span></div><div class="req">${[...Object.entries(rc.inp), ...(rc.fluidIn ? [rc.fluidIn] : [])].map(([k, n]) => `${item(k).icon} ${n} ${esc(item(k).name)}`).join(' + ') || 'no inputs'}</div></div></div>`).join('')}</div>`;
    }
    if (r || m.type.startsWith('miner')) {
      const ins = r ? Object.keys(r.inp) : [];
      body += `<h3>Production ${m.running ? '<span class="have">running</span>' : '<span class="hint">idle</span>'}</h3><div class="meter" style="height:8px"><div style="width:${m.progress * 100}%;background:var(--accent)"></div></div>
        <div class="statgrid" style="margin-top:8px">${ins.map(k => `<div class="k">${item(k).icon} ${esc(item(k).name)} (in)</div><div class="v">${m.inBuf[k] ?? 0} <button class="btn small" data-a="feed" data-id="${k}" ${p.inv.count(k) ? '' : 'disabled'}>Insert (${p.inv.count(k)})</button></div>`).join('')}
        ${Object.keys(m.outBuf).filter(k => m.outBuf[k] > 0).map(k => `<div class="k">${item(k).icon} ${esc(item(k).name)} (out)</div><div class="v">${m.outBuf[k]}</div>`).join('')}</div>
        <div class="btnrow"><button class="btn primary" data-a="takeout">Take output</button></div>`;
      if (m.node) body += `<p class="hint">Deposit: ${esc(item(m.node.type).name)} (${m.node.purity > 1 ? 'pure ×2' : 'normal'}). Output exits the front port — run a belt from there.</p>`;
    }
    if (m.inv) {
      const fuelIds = m.type === 'coal_gen' ? ['coal', 'gas_can'] : m.type === 'turret' ? ['ammo_9mm', 'ammo_rifle', 'ammo_shells'] : m.type === 'biomass' ? ['log', 'plank', 'leaves'] : [];
      body += `<h3>${m.type === 'storage' ? 'Contents' : m.type === 'turret' ? 'Ammunition' : 'Fuel'} ${m.def.power < 0 ? `<span class="hint">· burning ${Math.round(m.fuel)} MJ left</span>` : ''}</h3>
        ${fuelIds.length ? `<div class="btnrow">${fuelIds.map(id => `<button class="btn small" data-a="feedinv" data-id="${id}" ${p.inv.count(id) ? '' : 'disabled'}>${item(id).icon} Add ${esc(item(id).name)} (${p.inv.count(id)})</button>`).join('')}</div>` : ''}
        <div class="ilist" style="margin-top:6px">${m.inv.stacks.map(s => this.itemRow(s, 'm')).join('') || '<div class="hint">Empty</div>'}</div>${m.type === 'storage' ? '<div class="btnrow"><button class="btn" data-a="openstore">Open as container</button></div>' : ''}`;
    }
    if (m.fnets && m.fnets.length) body += `<h3>Pipe networks</h3>${m.fnets.map(n => `<div class="hint">${n.fluid ? esc(item(n.fluid).name) : 'empty'}: ${n.amount.toFixed(0)} / ${n.cap}</div>`).join('')}`;
    else if (['water_pump', 'oil_pump', 'refinery', 'fuel_gen', 'fluid_tank', 'packager'].includes(m.type)) body += `<p class="hint lack">Not connected to any pipe. Lay pipes (B → Logistics) touching this building.</p>`;
    if (m.type.startsWith('belt')) body += `<p class="hint">${m.items.length} items on this segment: ${m.items.map(i => item(i.id).icon).join(' ')}</p>`;
    if (m.type === 'radio_tower') body += `<p class="hint">${m.powered ? 'Broadcasting. A supply drop arrives near the tower each day at noon.' : 'Needs 20 MW to broadcast.'}</p>`;
    if (m.type === 'uplink') body += `<p class="hint">${m.running ? 'The old town grid is live. Street lights and houses glow at night.' : 'Needs 40 MW to energise the town grid.'}</p>`;
    if (m.type === 'power_pole') body += `<p class="hint">Poles link to other poles within 16 m and to machines within 10 m. Cables are drawn automatically.</p>`;
    return `<div class="panel window" style="width:min(760px,96vw)"><div class="top"><h2 style="padding:0 8px">${m.def.icon} ${esc(m.def.name)}</h2><span class="hint" style="margin-left:10px">HP ${Math.round(m.hp)} / ${m.def.hp}</span><button class="close" data-a="close">✕</button></div><div class="body" style="flex-direction:column"><div class="scroll">${power}<p class="hint">${esc(m.def.desc)}</p>${body}</div></div></div>`;
  }
  private hubPanel() {
    const g = this.g; const p = g.player;
    return `<div class="panel window" style="width:min(860px,96vw)"><div class="top"><h2 style="padding:0 8px">🏚️ Survivor Outpost — Milestones</h2><button class="close" data-a="close">✕</button></div><div class="body" style="flex-direction:column"><div class="scroll">
      <p class="hint">Deliver parts by hand or with a conveyor into the Outpost's rear port. Each milestone unlocks new buildings. Base heat: <b>${(g.factory.heat + g.combatHeat).toFixed(0)}</b> — running machines make noise that draws hordes.</p>
      ${MILESTONES.map((ms, i) => {
        const done = i < g.factory.tier, cur = i === g.factory.tier;
        return `<div class="ms ${done ? 'done' : cur ? 'cur' : ''}"><div class="mn"><span>${done ? '✔ ' : cur ? '▶ ' : '🔒 '}Tier ${i + 1}: ${esc(ms.name)}</span><span class="hint">${ms.unlocks.map(u => (MACHINES[u] ?? PIECES[u])?.icon ?? '').join(' ')}</span></div><div class="hint">${esc(ms.desc)}</div>
        ${cur ? Object.entries(ms.cost).map(([k, n]) => { const have = g.factory.milestoneProgress[k] ?? 0; return `<div class="req"><span style="width:200px">${item(k).icon} ${esc(item(k).name)}</span><div class="bar"><div style="width:${have / n * 100}%"></div></div><span>${have} / ${n}</span><button class="btn small" data-a="deposit" data-id="${k}" ${p.inv.count(k) && have < n ? '' : 'disabled'}>Deliver (${p.inv.count(k)})</button></div>`; }).join('') : ''}
        ${!done ? `<div class="hint" style="margin-top:4px">Unlocks: ${ms.unlocks.map(u => esc((MACHINES[u] ?? PIECES[u])?.name ?? u)).join(', ') || 'Victory'}</div>` : ''}</div>`;
      }).join('')}</div></div></div>`;
  }
  private campfirePanel() {
    const g = this.g; const pc = g.openPiece; if (!pc?.fire) return '';
    const p = g.player; const f = pc.fire;
    const fuels = p.inv.stacks.filter(s => item(s.id).fuel && s.id !== 'coal' && s.id !== 'gas_can');
    return `<div class="panel window" style="width:min(820px,96vw)"><div class="top"><h2 style="padding:0 8px">🔥 Campfire</h2><button class="close" data-a="close">✕</button></div><div class="body">
      <div class="col" style="max-width:300px"><h3>Fire</h3><div class="statgrid"><div class="k">Status</div><div class="v">${f.lit ? '<span class="have">burning</span>' : 'cold'}</div><div class="k">Fuel</div><div class="v">${Math.round(f.fuel)} min</div></div>
      <div class="btnrow"><button class="btn primary" data-a="light" ${f.lit ? 'disabled' : ''}>Light (lighter)</button></div><h3>Add fuel</h3><div class="btnrow">${fuels.map(s => `<button class="btn small" data-a="fuel" data-id="${s.id}">${item(s.id).icon} ${esc(item(s.id).name)} (${p.inv.count(s.id)})</button>`).join('') || '<span class="hint">No fuel: logs, planks or biomass.</span>'}</div>
      <p class="hint">Fires keep you warm and dry you off, and let you cook food and boil dirty water. Rain may put it out.</p></div>
      <div class="col"><h3>Cook</h3><div class="scroll">${RECIPES.filter(r => r.station === 'campfire').map(r => { const c = g.canCraft(r, 'campfire'); return `<div class="recipe ${c.ok ? '' : 'no'}" data-a="craft" data-id="${r.id}"><span class="ic">${item(r.out[0]).icon}</span><div style="flex:1"><div class="rn">${esc(item(r.out[0]).name)}</div><div class="req">${fmtCost(r.inp, p.inv)}${c.ok ? '' : ` — <span class="lack">${esc(c.why)}</span>`}</div></div></div>`; }).join('')}</div></div></div></div>`;
  }

  // ------------------------------------------------------------------ clicks
  private lastClick = { uid: -1, t: 0 };
  private onClick(e: MouseEvent) {
    const t = (e.target as HTMLElement).closest('[data-a]') as HTMLElement | null; if (!t) return;
    const a = t.dataset.a!; const g = this.g; const p = g.player;
    if (a) audio.play('ui_click');
    const uid = t.dataset.uid ? Number(t.dataset.uid) : -1;
    switch (a) {
      case 'continue': audio.init(); g.continueGame(); break;
      case 'new': audio.init(); document.getElementById('mainmenu')?.remove(); g.state = 'cc'; this.charCreation(); break;
      case 'settings': this.settingsModal(); break;
      case 'controls': this.controlsModal(); break;
      case 'close-modal': this.closeModal(); break;
      case 'rebind': { const def = t.dataset.id!; t.textContent = '…press a key'; g.input.capture = code => { const k = { ...(g.settings.keys ?? {}) }; k[def] = code; g.settings.keys = k; g.input.setBindings(k); g.saveSettings(); t.textContent = g.input.keyName(def); }; break; }
      case 'resetkeys': g.settings.keys = {}; g.input.setBindings({}); g.saveSettings(); this.settingsModal(); break;
      case 'save-settings': case 'reload': {
        const s = g.settings; const q = (id: string) => document.getElementById(id) as HTMLInputElement;
        s.quality = q('s-q').value as any; s.style = (document.getElementById('s-style') as HTMLSelectElement).value as any; s.fov = Number(q('s-fov').value); s.sens = Number(q('s-sens').value); s.master = Number(q('s-mv').value); s.music = Number(q('s-mu').value); s.dayLen = Number(q('s-day').value); s.creative = q('s-cr').checked;
        g.input.sensitivity = 0.0022 * s.sens; audio.setVolumes({ master: s.master, music: s.music }); g.saveSettings();
        if (a === 'reload') { g.save(true); location.reload(); } else this.closeModal();
        break;
      }
      case 'resume': g.pause(false); break;
      case 'save': g.save(false); break;
      case 'quit': g.save(true); location.reload(); break;
      case 'restart': location.reload(); break;
      case 'keep-playing': (t.closest('.endscreen') as HTMLElement).remove(); g.state = 'play'; g.input.lock(); break;
      case 'close': this.closeAll(); break;
      case 'tab': this.open = t.dataset.id as PanelName; this.sel = null; this.renderPanel(); break;
      case 'sel': {
        const side = t.dataset.side as 'p' | 'c' | 'm';
        const now = performance.now();
        if (this.lastClick.uid === uid && now - this.lastClick.t < 350) { this.onRightClick(t); this.lastClick.uid = -1; return; }
        this.lastClick = { uid, t: now };
        this.sel = uid; this.selSide = side === 'm' ? 'c' : side; this.renderPanel(); break;
      }
      case 'use': g.useItem(uid); this.refresh(); break;
      case 'drop': g.dropStack(uid, Number(t.dataset.n)); this.sel = null; break;
      case 'hot': { const i = Number(t.dataset.i); for (let k = 0; k < 8; k++) if (p.hotbar[k] === uid) p.hotbar[k] = null; p.hotbar[i] = uid; this.refresh(); break; }
      case 'xfer': this.transfer(t.dataset.side as 'p' | 'c', uid, Number(t.dataset.n)); break;
      case 'takeall': { const c = g.openContainer; if (!c) break; for (const s of [...c.inv.stacks]) g.transfer(c.inv, p.inv, s.uid, s.n); break; }
      case 'sort': p.inv.sort(); this.refresh(); break;
      case 'unwear': g.takeOff(t.dataset.slot as any); break;
      case 'craft': { const r = RECIPES.find(x => x.id === t.dataset.id)!; g.craft(r, g.station); break; }
      case 'treat': g.treat(t.dataset.part as PartId, Number(t.dataset.w), t.dataset.item!); break;
      case 'bcat': this.buildCat = t.dataset.id!; this.renderPanel(); break;
      case 'pick': g.toggleBuild(true, t.dataset.id!); break;
      case 'mrecipe': if (g.openMachine) g.setRecipe(g.openMachine, t.dataset.id!); break;
      case 'takeout': if (g.openMachine) g.takeOutput(g.openMachine); this.refresh(); break;
      case 'feed': if (g.openMachine) g.feedMachine(g.openMachine, t.dataset.id!, 999); break;
      case 'feedinv': if (g.openMachine) g.feedMachine(g.openMachine, t.dataset.id!, t.dataset.id === 'log' ? 5 : 50); break;
      case 'openstore': if (g.openMachine?.inv) { const m = g.openMachine; this.open = null; this.panelEl?.remove(); this.panelEl = null; g.openLoot(m.inv!, m.def.name, m); } break;
      case 'fuse': if (g.openMachine) g.factory.resetFuse(g.openMachine); this.refresh(); break;
      case 'deposit': g.depositHub(t.dataset.id!); break;
      case 'light': if (g.openPiece) g.campfireLight(g.openPiece); break;
      case 'fuel': if (g.openPiece) g.campfireFuel(g.openPiece, t.dataset.id!); break;
    }
  }
  private onRightClick(t: HTMLElement) {
    const uid = Number(t.dataset.uid); const side = t.dataset.side as 'p' | 'c' | 'm';
    const g = this.g;
    if (side === 'm' && g.openMachine?.inv) { g.transfer(g.openMachine.inv, g.player.inv, uid, 999); return; }
    if (g.openContainer) { const s = (side === 'p' ? g.player.inv : g.openContainer.inv).byUid(uid); if (s) this.transfer(side as 'p' | 'c', uid, s.n); }
    else if (side === 'p') g.useItem(uid);
  }
  private transfer(side: 'p' | 'c', uid: number, n: number) {
    const g = this.g; const c = g.openContainer; if (!c) return;
    if (side === 'p') g.transfer(g.player.inv, c.inv, uid, n); else g.transfer(c.inv, g.player.inv, uid, n);
    this.sel = null;
  }

  // ------------------------------------------------------------------ maps
  private ensureBaseMap() {
    if (this.baseMap) return this.baseMap;
    const N = 512; const c = document.createElement('canvas'); c.width = c.height = N;
    const ctx = c.getContext('2d')!; const img = ctx.createImageData(N, N);
    const w = this.g.world; const t = w.terrain;
    for (let j = 0; j < N; j++) for (let i = 0; i < N; i++) {
      const x = -HALF_WORLD + (i + 0.5) / N * WORLD_SIZE, z = -HALF_WORLD + (j + 0.5) / N * WORLD_SIZE;
      const h = t.heightAt(x, z); const sp = t.splatAt(x, z); const f = t.forestDensity(x, z); const sl = t.slopeAt(x, z);
      let r = 92, g = 108, b = 70;
      r -= f * 40; g -= f * 30; b -= f * 25;
      r += h * 0.8; g += h * 0.7; b += h * 0.6;
      if (sl > 0.3) { r = 120; g = 116; b = 108; }
      if (sp[1] > 120) { r = 128; g = 110; b = 84; }
      if (sp[0] > 100) { r = 54; g = 54; b = 58; }
      if (Math.hypot(x - LAKE.x, z - LAKE.z) < LAKE.r && h < LAKE.level) { r = 38; g = 62; b = 78; }
      const o = (j * N + i) * 4; img.data[o] = r; img.data[o + 1] = g; img.data[o + 2] = b; img.data[o + 3] = 255;
    }
    ctx.putImageData(img, 0, 0);
    ctx.fillStyle = '#b8a890';
    const s = N / WORLD_SIZE;
    for (const bd of w.town.buildings) { ctx.fillRect((bd.x - bd.w / 2 + HALF_WORLD) * s, (bd.z - bd.d / 2 + HALF_WORLD) * s, bd.w * s, bd.d * s); }
    const icon: Record<string, string> = { iron_ore: '#c0583a', copper_ore: '#e08a40', coal: '#202020', stone: '#e0dccf', sulfur: '#e8d040', f_crude: '#6a2a8a' };
    for (const o of w.ores) { ctx.fillStyle = icon[o.type]; ctx.beginPath(); ctx.arc((o.x + HALF_WORLD) * s, (o.z + HALF_WORLD) * s, 4, 0, 7); ctx.fill(); ctx.strokeStyle = '#000'; ctx.stroke(); }
    this.baseMap = c; return c;
  }
  private drawMap(cv: HTMLCanvasElement) {
    const ctx = cv.getContext('2d')!; const base = this.ensureBaseMap(); const N = cv.width;
    ctx.drawImage(base, 0, 0, N, N);
    // fog of war
    ctx.fillStyle = 'rgba(8,9,10,0.92)';
    const cs = N / 64;
    for (let j = 0; j < 64; j++) for (let i = 0; i < 64; i++) if (!this.g.explored[j * 64 + i]) ctx.fillRect(i * cs, j * cs, cs + 1, cs + 1);
    const s = N / WORLD_SIZE; const g = this.g;
    for (const m of g.factory.machines) { if (m.type.startsWith('belt')) ctx.fillStyle = '#e0a040'; else ctx.fillStyle = '#5ab0ff'; const sz = m.def.size[0] * 2 * s; ctx.fillRect((m.cx + HALF_WORLD) * s - sz / 2, (m.cz + HALF_WORLD) * s - sz / 2, sz, sz); }
    ctx.fillStyle = '#8ad'; for (const pc of g.base.pieces) ctx.fillRect((pc.cx + HALF_WORLD) * s - 2, (pc.cz + HALF_WORLD) * s - 2, 4, 4);
    if (g.factory.hub) { ctx.font = '22px serif'; ctx.fillText('🏚️', (g.factory.hub.cx + HALF_WORLD) * s - 11, (g.factory.hub.cz + HALF_WORLD) * s + 8); }
    const p = g.player;
    ctx.save(); ctx.translate((p.pos.x + HALF_WORLD) * s, (p.pos.z + HALF_WORLD) * s); ctx.rotate(-p.yaw + Math.PI);
    ctx.fillStyle = '#ffcf7a'; ctx.beginPath(); ctx.moveTo(0, 12); ctx.lineTo(7, -7); ctx.lineTo(-7, -7); ctx.closePath(); ctx.fill(); ctx.strokeStyle = '#000'; ctx.stroke(); ctx.restore();
    ctx.fillStyle = '#ddd'; ctx.font = '14px Barlow Condensed, sans-serif'; ctx.fillText('N ↑', 12, 22);
  }
  private drawMinimap() {
    const cv = this.els.minimap as HTMLCanvasElement; const ctx = cv.getContext('2d')!; const base = this.ensureBaseMap();
    const p = this.g.player; const N = cv.width; const scale = 512 / WORLD_SIZE; const zoom = 2.2;
    ctx.save(); ctx.fillStyle = '#0a0b0c'; ctx.fillRect(0, 0, N, N);
    ctx.translate(N / 2, N / 2); ctx.rotate(p.yaw); ctx.scale(zoom, zoom);
    ctx.drawImage(base, -(p.pos.x + HALF_WORLD) * scale, -(p.pos.z + HALF_WORLD) * scale);
    const g = this.g;
    for (const m of g.factory.machines) { ctx.fillStyle = m.type.startsWith('belt') ? '#e0a040' : '#5ab0ff'; const sz = m.def.size[0] * 2 * scale; ctx.fillRect((m.cx - p.pos.x) * scale - sz / 2, (m.cz - p.pos.z) * scale - sz / 2, sz, sz); }
    ctx.fillStyle = '#8ad'; for (const pc of g.base.pieces) ctx.fillRect((pc.cx - p.pos.x) * scale - 0.8, (pc.cz - p.pos.z) * scale - 0.8, 1.6, 1.6);
    ctx.fillStyle = '#e04030';
    for (const z of g.zombies.list) { if (z.state === 'dead' || z.frozen || z.state !== 'chase') continue; const dx = (z.pos.x - p.pos.x) * scale, dz = (z.pos.z - p.pos.z) * scale; if (dx * dx + dz * dz < 1600) ctx.fillRect(dx - 0.7, dz - 0.7, 1.4, 1.4); }
    ctx.restore();
    ctx.fillStyle = '#ffcf7a'; ctx.beginPath(); ctx.moveTo(N / 2, N / 2 - 7); ctx.lineTo(N / 2 + 5, N / 2 + 5); ctx.lineTo(N / 2 - 5, N / 2 + 5); ctx.closePath(); ctx.fill();
  }
}
export { THREE };
