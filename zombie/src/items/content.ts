// Content expansion: minerals, factory chains, farming, cooking, foraging, crafting, weapons, armour, trains.
// Imported once at startup (before the game is constructed); it registers into the shared tables.
import { ITEMS, ItemDef } from './items';
import { RECIPES, Recipe } from './recipes';
import { MRECIPES, MRecipe, MACHINES, PIECES, MILESTONES } from '../factory/defs';

const items: ItemDef[] = [
  // ---- minerals
  { id: 'quartz', name: 'Raw Quartz', icon: '🔷', cat: 'resource', weight: 1, stack: 100, desc: 'Crush into silica for glass and electronics.' },
  { id: 'bauxite', name: 'Bauxite', icon: '🟠', cat: 'resource', weight: 1, stack: 100, desc: 'Aluminium ore. Refine with water into alumina.' },
  { id: 'gold_ore', name: 'Gold Ore', icon: '✨', cat: 'resource', weight: 1, stack: 100, desc: 'Smelt into gold for high-end electronics.' },
  { id: 'sand', name: 'Sand', icon: '🏖️', cat: 'resource', weight: 1, stack: 100, desc: 'Crushed stone. Smelt into glass.' },
  { id: 'charcoal', name: 'Charcoal', icon: '◾', cat: 'material', weight: 0.3, stack: 100, fuel: 25, desc: 'Burns hot. Used in water filters.' },
  // ---- factory parts
  { id: 'silica', name: 'Silica', icon: '⚪', cat: 'part', weight: 0.3, stack: 200, desc: 'Refined quartz powder.' },
  { id: 'glass', name: 'Glass Pane', icon: '🪟', cat: 'part', weight: 1, stack: 100, desc: 'For greenhouses, jars and windows.' },
  { id: 'jar', name: 'Glass Jar', icon: '🫙', cat: 'misc', weight: 0.3, stack: 20, desc: 'Preserve food so it never spoils.' },
  { id: 'gold_ingot', name: 'Gold Ingot', icon: '🥇', cat: 'part', weight: 0.8, stack: 100, desc: 'Conductive and incorruptible.' },
  { id: 'alumina', name: 'Alumina', icon: '🧂', cat: 'part', weight: 0.4, stack: 200, desc: 'Refined bauxite.' },
  { id: 'aluminium_ingot', name: 'Aluminium Ingot', icon: '🔘', cat: 'part', weight: 0.3, stack: 100, desc: 'Light and strong.' },
  { id: 'aluminium_sheet', name: 'Aluminium Sheet', icon: '🔳', cat: 'part', weight: 0.3, stack: 100, desc: 'For vehicles and advanced builds.' },
  { id: 'steel_beam', name: 'Steel Beam', icon: '🏗️', cat: 'part', weight: 2, stack: 100, desc: 'Structural steel.' },
  { id: 'steel_pipe', name: 'Steel Pipe', icon: '🔧', cat: 'part', weight: 1, stack: 100, desc: 'Steel tubing.' },
  { id: 'stator', name: 'Stator', icon: '🧲', cat: 'part', weight: 1.5, stack: 100, desc: 'Wire-wound housing for motors.' },
  { id: 'motor', name: 'Motor', icon: '⚙️', cat: 'part', weight: 3, stack: 50, desc: 'Rotor + stator. Drives vehicles and big machines.' },
  { id: 'computer', name: 'Computer', icon: '🖥️', cat: 'part', weight: 2, stack: 50, desc: 'Circuits in a plastic case.' },
  { id: 'heavy_frame', name: 'Heavy Modular Frame', icon: '🧱', cat: 'part', weight: 6, stack: 50, desc: 'Massive load-bearing frame.' },
  { id: 'battery_cell', name: 'Battery Cell', icon: '🔋', cat: 'part', weight: 0.8, stack: 100, desc: 'Stores charge.' },
  { id: 'fertilizer', name: 'Fertilizer', icon: '💩', cat: 'material', weight: 0.5, stack: 50, desc: 'Use on a farm plot: faster growth, bigger harvest.' },
  { id: 'rail', name: 'Rail Segment', icon: '🛤️', cat: 'part', weight: 3, stack: 100, desc: 'Track for trains.' },
  // ---- crops, foraging, animal products
  { id: 'wheat', name: 'Wheat', icon: '🌾', cat: 'food', weight: 0.2, stack: 50, hunger: 2, spoilDays: 30, desc: 'Mill into flour.' },
  { id: 'flour', name: 'Flour', icon: '🥡', cat: 'food', weight: 0.5, stack: 20, hunger: 3, spoilDays: 40, desc: 'Bake into bread.' },
  { id: 'tomato', name: 'Tomato', icon: '🍅', cat: 'food', weight: 0.2, stack: 20, hunger: 8, thirst: 6, spoilDays: 6, desc: 'Juicy.' },
  { id: 'cabbage', name: 'Cabbage', icon: '🥬', cat: 'food', weight: 0.6, stack: 10, hunger: 10, spoilDays: 10, desc: 'Hardy — grows even in frost.' },
  { id: 'pumpkin', name: 'Pumpkin', icon: '🎃', cat: 'food', weight: 2, stack: 5, hunger: 18, spoilDays: 25, desc: 'Keeps for weeks.' },
  { id: 'hemp', name: 'Hemp', icon: '🌿', cat: 'material', weight: 0.2, stack: 50, desc: 'Fibre for rope and cloth.' },
  { id: 'berries', name: 'Wild Berries', icon: '🫐', cat: 'food', weight: 0.1, stack: 20, hunger: 5, thirst: 3, spoilDays: 3, mood: 3, desc: 'Foraged from bushes.' },
  { id: 'mushroom', name: 'Chanterelle', icon: '🍄', cat: 'food', weight: 0.1, stack: 20, hunger: 6, spoilDays: 3, desc: 'Edible forest mushroom. Better cooked.' },
  { id: 'mushroom_odd', name: 'Odd Mushroom', icon: '🍄', cat: 'food', weight: 0.1, stack: 20, hunger: 4, sick: 0.9, spoilDays: 3, desc: 'Are you sure about this one?' },
  { id: 'herbs', name: 'Medicinal Herbs', icon: '🌱', cat: 'material', weight: 0.05, stack: 30, desc: 'Plantain and yarrow. Make poultices and tea.' },
  { id: 'egg', name: 'Egg', icon: '🥚', cat: 'food', weight: 0.06, stack: 24, hunger: 4, sick: 0.3, spoilDays: 8, desc: 'Cook it.' },
  { id: 'hide', name: 'Animal Hide', icon: '🟤', cat: 'material', weight: 1.5, stack: 10, desc: 'Tan into leather at a workbench.' },
  { id: 'leather', name: 'Leather', icon: '🟫', cat: 'material', weight: 0.6, stack: 30, desc: 'Tough, flexible material.' },
  { id: 'cloth', name: 'Cloth', icon: '🧶', cat: 'material', weight: 0.2, stack: 50, desc: 'Woven fabric.' },
  // ---- cooked food
  { id: 'bread_fresh', name: 'Fresh Bread', icon: '🍞', cat: 'food', weight: 0.4, stack: 5, hunger: 28, spoilDays: 4, mood: 6, desc: 'Baked from your own wheat.' },
  { id: 'stew', name: 'Hearty Stew', icon: '🍲', cat: 'food', weight: 0.8, stack: 5, hunger: 55, thirst: 15, spoilDays: 2, mood: 12, desc: 'Meat and vegetables. Warms you to the bone.' },
  { id: 'veg_soup', name: 'Vegetable Soup', icon: '🥣', cat: 'food', weight: 0.6, stack: 5, hunger: 35, thirst: 20, spoilDays: 2, mood: 8, desc: 'Simple, nourishing.' },
  { id: 'omelette', name: 'Omelette', icon: '🍳', cat: 'food', weight: 0.3, stack: 5, hunger: 26, spoilDays: 1, mood: 8, desc: 'Fluffy.' },
  { id: 'pumpkin_pie', name: 'Pumpkin Pie', icon: '🥧', cat: 'food', weight: 0.8, stack: 5, hunger: 45, spoilDays: 4, mood: 25, desc: 'Tastes like before.' },
  { id: 'mushroom_skewer', name: 'Mushroom Skewer', icon: '🍢', cat: 'food', weight: 0.2, stack: 10, hunger: 16, spoilDays: 2, mood: 4, desc: 'Grilled mushrooms.' },
  { id: 'jerky', name: 'Jerky', icon: '🥓', cat: 'food', weight: 0.2, stack: 20, hunger: 22, thirst: -5, desc: 'Dried meat. Never spoils.' },
  { id: 'fish_stew', name: 'Fish Stew', icon: '🍜', cat: 'food', weight: 0.7, stack: 5, hunger: 45, thirst: 15, spoilDays: 2, mood: 10, desc: 'Lake fish and potatoes.' },
  { id: 'berry_jam', name: 'Berry Jam', icon: '🍯', cat: 'food', weight: 0.4, stack: 10, hunger: 20, mood: 15, desc: 'Preserved in a jar. Never spoils.' },
  { id: 'preserved_veg', name: 'Preserved Vegetables', icon: '🫙', cat: 'food', weight: 0.6, stack: 10, hunger: 30, desc: 'Home-canned. Never spoils.' },
  { id: 'herbal_tea', name: 'Herbal Tea', icon: '🍵', cat: 'drink', weight: 0.4, stack: 5, thirst: 30, mood: 10, desc: 'Calms panic, eases pain a little.' },
  { id: 'poultice', name: 'Herbal Poultice', icon: '🌿', cat: 'medical', weight: 0.1, stack: 10, med: 'disinfect', desc: 'Herbs bound in cloth. Cleans wounds like disinfectant.' },
  // ---- seeds
  { id: 'seeds_wheat', name: 'Wheat Seeds', icon: '🌱', cat: 'seed', weight: 0.02, stack: 50, crop: 'wheat', desc: 'Plant in a farm plot.' },
  { id: 'seeds_tomato', name: 'Tomato Seeds', icon: '🌱', cat: 'seed', weight: 0.02, stack: 50, crop: 'tomato', desc: 'Warm seasons only.' },
  { id: 'seeds_cabbage', name: 'Cabbage Seeds', icon: '🌱', cat: 'seed', weight: 0.02, stack: 50, crop: 'cabbage', desc: 'Frost hardy.' },
  { id: 'seeds_pumpkin', name: 'Pumpkin Seeds', icon: '🌱', cat: 'seed', weight: 0.02, stack: 50, crop: 'pumpkin', desc: 'Slow but rewarding.' },
  { id: 'seeds_hemp', name: 'Hemp Seeds', icon: '🌱', cat: 'seed', weight: 0.02, stack: 50, crop: 'hemp', desc: 'Fibre crop.' },
  // ---- weapons, tools, gear
  { id: 'spear', name: 'Spear', icon: '🔱', cat: 'weapon', weight: 1.4, stack: 1, desc: 'Long reach. Keep them at bay.', weapon: { kind: 'blade', damage: 38, range: 2.8, speed: 1.2, stamina: 6, knock: 1.6, durability: 110, model: 'pipe', noise: 3, twoHanded: true } },
  { id: 'crossbow', name: 'Crossbow', icon: '🏹', cat: 'weapon', weight: 3, stack: 1, desc: 'Silent and deadly. Bolts can be recovered.', weapon: { kind: 'firearm', damage: 120, range: 80, speed: 0.7, stamina: 0, knock: 1.5, durability: 400, model: 'rifle', noise: 6, ammo: 'bolt', mag: 1, spread: 0.006, reload: 2.2, recoil: 0.04, twoHanded: true } },
  { id: 'bolt', name: 'Crossbow Bolt', icon: '➶', cat: 'ammo', weight: 0.05, stack: 60, desc: 'Reusable — pick them up from corpses.' },
  { id: 'molotov', name: 'Molotov Cocktail', icon: '🍾', cat: 'weapon', weight: 0.6, stack: 5, desc: 'Throw (left click) to set an area ablaze.', weapon: { kind: 'blunt', damage: 0, range: 0, speed: 1, stamina: 2, knock: 0, durability: 1, model: 'generic', noise: 20 } },
  { id: 'pipe_bomb', name: 'Pipe Bomb', icon: '💣', cat: 'weapon', weight: 0.8, stack: 5, desc: 'Throw (left click). Huge blast. Huge noise.', weapon: { kind: 'blunt', damage: 0, range: 0, speed: 1, stamina: 2, knock: 0, durability: 1, model: 'generic', noise: 140 } },
  { id: 'steel_axe', name: 'Steel Axe', icon: '🪓', cat: 'weapon', weight: 2.2, stack: 1, tools: ['axe'], desc: 'Fells trees in half the time.', weapon: { kind: 'blade', damage: 66, range: 2.1, speed: 0.95, stamina: 10, knock: 1.5, durability: 600, model: 'axe', noise: 5, twoHanded: true, tree: 2 } },
  { id: 'steel_pickaxe', name: 'Steel Pickaxe', icon: '⛏️', cat: 'tool', weight: 2.6, stack: 1, tools: ['pickaxe'], desc: 'Mines twice as much ore.', weapon: { kind: 'blade', damage: 46, range: 2.0, speed: 0.9, stamina: 10, knock: 1.3, durability: 700, model: 'pickaxe', noise: 6, twoHanded: true, mine: 2 } },
  { id: 'water_filter', name: 'Water Filter', icon: '🧪', cat: 'tool', weight: 0.3, stack: 1, tools: ['filter'], desc: 'Carry it: lake and dirty water become safe to drink.' },
  { id: 'winter_coat', name: 'Winter Coat', icon: '🧥', cat: 'clothing', weight: 1.8, stack: 1, cloth: { slot: 'torso', insul: 0.9, bite: 0.25, scratch: 0.5, rain: 0.6 }, desc: 'Survive the winter.' },
  { id: 'scrap_armor', name: 'Scrap Armour', icon: '🛡️', cat: 'clothing', weight: 6, stack: 1, cloth: { slot: 'torso', insul: 0.2, bite: 0.8, scratch: 0.9 }, desc: 'Sheet metal and leather. Heavy, but bites rarely get through.' },
  { id: 'leather_boots', name: 'Leather Boots', icon: '🥾', cat: 'clothing', weight: 1, stack: 1, cloth: { slot: 'feet', insul: 0.3, bite: 0.5, scratch: 0.6 }, desc: 'Sturdy handmade boots.' },
  { id: 'leather_gloves', name: 'Leather Gloves', icon: '🧤', cat: 'clothing', weight: 0.3, stack: 1, cloth: { slot: 'hands', insul: 0.25, bite: 0.35, scratch: 0.6 }, desc: 'Protect your hands.' },
  { id: 'fur_hat', name: 'Fur Hat', icon: '🎩', cat: 'clothing', weight: 0.3, stack: 1, cloth: { slot: 'head', insul: 0.5, bite: 0.05, scratch: 0.15 }, desc: 'Warm.' },
  { id: 'survival_pack', name: 'Survival Pack', icon: '🎒', cat: 'clothing', weight: 1.2, stack: 1, cloth: { slot: 'back', insul: 0.05, bite: 0, scratch: 0, capacity: 38 }, desc: '+38 kg carry capacity.' },
];
for (const d of items) ITEMS[d.id] = d;

