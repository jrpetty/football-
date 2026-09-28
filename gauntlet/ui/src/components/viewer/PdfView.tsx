/**
 * PDF: the browser's own PDF viewer in an <object> (from a blob: URL, so the
 * document never talks to the network through us), next to the text we can
 * read out of the file ourselves (so a grader can skim it even where the
 * browser has no PDF viewer), plus version, page count and title.
 */
import { useEffect, useMemo, useState } from 'react';
import { Icon } from '../icons.tsx';
import { fmtSize } from './detect.ts';
import type { ViewerProps } from './types.ts';

/** Version, page count and title read straight from the PDF bytes (no parsing library). */
export function pdfFacts(b: Uint8Array): { version: string | null; pages: number | null; title: string | null } {
  const head = new TextDecoder('latin1').decode(b.slice(0, Math.min(b.length, 2_000_000)));
  const version = /^%PDF-(\d\.\d)/.exec(head)?.[1] ?? null;
  const pages = (head.match(/\/Type\s*\/Page(?!s)\b/g) ?? []).length || Number(/\/Count\s+(\d+)/.exec(head)?.[1] ?? '') || null;
  const title = /\/Title\s*\(([^)]{1,200})\)/.exec(head)?.[1] ?? null;
  return { version, pages, title };
}

function unescapePdf(s: string): string {
  return s.replace(/\\([nrtbf()\\]|\d{1,3})/g, (_m, c: string) => (/^\d/.test(c) ? String.fromCharCode(parseInt(c, 8)) : c === 'n' ? '\n' : c === 'r' || c === 't' || c === 'b' || c === 'f' ? ' ' : c));
}

async function inflate(data: Uint8Array): Promise<Uint8Array | null> {
  if (typeof DecompressionStream === 'undefined') return null;
  try {
    const ds = new DecompressionStream('deflate');
    const out = new Response(new Blob([data as BlobPart]).stream().pipeThrough(ds));
    return new Uint8Array(await out.arrayBuffer());
  } catch {
    return null;
  }
}

/** Text shown by simple text operators (Tj / TJ / ' / "), from plain or Flate-compressed content streams. */
export async function pdfText(b: Uint8Array, maxStreams = 40): Promise<string[]> {
  const latin = new TextDecoder('latin1').decode(b);
  const lines: string[] = [];
  const re = /<<([^]*?)>>\s*stream\r?\n/g;
  let m: RegExpExecArray | null;
  let n = 0;
  while ((m = re.exec(latin)) && n++ < maxStreams) {
    const start = m.index + m[0].length;
    const end = latin.indexOf('endstream', start);
    if (end < 0) break;
    let body: Uint8Array | null = b.slice(start, end);
    if (/\/FlateDecode/.test(m[1]!)) body = await inflate(body);
    if (!body || /\/Subtype\s*\/Image|\/Type\s*\/XObject/.test(m[1]!)) continue;
    const text = new TextDecoder('latin1').decode(body);
    for (const block of text.split(/\bET\b/)) {
      const parts = [...block.matchAll(/\((?:\\.|[^\\)])*\)/g)].map((x) => unescapePdf(x[0].slice(1, -1)));
      const line = parts.join('').replace(/\s+/g, ' ').trim();
      if (line && /[A-Za-z0-9]/.test(line)) lines.push(line);
    }
    if (lines.length > 400) break;
  }
  return lines;
}

export default function PdfView({ file, content, height }: ViewerProps) {
  const url = useMemo(() => (content.bytes ? URL.createObjectURL(new Blob([content.bytes as BlobPart], { type: 'application/pdf' })) : null), [content.bytes]);
  useEffect(() => () => void (url && URL.revokeObjectURL(url)), [url]);
  const facts = useMemo(() => (content.bytes ? pdfFacts(content.bytes) : null), [content.bytes]);
  const [text, setText] = useState<string[] | null>(null);
  useEffect(() => {
    let alive = true;
    setText(null);
    if (content.bytes) void pdfText(content.bytes).then((t) => alive && setText(t));
    return () => {
      alive = false;
    };
  }, [content.bytes]);
  const h = Math.max(260, height - 46);
  return (
    <div className="uv-media">
      <div className="uv-pdf-split">
        <div className="uv-pdf" style={{ height: h }}>
          {url && (
            <object data={`${url}#view=FitH&navpanes=0`} type="application/pdf" aria-label={file.name}>
              <div className="uv-pdf-fallback">
                <Icon.Book />
                <strong>{facts?.title ?? file.name}</strong>
                <span className="muted">This browser can’t show PDFs inside the page. Open it in a new tab or download it.</span>
                <a className="btn primary sm" href={url} target="_blank" rel="noreferrer noopener">
                  <Icon.External /> Open the PDF
                </a>
              </div>
            </object>
          )}
        </div>
        <aside className="uv-pdf-text" style={{ height: h }} aria-label="Text read from the PDF">
          <div className="uv-pdf-text-k">Text in this PDF</div>
          {text === null ? (
            <div className="muted">Reading…</div>
          ) : text.length ? (
            text.slice(0, 200).map((l, i) => (
              <p key={i} className={i === 0 ? 'lead' : undefined}>
                {l}
              </p>
            ))
          ) : (
            <div className="muted">No readable text (scanned pages or embedded fonts). Use the viewer or open it in a new tab.</div>
          )}
        </aside>
      </div>
      <div className="uv-media-bar">
        <span className="badge outline">PDF{facts?.version ? ` ${facts.version}` : ''}</span>
        {facts?.pages ? <span className="muted tnum">{facts.pages} page{facts.pages === 1 ? '' : 's'}</span> : null}
        {facts?.title && <span className="muted ellipsis">“{facts.title}”</span>}
        <span className="muted tnum">{fmtSize(content.bytes?.length ?? file.size)}</span>
        {url && (
          <a className="btn xs" href={url} target="_blank" rel="noreferrer noopener">
            <Icon.External /> Open in a new tab
          </a>
        )}
      </div>
    </div>
  );
}
