/**
 * Mock-mode fixtures for the long-context, drawing and picture visuals.
 *
 * The program results (Needle in a Haystack, Chain of Whispers, Draw It Blind)
 * were recorded by running the REAL programs against scripted demo players
 * (verification/visual-pass/record-mock.mts), so every replay carries exactly
 * the fields a real run stores. The SVG illustrations and games are hand-made
 * demo artifacts; the games were probed in real headless Chromium.
 * Vision results get believable model answers (right, or a near miss) so the
 * answer-vs-truth overlays have something to draw.
 */
import needleStd from '../../../tests/long-context/needle-haystack.json';
import needleHard from '../../../tests/long-context/needle-haystack-hard.json';
import whispersStd from '../../../tests/long-context/chain-of-whispers.json';
import whispersHard from '../../../tests/long-context/chain-of-whispers-hard.json';
import drawHard from '../../../tests/visual/draw-it-blind-hard.json';
import svgIllustration from '../../../tests/visual/svg-illustration.json';
import games from '../../../tests/creative/one-shot-games.json';
import recordedJson from './visualPassRecorded.json';
import type { ArtifactRef, CaseResult, CaseResultLite, ProgramInfo, ProgramTest, PromptTest, ReplayData, ScoreDetail, TestDefinition } from '../types.ts';
import { mockArtifactUrls } from './registry.ts';
import type { RunSpec } from './fixtures.ts';

interface Recorded {
  score: number;
  passed: boolean | null;
  summary: string;
  detail: ScoreDetail;
  replay?: ReplayData;
  artifacts: Record<string, string>;
  screenshot?: string;
}
const RECORDED = recordedJson as unknown as Record<string, Recorded>;

/** Program tests use only their first seed in the demo (keeps the mock bundle small). */
function firstSeed(def: unknown): ProgramTest {
  const t = def as ProgramTest;
  return { ...t, seeds: t.seeds.slice(0, 1) };
}

export const VISUAL_PASS_TESTS: TestDefinition[] = [
  firstSeed(needleStd),
  firstSeed(needleHard),
  firstSeed(whispersStd),
  firstSeed(whispersHard),
  firstSeed(drawHard),
  svgIllustration as unknown as PromptTest,
  games as unknown as PromptTest,
];

export const VISUAL_PASS_PROGRAMS: ProgramInfo[] = [
  {
    id: 'needle-haystack',
    name: 'Needle in a Haystack',
    description: 'A seeded ~45,000-word chronicle with planted needles (facts, chains, sums and corrections), each shadowed by a near-miss decoy. One call; all questions at once.',
    scoring: 'Correct answers ÷ needles. Naming the decoy, the superseded value or hedging is wrong.',
  },
  {
    id: 'chain-of-whispers',
    name: 'Chain of Whispers',
    description: 'A story with checkable facts is summarised and re-expanded several times, each step in a fresh context that sees only the previous output.',
    scoring: 'Facts surviving in the final story ÷ facts, minus small penalties for going over or under the word limits.',
  },
];

export const VISUAL_PASS_RUN_SPEC: RunSpec = {
  id: 'run-2026-09-25-longctx-drawing',
  name: 'Long context & drawing · replays',
  status: 'completed',
  contestantIds: ['meridian-atlas-4-ultra', 'kestrel-kite-reasoner', 'helios-nova-3-pro', 'helios-quill-flash', 'random-baseline'],
  testIds: [...VISUAL_PASS_TESTS.map((t) => t.id), 'visual.draw-it-blind'],
  repeats: 1,
  createdAt: '2026-09-25T10:15:00Z',
  notes: 'Demo run for the long-context, drawing and game visuals: open any cell for the replay, or the Presenter for the “answer vs truth” slides.',
};

const recordedFor = (lite: Pick<CaseResultLite, 'testId' | 'contestantId' | 'caseId'>) => RECORDED[`${lite.testId}|${lite.contestantId}|${lite.caseId}`];

function dataUrl(mime: string, content: string): string {
  return content.startsWith('data:') ? content : `data:${mime};charset=utf-8,${encodeURIComponent(content)}`;
}

/** Artifact refs for a recorded result; their URLs are registered up front so the Presenter can show them. */
function artifactsOf(lite: CaseResultLite, rec: Recorded): ArtifactRef[] {
  const out: ArtifactRef[] = [];
  const add = (name: string, kind: ArtifactRef['kind'], content: string, mime: string) => {
    const file = `${lite.contestantId}/${lite.testId}/${lite.caseId}-r${lite.repeat}/${name}`;
    mockArtifactUrls.set(`${lite.runId}/${file}`, dataUrl(mime, content));
    out.push({ name, kind, file, bytes: content.length });
  };
  for (const [name, content] of Object.entries(rec.artifacts ?? {})) {
    if (name.endsWith('.svg')) add(name, 'svg', content, 'image/svg+xml');
    else if (name.endsWith('.html')) add(name, 'html', content, 'text/html');
  }
  if (rec.screenshot) add('screenshot.png', 'png', rec.screenshot, 'image/jpeg');
  return out;
}

