import { useEffect, useState } from 'react';
import { createPortal } from 'react-dom';
import { Icon } from './icons.tsx';
import { cx } from './ui.tsx';
import { copyImage, downloadImage, testImageUrl } from '../vision.ts';
import '../styles/vision.css';

/** Small "picture" glyph used wherever a test shows the model an image. */
export function ImageGlyph({ className }: { className?: string }) {
  return (
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.8} strokeLinecap="round" strokeLinejoin="round" aria-hidden="true" focusable="false" className={className}>
      <rect x="3" y="4" width="18" height="16" rx="2" />
      <circle cx="9" cy="10" r="1.8" />
      <path d="m21 16-5-5-9 9" />
    </svg>
  );
}

/** Badge for tests / cases that show the model a picture. */
export function VisionBadge({ label = 'Image' }: { label?: string }) {
  return (
    <span className="badge vision-badge" title="The model is shown an image">
      <ImageGlyph className="vi-glyph" />
      {label}
    </span>
  );
}

function Lightbox({ src, name, onClose }: { src: string; name: string; onClose: () => void }) {
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && onClose();
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [onClose]);
  return createPortal(
    <div className="vi-lightbox" role="dialog" aria-modal="true" aria-label={`Image ${name}`} onClick={onClose}>
      <img src={src} alt={`Test image ${name}, enlarged`} />
      <div className="vi-lightbox-cap">
        {name} · click anywhere or press Esc to close
      </div>
    </div>,
    document.body,
  );
}

export interface VisionImageProps {
  src: string;
  name: string;
  width?: number;
  height?: number;
  /** Plain-words caption shown under the image. */
  caption?: string;
  /** Show Download and Copy image buttons (Manual Inbox). */
  actions?: boolean;
  size?: 'sm' | 'md' | 'lg' | 'fill';
  className?: string;
}

/** A test image: framed, click to enlarge, optional download / copy buttons. */
export function VisionImage({ src, name, width, height, caption, actions, size = 'md', className }: VisionImageProps) {
  const [open, setOpen] = useState(false);
  const [copied, setCopied] = useState<'ok' | 'fail' | null>(null);
  const [broken, setBroken] = useState(false);
  const dims = width && height ? `${width}×${height}` : null;
  return (
    <figure className={cx('vi-figure', `vi-${size}`, className)}>
      <button type="button" className="vi-frame" onClick={() => !broken && setOpen(true)} title="Click to enlarge" aria-label={`Enlarge image ${name}`}>
        {src && !broken ? (
          <img src={src} alt={`Test image ${name}`} loading="lazy" onError={() => setBroken(true)} />
        ) : (
          <span className="vi-missing">
            <ImageGlyph className="vi-glyph-lg" />
            Image unavailable
          </span>
        )}
      </button>
      <figcaption className="vi-cap">
        <span className="vi-name">
          <ImageGlyph className="vi-glyph" />
          <span className="mono">{name}</span>
          {dims && <span className="muted tnum">{dims}</span>}
        </span>
        {caption && <span className="vi-caption">{caption}</span>}
        {actions && src && (
          <span className="vi-actions">
            <button type="button" className="btn xs" onClick={() => void downloadImage(src, name)}>
              <Icon.Download /> Download
            </button>
            <button
              type="button"
              className="btn xs"
              onClick={async () => {
                const ok = await copyImage(src);
                setCopied(ok ? 'ok' : 'fail');
                window.setTimeout(() => setCopied(null), 1800);
              }}
            >
              {copied === 'ok' ? <Icon.Check /> : <Icon.Copy />}
              {copied === 'ok' ? 'Copied' : copied === 'fail' ? 'Use Download' : 'Copy image'}
            </button>
          </span>
        )}
      </figcaption>
      {open && <Lightbox src={src} name={name} onClose={() => setOpen(false)} />}
    </figure>
  );
}

/** Images of one rendered case (as returned by GET /api/tests/:id), grouped by turn. */
export function CaseImages({ images, turn, size = 'md', caption }: { images?: Array<{ turn: number; file: string; path?: string }>; turn?: number; size?: VisionImageProps['size']; caption?: string }) {
  const list = (images ?? []).filter((img) => turn === undefined || img.turn === turn);
  if (!list.length) return null;
  return (
    <div className={cx('vi-row', list.length > 1 && 'multi')}>
      {list.map((img, i) => (
        <VisionImage key={`${img.file}-${i}`} src={img.path ? testImageUrl(img.path) : ''} name={img.file.split('/').pop() ?? img.file} size={size} caption={caption} />
      ))}
    </div>
  );
}
