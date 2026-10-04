/**
 * Best on each test — for every test, every model that has taken it (API and copy & paste together), the leader
 * highlighted; plus a timeline of one test's scores by release date ("how far AI has come since Claude 3 Opus").
 */
import { useMemo, useState } from 'react';
import { MOCK } from '../api.ts';
import { useAsync } from '../hooks.ts';
import { Link, setQuery, useRoute } from '../router.tsx';
import { useViewerCaption } from '../context.tsx';
import { Callout, CategoryChip, Empty, ErrorState, PageHead, Seg, SkeletonRows, Tabs, cx } from '../components/ui.tsx';
import { Icon } from '../components/icons.tsx';
import { fmtDate } from '../format.ts';
import { HistoryChart } from '../channel/HistoryChart.tsx';
import type { HistoryData, HistoryPoint } from '../channel/channelApi.ts';
import { releaseYears, timelineFor, viewBest, type BestData, type BestFilters, type BestModel, type RankedEntry } from '../../../src/manual-models/best-rank.ts';
import { mmApi } from './mmApi.ts';
import '../channel/channel.css';
import './manual-models.css';

const pct = (v: number | null) => (v === null ? '—' : (v * 100).toFixed(v >= 0.995 || v === 0 ? 0 : 1));
const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
/** Hand-copied models carry the app in their tag, so the name can drop the "(claude.ai)" suffix. */
const shortName = (m: BestModel) => (m.manualModel ? m.label.replace(/ \([^()]*\)$/, '') : m.label);
const monthYear = (d: string | null) => (d ? `${MONTHS[Number(d.slice(5, 7)) - 1]} ${d.slice(0, 4)}` : 'release date unknown');

function HowTag({ m }: { m: BestModel }) {
  if (!m.manual) return m.routed ? <span className="badge outline mm-how">API · via OpenRouter</span> : <span className="badge outline mm-how">API</span>;
  return (
    <span className={cx('badge mm-how', m.manualModel?.webSearch ? 'warn' : 'info')} title="Replies were copied into a chat app and pasted back by hand">
      {m.howLabel || 'copied by hand'}
    </span>
  );
}

function EntryRow({ e, m, max }: { e: RankedEntry; m: BestModel; max: number }) {
  return (
    <li className={cx('mm-entry', e.leader && 'lead')} style={{ ['--c' as string]: m.color }}>
      <span className="mm-rank tnum">{e.rank ?? '–'}</span>
      <span className="mm-entry-name">
        <span className="mm-entry-label ellipsis" title={m.label}>
          {shortName(m)}
        </span>
        <span className="mm-entry-meta">
          <HowTag m={m} />
          {e.reassigned > 0 && (
            <span className="badge outline" title="Some answers were moved to this model later with “Reassign to a model”">
              {e.reassigned} reassigned
            </span>
          )}
        </span>
      </span>
      <span className="mm-bar" aria-hidden="true">
        <i style={{ width: `${e.score === null ? 0 : Math.max(2, (e.score / max) * 100)}%` }} />
      </span>
      <span className="mm-score tnum">{e.score === null ? <span className="muted">no score</span> : pct(e.score)}</span>
      <span className="mm-n tnum muted" title={`${e.n} scored answer${e.n === 1 ? '' : 's'}${e.attempts !== e.n ? ` (${e.attempts} on record)` : ''} over ${e.runs} run${e.runs === 1 ? '' : 's'}`}>
        {e.n}×
      </span>
    </li>
  );
}

