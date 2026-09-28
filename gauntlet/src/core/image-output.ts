import { computeCost } from './cost.ts';
import type { Contestant, ImageGenResult, ImagePricing, ProviderType } from './types.ts';

/**
 * Image output (The Gallery Masterpiece): which contestants can make pictures, and what a picture costs.
 *
 * A contestant makes pictures when `imageOutput: true` is set in config/models.json (Models → Edit → "Makes
 * images"). The Random Baseline (a deterministic noise picture) and Manual contestants (a person uploads the
 * picture they made in any app) always can. Everyone else is skipped on image-output tests, never scored 0.
 */

export const SKIP_NO_IMAGE_OUTPUT = 'Skipped — model has no image output';
export const SKIP_PICTURE_ONLY = 'Skipped — picture-only model (it cannot answer in text)';

/** True for the summary of a result skipped because of image output (as opposed to image input). */
export function isImageOutputSkip(summary: string | undefined): boolean {
  return !!summary && (summary.startsWith(SKIP_NO_IMAGE_OUTPUT) || summary.startsWith(SKIP_PICTURE_ONLY));
}

export function supportsImageOutput(c: Pick<Contestant, 'imageOutput'>, providerType: ProviderType | undefined): boolean {
  if (c.imageOutput !== undefined) return c.imageOutput;
  return providerType === 'mock' || providerType === 'manual';
}

/** The price of one picture of `size` at `quality` ("*" entries match anything). Null when no price is configured. */
export function imagePrice(pricing: ImagePricing | undefined, size: string | undefined, quality: string | undefined): number | null {
  const table = pricing?.perImage;
  if (!table || typeof table !== 'object') return null;
  const row = (size && table[size]) || table['*'];
  if (row) {
    const v = (quality ? row[quality] : undefined) ?? row['*'] ?? row.standard;
    if (typeof v === 'number' && Number.isFinite(v)) return v;
    // Unknown quality: charge the most expensive listed price (never under-estimate).
    const inRow = Object.values(row).filter((x) => typeof x === 'number' && Number.isFinite(x));
    if (inRow.length) return Math.max(...inRow);
  }
  // Unknown size: the most expensive price in the table.
  const all = Object.values(table)
    .flatMap((r) => Object.values(r ?? {}))
    .filter((x) => typeof x === 'number' && Number.isFinite(x));
  return all.length ? Math.max(...all) : null;
}

/**
 * USD cost of one image call: pictures × the per-image price + the prompt at the text input price. Without a
 * per-image price, the call's token usage is billed with the contestant's token pricing (image tokens as output).
 */
export function imageCallCost(c: Pick<Contestant, 'pricing' | 'imagePricing'>, r: Pick<ImageGenResult, 'images' | 'usage' | 'size' | 'quality' | 'costUsd'>): number {
  if (typeof r.costUsd === 'number') return r.costUsd;
  const per = imagePrice(c.imagePricing, r.size, r.quality);
  if (per === null) return computeCost(r.usage, c.pricing);
  const text = ((r.usage.inputTokens + r.usage.cachedInputTokens) * c.pricing.inputPerM) / 1e6;
  return Math.round((r.images.length * per + text) * 1e8) / 1e8;
}

/** Size and quality a contestant will request (the defaults of src/providers/image-gen.ts), for estimates and the UI. */
export function plannedImageSettings(c: Pick<Contestant, 'imageOptions'>, baseUrl: string | undefined, providerType: ProviderType | undefined): { size?: string; quality?: string } {
  if (providerType !== 'openai-compatible') return {};
  const isOpenAI = (baseUrl ?? '').includes('api.openai.com');
  const o = c.imageOptions ?? {};
  const size = o.size === null ? undefined : (o.size ?? (isOpenAI ? '1536x1024' : undefined));
  const quality = o.quality === null ? undefined : (o.quality ?? (isOpenAI ? 'high' : undefined));
  return { size, quality };
}

/** Estimated USD for `n` pictures with a prompt of `promptTokens`. */
export function estimateImageUsd(c: Pick<Contestant, 'pricing' | 'imagePricing'>, n: number, promptTokens: number, settings: { size?: string; quality?: string }): number {
  const per = imagePrice(c.imagePricing, settings.size, settings.quality);
  // No per-image price: assume ~1,300 output tokens per picture (Gemini's published figure for a 1024 px image).
  const pictureUsd = per ?? (1300 * c.pricing.outputPerM) / 1e6;
  return n * (pictureUsd + (promptTokens * c.pricing.inputPerM) / 1e6);
}
