/**
 * Demo mode (?mock=1) for the Business area: GET /api/business-ladder with clearly labelled DEMO scores.
 *
 * Only tests that really exist are used (the built Level-1 tests from tests/work/, loaded here so the demo
 * Test pages and Cost Planner can show them too); every planned test stays "coming soon". The demo models are
 * the mock's invented contestants, and the scores are generated from a fixed seed: they are never real results,
 * and the view carries `demo: true` so every page says so.
 */
import definitionJson from '../../../config/business-ladder.json';
import invoiceRead from '../../../tests/work/invoice-read.json';
import ticketTag from '../../../tests/work/ticket-tag.json';
import cvRead from '../../../tests/work/cv-read.json';
import emailActions from '../../../tests/work/email-actions.json';
import sheetLookup from '../../../tests/work/sheet-lookup.json';
import deliveryNote from '../../../tests/work/delivery-note.json';
import { buildBusinessLadderView, ladderTestInfo, type BusinessLadderView, type LadderDefinition, type ModelClusters } from '../../../src/core/business-ladder.ts';
import { explainerFor } from '../../../src/core/explainers.ts';
import { createRng, hashString } from '../../../src/core/rng.ts';
import type { PromptTest, TestDefinition } from '../../../src/core/types.ts';
import { CONTESTANTS } from './fixtures.ts';

const definition = definitionJson as unknown as LadderDefinition;

/** The real built Business tests, for the demo's test library. */
export const BUSINESS_MOCK_TESTS: TestDefinition[] = [invoiceRead, ticketTag, cvRead, emailActions, sheetLookup, deliveryNote] as unknown as TestDefinition[];

/** Invented demo skill per mock model (0..1). */
const SKILL: Record<string, number> = {
  'meridian-atlas-4-ultra': 0.97,
  'kestrel-kite-reasoner': 0.92,
  'helios-nova-3-pro': 0.84,
  'obsidian-sable-large': 0.76,
  'meridian-atlas-4-mini': 0.7,
  'helios-quill-flash': 0.62,
  'random-baseline': 0,
};

/** Demo per-case scores: harder (later) cases lose more fields; partial credit in twelfths, from a fixed seed. */
function demoClusters(modelId: string, skill: number): ModelClusters {
  const out: ModelClusters = {};
  for (const t of BUSINESS_MOCK_TESTS as PromptTest[]) {
    const rng = createRng(hashString(`${modelId}/${t.id}`));
    const bias = (rng.next() - 0.5) * 0.12;
    out[t.id] = Object.fromEntries(
      t.cases.map((c, i) => {
        const hard = i / Math.max(1, t.cases.length - 1);
        const p = skill === 0 ? 0 : Math.min(1, Math.max(0, skill + bias + 0.12 - hard * 0.34 + (rng.next() - 0.5) * 0.3));
        return [c.id, [Math.round(p * 12) / 12]];
      }),
    );
  }
  return out;
}

export function handleBusinessLadder(q: URLSearchParams): BusinessLadderView {
  const byId = new Map(BUSINESS_MOCK_TESTS.map((t) => [t.id, t]));
  const tests = ladderTestInfo(
    definition,
    (id) => {
      const d = byId.get(id);
      return d && d.kind === 'prompt' ? { name: d.name, difficulty: d.difficulty, cases: d.cases.length } : undefined;
    },
    explainerFor,
  );
  const models = CONTESTANTS.filter((c) => SKILL[c.id] !== undefined).map((c) => ({ id: c.id, label: c.label, color: c.color, vendor: c.vendor, clusters: demoClusters(c.id, SKILL[c.id]!) }));
  const compare = (q.get('models') ?? '').split(',').filter(Boolean);
  return buildBusinessLadderView({ definition, suiteId: 'all', tests, models, compare, demo: true });
}
