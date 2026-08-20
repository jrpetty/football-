import { useMemo, useRef, useState, useEffect } from 'react';
import { browseParts, search } from '../../core/catalogue.ts';
import { engineData } from '../store.ts';
import type { Confidence, FpsEstimate, ModelTerm } from '../../core/types.ts';

/* ---------------------------------------------------------------- picker -- */

export function PartPicker({
  kind,
  value,
  onChange,
  label,
}: {
  kind: 'cpu' | 'gpu';
  value: string;
  onChange: (id: string) => void;
  label?: string;
}) {
  const [open, setOpen] = useState(false);
  const [q, setQ] = useState('');
  const [cursor, setCursor] = useState(0);
  const [vendorFilter, setVendorFilter] = useState<string | null>(null);
  const ref = useRef<HTMLDivElement>(null);
  const listRef = useRef<HTMLDivElement>(null);

  const current = kind === 'gpu' ? engineData.gpus.get(value) : engineData.cpus.get(value);

  const groups = useMemo(() => browseParts(kind, engineData), [kind]);

  /**
   * Browsing shows the WHOLE catalogue grouped by vendor; searching flattens
   * matches into one relevance-ranked group. Either way the list is the same
   * shape, so keyboard navigation and rendering do not branch.
   */
  const visibleGroups = useMemo(() => {
    if (q.trim()) {
      const hits = search(q, engineData, 200).filter((h) => h.kind === kind);
      return hits.length ? [{ vendor: 'results', label: `${hits.length} match${hits.length === 1 ? '' : 'es'}`, hits }] : [];
    }
    return vendorFilter ? groups.filter((g) => g.vendor === vendorFilter) : groups;
  }, [q, kind, groups, vendorFilter]);

  // Flat view of the same items, so arrow keys cross group boundaries.
  const flat = useMemo(() => visibleGroups.flatMap((g) => g.hits), [visibleGroups]);

  useEffect(() => {
    const onDoc = (e: MouseEvent) => {
      if (ref.current && !ref.current.contains(e.target as Node)) setOpen(false);
    };
    document.addEventListener('mousedown', onDoc);
    return () => document.removeEventListener('mousedown', onDoc);
  }, []);

  // Keep the highlighted row on screen — the browse list runs to hundreds of
  // entries, so arrowing without this walks the cursor out of view immediately.
  useEffect(() => {
    if (!open) return;
    const el = listRef.current?.querySelector('[data-cursor="true"]');
    el?.scrollIntoView({ block: 'nearest' });
  }, [cursor, open]);

  const pick = (id: string) => {
    onChange(id);
    setOpen(false);
    setQ('');
  };

  let flatIndex = -1;

  return (
    <div className="field" ref={ref}>
      {label && <label>{label}</label>}
      <div className="combo">
        <input
          type="text"
          value={open ? q : (current?.fullName ?? value)}
          placeholder={`search or browse ${kind === 'gpu' ? 'GPUs' : 'CPUs'}…`}
          onFocus={() => {
            setOpen(true);
            setQ('');
            setCursor(0);
          }}
          onChange={(e) => {
            setQ(e.target.value);
            setCursor(0);
            setOpen(true);
          }}
          onKeyDown={(e) => {
            if (e.key === 'ArrowDown') { e.preventDefault(); setOpen(true); setCursor((c) => Math.min(c + 1, flat.length - 1)); }
            else if (e.key === 'ArrowUp') { e.preventDefault(); setCursor((c) => Math.max(c - 1, 0)); }
            else if (e.key === 'Enter' && flat[cursor]) { e.preventDefault(); pick(flat[cursor].id); }
            else if (e.key === 'Escape') setOpen(false);
          }}
        />
        {open && (
          <div className="combo-list" ref={listRef}>
            {/* Vendor filters: jump straight to a brand without knowing a part
                number. Hidden while searching, where relevance beats grouping. */}
            {!q.trim() && groups.length > 1 && (
              <div className="combo-filters">
                <button
                  type="button"
                  className={`toggle${vendorFilter === null ? ' on' : ''}`}
                  onMouseDown={(e) => { e.preventDefault(); setVendorFilter(null); setCursor(0); }}
                >
                  all {groups.reduce((n, g) => n + g.hits.length, 0)}
                </button>
                {groups.map((g) => (
                  <button
                    key={g.vendor}
                    type="button"
                    className={`toggle${vendorFilter === g.vendor ? ' on' : ''}`}
                    onMouseDown={(e) => { e.preventDefault(); setVendorFilter(g.vendor); setCursor(0); }}
                  >
                    {g.label} {g.hits.length}
                  </button>
                ))}
              </div>
            )}

            {visibleGroups.length === 0 && (
              <div className="combo-item"><span className="disambig">no match</span></div>
            )}

            {visibleGroups.map((group) => (
              <div key={group.vendor}>
                <div className="combo-group">{group.label}</div>
                {group.hits.map((h) => {
                  flatIndex += 1;
                  const i = flatIndex;
                  return (
                    <div
                      key={h.id}
                      data-cursor={i === cursor}
                      className={`combo-item${i === cursor ? ' cursor' : ''}${h.id === value ? ' selected' : ''}`}
                      onMouseEnter={() => setCursor(i)}
                      onMouseDown={(e) => { e.preventDefault(); pick(h.id); }}
                    >
                      <span className="label">{h.label}</span>
                      {h.incomplete && (
                        <span
                          className={h.estimable ? 'tag' : 'tag bad'}
                          title={
                            h.estimable
                              ? `Missing ${h.incomplete.join(', ')}. Still estimable — the band is just wider than it would be with the full record.`
                              : `Missing ${h.incomplete.join(', ')}, which is everything the index needs. The engine will refuse to estimate rather than invent a figure.`
                          }
                        >
                          {h.estimable ? 'gap' : 'no data'}
                        </span>
                      )}
                      {h.disambiguator && <span className="disambig">{h.disambiguator}</span>}
                    </div>
                  );
                })}
              </div>
            ))}
          </div>
        )}
      </div>
    </div>
  );
}

