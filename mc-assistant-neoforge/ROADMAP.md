# MC Assistant — where this is going

## Now: Assistants (built)

A player hires individual specialists. Each one is **directed by you**: you pick
its job, mark its patch, hand it its tools, and it works that job unattended
until it needs something. Nine jobs — farmer, lumberjack, miner, rancher, guard,
smelter, fisher, storekeeper, hauler — plus beds and shifts, veteran levels and
branches, a work record, and revival by Memory Core.

The defining constraint: **an assistant is a tool you aim.** It does not decide
what the base needs. That is deliberate, and it stays that way.

---

## Later: Village Folk (design note — NOT built)

A **separate unit type**, not a change to assistants. Village Folk are the same
underlying skill set, but organised as a settlement that runs itself.

### What makes them different

| | Assistant | Village Folk |
|---|---|---|
| Who decides the job | the player | the village |
| Role | fixed until you change it | switches as the village's needs change |
| Purpose | do the task you assigned | keep the settlement alive and growing |
| Zone | you mark it | the village plans and claims its own |

### The shape of it

- **A leader** — foreman / mayor. Reads what the settlement is short of and
  assigns roles accordingly: three on food when stores are low, two on iron when
  the smithy is dry. Roles are reassigned as conditions change rather than fixed.
- **Role switching** — a villager is not "a farmer"; it is someone currently
  farming. It carries every skill and takes whichever the leader needs.
- **Supply chains** — the miner's ore reaches the smelter's input chest; the
  smelter's ingots reach the store. Output is routed, not stranded.
- **A request queue** — the smelter is out of fuel and says so; the next
  lumberjack delivery is routed to it. Needs are published, not guessed at.
- **Priority under scarcity** — when upkeep only covers four of six, the
  important work continues instead of everyone stalling together.
- **They build it themselves** — houses, stores, walls, roads. The village
  decides its own layout and grows into it.
- **They gather for themselves** — their own resource targets, not the player's.

### The end state

A settlement that is genuinely self-sustaining: it feeds itself, defends itself,
decides what to build, and grows without instruction. The player is a neighbour
and a trading partner, not an operator.

### Why keep them separate

The two answer different fantasies. Assistants are **your crew** — precise,
directed, accountable to you. Village Folk are **a place that lives** — you
influence it, you don't run it. Merging them would make assistants unpredictable
(they'd wander off to do what "the village" wanted) and make the village feel
micromanaged. Separate units, shared skill code underneath.

### Order of work, when we get to it

1. Supply chains between assistants — routing output to the right input chest.
   Valuable on its own, and the foundation everything else sits on.
2. The request queue — publishing needs so another worker can satisfy them.
3. The leader — reading the settlement's state and assigning roles.
4. Priority under scarcity.
5. Self-directed building and layout.

Steps 1 and 2 are worth building for assistants regardless; 3 onward is what
turns them into Village Folk.

---

## Fifty upgrades for the towns and their people (b275 onward)

Seven batches, each built in its own worktree and merged one at a time. Everything follows the
house rules: folk speak through FolkTalk, nothing comes from nothing, and every addition shows
somewhere a player can see it.

### A. Health and care
1. **Colds and recovery.** Folk caught in the rain or worked to exhaustion can catch a cold.
   It may spread to housemates. A sick folk rests at home and works slowly for a day or two.
2. **The infirmary** (Stone Age, 20 folk). Beds, a brewing stand and a cauldron. The wounded and
   the sick go there and mend twice as fast.
3. **The healer.** A trade that tends the infirmary's patients and visits the bedridden at home,
   using honey, golden carrots and the brewer's potions from the stores.
4. **Neighbours look after the old.** The very old and frail get a daily visit and a meal carried
   in from the stores.
5. **The poor box.** Well-off folk drop coins in the chapel's poor box. The poor get help with
   rent and bread from it.
6. **Housewarming.** Neighbours and friends call on a family's new house that evening with a
   small gift.
7. **The welcome committee.** A newcomer is greeted, walked round the town and given a welcome
   basket from the stores.

### B. Seasons and festivals
8. **Seasons.** The town's 28-day year is split into four seasons. Tended crops grow faster in
   spring and summer and slower in winter. The season shows on the board, in the books and in
   the crier's news.
9. **The maypole and the May dance** on the first rest day of spring.
10. **The midsummer bonfire** on the square at dusk, with singing.
11. **The harvest festival.** The harvest is brought to the square for a long-table feast, with
    a prize for the best crop.
12. **Midwinter lanterns and gifts.** Lanterns line the avenue and friends and families exchange
    gifts.
13. **Winter in town.** Children build snowmen and folk wrap up warm.
14. **The town fair.** Competitions for the best bread, wool, fish and honey, judged by the
    elder. Players can enter too and win a ribbon and a purse.

### C. Sport and play
15. **The football pitch** (20 folk, Stone Age), with goals and lines.
16. **Football on rest days.** Two teams from the town's quarters, a ball, goals, a crowd, and
    the score in the chronicle.
17. **The league and the cup.** A season table, with the champions' cup on show in the hall.
18. **Friendly matches between towns** on good terms. The visiting team walks over with its
    supporters.
19. **The fishing contest** on summer rest days.
20. **Children's sports day**, with races in the park and prizes.
21. **The archery range.** The watch practises at targets and holds a contest.

### D. Culture and identity
22. **The town banner**, its colours drawn from the land and the leader. The tailor makes it and
    it flies from the hall, the gates and the market.
23. **The town motto**, carved over the hall's door.
24. **Traditions.** The town's great days (a storm weathered, a raid beaten off, the first
    diamond) become yearly customs.
