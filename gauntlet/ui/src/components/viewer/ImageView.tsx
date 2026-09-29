/** Images (PNG, JPEG, GIF, WebP, AVIF, BMP, ICO): fit or actual size, on a checkerboard, with real dimensions. */
import { useEffect, useMemo, useState } from 'react';
import { cx } from '../ui.tsx';
import type { ViewerProps } from './types.ts';

export default function ImageView({ file, content, height }: ViewerProps) {
  const [zoom, setZoom] = useState<'fit' | 'actual'>('fit');
  const [dims, setDims] = useState<{ w: number; h: number } | null>(null);
  const [failed, setFailed] = useState(false);
  // Bytes without a URL (uploaded files): show them through a blob URL.
  const blobUrl = useMemo(() => (!file.url && content.bytes ? URL.createObjectURL(new Blob([content.bytes as BlobPart], { type: file.mime || 'image/png' })) : null), [file.url, content.bytes, file.mime]);
  useEffect(() => () => void (blobUrl && URL.revokeObjectURL(blobUrl)), [blobUrl]);
  const src = file.url ?? blobUrl ?? '';
  return (
    <div className="uv-media">
      <div className={cx('uv-canvas bg-check', zoom === 'actual' && 'scroll')} style={{ height: Math.max(220, height - 46) }}>
        {failed ? (
          <div className="uv-empty">This browser can’t display this image format. Download it to open it in another app.</div>
        ) : (
          <img
            key={src}
            src={src}
            alt={file.name}
            className={cx(zoom === 'actual' && 'actual', (dims?.w ?? 99) < 64 && 'pixel')}
            onLoad={(e) => setDims({ w: e.currentTarget.naturalWidth, h: e.currentTarget.naturalHeight })}
            onError={() => setFailed(true)}
          />
        )}
      </div>
      <div className="uv-media-bar">
        <div className="seg sm" role="group" aria-label="Zoom">
          <button type="button" aria-pressed={zoom === 'fit'} onClick={() => setZoom('fit')}>
            Fit
          </button>
          <button type="button" aria-pressed={zoom === 'actual'} onClick={() => setZoom('actual')}>
            100%
          </button>
        </div>
        <span className="muted tnum">{dims ? `${dims.w} × ${dims.h} px` : failed ? 'unsupported' : 'loading…'}</span>
      </div>
    </div>
  );
}
