/**
 * The Gallery Masterpiece: Painted in Code — the same commission briefs as The Gallery Masterpiece, painted by
 * text models as a single SVG. Headless Chromium renders the SVG to a 1536 × 1024 PNG, and the judges score that
 * picture exactly as they score generated images (lib/gallery-judge.ts). Its own test and leaderboard column:
 * never mixed with the image-generation test.
 */
import type { ProgramContext, ProgramDefinition, ProgramResult } from '../core/types.ts';
import { extractSvg } from './lib/draw-it-blind-svg.ts';
import { CODE_HEIGHT, CODE_MAX_BYTES, CODE_WIDTH, briefForSeed, codePrompt } from './lib/gallery-briefs.ts';
import { finishPainting } from './lib/gallery-core.ts';
import { browserReason, rasterizeSvg, sanitizePaintingSvg } from './lib/gallery-render.ts';
import { toGeneratedImage } from '../providers/image-gen.ts';

export const program: ProgramDefinition = {
  id: 'gallery-code',
  name: 'The Gallery Masterpiece: Painted in Code',
  description:
    'The same museum commission briefs as The Gallery Masterpiece, for models that write rather than paint: the model paints the brief as a single hand-written SVG (1536 × 1024, shapes, gradients and filters; no text, images or scripts). Headless Chromium renders it to a picture, and that picture is judged exactly like a generated painting.',
  scoring:
    'The SVG is rendered to a 1536 × 1024 PNG in headless Chromium. At least two vision judges from other companies than the artist see the anonymised picture with an identical prompt. Brief adherence (50%): each of the 6 required elements and 3 "do not include" lines gets yes (1), partly (0.5) or no (0); the panel verdict per line is the median, and adherence is the mean. Artistry (50%): six criteria scored 1–10 on anchored scales; artistry is the median of the judges’ averages. Score = 0.5 × adherence + 0.5 × artistry ÷ 10. No SVG, an SVG that does not render or one over 200 kB scores 0. Judge spread is recorded, and splits of 2+ points are flagged for the owner.',
  judges: { vision: true, strictVendor: true, perCase: { inputTokens: 1900, outputTokens: 1600, images: 1, imageSize: { width: CODE_WIDTH, height: CODE_HEIGHT } } },

  async run(ctx: ProgramContext): Promise<ProgramResult> {
    const brief = briefForSeed(ctx.seed);
    const r = await ctx.model.complete({ messages: [{ role: 'user', content: codePrompt(brief) }], maxOutputTokens: ctx.maxOutputTokens, label: 'painting' });
    const raw = r.stopReason === 'refusal' ? null : extractSvg(r.text);
    if (!raw) {
      return finishPainting(ctx, brief, { kind: r.stopReason === 'refusal' ? 'refused' : 'no-picture', text: r.stopReason === 'refusal' ? r.text : '', costUsd: 0 }, 'code', { svgFound: false });
    }
    const svg = sanitizePaintingSvg(raw);
    const bytes = Buffer.byteLength(raw);
    ctx.artifact('painting.svg', 'svg', svg);
    if (bytes > CODE_MAX_BYTES) {
      return finishPainting(ctx, brief, { kind: 'no-picture', text: `The SVG is ${(bytes / 1000).toFixed(0)} kB, over the ${CODE_MAX_BYTES / 1000} kB limit.`, costUsd: 0 }, 'code', { svgFound: true, svgBytes: bytes, tooLarge: true });
    }
    const raster = await rasterizeSvg(svg, CODE_WIDTH, CODE_HEIGHT);
    if (!raster) throw new Error(`Painted in Code needs headless Chromium to turn the SVG into a picture (${browserReason()}). Install Google Chrome or Microsoft Edge, or set GAUNTLET_CHROMIUM, then resume the run.`);
    if (!raster.ok) {
      return finishPainting(ctx, brief, { kind: 'no-picture', text: raster.error ?? 'The SVG did not render.', costUsd: 0 }, 'code', { svgFound: true, svgBytes: bytes, renders: false });
    }
    const image = toGeneratedImage(raster.png.toString('base64'));
    if (!image) throw new Error('Chromium returned an unreadable screenshot');
    return finishPainting(ctx, brief, { kind: 'painting', image, costUsd: 0 }, 'code', { svgFound: true, svgBytes: bytes, renders: true });
  },
};
