/** Gauntlet brand mark + wordmark (shared by the shell and the presenter). */
import { useId } from 'react';

export function BrandMark({ className }: { className?: string }) {
  // Unique gradient id per instance: duplicate ids break when an earlier instance is hidden.
  const gid = `gm-${useId().replace(/:/g, '')}`;
  return (
    <svg className={className} viewBox="0 0 36 36" aria-hidden="true">
      <defs>
        <linearGradient id={gid} x1="0" y1="0" x2="1" y2="1">
          <stop offset="0" stopColor="#22d3ee" />
          <stop offset="1" stopColor="#a78bfa" />
        </linearGradient>
      </defs>
      <rect x="1" y="1" width="34" height="34" rx="9" fill="#0a0f16" stroke={`url(#${gid})`} strokeOpacity="0.55" />
      <path d="M9.5 25 L18 9 L26.5 25" fill="none" stroke={`url(#${gid})`} strokeWidth="3.2" strokeLinecap="round" strokeLinejoin="round" />
      <path d="M13.4 19.6 H22.6" stroke="#eef3fa" strokeWidth="3" strokeLinecap="round" />
    </svg>
  );
}

export function Wordmark({ tagline = true }: { tagline?: boolean }) {
  return (
    <div>
      <div className="brand-word">GAUNTLET</div>
      {tagline && <div className="brand-tag">AI Benchmark Lab</div>}
    </div>
  );
}
