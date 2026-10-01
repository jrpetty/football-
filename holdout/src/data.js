// All static game definitions: resources, skills, occupations, items,
// workstations, recipes, city locations, containers and zombies.

// ---------------------------------------------------------------- time
// One real second of simulation = 4 in-game minutes, so an in-game hour is
// 15 s and a full day is 6 minutes.
export const GAME_MIN_PER_SEC = 4
export const DAY_MIN = 24 * 60
export const SEC_PER_HOUR = 60 / GAME_MIN_PER_SEC

// ---------------------------------------------------------------- resources
export const RES = {
  food: { name: 'Food', color: '#e0a54a', sell: 2 },
  water: { name: 'Water', color: '#58a9dc', sell: 2 },
  wood: { name: 'Wood', color: '#b07a45', sell: 1 },
  metal: { name: 'Metal', color: '#a3adb6', sell: 3 },
  cloth: { name: 'Cloth', color: '#cdb892', sell: 2 },
  parts: { name: 'Parts', color: '#e08a3c', sell: 8 },
  fuel: { name: 'Fuel', color: '#d2553f', sell: 5 },
  ammo: { name: 'Ammo', color: '#d8c24a', sell: 1 },
  meds: { name: 'Meds', color: '#e8606e', sell: 12 },
  cash: { name: 'Cash', color: '#86c67a', sell: 1 },
}
export const RES_KEYS = Object.keys(RES)
export const STOCK_KEYS = RES_KEYS.filter((k) => k !== 'cash')

// ---------------------------------------------------------------- skills
export const SKILLS = {
  melee: { name: 'Melee', short: 'MEL', desc: 'Hand-to-hand damage and swing speed.' },
  ranged: { name: 'Ranged', short: 'RNG', desc: 'Firearm accuracy, damage and range.' },
  scavenge: { name: 'Scavenging', short: 'SCV', desc: 'Search speed and extra finds on runs.' },
  build: { name: 'Building', short: 'BLD', desc: 'Construction speed, Lumber Yard, Scrap Yard, dismantling.' },
  craft: { name: 'Crafting', short: 'CRF', desc: 'Workbench, Weapons Station, Clothing Station, Ammo Press.' },
  survival: { name: 'Survival', short: 'SRV', desc: 'Farming, water, cooking.' },
  medic: { name: 'Medicine', short: 'MED', desc: 'Infirmary healing, meds, reviving the downed.' },
  tech: { name: 'Engineering', short: 'ENG', desc: 'Generator, Biofuel Still, Radio, turrets.' },
}
export const SKILL_KEYS = Object.keys(SKILLS)
export const SKILL_MAX = 10
export const xpForLevel = (lvl) => Math.round(30 * Math.pow(lvl, 1.55))

// ---------------------------------------------------------------- occupations
// What each survivor did before the outbreak. Sets starting skills and a perk.
export const OCCUPATIONS = {
  doctor: { name: 'Doctor', skills: { medic: 5, craft: 1 }, perk: 'Heals twice as fast at the Infirmary and revives the downed in half the time.', fx: { healMult: 2, reviveMult: 0.5 }, stations: ['infirmary'] },
  nurse: { name: 'Nurse', skills: { medic: 4, survival: 1 }, perk: 'Squadmates within 4 m of a nurse on a run slowly regain health.', fx: { aura: 1.2 }, stations: ['infirmary'] },
  paramedic: { name: 'Paramedic', skills: { medic: 3, scavenge: 1, melee: 1 }, perk: 'Moves 12% faster and revives the downed 30% faster.', fx: { speed: 0.12, reviveMult: 0.7 }, stations: ['infirmary'] },
  police: { name: 'Police Officer', skills: { ranged: 4, melee: 2 }, perk: '+20% damage with pistols and revolvers.', fx: { pistolDmg: 0.2 } },
  soldier: { name: 'Soldier', skills: { ranged: 5, melee: 2 }, perk: '+20% damage with every firearm and +15 health.', fx: { gunDmg: 0.2, hp: 15 } },
  firefighter: { name: 'Firefighter', skills: { melee: 3, build: 2 }, perk: '+30 health and dismantles 35% faster. Axes hit 20% harder.', fx: { hp: 30, dismantle: 0.35, axeDmg: 0.2 } },
  farmer: { name: 'Farmer', skills: { survival: 5, build: 1 }, perk: 'Farm Plots produce 50% more when they work there.', fx: { station: { farm: 0.5 } }, stations: ['farm'] },
  chef: { name: 'Chef', skills: { survival: 3, craft: 1 }, perk: 'In the Cookhouse, stretches the food supply 60% further than a regular cook.', fx: { station: { kitchen: 0.6 } }, stations: ['kitchen'] },
  carpenter: { name: 'Carpenter', skills: { build: 4, craft: 2 }, perk: 'Lumber Yard +50%. Speeds up construction by 40%.', fx: { station: { lumber: 0.5 }, construct: 0.4 }, stations: ['lumber', 'workbench'] },
  mechanic: { name: 'Mechanic', skills: { craft: 3, tech: 3 }, perk: 'Scrap Yard and Weapons Station +40%. Strips cars for extra parts.', fx: { station: { scrapyard: 0.4, weapons: 0.4 }, carParts: 1 }, stations: ['scrapyard', 'weapons'] },
  electrician: { name: 'Electrician', skills: { tech: 5 }, perk: 'Operating the Generator adds 50% more power.', fx: { station: { generator: 0.5 } }, stations: ['generator'] },
  engineer: { name: 'Engineer', skills: { tech: 4, build: 2 }, perk: 'Operating the Biofuel Still, Ammo Press or Radio Tower +40%.', fx: { station: { still: 0.4, ammo: 0.4, radio: 0.4 } }, stations: ['still', 'ammo', 'radio'] },
  tailor: { name: 'Tailor', skills: { craft: 4, survival: 1 }, perk: 'Clothing Station +60%.', fx: { station: { clothing: 0.6 } }, stations: ['clothing'] },
  gunsmith: { name: 'Gunsmith', skills: { craft: 5, ranged: 1 }, perk: 'Weapons Station and Ammo Press +50%.', fx: { station: { weapons: 0.5, ammo: 0.5 } }, stations: ['weapons', 'ammo'] },
  hunter: { name: 'Hunter', skills: { ranged: 3, survival: 2, scavenge: 1 }, perk: 'Rifles and crossbows deal 25% more damage. Makes less noise.', fx: { rifleDmg: 0.25, noise: -0.3 } },
  athlete: { name: 'Athlete', skills: { melee: 2, scavenge: 2 }, perk: 'Moves 20% faster.', fx: { speed: 0.2 } },
  student: { name: 'Student', skills: { scavenge: 1, craft: 1 }, perk: 'Learns every skill 50% faster.', fx: { xp: 0.5 } },
  teacher: { name: 'Teacher', skills: { survival: 1, medic: 1 }, perk: 'At the Training Yard, everyone training learns 40% faster.', fx: { teach: 0.4 }, stations: ['training'] },
  plumber: { name: 'Plumber', skills: { survival: 2, tech: 2 }, perk: 'Water Filter +50%.', fx: { station: { filter: 0.5 } }, stations: ['filter'] },
  builder: { name: 'Construction Worker', skills: { build: 5, melee: 1 }, perk: 'Scrap Yard +30%. Speeds up construction by 30%.', fx: { station: { scrapyard: 0.3 }, construct: 0.3 }, stations: ['scrapyard', 'lumber'] },
  clerk: { name: 'Store Clerk', skills: { scavenge: 3 }, perk: 'While in camp, the black market pays 12% more for what you sell.', fx: { market: 0.12 } },
  excon: { name: 'Ex-Con', skills: { scavenge: 3, melee: 2 }, perk: 'Picks locks without lockpicks.', fx: { picklock: 1 } },
  drifter: { name: 'Drifter', skills: { scavenge: 2, survival: 2, melee: 1 }, perk: 'Finds 15% more loot on runs.', fx: { loot: 0.15 } },
  guard: { name: 'Security Guard', skills: { melee: 3, ranged: 2 }, perk: 'On a Watchtower, deals 30% more damage.', fx: { station: { watchtower: 0.3 } }, stations: ['watchtower'] },
}
export const OCC_KEYS = Object.keys(OCCUPATIONS)

