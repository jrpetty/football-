/**
 * Vision tests: the model's answer drawn on top of the picture it was shown.
 *  - Spot the difference / locate on a board: the cells the model named vs the
 *    truly changed cells, on every grid (green = right, red = wrong, dashed = missed).
 *  - Counting: each true target ringed and numbered, next to the model's count.
 *  - Charts: the bars or points the question is about, labelled with their values.
 *  - Handwriting: the truly wrong line vs the line the model picked.
 * Geometry comes from vision-layouts.json (written by verification/vision/layouts.mts
 * from the same seeded generators that drew the images).
 */
import { useState } from 'react';
import type { CSSProperties } from 'react';
import type { ChatImage, ScoreDetail } from '../../types.ts';
import { testImageUrl } from '../../vision.ts';
import { cx } from '../ui.tsx';
import { AnswerVsTruth, VizHeadline } from './VizHeadline.tsx';
import type { LegendItem } from './VizLegend.tsx';
import { VizLegend } from './VizLegend.tsx';
import type { CellMark, Tone, VisionLayout } from './vizModel.ts';
import { cellMarks, cellRect, parseCells, parseLine, parseNumber, showAnswer } from './vizModel.ts';
import layoutsJson from './vision-layouts.json';
import './viz.css';

const LAYOUTS = layoutsJson as unknown as Record<string, VisionLayout>;

export function visionLayout(img: Pick<ChatImage, 'path'> | undefined): VisionLayout | null {
  return img?.path ? (LAYOUTS[img.path] ?? null) : null;
}

export interface OverlayPlan {
  kind: 'cells' | 'count' | 'chart' | 'lines';
  title: string;
  tone: Tone;
  big?: string;
  bigSub?: string;
  marks?: CellMark[];
  modelLine?: number | null;
  trueLine?: number | null;
  legend: LegendItem[];
}

/** What to draw for this answer, or null when the picture has no layout (falls back to the plain panel). */
export function overlayPlan(layout: VisionLayout, d: ScoreDetail, passed: boolean | null): OverlayPlan | null {
  const truthCells = parseCells(d.expected);
  if (layout.grids?.length && truthCells) {
    const marks = cellMarks(parseCells(d.extracted), truthCells);
    const right = marks.filter((m) => m.status === 'correct').length;
    const wrong = marks.filter((m) => m.status === 'wrong').length;
    const missed = marks.filter((m) => m.status === 'missed').length;
    const parts = [`found ${right} of ${truthCells.cells.length}`];
    if (missed) parts.push(`missed ${missed}`);
    if (wrong) parts.push(`${wrong} wrong`);
    return {
      kind: 'cells',
      title: d.extracted === undefined ? 'No answer recorded' : wrong + missed === 0 ? `Every cell right: ${truthCells.cells.length} of ${truthCells.cells.length}` : parts.join(', ').replace(/^f/, 'F'),
      tone: wrong + missed === 0 ? 'good' : right > 0 ? 'warn' : 'bad',
      big: `${right}/${truthCells.cells.length}`,
      bigSub: 'cells right',
      marks,
      legend: [
        { kind: 'good', label: 'Model named it, and it is right' },
        { kind: 'bad', label: 'Model named it, but it is wrong' },
        { kind: 'missed', label: 'Truly different, but missed' },
      ],
    };
  }
  if (layout.targets?.length) {
    const said = parseNumber(d.extracted);
    const truth = layout.targets.length;
    return {
      kind: 'count',
      title: said === null ? `No count given; there are ${truth}` : said === truth ? `Counted all ${truth} exactly` : `Counted ${said}, but there are ${truth} (${said > truth ? `${said - truth} too many` : `${truth - said} missed`})`,
      tone: said === truth ? 'good' : 'bad',
      big: said === null ? '—' : String(said),
      bigSub: `said · truth ${truth}`,
      legend: [{ kind: 'accent', label: 'Every shape that should be counted, numbered' }],
    };
  }
  if (layout.lines?.length) {
    const modelLine = parseLine(d.extracted);
    const trueLine = parseLine(d.expected);
    return {
      kind: 'lines',
      title: modelLine === null ? 'No line picked' : modelLine === trueLine ? `Spotted the mistake on line ${trueLine}` : `Blamed line ${modelLine}; the first mistake is on line ${trueLine}`,
      tone: passed ? 'good' : modelLine === trueLine ? 'warn' : 'bad',
      modelLine,
      trueLine,
      legend: [
        { kind: 'good', label: 'The first wrong line (answer key)' },
        { kind: 'bad', label: 'The line the model blamed' },
      ],
    };
  }
  if (layout.highlights?.length) {
    return {
      kind: 'chart',
      title:
        d.extracted === undefined || d.extracted === null
          ? 'No answer recorded; the highlights show what the question is about'
          : passed
            ? `Read it right: ${showAnswer(d.extracted)}`
            : `Said ${showAnswer(d.extracted)}; the answer is ${showAnswer(d.expected)}`,
      tone: passed ? 'good' : passed === false ? 'bad' : 'neutral',
      legend: [{ kind: 'accent', label: 'What the question is about (values from the answer key)' }],
    };
  }
  return null;
}

