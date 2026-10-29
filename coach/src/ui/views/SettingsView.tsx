import { isDiscordWebhook } from '../../core/notify.ts'
import { useCallback, useEffect, useState, useSyncExternalStore } from 'react'
import type { GsiServerStatus, PlatformInfo, SightlineBridge, VoiceModelStatus } from '../../../shared/bridge.ts'
import type { CloudStatus } from '../../../shared/cloud.ts'
import type { GameProfile } from '../../core/profile/schema.ts'
import { COOLDOWN_RANGE, LEARNING_MIN_GAMES, learningPrefsFor, VERBOSITIES, VISION_LIMITS, visionPrefsFor, type CoachSettings, type LearningPrefs, type VisionPrefs } from '../../core/settings.ts'
import type { Verbosity } from '../../core/types.ts'
import { playEstimate } from '../../core/estimate.ts'
import type { UsageSummary } from '../../core/usage.ts'
import { modelPrice, MODELS } from '../../core/vision/cost.ts'
import { DEFAULT_CAPTURE } from '../../capture/sources.ts'
import type { ProfileCatalog } from '../App.tsx'
import { Notice, Segmented, Switch } from '../components/common.tsx'
import { VoicePanel } from '../components/VoicePanel.tsx'
import { MinimapAlign } from '../components/MinimapAlign.tsx'
import { HudAlign } from '../components/HudAlign.tsx'
import { RadarAlign } from '../components/RadarAlign.tsx'
import { RADAR_COLOURS, radarColourHint, type RadarColour } from '../../core/pitch/radar.ts'
import { OfficialMapsPanel } from '../components/OfficialMapsPanel.tsx'
import { HotkeyField } from '../components/HotkeyField.tsx'
import type { Hotkeys } from '../../core/settings.ts'
import { dateShort, usd, usdFine, usdRange } from '../format.ts'
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
  usage: UsageSummary | null
  onUsage: () => void
}

