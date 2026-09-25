/**
 * Human judging for judged Arena games (debate, courtroom) that no judge model
 * could decide — no judge keys, or every judge shares a debater's vendor.
 * Same conventions as Blind Review: you see "Side A" / "Side B" only (the same
 * blinded packet the judge models get), score the rubric, pick a winner, and
 * the identities are revealed after you submit.
 */
import { useEffect, useMemo, useState } from 'react';
import type { CSSProperties } from 'react';
import { useAsync, useLocalStorage } from '../hooks.ts';
import { Link, pathOf } from '../router.tsx';
import { useToast, useViewerCaption } from '../context.tsx';
import { Callout, Empty, ErrorState, LoadingPage, PageHead, cx } from '../components/ui.tsx';
import { Icon } from '../components/icons.tsx';
import { arenaApi, type JudgingPacket } from '../arena/client.ts';
import { entrantMap, useTournament } from '../arena/useTournament.ts';
import '../arena/arena.css';
import '../arena/formats.css';

const SIDE_COLOR = { A: '#d97706', B: '#0891b2' } as const;

interface Block {
  title: string;
  body: string;
}

function sections(material: string): Block[] {
  const out: Block[] = [];
  const parts = material.split(/^== (.+) ==$/m);
  if (parts[0]!.trim()) out.push({ title: '', body: parts[0]!.trim() });
  for (let i = 1; i < parts.length; i += 2) out.push({ title: parts[i]!, body: (parts[i + 1] ?? '').trim() });
  return out;
}

function Transcript({ body }: { body: string }) {
  const speeches = body.split(/\n(?=\[)/).map((chunk) => {
    const m = chunk.match(/^\[([^\]]+)\]\n?([\s\S]*)$/);
    return m ? { head: m[1]!, text: m[2]!.trim() } : { head: '', text: chunk };
  });
  return (
    <div className="hj-speeches">
      {speeches.map((s, i) => {
        const side = /Side A/.test(s.head) ? 'A' : /Side B/.test(s.head) ? 'B' : null;
        const cut = s.text.match(/\n?\[Cut by the harness:[^\]]*\]$/);
        return (
          <div key={i} className={cx('hj-speech', side === 'B' && 'right')} style={{ ['--c' as string]: side ? SIDE_COLOR[side] : 'var(--text-3)' } as CSSProperties}>
            <div className="hj-sp-head">{s.head}</div>
            <p>{cut ? s.text.slice(0, cut.index) : s.text}</p>
            {cut && <span className="db-pen">✂ {cut[0].replace(/^\n?\[|\]$/g, '')}</span>}
          </div>
        );
      })}
    </div>
  );
}

function Packet({ p }: { p: JudgingPacket }) {
  const blocks = sections(p.material).filter((b) => b.title !== 'HOW TO JUDGE' && b.title !== 'ANSWER FORMAT');
  return (
    <div className="hj-packet">
      {blocks.map((b) =>
        b.title === 'TRANSCRIPT' ? (
          <section key={b.title}>
            <h3 className="eyebrow">Transcript</h3>
            <Transcript body={b.body} />
          </section>
        ) : (
          <section key={b.title || 'intro'}>
            {b.title && <h3 className="eyebrow">{b.title.toLowerCase()}</h3>}
            <p className="hj-text">{b.body}</p>
          </section>
        ),
      )}
    </div>
  );
}

