# Village Folk

A settlement that runs itself. Nobody hires them, points them at ground, or gives
them a trade: they choose a trade the village is short of, stake ground for it,
work it, stash what they make, eat, sleep, build the village up and — if they are
fed and in work — raise children.

Everything is obtainable in **survival**.

## Getting a village

| How | What you do |
|---|---|
| **Village Folk Spawner** (recommended) | Craft it — 8 bread round a gold ingot — and *place* it. The first one founds a village of eight on the spot (the same start a village the world grows gets) and leaves the founding stores in a chest where it stood; each one placed after that, within reach, adds one settler. That is the last thing a village needs from you. |
| **Village Charter** | Right-click the ground. Same result as the spawner; craftable with paper, bread, a gold ingot, seeds and a chest. |
| **Where to find them** | In creative, everything the mod adds is in its own **Village Folk** tab (the spawner is also under Functional Blocks). In survival, every recipe is in the recipe book from the moment you join; the spawner is a gold ingot in the middle of the crafting grid with bread in all eight squares round it. |
| **/village spawn [1-100]** | Stands folk up two blocks ahead of you. `spawnat <x> <z> [n]` for the console. |
| **The world** | Villages generate as you explore (config `naturalVillages`), in groups of three to five. |
| **Vanilla villagers** | Turned into folk as you meet them (config `replaceVillagers`). Trading with them stops working; wandering traders are untouched. |

## From the first spawner to the Nether Age

Place a spawner and walk away. Nothing below needs you: no commands, no orders, no
chests to fill, no player nearby (a village keeps its own ground loaded, and the
real-terrain tests run on a server with nobody on it). Everything happens on its
own, and each step is there for a reason the village can see.

1. **Founding.** Eight folk stand up where the spawner stood and found the village,
   with a chest of founding stores: bread, seed, carrots and potatoes, saplings, torches,
   planks, cobblestone, four chests, string. Every folk carries rations, stone tools,
   a bench and a chest of its own.
2. **Trades and ground.** Each takes the trade the village is shortest of (farmers
   first, then miners, woodcutters, a smelter; a watch, a carrier, a storekeeper, a
   rancher and a fisher as it grows), stakes ground that suits it, sets its chest down
   and works.
3. **The storehouse, first.** For its first half hour the founding planks, stone and
   chests are kept for it. It goes up within minutes: four chests under one roof.
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
    village of their own that climbs the ages from the start — at most one every two
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
  | North and south of the square | The storehouse, the market, the workshop and the smeltery | Onto the square |
  | East and west of the square, two lots deep | The meeting hall, the chapel and the barracks | Onto the square |
  | The four corners by the square | The watchtowers | — |
  | Everywhere else | Homes, in rows back to back | Each row onto its own street |
  | Out at the edge | The lighthouse, the pen and the gateway | — |

The streets are worn into paths where people live. From the Iron Age the avenues
are cobbled and lit by lamp posts every few blocks. Fields, woods and mines are
staked outside the town, so it always has ground to grow into.

### Life in town

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

* **Coin.** A *Village Coin* is a gold coin. A new village's treasury starts with
  32. From the Iron Age the village mints more from the gold in its stores, nine
  coins to an ingot, whenever the treasury runs low.
* **Wages.** Every morning the treasury pays each working folk 1 coin, 2 from
  level 10 and 3 from level 25, for as long as the coin lasts. Folk save what
  they earn and will tell you how much they have put by.
* **Market day.** Once a week, on a different day for every village, the bell
  rings on the square in the morning and players nearby are told. On their break
  that day, folk go to the stalls and buy a treat with their savings: their
  favourite food if the stores have it. The coin goes back into the treasury.
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
  * The carrier leads a pack llama in the village's colours and carries what the
    stores have more than plenty of, the colony's needs first.
  * It walks the road to the colony and unloads into the colony's stores. Then it
    loads what the colony can spare that the mother village is short of, and
    walks home.
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
| Cook | Stone Age, 14 folk | the café | Bakes potatoes, roasts meat and fish, bakes bread, cookies, pumpkin pie and cakes, and makes drinks |
| Tailor | Stone Age, 18 folk | the workshop | Makes beds (in the colour of the wool), rugs, string, and banners on its loom |
| Beekeeper | Stone Age, 20 folk | a meadow outside town | Keeps up to four hives: comb with shears or honey with a bottle from a full hive, new hives from comb, bees bred on flowers |
| Blacksmith | Iron Age, 16 folk | the smithy | Makes iron picks for the miners, swords and armour for the watch, shears, buckets, axes and hoes; bows, and arrows of flint, stick and feather |
| Shopkeeper | Iron Age, 18 folk | the shop | Sets out what the crafts have made on the shop's counter |
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

**The links between trades.**

* **Farmers.** Plant the cane along the field's water and cut it back to its
  bottom so it grows again. The cane goes to the enchanter's paper, the brewer's
  sugar and the café's pies and cakes. They also plant the melon and pumpkin seed.
* **Ranchers.** Shear the sheep: the wool is the village's beds. They breed sheep
  before cows, and a pen with no sheep fetches a wild one even when it has a pair of cows.
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
there and then. While folk sleep on the ground the tailor makes beds before rugs and
banners, the quest board asks for wool, and the elder may order the herds grown.

