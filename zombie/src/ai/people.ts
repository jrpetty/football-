// Who a person is: past life, temperament, what they're good at, how they look, what's on their mind.
// Everything here is data + pure helpers; the behaviour lives in survivors.ts and town/town.ts.
import { RNG } from '../core/util';

/** body poses and held tools understood by the character renderer (same numbers as ACT/TOOL in zombies.ts) */
export const ACT = { none: 0, swing: 1, tend: 2, sit: 3, cower: 4, talk: 5, carry: 6, lie: 7, wave: 8, stir: 9, eat: 10 } as const;
export const TOOL = { none: 0, gun: 1, axe: 2, hoe: 3, wrench: 4, bat: 5, medbag: 6, ladle: 7 } as const;

// ---------------------------------------------------------------- skills & experience
export type PSkill = 'shooting' | 'melee' | 'farming' | 'cooking' | 'crafting' | 'medicine' | 'scavenging' | 'labour';
export const PSKILLS: { id: PSkill; name: string; icon: string; desc: string }[] = [
  { id: 'shooting', name: 'Marksmanship', icon: '🎯', desc: 'Hit chance and rate of fire on watch and in a fight.' },
  { id: 'melee', name: 'Brawling', icon: '🏏', desc: 'Damage up close, and the nerve to stand their ground.' },
  { id: 'farming', name: 'Farming', icon: '🌾', desc: 'Plots tended per day and the size of each harvest.' },
  { id: 'cooking', name: 'Cooking', icon: '🍲', desc: 'Meals cooked per shift; good cooks stretch ingredients further.' },
  { id: 'crafting', name: 'Handiwork', icon: '🔧', desc: 'How fast walls, doors and machines get patched up.' },
  { id: 'medicine', name: 'Medicine', icon: '🩺', desc: 'Healing, making bandages, and holding back a bite.' },
  { id: 'scavenging', name: 'Scavenging', icon: '🎒', desc: 'What they bring back from a run, and whether they come back at all.' },
  { id: 'labour', name: 'Labour', icon: '🪓', desc: 'Timber felled, loads hauled and odd jobs done.' },
];
/** cumulative xp needed to reach a skill level (0–10) */
export const P_XP = (lvl: number) => 40 * lvl * (lvl + 1);
export const MAX_SKILL = 10;
export function skillLevel(xp: number) { let l = 0; while (l < MAX_SKILL && xp >= P_XP(l + 1)) l++; return l; }
export function skillProgress(xp: number) { const l = skillLevel(xp); if (l >= MAX_SKILL) return 1; return (xp - P_XP(l)) / (P_XP(l + 1) - P_XP(l)); }
/** overall level from everything they've ever learned */
export function personLevel(total: number) { return 1 + Math.floor(Math.sqrt(Math.max(0, total) / 150)); }
export function levelProgress(total: number) { const l = personLevel(total); const a = 150 * (l - 1) ** 2, b = 150 * l ** 2; return (total - a) / (b - a); }
const RANKS: [number, string][] = [[14, 'Pillar of the town'], [11, 'Stalwart'], [8, 'Veteran'], [6, 'Regular'], [4, 'Hand'], [1, 'Newcomer']];
export const rankName = (lvl: number) => RANKS.find(r => lvl >= r[0])![1];

export type JobId = 'idle' | 'farmer' | 'cook' | 'guard' | 'lumberjack' | 'mechanic' | 'medic';
export const JOB_SKILL: Record<JobId, PSkill> = { idle: 'labour', farmer: 'farming', cook: 'cooking', guard: 'shooting', lumberjack: 'labour', mechanic: 'crafting', medic: 'medicine' };

