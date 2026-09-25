/**
 * The story of an agent run as a row of icon tiles (read → search → edit →
 * run tests → submit). Past steps are solid, the current one is lifted and
 * outlined, future ones are faded. Tiles are buttons when `onSeek` is given.
 */
import { useEffect, useRef } from 'react';
import type { ReactNode } from 'react';
import { cx } from '../ui.tsx';
import './viz.css';

export interface StoryItem {
  /** Icon drawn in the tile. */
  icon: ReactNode;
  /** Short word under the icon, e.g. "Edit". */
  label: string;
  /** Extra line, e.g. "6/9" or "+3 −1". */
  sub?: string;
  tone?: 'good' | 'bad' | 'neutral' | 'warn';
  /** Tooltip. */
  title?: string;
  /** Small red flag in the corner (e.g. "a bug the visible tests missed"). */
  flag?: boolean;
}

export function ActionStoryStrip({ items, current, onSeek, label = 'What the agent did, step by step' }: { items: StoryItem[]; current: number; onSeek?: (i: number) => void; label?: string }) {
  const ref = useRef<HTMLOListElement>(null);
  useEffect(() => {
    const el = ref.current?.querySelector<HTMLElement>('.st-item.on');
    const box = ref.current;
    if (!el || !box) return;
    // Scroll only when the current tile is out of view, keeping one tile of context before it.
    const l = el.getBoundingClientRect().left - box.getBoundingClientRect().left + box.scrollLeft;
    if (l < box.scrollLeft + 8 || l + el.clientWidth > box.scrollLeft + box.clientWidth - 8) box.scrollTo({ left: Math.max(0, l - el.clientWidth - 16), behavior: 'smooth' });
  }, [current]);
  return (
    <ol className="st-strip" ref={ref} aria-label={label}>
      {items.map((it, i) => {
        const body = (
          <>
            <span className="st-ico" aria-hidden="true">
              {it.icon}
            </span>
            <span className="st-l">{it.label}</span>
            {it.sub && <span className="st-sub tnum">{it.sub}</span>}
            {it.flag && <span className="st-flag" aria-hidden="true" />}
          </>
        );
        return (
          <li key={i} className={cx('st-item', i < current && 'past', i === current && 'on', i > current && 'future', it.tone && `t-${it.tone}`)} title={it.title}>
            {i > 0 && <span className="st-link" aria-hidden="true" />}
            {onSeek ? (
              <button type="button" onClick={() => onSeek(i)} aria-label={`Step ${i + 1}: ${it.title ?? it.label}`} aria-current={i === current ? 'step' : undefined}>
                {body}
              </button>
            ) : (
              <div aria-current={i === current ? 'step' : undefined}>{body}</div>
            )}
          </li>
        );
      })}
    </ol>
  );
}
