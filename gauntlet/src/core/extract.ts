/**
 * Response parsing helpers shared by scorers and programs. Parsing is
 * deliberately lenient about formatting (markdown, bold, trailing
 * punctuation) but strict about content, so a model is never penalised for
 * cosmetic differences — only for failing to state an answer.
 */

/** Standard instruction appended to extractive tests. Part of the protocol fingerprint. */
export const FINAL_ANSWER_INSTRUCTION =
  'When you are finished, write your final answer on its own line, exactly in the form:\nFINAL ANSWER: <answer>';

/** Strip markdown emphasis/backticks that models like to wrap answers in. */
export function stripDecoration(text: string): string {
  return text
    .replace(/\*\*|__|`/g, '')
    .replace(/^\s*[>*-]\s+/, '')
    .trim();
}

/**
 * Extract the value after the last "FINAL ANSWER:" marker.
 * Returns { answer, formatOk }. When the marker is missing, falls back to the
 * last non-empty line with formatOk=false.
 */
export function extractFinalAnswer(text: string): { answer: string; formatOk: boolean } {
  const re = /final\s+answer\s*[:：]\s*(.+)/gi;
  let match: RegExpExecArray | null;
  let last: string | null = null;
  while ((match = re.exec(text)) !== null) last = match[1] ?? '';
  if (last !== null) {
    return { answer: cleanAnswer(last), formatOk: true };
  }
  // Answer might be on the line after a bare "FINAL ANSWER" heading.
  const heading = /final\s+answer\s*[:：]?\s*\n+\s*(.+)/gi;
  let hLast: string | null = null;
  while ((match = heading.exec(text)) !== null) hLast = match[1] ?? '';
  if (hLast !== null) return { answer: cleanAnswer(hLast), formatOk: true };

  const lines = text.split('\n').map((l) => l.trim()).filter(Boolean);
  return { answer: cleanAnswer(lines[lines.length - 1] ?? ''), formatOk: false };
}

function cleanAnswer(raw: string): string {
  let a = stripDecoration(raw);
  a = a.replace(/\s+$/, '');
  // Remove one trailing sentence period ("42." → "42"; decimals like "3.5" are unaffected).
  if (a.endsWith('.') && !a.endsWith('..')) a = a.slice(0, -1);
  return a.trim();
}

/** Parse the first number in a string ("$1,234.50", "−3", "42 apples", "3/4"). */
export function parseNumber(text: string): number | null {
  const cleaned = text.replace(/[−–]/g, '-').replace(/,(?=\d{3}\b)/g, '');
  const frac = cleaned.match(/(-?\d+)\s*\/\s*(\d+)/);
  if (frac && !/\d\.\d/.test(cleaned.slice(0, frac.index))) {
    const n = Number(frac[1]);
    const d = Number(frac[2]);
    if (d !== 0 && /^\s*-?\d+\s*\/\s*\d+/.test(cleaned.slice(frac.index))) return n / d;
  }
  const m = cleaned.match(/-?\d+(?:\.\d+)?(?:e[+-]?\d+)?/i);
  if (!m) return null;
  const v = Number(m[0]);
  return Number.isFinite(v) ? v : null;
}

/** Extract the last fenced code block, optionally preferring given languages. */
export function extractCodeBlock(text: string, langs: string[] = []): { code: string; lang: string } | null {
  const re = /```([\w+-]*)[^\n]*\n([\s\S]*?)```/g;
  const blocks: Array<{ lang: string; code: string }> = [];
  let m: RegExpExecArray | null;
  while ((m = re.exec(text)) !== null) blocks.push({ lang: (m[1] ?? '').toLowerCase(), code: m[2] ?? '' });
  // Unterminated trailing fence (model hit max tokens or forgot to close).
  if (blocks.length === 0) {
    const open = text.match(/```([\w+-]*)[^\n]*\n([\s\S]*)$/);
    if (open) blocks.push({ lang: (open[1] ?? '').toLowerCase(), code: open[2] ?? '' });
  }
  if (blocks.length === 0) return null;
  const wanted = langs.map((l) => l.toLowerCase());
  for (let i = blocks.length - 1; i >= 0; i--) {
    if (wanted.length === 0 || wanted.includes(blocks[i]!.lang)) return blocks[i]!;
  }
  return blocks[blocks.length - 1]!;
}

/**
 * Parse an action line from an environment turn, e.g. "ACTION: MOVE NORTH".
 * Takes the LAST occurrence so models may reason before acting.
 */
export function extractTagged(text: string, tag: string): string | null {
  const re = new RegExp(`^[\\s>*_\`#-]*${tag}\\s*[:：]\\s*(.+)$`, 'gim');
  let m: RegExpExecArray | null;
  let last: string | null = null;
  while ((m = re.exec(text)) !== null) last = m[1] ?? '';
  return last === null ? null : stripDecoration(last);
}

/** Extract every tagged line (e.g. all "A1: ..." answers). */
export function extractAllTagged(text: string, tag: string): string[] {
  const re = new RegExp(`^[\\s>*_\`#-]*${tag}\\s*[:：]\\s*(.+)$`, 'gim');
  const out: string[] = [];
  let m: RegExpExecArray | null;
  while ((m = re.exec(text)) !== null) out.push(stripDecoration(m[1] ?? ''));
  return out;
}

/** Remove ``` fences around JSON and parse it. Returns undefined when invalid. */
export function parseJsonLoose(text: string): unknown {
  const block = extractCodeBlock(text, ['json', '']);
  const candidates = [block?.code, text];
  for (const c of candidates) {
    if (!c) continue;
    const trimmed = c.trim();
    try {
      return JSON.parse(trimmed);
    } catch {
      // Try the outermost {...} or [...] span.
      const start = trimmed.search(/[[{]/);
      const end = Math.max(trimmed.lastIndexOf('}'), trimmed.lastIndexOf(']'));
      if (start >= 0 && end > start) {
        try {
          return JSON.parse(trimmed.slice(start, end + 1));
        } catch {
          /* fall through */
        }
      }
    }
  }
  return undefined;
}

export function normalize(text: string, mode: 'none' | 'trim' | 'lower' | 'alnum' = 'lower'): string {
  switch (mode) {
    case 'none':
      return text;
    case 'trim':
      return text.trim();
    case 'lower':
      return text.trim().toLowerCase().replace(/\s+/g, ' ');
    case 'alnum':
      return text.toLowerCase().replace(/[^a-z0-9]/g, '');
  }
}
