import type { ProgramDefinition } from '../core/types.ts';
import { program as survivalIsland } from './survival-island.ts';
import { program as escapeRoom } from './escape-room.ts';
import { program as startupSim } from './startup-sim.ts';
import { program as liarsTable } from './liars-table.ts';
import { program as needleHaystack } from './needle-haystack.ts';
import { program as chainOfWhispers } from './chain-of-whispers.ts';
import { program as drawItBlind } from './draw-it-blind.ts';
import { program as codeAgent } from './code-agent.ts';

/**
 * Registry of every program (simulation / multi-step pipeline).
 * To add one: write src/programs/<id>.ts exporting `program`, import it here,
 * and add a JSON test with "kind": "program", "program": "<id>".
 */
const all: ProgramDefinition[] = [survivalIsland, escapeRoom, startupSim, liarsTable, needleHaystack, chainOfWhispers, drawItBlind, codeAgent];

export const PROGRAMS: Record<string, ProgramDefinition> = Object.fromEntries(all.map((p) => [p.id, p]));
