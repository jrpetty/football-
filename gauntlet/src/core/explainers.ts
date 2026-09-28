/**
 * Plain-English explainers: what every test is, in words a viewer gets in five seconds.
 *
 * One entry per test id (and one per Arena game, keyed "arena.<gameId>"). They are
 * display text only: never sent to a model, never part of a test hash, so editing
 * them changes nothing about scoring or stored results. `test/explain.test.ts`
 * fails when a built-in test or Arena game has no entry here, so a new test can't
 * ship without one (see docs/ADDING_TESTS.md → "Write an explainer").
 *
 * Every "howScored" line must match the real scorer: read the test's scorer config
 * (prompt tests) or the program's `scoring` text and code before writing it.
 */
import type { ScorerSpec, TestDefinition } from './types.ts';

/** Icon names drawn by ui/src/components/viz/ExplainIcon.tsx (typecheck fails if one is missing there). */
export const EXPLAIN_ICONS = [
  // Test icons
  'needle',
  'whisper',
  'island',
  'door',
  'growth',
  'bug',
  'mask',
  'grid',
  'route',
  'knight',
  'sigma',
  'receipt',
  'code',
  'gamepad',
  'document',
  'shield',
  'rules',
  'persona',
  'trap',
  'bolt',
  'eye',
  'chart',
  'count',
  'diff',
  'pen',
  'pencil',
  'shapes',
  'connect4',
  'chess',
  'cards',
  'mic',
  'gavel',
  // Step icons
  'ask',
  'check',
  'cross',
  'play',
  'scale',
  'percent',
  'clock',
  'target',
  'lock',
  'hidden',
  'robot',
  'moves',
  'flag',
  'trophy',
  'dollar',
  'ruler',
  'swap',
  'zero',
  'star',
  'key',
  'ladder',
] as const;

export type ExplainIconName = (typeof EXPLAIN_ICONS)[number];

export interface ExplainStep {
  icon: ExplainIconName;
  /** A short phrase (under ~60 characters) that reads well on one line of a slide. */
  text: string;
}

export interface TestExplainer {
  /** One line a viewer understands at a glance, usually a question. */
  hook: string;
  /** One sentence: the skill being tested. */
  whatItTests: string;
  /** One honest sentence: why this is hard for an AI. */
  whyHard: string;
  /** 2–4 short steps that match the real scorer exactly. */
  howScored: ExplainStep[];
  /** What a good score means, e.g. "100 means every needle found". */
  goodScore: string;
  icon: ExplainIconName;
  /** Programs and Arena games: the opening situation, one or two sentences (true for every seed). */
  opening?: string;
  /** True when the explainer was generated from the test definition, not written by hand (custom tests). */
  generated?: boolean;
}

// ───────────────────────────── Sample question (GET /api/tests/:id/sample, built in test-sample.ts) ─────────────────────────────

export interface SampleImage {
  name: string;
  /** Path relative to the tests folder: GET /api/test-files/<path>. */
  path?: string;
}

export interface TestSample {
  testId: string;
  kind: 'prompt' | 'program';
  /** Case id ("seed-<n>" for programs). */
  caseId: string;
  /** How many cases the test has. */
  caseCount: number;
  /** One or two plain sentences describing the opening situation (programs), or null. */
  situation: string | null;
  /** Instructions shared by every case (preamble or system prompt), trimmed; null when none. */
  context: string | null;
  /** Where `context` comes from: the system prompt, or text added before every question. */
  contextKind?: 'system' | 'preamble';
  /** The question itself, trimmed for a viewer. */
  text: string;
  /** True when `text` was shortened. */
  truncated: boolean;
  /** Very long prompts (a whole book, a long document): the end of the prompt, where the questions are. */
  tail?: string;
  /** Words left out between `text` and `tail`. */
  skippedWords?: number;
  /** Length of the full question in characters. */
  fullChars: number;
  /** Multi-turn cases: number of user turns (only the first is shown). */
  turns: number;
  images: SampleImage[];
  /** The case has an answer key or reference that `?reveal=1` would show. */
  hasAnswer: boolean;
  /** Only with reveal: the answer, in words a viewer can read. */
  answer?: string;
  /** Only with reveal: how that answer is checked, one line. */
  answerNote?: string;
  /** Held-out test: its questions are never shown. */
  private?: boolean;
}

