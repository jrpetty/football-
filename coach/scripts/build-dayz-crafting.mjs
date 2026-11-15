// Builds the DayZ pack's crafting recipes and vehicle parts
// (src/profiles/dayz/crafting.json) from two sources:
//
//   - Bohemia Interactive's DayZ game scripts (DayZ-Script-Diff, DayZ Public
//     License, DPL: attribution, non-commercial, DayZ only):
//       * scripts/4_world/classes/recipes/recipes/*.c — every recipe that
//         pluginrecipesmanagerbase.c registers (the ones the game uses): its
//         two ingredients (InsertIngredient), what happens to each
//         (m_IngredientDestroy, m_IngredientAddQuantity, m_IngredientAddHealth),
//         the minimum quantity (m_MinQuantityIngredient), the condition limits
//         (m_Min/MaxDamageIngredient), the results (AddResult,
//         m_ResultSetQuantity) and the extra rules in CanDo() and Do(),
//         written here in plain English;
//       * scripts/4_world/entities/vehicles (carscript.c, motorbikescript.c,
//         boatscript.c and the inherited*/ classes) and
//         scripts/3_game/vehicles/transport.c — what each vehicle needs for the
//         engine to start (CheckOperationalRequirements, IsVitalSparkPlug,
//         IsVitalGlowPlug, IsVitalRadiator, NeedElectricitySourceDevice), its
//         battery type, wheels, doors and spare wheel (OnDebugSpawn,
//         CanManipulateSpareWheel) and headlight slots.
//   - the DayZ Wiki (dayz.fandom.com, CC BY-NC-SA 3.0) for what the scripts
//     do not hold: the in-game vehicle and part names, the fuel tank sizes
//     and the radiator's coolant capacity (the game keeps these in its config
//     files, not in the scripts). The wiki values are the hand-written table
//     WIKI below, read through the wiki's API on 2026-11-15.
//
// Item names are the class names made readable (the helper from
// build-dayz-items.mjs), with the in-game name where it differs, checked
// against items.chernarus.json, weapons.json and the wiki.
//
// Recipes that are not registered (commented out by Bohemia, such as all the
// spray-paint recipes and CraftBait) and the debug/test classes are left out;
// the script prints them.
//
//   node scripts/build-dayz-crafting.mjs <DayZ-Script-Diff repo>

