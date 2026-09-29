/**
 * Leaf-by-leaf comparison of a JSON answer with its answer key, for display
 * (the real score comes from src/scoring/json-compare.ts). Strings compare
 * trimmed and case-insensitively, numbers exactly, like the scorer's defaults.
 */
export interface JsonDiffRow {
  path: string;
  expected: unknown;
  actual: unknown;
  /** match: equal · wrong: different · missing: not in the answer · extra: not in the key */
  state: 'match' | 'wrong' | 'missing' | 'extra';
}

function isObj(v: unknown): v is Record<string, unknown> {
  return !!v && typeof v === 'object' && !Array.isArray(v);
}

function same(a: unknown, b: unknown): boolean {
  if (typeof a === 'string' && typeof b === 'string') return a.trim().toLowerCase().replace(/\s+/g, ' ') === b.trim().toLowerCase().replace(/\s+/g, ' ');
  if (typeof a === 'number' && typeof b === 'string') return String(a) === b.trim();
  if (typeof b === 'number' && typeof a === 'string') return String(b) === a.trim();
  return a === b || (a === undefined && b === null) || (a === null && b === undefined);
}

export function jsonDiff(expected: unknown, actual: unknown, path = '', out: JsonDiffRow[] = []): JsonDiffRow[] {
  if (out.length > 2000) return out;
  if (isObj(expected)) {
    const a = isObj(actual) ? actual : {};
    for (const k of Object.keys(expected)) jsonDiff(expected[k], a[k], path ? `${path}.${k}` : k, out);
    if (isObj(actual)) for (const k of Object.keys(actual)) if (!(k in expected)) out.push({ path: path ? `${path}.${k}` : k, expected: undefined, actual: actual[k], state: 'extra' });
    return out;
  }
  if (Array.isArray(expected)) {
    const a = Array.isArray(actual) ? actual : [];
    expected.forEach((e, i) => jsonDiff(e, a[i], `${path}[${i}]`, out));
    for (let i = expected.length; i < a.length; i++) out.push({ path: `${path}[${i}]`, expected: undefined, actual: a[i], state: 'extra' });
    return out;
  }
  out.push({ path: path || '(value)', expected, actual, state: actual === undefined ? 'missing' : same(expected, actual) ? 'match' : 'wrong' });
  return out;
}