// ---------------------------------------------------------------- temperament
export interface PTrait { id: string; name: string; icon: string; desc: string; excl?: string[]; rare?: boolean }
export const PTRAITS: PTrait[] = [
  { id: 'brave', name: 'Brave', icon: '🦁', desc: 'Runs toward the screaming. Joins any fight near them and rarely breaks.', excl: ['coward'] },
  { id: 'coward', name: 'Skittish', icon: '🐇', desc: 'Bolts for home at the first groan, but is usually the first to raise the alarm.', excl: ['brave'] },
  { id: 'hardworker', name: 'Hard worker', icon: '⚒️', desc: 'Gets 25% more done in a shift.', excl: ['lazy'] },
  { id: 'lazy', name: 'Idler', icon: '🛋️', desc: 'Gets 20% less done, takes long breaks.', excl: ['hardworker'] },
  { id: 'cheerful', name: 'Cheerful', icon: '🌞', desc: 'Happier than most and good company; lifts the mood of people they talk to.', excl: ['gloomy'] },
  { id: 'gloomy', name: 'Brooding', icon: '🌧️', desc: 'Sees the worst in things. Harder to keep happy.', excl: ['cheerful'] },
  { id: 'quick', name: 'Quick study', icon: '📚', desc: 'Learns every skill 40% faster.', excl: ['slow'] },
  { id: 'slow', name: 'Set in their ways', icon: '🪨', desc: 'Learns 25% slower.', excl: ['quick'] },
  { id: 'tough', name: 'Tough as boots', icon: '🥾', desc: 'More health, shrugs off scratches, and bites break skin less often.', excl: ['frail'] },
  { id: 'frail', name: 'Frail', icon: '🍂', desc: 'Less health and slower on their feet.', excl: ['tough'] },
  { id: 'deadeye', name: 'Steady hands', icon: '🔭', desc: 'Markedly better aim.' },
  { id: 'greenthumb', name: 'Green thumb', icon: '🌱', desc: 'Bigger harvests from every plot they tend.' },
  { id: 'nightowl', name: 'Night owl', icon: '🦉', desc: 'Stays up late, sleeps in, and sees well in the dark. A natural for the night watch.', excl: ['earlybird'] },
  { id: 'earlybird', name: 'Early riser', icon: '🐓', desc: 'Up before dawn and first to work.', excl: ['nightowl'] },
  { id: 'glutton', name: 'Big appetite', icon: '🍖', desc: 'Eats two portions a day.', excl: ['frugal'] },
  { id: 'frugal', name: 'Small appetite', icon: '🥣', desc: 'Skips a meal every other day without complaint.', excl: ['glutton'] },
  { id: 'sociable', name: 'Sociable', icon: '💬', desc: 'Makes friends fast and gets lonely in a small camp.', excl: ['loner'] },
  { id: 'loner', name: 'Loner', icon: '🚶', desc: 'Prefers their own company; dislikes a crowded town.', excl: ['sociable'] },
  { id: 'resistant', name: 'Thick blood', icon: '🧬', desc: 'A bite takes nearly twice as long to turn them.', rare: true },
  { id: 'packrat', name: 'Magpie', icon: '🧲', desc: 'Always comes back from a scavenging run with something extra.' },
];
export const trait = (id: string) => PTRAITS.find(t => t.id === id)!;

