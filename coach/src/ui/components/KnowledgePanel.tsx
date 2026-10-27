// The game knowledge base: what the coach knows about a game before it watches
// it — the pack's curated reference plus an optional, much larger base that
// Claude researches on the web.

import { useCallback, useEffect, useState } from 'react'
import type { KnowledgeStatus, SightlineBridge } from '../../../shared/bridge.ts'
import type { CloudStatus } from '../../../shared/cloud.ts'
import { KNOWLEDGE_SECTIONS, knowledgeStats } from '../../core/knowledge/base.ts'
import type { GameProfile } from '../../core/profile/schema.ts'
import { dateShort, usd } from '../format.ts'
import { Notice } from './common.tsx'

export function KnowledgePanel({ bridge, profile, cloud }: { bridge: SightlineBridge; profile: GameProfile; cloud: CloudStatus }) {
  const [status, setStatus] = useState<KnowledgeStatus | null>(null)
  const [focus, setFocus] = useState('')
  const refresh = useCallback(() => void bridge.knowledgeStatus(profile.id).then(setStatus).catch(() => undefined), [bridge, profile.id])
  useEffect(refresh, [refresh])
  useEffect(() => {
    if (!status?.building) return
    const t = setInterval(refresh, 1500)
    return () => clearInterval(t)
  }, [status?.building, refresh])

  const ready = cloud.available && cloud.settings.enabled && cloud.key.set
  const pack = knowledgeStats({ sections: profile.knowledge.reference ?? [] })
  const kb = status?.knowledge
  return (
    <section className="panel" aria-labelledby="kb-h">
      <div className="panel-head">
        <h2 id="kb-h">Game knowledge</h2>
        <span className="muted small">{pack.items + (kb?.items ?? 0)} facts · {status?.screens ?? 0} screens learned</span>
      </div>
      <p className="small muted" style={{ marginTop: 0 }}>
        Everything the coach knows about {profile.game.title} before it watches: modes, maps, menus, the HUD, the roster, mechanics and common mistakes. It
        helps Claude recognise what is on your screen and answer your questions. The more it knows, the better it reads the game.
      </p>
      <div className="kb-stats">
        <div className="kb-stat">
          <div className="n">{pack.items}</div>
          <div className="l">Built into the pack{profile.knowledge.referenceCheckedAt ? ` (checked ${profile.knowledge.referenceCheckedAt})` : ''}</div>
        </div>
        <div className="kb-stat">
          <div className="n">{kb?.items ?? 0}</div>
          <div className="l">{kb ? `Researched ${dateShort(kb.createdAt)} · ${kb.sources} sources` : 'Researched (none yet)'}</div>
        </div>
        <div className="kb-stat">
          <div className="n">{kb ? `${Math.max(1, Math.round(kb.bytes / 1024))} KB` : '—'}</div>
          <div className="l">On disk (compressed)</div>
        </div>
      </div>
      {kb ? <p className="small">Game version researched: <b>{kb.gameVersion}</b></p> : null}

      {status?.building ? (
        <div className="kb-building">
          <span className="typing" aria-hidden="true">
            <span />
            <span />
            <span />
          </span>
          <span className="small">{status.stage}… this takes a few minutes. You can keep using the app.</span>
        </div>
      ) : (
        <form
          className="ask-input"
          onSubmit={(e) => {
            e.preventDefault()
            void bridge.buildKnowledge({ profileId: profile.id, focus }).then(setStatus)
          }}
        >
          <input aria-label="Research focus" placeholder="Anything to focus on? e.g. ranked meta, weapons, build orders" value={focus} maxLength={300} onChange={(e) => setFocus(e.target.value)} />
          <button type="submit" className="btn primary" disabled={!ready}>
            {kb ? 'Rebuild' : 'Build game knowledge'}
          </button>
        </form>
      )}
      <p className="muted small">
        {ready
          ? 'Claude searches official sites, patch notes, wikis and guides. A one-off cost, typically $1–4 with Opus (less with Sonnet). Rebuild after big patches.'
          : 'Needs Claude connected (Settings).'}
        {kb?.costUsd ? ` Last build: ${usd(kb.costUsd)}.` : ''}
      </p>
      {status?.error ? <Notice level="error">{status.error}</Notice> : null}

      {kb ? (
        <details className="kb-browse">
          <summary className="small">Browse what it knows</summary>
          {KNOWLEDGE_SECTIONS.map((s) => {
            const sec = kb.sectionsList.find((x) => x.id === s.id)
            if (!sec?.items.length) return null
            return (
              <details key={s.id} className="kb-section">
                <summary>
                  {s.title} <span className="muted small">({sec.items.length})</span>
                </summary>
                <ul>
                  {sec.items.map((it) => (
                    <li key={it.name}>
                      <b>{it.name}</b>
                      {it.detail ? ` — ${it.detail}` : ''}
                    </li>
                  ))}
                </ul>
              </details>
            )
          })}
          <button type="button" className="btn sm ghost danger" onClick={() => void bridge.deleteKnowledge(profile.id).then(setStatus)}>
            Delete researched knowledge
          </button>
        </details>
      ) : null}
    </section>
  )
}
