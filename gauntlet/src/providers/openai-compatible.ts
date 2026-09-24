import OpenAI from 'openai';
import type { CompletionRequest, CompletionResult, StopReason } from '../core/types.ts';
import { type AdapterContext, type ProviderAdapter, ProviderError, deepMerge, isAbortError, isRetryableStatus, parseRetryAfter } from './types.ts';

const STOP_MAP: Record<string, StopReason> = {
  stop: 'end',
  length: 'max_tokens',
  content_filter: 'content_filter',
};

interface UsageLike {
  prompt_tokens?: number;
  completion_tokens?: number;
  prompt_tokens_details?: { cached_tokens?: number } | null;
  completion_tokens_details?: { reasoning_tokens?: number } | null;
  // DeepSeek reports cache hits separately.
  prompt_cache_hit_tokens?: number;
  prompt_cache_miss_tokens?: number;
}

/**
 * Chat Completions API — works for OpenAI, xAI, DeepSeek, Mistral, Groq,
 * Together, OpenRouter, Ollama and any other OpenAI-compatible endpoint.
 */
export function createOpenAICompatibleAdapter(ctx: AdapterContext): ProviderAdapter {
  const client = new OpenAI({
    apiKey: ctx.apiKey ?? 'not-needed',
    baseURL: ctx.provider.baseUrl,
    maxRetries: 0,
    timeout: 30 * 60 * 1000,
    defaultHeaders: ctx.provider.headers,
  });
  const opts = ctx.contestant.options ?? {};
  const isOpenAI = (ctx.provider.baseUrl ?? '').includes('api.openai.com');
  const maxTokensParam = ctx.provider.maxTokensParam ?? (isOpenAI ? 'max_completion_tokens' : 'max_tokens');

  return {
    async complete(req: CompletionRequest): Promise<Omit<CompletionResult, 'retries'>> {
      const messages: Array<{ role: 'system' | 'user' | 'assistant'; content: string }> = [];
      if (req.system) messages.push({ role: 'system', content: req.system });
      for (const m of req.messages) messages.push({ role: m.role, content: m.content });

      const body: Record<string, unknown> = {
        model: ctx.contestant.model,
        messages,
        stream: true,
        stream_options: { include_usage: true },
        [maxTokensParam]: req.maxOutputTokens,
      };
      if (opts.effort) body.reasoning_effort = opts.effort;
      // Never let the provider retain benchmark prompts/completions (no stored conversations, no memory between cases).
      if (isOpenAI) body.store = false;
      if (opts.supportsTemperature && req.temperature !== undefined) body.temperature = opts.temperature ?? req.temperature;
      deepMerge(body, opts.extraBody);

      const startedAt = Date.now();
      let ttftMs: number | null = null;
      let text = '';
      let refusal = '';
      let finish = 'unknown';
      let usage: UsageLike | undefined;
      let servedModel = ctx.contestant.model;
      let requestId: string | undefined;
      try {
        const { data: stream, response } = await client.chat.completions
          .create(body as unknown as OpenAI.ChatCompletionCreateParamsStreaming, { signal: req.signal })
          .withResponse();
        requestId = response.headers.get('x-request-id') ?? undefined;
        for await (const chunk of stream) {
          if (chunk.model) servedModel = chunk.model;
          if (chunk.usage) usage = chunk.usage as UsageLike;
          const choice = chunk.choices?.[0];
          if (!choice) continue;
          const delta = choice.delta as { content?: string | null; refusal?: string | null; reasoning_content?: string | null; reasoning?: string | null };
          if (delta?.reasoning_content || delta?.reasoning) {
            if (ttftMs === null) ttftMs = Date.now() - startedAt;
          }
          if (delta?.content) {
            if (ttftMs === null) ttftMs = Date.now() - startedAt;
            text += delta.content;
            req.onDelta?.(delta.content);
          }
          if (delta?.refusal) refusal += delta.refusal;
          if (choice.finish_reason) finish = choice.finish_reason;
        }
      } catch (err) {
        if (isAbortError(err) || req.signal?.aborted) throw err;
        if (err instanceof OpenAI.APIError) {
          const status = err.status as number | undefined;
          throw new ProviderError(`${ctx.provider.label} ${status ?? 'network'} error: ${err.message}`, {
            status,
            retryable: err instanceof OpenAI.APIConnectionError || isRetryableStatus(status),
            retryAfterMs: parseRetryAfter((err.headers as Headers | undefined)?.get?.('retry-after')),
            cause: err,
          });
        }
        throw new ProviderError(`${ctx.provider.label} error: ${(err as Error).message}`, { retryable: true, cause: err });
      }

      const cached = usage?.prompt_tokens_details?.cached_tokens ?? usage?.prompt_cache_hit_tokens ?? 0;
      const prompt = usage?.prompt_tokens ?? 0;
      let stopReason: StopReason = STOP_MAP[finish] ?? 'other';
      if (refusal && !text) {
        stopReason = 'refusal';
        text = refusal;
      }
      return {
        text,
        usage: {
          inputTokens: Math.max(0, prompt - cached),
          outputTokens: usage?.completion_tokens ?? 0,
          reasoningTokens: usage?.completion_tokens_details?.reasoning_tokens ?? 0,
          cachedInputTokens: cached,
          cacheWriteTokens: 0,
        },
        startedAt,
        ttftMs,
        totalMs: Date.now() - startedAt,
        stopReason,
        rawStopReason: refusal && !text ? 'refusal' : finish,
        servedModel,
        requestId,
      };
    },
  };
}

export async function listOpenAICompatibleModels(ctx: AdapterContext): Promise<string[]> {
  const client = new OpenAI({ apiKey: ctx.apiKey ?? 'not-needed', baseURL: ctx.provider.baseUrl, maxRetries: 1, defaultHeaders: ctx.provider.headers });
  const ids: string[] = [];
  for await (const m of client.models.list()) ids.push(m.id);
  return ids.sort();
}
