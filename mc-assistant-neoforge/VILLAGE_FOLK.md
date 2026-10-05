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
| **Village Charter** | Right-click the ground. Founds a village of eight on the spot at once (the same start a village the world grows gets), without levelling anything; craftable with paper, bread, a gold ingot, seeds and a chest. |
| **Where to find them** | In creative, everything the mod adds is in its own **Village Folk** tab (the spawner is also under Functional Blocks). In survival, every recipe is in the recipe book from the moment you join; the spawner is a gold ingot in the middle of the crafting grid with bread in all eight squares round it. |
| **/village spawn [1-100]** | Stands folk up two blocks ahead of you. `spawnat <x> <z> [n]` for the console. |
| **The world** | Villages generate as you explore (config `naturalVillages`), in groups of three to five. |
| **Vanilla villagers** | Turned into folk as you meet them (config `replaceVillagers`). Trading with them stops working; wandering traders are untouched. |

## Founding a village: choose how many

**Place the spawner, then go to the board.** A Village Folk Spawner set down where there is no
village puts the **village board** up on the spot, on the edge of what will be the square and on
the far side of it from you, so it faces you. Nobody comes yet. The board says what is about to
happen, and chat says: *Go to the village board to choose how many folk start the village.*

**Right-click the board** and the *Found a village* screen opens:

* **How many**: a slider from 2 to 500 (it moves by ratio, so two to twelve is not squeezed into
  the first pixel), a − and a + beside it (with Shift, ten at a time), and the usual sizes to pick
  at a click: 2, 8, 12, 25, 50, 100, 250, 500. It starts at eight. A server lets a village be no
  bigger than its growth cap (`villageGrowthCap`, 100 unless it has been raised to 500), and when
  the cap is lower than five hundred the screen says so and goes no higher.
* **What that means**, worked out from the count as you move it: how much ground is made level,
  the trades that many settle into (the biggest first), the houses they will want before they
  raise children and how many beds the camp has, and what so many folk cost a server every tick,
  amber from about a hundred and red from three hundred (*hundreds of folk are heavy for a
  server: every one is a ticking creature*). If the world would hold more folk than it may
  (`villageWorldCap`), it says that too: past it no village raises a child.
* **Confirm and spawn** starts the founding. **Cancel** only closes the screen; the board goes on
  waiting. The server checks the choice again: you must be standing by the board (24 blocks), the
  board must still be waiting, and the count must be 2 to 500; a count over the growth cap is
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
  stand in a pit behind its banks; dry ground well under the sea's height keeps its own). Hills and knolls are cut
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
six), and the bedding of those the camp has no room for (it lays two dozen beds) in chests of its
own, up to two, for the first houses.

**It goes on without you.** The work is done a little every tick: at most 4,096 blocks or eight
milliseconds, 8,192 columns read while the ground is walked, eight folk. It keeps the ground it is
working loaded (six chunks asked for at a time, the rest held while it works), so you can walk
away, and it is kept with the world: after a restart it walks the ground again and carries on from
where it had got to. Anybody within 160 blocks sees how it is going on the action bar, the board
says it while it waits, and the village's own board says it once the village is founded. When it
is done, the first of them says so, and the village's history remembers it.

A spawner placed within reach of a village adds one settler, as it always has. `/village
spawnat` still stands a party up at once with no levelling (the soak tests use it), and so does
the Village Charter.

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
    than the last. `/village status` shows its **renown** (great works raised).
    **Rank** goes on past the ages:

    | Rank | Needs |
    |---|---|
    | Village | the Stone Age and 12 folk |
    | Town | the Iron Age and 30 folk |
    | City | the Diamond Age, two great works and 50 folk |
    | Capital | the Nether Age, six great works, 80 folk and two colonies of its own |

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

