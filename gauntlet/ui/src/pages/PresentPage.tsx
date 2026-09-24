/**
 * Episode Presenter — a full-screen, keyboard-driven 16:9 deck generated from a
 * run (GET /api/runs/:id), its tests (GET /api/tests/:id) and server meta.
 *
 * The deck renders on a fixed 1920×1080 stage that is scaled to fit the window,
 * so what the camera records is identical at any screen size. Every slide ends
 * with a plain-English "What you're seeing" caption.
 *
 * Keys: → / Space / PageDown next · ← / PageUp back · Home / End · F full screen
 *       A auto (reveal / advance) · ? help · Esc exit.
 */
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import type { CSSProperties, ReactNode } from 'react';
import { api } from '../api.ts';
import { useAsync, useCountUp, useElementSize, useHotkeys, useInterval } from '../hooks.ts';
import { navigate, pathOf, setQuery, useRoute } from '../router.tsx';
import { useMeta } from '../context.tsx';
import { cx } from '../components/ui.tsx';
import { Icon } from '../components/icons.tsx';
import { BrandMark } from '../components/Brand.tsx';
import { ScoreCostScatter } from '../components/charts/ScoreCostScatter.tsx';
import { isBaseline, olympicCompare, shortCat } from '../components/leaderboard/util.ts';
import { fmtCost, fmtIndex, fmtMs, shortHash } from '../format.ts';

/** Compact money for big slide type: $52.57, $0.23, $0.0063, <$0.001. */
function shortCost(usd: number | null | undefined): string {
  if (typeof usd !== 'number' || !Number.isFinite(usd)) return '—';
  if (usd === 0) return '$0';
  if (usd < 0.001) return '<$0.001';
  if (usd < 0.01) return `$${usd.toFixed(4).replace(/0+$/, '')}`;
  return fmtCost(usd);
}

/** Singular or plural noun (no number). */
const noun = (n: number, one: string, many = `${one}s`) => (n === 1 ? one : many);
import type { CategoryInfo, Leaderboard, LeaderboardRow, ProgramInfo, RunDetail, TestAggregate, TestDefinition, TestDetail, TestSnapshot } from '../types.ts';
import { VisionExplainerCard, examplePicture } from '../components/VisionPresenter.tsx';

const W = 1920;
const H = 1080;
const AUTO_REVEAL_MS = 1200;
const AUTO_ADVANCE_MS = 9000;

// ───────────────────────────── Deck model ─────────────────────────────

interface DeckTest {
  snap: TestSnapshot;
  detail: TestDetail | null;
  cat: CategoryInfo;
  /** 1-based position in the episode. */
  n: number;
}

interface Contender {
  id: string;
  label: string;
  vendor: string;
  color: string;
  baseline: boolean;
  manual: boolean;
}

interface Deck {
  d: RunDetail;
  lb: Leaderboard;
  tests: DeckTest[];
  contenders: Contender[];
  rowsById: Map<string, LeaderboardRow>;
  repeats: number;
  judgeCross: boolean;
  cats: CategoryInfo[];
  programs: Map<string, ProgramInfo>;
  hasManual: boolean;
  /** Any held-out (never published) test in the run. */
  hasPrivate: boolean;
}

type Slide =
  | { kind: 'title' }
  | { kind: 'how' }
  | { kind: 'explainer'; test: DeckTest }
  | { kind: 'result'; test: DeckTest }
  | { kind: 'final' }
  | { kind: 'scatter' }
  | { kind: 'medals' }
  | { kind: 'outro' };

interface Caption {
  text: string;
  fine?: string;
}

const longDate = (iso: string | undefined) => {
  if (!iso) return '';
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? '' : d.toLocaleDateString('en-GB', { day: 'numeric', month: 'long', year: 'numeric' });
};

const DIFF: Record<string, { label: string; level: number }> = {
  easy: { label: 'Easy', level: 1 },
  medium: { label: 'Medium', level: 2 },
  hard: { label: 'Hard', level: 3 },
  extreme: { label: 'Extreme', level: 4 },
};

/** Plain-English "How it's scored" line plus the precise scorer name for small print. */
export function friendlyScoring(def: TestDefinition | undefined, program: ProgramInfo | undefined, judgeCross: boolean): { text: string; tech: string } {
  const panel = `a panel of AI judges${judgeCross ? ' from other companies' : ''}`;
  if (!def) return { text: 'Scored automatically by the Gauntlet harness.', tech: 'unknown scorer' };
  if (def.kind === 'program') {
    return { text: program?.scoring || 'Scored by the simulation itself — the same world for every model.', tech: `program: ${def.program}` };
  }
  const sc = def.scorer;
  switch (sc.type) {
    case 'code-js': {
      let n = 0;
      for (const c of def.cases) {
        const t = (c.expected as { tests?: unknown[] } | undefined)?.tests;
        if (Array.isArray(t)) n += t.length;
      }
      return {
        text: n > 0 ? `The code each model writes is actually run against ${n} hidden unit tests.` : 'The code each model writes is actually run and tested.',
        tech: 'code-js (sandboxed execution)',
      };
    }
    case 'exact':
    case 'number':
    case 'choice':
    case 'regex':
      return { text: 'One correct answer per question: right or wrong.', tech: `${sc.type} match on the FINAL ANSWER line` };
    case 'contains':
      return { text: 'Checked for the key facts a correct answer has to mention.', tech: 'keyword check' };
    case 'constraints':
      return sc.allOrNothing
        ? { text: 'Every rule is checked automatically — break a single one and the answer scores zero.', tech: 'constraint checks (all or nothing)' }
        : { text: 'Every rule in the instructions is checked automatically: points for each rule followed.', tech: 'constraint checks (partial credit)' };
    case 'json':
      return sc.allOrNothing
        ? { text: 'The answer must be machine-readable data, and every single field must match the answer key: one wrong field scores zero.', tech: 'JSON field match (all or nothing)' }
        : { text: 'The answer must be machine-readable data: points for every field that matches the answer key.', tech: 'JSON field match (partial credit)' };
    case 'judge': {
      const withRef = def.cases.some((c) => c.expected !== undefined);
      return { text: `Graded by ${panel} ${withRef ? 'against a reference answer' : 'using a written marking guide'}.`, tech: 'LLM judge panel (rubric)' };
    }
    case 'judge-classify':
      return { text: `Graded by ${panel} against a reference answer.`, tech: 'LLM judge panel (classification)' };
    case 'artifact': {
      const what = sc.format === 'html' ? 'web page' : 'drawing';
      return {
        text: `The ${what} each model builds is opened and checked automatically${sc.rubric ? `, then rated by ${panel}` : ''}.`,
        tech: `artifact checks${sc.rubric ? ' + judge panel' : ''}`,
      };
    }
    case 'human':
      return { text: 'Rated blind by people who never see which model made which answer.', tech: 'human blind review' };
    default:
      return { text: 'Scored automatically by the Gauntlet harness.', tech: 'custom scorer' };
  }
}

