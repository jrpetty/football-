/**
 * Survival Island — a seeded castaway simulation.
 *
 * The model manages health, food, water, energy and warmth on a 12×12
 * island with a day/night cycle, weather, scarce regrowing resources,
 * crafting and hazards (poisonous berries, storms, cold nights). The
 * long-horizon prize is rescue: a supply ship passes on a fixed schedule
 * that can be discovered (a message in a bottle, sightings), and only a
 * signal fire blazing on the summit at the right moment brings it in.
 */
import type { ProgramContext, ProgramDefinition, ProgramResult, ReplayFrame } from '../core/types.ts';
import { askTurn, clamp01, noteBlock, round, truncate } from './lib/agentic-common.ts';
import {
  ISLAND_DEFAULTS,
  coordName,
  generateIsland,
  idleNights,
  islandFrame,
  islandObservation,
  newIslandState,
  PHASES,
  stepIsland,
  type IslandConfig,
} from './lib/agentic-island.ts';

const DEFAULTS = { ...ISLAND_DEFAULTS, recentEvents: 5 };

type Range = [number, number];

function readConfig(config: Record<string, unknown>): IslandConfig & { recentEvents: number } {
  const num = (k: string, fallback: number, lo: number, hi: number, int = true): number => {
    const v = Number(config[k] ?? fallback);
    if (!Number.isFinite(v)) return fallback;
    const c = Math.min(hi, Math.max(lo, v));
    return int ? Math.round(c) : c;
  };
  const range = (k: string, fallback: Range, lo: number, hi: number): Range => {
    const v = config[k];
    if (!Array.isArray(v) || v.length !== 2) return fallback;
    const a = Math.round(Math.min(hi, Math.max(lo, Number(v[0]))));
    const b = Math.round(Math.min(hi, Math.max(lo, Number(v[1]))));
    return Number.isFinite(a) && Number.isFinite(b) ? [Math.min(a, b), Math.max(a, b)] : fallback;
  };
  const d = ISLAND_DEFAULTS;
  const maxDays = num('maxDays', d.maxDays, 5, 30);
  return {
    maxDays,
    size: num('size', d.size, 10, 16),
    inventoryCap: num('inventoryCap', d.inventoryCap, 6, 30),
    shipFirstDay: range('shipFirstDay', d.shipFirstDay, 1, maxDays),
    shipEveryDays: range('shipEveryDays', d.shipEveryDays, 1, 10),
    signalsNeeded: num('signalsNeeded', d.signalsNeeded, 1, 4),
    requireNightFire: config.requireNightFire === undefined ? d.requireNightFire : config.requireNightFire === true,
    stormChance: num('stormChance', d.stormChance, 0, 0.4, false),
    rainChance: num('rainChance', d.rainChance, 0, 0.5, false),
    nightCold: range('nightCold', d.nightCold, 0, 40),
    drinkAmount: num('drinkAmount', d.drinkAmount, 10, 100),
    berryBushes: num('berryBushes', d.berryBushes, 2, 6),
    poisonBushes: num('poisonBushes', d.poisonBushes, 1, 5),
    poisonDamage: num('poisonDamage', d.poisonDamage, 0, 60),
    recentEvents: num('recentEvents', DEFAULTS.recentEvents, 1, 10),
  };
}

