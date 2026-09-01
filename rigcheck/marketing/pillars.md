# The seven pillars

Seven recurring formats, chosen for one reason: each is a question people
already ask, and the engine can answer it with real numbers rather than
opinion. Every one is repeatable with different parts, so a pillar is a
template that runs again — not a post that gets used once.

They are ranked by how much attention they should get, which is not the same as
how much I like them.

---

## 1 · The silent tax — free performance you are losing

**The hook:** your PC might be 46% slower than it should be, and fixing it costs nothing.

The strongest thing this project has. One stick of RAM instead of two, on
identical hardware, at 1080p:

| Game | 1 channel | 2 channels | |
|---|---|---|---|
| Baldur's Gate 3 | 65 | 95 | **+46%** |
| Total War Warhammer III | 83 | 117 | **+41%** |
| Counter-Strike 2 | 336 | 469 | **+40%** |
| Cyberpunk 2077 | 90 | 113 | **+26%** |
| Fortnite | 168 | 196 | **+17%** |

It works because it is *actionable, free, and surprising* — the three things
that make somebody send a post to a friend. And it has a natural series behind
it: memory profile never enabled, card in the wrong PCIe slot, monitor plugged
into the motherboard, power plan on Power Saver, laptop running on the
integrated chip. Every one is a real fault the health engine already detects.

**Cadence:** weekly. There are at least eight of these.
**Asset:** `images/silent-tax-memory-channels.png` — first of the series.

---

## 2 · Myth-busting with receipts

**The hook:** twice the VRAM, zero extra frames.

| Resolution | 8GB | 16GB | |
|---|---|---|---|
| 1080p | 90 | 90 | **0%** |
| 1440p | 61 | 61 | **0%** |
| 2160p | 24 | 30 | **+25%** |

Identical at 1080p and 1440p. The 16GB card earns its money at 4K and nowhere
else. This is comment bait in the good sense — people will argue, and the reply
is a number rather than an opinion.

The queue behind it: "more cores is always better" (it is not — see pillar 3),
"you need 32GB" , "PCIe 5 matters for gaming", "the X3D chip is always worth
it" (it is worth +67% in Baldur's Gate 3 and +4% in Fortnite).

**Cadence:** weekly.
**Asset:** `images/myth-vram.png`

---

## 3 · Does your CPU actually matter?

**The hook:** the same four processors are worth +67% in one game and +4% in another.

Already built, and the strongest *argument* of the seven — the two-card swipe
where the same ladder on a 4090 turns +10% into +75% is a genuine
"oh" moment. The thesis is one line: **your CPU matters exactly as much as your
GPU is not the limit.**

**Cadence:** fortnightly, rotating the card and the resolution.
**Assets:** `images/bottleneck-1-*.png`, `bottleneck-2-*.png`

---

## 4 · Is your old card still good?

**The hook:** a 2017 card still does 66fps in Cyberpunk.

| Card | Year | CS2 | Fortnite | Cyberpunk |
|---|---|---|---|---|
| GeForce GTX 970 | 2014 | 246 | 60 | won't run |
| GeForce GTX 1060 | 2016 | 251 | 70 | 34 |
| Radeon RX 580 | 2017 | 287 | 83 | 41 |
| GeForce GTX 1080 Ti | 2017 | 359 | 125 | 66 |

Nostalgia plus genuine search intent — "is a 1060 still good in 2026" is typed
into search boxes constantly. The GeForce GTX 970 not running Cyberpunk at all is
the emotional beat: there is a line, and this is where it falls.

**Cadence:** fortnightly, one card family at a time.
**Asset:** `images/still-good-old-cards.png`

---

## 5 · Am I being ripped off?

**The hook:** this prebuilt costs £X. The parts cost £Y.

The highest-engagement format in this niche and the one not yet built. Take a
real prebuilt or marketplace listing, price the parts, estimate the
performance, and say plainly whether it is fair. It writes itself weekly and
never runs out of material.

**What it needs first:** the price coverage is 64 of 279 graphics cards. This
pillar is only as good as that number, which is the argument for building the
paste-a-listing parser next.

**Cadence:** weekly, once prices are better.
**Status:** not built.

---

## 6 · Run it on your own PC

**The hook:** find out which graphics card is *actually* rendering, in thirty
seconds, with nothing to install.

The participation loop. Somebody runs the browser benchmark, gets a card of
their own results, posts it. The most common finding — a laptop rendering on
its integrated chip while the discrete card idles — is exactly the kind of
personal revelation people share.

This is the only pillar where the numbers are measured rather than modelled,
which makes it the most defensible thing on the account.

**Cadence:** monthly as a prompt; continuous as user-generated content.
**Status:** built. The result card ships in the app.

---

## 7 · Receipts on ourselves

**The hook:** my tool says it is 12% accurate. Here is why I do not believe it.

The differentiator. Publishing the model's own weaknesses — that its validation
set is recalled rather than measured, that its reported error is
self-consistency and not accuracy, and eventually where real measurements prove
it wrong.

It will get the least raw engagement of the seven and it is why anyone trusts
the other six. Do not drop it when the numbers look flat.

**Cadence:** monthly.

---

## A four-week rotation

| | Mon | Wed | Fri |
|---|---|---|---|
| **1** | Silent tax: memory channels | Myth: VRAM | Build: the £1,089 one |
| **2** | Silent tax: memory profile | CPU bottleneck (2-card swipe) | Still good: the 10-series |
| **3** | Silent tax: wrong PCIe slot | Myth: "you need 32GB" | Ripped off? prebuilt teardown |
| **4** | Run it yourself + result card | Myth: X3D is always worth it | Receipts: what the model got wrong |

Stories on the off days: single-stat hooks pulled from whatever went out that
week. Every post carries "modelled, not measured" in the body, not a footnote —
it is the reason to follow the account rather than a disclaimer on it.

---

## What every card is not allowed to do

One rule, and it is the whole brand: **no invented numbers.** Every figure on
every card traces to `builds.json`, `bottleneck.json` or `pillars.json`, each
generated by running the app. If a post needs a number the model cannot
produce, the post does not run.