const recipes: Recipe[] = [
  // hand
  { id: 'cloth_hemp', out: ['cloth', 1], inp: { hemp: 3 }, station: 'hand', time: 4 },
  { id: 'rope_hemp', out: ['rope', 2], inp: { hemp: 3 }, station: 'hand', time: 3 },
  { id: 'rags_cloth', out: ['rag', 2], inp: { cloth: 1 }, station: 'hand', time: 2 },
  { id: 'poultice', out: ['poultice', 1], inp: { herbs: 3, rag: 1 }, station: 'hand', time: 4, xp: ['firstaid', 4] },
  { id: 'spear', out: ['spear', 1], inp: { plank: 2, knife: 1, duct_tape: 1 }, station: 'hand', time: 6, xp: ['blade', 4] },
  { id: 'molotov', out: ['molotov', 1], inp: { empty_bottle: 1, gas_can: 1, rag: 1 }, station: 'hand', time: 4 },
  // workbench
  { id: 'tan_leather', out: ['leather', 2], inp: { hide: 1 }, tools: ['knife'], station: 'workbench', time: 10, xp: ['carpentry', 3] },
  { id: 'crossbow', out: ['crossbow', 1], inp: { plank: 4, rope: 2, iron_rod: 2, screws: 6 }, tools: ['screwdriver'], station: 'workbench', time: 18, skill: ['engineering', 2], xp: ['engineering', 14] },
  { id: 'bolts', out: ['bolt', 8], inp: { iron_rod: 2, plank: 1 }, tools: ['knife'], station: 'workbench', time: 6, xp: ['firearms', 3] },
  { id: 'bolts_scrap', out: ['bolt', 4], inp: { scrap_metal: 1, plank: 1 }, tools: ['knife'], station: 'workbench', time: 6 },
  { id: 'pipe_bomb', out: ['pipe_bomb', 1], inp: { pipe: 1, gunpowder: 3, duct_tape: 1 }, station: 'workbench', time: 10, skill: ['engineering', 3], xp: ['engineering', 10], tier: 3 },
  { id: 'steel_axe', out: ['steel_axe', 1], inp: { steel_ingot: 4, plank: 2, leather: 1 }, tools: ['hammer'], station: 'workbench', time: 14, skill: ['engineering', 2], xp: ['engineering', 10] },
  { id: 'steel_pickaxe', out: ['steel_pickaxe', 1], inp: { steel_ingot: 5, plank: 2, leather: 1 }, tools: ['hammer'], station: 'workbench', time: 14, skill: ['engineering', 2], xp: ['engineering', 10] },
  { id: 'water_filter', out: ['water_filter', 1], inp: { charcoal: 3, cloth: 2, empty_bottle: 1, sand: 2 }, station: 'workbench', time: 8, xp: ['engineering', 4] },
  { id: 'winter_coat', out: ['winter_coat', 1], inp: { cloth: 4, leather: 3, rope: 1 }, station: 'workbench', time: 16, xp: ['carpentry', 6] },
  { id: 'scrap_armor', out: ['scrap_armor', 1], inp: { sheet_metal: 3, leather: 2, duct_tape: 2 }, tools: ['hammer'], station: 'workbench', time: 18, skill: ['engineering', 2], xp: ['engineering', 10] },
  { id: 'leather_jacket_c', out: ['leather_jacket', 1], inp: { leather: 4, cloth: 1 }, station: 'workbench', time: 12, xp: ['carpentry', 5] },
  { id: 'leather_boots', out: ['leather_boots', 1], inp: { leather: 2, rope: 1 }, station: 'workbench', time: 8 },
  { id: 'leather_gloves', out: ['leather_gloves', 1], inp: { leather: 1, cloth: 1 }, station: 'workbench', time: 6 },
  { id: 'fur_hat', out: ['fur_hat', 1], inp: { hide: 1, cloth: 1 }, tools: ['knife'], station: 'workbench', time: 6 },
  { id: 'survival_pack', out: ['survival_pack', 1], inp: { leather: 3, cloth: 4, rope: 2 }, station: 'workbench', time: 16, xp: ['carpentry', 8] },
  { id: 'flour_quern', out: ['flour', 1], inp: { wheat: 4 }, station: 'workbench', time: 6 },
  { id: 'jar_wb', out: ['jar', 2], inp: { glass: 1 }, station: 'workbench', time: 4 },
  { id: 'sand_wb', out: ['sand', 2], inp: { stone: 2 }, tools: ['hammer'], station: 'workbench', time: 5 },
  { id: 'fertilizer_wb', out: ['fertilizer', 2], inp: { rotten_food: 3, leaves: 4 }, station: 'workbench', time: 6, xp: ['farming', 3] },
  { id: 'seeds_from_wheat', out: ['seeds_wheat', 4], inp: { wheat: 2 }, station: 'workbench', time: 3 },
  // campfire
  { id: 'charcoal', out: ['charcoal', 3], inp: { log: 1 }, station: 'campfire', time: 12 },
  { id: 'jerky', out: ['jerky', 2], inp: { raw_meat: 1 }, station: 'campfire', time: 20, xp: ['cooking', 4] },
  { id: 'mushroom_skewer', out: ['mushroom_skewer', 1], inp: { mushroom: 3 }, station: 'campfire', time: 6, xp: ['cooking', 3] },
  { id: 'fried_egg', out: ['omelette', 1], inp: { egg: 2 }, station: 'campfire', time: 5, xp: ['cooking', 3] },
  { id: 'herbal_tea', out: ['herbal_tea', 1], inp: { herbs: 2, water_bottle: 1 }, station: 'campfire', time: 5, xp: ['cooking', 2] },
  // stove (needs fuel like a campfire; indoors, bigger meals)
  { id: 'bread_fresh', out: ['bread_fresh', 2], inp: { flour: 2, water_bottle: 1 }, station: 'stove', time: 10, xp: ['cooking', 5] },
  { id: 'stew', out: ['stew', 2], inp: { raw_meat: 1, potato: 1, carrot: 1, water_bottle: 1 }, station: 'stove', time: 14, xp: ['cooking', 8] },
  { id: 'veg_soup', out: ['veg_soup', 2], inp: { cabbage: 1, tomato: 1, potato: 1, water_bottle: 1 }, station: 'stove', time: 12, xp: ['cooking', 6] },
  { id: 'fish_stew', out: ['fish_stew', 2], inp: { raw_fish: 1, potato: 1, water_bottle: 1 }, station: 'stove', time: 12, xp: ['cooking', 6] },
  { id: 'omelette', out: ['omelette', 2], inp: { egg: 3, mushroom: 1 }, station: 'stove', time: 6, xp: ['cooking', 4] },
  { id: 'pumpkin_pie', out: ['pumpkin_pie', 2], inp: { pumpkin: 1, flour: 2, egg: 1 }, station: 'stove', time: 16, skill: ['cooking', 2], xp: ['cooking', 10] },
  { id: 'berry_jam', out: ['berry_jam', 1], inp: { berries: 5, jar: 1 }, station: 'stove', time: 10, xp: ['cooking', 5] },
  { id: 'preserve_veg', out: ['preserved_veg', 1], inp: { carrot: 2, cabbage: 1, jar: 1 }, station: 'stove', time: 10, xp: ['cooking', 5] },
  { id: 'preserve_tomato', out: ['preserved_veg', 1], inp: { tomato: 3, jar: 1 }, station: 'stove', time: 10, xp: ['cooking', 5] },
  { id: 'cook_meat_stove', out: ['cooked_meat', 2], inp: { raw_meat: 2 }, station: 'stove', time: 8, xp: ['cooking', 3] },
  { id: 'boil_water_stove', out: ['water_bottle', 2], inp: { dirty_water: 2 }, station: 'stove', time: 6 },
];
RECIPES.push(...recipes);

