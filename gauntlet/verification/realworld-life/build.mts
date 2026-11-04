/**
 * Builds the Everyday Life pack (tests/life/*.json) from the generators and exact solvers in this folder.
 *
 *   node verification/realworld-life/build.mts          # write the test files
 *   node verification/realworld-life/build.mts --check  # fail if a committed file differs from what the generators produce
 *
 * Every answer key is computed here, never typed by hand. Re-running produces byte-identical files.
 */
import { readFileSync, writeFileSync, mkdirSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { testJson } from './common.mts';
import { buildTripCases } from './trip.mts';
import { buildShoppingCases } from './shopping.mts';
import { buildPackingCases } from './packing.mts';
import { buildBillCases } from './bills.mts';
import { buildTariffCases } from './tariff.mts';
import { buildLoanCases } from './loan.mts';

const here = dirname(fileURLToPath(import.meta.url));
export const LIFE_DIR = join(here, '..', '..', 'tests', 'life');

const META = { author: 'Gauntlet Real-World Pack', createdAt: '2026-11-04' };

export function tripTest(): Record<string, unknown> {
  const cases = buildTripCases();
  return {
    kind: 'prompt',
    id: 'life.trip-planner',
    version: '1.0.0',
    name: 'Weekend Away',
    category: 'life',
    description:
      'Plan the cheapest weekend trip to a wedding from a real-looking timetable of trains, coaches, sleepers, ferries and flights, with a railcard that has time limits, connection times, hotel check-in rules and late changes of plan. The itinerary is replayed by a checker and compared with the cheapest trip a computer search can find.',
    difficulty: 'hard',
    tags: ['real-world', 'everyday-life', 'planning', 'travel', 'optimisation', 'verifier'],
    hook: 'Get to the wedding on time, cheaply, without missing a connection.',
    maxOutputTokens: 32000,
    estimate: { inputTokens: 1950, outputTokens: 9000 },
    ...META,
    scorer: { type: 'verifier', verifier: 'trip' },
    cases: cases.map((c) => ({
      id: c.id,
      prompt: c.prompt,
      expected: { world: c.world, optimum: c.optimum, reference: c.reference, optimalPlans: c.optimalPlans },
      notes: c.notes,
    })),
  };
}

type Cases = Array<{ id: string; prompt: string; notes: string; world: unknown; reference: Record<string, unknown>; optimum?: number; optimalPlans?: number }>;

function promptTest(meta: Record<string, unknown>, verifier: string, cases: Cases): Record<string, unknown> {
  return {
    kind: 'prompt',
    ...meta,
    category: 'life',
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

export function billTest(): Record<string, unknown> {
  return promptTest(
    {
      id: 'life.bill-split',
      version: '1.0.0',
      name: 'Splitting the Bills',
      description:
        'Settle up a flat share: rent by room size, flatmates moving in and out mid-month, a Netflix share some opted out of, council tax with student and single-person rules, a boiler repair one person caused, standing orders and money already sent, and a corrected bill. Every balance and every transfer is checked to the penny against an exact calculator.',
      difficulty: 'hard',
      tags: ['real-world', 'everyday-life', 'money', 'arithmetic', 'verifier'],
      hook: 'Who owes whom, to the penny, when everyone moved in on different days?',
      maxOutputTokens: 32000,
      estimate: { inputTokens: 1500, outputTokens: 16000 },
    },
    'settle',
    buildBillCases(),
  );
}

export function tariffTest(): Record<string, unknown> {
  return promptTest(
    {
      id: 'life.tariff-chooser',
      version: '1.0.0',
      name: 'Cheapest Deal',
      description:
        'Pick the cheapest energy tariff or phone contract for a real household: monthly usage, standing charges, quarterly price-cap changes, Economy 7, prices quoted without VAT, exit fees, cashback, April price rises, half-price promos and roaming. Every option’s exact total and the winner are checked against an exact calculator.',
      difficulty: 'hard',
      tags: ['real-world', 'everyday-life', 'money', 'arithmetic', 'verifier'],
      hook: 'Which deal is really cheapest once every fee and price rise is counted?',
      maxOutputTokens: 32000,
      estimate: { inputTokens: 750, outputTokens: 12000 },
    },
    'fields',
    buildTariffCases(),
  );
}

export function shoppingTest(): Record<string, unknown> {
  return promptTest(
    {
      id: 'life.recipe-shopping',
      version: '1.0.0',
      name: 'Cook for Twelve',
      description:
        'Turn two to four recipes with different serving sizes and units (cups, ounces, pounds, pints, tablespoons) into the cheapest shopping basket: scale, convert, merge, take off the cupboard, then buy whole packs with multibuy offers. The basket is checked for every ingredient and compared with the cheapest basket found by trying every combination.',
      difficulty: 'hard',
      tags: ['real-world', 'everyday-life', 'cooking', 'optimisation', 'verifier'],
      hook: 'Scale the recipes, convert the cups, and spend as little as possible.',
      maxOutputTokens: 32000,
      estimate: { inputTokens: 1100, outputTokens: 12000 },
    },
    'shopping',
    buildShoppingCases(),
  );
}

export function loanTest(): Record<string, unknown> {
  return promptTest(
    {
      id: 'life.loan-overpayment',
      version: '1.0.0',
      name: 'Pay It Off Faster',
      description:
        'A loan or mortgage with overpayments: monthly interest rounded to the penny, a fix that ends and a recalculated payment, lump sums, a payment holiday and early repayment charges above the 10% allowance. When is it cleared, how much interest is paid and saved, and what do the charges come to? Checked against an exact month-by-month simulation.',
      difficulty: 'extreme',
      tags: ['real-world', 'everyday-life', 'money', 'long-calculation', 'verifier'],
      hook: 'Overpay the mortgage: when is it gone, and what does it save?',
      maxOutputTokens: 64000,
      estimate: { inputTokens: 700, outputTokens: 24000 },
    },
    'fields',
    buildLoanCases(),
  );
}

export function packingTest(): Record<string, unknown> {
  return promptTest(
    {
      id: 'life.packing-limits',
      version: '1.0.0',
      name: 'Pack for the Flight',
      description:
        'Decide what goes in the personal item, cabin bag and hold bags, what to wear and what to leave behind, under real airline rules: sizes, litres, weight limits and overweight fees, the 100 ml liquids rule, power banks and sharp items, and must-bring items. The plan is checked rule by rule and compared with the cheapest plan a computer search can find.',
      difficulty: 'hard',
      tags: ['real-world', 'everyday-life', 'travel', 'optimisation', 'verifier'],
      hook: 'Beat the baggage fees without breaking a single airline rule.',
      maxOutputTokens: 32000,
      estimate: { inputTokens: 1150, outputTokens: 10000 },
    },
    'packing',
    buildPackingCases(),
  );
}

export const BUILDS: Record<string, () => Record<string, unknown>> = {
  'trip-planner.json': tripTest,
  'bill-split.json': billTest,
  'tariff-chooser.json': tariffTest,
  'recipe-shopping.json': shoppingTest,
  'loan-overpayment.json': loanTest,
  'packing-limits.json': packingTest,
};

if (import.meta.url === `file://${process.argv[1]}`) {
  const check = process.argv.includes('--check');
  mkdirSync(LIFE_DIR, { recursive: true });
  let bad = 0;
  for (const [file, build] of Object.entries(BUILDS)) {
    const text = testJson(build());
    const path = join(LIFE_DIR, file);
    if (check) {
      let old = '';
      try {
        old = readFileSync(path, 'utf8');
      } catch {
        /* missing */
      }
      if (old !== text) {
        console.error(`DIFFERS: tests/life/${file}`);
        bad++;
      } else console.log(`ok: tests/life/${file}`);
    } else {
      writeFileSync(path, text);
      console.log(`wrote tests/life/${file} (${text.length} bytes)`);
    }
  }
  if (bad) process.exit(1);
}
