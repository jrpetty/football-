import { useCallback, useEffect, useState } from 'react'
import type { PlatformInfo, SightlineBridge, VoiceModelStatus } from '../../../shared/bridge.ts'
import type { CloudStatus } from '../../../shared/cloud.ts'
import type { GameProfile } from '../../core/profile/schema.ts'
import { COOLDOWN_RANGE, VERBOSITIES, type CoachSettings } from '../../core/settings.ts'
import type { Verbosity } from '../../core/types.ts'
import { estimatePerHour, MODELS, PRICING_URL } from '../../core/vision/cost.ts'
import { DEFAULT_CAPTURE } from '../../capture/sources.ts'
import type { ProfileCatalog } from '../App.tsx'
import { Notice, Segmented, Switch } from '../components/common.tsx'
import { VoicePanel } from '../components/VoicePanel.tsx'
import { usd } from '../format.ts'
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
  cloud: CloudStatus
  onCloud: (c: CloudStatus) => void
  profile: GameProfile
}

export function SettingsView({ settings, onSettings, session, info, catalog, bridge, cloud, onCloud, profile }: Props) {
  return (
    <div className="page">
      <div>
        <h1>Settings</h1>
        <p className="lede">How the coach sounds, when it may speak, how it sees, what it remembers, and how you talk to it.</p>
      </div>

      <CloudPanel bridge={bridge} cloud={cloud} onCloud={onCloud} profile={profile} />
      <MemoryPanel settings={settings} onSettings={onSettings} bridge={bridge} session={session} />
      <VoiceInputPanel settings={settings} onSettings={onSettings} bridge={bridge} info={info} />

      <VoicePanel settings={settings} onSettings={onSettings} session={session} bridge={bridge} info={info} />

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
          <h2 id="profiles">Game packs</h2>
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
          Packs are JSON files describing what to read on screen, which zones and mistakes matter, and the game’s modes. Add one
          by placing it in the packs folder and restarting. See docs/PROFILE_AUTHORING.md.
        </p>
      </section>
    </div>
  )
}

// --- Cloud (Anthropic API) ----------------------------------------------------------------

