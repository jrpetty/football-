/**
 * Replay stage for Chain of Whispers (standard and hard): the fact river up to
 * the current rewrite, the text of that rewrite with every surviving fact
 * highlighted (green = intact, amber = wording drifted), a word-limit meter,
 * and at the end a "what survived" scoreboard.
 */
import { useMemo } from 'react';
import type { ReplayData } from '../../types.ts';
import { cx } from '../ui.tsx';
import { VizHeadline, WordMeter } from './VizHeadline.tsx';
import { ToneIcon, VizLegend } from './VizLegend.tsx';
import { WhispersRiver } from './WhispersRiver.tsx';
import type { WhispersModel } from './vizModel.ts';
import { highlightFacts, visualOf, whisperDelta, whisperHeadline, whispersModel } from './vizModel.ts';
import './viz.css';

export function useWhispersModel(replay: ReplayData, detail: unknown): WhispersModel | null {
  return useMemo(() => whispersModel(detail, visualOf(replay, 'chain-of-whispers')), [replay, detail]);
}

function RoundText({ model, col, fallback }: { model: WhispersModel; col: number; fallback?: string }) {
  const c = model.columns[col]!;
  const labelOf = useMemo(() => new Map(model.facts.map((f) => [f.id, f.label])), [model.facts]);
  if (c.failed) return <div className="vz-missing">Empty or refused reply: the chain broke here.</div>;
  if (!c.text) {
    return (
      <div className="vz-text">
        {fallback && <p>{fallback}</p>}
        <div className="vz-missing">The full text and fact highlights were not recorded for this run (recorded from test v1.1.1 / hard v1.0.1 on).</div>
      </div>
    );
  }
  const segs = highlightFacts(c.text, c.trace);
  return (
    <div className="vz-text">
      {segs.map((s, i) =>
        s.status ? (
          <mark key={i} className={s.status} title={s.factIds!.map((id) => labelOf.get(id) ?? id).join(', ')}>
            {s.text}
            <sup>{s.factIds!.map((id) => labelOf.get(id) ?? id).join(' · ')}</sup>
          </mark>
        ) : (
          <span key={i}>{s.text}</span>
        ),
      )}
    </div>
  );
}

export function WhatSurvived({ model, col, final = true }: { model: WhispersModel; col: number; final?: boolean }) {
  const last = col;
  return (
    <div className="vz-card vz-survived">
      <div className="vz-card-k">{final ? `What survived · ${model.survived} of ${model.total}` : 'Still alive so far'}</div>
      <ul>
        {model.facts.map((f, i) => {
          const s = model.status[i]![last]!;
          const diedAt = model.status[i]!.findIndex((x) => x === 'lost');
          return (
            <li key={f.id} className={`is-${s}`}>
              <ToneIcon tone={s === 'kept' ? 'good' : s === 'changed' ? 'warn' : 'bad'} />
              <span className="vz-sv-l">{f.label}</span>
              <span className="vz-sv-r">{s === 'kept' ? 'intact' : s === 'changed' ? 'drifted' : diedAt > 0 ? `gone in ${model.columns[diedAt]!.label.toLowerCase()}` : 'gone'}</span>
            </li>
          );
        })}
      </ul>
    </div>
  );
}

export function WhispersStage({ model, idx, video, fallbackText }: { model: WhispersModel; idx: number; video: boolean; fallbackText?: string }) {
  const col = Math.min(idx, model.columns.length - 1);
  const c = model.columns[col]!;
  const head = whisperHeadline(model, col);
  const d = whisperDelta(model, col);
  const atEnd = col === model.columns.length - 1;
  const drifted = model.facts.filter((_, i) => model.status[i]![col] === 'changed');
  return (
    <div className={cx('vz-stage vz-whispers', video && 'video')}>
      <VizHeadline
        eyebrow={
          <>
            {c.round === 0 ? 'The source' : `Rewrite ${c.round} of ${model.columns.length - 1}`} · {c.label}
          </>
        }
        title={head.title}
        tone={head.tone}
        big={`${d.kept}/${model.facts.length}`}
        bigSub="facts intact"
      />
      <WhispersRiver model={model} current={col} />
      <VizLegend
        items={[
          { kind: 'good', label: 'Fact intact' },
          { kind: 'warn', label: 'Wording drifted (no longer counts)' },
          { kind: 'bad', label: 'Lost' },
        ]}
      />
      <div className="vz-cols">
        <div className="vz-col">
          <div className="vz-card-k">{c.round === 0 ? 'The original story' : `What the model wrote in this ${c.kind}`}</div>
          <RoundText model={model} col={col} fallback={fallbackText} />
        </div>
        <div className="vz-col">
          {c.round > 0 && c.words !== null && <WordMeter words={c.words} limit={c.limit} label={c.kind === 'summary' ? 'Summary words' : 'Story words'} />}
          {drifted.length > 0 && model.hasTrace && (
            <div className="vz-drift">
              {drifted.slice(0, 2).map((f) => (
                <div key={f.id}>
                  <span className="vz-k">Was</span> <s>{f.canonical}</s>
                  <br />
                  <span className="vz-k">Now</span> <em>{model.columns[col]!.trace?.[f.id]?.sentence ?? ''}</em>
                </div>
              ))}
            </div>
          )}
          <WhatSurvived model={model} col={col} final={atEnd} />
        </div>
      </div>
    </div>
  );
}
