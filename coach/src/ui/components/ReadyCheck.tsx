// Ready check: everything that can make the coach silent or blind, on one
// list before a game, each with its fix. Until now a mode left on "not sure",
// voice input off, a speech model not downloaded or a talk key taken by
// another program only showed as silence during play.

import { useEffect, useState, type ReactNode } from 'react'
import type { PlatformInfo, SightlineBridge, VoiceModelStatus } from '../../../shared/bridge.ts'
import type { CloudStatus } from '../../../shared/cloud.ts'
import type { ResolvedPolicy } from '../../core/policy/registry.ts'
import type { GameProfile } from '../../core/profile/schema.ts'
import type { CoachSettings } from '../../core/settings.ts'
import type { Tab } from '../App.tsx'
import type { CoachSession, SessionState } from '../session.ts'

type Level = 'ok' | 'warn' | 'bad' | 'off'

interface Row {
  readonly id: string
  readonly level: Level
  readonly title: string
  readonly detail: ReactNode
  readonly action?: ReactNode
}

const ICON: Record<Level, string> = { ok: '✓', warn: '!', bad: '✗', off: '–' }

export function ReadyCheck(props: {
  state: SessionState
  session: CoachSession
  settings: CoachSettings
  onSettings: (p: Partial<CoachSettings>) => void
  profile: GameProfile
  policy: ResolvedPolicy
  cloud: CloudStatus
  info: PlatformInfo | null
  bridge: SightlineBridge
  onTab: (t: Tab) => void
}) {
  const { session, settings, onSettings, profile, policy, cloud, info, bridge, onTab } = props
  const desktop = info?.shell === 'electron'
  const [voice, setVoice] = useState<VoiceModelStatus | null>(null)
  const [heard, setHeard] = useState<'idle' | 'playing' | 'yes'>('idle')
  const [mic, setMic] = useState<'idle' | 'testing' | { peak: number } | { error: string }>('idle')
  const [key, setKey] = useState<'idle' | 'waiting' | 'ok' | 'none'>('idle')
  const [cloudTest, setCloudTest] = useState<'idle' | 'testing' | 'ok' | string>('idle')

  useEffect(() => {
    if (desktop) void bridge.voiceStatus().then(setVoice).catch(() => undefined)
  }, [bridge, desktop, settings.voiceInput])

  const mode = profile.modes.find((m) => m.id === settings.profileModeId) ?? null
  const live = policy.scope.has('live')
  const rows: Row[] = []

  // 1. The game mode decides whether the coach may talk during play.
  rows.push(
    !mode
      ? {
          id: 'mode',
          level: 'warn',
          title: 'Game mode not set',
          detail: 'Treated as ranked: nothing is said during play, only the review after. Pick what you are playing.',
          action: (
            <select aria-label="Game mode" value="" onChange={(e) => onSettings({ profileModeId: e.target.value || null })}>
              <option value="">Choose…</option>
              {profile.modes.map((m) => (
                <option key={m.id} value={m.id}>
                  {m.label}
                </option>
              ))}
            </select>
          ),
        }
      : {
          id: 'mode',
          level: 'ok',
          title: `${mode.label}`,
          detail: live ? 'The coach may talk during play here.' : 'No talking during play here (fair play): you get the review after the match.',
        },
  )

  // 2. Can it be heard?
  const voiceOk = props.session.speech.available
  rows.push({
    id: 'voice',
    level: settings.muted ? 'warn' : !voiceOk ? 'bad' : heard === 'yes' ? 'ok' : 'warn',
    title: settings.muted ? 'Muted' : !voiceOk ? 'No voice to speak with' : heard === 'yes' ? 'You can hear the coach' : 'Can you hear the coach?',
    detail: settings.muted ? 'Nothing is spoken.' : !voiceOk ? 'Download a natural voice in Settings, Voice.' : 'Plays a short line through your speakers or headset.',
    action: settings.muted ? (
      <button type="button" className="btn sm" onClick={() => onSettings({ muted: false })}>
        Unmute
      </button>
    ) : !voiceOk ? (
      <button type="button" className="btn sm" onClick={() => onTab('settings')}>
        Settings
      </button>
    ) : heard === 'yes' ? null : (
      <span className="btn-row">
        <button
          type="button"
          className="btn sm"
          onClick={() => {
            setHeard('playing')
            session.speech.speakTest('This is your coach. If you can hear me, click yes.')
          }}
        >
          Play a test line
        </button>
        {heard === 'playing' ? (
          <button type="button" className="btn sm primary" onClick={() => setHeard('yes')}>
            Yes, I heard it
          </button>
        ) : null}
      </span>
    ),
  })

  // 3. Commentary.
  rows.push({
    id: 'commentary',
    level: !settings.commentary ? 'off' : live ? 'ok' : 'warn',
    title: !settings.commentary ? 'Live commentary off' : live ? 'Live commentary on' : 'Live commentary waits for the match to end',
    detail: !settings.commentary ? 'Turn it on for short spoken lines about what the coach sees.' : live ? 'Short lines as things happen, and a status line when it is quiet.' : 'Not in this mode: it explains this out loud when capture starts.',
    action: (
      <button type="button" className="btn sm ghost" onClick={() => onSettings({ commentary: !settings.commentary })}>
        {settings.commentary ? 'Turn off' : 'Turn on'}
      </button>
    ),
  })

  // 4. Talking to it.
  if (!settings.voiceInput) {
    rows.push({
      id: 'voice-input',
      level: 'off',
      title: 'Voice questions off',
      detail: 'Ask out loud with a key, or have it always listening.',
      action: (
        <button type="button" className="btn sm ghost" onClick={() => onSettings({ voiceInput: true })}>
          Turn on
        </button>
      ),
    })
  } else {
    const model = voice?.installed
    rows.push({
      id: 'speech-model',
      level: !desktop ? 'bad' : model ? 'ok' : 'bad',
      title: !desktop ? 'Voice questions need the desktop app' : model ? 'Speech recognition ready' : voice?.installing ? `Downloading speech recognition… ${Math.round((voice.progress ?? 0) * 100)}%` : 'Speech recognition not downloaded',
      detail: model ? 'On this computer; nothing is sent.' : 'About 100 MB, once.',
      action: desktop && !model && !voice?.installing ? (
        <button type="button" className="btn sm" onClick={() => void bridge.installVoiceModel().then(setVoice)}>
          Download
        </button>
      ) : null,
    })
    const peak = typeof mic === 'object' && 'peak' in mic ? mic.peak : null
    rows.push({
      id: 'mic',
      level: typeof mic === 'object' ? ('error' in mic ? 'bad' : peak! > 0.03 ? 'ok' : 'warn') : 'warn',
      title: typeof mic === 'object' ? ('error' in mic ? 'Microphone problem' : peak! > 0.03 ? 'Microphone hears you' : 'Microphone very quiet') : 'Microphone',
      detail:
        typeof mic === 'object'
          ? 'error' in mic
            ? mic.error
            : peak! > 0.03
              ? `Loudest level ${Math.round(peak! * 100)}%.`
              : 'Nothing louder than a whisper was heard: check the input device in your sound settings.'
          : 'Say something during a 2-second test.',
      action: (
        <button
          type="button"
          className="btn sm"
          disabled={mic === 'testing'}
          onClick={() => {
            setMic('testing')
            void session.testMic().then((r) => setMic(r.ok ? { peak: r.peak } : { error: r.error }))
          }}
        >
          {mic === 'testing' ? 'Listening…' : 'Test'}
        </button>
      ),
    })
    if (settings.listen === 'always') {
      const l = props.state.listen
      rows.push({
        id: 'listen',
        level: l.status === 'on' ? 'ok' : l.status === 'error' ? 'bad' : 'warn',
        title: l.status === 'on' ? `Always listening${settings.wakeWord ? ': start with “Coach”' : ''}` : l.status === 'error' ? 'Always listening stopped' : `Always listening: ${l.status}`,
        detail: l.error ?? (l.heard ? `Last heard: “${l.heard.text}”` : 'Say “Coach, what should I build?” to try it.'),
        action:
          l.status === 'error' ? (
            <button type="button" className="btn sm" onClick={() => session.retryListening()}>
              Try again
            </button>
          ) : null,
      })
    }
    const errors = info?.shortcutErrors ?? []
    rows.push({
      id: 'talk-key',
      level: !info?.talkShortcut || errors.length ? 'bad' : key === 'ok' ? 'ok' : key === 'none' ? 'bad' : 'warn',
      title: !info?.talkShortcut ? 'No talk key' : key === 'ok' ? `Talk key ${info.talkShortcut} works` : key === 'none' ? `Talk key ${info.talkShortcut} did not arrive` : `Talk key ${info.talkShortcut}`,
      detail: errors[0] ?? (key === 'waiting' ? 'Press it now (with the game in front, if you like).' : key === 'none' ? 'Another program may be using it: choose a different key in Settings, Keyboard.' : 'Hold it while you ask and let go, or tap it twice.'),
      action:
        info?.talkShortcut && !errors.length ? (
          <button
            type="button"
            className="btn sm"
            disabled={key === 'waiting'}
            onClick={() => {
              setKey('waiting')
              void session.testTalkKey().then((ok) => setKey(ok ? 'ok' : 'none'))
            }}
          >
            {key === 'waiting' ? 'Waiting for the key…' : 'Test'}
          </button>
        ) : (
          <button type="button" className="btn sm ghost" onClick={() => onTab('settings')}>
            Settings
          </button>
        ),
    })
  }

  // 5. Seeing the screen.
  const cloudOn = cloud.available && cloud.settings.enabled && cloud.key.set
  if (profile.vision) {
    rows.push({
      id: 'cloud',
      level: !cloudOn ? 'bad' : cloudTest === 'ok' ? 'ok' : cloudTest === 'idle' || cloudTest === 'testing' ? 'warn' : 'bad',
      title: !cloudOn ? 'Screen reading needs Claude' : cloudTest === 'ok' ? 'Claude connected' : typeof cloudTest === 'string' && !['idle', 'testing'].includes(cloudTest) ? 'Claude did not answer' : 'Claude',
      detail: !cloudOn ? `${profile.game.title}'s HUD is read by Claude: add your API key and turn cloud features on.` : typeof cloudTest === 'string' && !['idle', 'testing', 'ok'].includes(cloudTest) ? cloudTest : 'Checks your key with one tiny request.',
      action: !cloudOn ? (
        <button type="button" className="btn sm" onClick={() => onTab('settings')}>
          Set up
        </button>
      ) : (
        <button
          type="button"
          className="btn sm"
          disabled={cloudTest === 'testing'}
          onClick={() => {
            setCloudTest('testing')
            void bridge.testCloud().then((r) => setCloudTest(r.ok ? 'ok' : r.error))
          }}
        >
          {cloudTest === 'testing' ? 'Testing…' : 'Test'}
        </button>
      ),
    })
  }
  if (profile.hudNumbers?.length && profile.vision) {
    const marked = profile.hudNumbers.filter((h) => settings.hud[profile.id]?.[h.key]).length
    rows.push({
      id: 'hud',
      level: marked ? 'ok' : 'off',
      title: marked ? `${marked} HUD numbers marked` : 'HUD numbers not marked',
      detail: marked ? 'Read on this PC once they agree with Claude.' : 'Optional: mark where your resources, population and clock are, so they are read every second for free.',
      action: (
        <button type="button" className="btn sm ghost" onClick={() => onTab('settings')}>
          {marked ? 'Change' : 'Mark them'}
        </button>
      ),
    })
  }
  if (profile.id === 'aoe4') {
    rows.push({
      id: 'truth',
      level: settings.aoe4world.profileId ? 'ok' : 'off',
      title: settings.aoe4world.profileId ? 'Checked against the real game (AoE4World)' : 'Not checked against the real game',
      detail: settings.aoe4world.profileId ? 'After each match, in History.' : 'Optional: link your AoE4World profile in History to see how right the coach was.',
      action: (
        <button type="button" className="btn sm ghost" onClick={() => onTab('history')}>
          {settings.aoe4world.profileId ? 'History' : 'Link'}
        </button>
      ),
    })
  }

  const bad = rows.filter((r) => r.level === 'bad').length
  const warn = rows.filter((r) => r.level === 'warn').length
  return (
    <section className="panel ready" aria-labelledby="ready-h">
      <div className="panel-head">
        <h2 id="ready-h">Ready check</h2>
        <span className={`badge ${bad ? 'warn' : warn ? 'info' : 'ok'}`}>{bad ? `${bad} to fix` : warn ? `${warn} to check` : 'All set'}</span>
      </div>
      <ul className="ready-list">
        {rows.map((r) => (
          <li key={r.id} className={`ready-${r.level}`}>
            <span className="ready-icon" aria-hidden="true">
              {ICON[r.level]}
            </span>
            <div className="ready-text">
              <b>{r.title}</b>
              <span className="muted small">{r.detail}</span>
            </div>
            <div className="ready-action">{r.action}</div>
          </li>
        ))}
      </ul>
    </section>
  )
}
