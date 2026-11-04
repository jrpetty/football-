import type { Inventory, Item } from './data.ts'

export type BuildingType =
  | 'campfire' | 'lean_to' | 'hut' | 'house' | 'apartment' | 'skyscraper'
  | 'stockpile' | 'granary' | 'warehouse'
  | 'farm' | 'pasture' | 'well' | 'workshop' | 'kiln' | 'smithy' | 'mine'
  | 'library' | 'temple' | 'market' | 'town_hall' | 'watchtower' | 'dock' | 'mill'
  | 'university' | 'hospital' | 'factory' | 'power_plant' | 'lab' | 'data_center'
  | 'road' | 'launch_pad' | 'monument'
  | 'oil_well' | 'offshore_rig' | 'evaporation_ponds' | 'refinery' | 'fish_farm' | 'wind_farm' | 'solar_farm'
  | 'nuclear_plant' | 'airport' | 'radio_tower' | 'harbour' | 'walls' | 'barracks'

/** deposit = on a known ore body this people can reach; offshore = on the sea above one */
export type Placement = 'land' | 'coast' | 'hills' | 'fertile' | 'any' | 'deposit' | 'offshore'

export interface BuildingDef {
  id: BuildingType
  name: string
  size: 1 | 2
  cost: Inventory
  hours: number // labour hours to construct
  tech: string | null
  placement: Placement
  housing?: number
  storage?: number
  warmth?: number // radius of warmth
  light?: number // night light radius
  research?: number // research multiplier when researching here
  happiness?: number
  station?: boolean // crafting station (see recipes)
  water?: boolean // fresh water source
  walkable?: boolean
  /** supplies electricity to the town */
  power?: boolean
  desc: string
}