/** First case prompt, without the shared answer-format preamble. */
function examplePrompt(def: TestDefinition | undefined, detail: TestDetail | null, snap: TestSnapshot): string | null {
  if (!def || def.kind !== 'prompt') return null;
  const first = def.cases.find((c) => snap.caseIds.includes(c.id)) ?? def.cases[0];
  const raw = first?.prompt ?? first?.turns?.[0] ?? detail?.rendered?.[0]?.turns?.[0] ?? '';
  const text = raw.trim().replace(/\n{3,}/g, '\n\n');
  if (!text) return null;
  // At most ~320 characters and 8 lines, cut at a word boundary.
  const lines = text.split('\n');
  let out = lines.slice(0, 8).join('\n');
  let cut = lines.length > 8;
  if (out.length > 320) {
    const head = out.slice(0, 320);
    const sp = head.lastIndexOf(' ');
    out = head.slice(0, sp > 240 ? sp : 320);
    cut = true;
  }
  return cut ? `${out.trimEnd()}…` : out;
}

/** Whole sentences up to about `max` characters (at least one sentence, hard-cut if needed). */
function brief(text: string | undefined, max: number): string {
  const t = (text ?? '').replace(/\s+/g, ' ').trim();
  if (t.length <= max) return t;
  const sentences = t.match(/[^.!?]+[.!?]+(\s|$)/g) ?? [t];
  let out = '';
  for (const sn of sentences) {
    if (out && (out + sn).length > max) break;
    out += sn;
  }
  out = out.trim();
  if (out.length > max + 40) {
    const head = out.slice(0, max);
    out = `${head.slice(0, Math.max(head.lastIndexOf(' '), max - 30))}…`;
  }
  return out;
}

function buildDeck(d: RunDetail, details: Map<string, TestDetail>, metaCats: CategoryInfo[], cat: (id: string) => CategoryInfo, programs: ProgramInfo[], judgeCrossDefault: boolean | undefined, manualProviders: Set<string>): Deck {
  const m = d.manifest;
  const lb = d.leaderboard;
  const rowsById = new Map((lb?.rows ?? []).map((r) => [r.contestantId, r]));
  const catOrder = new Map<string, number>();
  [...metaCats, ...(lb?.categories ?? [])].forEach((c, i) => {
    if (!catOrder.has(c.id)) catOrder.set(c.id, i);
  });
  const ordered = m.tests
    .map((snap, i) => ({ snap, i }))
    .sort((a, b) => (catOrder.get(a.snap.category) ?? 999) - (catOrder.get(b.snap.category) ?? 999) || a.i - b.i)
    .map(({ snap }, i): DeckTest => ({ snap, detail: details.get(snap.id) ?? null, cat: cat(snap.category), n: i + 1 }));

  const contenders: Contender[] = m.contestants.map((c) => {
    const row = rowsById.get(c.id);
    return {
      id: c.id,
      label: c.label,
      vendor: c.vendor,
      color: c.color,
      baseline: isBaseline({ contestantId: c.id, vendor: c.vendor, label: c.label }),
      manual: !!row?.manual || manualProviders.has(c.provider),
    };
  });
  contenders.sort((a, b) => Number(a.baseline) - Number(b.baseline));

  const usedCats = new Set(m.tests.map((t) => t.category));
  const cats = [...usedCats].map((id) => cat(id)).sort((a, b) => (catOrder.get(a.id) ?? 999) - (catOrder.get(b.id) ?? 999));

  return {
    d,
    lb,
    tests: ordered,
    contenders,
    rowsById,
    repeats: m.settings?.repeats ?? 1,
    judgeCross: (m.settings?.judgeExcludeSameVendor ?? judgeCrossDefault) !== false,
    cats,
    programs: new Map(programs.map((p) => [p.id, p])),
    hasManual: contenders.some((c) => c.manual),
    hasPrivate: [...details.values()].some((t) => t.summary?.source === 'private'),
  };
}

function buildSlides(deck: Deck): Slide[] {
  const slides: Slide[] = [{ kind: 'title' }, { kind: 'how' }];
  for (const test of deck.tests) slides.push({ kind: 'explainer', test }, { kind: 'result', test });
  // Summary slides only when they have something to show (e.g. a baseline-only smoke test has none).
  const comps = standings(deck).filter((r) => typeof r.index === 'number');
  if (comps.length) slides.push({ kind: 'final' });
  if (comps.some((r) => (r.totals?.costUsd ?? 0) > 0)) slides.push({ kind: 'scatter' });
  if (comps.length && (deck.lb?.medals ?? []).some((e) => e.gold || e.silver || e.bronze)) slides.push({ kind: 'medals' });
  slides.push({ kind: 'outro' });
  return slides;
}

function standings(deck: Deck): LeaderboardRow[] {
  return (deck.lb?.rows ?? []).filter((r) => !isBaseline(r)).sort((a, b) => (a.index === null ? 1 : 0) - (b.index === null ? 1 : 0) || (a.rank ?? 99) - (b.rank ?? 99));
}

function baselineRow(deck: Deck): LeaderboardRow | null {
  return (deck.lb?.rows ?? []).find((r) => isBaseline(r)) ?? null;
}

function casesWord(t: DeckTest, n: number): string {
  if (t.snap.kind === 'program') return noun(n, 'world');
  const def = t.detail?.definition;
  if (def?.kind === 'prompt') {
    if (def.cases.some((c) => (c.turns?.length ?? 0) > 1)) return noun(n, 'conversation');
    if (def.scorer.type === 'code-js' || def.scorer.type === 'artifact' || def.scorer.type === 'human') return noun(n, 'task');
  }
  return noun(n, 'question');
}