const mrecipes: MRecipe[] = [
  { id: 'smelt_gold', machine: ['smelter'], inp: { gold_ore: 2 }, out: ['gold_ingot', 1], time: 4 },
  { id: 'smelt_glass', machine: ['smelter'], inp: { sand: 3 }, out: ['glass', 1], time: 4 },
  { id: 'smelt_glass_silica', machine: ['smelter'], inp: { silica: 2 }, out: ['glass', 1], time: 3 },
  { id: 'aluminium_ingot', machine: ['foundry'], inp: { alumina: 3, coal: 1 }, out: ['aluminium_ingot', 2], time: 4 },
  { id: 'silica', machine: ['constructor'], inp: { quartz: 3 }, out: ['silica', 5], time: 6 },
  { id: 'sand_c', machine: ['constructor'], inp: { stone: 2 }, out: ['sand', 3], time: 3 },
  { id: 'steel_beam', machine: ['constructor'], inp: { steel_ingot: 4 }, out: ['steel_beam', 1], time: 4 },
  { id: 'steel_pipe', machine: ['constructor'], inp: { steel_ingot: 3 }, out: ['steel_pipe', 2], time: 6 },
  { id: 'aluminium_sheet', machine: ['constructor'], inp: { aluminium_ingot: 3 }, out: ['aluminium_sheet', 2], time: 6 },
  { id: 'rail_c', machine: ['constructor'], inp: { steel_ingot: 2 }, out: ['rail', 3], time: 4 },
  { id: 'flour_c', machine: ['constructor'], inp: { wheat: 4 }, out: ['flour', 2], time: 4 },
  { id: 'jar_c', machine: ['constructor'], inp: { glass: 1 }, out: ['jar', 2], time: 3 },
  { id: 'cloth_c', machine: ['constructor'], inp: { hemp: 3 }, out: ['cloth', 1], time: 3 },
  { id: 'stator', machine: ['assembler'], inp: { steel_pipe: 3, wire: 8 }, out: ['stator', 1], time: 12 },
  { id: 'motor', machine: ['assembler'], inp: { rotor: 2, stator: 2 }, out: ['motor', 1], time: 12 },
  { id: 'computer', machine: ['assembler'], inp: { circuit: 3, plastic: 6 }, out: ['computer', 1], time: 16 },
  { id: 'heavy_frame', machine: ['assembler'], inp: { modular_frame: 2, steel_beam: 4 }, out: ['heavy_frame', 1], time: 20 },
  { id: 'battery_cell', machine: ['assembler'], inp: { aluminium_sheet: 2, gold_ingot: 1 }, out: ['battery_cell', 2], time: 8 },
  { id: 'circuit_gold', machine: ['assembler'], inp: { gold_ingot: 1, plastic: 2 }, out: ['circuit', 2], time: 8 },
  { id: 'fertilizer_a', machine: ['assembler'], inp: { leaves: 6, sulfur: 1 }, out: ['fertilizer', 4], time: 6 },
  { id: 'alumina', machine: ['refinery'], inp: { bauxite: 3 }, fluidIn: ['f_water', 2], out: ['alumina', 3], time: 4 },
  { id: 'grow_wheat', machine: ['greenhouse'], inp: {}, out: ['wheat', 3], time: 30 },
  { id: 'grow_tomato', machine: ['greenhouse'], inp: {}, out: ['tomato', 2], time: 30 },
  { id: 'grow_cabbage', machine: ['greenhouse'], inp: {}, out: ['cabbage', 2], time: 30 },
  { id: 'grow_pumpkin', machine: ['greenhouse'], inp: {}, out: ['pumpkin', 1], time: 30 },
  { id: 'grow_hemp', machine: ['greenhouse'], inp: {}, out: ['hemp', 3], time: 30 },
];
MRECIPES.push(...mrecipes);

