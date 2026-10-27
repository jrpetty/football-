# Policy registry

The registry decides **when the coach may say anything** in a given game and
mode. Screen capture working is never treated as permission.

- Source of truth: [`src/policy/registry.json`](src/policy/registry.json),
  validated at load (`src/core/policy/registry.ts`).
- It is shown in the app under **Policy & privacy**.

> This is a maintained record, not legal advice. **Every entry below must be
> reviewed by a person before release.** The build environment could not
> fetch some publisher pages directly, and several entries are Sightline's
> own conservative judgement rather than publisher statements.

## Statuses

| Status | During play | Round breaks | After the match |
| --- | --- | --- | --- |
| `approved_for_scope` | only if `scope` includes `live` | only if `scope` includes `round_break` | only if `scope` includes `post_match` |
| `noncompetitive_only` | yes, **only** in a mode declared offline, single-player and non-competitive | same | yes |
| `post_match_only` | no | no | yes |
| `needs_publisher_review` | no | no | yes |
| `disabled` | no | no | no |

A status only sets the ceiling. Within it, the player's coaching mode applies:
Competitive-safe never gives live tips, and Training is the only mode that
can. The governor (`src/core/policy/governor.ts`) then decides each message
on four further checks:
- **category:** descriptive, reflective, post-event, training or live
  tactical;
- **delivery window:** during play, round break, post-match or idle;
- **evidence grade and confidence;**
- **pacing:** cooldown, repetition and the per-minute cap.

## Fail-closed rules

1. **Unknown game or mode → `needs_publisher_review`.** The coach gives a
   post-match review only. The one exception is a mode the player declares
   offline and single-player, which matches the `default/offline-single-player`
   rule below.
2. **Stale review → `needs_publisher_review`.** A review counts as stale when
   `reviewedAt` is older than `reviewMaxAgeDays` (180).
3. **Tournament switch on → `disabled`** for every game. Event rules can be
   stricter than publisher rules.
4. **"Not sure" which mode → treated as competitive.**
5. **Precedence:** the exact mode of the game, then an offline single-player
   class rule for that game, then the game's `*` entry, then the cross-game
   offline single-player rule.

These rules have tests in `test/policy.test.ts`, including an exhaustive
1,000-case matrix asserting that nothing is ever delivered outside the policy
scope or the chosen mode.

## Entries (reviewed 2026-10-27)

| Entry | Status | Basis | Source and verification | Still needs |
| --- | --- | --- | --- | --- |
| `valorant/all` (every mode, including the practice range) | `post_match_only` | Publisher | Riot's third-party application policy (support.riotgames.com). It lists as unacceptable apps that "draw conclusions for you during gameplay". **Seen as a search excerpt; the page could not be fetched directly.** | A person to confirm the current wording. **No live tactical coaching for VALORANT in any mode.** |
| `league-of-legends/all` | `post_match_only` | Publisher | The same Riot list on the League support pages. **Search excerpt.** | Human confirmation. No pack ships for this title; the generic pack would run post-match only. |
| `ea-fc/offline-vs-cpu` (Kick-Off vs CPU, Career Mode) | `noncompetitive_only` | **Internal** | No EA statement found. This is Sightline's judgement that tips in offline play against the CPU cannot disadvantage another player. | Product/legal sign-off; ideally EA's view. |
| `ea-fc/all-other-modes` (Ultimate Team including Squad Battles, Rivals/Champions, Seasons, Clubs, friendlies) | `needs_publisher_review` | Publisher (absence) | EA's rules prohibit "specialized software" giving an unfair advantage. No statement on screen-only coaching tools was found. **Search excerpt.** | EA's position. Until then, post-match only. |
| `aoe2-de/offline-vs-ai` (campaign, skirmish vs AI) | `noncompetitive_only` | **Internal** | No Microsoft/World's Edge statement found. Sightline's judgement. | Product/legal sign-off. |
| `aoe2-de/all-other-modes` (ranked 1v1, ranked team, unranked lobbies) | `needs_publisher_review` | Publisher (absence) | World's Edge works with Capture Age, a spectator tool. No statement on player-side coaching tools was found. **Search excerpt.** | World's Edge's position. Until then, post-match only. |
| `synthetic-arena/practice` | `approved_for_scope` (live, round break, post-match) | Internal | A fictional game; Sightline is its "publisher". | None. It exists to demonstrate live tips. |
| `synthetic-arena/ranked` | `approved_for_scope` (round break, post-match) | Internal | Fictional. | None. It demonstrates a competitive scope. |
| `synthetic-arena/casual` | `needs_publisher_review` | Internal | Fictional. | None. It demonstrates fail-closed behaviour. |
| `default/offline-single-player` (any game, mode declared offline single-player) | `noncompetitive_only` | **Internal** | Sightline's judgement. A game-specific entry always takes precedence (for example, VALORANT's practice range stays post-match only). | Product/legal sign-off. |

## Adding or changing an entry

1. Read the publisher's current terms, EULA, third-party application or
   anti-cheat policy, and any esports rules. Quote the exact passage in
   `sources[].excerpt`, give the URL, and set `verification` to `direct` only
   if a person read the page.
2. Choose the most conservative status the sources support. Absence of a
   prohibition is not permission: use `needs_publisher_review`.
3. Set `reviewedAt` and `reviewer`. The registry validator rejects:
   - an `approved_for_scope` entry without a `scope`;
   - a `publisher` basis without sources;
   - an `internal` basis without a `rationale`;
   - live approval for a real game that rests on an internal decision rather
     than publisher material.
4. Run `npm test`.
