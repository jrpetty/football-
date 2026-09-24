import { useState } from 'react';
import type { ReactNode } from 'react';
import { Icon } from '../icons.tsx';
import { cx } from '../ui.tsx';

/**
 * Card wrapper for every chart: title, description, optional controls, and the
 * accessible table-view twin (toggle between chart and table).
 */
export function ChartCard({
  title,
  desc,
  controls,
  table,
  children,
  className,
  foot,
}: {
  title: ReactNode;
  desc?: ReactNode;
  controls?: ReactNode;
  table?: ReactNode;
  children: ReactNode;
  className?: string;
  foot?: ReactNode;
}) {
  const [showTable, setShowTable] = useState(false);
  return (
    <section className={cx('card chart-card', className)}>
      <div className="card-head">
        <div className="t">
          <h2>{title}</h2>
          {desc && <div className="desc">{desc}</div>}
        </div>
        <div className="tools">
          {controls}
          {table && (
            <button
              type="button"
              className="btn ghost icon sm no-broadcast"
              onClick={() => setShowTable((v) => !v)}
              aria-pressed={showTable}
              aria-label={showTable ? 'Show chart' : 'Show data table'}
              title={showTable ? 'Show chart' : 'Show data table'}
            >
              {showTable ? <Icon.Chart /> : <Icon.Table />}
            </button>
          )}
        </div>
      </div>
      <div className="card-body">{showTable && table ? <div className="table-wrap chart-table">{table}</div> : children}</div>
      {foot && <div className="card-foot">{foot}</div>}
    </section>
  );
}
