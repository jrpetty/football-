import { useEffect, useState } from 'react'
import type { PlatformInfo, SightlineBridge } from '../../../shared/bridge.ts'
import { COOLDOWN_RANGE, VERBOSITIES, type CoachSettings } from '../../core/settings.ts'
import type { Verbosity } from '../../core/types.ts'
import { DEFAULT_CAPTURE } from '../../capture/sources.ts'
import type { LocalVoice } from '../../speech/speech.ts'
import type { ProfileCatalog } from '../App.tsx'
import { Notice, Segmented, Switch } from '../components/common.tsx'
import type { CoachSession } from '../session.ts'

const VERBOSITY_TEXT: Record<Verbosity, string> = {
  minimal: 'Only strong evidence, at most one point per review.',
  balanced: 'Moderate evidence or better, one point per review.',
  detailed: 'Includes early signs, clearly labelled, up to two points.',
}

interface Props {
  settings: CoachSettings
  onSettings: (patch: Partial<CoachSettings>) => void
  session: CoachSession
  info: PlatformInfo | null
  catalog: ProfileCatalog
  bridge: SightlineBridge
}

export function SettingsView({ settings, onSettings, session, info, catalog, bridge }: Props) {
  const [voices, setVoices] = useState<LocalVoice[]>(() => session.speech.localVoices())
  useEffect(() => {
    if (!('speechSynthesis' in window)) return
    const update = () => setVoices(session.speech.localVoices())
    window.speechSynthesis.addEventListener('voiceschanged', update)
    update()
    return () => window.speechSynthesis.removeEventListener('voiceschanged', update)
  }, [session])

  return (
    <div className="page">
      <div>
        <h1>Voice & settings</h1>
        <p className="lede">How the coach sounds, how often it may speak, and how capture works.</p>
      </div>

      <section className="panel" aria-labelledby="voice">
        <div className="panel-head">
          <h2 id="voice">Voice</h2>
          <span className="muted small">Local voices only; text never leaves this computer.</span>
        </div>
        {voices.length === 0 ? (
          <Notice level="warning">
            No local text-to-speech voice was found. Messages will appear as text only. On Windows, add a voice under Settings →
            Time & language → Speech.
          </Notice>
        ) : null}
        <div className="grid-2">
          <div className="field">
            <label htmlFor="voice-select">Voice</label>
            <select id="voice-select" value={settings.voice.voiceURI ?? ''} onChange={(e) => onSettings({ voice: { ...settings.voice, voiceURI: e.target.value || null } })}>
              <option value="">System default</option>
              {voices.map((v) => (
                <option key={v.voiceURI} value={v.voiceURI}>
                  {v.name} ({v.lang})
                </option>
              ))}
            </select>
          </div>
          <div className="field">
            <span className="label">Try it</span>
            <div className="btn-row">
              <button type="button" className="btn" disabled={voices.length === 0} onClick={() => session.speech.speakTest('Low health. Patch up first.')}>
                Play sample
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

      <section className="panel" aria-labelledby="delivery">
        <div className="panel-head">
          <h2 id="delivery">Delivery</h2>
        </div>
        <div className="grid-2">
          <div className="field">
            <span className="label">Verbosity</span>
            <Segmented label="Verbosity" value={settings.verbosity} onChange={(verbosity) => onSettings({ verbosity })} options={VERBOSITIES.map((v) => ({ value: v, label: v[0].toUpperCase() + v.slice(1) }))} />
            <span className="muted small">{VERBOSITY_TEXT[settings.verbosity]}</span>
          </div>
          <div className="field">
            <label htmlFor="cooldown">Minimum gap between messages: {settings.cooldownSec}s</label>
            <input id="cooldown" type="range" min={COOLDOWN_RANGE.min} max={120} step={5} value={settings.cooldownSec} onChange={(e) => onSettings({ cooldownSec: Number(e.target.value) })} />
            <span className="muted small">The same tip is also never repeated within a few minutes.</span>
          </div>
        </div>
        <div style={{ marginTop: 16 }}>
          <Switch checked={settings.muted} onChange={(muted) => onSettings({ muted })} label={<b>Hard mute</b>} hint="Nothing is spoken. Messages still appear in the app and the review." />
        </div>
      </section>

      <section className="panel" aria-labelledby="capture">
        <div className="panel-head">
          <h2 id="capture">Capture</h2>
        </div>
        <dl className="kv">
          <dt>Sampling</dt>
          <dd>{DEFAULT_CAPTURE.fps} frames per second, downscaled to {DEFAULT_CAPTURE.analysisWidth}px wide, analysed then discarded</dd>
          <dt>Stop shortcut</dt>
          <dd>{info?.stopShortcut ?? 'Not available in this shell; use the Stop capture button'}</dd>
          <dt>Picker</dt>
          <dd>{info?.systemPicker ? "Your system's own screen-share picker" : 'Sightline’s window list (from the OS), one explicit choice per capture'}</dd>
          <dt>Shell</dt>
          <dd>
            {info ? `${info.shell} · ${info.platform} · v${info.appVersion}` : '…'}
          </dd>
        </dl>
      </section>

      <section className="panel" aria-labelledby="profiles">
        <div className="panel-head">
          <h2 id="profiles">Game profiles</h2>
          {info?.shell === 'electron' ? (
            <button type="button" className="btn sm" onClick={() => void bridge.openProfilesFolder()}>
              Open profiles folder
            </button>
          ) : null}
        </div>
        <ul className="plain small">
          {catalog.profiles.map((p) => (
            <li key={p.id}>
              <b>{p.name}</b> · v{p.version} · {p.validation.status} · patch {p.game.patch}
            </li>
          ))}
        </ul>
        {catalog.rejected.map((r) => (
          <div key={r.name} style={{ marginTop: 12 }}>
            <Notice level="warning">
              <b>{r.name}</b> was not loaded:
              <ul className="plain small">
                {r.errors.slice(0, 6).map((e) => (
                  <li key={e}>{e}</li>
                ))}
              </ul>
            </Notice>
          </div>
        ))}
        <p className="muted small" style={{ marginBottom: 0 }}>
          Profiles are JSON files describing where HUD elements are and what counts as an event. Add one by placing it in the
          profiles folder and restarting. See docs/PROFILE_AUTHORING.md.
        </p>
      </section>
    </div>
  )
}
