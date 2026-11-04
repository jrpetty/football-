/**
 * A difficulty-ladder suite with no results yet: what each ladder will measure, which are built and which are
 * still coming, so the page explains itself instead of showing an empty table.
 */
import type { SuiteView } from '../../types.ts';
import { Link, pathOf } from '../../router.tsx';
import { cx } from '../ui.tsx';
import { Icon } from '../icons.tsx';
import { ladderSpecOf, passMarkText } from '../../ladders.ts';
import '../viz/difficulty-ladder.css';

export function LadderSuiteSoon({ suite }: { suite: SuiteView }) {
  const entries = suite.tests.filter((t) => t.id !== '*');
  const built = entries.filter((t) => !t.planned).length;
  return (
    <section className="card lh-soon-card" aria-label={`${suite.name}: the ladders`}>
      <div className="card-head">
        <div className="t">
          <span className="dl-eyebrow">Difficulty ladders</span>
          <h2>{built ? 'No results yet' : 'Coming soon'}</h2>
          <div className="desc">
            {built
              ? `Run the ${suite.name} suite to see how high each model climbs.`
              : `The ${entries.length} ladders of ${suite.name} are being built. Each has ten frozen levels, each harder than the last; the height is the highest level a model passes reliably with every level below it.`}
          </div>
        </div>
        {built > 0 && (
          <div className="tools">
            <Link to={`/run/new?suite=${encodeURIComponent(suite.id)}`} className="btn primary">
              <Icon.Rocket /> Run {suite.name}
            </Link>
          </div>
        )}
      </div>
      <div className="card-body">
        <LadderList ids={entries.map((e) => e.id)} planned={new Set(entries.filter((e) => e.planned).map((e) => e.id))} />
      </div>
    </section>
  );
}

/** The ladders by name: what grows, the pass mark, and whether each is ready or still coming. */
export function LadderList({ ids, planned }: { ids: string[]; planned?: ReadonlySet<string> }) {
  return (
    <ul className="lh-soon-list">
      {ids.map((id) => {
        const spec = ladderSpecOf(id);
        const soon = planned ? planned.has(id) : !!spec?.planned;
        return (
          <li key={id} className={cx(soon && 'soon')}>
            <span className="lh-mini" aria-hidden="true">
              {Array.from({ length: 10 }, (_, i) => (
                <i key={i} />
              ))}
            </span>
            <div>
              <b>{soon ? spec?.name ?? id : <Link to={pathOf('tests', id)}>{spec?.name ?? id}</Link>}</b>
              {spec && <p>{spec.grows}</p>}
              <span className="lh-status">
                <span className={cx('lh-tag', !soon && 'ok')}>{soon ? 'Coming soon' : 'Ready'}</span>
                <small>{passMarkText(spec?.passMark ?? 1)}</small>
              </span>
            </div>
          </li>
        );
      })}
    </ul>
  );
}
