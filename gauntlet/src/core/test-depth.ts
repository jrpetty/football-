/**
 * "See in depth": a plain-English walk-through of what one test does, built from the test's real definition
 * (its cases or seeds, its scorer, its limits). Display text only: it is never sent to a model, never part of a
 * test hash, and never changes a score. Every line is derived from the definition, so it cannot drift from the
 * scoring code the way hand-written text can.
 */
import type { ArtifactCheck, ScorerSpec, TestDefinition } from './types.ts';

export interface DepthStep {
  title: string;
  detail: string;
}

export interface TestDepth {
  /** What happens, in order, for one model taking this test. */
  steps: DepthStep[];
  /** How each answer is marked, one line per rule. */
  marking: string[];
  /** Key numbers: questions, time, output room. */
  facts: Array<{ label: string; value: string }>;
}

const plural = (n: number, one: string, many = `${one}s`) => `${n} ${n === 1 ? one : many}`;

const RUBRIC_MAX = 700;
function trimRubric(text: string): string {
  const flat = text.replace(/\s+/g, ' ').trim();
  return flat.length > RUBRIC_MAX ? `${flat.slice(0, RUBRIC_MAX).replace(/\s+\S*$/, '')}…` : flat;
}

const ARTIFACT_CHECK_TEXT: Record<ArtifactCheck['check'], string> = {
  parses: 'The file must be valid HTML or SVG.',
  contains: 'The file must contain a required piece of text.',
  max_bytes: 'The file must stay within a size limit.',
  no_external_requests: 'It must not load anything from the internet (everything is self-contained).',
  runs_without_errors: 'It must run in a real browser without throwing errors.',
  has_canvas_or_svg: 'It must actually draw something on the page.',
  responds_to_input: 'It must react when a key or mouse action is sent.',
};

/** The marking rules for one scorer, one plain sentence each. */
export function describeMarking(scorer: ScorerSpec): string[] {
  switch (scorer.type) {
    case 'exact':
      return ['The final answer is compared word for word with the accepted answers (spacing and capitals are tidied first).'];
    case 'number':
      return [
        `The final answer must be the exact number in the answer key${scorer.tolerance ? `, within ${scorer.tolerance}` : ''}.`,
        'Only the number on the “FINAL ANSWER:” line counts.',
      ];
    case 'choice':
      return ['The letter on the “FINAL ANSWER:” line must be the one correct option.'];
    case 'regex':
      return [
        scorer.fullText ? 'The whole reply is searched for a required pattern.' : 'The “FINAL ANSWER:” line is checked against a required pattern.',
      ];
    case 'contains': {
      const rules: string[] = [];
      if (scorer.all?.length) rules.push(`Must mention: ${scorer.all.map((t) => `“${t}”`).join(', ')}.`);
      if (scorer.any?.length) rules.push(`Must mention at least one of: ${scorer.any.map((t) => `“${t}”`).join(', ')}.`);
      if (scorer.none?.length) rules.push(`Must NOT mention: ${scorer.none.map((t) => `“${t}”`).join(', ')}.`);
      return rules.length ? rules : ['The reply is checked for required and forbidden words.'];
    }
    case 'constraints':
      return [
        scorer.allOrNothing
          ? 'Every stated rule must hold, or the answer scores zero.'
          : 'Each stated rule is checked on its own; the score is the share of rules met.',
      ];
    case 'json':
      return [
        'The answer must be data in the format asked for.',
        scorer.allOrNothing
          ? 'Every field must match the answer key, or the answer scores zero.'
          : 'Every field is compared with the answer key; the score is the share of fields right.',
      ];
    case 'code-js':
      return ['The code is run on hidden test inputs in a sandbox; the score is the share of tests it passes.'];
    case 'judge':
      return [`AI judges from other companies score it against this rubric: ${trimRubric(scorer.rubric)}`];
    case 'judge-classify':
      return [
        `AI judges sort the answer into one of these labels: ${scorer.labels.map((l) => `${l.description} (${l.score} points)`).join('; ')}.`,
      ];
    case 'artifact': {
      const rules = (scorer.checks ?? []).map((c) => ARTIFACT_CHECK_TEXT[c.check]);
      if (scorer.playtest) rules.push('A scripted player plays the game for about 30 seconds; the screenshots are judged.');
      if (scorer.rubric) rules.push(`AI judges score it against this rubric: ${trimRubric(scorer.rubric)}`);
      return rules.length ? rules : ['The file is checked automatically.'];
    }
    case 'human':
      return [`A person grades it in Blind Review against this rubric: ${trimRubric(scorer.rubric)}`];
    case 'ladder':
      return [
        scorer.answer === 'plan'
          ? 'The plan is replayed step by step by the checker; it must be legal and reach the goal.'
          : scorer.answer === 'grid'
            ? 'The whole grid is compared cell by cell with the one correct solution.'
            : 'The number is checked exactly against the correct answer.',
      ];
  }
}

