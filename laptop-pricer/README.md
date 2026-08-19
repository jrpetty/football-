# Laptop pricing and stock engine

Phase 1 + the Phase 2 core of the design doc. Prices a used laptop from a
free-text description by fusing every pricing file you own into one comparable
market view, and tracks each physical machine as a serialised unit that is
revalued against that market while you hold it.

```
L0 ingest → L1 parse → L2 resolve → L3 normalise → L4 estimate → L5 price → L6 explain
                                          ↑                                      ↓
                    U5 outcome ← U4 listing ← U3 stock ← U2 refurb ← U1 intake
```

The two halves join on exactly one field: `units.config_id`. The pricer never
needs to know units exist; the stock book never needs to know how a valuation
was derived.

## Install and run

```bash
pip install duckdb pyyaml openpyxl
cd laptop-pricer

python3 -m pricer.cli ingest --reset          # load data/incoming via source profiles
python3 -m pricer.cli inspect yourfile.xlsx    # what does it make of your data?
python3 -m pricer.cli quote "Dell Latitude 5420 i5-1145G7 16GB 512GB"
python3 -m pricer.cli parts "Dell Latitude 5420 i5-1145G7 16GB 512GB" --grade C
python3 -m pricer.cli review                  # rows a human needs to decide

python3 scripts/make_demo_data.py             # 6 months of synthetic history
python3 scripts/seed_demo.py                  # + a 71-unit stock book
python3 -m pricer.cli stock                   # overview and ageing
python3 -m pricer.cli actions                 # the morning action queue

python3 -m unittest discover -s tests         # 93 tests
```

Dates are pinned to `2026-08-19` in the demo data. Pass `--as-of 2026-08-19`
to any command to reproduce the documented figures.

## Plugging in your own pricing files

Point it at a file you already have and it tells you what it found:

```bash
python3 -m pricer.cli inspect "Refurb Sales Export (Aug).csv"
```

```
  format csv   9 data rows   header on row 5
  ignored: 4 banner row(s) above the header, 2 blank, 1 total/summary

  Columns
    in your file            read as         conf   why
    Inv Date                sold_at         0.95   header contains 'date'; 9/9 parse as dates
    Item Description        raw_title       1.04   header contains 'description'; 8/9 contain a known brand
    Cond.                   grade_raw       1.01   header is exactly 'cond'; 7 distinct short values
    Sold For                price_gross     1.08   header is exactly 'sold for'; 9/9 numeric, median 312
    VAT Rate                — not used

  Recognition — the test that matters
    [███████████████████████·······]  7/9 rows resolve to a machine (78%)
         1  unknown model
         1  unknown CPU
```

No reformatting required. It finds the header under report banners, drops blank
and total rows, reads `£1,234.56` and `1.234,56` and `(99.00)`, handles six date
conventions plus Excel serials, and takes `.csv`, `.tsv` or `.xlsx`.

`--write-profile <id>` saves a source profile with your columns already mapped
and your grade words already translated (`Grade B` → B, `Mint` → A_PLUS,
`Spares or Repair` → SALVAGE). Only what it genuinely cannot infer is left for
you: **does the price include VAT, is it a sale or an ask, and which channel.**

The recognition percentage is the number to watch. Anything unresolved is named
with its reason, and fixing it once — a model added to `catalog/models.csv`, a
CPU alias added to `catalog/cpus.csv` — fixes it for every future file.

### What the engine knows about

```bash
python3 -m pricer.cli capabilities
```

Prints what will be accepted, read from the live code and catalogue rather than
from a hand-maintained list, so it cannot go stale. Currently **115 models and
96 CPUs across 367 spellings** — Dell Latitude/Precision/XPS/Inspiron, HP
EliteBook/ProBook/ZBook/Spectre/Pavilion, Lenovo ThinkPad/ThinkBook/Legion/
IdeaPad, Apple MacBook Air and Pro, Microsoft Surface, Asus, Acer.

The catalogue is the real limit on what prices, not the file format. Anything
outside it goes to the review queue named and explained; add a row to
`catalog/models.csv` (or a family to `scripts/build_catalog.py`) and every
future file containing that machine resolves.

