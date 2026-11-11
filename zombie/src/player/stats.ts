// Project-Zomboid-style survival simulation: needs, body parts & wounds, infection, temperature, moodles.
import { clamp } from '../core/util';

export type PartId = 'head' | 'torso' | 'armL' | 'armR' | 'legL' | 'legR';
export const PARTS: { id: PartId; name: string; weight: number }[] = [
  { id: 'head', name: 'Head', weight: 0.08 }, { id: 'torso', name: 'Torso', weight: 0.3 },
  { id: 'armL', name: 'Left Arm', weight: 0.17 }, { id: 'armR', name: 'Right Arm', weight: 0.17 },
  { id: 'legL', name: 'Left Leg', weight: 0.14 }, { id: 'legR', name: 'Right Leg', weight: 0.14 },
];
export type WoundType = 'scratch' | 'laceration' | 'bite' | 'bullet' | 'fracture' | 'burn';
export interface Wound { type: WoundType; heal: number; bleed: number; bandaged: boolean; dirty: boolean; infection: number; stitched: boolean; splinted: boolean; bandageAge: number }
export interface Part { hp: number; wounds: Wound[] }
export interface Moodle { id: string; icon: string; level: number; bad: boolean; label: string; desc: string }

const HEAL_HOURS: Record<WoundType, number> = { scratch: 20, laceration: 60, bite: 90, bullet: 80, fracture: 140, burn: 50 };
const BLEED: Record<WoundType, number> = { scratch: 0.6, laceration: 1.8, bite: 2.4, bullet: 2.2, fracture: 0, burn: 0 };

export interface StatCtx {
  moving: boolean; sprinting: boolean; exertion: number; ambientTemp: number; insulation: number; nearFire: boolean;
  indoors: boolean; raining: number; rainProtect: number; sleeping: boolean; fitness: number; strength: number;
  traits: Set<string>; weightRatio: number; zombiesNear: number; zombiesSeen: number; inLight: boolean; bloodyHands: boolean;
}

export class Stats {
  hunger = 85; thirst = 85; fatigue = 10; stamina = 100; maxStamina = 100;
  bodyTemp = 37; wetness = 0; pain = 0; panic = 0; unhappy = 10; sickness = 0; bloodLoss = 0;
  parts: Record<PartId, Part> = { head: { hp: 100, wounds: [] }, torso: { hp: 100, wounds: [] }, armL: { hp: 100, wounds: [] }, armR: { hp: 100, wounds: [] }, legL: { hp: 100, wounds: [] }, legR: { hp: 100, wounds: [] } };
  knox = 0; knoxInfected = false; // zombification progress 0..1
  painkillerHrs = 0; antibioticHrs = 0; serumHrs = 0;
  foodPoison = 0;
  dead = false; deathCause = '';
  hoursSurvived = 0;

  health() {
    let h = 0; for (const p of PARTS) h += this.parts[p.id].hp * p.weight;
    h -= this.bloodLoss * 0.6 + this.sickness * 0.25;
    if (this.knoxInfected) h -= Math.max(0, this.knox - 0.6) * 150;
    return clamp(h, 0, 100);
  }
  bleeding() { let b = 0; for (const p of PARTS) for (const w of this.parts[p.id].wounds) if (!w.bandaged) b += w.bleed; return b; }
  hasFracture(part: PartId) { return this.parts[part].wounds.some(w => w.type === 'fracture' && !w.splinted); }
  woundInfection() { let m = 0; for (const p of PARTS) for (const w of this.parts[p.id].wounds) m = Math.max(m, w.infection); return m; }

  addWound(part: PartId, type: WoundType, damage: number) {
    const w: Wound = { type, heal: 1, bleed: BLEED[type] * (0.7 + Math.random() * 0.6), bandaged: false, dirty: false, infection: 0, stitched: false, splinted: false, bandageAge: 0 };
    this.parts[part].wounds.push(w);
    this.parts[part].hp = Math.max(0, this.parts[part].hp - damage);
    this.pain = Math.min(100, this.pain + damage * 1.5);
    return w;
  }