export function survivalSystemPrompt(cfg: IslandConfig): string {
  const drink = cfg.drinkAmount >= 100 ? 'drink your fill at the spring (water to 100)' : `drink at the spring (+${cfg.drinkAmount} water; it only trickles)`;
  const passes =
    cfg.signalsNeeded <= 1
      ? 'The first pass on which the crew sees your signal, they send a boat and you are rescued.'
      : `The crew must see your signal on ${cfg.signalsNeeded} different passes: the first time(s) they acknowledge it with their horn, and on pass number ${cfg.signalsNeeded} they send a boat and you are rescued.`;
  const proof = cfg.requireNightFire
    ? '\n- The captain also wants proof that someone lives here: a pass only counts if a campfire you built was burning somewhere on the island during the previous night (a storm drowns any campfire without a shelter over it).'
    : '';
  return `You are playing SURVIVAL ISLAND. You are a castaway on a small uninhabited island. Survive as many days as you can — the game lasts ${cfg.maxDays} days — or, better, get rescued.

TIME
- Day 1 is the 1st of the month. Each day has three action turns (Morning, Afternoon, Evening); every command takes one turn.
- After the Evening turn the night resolves automatically wherever you are standing.

STATS (0-100, higher is better): health, food, water, energy, warmth.
- Each daytime turn costs 2 food and 4 water (7 water on hot days). Each night costs 5 food and 5 water.
- If food, water or warmth reaches 0 you lose health every turn (thirst is the fastest killer). At 0 health you die.
- Warmth rises in sunshine, falls in rain and storms, and drops sharply at night. At night a shelter (or the cave) protects you from cold and wet, and a campfire on your tile adds a lot of warmth. Sleeping in the open during a storm hurts.
- Energy is spent by actions (4 per tile walked, gather 6, fish 8, build 10, …) and restored by sleep (more when sheltered) and REST. With enough food, water and warmth you heal at night.

MAP LETTERS: ~ sea (impassable) · . beach (driftwood) · P palm (coconuts) · , grass (fibre) · * berry bush (berries) · T forest (wood) · ^ rocks (stone) · A summit, the highest point (stone) · S spring (fresh water) · C cave (natural shelter) · F your campfire · H your shelter · # signal pile · @ you · ? unexplored.
Coordinates are column letter + row number (e.g. F7). North is up (row numbers decrease going north). Gathered spots run out and regrow slowly (stone and driftwood never regrow).

COMMANDS
MOVE <N|S|E|W> [1-3] — walk 1 to 3 tiles in a straight line, e.g. MOVE N or MOVE E 3 (you stop early at the sea).
GATHER — collect what this tile offers (wood, stone, fibre, coconuts, berries, driftwood).
DRINK — ${drink}. In rain or storms you can catch a little rainwater anywhere (+20).
EAT <item> — eat that food until you are full, e.g. EAT COCONUT, EAT COOKED FISH, EAT RED BERRIES.
CRAFT SPEAR — 1 wood + 1 stone + 1 fibre. Needed to fish.
BUILD FIRE — 3 wood + 1 stone: a campfire on this tile that burns for 3 nights (building again refuels it). Lets you cook.
BUILD SHELTER — 4 wood + 3 fibre: a permanent shelter on this tile.
BUILD SIGNAL — only on the summit: 5 wood + 2 fibre: a huge signal pile.
LIGHT SIGNAL — on the summit, with a built signal pile and 1 fibre as tinder. Impossible during a storm (rain is fine). The fire blazes during this turn and the next turn, then burns out and the pile is gone: build a new pile to signal again.
FISH — on a beach or palm tile, with a spear: catch 0-2 raw fish.
COOK FISH — at a burning campfire on your tile: cooks all your raw fish.
REST — recover energy (more when sheltered).

FOOD: coconuts give food and water. Cooked fish is the best meal; raw fish may upset your stomach. Some berries on this island are poisonous — trust your senses.
Your inventory holds ${cfg.inventoryCap} items (the spear doesn't count).

RESCUE
- A supply ship passes the island on a fixed schedule, always at midday, except on stormy days when she stays in port. Midday is the end of the Afternoon turn: the pass happens right after your Afternoon action. Every pass is reported to you wherever you are.
- The crew only notices a signal fire that is BLAZING on the summit at that moment — so light the pile during the Morning or the Afternoon turn of a passing day.
- ${passes}${proof}
- The schedule is not given to you: work it out from clues and from the passes you witness, and keep track of it.

MEMORY: each turn you see only the current situation, the last few events and your own note. To remember anything (discoveries, dangers, schedules, plans), write a line NOTE: <text> (max 300 characters). It is shown back to you next turn; if you omit it, your previous note is kept.

OUTPUT: think briefly if you want, then end your reply with exactly one command line:
ACTION: <command>
You may put one NOTE: line just before it. If you write several ACTION lines, only the last one counts. Anything that is not a valid command wastes the turn.`;
}

