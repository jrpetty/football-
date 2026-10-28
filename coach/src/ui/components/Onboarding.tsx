// First-run setup: connect Claude (API key, tested), pick the game, choose a
// natural voice and the talk key. Every step can be skipped and changed later
// in Settings.

import { useEffect, useState } from 'react'
import type { PlatformInfo, SightlineBridge } from '../../../shared/bridge.ts'
import type { CloudStatus } from '../../../shared/cloud.ts'
import type { TtsStatus } from '../../../shared/voices.ts'
import type { GameProfile } from '../../core/profile/schema.ts'
import type { CoachSettings } from '../../core/settings.ts'
import { estimateRange, MODELS } from '../../core/vision/cost.ts'
import { usdRange } from '../format.ts'
import type { CoachSession } from '../session.ts'
import { HotkeyField } from './HotkeyField.tsx'
import { Notice, Switch } from './common.tsx'

const STEPS = ['Welcome', 'Connect Claude', 'Your game', 'Voice', 'Ready'] as const

interface Props {
  bridge: SightlineBridge
  session: CoachSession
  info: PlatformInfo | null
  cloud: CloudStatus
  onCloud: (c: CloudStatus) => void
  settings: CoachSettings
  onSettings: (patch: Partial<CoachSettings>) => void
  profiles: readonly GameProfile[]
  onDone: () => void
}

export function Onboarding(p: Props) {
  const [step, setStep] = useState(0)
  const desktop = p.info?.shell === 'electron'
  const next = () => setStep((s) => Math.min(STEPS.length - 1, s + 1))
  const finish = () => {
    p.onSettings({ onboarded: true })
    p.onDone()
  }
  return (
    <div className="backdrop onboarding-backdrop">
      <div className="modal onboarding" role="dialog" aria-modal="true" aria-label="Set up Sightline">
        <ol className="ob-steps" aria-label="Setup steps">
          {STEPS.map((s, i) => (
            <li key={s} className={i === step ? 'current' : i < step ? 'done' : ''}>
              <span className="ob-dot">{i < step ? '✓' : i + 1}</span>
              {s}
            </li>
          ))}
        </ol>
        <div className="ob-body">
          {step === 0 && <Welcome />}
          {step === 1 && <ConnectStep {...p} desktop={desktop} />}
          {step === 2 && <GameStep {...p} />}
          {step === 3 && <VoiceStep {...p} desktop={desktop} />}
          {step === 4 && <ReadyStep {...p} />}
        </div>
        <div className="ob-foot">
          <button type="button" className="btn ghost" onClick={finish}>
            {step === STEPS.length - 1 ? 'Close' : 'Skip setup'}
          </button>
          <div className="spacer" />
          {step > 0 && step < STEPS.length - 1 ? (
            <button type="button" className="btn" onClick={() => setStep((s) => s - 1)}>
              Back
            </button>
          ) : null}
          {step < STEPS.length - 1 ? (
            <button type="button" className="btn primary" onClick={next}>
              {step === 0 ? 'Get started' : 'Continue'}
            </button>
          ) : (
            <button type="button" className="btn primary" onClick={finish}>
              Start coaching
            </button>
          )}
        </div>
      </div>
    </div>
  )
}

function Welcome() {
  return (
    <div className="ob-welcome">
      <div className="ob-logo" aria-hidden="true">
        <svg viewBox="0 0 24 24" width="40" height="40">
          <circle cx="12" cy="12" r="4" fill="currentColor" />
          <path d="M2 12c3-5 6.5-7.5 10-7.5S19 7 22 12c-3 5-6.5 7.5-10 7.5S5 17 2 12Z" fill="none" stroke="currentColor" strokeWidth="2" />
        </svg>
      </div>
      <h1>Welcome to Sightline</h1>
      <p className="lede">An AI coach that watches your game with you, remembers your matches, and talks you through them.</p>
      <ul className="ob-points">
        <li>
          <b>Sees only what you share.</b> It uses the same screen-capture route as OBS and Discord. It never touches the game: no memory reading, no injection, no overlays, no input.
        </li>
        <li>
          <b>Talks with you.</b> A natural voice that runs on your PC, and a key you press to ask it anything.
        </li>
        <li>
          <b>Plays fair.</b> Live tips where the game allows them (vs AI, campaigns); in ranked and online play it saves its advice for after the match.
        </li>
      </ul>
    </div>
  )
}

