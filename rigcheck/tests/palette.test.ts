/**
 * The palette's promises, checked rather than commented.
 *
 * theme.css says "do not brighten these without re-validating", which is a
 * comment, and comments do not fail. These are the two properties that make
 * the colour scheme safe to look at, expressed as assertions:
 *
 *   - anything carrying meaning clears 3:1 on the LIGHTEST surface it can
 *     land on, so a limiter tag on surface-3 is as readable as on the page;
 *   - anything carrying prose clears the WCAG AA body-text floor of 4.5:1;
 *   - the magnitude ramp is monotonic, because a sequential encoding whose
 *     lightness wanders is not a sequential encoding.
 *
 * The categorical separation checks (CVD ΔE, chroma, lightness band) were run
 * with the dataviz validator when the set was chosen; the hexes are pinned
 * here so a casual edit has to come back through that gate deliberately.
 */
import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';

const css = readFileSync(new URL('../src/ui/theme.css', import.meta.url), 'utf8');

/** Read a custom property's literal hex out of the :root block. */
function token(name: string): string {
  const m = new RegExp(`--${name}:\\s*(#[0-9a-fA-F]{6})\\s*;`).exec(css);
  if (!m) throw new Error(`--${name} is not a literal hex in theme.css`);
  return m[1].toLowerCase();
}

const channel = (c: number) => (c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4);
function luminance(hex: string): number {
  const n = parseInt(hex.slice(1), 16);
  const [r, g, b] = [(n >> 16) & 255, (n >> 8) & 255, n & 255].map((v) => channel(v / 255));
  return 0.2126 * r + 0.7152 * g + 0.0722 * b;
}
function contrast(a: string, b: string): number {
  const [hi, lo] = [luminance(a), luminance(b)].sort((x, y) => y - x);
  return (hi + 0.05) / (lo + 0.05);
}

const SURFACES = ['bg', 'surface', 'surface-2', 'surface-3'];
/** Colours that carry meaning: a mark, a verdict, a category. */
const MEANING = ['accent', 'gpu', 'cpu', 'balanced', 'cap', 'vram',
  'measured', 'interpolated', 'spec', 'extrapolated', 'blocked',
  'chart-series', 'chart-knee'];
/** Colours that carry words. */
const PROSE = ['ink', 'muted', 'faint'];

describe('palette contrast', () => {
  it.each(MEANING)('%s clears 3:1 on every surface', (name) => {
    for (const s of SURFACES) {
      expect(contrast(token(name), token(s)), `${name} on ${s}`).toBeGreaterThanOrEqual(3);
    }
  });

  it.each(PROSE)('%s clears the AA body-text floor on every surface', (name) => {
    for (const s of SURFACES) {
      expect(contrast(token(name), token(s)), `${name} on ${s}`).toBeGreaterThanOrEqual(4.5);
    }
  });

  it('layers the surfaces far enough apart to be seen as layers', () => {
    // The old ladder moved ~6 points of lightness across three surfaces, which
    // is why every panel read as the same rectangle. Each step must be visible.
    for (let i = 1; i < SURFACES.length; i++) {
      const step = luminance(token(SURFACES[i])) - luminance(token(SURFACES[i - 1]));
      expect(step, `${SURFACES[i - 1]} → ${SURFACES[i]}`).toBeGreaterThan(0.004);
    }
  });
});

describe('magnitude ramp', () => {
  const ramp = ['mag-1', 'mag-2', 'mag-3', 'mag-4', 'mag-5'].map(token);

  it('is monotonically lighter, so bigger always reads as brighter', () => {
    const ls = ramp.map(luminance);
    for (let i = 1; i < ls.length; i++) expect(ls[i]).toBeGreaterThan(ls[i - 1]);
  });

  it('is one hue, not a rainbow', () => {
    // Sequential means a single hue. Every step must sit in the same corner of
    // the wheel: blue dominant, and red never the largest channel.
    for (const hex of ramp) {
      const n = parseInt(hex.slice(1), 16);
      const [r, g, b] = [(n >> 16) & 255, (n >> 8) & 255, n & 255];
      expect(b, `${hex} should be blue-dominant`).toBeGreaterThan(r);
      expect(b).toBeGreaterThanOrEqual(g);
    }
  });
});

describe('the validated categorical set', () => {
  // Pinned so a "brighten this" edit has to go back through the dataviz
  // validator rather than sliding past review. Worst adjacent CVD ΔE 8.4,
  // worst normal-vision ΔE 19.3, all five clearing 3:1 on surface-2.
  it('still holds the hexes that were validated as a set', () => {
    expect({
      gpu: token('gpu'), cpu: token('cpu'), balanced: token('balanced'),
      cap: token('cap'), vram: token('vram'),
    }).toEqual({
      gpu: '#3987e5', cpu: '#d95926', balanced: '#199e70',
      cap: '#c98500', vram: '#d55181',
    });
  });
});
