/**
 * The finale card for a simulation replay: the verdict, the key number, a few
 * stats and plain-English notes on what went right or wrong. Shown on the
 * last replay step and on the Presenter's best-moment slide.
 */
import { cx } from '../ui.tsx';
import type { Finale } from './simStory.ts';
import { SimIcon } from './SimIcon.tsx';

export function SimFinaleCard({ finale, score, className }: { finale: Finale; score?: number | null; className?: string }) {
  return (
    <section className={cx('sim-finale', `tone-${finale.tone}`, className)} aria-label="Final result">
      <div className="sf-head">
        <span className="sf-kicker">Final result</span>
        <h4 className="sf-title">{finale.title}</h4>
        {finale.subtitle && <p className="sf-sub">{finale.subtitle}</p>}
      </div>
      <div className="sf-numbers">
        {finale.big && (
          <div className="sf-big">
            <b className="tnum">{finale.big.value}</b>
            <span>{finale.big.label}</span>
          </div>
        )}
        {typeof score === 'number' && (
          <div className="sf-big score">
            <b className="tnum">
              {Math.round(score * 100)}
              <small>/100</small>
            </b>
            <span>score</span>
          </div>
        )}
      </div>
      {finale.stats.length > 0 && (
        <dl className="sf-stats">
          {finale.stats.map((s) => (
            <div key={s.label} className={cx(s.tone && `tone-${s.tone}`)}>
              <dt>{s.label}</dt>
              <dd className="tnum">{s.value}</dd>
            </div>
          ))}
        </dl>
      )}
      {finale.notes.length > 0 && (
        <ul className="sf-notes">
          {finale.notes.map((n) => (
            <li key={n.text} className={`tone-${n.tone}`}>
              <SimIcon name={n.tone === 'good' ? 'check' : n.tone === 'bad' ? 'cross' : 'clue'} size="1.1em" />
              {n.text}
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}