// ---------------------------------------------------------------- traits
export const TRAITS = {
  tough: { name: 'Tough', good: true, desc: '+15 health.', fx: { hp: 15 } },
  quick: { name: 'Quick', good: true, desc: 'Moves 10% faster.', fx: { speed: 0.1 } },
  sharpshooter: { name: 'Sharpshooter', good: true, desc: '+12% firearm accuracy.', fx: { acc: 0.12 } },
  brawler: { name: 'Brawler', good: true, desc: '+20% melee damage.', fx: { meleeDmg: 0.2 } },
  quiet: { name: 'Quiet', good: true, desc: 'Searching and dismantling make half the noise.', fx: { noise: -0.5 } },
  packrat: { name: 'Packrat', good: true, desc: 'Finds 15% more loot.', fx: { loot: 0.15 } },
  learner: { name: 'Quick Learner', good: true, desc: 'Learns skills 25% faster.', fx: { xp: 0.25 } },
  hardworker: { name: 'Hard Worker', good: true, desc: 'Works 15% faster at every station.', fx: { work: 0.15 } },
  glutton: { name: 'Glutton', good: false, desc: 'Eats 50% more.', fx: { eat: 0.5 } },
  clumsy: { name: 'Clumsy', good: false, desc: 'Makes 50% more noise.', fx: { noise: 0.5 } },
  frail: { name: 'Frail', good: false, desc: '-15 health.', fx: { hp: -15 } },
  lazy: { name: 'Lazy', good: false, desc: 'Works 15% slower.', fx: { work: -0.15 } },
  coward: { name: 'Coward', good: false, desc: '-15% damage.', fx: { dmg: -0.15 } },
}
export const TRAIT_KEYS = Object.keys(TRAITS)

export const FIRST_NAMES = ['Mara', 'Jonah', 'Priya', 'Dev', 'Tomas', 'Ines', 'Callum', 'Rosa', 'Eli', 'Nadia', 'Owen', 'Hana', 'Marcus', 'Leah', 'Sami', 'Grace', 'Theo', 'Ada', 'Felix', 'Zara', 'Reuben', 'Maya', 'Isaac', 'Lena', 'Ruben', 'Tess', 'Kofi', 'Ivy', 'Luca', 'Nell', 'Arjun', 'June', 'Silas', 'Wren', 'Omar', 'Faye', 'Dmitri', 'Cleo', 'Hugo', 'Esme', 'Bram', 'Kira', 'Declan', 'Yara', 'Otto', 'Mina', 'Rafe', 'Lottie', 'Idris', 'Pia', 'Gus', 'Freya', 'Wes', 'Anya', 'Cal', 'Suki', 'Nico', 'Bea', 'Jude', 'Tove']
export const LAST_NAMES = ['Hale', 'Okafor', 'Reyes', 'Brandt', 'Novak', 'Castillo', 'Whitlock', 'Mensah', 'Kowalski', 'Ferreira', 'Doyle', 'Tanaka', 'Rahman', 'Lindqvist', 'Moreau', 'Achebe', 'Vance', 'Quinn', 'Petrov', 'Salazar', 'Harrow', 'Nakamura', 'Byrne', 'Adeyemi', 'Holt', 'Varga', 'Pryce', 'Lund', 'Marsh', 'Orlov', 'Keane', 'Sato', 'Ibarra', 'Crane', 'Mbeki', 'Foss', 'Laine', 'Duarte', 'Wilde', 'Park']

export const SKIN_TONES = ['#f1c9a5', '#e0ac85', '#c68b62', '#a86b45', '#7d4a2e', '#5a3421']
export const HAIR_COLORS = ['#1c1714', '#3b2a1e', '#6b4a2b', '#a57b45', '#d8b67a', '#8c8c8c', '#b3432e']
export const SHIRT_COLORS = ['#5a6b4a', '#6d5a44', '#3f5566', '#7a3b33', '#8a7a5a', '#4a4f57', '#6b6f3a', '#34464a', '#8c5a2e', '#57466b']
export const PANTS_COLORS = ['#2f3640', '#3d3a33', '#4a4234', '#2b3a33', '#51493d', '#1f2a36']

// ---------------------------------------------------------------- items
export const RARITY = {
  common: { name: 'Common', color: '#b8bcb5', rank: 0 },
  uncommon: { name: 'Uncommon', color: '#7cc36a', rank: 1 },
  rare: { name: 'Rare', color: '#58a6e8', rank: 2 },
  epic: { name: 'Epic', color: '#b57ae8', rank: 3 },
  legendary: { name: 'Legendary', color: '#f0a23a', rank: 4 },
}

