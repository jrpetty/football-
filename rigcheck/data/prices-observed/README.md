# Observed prices — the lane for real market data

The prices in `data/pricing/gbp-new.json` and `gbp-used.json` are **recalled, not
sourced**. They were written from a model's memory of roughly mid-2026 UK street
prices and never checked against a retailer or a marketplace. They exist so the
build planner has something to work with; they are not evidence.

This directory is how you replace them with something real. Drop a CSV in here,
run `npm run import:prices`, and every figure you supply overrides the seed for
that part — with its source, its date and its sample size carried through to the
screen, so a sourced price is visibly a different kind of thing from a recalled
one.

## The one thing that matters most: sold, not asking

On any marketplace, **asking prices are not prices**. A used RTX 3070 might be
listed at £280 by ten optimistic sellers and actually change hands at £190. The
listings you can see most easily are the ones nobody bought — that is precisely
why they are still visible.

On eBay the distinction is a checkbox:

1. Search for the part, e.g. `RTX 3070` .
2. In the filters, tick **Sold Items** (and **Completed Items** if you want to
   see what failed to sell — useful context, not data).
3. Sort by **Ended: recently**.
4. Ignore the obvious contamination: bundles, whole PCs, "for parts / not
   working", mining-farm lots, and anything with a suspiciously round Buy It Now
   that never went to auction.
5. Take the **median** of what is left, not the mean. One £600 collector sale
   drags a mean and does nothing to a median.
6. Record how many sales you used. Five is thin, twenty is solid.

The importer accepts `asking` and `retail` as a basis, because sometimes that is
all you have — but it will say loudly that asking prices run high, and the
planner marks anything sourced that way as weaker evidence.

## Format

One row per observation. Header required, column order irrelevant, extra columns
ignored.

```csv
part_id,condition,basis,price,currency,source,observed_date,sample_size,note
nvidia-geforce-rtx-3070,used,sold,192,GBP,ebay-uk,2026-08-20,14,median of 14 sold auctions
amd-radeon-rx-6700-xt,used,sold,168,GBP,ebay-uk,2026-08-20,9,
nvidia-geforce-rtx-5070,new,retail,549,GBP,scan-uk,2026-08-19,1,in stock
```

| column | required | notes |
|---|---|---|
| `part_id` | yes | must match a catalogue id exactly; unknown ids are rejected by name |
| `condition` | yes | `new` or `used` |
| `basis` | yes | `sold` (what it went for), `asking` (what it is listed at), `retail` |
| `price` | yes | a number, in `currency` units, per single part |
| `currency` | yes | `GBP`, `USD` or `EUR` |
| `source` | yes | free text, e.g. `ebay-uk`. Recorded so a figure is traceable |
| `observed_date` | yes | `YYYY-MM-DD`. Prices decay; the screen shows the age |
| `sample_size` | no | how many sales the figure came from. Defaults to 1, which is weak |
| `note` | no | anything worth remembering about how it was gathered |

## What the importer does with it

- **Multiple observations of the same part and condition are combined by a
  sample-weighted median**, not a mean, and the spread is kept. Twenty sales at
  £190 and one at £600 gives £190 and a note about the outlier.
- **Asking-basis rows are discounted** by a documented factor before use, and
  flagged. They are better than nothing and worse than a sold price.
- **Rows older than 90 days are still imported but marked stale.** GPU pricing
  moves; a six-month-old figure is a historical note.
- **Every rejection is reported with a reason.** Nothing is dropped silently.

## Which parts are worth your time

You do not need all 279. The planner only ever recommends parts it has a price
for, so the ones that matter are the ones you would actually consider. Twenty
well-sourced used GPUs across the range is worth more than two hundred guesses,
and `npm run import:prices` prints which catalogue parts are still running on
the recalled seed once you are done.