function captionFor(slide: Slide, deck: Deck): Caption {
  const m = deck.d.manifest;
  const R = deck.repeats;
  const nModels = deck.contenders.filter((c) => !c.baseline).length;
  switch (slide.kind) {
    case 'title':
      return {
        text: `Meet the contenders: ${nModels} AI ${noun(nModels, 'model')} take on the same ${m.tests.length} ${noun(m.tests.length, 'test')}${R > 1 ? `, with ${R} attempts at every question` : ''}.${deck.contenders.some((c) => c.baseline) ? ' A random-guessing player sets the floor.' : ''}`,
        fine: `Fingerprint ${shortHash(m.fingerprint, 12)} identifies the exact prompts, answer keys and scoring rules used`,
      };
    case 'how':
      return {
        text: 'The rules: every test is scored out of 100, the Gauntlet Index averages the categories into one number, and brackets show how certain each score is.',
        fine: `Uncertainty = 95% bootstrap CI over test cases · temperature ${m.settings?.temperature ?? 0}${deck.hasManual ? ' · hand-pasted chatbots used their own apps' : ''}`,
      };
    case 'explainer': {
      const t = slide.test;
      const sc = friendlyScoring(t.detail?.definition, t.detail?.program ?? deck.programs.get(t.detail?.definition.kind === 'program' ? t.detail.definition.program : ''), deck.judgeCross);
      const n = t.snap.caseIds.length;
      return {
        text: `Challenge ${t.n} of ${deck.tests.length}: what we ask the models in “${t.snap.name}”, and how the answers are checked.`,
        fine: `Scorer: ${sc.tech} · ${n} ${casesWord(t, n)} × ${R} ${noun(R, 'attempt')} · test v${t.snap.version}`,
      };
    }
    case 'result': {
      const t = slide.test;
      const base = baselineRow(deck)?.tests?.[t.snap.id]?.score;
      const program = t.snap.kind === 'program';
      const n = t.snap.caseIds.length;
      const hasCi = deck.contenders.some((c) => {
        const ci = deck.rowsById.get(c.id)?.tests?.[t.snap.id]?.ci95;
        return !!ci && ci[1] - ci[0] > 0.002;
      });
      const baseNote = typeof base === 'number' ? (hasCi ? '; grey is random guessing' : ' Grey is random guessing') : '';
      const unc = hasCi ? ` Brackets show uncertainty${baseNote}.` : baseNote ? `${baseNote}.` : '';
      return {
        text: program ? `Each model’s score out of 100 — longer is better — with how its typical run went underneath.${unc}` : `Each model’s score on this test, out of 100 — longer is better.${unc}`,
        fine: `Average of ${n} ${casesWord(t, n)} × ${R} ${noun(R, 'attempt')} · uncertainty = 95% bootstrap CI · time = median per ${program ? 'run' : casesWord(t, 1)}`,
      };
    }
    case 'final':
      return {
        text: 'The final ranking: the Gauntlet Index combines every category into one score out of 100. Squares show each category; brackets show uncertainty.',
        fine: `Uncertainty: 95% bootstrap CI · cost per point = spend ÷ Index${deck.hasManual ? ' · * pasted by hand' : ''}`,
      };
    case 'scatter':
      return {
        text: 'Up and to the left is better: higher score, lower cost. The bright line links the models that give the best score for the money.',
        fine: 'x-axis: total API spend in USD (log scale) · line: Pareto frontier · whiskers: 95% CI',
      };
    case 'medals':
      return {
        text: `Who won the most individual tests: first place on a test earns gold, second silver and third bronze — across all ${deck.tests.length} tests.`,
        fine: 'Ranked Olympic-style: most golds first, then silvers, then bronzes',
      };
    case 'outro':
      return {
        text: deck.hasPrivate
          ? 'Every public prompt, answer key and scoring rule is published; held-out tests stay private. The fingerprint proves every model faced exactly the same tests.'
          : 'Every prompt, answer key and scoring rule behind this episode is published. The fingerprint proves every model faced exactly the same tests.',
        fine: `Fingerprint = hash over every test hash, the judge prompts and protocol v${m.settings?.protocolVersion ?? '—'}`,
      };
  }
}

function sectionFor(slide: Slide, deck: Deck): string {
  switch (slide.kind) {
    case 'title':
      return 'Episode';
    case 'how':
      return 'How it works';
    case 'explainer':
    case 'result':
      return `Test ${slide.test.n} of ${deck.tests.length} · ${slide.test.cat.name}`;
    case 'final':
      return 'Final standings';
    case 'scatter':
      return 'Value for money';
    case 'medals':
      return 'Medal table';
    case 'outro':
      return 'Methodology';
  }
}

// ───────────────────────────── Small pieces ─────────────────────────────

function PMedal({ kind, size = 'md' }: { kind: 'gold' | 'silver' | 'bronze'; size?: 'sm' | 'md' | 'lg' }) {
  const n = kind === 'gold' ? 1 : kind === 'silver' ? 2 : 3;
  const label = kind === 'gold' ? 'Gold medal' : kind === 'silver' ? 'Silver medal' : 'Bronze medal';
  return (
    <span className={cx('pmedal', kind, size)} role="img" aria-label={label} title={label}>
      {n}
    </span>
  );
}

function CatTag({ cat }: { cat: CategoryInfo }) {
  return (
    <span className="pcat" style={{ ['--cc' as string]: cat.color }}>
      <i />
      {cat.name}
    </span>
  );
}

function Difficulty({ value }: { value: string }) {
  const d = DIFF[value] ?? { label: value, level: 0 };
  return (
    <span className="pdiff" aria-label={`Difficulty: ${d.label}`}>
      <span className="pips" aria-hidden="true">
        {[1, 2, 3, 4].map((i) => (
          <i key={i} className={cx(i <= d.level && 'on')} />
        ))}
      </span>
      {d.label}
    </span>
  );
}

function SlideHead({ eyebrow, title, sub, right }: { eyebrow?: ReactNode; title: ReactNode; sub?: ReactNode; right?: ReactNode }) {
  return (
    <div className="p-head">
      <div className="p-head-t">
        {eyebrow && <div className="p-eyebrow">{eyebrow}</div>}
        <h1 className="p-title">{title}</h1>
        {sub && <div className="p-sub">{sub}</div>}
      </div>
      {right}
    </div>
  );
}

// ───────────────────────────── Slides ─────────────────────────────

function TitleSlide({ deck }: { deck: Deck }) {
  const m = deck.d.manifest;
  const nModels = deck.contenders.filter((c) => !c.baseline).length;
  const n = deck.contenders.length;
  // Up to 8 contenders in two rows of ≤4; bigger line-ups go to compact rows of up to 6.
  const cols = n <= 4 ? Math.max(2, n) : n <= 8 ? Math.ceil(n / 2) : Math.min(6, Math.ceil(n / 3));
  // "Core Gauntlet · September 2026" → a title line and a gradient sub-line.
  const nameParts = (m.name || m.id).split(/\s+·\s+/);
  return (
    <div className="s-title">
      <div className="p-eyebrow">Gauntlet · AI benchmark</div>
      <h1 className="t-run">
        {nameParts.length > 1 ? (
          <>
            {nameParts[0]}
            <span className="t-run-2">{nameParts.slice(1).join(' · ')}</span>
          </>
        ) : (
          m.name || m.id
        )}
      </h1>
      <div className="t-meta">
        <span>{longDate(m.startedAt ?? m.createdAt)}</span>
        <span className="dot" aria-hidden="true" />
        <span>
          <b>{nModels}</b> {noun(nModels, 'model')}
        </span>
        <span className="dot" aria-hidden="true" />
        <span>
          <b>{m.tests.length}</b> {noun(m.tests.length, 'test')}
        </span>
        <span className="dot" aria-hidden="true" />
        <span>
          <b>{deck.repeats}</b> {noun(deck.repeats, 'repeat')}
        </span>
      </div>
      <div className={cx('t-cards', n > 8 && 'many')} style={{ ['--cols' as string]: cols }}>
        {deck.contenders.map((c, i) => (
          <div key={c.id} className={cx('t-card', c.baseline && 'is-base')} style={{ ['--c' as string]: c.baseline ? 'var(--text-3)' : c.color, ['--i' as string]: i }}>
            <span className="t-sw" aria-hidden="true" />
            <div className="t-lbl">{c.baseline ? 'Random guessing' : c.label}</div>
            <div className="t-ven">{c.baseline ? 'Reference player' : c.vendor}</div>
            {c.manual && <span className="t-tag">Pasted by hand</span>}
            {c.baseline && <span className="t-tag">Sets the floor</span>}
          </div>
        ))}
      </div>
      <div className="t-fine mono">
        Fingerprint {shortHash(m.fingerprint, 16)} · harness v{m.harnessVersion} · protocol {m.settings?.protocolVersion ?? '—'}
      </div>
    </div>
  );
}

