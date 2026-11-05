/**
 * Names of the hidden tests of Engineering: Ladder, per level and in test order, as written by the test's generator
 * (verification/engineering-ladder/gen.mjs → labels.json). Used only when the count matches the recorded tests.
 */
import labels from '../../../../../verification/engineering-ladder/labels.json';

const LABELS = labels as Record<string, string[]>;

export function engineTestNames(caseId: string): string[] | undefined {
  return LABELS[caseId];
}
