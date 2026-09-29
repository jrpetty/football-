/**
 * File-type detection for the universal output viewer: by magic bytes first
 * (what the file really is), then MIME type, then extension. Pure and
 * unit-tested (test/grading-viewer.test.ts).
 */

export type ViewerKind =
  | 'html'
  | 'svg'
  | 'image'
  | 'pdf'
  | 'audio'
  | 'video'
  | 'json'
  | 'csv'
  | 'markdown'
  | 'code'
  | 'diff'
  | 'text'
  | 'zip'
  | 'replay'
  | 'transcript'
  | 'binary';

/** Size limits (bytes) with a clear message when exceeded. */
export const LIMITS = {
  /** Largest file the viewer downloads to preview at all. */
  fetch: 25_000_000,
  /** Text shown in full up to this size; longer text is cut with a notice. */
  text: 2_000_000,
  /** JSON pretty-printed and diffed up to this size. */
  json: 1_500_000,
  /** CSV rows rendered as a table. */
  csvRows: 2_000,
  /** Markdown rendered up to this size. */
  markdown: 1_000_000,
  /** SVG rendered up to this size. */
  svg: 5_000_000,
  /** Bytes shown in the hex preview. */
  hex: 512,
};

const EXT: Record<string, { kind: ViewerKind; mime: string; lang?: string }> = {
  html: { kind: 'html', mime: 'text/html' },
  htm: { kind: 'html', mime: 'text/html' },
  svg: { kind: 'svg', mime: 'image/svg+xml' },
  png: { kind: 'image', mime: 'image/png' },
  jpg: { kind: 'image', mime: 'image/jpeg' },
  jpeg: { kind: 'image', mime: 'image/jpeg' },
  gif: { kind: 'image', mime: 'image/gif' },
  webp: { kind: 'image', mime: 'image/webp' },
  avif: { kind: 'image', mime: 'image/avif' },
  bmp: { kind: 'image', mime: 'image/bmp' },
  ico: { kind: 'image', mime: 'image/x-icon' },
  pdf: { kind: 'pdf', mime: 'application/pdf' },
  wav: { kind: 'audio', mime: 'audio/wav' },
  mp3: { kind: 'audio', mime: 'audio/mpeg' },
  ogg: { kind: 'audio', mime: 'audio/ogg' },
  oga: { kind: 'audio', mime: 'audio/ogg' },
  m4a: { kind: 'audio', mime: 'audio/mp4' },
  flac: { kind: 'audio', mime: 'audio/flac' },
  weba: { kind: 'audio', mime: 'audio/webm' },
  mp4: { kind: 'video', mime: 'video/mp4' },
  m4v: { kind: 'video', mime: 'video/mp4' },
  webm: { kind: 'video', mime: 'video/webm' },
  ogv: { kind: 'video', mime: 'video/ogg' },
  mov: { kind: 'video', mime: 'video/quicktime' },
  json: { kind: 'json', mime: 'application/json' },
  jsonl: { kind: 'code', mime: 'application/x-ndjson', lang: 'json' },
  csv: { kind: 'csv', mime: 'text/csv' },
  tsv: { kind: 'csv', mime: 'text/tab-separated-values' },
  md: { kind: 'markdown', mime: 'text/markdown' },
  markdown: { kind: 'markdown', mime: 'text/markdown' },
  diff: { kind: 'diff', mime: 'text/x-diff' },
  patch: { kind: 'diff', mime: 'text/x-diff' },
  txt: { kind: 'text', mime: 'text/plain' },
  log: { kind: 'text', mime: 'text/plain' },
  zip: { kind: 'zip', mime: 'application/zip' },
  js: { kind: 'code', mime: 'text/javascript', lang: 'js' },
  mjs: { kind: 'code', mime: 'text/javascript', lang: 'js' },
  cjs: { kind: 'code', mime: 'text/javascript', lang: 'js' },
  jsx: { kind: 'code', mime: 'text/javascript', lang: 'js' },
  ts: { kind: 'code', mime: 'text/typescript', lang: 'ts' },
  tsx: { kind: 'code', mime: 'text/typescript', lang: 'ts' },
  py: { kind: 'code', mime: 'text/x-python', lang: 'py' },
  sh: { kind: 'code', mime: 'text/x-shellscript', lang: 'sh' },
  ps1: { kind: 'code', mime: 'text/plain', lang: 'sh' },
  rb: { kind: 'code', mime: 'text/x-ruby', lang: 'py' },
  css: { kind: 'code', mime: 'text/css', lang: 'js' },
  xml: { kind: 'code', mime: 'application/xml', lang: 'text' },
  yaml: { kind: 'code', mime: 'text/yaml', lang: 'py' },
  yml: { kind: 'code', mime: 'text/yaml', lang: 'py' },
  toml: { kind: 'code', mime: 'text/plain', lang: 'py' },
  sql: { kind: 'code', mime: 'text/x-sql', lang: 'js' },
  java: { kind: 'code', mime: 'text/x-java', lang: 'js' },
  c: { kind: 'code', mime: 'text/x-c', lang: 'js' },
  h: { kind: 'code', mime: 'text/x-c', lang: 'js' },
  cpp: { kind: 'code', mime: 'text/x-c++', lang: 'js' },
  cs: { kind: 'code', mime: 'text/x-csharp', lang: 'js' },
  go: { kind: 'code', mime: 'text/x-go', lang: 'js' },
  rs: { kind: 'code', mime: 'text/x-rust', lang: 'js' },
  php: { kind: 'code', mime: 'text/x-php', lang: 'js' },
  swift: { kind: 'code', mime: 'text/x-swift', lang: 'js' },
  kt: { kind: 'code', mime: 'text/x-kotlin', lang: 'js' },
};

