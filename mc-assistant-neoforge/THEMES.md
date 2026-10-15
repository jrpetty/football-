# Themes: a town's look, as data

A theme is everything a town's buildings are made of. The folk, their jobs and their AI
are the same in every theme: an alien town is a new theme file (and, if it wants, its own
drawings of some buildings), and nothing else.

- The mod's own: `src/main/resources/data/mc_assistant/themes/medieval.json` (the folk's
  timber, stone and slate; every town's unless told otherwise).
- A datapack's: `data/<namespace>/themes/<id>.json`. A file with the same id as the mod's
  replaces it; a new id is a new theme (a namespace other than `mc_assistant` is prefixed:
  `mypack.alien`).
- A worked example, for the tests only: `src/gametest/resources/data/mc_assistant/themes/styles_test.json`
  (end stone and purpur, its own house drawing, its own Iron Age rule).

Which theme a town builds in: the config's `villageTheme` for every town, or one town's
own, given by an operator with `/village theme set <id>` (`/village theme` lists them).
A town's older buildings are renovated to its new theme by its own builders.

## The file

JSON, with `//` line comments allowed. Everything is optional if the theme has a
`parent`: what it does not say is its parent's, section by section (the sections that are
maps, such as `lands`, `parts`, `styles`, are merged key by key). Block and item ids
without a namespace are `minecraft:`; `#name` is a tag; `*` matches any run of letters
(`*quartz*`).

```json
{
  "parent": "medieval",            // optional: inherit everything not said here
  "name": "Alien",                 // shown to the player
  "about": "glowing spires",       // one line, for /village theme
  "selectable": true,              // offered to the towns (false for a test theme)
  "thatch": false,                 // thatch first on Wood Age roofs while there is wheat to spare

  "woods": ["oak", "spruce"],      // the woods, best known first (see parts: woods(...))
  "dark_woods": ["dark_oak"],      // put early on a roof's or a frame's list

  "sets": {"great": ["hall", "chapel"], "homes": ["house", "house2"]},   // named sets the rules use

  "lands": {                        // a town's look by its land (Homeland.Land); "default" for the rest
    "default": {"walls": "oak", "roof": "dark_oak", "floor": "spruce", "frame": "spruce",
                "footing": ["cobblestone", "stone"], "dressed": ["stone_bricks"]}
  },
  "land_stone": {"DESERT": {"block": "cut_sandstone", "pay": ["sandstone", "cut_sandstone"], "each": 1}},

  "parts": { ... },                // what a builder reaches for, by design letter's purpose
  "ages": { ... },                 // what each age makes of a building's blocks
  "styles": { ... },               // each building style's woods, dressing and street furniture
  "street": {"lamp_post": "spruce_fence", "light": "lantern"},
  "palettes": { ... },             // the blocks of a storey put on at once (civic, house_stone, house_brick)
  "materials": { ... }             // never-blocks, rough fill, and the stand-ins age by age
}
```

### parts: the design letters

A design (`blueprints/*.txt`) marks each block with a letter that says what it is FOR, not
what it is made of; each letter's purpose is a `Blueprints.Style` (`W` is `WALL`, `f`
`FLOOR`, `^ v < >` `ROOF_STAIR`, `n u { }` `ROOF_STAIR_TOP`, `_` `ROOF_SLAB`, `=`
`ROOF_SLAB_TOP`, `R` `ROOF_BLOCK`, `L` `POST`, `-` `BEAM_ACROSS`, `|` `BEAM_ALONG`, `F`
`FOUNDATION`, `w` `WALL_LOW`, `S` `MASONRY`, `Z` `BRICK`, `s z` `STONE_SLAB`, `k`
`STONE_STAIR`, `d` `SOIL`, `#` `GENERIC`; the legend is in `Blueprints.java`). `parts`
maps each purpose to a list of lines, best first:

- an id, with the town's look put in for `{walls}`, `{roof}`, `{floor}`, `{frame}`:
  `"{walls}_planks"`, `"bamboo_planks"`;
- `woods(F):pattern`: the look's wood `F` first, then the theme's `woods`, each put in for `{}`;
- `darkwoods(F):pattern`: the same with the `dark_woods` put early;
- `list(F):pattern`: each of the look's list `F` (`footing`, `dressed`).

A builder takes the first of these it has; short of all of them it goes on to the
materials' stand-ins (below). The fittings and the furniture are not part of a theme: the
letters for doors (`D`, `Y`), ladders (`H` against the back wall, `J` with the wall on its
left, `i` with the wall on its right), beds, chests, workstations, glass and lights are
placed exactly as the legend in `Blueprints.java` says, the same things in every town, so
the folk always find a way in, a way up and a bed. A theme changes how they look by drawing
its own design (below), not by renaming them.

### ages: the make-over

For each purpose, a list of rules; the first that holds says what a block of the building
becomes in the town's age (no rule: it is left as it is). A rule's conditions, all of
which must hold:

| key | holds when |
|---|---|
| `age_min`, `age_max`, `age` | the town's age (`WOOD`, `STONE`, `IRON`, `DIAMOND`, `NETHER`) is at least / at most / exactly this |
| `structures`, `not_structures` | the building is (not) one of these (a list, or `"@great"` for a named set) |
| `home` | the building is (`true`) or is not (`false`) one of the set `homes` |
| `now`, `now_not` | the block standing there is (not) one of these (ids, `#tags`) |
| `chance` | one place in N, fixed by its position (moss in a few footings) |
| `lands` | the town's land is one of these (`PLAINS`, `FOREST`, `DESERT`...); an age's rule only, and only for a real town |
| `town_mod` | `[n, k...]`: the town is one of these picks out of n, the same for every block of the town (so one lowland town roofs in red tile and its neighbour in slate); an age's rule only, and only for a real town |

and what it becomes, `to`: an id; `like:id` (set the way the block standing there is set:
a stair's facing, a slab's half); `axis_run:id`, `axis_y:id` (a log along the wall, or
standing); `$land_stone` (the land's own walling, if the stores can pay for it); several
of these separated by `|` (the first that is a block). Example: Iron Age slate over any
wooden roof stair: `{"age_min": "IRON", "now": ["#wooden_stairs"], "to": "like:deepslate_tile_stairs"}`.

### styles: the six building styles

The town's AI chooses one of six building styles (`TIMBERED`, `HILL_FORT`, `COASTAL`,
`DESERT_COURT`, `FOREST_LODGE`, `GRAND_CIVIC`) from its land and its temper; a theme says
what each looks like:

- `look`: the woods it overrides (`walls`, `roof`, `floor`, `frame`);
- `dress`: rules (as `ages`, plus `band` (a wall course under the eaves or a window) and
  `side` (a side window low down), and `what`, the words for the work) for the kinds of
  place `WALL`, `POST`, `ROOF_STAIR`, `ROOF_SLAB`, `MASONRY`, `WINDOW`; `{trim}` is the
  town's colour (`like:{trim}_stained_glass_pane`);
- `ornaments`: `shutters` (a trapdoor), `banners` (true), `battlements` (`{"block", "age_min"}`),
  `by_door` (`{"kind": "barrel" | "pot" | "sign", "block", "what"}`), `chimney_fire` (a block);
- `lamp_post` (`{"block", "pay": [...], "cost"}`), `bench` (a list of `{"block", "pay"}`, tried in order);
- `keep_roof` (its homes keep their roofs through the ages), `boxes_everywhere` (flower
  boxes under every house's windows).

### materials

```json
"materials": {
  "forbidden": ["diamond_block", "*quartz*", "netherrack"],   // never building material (unless the theme names it)
  "rough": ["dirt", "gravel", "sand", "#wool"],               // not in a wall, floor or roof after rough_until
  "rough_until": "STONE",
  "groups": {"walls": ["WALL", "POST"], "floors": ["FLOOR"], "roofs": ["ROOF_STAIR"]},
  "fallback": {"walls": {"WOOD": ["#planks", "cobblestone"], "IRON": ["stone_bricks", "cobblestone", "stone", "#planks"]}}
}
```

A block the theme names anywhere (its parts for any land, its rules, its land stones,
palettes and street) is always its material, whatever `forbidden` says: an alien theme of
end stone and purpur names them, and builds with them. A builder short of the town's own
block goes down the group's `fallback` for the town's age (the latest age listed at or
before it), and past it waits for the stores; until `rough_until` is past it may still use
any proper block it has. A part in no group (the earth of a flower box) is held only to
`forbidden`.

## The theme's own designs

A theme may draw any design its own way: `data/mc_assistant/blueprints/<theme>/<design>.txt`
(`blueprints/alien/house.txt`), in the usual format. Its towns build that in place of the
usual drawing; a design it does not draw is the usual one (or its parent's). Keep the same
building's furniture (beds, chests, doors, the workstation) so the folk find what they
need; `BlueprintSoundnessTest` checks the usual drawings, and an override should pass the
same checks.

## Adding a theme, step by step

1. Copy `medieval.json` to `themes/<id>.json` (or start a small file with `"parent": "medieval"`).
2. Change `name`, `about`, `selectable`.
3. Write `parts` for every purpose your designs use, `lands` and `land_stone` if the land
   matters, `ages` for what each age makes of it (empty lists `[]` for none), `styles`
   for the six building styles (an empty `look` keeps the base look), `street`,
   `palettes` and `materials`.
4. Optionally draw `blueprints/<id>/<design>.txt` for the buildings that should look different.
5. Load the world, `/village theme` to see it on offer, `/village theme set <id>` near a
   town; its builders reach for your blocks at once and renovate the older buildings.
6. A test like StylesGameTests st03 proves it end to end.
