/** Episode presenter — pick a run to turn into a full-screen slide deck. */
import { useMemo } from 'react';
import { api } from '../api.ts';
import { useAsync } from '../hooks.ts';
import { Link, pathOf } from '../router.tsx';
import { Empty, ErrorState, ModelChip, PageHead, RunStatusBadge, SkeletonRows } from '../components/ui.tsx';
import { Icon } from '../components/icons.tsx';
import { fmtCost, fmtDate, fmtInt, pluralize } from '../format.ts';
import type { RunListItem } from '../types.ts';

const SLIDES: Array<[string, string]> = [
  ['Title card', 'Run name, date, line-up of contenders and the fingerprint'],
  ['How scoring works', '0–100 scores, the Gauntlet Index, what the thin uncertainty lines mean'],
  ['Every test, twice', 'An explainer (the challenge, how it’s scored, an example) then an animated result race'],
  ['Final standings', 'Revealed last to first, one row per key press — or automatically with A'],
  ['Score vs cost · Medal table · Credits', 'Value for money, test-by-test medals, and the methodology fingerprint'],
];

export default function PresentPickerPage() {
  const runs = useAsync<RunListItem[]>(() => api.runs(), []);
  const list = useMemo(() => (runs.data ?? []).filter((r) => r.completedJobs > 0).sort((a, b) => b.createdAt.localeCompare(a.createdAt)), [runs.data]);

  return (
    <div className="page">
      <PageHead
        eyebrow="Episode presenter"
        title="Present a run"
        sub="Turn any run into a full-screen 16:9 slideshow for recording: every slide explains itself in plain English, and the whole deck is driven from the keyboard."
      />
      <div className="present-pick">
        <section className="card">
          <div className="card-head">
            <div className="t">
              <h2>Choose a run</h2>
              <div className="desc">Runs with at least one finished result. The deck works for partial runs too — missing tests say so.</div>
            </div>
          </div>
          {runs.loading && !runs.data ? (
            <SkeletonRows rows={5} h={64} />
          ) : runs.error && !runs.data ? (
            <ErrorState error={runs.error} onRetry={runs.reload} title="Couldn’t load runs" />
          ) : list.length === 0 ? (
            <Empty
              icon={<Icon.Present />}
              title="Nothing to present yet"
              actions={
                <Link to="/run/new" className="btn primary">
                  <Icon.Rocket /> Start a run
                </Link>
              }
            >
              Finish a run first — the presenter builds its slides from the results.
            </Empty>
          ) : (
            <ul className="pick-list">
              {list.map((r) => (
                <li key={r.id}>
                  <Link to={pathOf('present', r.id)} className="pick-item" aria-label={`Present ${r.name}`}>
                    <div className="pick-main">
                      <div className="row wrap" style={{ gap: 10 }}>
                        <strong className="pick-name">{r.name || r.id}</strong>
                        <RunStatusBadge status={r.status} />
                      </div>
                      <div className="muted pick-meta tnum">
                        {fmtDate(r.createdAt)} · {r.contestants.length} {pluralize(r.contestants.length, 'model')} · {r.testCount} {pluralize(r.testCount, 'test')} · {fmtInt(r.completedJobs)}/{fmtInt(r.totalJobs)} jobs · {fmtCost(r.costUsd)}
                      </div>
                      <div className="chip-list">
                        {r.contestants.slice(0, 8).map((c) => (
                          <ModelChip key={c.id} label={c.label} color={c.color} pill />
                        ))}
                      </div>
                    </div>
                    <span className="btn primary pick-go">
                      <Icon.Present /> Present
                    </span>
                  </Link>
                </li>
              ))}
            </ul>
          )}
        </section>
        <aside className="stack">
          <section className="card">
            <div className="card-head">
              <div className="t">
                <h2>What’s in the deck</h2>
              </div>
            </div>
            <ol className="deck-outline">
              {SLIDES.map(([t, d]) => (
                <li key={t}>
                  <strong>{t}</strong>
                  <span className="muted">{d}</span>
                </li>
              ))}
            </ol>
          </section>
          <section className="card">
            <div className="card-head">
              <div className="t">
                <h2>Keys</h2>
              </div>
            </div>
            <dl className="kv keys-kv">
              <dt>
                <kbd>→</kbd> <kbd>Space</kbd>
              </dt>
              <dd>Next slide / reveal the next row</dd>
              <dt>
                <kbd>←</kbd>
              </dt>
              <dd>Back</dd>
              <dt>
                <kbd>Home</kbd> <kbd>End</kbd>
              </dt>
              <dd>First / last slide</dd>
              <dt>
                <kbd>A</kbd>
              </dt>
              <dd>Auto: reveal every 1.2 s, advance every 9 s</dd>
              <dt>
                <kbd>F</kbd>
              </dt>
              <dd>Full screen</dd>
              <dt>
                <kbd>Esc</kbd>
              </dt>
              <dd>Leave the presenter</dd>
            </dl>
          </section>
        </aside>
      </div>
    </div>
  );
}