function HowSlide({ deck }: { deck: Deck }) {
  const weights = Object.values(deck.lb?.categoryWeights ?? {});
  const equal = weights.length === 0 || weights.every((w) => w === weights[0]);
  const shown = deck.cats.slice(0, 6);
  return (
    <div className="s-how">
      <SlideHead eyebrow="Before we start" title="How scoring works" />
      <div className="how-grid">
        <section className="how-card" style={{ ['--i' as string]: 0 }}>
          <div className="how-vis scale-vis" aria-hidden="true">
            <div className="sv-track">
              <div className="sv-fill" />
              <span className="sv-mark">83</span>
            </div>
            <div className="sv-ticks">
              <span>0</span>
              <span>50</span>
              <span>100</span>
            </div>
          </div>
          <h2>Every test is scored 0–100</h2>
          <p>Right answers, working code or a finished task earn points. Some tests give partial credit.</p>
        </section>
        <section className="how-card" style={{ ['--i' as string]: 1 }}>
          <div className="how-vis index-vis" aria-hidden="true">
            <div className="iv-cats">
              {shown.map((c) => (
                <span key={c.id} style={{ ['--cc' as string]: c.color }}>
                  {shortCat(c.id, c.name)}
                </span>
              ))}
              {deck.cats.length > shown.length && <span className="more">+{deck.cats.length - shown.length}</span>}
            </div>
            <span className="iv-arrow">↓</span>
            <span className="iv-index">Gauntlet Index</span>
          </div>
          <h2>One number: the Gauntlet Index</h2>
          <p>We average the tests in each category, then average the categories — so every skill counts {equal ? 'equally' : 'by its published weight'}, no matter how many tests it has.</p>
        </section>
        <section className="how-card" style={{ ['--i' as string]: 2 }}>
          <div className="how-vis ci-vis" aria-hidden="true">
            <div className="cv-track">
              <div className="cv-fill" />
              <div className="cv-ci" />
            </div>
            <div className="cv-cap">scored 70 — really somewhere from 60 to 80</div>
          </div>
          <h2>Brackets show uncertainty</h2>
          <p>How far a score could move if we ran the tests again. When two models’ brackets overlap, it’s too close to call.</p>
        </section>
        <section className="how-card" style={{ ['--i' as string]: 3 }}>
          <div className="how-vis fair-vis" aria-hidden="true">
            <span>
              <Icon.Copy /> Identical prompts
            </span>
            <span>
              <Icon.Sparkles /> Fresh every time
            </span>
            <span>
              <Icon.Lock /> No tools, no internet
            </span>
          </div>
          <h2>A fair fight</h2>
          <p>
            Every model sees every test fresh, with no tools, and gets exactly the same prompts{deck.repeats > 1 ? ` — ${deck.repeats} attempts at each question` : ''}.
          </p>
        </section>
      </div>
    </div>
  );
}

function ExplainerSlide({ deck, test }: { deck: Deck; test: DeckTest }) {
  const def = test.detail?.definition;
  const summary = test.detail?.summary;
  const program = test.detail?.program ?? (def?.kind === 'program' ? deck.programs.get(def.program) : undefined);
  const sc = friendlyScoring(def, program, deck.judgeCross);
  const example = examplePrompt(def, test.detail, test.snap);
  const picture = examplePicture(test.detail, test.snap);
  const isPrivate = summary?.source === 'private';
  const n = test.snap.caseIds.length;
  const hook = def?.hook ?? summary?.hook;
  const description = def?.description ?? summary?.description;
  const finalLine = def?.kind === 'prompt' && /FINAL ANSWER/.test(`${def.preamble ?? ''}\n${def.system ?? ''}`);
  return (
    <div className="s-exp">
      <div className="x-top">
        <CatTag cat={test.cat} />
        <span className="x-count">
          Test {test.n} of {deck.tests.length}
        </span>
        {def?.difficulty && <Difficulty value={def.difficulty} />}
      </div>
      <h1 className="x-name">{test.snap.name}</h1>
      {hook && <p className="x-hook">{hook}</p>}
      <div className="x-grid">
        <div className="x-left">
          <section>
            <h3>The challenge</h3>
            <p className="x-desc">{brief(description, 300) || 'No description provided for this test.'}</p>
          </section>
          <section>
            <h3>How it’s scored</h3>
            <p className="x-score">{def?.kind === 'program' ? brief(sc.text, 200) : sc.text}</p>
          </section>
          <div className="x-facts">
            <div>
              <b className="tnum">{n}</b>
              <span>{test.snap.kind === 'program' ? `seeded ${noun(n, 'world')}` : casesWord(test, n)}</span>
            </div>
            <div>
              <b className="tnum">×{deck.repeats}</b>
              <span>{noun(deck.repeats, 'attempt')} each</span>
            </div>
            <div>
              <b className="tnum">{deck.repeats * n}</b>
              <span>scored per model</span>
            </div>
          </div>
        </div>
        <div className="x-right">
          {isPrivate ? (
            <div className="x-card private">
              <div className="x-card-k">
                <Icon.Lock /> Held-out test
              </div>
              <p>These questions are kept private so they can never leak into an AI’s training data. You’ll see the scores — not the questions.</p>
            </div>
          ) : def?.kind === 'program' ? (
            <div className="x-card">
              <div className="x-card-k">
                <Icon.Layers /> The world
              </div>
              <p className="x-world">{brief(program?.description, 300) || 'A simulated environment the model plays turn by turn.'}</p>
              <div className="x-seeds">
                {def.seeds.slice(0, 6).map((sd) => (
                  <span key={sd} className="mono">
                    seed {sd}
                  </span>
                ))}
              </div>
              <div className="x-note">Same seed = the exact same world for every model.</div>
            </div>
          ) : picture ? (
            <VisionExplainerCard pic={picture} />
          ) : example ? (
            <div className="x-card example">
              <div className="x-card-k">
                <Icon.Inbox /> Example {casesWord(test, 1)}
              </div>
              <pre className={cx('x-prompt', example.length > 200 && 'faded')}>{example}</pre>
              <div className="x-format">
                {finalLine ? (
                  <>
                    Every answer ends with <code>FINAL ANSWER: …</code>
                  </>
                ) : (
                  <>{n > 1 ? `One of ${n} ${casesWord(test, n)} — the rest stay off screen.` : `The only ${casesWord(test, 1)} in this test.`}</>
                )}
              </div>
            </div>
          ) : (
            <div className="x-card">
              <div className="x-card-k">
                <Icon.Info /> About this test
              </div>
              <p>{summary?.description ?? '—'}</p>
            </div>
          )}
        </div>
      </div>
    </div>
  );
}

interface RaceEntry {
  id: string;
  label: string;
  sub: string;
  color: string;
  score: number | null;
  ci: [number, number] | null;
  medal: 'gold' | 'silver' | 'bronze' | null;
  rank: number | null;
  baseline: boolean;
  manual: boolean;
  agg: TestAggregate | undefined;
}