export function OverlaySvg({ layout, plan }: { layout: VisionLayout; plan: OverlayPlan }) {
  const W = layout.width;
  const H = layout.height;
  return (
    <svg className="vz-vov" viewBox={`0 0 ${W} ${H}`} preserveAspectRatio="none" aria-hidden="true">
      {plan.kind === 'cells' &&
        layout.grids!.flatMap((g) =>
          plan.marks!.map((m) => {
            const r = cellRect(g, m.cell);
            if (!r) return null;
            return (
              <g key={`${g.id}-${m.cell}`} className={cx('vz-cell', m.status)}>
                <rect x={r.x + 3} y={r.y + 3} width={r.w - 6} height={r.h - 6} rx={6} />
                {m.status === 'wrong' && <path d={`M${r.x + r.w - 26} ${r.y + 8} l16 16 M${r.x + r.w - 10} ${r.y + 8} l-16 16`} className="vz-cell-x" />}
                {g.id !== 'RIGHT' && (m.change || m.said) && (
                  <text x={r.x + r.w / 2} y={r.y + r.h - 8} textAnchor="middle">
                    {m.said && m.change && m.said !== m.change ? `said ${m.said}` : (m.change ?? m.said)}
                  </text>
                )}
              </g>
            );
          }),
        )}
      {plan.kind === 'count' &&
        [...layout.targets!].sort((a, b) => Math.round(a.y / 140) - Math.round(b.y / 140) || a.x - b.x).map((t, i) => (
          <g key={i} className="vz-target" style={{ animationDelay: `${i * 60}ms` }}>
            <circle cx={t.x} cy={t.y} r={t.r + 9} />
            <g transform={`translate(${t.x + t.r + 10} ${t.y - t.r - 10})`}>
              <circle r={22} />
              <text y={9} textAnchor="middle">
                {i + 1}
              </text>
            </g>
          </g>
        ))}
      {plan.kind === 'chart' &&
        layout.highlights!.map((h, i) => {
          // Labels scale with the picture so they stay readable when it is shown small.
          const fs = W / 46;
          const tagW = (label: string) => label.length * fs * 0.6 + fs;
          const tagH = fs * 1.5;
          return h.shape === 'rect' ? (
            <g key={i} className="vz-hl" style={{ strokeWidth: W / 260 }}>
              <rect x={h.x - 3} y={h.y - 3} width={(h.w ?? 0) + 6} height={(h.h ?? 0) + 6} rx={4} />
              {h.label && (
                <g transform={`translate(${h.x + (h.w ?? 0) / 2} ${h.y - tagH * 0.7})`}>
                  <rect x={-tagW(h.label) / 2} y={-tagH * 0.75} width={tagW(h.label)} height={tagH} rx={tagH / 2} className="vz-hl-tag" />
                  <text y={fs * 0.3} textAnchor="middle" style={{ fontSize: fs }}>
                    {h.label}
                  </text>
                </g>
              )}
            </g>
          ) : (
            <g key={i} className="vz-hl" style={{ strokeWidth: W / 260 }}>
              <circle cx={h.x} cy={h.y} r={fs * 0.8} />
              {h.label && (
                <g transform={`translate(${h.x + fs} ${h.y - fs})`}>
                  <rect x={0} y={-tagH * 0.75} width={tagW(h.label)} height={tagH} rx={tagH / 2} className="vz-hl-tag" />
                  <text x={fs * 0.5} y={fs * 0.3} style={{ fontSize: fs }}>
                    {h.label}
                  </text>
                </g>
              )}
            </g>
          );
        })}
      {plan.kind === 'lines' &&
        layout.lines!.map((l) => {
          const n = Number(l.key);
          const isTrue = n === plan.trueLine;
          const isModel = n === plan.modelLine;
          if (!isTrue && !isModel) return null;
          return (
            <g key={l.key} className={cx('vz-line', isTrue ? 'truth' : 'model')}>
              <rect x={8} y={l.y} width={W - 16} height={l.h} rx={10} />
              <text x={W - 20} y={l.y + l.h / 2 + 8} textAnchor="end">
                {isTrue && isModel ? 'first mistake · model agrees' : isTrue ? 'first mistake (answer key)' : 'model blamed this line'}
              </text>
            </g>
          );
        })}
    </svg>
  );
}

