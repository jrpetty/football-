/**
 * The Gallery Masterpiece — the model receives a museum's commission brief (subject, required elements, medium
 * and style, palette, mood, freedoms, things to avoid) and paints it with its own image generator. A panel of
 * vision judges from other companies checks the brief line by line and scores the artistry.
 *
 * The seed selects the brief (seeds 1–8, see lib/gallery-briefs.ts). Models without image output are skipped
 * by the harness (requiresImageOutput), never scored 0. Manual contestants upload a picture made in any app.
 */
import type { ProgramContext, ProgramDefinition, ProgramResult } from '../core/types.ts';
import { briefForSeed, imagePrompt } from './lib/gallery-briefs.ts';
import { finishPainting } from './lib/gallery-core.ts';

export const program: ProgramDefinition = {
  id: 'gallery-masterpiece',
  name: 'The Gallery Masterpiece',
  description:
    'A museum commissions a painting: a very specific subject and composition, six required elements, a medium and art-historical style (Dutch Golden Age, Impressionism, Romantic sublime, ukiyo-e, Art Nouveau, Renaissance fresco, Pre-Raphaelite, Hudson River School), a palette, a mood, explicit room for interpretation and things that must not appear. The model paints it with its own image generator; nothing else is asked.',
  scoring:
    'At least two vision judges from other companies than the artist see the anonymised painting with an identical prompt. Brief adherence (50%): each of the 6 required elements and 3 "do not include" lines gets yes (1), partly (0.5) or no (0); the panel verdict per line is the median, and adherence is the mean. Artistry (50%): composition, light, colour harmony, craft, stylistic authenticity and gallery-worthiness, each 1–10 on anchored scales; artistry is the median of the judges’ averages. Score = 0.5 × adherence + 0.5 × artistry ÷ 10. A refusal or no picture scores 0. Artistry is subjective: judge spread is recorded, and splits of 2+ points are flagged for the owner, whose own artistry rating (Blind Review) replaces the judges’.',
  requiresImageOutput: true,
  imagesPerCase: 1,
  judges: { vision: true, strictVendor: true, perCase: { inputTokens: 1900, outputTokens: 1600, images: 1, imageSize: { width: 1536, height: 1024 } } },

  async run(ctx: ProgramContext): Promise<ProgramResult> {
    const brief = briefForSeed(ctx.seed);
    if (!ctx.model.generateImage) throw new Error('This contestant’s provider has no image-generation API in Gauntlet (OpenAI-compatible images, Gemini, Manual or the Random Baseline).');
    const reply = await ctx.model.generateImage({ prompt: imagePrompt(brief), aspectRatio: '3:2', label: 'painting' });
    if (!reply.image) {
      return finishPainting(ctx, brief, { kind: reply.stopReason === 'refusal' ? 'refused' : 'no-picture', text: reply.text, costUsd: reply.costUsd }, 'image');
    }
    return finishPainting(ctx, brief, { kind: 'painting', image: reply.image, costUsd: reply.costUsd }, 'image');
  },
};
