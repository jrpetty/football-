/**
 * Mock-mode (?mock=1) answers for the channel-tools API: site settings, a
 * publish result, model history, New Model Day and a viewer-challenge queue.
 * Uses the same fictional models as ../mock/fixtures.ts.
 */
import { ApiError } from '../api.ts';
import type {
  ChallengeItem,
  ChallengeQueue,
  ChallengeSlide,
  HistoryData,
  HistoryPoint,
  NewModelHeadline,
  NewModelInput,
  NewModelPrepared,
  NewModelSuiteCost,
  PublishResult,
  SiteConfig,
} from '../../../src/channel/types.ts';

const delay = (ms: number) => new Promise((r) => setTimeout(r, ms));

let site: SiteConfig = {
  channelName: 'Model Arena',
  tagline: 'Independent AI benchmarks: same tests, same seeds, every model.',
  logo: '',
  youtubeUrl: 'https://www.youtube.com/@example',
  accentColor: '#22D3EE',
  suites: ['core', 'frontier'],
  siteUrl: 'https://example.github.io/leaderboard',
  submissionFormUrl: 'https://forms.gle/example',
  season: '2026-s1',
  footerNote: 'Results are independent and unsponsored.',
};

// ───────────────────────────── History ─────────────────────────────

type P = [id: string, label: string, family: string, tier: 'flagship' | 'mid' | 'small', date: string, index: number, color: string];
const HISTORY: P[] = [
  ['meridian-atlas-2', 'Atlas-2', 'Atlas', 'flagship', '2024-06-11', 41.2, '#3987e5'],
  ['meridian-atlas-3', 'Atlas-3', 'Atlas', 'flagship', '2025-02-04', 55.8, '#3987e5'],
  ['meridian-atlas-3-5', 'Atlas-3.5', 'Atlas', 'flagship', '2025-09-16', 63.1, '#3987e5'],
  ['meridian-atlas-4-ultra', 'Atlas-4 Ultra', 'Atlas', 'flagship', '2026-08-14', 81.6, '#3987e5'],
  ['meridian-atlas-4-mini', 'Atlas-4 Mini', 'Atlas', 'small', '2026-08-14', 58.9, '#e66767'],
  ['helios-nova-1', 'Nova 1', 'Nova', 'flagship', '2024-09-24', 37.5, '#d95926'],
  ['helios-nova-2-pro', 'Nova 2 Pro', 'Nova', 'flagship', '2025-05-20', 52.4, '#d95926'],
  ['helios-nova-3-pro', 'Nova 3 Pro', 'Nova', 'flagship', '2026-03-03', 74.2, '#d95926'],
  ['helios-quill-flash', 'Quill Flash', 'Nova', 'mid', '2026-05-12', 60.3, '#d55181'],
  ['kestrel-kite-1', 'Kite 1', 'Kite', 'flagship', '2024-11-05', 33.9, '#199e70'],
  ['kestrel-kite-2', 'Kite 2', 'Kite', 'flagship', '2025-07-01', 57.0, '#199e70'],
  ['kestrel-kite-reasoner', 'Kite 2.5 Reasoner', 'Kite', 'flagship', '2026-01-27', 77.9, '#199e70'],
  ['obsidian-sable-medium', 'Sable Medium', 'Sable', 'mid', '2025-03-18', 44.1, '#c98500'],
  ['obsidian-sable-large', 'Sable Large', 'Sable', 'flagship', '2026-06-09', 66.7, '#c98500'],
];