// ---------------------------------------------------------------- past lives
export interface Background { id: string; name: string; bio: string; skills: Partial<Record<PSkill, number>>; loves: PSkill[]; shirt?: number[]; hat?: number }
export const BACKGROUNDS: Background[] = [
  { id: 'nurse', name: 'Nurse', bio: 'worked nights on a hospital ward', skills: { medicine: 4, cooking: 1 }, loves: ['medicine'], shirt: [0x4f8f8a, 0x6fa3b5] },
  { id: 'farmhand', name: 'Farmhand', bio: 'grew up on a dairy farm up the valley', skills: { farming: 4, labour: 2 }, loves: ['farming'], shirt: [0x7a5a3a, 0x8a3f33], hat: 0.5 },
  { id: 'mechanic', name: 'Mechanic', bio: 'ran the garage on the highway', skills: { crafting: 4, labour: 1 }, loves: ['crafting'], shirt: [0x34455c, 0x4a4a4a] },
  { id: 'cook', name: 'Line cook', bio: 'cooked breakfast for truckers for twenty years', skills: { cooking: 4, melee: 1 }, loves: ['cooking'], shirt: [0xd8d2c4, 0x3a3a3a] },
  { id: 'deputy', name: 'Deputy', bio: 'was a county sheriff\'s deputy', skills: { shooting: 4, melee: 2 }, loves: ['shooting'], shirt: [0x6b6247, 0x2f3b55] },
  { id: 'soldier', name: 'Reservist', bio: 'was called up when it started and never stood down', skills: { shooting: 3, melee: 2, medicine: 1 }, loves: ['shooting', 'melee'], shirt: [0x4f5a3a, 0x5c5a45] },
  { id: 'carpenter', name: 'Carpenter', bio: 'built half the porches in this county', skills: { crafting: 3, labour: 3 }, loves: ['crafting', 'labour'], shirt: [0xa3442f, 0x5b6b4a] },
  { id: 'logger', name: 'Logger', bio: 'worked the timber crews above the lake', skills: { labour: 4, melee: 2 }, loves: ['labour'], shirt: [0xa32f2f, 0x2f4a2f], hat: 0.6 },
  { id: 'teacher', name: 'Teacher', bio: 'taught fourth grade at the town school', skills: { medicine: 1, cooking: 1, farming: 1 }, loves: ['farming', 'cooking'], shirt: [0x7a6a8a, 0x8a7a5a] },
  { id: 'trucker', name: 'Trucker', bio: 'was hauling feed through town when the roads closed', skills: { scavenging: 3, crafting: 2 }, loves: ['scavenging'], shirt: [0x3f556b, 0x6b4a3a], hat: 0.6 },
  { id: 'hunter', name: 'Hunter', bio: 'spent every autumn in a deer blind', skills: { shooting: 3, scavenging: 3 }, loves: ['shooting', 'scavenging'], shirt: [0x5a5a35, 0x6b4f2a], hat: 0.5 },
  { id: 'student', name: 'Student', bio: 'was home from college for the weekend', skills: { scavenging: 1 }, loves: [], shirt: [0x8a2f4a, 0x2f5a8a, 0x8a8a8a] },
  { id: 'clerk', name: 'Shop clerk', bio: 'ran the till at the general store', skills: { scavenging: 2, cooking: 1 }, loves: ['scavenging'], shirt: [0x9a8a4a, 0x4a7a5a] },
  { id: 'vet', name: 'Vet', bio: 'looked after the valley\'s livestock', skills: { medicine: 3, farming: 2 }, loves: ['medicine', 'farming'], shirt: [0x5a8a6a, 0x6a7a8a] },
  { id: 'lineman', name: 'Lineman', bio: 'kept the power lines up through thirty winters', skills: { crafting: 3, labour: 2, scavenging: 1 }, loves: ['crafting'], shirt: [0xb8862f, 0x4a4a55], hat: 0.4 },
  { id: 'drifter', name: 'Drifter', bio: 'doesn\'t talk about before', skills: { scavenging: 2, melee: 2 }, loves: ['melee'], shirt: [0x4a443a, 0x3a3a3a] },
  { id: 'gardener', name: 'Gardener', bio: 'kept an allotment that fed half the street', skills: { farming: 3, cooking: 2 }, loves: ['farming'], shirt: [0x6a8a4a, 0x8a6a4a], hat: 0.4 },
  { id: 'boxer', name: 'Boxing coach', bio: 'ran the gym behind the fire station', skills: { melee: 4, labour: 1 }, loves: ['melee'], shirt: [0x8a2f2f, 0x3a3a3a] },
];
export const background = (id: string) => BACKGROUNDS.find(b => b.id === id) ?? BACKGROUNDS[BACKGROUNDS.length - 3];

