/**
 * Head to Head — #/versus?a=<model>&b=<model>[&run=<runId>]
 *
 * Two models compared round by round on results already recorded (no model
 * calls): a fighting-game face-off with the tale of the tape, one card per test
 * with facing score bars and the decisive moment, then the final scoreboard and
 * a vertical Shorts card. Data: GET /api/versus (src/versus/build.ts).
 */
import { useEffect, useMemo } from 'react';
import { useAsync } from '../hooks.ts';
import { Link, href, setQuery, useRoute } from '../router.tsx';
import { useViewerCaption } from '../context.tsx';
import { Card, Empty, ErrorState, LoadingPage, PageHead, cx } from '../components/ui.tsx';
import { Icon } from '../components/icons.tsx';
import { nameOf } from '../../../src/versus/words.ts';
import { versusApi, type VersusData, type VersusOptions } from './client.ts';
import { FaceOff, RoundCard, RoundStrip, Scoreboard, TaleOfTape, useCorners } from './parts.tsx';
import { VersusCardPanel } from './VersusCardPanel.tsx';
import './versus.css';

function Picker({ opts, a, b, run }: { opts: VersusOptions; a: string; b: string; run: string }) {
  const list = opts.fighters;
  const option = (f: VersusOptions['fighters'][number]) => (
    <option key={f.id} value={f.id}>
      {nameOf(f)} · {f.tests} {f.tests === 1 ? 'test' : 'tests'}
    </option>
  );
  return (
    <div className="vx-picker no-broadcast" role="group" aria-label="Choose the two models">
      <label className="vx-pick">
        <span>Left corner</span>
        <select className="select" value={a} onChange={(e) => setQuery({ a: e.target.value })}>
          {!list.some((f) => f.id === a) && <option value={a}>{a || 'Choose a model'}</option>}
          {list.map(option)}
        </select>
      </label>
      <button type="button" className="btn icon vx-swap" onClick={() => setQuery({ a: b, b: a })} aria-label="Swap sides" title="Swap sides">
        <Icon.Shuffle />
      </button>
      <label className="vx-pick">
        <span>Right corner</span>
        <select className="select" value={b} onChange={(e) => setQuery({ b: e.target.value })}>
          {!list.some((f) => f.id === b) && <option value={b}>{b || 'Choose a model'}</option>}
          {list.map(option)}
        </select>
      </label>
      <label className="vx-pick wide">
        <span>Results from</span>
        <select className="select" value={run} onChange={(e) => setQuery({ run: e.target.value || null })}>
          <option value="">Every run (latest valid results)</option>
          {opts.runs.map((r) => (
            <option key={r.id} value={r.id}>
              {r.name}
            </option>
          ))}
        </select>
      </label>
    </div>
  );
}

function Match({ d, run }: { d: VersusData; run: string }) {
  const colors = useCorners(d);
  const deck = href('/present/versus', { a: d.a.id, b: d.b.id, run: run || undefined });
  return (
    <>
      <section className="vx-hero" aria-label="The two models">
        <FaceOff d={d} colors={colors} />
        <TaleOfTape d={d} colors={colors} />
        <div className="vx-hero-foot no-broadcast">
          <a className="btn primary" href={deck}>
            <Icon.Present /> Present as slides
          </a>
          <button type="button" className="btn" onClick={() => document.getElementById('vx-card')?.scrollIntoView({ behavior: 'smooth', block: 'start' })}>
            <Icon.Image /> Shorts card
          </button>
        </div>
      </section>

      {d.rounds.length === 0 ? (
        <Empty icon={<Icon.Target />} title="No test in common yet">
          {nameOf(d.a)} and {nameOf(d.b)} have no test they both finished{d.scope.kind === 'run' ? ' in this run' : ''}. Run the same tests on both models, then come back.
        </Empty>
      ) : (
        <>
          <div className="vx-section-h">
            <h2>Round by round</h2>
            <p>
              Each test is one round. Scores are out of 100 and only count questions both models answered. Less than {d.tieMargin} points apart is a draw.
            </p>
            <RoundStrip d={d} colors={colors} />
          </div>
          <div className="vx-rounds">
            {d.rounds.map((r, i) => (
              <RoundCard key={r.testId} round={r} index={i} d={d} colors={colors} />
            ))}
          </div>

          <div className="vx-section-h">
            <h2>The result</h2>
          </div>
          <Scoreboard d={d} colors={colors} />
        </>
      )}

      {d.skipped.length > 0 && (
        <p className="vx-skipped">
          <Icon.Info /> Not compared, because only one model has results:{' '}
          {d.skipped.map((s, i) => (
            <span key={s.testId}>
              {i > 0 && ', '}
              <b>{s.testName}</b> ({s.reason === 'only-a' ? `only ${nameOf(d.a)}` : s.reason === 'only-b' ? `only ${nameOf(d.b)}` : 'different questions'})
            </span>
          ))}
          .
        </p>
      )}

      <Card id="vx-card" className="no-broadcast" title="Shorts card" desc="Vertical result card, ready to post.">
        <VersusCardPanel data={d} runId={run || undefined} />
      </Card>
    </>
  );
}

