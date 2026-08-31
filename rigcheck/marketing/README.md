# Marketing content

Generated from the app, not written by hand. Every figure in `blog-four-builds.md`
and `instagram.md` comes from `builds.json`, which is produced by running the
project's own planner and estimator.

## Regenerating

Run both from the `rigcheck/` directory:

```
npx tsx marketing/scripts/plans.ts   # re-runs the planner → builds.json
node marketing/scripts/cards.mjs     # re-renders images/ from builds.json
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
