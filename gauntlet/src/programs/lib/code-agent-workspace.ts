/**
 * The in-memory repository the model works on in "Fix the Bug", plus the
 * text the model gets back for LIST / READ / SEARCH / WRITE / PATCH /
 * RUN_TESTS. Nothing here touches the real disk.
 */
import { applyPatch, type PatchBlock } from './code-agent-protocol.ts';
import type { ProjectRun } from './code-agent-sandbox.ts';

export const READ_MAX_LINES = 250;
export const SEARCH_MAX_HITS = 40;
export const MAX_FILE_BYTES = 60_000;
export const MAX_FILES = 60;
const PATH_RE = /^[A-Za-z0-9_][A-Za-z0-9_.-]*(\/[A-Za-z0-9_][A-Za-z0-9_.-]*){0,5}$/;
const EDITABLE_EXT = /\.(js|cjs|json|md|txt)$/;

export type FileState = 'clean' | 'modified' | 'added' | 'readonly';

export type EditOutcome =
  | { ok: true; message: string; before: string | null; after: string; path: string }
  | { ok: false; message: string; tamper: boolean; path: string };

export class Workspace {
  readonly original: Readonly<Record<string, string>>;
  files: Record<string, string>;

  constructor(files: Record<string, string>) {
    this.original = Object.freeze({ ...files });
    this.files = { ...files };
  }

  /** Test files are read-only: the model may not change, add or delete tests. */
  static isReadOnly(path: string): boolean {
    return path === 'tests' || path.startsWith('tests/');
  }

  paths(): string[] {
    return Object.keys(this.files).sort();
  }

  state(path: string): FileState {
    if (Workspace.isReadOnly(path)) return 'readonly';
    if (!(path in this.original)) return 'added';
    return this.original[path] === this.files[path] ? 'clean' : 'modified';
  }

  changed(): string[] {
    return this.paths().filter((p) => this.state(p) === 'modified' || this.state(p) === 'added');
  }

  lineCount(path: string): number {
    const c = this.files[path] ?? '';
    if (!c) return 0;
    return c.endsWith('\n') ? c.split('\n').length - 1 : c.split('\n').length;
  }

