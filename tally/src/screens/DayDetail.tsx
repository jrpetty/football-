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
import { deleteDay, getDay, getPhoto, listPeople, listShifts } from '../storage/db.ts'
import { crewFor, formatHours, formatTime, shiftMinutes, type Person, type Shift } from '../core/rota.ts'
import { nightSummary, summaryFilename } from '../core/summary.ts'
import { downloadFile } from '../storage/export.ts'
import { loadSettings } from '../storage/settings.ts'

function provenance(c: Capture): string {
  if (c.source === 'manual') return 'Typed in'
  const engine = c.source === 'vision' ? 'Read by Claude' : 'Read on the phone'
  return c.edited ? `${engine}, then corrected` : engine
}

function Row({
  label,
  capture,
  pence,
  signed,
}: {
  label: string
  capture?: Capture
  pence: number | null
  /** A deduction, shown with the same typographic minus as every variance. */
  signed?: boolean
}) {
  return (
    <div className="card-head" style={{ marginBottom: 6 }}>
      <span>
        <strong>{label}</strong>
        {capture && <><br /><span className="hint">{provenance(capture)}</span></>}
      </span>
      <span className="num" style={{ fontSize: 19, fontWeight: 700 }}>
        {pence === null ? '—' : signed ? formatSigned(pence) : formatMoney(pence)}
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
  const [shared, setShared] = useState('')
  const [people, setPeople] = useState<Person[]>([])
  const [shifts, setShifts] = useState<Shift[]>([])
  const tolerance = loadSettings().tolerancePence

  useEffect(() => {
    let cancelled = false
    void Promise.all([listPeople(), listShifts()])
      .then(([p, sh]) => {
        if (cancelled) return
        setPeople(p)
        setShifts(sh)
      })
      .catch(() => {})
    return () => {
      cancelled = true
    }
  }, [date])

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

  /**
   * Hand the night to somebody else.
   *
   * The share sheet where the phone has one, because that is how a photograph
   * of the roll gets emailed already and it is the fewest taps. Falling back to
   * the clipboard, then to a file — one of the three works everywhere, and the
   * message says which happened so nothing appears to have silently failed.
   */
  async function share() {
    const text = nightSummary({ day: day as DayRecord, reconciliation: full, people, shifts })
    const title = `Takings — ${formatLong(date)}`
    try {
      if (navigator.share) {
        await navigator.share({ title, text })
        return
      }
      await navigator.clipboard.writeText(text)
      setShared('Copied — paste it into an email.')
    } catch (err) {
      // A cancelled share sheet is not a failure and must not look like one.
      if (err instanceof DOMException && err.name === 'AbortError') return
      try {
        downloadFile(summaryFilename(date), text, 'text/plain')
        setShared('Saved as a file.')
      } catch {
        setShared('Could not share that night.')
      }
    } finally {
      setTimeout(() => setShared(''), 4000)
    }
  }
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
        {day.floatPence ? (
          <>
            <Row label="Drawer counted" pence={(day.cashPence ?? 0) + day.floatPence} />
            <Row label="Less float" pence={-day.floatPence} signed />
            <Row label="Cash takings" pence={day.cashPence} />
          </>
        ) : (
          <Row label="Cash counted" pence={day.cashPence} />
        )}
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

      {crewFor(date, shifts, people).shifts.length > 0 && (() => {
        const night = crewFor(date, shifts, people)
        return (
          <section className="card">
            <div className="card-head">
              <h2>Who was on</h2>
              <span className="hint">{formatHours(night.minutes)}{night.costPence === null ? '' : ` · ${formatMoney(night.costPence)}`}</span>
            </div>
            {night.shifts.map((sh) => {
              const person = people.find((p) => p.id === sh.personId)
              return (
                <div className="zrow" key={sh.id}>
                  <span className="zname">
                    <span
                      className="legend-dot"
                      style={{ background: seriesVar(person?.slot ?? 1), marginRight: 8 }}
                      aria-hidden="true"
                    />
                    {person?.name ?? 'Someone'}
                    <small>{formatTime(sh.startMin)}–{formatTime(sh.endMin)}</small>
                  </span>
                  <span className="num">{formatHours(shiftMinutes(sh))}</span>
                </div>
              )
            })}
          </section>
        )
      })()}

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
                      <th scope="col">Each</th>
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
                        <td className="num">
                          {d.qtyMilli > 0 ? formatMoney(Math.round(d.pence / (d.qtyMilli / 1000))) : '—'}
                        </td>
                        <td className="num">{formatPercent(shareBp(d.pence, deptTotal))}</td>
                      </tr>
                    ))}
                  </tbody>
                  <tfoot>
                    <tr>
                      <td>Total</td>
                      <td className="num">{formatQty(zRead.deptTotal?.qtyMilli ?? 0)}</td>
                      <td className="num">{formatMoney(deptTotal)}</td>
                      <td className="num">
                        {(zRead.deptTotal?.qtyMilli ?? 0) > 0
                          ? formatMoney(Math.round(deptTotal / ((zRead.deptTotal?.qtyMilli ?? 1) / 1000)))
                          : '—'}
                      </td>
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

      <section className="card">
        <div className="card-head">
          <h2>Send this night on</h2>
          <span className="hint">for the accountant</span>
        </div>
        <p className="note" style={{ marginTop: 0 }}>
          The figures, the split, who was on and what explains the variance — as plain text, ready to
          paste into an email.
        </p>
        <button type="button" className="btn-primary" onClick={() => void share()}>
          Share this night
        </button>
        {shared && <p className="note" role="status">{shared}</p>}
      </section>

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
