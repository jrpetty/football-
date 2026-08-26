// ---------------------------------------------------------------------------
// The rota.
//
// A week at a time, one row per night, because that is how a pub week is
// actually thought about: "who have I got on Friday?" — not "when is Kelly in?"
// Tapping a night opens it and everybody is one tap from being on it.
//
// Copying last week is given its own button and a lot of room. Pub rotas repeat
// almost entirely from one week to the next; a tool that makes you re-enter the
// same five people every Sunday is a tool that gets abandoned by the third one.
// ---------------------------------------------------------------------------

import { useEffect, useMemo, useRef, useState } from 'react'
import { addDays, formatShort, tradingDayKey, weekdayOf } from '../core/date.ts'
import { formatMoney, parsePence, penceToInput } from '../core/money.ts'
import {
  crewFor,
  formatHours,
  formatTime,
  parseTime,
  proposeShifts,
  shiftFor,
  shiftId,
  shiftMinutes,
  shiftsFrom,
  weekDays,
  weekStart,
  type Person,
  type Shift,
  type ShiftProposal,
} from '../core/rota.ts'
import { scanRota } from '../ocr/scanList.ts'
import { describeZReadError } from '../ocr/scanZRead.ts'
import { seriesVar, StatTile } from '../components/charts.tsx'
import { dayStats } from '../core/analytics.ts'
import { crewRanking, crewStats, type CrewRank } from '../core/rota.ts'
import { listDays } from '../storage/db.ts'
import { loadSettings } from '../storage/settings.ts'
import { formatSigned } from '../core/money.ts'
import { formatShort as formatShortDate } from '../core/date.ts'
import { IconCamera, IconChevronRight, IconTickSmall } from '../components/icons.tsx'
import {
  archivePerson,
  deleteShift,
  listPeople,
  listShifts,
  savePerson,
  saveShift,
} from '../storage/db.ts'

type Panel = 'week' | 'people' | 'record'

/** Six until close, the shift most bar staff are actually on. */
const DEFAULT_START = 18 * 60
const DEFAULT_END = 23 * 60 + 30

let counter = 0
function newId(): string {
  // Not crypto.randomUUID: it is absent over plain HTTP, which is exactly where
  // a preview build gets opened.
  return `${Date.now().toString(36)}-${(counter++).toString(36)}-${Math.floor(Math.random() * 1e6).toString(36)}`
}