import { readFileSync, readdirSync, writeFileSync } from 'node:fs'
import { join, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'

const [repo, debug] = process.argv.slice(2)
if (!repo) {
  console.error('usage: node scripts/build-dayz-crafting.mjs <DayZ-Script-Diff repo>')
  process.exit(1)
}
const PACK = join(dirname(fileURLToPath(import.meta.url)), '..', 'src', 'profiles', 'dayz')
const OUT = join(PACK, 'crafting.json')
const SCRIPTS = join(repo, 'scripts')
const RECIPE_DIR = join(SCRIPTS, '4_world', 'classes', 'recipes', 'recipes')
const VEHICLE_DIR = join(SCRIPTS, '4_world', 'entities', 'vehicles')

// --- Reading Enforce Script ---------------------------------------------------------------------

/** The source without comments (strings kept as they are). */
function strip(src) {
  let out = ''
  for (let i = 0; i < src.length; ) {
    const c = src[i]
    if (c === '"') {
      let j = i + 1
      while (j < src.length && src[j] !== '"') j += src[j] === '\\' ? 2 : 1
      out += src.slice(i, j + 1)
      i = j + 1
    } else if (c === '/' && src[i + 1] === '/') {
      while (i < src.length && src[i] !== '\n') i++
    } else if (c === '/' && src[i + 1] === '*') {
      const j = src.indexOf('*/', i + 2)
      i = j < 0 ? src.length : j + 2
      out += ' '
    } else {
      out += c
      i++
    }
  }
  return out
}
const read = (...p) => strip(readFileSync(join(...p), 'utf8'))

/** The body of the first block whose header matches `re` (the text between its braces). */
function block(src, re) {
  const m = re.exec(src)
  if (!m) return null
  const start = src.indexOf('{', m.index + m[0].length - 1)
  for (let d = 0, j = start; j < src.length; j++) {
    if (src[j] === '{') d++
    else if (src[j] === '}' && --d === 0) return src.slice(start + 1, j)
  }
  return null
}
const squash = (s) => (s ?? '').replace(/\s+/g, ' ').trim()

/** Splits a file into its classes: name → { parent, body }. */
function classes(src) {
  const out = {}
  for (const m of src.matchAll(/\bclass\s+(\w+)\s*(?:extends|:)\s*(\w+)\s*\{?/g)) {
    const body = block(src.slice(m.index), /class\s+\w+[^{;]*\{/)
    out[m[1]] = { parent: m[2], body: body ?? '' }
  }
  return out
}

// --- Readable names -----------------------------------------------------------------------------

// From build-dayz-items.mjs.
const camel = (s) => s.replace(/_/g, ' ').replace(/([a-z])([A-Z0-9])/g, '$1 $2').replace(/([A-Z]+)([A-Z][a-z])/g, '$1 $2').replace(/\s+/g, ' ').trim()
const sentenceCase = (s) => s.charAt(0).toUpperCase() + s.slice(1).toLowerCase().replace(/\b(nbc|gps|iv|ak|m4|nvg|po-x|ttsko|bdu|un|9v|h7)\b/gi, (m) => m.toUpperCase())

// Vehicle parts keep the in-game Title Case names the wiki uses (and players say).
const PARTS = {
  CarBattery: 'Car Battery', TruckBattery: 'Truck Battery', SparkPlug: 'Spark Plug', GlowPlug: 'Glow Plug', CarRadiator: 'Radiator', HeadlightH7: 'Headlight Bulb',
  CivSedanWheel: 'Olga 24 Wheel', HatchbackWheel: 'Ada 4x4 Wheel', Hatchback_02_Wheel: 'Gunter 2 Wheel', Sedan_02_Wheel: 'Sarka 120 Wheel', Offroad_02_Wheel: 'M1025 Wheel',
  Truck_01_Wheel: 'M3S Wheel', Truck_01_WheelDouble: 'M3S Dual Wheel', Motorbike_01_Wheel_1: 'Motorbike 01 Wheel 1', Motorbike_01_Wheel_2: 'Motorbike 01 Wheel 2',
  Motorbike_02_Wheel_1: 'Motorbike 02 Wheel 1', Motorbike_02_Wheel_2: 'Motorbike 02 Wheel 2',
  CivSedanDoors_Driver: 'Olga 24 Front Left Door', CivSedanDoors_CoDriver: 'Olga 24 Front Right Door', CivSedanDoors_BackLeft: 'Olga 24 Rear Left Door', CivSedanDoors_BackRight: 'Olga 24 Rear Right Door', CivSedanHood: 'Olga 24 Hood', CivSedanTrunk: 'Olga 24 Trunk Lid',
  HatchbackDoors_Driver: 'Ada 4x4 Left Door', HatchbackDoors_CoDriver: 'Ada 4x4 Right Door', HatchbackHood: 'Ada 4x4 Hood', HatchbackTrunk: 'Ada 4x4 Rear Door',
  Hatchback_02_Door_1_1: 'Gunter 2 Front Left Door', Hatchback_02_Door_2_1: 'Gunter 2 Front Right Door', Hatchback_02_Door_1_2: 'Gunter 2 Rear Left Door', Hatchback_02_Door_2_2: 'Gunter 2 Rear Right Door', Hatchback_02_Hood: 'Gunter 2 Hood', Hatchback_02_Trunk: 'Gunter 2 Trunk Door',
  Sedan_02_Door_1_1: 'Sarka 120 Front Left Door', Sedan_02_Door_2_1: 'Sarka 120 Front Right Door', Sedan_02_Door_1_2: 'Sarka 120 Rear Left Door', Sedan_02_Door_2_2: 'Sarka 120 Rear Right Door', Sedan_02_Hood: 'Sarka 120 Hood', Sedan_02_Trunk: 'Sarka 120 Trunk Door',
  Offroad_02_Door_1_1: 'M1025 Front Left Door', Offroad_02_Door_2_1: 'M1025 Front Right Door', Offroad_02_Door_1_2: 'M1025 Rear Left Door', Offroad_02_Door_2_2: 'M1025 Rear Right Door', Offroad_02_Hood: 'M1025 Hood', Offroad_02_Trunk: 'M1025 Trunk Door',
  Truck_01_Door_1_1: 'M3S Left Door', Truck_01_Door_2_1: 'M3S Right Door', Truck_01_Hood: 'M3S Hood',
}
// Crafting items the loot table does not list (they are crafted, gathered or butchered), with
// the in-game names from the wiki where it has a page for them.
const NAMES = {
  BandageDressing: 'Bandage', Rag: 'Rags', WoodenStick: 'Short stick', LongWoodenStick: 'Long stick', SharpWoodenStick: 'Sharpened stick', Firewood: 'Firewood', WoodenLog: 'Wooden log', WoodenPlank: 'Plank',
  Nail: 'Nails', Bone: 'Bones', Bark_Birch: 'Light bark', Bark_Oak: 'Oak bark', Bark_Palm: 'Palm bark', Stone: 'Large stone', SmallStone: 'Small stone', Lard: 'Lard', Guts: 'Guts',
  TannedLeather: 'Tanned leather', BurlapStrip: 'Burlap strips', ChickenFeather: 'Chicken feathers', BoneHook: 'Bone fishing hook', WoodenHook: 'Wooden fishing hook', BoneKnife: 'Bone knife', StoneKnife: 'Stone knife',
  ImprovisedFishingRod: 'Simple fishing rod', Splint: 'Splint', Fireplace: 'Fireplace', Torch: 'Torch', LongTorch: 'Long torch', HandDrillKit: 'Hand drill kit', HandDrillKitBirch: 'Hand drill kit (birch)', HandDrillKitPalm: 'Hand drill kit (palm)',
  CourierBag: 'Burlap courier bag', ImprovisedBag: 'Burlap backpack', FurCourierBag: 'Fur courier bag', FurImprovisedBag: 'Fur backpack', LeatherSack_ColorBase: 'Leather backpack', LeatherSack_Brown: 'Leather backpack',
  WoodenCrate: 'Wooden crate', FenceKit: 'Fence kit', WatchtowerKit: 'Watchtower kit', ShelterKit: 'Shelter kit', TerritoryFlagKit: 'Territory flag kit', TripwireTrap: 'Tripwire', RabbitSnareTrap: 'Snare trap', SmallFishTrap: 'Small fish trap', FishNetTrap: 'Fish net trap',
  ImprovisedSuppressor: 'Plastic bottle suppressor', ImprovisedExplosive: 'Improvised explosive', Spear: 'Improvised spear', SpearBone: 'Bone spear', SpearStone: 'Stone spear', CookingStand: 'Cooking stand', MortarMix: 'Mortar mix', MortarMix_Opened: 'Mortar mix (opened)',
  NailedBaseballBat: 'Nailed baseball bat', BarbedBaseballBat: 'Barbed baseball bat', XmasBaseballBat: 'Christmas baseball bat', CamoNet: 'Camo net', CamoNetShelter: 'Camo net shelter',
  GhillieSuit_ColorBase: 'Ghillie suit', GhillieSuit_Tan: 'Ghillie suit (tan)', GhillieHood_ColorBase: 'Ghillie hood', GhillieHood_Tan: 'Ghillie hood (tan)', GhillieTop_ColorBase: 'Ghillie cloak', GhillieTop_Tan: 'Ghillie cloak (tan)',
  GhillieBushrag_ColorBase: 'Ghillie shrug', GhillieBushrag_Tan: 'Ghillie shrug (tan)', GhillieAtt_ColorBase: 'Ghillie wrap', GhillieAtt_Tan: 'Ghillie wrap (tan)',
  Armband_: 'Armband', Armband_White: 'Armband (white)', Headdress_Fox: 'Fox headdress', LeatherBelt_Lizard: 'Lizard leather belt', LeatherHippack_Lizard: 'Lizard leather hip pack', LizardKnifeSheath: 'Lizard leather knife sheath',
  GasMask_Filter_Improvised: 'Improvised gas mask filter', SalineBagIV: 'Saline bag IV', BloodBagIV: 'IV blood bag', BloodBagFull: 'Blood bag (full)', BloodSyringe: 'Syringe of blood', ClearSyringe: 'Syringe', InjectionVial: 'Injection vial',
  ChristmasHeadband: 'Christmas lights headband', ChristmasHeadband_Antlers: 'Christmas headband (antlers)', ChristmasHeadband_Gingerbread: 'Christmas headband (gingerbread)', ChristmasHeadband_Trees: 'Christmas headband (trees)',
  WitchHoodCoif_ColorBase: 'Witch hood', PumpkinSeeds: 'Pumpkin seeds', PepperSeeds: 'Pepper seeds', TomatoSeeds: 'Tomato seeds', ZucchiniSeeds: 'Zucchini seeds', SlicedPumpkin: 'Sliced pumpkin',
  DeadChicken_ColorBase: 'Dead chicken', DeadRabbit: 'Dead hare', DeadFox: 'Dead fox', DeadLizard: 'Dead lizard', RabbitPelt: 'Hare pelt', FoxPelt: 'Fox pelt', WildboarPelt: 'Boar pelt', LizardPelt: 'Lizard pelt', Pelt_Base: 'Animal pelt',
  ChickenBreastMeat: 'Chicken steak', RabbitLegMeat: 'Hare leg', FoxSteakMeat: 'Fox steak', LizardMeat: 'Lizard meat', CarpFilletMeat: 'Carp fillet', CatfishFilletMeat: 'Catfish fillet', MackerelFilletMeat: 'Mackerel fillet',
  SteelheadTroutFilletMeat: 'Steelhead trout fillet', WalleyePollockFilletMeat: 'Walleye pollock fillet', RedCaviar: 'Red caviar', WalleyePollock: 'Walleye pollock', SteelheadTrout: 'Steelhead trout',
  Barrel_ColorBase: 'Oil barrel', BarrelHoles_: 'Fire barrel', Waterskin_ColorBase: 'Waterskin', Waterskin_Natural: 'Waterskin', WaterPouch_ColorBase: 'Water pouch',
  ItemBook: 'Book', Paper: 'Paper', GiftWrapPaper: 'Gift wrap paper', EyeMask_ColorBase: 'Sleeping mask', MorsecodeLeaflet_blood: 'Morse code leaflet (blood)', MorsecodeLeaflet_fire: 'Morse code leaflet (fire)', MorsecodeLeaflet_coffee: 'Morse code leaflet (coffee)',
  Shemag_ColorBase: 'Shemag', Raincoat_ColorBase: 'Raincoat', Flag_Base: 'Flag', Bottle_Base: 'Any bottle', GlassBottle: 'Glass bottle', WaterBottle: 'Water bottle', FilteringBottle: 'Filtering bottle', AyranBottle: 'Ayran bottle',
  ChelatingTablets: 'Chelating tablets', Cauldron: 'Cauldron', Chainsaw: 'Chainsaw', Potato: 'Potato', Pumpkin: 'Pumpkin',
  GunCase_ColorBase: 'Gun case', GunCaseKeys_Colorbase: 'Gun case key', ScientificBriefcase: 'Scientific briefcase', ScientificBriefcaseKeys: 'Scientific briefcase key',
  PlateCarrierComplete: 'Plate carrier (with holster and pouches)', CarrierHolsterSolo: 'Holster (plate carrier)', CarrierPouches: 'Pouches (plate carrier)',
  HayHook: 'Hay hook', Iceaxe: 'Ice axe', BrickTrowel: 'Brick trowel', DonerKnife: 'Doner knife', Akinaka: 'Akinaka', Scimitar: 'Scimitar', Jambiya: 'Jambiya',
  Ammo_SharpStick: 'Sharp stick', XmasLights: 'Christmas lights', Broom_Birch: 'Birch broom', NioshFaceMask: 'NIOSH face mask', WitchHoodCoif_: 'Armoured witch hood', DrysackBag_: 'Drysack bag',
  WaterproofBag_ColorBase: 'Waterproof bag', PlateCarrierHolster: 'Plate carrier with holster', PlateCarrierPouches: 'Plate carrier with pouches',
  DefaultMagazine: 'Magazine', DefaultWeapon: 'Firearm', Inventory_Base: 'Clothing that tears into rags', Magnum_Gold: 'Golden revolver', SawedoffMagnum_Gold: 'Sawed-off golden revolver',
}
for (const [k, v] of Object.entries(PARTS)) NAMES[k] = v

const lootNames = new Map(JSON.parse(readFileSync(join(PACK, 'items.chernarus.json'), 'utf8')).items.map((i) => [i.id, i.name]))
const gunNames = new Map()
for (const g of JSON.parse(readFileSync(join(PACK, 'weapons.json'), 'utf8')).items)
  for (const a of [g.name, ...g.aliases]) if (/^\w+$/.test(a)) gunNames.set(a.toLowerCase(), g.name)
const COLOUR = /^(.+?)_(Black|Green|Blue|Red|Brown|Grey|Gray|Beige|White|Pink|Camo|Olive|Orange|Yellow|Tan|Woodland|Mossy|Natural|Wine)$/

function nameOf(cls) {
  if (NAMES[cls]) return NAMES[cls]
  if (lootNames.has(cls)) return lootNames.get(cls)
  if (gunNames.has(cls.toLowerCase())) return gunNames.get(cls.toLowerCase())
  const base = cls.match(/^(.+?)_(ColorBase|Colorbase|Base)$/)
  if (base) return nameOf(base[1])
  const col = cls.match(COLOUR)
  if (col) return `${nameOf(col[1])} (${col[2].toLowerCase()})`
  return sentenceCase(camel(cls))
}
const isClass = (c) => /^\w+$/.test(c)

// --- Recipes ------------------------------------------------------------------------------------

const registry = read(RECIPE_DIR, 'pluginrecipesmanagerbase.c')
const REGISTERED = [...block(registry, /void\s+RegisterRecipies\s*\(\s*\)\s*\{/).matchAll(/RegisterRecipe\s*\(\s*new\s+(\w+)/g)].map((m) => m[1])

const FILES = {}
for (const f of readdirSync(RECIPE_DIR).filter((f) => f.endsWith('.c'))) {
  for (const [name, c] of Object.entries(classes(read(RECIPE_DIR, f)))) FILES[name] = { ...c, file: f }
}

const INGREDIENT_FIELDS = {
  m_MinQuantityIngredient: 'minQty', m_MaxQuantityIngredient: 'maxQty', m_MinDamageIngredient: 'minDmg', m_MaxDamageIngredient: 'maxDmg',
  m_IngredientAddHealth: 'addHealth', m_IngredientSetHealth: 'setHealth', m_IngredientAddQuantity: 'addQty', m_IngredientDestroy: 'destroy',
}
const RESULT_FIELDS = { m_ResultSetQuantity: 'qty', m_ResultSetFullQuantity: 'full', m_ResultInheritsColor: 'colour', m_ResultReplacesIngredient: 'replaces' }
const value = (v) => (v.trim() === 'true' ? 1 : v.trim() === 'false' ? 0 : Number(v))
const fresh = () => ({
  name: null,
  ing: [0, 1].map(() => ({ classes: [], dropped: [], minQty: -1, maxQty: -1, minDmg: -1, maxDmg: -1, addHealth: 0, setHealth: -1, addQty: 0, destroy: 0 })),
  results: [],
})
const clone = (s) => JSON.parse(JSON.stringify(s))

/** What a recipe's Init() sets up, following `super.Init()` into the parent recipe. */
function initState(name) {
  const c = FILES[name]
  if (!c) return fresh()
  const init = block(c.body, /void\s+Init\s*\(\s*\)\s*\{/)
  if (init === null) return initState(c.parent)
  const s = /super\.Init\s*\(/.test(init) ? clone(initState(c.parent)) : fresh()
  const STMT = /InsertIngredient(?:Ex)?\s*\(\s*(\d)\s*,\s*"([^"]*)"|RemoveIngredient\s*\(\s*(\d)\s*,\s*"([^"]*)"|AddResult\s*\(\s*"([^"]*)"\s*\)|\b(m_\w+)\s*\[\s*(\d)\s*\]\s*=\s*([^;]+);|\bm_Name\s*=\s*([^;]+);/g
  for (const m of init.matchAll(STMT)) {
    if (m[1] !== undefined) {
      const ing = s.ing[Number(m[1])]
      if (!isClass(m[2])) ing.dropped.push(m[2])
      else if (!ing.classes.includes(m[2])) ing.classes.push(m[2])
    } else if (m[3] !== undefined) {
      const ing = s.ing[Number(m[3])]
      ing.classes = ing.classes.filter((x) => x !== m[4])
    } else if (m[5] !== undefined) {
      s.results.push({ cls: m[5], qty: -1, full: 0, colour: -1, replaces: -1 })
    } else if (m[6] !== undefined) {
      const i = Number(m[7])
      if (INGREDIENT_FIELDS[m[6]] && s.ing[i]) s.ing[i][INGREDIENT_FIELDS[m[6]]] = value(m[8])
      else if (RESULT_FIELDS[m[6]] && s.results[i]) s.results[i][RESULT_FIELDS[m[6]]] = value(m[8])
    } else if (m[9] !== undefined) {
      s.name = m[9].trim()
    }
  }
  return s
}

/** The body of a recipe's own (or inherited) method, or null when only RecipeBase has it. */
function method(name, re) {
  for (let c = FILES[name]; c; c = FILES[c.parent]) {
    const b = block(c.body, re)
    if (b !== null) return b
  }
  return null
}

function parseRecipe(id) {
  const s = initState(id)
  const canDo = method(id, /bool\s+CanDo\s*\([^)]*\)\s*\{/)
  const doBody = method(id, /void\s+Do\s*\([^)]*\)\s*\{/) ?? ''
  const extra = []
  const turned = new Set()
  for (const m of doBody.matchAll(/TurnItemIntoItemLambda\s*\(\s*ingredients\[(\d)\]\s*,\s*"(\w+)"/g)) {
    turned.add(Number(m[1]))
    if (!s.results.some((r) => r.cls === m[2]) && !extra.some((r) => r.cls === m[2])) extra.push({ cls: m[2], qty: 1, turned: Number(m[1]) })
  }
  for (const m of doBody.matchAll(/(?:SpawnEntityOnGroundRaycastDispersed|CreateItemBasePilesDispersed)\s*\(\s*"(\w+)"/g))
    if (!s.results.some((r) => r.cls === m[1]) && !extra.some((r) => r.cls === m[1])) extra.push({ cls: m[1], qty: null })
  return { id, file: FILES[id].file, ...s, extra, turned, canDo: canDo === null ? null : squash(canDo), doBody: squash(doBody) }
}

const SKIP_TEST = /^(Test|RecipeTest)$/
const skipped = { test: [], unregistered: [] }
for (const name of Object.keys(FILES)) {
  if (name === 'PluginRecipesManagerBase') continue
  if (SKIP_TEST.test(name)) skipped.test.push(name)
  else if (!REGISTERED.includes(name)) skipped.unregistered.push(name)
}
const missingClass = REGISTERED.filter((r) => !FILES[r])
if (missingClass.length) console.warn('registered but no class found:', missingClass)
const parsed = REGISTERED.filter((r) => FILES[r] && !SKIP_TEST.test(r)).map(parseRecipe)


if (debug === '--dump') {
  for (const r of parsed) {
    console.log(`\n## ${r.id} (${r.file}) name=${r.name}`)
    r.ing.forEach((g, i) => console.log(`  ing${i}: [${g.classes.join(',')}] min=${g.minQty} dmg=${g.minDmg}..${g.maxDmg} addQ=${g.addQty} addH=${g.addHealth} setH=${g.setHealth} destroy=${g.destroy}${g.dropped.length ? ` DROPPED=${g.dropped}` : ''}`))
    console.log(`  results: ${r.results.map((x) => `${x.cls}(q=${x.qty},full=${x.full},col=${x.colour})`).join(' ')} extra: ${r.extra.map((x) => x.cls).join(' ')}`)
    console.log(`  canDo: ${r.canDo === null ? '(base: no attachments)' : r.canDo}`)
  }
  process.exit(0)
}

// What the scripts' CanDo()/Do() add, in plain English. `cond` replaces the conditions, `notes`
// fills the notes; `ing[i]` changes what an ingredient shows (items: the label instead of class
// names, use/takes: what Do() really takes, drop: classes left out); `results` replaces the
// results. Every registered recipe whose CanDo() does more than `return true;` must have a
// `cond` here (the build stops otherwise), so nothing in the scripts is silently skipped.
const REPAIR = 'Repairs the item one health level (only to worn, unless the item allows pristine); uses some of the kit.'
const R = {
  CraftTorch: { ing: [{ use: 'consumed' }], notes: 'The whole stack of rags goes onto the torch as fuel.' },
  CraftLongTorch: { cond: null, ing: [{ use: 'consumed' }], notes: 'The whole stack of rags goes onto the torch as fuel.' },
  CraftFireplace: { cond: 'The two items must be different kinds of kindling or fuel', ing: [{ use: 'quantity', takes: 1 }, { use: 'quantity', takes: 1 }], notes: 'One of each (or the whole item if it has no quantity) goes into the fireplace.' },
  SharpenBroom: { name: 'Sharpen broom', cond: 'The broom must not be burning' },
  SharpenLongStick: { name: 'Sharpen long stick' },
  PokeHolesBarrel: { name: 'Fire barrel (poke holes in a barrel)', cond: 'The barrel must be empty', notes: 'Heavy wear on the tool.' },
  CraftBaseBallBatBarbed: { cond: 'Neither item may have anything attached', notes: 'Without gloves there is a 1 in 10 chance to cut a forearm and bleed.' },
  CraftChristmasLightsHeadband: { name: 'Christmas lights headband', cond: 'The Christmas lights must have nothing attached', results: [{ item: 'Christmas lights headband (same style as the headband)', cls: 'ChristmasHeadband', qty: 1 }] },
  CraftRagRope: { name: 'Rope (from rags)', notes: 'Combine two stacks of at least 6 rags each.' },
  CraftSuppressor: { cond: 'The bottle must be empty and not frozen' },
  CleanWeapon: { name: 'Clean weapon', cond: 'Item must be worn or worse and repairable with the kit', ing: [{ use: 'quantity' }, { items: ['Firearm, magazine or suppressor'] }], notes: REPAIR },
  RepairWithTape: { name: 'Repair with duct tape', cond: 'Item must be worn or worse and repairable with tape', ing: [{ use: 'quantity' }, { items: ['Item to repair', 'Firearm', 'Magazine'] }], notes: REPAIR },
  RepairEpoxy: { name: 'Repair with epoxy putty', cond: 'Item must be worn or worse and repairable with epoxy', ing: [{ use: 'quantity' }, { items: ['Item to repair', 'Firearm'] }], notes: REPAIR },
  RepairElectric: { name: 'Repair with electronic repair kit', cond: 'Item must be worn or worse and repairable with the kit', ing: [{ use: 'quantity' }, { items: ['Electrical item to repair'] }], notes: REPAIR },
  RepairWithTireKit: { name: 'Repair with tyre repair kit', cond: 'Item must be worn or worse and repairable with the kit', ing: [{ use: 'quantity' }, { items: ['Wheel to repair'] }], notes: REPAIR },
  RepairWithSewingKit: { name: 'Repair with sewing kit', cond: 'Item must be worn or worse and repairable with the kit', ing: [{ use: 'quantity' }, { items: ['Clothing to repair'] }], notes: REPAIR },
  RepairWithLeatherSewingKit: { name: 'Repair with leather sewing kit', cond: 'Item must be worn or worse and repairable with the kit', ing: [{ use: 'quantity' }, { items: ['Leather item to repair'] }], notes: REPAIR },
  SharpenMelee: { name: 'Sharpen blade', cond: 'Blade must be worn or worse and sharpenable with a whetstone', ing: [{ use: 'quantity' }, { items: ['Blade to sharpen'] }], notes: REPAIR },
  RepairWithPliers: { name: 'Repair chainmail with pliers', cond: 'The chainmail must be damaged or worse', notes: 'The chainmail gains 20% of its health; the pliers lose 20%.', ing: [{ use: 'damaged' }] },
  RepairPlanks: { name: 'Repair crate or chest with planks', cond: 'The crate or chest must be damaged or worse and have nothing attached' },
  CraftWoodenHook: { name: 'Wooden fishing hooks' },
  CraftImprovisedBag: { cond: 'The courier bag must be empty' },
  CraftImprovisedLeatherBag: { cond: 'The courier bag must be empty' },
  CraftImprovisedExplosive: { cond: 'The protector case must be empty' },
  DeCraftLeatherCourierBag: { cond: 'The bag must be empty' },
  DeCraftCourierBag: { cond: 'The bag must be empty' },
  DeCraftImprovisedBag: { cond: 'The bag must be empty' },
  DeCraftImprovisedLeatherBag: { cond: 'The bag must be empty' },
  DeCraftLeatherSack: { cond: 'The backpack must be empty' },
  DeCraftWaterskin: { cond: 'The waterskin must be empty' },
  DeCraftFishingRod: { cond: 'Nothing may be attached to the rod' },
  SplitLongWoodenStick: { name: 'Split long stick', cond: 'Nothing may be attached to the stick', notes: 'The script lists the jambiya as "Jambiya;" (a typo), so a jambiya does not work for this.' },
  SplitBroom: { name: 'Split broom', cond: 'The broom must not be burning and must have nothing attached' },
  SplitFirewood: { name: 'Split firewood' },
  SplitStones: { name: 'Split large stone' },
  CraftSplint: {
    cond: 'A full bandage, at least 4 rags, at least half a roll of duct tape, or a shemag',
    ing: [{ use: 'quantity', takes: null, minQty: null }],
    notes: 'Uses the whole bandage, 4 rags, half a roll of duct tape or the shemag.',
  },
  CraftSpearBone: { cond: 'Nothing may be attached to the stick' },
  CraftSpearStone: { cond: 'Nothing may be attached to the stick' },
  CraftFishingRod: { cond: 'Nothing may be attached to the stick' },
  CraftCookingStand: { cond: 'Nothing may be attached to the long stick; ruined items work too' },
  CutOutZucchiniSeeds: { cond: 'Must not be frozen', results: 'seeds', notes: 'Up to 8 seeds: fewer when partly eaten, half when dried or rotten, none when cooked, burnt or under 30% left.' },
  CutOutTomatoSeeds: { cond: 'Must not be frozen', results: 'seeds', notes: 'Up to 8 seeds: fewer when partly eaten, half when dried or rotten, none when cooked, burnt or under 30% left.' },
  CutOutPepperSeeds: { cond: 'Must not be frozen', results: 'seeds', notes: 'Up to 12 seeds: fewer when partly eaten, half when dried or rotten, none when cooked, burnt or under 30% left.' },
  CutOutPumpkinSeeds: { name: 'Cut up pumpkin', cond: 'Must not be frozen', results: 'seeds', notes: 'Up to 12 seeds (fewer when partly eaten, dried, rotten or cooked); the slices keep the pumpkin\'s condition and amount.' },
  PeelPotato: { name: 'Peel seed potato' },
  PrepareCarp: { name: 'Gut carp', cond: 'Must not be frozen' },
  PrepareCatfish: { name: 'Gut catfish', cond: 'Must not be frozen' },
  PrepareMackerel: { name: 'Gut mackerel', cond: 'Must not be frozen' },
  PrepareWalleyePollock: { name: 'Gut walleye pollock', cond: 'Must not be frozen' },
  PrepareSteelheadTrout: { name: 'Gut steelhead trout', cond: 'Must not be frozen' },
  PrepareChicken: { name: 'Skin chicken', cond: 'Must not be frozen', results: [{ cls: 'ChickenBreastMeat', qty: null }, { cls: 'ChickenFeather', qty: null }, { cls: 'Bone', qty: null }], notes: '2–4 chicken steaks, 5–15 feathers and 2–5 bones. Leaves you with bloody hands.' },
  PrepareRabbit: { name: 'Skin hare', cond: 'Must not be frozen', results: [{ cls: 'RabbitLegMeat', qty: null }, { cls: 'RabbitPelt', qty: 1 }, { cls: 'Bone', qty: null }], notes: '2–4 hare legs, a pelt and 2–5 bones. Leaves you with bloody hands.' },
  PrepareFox: { name: 'Skin fox', cond: 'Must not be frozen', results: [{ cls: 'FoxSteakMeat', qty: null }, { cls: 'FoxPelt', qty: 1 }, { cls: 'Bone', qty: null }], notes: '2–4 fox steaks, a pelt and 2–5 bones. Leaves you with bloody hands.' },
  PrepareLizard: { name: 'Skin lizard', cond: 'Must not be frozen', results: [{ cls: 'LizardMeat', qty: 1 }, { cls: 'LizardPelt', qty: 1 }, { cls: 'Bone', qty: null }], notes: 'One piece of lizard meat, a pelt and 2–5 bones. Leaves you with bloody hands.' },
  OpenCan: { name: 'Open can', cond: 'The can must not be frozen', ing: [{ use: 'consumed' }], results: [{ item: 'Opened can', cls: '<can>_Opened', qty: 1 }], notes: 'The can becomes the opened can; some food spills, how much depends on the tool.' },
  PurifyWater: { cond: 'The container must hold liquid that is not frozen', notes: 'Kills every disease agent in the liquid except heavy metals.' },
  ChelateWater: { name: 'Chelate water', cond: 'The container must hold liquid that is not frozen', notes: 'Removes every agent from the liquid, heavy metals included.' },
  FuelChainsaw: { name: 'Fuel chainsaw', cond: 'The container must hold gasoline that is not frozen', ing: [{}, { use: 'quantity', minQty: null }], notes: 'Fills the chainsaw; the container loses the same amount.' },
  DeCraftSnareTrap: { cond: 'Nothing may be attached to the trap' },
  DeCraftTripWire: { cond: 'Nothing may be attached to the tripwire' },
  DeCraftFishNetTrap: { cond: 'Nothing may be attached to the trap' },
  DisinfectItem: {
    name: 'Disinfect item', cond: 'The item must be one that can be disinfected, and there must be enough disinfectant',
    ing: [{ use: 'quantity' }, { items: ['Item to disinfect'] }], notes: 'Cleans the item and removes its agents except brain disease, salmonella and cholera.',
  },
  FillSyringe: { name: 'Fill syringe' },
  BloodTest: { name: 'Test blood type', notes: 'Shows the blood type of the syringe or bag.' },
  CraftArmbandRaincoat: { name: 'Armbands (from a raincoat)', cond: 'The raincoat must be empty' },
  CraftArmbandFlag: { name: 'Armbands (from a flag)', cond: 'The flag must not be on a flagpole' },
  CraftArmbandRag: { name: 'Armband (from rags)' },
  ExtinguishTorch: { name: 'Put out torch', cond: 'Must be burning; the container must hold water, beer or blood, not frozen', notes: 'Also soaks it.' },
  AttachHolsterPouch: { name: 'Plate carrier: add holster', cond: 'Both must be empty' },
  AttachPouchesHolster: { name: 'Plate carrier: add pouches', cond: 'Both must be empty' },
  SawOffIzh18: { cond: null },
  SawOffIzh18Shotgun: { cond: null },
  SawOffMagnum: { results: [{ cls: 'SawedoffMagnum', qty: 1 }], notes: 'A golden revolver becomes a sawed-off golden revolver.' },
  CraftRag: {
    name: 'Cut into rags', cond: 'Must be an item that gives rags, in your inventory or on the ground (not attached to anything else)',
    ing: [{}, { items: ['Clothing that tears into rags'] }], results: [{ cls: 'Rag', qty: null }], notes: 'How many rags depends on the item; anything inside it is dropped.',
  },
  CraftFeathers: {
    cond: 'Must be an item that gives feathers, and it must be empty', ing: [{}, { items: ['Item that gives feathers'], drop: ['Akinaka'] }],
    results: [{ cls: 'ChickenFeather', qty: null }], notes: 'How many feathers depends on the item.',
  },
  CraftGutsRope: { name: 'Rope (from guts)', notes: 'Leaves you with bloody hands.' },
  UpgradeTorchWithLard: {
    name: 'Coat torch with lard', cond: 'The torch must have rags and accept an upgrade; ruined items work too', ing: [{ use: 'quantity' }, {}],
    notes: 'Up to doubles the burn time, using as much lard as that needs; the rags are ruined and locked on.',
  },
  UpgradeTorchWithLiquidFuel: {
    name: 'Soak torch in fuel', cond: 'The container must hold gasoline or diesel; the torch must have rags and accept an upgrade; ruined items work too',
    ing: [{ use: 'quantity', items: ['Bottle or container of fuel', 'Oil barrel'] }, { use: 'kept', takes: null }],
    notes: 'Up to doubles the burn time, using as much fuel as that needs; the rags are ruined and locked on.',
  },
  CraftFenceKit: { cond: 'Neither item may be attached to anything' },
  CraftWatchtowerKit: { cond: 'Neither item may be attached to anything' },
  CraftTerritoryFlagKit: { cond: 'Neither item may be attached to anything' },
  CraftShelterKit: { cond: 'Neither item may be attached to anything' },
  CraftWoodenPlank: { name: 'Planks (saw a log)' },
  SawWoodenLog: { name: 'Firewood (cut up a log)' },
  DeCraftWoodenCrate: {
    cond: 'The crate must be empty', results: [{ cls: 'WoodenPlank', qty: null }, { cls: 'Nail', qty: null }],
    notes: 'Pristine: 6–8 planks and 12–16 nails; worn 5–7 and 9–13; damaged 4–6 and 6–10; badly damaged 3–5 and 3–7; ruined 1–2 and 1–4.',
  },
  CraftTannedLeather: {
    cond: 'Enough garden lime for the leather it yields', ing: [{ items: ['Animal pelt'] }, { use: 'quantity' }], results: [{ cls: 'TannedLeather', qty: null }],
    notes: 'Each leather uses 5% of a full garden lime; how much leather depends on the pelt and its condition.',
  },
  CraftTruck01DoubleWheel: { cond: 'Both wheels must be on the ground or in your inventory, not on the truck' },
  DeCraftTruck01DoubleWheel: { cond: 'The dual wheel must be on the ground, not on the truck' },
  CraftSmallFishTrap: { cond: 'The bottle must be empty and not frozen' },
  CraftMetalWire: { name: 'Metal wire (from barbed wire)', cond: 'The barbed wire must not be attached to anything', results: [{ cls: 'MetalWire', qty: 1 }] },
  CraftGasMask_Filter_Improvised: { cond: 'The bottle must be empty and not frozen', results: [{ cls: 'GasMask_Filter_Improvised', qty: 1 }], notes: 'The filter starts empty: fill it with charcoal tablets.' },
  FillGasMask_Filter: {
    name: 'Fill gas mask filter', cond: 'The filter must not be full or fitted to a GP-5 or airborne mask', ing: [{ use: 'quantity' }],
    notes: 'Each charcoal tablet fills 10 units of the filter; only what fits is used.',
  },
  DeCraftDetonator: { notes: 'The 9V battery comes out 25–100% charged.' },
  CraftWitchHoodCoif: { cond: 'The witch hood must be empty' },
  CraftDrysackBag: { cond: 'The waterproof bag must be empty' },
  OpenScientificBriefcase: { name: 'Unlock scientific briefcase', cond: 'The briefcase must be locked' },
  CloseScientificBriefcase: { name: 'Lock scientific briefcase', cond: 'The briefcase must be open' },
  OpenGunCase: { name: 'Unlock gun case', cond: 'The key must match the case, and the case must be locked' },
  CloseGunCase: { name: 'Lock gun case', cond: 'The key must match the case, and the case must be open' },
  CraftMortarMix: { name: 'Mix mortar', cond: 'The container must hold at least 1 litre of water, not frozen', ing: [{}, { use: 'quantity', takes: 1000 }] },
}

// Results the scripts give a stack of whatever size the item starts with (left null here).
const STACKS = new Set(['Rag', 'BurlapStrip', 'Nail', 'Bone', 'ChickenFeather', 'WoodenStick', 'Firewood', 'SmallStone', 'WoodenPlank', 'TannedLeather', 'Netting', 'PumpkinSeeds', 'PepperSeeds', 'TomatoSeeds', 'ZucchiniSeeds'])
const LEVEL = ['pristine', 'worn', 'damaged', 'badly damaged', 'ruined']
const lower = (s) => (/^[A-Z][a-z]/.test(s) && !/^(M3S|M1025|KA|LE-MAS|BK|Mosin|Blaze|Olga|Ada|Gunter|Sarka|IV|NIOSH)\b/.test(s) ? s.charAt(0).toLowerCase() + s.slice(1) : s)

/** The plain-English recipe name when no override gives one. */
function defaultName(r, results) {
  const id = r.id
  const target = lower(nameOf(r.ing[0].classes[0] ?? ''))
  if (/^DeCraft/i.test(id)) return `Break down ${target}`
  if (/^Sawoff/i.test(id)) return `Saw off ${target}`
  if (/^Craft/i.test(id) && results.length) return results[0].item
  if (/^Attach/i.test(id) && results.length) return results[0].item
  return sentenceCase(camel(id))
}

const checkNeeded = []
const recipes = parsed.map((r) => {
  const o = R[r.id] ?? {}
  // Results: AddResult in order (duplicates merged into a count), then what Do() spawns.
  let results = []
  const all = [...r.results, ...r.extra]
  for (const x of all) {
    const same = results.find((y) => y.cls === x.cls)
    const one = x.full ? (STACKS.has(x.cls) ? null : 1) : x.qty === null ? null : x.qty >= 0 ? x.qty : STACKS.has(x.cls) ? null : 1
    const colourFrom = x.colour >= 0 ? r.ing[x.colour]?.classes[0] : null
    if (same) {
      same.count++
      same.qty = same.qty === null || one === null ? null : same.qty + one
    } else results.push({ cls: x.cls, qty: one, count: 1, colourFrom })
  }
  results = results.map((x) => ({
    item: x.colourFrom ? `${nameOf(x.cls)} (colour of the ${lower(nameOf(x.colourFrom))})` : nameOf(x.cls),
    class: x.cls,
    qty: x.qty === null && x.count > 1 && !STACKS.has(x.cls) ? x.count : x.qty,
    piles: x.count,
  }))
  if (Array.isArray(o.results)) results = o.results.map((x) => ({ item: x.item ?? nameOf(x.cls), class: x.cls, qty: x.qty, piles: 1 }))
  if (o.results === 'seeds') results = results.map((x, i) => (i === 0 ? { ...x, qty: null } : x))

  const ingredients = r.ing.map((g, i) => {
    const oi = o.ing?.[i] ?? {}
    const classes = g.classes.filter((c) => !(oi.drop ?? []).includes(c))
    let use = g.destroy ? 'consumed' : g.addQty < 0 ? 'quantity' : g.addHealth < 0 || g.setHealth >= 0 ? 'damaged' : 'kept'
    let takes = !g.destroy && g.addQty < 0 ? -g.addQty : null
    if (r.turned.has(i)) use = 'consumed'
    if (oi.use) use = oi.use
    if ('takes' in oi) takes = oi.takes
    if (use !== 'quantity') takes = null
    return {
      items: oi.items ?? classes.map(nameOf),
      classes,
      minQty: 'minQty' in oi ? oi.minQty : g.minQty > 1 ? g.minQty : null,
      use,
      takes,
    }
  })

  // Conditions: CanDo() in words, then the health limits that differ from "not ruined".
  const trivial = r.canDo === null ? false : /^return true;$/.test(r.canDo) || /^return super\.CanDo\(ingredients, player\);$/.test(r.canDo)
  if (!trivial && !('cond' in o)) checkNeeded.push(r.id)
  const conds = []
  if (o.cond) conds.push(o.cond)
  r.ing.forEach((g, i) => {
    const who = r.ing.length > 1 ? (i === 0 ? 'The first item' : 'The second item') : 'It'
    const label = ingredients[i].items.length === 1 ? `The ${lower(ingredients[i].items[0])}` : who
    if (g.minDmg >= 1 && !/worn or worse|damaged or worse/.test(o.cond ?? '')) conds.push(`${label} must be ${LEVEL[g.minDmg]} or worse`)
    if (g.maxDmg >= 0 && g.maxDmg < 3) conds.push(`${label} must be ${LEVEL[g.maxDmg]} or better`)
  })
  const notes = [o.notes, ...results.filter((x) => x.piles > 1 && x.qty === null && STACKS.has(x.class)).map((x) => `${x.item}: ${x.piles} piles.`)].filter(Boolean)
  const turnedNote = [...r.turned].map((i) => `The ${lower(ingredients[i].items[0])} becomes the ${lower(results[0]?.item ?? 'result')}, keeping its condition and attachments.`)
  if (!o.notes && turnedNote.length) notes.push(...turnedNote)
  for (const x of results) delete x.piles

  return {
    id: r.id,
    name: o.name ?? defaultName(r, results),
    results,
    ingredients,
    conditions: conds.length ? conds.join('; ') : null,
    notes: notes.length ? notes.join(' ') : null,
  }
})
if (checkNeeded.length) {
  console.error('These recipes have a CanDo() with rules not yet written in R:', checkNeeded.join(', '))
  process.exit(1)
}
const dupNames = recipes.map((r) => r.name).filter((n, i, a) => a.indexOf(n) !== i)
if (dupNames.length) console.warn('duplicate recipe names:', [...new Set(dupNames)])

// --- Vehicles -----------------------------------------------------------------------------------

// From the DayZ Wiki (dayz.fandom.com, CC BY-NC-SA 3.0), read through its API on 2026-11-15:
// the in-game name and the infobox's fuel tank ("fueltank") of each vehicle page (page revision
// in brackets). The Gunter 2's infobox leaves the tank empty, and the motorbikes have no wiki
// page (Motorbike_01 matches the "Enduro Bike" page's red, yellow and blue variants, but that
// page has no figures), so those stay null rather than guessed.
const WIKI = {
  CivilianSedan: { name: 'Olga 24', fuelL: 55 }, // Olga 24 (177674)
  OffroadHatchback: { name: 'Ada 4x4', fuelL: 42 }, // Ada 4x4 (180014)
  Hatchback_02: { name: 'Gunter 2', fuelL: null }, // Gunter 2 (182355): fueltank empty
  Sedan_02: { name: 'Sarka 120', fuelL: 38 }, // Sarka 120 (182334)
  Offroad_02: { name: 'M1025 Humvee', fuelL: 115 }, // M1025 (181760)
  Truck_01_Covered: { name: 'M3S', fuelL: 120 }, // M3S (176147)
  Motorbike_01_ColorBase: { name: 'Enduro Bike', fuelL: null }, // Enduro Bike (182352): no figures; name matched by colours
  Motorbike_02_ColorBase: { name: 'Motorbike 02', fuelL: null }, // no wiki page; in-game name not confirmed
  Boat_01_ColorBase: { name: 'Rubber Boat', fuelL: 40 }, // Rubber Boat (181629)
}
// Car Radiator page (176628): "To completely fill the radiator, about 5,850ml of water is needed."
// The wiki gives this one figure for the radiator, not one per car.
const RADIATOR_L = 5.85
const WIKI_NOTES = {
  Offroad_02: 'Automatic gearbox (wiki).',
  Truck_01_Covered: 'The wiki says it drives on 2 of the 4 dual wheels, 4 recommended.',
}

const VEHICLES = [
  ['CivilianSedan', 'car'], ['OffroadHatchback', 'car'], ['Hatchback_02', 'car'], ['Sedan_02', 'car'], ['Offroad_02', 'car'],
  ['Truck_01_Covered', 'truck'], ['Motorbike_01_ColorBase', 'bike'], ['Motorbike_02_ColorBase', 'bike'], ['Boat_01_ColorBase', 'boat'],
]
// Truck_02 and Van_01 have scripts but are unreleased (not in the game's spawn lists; the wiki
// lists the Multicar and the Van as unused), and inheritedcars/test.c is a test car.

const VCLASSES = {}
const vfiles = [
  ...['carscript.c', 'motorbikescript.c', 'boatscript.c'].map((f) => join(VEHICLE_DIR, f)),
  ...['inheritedcars', 'inheritedmotorbikes', 'inheritedboats'].flatMap((d) => readdirSync(join(VEHICLE_DIR, d)).filter((f) => f.endsWith('.c') && f !== 'test.c').map((f) => join(VEHICLE_DIR, d, f))),
  ...['transport.c', 'car.c', 'motorbike.c', 'boat.c'].map((f) => join(SCRIPTS, '3_game', 'vehicles', f)),
]
for (const f of vfiles) for (const [name, c] of Object.entries(classes(read(f)))) if (!VCLASSES[name]) VCLASSES[name] = c
const chain = (cls) => {
  const out = []
  for (let c = cls; VCLASSES[c] && out.length < 20; c = VCLASSES[c].parent) out.push(VCLASSES[c])
  return out
}
/** The literal a method returns, from the nearest class in the inheritance chain that has it. */
function returns(cls, fn) {
  for (const c of chain(cls)) {
    const b = block(c.body, new RegExp(`\\b(?:bool|string|int|float)\\s+${fn}\\s*\\(\\s*\\)\\s*\\{`))
    if (b !== null) {
      const m = b.match(/return\s+(true|false|"[^"]*")\s*;/)
      return m ? (m[1].startsWith('"') ? m[1].slice(1, -1) : m[1] === 'true') : null
    }
  }
  return undefined
}
const vmethod = (cls, re) => {
  for (const c of chain(cls)) {
    const b = block(c.body, re)
    if (b !== null) return b
  }
  return null
}

const vehicles = VEHICLES.map(([cls, kind]) => {
  const ops = vmethod(cls, /int\s+CheckOperationalRequirements\s*\(\s*\)\s*\{/) ?? ''
  const needsBattery = /NeedElectricitySourceDevice\s*\(\s*\)/.test(ops) && returns(cls, 'NeedElectricitySourceDevice') === true
  const battery = needsBattery ? NAMES[returns(cls, 'GetElectricitySourceDeviceType')] ?? null : null
  const spark = /IsVitalSparkPlug\s*\(\s*\)/.test(ops) && returns(cls, 'IsVitalSparkPlug') === true
  const glow = /IsVitalGlowPlug\s*\(\s*\)/.test(ops) && returns(cls, 'IsVitalGlowPlug') === true
  const plug = spark ? 'Spark Plug' : glow ? 'Glow Plug' : null
  const radiator = returns(cls, 'IsVitalRadiator') === true
  const fuel = /FUEL\s*\)\s*<=\s*0/.test(ops)
  const start = [battery, plug, radiator ? 'Radiator' : null, fuel ? 'fuel' : null].filter(Boolean)

  const all = chain(cls).map((c) => c.body).join('\n')
  const bulbs = ['Reflector_1_1', 'Reflector_2_1'].filter((s) => all.includes(`"${s}"`)).length
  const spawn = [...(vmethod(cls, /void\s+OnDebugSpawn\s*\(\s*\)\s*\{/) ?? '').matchAll(/CreateInInventory\s*\(\s*"(\w+)"\s*\)/g)].map((m) => m[1])
  const firstPanel = spawn.findIndex((c) => /Door|Hood|Trunk/.test(c))
  const fitted = firstPanel < 0 ? spawn : spawn.slice(0, firstPanel)
  const wheelSpawn = fitted.filter((c) => /Wheel/.test(c))
  const doors = spawn.filter((c) => /Door|Hood|Trunk/.test(c)).map((c) => NAMES[c] ?? nameOf(c))
  const spare = chain(cls).some((c) => /CanManipulateSpareWheel\s*\(\s*string/.test(c.body) && c !== VCLASSES.CarScript)
  let wheels = null
  if (wheelSpawn.length) {
    const count = kind === 'car' ? Math.min(4, wheelSpawn.length) : wheelSpawn.length
    const kinds = [...new Set(wheelSpawn)]
    const item = kinds.length === 1 ? NAMES[kinds[0]] : kinds.map((k) => `${wheelSpawn.filter((w) => w === k).length}× ${NAMES[k]}`).join(' + ')
    wheels = { item, count, spare: kind === 'bike' ? false : spare }
  }

  const notes = []
  if (battery) notes.push(`The ${battery.toLowerCase()} must hold enough charge to crank the engine.`)
  if (radiator) notes.push('Fill the radiator with water: below half coolant the engine takes damage while running, and taking the radiator out drains it.')
  if (kind === 'truck') notes.push('No spark plug or radiator needed. Two spare M3S wheels fit behind the cab (open the side plate first).')
  if (cls === 'Offroad_02') notes.push('Glow plug instead of a spark plug; no radiator to fill.')
  if (kind === 'bike') notes.push('No battery needed.')
  if (kind === 'boat') notes.push('The spark plug sits in the outboard motor and cannot be taken out while the engine runs. No battery needed.')
  if (bulbs) notes.push(`${bulbs} headlight bulb${bulbs > 1 ? 's' : ''}.`)
  if (WIKI_NOTES[cls]) notes.push(WIKI_NOTES[cls])
  if (cls === 'Motorbike_01_ColorBase') notes.push('Name from the wiki\'s Enduro Bike page (same colours); not confirmed.')
  if (cls === 'Motorbike_02_ColorBase') notes.push('No wiki page: the in-game name is not confirmed.')
  if (cls === 'Motorbike_02_ColorBase') notes.push('Takes front, left and right shields.')

  return {
    class: cls.replace(/_ColorBase$/, ''),
    name: WIKI[cls].name,
    kind,
    start,
    wheels,
    battery,
    plug,
    radiator,
    lights: bulbs ? NAMES.HeadlightH7 : null,
    doors,
    fuelL: WIKI[cls].fuelL,
    coolantL: radiator ? RADIATOR_L : null,
    notes: notes.join(' ') || null,
  }
})

// --- Output -------------------------------------------------------------------------------------

const out = {
  source:
    "Bohemia Interactive's DayZ game scripts (DayZ-Script-Diff): the recipes pluginrecipesmanagerbase.c registers (scripts/4_world/classes/recipes) and the vehicle scripts (carscript.c, motorbikescript.c, boatscript.c, inherited classes). Vehicle names, fuel tanks and radiator capacity from the DayZ Wiki (dayz.fandom.com), read 2026-11-15. Ruined ingredients cannot be used unless a recipe says so. takes is in the item's own quantity units (ml for liquids); qty null means the script leaves the amount to the item's default or to chance.",
  licence: 'DayZ Public License (DPL): attribution, non-commercial, DayZ only. Vehicle fluid capacities from the DayZ Wiki (CC BY-NC-SA) where noted.',
  recipes,
  vehicles,
}
const json = `${JSON.stringify(out, null, 1)}\n`
writeFileSync(OUT, json)
console.log(`${recipes.length} recipes, ${vehicles.length} vehicles → ${OUT} (${(json.length / 1024).toFixed(1)} KB)`)
console.log(`skipped: ${skipped.test.length} test (${skipped.test.join(', ')}), ${skipped.unregistered.length} not registered: ${skipped.unregistered.join(', ')}`)
