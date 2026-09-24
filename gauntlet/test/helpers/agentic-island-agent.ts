/**
 * A scripted, competent Survival Island player used by the tests. It keeps a
 * mirror of the game (the engine is deterministic, so the mirror stays in
 * lock-step with the real run) and plays from full knowledge of the
 * generated world: it never eats poison, keeps water and food topped up,
 * sleeps sheltered when it can, and — in rescue mode — builds the signal
 * pile and lights it on the morning of a ship day.
 */
import {
  MAX_STEPS,
  availableActions,
  itemLabel,
  newIslandState,
  pathDistances,
  stepIsland,
  type IslandState,
  type IslandWorld,
  type Pos,
} from '../../src/programs/lib/agentic-island.ts';

const DIRS = { N: { x: 0, y: -1 }, S: { x: 0, y: 1 }, E: { x: 1, y: 0 }, W: { x: -1, y: 0 } } as const;

function distMap(s: IslandState, from: Pos): number[][] {
  return pathDistances(s.tiles, from);
}

/** First MOVE (up to MAX_STEPS tiles in one direction) along a shortest land path to target; null when there / unreachable. */
function stepToward(s: IslandState, target: Pos): string | null {
  if (s.pos.x === target.x && s.pos.y === target.y) return null;
  const d = distMap(s, target);
  if (d[s.pos.y]![s.pos.x]! < 0) return null;
  type D = keyof typeof DIRS;
  const names = Object.keys(DIRS) as D[];
  let cur: Pos = { ...s.pos };
  let dir: D | null = null;
  let steps = 0;
  while (steps < MAX_STEPS && d[cur.y]![cur.x]! > 0) {
    const here: number = d[cur.y]![cur.x]!;
    // Prefer continuing straight so one MOVE covers more ground.
    const order: D[] = dir ? [dir, ...names.filter((k) => k !== dir)] : names;
    const at: Pos = cur;
    const next: D | undefined = order.find((k) => d[at.y + DIRS[k].y]?.[at.x + DIRS[k].x] === here - 1);
    if (!next || (dir && next !== dir)) break;
    dir = next;
    cur = { x: cur.x + DIRS[next].x, y: cur.y + DIRS[next].y };
    steps++;
  }
  if (!dir) return null;
  return steps > 1 ? `MOVE ${dir} ${steps}` : `MOVE ${dir}`;
}

function nearest(s: IslandState, pred: (x: number, y: number) => boolean): { pos: Pos; dist: number } | null {
  const d = distMap(s, s.pos);
  let best: { pos: Pos; dist: number } | null = null;
  for (let y = 0; y < d.length; y++)
    for (let x = 0; x < d.length; x++) {
      const v = d[y]![x]!;
      if (v < 0 || !pred(x, y)) continue;
      if (!best || v < best.dist) best = { pos: { x, y }, dist: v };
    }
  return best;
}

export interface IslandAgent {
  state: IslandState;
  /** Decide, apply to the mirror, and return the command. */
  next(): string;
}

