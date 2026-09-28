/**
 * Mock-mode fixtures for the plain-English explainers (?mock=1):
 *  - the real explainers (src/core/explainers.ts) for every real test id the demo uses,
 *  - hand-written explainers for the demo-only tests in fixtures.ts,
 *  - GET /api/tests/:id/sample built in the browser from the definition (prompt tests) or from
 *    the real opening messages recorded in explainSamples.json (programs and Arena games).
 * "reasoning.calendar-puzzle" (a custom test) deliberately has no explainer, to demo the fallback.
 */
import { EXPLAINERS, explainerForDefinition } from '../../../src/core/explainers.ts';
import { promptSampleFrom } from '../../../src/core/sample-text.ts';
import type { ProgramInfo, SampleImage, TestDefinition, TestExplainer, TestSample } from '../types.ts';
import samples from './explainSamples.json';
import { ApiError } from '../api.ts';
import { renderedOf } from './fixtures.ts';

const RECORDED = samples as unknown as Record<string, TestSample>;

export const MOCK_EXPLAINERS: Record<string, TestExplainer> = {
  'reasoning.river-crossing': {
    hook: 'Can it get everyone across the river in the fewest trips — with new rules?',
    whatItTests: 'Planning the shortest sequence of crossings for river puzzles with fresh twists.',
    whyHard: 'The famous versions are memorised; one changed rule makes the memorised answer wrong.',
    howScored: [
      { icon: 'target', text: 'One number: the minimum crossings' },
      { icon: 'check', text: 'Must match the answer key exactly' },
    ],
    goodScore: '100 means every puzzle solved in the true minimum.',
    icon: 'route',
  },
  'reasoning.knights-knaves': {
    hook: 'Two of them are lying. Can it work out which two?',
    whatItTests: 'Logic with truth-tellers and liars: picking the one line-up that fits every statement.',
    whyHard: 'Statements refer to each other, so every case has to be checked.',
    howScored: [
      { icon: 'target', text: 'Picks one lettered option' },
      { icon: 'check', text: 'Right or wrong, no partial credit' },
    ],
    goodScore: '100 means all five puzzles right.',
    icon: 'knight',
  },
  'math.competition-mix': {
    hook: 'Six contest problems, no calculator, one number each.',
    whatItTests: 'Contest maths — counting, number theory and geometry — with whole-number answers.',
    whyHard: 'A clever idea is needed first, then an exact calculation with no slips.',
    howScored: [
      { icon: 'target', text: 'One whole-number answer per problem' },
      { icon: 'check', text: 'Must match exactly' },
    ],
    goodScore: '100 means all six right.',
    icon: 'sigma',
  },
  'math.probability-traps': {
    hook: 'Does its gut feeling about chance hold up? (It usually doesn’t.)',
    whatItTests: 'Probability questions built around intuitions that are wrong.',
    whyHard: 'The obvious answer feels right and is wrong; only careful counting works.',
    howScored: [
      { icon: 'target', text: 'One number per question' },
      { icon: 'check', text: 'Must match within 0.001' },
    ],
    goodScore: '100 means all four answered correctly.',
    icon: 'sigma',
  },
  'coding.interval-merge': {
    hook: 'Can it write code for merging time ranges that passes every hidden test?',
    whatItTests: 'Writing small JavaScript functions that merge, insert and subtract intervals.',
    whyHard: 'Touching and empty intervals are easy to get wrong.',
    howScored: [
      { icon: 'play', text: 'The code it writes is actually run' },
      { icon: 'percent', text: 'Score = share of hidden tests passed' },
    ],
    goodScore: '100 means every hidden test passes.',
    icon: 'code',
  },
  'coding.lru-cache': {
    hook: 'Can it build a memory cache that forgets the right thing at the right time?',
    whatItTests: 'Implementing a cache that evicts the least-recently-used item and expires old entries.',
    whyHard: 'The order of evictions and expiry edge cases must be exactly right.',
    howScored: [
      { icon: 'play', text: 'The code it writes is actually run' },
      { icon: 'percent', text: 'Score = share of hidden tests passed' },
    ],
    goodScore: '100 means every hidden test passes.',
    icon: 'code',
  },
  'instruction.constraint-gauntlet': {
    hook: 'Can it write a poem without the letter E — and make it an acrostic?',
    whatItTests: 'Writing short texts that obey several machine-checked rules at once.',
    whyHard: 'AI models write in chunks of words, not letters, so letter rules need constant checking.',
    howScored: [
      { icon: 'rules', text: 'Every rule checked by machine' },
      { icon: 'percent', text: 'Points for each rule followed' },
    ],
    goodScore: '100 means every rule of every task followed.',
    icon: 'rules',
  },
  'instruction.json-shapes': {
    hook: 'Can it produce data in exactly the shape it was asked for?',
    whatItTests: 'Returning machine-readable data (JSON) that matches a spec exactly.',
    whyHard: 'Small slips — a missing key, a number as text — break the shape.',
    howScored: [
      { icon: 'document', text: 'Answer must be JSON' },
      { icon: 'percent', text: 'Points for each field that matches' },
    ],
    goodScore: '100 means every field of every answer right.',
    icon: 'document',
  },
  'long-context.needle-novel': {
    hook: 'Can it find three planted facts in a 90,000-token novel and combine them?',
    whatItTests: 'Reading a very long text and linking details from different chapters.',
    whyHard: 'The facts are far apart, and each answer needs two of them.',
    howScored: [
      { icon: 'target', text: 'One short answer per question' },
      { icon: 'check', text: 'Matched against the key (spelling-insensitive)' },
    ],
    goodScore: '100 means all three questions right.',
    icon: 'needle',
  },
  'visual.svg-construct': {
    hook: 'Can it draw an exact diagram from a written spec — in code?',
    whatItTests: 'Building precise SVG drawings from geometric specifications.',
    whyHard: 'It draws blind by writing numbers, so the geometry must be worked out exactly.',
    howScored: [
      { icon: 'check', text: 'Half: automatic checks on the drawing' },
      { icon: 'scale', text: 'Half: AI judges compare it to the spec' },
    ],
    goodScore: '70 or more is a pass; 100 is a perfect match.',
    icon: 'shapes',
  },
  'creative.one-shot-game': {
    hook: 'Can it build a playable arcade game from one prompt?',
    whatItTests: 'Writing a complete working game in one web page.',
    whyHard: 'One bug can break the game and there is no second attempt.',
    howScored: [
      { icon: 'play', text: 'The game is opened in a real browser' },
      { icon: 'check', text: '40%: automatic checks (loads, reacts…)' },
      { icon: 'scale', text: '60%: AI judges rate it out of 10' },
    ],
    goodScore: '70 or more is a pass; 100 needs every check and a perfect 10.',
    icon: 'gamepad',
  },
  'creative.landing-page': {
    hook: 'Would you ship the landing page it designs?',
    whatItTests: 'Designing a polished single-file web page for a made-up product.',
    whyHard: 'Taste is hard to fake: layout, words and polish all show.',
    howScored: [
      { icon: 'scale', text: 'Rated blind by people, 0–10' },
      { icon: 'hidden', text: 'Raters never see which model made it' },
    ],
    goodScore: '100 means every rater gave it a 10.',
    icon: 'shapes',
  },
  'extraction.invoice-json': {
    hook: 'Can it turn a messy scanned invoice into exact data?',
    whatItTests: 'Reading scanned invoices and filling in a fixed data form.',
    whyHard: 'Scanning errors mix up letters and digits (0 and O), and dates use day/month order.',
    howScored: [
      { icon: 'document', text: 'Answer must be JSON' },
      { icon: 'percent', text: 'Points for each field that matches' },
    ],
    goodScore: '100 means every field right.',
    icon: 'document',
  },
  'extraction.messy-contacts': {
    hook: 'Can it pull clean contact details out of email signatures and chats?',
    whatItTests: 'Turning messy signatures into normalised name, email and phone records.',
    whyHard: 'Phone numbers come in many formats and must be written one exact way.',
    howScored: [
      { icon: 'document', text: 'Answer must be JSON' },
      { icon: 'percent', text: 'Points for each field that matches' },
    ],
    goodScore: '100 means every contact field right.',
    icon: 'document',
  },
  'reasoning.heldout-ciphers': {
    hook: 'Can it crack ciphers nobody has ever published?',
    whatItTests: 'Decoding substitution ciphers that exist only in a private test set.',
    whyHard: 'They were never published, so no model can have seen them in training.',
    howScored: [
      { icon: 'target', text: 'One decoded answer per cipher' },
      { icon: 'check', text: 'Must match the key exactly' },
    ],
    goodScore: '100 means every cipher cracked.',
    icon: 'lock',
  },
};

