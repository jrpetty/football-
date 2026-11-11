// Item database. Weights in kg. Food values in % of the 0-100 need scale.
import type { HeldModelId } from '../models/items';

export type ItemCat = 'food' | 'drink' | 'medical' | 'weapon' | 'ammo' | 'tool' | 'material' | 'resource' | 'part' | 'clothing' | 'seed' | 'book' | 'misc';
export type WeaponKind = 'blunt' | 'blade' | 'firearm';
export type ClothSlot = 'head' | 'torso' | 'legs' | 'feet' | 'hands' | 'back';
export type MedKind = 'bandage' | 'rag' | 'disinfect' | 'painkiller' | 'antibiotic' | 'splint' | 'suture' | 'serum';

export interface WeaponDef {
  kind: WeaponKind; damage: number; range: number; speed: number; stamina: number; knock: number;
  durability: number; model: HeldModelId; noise: number;
  ammo?: string; mag?: number; spread?: number; pellets?: number; reload?: number; auto?: boolean; recoil?: number;
  twoHanded?: boolean; tree?: number; mine?: number;
}
export interface ItemDef {
  id: string; name: string; icon: string; cat: ItemCat; weight: number; stack: number; desc: string;
  hunger?: number; thirst?: number; sick?: number; spoilDays?: number; mood?: number; empties?: string; cookTo?: string;
  med?: MedKind; weapon?: WeaponDef; tools?: string[]; fuel?: number;
  cloth?: { slot: ClothSlot; insul: number; bite: number; scratch: number; capacity?: number; rain?: number };
  skillBook?: string; crop?: string; light?: boolean;
}

