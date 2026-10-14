import { test } from 'node:test';
import assert from 'node:assert/strict';
import { computeCost, effectivePricing } from '../src/core/cost.ts';
import type { Pricing } from '../src/core/types.ts';

// Claude Haiku 5.5's two rate cards: $0.10 / $0.50 up to 100K prompt tokens, $0.50 / $2.50 above.
const haiku55: Pricing = {
  inputPerM: 0.1,
  outputPerM: 0.5,
  cachedInputPerM: 0.01,
  cacheWritePerM: 0.125,
  longPrompt: { aboveTokens: 100_000, inputPerM: 0.5, outputPerM: 2.5, cachedInputPerM: 0.05, cacheWritePerM: 0.625 },
};
const usage = (input: number, output: number, cached = 0, write = 0) => ({ inputTokens: input, outputTokens: output, reasoningTokens: 0, cachedInputTokens: cached, cacheWriteTokens: write });

test('a prompt of 100K tokens or fewer uses the base rate card', () => {
  assert.equal(computeCost(usage(100_000, 10_000), haiku55), (100_000 * 0.1 + 10_000 * 0.5) / 1e6);
});

test('a longer prompt (cached and cache-write tokens count too) uses the long-prompt card for every token', () => {
  assert.equal(computeCost(usage(60_000, 10_000, 30_000, 20_000), haiku55), (60_000 * 0.5 + 30_000 * 0.05 + 20_000 * 0.625 + 10_000 * 2.5) / 1e6);
});

test('models without a long-prompt card are unchanged', () => {
  const flat: Pricing = { inputPerM: 2, outputPerM: 10 };
  assert.equal(effectivePricing(flat, 900_000), flat);
  assert.equal(computeCost(usage(900_000, 1_000), flat), (900_000 * 2 + 1_000 * 10) / 1e6);
});