function RaceRow({
  e,
  i,
  program,
  runId,
  testId,
  nullNote,
  baseLine,
  baseLabel,
  unit,
}: {
  e: RaceEntry;
  i: number;
  program: boolean;
  runId: string;
  testId: string;
  nullNote: string;
  /** Random-guessing score (0..1) marked on the track, or null. */
  baseLine: number | null;
  baseLabel: boolean;
  /** Singular case noun: "question", "task", "run"… */
  unit: string;
}) {
  const target = e.score === null ? 0 : e.score * 100;
  const v = useCountUp(target, 1300, 280 + i * 120);
  const summary = program && !e.baseline ? e.agg?.summary : undefined;
  const stats = e.baseline
    ? 'Picks answers at random'
    : (e.agg?.skipped ?? 0) > 0 && !e.agg?.n
      ? 'Left out of this model’s average, not scored as 0'
      : e.manual
      ? 'Answers pasted by hand · time and cost not comparable'
      : `${fmtCost(e.agg?.costUsd)} for this test · ${fmtMs(e.agg?.medianCaseMs)} per ${unit}`;
  const href = `#${pathOf('runs', runId)}?tab=matrix&test=${encodeURIComponent(testId)}&c=${encodeURIComponent(e.id)}`;
  return (
    <div className={cx('rr', e.baseline && 'is-base', e.score === null && 'is-null', e.medal && `m-${e.medal}`)} style={{ ['--c' as string]: e.baseline ? 'var(--text-3)' : e.color, ['--i' as string]: i }}>
      <div className="rr-rank">{e.medal ? <PMedal kind={e.medal} /> : <span className="rr-n tnum">{e.baseline ? '' : e.rank ?? '–'}</span>}</div>
      <div className="rr-name">
        <span className="rr-sw" aria-hidden="true" />
        <div className="rr-nt">
          <div className="rr-lbl">{e.label}</div>
          <div className="rr-sub">{e.sub}</div>
        </div>
      </div>
      <div className="rr-main">
        <div className="rr-track">
          {baseLine !== null && e.score !== null && (
            <i className="rr-bmark" style={{ left: `${baseLine * 100}%` }} aria-hidden="true">
              {baseLabel && <span>random guessing</span>}
            </i>
          )}
          {e.score === null ? (
            <span className="rr-none">{(e.agg?.skipped ?? 0) > 0 && !e.agg?.n ? 'Skipped: this model can’t see images' : nullNote}</span>
          ) : (
            <>
              <div className="rr-fill" style={{ width: `${v}%` }} />
              {e.ci && e.ci[1] - e.ci[0] > 0.002 && <div className="rr-ci" style={{ left: `${e.ci[0] * 100}%`, width: `${(e.ci[1] - e.ci[0]) * 100}%` }} aria-hidden="true" />}
              <span className="rr-val tnum" style={{ left: `${v}%` }}>
                {Math.round(v)}
              </span>
            </>
          )}
        </div>
        <div className="rr-foot">
          {summary && <span className="rr-sum">{summary}</span>}
          <span className="rr-stats tnum">{stats}</span>
          {!e.baseline && e.score !== null && (
            <a className={cx('rr-link', !program && 'hover-only')} href={href}>
              {program ? '▶ Watch replay' : 'Open answers'}
            </a>
          )}
        </div>
      </div>
    </div>
  );
}

function ResultSlide({ deck, test }: { deck: Deck; test: DeckTest }) {
  const program = test.snap.kind === 'program';
  const medalEntry = deck.lb?.medals?.find((x) => x.testId === test.snap.id);
  const medalOf = (id: string): RaceEntry['medal'] => (medalEntry?.gold === id ? 'gold' : medalEntry?.silver === id ? 'silver' : medalEntry?.bronze === id ? 'bronze' : null);

  const entries: RaceEntry[] = deck.contenders.map((c) => {
    const agg = deck.rowsById.get(c.id)?.tests?.[test.snap.id];
    const score = typeof agg?.score === 'number' ? agg.score : null;
    return {
      id: c.id,
      label: c.baseline ? 'Random guessing' : c.label,
      sub: c.baseline ? 'Reference' : c.manual ? `${c.vendor} · pasted by hand` : c.vendor,
      color: c.color,
      score,
      ci: agg?.ci95 ?? null,
      medal: c.baseline ? null : medalOf(c.id),
      rank: null,
      baseline: c.baseline,
      manual: c.manual,
      agg,
    };
  });
  const medalOrder = (e: RaceEntry) => (e.medal === 'gold' ? 0 : e.medal === 'silver' ? 1 : e.medal === 'bronze' ? 2 : 3);
  // Ties are broken the way the server awarded medals, so positions and medals always agree.
  const competitors = entries.filter((e) => !e.baseline).sort((a, b) => (b.score ?? -1) - (a.score ?? -1) || medalOrder(a) - medalOrder(b) || a.label.localeCompare(b.label));
  competitors.forEach((e, i) => {
    if (e.score !== null) e.rank = i + 1;
  });
  const base = entries.find((e) => e.baseline) ?? null;
  const ordered = base ? [...competitors, base] : competitors;
  const anyScore = ordered.some((e) => e.score !== null);
  const winner = competitors.find((e) => e.medal === 'gold') ?? (competitors[0]?.score !== null ? competitors[0] : undefined);
  const pending = ordered.some((e) => (e.agg?.pendingHuman ?? 0) > 0);
  const nullNote = pending ? 'Awaiting human review' : 'No score — every attempt failed';
  const baseLine = base?.score !== null && base?.score !== undefined && base.score > 0.02 ? base.score : null;

  return (
    <div className="s-res">
      <div className="r-head">
        <div className="r-head-t">
          <div className="x-top">
            <CatTag cat={test.cat} />
            <span className="x-count">Results</span>
          </div>
          <h1 className="r-name">{test.snap.name}</h1>
        </div>
        {winner && winner.score !== null && (
          <div className="r-winner" style={{ ['--c' as string]: winner.color }}>
            <PMedal kind="gold" size="lg" />
            <div>
              <div className="k">Winner</div>
              <div className="v">{winner.label}</div>
            </div>
          </div>
        )}
      </div>
      {!anyScore ? (
        <div className="p-empty">
          <Icon.Clock />
          <h2>No scores for this test yet</h2>
          <p>{pending ? 'Answers are waiting for human review in Blind Review.' : 'This test did not produce scored results in this run.'}</p>
        </div>
      ) : (
        <div className={cx('race', program && 'with-sum', ordered.length > 8 && 'dense')} style={{ ['--rows' as string]: ordered.length }}>
          {ordered.map((e, i) => (
            <RaceRow key={e.id} e={e} i={i} program={program} runId={deck.d.manifest.id} testId={test.snap.id} nullNote={nullNote} baseLine={e.baseline ? null : baseLine} baseLabel={i === 0} unit={program ? 'run' : casesWord(test, 1)} />
          ))}
        </div>
      )}
    </div>
  );
}

