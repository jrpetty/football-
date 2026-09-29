/** Centre of the Grading Station: what was asked, what the model produced (universal viewer), what the machine checked, and the answer key. */
import { useMemo, useState } from 'react';
import { Icon } from '../components/icons.tsx';
import { cx } from '../components/ui.tsx';
import { prettyJson } from '../format.ts';
import { UniversalViewer } from '../components/viewer/UniversalViewer.tsx';
import { outputFilesFor, primaryFile } from '../components/viewer/outputFiles.ts';
import { VisionImage } from '../components/VisionImage.tsx';
import { testImageUrl } from '../vision.ts';
import type { StationItem } from './gradingApi.ts';

function showKey(v: unknown): string {
  if (v === undefined || v === null || v === '') return '—';
  if (typeof v === 'string') return v;
  if (Array.isArray(v) && v.every((x) => typeof x === 'string')) return v.length > 1 ? `${v[0]}   (also accepted: ${v.slice(1).join(', ')})` : String(v[0]);
  return prettyJson(v);
}

export function Brief({ item }: { item: StationItem }) {
  const [open, setOpen] = useState(item.spec.kind !== 'simulation');
  const r = item.rendered;
  if (!r) {
    return (
      <section className="gs-card gs-brief">
        <div className="gs-card-k">The brief</div>
        <p className="gs-brief-sim">{item.explainer?.opening ?? item.test?.description ?? 'A simulation: the program writes a new prompt every turn from the world state. Every turn is in the replay and transcript.'}</p>
      </section>
    );
  }
  const long = r.turns.join('').length > 900;
  return (
    <section className="gs-card gs-brief">
      <button type="button" className="gs-card-k gs-toggle" onClick={() => setOpen(!open)} aria-expanded={open}>
        <Icon.ChevronDown className={cx('chev', !open && 'closed')} /> The exact brief the model got {r.turns.length > 1 ? `(${r.turns.length} turns)` : ''}
      </button>
      {open && (
        <div className={cx('gs-brief-body', long && 'long')}>
          {r.images && r.images.length > 0 && (
            <div className="gs-brief-imgs">
              {r.images.map((im, i) => (
                <VisionImage key={i} src={im.path ? testImageUrl(im.path) : ''} name={im.file} size="md" />
              ))}
            </div>
          )}
          {r.system && (
            <div className="gs-turn sys">
              <span className="gs-turn-k">System</span>
              <pre>{r.system}</pre>
            </div>
          )}
          {r.turns.map((t, i) => (
            <div key={i} className="gs-turn">
              {r.turns.length > 1 && <span className="gs-turn-k">Turn {i + 1}</span>}
              <pre>{t}</pre>
            </div>
          ))}
        </div>
      )}
    </section>
  );
}

export function ChecksAndKey({ item }: { item: StationItem }) {
  const d = item.result.scoreDetail ?? {};
  const items = d.items ?? [];
  const key = item.spec.answerKey;
  const showAnswer = d.extracted !== undefined && typeof d.extracted === 'string' && d.extracted.length < 400 && !/^[[{]/.test(d.extracted.trim());
  return (
    <div className="gs-two">
      <section className="gs-card">
        <div className="gs-card-k">What the machine checked</div>
        {items.length > 0 ? (
          <ul className="gs-checks">
            {items.map((it, i) => (
              <li key={i} className={it.passed ? 'pass' : 'fail'}>
                <span className="ck">{it.passed ? '✓' : '✕'}</span>
                <span>{it.label}</span>
                {it.detail && <span className="muted gs-ck-d">{it.detail}</span>}
              </li>
            ))}
          </ul>
        ) : item.spec.checklist.length ? (
          <ul className="gs-checks plan">
            {item.spec.checklist.map((c) => (
              <li key={c.id}>
                <span className="ck">•</span>
                <span>{c.label}</span>
              </li>
            ))}
          </ul>
        ) : (
          <div className="muted">Nothing is checked by machine here: it is graded against the rubric.</div>
        )}
        {showAnswer && (
          <div className={cx('gs-answer', item.result.passed ? 'ok' : 'bad')}>
            <span className="gs-card-k">Model’s answer</span>
            <b>{d.extracted as string}</b>
            {d.formatOk === false && <span className="warn-text"> · answer format not followed</span>}
          </div>
        )}
        {Array.isArray(d.skippedChecks) && d.skippedChecks.length > 0 && <div className="muted gs-small">Browser checks skipped (no browser on the machine that ran it): {(d.skippedChecks as string[]).join(', ')}</div>}
      </section>
      <section className="gs-card">
        <div className="gs-card-k">Answer key</div>
        {key && (key.expected !== undefined || key.display) ? (
          <>
            <pre className="gs-key">{key.display ?? showKey(key.expected)}</pre>
            <div className="muted gs-small">{key.rule}</div>
            {key.lure && (
              <div className="gs-small">
                <b>The trap answer:</b> {key.lure}
              </div>
            )}
          </>
        ) : item.spec.formula ? (
          <div className="gs-formula">{item.spec.formula}</div>
        ) : (
          <div className="muted">No fixed answer: graded against the rubric{item.spec.rubricText ? ' on the right' : ''}.</div>
        )}
        {key?.notes && (
          <details className="gs-notes">
            <summary>Auditor notes (never shown to models)</summary>
            <p>{key.notes}</p>
          </details>
        )}
      </section>
    </div>
  );
}

export function OutputViewer({ item, height, modelLabel }: { item: StationItem; height: number; modelLabel: string }) {
  const files = useMemo(
    () =>
      outputFilesFor(item.result, {
        runId: item.result.runId,
        answerKey: item.spec.answerKey?.expected,
        replayContext: { testName: item.test?.name ?? item.result.testId, modelLabel, seed: item.result.seed, caseId: item.result.caseId, summary: item.result.summary, score: item.result.score, detail: item.result.scoreDetail },
      }),
    [item, modelLabel],
  );
  const [active, setActive] = useState<string | undefined>(undefined);
  const first = primaryFile(files);
  return <UniversalViewer key={item.result.key} files={files} height={height} activeId={active ?? first} onActive={setActive} emptyText="This result recorded no output (the call failed before replying)." />;
}
