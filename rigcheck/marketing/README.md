# Marketing content

Generated from the app, not written by hand. Every figure in the posts comes
from `builds.json` or `bottleneck.json`, both produced by running the project's
own planner and estimator.

- `blog-four-builds.md` — four builds by budget, from the planner
- `blog-cpu-bottleneck.md` — what four processors are worth, per game, on two cards
- `instagram.md` — six posts and three reel hooks covering both

## Regenerating

Run both from the `rigcheck/` directory:

```
npx tsx marketing/scripts/plans.ts       # the planner → builds.json
npx tsx marketing/scripts/bottleneck.ts  # the estimator → bottleneck.json
node marketing/scripts/cards.mjs         # re-renders images/ from both
```

`cards.mjs` needs a Chromium binary; it uses the one Playwright ships with.

Then re-run the two generator snippets to rebuild the prose. **Do not hand-edit
the figures** — the whole point is that they trace back to the model, and a
number typed in by hand is exactly the thing this project exists not to do.

## The claim these are allowed to make

The frame rates are modelled, not measured: derived from part specifications and
calibrated against per-game reference figures, not captured on a real machine.
The validation set behind them is itself recalled rather than measured, so the
model's reported ~12% median error is a self-consistency figure, not accuracy.

Every piece of content here says so in its own body copy rather than in a
footnote. That is deliberate: the honesty is the differentiator, and burying it
would remove the only thing that makes this different from the guides that quote
frame rates with no provenance at all.
