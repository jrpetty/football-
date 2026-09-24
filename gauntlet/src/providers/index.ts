import { getProvider, hasApiKey } from '../core/config.ts';
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

export function createAdapter(contestant: Contestant, provider: ProviderConfig = getProvider(contestant.provider)): ProviderAdapter {
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

/** Live list of model ids available to the configured key. */
export async function discoverModels(providerId: string): Promise<string[]> {
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
  const ctx = contextFor(provider, stub);
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
