/**
 * Answer / fact normalisation shared by needle-haystack and chain-of-whispers.
 *
 * normalizeText() lower-cases, folds typographic punctuation, turns number
 * words ("three hundred and seventeen", "twenty-first") into digits, strips
 * thousands separators and ordinal suffixes, and collapses everything that is
 * not a letter or digit into single spaces. Phrase matching is then done on
 * whole words, so "venn" never matches "fenn" or "venner".
 */

const UNITS: Record<string, number> = {
  zero: 0, one: 1, two: 2, three: 3, four: 4, five: 5, six: 6, seven: 7, eight: 8, nine: 9, ten: 10,
  eleven: 11, twelve: 12, thirteen: 13, fourteen: 14, fifteen: 15, sixteen: 16, seventeen: 17, eighteen: 18, nineteen: 19,
};
const TENS: Record<string, number> = { twenty: 20, thirty: 30, forty: 40, fifty: 50, sixty: 60, seventy: 70, eighty: 80, ninety: 90 };
const ORDINAL_UNITS: Record<string, number> = {
  first: 1, second: 2, third: 3, fourth: 4, fifth: 5, sixth: 6, seventh: 7, eighth: 8, ninth: 9, tenth: 10,
  eleventh: 11, twelfth: 12, thirteenth: 13, fourteenth: 14, fifteenth: 15, sixteenth: 16, seventeenth: 17,
  eighteenth: 18, nineteenth: 19,
};
const ORDINAL_TENS: Record<string, number> = {
  twentieth: 20, thirtieth: 30, fortieth: 40, fiftieth: 50, sixtieth: 60, seventieth: 70, eightieth: 80, ninetieth: 90,
};
const SCALES: Record<string, number> = { hundred: 100, thousand: 1000 };

function isNumberWord(w: string): boolean {
  return w in UNITS || w in TENS || w in SCALES || w in ORDINAL_UNITS || w in ORDINAL_TENS || w === 'hundredth' || w === 'thousandth';
}

/**
 * Parses a run of number words ("three hundred and seventeen", "twenty one").
 * Returns null when the words do not form a number.
 */
export function parseNumberWords(words: string[]): number | null {
  let total = 0;
  let current = 0;
  let seen = false;
  for (const w of words) {
    if (w === 'and') continue;
    if (w in UNITS) current += UNITS[w]!;
    else if (w in TENS) current += TENS[w]!;
    else if (w in ORDINAL_UNITS) current += ORDINAL_UNITS[w]!;
    else if (w in ORDINAL_TENS) current += ORDINAL_TENS[w]!;
    else if (w === 'hundred' || w === 'hundredth') current = (current || 1) * 100;
    else if (w === 'thousand' || w === 'thousandth') {
      total += (current || 1) * 1000;
      current = 0;
    } else return null;
    seen = true;
  }
  return seen ? total + current : null;
}

/** Replaces spelled-out numbers with digits ("a hundred and six" -> "106", "twenty-first" -> "21"). */
export function wordsToDigits(text: string): string {
  const pre = text.replace(/\b(?:a|an)\s+(hundred|thousand)\b/gi, 'one $1');
  const tokens = pre.match(/[A-Za-z]+|[^A-Za-z]+/g) ?? [];
  const out: string[] = [];
  let i = 0;
  while (i < tokens.length) {
    const lower = tokens[i]!.toLowerCase();
    if (isNumberWord(lower)) {
      const words = [lower];
      let end = i;
      let j = i + 1;
      while (j + 1 < tokens.length && /^(\s+|\s*-\s*)$/.test(tokens[j]!)) {
        const next = tokens[j + 1]!.toLowerCase();
        if (isNumberWord(next)) {
          words.push(next);
          end = j + 1;
          j += 2;
          continue;
        }
        const after = (tokens[j + 3] ?? '').toLowerCase();
        if (next === 'and' && /^\s+$/.test(tokens[j + 2] ?? '') && isNumberWord(after) && words.some((w) => w === 'hundred' || w === 'thousand')) {
          words.push('and', after);
          end = j + 3;
          j += 4;
          continue;
        }
        break;
      }
      const value = parseNumberWords(words);
      if (value !== null) {
        out.push(String(value));
        i = end + 1;
        continue;
      }
    }
    out.push(tokens[i]!);
    i++;
  }
  return out.join('');
}

/** Lower-case, fold punctuation, digits for number words, whole-word friendly spacing. */
export function normalizeText(text: string): string {
  let t = text
    .toLowerCase()
    .replace(/[‘’`´]/g, "'")
    .replace(/[“”]/g, '"')
    .replace(/[‐‑‒–—−]/g, '-');
  t = wordsToDigits(t);
  t = t
    .replace(/(\d),(?=\d{3}\b)/g, '$1')
    .replace(/\b(\d+)(?:st|nd|rd|th)\b/g, '$1')
    .replace(/'s\b/g, '')
    .replace(/[^a-z0-9.]+/g, ' ')
    .replace(/(?<!\d)\.|\.(?!\d)/g, ' ')
    .replace(/\s+/g, ' ')
    .trim();
  return t;
}

/** Whole-word phrase containment on normalised text. */
export function containsPhrase(haystackNorm: string, phrase: string): boolean {
  const p = normalizeText(phrase);
  if (!p) return false;
  return ` ${haystackNorm} `.includes(` ${p} `);
}

/** All numbers in a text (after number-word normalisation). */
export function extractNumbers(text: string): number[] {
  const norm = normalizeText(text);
  return [...norm.matchAll(/(?<![a-z0-9.])\d+(?:\.\d+)?(?![a-z0-9])/g)].map((m) => Number(m[0]));
}

/** Counts words the way a human would ("well-known" is one word, numbers count). */
export function countWords(text: string): number {
  const m = text.trim().match(/[^\s]+/g);
  return m ? m.filter((w) => /[\p{L}\p{N}]/u.test(w)).length : 0;
}

/** Keeps the first `n` words (as counted by countWords), preserving the original formatting. */
export function truncateWords(text: string, n: number): string {
  let count = 0;
  const re = /\S+/g;
  let m: RegExpExecArray | null;
  while ((m = re.exec(text)) !== null) {
    if (/[\p{L}\p{N}]/u.test(m[0])) count++;
    if (count === n) return text.slice(0, m.index + m[0].length);
  }
  return text;
}
