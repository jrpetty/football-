/**
 * A real question from a test (GET /api/tests/:id/sample), laid out for a viewer:
 * the prompt (trimmed), its picture for vision tests, the opening situation for
 * simulations and games, and the answer only behind a "Reveal answer" click.
 */
import { useLayoutEffect, useRef, useState } from 'react';
import { api } from '../../api.ts';
import { useAsync } from '../../hooks.ts';
import type { TestSample } from '../../types.ts';
import { testImageUrl } from '../../vision.ts';
import { cx } from '../ui.tsx';
import { Icon } from '../icons.tsx';
import '../../styles/explain.css';

/** Loads the sample of a test (no answer). */
export function useTestSample(testId: string | null | undefined) {
  return useAsync<TestSample | null>(() => (testId ? api.testSample(testId) : Promise.resolve(null)), [testId]);
}

const fmtInt = (n: number) => n.toLocaleString('en-GB');

export function SampleQuestion({
  sample,
  loading,
  size = 'md',
  reveal = true,
  title,
}: {
  sample: TestSample | null | undefined;
  loading?: boolean;
  size?: 'md' | 'slide';
  /** Offer the "Reveal answer" button when the case has an answer key. */
  reveal?: boolean;
  title?: string;
}) {
  const [answer, setAnswer] = useState<{ answer?: string; note?: string } | null>(null);
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  // Fade the bottom of the question only when it really runs past its box (or was shortened).
  const textRef = useRef<HTMLPreElement>(null);
  const [overflowing, setOverflowing] = useState(false);
  useLayoutEffect(() => {
    const el = textRef.current;
    if (!el) return;
    const check = () => setOverflowing(el.scrollHeight > el.clientHeight + 2);
    check();
    const ro = typeof ResizeObserver === 'function' ? new ResizeObserver(check) : null;
    ro?.observe(el);
    return () => ro?.disconnect();
  }, [sample?.text]);
  if (loading && !sample) {
    return (
      <div className={cx('sq', `sq-${size}`, 'is-loading')}>
        <div className="sq-k">{title ?? 'A real question from this test'}</div>
        <div className="sq-skel" />
      </div>
    );
  }
  if (!sample) return null;
  if (sample.private) {
    return (
      <div className={cx('sq', `sq-${size}`, 'is-private')}>
        <div className="sq-k">
          <Icon.Lock /> Held-out test
        </div>
        <p className="sq-sit">These questions are kept private so they can never leak into an AI’s training data.</p>
      </div>
    );
  }
  const program = sample.kind === 'program';
  const heading = title ?? (program ? 'How it starts' : sample.images.length ? 'What the models are shown' : 'A real question from this test');
  const doReveal = async () => {
    setBusy(true);
    setErr(null);
    try {
      const full = await api.testSample(sample.testId, true);
      setAnswer({ answer: full.answer, note: full.answerNote });
    } catch (e) {
      setErr((e as Error).message);
    } finally {
      setBusy(false);
    }
  };
  const img = sample.images.find((i) => i.path);
  // On a slide the picture is the star: keep only the question's first paragraph under it.
  const text = img && size === 'slide' ? sample.text.split(/\n\s*\n/)[0]! : sample.text;
  return (
    <div className={cx('sq', `sq-${size}`, img && 'has-img', program && 'is-program')}>
      <div className="sq-k">
        {program ? <Icon.Play /> : img ? <Icon.Image /> : <Icon.Inbox />}
        {heading}
        {sample.caseCount > 1 && <span className="sq-of">{program ? `world 1 of ${sample.caseCount}` : `1 of ${sample.caseCount}`}</span>}
      </div>
      {sample.situation && <p className="sq-sit">{sample.situation}</p>}
      {img && (
        <div className="sq-img">
          <img src={testImageUrl(img.path!)} alt={`Test picture ${img.name}`} />
        </div>
      )}
      {sample.context && !program && (
        <div className="sq-ctx">
          <span className="sq-ctx-k">{sample.contextKind === 'system' ? 'Standing rules the model is given' : 'Said before every question'}</span>
          {sample.context}
        </div>
      )}
      {sample.text ? (
        <div className={cx('sq-text-wrap', sample.tail && 'has-tail')}>
          {program && <div className="sq-sub">The first message the model receives</div>}
          <pre ref={textRef} className={cx('sq-text', !sample.tail && (sample.truncated || overflowing) && 'faded')}>
            {text}
          </pre>
          {sample.tail && (
            <>
              <div className="sq-gap">
                <span>{sample.skippedWords ? `${fmtInt(sample.skippedWords)} words not shown` : 'Middle not shown'}</span>
              </div>
              <pre className="sq-text sq-tail">{sample.tail}</pre>
            </>
          )}
        </div>
      ) : (
        !sample.situation && <p className="sq-sit muted">No sample available for this test.</p>
      )}
      <div className="sq-foot">
        {sample.turns > 1 && <span className="sq-note">First of {sample.turns} messages in this conversation</span>}
        {reveal && sample.hasAnswer && !answer && (
          <button type="button" className="btn sm sq-reveal" onClick={() => void doReveal()} disabled={busy}>
            <Icon.Eye /> {busy ? 'Loading…' : 'Reveal answer'}
          </button>
        )}
        {err && <span className="sq-note bad-text">{err}</span>}
      </div>
      {answer && (
        <div className="sq-answer">
          <div className="sq-ans-k">
            <Icon.Check /> Answer
          </div>
          <div className="sq-ans">{answer.answer ?? 'Not recorded'}</div>
          {answer.note && <div className="sq-ans-note">{answer.note}</div>}
        </div>
      )}
    </div>
  );
}