### What works, and what does not

Tested against deliberately awkward files (`tests/fixtures/`, locked in by
`TestAwkwardInputs`):

| Input | Result |
|---|---|
| `.csv`, `.tsv`, `.xlsx`, semicolon or tab delimited | works |
| Report banners, blank rows, trailing TOTAL lines | ignored automatically |
| `£1,234.56`, `1.234,56`, `(99.00)`, `$450`, `1 234` | all parsed |
| Six date conventions, plus Excel serial numbers | all parsed |
| Non-English headers (`Preis`, `Datum`, `Bezeichnung`) | works — matched on value shape, not wording |
| Workbook where the data is on a later sheet | works — picks the sheet with the most data |
| Brand / model / CPU / RAM in **separate** columns, no description | works — joined into one description |
| Monitors, phones, docks mixed in with laptops | rejected to review, never priced as laptops |
| **No date column** | loads, but **warns loudly** — undated rows can never be used |
| A model or CPU not in the catalogue | rejected to review, named with its reason |

Genuinely not supported: PDFs and scanned invoices, images of spreadsheets,
live links to Google Sheets or a database (nothing is fetched — it is an
offline tool), and machines other than laptops. Multi-currency files work but
FX uses fixed rates rather than the rate on the transaction date, so a
mixed-currency history will drift.

Three things no file can reveal, which you must state in the profile:
**whether the price includes VAT, whether a row is a sale or an ask, and which
channel it came from.** Everything else is inferred and shown to you for
confirmation.

Nothing is ever silently dropped or silently guessed. Every row either loads,
is excluded by a rule you wrote, or lands in the review queue with a reason.

### Writing a profile by hand

Drop a file in `data/incoming/` and add a profile in `sources/`:

```yaml
id: my_export
channel: ebay_bin            # from config/channels.yml
currency: GBP
observation_type: sold       # sold | ask | offer | appraisal
trust: 0.90
file_glob: "my_export_*.csv"

price:
  vat_treatment: inclusive   # inclusive | exclusive | margin_scheme
  fees: {rate: 0.128, fixed: 0.30}

columns:                     # your column names on the right
  raw_title: "Item Title"        # or a list, to join several columns:
  #raw_title: [Brand, Model, Processor, RAM, Storage]
  price_gross: "Sold For"
  sold_at: "Date Sold"
  grade_raw: "Condition"

grade_map:                   # your grade words -> the ladder in config/grades.yml
  "Very Good": A
  "Good": B

exclude_if:
  - title_matches: "(?i)\\b(job ?lot|spares|faulty)\\b"
  - price_gross_below: 25
```

Three things the engine cannot infer and you must state: **does the price
include VAT, is it a sale or an ask, and which channel is it.** Everything else
is guessed from the file and confirmed by you.

## The value of each part

```bash
python3 -m pricer.cli parts "Dell Latitude 5420 i5-1145G7 16GB 512GB 14in FHD" \
    --grade C --battery 74
```

```
  component                     value   basis                            source
  Display panel                 41.00   14" fhd                          median of 12 sold Aug 2026
  Motherboard                   44.72   Intel Core i5-1145G7 — 10400     seed formula
  Battery                       10.50   74% health x 0.6                 we buy these in at 17.50
  RAM modules                   18.00   16GB at £9.00/8GB                seed formula
  SSD                           12.00   512GB at £6.00/256GB             seed formula
× Chassis and palmrest           0.00   grade C                          seed formula
  Keyboard                       3.60   grade C x 0.3                    seed formula
  Charger                        9.00   included                         seed formula
  gross recovery               138.82
  parts availability             ×0.9   adjusted gross 124.94
  teardown labour              -10.00
  WEEE levy                    -13.00
  NET RECOVERY                 101.94

  whole unit, working          173.80   ex-VAT at grade C
  → SELL WHOLE: worth £71.86 more assembled
```

Put your own realised parts prices in **`catalog/parts_prices.csv`** and they
override the formula entirely — most specific key wins:

