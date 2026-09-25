/**
 * Tiny, dependency-free tokenizer for one line of JavaScript / TypeScript /
 * JSON / Python (keywords, strings, numbers, comments, calls, operators).
 * Plain data, so it is unit-tested; CodeSyntax.tsx turns tokens into spans.
 */

export type TokenClass = 'kw' | 'str' | 'num' | 'com' | 'fn' | 'type' | 'op';
export interface CodeToken {
  t: string;
  c?: TokenClass;
}

const KEYWORDS = new Set(
  'const let var function return if else elif for while do switch case break continue new class extends import export from default async await try catch except finally throw raise typeof instanceof in of this self null None undefined true True false False void yield static get set delete interface type enum implements readonly def lambda pass not and or is with as'.split(
    ' ',
  ),
);

const REST = String.raw`('(?:[^'\\]|\\.)*'?|"(?:[^"\\]|\\.)*"?|\`(?:[^\`\\]|\\.)*\`?)|(\b\d[\d_]*(?:\.\d+)?(?:e[+-]?\d+)?\b)|([A-Za-z_$][\w$]*)(\s*\()?|([=!<>]=?=?|&&|\|\||=>|[+\-*/%]=?)`;
const TOKEN_JS = new RegExp(String.raw`(\/\/.*$|\/\*.*?\*\/)|` + REST, 'g');
const TOKEN_HASH = new RegExp(String.raw`(#.*$)|` + REST, 'g');

/** Tokens for one line; joined back together they always equal the input. `lang` 'md' / 'text' gives one plain token. */
export function tokenizeLine(text: string, lang = 'js'): CodeToken[] {
  if (lang === 'md' || lang === 'text' || !text) return [{ t: text }];
  const re = lang === 'py' || lang === 'sh' ? TOKEN_HASH : TOKEN_JS;
  const out: CodeToken[] = [];
  const plain = (t: string) => {
    if (!t) return;
    const prev = out[out.length - 1];
    if (prev && !prev.c) prev.t += t;
    else out.push({ t });
  };
  let last = 0;
  for (const m of text.matchAll(re)) {
    const i = m.index ?? 0;
    plain(text.slice(last, i));
    const [whole, comment, str, num, word, call, op] = m;
    last = i + whole.length;
    if (comment !== undefined) out.push({ t: comment, c: 'com' });
    else if (str !== undefined) out.push({ t: str, c: 'str' });
    else if (num !== undefined) out.push({ t: num, c: 'num' });
    else if (word !== undefined) {
      const c: TokenClass | undefined = KEYWORDS.has(word) ? 'kw' : call ? 'fn' : /^[A-Z]/.test(word) ? 'type' : undefined;
      if (c) out.push({ t: word, c });
      else plain(word);
      if (call) plain(call);
    } else if (op !== undefined) out.push({ t: op, c: 'op' });
    else plain(whole);
  }
  plain(text.slice(last));
  return out;
}

/** Language from a file path ("src/money.js" → "js"). */
export function langOf(path: string): string {
  const ext = path.split('.').pop()?.toLowerCase() ?? '';
  if (ext === 'py') return 'py';
  if (ext === 'sh') return 'sh';
  if (ext === 'md' || ext === 'txt' || ext === 'csv') return 'md';
  return 'js';
}