// Weapons: dmg per hit, range in metres, rate = seconds between attacks,
// noise = alert radius in metres, model = what the survivor holds.
export const ITEMS = {
  fists: { name: 'Fists', slot: 'weapon', kind: 'melee', rarity: 'common', dmg: 7, range: 1.4, rate: 0.8, noise: 1, model: 'none', value: 0 },
  bat: { name: 'Baseball Bat', slot: 'weapon', kind: 'melee', rarity: 'common', dmg: 14, range: 1.6, rate: 0.9, noise: 2, model: 'bat', value: 30 },
  pipe: { name: 'Lead Pipe', slot: 'weapon', kind: 'melee', rarity: 'common', dmg: 15, range: 1.5, rate: 0.95, noise: 2, model: 'pipe', value: 30 },
  nailbat: { name: 'Nail Bat', slot: 'weapon', kind: 'melee', rarity: 'uncommon', dmg: 19, range: 1.6, rate: 0.9, noise: 2, model: 'nailbat', value: 70 },
  machete: { name: 'Machete', slot: 'weapon', kind: 'melee', rarity: 'uncommon', dmg: 22, range: 1.5, rate: 0.75, noise: 1, model: 'machete', value: 110 },
  axe: { name: 'Fire Axe', slot: 'weapon', kind: 'melee', rarity: 'rare', dmg: 36, range: 1.7, rate: 1.15, noise: 2, model: 'axe', value: 240, axe: true },
  katana: { name: 'Katana', slot: 'weapon', kind: 'melee', rarity: 'epic', dmg: 40, range: 1.8, rate: 0.7, noise: 1, model: 'katana', value: 650 },
  pistol: { name: '9mm Pistol', slot: 'weapon', kind: 'gun', rarity: 'common', dmg: 17, range: 8.5, rate: 0.55, noise: 10, model: 'pistol', value: 90, pistol: true },
  revolver: { name: 'Revolver', slot: 'weapon', kind: 'gun', rarity: 'uncommon', dmg: 32, range: 9, rate: 0.95, noise: 12, model: 'pistol', value: 190, pistol: true },
  shotgun: { name: 'Pump Shotgun', slot: 'weapon', kind: 'gun', rarity: 'uncommon', dmg: 48, range: 5.5, rate: 1.2, noise: 15, model: 'shotgun', value: 280, falloff: true },
  crossbow: { name: 'Crossbow', slot: 'weapon', kind: 'gun', rarity: 'rare', dmg: 52, range: 10, rate: 1.7, noise: 1, model: 'crossbow', value: 380, rifle: true },
  rifle: { name: 'Hunting Rifle', slot: 'weapon', kind: 'gun', rarity: 'rare', dmg: 62, range: 14, rate: 1.55, noise: 15, model: 'rifle', value: 460, rifle: true },
  smg: { name: 'SMG', slot: 'weapon', kind: 'gun', rarity: 'rare', dmg: 11, range: 7.5, rate: 0.13, noise: 12, model: 'smg', value: 480 },
  ar: { name: 'Assault Rifle', slot: 'weapon', kind: 'gun', rarity: 'epic', dmg: 21, range: 11.5, rate: 0.16, noise: 14, model: 'rifle', value: 950 },

  jacket: { name: 'Leather Jacket', slot: 'armor', rarity: 'common', hp: 15, dr: 0.08, value: 60, color: '#4a3526' },
  vest: { name: 'Kevlar Vest', slot: 'armor', rarity: 'uncommon', hp: 30, dr: 0.18, value: 240, color: '#2f3a2e' },
  riot: { name: 'Riot Armor', slot: 'armor', rarity: 'rare', hp: 50, dr: 0.28, speed: -0.08, value: 520, color: '#1f2530' },
  military: { name: 'Combat Armor', slot: 'armor', rarity: 'epic', hp: 70, dr: 0.36, value: 1000, color: '#4b5236' },

  backpack: { name: 'Backpack', slot: 'gear', rarity: 'uncommon', loot: 0.25, value: 150, desc: 'Finds 25% more loot.' },
  flashlight: { name: 'Flashlight', slot: 'gear', rarity: 'common', search: 0.3, value: 50, desc: 'Searches 30% faster.' },
  lockpicks: { name: 'Lockpicks', slot: 'gear', rarity: 'uncommon', picklock: 1, value: 120, desc: 'Opens locked safes and gun lockers quietly.' },
  medkit: { name: 'First Aid Kit', slot: 'gear', rarity: 'common', medkit: 1, value: 70, desc: 'Heals 50% health once, when badly hurt on a run.' },
  walkie: { name: 'Walkie-Talkie', slot: 'gear', rarity: 'rare', walkie: 1, value: 300, desc: 'Squad hears the horde coming: it arrives 45 s later on runs.' },
  shoes: { name: 'Running Shoes', slot: 'gear', rarity: 'common', speed: 0.15, value: 60, desc: 'Moves 15% faster.' },
  toolkit: { name: 'Toolkit', slot: 'gear', rarity: 'uncommon', dismantle: 0.4, value: 140, desc: 'Dismantles 40% faster and salvages more.' },
}

export function itemStatLine(id) {
  const it = ITEMS[id]
  if (!it) return ''
  if (it.slot === 'weapon') {
    const dps = (it.dmg / it.rate).toFixed(0)
    return `${it.dmg} dmg · ${it.range} m · ${dps} dps${it.kind === 'gun' ? ' · uses ammo' : ''}`
  }
  if (it.slot === 'armor') return `+${it.hp} health · ${Math.round(it.dr * 100)}% damage reduction${it.speed ? ` · ${Math.round(it.speed * 100)}% speed` : ''}`
  return it.desc || ''
}

