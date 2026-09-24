/**
 * Draw It Blind — the model describes a precise scene in words (no digits),
 * then, in a fresh context, redraws it as SVG from its own description.
 * The drawing is scored geometrically against the original scene.
 */
import type { ProgramContext, ProgramDefinition, ProgramResult, ReplayFrame } from '../core/types.ts';
import { extractCodeBlock } from '../core/extract.ts';
import { CANVAS, generateScene, paletteOf, renderSceneSvg, sceneTable } from './lib/draw-it-blind-scene.ts';
import type { PaletteId, Scene, SceneOptions } from './lib/draw-it-blind-scene.ts';
import { extractSvg, parseSvg, sanitizeSvg } from './lib/draw-it-blind-svg.ts';
import { matchShapes, where } from './lib/draw-it-blind-match.ts';
import { countWords, truncateWords } from './lib/needle-haystack-normalize.ts';

export interface DibConfig {
  minShapes: number;
  maxShapes: number;
  descriptionWords: number;
  /** 'basic' (8 colours) or 'extended' (12 colours with close pairs such as navy/blue, teal/green). */
  palette: PaletteId;
  /** Allow overlapping and nested shapes. */
  overlap: boolean;
  /** Rotate triangles (and score their direction). */
  rotation: boolean;
}

export function readConfig(raw: Record<string, unknown>): DibConfig {
  const int = (v: unknown, def: number, min: number, max: number) => {
    const n = Number(v);
    return Number.isInteger(n) && n >= min && n <= max ? n : def;
  };
  const minShapes = int(raw.minShapes, 5, 2, 12);
  return {
    minShapes,
    maxShapes: Math.max(minShapes, int(raw.maxShapes, 7, 2, 12)),
    descriptionWords: int(raw.descriptionWords, 120, 30, 400),
    palette: raw.palette === 'extended' ? 'extended' : 'basic',
    overlap: raw.overlap === true,
    rotation: raw.rotation === true,
  };
}

/**
 * The number rule, stated in the prompt: numbers may only be spelled out and
 * must be ten or smaller (fractions with denominators up to ten are fine).
 * Digits and any spelled-out number above ten are deleted and cost points.
 */
const LARGE_CARDINALS = [
  'eleven', 'twelve', 'thirteen', 'fourteen', 'fifteen', 'sixteen', 'seventeen', 'eighteen', 'nineteen',
  'twenty', 'thirty', 'forty', 'fourty', 'fifty', 'sixty', 'seventy', 'eighty', 'ninety', 'hundred', 'thousand', 'dozen',
];
const LARGE_ORDINALS = [
  'eleventh', 'twelfth', 'thirteenth', 'fourteenth', 'fifteenth', 'sixteenth', 'seventeenth', 'eighteenth', 'nineteenth',
  'twentieth', 'thirtieth', 'fortieth', 'fiftieth', 'sixtieth', 'seventieth', 'eightieth', 'ninetieth', 'hundredth', 'thousandth',
];
const NUMBER_WORDS = [...LARGE_CARDINALS, ...LARGE_ORDINALS].map((w) => `${w}s?`);
const SMALL_NUMBER_WORDS = [
  'zero', 'one', 'two', 'three', 'four', 'five', 'six', 'seven', 'eight', 'nine', 'ten',
  'halfs?', 'halves', 'thirds?', 'quarters?', 'fourths?', 'fifths?', 'sixths?', 'sevenths?', 'eighths?', 'ninths?', 'tenths?',
];
const DIGITS_RE = /\d+(?:[.,:/]\d+)*%?/g;
const LARGE_RE = new RegExp(`\\b(?:${NUMBER_WORDS.join('|')})\\b`, 'i');
const NUM = `(?:${[...SMALL_NUMBER_WORDS, ...NUMBER_WORDS].join('|')})`;
/** A run of number words ("one hundred and twenty", "eleven-twentieths"); it violates the rules if any word is above ten. */
const NUMBER_PHRASE_RE = new RegExp(`\\b${NUM}(?:(?:[\\s-]+and)?[\\s-]+${NUM})*\\b`, 'gi');

