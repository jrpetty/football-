/** Studio → Thumbnails & Shorts: the Head to Head Shorts card for two models of this run. */
import { useEffect, useState } from 'react';
import { useAsync } from '../hooks.ts';
import { href } from '../router.tsx';
import { Card, ErrorState, Field } from '../components/ui.tsx';
import { Icon } from '../components/icons.tsx';
import { nameOf } from '../../../src/versus/words.ts';
import { versusApi } from './client.ts';
import { VersusCardPanel } from './VersusCardPanel.tsx';
import './versus.css';

export function StudioVersusCard({ runId }: { runId: string }) {
  const opts = useAsync(() => versusApi.options(runId), [runId]);
  const [pair, setPair] = useState<[string, string] | null>(null);
  useEffect(() => {
    if (opts.data?.suggested) setPair(opts.data.suggested);
  }, [opts.data]);
  const match = useAsync(() => (pair && pair[0] !== pair[1] ? versusApi.get(pair[0], pair[1], runId) : Promise.resolve(null)), [pair?.[0], pair?.[1], runId]);
  const fighters = opts.data?.fighters ?? [];
  if (opts.data && fighters.length < 2) return null;
  const set = (i: 0 | 1, id: string) => setPair((p) => (p ? (i === 0 ? [id, p[1]] : [p[0], id]) : null));
  return (
    <Card
      className="no-broadcast vx-studio"
      title="Head to Head Shorts card"
      desc="Two models of this run, round by round, on one vertical card."
      tools={
        pair && (
          <a className="btn sm" href={href('/versus', { a: pair[0], b: pair[1], run: runId })}>
            <Icon.External /> Open Head to Head
          </a>
        )
      }
    >
      {opts.error ? (
        <ErrorState error={opts.error} onRetry={opts.reload} />
      ) : (
        <div className="stack">
          {pair && (
            <div className="row wrap" style={{ gap: 12 }}>
              {([0, 1] as const).map((i) => (
                <Field key={i} label={i === 0 ? 'Left corner' : 'Right corner'}>
                  <select className="select" value={pair[i]} onChange={(e) => set(i, e.target.value)}>
                    {fighters.map((f) => (
                      <option key={f.id} value={f.id}>
                        {nameOf(f)}
                      </option>
                    ))}
                  </select>
                </Field>
              ))}
            </div>
          )}
          {pair && pair[0] === pair[1] && <p className="muted">Pick two different models.</p>}
          {match.error && <ErrorState error={match.error} onRetry={match.reload} />}
          {match.data && <VersusCardPanel data={match.data} runId={runId} maxHeight={560} />}
        </div>
      )}
    </Card>
  );
}
