/**
 * Small Presenter chrome for the game-show features: the speaker icon, the
 * one-second "Sound on/off" flash, the sound controls for the control bar and
 * the auto-play progress ring.
 */
import { cx } from '../ui.tsx';
import { setSfxVolume, useSfx } from './sfx.ts';
import '../../styles/show.css';

export function SpeakerIcon({ on }: { on: boolean }) {
  return (
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <path d="M4 9h4l5-4v14l-5-4H4Z" fill="currentColor" stroke="none" />
      <path d="M4 9h4l5-4v14l-5-4H4Z" />
      {on ? (
        <>
          <path d="M16.5 9a4 4 0 0 1 0 6" />
          <path d="M19 6.5a7.5 7.5 0 0 1 0 11" />
        </>
      ) : (
        <path d="M17 9.5l5 5M22 9.5l-5 5" />
      )}
    </svg>
  );
}

/** Shown for one second after the sound is toggled (remount with a new key to replay). */
export function SoundFlash({ on }: { on: boolean }) {
  return (
    <div className="show-flash" role="status">
      <SpeakerIcon on={on} />
      {on ? 'Sound effects on' : 'Sound effects off'}
    </div>
  );
}

/** Toggle + master volume for the Presenter's control bar. */
export function SoundControls({ onToggle }: { onToggle: () => void }) {
  const { on, volume } = useSfx();
  return (
    <>
      <button className={cx('btn sm', on && 'primary')} onClick={onToggle} aria-pressed={on} title="Sound effects (S)">
        <span className="d-sound" style={{ color: 'inherit' }}>
          <SpeakerIcon on={on} />
        </span>
        Sound
      </button>
      {on && (
        <input
          className="show-vol"
          type="range"
          min={0}
          max={1}
          step={0.05}
          value={volume}
          onChange={(e) => setSfxVolume(Number(e.target.value))}
          aria-label="Sound effects volume"
          title={`Volume ${Math.round(volume * 100)}%`}
        />
      )}
    </>
  );
}

/** A small ring that fills until auto-play moves on; it pulses while waiting for an animation to finish. */
export function AutoRing({ p, wait }: { p: number; wait: boolean }) {
  const r = 8;
  const c = 2 * Math.PI * r;
  return (
    <svg className={cx('auto-ring', wait && 'wait')} viewBox="0 0 22 22" role="img" aria-label={wait ? 'Auto-play: waiting for the animation' : `Auto-play: next in ${Math.round((1 - p) * 100)}%`}>
      <circle className="bg" cx="11" cy="11" r={r} />
      <circle className="fg" cx="11" cy="11" r={r} strokeDasharray={c} strokeDashoffset={c * (1 - Math.max(0, Math.min(1, p)))} />
    </svg>
  );
}