  /** Resolve a path the model typed ("./src/a.js", "a.js" when unique) to an existing file. */
  resolve(raw: string): string | null {
    const p = raw.replace(/\\/g, '/').replace(/^\.\//, '').replace(/^\/+/, '');
    if (p in this.files) return p;
    if (`${p}.js` in this.files) return `${p}.js`;
    const bySuffix = this.paths().filter((f) => f.endsWith(`/${p}`));
    return bySuffix.length === 1 ? bySuffix[0]! : null;
  }

  list(): string {
    const paths = this.paths();
    const width = Math.max(...paths.map((p) => p.length), 10) + 2;
    const rows = paths.map((p) => {
      const s = this.state(p);
      const tag = s === 'readonly' ? '  (read-only)' : s === 'modified' ? '  (changed by you)' : s === 'added' ? '  (new)' : '';
      return `${p.padEnd(width)}${String(this.lineCount(p)).padStart(4)} lines${tag}`;
    });
    return `${paths.length} files:\n${rows.join('\n')}`;
  }

  read(raw: string, start?: number, end?: number): { ok: boolean; text: string; path: string | null; from: number; lines: string[] } {
    const path = this.resolve(raw);
    if (!path) return { ok: false, text: `No such file: ${raw}. Use LIST to see the files.`, path: null, from: 0, lines: [] };
    const all = (this.files[path] ?? '').replace(/\n$/, '').split('\n');
    const total = this.files[path] ? all.length : 0;
    let from = Math.max(1, Math.floor(start ?? 1));
    let to = Math.min(total, Math.floor(end ?? total));
    if (from > total && total > 0) return { ok: false, text: `${path} has only ${total} lines.`, path, from: 0, lines: [] };
    if (to < from) to = Math.min(total, from);
    let note = '';
    if (to - from + 1 > READ_MAX_LINES) {
      to = from + READ_MAX_LINES - 1;
      note = `\n… (showing ${READ_MAX_LINES} lines; READ ${path} ${to + 1}-${total} for more)`;
    }
    const lines = total ? all.slice(from - 1, to) : [];
    const width = String(to).length;
    const body = lines.map((l, i) => `${String(from + i).padStart(width)}| ${l}`).join('\n');
    const head = total ? `${path} (lines ${from}-${to} of ${total}${Workspace.isReadOnly(path) ? ', read-only' : ''})` : `${path} (empty file)`;
    return { ok: true, text: `${head}\n${body}${note}`, path, from, lines };
  }

  search(query: string): { text: string; hits: Array<{ path: string; line: number }> } {
    const q = query.toLowerCase();
    const hits: Array<{ path: string; line: number; text: string }> = [];
    let total = 0;
    for (const p of this.paths()) {
      const lines = (this.files[p] ?? '').split('\n');
      lines.forEach((l, i) => {
        if (!l.toLowerCase().includes(q)) return;
        total++;
        if (hits.length < SEARCH_MAX_HITS) hits.push({ path: p, line: i + 1, text: l.length > 160 ? `${l.slice(0, 159)}…` : l });
      });
    }
    if (!total) return { text: `No matches for "${query}".`, hits: [] };
    const more = total > hits.length ? `\n… ${total - hits.length} more matches not shown; search for something more specific.` : '';
    return { text: `${total} match${total === 1 ? '' : 'es'} for "${query}":\n${hits.map((h) => `${h.path}:${h.line}: ${h.text.trim()}`).join('\n')}${more}`, hits };
  }

  private checkWritable(raw: string): { path: string } | { error: string; tamper: boolean } {
    const path = raw.replace(/\\/g, '/').replace(/^\.\//, '').replace(/^\/+/, '');
    if (Workspace.isReadOnly(path)) {
      return { error: `Rejected: ${path} is under tests/, which is read-only. The tests are correct — fix the source code instead. (Attempts to change tests are penalised.)`, tamper: true };
    }
    if (!PATH_RE.test(path) || path.split('/').some((s) => s === '..' || s.startsWith('__'))) return { error: `Rejected: "${raw}" is not a valid project path (use paths like src/name.js).`, tamper: false };
    if (!EDITABLE_EXT.test(path)) return { error: `Rejected: only .js, .cjs, .json, .md and .txt files can be written.`, tamper: false };
    if (!(path in this.files) && Object.keys(this.files).length >= MAX_FILES) return { error: `Rejected: the project may not have more than ${MAX_FILES} files.`, tamper: false };
    return { path };
  }

  write(raw: string, content: string): EditOutcome {
    const c = this.checkWritable(raw);
    if ('error' in c) return { ok: false, message: c.error, tamper: c.tamper, path: raw };
    if (Buffer.byteLength(content, 'utf8') > MAX_FILE_BYTES) return { ok: false, message: `Rejected: files are limited to ${MAX_FILE_BYTES / 1000} KB.`, tamper: false, path: c.path };
    const before = c.path in this.files ? this.files[c.path]! : null;
    this.files[c.path] = content;
    const lines = this.lineCount(c.path);
    const message = before === null ? `Created ${c.path} (${lines} lines).` : before === content ? `${c.path} is unchanged (the content was identical).` : `Wrote ${c.path} (${lines} lines).`;
    return { ok: true, message, before, after: content, path: c.path };
  }

  patch(raw: string, blocks: PatchBlock[]): EditOutcome {
    const c = this.checkWritable(raw);
    if ('error' in c) return { ok: false, message: c.error, tamper: c.tamper, path: raw };
    const path = this.resolve(c.path);
    if (!path) return { ok: false, message: `No such file: ${c.path}. PATCH edits existing files; use WRITE to create one.`, tamper: false, path: c.path };
    if (Workspace.isReadOnly(path)) return { ok: false, message: `Rejected: ${path} is read-only.`, tamper: true, path };
    const before = this.files[path]!;
    const res = applyPatch(before, blocks);
    if (!res.ok) return { ok: false, message: `PATCH ${path} failed: ${res.error} Nothing was changed.`, tamper: false, path };
    if (Buffer.byteLength(res.content, 'utf8') > MAX_FILE_BYTES) return { ok: false, message: `Rejected: files are limited to ${MAX_FILE_BYTES / 1000} KB.`, tamper: false, path };
    this.files[path] = res.content;
    const n = blocks.length;
    return {
      ok: true,
      message: `Patched ${path}: ${n} block${n === 1 ? '' : 's'} applied${res.fuzzy ? ' (whitespace-tolerant match)' : ''}. The file now has ${this.lineCount(path)} lines.`,
      before,
      after: res.content,
      path,
    };
  }
}

/** The RUN_TESTS result as the model sees it: summary, then each failure with its message. */
export function formatTestRun(run: ProjectRun, maxChars = 3500): string {
  if (run.crashed) return `Test run failed: ${run.crashed}. No tests could be evaluated.`;
  const failed = run.tests.filter((t) => !t.ok);
  const parts: string[] = [];
  const loadNote = run.loadErrors.length ? `, ${run.loadErrors.length} test file${run.loadErrors.length === 1 ? '' : 's'} could not load` : '';
  parts.push(`Visible tests: ${run.passed} passed, ${failed.length} failed (${run.total} total${loadNote}).`);
  for (const e of run.loadErrors) parts.push(`\n✗ ${e.file} did not load:\n    ${e.error.split('\n').join('\n    ')}`);
  for (const t of failed) parts.push(`\n✗ ${t.file} › ${t.name}\n    ${(t.error ?? 'failed').split('\n').join('\n    ')}`);
  const passed = run.tests.filter((t) => t.ok);
  if (passed.length) parts.push(`\n✓ passing: ${passed.map((t) => t.name).join(' · ')}`);
  if (run.log.trim()) parts.push(`\nconsole output:\n${run.log.trim()}`);
  let text = parts.join('\n');
  if (text.length > maxChars) text = `${text.slice(0, maxChars - 60)}\n… (output truncated)`;
  return text;
}
