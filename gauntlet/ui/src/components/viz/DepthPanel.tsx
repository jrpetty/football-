/**
 * "See in depth" for one test: what happens step by step, exactly how each answer is marked, and the key numbers.
 * Built from the test's own definition (src/core/test-depth.ts), loaded only when the viewer asks, so the summary
 * card stays short and the detail is one click away.
 */
import { useState } from 'react';
import { api } from '../../api.ts';
import { testDepth, type TestDepth } from '../../../../src/core/test-depth.ts';
import { cx } from '../ui.tsx';
import { Icon } from '../icons.tsx';

export function DepthToggle({ testId, className }: { testId: string; className?: string }) {
  const [open, setOpen] = useState(false);
  const [depth, setDepth] = useState<TestDepth | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  if (testId.startsWith('arena.')) return null;
  const toggle = async () => {
    const next = !open;
    setOpen(next);
    if (!next || depth || busy) return;
    setBusy(true);
    try {
      const d = await api.test(testId);
      setDepth(testDepth(d.definition, d.program?.scoring));
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  };
  return (
    <div className={cx('depth', open && 'open', className)}>
      <button type="button" className="depth-toggle" aria-expanded={open} onClick={toggle}>
        <Icon.ChevronDown className={cx('depth-chev', open && 'up')} />
        {open ? 'Hide the detail' : 'See in depth'}
        <span className="depth-sub">step by step, and exactly how each answer is marked</span>
      </button>
      {open && (
        <div className="depth-body">
          {error && <p className="muted">Could not load the detail: {error}</p>}
          {busy && !depth && <p className="muted">Loading…</p>}
          {depth && <DepthView depth={depth} />}
        </div>
      )}
    </div>
  );
}

export function DepthView({ depth }: { depth: TestDepth }) {
  return (
    <div className="depth-view">
      <ul className="depth-facts" aria-label="Key numbers">
        {depth.facts.map((f) => (
          <li key={f.label}>
            <span>{f.label}</span>
            <b>{f.value}</b>
          </li>
        ))}
      </ul>
      <div className="depth-cols">
        <section>
          <h3>What happens, step by step</h3>
          <ol className="depth-steps">
            {depth.steps.map((s) => (
              <li key={s.title}>
                <b>{s.title}.</b> {s.detail}
              </li>
            ))}
          </ol>
        </section>
        <section>
          <h3>How each answer is marked</h3>
          <ul className="depth-marking">
            {depth.marking.map((m, i) => (
              <li key={i}>{m}</li>
            ))}
          </ul>
        </section>
      </div>
    </div>
  );
}
