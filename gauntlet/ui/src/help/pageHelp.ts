/**
 * Plain-English help for every page: the first-visit guide (a dismissible banner) and the "?" Help panel.
 * One entry per page key (ui/src/simple/nav.ts). test/simple-mode.test.ts fails if a route has no entry,
 * so a new page can't ship without help.
 *
 * Writing style: short sentences, no jargon, say what to click. "Guide" text is 1–3 sentences.
 */
import type { PageKey } from '../simple/nav.ts';

export interface PageHelp {
  /** Panel heading. */
  title: string;
  /** What this page is for, in one or two sentences. */
  purpose: string;
  /** How to use it, step by step. */
  steps: string[];
  /** Common questions. */
  faqs: Array<{ q: string; a: string }>;
  /** Related pages in the app. */
  links: Array<{ label: string; to: string }>;
  /** The longer written guide: a section of a file in the Gauntlet folder. */
  doc?: { file: string; section: string };
  /** First-visit banner (1–3 sentences) and its one "Next step" button. Omit for pages that explain themselves. */
  guide?: { text: string; next?: { label: string; to: string } };
}

const PLAYBOOK = 'docs/PLAYBOOK.md';

const RUN_DETAIL: PageHelp = {
  title: 'One run’s results',
  purpose: 'Everything one test run produced: who won, every score, and every question and answer.',
  steps: [
    'The ranking at the top shows who did best in this run.',
    'Click any coloured cell to see the exact question, the model’s answer and why it scored what it did.',
    'Simulations and games have a Replay button: step through what the model did, move by move.',
    'Press “Present” to turn this run into video slides.',
  ],
  faqs: [
    { q: 'Why is a cell grey or empty?', a: 'That model skipped the question (for example a picture question for a model that can’t see pictures) or it hasn’t been answered yet.' },
    { q: 'Something failed. Can I finish it?', a: 'Yes. Press Resume: finished answers are kept and only the missing ones run again.' },
  ],
  links: [
    { label: 'Make video slides', to: '/present' },
    { label: 'All past runs', to: '/runs' },
  ],
  doc: { file: PLAYBOOK, section: '4. Record' },
  guide: { text: 'This is one run’s results. Click any coloured cell to see the question, the answer and how it was marked.', next: { label: 'Make slides from it', to: '/present' } },
};

const ARENA_GAME: PageHelp = {
  title: 'Tournament',
  purpose: 'One Arena tournament: the bracket or table, every game, and who is winning.',
  steps: ['Watch games live as they are played, or open a finished game to replay it move by move.', 'The table shows wins, draws and losses so far.', 'Press Broadcast (B) for a clean full-screen view to record.'],
  faqs: [{ q: 'Is it fair?', a: 'Yes. Every model gets the same rules, plays both sides where that matters, and never sees the other model’s thinking.' }],
  links: [
    { label: 'All tournaments', to: '/arena' },
    { label: 'Start a tournament', to: '/arena/new' },
  ],
  doc: { file: PLAYBOOK, section: 'Arena episode (models play each other)' },
};

