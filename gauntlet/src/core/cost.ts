import type { Pricing, TokenUsage } from './types.ts';

/**
 * USD cost of one call. Output price applies to all output tokens, which by
 * provider convention already include reasoning/thinking tokens.
 */
/** The rate card that applies to one request whose prompt is `promptTokens` long (see Pricing.longPrompt). */
export function effectivePricing(pricing: Pricing, promptTokens: number): Pricing {
  const long = pricing.longPrompt;
  if (!long || !(promptTokens > long.aboveTokens)) return pricing;
  return { ...pricing, inputPerM: long.inputPerM, outputPerM: long.outputPerM, cachedInputPerM: long.cachedInputPerM, cacheWritePerM: long.cacheWritePerM };
}

export function computeCost(usage: TokenUsage, basePricing: Pricing): number {
  const pricing = effectivePricing(basePricing, usage.inputTokens + usage.cachedInputTokens + usage.cacheWriteTokens);
  const cachedRate = pricing.cachedInputPerM ?? pricing.inputPerM;
  const writeRate = pricing.cacheWritePerM ?? pricing.inputPerM;
  const cost =
    (usage.inputTokens * pricing.inputPerM +
      usage.cachedInputTokens * cachedRate +
      usage.cacheWriteTokens * writeRate +
      usage.outputTokens * pricing.outputPerM) /
    1_000_000;
  return Math.round(cost * 1e8) / 1e8;
}

export function emptyUsage(): TokenUsage {
  return { inputTokens: 0, outputTokens: 0, reasoningTokens: 0, cachedInputTokens: 0, cacheWriteTokens: 0 };
}

export function addUsage(a: TokenUsage, b: TokenUsage): TokenUsage {
  return {
    inputTokens: a.inputTokens + b.inputTokens,
    outputTokens: a.outputTokens + b.outputTokens,
    reasoningTokens: a.reasoningTokens + b.reasoningTokens,
    cachedInputTokens: a.cachedInputTokens + b.cachedInputTokens,
    cacheWriteTokens: a.cacheWriteTokens + b.cacheWriteTokens,
  };
}