function ConnectStep({ bridge, cloud, onCloud, desktop }: Props & { desktop: boolean }) {
  const [key, setKey] = useState('')
  const [state, setState] = useState<{ kind: 'idle' | 'testing' } | { kind: 'ok'; models: string[] } | { kind: 'error'; error: string }>({ kind: 'idle' })
  const test = async () => {
    setState({ kind: 'testing' })
    try {
      if (key.trim()) onCloud(await bridge.setApiKey(key.trim()))
      const r = await bridge.testCloud()
      if (r.ok) {
        setState({ kind: 'ok', models: r.models })
        setKey('')
        onCloud(await bridge.setCloudSettings({ enabled: true }))
      } else setState({ kind: 'error', error: r.error })
    } catch (e) {
      setState({ kind: 'error', error: e instanceof Error ? e.message.replace(/^Error invoking remote method '[^']+': (Error: )?/, '') : String(e) })
    }
  }
  if (!desktop) return <Notice level="info">Connecting Claude needs the desktop app: your key is kept by the app and never reaches a web page.</Notice>
  return (
    <div className="ob-section">
      <h2>Connect Claude</h2>
      <p className="small">
        Claude reads the game screen (scores, menus, minimaps, banners), answers your questions and researches your games. You use your own Anthropic API key; it is
        stored encrypted on this PC and only ever sent to Anthropic.
      </p>
      <ol className="small muted ob-howto">
        <li>Open console.anthropic.com and sign in.</li>
        <li>Go to API keys → Create key, and copy it.</li>
        <li>Paste it here.</li>
      </ol>
      <form
        className="ask-input"
        onSubmit={(e) => {
          e.preventDefault()
          void test()
        }}
      >
        <input type="password" autoComplete="off" placeholder={cloud.key.set ? `Saved key sk-ant-…${cloud.key.last4} (paste to replace)` : 'sk-ant-…'} value={key} onChange={(e) => setKey(e.target.value)} />
        <button type="submit" className="btn primary" disabled={state.kind === 'testing' || (!key.trim() && !cloud.key.set)}>
          {state.kind === 'testing' ? 'Testing…' : key.trim() || !cloud.key.set ? 'Save & test' : 'Test again'}
        </button>
      </form>
      {state.kind === 'ok' ? (
        <div className="ob-ok">
          ✓ Connected. Your key can use {state.models.filter((m) => MODELS.some((x) => x.id === m)).length || state.models.length} Claude models. Cloud features are on.
        </div>
      ) : null}
      {state.kind === 'error' ? <Notice level="error">{state.error}</Notice> : null}
      {cloud.key.set ? (
        <div className="field" style={{ marginTop: 14 }}>
          <label htmlFor="ob-model">Model for reading the screen</label>
          <select id="ob-model" value={cloud.settings.visionModel} onChange={(e) => void bridge.setCloudSettings({ visionModel: e.target.value, answerModel: e.target.value }).then(onCloud)}>
            {MODELS.map((m) => {
              const r = estimateRange(m.id, { intervalMs: 3000, width: 1024, height: 576, cachedTokens: 5000, thinkingOff: false })
              return (
                <option key={m.id} value={m.id}>
                  {m.label} — about {usdRange(r.low, r.high)}/hour
                </option>
              )
            })}
          </select>
          <span className="muted small">Estimates at list prices for one look every 3 s. Unchanged screens are skipped, a spending cap per session protects you, and Usage & cost shows what you actually spend.</span>
        </div>
      ) : null}
    </div>
  )
}

function GameStep({ settings, onSettings, profiles }: Props) {
  const profile = profiles.find((x) => x.id === settings.profileId) ?? profiles[0]
  return (
    <div className="ob-section">
      <h2>What do you play?</h2>
      <p className="small muted">Pick one to start; Sightline also suggests the right pack when you choose a game window.</p>
      <div className="pack-cards" role="radiogroup" aria-label="Game pack">
        {profiles
          .filter((x) => x.id !== 'synthetic-arena')
          .map((x) => (
            <button key={x.id} type="button" role="radio" aria-checked={x.id === profile.id} className="pack-card" onClick={() => onSettings({ profileId: x.id, profileModeId: null })}>
              <span className="pack-title">{x.depth === 'generic' ? 'Any other game' : x.game.title}</span>
              <span className="pack-sub">{x.depth === 'generic' ? 'Claude works out the screen' : x.name}</span>
            </button>
          ))}
      </div>
      <div className="field" style={{ marginTop: 14 }}>
        <label htmlFor="ob-mode">What will you play first?</label>
        <select id="ob-mode" value={settings.profileModeId ?? ''} onChange={(e) => onSettings({ profileModeId: e.target.value || null })}>
          <option value="">Not sure (treated as competitive)</option>
          {profile.modes.map((m) => (
            <option key={m.id} value={m.id}>
              {m.label}
            </option>
          ))}
        </select>
      </div>
    </div>
  )
}

function VoiceStep({ bridge, session, settings, onSettings, desktop }: Props & { desktop: boolean }) {
  const [tts, setTts] = useState<TtsStatus | null>(null)
  const [asr, setAsr] = useState<{ installed: boolean; installing: boolean; progress: number; error: string | null } | null>(null)
  useEffect(() => {
    const load = () => {
      void bridge.ttsStatus().then(setTts)
      void bridge.voiceStatus().then(setAsr)
    }
    load()
    const t = setInterval(load, 700)
    return () => clearInterval(t)
  }, [bridge])
  useEffect(() => {
    if (tts?.models.jenny.installed && !settings.voice.voiceURI?.startsWith('neural:')) onSettings({ voice: { ...settings.voice, voiceURI: 'neural:jenny' } })
  }, [tts?.models.jenny.installed, settings.voice, onSettings])
  if (!desktop) return <Notice level="info">Natural voices and voice input need the desktop app.</Notice>
  const jenny = tts?.models.jenny
  return (
    <div className="ob-section">
      <h2>Voice</h2>
      <div className="ob-card">
        <div>
          <b>The coach’s voice</b>
          <div className="small muted">Jenny, a natural British voice that runs on your PC (67 MB, one-time download). More voices in Settings.</div>
        </div>
        {jenny?.installed ? (
          <button type="button" className="btn" onClick={() => session.speech.speakTest("Hi, I'm your coach. Press your talk key any time to ask me something.")}>
            ▶ Hear it
          </button>
        ) : jenny?.installing ? (
          <Progress value={jenny.progress} />
        ) : (
          <button type="button" className="btn primary" onClick={() => void bridge.installTts('jenny').then(setTts)}>
            Download
          </button>
        )}
      </div>
      <div className="ob-card">
        <div>
          <Switch
            checked={settings.voiceInput}
            onChange={(voiceInput) => onSettings({ voiceInput })}
            label={<b>Talk to the coach</b>}
            hint="Press your talk key, speak, press again. Recognised on your PC (108 MB model); audio is never stored or sent."
          />
        </div>
        {settings.voiceInput ? (
          asr?.installed ? (
            <span className="badge ok">Ready</span>
          ) : asr?.installing ? (
            <Progress value={asr.progress} />
          ) : (
            <button type="button" className="btn primary" onClick={() => void bridge.installVoiceModel().then(setAsr)}>
              Download
            </button>
          )
        ) : null}
      </div>
      <div className="hotkeys">
        <HotkeyField label="Talk key" hint="Toggle: press to talk, press again to send." value={settings.hotkeys.talk} taken={[settings.hotkeys.mark, settings.hotkeys.stop]} onChange={(talk) => onSettings({ hotkeys: { ...settings.hotkeys, talk } })} />
        <HotkeyField label="Mark-this-moment key" hint="Bookmark a decision to ask about after the match." value={settings.hotkeys.mark} taken={[settings.hotkeys.talk, settings.hotkeys.stop]} onChange={(mark) => onSettings({ hotkeys: { ...settings.hotkeys, mark } })} />
      </div>
    </div>
  )
}

function ReadyStep({ settings, cloud }: Props) {
  return (
    <div className="ob-section">
      <h2>You’re set</h2>
      <ul className="ob-points">
        <li>{cloud.key.set ? '✓ Claude connected.' : '• Claude not connected yet — Settings → Cloud when you’re ready.'}</li>
        <li>{settings.voice.voiceURI?.startsWith('neural:') ? '✓ Natural voice ready.' : '• Using your system voice — download a natural voice in Settings.'}</li>
        <li>
          Talk: <kbd>{settings.hotkeys.talk}</kbd> · Mark a moment: <kbd>{settings.hotkeys.mark}</kbd> · Stop: <kbd>{settings.hotkeys.stop.replace('CommandOrControl', 'Ctrl')}</kbd>
        </li>
      </ul>
      <p className="small muted">
        Next: on the Coach screen, press <b>Choose the game window</b>. Want to see it first? Try the FC or Age of Empires demo replay.
      </p>
    </div>
  )
}

function Progress({ value }: { value: number }) {
  return (
    <div style={{ minWidth: 160 }}>
      <div className="small">{value < 0.9 ? 'Downloading' : 'Unpacking'}… {Math.round(value * 100)}%</div>
      <div className="meter">
        <span style={{ width: `${value * 100}%` }} />
      </div>
    </div>
  )
}