// ---------------------------------------------------------------- looks (same shape as HumanLook in zombies.ts)
export interface Look { skin: number; shirt: number; pants: number; hair: number; build: number; height: number; hairStyle: number }
const SKIN = [0xf0c9a5, 0xe4b48c, 0xd29b72, 0xb97f55, 0x94603c, 0x6f4429, 0x513220];
const HAIR = [0x1c1611, 0x2e2118, 0x4a3220, 0x6b4a2a, 0x8f6a3a, 0xb8925a, 0x7a2f1a];
const GREY = [0x9a9690, 0xc4c0b8, 0x6f6c68];
const PANTS = [0x2f3b55, 0x3a4660, 0x4a4234, 0x2a2a2a, 0x5c5340, 0x39473a, 0x4f4f57];
const SHIRTS = [0x8a3f33, 0x3f6b8a, 0x5b7a4a, 0xb89a4a, 0x7a5a8a, 0x8a8a84, 0x3a5a5a, 0xb86a3a, 0x4a4a6a, 0xa85a6a];

// ---------------------------------------------------------------- the person
export interface Thought { id: string; text: string; v: number; until: number }
export interface Persona {
  first: string; last: string; age: number; bg: string; traits: string[];
  /** skills they take to (xp ×1.5, happier doing it) and a job they resent */
  loves: PSkill[]; hates: JobId | null;
  xp: Record<PSkill, number>; bonusXp: number;
  look: Look;
  /** sleep window in hours */
  wake: number; bed: number;
  /** life in town */
  kills: number; daysHere: number; joinedDay: number; harvests: number; meals: number; repairs: number; healed: number; runs: number; wounds: number;
  thoughts: Thought[];
  bonds: Record<number, number>;
}

const FIRST = ['Marcus', 'Elena', 'Dwayne', 'Priya', 'Tom', 'Hannah', 'Luis', 'Grace', 'Owen', 'Mei', 'Abdul', 'Rosa', 'Pete', 'June', 'Kofi', 'Ava', 'Walt', 'Nadia', 'Bo', 'Sienna', 'Ray', 'Ingrid', 'Hector', 'Lena', 'Sam', 'Tasha', 'Ivan', 'Maggie', 'Earl', 'Yuki', 'Dev', 'Ruth', 'Cal', 'Fatima', 'Gus', 'Noor', 'Rhys', 'Dolores', 'Theo', 'Bea', 'Jonah', 'Carmen', 'Nils', 'Opal', 'Zeke', 'Wren', 'Arlo', 'Esther', 'Mack', 'Lucia', 'Silas', 'Kim', 'Reggie', 'Ada', 'Booker', 'Tilly', 'Ezra', 'Margo', 'Duke', 'Ines'];
const LAST = ['Hale', 'Okafor', 'Brandt', 'Nakamura', 'Reyes', 'Whitlock', 'Mbeki', 'Castellan', 'Dunmore', 'Varga', 'Pruitt', 'Lindqvist', 'Abara', 'Tolliver', 'Marsh', 'Kowalski', 'Delacroix', 'Haines', 'Osei', 'Ferro', 'Blackwood', 'Yilmaz', 'Greer', 'Santoro', 'Voss', 'Akhtar', 'Mercer', 'Quill', 'Rowe', 'Tanaka', 'Underhill', 'Baptiste', 'Crane', 'Easton', 'Fenn', 'Gallo', 'Hoyt', 'Ibarra', 'Jessup', 'Keane'];

