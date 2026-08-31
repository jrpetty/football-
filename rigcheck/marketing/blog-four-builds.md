# Four PC builds for 2026, and what they actually do

*Published by RIGCHECK. Every figure below comes from an open model you can
check — including the parts of it that are guesswork.*

---

Most build guides give you a parts list and a frame rate, and no way to tell
whether either was arrived at by measurement or by vibes. This one gives you the
same two things, plus a third: where the numbers came from, and how much you
should trust them.

The short version of that third part, up front, because it changes how you
should read everything else. **These frame rates are modelled, not measured.**
They come out of a model that takes each part's specification — shader count,
clocks, memory bandwidth, cache — and works forward to a frame rate, calibrated
against reference figures for each game. Nobody built these four machines and
benchmarked them. The ordering is reliable; treat the absolute numbers as ±20%.

That is a weaker claim than most build guides make. It is also, as far as I can
tell, a more honest one.

---

## The four builds

Each was produced by the same planner, given a budget, a screen and six games
to run. Nothing is sponsored, there are no affiliate links, and no part is
promoted — the planner picks on price and fit alone.

| Budget | Comes to | Graphics | Processor | For |
|---|---|---|---|---|
| £700 | **£582** | Intel Arc A580 | Ryzen 5 5600 | 1080p, 144Hz |
| £1,100 | **£1,089** | Radeon RX 9070 | Ryzen 7 5800X3D | 1440p, 144Hz |
| £1,800 | **£1,719** | GeForce RTX 4080 Super | Ryzen 7 7800X3D | 1440p, 165Hz |
| £2,600 | **£2,012** | GeForce RTX 5080 | Ryzen 9 9900X3D | 4K, 144Hz |

Three of the four come in under budget. That is not the planner being clever —
it is the planner refusing to spend money that buys nothing you asked for. Once
every game on your list clears your target frame rate, more graphics card is
just more graphics card.

---

## Put them on one ladder

The single most misleading thing a build guide does is compare machines at
different resolutions and print the frame rates next to each other. At its own
target, the £2,012 build runs Cyberpunk at 65fps and the £582 one runs it at
65fps — identical numbers, wildly different machines, because one is doing four
times the pixels.

So here they are on the same test. Cyberpunk 2077, 1440p, high preset, no
upscaling, all four:

| Build | Cyberpunk @ 1440p |
|---|---|
| £582 · Intel Arc A580 | 43 fps |
| £1,089 · Radeon RX 9070 | 83 fps |
| £1,719 · GeForce RTX 4080 Super | 115 fps |
| £2,012 · GeForce RTX 5080 | 129 fps |

Note what happens between the third and fourth rows. £293 more buys
14 more frames — about 12%, for 17% more money. The curve
flattens hard after the £1,700 mark, and that is the single most useful thing
on this page if you are deciding what to spend.

---

## £582 — 1080p, high, no compromises worth mentioning

**Intel Arc A580 · Ryzen 5 5600 · 16GB DDR4 · 260W, 400W supply**

| Game | Average | 1% low |
|---|---|---|
| Counter-Strike 2 | 355 fps | 216 fps |
| Fortnite | 122 fps | 74 fps |
| Cyberpunk 2077 | 65 fps | 44 fps |
| Baldur's Gate 3 | 71 fps | 48 fps |
| Call of Duty Black Ops 6 | 107 fps | 67 fps |
| Elden Ring | 60 fps | 46 fps |

Cyberpunk at 65fps is the honest headline here: this is a machine that
plays everything at 1080p and plays the demanding things at sixty rather than a
hundred and forty. Elden Ring reads 60 because Elden Ring is capped at 60 — that
is the game, not the build.

The interesting choice is the Intel Arc A580. Intel's cards are priced against their
reputation rather than their silicon, which makes them good value and slightly
riskier: driver quality has improved enormously but is still the thing most
likely to bite you.

---

## £1,089 — the one most people should buy

