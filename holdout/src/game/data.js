// Every static definition in the game: time, resources, skills, jobs, traits,
// items (quality, durability, mods), workstations and their recipes,
// expansions, city locations, containers, zombies, hordes and goals.

// ---------------------------------------------------------------- time
// One real second = 3 in-game minutes: an hour is 20 s, a day is 8 minutes.
export const GAME_MIN_PER_SEC = 3
export const DAY_MIN = 24 * 60
export const SEC_PER_HOUR = 60 / GAME_MIN_PER_SEC
export const SEC_PER_DAY = DAY_MIN / GAME_MIN_PER_SEC

// ---------------------------------------------------------------- resources
// cat: needs | materials | ammo | supplies | cash. capMul scales storage.
export const RES = {
  food: { name: 'Food', cat: 'needs', color: '#e0a54a', sell: 2, desc: 'Canned goods and crops. Everyone eats about 2 a day.' },
  water: { name: 'Water', cat: 'needs', color: '#58a9dc', sell: 2, desc: 'Clean drinking water. Everyone drinks about 2.4 a day.' },
  meds: { name: 'Medicine', cat: 'needs', color: '#e8606e', sell: 14, capMul: 0.5, desc: 'Treats wounds. Used for first aid kits and in the Infirmary.' },
  wood: { name: 'Wood', cat: 'materials', color: '#b07a45', sell: 1, desc: 'Building and crafting. Comes from the Lumber Yard and furniture.' },
  scrap: { name: 'Scrap', cat: 'materials', color: '#9a8a76', sell: 1, desc: 'Junk metal. Smelted into metal at the Forge.' },
  metal: { name: 'Metal', cat: 'materials', color: '#a3adb6', sell: 3, desc: 'Refined steel for weapons, armor and machines.' },
  cloth: { name: 'Cloth', cat: 'materials', color: '#cdb892', sell: 2, desc: 'Fabric for clothing, bandages and armor.' },
  parts: { name: 'Parts', cat: 'materials', color: '#e08a3c', sell: 8, capMul: 0.5, desc: 'Springs, gears and fittings. Made at the Workbench.' },
  electronics: { name: 'Electronics', cat: 'materials', color: '#6fd0c0', sell: 10, capMul: 0.4, desc: 'Circuit boards and wiring for radios, turrets and automation.' },
  chemicals: { name: 'Chemicals', cat: 'materials', color: '#b4d45a', sell: 6, capMul: 0.5, desc: 'Solvents and reagents. Found on runs, or refined from fuel and scrap at the Chemistry Lab.' },
  gunpowder: { name: 'Gunpowder', cat: 'materials', color: '#7a7a82', sell: 5, capMul: 0.6, desc: 'Made at the Chemistry Lab. The Ammo Press needs it.' },
  fuel: { name: 'Fuel', cat: 'materials', color: '#d2553f', sell: 5, desc: 'Runs the generator and the van. Distilled at the Biofuel Still.' },
  pammo: { name: 'Pistol Ammo', short: '9mm', cat: 'ammo', color: '#d8c24a', sell: 1, capMul: 3, desc: 'For pistols, revolvers and SMGs.' },
  rammo: { name: 'Rifle Ammo', short: '7.62', cat: 'ammo', color: '#e8a83a', sell: 2, capMul: 2, desc: 'For hunting and assault rifles.' },
  shells: { name: 'Shotgun Shells', short: '12ga', cat: 'ammo', color: '#d8743a', sell: 2, capMul: 1.5, desc: 'For shotguns.' },
  medkit: { name: 'First Aid Kit', cat: 'supplies', color: '#e8606e', sell: 30, capMul: 0.15, desc: 'Utility item: heals a survivor by half their health on a run.' },
  molotov: { name: 'Molotov', cat: 'supplies', color: '#ff8a3a', sell: 20, capMul: 0.15, desc: 'Utility item: sets an area ablaze for several seconds.' },
  pipebomb: { name: 'Pipe Bomb', cat: 'supplies', color: '#c8c8c8', sell: 45, capMul: 0.15, desc: 'Utility item: a loud blast that shreds everything close by.' },
  noisemaker: { name: 'Noise Maker', cat: 'supplies', color: '#58d0ff', sell: 25, capMul: 0.15, desc: 'Utility item: beeps for 15 s and lures nearby zombies to it.' },
  module: { name: 'Automation Module', cat: 'supplies', color: '#7ad0ff', sell: 90, capMul: 0.1, desc: 'Lets a station run on generator power with nobody assigned.' },
  cash: { name: 'Cash', cat: 'cash', color: '#86c67a', sell: 1, desc: 'Trade currency for the black market.' },
}
export const RES_KEYS = Object.keys(RES)
export const STOCK_KEYS = RES_KEYS.filter((k) => k !== 'cash')
export const AMMO_KEYS = ['pammo', 'rammo', 'shells']
export const UTILITIES = ['medkit', 'molotov', 'pipebomb', 'noisemaker']
export const TOP_BAR = ['food', 'water', 'meds', 'wood', 'scrap', 'metal', 'parts', 'fuel', 'cash']

// ---------------------------------------------------------------- skills
export const SKILLS = {
  melee: { name: 'Melee', short: 'MEL', desc: 'Hand-to-hand damage and swing speed.' },
  ranged: { name: 'Ranged', short: 'RNG', desc: 'Firearm accuracy, damage and range.' },
  scavenge: { name: 'Scavenging', short: 'SCV', desc: 'Search speed, finds and carrying capacity on runs.' },
  build: { name: 'Building', short: 'BLD', desc: 'Construction, expansions, Lumber Yard, Scrap Yard, dismantling.' },
  craft: { name: 'Crafting', short: 'CRF', desc: 'All crafting benches: speed and the quality of what gets made.' },
  survival: { name: 'Survival', short: 'SRV', desc: 'Farming, water, cooking.' },
  medic: { name: 'Medicine', short: 'MED', desc: 'Infirmary healing, first aid, reviving the downed.' },
  tech: { name: 'Engineering', short: 'ENG', desc: 'Forge, Generator, Still, Chemistry Lab, Electronics Bench, Radio.' },
}
export const SKILL_KEYS = Object.keys(SKILLS)
export const SKILL_MAX = 10
export const xpForLevel = (lvl) => Math.round(30 * Math.pow(lvl, 1.55))

// ---------------------------------------------------------------- occupations
export const OCCUPATIONS = {
  doctor: { name: 'Doctor', skills: { medic: 5, craft: 1 }, perk: 'Heals twice as fast at the Infirmary and revives the downed in half the time.', fx: { healMult: 2, reviveMult: 0.5 }, stations: ['infirmary'] },
  nurse: { name: 'Nurse', skills: { medic: 4, survival: 1 }, perk: 'Squadmates within 4 m on a run slowly regain health.', fx: { aura: 1.2 }, stations: ['infirmary'] },
  paramedic: { name: 'Paramedic', skills: { medic: 3, scavenge: 1, melee: 1 }, perk: 'Moves 12% faster and revives the downed 30% faster.', fx: { speed: 0.12, reviveMult: 0.7 }, stations: ['infirmary'] },
  police: { name: 'Police Officer', skills: { ranged: 4, melee: 2 }, perk: '+20% damage with pistols and revolvers.', fx: { pistolDmg: 0.2 } },
  soldier: { name: 'Soldier', skills: { ranged: 5, melee: 2 }, perk: '+20% damage with every firearm and +15 health.', fx: { gunDmg: 0.2, hp: 15 } },
  firefighter: { name: 'Firefighter', skills: { melee: 3, build: 2 }, perk: '+30 health, dismantles 35% faster, axes hit 20% harder, immune to fire.', fx: { hp: 30, dismantle: 0.35, axeDmg: 0.2, fireproof: 1 } },
  farmer: { name: 'Farmer', skills: { survival: 5, build: 1 }, perk: 'Farm Plots produce 50% more when they work there.', fx: { station: { farm: 0.5 } }, stations: ['farm'] },
  chef: { name: 'Chef', skills: { survival: 3, craft: 1 }, perk: 'In the Cookhouse, stretches food 60% further than a regular cook and lifts morale.', fx: { station: { kitchen: 0.6 } }, stations: ['kitchen'] },
  carpenter: { name: 'Carpenter', skills: { build: 4, craft: 2 }, perk: 'Lumber Yard +50%. Construction and expansions 40% faster. Better wooden weapons.', fx: { station: { lumber: 0.5, workbench: 0.2 }, construct: 0.4 }, stations: ['lumber', 'workbench'] },
  mechanic: { name: 'Mechanic', skills: { craft: 3, tech: 3 }, perk: 'Scrap Yard, Forge and Weapons Station +40%. Strips cars for extra parts.', fx: { station: { scrapyard: 0.4, forge: 0.4, weapons: 0.4 }, carParts: 1, quality: { weapons: 1 } }, stations: ['scrapyard', 'forge', 'weapons'] },
  electrician: { name: 'Electrician', skills: { tech: 5 }, perk: 'Operating a Generator adds 50% power. Electronics Bench +40%.', fx: { station: { generator: 0.5, electronics: 0.4 } }, stations: ['generator', 'electronics'] },
  engineer: { name: 'Engineer', skills: { tech: 4, build: 2 }, perk: 'Still, Chemistry Lab, Ammo Press and Radio +40%. Upgrades cost 15% less.', fx: { station: { still: 0.4, chemlab: 0.4, ammo: 0.4, radio: 0.4 }, discount: 0.15 }, stations: ['chemlab', 'still', 'ammo'] },
  tailor: { name: 'Tailor', skills: { craft: 4, survival: 1 }, perk: 'Tailor Station +60%, and much likelier to make Fine and Masterwork armor.', fx: { station: { tailor: 0.6 }, quality: { tailor: 2 } }, stations: ['tailor'] },
  gunsmith: { name: 'Gunsmith', skills: { craft: 5, ranged: 1 }, perk: 'Weapons Station and Ammo Press +50%, and much likelier to make Fine and Masterwork guns.', fx: { station: { weapons: 0.5, ammo: 0.5 }, quality: { weapons: 2 } }, stations: ['weapons', 'ammo'] },
  hunter: { name: 'Hunter', skills: { ranged: 3, survival: 2, scavenge: 1 }, perk: 'Rifles and crossbows deal 25% more damage. Makes less noise.', fx: { rifleDmg: 0.25, noise: -0.3 } },
  athlete: { name: 'Athlete', skills: { melee: 2, scavenge: 2 }, perk: 'Moves 20% faster and carries more.', fx: { speed: 0.2, carry: 10 } },
  student: { name: 'Student', skills: { scavenge: 1, craft: 1 }, perk: 'Learns every skill 50% faster.', fx: { xp: 0.5 } },
  teacher: { name: 'Teacher', skills: { survival: 1, medic: 1 }, perk: 'At the Training Yard, everyone training learns 40% faster.', fx: { teach: 0.4 }, stations: ['training'] },
  plumber: { name: 'Plumber', skills: { survival: 2, tech: 2 }, perk: 'Water Filter +50%.', fx: { station: { filter: 0.5 } }, stations: ['filter'] },
  builder: { name: 'Construction Worker', skills: { build: 5, melee: 1 }, perk: 'Scrap Yard +30%. Construction and expansions 30% faster.', fx: { station: { scrapyard: 0.3 }, construct: 0.3 }, stations: ['scrapyard', 'lumber'] },
  clerk: { name: 'Store Clerk', skills: { scavenge: 3 }, perk: 'While in camp, the black market pays 12% more and charges 8% less.', fx: { market: 0.12 } },
  excon: { name: 'Ex-Con', skills: { scavenge: 3, melee: 2 }, perk: 'Picks locks without lockpicks.', fx: { picklock: 1 } },
  drifter: { name: 'Drifter', skills: { scavenge: 2, survival: 2, melee: 1 }, perk: 'Finds 15% more loot on runs and carries more.', fx: { loot: 0.15, carry: 8 } },
  guard: { name: 'Security Guard', skills: { melee: 3, ranged: 2 }, perk: 'On a Watchtower, deals 30% more damage.', fx: { station: { watchtower: 0.3 } }, stations: ['watchtower'] },
}
export const OCC_KEYS = Object.keys(OCCUPATIONS)

