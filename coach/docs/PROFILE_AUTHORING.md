# Writing a game profile

A profile is a JSON file that teaches Sightline one game's visible HUD:
- where elements are;
- how to read them;
- which transitions count as events;
- which situations are worth learning from;
- what each game mode allows.

The reference example is [`src/profiles/synthetic-arena.json`](../src/profiles/synthetic-arena.json).

Validate with:

```bash
npm run profile:check -- path/to/profile.json
```

Install by putting the file in the app's profiles folder (Voice & settings →
Game profiles → *Open profiles folder*), then restart. Invalid profiles are
listed with their errors and are not loaded.

## Ground rules

- **Describe only what is visible.** If a fact needs game memory, network
  data or off-screen information, it does not belong in a profile.
- **Record the patch.** `game.patch` is the patch the regions were checked
  against. Bump `version`'s major number when a patch changes the HUD. Saved
  tendencies from an older major version are not mixed in.
- **Be honest in `validation` and `limitations`.** `reference` means
  illustrative, `community` means unreviewed, and `verified` means checked
  against the current patch at the resolutions in `capture.notes`.
- **Never mark a mode `approved` without a source.** See `liveCoaching` below.

## Structure

| Field | Purpose |
| --- | --- |
| `capture.aspectRatio` | Expected capture aspect (e.g. `1.7778`). Sightline warns when the capture differs by more than 3%. |
| `modes[]` | Game modes: `multiplayer`, `competitive`, `liveCoaching.status` (`not-reviewed` / `prohibited` / `approved`, plus `source` and `reviewedAt` when approved). |
| `signals[]` | HUD readings. Each has a normalised `region` (`x`, `y`, `w`, `h` in 0–1 of the captured image). |
| `events[]` | Rules that turn signal transitions into events. `category`: `context`, `action` (something the player visibly did), `outcome`, or `boundary` (`round-start`, `round-end` + `result`, `match-end`). |
| `episodes[]` | Situations to learn from: a `trigger` event, candidate `responses`, `negativeOutcomes`, a `windowMs`, the `visibility` signals that must stay readable, and wording `phrases`. |
| `liveCues[]` | Optional live tips, used only in Training mode for live-eligible modes, and only when the player's own evidence for the `recommend`ed response is moderate or better. `imperative` is 6 words or fewer. |
| `limitations[]` | Shown to players. List what the profile cannot see. |

### Signal kinds

- **`bar`**: health, shield, ammo or cooldown bars.
  - Set `fill` (HSV range of the filled part).
  - Strongly recommended: set `track` (HSV range of the empty part). Without
    it, an empty bar cannot be told apart from a hidden one, so a 0% reading
    is never trusted.
  - `axis`, `fillFrom`, and `smoothing` (median window) complete the spec.
  - Regions may include a few pixels of margin; margins are trimmed.
- **`coverage`**: the fraction of pixels within an HSV `color` range. Use it
  for banners, flashes, icons with a distinctive colour, and ready/used
  ability states. `on` and `off` form a hysteresis band. Banners with text
  rarely exceed 70–80% coverage, so start around `on: 0.4`, `off: 0.2`.
- **`signature`**: Pearson correlation against a small grayscale `template`
  (`cols` × `rows`, values 0–255) for icons that share colours with the
  scene. `on` and `off` are correlation levels between −1 and 1.

HSV: hue is in degrees (a range like `[350, 10]` wraps through red);
saturation and value are 0–1. Sample real captures at several brightness and
HDR settings. HDR and colour filters shift values.

### Event rules

| `type` | Fires when |
| --- | --- |
| `drop` / `rise` | the value changes by at least `delta` within `withinMs` |
| `below` / `above` | the value crosses `value`. It re-arms only after passing `rearm`, and only after a safe value was seen first, so "already low at start" is not an event |
| `becomes-true` / `becomes-false` | a boolean flips and holds for `minHoldMs`. The first sighting is not a transition |

Every rule has a `cooldownMs` (default 500). Nothing fires while a signal is
unknown. A transition seen across a gap longer than 2 s is reported with
reduced confidence.

## A checklist before sharing a profile

1. `npm run profile:check` passes.
2. With the game running, Coach → *Check HUD alignment* shows every box on its
   element at the resolutions you list in `capture.notes`.
3. A practice session's Session review shows the expected events, with no
   repeats, missed boundaries, or events firing in menus.
4. `limitations` lists what is not observed.
5. Every `approved` mode cites the publisher's policy URL and the date it was
   reviewed.
