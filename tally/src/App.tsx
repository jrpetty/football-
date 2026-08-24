// ---------------------------------------------------------------------------
// The shell.
//
// Four tabs and a little state, rather than a router. The app lives at a path
// that differs between the site root, /tally/ on Pages and a home-screen
// launch; a router would need configuring for each and buy nothing a pub
// landlady would ever notice.
// ---------------------------------------------------------------------------

import { useState } from 'react'
import { NewDay } from './screens/NewDay.tsx'
import { History } from './screens/History.tsx'
import { DayDetail } from './screens/DayDetail.tsx'
import { Dashboard } from './screens/Dashboard.tsx'
import { Settings } from './screens/Settings.tsx'
import { ZReadReview } from './screens/ZReadReview.tsx'
import { formatShort, tradingDayKey } from './core/date.ts'
import type { ZRead } from './core/zread.ts'

type Tab = 'tonight' | 'dashboard' | 'history' | 'settings'

/** An open review, together with where to write the corrected roll back to. */
interface Reviewing {
  zRead: ZRead
  apply: (next: ZRead) => void
}

export function App() {
  const [tab, setTab] = useState<Tab>('tonight')
  const [openDate, setOpenDate] = useState<string | null>(null)
  const [editDate, setEditDate] = useState<string | undefined>(undefined)
  const [refreshKey, setRefreshKey] = useState(0)
  const [saved, setSaved] = useState('')
  const [reviewing, setReviewing] = useState<Reviewing | null>(null)

  function afterSave(date: string) {
    setRefreshKey((k) => k + 1)
    setEditDate(undefined)
    setSaved(`${formatShort(date)} saved`)
    setTimeout(() => setSaved(''), 4000)
    setTab('history')
  }

  function go(next: Tab) {
    setTab(next)
    setOpenDate(null)
    setReviewing(null)
  }

  const subtitle = reviewing
    ? 'Checking the roll'
    : tab === 'tonight'
      ? editDate
        ? 'Correcting a saved night'
        : 'Tonight’s count'
      : tab === 'dashboard'
        ? 'How trade is going'
        : tab === 'history'
          ? 'Every night so far'
          : 'Settings'

  return (
    <div className="app">
      <header className="header">
        <h1>Tally</h1>
        <p className="sub">{subtitle}</p>
        <nav className="tabs" aria-label="Sections">
          <button type="button" aria-current={tab === 'tonight' ? 'page' : undefined} onClick={() => go('tonight')}>Tonight</button>
          <button type="button" aria-current={tab === 'dashboard' ? 'page' : undefined} onClick={() => go('dashboard')}>Trade</button>
          <button type="button" aria-current={tab === 'history' ? 'page' : undefined} onClick={() => go('history')}>Nights</button>
          <button type="button" aria-current={tab === 'settings' ? 'page' : undefined} onClick={() => go('settings')}>Settings</button>
        </nav>
      </header>

      {reviewing ? (
        <ZReadReview
          zRead={reviewing.zRead}
          onChange={(next) => {
            reviewing.apply(next)
            setReviewing({ ...reviewing, zRead: next })
          }}
          onBack={() => setReviewing(null)}
        />
      ) : (
        <>
          {tab === 'tonight' && (
            // Keyed on the date so switching between tonight and a night being
            // corrected remounts with the right record rather than merging them.
            <NewDay
              key={editDate ?? 'tonight'}
              initialDate={editDate}
              onSaved={afterSave}
              onReviewRoll={(zRead, apply) => setReviewing({ zRead, apply })}
            />
          )}

          {tab === 'dashboard' && openDate === null && (
            <Dashboard refreshKey={refreshKey} onOpen={setOpenDate} />
          )}

          {tab === 'history' && openDate === null && (
            <History
              refreshKey={refreshKey}
              onOpen={setOpenDate}
              onStart={() => { setEditDate(undefined); setTab('tonight') }}
            />
          )}

          {(tab === 'history' || tab === 'dashboard') && openDate !== null && (
            <DayDetail
              date={openDate}
              onBack={() => setOpenDate(null)}
              onEdit={(date) => {
                setEditDate(date === tradingDayKey() ? undefined : date)
                setOpenDate(null)
                setTab('tonight')
              }}
              onDeleted={() => { setOpenDate(null); setRefreshKey((k) => k + 1) }}
            />
          )}

          {tab === 'settings' && <Settings onChanged={() => setRefreshKey((k) => k + 1)} />}
        </>
      )}

      {saved && <div className="toast" role="status">{saved}</div>}
    </div>
  )
}
