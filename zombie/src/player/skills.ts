export type SkillId = 'fitness' | 'strength' | 'sprinting' | 'lightfoot' | 'blunt' | 'blade' | 'firearms' | 'carpentry' | 'engineering' | 'firstaid' | 'scavenging';
export const SKILLS: { id: SkillId; name: string; icon: string; desc: string; group: string }[] = [
  { id: 'fitness', name: 'Fitness', icon: '🫀', desc: 'Max stamina and stamina recovery.', group: 'Physical' },
  { id: 'strength', name: 'Strength', icon: '💪', desc: 'Carry weight and melee damage.', group: 'Physical' },
  { id: 'sprinting', name: 'Sprinting', icon: '🏃', desc: 'Sprint speed and sprint stamina cost.', group: 'Physical' },
  { id: 'lightfoot', name: 'Lightfooted', icon: '🦶', desc: 'Quieter movement. Zombies hear you less.', group: 'Physical' },
  { id: 'blunt', name: 'Blunt', icon: '🏏', desc: 'Damage and swing speed with blunt weapons.', group: 'Combat' },
  { id: 'blade', name: 'Blade', icon: '🔪', desc: 'Damage and swing speed with bladed weapons.', group: 'Combat' },
  { id: 'firearms', name: 'Firearms', icon: '🎯', desc: 'Accuracy, recoil control, reload speed.', group: 'Combat' },
  { id: 'carpentry', name: 'Carpentry', icon: '🪚', desc: 'Unlocks builds, stronger structures, faster building.', group: 'Crafting' },
  { id: 'engineering', name: 'Engineering', icon: '⚡', desc: 'Machines, electricity, metalwork. Higher levels speed up the factory.', group: 'Crafting' },
  { id: 'firstaid', name: 'First Aid', icon: '⛑️', desc: 'Faster, more effective treatment and healing.', group: 'Survival' },
  { id: 'scavenging', name: 'Scavenging', icon: '🔍', desc: 'Find more loot and search faster.', group: 'Survival' },
];
export const XP_FOR_LEVEL = (lvl: number) => 75 * lvl * (lvl + 1); // cumulative xp to reach lvl

export interface TraitDef { id: string; name: string; cost: number; desc: string; excl?: string[] }
// negative cost = gives points
export const TRAITS: TraitDef[] = [
  { id: 'athletic', name: 'Athletic', cost: 6, desc: '+2 Fitness, faster running.', excl: ['unfit', 'obese'] },
  { id: 'strong', name: 'Strong', cost: 6, desc: '+2 Strength: hit harder, carry more.', excl: ['weak'] },
  { id: 'lucky', name: 'Lucky', cost: 4, desc: 'Find more loot.' },
  { id: 'fast_healer', name: 'Fast Healer', cost: 5, desc: 'Wounds heal 50% faster.', excl: ['slow_healer'] },
  { id: 'thick_skin', name: 'Thick Skinned', cost: 6, desc: 'Scratches and bites are less likely to break skin.' },
  { id: 'keen_hearing', name: 'Keen Hearing', cost: 3, desc: 'Wider awareness: zombies show on the compass sooner.' },
  { id: 'night_owl', name: 'Night Owl', cost: 3, desc: 'Need less sleep. Better night vision.' },
  { id: 'handy', name: 'Handy', cost: 4, desc: '+1 Carpentry, +1 Engineering, structures are tougher.' },
  { id: 'iron_gut', name: 'Iron Gut', cost: 3, desc: 'Much less likely to get food poisoning.' },
  { id: 'unfit', name: 'Out of Shape', cost: -6, desc: '-2 Fitness.', excl: ['athletic'] },
  { id: 'weak', name: 'Weak', cost: -6, desc: '-2 Strength.', excl: ['strong'] },
  { id: 'slow_healer', name: 'Slow Healer', cost: -4, desc: 'Wounds heal 50% slower.', excl: ['fast_healer'] },
  { id: 'clumsy', name: 'Clumsy', cost: -3, desc: 'Movement makes much more noise.' },
  { id: 'hemophobic', name: 'Hemophobic', cost: -3, desc: 'Panic when bleeding or covered in blood. Slow at first aid.' },
  { id: 'insomniac', name: 'Restless Sleeper', cost: -3, desc: 'Sleep restores fatigue more slowly.' },
  { id: 'obese', name: 'Overweight', cost: -5, desc: 'Lower stamina, slower movement.', excl: ['athletic'] },
  { id: 'prone_ill', name: 'Prone to Illness', cost: -3, desc: 'Infections and sickness hit harder.' },
  { id: 'smoker', name: 'Short of Breath', cost: -2, desc: 'Stamina regenerates slower.' },
];