  /** dtH: game hours elapsed. returns notable events */
  update(dtReal: number, dtH: number, c: StatCtx): string[] {
    const ev: string[] = [];
    if (this.dead) return ev;
    const tr = c.traits;
    this.hoursSurvived += dtH;
    const sleepMul = c.sleeping ? 0.4 : 1;
    // needs (per game hour)
    this.hunger -= dtH * 3.2 * sleepMul * (1 + c.exertion * 0.6);
    this.thirst -= dtH * 4.6 * sleepMul * (1 + c.exertion * 0.8) * (this.bodyTemp > 37.6 ? 1.5 : 1);
    if (c.sleeping) this.fatigue -= dtH * (tr.has('insomniac') ? 9 : 13);
    else this.fatigue += dtH * (tr.has('night_owl') ? 3.0 : 3.9) * (1 + c.exertion * 0.5);
    this.hunger = clamp(this.hunger, 0, 100); this.thirst = clamp(this.thirst, 0, 100); this.fatigue = clamp(this.fatigue, 0, 100);

    // stamina (real time)
    let maxS = 60 + c.fitness * 6; if (tr.has('obese')) maxS *= 0.8; if (tr.has('athletic')) maxS *= 1.1;
    maxS *= 1 - Math.max(0, this.fatigue - 60) / 100;
    this.maxStamina = maxS;
    const heavy = Math.max(0, c.weightRatio - 1);
    if (c.sprinting) this.stamina -= dtReal * (9 - c.fitness * 0.35) * (1 + heavy);
    else {
      let regen = (c.moving ? 5 : 11) * (1 + c.fitness * 0.08);
      if (tr.has('smoker')) regen *= 0.7;
      if (this.hunger < 15) regen *= 0.5;
      this.stamina += dtReal * regen * (heavy > 0 ? 0.5 : 1);
    }
    this.stamina = clamp(this.stamina, 0, this.maxStamina);

    // temperature
    let eq = c.ambientTemp + 18 + c.insulation * 14 + (c.nearFire ? 14 : 0) + (c.indoors ? 6 : 0) + c.exertion * 6 - this.wetness * 12;
    if (c.sleeping && c.indoors) eq += 4;
    const target = clamp(36.4 + (eq - 37) * 0.06, 33, 39.5);
    this.bodyTemp += (target - this.bodyTemp) * clamp(dtH * 0.6, 0, 1);
    if (this.knoxInfected && this.knox > 0.3) this.bodyTemp = Math.max(this.bodyTemp, 37.6 + this.knox * 1.4);
    if (this.woundInfection() > 0.5) this.bodyTemp = Math.max(this.bodyTemp, 38.0);
    // wetness
    if (c.raining > 0.05 && !c.indoors) this.wetness = clamp(this.wetness + dtH * c.raining * 1.6 * (1 - c.rainProtect), 0, 1);
    else this.wetness = clamp(this.wetness - dtH * (c.nearFire ? 1.5 : c.indoors ? 0.4 : 0.2), 0, 1);

    // panic
    if (c.zombiesSeen > 0) this.panic = Math.min(100, this.panic + dtReal * c.zombiesSeen * (tr.has('veteran') ? 1.2 : 3.5));
    else this.panic = Math.max(0, this.panic - dtReal * 4);
    if (tr.has('hemophobic') && (this.bleeding() > 0 || c.bloodyHands)) this.panic = Math.min(100, this.panic + dtReal * 2);
    // unhappiness
    if (this.wetness > 0.5) this.unhappy += dtH * 1.5;
    if (c.indoors && c.inLight) this.unhappy -= dtH * 0.5;
    this.unhappy = clamp(this.unhappy - dtH * 0.2, 0, 100);

    // wounds
    const healMul = (tr.has('fast_healer') ? 1.5 : tr.has('slow_healer') ? 0.6 : 1) * (this.hunger > 30 && this.thirst > 30 ? 1 : 0.3) * (c.sleeping ? 2 : 1);
    let painTarget = 0;
    for (const p of PARTS) {
      const part = this.parts[p.id];
      for (let i = part.wounds.length - 1; i >= 0; i--) {
        const w = part.wounds[i];
        // bleeding drains blood
        if (!w.bandaged && w.bleed > 0) { this.bloodLoss = Math.min(100, this.bloodLoss + w.bleed * dtH * 6); w.bleed = Math.max(0, w.bleed - dtH * 0.08); }
        if (w.bandaged) { w.bandageAge += dtH; if (w.bandageAge > 18) w.dirty = true; }
        // wound infection
        const exposed = !w.bandaged || w.dirty;
        if (exposed && w.type !== 'fracture' && Math.random() < dtH * (tr.has('prone_ill') ? 0.04 : 0.02)) w.infection = Math.max(w.infection, 0.05);
        if (w.infection > 0) {
          w.infection = clamp(w.infection + dtH * (this.antibioticHrs > 0 ? -0.08 : 0.02 * (tr.has('prone_ill') ? 1.6 : 1)), 0, 1);
          if (w.infection > 0.7) part.hp = Math.max(0, part.hp - dtH * 0.6);
        }
        // healing
        const canHeal = w.type !== 'fracture' || w.splinted;
        if (canHeal && w.infection < 0.3) w.heal -= dtH / HEAL_HOURS[w.type] * healMul * (w.stitched ? 3 : 1) * (w.bandaged ? 1.3 : 1);
        painTarget += (w.type === 'fracture' ? 45 : w.type === 'bite' ? 25 : w.type === 'laceration' ? 18 : 8) * Math.max(0.2, w.heal) + w.infection * 30;
        if (w.heal <= 0) { part.wounds.splice(i, 1); ev.push(`healed:${p.name}`); }
      }
      if (!part.wounds.length && this.hunger > 25 && this.thirst > 25) part.hp = Math.min(100, part.hp + dtH * 2.5 * healMul);
    }
    if (this.bleeding() < 0.05) this.bloodLoss = Math.max(0, this.bloodLoss - dtH * (this.hunger > 30 && this.thirst > 30 ? 2.5 : 0.5));
    if (this.painkillerHrs > 0) painTarget *= 0.25;
    this.pain += (clamp(painTarget, 0, 100) - this.pain) * clamp(dtH * 2, 0, 1);
    this.painkillerHrs = Math.max(0, this.painkillerHrs - dtH);
    this.antibioticHrs = Math.max(0, this.antibioticHrs - dtH);

    // food poisoning / sickness
    if (this.foodPoison > 0) {
      this.sickness = clamp(this.sickness + dtH * this.foodPoison * 6, 0, 100);
      this.foodPoison = Math.max(0, this.foodPoison - dtH * (this.antibioticHrs > 0 ? 0.3 : 0.08));
    } else this.sickness = Math.max(0, this.sickness - dtH * (this.antibioticHrs > 0 ? 6 : 2.5));

    // knox infection progression (~2.5 days to death)
    if (this.knoxInfected) {
      if (this.serumHrs > 0) { this.knox = Math.max(0, this.knox - dtH * 0.05); if (this.knox <= 0) { this.knoxInfected = false; ev.push('cured'); } }
      else this.knox = Math.min(1, this.knox + dtH / 60);
    }
    this.serumHrs = Math.max(0, this.serumHrs - dtH);

    // starvation / dehydration / hypothermia damage
    const dmgAll = (amt: number) => { for (const p of PARTS) this.parts[p.id].hp = Math.max(0, this.parts[p.id].hp - amt); };
    if (this.hunger <= 0) dmgAll(dtH * 2.2);
    if (this.thirst <= 0) dmgAll(dtH * 4);
    if (this.bodyTemp < 34.5) dmgAll(dtH * 3 * (34.5 - this.bodyTemp));
    if (this.bodyTemp > 39.8) dmgAll(dtH * 2);

    const h = this.health();
    if (h <= 0) {
      this.dead = true;
      this.deathCause = this.knoxInfected && this.knox > 0.9 ? 'Succumbed to the Knox infection.' : this.bloodLoss > 60 ? 'Bled out.' : this.thirst <= 0 ? 'Died of dehydration.' : this.hunger <= 0 ? 'Starved to death.' : this.bodyTemp < 34.5 ? 'Froze to death.' : this.sickness > 60 ? 'Died of sickness.' : 'Died of injuries.';
      ev.push('dead');
    }
    return ev;
  }

