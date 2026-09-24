/**
 * Line diffs for "Fix the Bug": a small LCS diff (files are a few hundred
 * lines at most), unified-diff text for artifacts and compact rows for the
 * replay's diff view.
 */

export type DiffOp = ' ' | '+' | '-';

export interface DiffLine {
  op: DiffOp;
  text: string;
  /** 1-based line number in the old (for ' ' and '-') or new (for '+') file. */
  oldNo?: number;
  newNo?: number;
}

/** Full line-by-line diff (LCS). O(n·m), fine for files under a few thousand lines. */
export function diffLines(before: string, after: string): DiffLine[] {
  const a = before === '' ? [] : before.split('\n');
  const b = after === '' ? [] : after.split('\n');
  // Trim the common prefix and suffix first so the table stays small.
  let pre = 0;
  while (pre < a.length && pre < b.length && a[pre] === b[pre]) pre++;
  let suf = 0;
  while (suf < a.length - pre && suf < b.length - pre && a[a.length - 1 - suf] === b[b.length - 1 - suf]) suf++;
  const A = a.slice(pre, a.length - suf);
  const B = b.slice(pre, b.length - suf);
  const n = A.length;
  const m = B.length;
  const lcs: Uint32Array[] = Array.from({ length: n + 1 }, () => new Uint32Array(m + 1));
  for (let i = n - 1; i >= 0; i--) for (let j = m - 1; j >= 0; j--) lcs[i]![j] = A[i] === B[j] ? lcs[i + 1]![j + 1]! + 1 : Math.max(lcs[i + 1]![j]!, lcs[i]![j + 1]!);
  const out: DiffLine[] = [];
  for (let k = 0; k < pre; k++) out.push({ op: ' ', text: a[k]!, oldNo: k + 1, newNo: k + 1 });
  let i = 0;
  let j = 0;
  while (i < n || j < m) {
    if (i < n && j < m && A[i] === B[j]) {
      out.push({ op: ' ', text: A[i]!, oldNo: pre + i + 1, newNo: pre + j + 1 });
      i++;
      j++;
    } else if (i < n && (j >= m || lcs[i + 1]![j]! >= lcs[i]![j + 1]!)) {
      // Removals first, like `diff -u`.
      out.push({ op: '-', text: A[i]!, oldNo: pre + i + 1 });
      i++;
    } else {
      out.push({ op: '+', text: B[j]!, newNo: pre + j + 1 });
      j++;
    }
  }
  for (let k = 0; k < suf; k++) out.push({ op: ' ', text: a[a.length - suf + k]!, oldNo: a.length - suf + k + 1, newNo: b.length - suf + k + 1 });
  return out;
}

export interface Hunk {
  oldStart: number;
  newStart: number;
  lines: DiffLine[];
}

/** Group a diff into hunks with `context` unchanged lines around each change. */
export function hunks(diff: DiffLine[], context = 2): Hunk[] {
  const changed = diff.map((d, i) => (d.op !== ' ' ? i : -1)).filter((i) => i >= 0);
  if (!changed.length) return [];
  const out: Hunk[] = [];
  let start = Math.max(0, changed[0]! - context);
  let end = Math.min(diff.length - 1, changed[0]! + context);
  const flush = () => {
    const lines = diff.slice(start, end + 1);
    const first = lines[0]!;
    out.push({ oldStart: first.oldNo ?? (lines.find((l) => l.oldNo)?.oldNo ?? 1), newStart: first.newNo ?? (lines.find((l) => l.newNo)?.newNo ?? 1), lines });
  };
  for (const idx of changed.slice(1)) {
    if (idx - context <= end + 1) end = Math.min(diff.length - 1, idx + context);
    else {
      flush();
      start = Math.max(0, idx - context);
      end = Math.min(diff.length - 1, idx + context);
    }
  }
  flush();
  return out;
}

/** Standard unified diff text for one file. Empty string when unchanged. */
export function unifiedDiff(path: string, before: string | null, after: string | null, context = 3): string {
  const d = diffLines(before ?? '', after ?? '');
  const hs = hunks(d, context);
  if (!hs.length) return '';
  const head = [`--- ${before === null ? '/dev/null' : `a/${path}`}`, `+++ ${after === null ? '/dev/null' : `b/${path}`}`];
  for (const h of hs) {
    const oldCount = h.lines.filter((l) => l.op !== '+').length;
    const newCount = h.lines.filter((l) => l.op !== '-').length;
    head.push(`@@ -${h.oldStart},${oldCount} +${h.newStart},${newCount} @@`);
    for (const l of h.lines) head.push(`${l.op}${l.text}`);
  }
  return head.join('\n');
}

export interface DiffRow {
  /** ' ' context, '+' added, '-' removed, '@' hunk header / gap. */
  op: ' ' | '+' | '-' | '@';
  text: string;
  /** Line number shown in the gutter (new file for ' ' and '+', old file for '-'). */
  n?: number;
}

/** Compact diff rows for the replay (context 2, capped at `max` rows). */
export function diffRows(before: string, after: string, max = 48): { rows: DiffRow[]; added: number; removed: number; truncated: boolean } {
  const d = diffLines(before, after);
  const added = d.filter((l) => l.op === '+').length;
  const removed = d.filter((l) => l.op === '-').length;
  const rows: DiffRow[] = [];
  for (const h of hunks(d, 2)) {
    rows.push({ op: '@', text: `line ${h.newStart}` });
    for (const l of h.lines) rows.push({ op: l.op, text: l.text.length > 160 ? `${l.text.slice(0, 159)}…` : l.text, n: l.op === '-' ? l.oldNo : l.newNo });
  }
  const truncated = rows.length > max;
  return { rows: truncated ? rows.slice(0, max) : rows, added, removed, truncated };
}
