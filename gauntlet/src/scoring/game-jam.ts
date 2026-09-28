import type { ChatImage, ScoreBreakdownItem, ScoreDetail, ScorerSpec } from '../core/types.ts';
import { extractArtifact, type JudgeCall, type ScoringInput, type ScoringOutcome } from './index.ts';
import { JUDGE_SYSTEM, fill } from './judge-prompts.ts';
import { GENRES, runPlaytest, type PlaytestResult } from './playtest.ts';
import { GAME_JAM_PROTOCOL, JAM_CRITERIA, JAM_WEIGHTS, VISION_JUDGE_VISUAL_WEIGHT, criteriaOf, jamCaseSpec, numberedRequirements, validateGameJamCase, type GameJamDetail, type JamCaseSpec, type JamCriterion, type JamGenre, type JamJudgeCard, type JamRequirement, type RequirementVerdict } from './game-jam-shared.ts';
export { GAME_JAM_PROTOCOL, JAM_CRITERIA, JAM_WEIGHTS, jamCaseSpec, numberedRequirements, validateGameJamCase };

/**
 * The Game Jam scorer (creative.game-jam): an `artifact` scorer with `playtest` set.
 *
 *  1. The HTML game is extracted from the reply. A reply cut off by the output-token limit is still extracted
 *     (the unfinished file), tested and shown, but the judges are not asked: an unfinished file cannot be a
 *     finished game, and the summary says plainly that the model ran out of output space.
 *  2. Automatic checks: the usual artifact checks, computed from one genre playtest (src/scoring/playtest.ts),
 *     plus four playtest checks (draws a picture, keeps moving, still running at 15 s, no errors while playing).
 *  3. A judge panel (never the contestant's own vendor) reads the brief, the whole file, the playtest results and,
 *     when the judge accepts images, the five playtest screenshots. Each judge marks every numbered requirement
 *     PASS / PARTIAL / FAIL and scores five criteria 0-10 against anchored descriptions in the test's rubric.
 *  4. Score = (1 − judgeWeight) × checks + judgeWeight × judges. Judge weights (protocol 2): visual quality & art
 *     direction 30%, creativity 25%, requirement checklist 20%, plays 10%, feel & juice 10%, ambition 5%. On
 *     VISUALS a judge who saw the screenshots counts VISION_JUDGE_VISUAL_WEIGHT (3) times a text-only judge. A game that froze or stayed blank, or that loads outside files, is capped at 30.
 *
 * GAME_JAM_PROTOCOL versions the playtest script, the weights and the judge format below; the test JSON repeats it
 * (scorer.playtest.protocol), so changing any of them forces a test version bump (and a new test hash).
 */

/** Highest total a broken game (froze, blank, or loads outside files) can reach. */
export const BROKEN_CAP = 0.3;
/** Characters of game code a judge reads (≈ 300k tokens): a model's full 128k-token reply always fits; only a runaway 384k-token file is trimmed in the middle. */
const MAX_JUDGE_CODE_CHARS = 1_100_000;

// ───────────────────────────── Extraction ─────────────────────────────

/**
 * The HTML file in a reply. A reply cut off by the output limit has an unclosed ```html fence: take everything
 * after it, so the unfinished file can still be shown and tested.
 */
