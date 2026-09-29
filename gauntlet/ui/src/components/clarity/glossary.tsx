/**
 * The viewer glossary: plain-English terms a viewer hears in a Gauntlet video, each with a tiny
 * drawn illustration (inline SVG on an 80×80 grid, theme tokens only, so it matches on Windows and in
 * both themes). Wording is deliberately simple and only claims what the app actually does.
 */
import type { ReactNode } from 'react';

export interface GlossaryTerm {
  id: string;
  term: string;
  /** One or two plain sentences. */
  say: string;
  /** A short example from the app. */
  eg?: string;
  art: ReactNode;
}

const A = 'var(--accent)';
const A2 = 'var(--accent-2)';
const T1 = 'var(--text-1)';
const T3 = 'var(--text-3)';
const S3 = 'var(--surface-3)';
const S4 = 'var(--surface-4)';
const G = 'var(--band-good)';
const P = 'var(--band-partial)';
const R = 'var(--band-poor)';

function Art({ children }: { children: ReactNode }) {
  return (
    <svg viewBox="0 0 80 80" role="img" aria-hidden="true" focusable="false">
      {children}
    </svg>
  );
}

export const GLOSSARY: GlossaryTerm[] = [
  {
    id: 'benchmark',
    term: 'Benchmark',
    say: 'A fixed set of tests that every AI takes, marked the same way, so they can be compared fairly.',
    eg: 'Like an exam that every student sits on the same day with the same paper.',
    art: (
      <Art>
        <rect x="8" y="44" width="18" height="26" rx="3" fill={S4} />
        <rect x="31" y="24" width="18" height="46" rx="3" fill={A} />
        <rect x="54" y="36" width="18" height="34" rx="3" fill={A2} />
        <text x="40" y="18" textAnchor="middle" fontSize="12" fill={T1}>
          1st
        </text>
      </Art>
    ),
  },
  {
    id: 'test',
    term: 'Test',
    say: 'One kind of challenge, such as maths problems, spotting a lie, or escaping a room. Tests are grouped into categories like Logic or Coding.',
    eg: '“River Crossing, Remixed” is one test in the Logic category.',
    art: (
      <Art>
        <path d="M30 10h20M34 10v20L18 62a6 6 0 0 0 5 8h34a6 6 0 0 0 5-8L46 30V10" fill="none" stroke={T1} strokeWidth="3" strokeLinejoin="round" />
        <path d="M24 52h32l5 10a4 4 0 0 1-4 5H23a4 4 0 0 1-4-5Z" fill={A} opacity="0.85" />
      </Art>
    ),
  },
  {
    id: 'case',
    term: 'Question (case)',
    say: 'One single puzzle inside a test. A test has several, and each is asked a few times (tries) because AI answers can vary.',
    eg: 'On screen: “Question 3 · Try 2”.',
    art: (
      <Art>
        <rect x="22" y="12" width="40" height="50" rx="5" fill={S4} />
        <rect x="16" y="18" width="40" height="50" rx="5" fill={S3} stroke={T3} strokeWidth="1.5" />
        <text x="36" y="53" textAnchor="middle" fontSize="30" fill={A}>
          ?
        </text>
      </Art>
    ),
  },
  {
    id: 'run',
    term: 'Run',
    say: 'One recorded session: the chosen models take the chosen tests, and every answer, score and cost is saved so it can be checked later.',
    art: (
      <Art>
        <circle cx="40" cy="34" r="22" fill="none" stroke={T1} strokeWidth="3" />
        <path d="M34 23v22l18-11Z" fill={A} />
        <rect x="10" y="64" width="60" height="6" rx="3" fill={S4} />
        <rect x="10" y="64" width="40" height="6" rx="3" fill={A} />
      </Art>
    ),
  },
  {
    id: 'score',
    term: 'Score out of 100',
    say: 'Every score is out of 100. 100 means every answer was right; 0 means none were. A test score is the average over all its questions and tries.',
    art: (
      <Art>
        <path d="M12 56a28 28 0 0 1 56 0" fill="none" stroke={S4} strokeWidth="9" strokeLinecap="round" />
        <path d="M12 56a28 28 0 0 1 46-21" fill="none" stroke={A} strokeWidth="9" strokeLinecap="round" />
        <text x="40" y="60" textAnchor="middle" fontSize="18" fill={T1}>
          78
        </text>
        <text x="40" y="75" textAnchor="middle" fontSize="10" fill={T3}>
          out of 100
        </text>
      </Art>
    ),
  },
  {
    id: 'bands',
    term: 'Pass, partial, fail',
    say: 'Colours tell you how well a model did: green is mostly right (80 or more), amber is partly right (40–79), red is mostly wrong (under 40).',
    art: (
      <Art>
        <rect x="8" y="14" width="64" height="15" rx="4" fill={G} />
        <rect x="8" y="33" width="64" height="15" rx="4" fill={P} />
        <rect x="8" y="52" width="64" height="15" rx="4" fill={R} />
        <text x="40" y="25.5" textAnchor="middle" fontSize="10" fill="var(--band-good-ink)">
          80–100
        </text>
        <text x="40" y="44.5" textAnchor="middle" fontSize="10" fill="var(--band-partial-ink)">
          40–79
        </text>
        <text x="40" y="63.5" textAnchor="middle" fontSize="10" fill="var(--band-poor-ink)">
          0–39
        </text>
      </Art>
    ),
  },
  {
    id: 'index',
    term: 'Gauntlet Index',
    say: 'The overall score out of 100: the average of a model’s category scores. Categories are averaged, so one category with lots of tests can’t dominate.',
    art: (
      <Art>
        <rect x="8" y="40" width="10" height="30" rx="2" fill={S4} />
        <rect x="22" y="30" width="10" height="40" rx="2" fill={S4} />
        <rect x="36" y="46" width="10" height="24" rx="2" fill={S4} />
        <path d="M52 50h6" stroke={T3} strokeWidth="3" strokeLinecap="round" />
        <rect x="62" y="36" width="12" height="34" rx="2" fill={A} />
        <path d="M13 38l14-10 14 16" fill="none" stroke={T3} strokeWidth="1.5" strokeDasharray="3 3" />
      </Art>
    ),
  },
  {
    id: 'baseline',
    term: 'Random baseline',
    say: 'A pretend contestant that answers completely at random. It shows what pure guessing scores — any real AI should beat it clearly.',
    art: (
      <Art>
        <rect x="14" y="20" width="30" height="30" rx="6" fill="none" stroke={T1} strokeWidth="3" transform="rotate(-12 29 35)" />
        <rect x="38" y="32" width="30" height="30" rx="6" fill={S3} stroke={T3} strokeWidth="3" strokeDasharray="5 4" transform="rotate(10 53 47)" />
        <circle cx="24" cy="30" r="3" fill={A} />
        <circle cx="34" cy="40" r="3" fill={A} />
        <circle cx="53" cy="47" r="3" fill={T3} />
      </Art>
    ),
  },
  {
    id: 'hand-copied',
    term: 'Copied by hand',
    say: 'Some models, including old ones that have been switched off for developers, can only be reached through a chat app. The same prompt was copied into that app and the reply pasted back, then marked exactly like every other answer.',
    eg: 'Claude 3 Opus (claude.ai) · copied by hand. Chat apps can add their own hidden instructions, so small gaps are a draw.',
    art: (
      <Art>
        <rect x="8" y="14" width="30" height="40" rx="5" fill={S3} stroke={T3} strokeWidth="2.5" />
        <path d="M14 24h18M14 31h18M14 38h12" stroke={T1} strokeWidth="2.5" strokeLinecap="round" />
        <rect x="44" y="26" width="30" height="40" rx="5" fill="none" stroke={T1} strokeWidth="2.5" />
        <path d="M50 36h18M50 43h18M50 50h12" stroke={A} strokeWidth="2.5" strokeLinecap="round" />
        <path d="M30 62c6 6 14 6 18-2" fill="none" stroke={A2} strokeWidth="3" strokeLinecap="round" />
        <path d="M46 56l2 5-5 1" fill="none" stroke={A2} strokeWidth="3" strokeLinecap="round" strokeLinejoin="round" />
      </Art>
    ),
  },
  {
    id: 'judge',
    term: 'Judge',
    say: 'For open-ended tasks with no single right answer, other AI models mark the work against a fixed checklist. A model never judges its own company’s work.',
    art: (
      <Art>
        <path d="M40 12v52M22 66h36" stroke={T1} strokeWidth="3" strokeLinecap="round" />
        <path d="M16 24h48" stroke={T1} strokeWidth="3" strokeLinecap="round" />
        <path d="M16 24l-8 18h16Z M64 24l-8 18h16Z" fill={A} opacity="0.85" />
        <circle cx="40" cy="12" r="4" fill={A} />
      </Art>
    ),
  },
  {
    id: 'tokens',
    term: 'Tokens',
    say: 'How AI measures text: small chunks of words. In English a token is roughly three-quarters of a word. Companies charge per token read and written.',
    eg: '“benchmarking” might be split into “bench” + “mark” + “ing”.',
    art: (
      <Art>
        <rect x="6" y="30" width="24" height="20" rx="4" fill={A} />
        <rect x="33" y="30" width="22" height="20" rx="4" fill={A2} />
        <rect x="58" y="30" width="16" height="20" rx="4" fill={S4} />
        <text x="18" y="43.5" textAnchor="middle" fontSize="9" textLength="20" lengthAdjust="spacingAndGlyphs" fill="var(--accent-ink)">
          bench
        </text>
        <text x="44" y="43.5" textAnchor="middle" fontSize="9" textLength="17" lengthAdjust="spacingAndGlyphs" fill="var(--accent-ink)">
          mark
        </text>
        <text x="66" y="43.5" textAnchor="middle" fontSize="9" textLength="11" lengthAdjust="spacingAndGlyphs" fill={T1}>
          ing
        </text>
      </Art>
    ),
  },
  {
    id: 'reasoning',
    term: 'Reasoning tokens',
    say: 'Some models “think” privately before they answer. That hidden thinking is counted in tokens and paid for, even though you don’t see it.',
    art: (
      <Art>
        <ellipse cx="44" cy="30" rx="26" ry="18" fill={S3} stroke={T3} strokeWidth="2" />
        <circle cx="20" cy="56" r="5" fill={S3} stroke={T3} strokeWidth="2" />
        <circle cx="12" cy="68" r="3" fill={S3} stroke={T3} strokeWidth="2" />
        <circle cx="34" cy="30" r="3.5" fill={A} />
        <circle cx="44" cy="30" r="3.5" fill={A} />
        <circle cx="54" cy="30" r="3.5" fill={A} />
      </Art>
    ),
  },
  {
    id: 'context',
    term: 'Context length',
    say: 'How much text a model can read at once. Long-context tests hide one small fact deep inside a very long document and ask for it.',
    art: (
      <Art>
        <rect x="18" y="6" width="44" height="68" rx="5" fill={S3} stroke={T3} strokeWidth="1.5" />
        {[16, 24, 32, 40, 48, 56, 64].map((y) => (
          <rect key={y} x="25" y={y} width={y === 48 ? 18 : 30} height="3" rx="1.5" fill={S4} />
        ))}
        <rect x="45" y="47" width="10" height="5" rx="2" fill={A} />
      </Art>
    ),
  },
  {
    id: 'cost',
    term: 'Cost per run',
    say: 'What the AI companies would charge for every answer in a run, worked out from the tokens used and their published prices.',
    art: (
      <Art>
        {[62, 54, 46, 38].map((y, i) => (
          <ellipse key={y} cx={i % 2 ? 42 : 38} cy={y} rx="20" ry="6" fill={i === 3 ? A : S4} stroke={T3} strokeWidth="1.5" />
        ))}
        <text x="40" y="24" textAnchor="middle" fontSize="16" fill={T1}>
          $
        </text>
      </Art>
    ),
  },
  {
    id: 'seed',
    term: 'Seed (repeatability)',
    say: 'A number that fixes the “random” parts of a game — the map, the weather, the cards — so every model faces exactly the same world, and anyone can replay it.',
    eg: 'On screen: “World #101” is the same island for every model.',
    art: (
      <Art>
        <circle cx="16" cy="40" r="8" fill={A} />
        <path d="M26 40h8" stroke={T3} strokeWidth="2.5" strokeLinecap="round" />
        {[18, 40, 62].map((y) => (
          <g key={y}>
            <rect x="40" y={y - 9} width="32" height="18" rx="4" fill={S3} stroke={T3} strokeWidth="1.5" />
            <path d={`M44 ${y + 4}l6-7 5 5 4-4 9 6`} fill="none" stroke={A} strokeWidth="2" strokeLinejoin="round" />
          </g>
        ))}
      </Art>
    ),
  },
  {
    id: 'range',
    term: 'Likely range (±)',
    say: 'Scores wobble a little by chance, so each one comes with a likely range. If two models’ ranges overlap, the race is too close to call.',
    art: (
      <Art>
        <rect x="8" y="24" width="64" height="8" rx="4" fill={S4} />
        <rect x="8" y="24" width="44" height="8" rx="4" fill={A} />
        <path d="M44 20v16M60 20v16M44 28h16" stroke={T1} strokeWidth="2.5" />
        <rect x="8" y="50" width="64" height="8" rx="4" fill={S4} />
        <rect x="8" y="50" width="40" height="8" rx="4" fill={A2} />
        <path d="M40 46v16M56 46v16M40 54h16" stroke={T1} strokeWidth="2.5" />
      </Art>
    ),
  },
];
