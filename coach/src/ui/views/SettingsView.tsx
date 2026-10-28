import { useCallback, useEffect, useState } from 'react'
import type { PlatformInfo, SightlineBridge, VoiceModelStatus } from '../../../shared/bridge.ts'
import type { CloudStatus } from '../../../shared/cloud.ts'
import type { GameProfile } from '../../core/profile/schema.ts'
import { COOLDOWN_RANGE, LEARNING_MIN_GAMES, learningPrefsFor, VERBOSITIES, VISION_LIMITS, visionPrefsFor, type CoachSettings, type LearningPrefs, type VisionPrefs } from '../../core/settings.ts'
import type { Verbosity } from '../../core/types.ts'
import { cachedPromptTokens, estimatePerHour, modelPrice, MODELS, PRICING_URL } from '../../core/vision/cost.ts'
import { DEFAULT_CAPTURE } from '../../capture/sources.ts'
import type { ProfileCatalog } from '../App.tsx'
import { Notice, Segmented, Switch } from '../components/common.tsx'
import { VoicePanel } from '../components/VoicePanel.tsx'
import { HotkeyField } from '../components/HotkeyField.tsx'
import type { Hotkeys } from '../../core/settings.ts'
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

      <CloudPanel bridge={bridge} cloud={cloud} onCloud={onCloud} profile={profile} settings={settings} />
      {profile.vision ? <ScreenReadingPanel settings={settings} onSettings={onSettings} bridge={bridge} cloud={cloud} onCloud={onCloud} profile={profile} /> : null}
      {profile.learning ? <LearningPanel settings={settings} onSettings={onSettings} session={session} profile={profile} /> : null}
      <MemoryPanel settings={settings} onSettings={onSettings} bridge={bridge} session={session} />
      <VoiceInputPanel settings={settings} onSettings={onSettings} bridge={bridge} info={info} />
      <KeyboardPanel settings={settings} onSettings={onSettings} info={info} />

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
          <Switch
            checked={settings.commentary}
            onChange={(commentary) => onSettings({ commentary })}
            label={<b>Live commentary</b>}
            hint="Short spoken lines about what the coach sees as it happens: fights starting and how they ended, deaths and how they happened, goals, scouted buildings, checkpoints. It speaks during play only where the game and mode allow it (campaigns, against the AI or bots); elsewhere it tells you once at the start and you get the review after the match."
          />
        </div>
        <div style={{ marginTop: 12 }}>
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

