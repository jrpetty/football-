/**
 * A painting hung in a museum frame (gilt, ebony, walnut, or a matted oak frame for prints), drawn in CSS so it
 * looks the same everywhere and scales cleanly. Empty frames explain why there is no painting (declined, not
 * delivered, cannot paint, awaiting judges), in plain words.
 */
import type { ReactNode } from 'react';
import { stateText, type FrameStyle, type WallState } from './galleryModel.ts';
import './gallery.css';

const cx = (...p: Array<string | false | null | undefined>) => p.filter(Boolean).join(' ');

function EmptyMark({ state }: { state: WallState }) {
  // Simple line drawings: an easel for "no painting", a clock for "awaiting", a crossed brush for "cannot paint".
  const common = { width: 44, height: 44, viewBox: '0 0 24 24', fill: 'none', stroke: 'currentColor', strokeWidth: 1.4, strokeLinecap: 'round' as const, strokeLinejoin: 'round' as const, 'aria-hidden': true };
  if (state === 'awaiting')
    return (
      <svg {...common}>
        <circle cx="12" cy="12" r="8.5" />
        <path d="M12 7.5V12l3 2" />
      </svg>
    );
  if (state === 'no-output')
    return (
      <svg {...common}>
        <path d="M14.5 4.5 19.5 9.5 10 19H5v-5z" />
        <path d="M4 4l16 16" />
      </svg>
    );
  return (
    <svg {...common}>
      <path d="M7 21 12 3l5 18M8.8 14.5h6.4" />
      <rect x="6" y="5" width="12" height="8" rx="0.5" />
    </svg>
  );
}

export function GalleryFrame({
  src,
  alt,
  width = 1536,
  height = 1024,
  frame = 'gilt',
  state = 'painting',
  mode = 'image',
  size = 'md',
  lit = true,
  overlay,
  badge,
  className,
  onClick,
}: {
  src: string | null;
  alt: string;
  width?: number;
  height?: number;
  frame?: FrameStyle;
  state?: WallState;
  mode?: 'image' | 'code';
  size?: 'sm' | 'md' | 'lg' | 'xl';
  /** Gallery spotlight above the frame. */
  lit?: boolean;
  /** Drawn over the canvas (e.g. the checklist marks). */
  overlay?: ReactNode;
  /** Pinned to the frame's corner (e.g. a winner's rosette). */
  badge?: ReactNode;
  className?: string;
  onClick?: () => void;
}) {
  const ratio = width > 0 && height > 0 ? width / height : 1.5;
  const showImage = !!src && (state === 'painting' || state === 'awaiting');
  const empty = stateText(state, mode);
  return (
    <figure className={cx('gal-art', `gal-frame-${frame}`, `gal-${size}`, lit && 'is-lit', onClick && 'is-clickable', className)} style={{ ['--ar' as string]: String(ratio) }} onClick={onClick}>
      <div className="gal-frame">
        <div className="gal-liner">
          <div className="gal-canvas">
            {showImage ? (
              <img src={src!} alt={alt} loading="lazy" decoding="async" draggable={false} />
            ) : (
              <div className={cx('gal-empty', `is-${state}`)} role="img" aria-label={`${alt}: ${empty.title}`}>
                <EmptyMark state={state} />
                <strong>{empty.title || 'Not recorded'}</strong>
                <span>{empty.sub}</span>
              </div>
            )}
            {showImage && state === 'awaiting' && <span className="gal-awaiting">Awaiting judges</span>}
            {overlay}
          </div>
        </div>
      </div>
      {badge && <div className="gal-badge">{badge}</div>}
    </figure>
  );
}

/** A gold prize rosette ("Best in commission", "Masterpiece of the Show", "People's choice"). */
export function GalleryRosette({ label, tone = 'gold', size = 64 }: { label: string; tone?: 'gold' | 'blue'; size?: number }) {
  const c1 = tone === 'gold' ? '#f6d77a' : '#8fb8ff';
  const c2 = tone === 'gold' ? '#b8871b' : '#2f5fb3';
  const points = Array.from({ length: 16 }, (_, i) => {
    const a = (i / 16) * Math.PI * 2;
    const r = i % 2 ? 26 : 31;
    return `${(32 + Math.cos(a) * r).toFixed(1)},${(30 + Math.sin(a) * r).toFixed(1)}`;
  }).join(' ');
  return (
    <span className="gal-rosette" title={label}>
      <svg width={size} height={size * 1.35} viewBox="0 0 64 86" aria-hidden="true">
        <path d="M20 48 12 84l10-7 6 9 6-36z" fill={c2} />
        <path d="M44 48l8 36-10-7-6 9-6-36z" fill={c2} opacity="0.85" />
        <polygon points={points} fill={c2} />
        <circle cx="32" cy="30" r="22" fill={c1} stroke={c2} strokeWidth="2" />
        <circle cx="32" cy="30" r="15" fill="none" stroke={c2} strokeWidth="1.2" strokeDasharray="2 2" />
        <path d="m32 20 3 6.3 6.9.9-5 4.8 1.2 6.8L32 35.6l-6.1 3.2 1.2-6.8-5-4.8 6.9-.9z" fill={c2} />
      </svg>
      <span className="gal-rosette-label">{label}</span>
    </span>
  );
}
