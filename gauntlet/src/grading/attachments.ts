/**
 * Files inside a plain-text reply.
 *
 * Every model reply is plain text (see METHODOLOGY → transport). Files travel
 * inside it the way models write them anyway:
 *  - text files (HTML, SVG, JSON, CSV, Markdown, code, diffs…) as a fenced code
 *    block whose language is the file type, preceded by a "File: name" line;
 *  - binary files (images, audio, video, PDF, ZIP…) as a data URL in a Markdown
 *    link: `![name](data:image/png;base64,…)` for images, `[name](data:…)` otherwise.
 * The artifact scorers already read fenced ```html / ```svg blocks, so an uploaded
 * game in the Manual Inbox is graded exactly like a pasted one. The output viewer
 * finds these blocks and renders each file. Pure: used by the server and the UI.
 */

export interface ReplyAttachment {
  name: string;
  /** Fence language (text files) or MIME type (data URLs). */
  lang?: string;
  mime?: string;
  /** Text content (fenced blocks). */
  text?: string;
  /** data: URL (binary files). */
  dataUrl?: string;
  /** Approximate decoded size in bytes. */
  bytes: number;
}

/** File extension → fence language the scorers and the viewer understand. */
const LANG_BY_EXT: Record<string, string> = {
  html: 'html',
  htm: 'html',
  svg: 'svg',
  json: 'json',
  csv: 'csv',
  tsv: 'tsv',
  md: 'markdown',
  markdown: 'markdown',
  txt: 'text',
  log: 'text',
  js: 'javascript',
  mjs: 'javascript',
  cjs: 'javascript',
  jsx: 'jsx',
  ts: 'typescript',
  tsx: 'tsx',
  py: 'python',
  css: 'css',
  xml: 'xml',
  yaml: 'yaml',
  yml: 'yaml',
  sh: 'bash',
  ps1: 'powershell',
  bat: 'bat',
  sql: 'sql',
  java: 'java',
  c: 'c',
  h: 'c',
  cpp: 'cpp',
  cs: 'csharp',
  go: 'go',
  rs: 'rust',
  rb: 'ruby',
  php: 'php',
  swift: 'swift',
  kt: 'kotlin',
  diff: 'diff',
  patch: 'diff',
  toml: 'toml',
  ini: 'ini',
};

export function extOf(name: string): string {
  const m = /\.([A-Za-z0-9]+)$/.exec(name.trim());
  return m ? m[1]!.toLowerCase() : '';
}

/** Fence language for a file name, or null when the file is binary / unknown. */
export function langForFile(name: string): string | null {
  return LANG_BY_EXT[extOf(name)] ?? null;
}

/** A fence long enough that the content cannot close it. */
function fenceFor(text: string): string {
  const longest = Math.max(2, ...[...text.matchAll(/`{3,}/g)].map((m) => m[0].length));
  return '`'.repeat(longest + 1);
}

/** Reply text for an uploaded text file. */
export function textFileToReply(name: string, text: string): string {
  const lang = langForFile(name) ?? 'text';
  const fence = fenceFor(text);
  return `File: ${name}\n${fence}${lang}\n${text.replace(/\n$/, '')}\n${fence}`;
}

/** Reply text for an uploaded binary file (base64 body). */
export function binaryFileToReply(name: string, mime: string, base64: string): string {
  const safe = name.replace(/[[\]()]/g, '_');
  const url = `data:${mime || 'application/octet-stream'};base64,${base64}`;
  return mime.startsWith('image/') ? `![${safe}](${url})` : `[${safe}](${url})`;
}

function base64Bytes(b64: string): number {
  const clean = b64.replace(/\s+/g, '');
  const pad = clean.endsWith('==') ? 2 : clean.endsWith('=') ? 1 : 0;
  return Math.max(0, Math.floor((clean.length * 3) / 4) - pad);
}

/** Every file inside a reply: fenced blocks (with or without a "File:" line) and data-URL links. */
export function extractAttachments(reply: string): ReplyAttachment[] {
  const out: ReplyAttachment[] = [];
  // Fenced blocks: ```lang … ``` (3+ backticks; the closing fence must match the opening length).
  const fence = /(?:^|\n)(?:File:\s*([^\n]+)\n)?(`{3,})([\w+#.-]*)[^\n]*\n([\s\S]*?)\n\2(?=\n|$)/g;
  let n = 0;
  for (const m of reply.matchAll(fence)) {
    n++;
    const lang = (m[3] || '').toLowerCase();
    const text = m[4] ?? '';
    const name = m[1]?.trim() || `block-${n}.${extForLang(lang)}`;
    out.push({ name, lang: lang || langForFile(name) || 'text', text, bytes: new TextEncoder().encode(text).length });
  }
  // Data URLs in Markdown links / images.
  for (const m of reply.matchAll(/!?\[([^\]\n]{0,200})\]\((data:([\w.+-]+\/[\w.+-]+)(?:;[\w=.+-]+)*;base64,([A-Za-z0-9+/=\s]+))\)/g)) {
    out.push({ name: m[1] || `file.${m[3]!.split('/')[1]}`, mime: m[3], dataUrl: m[2]!.replace(/\s+/g, ''), bytes: base64Bytes(m[4]!) });
  }
  return out;
}

function extForLang(lang: string): string {
  const back: Record<string, string> = { javascript: 'js', typescript: 'ts', python: 'py', markdown: 'md', text: 'txt', bash: 'sh', html: 'html', svg: 'svg', json: 'json', csv: 'csv', diff: 'diff' };
  return back[lang] ?? (lang || 'txt');
}

/** Largest file the Manual Inbox accepts (the server takes 5 MB request bodies; base64 adds a third). */
export const MAX_UPLOAD_BYTES = 3_500_000;
