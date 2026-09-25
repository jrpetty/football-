/**
 * Gallery card for artifact tests ("Precise SVG Illustration", "Build a Game in
 * One Shot"): a big render, every automatic check as a visual checklist, the
 * judges' scores as bars, and
 *  - for SVGs: the prompt's numbered requirements drawn over the picture as
 *    dashed guides, plus values the app measured from the SVG code (display
 *    only; the score comes from the recorded checks and judges);
 *  - for games: the screenshot, a "does it work?" checklist and a big Play button.
 */
import { useEffect, useMemo, useState } from 'react';
import type { CSSProperties } from 'react';
import { artifactUrl } from '../../api.ts';
import type { ArtifactRef, CaseResult } from '../../types.ts';
import { cx } from '../ui.tsx';
import { Icon } from '../icons.tsx';
import { ScoreBars, VizHeadline } from './VizHeadline.tsx';
import { ToneIcon, VizLegend } from './VizLegend.tsx';
import type { Tone } from './vizModel.ts';
import type { Guide } from './svgRequirements.ts';
import { scanSvg, svgCaseSpec } from './svgRequirements.ts';
import './viz.css';

/** The artifact the showcase is about, when this result has one (prompt tests with an artifact scorer). */
export function showcaseArtifact(r: Pick<CaseResult, 'artifacts' | 'replay'>): ArtifactRef | null {
  if (r.replay) return null;
  return (r.artifacts ?? []).find((a) => a.kind === 'html') ?? (r.artifacts ?? []).find((a) => a.kind === 'svg') ?? null;
}

function useText(url: string, enabled: boolean): string | null {
  const [text, setText] = useState<string | null>(null);
  useEffect(() => {
    if (!enabled || !url) return;
    let alive = true;
    fetch(url)
      .then((r) => (r.ok ? r.text() : null))
      .then((t) => alive && setText(t))
      .catch(() => alive && setText(null));
    return () => {
      alive = false;
    };
  }, [url, enabled]);
  return text;
}

function GuideLayer({ guides, vb }: { guides: Guide[]; vb: [number, number] }) {
  return (
    <svg className="vz-guides" viewBox={`0 0 ${vb[0]} ${vb[1]}`} preserveAspectRatio="xMidYMid meet" aria-hidden="true">
      {guides.map((g, i) => {
        if (g.kind === 'ray') {
          // Keep the label inside the picture: anchor it on the side facing the centre.
          const anchor = g.x2! > vb[0] * 0.6 ? 'end' : g.x2! < vb[0] * 0.4 ? 'start' : 'middle';
          return (
            <g key={i}>
              <line x1={g.x1} y1={g.y1} x2={g.x2} y2={g.y2} />
              <text x={g.x2! + (anchor === 'start' ? 6 : anchor === 'end' ? -6 : 0)} y={g.y2! + (g.y2! > g.y1! ? 20 : -8)} textAnchor={anchor}>
                {g.text}
              </text>
            </g>
          );
        }
        if (g.kind === 'line')
          return (
            <g key={i}>
              <line x1={g.x1} y1={g.y1} x2={g.x2} y2={g.y2} />
              <text x={g.x1! + 6} y={g.y1! - 6}>
                {g.text}
              </text>
            </g>
          );
        return (
          <g key={i}>
            <rect x={g.x} y={g.y} width={g.w} height={g.h} />
            <text x={g.x! + 4} y={g.y! + 16}>
              {g.text}
            </text>
          </g>
        );
      })}
    </svg>
  );
}

const checkTone = (passed: boolean): Tone => (passed ? 'good' : 'bad');