export const TRAITS = {
  tough: { name: 'Tough', good: true, desc: '+15 health.', fx: { hp: 15 } },
  quick: { name: 'Quick', good: true, desc: 'Moves 10% faster.', fx: { speed: 0.1 } },
  sharpshooter: { name: 'Sharpshooter', good: true, desc: '+12% firearm accuracy.', fx: { acc: 0.12 } },
  brawler: { name: 'Brawler', good: true, desc: '+20% melee damage.', fx: { meleeDmg: 0.2 } },
  quiet: { name: 'Quiet', good: true, desc: 'Searching and dismantling make half the noise.', fx: { noise: -0.5 } },
  packrat: { name: 'Packrat', good: true, desc: 'Finds 15% more loot and carries 8 more.', fx: { loot: 0.15, carry: 8 } },
  learner: { name: 'Quick Learner', good: true, desc: 'Learns skills 25% faster.', fx: { xp: 0.25 } },
  hardworker: { name: 'Hard Worker', good: true, desc: 'Works 15% faster at every station.', fx: { work: 0.15 } },
  steady: { name: 'Steady Hands', good: true, desc: 'Crafts better quality items.', fx: { qualityAll: 1 } },
  glutton: { name: 'Glutton', good: false, desc: 'Eats 50% more.', fx: { eat: 0.5 } },
  clumsy: { name: 'Clumsy', good: false, desc: 'Makes 50% more noise.', fx: { noise: 0.5 } },
  frail: { name: 'Frail', good: false, desc: '-15 health.', fx: { hp: -15 } },
  lazy: { name: 'Lazy', good: false, desc: 'Works 15% slower.', fx: { work: -0.15 } },
  coward: { name: 'Coward', good: false, desc: '-15% damage.', fx: { dmg: -0.15 } },
}
export const TRAIT_KEYS = Object.keys(TRAITS)

export const FIRST_NAMES = ['Mara', 'Jonah', 'Priya', 'Dev', 'Tomas', 'Ines', 'Callum', 'Rosa', 'Eli', 'Nadia', 'Owen', 'Hana', 'Marcus', 'Leah', 'Sami', 'Grace', 'Theo', 'Ada', 'Felix', 'Zara', 'Reuben', 'Maya', 'Isaac', 'Lena', 'Ruben', 'Tess', 'Kofi', 'Ivy', 'Luca', 'Nell', 'Arjun', 'June', 'Silas', 'Wren', 'Omar', 'Faye', 'Dmitri', 'Cleo', 'Hugo', 'Esme', 'Bram', 'Kira', 'Declan', 'Yara', 'Otto', 'Mina', 'Rafe', 'Lottie', 'Idris', 'Pia', 'Gus', 'Freya', 'Wes', 'Anya', 'Cal', 'Suki', 'Nico', 'Bea', 'Jude', 'Tove']
export const FEMALE_NAMES = new Set(['Mara', 'Priya', 'Ines', 'Rosa', 'Nadia', 'Hana', 'Leah', 'Grace', 'Ada', 'Zara', 'Maya', 'Lena', 'Tess', 'Ivy', 'Nell', 'June', 'Wren', 'Faye', 'Cleo', 'Esme', 'Kira', 'Yara', 'Mina', 'Lottie', 'Pia', 'Freya', 'Anya', 'Suki', 'Bea', 'Tove'])
export const LAST_NAMES = ['Hale', 'Okafor', 'Reyes', 'Brandt', 'Novak', 'Castillo', 'Whitlock', 'Mensah', 'Kowalski', 'Ferreira', 'Doyle', 'Tanaka', 'Rahman', 'Lindqvist', 'Moreau', 'Achebe', 'Vance', 'Quinn', 'Petrov', 'Salazar', 'Harrow', 'Nakamura', 'Byrne', 'Adeyemi', 'Holt', 'Varga', 'Pryce', 'Lund', 'Marsh', 'Orlov', 'Keane', 'Sato', 'Ibarra', 'Crane', 'Mbeki', 'Foss', 'Laine', 'Duarte', 'Wilde', 'Park']

// ---------------------------------------------------------------- items
export const RARITY = {
  common: { name: 'Common', color: '#b8bcb5', rank: 0 },
  uncommon: { name: 'Uncommon', color: '#7cc36a', rank: 1 },
  rare: { name: 'Rare', color: '#58a6e8', rank: 2 },
  epic: { name: 'Epic', color: '#b57ae8', rank: 3 },
}
// Crafted and looted items roll a quality tier.
export const QUALITY = [
  { id: 'crude', name: 'Crude', mult: 0.85, dur: 0.7, value: 0.6, color: '#a08a72' },
  { id: 'standard', name: 'Standard', mult: 1, dur: 1, value: 1, color: '#c8c4b8' },
  { id: 'fine', name: 'Fine', mult: 1.15, dur: 1.3, value: 1.7, color: '#7cc8ff' },
  { id: 'master', name: 'Masterwork', mult: 1.3, dur: 1.7, value: 2.8, color: '#f2b84a' },
]

