// ---------------------------------------------------------------------------
// One night, in full — including the photographs it was read from.
//
// Keeping the receipts alongside the figures is what makes a disputed night
// answerable weeks later, which is the moment the paper ledger it replaces was
// actually good at.
// ---------------------------------------------------------------------------

import { useEffect, useState } from 'react'
import { formatLong } from '../core/date.ts'
import { formatMoney, formatSigned } from '../core/money.ts'
import { reconcileDay, verdictHeadline } from '../core/reconcile.ts'
import type { Capture, DayRecord } from '../core/types.ts'
import { ItemisedLegs, VerdictPanel } from '../components/Verdict.tsx'
import { CrossfootList, CrossfootSummary } from '../components/CrossfootPanel.tsx'
import { crossfootVerdict } from '../core/crossfoot.ts'
import { departmentLabel, departmentSlot } from '../core/departments.ts'
import { formatQty, shareBp, formatPercent } from '../core/zread.ts'
import { seriesVar, ShareBar, Legend } from '../components/charts.tsx'
import { reconcileFull } from '../core/reconcile.ts'
import { deleteDay, getDay, getPhoto } from '../storage/db.ts'
import { loadSettings } from '../storage/settings.ts'

function provenance(c: Capture): string {
  if (c.source === 'manual') return 'Typed in'
  const engine = c.source === 'vision' ? 'Read by Claude' : 'Read on the phone'
  return c.edited ? `${engine}, then corrected` : engine
}

function Row({ label, capture, pence }: { label: string; capture?: Capture; pence: number | null }) {
  return (
    <div className="card-head" style={{ marginBottom: 6 }}>
      <span>
        <strong>{label}</strong>
        {capture && <><br /><span className="hint">{provenance(capture)}</span></>}
      </span>
      <span className="num" style={{ fontSize: 19, fontWeight: 700 }}>
        {pence === null ? '—' : formatMoney(pence)}
      </span>
    </div>
  )
}

interface Props {
  date: string
  onBack: () => void
  onEdit: (date: string) => void
  onDeleted: () => void
}

