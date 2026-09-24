/** Studio → OBS overlays: build the overlay address, preview it over a fake scene, copy it into OBS. */
import { useState } from 'react';
import { MOCK } from '../../api.ts';
import { Card, CopyButton, Field, Seg, Switch } from '../ui.tsx';
import { Icon } from '../icons.tsx';
import { OVERLAY_VIEWS } from './overlayOptions.ts';
import type { OverlayPos, OverlayTheme, OverlayView } from './overlayOptions.ts';
import { useElementSize } from '../../hooks.ts';
import type { StudioPayload } from '../../types.ts';

type Target = 'run' | 'latest' | 'demo';

/** The address to paste into OBS. The server redirects /overlay/… to the dashboard's overlay route. */
export function overlayUrl(target: string, params: Record<string, string>): string {
  const qs = new URLSearchParams(Object.entries(params).filter(([, v]) => v !== '')).toString();
  const { origin, pathname } = window.location;
  // Demo data (?mock=1) and the Vite dev server can't use the /overlay/ path; use the hash route directly.
  if (MOCK || window.location.port === '5173') return `${origin}${pathname}${MOCK ? '?mock=1' : ''}#/overlay/${encodeURIComponent(target)}${qs ? `?${qs}` : ''}`;
  return `${origin}/overlay/${encodeURIComponent(target)}${qs ? `?${qs}` : ''}`;
}

function Preview({ url }: { url: string }) {
  const [ref, box] = useElementSize<HTMLDivElement>();
  const k = box.width ? box.width / 1920 : 0;
  return (
    <div ref={ref} className="ov-preview" style={{ height: 1080 * k || undefined }}>
      <div className="ov-preview-scene" aria-hidden="true">
        <span>Your game / camera / screen capture</span>
      </div>
      {k > 0 && <iframe key={url} title="Overlay preview" src={url} width={1920} height={1080} style={{ transform: `scale(${k})` }} tabIndex={-1} />}
    </div>
  );
}

export function OverlayPanel({ s }: { s: StudioPayload }) {
  const [target, setTarget] = useState<Target>('latest');
  const [view, setView] = useState<OverlayView>('scoreboard');
  const [theme, setTheme] = useState<OverlayTheme>('glass');
  const [pos, setPos] = useState<OverlayPos | ''>('');
  const [safe, setSafe] = useState(true);
  const id = target === 'run' ? s.runId : target;
  const params = { view, theme: theme === 'glass' ? '' : theme, pos, safe: safe ? '' : '0' };
  const url = overlayUrl(id, params);
  const isBar = view === 'ticker';

  return (
    <div className="stack loose">
      <div className="overlay-grid">
        <Card title="Build the overlay" desc="Choose what it shows and where; copy the address into OBS." className="no-broadcast">
          <div className="stack">
            <Field label="Which run">
              <Seg<Target>
                label="Which run"
                value={target}
                onChange={setTarget}
                options={[
                  { value: 'latest', label: 'Always the latest', title: 'Follows whatever run is going on — the address never changes' },
                  { value: 'run', label: 'This run', title: s.runName },
                  { value: 'demo', label: 'Demo', title: 'Animated made-up data, for setting up your scene' },
                ]}
              />
            </Field>
            <Field label="Show">
              <div className="kind-list" role="radiogroup" aria-label="Overlay view">
                {OVERLAY_VIEWS.map((v) => (
                  <button key={v.id} type="button" role="radio" aria-checked={view === v.id} className={view === v.id ? 'kind-opt on' : 'kind-opt'} onClick={() => (setView(v.id), setPos(''))}>
                    <span className="stack tight">
                      <b>{v.label}</b>
                      <span className="muted">{v.hint}</span>
                    </span>
                  </button>
                ))}
              </div>
            </Field>
            <Field label="Look">
              <Seg<OverlayTheme>
                label="Theme"
                value={theme}
                onChange={setTheme}
                options={[
                  { value: 'glass', label: 'Glass' },
                  { value: 'solid', label: 'Solid' },
                  { value: 'light', label: 'Light' },
                  { value: 'minimal', label: 'Text only' },
                ]}
              />
            </Field>
            <Field label="Position">
              <Seg<OverlayPos | ''>
                label="Position"
                value={pos}
                onChange={setPos}
                options={
                  isBar
                    ? [
                        { value: '', label: 'Bottom' },
                        { value: 'top', label: 'Top' },
                      ]
                    : [
                        { value: '', label: 'Default' },
                        { value: 'tl', label: '↖ Top left' },
                        { value: 'tr', label: '↗ Top right' },
                        { value: 'bl', label: '↙ Bottom left' },
                        { value: 'br', label: '↘ Bottom right' },
                      ]
                }
              />
            </Field>
            <label className="row" style={{ gap: 10 }}>
              <Switch checked={safe} onChange={setSafe} label="Keep inside the TV safe area" />
              <span>Keep inside the TV safe area (5% margin)</span>
            </label>
          </div>
        </Card>

        <Card
          title="Preview"
          desc="The checkered area is transparent in OBS: your scene shows through."
          tools={
            <a className="btn sm no-broadcast" href={url} target="_blank" rel="noreferrer">
              <Icon.External /> Open
            </a>
          }
        >
          <Preview url={url} />
          <div className="ov-url no-broadcast">
            <code>{url}</code>
            <CopyButton text={url} label="Copy address" small={false} />
          </div>
        </Card>
      </div>

      <Card className="no-broadcast" title="Add it to OBS in five steps" desc="No plugins needed — OBS has a built-in web browser source.">
        <ol className="obs-steps">
          <li>
            In OBS, under <b>Sources</b>, click <b>+</b> and choose <b>Browser</b>. Name it, e.g. “Gauntlet scoreboard”.
          </li>
          <li>
            Paste the address above into <b>URL</b> (untick “Local file”).
          </li>
          <li>
            Set <b>Width 1920</b> and <b>Height 1080</b> — the overlay is designed for a 1080p canvas and scales itself to fit.
          </li>
          <li>
            Leave <b>Custom CSS</b> as OBS fills it in (<code>background-color: rgba(0, 0, 0, 0)</code>): the page has a <b>transparent background</b>, so only the panels appear over your scene.
          </li>
          <li>
            Tick <b>Refresh browser when scene becomes active</b>. Keep Gauntlet running (<code>npm start</code>); the overlay updates live as results arrive.
          </li>
        </ol>
        <p className="muted" style={{ margin: '12px 0 0' }}>
          Tip: with <b>Always the latest</b> you set this up once — it follows every new run. Use <b>Demo</b> to position things before you go live. Add several Browser sources (scoreboard + ticker + lower third) for a full broadcast look.
        </p>
      </Card>

      <Card className="no-broadcast" title="Ready-made addresses">
        <div className="ov-presets">
          {OVERLAY_VIEWS.map((v) => {
            const u = overlayUrl('latest', { view: v.id });
            return (
              <div key={v.id} className="ov-preset">
                <div className="stack tight">
                  <b>{v.label}</b>
                  <span className="muted">{v.hint}</span>
                </div>
                <code>{u}</code>
                <CopyButton text={u} label="Copy" />
              </div>
            );
          })}
        </div>
      </Card>
    </div>
  );
}
