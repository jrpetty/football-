/** Studio: the 30-word performance summaries as facts to drop into the narration script. */
import { useMemo, useState } from 'react';
import { Card, CopyButton } from '../components/ui.tsx';
import type { StudioFact } from '../types.ts';
import { SummaryGlyph } from './PerformanceSummary.tsx';

export function StudioFacts({ facts, onInsert }: { facts: StudioFact[] | undefined; onInsert: (line: string) => void }) {
  const tests = useMemo(() => [...new Map((facts ?? []).map((f) => [f.testId, f.testName])).entries()], [facts]);
  const [testId, setTestId] = useState<string>('');
  if (!facts?.length) return null;
  const cur = testId || tests[0]?.[0] || '';
  const shown = facts.filter((f) => f.testId === cur);
  return (
    <Card
      className="no-broadcast"
      title={
        <span className="row" style={{ gap: 8 }}>
          <SummaryGlyph /> Performance facts
        </span>
      }
      desc="30-word summaries of each model on each test, built from the recorded results. Add them to the script as they are: every number in them is checked."
    >
      <select className="select" value={cur} onChange={(e) => setTestId(e.target.value)} aria-label="Test" style={{ width: '100%', marginBottom: 10 }}>
        {tests.map(([id, name]) => (
          <option key={id} value={id}>
            {name}
          </option>
        ))}
      </select>
      <ul className="facts-list">
        {shown.map((f) => {
          const line = `${f.label} on ${f.testName}: ${f.text}`;
          return (
            <li key={f.contestantId}>
              <div>
                <b>{f.label}</b> {f.source === 'ai' && <span className="badge outline">AI-written</span>}
                <p>{f.text}</p>
              </div>
              <div className="row" style={{ gap: 6 }}>
                <button type="button" className="btn xs" onClick={() => onInsert(line)}>
                  Add to script
                </button>
                <CopyButton text={line} label="Copy" />
              </div>
            </li>
          );
        })}
      </ul>
    </Card>
  );
}