export const program: ProgramDefinition = {
  id: 'survival-island',
  name: 'Survival Island',
  description:
    'A seeded castaway simulation on a 12×12 island with a day/night cycle, weather, cold nights, scarce regrowing resources, crafting (spear, campfire, shelter, signal pile) and hidden hazards such as poisonous berries. ' +
    'The model plays turn by turn (three actions per day) with only a compact observation and a 300-character self-written memo as long-term memory. ' +
    'Surviving takes steady resource loops; getting rescued takes discovering the passing ship’s schedule (a logbook page in a bottle, the passes you witness), then lighting a signal fire on the summit at midday on two different passing days — surviving, rebuilding and timing in between.',
  scoring:
    'Score = 0.7 × survival + 0.2 × rescue + 0.1 × building. Survival = (nights survived − nights an idle castaway survives on the same island) ÷ (days in the game − that idle baseline), clamped to 0–1; being rescued counts as surviving every day. ' +
    'Rescue is 1 if the supply ship counted your blazing summit signal on the required number of midday passes (2 by default; the hard variant also requires a campfire burning the night before each pass), otherwise 0. Building is the fraction of the four milestones achieved (spear, campfire, shelter, signal pile). ' +
    'Config knobs (days, ship schedule ranges, signals needed, night-fire proof, storm and rain odds, night cold, spring flow, poisonous bushes) only change the world, never the formula. Passed = rescued or alive at the end of the final day.',
  defaults: DEFAULTS,
  async run(ctx: ProgramContext): Promise<ProgramResult> {
    const cfg = readConfig(ctx.config);
    const world = generateIsland(ctx.rng, cfg);
    const idle = idleNights(world);
    const s = newIslandState(world);
    const system = survivalSystemPrompt(cfg);

    let note = '';
    const recent: string[] = [];
    const frames: ReplayFrame[] = [
      islandFrame(world, s, 0, {
        label: 'Day 1 · Dawn',
        outcome: `Washed up on the beach at ${coordName(world.start)}.`,
        tone: 'neutral',
      }),
    ];
    const pushEvent = (e: string): void => {
      recent.push(e);
      while (recent.length > cfg.recentEvents) recent.shift();
    };

    while (!s.over) {
      if (ctx.signal.aborted) throw Object.assign(new Error('Aborted'), { name: 'AbortError' });
      const observation = islandObservation(world, s, noteBlock(note), recent);
      const label = `Turn ${s.turn + 1} · Day ${s.day} ${PHASES[s.phase]}`;
      const turn = await askTurn(ctx, system, observation, label);
      if (turn.note !== null) note = turn.note;
      const step = stepIsland(world, s, turn.action, turn.failure);

      const when = `Day ${step.day} ${PHASES[step.phase]}`;
      const nightIdx = step.night ? step.events.indexOf(step.night) : -1;
      const dayEvents = nightIdx >= 0 ? step.events.slice(0, nightIdx) : step.events;
      const nightEvents = nightIdx >= 0 ? step.events.slice(nightIdx) : [];
      pushEvent(`${when}: ${step.action} → ${step.outcome}`);
      for (const e of dayEvents) pushEvent(`${when}: ${e}`);
      for (const e of nightEvents) pushEvent(e === step.night ? e : `Night ${step.day}: ${e}`);

      frames.push(
        islandFrame(
          world,
          s,
          frames.length,
          {
            label: step.label,
            action: step.action,
            outcome: truncate([step.outcome, ...dayEvents].join(' '), 240),
            observation: `${when} · ${world.weather[step.day]} · at ${coordName(s.pos)}`,
            tone: step.tone,
          },
          step.preNight,
        ),
      );
      if (step.night) {
        const rough = /battered|freezing|parched|starving|drowns/.test(step.night);
        frames.push(
          islandFrame(world, s, frames.length, {
            label: `Day ${step.day} · Night`,
            outcome: truncate(nightEvents.join(' '), 240),
            tone: !s.alive || rough ? 'bad' : nightEvents.some((e) => e.startsWith('You have survived')) ? 'good' : 'neutral',
          }),
        );
      }
    }

    const maxDays = world.maxDays;
    const daysCredited = s.rescued ? maxDays : s.nightsSurvived;
    const survival = clamp01((daysCredited - idle) / Math.max(1, maxDays - idle));
    const rescue = s.rescued ? 1 : 0;
    const m = s.milestones;
    const built = [m.spear, m.campfire, m.shelter, m.signalPile].filter((t) => t !== null).length;
    const craft = built / 4;
    const score = round(clamp01(0.7 * survival + 0.2 * rescue + 0.1 * craft), 4);
    const passed = s.rescued || (s.alive && s.nightsSurvived >= maxDays);

    const summary = s.rescued
      ? `Rescued by ship on day ${s.rescueDay} of ${maxDays}`
      : s.alive
        ? `Survived all ${maxDays} days · not rescued`
        : `Day ${s.day}: died of ${s.cause}`;

    return {
      score,
      passed,
      summary,
      detail: {
        daysCredited,
        nightsSurvived: s.nightsSurvived,
        maxDays,
        idleBaselineNights: idle,
        rescued: s.rescued,
        rescueDay: s.rescueDay,
        alive: s.alive,
        causeOfDeath: s.alive ? null : s.cause,
        deathDay: s.alive ? null : s.day,
        turnsUsed: s.turn,
        invalidActions: s.invalid,
        failedActions: s.failed,
        milestones: {
          spearTurn: m.spear,
          campfireTurn: m.campfire,
          shelterTurn: m.shelter,
          signalPileTurn: m.signalPile,
          signalLitTurn: m.signalLit,
          bottleFound: s.bottleFound,
        },
        shipSightings: s.shipsSeen,
        shipSchedule: { firstDay: world.shipFirst, everyDays: world.shipPeriod, passingDays: world.shipDays },
        poisonBerriesEaten: s.poisonEaten,
        berries: { safe: world.safeBerry, poisonous: world.poisonBerry },
        finalStats: { health: Math.max(0, s.health), food: s.food, water: s.water, energy: s.energy, warmth: s.warmth },
        components: { survival: round(survival, 4), rescue, building: craft },
        finalNote: note,
      },
      replay: {
        title: `Survival Island · seed ${ctx.seed}`,
        gauges: ['health', 'food', 'water', 'energy', 'warmth'],
        frames,
      },
    };
  },
};
