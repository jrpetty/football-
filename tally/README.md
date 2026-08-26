# Tally — pub till reconciliation

Mum runs a Samuel Smith's pub. Every night she reconciles the till by hand: paper
till roll, card machine slip, a cash count, and about an hour of writing and
adding up. There is no till software to export from — the receipts are the only
record there is.

Tally is v1 of replacing that hour with two or three minutes. She photographs
two receipts, types one number, and gets a balance check she can trust.

```
   Till roll ──photo(s)──►  transcribed  ──►  parsed  ──►  cross-footed
                                                              │
                            the roll states: takings, cash, card, CID,
                            departments, counts, clerks, items
                                                              │
   Card slip ──photo──►  read ──┐                             │
   Drawer    ─────typed─────────┤                             │
                                ▼                             ▼
                    counted  ────────  compared with  ──── the till's own figures
                                                              │
                    ✅ Balanced   ⚠️ The drawer is £12 light, the card agrees
```

---

## Using it

Open it, and it is already on tonight. Photograph the till roll, photograph the
card slip, count the drawer and type that in. The verdict updates as each figure
lands, so a mistake shows up while the receipts are still in your hand rather
than at the end. Save, and it joins the history.

The roll is long, so it takes as many photographs as it needs — each one is
merged into what is already held, and the summary can be photographed after the
item list without wiping it.

Every scanned figure lands in an ordinary editable box. **Nothing is ever locked
to what the scanner read.** That is a deliberate design rule, not a fallback:
receipt paper defeats scanners often enough that an app which merely *reported*
a number would be trusted once and abandoned the moment it was wrong.

### Installing it on her phone

It is a web app, so there is no app store. Open the link in Safari (iPhone) or
Chrome (Android) and choose **Add to Home Screen**. After that it opens like any
other app, full screen, and works with no signal.

---

## The two decisions the brief asked to be made

### Platform: a PWA

Confirmed with Jon before building. An installable web page beats native or
React Native here for one reason that outranks the rest: **the fix loop**. When
something is wrong at 11pm on a Friday — and in the first fortnight something
will be — a PWA is fixed by pushing, and she has it on the next open. Native
means a build, a TestFlight round trip and a wait. For a single user who needs
this working within a couple of weeks, that difference dominates everything
else.

What it costs: the on-device scanner is worse than a native one would be
(below), and iOS gives web apps a smaller storage allowance than a native app.
Both are acceptable at one pub. Neither survives the phase-three multi-pub
version, which is the right time to revisit it.

### OCR: both engines, Claude by default

The brief asked for on-device and hosted to be evaluated. Both are built, both
work, and either can be selected in Settings. The default is Claude.

| | **Claude vision** (default) | **On-device (Tesseract WASM)** |
|---|---|---|
| Faded thermal paper | Good | Poor — the failure case, not the edge case |
| Photographed at an angle | Good | Poor; wants a flat, square scan |
| Picking the *right* line | Understands that `GROSS TOTAL` is the figure and `SUBTOTAL` is not | Only sees shapes; relies on our keyword rules |
| Works with no signal | No | Yes, after the first run |
| Cost | A few pence a night — a pound or two a month | Free |
| Privacy | The photograph goes to Anthropic | Never leaves the phone |
| Setup | Needs an API key | None |

The deciding factor is the third row. A till roll's layout differs for every
till in every pub, and the hard part is not reading the characters but knowing
which of fifteen printed amounts is the session total. That is a comprehension
problem, and a conventional scanner cannot do it — which is why the on-device
path needs the hand-written keyword rules in `src/ocr/extractTotal.ts`, and why
it is still the weaker of the two.

### The layout trap

The hard part of this receipt is not the characters. It is that the till spreads
one record across several printed lines:

```
D01                             406.000 Q     <- code and quantity
DRAUGHT BEERS                    *1492.25     <- name and value
                                   68.05%     <- percentage, alone
```