// ---- new survival stations
Object.assign(PIECES, {
  stove: { id: 'stove', name: 'Wood Stove', icon: '♨️', desc: 'Cook proper meals: breads, stews, pies, preserves. Burns wood like a campfire, safe indoors.', kind: 'station', prop: 'stove', cost: { sheet_metal: 2, scrap_metal: 6, stone: 4 }, tier: 0, hp: 300, station: 'stove' },
  compost_bin: { id: 'compost_bin', name: 'Compost Bin', icon: '🗑️', desc: 'Turns rotten food, biomass and leaves into fertilizer over a day.', kind: 'station', prop: 'barrel', cost: { plank: 6, nails: 6 }, tier: 0, hp: 150, station: 'compost' },
  chicken_coop: { id: 'chicken_coop', name: 'Chicken Coop', icon: '🐔', desc: 'Keep it stocked with grain or seeds and it lays eggs daily.', kind: 'station', prop: 'wood_crate_storage', cost: { plank: 10, nails: 12, rope: 2 }, tier: 0, hp: 200, station: 'coop' },
  drying_rack: { id: 'drying_rack', name: 'Smoking Rack', icon: '🥩', desc: 'Hang raw meat to make jerky slowly, no fire needed.', kind: 'station', prop: 'tool_rack', cost: { plank: 6, rope: 2 }, tier: 0, hp: 120, station: 'rack' },
});

