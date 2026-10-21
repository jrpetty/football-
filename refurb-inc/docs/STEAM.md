# Steam (Milestone 2): co-op, invites, achievements, leaderboards

## What's in
- **GodotSteam 4.23.2** (MIT, Steamworks SDK 1.65) in `addons/godotsteam/`.
  - It ships 64-bit Windows and 64-bit Linux, which covers Steam Deck.
  - For macOS, copy `osx/` from the same release into the folder and restore its lines in `godotsteam.gdextension`.
  - The optional "GodotSteam Updater" editor plug-in is left switched off. It would check the internet for updates.
- **`SteamBridge`** (`scripts/net/steam_bridge.gd`) does everything Steam:
  - start-up, lobbies, invites and the P2P `SteamMultiplayerPeer`;
  - avatars, achievements and leaderboards.
  - It reaches GodotSteam only through `Engine.get_singleton("Steam")`, so builds and tests without the plug-in still run.
- **`Net`** (`scripts/autoload/net.gd`) has three transports: OFFLINE (solo), ENET (LAN / direct IP) and STEAM. The rest of the game doesn't know which is in use.
- **Title screen:**
  - **Host on Steam** asks for the business name, with an optional "Public game" tick box; friends-only is the default.
  - **Join a game…** lists public games, explains how to join a friend through Steam, and keeps LAN / direct IP.
- **Pause menu:**
  - **Open to friends (Steam)** turns a solo game, including a loaded save, into a hosted one without restarting.
  - **Invite friends** opens Steam's overlay invite dialog.
  - **Join *name*'s game** appears when a friend's invite arrives mid-game. It saves your game first.
- **Invites:**
  - At the title screen, accepting one joins straight away.
  - Mid-game, it becomes a toast plus the pause-menu button.
  - If the game wasn't running, Steam launches it with `+connect_lobby <id>` and it joins on start-up.
- **Players:**
  - Your Steam name is your player name, unless you set one in Settings.
  - Name tags update live.
  - Character → **Steam picture as my face (if no photo)** puts your Steam profile picture on your avatar for everyone.
- **Drop-outs:**
  - A guest who leaves drops whatever they were carrying where they stood, and the lobby reopens to new joiners.
  - If the host leaves, guests go back to the title screen with "The host closed the game." There's no host migration yet.
  - The lobby closes to new joiners once it's full (4 players).
- **Achievements:** `Achievements.steam_name(id)` → `ACH_<ID>` is unlocked on Steam as well as locally.
- **Leaderboards:** the host posts each milestone's time in working days to `DAYS_<ID>`. These boards are ascending, so fewer days is better.

## App id
`SteamBridge.APP_ID` is **480**, Valve's public test app "Spacewar". Lobbies, P2P and invites all work with it, so co-op can be tested now. While testing, Steam shows you as "playing Spacewar".

When Refurb Inc. has its own Steamworks app:
1. Set `APP_ID` to the real id.
2. Bump `LOBBY_VERSION` whenever the network protocol changes.
3. Create the achievements and leaderboards below in Steamworks (App Admin → Stats & Achievements / Leaderboards) and publish them.
   - With app 480, achievements and leaderboards quietly do nothing.

## Testing
- **One PC:** run `godot --headless --path . res://tools/steam_check.tscn` with Steam running. It:
  - starts Steam and reads your name and avatar;
  - opens a friends-only lobby and hosts over Steam P2P;
  - closes it again.
  - Last run: all 11 checks passed in 0.6 s.
- **Two players** need two Steam accounts on two PCs (or a friend):
  1. PC A: title screen → **Host on Steam** → name the business → **Start trading**.
  2. PC A: Esc → **Invite friends** → pick PC B's account.
  3. PC B: accept the invite in Steam. The game opens, or joins at once if it's running, and you appear in A's garage.
  4. Both: carry stock, use benches, make a tea round and hand each other mugs. Everything should match on both screens.
  5. PC B: Esc → Save & quit. A sees B's carried items dropped where B stood.
  6. PC A: quit to menu. If B rejoins first, B returns to the title screen with "The host closed the game."
- **Unit tests** (`tests/unit/test_steam.gd`) cover:
  - invite launch arguments;
  - join error messages;
  - the LAN/solo fallback without Steam;
  - Steamworks name validity.