// dur = how many uses (hits, shots, or hits taken) from 100% to broken.
// repair = bench that fixes it. mods = which mod family fits.
export const ITEMS = {
  fists: { name: 'Fists', slot: 'weapon', kind: 'melee', rarity: 'common', dmg: 7, range: 1.3, rate: 0.75, noise: 1, dur: 0, value: 0 },
  crowbar: { name: 'Crowbar', slot: 'weapon', kind: 'melee', rarity: 'common', dmg: 13, range: 1.5, rate: 0.85, noise: 2, dur: 400, value: 40, repair: 'workbench', mods: 'melee', pry: 1, desc: 'Pries locked containers open faster when smashing.' },
  bat: { name: 'Baseball Bat', slot: 'weapon', kind: 'melee', rarity: 'common', dmg: 14, range: 1.6, rate: 0.9, noise: 2, dur: 260, value: 35, repair: 'workbench', mods: 'melee' },
  pipe: { name: 'Lead Pipe', slot: 'weapon', kind: 'melee', rarity: 'common', dmg: 15, range: 1.5, rate: 0.95, noise: 2, dur: 420, value: 35, repair: 'workbench', mods: 'melee' },
  nailbat: { name: 'Nail Bat', slot: 'weapon', kind: 'melee', rarity: 'uncommon', dmg: 19, range: 1.6, rate: 0.9, noise: 2, dur: 220, value: 70, repair: 'workbench', mods: 'melee' },
  spear: { name: 'Spear', slot: 'weapon', kind: 'melee', rarity: 'uncommon', dmg: 21, range: 2.3, rate: 1.0, noise: 1, dur: 220, value: 80, repair: 'workbench', mods: 'melee', desc: 'Long reach: hits before they can.' },
  machete: { name: 'Machete', slot: 'weapon', kind: 'melee', rarity: 'uncommon', dmg: 23, range: 1.5, rate: 0.72, noise: 1, dur: 300, value: 120, repair: 'workbench', mods: 'melee' },
  axe: { name: 'Fire Axe', slot: 'weapon', kind: 'melee', rarity: 'rare', dmg: 36, range: 1.7, rate: 1.15, noise: 2, dur: 360, value: 250, repair: 'workbench', mods: 'melee', axe: true },
  sledge: { name: 'Sledgehammer', slot: 'weapon', kind: 'melee', rarity: 'rare', dmg: 50, range: 1.7, rate: 1.55, noise: 3, dur: 500, value: 280, repair: 'workbench', mods: 'melee', knock: 1, desc: 'Knocks zombies off their feet.' },
  katana: { name: 'Katana', slot: 'weapon', kind: 'melee', rarity: 'epic', dmg: 40, range: 1.8, rate: 0.68, noise: 1, dur: 320, value: 700, repair: 'workbench', mods: 'melee' },
  pistol: { name: '9mm Pistol', slot: 'weapon', kind: 'gun', rarity: 'common', dmg: 17, range: 9, rate: 0.5, noise: 11, ammo: 'pammo', dur: 600, value: 100, repair: 'weapons', mods: 'gun', pistol: true },
  revolver: { name: 'Revolver', slot: 'weapon', kind: 'gun', rarity: 'uncommon', dmg: 32, range: 10, rate: 0.9, noise: 13, ammo: 'pammo', dur: 800, value: 200, repair: 'weapons', mods: 'gun', pistol: true },
  smg: { name: 'SMG', slot: 'weapon', kind: 'gun', rarity: 'rare', dmg: 11, range: 8, rate: 0.12, noise: 12, ammo: 'pammo', dur: 500, value: 480, repair: 'weapons', mods: 'gun' },
  shotgun: { name: 'Pump Shotgun', slot: 'weapon', kind: 'gun', rarity: 'uncommon', dmg: 50, range: 6, rate: 1.15, noise: 16, ammo: 'shells', dur: 500, value: 290, repair: 'weapons', mods: 'gun', falloff: true, pellets: true },
  crossbow: { name: 'Crossbow', slot: 'weapon', kind: 'gun', rarity: 'rare', dmg: 55, range: 11, rate: 1.7, noise: 1, ammo: null, dur: 300, value: 380, repair: 'weapons', mods: 'gun', rifle: true, desc: 'Silent. Bolts are recovered, so it needs no ammo.' },
  rifle: { name: 'Hunting Rifle', slot: 'weapon', kind: 'gun', rarity: 'rare', dmg: 64, range: 15, rate: 1.5, noise: 16, ammo: 'rammo', dur: 700, value: 460, repair: 'weapons', mods: 'gun', rifle: true },
  ar: { name: 'Assault Rifle', slot: 'weapon', kind: 'gun', rarity: 'epic', dmg: 22, range: 12, rate: 0.15, noise: 15, ammo: 'rammo', dur: 600, value: 950, repair: 'weapons', mods: 'gun' },

  jacket: { name: 'Leather Jacket', slot: 'armor', rarity: 'common', hp: 15, dr: 0.08, dur: 120, value: 60, repair: 'tailor', mods: 'armor', look: 'jacket' },
  vest: { name: 'Kevlar Vest', slot: 'armor', rarity: 'uncommon', hp: 30, dr: 0.18, dur: 160, value: 240, repair: 'tailor', mods: 'armor', look: 'vest' },
  ghillie: { name: 'Ghillie Poncho', slot: 'armor', rarity: 'rare', hp: 10, dr: 0.04, stealth: 0.4, dur: 100, value: 320, repair: 'tailor', mods: 'armor', look: 'ghillie', desc: 'Zombies spot the wearer 40% later.' },
  riot: { name: 'Riot Armor', slot: 'armor', rarity: 'rare', hp: 50, dr: 0.28, speed: -0.08, dur: 220, value: 520, repair: 'tailor', mods: 'armor', look: 'riot' },
  military: { name: 'Combat Armor', slot: 'armor', rarity: 'epic', hp: 70, dr: 0.36, dur: 260, value: 1000, repair: 'tailor', mods: 'armor', look: 'military' },

  packS: { name: 'Small Backpack', slot: 'gear', rarity: 'common', carry: 15, util: 1, value: 60, desc: 'Carries 15 more and holds 1 more utility item.', pack: 'small' },
  packL: { name: 'Hiking Pack', slot: 'gear', rarity: 'uncommon', carry: 35, util: 2, value: 180, desc: 'Carries 35 more and holds 2 more utility items.', pack: 'large' },
  flashlight: { name: 'Flashlight', slot: 'gear', rarity: 'common', search: 0.3, nightSight: 0.5, value: 50, desc: 'Searches 30% faster and helps see at night.' },
  lockpicks: { name: 'Lockpicks', slot: 'gear', rarity: 'uncommon', picklock: 1, value: 120, desc: 'Opens locked safes, lockers and doors quietly.' },
  toolkit: { name: 'Toolkit', slot: 'gear', rarity: 'uncommon', dismantle: 0.4, value: 140, desc: 'Dismantles 40% faster and salvages more.' },
  walkie: { name: 'Walkie-Talkie', slot: 'gear', rarity: 'rare', walkie: 1, value: 300, desc: 'The squad hears the horde coming: on runs it arrives 45 s later.' },
  shoes: { name: 'Running Shoes', slot: 'gear', rarity: 'common', speed: 0.15, value: 60, desc: 'Moves 15% faster.' },
  nvg: { name: 'Night Vision Goggles', slot: 'gear', rarity: 'epic', nightSight: 1, acc: 0.06, value: 650, desc: 'No accuracy or sight penalty at night.' },
}
export function itemStatLine(id, q = 1, mods = []) {
  const it = ITEMS[id]
  if (!it) return ''
  const Q = QUALITY[q] || QUALITY[1]
  if (it.slot === 'weapon') {
    let dmg = it.dmg * Q.mult
    let rate = it.rate
    let range = it.range
    for (const m of mods) {
      const M = MODS[m]
      if (M?.fx.dmg) dmg *= M.fx.dmg
      if (M?.fx.rate) rate *= M.fx.rate
      if (M?.fx.range) range *= M.fx.range
    }
    return `${Math.round(dmg)} dmg · ${range.toFixed(range % 1 ? 1 : 0)} m · ${Math.round(dmg / rate)} dps${it.ammo ? ` · ${RES[it.ammo].short}` : it.kind === 'gun' ? ' · no ammo' : ''}`
  }
  if (it.slot === 'armor') {
    let hp = it.hp * Q.mult
    let dr = it.dr * Q.mult
    for (const m of mods) {
      if (MODS[m]?.fx.hp) hp += MODS[m].fx.hp
      if (MODS[m]?.fx.dr) dr += MODS[m].fx.dr
    }
    return `+${Math.round(hp)} health · ${Math.round(dr * 100)}% damage reduction${it.speed ? ` · ${Math.round(it.speed * 100)}% speed` : ''}${it.stealth ? ' · stealthy' : ''}`
  }
  return it.desc || ''
}

// Mods fitted at a bench; one per item.
export const MODS = {
  extmag: { name: 'Extended Magazine', type: 'gun', bench: 'weapons', lvl: 1, cost: { metal: 6, parts: 4 }, time: 40, fx: { rate: 0.85 }, desc: 'Fires 15% faster.' },
  reinforcedGun: { name: 'Reinforced Frame', type: 'gun', bench: 'weapons', lvl: 1, cost: { metal: 10, parts: 2 }, time: 35, fx: { dur: 2 }, desc: 'Wears out half as fast.' },
  suppressor: { name: 'Suppressor', type: 'gun', bench: 'weapons', lvl: 2, cost: { metal: 8, parts: 6 }, time: 55, fx: { noise: 0.3, dmg: 0.93 }, desc: 'Gunshots make 70% less noise. 7% less damage.', model: 'suppressor' },
  scope: { name: 'Scope', type: 'gun', bench: 'weapons', lvl: 2, cost: { metal: 4, parts: 4, electronics: 2 }, time: 50, fx: { range: 1.25, acc: 0.1 }, desc: '+25% range and +10% accuracy.', model: 'scope' },
  spiked: { name: 'Spikes', type: 'melee', bench: 'workbench', lvl: 1, cost: { metal: 3, scrap: 6 }, time: 25, fx: { dmg: 1.2 }, desc: '+20% damage.' },
  balanced: { name: 'Balanced Grip', type: 'melee', bench: 'workbench', lvl: 2, cost: { cloth: 4, parts: 2 }, time: 25, fx: { rate: 0.87 }, desc: 'Swings 13% faster.' },
  reinforcedMelee: { name: 'Reinforced', type: 'melee', bench: 'workbench', lvl: 1, cost: { metal: 6 }, time: 25, fx: { dur: 2 }, desc: 'Wears out half as fast.' },
  padding: { name: 'Padding', type: 'armor', bench: 'tailor', lvl: 1, cost: { cloth: 10 }, time: 30, fx: { hp: 15 }, desc: '+15 health.' },
  plates: { name: 'Steel Plates', type: 'armor', bench: 'tailor', lvl: 2, cost: { metal: 12, cloth: 4 }, time: 45, fx: { dr: 0.08, speed: -0.04 }, desc: '+8% damage reduction, 4% slower.' },
  camo: { name: 'Camouflage', type: 'armor', bench: 'tailor', lvl: 2, cost: { cloth: 8, chemicals: 2 }, time: 35, fx: { stealth: 0.3 }, desc: 'Zombies notice the wearer 30% later.' },
}