export interface DescriptionCheck {
  /** Digit runs and large number words found (each costs points). */
  violations: string[];
  /** The text passed to the drawer (numbers removed, cut to the word limit). */
  delivered: string;
  words: number;
  truncated: boolean;
  penalty: number;
}

/** Enforces the description rules: no digits, no large number words, word limit. */
export function checkDescription(text: string, limit: number): DescriptionCheck {
  const words = countWords(text);
  const truncated = words > limit;
  const kept = truncated ? truncateWords(text, limit) : text;
  const phrases = (kept.match(NUMBER_PHRASE_RE) ?? []).filter((p) => LARGE_RE.test(p));
  const violations = [...(kept.match(DIGITS_RE) ?? []), ...phrases];
  const delivered = kept
    .replace(DIGITS_RE, '')
    .replace(NUMBER_PHRASE_RE, (p) => (LARGE_RE.test(p) ? '' : p))
    .replace(/[ \t]{2,}/g, ' ')
    .replace(/ +([,.;:])/g, '$1')
    .trim();
  const numberPenalty = violations.length > 0 ? Math.min(0.3, 0.1 + 0.02 * (violations.length - 1)) : 0;
  return { violations, delivered, words, truncated, penalty: numberPenalty + (truncated ? 0.05 : 0) };
}

export function describePrompt(scene: Scene, limit: number): string {
  return [
    'You are playing DRAW IT BLIND. Below is the exact layout of a picture. Describe it in plain English so that another artist, who will never see this layout, can redraw it as accurately as possible from your words alone.',
    '',
    'PICTURE',
    sceneTable(scene),
    '',
    'Rules:',
    `- At most ${limit} words. Anything beyond ${limit} words is cut off.`,
    '- Do not use any digits (0-9), and do not spell out large numbers (eleven or more, such as "one hundred and twenty"). Describe positions and sizes in words: "top-left corner", "a quarter of the way down", "about a fifth of the canvas wide". Digits and large numbers are deleted before the artist reads your description, and they cost points.',
    '- Mention every shape with its colour.',
    '',
    `Reply with only the description: plain prose, at most ${limit} words, no digits.`,
  ].join('\n');
}

export function drawPrompt(description: string): string {
  return [
    'You are playing DRAW IT BLIND. Another artist looked at a picture and described it in words. Recreate the picture as an SVG image from the description alone.',
    '',
    'DESCRIPTION:',
    '"""',
    description,
    '"""',
    '',
    'Requirements:',
    `- One SVG with width="${CANVAS}" height="${CANVAS}" and viewBox="0 0 ${CANVAS} ${CANVAS}" (origin at the top-left, y grows downward) on a white background.`,
    '- Draw every described shape as one basic element (circle, rect, polygon, ellipse or path) filled with a solid colour.',
    '- No text, labels, outlines-only shapes or extra decoration.',
    '',
    'Reply with a single ```svg code block and nothing else.',
  ].join('\n');
}

