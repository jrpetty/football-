// How the coach sounds: natural neural voices (downloaded once, run on this
// computer) or the system's own local voices.

import { useCallback, useEffect, useState } from 'react'
import type { PlatformInfo, SightlineBridge } from '../../../shared/bridge.ts'
import { NEURAL_VOICES, neuralVoice, type TtsModelKey, type TtsStatus } from '../../../shared/voices.ts'
import type { CoachSettings } from '../../core/settings.ts'
import type { LocalVoice } from '../../speech/speech.ts'
import type { CoachSession } from '../session.ts'
import { Notice } from './common.tsx'

const MODEL_INFO: Record<TtsModelKey, { title: string; blurb: string }> = {
  jenny: { title: 'Jenny', blurb: 'A warm British voice. Fast on any PC — the recommended choice while you play.' },
  kokoro: { title: 'Kokoro', blurb: 'The most natural voices (American and British, male and female). Needs a quicker CPU.' },
}

const SAMPLE = 'Nice. You held that lane well. Next fight, take the high ground before you push.'

export function VoicePanel(props: {
  settings: CoachSettings
  onSettings: (patch: Partial<CoachSettings>) => void
  session: CoachSession
  bridge: SightlineBridge
  info: PlatformInfo | null
}) {
  const { settings, onSettings, session, bridge } = props
  const [system, setSystem] = useState<LocalVoice[]>(() => session.speech.localVoices())
  const [tts, setTts] = useState<TtsStatus | null>(null)
  const refresh = useCallback(() => void bridge.ttsStatus().then(setTts).catch(() => undefined), [bridge])

  useEffect(() => {
    refresh()
    if (!('speechSynthesis' in window)) return
    const update = () => setSystem(session.speech.localVoices())
    window.speechSynthesis.addEventListener('voiceschanged', update)
    update()
    return () => window.speechSynthesis.removeEventListener('voiceschanged', update)
  }, [session, refresh])

  const installing = tts && Object.values(tts.models).some((m) => m.installing)
  useEffect(() => {
    if (!installing) return
    const t = setInterval(refresh, 500)
    return () => clearInterval(t)
  }, [installing, refresh])

  // Pick the first natural voice automatically once one is installed and nothing is chosen.
  useEffect(() => {
    if (tts?.models.jenny.installed && !settings.voice.voiceURI) onSettings({ voice: { ...settings.voice, voiceURI: 'neural:jenny' } })
  }, [tts?.models.jenny.installed, settings.voice, onSettings])

  const current = neuralVoice(settings.voice.voiceURI)
  const choose = (voiceURI: string | null) => onSettings({ voice: { ...settings.voice, voiceURI } })
  const play = (voiceURI?: string | null) => {
    session.quiet()
    if (voiceURI !== undefined && voiceURI !== settings.voice.voiceURI) choose(voiceURI)
    setTimeout(() => session.speech.speakTest(SAMPLE), 50)
    setTimeout(refresh, 4000)
  }

  return (
    <section className="panel" aria-labelledby="voice">
      <div className="panel-head">
        <h2 id="voice">Coach voice</h2>
        <span className="muted small">Runs on this computer. What the coach says is never sent anywhere.</span>
      </div>

      {tts?.available ? (
        <div className="voice-models">
          {(['jenny', 'kokoro'] as const).map((key) => {
            const m = tts.models[key]
            const rtf = tts.realTimeFactor[key]
            return (
              <div key={key} className={`voice-model-card${m.installed ? ' installed' : ''}`}>
                <div className="vm-head">
                  <b>{MODEL_INFO[key].title}</b>
                  {key === 'jenny' ? <span className="badge ok">Recommended</span> : <span className="badge info">Best quality</span>}
                  {rtf !== undefined ? <SpeedBadge rtf={rtf} /> : null}
                </div>
                <p className="small muted">{MODEL_INFO[key].blurb}</p>
                {m.installing ? (
                  <div>
                    <div className="small">{m.progress < 0.9 ? 'Downloading' : 'Unpacking'}… {Math.round(m.progress * 100)}%</div>
                    <div className="meter">
                      <span style={{ width: `${m.progress * 100}%` }} />
                    </div>
                  </div>
                ) : m.installed ? (
                  <div className="voice-chips">
                    {NEURAL_VOICES.filter((v) => v.model === key).map((v) => (
                      <button key={v.id} type="button" className="chip" aria-pressed={settings.voice.voiceURI === v.id} onClick={() => play(v.id)} title={`${v.accent} ${v.gender} — click to hear`}>
                        {v.name} <span className="muted">· {v.accent === 'British' ? 'UK' : 'US'}</span>
                      </button>
                    ))}
                    <button type="button" className="btn sm ghost" onClick={() => void bridge.removeTts(key).then(setTts)}>
                      Remove
                    </button>
                  </div>
                ) : (
                  <button type="button" className="btn sm primary" onClick={() => void bridge.installTts(key).then(setTts)}>
                    Download ({m.sizeMb} MB)
                  </button>
                )}
                {m.error ? <Notice level="error">{m.error}</Notice> : null}
              </div>
            )
          })}
        </div>
      ) : (
        <Notice level="info">Natural voices need the desktop app. The browser preview uses your system’s local voices.</Notice>
      )}

      {current && tts?.realTimeFactor[current.model] !== undefined && tts.realTimeFactor[current.model]! > 0.8 ? (
        <Notice level="warning">
          {current.name} is slow on this PC ({tts.realTimeFactor[current.model]!.toFixed(1)}× real time), so tips may arrive late. Jenny is much faster.
        </Notice>
      ) : null}

      <div className="grid-2" style={{ marginTop: 14 }}>
        <div className="field">
          <label htmlFor="voice-select">Voice</label>
          <select id="voice-select" value={settings.voice.voiceURI ?? ''} onChange={(e) => choose(e.target.value || null)}>
            <option value="">System default</option>
            {tts?.available ? (
              <optgroup label="Natural (installed)">
                {NEURAL_VOICES.filter((v) => tts.models[v.model].installed).map((v) => (
                  <option key={v.id} value={v.id}>
                    {v.name} ({v.accent}, {v.gender})
                  </option>
                ))}
              </optgroup>
            ) : null}
            <optgroup label="System voices">
              {system.map((v) => (
                <option key={v.voiceURI} value={v.voiceURI}>
                  {v.name} ({v.lang})
                </option>
              ))}
            </optgroup>
          </select>
          {!current && system.length === 0 ? <span className="muted small warn-text">No local system voice found; download a natural voice above.</span> : null}
        </div>
        <div className="field">
          <span className="label">Try it</span>
          <div className="btn-row">
            <button type="button" className="btn" onClick={() => play()}>
              ▶ Play sample
            </button>
            <button type="button" className="btn ghost" onClick={() => session.quiet()}>
              Stop
            </button>
          </div>
        </div>
        <div className="field">
          <label htmlFor="rate">Speed: {settings.voice.rate.toFixed(2)}×</label>
          <input id="rate" type="range" min={0.5} max={2} step={0.05} value={settings.voice.rate} onChange={(e) => onSettings({ voice: { ...settings.voice, rate: Number(e.target.value) } })} />
        </div>
        <div className="field">
          <label htmlFor="volume">Volume: {Math.round(settings.voice.volume * 100)}%</label>
          <input id="volume" type="range" min={0} max={1} step={0.05} value={settings.voice.volume} onChange={(e) => onSettings({ voice: { ...settings.voice, volume: Number(e.target.value) } })} />
        </div>
      </div>
    </section>
  )
}

function SpeedBadge({ rtf }: { rtf: number }) {
  const label = rtf < 0.35 ? 'Fast on this PC' : rtf < 0.8 ? 'OK on this PC' : 'Slow on this PC'
  return <span className={`badge ${rtf < 0.35 ? 'ok' : rtf < 0.8 ? 'neutral' : 'warn'}`} title={`${rtf.toFixed(2)} s of compute per second of speech`}>{label}</span>
}