// ---- new machines
Object.assign(MACHINES, {
  sprinkler: { type: 'sprinkler', name: 'Sprinkler', icon: '💦', desc: 'Connect to a water pipe network: keeps farm plots within 7 m watered.', size: [1, 1], cost: { steel_pipe: 2, iron_plate: 2 }, power: 0, tier: 4, inputs: [], outputs: [], cat: 'production', hp: 200, heat: 0, height: 1.2 },
  rail: { type: 'rail', name: 'Railway Track', icon: '🛤️', desc: 'Drag to lay track. Turns curve automatically. Junctions let trains choose routes.', size: [1, 1], cost: { rail: 1, plank: 1 }, power: 0, tier: 5, inputs: [], outputs: [], cat: 'transport', hp: 300, heat: 0, height: 0.3 },
  train_station: { type: 'train_station', name: 'Freight Station', icon: '🚉', desc: 'Place beside track. Belt items in at the back, out the front. Set it to Load or Unload; trains stop here on their schedule and refuel from coal stored inside.', size: [2, 2], cost: { modular_frame: 2, steel_beam: 6, concrete: 20 }, power: 0, tier: 5, inputs: [0], outputs: [0], cat: 'transport', hp: 1200, heat: 1, height: 4 },
  locomotive: { type: 'locomotive', name: 'Locomotive', icon: '🚂', desc: 'Place on track. Burns coal. Give it a schedule of stations and it drives itself; wagons placed behind it couple automatically.', size: [1, 1], cost: { motor: 2, modular_frame: 3, steel_beam: 6 }, power: 0, tier: 5, inputs: [], outputs: [], cat: 'transport', hp: 1500, heat: 3, height: 3 },
  freight_wagon: { type: 'freight_wagon', name: 'Freight Wagon', icon: '🚃', desc: 'Carries 600 kg. Place on track behind a locomotive.', size: [1, 1], cost: { steel_beam: 4, iron_plate: 20 }, power: 0, tier: 5, inputs: [], outputs: [], cat: 'transport', hp: 1000, heat: 0, height: 2.5 },
});