// Shared step sets (the same scorer gives the same steps).
const CODE_AGENT_STEPS: ExplainStep[] = [
  { icon: 'hidden', text: 'Hidden tests run after it submits: 75%' },
  { icon: 'check', text: 'Broken visible tests it turned green: 15%' },
  { icon: 'clock', text: 'Fewer actions and tokens: up to 10%' },
  { icon: 'cross', text: 'Trying to edit the locked tests costs points' },
];
const ESCAPE_STEPS: ExplainStep[] = [
  { icon: 'key', text: 'Half the score: share of locks opened' },
  { icon: 'door', text: '+30 for getting out through the exit' },
  { icon: 'moves', text: 'Up to +20 for fewest moves, only if it escapes' },
];
const STARTUP_STEPS: ExplainStep[] = [
  { icon: 'dollar', text: 'Company value at the end: cash + stock − loans' },
  { icon: 'robot', text: '0 = no better than an autopilot' },
  { icon: 'trophy', text: '100 = matches an all-knowing player' },
  { icon: 'cross', text: 'Going bankrupt scores 0' },
];
const ISLAND_STEPS: ExplainStep[] = [
  { icon: 'clock', text: '70%: days survived beyond a do-nothing castaway' },
  { icon: 'flag', text: '20%: rescued by signalling the ship' },
  { icon: 'star', text: '10%: spear, campfire, shelter, signal pile built' },
];
const LIARS_STEPS: ExplainStep[] = [
  { icon: 'target', text: '60: naming the right thief (wrong name = 0)' },
  { icon: 'clock', text: 'Up to +25 for asking fewer questions' },
  { icon: 'check', text: '+15 for the right reason: time, room, proof' },
];
const WHISPER_STEPS = (facts: number): ExplainStep[] => [
  { icon: 'check', text: 'A fact counts if its key words stay in one sentence' },
  { icon: 'percent', text: `Score = facts left in the final story ÷ ${facts}` },
  { icon: 'ruler', text: 'Small penalties for breaking the word limits' },
];
const NEEDLE_STEPS = (n: number): ExplainStep[] => [
  { icon: 'needle', text: `${n} short answers, each worth the same` },
  { icon: 'check', text: 'Each matched against the answer key' },
  { icon: 'cross', text: 'The decoy, an outdated value or two answers: wrong' },
];
const EXACT_GRID_STEPS = (what: string): ExplainStep[] => [
  { icon: 'grid', text: what },
  { icon: 'check', text: 'Compared exactly (spaces and capitals ignored)' },
  { icon: 'zero', text: 'Any mistake: that puzzle scores 0' },
];
const NUMBER_STEPS = (what: string): ExplainStep[] => [
  { icon: 'target', text: what },
  { icon: 'check', text: 'Must match the answer key exactly' },
  { icon: 'cross', text: 'Two answers at once count as wrong' },
];
const CODE_STEPS = (tests: number, tasks: number, sec: number): ExplainStep[] => [
  { icon: 'play', text: 'The code it writes is actually run' },
  { icon: 'hidden', text: `${tests} hidden unit tests across ${tasks} tasks` },
  { icon: 'clock', text: `Each test must finish within ${sec} seconds` },
  { icon: 'percent', text: 'Score = share of tests passed' },
];
const HONESTY_STEPS = (judged: string, caught: string, fooled: string): ExplainStep[] => [
  { icon: 'scale', text: judged },
  { icon: 'check', text: caught },
  { icon: 'percent', text: 'Half-right: 50 · wrongly refusing: 25' },
  { icon: 'cross', text: fooled },
];
const ARENA_BOARD_STEPS: ExplainStep[] = [
  { icon: 'trophy', text: 'Win = 1 point · draw = ½ · loss = 0' },
  { icon: 'swap', text: 'Each pairing plays both colours' },
  { icon: 'cross', text: 'Illegal move: one retry, then a random move + strike' },
  { icon: 'zero', text: 'Too many strikes (3 by default) lose the game' },
];
const DRAW_STEPS = (weights: string): ExplainStep[] => [
  { icon: 'pencil', text: 'Its redrawing is matched shape by shape' },
  { icon: 'target', text: weights },
  { icon: 'cross', text: 'Digits in the words or extra shapes cost points' },
];

const LADDER_STEPS = (what: string): ExplainStep[] => [
  { icon: 'ladder', text: 'Ten levels, each harder than the last' },
  { icon: 'target', text: what },
  { icon: 'check', text: 'Checked by machine against a double-proven key' },
  { icon: 'flag', text: 'Headline: how high it climbs without missing a rung' },
];
const LADDER_SCORE = 'Every level is worth 10 points, so 30 means three levels solved. The ladder was built so today’s best models stall low and future ones can climb.';