// ───────────────────────────── Vision answers ─────────────────────────────

function hash(s: string): number {
  let h = 2166136261;
  for (let i = 0; i < s.length; i++) {
    h ^= s.charCodeAt(i);
    h = Math.imul(h, 16777619);
  }
  return (h >>> 0) / 4294967296;
}

/** The cell to the left (or, in column A, to the right): always inside the grid. */
const neighbour = (cell: string): string => {
  const col = cell.charCodeAt(0) - 65;
  const row = Number(cell.slice(1));
  return `${String.fromCharCode(65 + (col > 0 ? col - 1 : col + 1))}${row}`;
};

/** A believable wrong answer: one cell missed and one invented, a count off by one, the wrong line, a near-miss reading. */
function wrongVision(expected: unknown, seed: string): unknown {
  const u = hash(seed);
  if (typeof expected === 'number') return expected + (u < 0.5 ? -1 : 1) * (1 + Math.floor(u * 3) % 2);
  if (Array.isArray(expected)) {
    const [word, num] = String(expected[0]).split(' ');
    return `${word === 'SEP' ? 'OCT' : word} ${Math.max(1, Number(num) - 8)}`;
  }
  if (expected && typeof expected === 'object') {
    const e = expected as Record<string, unknown>;
    if (Array.isArray(e.cells)) {
      const cells = [...(e.cells as string[])];
      const drop = Math.floor(u * cells.length);
      const extra = neighbour(cells[(drop + 1) % cells.length]!);
      cells.splice(drop, 1);
      if (!cells.includes(extra) && u > 0.3) cells.push(extra);
      return { cells };
    }
    if (Array.isArray(e.differences)) {
      const diffs = (e.differences as Array<{ cell: string; change: string }>).map((d, i) => (i === 0 ? { ...d, change: d.change === 'colour' ? 'shape' : 'colour' } : d));
      return { differences: diffs.slice(0, diffs.length - 1) };
    }
    if (typeof e.line === 'number') return { ...e, line: e.line === 2 ? 3 : 2 };
  }
  return expected;
}

const show = (v: unknown) => (typeof v === 'string' ? v : Array.isArray(v) ? String(v[0]) : JSON.stringify(v));

function visionAnswer(t: TestDefinition, lite: CaseResultLite): CaseResultLite {
  if (t.kind !== 'prompt' || t.category !== 'vision' || lite.status !== 'ok' || lite.score === null) return lite;
  const c = t.cases.find((x) => x.id === lite.caseId);
  if (!c) return lite;
  // Every vision scorer is right-or-wrong (exact, number, or all-or-nothing JSON).
  const right = lite.score >= 0.5;
  const answer = right ? (Array.isArray(c.expected) ? c.expected[0] : c.expected) : wrongVision(c.expected, lite.key);
  return {
    ...lite,
    score: right ? 1 : 0,
    passed: right,
    summary: right ? 'Correct' : typeof c.expected === 'object' && !Array.isArray(c.expected) ? 'Wrong · all-or-nothing, so 0' : 'Incorrect',
    scoreDetail: { formatOk: true, extracted: show(answer), expected: c.expected, ...(c.notes ? { notes: c.notes } : {}) },
  };
}

// ───────────────────────────── Hooks used by fixtures.ts ─────────────────────────────

/** Replace the generic mock outcome with the recorded one (programs, SVG, games) or a believable vision answer. */
export function decorateVisualPass(t: TestDefinition, lite: CaseResultLite): CaseResultLite {
  const rec = recordedFor(lite);
  if (!rec) return visionAnswer(t, lite);
  return {
    ...lite,
    status: 'ok',
    score: rec.score,
    passed: rec.passed,
    summary: rec.summary,
    scoreDetail: rec.detail,
    artifacts: artifactsOf(lite, rec),
    error: undefined,
    hasReplay: !!rec.replay,
  };
}

/** Full result for the inspector: the recorded replay and detail (the generic mock transcript does not fit these). */
export function visualPassDetail(full: CaseResult, lite: CaseResultLite): CaseResult {
  const rec = recordedFor(full);
  if (!rec) {
    if (typeof lite.scoreDetail?.extracted === 'string' && lite.testId.startsWith('vision.')) return { ...full, scoreDetail: lite.scoreDetail };
    return full;
  }
  return { ...full, scoreDetail: rec.detail, replay: rec.replay, transcript: rec.replay ? [] : full.transcript };
}
