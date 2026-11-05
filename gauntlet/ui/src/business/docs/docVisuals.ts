/**
 * Every Business test that is drawn as its own paperwork, by test id: the close-ups (Level close-up for the
 * ladders, Case close-up for the other tests) take their beats from here. Each builder's registry is spread in.
 */
import type { DocVisual } from './customers-data/parts.tsx';
import { CUSTOMERS_DATA_VISUALS } from './customers-data/registry.ts';

export const BUSINESS_DOC_VISUALS: Record<string, DocVisual> = { ...CUSTOMERS_DATA_VISUALS };

export function docVisualFor(testId: string): DocVisual | null {
  return BUSINESS_DOC_VISUALS[testId] ?? null;
}