```csv
key,value,note,updated
panel:14:fhd,41.00,"median of 12 sold Aug 2026",2026-08-19
battery:dell-latitude-5420,17.50,"we buy these in at 17.50",2026-08-19
board:intel-i5-1145g7,52.00,"broker pays this",2026-08-19
```

Three behaviours are worth knowing:

- **Soldered memory has no separate value.** On a MacBook the RAM and SSD lines
  come out at zero, because their value is already in the board. Charging for
  them twice would overstate every Apple teardown.
- **A failed check zeroes the component it breaks**, not the whole machine —
  `--defects screen_cracked` kills the panel line and leaves the rest.
- **A hard stop suppresses the comparison.** A BIOS-locked machine is parts
  only, so no whole-unit price is offered against it.

The same figure is the salvage floor under every working-unit valuation, so the
breakdown you read and the floor the pricer applies can never drift apart —
there is a test that asserts exactly that.

## Your data sets the parameters, not just the prices

Every number that moves a price ships as a **seed prior** — my starting guess.
`pricer calibrate` replaces each one with a value estimated from your own
observations, shrunk toward the seed in proportion to the evidence behind it,
and records where each value came from.

```bash
python3 -m pricer.cli calibrate              # fit and show, change nothing
python3 -m pricer.cli calibrate --write      # adopt the fitted values
python3 -m pricer.cli params                 # what is fitted, what is still a guess
```

Fitted from your data: depreciation λ per build class, grade multipliers,
channel multipliers, and RAM/storage deltas. Method is robust alternating-median
backfitting on log price:

```
ln(price_net) = mu[config] + lambda[build_class]*days_ago
                + gamma[grade] + delta[channel] + residual
```

Medians rather than means, because sold data contains typos and bundle lots.
A fully saturated per-configuration baseline absorbs everything about the
machine itself, so the fitted parameters are identified from variation *within*
a configuration. Spec deltas come from a second pass over within-model,
same-CPU configuration pairs.

Three things it deliberately refuses to do:

- **Invent parameters it cannot see.** A channel absent from your files keeps
  its seed and says so.
- **Lurch on thin evidence.** Shrinkage means five observations nudge a
  parameter; three hundred move it.
- **Attribute an effect it cannot separate.** If RAM and storage only ever move
  together in your data, the two elasticities are not identified and it says so
  rather than splitting them arbitrarily. Likewise, a channel served by a single
  source cannot have its channel effect separated from that source's own bias —
  the diagnostics name every such case.

Set `parameters.allow_seed_fallback: false` in `config/business.yml` and the
engine **refuses to quote** using any adjustment it has not fitted from your
data. Start with it on, turn it off once you have history.

`config/fitted.yml` is written by calibration and gitignored — it is derived
from your data and belongs to your deployment, not to the repository.

### What stays mine, and why

The `catalog/` CSVs are facts about hardware, not judgements about price: a
MacBook Air M1 does have soldered RAM, an i5-1145G7 does score what it scores.
Those are reference data. `config/business.yml` — margins, refurb budgets — is
your commercial policy and was always yours. Everything in between is fitted.

## What is configurable without touching code

| File | Controls |
|---|---|
| `config/business.yml` | margins by build class, refurb budgets, VAT, rounding |
| `config/grades.yml` | the grade ladder and its multipliers |
| `config/channels.yml` | channel multipliers, expressed **ex-VAT net realised** |
| `config/depreciation.yml` | λ per segment, weighting half-life |
| `config/spec_deltas.yml` | hedonic RAM/storage/panel deltas, split by upgradeability |
| `config/grading_checklist.yml` | intake checks → grade, and the hard stops |
| `config/stock_policy.yml` | ageing buckets, margin floor, reprice/part-out triggers |
| `config/guardrails.yml` | outlier threshold, shrinkage k, sanity bounds |
| `config/parts_recovery.yml` | component recovery values for the salvage floor |
| `config/fitted.yml` | **written by calibration** — never hand-edit |
| `catalog/models.csv`, `cpus.csv` | generated by `scripts/build_catalog.py` — edit the script |
| `catalog/parts_prices.csv` | **your own realised parts prices** — override the formulas |

## Reference basis

