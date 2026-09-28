/**
 * The Game Jam (creative.game-jam): the shape of `scoreDetail.gameJam` plus the fixed weights and genre names,
 * shared by the scorer and the UI. Pure (no Node imports), so the dashboard can bundle it. Every number the UI
 * shows comes from the recorded detail: the UI never recomputes a score.
 */

import type { ScorerSpec } from '../core/types.ts';

export type JamGenre = 'flappy' | 'rts' | 'rpg' | 'zombie' | 'racing';

/** One playtest screenshot (the PNG is the artifact `name`). */
export interface PlaytestFrameInfo {
  /** Game time in ms after the page loaded. */
  t: number;
  /** Artifact file name, e.g. "playtest-03000ms.png". */
  name: string;
  /** Viewer caption, e.g. "3 s". */
  label: string;
  /** Share of pixels that differ from the frame's most common colour (0 = one flat colour). */
  detail: number;
  blank: boolean;
  /** Share of pixels changed since the previous screenshot (null for the first). */
  changed: number | null;
  /** Share of pixels that differ from the untouched copy at the same moment (null when not measured). */
  vsIdle: number | null;
}

export interface PlaytestCheck {
  passed: boolean;
  detail?: string;
}

export interface PlaytestSummary {
  genre: JamGenre;
  /** What the scripted player did, in plain words. */
  inputs: string;
  viewport: { width: number; height: number };
  seconds: number;
  frames: PlaytestFrameInfo[];
  /** Set when the page stopped responding (an endless loop). */
  hung: { atMs: number; phase: string } | null;
  inputsSent: number;
  /** The motion strip (six frames 0.1 s apart, one contact-sheet image) and the mean share of pixels changing per tenth of a second. Absent on protocol 1 results. */
  motion?: { name: string; times: number[]; changed: number } | null;
  drawsPicture: PlaytestCheck;
  keepsMoving: PlaytestCheck;
  reacts: PlaytestCheck;
  stillRunning: PlaytestCheck;
  noPlayErrors: PlaytestCheck;
}

export type RequirementVerdict = 'pass' | 'partial' | 'fail';

/** One numbered requirement of the brief with every judge's verdict and the panel's consensus. */
export interface JamRequirement {
  /** "R1", "R2", … (matches the numbered list in the prompt). */
  id: string;
  label: string;
  /** Consensus: mean of pass = 1, partial = 0.5, fail = 0 → ≥ 0.75 pass, ≥ 0.25 partial, else fail. Null when no judge graded it. */
  verdict: RequirementVerdict | null;
  /** judge contestant id → verdict and short reason. */
  votes: Record<string, { verdict: RequirementVerdict; reason?: string }>;
  /** Judges gave different verdicts. */
  split: boolean;
}

/** Judge criteria. Protocol 1 results used 'polish' (visual & audio polish) where protocol 2 has 'visual'. */
export type JamCriterion = 'fidelity' | 'plays' | 'feel' | 'creativity' | 'visual' | 'ambition' | 'polish';

export interface JamJudgeCard {
  judgeId: string;
  /** Criterion scores 0..1 (fidelity = share of the checklist met; the others = the judge's 0-10 ÷ 10). */
  criteria: Partial<Record<JamCriterion, number>>;
  /** Weighted total 0..1. */
  total: number;
  /** The judge's one-sentence verdict, quoted. */
  verdict?: string;
  /** Whether this judge was shown the playtest screenshots. */
  sawImages: boolean;
}

export interface GameJamDetail {
  genre: JamGenre;
  /** e.g. "Flappy Bird remake". */
  genreLabel: string;
  /** The reply hit the output-token limit, so the file is cut off. */
  truncated: boolean;
  /** Who set the output limit a truncated reply hit: absent = the model's own maximum (or the test's limit). */
  truncatedBy?: 'spend-limit' | 'per-answer';
  playtest: PlaytestSummary | null;
  requirements: JamRequirement[];
  judges: JamJudgeCard[];
  /** Panel mean per criterion (0..1), null when no judge graded. The visual criterion counts judges who saw the screenshots VISION_JUDGE_VISUAL_WEIGHT times. */
  criteria: Partial<Record<JamCriterion, number | null>>;
  weights: Partial<Record<JamCriterion, number>>;
  /** Share of the automatic checks passed (0..1). */
  automatedScore: number;
  /** Weight of the judges in the final score (the rest is the automatic checks). */
  judgeWeight: number;
  judgeScore: number | null;
  /** Largest difference between two judges' totals (0..1). */
  spread: number | null;
  /** A cap that lowered the score, in plain words (e.g. "froze during the playtest: capped at 30"). */
  cap?: string;
  /** Plain words: why the judges were not asked, when they were not. */
  judgesSkipped?: string;
}

/**
 * Weight of each criterion in a judge's total (protocol 2). Visual quality and creativity weigh most, as the owner
 * asked. The weights used for a result are recorded with it (GameJamDetail.weights), so older results keep theirs.
 */
export const JAM_WEIGHTS: Partial<Record<JamCriterion, number>> = {
  visual: 0.3,
  creativity: 0.25,
  fidelity: 0.2,
  plays: 0.1,
  feel: 0.1,
  ambition: 0.05,
};

/** The criteria a weight table uses, in display order. */
export function criteriaOf(weights: Partial<Record<JamCriterion, number>>): JamCriterion[] {
  return JAM_CRITERIA.map((c) => c.id).filter((id) => (weights[id] ?? 0) > 0);
}