// ---- town buildings: homes, food, water, jobs
Object.assign(MACHINES, {
  shelter_house: { type: 'shelter_house', name: 'Shelter House', icon: '🏠', desc: 'A patched-together home for 4 residents. People without a bed are miserable — and nobody new moves in without one.', size: [2, 2], cost: { plank: 16, nails: 24, scrap_metal: 4 }, power: 0, tier: 0, inputs: [], outputs: [], cat: 'town', hp: 700, heat: 0, height: 4 },
  guard_post: { type: 'guard_post', name: 'Guard Post', icon: '🛡️', desc: 'A sandbagged lookout. A resident guard stands watch and shoots zombies — keep 9mm or rifle ammo inside.', size: [1, 1], cost: { plank: 8, nails: 10, rag: 4 }, power: 0, tier: 0, inputs: [], outputs: [], cat: 'town', hp: 600, heat: 0, height: 3 },
  mess_hall: { type: 'mess_hall', name: 'Mess Hall', icon: '🍲', desc: 'A cook turns the ingredients stored here into hot meals (any stove recipe). Residents eat here each evening — hot food keeps them happy. Belt food in at the back.', size: [3, 3], cost: { plank: 24, nails: 30, stone: 12, scrap_metal: 6 }, power: 0, tier: 1, inputs: [1], outputs: [], cat: 'town', hp: 900, heat: 1, height: 4 },
  water_tower: { type: 'water_tower', name: 'Water Tower', icon: '🗼', desc: 'Holds 400 L for the town and its farm plots. Fills from rain, or from a water pump through pipes. Each resident drinks 4 L a day.', size: [2, 2], cost: { plank: 20, nails: 20, scrap_metal: 10, rope: 2 }, power: 0, tier: 1, inputs: [], outputs: [], cat: 'town', hp: 800, heat: 0, height: 7 },
  lumber_yard: { type: 'lumber_yard', name: 'Lumber Yard', icon: '🪓', desc: 'Two lumberjacks fell trees and saw planks. Logs and planks pile up inside.', size: [3, 3], cost: { plank: 10, nails: 12, scrap_metal: 6 }, power: 0, tier: 1, inputs: [], outputs: [], cat: 'town', hp: 700, heat: 1, height: 4 },
  workshop: { type: 'workshop', name: 'Repair Shop', icon: '🔧', desc: 'A mechanic patches up damaged walls, doors and machines around town.', size: [3, 3], cost: { iron_plate: 10, plank: 12, scrap_metal: 10 }, power: 0, tier: 2, inputs: [], outputs: [], cat: 'town', hp: 900, heat: 1, height: 4 },
  bunkhouse: { type: 'bunkhouse', name: 'Bunkhouse', icon: '🏢', desc: 'A two-storey dormitory for 10 residents.', size: [3, 3], cost: { concrete: 30, iron_plate: 16, plank: 20, glass: 6 }, power: 0, tier: 3, inputs: [], outputs: [], cat: 'town', hp: 1600, heat: 0, height: 7 },
});