function CatCells({ row, cats, dim }: { row: LeaderboardRow; cats: CategoryInfo[]; dim?: boolean }) {
  return (
    <div className="fs-cats">
      {cats.map((c) => {
        const v = row.categoryScores?.[c.id];
        const has = typeof v === 'number';
        const style: CSSProperties = has ? { background: `color-mix(in srgb, ${dim ? '#7f8b9d' : c.color} ${Math.round(8 + 62 * (v as number))}%, var(--surface-2))` } : {};
        return (
          <span key={c.id} className={cx('fs-cell', !has && 'na')} style={style} title={`${c.name}: ${has ? Math.round((v as number) * 100) : 'not scored'}`}>
            {has ? Math.round((v as number) * 100) : '–'}
          </span>
        );
      })}
    </div>
  );
}

function FinalRow({ row, place, cats, baseline }: { row: LeaderboardRow; place: number; cats: CategoryInfo[]; baseline?: boolean }) {
  const idx = typeof row.index === 'number' ? row.index : null;
  const v = useCountUp(idx ?? 0, 1100, 120);
  const medal = !baseline && place <= 3 && idx !== null ? (['gold', 'silver', 'bronze'] as const)[place - 1] : null;
  const m = row.medals ?? { gold: 0, silver: 0, bronze: 0 };
  return (
    <div className={cx('fs-row', 'fs-in', baseline && 'is-base', place === 1 && !baseline && 'first')} style={{ ['--c' as string]: baseline ? 'var(--text-3)' : row.color }}>
      <span className="fs-rank">{medal ? <PMedal kind={medal} size="lg" /> : <span className="tnum">{baseline ? '' : row.rank ?? '–'}</span>}</span>
      <span className="fs-model">
        <span className="rr-sw" aria-hidden="true" />
        <span className="fs-mt">
          <span className="fs-lbl">{baseline ? 'Random guessing' : row.label}</span>
          <span className="fs-ven">{baseline ? 'Reference' : row.vendor}</span>
        </span>
      </span>
      <span className="fs-index">
        <span className="fs-num tnum">{idx === null ? '—' : v.toFixed(1)}</span>
        <span className="fs-bar" aria-hidden="true">
          <i className="fs-fill" style={{ width: `${v}%` }} />
          {row.indexCi95 && <i className="fs-ci" style={{ left: `${row.indexCi95[0]}%`, width: `${Math.max(0.5, row.indexCi95[1] - row.indexCi95[0])}%` }} />}
        </span>
      </span>
      <CatCells row={row} cats={cats} dim={baseline} />
      <span className="fs-medals">
        {baseline ? (
          <span className="muted">—</span>
        ) : (
          (['gold', 'silver', 'bronze'] as const).map((k) => (
            <span key={k} className={cx('fs-m', !m[k] && 'zero')}>
              <PMedal kind={k} size="sm" />
              <b className="tnum">{m[k]}</b>
            </span>
          ))
        )}
      </span>
      <span className="fs-cost tnum">{row.manual ? 'by hand*' : shortCost(row.totals?.costUsd)}</span>
      <span className="fs-cpp tnum">{row.manual || baseline ? '—' : row.costPerPoint === null ? '—' : shortCost(row.costPerPoint)}</span>
    </div>
  );
}

function FinalSlide({ deck, reveal }: { deck: Deck; reveal: number }) {
  const rows = standings(deck);
  const base = baselineRow(deck);
  const n = rows.length;
  const cats = deck.cats;
  return (
    <div className="s-final">
      <SlideHead eyebrow="The verdict" title="Final standings" sub="Gauntlet Index — the overall score out of 100" />
      <div className="fs-table" style={{ ['--cats' as string]: cats.length, ['--rows' as string]: n + (base ? 1 : 0) }}>
        <div className="fs-row fs-headrow">
          <span className="fs-rank">#</span>
          <span>Model</span>
          <span>Gauntlet Index</span>
          <span className="fs-cats fs-cats-h">
            {cats.map((c, i) => (
              <span key={c.id} className={cx('fs-cat-h', i % 2 === 1 && 'lo')} title={c.name}>
                <span>{shortCat(c.id, c.name)}</span>
              </span>
            ))}
          </span>
          <span>Medals</span>
          <span className="num">Total cost</span>
          <span className="num">Cost / point</span>
        </div>
        {rows.map((r, i) =>
          i >= n - reveal ? (
            <FinalRow key={r.contestantId} row={r} place={i + 1} cats={cats} />
          ) : (
            <div key={r.contestantId} className="fs-row fs-hidden" aria-hidden="true">
              <span className="fs-rank tnum">{r.rank ?? i + 1}</span>
              <span className="fs-q">?</span>
            </div>
          ),
        )}
        {base && <FinalRow row={base} place={99} cats={cats} baseline />}
      </div>
    </div>
  );
}

function ScatterSlide({ deck }: { deck: Deck }) {
  const [ref, size] = useElementSize<HTMLDivElement>();
  const unplotted = (deck.lb?.rows ?? []).some((r) => !isBaseline(r) && typeof r.index === 'number' && !(r.totals?.costUsd > 0));
  // Leave room for the legend (and the "not plotted" note) under the plot.
  const h = Math.max(320, size.height - 36 - 58 - (unplotted ? 34 : 0));
  return (
    <div className="s-scatter">
      <SlideHead eyebrow="Value for money" title="Score vs cost" sub="Is the most expensive model worth it?" />
      <div className="sc-wrap" ref={ref}>
        {size.height > 0 && <ScoreCostScatter rows={deck.lb?.rows ?? []} mode="total" fontScale={1.72} height={h} />}
      </div>
    </div>
  );
}

function MedalsSlide({ deck }: { deck: Deck }) {
  const testName = new Map(deck.tests.map((t) => [t.snap.id, t.snap.name]));
  const golds = new Map<string, string[]>();
  for (const e of deck.lb?.medals ?? []) if (e.gold) golds.set(e.gold, [...(golds.get(e.gold) ?? []), testName.get(e.testId) ?? e.testId]);
  const rows = (deck.lb?.rows ?? [])
    .filter((r) => !isBaseline(r))
    .map((r) => ({ r, m: r.medals ?? { gold: 0, silver: 0, bronze: 0 } }))
    .sort((a, b) => olympicCompare(a.m, b.m) || (a.r.rank ?? 99) - (b.r.rank ?? 99));
  let rank = 0;
  let prev: (typeof rows)[number] | null = null;
  const ranked = rows.map((x, i) => {
    if (!prev || olympicCompare(prev.m, x.m) !== 0) rank = i + 1;
    prev = x;
    return { ...x, rank };
  });
  return (
    <div className="s-medals">
      <SlideHead eyebrow="Test by test" title="Medal table" sub={`Gold, silver and bronze on each of the ${deck.tests.length} tests`} />
      <div className="md-table" style={{ ['--rows' as string]: ranked.length }}>
        <div className="md-row md-headrow">
          <span>#</span>
          <span>Model</span>
          <span className="c">
            <PMedal kind="gold" size="sm" /> Gold
          </span>
          <span className="c">
            <PMedal kind="silver" size="sm" /> Silver
          </span>
          <span className="c">
            <PMedal kind="bronze" size="sm" /> Bronze
          </span>
          <span>Gold medals won in</span>
        </div>
        {ranked.map(({ r, m, rank: rk }, i) => {
          const won = golds.get(r.contestantId) ?? [];
          return (
            <div key={r.contestantId} className="md-row md-in" style={{ ['--c' as string]: r.color, ['--i' as string]: i }}>
              <span className="md-rank tnum">{rk}</span>
              <span className="fs-model">
                <span className="rr-sw" aria-hidden="true" />
                <span className="fs-mt">
                  <span className="fs-lbl">{r.label}</span>
                  <span className="fs-ven">{r.vendor}</span>
                </span>
              </span>
              {(['gold', 'silver', 'bronze'] as const).map((k) => (
                <span key={k} className={cx('md-n tnum', k, !m[k] && 'zero')}>
                  {m[k]}
                </span>
              ))}
              <span className="md-won">
                {won.length === 0 ? (
                  <span className="muted">—</span>
                ) : (
                  <>
                    {fitChips(won, 52).map((w) => (
                      <span key={w} className="md-chip">
                        {w}
                      </span>
                    ))}
                    {won.length > fitChips(won, 52).length && <span className="md-more">+{won.length - fitChips(won, 52).length} more</span>}
                  </>
                )}
              </span>
            </div>
          );
        })}
      </div>
    </div>
  );
}

