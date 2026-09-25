/**
 * A labelled 0–100 meter with an icon, the value, the change since the last
 * step, and a flashing critical state (≤ 20). Used for the castaway's stats.
 */
import { cx } from '../ui.tsx';
import { SimIcon, type SimIconName } from './SimIcon.tsx';

export function StatMeter({ label, icon, value, prev, critical = 20 }: { label: string; icon: SimIconName; value: number; prev?: number; critical?: number }) {
  const v = Math.max(0, Math.min(100, value));
  const crit = v <= critical;
  const delta = prev === undefined ? 0 : Math.round(value - prev);
  return (
    <div className={cx('stat-meter', crit && 'critical', v >= 60 && 'healthy')} role="meter" aria-label={label} aria-valuemin={0} aria-valuemax={100} aria-valuenow={Math.round(v)}>
      <SimIcon name={icon} size="1.35em" />
      <span className="sm-label">{label}</span>
      <span className="sm-track">
        <span className="sm-fill" style={{ width: `${v}%` }} />
      </span>
      <b className="sm-val tnum">{Math.round(v)}</b>
      <span className={cx('sm-delta tnum', delta > 0 && 'up', delta < 0 && 'down')} aria-label={delta ? `changed by ${delta}` : undefined}>
        {delta > 0 ? `+${delta}` : delta < 0 ? `−${-delta}` : ''}
      </span>
    </div>
  );
}