**Pay** is the trade's rate (a coin a day for the fields, the woods, the water and the
stores; two for miners, guards, smelters, ranchers, cooks, shopkeepers, beekeepers, scouts
and hunters; three for the smith, the tailor, the brewer and the enchanter) **times what the
place is**: a hamlet pays the rate, a village half as much again, a town twice, a city two
and a half times, a capital three. On top: a coin at level ten and another at twenty-five,
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
  * **Pay for what you make.** On top of its trade's rate, every folk is paid a quarter of
    what it made yesterday (up to twice its rate): the hardest workers are the best paid,
    and the Wages page says so ("+3 for what it made yesterday").
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
  * **Three meals a day, for everybody.** Every folk eats **breakfast** (from six in the
    morning), **the midday meal** (from half past eleven) and **supper** (from five in the
    evening): the children, the old, the leader and folk between trades as well as the hands at
    their work. At each mealtime it eats out of its own pack first, then its household's chest at
    home, then the village's stores (the town feeds its own; a far hand at its work has its rations
    sent out instead), always a real loaf, fish or stew out of somewhere, booked in the books. A
    ration eaten at its work while the mealtime is on is that meal: nobody eats twice. With nothing
    in reach it **misses the meal** and is **hungry** (its contentment falls, more for every meal
    missed, and it says so); a whole day without and it can't work properly; two days and it grows
    weak (it loses health, never past three hearts). Its card says when it last ate and what, and
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
    carrier. Then it loads what the colony can spare that the mother village is short of —
    never what it just brought — which the mother buys the same way when it gets home, and
    walks home with the coin.
  * Players nearby are told when a caravan sets out, and both towns record each
    delivery in their history.
  * You can meet a caravan on the road. Ask the carrier what it is doing and it
    will tell you where it is headed; ask to trade and it will offer what it is
    carrying.

### The watch, the gates and raids

Once a village has its wall, it sees to its own safety.

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

### The crafts, the café and the shop

As a village grows, some of the newcomers take up a craft instead of the fields. Each
craft wants an age and a headcount, and the village keeps only one or two of each
however big it gets. Nobody is taken off the farms to fill one; crafts go to folk
born or grown up into a big enough village.

