// Builds Chernarus's loot table for the coach (src/profiles/dayz/items.chernarus.json)
// from Bohemia Interactive's DayZ Central Economy files (ADPL-SA):
//
//   - db/types.xml: every item that spawns (nominal > 0), the kinds of
//     building it spawns in (usage), its loot tiers and how many are kept on
//     the map; colour variants are grouped under one item;
//   - areaflags.map: the loot tier of every spot on the map (rows from the
//     south edge, one byte of tier bits per cell after the usage layer),
//     reduced to a 256 × 256 grid of the highest tier in each 60 m square.
//
// Item names are the class names made readable, with the in-game name or
// the name players use where it differs (BandageDressing → Bandage).
//
//   node scripts/build-dayz-items.mjs <central-economy-repo> [chernarus|livonia|sakhal]

import { readFileSync, writeFileSync } from 'node:fs'
import { join, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'

const [ceDir, mapArg = 'chernarus'] = process.argv.slice(2)
const FOLDERS = { chernarus: ['dayzOffline.chernarusplus', 'Chernarus'], livonia: ['dayzOffline.enoch', 'Livonia'], sakhal: ['dayzOffline.sakhal', 'Sakhal'] }
if (!ceDir || !FOLDERS[mapArg]) {
  console.error('usage: node scripts/build-dayz-items.mjs <central-economy-repo> [chernarus|livonia|sakhal]')
  process.exit(1)
}
const [FOLDER, MAP_NAME] = FOLDERS[mapArg]
const OUT = join(dirname(fileURLToPath(import.meta.url)), '..', 'src', 'profiles', 'dayz', `items.${mapArg}.json`)
const CE = join(ceDir, FOLDER)

// --- Loot tiers -------------------------------------------------------------------------------

const flags = readFileSync(join(CE, 'areaflags.map'))
const [W, H, worldW] = [flags.readUInt32LE(0), flags.readUInt32LE(4), flags.readUInt32LE(8)]
const N = W * H
const values = flags.subarray(24 + 4 * N, 24 + 5 * N)
const G = 256
const f = W / G
const tiers = new Uint8Array(G * G)
for (let gy = 0; gy < G; gy++)
  for (let gx = 0; gx < G; gx++) {
    let best = 0
    for (let y = gy * f; y < (gy + 1) * f; y += 2)
      for (let x = gx * f; x < (gx + 1) * f; x += 2) {
        const v = values[y * W + x]
        const t = v & 8 ? 4 : v & 4 ? 3 : v & 2 ? 2 : v & 1 ? 1 : 0
        if (t > best) best = t
      }
    // Stored north row first, like the terrain grids.
    tiers[(G - 1 - gy) * G + gx] = best
  }
const rle = (a) => {
  const out = []
  for (let i = 0; i < a.length; ) {
    let j = i + 1
    while (j < a.length && a[j] === a[i] && j - i < 255) j++
    out.push(a[i], j - i)
    i = j
  }
  return Buffer.from(out).toString('base64')
}

// --- Items ------------------------------------------------------------------------------------

const VARIANT = /_(Black|Green|Blue|Red|Brown|Grey|Gray|Beige|White|Pink|Camo|Olive|Orange|Yellow|Violet|Maroon|Tan|Woodland|Desert|Winter|Autumn|Summer|Ttsko|Medical|Party|BDU|UN|Navy|Khaki|Dark|Light|Lime|Purple|Checker|Wood|Flat|Shiny|Dull|MenAtArms|Lunapark|Polkapattern|Blackpattern|Camopattern|Greenpattern|Redpattern|Skull|Blackskull|Chain|Pig|Black_White|Red_White|Blue_Gray|Green_Gray|Gray_Black|CMMG_Black|CMMG_Pink|NewYork|Muted|Arctic|Floral|Rainbow|Plaid|Striped|Autumn_Camo|Summer_Camo|ChernarusFlag|Spring|Fall)$/
const NAMES = {
  BandageDressing: ['Bandage', ['bandages', 'bandage dressing']],
  Rag: ['Rags', ['rag']],
  CharcoalTablets: ['Charcoal tablets', ['charcoal']],
  DisinfectantSpray: ['Disinfectant spray', ['disinfectant']],
  DisinfectantAlcohol: ['Alcohol tincture', ['alcohol', 'tincture']],
  TetracyclineAntibiotics: ['Tetracycline', ['antibiotics', 'tetra']],
  VitaminBottle: ['Multivitamins', ['vitamins', 'multivitamin']],
  PurificationTablets: ['Water purification tablets', ['purification tablets', 'water tablets']],
  PainkillerTablets: ['Painkillers', ['codeine', 'painkiller tablets']],
  Morphine: ['Morphine', ['morphine auto-injector']],
  Epinephrine: ['Epinephrine', ['epi', 'epipen', 'adrenaline']],
  AntiChemInjector: ['PO-X antidote', ['pox', 'po-x', 'antidote']],
  BloodBagEmpty: ['Blood bag (empty)', ['blood bag']],
  BloodTestKit: ['Blood test kit', ['blood test']],
  StartKitIV: ['IV start kit', ['iv kit']],
  SalineBag: ['Saline bag', ['saline']],
  FirstAidKit: ['First aid kit', ['med kit', 'medkit']],
  Canteen: ['Canteen', ['water canteen']],
  WaterBottle: ['Water bottle', ['bottle', 'plastic bottle']],
  ChernarusMap: ['Map of Chernarus', ['map', 'paper map']],
  Compass: ['Compass', []],
  GPSReceiver: ['GPS receiver', ['gps']],
  Binoculars: ['Binoculars', ['binos']],
  Rangefinder: ['Rangefinder', []],
  NVGoggles: ['Night vision goggles', ['nvg', 'night vision', 'nvgs']],
  PersonalRadio: ['Walkie-talkie', ['radio', 'walkie talkie', 'personal radio']],
  Matchbox: ['Matches', ['matchbox', 'match']],
  Heatpack: ['Heat pack', ['heatpack', 'hand warmer']],
  Flashlight: ['Flashlight', ['torch']],
  Headtorch: ['Head torch', ['headlamp', 'headtorch']],
  Battery9V: ['9V battery', ['battery', 'batteries']],
  CombatKnife: ['Combat knife', ['knife']],
  KitchenKnife: ['Kitchen knife', []],
  HuntingKnife: ['Hunting knife', []],
  SteakKnife: ['Steak knife', []],
  Hatchet: ['Hatchet', []],
  WoodAxe: ['Wood axe', ['axe']],
  FirefighterAxe: ['Firefighter axe', ['fire axe']],
  Pliers: ['Pliers', []],
  Screwdriver: ['Screwdriver', []],
  Hammer: ['Hammer', []],
  Shovel: ['Shovel', []],
  FieldShovel: ['Field shovel', ['entrenching tool']],
  Pickaxe: ['Pickaxe', []],
  Hacksaw: ['Hacksaw', ['saw']],
  HandSaw: ['Hand saw', []],
  Crowbar: ['Crowbar', []],
  Sickle: ['Sickle', []],
  CanOpener: ['Can opener', []],
  SewingKit: ['Sewing kit', []],
  LeatherSewingKit: ['Leather sewing kit', []],
  WeaponCleaningKit: ['Weapon cleaning kit', ['cleaning kit', 'gun cleaning kit']],
  ElectronicRepairKit: ['Electronic repair kit', []],
  TireRepairKit: ['Tyre repair kit', ['tire repair kit']],
  EpoxyPutty: ['Epoxy putty', []],
  DuctTape: ['Duct tape', ['tape']],
  Rope: ['Rope', []],
  Netting: ['Netting', []],
  BurlapSack: ['Burlap sack', []],
  Pot: ['Cooking pot', ['pot']],
  FryingPan: ['Frying pan', ['pan']],
  PortableGasStove: ['Gas stove', ['stove', 'portable stove']],
  SmallGasCanister: ['Gas canister (small)', ['gas canister']],
  CanisterGasoline: ['Jerry can', ['jerrycan', 'fuel can', 'gasoline canister']],
  CarBattery: ['Car battery', []],
  SparkPlug: ['Spark plug', []],
  CarRadiator: ['Radiator', []],
  HeadlightH7: ['Headlight bulb', ['h7 bulb']],
  GasMask: ['Gas mask', ['gasmask']],
  GP5GasMask: ['GP-5 gas mask', []],
  AirborneMask: ['Airborne mask', []],
  NBCHoodGray: ['NBC hood', []],
  NBCJacketGray: ['NBC jacket', ['nbc suit']],
  NBCPantsGray: ['NBC pants', []],
  NBCBootsGray: ['NBC boots', []],
  NBCGlovesGray: ['NBC gloves', []],
  PlateCarrierVest: ['Plate carrier', ['plate carrier vest', 'armour vest', 'armor vest']],
  PressVest: ['Press vest', []],
  PoliceVest: ['Police vest', []],
  HighCapacityVest: ['High capacity vest', []],
  SmershVest: ['Smersh vest', []],
  UKAssVest: ['Assault vest', []],
  BallisticHelmet: ['Ballistic helmet', []],
  Mich2001Helmet: ['Tactical helmet', ['mich helmet']],
  GorkaHelmet: ['Assault helmet', ['gorka helmet']],
  TaloonBag: ['Taloon backpack', ['taloon']],
  MountainBag: ['Mountain backpack', ['mountain bag']],
  HuntingBag: ['Hunting backpack', ['hunting bag']],
  AliceBag: ['Field backpack (ALICE)', ['alice', 'alice bag', 'field backpack']],
  AssaultBag: ['Assault backpack', ['assault bag']],
  CoyoteBag: ['Coyote backpack', ['coyote', 'coyote bag']],
  SmershBag: ['Smersh backpack', ['smersh bag']],
  TortillaBag: ['Tortilla bag', ['tortilla']],
  DryBag: ['Dry bag', ['drybag']],
  ChildBag: ["Children's backpack", ['child bag', 'kids backpack']],
  DuffelBagSmall: ['Duffel bag', ['duffel']],
  CourierBag: ['Courier bag', []],
  CanvasBag: ['Canvas bag', []],
  Attack2Bag: ['Combat backpack', []],
  ArmyPouch: ['Army pouch', []],
  Slingbag: ['Sling bag', []],
  HipPack: ['Hip pack', ['bum bag', 'fanny pack']],
  AmmoBox: ['Ammo box', []],
  SmallProtectorCase: ['Protector case', ['protector case']],
  BarbedWire: ['Barbed wire', []],
  CombinationLock: ['Combination lock', ['code lock', 'lock']],
  CombinationLock4: ['Four-digit combination lock', []],
  MetalWire: ['Metal wire', ['wire']],
  Nail: ['Nails', ['nail', 'box of nails']],
  NailBox: ['Box of nails', ['nails box']],
  Camonet: ['Camo net', ['camonet']],
  FishingRod: ['Fishing rod', []],
  Hook: ['Fishing hook', ['hook']],
  BearTrap: ['Bear trap', []],
  Rice: ['Rice', []],
  PowderedMilk: ['Powdered milk', []],
  TacticalBaconCan: ['Tactical bacon', []],
  BakedBeansCan: ['Baked beans', ['beans']],
  SpaghettiCan: ['Spaghetti', []],
  SardinesCan: ['Sardines', []],
  TunaCan: ['Tuna', []],
  PeachesCan: ['Peaches', []],
  SodaCan: ['Soda', ['soda can', 'pop', 'cola']],
  Zagorky: ['Zagorky bar', ['chocolate bar']],
}
const camel = (s) => s.replace(/_/g, ' ').replace(/([a-z])([A-Z0-9])/g, '$1 $2').replace(/([A-Z]+)([A-Z][a-z])/g, '$1 $2').replace(/\s+/g, ' ').trim()
const sentenceCase = (s) => s.charAt(0).toUpperCase() + s.slice(1).toLowerCase().replace(/\b(nbc|gps|iv|ak|m4|nvg|po-x|ttsko|bdu|un|9v|h7)\b/gi, (m) => m.toUpperCase())

/** The kind of thing, for "where do I find a backpack" and the checklist. */
function kindOf(id, cat) {
  if (/^(Mag_|Ammo_|AmmoBox_)/.test(id)) return id.startsWith('Mag_') ? 'magazine' : 'ammo'
  if (/Optic$|Suppressor|Bttstck|Hndgrd|Bayonet|Compensator|Light$|Mount|Bipod/.test(id) && cat === 'weapons') return 'attachment'
  if (cat === 'containers') return /Tent|CarTent/.test(id) ? 'tent' : /Bag|Pack|Pouch|Case|Box/.test(id) ? 'backpack' : 'container'
  if (cat === 'food') return /Canteen|SodaCan|Water/.test(id) ? 'drink' : 'food'
  if (/Bandage|Rag|Disinfect|Tetracycline|Vitamin|Purification|Painkiller|Morphine|Epinephrine|AntiChem|Blood|Saline|IV|FirstAid|Splint|Charcoal/.test(id)) return 'medical'
  if (/Knife|Axe|Hatchet|Machete|Sword|Bat|Pipe|Crowbar|Shovel|Pickaxe|Sickle|Cleaver|Hammer|Mace|Baton|Knuckles/.test(id)) return 'tool or melee'
  if (cat === 'clothes') return /Helmet/.test(id) ? 'helmet' : /Vest/.test(id) ? 'vest' : /Mask|Balaclava/.test(id) ? 'mask' : /Boots|Shoes|Sneakers|Wellies/.test(id) ? 'footwear' : /Gloves/.test(id) ? 'gloves' : /Pants|Jeans|Trousers|Skirt|Shorts/.test(id) ? 'trousers' : /Jacket|Coat|Hoodie|Shirt|Sweater|Top|Blouse|Parka|Tshirt|Gorka/.test(id) ? 'jacket or top' : /Hat|Cap|Beanie|Bandana|Helmet|Hood/.test(id) ? 'hat' : 'clothing'
  if (cat === 'explosives') return 'explosive'
  return 'tool'
}

const x = readFileSync(join(CE, 'db', 'types.xml'), 'utf8')
const groups = new Map()
for (const m of x.matchAll(/<type name="([^"]+)">([\s\S]*?)<\/type>/g)) {
  const [, id, b] = m
  const nominal = Number(b.match(/<nominal>(\d+)/)?.[1] ?? 0)
  const cat = b.match(/<category name="(\w+)"/)?.[1] ?? 'none'
  if (!nominal || cat === 'lootdispatch' || cat === 'none') continue
  // Guns themselves are in the arsenal; their ammo, magazines and parts are items.
  if (cat === 'weapons' && !/^(Mag_|Ammo_|AmmoBox_)|Optic|Suppressor|Bttstck|Hndgrd|Bayonet|Compensator|Light$|Mount|Bipod/.test(id)) continue
  const base = id.replace(VARIANT, '').replace(VARIANT, '')
  const g = groups.get(base) ?? { id: base, cat, nominal: 0, usage: new Set(), tiers: new Set(), deloot: false, variants: 0 }
  g.nominal += nominal
  g.variants++
  for (const u of b.matchAll(/<usage name="(\w+)"/g)) g.usage.add(u[1])
  for (const t of b.matchAll(/<value name="Tier(\d)"/g)) g.tiers.add(Number(t[1]))
  g.deloot ||= /deloot="1"/.test(b)
  groups.set(base, g)
}