/** Fence languages (```lang) → a file extension the table above knows. */
const LANG_EXT: Record<string, string> = {
  html: 'html',
  htm: 'html',
  svg: 'svg',
  xml: 'xml',
  json: 'json',
  csv: 'csv',
  tsv: 'tsv',
  markdown: 'md',
  md: 'md',
  diff: 'diff',
  patch: 'diff',
  javascript: 'js',
  js: 'js',
  mjs: 'js',
  jsx: 'js',
  typescript: 'ts',
  ts: 'ts',
  tsx: 'ts',
  python: 'py',
  py: 'py',
  bash: 'sh',
  sh: 'sh',
  shell: 'sh',
  powershell: 'ps1',
  css: 'css',
  yaml: 'yaml',
  yml: 'yaml',
  sql: 'sql',
  java: 'java',
  c: 'c',
  cpp: 'cpp',
  csharp: 'cs',
  go: 'go',
  rust: 'rs',
  ruby: 'rb',
  php: 'php',
  text: 'txt',
  txt: 'txt',
  plaintext: 'txt',
};

export function extensionOf(name: string): string {
  const m = /\.([A-Za-z0-9]+)(?:[?#].*)?$/.exec(name.trim());
  return m ? m[1]!.toLowerCase() : '';
}

export function extForLang(lang: string | undefined): string {
  return LANG_EXT[(lang ?? '').toLowerCase()] ?? '';
}

/** Syntax-colouring language for code files (CodeSyntax understands js, py, sh; anything else is plain). */
export function codeLangOf(name: string, fenceLang?: string): string {
  const ext = fenceLang ? extForLang(fenceLang) || extensionOf(name) : extensionOf(name);
  return EXT[ext]?.lang ?? (ext === 'json' ? 'json' : 'text');
}

function startsWith(b: Uint8Array, sig: number[], at = 0): boolean {
  if (b.length < at + sig.length) return false;
  for (let i = 0; i < sig.length; i++) if (b[at + i] !== sig[i]) return false;
  return true;
}

function ascii(b: Uint8Array, from: number, to: number): string {
  let s = '';
  for (let i = from; i < Math.min(to, b.length); i++) s += String.fromCharCode(b[i]!);
  return s;
}

/** What the first bytes say a file is (null when they don't say). */
export function sniffBytes(b: Uint8Array): { kind: ViewerKind; mime: string } | null {
  if (startsWith(b, [0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a])) return { kind: 'image', mime: 'image/png' };
  if (startsWith(b, [0xff, 0xd8, 0xff])) return { kind: 'image', mime: 'image/jpeg' };
  if (ascii(b, 0, 6) === 'GIF87a' || ascii(b, 0, 6) === 'GIF89a') return { kind: 'image', mime: 'image/gif' };
  if (ascii(b, 0, 4) === 'RIFF' && ascii(b, 8, 12) === 'WEBP') return { kind: 'image', mime: 'image/webp' };
  if (ascii(b, 0, 4) === 'RIFF' && ascii(b, 8, 12) === 'WAVE') return { kind: 'audio', mime: 'audio/wav' };
  if (ascii(b, 4, 8) === 'ftyp') {
    const brand = ascii(b, 8, 12);
    if (brand === 'avif' || brand === 'avis') return { kind: 'image', mime: 'image/avif' };
    if (brand === 'M4A ') return { kind: 'audio', mime: 'audio/mp4' };
    if (brand === 'qt  ') return { kind: 'video', mime: 'video/quicktime' };
    return { kind: 'video', mime: 'video/mp4' };
  }
  if (ascii(b, 0, 2) === 'BM' && b.length > 14) return { kind: 'image', mime: 'image/bmp' };
  if (startsWith(b, [0x00, 0x00, 0x01, 0x00]) && b.length > 6) return { kind: 'image', mime: 'image/x-icon' };
  if (ascii(b, 0, 5) === '%PDF-') return { kind: 'pdf', mime: 'application/pdf' };
  if (ascii(b, 0, 4) === 'OggS') return { kind: 'audio', mime: 'audio/ogg' };
  if (ascii(b, 0, 3) === 'ID3' || (b[0] === 0xff && ((b[1] ?? 0) & 0xe0) === 0xe0)) return { kind: 'audio', mime: 'audio/mpeg' };
  if (ascii(b, 0, 4) === 'fLaC') return { kind: 'audio', mime: 'audio/flac' };
  if (startsWith(b, [0x1a, 0x45, 0xdf, 0xa3])) return { kind: 'video', mime: 'video/webm' };
  if (startsWith(b, [0x50, 0x4b, 0x03, 0x04]) || startsWith(b, [0x50, 0x4b, 0x05, 0x06])) return { kind: 'zip', mime: 'application/zip' };
  // Text formats: look at the first non-space characters.
  const head = ascii(b, 0, 512).replace(/^﻿/, '').replace(/^[\sï»¿]+/, '');
  if (/^<\?xml[\s\S]*<svg[\s>]/i.test(head) || /^<svg[\s>]/i.test(head)) return { kind: 'svg', mime: 'image/svg+xml' };
  if (/^<!doctype html|^<html[\s>]/i.test(head)) return { kind: 'html', mime: 'text/html' };
  return null;
}

/** True when the bytes look like text (no NULs, few control characters). */
export function looksLikeText(b: Uint8Array): boolean {
  const n = Math.min(b.length, 4096);
  let bad = 0;
  for (let i = 0; i < n; i++) {
    const c = b[i]!;
    if (c === 0) return false;
    if (c < 9 || (c > 13 && c < 32)) bad++;
  }
  return bad <= n * 0.02;
}

/** Guess a text format from its content (used for pasted replies with no name). */
export function sniffText(text: string): ViewerKind | null {
  const t = text.trimStart();
  if (/^<\?xml[\s\S]{0,200}<svg[\s>]/i.test(t) || /^<svg[\s>]/i.test(t)) return 'svg';
  if (/^<!doctype html|^<html[\s>]/i.test(t)) return 'html';
  if (/^[[{]/.test(t)) {
    try {
      JSON.parse(t);
      return 'json';
    } catch {
      /* not JSON */
    }
  }
  if (/^(diff --git |--- \S|Index: )/m.test(t) && /^@@ /m.test(t)) return 'diff';
  return null;
}

export interface Detected {
  kind: ViewerKind;
  mime: string;
  /** How we know: 'magic' bytes, the declared 'mime' type, the 'extension', or the 'content'. */
  by: 'magic' | 'mime' | 'extension' | 'content' | 'hint';
}

/**
 * Decide how to show a file. Magic bytes win (a PNG named .txt is still a PNG), then an explicit hint
 * (replays, transcripts), then MIME, then extension, then content sniffing for text.
 */
export function detectKind(file: { name: string; mime?: string; hint?: ViewerKind; bytes?: Uint8Array; text?: string }): Detected {
  if (file.hint && (file.hint === 'replay' || file.hint === 'transcript')) return { kind: file.hint, mime: 'application/json', by: 'hint' };
  if (file.bytes && file.bytes.length) {
    const s = sniffBytes(file.bytes);
    if (s) return { ...s, by: 'magic' };
  }
  if (file.hint) return { kind: file.hint, mime: file.mime ?? EXT[extensionOf(file.name)]?.mime ?? 'application/octet-stream', by: 'hint' };
  const mime = (file.mime ?? '').split(';')[0]!.trim().toLowerCase();
  if (mime) {
    const byMime = kindForMime(mime);
    if (byMime) return { kind: byMime, mime, by: 'mime' };
  }
  const ext = EXT[extensionOf(file.name)];
  if (ext) return { kind: ext.kind, mime: ext.mime, by: 'extension' };
  const text = file.text ?? (file.bytes && looksLikeText(file.bytes) ? new TextDecoder().decode(file.bytes.slice(0, 200_000)) : undefined);
  if (text !== undefined) {
    const k = sniffText(text);
    return { kind: k ?? 'text', mime: k === 'json' ? 'application/json' : k === 'svg' ? 'image/svg+xml' : k === 'html' ? 'text/html' : 'text/plain', by: 'content' };
  }
  return { kind: 'binary', mime: mime || 'application/octet-stream', by: mime ? 'mime' : 'extension' };
}

export function kindForMime(mime: string): ViewerKind | null {
  if (mime === 'image/svg+xml') return 'svg';
  if (mime.startsWith('image/')) return 'image';
  if (mime.startsWith('audio/')) return 'audio';
  if (mime.startsWith('video/')) return 'video';
  if (mime === 'application/pdf') return 'pdf';
  if (mime === 'text/html' || mime === 'application/xhtml+xml') return 'html';
  if (mime === 'application/json' || mime.endsWith('+json')) return 'json';
  if (mime === 'text/csv' || mime === 'text/tab-separated-values') return 'csv';
  if (mime === 'text/markdown') return 'markdown';
  if (mime === 'text/x-diff' || mime === 'text/x-patch') return 'diff';
  if (mime === 'application/zip' || mime === 'application/x-zip-compressed') return 'zip';
  if (mime === 'text/javascript' || mime === 'application/javascript' || mime === 'text/x-python' || mime === 'text/css') return 'code';
  if (mime.startsWith('text/')) return 'text';
  return null;
}

/** MIME type for a file name (for data URLs and downloads). */
export function mimeForName(name: string): string {
  return EXT[extensionOf(name)]?.mime ?? 'application/octet-stream';
}

/** "12.4 kB" */
export function fmtSize(n: number | undefined | null): string {
  if (typeof n !== 'number' || !Number.isFinite(n)) return '—';
  if (n < 1000) return `${n} B`;
  if (n < 1_000_000) return `${(n / 1000).toFixed(n < 10_000 ? 1 : 0)} kB`;
  return `${(n / 1_000_000).toFixed(1)} MB`;
}

/** Classic hex dump rows: offset, 16 hex bytes, printable ASCII. */
export function hexRows(b: Uint8Array, max = LIMITS.hex): Array<{ offset: string; hex: string; ascii: string }> {
  const rows: Array<{ offset: string; hex: string; ascii: string }> = [];
  const n = Math.min(b.length, max);
  for (let i = 0; i < n; i += 16) {
    const chunk = b.slice(i, Math.min(i + 16, n));
    rows.push({
      offset: i.toString(16).padStart(8, '0'),
      hex: [...chunk].map((x) => x.toString(16).padStart(2, '0')).join(' '),
      ascii: [...chunk].map((x) => (x >= 32 && x < 127 ? String.fromCharCode(x) : '·')).join(''),
    });
  }
  return rows;
}

/** Decode a data: URL (base64 or percent-encoded) to bytes. */
export function dataUrlBytes(url: string): Uint8Array | null {
  const m = /^data:([^,]*?),(.*)$/s.exec(url);
  if (!m) return null;
  if (/;base64$/i.test(m[1]!)) {
    const bin = atob(m[2]!.replace(/\s+/g, ''));
    const out = new Uint8Array(bin.length);
    for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
    return out;
  }
  return new TextEncoder().encode(decodeURIComponent(m[2]!));
}

export function dataUrlMime(url: string): string | undefined {
  return /^data:([^;,]+)/.exec(url)?.[1]?.toLowerCase();
}