export function Rota({ onChanged }: { onChanged: () => void }) {
  const [panel, setPanel] = useState<Panel>('week')
  const [monday, setMonday] = useState(() => weekStart(tradingDayKey()))
  const [openDay, setOpenDay] = useState<string | null>(null)
  const [people, setPeople] = useState<Person[] | null>(null)
  const [shifts, setShifts] = useState<Shift[]>([])
  const [ranking, setRanking] = useState<CrewRank[]>([])
  /** Whose profile is open, if any. */
  const [openPerson, setOpenPerson] = useState<string | null>(null)
  const [toast, setToast] = useState('')
  const [scanning, setScanning] = useState(false)
  const [scanError, setScanError] = useState('')
  const [scanNotes, setScanNotes] = useState('')
  const [proposals, setProposals] = useState<ShiftProposal[] | null>(null)
  const [rejected, setRejected] = useState<Set<number>>(new Set())
  const photoRef = useRef<HTMLInputElement>(null)
  const abortRef = useRef<AbortController | null>(null)

  useEffect(() => () => abortRef.current?.abort(), [])

  // The new-person form.
  const [name, setName] = useState('')
  const [startText, setStartText] = useState(formatTime(DEFAULT_START))
  const [endText, setEndText] = useState(formatTime(DEFAULT_END))
  const [rateText, setRateText] = useState('')

  useEffect(() => {
    void (async () => {
      const [p, s] = await Promise.all([listPeople(), listShifts()])
      setPeople(p)
      setShifts(s)
    })()
  }, [])

  // The record is rebuilt whenever the rota changes, since a shift going on or
  // off a night changes whose night it was.
  useEffect(() => {
    if (people === null) return
    let cancelled = false
    void (async () => {
      const tolerance = loadSettings().tolerancePence
      const saved = await listDays().catch(() => [])
      if (cancelled) return
      const nights = saved
        .map((d) => dayStats(d, tolerance))
        .map((d) => ({ date: d.date, variancePence: d.variancePence, takingsPence: d.takingsPence }))
      setRanking(crewRanking(crewStats(nights, shifts, people, tolerance)))
    })()
    return () => {
      cancelled = true
    }
  }, [people, shifts])

  function say(message: string) {
    setToast(message)
    setTimeout(() => setToast(''), 4000)
  }

  const days = useMemo(() => weekDays(monday), [monday])
  const thisWeek = weekStart(tradingDayKey())
  const active = useMemo(() => (people ?? []).filter((p) => !p.archived), [people])

  const weekShifts = useMemo(
    () => shifts.filter((s) => days.includes(s.date)),
    [shifts, days],
  )
  const weekMinutes = weekShifts.reduce((a, s) => a + shiftMinutes(s), 0)
  const target = loadSettings().weeklyHoursTarget
  /** Positive: still to cover. Negative: rostered past the target. */
  const leftToRoster = target > 0 ? target * 60 - weekMinutes : 0
  const weekCost = useMemo(() => {
    let total = 0
    let priced = false
    const byId = new Map((people ?? []).map((p) => [p.id, p]))
    for (const s of weekShifts) {
      const rate = byId.get(s.personId)?.ratePencePerHour
      if (!rate) continue
      total += Math.round((shiftMinutes(s) * rate) / 60)
      priced = true
    }
    return priced ? total : null
  }, [weekShifts, people])

  async function toggle(date: string, person: Person) {
    const id = shiftId(date, person.id)
    const existing = shifts.find((s) => s.id === id)
    if (existing) {
      await deleteShift(id)
      setShifts(shifts.filter((s) => s.id !== id))
    } else {
      const shift = shiftFor(person, date)
      await saveShift(shift)
      setShifts([...shifts, shift])
    }
    onChanged()
  }

  async function setHours(shift: Shift, patch: Partial<Pick<Shift, 'startMin' | 'endMin'>>) {
    const next = { ...shift, ...patch }
    await saveShift(next)
    setShifts(shifts.map((s) => (s.id === next.id ? next : s)))
    onChanged()
  }

  /** Last week's rota, stamped onto this one. Days already filled are left alone. */
  async function copyLastWeek() {
    const previous = weekDays(addDays(monday, -7))
    const made: Shift[] = []
    for (const [i, from] of previous.entries()) {
      const to = days[i]!
      for (const s of shifts.filter((s) => s.date === from)) {
        const id = shiftId(to, s.personId)
        if (shifts.some((existing) => existing.id === id)) continue
        const copy: Shift = { ...s, id, date: to }
        await saveShift(copy)
        made.push(copy)
      }
    }
    if (made.length === 0) return say('Nothing on last week’s rota to copy.')
    setShifts([...shifts, ...made])
    onChanged()
    say(`Copied ${made.length} ${made.length === 1 ? 'shift' : 'shifts'} from last week.`)
  }

  async function readRota(file: File) {
    abortRef.current?.abort()
    const controller = new AbortController()
    abortRef.current = controller
    setScanning(true)
    setScanError('')
    setScanNotes('')
    try {
      const result = await scanRota(file, controller.signal)
      if (controller.signal.aborted) return
      // Resolved against the week on screen, so a rota photographed in advance
      // lands on the week she is looking at rather than on today.
      const rows = proposeShifts(result.shifts, people ?? [], days, shifts)
      setProposals(rows)
      setRejected(new Set())
      setScanNotes(result.notes)
      if (rows.length === 0) setScanError('No shifts could be read on that photograph.')
    } catch (err) {
      if (controller.signal.aborted) return
      setScanError(describeZReadError(err))
    } finally {
      if (!controller.signal.aborted) setScanning(false)
    }
  }

  function acceptable(rows: ShiftProposal[]): ShiftProposal[] {
    return rows.filter((r, i) => !rejected.has(i) && r.status === 'new')
  }

  async function applyRota() {
    if (!proposals) return
    const taking = shiftsFrom(acceptable(proposals))
    if (taking.length === 0) return say('Nothing new to put on.')
    for (const shift of taking) await saveShift(shift)
    setShifts([...shifts.filter((s) => !taking.some((t) => t.id === s.id)), ...taking])
    setProposals(null)
    onChanged()
    say(`${taking.length} ${taking.length === 1 ? 'shift' : 'shifts'} taken off the photograph.`)
  }

  async function addPerson() {
    const trimmed = name.trim()
    if (!trimmed) return say('They need a name.')
    const start = parseTime(startText)
    const end = parseTime(endText)
    if (start === null || end === null) return say('Those hours are not a time.')
    const rate = parsePence(rateText)
    const person: Person = {
      id: newId(),
      name: trimmed,
      // Fixed at creation, so a person's colour survives everyone else leaving.
      slot: ((people?.length ?? 0) % 8) + 1,
      defaultStartMin: start,
      defaultEndMin: end,
      ...(rate ? { ratePencePerHour: rate } : {}),
    }
    await savePerson(person)
    setPeople([...(people ?? []), person])
    setName('')
    setRateText('')
    onChanged()
    say(`${trimmed} added.`)
  }

  async function archive(person: Person) {
    await archivePerson(person.id)
    setPeople((people ?? []).map((p) => (p.id === person.id ? { ...p, archived: true } : p)))
    onChanged()
    say(`${person.name} taken off the rota. The nights they worked are kept.`)
  }

  async function updatePerson(person: Person, patch: Partial<Person>) {
    const next = { ...person, ...patch }
    await savePerson(next)
    setPeople((people ?? []).map((p) => (p.id === person.id ? next : p)))
    onChanged()
  }

  if (people === null) return <div className="main"><p className="note"><span className="spinner" /> Loading…</p></div>

  return (
    <div className="main">
      <section className="card">
        <div className="card-head">
          <h2>The rota</h2>
          <span className="badge">{active.length} {active.length === 1 ? 'person' : 'people'}</span>
        </div>
        <div className="chip-row">
          {(['week', 'record', 'people'] as Panel[]).map((p) => (
            <button
              key={p}
              type="button"
              className="chip"
              aria-pressed={panel === p}
              onClick={() => { setPanel(p); setOpenPerson(null) }}
            >
              {p === 'week' ? 'The week' : p === 'record' ? 'Records' : 'Who works here'}
            </button>
          ))}
        </div>
      </section>

      {active.length === 0 && (
        <section className="card">
          <p className="note" style={{ marginTop: 0 }}>
            Nobody on the books yet. Add whoever works behind the bar and you can put them on nights —
            and Tally can start telling you how the nights they work compare with the nights they don’t.
          </p>
          <button type="button" className="btn-primary" onClick={() => setPanel('people')}>
            Add the first person
          </button>
        </section>
      )}

      {/* --- the week ------------------------------------------------------- */}
      {panel === 'week' && active.length > 0 && (
        <>
          <section className="card">
            <div className="week-nav">
              <button type="button" className="btn-small" onClick={() => setMonday(addDays(monday, -7))} aria-label="The week before">
                ‹
              </button>
              <span className="week-when">
                <strong>{formatShort(monday).replace(/^\w+, /, '')} – {formatShort(days[6]!).replace(/^\w+, /, '')}</strong>
                <small>{monday === thisWeek ? 'this week' : monday > thisWeek ? 'coming up' : 'been and gone'}</small>
              </span>
              <button type="button" className="btn-small" onClick={() => setMonday(addDays(monday, 7))} aria-label="The week after">
                ›
              </button>
            </div>
            <div className="alts">
              <button type="button" className="btn-small" onClick={() => void copyLastWeek()}>
                Copy last week
              </button>
              <button type="button" className="btn-small" onClick={() => photoRef.current?.click()} disabled={scanning}>
                {scanning ? <><span className="spinner" /> Reading…</> : <><IconCamera size={17} /> Photograph the rota</>}
              </button>
              {monday !== thisWeek && (
                <button type="button" className="btn-small" onClick={() => setMonday(thisWeek)}>
                  Back to this week
                </button>
              )}
            </div>
            <input
              ref={photoRef}
              type="file"
              accept="image/*"
              className="visually-hidden"
              data-testid="file-rota"
              onChange={(e) => {
                const file = e.target.files?.[0]
                e.target.value = ''
                if (file) void readRota(file)
              }}
            />
            {scanError && <p className="note bad" role="status">{scanError}</p>}
            {scanNotes && !scanError && <p className="note warn" role="status">{scanNotes}</p>}
          </section>

          {/* --- what the paper said, before anything is written --------------- */}
          {proposals && (
            <section className="card">
              <div className="card-head">
                <h2>What the paper says</h2>
                <span className="badge">{acceptable(proposals).length} to put on</span>
              </div>
              <p className="note" style={{ marginTop: 0 }}>
                Nothing is on the rota yet. Anyone it could not recognise, and any day it could not
                place, is left for you — it will not guess which night someone is working.
              </p>
              {proposals.map((row, i) => {
                const off = rejected.has(i)
                return (
                  <div className="zrow" key={`${row.written}-${row.writtenDay}-${i}`}>
                    <span className="zname">
                      {row.personName ?? row.written}
                      <small>
                        {row.status === 'unknown-person' && `“${row.written}” — nobody on the books by that name`}
                        {row.status === 'ambiguous' && `“${row.written}” — could be ${row.between?.join(' or ')}`}
                        {row.status === 'unknown-day' && `could not read the day “${row.writtenDay}”`}
                        {(row.status === 'new' || row.status === 'already') && row.date && (
                          <>
                            {weekdayOf(row.date)}
                            {row.startMin !== undefined && row.endMin !== undefined
                              ? ` ${formatTime(row.startMin)}–${formatTime(row.endMin)}`
                              : ''}
                            {row.timesFrom === 'usual' ? ' · their usual hours' : ''}
                          </>
                        )}
                      </small>
                    </span>
                    {row.status === 'new' ? (
                      <button
                        type="button"
                        className="chip"
                        aria-pressed={!off}
                        aria-label={`${off ? 'Include' : 'Skip'} ${row.personName ?? row.written}`}
                        onClick={() =>
                          setRejected((r) => {
                            const next = new Set(r)
                            if (next.has(i)) next.delete(i)
                            else next.add(i)
                            return next
                          })
                        }
                      >
                        {off ? 'Skipped' : <IconTickSmall size={13} />}
                      </button>
                    ) : (
                      <span className={`badge ${row.status === 'already' ? 'good' : 'warn'}`}>
                        {row.status === 'already' ? 'already on' : 'by hand'}
                      </span>
                    )}
                  </div>
                )
              })}
              <div className="btn-row" style={{ marginTop: 14 }}>
                <button type="button" className="btn-primary" onClick={() => void applyRota()}>
                  Put {acceptable(proposals).length} on
                </button>
                <button type="button" className="btn-small" onClick={() => setProposals(null)}>
                  Throw it away
                </button>
              </div>
            </section>
          )}

          {days.map((date) => {
            const crew = crewFor(date, shifts, people)
            const isOpen = openDay === date
            const today = date === tradingDayKey()
            return (
              <section className={`card day-card${today ? ' today' : ''}`} key={date}>
                <button
                  type="button"
                  className="day-open"
                  aria-expanded={isOpen}
                  onClick={() => setOpenDay(isOpen ? null : date)}
                >
                  <span className="day-when">
                    <strong>{weekdayOf(date)}</strong>
                    <small>{formatShort(date).replace(/^\w+, /, '')}{today ? ' · tonight' : ''}</small>
                  </span>
                  <span className="day-crew">
                    {crew.shifts.length === 0 ? (
                      <span className="day-nobody">Nobody on</span>
                    ) : (
                      crew.shifts.map((s) => {
                        const person = people.find((p) => p.id === s.personId)
                        return (
                          <span className="who" key={s.id}>
                            <span
                              className="legend-dot"
                              style={{ background: seriesVar(person?.slot ?? 1) }}
                              aria-hidden="true"
                            />
                            {person?.name ?? 'Someone'}
                          </span>
                        )
                      })
                    )}
                  </span>
                  <span className="day-hours num">
                    {crew.minutes > 0 ? formatHours(crew.minutes) : ''}
                  </span>
                  <span className={`chev${isOpen ? ' open' : ''}`} aria-hidden="true">
                    <IconChevronRight size={16} />
                  </span>
                </button>

                {isOpen && (
                  <div className="day-edit">
                    <div className="chip-row">
                      {active.map((person) => {
                        const on = shifts.some((s) => s.id === shiftId(date, person.id))
                        return (
                          <button
                            key={person.id}
                            type="button"
                            className="chip"
                            aria-pressed={on}
                            onClick={() => void toggle(date, person)}
                          >
                            {on ? (
                              <IconTickSmall size={13} />
                            ) : (
                              <span className="legend-dot" style={{ background: seriesVar(person.slot) }} aria-hidden="true" />
                            )}
                            {person.name}
                          </button>
                        )
                      })}
                    </div>

                    {crew.shifts.map((s) => {
                      const person = people.find((p) => p.id === s.personId)
                      return (
                        <div className="zrow" key={s.id}>
                          <span className="zname">
                            {person?.name ?? 'Someone'}
                            <small>{formatHours(shiftMinutes(s))}</small>
                          </span>
                          <input
                            type="time"
                            aria-label={`${person?.name ?? 'Someone'} starts on ${date}`}
                            value={formatTime(s.startMin)}
                            onChange={(e) => {
                              const min = parseTime(e.target.value)
                              if (min !== null) void setHours(s, { startMin: min })
                            }}
                          />
                          <input
                            type="time"
                            aria-label={`${person?.name ?? 'Someone'} finishes on ${date}`}
                            value={formatTime(s.endMin)}
                            onChange={(e) => {
                              const min = parseTime(e.target.value)
                              if (min !== null) void setHours(s, { endMin: min })
                            }}
                          />
                        </div>
                      )
                    })}
                    {crew.costPence !== null && (
                      <p className="note" style={{ marginBottom: 0 }}>
                        {formatHours(crew.minutes)} on the bar, {formatMoney(crew.costPence)} in wages.
                      </p>
                    )}
                  </div>
                )}
              </section>
            )
          })}

          <section className="card">
            <div className="card-head">
              <h2>The week</h2>
              {target > 0 && (
                <span className={`badge ${leftToRoster === 0 ? 'good' : leftToRoster < 0 ? 'bad' : 'warn'}`}>
                  {leftToRoster === 0
                    ? 'target met'
                    : leftToRoster > 0
                      ? `${formatHours(leftToRoster)} to go`
                      : `${formatHours(-leftToRoster)} over`}
                </span>
              )}
            </div>
            <div className="zrow">
              <span className="zname">
                Hours rostered
                <small>{target > 0 ? `of a ${target} hour week` : 'everyone, all seven nights'}</small>
              </span>
              <strong className="num">
                {formatHours(weekMinutes)}
                {target > 0 && <span className="hint"> / {target}h</span>}
              </strong>
            </div>
            {target > 0 && (
              // A plain bar rather than a number alone: the point of a target is
              // seeing at a glance how much of the week is still to cover.
              <div
                className="meter"
                role="img"
                aria-label={`${formatHours(weekMinutes)} rostered of a ${target} hour target`}
              >
                <span
                  className={`meter-fill${weekMinutes > target * 60 ? ' over' : ''}`}
                  style={{ width: `${Math.min(100, Math.round((weekMinutes / (target * 60)) * 100))}%` }}
                />
              </div>
            )}
            {weekCost !== null && (
              <div className="zrow">
                <span className="zname">Wages<small>at the rates set on each person</small></span>
                <strong className="num">{formatMoney(weekCost)}</strong>
              </div>
            )}
            <p className="note">
              {weekCost === null
                ? 'Add an hourly rate to anyone and the wage bill for the week appears here — and labour as a share of takings appears on Trade.'
                : 'What this week costs before anyone has pulled a pint. Trade shows it against what the pub actually took.'}
            </p>
          </section>
        </>
      )}

      {/* --- how everybody is doing ------------------------------------------ */}
      {panel === 'record' && active.length > 0 && (() => {
        const open = openPerson ? ranking.find((r) => r.stat.personId === openPerson) : null
        if (open) {
          const { stat } = open
          const judged = stat.balancedNights + stat.shortNights + stat.overNights
          return (
            <>
              <section className="card">
                <div className="card-head">
                  <span
                    className="legend-dot"
                    style={{ background: seriesVar(stat.slot), width: 12, height: 12, marginRight: 10 }}
                    aria-hidden="true"
                  />
                  <h2>{stat.name}</h2>
                  <span className="badge">{open.place ? `#${open.place} on record` : 'not enough nights'}</span>
                </div>
                <div className="kpi-row">
                  <StatTile label="Nights worked" value={String(stat.nightsOn)} detail={formatHours(stat.minutes)} />
                  <StatTile
                    label="Nights balanced"
                    value={judged === 0 ? '—' : `${stat.balancedNights}/${judged}`}
                    detail={open.balancedBp === null ? 'none counted yet' : `${(open.balancedBp / 100).toFixed(0)}% of their nights`}
                    // Coloured only once there are enough nights to rank on.
                    // Painting one bad night red while the badge says "not
                    // enough nights" is the app contradicting itself.
                    tone={open.place === null ? undefined : (open.balancedBp ?? 0) >= 8000 ? 'good' : (open.balancedBp ?? 0) < 5000 ? 'bad' : undefined}
                  />
                  <StatTile
                    label="Avg take"
                    value={stat.avgTakingsOnPence === null ? '—' : formatMoney(stat.avgTakingsOnPence)}
                    detail="on their nights"
                  />
                  {stat.costPence !== null && (
                    <StatTile label="Wages" value={formatMoney(stat.costPence)} detail="over these nights" />
                  )}
                </div>
              </section>

              <section className="card">
                <div className="card-head"><h2>The drawer on their nights</h2></div>
                <div className="zrow">
                  <span className="zname">Their nights<small>{stat.comparedOn} counted</small></span>
                  <strong className={`num delta ${stat.avgVarianceOnPence !== null && stat.avgVarianceOnPence < 0 ? 'short' : ''}`}>
                    {stat.avgVarianceOnPence === null ? '—' : formatSigned(stat.avgVarianceOnPence)}
                  </strong>
                </div>
                <div className="zrow">
                  <span className="zname">Everyone else’s<small>{stat.comparedOff} counted</small></span>
                  <strong className="num">
                    {stat.avgVarianceOffPence === null ? '—' : formatSigned(stat.avgVarianceOffPence)}
                  </strong>
                </div>
                <div className="zrow">
                  <span className="zname">Difference<small>their nights against the rest</small></span>
                  <strong className="num">
                    {stat.meaningful && stat.differencePence !== null ? formatSigned(stat.differencePence) : 'too soon to say'}
                  </strong>
                </div>
                {stat.shortNights + stat.overNights > 0 && (
                  <div className="zrow">
                    <span className="zname">
                      Went out
                      <small>
                        {stat.shortNights} short, {stat.overNights} over
                        {stat.worstNightDate ? ` · worst ${formatShortDate(stat.worstNightDate)}` : ''}
                      </small>
                    </span>
                    <span className="num delta short">
                      {stat.worstNightPence === null ? '—' : formatSigned(stat.worstNightPence)}
                    </span>
                  </div>
                )}
                <p className="note">
                  Two or three people work most nights, so none of this is {stat.name}’s doing on its
                  own — every figure here belongs to the whole night, and the same numbers appear on
                  everyone else who was on. It is worth knowing and it is not evidence.
                </p>
              </section>

              <button type="button" className="btn-small" onClick={() => setOpenPerson(null)}>
                Back to everyone
              </button>
            </>
          )
        }

        return (
          <section className="card">
            <div className="card-head">
              <h2>Who runs the tightest till</h2>
              <span className="hint">by nights that balanced</span>
            </div>
            {ranking.length === 0 || ranking.every((r) => r.balancedBp === null) ? (
              <p className="note" style={{ marginTop: 0 }}>
                Nothing to compare yet. Put people on nights, save those nights, and a record builds
                itself from here.
              </p>
            ) : (
              ranking.map((r) => (
                <button
                  type="button"
                  className="zrow person-row"
                  key={r.stat.personId}
                  onClick={() => setOpenPerson(r.stat.personId)}
                >
                  <span className="zname">
                    <span
                      className="legend-dot"
                      style={{ background: seriesVar(r.stat.slot), marginRight: 8 }}
                      aria-hidden="true"
                    />
                    {r.stat.name}
                    <small>
                      {r.stat.nightsOn} {r.stat.nightsOn === 1 ? 'night' : 'nights'}
                      {r.balancedBp === null
                        ? ' · none counted yet'
                        : ` · ${r.stat.balancedNights} of ${r.stat.balancedNights + r.stat.shortNights + r.stat.overNights} balanced`}
                    </small>
                  </span>
                  <span className="num">
                    {r.balancedBp === null ? '—' : `${(r.balancedBp / 100).toFixed(0)}%`}
                  </span>
                  <span className="badge">{r.place ? `#${r.place}` : 'too soon'}</span>
                  <span className="chev" aria-hidden="true"><IconChevronRight size={16} /></span>
                </button>
              ))
            )}
            <p className="note">
              Ranked on how often a night balanced rather than by how much it was out, because one
              freak night says less than twenty small ones — and a rate compares someone who works
              twice a week with someone who works five times. Nobody is ranked until there are five
              countable nights behind them.
            </p>
          </section>
        )
      })()}

      {/* --- who works here -------------------------------------------------- */}
      {panel === 'people' && (
        <>
          <section className="card">
            <div className="card-head"><h2>Add someone</h2></div>
            <div className="field">
              <label htmlFor="person-name">Name</label>
              <input
                id="person-name"
                type="text"
                value={name}
                placeholder="Kelly"
                autoComplete="off"
                onChange={(e) => setName(e.target.value)}
              />
            </div>
            <div className="btn-row">
              <div className="field" style={{ marginBottom: 0 }}>
                <label htmlFor="person-start">Usually starts</label>
                <input id="person-start" type="time" value={startText} onChange={(e) => setStartText(e.target.value)} />
              </div>
              <div className="field" style={{ marginBottom: 0 }}>
                <label htmlFor="person-end">Usually finishes</label>
                <input id="person-end" type="time" value={endText} onChange={(e) => setEndText(e.target.value)} />
              </div>
            </div>
            <div className="field" style={{ marginTop: 14 }}>
              <label htmlFor="person-rate">Hourly rate (optional)</label>
              <input
                id="person-rate"
                inputMode="decimal"
                value={rateText}
                placeholder="12.21"
                autoComplete="off"
                onChange={(e) => setRateText(e.target.value)}
              />
              <p className="help">
                Only used to work out what a night costs in wages. Leave it blank and Tally simply
                won’t mention money — it never guesses a rate.
              </p>
            </div>
            <button type="button" className="btn-primary" onClick={() => void addPerson()}>
              Add to the rota
            </button>
          </section>

          {active.map((person) => (
            <section className="card" key={person.id}>
              <div className="card-head">
                <span
                  className="legend-dot"
                  style={{ background: seriesVar(person.slot), width: 12, height: 12, marginRight: 10 }}
                  aria-hidden="true"
                />
                <h2>{person.name}</h2>
                <span className="hint">
                  {formatTime(person.defaultStartMin)}–{formatTime(person.defaultEndMin)}
                  {person.ratePencePerHour ? ` · ${formatMoney(person.ratePencePerHour)}/hr` : ''}
                </span>
              </div>
              <div className="zrow">
                <span className="zname">Usual hours<small>what a tap puts them on for</small></span>
                <input
                  type="time"
                  aria-label={`${person.name} usually starts`}
                  value={formatTime(person.defaultStartMin)}
                  onChange={(e) => {
                    const min = parseTime(e.target.value)
                    if (min !== null) void updatePerson(person, { defaultStartMin: min })
                  }}
                />
                <input
                  type="time"
                  aria-label={`${person.name} usually finishes`}
                  value={formatTime(person.defaultEndMin)}
                  onChange={(e) => {
                    const min = parseTime(e.target.value)
                    if (min !== null) void updatePerson(person, { defaultEndMin: min })
                  }}
                />
              </div>
              <div className="zrow">
                <span className="zname">Hourly rate<small>blank means no wage figures</small></span>
                <input
                  inputMode="decimal"
                  aria-label={`${person.name} hourly rate`}
                  defaultValue={person.ratePencePerHour ? penceToInput(person.ratePencePerHour) : ''}
                  placeholder="—"
                  onChange={(e) => {
                    const pence = parsePence(e.target.value)
                    void updatePerson(person, pence ? { ratePencePerHour: pence } : { ratePencePerHour: undefined })
                  }}
                />
              </div>
              <div className="alts">
                <button type="button" className="btn-small btn-danger" onClick={() => void archive(person)}>
                  They’ve left
                </button>
              </div>
            </section>
          ))}

          {(people ?? []).some((p) => p.archived) && (
            <section className="card">
              <div className="card-head"><h2>No longer here</h2></div>
              <p className="note" style={{ marginTop: 0 }}>
                {(people ?? []).filter((p) => p.archived).map((p) => p.name).join(', ')} — kept so the
                nights they worked still read properly.
              </p>
            </section>
          )}
        </>
      )}

      {toast && <div className="toast" role="status">{toast}</div>}
    </div>
  )
}