// Ammo, magazines and gun parts: by calibre and by the gun's in-game name.
const CALIBRE = [
  [/^762x39/, '7.62x39mm'], [/^545x39/, '5.45x39mm'], [/^556x45/, '5.56x45mm'], [/^762x54/, '7.62x54mmR'], [/^308Win/, '.308 WIN'],
  [/^9x39AP/, '9x39mm armour-piercing'], [/^9x39/, '9x39mm'], [/^9x19/, '9x19mm'], [/^45ACP/, '.45 ACP'], [/^380/, '.380 ACP'], [/^357/, '.357'], [/^22/, '.22 LR'],
  [/^12gaPellets|^00buck/i, '12ga buckshot'], [/^12gaRubberSlug/, '12ga rubber slugs'], [/^12gaSlug/, '12ga slugs'], [/^40mm_(\w+)/, '40mm grenade'], [/^HuntingBolt/, 'crossbow bolts'],
]
const GUN = { AKS74U: 'KAS-74U', Fal: 'LAR', AKM: 'KA-M', AK74: 'KA-74', AK101: 'KA-101', CMAG: 'standardised (M4-A1, M16-A2)', STANAG: 'STANAG (M4-A1, M16-A2)', Glock: 'Mlock-91', MP5: 'SG5-K', UMP: 'USG-45', CZ61: 'CR-61', CZ75: 'CR-75', FNX45: 'FX-45', '1911': 'Kolt 1911', IJ70: 'IJ-70', MKII: 'MK II', Ruger1022: 'Sporter 22', Deagle: 'Deagle', CZ527: 'CR-527', CZ550: 'CR-550', FAL: 'LAR', SCAR: 'SCR 17', M14: 'DMR', SVD: 'VSD', VSS: 'VSS', Saiga: 'Vaiga', Scout: 'Pioneer', PP19: 'Bizon', SSG82: 'SSG 82', SV98: 'VS-89', Famas: 'LE-MAS', FAMAS: 'LE-MAS', Aug: 'AUR', PM73: 'RAK-37', Vikhr: 'Vikhr', ASVAL: 'SVAL', P1: 'P1', Mosin: 'Mosin', SKS: 'SK 59/66', Ruger: 'Sporter 22', Akm: 'KA-M', AK: 'KA' }
const PARTS = { ACOGOptic: 'ATOG 4x32 scope', ACOGOptic_6x: 'ATOG 6x48 scope', M68Optic: 'Combat sight', M4_T3NRDSOptic: 'RVN sight', ReflexOptic: 'Mini sight', HoloOptic: 'Baraka sight', BUISOptic: 'Backup iron sights', PSO1Optic: 'PSO-1 scope', PSO11Optic: 'PSO-1-1 scope', KashtanOptic: 'Kashtan scope', KobraOptic: 'Kobra sight', PUScopeOptic: 'PU scope', HuntingOptic: 'Hunting scope', SportingOptic: 'Sportpoint sight', StarlightOptic: 'NV-PVS4 scope', PistolOptic: 'Handgun scope', FNP45_MRDSOptic: 'Pistol red dot', AK_Suppressor: 'KA suppressor', M4_Suppressor: 'Standardised suppressor', PistolSuppressor: 'Pistol suppressor', TLRLight: 'Pistol flashlight', UniversalLight: 'Universal flashlight', ImprovisedSuppressor: 'Plastic bottle suppressor' }
function gunPart(id) {
  if (PARTS[id]) return PARTS[id]
  const m = id.match(/^([A-Za-z0-9]+)_(.+)$/)
  const gun = m ? (GUN[m[1]] ?? m[1]) : null
  const what = (m ? m[2] : id).replace(/Bttstck/, ' buttstock').replace(/Hndgrd/, ' handguard').replace(/Optic/, ' sight').replace(/Bayonet/, ' bayonet').replace(/Compensator/, ' compensator')
  return sentenceCase(`${gun ? `${gun} ` : ''}${camel(what)}`).replace(/\b(ka|ka-m|ka-74|ka-101|sg5-k|lar|scr 17|dmr|vsd|sval|aur)\b/gi, (x) => x.toUpperCase())
}
function ammoName(id) {
  const box = id.startsWith('AmmoBox_')
  const rest = id.replace(/^AmmoBox_|^Ammo_/, '')
  const cal = CALIBRE.find(([re]) => re.test(rest))
  const tracer = /Tracer/i.test(rest) ? ' (tracer)' : ''
  const count = rest.match(/(\d+)rnd/i)?.[1]
  return cal ? `${cal[1]}${/(slugs|buckshot|bolts)$/.test(cal[1]) ? '' : ' rounds'}${tracer}${box ? `, box${count ? ` of ${count}` : ''}` : ''}` : sentenceCase(camel(id))
}
function magName(id) {
  const m = id.match(/^Mag_([A-Za-z0-9]+?)_(?:Drum)?(\d+)[Rr]nd/)
  if (!m) return sentenceCase(camel(id.replace(/^Mag_/, 'Magazine ')))
  return `${GUN[m[1]] ?? m[1]} magazine, ${m[2]} rounds${/Drum/.test(id) ? ' (drum)' : ''}`
}

