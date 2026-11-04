/**
 * The verifier registry: `{ "type": "verifier", "verifier": "<name>" }` looks its checker up here. Each checker
 * lives in src/scoring/verifiers/<name>.ts; that file (and the shared files it imports from this folder) is
 * part of the hash of every test that uses it, together with the generic scorer src/scoring/verifier.ts.
 */
import type { Verifier } from './types.ts';
import { trip } from './trip.ts';
import { shopping } from './shopping.ts';
import { packing } from './packing.ts';
import { settle } from './settle.ts';
import { fields } from './fields.ts';

export const VERIFIERS: Record<string, Verifier> = {
  trip: trip as unknown as Verifier,
  shopping: shopping as unknown as Verifier,
  packing: packing as unknown as Verifier,
  settle: settle as unknown as Verifier,
  fields: fields as unknown as Verifier,
};

export const VERIFIER_NAMES: readonly string[] = Object.keys(VERIFIERS);
