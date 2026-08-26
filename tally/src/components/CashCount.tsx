// ---------------------------------------------------------------------------
// Counting the drawer out.
//
// A grid of what is actually in the till, biggest first, because that is the
// order it gets counted in. Type how many twenties, how many tens, and the
// total appears — rather than adding it up in your head at midnight and
// producing a figure that is wrong by exactly one note.
//
// It stays optional. Anyone who has already counted it can type the total
// straight into the box above and never open this.
// ---------------------------------------------------------------------------

import { countPieces, countTotal, DENOMINATIONS, type Tally } from '../core/cash.ts'
import { formatMoney } from '../core/money.ts'

interface Props {
  tally: Tally
  onChange: (next: Tally) => void
  /** Called with the total when she is done, to fill the figure above. */
  onUse: (pence: number) => void
  onClose: () => void
}

export function CashCount({ tally, onChange, onUse, onClose }: Props) {
  const total = countTotal(tally)
  const pieces = countPieces(tally)

  function set(pence: number, text: string) {
    const next = { ...tally }
    const n = Number(text)
    // An empty box is "none of these", not zero of them — the difference
    // matters only in that a stored 0 clutters the record for no reason.
    if (text.trim() === '' || !Number.isFinite(n) || n <= 0) delete next[pence]
    else next[pence] = Math.floor(n)
    onChange(next)
  }

  return (
    <div className="counter">
      <div className="counter-grid">
        {DENOMINATIONS.map((d) => {
          const n = tally[d.pence]
          return (
            <label className="counter-cell" key={d.pence}>
              <span className={`counter-label ${d.kind}`}>{d.label}</span>
              <input
                aria-label={`How many ${d.label}`}
                inputMode="numeric"
                placeholder="0"
                value={n === undefined ? '' : String(n)}
                onChange={(e) => set(d.pence, e.target.value)}
              />
              <span className="counter-sub num">{n ? formatMoney(n * d.pence) : ''}</span>
            </label>
          )
        })}
      </div>

      <div className="zrow">
        <span className="zname">
          Counted
          <small>
            {pieces.notes + pieces.coins === 0
              ? 'nothing counted yet'
              : `${pieces.notes} note${pieces.notes === 1 ? '' : 's'} · ${pieces.coins} coin${pieces.coins === 1 ? '' : 's'}`}
          </small>
        </span>
        <strong className="num" style={{ fontSize: 20 }}>{formatMoney(total)}</strong>
      </div>

      <div className="btn-row">
        <button type="button" className="btn-small" onClick={onClose}>Close</button>
        <button type="button" className="btn-primary" disabled={total === 0} onClick={() => onUse(total)}>
          Use {formatMoney(total)}
        </button>
      </div>
    </div>
  )
}
