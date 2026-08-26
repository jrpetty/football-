// ---------------------------------------------------------------------------
// Settings, and the backup story.
//
// The export is not a nice-to-have. The brief rules out a sync service, which
// is right for one pub — but "no cloud" must not mean "one dropped phone and
// the year is gone", so there is a file she can mail to herself, and nothing
// here is trapped inside the app.
// ---------------------------------------------------------------------------

import { useEffect, useRef, useState } from 'react'
import { formatMoney, parsePence, penceToInput } from '../core/money.ts'
import {
  listDays,
  listDeliveries,
  listPeople,
  listShifts,
  loadStockConfig,
  estimateUsage,
  loadDigest,
  prunePhotosBefore,
  requestPersistence,
  collectBackup,
  restoreBackup,
} from '../storage/db.ts'
import { dayStats } from '../core/analytics.ts'
import { addDays, tradingDayKey } from '../core/date.ts'
import { cellarValue, costOf } from '../core/margin.ts'
import { monthlyCsv, monthlyTakings, yearEndPack } from '../core/yearEnd.ts'
import { describeRestored, downloadFile, parseBackup, toCsv, toJson } from '../storage/export.ts'
import { testApiKey, type KeyCheck } from '../ocr/scanZRead.ts'
import { describeWeatherError, findPlace, type Place } from '../weather/openMeteo.ts'
import { IconReceipt } from '../components/icons.tsx'
import {
  AI_MODELS,
  loadSettings,
  restoreSettings,
  saveSettings,
  settingsForBackup,
  type EnginePreference,
  type Settings as SettingsShape,
} from '../storage/settings.ts'

const ENGINE_HELP: Record<EnginePreference, string> = {
  vision:
    'Claude reads the photograph. Much the better reader on faded or curled receipt paper, because it understands which line is the total rather than only seeing shapes. Needs a signal and an API key. A full roll is three photographs, so reckon on a few pence a night — a pound or two a month.',
  device:
    'The phone reads the photograph itself. Free, private and works with no signal, but noticeably worse on thermal receipts — expect to correct it more often.',
  off:
    'No scanning at all. Photograph nothing and type the three figures in. Still far quicker than the paper ledger, and completely reliable.',
}

