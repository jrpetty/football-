/** Hand-drawn 24×24 stroke icon set (decorative: aria-hidden). */
import type { ReactNode, SVGProps } from 'react';

type P = SVGProps<SVGSVGElement>;

function Svg({ children, ...p }: P & { children: ReactNode }) {
  return (
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.8} strokeLinecap="round" strokeLinejoin="round" aria-hidden="true" focusable="false" {...p}>
      {children}
    </svg>
  );
}

export const Icon = {
  Trophy: (p: P) => (
    <Svg {...p}>
      <path d="M8 21h8M12 17v4M7 4h10v5a5 5 0 0 1-10 0V4Z" />
      <path d="M17 5h3v2a4 4 0 0 1-3.5 4M7 5H4v2a4 4 0 0 0 3.5 4" />
    </Svg>
  ),
  Rocket: (p: P) => (
    <Svg {...p}>
      <path d="M5 15c-1.5 1.5-2 5-2 5s3.5-.5 5-2c.9-.9.9-2.2 0-3a2.1 2.1 0 0 0-3 0Z" />
      <path d="M9 12a22 22 0 0 1 11-9 22 22 0 0 1-9 11l-2-2Z" />
      <path d="M9 12H5l2.5-4H12M12 15v4l4-2.5V12" />
    </Svg>
  ),
  History: (p: P) => (
    <Svg {...p}>
      <path d="M3 12a9 9 0 1 0 3-6.7L3 8" />
      <path d="M3 3v5h5M12 7v5l3.5 2" />
    </Svg>
  ),
  Flask: (p: P) => (
    <Svg {...p}>
      <path d="M9 3h6M10 3v6l-5.5 9.5A2 2 0 0 0 6.2 21h11.6a2 2 0 0 0 1.7-2.5L14 9V3" />
      <path d="M7.5 15h9" />
    </Svg>
  ),
  Cpu: (p: P) => (
    <Svg {...p}>
      <rect x="6" y="6" width="12" height="12" rx="2" />
      <path d="M9.5 9.5h5v5h-5zM9 2v3M15 2v3M9 19v3M15 19v3M2 9h3M2 15h3M19 9h3M19 15h3" />
    </Svg>
  ),
  Eye: (p: P) => (
    <Svg {...p}>
      <path d="M2 12s3.6-7 10-7 10 7 10 7-3.6 7-10 7S2 12 2 12Z" />
      <circle cx="12" cy="12" r="3" />
    </Svg>
  ),
  EyeOff: (p: P) => (
    <Svg {...p}>
      <path d="M3 3l18 18M10.6 5.1A10 10 0 0 1 12 5c6.4 0 10 7 10 7a17 17 0 0 1-3.2 4.1M6.6 6.6A17 17 0 0 0 2 12s3.6 7 10 7a9.7 9.7 0 0 0 5.4-1.6" />
      <path d="M9.9 9.9a3 3 0 0 0 4.2 4.2" />
    </Svg>
  ),
  Book: (p: P) => (
    <Svg {...p}>
      <path d="M4 19.5V5a2 2 0 0 1 2-2h14v16H6.5A2.5 2.5 0 0 0 4 21.5v-2Z" />
      <path d="M8 7h8M8 11h6" />
    </Svg>
  ),
  Broadcast: (p: P) => (
    <Svg {...p}>
      <rect x="2" y="5" width="20" height="13" rx="2" />
      <path d="M8 21h8M10 9.5l4 2-4 2v-4Z" />
    </Svg>
  ),
  Sun: (p: P) => (
    <Svg {...p}>
      <circle cx="12" cy="12" r="4" />
      <path d="M12 2v2M12 20v2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M2 12h2M20 12h2M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4" />
    </Svg>
  ),
  Moon: (p: P) => (
    <Svg {...p}>
      <path d="M21 12.8A9 9 0 1 1 11.2 3a7 7 0 0 0 9.8 9.8Z" />
    </Svg>
  ),
  Menu: (p: P) => (
    <Svg {...p}>
      <path d="M4 6h16M4 12h16M4 18h16" />
    </Svg>
  ),
  X: (p: P) => (
    <Svg {...p}>
      <path d="M18 6 6 18M6 6l12 12" />
    </Svg>
  ),
  Search: (p: P) => (
    <Svg {...p}>
      <circle cx="11" cy="11" r="7" />
      <path d="m20 20-3.5-3.5" />
    </Svg>
  ),
  Check: (p: P) => (
    <Svg {...p}>
      <path d="M20 6 9 17l-5-5" />
    </Svg>
  ),
  Alert: (p: P) => (
    <Svg {...p}>
      <path d="M10.3 3.9 1.8 18a2 2 0 0 0 1.7 3h17a2 2 0 0 0 1.7-3L13.7 3.9a2 2 0 0 0-3.4 0Z" />
      <path d="M12 9v4M12 17h.01" />
    </Svg>
  ),
  Info: (p: P) => (
    <Svg {...p}>
      <circle cx="12" cy="12" r="9" />
      <path d="M12 16v-4M12 8h.01" />
    </Svg>
  ),
  Copy: (p: P) => (
    <Svg {...p}>
      <rect x="9" y="9" width="12" height="12" rx="2" />
      <path d="M5 15H4a1 1 0 0 1-1-1V4a1 1 0 0 1 1-1h10a1 1 0 0 1 1 1v1" />
    </Svg>
  ),
  Download: (p: P) => (
    <Svg {...p}>
      <path d="M12 3v12M7 10l5 5 5-5M5 21h14" />
    </Svg>
  ),
  Upload: (p: P) => (
    <Svg {...p}>
      <path d="M12 21V9M7 14l5-5 5 5M5 3h14" />
    </Svg>
  ),
  Trash: (p: P) => (
    <Svg {...p}>
      <path d="M3 6h18M8 6V4h8v2M6 6l1 14a2 2 0 0 0 2 2h6a2 2 0 0 0 2-2l1-14" />
    </Svg>
  ),
  Edit: (p: P) => (
    <Svg {...p}>
      <path d="M12 20h9M16.5 3.5a2.1 2.1 0 1 1 3 3L7 19l-4 1 1-4Z" />
    </Svg>
  ),
  Refresh: (p: P) => (
    <Svg {...p}>
      <path d="M21 12a9 9 0 0 1-15.5 6.2M3 12A9 9 0 0 1 18.5 5.8" />
      <path d="M21 3v5h-5M3 21v-5h5" />
    </Svg>
  ),
  Stop: (p: P) => (
    <Svg {...p}>
      <rect x="6" y="6" width="12" height="12" rx="2" />
    </Svg>
  ),
  Play: (p: P) => (
    <Svg {...p}>
      <path d="M7 4.5v15l12-7.5-12-7.5Z" fill="currentColor" stroke="none" />
    </Svg>
  ),
  Pause: (p: P) => (
    <Svg {...p}>
      <rect x="6" y="4.5" width="4" height="15" rx="1" fill="currentColor" stroke="none" />
      <rect x="14" y="4.5" width="4" height="15" rx="1" fill="currentColor" stroke="none" />
    </Svg>
  ),
  StepFwd: (p: P) => (
    <Svg {...p}>
      <path d="M5 5v14l10-7L5 5Z" fill="currentColor" stroke="none" />
      <path d="M19 5v14" strokeWidth={2.4} />
    </Svg>
  ),
  StepBack: (p: P) => (
    <Svg {...p}>
      <path d="M19 5v14L9 12l10-7Z" fill="currentColor" stroke="none" />
      <path d="M5 5v14" strokeWidth={2.4} />
    </Svg>
  ),
  Maximize: (p: P) => (
    <Svg {...p}>
      <path d="M8 3H5a2 2 0 0 0-2 2v3M21 8V5a2 2 0 0 0-2-2h-3M3 16v3a2 2 0 0 0 2 2h3M16 21h3a2 2 0 0 0 2-2v-3" />
    </Svg>
  ),
  External: (p: P) => (
    <Svg {...p}>
      <path d="M15 3h6v6M10 14 21 3M18 13v6a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h6" />
    </Svg>
  ),
  ChevronRight: (p: P) => (
    <Svg {...p}>
      <path d="m9 18 6-6-6-6" />
    </Svg>
  ),
  ChevronDown: (p: P) => (
    <Svg {...p}>
      <path d="m6 9 6 6 6-6" />
    </Svg>
  ),
  ArrowUp: (p: P) => (
    <Svg {...p}>
      <path d="M12 19V5M5 12l7-7 7 7" />
    </Svg>
  ),
  ArrowDown: (p: P) => (
    <Svg {...p}>
      <path d="M12 5v14M19 12l-7 7-7-7" />
    </Svg>
  ),
  Plus: (p: P) => (
    <Svg {...p}>
      <path d="M12 5v14M5 12h14" />
    </Svg>
  ),
  Key: (p: P) => (
    <Svg {...p}>
      <circle cx="7.5" cy="15.5" r="4.5" />
      <path d="m10.7 12.3 9.8-9.8M17 6l3 3M14.5 8.5l2 2" />
    </Svg>
  ),
  Zap: (p: P) => (
    <Svg {...p}>
      <path d="M13 2 3 14h9l-1 8 10-12h-9l1-8Z" />
    </Svg>
  ),
  Clock: (p: P) => (
    <Svg {...p}>
      <circle cx="12" cy="12" r="9" />
      <path d="M12 7v5l3 2" />
    </Svg>
  ),
  Table: (p: P) => (
    <Svg {...p}>
      <rect x="3" y="4" width="18" height="16" rx="2" />
      <path d="M3 10h18M3 15h18M9 10v10" />
    </Svg>
  ),
  Chart: (p: P) => (
    <Svg {...p}>
      <path d="M3 3v18h18" />
      <path d="m7 14 4-4 3 3 5-6" />
    </Svg>
  ),
  Fingerprint: (p: P) => (
    <Svg {...p}>
      <path d="M12 11v3a8 8 0 0 1-1.5 4.7M8.5 21a12 12 0 0 0 1.5-6V11a2 2 0 0 1 4 0v1" />
      <path d="M17.5 17.5A16 16 0 0 0 18 13v-2a6 6 0 0 0-10.8-3.6M5 16a13 13 0 0 0 1-5 6 6 0 0 1 .3-1.9" />
      <path d="M14 21a18 18 0 0 0 1.4-4" />
    </Svg>
  ),
  Git: (p: P) => (
    <Svg {...p}>
      <circle cx="12" cy="12" r="3.5" />
      <path d="M3 12h5.5M15.5 12H21" />
    </Svg>
  ),
  Flag: (p: P) => (
    <Svg {...p}>
      <path d="M4 22V4M4 4h13l-2 4 2 4H4" />
    </Svg>
  ),
  Sparkles: (p: P) => (
    <Svg {...p}>
      <path d="M12 3l1.8 4.7L18.5 9.5l-4.7 1.8L12 16l-1.8-4.7L5.5 9.5l4.7-1.8L12 3Z" />
      <path d="M19 15l.8 2.2L22 18l-2.2.8L19 21l-.8-2.2L16 18l2.2-.8L19 15Z" />
    </Svg>
  ),
  Filter: (p: P) => (
    <Svg {...p}>
      <path d="M3 5h18l-7 8v6l-4 2v-8L3 5Z" />
    </Svg>
  ),
  Layers: (p: P) => (
    <Svg {...p}>
      <path d="m12 3 9 5-9 5-9-5 9-5Z" />
      <path d="m3 13 9 5 9-5" />
    </Svg>
  ),
  Wifi: (p: P) => (
    <Svg {...p}>
      <path d="M2 8.8a15 15 0 0 1 20 0M5 12.5a10 10 0 0 1 14 0M8.5 16a5 5 0 0 1 7 0M12 20h.01" />
    </Svg>
  ),
  Grip: (p: P) => (
    <Svg {...p}>
      <path d="M9 6h.01M15 6h.01M9 12h.01M15 12h.01M9 18h.01M15 18h.01" strokeWidth={3} />
    </Svg>
  ),
  Keyboard: (p: P) => (
    <Svg {...p}>
      <rect x="2" y="6" width="20" height="12" rx="2" />
      <path d="M6 10h.01M10 10h.01M14 10h.01M18 10h.01M7 14h10" />
    </Svg>
  ),
  Shuffle: (p: P) => (
    <Svg {...p}>
      <path d="M16 3h5v5M4 20 21 3M21 16v5h-5M15 15l6 6M4 4l5 5" />
    </Svg>
  ),
  Lock: (p: P) => (
    <Svg {...p}>
      <rect x="4" y="11" width="16" height="10" rx="2" />
      <path d="M8 11V7a4 4 0 0 1 8 0v4" />
    </Svg>
  ),
  Dollar: (p: P) => (
    <Svg {...p}>
      <path d="M12 2v20M17 6.5C17 4.6 14.8 4 12 4S7 5 7 7.5 9.5 10.5 12 11s5 1.5 5 4-2.2 3.5-5 3.5-5-.8-5-2.5" />
    </Svg>
  ),
  Inbox: (p: P) => (
    <Svg {...p}>
      <path d="M22 12h-6l-2 3h-4l-2-3H2" />
      <path d="M5.5 5.1 2 12v6a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2v-6l-3.5-6.9A2 2 0 0 0 16.8 4H7.2a2 2 0 0 0-1.7 1.1Z" />
    </Svg>
  ),
  Target: (p: P) => (
    <Svg {...p}>
      <circle cx="12" cy="12" r="9" />
      <circle cx="12" cy="12" r="5" />
      <circle cx="12" cy="12" r="1" />
    </Svg>
  ),
};