/** As many chip labels as fit in roughly `budget` characters (always at least one). */
function fitChips(items: string[], budget: number): string[] {
  const out: string[] = [];
  let used = 0;
  for (const it of items) {
    if (out.length && used + it.length + 4 > budget) break;
    out.push(it);
    used += it.length + 4;
  }
  return out;
}

function OutroSlide({ deck }: { deck: Deck }) {
  const m = deck.d.manifest;
  const top = standings(deck)[0];
  return (
    <div className="s-outro">
      <BrandMark className="o-mark" />
      <div className="o-word">GAUNTLET</div>
      {top && typeof top.index === 'number' && (
        <div className="o-winner" style={{ ['--c' as string]: top.color }}>
          <PMedal kind="gold" size="lg" />
          <span>
            <b>{top.label}</b> wins this episode with a Gauntlet Index of <b className="tnum">{fmtIndex(top.index)}</b>
          </span>
        </div>
      )}
      <div className="o-title">Methodology &amp; prompts</div>
      <div className="o-fp mono">
        fingerprint {shortHash(m.fingerprint, 16)} · harness v{m.harnessVersion}
      </div>
      <p className="o-note">
        {deck.hasPrivate ? 'Every public prompt, answer key and scoring rule is published (held-out tests stay private).' : 'Every prompt, answer key and scoring rule is published.'} Re-run the same fingerprint and you get the same test — for any model.
      </p>
    </div>
  );
}

function SlideView({ slide, deck, reveal }: { slide: Slide; deck: Deck; reveal: number }) {
  switch (slide.kind) {
    case 'title':
      return <TitleSlide deck={deck} />;
    case 'how':
      return <HowSlide deck={deck} />;
    case 'explainer':
      return <ExplainerSlide deck={deck} test={slide.test} />;
    case 'result':
      return <ResultSlide deck={deck} test={slide.test} />;
    case 'final':
      return <FinalSlide deck={deck} reveal={reveal} />;
    case 'scatter':
      return <ScatterSlide deck={deck} />;
    case 'medals':
      return <MedalsSlide deck={deck} />;
    case 'outro':
      return <OutroSlide deck={deck} />;
  }
}

// ───────────────────────────── Page ─────────────────────────────

function useStageScale(): number {
  const calc = () => Math.min(window.innerWidth / W, window.innerHeight / H);
  const [k, setK] = useState(calc);
  useEffect(() => {
    const on = () => setK(calc());
    window.addEventListener('resize', on);
    return () => window.removeEventListener('resize', on);
  }, []);
  return k;
}

const KEYS: Array<[string, string]> = [
  ['→  Space', 'Next slide / reveal next row'],
  ['←', 'Back'],
  ['Home  End', 'First / last slide'],
  ['A', 'Auto: reveal rows every 1.2 s, then advance'],
  ['F', 'Full screen'],
  ['?', 'Show or hide this help'],
  ['Esc', 'Leave the presenter'],
];