/* ------------------------------------------------------------ fps figure -- */

export function fmt(n: number | undefined, dp = 0): string {
  if (n == null || !Number.isFinite(n)) return '—';
  return n.toFixed(dp);
}

/**
 * Every number is clickable and opens its full working. The credibility of the
 * whole tool rests on being auditable — an opaque percentage is exactly what
 * this replaces.
 */
export function FpsFigure({
  estimate,
  onExplain,
  showBand = true,
}: {
  estimate: FpsEstimate;
  onExplain?: () => void;
  showBand?: boolean;
}) {
  if (estimate.status === 'NO_ESTIMATE') {
    return (
      <button className="fps" onClick={onExplain} title="Catalogue record incomplete — click for detail">
        <span className="fps-band" style={{ fontSize: 11 }}>no estimate</span>
        <span className="fps-band">record incomplete</span>
      </button>
    );
  }
  if (estimate.status === 'WILL_NOT_RUN') {
    return (
      <button className="fps" onClick={onExplain} title="Fails a hard capability gate — click for detail">
        <span className="fps-blocked">WILL NOT RUN</span>
        {estimate.gateFailures[0] && (
          <span className="fps-band">{estimate.gateFailures[0].code.replace(/_/g, ' ').toLowerCase()}</span>
        )}
      </button>
    );
  }
  const u = estimate.uncertainty ?? 0;
  return (
    <button className="fps" onClick={onExplain} title="Click to see how this was calculated">
      <span className="fps-main">{fmt(estimate.avgFps)}</span>
      {showBand && (
        <span className="fps-band">
          {fmt(estimate.band?.low)}–{fmt(estimate.band?.high)} · ±{(u * 100).toFixed(0)}%
        </span>
      )}
    </button>
  );
}

export function ConfidenceDot({ c }: { c: Confidence }) {
  return <span className={`dot ${c}`} title={`confidence: ${c}`} />;
}