function TestCard({ t, data, limit }: { t: ReturnType<typeof viewBest>[number]; data: BestData; limit: number }) {
  const [all, setAll] = useState(false);
  const models = new Map(data.models.map((m) => [m.id, m]));
  const cat = data.categories.find((c) => c.id === t.category);
  const leaders = t.ranked.filter((e) => e.leader);
  const max = Math.max(1e-9, ...t.ranked.map((e) => e.score ?? 0));
  const shown = all ? t.ranked : t.ranked.slice(0, limit);
  const lead = leaders[0];
  const lm = lead ? models.get(lead.contestantId)! : null;
  return (
    <section className="card mm-test">
      <div className="mm-test-head">
        <div className="mm-test-title">
          <h3>{t.name}</h3>
          {cat && <CategoryChip name={cat.name} color={cat.color} />}
        </div>
        <span className="muted mm-test-count">
          {t.ranked.length} model{t.ranked.length === 1 ? '' : 's'}
        </span>
      </div>
      {lead && lm ? (
        <div className="mm-leader" style={{ ['--c' as string]: lm.color }}>
          <Icon.Trophy className="mm-leader-icon" />
          <div className="mm-leader-main">
            <div className="mm-eyebrow">{leaders.length > 1 ? `Joint leaders (${leaders.length})` : 'Leader'}</div>
            <div className="mm-leader-name">
              {leaders
                .slice(0, 2)
                .map((e) => shortName(models.get(e.contestantId)!))
                .join(' = ')}
              {leaders.length > 2 && <span className="muted"> + {leaders.length - 2} more</span>}
            </div>
            <div className="mm-leader-meta">
              <HowTag m={lm} />
              <span>
                {lead.n} answer{lead.n === 1 ? '' : 's'} · tested {lead.lastTestedAt ? fmtDate(lead.lastTestedAt) : '—'}
              </span>
            </div>
          </div>
          <div className="mm-leader-score tnum">
            {pct(lead.score)}
            <small>/100</small>
          </div>
        </div>
      ) : (
        <div className="mm-noleader muted">No model has scored above zero yet.</div>
      )}
      <ol className="mm-entries">
        {shown.map((e) => (
          <EntryRow key={e.contestantId} e={e} m={models.get(e.contestantId)!} max={max} />
        ))}
      </ol>
      {t.ranked.length > limit && (
        <button type="button" className="btn xs ghost mm-more" onClick={() => setAll((a) => !a)}>
          {all ? 'Show fewer' : `Show all ${t.ranked.length}`}
        </button>
      )}
    </section>
  );
}

/** HistoryData for the shared release-date chart: one line per family, score out of 100 as the "index". */
function timelineData(points: ReturnType<typeof timelineFor>): HistoryData {
  const fams = new Map<string, HistoryPoint[]>();
  for (const { model, entry } of points) {
    const family = model.family ?? model.vendor;
    const p: HistoryPoint = { contestantId: model.id, label: model.label.replace(/ \((.*)\)$/, ''), vendor: model.vendor, color: model.color, family, tier: null, releaseDate: model.releaseDate, index: (entry.score ?? 0) * 100, indexCi95: entry.ci95 ? [entry.ci95[0] * 100, entry.ci95[1] * 100] : null, categoryScores: {}, coverage: 1 };
    fams.set(family, [...(fams.get(family) ?? []), p]);
  }
  const families = [...fams].map(([family, pts]) => {
    // The line joins the best score per release date.
    const best = new Map<string, HistoryPoint>();
    for (const p of pts) if (!best.has(p.releaseDate!) || best.get(p.releaseDate!)!.index! < p.index!) best.set(p.releaseDate!, p);
    return { family, color: pts[0]!.color, points: pts, line: [...best.values()].sort((a, b) => a.releaseDate!.localeCompare(b.releaseDate!)) };
  });
  return { suiteId: '', metric: 'index', families, undated: [], noResults: [], jumps: [], generatedAt: '' };
}

