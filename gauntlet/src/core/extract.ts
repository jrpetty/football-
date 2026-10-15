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
  let a = stripLatex(stripDecoration(raw));
  a = a.replace(/\s+$/, '');
  // Remove one trailing sentence period ("42." → "42"; decimals like "3.5" are unaffected).
  if (a.endsWith('.') && !a.endsWith('..')) a = a.slice(0, -1);
  return a.trim();
}

const LATEX_WRAPPERS = /\\(?:boxed|fbox|text|textbf|textit|textrm|texttt|textnormal|mathrm|mathbf|mathit|mathsf|mathtt|operatorname|mbox|displaystyle)\s*\{([^{}]*)\}/g;

/**
 * Unwrap the LaTeX that models put around a final answer, keeping the content: `$\boxed{42}$` → `42`,
 * `\text{Eris}` → `Eris`, `\frac{3}{4}` → `3/4`, `6{,}589{,}440` and `6\,589\,440` → `6,589,440`,
 * `2\pi \approx 6.28` → `2π ≈ 6.28`, `50\%` → `50%`. Text without LaTeX is returned unchanged
 * (a lone "$" in "$1,234" is a currency sign and stays).
 */
export function stripLatex(text: string): string {
  if (!/[\\$]|\{,\}/.test(text)) return text;
  let a = text;
  const hasCommand = /\\[a-zA-Z]|\\[,;:! ]|\{,\}/.test(a);
  // Math delimiters: \( \) \[ \] always; $ only around LaTeX or when it wraps the whole answer.
  a = a.replace(/\\[()[\]]/g, ' ');
  if (hasCommand || /^\s*\$[^$]+\$\s*$/.test(a) || /^\s*\$\$[\s\S]+\$\$\s*$/.test(a)) a = a.replace(/\$/g, '');
  for (let i = 0; i < 6; i++) {
    const before = a;
    a = a.replace(LATEX_WRAPPERS, '$1').replace(/\\[dt]?frac\s*\{([^{}]*)\}\s*\{([^{}]*)\}/g, (_m, n: string, d: string) => (/^\s*-?[\d.]+\s*$/.test(n) && /^\s*[\d.]+\s*$/.test(d) ? `${n.trim()}/${d.trim()}` : `(${n})/(${d})`));
    if (a === before) break;
  }
  return a
    .replace(/(\d)\{,\}(?=\d)/g, '$1,')
    .replace(/(\d)\\[,;:! ](?=\d)/g, '$1')
    .replace(/\\[,;:! ]/g, ' ')
    .replace(/\\%/g, '%')
    .replace(/\\(?:approx|simeq|sim)\b/g, '≈')
    .replace(/\\times\b/g, '×')
    .replace(/\\c?dot\b/g, '·')
    .replace(/\\pi\b/g, 'π')
    .replace(/\\(?:left|right)\s*([()[\]|.])/g, (_m, b: string) => (b === '.' ? '' : b))
    .replace(/\^\s*\{\s*\\circ\s*\}|\^\s*\\circ\b/g, '°')
    .replace(/\s+/g, ' ')
    .trim();
}

const NUMBER_WORDS: Record<string, number> = {
  zero: 0, one: 1, two: 2, three: 3, four: 4, five: 5, six: 6, seven: 7, eight: 8, nine: 9, ten: 10,
  eleven: 11, twelve: 12, thirteen: 13, fourteen: 14, fifteen: 15, sixteen: 16, seventeen: 17, eighteen: 18, nineteen: 19,
  twenty: 20, thirty: 30, forty: 40, fifty: 50, sixty: 60, seventy: 70, eighty: 80, ninety: 90,
};

/**
 * Parse the first number in a string ("$1,234.50", "−3", "42 apples", "3/4", "6 589 440" with thin spaces).
 * A fraction counts only when it is the first number ("50 (i.e. 1/2)" is 50, not 0.5).
 */