  // ---- derived modifiers
  speedMult(weightRatio: number) {
    let m = 1;
    const legs = (this.parts.legL.hp + this.parts.legR.hp) / 200;
    m *= 0.55 + 0.45 * legs;
    if (this.hasFracture('legL') || this.hasFracture('legR')) m *= 0.55;
    if (this.fatigue > 70) m *= 0.85;
    if (weightRatio > 1) m *= Math.max(0.45, 1 - (weightRatio - 1) * 0.8);
    if (this.pain > 60) m *= 0.85;
    return m;
  }
  meleeMult() {
    const arms = (this.parts.armL.hp + this.parts.armR.hp) / 200;
    let m = 0.5 + 0.5 * arms;
    if (this.hasFracture('armL') || this.hasFracture('armR')) m *= 0.5;
    if (this.fatigue > 70) m *= 0.8;
    if (this.stamina < 10) m *= 0.6;
    if (this.hunger < 15) m *= 0.85;
    return m;
  }
  aimSway() {
    return (this.panic / 100) * 0.03 + (this.pain / 100) * 0.02 + Math.max(0, this.fatigue - 60) / 100 * 0.03 + (this.stamina < 20 ? 0.015 : 0) + (100 - this.parts.armR.hp) / 100 * 0.02;
  }
  canSprint(weightRatio: number) { return this.stamina > 3 && weightRatio < 1.5 && !this.hasFracture('legL') && !this.hasFracture('legR'); }

