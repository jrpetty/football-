/**
 * One consistent identity for a model everywhere a viewer sees it: a round monogram filled with the
 * contestant colour from config (ink picked for contrast, ringed so it reads on dark and light) plus
 * the model name and, optionally, the maker. The random baseline gets a dashed, unfilled monogram so
 * it never looks like a competitor.
 */
import type { ReactNode } from 'react';
import { inkOn, monogram } from '../clarity/plain.ts';

// Local join (ui.tsx imports this file, so no import back from it).
const cx = (...p: Array<string | false | null | undefined>) => p.filter(Boolean).join(' ');

export type ModelBadgeSize = 'sm' | 'md' | 'lg' | 'xl';

export function ModelMonogram({ label, color, size = 'md', baseline, title }: { label: string; color?: string; size?: ModelBadgeSize; baseline?: boolean; title?: string }) {
  const bg = color || '#7b8494';
  return (
    <span
      className={cx('mb-mono', `mb-${size}`, baseline && 'mb-baseline')}
      style={baseline ? { ['--mb-c' as string]: bg } : { background: bg, color: inkOn(bg), ['--mb-c' as string]: bg }}
      aria-hidden={title ? undefined : true}
      title={title}
    >
      {baseline ? '?' : monogram(label)}
    </span>
  );
}

export function ModelBadge({
  label,
  color,
  vendor,
  size = 'md',
  baseline,
  tag,
  className,
}: {
  label: string;
  color?: string;
  /** Maker shown under the name (omit for a one-line badge). */
  vendor?: ReactNode;
  size?: ModelBadgeSize;
  /** Random-guessing reference contestant. */
  baseline?: boolean;
  /** Extra inline element after the vendor (e.g. a "manual" tag). */
  tag?: ReactNode;
  className?: string;
}) {
  return (
    <span className={cx('model-badge', `mb-${size}`, baseline && 'is-baseline', className)} title={label}>
      <ModelMonogram label={label} color={color} size={size} baseline={baseline} />
      <span className="mb-names">
        <span className="mb-name">{label}</span>
        {(vendor || tag) && (
          <span className="mb-vendor">
            {vendor}
            {tag}
          </span>
        )}
      </span>
    </span>
  );
}