const I: ItemDef[] = [
  // ---------- food ----------
  { id: 'canned_beans', name: 'Canned Beans', icon: '🥫', cat: 'food', weight: 0.5, stack: 10, hunger: 32, thirst: -4, desc: 'Never expires. Needs a can opener or a knife... or just a rock and patience.' },
  { id: 'canned_soup', name: 'Canned Soup', icon: '🥫', cat: 'food', weight: 0.5, stack: 10, hunger: 26, thirst: 8, desc: 'Chicken noodle. Tastes like the old world.' },
  { id: 'chips', name: 'Potato Chips', icon: '🍟', cat: 'food', weight: 0.15, stack: 10, hunger: 10, thirst: -6, mood: 4, desc: 'Salty comfort.' },
  { id: 'chocolate', name: 'Chocolate Bar', icon: '🍫', cat: 'food', weight: 0.1, stack: 10, hunger: 8, mood: 10, desc: 'A small luxury. Lifts the spirits.' },
  { id: 'apple', name: 'Apple', icon: '🍎', cat: 'food', weight: 0.2, stack: 10, hunger: 12, thirst: 4, spoilDays: 6, desc: 'Fresh fruit. Will rot.' },
  { id: 'bread', name: 'Bread', icon: '🍞', cat: 'food', weight: 0.4, stack: 5, hunger: 22, spoilDays: 4, desc: 'Going stale fast.' },
  { id: 'raw_meat', name: 'Raw Meat', icon: '🥩', cat: 'food', weight: 0.5, stack: 5, hunger: 15, sick: 0.45, spoilDays: 2, cookTo: 'cooked_meat', desc: 'Cook it before eating. Seriously.' },
  { id: 'cooked_meat', name: 'Cooked Meat', icon: '🍖', cat: 'food', weight: 0.4, stack: 5, hunger: 38, spoilDays: 3, mood: 5, desc: 'Hot protein.' },
  { id: 'mre', name: 'Military Ration', icon: '🎖️', cat: 'food', weight: 0.6, stack: 5, hunger: 55, thirst: 5, desc: 'Meal, Ready-to-Eat. Calorie dense.' },
  { id: 'carrot', name: 'Carrot', icon: '🥕', cat: 'food', weight: 0.15, stack: 20, hunger: 9, spoilDays: 10, desc: 'Home grown.' },
  { id: 'potato', name: 'Potato', icon: '🥔', cat: 'food', weight: 0.3, stack: 20, hunger: 8, spoilDays: 20, cookTo: 'baked_potato', desc: 'Better cooked.' },
  { id: 'baked_potato', name: 'Baked Potato', icon: '🥔', cat: 'food', weight: 0.3, stack: 10, hunger: 24, spoilDays: 3, desc: 'Warm and filling.' },
  { id: 'corn', name: 'Corn', icon: '🌽', cat: 'food', weight: 0.3, stack: 20, hunger: 12, spoilDays: 8, desc: 'Sweet corn.' },
  { id: 'raw_fish', name: 'Raw Fish', icon: '🐟', cat: 'food', weight: 0.6, stack: 5, hunger: 12, sick: 0.35, spoilDays: 2, cookTo: 'cooked_fish', desc: 'Fresh from the lake. Cook it.' },
  { id: 'cooked_fish', name: 'Cooked Fish', icon: '🍣', cat: 'food', weight: 0.5, stack: 5, hunger: 30, spoilDays: 2, mood: 4, desc: 'Flaky and hot.' },
  { id: 'rotten_food', name: 'Rotten Food', icon: '🤢', cat: 'food', weight: 0.3, stack: 10, hunger: 6, sick: 0.85, desc: 'You would have to be desperate.' },
  // ---------- drink ----------
  { id: 'water_bottle', name: 'Water Bottle', icon: '💧', cat: 'drink', weight: 0.6, stack: 5, thirst: 40, empties: 'empty_bottle', desc: 'Clean, safe drinking water.' },
  { id: 'dirty_water', name: 'Dirty Water', icon: '🫗', cat: 'drink', weight: 0.6, stack: 5, thirst: 40, sick: 0.55, empties: 'empty_bottle', cookTo: 'water_bottle', desc: 'Boil it at a campfire first.' },
  { id: 'soda', name: 'Soda Can', icon: '🥤', cat: 'drink', weight: 0.35, stack: 10, thirst: 22, hunger: 4, mood: 6, desc: 'Flat but sugary.' },
  { id: 'empty_bottle', name: 'Empty Bottle', icon: '🍶', cat: 'misc', weight: 0.05, stack: 10, desc: 'Fill at water sources or rain collectors.' },
  // ---------- medical ----------
  { id: 'rag', name: 'Rag', icon: '🧻', cat: 'medical', weight: 0.05, stack: 20, med: 'rag', desc: 'Improvised bandage. Unsterile — wounds may get infected.' },
  { id: 'bandage', name: 'Sterile Bandage', icon: '🩹', cat: 'medical', weight: 0.05, stack: 20, med: 'bandage', desc: 'Stops bleeding and keeps wounds clean.' },
  { id: 'disinfectant', name: 'Disinfectant', icon: '🧴', cat: 'medical', weight: 0.3, stack: 5, med: 'disinfect', desc: 'Cleans a wound, curing wound infection.' },
  { id: 'painkillers', name: 'Painkillers', icon: '💊', cat: 'medical', weight: 0.1, stack: 10, med: 'painkiller', desc: 'Dulls pain for a few hours.' },
  { id: 'antibiotics', name: 'Antibiotics', icon: '💉', cat: 'medical', weight: 0.1, stack: 10, med: 'antibiotic', desc: 'Fights wound infection and sickness.' },
  { id: 'splint', name: 'Splint', icon: '🦴', cat: 'medical', weight: 0.4, stack: 5, med: 'splint', desc: 'Set fractured limbs so they can heal.' },
  { id: 'suture_kit', name: 'Suture Kit', icon: '🪡', cat: 'medical', weight: 0.1, stack: 5, med: 'suture', desc: 'Stitch deep lacerations — they heal far faster.' },
  { id: 'serum', name: 'Antiviral Serum', icon: '🧪', cat: 'medical', weight: 0.1, stack: 10, med: 'serum', desc: 'The cure. Halts and reverses the Knox infection. Civilisation\'s hope.' },
  // ---------- weapons ----------
  { id: 'bat', name: 'Baseball Bat', icon: '🏏', cat: 'weapon', weight: 1.0, stack: 1, desc: 'Reliable blunt weapon.', weapon: { kind: 'blunt', damage: 34, range: 2.2, speed: 1.15, stamina: 7, knock: 1.4, durability: 160, model: 'bat', noise: 6, twoHanded: true } },
  { id: 'bat_nails', name: 'Spiked Bat', icon: '🏏', cat: 'weapon', weight: 1.2, stack: 1, desc: 'A bat with nails driven through it.', weapon: { kind: 'blunt', damage: 48, range: 2.2, speed: 1.05, stamina: 8, knock: 1.3, durability: 140, model: 'bat_nails', noise: 6, twoHanded: true } },
  { id: 'crowbar', name: 'Crowbar', icon: '🔧', cat: 'weapon', weight: 1.5, stack: 1, tools: ['pry'], desc: 'Durable. Also pries open locked containers.', weapon: { kind: 'blunt', damage: 36, range: 1.9, speed: 1.2, stamina: 6, knock: 1.1, durability: 500, model: 'crowbar', noise: 6 } },
  { id: 'pipe', name: 'Lead Pipe', icon: '🪈', cat: 'weapon', weight: 1.4, stack: 1, desc: 'Heavy and crude.', weapon: { kind: 'blunt', damage: 32, range: 1.9, speed: 1.15, stamina: 7, knock: 1.2, durability: 320, model: 'pipe', noise: 6 } },
  { id: 'sledgehammer', name: 'Sledgehammer', icon: '🔨', cat: 'weapon', weight: 5.0, stack: 1, tools: ['demolish'], desc: 'Devastating but exhausting. Destroys structures.', weapon: { kind: 'blunt', damage: 95, range: 2.4, speed: 0.55, stamina: 20, knock: 3, durability: 400, model: 'sledgehammer', noise: 9, twoHanded: true } },
  { id: 'knife', name: 'Kitchen Knife', icon: '🔪', cat: 'weapon', weight: 0.3, stack: 1, tools: ['knife'], desc: 'Fast and quiet. Stab to the head.', weapon: { kind: 'blade', damage: 26, range: 1.5, speed: 2.0, stamina: 3, knock: 0.3, durability: 90, model: 'knife', noise: 2 } },
  { id: 'machete', name: 'Machete', icon: '🗡️', cat: 'weapon', weight: 1.0, stack: 1, tools: ['knife'], desc: 'Sweeping blade. Great against crowds.', weapon: { kind: 'blade', damage: 46, range: 2.0, speed: 1.3, stamina: 6, knock: 0.6, durability: 180, model: 'machete', noise: 3 } },
  { id: 'axe', name: 'Fire Axe', icon: '🪓', cat: 'weapon', weight: 2.5, stack: 1, tools: ['axe'], desc: 'Fells trees and zombies alike.', weapon: { kind: 'blade', damage: 58, range: 2.1, speed: 0.85, stamina: 11, knock: 1.4, durability: 260, model: 'axe', noise: 5, twoHanded: true, tree: 1 } },
  { id: 'hammer', name: 'Hammer', icon: '🔨', cat: 'tool', weight: 0.8, stack: 1, tools: ['hammer'], desc: 'Required for carpentry and barricading.', weapon: { kind: 'blunt', damage: 20, range: 1.6, speed: 1.5, stamina: 4, knock: 0.6, durability: 300, model: 'hammer', noise: 5 } },
  { id: 'pickaxe', name: 'Pickaxe', icon: '⛏️', cat: 'tool', weight: 3.0, stack: 1, tools: ['pickaxe'], desc: 'Mine ore and stone from deposits.', weapon: { kind: 'blade', damage: 40, range: 2.0, speed: 0.8, stamina: 12, knock: 1.2, durability: 350, model: 'pickaxe', noise: 6, twoHanded: true, mine: 1 } },
  { id: 'pistol', name: '9mm Pistol', icon: '🔫', cat: 'weapon', weight: 1.0, stack: 1, desc: 'Loud. Every shot rings out for blocks.', weapon: { kind: 'firearm', damage: 48, range: 80, speed: 4, stamina: 0, knock: 0.6, durability: 800, model: 'pistol', noise: 75, ammo: 'ammo_9mm', mag: 15, spread: 0.018, reload: 1.6, recoil: 0.035 } },
  { id: 'shotgun', name: 'Pump Shotgun', icon: '🔫', cat: 'weapon', weight: 3.5, stack: 1, desc: 'Close range crowd control. Deafening.', weapon: { kind: 'firearm', damage: 22, range: 35, speed: 1.1, stamina: 0, knock: 2.5, durability: 600, model: 'shotgun', noise: 110, ammo: 'ammo_shells', mag: 6, spread: 0.075, pellets: 8, reload: 3.2, recoil: 0.12, twoHanded: true } },
  { id: 'rifle', name: 'Hunting Rifle', icon: '🔫', cat: 'weapon', weight: 4.0, stack: 1, desc: 'Precise and powerful. Bolt action.', weapon: { kind: 'firearm', damage: 140, range: 200, speed: 0.9, stamina: 0, knock: 2, durability: 700, model: 'rifle', noise: 130, ammo: 'ammo_rifle', mag: 5, spread: 0.004, reload: 2.8, recoil: 0.09, twoHanded: true } },
  // ---------- ammo ----------
  { id: 'ammo_9mm', name: '9mm Rounds', icon: '🟡', cat: 'ammo', weight: 0.012, stack: 120, desc: 'Pistol ammunition.' },
  { id: 'ammo_shells', name: 'Shotgun Shells', icon: '🔴', cat: 'ammo', weight: 0.04, stack: 48, desc: '12 gauge buckshot.' },
  { id: 'ammo_rifle', name: 'Rifle Rounds', icon: '🟤', cat: 'ammo', weight: 0.025, stack: 60, desc: '.308 Winchester.' },
  // ---------- tools ----------
  { id: 'saw', name: 'Saw', icon: '🪚', cat: 'tool', weight: 0.8, stack: 1, tools: ['saw'], desc: 'Cut logs into planks.' },
  { id: 'screwdriver', name: 'Screwdriver', icon: '🪛', cat: 'tool', weight: 0.2, stack: 1, tools: ['screwdriver'], desc: 'Needed for electronics and machine work.' },
  { id: 'wrench', name: 'Wrench', icon: '🔧', cat: 'tool', weight: 0.6, stack: 1, tools: ['wrench'], desc: 'Needed to assemble machines.' },
  { id: 'flashlight', name: 'Flashlight', icon: '🔦', cat: 'tool', weight: 0.4, stack: 1, light: true, desc: 'Press F to toggle. Drains batteries.', weapon: { kind: 'blunt', damage: 8, range: 1.5, speed: 1.6, stamina: 3, knock: 0.3, durability: 200, model: 'flashlight', noise: 3 } },
  { id: 'fishing_rod', name: 'Fishing Rod', icon: '🎣', cat: 'tool', weight: 0.8, stack: 1, tools: ['fishing'], desc: 'Look at the lake and press E to fish.' },
  { id: 'battery', name: 'Battery', icon: '🔋', cat: 'tool', weight: 0.05, stack: 20, desc: 'Powers the flashlight.' },
  { id: 'lighter', name: 'Lighter', icon: '🔥', cat: 'tool', weight: 0.05, stack: 1, tools: ['fire'], desc: 'Light campfires.' },
  { id: 'gas_can', name: 'Gas Can', icon: '⛽', cat: 'tool', weight: 4, stack: 1, fuel: 60, empties: 'gas_can_empty', desc: 'Fuel for vehicles and generators. Use it next to a car to refuel.' },
  { id: 'gas_can_empty', name: 'Empty Gas Can', icon: '🛢️', cat: 'tool', weight: 0.6, stack: 1, desc: 'Fill at a powered gas pump, or siphon from a parked car (T).' },
  { id: 'car_keys', name: 'Car Keys', icon: '🔑', cat: 'misc', weight: 0.05, stack: 10, desc: 'Somebody\'s car keys. Opens a random car in town — try the ones nearby.' },
  // ---------- materials ----------
  { id: 'log', name: 'Log', icon: '🪵', cat: 'material', weight: 4, stack: 10, fuel: 18, desc: 'Chopped timber. Saw into planks or burn.' },
  { id: 'plank', name: 'Plank', icon: '🟫', cat: 'material', weight: 1, stack: 30, fuel: 6, desc: 'Basic building material.' },
  { id: 'nails', name: 'Nails', icon: '📌', cat: 'material', weight: 0.01, stack: 200, desc: 'Carpentry essential.' },
  { id: 'scrap_metal', name: 'Scrap Metal', icon: '⚙️', cat: 'material', weight: 0.5, stack: 50, desc: 'Salvaged metal. Can be refined.' },
  { id: 'sheet_metal', name: 'Sheet Metal', icon: '🔳', cat: 'material', weight: 1.5, stack: 20, desc: 'Flattened metal sheets for sturdier walls.' },
  { id: 'duct_tape', name: 'Duct Tape', icon: '🩶', cat: 'material', weight: 0.1, stack: 20, desc: 'Fixes everything. Repairs weapons.' },
  { id: 'rope', name: 'Rope', icon: '🪢', cat: 'material', weight: 0.3, stack: 20, desc: 'Useful for crafting.' },
  { id: 'electronics', name: 'Scrap Electronics', icon: '📟', cat: 'material', weight: 0.2, stack: 50, desc: 'Circuit boards and components from old devices.' },
  { id: 'stone', name: 'Stone', icon: '🪨', cat: 'resource', weight: 1, stack: 50, desc: 'Limestone. Smash into concrete.' },
  { id: 'leaves', name: 'Biomass', icon: '🍂', cat: 'material', weight: 0.2, stack: 100, fuel: 4, desc: 'Leaves and twigs. Burner fuel.' },
  // ---------- resources ----------
  { id: 'iron_ore', name: 'Iron Ore', icon: '🟥', cat: 'resource', weight: 1, stack: 100, desc: 'Smelt into iron ingots.' },
  { id: 'copper_ore', name: 'Copper Ore', icon: '🟧', cat: 'resource', weight: 1, stack: 100, desc: 'Smelt into copper ingots.' },
  { id: 'coal', name: 'Coal', icon: '⬛', cat: 'resource', weight: 1, stack: 100, fuel: 30, desc: 'Generator fuel. Used for steel and gunpowder.' },
  { id: 'sulfur', name: 'Sulfur', icon: '🟨', cat: 'resource', weight: 1, stack: 100, desc: 'For gunpowder.' },
  // ---------- parts (factory products) ----------
  { id: 'iron_ingot', name: 'Iron Ingot', icon: '🧱', cat: 'part', weight: 0.6, stack: 100, desc: 'Refined iron.' },
  { id: 'copper_ingot', name: 'Copper Ingot', icon: '🟠', cat: 'part', weight: 0.6, stack: 100, desc: 'Refined copper.' },
  { id: 'steel_ingot', name: 'Steel Ingot', icon: '⬜', cat: 'part', weight: 0.6, stack: 100, desc: 'Iron alloyed with coal.' },
  { id: 'iron_plate', name: 'Iron Plate', icon: '▫️', cat: 'part', weight: 0.5, stack: 100, desc: 'Pressed iron plate.' },
  { id: 'iron_rod', name: 'Iron Rod', icon: '➖', cat: 'part', weight: 0.3, stack: 100, desc: 'Iron rod.' },
  { id: 'screws', name: 'Screws', icon: '🔩', cat: 'part', weight: 0.01, stack: 500, desc: 'So many screws.' },
  { id: 'wire', name: 'Wire', icon: '〰️', cat: 'part', weight: 0.05, stack: 500, desc: 'Copper wire.' },
  { id: 'cable', name: 'Cable', icon: '➰', cat: 'part', weight: 0.2, stack: 200, desc: 'Insulated cable.' },
  { id: 'concrete', name: 'Concrete', icon: '🧊', cat: 'part', weight: 2, stack: 100, desc: 'Bagged concrete.' },
  { id: 'reinforced_plate', name: 'Reinforced Plate', icon: '🛡️', cat: 'part', weight: 2, stack: 100, desc: 'Plates screwed together.' },
  { id: 'rotor', name: 'Rotor', icon: '🌀', cat: 'part', weight: 1.5, stack: 100, desc: 'Spinning component.' },
  { id: 'circuit', name: 'Circuit Board', icon: '💾', cat: 'part', weight: 0.2, stack: 100, desc: 'Electronic logic.' },
  { id: 'gunpowder', name: 'Gunpowder', icon: '🧂', cat: 'part', weight: 0.1, stack: 200, desc: 'Coal and sulfur. Handle with care.' },
  { id: 'modular_frame', name: 'Modular Frame', icon: '🔲', cat: 'part', weight: 3, stack: 50, desc: 'Structural frame for advanced builds.' },
  { id: 'plastic', name: 'Plastic', icon: '🟦', cat: 'part', weight: 0.3, stack: 200, desc: 'Refined from crude oil.' },
  { id: 'rubber', name: 'Rubber', icon: '⚫', cat: 'part', weight: 0.4, stack: 200, desc: 'Refined from crude oil.' },
  { id: 'repair_kit', name: 'Vehicle Repair Kit', icon: '🧰', cat: 'tool', weight: 3, stack: 5, desc: 'Use next to a vehicle to restore 50% condition.' },
  { id: 'f_water', name: 'Water (fluid)', icon: '💧', cat: 'resource', weight: 0, stack: 1, desc: 'Pumped water.' },
  { id: 'f_crude', name: 'Crude Oil', icon: '🛢️', cat: 'resource', weight: 0, stack: 1, desc: 'Pumped from an oil deposit.' },
  { id: 'f_fuel', name: 'Fuel (fluid)', icon: '⛽', cat: 'resource', weight: 0, stack: 1, desc: 'Refined fuel.' },
  { id: 'zombie_tissue', name: 'Infected Tissue', icon: '🧫', cat: 'misc', weight: 0.1, stack: 50, desc: 'Sample from the infected. A lab could study it...' },
  // ---------- clothing ----------
  { id: 'hoodie', name: 'Hoodie', icon: '🧥', cat: 'clothing', weight: 0.6, stack: 1, cloth: { slot: 'torso', insul: 0.35, bite: 0.05, scratch: 0.2 }, desc: 'Warm-ish.' },
  { id: 'leather_jacket', name: 'Leather Jacket', icon: '🧥', cat: 'clothing', weight: 1.4, stack: 1, cloth: { slot: 'torso', insul: 0.45, bite: 0.35, scratch: 0.6, rain: 0.4 }, desc: 'Thick leather resists scratches and bites.' },
  { id: 'police_vest', name: 'Kevlar Vest', icon: '🦺', cat: 'clothing', weight: 2.5, stack: 1, cloth: { slot: 'torso', insul: 0.2, bite: 0.6, scratch: 0.75 }, desc: 'Excellent torso protection.' },
  { id: 'raincoat', name: 'Raincoat', icon: '🧥', cat: 'clothing', weight: 0.5, stack: 1, cloth: { slot: 'torso', insul: 0.15, bite: 0.02, scratch: 0.1, rain: 0.9 }, desc: 'Keeps you dry.' },
  { id: 'jeans', name: 'Jeans', icon: '👖', cat: 'clothing', weight: 0.8, stack: 1, cloth: { slot: 'legs', insul: 0.2, bite: 0.1, scratch: 0.35 }, desc: 'Durable denim.' },
  { id: 'cargo_pants', name: 'Cargo Pants', icon: '👖', cat: 'clothing', weight: 0.9, stack: 1, cloth: { slot: 'legs', insul: 0.25, bite: 0.12, scratch: 0.35, capacity: 3 }, desc: 'Extra pockets.' },
  { id: 'boots', name: 'Work Boots', icon: '🥾', cat: 'clothing', weight: 1.2, stack: 1, cloth: { slot: 'feet', insul: 0.2, bite: 0.4, scratch: 0.5 }, desc: 'Steel toe.' },
  { id: 'sneakers', name: 'Sneakers', icon: '👟', cat: 'clothing', weight: 0.6, stack: 1, cloth: { slot: 'feet', insul: 0.1, bite: 0.05, scratch: 0.15 }, desc: 'Light and quiet.' },
  { id: 'beanie', name: 'Beanie', icon: '🧢', cat: 'clothing', weight: 0.1, stack: 1, cloth: { slot: 'head', insul: 0.25, bite: 0, scratch: 0.05 }, desc: 'Keeps your head warm.' },
  { id: 'helmet', name: 'Riot Helmet', icon: '⛑️', cat: 'clothing', weight: 1.5, stack: 1, cloth: { slot: 'head', insul: 0.1, bite: 0.7, scratch: 0.8 }, desc: 'Protects the most important part.' },
  { id: 'gloves', name: 'Work Gloves', icon: '🧤', cat: 'clothing', weight: 0.2, stack: 1, cloth: { slot: 'hands', insul: 0.15, bite: 0.15, scratch: 0.4 }, desc: 'Better grip, fewer scratches.' },
  { id: 'school_bag', name: 'School Backpack', icon: '🎒', cat: 'clothing', weight: 0.5, stack: 1, cloth: { slot: 'back', insul: 0, bite: 0, scratch: 0, capacity: 12 }, desc: '+12 kg carry capacity.' },
  { id: 'duffel', name: 'Duffel Bag', icon: '👜', cat: 'clothing', weight: 0.8, stack: 1, cloth: { slot: 'back', insul: 0, bite: 0, scratch: 0, capacity: 18 }, desc: '+18 kg carry capacity.' },
  { id: 'hiking_bag', name: 'Hiking Backpack', icon: '🎒', cat: 'clothing', weight: 1.0, stack: 1, cloth: { slot: 'back', insul: 0.05, bite: 0, scratch: 0, capacity: 28 }, desc: '+28 kg carry capacity.' },
  // ---------- seeds ----------
  { id: 'seeds_carrot', name: 'Carrot Seeds', icon: '🌱', cat: 'seed', weight: 0.02, stack: 50, crop: 'carrot', desc: 'Plant in a farm plot.' },
  { id: 'seeds_potato', name: 'Seed Potatoes', icon: '🌱', cat: 'seed', weight: 0.1, stack: 50, crop: 'potato', desc: 'Plant in a farm plot.' },
  { id: 'seeds_corn', name: 'Corn Seeds', icon: '🌱', cat: 'seed', weight: 0.02, stack: 50, crop: 'corn', desc: 'Plant in a farm plot.' },
  // ---------- books ----------
  { id: 'book_carpentry', name: 'Carpentry for Beginners', icon: '📕', cat: 'book', weight: 0.4, stack: 1, skillBook: 'carpentry', desc: 'Read to triple Carpentry XP gain for a while.' },
  { id: 'book_engineering', name: 'Electrical Engineering', icon: '📘', cat: 'book', weight: 0.4, stack: 1, skillBook: 'engineering', desc: 'Read to triple Engineering XP gain.' },
  { id: 'book_firstaid', name: 'Field Medicine', icon: '📗', cat: 'book', weight: 0.4, stack: 1, skillBook: 'firstaid', desc: 'Read to triple First Aid XP gain.' },
  { id: 'book_firearms', name: 'Guns & Ammo Monthly', icon: '📙', cat: 'book', weight: 0.3, stack: 1, skillBook: 'firearms', desc: 'Read to triple Firearms XP gain.' },
  { id: 'book_scavenging', name: 'Urban Foraging', icon: '📓', cat: 'book', weight: 0.3, stack: 1, skillBook: 'scavenging', desc: 'Read to triple Scavenging XP gain.' },
  // ---------- misc ----------
  { id: 'radio_parts', name: 'Radio Parts', icon: '📻', cat: 'misc', weight: 0.5, stack: 10, desc: 'Transmitter components.' },
];

export const ITEMS: Record<string, ItemDef> = Object.fromEntries(I.map(d => [d.id, d]));
export function item(id: string): ItemDef {
  const d = ITEMS[id];
  if (!d) return { id, name: id, icon: '❔', cat: 'misc', weight: 0.1, stack: 10, desc: 'Unknown item' };
  return d;
}
