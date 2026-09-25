/**
 * A drawn SVG portrait for a Liar's Table suspect, generated deterministically
 * from the name (skin, hair, outfit, an accessory), so the same guest always
 * looks the same in every replay and on every platform.
 */
import { useId } from 'react';

const SKIN = ['#f4d2b6', '#e8b996', '#d49a6a', '#b77a4e', '#8d5a36', '#f0c9a8'];
const HAIR = ['#2b1d14', '#5a3820', '#9a6a3a', '#d8b36a', '#b0b4ba', '#7a2a1a', '#1b1b22'];
const COAT = ['#2f4a7a', '#7a2f3f', '#2f6a4f', '#5a3f7a', '#7a5a2f', '#3a3f4a', '#8a3a6a', '#2f6a78'];
const BG = ['#26344d', '#3a2a3f', '#233f3a', '#40362a', '#2a3346', '#3f2a2a'];

function hash(s: string): number {
  let h = 2166136261;
  for (let i = 0; i < s.length; i++) h = Math.imul(h ^ s.charCodeAt(i), 16777619);
  return h >>> 0;
}

export function SuspectPortrait({ name, size = 64, className }: { name: string; size?: number | string; className?: string }) {
  const id = useId().replace(/:/g, '');
  const h = hash(name);
  const pick = <T,>(arr: readonly T[], shift: number): T => arr[(h >>> shift) % arr.length]!;
  const skin = pick(SKIN, 0);
  const hair = pick(HAIR, 3);
  const coat = pick(COAT, 6);
  const bg = pick(BG, 9);
  const style = (h >>> 12) % 4;
  const extra = (h >>> 15) % 6;
  return (
    <svg className={className ?? 'suspect-portrait'} viewBox="0 0 100 100" width={size} height={size} role="img" aria-label={`Portrait of ${name}`}>
      <defs>
        <clipPath id={`c${id}`}>
          <circle cx="50" cy="50" r="48" />
        </clipPath>
      </defs>
      <g clipPath={`url(#c${id})`}>
        <rect width="100" height="100" fill={bg} />
        {style === 2 && <path d="M22 60 C18 30 30 14 50 14 C70 14 82 30 78 60 L78 86 L22 86Z" fill={hair} />}
        <path d="M14 104 C16 80 30 72 50 72 C70 72 84 80 86 104Z" fill={coat} />
        <path d="M42 72 L50 86 L58 72Z" fill="#f2f0ea" />
        <rect x="43" y="60" width="14" height="14" rx="4" fill={skin} />
        <ellipse cx="50" cy="46" rx="19" ry="22" fill={skin} />
        {style === 0 && <path d="M30 42 C30 24 40 18 50 18 C62 18 71 25 70 42 C66 32 58 29 50 29 C42 29 35 32 30 42Z" fill={hair} />}
        {style === 1 && <path d="M30 44 C28 22 42 16 52 17 C64 18 72 26 70 44 C68 36 64 33 58 31 C50 36 40 36 32 34Z" fill={hair} />}
        {style === 2 && <path d="M31 42 C31 26 40 20 50 20 C60 20 69 26 69 42 C63 32 56 30 50 30 C44 30 37 32 31 42Z" fill={hair} />}
        {style === 3 && (
          <>
            <path d="M31 40 C31 25 40 20 50 20 C60 20 69 25 69 40 C64 31 57 29 50 29 C43 29 36 31 31 40Z" fill={hair} />
            <circle cx="50" cy="15" r="8" fill={hair} />
          </>
        )}
        <circle cx="43" cy="47" r="2.2" fill="#1d1a18" />
        <circle cx="57" cy="47" r="2.2" fill="#1d1a18" />
        <path d="M44 58 Q50 61 56 58" stroke="#6a3a2a" strokeWidth="1.8" fill="none" strokeLinecap="round" />
        {extra === 1 && (
          <g stroke="#1d1d22" strokeWidth="1.8" fill="rgba(255,255,255,.15)">
            <circle cx="43" cy="47" r="5.5" />
            <circle cx="57" cy="47" r="5.5" />
            <path d="M48.5 47 H51.5" />
          </g>
        )}
        {extra === 2 && <path d="M41 55 Q46 51 50 54 Q54 51 59 55 Q54 57 50 55.5 Q46 57 41 55Z" fill={hair} />}
        {extra === 3 && (
          <>
            <rect x="28" y="22" width="44" height="6" rx="2" fill="#1f1f24" />
            <rect x="36" y="4" width="28" height="20" rx="3" fill="#1f1f24" />
            <rect x="36" y="18" width="28" height="3" fill="#8a2f2f" />
          </>
        )}
        {extra === 4 && (
          <g fill="#f4f1e6" stroke="#b9b3a0" strokeWidth=".6">
            {[36, 41, 46, 50, 54, 59, 64].map((x, i) => (
              <circle key={x} cx={x} cy={78 + Math.abs(i - 3) * -1.2} r="2.4" />
            ))}
          </g>
        )}
        {extra === 5 && (
          <g>
            <circle cx="57" cy="47" r="6" fill="rgba(255,255,255,.18)" stroke="#caa84a" strokeWidth="1.6" />
            <path d="M63 48 Q66 60 62 70" stroke="#caa84a" strokeWidth="1" fill="none" />
          </g>
        )}
      </g>
    </svg>
  );
}