function Timeline({ data, filters, testId, onTest, fromId, onFrom }: { data: BestData; filters: BestFilters; testId: string; onTest: (id: string) => void; fromId: string; onFrom: (id: string) => void }) {
  const tests = viewBest(data, { ...filters, category: '' });
  const counts = new Map(tests.map((t) => [t.id, timelineFor(data, t.id, filters).length]));
  const chosen = tests.some((t) => t.id === testId) ? testId : [...tests].sort((a, b) => (counts.get(b.id) ?? 0) - (counts.get(a.id) ?? 0))[0]?.id ?? '';
  const points = timelineFor(data, chosen, filters);
  const hist = useMemo(() => timelineData(points), [points]);
  // "Since …": the owner's pick, else Claude 3 Opus when it took the test, else the oldest model.
  const first = points.find((p) => p.model.id === fromId) ?? points.find((p) => (p.model.manualModel?.catalogId ?? p.model.id) === 'claude-3-opus') ?? points[0];
  const best = points.reduce<(typeof points)[number] | null>((b, p) => (!b || (p.entry.score ?? 0) > (b.entry.score ?? 0) ? p : b), null);
  const testName = tests.find((t) => t.id === chosen)?.name ?? '';
  return (
    <section className="card mm-timeline">
      <div className="card-head">
        <div className="t">
          <h2>{testName || 'Timeline'}</h2>
          <div className="desc">Every model that took this test, placed at its release date. Higher is better.</div>
        </div>
        <div className="tools no-broadcast">
          <select className="select" value={chosen} onChange={(e) => onTest(e.target.value)} aria-label="Test to show">
            {tests.map((t) => (
              <option key={t.id} value={t.id}>
                {t.name} ({counts.get(t.id) ?? 0} dated models)
              </option>
            ))}
          </select>
        </div>
      </div>
      <div className="card-body">
        {points.length === 0 ? (
          <Empty icon={<Icon.Chart />} title="No dated models on this test yet">
            Copy & paste models from the catalogue come with their release date. For API models, add a release date on the History page.
          </Empty>
        ) : (
          <>
            {first && best && best !== first && (
              <div className="mm-since">
                <span>
                  Since{' '}
                  <select className="select sm mm-since-pick no-broadcast" value={first.model.id} onChange={(e) => onFrom(e.target.value)} aria-label="Compare from">
                    {points.map((p) => (
                      <option key={p.model.id} value={p.model.id}>
                        {p.model.label}
                      </option>
                    ))}
                  </select>
                  <b className="only-broadcast">{shortName(first.model)}</b> ({monthYear(first.model.releaseDate)}): <b className="tnum">{pct(first.entry.score)}</b>
                </span>
                <Icon.ChevronRight />
                <span>
                  best now <b>{shortName(best.model)}</b> ({monthYear(best.model.releaseDate)}): <b className="tnum">{pct(best.entry.score)}</b>
                </span>
                <span className="mm-since-delta tnum">{((best.entry.score ?? 0) - (first.entry.score ?? 0)) * 100 >= 0 ? '+' : ''}{(((best.entry.score ?? 0) - (first.entry.score ?? 0)) * 100).toFixed(0)} points</span>
              </div>
            )}
            <HistoryChart data={hist} metricLabel={`${testName} score`} />
            <ol className="mm-strip">
              {points.map(({ model, entry }) => (
                <li key={model.id} style={{ ['--c' as string]: model.color }} className={cx(entry.leader && 'lead')}>
                  <span className="mm-strip-date">{monthYear(model.releaseDate)}</span>
                  <span className="mm-strip-name" title={model.label}>
                    {shortName(model)}
                  </span>
                  <span className="mm-strip-score tnum">{pct(entry.score)}</span>
                  <HowTag m={model} />
                </li>
              ))}
            </ol>
          </>
        )}
      </div>
    </section>
  );
}

