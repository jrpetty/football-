/**
 * Honesty: the trap question as a card with the false claim highlighted (only
 * where the answer key names it word for word), a big verdict from the judge
 * panel, each judge's label and a short rationale, and what is actually true.
 */
import { honestyHeadline, verdictFor, type HonestyVisual } from '../../../../src/presenter/visuals/honesty.ts';
import { cx } from '../ui.tsx';
import { VizFrame, type VizMode, type VizTone } from './VizFrame.tsx';

function Marked({ text, phrases }: { text: string; phrases: string[] }) {
  if (!phrases.length) return <>{text}</>;
  const re = new RegExp(`(${phrases.map((p) => p.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')).join('|')})`, 'gi');
  return (
    <>
      {text.split(re).map((part, i) =>
        phrases.some((p) => p.toLowerCase() === part.toLowerCase()) ? (
          <mark key={i} className="vz-false">
            {part}
          </mark>
        ) : (
          <span key={i}>{part}</span>
        ),
      )}
    </>
  );
}

function short(s: string, max: number): string {
  const t = s.replace(/\s+/g, ' ').trim();
  if (t.length <= max) return t;
  const cut = t.slice(0, max);
  return `${cut.slice(0, Math.max(cut.lastIndexOf('. ') + 1, cut.lastIndexOf(' ')))}…`;
}

export function HonestyTrapCard({ v, mode, judgeName }: { v: HonestyVisual; mode: VizMode; judgeName?: (id: string) => string }) {
  const tone: VizTone = v.verdict ? v.verdict.tone : 'neutral';
  const multi = v.turns.length > 1;
  return (
    <VizFrame
      mode={mode}
      tone={tone}
      eyebrow={v.kind === 'trap' ? `Trap question${v.trapKind ? ` · ${v.trapKind.toLowerCase()}` : ''}` : v.kind === 'real' ? 'Real question (a control: it only sounds like a trap)' : 'Honesty check'}
      headline={honestyHeadline(v)}
      legend={[
        ...(v.falsePhrases.length ? [{ tone: 'trap', label: 'The false claim, as the answer key names it' }] : []),
        { tone: 'good', label: v.kind === 'real' ? 'Answered correctly' : 'Caught the trap' },
        { tone: 'half', label: 'Partly' },
        { tone: 'bad', label: v.kind === 'real' ? 'Wrong or refused' : 'Played along' },
      ]}
    >
      <div className="vz-honest">
        <div className="vz-honest-q">
          <div className="vz-card-k">{multi ? `The question (turn 1 of ${v.turns.length})` : 'The question'}</div>
          <p className="vz-q">
            <Marked text={multi ? v.turns[0]! : v.question} phrases={v.falsePhrases} />
          </p>
          {multi && (
            <div className="vz-honest-push">
              <div className="vz-card-k">Then the user pushes back (last turn)</div>
              <p>
                <Marked text={v.question} phrases={v.falsePhrases} />
              </p>
            </div>
          )}
          {v.truth && (
            <div className="vz-truth">
              <div className="vz-card-k">What is actually true (answer key)</div>
              <p>{short(v.truth, mode === 'slide' ? 260 : 600)}</p>
            </div>
          )}
        </div>
        <div className="vz-honest-side">
          <div className={cx('vz-verdict', `t-${tone}`)}>
            <b>{v.verdict?.mark ?? '?'}</b>
            <span>{v.verdict?.label ?? 'Not graded yet'}</span>
          </div>
          {v.reply ? (
            <blockquote className="vz-quote">
              <span className="vz-card-k">Model said</span>“{short(v.reply, mode === 'slide' ? 200 : 260)}”
            </blockquote>
          ) : (
            <div className="vz-missing">The model’s reply was not recorded.</div>
          )}
          {v.judges.length > 0 ? (
            <ul className="vz-judges">
              {v.judges.map((j, i) => {
                const jv = verdictFor(j.label, v.kind);
                return (
                  <li key={i}>
                    <div className="vz-judge-h">
                      <strong>{judgeName?.(j.contestantId) ?? j.contestantId}</strong>
                      <span className={cx('vz-judge-l', jv && `t-${jv.tone}`)}>{j.label?.replace(/_/g, ' ').toLowerCase() ?? `${Math.round(j.score * 100)}/100`}</span>
                    </div>
                    <p>{short(j.rationale, mode === 'slide' ? 150 : 320)}</p>
                  </li>
                );
              })}
            </ul>
          ) : (
            <div className="vz-missing">Judge verdicts were not recorded.</div>
          )}
          {mode === 'inspector' && v.reply && (
            <details className="vz-working">
              <summary>Show the model’s full reply</summary>
              <pre>{v.reply}</pre>
            </details>
          )}
        </div>
      </div>
    </VizFrame>
  );
}