export function parseNumber(text: string): number | null {
  const cleaned = text
    .replace(/[−–‒﹣－]/g, '-')
    .replace(/(\d)\{,\}(?=\d{3}(?!\d))/g, '$1')
    .replace(/(\d)[   ](?=\d{3}(?!\d))/g, '$1')
    .replace(/,(?=\d{3}\b)/g, '');
  const m = /-?\d+(?:\.\d+)?(?:e[+-]?\d+)?/i.exec(cleaned);
  if (!m) return null;
  const frac = /^(-?\d+)\s*\/\s*(\d+)(?![\d.])/.exec(cleaned.slice(m.index));
  if (frac) {
    const d = Number(frac[2]);
    if (d !== 0) return Number(frac[1]) / d;
  }
  const v = Number(m[0]);
  return Number.isFinite(v) ? v : null;
}

/**
 * The number a FINAL ANSWER states. Like `parseNumber`, plus:
 *  - a stated calculation counts by its result: "x = 16", "m + n = 1835", "2π ≈ 6.28", "3/8 = 37.5%"
 *    (only when the last part is a plain number and the first part is a variable or an expression,
 *    so "1835 with m = 1234" stays 1835);
 *  - a number written in words when there are no digits ("four", "twenty-one").
 */
export function parseAnswerNumber(answer: string): number | null {
  const parts = answer.split(/\s*(?:=|≈|≃|≅)\s*/);
  if (parts.length > 1) {
    const last = parts[parts.length - 1]!;
    const first = parts[0]!;
    const plainLast = /^[-−+]?\s*[$€£]?\s*\d[\d,]*(?:\.\d+)?\s*%?\s*[a-zA-Zµ°/ ]*$/.test(last.trim());
    const exprFirst = !/\d/.test(first) || /^\s*[-−+]?\s*[$€£]?\d[\d,.]*\s*(?:[π×*·/^+\-−√!(]|$)/.test(first);
    if (plainLast && exprFirst) return parseNumber(last);
  }
  if (!/\d/.test(answer) && !/\bno\s+one\b/i.test(answer)) {
    const w = /\b(zero|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|thirteen|fourteen|fifteen|sixteen|seventeen|eighteen|nineteen|twenty|thirty|forty|fifty|sixty|seventy|eighty|ninety)(?:[\s-](one|two|three|four|five|six|seven|eight|nine))?\b/i.exec(answer);
    if (w) {
      const tens = NUMBER_WORDS[w[1]!.toLowerCase()]!;
      const units = w[2] && tens >= 20 && tens % 10 === 0 ? NUMBER_WORDS[w[2].toLowerCase()]! : 0;
      const neg = /\b(minus|negative)\s+$/i.test(answer.slice(0, w.index));
      return (neg ? -1 : 1) * (tens + units);
    }
  }
  return parseNumber(answer);
}

/** Every fenced code block, in order (an unterminated trailing fence counts when there is no closed block). */
export function extractCodeBlocks(text: string): Array<{ code: string; lang: string }> {
  const re = /```([\w+-]*)[^\n]*\n([\s\S]*?)```/g;
  const blocks: Array<{ lang: string; code: string }> = [];
  let m: RegExpExecArray | null;
  while ((m = re.exec(text)) !== null) blocks.push({ lang: (m[1] ?? '').toLowerCase(), code: m[2] ?? '' });
  // Unterminated trailing fence (model hit max tokens or forgot to close).
  if (blocks.length === 0) {
    const open = text.match(/```([\w+-]*)[^\n]*\n([\s\S]*)$/);
    if (open) blocks.push({ lang: (open[1] ?? '').toLowerCase(), code: open[2] ?? '' });
  }
  return blocks;
}

/** Extract the last fenced code block, optionally preferring given languages. */
export function extractCodeBlock(text: string, langs: string[] = []): { code: string; lang: string } | null {
  const blocks = extractCodeBlocks(text);
  if (blocks.length === 0) return null;
  const wanted = langs.map((l) => l.toLowerCase());
  for (let i = blocks.length - 1; i >= 0; i--) {
    if (wanted.length === 0 || wanted.includes(blocks[i]!.lang)) return blocks[i]!;
  }
  return blocks[blocks.length - 1]!;
}

/**
 * Remove a reasoning block that some providers put in the reply text itself (DeepSeek-R1 style
 * `<think>…</think>`, also `<thinking>` and `<reasoning>`), so only the visible answer is scored. Only a
 * block at the very start of the reply is removed; an unclosed one means the model never left its
 * reasoning, so nothing visible remains.
 */
export function stripReasoning(text: string): string {
  const m = /^\s*<(think|thinking|reasoning)>/i.exec(text);
  if (!m) return text;
  const close = new RegExp(`</${m[1]}>`, 'i').exec(text);
  return close ? stripReasoning(text.slice(close.index + close[0].length)) : '';
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

/** End index (inclusive) of the JSON-like value that opens at `start`, matching brackets outside strings; -1 if unbalanced. */
function balancedEnd(text: string, start: number): number {
  const stack: string[] = [];
  let inString = false;
  for (let i = start; i < text.length; i++) {
    const ch = text[i]!;
    if (inString) {
      if (ch === '\\') i++;
      else if (ch === '"') inString = false;
      continue;
    }
    if (ch === '"') inString = true;
    else if (ch === '{' || ch === '[') stack.push(ch === '{' ? '}' : ']');
    else if (ch === '}' || ch === ']') {
      if (stack.pop() !== ch) return -1;
      if (stack.length === 0) return i;
    }
  }
  return -1;
}

/** Every top-level JSON object or array embedded in prose, in order of appearance. */
function embeddedJsonValues(text: string): unknown[] {
  const out: unknown[] = [];
  if (text.length > 400_000) return out;
  let attempts = 0;
  for (let i = 0; i < text.length && attempts < 2000; i++) {
    const ch = text[i];
    if (ch !== '{' && ch !== '[') continue;
    attempts++;
    const end = balancedEnd(text, i);
    if (end < 0) continue;
    try {
      out.push(JSON.parse(text.slice(i, end + 1)));
      i = end;
    } catch {
      /* a bracket in prose, e.g. "[see above]": try the next opener */
    }
  }
  return out;
}

/**
 * Remove ``` fences around JSON and parse it. Returns undefined when invalid.
 * Order: the last ```json (or unlabelled) block, the whole reply, then the JSON values embedded in the
 * prose. Among embedded values the LAST one counts (a reply may echo the requested format or a draft
 * before its final answer); with `expected`, the last value of the same kind (object/array) that shares
 * a top-level key with it is preferred, so a stray "[1]" after the answer is not taken for it.
 */
export function parseJsonLoose(text: string, expected?: unknown): unknown {
  const block = extractCodeBlock(text, ['json', '']);
  for (const c of [block?.code, text]) {
    if (!c) continue;
    try {
      return JSON.parse(c.trim());
    } catch {
      /* fall through */
    }
  }
  const values = [...(block ? embeddedJsonValues(block.code) : []), ...embeddedJsonValues(text)];
  if (values.length === 0) return undefined;
  if (expected !== undefined && expected !== null && typeof expected === 'object') {
    const isArr = Array.isArray(expected);
    const sameKind = values.filter((v) => v !== null && typeof v === 'object' && Array.isArray(v) === isArr);
    if (!isArr) {
      const keys = Object.keys(expected as object);
      const sharing = sameKind.filter((v) => keys.some((k) => Object.prototype.hasOwnProperty.call(v, k)));
      if (sharing.length) return sharing[sharing.length - 1];
    }
    if (sameKind.length) return sameKind[sameKind.length - 1];
  }
  return values[values.length - 1];
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