// ---- our own names: salvaged, hand-built, post-collapse
const rename = (id: string, name: string, desc?: string, icon?: string) => { const m = MACHINES[id]; if (m) { m.name = name; if (desc) m.desc = desc; if (icon) m.icon = icon; } };
MACHINES.hub.cat = 'town';
rename('hub', 'Settlement Hall', 'The heart of your town. Residents gather here and it sleeps two. Deliver supplies to grow the settlement — each new level unlocks new buildings.', '🔔');
rename('miner', 'Ore Drill', 'Bores into an ore seam. 30 ore/min on a normal seam, more on a rich one.', '⛏️');
rename('miner2', 'Deep Drill', 'Heavy drill: 60 ore/min on a normal seam.');
rename('smelter', 'Furnace', 'Melts ore into ingots and sand into glass.');
rename('foundry', 'Steel Mill', 'Alloys iron with coal into steel; smelts alumina into aluminium.');
rename('constructor', 'Machine Shop', 'Lathes and presses: plates, rods, bolts, wire, concrete, nails, beams.', '🛠️');
rename('assembler', 'Fabrication Shop', 'Joins two parts: riveted plates, rotors, circuits, motors, gunpowder, ammunition.', '🏗️');
rename('biomass', 'Wood Boiler', '10 MW steam from logs, planks or leaves. Feed it by hand.', '🪵');
rename('coal_gen', 'Coal Steam Engine', '50 MW. Burns coal from a belt or by hand. Loud and smoky.');
rename('belt', 'Roller Conveyor', '60 items/min. Click and drag to lay a line.');
rename('belt2', 'Powered Conveyor', '120 items/min.');
rename('lab', 'Clinic', 'A medic heals residents here and turns rags into bandages. Brews antibiotics, and from infected tissue, the serum that stops a bite turning you.', '🏥');
rename('radio_tower', 'Radio Tower', 'Broadcasts to survivors across the valley: more newcomers find your town, and a supply drop lands each noon. 20 MW.');
rename('greenhouse', 'Greenhouse', 'Grows vegetables year-round under glass. Food security.');
rename('storage', 'Storage Shed', 'Holds 200 kg. Residents eat from it. Belt in at the back, out at the front.', '📦');
const renameItem = (id: string, name: string, desc?: string) => { const d = ITEMS[id]; if (d) { d.name = name; if (desc) d.desc = desc; } };
renameItem('screws', 'Bolts', 'Nuts and bolts. You can never have enough.');
renameItem('reinforced_plate', 'Riveted Plate', 'Two plates riveted together. Tough.');
renameItem('modular_frame', 'Steel Frame', 'Welded box frame for big builds.');
renameItem('heavy_frame', 'Girder', 'Massive load-bearing steel girder.');
renameItem('stator', 'Copper Coil', 'Wire wound on a steel core. Half of a motor.');
renameItem('motor', 'Electric Motor', 'Coil and rotor. Drives pumps, locomotives and big machines.');
renameItem('computer', 'Control Panel', 'Circuits and switches in a box. Runs the grid.');
renameItem('serum', 'Antiviral Serum', 'Stops and reverses a zombie bite. Brewed at the Clinic from infected tissue.');

