/**
 * Moves used against par (the optimal solution) and the hard budget: a bar
 * that stays green up to par, turns amber past it and red near the budget.
 */
import { cx } from '../ui.tsx';

export function ParMeter({ used, par, budget, label = 'Moves' }: { used: number; par: number; budget: number; label?: string }) {
  const max = Math.max(1, budget);
  const pct = (v: number) => `${Math.min(100, (v / max) * 100)}%`;
  const state = used <= par ? 'under' : used >= budget - Math.max(2, Math.round(budget * 0.1)) ? 'danger' : 'over';
  return (
    <div className={cx('par-meter', state)} aria-label={`${label}: ${used} used, par ${par}, budget ${budget}`}>
      <div className="pm-head">
        <span>{label}</span>
        <b className="tnum">
          {used}
          <small> / {budget}</small>
        </b>
      </div>
      <div className="pm-track">
        <span className="pm-fill" style={{ width: pct(used) }} />
        <span className="pm-par" style={{ left: pct(par) }} title={`Par: ${par}`} />
      </div>
      <div className="pm-scale">
        <span className="pm-par-label" style={{ left: pct(par) }}>
          par {par}
        </span>
        <span className="pm-budget-label">budget {budget}</span>
      </div>
    </div>
  );
}
