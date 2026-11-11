// High level world layout: town plateau, roads, lake, resource fields, spawn.
import { RNG } from '../core/util';

export interface Road { ax: number; az: number; bx: number; bz: number; w: number; kind: 'highway' | 'street' | 'dirt' }
export interface Plot { x: number; z: number; w: number; d: number; face: 0 | 1 | 2 | 3; kind: string } // face: 0:+z 1:+x 2:-z 3:-x (door side)
export interface OreField { type: 'iron_ore' | 'copper_ore' | 'coal' | 'stone' | 'sulfur' | 'f_crude' | 'quartz' | 'bauxite' | 'gold_ore'; x: number; z: number; purity: number }

export const TOWN = { x0: -150, x1: 150, z0: -120, z1: 120, h: 3 };
export const LAKE = { x: -235, z: 205, r: 62, level: -0.8 };
export const SPAWN = { x: -168, z: -196 };
export const CABIN = { x: -176, z: -206 };

export function buildLayout(seed: number) {
  const rng = new RNG(seed);
  const roads: Road[] = [];
  // highway crossing world east-west through town (main street)
  roads.push({ ax: -400, az: 0, bx: 400, bz: 0, w: 10, kind: 'highway' });
  // north-south avenues
  for (const x of [-120, -60, 0, 60, 120]) roads.push({ ax: x, az: -120, bx: x, bz: 120, w: 8, kind: 'street' });
  // cross streets
  for (const z of [-120, -60, 60, 120]) roads.push({ ax: -120, az: z, bx: 120, bz: z, w: 8, kind: 'street' });
  // road north out of town to quarry/hills
  roads.push({ ax: 120, az: 120, bx: 210, bz: 230, w: 8, kind: 'street' });
  roads.push({ ax: 210, az: 230, bx: 300, bz: 260, w: 7, kind: 'dirt' });
  // dirt road to the cabin / spawn
  roads.push({ ax: -120, az: -120, bx: -150, bz: -160, w: 5, kind: 'dirt' });
  roads.push({ ax: -150, az: -160, bx: -172, bz: -196, w: 5, kind: 'dirt' });
  // lake road
  roads.push({ ax: -120, az: 120, bx: -170, bz: 170, w: 5, kind: 'dirt' });
  // south road to farmland/industrial
  roads.push({ ax: 60, az: -120, bx: 90, bz: -230, w: 7, kind: 'street' });

  // building plots along the streets (blocks are 60 x 60 between avenues)
  const plots: Plot[] = [];
  const specials: string[] = ['supermarket', 'police', 'clinic', 'hardware', 'gas_station', 'warehouse', 'pharmacy', 'diner', 'warehouse', 'garage_shop', 'school', 'church'];
  rng.next();
  const blocks: [number, number][] = [];
  for (const bx of [-120, -60, 0, 60]) for (const bz of [-120, -60, 0, 60]) blocks.push([bx, bz]);
  // shuffle special assignment to blocks adjacent to main street (bz -60 or 0)
  let si = 0;
  for (const [bx, bz] of blocks) {
    const cx = bx + 30, cz = bz + 30;
    const mainBlock = bz === -60 || bz === 0;
    if (mainBlock && si < specials.length && rng.chance(0.85)) {
      const kind = specials[si++];
      // big building facing main street
      const faceZ = bz === -60 ? 0 : 2; // door toward z=0
      const big = kind === 'supermarket' || kind === 'warehouse' || kind === 'school' || kind === 'clinic';
      const w = big ? 32 : 22, d = big ? 22 : 16;
      const z = faceZ === 0 ? bz + 60 - 6 - d / 2 : bz + 6 + d / 2;
      plots.push({ x: cx, z, w, d, face: faceZ as 0 | 2, kind });
      // houses on back side of block
      const backZ = faceZ === 0 ? bz + 6 + 6 : bz + 60 - 6 - 6;
      for (const ox of [-14, 14]) if (rng.chance(0.8)) plots.push({ x: cx + ox, z: backZ, w: rng.pick([10, 12]), d: 9, face: faceZ === 0 ? 2 : 0, kind: rng.chance(0.5) ? 'house' : 'house_l' });
    } else {
      // residential block: 2 houses each facing north & south street
      for (const side of [0, 2] as const) {
        for (const ox of [-15, 15]) {
          if (rng.chance(0.12)) continue;
          const w = rng.pick([10, 12, 14]), d = rng.pick([9, 10]);
          const z = side === 2 ? bz + 6 + d / 2 + 1 : bz + 60 - 6 - d / 2 - 1;
          plots.push({ x: cx + ox, z, w, d, face: side === 2 ? 2 : 0, kind: rng.chance(0.35) ? 'house_l' : 'house' });
        }
      }
    }
  }
  // outskirts: farmhouse + barn to south, ranger cabin at spawn, quarry office
  plots.push({ x: 110, z: -240, w: 12, d: 10, face: 3, kind: 'house' });
  plots.push({ x: 128, z: -262, w: 20, d: 16, face: 3, kind: 'barn' });
  plots.push({ x: CABIN.x, z: CABIN.z, w: 8, d: 7, face: 1, kind: 'cabin' });
  plots.push({ x: 300, z: 272, w: 14, d: 10, face: 2, kind: 'warehouse_small' });
  plots.push({ x: -180, z: 180, w: 9, d: 8, face: 1, kind: 'cabin' });

  // ore deposits — a starter cluster near the spawn cabin, richer fields further out
  const ores: OreField[] = [
    { type: 'iron_ore', x: -206, z: -180, purity: 1 },
    { type: 'iron_ore', x: -214, z: -214, purity: 1 },
    { type: 'stone', x: -150, z: -232, purity: 1 },
    { type: 'copper_ore', x: -232, z: -244, purity: 1 },
    { type: 'coal', x: -126, z: -258, purity: 1 },
    { type: 'iron_ore', x: 250, z: 220, purity: 2 },
    { type: 'iron_ore', x: 280, z: 300, purity: 2 },
    { type: 'copper_ore', x: 320, z: 230, purity: 2 },
    { type: 'coal', x: 330, z: 300, purity: 2 },
    { type: 'coal', x: 240, z: 330, purity: 1 },
    { type: 'stone', x: 270, z: 190, purity: 2 },
    { type: 'sulfur', x: -320, z: 300, purity: 1 },
    { type: 'sulfur', x: -330, z: -60, purity: 1 },
    { type: 'copper_ore', x: 200, z: -300, purity: 1 },
    { type: 'iron_ore', x: 330, z: -200, purity: 2 },
    { type: 'stone', x: -300, z: -300, purity: 1 },
    { type: 'f_crude', x: 150, z: -318, purity: 1 },
    { type: 'f_crude', x: -310, z: 130, purity: 1 },
    { type: 'f_crude', x: 318, z: 60, purity: 2 },
  ];
  // scatter many more deposits across the wilderness, flavoured by region
  const R = new RNG(seed + 1234);
  type T = OreField['type'];
  const regionTable = (x: number, z: number): [T, number][] => {
    if (x > 150) return [['iron_ore', 3], ['copper_ore', 2], ['coal', 2], ['gold_ore', 1.2], ['stone', 1], ['quartz', 1]];
    if (x < -150) return [['sulfur', 2], ['quartz', 2], ['stone', 2], ['iron_ore', 1], ['bauxite', 1], ['copper_ore', 1]];
    if (z < -150) return [['bauxite', 2], ['iron_ore', 2], ['copper_ore', 2], ['coal', 1], ['stone', 1]];
    if (z > 150) return [['coal', 2], ['iron_ore', 2], ['quartz', 1], ['gold_ore', 1], ['stone', 1], ['copper_ore', 1]];
    return [['iron_ore', 2], ['copper_ore', 2], ['stone', 2], ['coal', 1]];
  };
  let tries = 0;
  while (ores.length < 88 && tries++ < 3000) {
    const x = R.range(-345, 345), z = R.range(-345, 345);
    if (x > TOWN.x0 - 35 && x < TOWN.x1 + 35 && z > TOWN.z0 - 35 && z < TOWN.z1 + 35) continue;
    if (Math.hypot(x - LAKE.x, z - LAKE.z) < LAKE.r + 15) continue;
    if (Math.hypot(x - CABIN.x, z - CABIN.z) < 25) continue;
    if (plots.some(p => Math.abs(p.x - x) < p.w / 2 + 14 && Math.abs(p.z - z) < p.d / 2 + 14)) continue;
    if (roads.some(r => distToSeg(x, z, r) < r.w / 2 + 6)) continue;
    if (ores.some(o => Math.hypot(o.x - x, o.z - z) < 34)) continue;
    const type: T = R.chance(0.05) ? 'f_crude' : R.weighted(regionTable(x, z));
    const purity = R.weighted<number>([[0.5, 40], [1, 45], [2, 15]]);
    ores.push({ type, x, z, purity });
  }
  return { roads, plots, ores };
}
export type Layout = ReturnType<typeof buildLayout>;

export function distToSeg(px: number, pz: number, r: Road) {
  const dx = r.bx - r.ax, dz = r.bz - r.az; const L2 = dx * dx + dz * dz;
  let t = L2 > 0 ? ((px - r.ax) * dx + (pz - r.az) * dz) / L2 : 0; t = t < 0 ? 0 : t > 1 ? 1 : t;
  const x = r.ax + dx * t - px, z = r.az + dz * t - pz; return Math.sqrt(x * x + z * z);
}
