/**
 * "Fix the Bug" tool protocol: parsing one action out of a free-form model
 * reply, and applying SEARCH/REPLACE patches.
 *
 * Lenient about decoration (markdown bold, backticks, "Action:" casing,
 * common aliases such as `cat` or `run tests`), strict about content: an edit
 * is applied only when it is unambiguous. Never throws on model output.
 */

export type Action =
  | { kind: 'LIST' }
  | { kind: 'READ'; path: string; start?: number; end?: number }
  | { kind: 'SEARCH'; text: string }
  | { kind: 'RUN_TESTS' }
  | { kind: 'WRITE'; path: string; content: string }
  | { kind: 'PATCH'; path: string; blocks: PatchBlock[] }
  | { kind: 'SUBMIT' };

export interface PatchBlock {
  search: string;
  replace: string;
}

export interface ParsedReply {
  action: Action | null;
  /** Why no action could be read (shown to the model). */
  error: string | null;
  /** The action line as written (cleaned), for logs and the replay. */
  line: string;
  /** Earlier ACTION lines that were ignored (the last one counts). */
  ignoredActions: number;
}

const ALIASES: Record<string, Action['kind']> = {
  LIST: 'LIST',
  LS: 'LIST',
  TREE: 'LIST',
  FILES: 'LIST',
  READ: 'READ',
  CAT: 'READ',
  OPEN: 'READ',
  VIEW: 'READ',
  SHOW: 'READ',
  SEARCH: 'SEARCH',
  GREP: 'SEARCH',
  FIND: 'SEARCH',
  RUN_TESTS: 'RUN_TESTS',
  RUNTESTS: 'RUN_TESTS',
  'RUN-TESTS': 'RUN_TESTS',
  TEST: 'RUN_TESTS',
  TESTS: 'RUN_TESTS',
  WRITE: 'WRITE',
  CREATE: 'WRITE',
  PATCH: 'PATCH',
  EDIT: 'PATCH',
  REPLACE: 'PATCH',
  SUBMIT: 'SUBMIT',
  DONE: 'SUBMIT',
  FINISH: 'SUBMIT',
};

