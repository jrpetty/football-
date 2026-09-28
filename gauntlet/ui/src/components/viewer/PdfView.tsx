/**
 * PDF: the browser's own PDF viewer in an <object> (from a blob: URL, so the
 * document never talks to the network through us), with a fallback card and
 * the facts we can read from the file itself (version, page count, title).
 */
import { useEffect, useMemo } from 'react';
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

export default function PdfView({ file, content, height }: ViewerProps) {
  const url = useMemo(() => (content.bytes ? URL.createObjectURL(new Blob([content.bytes as BlobPart], { type: 'application/pdf' })) : null), [content.bytes]);
  useEffect(() => () => void (url && URL.revokeObjectURL(url)), [url]);
  const facts = useMemo(() => (content.bytes ? pdfFacts(content.bytes) : null), [content.bytes]);
  return (
    <div className="uv-media">
      <div className="uv-pdf" style={{ height: Math.max(260, height - 46) }}>
        {url && (
          <object data={url} type="application/pdf" aria-label={file.name}>
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
