/**
 * Building blocks of the customers, comms and data pictures: one spec per test (its documents, the model's
 * decisions, the pictures) turned into the two things the app asks for — a case visual for the Result Inspector and
 * the Presenter, and the three close-up beats (the question · the model's answer · the result) — plus the shared
 * paper, stamp, mark and mini-card pieces every document is drawn with.
 */
import { useState } from 'react';
import type { CSSProperties, ReactNode } from 'react';
import type { CaseVisualInput } from '../../../../../src/presenter/visuals/common.ts';
import type { KindProps } from '../../../components/viz/closeup/types.ts';
import { BeatLayout, Mark, type Beat } from '../../../components/viz/closeup/parts.tsx';
import { VizFrame, type VizMode, type VizTone } from '../../../components/viz/VizFrame.tsx';
import { cx } from '../../../components/ui.tsx';
import { caseVerdict } from './kit.ts';
import '../../../components/viz/closeup/closeup.css';
import './docs.css';

export type Tone = 'good' | 'bad' | 'warn' | 'neutral' | 'info';

/** Everything one test needs to be drawn. D = the case's documents, A = the model's decisions against the key. */
export interface DocSpec<D, A> {
  /** The generic family to fall back on (and to report to the registry). */
  family: 'business' | 'extraction';
  caption: string;
  /** The documents, read from the prompt (and, for the answer side, the key). Null: cannot be drawn. */
  doc: (input: CaseVisualInput) => D | null;
  /** The model's decisions against the key. Null: the reply has no answer that could be read. */
  answer: (input: CaseVisualInput, doc: D) => A | null;
  /** One line under the question's title ("15 emails · Monday 9 March"). */
  sub: (doc: D) => string;
  Question: (p: { doc: D }) => JSX.Element;
  /** The correct answer, for the question-only view ("Reveal the answer"). */
  Key: (p: { doc: D }) => JSX.Element;
  Answer: (p: { doc: D; ans: A; input: CaseVisualInput }) => JSX.Element;
  /** What the mistakes would have cost, and which they were. */
  Result: (p: { doc: D; ans: A; input: CaseVisualInput }) => JSX.Element | null;
  /** Interactive extras below the answer (a wall of mini-cards that open one document). */
  AnswerExtra?: (p: { doc: D; ans: A }) => JSX.Element | null;
  QuestionExtra?: (p: { doc: D }) => JSX.Element | null;
  /** One plain sentence: what the model did and what that means. */
  sentence: (doc: D, ans: A, input: CaseVisualInput) => string;
  /** The headline count for the inspector ("13 of 15 emails right"). */
  headline: (doc: D, ans: A) => string;
  alt: {
    question: (doc: D) => string;
    key: (doc: D) => string;
    answer: (doc: D, ans: A) => string;
  };
}

export interface DocVisual {
  family: 'business' | 'extraction';
  caption: string;
  build: (input: CaseVisualInput) => unknown | null;
  render: (model: unknown, mode: VizMode, input: CaseVisualInput, ctx: unknown) => ReactNode;
  /** The close-up's three beats for this test. */
  Beats: (p: KindProps) => JSX.Element;
  /** The documents could be drawn (used to decide whether the close-up can open a case). */
  canDraw: (input: CaseVisualInput) => boolean;
}

function safe<T>(f: () => T): T | null {
  try {
    return f();
  } catch (err) {
    console.warn('Document picture could not be built; the plain view stands.', err);
    return null;
  }
}

