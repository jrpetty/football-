/**
 * HTML pages and games: a sandboxed iframe (scripts run, but in an opaque
 * origin with no network), behind a "Play it" cover so nothing runs until you
 * ask. Restart, full screen, and the screenshots the harness recorded.
 */
import { useEffect, useMemo, useRef, useState } from 'react';
import { Icon } from '../icons.tsx';
import { cx } from '../ui.tsx';
import { CodeBlock } from './CodeView.tsx';
import { sandboxedSrcdoc } from './sanitize.ts';
import { onViewerCommand } from './commands.ts';
import type { ViewerProps } from './types.ts';

export default function HtmlView({ file, content, height, source }: ViewerProps) {
  const [playing, setPlaying] = useState(false);
  const [nonce, setNonce] = useState(0);
  const [shot, setShot] = useState(0);
  const stageRef = useRef<HTMLDivElement>(null);
  const shots = file.screenshots ?? [];
  // Stored artifacts come from the server with a no-network CSP; inline HTML gets the same policy as a meta tag.
  const srcdoc = useMemo(() => (file.url && !file.url.startsWith('data:') ? undefined : sandboxedSrcdoc(content.text ?? '')), [file.url, content.text]);

  useEffect(() => setPlaying(false), [file.id]);
  useEffect(
    () =>
      onViewerCommand((cmd) => {
        if (cmd === 'play') setPlaying((p) => !p);
        if (cmd === 'restart') {
          setPlaying(true);
          setNonce((n) => n + 1);
        }
        if (cmd === 'fullscreen') void (document.fullscreenElement ? document.exitFullscreen() : stageRef.current?.requestFullscreen());
      }),
    [],
  );

  if (source) return <CodeBlock text={content.text ?? ''} lang="text" height={height} />;
  const poster = shots[shot];
  return (
    <div className="uv-html" style={{ ['--stage-h' as string]: `${height}px` }}>
      <div className={cx('uv-html-stage', playing && 'live')} ref={stageRef}>
        {playing ? (
          <iframe
            key={nonce}
            title={`${file.name} (sandboxed)`}
            src={srcdoc ? undefined : file.url}
            srcDoc={srcdoc}
            sandbox="allow-scripts"
            referrerPolicy="no-referrer"
            allow="fullscreen 'none'; camera 'none'; microphone 'none'; geolocation 'none'"
          />
        ) : (
          <button type="button" className="uv-cover" onClick={() => setPlaying(true)} aria-label="Play it (P)">
            {poster?.url ? <img src={poster.url} alt="" className="uv-cover-img" /> : <span className="uv-cover-grid" aria-hidden="true" />}
            <span className="uv-cover-cta">
              <span className="uv-play-disc">
                <Icon.Play />
              </span>
              <span className="uv-cover-t">Play it</span>
              <span className="uv-cover-s">
                Runs in a sealed sandbox: no internet, no access to Gauntlet · <kbd>P</kbd>
              </span>
            </span>
          </button>
        )}
      </div>
      <div className="uv-html-bar">
        <button type="button" className={cx('btn sm', playing ? '' : 'primary')} onClick={() => setPlaying(!playing)}>
          {playing ? <Icon.Stop /> : <Icon.Play />} {playing ? 'Stop' : 'Play it'} <kbd>P</kbd>
        </button>
        <button
          type="button"
          className="btn sm"
          onClick={() => {
            setPlaying(true);
            setNonce((n) => n + 1);
          }}
        >
          <Icon.Refresh /> Restart
        </button>
        <button type="button" className="btn sm" onClick={() => void stageRef.current?.requestFullscreen()}>
          <Icon.Maximize /> Full screen <kbd>F</kbd>
        </button>
        {playing && <span className="muted uv-hint">Click the game to control it; click outside it to use the grading shortcuts again.</span>}
      </div>
      {shots.length > 0 && (
        <div className="uv-shots" aria-label="Screenshots recorded by the harness">
          <span className="uv-shots-k">Recorded by the harness</span>
          {shots.map((s, i) => (
            <button
              key={s.id}
              type="button"
              className={cx('uv-shot', i === shot && !playing && 'on')}
              onClick={() => {
                setShot(i);
                setPlaying(false);
              }}
              title={s.name}
            >
              {s.url ? <img src={s.url} alt={s.name} loading="lazy" /> : s.name}
            </button>
          ))}
        </div>
      )}
    </div>
  );
}