// ---------------------------------------------------------------- workstations
// cost[i] = cost to build/upgrade to level i+1. time[i] = build seconds.
// workers[i] = job slots at level i+1. auto = level at which the station can
// run on generator power without workers (autoPower = power it draws).
export const STATIONS = {
  campfire: {
    name: 'Campfire', cat: 'living', size: [2, 2], levels: 1, fixed: true, skill: null,
    desc: 'The heart of the camp. Idle survivors gather here and the fire keeps spirits up.',
    cost: [{}], time: [0], workers: [0],
  },
  bunkhouse: {
    name: 'Bunkhouse', cat: 'living', size: [3, 3], levels: 3, skill: null,
    desc: 'Beds for survivors. You can only accept newcomers if there is a free bed.',
    cost: [{ wood: 40 }, { wood: 60, metal: 20, cloth: 10 }, { wood: 80, metal: 45, cloth: 25, parts: 6 }],
    time: [20, 45, 80], workers: [0, 0, 0], beds: [4, 6, 8],
  },
  storage: {
    name: 'Storage Depot', cat: 'living', size: [3, 2], levels: 3, skill: null,
    desc: 'Raises how much of each resource the camp can hold.',
    cost: [{ wood: 30, metal: 10 }, { wood: 60, metal: 30 }, { wood: 90, metal: 60, parts: 8 }],
    time: [15, 40, 70], workers: [0, 0, 0], cap: [100, 220, 400],
  },
  kitchen: {
    name: 'Cookhouse', cat: 'living', size: [3, 2], levels: 3, skill: 'survival',
    desc: 'A cook stretches rations so the whole camp eats less. Chefs are the best at it.',
    cost: [{ wood: 40, metal: 10 }, { wood: 50, metal: 25, cloth: 10 }, { wood: 60, metal: 40, parts: 8 }],
    time: [25, 50, 80], workers: [1, 1, 2], saving: [0.15, 0.22, 0.3],
  },
  infirmary: {
    name: 'Infirmary', cat: 'living', size: [3, 2], levels: 3, skill: 'medic',
    desc: 'Heals injured survivors. With no patients, the medic turns cloth and water into meds.',
    cost: [{ wood: 30, cloth: 20 }, { wood: 40, metal: 20, cloth: 30 }, { metal: 45, cloth: 40, parts: 10 }],
    time: [25, 50, 90], workers: [1, 1, 2], heal: [0.5, 0.8, 1.2], beds: [2, 3, 4],
    recipe: { in: { cloth: 3, water: 1 }, out: { meds: 1 }, time: 40 },
  },
  training: {
    name: 'Training Yard', cat: 'living', size: [3, 3], levels: 3, skill: null,
    desc: 'Survivors assigned here drill their weakest combat skill. Teachers make everyone learn faster.',
    cost: [{ wood: 35, cloth: 10 }, { wood: 50, metal: 20, cloth: 15 }, { wood: 60, metal: 40, parts: 8 }],
    time: [20, 45, 80], workers: [2, 3, 4], xpRate: [0.9, 1.3, 1.8],
  },
  radio: {
    name: 'Radio Tower', cat: 'living', size: [2, 2], levels: 3, skill: 'tech',
    desc: 'Broadcasts to other survivors. Newcomers arrive more often and with better skills. An operator helps.',
    cost: [{ metal: 30, parts: 10 }, { metal: 50, parts: 20 }, { metal: 80, parts: 35, fuel: 10 }],
    time: [30, 60, 100], workers: [1, 1, 1], recruit: [0.7, 0.5, 0.36],
  },

  farm: {
    name: 'Farm Plot', cat: 'production', size: [3, 3], levels: 3, skill: 'survival',
    desc: 'Grows food from water. Farmers double as good as anyone.',
    cost: [{ wood: 25 }, { wood: 40, metal: 10 }, { wood: 50, metal: 25, parts: 8 }],
    time: [15, 40, 70], workers: [1, 2, 3], auto: 3, autoPower: 1, autoRate: 1.5,
    recipe: { in: { water: 0.5 }, out: { food: 1 }, time: [42, 34, 28] },
  },
  collector: {
    name: 'Rain Collector', cat: 'production', size: [2, 2], levels: 3, skill: null,
    desc: 'Barrels and tarps that fill with rainwater on their own. No workers needed.',
    cost: [{ wood: 15, cloth: 5 }, { wood: 25, metal: 10, cloth: 10 }, { metal: 30, parts: 5 }],
    time: [10, 30, 60], workers: [0, 0, 0], passive: { water: [3, 6, 10] }, // per in-game day
  },
  filter: {
    name: 'Water Filter', cat: 'production', size: [2, 2], levels: 3, skill: 'survival',
    desc: 'Pumps and filters well water. Plumbers get the most out of it.',
    cost: [{ wood: 20, metal: 15, cloth: 5 }, { metal: 30, parts: 4 }, { metal: 45, parts: 10 }],
    time: [20, 40, 70], workers: [1, 1, 2], auto: 3, autoPower: 1, autoRate: 1.5,
    recipe: { in: {}, out: { water: 1 }, time: [36, 29, 23] },
  },
  lumber: {
    name: 'Lumber Yard', cat: 'production', size: [3, 3], levels: 3, skill: 'build',
    desc: 'Cuts felled trees and broken furniture into usable wood.',
    cost: [{ wood: 20, metal: 5 }, { wood: 40, metal: 20 }, { metal: 50, parts: 12, fuel: 5 }],
    time: [15, 40, 70], workers: [1, 2, 3], auto: 3, autoPower: 2, autoRate: 2,
    recipe: { in: {}, out: { wood: 1 }, time: [9, 7.5, 6] },
  },
  scrapyard: {
    name: 'Scrap Yard', cat: 'production', size: [3, 3], levels: 3, skill: 'build',
    desc: 'Strips wrecks for metal, and sometimes a usable part.',
    cost: [{ wood: 30 }, { wood: 40, metal: 20 }, { metal: 50, parts: 12, fuel: 5 }],
    time: [15, 40, 70], workers: [1, 2, 3], auto: 3, autoPower: 2, autoRate: 2,
    recipe: { in: {}, out: { metal: 1 }, time: [13, 11, 9], bonus: { parts: 0.12 } },
  },
  still: {
    name: 'Biofuel Still', cat: 'production', size: [2, 2], levels: 3, skill: 'tech',
    desc: 'Ferments food and water into fuel for the generator.',
    cost: [{ metal: 30, parts: 6 }, { metal: 45, parts: 12 }, { metal: 60, parts: 20 }],
    time: [25, 50, 80], workers: [1, 1, 2], auto: 2, autoPower: 1, autoRate: 1,
    recipe: { in: { food: 2, water: 1 }, out: { fuel: 1 }, time: [30, 24, 18] },
  },
  generator: {
    name: 'Generator', cat: 'production', size: [2, 2], levels: 3, skill: 'tech',
    desc: 'Burns fuel to power automated stations, turrets and floodlights. An operator adds power.',
    cost: [{ metal: 40, parts: 12 }, { metal: 60, parts: 20 }, { metal: 90, parts: 35 }],
    time: [30, 60, 100], workers: [1, 1, 1], power: [4, 8, 14], burn: [40, 34, 28], // seconds per fuel at full load
  },

  workbench: {
    name: 'Workbench', cat: 'crafting', size: [3, 2], levels: 3, skill: 'craft',
    desc: 'The crafting table. Makes parts, melee weapons and tools.',
    cost: [{ wood: 30 }, { wood: 40, metal: 25, parts: 4 }, { metal: 50, parts: 12 }],
    time: [15, 40, 70], workers: [1, 1, 2], auto: 3, autoPower: 1, autoRate: 1, queue: [2, 3, 4],
  },
  weapons: {
    name: 'Weapons Station', cat: 'crafting', size: [3, 2], levels: 3, skill: 'craft', req: { workbench: 2 },
    desc: 'A gunsmith\'s bench for building firearms from scrap.',
    cost: [{ wood: 30, metal: 40, parts: 10 }, { metal: 60, parts: 20 }, { metal: 90, parts: 35 }],
    time: [40, 70, 110], workers: [1, 1, 2], auto: 3, autoPower: 2, autoRate: 1, queue: [2, 3, 4],
  },
  clothing: {
    name: 'Clothing Station', cat: 'crafting', size: [3, 2], levels: 3, skill: 'craft',
    desc: 'Sewing tables for jackets, armor and backpacks.',
    cost: [{ wood: 30, cloth: 20 }, { wood: 40, metal: 20, cloth: 30 }, { metal: 50, cloth: 40, parts: 10 }],
    time: [20, 45, 80], workers: [1, 1, 2], auto: 3, autoPower: 1, autoRate: 1, queue: [2, 3, 4],
  },
  ammo: {
    name: 'Ammo Press', cat: 'crafting', size: [2, 2], levels: 3, skill: 'craft',
    desc: 'Presses scrap metal and fuel into rounds for every gun in camp.',
    cost: [{ metal: 35, parts: 8 }, { metal: 50, parts: 15 }, { metal: 70, parts: 25 }],
    time: [30, 60, 90], workers: [1, 1, 2], auto: 2, autoPower: 2, autoRate: 1.2,
    recipe: { in: { metal: 1, fuel: 0.2 }, out: { ammo: 6 }, time: [16, 13, 10] },
  },

  watchtower: {
    name: 'Watchtower', cat: 'defense', size: [2, 2], levels: 3, skill: 'ranged',
    desc: 'A guard up here fires on the horde with extra range and damage, and spots hordes early.',
    cost: [{ wood: 50 }, { wood: 60, metal: 25 }, { metal: 60, parts: 8 }],
    time: [25, 50, 80], workers: [1, 1, 2], towerDmg: [0.25, 0.45, 0.7],
  },
  turret: {
    name: 'Auto-Turret', cat: 'defense', size: [1, 1], levels: 3, skill: null, req: { generator: 1 },
    desc: 'Fires at anything that shambles near the fence. Uses camp ammo and generator power.',
    cost: [{ metal: 50, parts: 25 }, { metal: 70, parts: 35 }, { metal: 100, parts: 50 }],
    time: [40, 70, 110], workers: [0, 0, 0], power: 2, dmg: [14, 20, 28], rate: [0.45, 0.35, 0.25], range: [10, 12, 14],
  },
  floodlight: {
    name: 'Floodlight', cat: 'defense', size: [1, 1], levels: 1, skill: null, req: { generator: 1 },
    desc: 'Lights the perimeter at night. Defenders nearby shoot 25% more accurately in the dark.',
    cost: [{ metal: 12, parts: 5 }], time: [15], workers: [0], power: 1,
  },
}
export const STATION_CATS = [
  { id: 'living', name: 'Camp' },
  { id: 'production', name: 'Production' },
  { id: 'crafting', name: 'Crafting' },
  { id: 'defense', name: 'Defense' },
]

