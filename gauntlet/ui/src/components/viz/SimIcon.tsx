/**
 * Small, filled, full-colour SVG icons for the simulation replays (items,
 * stats, camp, weather, locks). Drawn shapes instead of emoji so they look the
 * same on every platform. Decorative (aria-hidden): always pair with a label.
 */
import type { ReactNode } from 'react';

export type SimIconName =
  | 'wood'
  | 'stone'
  | 'fibre'
  | 'coconut'
  | 'rawfish'
  | 'cookedfish'
  | 'berry'
  | 'spear'
  | 'health'
  | 'food'
  | 'water'
  | 'energy'
  | 'warmth'
  | 'campfire'
  | 'shelter'
  | 'signal'
  | 'signal-lit'
  | 'ship'
  | 'bottle'
  | 'skull'
  | 'spring'
  | 'key'
  | 'lock'
  | 'unlock'
  | 'door'
  | 'clue'
  | 'item'
  | 'sun'
  | 'moon'
  | 'cloud'
  | 'rain'
  | 'storm'
  | 'hot'
  | 'flag'
  | 'cross'
  | 'check'
  | 'wave'
  | 'hurt';

const BERRY: Record<string, string> = { red: '#e0393e', blue: '#3b6fe0', purple: '#8e44c9', yellow: '#f2c230', white: '#e8e8e0', black: '#2a2630' };

export function berryColour(name: string | undefined): string {
  return BERRY[name ?? ''] ?? '#8e44c9';
}

