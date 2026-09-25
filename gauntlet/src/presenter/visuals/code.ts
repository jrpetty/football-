/**
 * Coding tests: a results board with one tile per hidden test (pass, fail,
 * timeout, crash), the first failing test's input / expected / got, and the
 * model's code with a tiny syntax highlighter.
 */
import { extractCodeBlock } from '../../core/extract.ts';
import { finalReply, type CaseVisualInput } from './common.ts';

export type TileStatus = 'pass' | 'fail' | 'timeout' | 'error';

export interface CodeTile {
  index: number;
  status: TileStatus;
  label: string;
  detail: string;
  /** Full inputs and expected output from the test file, when available. */
  args?: unknown[];
  expected?: unknown;
  got?: string;
  argChars: number;
}

export interface CodeVisual {
  tiles: CodeTile[];
  groups: Array<{ name: string; hint: string; tiles: CodeTile[] }>;
  passed: number;
  total: number;
  code: string;
  loadError: string | null;
  functionName: string | null;
  firstFail: CodeTile | null;
}

/** Tests whose inputs serialise to more than this many characters count as "large" (stress / speed tests). */
export const LARGE_INPUT_CHARS = 400;

export function tileStatus(passed: boolean, detail: string | undefined): TileStatus {
  if (passed) return 'pass';
  const d = detail ?? '';
  if (/timed out|time or memory limit|killed/i.test(d)) return 'timeout';
  if (/^expected [\s\S]*, got /.test(d)) return 'fail';
  return 'error';
}

export function codeVisual(input: CaseVisualInput): CodeVisual | null {
  const items = input.detail.items ?? [];
  const exp = input.expected as { functionName?: unknown; tests?: unknown } | undefined;
  const tests = exp && Array.isArray(exp.tests) ? (exp.tests as Array<{ args?: unknown; expected?: unknown }>) : null;
  const code = typeof input.detail.extracted === 'string' ? input.detail.extracted : extractCodeBlock(finalReply(input), ['javascript', 'js', 'mjs', 'jsx', 'typescript', 'ts', ''])?.code ?? '';
  const loadError = typeof input.detail.loadError === 'string' ? input.detail.loadError : null;
  if (!items.length && !code) return null;
  const sameTests = !!tests && tests.length === items.length;
  const tiles: CodeTile[] = items.map((it, i) => {
    const t = sameTests ? tests![i] : undefined;
    const args = t && Array.isArray(t.args) ? (t.args as unknown[]) : undefined;
    const status = tileStatus(it.passed, it.detail);
    const got = status === 'fail' ? it.detail!.replace(/^expected [\s\S]*?, got /, '') : undefined;
    return { index: i + 1, status, label: it.label, detail: it.detail ?? '', args, expected: t?.expected, got, argChars: args ? JSON.stringify(args).length : 0 };
  });
  const groups: CodeVisual['groups'] = [];
  if (sameTests) {
    const small = tiles.filter((t) => t.argChars <= LARGE_INPUT_CHARS);
    const large = tiles.filter((t) => t.argChars > LARGE_INPUT_CHARS);
    if (small.length) groups.push({ name: 'Small inputs', hint: 'rules and edge cases', tiles: small });
    if (large.length) groups.push({ name: 'Large inputs', hint: 'stress and speed tests', tiles: large });
  } else if (tiles.length) groups.push({ name: 'Hidden tests', hint: '', tiles });
  return {
    tiles,
    groups,
    passed: tiles.filter((t) => t.status === 'pass').length,
    total: tiles.length,
    code,
    loadError,
    functionName: exp && typeof exp.functionName === 'string' ? exp.functionName : null,
    firstFail: tiles.find((t) => t.status !== 'pass') ?? null,
  };
}

export function codeHeadline(v: CodeVisual): string {
  if (v.loadError) return 'The code did not even load';
  if (!v.total) return 'No tests recorded';
  if (v.passed === v.total) return `All ${v.total} hidden tests pass`;
  const timeouts = v.tiles.filter((t) => t.status === 'timeout').length;
  if (timeouts && timeouts === v.total - v.passed) return `${v.passed} of ${v.total} pass — ${timeouts} too slow`;
  return `${v.passed} of ${v.total} hidden tests pass`;
}

// ─────────────────────────────── highlighter ───────────────────────────────

export type TokKind = 'kw' | 'str' | 'num' | 'com' | 'fn' | 'id' | 'pun' | 'ws';

const KEYWORDS = new Set(
  'break case catch class const continue debugger default delete do else export extends finally for function if import in instanceof let new of return static super switch this throw try typeof var void while with yield async await null undefined true false NaN Infinity'.split(' '),
);

const TOKEN_RE = /(\/\/[^\n]*|\/\*[\s\S]*?(?:\*\/|$))|(`(?:\\[\s\S]|[^`\\])*`?|"(?:\\.|[^"\\\n])*"?|'(?:\\.|[^'\\\n])*'?)|(\b(?:0[xX][\da-fA-F_]+|0[bB][01_]+|\d[\d_]*(?:\.\d+)?(?:[eE][+-]?\d+)?n?)\b)|([A-Za-z_$][\w$]*)|(\s+)|([^\sA-Za-z_$\d])/g;

/** Tokenise JavaScript for colouring. Every input character ends up in exactly one token. */
export function tokenizeJs(src: string): Array<{ k: TokKind; v: string }> {
  const out: Array<{ k: TokKind; v: string }> = [];
  let last = 0;
  for (const m of src.matchAll(TOKEN_RE)) {
    const i = m.index!;
    if (i > last) out.push({ k: 'pun', v: src.slice(last, i) });
    last = i + m[0].length;
    if (m[1]) out.push({ k: 'com', v: m[0] });
    else if (m[2]) out.push({ k: 'str', v: m[0] });
    else if (m[3]) out.push({ k: 'num', v: m[0] });
    else if (m[4]) {
      const next = src.slice(last).match(/^\s*\(/);
      out.push({ k: KEYWORDS.has(m[0]) ? 'kw' : next ? 'fn' : 'id', v: m[0] });
    } else if (m[5]) out.push({ k: 'ws', v: m[0] });
    else out.push({ k: 'pun', v: m[0] });
  }
  if (last < src.length) out.push({ k: 'pun', v: src.slice(last) });
  return out;
}