Every observation is pushed to one basis before it is used: **net realised,
grade B, own retail B2C, ex-VAT, GBP, today's money.** Get this wrong and
nothing downstream can be right — in particular, the channel multipliers are
calibrated on an ex-VAT net-realised basis, so they are smaller than the
VAT-inclusive gaps people quote from memory.

## What is and is not built

Built: parameter calibration from your own data with provenance tracking and a
strict mode; source profiles and ingestion with dedup and exclusion rules; the
rule-based parser and gazetteers; identity resolution with a review queue;
the full normalisation chain; Tier 1 and Tier 2 estimation with shrinkage,
MAD outlier rejection and predictive intervals; confidence scoring; the buy/
list/parts commercial layer with holding cost; purchase lots with value-weighted
cost allocation; the grading checklist with hard stops; unit lifecycle events;
nightly revaluation; ageing report and action queue; a CLI with full
explanation output.

Not yet built (later phases of the design doc): the Tier 3 family regression
and Tier 4 global ML model; per-source bias separation where a channel has
several sources; censored non-sale
feedback; the walk-forward backtest harness; parts register and refurb job
costing; multi-channel listing sync; the HTTP API.

`estimate()` returns `value=None` rather than guessing when it has no
comparable evidence. That is deliberate — Tier 4 is what fills that gap, and
it does not exist yet.

## Marketplace connectors

```bash
python3 -m pricer.cli connectors                       # what each one gives you
python3 -m pricer.cli sync ebay_browse --query "dell latitude 5420"
python3 -m pricer.cli ingest
```

A connector's only job is to **fetch and write a file**. Everything downstream
reads that file offline:

```
network  │  fetch  →  data/raw/<source>/<date>.json      (raw, never edited)
         │            data/incoming/<source>_<date>.csv
offline  │  ingest → parse → resolve → normalise → estimate
```

Pricing never blocks on a rate limit or an outage, every valuation stays
reproducible because the response that produced it is on disk, and the whole
pipeline below the connector keeps its existing tests unchanged.

| Connector | Gives | Trust | Reality |
|---|---|---|---|
| `ebay_browse` | ask | 0.40 | Active listings. Open access, 5,000 calls/day. **Asking prices, not sales.** |
| `ebay_sold` | sold | 0.90 | Marketplace Insights — **restricted, closed to new applicants**. Refuses with an alternative. |
| `amazon_competitive` | ask | 0.45 | Offers, plus a 60-day average selling price that *is* transaction-derived. |
| `amazon_orders` | sold | 1.00 | **Your own sales.** The most valuable source here. |
| `backmarket_orders` | sold | 1.00 | **Your own sales.** Back Market has no market-data API. |

The pattern worth understanding: **APIs mostly sell you asking prices.**
Realised sold prices for machines you did not sell are gated almost everywhere.
That is why the estimator has always distinguished `sold` from `ask`, applied a
haircut and weighted them differently — an ask-fed pricer drifts high.

Credentials live in `config/secrets.yml` (gitignored) or environment variables
(`PRICER_EBAY__CLIENT_ID`). See `config/secrets.yml.example`. Rate limits are
tracked in a token bucket persisted to disk, so a daily cap survives restarts.

## Offline by design

The pricing engine makes no network calls. Only `pricer sync` touches the
network, and it writes files that everything else reads offline — so the test
suite still passes with sockets blocked, and pricing works with the network
down. Nothing about your stock or margins is ever sent anywhere; connectors
only read public marketplace data and your own orders.

## A note on the numbers

`tests/test_pipeline.py::TestEndToEnd` is the strongest check in the suite.
`scripts/make_demo_data.py` generates prices by running the normalisation chain
*backwards* — from a known reference value out through depreciation, grade,
channel, fees and VAT, plus noise. The test then asserts the pipeline recovers
the original values within 5%. It currently recovers them within 1.7%.

`TestCalibration` does the same for the parameters: the generator uses known
values for λ, the grade ladder and the channel ladder, and calibration has to
recover them from the resulting prices alone. It gets grade and channel
multipliers within 5%, and enterprise λ within 15%.
