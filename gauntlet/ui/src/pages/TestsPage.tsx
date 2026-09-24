import { useMemo } from 'react';
import { api } from '../api.ts';
import { useAsync } from '../hooks.ts';
import { Link, pathOf, setQuery, useRoute } from '../router.tsx';
import { useMeta } from '../context.tsx';
import { DifficultyBadge, Empty, ErrorState, PageHead, Seg, SkeletonRows, cx } from '../components/ui.tsx';
import { Icon } from '../components/icons.tsx';
import { fmtInt, shortHash } from '../format.ts';
import type { TestSummary } from '../types.ts';

export function SourceBadge({ source, compact }: { source: TestSummary['source'] | undefined; compact?: boolean }) {
  if (source === 'custom') return <span className="badge info">custom</span>;
  if (source === 'private')
    return (
      <span className="badge accent" title="Held-out · never published — lives in tests/private/ and is used to detect contamination">
        <Icon.Lock /> {compact ? 'Held-out' : 'Held-out · never published'}
      </span>
    );
  return null;
}

export function TestCard({ t }: { t: TestSummary }) {
  const { cat } = useMeta();
  const c = cat(t.category);
  return (
    <Link to={pathOf('tests', t.id)} className="test-card" style={{ ['--cat' as string]: c.color }}>
      <div className="tc-top">
        <DifficultyBadge difficulty={t.difficulty} />
        <span className="spacer" />
        <SourceBadge source={t.source} compact />
        <span className="badge outline">{t.kind === 'program' ? 'simulation' : 'prompt'}</span>
      </div>
      <h3>{t.name}</h3>
      <div className="mono muted tc-id">{t.id}</div>
      {t.hook ? <p className="tc-hook">“{t.hook}”</p> : <p className="tc-desc">{t.description}</p>}
      <div className="tc-meta">
        <span title="Cases">
          <b>{fmtInt(t.caseCount)}</b> {t.kind === 'program' ? 'seeds' : 'cases'}
        </span>
        <span title="Scorer">{t.scorerType.replace(/^program:/, '')}</span>
        <span title="Version">v{t.version}</span>
        <span className="hash" title={`Test hash ${t.hash}`}>
          {shortHash(t.hash)}
        </span>
      </div>
    </Link>
  );
}

