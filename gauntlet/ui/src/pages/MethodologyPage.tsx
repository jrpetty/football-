import type { ReactNode } from 'react';
import { useMeta, useViewerCaption } from '../context.tsx';
import { Link } from '../router.tsx';
import { Icon } from '../components/icons.tsx';
import { PageHead, Skeleton } from '../components/ui.tsx';
import { Jargon } from '../components/clarity/Jargon.tsx';
import { FAIR_PLAY_NOTICE, FAIR_PLAY_VERSION } from '../../../src/core/fair-play.ts';

function Principle({ n, icon, title, children }: { n: number; icon: ReactNode; title: string; children: ReactNode }) {
  return (
    <article className="principle">
      <div className="pr-top">
        <span className="pr-n">{String(n).padStart(2, '0')}</span>
        <span className="pr-icon" aria-hidden="true">
          {icon}
        </span>
      </div>
      <h3>{title}</h3>
      <div className="pr-body">{children}</div>
    </article>
  );
}

export default function MethodologyPage() {
  useViewerCaption('How Gauntlet keeps the contest fair: identical prompts, repeated attempts, cross-company judges and published scoring rules.');
  const { meta, loading } = useMeta();
  const s = meta?.settings;
  return (
    <div className="page method">
      <PageHead
        eyebrow="About the numbers"
        title="Methodology"
        sub="Gauntlet is built so anyone can reproduce a published score: same prompts, same bytes, same seeds, same scoring — recorded with every result."
        actions={
          <div className="row wrap" style={{ gap: 8 }}>
            <span className="badge lg accent">protocol {meta?.protocolVersion ?? '…'}</span>
            <span className="badge lg">harness v{meta?.harnessVersion ?? '…'}</span>
          </div>
        }
      />

      <section className="index-formula card">
        <div className="card-body">
          <div className="eyebrow" style={{ marginBottom: 14 }}>
            How the Gauntlet Index is built
          </div>
          <div className="pipeline" role="img" aria-label="Case scores average into test scores, test scores into category scores, and the weighted category mean times 100 is the Gauntlet Index">
            <div className="pl-step">
              <span className="pl-k">Case</span>
              <b>
                <Jargon dev="score 0–1" plain="one question, scored" />
              </b>
              <span>
                <Jargon dev="one reply, scored by its test’s scorer" plain="0 = wrong, 1 = fully right" />
              </span>
            </div>
            <span className="pl-arrow">→</span>
            <div className="pl-step">
              <span className="pl-k">Test</span>
              <b>
                <Jargon dev="mean of cases × repeats" plain="average of its questions" />
              </b>
              <span>
                <Jargon dev="every repeat of every case counts equally" plain="every try of every question counts the same" />
              </span>
            </div>
            <span className="pl-arrow">→</span>
            <div className="pl-step">
              <span className="pl-k">Category</span>
              <b>mean of its tests</b>
              <span>tests weighted by the suite</span>
            </div>
            <span className="pl-arrow">→</span>
            <div className="pl-step hero">
              <span className="pl-k">Gauntlet Index</span>
              <b>
                <Jargon dev="100 × Σ wᶜ · catᶜ ⁄ Σ wᶜ" plain="average of the categories" />
              </b>
              <span>
                <Jargon dev="weighted mean of category means" plain="shown out of 100" />
              </span>
            </div>
          </div>
          <p className="dim" style={{ marginTop: 16, maxWidth: '90ch' }}>
            Categories are averaged — not tests — so a category with many tests can’t dominate. Missing categories are left out of both numerator and denominator and shown as “—”; the <em>coverage</em> column tells you how much of the suite a model has actually
            completed.
          </p>
        </div>
      </section>

      <div className="principles">
        <Principle n={1} icon={<Icon.Fingerprint />} title="Fixed prompts, versioned & hashed">
          Every test is a file with a semantic version. Its prompts, system prompt, expected answers and scorer config are hashed; each result stores that hash. The suite <em>fingerprint</em> hashes all test hashes, the judge prompts and the protocol version —
          identical fingerprints mean identical conditions.
        </Principle>
        <Principle n={2} icon={<Icon.Copy />} title="Identical bytes to every model">
          No per-model prompt tuning, no “system prompt tricks”. The same rendered text goes to every provider; adapters only translate the wire format. The harness adds exactly one thing: the fixed fair-play notice at the start of every model’s system prompt (below). Judges never get it.
        </Principle>
        <Principle n={3} icon={<Icon.Shuffle />} title="Seeds for simulations">
          Survival Island, the Liar’s Table and the other simulations are driven by seeded random generators. Each seed is one case, and a given seed produces exactly the same world, weather and suspects for every model.
        </Principle>
        <Principle n={4} icon={<Icon.Sparkles />} title="Temperature policy">
          Temperature <b>{s?.temperature ?? 0}</b> wherever the model accepts it; reasoning models that don’t accept a temperature run at their provider default. The policy is recorded per contestant in the run manifest.
        </Principle>
        <Principle n={5} icon={<Icon.Chart />} title="Repeats & 95% confidence intervals">
          Cases run {s?.defaultRepeats ?? 3}× by default. Scores come with 95% percentile-bootstrap confidence intervals: cases (together with all their repeats) are resampled with replacement 2,000 times and the score recomputed; the whisker spans the middle 95%. Overlapping whiskers mean “too close
          to call”.
        </Principle>
        <Principle n={6} icon={<Icon.Eye />} title="Judge panels with fixed prompts">
          Open-ended tasks are graded by a panel ({s?.judges?.length ?? 0} judge{s?.judges?.length === 1 ? '' : 's'} by default) using fixed, versioned judge prompts that are part of the fingerprint. Judges never see which model wrote the answer.
        </Principle>
        <Principle n={7} icon={<Icon.Dollar />} title="Cost from a pricing snapshot">
          Each run stores the per-token prices in force when it started. Cost = tokens × snapshot price, including reasoning and cached tokens. Judge costs are tracked separately and never billed to the contestant.
        </Principle>
        <Principle n={8} icon={<Icon.Layers />} title="Stale-result exclusion">
          The combined leaderboard takes the latest valid result for every model × test × case × repeat. If a test or a model config changed since a result was produced, its hash no longer matches and the result is excluded (and counted as “stale”).
        </Principle>
        <Principle n={9} icon={<Icon.Target />} title="Random baseline">
          A built-in contestant answers uniformly at random from each answer space. It anchors the bottom of the scale: a score near the baseline means “no better than guessing”.
        </Principle>
      </div>

      <section className="card fair-play">
        <div className="card-head">
          <div className="t">
            <h2>
              <Icon.Lock style={{ width: 18, height: 18, display: 'inline', verticalAlign: '-3px', marginRight: 8 }} />
              Fair play &amp; anti-cheating
            </h2>
            <div className="desc">The rules every model plays by — including models tested by hand.</div>
          </div>
        </div>
        <div className="card-body">
          <ul className="rule-list">
            <li>
              <b>Every model is told the rules.</b> Each prompt’s system text starts with the same fixed notice (version {FAIR_PLAY_VERSION}), for API and copy &amp; paste models alike, in every test, simulation and Arena game:
              <pre className="code" style={{ whiteSpace: 'pre-wrap', marginTop: 8 }}>{FAIR_PLAY_NOTICE}</pre>
              The notice is part of every test hash, so results recorded without it no longer count. Picture-making models (The Gallery) take no system prompt and don’t get it.
            </li>
            <li>
              <b>Cheating scores 0.</b> Every reply is checked before it is marked. Text aimed at the grader (notes to the judges, asking for a score, “ignore your instructions”, forged judge verdict lines), escaping the code sandbox, patching the test runner or reading the hidden tests scores 0 and is shown as a red “Fair-play violation” on the result and the leaderboard.
            </li>
            <li>
              <b>Possible outside help is flagged.</b> A pasted reply that says it searched the web or ran code, or carries a chat app’s search citations, keeps its score but goes to <Link to="/review">Blind Review</Link> for a person to check. Tool use inside a chat app can be discouraged and flagged, not proven.
            </li>
            <li>
              <b>Fresh, stateless conversation per case.</b> No memory carries over between cases, repeats or models. Multi-turn tests replay only their own earlier turns.
            </li>
            <li>
              <b>No tools, no internet.</b> Models answer from what they know; web search, code execution and file uploads are off — manual testers must switch them off in chat apps too.
            </li>
            <li>
              <b>Answer keys are never sent.</b> Expected answers and auditor notes live only in the scorer; they never appear in a prompt.
            </li>
            <li>
              <b>Hedged answers are wrong.</b> Listing several options, or “it could be A or B”, does not earn credit — the FINAL ANSWER line must commit to one answer.
            </li>
            <li>
              <b>Judges are blind and independent.</b> Judges see the response, never the model’s identity{s?.judgeExcludeSameVendor !== false ? ', and a judge is never from the same vendor as the model it grades' : ''}.
            </li>
            <li>
              <b>Disagreements go to humans.</b> When the judge panel disagrees, the case goes to <Link to="/review">Blind Review</Link> and the human rating becomes the final score.
            </li>
            <li>
              <b>Held-out private tests.</b> Some tests live only in a git-ignored <span className="mono">tests/private/</span> folder and are never published, so they can’t leak into training data. They are labelled “Held-out · never published”.
            </li>
            <li>
              <b>Canary strings for contamination checks.</b> Published tests carry a canary string. Run <span className="mono">node src/cli.ts probe-contamination --models a,b</span> to ask a model whether it can complete it — if it can, its training data likely includes the
              suite.
            </li>
            <li>
              <b>Manual (copy &amp; paste) models</b> follow the same rules: paste each prompt exactly as shown into a new chat, don’t add instructions, don’t regenerate, don’t edit the reply. Their speed figures are human time and are shown as not comparable.
            </li>
          </ul>
        </div>
      </section>

      <section className="card">
        <div className="card-head">
          <div className="t">
            <h2>
              <Icon.Clock style={{ width: 18, height: 18, display: 'inline', verticalAlign: '-3px', marginRight: 8 }} />
              Arena speed games (the chess clock)
            </h2>
            <div className="desc">Every Arena game is a speed game by default: Speed 4+0, like blitz chess.</div>
          </div>
        </div>
        <div className="card-body">
          <ul className="rule-list">
            <li>
              <b>One clock per player per game.</b> Each model has 4 minutes in total for all of its moves in a game (other presets: 1+0, 2+1, 3+2, 5+0, 10+0, or Classic with no clock). An increment, when there is one, is added after every completed move.
            </li>
            <li>
              <b>Only the model’s own time counts.</b> The clock runs from the moment a request is sent until the reply is complete. Waiting for a free slot at the provider and retrying after a network or rate-limit error never count. A rejected (illegal) move and its retry do count: the clock keeps running. Network latency counts as part of a model’s time, as it does in online chess.
            </li>
            <li>
              <b>Out of time.</b> Chess and Connect Four: the player loses on time (in chess it is a draw if the opponent has only a king, or a king and one bishop or knight, and so cannot checkmate). Poker: every remaining decision is played for it at once, checking when that is free and folding otherwise. Debate and Courtroom: its remaining speeches are missing and the judges are told it ran out of time.
            </li>
            <li>
              <b>The models know, and cannot run over by following the rules.</b> Every prompt opens with a clock section (time left for both players, a suggested time and the reply’s token limit), repeats it right before the move, and gets firmer as the clock runs down. Each reply’s output-token limit is sized from the player’s remaining time and its measured speed, so a model that uses its whole allowance every move still does not run out of time.
            </li>
            <li>
              <b>API models only.</b> A pasted reply cannot be timed fairly, so copy &amp; paste models can only play Classic tournaments. The Random Baseline answers instantly and its clock never runs.
            </li>
          </ul>
        </div>
      </section>

      <section className="card">
        <div className="card-head">
          <div className="t">
            <h2>
              <Icon.Dollar style={{ width: 18, height: 18, display: 'inline', verticalAlign: '-3px', marginRight: 8 }} />
              Budget &amp; cost estimates
            </h2>
            <div className="desc">Know the bill before you press start.</div>
          </div>
        </div>
        <div className="card-body">
          <ul className="rule-list">
            <li>
              <b>Estimates learn from real runs.</b> The estimate uses the average token usage measured in previous runs of the <em>same test version</em> — per model when available, otherwise across models — and falls back to each test’s declared estimate.
              Tests marked “measured ✓” in the <Link to="/costs">Cost Planner</Link> are based on real data.
            </li>
            <li>
              <b>A range, not a guess.</b> You get a central estimate and a conservative upper bound; the gap is wider for tests that have never been run.
            </li>
            <li>
              <b>Hard spending cap.</b> Set a cap in USD (contestant + judge cost). When it is reached the run stops starting new cases, ends as <em>cancelled</em> with “budget cap reached”, and can be resumed with a higher cap to finish only the missing
              jobs.
            </li>
            <li>
              <b>Manual models cost $0</b> unless you enter the real cost shown by the chat app; tokens are estimated from text length when not provided.
            </li>
            <li>
              <b>Speed games cost less at most.</b> A model cannot write more than its clock allows, so an Arena estimate caps each player’s output per game at its clock time × 250 tokens per second. A call stopped when the clock runs out is still counted, at its worst case.
            </li>
            <li>
              <b>Retries are bounded.</b> Transient provider errors are retried up to {s?.maxRetries ?? 3} times with back-off; every retry is billed and recorded.
            </li>
          </ul>
        </div>
      </section>

      <div className="grid cols-2" style={{ alignItems: 'start' }}>
        <section className="card">
          <div className="card-head">
            <div className="t">
              <h2>Categories</h2>
              <div className="desc">Live from the server configuration.</div>
            </div>
          </div>
          <div className="card-body">
            {loading && !meta ? (
              <Skeleton h={200} />
            ) : (
              <ul className="cat-list">
                {(meta?.categories ?? []).map((c) => (
                  <li key={c.id} style={{ ['--cat' as string]: c.color }}>
                    <div className="row" style={{ gap: 10 }}>
                      <span className="cat-dot" style={{ background: c.color, width: 12, height: 12 }} />
                      <strong>{c.name}</strong>
                      <span className="spacer" />
                      <span className="badge outline tnum">weight {c.weight}</span>
                    </div>
                    <p>{c.description}</p>
                  </li>
                ))}
              </ul>
            )}
          </div>
        </section>
        <section className="card">
          <div className="card-head">
            <div className="t">
              <h2>Simulations</h2>
              <div className="desc">Multi-step programs and how each is scored.</div>
            </div>
          </div>
          <div className="card-body">
            {loading && !meta ? (
              <Skeleton h={200} />
            ) : (meta?.programs ?? []).length === 0 ? (
              <p className="muted">No programs registered.</p>
            ) : (
              <ul className="cat-list">
                {(meta?.programs ?? []).map((p) => (
                  <li key={p.id}>
                    <div className="row" style={{ gap: 10 }}>
                      <strong>{p.name}</strong>
                      <span className="mono muted" style={{ fontSize: '0.76rem' }}>
                        {p.id}
                      </span>
                    </div>
                    <p>{p.description}</p>
                    <p className="scoring">
                      <b>Scoring:</b> {p.scoring}
                    </p>
                  </li>
                ))}
              </ul>
            )}
          </div>
        </section>
      </div>
    </div>
  );
}
