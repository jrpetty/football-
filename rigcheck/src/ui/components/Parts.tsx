import { useMemo, useRef, useState, useEffect } from 'react';
import { search } from '../../core/catalogue.ts';
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
  const ref = useRef<HTMLDivElement>(null);

  const current = kind === 'gpu' ? engineData.gpus.get(value) : engineData.cpus.get(value);

  const hits = useMemo(() => {
    if (!q.trim()) {
      const pool = kind === 'gpu' ? [...engineData.gpus.values()] : [...engineData.cpus.values()];
      return pool.slice(0, 40).map((p) => ({
        id: p.id,
        kind,
        label: p.fullName,
        disambiguator: '',
        score: 0,
      }));
    }
    return search(q, engineData, 60).filter((h) => h.kind === kind);
  }, [q, kind]);

  useEffect(() => {
    const onDoc = (e: MouseEvent) => {
      if (ref.current && !ref.current.contains(e.target as Node)) setOpen(false);
    };
    document.addEventListener('mousedown', onDoc);
    return () => document.removeEventListener('mousedown', onDoc);
  }, []);

  const pick = (id: string) => {
    onChange(id);
    setOpen(false);
    setQ('');
  };

  return (
    <div className="field" ref={ref}>
      {label && <label>{label}</label>}
      <div className="combo">
        <input
          type="text"
          value={open ? q : (current?.fullName ?? value)}
          placeholder={`search ${kind}…`}
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
            if (e.key === 'ArrowDown') { e.preventDefault(); setCursor((c) => Math.min(c + 1, hits.length - 1)); }
            else if (e.key === 'ArrowUp') { e.preventDefault(); setCursor((c) => Math.max(c - 1, 0)); }
            else if (e.key === 'Enter' && hits[cursor]) { e.preventDefault(); pick(hits[cursor].id); }
            else if (e.key === 'Escape') setOpen(false);
          }}
        />
        {open && (
          <div className="combo-list">
            {hits.length === 0 && <div className="combo-item"><span className="disambig">no match</span></div>}
            {hits.map((h, i) => (
              <div
                key={h.id}
                className={`combo-item${i === cursor ? ' cursor' : ''}`}
                onMouseEnter={() => setCursor(i)}
                onMouseDown={(e) => { e.preventDefault(); pick(h.id); }}
              >
                <span className="label">{h.label}</span>
                {h.disambiguator && <span className="disambig">{h.disambiguator}</span>}
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