// ---------------------------------------------------------------- stations
// cost[i]: build/upgrade to level i+1. time[i]: build seconds. workers[i]: job slots.
// auto: level that allows automation (needs an Automation Module + power).
// queue: crafting order slots per level. recipe: continuous processing.
export const STATIONS = {
  campfire: { name: 'Campfire', cat: 'living', size: [4, 4], levels: 1, fixed: true, skill: null, desc: 'The heart of the camp. Idle survivors gather here and the fire keeps spirits up.', cost: [{}], time: [0], workers: [0] },
  bunkhouse: {
    name: 'Bunkhouse', cat: 'living', size: [6, 5], levels: 3, skill: null,
    desc: 'Beds for survivors. Newcomers can only join if there is a free bed. Better bunks lift morale.',
    cost: [{ wood: 40 }, { wood: 70, metal: 20, cloth: 15 }, { wood: 90, metal: 50, cloth: 30, parts: 8 }],
    time: [30, 60, 100], workers: [0, 0, 0], beds: [4, 6, 10],
  },
  storage: {
    name: 'Storage Depot', cat: 'living', size: [5, 4], levels: 3, skill: null,
    desc: 'Raises how much of each resource the camp can hold.',
    cost: [{ wood: 40, scrap: 20 }, { wood: 70, metal: 30 }, { wood: 100, metal: 70, parts: 10 }],
    time: [25, 55, 90], workers: [0, 0, 0], cap: [120, 260, 450],
  },
  kitchen: {
    name: 'Cookhouse', cat: 'living', size: [5, 4], levels: 3, skill: 'survival',
    desc: 'A cook turns rations into proper meals: the camp eats less and morale rises. Burns a little wood.',
    cost: [{ wood: 45, scrap: 15 }, { wood: 60, metal: 25, cloth: 10 }, { wood: 40, metal: 50, parts: 8 }],
    time: [30, 60, 90], workers: [1, 1, 2], saving: [0.15, 0.22, 0.3], morale: [6, 9, 12], burn: { wood: 3 },
  },
  infirmary: {
    name: 'Infirmary', cat: 'living', size: [6, 4], levels: 3, skill: 'medic',
    desc: 'Medics heal the injured first. Between patients they make first aid kits, and at level 2 medicine.',
    cost: [{ wood: 35, cloth: 20 }, { wood: 40, metal: 25, cloth: 30 }, { metal: 50, cloth: 40, parts: 10 }],
    time: [30, 60, 100], workers: [1, 1, 2], heal: [0.6, 0.9, 1.3], beds: [2, 3, 5], queue: [2, 3, 4],
  },
  training: {
    name: 'Training Yard', cat: 'living', size: [6, 5], levels: 3, skill: null,
    desc: 'Trainees drill a combat skill. Teachers make everyone here learn faster.',
    cost: [{ wood: 40, cloth: 10 }, { wood: 60, metal: 20, cloth: 15 }, { wood: 70, metal: 40, parts: 8 }],
    time: [25, 50, 90], workers: [2, 3, 4], xpRate: [0.9, 1.3, 1.8],
  },
  radio: {
    name: 'Radio Tower', cat: 'living', size: [3, 3], levels: 3, skill: 'tech',
    desc: 'Broadcasts to other survivors: more newcomers, more distress calls to answer, and a better-stocked trader. An operator helps.',
    cost: [{ metal: 30, parts: 8, electronics: 4 }, { metal: 50, parts: 16, electronics: 10 }, { metal: 80, parts: 28, electronics: 18 }],
    time: [35, 70, 110], workers: [1, 1, 1], recruit: [0.75, 0.55, 0.4],
  },

  farm: {
    name: 'Farm Plot', cat: 'production', size: [6, 6], levels: 3, skill: 'survival',
    desc: 'Grows food from water. Farmers are 50% better at it.',
    cost: [{ wood: 30 }, { wood: 45, metal: 10, scrap: 10 }, { wood: 60, metal: 25, parts: 8 }],
    time: [20, 50, 80], workers: [1, 2, 3], auto: 3, autoPower: 1, autoRate: 1.5,
    recipe: { in: { water: 0.5 }, out: { food: 1 }, time: [56, 45, 36] },
  },
  collector: {
    name: 'Rain Collector', cat: 'production', size: [3, 3], levels: 3, skill: null,
    desc: 'Barrels and tarps that fill with rain on their own. More in wet weather.',
    cost: [{ wood: 15, cloth: 5 }, { wood: 25, metal: 10, cloth: 10 }, { metal: 30, parts: 5 }],
    time: [15, 35, 60], workers: [0, 0, 0], passive: { water: [5, 9, 14] },
  },
  filter: {
    name: 'Water Filter', cat: 'production', size: [4, 3], levels: 3, skill: 'survival',
    desc: 'Pumps and filters well water. Plumbers get the most out of it.',
    cost: [{ wood: 20, metal: 15, cloth: 5 }, { metal: 30, parts: 4 }, { metal: 45, parts: 10 }],
    time: [25, 45, 80], workers: [1, 1, 2], auto: 3, autoPower: 1, autoRate: 1.5,
    recipe: { in: {}, out: { water: 1 }, time: [48, 38, 30] },
  },
  lumber: {
    name: 'Lumber Yard', cat: 'production', size: [6, 5], levels: 3, skill: 'build',
    desc: 'Fells and saws timber from the surrounding land.',
    cost: [{ wood: 20, scrap: 15 }, { wood: 40, metal: 20 }, { metal: 50, parts: 12, fuel: 5 }],
    time: [20, 50, 80], workers: [1, 2, 3], auto: 3, autoPower: 2, autoRate: 2,
    recipe: { in: {}, out: { wood: 1 }, time: [12, 10, 8] },
  },
  scrapyard: {
    name: 'Scrap Yard', cat: 'production', size: [6, 5], levels: 3, skill: 'build',
    desc: 'Strips wrecks for scrap, and now and then a usable part or circuit board.',
    cost: [{ wood: 30 }, { wood: 40, metal: 20 }, { metal: 50, parts: 12, fuel: 5 }],
    time: [20, 50, 80], workers: [1, 2, 3], auto: 3, autoPower: 2, autoRate: 2,
    recipe: { in: {}, out: { scrap: 1 }, time: [10, 8, 6.5], bonus: { parts: 0.05, electronics: 0.02 } },
  },
  forge: {
    name: 'Forge', cat: 'production', size: [4, 4], levels: 3, skill: 'tech',
    desc: 'Smelts scrap into usable metal, fired with wood. Set a limit so it does not eat all your scrap.',
    cost: [{ wood: 30, scrap: 40 }, { metal: 30, parts: 6, scrap: 20 }, { metal: 60, parts: 14 }],
    time: [30, 60, 100], workers: [1, 1, 2], auto: 2, autoPower: 2, autoRate: 1.2,
    recipe: { in: { scrap: 3, wood: 1 }, out: { metal: 2 }, time: [22, 17, 13] }, limit: true,
  },
  still: {
    name: 'Biofuel Still', cat: 'production', size: [4, 3], levels: 3, skill: 'tech',
    desc: 'Ferments food and water into fuel for the generator and the van.',
    cost: [{ metal: 25, parts: 6, scrap: 20 }, { metal: 45, parts: 12 }, { metal: 60, parts: 20 }],
    time: [30, 60, 90], workers: [1, 1, 2], auto: 2, autoPower: 1, autoRate: 1,
    recipe: { in: { food: 2, water: 1 }, out: { fuel: 1 }, time: [40, 32, 24] }, limit: true,
  },
  generator: {
    name: 'Generator', cat: 'production', size: [4, 3], levels: 3, skill: 'tech',
    desc: 'Burns fuel to power automated stations, turrets and floodlights. An operator adds power.',
    cost: [{ metal: 40, parts: 12, electronics: 3 }, { metal: 60, parts: 20, electronics: 6 }, { metal: 90, parts: 35, electronics: 10 }],
    time: [35, 70, 110], workers: [1, 1, 1], power: [5, 10, 16], burn: [50, 44, 38],
  },
  solar: {
    name: 'Solar Array', cat: 'production', size: [5, 4], levels: 2, skill: null, req: { electronics: 1 },
    desc: 'Free power in daylight, nothing at night. Clouds cut the output.',
    cost: [{ metal: 30, electronics: 12, parts: 6 }, { metal: 40, electronics: 18, parts: 10 }],
    time: [40, 70], workers: [0, 0], solar: [3, 6],
  },
  chemlab: {
    name: 'Chemistry Lab', cat: 'crafting', size: [5, 4], levels: 3, skill: 'tech',
    desc: 'Mixes gunpowder for the Ammo Press, molotovs and pipe bombs for runs, and refines chemicals.',
    cost: [{ wood: 30, metal: 20, chemicals: 6 }, { metal: 40, parts: 10, chemicals: 10 }, { metal: 60, parts: 20, electronics: 6 }],
    time: [35, 65, 100], workers: [1, 1, 2], auto: 3, autoPower: 2, autoRate: 1, queue: [3, 4, 6],
  },

  workbench: {
    name: 'Workbench', cat: 'crafting', size: [4, 3], levels: 3, skill: 'craft',
    desc: 'The crafting table. Makes parts, melee weapons and tools, fits melee mods and repairs melee weapons.',
    cost: [{ wood: 30 }, { wood: 40, metal: 25, parts: 4 }, { metal: 50, parts: 12, electronics: 2 }],
    time: [20, 50, 80], workers: [1, 1, 2], auto: 3, autoPower: 1, autoRate: 1, queue: [3, 4, 6],
  },
  weapons: {
    name: 'Weapons Station', cat: 'crafting', size: [5, 3], levels: 3, skill: 'craft', req: { workbench: 2 },
    desc: 'A gunsmith\'s bench: builds firearms, fits suppressors, scopes and magazines, and repairs guns.',
    cost: [{ wood: 30, metal: 40, parts: 10 }, { metal: 60, parts: 20 }, { metal: 90, parts: 35, electronics: 4 }],
    time: [45, 75, 115], workers: [1, 1, 2], auto: 3, autoPower: 2, autoRate: 1, queue: [2, 3, 4],
  },
  ammo: {
    name: 'Ammo Press', cat: 'crafting', size: [4, 3], levels: 3, skill: 'craft', req: { chemlab: 1 },
    desc: 'Presses metal and gunpowder into rounds. Choose a calibre, or let it keep the lowest one topped up.',
    cost: [{ metal: 35, parts: 8 }, { metal: 50, parts: 15 }, { metal: 70, parts: 25, electronics: 4 }],
    time: [35, 65, 95], workers: [1, 1, 2], auto: 2, autoPower: 2, autoRate: 1.2,
    modes: {
      pammo: { in: { metal: 1, gunpowder: 1 }, out: { pammo: 12 }, time: [18, 14, 11] },
      rammo: { in: { metal: 1, gunpowder: 2 }, out: { rammo: 8 }, time: [22, 17, 13] },
      shells: { in: { metal: 1, gunpowder: 1, scrap: 1 }, out: { shells: 6 }, time: [20, 15, 12] },
    },
  },
  tailor: {
    name: 'Tailor Station', cat: 'crafting', size: [5, 4], levels: 3, skill: 'craft',
    desc: 'Sewing tables and a press: clothing, armor and backpacks, armor mods and armor repairs.',
    cost: [{ wood: 30, cloth: 20 }, { wood: 40, metal: 20, cloth: 30 }, { metal: 50, cloth: 40, parts: 10 }],
    time: [25, 50, 85], workers: [1, 1, 2], auto: 3, autoPower: 1, autoRate: 1, queue: [2, 3, 4],
  },
  electronics: {
    name: 'Electronics Bench', cat: 'crafting', size: [4, 3], levels: 3, skill: 'tech', req: { workbench: 1 },
    desc: 'Soldering station for flashlights, radios, noise makers, night vision and the automation modules that free up your workers.',
    cost: [{ wood: 20, metal: 20, electronics: 6 }, { metal: 35, parts: 10, electronics: 12 }, { metal: 50, parts: 18, electronics: 20 }],
    time: [35, 65, 100], workers: [1, 1, 2], queue: [2, 3, 4],
  },

  watchtower: {
    name: 'Watchtower', cat: 'defense', size: [3, 3], levels: 3, skill: 'ranged',
    desc: 'A guard up here fires on the horde with extra damage, picks off night wanderers and spots hordes early.',
    cost: [{ wood: 50 }, { wood: 60, metal: 25 }, { metal: 60, parts: 8 }],
    time: [30, 55, 85], workers: [1, 1, 2], towerDmg: [0.25, 0.45, 0.7],
  },
  turret: {
    name: 'Auto-Turret', cat: 'defense', size: [2, 2], levels: 3, skill: null, req: { generator: 1 },
    desc: 'Fires at anything near the fence. Uses pistol ammo from storage and generator power.',
    cost: [{ metal: 50, parts: 20, electronics: 8 }, { metal: 70, parts: 30, electronics: 12 }, { metal: 100, parts: 45, electronics: 18 }],
    time: [45, 75, 115], workers: [0, 0, 0], power: 2, dmg: [14, 20, 28], rate: [0.45, 0.35, 0.25], range: [11, 13, 15],
  },
  floodlight: {
    name: 'Floodlight', cat: 'defense', size: [1, 1], levels: 1, skill: null, req: { generator: 1 },
    desc: 'Lights the perimeter at night. Defenders nearby shoot at full accuracy in the dark.',
    cost: [{ metal: 12, parts: 4, electronics: 2 }], time: [20], workers: [0], power: 1,
  },
}
export const STATION_CATS = [
  { id: 'living', name: 'Camp' },
  { id: 'production', name: 'Production' },
  { id: 'crafting', name: 'Crafting' },
  { id: 'defense', name: 'Defense' },
]

