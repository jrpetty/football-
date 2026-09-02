# Marketing content

Generated from the app, not written by hand. Every figure in the posts comes
from `builds.json`, `bottleneck.json` or `pillars.json`, each produced by
running the project's own planner and estimator.

- `blog-four-builds.md` — four builds by budget, from the planner
- `blog-cpu-bottleneck.md` — what four processors are worth, per game, on two cards
- `pillars.md` — the seven recurring formats, why each one, and a four-week rotation
- `instagram.md` — nine posts and three reel hooks across the pillars

## Regenerating

Run both from the `rigcheck/` directory:

```
npx tsx marketing/scripts/plans.ts       # the planner → builds.json
npx tsx marketing/scripts/bottleneck.ts  # the estimator → bottleneck.json
npx tsx marketing/scripts/pillars.ts     # the estimator → pillars.json
npm run marketing:cards                  # re-renders images/ from all three
npm run marketing:verify                 # fact-checks the prose against them
```

`cards.mjs` needs a Chromium binary; it uses the one Playwright ships with.
`images/` is gitignored — it is generated, so it is never committed, and it has
to be re-rendered after any data change. What renders, and the form each one
takes — chosen by the data's job, never a table:

| Image | Form |
|---|---|
| `01-cover` | price → frame-rate curve, knee computed and marked |
| `02-700` … `05-2600` | six games as bars against the refresh target, 1% low as a tick |
| `bottleneck-fan-out` | two slope panels indexed to the slowest chip — bunched vs fanned |
| `bottleneck-1-*`, `-2-*` | the same slope chart, one card per panel, for the swipe |
| `silent-tax-memory-channels` | dumbbell per game, indexed so bar length is the gain |
| `myth-vram` | paired columns, one hue in two shades; identical pairs labelled once |
| `still-good-old-cards` | three small multiples with a 60fps rule |
| `hero-price-step`, `hero-cpu-split`, `hero-gtx-970` | one derived figure, one line |

Every figure on every card arrives through a data expression; `verify.mjs`
walks the renderer's templates and fails on a typed-in number with a unit.

Every card also prints a one-sentence **What this shows** strip, derived from
the data, and the renderer writes those sentences to `cards.json`. Each image
must appear in `instagram.md` on a line that names the file and repeats that
sentence after *shows:* — `verify.mjs` fails on an image with no such line, a
line pointing at an image the renderer does not make, or a sentence that
differs. An image never goes out without its caption, and the caption never
describes a different image.

**Do not hand-edit the figures** — the whole point is that they trace back to
the model, and a number typed in by hand is exactly the thing this project
exists not to do.

## Fact-checking — `npm run marketing:verify`

Nothing enforced that rule for a while, and an audit found eight things wrong
in already-published copy. None of them looked wrong:

| What was published | What the data said |
|---|---|
| "Four old cards" above a list of three | the fourth, an RX 580, was in the data and on the image |
| a GTX 970 "just refused" to run Cyberpunk | it runs it at 21fps — the gate was treating a *published minimum spec* as a capability check |
| "the GTX 970's 3.5GB" (on the image) | the same image printed 4GB two inches above it |
| "~12% median error" | correct when written (11.7%); the VRAM fix below moved it to 11.0% |
| "260W, 400W supply" | the parts list under it contained a 550W PSU |
| "everything except Cyberpunk clears 165fps" | three of six games do not |
| "every single number went up" | six of twenty-four did not |
| a 5600 "within a few frames" of the best chip in shooters | one frame in Fortnite, twenty-one in CS2 |

Every one reads fine, and the accuracy figure was *right when it was written* —
fixing the VRAM gate moved it, and nothing connected the two. That is the whole
problem: prose goes stale silently as the model behind it improves, and only a
machine notices. So `verify.mjs`
checks three things — that the committed JSON still regenerates identically,
that every fps figure in a markdown table exists in that JSON, and that each
quantified prose claim in its register still holds. A claim fails if its quote
has drifted *or* if the data stopped supporting it; both are the same bug.

**Adding a claim to a post means adding it to the register in `verify.mjs`.**
A sentence that asserts a number and is not in that file is unverified, and
unverified is how all eight of the above shipped.

## The claim these are allowed to make

The frame rates are modelled, not measured: derived from part specifications and
calibrated against per-game reference figures, not captured on a real machine.
The validation set behind them is itself recalled rather than measured, so the
model's reported ~11% median error is a self-consistency figure, not accuracy.

Every piece of content here says so in its own body copy rather than in a
footnote. That is deliberate: the honesty is the differentiator, and burying it
would remove the only thing that makes this different from the guides that quote
frame rates with no provenance at all.

## Posting every day without it becoming a job

The renderer is a library (`scripts/cardlib.mjs`): every template takes the
object it draws, in either format — a 1080×1350 post or a 1080×1920 story from
the same HTML, the story stacking any side-by-side panels. Anything can call
it with any data:

```
npm run marketing:build -- --budget 900 --resolution 1440p   # one build, both formats, caption
npm run marketing:versus -- nvidia-geforce-rtx-4070 amd-radeon-rx-9070
npm run game -- request "Helldivers 2" --votes 3             # the queue the poll card reads
npm run marketing:calendar                                   # four weeks into calendar/<date>/
npm run marketing:next                                       # today's folder → drop/, caption printed
```

**The calendar.** `rotation.json` is a week template: Monday a build of the
week at a rotating budget, Tuesday and Thursday and Friday existing posts in
rotation, Wednesday a versus pair, Saturday the which-game-next poll, Sunday a
story. `marketing:calendar` expands it into dated folders, each with the
image(s), the story image(s), `caption.txt` and `meta.json`. Existing posts are
copied from the rendered set; builds, versus and the poll are generated against
the data as it is that day. Every folder is checked — images present, the
caption carrying the sentence printed on the card, every `· Game — Nfps` line
matching the folder's own `data.json`, no `undefined` anywhere — and the
caption verifier runs last. The folders are generated and ignored by git;
`rotation.json` and `posted.json` beside them are committed.

**The daily two minutes.** `marketing:next` picks the next unposted day
(today, or the earliest overdue), regenerates just that day against current
data, re-checks it, copies it to `drop/<date>/`, prints the caption and
records the date. Upload the folder, paste the caption. Nothing here talks to
Instagram; that is a deliberate boundary until there is an account and an app
to talk to.

**Versus.** Any two parts of one kind on identical partners — the same
processor for two cards, the same card for two processors, at 1080p so the card
is out of the way. "Ahead by N% on average" is a geometric mean of per-game
ratios, which treats +50% and −33% as the same distance; an arithmetic mean of
percentages does not.

**Game requests.** `data/catalogue/requests.json` is the queue. `npm run game`
adds, votes, lists, scaffolds and promotes. A scaffold is the full game record
with a null everywhere a value must go and a checklist saying what each one
means; `promote` refuses anything still null and refuses a game with no
reference figures, because the engine would answer NO_ESTIMATE for it. Adding a
game is data entry, and the tooling makes that literally true.
