/**
 * Safe rendering of untrusted model output: an SVG sanitiser and a small
 * Markdown renderer. Both are string-based and pure so they are unit-tested
 * in Node (test/grading-viewer.test.ts). Defence in depth: the viewer also
 * shows SVG through <img> (browsers never run scripts or load external files
 * in an SVG image) and Markdown output is built only from escaped text.
 */

export function escapeHtml(s: string): string {
  return s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;').replace(/'/g, '&#39;');
}

// ───────────────────────────── SVG ─────────────────────────────

const DANGEROUS_ELEMENTS = ['script', 'foreignObject', 'iframe', 'embed', 'object', 'audio', 'video', 'handler', 'listener'];

/** A reference that stays inside the file (or embeds an image), never fetched from elsewhere. */
function safeRef(v: string): boolean {
  const t = v.trim().replace(/^['"]|['"]$/g, '');
  return t.startsWith('#') || /^data:image\/(png|jpe?g|gif|webp);base64,/i.test(t);
}

/**
 * Remove scripts, event handlers, external references and entity tricks from an SVG.
 * Returns the cleaned SVG and a list of what was removed (shown to the grader).
 */
export function sanitizeSvg(svg: string): { svg: string; removed: string[] } {
  const removed = new Set<string>();
  let s = svg;
  // XML prologue tricks: DOCTYPE with internal entities (billion laughs, external entities), stylesheets.
  s = s.replace(/<!DOCTYPE[^[>]*(\[[\s\S]*?\])?\s*>/gi, () => (removed.add('DOCTYPE / entity declarations'), ''));
  s = s.replace(/<\?xml-stylesheet[\s\S]*?\?>/gi, () => (removed.add('external stylesheet'), ''));
  s = s.replace(/<!\[CDATA\[([\s\S]*?)\]\]>/g, (_m, inner: string) => escapeHtml(inner));
  for (const el of DANGEROUS_ELEMENTS) {
    const paired = new RegExp(`<${el}\\b[\\s\\S]*?<\\/${el}\\s*>`, 'gi');
    const single = new RegExp(`<${el}\\b[^>]*\\/?>`, 'gi');
    s = s.replace(paired, () => (removed.add(`<${el}>`), '')).replace(single, () => (removed.add(`<${el}>`), ''));
  }
  // Animations that could rewrite a link target.
  s = s.replace(/<(set|animate)\b[^>]*attributeName\s*=\s*["']?(?:xlink:)?href["']?[^>]*\/?>/gi, () => (removed.add('animated link'), ''));
  // Event handlers: onload=, onclick=… (quoted or not).
  s = s.replace(/\s(on[a-z]+)\s*=\s*("[^"]*"|'[^']*'|[^\s>]+)/gi, (_m, name: string) => (removed.add(`${name.toLowerCase()} handler`), ''));
  // href / xlink:href / src to anything but an internal #ref or an embedded image.
  s = s.replace(/\s((?:xlink:)?href|src)\s*=\s*("[^"]*"|'[^']*'|[^\s>]+)/gi, (m, name: string, value: string) => {
    if (safeRef(value)) return m;
    removed.add(`external ${name.toLowerCase()}`);
    return '';
  });
  // CSS: @import and url(...) pointing outside the file.
  s = s.replace(/@import[^;]*;?/gi, () => (removed.add('@import'), ''));
  s = s.replace(/url\(\s*(['"]?)([^)'"]*)\1\s*\)/gi, (m, _q: string, target: string) => {
    if (safeRef(target)) return m;
    removed.add('external url()');
    return 'none';
  });
  s = s.replace(/javascript:/gi, () => (removed.add('javascript: URL'), ''));
  return { svg: s, removed: [...removed] };
}

/** data: URL for showing a (sanitised) SVG through <img>. */
export function svgDataUrl(svg: string): string {
  let s = svg;
  if (!/xmlns\s*=/.test(s)) s = s.replace(/<svg\b/i, '<svg xmlns="http://www.w3.org/2000/svg"');
  return `data:image/svg+xml;charset=utf-8,${encodeURIComponent(s)}`;
}

// ───────────────────────────── Markdown ─────────────────────────────

function safeHref(url: string): string | null {
  const u = url.trim();
  if (/^(https?:|mailto:)/i.test(u) || u.startsWith('#')) return u;
  return null;
}

function inline(text: string): string {
  // Code spans first, so their contents are never formatted.
  const codes: string[] = [];
  let s = text.replace(/`([^`\n]+)`/g, (_m, c: string) => {
    codes.push(`<code>${escapeHtml(c)}</code>`);
    return `\u0000${codes.length - 1}\u0000`;
  });
  s = escapeHtml(s);
  // Images: only embedded data images render; anything else would fetch from the network, so it becomes a label.
  s = s.replace(/!\[([^\]]*)\]\(([^)\s]+)\)/g, (_m, alt: string, url: string) => {
    const raw = url.replace(/&amp;/g, '&');
    if (/^data:image\/(png|jpe?g|gif|webp);base64,[A-Za-z0-9+/=]+$/i.test(raw)) return `<img alt="${alt}" src="${raw}">`;
    return `<span class="md-img-blocked" title="External images are not loaded">[image: ${alt || 'untitled'}]</span>`;
  });
  s = s.replace(/\[([^\]]+)\]\(([^)\s]+)\)/g, (_m, label: string, url: string) => {
    const href = safeHref(url.replace(/&amp;/g, '&'));
    return href ? `<a href="${escapeHtml(href)}" target="_blank" rel="noopener noreferrer nofollow">${label}</a>` : label;
  });
  s = s.replace(/\*\*([^*]+)\*\*|__([^_]+)__/g, (_m, a?: string, b?: string) => `<strong>${a ?? b}</strong>`);
  s = s.replace(/(^|[^*\w])\*([^*\n]+)\*(?!\w)|(^|[^_\w])_([^_\n]+)_(?!\w)/g, (_m, p1?: string, a?: string, p2?: string, b?: string) => `${p1 ?? p2 ?? ''}<em>${a ?? b}</em>`);
  s = s.replace(/~~([^~]+)~~/g, '<del>$1</del>');
  return s.replace(/\u0000(\d+)\u0000/g, (_m, i: string) => codes[Number(i)]!);
}

function tableRow(line: string): string[] {
  return line.trim().replace(/^\||\|$/g, '').split('|').map((c) => c.trim());
}

/**
 * Markdown → HTML built only from escaped text and a fixed set of tags
 * (headings, paragraphs, lists, quotes, code, tables, links, embedded images).
 * Raw HTML in the source is shown as text, never interpreted.
 */
export function renderMarkdown(md: string): string {
  const lines = md.replace(/\r\n?/g, '\n').split('\n');
  const out: string[] = [];
  let i = 0;
  const para: string[] = [];
  const flush = () => {
    if (para.length) out.push(`<p>${inline(para.join(' '))}</p>`);
    para.length = 0;
  };
  while (i < lines.length) {
    const line = lines[i]!;
    const fence = /^\s*(`{3,}|~{3,})\s*([\w+#.-]*)/.exec(line);
    if (fence) {
      flush();
      const body: string[] = [];
      i++;
      while (i < lines.length && !lines[i]!.trim().startsWith(fence[1]!)) body.push(lines[i++]!);
      i++;
      out.push(`<pre class="md-code"${fence[2] ? ` data-lang="${escapeHtml(fence[2])}"` : ''}><code>${escapeHtml(body.join('\n'))}</code></pre>`);
      continue;
    }
    const h = /^(#{1,6})\s+(.*)$/.exec(line);
    if (h) {
      flush();
      out.push(`<h${h[1]!.length}>${inline(h[2]!.replace(/\s#+\s*$/, ''))}</h${h[1]!.length}>`);
      i++;
      continue;
    }
    if (/^\s*([-*_])(\s*\1){2,}\s*$/.test(line)) {
      flush();
      out.push('<hr>');
      i++;
      continue;
    }
    if (/^\s*>/.test(line)) {
      flush();
      const body: string[] = [];
      while (i < lines.length && /^\s*>/.test(lines[i]!)) body.push(lines[i++]!.replace(/^\s*>\s?/, ''));
      out.push(`<blockquote>${renderMarkdown(body.join('\n'))}</blockquote>`);
      continue;
    }
    if (/^\s*\|.*\|\s*$/.test(line) && /^\s*\|?\s*:?-{2,}/.test(lines[i + 1] ?? '')) {
      flush();
      const head = tableRow(line);
      i += 2;
      const rows: string[][] = [];
      while (i < lines.length && /^\s*\|.*\|\s*$/.test(lines[i]!)) rows.push(tableRow(lines[i++]!));
      out.push(`<table><thead><tr>${head.map((c) => `<th>${inline(c)}</th>`).join('')}</tr></thead><tbody>${rows.map((r) => `<tr>${r.map((c) => `<td>${inline(c)}</td>`).join('')}</tr>`).join('')}</tbody></table>`);
      continue;
    }
    const li = /^\s*([-*+]|\d+[.)])\s+(.*)$/.exec(line);
    if (li) {
      flush();
      const ordered = /\d/.test(li[1]!);
      const items: string[] = [];
      while (i < lines.length) {
        const m = /^\s*([-*+]|\d+[.)])\s+(.*)$/.exec(lines[i]!);
        if (!m || /\d/.test(m[1]!) !== ordered) break;
        const task = /^\[( |x|X)\]\s+(.*)$/.exec(m[2]!);
        items.push(task ? `<li class="md-task"><span class="md-box${task[1] !== ' ' ? ' on' : ''}" aria-hidden="true"></span>${inline(task[2]!)}</li>` : `<li>${inline(m[2]!)}</li>`);
        i++;
      }
      out.push(ordered ? `<ol>${items.join('')}</ol>` : `<ul>${items.join('')}</ul>`);
      continue;
    }
    if (!line.trim()) {
      flush();
      i++;
      continue;
    }
    para.push(line.trim());
    i++;
  }
  flush();
  return out.join('\n');
}

// ───────────────────────────── HTML (sandboxed iframes) ─────────────────────────────

/** The same no-network policy the server sends with stored artifacts. */
export const ARTIFACT_CSP =
  "default-src 'none'; script-src 'unsafe-inline' 'unsafe-eval' data: blob:; style-src 'unsafe-inline' data:; img-src data: blob:; media-src data: blob:; font-src data:; connect-src 'none'; form-action 'none'";

/**
 * HTML for an iframe `srcdoc`: a Content-Security-Policy meta tag goes first so the page can run its own
 * scripts inside the sandbox but cannot load or send anything over the network.
 */
export function sandboxedSrcdoc(html: string): string {
  const meta = `<meta http-equiv="Content-Security-Policy" content="${ARTIFACT_CSP}">`;
  const doctype = /^\s*<!doctype[^>]*>/i.exec(html);
  if (doctype) return `${doctype[0]}${meta}${html.slice(doctype[0].length)}`;
  return `<!doctype html>${meta}${html}`;
}

// ───────────────────────────── CSV ─────────────────────────────

/** RFC 4180-style CSV/TSV parser (quoted fields, doubled quotes, newlines inside quotes). */
export function parseDelimited(text: string, delimiter?: string, maxRows = Infinity): { rows: string[][]; truncated: boolean; delimiter: string } {
  const firstLine = text.slice(0, text.indexOf('\n') >>> 0 || text.length);
  const d = delimiter ?? ((firstLine.match(/\t/g)?.length ?? 0) > (firstLine.match(/,/g)?.length ?? 0) ? '\t' : (firstLine.match(/;/g)?.length ?? 0) > (firstLine.match(/,/g)?.length ?? 0) ? ';' : ',');
  const rows: string[][] = [];
  let row: string[] = [];
  let field = '';
  let q = false;
  let truncated = false;
  for (let i = 0; i < text.length; i++) {
    const c = text[i]!;
    if (q) {
      if (c === '"') {
        if (text[i + 1] === '"') {
          field += '"';
          i++;
        } else q = false;
      } else field += c;
      continue;
    }
    if (c === '"' && field === '') q = true;
    else if (c === d) {
      row.push(field);
      field = '';
    } else if (c === '\n' || c === '\r') {
      if (c === '\r' && text[i + 1] === '\n') i++;
      row.push(field);
      rows.push(row);
      row = [];
      field = '';
      if (rows.length >= maxRows) {
        truncated = i < text.length - 1;
        break;
      }
    } else field += c;
  }
  if (!truncated && (field !== '' || row.length)) {
    row.push(field);
    rows.push(row);
  }
  return { rows, truncated, delimiter: d };
}
