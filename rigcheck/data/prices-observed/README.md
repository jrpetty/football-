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

## Recording one in a line

You do not need to open a file. From a checkout, a sold listing, or a receipt:

```
npm run price -- "i7 7700" used 40
npm run price -- "rtx 3070" used 192 --basis sold --source ebay-uk --n 14
npm run price -- "rtx 5070" new 549 --basis retail --source scan-uk
```

It resolves the part by search and refuses to guess between close matches
("7700" is four different parts — it will list them and ask for `--id`),
appends the row to this week's snapshot file, and re-runs the importer so the
app shows the figure immediately. Defaults: `--basis sold`, `--source operator`,
one sample, today's date, GBP. If you read the number off live listings that
have not sold, say `--basis asking` and it is discounted and flagged.

## Weekly snapshots

Files are named for the **Monday of the week** they were recorded in:
`2026-08-31.csv`, `2026-09-07.csv`, and so on. The CLI does this for you; if
you write a file by hand, use the same rule. One file per week keeps the
directory readable and makes "how often do we look" a visible thing rather than
a good intention.

Every dated observation is kept. The importer collapses nothing: a part
observed in July, August and September carries three points, and the app can
say **"down 12% since August"** because there is an August point to say it
against. The *current* figure is the median of the newest window of
observations (45 days back from the newest), so a real fall in price shows in
the week it happens instead of being averaged away by the spring.

Two snapshots of the same part on different dates is the whole trick. One is a
price; two is a trend; a claim like "since August" needs a point in August.

## Old parts: the resale market or nothing

An i7-7700 changes hands for about £40. Plenty of sites will still show it near
what it launched at in 2017, because their "price" is a retail figure that
nobody has updated and nobody pays. The app refuses to make that mistake: for a
part more than four years old, a used-price question is answered from a resale
observation or a recalled used figure, and **never from a new price** of any
kind. If there is no resale observation, the honest answer is "no resale price
recorded", and the fix is the line above.

That is also why this file's first real row is an i7-7700 at £40 — an actual
purchase, recorded the day it happened.

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
ignored. Any file whose name begins with `example` is documentation and is never
imported — a shipped example was once read as five real eBay medians.

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
- **Every date is kept as a point in a series.** The current figure votes only
  from the newest 45 days; older points are what the trend is read from.
- **Every rejection is reported with a reason.** Nothing is dropped silently.

## What to check next

`npm run prices:audit` writes `data/pricing/PRICE-AUDIT.md`: every priced part
and every part the posts name, with whether the figure on file is a **new**
price or a **used** one, where it came from, how old the part is, and a
prefilled `npm run price` line for each. It sorts by what matters — parts the
posts quote with no price at all, then launch-era new prices on old parts,
then every recalled used figure oldest first.

## Which parts are worth your time

You do not need all 279. The planner only ever recommends parts it has a price
for, so the ones that matter are the ones you would actually consider. Twenty
well-sourced used GPUs across the range is worth more than two hundred guesses,
and `npm run import:prices` prints which catalogue parts are still running on
the recalled seed once you are done.