export default function TestsPage() {
  const { query } = useRoute();
  const { cat, categories } = useMeta();
  const tests = useAsync<TestSummary[]>(() => api.tests(), []);
  const q = query.get('q') ?? '';
  const catF = query.get('cat') ?? '';
  const kind = (query.get('kind') ?? 'all') as 'all' | 'prompt' | 'program';
  const src = query.get('src') ?? '';
  const diff = query.get('diff') ?? '';

  const filtered = useMemo(() => {
    const needle = q.trim().toLowerCase();
    return (tests.data ?? []).filter(
      (t) =>
        (!needle || `${t.name} ${t.id} ${t.description} ${t.tags.join(' ')} ${t.hook ?? ''}`.toLowerCase().includes(needle)) &&
        (!catF || t.category === catF) &&
        (kind === 'all' || t.kind === kind) &&
        (!src || t.source === src) &&
        (!diff || t.difficulty === diff),
    );
  }, [tests.data, q, catF, kind, src, diff]);

  const groups = useMemo(() => {
    const order = new Map(categories.map((c, i) => [c.id, i]));
    const m = new Map<string, TestSummary[]>();
    for (const t of filtered) m.set(t.category, [...(m.get(t.category) ?? []), t]);
    return [...m.entries()].sort((a, b) => (order.get(a[0]) ?? 99) - (order.get(b[0]) ?? 99));
  }, [filtered, categories]);

  const all = tests.data ?? [];
  const usedCats = [...new Set(all.map((t) => t.category))];

  return (
    <div className="page">
      <PageHead
        eyebrow="Lab"
        title="Test Library"
        sub="Every test is versioned and hashed. Change a prompt, answer or scorer and the hash changes — old results stop counting, by design."
        actions={
          <>
            <Link to="/grade" className="btn">
              <Icon.Target /> Grade a reply
            </Link>
            <Link to="/tests/new" className="btn primary">
              <Icon.Plus /> New test
            </Link>
          </>
        }
      />

      {all.length > 0 && (
        <div className="stats">
          <div className="stat">
            <span className="k">Tests</span>
            <span className="v">{fmtInt(all.length)}</span>
            <span className="s">{all.filter((t) => t.kind === 'program').length} simulations</span>
          </div>
          <div className="stat">
            <span className="k">Cases</span>
            <span className="v">{fmtInt(all.reduce((s, t) => s + t.caseCount, 0))}</span>
            <span className="s">prompts &amp; seeds</span>
          </div>
          <div className="stat">
            <span className="k">Categories</span>
            <span className="v">{usedCats.length}</span>
            <span className="s">{all.filter((t) => t.source === 'custom').length} custom tests</span>
          </div>
          <div className="stat">
            <span className="k">Held-out</span>
            <span className="v">{all.filter((t) => t.source === 'private').length}</span>
            <span className="s">never published</span>
          </div>
        </div>
      )}

      <div className="filter-bar">
        <div className="search" style={{ flex: '1 1 260px' }}>
          <Icon.Search />
          <input className="input" placeholder="Search name, id, tag, hook…" value={q} onChange={(e) => setQuery({ q: e.target.value })} aria-label="Search tests" />
        </div>
        <Seg
          label="Kind"
          value={kind}
          onChange={(v) => setQuery({ kind: v === 'all' ? null : v })}
          options={[
            { value: 'all', label: 'All' },
            { value: 'prompt', label: 'Prompt' },
            { value: 'program', label: 'Simulation' },
          ]}
        />
        <select className="select" style={{ width: 150 }} value={diff} onChange={(e) => setQuery({ diff: e.target.value || null })} aria-label="Difficulty">
          <option value="">Any difficulty</option>
          {['easy', 'medium', 'hard', 'extreme'].map((d) => (
            <option key={d} value={d}>
              {d}
            </option>
          ))}
        </select>
        <select className="select" style={{ width: 150 }} value={src} onChange={(e) => setQuery({ src: e.target.value || null })} aria-label="Source">
          <option value="">Any source</option>
          <option value="builtin">Built-in</option>
          <option value="custom">Custom</option>
          <option value="private">Held-out</option>
        </select>
      </div>
      <div className="chip-list" role="group" aria-label="Category">
        <button type="button" className={cx('toggle-chip', !catF && 'on')} aria-pressed={!catF} onClick={() => setQuery({ cat: null })}>
          All categories
        </button>
        {categories
          .filter((c) => usedCats.includes(c.id))
          .map((c) => (
            <button key={c.id} type="button" className={cx('toggle-chip', catF === c.id && 'on')} aria-pressed={catF === c.id} onClick={() => setQuery({ cat: catF === c.id ? null : c.id })}>
              <span className="sw" style={{ background: c.color, opacity: 1 }} />
              {c.name}
            </button>
          ))}
      </div>

      {tests.loading && !tests.data ? (
        <div className="card pad">
          <SkeletonRows rows={6} h={90} />
        </div>
      ) : tests.error && !tests.data ? (
        <ErrorState error={tests.error} onRetry={tests.reload} title="Couldn’t load tests" />
      ) : groups.length === 0 ? (
        <div className="card">
          <Empty
            icon={<Icon.Flask />}
            title={all.length ? 'No tests match' : 'No tests yet'}
            actions={
              <Link to="/tests/new" className="btn primary">
                <Icon.Plus /> Build a test
              </Link>
            }
          >
            {all.length ? 'Try clearing a filter.' : 'Author your first test in the builder.'}
          </Empty>
        </div>
      ) : (
        <div className="test-groups">
        {groups.map(([catId, ts]) => {
          const info = cat(catId);
          return (
            <section key={catId} className="test-group" style={{ ['--cat' as string]: info.color }}>
              <header>
                <h2>
                  <span className="cat-bar" />
                  {info.name}
                  <span className="muted" style={{ fontWeight: 500, fontSize: '0.9rem' }}>
                    {ts.length}
                  </span>
                </h2>
                {info.description && <p>{info.description}</p>}
              </header>
              <div className="test-grid">
                {ts.map((t) => (
                  <TestCard key={t.id} t={t} />
                ))}
              </div>
            </section>
          );
        })}
        </div>
      )}
    </div>
  );
}
