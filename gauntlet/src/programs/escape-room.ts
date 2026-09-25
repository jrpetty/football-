/**
 * The Escape Room — three connected rooms of seeded, interlocking puzzles.
 *
 * The model explores with text commands under a hard move budget. Every
 * seed changes the puzzle modules, encodings, object names and placements,
 * so the chain of reasoning differs per seed. The optimal move count is
 * computed from the generated world and reported next to the model's own.
 */
import type { ProgramContext, ProgramDefinition, ProgramResult, ReplayFrame } from '../core/types.ts';
import { askTurn, clamp01, noteBlock, round, truncate } from './lib/agentic-common.ts';
import {
  cloneEscState,
  ESCAPE_DEFAULTS,
  escapeEvent,
  escapeFrame,
  escapeObservation,
  escapeSimFrame,
  escapeSimWorld,
  generateEscape,
  newEscState,
  stepEscape,
  type EscapeConfig,
} from './lib/agentic-escape.ts';

const DEFAULTS = { ...ESCAPE_DEFAULTS, recentEvents: 5 };

function readConfig(config: Record<string, unknown>): EscapeConfig & { recentEvents: number } {
  const d = ESCAPE_DEFAULTS;
  const num = (k: string, fallback: number, lo: number, hi: number): number => {
    const v = Number(config[k] ?? fallback);
    return Number.isFinite(v) ? Math.min(hi, Math.max(lo, v)) : fallback;
  };
  const locks = Array.isArray(config.locksPerRoom) ? config.locksPerRoom : d.locksPerRoom;
  const locksPerRoom = [0, 1, 2].map((i) => {
    const v = Number(locks[i] ?? d.locksPerRoom[i]);
    return Number.isFinite(v) ? Math.min(4, Math.max(1, Math.round(v))) : d.locksPerRoom[i]!;
  });
  const dec = Array.isArray(config.decoysPerRoom) && config.decoysPerRoom.length === 2 ? config.decoysPerRoom.map(Number) : d.decoysPerRoom;
  const dMin = Number.isFinite(dec[0]) ? Math.min(4, Math.max(0, Math.round(dec[0]!))) : d.decoysPerRoom[0];
  const dMax = Number.isFinite(dec[1]) ? Math.min(4, Math.max(dMin, Math.round(dec[1]!))) : Math.max(dMin, d.decoysPerRoom[1]);
  const slack = config.budgetSlack === null || config.budgetSlack === undefined ? d.budgetSlack : num('budgetSlack', 0.25, 0.05, 3);
  return {
    locksPerRoom,
    moveBudget: Math.round(num('moveBudget', d.moveBudget, 10, 120)),
    budgetSlack: slack,
    crossRoom: config.crossRoom === undefined ? d.crossRoom : config.crossRoom === true,
    cipherDepth: Math.round(num('cipherDepth', d.cipherDepth, 1, 2)),
    decoysPerRoom: [dMin, dMax],
    redHerrings: config.redHerrings === undefined ? d.redHerrings : config.redHerrings === true,
    emptySpots: Math.round(num('emptySpots', d.emptySpots, 0, 2)),
    recentEvents: Math.round(num('recentEvents', DEFAULTS.recentEvents, 1, 10)),
  };
}

export function escapeSystemPrompt(moveBudget: number): string {
  return `You are playing THE ESCAPE ROOM. You are locked in a house of three connected rooms. Get out through the EXIT DOOR in the third room before you run out of moves.

HOW IT WORKS
- The way onward from every room is locked. Locks open with keys, with tools, with items you combine, or with codes, words and colour sequences hidden in clues around the rooms (numbers, pictures, notes, ciphers, riddles…). Clues can depend on each other, and on things you saw in earlier rooms.
- EXAMINE objects to read them and to search them — some things are only found by examining furniture. Opened containers and searched hiding places reveal items; TAKE items to carry them. Locks and containers often carry an inscription that tells you which clue they want.
- Some items only become useful once combined: USE one item ON another while holding both.
- You have ${moveBudget} moves. EVERY command costs one move — including LOOK, INVENTORY, failed attempts and wrong codes. Opened doors stay open, so you can go back.

COMMANDS
LOOK — describe the room again (the room is also shown every turn).
EXAMINE <object> — look closely at an object or item, and search it.
TAKE <item> — pick up an item.
USE <item> ON <object or item> — e.g. a key on a lock, a tool on a fixture, or two items you hold.
ENTER <code> ON <object> — try a combination: digits (ENTER 1234 ON SAFE), letters (ENTER WORD ON BRIEFCASE) or colours in order (ENTER RED BLUE GREEN ON PANEL).
GO <door> — walk through an open door.
INVENTORY — list what you carry.
Use object names exactly as they are listed.

MEMORY: each turn you see only the current room, your inventory, the last few events and your own note. Clue texts you have read scroll out of view after a few moves, so write down what matters in a line NOTE: <text> (max 300 characters). It is shown back to you next turn; if you omit it, your previous note is kept.

OUTPUT: think as much as you need, then end your reply with exactly one command line:
ACTION: <command>
You may put one NOTE: line just before it. If you write several ACTION lines, only the last one counts. Anything that is not a valid command wastes a move.`;
}

