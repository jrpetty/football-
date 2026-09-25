/**
 * Presenter "answer vs truth" slide for the long-context, drawing and picture
 * tests, shown after a test's result slide. One glance per test:
 *  - Needle in a Haystack: every model's document strip ("who reads to the end?").
 *  - Chain of Whispers: which facts survived, model by model.
 *  - Draw It Blind: the original next to every model's redrawing.
 *  - SVG illustration / one-shot games: every model's render or screenshot.
 *  - Vision: the picture with the answer key drawn on it, and each model's answer.
 * Everything comes from the recorded results (score detail and artifacts).
 */
import type { CSSProperties, ReactNode } from 'react';
import { artifactUrl } from '../../api.ts';
import type { CaseResultLite, CategoryInfo, TestDetail } from '../../types.ts';
import { testImageUrl } from '../../vision.ts';
import { cx } from '../ui.tsx';
import { NeedleStrip } from '../viz/NeedleStrip.tsx';
import { OverlaySvg, overlayPlan, visionLayout } from '../viz/VisionAnswerOverlay.tsx';
import { ToneIcon } from '../viz/VizLegend.tsx';
import type { VisionLayout } from '../viz/vizModel.ts';
import { needleModel, parseCells, showAnswer, whispersModel } from '../viz/vizModel.ts';
import '../viz/viz.css';
import '../../styles/moments.css';

export type MomentKind = 'needle' | 'whispers' | 'draw' | 'svg' | 'game' | 'vision';

export interface MomentContender {
  id: string;
  label: string;
  color: string;
  baseline: boolean;
}

export interface MomentTest {
  id: string;
  name: string;
  kind: 'prompt' | 'program';
  caseIds: string[];
}

/** Which moment slide a test gets, from the recorded results (null = none). */
export function momentKindFor(t: MomentTest, detail: TestDetail | null, results: CaseResultLite[]): MomentKind | null {
  const mine = results.filter((r) => r.testId === t.id && r.status === 'ok');
  if (!mine.length) return null;
  const d = mine[0]!.scoreDetail ?? {};
  if (t.kind === 'program') {
    if (Array.isArray(d.needles)) return 'needle';
    if (Array.isArray(d.facts) && Array.isArray(d.rounds)) return 'whispers';
    if (Array.isArray(d.scene) && Array.isArray(d.matches)) return 'draw';
    return null;
  }
  if (mine.some((r) => (r.artifacts ?? []).some((a) => a.kind === 'html'))) return 'game';
  if (mine.some((r) => (r.artifacts ?? []).some((a) => a.kind === 'svg'))) return 'svg';
  const def = detail?.definition;
  if (def?.kind === 'prompt' && def.cases.some((c) => (c.images?.length ?? 0) > 0) && pickVisionCase(t, detail, results)) return 'vision';
  return null;
}

/** The Presenter's "What you're seeing" caption for each moment slide. */
export function momentCaption(kind: MomentKind): { text: string; fine?: string } {
  switch (kind) {
    case 'needle':
      return { text: 'The same long document for every model, drawn as a bar from start to end. Each dot is a hidden fact: green means the model found it, amber means it picked the look-alike decoy, red means it missed.', fine: 'First seed of the run · “end” = facts in the last third of the document' };
    case 'whispers':
      return { text: 'Each model rewrote the same story over and over, shrinking and re-growing it. A green tick means that fact survived to the final version; amber means its wording drifted; red means it was lost.', fine: 'First seed of the run · a fact counts only when its details stay together in one sentence' };
    case 'draw':
      return { text: 'Each model described the picture on the left in words, then redrew it from nothing but its own description. The number is how closely the redrawing matches, out of 100.', fine: 'Score = shape-by-shape match of type, colour, position and size, minus penalties' };
    case 'svg':
      return { text: 'The same drawing brief for every model, written as SVG code and rendered exactly as written. The number is its score out of 100 from automatic checks and cross-company judges.' };
    case 'game':
      return { text: 'Every model built the same game in a single file. These are screenshots from the automatic checker after it pressed keys; the lines under each show any check that failed.' };
    default:
      return { text: 'The picture every model was shown, with the correct answer drawn on it. Each model’s answer is listed with a tick or a cross.', fine: 'The question the models disagreed on most · first attempt of each model' };
  }
}

