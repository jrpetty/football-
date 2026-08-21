# ⚽ Prem Oracle — a Premier League prediction experiment

A statistical model that forecasts Premier League matches, explains its reasoning in numbers you
can check, names the plausible upsets, and **scores itself every week against what actually
happened**.

Not a tipping service. There is no betting advice anywhere in it, and the most prominent page is
the one showing how often it has been wrong.

---

## What it does

- **Forecasts every match** in the coming gameweeks: win/draw/win, expected goals, the full
  scoreline distribution, both-teams-to-score, over/under, clean sheets.
- **Explains itself.** Every prediction ships ranked, quantified reasons — *"Arsenal are without
  J.Timber and Saliba, cutting their attacking output by about 10% (0.20 goals)"* — generated
  from the same arithmetic that produced the probabilities, not written alongside them.
- **Looks for upsets properly.** Instead of just quoting a low number on the underdog, it names
  the mechanism: the favourite's absences, a finishing run due to regress, a short turnaround.
- **Tracks players.** Per-player goal, assist and card probabilities; injury and suspension
  status; and a yellow-card tracker that knows the 5/10/15 booking thresholds.
- **Predicts the line-ups.** Both starting elevens on a pitch, in the shape each club has actually
  been starting — read from its recent team sheets, weighted toward recent matches so a change of
  system shows through. Confirmed sheets appear about an hour before kickoff and are deliberately
  not waited for; this publishes days ahead.
- **Lets you edit the squad and watch the forecast move.** Take a player out and the whole fixture
  re-runs in your browser — probabilities, expected goals and the eleven itself. It runs the same
  model core that produced the published numbers, so with nothing removed it reproduces them
  exactly.
- **Reads a confirmed team sheet from a photograph.** Confirmed elevens are published about an hour
  before kickoff, long after this forecast is written — so photograph one (the television works)
  and the match re-runs against the side actually playing. Claude reads the names; matching them to
  players is done afterwards by tested code that reports whatever it could not resolve.
- **Re-checks itself every morning.** A scheduled job ingests results, scores the forecasts
  recorded *before* kickoff, refits, and republishes — so the accuracy record is real rather than
  retrospective. It also watches for squad movement: transfers are detected by diffing each run
  against the last, because the feed reports where a player *is*, never that he moved.

## How well does it work?

Walk-forward over five seasons, 2021-22 to 2025-26 (the model refits as each season progresses and
never sees a result before predicting it), n = 1,900:

| | RPS ↓ | Log-loss ↓ | Brier ↓ | Outcomes called ↑ |
|---|---|---|---|---|
| Fixed 44/25/31 baseline | 0.2320 | 1.0679 | 0.6461 | 44.2% |
| **This model** | **0.2011** | **0.9794** | **0.5824** | **53.8%** |

Calibration error is 0.016 — of everything it called at 30%, close to 30% happened. For scale,
bookmakers with vastly more data land around RPS 0.19–0.20, so there is real headroom left.

Seasons differ a lot, and the average hides that. The hardest of the five, 2025/26, scored RPS
0.2108 against a 0.2274 baseline and called 48.9% — so treat the pooled figure as an average
rather than a promise.

**Ranked Probability Score** is the primary measure because it respects the ordering of the three
outcomes: predicting a home win when the away side wins is penalised more than predicting a draw.

The headline is pooled across five seasons rather than one on purpose. A single season is 380
matches, where the standard error on RPS is about 0.008 — wide enough that a genuine improvement
and a genuine regression look identical. That is not a hypothetical: per-team home advantage
looked *harmful* on 2025/26 alone and is clearly worth having across 1,900 matches.

Reproduce it yourself:

```bash
npm run backtest              # 2025-26 by default, with the full ablation table
node scripts/backtest.ts --season 2024-25 --sweep
```

---

## How it works

**Ratings.** A Dixon-Coles model gives each club an attack and a defence rating, with a
league-wide home advantage and a correction for the fact that real football produces more 0-0s
and 1-1s than independent scoring rates predict. Four departures from the textbook version:

1. **Time decay** — a result from 2019 says little about 2026. Worth about 0.007 RPS.
2. **Ridge priors** — with ~38 matches a season, unregularised fitting overreacts to early noise.
   The league's overall scoring rate is held as its own unpenalised parameter, so the ridge shrinks
   how far clubs sit from the league mean without also shrinking the league mean itself.
3. **Fitted twice** — once on goals across the full history, once on expected goals over the
   seasons that have it, then blended. xG measures chance quality rather than whether the ball
   went in, so it is the steadier signal; goals supply the longer history.