export const PAGE_HELP: Record<PageKey, PageHelp> = {
  home: {
    title: 'Home',
    purpose: 'Your starting point. It shows what to do next, how your last test went and what you’ve spent this month.',
    steps: [
      'Follow the “Getting started” steps from the top: each one has a big button.',
      'Or pick a card under “What do you want to do?”.',
      'Use the sidebar on the left to move around. “Show all tools” at the bottom reveals every page.',
    ],
    faqs: [
      { q: 'Where did the leaderboard go?', a: 'It’s under Results in the sidebar (or the “See the leaderboard” card).' },
      { q: 'Where are all the other pages?', a: 'Press “Show all tools” at the bottom of the sidebar. Nothing has been removed.' },
      { q: 'Will these tips show up in my video?', a: 'No. Press B for Broadcast mode before recording: tips, the Help button and the checklist all hide.' },
    ],
    links: [
      { label: 'Run a test', to: '/run/new' },
      { label: 'See the leaderboard', to: '/leaderboard' },
    ],
    doc: { file: PLAYBOOK, section: 'Episode playbook' },
  },
  leaderboard: {
    title: 'Leaderboard',
    purpose: 'The ranking of every model across your runs, with a score out of 100 and how it did in each kind of test.',
    steps: [
      'Pick a set of tests at the top right (Core is the main one).',
      'The big number is the Gauntlet Index: 100 means full marks on everything.',
      'The coloured columns break the score down by category (logic, maths, coding…).',
      'Scroll down for the score-vs-cost chart and the medal table.',
    ],
    faqs: [
      { q: 'Why is a model missing?', a: 'It hasn’t been run on this set of tests yet. Run a test that includes it.' },
      { q: 'What is the Random Baseline?', a: 'A pretend model that guesses at random. It shows the score you’d get by luck, so every real model should beat it.' },
      { q: 'What are “stale results”?', a: 'Results recorded before a test or a model’s settings changed. They’re left out so the comparison stays fair.' },
    ],
    links: [
      { label: 'Best on each test', to: '/best' },
      { label: 'How scoring works', to: '/methodology' },
    ],
    guide: { text: 'This is the overall ranking. The big number is a score out of 100; the coloured columns show each kind of test.', next: { label: 'See the winner of each test', to: '/best' } },
  },
  'run-new': {
    title: 'Run a test',
    purpose: 'Choose which AI models to test and which tests to give them, see the price, and press Start.',
    steps: [
      'Step 1: pick a set of tests. New here? Choose “Quick Check” (about 2p).',
      'Step 2: tick the models you want to compare.',
      'Check the cost estimate on the right. Set a spending cap if you like.',
      'Press Start run. You’ll watch the answers come in live.',
    ],
    faqs: [
      { q: 'How much will it cost?', a: 'The estimate on the right is worked out before you start, and the spending cap stops the run if it would go over.' },
      { q: 'Can I try it for free?', a: 'Yes: tick only “Random Baseline”. It needs no key and costs nothing.' },
      { q: 'How do I test a chatbot that has no API key?', a: 'Press “Test models by copy & paste” lower down, then answer its questions in the Copy & paste Inbox.' },
    ],
    links: [
      { label: 'Add an API key', to: '/keys' },
      { label: 'Plan costs', to: '/costs' },
      { label: 'Copy & paste Inbox', to: '/inbox' },
    ],
    doc: { file: PLAYBOOK, section: '3. Run for real' },
    guide: { text: 'Pick a set of tests, tick the models, check the price on the right, then press Start run. First time? The Quick Check costs about 2p.', next: { label: 'Use the Quick Check', to: '/run/new?suite=quick-check&models=cheap' } },
  },
  runs: {
    title: 'Past runs',
    purpose: 'Every test run you’ve done, newest first, with its status, models and cost.',
    steps: ['Click a run to see its results.', 'A run that stopped early can be resumed: finished answers are kept.', 'Delete runs you don’t need any more with the bin button.'],
    faqs: [
      { q: 'What does “Interrupted” mean?', a: 'Gauntlet was closed while the run was going. Press Resume to finish it.' },
      { q: 'What does “stopped by spend limit” mean?', a: 'It reached the spending cap you set. Resume it with a higher cap if you want to finish.' },
    ],
    links: [
      { label: 'Run a test', to: '/run/new' },
      { label: 'Leaderboard', to: '/leaderboard' },
    ],
    guide: { text: 'Every test you’ve run is listed here. Click one to see its results.' },
  },
  'run-detail': RUN_DETAIL,
  'run-live': {
    title: 'Live view',
    purpose: 'Watch a run as it happens: every model answering side by side, scores landing and money ticking up.',
    steps: ['Leave this page open while the run works; you can also close it, the run carries on.', 'Press B for Broadcast mode to record it.', 'When it finishes, press “Open full results”.'],
    faqs: [
      { q: 'Can I close the browser?', a: 'Yes. The run keeps going as long as the black Gauntlet window stays open.' },
      { q: 'How do I stop it?', a: 'Press Cancel. Anything already answered is kept.' },
    ],
    links: [{ label: 'Past runs', to: '/runs' }],
    doc: { file: PLAYBOOK, section: 'Recording a run live' },
    guide: { text: 'Your test is running. Answers appear as each model replies; you can leave this page and come back.' },
  },
  'run-watch': {
    title: 'Watch it think',
    purpose: 'A close-up of every answer typing in, with a verdict flash and live commentary. Made for recording.',
    steps: ['Press B for Broadcast mode.', 'Let it play; each answer is marked as soon as it finishes.'],
    faqs: [{ q: 'Is the commentary made up?', a: 'No. Every line is written from the real answers and scores.' }],
    links: [{ label: 'Past runs', to: '/runs' }],
    doc: { file: PLAYBOOK, section: 'Recording a run live' },
  },
  'run-jam': {
    title: 'The Game Jam',
    purpose: 'The games each model built, side by side, playable in the browser.',
    steps: ['Click a game to play it.', 'The scores come from a scripted player and a panel of judges.'],
    faqs: [{ q: 'Why is a game blank?', a: 'That model’s game didn’t load or crashed. That counts against it.' }],
    links: [{ label: 'Past runs', to: '/runs' }],
    doc: { file: PLAYBOOK, section: 'Running the Game Jam' },
  },
  tests: {
    title: 'Test library',
    purpose: 'Every test Gauntlet can give a model: what it asks, how it is marked, and roughly what it costs.',
    steps: ['Click a test to see a sample question and the marking rules.', 'Press “New test” to write your own.'],
    faqs: [{ q: 'Do models see the answers?', a: 'Never. Answer keys stay on your computer and are only used for marking.' }],
    links: [
      { label: 'Run a test', to: '/run/new' },
      { label: 'How scoring works', to: '/methodology' },
    ],
    doc: { file: 'docs/ADDING_TESTS.md', section: 'Adding tests' },
    guide: { text: 'These are all the tests Gauntlet can run. Click one to see a sample question and how it is marked.' },
  },
  'test-detail': {
    title: 'About this test',
    purpose: 'What this test asks, a sample question, and exactly how answers are marked.',
    steps: ['Read the sample question.', 'Press “Show answer” to see what counts as right.', 'Run it from New Run (hand-pick tests).'],
    faqs: [{ q: 'Can I change the questions?', a: 'Built-in tests are fixed so results stay comparable. Make your own with “New test”.' }],
    links: [{ label: 'Test library', to: '/tests' }],
  },
  'test-builder': {
    title: 'Test builder',
    purpose: 'Write your own test: the question, the expected answer and how it should be marked.',
    steps: ['Fill in the name and question.', 'Choose how it is marked (exact answer, rules, or AI judges).', 'Press Validate, then Save.'],
    faqs: [{ q: 'What if I change a saved test?', a: 'Bump its version number: old results are kept separate so nothing gets mixed up.' }],
    links: [{ label: 'Test library', to: '/tests' }],
    doc: { file: 'docs/ADDING_TESTS.md', section: 'Adding tests' },
  },
  models: {
    title: 'Models',
    purpose: 'Which AI models can take part, their prices, and which ones are switched on.',
    steps: ['Switch a model on or off with its toggle.', 'Press “Ping” to check a model answers.', 'Press “Add model” to add a new one.'],
    faqs: [
      { q: 'A model says it has no key.', a: 'Add a key for its company on the API keys page, or one OpenRouter key for everything.' },
      { q: 'Can I add a chatbot with no API?', a: 'Yes: choose “Manual (copy & paste)” as its provider, then answer its questions in the Inbox.' },
    ],
    links: [
      { label: 'API keys', to: '/keys' },
      { label: 'Run a test', to: '/run/new' },
    ],
    guide: { text: 'These are the AI models Gauntlet knows about. Models with a key can run straight away.', next: { label: 'Add a key', to: '/keys' } },
  },
  keys: {
    title: 'API keys',
    purpose: 'Paste an API key so Gauntlet can talk to an AI company. Keys are saved on this computer only.',
    steps: [
      'Get a key: OpenRouter is easiest (one key for every AI). The “Get a key” buttons show you where.',
      'Click in the big box and press Ctrl+V.',
      'Gauntlet works out which company it’s from, checks it for free and saves it.',
    ],
    faqs: [
      { q: 'Is my key safe?', a: 'It is saved in a file on this computer, never in the Gauntlet folder and never sent anywhere except to that AI company.' },
      { q: 'It says the account has no credit.', a: 'Add a little credit (about £5) on that company’s billing page. The key is already saved.' },
      { q: 'Do I need a key for every company?', a: 'No. One OpenRouter key reaches nearly every model.' },
    ],
    links: [
      { label: 'Run your first test', to: '/run/new?suite=quick-check&models=cheap' },
      { label: 'Set a monthly budget', to: '/budget' },
    ],
    doc: { file: 'SETUP-WINDOWS.md', section: 'Adding more keys' },
    guide: { text: 'Paste a key into the big box and you’re done: Gauntlet checks it for free and saves it on this computer.', next: { label: 'Then run a test', to: '/run/new?suite=quick-check&models=cheap' } },
  },
  review: {
    title: 'Blind review',
    purpose: 'Score answers yourself without knowing which model wrote them, so your opinion can’t be biased.',
    steps: ['Read the question and the answer.', 'Give it a score.', 'The model’s name is revealed only after you score.'],
    faqs: [{ q: 'Why is it empty?', a: 'Only tests that need a human’s opinion end up here. Run one of those first.' }],
    links: [{ label: 'Grading Station', to: '/grading' }],
    doc: { file: PLAYBOOK, section: '5. Review' },
    guide: { text: 'Answers that need a human’s opinion wait here. You score them without seeing which model wrote them.' },
  },
  grading: {
    title: 'Grading Station',
    purpose: 'Mark answers that need a person (or AI judges): one answer at a time, with the marking guide beside it.',
    steps: ['Pick a run.', 'Read each answer and mark it with the keys shown, or ask the AI judges.', 'Your marks feed straight into the results.'],
    faqs: [{ q: 'Do AI judges cost money?', a: 'A little. The price is shown before anything is spent.' }],
    links: [
      { label: 'Copy & paste Inbox', to: '/inbox' },
      { label: 'Blind review', to: '/review' },
    ],
    doc: { file: PLAYBOOK, section: 'Grading: you, the AI, or both' },
    guide: { text: 'Mark answers that need a person here, one at a time. The marking guide sits right beside each answer.' },
  },
  methodology: {
    title: 'How scoring works',
    purpose: 'The rules that keep every test fair: same questions for everyone, hidden answers, and how scores are added up.',
    steps: ['Read it once; it’s also the page to point viewers to if they ask how it works.'],
    faqs: [{ q: 'Can a judge mark its own company’s model?', a: 'Never. Judges from the same company are left out automatically.' }],
    links: [{ label: 'Viewer guide', to: '/guide' }],
    doc: { file: 'docs/METHODOLOGY.md', section: 'Methodology' },
  },
  guide: {
    title: 'Viewer guide',
    purpose: 'Plain-English explanations of every term on screen, for your viewers. It can be shown full screen in a video.',
    steps: ['Scroll through the terms.', 'Press “Full screen” to step through them one at a time for a recording.'],
    faqs: [],
    links: [{ label: 'How scoring works', to: '/methodology' }],
  },
  gallery: {
    title: 'The Gallery',
    purpose: 'The pictures models painted for the art test, hung like an exhibition, with their scores.',
    steps: ['Pick a run.', 'Click a picture to see the brief and how it was judged.', 'Run a blind vote to let people pick favourites without knowing who painted what.'],
    faqs: [{ q: 'Why is the Gallery empty?', a: 'Run the Art suite first (pick “Art” in Run a test).' }],
    links: [{ label: 'Run a test', to: '/run/new?suite=art' }],
    doc: { file: PLAYBOOK, section: 'Running the Gallery test (AI art)' },
    guide: { text: 'This is where the art test’s pictures hang. Click any painting to see its brief and score.' },
  },
  'gallery-vote': {
    title: 'Blind vote',
    purpose: 'Show two pictures at a time with no names, and pick the better one.',
    steps: ['Click the picture you prefer.', 'Names are revealed at the end.'],
    faqs: [],
    links: [{ label: 'The Gallery', to: '/gallery' }],
  },
  inbox: {
    title: 'Copy & paste Inbox',
    purpose: 'Test any chatbot that has no API key: copy each question into its chat, then paste its reply back here.',
    steps: [
      'Start a copy & paste test first (Run a test → “Test models by copy & paste”).',
      'Click a question, press Copy, paste it into the chatbot.',
      'Copy the chatbot’s whole reply and paste it into the box here. It’s marked straight away.',
    ],
    faqs: [
      { q: 'Can I edit the question?', a: 'No. Paste it exactly as shown, use a fresh chat, and turn off web search. Otherwise it isn’t a fair test.' },
      { q: 'The chatbot refused or failed.', a: 'Press “Mark failed…”. It counts as a fail, just like an API model that errors.' },
    ],
    links: [
      { label: 'Start a copy & paste test', to: '/run/new?copy=1' },
      { label: 'Grading Station', to: '/grading' },
    ],
    doc: { file: PLAYBOOK, section: 'Testing old or chat-only models by copy & paste' },
    guide: { text: 'Questions for chatbots without an API key wait here. Copy each one into the chat, then paste the reply back.', next: { label: 'Start a copy & paste test', to: '/run/new?copy=1' } },
  },
  grade: {
    title: 'Grade one answer',
    purpose: 'Paste any reply to any test question and see the score it would get, without starting a run.',
    steps: ['Pick a test and question.', 'Paste the reply.', 'Press Grade.'],
    faqs: [{ q: 'Does this go on the leaderboard?', a: 'No. It’s a quick check only. Use a copy & paste run for results that count.' }],
    links: [{ label: 'Copy & paste Inbox', to: '/inbox' }],
  },
  costs: {
    title: 'Cost planner',
    purpose: 'Work out what a run would cost before you start it, for any mix of tests and models.',
    steps: ['Pick a set of tests and the models.', 'Read the table: every test × model, plus a safe upper limit.'],
    faqs: [{ q: 'Why is the upper limit higher than the estimate?', a: 'Some answers are longer than expected. The upper limit assumes the worst so you’re never surprised.' }],
    links: [
      { label: 'Run a test', to: '/run/new' },
      { label: 'Monthly budget', to: '/budget' },
    ],
    doc: { file: 'docs/COSTS.md', section: 'Costs' },
    guide: { text: 'See what a run would cost before you spend anything. Pick tests and models, and the table fills in.' },
  },
  budget: {
    title: 'Monthly budget',
    purpose: 'Set how much you’re happy to spend each month, in pounds, and see what you’ve spent so far.',
    steps: ['Type a monthly limit and press Save.', 'Turn on “Hard stop” to block anything that would go over.', 'The list shows every run and what it cost.'],
    faqs: [{ q: 'Is this my real bill?', a: 'It’s Gauntlet’s own record of what it spent. Your AI company’s billing page is the final word.' }],
    links: [{ label: 'Cost planner', to: '/costs' }],
    doc: { file: PLAYBOOK, section: 'Your own budget' },
    guide: { text: 'Set a monthly limit in pounds and Gauntlet will never spend more than that.' },
  },
  publish: {
    title: 'Publish',
    purpose: 'Turn your results into a small website for viewers: preview it, or download it as a .zip to upload anywhere.',
    steps: ['Pick which sets of tests to include and fill in your channel’s name.', 'Press “Publish website”.', 'Press “Preview the site” to check it, or “Download as .zip” to upload it.'],
    faqs: [{ q: 'Are my keys included?', a: 'Never. Only results and settings without keys are exported.' }],
    links: [{ label: 'Leaderboard', to: '/leaderboard' }],
    doc: { file: 'docs/PUBLISHING.md', section: 'Publishing' },
  },
  newmodel: {
    title: 'New Model Day',
    purpose: 'A new model just came out? Add it, check it works, see the price, run it and find out where it ranks, in five steps.',
    steps: ['Step 1: add the model (its name and price).', 'Step 2: check it answers.', 'Steps 3 to 5: check the cost, run it and read the verdict.'],
    faqs: [],
    links: [{ label: 'Models', to: '/models' }],
    doc: { file: 'docs/CHANNEL.md', section: 'New Model Day' },
  },
  history: {
    title: 'History',
    purpose: 'How models’ scores have changed from run to run and version to version.',
    steps: ['Pick models to follow.', 'Hover a point to see the run it came from.'],
    faqs: [{ q: 'Why is it empty?', a: 'History needs at least two runs of the same tests.' }],
    links: [{ label: 'Past runs', to: '/runs' }],
    guide: { text: 'This chart shows how models have changed over time. It fills in as you run the same tests again.' },
  },
  challenge: {
    title: 'Viewer Challenge',
    purpose: 'Your viewers write the questions. You check them, and the approved ones become a private test no AI has ever seen.',
    steps: ['Import submissions (for example a Google Forms CSV) or paste them in.', 'Approve or reject each one.', 'Run the approved questions as a test.'],
    faqs: [],
    links: [{ label: 'Make a video', to: '/present' }],
    doc: { file: 'docs/CHANNEL.md', section: 'Viewer Challenge' },
  },
  studio: {
    title: 'Studio',
    purpose: 'Everything for the edit: highlight moments, a script, thumbnail cards and OBS overlays for a run.',
    steps: ['Pick a run.', 'Use the tabs for highlights, script and thumbnails.', 'Download cards or copy the overlay link into OBS.'],
    faqs: [{ q: 'Does polishing the script cost money?', a: 'A little, and the price is shown before you press go.' }],
    links: [{ label: 'Presenter', to: '/present' }],
    doc: { file: PLAYBOOK, section: '4b. Making the video (Studio)' },
    guide: { text: 'Studio gives you highlights, a script and thumbnails for any run. Pick a run to start.' },
  },
  overlay: {
    title: 'OBS overlay',
    purpose: 'A transparent scoreboard to add to OBS as a browser source while you record.',
    steps: ['Copy this page’s address.', 'In OBS add a Browser source and paste it.'],
    faqs: [],
    links: [{ label: 'Studio', to: '/studio' }],
  },
  slides: {
    title: 'Channel slides',
    purpose: 'Full-screen slides for channel features such as History and New Model Day.',
    steps: ['Use the arrow keys to move between slides.', 'Press Esc to go back.'],
    faqs: [],
    links: [{ label: 'Presenter', to: '/present' }],
  },
  arena: {
    title: 'Tournaments (the Arena)',
    purpose: 'Models play games against each other (chess, poker, debates and more) in a bracket or league.',
    steps: ['Press “New tournament”.', 'Pick a game and the models.', 'Watch it live or replay any game afterwards.'],
    faqs: [{ q: 'How much does a tournament cost?', a: 'It depends on the game and the number of models. The price is shown before you start.' }],
    links: [{ label: 'New tournament', to: '/arena/new' }],
    doc: { file: PLAYBOOK, section: 'Arena episode (models play each other)' },
    guide: { text: 'In the Arena, models play games against each other. Start a tournament and watch who wins.', next: { label: 'New tournament', to: '/arena/new' } },
  },
  'arena-new': {
    title: 'New tournament',
    purpose: 'Set up a tournament: which game, which models and what format.',
    steps: ['Pick a game.', 'Tick the models.', 'Check the price and press Start.'],
    faqs: [],
    links: [{ label: 'All tournaments', to: '/arena' }],
  },
  'arena-tournament': ARENA_GAME,
  'arena-game': { ...ARENA_GAME, title: 'Arena game', purpose: 'One game from a tournament, move by move.' },
  'arena-judge': {
    title: 'Human judging',
    purpose: 'Judge Arena rounds yourself (for example debates) without knowing which model is which.',
    steps: ['Read both sides.', 'Pick the winner.'],
    faqs: [],
    links: [{ label: 'All tournaments', to: '/arena' }],
  },
  versus: {
    title: 'Head to Head',
    purpose: 'Put two models side by side on the same questions: who won each one, and by how much.',
    steps: ['Pick two models.', 'Choose the runs to compare.', 'Press “Present” for full-screen versus slides.'],
    faqs: [{ q: 'Why are there no questions to compare?', a: 'Both models need to have answered the same tests. Run a test with both of them first.' }],
    links: [{ label: 'Run a test', to: '/run/new' }],
    doc: { file: PLAYBOOK, section: 'Making a versus video' },
    guide: { text: 'Pick two models to put side by side. Every question they both answered is compared.' },
  },
  best: {
    title: 'Best on each test',
    purpose: 'The winning model on every single test, so you can say “X is best at maths, Y is best at code”.',
    steps: ['Scroll the list of tests.', 'Click one to see the top models and their scores.'],
    faqs: [{ q: 'Why is a test missing?', a: 'No run has included it yet.' }],
    links: [{ label: 'Leaderboard', to: '/leaderboard' }],
    guide: { text: 'See which model won each individual test.' },
  },
  present: {
    title: 'Presenter (video slides)',
    purpose: 'Turn any run into full-screen 16:9 slides that explain themselves. Record them for your video.',
    steps: [
      'Pick a run from the list.',
      'Use the arrow keys (or space) to move through the slides.',
      'Press F for full screen, then start your screen recorder.',
    ],
    faqs: [
      { q: 'Can I present a run that isn’t finished?', a: 'Yes. Missing tests say so on their slide.' },
      { q: 'Can I change what the slides say?', a: 'No, and that’s on purpose: every number and sentence comes from the real results, so the video is always honest.' },
    ],
    links: [
      { label: 'Head to Head slides', to: '/versus' },
      { label: 'Studio', to: '/studio' },
    ],
    doc: { file: PLAYBOOK, section: 'Running the show (Presenter)' },
    guide: { text: 'Pick a run and it becomes full-screen slides that explain themselves. Use the arrow keys to move, F for full screen.' },
  },
  unknown: {
    title: 'Help',
    purpose: 'This page doesn’t have its own help yet.',
    steps: ['Use Home to find your way back, or press “Show all tools” in the sidebar to see every page.'],
    faqs: [],
    links: [{ label: 'Home', to: '/' }],
  },
};

export function helpFor(key: PageKey): PageHelp {
  return PAGE_HELP[key] ?? PAGE_HELP.unknown;
}