const ACTION_LINE = /^\s*(?:[*_>#-]+\s*)*\**\s*action\s*\**\s*[:：]\s*\**\s*(.*)$/i;
const BARE_COMMAND = /^\s*[`*]*\s*(LIST|READ|SEARCH|RUN_TESTS|WRITE|PATCH|SUBMIT)\b(.*)$/;
const FENCE = /^\s*(`{3,}|~{3,})(.*)$/;
const SEARCH_MARK = /^\s*<{5,9}\s*SEARCH\s*$/i;
const DIVIDER = /^\s*={5,9}\s*$/;
const REPLACE_MARK = /^\s*>{5,9}\s*REPLACE\s*$/i;

/** Strip markdown decoration from an action line. */
function cleanLine(raw: string): string {
  return raw
    .replace(/\*\*|__/g, '')
    .replace(/^`+|`+$/g, '')
    .replace(/`/g, '')
    .trim();
}

/** Remove quotes / backticks / trailing punctuation around a path. */
export function cleanPath(raw: string): string {
  return raw
    .trim()
    .replace(/^["'`]+|["'`:,;]+$/g, '')
    .replace(/\\/g, '/')
    .replace(/^\.\//, '')
    .replace(/^\/+/, '');
}

interface LineInfo {
  text: string;
  inFence: boolean;
}

/** Mark which lines sit inside fenced code blocks or SEARCH/REPLACE blocks (their content is never an action). */
function scanLines(text: string): LineInfo[] {
  const lines = text.split('\n');
  const out: LineInfo[] = [];
  let fence: string | null = null;
  let inBlock = false;
  for (const line of lines) {
    if (inBlock) {
      if (REPLACE_MARK.test(line)) inBlock = false;
      out.push({ text: line, inFence: true });
      continue;
    }
    if (fence === null && SEARCH_MARK.test(line)) {
      inBlock = true;
      out.push({ text: line, inFence: true });
      continue;
    }
    const m = FENCE.exec(line);
    if (fence === null && m) {
      fence = m[1]!;
      out.push({ text: line, inFence: true });
      continue;
    }
    if (fence !== null) {
      const close = FENCE.exec(line);
      if (close && close[1]![0] === fence[0] && close[1]!.length >= fence.length && !close[2]!.trim()) fence = null;
      out.push({ text: line, inFence: true });
      continue;
    }
    out.push({ text: line, inFence: false });
  }
  return out;
}

/** Locate the action line: the last `ACTION:` line outside code fences, then inside, then the first bare upper-case command. */
function findActionLine(lines: LineInfo[]): { index: number; command: string; ignored: number } | null {
  const candidates = (inFence: boolean) =>
    lines
      .map((l, i) => ({ i, m: !l.inFence || inFence ? ACTION_LINE.exec(l.text) : null }))
      .filter((x) => x.m && cleanLine(x.m[1]!).length > 0);
  let found = candidates(false);
  if (!found.length) found = candidates(true);
  if (found.length) {
    const last = found[found.length - 1]!;
    return { index: last.i, command: cleanLine(last.m![1]!), ignored: found.length - 1 };
  }
  for (let i = 0; i < lines.length; i++) {
    if (lines[i]!.inFence) continue;
    const m = BARE_COMMAND.exec(lines[i]!.text);
    if (m) return { index: i, command: cleanLine(`${m[1]}${m[2]}`), ignored: 0 };
  }
  return null;
}

/** "src/a.js 10-40", "src/a.js:10-40", "src/a.js lines 10 to 40", "src/a.js 10 40" */
function parseReadArgs(rest: string): { path: string; start?: number; end?: number } {
  const s = rest.trim();
  const m =
    /^(\S+?)(?::|\s+(?:lines?\s+|L)?)(\d+)\s*(?:-|–|\.\.|to|\s)\s*(\d+)\s*$/i.exec(s) ??
    /^(\S+?)(?::|\s+(?:lines?\s+|L)?)(\d+)\s*$/i.exec(s);
  if (m) {
    const start = Number(m[2]);
    const end = m[3] !== undefined ? Number(m[3]) : undefined;
    return { path: cleanPath(m[1]!), start, end };
  }
  return { path: cleanPath(s.split(/\s+/)[0] ?? '') };
}

/** The first fenced block after line `from`. Unterminated fences run to the end of the reply. */
function fencedBlockAfter(lines: string[], from: number): { content: string; closed: boolean } | null {
  for (let i = from; i < lines.length; i++) {
    const open = FENCE.exec(lines[i]!);
    if (!open) continue;
    const marker = open[1]!;
    const body: string[] = [];
    for (let j = i + 1; j < lines.length; j++) {
      const close = FENCE.exec(lines[j]!);
      if (close && close[1]![0] === marker[0] && close[1]!.length >= marker.length && !close[2]!.trim()) return { content: body.join('\n'), closed: true };
      body.push(lines[j]!);
    }
    return { content: body.join('\n'), closed: false };
  }
  return null;
}

/** SEARCH/REPLACE blocks after line `from`. */
function patchBlocksAfter(lines: string[], from: number): { blocks: PatchBlock[]; error: string | null } {
  const blocks: PatchBlock[] = [];
  let state: 'out' | 'search' | 'replace' = 'out';
  let search: string[] = [];
  let replace: string[] = [];
  for (let i = from; i < lines.length; i++) {
    const line = lines[i]!;
    if (state === 'out') {
      if (SEARCH_MARK.test(line)) {
        state = 'search';
        search = [];
      }
    } else if (state === 'search') {
      if (DIVIDER.test(line)) {
        state = 'replace';
        replace = [];
      } else if (SEARCH_MARK.test(line) || REPLACE_MARK.test(line)) return { blocks, error: `SEARCH/REPLACE block ${blocks.length + 1} is malformed: expected a line of ======= after the SEARCH lines.` };
      else search.push(line);
    } else if (REPLACE_MARK.test(line)) {
      blocks.push({ search: search.join('\n'), replace: replace.join('\n') });
      state = 'out';
    } else if (SEARCH_MARK.test(line)) {
      return { blocks, error: `SEARCH/REPLACE block ${blocks.length + 1} is missing its >>>>>>> REPLACE line.` };
    } else replace.push(line);
  }
  if (state !== 'out') return { blocks, error: `SEARCH/REPLACE block ${blocks.length + 1} is not closed with >>>>>>> REPLACE.` };
  return { blocks, error: null };
}

/** Parse one model reply into an action. */
export function parseReply(rawText: string): ParsedReply {
  const text = String(rawText ?? '').replace(/\r\n?/g, '\n');
  if (!text.trim()) return { action: null, error: 'The reply was empty.', line: '', ignoredActions: 0 };
  const scanned = scanLines(text);
  const found = findActionLine(scanned);
  if (!found) {
    return {
      action: null,
      error: 'No action found. End every reply with one line of the form "ACTION: <command>", e.g. "ACTION: RUN_TESTS" or "ACTION: READ src/index.js".',
      line: '',
      ignoredActions: 0,
    };
  }
  const line = found.command;
  const [word = '', ...restParts] = line.split(/\s+/);
  const restRaw = line.slice(word.length).trim();
  let key = word.toUpperCase().replace(/[.:;,!]+$/, '');
  // "RUN TESTS", "RUN_TESTS()"
  if (key === 'RUN' && /^tests?\b/i.test(restParts[0] ?? '')) key = 'RUN_TESTS';
  key = key.replace(/\(\)$/, '');
  const kind = ALIASES[key];
  const base = { line, ignoredActions: found.ignored };
  if (!kind) return { ...base, action: null, error: `Unknown action "${word}". Use LIST, READ, SEARCH, RUN_TESTS, WRITE, PATCH or SUBMIT.` };

  const allLines = text.split('\n');
  switch (kind) {
    case 'LIST':
      return { ...base, action: { kind }, error: null };
    case 'RUN_TESTS':
      return { ...base, action: { kind }, error: null };
    case 'SUBMIT':
      return { ...base, action: { kind }, error: null };
    case 'SEARCH': {
      const q = restRaw.replace(/^["'`]+|["'`]+$/g, '');
      if (!q) return { ...base, action: null, error: 'SEARCH needs some text to look for, e.g. "ACTION: SEARCH toCents".' };
      return { ...base, action: { kind, text: q.slice(0, 200) }, error: null };
    }
    case 'READ': {
      const args = parseReadArgs(restRaw);
      if (!args.path) return { ...base, action: null, error: 'READ needs a file path, e.g. "ACTION: READ src/index.js".' };
      return { ...base, action: { kind, ...args }, error: null };
    }
    case 'WRITE': {
      const path = cleanPath(restRaw.split(/\s+/)[0] ?? '');
      if (!path) return { ...base, action: null, error: 'WRITE needs a file path, e.g. "ACTION: WRITE src/util.js", followed by the complete file in a fenced code block.' };
      const block = fencedBlockAfter(allLines, found.index + 1);
      if (!block) return { ...base, action: null, error: `WRITE ${path}: put the COMPLETE new file content in one fenced code block (\`\`\`) right after the ACTION line.` };
      const content = block.content.endsWith('\n') || block.content === '' ? block.content : `${block.content}\n`;
      return { ...base, action: { kind, path, content }, error: null };
    }
    case 'PATCH': {
      const path = cleanPath(restRaw.split(/\s+/)[0] ?? '');
      if (!path) return { ...base, action: null, error: 'PATCH needs a file path, e.g. "ACTION: PATCH src/util.js", followed by SEARCH/REPLACE blocks.' };
      const { blocks, error } = patchBlocksAfter(allLines, found.index + 1);
      if (error) return { ...base, action: null, error: `PATCH ${path}: ${error} Nothing was changed.` };
      if (!blocks.length)
        return {
          ...base,
          action: null,
          error: `PATCH ${path}: no SEARCH/REPLACE blocks found after the ACTION line. Use:\n<<<<<<< SEARCH\n(exact current lines)\n=======\n(new lines)\n>>>>>>> REPLACE\nNothing was changed.`,
        };
      return { ...base, action: { kind, path, blocks }, error: null };
    }
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// Applying SEARCH/REPLACE blocks
// ─────────────────────────────────────────────────────────────────────────────

const LINE_NO_PREFIX = /^\s*\d+\s?\| ?/;

/** Drop "  12| " prefixes when a model copied lines straight from READ output. */
function stripLineNumbers(block: PatchBlock): PatchBlock {
  const s = block.search.split('\n');
  if (!s.length || !s.every((l) => LINE_NO_PREFIX.test(l))) return block;
  const r = block.replace.split('\n');
  const rStrip = r.every((l) => l === '' || LINE_NO_PREFIX.test(l));
  return { search: s.map((l) => l.replace(LINE_NO_PREFIX, '')).join('\n'), replace: rStrip ? r.map((l) => l.replace(LINE_NO_PREFIX, '')).join('\n') : block.replace };
}

function countOccurrences(hay: string, needle: string): { count: number; index: number } {
  let count = 0;
  let index = -1;
  let from = 0;
  while (true) {
    const i = hay.indexOf(needle, from);
    if (i < 0) break;
    if (count === 0) index = i;
    count++;
    from = i + 1;
  }
  return { count, index };
}

/** Line windows of `content` matching `search` when each line is compared by `norm`. */
function lineMatches(contentLines: string[], searchLines: string[], norm: (s: string) => string): number[] {
  const target = searchLines.map(norm);
  const out: number[] = [];
  for (let i = 0; i + target.length <= contentLines.length; i++) {
    let ok = true;
    for (let k = 0; k < target.length; k++) {
      if (norm(contentLines[i + k]!) !== target[k]) {
        ok = false;
        break;
      }
    }
    if (ok) out.push(i);
  }
  return out;
}

export type PatchResult = { ok: true; content: string; fuzzy: number } | { ok: false; error: string };

/** Apply blocks in order; all must match exactly once, otherwise nothing changes. */
export function applyPatch(content: string, rawBlocks: PatchBlock[]): PatchResult {
  let out = content;
  let fuzzy = 0;
  for (let b = 0; b < rawBlocks.length; b++) {
    const block = stripLineNumbers(rawBlocks[b]!);
    const label = rawBlocks.length > 1 ? `SEARCH block ${b + 1}` : 'The SEARCH text';
    if (!block.search.trim()) return { ok: false, error: `${label} is empty. Copy the exact lines you want to replace into it.` };
    const exact = countOccurrences(out, block.search);
    if (exact.count === 1) {
      out = out.slice(0, exact.index) + block.replace + out.slice(exact.index + block.search.length);
      continue;
    }
    if (exact.count > 1) return { ok: false, error: `${label} matches ${exact.count} places. Include more surrounding lines so it matches exactly one.` };
    // Tolerate whitespace differences at the ends of each line.
    const contentLines = out.split('\n');
    const searchLines = block.search.replace(/\n+$/, '').split('\n');
    while (searchLines.length > 1 && !searchLines[0]!.trim()) searchLines.shift();
    let hits = lineMatches(contentLines, searchLines, (s) => s.trimEnd());
    if (!hits.length) hits = lineMatches(contentLines, searchLines, (s) => s.trim());
    if (hits.length === 1) {
      const at = hits[0]!;
      const replaceLines = block.replace.replace(/\n+$/, '').split('\n');
      const replacement = block.replace === '' ? [] : replaceLines;
      contentLines.splice(at, searchLines.length, ...replacement);
      out = contentLines.join('\n');
      fuzzy++;
      continue;
    }
    if (hits.length > 1) return { ok: false, error: `${label} matches ${hits.length} places. Include more surrounding lines so it matches exactly one.` };
    const first = searchLines.find((l) => l.trim())?.trim() ?? '';
    const near = first ? contentLines.findIndex((l) => l.trim() === first) : -1;
    return {
      ok: false,
      error:
        `${label} was not found in the file.` +
        (near >= 0 ? ` Its first line appears at line ${near + 1}, but the lines after it differ.` : '') +
        ' Copy the current lines exactly (READ the file again if unsure).',
    };
  }
  return { ok: true, content: out, fuzzy };
}