export function makePersona(rng: RNG, taken: (first: string) => boolean, day = 1): Persona {
  const bg = rng.pick(BACKGROUNDS);
  const age = Math.round(bg.id === 'student' ? rng.range(18, 23) : 21 + Math.pow(rng.next(), 1.3) * 46);
  // traits: two, sometimes three, never contradictory
  const traits: string[] = [];
  const want = rng.chance(0.3) ? 3 : 2;
  for (let guard = 0; traits.length < want && guard < 40; guard++) {
    const t = rng.pick(PTRAITS);
    if (traits.includes(t.id) || (t.rare && !rng.chance(0.25))) continue;
    if (t.excl?.some(e => traits.includes(e)) || traits.some(o => trait(o).excl?.includes(t.id))) continue;
    if (age > 58 && t.id === 'tough' && rng.chance(0.6)) continue;
    traits.push(t.id);
  }
  // skills: what the old life taught, plus a little of everything else
  const xp = Object.fromEntries(PSKILLS.map(s => [s.id, 0])) as Record<PSkill, number>;
  for (const s of PSKILLS) {
    const base = (bg.skills[s.id] ?? 0) + (rng.chance(0.35) ? rng.int(0, 2) : 0) + (age > 45 && rng.chance(0.3) ? 1 : 0);
    xp[s.id] = Math.round(P_XP(Math.min(8, base)) + rng.range(0, 30));
  }
  const loves = [...bg.loves];
  if (loves.length < 2 && rng.chance(0.6)) { const s = rng.pick(PSKILLS).id; if (!loves.includes(s)) loves.push(s); }
  const jobs: JobId[] = ['farmer', 'cook', 'guard', 'lumberjack', 'mechanic', 'medic'];
  let hates: JobId | null = rng.chance(0.4) ? rng.pick(jobs) : null;
  if (hates && loves.includes(JOB_SKILL[hates])) hates = null;
  if (traits.includes('coward') && rng.chance(0.6)) hates = 'guard';
  // looks follow the life
  const hairBase = rng.pick(HAIR);
  const greying = age > 60 ? 1 : age > 46 ? 0.5 : 0;
  const hair = rng.chance(greying) ? rng.pick(GREY) : hairBase;
  let hairStyle = rng.weighted<number>([[0, 5], [2, 3], [4, 3], [5, 2], [1, age > 40 ? 2 : 0.5], [3, 1]]);
  if (bg.hat && rng.chance(bg.hat)) hairStyle = 3;
  const heavy = traits.includes('glutton') ? 0.12 : traits.includes('frugal') ? -0.07 : 0;
  const look: Look = {
    skin: rng.pick(SKIN), hair, hairStyle,
    shirt: rng.chance(0.7) && bg.shirt ? rng.pick(bg.shirt) : rng.pick(SHIRTS),
    pants: rng.pick(PANTS),
    build: Math.min(1.25, Math.max(0.85, rng.range(0.9, 1.12) + heavy + (traits.includes('tough') ? 0.06 : 0) - (traits.includes('frail') ? 0.06 : 0))),
    height: rng.range(0.92, 1.08) - (age > 62 ? 0.02 : 0),
  };
  const owl = traits.includes('nightowl'), lark = traits.includes('earlybird');
  let first = rng.pick(FIRST);
  for (let i = 0; i < 30 && taken(first); i++) first = rng.pick(FIRST);
  return {
    first, last: rng.pick(LAST), age, bg: bg.id, traits, loves, hates, xp, bonusXp: 0, look,
    wake: (owl ? 7.6 : lark ? 5.2 : 6.3) + rng.range(-0.3, 0.5), bed: (owl ? 23.6 : lark ? 21.2 : 22.2) + rng.range(-0.4, 0.4),
    kills: 0, daysHere: 0, joinedDay: day, harvests: 0, meals: 0, repairs: 0, healed: 0, runs: 0, wounds: 0,
    thoughts: [], bonds: {},
  };
}