  moodles(weightRatio: number): Moodle[] {
    const m: Moodle[] = [];
    const lvl = (v: number, a: number, b: number, c: number, d: number) => v >= d ? 4 : v >= c ? 3 : v >= b ? 2 : v >= a ? 1 : 0;
    const push = (id: string, icon: string, level: number, label: string[], desc: string, bad = true) => { if (level > 0) m.push({ id, icon, level, bad, label: label[level - 1], desc }); };
    push('hunger', '🍽️', lvl(100 - this.hunger, 60, 75, 88, 98), ['Peckish', 'Hungry', 'Very Hungry', 'Starving'], 'Eat something. Starvation damages health.');
    push('thirst', '💧', lvl(100 - this.thirst, 55, 72, 86, 98), ['Slightly Thirsty', 'Thirsty', 'Parched', 'Dying of Thirst'], 'Drink clean water.');
    push('tired', '😴', lvl(this.fatigue, 55, 70, 85, 96), ['Drowsy', 'Tired', 'Very Tired', 'Exhausted'], 'Sleep in a bed. Fatigue lowers stamina and accuracy.');
    push('endurance', '😮‍💨', lvl(100 - this.stamina / this.maxStamina * 100, 50, 70, 85, 96), ['Winded', 'Out of Breath', 'Exhausted', 'Collapsing'], 'Rest to recover stamina.');
    push('bleeding', '🩸', lvl(this.bleeding(), 0.05, 1.2, 2.5, 4), ['Bleeding', 'Bleeding Heavily', 'Hemorrhaging', 'Bleeding Out'], 'Bandage your wounds (Health panel: H).');
    push('pain', '⚡', lvl(this.pain, 15, 35, 60, 85), ['Minor Pain', 'Pain', 'Severe Pain', 'Agony'], 'Painkillers help. Pain reduces speed and accuracy.');
    push('injured', '🤕', lvl(100 - this.health(), 15, 35, 60, 80), ['Scratched Up', 'Injured', 'Badly Injured', 'Critical'], 'Rest, eat and treat wounds.');
    push('panic', '😱', lvl(this.panic, 15, 40, 65, 88), ['Anxious', 'Panicked', 'Strong Panic', 'Extreme Panic'], 'Panic ruins aim. Get away from the horde.');
    if (this.bodyTemp < 36.2) push('cold', '🥶', lvl(37 - this.bodyTemp, 0.8, 1.4, 2.1, 2.6), ['Chilly', 'Cold', 'Freezing', 'Hypothermic'], 'Get dry, warm clothes, a fire, or shelter.');
    if (this.bodyTemp > 37.6) push('hot', '🥵', lvl(this.bodyTemp - 37, 0.6, 1.2, 1.8, 2.4), ['Warm', 'Feverish', 'High Fever', 'Burning Up'], 'Fever. Infection, perhaps?');
    push('wet', '🌧️', lvl(this.wetness * 100, 15, 40, 65, 88), ['Damp', 'Wet', 'Soaking', 'Drenched'], 'Being wet chills you. Dry off near a fire.');
    push('sick', '🤢', lvl(Math.max(this.sickness, this.knoxInfected && this.knox > 0.2 ? this.knox * 100 : 0), 15, 35, 60, 85), ['Queasy', 'Nauseous', 'Sick', 'Fever'], 'Something is wrong with you.');
    push('infection', '🦠', lvl(this.woundInfection() * 100, 5, 30, 55, 80), ['Wound Infected', 'Infected', 'Badly Infected', 'Sepsis'], 'Disinfect wounds and take antibiotics.');
    push('heavy', '🎒', lvl(weightRatio * 100, 101, 125, 150, 175), ['Heavy Load', 'Very Heavy Load', 'Overloaded', 'Crushing Load'], 'Drop items or equip a bag.');
    push('unhappy', '☹️', lvl(this.unhappy, 30, 50, 70, 88), ['Glum', 'Sad', 'Depressed', 'Despairing'], 'Eat treats, stay dry, keep a lit home.');
    if (this.painkillerHrs > 0) m.push({ id: 'pk', icon: '💊', level: 1, bad: false, label: 'Painkillers', desc: 'Pain dulled.' });
    if (this.hunger > 92) m.push({ id: 'full', icon: '😋', level: 1, bad: false, label: 'Well Fed', desc: 'Healing faster.' });
    return m;
  }

  toJSON() { const o: any = {}; for (const k of Object.keys(this)) o[k] = (this as any)[k]; return o; }
  load(o: any) { Object.assign(this, o); }
}