export const program: ProgramDefinition = {
  id: 'escape-room',
  name: 'The Escape Room',
  description:
    'Three connected rooms of seeded, interlocking puzzles: keys hidden in furniture, tools and items to combine, and code, word and colour locks whose answers are spread across clues (Roman numerals, stopped clocks, coloured counts, book spines, letter-number codes, Caesar ciphers whose shift is given elsewhere, acrostic poems, colour riddles). ' +
    'The seed changes which puzzles appear, how every clue is encoded and where everything is, so the reasoning chain itself differs per seed. ' +
    'The model plays one text command at a time under a hard move budget, with only a short event log and its own 300-character note as memory.',
  scoring:
    'Score = 0.5 × (locks opened ÷ total locks) + 0.3 for escaping + 0.2 × efficiency, where efficiency = optimal moves ÷ moves used (capped at 1) and only counts when the model escapes. ' +
    'The optimal move count is computed from each generated world: the shortest command sequence for a first-time solver who reads every clue it relies on (including the lock’s own inscription) and never guesses. ' +
    'Every command, valid or not, costs one move, and the case ends when the move budget runs out: a fixed number (45 by default) or, when budgetSlack is set, the optimal count plus that fraction, rounded up (the hard variant uses +25%). Passed = escaped.',
  defaults: DEFAULTS,
  async run(ctx: ProgramContext): Promise<ProgramResult> {
    const cfg = readConfig(ctx.config);
    const world = generateEscape(ctx.rng, cfg);
    const s = newEscState(world);
    const system = escapeSystemPrompt(world.moveBudget);

    let note = '';
    const recent: string[] = [];
    const frames: ReplayFrame[] = [
      { ...escapeFrame(world, s, 0, { label: 'Start', outcome: `Locked in ${world.rooms[0]!.name}.`, tone: 'neutral' }), sim: escapeSimFrame(world, s, { type: 'start' }) },
    ];

    while (!s.escaped && s.moves < world.moveBudget) {
      if (ctx.signal.aborted) throw Object.assign(new Error('Aborted'), { name: 'AbortError' });
      const observation = escapeObservation(world, s, noteBlock(note), recent);
      const turn = await askTurn(ctx, system, observation, `Move ${s.moves + 1}`);
      if (turn.note !== null) note = turn.note;
      const roomBefore = world.rooms[s.room]!.name;
      const before = cloneEscState(s);
      const step = stepEscape(world, s, turn.action, turn.failure);
      recent.push(`Move ${step.move}: ${step.action} → ${step.outcome}`);
      while (recent.length > cfg.recentEvents) recent.shift();
      frames.push({
        ...escapeFrame(world, s, frames.length, {
          label: `Move ${step.move}`,
          observation: roomBefore,
          action: step.action,
          outcome: truncate(step.outcome, 220),
          tone: step.tone,
        }),
        sim: escapeSimFrame(world, s, escapeEvent(world, before, s, turn.failure ? null : turn.action, step)),
      });
    }

    const total = world.locks.length;
    const opened = s.unlocked.length;
    const efficiency = s.escaped ? Math.min(1, world.optimal / Math.max(1, s.moves)) : 0;
    const score = round(clamp01(0.5 * (opened / total) + (s.escaped ? 0.3 + 0.2 * efficiency : 0)), 4);
    const summary = s.escaped
      ? `Escaped in ${s.moves} moves (optimal ${world.optimal})`
      : `Trapped in ${world.rooms[s.room]!.name} · ${opened}/${total} locks · ${s.moves} moves (optimal ${world.optimal})`;

    return {
      score,
      passed: s.escaped,
      summary,
      detail: {
        escaped: s.escaped,
        movesUsed: s.moves,
        moveBudget: world.moveBudget,
        optimalMoves: world.optimal,
        efficiency: round(efficiency, 4),
        locksOpened: opened,
        totalLocks: total,
        locks: world.locks.map((id) => {
          const o = world.objects[id]!;
          const log = s.lockLog.find((l) => l.id === id);
          return { name: o.name, kind: o.lock!.kind, room: world.rooms[o.rooms[0]!]!.name, openedAtMove: log?.move ?? null };
        }),
        roomsReached: s.visited.length,
        rooms: world.rooms.map((r) => r.name),
        invalidActions: s.invalid,
        failedActions: s.failed,
        wrongEntries: s.wrongEntries,
        components: { locks: round(opened / total, 4), escape: s.escaped ? 1 : 0, efficiency: round(efficiency, 4) },
        optimalPlan: world.plan,
        finalNote: note,
      },
      replay: {
        title: `The Escape Room · seed ${ctx.seed}`,
        gauges: ['progress', 'budget'],
        frames,
        sim: escapeSimWorld(world),
      },
    };
  },
};
