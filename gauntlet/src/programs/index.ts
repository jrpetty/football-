import type { ProgramDefinition } from '../core/types.ts';

/** Registry of every program (simulation / multi-step pipeline). */
export const PROGRAMS: Record<string, ProgramDefinition> = {};