## Steamworks registration lists

### Achievements (91)

| API name | Name | Description | Hidden |
|---|---|---|---|
| `ACH_OPEN_FOR_BUSINESS` | Open for business | Buy your first lot of ex-corporate kit. |  |
| `ACH_ITS_ALIVE` | It's alive! | Test your first unit. |  |
| `ACH_FIRST_FIX` | First fix | Repair your first fault. |  |
| `ACH_FRESH_INSTALL` | Fresh install | Wipe and image your first unit. |  |
| `ACH_GRADE_EXPECTATIONS` | Grade expectations | Grade your first unit. |  |
| `ACH_KA_CHING` | Ka-ching! | Get your first order on Flogit. |  |
| `ACH_THATS_A_WRAP` | That's a wrap | Box up an order yourself at the packing table. |  |
| `ACH_SIGNED_SEALED` | Signed, sealed, delivered | Ship your first sale with the courier. |  |
| `ACH_PUMP_IT_UP` | Pump it up | Jack up a pallet with the hand pallet truck. |  |
| `ACH_CLOCKING_OFF` | Clocking off | Finish your first working day. |  |
| `ACH_DIAGNOSTICIAN` | Diagnostician | Test 100 units. |  |
| `ACH_MINT_CONDITION` | Mint condition | Grade a unit A — pristine, full spec. |  |
| `ACH_FULL_STACK` | Full stack | Test a laptop, a desktop and a server. |  |
| `ACH_FULL_HOUSE` | Full house | Test every kind of kit: laptops, desktops, servers, monitors, switches, Wi-Fi access points, docks, tablets and phones. |  |
| `ACH_LORRY_LOAD` | Lorry-load | Buy a single lot of 20 or more units. |  |
| `ACH_LEMON` | Lemon | Test a unit with three or more faults. Somebody loved that one to death. | yes |
| `ACH_MADE_TO_ORDER` | Made to order | Ship a custom server build to a business buyer. |  |
| `ACH_RACK_AND_ROLL` | Rack and roll | Ship 10 custom server builds. |  |
| `ACH_BENCH_TECH` | Bench tech | Repair 50 faults. |  |
| `ACH_STEADY_HANDS` | Steady hands | Complete a level 2 repair: a screen, hinge, keyboard deck or PSU. |  |
| `ACH_BOARD_WIZARD` | Board-level wizard | Complete a level 3 repair: board faults, BIOS locks and dead ports. |  |
| `ACH_SOLD_AS_SEEN` | Sold as seen | Grade a unit as spares or repairs. Someone will want the bits. |  |
| `ACH_LAZARUS` | Lazarus | Bring a dead board back to life — fix a No POST fault. | yes |
| `ACH_SOUPED_UP` | Souped up | Upgrade the memory or storage of 25 units before selling them. |  |
| `ACH_CENTURY` | Century | Sell 100 units. |  |
| `ACH_PUNCTUAL` | Punctual | Ship 100 orders on time. |  |
| `ACH_BUSY_DAY` | Busy day | Ship 10 orders in a single day. |  |
| `ACH_COURIERS_NIGHTMARE` | The courier's nightmare | Ship 50 orders in a single day. |  |
| `ACH_CLEAN_SHEET` | Clean sheet | Finish a day with at least 5 orders shipped and none late or cancelled. |  |
| `ACH_FIVE_STAR_SHIFT` | Five-star shift | Earn a five-star shift rating. |  |
| `ACH_FIVE_STAR_SELLER` | Five-star seller | Max out your reputation. |  |
| `ACH_HIGH_ROLLER` | High roller | Ship a single order worth £750 or more. |  |
| `ACH_BETTER_LATE` | Better late than never | Let an order go late. The buyer has left a strongly worded message. | yes |
| `ACH_GOT_AWAY` | The one that got away | A buyer gave up waiting and cancelled. | yes |
| `ACH_DOTTED_LINE` | Sign on the dotted line | Accept your first business contract. |  |
| `ACH_BULK_BUYER` | Bulk buyer | Deliver a business order of 20 or more units on time. |  |
| `ACH_TOP_RATED` | Top-rated seller | Collect 25 five-star Flogit reviews. |  |
| `ACH_STRONGLY_WORDED` | Strongly worded | Get a one-star Flogit review. Ouch. | yes |
| `ACH_OUT_OF_THE_GARAGE` | Out of the garage | Move into the lock-up. |  |
| `ACH_BUSINESS_PARK` | Business park | Move into the small industrial unit. |  |
| `ACH_BIG_SHED_ENERGY` | Big shed energy | Move into the warehouse. |  |
| `ACH_HUB_AND_SPOKE` | Hub and spoke | Move into the distribution centre. |  |
| `ACH_HEADQUARTERS` | Headquarters | Move into the refurb facility. |  |
| `ACH_THE_MEGA_PLANT` | The mega plant | Move into the mega plant — the top of the ladder. |  |
| `ACH_FAST_MOVER` | Fast mover | Get out of the garage within 40 working days. |  |
| `ACH_LICENCE_TO_LIFT` | Licence to lift | Pick up a pallet with a forklift. |  |
| `ACH_TOP_SHELF` | Top shelf | Put a pallet away on the top level of pallet racking. |  |
| `ACH_INTERIOR_DESIGNER` | Interior designer | Paint the walls, mark out the floor and put up three pieces of decor. |  |
| `ACH_ROLLING` | Rolling, rolling, rolling | Buy your first conveyor. |  |
| `ACH_PRODUCTION_LINE` | Production line | Have 10 conveyor sections laid out. |  |
| `ACH_SPAGHETTI_JUNCTION` | Spaghetti junction | Have 50 conveyor sections laid out. |  |
| `ACH_BOOKED_IN` | Beep. Booked. | Let the goods-in scanner book a unit into stock. |  |
| `ACH_ROBOTS_WELCOME` | Robots welcome | Buy a pick-and-place robot arm. |  |
| `ACH_RACK_EM_UP` | Rack 'em up | Own a network test rack and a network imaging rack. |  |
| `ACH_TOWER_OF_POWER` | Tower of power | Store a unit in an automated stock tower. |  |
| `ACH_LIGHTS_OUT` | Lights out | Ship an order no human hand touched — booked in, processed and packed by machines. |  |
| `ACH_HANDS_FREE_GOODS_IN` | Hands-free goods-in | Let robot arms unload 100 units straight off pallets. |  |
| `ACH_TWO_PAIRS_OF_HANDS` | Two pairs of hands | Play a co-op session with a friend. |  |
| `ACH_FULL_CREW` | Full crew | Play with a full crew of four. |  |
| `ACH_GETTING_GOOD` | Getting good | Reach level 5 in any skill. |  |
| `ACH_MASTER_OF_ONE` | Master of one | Reach level 10 in a skill. |  |
| `ACH_JACK_OF_ALL_TRADES` | Jack of all trades | Reach level 5 in every skill. |  |
| `ACH_PERSONAL_BEST` | Personal best | Beat one of your own milestone records. |  |
| `ACH_EMPLOYEE_OF_THE_MONTH` | Employee of the Month | Get your name up in the frame on the wall: most jobs done in a week. |  |
| `ACH_FACE_OF_THE_COMPANY` | Face of the company | Put your own photo on your character and go to work. |  |
| `ACH_BIG_HEAD` | Head too big for the door | Finish a working day in big head mode. | yes |
| `ACH_KETTLES_ON` | Kettle's on | Make your first brew. |  |
| `ACH_WHOSE_ROUND` | Whose round is it? | Pour a round of four or more brews at once. |  |
| `ACH_BREW_BUDDY` | Brew buddy | Hand a brew to a colleague. |  |
| `ACH_PROPER_TEAMWORK` | Proper teamwork | Drink a brew a colleague made for you. |  |
| `ACH_CHIEF_TEA_MAKER` | Chief tea maker | Make 100 brews. |  |
| `ACH_STONE_COLD` | Stone cold | Drink a brew that went cold hours ago. You monster. | yes |
| `ACH_FIVE_FIGURES` | Five figures | Have £10,000 in the bank. |  |
| `ACH_GOOD_DAY` | Good day at the office | Make £1,000 profit in a single day. |  |
| `ACH_MILLIONAIRE` | Millionaire | Pass £1,000,000 in lifetime revenue. |  |
| `ACH_SHOCKING` | Shocking | Rack up an electricity bill of £100 or more in one day. |  |
| `ACH_IN_THE_RED` | In the red | Go overdrawn. The bank manager knows your name now. | yes |
| `ACH_INDUSTRIAL_SCALE` | Industrial scale | Sell 10,000 units. |  |
| `ACH_DARK_FACTORY` | Dark factory | Ship 100 lights-out orders. |  |
| `ACH_TWENTY_MILLION` | Twenty million | Pass £20,000,000 in lifetime revenue. |  |
| `ACH_REFURB_EMPIRE` | Refurb empire | Run the business from the mega plant with £20,000,000 in lifetime revenue. |  |
| `ACH_CUPPA_FOR_THE_CREW` | Cuppa for the crew | Hand a member of staff a brew you made. |  |
| `ACH_SOMEONE_ELSES_ROUND` | Someone else's round | Get a brew brought over by a member of staff. |  |
| `ACH_WORTH_EVERY_PENNY` | Worth every penny | Give a member of staff a pay rise. |  |
| `ACH_BACK_TO_SCHOOL` | Back to school | Send a member of staff on a training course. |  |
| `ACH_CHEERS_BOSS` | Cheers, boss | Thank your staff 25 times. |  |
| `ACH_NIGHT_OWLS` | Night owls | Work right through the night to 06:00 on the night shift licence. |  |
| `ACH_CHALLENGER` | Challenger | Win a medal in a challenge or a daily run. |  |
| `ACH_GOLD_STANDARD` | Gold standard | Win gold in a challenge. |  |
| `ACH_DAILY_GRIND` | Daily grind | Finish a daily run. |  |
| `ACH_COMPLETIONIST` | Completionist | Unlock every other achievement. |  |

