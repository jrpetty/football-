/**
 * Icons for the plain-English test explainers (src/core/explainers.ts): one per
 * test plus the small step icons of the "how it's scored" strip. Same 24×24
 * stroke style as components/icons.tsx, drawn as SVG so they look identical on
 * every platform. Decorative (aria-hidden): always paired with text.
 *
 * The Record type makes the typecheck fail if an icon named in EXPLAIN_ICONS
 * has no drawing here.
 */
import type { ReactNode, SVGProps } from 'react';
import type { ExplainIconName } from '../../types.ts';

const PATHS: Record<ExplainIconName, ReactNode> = {
  needle: (
    <>
      <path d="M20 4 7.5 16.5" />
      <path d="M17.2 4.8a1.6 1.6 0 1 1 2 2" />
      <path d="m7.5 16.5-3.5 3.5" />
      <path d="M3 9c3 0 4 2 4 4M14 21c0-3 2-4 4-4" />
    </>
  ),
  whisper: (
    <>
      <path d="M4 6h10a2 2 0 0 1 2 2v4a2 2 0 0 1-2 2H9l-3 3v-3H4a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2Z" />
      <path d="M18 9h2a2 2 0 0 1 2 2v4a2 2 0 0 1-2 2h-1v3l-3-3h-3" />
    </>
  ),
  island: (
    <>
      <path d="M2 20c3-2 6-2 10 0s7 2 10 0" />
      <path d="M12 18c0-5 .5-8 2-11" />
      <path d="M14 7c-2-2-5-2-7 0M14 7c2-2 5-2 7 0M14 7c-1-2-1-4 1-5M14 7c-3 0-5 2-5 5M14 7c3 0 5 2 5 5" />
    </>
  ),
  door: (
    <>
      <path d="M6 21V4a1 1 0 0 1 1-1h10a1 1 0 0 1 1 1v17" />
      <path d="M3 21h18" />
      <circle cx="14.5" cy="12.5" r="1" />
    </>
  ),
  growth: (
    <>
      <path d="M3 3v18h18" />
      <path d="m6 15 4-4 3 3 6-7" />
      <path d="M15 7h4v4" />
    </>
  ),
  bug: (
    <>
      <rect x="8" y="7" width="8" height="12" rx="4" />
      <path d="M9 7.5a3 3 0 0 1 6 0M12 11v8M8 12H4M20 12h-4M8 16H5M19 16h-3M9 8 6 5M15 8l3-3" />
    </>
  ),
  mask: (
    <>
      <path d="M3 6c3 1.5 6 1.5 9 0 3 1.5 6 1.5 9 0v5c0 5-4 9-9 9s-9-4-9-9V6Z" />
      <path d="M7 11c1-1 2.5-1 3.5 0M13.5 11c1-1 2.5-1 3.5 0M9 16c2 1.5 4 1.5 6 0" />
    </>
  ),
  grid: (
    <>
      <rect x="3" y="3" width="18" height="18" rx="2" />
      <path d="M3 9h18M3 15h18M9 3v18M15 3v18" />
      <path d="m10.5 11.5 1.2 1.2 2-2.2" />
    </>
  ),
  route: (
    <>
      <circle cx="6" cy="19" r="2" />
      <circle cx="18" cy="5" r="2" />
      <path d="M8 19h7.5a3.5 3.5 0 0 0 0-7h-7a3.5 3.5 0 0 1 0-7H16" />
    </>
  ),
  knight: (
    <>
      <path d="M7 21h11M8 18h9l-1-6c2-1 3-3 2-5l-3-3-2 1-1-2-2 2c-3 1-5 4-5 7l3-1-1 3 1 4Z" />
      <circle cx="13.5" cy="8" r=".6" fill="currentColor" />
    </>
  ),
  sigma: (
    <>
      <path d="M18 5V4H6l7 8-7 8h12v-1" />
    </>
  ),
  receipt: (
    <>
      <path d="M6 3h12v18l-2-1.5-2 1.5-2-1.5-2 1.5-2-1.5L6 21V3Z" />
      <path d="M9 8h6M9 12h6M9 16h3" />
    </>
  ),
  code: (
    <>
      <path d="m8 7-5 5 5 5M16 7l5 5-5 5M14 4l-4 16" />
    </>
  ),
  gamepad: (
    <>
      <path d="M6 8h12a4 4 0 0 1 4 4v1a4 4 0 0 1-7 2.6L14 15h-4l-1 .6A4 4 0 0 1 2 13v-1a4 4 0 0 1 4-4Z" />
      <path d="M7 11v3M5.5 12.5h3" />
      <circle cx="16" cy="11.5" r=".7" fill="currentColor" />
      <circle cx="18" cy="13.5" r=".7" fill="currentColor" />
    </>
  ),
  document: (
    <>
      <path d="M14 3H6a1 1 0 0 0-1 1v16a1 1 0 0 0 1 1h12a1 1 0 0 0 1-1V8l-5-5Z" />
      <path d="M14 3v5h5" />
      <path d="M10 12.5 8.5 14l1.5 1.5M14 12.5l1.5 1.5-1.5 1.5" />
    </>
  ),
  shield: (
    <>
      <path d="M12 3 4.5 6v5.5c0 4.6 3.2 8.4 7.5 9.5 4.3-1.1 7.5-4.9 7.5-9.5V6L12 3Z" />
      <path d="m8.5 12 2.5 2.5 4.5-5" />
    </>
  ),
  rules: (
    <>
      <path d="M9 6h11M9 12h11M9 18h11" />
      <path d="m3.5 6 1 1 2-2M3.5 12l1 1 2-2M3.5 18l1 1 2-2" />
    </>
  ),
  persona: (
    <>
      <circle cx="12" cy="8" r="4" />
      <path d="M4 21a8 8 0 0 1 16 0" />
      <path d="M16.5 3.5 19 2M19.5 6.5l2 .5" />
    </>
  ),
  trap: (
    <>
      <path d="M12 3 2 20h20L12 3Z" />
      <path d="M12 10v4.5" />
      <circle cx="12" cy="17.3" r=".7" fill="currentColor" />
    </>
  ),
  bolt: <path d="M13 2 4 14h7l-1 8 9-12h-7l1-8Z" />,
  eye: (
    <>
      <path d="M2 12s3.6-7 10-7 10 7 10 7-3.6 7-10 7S2 12 2 12Z" />
      <circle cx="12" cy="12" r="3" />
    </>
  ),
  chart: (
    <>
      <path d="M3 21h18" />
      <rect x="5" y="11" width="3" height="7" rx=".5" />
      <rect x="10.5" y="6" width="3" height="12" rx=".5" />
      <rect x="16" y="13" width="3" height="5" rx=".5" />
    </>
  ),
  count: (
    <>
      <path d="m5 9 3-5 3 5H5Z" />
      <path d="m13 20 3-5 3 5h-6Z" />
      <circle cx="17" cy="6.5" r="2.5" />
      <rect x="4.5" y="14.5" width="5" height="5" rx=".5" />
    </>
  ),
  diff: (
    <>
      <rect x="2.5" y="5" width="8" height="14" rx="1" />
      <rect x="13.5" y="5" width="8" height="14" rx="1" />
      <circle cx="6.5" cy="10" r="1.6" />
      <path d="m16 8.6 1.6 2.8h-3.2L16 8.6Z" />
      <circle cx="17.5" cy="14.5" r="3.6" strokeDasharray="2 2" />
    </>
  ),
  pen: (
    <>
      <path d="M3 21c2-.5 3.5-1.5 4.5-3L19 6.5a2.1 2.1 0 0 0-3-3L4.5 15C3 16 2.5 18 3 21Z" />
      <path d="M14.5 5 18 8.5" />
      <path d="M13 21h8" />
    </>
  ),
  pencil: (
    <>
      <path d="M16 3.5a2.1 2.1 0 0 1 3 3L8 17.5 4 19l1.5-4L16 3.5Z" />
      <path d="M14 5.5l3 3" />
      <circle cx="18" cy="18" r="3" />
    </>
  ),
  shapes: (
    <>
      <circle cx="7.5" cy="7.5" r="4.5" />
      <rect x="13" y="13" width="8" height="8" rx="1" />
      <path d="m17 3 4 7h-8l4-7Z" />
      <path d="M3 21l6-6" />
    </>
  ),
  connect4: (
    <>
      <rect x="3" y="4" width="18" height="16" rx="2" />
      <circle cx="8" cy="9" r="1.6" />
      <circle cx="12" cy="9" r="1.6" />
      <circle cx="16" cy="9" r="1.6" />
      <circle cx="8" cy="15" r="1.6" fill="currentColor" />
      <circle cx="12" cy="15" r="1.6" fill="currentColor" />
      <circle cx="16" cy="15" r="1.6" />
    </>
  ),
  chess: (
    <>
      <path d="M6 21h12M8 18h8M9 18l1-7h4l1 7" />
      <path d="M8.5 11h7M9.5 11 8 6h8l-1.5 5" />
      <path d="M8 6V4h2v1.2h1.2V4h1.6v1.2H14V4h2v2" />
    </>
  ),
  cards: (
    <>
      <rect x="3" y="6" width="10" height="14" rx="1.5" transform="rotate(-10 8 13)" />
      <rect x="11" y="4" width="10" height="14" rx="1.5" transform="rotate(10 16 11)" />
      <path d="M16 9.5c-1-1.2-2.6-.4-2.2.9.3 1 2.2 2.3 2.2 2.3s1.9-1.3 2.2-2.3c.4-1.3-1.2-2.1-2.2-.9Z" />
    </>
  ),
  mic: (
    <>
      <rect x="9" y="2.5" width="6" height="11" rx="3" />
      <path d="M5 11a7 7 0 0 0 14 0M12 18v3M8.5 21h7" />
    </>
  ),
  gavel: (
    <>
      <path d="m14 4 6 6M11.5 6.5l6 6M13 5.5l-4 4 5 5 4-4" />
      <path d="m10.5 11-7 7a1.4 1.4 0 0 0 2 2l7-7" />
      <path d="M13 21h8" />
    </>
  ),
  ask: (
    <>
      <path d="M21 12a8 8 0 0 1-11.5 7.2L4 20.5l1.3-4.6A8 8 0 1 1 21 12Z" />
      <path d="M10.2 9.5a2 2 0 1 1 2.8 1.8c-.6.3-1 .8-1 1.5v.4" />
      <circle cx="12" cy="15.8" r=".6" fill="currentColor" />
    </>
  ),
  check: (
    <>
      <circle cx="12" cy="12" r="9" />
      <path d="m8 12.5 2.8 2.8L16.5 9.5" />
    </>
  ),
  cross: (
    <>
      <circle cx="12" cy="12" r="9" />
      <path d="m9 9 6 6M15 9l-6 6" />
    </>
  ),
  play: (
    <>
      <circle cx="12" cy="12" r="9" />
      <path d="m10 8.5 5.5 3.5-5.5 3.5v-7Z" />
    </>
  ),
  scale: (
    <>
      <path d="M12 3v18M7 21h10M5 7h14M12 5V3" />
      <path d="m5 7-3 6a3 3 0 0 0 6 0L5 7ZM19 7l-3 6a3 3 0 0 0 6 0l-3-6Z" />
    </>
  ),
  percent: (
    <>
      <path d="M19 5 5 19" />
      <circle cx="6.5" cy="6.5" r="2.5" />
      <circle cx="17.5" cy="17.5" r="2.5" />
    </>
  ),
  clock: (
    <>
      <circle cx="12" cy="12" r="9" />
      <path d="M12 7v5l3 2" />
    </>
  ),
  target: (
    <>
      <circle cx="12" cy="12" r="9" />
      <circle cx="12" cy="12" r="5" />
      <circle cx="12" cy="12" r="1.2" fill="currentColor" />
    </>
  ),
  lock: (
    <>
      <rect x="5" y="11" width="14" height="10" rx="2" />
      <path d="M8 11V8a4 4 0 0 1 8 0v3" />
    </>
  ),
  hidden: (
    <>
      <path d="M3 3l18 18" />
      <path d="M10.6 5.1A10 10 0 0 1 12 5c6.4 0 10 7 10 7a17 17 0 0 1-2.6 3.4M6.6 6.6C3.8 8.4 2 12 2 12s3.6 7 10 7a9.6 9.6 0 0 0 4.4-1" />
      <path d="M9.9 9.9a3 3 0 0 0 4.2 4.2" />
    </>
  ),
  robot: (
    <>
      <rect x="4" y="8" width="16" height="12" rx="2" />
      <path d="M12 8V4M9 20v1M15 20v1M2 13v3M22 13v3" />
      <circle cx="9" cy="13" r="1.2" />
      <circle cx="15" cy="13" r="1.2" />
      <path d="M10 17h4" />
    </>
  ),
  moves: (
    <>
      <path d="M7 16c-1.5 0-2.5-1.3-2.5-3.5S5.5 8 7 8s2.5 2 2.5 4.5S8.5 16 7 16ZM5 18.5h4" />
      <path d="M17 11c-1.5 0-2.5-1.3-2.5-3.5S15.5 3 17 3s2.5 2 2.5 4.5S18.5 11 17 11ZM15 13.5h4" />
    </>
  ),
  flag: (
    <>
      <path d="M5 21V4" />
      <path d="M5 4h11l-2 4 2 4H5" />
    </>
  ),
  trophy: (
    <>
      <path d="M8 21h8M12 17v4M7 4h10v5a5 5 0 0 1-10 0V4Z" />
      <path d="M17 5h3v2a4 4 0 0 1-3.5 4M7 5H4v2a4 4 0 0 0 3.5 4" />
    </>
  ),
  dollar: (
    <>
      <circle cx="12" cy="12" r="9" />
      <path d="M15 9c-.5-1.2-1.7-2-3-2-1.7 0-3 1-3 2.5s1.3 2 3 2.5 3 1 3 2.5-1.3 2.5-3 2.5c-1.3 0-2.5-.8-3-2M12 5.5V7M12 17v1.5" />
    </>
  ),
  ruler: (
    <>
      <rect x="2" y="8" width="20" height="8" rx="1.5" />
      <path d="M6 8v3M10 8v4M14 8v3M18 8v4" />
    </>
  ),
  swap: (
    <>
      <path d="M4 8h14l-3-3M20 16H6l3 3" />
    </>
  ),
  zero: (
    <>
      <ellipse cx="12" cy="12" rx="6" ry="8.5" />
      <path d="m7 19 10-14" />
    </>
  ),
  star: <path d="m12 3 2.7 5.6 6.1.9-4.4 4.3 1 6.1L12 17l-5.4 2.9 1-6.1L3.2 9.5l6.1-.9L12 3Z" />,
  key: (
    <>
      <circle cx="7.5" cy="15.5" r="4.5" />
      <path d="m10.7 12.3 9.3-9.3M17 6l3 3M14.5 8.5l2 2" />
    </>
  ),
};

export function ExplainIcon({ name, ...p }: { name: ExplainIconName | string } & SVGProps<SVGSVGElement>) {
  const body = PATHS[name as ExplainIconName] ?? PATHS.star;
  return (
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.8} strokeLinecap="round" strokeLinejoin="round" aria-hidden="true" focusable="false" {...p}>
      {body}
    </svg>
  );
}