// Perimeter wall levels; upgrade cost is per 10 m of wall.
export const FENCE = [
  { name: 'Scrap Fence', hp: 70, per10: {} },
  { name: 'Log Palisade', hp: 140, per10: { wood: 18 }, time: 70 },
  { name: 'Sheet-Metal Wall', hp: 250, per10: { metal: 15, scrap: 8, parts: 1 }, time: 120 },
  { name: 'Fortified Wall', hp: 400, per10: { metal: 22, wood: 10, parts: 2 }, time: 170 },
]

// ---------------------------------------------------------------- recipes
// Items, resources and supplies made at crafting benches. time = work-seconds at 100%.
export const RECIPES = [
  // Workbench: the crafting table
  { id: 'parts', station: 'workbench', lvl: 1, out: { parts: 2 }, in: { scrap: 4, metal: 1 }, time: 22, cat: 'Materials' },
  { id: 'bat', station: 'workbench', lvl: 1, item: 'bat', in: { wood: 10 }, time: 25, cat: 'Melee' },
  { id: 'crowbar', station: 'workbench', lvl: 1, item: 'crowbar', in: { metal: 6, scrap: 2 }, time: 30, cat: 'Melee' },
  { id: 'spear', station: 'workbench', lvl: 1, item: 'spear', in: { wood: 8, metal: 2, cloth: 1 }, time: 35, cat: 'Melee' },
  { id: 'nailbat', station: 'workbench', lvl: 1, item: 'nailbat', in: { wood: 10, scrap: 4 }, time: 35, cat: 'Melee' },
  { id: 'machete', station: 'workbench', lvl: 2, item: 'machete', in: { metal: 10, wood: 2, parts: 1 }, time: 45, cat: 'Melee' },
  { id: 'axe', station: 'workbench', lvl: 3, item: 'axe', in: { metal: 16, wood: 8, parts: 3 }, time: 70, cat: 'Melee' },
  { id: 'sledge', station: 'workbench', lvl: 3, item: 'sledge', in: { metal: 22, wood: 6, parts: 2 }, time: 75, cat: 'Melee' },
  { id: 'lockpicks', station: 'workbench', lvl: 1, item: 'lockpicks', in: { metal: 4, parts: 2 }, time: 30, cat: 'Tools' },
  { id: 'toolkit', station: 'workbench', lvl: 2, item: 'toolkit', in: { metal: 12, parts: 6 }, time: 45, cat: 'Tools' },

  // Weapons Station
  { id: 'pistol', station: 'weapons', lvl: 1, item: 'pistol', in: { metal: 18, parts: 8 }, time: 50, cat: 'Handguns' },
  { id: 'revolver', station: 'weapons', lvl: 1, item: 'revolver', in: { metal: 24, parts: 10 }, time: 65, cat: 'Handguns' },
  { id: 'crossbow', station: 'weapons', lvl: 2, item: 'crossbow', in: { wood: 18, metal: 8, parts: 8 }, time: 70, cat: 'Long guns' },
  { id: 'shotgun', station: 'weapons', lvl: 2, item: 'shotgun', in: { metal: 28, wood: 10, parts: 12 }, time: 80, cat: 'Long guns' },
  { id: 'rifle', station: 'weapons', lvl: 2, item: 'rifle', in: { metal: 34, wood: 14, parts: 16 }, time: 95, cat: 'Long guns' },
  { id: 'smg', station: 'weapons', lvl: 3, item: 'smg', in: { metal: 42, parts: 22, electronics: 2 }, time: 110, cat: 'Automatics' },
  { id: 'ar', station: 'weapons', lvl: 3, item: 'ar', in: { metal: 58, parts: 34, electronics: 4 }, time: 140, cat: 'Automatics' },

  // Tailor Station
  { id: 'jacket', station: 'tailor', lvl: 1, item: 'jacket', in: { cloth: 16, scrap: 2 }, time: 30, cat: 'Armor' },
  { id: 'packS', station: 'tailor', lvl: 1, item: 'packS', in: { cloth: 18, wood: 2 }, time: 35, cat: 'Packs' },
  { id: 'shoes', station: 'tailor', lvl: 1, item: 'shoes', in: { cloth: 10, parts: 2 }, time: 25, cat: 'Gear' },
  { id: 'vest', station: 'tailor', lvl: 2, item: 'vest', in: { cloth: 22, metal: 12, parts: 6 }, time: 60, cat: 'Armor' },
  { id: 'packL', station: 'tailor', lvl: 2, item: 'packL', in: { cloth: 30, metal: 4, parts: 4 }, time: 55, cat: 'Packs' },
  { id: 'ghillie', station: 'tailor', lvl: 2, item: 'ghillie', in: { cloth: 26, chemicals: 3 }, time: 60, cat: 'Armor' },
  { id: 'riot', station: 'tailor', lvl: 3, item: 'riot', in: { cloth: 28, metal: 38, parts: 14 }, time: 90, cat: 'Armor' },
  { id: 'military', station: 'tailor', lvl: 3, item: 'military', in: { cloth: 36, metal: 48, parts: 22, electronics: 2 }, time: 120, cat: 'Armor' },

  // Chemistry Lab
  { id: 'chemicals', station: 'chemlab', lvl: 1, out: { chemicals: 1 }, in: { fuel: 1, scrap: 2 }, time: 40, cat: 'Materials' },
  { id: 'gunpowder', station: 'chemlab', lvl: 1, out: { gunpowder: 3 }, in: { chemicals: 2, wood: 1 }, time: 30, cat: 'Materials' },
  { id: 'molotov', station: 'chemlab', lvl: 1, out: { molotov: 1 }, in: { fuel: 2, cloth: 1 }, time: 20, cat: 'Throwables' },
  { id: 'pipebomb', station: 'chemlab', lvl: 2, out: { pipebomb: 1 }, in: { metal: 2, gunpowder: 4, electronics: 1 }, time: 45, cat: 'Throwables' },

  // Electronics Bench
  { id: 'flashlight', station: 'electronics', lvl: 1, item: 'flashlight', in: { electronics: 2, parts: 2, scrap: 2 }, time: 30, cat: 'Gadgets' },
  { id: 'noisemaker', station: 'electronics', lvl: 1, out: { noisemaker: 1 }, in: { electronics: 1, parts: 1 }, time: 25, cat: 'Gadgets' },
  { id: 'walkie', station: 'electronics', lvl: 2, item: 'walkie', in: { electronics: 6, parts: 4, metal: 2 }, time: 60, cat: 'Gadgets' },
  { id: 'module', station: 'electronics', lvl: 2, out: { module: 1 }, in: { electronics: 5, parts: 6, metal: 6 }, time: 80, cat: 'Automation' },
  { id: 'nvg', station: 'electronics', lvl: 3, item: 'nvg', in: { electronics: 12, parts: 8, metal: 4 }, time: 120, cat: 'Gadgets' },

  // Infirmary
  { id: 'medkit', station: 'infirmary', lvl: 1, out: { medkit: 1 }, in: { cloth: 4, meds: 2 }, time: 25, cat: 'Medical' },
  { id: 'meds', station: 'infirmary', lvl: 2, out: { meds: 1 }, in: { chemicals: 2, water: 2 }, time: 35, cat: 'Medical' },
]
// Repair cost per 100% condition, by bench (scaled by item value).
export const REPAIR = {
  workbench: { metal: 3, scrap: 4 },
  weapons: { metal: 4, parts: 3 },
  tailor: { cloth: 6, metal: 1 },
}

