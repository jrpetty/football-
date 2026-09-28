/**
 * A drawn icon for a test category (reasoning, maths, coding…). Same paths as
 * the Shorts cards (src/versus/glyphs.ts), so a category looks the same on
 * every screen and every OS. Sized by font-size (1em square).
 */
import { glyphPath } from '../../../../src/versus/glyphs.ts';

export function CategoryGlyph({ category, className, title }: { category: string; className?: string; title?: string }) {
  return (
    <svg viewBox="0 0 24 24" width="1em" height="1em" fill="none" stroke="currentColor" strokeWidth={2} strokeLinecap="round" strokeLinejoin="round" className={className} role={title ? 'img' : undefined} aria-hidden={title ? undefined : true} focusable="false">
      {title && <title>{title}</title>}
      <path d={glyphPath(category)} />
    </svg>
  );
}