/** The steps a model goes through, in plain English. */
function stepsFor(def: TestDefinition, programScoring?: string): DepthStep[] {
  if (def.kind === 'program') {
    return [
      { title: 'The world', detail: `Each of the ${plural(def.seeds.length, 'world')} is generated from a fixed seed, so every model faces the same one.` },
      { title: 'Turn by turn', detail: 'The model is shown what it can see, chooses an action, and the world responds. Nothing it has not been shown is revealed.' },
      { title: 'The score', detail: programScoring ?? 'Scored by the program (see the rules above).' },
    ];
  }
  const n = def.cases.length;
  const multi = def.cases.some((c) => (c.turns?.length ?? 0) > 1);
  const pictures = def.cases.some((c) => (c.images?.length ?? 0) > 0);
  const replyRule =
    def.scorer.type === 'exact' || def.scorer.type === 'number' || def.scorer.type === 'choice' || def.scorer.type === 'regex'
      ? 'Models are asked to finish with a line “FINAL ANSWER: …”, so the answer can be read reliably.'
      : 'Models reply in the format the question asks for.';
  return [
    { title: 'The questions', detail: `${plural(n, 'question')} from this test. Each one is sent on its own, in a fresh chat, with exactly the wording shown.` },
    ...(multi ? [{ title: 'Several turns', detail: 'Some questions continue over several messages, so the model must remember earlier replies.' }] : []),
    ...(pictures ? [{ title: 'Pictures', detail: 'Some questions include images the model has to read.' }] : []),
    { title: 'The reply', detail: replyRule },
    { title: 'Marking', detail: 'Each reply is marked by the rules below, with no hints and no answer key shown to the model.' },
    { title: 'The score', detail: 'Each question scores 0–100. The test score is the average over every question (and every repeat).' },
  ];
}

export function testDepth(def: TestDefinition, programScoring?: string): TestDepth {
  const facts: Array<{ label: string; value: string }> = [];
  if (def.kind === 'program') facts.push({ label: 'Worlds', value: String(def.seeds.length) });
  else facts.push({ label: 'Questions', value: String(def.cases.length) });
  facts.push({ label: 'Marked by', value: markedBy(def) });
  if (def.timeLimitSec) facts.push({ label: 'Time limit per question', value: `${Math.round(def.timeLimitSec / 60)} min` });
  if (def.maxOutputTokens === 'model-max') facts.push({ label: 'Answer length', value: 'each model’s own maximum' });
  else if (def.maxOutputTokens) facts.push({ label: 'Answer length', value: `up to ${def.maxOutputTokens.toLocaleString('en-GB')} tokens` });
  const marking = def.kind === 'program' ? [programScoring ?? 'Scored by the program.'] : describeMarking(def.scorer);
  return { steps: stepsFor(def, programScoring), marking, facts };
}

function markedBy(def: TestDefinition): string {
  if (def.kind === 'program') return 'the program’s own rules (sometimes with AI judges)';
  switch (def.scorer.type) {
    case 'judge':
    case 'judge-classify':
      return 'AI judges from other companies';
    case 'human':
      return 'a person (Blind Review)';
    case 'artifact':
      return def.scorer.rubric ? 'the computer and AI judges' : 'the computer (automatic checks)';
    default:
      return 'the computer, against an answer key';
  }
}
