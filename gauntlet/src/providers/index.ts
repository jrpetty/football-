import { getProvider, hasApiKey } from '../core/config.ts';
import { routeFor } from '../core/openrouter.ts';
import type { Contestant, ProviderConfig } from '../core/types.ts';
import { createAnthropicAdapter, listAnthropicModels } from './anthropic.ts';
import { createGeminiAdapter, listGeminiModels } from './gemini.ts';
import { createMockAdapter } from './mock.ts';
import { createManualAdapter } from './manual.ts';
import { createOpenAICompatibleAdapter, listOpenAICompatibleModels } from './openai-compatible.ts';
import type { AdapterContext, ProviderAdapter } from './types.ts';

export { ProviderError } from './types.ts';
export type { ProviderAdapter } from './types.ts';

function contextFor(provider: ProviderConfig, contestant: Contestant): AdapterContext {
  if (!hasApiKey(provider)) {
    throw new Error(`Missing API key: set ${provider.apiKeyEnv} to run ${contestant.label} (${provider.label}).`);
  }
  return { provider, contestant, apiKey: provider.apiKeyEnv ? process.env[provider.apiKeyEnv] : undefined };
}

/**
 * `noRoute`: never switch to OpenRouter here. Benchmark runs and tournaments resolve routes when they are planned (so
 * the manifest records them) and pass this, so a resumed run can't silently change how a model is reached.
 */
export function createAdapter(contestant: Contestant, provider: ProviderConfig = getProvider(contestant.provider), opts: { noRoute?: boolean } = {}): ProviderAdapter {
  // "One key for everything": no key for this company but an OpenRouter key → call the model through OpenRouter.
  if (!hasApiKey(provider) && !opts.noRoute) {
    const routed = routeFor(contestant);
    if (routed) return createAdapter(routed, getProvider(routed.provider));
  }
  const ctx = contextFor(provider, contestant);
  switch (provider.type) {
    case 'anthropic':
      return createAnthropicAdapter(ctx);
    case 'openai-compatible':
      return createOpenAICompatibleAdapter(ctx);
    case 'gemini':
      return createGeminiAdapter(ctx);
    case 'mock':
      return createMockAdapter(ctx);
    case 'manual':
      return createManualAdapter(ctx);
  }
}

/** List a provider's models (free on every supported API). `apiKey` checks a candidate key without applying it. */
export async function discoverModels(providerId: string, apiKey?: string): Promise<string[]> {
  const provider = getProvider(providerId);
  const stub: Contestant = {
    id: 'discover',
    label: 'discover',
    vendor: '',
    provider: providerId,
    model: '',
    color: '#000000',
    enabled: true,
    pricing: { inputPerM: 0, outputPerM: 0 },
  };
  const ctx = apiKey !== undefined ? { provider, contestant: stub, apiKey } : contextFor(provider, stub);
  switch (provider.type) {
    case 'anthropic':
      return listAnthropicModels(ctx);
    case 'openai-compatible':
      return listOpenAICompatibleModels(ctx);
    case 'gemini':
      return listGeminiModels(ctx);
    case 'mock':
      return ['random'];
    case 'manual':
      return [];
  }
}
