import type { CompletionRequest, CompletionResult, StopReason } from '../core/types.ts';
import { type AdapterContext, type ProviderAdapter, ProviderError, deepMerge, isAbortError, isRetryableStatus, parseRetryAfter } from './types.ts';

const STOP_MAP: Record<string, StopReason> = {
  STOP: 'end',
  MAX_TOKENS: 'max_tokens',
  SAFETY: 'refusal',
  PROHIBITED_CONTENT: 'refusal',
  BLOCKLIST: 'refusal',
  SPII: 'refusal',
  RECITATION: 'content_filter',
  IMAGE_SAFETY: 'refusal',
};

interface GeminiChunk {
  candidates?: Array<{
    content?: { parts?: Array<{ text?: string; thought?: boolean }> };
    finishReason?: string;
  }>;
  usageMetadata?: {
    promptTokenCount?: number;
    candidatesTokenCount?: number;
    thoughtsTokenCount?: number;
    cachedContentTokenCount?: number;
  };
  promptFeedback?: { blockReason?: string };
  modelVersion?: string;
  responseId?: string;
}

const EFFORT_TO_LEVEL: Record<string, string> = {
  minimal: 'minimal',
  low: 'low',
  medium: 'medium',
  high: 'high',
  xhigh: 'high',
  max: 'high',
};

/** Google Gemini `streamGenerateContent` over SSE (REST, no SDK dependency). */
export function createGeminiAdapter(ctx: AdapterContext): ProviderAdapter {
  const base = (ctx.provider.baseUrl ?? 'https://generativelanguage.googleapis.com/v1beta').replace(/\/$/, '');
  const opts = ctx.contestant.options ?? {};

  return {
    async complete(req: CompletionRequest): Promise<Omit<CompletionResult, 'retries'>> {
      const generationConfig: Record<string, unknown> = { maxOutputTokens: req.maxOutputTokens };
      if (opts.supportsTemperature && req.temperature !== undefined) generationConfig.temperature = opts.temperature ?? req.temperature;
      if (opts.effort && EFFORT_TO_LEVEL[opts.effort]) generationConfig.thinkingConfig = { thinkingLevel: EFFORT_TO_LEVEL[opts.effort] };
      const body: Record<string, unknown> = {
        contents: req.messages.map((m) => ({ role: m.role === 'assistant' ? 'model' : 'user', parts: [{ text: m.content }] })),
        generationConfig,
      };
      if (req.system) body.systemInstruction = { parts: [{ text: req.system }] };
      deepMerge(body, opts.extraBody);

      const url = `${base}/models/${encodeURIComponent(ctx.contestant.model)}:streamGenerateContent?alt=sse`;
      const startedAt = Date.now();
      let res: Response;
      try {
        res = await fetch(url, {
          method: 'POST',
          headers: { 'content-type': 'application/json', 'x-goog-api-key': ctx.apiKey ?? '', ...(ctx.provider.headers ?? {}) },
          body: JSON.stringify(body),
          signal: req.signal,
        });
      } catch (err) {
        if (isAbortError(err) || req.signal?.aborted) throw err;
        throw new ProviderError(`Gemini network error: ${(err as Error).message}`, { retryable: true, cause: err });
      }
      if (!res.ok || !res.body) {
        const detail = await res.text().catch(() => '');
        throw new ProviderError(`Gemini ${res.status} error: ${detail.slice(0, 500)}`, {
          status: res.status,
          retryable: isRetryableStatus(res.status),
          retryAfterMs: parseRetryAfter(res.headers.get('retry-after')),
        });
      }

      let ttftMs: number | null = null;
      let text = '';
      let finish = 'unknown';
      let usage: GeminiChunk['usageMetadata'];
      let servedModel = ctx.contestant.model;
      let requestId: string | undefined;
      let blockReason: string | undefined;

      const decoder = new TextDecoder();
      let buffer = '';
      try {
        for await (const bytes of res.body as unknown as AsyncIterable<Uint8Array>) {
          buffer += decoder.decode(bytes, { stream: true });
          let idx: number;
          while ((idx = buffer.indexOf('\n')) >= 0) {
            const line = buffer.slice(0, idx).trimEnd();
            buffer = buffer.slice(idx + 1);
            if (!line.startsWith('data:')) continue;
            const payload = line.slice(5).trim();
            if (!payload || payload === '[DONE]') continue;
            let chunk: GeminiChunk;
            try {
              chunk = JSON.parse(payload) as GeminiChunk;
            } catch {
              continue;
            }
            if (chunk.modelVersion) servedModel = chunk.modelVersion;
            if (chunk.responseId) requestId = chunk.responseId;
            if (chunk.usageMetadata) usage = chunk.usageMetadata;
            if (chunk.promptFeedback?.blockReason) blockReason = chunk.promptFeedback.blockReason;
            const cand = chunk.candidates?.[0];
            for (const part of cand?.content?.parts ?? []) {
              if (ttftMs === null && (part.text || part.thought)) ttftMs = Date.now() - startedAt;
              if (part.text && !part.thought) {
                text += part.text;
                req.onDelta?.(part.text);
              }
            }
            if (cand?.finishReason) finish = cand.finishReason;
          }
        }
      } catch (err) {
        if (isAbortError(err) || req.signal?.aborted) throw err;
        throw new ProviderError(`Gemini stream error: ${(err as Error).message}`, { retryable: true, cause: err });
      }

      const cached = usage?.cachedContentTokenCount ?? 0;
      const thoughts = usage?.thoughtsTokenCount ?? 0;
      let stopReason: StopReason = STOP_MAP[finish] ?? 'other';
      let raw = finish;
      if (blockReason) {
        stopReason = 'refusal';
        raw = `blocked:${blockReason}`;
      }
      return {
        text,
        usage: {
          inputTokens: Math.max(0, (usage?.promptTokenCount ?? 0) - cached),
          outputTokens: (usage?.candidatesTokenCount ?? 0) + thoughts,
          reasoningTokens: thoughts,
          cachedInputTokens: cached,
          cacheWriteTokens: 0,
        },
        startedAt,
        ttftMs,
        totalMs: Date.now() - startedAt,
        stopReason,
        rawStopReason: raw,
        servedModel,
        requestId,
      };
    },
  };
}

export async function listGeminiModels(ctx: AdapterContext): Promise<string[]> {
  const base = (ctx.provider.baseUrl ?? 'https://generativelanguage.googleapis.com/v1beta').replace(/\/$/, '');
  const ids: string[] = [];
  let pageToken = '';
  for (let page = 0; page < 10; page++) {
    const res = await fetch(`${base}/models?pageSize=1000${pageToken ? `&pageToken=${pageToken}` : ''}`, {
      headers: { 'x-goog-api-key': ctx.apiKey ?? '' },
    });
    if (!res.ok) throw new ProviderError(`Gemini ${res.status}: ${(await res.text()).slice(0, 300)}`, { status: res.status, retryable: false });
    const json = (await res.json()) as { models?: Array<{ name: string; supportedGenerationMethods?: string[] }>; nextPageToken?: string };
    for (const m of json.models ?? []) {
      if (!m.supportedGenerationMethods || m.supportedGenerationMethods.includes('generateContent')) ids.push(m.name.replace(/^models\//, ''));
    }
    if (!json.nextPageToken) break;
    pageToken = encodeURIComponent(json.nextPageToken);
  }
  return ids.sort();
}
