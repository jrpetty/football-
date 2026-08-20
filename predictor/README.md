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
- **Predicts the line-ups.** Both starting elevens on a pitch, from recent minutes and current
  availability. Confirmed team sheets appear about an hour before kickoff and are deliberately not
  waited for — this publishes days ahead.
- **Re-checks itself weekly.** A scheduled job ingests results, scores the forecasts recorded
  *before* kickoff, refits, and republishes — so the accuracy record is real rather than
  retrospective.

## How well does it work?

Walk-forward over the 2025/26 season (the model refits as the season progresses and never sees a
result before predicting it), n = 380:

| | RPS ↓ | Log-loss ↓ | Brier ↓ | Outcomes called ↑ |
|---|---|---|---|---|
| Fixed 44/25/31 baseline | 0.2274 | 1.0808 | 0.6542 | 42.6% |
| **This model** | **0.2095** | **1.0284** | **0.6179** | **48.2%** |

Calibration error is 0.016 — of everything it called at 30%, close to 30% happened. For scale,
bookmakers with vastly more data land around RPS 0.19–0.20, so there is real headroom left.

**Ranked Probability Score** is the primary measure because it respects the ordering of the three
outcomes: predicting a home win when the away side wins is penalised more than predicting a draw.

Reproduce it yourself:

```bash
npm run backtest              # 2025-26 by default
node scripts/backtest.ts --season 2024-25 --sweep
```

---

## How it works

**Ratings.** A Dixon-Coles model gives each club an attack and a defence rating, with a
league-wide home advantage and a correction for the fact that real football produces more 0-0s
and 1-1s than independent scoring rates predict. Three departures from the textbook version:

1. **Time decay** — a result from 2019 says little about 2026. Worth about 0.007 RPS.
2. **Ridge priors** — with ~38 matches a season, unregularised fitting overreacts to early noise.
3. **Fitted twice** — once on goals across the full history, once on expected goals over the
   seasons that have it, then blended. xG measures chance quality rather than whether the ball
   went in, so it is the steadier signal; goals supply the longer history.

**Players.** Each absent player's share of squad value is removed and replaced at that club's own
replacement level, so a deep bench is punished less than a thin one. The size of the effect is
measured, not assumed: across 2023-24 to 2025-26, sides with all three of their top attackers
available scored 1.520 goals per match; sides missing at least two of them scored 1.146. The xG
deltas match the goal deltas to within 0.005, so it is a real change in chance creation rather
than finishing luck.

**Uncertainty.** Scorelines come from a negative binomial rather than a Poisson, with dispersion
set from how confident the ratings are (`r = 1/σ²`). A newly promoted club with a wide rating band
gets a genuinely fatter tail — which is where its upset probability comes from, rather than from a
thumb on the scale.

**Promoted clubs.** Priors measured from 33 promoted seasons: 0.71× the league's attacking output,
1.23× conceded. The spread matters more than the average — 19 of those 33 finished under a point
per game, but 5 cleared 1.30 — so promoted sides carry inflated variance rather than just a lower
mean.

### Two things the data changed my mind about

**Championship form barely predicts Premier League output.** Goal difference correlates with
attacking output at r = 0.28. Championship *points per game* correlates **negatively** (r = −0.21),
and defensive record not at all (r = 0.05). Burnley won their division by +1.16 goals a game and
took 0.63 points a game in the top flight; Sunderland came up at +0.31 and took 1.42. An earlier
version of this model rewarded Championship points and was pushing promoted sides the wrong way.
The coefficients are now only what the data supports.

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

`.github/workflows/predictor-weekly.yml` runs twice a week:

- **Wednesday** — the main run. The gameweek is over (including Monday night fixtures), so this
  ingests results, scores the forecasts recorded before kickoff, refreshes injuries and
  suspensions, refits and publishes the next gameweek.
- **Friday** — a lighter top-up, since injury news moves between midweek and a Saturday kickoff.
  Delete the second `cron` line if one run a week is enough; nothing depends on it.

Forecasts are appended to an immutable ledger with a timestamp and never rewritten, which is what
makes the report card meaningful. The job runs the test suite and an artifact verifier before it
will commit anything.

---

## What it can't do

- **Line-ups are predicted, never confirmed.** Team sheets are published about an hour before
  kickoff; this forecasts days ahead, so a late change is not reflected. For a promoted club with
  no top-flight record in the data, the eleven is inferred from squad valuation and is a rough
  guess — labelled as such on the page. Positions follow the Fantasy Premier League
  classification, which does not always match where a player really lines up.
- **Red-card ban lengths are inferred.** The feed records that a red card was shown but not the
  offence, so the one-match minimum is assumed and flagged.
- **The availability adjustment isn't backtested.** Doing that honestly would need archived injury
  reports — knowing who was fit *before* each match. Reconstructing line-ups from minutes played
  would leak the result. Its size is measured from historical squad data instead, and that
  distinction is stated on the report card rather than glossed over.
- **Football is high variance.** Roughly half of matches called correctly is a good model, not a
  broken one.

---

## Optional written previews

Any match page can turn its factors into a few paragraphs of prose via Claude. It is given only the
model's own numbers and told not to invent others, so it cannot contradict the forecast. It needs
your own Anthropic API key, kept in your browser and sent directly to Anthropic — this site has no
server. Everything else works without it.