// ---- settlement levels: grow from a camp to a city. Each needs supplies delivered to the hall AND a living town.
MILESTONES.splice(0, MILESTONES.length,
  { id: 'm1', name: 'Outpost', desc: 'Get people under a roof and the first fires burning. Recruit a survivor with food and give them a bed.', req: { residents: 1, beds: 4 }, cost: { plank: 30, nails: 60, scrap_metal: 20, electronics: 6 }, unlocks: ['biomass', 'power_pole', 'miner', 'smelter', 'belt', 'storage', 'floodlight', 'mess_hall', 'water_tower', 'lumber_yard'] },
  { id: 'm2', name: 'Settlement', desc: 'Feed people properly and smelt your own metal. Newcomers arrive each morning when there are free beds and food in store.', req: { residents: 3, beds: 6, food: 20 }, cost: { iron_ingot: 60, copper_ingot: 30, sheet_metal: 6 }, unlocks: ['constructor', 'coal_gen', 'splitter', 'merger', 'electric_fence', 'metal_foundation', 'metal_wall', 'metal_wall_door', 'gate', 'watchtower', 'workshop', 'lab'] },
  { id: 'm3', name: 'Village', desc: 'Defend what you have built. Guards, turrets and walls let people sleep at night.', req: { residents: 6, defence: 4, happiness: 50 }, cost: { iron_plate: 80, screws: 150, wire: 80, cable: 30 }, unlocks: ['assembler', 'turret', 'solar', 'foundry', 'battery', 'concrete_wall', 'bunkhouse'] },
  { id: 'm4', name: 'Town', desc: 'Running water, greenhouses and power for everyone. Reach out by radio to bring more people in.', req: { residents: 10, power: 40, happiness: 55 }, cost: { reinforced_plate: 30, rotor: 20, circuit: 20, concrete: 60 }, unlocks: ['belt2', 'miner2', 'wind_turbine', 'greenhouse', 'radio_tower', 'water_pump', 'pipe', 'fluid_tank', 'packager', 'sprinkler'] },
  { id: 'm5', name: 'Railhead', desc: 'Steel, glass and motors. Lay track out to the far mines and farms and run freight home.', req: { residents: 14, food: 60 }, cost: { steel_beam: 40, motor: 8, modular_frame: 12, glass: 30 }, unlocks: ['rail', 'train_station', 'locomotive', 'freight_wagon'] },
  { id: 'm6', name: 'Lights On', desc: 'Fuel, plastics and the old town grid. Bring the street lights back.', req: { residents: 17, happiness: 60, defence: 8 }, cost: { heavy_frame: 6, steel_pipe: 60, circuit: 50, radio_parts: 4 }, unlocks: ['uplink', 'oil_pump', 'refinery', 'fuel_gen'] },
  { id: 'm7', name: 'City', desc: 'Twenty souls, a lit grid, full stores and walls that hold. The valley has a city again — now keep it alive.', req: { residents: 20, grid: true, happiness: 65, defence: 10 }, cost: { concrete: 200, glass: 60, steel_beam: 60, cloth: 40 }, unlocks: [] },
);

// ---- crops
export interface CropDef { id: string; days: number; seasons: number[]; yield: [number, number]; color: number; seedBack: number }
// seasons: 0 autumn, 1 winter, 2 spring, 3 summer
export const CROPS: Record<string, CropDef> = {
  carrot: { id: 'carrot', days: 3, seasons: [0, 2, 3], yield: [3, 6], color: 0xe07020, seedBack: 0.6 },
  potato: { id: 'potato', days: 4, seasons: [0, 2, 3], yield: [4, 7], color: 0xb09060, seedBack: 0.7 },
  corn: { id: 'corn', days: 5, seasons: [2, 3], yield: [3, 5], color: 0xe0c040, seedBack: 0.6 },
  wheat: { id: 'wheat', days: 4, seasons: [0, 2, 3], yield: [6, 10], color: 0xd8b860, seedBack: 0.8 },
  tomato: { id: 'tomato', days: 4, seasons: [2, 3], yield: [4, 8], color: 0xd83020, seedBack: 0.6 },
  cabbage: { id: 'cabbage', days: 4, seasons: [0, 1, 2], yield: [2, 4], color: 0x7aa040, seedBack: 0.6 },
  pumpkin: { id: 'pumpkin', days: 7, seasons: [0, 3], yield: [2, 3], color: 0xe07010, seedBack: 0.7 },
  hemp: { id: 'hemp', days: 3, seasons: [2, 3, 0], yield: [5, 9], color: 0x4a7a30, seedBack: 0.7 },
};
