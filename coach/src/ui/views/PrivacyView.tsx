import type { PlatformInfo } from '../../../shared/bridge.ts'
import type { GameProfile } from '../../core/profile/schema.ts'

const ITEMS: readonly { head: string; body: string }[] = [
  {
    head: 'Pixels only',
    body: 'Sightline sees only the window or screen you pick through the system capture flow. It never reads game memory, files, network packets or processes, never injects code, and never draws over the game.',
  },
  {
    head: 'No input, no automation',
    body: 'It cannot press keys, move the mouse, aim, or act for you. Its only output is short spoken and written feedback in its own window.',
  },
  {
    head: 'Frames stay in memory',
    body: 'Frames are downscaled, analysed and discarded within moments. No video, screenshots or audio are recorded. Analysis falls behind? Old frames are dropped, not queued.',
  },
  {
    head: 'No network',
    body: 'The desktop app blocks every outgoing web request. Speech uses local voices only. Nothing is uploaded or used for training.',
  },
  {
    head: 'What is kept',
    body: 'During a session: the event timeline you see in Session review, in memory. Between sessions: only outcome counts per situation, and only if you turn on “Remember tendencies”. You can delete them anytime.',
  },
  {
    head: 'Visible facts, honest confidence',
    body: 'The coach only reasons about what was visible. It reports how sure it is, ignores moments it could not see clearly, and describes patterns as associations, never as proven causes.',
  },
]

export function PrivacyView({ profile, info }: { profile: GameProfile; info: PlatformInfo | null }) {
  return (
    <div className="page">
      <div>
        <h1>Privacy & fair play</h1>
        <p className="lede">An observant coach, not a cheat. Here is exactly what it can and cannot do.</p>
      </div>

      <section className="privacy-grid">
        {ITEMS.map((i) => (
          <div className="panel privacy-item" key={i.head}>
            <div className="head">
              <span aria-hidden="true" style={{ color: 'var(--accent)' }}>✓</span>
              {i.head}
            </div>
            <div className="small" style={{ color: 'var(--text-2)' }}>
              {i.body}
            </div>
          </div>
        ))}
      </section>

      <section className="panel" aria-labelledby="integrity">
        <div className="panel-head">
          <h2 id="integrity">Competitive integrity</h2>
        </div>
        <p style={{ marginTop: 0 }}>
          Live tactical tips are only possible in <b>Training</b> mode, and only for game modes the profile marks as
          single-player and non-competitive, or where the publisher's permission is documented with a source. Everywhere else
          the coach automatically switches to <b>Competitive-safe</b>: reflective notes at round or match end only, and any note
          still playing when the next round starts is cut off.
        </p>
        <p className="muted small">
          Your operating system's permission to capture the screen is not the game publisher's permission to receive live
          coaching. Check the rules of the game and event you are playing in.
        </p>
        <table className="small" style={{ width: '100%', borderCollapse: 'collapse' }}>
          <caption className="sr-only">Live coaching status for {profile.name}</caption>
          <thead>
            <tr style={{ textAlign: 'left', color: 'var(--text-3)' }}>
              <th style={{ padding: '6px 4px' }}>Mode in {profile.game.title}</th>
              <th>Players</th>
              <th>Live coaching</th>
              <th>Source</th>
            </tr>
          </thead>
          <tbody>
            {profile.modes.map((m) => (
              <tr key={m.id} style={{ borderTop: '1px solid var(--line)' }}>
                <td style={{ padding: '6px 4px' }}>{m.label}</td>
                <td>{m.multiplayer ? (m.competitive ? 'Competitive multiplayer' : 'Multiplayer') : 'Single-player'}</td>
                <td>{m.liveCoaching.status.replace('-', ' ')}</td>
                <td>{m.liveCoaching.source ?? '—'}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </section>

      <section className="panel" aria-labelledby="limits">
        <div className="panel-head">
          <h2 id="limits">Known limits of “{profile.name}”</h2>
        </div>
        <ul className="plain small">
          {profile.limitations.map((l) => (
            <li key={l}>{l}</li>
          ))}
        </ul>
      </section>

      {info?.shell === 'browser' ? (
        <p className="muted small">
          You are running the browser preview. The browser's own share picker and permission apply; the network lock and global
          stop shortcut are features of the desktop app.
        </p>
      ) : null}
    </div>
  )
}
