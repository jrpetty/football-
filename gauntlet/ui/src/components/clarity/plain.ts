/**
 * Plain-English translations for viewer-facing screens (Broadcast mode).
 *
 * Normal mode keeps the owner's full detail; Broadcast mode swaps internal names, raw ids and
 * abbreviations for these labels. Pure functions only, so they are unit-tested in test/clarity.test.ts.
 */

/** Score bands shared by the results matrix, score pills and the viewer guide (same cut-offs as `scoreTone`). */
export const SCORE_BANDS = [
  { id: 'good', min: 0.8, label: 'Mostly right', range: '80–100' },
  { id: 'partial', min: 0.4, label: 'Partly right', range: '40–79' },
  { id: 'poor', min: 0, label: 'Mostly wrong', range: '0–39' },
] as const;
export type ScoreBand = (typeof SCORE_BANDS)[number]['id'];

export function scoreBand(score: number | null | undefined): ScoreBand | null {
  if (typeof score !== 'number' || !Number.isFinite(score)) return null;
  for (const b of SCORE_BANDS) if (score >= b.min) return b.id;
  return 'poor';
}

/**
 * Plain labels for score-detail keys recorded by scorers and simulations.
 * Keys not listed fall back to `humanizeKey` ("nightsSurvived" → "Nights survived").
 */
export const PLAIN_LABELS: Record<string, string> = {
  checkScore: 'Automatic checks',
  judgeScore: 'Judges’ score',
  judgeDisagreement: 'Judges disagreed',
  skippedChecks: 'Checks not run',
  consoleErrors: 'Errors in the page',
  pageErrors: 'Errors in the page',
  bytes: 'File size',
  textLength: 'Length (characters)',
  formatOk: 'Followed the answer format',
  tokenEfficiency: 'Words used vs. budget',
  wrongEntries: 'Wrong entries',
  extracted: 'Model’s answer',
  expected: 'Correct answer',
  // Case metrics
  wallMs: 'Time taken',
  ttftMs: 'Time to first word',
  apiCalls: 'Messages sent to the model',
  inputTokens: 'Text read (tokens)',
  outputTokens: 'Text written (tokens)',
  reasoningTokens: 'Thinking (tokens)',
  cachedInputTokens: 'Re-used text (tokens)',
  costUsd: 'Cost',
  judgeCostUsd: 'Judging cost',
  outputTokensPerSec: 'Writing speed',
  retries: 'Retries',
  responseChars: 'Answer length',
};

/** "nightsSurvived" / "night_survived" / "night-survived" → "Nights survived". */
export function humanizeKey(key: string): string {
  const words = key
    .replace(/([a-z0-9])([A-Z])/g, '$1 $2')
    .replace(/[_\-.]+/g, ' ')
    .trim()
    .toLowerCase();
  if (!words) return key;
  return words.charAt(0).toUpperCase() + words.slice(1);
}

export function plainKey(key: string): string {
  return PLAIN_LABELS[key] ?? humanizeKey(key);
}

function fmtKb(n: number): string {
  if (n < 1024) return `${n} bytes`;
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(n < 10 * 1024 ? 1 : 0)} KB`;
  return `${(n / 1024 / 1024).toFixed(1)} MB`;
}

/** A detail value as a viewer should read it. Ratios named "…Score" become "N / 100". */
export function plainValue(key: string, v: unknown): string {
  if (v === null || v === undefined) return '—';
  if (typeof v === 'boolean') return v ? 'Yes' : 'No';
  if (typeof v === 'number') {
    if (!Number.isFinite(v)) return '—';
    if (/score$/i.test(key) && v >= 0 && v <= 1) return `${Math.round(v * 100)} / 100`;
    if (key === 'bytes') return fmtKb(v);
    return Number.isInteger(v) ? v.toLocaleString('en-GB') : String(Math.round(v * 100) / 100);
  }
  if (Array.isArray(v)) {
    if (v.length === 0) return 'None';
    if (v.every((x) => typeof x === 'string' || typeof x === 'number')) return v.map((x) => (typeof x === 'string' ? humanizeKey(x) : String(x))).join(', ');
    return `${v.length} item${v.length === 1 ? '' : 's'}`;
  }
  if (typeof v === 'object') return `${Object.keys(v as object).length} values`;
  return String(v);
}

/**
 * A case id as a viewer should read it:
 * `seed-101` → "World #101" (a seeded simulation), `c03` → "Question 3", `goat-rope` → "Goat rope".
 */
export function plainCaseName(caseId: string): string {
  const seed = /^seed[-_]?(\d+)$/i.exec(caseId);
  if (seed) return `World #${Number(seed[1])}`;
  const q = /^(?:c|case|q)[-_]?(\d+)$/i.exec(caseId);
  if (q) return `Question ${Number(q[1])}`;
  return humanizeKey(caseId);
}

/** Zero-based repeat index → "Try 1". */
export function plainAttempt(repeat: number): string {
  return `Try ${repeat + 1}`;
}

/** Two-character monogram for a model: "Atlas-4 Ultra" → "A4", "Quill Flash" → "QF", "Kite" → "KI". */
export function monogram(label: string): string {
  const clean = label.replace(/\([^)]*\)/g, ' ').trim();
  const words = clean.split(/[\s\-_/·]+/).filter(Boolean);
  if (words.length === 0) return '?';
  const first = words[0].charAt(0).toUpperCase();
  if (words.length === 1) {
    const w = words[0];
    const second = w.slice(1).match(/[0-9A-Za-z]/)?.[0] ?? '';
    return (first + second.toUpperCase()).slice(0, 2);
  }
  const second = words[1].charAt(0);
  return first + (/[0-9]/.test(second) ? second : second.toUpperCase());
}

/** Relative luminance of a #rgb / #rrggbb colour (0–1), or null when it can't be parsed. */
export function luminance(hex: string): number | null {
  const m = /^#?([0-9a-f]{3}|[0-9a-f]{6})$/i.exec(hex.trim());
  if (!m) return null;
  const h = m[1].length === 3 ? m[1].replace(/./g, (c) => c + c) : m[1];
  const ch = [0, 2, 4].map((i) => parseInt(h.slice(i, i + 2), 16) / 255).map((c) => (c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4));
  return 0.2126 * ch[0] + 0.7152 * ch[1] + 0.0722 * ch[2];
}

/** Ink colour for text drawn on a filled `bg`: whichever of near-black / white has more contrast. */
export function inkOn(bg: string): string {
  const L = luminance(bg);
  if (L === null) return '#ffffff';
  const onWhite = 1.05 / (L + 0.05);
  const onBlack = (L + 0.05) / 0.054; // #0b0d12 has luminance ≈ 0.004
  return onBlack > onWhite ? '#0b0d12' : '#ffffff';
}
