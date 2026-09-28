import { createRng, hashString } from '../core/rng.ts';
import { plannedImageSettings } from '../core/image-output.ts';
import { noisePng } from '../core/png.ts';
import { imageInfo } from '../core/vision.ts';
import type { GeneratedImage, ImageGenRequest, ImageGenResult, StopReason, TokenUsage } from '../core/types.ts';
import { type AdapterContext, ProviderError, isAbortError, isRetryableStatus, parseRetryAfter } from './types.ts';

/**
 * Image generation for The Gallery Masterpiece, in the three wire formats that matter:
 *  - OpenAI Images API: POST {baseUrl}/images/generations (gpt-image models; xAI's grok image models use the
 *    same shape, and so can any other OpenAI-compatible provider that offers it);
 *  - Google Gemini: generateContent with responseModalities TEXT + IMAGE (Gemini image models);
 *  - the Random Baseline: a deterministic noise picture, no network.
 * Manual (copy & paste) pictures are handled by src/providers/manual.ts.
 *
 * A content-policy refusal is returned as a result with stopReason "refusal" (it scores 0 like any refusal);
 * a request the provider rejects as malformed (e.g. an unsupported size) throws a non-retryable ProviderError
 * that says which setting to change.
 */

const EMPTY_USAGE: TokenUsage = { inputTokens: 0, outputTokens: 0, reasoningTokens: 0, cachedInputTokens: 0, cacheWriteTokens: 0 };

/** Base64 bytes → a GeneratedImage (null when they are not a PNG or JPEG). */
export function toGeneratedImage(b64: string): GeneratedImage | null {
  const clean = b64.replace(/^data:[^,]*,/, '').trim();
  if (!clean) return null;
  const buf = Buffer.from(clean, 'base64');
  const info = imageInfo(buf);
  if (!info) return null;
  return { mediaType: info.mediaType, data: buf.toString('base64'), width: info.width, height: info.height, bytes: buf.length };
}

const REFUSAL_RE = /moderation|content[_ -]?policy|safety system|safety_violations|rejected by (our|the) safety|not allowed by our safety/i;

function refusal(text: string, raw: string, startedAt: number, servedModel: string, extra: Partial<ImageGenResult> = {}): ImageGenResult {
  return { images: [], text, usage: { ...EMPTY_USAGE }, startedAt, totalMs: Date.now() - startedAt, stopReason: 'refusal', rawStopReason: raw, servedModel, ...extra };
}

async function readError(res: Response): Promise<{ message: string; code?: string; param?: string }> {
  const raw = await res.text().catch(() => '');
  try {
    const j = JSON.parse(raw) as { error?: { message?: string; code?: string; param?: string; status?: string } | string };
    if (typeof j.error === 'string') return { message: j.error };
    return { message: j.error?.message ?? raw.slice(0, 400), code: j.error?.code ?? j.error?.status, param: j.error?.param };
  } catch {
    return { message: raw.slice(0, 400) };
  }
}

// ─────────────────────────────── OpenAI Images API ───────────────────────────────

interface OpenAIImagesResponse {
  data?: Array<{ b64_json?: string; url?: string; revised_prompt?: string }>;
  usage?: { input_tokens?: number; output_tokens?: number; input_tokens_details?: { text_tokens?: number; image_tokens?: number } };
  model?: string;
}

