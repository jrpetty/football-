/**
 * Deduction Grid (and Extreme): the full solution as a positions × categories
 * grid, with the model's answer laid over the categories the question asked
 * for. The solution comes from the case's auditor notes ("Full solution by
 * position: 1: …"), which are checked against the answer key before use.
 */
import { alnum, statedAnswer, finalTurn, type CaseVisualInput } from './common.ts';

export interface GridCategory {
  name: string;
  values: string[];
}

export interface GridCell {
  truth: string;
  /** What the model put here (asked categories only); '' when it gave nothing for this position. */
  model?: string;
  /** Asked categories only: whether the model's value matches the key. */
  ok?: boolean;
}

export interface GridVisual {
  /** Number of positions. */
  n: number;
  /** Label per position ("1", "9:00", "top-left", …). */
  positions: string[];
  /** What a position is ("spot", "room", …), singular. */
  noun: string;
  categories: GridCategory[];
  /** Indexes into categories that the question asked for. */
  asked: number[];
  /** rows[category][position]. */
  rows: GridCell[][];
  answered: boolean;
  right: number;
  total: number;
  /** Pairs of positions whose values the model swapped (asked categories only). */
  swaps: Array<{ category: string; a: number; b: number }>;
  question: string;
}

/** "- Names: Diego, Hana, Maeve, Tobias" lines under "Categories". */
export function parseGridCategories(prompt: string): GridCategory[] | null {
  const start = prompt.search(/^Categories\b[^\n]*:\s*$/m);
  if (start < 0) return null;
  const lines = prompt.slice(start).split('\n').slice(1);
  const cats: GridCategory[] = [];
  for (const line of lines) {
    const m = line.match(/^\s*-\s*([^:]+):\s*(.+)$/);
    if (!m) {
      if (cats.length && line.trim() === '') break;
      if (cats.length) break;
      continue;
    }
    const body = m[2]!.split(/\.\s+Clue wording/)[0]!.replace(/\.$/, '');
    const values = body.split(/,\s*/).map((v) => v.trim()).filter(Boolean);
    if (values.length < 2) return null;
    cats.push({ name: m[1]!.trim(), values });
  }
  if (cats.length < 2) return null;
  const n = cats[0]!.values.length;
  if (cats.some((c) => c.values.length !== n)) return null;
  return cats;
}

/** "Full solution by position: 1: Maeve, white, trail mix | 2: …" → per position, per category value. */
export function parseGridSolution(notes: string, cats: GridCategory[]): string[][] | null {
  const m = notes.match(/Full solution(?: by position)?:\s*([\s\S]+?)(?:\.\s+(?:Clue kinds|Built in)|\.?\s*$)/);
  if (!m) return null;
  const parts = m[1]!.split(/\s*\|\s*/);
  const n = cats[0]!.values.length;
  if (parts.length !== n) return null;
  const lookup = new Map<string, number>();
  cats.forEach((c, ci) =>
    c.values.forEach((v) => {
      const k = alnum(v);
      lookup.set(k, lookup.has(k) ? -1 : ci);
    }),
  );
  const out: string[][] = cats.map(() => new Array<string>(n).fill(''));
  for (let i = 0; i < n; i++) {
    const pm = parts[i]!.match(/^(\d+):\s*(.+?)\.?$/);
    if (!pm || Number(pm[1]) !== i + 1) return null;
    const vals = pm[2]!.split(/,\s*/);
    if (vals.length !== cats.length) return null;
    for (const v of vals) {
      const ci = lookup.get(alnum(v));
      if (ci === undefined || ci < 0 || out[ci]![i]) return null;
      out[ci]![i] = cats[ci]!.values.find((x) => alnum(x) === alnum(v))!;
    }
  }
  return out;
}

/** Split an answer line "A, B; C, D" (optionally "Countries: A, B; Books: C, D") into groups of values. */
function answerGroups(line: string): string[][] {
  return line
    .split(';')
    .map((g) => g.replace(/^\s*[^:,]{1,40}:\s*/, '').trim())
    .filter(Boolean)
    .map((g) => g.split(/,\s*/).map((v) => v.trim()).filter(Boolean));
}