function historyData(suite: string, metric: string, tiers: string[]): HistoryData {
  const offset = suite === 'frontier' ? -18 : suite === 'quick' ? 4 : 0;
  const cats = ['reasoning', 'math', 'coding', 'instruction', 'honesty', 'long-context', 'agentic', 'social', 'visual', 'creative', 'extraction'];
  const pts: HistoryPoint[] = HISTORY.filter((h) => !tiers.length || tiers.includes(h[3])).map(([id, label, family, tier, date, index, color], i) => ({
    contestantId: id,
    label,
    vendor: family,
    color,
    family,
    tier,
    releaseDate: date,
    index: Math.max(3, +(index + offset).toFixed(1)),
    indexCi95: [Math.max(0, index + offset - 4.2), Math.min(100, index + offset + 3.9)],
    categoryScores: Object.fromEntries(cats.map((c, k) => [c, Math.max(0.02, Math.min(0.99, (index + offset) / 100 + (((i * 7 + k * 13) % 17) - 8) / 60))])),
    coverage: 1,
  }));
  const value = (p: HistoryPoint) => (metric === 'index' ? p.index! : Math.round((p.categoryScores[metric] ?? 0) * 1000) / 10);
  const fams = new Map<string, HistoryPoint[]>();
  for (const p of pts) fams.set(p.family, [...(fams.get(p.family) ?? []), p]);
  const families = [...fams.entries()].map(([family, points]) => {
    points.sort((a, b) => a.releaseDate!.localeCompare(b.releaseDate!));
    const flagships = points.filter((p) => p.tier === 'flagship');
    const best = new Map<string, HistoryPoint>();
    for (const p of flagships.length >= 2 ? flagships : points) if (!best.has(p.releaseDate!) || value(p) > value(best.get(p.releaseDate!)!)) best.set(p.releaseDate!, p);
    const lead = [...points].reverse().find((p) => p.tier === 'flagship') ?? points[points.length - 1]!;
    return { family, color: lead.color, points, line: [...best.values()] };
  });
  const jumps = families
    .flatMap((f) =>
      f.line.slice(1).map((b, i) => {
        const a = f.line[i]!;
        return {
          family: f.family,
          from: { contestantId: a.contestantId, label: a.label, releaseDate: a.releaseDate!, value: value(a) },
          to: { contestantId: b.contestantId, label: b.label, releaseDate: b.releaseDate!, value: value(b) },
          delta: Math.round((value(b) - value(a)) * 10) / 10,
          days: Math.round((Date.parse(b.releaseDate!) - Date.parse(a.releaseDate!)) / 86_400_000),
        };
      }),
    )
    .filter((j) => j.delta > 0)
    .sort((a, b) => b.delta - a.delta)
    .slice(0, 3);
  const undated: HistoryPoint[] = [{ contestantId: 'manual-orbit-chat', label: 'Orbit Chat (web)', vendor: 'Orbit Labs', color: '#9085e9', family: 'Orbit', tier: null, releaseDate: null, index: 48.2, indexCi95: null, categoryScores: {}, coverage: 0.6 }];
  return { suiteId: suite, metric, families, undated, noResults: [], jumps, generatedAt: new Date().toISOString() };
}

// ───────────────────────────── New Model Day ─────────────────────────────

let lastModel: NewModelPrepared['contestant'] | null = null;

function prepare(input: NewModelInput): NewModelPrepared {
  if (!input.model) throw new ApiError('Enter the model id (e.g. gpt-6)', 400);
  if (input.inputPerM === undefined || input.outputPerM === undefined) throw new ApiError('Input and output prices are required', 400);
  const label = input.label || input.model;
  lastModel = {
    id: input.id || input.model.toLowerCase().replace(/[^a-z0-9._-]+/g, '-'),
    label,
    vendor: input.vendor || 'Meridian AI',
    provider: input.provider,
    model: input.model,
    color: '#8b5cf6',
    enabled: true,
    pricing: { inputPerM: input.inputPerM, outputPerM: input.outputPerM, verifiedAt: input.pricesVerified ? new Date().toISOString().slice(0, 10) : null, source: 'Entered on New Model Day' },
    family: input.family || label.match(/^[A-Za-z]+/)?.[0] || label,
    tier: input.tier || 'flagship',
    releaseDate: input.releaseDate,
  };
  return { contestant: lastModel, created: true, discovered: true, suggestions: [], warnings: input.pricesVerified ? [] : ['Prices are marked unverified until you check them on the provider’s price page.'] };
}

function costs(id: string): NewModelSuiteCost[] {
  const k = (lastModel && lastModel.id === id ? lastModel.pricing.outputPerM : 20) / 20;
  const s = (suiteId: string, name: string, tests: number, cases: number, usd: number): NewModelSuiteCost => ({
    suiteId,
    name,
    tests,
    cases,
    estimate: { jobs: cases, calls: cases * 3, judgeCostUsd: usd * 0.12, estCostUsd: +(usd * k).toFixed(2), estCostUsdHigh: +(usd * k * 1.45).toFixed(2), fingerprint: 'mock00fp', warnings: [] },
  });
  return [s('quick', 'Quick Look', 19, 24, 0.84), s('core', 'Core Gauntlet (Standard tier)', 21, 1107, 38.6), s('frontier', 'Frontier Gauntlet', 16, 369, 29.4)];
}

function headline(id: string, suite: string): NewModelHeadline {
  const label = lastModel?.id === id ? lastModel.label : id;
  return {
    contestantId: id,
    label,
    suiteId: suite,
    suiteName: suite === 'quick' ? 'Quick Look' : suite === 'frontier' ? 'Frontier Gauntlet' : 'Core Gauntlet (Standard tier)',
    index: 79.4,
    indexCi95: [75.1, 83.2],
    rank: 2,
    of: 7,
    above: [{ label: 'Atlas-4 Ultra', index: 81.6 }],
    below: [{ label: 'Kite 2.5 Reasoner', index: 77.9 }, { label: 'Nova 3 Pro', index: 74.2 }],
    bestCategory: { id: 'coding', name: 'Coding', score: 0.91 },
    worstCategory: { id: 'social', name: 'Social Deduction', score: 0.44 },
    costUsd: 31.87,
    tiedWithAbove: true,
    headline: `${label} scores 79.4 and ranks #2 of 7, statistically tied with Atlas-4 Ultra (81.6).`,
    titles: [`${label} Is Top 2 — But It Didn't Beat Atlas-4 Ultra`, `${label} vs Atlas-4 Ultra: Too Close To Call?`, `${label} Is Scary Good At Coding`, `Is ${label} Worth The Money? ($0.40 per point)`],
  };
}