export const FENCE = [
  { name: 'Scrap Fence', hp: 60, cost: {} },
  { name: 'Log Palisade', hp: 120, cost: { wood: 160 }, time: 60 },
  { name: 'Sheet-Metal Wall', hp: 220, cost: { metal: 200, parts: 10 }, time: 110 },
  { name: 'Fortified Wall', hp: 360, cost: { metal: 320, parts: 30, wood: 100 }, time: 160 },
]

// Crafting recipes. `time` = work-seconds at 100% efficiency.
export const RECIPES = [
  { id: 'parts', station: 'workbench', lvl: 1, out: { parts: 2 }, in: { metal: 4, wood: 2 }, time: 18 },
  { id: 'bat', station: 'workbench', lvl: 1, item: 'bat', in: { wood: 10 }, time: 25 },
  { id: 'nailbat', station: 'workbench', lvl: 1, item: 'nailbat', in: { wood: 10, metal: 4 }, time: 35 },
  { id: 'lockpicks', station: 'workbench', lvl: 1, item: 'lockpicks', in: { metal: 5, parts: 2 }, time: 30 },
  { id: 'medkit', station: 'workbench', lvl: 1, item: 'medkit', in: { cloth: 6, meds: 2 }, time: 25 },
  { id: 'machete', station: 'workbench', lvl: 2, item: 'machete', in: { metal: 12, wood: 2, parts: 1 }, time: 45 },
  { id: 'flashlight', station: 'workbench', lvl: 2, item: 'flashlight', in: { parts: 4, metal: 3 }, time: 30 },
  { id: 'toolkit', station: 'workbench', lvl: 2, item: 'toolkit', in: { metal: 15, parts: 6 }, time: 45 },
  { id: 'axe', station: 'workbench', lvl: 3, item: 'axe', in: { metal: 18, wood: 8, parts: 3 }, time: 70 },
  { id: 'walkie', station: 'workbench', lvl: 3, item: 'walkie', in: { parts: 20, metal: 10 }, time: 80 },

  { id: 'pistol', station: 'weapons', lvl: 1, item: 'pistol', in: { metal: 20, parts: 8 }, time: 50 },
  { id: 'revolver', station: 'weapons', lvl: 1, item: 'revolver', in: { metal: 26, parts: 11 }, time: 65 },
  { id: 'crossbow', station: 'weapons', lvl: 2, item: 'crossbow', in: { wood: 20, metal: 10, parts: 8 }, time: 70 },
  { id: 'shotgun', station: 'weapons', lvl: 2, item: 'shotgun', in: { metal: 30, wood: 10, parts: 12 }, time: 80 },
  { id: 'rifle', station: 'weapons', lvl: 2, item: 'rifle', in: { metal: 36, wood: 14, parts: 16 }, time: 95 },
  { id: 'smg', station: 'weapons', lvl: 3, item: 'smg', in: { metal: 45, parts: 24 }, time: 110 },
  { id: 'ar', station: 'weapons', lvl: 3, item: 'ar', in: { metal: 60, parts: 36, fuel: 10 }, time: 140 },

  { id: 'jacket', station: 'clothing', lvl: 1, item: 'jacket', in: { cloth: 16 }, time: 30 },
  { id: 'shoes', station: 'clothing', lvl: 1, item: 'shoes', in: { cloth: 10, parts: 2 }, time: 25 },
  { id: 'backpack', station: 'clothing', lvl: 1, item: 'backpack', in: { cloth: 20, wood: 2 }, time: 40 },
  { id: 'vest', station: 'clothing', lvl: 2, item: 'vest', in: { cloth: 24, metal: 14, parts: 6 }, time: 60 },
  { id: 'riot', station: 'clothing', lvl: 3, item: 'riot', in: { cloth: 30, metal: 40, parts: 14 }, time: 90 },
  { id: 'military', station: 'clothing', lvl: 3, item: 'military', in: { cloth: 40, metal: 50, parts: 24 }, time: 120 },
]