// ---------------------------------------------------------------- expansions
// The camp starts small. Each side can be pushed out twice.
export const EXPANSIONS = [
  { id: 'n1', side: 'n', ring: 1, name: 'North Woodlot', terrain: 'woods', cost: { wood: 60, scrap: 30, cash: 120 }, time: 90, salvage: { wood: 90 }, desc: 'Fell the pines behind the camp. The timber is yours.' },
  { id: 'e1', side: 'e', ring: 1, name: 'East Wrecking Lot', terrain: 'wrecks', cost: { wood: 60, scrap: 20, cash: 140 }, time: 90, salvage: { scrap: 80, parts: 6 }, desc: 'Drag off the wrecked cars. They\'re full of scrap.' },
  { id: 'w1', side: 'w', ring: 1, name: 'West Meadow', terrain: 'meadow', cost: { wood: 70, cash: 110 }, time: 80, salvage: { food: 20, wood: 25 }, desc: 'Open grass and an overgrown kitchen garden.' },
  { id: 's1', side: 's', ring: 1, name: 'South Yard', terrain: 'yard', cost: { wood: 70, metal: 20, cash: 150 }, time: 90, salvage: { scrap: 40, cloth: 20, wood: 20 }, desc: 'The old loading yard by the road. The gate moves south.' },
  { id: 'n2', side: 'n', ring: 2, name: 'Pine Ridge', terrain: 'woods', cost: { wood: 140, metal: 60, parts: 8, cash: 350 }, time: 150, salvage: { wood: 160 }, desc: 'A second stand of timber up the ridge.' },
  { id: 'e2', side: 'e', ring: 2, name: 'Freight Yard', terrain: 'containers', cost: { wood: 120, metal: 80, parts: 10, cash: 400 }, time: 160, salvage: { scrap: 120, metal: 40, parts: 10, electronics: 4 }, desc: 'Shipping containers, half of them still sealed.' },
  { id: 'w2', side: 'w', ring: 2, name: 'Old Orchard', terrain: 'orchard', cost: { wood: 140, metal: 50, parts: 6, cash: 350 }, time: 140, salvage: { food: 60, wood: 60 }, desc: 'Gnarled fruit trees and a well-drained slope.' },
  { id: 's2', side: 's', ring: 2, name: 'Roadside Strip', terrain: 'lot', cost: { wood: 120, metal: 70, parts: 8, cash: 380 }, time: 150, salvage: { scrap: 60, fuel: 15, cloth: 20 }, desc: 'The strip of road and a gutted gas kiosk. The gate moves south again.' },
]
export const EXPANSION_DEPTH = 10

// ---------------------------------------------------------------- city
// Loot pools: { r: resource, n: [min,max], w } or { i: itemId, w }
const P = {
  kitchen: [{ r: 'food', n: [2, 6], w: 6 }, { r: 'water', n: [2, 5], w: 5 }, { r: 'cloth', n: [1, 3], w: 1 }, { r: 'chemicals', n: [1, 2], w: 0.6 }],
  fridge: [{ r: 'food', n: [3, 7], w: 6 }, { r: 'water', n: [2, 6], w: 5 }],
  closet: [{ r: 'cloth', n: [3, 7], w: 6 }, { i: 'jacket', w: 0.5 }, { i: 'packS', w: 0.3 }, { i: 'shoes', w: 0.4 }, { r: 'cash', n: [5, 20], w: 1 }],
  desk: [{ r: 'cash', n: [5, 25], w: 4 }, { r: 'parts', n: [1, 2], w: 2 }, { r: 'electronics', n: [1, 2], w: 1.5 }, { r: 'cloth', n: [1, 3], w: 1 }, { i: 'flashlight', w: 0.4 }, { i: 'pistol', w: 0.12 }],
  books: [{ r: 'cloth', n: [1, 3], w: 2 }, { r: 'cash', n: [3, 12], w: 2 }, { r: 'wood', n: [2, 4], w: 2 }],
  trash: [{ r: 'cloth', n: [1, 3], w: 3 }, { r: 'scrap', n: [2, 5], w: 4 }, { r: 'parts', n: [1, 1], w: 1 }, { r: 'food', n: [1, 2], w: 2 }],
  shelf: [{ r: 'food', n: [3, 8], w: 6 }, { r: 'water', n: [3, 7], w: 5 }, { r: 'meds', n: [1, 2], w: 0.6 }, { r: 'cloth', n: [1, 4], w: 1 }, { r: 'chemicals', n: [1, 3], w: 0.8 }],
  register: [{ r: 'cash', n: [20, 60], w: 1 }],
  tools: [{ r: 'scrap', n: [3, 8], w: 4 }, { r: 'metal', n: [2, 5], w: 3 }, { r: 'parts', n: [1, 4], w: 3 }, { r: 'wood', n: [3, 8], w: 3 }, { r: 'chemicals', n: [1, 3], w: 1 }, { i: 'pipe', w: 0.5 }, { i: 'crowbar', w: 0.4 }, { i: 'bat', w: 0.3 }, { i: 'toolkit', w: 0.2 }, { i: 'lockpicks', w: 0.15 }, { i: 'machete', w: 0.12 }],
  medcab: [{ r: 'meds', n: [1, 4], w: 6 }, { r: 'cloth', n: [2, 4], w: 2 }, { r: 'chemicals', n: [1, 2], w: 1 }, { r: 'medkit', n: [1, 1], w: 0.8 }],
  locker: [{ r: 'cloth', n: [2, 5], w: 3 }, { r: 'pammo', n: [8, 24], w: 3 }, { r: 'cash', n: [10, 30], w: 2 }, { i: 'jacket', w: 0.4 }, { i: 'vest', w: 0.25 }, { i: 'flashlight', w: 0.4 }, { i: 'pistol', w: 0.25 }, { i: 'walkie', w: 0.08 }],
  gunlocker: [{ r: 'pammo', n: [20, 50], w: 4 }, { r: 'rammo', n: [10, 30], w: 2 }, { r: 'shells', n: [8, 20], w: 2 }, { i: 'pistol', w: 1 }, { i: 'revolver', w: 0.6 }, { i: 'shotgun', w: 0.5 }, { i: 'rifle', w: 0.3 }, { i: 'smg', w: 0.2 }, { i: 'ar', w: 0.07 }, { i: 'vest', w: 0.3 }],
  safe: [{ r: 'cash', n: [80, 220], w: 5 }, { r: 'meds', n: [2, 5], w: 1 }, { r: 'electronics', n: [2, 4], w: 1 }, { i: 'revolver', w: 0.4 }, { i: 'walkie', w: 0.2 }, { i: 'katana', w: 0.04 }],
  crate: [{ r: 'wood', n: [5, 12], w: 3 }, { r: 'scrap', n: [5, 12], w: 3 }, { r: 'metal', n: [3, 8], w: 2 }, { r: 'parts', n: [2, 5], w: 2 }, { r: 'cloth', n: [4, 9], w: 2 }, { r: 'fuel', n: [2, 5], w: 1 }, { r: 'electronics', n: [1, 3], w: 0.8 }],
  milcrate: [{ r: 'rammo', n: [20, 60], w: 4 }, { r: 'pammo', n: [20, 60], w: 3 }, { r: 'shells', n: [10, 24], w: 2 }, { r: 'parts', n: [4, 9], w: 2 }, { r: 'meds', n: [2, 5], w: 1 }, { r: 'electronics', n: [2, 5], w: 1 }, { r: 'pipebomb', n: [1, 2], w: 0.6 }, { i: 'rifle', w: 0.4 }, { i: 'smg', w: 0.35 }, { i: 'ar', w: 0.2 }, { i: 'military', w: 0.12 }, { i: 'riot', w: 0.2 }, { i: 'walkie', w: 0.3 }, { i: 'nvg', w: 0.06 }],
  car: [{ r: 'fuel', n: [2, 6], w: 4 }, { r: 'parts', n: [1, 3], w: 2 }, { r: 'electronics', n: [1, 2], w: 1 }, { r: 'cash', n: [5, 20], w: 1 }, { r: 'water', n: [1, 3], w: 1 }],
  pump: [{ r: 'fuel', n: [4, 10], w: 1 }],
  dumpster: [{ r: 'food', n: [1, 3], w: 2 }, { r: 'cloth', n: [2, 5], w: 3 }, { r: 'scrap', n: [3, 7], w: 4 }, { r: 'wood', n: [2, 5], w: 2 }, { r: 'parts', n: [1, 2], w: 1 }],
  electronic: [{ r: 'electronics', n: [2, 5], w: 5 }, { r: 'parts', n: [1, 3], w: 2 }, { r: 'scrap', n: [2, 4], w: 2 }],
  chem: [{ r: 'chemicals', n: [2, 6], w: 6 }, { r: 'fuel', n: [1, 3], w: 1 }, { r: 'meds', n: [1, 2], w: 0.5 }],
  fireLocker: [{ r: 'meds', n: [1, 3], w: 2 }, { r: 'cloth', n: [3, 6], w: 2 }, { r: 'medkit', n: [1, 1], w: 1 }, { i: 'axe', w: 0.6 }, { i: 'jacket', w: 0.5 }, { i: 'flashlight', w: 0.5 }, { i: 'crowbar', w: 0.5 }],
  shed: [{ r: 'wood', n: [4, 9], w: 3 }, { r: 'scrap', n: [3, 8], w: 3 }, { r: 'fuel', n: [1, 4], w: 2 }, { r: 'chemicals', n: [1, 3], w: 1 }, { i: 'crowbar', w: 0.3 }, { i: 'bat', w: 0.2 }],
}

