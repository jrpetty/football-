/**
 * Colour helpers for the two corners. Two models from the same vendor often
 * share a colour; the right-hand corner is then shifted so the sides stay
 * tell-apart-able on screen (the badge and name still say who is who).
 */
export function hexRgb(hex: string): [number, number, number] {
  const h = (hex || '').replace('#', '');
  const full = h.length === 3 ? h.split('').map((c) => c + c).join('') : h.padEnd(6, '0').slice(0, 6);
  const n = parseInt(full, 16);
  return Number.isNaN(n) ? [100, 116, 139] : [(n >> 16) & 255, (n >> 8) & 255, n & 255];
}

const toHex = (r: number, g: number, b: number) => `#${[r, g, b].map((v) => Math.round(Math.max(0, Math.min(255, v))).toString(16).padStart(2, '0')).join('')}`;

/** Black or white, whichever reads better on the colour. */
export function inkOn(hex: string): string {
  const [r, g, b] = hexRgb(hex).map((v) => {
    const c = v / 255;
    return c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4;
  }) as [number, number, number];
  const L = 0.2126 * r + 0.7152 * g + 0.0722 * b;
  return L > 0.36 ? '#0a0d12' : '#ffffff';
}

function distance(x: string, y: string): number {
  const [a, b, c] = hexRgb(x);
  const [d, e, f] = hexRgb(y);
  return Math.sqrt((a - d) ** 2 + (b - e) ** 2 + (c - f) ** 2);
}

/** Rotate a colour's hue by `deg` degrees. */
function rotateHue(hex: string, deg: number): string {
  const [r, g, b] = hexRgb(hex).map((v) => v / 255) as [number, number, number];
  const max = Math.max(r, g, b);
  const min = Math.min(r, g, b);
  const l = (max + min) / 2;
  const d = max - min;
  let h = 0;
  const s = d === 0 ? 0 : d / (1 - Math.abs(2 * l - 1));
  if (d !== 0) {
    if (max === r) h = ((g - b) / d) % 6;
    else if (max === g) h = (b - r) / d + 2;
    else h = (r - g) / d + 4;
  }
  h = (h * 60 + deg + 360) % 360;
  const sat = Math.max(s, 0.55);
  const c = (1 - Math.abs(2 * l - 1)) * sat;
  const x = c * (1 - Math.abs(((h / 60) % 2) - 1));
  const m = l - c / 2;
  const [rr, gg, bb] = h < 60 ? [c, x, 0] : h < 120 ? [x, c, 0] : h < 180 ? [0, c, x] : h < 240 ? [0, x, c] : h < 300 ? [x, 0, c] : [c, 0, x];
  return toHex((rr + m) * 255, (gg + m) * 255, (bb + m) * 255);
}

/** The two corner colours: A as configured, B shifted when the two are too alike. */
export function cornerColors(a: string, b: string): { a: string; b: string } {
  if (distance(a, b) >= 90) return { a, b };
  for (const deg of [150, 200, 110, 250]) {
    const c = rotateHue(b, deg);
    if (distance(a, c) >= 90) return { a, b: c };
  }
  return { a, b: '#e2e8f0' };
}
