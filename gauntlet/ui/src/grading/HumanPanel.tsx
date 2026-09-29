/**
 * The human grading panel: the test's real rubric as anchor buttons, a
 * requirement checklist, or labels — all driven by the keyboard (see
 * rubricKeys.ts) — plus notes and the running total.
 */
import { forwardRef } from 'react';
import type { GradingSpec, RubricCriterion } from '../../../src/grading/spec.ts';
import { cx } from '../components/ui.tsx';
import { draftScore, effectivePoints, type RubricDraft, type Stop } from './rubricKeys.ts';

function keyFor(c: RubricCriterion, v: number): string {
  if (c.max === 10 && v === 10) return '0';
  if (c.max === 10 && v === 0) return 'X';
  return Number.isInteger(v) && v <= 9 ? String(v) : '';
}

function stopIs(s: Stop | undefined, x: Stop): boolean {
  if (!s || s.type !== x.type) return false;
  if (s.type === 'criterion' && x.type === 'criterion') return s.id === x.id;
  if (s.type === 'req' && x.type === 'req') return s.criterion === x.criterion && s.req === x.req;
  return s.type === 'labels';
}

function Criterion({ c, value, focused, onSet, onFocus }: { c: RubricCriterion; value: number | undefined; focused: boolean; onSet: (v: number) => void; onFocus: () => void }) {
  // Whole points as buttons (keys 0–9); half points with ←/→.
  const discrete = c.max - c.min <= 10;
  const values = discrete ? Array.from({ length: Math.floor(c.max - c.min) + 1 }, (_, i) => c.min + i) : [];
  const anchorAt = (v: number) => c.anchors.find((a) => a.value === v);
  const active = value !== undefined ? c.anchors.reduce((best, a) => (Math.abs(a.value - value) < Math.abs(best.value - value) ? a : best), c.anchors[0]!) : null;
  return (
    <div className={cx('hp-crit', focused && 'focus', value !== undefined && 'done')} onClick={onFocus}>
      <div className="hp-crit-head">
        <span className="hp-crit-label">{c.label}</span>
        <span className="hp-crit-val tnum">
          {value === undefined ? '–' : value}
          <span className="muted">/{c.max}</span>
        </span>
      </div>
      {discrete && values.length <= 11 ? (
        <div className="hp-anchors" role="radiogroup" aria-label={c.label}>
          {values.map((v) => {
            const a = anchorAt(v);
            const k = keyFor(c, v);
            return (
              <button key={v} type="button" role="radio" aria-checked={value === v} className={cx('hp-anchor', value === v && 'on', a && 'named')} onClick={() => onSet(v)} title={a?.text}>
                <span className="hp-anchor-v tnum">{v}</span>
                {k && k !== String(v) && <kbd>{k}</kbd>}
              </button>
            );
          })}
        </div>
      ) : (
        <input type="range" min={c.min} max={c.max} step={c.step} value={value ?? c.min} onChange={(e) => onSet(Number(e.target.value))} aria-label={c.label} />
      )}
      <div className="hp-anchor-text">{active ? active.text : c.help ? c.help : 'Pick a score.'}</div>
    </div>
  );
}