export default function BestPerTestPage() {
  const { query } = useRoute();
  const state = useAsync(() => mmApi.best(), []);
  const view = query.get('view') === 'timeline' ? 'timeline' : 'rankings';
  const filters: BestFilters = {
    vendor: query.get('vendor') ?? '',
    year: query.get('year') ?? '',
    kind: (['api', 'manual'].includes(query.get('kind') ?? '') ? query.get('kind') : 'all') as BestFilters['kind'],
    category: query.get('category') ?? '',
  };
  const data = state.data;
  const shown = useMemo(() => (data ? viewBest(data, filters) : []), [data, filters.vendor, filters.year, filters.kind, filters.category]);
  const hasManual = Boolean(data?.models.some((m) => m.manual));
  const webModels = data?.models.filter((m) => m.manualModel?.webSearch) ?? [];
  useViewerCaption(
    data && shown.length
      ? view === 'timeline'
        ? 'One test, every model that has taken it, placed at its release date: how far AI has come.'
        : 'For every test: which model does best. The leader is highlighted; models tested by copying prompts into a chat app are labelled “copied by hand”.'
      : 'Best on each test: which AI model scores highest on every test.',
    'Score out of 100 · × = answers behind the score',
  );
  const vendors = data ? [...new Set(data.models.map((m) => m.vendor))].sort() : [];
  const years = data ? releaseYears(data) : [];

  return (
    <div className="page mm-best-page">
      <PageHead
        eyebrow={
          <span className="row" style={{ gap: 8 }}>
            <Icon.Trophy style={{ width: 14, height: 14 }} /> Compare
          </span>
        }
        title="Best on each test"
        sub="Every model that has taken each test, API and copy & paste together, with the leader highlighted."
        actions={
          <Tabs
            value={view}
            onChange={(v) => setQuery({ view: v === 'rankings' ? null : v })}
            tabs={[
              { id: 'rankings', label: 'Rankings' },
              { id: 'timeline', label: 'Timeline' },
            ]}
          />
        }
      />

      {MOCK && (
        <Callout tone="info" icon={<Icon.Info />}>
          <b>Demo data:</b> the scores on this page are made up to show the layout. Real results appear once you run tests.
        </Callout>
      )}

      <section className="card no-broadcast mm-filters">
        <div className="card-body row wrap" style={{ gap: 14, alignItems: 'flex-end' }}>
          <label className="field">
            <span className="label">Company</span>
            <select className="select" value={filters.vendor} onChange={(e) => setQuery({ vendor: e.target.value || null })}>
              <option value="">All companies</option>
              {vendors.map((v) => (
                <option key={v}>{v}</option>
              ))}
            </select>
          </label>
          <label className="field">
            <span className="label">Released in</span>
            <select className="select" value={filters.year} onChange={(e) => setQuery({ year: e.target.value || null })}>
              <option value="">Any year</option>
              {years.map((y) => (
                <option key={y}>{y}</option>
              ))}
            </select>
          </label>
          <div className="field">
            <span className="label">How it was tested</span>
            <Seg
              label="How it was tested"
              value={filters.kind}
              onChange={(v) => setQuery({ kind: v === 'all' ? null : v })}
              options={[
                { value: 'all', label: 'All' },
                { value: 'api', label: 'API' },
                { value: 'manual', label: 'Copied by hand' },
              ]}
            />
          </div>
          {view === 'rankings' && (
            <label className="field">
              <span className="label">Test category</span>
              <select className="select" value={filters.category} onChange={(e) => setQuery({ category: e.target.value || null })}>
                <option value="">All categories</option>
                {(data?.categories ?? []).map((c) => (
                  <option key={c.id} value={c.id}>
                    {c.name}
                  </option>
                ))}
              </select>
            </label>
          )}
          <span className="spacer" />
          {(filters.vendor || filters.year || filters.kind !== 'all' || filters.category) && (
            <button type="button" className="btn sm ghost" onClick={() => setQuery({ vendor: null, year: null, kind: null, category: null })}>
              Clear filters
            </button>
          )}
        </div>
      </section>

      {hasManual && (
        <Callout tone="warn" icon={<Icon.Alert />}>
          <b>About “copied by hand” results:</b> chat apps can add their own hidden instructions, use different settings from the API, and quietly update a model over time, so a hand-copied score is not a perfect match for the same model through the API. Treat small gaps as a draw.
          {webModels.length > 0 && <> Results marked <b>web search on</b> could look things up, which API models can’t.</>}
        </Callout>
      )}

      {state.error && !data ? (
        <ErrorState error={state.error} onRetry={state.reload} title="Couldn’t load the results" />
      ) : !data ? (
        <div className="card pad">
          <SkeletonRows rows={6} h={48} />
        </div>
      ) : shown.length === 0 ? (
        <div className="card">
          <Empty
            icon={<Icon.Trophy />}
            title={data.tests.length ? 'Nothing matches these filters' : 'No results yet'}
            actions={
              !data.tests.length && (
                <Link to="/run/new" className="btn primary">
                  <Icon.Rocket /> Run a test
                </Link>
              )
            }
          >
            {data.tests.length ? 'Try clearing a filter.' : 'Run some tests (API models, or copy & paste from New Run) and every test’s leader will appear here.'}
          </Empty>
        </div>
      ) : view === 'timeline' ? (
        <Timeline data={data} filters={filters} testId={query.get('test') ?? ''} onTest={(id) => setQuery({ test: id })} fromId={query.get('from') ?? ''} onFrom={(id) => setQuery({ from: id })} />
      ) : (
        <div className="mm-grid">
          {shown.map((t) => (
            <TestCard key={t.id} t={t} data={data} limit={8} />
          ))}
        </div>
      )}
      {data && data.staleExcluded > 0 && (
        <p className="muted mm-small no-broadcast">
          {data.staleExcluded} older result{data.staleExcluded === 1 ? '' : 's'} left out because the test or the model’s settings have changed since (same rule as the leaderboard).
        </p>
      )}
    </div>
  );
}
