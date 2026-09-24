import type { Constraint, ScoreBreakdownItem } from '../core/types.ts';

/**
 * Machine-verifiable instruction-following checks. Definitions are published
 * on the Methodology page so every count is unambiguous:
 *  - word: whitespace-separated token containing a letter or digit
 *  - sentence: text segment ending in . ! or ? followed by whitespace/end
 *  - paragraph: block separated by one or more blank lines
 *  - line: non-empty line; bullet: line starting with -, *, • or "1." / "1)"
 */

const hasAlnum = /[\p{L}\p{N}]/u;

export function words(text: string): string[] {
  return text.split(/\s+/).filter((w) => hasAlnum.test(w));
}

export function sentences(text: string): string[] {
  return text
    .split(/(?<=[.!?])["'”’)\]]*\s+|(?<=[.!?])["'”’)\]]*$/)
    .map((s) => s.trim())
    .filter((s) => hasAlnum.test(s));
}

export function paragraphs(text: string): string[] {
  return text.split(/\n\s*\n/).map((p) => p.trim()).filter(Boolean);
}

export function lines(text: string): string[] {
  return text.split('\n').map((l) => l.trim()).filter(Boolean);
}

const BULLET_RE = /^\s*(?:[-*•]|\d+[.)])\s+/;

function countOccurrences(haystack: string, needle: string): number {
  if (!needle) return 0;
  let count = 0;
  let idx = haystack.indexOf(needle);
  while (idx !== -1) {
    count++;
    idx = haystack.indexOf(needle, idx + needle.length);
  }
  return count;
}

function stripFences(text: string): string {
  const t = text.trim();
  const m = t.match(/^```[\w-]*\s*\n([\s\S]*?)\n?```$/);
  return m ? m[1]!.trim() : t;
}

function range(n: number, min?: number, max?: number): boolean {
  return (min === undefined || n >= min) && (max === undefined || n <= max);
}

function rangeLabel(noun: string, min?: number, max?: number): string {
  if (min !== undefined && max !== undefined) return min === max ? `exactly ${min} ${noun}` : `${min}–${max} ${noun}`;
  if (min !== undefined) return `at least ${min} ${noun}`;
  if (max !== undefined) return `at most ${max} ${noun}`;
  return noun;
}

export function checkConstraint(text: string, c: Constraint): ScoreBreakdownItem {
  const fold = (s: string, cs?: boolean) => (cs ? s : s.toLowerCase());
  switch (c.check) {
    case 'word_count': {
      const n = words(text).length;
      return { label: rangeLabel('words', c.min, c.max), passed: range(n, c.min, c.max), detail: `${n} words` };
    }
    case 'sentence_count': {
      const n = sentences(text).length;
      return { label: rangeLabel('sentences', c.min, c.max), passed: range(n, c.min, c.max), detail: `${n} sentences` };
    }
    case 'paragraph_count': {
      const n = paragraphs(text).length;
      return { label: rangeLabel('paragraphs', c.min, c.max), passed: range(n, c.min, c.max), detail: `${n} paragraphs` };
    }
    case 'line_count': {
      const n = lines(text).length;
      return { label: rangeLabel('lines', c.min, c.max), passed: range(n, c.min, c.max), detail: `${n} lines` };
    }
    case 'bullet_count': {
      const n = text.split('\n').filter((l) => BULLET_RE.test(l)).length;
      return { label: rangeLabel('bullet points', c.min, c.max), passed: range(n, c.min, c.max), detail: `${n} bullets` };
    }
    case 'include': {
      const n = countOccurrences(fold(text, c.caseSensitive), fold(c.text, c.caseSensitive));
      const min = c.min ?? 1;
      const passed = range(n, min, c.max);
      const label = c.max !== undefined || min !== 1 ? `includes "${c.text}" ${rangeLabel('times', min, c.max)}` : `includes "${c.text}"`;
      return { label, passed, detail: `found ${n}×` };
    }
    case 'exclude': {
      const n = countOccurrences(fold(text, c.caseSensitive), fold(c.text, c.caseSensitive));
      return { label: `never says "${c.text}"`, passed: n === 0, detail: n ? `found ${n}×` : undefined };
    }
    case 'no_letter': {
      const letter = c.letter.toLowerCase();
      const n = countOccurrences(text.toLowerCase(), letter);
      return { label: `no letter "${c.letter}"`, passed: n === 0, detail: n ? `used ${n}×` : undefined };
    }
    case 'starts_with': {
      const passed = fold(text.trim(), c.caseSensitive).startsWith(fold(c.text, c.caseSensitive));
      return { label: `starts with "${c.text}"`, passed, detail: passed ? undefined : `starts "${text.trim().slice(0, 40)}"` };
    }
    case 'ends_with': {
      const passed = fold(text.trim(), c.caseSensitive).endsWith(fold(c.text, c.caseSensitive));
      return { label: `ends with "${c.text}"`, passed, detail: passed ? undefined : `ends "${text.trim().slice(-40)}"` };
    }
    case 'all_lowercase':
      return { label: 'all lowercase', passed: text === text.toLowerCase() };
    case 'all_uppercase':
      return { label: 'all uppercase', passed: text === text.toUpperCase() };
    case 'no_commas': {
      const n = (text.match(/[,，]/g) ?? []).length;
      return { label: 'no commas', passed: n === 0, detail: n ? `${n} commas` : undefined };
    }
    case 'json': {
      try {
        JSON.parse(stripFences(text));
        return { label: 'valid JSON', passed: true };
      } catch (e) {
        return { label: 'valid JSON', passed: false, detail: (e as Error).message.slice(0, 80) };
      }
    }
    case 'json_keys': {
      let obj: unknown;
      try {
        obj = JSON.parse(stripFences(text));
      } catch {
        return { label: `JSON with keys ${c.keys.join(', ')}`, passed: false, detail: 'not valid JSON' };
      }
      const missing = obj && typeof obj === 'object' && !Array.isArray(obj) ? c.keys.filter((k) => !(k in (obj as object))) : c.keys;
      return { label: `JSON with keys ${c.keys.join(', ')}`, passed: missing.length === 0, detail: missing.length ? `missing ${missing.join(', ')}` : undefined };
    }
    case 'regex': {
      let re: RegExp;
      try {
        re = new RegExp(c.pattern, c.flags);
      } catch {
        return { label: `matches /${c.pattern}/`, passed: false, detail: 'invalid pattern' };
      }
      const should = c.shouldMatch ?? true;
      return { label: `${should ? 'matches' : 'does not match'} /${c.pattern}/${c.flags ?? ''}`, passed: re.test(text) === should };
    }
    case 'max_word_length': {
      const longest = words(text)
        .map((w) => w.replace(/[^\p{L}\p{N}'-]/gu, ''))
        .reduce((a, w) => (w.length > a.length ? w : a), '');
      return { label: `no word longer than ${c.max} letters`, passed: longest.length <= c.max, detail: longest.length > c.max ? `"${longest}"` : undefined };
    }
    case 'acrostic': {
      const initials = lines(text)
        .map((l) => l.replace(/^[^\p{L}]+/u, '').charAt(0))
        .join('');
      const target = c.word.replace(/[^\p{L}]/gu, '');
      return { label: `acrostic spells "${c.word}"`, passed: initials.toLowerCase() === target.toLowerCase(), detail: `spells "${initials}"` };
    }
    case 'each_line_starts_with': {
      const bad = lines(text).filter((l) => !l.startsWith(c.text));
      return { label: `every line starts with "${c.text}"`, passed: bad.length === 0, detail: bad.length ? `${bad.length} lines don't` : undefined };
    }
    case 'title_case_lines': {
      const bad = lines(text).filter((l) => words(l).some((w) => /^\p{Ll}/u.test(w.replace(/^[^\p{L}]+/u, ''))));
      return { label: 'every word capitalised', passed: bad.length === 0, detail: bad.length ? `${bad.length} lines not title case` : undefined };
    }
  }
}

export function checkConstraints(text: string, constraints: Constraint[]): ScoreBreakdownItem[] {
  return constraints.map((c) => checkConstraint(text, c));
}