// ───────────────────────────── Viewer challenge ─────────────────────────────

const now = '2026-09-20T10:00:00.000Z';
const item = (p: Partial<ChallengeItem> & Pick<ChallengeItem, 'id' | 'question' | 'answer'>): ChallengeItem => ({
  answerType: 'exact',
  viewerName: '',
  viewerHandle: '',
  notes: '',
  submittedAt: now,
  status: 'pending',
  credit: true,
  issues: [],
  ...p,
});

let queue: ChallengeQueue = {
  season: '2026-s1',
  testFile: 'tests/private/viewer-challenge-2026-s1.json',
  testId: 'reasoning.viewer-challenge-2026-s1',
  updatedAt: now,
  items: [
    item({ id: 'a1f3c9e2', question: 'How many times does the letter "r" appear in "strawberry raspberry"? Give a number.', answer: '6', answerType: 'number', viewerName: 'Ada L.', viewerHandle: '@ada_codes', notes: 'Counted twice: straw-b-e-r-r-y (3) + r-a-s-p-b-e-r-r-y (3).', status: 'approved', caseId: 'v01' }),
    item({
      id: 'b7d20c11',
      question: 'A bat and a ball cost $1.10 in total. The bat costs $1.00 more than the ball. Which is right?\nA) ball = 10¢\nB) ball = 5¢\nC) ball = 1¢\nD) it cannot be known',
      answer: 'B',
      answerType: 'choice',
      viewerName: 'Bob',
      viewerHandle: '@bobthinks',
      notes: 'Classic, but reworded.',
      issues: [{ level: 'warn', code: 'near-duplicate', message: 'Very similar to (64%) an existing test: math.word-problems / c03.' }],
    }),
    item({ id: 'c0ffee42', question: 'I am the only number that is spelled with its letters in alphabetical order. What am I? One word.', answer: 'forty', viewerHandle: '@puzzlequeen', notes: 'f-o-r-t-y.', credit: false }),
    item({ id: 'd4e5f6a7', question: 'What is the capital of Australia?', answer: '', viewerHandle: '@geo_gabe', issues: [{ level: 'error', code: 'missing-answer', message: 'No answer key: add the correct answer before approving.' }, { level: 'warn', code: 'too-short', message: 'Very short question: is it clear enough to have one right answer?' }] }),
    item({ id: 'e9e9e9e9', question: 'Name the largest planet in the solar system.', answer: 'Name the largest planet in the solar system. Jupiter', viewerHandle: '@spacecadet', issues: [{ level: 'error', code: 'question-in-answer', message: 'The answer contains the whole question (pasted into the wrong box?).' }] }),
    item({ id: 'f1f2f3f4', question: 'Spam spam spam', answer: 'spam', viewerHandle: '@troll', status: 'rejected', issues: [{ level: 'warn', code: 'too-short', message: 'Very short question: is it clear enough to have one right answer?' }, { level: 'warn', code: 'answer-in-question', message: 'The answer appears word-for-word in the question: it may give itself away.' }] }),
  ],
};

function slides(): ChallengeSlide[] {
  const models = [
    ['Atlas-4 Ultra', '#3987e5', 1],
    ['Kite 2.5 Reasoner', '#199e70', 1],
    ['Nova 3 Pro', '#d95926', 0],
    ['Sable Large', '#c98500', 0],
    ['Quill Flash', '#d55181', 0],
  ] as const;
  return queue.items
    .filter((i) => i.status === 'approved')
    .map((i) => ({
      itemId: i.id,
      caseId: i.caseId ?? null,
      question: i.question,
      answer: i.answer,
      credit: i.credit ? i.viewerHandle || i.viewerName || null : null,
      outcomes: models.map(([label, color, s]) => ({ contestantId: label.toLowerCase().replace(/\W+/g, '-'), label, color, score: s, passed: s === 1 })),
    }));
}

// ───────────────────────────── Router ─────────────────────────────

