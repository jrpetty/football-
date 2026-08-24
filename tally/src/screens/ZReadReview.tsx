// ---------------------------------------------------------------------------
// Checking every figure off the roll.
//
// The cross-foot runs on every keystroke, so correcting a misread digit turns
// the failing sum green while she is still looking at it. That immediate answer
// is the whole point: it is the difference between "the app thinks something is
// wrong somewhere" and "that was the one, it agrees now".
// ---------------------------------------------------------------------------

import { useEffect, useState } from 'react'
import { CrossfootList, CrossfootSummary } from '../components/CrossfootPanel.tsx'
import { crossfootVerdict } from '../core/crossfoot.ts'
import { departmentLabel, departmentSlot } from '../core/departments.ts'
import { formatMoney, parsePence, penceToInput } from '../core/money.ts'
import { formatPercent, formatQty, shareBp, type ZRead } from '../core/zread.ts'
import { seriesVar } from '../components/charts.tsx'

/** A money box that tolerates being half-typed. */
function MoneyCell({
  label,
  value,
  onChange,
}: {
  label: string
  value: number | undefined
  onChange: (pence: number | undefined) => void
}) {
  const [text, setText] = useState(() => (value === undefined ? '' : penceToInput(value)))

  // Re-sync when the figure changes underneath — a second photograph of the
  // roll, or the read being started again.
  useEffect(() => {
    setText(value === undefined ? '' : penceToInput(value))
  }, [value])

  return (
    <input
      aria-label={label}
      inputMode="decimal"
      value={text}
      onChange={(e) => {
        setText(e.target.value)
        if (e.target.value.trim() === '') return onChange(undefined)
        const pence = parsePence(e.target.value)
        if (pence !== null) onChange(pence)
      }}
      onFocus={(e) => e.target.select()}
    />
  )
}

function CountCell({
  label,
  value,
  onChange,
  scale = 1,
}: {
  label: string
  value: number | undefined
  onChange: (n: number | undefined) => void
  /** 1000 for quantities the till prints to three decimals. */
  scale?: number
}) {
  const [text, setText] = useState(() => (value === undefined ? '' : String(value / scale)))
  useEffect(() => {
    setText(value === undefined ? '' : String(value / scale))
  }, [value, scale])

  return (
    <input
      aria-label={label}
      inputMode="decimal"
      value={text}
      onChange={(e) => {
        setText(e.target.value)
        if (e.target.value.trim() === '') return onChange(undefined)
        const n = Number(e.target.value.replace(/,/g, ''))
        if (Number.isFinite(n)) onChange(Math.round(n * scale))
      }}
      onFocus={(e) => e.target.select()}
    />
  )
}

interface Props {
  zRead: ZRead
  onChange: (next: ZRead) => void
  onBack: () => void
}