/** First attempt of each contestant on one case. */
function perModel(results: CaseResultLite[], testId: string, caseId: string): Map<string, CaseResultLite> {
  const m = new Map<string, CaseResultLite>();
  for (const r of results) if (r.testId === testId && r.caseId === caseId && !m.has(r.contestantId)) m.set(r.contestantId, r);
  return m;
}

/** The case where the models disagree most (ties: the first). */
function divisiveCase(t: MomentTest, results: CaseResultLite[], ok: (caseId: string) => boolean = () => true): string | null {
  let best: { id: string; spread: number } | null = null;
  for (const id of t.caseIds) {
    if (!ok(id)) continue;
    const scores = [...perModel(results, t.id, id).values()].map((r) => r.score).filter((s): s is number => typeof s === 'number');
    if (!scores.length) continue;
    const spread = Math.max(...scores) - Math.min(...scores);
    if (!best || spread > best.spread + 1e-9) best = { id, spread };
  }
  return best?.id ?? null;
}

function pickVisionCase(t: MomentTest, detail: TestDetail | null, results: CaseResultLite[]): { caseId: string; path: string; layout: VisionLayout | null } | null {
  const imageOf = (caseId: string) => detail?.rendered.find((r) => r.caseId === caseId)?.images?.[0]?.path;
  const withLayout = divisiveCase(t, results, (id) => !!imageOf(id) && !!visionLayout({ path: imageOf(id) }));
  const id = withLayout ?? divisiveCase(t, results, (id) => !!imageOf(id));
  if (!id) return null;
  const path = imageOf(id)!;
  return { caseId: id, path, layout: visionLayout({ path }) };
}

function Who({ c }: { c: MomentContender }) {
  return (
    <span className="mo-who" style={{ ['--c' as string]: c.baseline ? 'var(--text-3)' : c.color } as CSSProperties}>
      <i />
      <b>{c.baseline ? 'Random guessing' : c.label}</b>
    </span>
  );
}

const pct = (s: number | null | undefined) => (typeof s === 'number' ? `${Math.round(s * 100)}` : '—');

function Head({ cat, name, title, sub }: { cat: CategoryInfo; name: string; title: ReactNode; sub?: ReactNode }) {
  return (
    <div className="mo-head">
      <div className="x-top">
        <span className="pcat" style={{ ['--cc' as string]: cat.color } as CSSProperties}>
          <i />
          {cat.name}
        </span>
        <span className="x-count">{name}</span>
      </div>
      <h1 className="mo-title">{title}</h1>
      {sub && <div className="mo-sub">{sub}</div>}
    </div>
  );
}