// Containers: time = search seconds; strip = dismantle yield; locked needs lockpicks or smashing.
export const CONTAINERS = {
  fridge: { name: 'Fridge', pool: P.fridge, rolls: [1, 2], time: 2.5, strip: { scrap: [4, 7], parts: [0, 1] }, model: 'fridge' },
  cabinet: { name: 'Kitchen Cabinet', pool: P.kitchen, rolls: [1, 2], time: 2.5, strip: { wood: [3, 6] }, model: 'cabinet' },
  counter: { name: 'Kitchen Counter', pool: P.kitchen, rolls: [1, 2], time: 2.5, strip: { wood: [3, 5], scrap: [1, 2] }, model: 'counter' },
  wardrobe: { name: 'Wardrobe', pool: P.closet, rolls: [1, 2], time: 3, strip: { wood: [4, 8] }, model: 'wardrobe' },
  dresser: { name: 'Dresser', pool: P.closet, rolls: [1, 2], time: 2.5, strip: { wood: [3, 6] }, model: 'dresser' },
  desk: { name: 'Desk', pool: P.desk, rolls: [1, 2], time: 2.5, strip: { wood: [3, 6], scrap: [0, 2] }, model: 'desk' },
  filing: { name: 'Filing Cabinet', pool: P.desk, rolls: [1, 2], time: 2.5, strip: { scrap: [3, 6] }, model: 'filing' },
  bookshelf: { name: 'Bookshelf', pool: P.books, rolls: [1, 1], time: 2, strip: { wood: [4, 7] }, model: 'bookshelf' },
  trash: { name: 'Trash Can', pool: P.trash, rolls: [1, 1], time: 1.5, strip: { scrap: [1, 3] }, model: 'trash' },
  shelf: { name: 'Store Shelf', pool: P.shelf, rolls: [1, 3], time: 3, strip: { scrap: [3, 6], wood: [1, 3] }, model: 'shelf' },
  register: { name: 'Cash Register', pool: P.register, rolls: [1, 1], time: 2, strip: { parts: [1, 2], electronics: [1, 1] }, model: 'register' },
  toolrack: { name: 'Tool Rack', pool: P.tools, rolls: [1, 3], time: 3, strip: { scrap: [3, 6], wood: [2, 4] }, model: 'toolrack' },
  medcab: { name: 'Medicine Cabinet', pool: P.medcab, rolls: [1, 2], time: 2.5, strip: { scrap: [1, 3] }, model: 'medcab' },
  locker: { name: 'Locker', pool: P.locker, rolls: [1, 2], time: 3, strip: { scrap: [4, 7], metal: [0, 2] }, model: 'locker' },
  gunlocker: { name: 'Gun Locker', pool: P.gunlocker, rolls: [2, 3], time: 4, strip: { metal: [5, 9], parts: [1, 3] }, model: 'gunlocker', locked: true },
  safe: { name: 'Safe', pool: P.safe, rolls: [2, 3], time: 4, strip: { metal: [7, 12], parts: [1, 2] }, model: 'safe', locked: true },
  crate: { name: 'Supply Crate', pool: P.crate, rolls: [2, 3], time: 3, strip: { wood: [5, 9] }, model: 'crate' },
  pallet: { name: 'Pallet Rack', pool: P.crate, rolls: [2, 3], time: 3.5, strip: { metal: [4, 8], wood: [3, 6] }, model: 'pallet', big: true },
  milcrate: { name: 'Military Crate', pool: P.milcrate, rolls: [2, 3], time: 4, strip: { wood: [4, 7], metal: [2, 5] }, model: 'milcrate' },
  car: { name: 'Abandoned Car', pool: P.car, rolls: [1, 2], time: 3.5, strip: { scrap: [10, 16], parts: [2, 4], cloth: [1, 3], electronics: [0, 1] }, model: 'car', big: true },
  pump: { name: 'Fuel Pump', pool: P.pump, rolls: [1, 1], time: 4, strip: { scrap: [5, 9], parts: [1, 2] }, model: 'pump' },
  dumpster: { name: 'Dumpster', pool: P.dumpster, rolls: [1, 2], time: 3, strip: { scrap: [6, 10] }, model: 'dumpster' },
  server: { name: 'Server Rack', pool: P.electronic, rolls: [1, 2], time: 3.5, strip: { electronics: [1, 3], scrap: [3, 5] }, model: 'server' },
  tv: { name: 'Television', pool: P.electronic, rolls: [1, 1], time: 2, strip: { electronics: [1, 2], scrap: [1, 3] }, model: 'tv' },
  chemshelf: { name: 'Chemical Shelf', pool: P.chem, rolls: [1, 2], time: 3, strip: { scrap: [2, 4] }, model: 'chemshelf' },
  firelocker: { name: 'Gear Locker', pool: P.fireLocker, rolls: [1, 2], time: 3, strip: { scrap: [4, 7] }, model: 'locker' },
  shed: { name: 'Garden Shed', pool: P.shed, rolls: [2, 3], time: 3.5, strip: { wood: [8, 14], scrap: [2, 5] }, model: 'shed', big: true },
  toolchest: { name: 'Tool Chest', pool: P.tools, rolls: [1, 2], time: 3, strip: { metal: [2, 5], scrap: [3, 6] }, model: 'toolchest' },
}

// Room types drive both furniture and loot. containers/decor are weighted lists.
export const ROOMS = {
  kitchen: { floor: 'tiles', wall: '#d8d0bc', containers: { fridge: 1, counter: 2.5, cabinet: 1.5, trash: 0.6 }, decor: ['table', 'stove'], center: 'table' },
  living: { floor: 'planks', wall: '#c8b898', containers: { bookshelf: 1, tv: 0.8, dresser: 0.5 }, decor: ['sofa', 'armchair', 'rug', 'lamp'], center: 'coffeetable' },
  bedroom: { floor: 'carpet', wall: '#b8c0b0', containers: { wardrobe: 1, dresser: 1, desk: 0.4 }, decor: ['bed', 'nightstand', 'rug'] },
  bathroom: { floor: 'checker', wall: '#e0e4e0', containers: { medcab: 1 }, decor: ['toilet', 'tub', 'sink'] },
  garage: { floor: 'concrete', wall: '#a8a49a', containers: { toolrack: 1.5, toolchest: 1, shelf: 0.5, crate: 0.6 }, decor: ['workbenchDecor'], car: 0.7 },
  office: { floor: 'carpet', wall: '#c8ccc4', containers: { desk: 2, filing: 1.5, bookshelf: 0.5, server: 0.3 }, decor: ['chair', 'plant'] },
  storeroom: { floor: 'concrete', wall: '#b0aca0', containers: { crate: 2, shelf: 1.5, pallet: 0.5 }, decor: ['boxes'] },
  sales: { floor: 'linoleum', wall: '#d8d4c4', aisles: 'shelf', containers: { register: 1, fridge: 0.8 }, decor: [] },
  hardware: { floor: 'concrete', wall: '#c4bca8', aisles: 'toolrack', containers: { register: 1, toolchest: 1, chemshelf: 0.6 }, decor: [] },
  pharmacy: { floor: 'linoleum', wall: '#e4e8e4', aisles: 'shelf', containers: { register: 1, medcab: 3, safe: 0.3 }, decor: [] },
  diner: { floor: 'checker', wall: '#c8a890', containers: { register: 1, cabinet: 1 }, decor: ['booth', 'booth', 'booth', 'counterDecor'] },
  ward: { floor: 'linoleum', wall: '#d8e4e4', containers: { medcab: 1.5, locker: 0.6 }, decor: ['hospitalBed', 'hospitalBed', 'curtain'] },
  corridor: { floor: 'linoleum', wall: '#d0d4cc', containers: { trash: 0.5 }, decor: ['bench'] },
  lobby: { floor: 'tiles', wall: '#cfc8b8', containers: { desk: 1, register: 0.4 }, decor: ['bench', 'plant', 'chair'] },
  cells: { floor: 'concrete', wall: '#a8aca8', containers: { locker: 0.4 }, decor: ['cellBars', 'cot'] },
  armory: { floor: 'concrete', wall: '#8a9088', containers: { gunlocker: 2.5, locker: 1, milcrate: 0.4 }, decor: [] },
  lockers: { floor: 'tiles', wall: '#b8c0c8', containers: { locker: 3, firelocker: 0.5 }, decor: ['bench'] },
  warehouse: { floor: 'concrete', wall: '#9a968c', aisles: 'pallet', containers: { crate: 3, toolchest: 0.6 }, decor: ['forklift'] },
  classroom: { floor: 'linoleum', wall: '#d8d0b0', containers: { desk: 1, bookshelf: 1, filing: 0.5 }, decor: ['schoolDesks'] },
  lab: { floor: 'linoleum', wall: '#d8e0e0', containers: { chemshelf: 2, server: 0.6, filing: 0.6 }, decor: ['labBench'] },
  barracks: { floor: 'concrete', wall: '#7a8068', containers: { locker: 2, milcrate: 1.5 }, decor: ['cot', 'cot'] },
}