4. **Home advantage per club** — see below.

**Home and away.** The league-wide home advantage is currently worth about 19% on the home side's
scoring rate, but clubs are not equal at it. Each gets two further parameters fitted from its own
home matches: how much more it scores there, and how much it suppresses visitors there. Because
they sit inside the likelihood, they are controlled for *who* a club happened to host — a side that
faced the bottom six in its first eight home games gets no credit for it. Both are shrunk hard
toward the league figure, because nineteen home matches a season is a small sample and an unshrunk
venue split is mostly noise; the shrinkage is tuned by backtest and holds the largest fitted effect
to about 9%.

The `Ratings → Home and away` page shows every club's record at each venue beside the effect fitted
from it, and says plainly where the two disagree.

**Players.** Each absent player's share of squad value is removed and replaced at that club's own
replacement level, so a deep bench is punished less than a thin one. The size of the effect is
measured, not assumed: across 2023-24 to 2025-26, sides with all three of their top attackers
available scored 1.520 goals per match; sides missing at least two of them scored 1.146. The xG
deltas match the goal deltas to within 0.005, so it is a real change in chance creation rather
than finishing luck.

**Shape.** Every gameweek row records the position a player was listed in and whether he started,
so the eleven a club fielded reconstructs exactly. The line-up picker targets the shape that club
actually starts most often rather than a generic rule — an earlier version imposed "at least three
at the back, at most five in midfield" and produced Liverpool as 3-6-1, a formation nobody has
ever set up in. When a club is short in one line (Liverpool began this season with three fit
defenders who had Premier League minutes and five who did not) it calls on squad depth rather than
distorting the shape.

**Confirmed line-ups.** A team sheet is a rating adjustment, not an availability one: it says who
is on the pitch, measured against who was *expected* to be. That distinction is the whole trick —
an earlier version scaled everyone's expected minutes to match the sheet, but the availability
calculation derives its baseline from the squad it is handed, so numerator and denominator moved
together and the forecast did not budge. Comparing the named eleven against expectation, Manchester
City naming their strongest side moves them from 60.3% to 63.8%, and a heavily rotated one to 58.7%.
Substitutes are not written off — they come on and score — so the bench keeps a small share.

**Uncertainty.** Scorelines come from a negative binomial rather than a Poisson, with dispersion
set from how confident the ratings are (`r = 1/σ²`). A newly promoted club with a wide rating band
gets a genuinely fatter tail — which is where its upset probability comes from, rather than from a
thumb on the scale.

**Promoted clubs.** Priors measured from 33 promoted seasons: 0.71× the league's attacking output,
1.23× conceded. The spread matters more than the average — 19 of those 33 finished under a point
per game, but 5 cleared 1.30 — so promoted sides carry inflated variance rather than just a lower
mean.

### Three things the data changed my mind about

**Championship form barely predicts Premier League output.** Goal difference correlates with
attacking output at r = 0.28. Championship *points per game* correlates **negatively** (r = −0.21),
and defensive record not at all (r = 0.05). Burnley won their division by +1.16 goals a game and
took 0.63 points a game in the top flight; Sunderland came up at +0.31 and took 1.42. An earlier
version of this model rewarded Championship points and was pushing promoted sides the wrong way.
The coefficients are now only what the data supports.

**The player-availability adjustment does not demonstrably help.** This was the one part of the
model running on a measured effect size rather than a walk-forward test, because testing it looked
like it needed archived injury reports the feed does not carry. There is an honest way round that:
a player who missed his club's previous two matches was, in most cases, already known to be
unavailable — information a forecaster genuinely had at kickoff. Tested that way, the adjustment
made forecasts *worse* at every weight and every severity of absence, costing 0.0006 RPS at 0.3
and 0.0031 at 1.1. Recency-weighted ratings seem to absorb most of it already: a side playing
without its best players produces worse results, and the rating has read that.

The weight was cut from 0.7 to 0.3 on this evidence rather than removed, for two reasons. The
test's absence signal is much noisier than the live one — the proxy flags 7.0 players per
team-match where the real injury feed flags 2.9 per club, so most of what it detects is rotation,
which is not a downgrade. And at 0.3 the measured cost sits well inside the noise, while the
adjustment is what makes the player-level reasoning and the squad editor reflect the model rather
than decorate it. Availability flags are now recorded every week (`status-history.json`) so the
clean version of this test becomes possible in a few months.

Reproduce it: `node scripts/backtest-availability.ts`

**Hyperparameters barely matter here.** A 16-point sweep across time-decay and xG-blend settings
moved RPS by under 0.0005 — well inside the noise for 380 matches. They are left at sensible
defaults rather than tuned to a number that would not generalise.