function CloudPanel({ bridge, cloud, onCloud, profile, settings }: { bridge: SightlineBridge; cloud: CloudStatus; onCloud: (c: CloudStatus) => void; profile: GameProfile; settings: CoachSettings }) {
  const [key, setKey] = useState('')
  const [error, setError] = useState<string | null>(null)
  const apply = (p: Promise<CloudStatus>) =>
    p.then((c) => {
      setError(null)
      onCloud(c)
    }).catch((e: unknown) => setError(e instanceof Error ? e.message.replace(/^Error invoking remote method '[^']+': (Error: )?/, '') : String(e)))
  const v = profile.vision ? visionPrefsFor(profile, settings) : null
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
                  {profile.game.title}: one look every {(v.intervalMs / 1000).toFixed(1)} s ≈ {usd(estimatePerHour(cloud.settings.visionModel, v.intervalMs, v.maxWidth, height, cachedPromptTokens(!!profile.knowledge.roster)))}/hour
                  before thinking (estimate; see Screen reading below for what this session measured). Haiku is far cheaper; Opus reads busy screens best.
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

// --- Screen reading (per game) -------------------------------------------------------------

const WIDTH_LABEL: Record<number, string> = { 768: 'Low', 1024: 'Medium', 1280: 'High', 1600: 'Max' }

/** How often and how sharply the coach looks at this game's screen, chosen by the player; and what it costs, measured. */
function ScreenReadingPanel({ settings, onSettings, bridge, cloud, onCloud, profile }: { settings: CoachSettings; onSettings: (p: Partial<CoachSettings>) => void; bridge: SightlineBridge; cloud: CloudStatus; onCloud: (c: CloudStatus) => void; profile: GameProfile }) {
  const pack = profile.vision!
  const v = visionPrefsFor(profile, settings)
  const own = settings.vision[profile.id] ?? {}
  const set = (patch: VisionPrefs | null) => {
    const next = { ...settings.vision }
    if (patch === null) delete next[profile.id]
    else next[profile.id] = { ...own, ...patch }
    onSettings({ vision: next })
  }
  const unset = (key: keyof VisionPrefs) => {
    const { [key]: _drop, ...rest } = own
    const next = { ...settings.vision, [profile.id]: rest }
    if (!Object.keys(rest).length) delete next[profile.id]
    onSettings({ vision: next })
  }
  const height = Math.round((v.maxWidth * 9) / 16)
  const model = cloud.settings.visionModel
  const estimate = estimatePerHour(model, v.intervalMs, v.maxWidth, height, cachedPromptTokens(!!profile.knowledge.roster))
  const used = cloud.spend.vision
  const measured = used && used.calls >= 5 ? used : null
  const per = (n: number) => Math.round(n / (measured?.calls ?? 1))
  const perHour = (3_600_000 / v.intervalMs) | 0
  const sonnetOrHaiku = /^claude-(sonnet|haiku)-5-5/.test(model)
  const applyCloud = (p: Promise<CloudStatus>) => void p.then(onCloud).catch(() => undefined)
  return (
    <section className="panel" aria-labelledby="screen-reading">
      <div className="panel-head">
        <h2 id="screen-reading">Screen reading: {profile.game.title}</h2>
        <span className={`badge ${v.custom ? 'ok' : 'neutral'}`}>{v.custom ? 'Your settings' : 'Game defaults'}</span>
      </div>
      <p className="small" style={{ marginTop: 0 }}>
        How often the coach looks at your screen for this game, and how sharp each look is. Faster looks catch more (gunfights, quick scouting
        passes) and cost more. Each game keeps its own settings; changes apply from the next look, even during capture.
      </p>
      <div className="grid-2">
        <div className="field">
          <label htmlFor="vision-interval">Look every {(v.intervalMs / 1000).toFixed(1)} s</label>
          <input
            id="vision-interval"
            type="range"
            min={VISION_LIMITS.intervalMs.min}
            max={VISION_LIMITS.intervalMs.max}
            step={500}
            value={v.intervalMs}
            onChange={(e) => set({ intervalMs: Number(e.target.value) })}
          />
          <div className="btn-row">
            <button type="button" className="btn sm ghost" onClick={() => set({ intervalMs: 1000 })}>Fast · 1 s</button>
            <button type="button" className="btn sm ghost" onClick={() => unset('intervalMs')}>Game default · {(pack.sampleIntervalMs / 1000).toFixed(1)} s</button>
            <button type="button" className="btn sm ghost" onClick={() => set({ intervalMs: 6000 })}>Saver · 6 s</button>
          </div>
          <span className="muted small">Shooters and football change fast: 1–2.5 s. Strategy games: 3–5 s. About {perHour.toLocaleString()} looks an hour of play.</span>
        </div>
        <div className="field">
          <span className="label">Looks at once</span>
          <Segmented
            label="Looks at once"
            value={own.maxInFlight ? String(own.maxInFlight) : 'auto'}
            onChange={(x) => (x === 'auto' ? unset('maxInFlight') : set({ maxInFlight: Number(x) }))}
            options={[{ value: 'auto', label: `Auto (${v.maxInFlight})` }, ...[1, 2, 3, 4].map((n) => ({ value: String(n), label: String(n) }))]}
          />
          <span className="muted small">A reply takes a few seconds; with a short interval, more looks at once keep the pace. Auto picks enough for your interval.</span>
        </div>
        <div className="field">
          <span className="label">Detail</span>
          <Segmented
            label="Detail"
            value={String(v.maxWidth)}
            onChange={(x) => (Number(x) === pack.maxWidth ? unset('maxWidth') : set({ maxWidth: Number(x) }))}
            options={VISION_LIMITS.widths.map((w) => ({ value: String(w), label: `${WIDTH_LABEL[w]}${w === pack.maxWidth ? ' (default)' : ''}` }))}
          />
          <span className="muted small">
            Each look is scaled to {v.maxWidth} pixels wide. Lower is cheaper but small icons and text get harder to read; higher helps busy screens.
          </span>
        </div>
        {cloud.available ? (
          <div className="field">
            <span className="label">Thinking before each look</span>
            <Segmented
              label="Thinking before each look"
              value={cloud.settings.visionThinking}
              onChange={(visionThinking) => applyCloud(bridge.setCloudSettings({ visionThinking }))}
              options={[
                { value: 'auto', label: 'Brief (default)' },
                { value: 'off', label: 'Off' },
              ]}
            />
            <span className="muted small">
              {sonnetOrHaiku
                ? 'Off skips thinking on screen readings: faster and cheaper. Reading a screen rarely needs it, but compare a game each way before you decide.'
                : `${modelPrice(model).label} always thinks a little (kept brief). Off applies to Sonnet 5.5 and Haiku 5.5.`}
            </span>
          </div>
        ) : null}
        {cloud.available && profile.intel ? (
          <div className="field">
            <Switch
              checked={cloud.settings.matchupReference}
              onChange={(matchupReference) => applyCloud(bridge.setCloudSettings({ matchupReference }))}
              label="Send only this matchup’s reference"
              hint="Once the opponent’s civilisation is read, each look carries only the two civilisations’ units and buildings (and the shared ones) instead of all of them: about 70% less reference text per look, same names."
            />
          </div>
        ) : null}
      </div>
      <div className="cost-box">
        <div>
          <span className="label">Estimate</span> ≈ {usd(estimate)}/hour with {modelPrice(model).label.replace(/ \(.*\)$/, '')}, before thinking.
        </div>
        {measured ? (
          <div>
            <span className="label">Measured this session</span> {measured.calls} looks, {usd(measured.usd / measured.calls)} each ≈ {usd((measured.usd / measured.calls) * perHour)}/hour at this
            refresh. Per look: {per(measured.input).toLocaleString()} tokens of image and question, {per(measured.cacheRead + measured.cacheWrite).toLocaleString()} of
            cached reference, {per(measured.output).toLocaleString()} written{measured.thinking ? ` (${per(measured.thinking).toLocaleString()} of them thinking)` : ''}.
          </div>
        ) : (
          <div className="muted small">After a few looks with cloud vision on, the measured cost per look and per hour shows here (from the API’s own token counts).</div>
        )}
        {v.custom ? (
          <button type="button" className="btn sm ghost" onClick={() => set(null)}>
            Reset to game defaults
          </button>
        ) : null}
      </div>
    </section>
  )
}

// --- Learning across games (per game) ----------------------------------------------------------

/** What the coach learns from this game's matches, and whether it recalls it at the start of a game. */
function LearningPanel({ settings, onSettings, session, profile }: { settings: CoachSettings; onSettings: (p: Partial<CoachSettings>) => void; session: CoachSession; profile: GameProfile }) {
  const prefs = learningPrefsFor(profile.id, settings)
  const [confirm, setConfirm] = useState(false)
  const [forgot, setForgot] = useState<number | null>(null)
  const set = (patch: LearningPrefs) => onSettings({ learning: { ...settings.learning, [profile.id]: { ...(settings.learning[profile.id] ?? {}), ...patch } } })
  const spec = profile.learning!
  return (
    <section className="panel" aria-labelledby="learning">
      <div className="panel-head">
        <h2 id="learning">Learning: {profile.game.title}</h2>
        <span className={`badge ${prefs.enabled ? 'ok' : 'neutral'}`}>{prefs.enabled ? 'On' : 'Off'}</span>
      </div>
      <p className="small" style={{ marginTop: 0 }}>
        After each game the coach files what your screen showed under {spec.dimensions.map((d) => d.label.toLowerCase()).join(', ')}, and learns{' '}
        {spec.tracks.map((t) => t.label.toLowerCase()).join(', ')}. It keeps this between sessions only with “Remember my games” on (below).
      </p>
      <div className="grid-2">
        <div className="field">
          <Switch checked={prefs.enabled} onChange={(enabled) => set({ enabled })} label="Learn from my games" hint="Off: nothing new is kept for this game; what was learned stays until you forget it." />
        </div>
        <div className="field">
          <Switch
            checked={prefs.recall}
            onChange={(recall) => set({ recall })}
            label="Recall at the start of a game"
            hint={`Once the ${spec.dimensions.filter((d) => d.live).map((d) => d.label.toLowerCase()).join(' or ')} is known: “Your last games against … ”. Only where live coaching is allowed; otherwise in Insights after the match.`}
          />
        </div>
        <div className="field">
          <label htmlFor="learn-min">Recall after {prefs.minGames} {prefs.minGames === 1 ? 'game' : 'games'} of that kind</label>
          <input id="learn-min" type="range" min={LEARNING_MIN_GAMES.min} max={LEARNING_MIN_GAMES.max} step={1} value={prefs.minGames} onChange={(e) => set({ minGames: Number(e.target.value) })} />
          <span className="muted small">More games before anything is said: fewer early hunches from a single game.</span>
        </div>
        <div className="field">
          <span className="label">Forget</span>
          {confirm ? (
            <div className="btn-row">
              <button
                type="button"
                className="btn sm danger"
                onClick={() =>
                  void session.forgetLearned().then((n) => {
                    setForgot(n)
                    setConfirm(false)
                  })
                }
              >
                Forget what was learned
              </button>
              <button type="button" className="btn sm ghost" onClick={() => setConfirm(false)}>
                Cancel
              </button>
            </div>
          ) : (
            <button type="button" className="btn sm ghost danger" onClick={() => setConfirm(true)}>
              Forget what was learned for {profile.game.title}…
            </button>
          )}
          <span className="muted small">{forgot !== null ? `Forgotten from ${forgot} saved ${forgot === 1 ? 'game' : 'games'}.` : 'Scores and other match records stay.'}</span>
        </div>
      </div>
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
        hint={`Press the talk key${info?.talkShortcut ? ` (${info.talkShortcut})` : ''} to start listening (a rising tone), ask, then press it again to send (a falling tone); it stops by itself after 15 seconds. The coach answers out loud. Speech is recognised on this computer; audio is never stored or sent.`}
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

// --- Keyboard -----------------------------------------------------------------------------

function KeyboardPanel({ settings, onSettings, info }: { settings: CoachSettings; onSettings: (p: Partial<CoachSettings>) => void; info: PlatformInfo | null }) {
  const keys = settings.hotkeys
  const set = (name: keyof Hotkeys) => (accel: string) => onSettings({ hotkeys: { ...keys, [name]: accel } })
  const taken = (name: keyof Hotkeys) => (Object.keys(keys) as (keyof Hotkeys)[]).filter((k) => k !== name).map((k) => keys[k])
  const desktop = info?.shell === 'electron'
  return (
    <section className="panel" aria-labelledby="keys">
      <div className="panel-head">
        <h2 id="keys">Keyboard</h2>
        <span className="muted small">These keys work while the game has focus — no alt-tabbing.</span>
      </div>
      <div className="hotkeys">
        <HotkeyField
          label="Talk to the coach"
          hint={settings.voiceInput ? 'Press once to start talking, again to send.' : 'Turn on voice input above to use it.'}
          value={keys.talk}
          taken={taken('talk')}
          onChange={set('talk')}
        />
        <HotkeyField label="Mark this moment" hint="Saves the current screen (in memory) so you can ask about that decision after the match." value={keys.mark} taken={taken('mark')} onChange={set('mark')} />
        <HotkeyField label="Stop capture" hint="Stops watching immediately." value={keys.stop} taken={taken('stop')} onChange={set('stop')} />
      </div>
      {info?.shortcutErrors.map((e) => (
        <Notice key={e} level="warning">
          {e}
        </Notice>
      ))}
      <p className="muted small" style={{ marginBottom: 0 }}>
        {desktop
          ? 'While Sightline runs, the game will not receive these keys, so pick ones your game does not use.'
          : 'Global keys need the desktop app.'}
      </p>
    </section>
  )
}
