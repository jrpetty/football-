/**
 * Mock mode for The Game Jam: a finished demo run of the five-genre jam, built from real recordings made by
 * verification/game-jam/e2e.mts --record. The games are hand-written samples (two complete entries, several small
 * ones and deliberately broken ones), run through the real scorer and headless-browser playtest; only the judges
 * were scripted stand-ins. Screenshots are stored as small JPEGs.
 */
import jamJson from '../../../tests/creative/game-jam.json';
import recordedJson from './gameJamRecorded.json';
import type { ArtifactRef, CaseResult, CaseResultLite, PromptTest, ScoreDetail, SuiteView, TestDefinition } from '../types.ts';
import { mockArtifactUrls } from './registry.ts';
import type { RunSpec } from './fixtures.ts';
import { JAM_CRITERIA, JAM_WEIGHTS, JAM_GENRES, type GameJamDetail, type JamGenre } from '../../../src/scoring/game-jam-shared.ts';

interface Recorded {
  score: number;
  passed: boolean;
  summary: string;
  detail: ScoreDetail;
  html?: string;
  frames: Record<string, string>;
}
const RECORDED = recordedJson as unknown as Record<string, Recorded>;
const JAM = jamJson as unknown as PromptTest;

export const GAME_JAM_TESTS: TestDefinition[] = [JAM];

export const GAME_JAM_SUITE: SuiteView = {
  fingerprint: '9f3c2a7e41b0',
  testCount: 1,
  id: 'games',
  version: '1.0.0',
  name: 'The Game Jam',
  description: 'Five genres, one prompt each: Flappy Bird, an RTS, an action RPG, zombie survival with base building and a racer, played by a scripted player and rated by a cross-vendor judge panel that weights visual quality and creativity most.',
  repeats: 1,
  tests: [{ id: 'creative.game-jam' }],
};

export const GAME_JAM_RUN_SPEC: RunSpec = {
  id: 'run-2026-09-28-game-jam',
  name: 'The Game Jam · five genres',
  status: 'completed',
  contestantIds: ['meridian-atlas-4-ultra', 'kestrel-kite-reasoner', 'helios-quill-flash', 'random-baseline'],
  testIds: ['creative.game-jam'],
  repeats: 1,
  suiteId: 'games',
  createdAt: '2026-09-28T09:00:00Z',
  // Disclosed on the run page and the Presenter: each model's own maximum output, with a £30 whole-run limit.
  maxCostUsd: 39.9,
  limits: { maxCostUsd: 39.9, currency: { code: 'GBP', usdPerUnit: 1.33 } },
  notes: 'Demo run for The Game Jam: open "Game Jam" for the cabinet wall, any cell for a game card, or the Presenter for the genre slides.',
};

const genreOf = (caseId: string): JamGenre => ((JAM.cases.find((c) => c.id === caseId)?.expected as { genre?: JamGenre } | undefined)?.genre ?? 'flappy');

function emptyDetail(caseId: string, note: string): GameJamDetail {
  const genre = genreOf(caseId);
  const reqs = ((JAM.cases.find((c) => c.id === caseId)?.expected as { requirements?: string[] } | undefined)?.requirements ?? []).map((label, i) => ({ id: `R${i + 1}`, label, verdict: null, votes: {}, split: false }));
  return {
    genre,
    genreLabel: JAM_GENRES.find((g) => g.id === genre)?.label ?? genre,
    truncated: false,
    playtest: null,
    requirements: reqs,
    judges: [],
    criteria: Object.fromEntries(JAM_CRITERIA.map((c) => [c.id, null])) as GameJamDetail['criteria'],
    weights: { ...JAM_WEIGHTS },
    automatedScore: 0,
    judgeWeight: 0.75,
    judgeScore: null,
    spread: null,
    judgesSkipped: note,
  };
}

function artifactsOf(lite: CaseResultLite, rec: Recorded): ArtifactRef[] {
  const out: ArtifactRef[] = [];
  const add = (name: string, kind: ArtifactRef['kind'], url: string, bytes: number) => {
    const file = `${lite.contestantId}/${lite.testId}/${lite.caseId}-r${lite.repeat}/${name}`;
    mockArtifactUrls.set(`${lite.runId}/${file}`, url);
    out.push({ name, kind, file, bytes });
  };
  if (rec.html) add('artifact.html', 'html', `data:text/html;charset=utf-8,${encodeURIComponent(rec.html)}`, rec.html.length);
  const names = Object.keys(rec.frames).sort();
  const key = names.find((n) => n.includes('10000')) ?? names[names.length - 1];
  if (key) add('screenshot.png', 'png', rec.frames[key]!, rec.frames[key]!.length);
  for (const n of names) add(n, 'png', rec.frames[n]!, rec.frames[n]!.length);
  return out;
}

/** Replace the generic mock outcome of a jam result with the recorded one (or a plain timeout / no-game). */
export function decorateGameJam(t: TestDefinition, lite: CaseResultLite): CaseResultLite {
  if (t.id !== JAM.id) return lite;
  const rec = RECORDED[`${t.id}|${lite.contestantId}|${lite.caseId}`];
  if (rec) return { ...lite, status: 'ok', score: rec.score, passed: rec.passed, summary: rec.summary, scoreDetail: rec.detail, artifacts: artifactsOf(lite, rec), error: undefined, hasReplay: false };
  if (lite.contestantId === 'random-baseline') {
    return { ...lite, status: 'ok', score: 0, passed: false, summary: 'No HTML game found in the reply', scoreDetail: { formatOk: false, gameJam: emptyDetail(lite.caseId, 'Not asked: there is no game to grade.') }, artifacts: [], error: undefined, hasReplay: false };
  }
  // A slow model that ran out of the hour-long time limit on the bigger rounds.
  return { ...lite, status: 'timeout', score: 0, passed: false, summary: 'Timed out after 3600 s', scoreDetail: { gameJam: emptyDetail(lite.caseId, 'Not asked: no reply arrived within the time limit.') }, artifacts: [], error: 'Case exceeded the 3600 s time limit', hasReplay: false };
}

/** Full result for the inspector: keep the recorded detail. */
export function gameJamDetail(full: CaseResult, lite: CaseResultLite): CaseResult {
  if (lite.testId !== JAM.id) return full;
  return { ...full, status: lite.status, score: lite.score, passed: lite.passed, summary: lite.summary, scoreDetail: lite.scoreDetail, artifacts: lite.artifacts };
}