export const has = (p: Persona, t: string) => p.traits.includes(t);
export const lvl = (p: Persona, s: PSkill) => skillLevel(p.xp[s]);
export const totalXp = (p: Persona) => PSKILLS.reduce((a, s) => a + p.xp[s.id], 0) + p.bonusXp;
export const level = (p: Persona) => personLevel(totalXp(p));
export const maxHp = (p: Persona) => Math.round(100 + (has(p, 'tough') ? 30 : has(p, 'frail') ? -20 : 0) + (level(p) - 1) * 3 - (p.age > 62 ? 8 : 0));
/** how much gets done in a shift: skill, temperament, mood */
export function workRate(p: Persona, s: PSkill, happy: number) {
  return (0.55 + lvl(p, s) * 0.12) * (has(p, 'hardworker') ? 1.25 : has(p, 'lazy') ? 0.8 : 1) * (0.75 + happy / 200) * (1 + (level(p) - 1) * 0.01);
}
/** suitability for a job when the town hands out work */
export function jobScore(p: Persona, j: JobId) {
  const s = JOB_SKILL[j];
  let v = lvl(p, s) * 2 + (p.loves.includes(s) ? 3 : 0) - (p.hates === j ? 6 : 0);
  if (j === 'guard') v += (has(p, 'brave') ? 3 : 0) - (has(p, 'coward') ? 8 : 0) + (has(p, 'deadeye') ? 2 : 0) + (has(p, 'nightowl') ? 1 : 0);
  if (j === 'farmer' && has(p, 'greenthumb')) v += 3;
  return v;
}
/** add xp; returns the new level if the skill levelled up */
export function gainXp(p: Persona, s: PSkill, amount: number): number {
  const before = skillLevel(p.xp[s]);
  p.xp[s] += amount * (has(p, 'quick') ? 1.4 : has(p, 'slow') ? 0.75 : 1) * (p.loves.includes(s) ? 1.5 : 1) * (p.age < 26 ? 1.1 : p.age > 60 ? 0.9 : 1);
  const after = skillLevel(p.xp[s]);
  return after > before ? after : 0;
}
export function think(p: Persona, now: number, id: string, text: string, v: number, hours: number) {
  const t = p.thoughts.find(q => q.id === id);
  if (t) { t.text = text; t.v = v; t.until = now + hours * 60; } else p.thoughts.push({ id, text, v, until: now + hours * 60 });
}
export function prune(p: Persona, now: number) { if (p.thoughts.length) p.thoughts = p.thoughts.filter(t => t.until > now); }
export const moodSum = (p: Persona) => p.thoughts.reduce((a, t) => a + t.v, 0);
export const bioLine = (p: Persona) => `${p.age}, ${background(p.bg).name.toLowerCase()} — ${background(p.bg).bio}.`;