export function extractJamHtml(response: string, truncated: boolean): string | null {
  const whole = extractArtifact(response, 'html');
  if (whole) return whole;
  if (!truncated) return null;
  const fence = response.match(/```(?:html|htm)?[^\n]*\n([\s\S]*)$/i);
  const body = fence ? fence[1]! : (response.match(/<!doctype html[\s\S]*|<html[\s\S]*/i)?.[0] ?? '');
  return /<(html|body|canvas|script|div|!doctype)/i.test(body) ? body.trim() : null;
}

// ───────────────────────────── Judge prompt ─────────────────────────────

export const GAME_JAM_JUDGE_TEMPLATE = `## The game-jam brief given to the model
<task>
{{task}}
</task>

## Automated playtest by the harness
{{playtest}}

## Requirement checklist to grade
{{requirements}}

## Rubric
{{rubric}}

## The HTML file the model produced ({{bytes}})
<artifact>
{{artifact}}
</artifact>

Grade the game. Think it through first if you need to, then end your reply with exactly these lines and nothing after them:
{{format}}
VISUALS: <integer 0-10>
CREATIVITY: <integer 0-10>
PLAYS: <integer 0-10>
FEEL: <integer 0-10>
AMBITION: <integer 0-10>
VERDICT: <one plain-English sentence for a viewer, at most 20 words>`;

const pct = (x: number) => `${Math.round(x * 100)}%`;

function playtestText(r: PlaytestResult | null, genre: JamGenre, items: ScoreBreakdownItem[], withImages: boolean): string {
  const lines: string[] = [];
  if (!r) {
    lines.push('No headless browser was available, so the game was not played automatically. Judge from the code alone.');
  } else {
    const s = r.summary;
    lines.push(
      `The harness opened the file at ${s.viewport.width}×${s.viewport.height} on a fixed clock and played it for ${s.seconds} seconds of game time with a scripted, unskilled player: ${GENRES[genre].inputs}. A second copy ran with no input for comparison.`,
      'Losing quickly to a scripted player is expected and is not a flaw. The playtest proves the game runs and reacts; how good it is comes from you.',
    );
    lines.push(
      withImages
        ? `Full-HD screenshots attached in order: ${s.frames.map((f) => f.label).join(', ')}.${s.motion ? ` The last image is a motion strip: six frames 0.1 s apart from ${(s.motion.times[0]! / 1000).toFixed(0)} s, left to right then top to bottom, so you can judge animation.` : ''} Grade VISUALS mainly from these pictures.`
        : `Screenshots were taken at ${s.frames.map((f) => f.label).join(', ')}, but you receive text only: grade VISUALS from what the drawing code would produce, and be conservative. Your visual score counts less than a judge who saw the pictures.`,
    );
    if (s.motion) lines.push(`Motion strip: on average ${pct(s.motion.changed)} of the screen changes every tenth of a second.`);
    lines.push('Measurements per screenshot (share of pixels that changed since the previous one / that differ from the untouched copy):');
    for (const f of s.frames) lines.push(`- ${f.label}: ${f.blank ? 'BLANK (one flat colour)' : 'shows a picture'}; changed ${f.changed === null ? '—' : pct(f.changed)}; vs untouched ${f.vsIdle === null ? '—' : pct(f.vsIdle)}`);
    if (s.hung) lines.push(`The page STOPPED RESPONDING at ${(s.hung.atMs / 1000).toFixed(1)} s (${s.hung.phase}).`);
    if (r.playErrors.length) lines.push(`Errors while playing: ${r.playErrors.slice(0, 3).join(' | ')}`);
  }
  lines.push('Automatic checks:');
  for (const i of items) lines.push(`- ${i.passed ? 'PASS' : 'FAIL'}: ${i.label}${i.detail ? ` (${i.detail})` : ''}`);
  return lines.join('\n');
}

function clipCode(text: string): string {
  if (text.length <= MAX_JUDGE_CODE_CHARS) return text;
  const half = Math.floor(MAX_JUDGE_CODE_CHARS / 2);
  return `${text.slice(0, half)}\n\n[… ${text.length - MAX_JUDGE_CODE_CHARS} characters omitted by the harness …]\n\n${text.slice(-half)}`;
}

export function buildJudgePrompt(o: { task: string; rubric: string; spec: JamCaseSpec; artifact: string; playtest: PlaytestResult | null; items: ScoreBreakdownItem[]; withImages: boolean }): string {
  const n = o.spec.requirements.length;
  return fill(GAME_JAM_JUDGE_TEMPLATE, {
    task: o.task,
    playtest: playtestText(o.playtest, o.spec.genre, o.items, o.withImages),
    requirements: o.spec.requirements.map((r, i) => `R${i + 1}. ${r} (requirement ${i + 1} of the brief)`).join('\n'),
    rubric: o.rubric,
    bytes: `${(Buffer.byteLength(o.artifact) / 1000).toFixed(1)} kB`,
    artifact: clipCode(o.artifact),
    format: [`R1: PASS | PARTIAL | FAIL — <reason, at most 12 words>`, n > 2 ? `… one line for each requirement …` : '', `R${n}: PASS | PARTIAL | FAIL — <reason>`].filter(Boolean).join('\n'),
  });
}

// ───────────────────────────── Parsing a verdict ─────────────────────────────

export interface ParsedJamVerdict {
  requirements: Array<{ verdict: RequirementVerdict; reason?: string } | null>;
  criteria: Record<'visual' | 'creativity' | 'plays' | 'feel' | 'ambition', number>;
  verdict?: string;
}

const strip = (s: string) => s.replace(/[*_`#>]/g, '').trim();

/**
 * Read a judge's reply. Valid when all five criteria are present (0-10) and at least 60% of the requirement lines
 * are; the last occurrence of a line wins (judges sometimes draft, then restate). Returns an error message otherwise.
 */
export function parseJamVerdict(text: string, nRequirements: number): ParsedJamVerdict | string {
  const lines = text.split('\n').map(strip);
  const reqs: ParsedJamVerdict['requirements'] = Array.from({ length: nRequirements }, () => null);
  const crit: Partial<Record<string, number>> = {};
  let verdict: string | undefined;
  for (const line of lines) {
    const r = line.match(/^R\s*(\d+)\s*[:.)\-–—]\s*(PASS|PARTIAL|FAIL)\b\s*[—–:\-]*\s*(.*)$/i);
    if (r) {
      const i = Number(r[1]) - 1;
      if (i >= 0 && i < nRequirements) reqs[i] = { verdict: r[2]!.toLowerCase() as RequirementVerdict, reason: r[3]?.trim().slice(0, 140) || undefined };
      continue;
    }
    const c = line.match(/^(VISUALS?|PLAYS|FEEL|CREATIVITY|AMBITION)\s*[:=]\s*(\d+(?:\.\d+)?)\s*(?:\/\s*10)?\b/i);
    if (c) {
      crit[c[1]!.toUpperCase().replace(/^VISUAL$/, 'VISUALS')] = Number(c[2]);
      continue;
    }
    const v = line.match(/^VERDICT\s*:\s*(.+)$/i);
    if (v) verdict = v[1]!.trim().replace(/^["“]|["”]$/g, '').slice(0, 220);
  }
  const missing = ['VISUALS', 'CREATIVITY', 'PLAYS', 'FEEL', 'AMBITION'].filter((key) => !(typeof crit[key] === 'number' && crit[key]! >= 0 && crit[key]! <= 10));
  if (missing.length) return `missing or invalid ${missing.join(', ')}`;
  const graded = reqs.filter(Boolean).length;
  if (graded < Math.ceil(nRequirements * 0.6)) return `graded only ${graded} of ${nRequirements} requirements`;
  const k = (key: string) => crit[key]! / 10;
  return { requirements: reqs, criteria: { visual: k('VISUALS'), creativity: k('CREATIVITY'), plays: k('PLAYS'), feel: k('FEEL'), ambition: k('AMBITION') }, verdict };
}

const VALUE: Record<RequirementVerdict, number> = { pass: 1, partial: 0.5, fail: 0 };

/** Share of the checklist met: pass = 1, partial = ½, over the requirements this judge graded. */
export function fidelityOf(reqs: ParsedJamVerdict['requirements']): number {
  const graded = reqs.filter((r): r is NonNullable<typeof r> => r !== null);
  return graded.length ? graded.reduce((s, r) => s + VALUE[r.verdict], 0) / graded.length : 0;
}

export function jamTotal(criteria: Partial<Record<JamCriterion, number | null>>, weights: Partial<Record<JamCriterion, number>> = JAM_WEIGHTS): number {
  return (Object.keys(weights) as JamCriterion[]).reduce((s, k) => s + (weights[k] ?? 0) * (criteria[k] ?? 0), 0);
}

/**
 * The panel's score per criterion: the mean over judges, except VISUALS, where a judge who saw the screenshots
 * counts VISION_JUDGE_VISUAL_WEIGHT times as much as a judge who only read the code (text-only judges still count).
 */
export function panelCriteria(cards: Array<{ criteria: Partial<Record<JamCriterion, number>>; sawImages: boolean }>): Partial<Record<JamCriterion, number>> {
  const out: Partial<Record<JamCriterion, number>> = {};
  for (const id of criteriaOf(JAM_WEIGHTS)) {
    let sum = 0;
    let wsum = 0;
    for (const c of cards) {
      const v = c.criteria[id];
      if (typeof v !== 'number') continue;
      const w = id === 'visual' && c.sawImages ? VISION_JUDGE_VISUAL_WEIGHT : 1;
      sum += v * w;
      wsum += w;
    }
    if (wsum > 0) out[id] = sum / wsum;
  }
  return out;
}

/** Panel consensus per requirement. */
export function consensus(labels: string[], cards: Array<{ judgeId: string; parsed: ParsedJamVerdict }>): JamRequirement[] {
  return labels.map((label, i) => {
    const votes: JamRequirement['votes'] = {};
    for (const c of cards) {
      const v = c.parsed.requirements[i];
      if (v) votes[c.judgeId] = v;
    }
    const vals = Object.values(votes).map((v) => VALUE[v.verdict]);
    const mean = vals.length ? vals.reduce((a, b) => a + b, 0) / vals.length : null;
    return {
      id: `R${i + 1}`,
      label,
      verdict: mean === null ? null : mean >= 0.75 ? 'pass' : mean >= 0.25 ? 'partial' : 'fail',
      votes,
      split: new Set(Object.values(votes).map((v) => v.verdict)).size > 1,
    };
  });
}

// ───────────────────────────── Scoring ─────────────────────────────

const round = (n: number) => Math.round(n * 10000) / 10000;
const kb = (bytes: number) => `${(bytes / 1000).toFixed(1)} kB`;

function emptyCriteria(): Partial<Record<JamCriterion, number | null>> {
  return Object.fromEntries(criteriaOf(JAM_WEIGHTS).map((id) => [id, null]));
}

export async function scoreGameJam(input: ScoringInput, scorer: Extract<ScorerSpec, { type: 'artifact' }>): Promise<ScoringOutcome> {
  const spec = jamCaseSpec(input.expected);
  const g = GENRES[spec.genre];
  const truncated = input.stopReason === 'max_tokens';
  const judgeWeight = scorer.judgeWeight ?? 0;
  const base: GameJamDetail = {
    genre: spec.genre,
    genreLabel: g.label,
    truncated,
    playtest: null,
    requirements: spec.requirements.map((label, i) => ({ id: `R${i + 1}`, label, verdict: null, votes: {}, split: false })),
    judges: [],
    criteria: emptyCriteria(),
    weights: { ...JAM_WEIGHTS },
    automatedScore: 0,
    judgeWeight,
    judgeScore: null,
    spread: null,
  };
  const outOfSpace = 'Ran out of output space: the reply hit the output-token limit, so the game file is unfinished';
  const artifact = extractJamHtml(input.response, truncated);
  if (!artifact) {
    const summary = truncated ? `${outOfSpace} and no game code arrived` : 'No HTML game found in the reply';
    return { score: 0, passed: false, summary, detail: { formatOk: false, notes: truncated ? `${outOfSpace}.` : undefined, gameJam: { ...base, judgesSkipped: truncated ? 'Not asked: the game was never delivered.' : 'Not asked: there is no game to grade.' } } };
  }
  input.saveArtifact('artifact.html', 'html', artifact);
  const bytes = Buffer.byteLength(artifact);

  const playtest = await runPlaytest(artifact, spec.genre).catch(() => null);
  if (playtest) {
    // The most telling frame first (the legacy showcase and Blind Review show the first PNG), then the filmstrip.
    const key = playtest.frames.find((f) => f.t === 10000) ?? playtest.frames[playtest.frames.length - 1];
    if (key) input.saveArtifact('screenshot.png', 'png', key.png);
    for (const f of playtest.frames) input.saveArtifact(playtest.summary.frames.find((x) => x.t === f.t)!.name, 'png', f.png);
    if (playtest.motion && playtest.summary.motion) input.saveArtifact(playtest.summary.motion.name, 'png', playtest.motion.png);
  }

  // ── Automatic checks ──
  const items: ScoreBreakdownItem[] = [];
  const skipped: string[] = [];
  const s = playtest?.summary;
  for (const c of scorer.checks ?? []) {
    switch (c.check) {
      case 'parses':
        items.push({ label: 'HTML document parses', passed: /<(body|canvas|script|div|main)/i.test(artifact) && bytes > 80 });
        break;
      case 'contains':
        items.push({ label: `contains "${c.text}"`, passed: artifact.toLowerCase().includes(c.text.toLowerCase()) });
        break;
      case 'max_bytes':
        // A safety net against runaway output, not a design limit (the jam allows 20 MB).
        items.push({ label: c.bytes >= 1_000_000 ? `under ${Math.round(c.bytes / 1_000_000)} MB (runaway-output safety net)` : `≤ ${Math.round(c.bytes / 1000)} kB`, passed: bytes <= c.bytes, detail: kb(bytes) });
        break;
      case 'no_external_requests':
        if (playtest) items.push({ label: 'no external requests', passed: playtest.externalRequests.length === 0, detail: playtest.externalRequests.slice(0, 3).join(', ') || undefined });
        else {
          const ext = artifact.match(/(?:src|href)\s*=\s*["']https?:\/\/[^"']+|@import\s+url\(["']?https?:|fetch\(\s*["']https?:/gi) ?? [];
          items.push({ label: 'no external requests (static scan)', passed: ext.length === 0, detail: ext.slice(0, 2).join(', ') || undefined });
        }
        break;
      case 'runs_without_errors':
        if (playtest) items.push({ label: 'runs without JavaScript errors', passed: playtest.loadErrors.length === 0 && !(s!.hung && s!.hung.atMs === 0), detail: s!.hung?.atMs === 0 ? 'froze while loading' : playtest.loadErrors[0] });
        else skipped.push('runs_without_errors');
        break;
      case 'has_canvas_or_svg':
        if (playtest) items.push({ label: 'renders a canvas/SVG', passed: playtest.hasCanvasOrSvg });
        else skipped.push('has_canvas_or_svg');
        break;
      case 'responds_to_input':
        if (s) items.push({ label: 'reacts to keyboard/mouse input', passed: s.reacts.passed, detail: s.reacts.detail });
        else skipped.push('responds_to_input');
        break;
    }
  }
  if (s) {
    items.push(
      { label: 'Playtest: draws the game on screen (not blank)', passed: s.drawsPicture.passed, detail: s.drawsPicture.detail },
      { label: 'Playtest: keeps moving (not frozen)', passed: s.keepsMoving.passed, detail: s.keepsMoving.detail },
      { label: `Playtest: still running after ${s.seconds} s`, passed: s.stillRunning.passed, detail: s.stillRunning.detail },
      { label: 'Playtest: no errors while playing', passed: s.noPlayErrors.passed, detail: s.noPlayErrors.detail },
    );
  } else skipped.push('playtest');
  const automated = items.length ? items.filter((i) => i.passed).length / items.length : 1;
  const detailBase: ScoreDetail = {
    items,
    bytes,
    checkScore: round(automated),
    skippedChecks: skipped.length ? skipped : undefined,
    consoleErrors: playtest?.consoleErrors.slice(0, 5),
  };
  const gj: GameJamDetail = { ...base, playtest: s ?? null, automatedScore: round(automated) };

  // ── Caps (plain rules a viewer can follow) ──
  const broken = s ? Boolean(s.hung) || !s.drawsPicture.passed : false;
  const external = (playtest?.externalRequests.length ?? 0) > 0;
  const capReason = broken ? (s!.hung ? 'froze during the playtest' : 'showed a blank screen during the playtest') : external ? 'loads files from the internet' : null;

  // ── Out of output space: automatic checks only ──
  if (truncated) {
    const score = (1 - judgeWeight) * automated;
    return {
      score: round(score),
      passed: false,
      summary: `${outOfSpace} · ${items.filter((i) => i.passed).length}/${items.length} checks · judges not asked`,
      detail: { ...detailBase, notes: `${outOfSpace}. The unfinished file was still playtested; the judges were not asked, so the judged part scores 0.`, gameJam: { ...gj, judgesSkipped: 'Not asked: the model ran out of output space before finishing the file.' } },
    };
  }

  // ── Judges ──
  let judgeScore: number | null = null;
  let notes: string | undefined;
  if (scorer.rubric && judgeWeight > 0) {
    // Judges get high-quality JPEGs (the same frozen moments as the PNGs, within every provider's per-image size limit).
    const pic = (name: string, buf: Buffer, jpg: boolean): ChatImage => ({ name, mediaType: jpg ? 'image/jpeg' : 'image/png', data: buf.toString('base64'), bytes: buf.length, width: s!.viewport.width, height: s!.viewport.height });
    const images: ChatImage[] = (playtest?.frames ?? []).map((f) => pic(s!.frames.find((x) => x.t === f.t)!.name.replace(/\.png$/, f.jpg ? '.jpg' : '.png'), f.jpg ?? f.png, Boolean(f.jpg)));
    if (playtest?.motion) images.push(pic('playtest-motion.jpg', playtest.motion.jpg, true));
    const promptArgs = { task: input.taskText, rubric: scorer.rubric, spec, artifact, playtest, items };
    const withImages = buildJudgePrompt({ ...promptArgs, withImages: images.length > 0 });
    const textOnly = buildJudgePrompt({ ...promptArgs, withImages: false });
    const calls: JudgeCall[] = await input.judges.ask(JUDGE_SYSTEM, withImages, 'judge', { images, textOnlyUser: textOnly });
    const errors: string[] = [];
    const cards: Array<{ judgeId: string; parsed: ParsedJamVerdict; sawImages: boolean }> = [];
    for (const c of calls) {
      if (c.error) {
        errors.push(`${c.judgeId}: ${c.error}`);
        continue;
      }
      const p = parseJamVerdict(c.text, spec.requirements.length);
      if (typeof p === 'string') errors.push(`${c.judgeId}: unparsable verdict (${p})`);
      else cards.push({ judgeId: c.judgeId, parsed: p, sawImages: Boolean(c.sawImages) });
    }
    if (!cards.length) throw new Error(`All judges failed: ${errors.join('; ') || 'no judges configured'}`);
    const judgeCards: JamJudgeCard[] = cards.map((c) => {
      const criteria = { fidelity: fidelityOf(c.parsed.requirements), ...c.parsed.criteria };
      return { judgeId: c.judgeId, criteria: Object.fromEntries(Object.entries(criteria).map(([k, v]) => [k, round(v)])) as Partial<Record<JamCriterion, number>>, total: round(jamTotal(criteria)), verdict: c.parsed.verdict, sawImages: c.sawImages };
    });
    // The panel total comes from the panel's criteria, so a judge who saw the pictures weighs more on VISUALS.
    const panel = panelCriteria(judgeCards);
    judgeScore = jamTotal(panel);
    const spread = judgeCards.length > 1 ? Math.max(...judgeCards.map((j) => j.total)) - Math.min(...judgeCards.map((j) => j.total)) : 0;
    gj.judges = judgeCards;
    gj.requirements = consensus(spec.requirements, cards);
    gj.criteria = Object.fromEntries(Object.entries(panel).map(([k, v]) => [k, round(v!)]));
    gj.judgeScore = round(judgeScore);
    gj.spread = round(spread);
    const ten = (v: number | undefined) => ((v ?? 0) * 10).toFixed(0);
    detailBase.judge = judgeCards.map((j) => ({ contestantId: j.judgeId, score: j.total, rationale: [j.verdict, `Visuals ${ten(j.criteria.visual)}/10${j.sawImages ? '' : ' (code only)'} · creativity ${ten(j.criteria.creativity)} · plays ${ten(j.criteria.plays)} · feel ${ten(j.criteria.feel)} · ambition ${ten(j.criteria.ambition)} · checklist ${Math.round((j.criteria.fidelity ?? 0) * 100)}%`].filter(Boolean).join(' ') }));
    detailBase.judgeScore = round(judgeScore);
    detailBase.judgeSpread = round(spread);
    if (spread > 0.3) {
      detailBase.judgeDisagreement = true;
      notes = `Judges disagree (spread ${(spread * 10).toFixed(1)} points) — flagged for human review`;
    }
    if (errors.length) notes = [notes, `Judge issues: ${errors.join('; ')}`].filter(Boolean).join(' · ');
  }

  let score = judgeScore === null ? automated : (1 - judgeWeight) * automated + judgeWeight * judgeScore;
  if (capReason) {
    score = Math.min(score, BROKEN_CAP);
    gj.cap = `The game ${capReason}, so its score is capped at ${Math.round(BROKEN_CAP * 100)}.`;
  }
  const passedChecks = items.filter((i) => i.passed).length;
  const creative = gj.criteria.creativity;
  const visual = gj.criteria.visual;
  return {
    score: round(score),
    passed: score >= 0.7,
    summary: `${passedChecks}/${items.length} checks${judgeScore !== null ? ` · judges ${(judgeScore * 10).toFixed(1)}/10` : ''}${typeof visual === 'number' ? ` · visuals ${(visual * 10).toFixed(1)}` : ''}${typeof creative === 'number' ? ` · creativity ${(creative * 10).toFixed(1)}` : ''}${gj.cap ? ` · capped (${capReason})` : ''}${skipped.length ? ' · browser checks skipped' : ''}`,
    detail: { ...detailBase, notes: [gj.cap, notes].filter(Boolean).join(' · ') || undefined, gameJam: gj },
  };
}
