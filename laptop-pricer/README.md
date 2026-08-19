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
pip install duckdb pyyaml
cd laptop-pricer

python3 -m pricer.cli ingest --reset          # load data/incoming via source profiles
python3 -m pricer.cli quote "Dell Latitude 5420 i5-1145G7 16GB 512GB"
python3 -m pricer.cli review                  # rows a human needs to decide

python3 scripts/make_demo_data.py             # 6 months of synthetic history
python3 scripts/seed_demo.py                  # + a 71-unit stock book
python3 -m pricer.cli stock                   # overview and ageing
python3 -m pricer.cli actions                 # the morning action queue

python3 -m unittest discover -s tests         # 41 tests
```

Dates are pinned to `2026-08-19` in the demo data. Pass `--as-of 2026-08-19`
to any command to reproduce the documented figures.

## Plugging in your own pricing files

This is the part built for your data. Drop a CSV in `data/incoming/` and add a
profile in `sources/`:

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
  raw_title: "Item Title"
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
| `catalog/*.csv` | models with build attributes, CPUs and GPUs with aliases |

## Reference basis

Every observation is pushed to one basis before it is used: **net realised,
grade B, own retail B2C, ex-VAT, GBP, today's money.** Get this wrong and
nothing downstream can be right — in particular, the channel multipliers are
calibrated on an ex-VAT net-realised basis, so they are smaller than the
VAT-inclusive gaps people quote from memory.

## What is and is not built

Built: source profiles and ingestion with dedup and exclusion rules; the
rule-based parser and gazetteers; identity resolution with a review queue;
the full normalisation chain; Tier 1 and Tier 2 estimation with shrinkage,
MAD outlier rejection and predictive intervals; confidence scoring; the buy/
list/parts commercial layer with holding cost; purchase lots with value-weighted
cost allocation; the grading checklist with hard stops; unit lifecycle events;
nightly revaluation; ageing report and action queue; a CLI with full
explanation output.

Not yet built (later phases of the design doc): the Tier 3 family regression
and Tier 4 global ML model; source bias auto-calibration; censored non-sale
feedback; the walk-forward backtest harness; parts register and refurb job
costing; multi-channel listing sync; the HTTP API.

`estimate()` returns `value=None` rather than guessing when it has no
comparable evidence. That is deliberate — Tier 4 is what fills that gap, and
it does not exist yet.

## A note on the numbers

`tests/test_pipeline.py::TestEndToEnd` is the strongest check in the suite.
`scripts/make_demo_data.py` generates prices by running the normalisation chain
*backwards* — from a known reference value out through depreciation, grade,
channel, fees and VAT, plus noise. The test then asserts the pipeline recovers
the original values within 5%. It currently recovers them within 1.7%.