export interface Occupation { id: string; name: string; icon: string; points: number; desc: string; skills: Partial<Record<SkillId, number>>; items: [string, number][] }
export const OCCUPATIONS: Occupation[] = [
  { id: 'unemployed', name: 'Unemployed', icon: '🪑', points: 8, desc: 'No skills, lots of potential (+8 trait points).', skills: {}, items: [] },
  { id: 'construction', name: 'Construction Worker', icon: '👷', points: 0, desc: 'Carpentry +3, Strength +1. Starts with a hammer and nails.', skills: { carpentry: 3, strength: 1 }, items: [['hammer', 1], ['nails', 30]] },
  { id: 'police', name: 'Police Officer', icon: '👮', points: -4, desc: 'Firearms +3, Fitness +1. Starts with a pistol and ammo.', skills: { firearms: 3, fitness: 1 }, items: [['pistol', 1], ['ammo_9mm', 24]] },
  { id: 'nurse', name: 'Nurse', icon: '🧑‍⚕️', points: 2, desc: 'First Aid +3, Lightfooted +1. Starts with medical supplies.', skills: { firstaid: 3, lightfoot: 1 }, items: [['bandage', 4], ['disinfectant', 1], ['painkillers', 4]] },
  { id: 'engineer', name: 'Electrical Engineer', icon: '🧑‍🔧', points: 0, desc: 'Engineering +3, Carpentry +1. The factory dreamer. Starts with a screwdriver & wrench.', skills: { engineering: 3, carpentry: 1 }, items: [['screwdriver', 1], ['wrench', 1]] },
  { id: 'lumberjack', name: 'Lumberjack', icon: '🪓', points: 0, desc: 'Blade +2, Strength +1, Fitness +1. Starts with an axe.', skills: { blade: 2, strength: 1, fitness: 1 }, items: [['axe', 1]] },
  { id: 'burglar', name: 'Burglar', icon: '🥷', points: 0, desc: 'Scavenging +3, Lightfooted +2. Starts with a crowbar.', skills: { scavenging: 3, lightfoot: 2 }, items: [['crowbar', 1]] },
  { id: 'veteran', name: 'Veteran', icon: '🎖️', points: -6, desc: 'Firearms +2, Blunt +1, Fitness +1. Calm under pressure (less panic).', skills: { firearms: 2, blunt: 1, fitness: 1 }, items: [['rifle', 1], ['ammo_rifle', 10]] },
];

export class Skills {
  xp: Record<SkillId, number> = Object.fromEntries(SKILLS.map(s => [s.id, 0])) as Record<SkillId, number>;
  bonus: Record<SkillId, number> = Object.fromEntries(SKILLS.map(s => [s.id, 0])) as Record<SkillId, number>;
  bookUntil: Partial<Record<SkillId, number>> = {}; // game-time (minutes) until which XP is boosted
  onLevelUp: (id: SkillId, lvl: number) => void = () => {};

  level(id: SkillId) {
    const xp = this.xp[id]; let l = 0;
    while (l < 10 && xp >= XP_FOR_LEVEL(l + 1)) l++;
    return Math.min(10, l + this.bonus[id]);
  }
  rawLevel(id: SkillId) { const xp = this.xp[id]; let l = 0; while (l < 10 && xp >= XP_FOR_LEVEL(l + 1)) l++; return l; }
  progress(id: SkillId) {
    const l = this.rawLevel(id); if (l >= 10) return 1;
    const a = XP_FOR_LEVEL(l), b = XP_FOR_LEVEL(l + 1);
    return (this.xp[id] - a) / (b - a);
  }
  gain(id: SkillId, amount: number, now: number) {
    const before = this.level(id);
    let mult = 1;
    if ((this.bookUntil[id] ?? 0) > now) mult *= 3;
    this.xp[id] += amount * mult;
    const after = this.level(id);
    if (after > before) this.onLevelUp(id, after);
  }
}