// ---------------------------------------------------------------- city
// Loot pools used by containers. Each entry: { r: resource, n: [min,max], w }
// or { i: itemId, w }.
const P = {
  kitchen: [{ r: 'food', n: [2, 6], w: 6 }, { r: 'water', n: [2, 5], w: 5 }, { r: 'cloth', n: [1, 3], w: 1 }],
  fridge: [{ r: 'food', n: [3, 7], w: 6 }, { r: 'water', n: [2, 6], w: 5 }],
  closet: [{ r: 'cloth', n: [3, 7], w: 6 }, { i: 'jacket', w: 0.5 }, { i: 'backpack', w: 0.25 }, { i: 'shoes', w: 0.4 }, { r: 'cash', n: [5, 20], w: 1 }],
  desk: [{ r: 'cash', n: [5, 25], w: 4 }, { r: 'parts', n: [1, 2], w: 2 }, { r: 'cloth', n: [1, 3], w: 1 }, { i: 'flashlight', w: 0.4 }, { i: 'pistol', w: 0.12 }],
  books: [{ r: 'cloth', n: [1, 3], w: 2 }, { r: 'cash', n: [3, 12], w: 2 }, { r: 'wood', n: [2, 4], w: 2 }],
  trash: [{ r: 'cloth', n: [1, 3], w: 3 }, { r: 'metal', n: [1, 3], w: 3 }, { r: 'parts', n: [1, 1], w: 1 }, { r: 'food', n: [1, 2], w: 2 }],
  shelf: [{ r: 'food', n: [3, 8], w: 6 }, { r: 'water', n: [3, 7], w: 5 }, { r: 'meds', n: [1, 2], w: 0.6 }, { r: 'cloth', n: [1, 4], w: 1 }],
  register: [{ r: 'cash', n: [20, 60], w: 1 }],
  tools: [{ r: 'metal', n: [3, 7], w: 4 }, { r: 'parts', n: [1, 4], w: 3 }, { r: 'wood', n: [3, 8], w: 3 }, { i: 'pipe', w: 0.5 }, { i: 'bat', w: 0.3 }, { i: 'toolkit', w: 0.2 }, { i: 'lockpicks', w: 0.15 }, { i: 'machete', w: 0.12 }],
  medcab: [{ r: 'meds', n: [1, 4], w: 6 }, { r: 'cloth', n: [2, 4], w: 2 }, { i: 'medkit', w: 0.6 }],
  locker: [{ r: 'cloth', n: [2, 5], w: 3 }, { r: 'ammo', n: [8, 24], w: 3 }, { r: 'cash', n: [10, 30], w: 2 }, { i: 'jacket', w: 0.4 }, { i: 'vest', w: 0.25 }, { i: 'flashlight', w: 0.4 }, { i: 'pistol', w: 0.25 }, { i: 'walkie', w: 0.08 }],
  gunlocker: [{ r: 'ammo', n: [20, 50], w: 5 }, { i: 'pistol', w: 1 }, { i: 'revolver', w: 0.6 }, { i: 'shotgun', w: 0.5 }, { i: 'rifle', w: 0.3 }, { i: 'smg', w: 0.2 }, { i: 'ar', w: 0.07 }, { i: 'vest', w: 0.3 }],
  safe: [{ r: 'cash', n: [80, 220], w: 5 }, { r: 'meds', n: [2, 5], w: 1 }, { i: 'revolver', w: 0.4 }, { i: 'walkie', w: 0.2 }, { i: 'katana', w: 0.04 }],
  crate: [{ r: 'wood', n: [5, 12], w: 3 }, { r: 'metal', n: [5, 12], w: 3 }, { r: 'parts', n: [2, 5], w: 2 }, { r: 'cloth', n: [4, 9], w: 2 }, { r: 'fuel', n: [2, 5], w: 1 }],
  milcrate: [{ r: 'ammo', n: [30, 80], w: 5 }, { r: 'parts', n: [4, 9], w: 2 }, { r: 'meds', n: [2, 5], w: 1 }, { i: 'rifle', w: 0.4 }, { i: 'smg', w: 0.35 }, { i: 'ar', w: 0.2 }, { i: 'military', w: 0.12 }, { i: 'riot', w: 0.2 }, { i: 'walkie', w: 0.3 }],
  car: [{ r: 'fuel', n: [2, 6], w: 4 }, { r: 'parts', n: [1, 3], w: 2 }, { r: 'cash', n: [5, 20], w: 1 }, { r: 'water', n: [1, 3], w: 1 }],
  pump: [{ r: 'fuel', n: [4, 10], w: 1 }],
  dumpster: [{ r: 'food', n: [1, 3], w: 2 }, { r: 'cloth', n: [2, 5], w: 3 }, { r: 'metal', n: [2, 4], w: 3 }, { r: 'wood', n: [2, 5], w: 2 }, { r: 'parts', n: [1, 2], w: 1 }],
}

