# Village Folk

A settlement that runs itself. Nobody hires them, points them at ground, or gives
them a trade: they choose a trade the village is short of, stake ground for it,
work it, stash what they make, eat, sleep, build the village up and — if they are
fed and in work — raise children.

Everything is obtainable in **survival**.

## Getting a village

| How | What you do |
|---|---|
| **Village Folk Spawner** (recommended) | Craft it — 8 bread round a gold ingot — and *place* it. The first one puts the village board up on the spot; go to the board and choose how many folk start the village, from two to five hundred (see *Founding a village: choose how many*). The ground round about is made level for them, and they come, with the founding stores in a chest where the spawner stood. Each one placed after that, within reach of the village, adds one settler. That is the last thing a village needs from you. |
| **Village Charter** | Right-click the ground. Founds a town of **seventy** there (config `villageCharterFolk`; use a spawner and its board to choose any other number): its ground is made level first, as for a spawner, and the folk come when the heart of it is level, each with a bed at the camp and the town's treasury opened with their savings (four coins a head). Craftable with paper, bread, a gold ingot, seeds and a chest. |
| **Where to find them** | In creative, everything the mod adds is in its own **Village Folk** tab (the spawner is also under Functional Blocks). In survival, every recipe is in the recipe book from the moment you join; the spawner is a gold ingot in the middle of the crafting grid with bread in all eight squares round it. |
| **/village spawn [1-100]** | Out of reach of a village, founds one two blocks ahead of you, its ground made level first (at least two folk); in reach of one, stands that many more up in it. `spawnat <x> <z> [n]` for the console and scripts founds at once, levelling nothing. |
| **The world** | Villages generate as you explore (config `naturalVillages`), in groups of three to five. Each has its ground made level before its folk come, as a founding at a board does. |
| **/village level** | A town already standing (one from before its ground was levelled) has the ground round it made level now: trees cleared, hills cut, hollows filled, nothing built touched, and its folk kept clear of the moving ground. Operators, or the world's owner. |
| **Vanilla villagers** | Turned into folk as you meet them (config `replaceVillagers`). Trading with them stops working; wandering traders are untouched. |

## Founding a village: choose how many

**Place the spawner, then go to the board.** A Village Folk Spawner set down where there is no
village puts the **village board** up on the spot, on the edge of what will be the square and on
the far side of it from you, so it faces you. Nobody comes yet. The board says what is about to
happen, and chat says: *Go to the village board to choose how many folk start the village.*

**Right-click the board** and the *Found a village* screen opens:

* **How many**: a slider from 2 to 500 (it moves by ratio, so two to twelve is not squeezed into
  the first pixel), a − and a + beside it (with Shift, ten at a time), and the usual sizes to pick
  at a click: 2, 12, 25, 50, 70, 100, 250, 500. It starts at **seventy** (`villageCharterFolk`, which
  a server may set anywhere from 2 to 500). A server may found a village with at most
  `villageFoundingMost` folk (500 unless it says fewer), and when that is lower than five hundred
  the screen says so and goes no higher. (That is not the growth cap: `villageGrowthCap` is how big a
  village grows by raising children, and a founding may be bigger; it then raises none until it is
  smaller.)
* **What that means**, worked out from the count as you move it: how much ground is made level,
  the trades that many settle into (the biggest first), the houses they will want before they
  raise children and how many beds the camp has, and what so many folk cost a server every tick,
  amber from about a hundred and red from three hundred (*hundreds of folk are heavy for a
  server: every one is a ticking creature*). If the world would hold more folk than it may
  (`villageWorldCap`), it says that too: past it no village raises a child.
* **Confirm and spawn** starts the founding. **Cancel** only closes the screen; the board goes on
  waiting. The server checks the choice again: you must be standing by the board (24 blocks), the
  board must still be waiting, and the count must be 2 to 500; a count over `villageFoundingMost` is
  brought down to it, and you are told.

To call it off, take the board down: you get your spawner back. A board left waiting is still
waiting after a restart.

**The ground is made level first.** A square with rounded corners round the spot, forty percent
wider than the town those folk will build strictly needs (the homes they will want, and how far
out the town's plan has to run to hold them), so it has level room to grow into:

| Folk | Ground made level | Level before anybody comes |
|---|---|---|
| 2 to 8 | about 85 by 85 blocks | 16 blocks round the heart |
| 12 | 91 by 91 | 16 |
| 25 | 99 by 99 | 16 |
| 50 | 115 by 115 | 16 |
| 100 | 139 by 139 | 17 |
| 250 | 189 by 189 | 23 |
| 500 | 251 by 251: the whole of the town's plan, all three rings, and more | 31 |

* **Inside**, everything is brought to one level: the middle height of the dry land there (by
  water a little under the sea's height, lifted just out of it, so a town by the shore does not
  stand in a pit behind its banks; dry ground well under the sea's height keeps its own; and an
  island, or a shore where water covers two fifths or more of the square, is brought down to a block
  over the water, so its edge is a step down to it and not a cliff as high as the hill was, unless
  that would cut more than sixteen blocks off it). Hills and knolls are cut
  down, hollows filled, trees, plants and snow cleared, and the ground is dressed in the land's
  own soil: grass on the plains, sand in the desert, podzol in the pine woods (grass where it was
  all rock: the folk are going to farm it), with earth under a cut and sandstone under deep sand.
  Ponds are filled. Rivers, lakes and the sea are left exactly as they are, and the land beside
  them is never cut below the water. **The square itself is flat to its edge whatever water stands
  about it**: beside a lake or river lower down it ends in a quay, the slope down to the water
  lying out past the square; a stream or tarn higher up is held in by its own bank, a block wide,
  the square cut flat up to it (out past the square the bank slopes back into the hillside); and
  water above the level inside the square (a stream down the hillside the town is cut into) is let
  out there and dammed by its bank where it comes in. (Once the ground was held up a block for every
  block from a stream on the hill, and let down to a lake's shore, which left terraces in the square.)
  When the levelling is done, **the square and its worked edge are looked over again, column by
  column**: any the
  levelling left off the level, hollow within five of its top (sand that slid, water that ran in,
  a cave it missed), not dressed in the town's soil, or with anything growing on it, is worked
  again, and the square looked over again while anything was mended (three times at most). Only
  then is the founding done. The server log says how flat it came out (every column measured, and
  why any is off), and `/village found ground <x> <z> <radius>` measures any ground.
  The level ground is **solid five deep**, and so is every column of its sloped edge that was cut
  or built up: its top (the town's grass, or sand or podzol; the edge keeps its own) and four
  blocks of earth under it, whatever the land had there. Caves, springs, pockets of water or lava,
  buried roots and stumps, and sand over a hollow are filled in the land's own earth (dirt under
  grass, sandstone under sand), so nothing built on it sinks and nobody steps through it; stone,
  ore and earth already there are kept, and nothing is left growing on it.
* **At the edge** the level ground meets the land as it was, and nothing is cut or built more
  steeply than a slope: rounding off from the flat over the first six blocks, a block up for every
  block across after that, a block and a half from twenty-four out. So a hill a few blocks high is
  met over eight to ten blocks, one of fifteen over about twenty, and a mountain the square cuts
  into stands back from the town on a long face rather than a cliff (up to fifty-nine blocks of
  it, forty-eight out). Wherever the land is already within that slope it is not touched at all,
  and a little noise moves the line in and out so it does not run like a ruled edge. Worked
  through on made-up ground of every kind, no step on the edge is more than two blocks, unless
  the land had one already.
* **Nothing anybody built is touched**, inside or out: a column with so much as a plank or a torch
  in it is left as it is, with two columns round it, and the ground round that held to it by the
  same slope. Nothing outside the ground being worked is touched at all.

**Then they come.** The heart is levelled first and the rest outwards; as soon as the camp ground
is level the board comes down and the first of them stands up at the heart and founds the village
there, with its founding stores, its board put up again on the same side of the square, and the
camp. The rest come a few a tick (eight), on a spiral round the heart, each on dry ground of its
own, while the outer ground is still being shaped. A bigger party brings more: a second chest of
bread, seed, saplings, torches, planks and stone for every sixteen past the first dozen (up to
six), and the bedding of those the camp has no room for (it lays a bed each for up to about eighty, in
rings round the heart, the well's and the monuments' places on the square left clear) in chests of its
own, up to two, for the first houses.

**It goes on without you.** The work is done a little every tick: at most 4,096 blocks or eight
milliseconds, 8,192 columns read while the ground is walked, eight folk. It keeps the ground it is
working loaded (six chunks asked for at a time, the rest held while it works), so you can walk
away, and it is kept with the world: after a restart it walks the ground again and carries on from
where it had got to. Anybody within 160 blocks sees how it is going on the action bar, the board
says it while it waits, and the village's own board says it once the village is founded. When it
is done, the first of them says so, and the village's history remembers it.

A spawner placed within reach of a village adds one settler, as it always has. Every other way a
village begins levels its ground the same way: a village the world founds as you come upon it,
`/village spawn` out of reach of a village, and the Village Charter all put the board up and
confirm the founding at once, and the folk come when the heart is level. Only `/village spawnat`
(the console and the soak tests) still stands a party up at once with no levelling, and a colony
sent out by its mother town is founded where it stops. The board clears whatever grew where it
stands, tree trunks too: in a jungle every place on the square's edge has one, and the board once
could not go up there at all. A town that was founded before any of this, standing among its
trees, has its ground levelled with `/village level`.

Tested in `FoundingGameTests`: on rough ground (a stone hill across the edge, a knoll and a hollow
inside, a pond, trees and somebody's hut), a spawner brings nobody until the founders are chosen,
the board keeps its waiting across a save, counts outside 2 to 500 are refused and one over the
cap brought down to it; twenty chosen, the square ends flat to a block, no step on the edge is
over two, the hut stands, and twenty folk live there. A spawner in a village adds one; the
command founds twelve. The client smoke test photographs the board, the screen and the levelled
ground from above (`smoke-17-found-*.png`).

## From the first spawner to the Nether Age

Place a spawner, say at its board how many, and walk away. Nothing below needs you:
no commands, no orders, no chests to fill, no player nearby (a village keeps its own ground loaded, and the
real-terrain tests run on a server with nobody on it). Everything happens on its
own, and each step is there for a reason the village can see.

1. **Founding.** The folk you chose at the board (eight is what it offers first) stand up
   where the spawner stood, on ground levelled for them, and found the village,
   with a chest of founding stores: bread, seed, carrots and potatoes, saplings, torches,
   planks, cobblestone, string, and the **twenty-seven units of the Village Storehouse**.
   Every folk carries rations, stone tools and a bench. Nobody carries a chest of their
   own: the village keeps its goods in one place (see *The Village Storehouse*).
2. **Trades and ground.** Each takes the trade the village is shortest of (farmers
   first, then miners, woodcutters, a smelter; a watch, a carrier, a storekeeper, a
   rancher and a fisher as it grows), stakes ground that suits it and works. What it
   makes it carries to the village's stores.
3. **The storehouse, first.** For its first half hour the founding planks and stone
   are kept for it. A builder walks to the stores, loads up, and raises the storehouse
   shed round the **Village Storehouse**, laying its twenty-seven units one by one. When
   the last one goes in they join into one store, and from then on the whole village
   keeps everything there.
4. **A shelter, then houses.** *Why:* a village has room for twelve, and every house
   is room for five more (a shelter three, the meeting hall six). Two fed folk in
   work raise a child only while there is room **and** the stores hold a day's
   meals for everybody, so the village builds a house whenever it is nearly full
   and farms ahead of its mouths. That loop (food put by → children → hands →
   materials → houses → room → children) is what makes it grow, and because
   children come out of a surplus, the larder each age asks for is there too.
5. **A well** at the middle marks the camp as a village, and the Wood Age asks for it
   with timber, food in the stores, the storehouse, the shelter and enough houses.
6. **The Stone Age:** quarry stone and coal, a **wall** round the village (it follows
   the ground and goes round the houses, fields and ponds already there), more
   houses, a **smeltery** of three furnaces, and a **meeting hall**. The miners take
   their mines down to the iron seam (height 16, where this game puts the most
   iron) to be ready for the next age.
7. **The Iron Age:** iron in the stores for the watch's armour and a smith's stock, a
   **workshop**, a **watchtower** and a **market**. Iron is kept for the age: a folk
   makes itself iron armour or iron tools only out of what the village holds beyond
   what it is saving.
8. **The Diamond Age:** every other miner has an iron pickaxe made from the stores
   and takes its mine down to the diamonds near the bottom of the world, however new
   it is to the work (the village decides how deep its mines go); twice the
   Iron Age's iron, a **lighthouse** and a **chapel**.
9. **The Nether Age:** one miner gets the village's diamond pickaxe and a bucket of
   water, and makes the obsidian the way a player does — water poured on the lava
   down at the bottom of the world, the obsidian broken out, the hole stopped — and
   the village raises an obsidian **gateway**.
   * **The gateway is lit** with flint and steel (a flint and an iron from the stores),
     and the Nether lies open.
   * **Nether parties.** Every other day, by daylight, a guard and two miners go
     through. They wait by the gateway while they are away; the Nether itself is told,
     not walked (folk on the far side of a portal would be out of reach of everything
     that keeps them working).
   * **What they bring back**, half a day later: blaze rods, nether wart, soul sand,
     quartz, glowstone and gold, sometimes magma cream or a ghast tear. The brewer
     grinds the rods into blaze powder, so its first kit is the last the village is
     given.
   * **The risk.** Now and then somebody comes back hurt, and very rarely somebody
     doesn't come back.
10. **And then it never stops.** A village that has come through every age raises
    **great works** — a granary, barracks, a monument, round and round, each on new
    ground — and every one asks the stores for a quarter more food, stone and iron
    than the last. `/village status` shows its **renown**: ten for every great work raised,
    and whatever its museum has on show (see *The museum and the archive*).
    **Rank** goes on past the ages:

    | Rank | Needs |
    |---|---|
    | Village | the Stone Age and 12 folk |
    | Town | the Iron Age and 30 folk |
    | City | the Diamond Age, renown 20 (two great works, or a museum of rare finds) and 50 folk |
    | Capital | the Nether Age, renown 60 (six great works, or fewer and a museum), 80 folk and two colonies of its own |

    A rise in rank is told to everyone, the treasury gets a purse for it, and the folk
    remember the day. `/village status` and the journal show the rank and what the
    next one asks for.
11. **Colonies.** From the Stone Age on, a village of forty sends a founding party of
    eight a couple of hundred blocks out, fed from its own larder, and they found a
    village of their own that climbs the ages from the start. The mother sends them
    properly or not yet: their storehouse is made out of her timber, their tools out of
    her stone, and her bread goes with them in their packs — at most one every two
    game days from any one village, and never past the folk the whole world may
    hold (`villageWorldCap`). Over a long game the settlements spread across the map.

A project that cannot go ahead — no ground for it, the stores cannot pay for it yet,
or a part nobody can make yet (obsidian) — is set aside for a few minutes and the
next thing on the list goes up meanwhile, so one stuck building never holds up an
age.

Each age is announced once in chat (the only thing a village ever says).
`/village status` shows what it is short of, how much room it has, whether it is
growing (and if not, what it is waiting for), and what it will build next and why.

### The town

A village is laid out as a town, to one plan, wherever it is founded:

* **The square** at the heart: 25 blocks across, walled, with a gate on each side.
  It holds the founders' camp and the stores in the middle, the well to the south and
  the monument to the north. It is paved with cobblestone from the Stone Age and with
  stone bricks from the Iron Age.
* **The ring street** runs round the outside of the wall. **Four avenues**, five wide,
  run out from the gates to the north, south, east and west. **A grid of streets**,
  three wide, crosses the land every 25 blocks.
* **The lots** are 11 blocks square, four to a block, and every one of them is on a
  street:

  | Where | What goes there | Which way it faces |
  |---|---|---|
  | North and south of the square | The storehouse, the market, the shops, the café and the tavern | Onto the square |
  | East and west of the square, two lots deep | The meeting hall, the chapel and the barracks | Onto the square |
  | The four corners by the square | The watchtowers | — |
  | Everywhere else | Homes, in rows back to back | Each row onto its own street |
  | Out at the edge | The lighthouse, the pen and the gateway | — |

The streets are worn into paths where people live. From the Iron Age the avenues
are cobbled and lit by lamp posts every few blocks. Fields, woods and mines are
staked outside the town, so it always has ground to grow into.

### The town's quarters, and the park

The plan has **quarters**, and each grows outward on its own side as the town does:

| Quarter | Where | What goes there |
|---|---|---|
| **The square** | The heart, inside the wall | The well, the fountain, the monuments, the bell tower |
| **The market quarter** | Round the square, and out toward the fields as far as the first field | The storehouse, the market, the shop, the café, the tavern, the granary, the library, the bank; the great halls and the watchtowers keep their own lots by the square |
| **The craft quarter** | One side of the town, away from the homes | The smeltery, the smithy, the workshop, the brewery, and any new trade's works (a forge, a kiln, a mill, a tannery) |
| **The farmland** | Another side, past the town's first block | The fields, as before |
| **The homes quarter** | The two sides that are left | The houses, the manor houses, the park |
| **The outskirts** | Past the last street | The lighthouse, the pen, the gateway, the graveyard |

The village chooses its craft side once and keeps it: where its smeltery already stands (so a
town founded before there were quarters carries on as it was), else toward its mines, else the
side with the fewest homes on it, next round from the fields. If the fields are later laid out
that way, the crafts move round to the next side; nothing that stands is moved or pulled down.
A building is offered a lot in its own quarter first; only when every one is taken, or no good
(a cliff, a lake), does it go on the next best: the market's trades into the homes' streets, the
crafts by the market, a home by the market. A new house is kept out of the smoke too, while there
is room elsewhere.

**Smoke and noise.** A home within sixteen blocks of a smeltery, a smithy, a workshop or a
brewery that has been at work in the last two days (its furnace lit, or its hands at it), or
within twenty of a mine's head while the miner works it, is a worse place to live. Its folk are a
little less content for it, and say why when asked how they are ("The smithy's hammering keeps me
up.", "The smeltery's smoke gets into the washing."); a grump minds it more, an easygoing folk
less, and a smith does not mind its own forge. The house sells and lets for less (85% of the
going price; a rent of three coins or more is cut to match).

**The park.** Once the town has twenty folk and builds in stone, a park goes on the builders'
list with the other amenities, on a lot in the homes quarter, the nearest the square. The builders
put it up out of the stores like anything else: a fountain of dressed stone in the middle, its
basin and the spring on its pillar filled a bucket at a time (the bucket is the stores', the
water comes from a pond or a river that never runs dry), sixteen wooden benches round it facing
the water, a lamp post at each of the four ways in, and flowers along its edges. Then its keepers
finish it, a little at a time and out of the stores: a tree in each corner from the woodcutters'
saplings (with a little bone meal now and then, if the stores can spare it), paths of trodden
earth round the fountain and out to the four ways in, whatever flowers and water the builder had
none of, and lanterns for the torches once the smith makes them. From the Iron Age the paths are
paved and the fountain's rough stone dressed, in stone bricks.

**A level lawn, and a fountain that keeps its water.** A park is a lawn, and a lawn is level. The
plan gives it the flattest lot it can (a lot on the edge of a drop counts heavily against it), and
lays the lawn at the middle height of the ground there. Before a stone of it goes down, the builder
digs away the earth above that height, from the top down, and fills the hollows under it, the top
of the fill in the earth it dug (the grass grows back over it); the high ground round the lot is
cut back to a step of one block, then two, so the park doesn't sit in a pit. Its keepers bank up
the low ground round it the same way, and turf any bare stone or sand in the lawn, with earth from
the stores. The fountain stands on a stone footing. Water goes into it only where it will stay:
there must be a floor under it and a wall on every side, and the spring on the pillar only runs
over a whole basin. If a stone of the rim or the floor is knocked out, the fountain is emptied at
once, before its water can run anywhere. Its keepers then put the stone back from the stores and
fill it again.

**Folk spend their free time there.** Some evenings (more often the ones who live by it, the
walkers and the readers, the sociable and the easygoing; less often the shy) and some breaks, a
folk walks over, sits on a bench looking at the fountain (partners and best friends side by side),
chats with whoever sits by it and likes them the better for it, then gets up and strolls the
paths, greeting whoever it passes. Couples walking out on the day of rest go round its paths.
Children run about it of an afternoon, playing tag round the fountain. A folk that spent an hour
in the park today is a little happier for it, and a home within twenty-four blocks of the park is
the happier (its folk say "I can hear the fountain from my window") and sells and lets for more
(115%). In the smoke and by the park both, it comes out about even.

**Where to see it.** A folk's card (the About tab) has a **Quarter** line: which quarter it lives
in, whether it is beside the smithy or the smeltery, whether it is by the park, and what that does
to its house's price. The line at the top of its card says when it is in the park ("Off work:
sitting on a bench in the park with Bess"). In the town's books, the **Buildings** page gives
every building's quarter (a coloured edge and a Quarter column, *smoky* or *by park* for a
house), and **Map of the quarters** at the foot of the page turns the list into a map of the town: every lot
tinted by its quarter, every building on it in its quarter's colour, the homes in the smoke ringed
in soot and the homes by the park in green, the works at work marked by their fire, the mines'
heads, and a key with how many buildings each quarter has, the plan in a line and how the park is
coming on. The **Why** page lists the homes in the smoke and din (by address, who lives there and
what troubles them) and the homes by the park; the **Society** page counts the folk in each and
how many are in the park now. `/village districts` says all of it in chat.

### Life in town

**The village gathers.** The bell rings and the village comes together, by itself, for:
* the **morning assembly** at the board before work: what is being built, what the
  village is short of, the elder's orders, tonight's doings, what the scouts and envoys
  came back with;
* the **opening** of a new building, with thanks to its builder;
* the weekly **feast** (food out of the stores, fireworks only if there is gunpowder and
  paper);
* **weddings** before the chapel, **vigils**, **celebrations** of a new age, an
  **honouring**, and a child's **coming of age**;
* the **council** every seventh day, which hears complaints and votes on what to build
  next;
* the **election** of the elder, two days later.

Folk walk there and take their places, in rows before the speaker, in a ring, along an
aisle or in a circle, children at the front. They face the speaker, cheer, murmur or
hush, then mingle and drift home talking. Two folk passing chat about what is really
going on (the building going up, what the village is short of, the weather, the scouts'
finds), each in its own way. Families have supper together, and everybody runs for
cover when it rains.


The village keeps a register of every building it raises: what it is, where it
stands and which way it faces. It is saved with the world. The details below are
added a little at a time round those buildings:

* **Lit windows.** After dark the windows of each building turn a warm yellow and
  the room behind them is lit. The buildings light up one by one through the
  evening, and their lights go out one by one before dawn.
* **Chimney smoke.** Every chimney has a fire on top, so smoke rises over the
  town's roofs.
* **Washing lines.** Behind the houses, in the yard two rows of houses share, a
  washing line hangs between two posts with the wash pegged out on it.
* **Scarecrows.** Every farmer's field gets a scarecrow at one corner: a post, a
  straw body with its arms out, and a pumpkin head turned toward the crops.
* **Market stalls.** From the Stone Age, four stalls stand on the square under
  striped awnings. The goods the stores hold most of are set out on their
  counters. Passers-by can look but can't take them.
* **Street names.** Every street has a name, and each half of a street has its
  own. The avenues are the North, South, East and West Roads; the other streets
  have names like Mill Lane or Orchard Row. A sign post with a lantern stands at
  every crossing, with each street's name on the side facing it.
* **House numbers.** A sign by every door gives the number and the street, and
  for a house, the names of the folk who sleep there. Numbers go up from the
  avenue outward, odd on one side of the street and even on the other. Other
  buildings have their name over the door: *The Storehouse*, *The Smeltery*.

### Money and the market

**What a village makes, earns and is worth.** Everything a working folk brings home to the
stores is the village's **output**, valued at what the market says it is worth: the
farmers' bread, the woodcutters' logs, the miners' stone and ore, the smelter's iron, the
fishers' catch, the hunters' meat and hides, the crafts' tools, beds and drinks. A carrier
makes nothing of its own: the load it fetches is the worker's output. The books close every
morning and a week is kept, so the village knows whether it is making more or less. The
**traders who come each morning buy up to what the town made**: a busy town meets its
wages, an idle one sells what it can and pays short. Its **worth** is its treasury, its
stores at the market's prices, and its folk's savings.

**Pay** is what the job is worth (see *What every job is worth*, at the end): its value to the
town, how hard it is to fill, how hard it is and the skill it takes, and the folk's own hand at
it, **times what the place is**: a hamlet pays the unit, a village half as much again, a town
twice, a city two and a half times, a capital three, at what the treasury can afford. On top:
one for the elder, and up to two for a hard day's work. When the town comes up in the world
the elder tells the morning assembly that wages are going up; when the treasury is short,
everybody gets the same share and a grumbler or two says so.

**Seeing it.** The village journal (J) has three pages: **Village**, **Wages** — the pay
scale, everybody who works, best paid first, with what their wage is made of, what they have
earned in all and what they are worth, and the richest — and **Economy** — what was made
yesterday by kind and by trade, the week, the best producers, money in and out, and the
village's worth. The same as `/village wages` and `/village economy`; the board shows the
output, the worth and the three best paid, and any folk will tell you who earns the most.

* **Coin.** A *Village Coin* is a gold coin. A new village's treasury starts with
  32. From the Iron Age the village mints more from the gold in its stores, nine
  coins to an ingot, whenever the treasury runs low.
* **Wages.** Every morning the treasury pays each working folk its wage: more for a
  skilled trade, a high level, the elder's office and a hard day's work. Folk save what
  they earn and will tell you how much they have put by.
  * **Short of coin**, every folk gets the same share of its wage. The odd coins go
    round, starting with somebody different each day.
  * **Saving up.** When the village can't yet afford something it must buy (a trade's
    kit, the drover's pair), the wages leave that much in the treasury for three days.
  * **The day's work is the revenue.** What the village made yesterday — its fields, woods,
    mines, pens and crafts, valued at the market's prices, and at the place's standing like
    its wages (a hamlet's work at the trades' rates, a town's at twice) — comes into the
    treasury every morning (less whatever the traders paid for that morning). A busy
    village pays its wages; an idle one pays short. The journal's Economy page shows the
    takings.
  * **Pay for what the work brings in.** A trade is paid by what a hand of it brings the town
    at the town's own prices, against what an average hand does (*What every job is worth*).
  * **Passing traders** come every morning and buy enough of what the village has to
    spare (anything over four lots of it) to meet the day's wages and what it is saving
    for, at a fair price. They never take what the village is short of itself — the
    stone for the hall it is raising — nor the wool its beds are waiting on, and food only
    over a full larder.
  * **Hungry, the fields still get worked.** Every hand needs its rations to work, except
    the farmers, fishers and hunters: their work is the food.
  * **Rations before they run out.** A hand down to its last three or so takes more at the
    counter if it is at the stores (after the morning assembly, say), or, far out on its plot,
    has a courier bring them out while it works. Of an evening anybody living in the town
    tops up at the stores. A hand whose plot is a long walk out carries a few days' (twelve),
    one near the stores a couple of days' (eight), and no more than a day's while the larder
    is low. Rations are food that will not poison it: no rotten flesh, spider eyes or raw
    chicken.
  * **Two meals a day, for everybody.** Every folk eats **the midday meal** (from half past
    eleven) and **supper** (from five in the evening), and is content on the two: there is no
    breakfast (there was, and a town ate a third more than it needed). A hand at its work eats a
    ration every five and a half minutes of work, a third less often than it did. The children, the old, the leader and folk between trades as well as the hands at
    their work. At each mealtime it eats out of its own pack first, then its household's chest at
    home, then the village's stores (the town feeds its own; a far hand at its work has its rations
    sent out instead), always a real loaf, fish or stew out of somewhere, booked in the books. A
    ration eaten at its work while the mealtime is on is that meal: nobody eats twice. With nothing
    in reach it **misses the meal** and is **hungry** (its contentment falls, more for every meal
    missed, and it says so); a whole day without (both meals) and it can't work properly; two days
    and it grows weak (it loses health, never past three hearts). Its card says when it last ate and what, and
    how many meals it has had today; the Why page says whether every folk had its meals yesterday,
    or how many were missed and by how many folk. (Once only a hand at its work ever ate, and a
    child, an elder or a folk off its shift went all its life without a bite.)
  * **Contentment knows the difference.** Wages paid in full are a good thing; paid
    short, or hardly at all, folk say so.
  * **The tax.** A tenth of every wage is the village's tax: it never leaves the treasury, and
    the folk is paid the rest (the odd part of a coin is carried to its next payday, so a hand on
    three a day pays a coin every third or fourth morning). The poor pay none, and while the
    treasury holds a week's wages it takes none. The wages are reckoned before the tax out of what
    the treasury holds, so a payday never empties it: it keeps a tenth of whatever it pays out.
    (It used to pay out every coin it held each morning, and a town of a hundred kept a few coins
    in its treasury while three thousand sat in its purses.) The Wages page says so, and the
    Economy page and the town's books count the tax as money in.
  * **The tithe.** On the day of rest every folk with savings gives one coin in ten of
    what it holds over a dozen back to the treasury.
  * **Rent.** Straight after the wages, every household that rents from the village pays its
    day's rent into the treasury (a coin for a house in most places: see Homes), and those
    saving for a house of their own put some of their pay by; a household that has saved the
    price buys its house. The Economy page counts both as money in, and what the folk spend in
    town out of their own purses (the market's treats, the café, the shop, the tavern, the
    comforts of home, a child's bed) too: "spent in town".
* **Wool for the beds.** Every morning, before the wages, a village whose houses wait
  for beds and that has no wool to make them buys a lot or two from the traders, out of
  half the treasury at most. Short of the coin, it puts a lot's price by for tomorrow. While the beds wait, the village keeps
  twice the ranchers, for their sheep.
* **Market day.** Once a week, on a different day for every village, the bell
  rings on the square in the morning and players nearby are told. On their break
  that day, folk go to the stalls and buy a treat with their savings: their
  favourite food if the stores have it. The coin goes back into the treasury. A
  player's own stall on the square is weighed like any other seller (see *Your stall
  on the square*).
* **Prices.** Everything the market deals in has a worth. Its price moves with
  how much of it the stores hold: dear when it is scarce, cheap when there is
  plenty, and kinder on market day. Each stall has two signs:
  * one listing the lots on its counter and their prices;
  * one saying what the village is buying and what it pays.
* **Trading at a stall.** Right-click a stall's counter:
  * **To buy** what is on the counter, have coin in your pack.
  * **To sell**, hold goods the village buys. It pays from its treasury and puts
    them in its stores.

  Friends of the village get a tenth off, the unwelcome pay double, and an outcast
  can't trade at all. `/village status` shows the treasury, what is in the
  folk's purses, and how many days until market day.

### The price list

Everything the folk can mine, grow, catch or make has a price in village coin, and they are
paid for all of it:

* **The market's board first.** A loaf 0.3, a log 0.25, a cobblestone 0.04, an iron ingot
  1.5, a gold ingot 9, a diamond 24. The board, the shop and the wages never disagree.
* **Everything gathered** is on a base list: every ore (in the block and out of it), every
  kind of stone, earth and sand, the Nether's and the End's blocks, crops and seeds,
  saplings, flowers, mushrooms and coral, everything from animals and monsters (hides,
  wool, bones, pearls, rods, heads, shells), every fish, and the rare finds (the heart of the
  sea, a nether star, music discs, trim templates, pottery sherds).
* **Everything made** is priced from its own recipe when the world starts: crafting, the
  furnace, the smoker, the blast furnace, the campfire and the stonecutter. Its price is
  what goes in, by the cheapest way of making it, plus the work: a tenth on top for a
  craft, the fuel for a smelt. So a block of iron is nine ingots, a diamond pickaxe its
  three diamonds and two sticks and the smith's time, and a beacon its nether star, glass
  and obsidian. A modpack's items are priced the same way from their own recipes.
* **Enchantments add to a thing's worth, and wear takes from it.** Anything nobody can make
  in survival (command blocks, spawn eggs) is worth nothing.
* **What sort of goods it is** comes with the price, so each trade is paid for its own
  work. A miner is paid for deepslate bricks, a woodcutter for saplings, a farmer for seed
  and a beekeeper for flowers. The economy page counts **plants and flowers** as goods of
  their own.

### The village's purse: its own needs first

The village keeps its books, and **sees to itself before it sells anything**:

* **What it keeps.** A full larder (and never the last sixteen of a thing). A tool for every hand
  that uses one, and spares: a pickaxe for each miner, an axe for each woodcutter, a hoe for each
  farmer, a sword and armour for each guard, shears, rods, bows and shields. It keeps its best ones.
  It also keeps the timber and stone its building wants, the ore its smiths and smelters work, the
  wool its beds wait on, seed and saplings, torches, buckets and a couple of anything else.
  Whatever the village is short of, it keeps all of.
* **When it sells.** Only once it is **making enough for itself**: fed, every bed made, every
  hand tooled, the wages paid in full. Until then it sells only a **glut** (four times what it
  keeps, and more), such as the miners' mountain of cobblestone.
* **What it sells.** Everything over what it keeps, at the price list's worth and **a quarter
  over**. Ask any folk **"What can the village spare?"** (the **For sale?** button) for the list.
  Then ask the storekeeper for what you want, for example *"could I have an iron sword?"*. Friends
  and citizens still get their free share, but only out of what is spare.
* **The shop and the café.** The shop's counters show spare armour, arms and tools of every
  kind, as well as what the crafts make. The café sells no bread off a low larder. What the
  crafts make to sell (drinks, potions, enchanted things, banners, rugs, books) is always for sale.
  What the shop and the café make themselves is marked down when it is slow, and never sold for
  less than it cost (see *What sells, and what to make next*).
* **Its coin.** Two days' wages are kept back first, then what it is saving for (wool for beds, a
  hive, the drover's pair). The rest is free. The board and the village status show **the purse**:
  the coin, what is kept back, what is put by, what is free, and what is for sale.

### Your stall on the square

A market stall of your own (`entity/PlayerStalls.java`), let a week at a time.

* **Renting.** Ask any folk *"Could I rent a stall?"* (the **Rent a stall** button on the Money tab),
  right-click a stall standing empty on the square (its sign says *Stall to let*), or
  `/village stall rent`. A week costs five coins in a hamlet, more in a bigger place (eight in a
  village, ten in a town, thirteen in a city, fifteen in a capital), paid into the treasury; pay up to
  four weeks ahead. Up to four stalls stand on a square, on the sides near the wall, clear of the
  market's own stalls, the well and the ways in.
* **The booth.** A barrel, a sign on its lid with your name and what it sells, two posts and a
  striped awning. The market's hands put it up out of the stores (a barrel or seven planks, a sign or
  two planks, four lengths of fence or eight planks, three wool), never while the village is short of
  timber; if the stores can't spare them, out of your own pack (a barrel or a chest and a sign; fences
  and wool for the posts and awning). From the Stone Age, with you about and the makings to spare, the
  market keeps one booth standing empty, to let. Nobody else may open or break your booth, and no folk
  or helper takes anything from it.
* **Stocking it.** Right-click its barrel and put in whatever you'd sell.
* **Your prices.** Crouch and right-click the barrel, or right-click its sign: the stall's screen lists
  each kind of thing in the barrel with a price box for a lot of it (the market's lot: eight bread,
  eight apples, one pick), the village's **going price** beside it (what its own counter asks today:
  dear when its stores are short of it, kinder on market day), and how the folk will take your price:
  *a bargain*, *fair*, *a little dear*, *dear: the well-off*, *dear: the rich only*, *too dear: nobody*.
  Leave a box empty for the going price; 0 keeps a thing back. Enter, **Save prices** or closing the
  screen sets them. Or `/village stall price 3 bread`.
* **Who buys.** On market day nearly everybody with coin walks over to look; on other days a folk comes
  when it wants something you have: the tool of its trade when it has none, food when the larder is
  low, a comfort for its home (a rug, a pot, a candle, a lantern, a chest, a barrel, a bookshelf once
  it is well off, which it carries home and sets up by its bed), something nice when it is doing well.
  At the market's own stalls on market day a folk weighs yours as it would any other seller.
* **What they pay.** Weighed against the going price: the thrifty want it cheaper (a tenth under), most
  folk pay the going price, the comfortable a tenth over, the well-off a quarter, the wealthy two fifths,
  a generous folk a little more, and a little more for its favourite food; nobody pays half as much
  again. And nobody pays more than the village asks for something the village has itself. Out of its
  own purse (keeping a couple of coins back, unless it needs the thing). A thing priced too high sells
  nothing, and the folk say so: *"Thirty for bread? Not likely."* Worn tools don't sell.
* **The till.** What the folk pay goes into the stall's till, not the barrel. Take it on the stall's
  screen (**Take the till**), by asking any folk to *"take the till"*, or `/village stall till`. The
  market takes no cut: the rent is its due.
* **The books.** Every sale is booked: who bought what, how many, for how much. You are told in chat
  whenever something sells, and (once a thing a day) when a folk thought it too dear and what the going
  price was. The stall's screen shows the till, the week's sales and takings and all of them, the last
  sales and what was turned down. The town's books (the village board) have a **players' stalls** tab
  on the **Shops** page: every stall, its rent and how long it runs, its stock and prices against the
  going prices, the week's sales and takings, the till, the last sales and the turnings-down. Yesterday's
  takings go in the chronicle.
* **When the rent runs out** the stall shuts (its sign says *Rent due*, and you are told). Pay another
  week and it opens again. Three days on it is **given back**: everything in its barrel stays there for
  you to collect (nobody else can open it), and its till is kept for you. Once you have emptied it, it
  is let again. Ask any folk to *"give up my stall"* (or the screen's **Give it up**) to have your goods
  and the till back at once; the rent paid is the market's. Nothing you put in it is ever lost.
* `/village stall` lists the stalls of the village you stand in; `/village stall screen` opens yours;
  `/village stall books` opens the town's books at the players' stalls.

### You and the folk: twenty things to do together

Small things first (the **Deal** tab of the talk screen, or just say it):
* **A greeting by name.** Friends passing by hail you by name now and then.
* **Eat together.** Share the food in your hand: the folk eats with you and likes you the
  better, more so if it is its favourite. Once a day each.
* **Dice.** A game of dice for three coins (say "dice for 10" to raise it), out of each
  other's purses. A shy folk may not play.
* **A lesson.** Show a folk a trick of its trade with the tool of it in your hand (a hoe
  for a farmer, a pickaxe for a miner, a rod for a fisher). Once a day, it learns, and it
  may go up a level.
* **Godparent.** Ask a child: a friend of the village may stand godparent. The child thinks
  the world of you, and its parents the better of you.
* **A keepsake.** A close friend (affinity 50 and more) gives you something of its own,
  named and inscribed, to remember it by. Once.
* **Letters.** Close friends write to you once a week, when you are about the village:
  the latest news, and a line from them.
* **Feast on me.** Pay for tonight's feast (ten coins and one for every mouth): the whole
  village gathers, and remembers who paid.

The trades (the **Money** tab):
* **Make me…** Ask a smith, tailor, smelter or enchanter to make you something. It is made
  from its own recipe, out of what you carry and what the village can spare. You pay for
  the village's makings, a quarter over, and a fifth of the thing's worth for the work.
  It is ready the next day: ask for it.
* **Mend this.** The smith mends the worn tool, weapon or armour in your hand, for coin
  (by how worn it is) and a scrap of its metal, from your pack or the village's spare.
* **Haggle.** Ask the storekeeper, shopkeeper or cook to do it cheaper. A generous one and
  a friend are easily talked round; a shrewd elder's village is not. The discount (five
  to twenty in the hundred) holds at the stores and the counters for the day.

Business with the whole village (the **Money** tab):
* **Bulk orders.** "I'd like to order 256 cobblestone": what the village can spare goes at
  once, a tenth off for the quantity. The rest is put by for you as it is made: a quarter
  down, the rest when you collect, within the week (or your deposit back).
* **Supply contracts.** The village offers a contract for what it is short of: so much a
  week for a month, at a third over its worth. Say "I'll sign", then bring it each week.
  Keep it up and the village thinks the world of you. Miss two weeks and it lapses, and
  they think less of you.
* **Your own stall.** Rent a stall on the square a week at a time (five coins in a hamlet):
  a booth with your name on its sign. Stock its barrel, set your prices against the going
  price, and the folk buy from it out of their own purses into its till. See *Your stall on
  the square*.
* **The bank.** "Deposit 20", "withdraw 10": the treasury keeps your coin and pays a coin
  in fifty a week. A friend may "borrow 30" (an honoured one up to 64), at a tenth a week.
  A debt left a fortnight shames you. "Repay" pays it off.
* **Investing.** "Invest 50": for two weeks you take a share of what the village takes in
  each day, paid into your account.
* **The auction.** On market day the village puts its finest spare thing up for bids.
  "I bid 60": the best bid takes it the next day (an outbid bid goes back into your
  account). Ask for your lot to collect it.
* **Caravan escort.** Sign on to guard the next caravan, walk with it, and you are paid at
  the other end. Both villages think the better of you.
* **A trade route of your own.** Fifty coins charters a trade route to a neighbour: the
  caravans run it, and a tenth of every load sold on it goes into your account.
* **Where things are dear.** "Where's iron dear?": what a thing sells for here and in the
  villages round about. Buy cheap, sell dear.

### Roads and caravans

* **Roads.** When a village founds a colony, a road is laid between them. It runs
  from the end of one town's avenue to the end of the other's and is built a few
  steps at a time:
  * a worn path three wide, following the land a step up or down at a time,
    banked up over dips and cut through bumps, with trees and plants cleared;
  * a plank bridge with rails and lanterns where it crosses water;
  * a lamp post every so often.

  The ground at the road's head is kept loaded while it is being laid, so the road
  gets built even when nobody is near. When it is done, both towns record it in
  their history. A signpost at each end gives the other town's name and how far
  away it is. `/village status` shows how far along each road is.
* **Caravans.** Every two days, in the morning, the village sends a caravan to
  each of its colonies, usually led by a carrier:
  * The carrier carries on its own back what the stores have more than plenty of, the
    colony's needs first: what the colony has sent for. The mother village pays its road
    money (a coin a hundred blocks) and its provisions.
  * At the colony, **the colony buys the goods as they come off the carrier's back**, at the
    family price (half what the market says they're worth); between two villages with a
    trade pact it's the full price. What the treasury can't pay for goes home again with the
    carrier. Then it buys what the colony can spare that the mother village is short of —
    never what it just brought — there and then, out of the coin in its purse, and walks home
    with the goods and what is left of the coin. Coin is always carried: nothing is paid into
    another town's treasury from afar.
  * Players nearby are told when a caravan sets out, and both towns record each
    delivery in their history.
  * You can meet a caravan on the road. Ask the carrier what it is doing and it
    will tell you where it is headed; ask to trade and it will offer what it is
    carrying.

### The watch, the gates and raids

Once a village has its wall, it sees to its own safety.

* **Guards are hardier.** A guard has twice the health of any other folk (forty, where the rest
  have twenty; a sturdy one's extra heart and a veteran's are doubled with it). A folk at full
  health when it takes up the watch is at its new full at once; one who leaves the watch is back
  to the ordinary, keeping its wounds where they fit.
* **Lights from the first days.** A torch on a fence post goes up every few blocks along
  the streets, made from the stores' coal and wood; from the Iron Age, lamp posts.
* **An iron golem.** An Iron Age village keeps one, as a vanilla village does. If it is
  lost, another is made a few days later.
* **The wall over water.** Where the ring of the wall crosses a pond or a river (up to six
  deep), the builders raise a stone footing from the bed and the wall stands on it.

* **Gates.** Each of the wall's four gaps gets a gate: stone posts, a lintel and
  three spruce doors. Ten stone and six planks (or two logs) come from the stores. The doors stand open by
  day and are shut at dusk. Folk let themselves through, as you would; a zombie can't.
* **The watch's posts.** Two places on each side of the wall where a guard stands
  between the battlements, with a ladder up the inside of the wall to each. If a post's
  usual place won't do (the wall went round a house there), it goes a little further
  along. A wall the builders had to finish in planks or logs, because the stone ran out,
  still gets its gates and posts.
* **The alarm bell.** A bell on a stone plinth on the square, rung when trouble comes.
  Once there is a chapel, its bell rings too.

**When the bell rings.** It rings when four or more monsters get inside the wall,
when a raid comes to the village, or when a raiding party is sighted. A village with
no wall yet has no bell to ring: its folk are indoors at night anyway. Monsters in
caves under the town don't count. By day the bell stops once there are only one or
two monsters left (the watch deals with those on its rounds), so a creeper in the
shade can't keep the whole village indoors. Once the day has stopped the bell, it
doesn't ring again for monsters until dusk (a raid still rings it).

* The gates are shut and every guard turns out, whichever watch it keeps.
* A guard with a bow (its own, or one from the stores with arrows) walks to its post,
  climbs the ladder and shoots from the wall. Posts on the side under attack are
  manned first. A guard with no bow holds the nearest gate from the inside.
* Everybody else drops their work and gets indoors: home to their own bed, or into
  the nearest of the village's buildings. Children too.
* When it is quiet again the bell stops and the guards come down. The night goes into
  the village's history: how many came, how many the watch killed, and who fell.

**Raiding parties.** About one night in five, a walled village with at least ten
folk and a watch is raided.

* A band comes at one of its gates after dark. It is bigger the more guards there are
  to meet it, and no more than ten.
* Stone Age: zombies and a skeleton. Iron Age: spiders join them. From the Diamond
  Age: pillagers and vindicators.
* The band goes for the village's people. What is left of it slinks off at dawn.
* If you kill raiders, every folk in the village thinks better of you, and the village
  remembers that you stood with it.
* `villageRaids = false` in the config turns raids off. There are none on Peaceful.

**Arms for the watch.**

* The blacksmith makes a bow for every guard (three string, three sticks) and keeps
  arrows stocked: a flint head, or a chipped stone one, on a stick. Four arrows with a
  feather, two without.
* The tailor spins string from wool when the stores run short.
* Any guard may draw a bow while the bell rings; other times a bow is still level-20
  work.

`/village status` shows the watch: whether the bell is ringing and why, how many gates
there are and whether they are shut, and how many posts are on the wall. If the wall is
built but has no gates or posts, it says why, side by side (no gap in the wall, the gap
blocked, no wall-top to stand on).

### The watch's kit

The town kits out its guards with the best armour and weapons it can make, and the town pays
for it. A guard never spends a coin of its own on its kit.

* **Leather in the Stone Age.** Before there is iron, the watch goes in leather: a cap of five
  leather, a tunic of eight, trousers of seven and boots of four, the same as you would make them.
  The tailor cuts them out of the stores' leather (the smith does it if the town has no tailor,
  and the shop's workshop once the shop stands). The stores keep as many of each piece as there
  are guards who wear worse, and a little leather is always kept back for books and the like. A
  tailor of ten years and more makes them in the town's colour.
* **Iron in the Iron Age.** The smith's iron helmets, chestplates, leggings, boots and swords. The
  iron an age asks for is sized to put the watch in armour, so the watch's armour does not wait
  while the town puts iron by: up to the watch's share of that iron, the smith forges each piece a
  guard is waiting for, and the iron on the guards' backs counts toward the age as if it were in
  the stores. Past the watch's share the armour waits for the age (unless the town is on a war
  footing), and those guards keep their leather meanwhile.
* **Diamond in the Diamond Age.** Once the town has come into the Diamond Age and is not putting
  its diamonds by for the next age, the smith forges the watch a diamond sword first, then the
  chestplate, the leggings, the helmet and the boots, a couple of diamonds always kept back. It
  works on these turn and turn about with its other forging. If the smith has the hand to make the
  miners' diamond pick, the pick comes first; if it has not, the watch does not wait for it. In the
  Nether Age a master smith (level 40) takes them on to netherite at the smithing table, with the
  stores' netherite ingot and upgrade template.
* **A smith and a tailor for the watch.** A town that keeps a watch takes up a smith in the Iron
  Age and a tailor in the Stone Age before their smithy or workshop stand. They work from a post by
  the square, a little slower, and the smithy then goes up with what the age asks for. (A real town
  of a hundred had built neither in fifty-eight days, and so had no smith, and no armour.)
* **Whatever the maker's years.** The watch's armour, blades and shields wait on nobody's level. A
  smith or tailor makes them once the age has come to them, and what is beyond its hand comes out
  as an apprentice's work: it wears through a little sooner and carries a beginner's mark. A smith
  takes weeks at the anvil to reach level 10, and many months to reach 25, so the watch would
  otherwise have waited for ever. The rest of the ladder (diamond picks, a player's order) still
  waits for the hand (see *The makers' hands*).
* **The best goes on.** Every so often each guard looks in the stores (and the shop's round does
  the same for the whole watch): in each slot it puts on the best piece there is that beats what it
  wears (netherite, then diamond, iron, chainmail, leather), takes the blade that bites hardest if
  it beats its own, and a bow with a few arrows and a shield if it has none. The piece it took off
  goes back into the stores for the next guard or the militia. A guard's own wooden or stone sword
  stays in its pack.
* **No rank on the town's kit.** A guard new to the watch wears diamond and wields a diamond sword
  as soon as the town issues them: it is the town's kit, not a tool it has to earn the skill for.
  Other trades still need their levels for diamond and netherite tools.
* **Handed back.** Everything the town issues its watch carries the town's mark, including a blade
  off the storehouse's rack. A guard who leaves the watch, for another trade or to live in another
  town, hands it all back into the stores for the next guard or the militia: armour, blade, bow,
  arrows and shield. Its own stone sword stays with it. If a guard dies, its kit drops as anybody's
  would.
* **The town pays.** Every piece is made out of the stores' own leather, iron and diamonds by the
  town's makers, and issued free. A guard is never sold its blade at the shop, never charged for
  one off the storehouse's rack, and never pays for armour. The shop's takings go into the
  treasury anyway, so the town has nothing to pay its own shop; instead, what the kit is worth at
  the town's prices is booked as the watch's kit.

**Where you see it.**

* A guard's card has a **Kit** line: *iron helmet, iron chestplate, iron leggings, leather boots,
  iron sword, bow, 16 arrows: issued by the town, not a coin of it from its own purse.*
* The board says how the watch is dressed: *The watch: 4 guards, 3 in iron, 1 in leather.*
* The town's money page (`/village economy`) adds what the kit has cost the town, all told and
  this week.
* The chronicle notes the day the watch first went into iron, into diamond and into netherite.
* A guard handed a better piece often says so: *New diamond chestplate from the stores. Let them
  come.*
* `/village watch` (or `/village watch kit`) lists each guard and its kit, what is on order for the
  watch (and who will make it, and what it is waiting on: the age, the diamonds put by, the
  miners' pick, the age's iron past the watch's share), the shop's order book for the watch, what
  was made for it today, and what it has all cost.

### The crafts, the café and the shop

As a village grows, some of the newcomers take up a craft instead of the fields. Each
craft wants an age and a headcount, and the village keeps only one or two of each
however big it gets. Nobody is taken off the farms to fill one; crafts go to folk
born or grown up into a big enough village.

| Trade | From | Works at | What it does |
|---|---|---|---|
| Cook | Stone Age, 14 folk | the café | Bakes potatoes, roasts meat and fish, bakes bread, cookies, pumpkin pie and cakes, and makes drinks for the café and the tavern, more of what sells |
| Tailor | Stone Age, 18 folk | the workshop (before it stands, a post by the square, once the town keeps a watch) | Makes beds (in the colour of the wool), rugs, string, banners on its loom, and the watch's leather |
| Beekeeper | Stone Age, 20 folk | a meadow outside town | Keeps up to four hives: comb with shears or honey with a bottle from a full hive, new hives from comb, bees bred on flowers |
| Blacksmith | Iron Age, 16 folk | the smithy (before it stands, a post by the square, once the town keeps a watch) | Makes iron picks for the miners, swords and armour for the watch (diamond in the Diamond Age), shears, buckets, axes and hoes; bows, and arrows of flint, stick and feather |
| Shopkeeper | Iron Age, 18 folk | the shop | Makes what a house wants at its bench, the whole way from the stores (logs to planks to sticks to a pick), more of what sells, and sets it out on the counter with what the crafts have made; with the hands it takes on, makes the watch's blades and armour and the rack's spare tools, by any recipe there is, as far as the age has come (*The shop's workshop*) |
| Brewer | Iron Age, 22 folk | the brewery | Brews at a real brewing stand: healing for the watch, then swiftness, night vision, regeneration, leaping, water breathing, fire resistance and strength |
| Enchanter | Diamond Age, 24 folk | the library | Binds books from paper (the farmers' cane) and leather, then enchants the village's iron and diamond tools and armour with lapis at its table |

Every craft works out of the village's stores and puts what it makes back into
them, a piece of work every twenty seconds or so. A blacksmith always leaves a few
bars of iron for the village, and a cook never uses the last of the seed crops.

**Drinks.** The cook makes six drinks, three bottles at a time (from glass bottles,
or glass). Each does a little good:

| Drink | Made from | Does |
|---|---|---|
| Apple Cider | apples | absorption for a minute |
| Berry Juice | sweet berries | jump boost for a minute |
| Honey Tea | a honey bottle | regeneration for ten seconds |
| Hot Cocoa | cocoa beans | a little food |
| Melon Juice | melon slices | speed for a minute |
| Carrot Juice | carrots | night vision for a minute and a half |

**The café and the shop.** Each has a counter of casks. Every cask has one thing
on it, in a frame, and a price tag in front. The café shows its drinks first, then
whatever food is ready; the shop shows enchanted things first, then potions, then
tools, armour, beds, rugs, banners, books and honey. A counter is only stocked
while the village has a cook (for the café) or a shopkeeper (for the shop).

* Right-click a counter to buy what is on it with village coin from your pack.
  Crouch and right-click to ask the price first.
* An enchanted thing costs three times as much. The village won't buy worn tools
  from you, and pays double for enchanted ones.
* Folk drop in to the café about one break in three, if they have a couple of coins
  saved. They buy a drink or a bite, have it there and then, and the coin goes back
  to the treasury.

**Made the whole way, from what the stores hold.** Whoever sells something knows how it is
made, by the game's own recipes (`entity/Bench.java`), and makes every step of it out of the
stores: the shopkeeper saws logs into planks, cuts planks into sticks and slabs, beats an ingot
into nuggets and burns a log to charcoal for torches when there is no coal; the cook presses cane
into sugar for a pie, blows the smelter's glass into bottles for its drinks, and bakes bread of the
farmers' wheat. A modpack's things are made the same way, from their own recipes.

* **What is left over goes back.** The rest of a log's four planks, a slab's other five, a
  cake's three milk buckets: back into the stores.
* **The bench and the fire.** A three-by-three recipe wants a crafting table (the one a folk
  carries or borrows, one in the stores, or one standing in its building); a firing wants a furnace (the
  smeltery's will do), the café's smoker, or for food the tavern's hearth, and its fuel by the
  game's burn times (a coal fires eight, a plank one and a half; the hearth burns for nothing).
  With no table or furnace to hand, it makes one first and keeps it.
* **Never the village's own.** It never takes what the village is short of for its age (timber in
  the Wood Age, stone and coal in the Stone Age, iron in the Iron Age), the builders' working
  timber (16 logs, 48 planks) and stone (64, or what the age holds back), the smith's last 16 bars
  (24 while there is a smith), the coal's last 8, the mint's gold, the beds' wool while beds wait,
  the last 12 of each seed crop, nor anybody's tools, arms, armour or marked work.
* **Its hand.** Nothing above its level at the trade (*The makers' hands*), and a tool comes off
  its bench as good as its hand, with its mark on it.
* **Short of something,** it moves on to the next ware, writes down what it is short of and why
  (*"3 iron ingots (put by for the age)"*), says so once a day, and puts the makings of the ware
  that sells best on the quest board (*"bring 8 string for the shop's fishing rods"*).

**What sells, and what to make next** (`entity/Stockroom.java`). The shop, the café, the
tavern, the market's stalls and the stores each keep books: for every ware, a week of what was
sold (to folk and to players), what was asked for and not there (a miner after a pick the shelf
had not got, a favourite missing on market day, a round poured in water), what was made, the coin
it took, and what one cost to make. They are kept with the village and turned over day by day.

* **How many to keep.** For its first two days a seller keeps the usual (the shop four candles,
  two chests, a pick of each kind; the café a dozen loaves, three of each drink). After that it
  keeps **two and a half days' sales** (a day's sales: the week's average, or today's if busier),
  never fewer than the ware's fewest (one, for most) nor more than its shelf holds. A ware nothing
  has sold of all week is cut to its fewest, and at its fewest no more is made; the iron tools,
  dear things, are not kept at all once they stop selling. What the village lives on (bread,
  baked potatoes, the roasts, torches) never goes under the usual: the larder eats it.
* **What first.** The ware whose shelf is emptiest against what it means to keep, that the stores
  can run to; one piece of work at a time.
* **Prices.** A ware over what its seller keeps that has not sold for three days is marked down a
  tenth, another tenth every two days after, to four tenths off; a sale ends it. Nothing a seller
  makes is sold for less than it cost.
* **On hand** is what the stores physically hold of it; the counters show what the village can spare.
* **Asked about its trade,** a shopkeeper or a cook says what sells best this week, what is low,
  what is marked down and what it is short of; asked what the village is short of, any folk names
  what the sellers cannot make for want of something.
* **Made to order.** Ask the storekeeper for a thing the stores have not got (*"could I have a
  chest?"*) and it makes it up at the bench out of what they can spare, if it has the hand for it;
  if it cannot, it says why.
* **The town's books** read it all from `Stockroom.inventoryReport`: per seller, per ware, what is
  on hand and spare, what it means to keep, sold today, yesterday and this week, asked for and not
  there, made today and this week, the price, the cost, any markdown, what it is short of, and the
  way it was last made.

**Buildings.** A village builds a café from the Stone Age once it has 14 folk, a
smithy (16), a shop (18) and a brewery (22) in the Iron Age, and a library (24) in the
Diamond Age. These come after everything the age itself asks for, so they never hold a
village back from its next age. Houses for beds and these amenities go up turn about (a
house, then an amenity, then a house), so a town that grows faster than it builds still
gets its café, smithy and the rest. The builder makes their
furniture from the stores: a smoker, loom, grindstone, bookshelves, a lectern, a
brewing stand (from a blaze rod) and an enchanting table (from a book, two diamonds
and four obsidian). Anything it can't make is left out — and the craft that works
there sets its own down on that very spot (see below).

### How each trade really works

Every trade works the way you would do it yourself, with real blocks and real
items. Some things a young village could never make, so the **first of a trade
brings them along** — once per village (if they are lost, they come again three
days later):

| Trade | Brings | Why it couldn't be made |
|---|---|---|
| Beekeeper | a beehive with a swarm of two bees in it | a hive is made of honeycomb, and honeycomb only comes out of a hive |
| Brewer | a brewing stand, 8 blaze powder, 4 nether wart, 4 soul sand | blaze rods, wart and soul sand are all from the Nether |
| Enchanter | an enchanting table and 6 lapis | the table needs diamonds, obsidian and a book |
| Blacksmith | an old (chipped) anvil, if the stores haven't 31 iron | an anvil is thirty-one iron |
| Tailor | a loom, if the village has no string | string comes from spiders |
| Rancher | two leads, and shears if the village has no iron for them | leads need slime; shears need iron a young village spends on picks |
| First farmer | 3 sugar cane, 2 melon seeds, 2 pumpkin seeds (if nobody has any) | seeds and cuttings like these are rare finds |
| First fisher | a fishing rod, if the village has no string | a rod is string, and string is spiders' or wool's |
| Hunter | a bow (if nobody has one and there's no string), 16 arrows, 2 leads | a bow is string; arrows want feathers and flint |

Every one of these is **bought from a pedlar**, out of the treasury: no coin, no kit — and
the wages leave the price put by in the treasury until it can be paid.

A workstation that belongs in a building (the brewer's stand in the brewery, the
enchanter's table in the library) stays in its owner's pack until that building
stands, and is then set down on the spot the drawing has for it.

**Who takes up a craft.** A village gets one of each craft before it gets more of the
common trades: when a post falls empty and the watch is full, a brewer, beekeeper,
enchanter, smith or tailor the village has none of comes before a fourth farmer. A craft
is taken up once its building stands (the smith's smithy, the tailor's workshop, the
brewer's brewery, the enchanter's library). There is only ever one beekeeper.

**The village's shape.** Each trade has its share of the village, and the shares are
fitted to the hands the village actually has: children count toward its size but work at
nothing. So a town of thirty keeps a proper watch and its crafts, and a child who grows up
in a village with more farmers than it needs takes up the trade the village is short of
rather than its parent's.

Everything after that the village makes for itself:

* **Beekeeper.** Sets the hive it brought on its meadow and lets the swarm out.
  A full hive gives three honeycomb to shears (the smith's) or a honey bottle to a
  glass bottle. When the bees fill the hives, it makes another from three honeycomb
  and six planks. While there is room, it feeds two bees a flower each so they
  breed. It keeps flowers round the hives: from the stores, grown with bone meal
  (the watch's bones), or dug up wild and replanted.
* **Brewer.** Sets its stand down in the brewery and fires it with blaze powder,
  which lasts twenty brews. It puts in three bottles of water and a nether wart and
  waits twenty seconds for awkward potions. Then it adds the reagent the village is
  shortest of, waits twenty seconds more, and the three potions go to the stores:
  * healing: a glistering melon, made from a melon slice and gold;
  * swiftness: sugar, from the farmers' cane;
  * night vision: a golden carrot;
  * water breathing: a fisher's pufferfish.

  Its soul sand goes into the ground beside the brewery as a nether wart patch,
  which it picks and replants.
* **Enchanter.** Sets its table down in the library. Paper is pressed from the
  farmers' cane, and three paper and the rancher's leather make a book. Each
  enchantment costs three lapis and a book, and the more bookshelves round the
  table, the stronger it is.
* **Blacksmith.** Arrows are flint, stick and feather, four at a time. Flint is
  knapped from the miners' gravel, and feathers come from the rancher's hens. Planks
  are sawn from the woodcutters' logs as needed.

**Hunters** (from fifteen folk; sooner in forest, pine woods, snowfields and savanna).
Hunting grounds out past the fields, wherever the game is. A hunter takes only grown wild
cows, pigs, sheep, hens and rabbits — never the village's herd, never one with a name, never
the young, and **never the last pair of a kind** within twenty-four blocks, so there is
always game next year. It stalks quietly up to the quarry and takes it with its bow or its
blade, sweeps up the meat, hides, feathers and wool, and brings them home for the stores (the
cook and the café turn the meat into meals; the tailor has the leather). When the village's
pens are short of a kind, the hunter brings one home **alive** instead, with feed or on one
of its leads. When a whole day turns up nothing, the game has gone, and it finds new
grounds. It wears a mottled hood, a fur mantle, a quiver across its back and a knife.

**Fishers** need a rod: the first brings one, and after that one is made from the stores'
string and wood — a lock of wool spun into line when there's no string. A fisher fishes from
the bank, up to six blocks out, and tries another stretch of bank before it gives up on the
water.

**The links between trades.**

* **Farmers.** Plant the cane along the field's water and cut it back to its
  bottom so it grows again. The cane goes to the enchanter's paper, the brewer's
  sugar and the café's pies and cakes. They also plant the melon and pumpkin seed.
* **A village feeds itself.** Its food comes from its own farmers, fishers and hunters and
  nothing else, so it looks after them:
  * **Buckets.** Every farmer gets ten buckets of water (a pedlar's, a coin apiece out of the
    treasury): one for the water hole in the middle of each square of its field. A crop on
    wet farmland grows three times as fast as on dry. A field with no pond by it is still a
    field: its farmer digs the first water hole the day it gets there.
  * **The farmland.** The village marks out one side of the town for its fields: the side
    whose ground is best (open soil, level with the town, water on it or by it). There its
    fields are laid out before the first furrow, in squares a full-grown field across
    (twenty-seven blocks) with a two-block lane between, starting just past the town's
    first block (forty-one blocks out) and going on outward, the avenue running up the
    middle as a farm track. Each new farmer takes the nearest free square, so the fields
    come up side by side and never overlap. A square more than ten blocks above or below the
    town is passed over: a farmer walks there and back every day.
  * **Only where the farmers can walk.** The village maps the ground it can reach on foot from
    its heart, out to a hundred and twenty-eight blocks: a step up or down at a time, round
    trees, houses and cliffs, and never through water deeper than a wade. Its fields go only
    on that ground, and a field nobody can walk to (one staked before the map, or by an older
    village) is given up for one they can. The long walks out to a field and home to bed
    follow the map, about twenty-eight blocks at a time, so the folk go round a pond or a
    ridge instead of swimming into it. A folk stuck in water climbs out onto the nearest bank,
    choosing one that leads somewhere.
  * **The town keeps off it.** The town grows the other three ways. Its lots on the
    farmland side past the first block are never built on, nor any lot over a field, pen or
    hives that is already there. Its streets stop at the field edge, and woods, mines, pens
    and hives are never staked on the farmland.
  * **Hungry, more hands to the fields.** While the larder is low the village takes on half as
    many farmers and fishers again; in famine its miners and woodcutters go to the fields
    whatever its building wants.
  * **Room for the harvest.** Stores full of rubbish (dirt, gravel, spare rough stone, rotten
    flesh) throw it out, so the crops always have somewhere to go.
* **Fields that grow, square by square.** A field is laid out the way you would lay one out:
  a hole dug in the middle of a nine-by-nine square and filled from a bucket keeps the eighty
  squares round it wet (water reaches four blocks every way, diagonals too, at its own level
  or one below). A new farmer's field is that one square. Once six squares in ten are under
  crops it lays out the squares round it, nine blocks over, each with its own water hole —
  up to three squares by three, twenty-seven blocks across and over seven hundred crops — as
  long as the ground is clear of the town, its buildings and the other fields. The farmer
  keeps back the seed for the squares it is sowing.
* **Ranchers.** Shear the sheep: the wool is the village's beds. They breed sheep
  before cows, and a pen with no sheep fetches a wild one even when it has a pair of cows.
  **Breeding is done the way you'd do it:** the rancher holds the right feed out in its
  hand (wheat for sheep and cows, a carrot for pigs, seeds for hens), the pair come to it,
  and it feeds each. **Fetching a wild animal home** is done the same way: the feed held
  out, the animal following the hand, the rancher walking slowly home and waiting when it
  lags — a lead only when there's no feed to hand.
  **The pen.** Once the village has built its pen (a fenced square with a gate), the
  rancher's ground is the pen and the herd lives inside it. Animals are brought in through
  the gate and led to the far side, well clear of it, before the rancher lets them be; the
  gate opens for a folk going through it and is shut behind them (one you open is yours to
  shut). The rancher looks the herd over every half-minute, and one that got out is fetched
  back.
  Milk the cows with a bucket for the café's cakes; the café sends
  the buckets back. When the pen has no pair to breed, the rancher takes a lead,
  finds a wild sheep, cow, pig or hen, and walks it home. If there is nothing wild
  for fifty blocks, the village buys a drover's pair (two sheep and two hens) once.
  Ranchers draw the smith's shears before making their own. A rancher never takes an
  animal with a name, one kept on purpose, or one in somebody's pen (another ranch, or
  inside fences): a player's animals stay a player's. With no lead of its own it takes
  one from the stores. One it can't get home in three minutes is left until tomorrow.
* **Smelter.** When glass runs short, it first takes any sand in the stores to its
  furnace. Failing that it digs sand off a river or pond bed (the water fills the
  hole), or shaves the top layer off open sand beyond the town, never digging the same
  spot twice. The glass makes bottles for the brewer, the beekeeper and the café, and
  windows.
* **Guards.** When the bell rings, each guard takes a healing potion from the
  stores along with its bow and arrows, keeps one, and drinks it if badly hurt.

**Beds.** Every house has four beds in its drawing. A house goes up with whatever beds
the stores can make that day (three wool and three planks each), and any it still lacks
are brought in later, one at a time, as wool comes in: a bed from the stores, or one made
there and then. With no wool, a bed comes in from the founders' camp, one at a time as
each is laid, so the village never has fewer beds than it had while a house is going up.
**A new house always gets two beds**: with none in the builder's pack and none at the
camp, the builder lays the house's first two beds free (the one thing in the village that
comes from nothing, so a house can take folk in the day it goes up; a town of eight once
stood a week with five houses and twelve beds unmade). Making up the rest has a hand of
its own: one more than the one in eight the town's work may take, so it is never waiting
behind the levelling. And while any house waits on a bed, the stores' wool goes to the
beds, not to washing lines or market stalls.
Bedding lying in the stores is laid out at the camp each morning by the leader for anybody
without a bed. A bed under one of the village's own roofs is a home however high the roof
over it; only a bed down in the ground, in nothing the village built, is passed over. While
folk sleep on the ground the tailor makes beds before rugs and banners, the quest board asks
for wool, and the elder may order the herds grown. A folk with no bed of its own lodges in a bed
another household can spare (see Homes).

**When a building waits.** A project the village cannot start (no lot will take it, the
stores cannot pay for it yet, or a part nobody can make) is set aside for a while and the
next thing on the list goes up. `/village status` says what is set aside and why. The great
buildings (the meeting hall, the chapel, the barracks, the manor) want the most ground of
anything; when look after look finds them no lot, they are let onto steeper ground and
terraced up under the floor, so a mountain town is not kept out of the Iron Age for want of
a flat place for its hall.

**Timber is kept for the builders.** A village short of coal has its idle smelters burn logs
into charcoal, but only logs the builders can spare: the timber its buildings want is kept
back. When the stores run low on logs, past the Wood Age as much as in it, the leader orders
more axes into the woods.

**The age comes first.** In the Stone Age, the plain stone the age asks for is kept back from
the village's looks: a building is made over in stone, or a house rebuilt in it, out of stone
bricks put by and whatever is quarried past the age's need, so a growing town is not kept
out of the Iron Age by its own new walls.

**What an age asks for.** The stock an age wants before the next (timber in the Wood Age,
stone and coal in the Stone Age) is sized to a village of up to twenty-four folk; the larder
still grows with every mouth. A village that grows faster than it gathers is not left chasing a
mark that keeps moving. The leader does not keep the village on "steady as we go" while it is
short of something it could go and get, and once a famine is over it sends the extra field
hands back to their own work the same morning.

**How the village keeps its balance.**

* **Gluts.** A trade whose stores are piled far past any use gets a smaller share of the
  village. With food over four larders the farms are halved (over two, cut by a quarter),
  and logs and stone likewise. The mines are never cut while the age is short of iron or
  diamonds. Hands a trade can spare go to whichever trade is a hand and a half short; if
  that trade has no ground to be had (no water within reach for a fisher), to the next one
  short that has, the couriers (whose ground is the storehouse) as often as not.
* **Iron first.** An iron vein never uses up a miner's vein budget, and is dug before
  any other ore it finds. The smelter fires ore before sand, and fetches the raw iron
  the carriers have brought to the stores.
* **Every run cuts fresh rock.** A miner goes back down the same stairs run after run;
  its galleries are measured in fresh rock, so each run walks along the old tunnel to the
  face and cuts new ground (and walks past its own torches). Between runs a miner stays
  with its mine rather than going up to quarry stone at the surface, or across the village
  to clear out old chests.
* **The gallery follows the rock.** A mine on low ground (a flat world, a hill standing on
  one) is floored at or above the plot itself, so the miner first goes down to the bottom of
  the rock under its plot. Its gallery opens the way with the most rock left in it, goes on
  while there is rock ahead, turns along whichever side still has some (at the patch's edge,
  at the far side of a hill), and walks its own old workings to rock beside them; the run is
  over when none of that is left. A gallery never cuts the floor of its own stairs.
* **A spent mine is left.** Three galleries in a row that come home with next to nothing
  and the miner stakes fresh rock somewhere else round the village, rather than going back
  to the same dug-out hole between odd jobs. With nowhere else to go yet, the next empty run
  looks again.
* **Idle hands help the builder.** A folk whose trade has nothing to do, and that can
  fetch nothing the village is short of, goes and helps whoever is raising the village's
  building. Each helper (up to three) makes the blocks go down a tick faster.
* **Market day sells the surplus.** Travelling traders buy logs, cobblestone and food
  far over a reserve (up to three stacks each) for coin into the treasury.
* **The stores grow.** The storehouse has no bottom (see *The Village Storehouse*). Before
  there is one, when every store is full, another chest is set down beside them, made of the
  stores' own planks.
* **One production chest a worker.** Every farmer, woodcutter, miner, fisher, rancher,
  hunter and beekeeper keeps **one production chest** on its own plot: a farmer's in the
  corner of its field nearest the town, the others just inside the edge of theirs on the
  town side, always **on the plot's own level** (a step or two up or down at most): where
  that edge is the top of a cliff over a riverbank, the chest goes down by the middle of the
  plot instead, and a chest left up a cliff from an older game is given up for the couriers
  and a new one set down where the worker can reach it. It is the folk's own chest, or one from the stores, or one made of the stores'
  planks. Everything it makes goes in there, not on a walk to the storehouse, and it is
  **paid for it as it puts it in**. When the chest is full, the load goes to the stores
  instead. That chest is where the couriers know to come for it.
  * **It moves with the plot.** When a worker's plot moves on (a miner's seam runs out, a
    hunter's game goes, a rancher moves into the new pen), it does not leave its chest
    behind and set down another. It walks back to the old one, takes what is in it and the
    chest itself, sets it down at the edge of the new plot and puts the goods back in. If
    its pack could not hold what is in it, or it cannot get there, it leaves it for the
    couriers: they empty it into the storehouse and take the chest up, and the worker has
    another from the stores. A worker never has two.
  * The smelter and the crafts keep no chest of their own: the forge and the workshops are
    a few steps from the storehouse (the couriers take what the furnaces have made straight
    out of them), and the crafts work out of the stores and into them.
* **The storehouse runs the couriers.** The **couriers** are the storehouse's staff: they
  work out of it, wait at its door between runs, and are paid as its staff (the hauler's
  rate; the wages page says *courier of the storehouse*). They do not choose their own
  rounds: the storehouse keeps a **run list**, and each courier takes the next run on it —
  sent out by the storekeeper when it is at the counter, or straight off the list when it
  is not, so nothing waits on one pair of hands. Best first:
  1. a worker's **kit** (seed, saplings, torches, feed, arrows, its **rations** while it still
     has a meal or two left, and a **spare tool** off the rack before its own wears through)
     carried **out** to it, when it is far out on its plot and asks for it: it keeps working
     instead of walking in;
  2. **ore and fuel out to the smelter** when it runs low (or the stone and clay for its
     masonry);
  3. the **production chests**, the fullest first, and of two as full the one that has
     waited longer; a hungry village's food before anything else;
  4. a worker far out with a heavy pack and no chest: its load, off its back;
  5. what the furnaces have made, and any other of the village's chests out on the plots;
  6. with nothing else, an **old chest** to clear into the storehouse and take up.
  A courier does the run, carries the goods into the storehouse (stacked onto what is there),
  **reports back at the door**, and the run goes into the storehouse's books. A courier
  leaves the seed, the saplings and the planting carrots where they are, and a production
  chest is never cleared away as an old chest. A village takes on its first courier from its
  sixth folk, then one for every five workers on plots. Builders still fill their packs
  from the stores themselves.
* **Work never stops for want of a player.** Every plot keeps its own chunks loaded, the
  whole of it: a field grown twenty-seven across, a wood, hunting grounds forty across. A
  folk out past the village's loaded ground (a walk to a far field, a fetch across the map)
  carries a window of loaded chunks with it, so nobody stops dead in a chunk nobody is near.
* **Nobody stands about.** A folk whose trade has nothing for it, and that can fetch
  nothing the village is short of or help the builder, finds something anyway, whatever
  its trade (except a courier, whose place between runs is the storehouse door). It clears out an old chest into the storehouse, takes what it carries to the
  stores, or goes to a woodcutter's or a miner's ground and brings timber or stone home.
* **Nothing out of nothing.** Every block the village puts down is paid for: by the
  builder out of its pack, or by the town out of the stores. That goes for the chimney
  fires, washing lines, signs and lamps, the roads and bridges to its colonies, the jetty
  and its boat, garden fences and flowers, the earth that levels a lot, the gravestones,
  and a house's second storey and the beds in it. When the stores are short of what it
  takes, the work waits until they have it. What is taken down to make way (a stripped
  roof, the earth cut off a knoll) goes back into the stores.
* **A trade needs somewhere to work.** No shopkeeper before there is a shop, no cook
  before the café, no smith, brewer, tailor or enchanter before their building. No
  storekeeper or carrier before there is a storehouse. A folk in a trade with nowhere to
  work at it takes up whatever the village is shortest of.
* **The forge.** A smelter has at least one furnace, and while there is ore enough to keep
  more busy it adds more, **up to four, side by side in a neat row facing the same way**.
  Each is made of eight of the village's stone. A smelter short of a furnace fetches the
  stone straight away, even in the village's first half hour.
* **Lost on the way back.** A folk walking back to its plot that gets no nearer four times
  running is put on its plot.

**Tools, potions and clothes.**

* **The rack of spare tools.** The storehouse keeps spares ahead of need: a pick for every
  four miners, an axe for every four woodcutters, a blade for every four of the watch and the
  hunters, a hoe for every eight farmers, a rod for every four fishers and shears for the pen
  (at least one of each that anybody uses, never more than eight). Whoever is at the stores
  with a bench makes them — the storekeeper at its counter through the day, anybody at the
  heart of an evening or with nothing better to do there — two at a time, out of the stores'
  own goods: three cobblestone (or iron) and two sticks for a pick or an axe, the sticks out
  of the stores' sticks, else planks, else a log sawn for them, and what is left of the log goes
  back; a rod of three sticks and two string, shears of two iron. Stone, once there is stone
  to spare (in the Wood Age the builders' stack comes first, and with none a wooden one); iron
  once the village has come to iron and is not putting it by for its age, never the smith's
  last bars. Nothing goes on the rack while the founding stores are the storehouse's.
* **A broken tool is replaced from the rack.** A hand whose tool is gone takes a spare there
  and then (booked out in the storehouse's books), by day or of an evening, so a pick that
  broke at dusk is replaced before the morning. One whose tool is nearly worn through takes
  its spare before it breaks — brought out by a courier if its plot is far out, so it works
  on. A hand with a full pack banks its load first, to have room for it. With the rack empty,
  it makes itself one of the stores' stone and a stick, as before. Wear is as it was: a spare
  wears through like any other tool.
* **The best tool of the trade.** Every few minutes a miner, woodcutter, farmer or guard
  takes the best tool of its kind the stores hold, if it beats its own: the smith's iron
  and the enchanter's work. Its old tool goes back.
* **The watch's kit.** Guards put on the best armour the town has made them (leather, then iron,
  then diamond) and take its best blade, free: see *The watch's kit*.
* **Potions at work.** A miner deep down takes fire resistance (or night vision), a
  carrier swiftness for its rounds, a fisher in the water water breathing. Anybody badly
  hurt sends for the brewer's healing.
* **Boots.** The tailor makes leather boots in the village's colour, and anybody without
  boots takes a pair.
* **Bone meal and beetroot.** Village farmers grow beetroot as well as wheat, carrots and
  potatoes. They use bone meal on the fields, made from seed the stores can't use and
  from the watch's bones.

**Where the fields go.** A farmer puts its field on the bank of the water nearest the
village, just outside the town's own ground, whichever way that is. If there is no
water anywhere near, it takes the nearest good soil: the crops grow slower on dry
ground, and the farmers cut irrigation channels through it from the Stone Age.

**Where you come in.** What the village can never make for itself, it will buy:
blaze rods and powder, nether wart and soul sand, slime balls and leads, flint, sand
and bones are all on the stalls' buying list. The quest board asks for what a trade is
actually running short of: blaze powder for the brewing stand's fire, lapis or cane for
the enchanter, feathers for the watch's arrows, a lead for the rancher, cocoa for the
café, wool for beds when folk are sleeping on the ground, and so on. Or give it straight to the folk whose trade needs it (a gift from the
talk screen): a brewer handed blaze powder, a rancher a lead or an enchanter lapis
thanks you for it warmly and puts it to work that day.

Ask any folk **"How does your trade work?"** to hear its own account of its work:
its tools, what it uses, where its work goes, and what it is short of. **"What is
the village short of?"** gives the next age's wants and every trade's empty
shelves, with a nudge to bring them to the stalls or look at the quest board.

### The buildings

**Building as fast as the town can afford.** A town that is getting by builds one thing at a time,
with a few minutes between projects. A thriving town builds faster: food to spare (the leader's
plan is *plenty*), a bed for all but a few, and no raid at the gates. It has two crews raising two
different buildings at once from twelve folk, and three from forty. It waits half a minute
between projects, and it builds its houses ahead of the folk who will want them. Fall short of
any of those and it goes back to the careful pace.

**A way in at every door.** A building is laid out at one height, but the ground in front of
its door is whatever the world made. Now and then a door opened onto a bank of earth a block
high, or over a drop too deep to step up from. On its rounds of the streets the town checks its
buildings' doors one by one. Wherever the way in can't be walked, a hand comes to fix it: earth
in the doorway is dug out, a doorstep is laid level with the door, and steps are cut into the
bank or built up out of the hollow, a block at a time, until the way meets the ground. Only the
ground the world made is dug. Nothing built is touched, nor anything under a porch roof. The
blocks come out of the stores, and what is dug goes into them.

Every building is drawn. Each drawing is a text file in
`data/mc_assistant/blueprints/`, and `tools/blueprints.py` renders them.

What a building is made of depends on the village. The drawing says what each block
is for: a stone footing, a log frame, plank walls, a roof of stairs. A builder lays
the village's own materials there: its oak, spruce or birch, and its cobble.
It cuts the roof's stairs and slabs, and the doors, from the stores' planks, the
window panes from glass, and hay bales from wheat. Whatever it can't have, it builds
with plain blocks, so a building is never held up.

| Building | Size | What it is |
|---|---|---|
| House | 9×9 | Timber-framed cottage on a stone footing, steep roof with a chimney, glass windows, lanterns by the door. Inside: bench, furnace, chest and **four beds** |
| Guest house | 9×11 | The house made finer for an honoured player: porch on posts, flower boxes, a rug, one bed |
| Storehouse | 7×9 | Log-framed shed with a gabled front round the Village Storehouse (27 units, 3×3×3), barrels by the door |
| Shelter | 7×7 | Four log posts, a low stone wall and a pitched roof |
| Well | 5×5 | Stone curb round water, four posts, a little roof with a hanging lantern |
| Smeltery | 9×9 | Stone forge open to the street, three furnaces under a brick chimney, anvil, bench and chests |
| Workshop | 9×9 | Timber workroom with a wide door, a bench and the tailor's loom, furnace, barrels and a hayloft |
| Granary | 7×7 | Squat store on a stone base, full of hay, three chests, hipped roof |
| Market | 11×11 | Open hall on log posts under a broad hipped roof, stalls of hay and barrels, a fountain, lanterns hung from the eaves over the stalls |
| Meeting hall | 11×19 | Long timber hall: tall windows, a pair of doors up steps between lamp posts, a long table, lanterns hung from the rafters down both sides, the elder's seat |
| Chapel | 9×21 | Stone nave with tall windows, a bell tower over the door (a ladder up to its bell), pews, an altar and lights hung from tie beams |
| Barracks | 9×15 | Stone-and-timber dormitory with six bunks, chests, a bench and an anvil |
| Watchtower | 7×7 | Stone tower three storeys high, ladder inside, battlemented deck with a lookout roof |
| Lighthouse | 7×7 | Tall banded stone tower, ladder all the way up to a railed gallery round the glass lamp room, pointed roof |
| Monument | 7×7 | Stepped plinth, banded pillar, lanterns at the corners and on top |
| Gateway | 9×5 | The Nether Age's obsidian frame on a stone dais between lantern pillars |
| Wall | ring of 27 | Round the square: battlements, a gate onto each avenue, lantern pillars, corner towers |
| Pen | 7×7 | Fence ring with a gate, once there is a rancher |
| Stable | 9×9 | Tall timber barn on a stone footing: four fenced stalls either side of an aisle, hay at the front and in the loft, a sunken trough and a cauldron, three gates across a door four high, once the village has horses |
| Tavern | 11×11 | Broad timber inn: stone hearth with its fire and chimney, a bar of casks, tables and benches, note blocks, lanterns |
| Graveyard | 9×9 | Fenced plot with a gate, a path to a stone cross, lanterns on the corner posts, twelve graves |
| House, grown | 9×9 | The family house with a second storey: a ladder up to two more beds and a chest under the eaves |
| Café | 9×9 | Bright timber room with big windows on the street, a counter of casks, a smoker behind it, little tables and chairs, flowers by the door |
| Shop | 9×9 | Timber shopfront with a window either side of the door, a counter of casks with the goods on it, shelves of barrels behind |
| Smithy | 9×9 | Stone forge open to the street between log pillars: two furnaces under a brick hood, the anvil, a grindstone, a quenching tub, a bench and chests |
| Brewery | 9×9 | Timber still-house on a stone footing: two brewing stands on a stone bench, cauldrons, casks, a window to the street |
| Library | 9×9 | Stone hall with tall windows, walls lined with bookshelves, an enchanting table on a carpet between lecterns |
| School | 9×11 | Timber schoolroom under a steep roof, tall windows down both sides: two rows of desks (a top slab, a stair for a bench) either side of the aisle, the teacher's lectern, and at the back a blackboard over a cupboard of barrels, a bookshelf either side with a lamp on it; a lamp hung under the ceiling over the desks by each wall, none in the aisle |
| Bank | 9×11 | Stone house of business: a counter across the room with a lantern on its far end, the ledger on a lectern before it, and a vault at the back, its strongboxes behind a gate and grille of iron bars the banker sets; its two lanterns hang flush under the ceiling, over the counter and over the strongboxes |
| Block of flats | 11×11 | A stone town house three storeys high (four in the Diamond Age) with brick quoins, balconies, window boxes, a pedimented front door between lanterns, a slate roof with a dormer and two chimney stacks: six small flats off a winding stair (see Blocks of flats) |
| Park | 11×11 | A green: a stone fountain with a spring spilling from its pillar, sixteen wooden benches round it, lamp posts at the four ways in, flowers along its edges; then a tree in each corner, paths and lanterns (see *The town's quarters, and the park*) |

Every drawing is sound as built: a hanging lantern hangs from a beam, a ceiling, the rafters, an eave or
a beam end, never from thin air, and indoors it hangs over a bed, a table or a counter or by the wall,
not over the middle of the floor; a torch or a standing lantern stands on something; a ladder has a
wall at its back; a door opens onto a floor with headroom; water is held in its basin; and every bed,
bench, bell and storey can be walked to from the street, up the stairs and the ladders, in the
buildings the Iron Age puts a second storey on as well. (A test reads every drawing and checks it.)

Builders take the place the town plan has for the building. Of the first few good
lots they pick the one that costs least to build on. They level it (filling the low
side, felling any tree in the way and keeping the wood) and build from what they
actually carry.

`/village showcase buildings` (an operator command) sets every building out on a
stage to be looked at. `/village showcase town` lays out a whole grown town to the
plan.

## The Village Storehouse

One store for the whole village: a cube **three blocks wide, three deep and three high**
that starts with **729 slots** (as much as twenty-seven chests) and **never fills**. Whenever
fewer than two rows stand empty it grows nine more, so the harvest always has somewhere to go.

* **Made of storehouse units.** A unit is a crate: four planks and four sticks
  (plank, stick, plank / stick, empty, stick / plank, stick, plank). Stack 27 of them in
  a 3×3×3 cube and they join into a Village Storehouse, with a door in the middle of the
  bottom row of the front (the side facing you as you lay the last one). Right-click any
  face to open it.
* **Its screen** shows six rows at a time. Scroll with the wheel (shift scrolls a page)
  or drag the bar through all of it, however big it has grown; **Sort** puts like with like,
  tops stacks up, and puts them in order. It shows how many stacks it holds. Shift-click from your pack puts things
  anywhere in the store, not just the rows on screen.
* **Taking it apart.** Break a unit and it drops; the rest go back to loose units, and
  the goods wait safely in the door unit until the cube is whole again. Break the door
  unit itself and the unit you pick up **carries the goods** with it (its tooltip says
  how many stacks): set it down in a cube again and they are back. It does not burn,
  pistons cannot move it, and explosions barely mark it.
* **How the village uses it.**
  * A new village carries its 27 units in its founding stores, and its first building,
    the storehouse shed, is built round them: the builder fetches them from the stores
    and lays them **one by one**.
  * A village whose shed was built before there were units has the units laid into its
    shed (made from the stores' planks, six a unit). A village with no shed puts its
    storehouse up on a lot of its own on the square.
  * Folk set no chests of their own down except a producer's one **production chest** on its
    plot. Everything they make goes there (or to the stores when it is full), and the
    couriers bring it in: to the storehouse once it stands, the chests at the heart before
    that. Their tools, seed and supplies are fetched from there.
  * **Things stack the way you would stack them.** Whatever goes into the stores — a
    courier's load, a sale, a maker's work, a caravan home — goes onto the part stacks of
    the same thing first, wherever they are in the storehouse and the store chests, and only
    then into an empty slot, a full stack at a time. A thing with a maker's mark or an
    enchantment keeps to its own stack, as it would for you.
  * **The old chests are cleared out.** Once the storehouse stands, the couriers (off the
    bottom of the run list), and anybody with nothing else to do, walk to the chests folk
    set down over the years, empty them, take the chest up and carry the lot into the
    storehouse. A chest that is part of a building is left where it is as furniture and is
    no longer one of the stores. A worker's production chest, and one it is carrying along
    to a new plot, is never touched. Nor are your guest house and any chest with a sign on it.
* **The storekeeper keeps the storehouse.**
  * **In order.** While it is on duty it tidies the storehouse **once a minute**, and at once
    after a big delivery: like with like, every stack topped up, in order by kind — food, crops
    and seed, timber, stone and earth, ore and metal, cloth and hides, tools and arms, then the
    rest — and it tops up the part stacks in the store chests round about. It sorts the store
    chests into the storehouse too.
  * **At the counter.** A folk who comes to the storehouse for something is served by the
    storekeeper when it is on duty there (awake, at work, not on its break, and at the
    storehouse): a moment at the counter, and *"Sixteen torches for you, Holt."* A folk that
    draws on the stores from its plot is handed it the same way. With nobody at the counter
    (no storekeeper, or asleep, or out) folk help themselves, as they always have, so the
    village never waits. A storekeeper with couriers to run keeps its counter all day; one
    without bakes and builds between tidies.
  * **The couriers.** It sends them out on their runs (see *The storehouse runs the couriers*).
  * **The books.** It keeps a day's books: what went in and came out, by item; who brought
    it and who took it; the requests it served and the ones folk served themselves; the
    couriers' runs and what they carried; and how the last tidy went (the slots used before
    and after, the stacks merged, the slots free). They are on the **Stores** page of the
    town's books, with the storehouse's staff (the storekeeper and each courier: runs today,
    goods carried, what it is doing now) and the run list (under way and waiting), and in
    chat with `/village stores`. A courier's *About* card says it works for the storehouse,
    under the storekeeper.
  * You can place one yourself within about 38 blocks of a village's heart and the
    village will use it.

### The street sweeper

Things lie about a town: saplings off the trees felled for its buildings, seed the birds
kicked out of the grass, an egg a hen laid on the square, the bones and string the watch
left after a night's fighting, the blocks a builder knocked out of the way, a tuft of wool.
Every one is something the server ticks for five minutes, and something the village could
have used. **The storehouse keeps a sweeper to see to them.**

* **Who.** Once a town has its storehouse and **16 folk**, one of the storehouse's couriers
  takes up the broom (*"A broom, is it? Right — the streets it is."*), and one more for every
  16 folk after that (three at most). The town wants a courier more for each, so the runs are
  not left short, and the storehouse's **last** courier is never made its sweeper. It is paid
  as the storehouse's staff, like the couriers. In a **smaller town** (or while the sweeper is
  asleep or off work) a courier with no run sweeps between runs instead: a few heaps, then in
  with them, and back to the door.
* **Where.** The town's streets and squares: anything lying in the open within the town's
  reach. Not indoors, not down a hole or a mine, not in the water.
* **What it leaves.**
  * **Anything a player threw or dropped**, or that fell when a player died — always, however
    long it lies. (No folk picks those up in passing either: they are yours to come back for.)
  * Anything at all for a minute while **a player is near**: it may be theirs, out of a block
    they broke.
  * The ground of a **building going up**, while the builders are at it.
  * Anything on **a worker's own ground**, or beside a hand at its work, for two minutes: the
    woodcutter sweeps up its own saplings between fellings, the farmer and the miner take up
    what they knock loose. The sweeper takes only what they have **left lying**.
* **Where it goes.** Into the storehouse (and the store chests beside it), onto the stacks
  there like everything else, and into the storehouse's books as **swept in**, by whom, item
  by item.
* **Seeing it.** A sweeper's *About* card says *Street sweeper* and how much it has swept in
  today, and the line at the top says what it is doing (*"For the storehouse: sweeping the
  streets: going for three oak saplings"*). The **Stores** page lists it among the
  storehouse's staff with what it swept today, and shows how much is **lying about the town**
  now (*"lying about the town: 12 items (5 for the broom)"*); the **Overview** page shows the
  same count at the end of the line under its cards, so you can watch the clutter (and the
  load on the server) go down. `/village sweeper` says it all in chat.

### No phantoms over the villages

The folk keep their own hours, and a town is no place for phantoms. While a village stands,
**no phantom comes over it of itself**: a player who has not slept, standing within the
town's reach (and twenty-four blocks round it), has no phantoms sent after them there, and a
phantom that strays in over the town from outside is seen off in a puff of smoke — nothing
hurt, nothing dropped. One you brought (a spawn egg, a spawner, a command, a name tag) is left
alone. And **a phantom never goes for a folk**, whatever the settings: it hunts players, as
in the game. Turn `villagePhantoms` on in the config to let them come over the villages as
they always did.

## How they look

A folk has a villager's head and nose on a body that can hold things: real arms
that swing as it walks, carry its tool in its hand and swing that tool when it
works. Who it is and what it does are two different things, and both show:

* **Who it is** — one of ten faces, chosen by its id so it always looks the same:
  six skin tones, seven hair colours, short, long, cropped or balding hair, blue,
  green, brown or grey eyes, and a beard on some.
* **What it does** — every trade has its own clothes and kit:

| Trade | What it wears |
|---|---|
| Newcomer | An undyed wool tunic to the shins, a rope belt, a hood in its own colour |
| Farmer | Denim overalls over a checked shirt, a neckerchief, a broad straw hat with a red band, a seed pouch |
| Lumberjack | Flannel in its own colour, braces, canvas trousers, a knitted cap with a bobble, a beard, the day's logs on a frame on its back |
| Miner | A dusty jacket and leather harness, a tool belt, gloves, knee pads and steel toes, a hard hat with a lamp, a lantern on its belt — both glow in the dark |
| Rancher | A leather waistcoat over a shirt in its own colour, chaps, a wool shawl, a wide hat with its brim turned up, a coil of rope |
| Guard | A quilted gambeson under a tabard in **its village's colours** with the village's gold bell, a kettle hat, a shield in the same colours on its back, a scabbard |
| Smelter | Short sleeves, heavy gauntlets, a scorched leather apron with tongs in its pocket, goggles pushed up on its forehead |
| Fisher | A yellow oilskin coat and sou'wester with a long back brim, tall rubber boots, a wicker creel on its back |
| Storekeeper | A white shirt with sleeve garters, a waistcoat in its own colour with a watch chain, pinstripes, spectacles, a derby, a ledger at its belt and a quill behind its ear |
| Hauler | A canvas jacket with four pockets, a scarf in its own colour, puttees, a tweed flat cap, and a big pack with a bedroll and a pan; it leans into the load as it walks |

A guard that has been given armour wears it, and its helmet goes on instead of
its hat. Every village has its own colours, so you can tell whose watch you are
looking at. The trade's tool still floats over a folk's head from far enough off
that its clothes cannot be read, and a barrier floats over one that has stopped
because it is missing something.

`/village lineup` (operators) stands one folk of every trade in a row in front
of you, dressed and holding its tool. `/kill @e[tag=folk_lineup]` clears them.
The pictures and the shape are made by `tools/folk_art.py`, which writes both.

## People, not workers

Every folk is somebody. Each is born with two traits, and they change how it lives:

| Trait | What it does |
|---|---|
| Hardworking | A short break, and first to bed |
| Easygoing | A long break |
| Sociable | Warms to people fast, seeks company on its break and stays up latest |
| Shy | Warms slowly and keeps to itself |
| Cheerful | Everybody warms to it a little faster |
| Grumpy | Warms slowly, and sometimes takes against people (two grumps most of all) |
| Generous | Hands rations to a friend who has none |
| Curious | Spends its time off wandering the edges of the village |

**Friendships** grow out of time spent near each other: working side by side, a break
together, the evening at the heart. They drift a little each day, so the ones that
last are the ones kept up. When two friends meet you see it: they turn to each other,
there is a happy face and a villager's murmur; two rivals scowl (an angry face) and
walk away from each other.

**Partners and families.** A folk raises children with its partner if it has one,
else with whoever it is closest to; the two who raise a child are partners from then
on. Partners take their break at the same hour and spend their evenings side by side
(hearts between them). A child knows whose it is and takes one trait from a parent.

**The day** runs like anyone's: work, one break at the folk's own hour, then at dusk
the village gathers at the heart — partners together, friends with friends, the shy
at the edge — until bedtime (later for the sociable, earlier for the hard workers),
when everybody goes to a bed of its own anywhere in the village and sleeps until
morning. Builders make the beds for the houses from wool and planks in the stores, and
a rancher has shears made once the village has iron, so the wool keeps coming.

**Everybody sleeps, every night.**
* **The founders' camp.** Settlers bring their bedding and lay it out round the
  stores: a ring of beds about the heart, one each. Nobody spends the first nights on
  their feet. As houses go up, a builder with no wool for new beds carries beds in
  from the camp, and the camp empties into the houses.
* **A bed for everyone.** Once the buildings its age asks for are up, a village keeps
  building houses for as long as it has more people than its homes have beds.
  `/village status` tells you how many have a bed, how many are asleep, and how many
  beds are still at the camp.
* **Families sleep under one roof.** A folk takes the free bed nearest its partner's,
  and a child the one nearest its mother's or father's.
* **The night round.** A guard on watch walks the ring street round the square, stop to
  stop past each gate, rather than the corners of its own plot.
* **Children share.** Two children count as one bed's worth when the village works out
  how many houses it needs. While any grown-up has no bed, a child sleeps by its
  family's bed instead of taking one.
* **The watch sleeps too.** The guards keep the night in two watches. Half stand the
  first, from dusk to midnight, then go to bed. The other half sleep first and take
  the second, from midnight to dawn. A village with one guard has it watch until
  midnight and sleep after.
* **Work stops at bedtime.** Whatever is left of the day's job waits for the morning.
  The village's builder picks its building up again then, and the miner its mine.
* **Nowhere to sleep?** Ask a folk with no bed "Can I help?" and it asks you for one.
  Bring any bed and it lays it out by the heart and sleeps in it that night.

**Seeing it.** Sneak and right-click a folk: its traits are on the title line, and
hovering the title shows its partner, friends, anyone it does not get on with, its
family, how it feels, what it loves doing and what it hopes for.
`/village people` lists everybody that way, under a line saying how the village hangs
together ("20 folk: 3 couples, 9 friendships, 1 rivalry; 4 born here"), and
`/village status` carries that line too.

## A life of its own

Besides its two traits every folk has:

* **A pastime** — fishing, stargazing, gardening, music, reading, long walks, cards
  or whittling — and two evenings in three it goes and does it:
  * A fisher sits at the water's edge with a rod and brings its catch home to the
    stores.
  * A gardener plants flowers by its door, and over a long game the village fills
    with them.
  * A musician plays at the well, and the folk who like a tune come and stand round.
  * A stargazer climbs to open ground once it is dark and looks up.
  * A card player finds a friend, and they like each other better for the game.
  * A whittler carves bowls by its door.
* **A quirk** (it hums while it works, it is afraid of the dark, it tells terrible
  jokes…), **a favourite food**, **something it loves to be given** and something it
  can't abide.
* **A dream** — to be the best at its trade, to raise a family, to have friends all
  over the village, to find a diamond, to grow the finest garden, to see a great work
  raised, to see the village reach the Nether Age, never to go hungry again. The game
  sees it come true. When it does, the folk says so, the village hears about it, and
  it never forgets.
* **A mood**, worked out from its own life:
  * Up: a night in its own bed, food in its pack, a partner, friends, a present, an
    evening at its pastime, the village coming of age, a day of rest, a thriving village.
  * Down: hunger, sleeping rough, loneliness, rain (unless it likes rain), work it
    can't do, being hit, a hungry village, grief for somebody lost, a quarrel, a
    miserable village.
  * A happy folk works a little quicker, and a miserable one slower.
* **Needs.** Ask how it is and it tells you what it could do with: something to eat,
  a bed of its own, a good night's sleep, a friend or two, time for its pastime.
* **Memories** — its children born, the ages it saw the village come into, a friend
  lost, a present from you, the day you hit it.
* **What it thinks of you** — each player separately.

The village keeps its own **news**: who is together now, who had a child, what went
up, who died, whose dream came true. Folk pass it on.

### How the village is doing

Every village has a **contentment** score out of a hundred, shown by `/village status`.
It is made up of:

| Part | Up to | What counts |
|---|---|---|
| Food | 25 | The larder against what the village would want put by for a child |
| Homes | 20 | Folk with a bed of their own; less if there are more folk than homes |
| Mood | 25 | Its people's moods, on average |
| Safety | 10 | Less after a death, while the bell rings, or with no wall from the Stone Age |
| Things to enjoy | 10 | Two each for a well, a market, a café, a tavern and a chapel |
| Wages | 5 | Paid this morning or yesterday |
| Rest | 5 | A day of rest kept this week |

What the score does:

| Score | Word | Effect |
|---|---|---|
| 80+ | thriving | Folk work 10% faster and raise children readily |
| 60+ | content | Folk work 5% faster |
| 40+ | getting by | No change |
| 25+ | unhappy | Folk work 5% slower and grumble about what is wrong |
| under 25 | miserable | Folk work 10% slower. After three miserable days, somebody leaves |

Leaving works like this:

* One folk leaves at a time, every other day at most, and never below eight. The one
  with least to keep it goes: no partner, not the elder, not on the watch, the
  fewest friends.
* It goes to the happiest village nearby with room, or off into the world. The
  village's history records why.

Better tools mean faster work too:

* The right tool sets the pace by its tier, from wood to netherite. Bare hands, or a
  tool that is no use on the block, are slower than the worst tool.
* Hard blocks (ore, deepslate, obsidian) take longer than earth and stone.
* Each level of Efficiency on a tool makes it an eighth quicker.
* The crafts work quicker with their own building, and slower without it.

**On the move they act like players.** On a long walk a folk breaks into a run, now and
then jumping in its stride on open ground. It stops running when it gets there, when it
reaches water, or when it is too hungry or too old to run.

**Children** come more easily in good times and less in bad, but never stop while
there is any food at all:

* A thriving village needs only three fifths of the usual larder put by, and has a
  child at one chance in two. A content one needs four fifths, at one in three.
* In lean times (two fifths of the larder) a child still comes, at one chance in
  eight and at a third of the usual pace.
* A widow or widower may love and marry again.

**Quarrels.** Most folk get on. Now and then two whose natures rub (the tidy and the
easygoing, the chatterbox and the quiet one) fall out. Two who can't abide each
other occasionally have words when they meet off work:

* They face each other, say their piece, and stalk off.
* Both are cross for the day.
* Once in a while it clears the air and they make up.

### Getting quicker: experience and tools

Every folk gets quicker at its work the longer it does it, and quicker again with a better
tool. This holds for every trade.

* **Experience.** Each level at its trade makes a folk's work 3% quicker: 30% quicker at
  level 10, 60% at 20, 90% at 30 (nearly twice a new hand's speed) and 150% at level 50, the
  most a level goes. All told, with its mood, its crew, its nature, the town's research and its
  knacks, the quickest hand in a town works up to three and a half times a new hand's speed.
  (It used to come in three steps: 10% at level 10, 20% at 20 and 30% at 35.)
  The level is the one for the trade it works now. A farmer of level 18 who takes up mining
  starts at nought down the mine, and has its 18 again when it goes back to the fields.
* **Tools.** The tool of the trade sets the pace by its tier. Against six seconds a stroke
  with wood, stone takes 5.3, iron 4.5, diamond 3.8 and netherite 3. With no tool at all it
  is 7.5. These are a new hand's times; a village folk at its own trade works much quicker,
  but the tiers keep the same proportions. A village gives its folk better tools as it can
  make them:
  * The founders bring stone picks, axes and swords. Children get wooden ones, and as soon
    as they take up a trade they make a stone tool from three cobblestone and a bit of wood
    from the stores.
  * Farmers make a stone hoe the same way, and hold it while they work. A farmer with no
    hoe is the slowest farmer of all.
  * Hunters swap their wooden swords for stone ones. The smith's iron blades go to the watch.
  * Once the smith is at work, its iron picks, axes, hoes and swords in the stores go to any
    miner, woodcutter, farmer or guard whose tool they beat. The enchanter's Efficiency on
    top makes a tool an eighth quicker for each level.
  * A Diamond Age village makes iron picks for its deep miners. A Nether Age village makes
    one diamond pick, for the miner who cuts the obsidian.
* **Everything else.** On top of its level a folk's pace is also changed by:
  * its mood (from −10% to +8%);
  * how the town is doing (from −10% to +10%);
  * its leader (from −8% to +10%);
  * how its nature suits the trade (from −20% to +20%);
  * working beside its crew (up to +10%);
  * its quirk;
  * the town's research and its own knacks.

  Altogether a folk is never more than 55% quicker, nor more than 30% slower. A hungry folk
  works slower than all of that.
* **Old age costs nothing.** From sixty a folk is old, and works on at its trade all its days
  as quick as it ever was, walks and runs as briskly as anybody: that is just how it is in the
  village. (It once worked five to ten percent slower and walked slower too.)

What gets quicker, trade by trade:

| Trade | What gets quicker |
|---|---|
| Miner, woodcutter | Every block and every log (pick or axe tier) |
| Farmer | Every crop cut, every square tilled and planted (hoe tier) |
| Rancher | Every sheep shorn and every animal fed |
| Fisher | The wait for a bite. Half its pace counts, since the fish bite when they bite: up to about a quarter off |
| Smith, tailor, cook, shopkeeper, brewer, enchanter, beekeeper | How often it makes something: every 20 s for a new hand, every 14 s at level 30, every 9 s at the very most |
| Builder | Every block laid: 6 ticks for a new hand, 4.2 at level 30. Its level here is its trade's, or its *building* level (from the blocks it has laid) if that is higher. Its mood, the town, its years and the town's research count too |
| Carrier, storekeeper | Handling each load. A carrier, and a scout too, also walks a quarter of a percent quicker a level, up to 5% at level 20 (on top of the Swift perk) |
| Smelter | Not the smelter: the furnace sets the pace, ten seconds a smelt |

**See it for yourself.** Right-click a folk. Its About page has a **Pace** line, for example
"23% quicker than a new hand: level 18 (+18%), stone axe (5.3 s a stroke against 6 for
wood), content (+4%), the town's research (+3%)". A builder's line also gives its building
level and how often it lays a block.

### Growing up, growing old

* **Apprentices.** From its second day a child spends its mornings at a grown-up's
  side at work, watching and having a go. The teacher is a parent at work, or else the
  most practised hand at the trade the village needs most. When the child grows up it
  takes up that trade (unless the village has more than enough hands at it) with a few
  levels' knack already. The village's history records who taught whom. Once the village has a
  school, the morning's lessons come first and the apprenticeship has what is left of the
  morning; a schooled child takes up the trade it leaned to at school instead (see *The school*).
* **No trade yet.** A grown folk without a trade (one who has just moved in from another town,
  married into this one, or given up a trade there was no ground for) takes up the trade the
  village is shortest of the first time it looks round by day, a few seconds at most, and then
  goes looking for ground for it. Until it finds some it lends a hand at the heart, baking and
  building. Children have no trade at all until they grow up, so the folk list
  (`/village folk`) and the register (`/village people`) call them *Child*, not *Unassigned*.
* **Age.**
  * A child grows six years a day, and is grown at eighteen, three days after it is born.
  * Grown folk age **a year every five game days**. Round birthdays (thirty, forty…) come
    every fifty days.
  * The founders were grown when the village began. Each comes aged between eighteen and
    forty-five, so they don't all grow old together.
  * From sixty folk are old, and go on working their trade all their days, as quick as ever
    (see *Getting quicker*); age is no mark against them at an election either.
  * Each folk lives to between seventy and a hundred (a tenth more once the town has
    Healers). How long that is in play:
    * a founder: about a hundred and twenty-five to four hundred and ten days, typically
      around two hundred and seventy;
    * a child born in the village: two hundred and sixty to four hundred and fifteen days.
  * A few years before the end (about twelve days), the village hears that they are very
    frail. At the end of their years they die peacefully in their sleep.
  * Ask a folk about itself and it tells you its age. The register gives everyone's, and the
    town's books show them on the Folk page and the Society page (the ages by tens).
    `/village lifespans` gives each folk's age, the age it will live to and the day that falls on.
  * A world saved before this change keeps every folk's age, and from then on they age at
    the new pace.
* **The dead.** Everyone who dies is remembered: name, the days they lived, how they
  died, their parents, their partner and their trade.
  * Once a village has lost somebody it builds a **graveyard** on the edge of town: a
    fenced plot with a gate, a path to a stone cross, lanterns on the corner posts and
    twelve graves.
  * Each of the dead gets a carved headstone and a mound, with their name and their
    days on the stone. When it is full the village builds another.
  * The chapel keeps a **memorial**: boards along the nave, "In loving memory", three
    names to a board.
* **The register** (ask a folk "who lives here?") now lists, after the living:
  * the dead ("In memory");
  * the **family trees**: every couple who came from outside the village, and under
    them their children, grandchildren and great-grandchildren, a dash deeper for each
    generation, with a dagger by those who have died.

### The school

Once a Stone Age village has ten folk or more and three children, it wants a **schoolhouse**
(one of its amenities, after what its age asks for; the council may vote it up the list, the
parents of little ones first). The builders raise it like any other building, out of the stores:
a timber schoolroom with two rows of desks either side of the aisle (a top slab for the desk, a
stair for the bench: eight places), the teacher's lectern, and at the back a cupboard of barrels
with a bookshelf either side. It is rebuilt in stone and slate with the ages, and furnished for
them, like the rest.

* **The teacher.** A grown folk with a trade is asked to teach: an old hand first, then the
  curious, the patient (easygoing), the kind, the readers, and the best at their trade; never the
  elder or the watch, and seldom a village's only smith. It teaches in the mornings and goes back
  to its own trade at noon, and it is paid for teaching on top of its trade's wage (a miner's rate
  at the place's standing: 2 coins a day in a hamlet, 3 in a village...). Its card says
  "Teacher", with what it teaches; the Jobs page has a row for it. The village's history records
  who took the school.
* **The schoolroom put to rights.** Before its first lesson of a morning the teacher puts up what
  is missing, out of the stores: the **blackboard** on the back wall (six blocks of black wool, or
  black terracotta or concrete, a block of coal, blackstone or slate: whatever the stores have),
  a **book on the lectern** (a book and quill or a written book if there is one, else a plain book),
  and the lectern itself or a bookshelf if the builders had no books for them (eight or six planks
  and three books). What the stores cannot pay for waits.
* **Lessons**, on working mornings (never on the day of rest), from half past seven to half past
  eleven (after the morning assembly). Every child from a day old until it grows up goes to its
  desk; the teacher stands at the lectern before the blackboard, wishes the class good morning,
  says what the day's lesson is (a trade a day, one of the class's own), and now and then a line
  of it: "A good miner listens to the stone: a hollow knock means a cave behind it." The children
  answer back. After school, the apprenticeship (see above) has the rest of the morning.
* **What a child leans to.** On its first morning each child takes a leaning to a trade: its
  parents' trades pull hardest, then what it has watched at its apprenticeship, its own nature
  (curious: the mine, the library, the brewery; shy: the hives, the loom, the water; generous: the
  fields and the kitchen...), what it loves doing (fishing, gardening, reading, whittling...) and
  what it loves to be given, and what the village has a use for.
* **Learning.** Every five seconds at its desk with the teacher in the room, a child learns a
  little of its trade, and a little of the day's lesson if that is another trade (a level or two
  at most). A full schooling, about two mornings, is worth **level five** under a plain teacher
  and up to **eight** under a good one: one more for a teacher of level ten at its trade, one for a
  master of twenty, one for an old, patient or curious one. A curious or patient teacher teaches a
  little quicker, a grumpy one slower; a hardworking or curious child learns quicker, an easygoing
  one slower.
* **Truants.** Now and then a child plays instead: an easygoing one most, a grumpy or sociable
  one sometimes, a curious one seldom, a hardworking one never. It learns nothing that morning.
* **Grown up.** A child that went to school takes up the trade it leaned to, at the level its
  lessons made ("Ada grew up and went to work as a miner, level 6 from the school"), with its
  apprenticeship's knack on top when that was the same trade; only if the village has more than
  enough hands at it does it go where it is needed instead, its school levels kept for the day it
  takes the trade up. A child that never went (no school yet, or a truant every morning) starts
  at level nought, as before. A schooled child grown up at level five has its first knack point
  at once (see *Knacks*).
* **See it.** A child's card has a **School** line: "at school: learning to be a miner, level 3
  (2 mornings; its parent Tom is a miner)", or "playing truant this morning"; its talk screen says
  "At school, learning to be a miner" while it is at its desk; ask it what it is good at and it
  tells you. The teacher's card says it **Teaches**: its pupils, the mornings taught, how far a
  schooling under it goes and what it is paid for it. The town's books have a **School** page (see
  *The town's books*), and `/village school` says it all in chat.

### The tavern

Once it has twelve folk, a Stone Age village builds a **tavern**: a broad timber inn
with a stone hearth and its fire, a bar of casks, tables and benches, note blocks in
the corner, and lanterns hung low.

* Off-work folk drop in two evenings in five. They stand about the tables and the
  fire and tell stories, things that really happened, out of the village's history
  ("Remember when the raiders came at the north gate? That was 3 days back."), and
  somebody answers.
* Once there are a few in, the **music** starts: a jig early in the evening, a slow
  air later, with the bass on the note blocks themselves.
* **Buy a round.** Right-click the board on the bar ("Buy a round, a coin a head").
  Everybody in the tavern raises a glass to you and thinks better of you (once an
  evening), and the village remembers it.
* **What is drunk** is the café's: the cook's cider, juices, honey tea and cocoa, out of the
  stores, a bottle a head (the bottles go back), each doing its little good. A folk in for the
  evening with a few coins put by buys itself one. With none in the stores the round is drunk in
  water, and the tavern's books count the drinks it had not got, so the cook makes more.

### The day of rest

Once a week, never on market day, a village past its first week and out of the Wood
Age keeps a **day of rest**. Nobody works but the watch.

* **The morning service.** The bell rings and everybody goes to the chapel, or
  gathers round the well if there isn't one. The elder gives thanks for the week:
  the things in the village's history.
* **Games on the square** (late morning to the afternoon): tag, with the children in
  it too.
* **Walking out** (the afternoon): couples walk together, and the unattached walk
  out with whoever they are sweet on. That is how sweethearts become partners.

Folk feel the better for it the next day.

**Running.** On a long walk, folk who are fed and well break into a run.

### The town bell, birthdays and Founding Day

**The town bell keeps the day.** Three times a day somebody walks to the town's bell and rings
it, as you would — the bell swings and is heard all round:

| Bell | When | Strokes | What the town does |
|---|---|---|---|
| Dawn | 6:00 | 3 | Gets up. Before it the town lies in (the watch excepted); it wakes them, the morning assembly at the board follows, then work |
| Noon | 12:00 | 6 | Everybody's break at once, and the midday meal with it: to the café or the tavern's bar with a few coins (a dish bought out of the stores, the coin into the treasury), else home to its household's table, else to the stores, and it sits down to the meal there (the meals come out of its pack, its home's chest or the stores, as every meal does; nobody eats twice) |
| Dusk | 18:00 | 9 | The day's work stops and folk walk home to their beds; the guards go on watch; on Founding Day the town goes to the board instead |

* **The bell's own frame.** From its second day, once its stores have the timber, the town's
  works build its bell a frame on the square, a few blocks from the board with its front to the
  square: two posts with a beam across, the bell hung between them, a little roof of stairs and
  slabs over it and a lantern under each eave. It stands on level ground of its own, clear of the
  board and its courtyard, the gates' ways in, the market stalls, the well and the monuments, and
  off the worn paths. It is built a piece at a time out of the stores, like everything else: posts
  of logs and a roof of the village's own wood, cut from its planks (six planks make four stairs,
  three make six slabs, the rest of each batch back into the stores); in a town in the Stone Age
  whose masons have the bricks, of stone bricks a post higher, a short belfry; lanterns if the smith
  has made them, else a torch on each post. `/village bell` says where it stands and how far on it is.
* **The bell** is the town's own: a bell it already has — one by the board, the alarm bell on the
  square, the bell of a village its folk moved into — keeps being rung where it hangs, even under
  the board, until the frame stands; then it is taken down (its plinth's stone back into the
  stores) and hung in the frame. Nobody in a village can make a bell: **put one in the stores**
  (found in a village, bought from a villager or brought) and it is hung in the frame. A bell in
  a bell tower or a chapel's tower is rung where it hangs: the town has its belfry. The alarm is
  rung on the town bell too. Till there is a bell, the ringer **calls the hours** before the
  frame (or at the board), as a town crier would.
* **The ringer** is somebody sensible and awake: at dawn a guard of the second watch (up all night
  anyway), at noon the storekeeper, at dusk a guard going on watch — else the next of them, a
  courier, or whoever is grown and nearest (woken a little early for the dawn bell). It sets off a
  little before the hour, waits at the bell and rings on the hour. If it cannot get there, whoever
  is standing by the bell rings it a little late; with nobody by it the hour goes unrung and the
  day goes on without it.
* **The bell keeps the day it began.** Noon and dusk are rung only on a day the dawn bell was, so a
  town that comes back to the world mid-morning keeps its own hours (each folk its own break) until
  the next dawn. A camp of fewer than three keeps its own hours too, and the alarm bell has the bell
  while it rings.
* **Where you see it.** The board's right-hand column has today's bells (*The bell today: dawn
  6:02 (Holt), noon 12:00 (Holt), dusk at 18:00*); the town's books' **News** page has the town's
  calendar (where the bell hangs, each bell: when, rung by whom, how many answered it); a folk's
  card (**The bell**) says when it got up, ate and went home, and any bell it rang.

**Birthdays.** Grown folk age a year every five days (children six years a day), so a birthday by
their count would come every fifth day. What they keep are the **round ones**: the day a folk's years
pass into a new ten — a child's when it comes into double figures (its second day), then twenty,
thirty, forty and on, every fifty days. The day comes from when it was born, or for one who came to the village grown,
from the years it came with; it is saved with the folk, and the last birthday kept with the world.

* It says so ("Forty today!"), remembers it, and is the happier for the day.
* Its **friends** (those fond enough of it), its partner, its parents and children — the three
  fondest — each come round in their own time (a break, the evening) with **a present**: something
  out of their own pack — a flower, a cookie, a slice of pie, bread, an apple, a rug, a book — the
  kind it loves first, never the kind it hates, never the giver's last rations or the tools of its
  trade; or, with nothing fit to give, one **bought out of its own purse** from the stores (the coin
  into the treasury). The present goes from the one pack to the other — nothing is made out of
  nothing — and a flower or a rug is kept as its own. It says thank you, and both are the fonder.
* The village's history notes **a child's birthday** and **an elder's** (sixty and over).
* A folk's card (**Birthday**) has when it was born, its age and its next birthday (or today's,
  with the presents); the board and the books' News page list **the week's birthdays**.

**Founding Day.** Nothing in a folk's life measures a year, so the town counts its own: **four of
its weeks, twenty-eight days** (long enough to be an occasion, short enough that a town sees one
every few evenings' play and a folk lives through one or two). Every twenty-eight days from the
day it was founded, in the evening, before any other gathering that night (the weekly feast waits a
week; rain does not put it off):

* the village gathers before the board (the leader's hall behind it, once there is one);
* the leader — or, with nobody leading, the eldest — **reads out the year's chronicle**: the eight
  things that mattered most, in the order they happened, a line at a time, slowly enough to read
  over their heads ("Day 9: The smithy was opened.");
* then **a feast** out of the stores, and **fireworks** over the board if the stores hold the
  gunpowder and the paper to make them (one of each a rocket; a bonfire's sparks once they run out);
* and the history notes the year kept. The board and the books show **the next Founding Day**.

## Life together

A village keeps some evenings together, at its heart, once the day's work is done:

* **Weddings.** Two folk who grow as fond of each other as folk get pledge
  themselves ("Will you… marry me?" — "Yes! Yes, of course!"). The next evening the
  village holds the wedding:
  * the couple stand at the heart and the village gathers in a ring round them;
  * the bell rings, the two say their vows, and there are hearts;
  * the guests cheer ("Kiss! Kiss!") and dance, and they like the couple the
    better for it.
  
  The chronicle records it.
* **Celebrations.** The evening a village comes into a new age, everybody turns
  out and there are fireworks over the heart.
* **Feasts.** Every seventh evening: food passed round, music, dancing, toasts to the
  village. Everyone is in a better mood the next day.
* **Vigils.** The evening after a death, the village gathers quietly at the heart for
  the one it lost.

**Children.** A child is born small (a big head on a little body), in plain
clothes, with no trade. For three days it plays:
* tag with the other children ("Tag! You're it!" — "Can't catch me!");
* trailing after its mother or father;
* exploring round the heart;
* bed when it is dark.

Then it grows up ("I'm all grown up!") and takes up a trade. Children talk like
children: ask one about itself and it will tell you what it is going to be when it
grows up.

## Talking with them

**Right-click a folk to talk.** The conversation screen shows:

* its likeness, its trade and personality, and what it loves doing;
* how it feels;
* what it thinks of you, from "can't stand you" to "thinks the world of you", with
  hearts;
* what it just said;
* a **Pack** button under its likeness. It opens what the folk is carrying: its pack, the
  tools in its hands and the clothes it wears. You can look but not take. Sneak and
  right-click a folk to go straight to its pack; the pack screen's **Talk** button goes
  back to the conversation, and its **Work done** button shows everything the folk has
  done in its life: blocks mined, ore veins dug, trees felled, crops planted and
  harvested, animals bred, fish caught, things smelted and made, blocks built, loads
  carried.
* its name: in creative, when you are not already beside it (it has wandered more than five
  blocks off, or is out of sight round a wall or up a floor), click it for **Teleport to** the
  folk, as in the town's books.

The buttons ask:

| Ask | What you get |
|---|---|
| How are you? | Its mood and the reasons for it |
| What are you up to? | Its work, its break, or its pastime |
| Tell me about yourself | Where it came from, how long it has lived here, its personality, quirk, pastime and favourite food |
| Friends and family? | Its partner, children, parents, closest friends, and whoever it can't stand |
| Any news? | The village's age, what it needs most, and the latest gossip |
| What do you hope for? | Its dream, and how far it has got |
| What do you do for fun? | Its pastime |
| Tell me a joke | One of its jokes (a grump won't) |
| Trade? | A bundle of what its work makes, for village coin or an emerald (see below) |
| Gossip? | Who is sweet on whom, who can't stand whom, what's said about other players, and what's said about you |
| Residents? | The village register (see "Your place in a village") |
| I'm sorry | An apology, for whatever you did. A folk accepts one a day |

You can also **type anything** into the box ("how are you?", "will you come with
me?", "who are your friends?"…). It works out what you mean from your words.

What a folk says depends on who it is and what it thinks of you:
* A grump is short with you, a shy folk stumbles over its words, and a cheerful one
  can't help an exclamation mark.
* A curious folk asks you things back, and a generous one offers help.
* A friend greets you by name. A stranger gets polite words. Somebody it can't stand
  gets as few words as it can manage.

**What you can do:**

* **Give it a present** — whatever is in your hand. Something it loves, or its
  favourite food, delights it. Food and useful things please it. Something it hates is
  refused, and it thinks the worse of you for offering. Up to three presents a day
  count.
* **Trade.** Every trade has its goods:

  | Trade | Goods |
  |---|---|
  | Farmer | Bread, wheat, carrots, potatoes |
  | Woodcutter | Logs, planks, saplings |
  | Miner | Coal, cobblestone, raw copper, lapis, redstone |
  | Rancher | Wool, leather, eggs, meat |
  | Fisher | Cod, salmon |
  | Smelter | Charcoal, glass, smooth stone |
  | Guard | Arrows |

  A folk offers a bundle of what it can spare, from its own pack or the village
  stores, for its worth in village coin (the coin goes to the treasury), or an emerald.
  When the village is short of something, it will take some
  of that instead ("Or 16 cobblestone — we're short of it, and I'd sooner have
  that."). The offer shows under the village line. Carry the price and press **Hand
  over**.
  * It never sells what the village is short of.
  * A friend of the village gets half as much again for the same price, and an
    honoured guest or hero gets double.
  * Someone unwelcome pays double. An outcast is not sold anything.
* **Ask a favour** — if it likes you well enough, once a day it gives you something
  from its trade: bread, logs, coal, wool, fish, torches, arrows.
* **Ask it to come with you.** It walks with you for a while, then heads home. It may
  refuse:
  * it won't go with somebody it hardly knows;
  * it won't go when it is miserable, or at bedtime;
  * a guard won't leave its watch;
  * a hard worker won't leave its work mid-shift.
* **Look after the village** — kill a monster near folk and everyone who saw thinks
  better of you, and somebody says thank you.
* **Hit one**, and it won't forget. Anyone who saw it thinks less of you too.

Talking to a folk once a day makes it like you a little more. While you talk, it stops
and faces you. Close the screen to say goodbye.

**Speech bubbles.** Folk never write in the chat. Whatever a folk says out loud shows
in a bubble over its head, readable day or night, for whoever is near:
* a greeting as you walk past;
* its answers to you;
* two friends passing the time of day ("Lovely evening." — "Aye.");
* "Got one!" at the water's edge;
* "I did it!" when a dream comes true.

## Your place in a village

**Every village has a name** (Oakford, Ravenmere, Hollowby…) and **a history**: when
it was founded, every building raised, every age it came into, every birth,
partnership and death, every dream that came true, every colony it sent out, and what
players did for it. Ask any folk for a copy ("History") and it hands you the
village's chronicle as a written book. `/village chronicle` does the same.

**Walking into a village** you are told where you are: its name, its age, how many
live there, and what you are to it. Come back after a day or more away and the
nearest folk who knows you catches you up ("Welcome back! While you were away, Bryn
and Fen were wed, the chapel went up, and Old Tom died.").

**The village elder.** Every day the village looks to whoever its people think most
of, its longest-standing folk counting a little extra, and that one is its elder.
The chronicle records each new elder. The elder speaks for the village when you ask
what people think of you, and `/village status` names them.

**The register.** Ask anyone "who lives here?" and they hand you the village register,
a book with every resident in it: their trade (or "a child"), their nature, partner,
children, favourite pastime, how they feel today, and whether their dream has come
true. The elder is marked.

**Your standing** in a village is what its people think of you, taken together, and
it is earned one person at a time:

| Standing | What it means |
|---|---|
| Outcast | Nobody will talk to you (a present may still mend things) |
| Unwelcome | Folk are cold with you, and people talk |
| Stranger / Visitor | Folk are making their minds up |
| Friend of the village | Favours come easier, from everybody |
| Honoured guest | Folk come along with you more readily |
| Hero | Everybody says so |

Ask a folk "My standing?" and it tells you, including who thinks the world of you
and who doesn't trust you. `/village standing` lists every village that knows you.

**Word gets round.** Folk talk about you to each other. One who thinks well (or
badly) of you tells their friends, and over the evenings what they think comes round
to it too. You may overhear it ("If Steve comes by, make them welcome." "Keep an eye
on Steve. Trouble, that one."). Someone who has only heard of you greets you that
way the first time you meet: "So you're Steve! Bryn's told me all about you." Only
what folk have seen for themselves counts toward your standing. Hearsay just gives
them a head start, good or bad.

**Presents.** A folk who is fond of you will now and then come up as you pass and
give you something, unasked. It gives whatever its days have given it: a fish it
caught, a loaf from the farm, a flower, a bowl it whittled, something it found down
the mine. A folk does this once every few days at most.

**Cries for help.** A folk set on by a monster shouts for the nearest player ("Help!
Steve, help!"). Kill that monster and you saved its life. It never forgets it, its
friends and family think better of you too, and the chronicle records the rescue.

**Grudges fade.** Every day, whatever a folk holds against you softens a little:
quickly for an easygoing or generous soul, slowly for a grumpy one. An outcast who
stays away long enough, or says sorry, can come back.

**What a village does for you.**
* **Honoured guest.** The village resolves to build you **a house of your own**
  (folk tell you so). It goes on the village's list like any other building, and the
  builders raise it on a lot of its own. When it stands, the next folk you talk to
  hands you **its key**, named and marked with where the house is. Nobody in the
  village will sleep in its bed: it is yours.
* **Hero.** The village holds **a night in your honour**:
  * everybody at the heart and fireworks overhead ("Three cheers for…!");
  * the next folk you talk to gives you **the village's medal**, inscribed with the
    day you earned it.
  
  The chronicle records both, for good.
* **Outcast.** Folk keep away from you, the guards keep an eye on you ("I've got my
  eye on you."), and nobody will talk to you, though a present may still mend things.

**Errands.** Ask "Can I help?" and a folk tells you what it needs:
* Usually it is what the village is short of for its next age: iron for the watch's
  armour, logs for the next house, coal, stone, food, diamonds, obsidian. Each folk
  asks within its own trade first.
* With nothing short, it asks for something of its own: the diamond it dreams of,
  flowers for its garden, a book, fish, a note block, its favourite food.
* On some days it asks you to clear five monsters from around the village.

Bring the things (they count from your pack, a little at a time if you like) and
"Hand over". What you bring goes into the village's stores, toward its next age. You
get back:
* experience;
* something from the stores;
* the folk's gratitude, and a little from everybody in the village;
* for a personal favour, **a keepsake the folk made itself**, with its name on it
  ("Fen's carved bowl", "Willa's star chart").

The village's chronicle records what you did. An errand lapses after three days.

**More to say:**
* Ask a folk about anyone in the village by name ("what do you think of Bryn?") and
  it tells you: partner, parent, close friend, nodding acquaintance or rival, with
  some gossip.
* "Memories?" brings out its fondest and latest memories. Folk who have lived in the
  village twenty days remember how it all began.
* Saying sorry can mend a little of what a hit broke.
* A folk remembers what you did together: it thanks you again for the present, asks
  how the errand is going, or keeps its distance.

### Citizens, the council and the law

**Citizenship.** Ask any folk "May I live here?" (the "Live here?" button). A friend of
the village becomes one of its citizens:
* free, if the village has already built you a house;
* otherwise for twenty coins to the treasury, and the builders put up a house for you on
  one of the town's lots.

A citizen has a vote on the council, a tenth off at the shop and the café, and may take
what it needs from the village's stores without it counting as theft. Your best title
anywhere ("Hero of Oakford", "Citizen of Oakford", "Honoured guest of Oakford") is shown
in gold after your name: over your head, in the player list and in chat. Players already
on a team of their own are left alone.

**The council** is the elder and the four folk the village thinks most of. Citizens
each have a vote too. It decides which of the extras goes up first: the café, the
tavern, the smithy, the shop, the brewery or the library.
* Each councillor votes for what suits it: a cook for the café, a sociable soul for the
  tavern, a guard or a miner for the smithy, a curious one for the library.
* Tell any folk what you think the village should build ("you should build a tavern").
  Councillors who like you vote your way, more so the better they like you, and gifts
  help. A citizen's own vote goes to what it proposed.
* The vote goes into the history ("the council voted 3 to 2 for a tavern, as Steve
  proposed"). Ask "The council" to hear who sits on it and what it decided.

**The law.** Somebody has to see it done: a folk within sixteen blocks, awake, with a
clear view. Two things are against the law:
* taking from the village's stores (unless you are a citizen);
* breaking what the village built: its buildings, its wall and gates, its store chests.
  Ground, trees and crops are anybody's to dig, cut and pick.

What follows:
1. **A fine** the first time: twice the worth of what you took, five coins for damage.
   It comes out of your purse there and then, or is owed.
2. **A trial** the second time. The council hears it and finds you guilty, three times
   the fine, unless most of its members think well of you.
3. **Banishment** the third time, for seven days. You lose your citizenship, nobody will
   serve you at the counters, and the guards turn you out of the village on sight.

Every offence costs you the village's good opinion, the witness's most of all. Ask any
folk "Pay a fine" to pay what you owe.

**Statues.** When you become a village's hero, it raises **a statue of you on the
square**: your likeness (your own face) in gold armour with a golden sword, on a carved
plinth, with a plaque bearing your name and the day. The square has room for three
heroes.

### The land: level ground, houses that age, the waterfront

**Flat ground.** Settlers look about before they pitch camp: of the dry ground within
thirty-odd blocks, the flattest piece wins. This applies to a natural village, a colony,
and a lone folk founding one. A party you set down yourself (the spawner block, or
`/village spawnat`) stays where you put it if the ground there is fit for a village. On a
mountainside, a ledge or a cliff over a pond, it makes camp on the best ground within forty
blocks instead: flat, dry, and joined on foot to plenty more. The founders' beds go in a
ring round the stores, and on broken ground a second ring further out.

**Levelling the town.** Every day the village levels a little more of its town to the
height of the square: knolls cut down, hollows filled, so houses and streets stand true.
Only earth, sand, gravel and plain stone are moved. Nothing anybody built, no field, no
tree, nothing next to water, and never more than six blocks up or down, so a hill stays a
hill and just stops at the edge of town. Rock that is cut goes into the stores as
cobblestone. When the whole town is level, the history says so.

**Every building grows up with its village** (not only the houses). The builders do it a
few blocks a visit, out of the stores, and what comes off goes back in:
* **Stone Age:** the timber walls of every building are rebuilt in stone (the land's own
  stone where the stores have it) and lamp posts go up by every door. A fountain is built
  on the square.
* **Iron Age:** the roofs are slated (deepslate tiles) and the footings dressed. The great
  buildings go up a storey, one at a time: the meeting hall, the tavern (rooms over the
  bar), the library, the guest house, the shop, the café, the workshop, the brewery, the
  smithy and the granary. Their walls are built up again over a new floor, with a ladder
  up and the windows where they were. The old eaves stay as a skirt of roof between the
  storeys, and the roof is lifted onto the new walls. All of it is paid for out of the
  stores before the roof comes off. A town of twenty or more builds manor houses, its
  best homes: two storeys of brick, slate and timber framing, six beds each.
* **Diamond Age:** the great buildings (hall, chapel, library, granary, tavern, guest
  house, market, towers, manors) are roofed in copper, which goes green with the years;
  moss gets into the old footings; and a bell tower goes up on the square, with an open
  belfry, a copper spire and a lantern at the very top.

**Houses that grow up.** So an old village looks old:
* **Stone Age:** each house gets a fenced garden with a gate to the street and flowers by
  the path, and its timber walls are rebuilt in stone.
* **Iron Age:** walls in brick, and one house at a time (oldest first, once there are
  planks in the stores) gets a second storey. The old roof comes off, a floor of
  bedrooms goes on (two more beds, a ladder up), and a slate roof goes over it, with the
  chimney carried up. Each one is two more beds for the village.
* **Diamond Age:** moss creeps into the old footings.

**The waterfront.**
* **Jetties and boats.** A fisher's water gets a jetty from the bank: planks out over the
  water on posts, a lantern at the end, and a boat moored alongside. The fisher fishes off
  the end.
* **Irrigation.** From the Stone Age, a field that is too dry gets channels cut through it,
  a run of water every eight rows, so all its farmland is near water.
* **Bridges.** Roads between villages bridge the rivers they cross.

### Work, coin and services

**Earning coin.** There are four ways:
* **Sell to the village.** Right-click a market stall with what the village is buying
  (the stall's sign says what). It pays from the treasury, and what you sell goes into
  the stores.
* **Keep a stall of your own** on the square and sell to the folk at your own prices, out of
  their own purses (see *Your stall on the square*).
* **Do its errands.** Errands for the village are paid in coin as well as goods.
* **Answer the quest board.** See below.

Passing traders buy enough of what every village makes each day to meet its wages, so the
treasury never runs quite dry.

**The quest board** hangs on the front of the meeting hall (on the storehouse until there
is a hall). Real postings, worked out from what the village needs right now:
* **Wanted:** what it is short of for its next building or age ("40 iron for the
  smeltery", "48 logs for a new house"), and what its trades need (wool for the tailor,
  lapis for the enchanter, nether wart for the brewer, flowers for the hives, sugar for
  the café).
* **Clear:** monsters about where the village works ("clear 5 spiders from the east mine",
  "the zombies from the north fields").

To use a posting:
1. Right-click it to take it on. Your name goes on it.
2. Bring what it asks for and right-click it again; it goes straight into the stores. A
   little at a time is fine.
3. When it is done, right-click once more to claim the reward from the treasury.

A new posting goes up most mornings. One nobody takes, or nobody finishes, comes down
after four days. Ask any folk "Quest board" to hear what is on it.

**Hire a folk.** "Hire you?" costs four coins a day, and the folk must know you. It
leaves its work and goes with you, day and night, wherever you go. It catches up if left
behind, fights whatever attacks you or whatever you attack, and picks up what falls. Ask
again to pay for another day.

When the time is up, or you say "go back to your day", it:
* hands over everything it carried for you (into the stores if you are not there);
* goes home with a story: how many days it was gone, the monsters it saw off and the
  lands it saw. The story goes into the village's history, so it is retold at the tavern.

**A house to order.** Bring 64 planks, 32 cobblestone and 8 glass and say "build me a
house". The makings go into the stores. The builders put up a house for you on one of
the town's lots, and you get its key when it stands.

**The town ledger.** Ask "Town ledger" (or `/village ledger`) for a book of the village's
affairs as they stand today:
* the treasury and the contentment;
* everything in the stores;
* who lives where (and which house), and the guests' houses;
* what is going up next;
* what it is short of;
* the quest board.

**The storekeeper serves you.** Ask the storekeeper (the elder, if there is none) for
something from the stores: "could I have 16 bread?", "could I borrow the iron pickaxe?".
* **A friend or a citizen** is given food and goods free, up to a stack a day. Tools,
  weapons and armour are lent: "Hand over" gives them back. Anything not returned within
  five days is remembered against you.
* **A stranger** pays the market's price and a little over.

### The elder's orders

Every three days the village's elder looks the place over and gives an order: what it
wants everybody to put their backs into. An order changes only the village's make-up: a
few more hands in one trade, a few fewer in the others.

| Order | Wants more | Given when |
|---|---|---|
| Fill the larder | farmers, fishers, hunters | the larder is low |
| Timber for the builders | woodcutters | the builders are short of wood |
| Dig deep | miners, a smelter | stone, iron or coal is short |
| Man the walls | guards | lives were lost, the bell rang, or there is a feud |
| Grow the herds | ranchers | a tailor needs wool, or folk have no beds for want of it |
| Fill the stalls | the shop, the café, the smith, the tailor | a thriving Iron Age town |
| To the water | fishers | food is short and there is a fisher |
| Steady as we go | nobody | all is well |

The elder's own nature and trade weigh in too: a hardworking elder digs, a generous one
fills the larder, a grumpy one mans the walls, a sociable one fills the stalls. An
order stands until something else is clearly wanted more.

* **The elder is sensible about it.** A hungry village is always told to fill the
  larder first, and nobody is moved off the farms while food is short. The elder never
  gives an order its folk can't carry out (no "To the water" without a fisher).
* **Folk follow it** one at a time, at most one a day. The one who moves comes from a
  trade with a hand to spare, and never from a craft, from the watch, or from a trade the
  order wants. It says so ("The elder wants more hands at the farming — off I go!") and
  the history records it.
* **The order heads the quest board** on the meeting hall ("ELDER'S ORDERS / Fill the
  larder / - Bram"). Right-click it to read the order in full.
* **Ask anyone** "Elder's orders" and they tell you what it is, and what the village is
  building next.
* **Ask a folk to change trade** ("could you be a miner?", "take up farming"). A folk that
  thinks well of you, or a citizen's neighbour, does it, if the village has a use for the
  trade (and the craft's building stands) and can spare it from its own. Once a day each.
* **Ask the elder for a building** ("build a market next", "could you build a well?"). If
  it is something the village would build, it goes to the front of the list.
* **Have your say.** Tell the elder what you think it should order ("you should order the
  village to dig for iron", or "we need more miners"). If it thinks well of you, or you
  are a citizen in good standing, it agrees, as long as the order is one the village can
  carry out. One petition per village a day. An outcast is not listened to.
  `/village status` and the town ledger show the order too.

### The leader runs the village

The elder (the reeve, the thane, the harbourmaster: whatever the land calls its head) does
more than give orders. Every morning it goes over the village's books and makes the calls,
and its own nature runs through the place.

* **The food books.** The village counts the food that came into its stores yesterday and
  what was eaten, and the leader reckons how many days of food are put by. That counts the
  stores and what its people carry. How much it likes to keep in hand is its nature: a shy
  leader three days, a grumpy or generous one two and a half, a hardworking or curious one
  two, a cheerful or sociable one a day and three quarters, an easygoing one a day and a
  half.
* **Short of that, or eating more than it grows, it expands the farms.** It orders the
  larder filled at once, not in three days' time, and wants half as many farmers and
  fishers again. The fields are widened as soon as they are half sown instead of six parts
  in ten, and looked at twice as often. It also watches the trend, not only the stock: a
  larder going down faster than it is filled is short commons as soon as it would be empty
  within twelve days at more than two meals eaten for every one grown (or within eight at
  three for two), however full it still looks, because new fields take days to come in
  (once its books have a few days in them: a village just founded has grown nothing yet).
  Once short, it stays short until nearly as much is grown as eaten, so the extra hands are
  not sent back to the mine too soon. The town's books say so too: "more eaten than grown,
  gone in about N days at this rate".
* **In a famine** (under a day's food left) it wants twice the farmers and fishers and
  more hunters. If the treasury has the coin, it buys bread from the passing traders to
  tide the village over.
* **With plenty** (three times its reserve, and more grown than eaten) the fields can
  spare a hand for the village's other work again.
* **Work.** A hardworking leader drives the village: everybody works up to a tenth faster,
  and the breaks are a fifth shorter. An easygoing one lets it take its time: slower work,
  longer breaks. A grumpy one is somewhere in between.
* **Spirits.** A cheerful leader lifts everybody's mood, as does a generous or easygoing
  one; a grumpy or hard-driving one wears it down. Each folk feels it more or less by how
  it gets on with the leader: a friend of the leader's is glad of it, a rival chafes, and a
  folk of a like nature gets on with it best. Ask a folk how it is and it may say so ("Elder
  Rook works us to the bone").
* **Pay.** A generous leader pays over the odds, a grumpy, shy or hardworking one keeps the
  purse tight. When there are beds or bread to buy and the treasury is thin, any leader
  holds part of the wages back for them, and says so at the morning assembly.
* **Families.** A sociable, cheerful or generous leader's village has its children sooner;
  a shy or grumpy one's, later.
* **The young.** A child that grows up without a trade of its own learned is set to work
  by the leader where the village most needs hands, and the history says so.

Everything the leader decides goes into the village's history and the morning assembly
hears it. `/village status` shows the leader, its nature, how much food it reckons is put
by and the plan; the board shows it too; and the elder will tell you itself when you ask
about its orders.

### Mouths, fuel and the builders' stock

A village of eight on the plains, left to itself for a hundred days, showed three ways a town
goes wrong: it had sixteen children in a week on a full larder and then ate the larder down; it
went most days with no coal in its stores; and its guard walked the streets with a builder's
load in its pack. So:

* **A child is a mouth for good.** A full larder is no longer enough. A child is raised only
  when the fields, the waters and the hunt grow at least what the town eats in a day, counting
  every mouth it has now and the child's besides (a folk eats what the leader's books say a head
  eats, and never less than its two meals). A small gap (a fifth of what is eaten) is allowed
  when the larder could carry it for a fortnight while new fields come in. On short commons, no
  child is raised at all. The board's **Growing:** line and the town's books say why not:
  "no — 70 meals grown a day against 90 eaten with one more mouth: the fields first".
* **The leader counts the new mouths.** Its forecast of what is eaten a day is never less than
  what every folk here now eats by its books, so a run of births turns the plan to short commons
  (more farmers and fishers, the fields widened) at once, not three days later; and once the
  forecast has turned, a miner or woodcutter whose stone or logs are piled past twice what the
  age wants goes to the fields before the larder runs low.
* **Coal in the stores, whatever the age.** A village keeps a floor of coal or charcoal in its
  stores: sixteen, or one a head, up to forty-eight. Under it, the smelter burns logs the
  builders can spare into charcoal for the stores before anything else (half of them to burn,
  half as fuel), as a player would, and banks it rather than keeping it as fuel; its furnaces
  burn wood first; the couriers take it logs, not the last coal; and the stores' torches are
  made of charcoal only. In the Wood Age it waits while the age still wants timber: the houses
  come first.
* **Torches out of coal in hand.** A miner or a guard makes torches only out of the coal it
  has, as many as it makes (four a lump), and the watch makes none while the stores are short of
  coal or under the floor; a miner may still light its shaft with what it dug. A miner hands in
  every lump past that.
* **The builders' stock goes back.** Once a minute a hand with nothing to do looks in its pack.
  Anything the builders use (logs, planks, stone and brick, stairs, slabs, doors, glass, sand,
  ingots, fences, ladders, beds) that is not its own trade's work (a woodcutter's logs, a
  miner's stone) and not held back (its kit, or the building it is leading) goes back to the
  stores: a load of sixteen or more at once, anything less after three minutes. A builder who
  gives up the lead hands back its stairs, slabs, doors and glass with its timber and stone, and
  a lead that has lapsed (five minutes without getting anywhere, or another hand leading now)
  lets go of what it drew wherever it is, so its next trip to the stores takes it in, its own
  trade's work with it. The smelter, the crafts, the couriers and the storekeeper work with that
  stock and are let be.
* **The day's work is put away twice a day.** Every working hand (the fields, the woods, the
  mines, the waters, the pen, the hives, the hunt, the watch, the smeltery, and the couriers
  between runs) banks what it has collected at midday and again at the end of its shift, full
  pack or not, so nobody goes to bed with the day's work on its back. At midday (the noon bell,
  or from the midday meal's hour, before it sits down to eat) it walks to its own work chest at
  its plot, a few steps off, or to the stores if they are as near; a hand far out with neither
  near keeps it till the evening rather than walk to town. At the end of its shift (the dusk bell,
  or nightfall in a town with no bell; the watch at dusk, before it goes on watch) it puts it in
  its work chest, or the stores if it has none with room, and then goes home: the bell's "home"
  waits for it. It keeps what a deposit always keeps: its tools, weapons and armour (a guard its
  sword, bow, shield and arrows), its trade's kit and working stock (a farmer's seed, a miner's
  torches and a little stone for bridging), a day's rations, and a builder's materials for the
  building it is leading; a guard banks its mob drops. A courier on a run banks when the run is
  done; a folk away with a caravan, scouting, through the gateway, out after a wild animal or
  on the road to another town is let be. Its card reads "Putting the day's work away" while it
  is about it; the town's books say "Banked yesterday at noon 11 of 12 hands, at dusk 12 of 12",
  the Stores page and `/village larder` today's so far.
* **A farmer keeps its seed, not the harvest.** It keeps sixteen of each crop it plants for a
  first field, two more for each ring the field has grown, and never more than thirty-two;
  every carrot, potato and seed past that goes in with the rest of the harvest. (A grown field's
  farmer used to keep forty-eight of each, and walked about with a hundred and thirty meals while
  the stores held thirteen.)
* **Nobody goes hungry with the larder full.** A hand whose plot lies beyond the stores' reach
  (more than sixty-four blocks out, where the stores cannot feed it at mealtimes) takes a packed
  lunch before it sets out on a working day: if it carries fewer than a day's meals (its seed
  not counted), it takes two meals and one over out of the stores, two more for a trade that
  eats rations at its work. Caught out there at a mealtime with nothing to eat, it sends for
  food once a meal: a courier brings it if the storehouse has couriers, else it walks in when
  its work in hand is done. And a farmer with nothing but its seed eats a carrot or a potato of
  it rather than miss the meal.
* **What took them.** A death is written down with its cause: by drowning, in a fall, in lava,
  fighting a zombie, and so on, not just "by misfortune", in the history, the graves and the
  books. (Hunger never kills: a folk that misses its meals grows weak, but never below three
  hearts.) The town's books add up the last seven days each morning: "6 died over the last 7
  days: 3 fighting a zombie, 2 in a fall, 1 by drowning".
* **The town's fields grow faster.** A nine-by-nine of wheat at the game's own pace grows about
  eleven meals a day, and a farmer needs to feed four. So the farmland of a farmer's plot is a
  tended field: its crops get the game's own random growth ticks once more, so they grow twice
  as fast (`villageCropGrowth`, 2.0, from 1.0 for the game's own pace to 8.0; it was three, which
  looked too quick to be natural), through each crop's own growth, so a dark or a dry field gains
  nothing, and only where the game is growing anything at all (a player near, or the town's
  ground kept awake). The farmer's care adds a twentieth each, up to 2.2 times in all: its level
  (ten or more), a field nearly all watered, a field lit, and a composter by its chest. It costs
  a handful of block looks a field a second (two or three for a nine-by-nine, never more than
  forty-eight). Wild crops and a player's own farm grow as ever. The books say "the fields grow
  at 2.0x (tended)", and a farmer's card how its own does.
* **A practised hand at the field.** A village's farmer takes a crop and sows it again at twice
  the pace of the rest of its trade's work, and a farmer with a quarter of its field ripe puts
  its break off till the harvest is in.
* **Bone meal and a composter.** A farmer passing the stores takes four of their bones (the
  watch's and the hunters'), crushes one into three bone meal at its field as anybody would, and
  puts one on a growing crop every few seconds; it keeps sixteen. It sets a composter down beside
  its work chest out of four of the stores' planks, fills it with the seed past what it keeps and
  any poisonous potato, and takes the bone meal out when it is ready.
* **The field lit.** At its field, a farmer sets two of the stores' torches a minute round the
  field's edge till it is lit, so nothing spawns among the crops at night.
* **Food in, by where it came from.** The books say each morning what came in the day before:
  "Food in yesterday: 61 meals: 34 from the fields (3 farmers, 11 each), 18 fish (2 fishers, 9
  each), 9 from the hunt (1 hunter, 9 each)", wheat counted as a third of a meal.
* **Fishers and hunters.** A town fishes from eight folk (one to every ten, more on the coast) and
  hunts from ten (one to every ten, up to four), and on short commons wants half as many fishers
  again (one to about every six) and a quarter more hunters (one to about every eight); the
  hunter never takes one of the last two of a kind about it, and the rancher culls past ten
  head. The village's smelter cooks the fish and the meat whatever its level. And the hands the
  leader sends to the fields and the water stay there three days after short commons end, so they
  are not called back before their new fields come in.
* **The watch grows with the town.** From eleven folk the town wants a guard to every eight
  (two at sixteen, four at thirty-two, seven or eight at sixty), and half as many again, and two at least, for five days
  after it has lost a folk to monsters or raiders. What is left of a raiding party slinks off at
  dawn, all of it: a raider that wandered off out of the loaded ground or outlived its raid no
  longer waits by the town for the next.
* **Stuck fast, and frozen.** A folk standing in ground that no longer ticks (beyond the chunks
  the town keeps awake) is brought home; one on its shift that has done no work in half a day
  and has not moved in five minutes, off its own plot, is put back on it.

`/village larder` says it all in chat: food grown against eaten and whether a child may be
raised, the coal in the stores against the floor, who carries the builders' stock about, the
food in by where it came from (yesterday's and today's so far), the fields' pace and each
farmer's care, the week's deaths and the watch wanted, and what the village's dead died of. The
Stores page of the town's books shows the larder's word on a child under the food chart.

### What each folk cares about

Every grown folk weighs seven things a village can be run for, and the heaviest names the kind of
folk it is:

| It cares most for | It is a… | With an hour to itself it… |
|---|---|---|
| a full larder | Provider | looks over the fields |
| a home for every family | Homemaker | tidies round its house |
| the next age | Visionary | watches the new building go up |
| safe streets | Guardian | walks the wall, keeping an eye out |
| good wages and trade | Merchant | sees what is for sale at the market or shop |
| rest and merriment | Free Spirit | idles the hour away at the tavern |
| the old ways | Traditionalist | sits quietly by the chapel or the well |

It starts from its two traits (a hardworking folk minds the next age, a generous one the larder and
homes, a grumpy one safety, a shy one the old ways) and a little of its own, and then its life moves
it, a little each day:

* **What happens to it.** Going hungry makes it mind the larder; a night on the ground makes it want
  homes built; seeing the raiders come, or being hurt, makes it want the watch; a short wage makes it
  want better pay; a feast or a day of rest, merriment; a death, the old ways.
* **Its trade, more as it rises in it.** A farmer, fisher or cook learns what a larder is for; a
  miner, smelter or smith what the next age is for; a guard what safety is for; a shopkeeper or
  hauler what trade is for. A master of its trade feels it most.
* **The people it loves.** Each day it leans a little toward what its partner and its best friend
  care about most, so a village grows its own camps.

Ask one about the vote and it tells you what kind of folk it is. `/village people` shows each folk's
kind and `/village folk` its weights (`type=Provider(food 62,safety 40)`).

### Elections

Every ten days the village chooses its leader (its elder, thane, mayor or reeve: the land decides the
word).

* **Two days before**, the ones the village thinks most of stand, the leader in office again if it
  will: liked by many, long in the village, good at its trade, sociable or hardworking rather than
  shy. Two stand in a small village, three or four in a town. Each stands for what it cares about
  most (or its second care, if another already stands for the first) and promises it: "a full larder:
  wider fields and more hands on the water", "on to the Iron Age: more picks in the mine and the
  furnaces kept hot", "safe streets: the watch walking every road, day and night". They say so at the
  morning assembly, the board in the square lists them, and folk argue it over in the street ("Who
  are you voting for?" "Fen. We've stood still too long, and they'll take us on.").
* **On the day** each grown folk walks to the board at an hour of its own, when its work allows, and
  casts its vote there, with a word to anyone nearby about who and why. It votes for whoever it judges
  best:
  * what the candidate stands for against what it cares about;
  * what the village needs just now, as it feels it (hungry folk want the larder filled, folk on the
    ground want homes, folk who saw the raiders want the watch, folk paid short want better wages);
  * the people: a partner, family, friends, and folk it cannot abide;
  * a nature like its own, and a hand that knows its trade;
  * and, for the one in office, how it has done by them on what it promised.
* **At dusk** the village gathers at the board, those who stand speak, the votes are counted aloud
  and the winner is announced. The others answer in their own way: a generous loser offers its help,
  a grumpy one says "we'll see how long that lasts". Rain does not put off the count.
* **The mandate.** What the winner stood for steers the village until the next election: a
  Provider's village orders more hands to the fields and keeps half a day more food put by, a
  Homemaker's builds houses sooner, a Visionary's digs, a Guardian's mans the walls, a Merchant's pays
  better, a Free Spirit's takes longer breaks. At the next election the one in office is judged on it,
  and folk will tell you whether it kept its word.
* The one elected leads its whole term, through restarts. If it dies, someone speaks for the village
  and an election is held two days later.

`/village status` shows the election (who stands and the count so far, or the last result and the
next day), and the board shows it too.

### Every town its own look

A village builds in a look of its own, so its streets are of a piece: its walls in the wood its
own woods grow, its roofs in a darker wood against them, its floors in a third, its posts and
beams in the roof's wood (dark timbers on light walls), and its footings and dressed stone in the
land's own stone. Each part has a short list of six to a dozen kinds, best first, and a builder
takes the first it has enough of, going down the list only for what it has none of:

| Land | Walls | Roofs | Floors | Beams | Footings | Dressed stone |
|---|---|---|---|---|---|---|
| plains | oak | dark oak | spruce | spruce | cobblestone | stone bricks |
| forest | birch | dark oak | oak | dark oak | cobblestone | stone bricks (mossy if any) |
| pine woods, snow | spruce | dark oak | spruce | dark oak | cobblestone | stone bricks |
| mountains | spruce | dark oak | spruce | spruce | cobbled deepslate | deepslate bricks |
| desert | birch | jungle | acacia | jungle | sandstone | cut sandstone |
| savanna | acacia | dark oak | acacia | dark oak | cobblestone | stone bricks |
| jungle | jungle | dark oak | jungle | jungle | mossy cobblestone (if any) | mossy stone bricks (if any) |
| swamp | mangrove | dark oak | spruce | mangrove | mud bricks | mud bricks |
| badlands | dark oak | spruce | acacia | spruce | terracotta | cut red sandstone |
| cherry groves | cherry | spruce | oak | spruce | cobblestone | stone bricks |
| coast, river | oak | spruce | spruce | spruce | cobblestone | stone bricks |

Nothing precious goes into a wall or a roof: no iron, no gold, no gems. A roof is wood until the Iron
Age slates it and the Diamond Age roofs the great buildings in copper. `/village status` shows the
village's look.

### Where everything comes from: the supply chains

Nothing a village lays is conjured. Every block a building, a make-over, a street or a grave is made
of has a way to it from what the land gives, through the village's own trades, and the builders only
lay what the stores actually hold. Where an optional block cannot be had, the next best thing is used,
or the cell is left as it is till it can. The recipes and the real prices live in one place,
`entity/Masonry.java`.

#### Who makes what

| Block or thing | Made of | Who makes it | Who carries it |
|---|---|---|---|
| Stone | Cobblestone, smelted | The smelter, from the cobble the village can spare (never the stone the age holds back, never the builders' working 64) | The smelter fetches it (Links.stone); couriers bring it when there is no ore |
| Stone bricks | 4 stone make 4 | The smelter, at its bench, as mason | Banked by the smelter; drawn by builders for dressed-stone cells |
| Smooth stone | Stone, smelted again | The smelter (a quarter of each batch of stone, while the village is short) | Banked by the smelter |
| Stone brick stairs, slabs, chiselled stone | 6 bricks make 4 stairs, 3 make 6 slabs, 2 slabs make a chiselled block | Cut at payment time from the stores, whole batches, the rest of a batch back into the stores | |
| Brick | Clay balls, fired; 4 bricks make a block | The smelter digs clay off a river or pond bed (4 balls a block) or takes the stores' clay, fires it, cuts blocks | The smelter fetches it (Links.clay); couriers bring the stores' clay |
| Slate (deepslate tiles) | Cobbled deepslate, dressed through polished deepslate and deepslate bricks, 4 for 4 each time | Cut at payment time from the miners' deep stone | |
| Cut copper | 9 ingots a copper block, 4 blocks to 4 cut copper (9 ingots a block); stairs 6 to 4, slabs 3 to 6 | Cut at payment time | |
| Mossy cobble, mossy stone bricks | A cobble or a stone brick and a vine (or a moss block) | Made at payment time; the woodcutters cut vines with shears when they find them (Links.vines) | Woodcutter's deposit |
| Lantern | 8 iron nuggets and a torch (an ingot is 9 nuggets) | The smith, only from iron the village can spare (never while saving iron for its age, never the last 16 bars); keeps 8 | |
| Cauldron | 7 iron | The smith, when a building going up has a place for one and iron is spare | |
| Torches | A coal or charcoal and a stick make 4 | The smelter keeps the stores in torches (32 plus 2 a head, up to 96); a village saving coal uses only charcoal, and only when nearly out | |
| Glass and panes | Sand, smelted; 6 glass make 16 panes | The smelter digs or fetches sand while the stores hold under 48 glass (panes counted) | Couriers bring the stores' sand |
| Books and bookshelves | 3 paper (cane) and a leather; 6 planks and 3 books a shelf | The tailor binds books for the library's empty shelves; the shelves go up one a turn (Grow.shelve) | |
| Flowers | Picked wild | Farmers pick one or two on their rounds while the stores hold under 8 | Farmer's deposit |
| Water for a well | A bucket filled at the nearest water | The builder, with the smith's bucket | |
| Armour stand (a statue) | 6 sticks and a smooth stone slab | Made at payment time | |
| Bell | Cannot be made | Found, bought or brought by a player; never required, and without one the watch shouts | |

#### What happens when something is missing

* **Stone bricks in a drawing** ('S' cells): the builder draws the masons' stone bricks first; short of
  them it lays rough cobble. Footings take rough stone first, so the dressed stone goes where it shows.
* **Brick in a drawing** ('Z' cells): blocks of brick, made up of the stores' fired bricks if need be;
  else dressed stone; else cobble.
* **A lantern cell**: a lantern if the builder has one, else a torch (standing on the block under it,
  or on a wall beside it), else left out.
* **Lamp posts** (by the doors, along the avenues, on the roads, at the jetty, on street signs): a
  lantern if the smith has made one, else a torch.
* **The Ages' make-overs**: stone walls only of real stone bricks; brick only of real brick, else stone
  bricks; a slate roof only of deep stone, else stone bricks, else the wood stays; copper at its real
  price, else slate, else stone; moss only of vines, else the footing stays plain (and moss never holds
  a building's make-over open).
* **A second storey** (houses and the Iron Age's tall buildings) is chosen and paid for all at once out
  of what the stores can pay for (Masonry.buildIn): the palette's walls, else stone bricks, else cobble,
  else the village's own boards; the palette's roof, else stone brick, else the village's own wood; the
  windows, lights, doors and rugs left out when there is nothing to make them of. What it was paid in is
  kept in the ledger, so it goes up in what was paid for.
* **Headstones and statue plinths**: chiselled stone bricks if the stone bricks run to it, else plain
  stone bricks, else a rough block of cobble.
* **Watch gates and the bell's plinth**: stone bricks if the stores have them, else cobble and cobble slabs.

#### Never on a roof

Iron (and gold, diamond, emerald, netherite, lapis, amethyst) never goes on a roof, whatever the
stores or a palette hold: the builder's roof fallbacks, the Ages' roof looks and a storey's roof
choices all go through `Masonry.fitForARoof`. Copper and stone are fine.

#### The smeltery and the couriers

The smelter works its furnaces for iron first. With no ore to run it turns mason: it cuts the stone
it has fired into stone bricks, fires clay into bricks, smooths stone and fires spare cobble, as the
village is short of each (Masonry.work, called from the station brain). It fetches the makings itself
from the stores every couple of minutes (Links.stone, Links.clay, Links.sand), and the couriers carry
ore, coal, spare cobble, clay and sand out to it by hand when it runs low (haulerRound). What it makes
it banks at the stores (its deposit rung now banks stone bricks, smooth stone and brick), and the
couriers still take what the furnaces have made straight out of them. The smeltery's own chests stay
unmarked furniture: the village's stores are the storehouse, and nothing in the courier model routes
through them.

#### Tests

`SupplyGameTests` (s01 to s06): the smelter fires cobble to stone in real furnaces, cuts stone bricks
and smooths stone; a builder lays a torch in each of a monument's lantern cells when it has no
lantern; no roof has iron in it through the ages, even with stores full of iron; the smelter digs and
fires clay and a house is refaced in brick only of real brick; the real prices of cut copper, slate,
lanterns, moss and chiselled stone; moss only of vines.

### Furnished for the age

A building's insides come up in the world with the village, a piece at a time, out of its stores
and by its own hands (town work), in the houses, the manors, the leader's hall, the meeting hall,
the tavern, the café, the shop, the library, the chapel, the guest house and the barracks:
* **the Stone Age:** a rug down the middle of every room (the tailor's carpet, or two wool cut into
  three), a barrel by the wall for the household's odds and ends, and a pot of flowers;
* **the Iron Age:** the rug gets a border in a second colour, a shelf of books goes up by the wall
  (two in the great rooms), and a lantern stands on the barrel;
* **the Diamond Age:** candles on the shelf, a second pot of flowers, another shelf.

Where each piece goes is worked out from the building's own drawing: furniture against the walls
from the corners in, the rug on the open floor, and never across the doorway, the way in from it,
a ladder or a chest. Nothing goes in that the stores did not hold. A child's bed set down later
rolls a rug back into the stores rather than going without. After the age's furnishing, each
household's house gets the things of its own trades and its colours (*Homes that show the trade,
and luxuries*).

### The shop's shelves

The shopkeeper keeps what a house wants on the shelves, made up at the shop's bench by the game's
own recipes, the whole way from what the stores can spare, when anything runs low against what the
shop means to keep (*What sells, and what to make next*):

| Ware | Usual / fewest / most | Made of (the whole way) |
|---|---|---|
| Torches | 24 / 8 / 64, the village's lights | a coal or charcoal (a log burnt in the furnace) and a stick |
| Chest, barrel | 2 / 1 / 6 | planks (sawn from logs); a barrel's slabs cut from planks |
| Candles | 4 / 1 / 12 | string and honeycomb |
| Fishing rod | 1 / 1 / 4 | sticks and string |
| Flower pot | 2 / 1 / 8 | bricks |
| Painting, item frame | 1 / 1 / 4 | sticks, and wool or leather |
| Lantern | 2 / 1 / 8 | iron nuggets (an ingot beaten into nine) and a torch |
| Glass panes | 8 / 0 / 24 | sixteen cut from six of the smelter's glass (sand from the river bed) |
| Bucket, shears | 1 / 1 / 4 (shears 3) | iron |
| Stone pick, axe, hoe, shovel, sword | 1 / 1 / 4 | cobblestone and sticks |
| Iron pick, axe, shovel, hoe | 1 / 0 / 3 | iron and sticks, of iron the village can spare |

Never out of the builders' timber and stone, nor the smith's iron, nor anything the age is putting
by. Folk buy their luxuries there once there is a shop open (a carpet, a painting, glass for the
windows, a candle, a pot and a flower for it, a lantern, a banner, a bookshelf: see *Homes that show
the trade, and luxuries*), and their children's beds; a folk come for the tool of its trade and
finding none is a sale the shop had not got, and it keeps more of that tool.

### Homes that show the trade, and luxuries

**A house shows who lives in it.** When the village furnishes its houses for the age, each
household's house also gets the things of its folk's trades, out of the stores and set out tidily
against the walls (never across the doorway or the way in, never where it would cut a room in two,
never on the age's own rug and barrel):

| Trade | In its house |
|---|---|
| Blacksmith | its anvil |
| Fisher | a barrel with cod in it, and a rod on the wall |
| Farmer | a composter, and a sack of seed (a barrel of it) |
| Miner | its lantern, and a pickaxe in a frame on the wall |
| Woodcutter | a chopping block (a log, stood on end), and an axe on the wall |
| Cook | a smoker |
| Beekeeper | a shelf of honey (a barrel of honey bottles) |
| Guard | an armour stand |
| Scout | a map table, and a compass on the wall |
| Tailor | a loom |
| Rancher | a bale of hay, and shears on the wall |
| Smelter | a furnace of its own |
| Storekeeper | a lectern |
| Brewer | a brewing stand |
| Enchanter | a shelf of books |
| Shopkeeper | a bench of its own |
| Hunter | a fletching table, and a bow on the wall |
| Courier | a crate |

Each grown folk's first thing goes in before anybody's second: a little house has room by its walls
for two. **Nothing comes from nothing.** The thing comes out of the stores; a tool for the wall only
if the village has more than it keeps (else a plain wooden one, whittled of the stores' planks), the
cod only out of a full larder; plain joinery (a composter, a barrel, a map table, an armour stand)
is made there and then of the stores' planks and stone by the game's recipes. What wants a maker —
the smith's anvil, a dyed rug — the house **waits on the stores for** (the Homes page says what), and
the makers see to it between their own work: the tailor dyes and weaves, the smith beats out an
anvil or a lantern, the cook builds a smoker, the beekeeper dips candles, the enchanter makes up a
bookshelf, the shopkeeper's bench the rest. A trade's own workstation goes to its building first. A
household that moves out leaves its things to be cleared back into the stores for the next.

**Its colours.** Every folk has a favourite colour, from what it loves: a gardener's is a flower's,
a stargazer's the night's blue, one that loves gems the diamond's, gold the sun's, books a binding's,
fish the sea's, sweets a sugared pink, music a royal purple. Always one the meadows give a dye for
(purple of red and blue). The house gets a rug by the bed in its owner's colour and a banner on the
wall in its partner's, of wool the tailor dyed with flowers.

**Luxuries: made, bought and used.** Rugs (the tailor, of wool), paintings (sticks and wool), glass
panes (the smelter's glass, cut at the shop's bench), candles (the beekeeper's comb and string),
flower pots (fired clay) and lanterns are made of real things and stand on the shop's shelves at the
price list's prices. A **comfortable** folk, every second day at most, walks to the shop for
something its home lacks and it can afford — a carpet (in its own colour if the shop has one), a
painting, glass for an open window, a candle, a pot and a flower to go in it — and a **well-off** one
for a lantern, a banner in its colour or a bookshelf. It pays at the counter out of its own purse,
the way every sale at the shop is paid (the shop's books, the treasury), carries it home and sets it
where it belongs: the carpet on the floor by its bed, the painting on the wall, the panes in the
window, the candle on a table or the sill, the pot with its flower on the sill. Two things for a
comfortable folk, four for a well-off one, seven for the wealthy. **Its candles are lit at dusk**,
each house a minute of its own, and snuffed when the last of the household goes to bed. Players buy
the same luxuries at the shop's counters.

**What it does for a home.** A home is furnished out of ten: the age's furnishing (two), the things
of its trades (two), its colours (two) and its luxuries (four, a point a kind). At four its folk are
a little happier, at seven happier still ("My home's a picture: a blue rug, the smith's anvil, two
paintings, candles lit"), and what the village put in raises the house's price, three in the hundred
a point.

**Seeing it.** A folk's card has a **Comforts** line ("a blue rug and banner, the smith's anvil, two
paintings, candles lit (furnished 7 of 10); blue is its colour"). The town's books: the **Homes**
page gives each house's score out of ten, and the mouse over a row what is in it, what it waits on
and its folk's colours; the **Production** page has a **Luxuries** button for what was made of them;
the **Shops** page says how many luxuries were made and sold this week and how many the folk bought
for their homes. `/village decor` lists every home's furnishing; `/village decor now` (ops) furnishes
them as far as the stores run to and sees to the candles; `/village decor showcase` (ops) sets a
furnished home out where you stand, for the pictures.

### The shop's workshop: its hands, every blueprint, and the age's say

The shop makes what the town wears out, not only what a house wants (`entity/Workshop.java`). The
shopkeeper makes between customers, and the shop **takes on hands for its bench**, who work at the
crafting table in its back room (behind the counter) making whatever is lowest on its order book,
a piece every twenty seconds or so each.

* **Every blueprint.** The makers know every recipe the game has — every crafting recipe, shaped or
  not, of any plank or any wool; every furnace firing; the smithing table's netherite — and a
  modpack's recipes the same way. They work the whole way from what the stores hold (a sword wants
  sticks: the sticks are cut from planks first, the planks sawn from a log), take out exactly what
  the recipe takes, and put back what it leaves (the planks and sticks it did not use, an empty
  bucket). Never the builders' timber and stone, the smith's iron, the coal, nor what the age is
  putting by; everything in and out is in the storehouse's books and the day's production.
* **The age's say.** Each age opens its materials, and a thing belongs to the latest age of what
  goes into it, the whole way down, read off its recipe — so the rule holds for every item there is:

  | Age | Opens | So the makers can make |
  |---|---|---|
  | Wood | wood, wool and string, feathers, bone, the fields' goods, sand, clay, gravel | wooden tools, crafting tables, chests, barrels, fences, doors, beds, ladders, boats |
  | Stone | stone, flint, coal, copper, leather, honeycomb, and anything fired in a furnace | stone tools and swords, furnaces, torches, leather armour, glass, bricks, candles |
  | Iron | iron, gold, redstone, lapis, emerald, gunpowder, slime, pearls, buckets | iron tools, swords and armour, shields, buckets, rails, anvils, lanterns, chains |
  | Diamond | diamonds and obsidian | diamond tools, swords and armour, enchanting tables |
  | Nether | netherite and what comes from the Nether and the End | netherite gear (at the smithing table), brewing stands |

  The same rule holds for the storekeeper's made-to-order: ask for an iron chestplate in the Stone
  Age and it says it is the Iron Age's work.
* **The order book.** What the shop keeps made, the town's needs first, then the emptiest shelf:
  the watch's blades and armour (a piece for every guard who wears worse than the age's best the
  stores can run to and the guard may wear, and a spare for every four guards), shields, bows and
  arrows; the storehouse's rack of spare tools (once there is a shop, the rack is its hands' to keep,
  of the best metal the age and the stores allow); the shop's own shelves (above); what folk and
  players buy and ask for most; and what you order. Short of something, it says so, and asks for it
  on the quest board as before.
* **The watch fitted out.** A guard who wears worse than the best piece in the shop's stock is given
  it, the village paying (no coin changes hands: the shop's takings are the treasury's), and its
  old piece goes back into the stores: the same fitting as a guard's own (see *The watch's kit*). Workers take their spares off the rack and buy their tools at
  the counter; you buy at the counter.
* **Its hands.** One for every fifteen folk in the town, four at the most, while the order book has
  work in it: hands the village can spare (a folk between trades, or idle in a trade with more hands
  than it wants), of the shop's trade and paid at its rate. A hand's card says *Shop hand* and what
  it is making (*Making a stone sword for the shop*); with no keeper left, the most experienced hand
  takes the shop over.
* **Fire.** What wants firing (glass for a bottle, an ingot of raw iron, charcoal for a torch) is the
  smelter's: with a smelter at work the shop sends it to the smeltery and makes the rest when it
  comes back; with none it fires it at its own bench.
* **Where to see it.** The town's books → Shops → *The shop's workshop*: who is at the bench and what
  each made today, the day's pieces and what they were made of, the order book against the stock
  (and what each is for, and the whole way it was last made — what it took ready-made out of the stores
  said made in its turn: *3 cobblestone, 2 sticks, into a stone pickaxe; the sticks of planks, the oak
  planks of oak logs*), what waits on the next age, and what the next age will let it make
  (*the Iron Age will let us make iron swords, iron pickaxes, shields…*). `/village workshop` reads it
  out; `/village workshop blueprints [word]` lists the blueprints and the age each belongs to;
  `/village workshop order <item> [count]` puts something on the order book for you.

### The leader's hall and the courtyard

Once the village has a meeting hall and a board, its builders lay **the courtyard** before the
board: paved in dressed stone with a cross of it through the middle, benches down both sides,
flowers at either end of the board and a lamp post at each front corner, the middle kept clear for
the crowd. It is where the village gathers: the morning assembly, the count at an election,
celebrations, and weddings (the couple at the board, the village down the aisle between the
benches).

Once the town is sixteen strong it builds **the leader's hall**, the best and biggest building in
it (1,569 blocks, 24 high), on the great lot behind the board, which is kept for it:
* below, a great hall of dressed stone: tall windows between stone piers, benches either side of
  a red runner up to a dais, the leader's seat under a great window with lamps either side, the
  clerks' lecterns, and walls of books;
* above, a timber storey: the leader's family's rooms at the back (a bed for two, the children's
  beds at the other end of the room) and the council chamber at the front, with its long table;
* over the door, a stone tower four storeys high with the leader's study in it, a ladder from the
  study up through its floors, and an open lantern-room at the top, seen from the fields.

Every morning, after the business at the board, the leader walks to the hall and holds court
for a while from the dais at the head of the great hall, looking down it to the door ("Next! Who
has business with the hall?"), then goes back to its own work.

Whoever leads the village lives there, with its partner and their children; its old house goes
back to the village (bought back at half what they paid, if it was theirs). When another is
elected the households change over, and the last leader's family goes on the list for a house of
its own. The council sits in the hall's council chamber.

### The town's books: click the village board

Right-click the village board (or press **Analytics** in the village journal, or type
`/village stats`) and the town's books open: everything the village is and has been, with
charts, so you can see exactly what is driving its growth. Every morning the village is
written down (kept for four hundred days), and the books have twenty pages, picked along the
top (in two rows on a small window; the arrow keys turn the pages); the range (a week, a month, a hundred days, or all of it) is picked at the top right, and
every chart reads out the day under the mouse.

**Click a name to go to them.** On the Folk page, the Society page (the richest, the best liked and
the best hand at each trade) and the Leader page, a folk's name is underlined under the mouse (the
pointer turns to a hand); click it for a small card of buttons under it. In creative mode it has
**Teleport to** the folk: the books shut and you are set down on the ground beside it, facing it,
never inside a block or over a drop, and in the Nether if that is where it is. If the folk's part
of the world is asleep, you go to where it was last seen and it wakes around you. On a server you
must be an operator as well; in survival there is no button (and the server turns the request away
whatever sends it). When the folk is near enough to talk to, **Show card** opens its card, as
right-clicking it would. `/village tp <name>` does the same from the command line.

* **Overview:** population, what it makes a day, the treasury and its worth, each with how
  far it has moved in the week; contentment, beds, days of food put by, and the leader with
  the share of the village that approves of it; population and output over time; and the
  first of what is driving it.
* **Growth:** its people over time (grown and children), births against deaths each day,
  comings and goings, the hands at each of its biggest trades over time, the growth rate in
  the range, and what its dead died of.
* **Money:** what it made, took in and paid out each day; the treasury, the folk's purses and
  its worth over time; money in against money out; and what it made by kind (food, timber,
  stone, ore and metal, wool and hides, crafts, plants), with the takings, sales, tax, tithe,
  rent, houses sold, what the folk spent in town, wages and buying-in for the range.
* **Production:** everything the village makes, item by item: every log, cobblestone, loaf,
  ingot, stone brick, lantern and bed its folk bring home or make in the stores (never what
  they were only given or fetched: a founder's kit, or the stores' own goods put back). Five cards
  give the whole of it: what it made yesterday, a day this week, how many kinds of thing,
  what that is worth a day at the market's prices, and how much each grown folk makes a day.
  Below, a table of every item (with its icon): yesterday, a day this week, the last thirty
  days, all told since the village began, how many it used up making other things this week,
  how many are in the stores now, what it is worth a day, and whether it is making more or
  less than the week before. Pick a kind along the top (food, timber, stone, ore and metal,
  wool and hides, crafts, plants), click a heading to sort by it, click an item for its own
  story: which trades make it, this week against last, made against used, and its making day
  by day on a chart. Down the side, what the leader reads from it: each thing the village is
  short of for its next age, how much more it wants, how many it makes a day, and how many
  days that is at this rate. The leader uses the same books: a want the village makes none of
  at all, or too little of to have within the week, counts for more when the leader chooses
  the orders (more hands to the fields, the woods or the mine).

  How it is counted: what a working folk brings home to the stores is its trade's making; what a
  maker (the smith, the tailor, the brewer, the cook, the shopkeeper...) does in the stores is
  reckoned up item by item, so a lantern beaten out of an ingot counts the lantern (and the
  spare nugget) made and the ingot and the torch used. Each morning the day is written down
  (kept a hundred days, item by item, and the totals since the village began for ever).
* **Shops:** the village's sellers (the shop, the café, the tavern, the market, the stores'
  counter), picked along the top: who keeps each and whether it is open, what it sold and took
  this week and what it made; what it is short of to make more; and every ware with its icon:
  what it has against the stock it means to keep (a bar; the target follows what sells), sold
  this week, asked for and missed, made, its price (and any markdown on slow stock), what one
  costs to make, and a note (short of what, or how it is going). The mouse over a ware gives its
  books: today, yesterday and the week, the usual stock and its bounds, and how it was last made.
* **Jobs:** every trade: its hands, their average level, their pay, what it made yesterday
  and this week, what it makes per hand a day, its return (what a hand makes for each coin of
  its pay: green when the trade earns its keep, red when it does not, as the watch never does),
  and its share of everything the village made, biggest earner first; under it, what every trade made each day, stacked, so you can
  see at a glance which trades bring in the most and how that has changed (the mouse reads out
  each trade's share of the day). Click a trade for its own history (what it made, and how many
  worked at it, day by day). Once the school has a teacher it has a row of its own, the
  **Teacher**: one hand, its level and what teaching pays it a day (it is counted at its own trade
  too, which it works in the afternoons).
* **Folk:** everybody, with trade, level, age (in years), loose money (what is in its purse),
  pay, what each made yesterday, mood, nature and net worth; click any heading to sort by it (sort
  by "Loose" or "Net worth" for the richest). The mouse over a folk lays its money out: loose money,
  what it has put by toward a house, its share of a house it owns (at what the house was bought
  for), what it carries (tools, gear and keepsakes at the market's prices), the comforts of home,
  its net worth and wealth band, its pay and all it has earned. Along the top, the town's money in
  sum (loose and net worth, each and in all, put by toward houses, in houses owned, the richest);
  along the foot, the players with a stake in the village (its citizens and anyone who owns a house
  in it): the coin each carries (when on), the houses each owns and what they are worth, the rent
  their tenants owe them, and their net worth. The leader is starred.
* **Society:** the age pyramid (by ten years), how they feel (miserable to joyful) and how many
  live in the crafts' smoke or by the park (and are in it now), how evenly
  the money is spread (the Gini of the purses, the middle purse, what the richest tenth and the
  poorer half hold, and the five richest), couples, households and how big they are,
  friendships and rivalries, the best liked, their natures and most common traits, how skilled
  they are (novice to master), and the best hand at each trade.
* **Leader:** who leads and how: its nature and what it cares about (seven bars), its trade
  and level, family, home and escort, how long in office, its mandate and orders, the
  council, the pace and pay it sets, its approval and regard, the elections (the one coming
  and the ones before), and what it has been doing lately.
* **Homes:** folk against beds and room over time; households renting, owning, saving to buy
  and waiting over time; the rent and the houses sold each day; the figures (housed, waiting,
  renting, owned, saving, coin put by, rent yesterday, owed, players' houses, empty, founders
  rent-free, folk lodging in a spare bed); and every household: who, which house and where, rent-free or renting or saving or
  owning, its rent (and anything it
  owes), a bar of what it has put by toward the price, and whether it wants a house of its own
  and why (the mouse over a row tells the whole of it).
* **Buildings:** every building: what it is, how far and which way from the heart, its quarter
  (and for a house whether it is in the smoke or by the park), its storeys (or a storey going
  up), how far its insides are furnished for the age, and for a house how many live there and on
  what terms; how many of each kind; and what the village will build next, in order, with why.
  **Map of the quarters**, at the foot of the page, shows the town as a map of its quarters instead (see
  *The town's quarters, and the park*).
* **Stores:** food, the days of food put by, timber and stone, coal and iron over time, what
  is in the stores now and the larder's books; and beside them **the storehouse**: its slots
  used and free, its storekeeper and whether it is at the counter, the day's goods in and out
  and the requests served at the counter against those folk served themselves, the couriers'
  runs, the last tidy (slots before and after, stacks merged), its **staff** (the storekeeper
  and each courier: wage, runs today, goods carried, what it is doing now), its **run list**
  (under way and waiting), and the day's books by item and by folk.
* **Stock:** everything the village holds, item by item, with its icon: how many in all and in how
  many stacks, how many in the storehouse and how many in the other store chests, how many more
  are on their way in the workers' chests (waiting for the couriers), what one is worth and what
  they all are, and how many the village keeps back from its makers and why (the builders'
  timber, the iron its age is putting by, seed for the fields...). What the next age wants is
  marked in amber. Five cards along the top: things in store, how many kinds, their worth, the
  storehouse's slots used, and what is on its way in. Pick a kind (food, timber, stone, ore and
  metal, wool and hides, crafts, plants), type to find a thing (backspace to take a letter back),
  click a heading to sort by it, and the mouse over a row tells the whole of it.
* **Research:** the city's research (see *The city's research* below): what the town is studying,
  how many of its points are in, how many mornings to go, who chose it and why; the points in hand
  and how they come; the tree itself, five branches as columns of four civics, each with its name,
  its effect and its cost, green when done (with the day), amber with a bar while it is being
  studied, light when it is open to choose, grey when it waits on the one before it (the mouse over
  one tells the whole of it); and every civic done, with its day. It shows from the first day.
* **Why:** what is driving its growth and what is holding it back, read from its books:
  population and where it came from, whether every bed is taken, whether the larder is full
  enough for children, output up or down and which trades moved it, the most productive trade
  per hand, the biggest earner, idle hands, money in against out, worth, contentment and its
  six parts, what it is short of for the next age, and what would help most now; and at the
  foot, where they live: the homes in the crafts' smoke and din, and the homes by the park.
* **Trends:** what each grown folk makes a day, the worth per head, contentment against idle
  hands and the watch, and buildings, renown and the ages over time.
* **Records:** its bests (most folk, most made in a day, fullest treasury, greatest worth,
  most born in a day, most and least content, most buildings and renown, each with its day,
  and its longest run without a loss); its renown now, and how much of it the museum's finds
  bring (the Overview says so too, under its cards); everything all told (born, died, came, left, made,
  money in, wages paid); a day on average over the range; and where it is heading: folk,
  output, treasury, worth and buildings in thirty days at the pace of the last fortnight, and
  when the larder would run dry if it is emptying.
* **News:** the villages of the world, biggest first, with this one marked (folk, age, worth,
  buildings, how far and which way, and the terms it is on with each), its neighbours, and
  the latest of its chronicle.
* **Board:** the board's own page.
* **School:** the schoolhouse (where, its desks, whether the blackboard is up and a book on the
  lectern; or why there is none yet), the teacher (who, its trade and level, why it was chosen,
  what it is paid for it, how far a schooling under it goes), this morning's lesson and the
  mornings taught, who has left school and as what; and every child: its age, the trade it leans
  to (the mouse over it says why), its level at it so far against a full schooling, the mornings
  it has been, and where it is now (at its desk, on the way, home, too young, truant). See *The
  school* below.

`/village stats <page>` opens the books at a page by its number, counting from Overview at 0:
Stock is 12, Research 13, Why 14, Trends 15, Records 16, News 17 and the Board 18. The School page
comes after the Board: `/village school page` opens the books at it.
* **Museum:** what is on show, a row each: what it is, who found it and at what trade, the day
  it was found, where it stands (in a frame, under glass, on a stand, in the jukebox) and the
  renown it brings; the curator and what it is doing now; the archive's volumes of the chronicle
  (title, pages, where each stands, a copy or the one first written) and the years waiting to be
  bound; what may go on show next, and what the museum is short of. Before there is a museum: the
  town's finds so far, and what it waits on.

`/village stats <page>` opens the books at a page by its number, counting from Overview at 0:
Stock is 12, Research 13, Why 14, Trends 15, Records 16, News 17, the Board 18 and the Museum 19.

### The city's research

Every village studies one **civic improvement** at a time, chosen by its leader, and the whole town
works toward it: twenty in all, in five branches of four. Each is a slight buff (a few in the
hundred off someone's pace, a tenth or a fifth off a price), nothing that runs the village for you;
together they make an old town a good deal easier to live in than a new one.

**The branches.** **Industry** (the trades), **Land** (the fields, the herds and the larder),
**Homes** (rent, building and buying), **Wellbeing** (spirits, health and rest) and **Trade** (the
market, the roads and the watch). A civic is open once the one before it in its branch is done, so
the Watch Drills wait on the Market Charter and the Paved Roads. They cost **10, 25, 50 and 90
points** by tier: 875 for the whole tree.

**Points.** Every morning, with the day's books, the village earns research points:

* a point for every four grown folk, up to sixteen of them, and a point for every twelve more past
  that (twelve at most from its people: a big town has more hands, not many more scholars);
* a point each for a **meeting hall**, a **library** and a **chapel**;
* a point while its leader was elected for **the next age**;
* never less than a point a day.

A village of twelve earns three a day and finishes its first civic on its fourth morning. A town of
sixty with a hall, a library and a chapel earns nine or ten, and finishes all twenty in about ninety
days of its own, more counting the years it was small. Points over a civic's cost go toward the next.

**Who chooses, and why.** When nothing is being studied, the leader chooses among the open civics:

* by **what it cares about**: a Provider leans to the Land, a Homemaker to Homes, a Visionary to
  Industry, a Merchant to Trade, a Free Spirit to Wellbeing, a Guardian to the Watch Drills and the
  Healers, a Traditionalist to the Land and to Wellbeing; above all by **what it was elected for**;
* by **its nature**: a hardworking leader likes Industry, a cheerful or sociable one Wellbeing, a
  generous one Homes, a grumpy one the Watch Drills, a shy one the Land;
* by **what the village is short of now**: food short → the Land; families waiting for houses or rent
  owed → Homes; folk low → Wellbeing; the raiders at the gate this week → the Watch Drills (and the
  Healers); the treasury thin → Trade;
* and by what it leads to: a Guardian who wants the Watch Drills starts on the Market Charter, "the
  road to Watch Drills". The cheaper tiers come a little before the dearer ones.

Whatever weighed most is the reason it gives. The chronicle has it (*Reeve Bramble set the town to
work on Crop Rotation: our fields need it*), the leader says so aloud if it is about, and the morning
assembly hears it; the day it is finished is told the same way. With nobody leading, the council
chooses whatever is most pressing.

**The twenty civics.**

| Branch | Tier | Civic | Cost | What it does |
|---|---|---|---|---|
| Industry | 1 | Common Tools | 10 | Every trade works 3% quicker. |
| Industry | 2 | Apprentice Halls | 25 | A tenth more experience from every piece of work. |
| Industry | 3 | Guild Charters | 50 | The smith, tailor, cook, shopkeeper, brewer, enchanter and smelter work 5% quicker. |
| Industry | 4 | Master Workshops | 90 | Tools wear a fifth slower (one use in five is not charged to the tool). |
| Land | 1 | Crop Rotation | 10 | Farmers work 5% quicker. |
| Land | 2 | Herd Books | 25 | Ranchers, hunters, fishers and beekeepers work 5% quicker. |
| Land | 3 | Seed Exchange | 50 | One harvested crop in ten gives one more wheat, carrot, potato or beetroot. |
| Land | 4 | Granaries | 90 | Folk go a tenth longer between meals: the larder goes a tenth further. |
| Homes | 1 | Cheap Homes | 10 | A fifth off every rent of three coins or more; a rent of a coin or two is let off one payday in five. |
| Homes | 2 | Builders' Guild | 25 | Builders lay their blocks about a tenth quicker. |
| Homes | 3 | Home Loans | 50 | Every house the village sells costs a tenth less. |
| Homes | 4 | Housing Fund | 90 | For every ten coins a renting household puts by toward its house, the treasury adds one (while it has it). |
| Wellbeing | 1 | Feast Days | 10 | The village's contentment is 3 points higher ("feast days kept"). |
| Wellbeing | 2 | Tavern Songs | 25 | Every folk's mood is 3 better. |
| Wellbeing | 3 | Healers | 50 | The hurt mend twice as fast between meals, and folk live a tenth longer. |
| Wellbeing | 4 | Rest Day Charter | 90 | Breaks are a tenth shorter, and every folk's mood is 2 better for them. |
| Trade | 1 | Market Charter | 10 | The village's daily takings, and what the market-day traders pay, are 5% higher. |
| Trade | 2 | Paved Roads | 25 | Everybody walks 5% faster. |
| Trade | 3 | Watch Drills | 50 | Every guard has a point more armour and hits a point harder. |
| Trade | 4 | Counting House | 90 | A twentieth of each payday's wages comes back to the treasury; the folk keep every coin. |

The paces add to the rest of a folk's pace (its level, its tools, its mood, the leader): eight in
the hundred at most from the city's research for any one trade.

**Seeing it.**

* **The village board**, in *What we're working towards*: *Researching: Herd Books 12/25, about 5
  days (Reeve Bramble: our fields need it) · Done: Crop Rotation, Cheap Homes.* Past three done it
  says how many and the latest two. It runs the width of the board near the top of its foot, which
  is always drawn in full, so a busy board does not push it off the bottom.
* **The town's books**, the **Research** page: the tree, coloured by state, with the leader's
  reason and the day each was done.
* **Talk:** ask any folk about the elder's orders and it adds what the town is studying and why.
* **`/village research`**: the points, the rate and what makes it, what is being studied and why,
  and every branch with each civic's key, state and cost. For ops (and tests): `/village research
  pick <civic>` sets the town to study an open civic now (the points in hand go toward it), and
  `/village research grant <civic>` has an open civic done at once. Both refuse a civic whose branch
  has not reached it.

### The watch between the bells

When the bell is not ringing, the watch keeps the town's people safe on its own rounds.

* **The beat.** A guard walks the town's streets by day and by night, not the few yards of
  its own plot. The stops are the corners of the square inside the wall, the corners of the
  ring street and of each street out (and where each crosses an avenue) as far as the town
  reaches, and the street at the door of every building the village has put up. They are cut
  into one beat per guard, a slice of the town each, so four guards watch four parts of the
  town instead of standing together at the stores. A guard stands a moment at each stop and
  looks about before walking on. Ask a guard about its trade and it tells you its beat:
  *My beat's the north-east streets, round Mill Lane.*
* **A word as it passes.** Now and then, at a stop, a guard has a word for a folk going by:
  *All quiet on my beat* by day, *Sleep easy, I'm about* after dark, and *Off home with
  you* to a child out late. Sometimes the folk thanks it.
* **Help.** A monster within sixteen blocks of one of the village's people (a folk, or a
  player who is a citizen or a friend of the village) anywhere in the town draws the
  nearest guard who is free, at a run, and it fights it. A creeper is only taken on by a
  guard with a bow. Monsters in caves under the town, endermen, zombified piglins and
  phantoms are left be.
* **Guard! Help!** A folk a monster hurts shouts for the watch, and the nearest guard
  answers and comes running. With no guard to shout for, it shouts for a player nearby, as
  before.
* **Not past the village.** A guard lets a monster go once it (or the chase) is thirty-two
  blocks past the town's edge, and its round brings it home.
* When the bell rings none of this applies: the walls and the gates are the watch's
  orders then, as before.

**The leader's escort.** Once the village has two guards, or a barracks and one, the best of
its guards (by its level as a guard; the one already at it keeps the post on a tie) walks
with the leader whenever the leader is out and about: on its way somewhere, at the morning
assembly or any other, at the board, at the poll, at a wedding, talking to a player.

* It keeps two to four blocks off, a little behind and to one side, and while the leader
  speaks it stands by with its eyes on the street.
* A monster within ten blocks of the leader, and the escort goes for it.
* When the leader is at its own work, at home or asleep (and after a few seconds of that,
  not at every door it stops at), the escort goes back to its beat. It also slips off to
  cast its own vote at its hour on election day, and comes straight back.
* By day only: the night is the watch's, and the leader is in bed.
* The status line can say who it is: *escorted by Bram*.

### The makers' hands: what they can make, and how well

A smith, a tailor or an enchanter gets better at its trade with the years (its level at
that trade), in what it can make and in how well it makes it.

| Level | The smith can forge | The tailor can make | The enchanter can lay |
|---|---|---|---|
| 0 | iron tools and blades, shears, buckets, bows and arrows | beds, rugs, string; boots and caps of plain leather | the first rank, on iron things and bows |
| 5 | iron helmets and boots | leather jerkins and leggings | |
| 10 | iron chestplates and leggings | clothes in the village's colour; banners on the loom | the second rank; diamond things |
| 15 | shields for the watch (up to three in the stores; a guard of level 10 takes one), and crossbows to order | | |
| 25 | diamond tools and diamond armour, with the diamonds to hand (a pick for the miners, unless the age is saving its diamonds) | banners with a border woven in | the third rank; netherite things |
| 30 | | | its work bound to last (Unbreaking one better) |
| 40 | netherite has its rung here, though no recipe the folk use makes it yet | banners with a stripe as well | |

**The watch's kit comes first.** The watch's armour, blades and shields are not held back by this
ladder. Once the age has come to them, a smith or tailor of any level makes them, and what is above
its rung comes out as an apprentice's work (see *The watch's kit*).

The bookshelves round the enchanting table still have their say, as they do for a player:
the rank laid is the lower of the shelves' and the enchanter's own. The enchanter now also
improves a thing already enchanted when its work is stronger than what is on it.

**How well.** Everything that is one to a stack (tools, armour, shields, beds, boots) comes
out as good as the hand that made it, with the maker's mark on it (*Fine work, by Bram*):

| Level | Grade | How long it lasts | What it fetches |
|---|---|---|---|
| under 5 | an apprentice's work | about a seventh fewer uses | 0.85 of the usual |
| 5 | sound work | as usual | as usual |
| 15 | good work | a sixth longer | 1.15 |
| 25 | fine work | a third longer | 1.35 |
| 40 | a master's work | half as long again, tempered: Unbreaking I and an edge (Efficiency, Sharpness or Protection I) | 1.6 |

The village's counters ask that much more (or less) for a maker's goods, and the village
values them so in its books.

**Asked about its work** (*How does your trade work?*), a maker says what its hand can do:
*I can forge diamond tools now, given the diamonds, and iron of every kind; my picks last a
third longer than most. Diamond armour comes at 30.*

**Made to order.** A player's order is made as well as the hand that took it (and priced
for that hand's work), and the maker turns down what is above it: *A diamond chestplate?
That's beyond me yet: it's level 30 work, and I'm level 7. Ask me again when I've more years
at the anvil.*

Tested in `GuardGameTests`: g01 (a guard runs to a zombie by a folk and strikes it; a folk
hurt by a monster shouts for the watch and the guard answers), g02 (three guards, three
beats over different parts of the town), g03 (the leader's escort: chosen by level, follows,
fights, stands down at work), g04 (a beginner's, a veteran's and a master's iron pick), g05
(an order turned down by a beginner and made by a veteran).

### Homes: households, rent first, saving up to buy, and yours to buy

Every folk belongs to a household: itself, its partner and their children. Each household has a
house of its own (a house or a manor the village built) and sleeps there.

* **Families together.** A couple sleep side by side; their children's beds are on the other side
  of the room. Two folk who marry move in together, into the better of their two houses; the other
  goes back to the village (bought back at half what they paid for it, if it was theirs).
* **A bed for every child.** A child born into a full house gets a bed of its own: its parents buy
  it out of their purses (a generous leader's village gives it if they can't pay). Once the village
  has a shop, a parent walks to the shop for it ("One bed, please — for Wren."), carries it home and
  sets it up across the room from theirs. Twins want two beds, and get them. Births come one at a
  time mostly; twins about one birth in seventeen, triplets once in two hundred, quadruplets once in
  four thousand.
* **Grown children move out.** A child who comes of age stays at home until there is a house for it,
  then moves into a place of its own — wed or not: a grown child who married and lives at its
  parents' (or its partner's) with its partner and their children waits for a house with them, and
  they move out together. While anyone waits for a house and none stands empty, the builders put
  one up.
* **A spare bed meanwhile.** A house has four beds (a two-storey one or a manor six), and a couple
  or a widower does not need them all. A folk with no bed of its own (no house yet, or a house with
  more folk than beds) lodges in a bed another household can spare: one more than that household
  and its lodgers fill. The household always comes first: one of its own who wants a bed (a child
  born, a partner moved in) has it back, and the lodger finds another. The leader's hall puts one up
  too; a player's house never does. Anybody lodging out takes its own bed at home as soon as one
  stands free there, and a guard, up all night on the watch, has its bed at home kept for it. Ask a
  lodger where it lives: "I've no house of my own yet: I sleep in a spare bed at No. 3, Mill Lane
  till there's one for me." The homes line and the Homes page count the lodgers. (Every bed in a
  house used to be its household's, needed or not: a town of eighty-seven had eighty-nine beds made
  up and sixty-six folk in them.)
* **Moving house.** A household that moves carries its belongings: it walks to the old house's
  chest, takes its things, and puts them in the new one's. Its keepsakes (presents it loved, treats it
  bought at the shop for itself) are its own: never banked in the village stores.
* **Rented first, never given.** The village lets its houses; it gives none away. A household moves
  in as the village's tenant, families first, rich or poor, and pays its rent on payday, straight
  after the wages, out of its purses into the treasury (the books count it as money in: the
  Economy page's "in rent"). The rent follows the wages: half a field hand's day for a house, a
  field hand's day for a two-storey house, two for a manor, rounded up — so a house is a coin a day
  in a hamlet, a village or a town and two in a city; a two-storey house one in a hamlet, two in a
  village or a town, three in a city; a manor two, four and six. Half that (never under a coin)
  under a leader elected for homes. The leader's hall is free: it goes with the office.
* **The founders live free till they can pay.** The folk who started the village (the founding
  party, and anyone who came the day it was founded) move into the houses it builds them
  rent-free. From the first payday a household of founders has a week's rent in hand over the dozen
  coins a head it lives on, it pays like everybody else ("We can pay our way now. Rent from today —
  fair's fair."), the chronicle says so, and it never goes back to free, even if its purses run
  low again (then its rent goes on the slate like anybody's). The Homes page counts the founders'
  households still rent-free, shows each as "rent-free" with the rent it will pay later, and the
  mouse over it tells how close it is to affording it.
* **Nobody is put out.** A tenant that can't pay has its rent put on the slate and pays it back
  out of later wages (before it saves a coin); the village writes off more than a week's rent owed,
  and says so in the chronicle. A house where nobody earns (no grown folk with a trade) pays no
  rent at all, and a generous leader (or one elected for homes) lets off whatever a tenant is
  short. A household that cannot pay anything is housed all the same.
* **Some want to own, some never do.** Each household weighs it by what its grown folk care about
  most: a Homemaker wants a house of its own, a Traditionalist and a Provider like to own the roof
  over their heads, a Guardian a little; a Visionary isn't fussed; a Free Spirit would rather rent
  and keep its coin and its freedom; a Merchant buys only when it is a good deal (the price no more
  than forty days' rent: in a city, or early on, but not a village's houses in the Iron Age). A
  couple settling down, children, and the middle years (thirty to sixty) pull toward buying;
  youth (under twenty-four) and old age away. Ask the household and it tells you why.
* **Saving up, then buying.** A household that wants to own puts by on payday a third of what each
  of its grown folk was paid, or all its purse holds over a dozen coins, whichever is more: it
  lives on a dozen coins a head and saves the rest. What it has put by shows ("saving to buy it: 34
  of 44 coins put by"), counts toward its worth and the village's, and pays the rent if the purses
  run dry. When it covers the house's price (35 coins for a house, 55 for a two-storey one, 120 for
  a manor, a quarter more with each age) the household buys the house outright from the village —
  no deposit, no instalments (until the town has a bank: see *The bank*) — and pays no more rent; the
  price goes into the treasury and onto the books. A household that changes its mind gets its
  savings back; one that moves out takes them with it (the last of a household to die leaves them
  to the village).
* **Owners.** An owner sells its house back to the village at half what it paid when it leaves it:
  to marry into its partner's house, for the leader's hall, or up to a manor. A rich owner moves
  up to a manor, which the builders put up once someone can afford one (once the town is getting
  rich: the Stone Age past, 120 coins in the treasury, sixteen folk); an empty manor is kept for a
  household that could buy it, which rents it first like anyone else. Houses given before the
  village let them are let to their households from the next payday.
* **Your house.** Stand in an empty house and buy it with village coin (`/village house buy`, or ask
  any folk "buy this house"): a citizen pays the price, a friend of the village a quarter more. You
  get its key. Sleep in it, or let it (`/village house let 3` for three coins a day): the next
  household with nowhere to live moves in and pays you rent each payday, which you collect with
  `/village house rent`. `/village house let 0` gives your tenants notice. `/village house` lists every
  house, who lives in it, on what terms, what each tenant has put by, and what stands empty. A
  player's tenants don't save to buy it: it isn't the village's to sell.

Ask a folk "Where do you live?" (on the Talk page) and it tells you: "I live at No. 4, Elm Street,
with Tansy and the children — we rent it from the village at 1 coin a day; we're saving to buy it:
34 of 44 coins put by (we want a place of our own)", or "...; renting suits me (I'd rather keep my
coin and my freedom)". The status line shows the homes: "9 households housed (2 owned, 7 rented, 3
of them saving to buy), 1 waiting; 1 empty (to let: house 1c a day); rent 7 coins yesterday", and
the board "Homes: ... 7 rented, 2 owned, 3 saving to buy; rent 7 yesterday". The town's books get it
all, a row a household: where, on what terms, the rent, what it owes, what it has put by against
the price, and whether it wants to own and why.

### The bank

A town of thirty in the Iron Age builds a **bank** on one of the trades' lots facing the square: a
stone house of business with a counter across the room and a lantern on its far end, a lectern
before the counter where the bank's ledger lies open for anybody to read, and at the back the vault,
its strongboxes along the wall behind a barred gate and grille. The builders fell any tree on its
lot before they build, and whatever got into its rooms (a canopy's leaves, a tuft of grass) is swept
out the day it opens, and by the banker after. Until it stands, nothing changes. The day it does
it opens ("the bank opened its doors at No. 3, Market Row: ..."), the elder tells the morning
assembly, and the most careful, shrewdest hand the village can spare — a Merchant before a steady
soul, never a workshop's only hand nor the storekeeper — gives up its trade to keep it, at a
craftsman's wage. The banker makes the vault's bars out of six of the village's spare iron ingots
(sixteen bars; the rest go to the stores), writes the ledger up in a book and quill made of a book,
an ink sac and a feather from the stores, lays it on the lectern, and writes it up again each week:
the vault, the deposits and the loans, the week's interest, every mortgage and the savers.

* **Savings.** Each morning, after the wages, the rent and the tithe, a folk with more in its purse
  than its week wants (a dozen coins to live on, its share of the week's rent, and of the week's
  payment on its house) puts part of the rest in the bank — by its nature: a thrifty one three parts
  in four, a careful one six in ten, most four in ten, one free with its coin a fifth, a spendthrift
  none of it. (Merchants are the most careful with their coin, then Traditionalists, Guardians and
  Homemakers; Free Spirits spend theirs. Hardworking and grumpy folk hold on to it; easygoing,
  generous and merry ones let it go; the Thrifty knack counts twice, the Haggler once.) Short of
  what the rent wants, it draws its savings out before the rent is taken. A household saving up for
  its house keeps that by itself, as before. The tithe is reckoned on what a folk has at the bank
  as well as in its purse: on the rest day the bank pays a tenth of every account of ten coins or
  more to the treasury, out of the account.
* **Mortgages.** The bank lends what is deposited with it, but never all of it: three coins in ten
  of the deposits stay in the vault for whoever comes to draw. A household that wants a house of
  its own and has a fifth of the price put by (a Nest Egg counts) draws its savings at the bank
  toward the price and borrows the rest — if its wages carry the payment (a week's payment no more
  than a third of what its grown folk earn in a week), over the shortest term from eight weeks to
  twelve that they carry. The village has the whole price there and then, and the household owns
  the house ("the bank lent Tansy and Rook 41 coins toward No. 4, Elm Street (14 down; 7 a week for
  8 weeks)").
* **The bank's week.** Every seventh day from its opening, the bank's round: each mortgage runs up a
  week's interest (four in the hundred on what is still owed) and the week's payment is taken from
  the household's purses, then its savings at the bank. What the loans earned pays the savers their
  interest — a coin in a hundred a week, but never more than six parts in ten of what the loans
  earned: no loans, no interest. Of what the bank keeps, half goes to the treasury and half stays
  in the vault against a bad debt. No coin is made anywhere: a saver's interest is coin a borrower
  paid in, and every coin is in a purse, the treasury, what a household has put by, the vault, or a
  player's pack.
* **Falling behind.** A payment missed goes on the arrears, and the banker warns the household
  ("Tansy and Rook missed a payment on their mortgage..."), then warns it again. Three weeks'
  payments behind, the bank takes the house back: the village buys it off the bank for what is
  still owed on it (never the day's wages: the bank writes off what the treasury cannot spare), and
  the household stays on in it as the village's tenant, paying rent. The chronicle tells it, and
  they remember it. A mortgaged house its household leaves (moving up, marrying into the other
  house, the last of them gone) has its mortgage settled out of their purses and savings, the
  village making up the rest; grown children who stay on take the mortgage on.
* **You and the bank.** Talk to the banker (the **Money** tab's *The bank*, or say "deposit 20",
  "withdraw 10", "my account") or use `/village bank deposit 20` / `withdraw 10`. Your account earns
  the savers' interest too. Whatever you had put by at the treasury before there was a bank comes
  over to it the first time you call (when the treasury can spare it over the day's wages). Standing in an empty house, say "a mortgage on this house" (or
  `/village bank mortgage`): a fifth down (out of your account first, then your pack) and the bank's
  loan for the rest, paid on the bank's round out of your account — and out of the rent your tenants
  pay you, if you let it — over ten weeks. `/village bank repay 10` pays it down sooner. Three weeks
  behind and the house goes back to the village. The treasury's own small loans ("borrow 30",
  "repay") are as they were.
* **Where to see it.** The books' **Money** page has the bank where the in-and-out bars were: the
  vault, what is on deposit, what is lent out and kept back, the week's interest earned and paid
  and the treasury's share, and a row for every mortgage (what is owed, the week's payment, the
  weeks to go, payments missed; the mouse over one for the whole of it). The **Homes** page marks
  each mortgaged house "owns, mortgaged" with a bar of what is paid off, and says why the bank has
  not lent to a household that wants to buy. A folk's card has a **Bank** line (its savings, its
  mortgage, how careful it is with its coin; the banker's, its bank), its worth counts its savings
  less what it owes, and "How are you doing for money?" gets the bank in its answer. Ask the banker
  "What are you working on?" and it tells you its books — or who is behind with their payments.
### Blocks of flats

From the Iron Age a town that is short of homes builds **up** instead of out: a block of flats,
three storeys of small homes on one house's lot.

* **When.** Households are waiting for a home, some of them the flats' sort (a grown folk on its
  own, a couple with no children yet, a household that couldn't afford a house's rent), and the
  ground near the square is running short (three home lots or fewer free in the first ring of
  streets) or the queue is long (three households or more). One block for every thirty folk, the
  first at once; never while a block has more than one flat standing empty. It goes on the
  builders' list ahead of the next house, on a home lot as near the square as there is, and the
  status line says why ("a block of flats ...: 4 households wait for a home (3 of them young or
  hard up) and 2 lots stand free near the square").
* **The building.** Eleven blocks wide and built to look like a town house: a slate plinth, stone
  walls with toothed brick quoins at the corners, tall windows in three even bays front and back,
  window boxes of flowers under the ground floor's windows, a balcony on two brackets across each
  upper flat's front, slate sills under the rest, a bracketed cornice under the eaves, and a
  pitched slate roof with a dormer over the middle bay, between stone gables each with a brick
  chimney stack rising out of it (and smoking: a grate in every flat). The front door, on a stone
  doorstep, has a fanlight over it and a little slate pediment over that, a lantern hung at either end
  of it, and the block's name on a sign beside it. Inside, a stair hall up one side with a stair
  that winds up the back of it to every landing (real steps, a rail at the well's edge, a lantern
  on every landing), and two flats to a landing: six in all, three for couples (two beds) and three
  for singles (one bed). Each flat is a room with its bed or beds, a chest, a little table (a post
  with a cloth on it), a lantern over the bed and windows on two sides. The builders raise it like
  anything else, out of the stores, a load at a time and storey by storey from the footing up; its
  roof, plinth, sills, balconies and brackets go up in the stores' wood and the Iron Age's
  make-over slates them like the rest of the town's roofs, and lamp posts go up by its front door.
  (The drawings are made by `tools/flats_design.py`.)
* **Seen to.** Once it stands the town's hands see to it, out of the stores: the beds it went up
  without (from the stores, or three wool and three planks), the ground floor's two doors, each
  flat's number on a sign on its landing with who lives there ("Flat 2B / Tansy & Rook"), and iron
  railings round the balconies (iron bars put by, or six bars of iron beaten into sixteen, never
  while the village is putting iron by for its age). The sign by the front door gives its name.
* **Its name.** Each block is named for its street: Elm Row Flats, and a second on the same street
  Elm Row Buildings (then Court, Mansions, House). Each flat has a number: the storey (1 the ground
  floor) and A for the front, B for the back. A folk's card says "I live in flat 2B, Elm Row Flats,
  with Tansy — ...".
* **Who gets one.** The flats are let, never sold, and the young and the hard-up are offered them
  first: a grown child who has come of age moves out of its parents' house into a flat of its own
  ("A flat of my own! Small, but it's mine."), a couple starting out takes a couple's flat, and a
  household that couldn't afford a house's rent (nobody earning, or not a week of it in hand over
  the dozen coins a head it lives on) takes one too. A family that can afford a house is offered a
  house first, and a flat only when there is none. A family's child gets a bed of its own in the
  flat, bought at the shop as in a house.
* **Half a house's rent.** A flat's rent is a house's day's rent every other day (so a coin every
  other day in a village or a town). It goes on the slate, is let off and written off just as a
  house's is: nobody is put out.
* **Moving on.** A household in a flat that wants a house of its own saves on payday toward a
  house's price (never the flat's), and says so ("we're saving for a house of our own: 34 of 53
  coins put by"). Once it has the price and a house stands empty, it moves out and buys the house
  outright; a family with children that can afford a house's rent moves out to an empty one as its
  tenants. While such a household waits with no house empty, the builders put one up. The flat is
  let to the next household. Two who marry and set up together in a single's flat move across to a
  couple's flat when one is free (or to a house, if they can afford one).
* **Every bed counts.** The flats' beds are the town's beds: counted in the room it has, in the
  houses it plans (nine a block), and in "with a bed" on the books.
* **The Diamond Age.** A block whose flats are all let while folk still wait gets a fourth storey:
  paid for out of the stores before its roof comes off, raised a layer at a time, the stair carried
  up, a balcony more, the roof, gables and chimneys put back on top, and two more flats (twelve
  beds).
* **Seeing it.** The chronicle notes the town's first block ("the town's first block of flats
  opened: Elm Row Flats, 6 flats on one lot ...") and each after it; the books' Buildings page
  shows each block by name with its storeys and its flats let and free ("4 of 6 flats let, 2
  free"); the Homes page lists every household in a flat (kind "flat", rent due every other day,
  what it has put by toward a house); the status line adds "flats: 4 of 6 let"; and `/village flats`
  lists every block, every flat, who lives there and on what terms. (`/village flats stage` sets a
  furnished block out on a stage, for the pictures.)

### Knacks: what each folk chooses for itself

As a folk grows in experience it earns **knack points**, one at each fifth level of the trade it is
best at (levels 5, 10, 15, 20, 25 and 30), six in a lifetime at most. A child earns none. It spends
each point on a **knack** of its own choosing: nobody chooses for it. It chooses by itself, at a quiet
moment of its day (on its break, or off work in the evening, awake), one a day at most, and says so
out loud ("I've got the knack of it now: Steady Hands."). It remembers what it chose, the day, and
why, and keeps its knacks for life, even when it changes its trade. A trade's knack only works while
it works that trade (the Skills page shows it as "resting" otherwise); the others go with it
everywhere.

Every knack is slight: a few in a hundred quicker, a little more of something, a little less spent.
There are twenty-four, in three families.

**Trade** (only in its own trade):

| Knack | Trade | What it does |
|---|---|---|
| Steady Hands | miner | +5% pace at the mine |
| Keen Eye | miner | one ore in eight gives one more of what it drops |
| Green Thumb | farmer | +5% pace in the fields |
| Careful Harvest | farmer | one harvest in three gives a seed back |
| Clean Cut | lumberjack | +5% pace felling trees |
| Drilled | guard | +1 armour while it is a guard |
| Sharp Eyes | guard | picks out its mark from 32 blocks off the wall instead of 28 |
| Practised Hand | smith, tailor, brewer, enchanter | +5% pace at the bench |
| Fire Tender | smelter | the furnaces' fuel goes about an eighth further (one load in two costs a piece less) |
| Strong Back | hauler | carries 32 more on each load of its round (288 instead of 256) |
| Tidy Shelves | storekeeper | +5% pace in the storehouse |
| Patient | fisher, hunter | +5% pace |
| Gentle Hand | rancher, beekeeper | +5% pace |
| Friendly Face | cook, shopkeeper | +5% takings: a buyer at its counter leaves a coin's tip now and then (a twentieth of the price, on average), while it is at work |

**Nature** (one for each trait; open to anyone whose nature is not the opposite, so a grump never
takes Bright Spirit, nor a cheerful folk Grim Resolve):

| Knack | Nature | What it does |
|---|---|---|
| Bright Spirit | cheerful | its mood +3, and its friends' +1 while they are near it |
| Early Riser | hardworking | its break is 15% shorter |
| Good Company | sociable | its friendships grow about a quarter quicker |
| Unflappable | easygoing | its mood never falls below 35 |
| Quiet Focus | shy | +3% pace with nobody else within 8 blocks |
| Quick Study | curious | +10% experience at its trade |
| Grim Resolve | grumpy | +4% pace when its mood is low (under 45) |

**Purse**:

| Knack | What it does |
|---|---|
| Thrifty | pays a tenth less at the shop, the café and the market |
| Haggler | +5% on its wages: an extra coin now and then (on four coins a day, a coin every fifth day) |
| Nest Egg | once: about 30% of its house's price toward buying it |

**How it chooses.** It weighs every knack open to it:

* **its trade**: the knacks of the trade it works count most, and more the better it is at it; a
  trade it once worked counts a little. A hard worker likes the pace knacks, a Guardian the watch's,
  a Provider the fields', a Visionary the mine's and the bench's;
* **its nature**: the knack of its own trait counts as much as its trade's;
* **what it cares about** (see *What each folk cares about*): a Merchant at heart reaches for Thrifty
  or Haggler, a Homemaker for Nest Egg, a Free Spirit for Bright Spirit, Unflappable and Good
  Company, a Visionary for Quick Study;
* **how it is placed**: a household that rents its house and wants to buy it reaches for Nest Egg
  before anything else; a lonely folk for Good Company; a folk that has been low for Unflappable,
  Bright Spirit or Grim Resolve; a folk whose work is a lonely one (the mine, the river, the hives,
  the furnaces) for Quiet Focus.

A little leaning of its own breaks a tie, so two miners need not choose alike.

**Nest Egg.** A one-time grant of about three tenths of the price of the house it lives in (a plain
house's, if it has none of its own), toward buying it. It never covers the whole: a household that
takes it still saves the rest out of its wages and buys on payday, as always. The coin is the
village's: the treasury pays it out of what it can spare (what it holds over what it is saving for
and a day's wages), at once if it can. If it cannot, it pays what it can and sets the rest aside,
and pays it a part at a time, each afternoon it has coin to spare, until it is paid. It goes into
what the household has put by toward its house if it rents it and wants to buy it (never past the
price), and otherwise into the folk's own purse. The chronicle says so ("Tansy chose Nest Egg: 11
coins toward the house its household rents and means to buy (30% of a 35-coin house), paid from the
treasury"), and so does the folk: "That's a good start on a house of our own." Once a household:
a folk whose partner has had it cannot choose it again.

**Seeing them.** Right-click a folk and open its **Skills** page: its knack points as six pips (gold
for a point spent, a gold ring for one to spend, grey for one still to earn), a bar to its next
point, its trades as rulers marked at every fifth level, a card for each knack it chose (what it
does, why it chose it, the day, and whether it is resting), and its tree of knacks in three
branches, Trade, Nature and Purse, with what it chose in gold and what is still open to it greyed
(the mouse over any of them tells the whole of it; a dot marks the ones it leans toward). Scroll it
with the wheel. Its **About** card has a **Knacks** line too ("Steady Hands, Nest Egg · next point at
level 15"), and asked "What are you good at?" it names its knacks. `/village knacks` lists every
folk's in the nearest village; `/village knacks <name>` one folk's, with the knacks still open to it
and how much it wants each.

Tested in `KnackGameTests` (kn01 to kn05): points at levels 5 and 10 (one still at 9, six at most,
none for a child, kept across a change of trade); a Homemaker renting its house chooses Nest Egg and
has 30% of the price put by toward it, never the whole, out of the treasury, into the chronicle and
its memory, and another, the treasury empty, has it set aside and paid when the coin comes; a
Merchant chooses a money knack, a miner a miner's knack, a cheerful Free Spirit Bright Spirit, one
a day; Steady Hands quickens mining only, Drilled is armour on the watch only, a Haggler's wage
comes to a twentieth more, Thrifty pays a tenth less; and the knacks survive a save and a load, and
reach the Skills page and the About card through the reply's codec.

### Neighbours: rivals, allies and feuds

**Every village is its own.** A chest or a storehouse belongs to the village whose heart is
nearest it, and a worker's own chest to that worker's village, wherever its plot is. A village
counts, takes from and fills only its own. A second town founded a couple of hundred blocks off
used to count the first town's stores as its own and draw its builders' timber out of them, with
nobody walking over. Goods and coin pass between villages only by caravan, unless the config
option `villagesShareGoods` is turned on (see Config). Then the alliance food, tribute,
neighbourly help and envoys' gifts below also move goods outright.

Villages within about six hundred blocks of each other have dealings, and what each
thinks of the other runs from -100 to 100:
* **Land disputes.** Two villages whose lands overlap quarrel over the ground between
  them, worse every day: boundary stones moved, trees felled on the wrong side.
* **Kin.** A colony and its mother village start as family, and their caravans keep
  them close.
* **Comings and goings.** Traders come to market, a lad comes courting from the next
  village, a sheep goes missing and the neighbours get the blame.
* **Alliances** (60 and over). Each village sleeps sounder for the other (a little more
  contentment). An ally that goes hungry is sent food from the other's stores.
* **Feuds** (-50 and under). Scuffles at the boundary sour both villages' moods, and
  each feud lies heavy on its village's contentment. Now and then the elders meet and
  agree a truce.
* **Tribute.** A village half again as big and at least as advanced, that thinks
  nothing of a smaller neighbour, demands tribute every week from the smaller one's
  treasury. Paying keeps an uneasy peace. Refusing, or being too poor, makes it worse.

You can take a hand:
* "Neighbours?" tells you what a folk thinks of the villages round about, and where
  they lie.
* "Make peace with Ravenmere": you carry gifts (ten coins) between them. That mends
  25 points, and both villages think better of you.
* "Stir up trouble about Ravenmere" / "the rumours about them": relations sour by 20.
  One time in three somebody finds out who started it, and then both villages think
  worse of you.

**What they are to each other.** Beyond the number, every pair of neighbours has:
* **A memory.** Each village remembers its last eight dealings with each neighbour: the
  traders who came, the bread sent in a hungry week, the brawl at the boundary stone, the
  tribute paid and resented, the letter you carried. A fresh kindness or a fresh grudge
  (the last month's) holds a relation where it is instead of letting it drift back to
  nothing. A folk tells you the latest when you ask about the neighbours.
* **A border.** No village ever stakes a field, a wood or a mine nearer a neighbour's
  heart than its own. Crowded neighbours not at each other's throats walk the line and
  agree a border, stones and all, and the quarrel over the ground ends there.
* **Truces.** A feud that cools (the elders at the boundary stone, a go-between, or your
  olive branch) gets ten days of truce. There are no brawls or insults, and the relation
  cannot fall back into a feud while it lasts.
* **A go-between.** Two villages at odds that both get on with a third: its elder brings
  them together and talks them round, and both think the better of it.
* **Marriages.** Neighbours on good terms marry across the boundary. One of the pair (from
  the bigger village) moves to the other's village, and the wedding is held there. Every
  marriage binds the two villages a little closer, every day, for good.
* **The feast.** On the weekly feast, friends send a guest with a gift.
* **A hand when short.** Friends send what they can spare (only what their own needs leave
  over) of what the other is short of.
* **The harvest contest.** Once a week neighbours compare what they made. The winner
  crows; a good loser takes it well, and a prickly elder does not.
* **Word of you spreads.** Honoured in one village, you are welcomed by its allies and
  looked at sideways by its enemies. An outcast of one village is welcome among its
  enemies.

And you can take a hand:
* **"Carry a letter"** (the village tab): the elder writes to the nearest neighbour's (or
  the one you name). Hand the letter to anybody there: the two villages warm by 8, both
  think the better of you, and the one you deliver to pays you a coin or three for the
  walk. One letter at a time, and nobody writes to a village it is feuding with.
* **"Make a trade pact with Ravenmere"**: honoured in both villages, with the two on good
  terms, you broker a trade pact on your word, and their caravans take to the road.

Every alliance, feud, truce, border, marriage and tribute goes into both villages' history.
`/village relations` lists every pair of neighbours, how they stand and what binds them
(border, truce, pact, alliance, marriages, warm memories or a grudge). `/village status`
gives the council, the citizens and the neighbours.

### The job market between towns

Towns that know each other trade hands as well as goods. A town short of a pair of hands
puts a notice up on its village board; folk in the towns round about who have a reason to
move read it on their own boards, apply, and the best of them comes.

**Wanted notices.** A town puts a notice up when it is a whole hand short at a trade it
needs (or has nobody at all at one it wants), or when a new workplace stands with nobody in
it: a smithy wants a smith, a café a cook, a library an enchanter; a school, a bank or a
stable too, once the town has them. Idle hands at home take up its wants first. The notice
says the trade, the wage (the trade's rate at the place's standing, at the leader's rate:
what it will really pay out of the treasury), and what it wants: some years at the trade,
and an age where it matters (the watch able-bodied, eighteen to fifty; the mines and the
woods strong backs, up to sixty; a teacher an older, wiser head, thirty-five or more). A
town that cannot pay posts nothing; three notices at most; a notice nobody answers comes
down after six days, and one the town filled from its own folk comes down at once.

**Who hears of them.** Word goes by the roads, the caravans and the elders' dealings. A
colony and its mother village see each other's notices the day they go up (and a laid road
between them makes going easiest of all), and so do towns with a trade pact or an alliance.
Neighbours who know each other hear of them a day later. Rivals hear too, but it takes more
to make a folk go over to them, and an elder who mistrusts a rival will not take its folk
on. A town that has never met another hears nothing of it.

**Who goes looking.** Only a folk with a reason: out of work (no trade, or a trade it has had
no ground to work at for a couple of minutes: a woodcutter with no wood about, a miner with no
hill), or idle at a trade its town has more hands at than it needs; paid less than a notice elsewhere offers (by what that
town's paydays really pay, against its own); unhappy at home; family living in the other
town; or young, with no trade much learned yet, wanting a start. It takes enough of them
to go (more to go over to a rival, and more again if its own town is short of its trade).
Folk with no reason stay put, and the elder never goes, nor the builder leading a build,
nor a newcomer not five days settled. On its free time (its break, the evening before bed,
the day of rest, or any time if it has no work) a folk with a reason walks to its own
board, stands and reads the notices ("Wanted, a miner in Oakhollow, two a day, some years
at it..."), and if one suits it, it puts its name down: an application to that town.

**The leader decides.** A while after the first application comes (or as soon as three
have), the hiring town's elder looks the applicants over: their level at the trade and the
knacks of it they chose, their years (too old for the mines, too young to teach; an old
head counts where wisdom is wanted), how cheerful or sour they are, family already in the
town, and how the two towns stand (a shrewd elder weighs the years at the trade above all;
a warm one gives the young a start; a wary one is slow to take a stranger). The best gets
the place; the others are told no and why, and say so when you are about ("Too old for the
mines, Oakhollow says. Hmph."). An elder that could do better holds out a day or two for a
hand with the years the notice asked for. And the applicant's own town must be able to
spare it: a town keeps four grown folk at least, its last farmer, its last guard behind a
wall, and no more than one in eight of its folk leave for work elsewhere in a week.

**The move.** Taken on, the folk says so, walks to the square and says its goodbyes (its
friends see it off), and goes: what it carries of the village's goods back into the
stores, its own things out of its house's chest, off the old town's roll and onto the new
one's, and down the road the caravans take, its partner and children with it if it has
them. The new town pays its road money, a coin a hundred blocks. At the other end it is
found a home (an empty house for the household, or a bed at the camp) and takes up the
trade it was taken on for. Both chronicles tell it, the morning assembly welcomes it, and
the old town, one hand short now, may put up a notice of its own. Because the wages differ
by town, folk drift, slowly, to where they are paid and needed.

**Refugees.** A raid that leaves folk without a bed (their beds gone from under them, their
house left with none) sends them, with their households, to the nearest friendly town with
room, once no bed can be found at home. The town takes them in: a bed found, work as they
fit. If so few are left after a raid that the village cannot go on, they all go, sharing
out what was in its treasury, and the village is given up. Both chronicles tell it, and the
two towns think the better of each other for it.

**Where you see it.** The board shows the town's Wanted notices (and how many have applied),
word of other towns' notices from the road, who is on the road here, and who came and went
this week. The city books' **Jobs** page has a switch at the top: **Between towns** lists
every notice and how it went, every application with the applicant's level, age and knacks,
why it applied and the leader's verdict (the mouse over a row for all of it), who came and
went this week and why, who is on the road here, and what the other towns want. A folk's
card says what it applied for, that it was taken on and is saying its goodbyes, that it is on
the road, or where it came from and why ("Came from Riverford for the wages, to work as a
miner"). `/village jobs` gives the same from the console.

### Every town its own: the land

When a village is founded it looks over its land, and the land shapes it:

| Land | What it lives by | Its leader |
|---|---|---|
| The coast | fishing from its first days, three times the fishers | the harbourmaster |
| A river | twice the fishers, more farmers | the reeve |
| Forest / pine woods | more woodcutters, hunters from eight | the warden |
| The snowfields | hunters from six, fishers from eight, fewer farmers | the hearthkeeper |
| The mountains | more miners and smelters | the thane |
| The desert | more miners, fewer farmers and herds | the wellkeeper |
| The savanna | twice the herds, a rancher from eight | the herdmaster |
| The jungle | woodcutters and hunters | the chief |
| The swamp | fishers | the fen-reeve |
| The badlands | miners and smelters | the headman |
| Cherry groves | herds and hives | the mayor |
| The plains | an ordinary mix | the elder |

* **Its leader.** The folk look for the nature the land asks for in whoever leads them:
  open and easy on the coast and the rivers, hard and steady in the hills and the snow,
  quiet and watchful in the woods, shrewd in the desert. That nature is also how the
  village treats its neighbours (see Leaders and envoys).
* **Its houses** are rebuilt in the land's own stone, while the stores have it:
  sandstone in the desert, terracotta in the badlands, andesite in the hills, mossy stone
  in the jungle and the swamp. The timber is whatever its own woods grow.
* **Its name**, for a village founded now: a haven or a mere on the coast, a crag or a
  fell in the hills, a fen in the swamp, a well in the desert.
* `/village status` and the board say what land it is and who leads it, and its folk will
  tell you.

### Leaders and envoys

How a village gets on with its neighbours is down to who leads it. The elder's nature
sets the village's temper:

| Elder | Temper | How it deals with the neighbours |
|---|---|---|
| cheerful | warm-hearted | makes friends; forgives a feud quickly |
| sociable | friendly | always sending somebody to see the neighbours |
| generous | open-handed | sends gifts; never asks for tribute |
| easygoing | easygoing | lets most things go; quick to a truce |
| curious | curious | wants to meet everybody |
| (none yet) | steady | keeps a steady hand |
| hardworking | shrewd | wants trade pacts, and tribute from a weaker neighbour |
| shy | wary | keeps itself to itself; slow to trust |
| grumpy | prickly | takes offence, complains about the boundary, never pays tribute |

Two elders who share a trait get on. Two opposites (cheerful and grumpy, sociable and
shy, hardworking and easygoing) do not.

Dealings are done **in person**. The elder sends an **envoy**, a real folk on foot (the
elder itself for an alliance or a peace), down the road to the neighbour. When the envoy
arrives, the neighbour's bell rings and its folk gather before their board to hear it.
Their elder answers there and then, in its own way. The envoy walks home, and the answer
is told at the next morning assembly. An envoy can carry:
* a first **greeting**;
* an offer of **trade**: bargained over before the board, round by round, into a **deal**
  both towns gain by (see Trade between towns, at the end). Its caravans then run both ways
  on the agreed days, and each trip warms the two villages a little;
* an **alliance**, sworn before the village board. Allies feed each other when one goes
  hungry;
* **peace**, with gifts out of the stores and a few coins;
* a demand for **tribute**, which comes home in the envoy's purse, or with a refusal;
* a **complaint** about the boundary, answered with an apology or with "it's ours!";
* a **gift**, from an open-handed elder.

A feud tears up the pacts and alliances between two villages. The board shows the
neighbours, how the village stands with each, which it trades with, and its elder's
temper. Ask any folk "Neighbours?" to hear about its elder too.

### Scouts and the atlas

A town of **forty** takes up one or two **scouts** (a hood, a cape, a map case and a
spyglass). Every morning a scout picks the way the village knows least and sets off with
food (and a torch or two) from the stores:
* **Finding the way.** It walks in stages of about twenty blocks. At each stage it
  looks at the ground ahead, straight on and to either side, and takes the best line:
  dry, an easy slope, still heading the right way. It checks the path before it
  commits. Where the way is blocked it searches wider, step by step: round the lake,
  along the cliff foot. If the whole way is blocked, it notes that in the atlas and
  comes back.
* **Coming home.** It drops a breadcrumb every sixteen blocks and comes home along its
  own trail. It keeps clear of anything hostile, turns back if hurt, and is home by
  dusk.
* **What it finds:**
  * other towns, and villages of villagers;
  * temples, igloos, ruined portals, trail ruins and beached shipwrecks;
  * dangers: pillager outposts, witch huts, mansions and monuments. Danger near home
    sends it running back to warn the village;
  * high peaks, lakes and the sea, and other lands (a desert, a cherry grove);
  * iron, coal, gold, diamonds and other ore showing in the rock (marked with a
    torch), and pools of lava;
  * good flat ground by water for a new village;
  * the players it meets on the road, whom it hails.
* **Telling.** It calls out what it finds as it goes. At home, the finds go into the
  village's **atlas**, the chronicle, the board ("Scouts: 12 things in the atlas,
  lately a desert temple 340 south-east; 18% of the land explored") and the next morning
  assembly.
* **Other towns.** A town the scouts reach and their own swap their atlases, and count
  as neighbours twice as far off as before, so envoys and trade can reach them.
* **Asking.** Ask anybody "What's out there?" (or press **Out there** on the Village
  tab). A scout gives the exact coordinates, and will **walk you** to a find that is
  near enough.

### The museum and the archive

A town keeps what it is proudest of.

* **Finds.** Whatever a folk picks up out of the world is looked at as it comes into its hands:
  a miner's diamond or emerald, a **fossil** (bone blocks dug out of the deep rock), a fisher's
  nautilus shell or saddle (a fisher's line brings up treasure now and then, as a player's does:
  a saddle, a nautilus shell, a name tag or an enchanted book; and an ink sac, sometimes), a
  guard's trophy off a monster (a skull, a trident, chainmail, a totem), a hunter's rabbit's foot,
  a ghast's tear from the Nether. Who found it, at what trade and on what day is written down then
  and there, and the first of every rare kind is told in the chronicle ("Ember the miner mined the
  town's first diamond").
* **The museum.** A town of twenty in the Iron Age with three different rare finds plans a
  **museum** among its amenities, on a lot facing the square: the town's grandest front. A hall
  of dressed stone stands on a **plinth**, up a stair four wide between the middle two of a
  **portico of four columns**, with a lantern on a stone post either side of the stair and two
  more hung from the entablature over the door. Over the portico rises a low **pediment**, half
  a block a step, its face of stone and its raking edge of the town's roofing. The double door
  stands under a glass fanlight with a window either side of it; **tall windows**, three panes
  high, run down each side; a flat roof with a **skylight** over the middle of the hall has a
  stone **parapet** round it. It is built like any other building, out of the stores (about as
  much stone as the chapel; the plinth is rough stone until the Iron Age's make-over dresses it).
  Once it stands, its **name goes up over the door** on two signs ("The Museum | of Oakhollow")
  and a **banner in the town's colours** (its watch's: blue, red, green, purple, black, teal or
  orange) is hung either side of the door between the columns: put up a piece at a time by a
  hand sent to the town's work, a sign out of the stores (or two planks), a banner out of the
  stores (or the wool, the dye and the stick it is made of), and not before the stores can run
  to it. The name follows the town's if it is renamed. A museum built before it had a portico
  keeps its old front, and everything inside stays where it was. A **curator** looks after it: the folk with the most
  curiosity and learning in it (a curious nature, a love of reading, the enchanter's trade), or
  else the eldest. It keeps its own trade, and does the museum's work by day.
* **What goes on show.** One of every kind: the first diamond, the first emerald, a fossil, a
  music disc, an enchanted book, a trident, a nautilus shell, the heart of the sea, a totem, a
  saddle, a rare fish, a monster's skull, chainmail, a rabbit's foot, a ghast's tear, amethyst, a
  name tag, a **map** of one of the scouts' finds (drawn by the curator on an empty map made out
  of the stores, the ruin marked with a red cross), and the first iron of the Iron Age. Nothing
  comes from nowhere:
  * the curator **asks** for the rarest thing the stores hold that is not on show yet; from then
    on the stores keep it back from every maker (the Stock page says *kept for the museum*);
  * it walks to the stores and takes it out, with whatever its place wants, made out of the
    stores by the game's own recipes: a frame (sticks and leather), a sign for its label
    (planks and a stick), glass for a case, an armour stand, a jukebox. If the stores cannot run
    to it, it waits, and the Museum page says what it is short of;
  * it carries it to the museum (you can see it in its hand) and sets it out: **in a frame** on
    the wall, **under glass** let into the floor (the fossil, a skull), **on a stand** by the
    door (the trident in its hand, the skull on its head, the chainmail on its back), or **in
    the jukebox**.
  * Its **label** (a sign) says what it is, who found it, at what trade and on what day
    ("Diamond / mined by Ember / the miner / day 41"); look at the thing itself and its name
    says the same.
* **Pride and renown.** The finder is proud of its find on show, says so, and is the happier
  for it for days. The town's **renown** rises with every thing on show, the rarer the more: one
  for a rare fish, three for a diamond or a fossil, five for the heart of the sea or a totem
  (a great work is ten). The board tells what is new in the museum.
* **Visitors.** Folk look round of an evening, the curious and the readers most, finders to see
  their own finds: they stop before a thing and say a word about it. Come in yourself and the
  curator welcomes you; every label can be read. On the **day of rest** the jukebox plays its
  disc. Something taken away is missed, and the chronicle says so.
* **The archive.** The town counts its years from its founding, **twenty-eight days to a year** (the town calendar's year, the same as its birthdays and Founding Day). When a
  year is over, what the chronicle says of it is written down at once (the chronicle itself keeps
  only so many lines). The curator makes a **book and quill** out of the stores (a book, a feather
  and an ink sac; the book out of paper and leather if need be), writes the year into it and signs
  it: **"Chronicle of Oakhollow, Year 2"**, by the curator, with a title page and then the year
  day by day, every line fitted to the page. The newest volume lies **open on the lectern** at the
  back of the hall for anyone to read; the one before goes onto the archive's **chiseled
  bookshelves** either side of it (made out of the stores as they are wanted), six to a shelf. A
  year too long for one book goes into two. Take a volume away if you like: the curator writes it
  out again, a fair copy, from its notes.
* `/village museum` says it all in chat: the curator, every exhibit and who found it, the
  volumes and where they stand, what is waiting and what the museum is short of.

### Horses and the stable

A village keeps **horses, donkeys and mules**, every one of them a wild one brought home and
tamed by its rancher; nothing comes out of nowhere.
* **Bringing one home.** Horses and donkeys come into the world wild, on the plains and the
  savanna. The rancher goes out to one within sixty-odd blocks with something it eats in its
  hand — wheat, an apple, a golden carrot if the stores have any (out of the stores) — and
  walks it home with the animal following the hand: into the stable if there is one, to the
  pen or the rancher's ground if not. A donkey first, when the village sends caravans.
* **Gentling it.** At home it is the village's catch, and the rancher gentles it a go at a
  time, as you would: a bite to eat (wheat, an apple or sugar sweeten its temper by three, a
  golden carrot by five), then up on its back. It bucks. If its temper is up it stands for the
  rider and is **tamed**; if not, the rancher is thrown and the horse is a little calmer for
  next time (five more temper). Once tamed it is the village's own (the village is its owner),
  it gets a name by its colour — *Bay*, *Dapple*, *Chestnut*, *Ned* the donkey — and the
  chronicle says who tamed it after how many goes. Two tamed horses with room in the stable
  and a golden carrot each make a **foal**; a foal born in the stable is gentled when it is
  grown.
* **Saddles, leads and chests.** Nobody can make a saddle. The fishers land one now and then
  (about one catch in a hundred, near enough the game's own odds), a scout who comes on an
  old unopened chest out in the world (a ruin's, a temple's, a villagers' village's) looks in
  it and brings home any saddle or lead, and on **market day** the traders sell the village a
  saddle (24 coin, out of what the treasury can spare after the wages) when the stable has a
  horse without one. The rancher puts a saddle from the stores on a tamed horse, and a chest
  from the stores on a donkey or a mule when the village sends caravans. Leads are plaited by
  the rancher, two from four string and a slime ball, when the stores are short of them.
* **The stable** (planned, from the Stone Age on, once the village has horses of its own, or
  once an Iron Age town has a rancher and a saddle in the stores; on a lot by the square, near
  the storehouse; the council may put it before or after the other amenities): a
  tall timber barn with **four stalls** fenced off two by two either side of the aisle, hay at
  the front and in the loft, a sunken water trough and a cauldron, and three **gates** across
  the door, open four high so a rider comes in on horseback. Each horse has its stall and
  stands in it when it is not out. The gates open for whoever is going through and shut behind
  them. One that strays into the yard walks back in; further out, the rancher fetches it (rides
  it home if it has a saddle on, coaxes it home with a bite if not). Every evening the rancher
  goes round the stalls with the feed, a bite each of wheat, an apple or hay out of the stores.
* **Couriers ride.** A courier with a **long run** (forty-eight blocks or more) takes the
  quickest saddled horse in the stable, if the walk to the stable is worth it. Up in its stall,
  out through the gates and away: the horse goes where the courier would have walked, at its
  own pace under a rider — an ordinary horse is about **half again as quick as a folk at a
  run**, and a slow one is not taken. A few steps short of the chest it gets down, leaves the
  horse tied (it does not wander), empties the chest on foot, rides back, and ties the horse by
  the storehouse while it carries the load in. Another long run, and it is back on it; a minute
  with none, or the evening, and it rides the horse home and puts it in its stall. Its card
  says so: *"Riding Bay to the north mine."* Nobody rides a horse without a saddle.
* **Scouts ride** their rounds the same way, all day, and put the horse away when they are
  home.
* **Caravans take a donkey.** A caravan takes a donkey (or a mule) with a chest on it from the
  stable, on a lead from the stores: the carrier fetches it, ties it and puts the load **in the
  donkey's chest** rather than on its own back. At the other end the load comes out of the
  chest to be sold and the goods for home go back in. Home again, the carrier leads the donkey
  back to the stable and hangs the lead up with the stores.
* **Never lost.** A rider gets down where it is too low to ride under, or where it can get no
  nearer, and goes on foot. A horse whose rider could not get back to it stands where it was
  left, and the rancher brings it in.
* **Where to see it.** The **Jobs** page of the town's books has a line for the horses (how many,
  how many saddled, the donkeys and their chests, the saddles in the stores, the stable and
  its stalls, the rides today); the **Buildings** page has *The stable*, with every animal by
  name and where it is (*"Bay (brown horse), saddled: out with Holt, to the north mine"*). A
  rider's card has *Horses* (the horse it has out and how quick it is, its rides today); the
  rancher's card shows its **gentling** (*"a brown horse (temper 31 of 100, 4 goes)"*), and its
  top line what it is doing (*"Gentling a wild brown horse: its temper 31 of 100 after 4
  goes"*). `/village horses` says it all in chat.

### Built by hand

Everything the town does to itself is **done by somebody**:
* streets worn and paved, lamp posts;
* gardens, refacing and second storeys, beds made up;
* ground levelled, chimney fires, washing lines, scarecrows;
* market stalls, house and street signs;
* the jetty and the boat, the wall's ladders, the gates and the bell;
* headstones, new store chests;
* the road to a colony, and the iron golem (36 iron and a pumpkin).

The materials come out of the stores as before. Now the work also waits until a folk
from the village has walked to the spot, and then it is done in that folk's hands. The
hand is one the village can spare: one between trades, a carrier or storekeeper, one
whose own trade has nothing to work on, or the trade the work belongs to (a fisher for
the jetty, a guard for the gate). There is never more than one hand in eight on it at a
time — unless hands are standing idle in a trade that has more than it wants (or short of
their kit): then up to one in four, the extra ones only from those idle hands — and only by
day. A road crew works out along the road and keeps the ground around
it loaded as it goes. `/village status` shows who is at the town's works.

And nothing comes from nothing:
* **Bought, not given:**
  * the trades' hard-to-get workstations (a brewing stand, an enchanting table, an
    anvil, a hive with its swarm) are bought from a pedlar out of the treasury;
  * so is a drover's pair of sheep and hens.
* **Coin** comes only from goods the village sells, gold it mints and players. Errands
  and quests are paid from the treasury or a folk's own purse.
* **The Nether party** takes provisions and wears its tools. It brings back only what
  it had the means to get: blaze rods only with a blade or bow, quartz and glowstone
  only with a pick.
* **Settlers' kits:** a child's first tools come out of the stores, and a colony's
  settlers are outfitted by their mother village.
* **Gifts and pastimes:**
  * gifts and keepsakes come from what a folk has: its pack, the stores, or a flower
    it picks;
  * hobby fishing needs a real rod;
  * a pastime's prop in the hand is only for show, and is never dropped.

## What they do

* **Trades** — farmers first and most: at ten folk about four or five farmers,
  two miners, a woodcutter or two, a smelter, a fisher and a hunter. More trades
  open as the village grows: a watch, a carrier, a storekeeper, a rancher, the
  crafts. In a working town nearly two folk in five farm, and the food trades
  (farmers, fishers, hunters) are about half the hands; more again when the
  larder runs low.
* **Ground** — each trade claims its own plot (farms by water, woods, hillsides
  to dig) and works it. Plots are never shared.
* **Stores** — the village's goods are kept in the **Village Storehouse** (see
  above), and before it stands in the chests at the heart. Every chest and furnace a
  folk puts down is named **Village Store**. Folk use *only* containers with that name, so a village founded next
  to your base leaves your chests alone. To let them use a chest of yours,
  rename it *Village Store* in an anvil. A sign on a chest still hides it from
  everybody. The chests and barrels of a vanilla village a spawner takes over
  become its stores too. Your guest house's chest is never one.
* **The storehouse** — the village's stores are the chests round its square,
  and the storehouse's come first. Every load for the stores goes to the
  storehouse: a builder's leftovers, a miner's or woodcutter's surplus, the
  bread baked at dusk. When its chests are full, the load goes to the next
  store with room (the founding chest, then the granary, the market and the
  workshop). When every store is full, the carrier says so.
* **Carriers** — a carrier's round is chosen, not set with the wand. The
  workers' production chests come first, wherever the plots are. Then every
  minute it looks at every chest and furnace out to where the village's plots
  reach. That includes the farms', woods' and mines' chests and anything the
  furnaces have finished. It walks to the fullest and carries the load to the
  storehouse. When a pickup is empty, it picks the next one. Two carriers take
  the fullest and the next fullest, so they don't both go to the same chest.
  A carrier only takes a furnace's finished goods, never the ore or fuel in it.
  It leaves sixteen carrots and sixteen potatoes in a farm chest for planting,
  and never takes seed, saplings or torches.
* **Drawing from the stores** — anybody short of something goes to the stores
  for it: rations, a tool or the makings of one, fuel, ore, a chest, a furnace.
  When a trade's own supplies run low, a folk fetches them from the storehouse
  before it runs out:
  * a farmer's seed, carrots and potatoes;
  * a woodcutter's saplings;
  * a miner's torches;
  * a rancher's wheat;
  * a guard's arrows.

  Builders draw their timber and stone from the stores. Once the smeltery
  stands, the smelter works in it, lights its three furnaces and empties them
  every round. Once the storehouse stands, the storekeeper works in it and
  keeps it sorted from its first day.
* **Food** — wheat is baked into bread by whoever is idle (including everybody
  at dusk, indoors). A folk short of rations fetches some from the stores. A
  folk eats one ration every four and a half minutes of work. Every folk is sent
  out with carrots and potatoes as well as wheat seed, and any settler may sow
  them: a root plant gives three or so to eat and each of those is a plant
  again, where a wheat plant gives one ear.
* **Building** — one project at a time, one lead builder per project, on a lot
  chosen once on the village's own grid: storage, shelter, houses, a well, then
  (as the village comes of age) a wall, a smeltery, a meeting hall, a workshop, a
  watchtower, a market, a lighthouse, a chapel, a gateway, and after that the great
  works for ever. A house comes first while folk are short of beds; but in a town of
  twenty-four or more where all but four or fewer have one, the buildings the age asks
  for go up before the next house (a town having children faster than it can house them
  would otherwise never get to its hall). A lot is dry, within twelve blocks of the ground at the heart and
  no steeper than four blocks across; the builder fills the low side up to the
  floor and fells any tree in the way (keeping the wood). If the builders cannot
  get to a lot (three cells in a row out of reach and nothing standing) it is
  given up and another is chosen, and the builder puts everything it drew for it
  back in the stores for whoever raises it; a village that has been all round
  its lots without a find is less particular the next time. Stone is spent before planks,
  and planks before logs. For its
  first ten minutes a village keeps the founding planks, stone and chests for
  its storehouse.
* **Feeding itself** — farmers work any ground, wet or dry (a crop grows on dry
  farmland, slower, and never lets it dry back), and a farmer with no water on its
  plot has a bucket of water made from the stores (three iron) when the village is
  hungry.
  A hungry village — less than half a day's meals put by — turns a miner or a
  woodcutter whose stone or timber is piled high into a farmer, and a smelter with
  no ore burns logs into charcoal when the village is short of coal. A folk that
  cannot set its ground up for a whole working day finds new ground.
* **Evenings** — at home for the night a folk takes on a day's rations from the stores;
  two folk who are home and fed can raise a child (so a village that starts from
  two spawner items can grow).
* **Breaks** — one a day, at an hour of each folk's own.
* **Pace** — a folk works a little over twice as fast as a hired assistant and
  wears a tool a third as fast: a village that dug a block every five seconds
  raised one building a game day.
* **Days** — the day shift works the day, then goes to bed; the watch keeps the
  night in two halves, so every guard sleeps too. Nobody builds, mines or moves house after dark. Sleeping in a bed skips
  the night for everybody, wherever you are.
* **Ages** — Wood → Stone → Iron → Diamond → Nether, each with its own list of
  what the village needs, then great works with no end. A village that reaches a new age says so, once, in
  chat. Folk themselves never speak.
* **Growth** — two folk who are fed and in work, standing together, have a
  chance of raising a child, paced across the whole village, while there is room
  and the stores hold a day's meals for everybody. Cap: 100 by
  default, 500 at most (`villageGrowthCap`).

Right-click a folk to see what it carries and what it is doing. You can look;
only its owner (nobody, for folk) can rearrange the pack.

## Down the mines and back up

Every mine's stairs are kept with the world, step by step. Nothing takes the floor of a step:
not a gallery or a vein, not a quarry's next level, not a hand sent for stone. The head of a
mine's stairs used to be the nearest stone to a plot's middle, and it went first.

A miner climbing out mends its stairs as it goes. It lays a step's floor again where something
took it, and cuts out a block or a fall of gravel lying across the stairs. If it has no stairs
it can climb, it cuts its own up to the open sky, the way a player would, walling off water and
lava as it goes. Those stairs are kept too.

A run that ends deep down (liquid ahead, a cavity, a stuck step) never ends there: the miner
goes up its ladder, back up its stairs, or cuts new stairs out. A folk can also end up lost
underground away from any run of its own: a run cut short by the morning bell or a fight, or a
fall into a cave. Once it finds no way up it can walk, it is sent up of its own accord, by the
nearest stairs (mended) or by stairs of its own.

## The town's mine

A town has one mine, and all its miners work it together. It is opened the first time a miner
looks for ground: out on the best rock fifty to a hundred-odd blocks from the heart (further
when the town is big), clear of the town, off the farmland, short of a neighbour's border and
never on water. Hills count for more, having more rock above the seams. The place is kept with
the world, and the town's book records the day it was opened.

The mine is laid out in faces, each a miner's plot across (seventeen blocks). Each miner works
one face, beside the others, so their stairs and galleries run into one another and the rock is
dug out between them, down and across. When a face is worked out, nobody is sent to it again;
its miner takes the next free face, a ring further out and on the far side from the town, so
the mine spreads away from the houses as it is dug. A face the town has since built on is passed
over.

How deep the mine goes depends on the age and on what the town needs:

* to the iron (Y16) from the first day;
* up to the coal seam, for two miners in three, while the age is short of coal;
* down to the diamonds (Y-50, never lower, out of the lava) only from the Diamond Age, and only
  for a miner with an iron pickaxe.

Bedrock is never the aim.

No miner of a town cuts a block under its buildings (each with two blocks round it) or under
the square. Plots staked before the town grew out over them, and galleries that followed the
rock under the nearest houses, used to cut out the floor of a house, and once the middle of the
square. A miner whose plot the town has built over now moves to a face of the mine at the depth
it was working. Wherever it digs, a miner cuts only ground the world made: rock, earth, sand,
gravel, clay, the ores, ice, and a tree in its way. Cobblestone, planks, bricks, a path or a
field were laid by somebody, the town or a player, and are never broken. A miner lost under the
town climbs out through the rock, never through a cellar floor.

`/village mine` shows where the mine is, how many faces are worked out, and which face each
miner works and how deep.

## The cave dwellers

An Iron Age town of **twenty-five** or more, with a couple of miners at its mine and a watch on
its walls, sends a small, highly skilled **cave team** into the caves round it: half miners,
half guards. The team is **two** strong, **three** at sixty folk and **four** at a hundred,
never more. A cave dweller wears the miner's helmet and lamp, with the town's armour over them,
and a lantern floats over its head from far off.

* **Picked from the town's best.** The town takes its most skilled miners and guards, by their
  level and their years at the work. It never takes an idle hand of no skill just for being
  idle, and never a hand from a trade it is short of. A new cave dweller starts near its mining
  or guarding level and learns the caves fast. The most experienced one **leads**, and the
  Caves page shows each one's level. Cave dwellers have a **guard's health** (twice a folk's),
  and they are among the **best paid** in the town: dangerous, skilled work underground, in a
  small team (the Jobs page and their cards say so).
* **A plan before they go.** The leader works out how long the trip will be. A cave nobody has
  mapped gets **a day**: the team maps it, lists its veins, mines what is close and comes home.
  A known cave is planned from its list: the hours to mine the veins the team's picks can take,
  plus the walk there and back, over a working day of about ten hours of daylight. That comes to
  anything from **half a day** (a near cave with a vein or two left) to **four days** (a big cave,
  deep and far, with a long list). Veins worth little, of something the stores already have
  plenty of, are left out. If the age is waiting badly on iron or diamonds the cave has, the
  plan runs a day longer. The plan is cut down to what the town can spare: two meals a member a
  day and a day over, 64 torches a member a day, and no more than a day for a team short of its
  kit. The plan goes on the board and in the chronicle, for example *"Three days to the deep
  caves north-east: 14 veins listed (iron, the town's want), a day's walk there and back, food
  and torches for three."* The Caves page shows the plan and how it was worked out.
* **Kitted out by the town, free.** Before setting out each one goes to the stores. It gets the
  armour, blade and shield the watch has, and the best pickaxe in the stores. It gets at least
  64 torches (more for a long trip), food for the days planned and one over, cobblestone for
  the nights' camps, a crafting table, a few planks and sticks, and three iron ingots if the
  town can spare them. It leaves what it does not need, such as seed, saplings and a bench, in
  the stores. The town's pieces carry its mark and go back into the stores when it takes up
  another trade. Nothing comes out of its purse.
* **The town's torches.** Every torch the team carries is made by the town from its own coal or
  charcoal and sticks. The smelter, the smith and the shop top the stores up for the team ahead
  of its next trip. The team only draws what the town can spare over its own lights, its miners'
  and its street lamps, and never takes the last of them. If the town cannot spare 64 each, the
  team goes with what it can spare and keeps the trip shorter. With too few to go at all, it
  waits for the makers.
* **Together.** The team goes out as one. They walk in order, the leader first, each a couple
  of blocks behind the one ahead, never more than a few blocks from the leader. The leader waits
  for anyone who falls behind and goes back for them. In a fight one takes on a lone monster
  while the others work on, and they all join in against a group. They shout a warning about a
  creeper and back off from it. If one of them is badly hurt, they all go home together.
* **Every vein.** In a cave the leader looks through the walls, the floor and the roof for ore.
  Every vein showing, and every vein up to three blocks behind the rock face, goes on the cave's
  list, and whoever is nearest calls it out ("Iron here!"). The leader picks the vein the town
  wants most first, then the nearest. For a hidden vein they cut a short tunnel in to it. Each
  member digs its own block, and two never dig the same one. They cheer at diamonds. They never
  dig the block they stand on, a block with lava or water behind it, a step of the miners'
  stairs, a portal's obsidian, or anything in a town. Obsidian with no diamond pick in the team
  stays on the list, waiting. When every vein is done, the cave is worked out and they move on
  to the next one. They come back to the waiting veins once the town has a better pick.
* **Light where it counts.** Torches cost the town, so the team does not cover the cave in them.
  The leader sets one about every 15 blocks along the way in, up to 20 in a straight passage and
  closer at a turn or where passages meet, so the way home is clear. Anyone mining or fighting
  where it is dark enough for monsters to spawn sets one there. What they set stays in the cave
  for the town's later use.
* **Made on the spot.** Low on torches, a cave dweller makes more from the coal it has mined and
  its sticks (one coal and one stick for four torches; a plank makes two sticks, a log four
  planks), so coal mined in the cave turns into light there and then. When its pickaxe or sword
  is about to break, it sets its crafting table down, makes a new one and picks the table up
  again. It uses iron ingots if it has them, otherwise cobblestone it has cut, otherwise wood.
  It only makes a diamond pickaxe when the cave's list has a vein waiting for one that the town
  wants. Otherwise the town wants its diamonds in the storehouse. Before a long dig it makes a
  spare pick if its own is half worn. Every recipe is the game's own, and everything comes out
  of its pack. What it made goes into the story of the trip.
* **Lava, water and holes.** They wall off lava and running water with cobblestone, patch holes
  in the floor so nobody falls in, and put themselves out with a water bucket if they carry one.
  They put torches all round a spawner so nothing more comes out of it, leave it standing, and
  note it.
* **When to come back.** They come home when the plan's time is up. If the work is going well
  and their food and torches hold, they stay another half day, twice at most. The leader turns
  them back early, and says why ("We turn back: the torches were nearly gone"), when:
    * the torches are nearly gone and there is no coal or wood to make more;
    * the food will not last the walk home;
    * one of them has no tool and nothing to make one with;
    * a pack is full;
    * one of them is badly hurt with nothing to eat, or one of the team is lost;
    * the cave is worked out;
    * there is more down there than they can take on, such as a warden or a crowd of monsters.
* **Nights underground.** On a trip of more than a day, at dusk they find a nook, wall it in
  with cobblestone and set a torch inside. They eat, and sleep in turns while one keeps watch.
  At first light they eat again, take the walls down and go on. While they are away the town
  still counts them as its team: nobody takes up their trade, their beds stay theirs, and their
  cards and the Caves page say "on an expedition, day 2 of 3". If they are a day overdue, the
  town sends out a search party. After a restart, they take up the trip again where they are.
* **Old chests.** They open the chests the world left: a mineshaft's carts, a dungeon's chests,
  and the chests of the jungle temples, strongholds, ruined portals, igloos and shipwrecks. They
  take what is worth carrying: ore and metal, gems, enchanted books, golden apples, saddles,
  name tags and music discs. They leave the bones and the string. They only open a chest that
  still has the world's loot in it, or one inside something the world built. They never open a
  named chest, a chest near any town, or a chest in a village of villagers. They leave the deep
  dark's ancient cities and the trial chambers alone. They only open a desert temple's chests
  once the TNT trap under them is gone.
* **Never trapped.** They come home along the leader's marks. If one of them is stuck, it cuts
  itself a step. If they are underground with no way they can walk, they cut their own stairs
  up, as a lost miner does. If even that fails, they call for help, and the town's search party
  goes out for them. They are never lifted out.
* **Home.** The town comes out to greet them, and a long trip home with a big haul is the town's
  news. Every one of them walks to the town's **storehouse** and puts its whole haul in, booked
  in the storehouse's books as brought in by that cave dweller and counted as its work. With no
  storehouse, the haul goes into the stores at the heart. Unused torches and makings go back
  too. The chronicle tells the trip as a short story: who went, where, the veins listed and
  mined, the fights, the spawners lit, the nights camped, what they made down there, and the
  haul, with the torches set and drawn.

**Where to see it.** The **Caves** page of the town's books (the last tab) has a map of the
finds round the town. Beside it, scrolling, are the team with each one's level (the leader
marked) and its card, the trip under way or the last plan with how it was worked out, the
torches drawn and set, each cave with its list of veins (mined, waiting for a better pick, or
still to do), every find with its coordinates, and the hauls brought home. The board shows the
plan while the team is out. The chronicle and the gazette tell of diamonds, a mineshaft, a
dungeon or a temple's treasure, and so does the next morning assembly. A cave dweller's card
shows its level, its place in the team, where it is on the trip, the torches set and drawn,
and its kit. Ask anybody "What's down in the caves?" (or press **Underground** on the Village
tab); a cave dweller gives the exact spot.

**Commands.**
* `/village caves` lists it all, and `/village caves books` opens the page.
* For operators, `/village caves now` sends the team out at once, picking it first if the town
  wants one and has none.
* For operators, `/village caves stage` cuts a small cave beside you, with ore in and behind its
  walls and an old chest, and sends the town's team into it.

## Names

There are close to six hundred names: hedgerow and meadow, birds and beasts, old names, trade
names, the lie of the land, the weather and a few fond nonsenses. A town's folk are named at
random from every name nobody in that town has yet: founders, newcomers, villagers taken over
and children born. So no two towns begin with the same folk. Your own crew's names still go
down the list in order, and the rename screen pages through all of them.

## Watching it grow: fast time

The whole world can run faster, so you can sit back and watch a village grow: fields
ripen, days pass, folk work and houses go up at that pace.

* **Keys:** `]` a step faster (2×, 4×, 8×, 16×, 32×, 64×, 128×, 256×, then *max*),
  `[` a step slower, `\` back to normal. Rebind them under Controls.
* **The village journal (J):** a row of buttons — 1×, 2×, 4×, 8×, 16×, 32×, 64×, max.
* **`/village speed 16`**, `/village speed max`, `/village speed normal`; `/village speed`
  on its own says how fast time is running.
* The top right corner of the screen says the speed asked for and the speed the server
  is really managing. *Max* is as fast as the machine can go. A big village on a slow
  machine may manage less than the speed asked for; the corner shows when that happens.
* This uses the game's own tick rate (what vanilla's `/tick rate` sets), so it speeds
  up everything, you included. Watch from somewhere safe, or in creative or spectator.
* Operators can change the speed, and so can the owner of a single-player world, with
  or without cheats.

## Commands

* `/village list` — every village the game knows of: where, how many live
  there, what age, what has been built. Works from the console.
* `/village top` — every village side by side, the biggest first: its folk, its age and how
  many days old it is, what it is worth (its treasury and its stores at the market's prices)
  and its renown. Works from the console.
* `/village level` — (operators, or the world's owner) the ground round the nearest town made level
  as a founding makes it: about as wide as a founding of its size, trees cleared, hills cut and
  hollows filled; anything built, and the ground under it, left as it is. Its folk carry on, and
  none is buried or hurt as the ground moves. The action bar says how far it has got.
* `/village found <count> [x z]` — (operators) found a village of that many (2 to 500)
  where you stand or at x z, exactly as the founding screen's **Confirm and spawn** does: on
  the board waiting there, or on one put up for it. `found board [x z]` puts the board up
  alone, as placing a spawner does; `found screen [count]` opens the founding screen of the
  waiting board nearest you; `found status` says how every founding is getting on.
* `/village tp <name>` — (operators) go straight to a folk by name, the nearest of that name
  first, in any world: set down on the ground beside it, facing it, never inside a block or over
  a drop. From the console, name who goes: `/execute as <player> run village tp <name>`. The
  town's books and a folk's card have the same as a button for a player in creative.
* `/village status` — age, headcount, trades, what the stores hold, what has
  been built, what the village is short of. Works from the console.
* **The village journal (J key)** — the same, for the village you stand in, on a page
  you can scroll, a line to each part. Rebind it under Controls ("Village journal").
* `/village news on` / `off` — the morning news of the villages within 256 blocks of
  you, in chat, once a morning: what happened yesterday and what each is short of.
  Off unless you turn it on; the choice is kept with you.
* `/village people` — who everybody is: trade, temperament, partner, friends,
  rivals and family, under a line on the village's couples and friendships.
* `/village knacks [name]` — the knacks each folk of the nearest village chose for itself (its
  points, each knack with its day and its reason, how far to the next point); with a name, that
  folk, and the knacks still open to it with how much it wants each. `knacks grant <name> <key>`
  (operators) gives a folk a knack as though it chose it (`nest_egg`, `steady_hands`, ...).
* `/village house` — the nearest village's houses: who lives in each, on what terms, and
  what is for sale. `house buy` buys the empty house you stand in; `house let <coins>` lets
  yours out at that rent a day (0 to take it back); `house rent` collects the rent.
* `/village bank` — the nearest village's bank: the vault, the deposits, the loans, what it keeps
  back and may lend, last week's interest and the treasury's share, every mortgage and the biggest
  savers, and your account. `bank deposit <coins>` / `bank withdraw <coins>` for your account;
  `bank mortgage` buys the empty house you stand in with a fifth down; `bank repay <coins>` pays
  your mortgage down. `bank week` (operators) runs the bank's round now; `bank showcase`
  (operators) puts a bank up ten blocks in front of you (on past any of the village's buildings,
  every tree reaching into it felled whole and the ground cleared first), opens it and holds its
  banker at the counter facing the door, with the bars and the ledger in (for the screenshots);
  run again, it puts the banker back at the counter; `bank showcase done` lets the banker go.
* `/village flats` — the nearest village's blocks of flats: each block's name and storeys, every
  flat, who lives in it, its rent and what its household has put by for a house; and whether the
  town wants another block, and why. `flats stage` (operators) sets a furnished block out on a
  stage where you stand, for the pictures.
* `/village stats [page]` — the town's books on the analytics screen (as clicking the village
  board does), opened at a page if one is given (0 the Overview to 18 the Board; 12 is the Stock,
  13 Research); from the console, the reading of what drives the village's growth.
* `/village school` — the nearest village's school: the schoolhouse, the teacher (why, its pay,
  how far a schooling under it goes), this morning, every child with the trade it leans to, its
  level so far and its mornings, and who has left school. Works from the console. `school page`
  opens the town's books at the School page; `school lesson` (operators) calls a lesson now,
  whatever the hour, for two minutes; `school say` (operators) has the nearest teacher say a
  line of the lesson; `school stage` (operators) sets a schoolhouse out mid-lesson where you stand,
  for the pictures, on the land itself (its lot levelled to the ground's height there, the edges
  sloped back into the land; `/kill @e[tag=folk_lineup]` clears its folk).
  board does), opened at a page if one is given (0 the Overview to 18 the Board and 19 the Museum;
  12 is the Stock, 13 Research); from the console, the reading of what drives the village's growth.
* `/village research` — the city's research: points in hand and a day, what is being studied,
  who chose it and why, and every branch's civics with their keys, states and costs. Works from
  the console. `research pick <civic>` and `research grant <civic>` (operators) set the town to an
  open civic now, or have it done at once (see *The city's research*).
* `/village shop` — the sellers' books: for the shop, the café, the tavern, the market and the
  stores, what each ware has on hand against what is kept, what sold today and this week, what
  was wanted and not there, what was made, its price and markdown, and what it is short of.
* `/village stall` — the players' market stalls in the village you stand in: whose, where, rent
  paid to, the till, the week's sales, each thing at its price against the going price, the last
  sales. `stall rent` rents one (or pays another week), `stall screen` opens yours, `stall till`
  takes the till, `stall price <coins> <item>` sets a price (-1 the going price, 0 kept back),
  `stall books` opens the town's books at the players' stalls; `stall market` (operators) brings
  the nearest folk with coin to every open stall to buy as on market day, and `stall lapse`
  (operators) has your rent run out days ago, to see a stall given back.
* `/village workshop` — the shop's workshop: its keeper and hands, what each made today and of
  what, its order book against the stock, and what the age lets it make and the next will.
  `workshop blueprints [word]` — the blueprints the makers know and the age each belongs to;
  `workshop order <item> [count]` — put something on its order book for you; `workshop books` — the
  town's books opened at the workshop; `workshop hire`, `workshop work` and `workshop stage`
  (operators) take the nearest grown folk on as a hand, have every maker do a piece of work, or (the
  client smoke) put a shop up by you if the village has none, with a keeper and a hand at work in it.
* `/village watch` (or `watch kit`) — the watch's kit: each guard and what it wears and carries, what is
  on order for the watch, who makes it and what it is waiting on, the shop's book for the watch, what
  was made for it today, and what it has cost the town. `watch now` (operators) fits every guard out of
  the stores at once; `watch stage` (operators, the client smoke) stands three guards in a row on the
  nearest dry, open ground to you, in leather, iron and diamond (clear them with
  `/kill @e[tag=watch_kit_lineup]`).
* `/village mine` — the town's mine: where it was opened, the faces worked out, who works which face and how deep
* `/village stores` — the storehouse's day: its slots, its storekeeper, what went in and out
  and who served the requests, its last tidy, its staff and their runs, and its run list.
* `/village sweeper` — the street sweeper's day: what it swept in, by whom, and how much is
  lying about the town now. `sweeper appoint` (operators) makes the nearest grown folk the
  storehouse's sweeper at once, whatever the size of the town.
* `/village decor` — every home of the nearest village: who lives there (their trades and
  favourite colours), how it is furnished out of ten, what is in it and what it waits on the stores
  for. `decor now` (operators) furnishes the homes as far as the stores run to and sees to the
  candles; `decor showcase` (operators) sets a furnished home out where you stand, for the pictures.
* `/village bell` — the town bell: where it hangs (or that there is none yet), today's bells, who
  rang them and how many answered, and its frame (`FRAME-AT x y z ALONG .. FACING ..` and how far on
  it is). `bell ring dawn|noon|dusk` (operators) has it rung now, by whoever would ring it, and the
  town answers it; `bell call dawn|noon|dusk` sends its ringer to it to ring it there.
* `/village founding` — when the town was founded and its next Founding Day; `founding now`
  (operators) keeps it this minute, before the board.
* `/village birthdays` — whose birthday falls this week; `birthdays now <name>` (operators) has
  that folk keep one today, and its friends go round with presents.
* `/village lifespans` — the nearest town's folk, eldest first: each one's age, the day it was
  born, the age it will live to and the day that falls on, and how many of them are old. Grown folk
  age a year every five days. Works from the console.
* `/village version` — which version of the mod is loaded: its number (0.<build>.0, one higher
  with every update) and the newest change in it. The jar's name carries the same number.
* `/village sights` (operators) — where the nearest town's newer sights are: the welcome sign at
  the edge of town, the gazette's lectern, today's crier and where it reads, the children's game
  and its playground, and every household with its children, its chest, its garden and its pet.
  `sights sign`, `gazette`, `crier`, `tag`, `hide`, `pet` or `garden` makes that one now rather than
  later in the day, out of the same stores and purses as ever (only the walk is skipped).
* `/village districts` — the nearest town's quarters: the plan in a line, how many buildings
  each quarter has, which works are at work, the homes in the smoke and din and the homes by the
  park, and how the park is coming on. Works from the console. `districts map` opens the books at
  the Buildings page's map; `districts park now` (operators) puts the park up at once on its lot,
  as the showcase does, with its trees grown and its paths laid, and sends everybody off work to
  it (it prints `PARK x y z facing dir`); `districts park visit` (operators) sends them again.
* `/village horses` — the stable: the village's horses, donkeys and mules (saddled, with a
  chest, being gentled), each by name and where it is, who has one out, and the saddles and
  leads in the stores.
* `/village chronicle` — the nearest village's history, as a written book.
* `/village museum` — the nearest village's museum: its curator, what is on show and who found
  each thing, the archive's volumes and where they stand, what waits to be bound, and what it is
  short of. `museum work` (operators) has the curator do its next piece of work now, out of the
  stores; `museum stage` (operators) sets a museum out where you stand for the pictures, on a
  forecourt of smooth stone, its places filled with one of everything, the chronicle so far bound
  into its archive, and its name and the town's banners up over the door.
* `/village larder` — the nearest village's larder against its mouths (grown a day, eaten a
  day, whether a child may be raised and why), its coal and charcoal against the floor it keeps,
  who carries the builders' stock about, and what its dead died of. `larder charcoal`
  (operators) has its smelter burn logs into charcoal now, if the village wants it; `larder fields`
  tells of each farmer's field. (It used to answer to `/village economy` too, which hid the economy
  page: that is `/village economy` again.)
  who carries the builders' stock about, and what its dead died of. `economy charcoal`
  (operators) has its smelter burn logs into charcoal now, if the village wants it.
* `/village wages` — what every job in the nearest village is worth and pays, part by part, and who
  is paid what and why. `wages show` opens the page on your screen, `wages books` the town's books
  at the Jobs page, `wages card [n]` the card of the n-th best paid; `wages reckon` (operators) draws
  up the day's pay scale now.
* `/village standing` — what every village you have met thinks of you.
* `/village ledger` — the nearest village's town ledger, as a book.
* `/village relations` — every pair of neighbouring villages: allies, friends, uneasy,
  in a feud, how far apart, and whether they are kin.
* `/village jobs` — the nearest town's job market: its notices and how each went, the
  applications with each applicant's level, age and knacks, why it applied and the verdict,
  who is on the road here, what other towns want that word of has come here, and who came and
  went this week. `jobs why <name>` says what a folk would make of the notices (its reasons, or
  why it stays); `jobs books` opens the city books at the job market. For operators: `jobs post`
  (the town looks over its notices now), `jobs decide` (its leader decides the applications now),
  `jobs look [name]` (a folk, or the one with most reason to, goes to read the board now: it reads
  the notices out, and applies only if one is for it and it has a reason to go), `jobs want <trade>`
  (the town puts a notice up for that trade now, at the trade's real wage, whatever it is short of),
  and `jobs pact` (the nearest town and its nearest neighbour agree to trade, so word of their
  notices passes).
* `/village talk [words]` — (operators) talk with the nearest folk, as a right-click
  would; with words, say them to it.
* `/village lineup` — (operators) one folk of every trade, dressed and holding
  its tool, stood in a row in front of you to be looked at.
* `/village speed [times|max|normal]` — run time faster to watch a village grow (see
  *Watching it grow*). Operators, or the owner of a single-player world.
* `/village folk` — one line per folk: trade, ground, status, job, what is
  missing, what it carries (`pack=`), what it last tried at the essentials
  gate (`gate=`), and a trail of the jobs it has run.

## Config (`config/mc_assistant-common.toml`, section `[villages]`)

`naturalVillages`, `villageSpacing`, `villageMinFolk`, `villageMaxFolk`, `villageCharterFolk` (70),
`villageFoundingMost` (500), `villageBreeding`, `villageGrowthCap`, `villageLoadedChunks`, `replaceVillagers`,
`protectTradedVillagers`, `villageColonies` (on), `villageColonyAt` (40),
`villageWorldCap` (200).

* `villageCharterFolk` (70) is how many a Village Charter founds a village with, and where the
  founding screen at a spawner's board starts; a player may choose 2 to `villageFoundingMost` there.
  Villages the world grows on its own start with `villageMinFolk` to `villageMaxFolk` (8 to 12), and a
  colony with `villageMinFolk`.
* `villageFoundingMost` (500) is the most a village may be founded with, at the board or by charter.
* `villageGrowthCap` (100) is the largest a village grows by raising children.
* `villageBuildSpeed` (100) is how fast village builders lay blocks, as a percentage:
  200 is twice as fast, 50 half.
* `villageReshapeLand` (on): off keeps your terrain as it is. The town's ground isn't
  levelled, no sand is dug and no irrigation channels are cut. Buildings still get the
  footings they need.
* `villagesShareGoods` (off): every village is its own. It keeps to its own stores, chests
  and treasury and never touches another village's, however close the two are. On, villages
  on good terms may send each other goods and coin outright: a hand when one is short, food
  for an ally, tribute to a bigger neighbour, an envoy's gift. Either way, trade between
  villages goes by caravan, on the road, paid for, under a pact.
* `villagePhantoms` (off): on lets phantoms come over the villages as they do anywhere else.
  Off, none spawns over a village (its town's reach and twenty-four blocks round it) and a
  stray that flies in is seen off. Phantoms never go for the folk either way.

## Keeping it steady

* **Across a restart.** Things a player borrowed, the day's move under the elder's
  orders and the day's petition are kept with the world. After a restart the gates are
  set right for the hour, and an animal left on a lead mid-fetch is let go.
* **Stuck folk.** A folk that gives up twice on the same spot is wedged. It digs or
  climbs its way out (village folk only; a hired assistant never digs through your
  walls). A miner stuck below ground walks back up its own workings.
* **Carriers** deliver what they hold before they take their break.
* **The server's time.** Each village's land work (levelling, houses, docks) runs on its
  own tick, a fifth of the villages at a time. Any village whose work takes over 25 ms
  is written to the log as `[MCA-SLOW]`.

## How this is tested

Compiling proves nothing about a system this large, so the village is *run*.
Every push to CI:

* boots a real headless server and runs the game tests in
  `src/gametest/java` — recipes load, the spawner block, the charter and the
  commands work, a village founded at a chosen size on rough ground ends on level
  ground with sloped edges, a vanilla villager is swapped for a folk, and twelve folk are
  left alone for **three game days** on ground of the test's own making;
* starts a real dedicated server with vanilla world generation, settles a
  village in a chosen biome over RCON (`tools/realworld/soak.py`), lets three
  game days pass at full speed, puts a raid of zombies, skeletons, creepers and
  spiders among the settlers at dusk, and reports — for plains, forest, taiga
  and savanna;
* settles a hundred folk on one map and reports what that costs a tick;
* checks a generated vanilla village's villagers are converted without
  freezing the server;
* builds a storehouse on a hillside and among trees (game tests `t11`, `t12`),
  so the ground work — filling the low side, felling the tree in the way — runs;
* plays **the long game** in a workflow of its own that a push never cancels
  (`village-long-soak.yml`): one village founded the way a spawner founds one, left
  alone for up to sixty game days, with a line a day saying how many folk, which
  age, how many buildings, its renown and how many villages the world now holds —
  published every ten minutes while it runs (`real-epic.txt`);
* plays **a hundred days** (`village-hundred-days.yml`, started by touching
  `tools/realworld/hundred.trigger`). A runner has six hours and a big town runs
  slower by the day, so it is run in legs: each leg loads the world the last one saved
  and carries on from the day after. One line and one row of numbers a day go to
  `real-hundred.txt` and `hundred-metrics.jsonl`: folk, age, buildings, stores,
  coin, contentment, the atlas, pacts and allies, who is at the town's works, and
  how the wealth is spread. Each folk's line in a report ends with where its day went
  (`day={job FARM 42%, asleep 37%, ...} wet=1%`), sampled once a second, and a farmer's
  with whether its field is on ground the village can walk to (`reach=27570/ok`);
* gathers the village for its morning assembly and checks folk take their places and
  the elder speaks (`t50`), and holds an election (`t51`);
* sends an envoy to offer trade and checks the other village gathers to hear it and
  answers (`t52`);
* runs the job market between two towns two hundred blocks apart (`jm01`): a town short of a
  miner puts a notice up, a town that has never met it hears nothing and one with a trade pact
  hears of it the same day; that town's three idle miners walk to their own board, read it and
  apply; the leader takes the twelve-level miner of thirty and turns down the fifteen-level one
  of sixty-four (too old for the mines) and the learner; the one taken on walks the road, joins
  the new town's roll and takes up mining, and its card and both towns' books say so. Four folk
  whose beds a raid burned are taken in by a friendly neighbour with beds to spare, found a bed
  and work (`jm02`); and of two folk sent to read the board, the one out of work applies and the
  one with no reason to move does not go (`jm03`);
* sends a scout 260 blocks to a town it has never seen and checks it finds it, comes
  home and can tell you where it is (`t53`);
* checks a street is not laid until a folk from the village has walked to it (`t54`);
* checks the wages are shared out fairly when coin is short, that what the village is saving
  for stays in the treasury, and that the tithe brings coin back (`t55`);
* checks a hunter takes a grown cow and leaves the last pair of pigs to breed (`t56`);
* checks the village's books: a farmer's bread counted at the market's worth, its stone not
  (`t57`);
* checks a sheep is coaxed home with wheat held out, no lead, and kept as the herd's (`t58`);
* raises a house and a storehouse on the plan's lots and checks the town's life round
  them: the chimney fire, the door's number and names, the washing line, the windows lit
  by night and dark by day, a street sign, the stalls and their goods, a scarecrow
  (game test `t29`);
* lays the road between a village and its colony, over a pond, with its signposts,
  and sends a caravan of bread down it and home again (game test `t31`);
* puts the council to the vote, makes a player a citizen, fines, tries and banishes a
  thief seen at the stores, lets two villages built too close together fall out, makes
  peace between them, and raises a statue to a hero (game test `t36`);
* takes on and pays out quest-board postings, hires a folk who comes home with a story and
  what it carried, commissions a house from a player's makings, writes the town ledger,
  and has the storekeeper give, lend and sell (game test `t37`);
* gives the trades their kits and runs them for real (game test `t40`): the first brewer
  brings a stand, blaze powder, wart and soul sand, and the village gets one kit only;
  in a brewery built without stands it sets its own down where the drawing has one;
  water and wart become awkward potions twenty seconds later, a glistering melon goes
  in (made of a melon slice and gold), and three potions of healing come out to the
  stores; the beekeeper sets down the hive it brought, the swarm comes out, and a
  second hive is made of three honeycomb and six planks;
* puts a farmer's field by the nearest of two ponds, and checks that monsters about a
  village with no wall don't ring the bell (game test `t42`);
* runs two villages a thousand blocks apart side by side, with the ground round each
  loaded and dropped in turn as two players coming and going would (scenario `twin`),
  and a village alongside JEI and Jade (scenario `modpack`);
* keeps the long game's numbers day by day (`epic-metrics.jsonl`), the last twenty
  builds' side by side (`epic-history.json`), and a page of charts drawn from them
  (`epic-dashboard.html`) on the `village-test-latest` release;
* puts beds into a house that went up without them, from the stores' wool and planks,
  one at a time and no more than the wool allows (game test `t43`);
* runs the links between the trades (game test `t41`): the first farmer brings cane and
  plants it on the water's edge, then cuts it down to its bottom; a rancher milks a cow,
  walks a wild sheep twenty-six blocks home on a lead and keeps the lead, then — nothing
  wild left — buys the drover's pair once; a smelter digs sand off a pond's bed for
  glass; a hurt guard drinks a healing potion; the cook bakes a cake and sends the
  buckets back;
* picks the flattest ground among rough ground, levels a knoll and a hollow in a town,
  grows a house a second storey in brick with a garden fence, runs a jetty out with a boat,
  and irrigates a dry field (game test `t38`);
* lives a life (game test `t35`): a child apprenticed to a parent grows up into the
  trade with a few levels' knack; a folk past its years dies in its sleep and is
  recorded among the dead; a graveyard gets its headstone with the name on it; the
  register remembers the dead and the families; and a player buys a round in the
  tavern for the two in there, a coin a head;
* ages its folk slowly (game tests `ag01` to `ag03`): founders raised today are eighteen
  to forty-five and fifty days later ten years older, not a hundred; a world saved before keeps
  everyone's age; round birthdays come fifty days apart; and a folk dies in its sleep at the
  end of its years, told frail first;
* checks how a village is doing (game test `t34`): contentment rising when the larder
  fills, bare hands slower than a wooden pick and wood slower than iron (ore slower
  than stone), the day of rest's morning service with nobody at work, and a widow
  free to love again and grieving;
* raids a walled village at night (game test `t33`): four gates hung, the bell and the
  ladders put up, a raiding party at a gate, every gate shut and the bell ringing, the
  guard up its ladder and holding its post on the wall while the farmer stops work, then
  the bell stopping when the band is beaten off, the raid in the village's history, the
  guard back down, and the gates open again in the morning;
* runs every craft once from one village's stores (game test `t32`): the blacksmith
  makes iron tools, the tailor a bed, the beekeeper sets down the hive it brought and
  takes its honey, the brewer sets down its stand and loads it with water and nether
  wart, the enchanter sets down its table, binds a book and enchants a tool, the cook
  makes three apple ciders; the café's counter shows the cider with its price tag,
  a folk buys something there, and a player buys a cider and the enchanted thing
  from the shop's counter;
* runs the sellers' benches and books (`ShopGameTests`, sh01 to sh05): a shopkeeper with nothing
  but logs and iron in the stores saws planks, cuts sticks and makes a tool for its shelves, with
  its mark, never into the builders' sixteen logs or the smith's sixteen bars; a cook with wheat
  bakes bread for its counter and stops at the farmers' seed; candles that sell out every day are
  kept more of and flower pots that never sell are cut to one, the slow pots marked down and
  nothing sold under cost; an Iron Age village saving its iron gets chests but not a bar beaten
  into a bucket; and the storekeeper makes a chest to order and says why it cannot make a diamond
  pickaxe;
* keeps a player's market stall (`StallGameTests`, st01 to st05): a player rents one (the rent into
  the treasury, a booth of the stores' timber or the player's own barrel and sign, their name on its
  sign), stocks it with bread a little under the going price, and a folk out for food buys a lot of it
  (its purse down, the till up, eight loaves out of the barrel and into its pack); at the market's own
  stalls a second folk buys there as at any seller, and the stall's screen has the till, the bread
  against the going price and both sales; bread at thirty doesn't sell, the folk keep their coin and
  the books say it was too dear, and brought down to a fair price it sells; the till pays out to the
  player and the town's books list the stall; a stall whose rent runs out shuts, is given back with
  its bread kept in it, every loaf goes back to the player and another takes the stall; and on market
  day a folk comes to the stall of its own accord and buys apples;
* furnishes homes and sells luxuries (`DecorGameTests`, dc01 to dc03): a smith's house is given the
  anvil out of the stores, against its wall with the way in clear, and its colours are waited on; a
  well-off farmer buys a carpet and a candle the shopkeeper made, its purse down and the shop's
  takings up, the stores one fewer of each and not a coin made or lost, and both are set out in its
  house; and the candle is unlit by day, lit at dusk and snuffed after bedtime;
* measures the pace of work (`PaceGameTests`, pc01 to pc05): stone breaks quicker at level 10
  than at nought and quicker again at 30, with every other piece of work, the bench and the
  fisher's wait following; better picks, axes and hoes are quicker at the same level (a farmer
  with no hoe slowest); a builder lays quicker at 10 and 30, and for blocks laid; an old master
  and an old beginner work exactly as quick as they did young; the About card has
  the pace line; and a carrier of level 20 walks 5% quicker;
* runs the folk's own knacks (`KnackGameTests`, kn01 to kn05): points at every fifth level, six at
  most, none for a child; a Homemaker renting its house chooses Nest Egg and has 30% of the price put
  by toward it (never the whole), or set aside and paid later when the treasury is empty; a Merchant
  chooses a money knack, a miner a miner's, a cheerful Free Spirit Bright Spirit; a trade's knack
  quickens only its trade; and the knacks survive a save and reach the talk screen's Skills page;
* checks the beds and the treasury (`BedsAndTreasuryGameTests`, bt01 to bt03): one house and six
  folk with nowhere to live, the household in its own bed and three lodging in the beds it can
  spare, no more; one marrying in has a bed at home at once, a lodger giving one back; a second
  house and everybody has a bed, no two the same; a grown child wed and living at its parents' waits
  with its partner and baby for a house and moves out with them; and a tenth of every wage kept in
  the treasury as tax over eight paydays on less coming in than goes out, the treasury never below
  nothing, no coin from nowhere, the tax in the books, none from the poor and none while the
  treasury holds a week's wages;
* keeps the town's calendar (`BellGameTests`, b01 to b04): a town of six with a bell lies in before
  the dawn bell, is rung up by a ringer who walks to the bell (three strokes, the bell swinging) and
  goes to work; at the noon bell (six) most of it goes to its midday meal and eats; at the dusk bell
  (nine) the day's work stops, the hands go home to their beds and the guard goes on watch; on a
  folk's fortieth birthday its friend walks round with the flower from its own pack, which goes from
  the one pack to the other as a keepsake, and the birthday and the present raise its spirits; and
  twenty-eight days after the founding the town gathers for Founding Day, hears the year's chronicle
  read out in the order it happened, beginning with the founding, feasts, and the history notes its
  first year kept; and a town whose bell stands under its board builds the bell its own frame on the
  square, clear of the board and its courtyard: log posts, a roof cut from ten planks with the rest
  of the batches put by, two lanterns out of the stores; the old bell is taken down and hung in it,
  and rung there;
* runs the museum and its archive (`MuseumGameTests`, mu01 to mu03): the miner's first diamond in
  the stores is chosen, taken out with a frame and a sign (no more), hung in a frame on the
  museum's wall with a label saying who found it, at what trade and on what day, and the town's
  renown rises by three; a year of the chronicle is written into a book and quill made of the
  stores' book, feather and ink sac, signed with the year's title by the curator and laid open on
  the archive's lectern, every page and line fitting the book; and a curator does it on foot, the
  stores keeping the find back from the makers while it walks;
* runs the horses (`StableGameTests`, hs01 to hs03): a rancher coaxes a wild horse home to the
  stable with wheat out of the stores and gentles it, thrown a go at a time, till it is tamed, the
  village's own with a name; a courier with a long run takes the saddled horse from the stable,
  rides it out quicker than a folk can run, gets down by the chest, rides back, and puts it back in
  its stall; a caravan's donkey with a chest carries the bread in its chest, the colony buys it out
  of the chest, and the donkey is led home to the stable and the lead hung up with the stores;
* checks the money (game test `t30`): a new village's purse, gold minted into coin,
  a day's wages, prices that move with the stores, a folk's market-day treat, a
  player buying bread at a stall and selling iron, and the stalls' price signs;
* fills a storehouse from a field's chest and a furnace's output with a real carrier,
  and has a farmer who has run out of seed fetch it from there (game test `t28`);
* raises the later ages' buildings and a great work for real (game test `t14`), and
  checks the order a village builds in all the way past the last age, and that a
  grown village founds a colony (`t15`, `t16`);
* runs a real game client on a virtual display (software OpenGL), has it join a
  server, stands a village and some armoured folk in front of it and publishes
  screenshots (`smoke-*.png`) and the client's log, because nothing else in CI
  ever draws a folk;
* lets a world found villages as ground generates, the way exploring does;
* founds a village, saves and stops the server, starts the same world again,
  and checks the village came back and carries on.

Every folk's tick and every village event handler runs inside a guard
(`[MCA-GUARD]` in the log): a bug in a folk's thinking is written down and that one
folk misses a beat, rather than the exception stopping the server.

A watcher thread in the mod writes the server thread's stack to the log
whenever a tick takes longer than a second and a half (`MCA-STALL`), so a freeze
can be traced to the call that caused it. The reports print those stacks, the
chunk queue (`MCA-CHUNKS`), and why a village was or was not building
(`MCA-BUILD`).

The reports are published to the `village-test-latest` release: `vt-report.txt`,
`real-<scenario>.txt` and `smoke.txt` with its pictures. Each dashboard line says what one folk is doing and
why it is not doing more.

## Services for players

* **Mending at the forge.** Hold a worn tool, weapon or piece of armour and ask the smith
  ("could you mend this?", or **Mend this** on the talk screen). It mends it with its own metal
  out of the stores, a unit for every quarter of the wear, as an anvil would (three iron ingots for
  a badly worn iron sword, a diamond for each quarter of a diamond pick), and charges the market's
  price for the metal plus a fee of two coins for the work. In a town with no smith, a smelter
  mends things at its forge (by its furnace). No metal in the stores, and none in your pack: no
  mending. Not enough: it is mended as far as the metal goes. Bring your own metal and you pay
  only the fee.
* **A map of the town.** A citizen or an honoured guest can ask the storekeeper or the elder for
  a map ("could I have a map of the town?", or **Town map**). It is a real map drawn on a sheet of
  the stores' paper, centred exactly on the heart, filled in from the town as it stands, with the
  heart marked and the storekeeper's name on it. One a day; no paper in the stores, no map.
* **`/village top`.** Every village in the world on one list, the biggest first: its folk, its
  age, what it is worth (treasury and stores) and its renown.
* **A bounty on a busy night.** When the watch has had a busy night — three monsters killed by
  the town's folk, or the bell rung — the board posts a bounty (you are told in chat, and the
  Village Board shows it): every hostile mob you kill inside the town before dawn earns a coin
  out of the treasury (two for a creeper, a witch, an enderman or an illager), up to twelve coins
  a night, and the town thanks you for it. The morning's news says who earned it.
* **Lost and Found.** Something you drop in the streets (or that falls when you die there) and
  leave lying two minutes is swept up by the street sweeper (or a courier between runs) and put
  in a chest named **Lost and Found** by the storehouse — a chest out of the stores, or eight of
  their planks knocked together, set down the first time it is needed. It is kept for you, and
  only you, for three days: right-click the chest, or ask the storekeeper "has anything of mine
  turned up?" (**Lost & found**), and it is handed back. Nobody else can take it out. What
  nobody comes for goes into the stores. A town with no storehouse, or with no chest or planks
  to spare, leaves your things where they lie, as before.
* **Milestones.** A town of twenty-five, fifty and a hundred folk, each new age, and the first
  diamond in its stores are written into the chronicle as milestones, and the town celebrates on
  the square: three rockets made of the stores' paper and gunpowder (sparks, if it has none),
  the folk cheering, and a message to every player within 128 blocks.

## Town life

* **The town crier.** At the noon bell (or at noon by the clock, in a town that rang no bell that
  day) one folk walks to the square and reads out the day's news, a line every few seconds, in
  bubbles over its head: what happened yesterday and this morning (who was born, who died, what
  went up), the elder's order, when market day is and what the town is short of. The crier is the
  elder's pick, a sociable one by preference, never the elder itself or the bell-ringer; its dinner
  waits till it is done. Stand on the square at noon to hear it.
* **The gazette.** A town with a meeting hall keeps its paper there: a written book open on a
  lectern beside the elder's chair, written up afresh every morning with yesterday's births,
  deaths and new buildings, the elder's order and the market's prices (what the stores sell their
  goods for, and what the town is buying). Right-click the lectern to read it. The lectern comes
  out of the stores (or is made of three books and eight planks), and the book is a book from the
  stores or one made of three paper and a leather: no book, no gazette, so put a book or the
  makings of one in the stores if your town has none. A book you put on that lectern yourself is
  left alone.
* **The welcome sign.** Where the main road leaves town (the avenue the most roads go out along:
  to its colonies and its mother town, else toward its nearest neighbour, else away from its
  fields), a signpost stands at the town's edge beside the road, facing whoever is coming in:
  "Welcome to", the town's name, its population and its age, with when it was founded on the back.
  It is written up every day. The post and the sign are a fence and a sign from the stores, or two
  planks each; as the town grows its edge moves out, and the sign is taken down (back into the
  stores) and put up again at the new edge.
* **Sitting down.** Most days, a folk on its break looks round for somewhere to sit near it — a
  bench, a step, a chair (any stair the right way up, on solid ground, with room over it) — and
  sits down on it, chatting with whoever is by, until its break is over and it goes back to work.
  At a gathering, a folk whose place in the crowd has a seat a step or two away sits on it and
  stands when the gathering is over. Put a few stairs out as benches on the square, by the board or
  outside the hall, and you will see them used.
* **A wave and a hello.** A folk who knows you (has talked with you, not just heard of you) waves
  and says hello by name the first time each day you come within a few blocks. A child who likes
  you tags along after you for half a minute or so, chattering, then runs back to its own day.
* **A light after dark.** A grown folk out of doors after dark holds a lantern or a torch from its
  own pack in its free hand (a lantern if it has one), and puts it back in its pack when it goes
  indoors, goes to bed, has a fight on its hands, or the day breaks. Nothing is lit or made: a folk
  with no light in its pack walks in the dark, and a guard with a shield keeps its hand for that.

Tested by `TownLifeGameTests` (tl01 to tl06).

## Safety and food from every trade

* **Fishers fish where a line can go in.** A fisher's water is open water on top (not a lake under
  ice, not water under a ledge) that the town can walk to. A fisher whose water gives it nothing, or
  that it cannot cast into or reach, goes looking for other water as a hunter looks for new grounds
  ("Not a bite all day. The fish are somewhere else."). With no such water anywhere within reach of
  the town it gives the trade up, the books say so ("gave up fishing: no water fit to fish within
  reach of the town"), and the village wants no fisher for the next three days.
* **The catch goes home.** A fisher's fish, a hunter's meat and a rancher's mutton or beef are not
  kept in the pack as the hand's own rations (it used to keep eight of every kind, and eat them):
  they are banked with the rest of the day's work, and the books count them, "fish", "from the hunt"
  and "from the pen". A fish banked by somebody who has since given the water up still counts as fish.
* **The pen feeds the town.** A rancher keeps four of each kind in its pen, a pair to breed and a pair
  besides; once a kind is past four it culls one at a time for the larder (the meat, the hide, the
  wool go home with it). It never takes a kind below four, never one with a name or on a lead, and
  leaves a pair in love to it.
* **No falls.** Folk plan their walks with no drop over three blocks wherever there is such a way,
  and the five-block drop they take unhurt only where there is not; a hunter or a guard after
  something plans no drop that would hurt either (it used to follow its quarry down any cliff its
  health would stand). A builder at its building plans no drop over three. At the edge of a drop
  deeper than it should take, a folk stops as a player crouching at an edge does, whether it was
  walking or shoved by the crowd, and a blow taken at a ledge is braced against rather than thrown
  over it. A drop into water or down a ladder is no fall.
* **No lava.** A miner that uncovers lava seals it with a block from its pack, cobblestone first
  and never anything that burns or falls; with nothing to seal it with it stops the run and goes
  home rather than dig on beside it, and lava met on a step through open cave is sealed the same
  way. A folk digging itself free never breaks into a wall with lava behind it. A folk on fire with
  water within four blocks runs into it.
* **The stores read steady.** The morning's count of the stores finds the storehouse even in a
  chunk just back from the disk, and counts the goods waiting in a storehouse whose cube has come
  apart while it is laid again. If the morning's count still reads less than a quarter of the last
  good one while the storehouse stands, the hungry-village check and the leader's books use the last
  good count for the day and the town's books say why; a second such morning is believed.
* Tested by `SafetyGameTests` (sf01 to sf06).

## Families, pets and gardens

* **A pet** — a household with children takes in a stray cat or a wolf from the wild near town,
  the way you would: one of the parents takes raw cod or salmon (for a cat) or bones (for a wolf)
  out of the house's chest, else the stores, and offers them one at a time till the animal takes
  to them. A child names it, and the town's history says so. By day it trots after the
  household's children; at night, or while they are at school, it goes home and sits. One pet a
  household. Put bones or fish in a family's chest, with a wolf or a cat about, to give them the idea.
* **The children's games** — of an afternoon, after school and before supper, the children
  gather in the park (or the square, with no park) and play: tag one afternoon, whoever is it
  chasing the rest about, and hide-and-seek the next, the seeker counting to ten out loud at the
  den while the others crouch out of sight by a wall.
* **Supper at home** — at supper a family goes home and eats together round its own table, out
  of the house's chest first, rather than wherever each of them happens to be. A child comes home
  for it; a parent comes in when its day's work is done. Anybody who cannot get home in time eats
  where it is, as before, so nobody goes hungry for it. Keep a family's chest stocked and you will
  find them at table.
* **A story at bedtime** — most evenings, after supper, an old folk of the family (a grandparent
  from another house, if one is free) or else a parent sits down with the children at home and
  tells them a story out of the town's chronicle: a wedding, a death, a birth, a building opened,
  by the names of the folk it happened to and the day it was. The children go to bed after it.
* **A garden** — a household with thirty coins or so put by buys a few flowers and a sapling from
  the stores and plants a little bed of flowers in front of its house and the sapling off to one
  side, clear of the walls. Once a house.
* **Remembrance** — on the anniversary of a death (the town's year of four weeks) the one closest
  to the dead — its partner, else a child, else a parent — takes a flower from the stores out to the
  grave, lays it in front of the stone and says a few words. A couple marks its wedding
  anniversary too: a word to each other, a heart or two, and the day the happier for it.

A folk's card has a *Household* line: the family's pet and what it is doing, whether the house has
had its garden, and when the next wedding anniversary falls.

## Fuel, fire and weather

* **Coal when the age wants it.** While the stores are short of the coal the Stone Age asks for, two
  miners in three take their mines up to the coal seam (Y96, or twelve blocks under the ground if that is
  shallower; higher up in the mountains' own band) instead of down at the iron, and stay there until the
  stores hold half as much again as the age wants, so the mines do not go up and down their stairs with
  every swing of the stores; then they go back down to the iron. The third keeps on at the iron. The
  smelters burn the logs the builders can spare into charcoal first and put no coal on the fire, and the
  stores' torches are made of charcoal while coal is short. `/village larder` shows the fuel.
* **The woods kept growing.** A woodcutter puts a sapling on every stump it makes. Out of saplings, it
  knocks down the crown of a tree it has felled and takes up what falls (saplings, sticks, the odd apple)
  rather than wait for the leaves to drop. With saplings to spare (it keeps four for the stumps), it
  plants the open ground of its wood, two blocks clear of any trunk or sapling, until the wood holds a
  tree or a sapling to every twenty blocks of ground; never in the town, on the farmland or on a building
  site. While the town is short of timber it feeds its saplings bone meal from the stores (or the watch's
  bones, crushed). Leave saplings or bone meal in the stores and the woods grow back the faster.
* **Lightning rods.** Once the stores hold copper ingots, a hand on the town's works puts a lightning rod
  on the highest point of the roof of the meeting hall, the bell tower, the chapel and the leader's hall,
  one each, made of three of the stores' copper ingots (or a rod you left in the stores). The game sends a
  storm's lightning to a rod near where it strikes, so the town's timber is spared. Take one down and
  another goes up when there is copper for it.
* **The fire brigade.** Fire on or next to the town's own blocks (from lightning, lava, a campfire, or
  anything else) is seen within a couple of seconds, and the nearest grown folk run to put it out, woken
  if need be: with a bucket of water from the stores; else an empty bucket from the stores, or one made of
  three of the stores' iron, filled at the nearest water; else with their fists, a flame at a time, as a
  player can. One hand to a small fire, up to three to a big one. A fire laid on netherrack (a hearth) is
  left to burn. The news and the town's books (`fires` and `fire_log` on the analytics page) note every
  fire: where, how it started and who put it out, or that it burnt itself out out of reach.
* **Snow off the streets.** In a town where snow falls (a snowy biome, or high in the mountains), the
  street sweeper (or a courier between runs) shovels the snow off the town's streets, worn paths and
  square once there is nothing lying about to sweep, and carries the snowballs into the storehouse with
  the rest of its sack. It uses a shovel from the stores or makes a wooden one of two of their planks;
  without one the snow goes but gives nothing. Gardens and lots are left alone. The Stores page and
  `/village sweeper` show the snow cleared today.
* **In out of the storm.** During a thunderstorm everybody but the watch drops its work and goes indoors,
  home or into the nearest of the town's buildings, whichever is nearer, and waits there until the storm
  has passed; the town's works wait too. Farmers do not stand in open fields under the lightning. A miner
  down its mine keeps working (the rock is its roof), a fire still gets its brigade, and folk off work
  with a bed of their own go home as they always do in the wet.

The game tests `WeatherFuelGameTests` (wf01 to wf06) check each of these: the miners up to the coal and
back, the charcoal and the charcoal torches; a sapling from the crowns on every felled stump, the open
ground planted and the saplings fed; a rod a building, of three copper ingots; a fire put out with a
bucket made of the stores' iron and noted in the books; the snow shovelled off the square into the
storehouse and not off a garden; and a farmer in out of the storm while the watch stays out.

## Seasons and festivals

A town keeps its own year of four weeks, counted from its Founding Day, and each week of it is a season:
**spring** (the first seven days, from Founding Day), then **summer**, **autumn** and **winter**. Two towns
founded a fortnight apart are a season apart.

* **The seasons and the fields.** A farmer's tended field grows quicker in spring and summer and slower in
  autumn and winter, but never stops: with the fields at twice the wild's pace (the default), a tended crop
  grows at 2.3 times the wild in spring, 2.15 in summer, 1.8 in autumn and 1.55 in winter. The board says
  the season and the day of it ("Summer, day 4 of 7 — the town's second year"), the town's books show it on
  the News page with the fields' pace in every season, the crier reads it out at noon, the gazette prints
  it under its masthead, and folk mention it now and then (the blossom, the long days, the leaves turning,
  the cold). The first day of each season goes into the chronicle. `/village season` says where the town
  is in its year; an operator can turn a town's calendar to any day of it with `/village season set <1-28>`.
* **The May dance.** On the first rest day of spring a hand puts a maypole up on the square out of the
  stores: five fence posts (on a foot of logs if the stores are short of posts, or five logs) with a block
  of wool of every colour the stores have at its head, up to five. At dusk the town gathers in a ring round it, the elder says a word, and they dance: the whole
  ring moves round the pole a place at a time, to a tune. Everybody remembers it, and the town is the
  happier for a few days. The next morning the pole comes down and every post and every block of wool goes
  back into the stores. With no wool (or nothing for the pole) the board says why there is no maypole.
* **The midsummer bonfire.** On midsummer's day (the fourth of summer), at dusk, a hand builds a fire on
  the square: a campfire made as you would make one (three of the stores' logs, a lump of coal or charcoal
  and three sticks, sawn from planks if need be), or five in a cross if the stores run to it. The town
  gathers round and sings. At midnight it is put out, the ground is clear again, and what a campfire leaves
  (two charcoal each) goes into the stores. Rain puts it off a day.
* **The town fair.** Late in summer (its sixth day) the town holds a fair with four classes: the best
  bread, the best wool, the biggest fish and the best honey. On fair day you can enter too: hold your
  entry and right-click the board (or use `/village fair enter` near it). The fair keeps it, and it comes
  back to you after the judging. At dusk the town gathers before the board and the hands whose trade it is
  bring theirs, the most skilled first: the rancher's or the tailor's wool, the fisher's catch and the
  beekeeper's honey out of their own packs or, if they have none, the best of their trade's work in the
  stores; and the cook's or a farmer's pick of the town's baking from the stores (the loaves folk carry are
  their rations, not their baking). The elder judges by rules anyone can check: the biggest batch of loaves
  (up to a stack), the biggest fleece of one colour (a dyed one scores a little more), the heaviest fish (a
  salmon weighs five to ten pounds, a cod three to seven), honey by the bottle and the comb; a tie goes to
  whoever entered first, so a guest who entered in the day wins a tie with the town. Each winner gets a blue ribbon (a sheet of the
  stores' paper, named "Ribbon: best bread, Year 3") and four coins from the treasury. Everything entered
  goes back where it came from, the results go into the chronicle and the books, and they stay on the
  board for a few days. If you are away when the judging is done, your entry and any prize are handed to
  you the next time you are in the world. `/village fair` shows the next fair, its entries and the last
  ribbons.
* **The harvest festival.** All year the town counts its harvest as it is brought in: the meals from the
  fields (farmer by farmer), the water, the hunt and the pens. On the last day of autumn a hand lays two
  long tables on the square (the stores' wooden slabs, or planks sawn into them), and at dusk the town
  feasts at them out of its stores. The elder reads out the year's harvest and gives the farmer who brought
  in the most a prize of ten coins from the treasury. It all goes into the chronicle, and the tables are
  cleared into the stores the next morning.
* **Midwinter.** On midwinter's day (the fourth of winter) a hand sets lanterns out along the main avenue
  (the one the board looks down), both sides of the road, twelve at most: the stores' lanterns, or torches
  until the town makes lanterns. Friends and families give each other small presents: each grown folk with
  a coin or two picks its partner, its child or parent, or its dearest friend, goes to the shop in its own
  time and buys something they would like (a flower, a cookie, a candle, a book) out of its own purse, then
  takes it round and hands it over. With no shop open it buys over the stores' counter instead. The present
  becomes the friend's own keepsake. The next morning the lanterns go back into the stores, and midwinter
  goes into the chronicle.
* **Winter in town.** In a town where snow falls, on a winter afternoon one of the children goes off to
  build a snowman by the playground (the park, or the square), two in a winter at most. It gathers the
  lying snow with a shovel borrowed from the stores (by hand, snow gives nothing), makes up the rest with
  the snowballs the sweeper has banked, and packs four snowballs to a block. If the stores have a carved
  pumpkin it becomes the snowman's face, and as in the game, two blocks of snow under a carved pumpkin come
  alive as a snow golem. A snowman made only of snow stands until the thaw on the first day of spring.
  Everywhere, folk say it is cold in winter: snow talk where it snows, and talk of frost where it does not.

A festival is a gathering like the others: it takes the evening before the weekly feast, gives way to a
wedding, a vigil or a celebration (and is kept the next evening instead), and needs at least five grown
folk for a hand to put things up. Everything it puts up is recorded with the world, so a restart never
strands a maypole on the square or a lantern outside the stores.
`/village festival <maypole|bonfire|fair|harvest|midwinter|snowman> [now]` (operators) shows where a
festival stands, or sets it up now and calls the town to it.

The game tests `SeasonsGameTests` (sf21 to sf28) check each of these: the seasons, the fields' pace in each
and where they are shown; the maypole up and back into the stores; the ring going round the pole; five
campfires built as a player builds them, put out at midnight with their charcoal in the stores; the fair's
judging, ribbons, purses and every entry returned; the harvest counted and its prize paid; midwinter's
lights and a present bought and given; and a snowman built by a child, woken by a pumpkin, and gone with
the thaw.

## Visitors and the player

Towns are visited now, by strangers from far away and by friends from the next town, and the game keeps
note of your own milestones among them.

* **Visitors are nobody's.** A bard, a tourist or a merchant comes in on foot from the edge of the world
  near the town, a folk like any other to look at and talk to, but of no village: never on a town's roll or
  in its headcount, never given a bed, a trade, a wage or a vote, never called to the town's work. Its name
  says what it is ("Wren the bard", "Ash the merchant", "Rowan (visiting)"), and its card says where it came
  from, when it leaves and what it carries. Ask it about itself, or how it is; anything about the town it
  leaves to the people who live there. What a visitor brings, it brings from outside, as the game's
  wandering trader does, and it is kept small: the bard's bedroll and the price of its rooms, a tourist's purse
  of four to ten coins (and a room's price, if the town has an inn), a merchant's few lots. What it takes away (its purse, a souvenir, unsold goods) goes with it.
* **The travelling bard.** Every four to six days a town of fifteen or more with a tavern has a bard come
  in. It makes for the tavern and says who it is; nearby players hear of it. For two or three nights:
  * **of an evening** it stands by the tavern's hearth and plays, a phrase at a time with the notes rising
    over its head, and between tunes tells the news of the towns round about, real lines out of their own
    chronicles ("News from Oakford, 300 blocks east: the smithy went up, on day 12"), or one of its tales.
    With a bard in, the whole town comes to the tavern, not two evenings in five. Whoever hears it is
    happier that day and the next ("There's a bard at the tavern — what songs!") and remembers it;
  * **by day** it sees the town and busks on the square; a folk who likes the song may drop a coin of its
    own in the bard's hat, once a visit;
  * **at night** it takes a room at the inn, if the town has one with a keeper and a bed free, and pays for
    it like any traveller (three coins a night into the till); else it sleeps at the tavern on its own
    bedroll laid by the wall, rolled up again in the morning;
  * then it goes on its way, and the chronicle says so.
* **Tourists.** A town of renown draws people to see it: its renown (its great works and its museum) and
  five more for each statue to a hero. At fifteen it has tourists, one or two at a time, a few days apart
  (the more renowned, the oftener). A tourist walks the sights in turn (the museum first, the statues, the
  monument, the park, the fountain, the bell tower and the rest), stops a while before each and says what
  it thinks, has a drink or a bite at the café and buys a souvenir at the shop, at the town's prices, out of
  its own purse into the treasury (in whole coins: a visitor keeps no account and runs up no slate). With an
  inn it takes a room for the night, as any traveller does, and goes home in the morning; without one it
  goes home at dusk.
* **The merchant from afar.** On market day a town with a market has a merchant come with up to four lots
  of what its own land has not got: cocoa, glow berries, sugar cane, cactus, bamboo, coral, dyes, melon,
  honeycomb, and saplings of the woods that do not grow there. It never brings what the town's ground grows
  or what its stores already hold. **The town buys what it needs** as soon as the stall is up, out of the
  treasury (never out of what it keeps for the wages), two lots at most: cane when it is short of paper,
  cocoa for the café, saplings when the woodcutters have few, berries when the larder is low, dyes while
  houses wait on coloured rugs. **You can buy too**: ask the merchant "Trade?" for its lots in turn, and
  press **Hand over** with the coin (or an emerald). It leaves at dusk.
* **Friends from other towns.** Folk with a friend or family in another town within five hundred blocks
  (one moved there on the job market, went out with a colony, or grew up and left) now and then walk over
  to see them for the day: one from a town every three days at most, never the watch or the leader. It goes
  on foot by the road between the towns, finds its friend, is welcomed, and they eat together, a bite each
  of their own. It keeps its friend company till the afternoon, then walks home. Both remember it, think
  the warmer of each other (a friendship kept up across the miles does not fade), are happier for a day or
  two, and both chronicles take it down. Its card says where it is while it is away.
* **Gifts kept.** Give a folk something precious (worth five coins or more and not food: a diamond, an
  emerald, gold, an enchanted book, a music disc, a fine tool) and it is its own: never put in the stores,
  carried with it when it moves house. Of an evening at home it hangs the finest it has in an item frame on
  the wall by its bed, a frame it buys out of its own purse at the town's price; a finer gift later takes
  its place. Its card has a **Keeps** line, and it mentions it: "I keep the diamond you gave me by my bed."
* **The map room.** Once the hall stands, a hand at the town's works (the clerk) draws a map of the town and
  hangs it in a frame on the hall's wall: on a sheet of nine of the stores' paper (or eight and a compass
  the town can spare), filled in from the ground as it stands, in a frame the stores have or make of their
  sticks and a leather. A town of forty or more has a two-by-two of maps, each a quarter of the town at
  twice the detail. Every seven days a fresh map is drawn on fresh paper and last week's goes back to the
  stores, where you may have it.
* **Watch dogs.** The watch keeps a dog for every two guards. A guard takes bones (or raw meat) out of the
  stores, walks out to a wild wolf about the town and holds them out one at a time, as you would; what it
  does not use goes back. The dog is named, follows its guard on its rounds by day, growls and barks at a
  monster near the town and goes for it (never a creeper), and at night lies down by its guard's bed. The
  guard's card has an **Its dog** line.
* **Advancements.** A page of its own on the advancements screen, **Village Life** (the village board's
  icon), with a toast as each comes: founding a village; a town you founded or are a citizen of reaching
  twenty-five, fifty and a hundred folk, and each age from the Stone Age to the Nether; becoming a citizen;
  being made an honoured guest; your first trade with a folk; a letter from a friend in a village; and,
  hidden until won, a ribbon at a town fair. A football cup has its advancement too, hidden, for when a
  player can win one: the town's matches are played by its folk.
* **Seeing it.** The town's books, **News** page, have a **Visitors** section: the week's visitors and what
  they spent, who is in town now, friends' visits this week and who is away today, when the hall's map was
  drawn, and the watch's dogs. The chronicle takes down every coming and going. Operators can use
  `/village visitors` (who is visiting and where, the map's frames, the dogs) and `/village visitors
  <bard|tourist|merchant|friend|map|dog|gifts|evening>` to bring one about now with the town's own stores,
  purses and hands.

The game tests `VisitorsGameTests` (vp01 to vp09) check each: the bard is on nobody's roll, its news is the
other town's real line, the room hears it and is the happier, it beds down on its bedroll and rolls it up;
a tourist's coin goes from its own purse into the treasury and the books count it; the town buys what it
needs off the merchant's stall out of the treasury, and a player buys a lot; the advancements are all loaded
and granted (the founder's, the ages, citizenship); the map is drawn on the stores' paper, hung, renewed a
week on with the old map back in the stores, and grows into a two-by-two; a guard tames a wolf with one of
the stores' bones, and the dog lies down at night and goes for a zombie by day; a friend's visit is walked,
welcomed, eaten together and remembered on both sides; and a player's diamond is hung in a frame the folk
bought, mentioned, and given way to a finer gift; and, in a town with an inn, a tourist and the bard each pay for a
room there for the night.

## Health and care

* **Colds.** A folk caught out in the rain or a thunderstorm with nothing over its head for a couple of
  minutes in a day, or worked to the bone (a long stretch at its work with no break: a harvest that would
  not wait, a hard-driving leader), may catch a cold. The odds are low (worse on a poor diet), a town has to
  be settled first (three days old and six folk), and no town catches more than four in a week; a cold may
  pass to the folk it lives with, a small chance a day. A folk with a cold works at half its pace (every clock
  its trade keeps, and its building), coughs and sneezes now and then and into what it says, spends the first
  quarter-day in bed, and goes to bed early of an evening. It is over it in a day or two, sooner in the
  infirmary's beds or with the healer's care. A cold never kills anybody. The folk's card has a *Health*
  line (since when, how it caught it, where it is lying, who has seen to it), its pace line says "with a
  cold, at 50% of that", the town's books mark the ill on the Folk page ("(ill)", and how many at the foot),
  and the chronicle hears of it only when three or more are down with it at once.
* **The infirmary.** A Stone Age town of twenty or more builds one once its meeting hall stands: a timber
  ward on a stone footing with four beds down its sides, a cauldron and a brewing stand at the back between
  two barrels, and lanterns. Its beds are made up from the stores like a house's and kept for the sick:
  nobody takes one as its own bed. A folk down to three fifths of its health, out of any fight, walks there,
  lies down and mends half a heart more every eight seconds than it would on its own (twice the pace), and
  gets up nine tenths whole. A folk with a cold lies there in preference to its own bed, and its cold runs
  out twice as fast.
* **The healer.** The care of the sick is a works of the town's (the brewer first, else a hand the town can
  spare): about once a minute somebody goes round the infirmary's patients and visits the bedridden at home,
  with a honey bottle, a golden carrot, sweet berries or the brewer's healing potion out of the stores (the
  bottle goes back). With none of those in the stores it sits with them a while anyway: rest and company do a
  little good. The patient remembers who looked after it, and likes it the better. Keep honey and golden
  carrots in the stores to see colds off quickly.
* **Neighbours look after the old.** Every day somebody looks in on each very old (eighty and over) or frail
  folk: one of its family if there is one, else a friend, else a neighbour it gets on with. In its own time it
  fetches a meal from the stores (the old one's favourite if there is one, else bread or something cooked),
  carries it over, hands it to the old one and sits down with it a while. They are the fonder of each other, and
  both remember it. An old folk's card says it is looked in on.
* **The poor box.** In the chapel (the meeting hall until there is one) there is a poor box. Once a week every
  well-off folk walks there and puts a coin in out of its own purse (a wealthy one two, a generous one one more).
  On payday a household that cannot make its rent has what it is short of paid out of the box, and a poor folk
  with next to nothing to eat walks to the café (or the shop, or the stores) for a loaf the box pays for, at the
  counter's price. The box's coin is its own, never the treasury's; the books' Money page shows what is in it,
  what went in this week and from how many, and what came out for rent and bread.
* **Housewarming.** The evening a household moves into a house that is new to it, its friends and neighbours
  call at the door with a small present (a flower, a loaf or a candle out of their own packs, or bought at the
  stores out of their own purses). The household comes out to meet them, and everybody remembers the
  housewarming. (The founders, who all move in together, have nobody to call.)
* **The welcome committee.** A newcomer (taken on at the job market, taken in after a raid, a villager the
  town took in, a folk stood up in a town a day old or more) is greeted within the day by the elder, or else
  the friendliest folk in the town, and walked round: the heart of the town, the stores (where it is handed a
  welcome basket out of them: a loaf, a torch and a flower, whatever of them there is) and its new home.
  Put a few loaves, torches and flowers in the stores and every newcomer gets the lot.

`/village care` says who is ill and how, the infirmary and its beds, the day's care, the poor box, and the
day's visits, welcomes and housewarmings. Operators: `/village care cold` gives the nearest folk a cold, and
`/village care stage` sets an infirmary out where you stand, its beds full, for a look inside.

Tested by `HealthCareGameTests` (hc01 to hc07).

## Sport and play

What a town does with its day of rest besides the service, the games on the square and walking out,
and what its watch does of a working morning. Everything here is played with the town's own things:
the ball, the rods, the arrows, the prizes and the cup all come out of its stores (and the ball, the
rods and the arrows go back into them after).

* **The football pitch.** Once a town builds in stone and has twenty folk, a football pitch goes on the
  builders' list with the other amenities. It takes a long lot among the homes, the nearest the park if
  there is one: a field of grass fifteen blocks long and nine across inside its lines, a goal at each end
  (two fence posts with a fence across the top for the crossbar, and fences round the back for a net), a
  bench of stairs down each side and a lamp on a post at each corner. Then its keepers finish it out of
  the stores, a little at a time: any earth standing proud of the field is cut away, a hollow is filled
  and the builder's bare stone turfed with the stores' earth, and the lines (the touchlines, the goal
  lines and the halfway line) are set into the grass in **white wool**, or **birch planks** where the
  stores have no wool to spare (a town short of beds keeps its wool for them). Put white wool or birch
  planks in the stores and you will see the lines go down.
* **Football on the rest day.** Every rest day, in the early afternoon, two of the town's **ends** play a
  match on the pitch: every folk belongs to the end of town its home is on (the North End, the East End,
  the South End, the West End), and the two ends that have met least this season turn out up to five a
  side each, a different few from week to week (with three or more a side, one keeps goal). The watch, the
  old and the children do not play. The **ball is real**: a slime ball out of the stores, or a scrap of
  leather if there is no slime, and back in the stores at the final whistle; no ball, no match. The players
  walk out to their places, the home side kicks off, and they run at the ball and kick it toward the other
  goal (a pass from far out, a shot from close in, a long clearance from the keeper). A goal is the ball
  over the goal line between the posts and under the bar, and the side that let it in kicks off again; a
  ball over a line anywhere else is put back on the field. Two and a half minutes later it is over, and the
  result, with who scored, goes into the chronicle, onto the board and into the league. Folk with nobody to
  walk out with that afternoon, the children and the players' own families stand along the sides and on the
  benches and cheer their end on. A thunderstorm, or the bell, stops it.
* **The league and the cup.** The ends' results make a **league table**, three points for a win and one
  for a draw, kept with the world. A season is the town's own year (twenty-eight days from its founding,
  as Founding Day counts them): when it turns, whoever is top of the table are the champions, written into
  the chronicle, and they get **the cup**, a gold ingot out of the stores (or nine nuggets) named "The
  *Town* Cup", with every year's winners written on it, in an item frame (the stores', or made of eight
  sticks and a leather) on the back wall of the leader's hall or the meeting hall. The cup is made once;
  each year after, the new champions' name goes on the same cup. No hall, or no gold in the stores, and the
  champions wait for their cup until there is.
* **Friendlies between towns.** Two towns on good terms (friendly or better) and within a caravan's
  reach of each other now and then play a friendly, on the rest day of the one with the pitch. The
  visitors' side, up to five, sets out after the morning assembly and really walks there, by the road a
  caravan takes, to the side of the host's pitch; the host turns out as many as came. The result goes into
  both towns' chronicles and books, and a good afternoon's football warms the two towns to each other a
  little (a thrashing rather less). A walker that gets stuck on the road is set down at its next step, as
  a caravan's carrier is; if the side has not got there by early afternoon, or nobody in the host town comes
  out to play, the match is called off (both chronicles say so) and the side walks home.
* **The fishing contest.** Folk have no seasons to go by, so the rest days take turns: the first of every
  three has the fishing contest in the late morning. The town's fishers and anybody whose pastime is
  fishing (up to six) fish side by side at the town's water for an hour, each with its own rod or one
  borrowed from the stores. The catch is real, mostly fish and now and then an old bone or a bit of
  string, and it all goes into the stores. The most fish wins **a purse of five coins** from the treasury
  (if it has them).
* **The children's sports day.** The second rest day of the three, the children (up to eight) line up in
  lanes at one end of the park, or of the square with no park, and race to the other end, each at its own
  pace on the day; their parents stand at the finish and cheer them home. The winner gets **a cookie** out
  of the stores, or an apple. Put cookies in the stores for the prize.
* **The archery range.** An Iron Age town with two guards or more builds the watch a range on a lot just
  outside a corner of the wall: three butts of hay at the back with a wall of boards behind them, and a
  line of stone slabs eight blocks off. Its keepers make each butt's top bale a **target** with four of the
  stores' redstone (with no redstone to spare, the guards shoot at the hay). On a working morning a guard
  not needed elsewhere goes down once a day (never more than half the watch at once), takes six real arrows
  out of the stores and its bow (or one borrowed from the stores), and shoots them at its butt, holding its
  fire while anybody is in the way: two points in the target, one in the bale under it. Then it pulls its
  arrows and they go back into the stores (a lost one is lost), and the morning makes it a little better at
  its trade. On the rest day the watch holds an **archery contest**, six arrows each; the most points wins.

**Where to see it.** The board's right-hand column says what is on now ("Now: football on the pitch: the
North End 2, the South End 1 — come and watch!"), a side away or on its way, the last result, the top of
the league and who holds the cup. In the town's books the **News** page has a *Sport and play* section: how
the pitch and the range are coming on, the next rest day's programme, the league table, the cup and its
winners, the latest results and friendlies, and the latest winners of the fishing contest, the children's
race and the archery contest. A folk's card says when it is playing, watching, fishing, racing or at the
butts. Operators: `/village sport` says all of it; `/village sport pitch now`, `match now`, `range now`,
`fishing now`, `race now`, `archery now` and `cup now` make each happen at once.

Tested by `SportGameTests` (sp01 to sp07): the pitch and its keepers, a match with a real ball (the
referee's eye for a goal, the kick-off, the result and the ball back in the stores), the league and the cup,
a friendly between two towns (and one called off), the fishing contest, the children's race and the archery
range.

## Culture and identity

Every town ends up with a look and a voice of its own: a banner, a motto, its own customs, its own
plays, its own band, pictures on its walls and plaques where things happened. All of it is in the
town's books on the new **Culture** page (the last tab; `/village stats 21`), and `/village culture`
says it all in chat.

* **The town's banner.** When a town is founded it draws its banner, once and for good. The base colour
  comes from its land: blue for the coast, pale blue for a river, green for the forest, white for the
  snowfields, grey for the mountains, yellow for the desert, and so on. On it go two or three patterns.
  One is for the land itself where it has one (the sea's edge, a river's bend, a snowy peak, the desert
  sun). One is for the town's name, in the town's colours: a band of water for a ford or a brook, a pale
  for an oak or an ash, stonework for a Stonebury. The last is for the nature of whoever led it then: a
  hard worker's border, a cheerful leader's chief, a curious one's lozenge. The tailor makes it as you
  would, a banner of the base colour and a dye for each pattern at the loom, all out of the stores. A
  hand at the town's works then hangs it, one at a time: either side of the hall's door, on a post of
  each gate, on the market's front posts and over the theatre's stage. The Culture page draws it large,
  says it in words, lists where it hangs and says what it is waiting for ("short of 2 red dye").
* **A copy for yourself.** Once there is a shop, a sign goes up beside its door: "Our banner". If you are
  a citizen of the town, right-click it and the tailor makes you a copy there and then, out of the
  stores, for the price of what goes into it and a little for the work. The coin goes into the
  treasury. Nobody else may fly it.
* **The motto.** It is chosen with the banner, out of the land and what the town cares about most (its
  leader's heart, or most of its folk's): "By the river, for each other", "Out of the rock, the gate
  holds". Once the hall stands, a hand carves it on a sign over the hall's door. The crier cries it on
  feast days: the weekly feast, Founding Day, a celebration, a wedding, the day of rest. The board's foot
  shows the banner and the motto.
* **Customs.** A town's great days become yearly customs, kept on their anniversary in the town's year
  of four weeks. Its founding is always the first. The others are the night the raiders were beaten off
  at a gate, a great storm (most of a minute of thunder over the town) and the first diamond out of its
  mines. A town keeps three customs at most, each in its own way:
  * *a minute's silence* at the dusk bell, for those who fell in a raid. The bell tolls, and every grown
    folk stops where it is, faces the bell with its head bowed and stays quiet for a minute, until the
    bell sounds again;
  * *lanterns lit on the square* for the founding and for a great storm. Before dusk a hand sets lanterns
    round the square out of the stores (torches if there are none) and takes them in the next morning;
  * *a toast at the tavern* for a raid beaten off with nobody lost, or for the first diamond. That evening
    the town goes to the tavern, and once they are in, the eldest raises a cup ("To Ember, and the first
    diamond!"). They all drink to it, a bottle of the café's each out of the stores (the bottles go back),
    or water when there are none.

  The crier cries a custom the day before and on the day itself. The board lists them all with the next
  day each one falls.
* **The theatre.** An Iron Age town of twenty with at least two *players* (folk who love reading, music
  or whittling) builds a theatre among its amenities. It is an open-air stage: a raised wooden platform,
  a back wall with the town's banner either side, steps up at each end and two rows of benches. On the
  evening of the day of rest, after supper and unless it rains, two or three players put on a short play
  made from a story in the town's own chronicle. They pick the weightiest one they have not played yet:
  the night at the north gate, the wedding of Ada and Bert, the first diamond, the great storm, how the
  town began. They speak it in character, a line at a time over their heads. The rest of the town takes
  the benches (latecomers stand behind) and reacts as a crowd does, and at the end the players bow and
  the town cheers. Come and watch: you will see every line. The play goes into the chronicle and into
  everyone's memory. The board says when a play is on tonight, and the Culture page lists the plays so
  far.
* **The band and the choir.** Up to four musicians (folk who love music) form the town's band. On the
  evening of the day of rest they play at the tavern (after the play, if there is one), and at weddings
  and feasts they stand together beside whoever is speaking. They play through the procession and the
  feasting and stay quiet for the speeches. Each one plays a note block. That is a real thing: its own if
  it has one, otherwise one lent out of the stores for the evening and put back after. **No note blocks
  in the stores, no band** (the page says so), and the tavern's own tune carries on as before. The
  parts are the tune, the bass, the harmony and a bell on the beat: a jig early in the evening, a slow
  air later, a march for a wedding. In a town with a chapel, at the morning service on the day of rest,
  the choir (the musicians, then sociable cheerful folk, six at most) stands before the altar facing the
  pews. They sing the town's own hymn a line at a time, in chords; one line of it is the motto.
* **Paintings.** A whittler or a gardener paints instead of its usual pastime one evening in three. It
  takes a painting's makings, eight sticks and a wool, from its own pack or else the stores (no makings,
  no painting). It sets up by its door with a brush in hand and the wool's colour flecking off it, and
  paints until it is done. It names the picture ("The Well at Dusk", "Portrait of Fern", "The Sea at
  Ashford") and signs it, and the town buys it from the treasury for the stores. From there it is sold
  at the shop like any painting, and a comfortable folk buys one for its own wall. The town's works hang
  two in each public room (the hall, the museum, the tavern) wherever one fits on a wall. The first is
  hung no bigger than leaves room on the walls for the second.
* **Plaques.** A hand at the town's works puts up oak signs where something happened, out of the stores
  (a sign, and a post for one on open ground):
  * "Here Ashford was founded" by the heart of the town, once it is a day old;
  * "The first house of Ashford" on that house's front wall beside the door;
  * "Here fell Ada, against raiders" where a guard or anyone else fell while the raiders came (three at most);
  * "Record harvest, 46 coins' worth" at the edge of the field of the farmer who brought in the most, once
    the town's fields bring in more in a day than ever before. It is rewritten when the record is beaten.

  A plaque never goes on a street, a worn path or in a doorway. On open ground it goes on the nearest
  free ground to its place. The Culture page lists each one with its words and where it stands.
* **Seeing it.** A folk's card has a **Culture** line, for example "plays the bass in the town's band,
  and in the choir; played Bert in "The Wedding of Ada and Bert" on day 33", or "has painted 2 pictures,
  the last "Night over Ashford"". The board's foot shows the banner, the motto, the customs and
  tonight's play.

Commands: `/village culture` (all of the above for the nearest town), `/village culture now` (ops: every
piece the stores run to put up now), `/village culture play` (ops: tonight's play begun now) and
`/village culture stage` (ops: a theatre set out where you stand, the banners up, players on the stage
and an audience on the benches, for pictures).

Tested by `CultureGameTests` (ci01 to ci08): the banner drawn and kept, woven from the stores and hung
either side of the hall's door, a copy sold to a citizen and refused to a stranger; the motto carved and
cried on a feast day but not on a plain one; three customs taken up and the fourth not, lanterns set out
and taken in, the silence, and the toast with a drink each; the theatre wanted, a play cast from the
players, watched from the benches, every line said and the next play a different story; the band only
with the stores' note blocks, playing together and putting them back; the choir at the service; a
picture painted from the stores' sticks and wool, bought and hung in the tavern; and the four plaques,
none of them on a street.

## The town's look

A town of any size starts to look like one: trees along its avenues, benches on its corners, flowers
under the better windows, a notice board by the square, and out by the fields a windmill, an orchard and
allotments; a bakery among the shops and an inn by the road. All of it comes out of the stores and is put
in by a hand at the town's works, a little at a time, like the streets and the lamps.

* **Tree-lined avenues.** On the verge just off each avenue, midway between two lamp posts (every six
  blocks), the town plants a tree from the stores' saplings: the woodcutter for choice, as far out as the
  town has come. Never on the road, where a street crosses, within two blocks of a door, against a lamp
  post or a street sign, hard against a wall or on the farmland. Once a tree is grown, the leaves it hangs
  over the road lower than three blocks are trimmed off (what they drop goes to the stores); the tree is
  never felled for it. Leave saplings in the stores and the avenues fill up.
* **Benches** go on the street corners, the first of them where the avenues leave the square: a wooden
  stair the right way up, its back to the lot, made of six of the stores' planks (four stairs; the other
  three go back into the stores for the builders' roofs). Folk on their break sit on them. The benches,
  the window boxes' ledges and the notice board are only ever made of planks the builders can spare: none
  in the Wood Age, when every plank is put by for the age, and never the builders' forty-eight.
* **Window boxes.** A house whose household is well off (one of them worth ninety coins or more) gets a
  box under two of its windows (the front ones where the lamp posts by the door leave room, else the
  sides): a trapdoor fixed under the sill for a ledge, and a pot of flowers on it. The trapdoor, the pot
  (or three bricks for one) and the flower all come out of the stores.
* **The notice board** stands by the square where the avenue nearest the village board comes out: two
  signs side by side, the first with the town's name, the day and how many live there, the second with
  the latest from the town's chronicle (the gazette's news), pinned up afresh every day.
* **The allotments** (a town of eighteen, once two households have no garden of their own): a fenced
  square of four small plots by the fields, with a channel of water down the back. Each household with no
  garden (see *Families, pets and gardens*) is let a plot; two evenings in three one of it walks over after
  supper, turns the earth with a hoe (its own, or the stores' borrowed and put back the worse for wear),
  sows carrots, potatoes, beetroot or wheat out of its own chest or the stores (never the farmers' last
  twelve), pulls up whatever is ripe and puts one back in the ground, and carries the rest home to the
  household's chest. A household that plants its garden after all gives its plot up for the next. The
  channel is filled from a water bucket in the stores when it runs dry.
* **The orchard** (a farming town of fourteen): a fenced square by the fields where the farmers plant
  four oaks from the stores' oak saplings (the oak is the tree that bears apples), with bone meal now and
  then if the stores have eight or more. In the last week of the town's twenty-eight-day year they pick
  it: an oak only gives up its apples as its leaves come down (one leaf in two hundred), so each tree's
  crown is taken down leaf by leaf, everything that falls (apples, saplings, sticks) and the trunk go into
  the stores, and an oak goes straight back into the same ground. Apples lying in the grass are picked up
  any day. The apples are for the café's cider and anybody's lunch.
* **The windmill** (Stone Age, a farming town of sixteen): a stone and timber tower by the fields with
  four sails on its front, the landmark of the farmland. The builders put up the axle and the arms; the
  cloth (twelve pieces of the stores' wool) is hung after. Its two chests are where the town's grain is
  kept: a farmer coming past with sixteen wheat or more leaves it there, and once the stores hold more
  than sixty-four wheat a hand carries the rest up to it a load at a time.
* **The bakery** (Stone Age, a farming town of twenty): two ovens, the baker's table and a counter. A
  hand at the works (the café's cook for choice) bakes bread, cookies (wheat and cocoa beans), pumpkin pie
  (a pumpkin, an egg and sugar pressed from cane) and cake (three buckets of milk, the buckets given back,
  two sugar, an egg and three wheat), each by the game's own recipe out of the stores: whatever the stores are
  shortest of against what a town its size likes to have (a loaf a head, a few dozen cookies, a pie or
  two, a cake, another on a feast night), and never the farmers' last twelve wheat. It all goes to the
  stores, where the café sets it out and the feasts draw on it. With a windmill, the baker walks over for
  a sack of wheat when the stores run low and bakes twice the batch at a time. The idle hands who bake the
  odd batch of bread leave it to the bakery while it is at it.
* **The inn** (the Iron Age, or a town of thirty, once it has a cook or a shopkeeper to keep it): a long
  timber house by the road in, on one of the lots that face the square beside an avenue's gate if one is
  free (else the nearest lot), two rooms of two beds at the back and a common room with a bar. Its keeper
  is the café's cook, else the shopkeeper, and its sign goes up by the door. A caravan's driver, an envoy or a household on the
  road to a new home who is in a town with an inn after dusk takes a room out of its own purse (three
  coins, into the town's treasury), sleeps there and goes on in the morning. **You can take a room
  too:** right-click the inn's sign, or the innkeeper with village coins in your hand. For three coins
  (less if you have haggled with the town today) you are given one of the beds until the next morning;
  nobody else may sleep in it, and without a room you may sleep in none of them. A night at the inn does
  not move your respawn point. The town's own folk never take an inn bed for their own, and its beds are
  not counted as homes.

**Where to see it.** The village board has a line for the bakery's day, the inn's rooms, the windmill
and the trees along the avenues. A folk's card has an *About town* line: its household's allotment and
what is growing on it, its turn at the bakery, the inn it keeps or is lodging at. The chronicle (and so
the notice board and the gazette) records the first tree, the notice board, the windmill's sails, the
bakery's first bake, the inn opening and its first guests, and the orchard's apples. `/village townlook`
says all of it in chat; operators have `/village townlook showcase` (all of it set out where you stand,
to be looked at) and `/village townlook now` (a few rounds of the town's works done at once).

The game tests `TownLookGameTests` (tl01 to tl07) check each of these: the avenues' trees out of the stores
and never by a door or a lamp post, and their low leaves trimmed off the road; the benches, a well-off
house's window boxes and the notice board; a household's plot turned, sown and harvested, the vegetables
home in its chest; the orchard planted, grown, picked and planted again; the windmill's place, its sails
and its grain; the bakery's four bakes by their recipes and its milled batches; and a player's room and a
traveller's at the inn.

## Town life and governance

* **The post office and letters.** From the Stone Age a town of ten wants a post office (built once its
  more pressing buildings are up): a small timber office facing the square, a counter across it, the clerk's lectern behind and a row of pigeonholes
  (barrels) along the back wall, its name on the sign by the door. One of the storehouse's couriers is its
  postman. Folk with family or close friends living in another town now (a child who took a job there, a
  friend who went off with a colony, a sister who married away) write to them of an afternoon on a sheet of
  the stores' paper and walk it to the counter. A letter goes no faster than somebody walking: it waits in
  the pigeonholes until a caravan or an envoy sets out that way, travels in the carrier's bag, and comes out
  at the other town's counter, whose postman walks it round. Read, it is remembered, cheers the reader up
  and warms it to the writer, and about half the time it is answered. **To write yourself**, take a book
  and quill, put the name at the top ("Dear Ash,") or title a written book with it, and right-click the post
  office's sign (or a pigeonhole, or the lectern, or hand it to the postman): it is posted to that folk,
  wherever they live. They write back in your book, and the answer comes back to the counter you posted it
  at: the postman brings it to you if you are in town, or right-click the sign with an empty hand. A letter
  to somebody in the same town is answered within the day; one to another town takes as long as the road.
* **Petitions.** Folk look about them: no bench anywhere near the house, the street at the door dark, no
  well in their quarter, a plot out past the town with no road to it. One with a grievance gets up a
  petition, and it goes on the board ("Petitions: a bench by No. 3 Elm Row (Ash; 2 of 4 names)"). Over the
  next days the neighbours, the quarter, the hands who work that way and the raiser's friends walk to the
  board of an evening and sign it (never its rivals). With a quarter of the grown folk's names the council
  puts it on the town's works at its weekly sitting (or the elder does, if the council has not sat in two
  days), and a hand does it with the stores' materials: a stair for a bench (or two planks), a fence post and
  a torch for a light, eight cobblestone and a bucket of water for a well, a worn path for a road. The board
  lists what the petitions have won. A petition without its names in a week lapses.
* **The town meeting.** Once a week, the evening before the day of rest (or the day of rest itself, if that
  evening is taken), the bell calls the town to the meeting hall's door, or to the board before there is a
  hall. The elder gives the week: the treasury against last week, what went up, who was born and who died,
  what the petitions won, how the statue fund stands and what is to be built next. Then two or three folk
  have their say (a grumble about what the town is short of, a question about the next building, a word for
  a petition still wanting names) and the elder answers each. A summary goes into the chronicle, and so into
  the next morning's gazette, and everybody who came is a little the happier for knowing how the town stands.
  The board says when the next one is.
* **Quarter wardens.** Each quarter with homes in it (the market quarter, the craft quarter, the homes
  quarter) has a warden: the folk living there the rest of the town thinks most of, never the elder nor the
  watch. Of an evening it walks the quarter's street corners. A door nobody can walk in at goes to the town's
  works and is dug out; a dark stretch of street gets a lamp (a fence post and a torch from the stores); litter
  lying about (never a player's) is picked up and carried to the stores; and two neighbours who cannot abide
  each other are talked round and shake on it. The warden's card says so, and the books keep its reports.
* **The public works fund.** From the Stone Age, in a town of six or more, the board shows a fund for a statue
  on the square: sixty coins. Folk with savings put in a coin or three now and then (the generous most). You
  give by right-clicking the board with village coins in hand (a coin a click, the whole stack crouching) or
  with `/village donate <coins>`; the town thinks the better of you for it. The coin is kept in the fund until
  the sixty are raised; then it goes into the treasury and the statue (a stone figure holding up a lantern, on
  a plinth with lanterns at its corners) goes to the head of the build list. When it stands, a sign before
  it names those who gave most.
* **Search parties.** Every few seconds the town notes where each of its folk is. One not seen in the town,
  at its own work, in its bed or away on business the town knows of (a caravan, an errand, scouting, the
  Nether, a mine run) for a whole day is missed: its partner, parents and grown children and two or three of
  its friends go out to look, to where it was last seen and round about there, calling its name. It calls
  back when it hears them. Found underground, it is set on its way up the nearest stairs and they wait at the
  top; found stuck in a hole, they cut it a step out; then they bring it home, and the chronicle tells who
  found it. At nightfall they go home and out again at first light; after three days the search is given up.
  The board shows who is missing.
* **Neighbourly favours.** Of an evening, a folk short of something goes round to a neighbour: the loan of a tool
  its trade wants (a spare out of the neighbour's own pack), a bite to eat when it has gone without a meal
  (sugar if the neighbour has any), or a hand carrying a heavy load to the stores. The things change packs
  for real, and are paid back: the tool once the borrower has one of its own (or two coins for it), a bite
  once there is food to spare (or a coin), a coin for the carrying. Both think the better of each other for
  it; a folk who has done five good turns is known as a good neighbour, in the chronicle and on its card.

A folk's card has a *Town life* line (its letters, its quarter if it is a warden, its petitions, its good
turns, what it gave to the fund), and the books' News page has *The town's affairs*: the meetings, the
petitions, the fund, the post, the wardens' reports, the searches and the good neighbours. `/village civic`
prints the same. Tested by `TownAffairsGameTests` (tg01 to tg07).

## Prices and paying for things

Every town now has prices of its own, set by supply and demand, and once it has a shop its folk buy what they
want for themselves.

* **Prices by supply and demand.** Every morning each town reckons a price for every good on the board and
  every ware its sellers deal in. Supply is what its stores and its shop hold and what it made yesterday;
  demand is what was sold at its counters, what was asked for and not there, what folk drew from the stores
  and what went into making other things. Three days' want on hand is the usual price; less makes it dear,
  more makes it cheap. What folk would not pay brings a price down. A price stays between four tenths and
  three times what the thing is usually worth, and moves a little each day (never more than fifteen in the
  hundred), so bread does not double overnight. A new town sells at the usual worth until its first morning.
  Market day's tenth off, a slow ware's markdown and the shop's floor at what a thing cost to make still apply.
* **One price everywhere.** The board's stall signs, the shop's and the café's price signs, what the shop
  charges, what passing traders pay, the gazette and the books all go by the same price. A price sign shows
  "↑ dearer" or "↓ cheaper" under the price when it is moving.
* **Free until the shop opens.** In a young town folk take their meals, rations, packed lunches, the tool of
  their trade and a child's bed out of the stores, free, as before. From the day the shop opens, all of that
  is bought at the town's price out of the folk's own purse, and the coin goes into the treasury that pays
  the wages. What their work uses stays the town's: the fields' seed, the mine's torches, the builders'
  blocks, the watch's arrows and the guards' kit and blades.
* **Homes.** A household that owns its house pays for a child's bed and the furnishing set out in it, at the
  town's price. A house the town lets is furnished by the town, its landlord.
* **Buyers who mind the price.** A folk weighs a price against what it expects: the usual worth, nudged by
  what it paid last time. How far over that it will go depends on how well off it is and its nature (thrifty
  folk and Merchants less, the generous and Free Spirits more). A hungry folk buys its meal and a worker its
  tool whatever the price, though it picks the cheaper food when its favourite is dear. A treat or a luxury
  that is too dear stays on the shelf, and the refusal counts toward bringing the price down. When something
  is cheap, folk buy more: a day or two's food put by, a second treat, a luxury for the home sooner.
* **Change and the slate.** Prices go to the hundredth of a coin; a folk pays in whole coins and its change
  is kept at the counter for next time. One that cannot pay for its food or its tool has it on the slate,
  paid back first out of its next wages: nobody goes hungry or without its tool. A child's food is its
  family's to pay; a folk with no wage and no coin is fed from the poor box, and a slate past two weeks of a
  field hand's wage is let go.
* **The living wage.** Two meals and the cheapest rent at today's prices must fit in the lowest wage. If they
  do not, the books say so, the chronicle tells of it and the elder raises it at the morning assembly.
* **Where you see it.** `/village prices` lists every price today against its usual worth, which way it is
  going, the stock against what is made and wanted, what folk thought too dear, the bargains, the slates and
  the cost of living against the lowest wage. `/village prices page` opens the town's books at the new
  **Prices** page, with the same figures and a line a good (hover over a row for its supply and demand).
  A big move in a week goes on the board, in the gazette and in the chronicle ("bread dear this week: the
  harvest failed"). A folk's card has an "At the counter" line (what it owes, its change, what it last bought
  and what it left as too dear), folk grumble or cheer at the prices when they buy, and ask any folk "how are
  prices?" for its view.
* **`/village economy` and `/village larder`.** `/village economy` is the economy page again (what the town
  makes, sells and is worth); the larder, fuel and fields report is `/village larder`.

The game tests `PricesGameTests` (px01 to px06) check that scarce, wanted bread grows dearer day by day and a
stack nobody buys grows cheaper; that a rug at three times its worth goes unbought while dear bread is still
bought by a hungry folk; that cheap cookies are bought two at a time; that a supper is free before the shop
opens and paid for into the treasury after; that a folk with an empty purse still eats and its slate is paid
back from its wages; and that a rug costs one rug's price, not a lot of four's.

## The town store

Once a town has a shop and folk buy for themselves there, the shop runs as a real business: it has stock of
its own, it is kept stocked by deliveries and by its own crafters, and it has staff who each do a job.

* **The store.** In the Iron Age, a town of forty (or a town of twenty-eight whose shop sells a hundred things
  a week) builds a town store on one of the long lots by the square: eleven across and seventeen deep, two
  storeys. On the shop floor six counters of casks, each with the thing it sells in a frame on top and a price
  tag in front; behind a partition the **stockroom** (chests along the back, casks two high down the wall,
  more chests) and the crafters' **workshop** (a crafting table, a furnace, a loom, a grindstone and an anvil);
  up the stair the loft (more of the stockroom) and the **stock keeper's office**, its desk, the stock book on
  a lectern and a shelf of books. The old shop keeps going as the store's branch: its counters still serve,
  and its back room's chests are part of the same stock. Nothing is pulled down or wasted.
* **Its own stock.** What the shop sells comes out of its stockroom and its counters, not straight out of
  the village's stores. The chests and casks in the shop and the store are the shop's, not the stores'. If a
  folk wants something the stockroom has run out of, the shop sends to the stores for it so nobody goes
  without, but that is a **stock-out**: it goes in the stock keeper's book, and the stock keeper orders that
  thing in.
* **Deliveries.** Goods come into the stockroom as real deliveries. The storehouse's couriers carry them
  between their own runs: so many of a thing packed at the storehouse, walked over and put away. A delivery
  no courier takes, the stock keeper fetches itself. What the shop's crafters make to sell (for its
  shelves, or on the stock keeper's order) goes straight into the stockroom. The town's own needs (the
  watch's armour, the storehouse's tool rack, a player's order) still go to the stores. Food that has sat
  more than four days' worth, and anything nobody has bought for four days, is carried back to the stores.
* **The staff.** All of the shop's trade, each with a title on its card and its nameplate:
  * the **shopkeeper** runs it and sets its prices with the stock keeper;
  * the **shop assistants** stand behind the counters in working hours, one to a counter, and serve. A folk
    buying comes to a counter and the assistant there hands it over and takes the coin, with a word on each
    side;
  * the **shop crafters** are the hands at the bench (the workshop's hands), making what sells;
  * the **stock keeper** has the hardest job. Every morning it walks the stockroom and counts every ware: on
    hand, sold, wanted and not there, and how many days' sales it has left. From each ware's rate of sale it
    sets a reorder point and an order-up-to level, then orders, food first: from the storehouse if the stores
    can spare it; else from the crafters, by way of the smelter if it wants firing; else it puts up a notice
    that the store wants it (on the quest board, and in what the leader says the town is short of). After the
    midday meal it looks at the food again. It chases the crafters when they are two days late, and is judged
    by its stock-outs: its card says how many this week. A shop with no stock keeper only restocks what its
    keeper notices has run out, a lot of each, four things a day at most.

  The little shop takes on an assistant in a town of twenty-four and a stock keeper at thirty. The store wants
  a stock keeper from the day it opens, and one assistant, two at fifty-five folk, three at eighty-five (or
  two hundred and fifty sales a week), never more than its counters. They come first from the shop's own spare
  hands, then from folk between trades. The stock keeper's job goes to someone experienced, hard-working and
  curious; the counter to a cheerful, sociable face.
* **The prices.** The shop asks what a thing costs in the town today plus its margin: a quarter at first,
  raised a little when a thing sells out and lowered when it sits unsold three days (food never more than
  thirty-five in the hundred over). Slow stock is still marked down, and never sold under what it cost. The
  price tags on the counters show the shop's price. The coin goes to the treasury.
* **Buying as a player.** Right-click a counter to buy a lot of what is on it (crouch to see the price), or
  right-click the assistant behind it with village coin in your hand. The town's view of you still counts:
  friends and citizens pay a tenth less, the unwelcome pay double, and an outcast is not served.
* **Where you see it.** `/village stock` is the stock book: every ware on hand against its reorder point and
  its level, its days of cover, what sold and ran out this week, its price and margin, the orders in flight
  and where they went, what the store wants, the day's entries and the staff. The board says what the shop
  sold yesterday and what it is out of; the gazette has a piece on the store; once a week the chronicle says
  something like "the store sold 140 loaves of bread this week; out of iron pickaxes twice". Operators can run
  the count now with `/village stock count`, or set a staffed, stocked store down with `/village stock stage`.

The game tests `StoreGameTests` (sx01 to sx06) check the store's counters, stockroom, benches and desk, and
when a town wants one; that a sale comes out of the stockroom and not the stores, and that a stock-out falls
back on the stores and is booked; that a delivery moves real loaves from the storehouse into the stockroom;
that a ware nobody has is ordered from the crafters and what they make goes into the stockroom; that a store
is staffed with one keeper, a crafter, an assistant and a stock keeper; and that a folk buying at a counter is
served there by its assistant. The unit test `BlueprintSoundnessTest` checks the store's drawing.

## What every job is worth

Every job in a town is paid for what it is worth: what it brings the town, how hard it is to get hands
for, how hard it is and the skill it takes, and how good the folk doing it is. A folk's day's wage is
**the town's pay level × the job's worth × its own hand at it**, and the wages page shows every part.

* **The pay level.** What the place is (a hamlet pays the unit, a village half as much again, a town
  twice, a city two and a half times, a capital three), at what the treasury can afford. Each morning
  the town sets the day's wage bill against the money coming in (the day's work taken in, sales, the
  tax, the tithe, the rent, houses sold and what the folk spend in town, a few days smoothed): while the
  bill runs over seventeen twentieths of it the rate eases down a little each day, as far as thirteen
  twentieths of the full rate; while there is plenty coming in and a week's wages put by, it eases up,
  as far as six fifths. The leader's own rate (stingy or generous) and the tax come on top as before.
* **Value to the town.** What a hand of the trade brought in yesterday, at the town's own prices,
  against what an average hand brings in. A maker counts half of what it made as its own (the ore, the
  wool or the wheat it worked was somebody else's work). The jobs that make nothing to sell are paid for
  their service: the watch keeps folk alive; the couriers and the storekeeper keep the goods moving; the
  banker keeps the savings and the loans; the teacher teaches the children; a healer, if the town has
  one, keeps folk well; and at the shop the keeper runs the place, the assistants serve at the counter
  and the stock keeper keeps the shelves full. A bigger town leans on its services a little more.
* **Scarcity.** A trade short of hands for the town's shape is paid more, one with hands to spare less;
  more again for a notice on the board nobody has answered, and for a skilled trade few in the town have
  the skill for. It moves a third of the way a day and never by more than a tenth, so pay rises over a
  few days while a trade stays short, and does not jump about.
* **Difficulty.** Fixed for each job: the fields, the water and the couriers' rounds are easy; the woods
  heavy; the mines, the watch and the furnaces hard and dangerous; the smith, the enchanter, the banker,
  the teacher and the shop's stock keeper need much skill. A shop assistant's is the easier job, the
  stock keeper's a hard, skilled one.
* **Its own hand.** Its level at the trade on a smooth curve: four fifths of the rate new to the work,
  the rate at about level five, half as much again for a master; a little more for a maker whose marks
  say good, fine or a master's work, and for each knack of the trade it chose.
* **The living wage and the top.** Nobody is paid less than two meals and a house's rent at today's
  prices (two loaves at the town's price of bread, and a plain house's rent), whatever the job or the
  leader's rate; nobody's worth is paid more than five times the lowest wage. On top, as before: one for
  the elder, up to two for a hard day's work, a Haggler's twentieth, and the teacher's mornings at the
  school.

**Seeing it.**

* **`/village wages`** (and the journal's Wages page): the pay level, the lowest wage against the cost of
  living, the top of the scale, the day's bill against what comes in; then every job's day for a
  journeyman with its worth part by part ("Miner 6 a day (worth ×3.4): value ×1.00, scarcity ×1.27 (1
  at it, 2.2 wanted, short), difficulty ×1.54 (hard and dangerous work)"); then everybody who works,
  best paid first, with why. **`/village wages show`** opens it on your screen; **`/village wages
  books`** opens the town's books at the Jobs page, where the mouse over a trade shows its worth and the
  pay scale's three lines sit under the table; the Folk page's tooltips say why each is paid what it is.
* **A folk's card** has a **Wage** line: "Paid 6 coins a day: the mines are short of hands, hard and
  dangerous work and it is a skilled miner." Ask a folk "what's your wage?" and its card opens at it;
  ask how it is doing for money and it tells you the same in its own words. **`/village wages card
  [n]`** opens the card of the n-th best paid.
* **The news.** When a short-handed trade's pay goes up, the chronicle and the morning assembly say so,
  and the gazette prints it in a **Wages** column that morning ("Miners' pay is up to 4 a day, from 3:
  the mines are short of hands.").
* **Changing jobs.** A town's notice on the job market offers what the job is worth there for the hand
  it asks for, the more for being short; a folk weighing a notice sets it against what it really earns
  at home. A hand moving to a trade its own town is short of goes to the best paid of them first, and
  says so if the pay is better ("They're short of hands at the mines — 6 a day against my 3.").

The game tests `WagesGameTests` (wg01 to wg06) check that a trade short of hands is paid more than the
same trade over-staffed, and the gazette tells of it; that a master is paid more than a novice at the
same trade, on a smooth curve; that the shop's stock keeper is paid more than its assistant; that the
watch is paid for its service though it brings nothing in; that the lowest wage covers the cost of
living even when the treasury can afford next to nothing; and that the day's bill settles within what
a steady town takes in.

## Building your own home: the housing market

Every house the village builds is **the council's**: it lets them first, and sells them to the tenants
who save up for them, as it always has (see *Homes*). Later on, a household that has done well can
have a house **built for itself**, and pays for every block, every hour of the builders' work, the
ground it stands on and the council's permit. And what a house fetches, and what it lets for, now
follow supply and demand.

* **The council's houses, on the books.** Each house the council builds is costed the first day it is
  on the books: every block of its drawing at what that block costs in the town that day, and its
  builders' hours (twelve blocks an hour, at a craftsman's rate: a smith's day of ten hours). The cost
  is written against the treasury's books ("the council's 9 houses cost 342c to build"), and costed
  again when the house is raised a storey or the town comes into a new age and its houses are rebuilt
  in stone and brick. What a house cost to build is what it is **worth**: the market prices it from
  that, no longer at a flat 35, 55 or 120 coins.
* **The housing market.** Each morning the town weighs the households wanting a home (the waiting
  list, the grown children still at home, the newcomers), the families outgrowing their house (more
  of them than beds) and, a little, the tenants saving to buy, against the houses and flats standing
  empty. The **housing index** moves by what is short or over: up to about five in the hundred a week,
  never under six tenths of what a house costs to build nor over nearly twice it. House prices are the
  house's worth times the index (and, as before, its quarter: dearer by the park, cheaper in the
  crafts' smoke; its furnishing; the town's Home Loans); **rents** are the old rent times the index,
  never under a coin, so they rise when homes are scarce and fall when they stand empty. With the two
  about even it drifts back toward what a house costs. The founders' rent-free start and the slate are
  as they were.
* **Who builds its own.** Past the Wood Age, in a town of sixteen, a household with a trade can
  commission a house: the well-off (and the comfortable, a little), a family outgrowing its house, a
  Visionary who wants a house to its own drawing, a Merchant with a front to show, a couple wed this
  week, any household that wants to own; a Free Spirit would rather rent and keep its coin. One house
  of a folk's own goes up at a time; the others save.
* **What it builds.** By its taste and its purse, the dearest it likes that it can pay for:
  - **a cottage**: the council's house, in timber on a stone footing (four beds);
  - **a family house**: the two-storey house, in dressed stone under a shingled roof (six beds);
  - **a town house**: the two-storey house in dressed stone under slate;
  - **a villa**, a drawing of its own: detached and square, brick under a hipped slate roof coming down
    on all four sides, a porch on two posts with a lantern hung under it and flowers by the step, a
    chimney stack up the side, a parlour below and the bedrooms upstairs (six beds).
  A Visionary wants the villa first, a Merchant the town house, a Traditionalist the cottage, a family
  the family house. It is laid in what the stores can pay for all of (Masonry): brick short, dressed
  stone; or, with even that short, the council's timber.
* **The plot.** A lot of the town's plan where homes go, the homes' quarter first, as the builders
  choose any (the gardens, the woods and the sweepers keep off it while it goes up). Where the council's
  builders find none (a hilly town, a town by a river), the household finds one for itself on ground the
  council would not build on: as steep as eight blocks across the house, or with up to a third of it a
  river's shallow edge, filled from the bed. It pays for the ground made up (the bill's cobblestone), so
  a steep lot is a dear one. A villa with no lot to its size anywhere is built as a town house. Its price: eight
  coins in a Wood Age hamlet, a quarter more an age and as much more as the place's wages are, a
  quarter more on the first ring of streets round the square and a fifth less out at the edge, dearer
  by the park, cheaper in the smoke, and as the housing market stands.
* **The bill.** Every block of the drawing at the town's price today, line by line ("98 oak planks at
  0.07, 47 bricks at 0.55, ..."), the ground made up under it on a slope, the builders' hours at their
  rate, the plot, the **furnishing** it wants (its beds, one each and one to spare, its rugs, its
  flowers, a barrel) and the **permit** (a twentieth of the building, two coins at least). The
  chronicle has the whole of it: "Tansy and Rook commissioned a family house of their own at No. 6,
  Elm Street: 142 coins all told — blocks 61, builders' labour 18, plot 12, furnishing 24, permit 3".
* **Can it pay?** All of it, or no house: its purses (a dozen coins a head kept back to live on), what
  it had put by toward the house it rents, its savings at the bank, and, once the town has a bank, the
  bank's **mortgage** for the rest with a fifth down, if its wages carry the week's payment: no more
  than a third of them (the bank's rule), and no more than they leave over its week's bread (two loaves
  a day each, at the town's price), its rent while it waits and any payment on the house it has. A
  household that cannot says so and keeps saving ("Saving for a family house of our own — 24 of the
  29 down put by. We'll get there."), and its card shows what it is saving for.
* **Paying, and building.** The plot and the permit go to the treasury the day it is agreed; the rest
  is held for the build. The town's hands build it a course at a time (the hand the town's work calls,
  one its own trade can spare: so its hours are its own, and the household pays it for them, into its
  purse, as it lays each course). Each course's blocks come **out of the stores** there and then
  (stone bricks cut, bricks and slate made of their makings if need be; timber a plank a board) and are
  paid for into the treasury at the price agreed; then the beds and rugs. Short of anything, the build
  **waits** for the stores ("waiting on the stores for 12 glass panes"); three days on the same thing
  and the rest goes up in what they have, at no more than the price agreed. The town's own buildings
  come first, unless the town is thriving. If the bank takes the mortgage back while it is going up,
  the council finishes it as one of its own.
* **Moving in.** When it stands the household **owns it outright** (or on the bank's mortgage) and
  moves in with its things; anything left over of what was held pays the mortgage down or goes back to
  the purses. A house it rented goes back to the council to let. The chronicle, the gazette ("Built")
  and the morning's assembly have it: "Tansy and Rook moved into the family house they had built at
  No. 6, Elm Street: 142 coins all told, every block of it paid for (... labour 18 to Bramble 9, Fen
  9 ...)". The council never rebuilds, raises or furnishes a folk's own house: it keeps the look it
  paid for.
* **Selling.** An owner leaving its house (moving up to a manor or into a house it had built, into its
  partner's house, into the leader's hall) sells it **at the going price**, not back at half: to a
  household that wants a home and can pay (those waiting first, then the tenants saving to buy; out of
  what it has, and the bank's loan if it needs one), which moves in owning it; with nobody able to buy,
  back to the council at four-fifths of the going price. The seller's mortgage is paid off out of the
  price. Every sale is kept.
* **Where to see it.** The books' **Homes** page has the market under the beds: the index, its
  fortnight drawn small and the week's move, a house's and a manor's price and rent, the waiting and
  outgrowing against the empty, what the council's houses cost to build, and the house going up (a bar
  of what is laid, and its bill) or the last one built and the last sale; the mouse over it for the
  bill item by item, the builders' pay, the plans folk are saving for, and the sales. `/village house`
  starts with the market's lines; `/village house market` adds the bill line by line. A folk's card
  has an **Own house** line ("Its own family house going up at No. 6, Elm Street: 120 of 290 blocks
  laid (41%). The bill 142c: ..." / "Built its own villa on day 41: 162c all told ..." / "Saving to
  build a cottage of its own: about 66c all told, 34 to hand — a fifth down wanted"), and asked where
  it lives it says so too ("... And we're having a family house of our own built at No. 6, Elm Street —
  the walls are going up, every block paid for.").
* **For operators and the pictures.** `/village house market custom` has the best-placed household
  commission a house now (a grant from the treasury making up what it lacks, said in the chronicle);
  `/village house market build 100` lays a hundred blocks of it now, out of the stores, as the builders
  would; `/village house market day` runs the morning's reckoning; `/village house market stage` levels a
  plot at the spot (or the first clear one east of it), delivers a villa's makings into the stores and grants
  the best-placed household what it lacks (the chronicle says so), and commissions the villa there.

The game tests `HousingMarketGameTests` (hm01 to hm05) check that a house's bill is its every block at
the town's price plus the builders' hours plus the plot, the furnishing and the permit, line by line;
that a folk that cannot pay commissions nothing, and nothing is taken; that one that can pays, the
treasury has the plot, the permit, the blocks and the furnishing and the builders the labour to the
coin, real blocks leave the stores, and it moves in owning the house; that the index, prices and rents
rise while homes are scarce and fall while they stand empty; and that an owner moving up sells at the
going price to a buyer (or to the council at four-fifths), every coin counted.

## Trade between towns

Towns trade with each other the way people do: one town has stone it cannot use and too little
food, its neighbour the other way round, and their envoy and leader work out a deal that suits both.

**What a town is good at, and short of.** Every town keeps a **trade book**, ware by ware: food,
timber, stone, ore and iron, coal, wool and the crafts. For each it shows what the stores hold
against what the town keeps for itself (a full larder, so many logs and so much stone a head, the
smiths' iron, the smelters' coal, wool for beds), and so what it has **to spare** (only what its own
needs leave over) or how far it is **short** (under what it keeps, or what its age still asks for).
It also shows what the town makes a day and what goes out a day, how many days that lasts, how its
land leans (a mountain town lives by its mine, a river town by its fields, a forest town by its
timber), what one costs here today, and what one is **worth to the town**: dearer when it is short,
cheaper when it has a glut.

**The envoy and the leader.** When two neighbours on decent terms each have something the other is
short of, an elder sends an envoy to talk trade (and again when a deal nears its end). The envoy
brings its town's offer list and want list. Before the host's board, with the town gathered round:
* The host's leader holds them up against its own books, and the two **bargain in rounds**. The
  envoy opens ("128 cobblestone every 3 days, for 80 bread and 15 coin"), the leader counters ("I'll
  give 8 bread, and that's fair"), and each round both give up part of the gap.
* **Each side reckons by its own prices.** Stone is cheap in the mining town and dear in the farm
  town that has none, and bread the other way round. That difference is what there is to gain. A
  deal is only struck where **both towns gain by their own reckoning**. With no such room there is
  no deal, however long they talk.
* **The leader's nature tells.** A shrewd leader opens hard and gives little ground. A warm or
  open-handed one opens near the middle and meets you quickly. A prickly one may walk out of talks
  going nowhere. Friends meet sooner, rivals hold out, and two elders who get on settle faster.
* **Coin balances it.** The buyer pays in its own spare goods as far as they go, then in coin.
* The deal says what goes each way, how much each delivery, how often (every three days, four to a
  far town), for how long (three weeks), and what a delivery missed costs: a tenth of the price.
* The town hears it all, a few lines at a time, and the chronicle tells it the way you would: "Ashford's
  envoy offered 128 cobblestone every 3 days for 80 bread and 15 coin; Brindle's elder offered 8
  bread; then 52 against 30, ...; they settled at 54 bread and 3 coin, for 3 weeks". With no deal
  the two towns still think a little better of each other, unless somebody walked out.

**Carrying it out.**
* Deliveries go **by caravan**, each town's in turn, on the agreed days. A carrier sets out with its
  own town's goods: the agreed goods only, never more than agreed, and never more than the town can
  spare. If its town is paying, it takes the coin in its purse.
* At the other town it unloads, the other town loads its own goods for the way back, and **the coin
  changes hands there, in person**. Home again, the goods go into the stores and the coin into the
  treasury. Nothing goes chest to chest.
* Each delivery is booked: made against due, each side's shortfalls, and what each town has gained
  by its own prices. A side that falls short owes the penalty, and three short in a row break the deal
  (and the friendship suffers). A deal that runs its term well leaves the towns warmer. A feud tears
  it up. Renewing one is another audience, at the prices of the day.
* What a town has promised its partner is **spoken for**: the passing traders do not buy it on market
  day, and the fields and the mine are not cut back as if it were a glut.
* A caravan or envoy on the road is written down, so after a restart it walks on with its goods and
  its coin.

**Specialising.** With the bread coming in reliably, the stone town needs fewer hands in its fields,
and with stone promised it needs more in its mine. Each morning the leader leans the trades' shares
toward what the deals want: fields down, mine up (more so where the land backs it), and the reverse
for the farm town. It moves a step a day, never below two thirds or above seven fifths of the usual
share, and **never fewer farmers on short commons or in a hungry town**. If the deliveries stop, the
hands go back to the fields the next morning. Over the days you can watch one town mining more and the
other farming more.

**Where you see it.**
* **The town's books** (the board) have a **Trade** page: the trade book, ware by ware (the mouse
  over a ware for all of it); a chart of the town's farmers and miners over the days, with how far its
  deals lean their shares; its deals (partner, terms, deliveries made against those due, shortfalls,
  what it has gained by its own prices, what either owes); every negotiation, round by round; the
  deals past; and the caravans' comings and goings.
* **The board** says what the town is good at and short of, and its deals.
* **The gazette** has a Trade section: yesterday's deals and caravans, and the deals standing.
* `/village trade` gives the same in chat. `/village trade books` opens the Trade page. For operators:
  `/village trade now [town]` (bargain at once, every round shown), `/village trade talk [town]` (send an
  envoy now), `/village trade deliver` (the next delivery sets out now), and for the pictures
  `/village trade stage` (the town and its nearest neighbour stocked to trade if neither has anything
  the other wants, and the neighbour's envoy before the board), `/village trade audience` (how that
  audience is going) and `/village trade road` (the deal's caravan set out from this town, a third of
  the way along the road).
* A poor town is offered a smaller lot it can pay for. If the envoy's offer comes to nothing, the two
  leaders try it the other way round: the envoy buys the host's surplus instead.

The game tests `TownTradeGameTests` (td01 to td07) check each of these with a stone town short of
food and a farm town short of stone: each town's book names the right surplus and shortage; the envoy
and the leader strike a deal each gains by at its own prices; a shrewd leader gives less ground than a
warm one; two towns with nothing the other wants strike no deal, and the relation still moves; a
delivery carries the agreed goods out of one town's stores into the other's, the coin carried in the
purse (and kept through a restart); the stone town's farm share leans down and its mine's up while
the bread comes, and back the morning it stops; and the whole audience before the board.

## Scouts at war: knowing the enemy

A town at odds with a neighbour (in a feud, or at war) wants to know what it is up against, and it finds out
the way you would: somebody goes and looks.

* **A scout sent to the enemy.** Of a morning, a town on its guard sends one of its folk to watch the town
  it is at odds with: its scout if it has one, else whoever has the sharpest eyes, a courier who knows the
  stable's horses, the rancher or a hunter; never a guard and never the leader. It takes food for the road
  out of the stores (and a saddled horse, if the stable has one standing free), walks there the way a scout
  walks, and lies down in the grass on the highest ground it can find outside the enemy's streets. For a
  minute it watches and counts what it can really see: the guards out of doors, which are in iron and
  which carry bows, the wall's sides and its gates, the folk about the streets and the houses for the rest,
  and the fields and granary for a guess at the food. A guard indoors is not seen, and not counted. Now and
  then a bold one (a scout, or a curious folk, at war) slips in among the houses afterwards and, if nobody
  stops it, counts the barracks and the granary too. In a feud a town looks again every four days; at war,
  every two.
* **The report, and its age.** Home again, the scout files its report, dated the day it watched, in its
  own words ("watched from the ridge south-west of it, on Bess"). It tells the leader if the leader is
  about, and the morning assembly hears it; it goes in the chronicle, on the board ("The enemy: Brindle (at
  war): 6 guards ... a day old") and on the war map, always with how old it is. From the fifth day a report
  is marked old.
* **The strength reckoning.** The town knows its own strength exactly: its guards, those in iron, its
  bowmen, its wall and gates, its days of food, its allies. The enemy's it knows only from the latest report,
  or from rumour if nobody has looked (its size, whether it has a wall, and a guess at the rest). The two set
  against each other give a balance: well over one, the town reckons itself the stronger; under nine in ten,
  the weaker, and the one that should look for peace. The war council goes by it, and so does the number of
  guards the town wants to keep (enough to hold off what the enemy could bring, a fifth to spare).
* **The fog of war.** An old report is not believed as it stands: the older it is, the further out the
  leader's guess may be (six in the hundred a day, up to sixty), and the less sure the council is of it. The
  leader's temper bends it too: a prickly leader makes light of the enemy, a wary one sees more spears than
  there are. A town that went to war on a bad guess is told so in its chronicle when it learns the truth:
  "we went to war with Brindle thinking it held six guards; it held fourteen".
* **Catching spies.** The watch keeps an eye on the hills. A guard (or a picket) that sees a stranger from
  a rival town watching gives chase; the spy jumps up and runs for home. Caught, it is held at the barracks
  and questioned, and the town that caught it learns what it knew of its own town; its own report is lost
  with it, and relations between the two get worse. Chased off, it goes home and says it was seen. When the
  two towns make peace (or exchange captives at the peace talks) it is let go and walks home.
* **Pickets on the roads.** On its guard or at war, a town puts a picket on the road toward each rival
  (two at most), thirty blocks out past its last buildings: a guard the watch can spare (half the watch
  always stays in), else one of the militia or a hunter, one by day and another by night. A picket that
  sees a spy points it out to the watch. One that sees an envoy coming runs ahead and the town bell is rung
  once to fetch the leader. One that sees any other folk of the enemy coming runs home with it, and the
  alarm bell is rung early: the gates shut and the watch goes up on the walls while the enemy is still out
  on the road, and the bell keeps ringing while they are about.
* **The war map.** `/village war map` lays it all out in words; `/village war map books` (or the War map
  tab of the town's books, from the board) draws it: your town in the middle, north up, every rival where
  it lies (red with a report, amber on rumour alone) with its last known guards and the report's age, where
  the pickets last saw folk of theirs, your pickets on the roads and your scouts out; and beside it, for
  each rival, the report, what the leader believes now and how sure it is, the balance of strength, and how
  many guards the town wants against it. Ask any folk what the scouts have found and they will tell you
  what is known of the enemy, too. `/village war intel` gives the figures in a line each.

The game tests `ScoutingGameTests` (si01 to si05) check each part: a scout sent to a town in a feud counts
its four guards (two in iron, one bow) from where it lies and files a report dated that day; a report's
guess drifts further from it with every day it ages, and a prickly and a wary leader read it differently;
a town with a report of a strong enemy reckons itself the weaker and wants more guards, and with a report
of a weak one the stronger; the watch runs down a spy, questions it, and lets it go at peace; and a picket
sees the enemy coming up the road and the bell rings while they are still outside the town.

## A town on a war footing

When a town falls out badly with a neighbour (a feud) it goes **on its guard**; when the two go to war it is
**at war**. Either way it changes the way it works, and at peace it goes back to its old ways. The page for it
is `/village war footing`, and the News page of the town's books has a panel "On a war footing" whenever there
is anything to show. The board says so too, and a folk's card has a **War** line when it has a part in it.

* **More guards, sized by the scouts.** The town wants more of its folk on the watch: enough to meet what it
  reckons the enemy has, by its scouts' last report of the enemy's guards and how many of them are in iron.
  With no report, or one more than ten days old, the leader makes a cautious guess from the enemy's size
  (a shy or grumpy leader one guard more). A wall lets a few hold against more. On its guard the town goes
  half-way to that number; at war, all the way, never more than three in ten of its grown folk. The hands come
  out of the woods, the pens, the hives and the crafts' benches, never the fields, the fishing or the hunt:
  the farmers' share does not change by a single hand. The page shows how the enemy was reckoned.
* **Volunteers.** Each morning the leader calls for volunteers to make up the watch: one a day on its guard,
  two at war. Those who care most for safe streets (the Guardians) come forward first, then the hardworking
  and the generous; the shy and the easygoing hang back, and somebody who has drilled with the militia is
  readier. Never a town's last hand at a trade, its storekeeper, banker or elder, nor a farmer while the town
  is short of food. At peace each goes back to the trade it left.
* **The work changes.** The smith makes swords, armour, bows and arrows for the militia as well as the watch,
  and stops holding the armour back for the next age; the elder orders the mines dug when the arms want
  iron, or the walls manned while the watch is short; the couriers carry the arms to the armoury.
* **Danger money.** A guard is paid a tenth more on its guard and a quarter more at war (more again when the
  enemy is reckoned the stronger); a militia hand called up gets a tenth on top of its own trade's wage.
* **The militia.** A quarter of the town's able grown folk are enrolled on its guard, a third at war. They keep
  their own trades. On the day of rest, while everybody else plays games on the square, the militia drills at
  the training yard (before the barracks if there is no yard, on the square failing both), and every drill
  teaches it a little of the watch's trade. At war it is called up: armed out of the armoury (a sword, and a
  helmet or a breastplate if there is one), counted among those who fight for the town, and every morning of
  the war it musters and drills for an hour and a half, away from its own work. At peace it hands its arms
  back and is stood down.
* **Fortifications.** On its guard the town builds its wall before anything else; the armoury and the training
  yard come after its next house; at war a well, if it has none, comes first of all, for water in a siege. A Wood
  Age town with no wall stands a **palisade** of logs two high on the wall's line (at war, or on its guard if it
  has a good pile of logs), and takes it down again, logs back to the stores, when the stone wall is to go up.
  A walled town raises **corner towers** (each corner built out three across and two over the wall, with
  battlements), and at war builds a **gatehouse** over every gate and digs a **ditch** two deep along the
  outside foot of the wall, with a causeway at every avenue. All of it is done by hand, a few blocks at a time,
  out of the stores' stone (sixty-four always kept back for the builders), and the earth dug goes into the
  stores.
* **The armoury.** A small stone house with racks of chests, an anvil and a grindstone. What is in it is not
  the town's stores: it is never sold or spent. The couriers fill it from the smith's work up to what the
  militia wants, the militia draws its arms from it when called up, and hands them back to it at peace.
* **The training yard.** A fenced yard with three dummies and two archery butts. The militia drills there, and
  every guard takes a turn at the dummies each day the town has one; the watch grows better at its trade.
* **Siege stores.** The leader keeps more food put by: half a day more on its guard, four days more at war.
  At war the leader's plan is **War** (on the board and in the books): the larder is kept against a siege, more
  hands go to the fields while it is under that, and no food is sold to the traders out of it on market day. A
  famine is still a famine. Arrows are made for everybody who would fight, and sugar cane is pressed into paper
  for bandages (one for every two folk at war).
* **What it costs.** The page shows what standing armed costs a day: the danger money, and the hours of work
  lost (the volunteers' old trades, the militia's muster), in coin.

Ops can try it with `/village war footing tension`, `war` and `peace` (against the nearest other town) and
`/village war footing now` (the leader's morning at once). The game tests `WarFootingGameTests` (wr01 to wr06)
check it: more guards and fewer woodcutters on its guard, the farmers untouched, the scouts' report sizing the
enemy and a stale one making the leader guess, and volunteers on the wall; the militia enrolled, drilling,
called up and armed at war, the watch's pay up; everybody back at their old trades at peace and the swords
back in the stores; the palisade, the wall at the head of the list and why; the leader keeping more food and
the plan going to War; and the armoury filled from the stores, the militia armed out of it, and a guard at
the dummies.

## War and peace

Towns can go to war with each other, but rarely and never for nothing. A war between towns is an armed
standoff: it is declared, the towns scout each other and stand on a war footing, and it ends at the
table with a treaty. Nobody marches, and no block is broken. Turn it off with `villageWars = false` in
the config; then a feud stays a feud.

* **How a war begins.** Two neighbours must be in a feud, and one must hold something real against the
  other: a quarrel over the land, a stolen sheep, a broken deal, tribute demanded. A town keeps these
  grievances in its books, taken from what it remembers of its neighbours. Then it depends on who leads
  it. A prickly or shrewd elder who cares most for safe streets is a hawk. A warm or wary elder who cares
  for trade is a dove, and a dove does not go to war. A hawk weighs the odds: its town's strength against
  the other's as it sees it. It uses its scouts' last report if there is a fresh one. With only an old
  report or none, it guesses from rumour, and its temper colours the guess: a prickly elder thinks little
  of the other town, a wary one fears the worst. It never picks a fight with a town half as strong again
  as its own unless its allies would stand with it. Even when all this holds, most days pass without war.
* **The council of war.** The elder calls the council to the hall that evening. A town with no hall
  meets out on the square before the board's face, the ring of councillors four blocks out from the
  board's foot, so all of them can be seen and heard from the square. The elder makes its case,
  then each councillor votes aye or nay out loud, by its own values. A Guardian votes for war. A Merchant
  or a Provider votes against, and so does anyone with family in the other town. The odds count too. The
  elder's vote counts three. A dove council can say no, and then the matter is dropped for ten days. The
  vote goes on the board and into the chronicle, and each councillor's card shows how it voted.
* **What the war is for.** Every war has a goal: a border where we say it runs, tribute, a trade deal on
  our terms (written down for the trade between towns to take up), satisfaction for the wrongs done us,
  or one of our colonies left in peace.
* **The ultimatum.** A herald walks to the other town with the demands and is heard before its board.
  That town's elder weighs its own strength, and its allies', against the herald's town as it sees it:
  - **Yield:** it meets the demand, and the coin goes home in the herald's purse.
  - **Bargain:** it meets half the demand. A prickly elder on the herald's side will not take half.
  - **Refuse:** it is war.
  If the demand is met, there is no war, and the matter is settled for twenty days.
* **Declaration day.** Both towns ring their bells, and each hangs a war banner. The banner goes over the
  gate that faces the enemy, or on the front of the hall, or on a pole by the board. The pole stands on
  the square just past one end of the board, in front of its face, and the cloth faces the same way as
  the board, so it is never hidden behind the board. It is a real banner
  from the town's stores, or one made from six of its wool and a stick. With no cloth in the stores
  there is no banner until cloth turns up. The chronicle, the gazette and any player nearby hear of it.
  Neighbours take sides by how they feel about each town. The towns' sworn allies are called by envoy.
* **Allies.** An ally that answers the call sends up to three of its guards, never more than half its
  watch. They walk over and stand on the walls of the town they are sworn to, raising its strength in
  anybody's reckoning, and they go home at the peace.
* **War-weariness.** Every day of a war wears a town down, measured from 0 to 100. These add to it:
  - the war's length (more after ten days, more again after twenty);
  - its cost: the danger pay and the hours lost to the volunteers and the militia's muster;
  - the day of rest given to the militia's drill instead of the games;
  - a larder kept for a siege, gone short, or empty;
  - trade with the enemy lost;
  - an enemy the town reckons the stronger;
  - its spies held by the enemy, and its guards away on an ally's walls;
  - everyone the war costs.

  At peace it eases again, a little each day. You see it in the town's contentment, in the folk's spirits
  and what they say about the war, on their cards, on the board and at the town meeting. A Guardian is
  proud of the watch early in a war. Once the town is weary, some folk look at other towns' notices for
  work (never the enemy's). Once it is worn out, a folk every few days packs up for a neighbour at peace
  with room for it. A town never drops below eight this way.
* **The wartime election.** A weary town puts up a candidate for peace at its next election, standing for
  "the militia home, the walls stood down, and our trade back". A worn-out town calls the election early,
  once a war. Weary voters lean to peace and away from the leader who took them to war, the more so if
  they care for wages or rest or have family over there. If the war is going well, with the enemy reckoned
  the weaker, the hawk stays ahead. If the peace candidate wins, the new leader sues for peace at once, and
  the town keeps talking until it has peace.
* **Peace talks.** After the war has stood a while (three days for a soft elder, a week for a prickly one),
  the side that reckons itself the weaker sues for peace under a white flag. It judges by its scouts'
  reports, or by rumour if it has none. If neither side reckons itself the weaker, the cost of the war
  footing decides: the danger pay and the work lost, booked day by day. The town it costs most
  sends the white flag. The terms come from the war's goal and the real balance of strength:
  - if the town that began the war is half as strong again, the other gives up the whole goal;
  - if it is a little stronger, the other gives up part of it;
  - otherwise the war ends with no gain. If the town that began it is much the weaker, it pays
    reparations as well: a coin for every day of the war and five more, at most a fifth of its treasury.

  Each town sends home the spies it holds of the other's. A trade deal goal is written down for the trade
  between towns and, where the two towns' books have something to trade, a standing deal is struck at the
  table. Coin owed travels in the envoy's purse.
* **Treaties.** Peace ends the war on both towns' books. The treaty is written into both and shown on
  both boards, and it keeps the peace (a truce, no brawls) for a town's year. Breaking a treaty, such as a
  raid while it holds, is a cause for war. It goes into both towns' chronicles and onto the board of the
  town that was wronged, and every town that hears of it thinks the worse of the one that did it.
* **Peace returns.** On the day of the peace:
  - the war banner comes down and goes back into the stores;
  - the militia hands their arms back to the armoury, and the volunteers return to the trades they left;
  - the danger pay ends;
  - the town goes back to its peacetime building list: defences not yet begun drop off the top, and any
    begun are finished later;
  - a feast for the peace is called for the next day of rest within the week, or the next evening.
* **Memorials.** Everyone the war cost is remembered: a spy who died on its errand, an ally's guard who
  died on another town's walls, or anyone killed by the enemy's hand. If nobody died, the memorial is to
  the war itself and the peace. The plaque is wanted on the day of the peace, and a hand at the town's
  works puts it up on a post before the chapel or the graveyard. If the town has neither, it goes on the
  square in front of the board, past the far end from the war banner's pole. It is made from the stores:
  a sign, and a fence or planks. The day of the peace becomes Remembrance Day, kept every year with a minute's silence at
  the dusk bell. It is on the town's calendar, the board, the gazette and the Culture page.
* **Brokering peace.** A player held in honour by both towns can say "make peace with ..." to a folk of
  either. The player carries ten coins' worth of gifts and the peace is made on their word, with no coin
  owed. Both towns think the better of the player for it.

Where to see it:
* The board shows the war, the council's vote, the herald on the road, how weary the town is, the peace
  candidate, the peace feast and the treaty in force.
* The gazette has a *War and peace* section.
* A folk's card has a *The war* line.
* The town's books have a **War** page. It shows the town's strength and each war: its goal, our
  strength against theirs and what that figure rests on, the cost so far, which side is likelier to sue,
  where the banner hangs and the war's course day by day. At peace it shows the last war, day by day, up
  to its treaty. Below that come:
  - a weariness bar, with what wore the town down;
  - the peace candidate;
  - the quarrels, the allies and their guards;
  - what the town holds against its neighbours;
  - its treaties and its wars before;
  - those its wars cost, and Remembrance Day.
* `/village war` gives the same in chat, plus each neighbour and whether the elder would go to war with
  it today, and why.
* `/village war books` opens the page.
* For operators:
  - `/village war council` calls a council of war over the nearest neighbour, and prints where it sits,
    the side to look at it from and whether it is indoors (`AT x y z south outdoors`);
  - `/village war declare` declares war at once, and prints where each banner hangs and which way its
    face looks (`BANNER x y z north Oakford`; a banner on a pole by the board adds the board's foot,
    `BOARD x y z`);
  - `/village war cloth` puts a red banner into the stores;
  - `/village war peace` makes peace on the terms the balance of strength gives;
  - `/village war memorial` puts the town's newest war memorial up at once, from the stores, and prints
    where its post stands and which way the sign looks (`MEMORIAL x y z south The war with / Oakford /
    ...`), or `NO-MEMORIAL` and why.

The game tests `WarAndPeaceGameTests` (wp01 to wp10) check that:
* a hawk in a feud with a grievance puts war to the council, going by its scouts' report, and the
  council's vote sends the herald;
* a dove does not go to war, even after ten days of grievances;
* a soft, weak town yields to an ultimatum and the tribute reaches the other treasury, with no war;
* a refusal starts the war on both books under banners made from the stores;
* the weaker side by its scouts' report sues for peace and the treaty concedes the whole goal; the war
  cost no lives, and its memorial names the war itself and goes up from the stores;
* an ally's guards raise a town's strength, peace comes when the cost tells, and the guards go home;
  then a treaty broken is in both chronicles and on the board, and costs the breaker everywhere;
* with wars switched off, nobody goes to war;
* weariness grows with every day of a war, and faster when the war footing costs the town, and shows in
  its contentment and on its folk's cards;
* a weary town with a war going badly elects its peace candidate, who sues for peace at once, while a
  war going well keeps the hawk ahead in a voter's eyes;
* at the peace the militia stands down, the volunteers go back to their trades, the danger pay ends, a
  feast is called, the memorial goes up with the name of the spy the war cost, and Remembrance Day falls
  on the day of the peace a year later.

## Dry land and safe mines

* **Nobody sits in a boat for good.** A boat takes aboard any creature that bumps into it, and a
  creature never gets out by itself. The boat moored at each fisher's jetty used to catch folk this
  way: in one long game, three farmers and a miner sat in two boats below the town for forty days
  and more, and the fields went untended until the food ran out. Now a folk only gets into a boat or a
  minecart when it means to go somewhere in it. One that finds itself aboard anyway, from a world
  saved like that, climbs out within a second and wades ashore. Horses are still ridden as before.
* **Only miners go down the mine.** A folk with nothing to do in its own trade is no longer sent
  to a miner's face to cut stone, since that walked it down the stairs after the nearest rock. It
  fetches timber instead, and its own trade and field are untouched while it helps.
* **Anyone below ground in the mine climbs out.** A folk in the town's mine that is not a miner at
  work is sent up the stairs, mending missing steps as it climbs, or cuts and lays its own steps as
  a player would. It is never lifted out. The town's other rescues, such as being put back on its
  plot or set down by its bed, send a folk below ground in the mine climbing instead.
* **The stair heads are fenced.** The top steps of a mine's stairs are an open trench two or three
  blocks deep. A hand on the town's works fences them round at ground level, leaving the head
  itself open as the way in, and puts a sign on the post beside it reading *The mine of* and the
  town's name. The fence and the sign come out of the stores: a length of fence or two planks each,
  a sign or two planks. A miner cutting new stairs takes down any length that stands in its way,
  and it is put up again round the new ones.
* **Work chests stay at the surface.** A miner's work chest, where the couriers collect its stone and
  ore, is always set down at ground level on the edge of its face, never down below.

Where to see it:
* `/village mine` (and the long game's MINE line) adds two lines. *Below ground in the mine* lists
  who is down there, by name and trade, and whether each is at work or climbing out. *Stairs into
  the mine* gives how many stair heads there are, whether they are all fenced round, and how many
  have the sign up. A third line counts folk who had to get out of a boat or cart they never meant
  to board, if there were any.
* `/village mine showcase` (operators) cuts a short run of mine stairs where you stand, fenced round
  with the sign up, so you can see what the town does at its stair heads.
* A folk's line in `/village folk` shows `aboard=boat` while it sits in one. The long game's tally
  names anyone who is.

The game tests `MineSafetyGameTests` (mf01 to mf05) check that:
* a boat takes a pig aboard, but not a folk that bumps into it;
* a folk already sitting in a boat cannot be moved by a rescue, gets out within two seconds, and is
  not taken aboard again;
* a farmer twenty-four blocks down, at the end of a gallery below stairs with a broken step, climbs
  out and walks back to its field without ever being lifted, and afterwards the books show nobody
  below ground;
* the open top of a mine's stairs is fenced round with the head and the first step left open and the
  sign up, and a farmer whose straight way crosses it walks round without ever going below ground;
* a farmer with nothing to do is lent out for timber, never to a miner's face for stone, keeps its
  trade and field, and is back on its own field once the lend is over;
* a miner at the foot of its stairs sets its work chest down at the surface of its face.

## The watch clears the town

A town's watch used to stand the wall round the square when the bell rang, and between the bells only went after
a monster that came within sixteen blocks of somebody. In a big town that left the streets where the folk live
unwatched on the worst nights, and left creepers, spiders and anything in the shade standing about the town for
days. Now the watch clears the town, and everybody else keeps out of the way.

* **Every monster is the watch's business.** Once a second the town looks for every hostile monster up about it:
  inside its reach or a little past it, in the open, under a roof or a tree, or in the water (not one down a cave
  under the town). The nearest guard who is free goes after each one at a run, by night and by day: the ones
  attacking somebody first, then raiders, then any near a child, then any near anybody, then the rest. A monster
  that lived through the night in the shade is hunted down in the morning.
* **Creepers are shot, not fought.** A creeper goes to a guard with a bow, and any guard of the watch may draw the
  town's bow for it, however new to the job. If no free guard has one, one is armed out of the town's stores.
* **Raiders get two guards each.** A vindicator's axe is more than one guard's match, so the watch pairs up on them.
* **Under the bell.** A third of the watch keeps the posts on the wall with their bows. The rest go out among the
  houses, through the gates, after anything that has come within twenty blocks of the town's folk. The town's
  raiding band now gathers out past its last houses, not in its streets, and a raider goes for a guard near it
  before it goes for a folk.
* **The iron golem helps.** With nothing else to fight it goes for the nearest monster within twenty-odd blocks
  (never a creeper; no golem will).
* **A guard that cannot get at its monster** (on a roof, across water, down a hole) gives up after three quarters
  of a minute, and another guard tries.

**Folk keep out of harm's way.**

* A folk who is not one of the watch, with a monster within ten blocks (sixteen for a child, twelve for a creeper,
  more if it is coming for it), goes indoors. It goes home if that is the nearer way and not past the monster,
  otherwise into the nearest of the town's buildings with a roof and a door, and stays there till the monster has
  gone. A bed out in the open (the founders' camp) doesn't count as cover. It says so: *There's a zombie out
  there. Indoors!*, and a child: *Mum! There's a zombie!*
* A creeper in a hand's yard keeps it from its work until the watch has dealt with it.
* Set on by a monster, a folk runs for it rather than trading blows, and only fights back if it is cornered. Only
  the watch (and a folk hired to fight) answers a shout for help with a fight; everybody else's shout still brings
  the nearest guard.
* With the bell ringing, a folk whose bed is more than thirty-two blocks away goes into the nearest building
  instead of walking across the town to it, children included.
* So that one stuck creeper can't shut a street indoors for days, a grown folk that has waited two minutes for a
  monster that is after nobody goes back to its day and minds that monster no more for half a day. Children keep
  waiting.

**What the books say.**

* A folk who dies while the bell rings is put down as what killed it, with the raid after it, such as *fighting a
  vindicator when the raiders came*. A fall or the lava is a fall or the lava, bell or no bell. Before, any death
  while the bell rang was put down as *when the raiders came*.
* The status (`/village status`, and the town screen) says how many monsters are about the town now, of what kinds,
  and what the watch and the golem have killed this week: *monsters about: 2 (1 zombie, 1 creeper); the watch has
  killed 21 this week and the golem 4*.
* The town's books (the board, `/village stats`) give the week's tally, how many are still about, and who was lost
  to monsters: where they fell (*at home*, *in the street, 40 blocks east of the square*, *in its field*, *on the
  wall*, *down the mine*) and what they were doing (*asleep*, *walking home*, *hunting a zombie*, *making for
  cover*).
* `/village monsters` lists every monster about the town now: what it is, where, whether it is in the shade or the
  water, and which guards are after it. It also gives the tally day by day, how many guards are out on a hunt, how
  many folk are indoors out of the way, and the last dozen folk lost with where and doing what. Operators can use
  `/village monsters now` to have the watch look round the town at once.

The long game (`tools/realworld/soak.py`) now keeps each stop at its time of day, so the dusk raid comes at dusk
and the midnight bed count is at midnight. After each raid it prints a `MONSTERS` line at midnight, the next
morning and the next noon, giving the town's own count of monsters about it and every one of the raid's tagged
monsters anywhere.

The game tests `WatchClearsGameTests` (wc01 to wc05) check that:
* at dusk, four zombies, two skeletons, a spider and a creeper put about a town of five with three guards are all
  killed within three minutes, mostly by the watch, and no folk dies;
* a zombie under a stone canopy at noon, twenty-five blocks from anybody, is counted as about the town, and a guard
  is sent after it and kills it;
* a folk with a zombie seven blocks off goes into a house nearby, stays in while the zombie is there, and comes
  out once it has gone;
* a child goes indoors from a zombie twelve blocks off (a grown folk at the same distance carries on), and is never
  out in the open with it near until it has gone;
* a raiding band gathers at the town's edge; two raiders among the folk get two guards each, and none is sent out to
  the band still at the edge; deaths while the bell rings are put down as what took them, with where and doing what;
  and the status, the books and `/village monsters` count the watch's and the golem's kills.