function CloudPanel({ bridge, cloud, onCloud, profile }: { bridge: SightlineBridge; cloud: CloudStatus; onCloud: (c: CloudStatus) => void; profile: GameProfile }) {
  const [key, setKey] = useState('')
  const [error, setError] = useState<string | null>(null)
  const apply = (p: Promise<CloudStatus>) =>
    p.then((c) => {
      setError(null)
      onCloud(c)
    }).catch((e: unknown) => setError(e instanceof Error ? e.message.replace(/^Error invoking remote method '[^']+': (Error: )?/, '') : String(e)))
  const v = profile.vision
  const height = v ? Math.round((v.maxWidth * 9) / 16) : 0

  return (
    <section className="panel" aria-labelledby="cloud">
      <div className="panel-head">
        <h2 id="cloud">Cloud vision & answers (Anthropic API)</h2>
        <span className={`badge ${cloud.available && cloud.settings.enabled && cloud.key.set ? 'ok' : 'neutral'}`}>
          {!cloud.available ? 'Desktop app only' : cloud.settings.enabled && cloud.key.set ? 'On' : 'Off'}
        </span>
      </div>
      <p className="small" style={{ marginTop: 0 }}>
        With your own API key, Claude can read game screens the built-in recognisers can’t (scores, minimaps, banners, statistics),
        answer open questions, and research each game. Off by default. Frames sent are downscaled (or only the pack’s HUD crops), go
        directly to Anthropic with your key, and are not stored by Sightline.
      </p>
      {!cloud.available ? (
        <Notice level="info">Cloud features need the desktop app: your key is kept by the app’s main process and never reaches a web page.</Notice>
      ) : (
        <>
          <div className="grid-2">
            <div className="field">
              <label htmlFor="api-key">API key</label>
              {cloud.key.set ? (
                <div className="btn-row">
                  <code>sk-ant-…{cloud.key.last4}</code>
                  <span className="muted small">{cloud.key.persisted ? 'saved, encrypted by your OS' : 'this session only (OS encryption unavailable)'}</span>
                  <button type="button" className="btn sm ghost danger" onClick={() => void apply(bridge.setApiKey(null))}>
                    Remove
                  </button>
                </div>
              ) : (
                <form
                  className="ask-input"
                  onSubmit={(e) => {
                    e.preventDefault()
                    void apply(bridge.setApiKey(key)).then(() => setKey(''))
                  }}
                >
                  <input id="api-key" type="password" autoComplete="off" placeholder="sk-ant-…" value={key} onChange={(e) => setKey(e.target.value)} />
                  <button type="submit" className="btn" disabled={!key.trim()}>
                    Save key
                  </button>
                </form>
              )}
              <span className="muted small">Create one in the Anthropic Console. Usage is billed to your account.</span>
            </div>
            <div className="field">
              <span className="label">Enable</span>
              <Switch
                checked={cloud.settings.enabled}
                onChange={(enabled) => void apply(bridge.setCloudSettings({ enabled }))}
                label="Use Claude for vision, answers and research"
                hint={cloud.key.set ? undefined : 'Add a key first.'}
              />
            </div>
            <div className="field">
              <label htmlFor="vision-model">Vision model</label>
              <select id="vision-model" value={cloud.settings.visionModel} onChange={(e) => void apply(bridge.setCloudSettings({ visionModel: e.target.value }))}>
                {MODELS.map((m) => (
                  <option key={m.id} value={m.id}>
                    {m.label}
                  </option>
                ))}
              </select>
              {v ? (
                <span className="muted small">
                  {profile.game.title}: one image every {(v.sampleIntervalMs / 1000).toFixed(1)} s ≈ {usd(estimatePerHour(cloud.settings.visionModel, v.sampleIntervalMs, v.maxWidth, height))}/hour (estimate;
                  unchanged frames are skipped). Haiku is far cheaper; Opus reads busy screens best.
                </span>
              ) : null}
            </div>
            <div className="field">
              <label htmlFor="answer-model">Answers & research model</label>
              <select id="answer-model" value={cloud.settings.answerModel} onChange={(e) => void apply(bridge.setCloudSettings({ answerModel: e.target.value }))}>
                {MODELS.map((m) => (
                  <option key={m.id} value={m.id}>
                    {m.label}
                  </option>
                ))}
              </select>
            </div>
            <div className="field">
              <label htmlFor="cap">Spending cap per session: {usd(cloud.settings.sessionCapUsd)}</label>
              <input id="cap" type="range" min={0.5} max={20} step={0.5} value={cloud.settings.sessionCapUsd} onChange={(e) => void apply(bridge.setCloudSettings({ sessionCapUsd: Number(e.target.value) }))} />
              <span className="muted small">
                All cloud calls stop for the session at the cap. This session: {usd(cloud.spend.usd)} over {cloud.spend.calls} calls (estimated).{' '}
                <span title={PRICING_URL}>Prices: anthropic.com/pricing</span>
              </span>
            </div>
          </div>
          {error ? <Notice level="error">{error}</Notice> : null}
        </>
      )}
    </section>
  )
}

// --- Long-term memory ---------------------------------------------------------------------

function MemoryPanel({ settings, onSettings, bridge, session }: { settings: CoachSettings; onSettings: (p: Partial<CoachSettings>) => void; bridge: SightlineBridge; session: CoachSession }) {
  const [encrypted, setEncrypted] = useState<boolean | null>(null)
  const [confirm, setConfirm] = useState(false)
  useEffect(() => void bridge.storageInfo().then((i) => setEncrypted(i.encrypted)), [bridge])
  return (
    <section className="panel" aria-labelledby="memory">
      <div className="panel-head">
        <h2 id="memory">Long-term memory</h2>
        <span className={`badge ${settings.saveTendencies ? 'ok' : 'neutral'}`}>{settings.saveTendencies ? 'On' : 'Off'}</span>
      </div>
      <Switch
        checked={settings.saveTendencies}
        onChange={(saveTendencies) => onSettings({ saveTendencies })}
        label={<b>Remember my games between sessions</b>}
        hint="Keeps match records (score, statistics, mistakes, zone counts), pattern counts and your notes so the coach can compare across games. Never video, screenshots or audio."
      />
      <p className="muted small">
        {encrypted === null ? '' : encrypted ? 'Stored on this computer, encrypted with your OS account.' : 'Stored on this computer without encryption (OS encryption unavailable, or browser preview).'} Patterns fade with a 30-day half-life, and patterns from an older game version are not mixed in.
      </p>
      <div className="btn-row">
        {confirm ? (
          <>
            <span className="small">Delete all saved matches, patterns, notes and research for every game?</span>
            <button
              type="button"
              className="btn danger"
              onClick={() => {
                setConfirm(false)
                void bridge.forgetPlayerData(null).then(() => session.reloadPlayerData())
              }}
            >
              Delete everything
            </button>
            <button type="button" className="btn ghost" onClick={() => setConfirm(false)}>
              Cancel
            </button>
          </>
        ) : (
          <button type="button" className="btn ghost danger" onClick={() => setConfirm(true)}>
            Forget everything…
          </button>
        )}
      </div>
    </section>
  )
}

// --- Voice input -------------------------------------------------------------------------

function VoiceInputPanel({ settings, onSettings, bridge, info }: { settings: CoachSettings; onSettings: (p: Partial<CoachSettings>) => void; bridge: SightlineBridge; info: PlatformInfo | null }) {
  const [status, setStatus] = useState<VoiceModelStatus | null>(null)
  const refresh = useCallback(() => void bridge.voiceStatus().then(setStatus), [bridge])
  useEffect(refresh, [refresh])
  useEffect(() => {
    if (!status?.installing) return
    const t = setInterval(refresh, 500)
    return () => clearInterval(t)
  }, [status?.installing, refresh])
  const desktop = info?.shell === 'electron'
  return (
    <section className="panel" aria-labelledby="vin">
      <div className="panel-head">
        <h2 id="vin">Voice input</h2>
        <span className={`badge ${settings.voiceInput && status?.installed ? 'ok' : 'neutral'}`}>{settings.voiceInput && status?.installed ? 'Ready' : 'Off'}</span>
      </div>
      <Switch
        checked={settings.voiceInput}
        onChange={(voiceInput) => onSettings({ voiceInput })}
        label={<b>Ask the coach out loud (push to talk)</b>}
        hint={`The microphone opens only while you hold the talk control${info?.talkShortcut ? ` (${info.talkShortcut})` : ''}, for at most 15 seconds. Speech is recognised on this computer; audio is never stored or sent.`}
      />
      {settings.voiceInput && desktop && status ? (
        <div className="voice-model">
          {status.installed ? (
            <div className="btn-row">
              <span className="small">Speech model installed (Moonshine tiny, English, ~{status.sizeMb} MB download).</span>
              <button type="button" className="btn sm ghost" onClick={() => void bridge.removeVoiceModel().then(setStatus)}>
                Remove model
              </button>
            </div>
          ) : status.installing ? (
            <div>
              <div className="small">Downloading the speech model… {Math.round(status.progress * 100)}%</div>
              <div className="meter">
                <span style={{ width: `${status.progress * 100}%` }} />
              </div>
            </div>
          ) : (
            <div className="btn-row">
              <button type="button" className="btn" onClick={() => void bridge.installVoiceModel().then(setStatus)}>
                Download speech model (~{status.sizeMb} MB)
              </button>
              <span className="muted small">One-time download from GitHub, checked against a pinned SHA-256.</span>
            </div>
          )}
          {status.error ? <Notice level="error">{status.error}</Notice> : null}
        </div>
      ) : settings.voiceInput && !desktop ? (
        <Notice level="info">Voice input needs the desktop app.</Notice>
      ) : null}
    </section>
  )
}
