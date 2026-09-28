/**
 * The live commentary, two ways: a scrolling feed (newest on top) for the run
 * page and the Watch view, and a lower-third crawl for recording / OBS.
 * Every line comes from commentary.ts (real events only).
 */
import { useEffect, useLayoutEffect, useRef, useState } from 'react';
import type { CSSProperties, ReactNode } from 'react';
import { cx } from '../ui.tsx';
import { Icon } from '../icons.tsx';
import { usePrefersReducedMotion } from '../../hooks.ts';
import type { CommentaryLine } from './commentary.ts';
import '../../styles/live-watch.css';

const CROWN = (
  <svg viewBox="0 0 24 24" aria-hidden="true">
    <path d="M3 8l4.5 4L12 5l4.5 7L21 8l-2 11H5Z" fill="currentColor" />
  </svg>
);

export function lineIcon(l: CommentaryLine): ReactNode {
  if (l.kind === 'lead') return CROWN;
  if (l.kind === 'streak') return <Icon.Zap />;
  if (l.kind === 'progress' || l.kind === 'finish') return <Icon.Flag />;
  if (l.tone === 'good') return <Icon.Check />;
  if (l.tone === 'bad') return <Icon.X />;
  if (l.tone === 'partial')
    return (
      <svg viewBox="0 0 24 24" aria-hidden="true">
        <circle cx="12" cy="12" r="8" fill="none" stroke="currentColor" strokeWidth="2.4" />
        <path d="M12 4a8 8 0 0 1 0 16Z" fill="currentColor" />
      </svg>
    );
  if (l.tone === 'warn') return <Icon.Alert />;
  return <Icon.Info />;
}

function ago(at: string, now: number): string {
  const s = Math.max(0, Math.round((now - Date.parse(at)) / 1000));
  if (!Number.isFinite(s)) return '';
  if (s < 5) return 'now';
  if (s < 60) return `${s}s ago`;
  const m = Math.floor(s / 60);
  return m < 60 ? `${m}m ago` : `${Math.floor(m / 60)}h ago`;
}

export function CommentaryFeed({ lines, now, max = 30, title = 'Live commentary', empty }: { lines: CommentaryLine[]; now: number; max?: number; title?: string; empty?: string }) {
  return (
    <section className="cf" aria-label={title}>
      <header className="cf-head">
        <span className="cf-live" aria-hidden="true" />
        <h3>{title}</h3>
        <span className="cf-hint">written from the results as they happen</span>
      </header>
      {lines.length === 0 ? (
        <p className="cf-empty">{empty ?? 'The first line appears when the first answer is graded.'}</p>
      ) : (
        <ol className="cf-list" aria-live="polite">
          {lines.slice(0, max).map((l) => (
            <li key={l.id} className={cx('cf-line', `t-${l.tone}`, `k-${l.kind}`)} style={{ '--c': l.color ?? 'var(--text-3)' } as CSSProperties}>
              <span className="cf-ico">{lineIcon(l)}</span>
              <span className="cf-text">{l.text}</span>
              <span className="cf-ago tnum">{ago(l.at, now)}</span>
            </li>
          ))}
        </ol>
      )}
    </section>
  );
}

/** Lines waiting to enter the crawl beyond this are dropped (oldest first), so it never falls far behind. */
const CRAWL_BACKLOG = 10;

/**
 * Lower-third crawl: a news-style ticker. New lines join the end of the strip
 * and scroll in from the right; lines that have left on the left are removed.
 * It crosses the screen in about 14 s and speeds up a little when busy.
 * Reduced motion: the newest line, still.
 */
export function CommentaryCrawl({ lines, label = 'Live', className }: { lines: CommentaryLine[]; label?: string; className?: string }) {
  const reduced = usePrefersReducedMotion();
  const trackRef = useRef<HTMLDivElement>(null);
  const moveRef = useRef<HTMLDivElement>(null);
  const [items, setItems] = useState<CommentaryLine[]>([]);
  const added = useRef(new Set<string>());
  const x = useRef<number | null>(null);
  const pendingShift = useRef(0);
  const removing = useRef(false);

  // Append lines we have not shown yet (oldest first).
  useEffect(() => {
    const fresh = lines.filter((l) => !added.current.has(l.id)).reverse();
    if (!fresh.length) return;
    for (const l of fresh) added.current.add(l.id);
    setItems((prev) => {
      const next = [...prev, ...fresh];
      const visible = prev.length;
      return next.length - visible > CRAWL_BACKLOG ? [...prev, ...fresh.slice(-CRAWL_BACKLOG)] : next;
    });
  }, [lines]);

  // After each render: settle removals, and make sure new items start at the right edge, not mid-screen.
  useLayoutEffect(() => {
    const track = trackRef.current;
    const move = moveRef.current;
    if (!track || !move) return;
    if (x.current === null) x.current = track.clientWidth;
    x.current += pendingShift.current;
    pendingShift.current = 0;
    removing.current = false;
    const kids = Array.from(move.children) as HTMLElement[];
    for (const k of kids) {
      if (k.dataset.placed) continue;
      k.dataset.placed = '1';
      const left = x.current + k.offsetLeft;
      if (left < track.clientWidth) k.style.marginLeft = `${track.clientWidth - left}px`;
    }
    move.style.transform = `translate3d(${x.current}px,0,0)`;
  }, [items]);

  useEffect(() => {
    if (reduced) return;
    let raf = 0;
    let last = performance.now();
    const step = (t: number) => {
      const dt = Math.min(100, t - last);
      last = t;
      const track = trackRef.current;
      const move = moveRef.current;
      if (track && move && x.current !== null && move.children.length) {
        const busy = Math.max(0, move.children.length - 5);
        const speed = (track.clientWidth / 14000) * (1 + busy * 0.12);
        x.current -= speed * dt;
        move.style.transform = `translate3d(${x.current}px,0,0)`;
        const first = move.children[0] as HTMLElement;
        const gap = parseFloat(getComputedStyle(move).columnGap) || 0;
        if (!removing.current && x.current + first.offsetLeft + first.offsetWidth < 0) {
          removing.current = true;
          pendingShift.current += first.offsetLeft + first.offsetWidth + gap;
          setItems((prev) => prev.slice(1));
        }
      } else if (track && move && !move.children.length) x.current = track.clientWidth;
      raf = requestAnimationFrame(step);
    };
    raf = requestAnimationFrame(step);
    return () => cancelAnimationFrame(raf);
  }, [reduced]);

  const newest = lines[0];
  return (
    <div className={cx('crawl', className)} role="marquee" aria-label="Live commentary">
      <span className="crawl-label">
        <span className="crawl-dot" aria-hidden="true" />
        {label}
      </span>
      <div className="crawl-track" ref={trackRef}>
        {!newest ? (
          <span className="crawl-empty">Commentary starts with the first graded answer…</span>
        ) : reduced ? (
          <span className={cx('crawl-item static', `t-${newest.tone}`)} style={{ '--c': newest.color ?? 'var(--text-3)' } as CSSProperties}>
            <span className="crawl-ico">{lineIcon(newest)}</span>
            {newest.text}
          </span>
        ) : null}
        <div className="crawl-move" ref={moveRef} hidden={reduced}>
          {items.map((l) => (
            <span key={l.id} className={cx('crawl-item', `t-${l.tone}`)} style={{ '--c': l.color ?? 'var(--text-3)' } as CSSProperties}>
              <span className="crawl-ico">{lineIcon(l)}</span>
              {l.text}
            </span>
          ))}
        </div>
      </div>
    </div>
  );
}