// Containers you can search or dismantle on a run.
// time = search seconds; strip = dismantle yield; kind = model id.
export const CONTAINERS = {
  fridge: { name: 'Fridge', pool: P.fridge, rolls: [1, 2], time: 2.5, strip: { metal: [3, 6], parts: [0, 1] }, model: 'fridge' },
  cabinet: { name: 'Kitchen Cabinet', pool: P.kitchen, rolls: [1, 2], time: 2.5, strip: { wood: [3, 6] }, model: 'cabinet' },
  wardrobe: { name: 'Wardrobe', pool: P.closet, rolls: [1, 2], time: 3, strip: { wood: [4, 8] }, model: 'wardrobe' },
  desk: { name: 'Desk', pool: P.desk, rolls: [1, 2], time: 2.5, strip: { wood: [3, 6], metal: [0, 2] }, model: 'desk' },
  bookshelf: { name: 'Bookshelf', pool: P.books, rolls: [1, 1], time: 2, strip: { wood: [4, 7] }, model: 'bookshelf' },
  trash: { name: 'Trash Can', pool: P.trash, rolls: [1, 1], time: 1.5, strip: { metal: [1, 3] }, model: 'trash' },
  shelf: { name: 'Store Shelf', pool: P.shelf, rolls: [1, 3], time: 3, strip: { metal: [3, 6], wood: [1, 3] }, model: 'shelf' },
  register: { name: 'Cash Register', pool: P.register, rolls: [1, 1], time: 2, strip: { parts: [1, 2], metal: [1, 2] }, model: 'register' },
  toolrack: { name: 'Tool Rack', pool: P.tools, rolls: [1, 3], time: 3, strip: { metal: [3, 6], wood: [2, 4] }, model: 'toolrack' },
  medcab: { name: 'Medicine Cabinet', pool: P.medcab, rolls: [1, 2], time: 2.5, strip: { metal: [1, 3] }, model: 'medcab' },
  locker: { name: 'Locker', pool: P.locker, rolls: [1, 2], time: 3, strip: { metal: [4, 7] }, model: 'locker' },
  gunlocker: { name: 'Gun Locker', pool: P.gunlocker, rolls: [2, 3], time: 4, strip: { metal: [6, 10], parts: [1, 3] }, model: 'gunlocker', locked: true },
  safe: { name: 'Safe', pool: P.safe, rolls: [2, 3], time: 4, strip: { metal: [8, 12], parts: [1, 2] }, model: 'safe', locked: true },
  crate: { name: 'Supply Crate', pool: P.crate, rolls: [2, 3], time: 3, strip: { wood: [5, 9] }, model: 'crate' },
  milcrate: { name: 'Military Crate', pool: P.milcrate, rolls: [2, 3], time: 4, strip: { wood: [4, 7], metal: [2, 5] }, model: 'milcrate' },
  car: { name: 'Abandoned Car', pool: P.car, rolls: [1, 2], time: 3.5, strip: { metal: [8, 14], parts: [2, 4], cloth: [1, 3] }, model: 'car', big: true },
  pump: { name: 'Fuel Pump', pool: P.pump, rolls: [1, 1], time: 4, strip: { metal: [5, 9], parts: [1, 2] }, model: 'pump' },
  dumpster: { name: 'Dumpster', pool: P.dumpster, rolls: [1, 2], time: 3, strip: { metal: [6, 10] }, model: 'dumpster' },
}