`CASH`, `CREDIT CARD`, `VOID`, the group subtotals and the department total all
split the same way. An earlier version of this parser assumed one line per
record, was built against a tidy layout invented for the fixture, and passed
every test while being able to read **nothing at all** from the real paper. The
fixture is now transcribed from the photograph, in the layout the till actually
prints, and there is a second copy of it flattened onto one line per record — a
transcription may straighten the columns despite being asked not to, and a test
holds both to producing the identical result.

So the parser reads *records*, not lines: a starter line opens one, the lines
below fill in what it is missing, and the next starter closes it. It tolerates
inconsistent spacing, blank lines mid-record, a missing percentage, `* 1492.25`
with a space after the star, and `D1` where the till printed `D01`.

### The division of labour

Claude is asked only to **transcribe** — to copy the roll out line by line,
character for character. It is not asked to find the total, add a column up, or
decide which figure matters. All of that happens afterwards in `parseZRead`,
which is pure, tested, and pinned to the real roll.

That split is deliberate. Transcription is what a vision model is reliably good
at; interpretation is what tested code is reliably good at. Asked to do both, a
model can hand back a wrong figure that looks exactly like a right one. Split
this way, a misread digit almost always breaks one of the receipt's own
equations — and then the app can point at the line.

It also means both engines go down one path: text in, parsed, cross-footed. Only
the quality of the text differs.

The on-device engine is not decoration. When the wifi drops mid-scan the app
falls back to it automatically and says so. And when neither can run, the boxes
are still there to type into — **every path ends somewhere she can finish the
night**, because the paper process it replaces never had a dead end.

Reading the text is only half of it. Turning that text into a figure is
`extractTotal.ts` and `money.ts`, which are pure, have no idea which engine
called them, and carry most of the test suite. Some of what they defend against:

- `SUBTOTAL` sitting directly above `GROSS TOTAL`, and the `CASH`/`CARD` split
  directly below it — all real totals of real things, all the wrong one.
- Merchant IDs, terminal IDs and the last four of a card number, which are
  well-formed numbers that are not money.
- `l`→`1`, `O`→`0`, `S`→`5`, `B`→`8` on a faded roll — repaired, but **only**
  inside tokens that are already mostly digits. Unguarded, that repair turns the
  printed word `SALE` into `5413` and hands back £54.13 with total confidence.
- `1,234` meaning twelve hundred pounds, not £1.23.
- Receipt columns: `DEPT 1     2104.50` must not become £12,104.50.

---

## The receipt checks its own reading

This is the part that changed once a real Z read turned up.

The roll from the Gardeners Arms — 23 August 2026, Z counter 1685 — is heavily
redundant. The departments sum to the department total. Cash plus card equals
the paid total. The transaction counts sum to the guest count. The paid total
divided by the guests is the printed average. Each department's percentage
recomputes from its own value. The item list sums to the same figure as the
departments. The clerks sum to the night.

That is not decoration — it is a set of simultaneous equations, and a misread
digit almost always breaks one of them. So `core/crossfoot.ts` runs all of them
and reports what disagrees:

> The departments add up to the department total — should be £2,192.80, the roll
> says £2,192.40

That is worth far more than a confidence score. "80% sure" cannot be acted on;
"one of these six lines is wrong" can. The checks re-run on every keystroke while
she is correcting, so fixing the digit turns the failing sum green in front of
her.

**The author was caught by it twice.** Transcribing that roll by eye, the item
list came out £25.35 over the department total, and GT3 was read as −£21,185.57
out of "-00000021185.57" when the padding zeros hide that it is −£2,185.57. Both
were found by arithmetic, not by re-reading.

The first turned out to be a misdiagnosis, and the fix is instructive: the item
*figures* were right all along — what was wrong was a guess at which department
each item belongs to, which the receipt never states. Checked the way the
receipt actually allows, all 38 lines come to 689 items and £2,192.80, matching
its own printed total on both counts, and the first twelve come to exactly D01's
406 items and £1,492.25. The item list is in the fixture now because it
reconciles, not because it was read carefully.

