/**
 * The navigation model behind "simple mode" (docs/PLAYBOOK.md → "Finding your way around").
 *
 * Every screen in the app has a page key (pageKeyFor). Pages are grouped into a handful of task-named
 * sections ("Run a test", "Results", "Make a video", …). Simple mode shows one sidebar item per section
 * and puts the section's main pages in tabs at the top; "Show all tools" shows every page, grouped by the
 * same sections. Nothing is removed: every route still works by URL in both modes.
 *
 * Pure data and functions (no React), so the unit tests can check it (test/simple-mode.test.ts).
 */

/** One key per kind of screen. Several URLs can share a key (e.g. every run's detail page is "run-detail"). */
export type PageKey =
  | 'home'
  | 'leaderboard'
  | 'run-new'
  | 'runs'
  | 'run-detail'
  | 'run-live'
  | 'run-watch'
  | 'run-jam'
  | 'tests'
  | 'test-detail'
  | 'test-builder'
  | 'models'
  | 'keys'
  | 'review'
  | 'grading'
  | 'methodology'
  | 'guide'
  | 'gallery'
  | 'gallery-vote'
  | 'inbox'
  | 'grade'
  | 'costs'
  | 'budget'
  | 'publish'
  | 'newmodel'
  | 'history'
  | 'challenge'
  | 'studio'
  | 'overlay'
  | 'slides'
  | 'arena'
  | 'arena-new'
  | 'arena-tournament'
  | 'arena-game'
  | 'arena-judge'
  | 'versus'
  | 'best'
  | 'present'
  | 'unknown';

function splitPath(path: string): string[] {
  return path
    .split('?')[0]!
    .split('/')
    .filter(Boolean);
}

/** The page key for a hash path such as "/runs/run-123/live" (no leading "#"). */
export function pageKeyFor(path: string): PageKey {
  const [a, b, c, d] = splitPath(path);
  if (!a) return 'home';
  switch (a) {
    case 'leaderboard':
      return 'leaderboard';
    case 'run':
      return b === 'new' ? 'run-new' : 'unknown';
    case 'runs':
      if (!b) return 'runs';
      if (c === 'live') return 'run-live';
      if (c === 'watch') return 'run-watch';
      if (c === 'jam') return 'run-jam';
      return 'run-detail';
    case 'tests':
      if (!b) return 'tests';
      if (b === 'new' || c === 'edit') return 'test-builder';
      return 'test-detail';
    case 'models':
    case 'keys':
    case 'review':
    case 'grading':
    case 'methodology':
    case 'inbox':
    case 'grade':
    case 'costs':
    case 'budget':
    case 'publish':
    case 'newmodel':
    case 'history':
    case 'challenge':
    case 'studio':
    case 'overlay':
    case 'slides':
    case 'versus':
    case 'best':
      return a;
    case 'guide':
      return 'guide';
    case 'gallery':
      return b && c === 'vote' ? 'gallery-vote' : 'gallery';
    case 'arena':
      if (!b) return 'arena';
      if (b === 'new') return 'arena-new';
      if (c === 'game' && d) return 'arena-game';
      if (c === 'judge') return 'arena-judge';
      return 'arena-tournament';
    case 'present':
      return b === 'versus' ? 'versus' : 'present';
    default:
      return 'unknown';
  }
}

/** Icon names the sidebar knows how to draw (see simple/icons.tsx). */
export type NavIconName = 'home' | 'run' | 'results' | 'video' | 'copy' | 'settings' | 'about';

export interface SectionTab {
  label: string;
  to: string;
  /** Pages that light this tab up. */
  keys: PageKey[];
  /** Tooltip. */
  hint: string;
}

export interface NavSection {
  id: 'home' | 'run' | 'results' | 'video' | 'copy' | 'settings' | 'about';
  /** Sidebar label in simple mode; group heading in "all tools" mode. */
  label: string;
  icon: NavIconName;
  /** Where the simple-mode sidebar item goes. */
  to: string;
  /** One-line description (tooltip, Home cards). */
  blurb: string;
  /** Every page that belongs here (lights up the sidebar item). */
  keys: PageKey[];
  /** Tabs shown at the top of the section's main pages in simple mode (none: no tab bar). */
  tabs: SectionTab[];
  /** Top-level paths of the "all tools" menu items that belong to this group, in order. */
  paths: string[];
  /** false: only in "all tools" mode (simple mode reaches it some other way). */
  simple?: boolean;
}

