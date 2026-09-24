import Anthropic from '@anthropic-ai/sdk';
import type { CompletionRequest, CompletionResult, StopReason } from '../core/types.ts';
import { type AdapterContext, type ProviderAdapter, ProviderError, deepMerge, isAbortError, isRetryableStatus, parseRetryAfter } from './types.ts';

const STOP_MAP: Record<string, StopReason> = {
  end_turn: 'end',
  stop_sequence: 'end',
  max_tokens: 'max_tokens',
  model_context_window_exceeded: 'max_tokens',
  refusal: 'refusal',
};

/**
 * Anthropic Messages API via the official SDK, always streamed.
 *
 * Benchmark policy: no server-side refusal fallbacks are enabled, because a
 * fallback would let a *different* model answer on this contestant's behalf.
 * A refusal is recorded as a refusal.
 */
export function createAnthropicAdapter(ctx: AdapterContext): ProviderAdapter {
  const client = new Anthropic({
    apiKey: ctx.apiKey,
    baseURL: ctx.provider.baseUrl,
    maxRetries: 0, // the engine retries uniformly across providers
    timeout: 30 * 60 * 1000,
    defaultHeaders: ctx.provider.headers,
  });
  const opts = ctx.contestant.options ?? {};

  return {
    async complete(req: CompletionRequest): Promise<Omit<CompletionResult, 'retries'>> {
      const body: Record<string, unknown> = {
        model: ctx.contestant.model,
        max_tokens: req.maxOutputTokens,
        messages: req.messages.map((m) => ({ role: m.role, content: m.content })),
      };
      if (req.system) body.system = req.system;
      const thinking = Boolean(opts.effort && opts.effort !== 'none' && opts.effort !== 'minimal');
      if (thinking) {
        body.thinking = { type: 'adaptive' };
        body.output_config = { effort: opts.effort };
      }
      // Extended thinking is incompatible with a custom temperature, so it is only sent to non-thinking configurations.
      if (opts.supportsTemperature && !thinking && req.temperature !== undefined) body.temperature = opts.temperature ?? req.temperature;
      deepMerge(body, opts.extraBody);

      const startedAt = Date.now();
      let ttftMs: number | null = null;
      const markFirst = () => {
        if (ttftMs === null) ttftMs = Date.now() - startedAt;
      };
      try {
        const stream = client.messages.stream(body as unknown as Anthropic.MessageStreamParams, { signal: req.signal });
        stream.on('streamEvent', (event) => {
          if (event.type === 'content_block_delta') markFirst();
        });
        stream.on('text', (delta) => {
          markFirst();
          req.onDelta?.(delta);
        });
        const msg = await stream.finalMessage();
        const text = msg.content
          .filter((b): b is Anthropic.TextBlock => b.type === 'text')
          .map((b) => b.text)
          .join('');
        const u = msg.usage;
        const raw = msg.stop_reason ?? 'unknown';
        return {
          text,
          usage: {
            inputTokens: u.input_tokens ?? 0,
            outputTokens: u.output_tokens ?? 0,
            reasoningTokens: u.output_tokens_details?.thinking_tokens ?? 0,
            cachedInputTokens: u.cache_read_input_tokens ?? 0,
            cacheWriteTokens: u.cache_creation_input_tokens ?? 0,
          },
          startedAt,
          ttftMs,
          totalMs: Date.now() - startedAt,
          stopReason: STOP_MAP[raw] ?? 'other',
          rawStopReason: raw,
          servedModel: msg.model,
          requestId: stream.request_id ?? undefined,
        };
      } catch (err) {
        if (isAbortError(err) || req.signal?.aborted) throw err;
        if (err instanceof Anthropic.APIError) {
          const status = err.status as number | undefined;
          throw new ProviderError(`Anthropic ${status ?? 'network'} error: ${err.message}`, {
            status,
            retryable: err instanceof Anthropic.APIConnectionError || isRetryableStatus(status),
            retryAfterMs: parseRetryAfter(err.headers?.get?.('retry-after')),
            cause: err,
          });
        }
        throw new ProviderError(`Anthropic error: ${(err as Error).message}`, { retryable: true, cause: err });
      }
    },
  };
}

export async function listAnthropicModels(ctx: AdapterContext): Promise<string[]> {
  const client = new Anthropic({ apiKey: ctx.apiKey, baseURL: ctx.provider.baseUrl, maxRetries: 1 });
  const ids: string[] = [];
  for await (const m of client.models.list({ limit: 100 })) ids.push(m.id);
  return ids;
}