export function createIslandAgent(world: IslandWorld, opts: { rescue: boolean }): IslandAgent {
  const s = newIslandState(world);
  const safe = `${world.safeBerry}berry`;
  const dist = (a: Pos, b: Pos): number => pathDistances(s.tiles, a)[b.y]![b.x]!;
  // Camp: the cave when it is close to the spring, else a shelter next to the spring.
  const caveToSpring = dist(world.cave, world.spring);
  let camp: Pos = world.cave;
  if (caveToSpring > 3) {
    const cands = Object.values(DIRS)
      .map((v) => ({ x: world.spring.x + v.x, y: world.spring.y + v.y }))
      .filter((p) => {
        const t = s.tiles[p.y]?.[p.x]?.terrain;
        return t && t !== 'sea' && t !== 'cave' && t !== 'summit' && t !== 'spring';
      });
    camp = cands[0] ?? world.cave;
  }
  const campIsCave = camp.x === world.cave.x && camp.y === world.cave.y;

  const gatherAt = (pred: (x: number, y: number) => boolean, avail: Set<string>): string | null => {
    const here = s.tiles[s.pos.y]![s.pos.x]!;
    if (pred(s.pos.x, s.pos.y) && here.stock > 0 && avail.has('GATHER')) return 'GATHER';
    const t = nearest(s, (x, y) => pred(x, y) && s.tiles[y]![x]!.stock > 0);
    return t ? stepToward(s, t.pos) : null;
  };
  const isT = (terrain: string) => (x: number, y: number) => s.tiles[y]![x]!.terrain === terrain;
  const wood = (x: number, y: number) => s.tiles[y]![x]!.terrain === 'forest';
  const foodSpot = (x: number, y: number) => {
    const t = s.tiles[y]![x]!;
    return t.terrain === 'palm' || (t.terrain === 'bush' && t.berry === world.safeBerry);
  };
  const foodUnits = (): number => (s.inv.coconut ?? 0) + (s.inv[safe] ?? 0) + (s.inv.cookedfish ?? 0) + (s.inv.rawfish ?? 0);

  function decide(): string {
    const avail = new Set(availableActions(world, s));
    const here = s.tiles[s.pos.y]![s.pos.x]!;
    const atSummit = here.terrain === 'summit';
    const shipToday = world.shipDays.includes(s.day);
    const nextShip = world.shipDays.find((d) => d > s.day || (d === s.day && s.phase <= 1));
    const inv = (k: string): number => s.inv[k] ?? 0;
    const toCamp = dist(s.pos, camp);
    const atCamp = toCamp === 0;
    const campReady = campIsCave || s.shelters.includes(`${camp.x},${camp.y}`);
    const getFood = (): string | null => {
      // Prefer a well-stocked safe bush, then palms.
      const bush = nearest(s, (x, y) => {
        const t = s.tiles[y]![x]!;
        return t.terrain === 'bush' && t.berry === world.safeBerry && t.stock >= 2;
      });
      if (bush && bush.dist <= 6) return bush.dist === 0 ? 'GATHER' : stepToward(s, bush.pos);
      return gatherAt(foodSpot, avail);
    };

    // Rescue: be on the summit with a lit pile around midday of a ship day.
    if (opts.rescue && shipToday && s.phase <= 1 && avail.has('LIGHT SIGNAL')) return 'LIGHT SIGNAL';
    if (opts.rescue && s.signal === 'built' && shipToday && s.phase <= 1 && inv('fibre') >= 1 && !atSummit)
      return stepToward(s, world.summit) ?? 'REST';

    // Survival needs.
    if (here.terrain === 'spring' && s.water < 70) return 'DRINK';
    if (s.water < 30 && avail.has('DRINK')) return 'DRINK';
    if (s.food < 60) {
      for (const f of ['cookedfish', 'coconut', safe, 'rawfish']) if (inv(f) > 0) return `EAT ${itemLabel(f).toUpperCase()}`;
    }
    if (s.water < 35) return stepToward(s, world.spring) ?? 'DRINK';
    if (s.food < 45) {
      const g = getFood();
      if (g) return g;
    }
    if (s.energy < 15) return 'REST';

    // Rescue preparation comes first: the pile, then being in position the evening before.
    if (opts.rescue && nextShip !== undefined) {
      if (s.signal === 'none') {
        if (inv('wood') < 5) return gatherAt(wood, avail) ?? 'REST';
        if (inv('fibre') < 3) return gatherAt(isT('grass'), avail) ?? 'REST';
        if (atSummit) return 'BUILD SIGNAL';
        return stepToward(s, world.summit) ?? 'REST';
      }
      if (s.signal === 'built' && inv('fibre') < 1) return gatherAt(isT('grass'), avail) ?? 'REST';
      if (s.signal === 'built' && nextShip === s.day + 1 && s.phase === 2 && !atSummit) return stepToward(s, world.summit) ?? 'REST';
    }

    // Evening: sleep at camp when it is within reach.
    if (s.phase === 2 && campReady) {
      if (!atCamp && toCamp <= MAX_STEPS) return stepToward(s, camp) ?? 'REST';
      if (atCamp && (s.fires[`${camp.x},${camp.y}`] ?? 0) <= 0 && avail.has('BUILD FIRE')) return 'BUILD FIRE';
      if (atCamp && avail.has('COOK FISH')) return 'COOK FISH';
      if (atCamp) return 'REST';
    }

    // Camp: shelter first, then a campfire.
    if (!campReady) {
      if (inv('wood') < 4) return gatherAt(wood, avail) ?? 'REST';
      if (inv('fibre') < 3) return gatherAt(isT('grass'), avail) ?? 'REST';
      return atCamp ? 'BUILD SHELTER' : (stepToward(s, camp) ?? 'REST');
    }

    // Keep a food buffer and firewood.
    if (foodUnits() < 4 && s.phase < 2) {
      const g = getFood();
      if (g) return g;
    }
    if ((s.fires[`${camp.x},${camp.y}`] ?? 0) <= 1) {
      if (inv('wood') < 3) return gatherAt(wood, avail) ?? 'REST';
      if (inv('stone') < 1) return gatherAt((x, y) => ['rocks', 'summit'].includes(s.tiles[y]![x]!.terrain), avail) ?? 'REST';
    }
    if (s.phase === 1 && toCamp > MAX_STEPS) return stepToward(s, camp) ?? 'REST';
    if (s.water < 60 && dist(s.pos, world.spring) <= MAX_STEPS) return stepToward(s, world.spring) ?? 'DRINK';
    return 'REST';
  }

  return {
    state: s,
    next() {
      const cmd = decide();
      stepIsland(world, s, cmd);
      return cmd;
    },
  };
}
