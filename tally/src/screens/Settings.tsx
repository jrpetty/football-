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
import { listDays, estimateUsage, prunePhotosBefore, saveDay, requestPersistence } from '../storage/db.ts'
import { downloadFile, parseBackup, toCsv, toJson } from '../storage/export.ts'
import {
  AI_MODELS,
  loadSettings,
  saveSettings,
  type EnginePreference,
  type Settings as SettingsShape,
} from '../storage/settings.ts'

const ENGINE_HELP: Record<EnginePreference, string> = {
  vision:
    'Claude reads the photograph. Much the better reader on faded or curled receipt paper, because it understands which line is the total rather than only seeing shapes. Needs a signal and an API key, and costs roughly a penny a night.',
  device:
    'The phone reads the photograph itself. Free, private and works with no signal, but noticeably worse on thermal receipts — expect to correct it more often.',
  off:
    'No scanning at all. Photograph nothing and type the three figures in. Still far quicker than the paper ledger, and completely reliable.',
}

export function Settings({ onChanged }: { onChanged: () => void }) {
  const [s, setS] = useState<SettingsShape>(loadSettings)
  const [toleranceText, setToleranceText] = useState(() => penceToInput(loadSettings().tolerancePence))
  const [usage, setUsage] = useState<{ usedBytes: number; quotaBytes: number } | null>(null)
  const [toast, setToast] = useState('')
  const [showKey, setShowKey] = useState(false)
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

  async function exportCsv() {
    const days = await listDays()
    if (days.length === 0) return say('Nothing to export yet.')
    downloadFile(`tally-${new Date().toISOString().slice(0, 10)}.csv`, toCsv(days, s.tolerancePence), 'text/csv')
    say(`Exported ${days.length} nights.`)
  }

  async function exportBackup() {
    const days = await listDays()
    if (days.length === 0) return say('Nothing to back up yet.')
    downloadFile(`tally-backup-${new Date().toISOString().slice(0, 10)}.json`, toJson(days), 'application/json')
    say(`Backed up ${days.length} nights.`)
  }

  async function importBackup(file: File) {
    try {
      const days = parseBackup(await file.text())
      if (days.length === 0) return say('That file had no nights in it.')
      for (const day of days) await saveDay(day)
      onChanged()
      say(`Restored ${days.length} nights.`)
    } catch (err) {
      say(err instanceof Error ? err.message : 'That file could not be read.')
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

        {s.engine === 'vision' && (
          <>
            <div className="field">
              <label htmlFor="apiKey">Anthropic API key</label>
              <input
                id="apiKey"
                type={showKey ? 'text' : 'password'}
                value={s.apiKey}
                autoComplete="off"
                spellCheck={false}
                placeholder="sk-ant-…"
                onChange={(e) => update({ apiKey: e.target.value })}
              />
              <div className="alts">
                <button type="button" className="btn-small" onClick={() => setShowKey((v) => !v)}>
                  {showKey ? 'Hide key' : 'Show key'}
                </button>
              </div>
              <p className="help">
                Kept in this browser and sent straight to Anthropic — there is no server in between. It never
                leaves the phone except to read a receipt.
              </p>
            </div>

            <div className="field">
              <label htmlFor="model">Model</label>
              <select id="model" value={s.model} onChange={(e) => update({ model: e.target.value })}>
                {AI_MODELS.map((m) => (
                  <option key={m.id} value={m.id}>{m.label} — {m.hint}</option>
                ))}
              </select>
            </div>
          </>
        )}
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
        <div className="card-head"><h2>Keeping the data safe</h2></div>
        <p className="help" style={{ marginTop: 0 }}>
          Everything lives on this phone and nowhere else, which is why it works with the wifi down — and why a
          backup matters. Mail yourself the file now and again.
        </p>
        <div className="btn-row" style={{ marginBottom: 10 }}>
          <button type="button" onClick={() => void exportCsv()}>Export spreadsheet</button>
          <button type="button" onClick={() => void exportBackup()}>Back up</button>
        </div>
        <button type="button" className="btn-ghost" onClick={() => importRef.current?.click()}>
          Restore from a backup
        </button>
        <input
          ref={importRef}
          type="file"
          accept="application/json,.json"
          className="visually-hidden"
          onChange={(e) => {
            const file = e.target.files?.[0]
            e.target.value = ''
            if (file) void importBackup(file)
          }}
        />
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
