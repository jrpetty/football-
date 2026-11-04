// Four new people every time: names from around the world, a temperament,
// a quirk, a bit of a life story, and a face.

import type { Person, RoleDef } from './types.ts'

const NAMES: { name: string; g: 'woman' | 'man' | 'nonbinary' }[] = [
  ...['Amara', 'Sofia', 'Mei', 'Priya', 'Fatima', 'Ingrid', 'Lucía', 'Yuki', 'Zainab', 'Chloé', 'Olga', 'Nia', 'Ana', 'Hana', 'Grace', 'Leila', 'Ayesha', 'Freya', 'Camila', 'Siobhan', 'Thandiwe', 'Mariam', 'Elena', 'Jia', 'Rosa', 'Kemi', 'Saoirse', 'Isabel', 'Noor', 'Dagny'].map((n) => ({ name: n, g: 'woman' as const })),
  ...['Kwame', 'Mateo', 'Hiroshi', 'Arjun', 'Omar', 'Lars', 'Diego', 'Wei', 'Tomasz', 'Malik', 'Finn', 'Rafael', 'Kofi', 'Dmitri', 'Jonah', 'Ravi', 'Samir', 'Callum', 'Thiago', 'Ji-ho', 'Emeka', 'Bruno', 'Eitan', 'Hugo', 'Nikolai', 'Darius', 'Owen', 'Tariq', 'Luca', 'Sipho'].map((n) => ({ name: n, g: 'man' as const })),
  ...['Alex', 'Robin', 'Sam', 'Kai', 'Rowan', 'Ash'].map((n) => ({ name: n, g: 'nonbinary' as const })),
]

const QUIRKS = [
  'hums when nervous', 'makes terrible puns under pressure', 'quotes their grandmother constantly', 'cannot stand silence',
  'keeps a running list of everything', 'apologises too much', 'is fiercely competitive about small things', 'tells long stories with no point',
  'notices everything and says little', 'laughs at the worst moments', 'is suspicious of anyone too friendly', 'always volunteers first',
  'turns every problem into a spreadsheet', 'is secretly very superstitious', 'eats when stressed', 'goes quiet when angry',
  'speaks bluntly and means well', 'can fix almost anything', 'cries easily and is not ashamed of it', 'loves an argument',
]

const BACKGROUNDS = [
  'grew up in a small coastal town', 'is the first in their family to go to university', 'raised two younger siblings', 'moved countries as a teenager',
  'recently came out of a long relationship', 'is quietly caring for a sick parent', 'ran marathons until a knee injury', 'was a choir singer for twenty years',
  'lost a lot of money in a failed business', 'volunteers at a food bank on Sundays', 'speaks four languages', 'spent two years travelling alone',
  'has a teenage kid who barely talks to them', 'grew up on a farm', 'survived a serious illness', 'was a semi-professional chess player',
]

const TOPS = ['#e57373', '#64b5f6', '#81c784', '#ffb74d', '#ba68c8', '#4db6ac', '#f06292', '#a1887f', '#90a4ae', '#ffd54f', '#7986cb', '#4dd0e1']

function rnd(a: number, b: number) {
  return a + Math.random() * (b - a)
}
function pick<T>(arr: readonly T[]): T {
  return arr[Math.floor(Math.random() * arr.length)]
}
function trait() {
  // a little more extreme than uniform: characters with edges are more interesting
  const v = Math.random()
  return Math.round((v < 0.5 ? v * v * 2 : 1 - (1 - v) * (1 - v) * 2) * 100) / 100
}

export function makePerson(id: number, role: RoleDef, startPlace: string, taken: Set<string>): Person {
  let n = pick(NAMES)
  for (let k = 0; k < 20 && taken.has(n.name); k++) n = pick(NAMES)
  taken.add(n.name)
  const g = /mother|grandmother|^bride$|maid/i.test(role.title) ? 'woman' : /^groom$/i.test(role.title) ? 'man' : n.g
  if (g !== n.g) {
    const pool = NAMES.filter((x) => x.g === g && !taken.has(x.name))
    if (pool.length) { taken.delete(n.name); n = pick(pool); taken.add(n.name) }
  }
  const age = Math.round(rnd(role.age[0], role.age[1]))
  return {
    id, name: n.name, age, gender: g, role,
    traits: { openness: trait(), conscientiousness: trait(), extraversion: trait(), agreeableness: trait(), neuroticism: trait() },
    quirk: pick(QUIRKS),
    backstory: pick(BACKGROUNDS),
    look: {
      skin: Math.floor(Math.random() * 6), hair: Math.floor(Math.random() * 7), style: Math.floor(Math.random() * 6),
      beard: g === 'man' && Math.random() < 0.4, glasses: Math.random() < 0.3 || age > 60, top: pick(TOPS), build: rnd(0.85, 1.15),
    },
    place: startPlace,
    health: 100, energy: Math.round(rnd(70, 95)), mood: Math.round(rnd(45, 70)), stress: Math.round(rnd(25, 50)), score: 0,
    stance: '', items: role.item ? [role.item] : [], opinions: {}, memory: [],
    thought: '', feeling: 'uneasy', lastSay: '', lastSayTo: '', lastAction: '', model: '', thinking: false, errors: 0,
    bubbleUntil: 0, emote: '', emoteUntil: 0,
    secretOut: false, secretHow: '', contribution: 0, vote: '',
  }
}

export function describeTraits(t: Person['traits']): string {
  const w = (v: number, lo: string, mid: string, hi: string) => (v < 0.33 ? lo : v > 0.66 ? hi : mid)
  return [
    w(t.openness, 'conventional', 'practical', 'curious and imaginative'),
    w(t.conscientiousness, 'disorganised', 'reasonably reliable', 'meticulous'),
    w(t.extraversion, 'reserved', 'sociable when needed', 'outgoing and loud'),
    w(t.agreeableness, 'blunt and combative', 'fair', 'warm and trusting'),
    w(t.neuroticism, 'unflappable', 'occasionally anxious', 'highly strung'),
  ].join(', ')
}