function positionLabels(prompt: string, question: string, n: number): { labels: string[]; noun: string } {
  const order = question.match(/in this order:\s*([^.?\n]+)/i);
  if (order) {
    const labels = order[1]!.split(/,\s*/).map((s) => s.trim());
    if (labels.length === n) return { labels, noun: 'place' };
  }
  const firstPara = prompt.split('\n\n')[0] ?? '';
  const times = firstPara.match(/\b\d{1,2}:\d{2}\b/g);
  if (times && times.length === n) return { labels: times, noun: 'slot' };
  const nm = question.match(/\b([a-z]+)\s+1\s+(?:to|,|up to)/i) ?? firstPara.match(/\b([a-z]+),?\s+(?:are\s+)?numbered\s+1\b/i);
  const noun = nm ? singular(nm[1]!.toLowerCase()) : 'position';
  return { labels: Array.from({ length: n }, (_, i) => String(i + 1)), noun };
}

const singular = (w: string) => (w.endsWith('s') && !w.endsWith('ss') ? w.slice(0, -1) : w);

export function gridVisual(input: CaseVisualInput): GridVisual | null {
  const prompt = input.turns[0] ?? '';
  const cats = parseGridCategories(prompt);
  if (!cats || !input.notes) return null;
  const sol = parseGridSolution(input.notes, cats);
  if (!sol) return null;
  const n = cats[0]!.values.length;
  const exp = Array.isArray(input.expected) ? String(input.expected[0] ?? '') : typeof input.expected === 'string' ? input.expected : '';
  if (!exp) return null;
  // Which categories did the question ask for? Each expected group must equal one solution row, in position order.
  const asked: number[] = [];
  for (const g of answerGroups(exp)) {
    const ci = sol.findIndex((row) => row.length === g.length && row.every((v, i) => alnum(v) === alnum(g[i]!)));
    if (ci < 0) return null; // the notes disagree with the answer key: do not draw
    asked.push(ci);
  }
  if (!asked.length) return null;
  const question = (finalTurn(input).match(/Question:\s*([^\n]+)/) ?? [])[1] ?? '';
  const { labels, noun } = positionLabels(prompt, question, n);

  const stated = statedAnswer(input);
  const groups = stated ? answerGroups(stated.answer) : [];
  // Accepted answers may drop articles ("comic" for "a comic").
  const same = (a: string, b: string) => alnum(a.replace(/^(?:a|an|the)\s+/i, '')) === alnum(b.replace(/^(?:a|an|the)\s+/i, ''));
  const rows: GridCell[][] = sol.map((row) => row.map((truth) => ({ truth })));
  let right = 0;
  let total = 0;
  const swaps: GridVisual['swaps'] = [];
  asked.forEach((ci, gi) => {
    const given = groups[gi] ?? [];
    for (let i = 0; i < n; i++) {
      const model = given[i] ?? '';
      const ok = !!model && same(model, sol[ci]![i]!);
      rows[ci]![i] = { truth: sol[ci]![i]!, model, ok };
      total++;
      if (ok) right++;
    }
    for (let a = 0; a < n; a++)
      for (let b = a + 1; b < n; b++) {
        const ga = given[a];
        const gb = given[b];
        if (ga && gb && same(ga, sol[ci]![b]!) && same(gb, sol[ci]![a]!)) swaps.push({ category: cats[ci]!.name, a, b });
      }
  });
  // Never contradict the scorer: a passed answer must show every asked cell right.
  if (input.passed === true && right !== total) return null;
  return { n, positions: labels, noun, categories: cats, asked, rows, answered: groups.length > 0, right, total, swaps, question };
}

/** "spot 3", "9:00", "top-left". */
export function placeName(v: GridVisual, i: number): string {
  const l = v.positions[i] ?? String(i + 1);
  return /^\d+$/.test(l) ? `${v.noun} ${l}` : l;
}

/** One plain-English line for the headline. */
export function gridHeadline(v: GridVisual): string {
  if (!v.answered) return 'No answer given';
  if (v.right === v.total) return `Every ${v.noun === 'place' || v.noun === 'position' ? 'cell' : v.noun} right: ${v.right} of ${v.total}`;
  if (v.swaps.length === 1 && v.total - v.right === 2) {
    const s = v.swaps[0]!;
    const numeric = /^\d+$/.test(v.positions[s.a] ?? '') && /^\d+$/.test(v.positions[s.b] ?? '');
    const pair = numeric ? `${v.noun}s ${v.positions[s.a]} and ${v.positions[s.b]}` : `${placeName(v, s.a)} and ${placeName(v, s.b)}`;
    const what = v.asked.length > 1 ? `the ${s.category.toLowerCase()} of ` : '';
    return `Swapped ${what}${pair}: one swap scores zero`;
  }
  return `${v.total - v.right} of ${v.total} cells wrong: scores zero`;
}