### Leaderboards (ascending, numeric: working days)

| Leaderboard | Milestone |
|---|---|
| `DAYS_FIRST_SALE` | First sale |
| `DAYS_REVENUE_10K` | £10k in sales |
| `DAYS_BUILDING_LOCKUP` | Out of the garage |
| `DAYS_SOLD_100` | 100 units sold |
| `DAYS_REVENUE_50K` | £50k in sales |
| `DAYS_BUILDING_INDUSTRIAL` | Business park |
| `DAYS_REP_100` | Five-star seller |
| `DAYS_REVENUE_250K` | £250k in sales |
| `DAYS_FIRST_ROBOT` | Robots welcome |
| `DAYS_BUILDING_WAREHOUSE` | Warehouse |
| `DAYS_SOLD_1000` | 1,000 units sold |
| `DAYS_REVENUE_1M` | Millionaire |
| `DAYS_BUILDING_DISTRIBUTION` | Distribution centre |
| `DAYS_REVENUE_5M` | £5M in sales |
| `DAYS_BUILDING_FACILITY` | Refurb Inc. HQ |
| `DAYS_SOLD_10000` | 10,000 units sold |
| `DAYS_BUILDING_MEGAPLANT` | The mega plant |
| `DAYS_REVENUE_10M` | £10M in sales |
| `DAYS_REVENUE_20M` | £20M in sales |
| `DAYS_BREWS_100` | Chief tea maker |
| `DAYS_EMPIRE` | Refurb empire (end goal) |

### Challenge leaderboards (created on first use by `SteamBridge.submit_score`)

Timed challenges post the finish time in **working minutes** (ascending, fewer is better). The daily run posts its
score (descending, more is better). Each date gets its own board, so it can't be registered in advance.

| Leaderboard | Challenge | Sort |
|---|---|---|
| `CHALLENGE_COUNCIL_REFRESH` | The council refresh | ascending (working minutes) |
| `CHALLENGE_GARAGE_TO_LOCKUP` | Out of the garage | ascending (working minutes) |
| `CHALLENGE_LONE_WOLF` | Lone wolf | ascending (working minutes) |
| `CHALLENGE_OVERDRAFT_ESCAPE` | Overdraft escape | ascending (working minutes) |
| `CHALLENGE_FLOGIT_FRENZY` | Flogit frenzy | ascending (working minutes) |
| `CHALLENGE_DEADLINE_NIGHT` | Deadline night | ascending (working minutes) |
| `DAILY_<yyyymmdd>` | Daily run (UTC date) | descending (profit £ or orders shipped) |