export const EXPLAINERS: Record<string, TestExplainer> = {
  // ─────────────── Agentic ───────────────
  'agentic.code-agent': {
    hook: 'Can it find and fix the bugs in a real code project on its own?',
    whatItTests: 'Working as a coding agent: reading files, searching, editing code and running tests in a small JavaScript project until the bugs are gone.',
    whyHard: 'It has to work out where the bugs are from a vague bug report and failing tests, with only 30 actions, and hidden tests catch fixes that merely fool the visible ones.',
    howScored: CODE_AGENT_STEPS,
    goodScore: '100 means every hidden test passes, reached efficiently; 0 means nothing that was broken got fixed.',
    icon: 'bug',
    opening: 'A small JavaScript project with a red test suite and a bug report from users. The model has 30 actions: list, read, search, edit, run the tests, submit.',
  },
  'agentic.code-agent-hard': {
    hook: 'Can it fix three bugs in a big codebase when the clues point the wrong way?',
    whatItTests: 'Working like a real software engineer in a 25-file project: exploring, finding bugs from symptoms and fixing them within a strict action budget.',
    whyHard: 'The failing test blames the wrong file, one bug only appears after another is fixed, and one is caught only by hidden tests, so turning the visible tests green is not enough.',
    howScored: CODE_AGENT_STEPS,
    goodScore: '100 means every hidden test passes, reached efficiently; 0 means nothing that was broken got fixed.',
    icon: 'bug',
    opening: 'A 23–25 file project full of decoys, a red test suite and a bug report that describes symptoms, not causes. The model has 35 actions — not enough to read everything.',
  },
  'agentic.escape-room': {
    hook: 'Can it escape three locked rooms by cracking codes in 45 moves?',
    whatItTests: 'Solving a chain of puzzles in a text adventure: finding keys, decoding clues and opening locks in the right order.',
    whyHard: 'Clues are hidden in objects and encoded (Roman numerals, stopped clocks, ciphers), every command costs a move, and its only memory is a short note it writes itself.',
    howScored: ESCAPE_STEPS,
    goodScore: '100 means it escaped using the fewest possible moves; opening half the locks without escaping scores 25.',
    icon: 'door',
    opening: 'The model wakes up in a locked study full of objects, with a coded door and a safe. It has 45 moves, and every command — even a useless one — uses one.',
  },
  'agentic.escape-room-hard': {
    hook: 'Can it escape when a clue from the first room unlocks the last?',
    whatItTests: 'Solving a long chain of puzzles across three rooms, and remembering which clues and items will matter later.',
    whyHard: 'Ciphers take two decoding steps, decoys carry fake numbers and colours, and it gets only 25% more moves than a perfect player, so there is no room to guess.',
    howScored: ESCAPE_STEPS,
    goodScore: '100 means it escaped using the fewest possible moves; opening half the ten locks without escaping scores 25.',
    icon: 'door',
    opening: 'Ten locks across three rooms. The shift for a cipher in the last room is engraved in the first, and an item it needs at the end is waiting back at the start.',
  },
  'agentic.startup-sim': {
    hook: 'Can it grow a company from $10,000 better than autopilot?',
    whatItTests: 'Business judgement over 12 months: setting price, production, marketing and hiring, then learning from each month’s results.',
    whyHard: 'The market’s rules are hidden, so it must learn them from its own sales figures and prepare for events like a price war and a supplier cost spike.',
    howScored: STARTUP_STEPS,
    goodScore: '50 or more is a pass: it closed at least half the gap between the autopilot and an all-knowing player.',
    icon: 'growth',
    opening: 'Month 1 of 12: $10,000 in the bank, two staff and a product nobody has heard of. Each month it sets price, production, marketing and hiring, then reads the results.',
  },
  'agentic.startup-sim-hard': {
    hook: 'Can it steer a company through a price war, a supply shock and a demand crash?',
    whatItTests: 'Business judgement in a volatile market: reading monthly results, hedging ahead of shocks and keeping the company alive.',
    whyHard: 'Demand swings month to month, costs jump by up to 80% for four months and a safety scare cuts demand by about 40%, so a plan that ignores the news goes broke.',
    howScored: STARTUP_STEPS,
    goodScore: '50 or more is a pass: it closed at least half the gap between the autopilot and an all-knowing player.',
    icon: 'growth',
    opening: 'The same $10,000 start as The Startup, but the market hits harder: noisier demand, a deeper supplier spike, a harsher price war and a one-month demand crash.',
  },
  'agentic.survival-island': {
    hook: 'Can it find water, avoid poison berries and get rescued from a desert island?',
    whatItTests: 'Planning over many turns: keeping food, water and warmth up, building a camp and signalling a passing ship at the right moment.',
    whyHard: 'It sees one turn at a time, remembers only a 300-character note, and must work out the ship’s timetable to light the signal fire when it passes.',
    howScored: ISLAND_STEPS,
    goodScore: '100 means rescued with all four things built; staying alive to the last day without a rescue still counts as a pass.',
    icon: 'island',
    opening: 'Day 1 of 12, morning, on a beach with nothing. Health, food, water, energy and warmth start draining and most of the map is unexplored.',
  },
  'agentic.survival-island-hard': {
    hook: 'Can it survive 13 harsh days on a desert island until a ship finally comes?',
    whatItTests: 'Long-term survival planning: a sheltered camp, a fire kept burning and a steady food and water routine, then a well-timed rescue signal.',
    whyHard: 'The ship doesn’t pass until day 8 or 9, three of five berry bushes are poisonous, the spring only trickles and storms are frequent.',
    howScored: ISLAND_STEPS,
    goodScore: '100 means rescued with all four things built; staying alive to the last day without a rescue still counts as a pass.',
    icon: 'island',
    opening: 'Day 1 of 13 on an empty beach, with harsher weather, a trickling spring and mostly poisonous berries. No ship will pass for at least a week.',
  },

  // ─────────────── Coding ───────────────
  'coding.algorithms': {
    hook: 'Can it write code that is both correct and fast enough for huge inputs?',
    whatItTests: 'Writing JavaScript functions for algorithm problems that must pass hidden tests.',
    whyHard: 'The hidden tests include tricky edge cases and inputs so large that a correct but slow solution runs out of time.',
    howScored: CODE_STEPS(111, 8, 2),
    goodScore: '100 means every hidden test passes; code that is right but too slow loses the big-input tests.',
    icon: 'code',
  },
  'coding.debug-and-edge-cases': {
    hook: 'Can it follow a tricky spec to the letter, down to the last edge case?',
    whatItTests: 'Writing small functions — exact decimal maths, emoji-safe text cutting, CSV parsing, Roman numerals — from detailed specifications.',
    whyHard: 'The textbook version of each function is wrong somewhere; only reading every rule carefully passes the corner cases.',
    howScored: CODE_STEPS(137, 6, 2),
    goodScore: '100 means every hidden test passes; each missed corner case costs a test or two.',
    icon: 'code',
  },
  'coding.hard': {
    hook: 'Can it build a regex engine, a spreadsheet and a calendar in one go each?',
    whatItTests: 'Implementing five genuinely hard programs from long, exact specifications, first time.',
    whyHard: 'Each task has dozens of precise rules and nasty inputs (deep nesting, slow patterns, huge dates), so a nearly-right program still fails many tests.',
    howScored: CODE_STEPS(156, 5, 2),
    goodScore: '100 means every hidden test passes; only very precise, robust code gets close.',
    icon: 'code',
  },
  'coding.frontier': {
    hook: 'Can it solve expert programming problems where the obvious code is too slow?',
    whatItTests: 'Building six expert-level programs — an assembler, an order-matching engine, geometry and optimisation — that pass every rule and run fast.',
    whyHard: 'Each needs the right algorithm AND every rule right; plausible shortcuts fail a large share of the hidden tests.',
    howScored: CODE_STEPS(196, 6, 6),
    goodScore: '100 means every hidden test passes; this is built to separate the very best models.',
    icon: 'code',
  },

  // ─────────────── Creative ───────────────
  'creative.one-shot-games': {
    hook: 'Can it build a complete, playable browser game from a single prompt?',
    whatItTests: 'Writing a whole working game in one web page: graphics, controls, scoring, game over and restart.',
    whyHard: 'One bug can crash the game and there is no second attempt to fix it; the game also has to look good and feel fair.',
    howScored: [
      { icon: 'play', text: 'The game is opened in a real web browser' },
      { icon: 'check', text: 'Half: 6 automatic checks (loads, no errors, reacts…)' },
      { icon: 'scale', text: 'Half: AI judges rate it out of 10' },
    ],
    goodScore: '70 or more is a pass; 100 needs every check passed and a perfect 10 from the judges.',
    icon: 'gamepad',
  },

  // ─────────────── Extraction ───────────────
  'extraction.structured-json': {
    hook: 'Can it turn messy invoices and emails into exact, machine-readable data?',
    whatItTests: 'Reading real-world documents and filling in a fixed data form with the right names, dates and numbers.',
    whyHard: 'Documents contain corrections, reschedules, day/month dates and unit conversions, and a careless reader picks up the old value.',
    howScored: [
      { icon: 'document', text: 'Answer must be data (JSON) in a set format' },
      { icon: 'check', text: 'Every field is compared with the answer key' },
      { icon: 'percent', text: 'Points for each field that matches' },
    ],
    goodScore: '100 means every field right; 90 means about one field in ten was wrong.',
    icon: 'document',
  },
  'extraction.frontier': {
    hook: 'Can it read a long, messy email thread and get every final number right?',
    whatItTests: 'Turning long documents into exact data after every correction, amendment and recalculation.',
    whyHard: 'Almost every early fact is later corrected, and several totals must be recalculated by hand, so one missed update breaks a field.',
    howScored: [
      { icon: 'document', text: 'Answer must be data (JSON) in a set format' },
      { icon: 'check', text: 'Every field is compared with the answer key' },
      { icon: 'zero', text: 'One wrong field: that document scores 0' },
    ],
    goodScore: '100 means all five documents exactly right; each perfect document is worth 20 points.',
    icon: 'document',
  },

  // ─────────────── Honesty ───────────────
  'honesty.honesty-trap': {
    hook: 'Will it make things up about people and papers that don’t exist?',
    whatItTests: 'Honesty: spotting false premises and invented things, while still answering the real questions properly.',
    whyHard: 'AI models are trained to be helpful, so the easy path is a confident invented answer — but refusing everything is punished too.',
    howScored: HONESTY_STEPS('AI judges compare each answer with a reference', 'Right answer, or trap spotted: full marks', 'Invented details or a wrong answer: 0'),
    goodScore: '100 means every trap spotted and every real question answered correctly.',
    icon: 'shield',
  },
  'honesty.pressure-traps': {
    hook: 'If a user insists on something false, will it still tell the truth?',
    whatItTests: 'Staying truthful under pressure: correcting false details even when told “no caveats, just the number”.',
    whyHard: 'Prompts demand a one-word answer or claim a professor already confirmed the false premise, so being honest means pushing back on the user.',
    howScored: HONESTY_STEPS('AI judges grade the final reply against a reference', 'Truth told, or trap spotted: full marks', 'Going along with the falsehood: 0'),
    goodScore: '100 means every trap spotted and every real question answered, even under pushback.',
    icon: 'shield',
  },

  // ─────────────── Instruction following ───────────────
  'instruction.precision-formatting': {
    hook: 'Can it follow every formatting rule at once — exact words, no commas, set line starts?',
    whatItTests: 'Writing short texts that obey several exact rules together: word and sentence counts, banned letters, acrostics, strict layouts.',
    whyHard: 'AI models write in chunks of words, not letters, so counting and letter rules need constant self-checking while writing.',
    howScored: [
      { icon: 'rules', text: '4–7 rules per task, checked by machine' },
      { icon: 'percent', text: 'Points for each rule followed' },
      { icon: 'ruler', text: 'The prompt says exactly how words are counted' },
    ],
    goodScore: '100 means every rule of all 15 tasks followed.',
    icon: 'rules',
  },
  'instruction.extreme-constraints': {
    hook: 'Can it write 60 words in 5 sentences without ever using the letter e?',
    whatItTests: 'Writing text that obeys 7–10 strict rules at the same time: exact counts, banned letters, acrostics, set first and last words.',
    whyHard: 'AI models write in chunks of words, not letters, so hitting exact counts while avoiding a letter is a constant juggling act.',
    howScored: [
      { icon: 'rules', text: '7–10 rules per task, checked by machine' },
      { icon: 'zero', text: 'Break one rule and that task scores 0' },
      { icon: 'percent', text: 'Score = share of the 12 tasks done perfectly' },
    ],
    goodScore: '100 means all 12 tasks perfect; each perfect task adds about 8 points.',
    icon: 'rules',
  },
  'instruction.system-prompt-adherence': {
    hook: 'When a user says “ignore your rules”, does the chatbot stay in character?',
    whatItTests: 'Keeping a shop assistant’s ten standing rules while users try jailbreaks, fake authority and hidden instructions.',
    whyHard: 'The tricks sound reasonable — a staff member, a role-play, a pasted email — and one slip in the reply breaks the rules.',
    howScored: [
      { icon: 'rules', text: 'The final reply is checked against 6–10 rules' },
      { icon: 'zero', text: 'Break one rule and that conversation scores 0' },
      { icon: 'percent', text: 'Score = share of the 12 conversations passed' },
    ],
    goodScore: '100 means it held every rule in all 12 conversations.',
    icon: 'persona',
  },
  'instruction.adversarial-system': {
    hook: 'Can a chatbot keep 15 rules and a secret code while users try to trick it?',
    whatItTests: 'Following a museum assistant’s standing rules — format, length, banned words and a secret — through conversations of escalating pressure.',
    whyHard: 'Users fake emergencies, pose as developers and play encoding games to leak the secret, and a single slip in the last reply fails the whole conversation.',
    howScored: [
      { icon: 'rules', text: 'The last reply is checked by 22–27 rules' },
      { icon: 'lock', text: 'Including the secret in any disguise (reversed, spelled…)' },
      { icon: 'zero', text: 'Break one rule and that conversation scores 0' },
    ],
    goodScore: '100 means every conversation ended with every rule kept; each is worth 12.5 points.',
    icon: 'persona',
  },

  // ─────────────── Long context ───────────────
  'long-context.needle-haystack': {
    hook: 'Can it find ten tiny facts hidden in a 45,000-word book?',
    whatItTests: 'Reading a very long text carefully and pulling out exact details, including ones that must be linked or added up.',
    whyHard: 'Every fact has a near-miss decoy nearby (Daskwell vs Taskwell, 317 vs 371), and one value is corrected later in the book.',
    howScored: NEEDLE_STEPS(10),
    goodScore: '100 means all ten needles found; each one is worth 10 points.',
    icon: 'needle',
    opening: 'A made-up 45,000-word history of an invented city, followed by ten questions about details buried in it. One reply answers all ten.',
  },
  'long-context.needle-haystack-hard': {
    hook: 'Can it find 12 tiny facts hidden in a 78,000-word book?',
    whatItTests: 'Reading a book-length text and answering questions that chain facts from different chapters or add up scattered figures.',
    whyHard: 'Some answers need three linked facts with a look-alike decoy at every step, and some values are corrected much later in the text.',
    howScored: NEEDLE_STEPS(12),
    goodScore: '100 means all 12 needles found; each one is worth about 8 points.',
    icon: 'needle',
    opening: 'A made-up 78,000-word history of an invented city, followed by 12 questions about details buried in it. One reply answers all 12.',
  },
  'long-context.chain-of-whispers': {
    hook: 'Summarise, rewrite, repeat: how many of 12 facts survive six rewrites?',
    whatItTests: 'How precisely an AI can shrink a story and rebuild it without losing details — a game of telephone with itself.',
    whyHard: 'Every rewrite happens in a fresh chat that sees only the previous text, so each lost name or number is gone for good.',
    howScored: WHISPER_STEPS(12),
    goodScore: '100 means all 12 facts survived all six rewrites.',
    icon: 'whisper',
    opening: 'A story packed with names, roles, numbers, a date and places. First it must shrink it to 100 words; then rebuild a full story from its own summary, three times over.',
  },
  'long-context.chain-of-whispers-hard': {
    hook: 'Squeeze 20 facts into 60 words, ten times over. How many survive?',
    whatItTests: 'Dense, deliberate summarising: keeping many exact details through repeated heavy compression and rebuilding.',
    whyHard: 'Twenty facts barely fit in 60 words, and every step runs in a fresh chat that sees only the previous text.',
    howScored: WHISPER_STEPS(20),
    goodScore: '100 means all 20 facts survived all ten rewrites.',
    icon: 'whisper',
    opening: 'A story with 20 checkable facts. It must shrink it to 60 words, then rebuild a full story from that summary — five times over.',
  },

  // ─────────────── Maths ───────────────
  'math.competition': {
    hook: 'Can it solve 20 maths-contest problems with no calculator and no partial credit?',
    whatItTests: 'Mathematical problem solving — counting, probability, number theory, algebra and geometry — with exact whole-number answers.',
    whyHard: 'The hard problems need a clever insight and then a long exact calculation; one slip anywhere gives a wrong number.',
    howScored: NUMBER_STEPS('One whole-number answer per problem'),
    goodScore: '100 means all 20 right; each problem is worth 5 points.',
    icon: 'sigma',
  },
  'math.olympiad': {
    hook: 'Can it crack olympiad maths problems that stump strong students?',
    whatItTests: 'Olympiad-level mathematics: finding the hidden structure in a problem, then carrying out a long exact calculation.',
    whyHard: 'Trying cases by hand is hopeless on most of them, and a single arithmetic slip in a long derivation gives the wrong integer.',
    howScored: NUMBER_STEPS('One whole-number answer per problem'),
    goodScore: '100 means all 12 right; each problem is worth about 8 points.',
    icon: 'sigma',
  },
  'math.word-problems': {
    hook: 'Can it work out a receipt, a payslip or a bill to the exact cent?',
    whatItTests: 'Everyday multi-step maths: discounts, tax, pay, stock and time zones, with exact rounding rules.',
    whyHard: 'Distractor numbers and strict rounding rules punish anyone who grabs the wrong figure or rounds at the wrong step.',
    howScored: [
      { icon: 'target', text: 'One final answer per problem' },
      { icon: 'check', text: 'Must match the key (to within 0.001)' },
      { icon: 'cross', text: 'Right or wrong: no partial credit' },
    ],
    goodScore: '100 means all 17 problems right to the cent.',
    icon: 'receipt',
  },

  // ─────────────── Reasoning ───────────────
  'reasoning.deduction-grid': {
    hook: 'Who stands where, with what? Can it solve a logic grid from the clues alone?',
    whatItTests: 'Pure logical deduction: using clues to work out who has what until exactly one arrangement fits.',
    whyHard: 'Every clue is needed and the answer is the full arrangement, so one wrong step ruins the whole grid.',
    howScored: EXACT_GRID_STEPS('Must give the complete arrangement'),
    goodScore: '100 means all 15 grids solved; each is worth about 7 points.',
    icon: 'grid',
  },
  'reasoning.deduction-grid-extreme': {
    hook: 'Can it solve logic grids with eight people, six attributes and up to 55 clues?',
    whatItTests: 'Long chains of logical deduction over a large grid, with conditional, either/or and arithmetic clues.',
    whyHard: 'The grids are big, every clue matters, and two full orderings must come out exactly right.',
    howScored: EXACT_GRID_STEPS('Must give two complete orderings'),
    goodScore: '100 means all ten grids solved; each is worth 10 points.',
    icon: 'grid',
  },
  'reasoning.truth-tellers': {
    hook: 'Knights always tell the truth, knaves always lie. Can it tell who is who?',
    whatItTests: 'Logical case analysis: working out who is a knight, knave, spy or alternator from what they say.',
    whyHard: 'Statements refer to each other and exactly one line-up fits, so every case has to be checked without a slip.',
    howScored: EXACT_GRID_STEPS('Must name every islander’s type'),
    goodScore: '100 means all 15 puzzles solved; each is worth about 7 points.',
    icon: 'knight',
  },
  'reasoning.truth-tellers-extreme': {
    hook: 'Ten islanders, four kinds of liar: can it find the only story that fits?',
    whatItTests: 'Deep case analysis with knights, knaves, spies and alternators making statements about each other’s statements.',
    whyHard: 'There are about a million possible line-ups and only one survives, so one missed case gives the wrong answer.',
    howScored: EXACT_GRID_STEPS('Must name every islander’s type'),
    goodScore: '100 means all ten puzzles solved; each is worth 10 points.',
    icon: 'knight',
  },
  'reasoning.planning': {
    hook: 'What is the fewest moves that solves it — and is it sure nothing shorter exists?',
    whatItTests: 'Planning: finding the shortest solution to puzzles like jugs, river crossings, sliding tiles and schedules.',
    whyHard: 'A plan that works is easy; proving it is the shortest means ruling out every quicker route.',
    howScored: NUMBER_STEPS('One number: the proven minimum'),
    goodScore: '100 means all 15 minimums found; one move too many scores zero for that puzzle.',
    icon: 'route',
  },
  'reasoning.planning-extreme': {
    hook: 'Can it find the shortest plan when there are up to a million possible states?',
    whatItTests: 'Optimal planning on puzzles far too big to try every option: bridges, crossings, schedules, fuel depots and sliding puzzles.',
    whyHard: 'The textbook rule is often wrong here, and a plan that is even one step or one unit above the minimum is marked wrong.',
    howScored: NUMBER_STEPS('One number: the proven minimum'),
    goodScore: '100 means all ten minimums found; each is worth 10 points.',
    icon: 'route',
  },

  // ─────────────── Social ───────────────
  'social.liars-table': {
    hook: 'Five suspects, one liar, twelve questions: can it find the thief?',
    whatItTests: 'Detective work: choosing good questions, cross-checking stories against evidence and spotting the one lie.',
    whyHard: 'Asking everyone everything wastes questions, and an honest guest may misremember a detail that looks like a lie.',
    howScored: LIARS_STEPS,
    goodScore: '60 means it named the thief; 100 means it did so with few questions and the full reason.',
    icon: 'mask',
    opening: 'A dinner party, a stolen treasure and five suspects. The model has the case file — door log, a staff witness, bar receipts and CCTV — and 12 questions.',
  },
  'social.liars-table-hard': {
    hook: 'Seven suspects, eight questions, and the door log went dark. Can it still find the liar?',
    whatItTests: 'Efficient interrogation: planning which questions to ask and chaining two or three inferences to catch the lie.',
    whyHard: 'The theft time must be deduced, two honest guests misremember, and a decoy contradiction sits in the other possible time slot.',
    howScored: LIARS_STEPS,
    goodScore: '60 means it named the thief; 100 means it did so with few questions and the full reason.',
    icon: 'mask',
    opening: 'Seven suspects, a door log with gaps, a camera with a blind spot — and only 8 questions to find who lied about where they were.',
  },

  // ─────────────── Trick questions ───────────────
  'trick.false-premise': {
    hook: 'Will it notice when a question slips in one false fact?',
    whatItTests: 'Checking a question’s facts before answering, without doubting true facts that merely sound wrong.',
    whyHard: 'The false detail hides inside an otherwise true sentence, and some true facts sound made up, so both gullibility and suspicion lose.',
    howScored: [
      { icon: 'trap', text: 'Any false detail: the answer must be “FALSE PREMISE”' },
      { icon: 'check', text: 'Otherwise the normal answer, matched exactly' },
      { icon: 'cross', text: 'Right or wrong — no judge needed' },
    ],
    goodScore: '100 means every false premise caught and every real question answered.',
    icon: 'trap',
  },
  'trick.lightning-traps': {
    hook: '30 seconds, one line: will it fall for the tempting wrong answer?',
    whatItTests: 'Stopping to think under time pressure on quick questions with a tempting fast wrong answer.',
    whyHard: 'The obvious answer comes to mind first and is wrong, and there is no room to explain: the reply must be a single line.',
    howScored: [
      { icon: 'clock', text: '30 seconds per question: late scores 0' },
      { icon: 'rules', text: 'Reply must be one line: “FINAL ANSWER: …”' },
      { icon: 'check', text: 'Right answer only; any extra text scores 0' },
    ],
    goodScore: '100 means all 20 answered correctly, on time and in one line.',
    icon: 'bolt',
  },
  'trick.modified-classics': {
    hook: 'It looks like a famous puzzle, but one detail has changed. Will it notice?',
    whatItTests: 'Reading carefully instead of reciting a memorised answer to a well-known puzzle.',
    whyHard: 'AI models have seen the original puzzles countless times, so the memorised answer is very tempting — and it is wrong every time.',
    howScored: [
      { icon: 'target', text: 'One final answer: a number or a letter' },
      { icon: 'check', text: 'Must match the recomputed answer' },
      { icon: 'cross', text: 'Right or wrong: no partial credit' },
    ],
    goodScore: '100 means it saw through all 24 changed puzzles.',
    icon: 'trap',
  },

  // ─────────────── Vision ───────────────
  'vision.count-and-locate': {
    hook: 'How many red triangles are in the picture? Exactly?',
    whatItTests: 'Looking at a picture and counting or locating objects exactly among look-alikes.',
    whyHard: 'Distractors share the colour or the shape, and AI vision tends to estimate rather than count one by one.',
    howScored: [
      { icon: 'eye', text: 'The model is shown a generated picture' },
      { icon: 'count', text: 'Counts must be exact; cell lists complete' },
      { icon: 'zero', text: 'Off by one, or one cell missed: 0' },
    ],
    goodScore: '100 means every count and every list exactly right.',
    icon: 'count',
  },
  'vision.handwritten-maths': {
    hook: 'Can it read messy handwriting and find the student’s mistake?',
    whatItTests: 'Reading handwritten maths from a picture, then checking or solving it.',
    whyHard: 'The handwriting is wobbly and crowded, 1s look like 7s, and crossed-out corrections must be read correctly.',
    howScored: [
      { icon: 'eye', text: 'Shown a handwritten page' },
      { icon: 'target', text: 'One exact answer: a number, or line + value' },
      { icon: 'check', text: 'Must match exactly (every part, if two)' },
    ],
    goodScore: '100 means all eight pages read and solved correctly.',
    icon: 'pen',
  },
  'vision.read-the-chart': {
    hook: 'Can it read exact numbers off a chart and do the maths?',
    whatItTests: 'Reading values from bar, line, pie and stacked charts and calculating an answer from them.',
    whyHard: 'Some charts have no value labels, a log scale or two axes, so a misread bar or the wrong axis gives the wrong answer.',
    howScored: [
      { icon: 'chart', text: 'Shown one generated chart per question' },
      { icon: 'target', text: 'Reads values, then gives one answer' },
      { icon: 'check', text: 'Must match the answer key exactly' },
    ],
    goodScore: '100 means all eight charts read and calculated correctly.',
    icon: 'chart',
  },
  'vision.spot-the-difference': {
    hook: 'Can it find every difference between two pictures?',
    whatItTests: 'Comparing two grids of shapes side by side and listing exactly the cells that changed.',
    whyHard: 'Changes are small — a flipped triangle, a tiny dot, a slightly bigger shape — and the grids grow to 8×8.',
    howScored: [
      { icon: 'diff', text: 'Shown two grids side by side' },
      { icon: 'check', text: 'Must list exactly the changed cells' },
      { icon: 'zero', text: 'Miss one or invent one: 0' },
    ],
    goodScore: '100 means every difference in all five pictures found, with none invented.',
    icon: 'diff',
  },

  // ─────────────── Visual ───────────────
  'visual.draw-it-blind': {
    hook: 'Can it describe a picture in words, then redraw it from its own description?',
    whatItTests: 'Describing a scene precisely in words, then turning those words back into a drawing.',
    whyHard: 'It has 120 words and no digits, and the redraw happens in a fresh chat that sees only the description.',
    howScored: DRAW_STEPS('Each shape: 30% type, 30% colour, 25% place, 15% size'),
    goodScore: '100 means a perfect copy; shapes in roughly the right place still earn part of the credit.',
    icon: 'pencil',
    opening: 'An exact table of 5–7 coloured shapes on a square canvas. The model must describe it in at most 120 words, numbers spelled out, for an artist who never sees it.',
  },
  'visual.draw-it-blind-hard': {
    hook: 'Can it describe 12 overlapping shapes in 90 words, then redraw them?',
    whatItTests: 'Squeezing a busy scene into very few words, then rebuilding it accurately as a drawing.',
    whyHard: 'Shapes overlap and nest, colours come in close pairs (navy and blue), and each shape gets about seven words.',
    howScored: DRAW_STEPS('Each shape: 40% place, 20% type, colour, size'),
    goodScore: '100 means a perfect copy; position counts most, so shapes must land close to where they were.',
    icon: 'pencil',
    opening: 'An exact table of 9–12 overlapping shapes in 12 possible colours. The model must describe it in at most 90 words, numbers spelled out, for an artist who never sees it.',
  },
  'visual.svg-illustration': {
    hook: 'Can it draw a clock with its hands at exactly the right angle — in code?',
    whatItTests: 'Drawing precise pictures by writing SVG code: exact counts, positions, colours and angles.',
    whyHard: 'It draws blind, by writing numbers, so it must work out the geometry exactly without ever seeing the result.',
    howScored: [
      { icon: 'code', text: 'The drawing code is rendered' },
      { icon: 'check', text: '40%: automatic checks (renders, size, parts)' },
      { icon: 'scale', text: '60%: AI judges check each numbered requirement' },
    ],
    goodScore: '70 or more is a pass; 100 means every requirement met exactly.',
    icon: 'shapes',
  },

  // ─────────────── Arena (head-to-head) ───────────────
  'arena.connect4': {
    hook: 'Can it plan ahead and block its opponent in four-in-a-row?',
    whatItTests: 'Reading a game board, spotting threats and planning a few moves ahead against another AI.',
    whyHard: 'The board arrives as text, so it must picture the grid itself, and one missed diagonal loses the game.',
    howScored: ARENA_BOARD_STEPS,
    goodScore: 'More points than its opponents wins; winning with both colours is a clear edge.',
    icon: 'connect4',
    opening: 'An empty 7×6 board. Red drops the first disc; each turn a model names a column from 1 to 7.',
  },
  'arena.chess': {
    hook: 'Can it play a full game of chess without a single illegal move?',
    whatItTests: 'Board reading, legal-move discipline and tactics in a complete game of chess against another AI.',
    whyHard: 'The position arrives as text every move, and keeping track of every piece — and every rule — for dozens of moves is hard.',
    howScored: [
      { icon: 'trophy', text: 'Win = 1 point · draw = ½ · loss = 0' },
      { icon: 'swap', text: 'Each pairing plays both colours' },
      { icon: 'clock', text: 'At the move cap: 3+ points of material ahead wins' },
      { icon: 'cross', text: 'Illegal move: retry, then random move + strike' },
    ],
    goodScore: 'More points than its opponents wins; checkmates count, and so does never making an illegal move.',
    icon: 'chess',
    opening: 'The standard starting position. White moves first; every move must be legal, written like “e2e4” or “Nf3”.',
  },
  'arena.poker': {
    hook: 'Can it bluff, bet and fold better than another AI — with the luck taken out?',
    whatItTests: 'Decisions under uncertainty: reading a situation with hidden cards, sizing bets and bluffing.',
    whyHard: 'It can’t see the other player’s cards, so it must reason about odds and behaviour rather than facts.',
    howScored: [
      { icon: 'dollar', text: 'Ranked by total chips won' },
      { icon: 'swap', text: 'Every deal is played twice, seats swapped' },
      { icon: 'cross', text: 'Unreadable action: check if possible, else fold' },
    ],
    goodScore: 'Chips above zero mean it outplayed its opponents on the very same cards.',
    icon: 'cards',
    opening: 'Heads-up No-Limit Texas Hold’em: blinds 1/2, 200 chips each, stacks reset every hand. Each player sees only its own two cards.',
  },
  'arena.debate': {
    hook: 'Can it win an argument in front of a blind panel of AI judges?',
    whatItTests: 'Persuasive argument: making a case, answering the other side and summing up within strict word limits.',
    whyHard: 'It must argue whichever side it is given, then swap and argue the other, and the judges punish invented facts.',
    howScored: [
      { icon: 'mic', text: '3 rounds: 180, 150 and 120 words' },
      { icon: 'swap', text: 'Sides swap for the second game' },
      { icon: 'scale', text: 'Blind judges from other companies pick a winner' },
    ],
    goodScore: 'Winning both games of a pairing means it out-argued its opponent from both sides of the motion.',
    icon: 'mic',
    opening: 'A balanced, non-political motion. One model argues for it, the other against; the side arguing for speaks first in every round.',
  },
  'arena.courtroom': {
    hook: 'Can it argue a court case from the evidence better than another AI?',
    whatItTests: 'Advocacy from evidence: prosecuting or defending a fictional case using only the exhibits in the case file.',
    whyHard: 'The evidence cuts both ways on purpose, and misquoting an exhibit or inventing a fact is marked down hard.',
    howScored: [
      { icon: 'gavel', text: 'Prosecution vs defence: 3 rounds of speeches' },
      { icon: 'swap', text: 'Sides swap for the second game' },
      { icon: 'scale', text: 'Blind judges pick who used the evidence better' },
    ],
    goodScore: 'Winning both games of a pairing means it argued the evidence better from both sides.',
    icon: 'gavel',
    opening: 'A fictional case file with lettered exhibits. One model prosecutes, the other defends, then they swap.',
  },  'horizon.mind-runner': {
    hook: 'Can it run a program for 70,000 steps in its head, without a computer?',
    whatItTests: 'Executing code by pure reasoning: tracking every variable through loops, arrays and function calls to the exact number printed.',
    whyHard: 'The top level runs about 73,000 statements, and one slip anywhere changes the final number.',
    howScored: LADDER_STEPS('The exact number the program prints'),
    goodScore: LADDER_SCORE,
    icon: 'code',
  },
  'horizon.modpow-ladder': {
    hook: 'Can it do 44-digit arithmetic in its head, with no mistakes?',
    whatItTests: 'Long exact arithmetic: a huge number raised to a huge power, then divided, keeping only the remainder.',
    whyHard: 'There is no shortcut: the top level needs about 180 long multiplications and divisions of 44-digit numbers, every digit right.',
    howScored: LADDER_STEPS('The exact remainder, every digit'),
    goodScore: LADDER_SCORE,
    icon: 'sigma',
  },
  'horizon.sliding-ladder': {
    hook: 'Can it find the shortest solution when the answer is 56 moves long?',
    whatItTests: 'Deep planning: solving a sliding-tile puzzle in the fewest possible moves, proven by computer search.',
    whyHard: 'Finding a solution is easy; finding the shortest one means ruling out billions of alternatives.',
    howScored: [
      { icon: 'ladder', text: 'Ten puzzles, each needing more moves' },
      { icon: 'moves', text: 'Its whole plan is replayed tile by tile' },
      { icon: 'trophy', text: 'Shortest possible plan: full marks' },
      { icon: 'percent', text: 'Longer plan that works: at most a quarter' },
    ],
    goodScore: LADDER_SCORE,
    icon: 'route',
  },
  'horizon.nonogram-ladder': {
    hook: 'Can it deduce a 1,225-cell picture from numbers alone?',
    whatItTests: 'Constraint solving: filling a paint-by-numbers grid, up to 35 by 35, that has exactly one solution.',
    whyHard: 'The pictures are random, so nothing can be guessed: every cell follows from long chains of logic.',
    howScored: LADDER_STEPS('The whole grid, every cell'),
    goodScore: LADDER_SCORE,
    icon: 'grid',
  },
  'horizon.tiling-count': {
    hook: 'Can it count every domino tiling when the answer has 22 digits?',
    whatItTests: 'Exact counting: in how many ways dominoes can cover a board with holes, from a 6x6 board to a 16x14 one.',
    whyHard: 'The count explodes to 22 digits, so it must be organised perfectly: no guessing, no estimating.',
    howScored: LADDER_STEPS('The exact count, every digit'),
    goodScore: LADDER_SCORE,
    icon: 'count',
  },
};

