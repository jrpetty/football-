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

const DEFAULTS = { maxDays: 15, size: 12, inventoryCap: 12, recentEvents: 5 };

function readConfig(config: Record<string, unknown>): IslandConfig & { recentEvents: number } {
  const num = (k: keyof typeof DEFAULTS, lo: number, hi: number): number => {
    const v = Number(config[k] ?? DEFAULTS[k]);
    return Number.isFinite(v) ? Math.min(hi, Math.max(lo, Math.round(v))) : DEFAULTS[k];
  };
  return {
    maxDays: num('maxDays', 5, 30),
    size: num('size', 10, 16),
    inventoryCap: num('inventoryCap', 6, 30),
    recentEvents: num('recentEvents', 1, 10),
  };
}

export function survivalSystemPrompt(cfg: IslandConfig): string {
  return `You are playing SURVIVAL ISLAND. You are a castaway on a small uninhabited island. Survive as many days as you can — the game lasts ${cfg.maxDays} days — or, better, get rescued.

TIME
- Each day has three action turns (Morning, Afternoon, Evening). Every command takes one turn.
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
DRINK — drink your fill at the spring (water to 100). In rain or storms you can catch a little rainwater anywhere.
EAT <item> — eat that food until you are full, e.g. EAT COCONUT, EAT COOKED FISH, EAT RED BERRIES.
CRAFT SPEAR — 1 wood + 1 stone + 1 fibre. Needed to fish.
BUILD FIRE — 3 wood + 1 stone: a campfire on this tile that burns for 3 nights (building again refuels it). Lets you cook.
BUILD SHELTER — 4 wood + 3 fibre: a permanent shelter on this tile.
BUILD SIGNAL — only on the summit: 5 wood + 2 fibre: a huge signal pile.
LIGHT SIGNAL — on the summit, with a built signal pile and 1 fibre as tinder: it blazes for this turn and the next, then burns out.
FISH — on a beach or palm tile, with a spear: catch 0-2 raw fish.
COOK FISH — at a burning campfire on your tile: cooks all your raw fish.
REST — recover energy (more when sheltered).

FOOD: coconuts give food and water. Cooked fish is the best meal; raw fish may upset your stomach. Some berries on this island are poisonous — trust your senses.
Your inventory holds ${cfg.inventoryCap} items (the spear doesn't count).
RESCUE: ships sometimes pass this island, but nobody will notice a lone castaway. A blazing signal fire on the summit, lit at the right time, might be seen. Look for clues.

MEMORY: each turn you see only the current situation, the last few events and your own note. To remember anything (discoveries, dangers, schedules, plans), write a line NOTE: <text> (max 300 characters). It is shown back to you next turn; if you omit it, your previous note is kept.

OUTPUT: think briefly if you want, then end your reply with exactly one command line:
ACTION: <command>
You may put one NOTE: line just before it. Only listed commands work; anything else wastes the turn.`;
}

export const program: ProgramDefinition = {
  id: 'survival-island',
  name: 'Survival Island',
  description:
    'A seeded castaway simulation on a 12×12 island with a day/night cycle, weather, cold nights, scarce regrowing resources, crafting (spear, campfire, shelter, signal pile) and hidden hazards such as poisonous berries. ' +
    'The model plays turn by turn (three actions per day) with only a compact observation and a 300-character self-written memo as long-term memory. ' +
    'Surviving takes steady resource loops; getting rescued takes discovering the passing ship’s schedule and lighting a signal fire on the summit at the right moment.',
  scoring:
    'Score = 0.7 × survival + 0.2 × rescue + 0.1 × building. Survival = (nights survived − nights an idle castaway survives on the same island) ÷ (days in the game − that idle baseline), clamped to 0–1; being rescued counts as surviving every day. ' +
    'Rescue is 1 if a passing ship saw your signal fire, otherwise 0. Building is the fraction of the four milestones achieved (spear, campfire, shelter, signal pile). ' +
    'Passed = rescued or alive at the end of the final day.',
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
