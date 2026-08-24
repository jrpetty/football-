// ---------------------------------------------------------------------------
// The shell.
//
// Three tabs and a little state, rather than a router. The app has four screens
// and lives at a path that differs between the site root, /tally/ on Pages and
// a home-screen launch; a router would need configuring for each of those, and
// would buy nothing a pub landlady would ever notice.
// ---------------------------------------------------------------------------

import { useState } from 'react'
import { NewDay } from './screens/NewDay.tsx'
import { History } from './screens/History.tsx'
import { DayDetail } from './screens/DayDetail.tsx'
import { Settings } from './screens/Settings.tsx'
import { formatShort } from './core/date.ts'
import { tradingDayKey } from './core/date.ts'

type Tab = 'tonight' | 'history' | 'settings'

export function App() {
  const [tab, setTab] = useState<Tab>('tonight')
  const [openDate, setOpenDate] = useState<string | null>(null)
  const [editDate, setEditDate] = useState<string | undefined>(undefined)
  const [refreshKey, setRefreshKey] = useState(0)
  const [saved, setSaved] = useState('')

  function afterSave(date: string) {
    setRefreshKey((k) => k + 1)
    setEditDate(undefined)
    setSaved(`${formatShort(date)} saved`)
    setTimeout(() => setSaved(''), 4000)
    setTab('history')
  }

  const subtitle =
    tab === 'tonight'
      ? editDate
        ? 'Correcting a saved night'
        : 'Tonight’s count'
      : tab === 'history'
        ? 'Every night so far'
        : 'Settings'

  return (
    <div className="app">
      <header className="header">
        <h1>Tally</h1>
        <p className="sub">{subtitle}</p>
        <nav className="tabs" aria-label="Sections">
          <button type="button" aria-current={tab === 'tonight' ? 'page' : undefined} onClick={() => { setTab('tonight'); setOpenDate(null) }}>
            Tonight
          </button>
          <button type="button" aria-current={tab === 'history' ? 'page' : undefined} onClick={() => { setTab('history'); setOpenDate(null) }}>
            History
          </button>
          <button type="button" aria-current={tab === 'settings' ? 'page' : undefined} onClick={() => { setTab('settings'); setOpenDate(null) }}>
            Settings
          </button>
        </nav>
      </header>

      {tab === 'tonight' && (
        // Keyed on the date so switching between tonight and a night being
        // corrected remounts with the right record rather than merging the two.
        <NewDay key={editDate ?? 'tonight'} initialDate={editDate} onSaved={afterSave} />
      )}

      {tab === 'history' && openDate === null && (
        <History
          refreshKey={refreshKey}
          onOpen={setOpenDate}
          onStart={() => { setEditDate(undefined); setTab('tonight') }}
        />
      )}

      {tab === 'history' && openDate !== null && (
        <DayDetail
          date={openDate}
          onBack={() => setOpenDate(null)}
          onEdit={(date) => { setEditDate(date === tradingDayKey() ? undefined : date); setOpenDate(null); setTab('tonight') }}
          onDeleted={() => { setOpenDate(null); setRefreshKey((k) => k + 1) }}
        />
      )}

      {tab === 'settings' && <Settings onChanged={() => setRefreshKey((k) => k + 1)} />}

      {saved && <div className="toast" role="status">{saved}</div>}
    </div>
  )
}