/** The explainer the mock API returns for a test: real, demo-only, or generated from the definition. */
export function mockExplainerFor(def: TestDefinition, program?: ProgramInfo): TestExplainer {
  return EXPLAINERS[def.id] ?? MOCK_EXPLAINERS[def.id] ?? explainerForDefinition(def, program?.scoring);
}

/** Every explainer the mock knows (GET /api/explainers). */
export function mockExplainers(): Record<string, TestExplainer> {
  return { ...EXPLAINERS, ...MOCK_EXPLAINERS };
}

/** GET /api/tests/:id/sample in mock mode. */
export function mockSample(id: string, def: TestDefinition | undefined, images: SampleImage[], reveal: boolean, isPrivate: boolean): TestSample | null {
  if (id.startsWith('arena.')) return RECORDED[id] ?? null;
  if (!def) return null;
  if (def.kind === 'program') {
    const rec = RECORDED[def.id];
    const opening = EXPLAINERS[def.id]?.opening ?? null;
    const base: TestSample = rec
      ? { ...rec, situation: opening, caseCount: def.seeds.length, caseId: `seed-${def.seeds[0]}` }
      : {
          testId: def.id,
          kind: 'program',
          caseId: `seed-${def.seeds[0]}`,
          caseCount: def.seeds.length,
          situation: opening ?? def.description,
          context: null,
          text: '',
          truncated: false,
          fullChars: 0,
          turns: 1,
          images: [],
          hasAnswer: false,
        };
    return base;
  }
  const s = promptSampleFrom(def, images, reveal);
  return isPrivate ? { ...s, text: '', tail: undefined, context: null, images: [], hasAnswer: false, answer: undefined, answerNote: undefined, private: true } : s;
}

/** The mock route: pictures come from the rendered case (mock images are bundled). */
export function mockSampleRoute(id: string, def: TestDefinition | undefined, reveal: boolean, isPrivate: boolean): TestSample {
  const images: SampleImage[] = def?.kind === 'prompt' ? (renderedOf(def)[0]?.images ?? []).map((r) => ({ name: r.file.split('/').pop() ?? r.file, ...(r.path ? { path: r.path } : {}) })) : [];
  const s = mockSample(id, def, images, reveal, isPrivate);
  if (!s) throw new ApiError(`Test not found: ${id}`, 404);
  return s;
}