/** Turn a spec into the registry entry. */
export function docVisual<D, A>(spec: DocSpec<D, A>): DocVisual {
  const build = (input: CaseVisualInput) =>
    safe(() => {
      const doc = spec.doc(input);
      if (!doc) return null;
      const ans = spec.answer(input, doc);
      return ans ? { doc, ans } : null;
    });
  const Beats = (p: KindProps) => <DocBeats spec={spec} p={p} />;
  return {
    family: spec.family,
    caption: spec.caption,
    build,
    Beats,
    canDraw: (input) => !!safe(() => spec.doc(input)),
    render: (model, mode, input) => {
      const { doc, ans } = model as { doc: D; ans: A };
      const v = caseVerdict(input.score, String(input.status));
      const tone: VizTone = v.tone === 'pass' ? 'good' : v.tone === 'partial' ? 'half' : v.tone === 'fail' ? 'bad' : 'neutral';
      return (
        <VizFrame mode={mode} tone={tone} className="lc-inline-root cd-frame" eyebrow="The job as real paperwork · the model’s decisions on it" headline={spec.headline(doc, ans)} big={input.score === null ? undefined : `${Math.round(input.score * 100)}`} bigSub="of 100">
          <div className={cx('cd-root', `cd-${mode}`)}>
            <spec.Answer doc={doc} ans={ans} input={input} />
            {spec.AnswerExtra && <spec.AnswerExtra doc={doc} ans={ans} />}
            <spec.Result doc={doc} ans={ans} input={input} />
            <p className="cd-sentence">{spec.sentence(doc, ans, input)}</p>
          </div>
        </VizFrame>
      );
    },
  };
}

function DocBeats<D, A>({ spec, p }: { spec: DocSpec<D, A>; p: KindProps }) {
  const qDoc = safe(() => spec.doc(p.q));
  const aDoc = p.a ? (safe(() => spec.doc(p.a!)) ?? qDoc) : qDoc;
  const ans = p.a && aDoc ? safe(() => spec.answer(p.a!, aDoc)) : null;
  const doc = aDoc ?? qDoc;
  if (!doc) {
    return <div className="lc-loading">This case’s documents could not be drawn; “Show the full prompt” in the Result Inspector has the exact text.</div>;
  }
  const question: Beat = { node: <div className="cd-root"><spec.Question doc={doc} /></div>, alt: spec.alt.question(doc), sub: spec.sub(doc) };
  const keyBeat: Beat = { node: <div className="cd-root"><spec.Key doc={doc} /></div>, alt: spec.alt.key(doc) };
  let answer: Beat | null = null;
  let result: Beat | null = null;
  let sentence: string | null = null;
  if (p.a) {
    if (ans) {
      answer = { node: <div className="cd-root"><spec.Answer doc={doc} ans={ans} input={p.a} /></div>, alt: spec.alt.answer(doc, ans), sub: spec.headline(doc, ans) };
      const r = <spec.Result doc={doc} ans={ans} input={p.a} />;
      result = { node: <div className="cd-root">{r}</div>, alt: spec.headline(doc, ans) };
      sentence = spec.sentence(doc, ans, p.a);
    } else {
      answer = { node: <p className="cd-empty">The reply has no answer in the form the task asked for, so nothing could be marked.</p>, alt: 'No readable answer.' };
      sentence = 'The reply could not be read as an answer, so it scored nothing.';
    }
  }
  return (
    <div className={cx('cd-beats', !p.a && 'q-only')}>
      <BeatLayout
        question={question}
        prompt={p.prompt}
        answer={answer}
        reply={p.reply}
        result={result}
        verdict={p.verdict}
        sentence={sentence}
        answerHead={p.answerHead}
        keyBeat={keyBeat}
        interactiveAnswer={ans && spec.AnswerExtra ? <spec.AnswerExtra doc={doc} ans={ans} /> : undefined}
        interactiveResult={undefined}
        interactiveKey={undefined}
      />
      {!p.a && spec.QuestionExtra && <spec.QuestionExtra doc={doc} />}
    </div>
  );
}

// ───────────────────────────── shared pieces ─────────────────────────────

/** A sheet of paper (an email, a ticket, a contract page, a CV…). */
export function Paper({ children, className, title, style }: { children: ReactNode; className?: string; title?: ReactNode; style?: CSSProperties }) {
  return (
    <div className={cx('cd-paper', className)} style={style}>
      {title && <div className="cd-paper-title">{title}</div>}
      {children}
    </div>
  );
}

/** A rubber stamp: "REFUND £95.00", "ESCALATE · P1", "SCAM". */
export function Stamp({ children, tone, small, sub }: { children: ReactNode; tone: Tone; small?: boolean; sub?: ReactNode }) {
  return (
    <span className={cx('cd-stamp', `t-${tone}`, small && 'sm')}>
      <b>{children}</b>
      {sub && <small>{sub}</small>}
    </span>
  );
}

