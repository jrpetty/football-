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

import { useEffect, useMemo, useState } from 'react'
import { addDays, formatShort, tradingDayKey, weekdayOf } from '../core/date.ts'
import { formatMoney, parsePence, penceToInput } from '../core/money.ts'
import {
  crewFor,
  formatHours,
  formatTime,
  parseTime,
  shiftFor,
  shiftId,
  shiftMinutes,
  weekDays,
  weekStart,
  type Person,
  type Shift,
} from '../core/rota.ts'
import { seriesVar } from '../components/charts.tsx'
import { IconChevronRight, IconTickSmall } from '../components/icons.tsx'
import {
  archivePerson,
  deleteShift,
  listPeople,
  listShifts,
  savePerson,
  saveShift,
} from '../storage/db.ts'

type Panel = 'week' | 'people'

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
  const [toast, setToast] = useState('')

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
          {(['week', 'people'] as Panel[]).map((p) => (
            <button key={p} type="button" className="chip" aria-pressed={panel === p} onClick={() => setPanel(p)}>
              {p === 'week' ? 'The week' : 'Who works here'}
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
              {monday !== thisWeek && (
                <button type="button" className="btn-small" onClick={() => setMonday(thisWeek)}>
                  Back to this week
                </button>
              )}
            </div>
          </section>

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
            <div className="card-head"><h2>The week</h2></div>
            <div className="zrow">
              <span className="zname">Hours rostered<small>everyone, all seven nights</small></span>
              <strong className="num">{formatHours(weekMinutes)}</strong>
            </div>
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
