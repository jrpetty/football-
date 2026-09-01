# Does your CPU actually matter? It depends on the game — and on your graphics card

*Every figure here comes from RIGCHECK's estimator. Modelled, not measured:
the ordering is reliable, the absolute numbers are ±20%.*

---

"Am I CPU bottlenecked?" is the most-asked question in PC building and the one
most often answered badly, because it is usually answered with a single number.
There isn't one. Hold the graphics card fixed, swap four processors underneath
it, and the answer moves from "worth two thirds more frames" to "worth nothing
at all" depending entirely on which game you name.

Here is the same four-processor ladder — Ryzen 5 3600 → 5600 → 7 5800X3D →
7 7800X3D — run twice. Once on an RTX 4070, once on an RTX 4090. Everything
else identical: 1440p, high preset, 32GB dual-channel, no upscaling.

## On an GeForce RTX 4070

| Game | 3600 | 5600 | 5800X3D | 7800X3D | Worth |
|---|---|---|---|---|---|
| Baldur's Gate 3 | 60 | 74 | 91 | 100 | **+67%** |
| Total War Warhammer III | 71 | 87 | 99 | 102 | **+44%** |
| Counter-Strike 2 | 298 | 345 | 361 | 366 | **+23%** |
| Cyberpunk 2077 | 73 | 78 | 80 | 80 | **+10%** |
| Fortnite | 135 | 140 | 141 | 141 | **+4%** |
| Factorio | 60 | 60 | 60 | 60 | **+0%** |

Three groups, and they are not subtle.

**Baldur's Gate 3 and Total War** gain 67% and 44%. These are simulation-heavy
games: lots of entities, lots of logic, and a rendering load that a 4070 eats
easily. The processor is the wall, and the 3D V-Cache parts are where the wall
moves — that stacked L3 is worth more here than anything else you can buy.

**Cyberpunk and Fortnite** gain 10% and 4%. The card is saturated. You could
put the best processor made underneath this graphics card and get four extra
frames in Fortnite. If these are your games, a CPU upgrade is close to wasted
money.

**Factorio gains nothing**, because Factorio is capped at 60. That is the game,
not the hardware, and a chart that does not say so makes a fine processor look
pointless.

## On an GeForce RTX 4090

Now the same four processors, same games, same resolution — with the graphics
card no longer the limit.

| Game | 3600 | 5600 | 5800X3D | 7800X3D | Worth |
|---|---|---|---|---|---|
| Total War Warhammer III | 71 | 93 | 122 | 145 | **+104%** |
| Baldur's Gate 3 | 60 | 75 | 95 | 114 | **+90%** |
| Cyberpunk 2077 | 83 | 104 | 130 | 145 | **+75%** |
| Counter-Strike 2 | 310 | 398 | 470 | 526 | **+70%** |
| Fortnite | 163 | 204 | 229 | 241 | **+48%** |
| Factorio | 60 | 60 | 60 | 60 | **+0%** |

Every single number went up, and the *shape* changed completely.

Cyberpunk went from a 10% spread to 75%. Fortnite from 4% to 48%.
Counter-Strike from 23% to 70%. Nothing about the processors changed — the
graphics card stopped being the thing holding them back, and the processor
became the thing that was.

That is the actual answer to "does my CPU matter":

> **Your CPU matters exactly as much as your GPU is not the limit.**

Which is why "is a 5600 enough?" cannot be answered without knowing what card
it is sitting next to and what you play. On a 4070 playing shooters, a
Ryzen 5 5600 is within a few frames of the best part on this list. On a 4090
playing Total War, it leaves 52 frames on the table.

## How to use this

1. **Name your game first.** Simulation, strategy and anything with a lot of
   entities is where processors earn their money. Shooters at high resolution
   are where they mostly do not.
2. **Then look at your card.** The faster it is, the more the processor matters
   — the reverse of how this is usually explained.
3. **Then check your resolution.** Everything above is 1440p. Move to 4K and the
   graphics card takes back the limit in almost every title; move to 1080p and
   the processor takes more of it.

## The caveat, which is the same as always

These are modelled from part specifications and per-game reference figures.
Nobody built eight machines and benchmarked them. The model's validation set is
itself recalled rather than measured, so its reported error is a
self-consistency figure and not accuracy.

The ordering is what I would stand behind: that Baldur's Gate 3 responds to a
processor and Fortnite does not, that a faster card makes the processor matter
more. The exact percentages are a guide with a wide band around them.