| Trade | From | Works at | What it does |
|---|---|---|---|
| Cook | Stone Age, 14 folk | the café | Bakes potatoes, roasts meat and fish, bakes bread, cookies, pumpkin pie and cakes, and makes drinks for the café and the tavern, more of what sells |
| Tailor | Stone Age, 18 folk | the workshop | Makes beds (in the colour of the wool), rugs, string, and banners on its loom |
| Beekeeper | Stone Age, 20 folk | a meadow outside town | Keeps up to four hives: comb with shears or honey with a bottle from a full hive, new hives from comb, bees bred on flowers |
| Blacksmith | Iron Age, 16 folk | the smithy | Makes iron picks for the miners, swords and armour for the watch, shears, buckets, axes and hoes; bows, and arrows of flint, stick and feather |
| Shopkeeper | Iron Age, 18 folk | the shop | Makes what a house wants at its bench, the whole way from the stores (logs to planks to sticks to a pick), more of what sells, and sets it out on the counter with what the crafts have made |
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
  town side. It is the folk's own chest, or one from the stores, or one made of the stores'
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
* **The watch's iron.** Guards put on the smith's iron armour and take its swords.
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
| Workshop | 9×9 | Timber workroom with a wide door, two benches, furnace, barrels and a hayloft |
| Granary | 7×7 | Squat store on a stone base, full of hay, three chests, hipped roof |
| Market | 11×11 | Open hall on log posts under a broad hipped roof, stalls of hay and barrels, a fountain |
| Meeting hall | 11×19 | Long timber hall: tall windows, double doors up steps, a long table, the elder's seat |
| Chapel | 9×21 | Stone nave with tall windows, a bell tower over the door, pews, an altar and lights |
| Barracks | 9×15 | Stone-and-timber dormitory with six bunks, chests, a bench and an anvil |
| Watchtower | 7×7 | Stone tower three storeys high, ladder inside, battlemented deck with a lookout roof |
| Lighthouse | 7×7 | Tall banded stone tower, ladder all the way up, glass lamp room, pointed roof |
| Monument | 7×7 | Stepped plinth, banded pillar, lanterns at the corners and on top |
| Gateway | 9×5 | The Nether Age's obsidian frame on a stone dais between lantern pillars |
| Wall | ring of 27 | Round the square: battlements, a gate onto each avenue, lantern pillars, corner towers |
| Pen | 7×7 | Fence ring with a gate, once there is a rancher |
| Tavern | 11×11 | Broad timber inn: stone hearth with its fire and chimney, a bar of casks, tables and benches, note blocks, lanterns |
| Graveyard | 9×9 | Fenced plot with a gate, a path to a stone cross, lanterns on the corner posts, twelve graves |
| House, grown | 9×9 | The family house with a second storey: a ladder up to two more beds and a chest under the eaves |
| Café | 9×9 | Bright timber room with big windows on the street, a counter of casks, a smoker behind it, little tables and chairs, flowers by the door |
| Shop | 9×9 | Timber shopfront with a window either side of the door, a counter of casks with the goods on it, shelves of barrels behind |
| Smithy | 9×9 | Stone forge open to the street between log pillars: two furnaces under a brick hood, the anvil, a grindstone, a quenching tub, a bench and chests |
| Brewery | 9×9 | Timber still-house on a stone footing: two brewing stands on a stone bench, cauldrons, casks, a window to the street |
| Library | 9×9 | Stone hall with tall windows, walls lined with bookshelves, an enchanting table on a carpet between lecterns |
| School | 9×11 | Timber schoolroom under a steep roof, tall windows down both sides: two rows of desks (a top slab, a stair for a bench) either side of the aisle, the teacher's lectern, and at the back a blackboard over a cupboard of barrels, a bookshelf either side |
| Bank | 9×11 | Stone house of business: a counter across the room with a lantern on it, the ledger on a lectern by the door, and a vault at the back, its strongboxes behind a gate and grille of iron bars the banker sets |
| Park | 11×11 | A green: a stone fountain with a spring spilling from its pillar, sixteen wooden benches round it, lamp posts at the four ways in, flowers along its edges; then a tree in each corner, paths and lanterns (see *The town's quarters, and the park*) |

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
* **Age.**
  * A child is grown at eighteen, three days after it is born.
  * Grown folk age two years a day. The founders were grown when the village began.
  * From sixty folk are old, and go on working their trade all their days, as quick as ever
    (see *Getting quicker*); age is no mark against them at an election either.
  * Each folk lives to between seventy and a hundred.
  * A few years before the end, the village hears that they are very frail. At the
    end of their years they die peacefully in their sleep.
  * Ask a folk about itself and it tells you its age. The register gives everyone's.
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

* **The bell** is the town's own: a bell it already has — the bell tower's, one by the leader's
  hall or the board, the alarm bell on the square, the chapel's, the bell of a village its folk
  moved into — the nearest to where a town bell belongs (before the leader's hall, else the board,
  else the heart). Nobody in a village can make a bell: **put one in the stores** (found in a
  village, bought from a villager or brought) and the town's works hang it on a plinth of stone
  out of the stores, before the leader's hall once it stands, else on the square where the watch
  hangs its alarm bell. Till there is one, the ringer **calls the hours at the board**, as a town
  crier would.
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

**Birthdays.** Folk count their years two to the day (six as children), so a birthday by their count
comes twice a day. What they keep are the **round ones**: the day a folk's years pass into a new
ten — a child's when it comes into double figures (its second day), then twenty, thirty, forty and
on, every five days. The day comes from when it was born, or for one who came to the village grown,
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
| Fill the larder | farmers, fishers | the larder is low |
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

### The leader's hall and the courtyard

Once the village has a meeting hall and a board, its builders lay **the courtyard** before the
board: paved in dressed stone with a cross of it through the middle, benches down both sides,
flowers at either end of the board and a lamp post at each front corner, the middle kept clear for
the crowd. It is where the village gathers: the morning assembly, the count at an election,
celebrations, and weddings (the couple at the board, the village down the aisle between the
benches).

Once the town is sixteen strong it builds **the leader's hall**, the best and biggest building in
it (1,557 blocks, 24 high), on the great lot behind the board, which is kept for it:
* below, a great hall of dressed stone: tall windows between stone piers, benches either side of
  a red runner up to a dais, the leader's seat under a great window with lamps either side, the
  clerks' lecterns, and walls of books;
* above, a timber storey: the leader's family's rooms at the back (a bed for two, the children's
  beds at the other end of the room) and the council chamber at the front, with its long table;
* over the door, a stone tower four storeys high with the leader's study in it and an open
  lantern-room at the top, seen from the fields.

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
  and its longest run without a loss); everything all told (born, died, came, left, made,
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
| 25 | diamond tools, with the diamonds to hand (a pick for the miners and a blade for the watch, unless the age is saving its diamonds) | banners with a border woven in | the third rank; netherite things |
| 30 | diamond armour (to order) | | its work bound to last (Unbreaking one better) |
| 40 | netherite has its rung here, though no recipe the folk use makes it yet | banners with a stripe as well | |

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
stone house of business with a counter across the room and a lantern on it, a lectern by the door
where the bank's ledger lies open for anybody to read, and at the back the vault, its strongboxes
along the wall behind a barred gate and grille. Until it stands, nothing changes. The day it does
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
* an offer of **trade**: a pact, after which **trade caravans** run both ways every
  few days. The goods are paid for in coin, and each trip warms the two villages a
  little;
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

* **Trades** — at ten folk: four farmers, three miners, two woodcutters, one
  smelter. More trades open as the village grows: a watch, a carrier, a
  storekeeper, a rancher, a fisher.
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
  works for ever. A lot is dry, within twelve blocks of the ground at the heart and
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
* `/village found <count> [x z]` — (operators) found a village of that many (2 to 500)
  where you stand or at x z, exactly as the founding screen's **Confirm and spawn** does: on
  the board waiting there, or on one put up for it. `found board [x z]` puts the board up
  alone, as placing a spawner does; `found screen [count]` opens the founding screen of the
  waiting board nearest you; `found status` says how every founding is getting on.
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
  (operators) puts a bank up ten blocks in front of you, opens it and sets its banker at the
  counter, with the bars and the ledger in (for the screenshots).
* `/village stats [page]` — the town's books on the analytics screen (as clicking the village
  board does), opened at a page if one is given (0 the Overview to 18 the Board; 12 is the Stock,
  13 Research); from the console, the reading of what drives the village's growth.