export function MomentSlide({ kind, test, cat, detail, results, contenders, runId }: { kind: MomentKind; test: MomentTest; cat: CategoryInfo; detail: TestDetail | null; results: CaseResultLite[]; contenders: MomentContender[]; runId: string }) {
  const people = [...contenders].sort((a, b) => Number(a.baseline) - Number(b.baseline));

  if (kind === 'needle') {
    const caseId = test.caseIds.find((id) => perModel(results, test.id, id).size > 0) ?? test.caseIds[0]!;
    const rows = perModel(results, test.id, caseId);
    return (
      <div className="s-moment" data-moment="needle">
        <Head cat={cat} name={test.name} title="Who reads to the end?" sub={`Each bar is the whole document, start to end. A dot is a hidden fact: green found, amber fooled by a look-alike, red missed. Same document for everyone (${caseId}).`} />
        <div className="mo-rows">
          {people.map((c) => {
            const r = rows.get(c.id);
            const m = r ? needleModel(r.scoreDetail) : null;
            const end = m?.zones[2];
            return (
              <div key={c.id} className={cx('mo-row', c.baseline && 'base')}>
                <Who c={c} />
                <div className="mo-strip">{m ? <NeedleStrip model={m} compact /> : <span className="mo-none">{r ? r.summary : 'no result'}</span>}</div>
                <div className="mo-stat">
                  <b className="tnum">{m ? `${m.correct}/${m.total}` : '—'}</b>
                  <small>{end && end.total ? `end ${end.found}/${end.total}` : 'found'}</small>
                </div>
              </div>
            );
          })}
        </div>
        <div className="mo-axis" aria-hidden="true">
          <span />
          <span>
            <span>▲ Start of the document</span>
            <span>End ▲</span>
          </span>
          <span />
        </div>
      </div>
    );
  }

  if (kind === 'whispers') {
    const caseId = test.caseIds.find((id) => perModel(results, test.id, id).size > 0) ?? test.caseIds[0]!;
    const rows = perModel(results, test.id, caseId);
    const models = people.map((c) => ({ c, m: rows.get(c.id) ? whispersModel(rows.get(c.id)!.scoreDetail) : null }));
    const facts = models.find((x) => x.m)?.m?.facts ?? [];
    return (
      <div className="s-moment" data-moment="whispers">
        <Head cat={cat} name={test.name} title="What survived the retelling?" sub="Every model rewrote the same story again and again. A tick means the fact made it to the final version." />
        <div className="mo-grid" style={{ ['--n' as string]: facts.length } as CSSProperties}>
          <span />
          {facts.map((f) => (
            <span key={f.id} className="mo-fact">
              {f.label}
            </span>
          ))}
          <span className="mo-fact total">Kept</span>
          {models.map(({ c, m }) => (
            <div key={c.id} className={cx('mo-grow', c.baseline && 'base')}>
              <Who c={c} />
              {facts.map((f) => {
                const st = m ? m.status[m.facts.findIndex((x) => x.id === f.id)]?.[m.columns.length - 1] : undefined;
                return <span key={f.id} className="mo-cell">{st ? <ToneIcon tone={st === 'kept' ? 'good' : st === 'changed' ? 'warn' : 'bad'} /> : '·'}</span>;
              })}
              <b className="mo-cell tnum">{m ? `${m.survived}/${m.total}` : '—'}</b>
            </div>
          ))}
        </div>
      </div>
    );
  }

  if (kind === 'draw' || kind === 'svg' || kind === 'game') {
    const caseId = divisiveCase(test, results) ?? test.caseIds[0]!;
    const rows = perModel(results, test.id, caseId);
    const url = (r: CaseResultLite | undefined, pick: (name: string, kind: string) => boolean) => {
      const a = (r?.artifacts ?? []).find((x) => pick(x.name, x.kind));
      return a ? artifactUrl(runId, a.file) : '';
    };
    const target = kind === 'draw' ? [...rows.values()].map((r) => url(r, (n) => n === 'target.svg')).find(Boolean) ?? '' : '';
    const tiles = people.filter((c) => !c.baseline || rows.has(c.id));
    const title = kind === 'draw' ? 'Described in words, then redrawn from memory' : kind === 'svg' ? 'Same brief, every drawing' : 'One prompt, one file: every game';
    const sub =
      kind === 'draw'
        ? 'Left: the picture each model had to describe. Right: what it drew from its own description.'
        : kind === 'svg'
          ? `Case ${caseId}: the drawing each model wrote as SVG code, with its score out of 100.`
          : `Case ${caseId}: a screenshot after the automatic checker pressed keys, with what worked.`;
    return (
      <div className="s-moment" data-moment={kind}>
        <Head cat={cat} name={test.name} title={title} sub={sub} />
        <div className={cx('mo-gallery', kind === 'draw' && 'with-target')}>
          {kind === 'draw' && (
            <figure className="mo-tile target">
              {target ? <img src={target} alt="The original picture" /> : <div className="mo-none">Original not recorded</div>}
              <figcaption>
                <b>The original</b>
              </figcaption>
            </figure>
          )}
          <div className={cx('mo-tiles', tiles.length > 3 && 'two-rows')} style={{ ['--n' as string]: tiles.length > 3 ? Math.ceil(tiles.length / 2) : Math.max(1, tiles.length) } as CSSProperties}>
            {tiles.map((c) => {
              const r = rows.get(c.id);
              const src = kind === 'draw' ? url(r, (n) => n === 'drawn.svg') : kind === 'svg' ? url(r, (_n, k) => k === 'svg') : url(r, (_n, k) => k === 'png');
              const items = r?.scoreDetail?.items ?? [];
              const failed = items.filter((i) => !i.passed);
              return (
                <figure key={c.id} className={cx('mo-tile', kind === 'game' && 'wide')}>
                  {src ? <img src={src} alt={`${c.label}'s ${kind === 'game' ? 'game' : 'drawing'}`} /> : <div className="mo-none">{r ? r.summary || 'nothing to show' : 'no result'}</div>}
                  <figcaption>
                    <Who c={c} />
                    <b className={cx('mo-score tnum', (r?.score ?? 0) >= 0.7 ? 'good' : (r?.score ?? 0) >= 0.4 ? 'warn' : 'bad')}>{pct(r?.score)}</b>
                  </figcaption>
                  {kind === 'game' && r && items.length > 0 && (
                    <div className="mo-fails">
                      {failed.length ? (
                        failed.slice(0, 2).map((f) => (
                          <span key={f.label}>
                            <ToneIcon tone="bad" /> {f.label}
                          </span>
                        ))
                      ) : (
                        <span>
                          <ToneIcon tone="good" /> every automatic check passed
                        </span>
                      )}
                    </div>
                  )}
                </figure>
              );
            })}
          </div>
        </div>
      </div>
    );
  }

  // Vision
  const pick = pickVisionCase(test, detail, results);
  if (!pick) return null;
  const rows = perModel(results, test.id, pick.caseId);
  const def = detail?.definition;
  const c0 = def?.kind === 'prompt' ? def.cases.find((c) => c.id === pick.caseId) : undefined;
  const question = (c0?.prompt ?? '').split(/\n\s*\n/)[0]!.trim();
  const truth = c0?.expected;
  // The answer key drawn on the picture (as if a model had answered it perfectly).
  const keyPlan = pick.layout ? overlayPlan(pick.layout, { extracted: typeof truth === 'object' && !Array.isArray(truth) ? JSON.stringify(truth) : Array.isArray(truth) ? truth[0] : truth, expected: truth }, true) : null;
  if (keyPlan?.kind === 'lines') keyPlan.modelLine = null;
  const truthCells = parseCells(truth);
  return (
    <div className="s-moment" data-moment="vision">
      <Head cat={cat} name={test.name} title="Answer vs truth" sub={question} />
      <div className="mo-vision">
        <figure className="mo-vfig" style={pick.layout ? ({ aspectRatio: `${pick.layout.width} / ${pick.layout.height}`, ['--ar' as string]: pick.layout.width / pick.layout.height } as CSSProperties) : undefined}>
          <img src={testImageUrl(pick.path)} alt="The picture every model was shown" />
          {pick.layout && keyPlan && <OverlaySvg layout={pick.layout} plan={keyPlan} />}
        </figure>
        <div className="mo-answers">
          <div className="mo-key">
            <span className="vz-k">Answer key</span>
            <b>{showAnswer(truth)}</b>
          </div>
          {people.map((c) => {
            const r = rows.get(c.id);
            const ok = r?.passed === true;
            const said = r?.status === 'skipped' ? 'skipped: no image input' : r ? showAnswer(r.scoreDetail?.extracted) : 'no result';
            const cells = truthCells && r ? parseCells(r.scoreDetail?.extracted) : null;
            return (
              <div key={c.id} className={cx('mo-ans', r?.status === 'skipped' ? 'skip' : ok ? 'good' : 'bad', c.baseline && 'base')}>
                <Who c={c} />
                <span className="mo-said">
                  {cells ? (
                    <span className="vz-chips">
                      {cells.cells.map((cell) => (
                        <span key={cell} className={cx('vz-chip', truthCells!.cells.includes(cell) ? 'good' : 'bad')}>
                          {cell}
                        </span>
                      ))}
                    </span>
                  ) : (
                    said
                  )}
                </span>
                {r?.status !== 'skipped' && r && <ToneIcon tone={ok ? 'good' : 'bad'} />}
              </div>
            );
          })}
        </div>
      </div>
    </div>
  );
}