**How the village keeps its balance.**

* **Gluts.** A trade whose stores are piled far past any use gets a smaller share of the
  village. With food over four larders the farms are halved (over two, cut by a quarter),
  and logs and stone likewise. The mines are never cut while the age is short of iron or
  diamonds. Hands a trade can spare go to whichever trade is a hand and a half short.
* **Iron first.** An iron vein never uses up a miner's vein budget, and is dug before
  any other ore it finds. The smelter fires ore before sand, and fetches the raw iron
  the carriers have brought to the stores.
* **Idle hands help the builder.** A folk whose trade has nothing to do, and that can
  fetch nothing the village is short of, goes and helps whoever is raising the village's
  building. Each helper (up to three) makes the blocks go down a tick faster.
* **Market day sells the surplus.** Travelling traders buy logs, cobblestone and food
  far over a reserve (up to three stacks each) for coin into the treasury.
* **The stores grow.** When every store chest is full, another is set down beside them,
  made of the stores' own planks.

**Tools, potions and clothes.**

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
| Storehouse | 7×9 | Log-framed store with a gabled front, four chests and barrels |
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

Builders take the place the town plan has for the building. Of the first few good
lots they pick the one that costs least to build on. They level it (filling the low
side, felling any tree in the way and keeping the wood) and build from what they
actually carry.

`/village showcase buildings` (an operator command) sets every building out on a
stage to be looked at. `/village showcase town` lays out a whole grown town to the
plan.

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

### Growing up, growing old

* **Apprentices.** From its second day a child spends its mornings at a grown-up's
  side at work, watching and having a go. The teacher is a parent at work, or else the
  most practised hand at the trade the village needs most. When the child grows up it
  takes up that trade (unless the village has more than enough hands at it) with a few
  levels' knack already. The village's history records who taught whom.
* **Age.**
  * A child is grown at eighteen, three days after it is born.
  * Grown folk age two years a day. The founders were grown when the village began.
  * From sixty folk are old: they walk a little slower and work a little slower.
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
* what it just said.

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
| Trade? | A bundle of what its work makes, for an emerald (see below) |
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
  stores, for an emerald. When the village is short of something, it will take some
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
and a lone folk founding one.

**Levelling the town.** Every day the village levels a little more of its town to the
height of the square: knolls cut down, hollows filled, so houses and streets stand true.
Only earth, sand, gravel and plain stone are moved. Nothing anybody built, no field, no
tree, nothing next to water, and never more than six blocks up or down, so a hill stays a
hill and just stops at the edge of town. Rock that is cut goes into the stores as
cobblestone. When the whole town is level, the history says so.

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

**Earning coin.** There are three ways:
* **Sell to the village.** Right-click a market stall with what the village is buying
  (the stall's sign says what). It pays from the treasury, and what you sell goes into
  the stores.
* **Do its errands.** Errands for the village are paid in coin as well as goods.
* **Answer the quest board.** See below.

Passing traders buy a little of what every village makes each day, so the treasury never
runs quite dry.

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

Every alliance, feud, truce and tribute goes into both villages' history.
`/village relations` lists every pair of neighbours and how they stand, and
`/village status` gives the council, the citizens and the neighbours.

## What they do

* **Trades** — at ten folk: four farmers, three miners, two woodcutters, one
  smelter. More trades open as the village grows: a watch, a carrier, a
  storekeeper, a rancher, a fisher.
* **Ground** — each trade claims its own plot (farms by water, woods, hillsides
  to dig) and works it. Plots are never shared.
* **Stores** — every chest and furnace a folk puts down is named **Village
  Store**. Folk use *only* containers with that name, so a village founded next
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
* **Carriers** — a carrier's round is chosen, not set with the wand. Every
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

## Commands

* `/village list` — every village the game knows of: where, how many live
  there, what age, what has been built. Works from the console.
* `/village status` — age, headcount, trades, what the stores hold, what has
  been built, what the village is short of. Works from the console.
* **The village journal (J key)** — the same, for the village you stand in, on a page
  you can scroll, a line to each part. Rebind it under Controls ("Village journal").
* `/village news on` / `off` — the morning news of the villages within 256 blocks of
  you, in chat, once a morning: what happened yesterday and what each is short of.
  Off unless you turn it on; the choice is kept with you.
* `/village people` — who everybody is: trade, temperament, partner, friends,
  rivals and family, under a line on the village's couples and friendships.
* `/village chronicle` — the nearest village's history, as a written book.
* `/village standing` — what every village you have met thinks of you.
* `/village ledger` — the nearest village's town ledger, as a book.
* `/village relations` — every pair of neighbouring villages: allies, friends, uneasy,
  in a feud, how far apart, and whether they are kin.
* `/village talk [words]` — (operators) talk with the nearest folk, as a right-click
  would; with words, say them to it.
* `/village lineup` — (operators) one folk of every trade, dressed and holding
  its tool, stood in a row in front of you to be looked at.
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
  commands work, a vanilla villager is swapped for a folk, and twelve folk are
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