const items = [...groups.values()]
  .map((g) => {
    const named = NAMES[g.id]
    const name =
      named?.[0] ??
      (/^(Ammo_|AmmoBox_)/.test(g.id) ? ammoName(g.id) : g.id.startsWith('Mag_') ? magName(g.id) : g.cat === 'weapons' ? gunPart(g.id) : sentenceCase(camel(g.id)))
    return {
      id: g.id,
      name,
      aka: named?.[1] ?? [],
      kind: kindOf(g.id, g.cat),
      usage: [...g.usage].sort(),
      tiers: [...g.tiers].sort(),
      nominal: g.nominal,
      ...(g.deloot ? { deloot: true } : {}),
    }
  })
  .sort((a, b) => a.name.localeCompare(b.name))

writeFileSync(
  OUT,
  `${JSON.stringify({
    source: `Bohemia Interactive DayZ Central Economy, ${MAP_NAME} (db/types.xml and areaflags.map), ADPL-SA. Names made readable from the class names.`,
    licence: 'ADPL-SA: non-commercial, DayZ/Arma only, share alike.',
    worldSize: worldW,
    tiers: { size: G, rle: rle(tiers) },
    items,
  })}\n`,
)
console.log(items.length, 'items →', OUT, `(${W}×${H} flags)`)
const t = (x0, y0) => tiers[Math.floor((1 - y0 / worldW) * G) * G + Math.floor((x0 / worldW) * G)]
if (mapArg === 'chernarus') console.log('tiers: Cherno', t(6750, 2500), 'Elektro', t(10300, 2200), 'NWAF', t(4600, 10300), 'Tisy', t(1700, 14000))
for (const n of ['Bandage', 'Canteen', 'Morphine', 'Taloon backpack', 'Compass', 'Map of Chernarus', 'Gas mask']) console.log(JSON.stringify(items.find((i) => i.name === n)))