25. **The theatre** (Iron Age). Plays from the chronicle on rest-day evenings.
26. **The band and the choir**, at the tavern, weddings, festivals and the chapel.
27. **Paintings.** Folk with an artist's hobby paint, and the well-off hang paintings at home
    and in public buildings.
28. **Plaques** at notable places: the founding spot, the first house, a hero's last stand.

### E. The town's look
29. **Tree-lined avenues.**
30. **Street furniture.** Benches at corners, flower boxes under windows and notice boards.
31. **Allotments** for folk without a garden.
32. **The orchard.** Apples for the café.
33. **The windmill** by the fields.
34. **The bakery.** Bread, cookies, pies and cakes from the stores, for the café, the shop and
    feasts.
35. **The inn.** Rooms for travellers, caravan drivers, envoys, visiting teams and players.

### F. Town life and governance
36. **The post office and letters.** Letters travel between towns, and a player can post a
    letter to a folk and get a reply.
37. **Petitions.** A grievance on the board gathers signatures, and the council takes it up.
38. **The town meeting.** Every week the elder gives a report and answers questions.
39. **Quarter wardens.** An evening round of each district, reporting what needs fixing and
    settling small quarrels.
40. **The public works fund.** Donations go towards a monument, with the donors named on a
    plaque.
41. **Search parties** for a folk not seen all day.
42. **Neighbourly favours.** Folk lend tools and help each other carry, and friendships grow
    from it.

### G. Visitors and the player
43. **The travelling bard.** Visits a few nights, plays at the tavern and brings news of other
    towns.
44. **Tourists.** A town of renown draws visitors who stay at the inn and spend at the shop and
    café.
45. **Advancements.** Game toasts for founding, town size, ages, citizenship, the cup, the fair
    and letters.
46. **The map room.** The hall's map wall, kept up to date by the clerk.
47. **Watch dogs.** Guards keep a dog that patrols with them and barks at monsters.
48. **Travelling merchants** on market day, with exotic goods.
49. **Friends from other towns** come to visit.
50. **Gifts kept.** A folk displays a player's gifts at home.

## War between towns (b277 onward)

