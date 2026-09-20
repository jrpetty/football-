// ---------------------------------------------------------------------------
// The shell.
//
// Three tabs and a drawer, rather than a router. The app lives at a path that
// differs between the site root, /tally/ on Pages and a home-screen launch; a
// router would need configuring for each and buy nothing a pub landlady would
// ever notice.
//
// Why three. There are three jobs: tonight's count, the cellar, and looking a
// night up again. Everything else the app can do — how trade is going, who is
// on, the price list, the settings — is real work but it is not the work of a
// Tuesday, and a bar of six evenly weighted tabs says otherwise every time she
// opens it. So the other four live behind More: one tap away, and out of the
// way of the job she actually came to do.
// ---------------------------------------------------------------------------

import { useState } from 'react'
import { NewDay } from './screens/NewDay.tsx'
import { History } from './screens/History.tsx'
import { DayDetail } from './screens/DayDetail.tsx'
import { Dashboard } from './screens/Dashboard.tsx'
import { Settings } from './screens/Settings.tsx'
import { Prices } from './screens/Prices.tsx'
import { Stock } from './screens/Stock.tsx'
import { Rota } from './screens/Rota.tsx'
import { ZReadReview } from './screens/ZReadReview.tsx'
import { useToast } from './components/toast.ts'
import { formatShort, tradingDayKey } from './core/date.ts'
import type { ZRead } from './core/zread.ts'
import {
  IconBarrel, IconBook, IconChart, IconChevronRight, IconMoon, IconMore,
  IconPeople, IconReceipt, IconSliders, TallyMark,
} from './components/icons.tsx'

/** The three that are on the bar, then the four that live behind More. */
type Tab = 'tonight' | 'stock' | 'history' | 'more'
type Deeper = 'dashboard' | 'rota' | 'prices' | 'settings'

/** An open review, together with where to write the corrected roll back to. */
interface Reviewing {
  zRead: ZRead
  apply: (next: ZRead) => void
}

const DEEPER: { key: Deeper; label: string; blurb: string; icon: React.ReactNode }[] = [
  { key: 'dashboard', label: 'Trade', blurb: 'How the takings are going', icon: <IconChart /> },
  { key: 'rota', label: 'Rota', blurb: 'Who is on, and what it costs', icon: <IconPeople /> },
  { key: 'prices', label: 'Price list', blurb: 'What everything sells for', icon: <IconReceipt /> },
  { key: 'settings', label: 'Settings', blurb: 'Backups, the float, the lot', icon: <IconSliders /> },
]

export function App() {
  const [tab, setTab] = useState<Tab>('tonight')
  const [deeper, setDeeper] = useState<Deeper | null>(null)
  const [openDate, setOpenDate] = useState<string | null>(null)
  const [editDate, setEditDate] = useState<string | undefined>(undefined)
  const [refreshKey, setRefreshKey] = useState(0)
  const [saved, saySaved] = useToast()
  const [reviewing, setReviewing] = useState<Reviewing | null>(null)

  function bump() {
    setRefreshKey((k) => k + 1)
  }

  function afterSave(date: string) {
    bump()
    setEditDate(undefined)
    saySaved(`${formatShort(date)} saved`)
    setTab('history')
  }

  function go(next: Tab) {
    setTab(next)
    setOpenDate(null)
    setReviewing(null)
    // Tapping More while already inside one of its screens comes back to the
    // list, which is the only way back that does not need a second control.
    setDeeper(null)
  }

  const subtitle = reviewing
    ? 'Checking the roll'
    : deeper
      ? DEEPER.find((d) => d.key === deeper)!.blurb
      : tab === 'tonight'
        ? editDate
          ? 'Correcting a saved night'
          : 'Tonight’s count'
        : tab === 'stock'
          ? 'What is in the cellar'
          : tab === 'history'
            ? 'Every night so far'
            : 'Everything else'

  return (
    <div className="app">
      <header className="header">
        <div className="brand">
          <span className="brand-mark" aria-hidden="true"><TallyMark size={26} /></span>
          <div>
            <h1>Tally</h1>
            <p className="sub">{subtitle}</p>
          </div>
        </div>
        <nav className="tabs" aria-label="Sections">
          <button type="button" aria-current={tab === 'tonight' ? 'page' : undefined} onClick={() => go('tonight')}>
            <IconMoon /><span>Tonight</span>
          </button>
          <button type="button" aria-current={tab === 'stock' ? 'page' : undefined} onClick={() => go('stock')}>
            <IconBarrel /><span>Cellar</span>
          </button>
          <button type="button" aria-current={tab === 'history' ? 'page' : undefined} onClick={() => go('history')}>
            <IconBook /><span>Nights</span>
          </button>
          <button type="button" aria-current={tab === 'more' ? 'page' : undefined} onClick={() => go('more')}>
            <IconMore /><span>More</span>
          </button>
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
              onOpenCellar={() => go('stock')}
            />
          )}

          {tab === 'stock' && <Stock onChanged={bump} />}

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
              onEdit={(date) => {
                setEditDate(date === tradingDayKey() ? undefined : date)
                setOpenDate(null)
                setTab('tonight')
              }}
              onDeleted={() => { setOpenDate(null); bump() }}
            />
          )}

          {tab === 'more' && deeper === null && (
            <div className="main">
              <nav className="doors" aria-label="Everything else">
                {DEEPER.map((d) => (
                  <button key={d.key} type="button" className="door" onClick={() => setDeeper(d.key)}>
                    <span className="door-icon" aria-hidden="true">{d.icon}</span>
                    <span className="door-text">
                      <strong>{d.label}</strong>
                      <span>{d.blurb}</span>
                    </span>
                    <IconChevronRight />
                  </button>
                ))}
              </nav>
              <p className="note">
                Nothing in here is needed to count a night. It is all one tap away when it is wanted.
              </p>
            </div>
          )}

          {tab === 'more' && deeper === 'dashboard' && openDate === null && (
            <Dashboard refreshKey={refreshKey} onOpen={setOpenDate} />
          )}

          {tab === 'more' && deeper === 'dashboard' && openDate !== null && (
            <DayDetail
              date={openDate}
              onBack={() => setOpenDate(null)}
              onEdit={(date) => {
                setEditDate(date === tradingDayKey() ? undefined : date)
                setOpenDate(null)
                setTab('tonight')
              }}
              onDeleted={() => { setOpenDate(null); bump() }}
            />
          )}

          {tab === 'more' && deeper === 'rota' && <Rota onChanged={bump} />}

          {tab === 'more' && deeper === 'prices' && <Prices onChanged={bump} />}

          {tab === 'more' && deeper === 'settings' && (
            <Settings onChanged={bump} onOpenPrices={() => setDeeper('prices')} />
          )}
        </>
      )}

      {saved && <div className="toast" role="status">{saved}</div>}
    </div>
  )
}