Two traps that roll contains, both now covered by tests:

- `2188.80` (clerk 4's takings) and `2188.40` (GROUP01) are different real
  figures that look near-identical on thermal paper.
- `689` and `699` on the department quantity are genuinely ambiguous in the
  photograph. The department quantities settle it: they sum to 687 + 2.

One check needs yesterday as well as tonight. The **Z counter** is the only
field on the roll that can reveal a night that was never entered at all — a
missing day's figures are not wrong, they are absent, and nothing inside a single
receipt can notice that. The running grand total (GT1) is checked the same way:
it should have moved by exactly tonight's takings.

---

## What the roll actually gives you

Far more than a session total, and all of it is kept:

| | |
|---|---|
| **Departments** | Draught beers, Spirits, Wine, Bottled beers, Mixers, Sundries, Open food — quantity, value and percentage each |
| **Groups** | GROUP01/GROUP02 subtotals |
| **Payments** | Cash and card, each with its transaction count, plus **CID** — what should physically be in the drawer |
| **Counts** | Guests, average spend, voids, no-sales |
| **Clerks** | The same breakdown per clerk |
| **Items** | The PLU list, when it is legible |
| **Continuity** | Z counter and the running grand totals |

**The reconciliation was rebuilt around this.** The till states cash (£351.80)
and card (£1,841.00) separately, so the card slip is no longer the only source
for the card figure — it is a second opinion on a number the till already gave.
That turns one blended answer into two answerable ones:

> ~~You are £12 short~~
> The card machine agrees with the till to the penny — the difference is in the drawer.

The first leaves her hunting through a whole night. The second is most of the way
to knowing why. The two legs necessarily sum to the overall variance, because
the receipt's own cash + card = paid total guarantees it — and cross-foot checks
that too.

---

## The dashboard

Every number on it comes from `core/analytics.ts`, which is pure and tested.
Nothing on the screen counts anything itself, which is what stops a chart quietly
disagreeing with the table beside it.

- **Filters** — date range, weekday, department, and "didn't balance". Filtering
  to two departments re-bases their percentages onto each other, because that is
  what a filter is for.
- **Takings by night**, department **mix with percentages**, **how far out each
  night was**, and **average night by weekday** — which is the "are Fridays
  always short?" question from phase two, arriving early because the roll had the
  data all along.
- A **department table with percentages** sits under the chart, always visible.

Some notes on how the charts are built, since they were not improvised:

- The palette is validated with a runnable checker against both surfaces rather
  than eyeballed — including colourblind separation. Light mode returns a
  contrast relief on three of the eight hues, and the always-visible table *is*
  that relief, which is why it is not behind a toggle.
- **Colour follows the department, not its rank.** `core/departments.ts` pins
  D01–D08 to fixed palette slots for the life of the app, so filtering Wine out
  cannot repaint Mixers. A reader who learned "draught is blue" is never misled.
- Short and over use **status colours, not a diverging pair** — deliberately.
  Short and over are not opposites in a till: being short is worse. A symmetric
  warm/cool pair would deny that. Both states carry a legend and a labelled axis,
  so colour is never the only signal.
- Weighted averages, not averages of averages: a quiet Monday must not weigh the
  same as a packed Saturday.
- Both a **net** and an **absolute** variance total, because one night £50 over
  and one £50 short nets to zero and would read as a fortnight with nothing
  wrong.

---

## Decisions worth knowing about

**The trading day.** A count finished at 00:30 on Saturday belongs to *Friday's*
trade. Before 5am the date therefore defaults to the previous day, and says so on
screen. It is editable, as everything here is. Without this, every late night
would be filed one day forward, and the mislabelling would be invisible until
someone tried to explain a Friday that looked empty.

**"Balanced" allows 50p by default.** Not zero. A till that has taken four
hundred cash transactions is routinely a few pence out through honest rounding,
and an app that cried wolf every single night would be ignored inside a week —
which is the only failure mode that actually matters. Adjustable, including down
to zero.

**Money is integer pence everywhere.** Pounds exist only when text is read in
and when a figure is printed out. Floating point has no business in a
reconciliation whose entire purpose is whether two numbers match.

**The receipts are kept.** A shrunk copy of each photograph is stored with the
night, because "why was Tuesday £20 short" gets asked three weeks later, and
answering it is the one thing the paper ledger was genuinely good at.

**The API key lives in her browser** and goes straight to Anthropic with no
server in between. That is the right trade for one person on a static host. It
is **the first thing that has to change** for the phase-three multi-pub version,
where it belongs behind a backend.

---

## Running it

```bash
npm install
npm run dev        # development
npm test           # the pure logic — money, dates, reconciliation, receipt parsing
npm run build      # production build into dist/
npm run test:e2e   # the whole flow in real Chromium (build first)
npm run icons      # re-rasterise the PNGs after editing public/icon.svg
```

`npm test` covers the arithmetic, the receipt parsing and the cross-foot checks —
including parsing the real Gardeners Arms roll and asserting it reproduces a
hand transcription exactly. `npm run test:e2e` covers what unit tests cannot: that
the verdict updates as she types, that a saved night comes back after a reload,
that correcting a night updates rather than duplicates it, that a *failed* scan
still leaves her able to finish, and that the dashboard's figures and percentages
match the real receipt — seeded into storage and read back through the interface.

### Deploying

The repository's Pages workflow publishes this under `/tally/` on the default
branch. The Vite base is relative, so the same build also works at a site root
or from a home-screen launch.

---

## Layout

```
src/
  core/        pure domain — money, dates, the Z read, cross-foot, analytics
  ocr/         transcription (both engines) + the parser + preprocessing
  storage/     IndexedDB, settings, CSV/JSON export
  components/  MoneyInput, FigureCard, TillRollCard, Verdict, charts
  screens/     NewDay, ZReadReview, Dashboard, History, DayDetail, Settings
```

The rule is that `core/` and `ocr/extractTotal.ts` know nothing about React,
IndexedDB or any OCR engine. They are where the reasoning lives, so they are
where the tests are.

---

## Beyond the nightly count

The reconciliation above was v1. The rest grew out of it, each piece reading
what the nights already record rather than asking for new typing:

- **The Cellar** — stock in real containers (a kil is 144 pints), deliveries
  booked in by photographing the note, stock takes, and a variance that names
  the loss no balanced drawer can see: beer that left without going through the
  till. Costs per container turn into cost per pint, what the cellar is worth,
  and a dead-stock list of what is not earning its space.
- **Gross profit** — the price book (photographable off the board) joined to
  the cellar costs gives GP per line on the plain basis the bar talks in — the
  board price against the invoice price — and a price-history log shows which
  brewery rises the board never followed.
- **The Rota** — people, shifts, a weekly hours target, wage costs, and each
  person's record: how the drawer behaves on their nights against everyone
  else's, with the sample-size honesty that comparison demands.
- **Trade intelligence** — a weather-informed forecast of next week fitted to
  this pub's own history, like-for-like against 52 weeks back, and a capped
  list of weekly findings worth acting on.
- **Paperwork** — any night shareable as plain text for the accountant, and a
  one-button year-end pack: takings by month, stock at cost, wages and what
  was bought in, stamped as working figures rather than a return.

There is still no cloud sync, on purpose — which is why "Save everything" in
Settings matters. One file carries the whole app (nights, prices, cellar, rota,
settings; never the API key) to a new phone, laptop or newer copy, and "no
cloud" never means "one dropped phone and the year is gone".

**The brewery pitch**, someday: multi-pub, manager accounts, aggregate
reporting. That is the version that needs a backend, and the point at which the
API-key-in-the-browser decision above gets paid back.

---

## What "done" looks like

Mum uses it for real, for a fortnight straight, and it is visibly faster than the
paper. Nothing after this paragraph gets built until that has happened.