export function DayDetail({ date, onBack, onEdit, onDeleted }: Props) {
  const [day, setDay] = useState<DayRecord | null | undefined>(undefined)
  const [shots, setShots] = useState<{ till?: string; card?: string }>({})
  const [confirming, setConfirming] = useState(false)
  const tolerance = loadSettings().tolerancePence

  useEffect(() => {
    let cancelled = false
    const urls: string[] = []
    void (async () => {
      const found = await getDay(date).catch(() => undefined)
      if (cancelled) return
      setDay(found ?? null)
      if (!found) return
      const next: { till?: string; card?: string } = {}
      for (const [key, id] of [['till', found.till.photoId], ['card', found.card.photoId]] as const) {
        if (!id) continue
        const blob = await getPhoto(id).catch(() => undefined)
        if (!blob) continue
        const url = URL.createObjectURL(blob)
        urls.push(url)
        next[key] = url
      }
      if (cancelled) {
        urls.forEach((u) => URL.revokeObjectURL(u))
        return
      }
      setShots(next)
    })()
    return () => {
      cancelled = true
      urls.forEach((u) => URL.revokeObjectURL(u))
    }
  }, [date])

  if (day === undefined) return <div className="main"><p className="note"><span className="spinner" /> Loading…</p></div>
  if (day === null) return <div className="main"><p className="note">That night is no longer saved.</p><button type="button" onClick={onBack}>Back</button></div>

  const r = reconcileDay(day, tolerance)
  const full = reconcileFull({
    tillPence: day.till.pence,
    cardPence: day.card.pence,
    cashPence: day.cashPence,
    tolerancePence: tolerance,
    ...(day.zRead ? { zRead: day.zRead } : {}),
  })
  const counted = day.card.pence !== null && day.cashPence !== null ? day.card.pence + day.cashPence : null
  const zRead = day.zRead
  const deptTotal = zRead?.deptTotal?.pence ?? 0
  const shareRows =
    zRead?.departments.map((d) => ({
      key: d.code,
      label: departmentLabel(d.code, d.name),
      pence: d.pence,
      percentBp: shareBp(d.pence, deptTotal),
      slot: departmentSlot(d.code),
    })) ?? []

  return (
    <div className="main">
      <section className="card">
        <div className="card-head">
          <h2>{formatLong(date)}</h2>
          <span className={`badge ${r.verdict === 'balanced' ? 'good' : r.verdict === 'short' ? 'bad' : r.verdict === 'over' ? 'warn' : ''}`}>
            {verdictHeadline(r)}
          </span>
        </div>
        <Row label="Till roll total" capture={day.till} pence={day.till.pence} />
        <Row label="Card total" capture={day.card} pence={day.card.pence} />
        <Row label="Cash counted" pence={day.cashPence} />
        <hr style={{ border: 0, borderTop: '1px solid var(--line)', margin: '10px 0' }} />
        <Row label="Card + cash" pence={counted} />
        <div className="card-head" style={{ marginBottom: 0 }}>
          <strong>Variance</strong>
          <span className={`num delta ${r.verdict}`} style={{ fontSize: 19 }}>
            {r.complete ? formatSigned(r.variancePence) : '—'}
          </span>
        </div>
      </section>

      <VerdictPanel r={r} />

      <ItemisedLegs r={full} />

      {zRead && (
        <section className="card">
          <div className="card-head">
            <h2>What sold</h2>
            {zRead.header.zNumber !== undefined && <span className="badge">Z {zRead.header.zNumber}</span>}
          </div>
          <CrossfootSummary verdict={crossfootVerdict(zRead)} />
          <CrossfootList verdict={crossfootVerdict(zRead)} />
          {shareRows.length > 0 && (
            <>
              <ShareBar rows={shareRows} />
              <Legend items={shareRows.map((x) => ({ label: x.label, color: seriesVar(x.slot) }))} />
              <div className="table-wrap">
                <table className="data">
                  <thead>
                    <tr>
                      <th scope="col">Department</th>
                      <th scope="col">Sold</th>
                      <th scope="col">Taken</th>
                      <th scope="col">Share</th>
                    </tr>
                  </thead>
                  <tbody>
                    {zRead.departments.map((d) => (
                      <tr key={d.code}>
                        <th scope="row">
                          <span className="swatch" style={{ background: seriesVar(departmentSlot(d.code)) }} aria-hidden="true" />
                          {departmentLabel(d.code, d.name)}
                        </th>
                        <td className="num">{formatQty(d.qtyMilli)}</td>
                        <td className="num">{formatMoney(d.pence)}</td>
                        <td className="num">{formatPercent(shareBp(d.pence, deptTotal))}</td>
                      </tr>
                    ))}
                  </tbody>
                  <tfoot>
                    <tr>
                      <td>Total</td>
                      <td className="num">{formatQty(zRead.deptTotal?.qtyMilli ?? 0)}</td>
                      <td className="num">{formatMoney(deptTotal)}</td>
                      <td className="num">100.00%</td>
                    </tr>
                  </tfoot>
                </table>
              </div>
            </>
          )}
          {zRead.transaction.guestCount !== undefined && (
            <p className="note">
              {zRead.transaction.guestCount} sales, {formatMoney(zRead.transaction.avePence ?? 0)} average
              {zRead.transaction.voidCount ? ` · ${zRead.transaction.voidCount} voids` : ''}
              {zRead.transaction.noSaleCount ? ` · ${zRead.transaction.noSaleCount} no-sales` : ''}
            </p>
          )}
        </section>
      )}

      {day.note && (
        <section className="card">
          <div className="card-head"><h2>Note</h2></div>
          <p className="note" style={{ marginTop: 0 }}>{day.note}</p>
        </section>
      )}

      {(shots.till || shots.card) && (
        <section className="card">
          <div className="card-head"><h2>The receipts</h2></div>
          {shots.till && <div className="shot"><img src={shots.till} alt="The till roll as photographed" /></div>}
          {shots.card && <div className="shot"><img src={shots.card} alt="The card slip as photographed" /></div>}
        </section>
      )}

      <div className="btn-row">
        <button type="button" onClick={onBack}>Back</button>
        <button type="button" className="btn-primary" onClick={() => onEdit(date)}>Edit</button>
      </div>

      {confirming ? (
        <section className="card">
          <p className="note bad" style={{ marginTop: 0 }}>
            Delete {formatLong(date)} for good? The figures and photographs both go.
          </p>
          <div className="btn-row">
            <button type="button" onClick={() => setConfirming(false)}>Keep it</button>
            <button
              type="button"
              className="btn-danger"
              onClick={() => void deleteDay(date).then(onDeleted)}
            >
              Delete
            </button>
          </div>
        </section>
      ) : (
        <button type="button" className="btn-ghost btn-danger" onClick={() => setConfirming(true)}>
          Delete this night
        </button>
      )}
    </div>
  )
}