// ---------------------------------------------------------------- things people say
type Say = Record<string, string[]>;
export const SAY: Say = {
  greet: ['Morning.', 'Hey, boss.', 'Still breathing. You?', 'Good to see you in one piece.', 'Need something?', 'Quiet today. I don\'t trust it.'],
  greet_cheerful: ['Look at this place! We\'re really doing it.', 'Beautiful day to not be eaten!', 'There you are! Sleep alright?'],
  greet_gloomy: ['Another day. Hooray.', 'It won\'t last, you know. Nothing does.', 'What now.'],
  greet_night: ['Can\'t sleep either?', 'Keep your voice down. They hear better at night.'],
  stranger: ['Stay back. …You bitten?', 'I\'ve got nothing worth taking.', 'You got food? Then we can talk.', 'Haven\'t seen a living face in weeks.'],
  joined: ['Alright. I\'m with you.', 'Lead the way.', 'Better than dying alone.', 'A roof and a meal. That\'s all I ask.'],
  alarm: ['ZOMBIES! Inside the line!', 'They\'re here! Get to cover!', 'Dead ones — at the edge of town!', 'Ring the bell! RING THE BELL!'],
  flee: ['Nope. Nope nope nope.', 'I\'m not dying today!', 'Run! RUN!', 'Get me out of here!'],
  fight: ['Come on then!', 'Hold the line!', 'Not one step further!', 'Stay behind me!', 'Got one in my sights.'],
  kill: ['That\'s one less.', 'Stay down.', 'Got it!', 'Rest now.'],
  hurt: ['Agh — it got me!', 'I\'m hit!', 'Get it off me!'],
  bitten: ['It broke the skin… oh no.', 'It\'s just a scratch. It\'s just a scratch.', 'Don\'t tell the others. Please.'],
  fever: ['So cold…', 'Head\'s pounding. I just need to lie down.', 'If I turn… you do it. Promise me.'],
  noammo: ['I\'m out!', 'Empty! Need rounds at the post!'],
  hungry: ['My stomach thinks my throat\'s been cut.', 'We need food in the stores. Soon.', 'I\'d trade my boots for a hot meal.'],
  thirsty: ['My mouth\'s like sandpaper.', 'Is there no clean water at all?'],
  homeless: ['Where am I meant to sleep? The mud?', 'A bed. That\'s all. One bed.'],
  farmer: ['Soil\'s good here.', 'These\'ll be ready in a few days.', 'Never thought I\'d miss weeding.', 'Rain would help.'],
  cook: ['Stew\'s on. Don\'t ask what\'s in it.', 'Bring me vegetables and I\'ll work miracles.', 'Hot food changes everything.'],
  guard: ['Nothing moving. For now.', 'I see everything from up here.', 'Tree line\'s clear.', 'Keep the post stocked and I\'ll keep them off you.'],
  lumberjack: ['Timber!', 'Good straight pine, this.', 'My back will remember today.', 'Planks by sundown.'],
  mechanic: ['Held together with spit and wire, this.', 'I can fix that.', 'Who built this wall? …Oh. Sorry.'],
  medic: ['Keep that wound clean.', 'Let me look at that.', 'Rags boil into bandages. Bring me rags.', 'Bites I can slow. Not stop — not without serum.'],
  idle: ['Give me something to do.', 'I\'ll tidy up around the hall.', 'Any work going?'],
  eat: ['Mm. Almost like before.', 'Second helpings?', 'Best meal since… I can\'t remember.'],
  chat: ['…and that was the last time I saw the sea.', 'You ever think they remember anything?', 'My mother had a recipe for that.', 'When the lights come back, first thing I\'m doing is a hot bath.', 'Did you hear the wolves last night?', 'I used to complain about traffic. Traffic!', 'Deal. But you owe me a favour.', 'Ha! No. No, really?', 'I had a dog. Before.', 'Do you think anywhere else made it?'],
  chat_reply: ['Mm.', 'Ha!', 'You\'re not wrong.', 'Don\'t start.', 'Tell me about it.', 'Same.', 'I think about that too.'],
  argue: ['You never listen.', 'Say that again.', 'Unbelievable.', 'I\'m done talking to you.'],
  levelup: ['I\'m getting the hang of this.', 'Huh. That was easier than last week.', 'Practice. Who knew.'],
  grief: ['They were my friend.', 'We should have been faster.', 'I can still hear it.'],
  morning: ['Up and at it.', 'Coffee. I would kill for coffee.', 'Another day above ground.'],
  bedtime: ['I\'m turning in.', 'Lock the doors.', 'Wake me if the world ends again.'],
  rain: ['Lovely weather for it.', 'At least the tower\'s filling.'],
  follow: ['Right behind you.', 'Lead on.', 'Watching your back.'],
  wait: ['I\'ll hold here.', 'Don\'t be long.'],
};
export function line(rng: RNG, kind: string, p?: Persona) {
  if (p) for (const t of p.traits) { const k = `${kind}_${t === 'cheerful' ? 'cheerful' : t === 'gloomy' ? 'gloomy' : ''}`; if (SAY[k] && rng.chance(0.5)) return rng.pick(SAY[k]); }
  return rng.pick(SAY[kind] ?? SAY.greet);
}