/** POST /images/generations. Size and quality come from `imageOptions` (defaults: 1536x1024, high on api.openai.com). */
export async function openAIImageGenerate(ctx: AdapterContext, req: ImageGenRequest): Promise<ImageGenResult> {
  const base = (ctx.provider.baseUrl ?? 'https://api.openai.com/v1').replace(/\/$/, '');
  const isOpenAI = base.includes('api.openai.com');
  const model = ctx.contestant.model;
  const opts = ctx.contestant.imageOptions ?? {};
  const { size, quality } = plannedImageSettings(ctx.contestant, base, 'openai-compatible');
  const body: Record<string, unknown> = { model, prompt: req.prompt, n: 1 };
  if (size) body.size = size;
  if (quality) body.quality = quality;
  // gpt-image models always return base64 and reject response_format; DALL·E and xAI need it to return bytes.
  const responseFormat = opts.responseFormat === null ? undefined : (opts.responseFormat ?? (isOpenAI && !/^dall-e/i.test(model) ? undefined : 'b64_json'));
  if (responseFormat) body.response_format = responseFormat;

  const startedAt = Date.now();
  let res: Response;
  try {
    res = await fetch(`${base}/images/generations`, {
      method: 'POST',
      headers: { 'content-type': 'application/json', authorization: `Bearer ${ctx.apiKey ?? ''}`, ...(ctx.provider.headers ?? {}) },
      body: JSON.stringify(body),
      signal: req.signal,
    });
  } catch (err) {
    if (isAbortError(err) || req.signal?.aborted) throw err;
    throw new ProviderError(`${ctx.provider.label} network error: ${(err as Error).message}`, { retryable: true, cause: err });
  }
  const requestId = res.headers.get('x-request-id') ?? undefined;
  if (!res.ok) {
    const e = await readError(res);
    if (res.status === 400 && (REFUSAL_RE.test(e.code ?? '') || REFUSAL_RE.test(e.message))) {
      return refusal(e.message, `refused:${e.code ?? 'content_policy'}`, startedAt, model, { requestId, size, quality });
    }
    if (res.status === 400 && (e.param === 'size' || e.param === 'quality' || /\b(size|quality)\b/i.test(e.message))) {
      throw new ProviderError(
        `${ctx.provider.label} rejected the picture settings (size ${size ?? 'not sent'}, quality ${quality ?? 'not sent'}): ${e.message}. Set "imageOptions" on this model in config/models.json (size / quality, or null to leave one out).`,
        { status: 400, retryable: false },
      );
    }
    throw new ProviderError(`${ctx.provider.label} ${res.status} error: ${e.message}`, { status: res.status, retryable: isRetryableStatus(res.status), retryAfterMs: parseRetryAfter(res.headers.get('retry-after')) });
  }
  let json: OpenAIImagesResponse;
  try {
    json = (await res.json()) as OpenAIImagesResponse;
  } catch (err) {
    throw new ProviderError(`${ctx.provider.label} returned an unreadable image response`, { retryable: true, cause: err });
  }
  const first = json.data?.[0];
  let image = first?.b64_json ? toGeneratedImage(first.b64_json) : null;
  if (!image && first?.url) image = await fetchImageUrl(first.url, req.signal);
  const u = json.usage;
  const usage: TokenUsage = { inputTokens: u?.input_tokens ?? 0, outputTokens: u?.output_tokens ?? 0, reasoningTokens: 0, cachedInputTokens: 0, cacheWriteTokens: 0 };
  return {
    images: image ? [image] : [],
    text: first?.revised_prompt ?? '',
    usage,
    startedAt,
    totalMs: Date.now() - startedAt,
    stopReason: image ? 'end' : 'other',
    rawStopReason: image ? 'image' : first ? 'unreadable_image' : 'no_image',
    servedModel: json.model ?? model,
    requestId,
    size,
    quality,
  };
}