export function LimiterTag({ limiter }: { limiter?: FpsEstimate['limiter'] }) {
  if (!limiter) return null;
  return <span className={`tag ${limiter}`}>{limiter}</span>;
}

/* --------------------------------------------------------------- explain -- */

export function ExplainPanel({ estimate, title }: { estimate: FpsEstimate; title: string }) {
  return (
    <div className="explain">
      <div className="explain-head">
        <span>how this was calculated — {title}</span>
        <span style={{ marginLeft: 'auto', display: 'inline-flex', gap: 6, alignItems: 'center' }}>
          <ConfidenceDot c={estimate.confidence} />
          <span>{estimate.confidence}</span>
        </span>
      </div>

      {estimate.gateFailures.length > 0 && (
        <div style={{ padding: 10 }}>
          {estimate.gateFailures.map((f) => (
            <div className="gate-fail" key={f.code}>
              <div className="code">{f.code}</div>
              <div style={{ marginTop: 3 }}>{f.detail}</div>
              <div style={{ marginTop: 3, color: 'var(--faint)' }} className="mono">
                required {f.required} · actual {f.actual}
              </div>
            </div>
          ))}
          <div className="note bad" style={{ marginTop: 8 }}>
            A capability failure is not a low frame rate. The engine short-circuits before estimating,
            because the honest answer is that the title will not launch.
          </div>
        </div>
      )}

      {estimate.status === 'NO_ESTIMATE' && (
        <div style={{ padding: 10 }}>
          {estimate.terms.map((t, i) => (
            <div className="note warn" key={i}>{t.explain}</div>
          ))}
        </div>
      )}

      {estimate.status === 'ok' && (
        <>
          <table className="terms">
            <tbody>
              {estimate.terms.map((t: ModelTerm, i: number) => (
                <tr key={i}>
                  <td className="t-label">
                    <ConfidenceDot c={t.confidence} /> {t.label}
                  </td>
                  <td className="t-value">{typeof t.value === 'number' ? t.value : t.value}</td>
                  <td className="t-explain">{t.explain}</td>
                </tr>
              ))}
            </tbody>
          </table>
          <div style={{ padding: '8px 10px', borderTop: '1px solid var(--line)' }}>
            <div className="legend">
              <span className="item"><b className="mono">cpu-bound</b> {fmt(estimate.cpuBoundFps, 1)} fps</span>
              <span className="item"><b className="mono">gpu-bound</b> {fmt(estimate.gpuBoundFps, 1)} fps</span>
              <span className="item"><b className="mono">1% low</b> {fmt(estimate.low1PctFps, 1)} fps</span>
              <span className="item">limiter <LimiterTag limiter={estimate.limiter} /></span>
            </div>
          </div>
        </>
      )}
    </div>
  );
}

/* ----------------------------------------------------------------- delta -- */

export function Delta({ value, noise }: { value?: number; noise?: boolean }) {
  if (value == null) return <span className="delta" style={{ color: 'var(--faint)' }}>baseline</span>;
  const cls = noise ? 'noise' : value > 0 ? 'up' : value < 0 ? 'down' : '';
  return (
    <span
      className={`delta ${cls}`}
      title={noise ? 'Smaller than the combined uncertainty of the two builds — not a real finding.' : undefined}
    >
      {value > 0 ? '+' : ''}
      {(value * 100).toFixed(1)}%
    </span>
  );
}

export function Legend() {
  return (
    <div className="legend">
      <span className="item"><span className="dot measured" /> measured</span>
      <span className="item"><span className="dot interpolated" /> interpolated</span>
      <span className="item"><span className="dot spec-derived" /> spec-derived</span>
      <span className="item"><span className="dot extrapolated" /> extrapolated</span>
      <span className="item"><span className="dot gate-blocked" /> gate-blocked</span>
      <span className="item" style={{ marginLeft: 8 }}>
        <span className="delta noise">±0.0%</span> below noise floor
      </span>
    </div>
  );
}
