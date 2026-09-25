/**
 * Replay stage for Needle in a Haystack (standard and hard). One replay step
 * per needle, in reading order: the document strip fills up to the needle,
 * the headline says what the model did, its answer sits next to the truth,
 * and the passage (with the decoy) is zoomed in. "Who reads to the end?"
 * compares the found-rate at the start, middle and end of the document.
 */
import { useMemo } from 'react';
import type { ReplayData, ReplayFrame } from '../../types.ts';
import { cx } from '../ui.tsx';
import { NeedleStrip } from './NeedleStrip.tsx';
import { AnswerVsTruth, ScoreBars, VizHeadline } from './VizHeadline.tsx';
import { VizLegend } from './VizLegend.tsx';
import type { NeedleModel, NeedleView, Passage } from './vizModel.ts';
import { needleForFrame, needleHeadline, needleModel, needleZones, visualOf, wordAt } from './vizModel.ts';
import './viz.css';

export function useNeedleModel(replay: ReplayData, detail: unknown): NeedleModel | null {
  return useMemo(() => needleModel(detail, visualOf(replay, 'needle-haystack')), [replay, detail]);
}

function partCaption(n: NeedleView, i: number): string {
  const count = n.parts?.length ?? 1;
  if (n.kind === 'superseded') return i === 0 ? 'The first value (corrected later in the text)' : 'The correction: this is the answer';
  if (n.kind === 'aggregate') return `Figure ${i + 1} of ${count} to add up`;
  if (count > 1) return `Clue ${i + 1} of ${count} (the answer chains these together)`;
  return 'Where the answer is';
}

function PassageView({ p, words, kind, caption }: { p: Passage; words: number | null; kind: 'answer' | 'decoy'; caption: string }) {
  return (
    <figure className={cx('vz-passage', kind)}>
      <figcaption>
        {caption} · <span className="tnum">{p.depth !== null ? wordAt(p.depth, words) : ''}</span>
      </figcaption>
      <p>
        {p.before && <span className="vz-ctx">… {p.before} </span>}
        <mark>{p.text}</mark>
        {p.after && <span className="vz-ctx"> {p.after} …</span>}
      </p>
    </figure>
  );
}

export function WhoReadsToTheEnd({ model, revealed }: { model: NeedleModel; revealed?: Set<string> }) {
  const zones = revealed ? needleZones(model.needles.filter((n) => revealed.has(n.id))) : model.zones;
  return (
    <div className="vz-card">
      <div className="vz-card-k">Who reads to the end? {revealed && revealed.size < model.total ? 'Found so far, by position' : 'Found, by position'}</div>
      <ScoreBars
        rows={zones.map((z) => ({
          label: z.label,
          value: z.total ? z.found / z.total : null,
          tone: z.total === 0 ? undefined : z.found === z.total ? 'good' : z.found / z.total >= 0.5 ? 'warn' : 'bad',
          right: z.total ? `${z.found}/${z.total}` : revealed ? 'not yet' : 'none here',
        }))}
      />
    </div>
  );
}

export function NeedleStage({ model, frame, frames, idx, video }: { model: NeedleModel; frame: ReplayFrame; frames: ReplayFrame[]; idx: number; video: boolean }) {
  const cur: NeedleView | undefined = needleForFrame(model, frame.label);
  const revealed = useMemo(() => {
    const s = new Set<string>();
    for (const f of frames.slice(0, idx + 1)) {
      const n = needleForFrame(model, f.label);
      if (n) s.add(n.id);
    }
    return s;
  }, [frames, idx, model]);
  const foundSoFar = model.needles.filter((n) => revealed.has(n.id) && n.outcome === 'found').length;
  if (!cur) return null;
  const head = needleHeadline(cur);
  const decoyAnswer = cur.outcome === 'decoy' || cur.outcome === 'old-value';
  return (
    <div className={cx('vz-stage vz-needle', video && 'video')}>
      <VizHeadline
        eyebrow={
          <>
            {cur.id} · {cur.kindLabel} · {Math.round(cur.depth)}% into the document
          </>
        }
        title={head.title}
        tone={head.tone}
        big={`${foundSoFar}/${revealed.size}`}
        bigSub="found so far"
      />
      <NeedleStrip model={model} current={cur} revealed={revealed} cursor={cur.depth} />
      <div className="vz-cols">
        <div className="vz-col">
          <div className="vz-q">
            <span className="vz-k">The question</span>
            <p>{cur.question}</p>
          </div>
          <AnswerVsTruth
            answer={cur.answer ?? '(no answer)'}
            truth={cur.expected}
            correct={cur.outcome === 'found'}
            note={
              decoyAnswer
                ? cur.outcome === 'old-value'
                  ? 'That value was corrected later in the text; only the correction counts.'
                  : 'That is the near-miss decoy planted elsewhere in the text.'
                : cur.outcome === 'hedged'
                  ? 'Offering more than one answer counts as wrong.'
                  : undefined
            }
          />
          {model.hasPassages ? (
            <div className="vz-passages">
              {cur.parts?.map((p, i) => <PassageView key={`p${i}`} p={p} words={model.words} kind="answer" caption={partCaption(cur, i)} />)}
              {cur.decoys?.slice(0, 2).map((p, i) => <PassageView key={`d${i}`} p={p} words={model.words} kind="decoy" caption="A look-alike decoy" />)}
            </div>
          ) : (
            <div className="vz-missing">The passage text was not recorded for this run (it is recorded from test v1.1.1 / hard v1.0.1 on). The strip above still shows where the answer and the decoy sit.</div>
          )}
        </div>
        <div className="vz-col narrow">
          <WhoReadsToTheEnd model={model} revealed={revealed} />
          <VizLegend
            items={[
              { kind: 'good', label: 'Found' },
              { kind: 'warn', label: 'Took the decoy / old value' },
              { kind: 'bad', label: 'Wrong or gave up' },
              { kind: 'muted', label: 'Not reached yet' },
              { kind: 'decoy', label: 'Decoy position' },
            ]}
          />
        </div>
      </div>
    </div>
  );
}
