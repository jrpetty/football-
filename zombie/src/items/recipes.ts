import type { SkillId } from '../player/skills';

export type Station = 'hand' | 'workbench' | 'campfire';
export interface Recipe {
  id: string; out: [string, number]; inp: Record<string, number>; tools?: string[]; station: Station;
  time: number; skill?: [SkillId, number]; xp?: [SkillId, number]; tier?: number; keep?: string[];
}

export const RECIPES: Recipe[] = [
  // ---- hand ----
  { id: 'rip_rags', out: ['rag', 3], inp: { hoodie: 1 }, station: 'hand', time: 3 },
  { id: 'rip_rags_jeans', out: ['rag', 3], inp: { jeans: 1 }, station: 'hand', time: 3 },
  { id: 'bandage', out: ['bandage', 2], inp: { rag: 2, disinfectant: 1 }, station: 'hand', time: 4, xp: ['firstaid', 6] },
  { id: 'splint', out: ['splint', 1], inp: { plank: 1, rag: 2 }, station: 'hand', time: 4, xp: ['firstaid', 5] },
  { id: 'plank_axe', out: ['plank', 2], inp: { log: 1 }, tools: ['axe'], station: 'hand', time: 4, xp: ['carpentry', 3] },
  { id: 'plank_saw', out: ['plank', 4], inp: { log: 1 }, tools: ['saw'], station: 'hand', time: 5, xp: ['carpentry', 4] },
  { id: 'bat_nails', out: ['bat_nails', 1], inp: { bat: 1, nails: 6 }, tools: ['hammer'], station: 'hand', time: 5, xp: ['carpentry', 6] },
  { id: 'biomass', out: ['leaves', 5], inp: { plank: 1 }, station: 'hand', time: 2 },
  // ---- workbench ----
  { id: 'nails', out: ['nails', 12], inp: { scrap_metal: 2 }, tools: ['hammer'], station: 'workbench', time: 4, xp: ['engineering', 3] },
  { id: 'sheet_metal', out: ['sheet_metal', 1], inp: { scrap_metal: 4 }, tools: ['hammer'], station: 'workbench', time: 6, skill: ['engineering', 1], xp: ['engineering', 6] },
  { id: 'pipe_wb', out: ['pipe', 1], inp: { scrap_metal: 4 }, tools: ['hammer'], station: 'workbench', time: 6, xp: ['engineering', 5] },
  { id: 'machete_wb', out: ['machete', 1], inp: { sheet_metal: 1, duct_tape: 1, plank: 1 }, tools: ['hammer'], station: 'workbench', time: 10, skill: ['engineering', 2], xp: ['engineering', 10] },
  { id: 'pickaxe_wb', out: ['pickaxe', 1], inp: { scrap_metal: 5, plank: 2, rope: 1 }, tools: ['hammer'], station: 'workbench', time: 10, xp: ['engineering', 8] },
  { id: 'axe_wb', out: ['axe', 1], inp: { sheet_metal: 1, plank: 2, rope: 1 }, tools: ['hammer'], station: 'workbench', time: 10, skill: ['engineering', 1], xp: ['engineering', 8] },
  { id: 'hammer_wb', out: ['hammer', 1], inp: { scrap_metal: 2, plank: 1 }, station: 'workbench', time: 6, xp: ['engineering', 4] },
  { id: 'wrench_wb', out: ['wrench', 1], inp: { scrap_metal: 3 }, tools: ['hammer'], station: 'workbench', time: 6, xp: ['engineering', 4] },
  { id: 'fishing_rod', out: ['fishing_rod', 1], inp: { plank: 1, rope: 1, nails: 1 }, station: 'workbench', time: 6 },
  { id: 'rope', out: ['rope', 1], inp: { rag: 3 }, station: 'workbench', time: 4 },
  { id: 'sledge_wb', out: ['sledgehammer', 1], inp: { sheet_metal: 2, plank: 2, duct_tape: 1 }, tools: ['hammer'], station: 'workbench', time: 12, skill: ['engineering', 3], xp: ['engineering', 12] },
  { id: 'ammo_9mm', out: ['ammo_9mm', 15], inp: { gunpowder: 2, scrap_metal: 1 }, tools: ['screwdriver'], station: 'workbench', time: 8, skill: ['firearms', 2], xp: ['firearms', 6], tier: 3 },
  { id: 'ammo_shells', out: ['ammo_shells', 6], inp: { gunpowder: 3, scrap_metal: 1, plank: 1 }, tools: ['screwdriver'], station: 'workbench', time: 8, skill: ['firearms', 3], xp: ['firearms', 6], tier: 3 },
  { id: 'ammo_rifle', out: ['ammo_rifle', 6], inp: { gunpowder: 3, iron_rod: 1 }, tools: ['screwdriver'], station: 'workbench', time: 8, skill: ['firearms', 4], xp: ['firearms', 8], tier: 3 },
  { id: 'iron_plate_wb', out: ['iron_plate', 1], inp: { iron_ingot: 3 }, tools: ['hammer'], station: 'workbench', time: 6, xp: ['engineering', 2] },
  { id: 'iron_rod_wb', out: ['iron_rod', 1], inp: { iron_ingot: 1 }, tools: ['hammer'], station: 'workbench', time: 4, xp: ['engineering', 1] },
  { id: 'screws_wb', out: ['screws', 4], inp: { iron_rod: 1 }, tools: ['screwdriver'], station: 'workbench', time: 4, xp: ['engineering', 1] },
  { id: 'wire_wb', out: ['wire', 2], inp: { copper_ingot: 1 }, tools: ['screwdriver'], station: 'workbench', time: 4, xp: ['engineering', 1] },
  { id: 'cable_wb', out: ['cable', 1], inp: { wire: 2, rag: 1 }, station: 'workbench', time: 4, xp: ['engineering', 1] },
  { id: 'circuit_wb', out: ['circuit', 1], inp: { electronics: 4, wire: 4 }, tools: ['screwdriver'], station: 'workbench', time: 10, skill: ['engineering', 3], xp: ['engineering', 10] },
  { id: 'radio_parts', out: ['radio_parts', 1], inp: { electronics: 6, wire: 6 }, tools: ['screwdriver'], station: 'workbench', time: 12, skill: ['engineering', 2], xp: ['engineering', 10] },
  { id: 'suture_wb', out: ['suture_kit', 1], inp: { rag: 1, wire: 2, disinfectant: 1 }, station: 'workbench', time: 6, skill: ['firstaid', 2], xp: ['firstaid', 8] },
  { id: 'helmet_wb', out: ['helmet', 1], inp: { sheet_metal: 2, rag: 2, duct_tape: 1 }, tools: ['hammer'], station: 'workbench', time: 12, skill: ['engineering', 2], xp: ['engineering', 10] },
  { id: 'vest_wb', out: ['police_vest', 1], inp: { reinforced_plate: 1, rag: 4, duct_tape: 2 }, station: 'workbench', time: 14, skill: ['engineering', 3], xp: ['engineering', 12], tier: 3 },
  // ---- campfire ----
  { id: 'cook_meat', out: ['cooked_meat', 1], inp: { raw_meat: 1 }, station: 'campfire', time: 6 },
  { id: 'bake_potato', out: ['baked_potato', 1], inp: { potato: 1 }, station: 'campfire', time: 6 },
  { id: 'cook_fish', out: ['cooked_fish', 1], inp: { raw_fish: 1 }, station: 'campfire', time: 6 },
  { id: 'boil_water', out: ['water_bottle', 1], inp: { dirty_water: 1 }, station: 'campfire', time: 6 },
  { id: 'sterilize_rag', out: ['bandage', 1], inp: { rag: 2, water_bottle: 1 }, station: 'campfire', time: 8, xp: ['firstaid', 4] },
];
