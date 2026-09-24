/**
 * Arena game registry. To add a game, create `src/arena/games/<id>.ts`
 * exporting an `ArenaGame` and register it here (see docs/ADDING_TESTS.md →
 * "Adding an Arena game").
 */
import type { ArenaGame } from '../types.ts';
import { chess } from './chess.ts';
import { connect4 } from './connect4.ts';

// eslint-disable-next-line @typescript-eslint/no-explicit-any
export const GAMES: Record<string, ArenaGame<any>> = {
  connect4,
  chess,
};

// eslint-disable-next-line @typescript-eslint/no-explicit-any
export function getGame(id: string): ArenaGame<any> {
  const g = GAMES[id];
  if (!g) throw new Error(`Unknown arena game "${id}". Available: ${Object.keys(GAMES).join(', ')}`);
  return g;
}
