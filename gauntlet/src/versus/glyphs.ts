/**
 * One drawn icon per test category (24×24 stroke paths), shared by the UI
 * (ui/src/components/viz/CategoryGlyph.tsx) and the Shorts card HTML, so a
 * category looks the same everywhere and on every OS (no emoji).
 */
export const CATEGORY_GLYPHS: Record<string, string> = {
  reasoning: 'M9 18h6M10 21h4M12 3a6 6 0 0 0-4 10.5c.7.7 1 1.5 1 2.5h6c0-1 .3-1.8 1-2.5A6 6 0 0 0 12 3Z',
  math: 'M4 7h6M7 4v6M14 7h6M4.5 14.5l5 5M9.5 14.5l-5 5M14 15.5h6M14 18.5h6',
  coding: 'M8 7l-5 5 5 5M16 7l5 5-5 5M13.5 4l-3 16',
  instruction: 'M10 6h10M10 12h10M10 18h10M3.5 6l1.5 1.5L7.5 5M3.5 12l1.5 1.5 2.5-2.5M3.5 18l1.5 1.5 2.5-2.5',
  honesty: 'M12 3l8 3v6c0 5-3.5 8-8 9-4.5-1-8-4-8-9V6ZM8.5 12l2.5 2.5 4.5-4.5',
  'long-context': 'M6 3h10l3 3v15H6ZM16 3v3h3M9 10h7M9 13.5h7M9 17h4',
  agentic: 'M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18ZM15.5 8.5l-2 5-5 2 2-5Z',
  social: 'M9 11a3 3 0 1 0 0-6 3 3 0 0 0 0 6ZM3 20c0-3.3 2.7-6 6-6s6 2.7 6 6M16 5a3 3 0 0 1 0 6M18 14c2 .6 3 2.8 3 6',
  visual: 'M3.5 20l5-9 5 9ZM17 10a3 3 0 1 0 0-6 3 3 0 0 0 0 6ZM14.5 13.5h6v6h-6Z',
  creative: 'M3 9h18v7a3 3 0 0 1-3 3H6a3 3 0 0 1-3-3ZM8 11.5v5M5.5 14h5M15 13h.01M17.5 15.5h.01',
  extraction: 'M8 4c-2 0-3 1-3 3v2c0 1.5-1 2.5-2 3 1 .5 2 1.5 2 3v2c0 2 1 3 3 3M16 4c2 0 3 1 3 3v2c0 1.5 1 2.5 2 3-1 .5-2 1.5-2 3v2c0 2-1 3-3 3M10 12h.01M14 12h.01',
  vision: 'M2 12s4-7 10-7 10 7 10 7-4 7-10 7S2 12 2 12ZM12 15a3 3 0 1 0 0-6 3 3 0 0 0 0 6Z',
  trick: 'M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18ZM9.5 9.5a2.5 2.5 0 1 1 3.5 2.3c-.7.3-1 .9-1 1.7v.5M12 17h.01',
};

/** Fallback: a target. */
export const DEFAULT_GLYPH = 'M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18ZM12 16a4 4 0 1 0 0-8 4 4 0 0 0 0 8ZM12 12h.01';

export function glyphPath(category: string): string {
  return CATEGORY_GLYPHS[category] ?? DEFAULT_GLYPH;
}
