/**
 * One commission's gallery wall: every artist's painting in its frame under a spotlight, with its museum
 * placard. The best-judged painting wears a rosette. Used by the Gallery page and the Presenter.
 */
import type { ReactNode } from 'react';
import { GalleryFrame, GalleryRosette } from './GalleryFrame.tsx';
import { MuseumPlacard } from './MuseumPlacard.tsx';
import { ChecklistRibbon } from './BriefChecklist.tsx';
import { bestOf, frameFor, type Brief, type WallEntry } from './galleryModel.ts';
import './gallery.css';

const cx = (...p: Array<string | false | null | undefined>) => p.filter(Boolean).join(' ');

/** Columns for n paintings: one row up to 4, then two rows. */
export function wallColumns(n: number): number {
  if (n <= 4) return Math.max(1, n);
  if (n <= 6) return 3;
  return 4;
}

export function GalleryWall({
  brief,
  entries,
  mode = 'image',
  variant = 'page',
  ribbons = false,
  winner = true,
  onSelect,
  selectedKey,
  footer,
}: {
  brief: Brief;
  entries: WallEntry[];
  mode?: 'image' | 'code';
  /** "slide": fixed layout for the 1920×1080 Presenter stage. */
  variant?: 'page' | 'slide';
  /** Checklist ticks over each painting's corner. */
  ribbons?: boolean;
  winner?: boolean;
  onSelect?: (e: WallEntry) => void;
  selectedKey?: string | null;
  footer?: ReactNode;
}) {
  const best = winner ? bestOf(entries) : null;
  // The Presenter hangs up to six paintings in one row, like a real gallery wall.
  const cols = variant === 'slide' && entries.length <= 6 ? Math.max(1, entries.length) : wallColumns(entries.length);
  const frame = frameFor(brief.n);
  const small = variant === 'slide' && entries.length > 4;
  return (
    <div className={cx('gal-wall', `is-${variant}`, `cols-${cols}`, small && 'is-dense')} style={{ ['--cols' as string]: String(cols) }}>
      {entries.map((e) => (
        <div key={e.key} className={cx('gal-slot', selectedKey === e.key && 'is-selected', e.baseline && 'is-baseline')}>
          <GalleryFrame
            src={e.url}
            alt={`${brief.title}, painted by ${e.label}`}
            width={e.width}
            height={e.height}
            frame={frame}
            state={e.state}
            mode={mode}
            size={variant === 'slide' ? (small ? 'sm' : 'md') : 'md'}
            overlay={ribbons && e.detail && e.state === 'painting' && e.detail.artistry !== null ? <ChecklistRibbon detail={e.detail} /> : undefined}
            badge={best?.key === e.key ? <GalleryRosette label="Best in commission" size={variant === 'slide' ? 46 : 40} /> : undefined}
            onClick={onSelect ? () => onSelect(e) : undefined}
          />
          <MuseumPlacard
            title={brief.title}
            artist={e.label}
            color={e.color}
            medium={brief.medium}
            style={brief.style}
            detail={e.detail}
            state={e.state}
            score={e.score}
            costUsd={e.costUsd}
            manual={e.manual}
            baseline={e.baseline}
            mode={mode}
            size={variant === 'slide' ? 'sm' : 'md'}
          />
        </div>
      ))}
      {footer}
    </div>
  );
}
