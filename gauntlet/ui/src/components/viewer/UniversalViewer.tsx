/**
 * Universal output viewer: shows any file a model produced (or Gauntlet
 * recorded) the right way — games you can play, pictures, PDFs, audio,
 * video, JSON against its key, tables, Markdown, code, diffs, logs, ZIPs,
 * replays and transcripts — and anything else as a safe hex preview with a
 * download link. Type = magic bytes → MIME → extension → content.
 */
import { useEffect, useMemo, useRef, useState } from 'react';
import { Icon } from '../icons.tsx';
import { cx } from '../ui.tsx';
import { detectKind, fmtSize } from './detect.ts';
import { viewerFor } from './registry.tsx';
import { useFileContent } from './useFileContent.ts';
import type { ViewerFile, ViewerKind } from './types.ts';
import './viewer.css';

const KIND_GLYPH: Record<string, string> = {
  html: '▶',
  svg: '◇',
  image: '▣',
  pdf: '▤',
  audio: '♪',
  video: '▶',
  json: '{ }',
  csv: '▦',
  markdown: '¶',
  code: '</>',
  diff: '±',
  text: '≡',
  zip: '⧉',
  replay: '⟲',
  transcript: '☰',
  binary: '01',
};

function useDownloadUrl(file: ViewerFile | null): string | null {
  const url = useMemo(() => {
    if (!file) return null;
    if (file.url) return file.url;
    if (file.bytes) return URL.createObjectURL(new Blob([file.bytes as BlobPart], { type: file.mime || 'application/octet-stream' }));
    if (file.text !== undefined) return URL.createObjectURL(new Blob([file.text], { type: `${file.mime || 'text/plain'};charset=utf-8` }));
    return null;
  }, [file]);
  useEffect(() => () => void (url && url.startsWith('blob:') && URL.revokeObjectURL(url)), [url]);
  return url;
}

export function kindOfFile(f: ViewerFile, bytes?: Uint8Array): ViewerKind {
  return detectKind({ name: f.name, mime: f.mime, hint: f.hint ?? (f.replay ? 'replay' : f.transcript ? 'transcript' : undefined), text: f.text, bytes: bytes ?? f.bytes }).kind;
}

export function UniversalViewer({ files, height = 520, activeId, onActive, className, emptyText = 'Nothing was recorded for this result.' }: { files: ViewerFile[]; height?: number; activeId?: string; onActive?: (id: string) => void; className?: string; emptyText?: string }) {
  const [own, setOwn] = useState<string | null>(null);
  const [source, setSource] = useState(false);
  const rootRef = useRef<HTMLDivElement>(null);
  const current = files.find((f) => f.id === (activeId ?? own)) ?? files[0] ?? null;
  useEffect(() => setSource(false), [current?.id]);

  const guess = current ? kindOfFile(current) : 'binary';
  const plugin0 = viewerFor(guess);
  // Files we can't place from their name get their bytes read so magic bytes can decide.
  const needs = !current ? 'none' : plugin0.needs === 'none' && current.url && !current.hint && (guess === 'binary' || guess === 'text') ? 'bytes' : plugin0.needs;
  const content = useFileContent(current, needs);
  const kind = current && content.bytes && !current.hint ? kindOfFile(current, content.bytes) : guess;
  const plugin = viewerFor(kind);
  const View = plugin.Component;
  const download = useDownloadUrl(current);
  if (!current) return <div className={cx('uv', className)}><div className="uv-empty">{emptyText}</div></div>;
  const select = (id: string) => (onActive ? onActive(id) : setOwn(id));
  const size = current.size ?? content.bytes?.length ?? (current.text !== undefined ? new TextEncoder().encode(current.text).length : undefined);
  const stageH = Math.max(240, height);
  return (
    <div className={cx('uv', className)} ref={rootRef}>
      {files.length > 1 && (
        <div className="uv-tabs" role="tablist" aria-label="Files">
          {files.map((f) => {
            const k = kindOfFile(f);
            return (
              <button key={f.id} type="button" role="tab" aria-selected={f.id === current.id} className={cx('uv-tab', f.id === current.id && 'on')} onClick={() => select(f.id)} title={f.name}>
                <span className={cx('uv-glyph', `k-${k}`)} aria-hidden="true">
                  {KIND_GLYPH[k] ?? '•'}
                </span>
                <span className="uv-tab-t">
                  <span className="uv-tab-role">{f.role ?? viewerFor(k).label}</span>
                  <span className="uv-tab-name">{f.name}</span>
                </span>
              </button>
            );
          })}
        </div>
      )}
      <div className="uv-bar">
        <span className={cx('uv-kind', `k-${kind}`)}>
          <span aria-hidden="true">{KIND_GLYPH[kind] ?? '•'}</span> {plugin.label}
        </span>
        <span className="uv-name ellipsis" title={current.name}>
          {current.name}
        </span>
        {size !== undefined && kind !== 'replay' && kind !== 'transcript' && <span className="muted tnum">{fmtSize(size)}</span>}
        <span className="spacer" />
        {plugin.hasSource && (
          <div className="seg sm" role="group" aria-label="Show">
            <button type="button" aria-pressed={!source} onClick={() => setSource(false)}>
              {kind === 'html' ? 'Page' : kind === 'csv' ? 'Table' : 'Rendered'}
            </button>
            <button type="button" aria-pressed={source} onClick={() => setSource(true)}>
              Source
            </button>
          </div>
        )}
        {kind !== 'replay' && kind !== 'transcript' && kind !== 'html' && (
          <button type="button" className="btn xs ghost" onClick={() => void (document.fullscreenElement ? document.exitFullscreen() : rootRef.current?.requestFullscreen())} title="Full screen">
            <Icon.Maximize />
          </button>
        )}
        {download && kind !== 'replay' && kind !== 'transcript' && (
          <a className="btn xs" href={download} download={current.name} title="Download this file">
            <Icon.Download /> Download
          </a>
        )}
      </div>
      <div className="uv-stage" style={{ minHeight: Math.min(stageH, 260) }}>
        {content.loading ? (
          <div className="uv-empty">Loading {current.name}…</div>
        ) : content.error ? (
          <div className="uv-empty bad">
            {content.error}
            {download && (
              <a className="btn xs" href={download} download={current.name}>
                <Icon.Download /> Try downloading it
              </a>
            )}
          </div>
        ) : content.tooBig && kind !== 'image' && kind !== 'video' ? (
          <div className="uv-empty">This file is too big to preview here ({fmtSize(current.size)}). Download it to open it.</div>
        ) : (
          <View file={current} content={content} height={stageH} source={source} />
        )}
      </div>
    </div>
  );
}
