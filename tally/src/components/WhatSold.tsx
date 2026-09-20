// ---------------------------------------------------------------------------
// What sold, on the screen.
//
// One component, used in three places — tonight, a night looked up again, and a
// span of nights — because the answer must not change shape depending on where
// she is standing when she asks the question.
//
// Two tables rather than one merged one. The roll states departments and
// buttons separately and gives no way to put a button into a department, so
// they stay separate here too. Each carries its own total, and each says how
// many receipts it was read off, because a table that covers four of seven
// nights is not a week however much it looks like one.
// ---------------------------------------------------------------------------

import { useState } from 'react'
import { formatMoney } from '../core/money.ts'
import { departmentSlot } from '../core/departments.ts'
import { shortBy, type Sold, type SoldSide } from '../core/sold.ts'
import { formatQty } from '../core/zread.ts'
import { seriesVar, StatTile } from './charts.tsx'

/** Units, as a person says them: 406000 -> "406". */
function units(qtyMilli: number): string {
  return formatQty(qtyMilli)
}

function each(pence: number | null): string {
  return pence === null ? '—' : formatMoney(pence)
}

/** "off 4 of the 7 receipts read" — or nothing at all when it is off all of them. */
function coverage(sold: Sold, side: SoldSide): string | null {
  const missing = shortBy(sold, side)
  if (missing === 0 || side.reads === 0) return null
  return `off ${side.reads} of the ${sold.reads} receipts read — ${missing} did not carry this block`
}

function Table({
  sold,
  side,
  heading,
  what,
  testid,
  swatches,
  limit,
}: {
  sold: Sold
  side: SoldSide
  heading: string
  /** The word for one row: "Category", "Item". */
  what: string
  testid: string
  swatches?: boolean
  /** Rows shown before the rest is folded away. */
  limit?: number
}) {
  const [all, setAll] = useState(false)
  if (side.lines.length === 0) return null

  const rows = limit && !all ? side.lines.slice(0, limit) : side.lines
  const hidden = side.lines.length - rows.length
  const note = coverage(sold, side)

  return (
    <section className="card">
      <div className="card-head">
        <h2>{heading}</h2>
        <span className="hint">
          {units(side.qtyMilli)} sold · {formatMoney(side.pence)}
        </span>
      </div>
      {note && <p className="note warn" style={{ marginTop: 0 }}>{note}</p>}
      <div className="table-wrap">
        <table className="data" data-testid={testid}>
          <thead>
            <tr>
              <th scope="col">{what}</th>
              <th scope="col">Sold</th>
              <th scope="col">Taken</th>
              <th scope="col">Each</th>
              <th scope="col">Share</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((l) => (
              <tr key={l.code}>
                <th scope="row">
                  {swatches && (
                    <span
                      className="swatch"
                      style={{ background: seriesVar(departmentSlot(l.code)) }}
                      aria-hidden="true"
                    />
                  )}
                  {l.name}
                </th>
                <td className="num">{units(l.qtyMilli)}</td>
                <td className="num">{formatMoney(l.pence)}</td>
                <td className="num">{each(l.eachPence)}</td>
                <td className="num">{(l.shareBp / 100).toFixed(2)}%</td>
              </tr>
            ))}
          </tbody>
          <tfoot>
            <tr>
              <td>Total</td>
              <td className="num">{units(side.qtyMilli)}</td>
              <td className="num">{formatMoney(side.pence)}</td>
              <td className="num">
                {each(side.qtyMilli > 0 ? Math.round(side.pence / (side.qtyMilli / 1000)) : null)}
              </td>
              <td className="num">100.00%</td>
            </tr>
          </tfoot>
        </table>
      </div>
      {hidden > 0 && (
        <div className="alts">
          <button type="button" className="btn-small" onClick={() => setAll(true)}>
            Show the other {hidden}
          </button>
        </div>
      )}
    </section>
  )
}

/**
 * The headline and the two tables.
 *
 * `heading` names what the figures cover — "Tonight", "Over 30 nights" — so the
 * same component can never be mistaken for saying something about a different
 * span than the one she asked for.
 */
export function WhatSold({
  sold,
  heading,
  limit = 12,
}: {
  sold: Sold
  heading: string
  limit?: number
}) {
  if (sold.reads === 0) {
    return (
      <section className="card">
        <div className="card-head"><h2>What sold</h2></div>
        <p className="note" style={{ marginTop: 0, marginBottom: 0 }}>
          {sold.offered === 0
            ? 'Nothing photographed yet. Add the till roll and everything below fills itself in.'
            : 'Nothing has been read off the photographs yet, so there is nothing to add up — which is not the same as a night that sold nothing.'}
        </p>
      </section>
    )
  }

  // The headline runs off the department block where there is one, because that
  // is the till's own statement of the night. Where the top of the roll was
  // missed it runs off the buttons instead, and says so.
  const main = sold.categories.reads > 0 ? sold.categories : sold.items
  const fromItems = sold.categories.reads === 0

  return (
    <>
      <div className="kpi-row" data-testid="sold-headline">
        <StatTile
          label={`Taken — ${heading.toLowerCase()}`}
          value={formatMoney(main.pence)}
          detail={fromItems ? 'off the buttons, no department block read' : undefined}
        />
        <StatTile
          label="Units sold"
          value={units(main.qtyMilli)}
          detail={
            main.qtyMilli > 0
              ? `${formatMoney(Math.round(main.pence / (main.qtyMilli / 1000)))} each`
              : undefined
          }
        />
        <StatTile
          label={sold.reads === 1 ? 'Receipt read' : 'Receipts read'}
          value={String(sold.reads)}
          detail={sold.offered > sold.reads ? `${sold.offered - sold.reads} not read` : undefined}
        />
      </div>

      <Table
        sold={sold}
        side={sold.categories}
        heading="By category"
        what="Category"
        testid="mix-table"
        swatches
      />
      <Table
        sold={sold}
        side={sold.items}
        heading="By item"
        what="Item"
        testid="sold-items"
        limit={limit}
      />
    </>
  )
}