export const BUILDINGS: Record<BuildingType, BuildingDef> = {
  campfire: { id: 'campfire', name: 'Campfire', size: 1, cost: { wood: 5 }, hours: 1.5, tech: 'fire', placement: 'land', warmth: 4, light: 5, happiness: 2, station: true, walkable: true, desc: 'Warmth, light, cooking and a place to gather.' },
  lean_to: { id: 'lean_to', name: 'Lean-to', size: 1, cost: { wood: 6, fiber: 2 }, hours: 2.5, tech: null, placement: 'land', housing: 2, warmth: 1, desc: 'A crude shelter of branches.' },
  hut: { id: 'hut', name: 'Hut', size: 1, cost: { wood: 12, fiber: 4 }, hours: 5, tech: 'shelter', placement: 'land', housing: 3, warmth: 1, light: 2, desc: 'A sturdy home of timber and thatch.' },
  house: { id: 'house', name: 'Stone house', size: 1, cost: { planks: 8, stone: 12 }, hours: 10, tech: 'masonry', placement: 'land', housing: 5, warmth: 1, light: 2, happiness: 1, desc: 'A warm family home.' },
  apartment: { id: 'apartment', name: 'Apartment block', size: 2, cost: { concrete: 30, steel: 10, glass: 10 }, hours: 30, tech: 'urban_planning', placement: 'land', housing: 20, light: 3, desc: 'Housing for many families.' },
  skyscraper: { id: 'skyscraper', name: 'Skyscraper', size: 2, cost: { concrete: 50, steel: 40, glass: 30 }, hours: 60, tech: 'skyscrapers', placement: 'land', housing: 40, light: 4, happiness: 4, desc: 'A tower of steel and glass.' },
  stockpile: { id: 'stockpile', name: 'Stockpile', size: 1, cost: { wood: 6 }, hours: 1.5, tech: null, placement: 'land', storage: 160, desc: 'Shared tribe storage.' },
  granary: { id: 'granary', name: 'Granary', size: 1, cost: { wood: 12, pottery: 4 }, hours: 6, tech: 'pottery', placement: 'land', storage: 400, desc: 'Large storage; food keeps longer.' },
  warehouse: { id: 'warehouse', name: 'Warehouse', size: 2, cost: { bricks: 20, steel: 6 }, hours: 18, tech: 'industrialization', placement: 'land', storage: 2500, desc: 'Industrial-scale storage.' },
  farm: { id: 'farm', name: 'Farm field', size: 1, cost: { wood: 2 }, hours: 2, tech: 'agriculture', placement: 'fertile', walkable: true, desc: 'Grows grain through spring and summer.' },
  pasture: { id: 'pasture', name: 'Pasture', size: 1, cost: { wood: 10 }, hours: 4, tech: 'animal_husbandry', placement: 'fertile', walkable: true, desc: 'Domesticated herds: steady meat and hides.' },
  well: { id: 'well', name: 'Well', size: 1, cost: { stone: 12 }, hours: 5, tech: 'masonry', placement: 'land', water: true, desc: 'Fresh water close to home.' },
  workshop: { id: 'workshop', name: 'Workshop', size: 1, cost: { wood: 15, stone: 5 }, hours: 6, tech: 'carpentry', placement: 'land', station: true, desc: 'Carpentry, tools, cloth and paper.' },
  kiln: { id: 'kiln', name: 'Kiln', size: 1, cost: { clay: 8, stone: 6 }, hours: 5, tech: 'pottery', placement: 'land', station: true, light: 2, desc: 'Fires pottery, bricks, glass and bread.' },
  smithy: { id: 'smithy', name: 'Smithy', size: 1, cost: { stone: 15, bricks: 6, wood: 10 }, hours: 10, tech: 'bronze_working', placement: 'land', station: true, light: 2, desc: 'Smelts and forges metal.' },
  mine: { id: 'mine', name: 'Mine', size: 1, cost: { wood: 12, stone: 4 }, hours: 6, tech: 'mining', placement: 'deposit', desc: 'Sunk over a known ore body: pits, then shafts, then deep steam-pumped workings as knowledge grows. Without a mine, only surface outcrops can be dug.' },
  library: { id: 'library', name: 'Library', size: 1, cost: { planks: 10, stone: 10, paper: 6 }, hours: 12, tech: 'writing', placement: 'land', research: 1.6, light: 2, desc: 'Research ×1.6. Keeps written works.' },
  temple: { id: 'temple', name: 'Temple', size: 1, cost: { stone: 25, gold: 2 }, hours: 14, tech: 'mysticism', placement: 'land', happiness: 6, light: 2, desc: 'Faith, ritual and community.' },
  market: { id: 'market', name: 'Market', size: 1, cost: { planks: 12, stone: 8 }, hours: 10, tech: 'currency', placement: 'land', happiness: 4, desc: 'Trade hub; lifts spirits.' },
  town_hall: { id: 'town_hall', name: 'Town hall', size: 2, cost: { stone: 30, planks: 20 }, hours: 20, tech: 'code_of_laws', placement: 'land', happiness: 3, light: 3, storage: 300, desc: 'Seat of government. Expands territory.' },
  watchtower: { id: 'watchtower', name: 'Watchtower', size: 1, cost: { stone: 12, wood: 6 }, hours: 8, tech: 'bronze_working', placement: 'land', light: 3, desc: 'Defence and lookout.' },
  dock: { id: 'dock', name: 'Dock', size: 1, cost: { planks: 14, wood: 6 }, hours: 10, tech: 'sailing', placement: 'coast', desc: 'Deep-water fishing ×2.' },
  mill: { id: 'mill', name: 'Mill', size: 1, cost: { planks: 12, stone: 10 }, hours: 12, tech: 'engineering', placement: 'land', station: true, desc: 'Grinds grain; farm yields ×1.5.' },
  university: { id: 'university', name: 'University', size: 2, cost: { bricks: 30, planks: 20, paper: 12 }, hours: 30, tech: 'education', placement: 'land', research: 2.6, light: 3, happiness: 2, desc: 'Research ×2.6.' },
  hospital: { id: 'hospital', name: 'Hospital', size: 1, cost: { bricks: 20, medicine: 6, planks: 8 }, hours: 18, tech: 'medicine', placement: 'land', light: 2, desc: 'Heals the sick and injured.' },
  factory: { id: 'factory', name: 'Factory', size: 2, cost: { bricks: 30, steel: 10, iron: 10 }, hours: 30, tech: 'industrialization', placement: 'land', station: true, light: 3, desc: 'Machinery, fuel and electronics.' },
  power_plant: { id: 'power_plant', name: 'Power plant', size: 2, cost: { steel: 20, concrete: 20, machinery: 5 }, hours: 36, tech: 'electricity', placement: 'land', light: 4, power: true, desc: 'Coal-fired power station: electrifies the town (all work ×1.4, lit streets).' },
  lab: { id: 'lab', name: 'Laboratory', size: 1, cost: { concrete: 15, glass: 10, electronics: 5 }, hours: 24, tech: 'research_labs', placement: 'land', research: 3.6, light: 3, desc: 'Research ×3.6.' },
  data_center: { id: 'data_center', name: 'Data centre', size: 2, cost: { concrete: 30, electronics: 30, steel: 10 }, hours: 40, tech: 'computing', placement: 'land', research: 5, light: 3, desc: 'Research ×5.' },
  road: { id: 'road', name: 'Road', size: 1, cost: { stone: 1 }, hours: 0.5, tech: 'the_wheel', placement: 'land', walkable: true, desc: 'Fast travel.' },
  monument: { id: 'monument', name: 'Monument', size: 1, cost: { stone: 40, gold: 5 }, hours: 30, tech: 'masonry', placement: 'land', happiness: 8, light: 2, desc: 'A great work to be remembered by.' },
  oil_well: { id: 'oil_well', name: 'Oil well', size: 1, cost: { steel: 10, machinery: 3, planks: 6 }, hours: 16, tech: 'oil_drilling', placement: 'deposit', desc: 'A derrick and pumpjack over a known oil reservoir.' },
  offshore_rig: { id: 'offshore_rig', name: 'Offshore platform', size: 2, cost: { steel: 40, concrete: 20, machinery: 8 }, hours: 40, tech: 'offshore_drilling', placement: 'offshore', light: 3, desc: 'Drills oil from beneath the sea floor. Crews come and go by boat.' },
  evaporation_ponds: { id: 'evaporation_ponds', name: 'Evaporation ponds', size: 2, cost: { concrete: 12, plastic: 6 }, hours: 18, tech: 'chemistry', placement: 'deposit', walkable: true, desc: 'Pump lithium brine from under the salt flat and let the sun do the rest.' },
  refinery: { id: 'refinery', name: 'Refinery', size: 2, cost: { steel: 30, bricks: 20, machinery: 6 }, hours: 34, tech: 'refining', placement: 'land', station: true, light: 4, desc: 'Distils crude oil into fuel and plastics.' },
  fish_farm: { id: 'fish_farm', name: 'Fish farm', size: 1, cost: { planks: 10, plastic: 6, steel: 2 }, hours: 12, tech: 'aquaculture', placement: 'coast', desc: 'Net pens raise fish without emptying the sea: a steady harvest every day.' },
  wind_farm: { id: 'wind_farm', name: 'Wind turbines', size: 2, cost: { steel: 20, concrete: 10, machinery: 6, plastic: 8 }, hours: 30, tech: 'wind_power', placement: 'land', power: true, desc: 'Clean power from the wind.' },
  solar_farm: { id: 'solar_farm', name: 'Solar farm', size: 2, cost: { glass: 20, aluminium: 10, electronics: 10 }, hours: 26, tech: 'solar_power', placement: 'land', walkable: true, power: true, desc: 'Fields of photovoltaic panels.' },
  nuclear_plant: { id: 'nuclear_plant', name: 'Nuclear power station', size: 2, cost: { concrete: 60, steel: 40, uranium: 10, electronics: 10 }, hours: 60, tech: 'nuclear_power', placement: 'land', light: 4, power: true, desc: 'Fission reactor and cooling towers: enormous, steady power.' },
  airport: { id: 'airport', name: 'Airport', size: 2, cost: { concrete: 40, steel: 20, electronics: 10, fuel: 10 }, hours: 40, tech: 'flight', placement: 'land', light: 4, happiness: 3, walkable: true, desc: 'A runway and terminal: people and goods fly.' },
  radio_tower: { id: 'radio_tower', name: 'Radio mast', size: 1, cost: { steel: 10, electronics: 6 }, hours: 12, tech: 'radio', placement: 'land', research: 1.3, light: 2, happiness: 2, desc: 'Broadcasts news, music and lessons to everyone.' },
  harbour: { id: 'harbour', name: 'Harbour', size: 2, cost: { concrete: 30, steel: 16, planks: 20 }, hours: 36, tech: 'steamships', placement: 'coast', light: 3, desc: 'Quays, cranes and a fishing fleet: big boats, big catches.' },
  walls: { id: 'walls', name: 'Town walls', size: 2, cost: { stone: 40, wood: 16 }, hours: 30, tech: 'masonry', placement: 'land', walkable: true, desc: 'A palisade, then stone walls and towers, then concrete: defenders fight far harder behind them.' },
  barracks: { id: 'barracks', name: 'Barracks', size: 1, cost: { stone: 14, planks: 10 }, hours: 14, tech: 'bronze_working', placement: 'land', desc: 'Drilled, armed and trained warriors: the whole people fights a quarter better.' },
  launch_pad: { id: 'launch_pad', name: 'Launch pad', size: 2, cost: { concrete: 40, steel: 40, electronics: 25, fuel: 40, machinery: 10, aluminium: 20 }, hours: 60, tech: 'rocketry', placement: 'land', light: 4, desc: 'Send your people to the stars.' },
}