export const SECTIONS: NavSection[] = [
  {
    id: 'home',
    label: 'Home',
    icon: 'home',
    to: '/',
    blurb: 'Your starting point: what to do next and how things stand.',
    keys: ['home'],
    tabs: [],
    paths: ['/'],
  },
  {
    id: 'run',
    label: 'Run a test',
    icon: 'run',
    to: '/run/new',
    blurb: 'Pick some AI models and a set of tests, see the price, press start.',
    keys: ['run-new', 'run-live', 'run-watch', 'run-jam', 'tests', 'test-detail', 'test-builder', 'arena', 'arena-new', 'arena-tournament', 'arena-game', 'arena-judge', 'costs'],
    tabs: [
      { label: 'Start a test', to: '/run/new', keys: ['run-new'], hint: 'Choose models and tests, then start' },
      { label: 'Tournaments', to: '/arena', keys: ['arena', 'arena-new', 'arena-tournament', 'arena-judge'], hint: 'Models play games against each other (the Arena)' },
      { label: 'Test library', to: '/tests', keys: ['tests', 'test-detail', 'test-builder'], hint: 'Every test, what it asks and how it is marked' },
      { label: 'Cost planner', to: '/costs', keys: ['costs'], hint: 'What a run would cost before you start it' },
    ],
    paths: ['/run/new', '/tests', '/arena', '/costs'],
  },
  {
    id: 'results',
    label: 'Results',
    icon: 'results',
    to: '/leaderboard',
    blurb: 'Who won: the leaderboard, every past run and the best model on each test.',
    keys: ['leaderboard', 'best', 'runs', 'run-detail', 'history'],
    tabs: [
      { label: 'Leaderboard', to: '/leaderboard', keys: ['leaderboard'], hint: 'The ranking across all your runs' },
      { label: 'Best on each test', to: '/best', keys: ['best'], hint: 'The winner of every single test' },
      { label: 'Past runs', to: '/runs', keys: ['runs', 'run-detail'], hint: 'Every test run you have done' },
      { label: 'History', to: '/history', keys: ['history'], hint: 'How models have changed over time' },
    ],
    paths: ['/leaderboard', '/best', '/runs', '/history'],
  },
  {
    id: 'video',
    label: 'Make a video',
    icon: 'video',
    to: '/present',
    blurb: 'Turn results into full-screen slides, head-to-heads and clips to record.',
    keys: ['present', 'slides', 'versus', 'studio', 'overlay', 'gallery', 'gallery-vote', 'newmodel', 'challenge', 'publish'],
    tabs: [
      { label: 'Presenter', to: '/present', keys: ['present'], hint: 'Full-screen slides that explain a run' },
      { label: 'Head to Head', to: '/versus', keys: ['versus'], hint: 'Two models, side by side' },
      { label: 'Studio', to: '/studio', keys: ['studio'], hint: 'Highlights, script, thumbnails and OBS overlays' },
      { label: 'The Gallery', to: '/gallery', keys: ['gallery', 'gallery-vote'], hint: 'The pictures models painted' },
    ],
    paths: ['/present', '/versus', '/studio', '/gallery', '/newmodel', '/challenge', '/publish'],
  },
  {
    id: 'copy',
    label: 'Copy & paste tests',
    icon: 'copy',
    to: '/inbox',
    blurb: 'Test a chatbot that has no API key: copy each question in, paste the reply back.',
    keys: ['inbox', 'grading', 'review', 'grade'],
    tabs: [
      { label: 'Inbox', to: '/inbox', keys: ['inbox'], hint: 'Questions waiting for you to paste a reply' },
      { label: 'Grading Station', to: '/grading', keys: ['grading'], hint: 'Mark answers that need a human' },
      { label: 'Blind review', to: '/review', keys: ['review'], hint: 'Score answers without knowing which model wrote them' },
      { label: 'Grade one answer', to: '/grade', keys: ['grade'], hint: 'Paste any reply and see its score' },
    ],
    paths: ['/inbox', '/grading', '/review', '/grade'],
  },
  {
    id: 'settings',
    label: 'Settings',
    icon: 'settings',
    to: '/keys',
    blurb: 'API keys, your monthly budget and which models take part.',
    keys: ['keys', 'budget', 'models'],
    tabs: [
      { label: 'API keys', to: '/keys', keys: ['keys'], hint: 'Paste a key so Gauntlet can talk to the models' },
      { label: 'Budget', to: '/budget', keys: ['budget'], hint: 'Your monthly spending limit' },
      { label: 'Models', to: '/models', keys: ['models'], hint: 'Which AI models take part' },
    ],
    paths: ['/keys', '/budget', '/models'],
  },
  {
    id: 'about',
    label: 'About',
    icon: 'about',
    to: '/methodology',
    blurb: 'How the scoring works, and a plain-English guide for viewers.',
    keys: ['methodology', 'guide'],
    tabs: [],
    paths: ['/methodology', '/guide'],
    simple: false,
  },
];