/** ✓ / ✕ in a coloured disc. */
export function Tick({ ok, title }: { ok: boolean | null; title?: string }) {
  return (
    <span className={cx('cd-tick', ok === null ? 't-none' : ok ? 't-good' : 't-bad')} title={title}>
      <Mark tone={ok === null ? 'none' : ok ? 'pass' : 'fail'} />
    </span>
  );
}

/** "should be X" beside a wrong value. */
export function Fix({ children }: { children: ReactNode }) {
  return <span className="cd-fix">should be {children}</span>;
}

/** A coloured label chip. */
export function Chip({ children, tone, style, strike, title }: { children: ReactNode; tone?: Tone; style?: CSSProperties; strike?: boolean; title?: string }) {
  return (
    <span className={cx('cd-chip', tone && `t-${tone}`, strike && 'strike')} style={style} title={title}>
      {children}
    </span>
  );
}

/** The business-impact lines under a result: each one taken from the case's own figures. */
export function Impact({ lines }: { lines: Array<{ tone: Tone; text: ReactNode }> }) {
  if (!lines.length) return null;
  return (
    <ul className="cd-impact">
      {lines.map((l, i) => (
        <li key={i} className={`t-${l.tone}`}>
          <span className="cd-impact-dot" aria-hidden="true" />
          <span>{l.text}</span>
        </li>
      ))}
    </ul>
  );
}

/** Count tiles: "13 / 15 emails labelled right". */
export function Tally({ items }: { items: Array<{ n: number; of: number; label: string }> }) {
  return (
    <div className="cd-tally">
      {items.map((t) => {
        const tone = t.n === t.of ? 'good' : t.n >= t.of * 0.8 ? 'warn' : 'bad';
        return (
          <div key={t.label} className={cx('cd-tile', `t-${tone}`)}>
            <b className="tnum">
              {t.n}
              <small>/{t.of}</small>
            </b>
            <span>{t.label}</span>
          </div>
        );
      })}
    </div>
  );
}

/** A colour legend for a picture. */
export function Legend({ items }: { items: Array<{ tone: Tone; label: string }> }) {
  return (
    <ul className="cd-legend" aria-hidden="true">
      {items.map((i) => (
        <li key={i.label}>
          <i className={`t-${i.tone}`} />
          {i.label}
        </li>
      ))}
    </ul>
  );
}

export interface MiniItem {
  id: string;
  label: string;
  tone: Tone;
  title?: string;
}

/**
 * A wall of mini-cards for big cases: one card per document, coloured right or wrong; clicking a card opens that
 * document full size underneath.
 */
export function MiniWall({ items, render, noun, note }: { items: MiniItem[]; render: (id: string) => ReactNode; noun: string; note?: string }) {
  const [open, setOpen] = useState<string | null>(null);
  if (!items.length) return null;
  return (
    <div className="cd-wall-wrap">
      <div className="cd-wall-t">
        {note ?? `Every ${noun}`} · <span className="muted">pick one to open it</span>
      </div>
      <div className="cd-wall" role="list">
        {items.map((it) => (
          <button key={it.id} type="button" role="listitem" className={cx('cd-mini', `t-${it.tone}`, open === it.id && 'sel')} title={it.title ?? it.label} aria-pressed={open === it.id} onClick={() => setOpen((o) => (o === it.id ? null : it.id))}>
            {it.label}
          </button>
        ))}
      </div>
      {open && (
        <div className="cd-wall-open">
          <div className="cd-wall-open-bar">
            <b>{items.find((i) => i.id === open)?.title ?? open}</b>
            <button type="button" className="btn xs ghost" onClick={() => setOpen(null)}>
              Close
            </button>
          </div>
          <div className="cd-root">{render(open)}</div>
        </div>
      )}
    </div>
  );
}

/** "+ 12 more emails (see every one below)" */
export function MoreLine({ n, noun }: { n: number; noun: string }) {
  if (n <= 0) return null;
  return <div className="cd-more">+ {n.toLocaleString('en-GB')} more {noun}</div>;
}