export function ZReadReview({ zRead, onChange, onBack }: Props) {
  const verdict = crossfootVerdict(zRead)
  const t = zRead.transaction
  const totalPence = zRead.deptTotal?.pence ?? 0

  const setTransaction = (patch: Partial<typeof t>) =>
    onChange({ ...zRead, transaction: { ...t, ...patch } })

  return (
    <div className="main">
      <section className="card">
        <div className="card-head">
          <h2>Every figure on the roll</h2>
          {zRead.header.zNumber !== undefined && <span className="badge">Z {zRead.header.zNumber}</span>}
        </div>
        <CrossfootSummary verdict={verdict} />
        <CrossfootList verdict={verdict} />
        {zRead.header.printedAt && (
          <p className="note">Printed {zRead.header.printedAt}{zRead.header.clerk ? ` by ${zRead.header.clerk}` : ''}.</p>
        )}
      </section>

      {/* --- departments ---------------------------------------------------- */}
      <section className="card">
        <div className="card-head">
          <h2>Departments</h2>
          <span className="hint">sold · taken</span>
        </div>
        {zRead.departments.map((d, i) => (
          <div className="zrow" key={d.code}>
            <span className="zname">
              <span className="swatch" style={{ background: seriesVar(departmentSlot(d.code)), display: 'inline-block', width: 9, height: 9, borderRadius: 3, marginRight: 8 }} aria-hidden="true" />
              {departmentLabel(d.code, d.name)}
              <small>{d.code}{d.group ? ` · ${d.group}` : ''}</small>
            </span>
            <CountCell
              label={`${d.name} quantity`}
              value={d.qtyMilli}
              scale={1000}
              onChange={(n) => {
                const next = [...zRead.departments]
                next[i] = { ...d, qtyMilli: n ?? 0 }
                onChange({ ...zRead, departments: next })
              }}
            />
            <MoneyCell
              label={`${d.name} taken`}
              value={d.pence}
              onChange={(pence) => {
                const next = [...zRead.departments]
                next[i] = { ...d, pence: pence ?? 0 }
                onChange({ ...zRead, departments: next })
              }}
            />
            {/* Recomputed rather than read off the paper, so it cannot drift. */}
            <span className="zpct">{totalPence > 0 ? formatPercent(shareBp(d.pence, totalPence)) : '—'}</span>
          </div>
        ))}

        {zRead.groups.map((g, i) => (
          <div className="zrow" key={g.code}>
            <span className="zname"><strong>{g.code}</strong><small>group subtotal</small></span>
            <span className="zpct num">{formatQty(g.qtyMilli)}</span>
            <MoneyCell
              label={`${g.code} total`}
              value={g.pence}
              onChange={(pence) => {
                const next = [...zRead.groups]
                next[i] = { ...g, pence: pence ?? 0 }
                onChange({ ...zRead, groups: next })
              }}
            />
            <span className="zpct" />
          </div>
        ))}

        {zRead.deptTotal && (
          <div className="zrow">
            <span className="zname"><strong>Department total</strong><small>all departments</small></span>
            <CountCell
              label="Total quantity"
              value={zRead.deptTotal.qtyMilli}
              scale={1000}
              onChange={(n) => onChange({ ...zRead, deptTotal: { ...zRead.deptTotal!, qtyMilli: n ?? 0 } })}
            />
            <MoneyCell
              label="Department total"
              value={zRead.deptTotal.pence}
              onChange={(pence) => onChange({ ...zRead, deptTotal: { ...zRead.deptTotal!, pence: pence ?? 0 } })}
            />
            <span className="zpct">100.00%</span>
          </div>
        )}
      </section>

      {/* --- how it was paid ------------------------------------------------- */}
      <section className="card">
        <div className="card-head"><h2>How it was paid</h2></div>

        <div className="zrow" style={{ borderTop: 0 }}>
          <span className="zname">Cash<small>transactions · taken</small></span>
          <CountCell label="Cash transactions" value={t.cashCount} onChange={(n) => setTransaction({ cashCount: n })} />
          <MoneyCell label="Cash taken" value={t.cashPence} onChange={(p) => setTransaction({ cashPence: p })} />
        </div>

        <div className="zrow">
          <span className="zname">Card<small>transactions · taken</small></span>
          <CountCell label="Card transactions" value={t.cardCount} onChange={(n) => setTransaction({ cardCount: n })} />
          <MoneyCell label="Card taken" value={t.cardPence} onChange={(p) => setTransaction({ cardPence: p })} />
        </div>

        <div className="zrow">
          <span className="zname">Cash in drawer<small>what should be there</small></span>
          <MoneyCell label="Cash in drawer" value={t.cidPence} onChange={(p) => setTransaction({ cidPence: p })} />
        </div>

        <div className="zrow">
          <span className="zname">Paid total</span>
          <MoneyCell label="Paid total" value={t.paidTotalPence} onChange={(p) => setTransaction({ paidTotalPence: p })} />
        </div>

        <div className="zrow">
          <span className="zname">Order total</span>
          <MoneyCell label="Order total" value={t.orderTotalPence} onChange={(p) => setTransaction({ orderTotalPence: p })} />
        </div>
      </section>

      {/* --- counts ---------------------------------------------------------- */}
      <section className="card">
        <div className="card-head"><h2>Counts</h2></div>

        <div className="zrow" style={{ borderTop: 0 }}>
          <span className="zname">Sales<small>the till's guest count</small></span>
          <CountCell label="Guest count" value={t.guestCount} onChange={(n) => setTransaction({ guestCount: n })} />
        </div>
        <div className="zrow">
          <span className="zname">Average spend</span>
          <MoneyCell label="Average spend" value={t.avePence} onChange={(p) => setTransaction({ avePence: p })} />
        </div>
        <div className="zrow">
          <span className="zname">Voids<small>count · value</small></span>
          <CountCell label="Void count" value={t.voidCount} onChange={(n) => setTransaction({ voidCount: n })} />
          <MoneyCell label="Void value" value={t.voidPence} onChange={(p) => setTransaction({ voidPence: p })} />
        </div>
        <div className="zrow">
          <span className="zname">No sales<small>drawer opened without a sale</small></span>
          <CountCell label="No sale count" value={t.noSaleCount} onChange={(n) => setTransaction({ noSaleCount: n })} />
        </div>
      </section>

      {/* --- clerks ---------------------------------------------------------- */}
      {zRead.clerks.length > 0 && (
        <section className="card">
          <div className="card-head"><h2>By clerk</h2></div>
          <div className="table-wrap">
            <table className="data">
              <thead>
                <tr>
                  <th scope="col">Clerk</th>
                  <th scope="col">Sales</th>
                  <th scope="col">Cash</th>
                  <th scope="col">Card</th>
                  <th scope="col">Taken</th>
                </tr>
              </thead>
              <tbody>
                {zRead.clerks.map((c) => (
                  <tr key={c.code}>
                    <th scope="row">{c.name ?? c.code}</th>
                    <td className="num">{c.guestCount ?? '—'}</td>
                    <td className="num">{c.cashPence === undefined ? '—' : formatMoney(c.cashPence)}</td>
                    <td className="num">{c.cardPence === undefined ? '—' : formatMoney(c.cardPence)}</td>
                    <td className="num">{c.paidTotalPence === undefined ? '—' : formatMoney(c.paidTotalPence)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </section>
      )}

      {/* --- items ------------------------------------------------------------ */}
      {zRead.plus.length > 0 && (
        <section className="card">
          <div className="card-head">
            <h2>Items</h2>
            <span className="hint">{zRead.plus.length} lines</span>
          </div>
          <div className="table-wrap">
            <table className="data">
              <thead>
                <tr>
                  <th scope="col">Item</th>
                  <th scope="col">Sold</th>
                  <th scope="col">Taken</th>
                </tr>
              </thead>
              <tbody>
                {zRead.plus.map((p) => (
                  <tr key={p.code}>
                    <th scope="row">{p.name || p.code}</th>
                    <td className="num">{formatQty(p.qtyMilli)}</td>
                    <td className="num">{formatMoney(p.pence)}</td>
                  </tr>
                ))}
              </tbody>
              {zRead.pluTotal && (
                <tfoot>
                  <tr>
                    <td>Total</td>
                    <td className="num">{formatQty(zRead.pluTotal.qtyMilli)}</td>
                    <td className="num">{formatMoney(zRead.pluTotal.pence)}</td>
                  </tr>
                </tfoot>
              )}
            </table>
          </div>
        </section>
      )}

      <button type="button" className="btn-primary" onClick={onBack}>Done</button>
    </div>
  )
}
