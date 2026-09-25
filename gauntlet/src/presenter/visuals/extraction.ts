/**
 * Data extraction (Messy Text to JSON, Extraction: Frontier): the expected
 * JSON and the model's JSON side by side, leaf by leaf, using the verdicts
 * the scorer recorded. Document lines are highlighted only on exact text
 * matches: where a correct value comes from, where a wrong value came from,
 * and lines that correct, retract or change something (the traps).
 */
import { parseJsonLoose } from '../../core/extract.ts';
import { finalReply, type CaseVisualInput } from './common.ts';

export interface JsonLeaf {
  path: string;
  /** Display key (last path segment) and nesting depth for the tree. */
  key: string;
  depth: number;
  expected: unknown;
  /** The model's value as the scorer saw it ('missing' when absent). */
  got: string;
  passed: boolean;
  /** Document line (0-based) the expected value appears on, when exactly one line contains it. */
  sourceLine: number | null;
  /** Document lines containing the model's wrong value. */
  wrongLines: number[];
}

export interface JsonNode {
  key: string;
  depth: number;
  /** Section header for an object / array ("line_items (4 items)"). */
  header: string | null;
  leaf: JsonLeaf | null;
  /** Array-length check result for array headers. */
  lengthOk?: boolean;
  lengthDetail?: string;
}

export interface ExtractionVisual {
  nodes: JsonNode[];
  leaves: JsonLeaf[];
  document: string[];
  /** Lines that correct, retract, cancel or move something. */
  trapLines: number[];
  parsedOk: boolean;
  matched: number;
  total: number;
}

const TRAP_RE = /\b(retract\w*|correct(?:ed|ion|ing)?|cancel\w*|reschedul\w*|moved?|waived?|withdr[ae]w\w*|instead|revised?|supersed\w*|no longer|updated?|changed?|replac\w*|disregard|ignore)\b/i;

function fmt(v: unknown): string {
  const s = JSON.stringify(v);
  return s === undefined ? 'missing' : s.length > 60 ? s.slice(0, 57) + '…' : s;
}

function numberForms(n: number): string[] {
  const out = new Set<string>([String(n), n.toFixed(2)]);
  const withSep = (s: string) => s.replace(/^(\d+)/, (int) => int.replace(/\B(?=(\d{3})+(?!\d))/g, ','));
  out.add(withSep(String(n)));
  out.add(withSep(n.toFixed(2)));
  return [...out].filter((s) => s.replace(/\D/g, '').length >= 2);
}

function linesWith(doc: string[], v: unknown): number[] {
  if (typeof v === 'string') {
    const t = v.trim();
    if (t.length < 3) return [];
    const low = t.toLowerCase();
    return doc.flatMap((l, i) => (l.toLowerCase().includes(low) ? [i] : []));
  }
  if (typeof v === 'number') {
    const forms = numberForms(v);
    if (!forms.length) return [];
    return doc.flatMap((l, i) => (forms.some((f) => new RegExp(`(?<![\\d.,])${f.replace(/[.]/g, '\\.')}(?![\\d]|[.,]\\d)`).test(l)) ? [i] : []));
  }
  return [];
}

/** The document between <<< and >>> in the prompt, split into lines. */
export function documentLines(prompt: string): string[] {
  const m = prompt.match(/<<<\n?([\s\S]*?)\n?>>>/);
  return m ? m[1]!.split('\n') : [];
}

function gotFromDetail(detail: string | undefined): string | null {
  const m = detail?.match(/^expected [\s\S]*?, got ([\s\S]*)$/);
  return m ? m[1]! : null;
}

function atPath(obj: unknown, parts: Array<string | number>): unknown {
  let cur = obj;
  for (const p of parts) {
    if (cur === null || typeof cur !== 'object') return undefined;
    cur = (cur as Record<string | number, unknown>)[p];
  }
  return cur;
}

export function extractionVisual(input: CaseVisualInput): ExtractionVisual | null {
  const exp = input.expected;
  if (!exp || typeof exp !== 'object' || Array.isArray(exp)) return null;
  const doc = documentLines(input.turns[0] ?? '');
  const parsed = parseJsonLoose(finalReply(input));
  const items = new Map((input.detail.items ?? []).map((it) => [it.label, it]));
  const nodes: JsonNode[] = [];
  const leaves: JsonLeaf[] = [];

  const walk = (v: unknown, path: string, parts: Array<string | number>, key: string, depth: number) => {
    if (Array.isArray(v)) {
      const lenItem = items.get(`${path || '$'} has ${v.length} items`);
      nodes.push({ key, depth, header: `${key} · ${v.length} item${v.length === 1 ? '' : 's'}`, leaf: null, lengthOk: lenItem?.passed, lengthDetail: lenItem?.detail });
      v.forEach((x, i) => walk(x, `${path}[${i}]`, [...parts, i], `[${i + 1}]`, depth + 1));
      return;
    }
    if (v && typeof v === 'object') {
      if (path) nodes.push({ key, depth, header: key, leaf: null });
      for (const k of Object.keys(v)) walk((v as Record<string, unknown>)[k], path ? `${path}.${k}` : k, [...parts, k], k, path ? depth + 1 : depth);
      return;
    }
    const it = items.get(path || '$');
    const passed = it ? it.passed : false;
    const got = passed ? fmt(atPath(parsed, parts) ?? v) : gotFromDetail(it?.detail) ?? fmt(atPath(parsed, parts));
    const src = linesWith(doc, v);
    let wrongLines: number[] = [];
    if (!passed && got !== 'missing') {
      let gv: unknown;
      try {
        gv = JSON.parse(got);
      } catch {
        gv = undefined;
      }
      wrongLines = linesWith(doc, gv).slice(0, 3);
    }
    const leaf: JsonLeaf = { path, key, depth, expected: v, got, passed, sourceLine: src.length === 1 ? src[0]! : null, wrongLines };
    leaves.push(leaf);
    nodes.push({ key, depth, header: null, leaf });
  };
  walk(exp, '', [], '', 0);
  if (!leaves.length) return null;
  const trapLines = doc.flatMap((l, i) => (TRAP_RE.test(l) ? [i] : []));
  // Count the way the scorer does (array lengths are checks too) when its items were recorded.
  const recorded = input.detail.items ?? [];
  const matched = recorded.length ? recorded.filter((i) => i.passed).length : leaves.filter((l) => l.passed).length;
  const total = recorded.length || leaves.length;
  return { nodes, leaves, document: doc, trapLines, parsedOk: parsed !== undefined, matched, total };
}

export function extractionHeadline(v: ExtractionVisual): string {
  if (!v.parsedOk) return 'No valid JSON in the reply';
  if (v.matched === v.total) return `All ${v.total} fields exact`;
  const wrong = v.leaves.filter((l) => !l.passed);
  if (wrong.length === 1) return `One field wrong: ${wrong[0]!.path} (${wrong[0]!.got} instead of ${fmt(wrong[0]!.expected)})`;
  return `${v.total - v.matched} of ${v.total} checks wrong`;
}