export const BUILDING_TYPES = Object.keys(BUILDINGS) as BuildingType[]

export interface Recipe {
  id: string
  name: string
  inputs: Inventory
  out: Item | 'tools' | null
  outN: number
  tier?: number // for tools
  tech: string | null
  station: BuildingType[] | null // any of these nearby; null = anywhere
  hours: number
}

export const RECIPES: Recipe[] = [
  { id: 'stone_tools', name: 'Stone tools', inputs: { stone: 2, wood: 1 }, out: 'tools', outN: 1, tier: 1, tech: 'stone_tools', station: null, hours: 1 },
  { id: 'clothes', name: 'Hide clothes', inputs: { hide: 2 }, out: 'clothes', outN: 1, tech: 'clothing', station: null, hours: 1.5 },
  { id: 'cloth', name: 'Cloth', inputs: { fiber: 3 }, out: 'cloth', outN: 1, tech: 'weaving', station: null, hours: 1 },
  { id: 'woven_clothes', name: 'Woven clothes', inputs: { cloth: 2 }, out: 'clothes', outN: 2, tech: 'weaving', station: ['workshop'], hours: 1.5 },
  { id: 'medicine', name: 'Herbal medicine', inputs: { fiber: 2, berries: 1 }, out: 'medicine', outN: 1, tech: 'herbalism', station: null, hours: 1 },
  { id: 'planks', name: 'Planks', inputs: { wood: 2 }, out: 'planks', outN: 3, tech: 'carpentry', station: ['workshop'], hours: 1 },
  { id: 'pottery', name: 'Pottery', inputs: { clay: 2 }, out: 'pottery', outN: 1, tech: 'pottery', station: ['kiln', 'campfire'], hours: 1 },
  { id: 'bread', name: 'Bread', inputs: { grain: 2 }, out: 'bread', outN: 2, tech: 'pottery', station: ['kiln', 'mill', 'campfire'], hours: 0.8 },
  { id: 'bricks', name: 'Bricks', inputs: { clay: 2, wood: 1 }, out: 'bricks', outN: 3, tech: 'masonry', station: ['kiln'], hours: 1.2 },
  { id: 'paper', name: 'Paper', inputs: { fiber: 2 }, out: 'paper', outN: 2, tech: 'writing', station: ['workshop'], hours: 1 },
  { id: 'bronze', name: 'Bronze', inputs: { copper: 2, tin: 1, wood: 1 }, out: 'bronze', outN: 2, tech: 'bronze_working', station: ['smithy'], hours: 1.5 },
  { id: 'bronze_tools', name: 'Bronze tools', inputs: { bronze: 2, wood: 1 }, out: 'tools', outN: 1, tier: 2, tech: 'bronze_working', station: ['smithy'], hours: 1.5 },
  { id: 'iron', name: 'Iron', inputs: { iron_ore: 2, coal: 1 }, out: 'iron', outN: 1, tech: 'iron_working', station: ['smithy', 'factory'], hours: 1.5 },
  { id: 'iron_charcoal', name: 'Iron (charcoal)', inputs: { iron_ore: 2, wood: 3 }, out: 'iron', outN: 1, tech: 'iron_working', station: ['smithy'], hours: 2 },
  { id: 'iron_tools', name: 'Iron tools', inputs: { iron: 2, wood: 1 }, out: 'tools', outN: 1, tier: 3, tech: 'iron_working', station: ['smithy'], hours: 1.5 },
  { id: 'glass', name: 'Glass', inputs: { sand: 2, coal: 1 }, out: 'glass', outN: 2, tech: 'glassmaking', station: ['kiln', 'factory'], hours: 1.2 },
  { id: 'glass_wood', name: 'Glass (wood-fired)', inputs: { sand: 2, wood: 2 }, out: 'glass', outN: 1, tech: 'glassmaking', station: ['kiln'], hours: 1.5 },
  { id: 'concrete', name: 'Concrete', inputs: { stone: 2, clay: 1, sand: 1 }, out: 'concrete', outN: 2, tech: 'engineering', station: ['kiln', 'factory', 'mill'], hours: 1.2 },
  { id: 'steel', name: 'Steel', inputs: { iron: 2, coal: 2 }, out: 'steel', outN: 2, tech: 'steel', station: ['smithy', 'factory'], hours: 2 },
  { id: 'steel_tools', name: 'Steel tools', inputs: { steel: 2, wood: 1 }, out: 'tools', outN: 1, tier: 4, tech: 'steel', station: ['smithy', 'factory'], hours: 1.5 },
  { id: 'machinery', name: 'Machinery', inputs: { steel: 2, iron: 2 }, out: 'machinery', outN: 1, tech: 'industrialization', station: ['factory'], hours: 2 },
  { id: 'fuel', name: 'Fuel', inputs: { oil: 2 }, out: 'fuel', outN: 2, tech: 'refining', station: ['refinery', 'factory'], hours: 1 },
  { id: 'plastic', name: 'Plastic', inputs: { oil: 2 }, out: 'plastic', outN: 3, tech: 'plastics', station: ['refinery', 'factory'], hours: 1 },
  { id: 'aluminium', name: 'Aluminium', inputs: { bauxite: 4 }, out: 'aluminium', outN: 1, tech: 'aluminium_smelting', station: ['factory'], hours: 2 },
  { id: 'batteries', name: 'Batteries', inputs: { lithium: 1, copper: 1, plastic: 1 }, out: 'batteries', outN: 2, tech: 'lithium_batteries', station: ['factory'], hours: 1.5 },
  { id: 'coke_iron', name: 'Iron (coke furnace)', inputs: { iron_ore: 3, coal: 1 }, out: 'iron', outN: 2, tech: 'coke_smelting', station: ['factory', 'smithy'], hours: 1.5 },
  { id: 'electronics', name: 'Electronics', inputs: { copper: 2, glass: 1, gold: 1 }, out: 'electronics', outN: 2, tech: 'electronics', station: ['factory'], hours: 2 },
  { id: 'power_tools', name: 'Power tools', inputs: { machinery: 1, electronics: 1 }, out: 'tools', outN: 1, tier: 5, tech: 'electricity', station: ['factory'], hours: 2 },
]

export const RECIPE_BY_ID: Record<string, Recipe> = Object.fromEntries(RECIPES.map((r) => [r.id, r]))

export const TOOL_NAMES = ['bare hands', 'stone tools', 'bronze tools', 'iron tools', 'steel tools', 'power tools']
export const TOOL_MULT = [1, 1.5, 2, 2.5, 3.2, 4.2]
