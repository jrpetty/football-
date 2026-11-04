/**
 * Builds the operations & workflow pack (tests/work/{stock-reorder,shift-rota,delivery-routes,support-replies,
 * board-report}.json) from the generators and exact solvers in this folder.
 *
 *   node verification/realworld-ops/build.mts          # write the test files
 *   node verification/realworld-ops/build.mts --check  # fail if a committed file differs from what the generators produce
 *
 * Every answer key is computed here, never typed by hand. Re-running produces byte-identical files.
 */
import { readFileSync, writeFileSync, mkdirSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { testJson } from './common.mts';
import { buildReorderCases } from './reorder.mts';
import { buildRotaCases } from './rota.mts';
import { buildRoutesCases } from './routes.mts';
import { buildReplyCases } from './replies.mts';
import { BOARD_WEIGHTS, buildBoardCases } from './board.mts';

const here = dirname(fileURLToPath(import.meta.url));
export const WORK_DIR = join(here, '..', '..', 'tests', 'work');

const META = { author: 'Gauntlet Real-World Pack', createdAt: '2026-11-04' };

/** Input tokens from prompt length (about 3.6 characters a token for this mix of prose, tables and numbers). */
function inputTokens(prompts: string[]): number {
  return Math.round(prompts.reduce((a, p) => a + p.length, 0) / prompts.length / 3.6 / 10) * 10;
}

type VCase = { id: string; prompt: string; notes: string; world: unknown; reference: Record<string, unknown>; optimum?: number; optimalPlans?: number };

function verifierTest(meta: Record<string, unknown>, verifier: string, cases: VCase[], outputTokens: number): Record<string, unknown> {
  return {
    kind: 'prompt',
    ...meta,
    category: 'work',
    maxOutputTokens: 32000,
    estimate: { inputTokens: inputTokens(cases.map((c) => c.prompt)), outputTokens },
    ...META,
    scorer: { type: 'verifier', verifier },
    cases: cases.map((c) => ({
      id: c.id,
      prompt: c.prompt,
      expected: { world: c.world, ...(c.optimum !== undefined ? { optimum: c.optimum } : {}), reference: c.reference, ...(c.optimalPlans !== undefined ? { optimalPlans: c.optimalPlans } : {}) },
      notes: c.notes,
    })),
  };
}

export function reorderTest(): Record<string, unknown> {
  return verifierTest(
    {
      id: 'work.stock-reorder',
      version: '1.0.0',
      name: 'What Do We Reorder?',
      description:
        'Business Ladder, Operations, Level 2 (Automate a step). The Monday stock order for a café or a hardware shop: 8 to 32 items from up to four suppliers, with sales history (promotion weeks, bank holidays, a closed week), lead times, delivery cycles and a supplier shutdown, open purchase orders, minimum order quantities, case sizes, storage limits, bulk prices, delivery charges, supplier minimum orders, a cash limit and a safety-stock rule stated exactly. The order is replayed week by week by a checker and compared with the cheapest order an exact search can find.',
      difficulty: 'hard',
      tags: ['business-ladder', 'operations', 'level-2', 'inventory', 'optimisation', 'verifier'],
      hook: 'Thirty items, four suppliers, one cash limit: order exactly enough, as cheaply as possible.',
    },
    'reorder',
    buildReorderCases(),
    9000,
  );
}

export function rotaTest(): Record<string, unknown> {
  return verifierTest(
    {
      id: 'work.shift-rota',
      version: '1.0.0',
      name: 'Build the Rota',
      description:
        'Business Ladder, Operations, Level 3 (Run a workflow). A week’s rota for a pub, a care home or a warehouse: 5 to 12 staff with contracted hours, pay rates, skills (keyholder, personal licence, medication, first aid, forklift), availability and leave, 11 hours’ rest between shifts (12 for under-18s, counting last week’s last shift), a 48-hour cap, under-18 limits, exact cover per shift, a weekend fairness rule and a late message. The rota is checked rule by rule and its wage cost compared with the cheapest legal rota an exact branch-and-bound search can prove.',
      difficulty: 'hard',
      tags: ['business-ladder', 'operations', 'level-3', 'scheduling', 'optimisation', 'verifier'],
      hook: 'Cover every shift, break no working-time rule, and spend the least on wages.',
    },
    'rota',
    buildRotaCases(),
    10000,
  );
}

export function routesTest(): Record<string, unknown> {
  return verifierTest(
    {
      id: 'work.delivery-routes',
      version: '1.0.0',
      name: 'Plan the Van Routes',
      description:
        'Business Ladder, Operations, Level 4 (Agent). Tomorrow’s delivery routes for a Derbyshire food wholesaler or a Worcestershire florist supplier: 2 to 4 vans with weight limits, 8 to 15 drops with delivery windows and unloading times, a full travel-time matrix, depot hours, a drivers’-hours break rule (45 minutes before more than 4 h 30 min of driving) and a late message with a road closure or a cancelled drop. Every route is replayed minute by minute and the total van time compared with the least an exact search can prove.',
      difficulty: 'extreme',
      tags: ['business-ladder', 'operations', 'level-4', 'routing', 'optimisation', 'verifier'],
      hook: 'Every drop in its window, every driver’s break taken, and the vans home as early as possible.',
    },
    'routes',
    buildRoutesCases(),
    12000,
  );
}

export function repliesTest(): Record<string, unknown> {
  return verifierTest(
    {
      id: 'work.support-replies',
      version: '1.0.0',
      name: 'Reply Within Policy',
      description:
        'Business Ladder, Customer Service, Level 3 (Run a workflow). A customer email, the order record and the online shop’s written returns policy: decide (refund, replace, refuse, ask for photos, investigate or escalate), work out the refund to the penny (discount codes, the returns-label fee, delivery charges, working days) and write the reply. The header is checked field by field against a policy engine and the reply text by the house rules: no AI judge.',
      difficulty: 'hard',
      tags: ['business-ladder', 'customer-service', 'level-3', 'policy', 'writing', 'verifier'],
      hook: 'An angry email, a strict policy: the right decision, the right refund and a reply that breaks no rule.',
    },
    'reply',
    buildReplyCases(),
    3000,
  );
}

export function boardTest(): Record<string, unknown> {
  const cases = buildBoardCases();
  return {
    kind: 'prompt',
    id: 'work.board-report',
    version: '1.0.0',
    name: 'Numbers for the Board',
    category: 'work',
    description:
      'Business Ladder, Data & Reporting, Level 3 (Run a workflow). Three messy exports (orders, refunds, people and payroll) and the finance director’s memo defining every KPI: VAT in some amounts and not others, refunds by processing date, three spellings of “cancelled”, test orders, duplicate rows, a contractor, starters and leavers, a payroll re-run, a restated prior quarter and a late re-keying note. Twelve KPIs for the quarter, each checked exactly to its stated rounding.',
    difficulty: 'hard',
    tags: ['business-ladder', 'data-reporting', 'level-3', 'finance', 'kpi', 'json'],
    hook: 'Three exports, one memo, twelve numbers the board will read. Every one exact.',
    maxOutputTokens: 32000,
    estimate: { inputTokens: inputTokens(cases.map((c) => c.prompt)), outputTokens: 12000 },
    ...META,
    // Exact to the stated rounding: the relative tolerance only absorbs floating-point noise (1e-9 of £1m is 0.1p).
    scorer: { type: 'json', lenient: true, numberTolerance: 1e-9, weights: BOARD_WEIGHTS },
    cases: cases.map((c) => ({ id: c.id, prompt: c.prompt, expected: c.expected, notes: c.notes })),
  };
}

export const BUILDS: Record<string, () => Record<string, unknown>> = {
  'stock-reorder.json': reorderTest,
  'shift-rota.json': rotaTest,
  'delivery-routes.json': routesTest,
  'support-replies.json': repliesTest,
  'board-report.json': boardTest,
};

if (import.meta.url === `file://${process.argv[1]}`) {
  const check = process.argv.includes('--check');
  mkdirSync(WORK_DIR, { recursive: true });
  let bad = 0;
  for (const [file, build] of Object.entries(BUILDS)) {
    const t0 = Date.now();
    const text = testJson(build());
    const path = join(WORK_DIR, file);
    if (check) {
      let old = '';
      try {
        old = readFileSync(path, 'utf8');
      } catch {
        /* missing */
      }
      if (old !== text) {
        console.error(`DIFFERS: tests/work/${file}`);
        bad++;
      } else console.log(`ok: tests/work/${file} (${Date.now() - t0} ms)`);
    } else {
      writeFileSync(path, text);
      console.log(`wrote tests/work/${file} (${text.length} bytes, ${Date.now() - t0} ms)`);
    }
  }
  if (bad) process.exit(1);
}