export async function handleChannel(method: string, path: string, body: unknown): Promise<unknown> {
  await delay(160);
  const url = new URL(path, 'http://mock');
  const p = url.pathname;
  const q = url.searchParams;
  if (method === 'GET' && p === '/api/channel/site') return site;
  if (method === 'PUT' && p === '/api/channel/site') return (site = { ...site, ...(body as Partial<SiteConfig>) });
  if (method === 'POST' && p === '/api/channel/publish') {
    await delay(700);
    const b = body as { suites: string[]; zip: boolean };
    const r: PublishResult = {
      outDir: 'C:\\Users\\you\\gauntlet\\site',
      zipPath: b.zip ? 'C:\\Users\\you\\gauntlet\\site.zip' : undefined,
      pages: ['index.html', 'models.html', 'tests.html', 'history.html', 'methodology.html', 'challenge.html'],
      files: 64,
      bytes: 1_482_113,
      suites: b.suites.map((id) => ({ id, name: id === 'frontier' ? 'Frontier Gauntlet' : id === 'quick' ? 'Quick Look' : 'Core Gauntlet (Standard tier)', models: 7, tests: id === 'frontier' ? 16 : 21 })),
      hiddenTests: 1,
      withheldPrompts: 0,
      warnings: ['Prices not verified for Kite 2.5 Reasoner: the site marks them "unverified".'],
      generatedAt: new Date().toISOString(),
    };
    return r;
  }
  if (method === 'POST' && p === '/api/channel/newmodel/prepare') return prepare(body as NewModelInput);
  let m = p.match(/^\/api\/channel\/newmodel\/([^/]+)\/costs$/);
  if (method === 'GET' && m) return costs(decodeURIComponent(m[1]!));
  m = p.match(/^\/api\/channel\/newmodel\/([^/]+)\/headline$/);
  if (method === 'GET' && m) return headline(decodeURIComponent(m[1]!), q.get('suite') || 'core');
  if (method === 'POST' && p.startsWith('/api/channel/mock-ping/')) {
    await delay(600);
    return { ok: true, text: 'pong', totalMs: 842, ttftMs: 510, costUsd: 0.00041, servedModel: lastModel?.model ?? 'mock' };
  }
  if (method === 'POST' && p === '/api/channel/mock-run') return { runId: 'run-2026-09-24-agents' };
  if (method === 'GET' && p === '/api/channel/history') return historyData(q.get('suite') || 'core', q.get('metric') || 'index', (q.get('tiers') ?? '').split(',').filter(Boolean));

  if (method === 'GET' && p === '/api/channel/challenge') return { seasons: ['2026-s1'], queue };
  m = p.match(/^\/api\/channel\/challenge\/([^/]+)\/import$/);
  if (method === 'POST' && m) {
    const text = String((body as { text?: string }).text ?? '');
    const lines = text.split(/\r?\n/).filter((l) => l.trim());
    const added = lines.slice(1).map((l, i) => {
      const [question = '', answer = '', , , handle = ''] = l.split(',');
      return item({ id: `m${Date.now().toString(36)}${i}`, question, answer, viewerHandle: handle });
    });
    queue = { ...queue, items: [...queue.items, ...added] };
    return { added: added.length, skipped: 0, errors: [], queue };
  }
  m = p.match(/^\/api\/channel\/challenge\/([^/]+)\/items\/([^/]+)$/);
  if (m && method === 'PUT') {
    const id = decodeURIComponent(m[2]!);
    const patch = body as Partial<ChallengeItem>;
    const cur = queue.items.find((i) => i.id === id);
    if (!cur) throw new ApiError('No such submission', 404);
    const next = { ...cur, ...patch };
    if (next.answer.trim()) next.issues = next.issues.filter((x) => x.code !== 'missing-answer' && !(x.code === 'question-in-answer' && !next.answer.includes(next.question)));
    if (patch.status === 'approved' && next.issues.some((x) => x.level === 'error')) throw new ApiError(`Fix these before approving: ${next.issues.filter((x) => x.level === 'error').map((x) => x.message).join(' ')}`, 400);
    queue = { ...queue, items: queue.items.map((i) => (i.id === id ? next : i)) };
    return queue;
  }
  if (m && method === 'DELETE') {
    queue = { ...queue, items: queue.items.filter((i) => i.id !== decodeURIComponent(m![2]!)) };
    return queue;
  }
  m = p.match(/^\/api\/channel\/challenge\/([^/]+)\/write$/);
  if (method === 'POST' && m) {
    const n = queue.items.filter((i) => i.status === 'approved').length;
    queue = { ...queue, writtenAt: new Date().toISOString(), writtenVersion: '1.0.1' };
    return { file: queue.testFile, testId: queue.testId, version: '1.0.1', cases: n, hash: '9c1e5a7b2d40', errors: n ? [] : ['Approve at least one submission first.'] };
  }
  m = p.match(/^\/api\/channel\/challenge\/([^/]+)\/slides$/);
  if (method === 'GET' && m) return slides();
  throw new ApiError(`Mock: no route for ${method} ${p}`, 404);
}
