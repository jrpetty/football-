# Tally — pub till reconciliation

Mum runs a Samuel Smith's pub. Every night she reconciles the till by hand: paper
till roll, card machine slip, a cash count, and about an hour of writing and
adding up. There is no till software to export from — the receipts are the only
record there is.

Tally is v1 of replacing that hour with two or three minutes. She photographs
two receipts, types one number, and gets a balance check she can trust.

```
   Till roll  ──photo──►  read  ──►  she confirms or corrects  ──┐
   Card slip  ──photo──►  read  ──►  she confirms or corrects  ──┤
   Cash       ──────────────typed──────────────────────────────┤
                                                                ▼
                                          (card + cash) − till roll
                                                                ▼
                                    ✅ Balanced   ⚠️ Short by £X   ⚠️ Over by £X
```

---

## Using it

Open it, and it is already on tonight. Photograph the till roll, photograph the
card slip, count the drawer and type that in. The verdict updates as each figure
lands, so a mistake shows up while the receipts are still in your hand rather
than at the end. Save, and it joins the history.

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
| Cost | Roughly a penny a night | Free |
| Privacy | The photograph goes to Anthropic | Never leaves the phone |
| Setup | Needs an API key | None |

The deciding factor is the third row. A till roll's layout differs for every
till in every pub, and the hard part is not reading the characters but knowing
which of fifteen printed amounts is the session total. That is a comprehension
problem, and a conventional scanner cannot do it — which is why the on-device
path needs the hand-written keyword rules in `src/ocr/extractTotal.ts`, and why
it is still the weaker of the two.

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

`npm test` covers the arithmetic and the receipt parsing. `npm run test:e2e`
covers what unit tests cannot: that the verdict updates as she types, that a
saved night comes back after a reload, that correcting a night updates it rather
than duplicating it, and that a *failed* scan still leaves her able to finish.

### Deploying

The repository's Pages workflow publishes this under `/tally/` on the default
branch. The Vite base is relative, so the same build also works at a site root
or from a home-screen launch.

---

## Layout

```
src/
  core/        pure domain — money, dates, the reconciliation itself
  ocr/         extractTotal (pure, tested) + the two engines + preprocessing
  storage/     IndexedDB, settings, CSV/JSON export
  components/  MoneyInput, FigureCard, Verdict
  screens/     NewDay, History, DayDetail, Settings
```

The rule is that `core/` and `ocr/extractTotal.ts` know nothing about React,
IndexedDB or any OCR engine. They are where the reasoning lives, so they are
where the tests are.

---

## Not in v1, on purpose

Staff hours, wage costs, stock deliveries, weekly and monthly summaries,
multi-pub, manager accounts, any accounts system at all.

There is no cloud sync — which is why the export in Settings matters. "No cloud"
must not mean "one dropped phone and the year is gone", so there is a spreadsheet
export and a full backup file she can mail to herself, and nothing is trapped
inside the app.

**Phase 2**, once this is proven: staff hours and wage cost per day, stock
delivery costs, and the weekly view that answers "are Fridays always short?".
Both phase-two figures hang off the same date key, so they are additive rather
than a rewrite.

**Phase 3**, the brewery pitch: multi-pub, manager accounts, aggregate reporting.
That is the version that needs a backend, and that is the point at which the
API-key-in-the-browser decision above gets paid back.

---

## What "done" looks like

Mum uses it for real, for a fortnight straight, and it is visibly faster than the
paper. Nothing after this paragraph gets built until that has happened.
