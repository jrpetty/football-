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
   the village raises an obsidian **gateway** (never lit).
10. **And then it never stops.** A village that has come through every age raises
    **great works** — a granary, barracks, a monument, round and round, each on new
    ground — and every one asks the stores for a quarter more food, stone and iron
    than the last. `/village status` shows its **renown** (great works raised).
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
    evening at its pastime, the village coming of age.
  * Down: hunger, sleeping rough, loneliness, rain (unless it likes rain), work it
    can't do, being hit, a hungry village.
  * A happy folk works a little quicker, and a miserable one slower.
* **Memories** — its children born, the ages it saw the village come into, a friend
  lost, a present from you, the day you hit it.
* **What it thinks of you** — each player separately.

The village keeps its own **news**: who is together now, who had a child, what went
up, who died, whose dream came true. Folk pass it on.

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
* `/village people` — who everybody is: trade, temperament, partner, friends,
  rivals and family, under a line on the village's couples and friendships.
* `/village chronicle` — the nearest village's history, as a written book.
* `/village standing` — what every village you have met thinks of you.
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