* `/village school` — the nearest village's school: the schoolhouse, the teacher (why, its pay,
  how far a schooling under it goes), this morning, every child with the trade it leans to, its
  level so far and its mornings, and who has left school. Works from the console. `school page`
  opens the town's books at the School page; `school lesson` (operators) calls a lesson now,
  whatever the hour, for two minutes; `school say` (operators) has the nearest teacher say a
  line of the lesson; `school stage` (operators) sets a schoolhouse out on a stage mid-lesson for
  the pictures (`/kill @e[tag=folk_lineup]` clears its folk).
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
  rang them and how many answered. `bell ring dawn|noon|dusk` (operators) has it rung now, by
  whoever would ring it, and the town answers it.
* `/village founding` — when the town was founded and its next Founding Day; `founding now`
  (operators) keeps it this minute, before the board.
* `/village birthdays` — whose birthday falls this week; `birthdays now <name>` (operators) has
  that folk keep one today, and its friends go round with presents.
* `/village districts` — the nearest town's quarters: the plan in a line, how many buildings
  each quarter has, which works are at work, the homes in the smoke and din and the homes by the
  park, and how the park is coming on. Works from the console. `districts map` opens the books at
  the Buildings page's map; `districts park now` (operators) puts the park up at once on its lot,
  as the showcase does, with its trees grown and its paths laid, and sends everybody off work to
  it (it prints `PARK x y z facing dir`); `districts park visit` (operators) sends them again.
* `/village chronicle` — the nearest village's history, as a written book.
* `/village standing` — what every village you have met thinks of you.
* `/village ledger` — the nearest village's town ledger, as a book.
* `/village relations` — every pair of neighbouring villages: allies, friends, uneasy,
  in a feud, how far apart, and whether they are kin.
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

`naturalVillages`, `villageSpacing`, `villageMinFolk`, `villageMaxFolk`,
`villageBreeding`, `villageGrowthCap`, `villageLoadedChunks`, `replaceVillagers`,
`protectTradedVillagers`, `villageColonies` (on), `villageColonyAt` (40),
`villageWorldCap` (200).

* `villageGrowthCap` (100) is the largest a village grows by raising children.
* `villageBuildSpeed` (100) is how fast village builders lay blocks, as a percentage:
  200 is twice as fast, 50 half.
* `villageReshapeLand` (on): off keeps your terrain as it is. The town's ground isn't
  levelled, no sand is dug and no irrigation channels are cut. Buildings still get the
  footings they need.
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
* keeps the town's calendar (`BellGameTests`, b01 to b03): a town of six with a bell lies in before
  the dawn bell, is rung up by a ringer who walks to the bell (three strokes, the bell swinging) and
  goes to work; at the noon bell (six) most of it goes to its midday meal and eats; at the dusk bell
  (nine) the day's work stops, the hands go home to their beds and the guard goes on watch; on a
  folk's fortieth birthday its friend walks round with the flower from its own pack, which goes from
  the one pack to the other as a keepsake, and the birthday and the present raise its spirits; and
  twenty-eight days after the founding the town gathers for Founding Day, hears the year's chronicle
  read out in the order it happened, feasts, and the history notes its first year kept;
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