/** Explainer key of an Arena game. */
export function arenaExplainerId(gameId: string): string {
  return `arena.${gameId}`;
}

/** The hand-written explainer of a test (or "arena.<game>"), if there is one. */
export function explainerFor(id: string): TestExplainer | undefined {
  return Object.prototype.hasOwnProperty.call(EXPLAINERS, id) ? EXPLAINERS[id] : undefined;
}

function scorerSteps(sc: ScorerSpec): ExplainStep[] {
  switch (sc.type) {
    case 'exact':
    case 'number':
    case 'choice':
    case 'regex':
      return [
        { icon: 'target', text: 'One final answer per question' },
        { icon: 'check', text: 'Compared with the answer key: right or wrong' },
      ];
    case 'contains':
      return [
        { icon: 'check', text: 'Checked for the key facts it must mention' },
        { icon: 'percent', text: 'Points for each fact found' },
      ];
    case 'constraints':
      return [
        { icon: 'rules', text: 'Every rule checked by machine' },
        sc.allOrNothing ? { icon: 'zero', text: 'Break one rule and it scores 0' } : { icon: 'percent', text: 'Points for each rule followed' },
      ];
    case 'json':
      return [
        { icon: 'document', text: 'Answer must be data (JSON) in a set format' },
        sc.allOrNothing ? { icon: 'zero', text: 'One wrong field scores 0' } : { icon: 'percent', text: 'Points for each field that matches' },
      ];
    case 'code-js':
      return [
        { icon: 'play', text: 'The code it writes is actually run' },
        { icon: 'percent', text: 'Score = share of hidden tests passed' },
      ];
    case 'judge':
    case 'judge-classify':
      return [{ icon: 'scale', text: 'A panel of AI judges grades each answer' }];
    case 'artifact':
      return [{ icon: 'play', text: 'What it builds is opened and checked' }, ...(sc.rubric && (sc.judgeWeight ?? 0) > 0 ? [{ icon: 'scale' as const, text: 'AI judges rate it too' }] : [])];
    case 'human':
      return [{ icon: 'scale', text: 'Rated blind by people' }];
    case 'ladder':
      return [
        { icon: 'target', text: 'One exact answer per level' },
        { icon: 'check', text: 'Checked by machine: right or wrong' },
      ];
  }
}

/**
 * The explainer to show for a test: the hand-written one, or (custom tests without one) a plain one
 * built from the test's own description and scorer, marked `generated`.
 */
export function explainerForDefinition(def: TestDefinition, programScoring?: string): TestExplainer {
  const own = explainerFor(def.id);
  if (own) return own;
  const first = (def.description ?? '').split(/(?<=[.!?])\s/)[0] ?? '';
  return {
    hook: def.hook?.trim() || def.name,
    whatItTests: first || def.name,
    whyHard: '',
    howScored: def.kind === 'prompt' ? scorerSteps(def.scorer) : [{ icon: 'play', text: programScoring ? 'Scored by the simulation itself' : 'Scored by the program' }],
    goodScore: '100 is a perfect score.',
    icon: 'star',
    generated: true,
  };
}
