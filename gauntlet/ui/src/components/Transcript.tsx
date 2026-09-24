/** Transcript viewer: each model call as chat bubbles with timing / tokens / cost. */
import { memo, useState } from 'react';
import type { ChatImage, TranscriptEntry } from '../types.ts';
import { fmtCost, fmtMs, fmtTokens } from '../format.ts';
import { CopyButton, cx } from './ui.tsx';
import { VisionImage } from './VisionImage.tsx';
import { testImageUrl } from '../vision.ts';

const LONG = 1400;

function Bubble({ role, text, label, images }: { role: 'user' | 'assistant' | 'system'; text: string; label?: string; images?: ChatImage[] }) {
  const [expanded, setExpanded] = useState(text.length <= LONG);
  const shown = expanded ? text : `${text.slice(0, LONG)}…`;
  return (
    <div className={cx('bubble', role)}>
      <div className="bubble-head">
        <span>{label ?? role}</span>
        <CopyButton text={text} iconOnly label={`Copy ${role} message`} />
      </div>
      {images && images.length > 0 && (
        <div className="vi-row" style={{ marginBottom: 8 }}>
          {images.map((img, k) => (
            <VisionImage key={k} src={img.path ? testImageUrl(img.path) : ''} name={img.name} width={img.width} height={img.height} size="sm" />
          ))}
        </div>
      )}
      <pre className="bubble-text">{shown || <span className="muted">(empty)</span>}</pre>
      {text.length > LONG && (
        <button type="button" className="btn xs ghost" onClick={() => setExpanded((v) => !v)}>
          {expanded ? 'Collapse' : `Show all ${text.length.toLocaleString()} characters`}
        </button>
      )}
    </div>
  );
}

export const TranscriptView = memo(function TranscriptView({ entries }: { entries: TranscriptEntry[] }) {
  if (!entries?.length) return <div className="chart-empty">No transcript recorded for this case.</div>;
  return (
    <div className="transcript">
      {entries.map((e, i) => {
        const priorMessages = e.messages ?? [];
        // Show only the new user turn(s) since the previous entry to avoid repeating history.
        const prev = i > 0 ? entries[i - 1] : null;
        const sameThread = prev && !prev.judge && !e.judge && prev.messages?.length && priorMessages.length > prev.messages.length;
        const visible = sameThread ? priorMessages.slice(prev!.messages.length + 1) : priorMessages;
        return (
          <section key={i} className={cx('t-entry', e.judge && 'judge')}>
            <header className="t-head">
              <span className="t-label">{e.label ?? `call ${i + 1}`}</span>
              {e.judge && <span className="badge info">judge · billed separately</span>}
              {e.error && <span className="badge bad">error</span>}
              {e.stopReason && e.stopReason !== 'end' && <span className="badge warn">stop: {e.stopReason}</span>}
              <span className="spacer" />
              <span className="t-metrics tnum">
                <span title="Time to first token">TTFT {fmtMs(e.ttftMs)}</span>
                <span title="Total call time">{fmtMs(e.totalMs)}</span>
                <span title="Input / output tokens">
                  {fmtTokens(e.usage?.inputTokens)} in · {fmtTokens(e.usage?.outputTokens)} out
                  {e.usage?.reasoningTokens ? ` (${fmtTokens(e.usage.reasoningTokens)} reasoning)` : ''}
                </span>
                <span title="Cost of this call">{fmtCost(e.costUsd)}</span>
                {e.retries > 0 && <span className="warn-text">{e.retries} retr{e.retries === 1 ? 'y' : 'ies'}</span>}
              </span>
            </header>
            {e.system && (
              <details className="collapse sys">
                <summary>System prompt · {e.system.length.toLocaleString()} chars</summary>
                <div className="inner">
                  <pre className="code">{e.system}</pre>
                </div>
              </details>
            )}
            {sameThread && <div className="muted" style={{ fontSize: '0.78rem' }}>… {prev!.messages.length + 1} earlier messages in this conversation</div>}
            <div className="bubbles">
              {visible.map((m, j) => (
                <Bubble key={j} role={m.role} text={m.content} images={m.images} />
              ))}
              {e.error ? <div className="callout bad">{e.error}</div> : <Bubble role="assistant" text={e.response} label="response" />}
            </div>
          </section>
        );
      })}
    </div>
  );
});