/** Criteria in display order; `key` is the line a judge writes (fidelity comes from the checklist). */
export const JAM_CRITERIA: Array<{ id: JamCriterion; label: string; short: string; key: string | null }> = [
  { id: 'visual', label: 'Visual quality & art direction', short: 'Visuals', key: 'VISUALS' },
  { id: 'creativity', label: 'Creativity & originality', short: 'Creativity', key: 'CREATIVITY' },
  { id: 'fidelity', label: 'Requirement checklist', short: 'Checklist', key: null },
  { id: 'plays', label: 'Does it actually play', short: 'Plays', key: 'PLAYS' },
  { id: 'feel', label: 'Game feel & juice (incl. audio)', short: 'Feel', key: 'FEEL' },
  { id: 'ambition', label: 'Ambition & depth', short: 'Ambition', key: 'AMBITION' },
  { id: 'polish', label: 'Visual & audio polish', short: 'Polish', key: 'POLISH' },
];

/** How much a judge's visual score counts when it saw the screenshots, relative to a text-only judge. */
export const VISION_JUDGE_VISUAL_WEIGHT = 3;

/** Viewer-facing genre names, in round order. */
export const JAM_GENRES: Array<{ id: JamGenre; round: number; label: string; short: string; marquee: string }> = [
  { id: 'flappy', round: 1, label: 'Flappy Bird remake', short: 'Flappy', marquee: 'FLAP' },
  { id: 'rts', round: 2, label: 'Real-time strategy', short: 'RTS', marquee: 'COMMAND' },
  { id: 'rpg', round: 3, label: 'Top-down action RPG', short: 'RPG', marquee: 'QUEST' },
  { id: 'zombie', round: 4, label: 'Zombie survival', short: 'Zombies', marquee: 'SURVIVE' },
  { id: 'racing', round: 5, label: 'Racing', short: 'Racing', marquee: 'RACE' },
];

export function jamGenreInfo(g: string): (typeof JAM_GENRES)[number] | undefined {
  return JAM_GENRES.find((x) => x.id === g);
}

/** The recorded Game Jam detail of a result, if any (older or other results have none). */
export function gameJamOf(detail: { [key: string]: unknown } | undefined | null): GameJamDetail | null {
  const g = detail?.gameJam as GameJamDetail | undefined;
  return g && typeof g === 'object' && typeof g.genre === 'string' && Array.isArray(g.requirements) ? g : null;
}

/** Plain count used in captions: "12 of 14 requirements met, 1 partly". */
export function checklistLine(reqs: JamRequirement[]): string {
  const graded = reqs.filter((r) => r.verdict !== null);
  const met = graded.filter((r) => r.verdict === 'pass').length;
  const part = graded.filter((r) => r.verdict === 'partial').length;
  return graded.length ? `${met} of ${reqs.length} requirements met${part ? `, ${part} partly` : ''}` : 'requirements not graded';
}

export function isJamGenre(g: unknown): g is JamGenre {
  return typeof g === 'string' && JAM_GENRES.some((x) => x.id === g);
}

/** Version of the playtest script, weights and judge format (src/scoring/game-jam.ts); the test JSON repeats it. */
export const GAME_JAM_PROTOCOL = 2;

export interface JamCaseSpec {
  genre: JamGenre;
  /** Short labels of the numbered requirements, in order (R1 = requirement 1 of the brief). */
  requirements: string[];
}

/** The case's `expected`: genre and requirement labels (never sent to the model). */
export function jamCaseSpec(expected: unknown): JamCaseSpec {
  const e = (expected ?? {}) as Partial<JamCaseSpec>;
  if (!isJamGenre(e.genre)) throw new Error('Game Jam case needs expected.genre (flappy, rts, rpg, zombie or racing)');
  if (!Array.isArray(e.requirements) || !e.requirements.length || !e.requirements.every((r) => typeof r === 'string' && r.trim())) {
    throw new Error('Game Jam case needs expected.requirements: one short label per numbered requirement');
  }
  return { genre: e.genre, requirements: e.requirements };
}

/** Validation problems of a Game Jam scorer / case (used by the registry validator and the unit tests). */
export function validateGameJamCase(scorer: ScorerSpec, expected: unknown, prompt: string | undefined, where: string): string[] {
  const errors: string[] = [];
  if (scorer.type !== 'artifact' || !scorer.playtest) return errors;
  if (scorer.format !== 'html') errors.push(`${where}: playtests need format "html"`);
  if (scorer.playtest.protocol !== GAME_JAM_PROTOCOL) errors.push(`${where}: playtest protocol ${scorer.playtest.protocol} does not match the code (${GAME_JAM_PROTOCOL}); bump the test version and the protocol together`);
  let spec: JamCaseSpec | null = null;
  try {
    spec = jamCaseSpec(expected);
  } catch (e) {
    errors.push(`${where}: ${(e as Error).message}`);
  }
  if (spec && prompt) {
    const numbered = numberedRequirements(prompt);
    if (numbered !== spec.requirements.length) errors.push(`${where}: the prompt has ${numbered} numbered requirements but expected.requirements has ${spec.requirements.length} labels`);
  }
  return errors;
}

/** Count of "1." … "N." requirement lines in the brief's "Numbered requirements" section. */
export function numberedRequirements(prompt: string): number {
  const section = prompt.split(/^##\s+/m).find((s) => /^numbered requirements/i.test(s));
  if (!section) return 0;
  let n = 0;
  for (const line of section.split('\n')) {
    const m = line.match(/^(\d+)\.\s/);
    if (m && Number(m[1]) === n + 1) n++;
  }
  return n;
}