export function SettingsView({ settings, onSettings, session, info, catalog, bridge, cloud, onCloud, profile, usage, onUsage }: Props) {
  return (
    <div className="page">
      <div>
        <h1>Settings</h1>
        <p className="lede">How the coach sounds, when it may speak, how it sees, what it remembers, and how you talk to it.</p>
      </div>

      <CloudPanel bridge={bridge} cloud={cloud} onCloud={onCloud} profile={profile} settings={settings} usage={usage} onUsage={onUsage} />
      {profile.vision ? <ScreenReadingPanel settings={settings} onSettings={onSettings} bridge={bridge} cloud={cloud} onCloud={onCloud} profile={profile} usage={usage} /> : null}
      {profile.learning ? <LearningPanel settings={settings} onSettings={onSettings} session={session} profile={profile} /> : null}
      {profile.hudNumbers?.length && profile.vision ? <HudPanel settings={settings} onSettings={onSettings} session={session} profile={profile} bridge={bridge} /> : null}
      {profile.positioning?.minimap ? <MinimapPanel settings={settings} onSettings={onSettings} session={session} profile={profile} /> : null}
      {profile.positioning?.minimap && profile.knowledge.maps?.length ? <OfficialMapsPanel session={session} profile={profile} info={info} /> : null}
      {profile.pitch ? <RadarPanel settings={settings} onSettings={onSettings} session={session} profile={profile} /> : null}
      {profile.gameState?.kind === 'cs2-gsi' ? <GameDataPanel bridge={bridge} /> : null}
      <MemoryPanel settings={settings} onSettings={onSettings} bridge={bridge} session={session} />
      <DiscordPanel settings={settings} onSettings={onSettings} session={session} />
      <VoiceInputPanel settings={settings} onSettings={onSettings} bridge={bridge} info={info} session={session} />
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

function CloudPanel({ bridge, cloud, onCloud, profile, settings, usage, onUsage }: { bridge: SightlineBridge; cloud: CloudStatus; onCloud: (c: CloudStatus) => void; profile: GameProfile; settings: CoachSettings; usage: UsageSummary | null; onUsage: () => void }) {
  const [key, setKey] = useState('')
  const [error, setError] = useState<string | null>(null)
  const apply = (p: Promise<CloudStatus>) =>
    p.then((c) => {
      setError(null)
      onCloud(c)
    }).catch((e: unknown) => setError(e instanceof Error ? e.message.replace(/^Error invoking remote method '[^']+': (Error: )?/, '') : String(e)))
  const est = playEstimate(profile, settings, cloud.settings, usage)

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
              {est ? (
                <span className="muted small">
                  {profile.game.title}: one look every {(est.intervalMs / 1000).toFixed(1)} s ≈ {usdRange(est.perHour.low, est.perHour.high)}/hour
                  {est.measured ? ` (measured from your ${est.measured.looks.toLocaleString()} looks)` : ' (estimate)'}. With each model:{' '}
                  {MODELS.map((m) => {
                    const e = playEstimate(profile, settings, cloud.settings, usage, m.id)!
                    return `${m.label.split(' (')[0].replace('Claude ', '')} ${usdRange(e.perHour.low, e.perHour.high)}`
                  }).join(' · ')}
                  . Haiku is far cheaper; Opus reads busy screens best.
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
                All cloud calls stop for the session at the cap. This session: {usd(cloud.spend.usd)} over {cloud.spend.calls} calls (estimated). A monthly budget, the
                history and the prices are in{' '}
                <button type="button" className="link-btn" onClick={onUsage}>
                  Usage & cost
                </button>
                .
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
function ScreenReadingPanel({ settings, onSettings, bridge, cloud, onCloud, profile, usage }: { settings: CoachSettings; onSettings: (p: Partial<CoachSettings>) => void; bridge: SightlineBridge; cloud: CloudStatus; onCloud: (c: CloudStatus) => void; profile: GameProfile; usage: UsageSummary | null }) {
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
  const model = cloud.settings.visionModel
  const est = playEstimate(profile, settings, cloud.settings, usage)!
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
          <span className="label">Estimate</span> {usdFine(est.listPerLook.low)}–{usdFine(est.listPerLook.high)} a look ≈ {usdRange(est.listPerHour.low, est.listPerHour.high)}/hour with{' '}
          {modelPrice(model).label.replace(/ \(.*\)$/, '')} at list prices (from no thinking to a little), about {usdRange(est.listPerMatch.low, est.listPerMatch.high)} for a typical{' '}
          {est.matchMinutes}-minute match. Still screens are skipped, so real play usually costs less.
        </div>
        {est.measured ? (
          <div>
            <span className="label">Measured, all your games</span> {est.measured.looks.toLocaleString()} looks of {profile.game.title} with this model, {usdFine(est.measured.usdPerLook)} each ≈{' '}
            {usd(est.measured.perHour)}/hour at this refresh.
          </div>
        ) : null}
        {measured ? (
          <div>
            <span className="label">Measured this session</span> {measured.calls} looks, {usdFine(measured.usd / measured.calls)} each ≈ {usd((measured.usd / measured.calls) * perHour)}/hour at this
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

// --- The minimap: where it is, and the maps stitched from it --------------------------------------

/** Reading the HUD's numbers on this PC: where they are, and how well each agrees with the cloud model. */
function HudPanel({ settings, onSettings, session, profile, bridge }: { settings: CoachSettings; onSettings: (p: Partial<CoachSettings>) => void; session: CoachSession; profile: GameProfile; bridge: SightlineBridge }) {
  const state = useSyncExternalStore(session.subscribe, session.getState)
  const [marking, setMarking] = useState(false)
  const [forgot, setForgot] = useState(false)
  const specs = profile.hudNumbers ?? []
  const boxes = settings.hud[profile.id] ?? {}
  const marked = specs.filter((s) => boxes[s.key])
  const live = state.status?.vision.hud ?? null
  const fieldStats = (key: string) => live?.fields.find((f) => f.key === key) ?? null
  const trusted = live?.fields.filter((f) => f.trusted).length ?? 0
  return (
    <section className="panel" aria-labelledby="hud-h">
      <div className="panel-head">
        <h2 id="hud-h">Read numbers on this PC</h2>
        <span className={`badge ${live?.saving ? 'ok' : marked.length ? 'info' : 'neutral'}`}>
          {live ? (live.saving ? 'All read here' : `${trusted} of ${live.fields.length} read here`) : marked.length ? `${marked.length} marked` : 'Not set up'}
        </span>
      </div>
      <p className="muted small" style={{ marginTop: 0 }}>
        Your HUD's numbers (resources, population, the clock) never move, so once the coach knows your digits it reads them itself every second, for free, instead of waiting for the cloud model. It learns the digits from what the
        model reads, and uses its own reading of a number only after the two have agreed on your screen at least 8 times and 95% of the time. Until then nothing changes. The digit shapes (tiny grey patterns, no pictures) are kept on this PC.
      </p>
      <div className="btn-row">
        <button type="button" className="btn" onClick={() => setMarking(true)}>
          {marked.length ? 'Change where the numbers are' : 'Mark where the numbers are'}
        </button>
        <button
          type="button"
          className="btn ghost"
          onClick={() =>
            void bridge.saveHudBook(profile.id, { v: 1, chars: {} }).then(() => {
              setForgot(true)
              setTimeout(() => setForgot(false), 3000)
            })
          }
        >
          {forgot ? 'Forgotten' : 'Forget learned digits'}
        </button>
      </div>
      {marked.length ? (
        <table className="hud-table small">
          <thead>
            <tr>
              <th>Number</th>
              <th>Agreed with the model</th>
              <th>Read here</th>
              <th>Last read</th>
            </tr>
          </thead>
          <tbody>
            {marked.map((s) => {
              const f = fieldStats(s.key)
              return (
                <tr key={s.key}>
                  <td>{s.label}</td>
                  <td>{f ? (f.checks ? `${f.agreed} of ${f.checks}${f.recent !== null ? ` (recently ${Math.round(f.recent * 100)}%)` : ''}` : 'learning the digits…') : '—'}</td>
                  <td>{f ? (f.trusted ? '✓ yes' : 'not yet') : '—'}</td>
                  <td>{f?.last ?? '—'}</td>
                </tr>
              )
            })}
          </tbody>
        </table>
      ) : null}
      {marked.length && !live ? <p className="muted small">The table fills in while you play (with cloud features on).</p> : null}
      {live ? <p className="muted small">Digits known: {live.known.length ? live.known.join(' ') : 'none yet'}.</p> : null}
      <div style={{ marginTop: 12 }}>
        <Switch
          checked={settings.hudSaves}
          onChange={(hudSaves) => onSettings({ hudSaves })}
          label={<b>Save cost once every marked number is read here</b>}
          hint="The cloud model is then asked half as often (it still reads fights, scouting and everything else). If any number stops agreeing with the model, it goes back to the normal rate."
        />
      </div>
      {marking ? (
        <HudAlign
          session={session}
          specs={specs}
          initial={boxes}
          onSave={(b) => onSettings({ hud: { ...settings.hud, [profile.id]: b } })}
          onClose={() => setMarking(false)}
        />
      ) : null}
    </section>
  )
}

function MinimapPanel({ settings, onSettings, session, profile }: { settings: CoachSettings; onSettings: (p: Partial<CoachSettings>) => void; session: CoachSession; profile: GameProfile }) {
  const state = useSyncExternalStore(session.subscribe, session.getState)
  const spec = profile.positioning!.minimap!
  const own = settings.minimap[profile.id]
  const box = own ?? spec.region
  const [aligning, setAligning] = useState(false)
  const [confirm, setConfirm] = useState<string | null>(null)
  const fixedKey = spec.fixedContextKey
  const rotating = fixedKey ? settings.context[profile.id]?.[fixedKey] === 'rotating' : false
  const maps = Object.values(state.mosaics)
    .filter((m) => !m.synthetic)
    .sort((a, b) => b.updatedAt.localeCompare(a.updatedAt))
  const setBox = (b: typeof box | null) => {
    const next = { ...settings.minimap }
    if (b) next[profile.id] = b
    else delete next[profile.id]
    onSettings({ minimap: next })
  }
  return (
    <section className="panel" aria-labelledby="minimap-h">
      <div className="panel-head">
        <h2 id="minimap-h">Minimap: {profile.game.title}</h2>
        <span className={`badge ${rotating ? 'warn' : 'ok'}`}>{rotating ? 'Rotating: not stitched' : 'Fixed: stitched'}</span>
      </div>
      <p className="small" style={{ marginTop: 0 }}>
        With a fixed (north-up) minimap the coach stitches each map from your minimap as you move, then pins where you die, get kills and where killers were to the spot,
        overhead and in 3D (Insights → The map). Only your screen’s pixels are read. Set fixed or rotating on the Coach screen.
      </p>
      <div className="grid-2">
        <div className="field">
          <span className="label">Where your minimap is</span>
          <span className="small">
            {own ? 'Your alignment' : 'The game’s default'}: {Math.round(box.x * 1000) / 10}% from the left, {Math.round(box.y * 1000) / 10}% from the top,{' '}
            {Math.round(box.w * 1000) / 10}% wide{box.round ? ', round' : ''}.
          </span>
          <div className="btn-row">
            <button type="button" className="btn sm" disabled={state.phase !== 'capturing'} onClick={() => setAligning(true)}>
              Align on the screen…
            </button>
            {own ? (
              <button type="button" className="btn sm ghost" onClick={() => setBox(null)}>
                Use the game’s default
              </button>
            ) : null}
          </div>
          <span className="muted small">{state.phase === 'capturing' ? 'Shows a still of the capture with the box.' : 'Start capture (or the demo) to align it on a still.'}</span>
        </div>
        <div className="field">
          <span className="label">Maps learned ({maps.length})</span>
          {maps.length ? (
            <ul className="plain small map-list">
              {maps.map((m) => (
                <li key={m.map}>
                  <b>{m.map}</b> <span className="muted">· {Object.keys(m.callouts).length} callouts placed · updated {dateShort(m.updatedAt)}</span>{' '}
                  {confirm === m.map ? (
                    <>
                      <button
                        type="button"
                        className="btn sm danger"
                        onClick={() => {
                          void session.forgetMosaic(m.map)
                          setConfirm(null)
                        }}
                      >
                        Forget it
                      </button>{' '}
                      <button type="button" className="btn sm ghost" onClick={() => setConfirm(null)}>
                        Keep
                      </button>
                    </>
                  ) : (
                    <button type="button" className="btn sm ghost danger" disabled={state.phase !== 'idle'} onClick={() => setConfirm(m.map)}>
                      Forget…
                    </button>
                  )}
                </li>
              ))}
            </ul>
          ) : (
            <span className="muted small">None yet. Play a match with a fixed minimap.</span>
          )}
          <span className="muted small">Kept on this computer: the minimap’s picture of each map, and callouts and heights placed on it. Deleted with “Forget everything”.</span>
        </div>
      </div>
      {aligning ? <MinimapAlign session={session} initial={box} packDefault={spec.region} onSave={setBox} onClose={() => setAligning(false)} /> : null}
    </section>
  )
}

// --- Counter-Strike 2 Game State Integration: the game's own data, on this computer only -----------

const RADAR_CONSOLE = 'cl_radar_rotate 0; cl_radar_always_centered 1; cl_radar_scale 0.5; cl_radar_scale_dynamic 0'

function GameDataPanel({ bridge }: { bridge: SightlineBridge }) {
  const [status, setStatus] = useState<GsiServerStatus | null>(null)
  const [port, setPort] = useState('')
  const [cfg, setCfg] = useState<{ text: string; fileName: string; folder: string | null } | null>(null)
  const [saved, setSaved] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  useEffect(() => {
    let live = true
    const poll = () =>
      void bridge.gsiStatus().then((s) => {
        if (!live || !s) return
        setStatus(s)
        setPort((p) => p || String(s.port))
      })
    poll()
    const id = setInterval(poll, 3000)
    void bridge.gsiCfg().then((c) => live && setCfg(c))
    return () => {
      live = false
      clearInterval(id)
    }
  }, [bridge])
  const apply = async (enabled: boolean) => {
    setBusy(true)
    const p = Number(port)
    const s = await bridge.gsiConfigure({ enabled, port: Number.isInteger(p) && p >= 1024 && p <= 65535 ? p : (status?.port ?? 31982) })
    if (s) setStatus(s)
    setCfg(await bridge.gsiCfg())
    setBusy(false)
  }
  const copy = (text: string) => void navigator.clipboard?.writeText(text).catch(() => undefined)
  const available = status?.available !== false
  return (
    <section className="panel" aria-labelledby="gsi-h">
      <div className="panel-head">
        <h2 id="gsi-h">Game data: Counter-Strike 2</h2>
        <span className={`badge ${status?.listening ? 'ok' : ''}`}>{!available ? 'Desktop app only' : status?.listening ? 'Listening' : status?.enabled ? 'Not listening' : 'Off'}</span>
      </div>
      <p className="small" style={{ marginTop: 0 }}>
        Counter-Strike 2’s own Game State Integration sends your rounds, money, health, weapons and kills to an address on this computer. With it on, the coach
        counts every kill and death exactly, knows when you were flashed or reloading, and keeps each round’s buy and result. While you play the game sends only your own
        state: no positions, no other players, nothing hidden, so it stays fair. The listener is on 127.0.0.1 only, needs this computer’s token, and nothing it receives
        is stored except in your match records.
      </p>
      {!available ? (
        <Notice level="info">Game State Integration needs the desktop app (it listens for the game on this computer).</Notice>
      ) : (
        <div className="grid-2">
          <div className="field">
            <Switch checked={!!status?.enabled} onChange={(on) => void apply(on)} label="Listen for the game’s data" hint={status?.error ?? undefined} />
            <label className="small">
              Port{' '}
              <input type="number" min={1024} max={65535} value={port} onChange={(e) => setPort(e.target.value)} style={{ width: 90 }} aria-label="Port" />{' '}
              <button type="button" className="btn sm ghost" disabled={busy || String(status?.port) === port} onClick={() => void apply(!!status?.enabled)}>
                Use this port
              </button>
            </label>
            <span className="muted small">
              {status?.listening
                ? status.lastAt
                  ? `${status.updates} updates; the last ${Math.max(0, Math.round((Date.now() - status.lastAt) / 1000))} s ago.`
                  : 'Waiting for the game: save the file below and restart Counter-Strike 2.'
                : 'Off.'}
            </span>
          </div>
          <div className="field">
            <span className="label">Set it up once</span>
            <ol className="small" style={{ margin: '4px 0', paddingLeft: 18 }}>
              <li>
                Save the file into the game’s cfg folder{cfg?.folder ? ` (found: ${cfg.folder})` : ' (…\\steamapps\\common\\Counter-Strike Global Offensive\\game\\csgo\\cfg)'}.{' '}
                <button
                  type="button"
                  className="btn sm"
                  onClick={() =>
                    void bridge.gsiSaveCfg().then((r) => setSaved(r.ok ? `Saved: ${r.path}` : r.error ? r.error : null))
                  }
                >
                  Save the cfg file…
                </button>
              </li>
              <li>Restart Counter-Strike 2: it reads the file at launch (the console shows “Loading Game State Integration”).</li>
              <li>Start capture on the Coach screen with this pack: rounds and money appear there.</li>
            </ol>
            {saved ? <span className="small">{saved}</span> : null}
            {cfg?.text ? (
              <details className="small">
                <summary>Show the file ({cfg.fileName})</summary>
                <pre className="code-block">{cfg.text}</pre>
                <button type="button" className="btn sm ghost" onClick={() => copy(cfg.text)}>
                  Copy
                </button>
              </details>
            ) : null}
          </div>
        </div>
      )}
      <div className="field" style={{ marginTop: 10 }}>
        <span className="label">The radar, for the death map</span>
        <span className="small">
          Settings › Game › Radar / Tablet: Radar Is Rotating: No, Radar Centers The Player: Yes, Radar Map Zoom about 0.5. Or paste into the console:{' '}
          <code>{RADAR_CONSOLE}</code>{' '}
          <button type="button" className="btn sm ghost" onClick={() => copy(RADAR_CONSOLE)}>
            Copy
          </button>
        </span>
      </div>
    </section>
  )
}

// --- The radar (football): where it is, its colours, what the game should be set to ---------------

function RadarPanel({ settings, onSettings, session, profile }: { settings: CoachSettings; onSettings: (p: Partial<CoachSettings>) => void; session: CoachSession; profile: GameProfile }) {
  const state = useSyncExternalStore(session.subscribe, session.getState)
  const spec = profile.pitch!
  const own = settings.minimap[profile.id]
  const box = own ?? spec.radar
  const [aligning, setAligning] = useState(false)
  const ctx = settings.context[profile.id] ?? {}
  const colour = (k: string | undefined) => String((k ? ctx[k] : undefined) ?? 'default')
  const ours = colour(spec.oursColourKey)
  const theirs = colour(spec.theirsColourKey)
  const hint = radarColourHint(ours, theirs)
  const known = (c: string): RadarColour | null => (c in RADAR_COLOURS ? (c as RadarColour) : null)
  const setBox = (b: typeof box | null) => {
    const next = { ...settings.minimap }
    if (b) next[profile.id] = b
    else delete next[profile.id]
    onSettings({ minimap: next })
  }
  const live = state.report?.profileId === profile.id ? state.report.pitch?.live : null
  return (
    <section className="panel" aria-labelledby="radar-h">
      <div className="panel-head">
        <h2 id="radar-h">Radar: {profile.game.title}</h2>
        <span className={`badge ${hint ? 'warn' : 'ok'}`}>{hint ? 'Check the colours' : 'Colours set'}</span>
      </div>
      <p className="small" style={{ marginTop: 0 }}>
        The coach reads every player and the ball from the 2D radar at the bottom of the screen, about twice a second: team shapes, the back line, possession, lost balls,
        shots and the seconds before each goal (Insights → The pitch). Only your screen’s pixels are read.
      </p>
      <div className="grid-2">
        <div className="field">
          <span className="label">Where the radar is</span>
          <span className="small">
            {own ? 'Your alignment' : 'The game’s default'}: {Math.round(box.x * 1000) / 10}% from the left, {Math.round(box.y * 1000) / 10}% from the top,{' '}
            {Math.round(box.w * 1000) / 10}% wide, {Math.round(box.h * 1000) / 10}% high.
          </span>
          <div className="btn-row">
            <button type="button" className="btn sm" disabled={state.phase !== 'capturing'} onClick={() => setAligning(true)}>
              Align on the screen…
            </button>
            {own ? (
              <button type="button" className="btn sm ghost" onClick={() => setBox(null)}>
                Use the game’s default
              </button>
            ) : null}
          </div>
          <span className="muted small">
            {state.phase === 'capturing'
              ? live?.ok
                ? `Reading now: ${live.ours.length} and ${live.theirs.length} players${live.ball ? ' and the ball' : ''}.`
                : 'Not reading the radar right now (a replay or menu, or the box is off).'
              : 'Start capture (or the demo) to align it on a still.'}
          </span>
        </div>
        <div className="field">
          <span className="label">In the game (Settings › Game Settings › Visual)</span>
          <ul className="small" style={{ margin: '4px 0', paddingLeft: 18 }}>
            <li>Radar: 2D (3D is drawn in perspective and is not measured; Off turns this off).</li>
            <li>
              User Radar Colour and Opponent Radar Colour: two strong, different colours, such as Blue and Red. Then pick the same on the Coach screen (now: {ours} and {theirs}).
            </li>
            <li>Score Clock: On, so goals are counted.</li>
          </ul>
          {hint ? <p className="warn-text small">⚠ {hint}</p> : null}
        </div>
      </div>
      {aligning ? (
        <RadarAlign
          session={session}
          initial={box}
          packDefault={spec.radar}
          colours={{ ours: known(ours), theirs: known(theirs) }}
          onSave={setBox}
          onClose={() => setAligning(false)}
        />
      ) : null}
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

function DiscordPanel({ settings, onSettings, session }: { settings: CoachSettings; onSettings: (p: Partial<CoachSettings>) => void; session: CoachSession }) {
  const d = settings.discord
  const [draft, setDraft] = useState(d.webhook ?? '')
  const [status, setStatus] = useState<string | null>(null)
  const valid = isDiscordWebhook(draft.trim())
  return (
    <section className="panel" aria-labelledby="discord-h">
      <div className="panel-head">
        <h2 id="discord-h">Match summaries on your phone (Discord)</h2>
        <span className={`badge ${d.webhook && d.afterMatch ? 'ok' : 'neutral'}`}>{d.webhook ? (d.afterMatch ? 'On' : 'Paused') : 'Off'}</span>
      </div>
      <p className="muted small" style={{ marginTop: 0 }}>
        After each match you play (not demos), the coach posts a short summary to a Discord channel of yours: the result, the numbers it read, and the review's points to work on. The Discord app shows it on your phone. In Discord: channel settings → Integrations → Webhooks → New webhook → Copy webhook URL. Only that text is sent, and only there.
      </p>
      <div className="btn-row">
        <input type="password" placeholder="https://discord.com/api/webhooks/…" value={draft} onChange={(e) => setDraft(e.target.value)} style={{ flex: 1 }} aria-label="Discord webhook URL" />
        <button type="button" className="btn" disabled={!valid || draft.trim() === d.webhook} onClick={() => (onSettings({ discord: { ...d, webhook: draft.trim() } }), setStatus('Saved.'))}>
          Save
        </button>
        {d.webhook ? (
          <button type="button" className="btn ghost" onClick={() => (onSettings({ discord: { ...d, webhook: null } }), setDraft(''), setStatus('Removed.'))}>
            Remove
          </button>
        ) : null}
      </div>
      {draft.trim() && !valid ? <p className="small warn-text">That is not a Discord webhook address (https://discord.com/api/webhooks/…).</p> : null}
      {d.webhook ? (
        <>
          <Switch checked={d.afterMatch} onChange={(afterMatch) => onSettings({ discord: { ...d, afterMatch } })} label={<b>Send a summary after each match</b>} />
          <button type="button" className="btn sm" onClick={() => void session.testDiscord().then((r) => setStatus(r.ok ? 'Sent: check the channel.' : r.error))}>
            Send a test message
          </button>
        </>
      ) : null}
      {status ? <p className="small muted">{status}</p> : null}
    </section>
  )
}

// --- Voice input -------------------------------------------------------------------------

function VoiceInputPanel({
  settings,
  onSettings,
  bridge,
  info,
  session,
}: {
  settings: CoachSettings
  onSettings: (p: Partial<CoachSettings>) => void
  bridge: SightlineBridge
  info: PlatformInfo | null
  session: CoachSession
}) {
  const [status, setStatus] = useState<VoiceModelStatus | null>(null)
  const refresh = useCallback(() => void bridge.voiceStatus().then(setStatus), [bridge])
  useEffect(refresh, [refresh])
  // A different model chosen: the app checks whether that one is installed (after saving the choice).
  useEffect(() => {
    const t = setTimeout(refresh, 400)
    return () => clearTimeout(t)
  }, [refresh, settings.speechModel])
  useEffect(() => {
    if (!status?.installing) return
    const t = setInterval(refresh, 500)
    return () => clearInterval(t)
  }, [status?.installing, refresh])
  const desktop = info?.shell === 'electron'
  // The speech model just finished downloading: always listening can start now.
  const installed = status?.installed === true
  useEffect(() => {
    if (installed && session.getState().listen.status === 'error') session.retryListening()
  }, [installed, session])
  return (
    <section className="panel" aria-labelledby="vin">
      <div className="panel-head">
        <h2 id="vin">Voice input</h2>
        <span className={`badge ${settings.voiceInput && status?.installed ? 'ok' : 'neutral'}`}>{settings.voiceInput && status?.installed ? 'Ready' : 'Off'}</span>
      </div>
      <Switch
        checked={settings.voiceInput}
        onChange={(voiceInput) => onSettings({ voiceInput })}
        label={<b>Ask the coach out loud</b>}
        hint="The coach answers out loud. Speech is recognised on this computer; audio is never stored or sent. Only the words of a question go to Claude, as with a typed one."
      />
      {settings.voiceInput ? (
        <>
          <div className="field" style={{ marginTop: 12 }}>
            <span className="small">
              <b>How you talk to it</b>
            </span>
            <Segmented
              label="How you talk to the coach"
              value={settings.listen}
              options={[
                { value: 'key', label: 'Talk key' },
                { value: 'always', label: 'Always listening' },
              ]}
              onChange={(listen) => onSettings({ listen })}
            />
            <span className="muted small">
              {settings.listen === 'key'
                ? `Hold the talk key${info?.talkShortcut ? ` (${info.talkShortcut})` : ''} while you ask and let go to send, or tap it, ask, and tap it again. A rising tone means it is listening, a falling tone that it is thinking. It stops by itself after 15 seconds. The microphone is open only while you talk.`
                : 'The microphone stays open while the app runs, and it hears you whenever you speak (it pauses while the coach itself is talking). The talk key still works. Best with a headset: through speakers, game sound can be taken for speech.'}
            </span>
          </div>
          {settings.listen === 'always' ? (
            <>
              <Switch
                checked={settings.wakeWord}
                onChange={(wakeWord) => onSettings({ wakeWord })}
                label={<b>Only when I start with “Coach”</b>}
                hint="“Coach, how many villagers should I have?” is asked; talk to your team is not. Say just “Coach” and it waits a few seconds for the question. Turn this off to have everything you say taken as a question."
              />
              <ListeningStatus session={session} />
            </>
          ) : null}
        </>
      ) : null}
      {settings.voiceInput && desktop && status ? (
        <div className="voice-model">
          <div className="field" style={{ marginBottom: 8 }}>
            <span className="small">
              <b>Speech recognition</b>
            </span>
            <Segmented
              label="Speech recognition model"
              value={settings.speechModel}
              options={[
                { value: 'tiny', label: 'Fast (Moonshine tiny, 108 MB)' },
                { value: 'base', label: 'More accurate (Moonshine base, 251 MB)' },
              ]}
              onChange={(speechModel) => onSettings({ speechModel })}
            />
            <span className="muted small">
              Measured on 45 spoken test questions: fast took 44 the right way (question or not), about 0.2 s each; more accurate took all 45, about 0.4 s each. Real voices tend to favour the bigger one more.
            </span>
          </div>
          {status.installed && status.vad ? (
            <div className="btn-row">
              <span className="small">
                Installed: {settings.speechModel === 'base' ? 'Moonshine base' : 'Moonshine tiny'} (English) and the voice detector that keeps game sound out.
              </span>
              <button type="button" className="btn sm ghost" onClick={() => void bridge.removeVoiceModel().then(setStatus)}>
                Remove model
              </button>
            </div>
          ) : status.installed && !status.installing ? (
            <div className="btn-row">
              <span className="small">Speech recognition installed. The voice detector (0.6 MB) that keeps game sound out is not.</span>
              <button type="button" className="btn sm" onClick={() => void bridge.installVoiceModel().then(setStatus)}>
                Add it
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

/** Always listening: whether the microphone is open, a live input meter, and the last thing heard. */
function ListeningStatus({ session }: { session: CoachSession }) {
  const state = useSyncExternalStore(session.subscribe, session.getState)
  const l = state.listen
  const [meter, setMeter] = useState<{ level: number; threshold: number; speaking: boolean } | null>(null)
  useEffect(() => {
    if (l.status !== 'on') return setMeter(null)
    const t = setInterval(() => setMeter(session.listenLevel()), 120)
    return () => clearInterval(t)
  }, [l.status, session])
  const pct = (v: number) => `${Math.min(100, Math.round(Math.sqrt(v / 0.3) * 100))}%`
  return (
    <div className="listen-status">
      <div className="btn-row small">
        <span className={`badge ${l.status === 'on' ? 'ok' : l.status === 'error' ? 'warn' : 'neutral'}`}>
          {l.status === 'on' ? 'Listening' : l.status === 'starting' ? 'Opening the microphone…' : l.status === 'paused' ? 'Paused (top bar)' : l.status === 'error' ? 'Not listening' : 'Off'}
        </span>
        {l.status === 'error' || l.status === 'paused' ? (
          <button type="button" className="btn sm ghost" onClick={() => (l.status === 'error' ? session.retryListening() : session.setListeningPaused(false))}>
            {l.status === 'error' ? 'Try again' : 'Listen again'}
          </button>
        ) : null}
      </div>
      {meter ? (
        <div className="listen-meter" aria-hidden="true" title="Your microphone level; speech must cross the line">
          <span className={meter.speaking ? 'speaking' : ''} style={{ width: pct(meter.level) }} />
          <i style={{ left: pct(meter.threshold) }} />
        </div>
      ) : null}
      {l.error ? <Notice level="warning">{l.error}</Notice> : null}
      {l.heard ? (
        <p className="small muted" style={{ margin: '6px 0 0' }}>
          Last heard: “{l.heard.text}”{' '}
          {l.heard.kind === 'question' ? '(asked)' : l.heard.kind === 'wake' ? '(waiting for your question)' : '(not for the coach)'}
        </p>
      ) : null}
    </div>
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
          hint={settings.voiceInput ? 'Hold it while you ask and let go, or tap it, ask, and tap it again.' : 'Turn on voice input above to use it.'}
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