---

## Running it

```bash
npm install
npm run update     # fetch data and rebuild every artifact
npm run dev        # http://localhost:5174
```

| Command | What it does |
|---|---|
| `npm run dev` | Development server |
| `npm run build` | Production build into `dist/` |
| `npm test` | Model maths, card rules, parsers, isomorphism guards (64 tests) |
| `npm run ingest` | Fetch and normalise source data into `.cache/` |
| `npm run build:data` | Rebuild predictions from the cached corpus |
| `npm run backtest` | Walk-forward evaluation |
| `node scripts/backtest-availability.ts` | Tests whether the availability adjustment earns its place |
| `npm run verify` | Validate artifacts before publishing |

Node 22+ is required — the pipeline scripts are TypeScript executed directly, so there is no build
step and no extra dependency for them.

### Data

| Source | Supplies |
|---|---|
| [openfootball](https://github.com/openfootball/football.json) | Match results, 2014-15 onward, and Championship results for promoted-club priors |
| [Fantasy Premier League](https://fantasy.premierleague.com/) (live, with a [mirror](https://github.com/vaastav/Fantasy-Premier-League) fallback) | Players, minutes, goals, assists, cards, expected goals, injuries and suspensions |

The two are split by what each can supply cleanly. openfootball names clubs directly, so the long
results history needs no ID mapping. The FPL feed is only used from 2022-23, which is when it
gained the team, position and expected-goals columns — before that its per-gameweek rows cannot
even be attributed to a club.

The weekly job prefers the live API and falls back to the mirror, validating the shape of what
comes back before it will use it. A quiet fallback beats a confidently wrong gameweek.

---

## The weekly cycle

`.github/workflows/predictor-daily.yml` runs every morning at 06:00 UTC. The pipeline is
idempotent — it ingests whatever is current, scores any fixture finished since the last run,
refits and republishes — so one job covers every case:

- **Wednesday** the gameweek is complete (Monday night included), so that run is the one which
  scores the round's forecasts and publishes the next.
- **Every day** injuries, suspensions and transfers move daily, and during a window a squad can
  change hours before a deadline. A weekly cadence would have shown a player at the wrong club for
  six days.

Nothing is committed unless something actually changed, so a quiet day costs a minute of CI and
produces no commit. The site header shows when the data was last rebuilt, and turns into a warning
past three days — stale numbers that look current are worse than an obvious gap.

Forecasts are appended to an immutable ledger with a timestamp and never rewritten, which is what
makes the report card meaningful. The job runs the test suite and an artifact verifier before it
will commit anything.

---

## What it can't do

- **Line-ups are predicted, never confirmed.** Team sheets are published about an hour before
  kickoff; this forecasts days ahead, so a late change is not reflected. For a promoted club with
  no top-flight record in the data, the eleven is inferred from squad valuation and is a rough
  guess — labelled as such on the page. Positions follow the Fantasy Premier League
  classification, which does not always match where a player really lines up, so a real 4-3-3
  often reads as 4-5-1 here.
- **Red-card ban lengths are inferred.** The feed records that a red card was shown but not the
  offence, so the one-match minimum is assumed and flagged.
- **The availability adjustment isn't backtested.** Doing that honestly would need archived injury
  reports — knowing who was fit *before* each match. Reconstructing line-ups from minutes played
  would leak the result. Its size is measured from historical squad data instead, and that
  distinction is stated on the report card rather than glossed over.
- **Transfers are inferred, not announced.** The feed states where a player is, so a move is only
  visible as a difference between two runs. A player appearing for the first time is not
  necessarily a signing — the source periodically expands its roster — so an arrival carrying an
  old join date is labelled "newly listed" rather than counted as a transfer. A diff of more than
  60 changes at once is treated as a feed problem and discarded rather than written into the
  ledger.
- **A photographed team sheet is read, not verified.** Claude is asked to report only names it can
  see, never to infer a likely eleven, but a blurred or cropped image can still yield a partial
  reading — which is why every matched name is shown against the player it resolved to and anything
  unresolved is listed rather than dropped. Nothing reaches the forecast until you apply it.
- **Football is high variance.** Roughly half of matches called correctly is a good model, not a
  broken one.

---

## Optional written previews

Any match page can turn its factors into a few paragraphs of prose via Claude. It is given only the
model's own numbers and told not to invent others, so it cannot contradict the forecast. It needs
your own Anthropic API key, kept in your browser and sent directly to Anthropic — this site has no
server. Everything else works without it.