function cleanDescription(text: string): string {
  let t = text.trim();
  if (/^```/.test(t)) t = extractCodeBlock(t)?.code.trim() ?? t;
  t = t.replace(/^(?:\*\*|#+\s*)?description\s*(?:\*\*)?\s*:\s*(?:\*\*)?\s*/i, '');
  return t.replace(/^"+|"+$/g, '').trim();
}

const PLACEHOLDER_SVG = `<svg xmlns="http://www.w3.org/2000/svg" width="${CANVAS}" height="${CANVAS}" viewBox="0 0 ${CANVAS} ${CANVAS}"><rect width="${CANVAS}" height="${CANVAS}" fill="#f4f4f5"/><text x="200" y="205" text-anchor="middle" font-family="sans-serif" font-size="20" fill="#71717a">No drawing</text></svg>`;

const pct = (x: number) => Math.round(x * 100);
const r1 = (x: number) => Math.round(x * 10) / 10;

export function buildScene(ctx: Pick<ProgramContext, 'rng'>, cfg: DibConfig): Scene {
  return generateScene(ctx.rng.fork('draw-it-blind'), cfg.minShapes, cfg.maxShapes);
}

export const program: ProgramDefinition = {
  id: 'draw-it-blind',
  name: 'Draw It Blind',
  description:
    'The model sees a precise table of 5–7 coloured shapes on a 400×400 canvas and must describe it in at most 120 words without using digits. Then, in a fresh context, the same model redraws the picture as SVG from nothing but its own description. The drawing is parsed and matched shape-by-shape against the original.',
  scoring:
    'The drawn SVG is parsed into shapes, and each target shape is paired with at most one drawn shape using the best possible one-to-one assignment. A pair scores 30% for shape type, 30% for colour (mapped to the nearest of the eight palette colours), 25% for position (zero at 160 px away) and 15% for size; unmatched targets score 0 and each extra drawn shape costs 0.03 (up to 0.15). Digits or large number words in the description are deleted before the drawing step and cost 0.10 plus 0.02 per extra occurrence (up to 0.30), and going over 120 words costs 0.05.',
  defaults: { minShapes: 5, maxShapes: 7, descriptionWords: 120 },

  async run(ctx: ProgramContext): Promise<ProgramResult> {
    const cfg = readConfig(ctx.config);
    const scene = buildScene(ctx, cfg);
    const targetSvg = renderSceneSvg(scene);
    ctx.artifact('target.svg', 'svg', targetSvg);
    const n = scene.shapes.length;
    const sceneList = scene.shapes.map((s) => `${s.color} ${s.type}`).join(', ');

    // Step 1 — describe.
    const d = await ctx.model.complete({
      messages: [{ role: 'user', content: describePrompt(scene, cfg.descriptionWords) }],
      maxOutputTokens: ctx.maxOutputTokens,
      label: 'describe',
    });
    const description = d.stopReason === 'refusal' ? '' : cleanDescription(d.text);
    const check = checkDescription(description, cfg.descriptionWords);
    const describeFrame: ReplayFrame = {
      step: 1,
      label: 'Step 1 · Describe',
      observation: `${n} shapes: ${sceneList}`,
      action: description ? description.slice(0, 600) : '(no description)',
      outcome: !description
        ? 'No description — the drawing step was skipped'
        : `${check.words} words${check.truncated ? ` (cut to ${cfg.descriptionWords})` : ''} · ${check.violations.length ? `${check.violations.length} number${check.violations.length === 1 ? '' : 's'} removed (−${pct(check.penalty)}%)` : 'no digits'}`,
      stats: { words: check.words, numbers: check.violations.length },
      tone: !description || check.penalty > 0 ? 'bad' : 'good',
    };

    let drawnSvg = PLACEHOLDER_SVG;
    let svgFound = false;
    let elements = 0;
    let ignored: Array<{ tag: string; reason: string }> = [];
    let shapes: ReturnType<typeof parseSvg>['shapes'] = [];
    const drawable = /[a-z]/i.test(check.delivered);
    if (drawable) {
      // Step 2 — draw, in a fresh context, from the description alone.
      const r = await ctx.model.complete({
        messages: [{ role: 'user', content: drawPrompt(check.delivered) }],
        maxOutputTokens: ctx.maxOutputTokens,
        label: 'draw',
      });
      const markup = r.stopReason === 'refusal' ? null : extractSvg(r.text);
      if (markup) {
        const parsed = parseSvg(markup);
        svgFound = parsed.found;
        elements = parsed.elements;
        ignored = parsed.ignored;
        shapes = parsed.shapes;
        drawnSvg = sanitizeSvg(markup);
      }
    }
    ctx.artifact('drawn.svg', 'svg', drawnSvg);

    const m = matchShapes(scene.shapes, shapes);
    const score = drawable ? Math.round(Math.max(0, Math.min(1, m.match - check.penalty)) * 1000) / 1000 : 0;

    let summary: string;
    if (!description) summary = 'No description · 0% match';
    else if (!drawable) summary = 'Description was only numbers · 0% match';
    else if (!svgFound) summary = 'No drawing returned · 0% match';
    else {
      summary = `Rebuilt ${m.rebuilt}/${n} shapes · ${pct(m.match)}% match`;
      if (check.penalty > 0) summary += ` · −${pct(check.penalty)}% for ${check.violations.length ? 'digits' : 'length'}`;
    }

    const frames: ReplayFrame[] = [
      describeFrame,
      {
        step: 2,
        label: 'Step 2 · Draw',
        observation: drawable ? check.delivered.slice(0, 600) : '(nothing to draw from)',
        action: svgFound ? `SVG · ${elements} elements → ${shapes.length} shapes` : drawable ? 'No SVG found' : '(skipped)',
        outcome: svgFound ? `Rebuilt ${m.rebuilt}/${n} · ${pct(m.match)}% match` : '0% match',
        stats: { match: pct(m.match), shapes: shapes.length },
        tone: m.match >= 0.6 ? 'good' : 'bad',
      },
      ...m.pairs.map((p, k): ReplayFrame => ({
        step: 3 + k,
        label: `#${p.target.id} ${p.target.color} ${p.target.type} (${where(p.target.cx, p.target.cy)})`,
        outcome: p.drawn
          ? `→ ${p.drawn.color} ${p.drawn.kind} (${where(p.drawn.cx, p.drawn.cy)}) · ${pct(p.score!.total)}%`
          : 'not drawn',
        stats: { match: p.score ? pct(p.score.total) : 0 },
        tone: p.score && p.score.total >= 0.75 ? 'good' : p.score && p.score.total >= 0.5 ? 'neutral' : 'bad',
      })),
    ];

    return {
      score,
      passed: score >= 0.6,
      summary,
      detail: {
        scene: scene.shapes.map((s) => ({ id: s.id, type: s.type, color: s.color, cx: s.cx, cy: s.cy, w: s.w, h: s.h })),
        description,
        descriptionWords: check.words,
        descriptionTruncated: check.truncated,
        numberViolations: check.violations,
        deliveredDescription: check.delivered,
        svgFound,
        elements,
        drawnShapes: shapes.length,
        ignored,
        matches: m.pairs.map((p) => ({
          target: `${p.target.color} ${p.target.type}`,
          drawn: p.drawn ? { kind: p.drawn.kind, color: p.drawn.color, cx: r1(p.drawn.cx), cy: r1(p.drawn.cy), w: r1(p.drawn.w), h: r1(p.drawn.h) } : null,
          score: p.score ? Math.round(p.score.total * 1000) / 1000 : 0,
          type: p.score?.type ?? 0,
          color: p.score?.color ?? 0,
          position: p.score ? Math.round(p.score.position * 1000) / 1000 : 0,
          size: p.score ? Math.round(p.score.size * 1000) / 1000 : 0,
          distance: p.score ? r1(p.score.distance) : null,
        })),
        extras: m.extras.length,
        rebuilt: m.rebuilt,
        geometric: Math.round(m.geometric * 1000) / 1000,
        match: Math.round(m.match * 1000) / 1000,
        penalties: { extras: Math.round(m.extraPenalty * 1000) / 1000, description: Math.round(check.penalty * 1000) / 1000 },
      },
      replay: {
        title: `Draw It Blind — ${n} shapes`,
        gauges: ['match'],
        frames,
        svgCompare: {
          left: { title: 'Original', svg: targetSvg },
          right: { title: 'Redrawn from its own description', svg: drawnSvg },
        },
      },
    };
  },
};