// Location types on the city map. level = danger tier 1–5.
// rooms: weighted container kinds placed inside; decor: non-lootable props.
export const LOCATIONS = {
  house: { name: 'House', level: 1, names: ['Maple St. House', 'Birch Lane House', 'Harlow Cottage', 'Duplex on 3rd', 'Corner Bungalow', 'Pine Ave. House'], size: [12, 10], floor: 'wood', wall: '#b9a58a', containers: { fridge: 2, cabinet: 3, wardrobe: 2, desk: 1, bookshelf: 1, trash: 1, safe: 0.15 }, decor: ['bed', 'sofa', 'table'], blurb: 'Kitchens and closets. Food, water and cloth.' },
  apartment: { name: 'Apartments', level: 1, names: ['Rosewood Apartments', 'Kestrel Flats', 'Elm Court', 'The Carlyle'], size: [16, 11], floor: 'wood', wall: '#a99c8c', containers: { fridge: 2, cabinet: 3, wardrobe: 3, desk: 1, bookshelf: 1, trash: 2, medcab: 0.6 }, decor: ['bed', 'sofa', 'table'], blurb: 'Lots of small rooms. Food, cloth, a little cash.' },
  store: { name: 'Corner Store', level: 1, names: ['Quik Stop', 'Sunny Mart', 'Lucky 24', 'Corner Deli'], size: [12, 9], floor: 'tile', wall: '#c2b49a', containers: { shelf: 6, register: 1, fridge: 2, trash: 1 }, aisles: true, blurb: 'Shelves of snacks and bottled water.' },
  diner: { name: 'Diner', level: 2, names: ["Rosie's Diner", 'Route 9 Grill', 'The Blue Plate'], size: [14, 10], floor: 'tile', wall: '#c9a98f', containers: { fridge: 3, cabinet: 4, register: 1, trash: 2, locker: 1 }, decor: ['table', 'table', 'booth'], blurb: 'Walk-in fridges and pantry stock.' },
  gas: { name: 'Gas Station', level: 2, names: ['Petro Plus', 'Gulf Line Fuel', 'Highway 12 Gas'], size: [11, 8], floor: 'tile', wall: '#bcb3a3', containers: { shelf: 4, register: 1, fridge: 2, toolrack: 1 }, outside: { pump: 3, car: 2 }, blurb: 'Fuel pumps, snacks and car parts.' },
  hardware: { name: 'Hardware Store', level: 2, names: ["Hank's Hardware", 'Ironside Supply', 'Builders Depot'], size: [15, 11], floor: 'concrete', wall: '#a69a86', containers: { toolrack: 6, crate: 3, register: 1, shelf: 2 }, aisles: true, blurb: 'Wood, metal, parts, and the odd blade.' },
  pharmacy: { name: 'Pharmacy', level: 3, names: ['CarePlus Pharmacy', 'Main St. Chemist', 'Wellway Drugs'], size: [12, 10], floor: 'tile', wall: '#d2cdc2', containers: { medcab: 5, shelf: 4, register: 1, safe: 0.4 }, aisles: true, blurb: 'Medicine cabinets. Meds and first aid kits.' },
  supermarket: { name: 'Supermarket', level: 3, names: ['FreshWay Market', 'Grand Grocer', 'ValuMart'], size: [20, 14], floor: 'tile', wall: '#c6bca6', containers: { shelf: 12, fridge: 5, register: 2, crate: 2, trash: 2 }, aisles: true, blurb: 'Huge food and water stocks. Big and crowded.' },
  garage: { name: 'Auto Garage', level: 3, names: ["Dale's Auto Body", 'Precision Motors', 'Southside Garage'], size: [16, 11], floor: 'concrete', wall: '#9f9586', containers: { toolrack: 5, crate: 2, locker: 2, desk: 1 }, outside: { car: 4 }, inside: { car: 2 }, blurb: 'Cars to strip, fuel, metal and parts.' },
  hospital: { name: 'Hospital', level: 4, names: ['St. Agnes Hospital', 'County General', 'Mercy Medical'], size: [22, 15], floor: 'tile', wall: '#d8d4cc', containers: { medcab: 8, locker: 4, desk: 3, wardrobe: 2, safe: 0.4 }, decor: ['bed', 'bed', 'bed', 'table'], blurb: 'Meds, first aid, and far too many patients.' },
  gunstore: { name: 'Gun Store', level: 4, names: ['Liberty Arms', 'Deadeye Outfitters', 'Ridgeline Firearms'], size: [13, 10], floor: 'concrete', wall: '#8f8578', containers: { gunlocker: 4, locker: 2, crate: 2, register: 1, safe: 0.6 }, blurb: 'Locked gun racks. Bring lockpicks.' },
  warehouse: { name: 'Warehouse', level: 4, names: ['Dockside Warehouse', 'Northgate Logistics', 'Cold Storage 7'], size: [22, 15], floor: 'concrete', wall: '#8e8a82', containers: { crate: 12, toolrack: 2, locker: 2, desk: 1 }, inside: { car: 1 }, blurb: 'Pallets of raw materials. Wide open floor.' },
  police: { name: 'Police Station', level: 5, names: ['7th Precinct', 'Harbor PD', 'Central Station'], size: [20, 14], floor: 'tile', wall: '#9aa0a6', containers: { gunlocker: 4, locker: 6, desk: 4, safe: 0.8, milcrate: 1 }, outside: { car: 3 }, blurb: 'Armor, guns, ammo and radios. Heavily infected.' },
  military: { name: 'Military Checkpoint', level: 5, names: ['Checkpoint Bravo', 'FOB Harlan', 'Quarantine Gate 3'], size: [20, 15], floor: 'concrete', wall: '#6f7560', containers: { milcrate: 7, gunlocker: 3, locker: 3, crate: 3 }, outside: { car: 2 }, blurb: 'Military crates with the best gear in the city.' },
}
export const LEVEL_COLORS = ['#7cc36a', '#c8c64a', '#e8a33d', '#e36b3a', '#d8384a']

// ---------------------------------------------------------------- zombies
export const ZOMBIES = {
  walker: { name: 'Walker', hp: 50, speed: 1.25, dmg: 8, rate: 1.3, scale: 1, sight: 6, xp: 4 },
  runner: { name: 'Runner', hp: 32, speed: 3.1, dmg: 6, rate: 0.9, scale: 0.92, sight: 8, xp: 5 },
  brute: { name: 'Brute', hp: 200, speed: 1.0, dmg: 22, rate: 1.7, scale: 1.4, sight: 5, xp: 14 },
}
export function zombieMix(level) {
  // weights for walker/runner/brute by danger level
  return [
    { t: 'walker', w: 10 },
    { t: 'runner', w: level >= 2 ? 1 + level * 1.2 : 0.4 },
    { t: 'brute', w: level >= 3 ? level * 0.7 - 1 : 0 },
  ]
}

// Horde sizes for camp attacks.
export const HORDES = [
  { id: 'small', name: 'Small horde', min: 4, max: 7 },
  { id: 'medium', name: 'Horde', min: 9, max: 14 },
  { id: 'large', name: 'Large horde', min: 16, max: 24 },
  { id: 'huge', name: 'Massive horde', min: 28, max: 40 },
]

// ---------------------------------------------------------------- goals
// Objectives guide the early game and pay out rewards.
export const GOALS = [
  { id: 'assignFarm', text: 'Assign a survivor to the Farm Plot', reward: { cash: 40 } },
  { id: 'buildFilter', text: 'Build a Water Filter', reward: { wood: 20, metal: 10 } },
  { id: 'firstRun', text: 'Send a squad on a supply run', reward: { cash: 60 } },
  { id: 'buildLumber', text: 'Build a Lumber Yard or Scrap Yard', reward: { parts: 4 } },
  { id: 'surviveHorde', text: 'Survive your first horde', reward: { ammo: 40, meds: 3 } },
  { id: 'recruit', text: 'Recruit a new survivor', reward: { food: 25 } },
  { id: 'craft', text: 'Craft an item at a crafting station', reward: { cash: 80 } },
  { id: 'loot3', text: 'Loot a level 3 location', reward: { parts: 10 } },
  { id: 'generator', text: 'Build a Generator', reward: { fuel: 20 } },
  { id: 'automate', text: 'Automate a station with generator power', reward: { cash: 200 } },
  { id: 'pop10', text: 'Grow the camp to 10 survivors', reward: { cash: 250 } },
  { id: 'fence2', text: 'Upgrade the perimeter to a Log Palisade', reward: { ammo: 80 } },
  { id: 'day7', text: 'Survive 7 days', reward: { cash: 300, parts: 15 } },
  { id: 'loot5', text: 'Loot a level 5 location', reward: { cash: 400 } },
  { id: 'pop20', text: 'Grow the camp to 20 survivors', reward: { cash: 600 } },
  { id: 'day30', text: 'Survive 30 days', reward: { cash: 1500 } },
]
