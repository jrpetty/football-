// ---------------------------------------------------------------------------
// What should be down there, against what is.
//
// One table, two screens. The night's own page shows it after the fact; the
// count sheet shows it as she types, before anything is saved. They must never
// disagree, so they are the same component reading the same window arithmetic:
// the last count, plus what came in, less what the till poured.
// ---------------------------------------------------------------------------

import { formatSigned } from '../core/money.ts'
import { formatServings, formatServingsSigned, type CellarWindow } from '../core/stock.ts'
import { IconTickSmall } from './icons.tsx'

interface Props {
  gap: CellarWindow
  /** Counting now rather than reading back a night that is done. */
  live?: boolean
  /**
   * Sold lines on the roll with no pour set against them.
   *
   * They take nothing off the cellar, so every one of them makes the gap read
   * worse than it is — beer that went through the till looking like beer that
   * walked. Silent, and the worst kind of wrong this table could be, so it
   * says so rather than letting the figure stand.
   */
  unmapped?: number
}

export function CellarGap({ gap, live = false, unmapped = 0 }: Props) {
  if (gap.lines.length === 0) {
    return (
      <p className="note" style={{ marginBottom: 0 }}>
        Nothing counted here was on the count before it, so nothing can be judged yet.
      </p>
    )
  }
  return (
    <>
      <div className="table-wrap">
        <table className="data">
          <thead>
            <tr>
              <th scope="col">Line</th>
              <th scope="col">Should be</th>
              <th scope="col">{live ? 'Counted' : 'Was'}</th>
              <th scope="col">Out by</th>
            </tr>
          </thead>
          <tbody>
            {gap.lines.map((v) => {
              const out = v.varianceBaseUnits ?? 0
              return (
                <tr key={v.item.id}>
                  <th scope="row">{v.item.name}</th>
                  <td className="num">{formatServings(v.expectedBaseUnits, v.item)}</td>
                  <td className="num">{formatServings(v.actualBaseUnits ?? 0, v.item)}</td>
                  <td className={`num delta ${out < 0 ? 'short' : out > 0 ? 'over' : ''}`}>
                    {out === 0 ? <IconTickSmall size={16} /> : formatServingsSigned(out, v.item)}
                  </td>
                </tr>
              )
            })}
          </tbody>
        </table>
      </div>
      <div className="zrow">
        <span className="zname">
          At cost
          <small>what the gap comes to, on the lines with a cost set</small>
        </span>
        <strong className={`num ${gap.gapPence !== null && gap.gapPence < 0 ? 'short' : ''}`}>
          {gap.gapPence === null ? '—' : gap.gapPence === 0 ? 'nothing out' : formatSigned(gap.gapPence)}
        </strong>
      </div>
      {unmapped > 0 && (
        <p className="note warn">
          {unmapped} sold {unmapped === 1 ? 'line has' : 'lines have'} no pour set, so what{' '}
          {unmapped === 1 ? 'it took' : 'they took'} off the cellar is not in this — the gap reads
          worse than it is. Set {unmapped === 1 ? 'it' : 'them'} on the Cellar tab, under Set up.
        </p>
      )}
      <p className="note" style={{ marginBottom: 0 }}>
        Short here is stock that left the cellar without going through the till. Before reading it
        that way: spillage, line cleaning, a wrong pour setting and a missed delivery all land in the
        same column, and all of them are commoner than the alternative.
      </p>
    </>
  )
}