/** Some providers return a short-lived URL instead of bytes. */
async function fetchImageUrl(url: string, signal?: AbortSignal): Promise<GeneratedImage | null> {
  if (!/^https?:\/\//i.test(url)) return url.startsWith('data:') ? toGeneratedImage(url) : null;
  try {
    const r = await fetch(url, { signal });
    if (!r.ok) return null;
    return toGeneratedImage(Buffer.from(await r.arrayBuffer()).toString('base64'));
  } catch (err) {
    if (isAbortError(err) || signal?.aborted) throw err;
    return null;
  }
}

// ─────────────────────────────── Gemini ───────────────────────────────

interface GeminiImageResponse {
  candidates?: Array<{ content?: { parts?: Array<{ text?: string; thought?: boolean; inlineData?: { mimeType?: string; data?: string }; inline_data?: { mime_type?: string; data?: string } }> }; finishReason?: string }>;
  promptFeedback?: { blockReason?: string };
  usageMetadata?: { promptTokenCount?: number; candidatesTokenCount?: number; thoughtsTokenCount?: number };
  modelVersion?: string;
  responseId?: string;
}

const GEMINI_REFUSALS = new Set(['SAFETY', 'IMAGE_SAFETY', 'PROHIBITED_CONTENT', 'IMAGE_PROHIBITED_CONTENT', 'BLOCKLIST', 'SPII', 'RECITATION', 'IMAGE_RECITATION']);

/** generateContent with responseModalities ["TEXT", "IMAGE"] and imageConfig.aspectRatio. */
export async function geminiImageGenerate(ctx: AdapterContext, req: ImageGenRequest): Promise<ImageGenResult> {
  const base = (ctx.provider.baseUrl ?? 'https://generativelanguage.googleapis.com/v1beta').replace(/\/$/, '');
  const model = ctx.contestant.model;
  const body = {
    contents: [{ role: 'user', parts: [{ text: req.prompt }] }],
    generationConfig: { responseModalities: ['TEXT', 'IMAGE'], imageConfig: { aspectRatio: req.aspectRatio } },
  };
  const startedAt = Date.now();
  let res: Response;
  try {
    res = await fetch(`${base}/models/${encodeURIComponent(model)}:generateContent`, {
      method: 'POST',
      headers: { 'content-type': 'application/json', 'x-goog-api-key': ctx.apiKey ?? '', ...(ctx.provider.headers ?? {}) },
      body: JSON.stringify(body),
      signal: req.signal,
    });
  } catch (err) {
    if (isAbortError(err) || req.signal?.aborted) throw err;
    throw new ProviderError(`Gemini network error: ${(err as Error).message}`, { retryable: true, cause: err });
  }
  if (!res.ok) {
    const e = await readError(res);
    if (res.status === 400 && /aspect|image ?config|size/i.test(e.message)) {
      throw new ProviderError(`Gemini rejected the picture settings (aspect ratio ${req.aspectRatio}): ${e.message}. This model may not support that aspect ratio.`, { status: 400, retryable: false });
    }
    throw new ProviderError(`Gemini ${res.status} error: ${e.message}`, { status: res.status, retryable: isRetryableStatus(res.status), retryAfterMs: parseRetryAfter(res.headers.get('retry-after')) });
  }
  let json: GeminiImageResponse;
  try {
    json = (await res.json()) as GeminiImageResponse;
  } catch (err) {
    throw new ProviderError('Gemini returned an unreadable image response', { retryable: true, cause: err });
  }
  const cand = json.candidates?.[0];
  let text = '';
  let image: GeneratedImage | null = null;
  for (const p of cand?.content?.parts ?? []) {
    if (p.text && !p.thought) text += p.text;
    const data = p.inlineData?.data ?? p.inline_data?.data;
    if (data && !image) image = toGeneratedImage(data);
  }
  const u = json.usageMetadata;
  const usage: TokenUsage = { inputTokens: u?.promptTokenCount ?? 0, outputTokens: (u?.candidatesTokenCount ?? 0) + (u?.thoughtsTokenCount ?? 0), reasoningTokens: u?.thoughtsTokenCount ?? 0, cachedInputTokens: 0, cacheWriteTokens: 0 };
  const finish = cand?.finishReason ?? 'unknown';
  const blocked = json.promptFeedback?.blockReason;
  const servedModel = json.modelVersion ?? model;
  if (!image && (blocked || GEMINI_REFUSALS.has(finish))) {
    return { ...refusal(text || `Blocked by Gemini (${blocked ?? finish})`, blocked ? `blocked:${blocked}` : finish, startedAt, servedModel, { requestId: json.responseId }), usage };
  }
  const stopReason: StopReason = image ? 'end' : 'other';
  return { images: image ? [image] : [], text, usage, startedAt, totalMs: Date.now() - startedAt, stopReason, rawStopReason: image ? finish : finish === 'STOP' ? 'no_image' : finish, servedModel, requestId: json.responseId };
}

// ─────────────────────────────── Random Baseline ───────────────────────────────

/** A deterministic abstract noise picture (384 × 256): the floor for "made no attempt at the brief". */
export async function baselineImage(ctx: AdapterContext, req: ImageGenRequest): Promise<ImageGenResult> {
  const startedAt = Date.now();
  const seed = hashString(`${ctx.contestant.id}\n${req.prompt}`);
  const png = noisePng(seed);
  const rng = createRng(seed);
  await new Promise((r) => setTimeout(r, 40 + Math.floor(rng.next() * 120)));
  const img = toGeneratedImage(png.toString('base64'))!;
  return { images: [img], text: '', usage: { ...EMPTY_USAGE }, startedAt, totalMs: Date.now() - startedAt, stopReason: 'end', rawStopReason: 'end', servedModel: `mock/${ctx.contestant.model}` };
}
