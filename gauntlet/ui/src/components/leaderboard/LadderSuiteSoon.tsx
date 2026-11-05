/**
 * A difficulty-ladder suite with no results yet: what each ladder will measure, which are built and which are
 * still coming, so the page explains itself instead of showing an empty table.
 */
import type { SuiteView } from '../../types.ts';
import { Link, pathOf } from '../../router.tsx';
import { Callout, cx } from '../ui.tsx';
import { Icon } from '../icons.tsx';
import { LADDERS, ladderSpecOf, ladderVerdictOf, passMarkText } from '../../ladders.ts';
import { calibrationClimbText, calibrationMonth, calibrationVerdict, levelsText } from '../../../../src/core/ladders.ts';
import { BlindTestMini } from '../viz/ClimbScene.tsx';
import '../viz/difficulty-ladder.css';

/**
 * The honest note on the Business difficulty ladders (Business results → Difficulty ladders, and the Business tests
 * page): what the blind test found, worked out from config/ladders.json so it stays true when the numbers change.
 */
export function BusinessLaddersNote() {
  const tested = LADDERS.tests.filter((t) => t.area === 'business' && t.calibration);
  if (!tested.length) return null;
  const cleared = tested.filter((t) => calibrationVerdict(t.calibration!) === 'top-clears').length;
  const slipped = tested.map((t) => t.calibration!.tiers.small?.failed ?? []).filter((f) => f.length > 0);
  const smallSlips = slipped.length;
  const slipAt = levelsText([...new Set(slipped.map((f) => Math.min(...f)))]).replace(/^L/, '').replace(/ L/g, ' ');
  const when = calibrationMonth(tested[0]!.calibration!.date);
  const all = cleared === tested.length;
  return (
    <Callout tone="info">
      <strong>
        Blind test, {when}: today’s top models clear {all ? 'these ladders' : `${cleared} of these ${tested.length} ladders`}.
      </strong>{' '}
      {all ? 'A mid-sized and a top model passed every level they were tested on, up to level 9, scoring 96–100%. ' : ''}
      So these ladders do not pick out the cleverest top model. They show which models stay error-free as the work piles up
      {smallSlips ? `, and where smaller, cheaper models start to slip (a small model already failed level ${slipAt} on ${smallSlips === 1 ? 'one' : smallSlips === 2 ? 'two' : smallSlips} of them)` : ''}. One model of each size, one attempt per level.
    </Callout>
  );
}

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

/** The ladders by name: what grows, the pass mark, whether each is ready or still coming, and the blind test as a small climb with its verdict. */
export function LadderList({ ids, planned }: { ids: string[]; planned?: ReadonlySet<string> }) {
  return (
    <ul className="lh-soon-list">
      {ids.map((id) => {
        const spec = ladderSpecOf(id);
        const soon = planned ? planned.has(id) : !!spec?.planned;
        const verdict = ladderVerdictOf(id);
        return (
          <li key={id} className={cx(soon && 'soon')}>
            {spec?.calibration ? (
              <span className="lh-mini-cal" title={calibrationClimbText(spec.calibration)}>
                <BlindTestMini calibration={spec.calibration} levels={spec.rungs?.length ?? 10} />
                <small>Blind test</small>
              </span>
            ) : (
              <span className="lh-mini" aria-hidden="true">
                {Array.from({ length: 10 }, (_, i) => (
                  <i key={i} />
                ))}
              </span>
            )}
            <div>
              <b>{soon ? spec?.name ?? id : <Link to={pathOf('tests', id)}>{spec?.name ?? id}</Link>}</b>
              {spec && <p>{spec.grows}</p>}
              <span className="lh-status">
                <span className={cx('lh-tag', !soon && 'ok')}>{soon ? 'Coming soon' : 'Ready'}</span>
                <small>{passMarkText(spec?.passMark ?? 1)}</small>
              </span>
              {spec?.calibration && <p className="lh-climbs">{calibrationClimbText(spec.calibration).replace(/^Blind test, [^:]+: (.)/, (_, c: string) => `Climbers: ${c}`)}.</p>}
              {verdict && <p className="lh-verdict">{verdict}</p>}
            </div>
          </li>
        );
      })}
    </ul>
  );
}
