// DayZ: the companion. A chat you talk to openly (typed, or out loud with the
// talk key or always listening), beside its own screen, which pulls up what
// you asked for: maps and routes, places, guns, where items spawn, plans,
// advice, your kit. Known questions are answered on this computer at once;
// open ones go to Claude, which uses the same data through tools.

import { useEffect, useRef, useState } from 'react'
import type { CloudStatus } from '../../../shared/cloud.ts'
import type { GameProfile } from '../../core/profile/schema.ts'
import type { CoachSettings } from '../../core/settings.ts'
import { KIT } from '../../core/dayz/assistant.ts'
import { nextRestart, parseRestarts } from '../../core/dayz/server.ts'
import { AskBox } from '../components/ask.tsx'
import { CompanionScreen } from '../components/companionScreen.tsx'
import { usd } from '../format.ts'
import type { CoachSession, QaEntry, SessionState } from '../session.ts'

interface Props {
  state: SessionState
  session: CoachSession
  profile: GameProfile
  settings: CoachSettings
  cloud: CloudStatus
  /** Open a question on the big Map screen. */
  onOpenMap: (q: string) => void
}

export function CompanionView({ state, session, profile, settings, cloud, onOpenMap }: Props) {
  const withCard = state.conversation.filter((e) => e.card)
  const [selected, setSelected] = useState<string | null>(null)
  // A new card: show it (unless the player picked an older one in the last few seconds).
  const lastCard = withCard.at(-1)?.id ?? null
  const pickedAt = useRef(0)
  useEffect(() => {
    if (lastCard && Date.now() - pickedAt.current > 4000) setSelected(lastCard)
  }, [lastCard])
  const shown = state.conversation.find((e) => e.id === selected && e.card) ?? withCard.at(-1) ?? null
  const ask = (q: string) => void session.ask(q, 'text', cloud)
  const list = useRef<HTMLOListElement>(null)
  useEffect(() => {
    list.current?.scrollTo({ top: list.current.scrollHeight, behavior: 'smooth' })
  }, [state.conversation.length, state.conversation.at(-1)?.status])
  const claude = cloud.available && cloud.settings.enabled && cloud.key.set
  const prefs = settings.context.dayz ?? {}
  const beginner = prefs.level === 'New to DayZ'
  // The next server restart, refreshed every half minute.
  const [, tick] = useState(0)
  useEffect(() => {
    const t = setInterval(() => tick((n) => n + 1), 30_000)
    return () => clearInterval(t)
  }, [])
  const restart = nextRestart(parseRestarts(String(prefs.restarts ?? '')), new Date())
  const notice = state.restartNotice && Date.now() - state.restartNotice.at < 5 * 60_000 ? state.restartNotice.text : null

  return (
    <div className="page companion">
      <div className="comp-top">
        <div>
          <h1>Companion</h1>
          <p className="lede">Talk to it like a teammate who knows Chernarus. What you ask for comes up on its screen.</p>
        </div>
        <div className="comp-chips">
          <span className={`badge ${state.spot ? 'ok' : 'neutral'}`}>{state.spot ? `You: ${state.spot.label}` : 'Spot not set'}</span>
          <span className="badge neutral">
            Kit {state.kit.length}/{KIT.length}
          </span>
          <span className={`badge ${claude ? 'info' : 'neutral'}`} title={claude ? 'Open questions go to Claude (with the app’s own data as tools).' : 'Without Claude it answers what it knows on this computer.'}>
            {claude ? 'Open chat: Claude' : 'Answers on this PC'}
          </span>
          {beginner ? <span className="badge info">Beginner mode</span> : null}
          {restart ? <span className={`badge ${restart.inMin <= 15 ? 'warn' : 'neutral'}`}>Restart in {restart.inMin >= 60 ? `${Math.floor(restart.inMin / 60)} h ${Math.round(restart.inMin % 60)} min` : `${Math.max(1, Math.round(restart.inMin))} min`}</span> : null}
          {settings.voiceInput ? <span className="badge neutral">{settings.listen === 'always' ? 'Listening: say “Coach…”' : 'Talk key to speak'}</span> : null}
        </div>
      </div>

      {notice ? (
        <p className="notice warning small" role="alert">
          {notice}
        </p>
      ) : null}
      <div className="comp-layout">
        <section className="panel comp-chat" aria-label="Conversation">
          <ol className="comp-messages" ref={list}>
            {state.conversation.length === 0 ? (
              <li className="comp-empty small muted">Say hello, or try “I'm at Turovo”, then “what now?”.</li>
            ) : (
              state.conversation.map((e) => <Message key={e.id} e={e} active={shown?.id === e.id} onShow={() => ((pickedAt.current = Date.now()), setSelected(e.id))} />)
            )}
          </ol>
          <AskBox state={state} session={session} profile={profile} settings={settings} cloud={cloud} compact placeholder="Talk to your companion…" />
        </section>
        <section className="panel comp-screen" aria-label="Companion screen">
          <CompanionScreen key={shown?.id ?? 'welcome'} card={shown?.card ?? null} question={shown?.question ?? null} state={state} session={session} onAsk={ask} onOpenMap={onOpenMap} />
        </section>
      </div>
    </div>
  )
}

function Message({ e, active, onShow }: { e: QaEntry; active: boolean; onShow: () => void }) {
  return (
    <li className={`comp-msg${active ? ' active' : ''}`}>
      <div className="comp-you">
        {e.question}
        {e.via === 'voice' ? <span className="muted small"> (voice)</span> : null}
      </div>
      <div className={`comp-bot ${e.status}`}>
        {e.status === 'thinking' && !e.answer ? (
          <span className="typing" aria-label="Thinking">
            <span />
            <span />
            <span />
          </span>
        ) : null}
        {e.answer && e.status !== 'error' ? <p>{e.answer}</p> : null}
        {e.status === 'held' ? <p className="small warn-text">{e.note}</p> : null}
        {e.status === 'error' ? <p className="small warn-text">{e.note}</p> : null}
        <div className="comp-meta small muted">
          {e.card ? (
            <button type="button" className={`link-btn${active ? ' on' : ''}`} onClick={onShow}>
              {active ? 'On screen' : 'Show on screen'}
            </button>
          ) : null}
          {e.author === 'ai' ? <span>Claude{e.costUsd > 0 ? ` · ${usd(e.costUsd)}` : ''}</span> : e.status === 'answered' ? <span>On this PC</span> : null}
        </div>
      </div>
    </li>
  )
}