Built by four helpers in parallel on the shared seams `Wars` (who is at war, a town's footing), `Intel`
(scouts' dated reports) and `WarFooting` (who fights). Config `villageWars` (on).

### Why towns go to war (war and peace)
1. Feuds that boil over: land quarrels, broken deals, stolen goods and insults drive relations to war.
2. Hawks and doves: the leader's temper and values decide whether a feud becomes a war.
3. The war council: the leader puts war to the council in the hall; folk vote by their values.
4. War goals: a border, tribute, a trade deal on our terms, revenge, or freeing a colony.
5. The ultimatum: a herald carries the demands; the other town gives in, bargains or refuses.
6. Declaration day: the bell, the war banner over the walls, the chronicle, neighbours take sides.

### Scouts and intelligence
7. Scouting the enemy: guards, armour, walls, gates, food stores and weapons counted.
8. Intelligence reports: dated, brought to the war council, stale as the days pass.
9. Strength reckoning: our strength against theirs from the latest report; no scout, only rumour.
10. Fog of war: without fresh intelligence the leader guesses, boldly or timidly by temper.
11. Catching spies: the watch spots enemy scouts; caught spies held, questioned, traded at peace.
12. Pickets and watchtowers on the roads give early warning of a war band.
13. The war map in the map room: the enemy town, its last known strength, war bands seen.
14. Deception: a shrewd leader keeps its barracks out of sight; enemy reports can be wrong.

### Getting ready (the war footing)
15. The town changes how it works: more guards, smiths on weapons, miners on iron, couriers for the army.
16. The militia: every able folk drills on rest days and is called up in war.
17. Recruiting: guards' pay rises with the danger; volunteers by their values.
18. Fortifications: palisade, stone walls, corner towers, a gatehouse, a ditch.
19. The armoury: weapons and armour kept and issued; the smiths fill it.
20. Siege stores: the leader's plan becomes WAR: food, arrows and bandages put by.
21. Rationing: meals cut to stretch the stores, luxuries stopped.
22. Curfew and blackout: lanterns out, gates shut at dusk, strangers stopped.
23. Shelter plan: children and the old to the hall's cellar when the bell rings.
24. The training yard: guards train at targets and dummies; skill rises.
25. Cavalry: the stables mount riders for scouting and raids.
26. Calling the allies: allied towns send guards.

### The war economy
27. The war chest and a war tax on wages.
28. War bonds: the well-off lend the town coin at interest.
29. War prices: iron, weapons, armour and food dearer; luxuries cheaper.
30. Blockade: caravans stopped or escorted, trade deals paused.
31. The arms trade: neutral towns and the player sell weapons to both sides.
32. Mercenaries hired from other towns' job markets.
33. Requisition: goods taken for the war, paid in IOUs honoured after peace.
34. The cost counted: coin, goods, lives and trade lost, on the war page.

### Fighting
35. The war band: guards and militia muster at the gate, armed and fed, under a captain.
36. Raids on outlying farms, mines and herds: goods and animals carried off, no building broken.
37. Battles in the field with swords, bows, shields and armour.
38. Yield and surrender: a beaten folk yields rather than dies; captives taken.
39. The wounded carried home and tended.
40. Sieges: a camp outside the walls stops the fields and caravans; relief or hunger decides it.
41. Defending the walls: archers on the walls, gates shut, a sally when the enemy tires.
42. Caravan escorts and ambushes on the roads.
43. Heroes: titles, medals and the chronicle's stories.
44. The player in the war: join a side, lead the band, scout, sell arms, or broker peace.

### Peace and after
45. War weariness: losses, hunger and long wars wear a town down; folk may leave.
46. Wartime elections: a failing war can cost the leader its seat.
47. Peace talks under a white flag: tribute, borders, captives, reparations, a trade deal.
48. Treaties kept, in both chronicles and on the board; breaking one is a cause for war.
49. Memorials and a remembrance day.
50. Demobilisation: the militia home to their trades, the war tax ended, the town rebuilt.
