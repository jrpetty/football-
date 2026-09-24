/**
 * A scripted, competent Survival Island player used by the tests. It keeps a
 * mirror of the game (the engine is deterministic, so the mirror stays in
 * lock-step with the real run) and plays from full knowledge of the
 * generated world: it never eats poison, knows the ship schedule, keeps
 * water and food topped up, keeps a camp and campfire when the weather is
 * harsh, and — in rescue mode — builds the signal pile, lights a campfire
 * the night before a pass (proof of life) and lights the pile at midday.
 *
 * It plays with committed goals (keep gathering wood until it has enough,
 * keep walking to the spring until it drinks…) so it does not thrash between
 * needs; only critical needs and the pass timeline interrupt a goal.
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
type D = keyof typeof DIRS;

/** First MOVE (up to MAX_STEPS tiles in one direction) along a shortest land path; null when there / unreachable. */
function stepToward(s: IslandState, target: Pos): string | null {
  if (s.pos.x === target.x && s.pos.y === target.y) return null;
  const d = pathDistances(s.tiles, target);
  if (d[s.pos.y]![s.pos.x]! < 0) return null;
  const names = Object.keys(DIRS) as D[];
  let cur: Pos = { ...s.pos };
  let dir: D | null = null;
  let steps = 0;
  while (steps < MAX_STEPS && d[cur.y]![cur.x]! > 0) {
    const here: number = d[cur.y]![cur.x]!;
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
  const d = pathDistances(s.tiles, s.pos);
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

type Goal = 'water' | 'food' | 'pile' | 'firemats' | 'camp' | 'campfire' | null;

export function createIslandAgent(world: IslandWorld, opts: { rescue: boolean }): IslandAgent {
  const s = newIslandState(world);
  const safe = `${world.safeBerry}berry`;
  const dist = (a: Pos, b: Pos): number => pathDistances(s.tiles, a)[b.y]![b.x]!;
  // Camp: the cave when it is close to the spring, else a shelter next to the spring.
  let camp: Pos = world.cave;
  if (dist(world.cave, world.spring) > 3) {
    const cands = Object.values(DIRS)
      .map((v) => ({ x: world.spring.x + v.x, y: world.spring.y + v.y }))
      .filter((p) => {
        const t = s.tiles[p.y]?.[p.x]?.terrain;
        return t && t !== 'sea' && t !== 'cave' && t !== 'summit' && t !== 'spring';
      });
    camp = cands[0] ?? world.cave;
  }
  const campKey = `${camp.x},${camp.y}`;
  const campIsCave = camp.x === world.cave.x && camp.y === world.cave.y;
  const harsh = world.drinkAmount < 100 || world.poisonDamage > 20;
  let goal: Goal = null;

  const inv = (k: string): number => s.inv[k] ?? 0;
  const terrainIs = (...t: string[]) => (x: number, y: number) => t.includes(s.tiles[y]![x]!.terrain);
  const foodUnits = (): number => inv('coconut') + inv(safe) + inv('cookedfish') + inv('rawfish');
  const campReady = (): boolean => campIsCave || s.shelters.includes(campKey);
  const campFuel = (): number => s.fires[campKey] ?? 0;

  function gatherAt(pred: (x: number, y: number) => boolean, avail: Set<string>): string | null {
    const here = s.tiles[s.pos.y]![s.pos.x]!;
    if (pred(s.pos.x, s.pos.y) && here.stock > 0 && avail.has('GATHER')) return 'GATHER';
    const t = nearest(s, (x, y) => pred(x, y) && s.tiles[y]![x]!.stock > 0);
    return t ? stepToward(s, t.pos) : null;
  }
  function eat(): string | null {
    for (const f of ['cookedfish', 'coconut', safe, 'rawfish']) if (inv(f) > 0) return `EAT ${itemLabel(f).toUpperCase()}`;
    return null;
  }
  function getFood(avail: Set<string>): string | null {
    const bush = nearest(s, (x, y) => {
      const t = s.tiles[y]![x]!;
      return t.terrain === 'bush' && t.berry === world.safeBerry && t.stock >= 2;
    });
    const palm = nearest(s, (x, y) => s.tiles[y]![x]!.terrain === 'palm' && s.tiles[y]![x]!.stock >= 1);
    const best = bush && (!palm || bush.dist <= palm.dist + 2) ? bush : palm;
    if (!best) return null;
    return best.dist === 0 ? (avail.has('GATHER') ? 'GATHER' : null) : stepToward(s, best.pos);
  }

  /** One step towards the current goal, or null when the goal is complete / impossible. */
  function pursue(g: Goal, avail: Set<string>): string | null {
    const here = s.tiles[s.pos.y]![s.pos.x]!;
    switch (g) {
      case 'water':
        if (s.water >= 85) return null;
        return here.terrain === 'spring' ? 'DRINK' : stepToward(s, world.spring);
      case 'food':
        if (foodUnits() >= 3) return null;
        return getFood(avail);
      case 'pile':
        if (s.signal !== 'none') return null;
        if (inv('wood') < 5) return gatherAt(terrainIs('forest'), avail);
        if (inv('fibre') < 3) return gatherAt(terrainIs('grass'), avail);
        return here.terrain === 'summit' ? 'BUILD SIGNAL' : stepToward(s, world.summit);
      case 'firemats':
        if (inv('wood') >= 3 && inv('stone') >= 1) return null;
        if (inv('wood') < 3) return gatherAt(terrainIs('forest'), avail);
        return gatherAt(terrainIs('rocks', 'summit'), avail);
      case 'camp':
        if (campReady()) return null;
        if (inv('wood') < 4) return gatherAt(terrainIs('forest'), avail);
        if (inv('fibre') < 3) return gatherAt(terrainIs('grass'), avail);
        return s.pos.x === camp.x && s.pos.y === camp.y ? 'BUILD SHELTER' : stepToward(s, camp);
      case 'campfire':
        if (campFuel() >= 2) return null;
        if (inv('wood') < 3 || inv('stone') < 1) return pursue('firemats', avail);
        return s.pos.x === camp.x && s.pos.y === camp.y ? 'BUILD FIRE' : stepToward(s, camp);
      default:
        return null;
    }
  }

  function chooseGoal(): Goal {
    const nextShip = world.shipDays.find((d) => d > s.day || (d === s.day && s.phase <= 1));
    const passSoon = opts.rescue && nextShip !== undefined && nextShip - s.day <= 2;
    if (s.water < 45) return 'water';
    if (s.food < 45 && foodUnits() === 0) return 'food';
    if (opts.rescue && nextShip !== undefined && s.signal === 'none') return 'pile';
    if (opts.rescue && nextShip !== undefined && world.requireNightFire && !(inv('wood') >= 3 && inv('stone') >= 1)) return 'firemats';
    if (passSoon && foodUnits() < 2) return 'food';
    if (harsh && !campReady()) return 'camp';
    if (harsh && campFuel() <= 1) return 'campfire';
    if (foodUnits() < 2) return 'food';
    if (s.water < 70) return 'water';
    if (!campReady() && s.day >= 3) return 'camp';
    return null;
  }

  function decide(): string {
    const avail = new Set(availableActions(world, s));
    const here = s.tiles[s.pos.y]![s.pos.x]!;
    const weather = world.weather[s.day]!;
    const atSummit = here.terrain === 'summit';
    const shipToday = world.shipDays.includes(s.day);
    const nextShip = world.shipDays.find((d) => d > s.day || (d === s.day && s.phase <= 1));
    const passTomorrow = opts.rescue && nextShip === s.day + 1;
    const fireTonight = Object.values(s.fires).some((f) => f > 0);
    const atCamp = s.pos.x === camp.x && s.pos.y === camp.y;

    // 1. The pass: be on the summit with the pile blazing at midday.
    if (opts.rescue && shipToday && s.phase <= 1) {
      if (avail.has('LIGHT SIGNAL')) return 'LIGHT SIGNAL';
      if (atSummit && s.signal === 'none' && avail.has('BUILD SIGNAL') && s.phase === 0) return 'BUILD SIGNAL';
      if (!atSummit && inv('fibre') >= 1 && (s.signal === 'built' || (inv('wood') >= 5 && inv('fibre') >= 3 && s.phase === 0)))
        return stepToward(s, world.summit) ?? 'REST';
    }
    if (opts.rescue && nextShip !== undefined && atSummit && s.signal === 'none' && avail.has('BUILD SIGNAL')) return 'BUILD SIGNAL';

    // 2. Critical needs interrupt any goal.
    if (s.water < 20) return avail.has('DRINK') ? 'DRINK' : (stepToward(s, world.spring) ?? 'REST');
    if (s.food < 30 && eat()) return eat()!;
    if (s.food < 15) return getFood(avail) ?? 'REST';
    if (s.energy < 12) return 'REST';

    // 3. Cheap top-ups where we stand.
    if (here.terrain === 'spring' && s.water < 85) return 'DRINK';
    if (s.food < 55 && eat()) return eat()!;
    const goodFood = (here.terrain === 'bush' && here.berry === world.safeBerry) || here.terrain === 'palm';
    if (goodFood && here.stock > 0 && foodUnits() < 3 && avail.has('GATHER')) return 'GATHER';

    // 4. The day before a pass: tinder, then get next to the summit and (evening) light a campfire as proof of life.
    if (opts.rescue && s.signal === 'built' && inv('fibre') < 1) {
      const g = gatherAt(terrainIs('grass'), avail);
      if (g) return g;
    }
    if (passTomorrow && s.signal === 'built') {
      if (s.phase === 2 && world.requireNightFire && !fireTonight && avail.has('BUILD FIRE')) return 'BUILD FIRE';
      if (s.phase >= 1 && dist(s.pos, world.summit) > 1) return stepToward(s, world.summit) ?? 'REST';
      if (s.phase === 2 && world.requireNightFire && !fireTonight) return pursue('firemats', avail) ?? 'REST';
    }

    // 5. Evenings in harsh or wet weather: sleep at camp by a fire.
    if (s.phase === 2 && campReady() && (harsh || s.warmth < 50 || weather === 'storm' || weather === 'rain')) {
      if (!atCamp && dist(s.pos, camp) <= MAX_STEPS && !passTomorrow) return stepToward(s, camp) ?? 'REST';
      if (atCamp && campFuel() <= 0 && avail.has('BUILD FIRE')) return 'BUILD FIRE';
      if (atCamp && avail.has('COOK FISH')) return 'COOK FISH';
    }

    // 6. Committed goals (thirst and hunger pre-empt a long errand).
    const fresh = chooseGoal();
    if ((fresh === 'water' || fresh === 'food') && goal !== 'water' && goal !== 'food') goal = fresh;
    for (let tries = 0; tries < 3; tries++) {
      if (goal === null) goal = chooseGoal();
      if (goal === null) break;
      const step = pursue(goal, avail);
      if (step) return step;
      goal = null;
    }
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