export default function VersusPage() {
  const { query } = useRoute();
  const a = query.get('a') ?? '';
  const b = query.get('b') ?? '';
  const run = query.get('run') ?? '';
  const opts = useAsync(() => versusApi.options(run || undefined), [run]);
  const match = useAsync(() => (a && b && a !== b ? versusApi.get(a, b, run || undefined) : Promise.resolve(null)), [a, b, run]);

  // No pair chosen yet: start with the suggested one (the two best models that share tests).
  useEffect(() => {
    const s = opts.data?.suggested;
    if (s && (!a || !b)) setQuery({ a: a || (s[0] === b ? s[1] : s[0]), b: b || (s[1] === a ? s[0] : s[1]) });
  }, [opts.data, a, b]);

  const d = match.data ?? null;
  useViewerCaption(
    d
      ? d.rounds.length
        ? `Head to head: ${nameOf(d.a)} against ${nameOf(d.b)} on ${d.rounds.length} ${d.rounds.length === 1 ? 'test' : 'tests'}, one round per test. The final score is at the bottom.`
        : `Head to head: ${nameOf(d.a)} against ${nameOf(d.b)}. They have no test in common yet.`
      : null,
    d ? `Only questions both models answered are compared · a draw is less than ${d.tieMargin} points apart` : undefined,
  );
  const title = useMemo(() => (d ? `${nameOf(d.a)} vs ${nameOf(d.b)}` : 'Head to Head'), [d]);

  if (opts.loading && !opts.data) return <LoadingPage />;
  if (opts.error && !opts.data) return <ErrorState error={opts.error} onRetry={opts.reload} title="Couldn’t load the models" />;
  const o = opts.data!;

  return (
    <div className="page vx-page">
      <PageHead
        eyebrow={
          <span className="row" style={{ gap: 8 }}>
            <Icon.Target style={{ width: 14, height: 14 }} /> Head to Head
            {d?.scope.kind === 'run' && <span className="muted"> · {d.scope.runName}</span>}
          </span>
        }
        title={<span className={cx('vx-title', !d && 'muted')}>{title}</span>}
        sub="Pick any two models and compare them test by test on the results you have already recorded. Nothing new is run and nothing costs money."
      />
      {o.fighters.length < 2 ? (
        <div className="card">
          <Empty
            icon={<Icon.Target />}
            title="Run a test with two models first"
            actions={
              <Link to="/run/new?suite=quick-check&models=cheap" className="btn primary">
                <Icon.Rocket /> Run a test
              </Link>
            }
          >
            A head to head compares two models on questions they have both answered. The 2p Quick Check runs the two cheapest models, which is enough to try it.
          </Empty>
        </div>
      ) : (
        <>
          <Picker opts={o} a={a} b={b} run={run} />
          {a && b && a === b && <p className="vx-skipped">Pick two different models.</p>}
          {match.error ? <ErrorState error={match.error} onRetry={match.reload} title="Couldn’t compare these models" /> : d ? <Match d={d} run={run} /> : a && b && a !== b ? <LoadingPage /> : null}
        </>
      )}
    </div>
  );
}