export function VisionAnswerOverlay({ image, layout, detail, passed }: { image: ChatImage; layout: VisionLayout; detail: ScoreDetail; passed: boolean | null }) {
  const plan = overlayPlan(layout, detail, passed);
  const [show, setShow] = useState(true);
  if (!plan) return null;
  const url = image.path ? testImageUrl(image.path) : '';
  return (
    <div className="vz-vision">
      <VizHeadline eyebrow="The model’s answer, drawn on the picture it saw" title={plan.title} tone={plan.tone} big={plan.big} bigSub={plan.bigSub} />
      <div className="vz-cols wide-left">
        <div className="vz-col">
          <figure className="vz-vfig" style={{ aspectRatio: `${layout.width} / ${layout.height}`, ['--ar' as string]: layout.width / layout.height } as CSSProperties}>
            {url ? <img src={url} alt={`Test image ${image.name}`} /> : <div className="vz-missing">Image unavailable</div>}
            {show && <OverlaySvg layout={layout} plan={plan} />}
          </figure>
          <div className="vz-vfig-bar">
            <label className="vz-toggle">
              <input type="checkbox" checked={show} onChange={(e) => setShow(e.target.checked)} /> Show the overlay
            </label>
            <VizLegend items={plan.legend} />
          </div>
        </div>
        <div className="vz-col narrow">
          {plan.kind === 'cells' ? (
            <AnswerVsTruth
              strike={false}
              answer={
                <span className="vz-chips">
                  {plan.marks!.filter((m) => m.status !== 'missed').map((m) => (
                    <span key={m.cell} className={cx('vz-chip', m.status === 'correct' ? 'good' : 'bad')}>
                      {m.cell}
                      {m.said ? ` ${m.said}` : ''}
                    </span>
                  ))}
                  {plan.marks!.every((m) => m.status === 'missed') && '(none)'}
                </span>
              }
              truth={
                <span className="vz-chips">
                  {plan.marks!.filter((m) => m.status !== 'wrong' || m.change).map((m) => (
                    <span key={m.cell} className={cx('vz-chip', m.status === 'missed' ? 'missed' : 'key')}>
                      {m.cell}
                      {m.change ? ` ${m.change}` : ''}
                    </span>
                  ))}
                </span>
              }
              correct={passed}
              truthLabel="Answer key"
            />
          ) : (
            <AnswerVsTruth answer={showAnswer(detail.extracted)} truth={showAnswer(detail.expected)} correct={passed} truthLabel="Answer key" />
          )}
          {detail.formatOk === false && <div className="vz-missing">The answer format was not followed (a fallback extraction was used).</div>}
        </div>
      </div>
    </div>
  );
}