function glyph(name: SimIconName, colour?: string): ReactNode {
  switch (name) {
    case 'wood':
      return (
        <>
          <rect x="3" y="7" width="17" height="5" rx="2.5" fill="#9a6433" />
          <rect x="4" y="13" width="17" height="5" rx="2.5" fill="#b8793f" />
          <circle cx="19.5" cy="9.5" r="2" fill="#e2b77f" />
          <circle cx="20.5" cy="15.5" r="2" fill="#e8c592" />
        </>
      );
    case 'stone':
      return <path d="M4 17l2-7 5-3 6 1 3 5-2 5H6z" fill="#8f98a6" stroke="#5c6572" strokeWidth="1.2" />;
    case 'fibre':
      return (
        <g stroke="#9bc35a" strokeWidth="2" strokeLinecap="round" fill="none">
          <path d="M6 20C6 13 8 8 11 4" />
          <path d="M12 20c0-6 1-10 5-15" />
          <path d="M9 20c1-5 0-8-4-11" />
          <path d="M16 20c0-4 2-7 4-8" />
        </g>
      );
    case 'coconut':
      return (
        <>
          <circle cx="12" cy="13" r="7.5" fill="#7a4a24" />
          <circle cx="9.5" cy="11" r="1.1" fill="#3d2410" />
          <circle cx="13" cy="10" r="1.1" fill="#3d2410" />
          <circle cx="11.5" cy="13.5" r="1.1" fill="#3d2410" />
        </>
      );
    case 'rawfish':
    case 'cookedfish': {
      const c = name === 'rawfish' ? '#8fb8d8' : '#d98a3d';
      return (
        <>
          <path d="M3 12c4-6 11-6 15 0-4 6-11 6-15 0z" fill={c} />
          <path d="M17 12l4-4v8z" fill={c} />
          <circle cx="7" cy="11" r="1.1" fill="#1b2430" />
          {name === 'cookedfish' && <path d="M9 9l2 6M12 9l2 6" stroke="#8a4a18" strokeWidth="1.2" />}
        </>
      );
    }
    case 'berry':
      return (
        <>
          <path d="M12 3c1 2 3 3 5 3" stroke="#3c8a3c" strokeWidth="1.6" fill="none" strokeLinecap="round" />
          <circle cx="9" cy="14" r="4.2" fill={colour ?? '#8e44c9'} stroke="rgba(0,0,0,.35)" strokeWidth=".8" />
          <circle cx="15" cy="13" r="4.2" fill={colour ?? '#8e44c9'} stroke="rgba(0,0,0,.35)" strokeWidth=".8" />
          <circle cx="12" cy="18" r="3.6" fill={colour ?? '#8e44c9'} stroke="rgba(0,0,0,.35)" strokeWidth=".8" />
        </>
      );
    case 'spear':
      return (
        <>
          <path d="M4 20L17 7" stroke="#a36a35" strokeWidth="2.2" strokeLinecap="round" />
          <path d="M15 5l5-2-2 5-2.5.5z" fill="#b8c2cf" stroke="#6b7482" strokeWidth=".8" />
        </>
      );
    case 'health':
      return <path d="M12 20s-7-4.5-7-10a4 4 0 0 1 7-2.6A4 4 0 0 1 19 10c0 5.5-7 10-7 10z" fill="#ef4f5f" />;
    case 'food':
      return (
        <>
          <path d="M12 7c-4-2-8 1-7 6 1 4 4 7 7 6 3 1 6-2 7-6 1-5-3-8-7-6z" fill="#e6553a" />
          <path d="M12 7c0-2 1-3 3-4" stroke="#4a8a3a" strokeWidth="1.6" fill="none" strokeLinecap="round" />
        </>
      );
    case 'water':
    case 'spring':
      return <path d="M12 3c3 5 6 8 6 11.5A6 6 0 0 1 6 14.5C6 11 9 8 12 3z" fill="#3aa8f0" />;
    case 'energy':
      return <path d="M13 2L5 13h6l-1 9 8-11h-6z" fill="#f5c22b" />;
    case 'warmth':
      return (
        <>
          <rect x="10" y="3" width="4" height="12" rx="2" fill="#f0a44a" />
          <circle cx="12" cy="17" r="4" fill="#f07a3a" />
        </>
      );
    case 'campfire':
    case 'signal-lit':
      return (
        <>
          {name === 'campfire' && <path d="M4 20l16-3M4 17l16 3" stroke="#8a5530" strokeWidth="2" strokeLinecap="round" />}
          {name === 'signal-lit' && <path d="M3 21h18" stroke="#6b3f1e" strokeWidth="3" />}
          <path d="M12 3c3 4 6 6 6 10a6 6 0 0 1-12 0c0-2 1-4 3-5 0 2 1 3 2 3 0-3-1-5 1-8z" fill="#f26b1d" />
          <path d="M12 10c1.5 2 3 3 3 5a3 3 0 0 1-6 0c0-1.5 1-2.5 3-5z" fill="#ffd23d" />
        </>
      );
    case 'shelter':
      return (
        <>
          <path d="M2.5 19L12 5l9.5 14z" fill="#b07a3a" stroke="#6e4618" strokeWidth="1" />
          <path d="M9.5 19l2.5-5 2.5 5z" fill="#3a2410" />
        </>
      );
    case 'signal':
      return (
        <g fill="#8a5530" stroke="#5a3418" strokeWidth=".8">
          <rect x="3" y="16" width="18" height="3.5" rx="1.5" />
          <rect x="5" y="12" width="14" height="3.5" rx="1.5" />
          <rect x="8" y="8" width="8" height="3.5" rx="1.5" />
        </g>
      );
    case 'ship':
      return (
        <>
          <path d="M11 3v11" stroke="#6b4a2a" strokeWidth="1.4" />
          <path d="M11.5 4l6 8h-6z" fill="#f1efe6" />
          <path d="M10.5 5l-5 7h5z" fill="#dcd8cc" />
          <path d="M3 15h18l-3 5H6z" fill="#7a3b22" />
        </>
      );
    case 'bottle':
      return (
        <>
          <path d="M10 3h4v4l2 3v10H8V10l2-3z" fill="#6fc3d8" stroke="#2f7f94" strokeWidth=".9" />
          <rect x="10" y="12" width="4" height="5" fill="#f3ead2" />
        </>
      );
    case 'skull':
      return (
        <>
          <path d="M12 3a7 7 0 0 0-7 7c0 2.5 1.2 4.3 3 5.3V19h8v-3.7c1.8-1 3-2.8 3-5.3a7 7 0 0 0-7-7z" fill="#f4f1ea" stroke="#3a3a3a" strokeWidth=".9" />
          <circle cx="9.3" cy="10.5" r="1.8" fill="#2a2a2a" />
          <circle cx="14.7" cy="10.5" r="1.8" fill="#2a2a2a" />
          <path d="M10 19v-2M12 19v-2M14 19v-2" stroke="#2a2a2a" strokeWidth="1" />
        </>
      );
    case 'key':
      return (
        <>
          <circle cx="7.5" cy="12" r="4" fill="none" stroke="#e4b43c" strokeWidth="2.4" />
          <path d="M11.5 12H21M17 12v3M20 12v2.5" stroke="#e4b43c" strokeWidth="2.4" strokeLinecap="round" />
        </>
      );
    case 'lock':
    case 'unlock':
      return (
        <>
          <path d={name === 'lock' ? 'M8 11V8a4 4 0 0 1 8 0v3' : 'M8 11V8a4 4 0 0 1 7.5-2'} fill="none" stroke={name === 'lock' ? '#c7cfdb' : '#7ee29a'} strokeWidth="2.2" />
          <rect x="5" y="11" width="14" height="10" rx="2" fill={name === 'lock' ? '#d9534f' : '#27b35a'} />
          <circle cx="12" cy="16" r="1.6" fill="rgba(0,0,0,.45)" />
        </>
      );
    case 'door':
      return (
        <>
          <rect x="6" y="3" width="12" height="18" rx="1.5" fill="#9a6433" stroke="#5e3a18" strokeWidth="1" />
          <circle cx="15" cy="12.5" r="1.1" fill="#f0c64a" />
        </>
      );
    case 'clue':
      return (
        <>
          <path d="M6 3h10l3 3v15H6z" fill="#f2e8cc" stroke="#a08a55" strokeWidth=".9" />
          <path d="M9 9h7M9 12h7M9 15h5" stroke="#8a7648" strokeWidth="1.2" />
        </>
      );
    case 'item':
      return <rect x="5" y="7" width="14" height="11" rx="2" fill="#8d7bd8" stroke="#5a49a8" strokeWidth=".9" />;
    case 'sun':
      return (
        <>
          <circle cx="12" cy="12" r="5" fill="#f7c531" />
          <g stroke="#f7c531" strokeWidth="1.8" strokeLinecap="round">
            <path d="M12 2v2.5M12 19.5V22M2 12h2.5M19.5 12H22M4.9 4.9l1.8 1.8M17.3 17.3l1.8 1.8M4.9 19.1l1.8-1.8M17.3 6.7l1.8-1.8" />
          </g>
        </>
      );
    case 'hot':
      return (
        <>
          <circle cx="12" cy="12" r="6" fill="#ff8a1f" />
          <g stroke="#ff5a1f" strokeWidth="2" strokeLinecap="round">
            <path d="M12 1.5v3M12 19.5v3M1.5 12h3M19.5 12h3M4.2 4.2l2 2M17.8 17.8l2 2M4.2 19.8l2-2M17.8 6.2l2-2" />
          </g>
        </>
      );
    case 'moon':
      return <path d="M15 3a8 8 0 1 0 6 12A7 7 0 0 1 15 3z" fill="#e8e3c8" />;
    case 'cloud':
      return <path d="M7 18h10a4 4 0 0 0 .6-8A5.5 5.5 0 0 0 7 9.5 4.3 4.3 0 0 0 7 18z" fill="#b9c3cf" />;
    case 'rain':
    case 'storm':
      return (
        <>
          <path d="M7 14h10a4 4 0 0 0 .6-8A5.5 5.5 0 0 0 7 5.5 4.3 4.3 0 0 0 7 14z" fill={name === 'storm' ? '#6b7584' : '#9aa6b5'} />
          {name === 'rain' ? (
            <path d="M8 16l-1 3M12 16l-1 3M16 16l-1 3" stroke="#4fa3f0" strokeWidth="1.8" strokeLinecap="round" />
          ) : (
            <path d="M12.5 14l-3 4.5h3l-2 4" stroke="#f7d23b" strokeWidth="1.8" fill="none" strokeLinejoin="round" strokeLinecap="round" />
          )}
        </>
      );
    case 'flag':
      return (
        <>
          <path d="M6 3v18" stroke="#c7cfdb" strokeWidth="1.8" />
          <path d="M7 4h11l-3 4 3 4H7z" fill="#27b35a" />
        </>
      );
    case 'cross':
      return (
        <>
          <circle cx="12" cy="12" r="9" fill="#d03b3b" />
          <path d="M8.5 8.5l7 7M15.5 8.5l-7 7" stroke="#fff" strokeWidth="2.2" strokeLinecap="round" />
        </>
      );
    case 'check':
      return (
        <>
          <circle cx="12" cy="12" r="9" fill="#179c3c" />
          <path d="M7.5 12.5l3 3 6-6.5" stroke="#fff" strokeWidth="2.2" fill="none" strokeLinecap="round" strokeLinejoin="round" />
        </>
      );
    case 'wave':
      return <path d="M2 14c2.5-3 5-3 7.5 0s5 3 7.5 0 3.5-2 5-1" stroke="#3a8ee0" strokeWidth="2.2" fill="none" strokeLinecap="round" />;
    case 'hurt':
      return <path d="M12 20s-7-4.5-7-10a4 4 0 0 1 7-2.6A4 4 0 0 1 19 10c0 5.5-7 10-7 10z M12 7l-2 5h3l-2 5" fill="#ef4f5f" stroke="#fff" strokeWidth="1.2" />;
  }
}

export function SimIcon({ name, size = 22, colour, title, className }: { name: SimIconName; size?: number | string; colour?: string; title?: string; className?: string }) {
  return (
    <svg viewBox="0 0 24 24" width={size} height={size} className={className ?? 'sim-icon'} aria-hidden={title ? undefined : true} role={title ? 'img' : undefined} focusable="false">
      {title && <title>{title}</title>}
      {glyph(name, colour)}
    </svg>
  );
}

/** Icon for an island inventory item key ("wood", "redberry", …). */
export function itemIcon(item: string): { name: SimIconName; colour?: string } {
  if (item.endsWith('berry')) return { name: 'berry', colour: berryColour(item.slice(0, -5)) };
  if (['wood', 'stone', 'fibre', 'coconut', 'rawfish', 'cookedfish'].includes(item)) return { name: item as SimIconName };
  return { name: 'item' };
}

export function itemName(item: string): string {
  if (item.endsWith('berry')) return `${item.slice(0, -5)} berries`;
  return ({ rawfish: 'raw fish', cookedfish: 'cooked fish' } as Record<string, string>)[item] ?? item;
}