export function Settings({ onChanged, onOpenPrices }: { onChanged: () => void; onOpenPrices: () => void }) {
  const [s, setS] = useState<SettingsShape>(loadSettings)
  const [toleranceText, setToleranceText] = useState(() => penceToInput(loadSettings().tolerancePence))
  const [vatText, setVatText] = useState(() => String(loadSettings().vatBp / 100))
  const [hoursText, setHoursText] = useState(() => {
    const h = loadSettings().weeklyHoursTarget
    return h > 0 ? String(h) : ''
  })
  const [usage, setUsage] = useState<{ usedBytes: number; quotaBytes: number } | null>(null)
  const [toast, setToast] = useState('')
  const [showKey, setShowKey] = useState(false)
  // The box holds its own text so "Save" means something. Saving on every
  // keystroke, as this did before, is indistinguishable from not saving at all.
  const [keyText, setKeyText] = useState(() => loadSettings().apiKey)
  const [keyState, setKeyState] = useState<KeyCheck | null>(null)
  const [checking, setChecking] = useState(false)
  const [restoring, setRestoring] = useState(false)
  const [dragging, setDragging] = useState(false)
  const [floatTextSetting, setFloatTextSetting] = useState(() => {
    const f = loadSettings().standingFloatPence
    return f > 0 ? penceToInput(f) : ''
  })
  const [nudge, setNudge] = useState<NotificationPermission | 'unsupported'>(() =>
    typeof Notification === 'undefined' ? 'unsupported' : Notification.permission,
  )
  const [placeQuery, setPlaceQuery] = useState('')
  const [places, setPlaces] = useState<Place[] | null>(null)
  const [findingPlace, setFindingPlace] = useState(false)
  const importRef = useRef<HTMLInputElement>(null)

  useEffect(() => {
    void estimateUsage().then(setUsage)
  }, [toast])

  function update(next: Partial<SettingsShape>) {
    const merged = { ...s, ...next }
    setS(merged)
    saveSettings(merged)
    onChanged()
  }

  function say(message: string) {
    setToast(message)
    setTimeout(() => setToast(''), 5000)
  }

  /**
   * Ask for the weekly nudge.
   *
   * Two separate permissions, and only the first is universal: showing a
   * notification at all, and being woken up on a schedule to show one. The
   * second is registered where it exists and quietly skipped where it does not,
   * because a browser that cannot do it is not a failure to report — it is
   * simply an app that speaks when opened, as it did before.
   */
  async function askForNudge() {
    if (typeof Notification === 'undefined') return say('This browser does not do notifications.')
    const permission = await Notification.requestPermission()
    setNudge(permission)
    if (permission !== 'granted') return say('Left switched off.')

    try {
      const registration = await navigator.serviceWorker.ready
      const periodic = (registration as unknown as {
        periodicSync?: { register: (tag: string, options: { minInterval: number }) => Promise<void> }
      }).periodicSync
      if (periodic) {
        await periodic.register('tally-weekly', { minInterval: 7 * 24 * 60 * 60 * 1000 })
        say('On. You will get one a week.')
      } else {
        say('On — though this browser will only show it while Tally is open.')
      }
    } catch {
      say('On — though this browser would not agree to a weekly schedule.')
    }
  }

  /** What this week's would say, so it is not a mystery what was signed up for. */
  async function showNudgeNow() {
    const digest = await loadDigest().catch(() => null)
    const body = digest?.summary ?? 'Nothing worked out yet — open Trade once and it will fill in.'
    try {
      const registration = await navigator.serviceWorker.ready
      await registration.showNotification('Tally', { body, icon: './icon-192.png', tag: 'tally-weekly' })
    } catch {
      say(body)
    }
  }

  /**
   * The year's pack, assembled.
   *
   * Everything in it exists elsewhere in the app; what has never existed is the
   * putting together, which is the part that goes wrong when it is done by hand
   * the night before a deadline.
   */
  async function exportYearEnd() {
    const to = tradingDayKey()
    const from = addDays(to, -364)
    const tolerance = s.tolerancePence

    const [days, shifts, people, stock, deliveries] = await Promise.all([
      listDays(),
      listShifts(),
      listPeople(),
      loadStockConfig(),
      listDeliveries(),
    ])
    if (days.length === 0) return say('No nights recorded yet.')

    const stats = days.map((d) => dayStats(d, tolerance))
    // Valued at what is on hand now, which is what a year end asks for.
    const onHand = new Map<string, number>()
    for (const delivery of deliveries) {
      for (const line of delivery.lines) {
        onHand.set(line.stockItemId, (onHand.get(line.stockItemId) ?? 0) + line.baseUnits)
      }
    }
    const cellar = stock.items.length
      ? cellarValue(
          stock.items.map((item) => ({
            item,
            countedBaseUnits: 0,
            deliveredBaseUnits: 0,
            pouredBaseUnits: 0,
            expectedBaseUnits: onHand.get(item.id) ?? 0,
          })),
        )
      : null

    // What was bought in over the year, at the costs entered — the input side.
    let purchasesPence: number | null = null
    for (const delivery of deliveries) {
      if (delivery.date < from || delivery.date > to) continue
      for (const line of delivery.lines) {
        const item = stock.items.find((i) => i.id === line.stockItemId)
        const cost = item ? costOf(item, line.baseUnits) : null
        if (cost !== null) purchasesPence = (purchasesPence ?? 0) + cost
      }
    }

    const text = yearEndPack({
      from,
      to,
      days: stats,
      shifts,
      people,
      cellar,
      vatBp: s.vatBp,
      purchasesPence,
    })

    downloadFile(`year-end-${to}.txt`, text, 'text/plain')
    downloadFile(`takings-by-month-${to}.csv`, monthlyCsv(monthlyTakings(stats.filter((d) => d.date >= from && d.date <= to), s.vatBp)), 'text/csv')
    say('Year-end pack saved — the summary and the monthly figures.')
  }

  async function exportCsv() {
    const days = await listDays()
    if (days.length === 0) return say('Nothing to export yet.')
    downloadFile(`tally-${new Date().toISOString().slice(0, 10)}.csv`, toCsv(days, s.tolerancePence), 'text/csv')
    say(`Exported ${days.length} nights.`)
  }

  /**
   * Everything, in one file.
   *
   * The nights are the least of it — the price list, the cellar with every
   * barrel cost, the rota and everyone on it all live here too, and an earlier
   * version of this saved only the nights, which meant moving to a new copy of
   * the app quietly threw most of the work away.
   */
  async function exportBackup() {
    const bundle = await collectBackup()
    if (bundle.days.length === 0 && bundle.stock.items.length === 0 && bundle.people.length === 0) {
      return say('Nothing to back up yet.')
    }
    downloadFile(
      `tally-${new Date().toISOString().slice(0, 10)}.tally.json`,
      toJson({ ...bundle, settings: settingsForBackup() }),
      'application/json',
    )
    say('Saved. That one file is the whole app — keep it somewhere safe.')
  }

  async function importBackup(file: File) {
    setRestoring(true)
    try {
      const restored = parseBackup(await file.text())
      await restoreBackup(restored)
      restoreSettings(restored.settings)
      setS(loadSettings())
      onChanged()
      say(
        restored.nightsOnly
          ? `${describeRestored(restored)} That file was made by an older version, so it only had the nights in it.`
          : describeRestored(restored),
      )
    } catch (err) {
      say(err instanceof Error ? err.message : 'That file could not be read.')
    } finally {
      setRestoring(false)
    }
  }

  async function prune() {
    const cutoff = Date.now() - 90 * 24 * 60 * 60 * 1000
    const removed = await prunePhotosBefore(cutoff)
    say(removed === 0 ? 'No photographs older than 90 days.' : `Removed ${removed} old photographs. The figures are untouched.`)
  }

  const megabytes = usage ? (usage.usedBytes / 1_048_576).toFixed(1) : null

  return (
    <div className="main">
      <section className="card">
        <div className="card-head"><h2>Reading the receipts</h2></div>

        <div className="field">
          <label htmlFor="engine">How the photographs are read</label>
          <select id="engine" value={s.engine} onChange={(e) => update({ engine: e.target.value as EnginePreference })}>
            <option value="vision">Claude reads it (best on receipt paper)</option>
            <option value="device">The phone reads it (works offline)</option>
            <option value="off">Don't scan — I'll type the figures</option>
          </select>
          <p className="help">{ENGINE_HELP[s.engine]}</p>
        </div>

        {/* Always shown. The scan failure tells her to "add a key in Settings",
            and hiding the box behind a setting she has not chosen yet makes
            that instruction impossible to follow. */}
        {
          <>
            {s.engine !== 'vision' && (
              <p className="note warn">
                Claude is not reading the photographs at the moment, so this key will sit unused.{' '}
                <button
                  type="button"
                  className="btn-small"
                  onClick={() => update({ engine: 'vision' })}
                >
                  Switch Claude on
                </button>
              </p>
            )}
            <div className="field">
              <label htmlFor="apiKey">Anthropic API key</label>
              <input
                id="apiKey"
                type={showKey ? 'text' : 'password'}
                value={keyText}
                autoComplete="off"
                spellCheck={false}
                placeholder="sk-ant-…"
                onChange={(e) => setKeyText(e.target.value)}
              />
              <div className="btn-row" style={{ marginTop: 4 }}>
                <button
                  type="button"
                  className="btn-primary"
                  disabled={keyText.trim() === s.apiKey.trim()}
                  onClick={() => {
                    update({ apiKey: keyText })
                    setKeyState(null)
                    say(keyText.trim() ? 'Key saved.' : 'Key removed.')
                  }}
                >
                  {keyText.trim() === s.apiKey.trim() ? 'Saved' : 'Save key'}
                </button>
                <button
                  type="button"
                  disabled={checking || !s.apiKey.trim()}
                  onClick={() => {
                    setChecking(true)
                    setKeyState(null)
                    void testApiKey()
                      .then(setKeyState)
                      .finally(() => setChecking(false))
                  }}
                >
                  {checking ? 'Checking…' : 'Test it'}
                </button>
              </div>
              <div className="alts">
                <button type="button" className="btn-small" onClick={() => setShowKey((v) => !v)}>
                  {showKey ? 'Hide key' : 'Show key'}
                </button>
                {s.apiKey.trim() ? (
                  <span className="badge good">Key saved</span>
                ) : (
                  <span className="badge warn">No key yet</span>
                )}
              </div>

              {/* The answer to "is it the key, the model, or this browser?" */}
              {keyState && (
                <p className={`note ${keyState.ok ? '' : keyState.blocked ? 'warn' : 'bad'}`} role="status">
                  <span className={`badge ${keyState.ok ? 'good' : keyState.blocked ? 'warn' : 'bad'}`}>
                    {keyState.ok ? 'Works' : keyState.blocked ? 'Blocked here' : 'Not working'}
                  </span>{' '}
                  {keyState.message}
                </p>
              )}

              <p className="help">
                Get one at console.anthropic.com — Billing first, then API keys. It is kept in this browser
                and sent straight to Anthropic; there is no server in between.
              </p>
            </div>

            <div className="field">
              <label htmlFor="model">Model</label>
              <select
                id="model"
                value={s.model}
                onChange={(e) => {
                  update({ model: e.target.value })
                  setKeyState(null)
                }}
              >
                {AI_MODELS.map((m) => (
                  <option key={m.id} value={m.id}>{m.label} — {m.hint}</option>
                ))}
              </select>
            </div>
          </>
        }
      </section>

      <section className="card">
        <div className="card-head"><h2>What things should cost</h2></div>
        <p className="help" style={{ marginTop: 0 }}>
          Set the board price for each line and the app can check what the till <em>should</em> have
          taken, not only whether the drawer matched what it did. A till told the wrong price balances
          perfectly every night.
        </p>
        <button type="button" className="btn-primary" onClick={onOpenPrices}>
          Open the price list
        </button>
      </section>

      <section className="card">
        <div className="card-head"><h2>What counts as balanced</h2></div>
        <div className="field" style={{ marginBottom: 0 }}>
          <label htmlFor="tolerance">Allow up to</label>
          <input
            id="tolerance"
            inputMode="decimal"
            value={toleranceText}
            onChange={(e) => {
              setToleranceText(e.target.value)
              const pence = parsePence(e.target.value)
              if (pence !== null && pence >= 0) update({ tolerancePence: pence })
            }}
          />
          <p className="help">
            A night out by less than {formatMoney(s.tolerancePence)} is called balanced. Set it to £0.00 to be
            told about every penny — but a till that has taken four hundred cash transactions is routinely a
            few pence out, and an app that cried wolf nightly would stop being read.
          </p>
        </div>
      </section>

      <section className="card">
        <div className="card-head"><h2>The trade</h2></div>
        <div className="field">
          <label htmlFor="vat">VAT rate</label>
          <input
            id="vat"
            inputMode="decimal"
            value={vatText}
            onChange={(e) => {
              setVatText(e.target.value)
              const percent = Number(e.target.value)
              if (Number.isFinite(percent) && percent >= 0 && percent <= 100) update({ vatBp: Math.round(percent * 100) })
            }}
          />
          <p className="help">
            Taken off the selling price before gross profit is worked out, because that part of it is
            never yours. At {(s.vatBp / 100).toFixed(1)}% a £4.00 pint is {formatMoney(Math.round((400 * 10000) / (10000 + s.vatBp)))} to the pub.
          </p>
        </div>
        <div className="field">
          <label htmlFor="standing-float">Float left in the drawer</label>
          <input
            id="standing-float"
            inputMode="decimal"
            placeholder="none"
            value={floatTextSetting}
            onChange={(e) => {
              setFloatTextSetting(e.target.value)
              if (e.target.value.trim() === '') update({ standingFloatPence: 0 })
              else {
                const pence = parsePence(e.target.value)
                if (pence !== null && pence >= 0) update({ standingFloatPence: pence })
              }
            }}
          />
          <p className="help">
            {s.standingFloatPence > 0
              ? `Filled in on every night, so the ${formatMoney(s.standingFloatPence)} left for change is taken off the drawer before anything is compared with the till. Change it on the night if it was different.`
              : 'If change is left in the drawer overnight, put the usual amount here and it will be filled in each night. Without it a float reads as the pub being over by exactly that much, every single night.'}
          </p>
        </div>

        <div className="field">
          <label htmlFor="place">Where the pub is</label>
          {s.place.name ? (
            <div className="zrow" style={{ borderTop: 0, paddingTop: 0 }}>
              <span className="zname">
                {s.place.name}
                <small>{s.place.latitude.toFixed(2)}, {s.place.longitude.toFixed(2)}</small>
              </span>
              <button
                type="button"
                className="btn-small"
                onClick={() => { update({ place: { name: '', latitude: 0, longitude: 0 } }); setPlaces(null) }}
              >
                Change
              </button>
            </div>
          ) : (
            <>
              <input
                id="place"
                type="text"
                placeholder="Tadcaster"
                autoComplete="off"
                value={placeQuery}
                onChange={(e) => setPlaceQuery(e.target.value)}
              />
              <div className="btn-row">
                <button
                  type="button"
                  className="btn-small"
                  disabled={findingPlace || placeQuery.trim().length < 2}
                  onClick={() => {
                    setFindingPlace(true)
                    void findPlace(placeQuery.trim())
                      .then((found) => {
                        setPlaces(found)
                        if (found.length === 0) say('Nothing found by that name.')
                      })
                      .catch((err: unknown) => say(describeWeatherError(err)))
                      .finally(() => setFindingPlace(false))
                  }}
                >
                  {findingPlace ? <><span className="spinner" /> Looking…</> : 'Find the town'}
                </button>
                <button
                  type="button"
                  className="btn-small"
                  onClick={() => {
                    if (!navigator.geolocation) return say('This phone will not give a location.')
                    navigator.geolocation.getCurrentPosition(
                      (pos) => {
                        update({
                          place: {
                            name: 'Here',
                            latitude: Math.round(pos.coords.latitude * 1000) / 1000,
                            longitude: Math.round(pos.coords.longitude * 1000) / 1000,
                          },
                        })
                        say('Location set from the phone.')
                      },
                      () => say('The phone would not share a location.'),
                    )
                  }}
                >
                  Use where I am
                </button>
              </div>
              {places && places.length > 0 && (
                <div className="alts">
                  {places.map((p) => (
                    <button
                      key={`${p.latitude},${p.longitude}`}
                      type="button"
                      className="btn-small"
                      onClick={() => { update({ place: p }); setPlaces(null); setPlaceQuery('') }}
                    >
                      {p.name}
                    </button>
                  ))}
                </div>
              )}
            </>
          )}
          <p className="help">
            Only used to look up the weather, which is what lets the forecast learn what a warm dry
            Saturday is worth here. Nothing else is sent, and leaving it blank simply drops the
            weather column.
          </p>
        </div>

        <div className="field" style={{ marginBottom: 0 }}>
          <label htmlFor="hours-target">Hours in a week</label>
          <input
            id="hours-target"
            inputMode="decimal"
            placeholder="none"
            value={hoursText}
            onChange={(e) => {
              setHoursText(e.target.value)
              const hours = Number(e.target.value)
              if (e.target.value.trim() === '') update({ weeklyHoursTarget: 0 })
              else if (Number.isFinite(hours) && hours >= 0) update({ weeklyHoursTarget: Math.round(hours * 10) / 10 })
            }}
          />
          <p className="help">
            {s.weeklyHoursTarget > 0
              ? `The rota counts down from ${s.weeklyHoursTarget} hours as people go on, so you can see what is still to cover.`
              : 'Set a figure and the rota will count down from it as people go on nights. Leave it blank for no target.'}
          </p>
        </div>
      </section>

      <section className="card">
        <div className="card-head">
          <h2>The weekly nudge</h2>
          {nudge === 'granted' && <span className="badge good">On</span>}
        </div>
        <p className="help" style={{ marginTop: 0 }}>
          Once a week, the phone can show the one thing most worth knowing — a weekday well down on
          last year, a line whose margin has slipped, a cellar that does not agree with the till.
        </p>
        <div className="btn-row">
          <button
            type="button"
            className="btn-primary"
            disabled={nudge === 'granted' || nudge === 'denied'}
            onClick={() => void askForNudge()}
          >
            {nudge === 'granted' ? 'Switched on' : nudge === 'denied' ? 'Blocked in the browser' : 'Turn it on'}
          </button>
          {nudge === 'granted' && (
            <button type="button" className="btn-small" onClick={() => void showNudgeNow()}>
              Show me this week's
            </button>
          )}
        </div>
        <p className="help">
          Worth being straight about what this is. A proper push notification needs a server to send
          it, and this app has none — nothing about the pub's takings leaves the phone, which is the
          whole point of it. What a browser will do instead is wake the app on a schedule, on Android
          and on a phone where Tally has been added to the home screen; on an iPhone it will not.
          {nudge === 'denied' && ' Notifications are blocked for this site, which has to be undone in the browser rather than here.'}
        </p>
        <p className="help">
          It also shows what the app last worked out rather than checking afresh, because the part of
          the browser that wakes up cannot do the sums. Opening Tally now and again keeps it current —
          which counting up every night does anyway.
        </p>
      </section>

      <section className="card">
        <div className="card-head"><h2>The photographs</h2></div>
        <div className="switch">
          <span>
            Keep the receipts
            <br />
            <span className="hint">So a disputed night can be checked later</span>
          </span>
          <input
            type="checkbox"
            aria-label="Keep the receipt photographs"
            checked={s.keepPhotos}
            onChange={(e) => update({ keepPhotos: e.target.checked })}
          />
        </div>
        {megabytes && <p className="help">Using about {megabytes} MB on this phone.</p>}
        <div className="alts">
          <button type="button" className="btn-small" onClick={() => void prune()}>
            Delete photographs over 90 days old
          </button>
        </div>
      </section>

      <section className="card">
        <div className="card-head">
          <h2>The year end</h2>
          <span className="hint">for the accountant</span>
        </div>
        <p className="help" style={{ marginTop: 0 }}>
          The last twelve months put together in one go: takings by month, the split between cash and
          card, what is in the cellar at cost, the hours rostered, and the VAT on both sides. Two
          files — a summary to read and a spreadsheet for the detail.
        </p>
        <button type="button" className="btn-primary" onClick={() => void exportYearEnd()}>
          Make the year-end pack
        </button>
        <p className="help">
          Working figures drawn from the till, not a return — the VAT lines especially are something
          to hand an accountant, not something to file. It says so on the document.
        </p>
      </section>

      <section className="card">
        <div className="card-head"><h2>Moving to a new version</h2></div>
        <p className="help" style={{ marginTop: 0 }}>
          Everything lives on this device and nowhere else, which is why it works with the wifi down.
          To carry it to a new copy of Tally — a new phone, a laptop, or a newer file — save it here
          and drop that one file onto the new copy.
        </p>

        <div className="btn-row" style={{ marginBottom: 10 }}>
          <button type="button" className="btn-primary" onClick={() => void exportBackup()}>
            Save everything
          </button>
          <button type="button" onClick={() => void exportCsv()}>Spreadsheet</button>
        </div>

        {/* Drag-and-drop as well as a file picker: on a laptop the file is
            already sitting in a folder, and dragging it on is fewer steps than
            finding it through a dialog. */}
        <div
          className={`dropzone${dragging ? ' over' : ''}${restoring ? ' busy' : ''}`}
          onDragOver={(e) => {
            e.preventDefault()
            setDragging(true)
          }}
          onDragLeave={() => setDragging(false)}
          onDrop={(e) => {
            e.preventDefault()
            setDragging(false)
            const file = [...e.dataTransfer.files][0]
            if (file) void importBackup(file)
          }}
          onClick={() => !restoring && importRef.current?.click()}
          role="button"
          tabIndex={0}
          onKeyDown={(e) => {
            if (e.key === 'Enter' || e.key === ' ') {
              e.preventDefault()
              importRef.current?.click()
            }
          }}
          aria-label="Restore from a saved file"
        >
          {restoring ? (
            <>
              <span className="spinner" />
              <strong>Putting it back…</strong>
            </>
          ) : (
            <>
              <span className="dz-glyph" aria-hidden="true"><IconReceipt size={28} strokeWidth={1.5} /></span>
              <strong>Bring everything back</strong>
              <span className="dz-hint">Drop a saved file here, or tap to find it.</span>
            </>
          )}
        </div>

        <input
          ref={importRef}
          type="file"
          accept="application/json,.json"
          className="visually-hidden"
          data-testid="file-restore"
          onChange={(e) => {
            const file = e.target.files?.[0]
            e.target.value = ''
            if (file) void importBackup(file)
          }}
        />

        <p className="help">
          The file holds the nights, the price list, the cellar with its costs, the rota and everyone
          on it. Restoring adds to whatever is already there — a night with the same date is replaced
          by the one in the file, and nothing else is touched.
        </p>
        <p className="help">
          Two things it deliberately leaves out: the photographs, which are an audit trail rather than
          figures and would make the file too big to email; and the API key, because a backup gets
          sent about and a key is better typed in again.
        </p>
        {megabytes && (
          <p className="help">
            Tally is using about {megabytes} MB on this device.
            {usage && usage.quotaBytes > 0 && ` There is room for roughly ${Math.round(usage.quotaBytes / 1_048_576)} MB.`}
          </p>
        )}
      </section>

      <section className="card">
        <div className="card-head"><h2>About</h2></div>
        <p className="help" style={{ marginTop: 0 }}>
          Tally v1 — till reconciliation for one pub. Add it to the home screen and it opens like any other app,
          with or without a signal.
        </p>
        <div className="alts">
          <button
            type="button"
            className="btn-small"
            onClick={() => void requestPersistence().then((ok) => say(ok ? 'This phone will keep the data.' : 'The browser would not promise to keep the data — back up regularly.'))}
          >
            Ask the phone to keep the data
          </button>
        </div>
      </section>

      {toast && <div className="toast" role="alert">{toast}</div>}
    </div>
  )
}
