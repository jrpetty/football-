/** Head to Head nav icon (same 24×24 stroke style as components/icons.tsx): two shields facing each other. */
import type { SVGProps } from 'react';

export function VersusIcon(p: SVGProps<SVGSVGElement>) {
  return (
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.8} strokeLinecap="round" strokeLinejoin="round" aria-hidden="true" focusable="false" {...p}>
      <path d="M2.5 5.5 8 4v7c0 3.5-2.3 6-5.5 7" />
      <path d="M21.5 5.5 16 4v7c0 3.5 2.3 6 5.5 7" />
      <path d="m10 9 2 5 2-5" />
      <path d="M12 17v3" />
    </svg>
  );
}
