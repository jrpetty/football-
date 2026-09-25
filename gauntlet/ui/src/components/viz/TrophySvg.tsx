/**
 * A gold trophy drawn in SVG (the emoji renders differently on every OS).
 * Sized by font-size (1em square), so existing emoji sizing rules keep working.
 */
import { useId } from 'react';

export function TrophySvg({ className, title }: { className?: string; title?: string }) {
  const id = useId().replace(/:/g, '');
  return (
    <svg viewBox="0 0 64 64" width="1em" height="1em" className={className} role={title ? 'img' : undefined} aria-hidden={title ? undefined : true} focusable="false">
      {title && <title>{title}</title>}
      <defs>
        <linearGradient id={`tg-${id}`} x1="0" y1="0" x2="1" y2="1">
          <stop offset="0" stopColor="#ffe58a" />
          <stop offset="0.45" stopColor="#f5c518" />
          <stop offset="1" stopColor="#b8860b" />
        </linearGradient>
      </defs>
      <g fill={`url(#tg-${id})`} stroke="#8a6508" strokeWidth="1.5" strokeLinejoin="round">
        <path d="M14 8h36v10c0 12-7 21-18 22C21 39 14 30 14 18Z" />
        <path d="M14 12H6v5c0 7 5 12 12 13l-1-4c-4-1-7-4-7-9v-1h4Z" />
        <path d="M50 12h8v5c0 7-5 12-12 13l1-4c4-1 7-4 7-9v-1h-4Z" />
        <path d="M28 40h8v8h-8Z" />
        <path d="M20 48h24l2 8H18Z" />
      </g>
      <path d="M21 13c0 8 2 15 7 20" fill="none" stroke="#fff6cc" strokeWidth="2.4" strokeLinecap="round" opacity="0.8" />
      <rect x="22" y="50" width="20" height="3" rx="1.5" fill="#8a6508" opacity="0.5" />
    </svg>
  );
}