**Radeon RX 9070 · Ryzen 7 5800X3D · 32GB DDR4 · 332W, 500W supply**

| Game | Average | 1% low |
|---|---|---|
| Counter-Strike 2 | 370 fps | 232 fps |
| Fortnite | 146 fps | 91 fps |
| Cyberpunk 2077 | 83 fps | 58 fps |
| Baldur's Gate 3 | 92 fps | 63 fps |
| Call of Duty Black Ops 6 | 142 fps | 92 fps |
| Elden Ring | 60 fps | 47 fps |

This is where the money stops being theoretical. 83fps in Cyberpunk at
1440p, 146 in Fortnite, 370 in Counter-Strike — a machine that does the
thing you bought it for at the resolution you bought the monitor for.

The Ryzen 7 5800X3D is here for a specific reason. Its stacked cache is worth very
little in a GPU-bound shooter and a great deal in simulation and strategy
titles, which is why Baldur's Gate 3 is the one game on this list where the
processor, not the card, is the limit.

---

## £1,719 — for a high-refresh 1440p panel

**GeForce RTX 4080 Super · Ryzen 7 7800X3D · 32GB DDR5 · 426W, 650W supply**

| Game | Average | 1% low |
|---|---|---|
| Counter-Strike 2 | 456 fps | 285 fps |
| Fortnite | 192 fps | 120 fps |
| Cyberpunk 2077 | 115 fps | 80 fps |
| Baldur's Gate 3 | 112 fps | 78 fps |
| Call of Duty Black Ops 6 | 194 fps | 126 fps |
| Elden Ring | 60 fps | 42 fps |

Everything except Cyberpunk clears 165fps, and Cyberpunk clears 100. If you own
a 165Hz or 240Hz 1440p monitor and you play competitively, this is the build
that actually feeds it.

---

## £2,012 — 4K, and where the argument gets weak

**GeForce RTX 5080 · Ryzen 9 9900X3D · 32GB DDR5 · 464W, 800W supply**

| Game | Average | 1% low |
|---|---|---|
| Counter-Strike 2 | 314 fps | 196 fps |
| Fortnite | 117 fps | 73 fps |
| Cyberpunk 2077 | 65 fps | 45 fps |
| Baldur's Gate 3 | 90 fps | 71 fps |
| Call of Duty Black Ops 6 | 118 fps | 77 fps |
| Elden Ring | 60 fps | 47 fps |

It came in £588 under a £2,600 budget, and it should have come in further
under. At 4K, Cyberpunk lands at 65fps — the same number the £582 machine
manages at 1080p, which tells you exactly how much four times the pixels costs.

If you have a 4K monitor and you want high refresh rates in demanding titles,
the honest answer is that no build at this price does it without upscaling. Turn
DLSS or FSR on and the picture changes completely. Every number on this page is
native resolution, because upscaling numbers depend on which mode you pick and I
would rather quote the floor.

---

## Why you should be sceptical of all of this

The model behind these figures reports a median error of about 12% against its
own validation set. That sounds reassuring and it is not, quite, because of what
the validation set is.

Every fixture in it is *recalled* rather than measured — a model's memory of
widely-reported review configurations, not a capture from a real machine. So
that 12% measures whether the estimator agrees with the memory it was built
from. It is a self-consistency figure. A perfect score would mean the two halves
agree, not that either matches your desk.

That is why the tool has a whole screen dedicated to arguing against its own
outputs, and why the number it reports is labelled "disagreement with the
recalled set" rather than "accuracy". If you take one thing from this post,
take that distinction and apply it to every build guide you read — including the
ones that sound much more confident than this one.

**What these numbers are good for:** deciding roughly what to spend, seeing
where the price/performance curve flattens, and knowing which component is
going to be your limit.

**What they are not good for:** promising you a specific frame rate in a
specific game. Nobody can do that from a spec sheet, and the ones who imply they
can are not measuring either.