// Location types. size = building footprint (m). layout drives room generation.
export const LOCATIONS = {
  house: { name: 'House', level: 1, names: ['Maple St. House', 'Birch Lane House', 'Harlow House', 'Duplex on 3rd', 'Corner House', 'Pine Ave. House', 'Elm Row House'], size: [18, 14], wall: '#b9a58a', ext: 'siding', extColor: ['#c8b89a', '#a8b4b8', '#c8a888', '#9aa88a', '#d0c8b0'], layout: 'house', rooms: ['kitchen', 'living', 'bedroom', 'bedroom', 'bathroom', 'garage'], yard: 'house', blurb: 'Kitchens and closets. Food, water and cloth, maybe a garage.' },
  apartment: { name: 'Apartments', level: 1, names: ['Rosewood Apartments', 'Kestrel Flats', 'Elm Court', 'The Carlyle'], size: [26, 18], wall: '#a99c8c', ext: 'brick', layout: 'corridor', rooms: ['kitchen', 'living', 'bedroom', 'bathroom', 'kitchen', 'bedroom', 'living', 'bedroom', 'bathroom'], yard: 'street', blurb: 'A hallway of small flats. Food, cloth, a little cash.' },
  store: { name: 'Corner Store', level: 1, names: ['Quik Stop', 'Sunny Mart', 'Lucky 24', 'Corner Deli'], size: [18, 14], wall: '#c2b49a', ext: 'brick', layout: 'shop', rooms: ['sales', 'storeroom', 'office', 'bathroom'], yard: 'lot', sign: true, blurb: 'Shelves of snacks and bottled water.' },
  office: { name: 'Office', level: 2, names: ['Brightline Insurance', 'Halvorsen & Co.', 'County Records'], size: [24, 16], wall: '#c8c4b8', ext: 'concrete', layout: 'corridor', rooms: ['lobby', 'office', 'office', 'office', 'office', 'storeroom', 'bathroom'], yard: 'lot', sign: true, blurb: 'Desks, filing cabinets and computers: electronics and cash.' },
  diner: { name: 'Diner', level: 2, names: ["Rosie's Diner", 'Route 9 Grill', 'The Blue Plate'], size: [20, 14], wall: '#c9a98f', ext: 'siding', extColor: ['#b8d0c8', '#e0c8a8'], layout: 'shop', rooms: ['diner', 'kitchen', 'storeroom', 'bathroom'], yard: 'lot', sign: true, blurb: 'Walk-in fridges and pantry stock.' },
  gas: { name: 'Gas Station', level: 2, names: ['Petro Plus', 'Gulf Line Fuel', 'Highway 12 Gas'], size: [16, 12], wall: '#bcb3a3', ext: 'concrete', layout: 'shop', rooms: ['sales', 'storeroom', 'garage'], yard: 'forecourt', sign: true, blurb: 'Fuel pumps, snacks and car parts.' },
  hardware: { name: 'Hardware Store', level: 2, names: ["Hank's Hardware", 'Ironside Supply', 'Builders Depot'], size: [24, 18], wall: '#a69a86', ext: 'brick', layout: 'shop', rooms: ['hardware', 'storeroom', 'office', 'garage'], yard: 'lot', sign: true, blurb: 'Wood, metal, parts, chemicals, and the odd blade.' },
  pharmacy: { name: 'Pharmacy', level: 3, names: ['CarePlus Pharmacy', 'Main St. Chemist', 'Wellway Drugs'], size: [18, 14], wall: '#d2cdc2', ext: 'brick', layout: 'shop', rooms: ['pharmacy', 'storeroom', 'office', 'lab'], yard: 'lot', sign: true, blurb: 'Medicine, first aid kits and chemicals.' },
  supermarket: { name: 'Supermarket', level: 3, names: ['FreshWay Market', 'Grand Grocer', 'ValuMart'], size: [32, 22], wall: '#c6bca6', ext: 'concrete', layout: 'shop', rooms: ['sales', 'storeroom', 'storeroom', 'office', 'kitchen', 'bathroom'], yard: 'parking', sign: true, blurb: 'Huge food and water stocks. Big and crowded.' },
  garage: { name: 'Auto Garage', level: 3, names: ["Dale's Auto Body", 'Precision Motors', 'Southside Garage'], size: [26, 18], wall: '#9f9586', ext: 'corrugated', layout: 'shop', rooms: ['garage', 'garage', 'office', 'storeroom'], yard: 'lot', sign: true, blurb: 'Cars to strip, fuel, scrap and parts.' },
  school: { name: 'School', level: 3, names: ['Lincoln Elementary', 'Westbrook High', 'St. Mary\'s School'], size: [32, 22], wall: '#c8b8a0', ext: 'brick', layout: 'corridor', rooms: ['classroom', 'classroom', 'classroom', 'lab', 'office', 'kitchen', 'lockers', 'bathroom'], yard: 'parking', sign: true, blurb: 'Classrooms, a science lab and a cafeteria. Wide hallways.' },
  hospital: { name: 'Hospital', level: 4, names: ['St. Agnes Hospital', 'County General', 'Mercy Medical'], size: [34, 24], wall: '#d8d4cc', ext: 'concrete', layout: 'corridor', rooms: ['lobby', 'ward', 'ward', 'ward', 'pharmacy', 'office', 'lab', 'lockers', 'storeroom'], yard: 'parking', sign: true, zombieTheme: 'hospital', blurb: 'Medicine, first aid, and far too many patients.' },
  firestation: { name: 'Fire Station', level: 4, names: ['Engine Co. 9', 'Station 14', 'Northside Fire Dept.'], size: [28, 20], wall: '#c8a090', ext: 'brick', layout: 'shop', rooms: ['garage', 'lockers', 'kitchen', 'office', 'barracks'], yard: 'lot', sign: true, zombieTheme: 'worker', blurb: 'Axes, first aid, protective gear and fuel.' },
  gunstore: { name: 'Gun Store', level: 4, names: ['Liberty Arms', 'Deadeye Outfitters', 'Ridgeline Firearms'], size: [20, 14], wall: '#8f8578', ext: 'concrete', layout: 'shop', rooms: ['armory', 'sales', 'storeroom', 'office'], yard: 'lot', sign: true, blurb: 'Locked gun racks and ammunition. Bring lockpicks.' },
  warehouse: { name: 'Warehouse', level: 4, names: ['Dockside Warehouse', 'Northgate Logistics', 'Cold Storage 7'], size: [34, 24], wall: '#8e8a82', ext: 'corrugated', layout: 'hall', rooms: ['warehouse', 'office', 'storeroom', 'garage'], yard: 'loading', blurb: 'Pallets of raw materials. A wide open floor.' },
  police: { name: 'Police Station', level: 5, names: ['7th Precinct', 'Harbor PD', 'Central Station'], size: [30, 22], wall: '#9aa0a6', ext: 'concrete', layout: 'corridor', rooms: ['lobby', 'office', 'office', 'cells', 'armory', 'lockers', 'storeroom'], yard: 'parking', sign: true, zombieTheme: 'police', blurb: 'Armor, guns, ammo and radios. Heavily infected.' },
  military: { name: 'Military Checkpoint', level: 5, names: ['Checkpoint Bravo', 'FOB Harlan', 'Quarantine Gate 3'], size: [32, 24], wall: '#6f7560', ext: 'concrete', layout: 'compound', rooms: ['armory', 'barracks', 'barracks', 'storeroom', 'office', 'ward'], yard: 'checkpoint', zombieTheme: 'military', blurb: 'Military crates hold the best gear in the city.' },
}
export const LEVEL_COLORS = ['#7cc36a', '#c8c64a', '#e8a33d', '#e36b3a', '#d8384a']

// ---------------------------------------------------------------- zombies
export const ZOMBIES = {
  walker: { name: 'Walker', hp: 50, speed: 1.25, dmg: 8, rate: 1.3, scale: 1, sight: 6, xp: 4 },
  runner: { name: 'Runner', hp: 34, speed: 3.2, dmg: 6, rate: 0.9, scale: 0.94, sight: 8, xp: 5, build: 0.88 },
  brute: { name: 'Brute', hp: 210, speed: 1.0, dmg: 22, rate: 1.7, scale: 1.25, sight: 5, xp: 14, build: 1.35 },
  crawler: { name: 'Crawler', hp: 40, speed: 0.6, dmg: 10, rate: 1.1, scale: 1, sight: 4, xp: 4, crawl: true },
  armored: { name: 'Riot Walker', hp: 70, speed: 1.15, dmg: 9, rate: 1.3, scale: 1, sight: 6, xp: 8, armor: 0.45 },
}
export function zombieMix(level, theme = null) {
  const m = [
    { t: 'walker', w: 10 },
    { t: 'runner', w: level >= 2 ? 1 + level * 1.2 : 0.4 },
    { t: 'crawler', w: 1.2 },
    { t: 'brute', w: level >= 3 ? level * 0.7 - 1 : 0 },
  ]
  if (theme === 'police' || theme === 'military') m.push({ t: 'armored', w: level * 0.8 })
  return m
}

export const HORDES = [
  { id: 'small', name: 'Small horde', min: 5, max: 8 },
  { id: 'medium', name: 'Horde', min: 10, max: 15 },
  { id: 'large', name: 'Large horde', min: 18, max: 26 },
  { id: 'huge', name: 'Massive horde', min: 30, max: 42 },
]

// ---------------------------------------------------------------- goals
export const GOALS = [
  { id: 'assignFarm', text: 'Assign a survivor to the Farm Plot', reward: { cash: 40 } },
  { id: 'buildFilter', text: 'Build a Water Filter', reward: { wood: 20, metal: 10 } },
  { id: 'firstRun', text: 'Send a squad on a supply run', reward: { cash: 60 } },
  { id: 'buildLumber', text: 'Build a Lumber Yard or Scrap Yard', reward: { parts: 4 } },
  { id: 'buildForge', text: 'Build a Forge to smelt scrap into metal', reward: { metal: 20 } },
  { id: 'surviveHorde', text: 'Survive your first horde', reward: { pammo: 60, meds: 3 } },
  { id: 'recruit', text: 'Recruit a new survivor', reward: { food: 25 } },
  { id: 'craft', text: 'Craft an item at any bench', reward: { cash: 80 } },
  { id: 'expand', text: 'Expand the camp', reward: { cash: 150, wood: 40 } },
  { id: 'loot3', text: 'Loot a level 3 location', reward: { parts: 10 } },
  { id: 'chemlab', text: 'Build a Chemistry Lab and an Ammo Press', reward: { chemicals: 10 } },
  { id: 'generator', text: 'Build a Generator', reward: { fuel: 20 } },
  { id: 'automate', text: 'Automate a station with a module and power', reward: { cash: 250 } },
  { id: 'master', text: 'Craft a Masterwork item', reward: { cash: 200, parts: 10 } },
  { id: 'rescue', text: 'Answer a distress call and bring the survivor home', reward: { cash: 150 } },
  { id: 'pop10', text: 'Grow the camp to 10 survivors', reward: { cash: 250 } },
  { id: 'fence2', text: 'Upgrade the perimeter to a Log Palisade', reward: { pammo: 80 } },
  { id: 'day7', text: 'Survive 7 days', reward: { cash: 300, parts: 15 } },
  { id: 'expandAll', text: 'Expand the camp in every direction', reward: { cash: 500 } },
  { id: 'loot5', text: 'Loot a level 5 location', reward: { cash: 400 } },
  { id: 'pop20', text: 'Grow the camp to 20 survivors', reward: { cash: 600 } },
  { id: 'day30', text: 'Survive 30 days', reward: { cash: 1500 } },
]
