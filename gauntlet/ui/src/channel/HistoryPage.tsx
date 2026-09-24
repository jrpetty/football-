/** Model history — Gauntlet Index (or a category) against release date, one line per family. */
import { useMemo, useState } from 'react';
import { api } from '../api.ts';
import { useAsync } from '../hooks.ts';
import { Link, setQuery, useRoute } from '../router.tsx';
import { useMeta, useToast, useViewerCaption } from '../context.tsx';
import { Callout, Card, ErrorState, PageHead, Seg, SkeletonRows, cx } from '../components/ui.tsx';
import { Icon } from '../components/icons.tsx';
import type { Contestant, ContestantView, SuiteView } from '../types.ts';
import { channelApi } from './channelApi.ts';
import { HistoryChart } from './HistoryChart.tsx';
import './channel.css';

const TIERS = ['flagship', 'mid', 'small'] as const;

function toContestant(v: ContestantView): Contestant {
  const { configHash: _h, hasKey: _k, providerLabel: _l, providerType: _t, ...c } = v;
  return c;
}

function MetadataEditor({ contestants, onSaved }: { contestants: ContestantView[]; onSaved: () => void }) {
  const toast = useToast();
  const [draft, setDraft] = useState<Record<string, Partial<Contestant>>>({});
  const [busy, setBusy] = useState<string | null>(null);
  const rows = contestants.filter((c) => c.providerType !== 'mock');
  const val = <K extends 'family' | 'releaseDate' | 'tier'>(c: ContestantView, k: K) => (draft[c.id]?.[k] ?? c[k] ?? '') as string;
  const set = (id: string, patch: Partial<Contestant>) => setDraft((d) => ({ ...d, [id]: { ...d[id], ...patch } }));
  const save = async (c: ContestantView) => {
    const d = draft[c.id] ?? {};
    const next: Contestant = { ...toContestant(c), ...d };
    for (const k of ['family', 'releaseDate', 'tier'] as const) if (!next[k]) delete next[k];
    setBusy(c.id);
    try {
      await api.saveContestant(next);
      setDraft((x) => {
        const { [c.id]: _gone, ...rest } = x;
        return rest;
      });
      toast.success(`${c.label} updated`);
      onSaved();
    } catch (e) {
      toast.error(e);
    } finally {
      setBusy(null);
    }
  };
  return (
    <div className="table-wrap">
      <table className="table compact ch-meta">
        <thead>
          <tr>
            <th>Model</th>
            <th>Family</th>
            <th>Release date</th>
            <th>Tier</th>
            <th />
          </tr>
        </thead>
        <tbody>
          {rows.map((c) => {
            const dirty = !!draft[c.id];
            return (
              <tr key={c.id}>
                <td>
                  <span className="row" style={{ gap: 8 }}>
                    <span className="cat-dot" style={{ background: c.color }} />
                    <strong>{c.label}</strong>
                    {!c.releaseDate && <span className="badge warn">no date</span>}
                  </span>
                </td>
                <td>
                  <input className="input sm" value={val(c, 'family')} placeholder={c.vendor} onChange={(e) => set(c.id, { family: e.target.value })} aria-label={`${c.label} family`} />
                </td>
                <td>
                  <input className="input sm" type="date" value={val(c, 'releaseDate')} onChange={(e) => set(c.id, { releaseDate: e.target.value })} aria-label={`${c.label} release date`} />
                </td>
                <td>
                  <select className="select sm" value={val(c, 'tier')} onChange={(e) => set(c.id, { tier: (e.target.value || undefined) as Contestant['tier'] })} aria-label={`${c.label} tier`}>
                    <option value="">—</option>
                    {TIERS.map((t) => (
                      <option key={t} value={t}>
                        {t}
                      </option>
                    ))}
                  </select>
                </td>
                <td className="num">
                  <button className={cx('btn xs', dirty && 'primary')} disabled={!dirty || busy === c.id} onClick={() => void save(c)}>
                    {busy === c.id ? 'Saving…' : 'Save'}
                  </button>
                </td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}

export default function HistoryPage() {
  const { query } = useRoute();
  const { categories } = useMeta();
  const suite = query.get('suite') || 'core';
  const metric = query.get('metric') || 'index';
  const tiers = (query.get('tiers') ?? '').split(',').filter(Boolean);
  const [highlight, setHighlight] = useState<string | null>(null);
  const lists = useAsync<[SuiteView[], ContestantView[]]>(() => Promise.all([api.suites(), api.contestants()]), []);
  const hist = useAsync(() => channelApi.history(suite, metric, tiers), [suite, metric, tiers.join(',')]);
  const metricLabel = metric === 'index' ? 'Gauntlet Index' : categories.find((c) => c.id === metric)?.name ?? metric;
  const h = hist.data;
  const nModels = h ? h.families.reduce((s, f) => s + f.points.length, 0) : 0;
  useViewerCaption(
    h && nModels ? `How each AI family has improved over time: every dot is a model at its release date, and each line follows one family’s top models. Higher is better.` : null,
    h ? `${metricLabel} on the ${suite} suite · ${nModels} models · same tests for every model` : undefined,
  );
  const suites = lists.data?.[0] ?? [];
  const slideHref = useMemo(() => `/slides/history?suite=${encodeURIComponent(suite)}&metric=${encodeURIComponent(metric)}${tiers.length ? `&tiers=${tiers.join(',')}` : ''}`, [suite, metric, tiers]);

  return (
    <div className="page ch-page">
      <PageHead
        eyebrow={
          <span className="row" style={{ gap: 8 }}>
            <Icon.Chart style={{ width: 14, height: 14 }} /> Channel
          </span>
        }
        title="Model history"
        sub="Has AI really got better? Every model’s score against its release date, one line per family."
        actions={
          <Link to={slideHref} className="btn primary">
            <Icon.Present /> Present as slide
          </Link>
        }
      />

      <section className="card no-broadcast">
        <div className="card-body row wrap" style={{ gap: 16, alignItems: 'flex-end' }}>
          <label className="field" style={{ minWidth: 220 }}>
            <span className="label">Suite</span>
            <select className="select" value={suite} onChange={(e) => setQuery({ suite: e.target.value })}>
              {(suites.length ? suites : [{ id: suite, name: suite } as SuiteView]).map((s) => (
                <option key={s.id} value={s.id}>
                  {s.name}
                </option>
              ))}
            </select>
          </label>
          <label className="field" style={{ minWidth: 220 }}>
            <span className="label">Score</span>
            <select className="select" value={metric} onChange={(e) => setQuery({ metric: e.target.value === 'index' ? null : e.target.value })}>
              <option value="index">Gauntlet Index</option>
              {categories.map((c) => (
                <option key={c.id} value={c.id}>
                  {c.name}
                </option>
              ))}
            </select>
          </label>
          <div className="field">
            <span className="label">Tier</span>
            <Seg
              label="Tier"
              value={tiers.length === 1 ? tiers[0]! : 'all'}
              onChange={(v) => setQuery({ tiers: v === 'all' ? null : v })}
              options={[
                { value: 'all', label: 'All' },
                { value: 'flagship', label: 'Flagship' },
                { value: 'mid', label: 'Mid' },
                { value: 'small', label: 'Small' },
              ]}
            />
          </div>
        </div>
      </section>

      {hist.error ? (
        <ErrorState error={hist.error} onRetry={hist.reload} />
      ) : !h ? (
        <div className="card pad">
          <SkeletonRows rows={8} />
        </div>
      ) : (
        <>
          <Card title={`${metricLabel} by release date`} desc="Dots are models; each line joins one family’s flagship models (or best model per release), oldest to newest. Hover a dot for details, or a family below to focus it.">
            <HistoryChart data={h} metricLabel={metricLabel} highlight={highlight} />
            <div className="ch-family-chips no-broadcast">
              {h.families.map((f) => (
                <button key={f.family} type="button" className={cx('toggle-chip', highlight === f.family && 'on')} onMouseEnter={() => setHighlight(f.family)} onMouseLeave={() => setHighlight(null)} onClick={() => setHighlight(highlight === f.family ? null : f.family)}>
                  <span className="sw" style={{ background: f.color }} />
                  {f.family} · {f.points.length}
                </button>
              ))}
            </div>
          </Card>

          {h.jumps.length > 0 && (
            <div className="ch-jumps">
              {h.jumps.map((j, i) => {
                const color = h.families.find((f) => f.family === j.family)?.color;
                return (
                  <div key={i} className="ch-jump" style={{ ['--c' as string]: color }}>
                    <span className="eyebrow">{i === 0 ? 'Biggest jump' : `Jump #${i + 1}`} · {j.family}</span>
                    <span className="big">+{j.delta.toFixed(1)}</span>
                    <span>
                      {j.from.label} → <strong>{j.to.label}</strong>
                    </span>
                    <span className="muted small">
                      {j.from.value.toFixed(1)} → {j.to.value.toFixed(1)} in {Math.max(1, Math.round(j.days / 30.4))} months
                    </span>
                  </div>
                );
              })}
            </div>
          )}

          {(h.undated.length > 0 || h.noResults.length > 0) && (
            <Callout tone="info" icon={<Icon.Info />}>
              {h.undated.length > 0 && (
                <div>
                  <strong>Not on the chart (no release date):</strong> {h.undated.map((p) => p.label).join(', ')}. Add dates below.
                </div>
              )}
              {h.noResults.length > 0 && (
                <div>
                  <strong>No results on this suite yet:</strong> {h.noResults.map((p) => p.label).join(', ')}.
                </div>
              )}
            </Callout>
          )}
        </>
      )}

      <Card className="no-broadcast" title="Families, release dates and tiers" desc="Used only for this page, the Presenter slide and the public site. Changing them never affects scores.">
        {lists.error ? <ErrorState error={lists.error} onRetry={lists.reload} /> : !lists.data ? <SkeletonRows rows={6} /> : <MetadataEditor contestants={lists.data[1]} onSaved={() => { lists.reload(); hist.reload(); }} />}
      </Card>
    </div>
  );
}
