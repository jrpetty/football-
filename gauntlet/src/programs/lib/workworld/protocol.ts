/**
 * Work-world action protocol: one action per reply.
 *
 *   (any reasoning)
 *   MEMO: optional scratchpad line, shown back every turn
 *   ACTION: SEND tom@example.com | Moving our 1:1
 *   the message text, on the lines after the ACTION line
 *
 * Generous about style (markdown, backticks, quotes, case, "CREATE EVENT" vs "CREATE_EVENT", "|" or ";"
 * between arguments, a fenced body, a body written as the last "|" argument), strict about content: an
 * unknown tool or a missing argument is an invalid action, never a guess. Never throws on model output.
 */
import { stripDecoration } from '../../../core/extract.ts';

export const MEMO_LIMIT = 400;

export interface ParsedAction {
  /** Canonical tool name, or null when no action could be read. */
  name: string | null;
  /** Everything after the tool name on the ACTION line (decoration stripped). */
  argText: string;
  /** argText split on "|" (or ";" when there is no "|"), trimmed; [] when empty. */
  args: string[];
  /** Text after the ACTION line (fences removed); falls back to the last fenced block before it. */
  body: string;
  /** The action line as written (cleaned), for logs and the replay. */
  line: string;
  /** The last MEMO: line, or null when the reply has none. */
  memo: string | null;
  /** Earlier ACTION lines that were ignored (the last one counts). */
  ignored: number;
  /** Why no action could be read. */
  error: string | null;
}

const ACTION_RE = /^[\s>*_`#-]*(?:\*\*|__)?\s*ACTION\s*(?:\*\*|__)?\s*[:：]\s*(.*)$/i;
const MEMO_RE = /^[\s>*_`#-]*(?:\*\*|__)?\s*MEMO\s*(?:\*\*|__)?\s*[:：]\s*(.*)$/i;

/** Remove one surrounding code fence and a leading "Body:" / "Message:" label. */
export function cleanBody(text: string): string {
  let t = text.replace(/\r/g, '');
  const fenced = /^\s*```[^\n]*\n([\s\S]*?)\n?```\s*$/.exec(t);
  if (fenced) t = fenced[1]!;
  else t = t.replace(/^\s*```[^\n]*\n/, '').replace(/\n```\s*$/, '');
  t = t.replace(/^\s*(?:\*\*)?(?:body|message|text|email|note)(?:\*\*)?\s*[:：]\s*/i, '');
  return t.replace(/^\s*\n/, '').replace(/\s+$/, '');
}

function cleanCommand(raw: string): string {
  let s = stripDecoration(raw).replace(/`/g, '').trim();
  s = s.replace(/^["'“”‘’]+|["'“”‘’]+$/g, '').trim();
  return s.replace(/\s+/g, ' ');
}

export function splitArgs(argText: string): string[] {
  const t = argText.trim();
  if (!t) return [];
  const sep = t.includes('|') ? '|' : t.includes(';') ? ';' : null;
  return (sep ? t.split(sep) : [t]).map((a) => a.trim().replace(/^["'“”‘’]+|["'“”‘’]+$/g, '').trim());
}

/**
 * Parse a reply. `names` maps every accepted spelling (canonical name and aliases, upper case, words joined
 * by "_") to the canonical tool name.
 */
export function parseAction(text: string, names: ReadonlyMap<string, string>): ParsedAction {
  const lines = (typeof text === 'string' ? text : '').replace(/\r/g, '').split('\n');
  const actionIdx: number[] = [];
  lines.forEach((l, i) => {
    if (ACTION_RE.test(l)) actionIdx.push(i);
  });
  let memo: string | null = null;
  const base: ParsedAction = { name: null, argText: '', args: [], body: '', line: '', memo: null, ignored: 0, error: null };
  let cmdRaw: string;
  let after = '';
  let beforeText = '';
  if (actionIdx.length) {
    const L = actionIdx[actionIdx.length - 1]!;
    cmdRaw = ACTION_RE.exec(lines[L]!)![1] ?? '';
    after = lines.slice(L + 1).join('\n');
    beforeText = lines.slice(0, L).join('\n');
    base.ignored = actionIdx.length - 1;
  } else {
    const nonEmpty = lines.map((l) => l.trim()).filter(Boolean);
    if (nonEmpty.length !== 1 || MEMO_RE.test(nonEmpty[0]!)) return { ...base, error: 'No ACTION line found. End your reply with a line like "ACTION: INBOX".' };
    cmdRaw = nonEmpty[0]!;
  }
  for (const l of beforeText.split('\n')) {
    const m = MEMO_RE.exec(l);
    if (m) memo = m[1]!.trim();
  }
  if (memo !== null) memo = memo.replace(/`/g, "'").replace(/\s+/g, ' ').slice(0, MEMO_LIMIT);
  const cmd = cleanCommand(cmdRaw);
  base.line = cmd;
  base.memo = memo;
  if (!cmd) return { ...base, error: 'The ACTION line is empty.' };
  const words = cmd.split(' ');
  let name: string | null = null;
  let used = 0;
  for (let k = Math.min(3, words.length); k >= 1 && !name; k--) {
    const cand = words
      .slice(0, k)
      .join('_')
      .toUpperCase()
      .replace(/-/g, '_')
      .replace(/[^A-Z_]/g, '');
    const hit = names.get(cand);
    if (hit) {
      name = hit;
      used = k;
    }
  }
  if (!name) return { ...base, error: `"${words[0]}" is not one of the actions. Use one of the listed actions, e.g. "ACTION: INBOX".` };
  const argText = words.slice(used).join(' ').replace(/^[:\-–—]\s*/, '').trim();
  let body = cleanBody(after);
  if (!body) {
    const fences = [...beforeText.matchAll(/```[^\n]*\n([\s\S]*?)```/g)];
    if (fences.length) body = cleanBody(fences[fences.length - 1]![1]!);
  }
  return { ...base, name, argText, args: splitArgs(argText), body };
}