export default function ArenaJudgePage({ id }: { id: string }) {
  const toast = useToast();
  const { detail: d, reload: reloadT } = useTournament(id);
  const packets = useAsync(() => arenaApi.judging(id), [id]);
  const [key, setKey] = useState<string | null>(null);
  const [scores, setScores] = useState<Record<'A' | 'B', Record<string, number>>>({ A: {}, B: {} });
  const [winner, setWinner] = useState<'A' | 'B' | null>(null);
  const [why, setWhy] = useState('');
  const [rater, setRater] = useLocalStorage('gauntlet.rater', '');
  const [busy, setBusy] = useState(false);
  const [revealed, setRevealed] = useState<{ key: string; text: string } | null>(null);
  useViewerCaption('Human judging: you read the debate as “Side A” and “Side B”, exactly like the judge models, and decide who argued better. The models’ names are revealed only after you submit.');

  const list = packets.data ?? [];
  const current = list.find((p) => p.key === key) ?? list[0];
  useEffect(() => {
    setScores({ A: {}, B: {} });
    setWinner(null);
    setWhy('');
  }, [current?.key]);
  const ents = entrantMap(d);
  const complete = useMemo(() => current && current.rubric.every((r) => scores.A[r.key] && scores.B[r.key]), [current, scores]);

  if (packets.error) return <div className="page"><ErrorState error={packets.error} onRetry={packets.reload} /></div>;
  if (!packets.data || !d) return <LoadingPage />;

  const submit = async () => {
    if (!current || !winner) return;
    setBusy(true);
    try {
      const r = await arenaApi.verdict(id, current.key, { winner, rationale: [why.trim(), rater ? `(${rater})` : ''].filter(Boolean).join(' '), ...(complete ? { scores: { side_a: scores.A, side_b: scores.B } } : {}) });
      const g = d.games.find((x) => x.key === current.key);
      const who = g && r.winner !== null ? ents.get(g.players[r.winner])?.label : null;
      setRevealed({ key: current.key, text: who ? `Side ${winner} was ${who}.` : 'Verdict recorded.' });
      toast.success(r.resumed ? 'Verdict recorded: the tournament continues' : 'Verdict recorded');
      setKey(null);
      packets.reload();
      reloadT();
    } catch (e) {
      toast.error(e);
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="page arena-judge">
      <PageHead
        eyebrow={
          <span>
            <Link to="/arena">The Arena</Link> · <Link to={pathOf('arena', id)}>{d.manifest.name}</Link>
          </span>
        }
        title="Human judging"
        sub="Games no judge model could decide. You see exactly what the judges see: Side A and Side B, in a random order, with every model name removed."
        actions={
          <Link className="btn ghost" to={pathOf('arena', id)}>
            Back to tournament
          </Link>
        }
      />
      {revealed && (
        <div style={{ marginBottom: 14 }}>
          <Callout tone="info" icon={<Icon.Check />}>
            <b>Revealed:</b> {revealed.text}
          </Callout>
        </div>
      )}
      {!current ? (
        <div className="card">
          <Empty icon={<Icon.Check />} title="Nothing to judge">
            Every judged game in this tournament has a verdict.
          </Empty>
        </div>
      ) : (
        <div className="hj-grid">
          <div className="stack">
            {list.length > 1 && (
              <div className="row wrap" style={{ gap: 6 }}>
                {list.map((p) => (
                  <button key={p.key} className={cx('btn xs', p.key === current.key && 'primary')} onClick={() => setKey(p.key)}>
                    {p.roundName} · game {p.gameNo}
                  </button>
                ))}
              </div>
            )}
            {current.note && <Callout tone="warn">{current.note}</Callout>}
            <div className="card">
              <div className="card-body">
                <Packet p={current} />
              </div>
            </div>
          </div>
          <aside className="card hj-form sticky-col">
            <div className="card-head">
              <div className="t">
                <h2>Your scorecard</h2>
                <div className="desc">1 = very poor · 10 = outstanding. Scores are optional; the winner is not.</div>
              </div>
            </div>
            <div className="card-body stack">
              <div className="hj-rubric">
                <span />
                <b style={{ color: SIDE_COLOR.A }}>Side A</b>
                <b style={{ color: SIDE_COLOR.B }}>Side B</b>
                {current.rubric.map((r) => (
                  <div key={r.key} className="hj-row">
                    <span title={r.help}>{r.label}</span>
                    {(['A', 'B'] as const).map((s) => (
                      <input
                        key={s}
                        className="input tnum"
                        type="number"
                        min={1}
                        max={10}
                        aria-label={`${r.label}, Side ${s}`}
                        value={scores[s][r.key] ?? ''}
                        onChange={(e) => setScores((x) => ({ ...x, [s]: { ...x[s], [r.key]: Math.max(1, Math.min(10, Number(e.target.value) || 1)) } }))}
                      />
                    ))}
                  </div>
                ))}
              </div>
              <div className="hj-pick" role="radiogroup" aria-label="Winner">
                {(['A', 'B'] as const).map((s) => (
                  <button key={s} role="radio" aria-checked={winner === s} className={cx('hj-side', winner === s && 'on')} style={{ ['--c' as string]: SIDE_COLOR[s] } as CSSProperties} onClick={() => setWinner(s)}>
                    Side {s} wins
                  </button>
                ))}
              </div>
              <label className="stack tight">
                <span className="mini-title">Why (one or two sentences)</span>
                <textarea className="input" rows={3} value={why} onChange={(e) => setWhy(e.target.value)} placeholder="Side A answered the evidence point by point…" />
              </label>
              <label className="stack tight">
                <span className="mini-title">Your name (optional, saved on this computer)</span>
                <input className="input" value={rater} onChange={(e) => setRater(e.target.value)} placeholder="e.g. Sam" />
              </label>
            </div>
            <div className="card-foot">
              <button className="btn primary lg block" disabled={!winner || busy} onClick={submit}>
                <Icon.Check /> {busy ? 'Saving…' : 'Submit verdict'}
              </button>
            </div>
          </aside>
        </div>
      )}
    </div>
  );
}
