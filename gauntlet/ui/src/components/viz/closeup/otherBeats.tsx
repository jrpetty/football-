/** Horizon levels (reusing the Horizon rung card) and a plain fallback for anything a picture cannot be drawn for. */
import { useMemo } from 'react';
import { viewerText } from '../../../../../src/presenter/visuals/common.ts';
import { statedLine } from '../../../../../src/presenter/visuals/closeup.ts';
import { horizonVisual, rungCaption, rungHeadline } from '../../../../../src/presenter/visuals/horizon.ts';
import { HorizonRungCard } from '../HorizonRungCard.tsx';
import { BeatLayout, Chip, type Beat } from './parts.tsx';
import type { KindProps } from './types.ts';

/** The question as readable paragraphs (no answer-format boilerplate), first few only. */
function Paragraphs({ text, max = 6 }: { text: string; max?: number }) {
  const paras = viewerText(text)
    .split(/\n\s*\n/)
    .map((x) => x.trim())
    .filter(Boolean);
  return (
    <div className="lc-paras">
      {paras.slice(0, max).map((x, i) => (
        <p key={i}>{x.length > 600 ? `${x.slice(0, 599)}…` : x}</p>
      ))}
      {paras.length > max && <p className="lc-more">+ {paras.length - max} more paragraphs in the full prompt</p>}
    </div>
  );
}

export function TextBeats(p: KindProps) {
  const question: Beat = { sub: p.rung, alt: viewerText(p.prompt).slice(0, 1200), node: <Paragraphs text={p.prompt} /> };
  const said = p.a ? statedLine(p.a) : '';
  const answer: Beat | null = p.a ? { sub: 'Its final answer', alt: said ? `The model answered: ${said}` : 'No final answer line.', node: <p className="lc-said">{said || 'No final answer line'}</p> } : null;
  const key = p.q.expected;
  const keyText = typeof key === 'string' || typeof key === 'number' ? String(key) : Array.isArray(key) && typeof key[0] === 'string' ? key[0] : null;
  const keyBeat: Beat | null = keyText ? { alt: `The answer: ${keyText}`, node: <p className="lc-said key">{keyText}</p> } : null;
  return <BeatLayout question={question} prompt={p.prompt} answer={answer} reply={p.reply} result={null} verdict={p.verdict} sentence={null} answerHead={p.answerHead} keyBeat={keyBeat} />;
}

export function HorizonBeats(p: KindProps) {
  const caption = rungCaption(p.q.testId, p.prompt, p.q.notes) ?? p.rung ?? null;
  const m = useMemo(() => {
    if (!p.a) return null;
    try {
      return horizonVisual(p.a);
    } catch {
      return null;
    }
  }, [p.a]);
  const key = useMemo(() => {
    try {
      return horizonVisual({ ...p.q, replies: ['FINAL ANSWER: '] });
    } catch {
      return null;
    }
  }, [p.q]);
  const question: Beat = {
    sub: caption ? <Chip tone="accent">{caption}</Chip> : undefined,
    alt: viewerText(p.prompt).slice(0, 1200),
    node: <Paragraphs text={p.prompt} max={4} />,
  };
  const said = p.a ? statedLine(p.a) : '';
  const answer: Beat | null = p.a
    ? {
        sub: 'Its final answer',
        alt: said ? `The model answered: ${said.slice(0, 300)}` : 'No final answer line.',
        node: <p className="lc-said">{said ? (said.length > 160 ? `${said.slice(0, 159)}…` : said) : 'No final answer line'}</p>,
      }
    : null;
  const result: Beat | null = m ? { alt: rungHeadline(m, p.modelLabel), node: <HorizonRungCard m={m} mode="inspector" modelLabel={p.modelLabel} /> } : null;
  const keyBeat: Beat | null =
    key && key.compare.kind === 'integer'
      ? { alt: `The answer: ${key.compare.want}`, node: <p className="lc-said key tnum">{key.compare.want}</p> }
      : key && key.compare.kind === 'plan'
        ? { alt: `The shortest plan takes ${key.compare.optimal} moves.`, node: <p className="lc-said key">{key.compare.optimal} moves is the minimum</p> }
        : key && key.compare.kind === 'grid'
          ? { alt: 'The solved picture.', node: <pre className="lc-said key lc-gridkey">{key.compare.want.join('\n')}</pre> }
          : null;
  return <BeatLayout question={question} prompt={p.prompt} answer={answer} reply={p.reply} result={result} verdict={p.verdict} sentence={m ? rungHeadline(m, p.modelLabel) : null} answerHead={p.answerHead} keyBeat={keyBeat} />;
}
