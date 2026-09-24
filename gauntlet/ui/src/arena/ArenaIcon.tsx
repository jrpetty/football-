/** Arena icons (same 24×24 stroke style as components/icons.tsx). */
import type { SVGProps } from 'react';

/** Two crossed swords: the Arena nav item. */
export function ArenaIcon(p: SVGProps<SVGSVGElement>) {
  return (
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.8} strokeLinecap="round" strokeLinejoin="round" aria-hidden="true" focusable="false" {...p}>
      <path d="M14.5 17.5 3 6V3h3l11.5 11.5" />
      <path d="m13 19 6-6" />
      <path d="m16 16 4 4" />
      <path d="m19 21 2-2" />
      <path d="M9.5 6.5 18 3h3v3l-3.5 8.5" />
      <path d="m5 14 6 6" />
      <path d="m7 17-4 4" />
      <path d="m3 19 2 2" />
    </svg>
  );
}

/** Big game glyph for cards: a disc grid for Connect Four, a knight for chess. */
export function GameGlyph({ gameId, className }: { gameId: string; className?: string }) {
  if (gameId === 'chess') {
    return (
      <span className={`game-glyph gg-chess ${className ?? ''}`} aria-hidden="true">
        ♞
      </span>
    );
  }
  return (
    <span className={`game-glyph gg-c4 ${className ?? ''}`} aria-hidden="true">
      <i className="r" />
      <i />
      <i className="y" />
      <i className="y" />
      <i className="r" />
      <i />
      <i className="r" />
      <i className="y" />
      <i className="r" />
    </span>
  );
}