/** Plain-words "does it work?" rows from the recorded browser checks. */
function gameChecks(r: CaseResult): Array<{ label: string; state: 'pass' | 'fail' | 'unknown'; detail?: string }> {
  const items = r.scoreDetail?.items ?? [];
  const find = (re: RegExp) => items.find((i) => re.test(i.label));
  const row = (label: string, it: ReturnType<typeof find>, detail?: string) => ({ label, state: it ? (it.passed ? 'pass' : 'fail') : 'unknown', detail: detail ?? it?.detail }) as const;
  const errs = r.scoreDetail?.consoleErrors;
  return [
    row('Loads', find(/parses/i)),
    row('Runs without crashing', find(/javascript errors/i)),
    row('Draws a game screen', find(/canvas|svg/i)),
    row('Responds to keys and mouse', find(/input/i)),
    Array.isArray(errs) ? { label: 'No console errors', state: errs.length ? 'fail' : 'pass', detail: errs[0] ? String(errs[0]) : undefined } : { label: 'No console errors', state: 'unknown' as const },
    row('No outside downloads', find(/external/i)),
  ];
}

export function ArtifactShowcase({ runId, result, testId, caseId, names }: { runId: string; result: CaseResult; testId: string; caseId: string; names?: Map<string, string> }) {
  const art = showcaseArtifact(result);
  const isGame = art?.kind === 'html';
  const url = art ? artifactUrl(runId, art.file) : '';
  const shot = (result.artifacts ?? []).find((a) => a.kind === 'png');
  const shotUrl = shot ? artifactUrl(runId, shot.file) : '';
  const spec = useMemo(() => (isGame ? null : svgCaseSpec(testId, caseId)), [isGame, testId, caseId]);
  const svgText = useText(url, !!spec);
  const measured = useMemo(() => (spec && svgText ? spec.measure(scanSvg(svgText)) : []), [spec, svgText]);
  const [guides, setGuides] = useState(true);
  const [playing, setPlaying] = useState(false);
  const d = result.scoreDetail ?? {};
  const items = d.items ?? [];
  const passed = items.filter((i) => i.passed).length;
  const judges = d.judge ?? [];
  const judgeMean = typeof d.judgeScore === 'number' ? d.judgeScore : judges.length ? judges.reduce((a, j) => a + j.score, 0) / judges.length : null;
  const score = result.score ?? 0;
  const tone: Tone = result.score === null ? 'neutral' : score >= 0.7 ? 'good' : score >= 0.4 ? 'warn' : 'bad';
  if (!art) return null;

  const title = isGame
    ? (() => {
        const rows = gameChecks(result);
        const broke = rows.filter((r) => r.state === 'fail').map((r) => r.label.toLowerCase());
        return broke.length ? `Ships a game, but fails: ${broke.slice(0, 2).join(', ')}` : 'A working game: loads, runs and responds to input';
      })()
    : measured.length
      ? `${measured.filter((m) => m.ok).length} of ${measured.length} measured requirements exactly right`
      : `${passed} of ${items.length} automatic checks passed`;

  return (
    <div className="vz-showcase">
      <VizHeadline
        eyebrow={isGame ? 'Build a game in one shot' : 'Precise drawing in SVG code'}
        title={title}
        tone={tone}
        big={`${Math.round(score * 100)}`}
        bigSub="/100"
      />
      <div className="vz-cols wide-left">
        <div className="vz-col">
          {isGame ? (
            <div className="vz-game">
              {playing ? (
                <iframe title="Play the game" src={url} sandbox="allow-scripts" referrerPolicy="no-referrer" />
              ) : shotUrl ? (
                <img src={shotUrl} alt="Screenshot of the game after the automatic key presses" />
              ) : (
                <div className="vz-missing">No screenshot was recorded (the browser checks were skipped on this machine).</div>
              )}
              <div className="vz-game-bar">
                <button type="button" className={cx('btn', playing ? '' : 'primary', 'vz-play')} onClick={() => setPlaying((p) => !p)}>
                  {playing ? <Icon.Stop /> : <Icon.Play />} {playing ? 'Stop' : 'Play it'}
                </button>
                <a className="btn" href={url} target="_blank" rel="noreferrer noopener">
                  <Icon.External /> Open in a new tab
                </a>
                <span className="muted">{playing ? 'Running inside a locked-down frame: no network, no access to this app.' : 'Screenshot taken by the automatic checker after it pressed keys and clicked.'}</span>
              </div>
            </div>
          ) : (
            <figure className="vz-render">
              <div className="vz-render-stage" style={spec ? ({ aspectRatio: `${spec.viewBox[0]} / ${spec.viewBox[1]}`, ['--ar' as string]: spec.viewBox[0] / spec.viewBox[1] } as CSSProperties) : undefined}>
                <img src={url} alt={`The model's SVG drawing (${art.name})`} />
                {spec && guides && <GuideLayer guides={spec.guides} vb={spec.viewBox} />}
              </div>
              <figcaption>
                {spec ? (
                  <label className="vz-toggle">
                    <input type="checkbox" checked={guides} onChange={(e) => setGuides(e.target.checked)} /> Show the prompt’s requirements as dashed guides
                  </label>
                ) : (
                  <span className="muted">Rendered exactly as the model wrote it.</span>
                )}
                <a className="btn xs" href={url} target="_blank" rel="noreferrer noopener">
                  <Icon.External /> Open SVG
                </a>
              </figcaption>
            </figure>
          )}
        </div>
        <div className="vz-col narrow">
          {isGame ? (
            <div className="vz-card">
              <div className="vz-card-k">Does it work?</div>
              <ul className="vz-checks big">
                {gameChecks(result).map((c) => (
                  <li key={c.label} className={c.state}>
                    {c.state === 'unknown' ? <ToneIcon tone="neutral" /> : <ToneIcon tone={c.state === 'pass' ? 'good' : 'bad'} />}
                    <span>{c.label}</span>
                    <em>{c.state === 'unknown' ? 'not checked' : c.detail ?? ''}</em>
                  </li>
                ))}
              </ul>
            </div>
          ) : (
            measured.length > 0 && (
              <div className="vz-card">
                <div className="vz-card-k">Measured from its SVG code</div>
                <table className="vz-measure">
                  <thead>
                    <tr>
                      <th>Requirement</th>
                      <th>Asked</th>
                      <th>Measured</th>
                    </tr>
                  </thead>
                  <tbody>
                    {measured.map((m) => (
                      <tr key={m.label} className={m.ok === true ? 'pass' : m.ok === false ? 'fail' : ''}>
                        <td>
                          {m.ok !== null && <ToneIcon tone={m.ok ? 'good' : 'bad'} />} {m.label}
                        </td>
                        <td className="tnum">{m.target}</td>
                        <td className="tnum">{m.measured}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
                <p className="vz-fine">Read from the code by this app for the viewer; it does not change the score.</p>
              </div>
            )
          )}
          {/* Games already show these as "Does it work?", so the raw list is folded away there. */}
          <details className="vz-card vz-fold" open={!isGame}>
            <summary className="vz-card-k">
              Automatic checks · {passed}/{items.length} passed
            </summary>
            <ul className="vz-checks">
              {items.map((it, i) => (
                <li key={i} className={it.passed ? 'pass' : 'fail'}>
                  <ToneIcon tone={checkTone(it.passed)} />
                  <span>{it.label}</span>
                  {it.detail && <em>{it.detail}</em>}
                </li>
              ))}
            </ul>
          </details>
          {judges.length > 0 && (
            <div className="vz-card">
              <div className="vz-card-k">Judges{judgeMean !== null ? ` · ${(judgeMean * 10).toFixed(1)}/10` : ''}</div>
              <ScoreBars rows={judges.map((j) => ({ label: names?.get(j.contestantId) ?? j.contestantId, value: j.score, tone: j.score >= 0.7 ? 'good' : j.score >= 0.4 ? 'warn' : 'bad', right: `${(j.score * 10).toFixed(1)}/10`, note: j.rationale }))} />
            </div>
          )}
          {!isGame && spec && <VizLegend items={[{ kind: 'guide', label: 'Where the prompt says it must be' }]} />}
        </div>
      </div>
    </div>
  );
}