export const HumanPanel = forwardRef<HTMLTextAreaElement, {
  spec: GradingSpec;
  draft: RubricDraft;
  stops: Stop[];
  focus: number;
  onFocus: (i: number) => void;
  onChange: (d: RubricDraft) => void;
  note: string;
  onNote: (s: string) => void;
}>(function HumanPanel({ spec, draft, stops, focus, onFocus, onChange, note, onNote }, noteRef) {
  const pts = effectivePoints(spec, draft);
  const score = draftScore(spec, draft);
  const cur = stops[focus];
  const idx = (s: Stop) => stops.findIndex((x) => stopIs(x, s));
  return (
    <div className="hp">
      {spec.labels?.length ? (
        <div className="hp-labels" role="radiogroup" aria-label="Pick one label">
          {spec.labels.map((l) => (
            <button key={l.id} type="button" role="radio" aria-checked={draft.label === l.id} className={cx('hp-label', draft.label === l.id && 'on', !l.applies && 'dim', cur?.type === 'labels' && 'focus')} onClick={() => onChange({ ...draft, label: l.id })}>
              <kbd>{l.key}</kbd>
              <span className="hp-label-t">
                <b>{l.id.replace(/_/g, ' ').toLowerCase()}</b>
                <span>{l.description}</span>
              </span>
              <span className={cx('hp-label-s tnum', l.score >= 1 ? 'good' : l.score <= 0 ? 'bad' : 'mid')}>{Math.round(l.score * 100)}</span>
            </button>
          ))}
          <div className="muted hp-foot-note">Faded labels are for the other kind of question; they are still allowed.</div>
        </div>
      ) : (
        spec.criteria.map((c) =>
          c.requirements?.items.length ? (
            <div key={c.id} className={cx('hp-crit', 'reqs', pts[c.id] !== undefined && 'done')}>
              <div className="hp-crit-head">
                <span className="hp-crit-label">{c.label}</span>
                <span className="hp-crit-val tnum">
                  {pts[c.id] ?? '–'}
                  <span className="muted">/{c.max}</span>
                </span>
              </div>
              <div className="hp-req-rule muted">
                {c.requirements.mode === 'share' ? 'Each requirement earns an equal share; half met earns half.' : c.requirements.mode === 'deduct' ? `Start at ${c.max}; lose a point for each one missing or broken.` : `${c.max} only if every one is met.`}
              </div>
              <ol className="hp-reqs">
                {c.requirements.items.map((it) => {
                  const k = `${c.id}:${it.id}`;
                  const st = draft.requirements[k];
                  const i = idx({ type: 'req', criterion: c.id, req: it.id });
                  return (
                    <li key={it.id} className={cx('hp-req', st, i === focus && 'focus')} onClick={() => onFocus(i)}>
                      <span className="hp-req-id">{it.id}</span>
                      <span className="hp-req-t">{it.text}</span>
                      <span className="hp-req-btns">
                        <button type="button" className={cx('rq met', st === 'met' && 'on')} onClick={() => onChange({ ...draft, requirements: { ...draft.requirements, [k]: 'met' } })} title="Met (1)" aria-label="Met">
                          ✓
                        </button>
                        {c.requirements!.mode === 'share' && (
                          <button type="button" className={cx('rq partial', st === 'partial' && 'on')} onClick={() => onChange({ ...draft, requirements: { ...draft.requirements, [k]: 'partial' } })} title="Half met (2)" aria-label="Half met">
                            ½
                          </button>
                        )}
                        <button type="button" className={cx('rq missed', st === 'missed' && 'on')} onClick={() => onChange({ ...draft, requirements: { ...draft.requirements, [k]: 'missed' } })} title="Missed (0)" aria-label="Missed">
                          ✕
                        </button>
                      </span>
                    </li>
                  );
                })}
              </ol>
            </div>
          ) : (
            <Criterion key={c.id} c={c} value={draft.criteria[c.id]} focused={cur?.type === 'criterion' && cur.id === c.id} onFocus={() => onFocus(idx({ type: 'criterion', id: c.id }))} onSet={(v) => onChange({ ...draft, criteria: { ...draft.criteria, [c.id]: v } })} />
          ),
        )
      )}
      {spec.rules.length > 0 && (
        <div className="hp-rules">
          {spec.rules.map((r) => (
            <div key={r}>⚠ {r}</div>
          ))}
        </div>
      )}
      <label className="hp-note">
        <span className="label">
          Notes <kbd>N</kbd>
        </span>
        <textarea ref={noteRef} className="textarea" rows={2} placeholder="What you noticed (stored with your grade)" value={note} onChange={(e) => onNote(e.target.value)} />
      </label>
      <div className={cx('hp-total', score === null && 'pending')}>
        <span className="hp-total-k">Your grade</span>
        <span className="hp-total-v tnum">{score === null ? '—' : (score * 10).toFixed(1)}</span>
        <span className="hp-total-u">/ 10</span>
        {score === null && <span className="muted hp-total-hint">Grade every line to save</span>}
      </div>
    </div>
  );
});