/** Sidebar items in simple mode (about six). */
export const SIMPLE_NAV: NavSection[] = SECTIONS.filter((s) => s.simple !== false);

/** The section a page belongs to (null: none, e.g. a page added later). */
export function sectionFor(key: PageKey): NavSection | null {
  return SECTIONS.find((s) => s.keys.includes(key)) ?? null;
}

/** The tab bar for a page in simple mode: the section's tabs, and which one is current. Null when the page has none. */
export function tabsFor(key: PageKey): { section: NavSection; tabs: SectionTab[]; current: SectionTab } | null {
  const section = sectionFor(key);
  if (!section || section.tabs.length < 2) return null;
  const current = section.tabs.find((t) => t.keys.includes(key));
  return current ? { section, tabs: section.tabs, current } : null;
}

/** Top-level path of a menu link ("/run/new" stays whole, "/runs" → "/runs"). */
function topPath(to: string): string {
  const p = to.split('?')[0]!;
  return p === '/run/new' ? p : `/${splitPath(p)[0] ?? ''}`;
}

/**
 * The "all tools" menu: the app's full list of menu items grouped into the sections above, in each
 * section's order. Items no section claims (e.g. a tool added later) land in a final "More tools" group,
 * so nothing ever disappears from the menu.
 */
export function groupAllTools<T extends { to: string }>(items: T[]): Array<{ id: string; label: string; blurb: string; items: T[] }> {
  const used = new Set<T>();
  const groups: Array<{ id: string; label: string; blurb: string; items: T[] }> = [];
  for (const s of SECTIONS) {
    const mine: T[] = [];
    for (const p of s.paths) {
      for (const it of items) if (!used.has(it) && topPath(it.to) === p) (used.add(it), mine.push(it));
    }
    if (mine.length) groups.push({ id: s.id, label: s.label, blurb: s.blurb, items: mine });
  }
  const rest = items.filter((it) => !used.has(it));
  if (rest.length) groups.push({ id: 'more', label: 'More tools', blurb: 'Everything else.', items: rest });
  return groups;
}

/** Example URL for every page key (used by the tests and the Help panel's "all pages" check). */
export const ROUTE_EXAMPLES: Record<Exclude<PageKey, 'unknown'>, string> = {
  home: '/',
  leaderboard: '/leaderboard',
  'run-new': '/run/new',
  runs: '/runs',
  'run-detail': '/runs/run-1',
  'run-live': '/runs/run-1/live',
  'run-watch': '/runs/run-1/watch',
  'run-jam': '/runs/run-1/jam',
  tests: '/tests',
  'test-detail': '/tests/math.word-problems',
  'test-builder': '/tests/new',
  models: '/models',
  keys: '/keys',
  review: '/review',
  grading: '/grading',
  methodology: '/methodology',
  guide: '/guide',
  gallery: '/gallery',
  'gallery-vote': '/gallery/run-1/vote',
  inbox: '/inbox',
  grade: '/grade',
  costs: '/costs',
  budget: '/budget',
  publish: '/publish',
  newmodel: '/newmodel',
  history: '/history',
  challenge: '/challenge',
  studio: '/studio',
  overlay: '/overlay',
  slides: '/slides/history',
  arena: '/arena',
  'arena-new': '/arena/new',
  'arena-tournament': '/arena/t-1',
  'arena-game': '/arena/t-1/game/g1',
  'arena-judge': '/arena/t-1/judge',
  versus: '/versus',
  best: '/best',
  present: '/present',
};