export default function PresentPage({ runId }: { runId: string }) {
  const { query } = useRoute();
  const { meta, categories, cat } = useMeta();
  const scale = useStageScale();
  const state = useAsync(async () => {
    const d = await api.run(runId);
    const settled = await Promise.allSettled(d.manifest.tests.map((t) => api.test(t.id)));
    const details = new Map<string, TestDetail>();
    settled.forEach((r, i) => {
      if (r.status === 'fulfilled') details.set(d.manifest.tests[i].id, r.value);
    });
    return { d, details };
  }, [runId]);

  const manualProviders = useMemo(() => new Set((meta?.providers ?? []).filter((p) => p.type === 'manual').map((p) => p.id)), [meta]);
  const deck = useMemo(
    () => (state.data ? buildDeck(state.data.d, state.data.details, categories, cat, meta?.programs ?? [], meta?.settings?.judgeExcludeSameVendor, manualProviders) : null),
    [state.data, categories, cat, meta, manualProviders],
  );
  const slides = useMemo(() => (deck ? buildSlides(deck) : []), [deck]);
  const finalRows = deck ? standings(deck).length : 0;

  const requested = Math.max(1, Number(query.get('s')) || 1) - 1;
  const idx = slides.length ? Math.min(requested, slides.length - 1) : 0;
  const slide = slides[idx];

  const [reveal, setReveal] = useState(0);
  const [auto, setAuto] = useState(false);
  const [help, setHelp] = useState(false);
  const [hint, setHint] = useState(true);
  const [ctrl, setCtrl] = useState(false);
  const [isFs, setIsFs] = useState(false);
  const dirRef = useRef<1 | -1>(1);
  const prevIdx = useRef(idx);
  const ctrlTimer = useRef<number | undefined>(undefined);

  // Entering the final slide: fresh reveal going forward, fully revealed going back.
  if (prevIdx.current !== idx) {
    const forward = idx > prevIdx.current;
    prevIdx.current = idx;
    if (slide?.kind === 'final') {
      const want = forward ? 0 : finalRows;
      if (reveal !== want) setReveal(want);
    }
  }

  const go = useCallback(
    (i: number) => {
      if (!slides.length) return;
      const next = Math.max(0, Math.min(slides.length - 1, i));
      if (next === idx) return;
      dirRef.current = next > idx ? 1 : -1;
      setQuery({ s: next + 1 });
    },
    [slides.length, idx],
  );

  const next = useCallback(() => {
    setHint(false);
    if (slide?.kind === 'final' && reveal < finalRows) {
      setReveal((r) => r + 1);
      return;
    }
    if (idx >= slides.length - 1) {
      setAuto(false);
      return;
    }
    go(idx + 1);
  }, [slide, reveal, finalRows, idx, slides.length, go]);

  const prev = useCallback(() => {
    setHint(false);
    if (slide?.kind === 'final' && reveal > 0) {
      setReveal((r) => r - 1);
      return;
    }
    go(idx - 1);
  }, [slide, reveal, idx, go]);

  const toggleFs = useCallback(() => {
    if (document.fullscreenElement) void document.exitFullscreen();
    else void document.documentElement.requestFullscreen?.().catch(() => undefined);
  }, []);

  const exit = useCallback(() => {
    if (document.fullscreenElement) void document.exitFullscreen();
    navigate(pathOf('runs', runId));
  }, [runId]);

  useHotkeys({
    ArrowRight: (e) => (e.preventDefault(), next()),
    ArrowDown: (e) => (e.preventDefault(), next()),
    ' ': (e) => (e.preventDefault(), next()),
    PageDown: (e) => (e.preventDefault(), next()),
    ArrowLeft: (e) => (e.preventDefault(), prev()),
    ArrowUp: (e) => (e.preventDefault(), prev()),
    PageUp: (e) => (e.preventDefault(), prev()),
    Backspace: (e) => (e.preventDefault(), prev()),
    Home: (e) => (e.preventDefault(), go(0)),
    End: (e) => (e.preventDefault(), go(slides.length - 1)),
    f: () => toggleFs(),
    a: () => setAuto((x) => !x),
    '?': () => setHelp((h) => !h),
    h: () => setHelp((h) => !h),
    Escape: () => {
      if (help) setHelp(false);
      else if (!document.fullscreenElement) exit();
    },
  });

  // Auto mode: reveal final rows every 1.2 s, otherwise advance every 9 s.
  const stepKey = `${idx}:${reveal}`;
  const stepRef = useRef({ key: stepKey, at: Date.now() });
  if (stepRef.current.key !== stepKey) stepRef.current = { key: stepKey, at: Date.now() };
  useInterval(
    () => {
      const period = slide?.kind === 'final' && reveal < finalRows ? AUTO_REVEAL_MS : AUTO_ADVANCE_MS;
      if (Date.now() - stepRef.current.at >= period) next();
    },
    auto ? 200 : null,
  );

  useEffect(() => {
    const on = () => setIsFs(!!document.fullscreenElement);
    document.addEventListener('fullscreenchange', on);
    const t = window.setTimeout(() => setHint(false), 4500);
    return () => {
      document.removeEventListener('fullscreenchange', on);
      window.clearTimeout(t);
    };
  }, []);

  // Controls + cursor only while the pointer moves (keeps recordings clean).
  useEffect(() => {
    const show = () => {
      setCtrl(true);
      window.clearTimeout(ctrlTimer.current);
      ctrlTimer.current = window.setTimeout(() => setCtrl(false), 2200);
    };
    window.addEventListener('pointermove', show);
    return () => {
      window.removeEventListener('pointermove', show);
      window.clearTimeout(ctrlTimer.current);
    };
  }, []);

  useEffect(() => {
    if (deck) document.title = `${deck.d.manifest.name || runId} · Presenter · Gauntlet`;
  }, [deck, runId, idx]);

  if (!deck || !slide) {
    return (
      <div className="deck deck-msg">
        {state.error ? (
          <div className="deck-msg-box">
            <Icon.Alert />
            <h2>Couldn’t load this run</h2>
            <p>{state.error.message}</p>
            <div className="row" style={{ gap: 10, justifyContent: 'center' }}>
              <button className="btn" onClick={state.reload}>
                <Icon.Refresh /> Retry
              </button>
              <a className="btn ghost" href="#/present">
                Choose another run
              </a>
            </div>
          </div>
        ) : (
          <div className="deck-msg-box">
            <BrandMark className="deck-load-mark" />
            <p>Preparing the episode…</p>
          </div>
        )}
      </div>
    );
  }

  const cap = captionFor(slide, deck);
  const total = slides.length;

  return (
    <div className={cx('deck', !ctrl && 'hide-cursor')}>
      <div className="deck-stage" style={{ transform: `translate(-50%, -50%) scale(${scale})` }} data-slide={slide.kind}>
        <div className="deck-bg" aria-hidden="true">
          <div className="glow a" />
          <div className="glow b" />
          <div className="grid" />
        </div>
        <header className="d-top">
          <span className="d-brand">
            <BrandMark className="d-mark" />
            <span className="d-word">GAUNTLET</span>
          </span>
          <span className="d-run">{deck.d.manifest.name || runId}</span>
          <span className="d-spacer" />
          <span className="d-section">{sectionFor(slide, deck)}</span>
          <span className="d-prog" aria-label={`Slide ${idx + 1} of ${total}`}>
            <span className="tnum">
              {idx + 1} / {total}
            </span>
            <i>
              <b style={{ width: `${((idx + 1) / total) * 100}%` }} />
            </i>
            {auto && <em>AUTO</em>}
          </span>
        </header>
        <main className="d-body" key={idx} data-dir={dirRef.current > 0 ? 'fwd' : 'back'} aria-live="polite">
          <SlideView slide={slide} deck={deck} reveal={reveal} />
        </main>
        <footer className="d-cap" key={`cap-${idx}`}>
          <span className="d-cap-k">What you’re seeing</span>
          <span className="d-cap-t">{cap.text}</span>
          {cap.fine && <span className="d-cap-f">{cap.fine}</span>}
        </footer>
      </div>

      <div className={cx('deck-ctrl', ctrl && 'visible')}>
        <button className="btn sm icon" onClick={prev} aria-label="Previous slide" disabled={idx === 0 && reveal === 0}>
          <Icon.StepBack />
        </button>
        <button className="btn sm icon" onClick={next} aria-label="Next slide">
          <Icon.StepFwd />
        </button>
        <button className={cx('btn sm', auto && 'primary')} onClick={() => setAuto((x) => !x)} aria-pressed={auto} title="Auto (A)">
          {auto ? <Icon.Pause /> : <Icon.Play />} Auto
        </button>
        <button className="btn sm icon" onClick={toggleFs} aria-label={isFs ? 'Exit full screen' : 'Full screen'} title="Full screen (F)">
          <Icon.Maximize />
        </button>
        <button className="btn sm icon" onClick={() => setHelp((h) => !h)} aria-label="Keyboard shortcuts" title="Shortcuts (?)">
          <Icon.Keyboard />
        </button>
        <button className="btn sm" onClick={exit} title="Leave the presenter (Esc)">
          <Icon.X /> Exit
        </button>
      </div>

      {hint && !help && (
        <div className="deck-hint" aria-hidden="true">
          <kbd>→</kbd> next · <kbd>←</kbd> back · <kbd>F</kbd> full screen · <kbd>A</kbd> auto · <kbd>?</kbd> keys
        </div>
      )}

      {help && (
        <div className="deck-help" role="dialog" aria-label="Presenter shortcuts" onClick={() => setHelp(false)}>
          <div className="deck-help-box" onClick={(e) => e.stopPropagation()}>
            <h2>Presenter shortcuts</h2>
            <dl>
              {KEYS.map(([k, v]) => (
                <div key={k}>
                  <dt>
                    {k.split('  ').map((x) => (
                      <kbd key={x}>{x}</kbd>
                    ))}
                  </dt>
                  <dd>{v}</dd>
                </div>
              ))}
            </dl>
            <p className="muted">Tip: add ?s=12 to the address to open a specific slide.</p>
          </div>
        </div>
      )}
    </div>
  );
}
