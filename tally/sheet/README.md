# The spreadsheet

`Gardeners Arms.xlsx` is the app on four sheets of paper, for anyone who would
rather type into Excel than photograph a till roll.

```
pip install openpyxl
cd tally/sheet && python3 build.py
```

It writes `Gardeners Arms.xlsx` beside itself. The workbook is marked to
recalculate the moment it is opened, so Excel, Numbers and Google Sheets all work
every formula out for themselves; openpyxl stores no answers of its own, which is
why a preview tool that only reads cached values will show the formula columns
empty.

To check the formulas actually pull what they should:

```
pip install formulas
python3 check.py "Gardeners Arms.xlsx"
```

That works the whole book out with a third-party implementation of Excel's
semantics and compares it against the figures typed in again by hand — her stock
take and the printed roll of 23 August 2026. It caught a real one: the first
draft tested "has a figure been put in yet?" with `COUNTIFS(range,"<>")`, which
different spreadsheets read differently, and which can make an uncounted night
look like a balanced one. It asks `ISNUMBER` now.

```
python3 check-join.py "Gardeners Arms.xlsx"
```

That one is the load-bearing check. It copies the workbook, moves the stock take
back to before the receipt, and proves every line the roll sold actually comes
off the cellar — 120 pints and 19 halves of Taddy leaving 1,056 pints at 926.5,
and so on down all nineteen lines, including the two that sold nothing and must
not move. A cellar that is quietly not being reduced looks exactly like one that
is, so it is checked rather than assumed.

## The one-sheet version

`Gardeners Arms stock.xlsx` is what she asked for when the four-sheet one was
still too much: **one tab**, one row per line, count on the left and what the
till sold on the right.

```
python3 build-stock.py     # writes it
python3 check-stock.py "Gardeners Arms stock.xlsx"
```

| Line | Counted in | Counted | Sold | Left | Money it took | A full one is |

The Sold and Money columns come in filled with the real night of 18 September
2026 — both cash-ups added, £1,174.75 at 16:57 and £836.30 at 22:46, £2,011.05
over 590 items. They are worked out in `build-stock.py` from `night.json`, which
is the app's own reading of her two receipts, converted into the units she
counts in: 123 pints and 20 halves of Taddy is 133 pints.

`TAKES` in the builder is the map from till button to cellar line. It holds only
the certain ones. Nine things the till sells — OBB and Pure Brew on draught, the
post mix, the spirits, open food — get a line of their own marked *not counted
yet* rather than being pushed onto whichever counted line looks closest. The
builder asserts that every one of the 32 buttons is accounted for and that the
money foots to the receipt, so a new line on the till breaks the build rather
than quietly vanishing.

## What is in it

| Sheet | What it holds |
| --- | --- |
| Read me | Which boxes to type in, and what the arithmetic assumes |
| Nights | One row per receipt. Rows sharing a date are added and judged as one night |
| Sold | One row per line the receipt sold: how many, and what one sale takes |
| Cellar | The stock take, less whatever the Sold sheet says went since it was counted |

`cellar.json` is the stock take of 16 September 2026, exported from the app's
seed so the two do not drift. If the seed changes, export it again rather than
editing this by hand.

## The rules it keeps

The same ones the app keeps, because a sheet that quietly rounds them off is
worse than no sheet:

- A pint is 568ml exactly, so two halves make one pint and a keg entered as 176
  pints cancels dead against 176 pints of sales.
- A night with no card or cash figure in it reads *not counted yet*, never £0.
- A cellar line with no count against it reads *not counted*, never zero.
- Only sales dated on or after the stock take come off it.
- A Sold line whose name matches nothing in the cellar is called out rather
  than silently absorbed — the Cellar sheet counts them.
