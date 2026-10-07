package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.Holder;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The city's research: sixty-three civic improvements in ten branches, each a slight buff, that a
 * village's leader chooses one at a time and the whole town works toward. [perks] It began as twenty
 * in five; the five old branches keep their twenty, under their old names, and saved towns lose
 * nothing of what they had done.
 *
 * <h2>The tree</h2>
 * Ten branches: <b>Industry</b>, <b>Land</b>, <b>Homes &amp; Works</b> (the houses, and now the roads,
 * the rails and the machines), <b>Wellbeing &amp; Arts</b> (the spirits, and now the stage and the
 * street music), <b>Trade</b>, <b>Defence</b>, <b>Lore</b>, <b>Faith</b>, <b>Sea</b> and <b>the
 * Arcane</b>. The old five have six tiers, the new five five. A tier is open once a civic of the tier
 * before it in its branch is done, so a town that wants the Watch Drills (Trade, third) must first see
 * to its Market Charter and its Paved Roads. Each costs research points by its tier: 10, 25, 50, 90
 * and 140; the top of every branch is a wonder and costs 200.
 *
 * <h2>Choices that shape a town</h2>
 * Eight tiers hold a pair, and a town may have only one of the two: Guild Monopolies or the Free
 * Market, the Open Granary or Private Larders, the Observer Pattern Books or the Turnpikes, Patronage
 * or Plain Living, Open Borders or Tolls and Tariffs, a Standing Army or a Militia, Stone Walls or
 * Earthworks and Hedges, the Scholars' Endowment or the Craftsmen's. Choosing one to study closes the
 * other for good once it is done (and while it is being studied). Either opens the tier above. So two
 * towns of the same land and size come apart: one dear and well made, one cheap and quick; one that
 * feeds everybody, one where a household keeps its own.
 *
 * <h2>Wonders</h2>
 * The top of each branch is a wonder (Wonders): the Great Forge, the Sky Garden, the Clockwork Gate,
 * the Founders' Colossus, the Grand Bazaar, the Arena, the Grand Library, the Cathedral, the Great
 * Lighthouse and the Observatory. Studied, it is a set of plans; the town then lays the wonder's dues
 * by out of its stores and raises it, a real building of its own, and its big perk works only while
 * it stands and is the town's. Only one town in the world may have each: the first to finish it.
 * Once one is up anywhere it is closed to every other town, and they hear of it.
 *
 * <h2>Points</h2>
 * A village earns its points every morning, with the day's books (Market.tick, straight after the
 * leader's own morning): a point for every four grown folk up to sixteen of them, and a point for
 * every twelve grown folk past that (twelve points at most from its people: a town of two hundred
 * has more hands but not many more scholars); a point for a meeting hall, one for a library and one
 * for a chapel; and a point while its leader holds a mandate for the next age (PROGRESS). The
 * Scholars' Endowment adds two and the Surveyors' Office one; a leader's Sage skill two, and a
 * legacy of learning one each (Reigns). The Grand Library adds half again, the Observatory a quarter,
 * a Visionary in office fifteen in the hundred; what is less than a whole point is carried to the
 * next morning ({@code civic.carry}), so a small town's fifteenth is never lost. Never less than a
 * point a day.
 *
 * <h2>Who chooses, and why</h2>
 * When nothing is being studied, the leader chooses the next civic among the open ones, by:
 * <ul>
 * <li><b>what it cares about</b> (Values: its seven weights, each civic leaning to one or two of them:
 *     a Provider to the Land and the Sea, a Homemaker to Homes, a Visionary to Industry, Lore and the
 *     works, a Merchant to Trade, a Free Spirit to Wellbeing, a Guardian to Defence, a Traditionalist
 *     to Faith), and above all what it was elected for (its mandate, +30);</li>
 * <li><b>its nature</b> (a hardworking leader likes Industry, a cheerful or sociable one Wellbeing, a
 *     generous one Homes and the Open Granary, a grumpy one the watch and the tariffs, a curious one
 *     Lore and the Arcane, a shy one the Land and the chapel...);</li>
 * <li><b>the land</b> (Homeland: a coast town leans to the Sea, a mountain town to Industry and the
 *     walls, a desert town to its faith, a fen town to the Arcane), and <b>the town's ethos</b>, which
 *     whoever keeps it may lean through {@link #ETHOS};</li>
 * <li><b>what the village is short of now</b>: food short → the Land; houses short → Homes;
 *     contentment low → Wellbeing and Faith; the raiders at the gate this week → Defence and the Watch
 *     Drills; the treasury thin → Trade;</li>
 * <li>a wonder no town has raised yet, for the glory of it;</li>
 * <li>and half of whatever pulls hardest further up the same branch, so a Guardian who wants the
 *     Arena starts on the Watch House ("it's the road to the Arena"). A little for the cheaper tiers,
 *     too, so the first steps come before the long ones.</li>
 * </ul>
 * The biggest of those is the reason it gives. With no leader, the council chooses what is most
 * pressing (the village's needs alone); a player who leads chooses on its Leader's page, and when it
 * leaves it to them its steward chooses for it. The choice goes in the chronicle ("Reeve Bramble set
 * the town to work on Crop Rotation: our fields need it"), the leader says it aloud when it is about,
 * and the morning assembly hears it; so does the day it is finished.
 *
 * <h2>The effects</h2>
 * Each civic touches the real mechanics at one place (named on each below), and each is slight: a
 * civic is worth three to ten in the hundred of somebody's pace, a tenth or a fifth off a price, a
 * point of armour. A wonder is worth more.
 *
 * <h2>Where it is kept</h2>
 * In the village's Ledger notes, so it survives a restart: {@code civic.done} (the civics done and
 * the day each was: "COMMON_TOOLS:4,CHEAP_HOMES:9"), {@code civic.now} (the one being studied),
 * {@code civic.points} (points in hand toward it), {@code civic.carry} (hundredths of a point carried),
 * {@code civic.why} and {@code civic.by} (the leader's reason and its name), {@code civic.since} (the
 * day it was chosen), {@code civic.day} (the last morning counted) and two small carries for the coin
 * effects ({@code civic.fund}, {@code civic.counting}).
 *
 * <p>Shown on the village board (a line in "What we're working towards"), on the town's books'
 * Research page (CityScreen, from {@link #report}), by {@code /village research}, and by the folk when
 * asked about the elder's orders.
 */
public final class CityTree {

    private CityTree() {}

    // ------------------------------------------------------------------ the tree

    public enum Branch {
        INDUSTRY("Industry", "the trades: tools, training, guilds and the forge"),
        LAND("Land", "the fields, the herds and the larder"),
        HOMES("Homes & Works", "houses, building, roads, rails and machines"),
        WELLBEING("Wellbeing & Arts", "spirits, health, rest, the stage and the street's music"),
        TRADE("Trade", "the market, the roads, the caravans and the counting house"),
        // [perks] The five new branches.
        DEFENCE("Defence", "the watch, the walls and the arrows"),
        LORE("Lore", "the school, the library, maps and learning"),
        FAITH("Faith", "the chapel, remembrance and hard times"),
        SEA("Sea", "the fleet, the harbour and the deep"),
        ARCANE("The Arcane", "the enchanter, the brewer and the Nether");

        public final String title;
        public final String about;

        Branch(String title, String about) {
            this.title = title;
            this.about = about;
        }

        /** How many tiers it has: the old five six, the new five five. */
        public int tiers() {
            int most = 0;
            for (Civic c : Civic.values()) if (c.branch == this) most = Math.max(most, c.tier);
            return most;
        }
    }

    /** What each tier costs, in research points (a wonder, whatever its tier, costs {@link #WONDER_COST}). */
    static final int[] COST = { 10, 25, 50, 90, 140 };
    /** A wonder's plans. */
    static final int WONDER_COST = 200;

    public enum Civic {
        // Industry: what the trades work with and how they learn.
        COMMON_TOOLS(Branch.INDUSTRY, 1, "Common Tools", "+3% work pace, every trade",
            "every trade works 3% quicker",
            "A decent tool in every hand, and somebody to keep them sharp: every trade's work goes 3% quicker."),
        APPRENTICE_HALLS(Branch.INDUSTRY, 2, "Apprentice Halls", "+10% trade experience",
            "folk learn their trades a tenth faster",
            "Somewhere the new hands learn from the old: a tenth more experience from every piece of work."),
        GUILD_CHARTERS(Branch.INDUSTRY, 3, "Guild Charters", "+5% pace for the crafts",
            "the smith, tailor, cook, shopkeeper, brewer, enchanter and smelter work 5% quicker",
            "Each craft keeps its own guild and standards: the smith, the tailor, the cook, the shopkeeper, the brewer, "
                + "the enchanter and the smelter work 5% quicker."),
        MASTER_WORKSHOPS(Branch.INDUSTRY, 4, "Master Workshops", "Tools wear 20% slower",
            "tools last a fifth longer",
            "Proper forges and whetstones: a tool is seen to before it breaks, and wears a fifth slower."),
        // [perks] Industry's choice, and its wonder.
        GUILD_MONOPOLIES(Branch.INDUSTRY, 5, "Guild Monopolies", "Crafted goods 10% dearer; tools last longer",
            "the guilds' goods cost a tenth more, and are made to last",
            "Each guild holds its craft to itself and to its own standards: a tool, a blade, a piece of armour, a potion or "
                + "an enchanted book costs a tenth more at the town's counters, and every tool the town's folk use wears a tenth "
                + "slower. Closes the Free Market."),
        FREE_MARKET(Branch.INDUSTRY, 5, "Free Market", "Crafted goods 10% cheaper; crafts +6% pace",
            "the crafts work quicker and sell cheaper",
            "Anybody may set up at any craft, and the best and cheapest win: a tool, a blade, a piece of armour, a potion or "
                + "an enchanted book costs a tenth less at the town's counters, and the crafts work 6% quicker. Closes the "
                + "Guild Monopolies."),
        GREAT_FORGE(Branch.INDUSTRY, 6, "The Great Forge", "Wonder: every trade +4%, smith and smelter +10%",
            "the Great Forge's plans are drawn",
            "A wonder: a forge hall with twin chimneys, a row of furnaces and the town's anvils under one roof. While it "
                + "stands and is the town's, every trade works 4% quicker, the smith and the smelter 10% more, and a tool "
                + "wears a quarter slower. Only one town in the world may raise it."),
        // Land: the fields, the herds and the larder.
        CROP_ROTATION(Branch.LAND, 1, "Crop Rotation", "+5% farmer pace",
            "farmers work 5% quicker",
            "The fields rested in turn, so they never tire: the farmers work 5% quicker."),
        HERD_BOOKS(Branch.LAND, 2, "Herd Books", "+5% pace: herds, hunt, fish, bees",
            "ranchers, hunters, fishers and beekeepers work 5% quicker",
            "Who sired what, where the fish run and when the bees swarm, written down: ranchers, hunters, fishers "
                + "and beekeepers work 5% quicker."),
        SEED_EXCHANGE(Branch.LAND, 3, "Seed Exchange", "One crop in ten gives one more",
            "a tenth more comes off the fields",
            "The best seed kept and traded field to field: one harvested crop in ten gives one more wheat, carrot, "
                + "potato or beetroot."),
        GRANARIES(Branch.LAND, 4, "Granaries", "Folk eat 10% less",
            "the larder goes a tenth further",
            "Dry stores and a careful hand, so less spoils: folk go a tenth longer between meals, and the larder "
                + "goes a tenth further."),
        OPEN_GRANARY(Branch.LAND, 5, "Open Granary", "Meals free from the stores; nobody starves",
            "the stores feed anybody hungry, free, wherever they are",
            "What the town grows is the town's: a meal out of the stores is free to anybody, wherever it stands, nobody "
                + "grows weak with hunger, and the town is 2 more content for it. Closes Private Larders."),
        PRIVATE_LARDERS(Branch.LAND, 5, "Private Larders", "Wages +5%; food a fifth dearer; hard on the poor",
            "every household keeps its own larder, and pays its way",
            "Every household keeps its own: wages are 5% higher, food bought at the town's counter costs a fifth more "
                + "(into the treasury), the poor feel it (4 lower in spirits) and are the readier to steal. Closes the "
                + "Open Granary."),
        SKY_GARDEN(Branch.LAND, 6, "The Sky Garden", "Wonder: farmers +8%, a harvest in five +1, mood +3",
            "the Sky Garden's plans are drawn",
            "A wonder: terraces of earth and flowers climbing to a pavilion in the sky. While it stands and is the town's, "
                + "the farmers work 8% quicker, one harvest in five gives one more, and everybody is 3 the happier for "
                + "the sight of it. Only one town in the world may raise it."),
        // Homes: rent, building and buying; and the works.
        CHEAP_HOMES(Branch.HOMES, 1, "Cheap Homes", "Rent a fifth lower",
            "rents fall by a fifth",
            "The village lets its houses cheap: a fifth off every rent of three coins or more, and a rent of a "
                + "coin or two is let off one payday in five."),
        BUILDERS_GUILD(Branch.HOMES, 2, "Builders' Guild", "Building 10% quicker",
            "builders lay their blocks 10% quicker",
            "Builders who know their trade, and a yard to work from: every building goes up 10% quicker."),
        HOME_LOANS(Branch.HOMES, 3, "Home Loans", "House prices 10% lower",
            "houses sell for a tenth less",
            "The village helps a household to its own roof: every house it sells costs a tenth less."),
        HOUSING_FUND(Branch.HOMES, 4, "Housing Fund", "Treasury adds a tenth to house savings",
            "the treasury adds a coin for every ten a household saves toward its house",
            "For every ten coins a renting household puts by toward buying its house, the treasury adds one more "
                + "(while it has the coin)."),
        OBSERVER_PATTERN_BOOKS(Branch.HOMES, 5, "Observer Pattern Books", "Machines 25% faster; rails laid 25% faster",
            "the town's machines and rails go in a quarter quicker",
            "The redstone patterns written down: the redstone engineer builds its machines a quarter faster, and the "
                + "railway's rails go down a quarter faster. Closes the Turnpikes."),
        TURNPIKES(Branch.HOMES, 5, "Turnpikes", "Walk +4%; roads and bridges laid half again as fast",
            "good roads and quick bridges",
            "Roads kept up out of the tolls: everybody walks 4% faster, and the road to a colony and a stone bridge are "
                + "laid half again as fast. Closes the Observer Pattern Books."),
        CLOCKWORK_GATE(Branch.HOMES, 6, "The Clockwork Gate", "Wonder: building +15%, walk +3%, works twice as fast",
            "the Clockwork Gate's plans are drawn",
            "A wonder: a great gatehouse of two towers, a bell and a clockwork of note blocks. While it stands and is the "
                + "town's, every building goes up 15% quicker, everybody walks 3% faster, and the rails, roads and "
                + "bridges are laid twice as fast. Only one town in the world may raise it."),
        // Wellbeing: spirits, health and rest; and the arts.
        FEAST_DAYS(Branch.WELLBEING, 1, "Feast Days", "+3 contentment",
            "the town is 3 points more content",
            "Feast days on the calendar, kept every season: the village's contentment is 3 points higher."),
        TAVERN_SONGS(Branch.WELLBEING, 2, "Tavern Songs", "+3 mood for everyone",
            "everybody's mood is 3 better",
            "Songs at the tavern of an evening, and everybody knows the words: every folk's mood is 3 better."),
        HEALERS(Branch.WELLBEING, 3, "Healers", "Heal twice as fast; live 10% longer",
            "folk mend twice as fast and live a tenth longer",
            "Somebody who knows herbs and bandages: the hurt mend twice as fast between meals, and the old live a "
                + "tenth longer."),
        REST_DAY_CHARTER(Branch.WELLBEING, 4, "Rest Day Charter", "Breaks 10% shorter, +2 mood",
            "breaks are a tenth shorter, and folk feel 2 better for them",
            "Rest kept by the charter — rested, not idle: breaks are a tenth shorter, and every folk's mood is 2 better."),
        PATRONAGE(Branch.WELLBEING, 5, "Patronage of the Arts", "+3 contentment; buskers tipped more",
            "the town keeps its players, painters and musicians",
            "The treasury's patronage for the stage, the band and the painters: the town is 3 more content, and a busker's "
                + "hat fills a good deal quicker. Closes Plain Living."),
        PLAIN_LIVING(Branch.WELLBEING, 5, "Plain Living", "Every trade +3%; breaks 5% shorter; -2 contentment",
            "plain living and hard work",
            "No frills: every trade works 3% quicker and a break is a twentieth shorter, though the town is 2 less content "
                + "for it. Closes Patronage of the Arts."),
        FOUNDERS_COLOSSUS(Branch.WELLBEING, 6, "The Founders' Colossus", "Wonder: contentment +5, mood +2, nobody leaves",
            "the Founders' Colossus's plans are drawn",
            "A wonder: the town's founder, raised in stone four times a house's height over the square. While it stands "
                + "and is the town's, the town is 5 more content, everybody 2 happier, and no folk of it ever leaves for "
                + "another town. Only one town in the world may raise it."),
        // Trade: the market, the roads and the watch.
        MARKET_CHARTER(Branch.TRADE, 1, "Market Charter", "+5% market takings",
            "the village takes 5% more for its work and its goods",
            "A charter for the market, so traders come from further off: the village's daily takings and what the "
                + "market-day traders pay are 5% higher."),
        PAVED_ROADS(Branch.TRADE, 2, "Paved Roads", "Folk walk 5% faster",
            "everybody walks 5% faster",
            "Stone underfoot from door to field: everybody walks 5% faster."),
        WATCH_DRILLS(Branch.TRADE, 3, "Watch Drills", "Guards +1 armour, +1 attack",
            "guards have a point more armour and hit a point harder",
            "The watch drills at dawn: every guard has a point more armour and hits a point harder."),
        COUNTING_HOUSE(Branch.TRADE, 4, "Counting House", "Wages cost 5% less",
            "the wages cost the treasury a twentieth less",
            "A clerk who counts every coin: a twentieth of each payday's wages comes back to the treasury."),
        OPEN_BORDERS(Branch.TRADE, 5, "Open Borders", "Caravans carry 6 lots; neighbours warm; takings +3%",
            "the roads are open to every neighbour",
            "Come one, come all: a caravan carries six lots, not four, the neighbours think a little better of the town "
                + "every day, and the takings are 3% higher. Closes Tolls and Tariffs."),
        TARIFFS(Branch.TRADE, 5, "Tolls and Tariffs", "Takings +6%; neighbours cool a little",
            "a toll on every road and a tariff at the gate",
            "Every trader pays at the gate: the takings are 6% higher, but the neighbours think a little less of the town "
                + "every other day. Closes Open Borders."),
        GRAND_BAZAAR(Branch.TRADE, 6, "The Grand Bazaar", "Wonder: takings +10%, caravans 8 lots, wages back",
            "the Grand Bazaar's plans are drawn",
            "A wonder: a great covered market of arcades and stalls. While it stands and is the town's, the takings are "
                + "10% higher, a caravan carries eight lots, and a further twentieth of the wages comes back to the "
                + "treasury. Only one town in the world may raise it."),
        // [perks] Defence: the watch, the walls and the arrows.
        WATCH_HOUSE(Branch.DEFENCE, 1, "Watch House", "Guards +2 hearts",
            "every guard is two hearts the hardier",
            "A house for the watch, with its bunks, its fire and its surgeon: every guard has two hearts more."),
        STANDING_ARMY(Branch.DEFENCE, 2, "Standing Army", "A guard more; guards +1 attack, paid a coin more",
            "the watch is kept full-time",
            "The watch is a trade of its own, paid all year: the town keeps one more guard, every guard hits a point "
                + "harder and is paid a coin more a day, and crime is a little rarer. Closes the Militia."),
        MILITIA(Branch.DEFENCE, 2, "Militia", "Everyone +1 armour; a guard fewer",
            "everybody drills on the green",
            "Everybody takes a turn at the drill: every grown folk has a point of armour, and the town keeps one guard "
                + "fewer (never none). Cheaper, and weaker. Closes the Standing Army."),
        FLETCHERS_CHARTER(Branch.DEFENCE, 3, "Fletchers' Charter", "Quivers +16",
            "every guard carries sixteen more arrows",
            "A charter for the fletchers: a guard with a bow is given thirty-two arrows out of the stores, not sixteen."),
        STONE_WALLS(Branch.DEFENCE, 4, "Stone Walls", "Under the bell, everyone +2 armour",
            "stone walls to shelter behind",
            "Proper walls of stone: while the bell rings, every folk of the town has two points of armour more. Closes "
                + "Earthworks and Hedges."),
        EARTHWORKS(Branch.DEFENCE, 4, "Earthworks and Hedges", "Guards see 6 further; hunters +5%",
            "banks and hedges round the town",
            "Banks, ditches and hedgerows: a guard on the wall picks its mark six blocks further out, and the hedges "
                + "shelter game, so the hunters work 5% quicker. Closes Stone Walls."),
        ARENA(Branch.DEFENCE, 5, "The Arena", "Wonder: guards +2 attack and armour; contentment +3",
            "the Arena's plans are drawn",
            "A wonder: a ring of stone seats round a sanded floor, where the watch trains and the town comes to watch. "
                + "While it stands and is the town's, every guard hits two points harder and has two more armour, and the "
                + "town is 3 more content. Only one town in the world may raise it."),
        // [perks] Lore: the school, the library, maps and learning.
        PRIMERS(Branch.LORE, 1, "Primers", "Children learn a quarter faster",
            "every child has a primer",
            "A primer for every child: a lesson at the school teaches a quarter more of its trade."),
        PRINTING_PRESS(Branch.LORE, 2, "Printing Press", "Each new library book printed twice",
            "the library's books are printed",
            "A press: each new book written for the library is printed once more on a plain book out of the stores, the "
                + "copy put in the stores for the town to sell or keep."),
        SCHOLARS_ENDOWMENT(Branch.LORE, 3, "Scholars' Endowment", "Research +2 a day; the school a fifth more",
            "the scholars are kept by the town",
            "The treasury keeps scholars: two more research points every morning, and a lesson at the school teaches a "
                + "fifth more. Closes the Craftsmen's Endowment."),
        CRAFTSMENS_ENDOWMENT(Branch.LORE, 3, "Craftsmen's Endowment", "Trade experience +10%; crafts +3%",
            "the masters are kept by the town",
            "The treasury keeps the masters at their benches: a tenth more experience from every piece of work, and the "
                + "crafts work 3% quicker. Closes the Scholars' Endowment."),
        SURVEYORS_OFFICE(Branch.LORE, 4, "Surveyors' Office", "Scouts range a quarter further; research +1",
            "the land is surveyed and mapped",
            "Maps of everything the town knows: the scouts go a quarter further and see more, and the research gains a "
                + "point a morning."),
        GRAND_LIBRARY(Branch.LORE, 5, "The Grand Library", "Wonder: research +50%, experience +10%",
            "the Grand Library's plans are drawn",
            "A wonder: a hall of books two storeys high, galleries and ladders and a reading floor. While it stands and is "
                + "the town's, its research comes half again as fast, and every piece of work teaches a tenth more. Only "
                + "one town in the world may raise it."),
        // [perks] Faith: the chapel, remembrance and hard times.
        VESPERS(Branch.FAITH, 1, "Vespers", "+3 mood in hard times",
            "an evening service when times are hard",
            "Evening prayers when the town is low: while the town's contentment is under fifty, every folk is 3 the "
                + "better for them."),
        REMEMBRANCE(Branch.FAITH, 2, "Remembrance", "Grief half as hard",
            "the dead are remembered together",
            "The dead are remembered together, and nobody grieves alone: a folk's grief weighs half as much."),
        SAINTS_DAYS(Branch.FAITH, 3, "Saints' Days", "+1 feast a season",
            "a saint's feast every season",
            "A saint for every season, and a feast for each: the town gathers for one more feast a season, on its second "
                + "day."),
        ALMSHOUSE(Branch.FAITH, 4, "Almshouse", "The poor +4 mood, less tempted",
            "the poor are looked after",
            "Alms for the poor: a folk with nothing is 4 the happier for knowing it will be looked after, and crime is "
                + "a little rarer."),
        CATHEDRAL(Branch.FAITH, 5, "The Cathedral", "Wonder: contentment +4, mood never under 30, live longer",
            "the Cathedral's plans are drawn",
            "A wonder: a nave of tall windows and a spire with a bell. While it stands and is the town's, the town is 4 "
                + "more content, no folk of it sinks below 30 in spirits, and the old live a tenth longer. Only one town "
                + "in the world may raise it."),
        // [perks] Sea: the fleet, the harbour and the deep.
        FISHWIVES_GUILD(Branch.SEA, 1, "Fishwives' Guild", "Fishers +6%",
            "the fishers work 6% quicker",
            "A guild that mends the nets, guts the catch and keeps the quay: the fishers work 6% quicker."),
        NAVIGATORS_GUILD(Branch.SEA, 2, "Navigator's Guild", "The fleet sails in light rain",
            "the fleet sails in the rain",
            "Charts and a compass in every boat: the fishing fleet goes out in the rain (never in a storm)."),
        DIVING_BELLS(Branch.SEA, 3, "Diving Bells", "Everyone holds their breath longer",
            "the town's folk swim like fish",
            "Diving bells and practice in the deep: every folk of the town holds its breath twice as long."),
        SHIPWRIGHTS(Branch.SEA, 4, "Shipwrights", "The fleet fits out a boat more",
            "a shipwright on the quay",
            "A shipwright on the quay: the fishing fleet fits out a boat more (and a fisher for it)."),
        GREAT_LIGHTHOUSE(Branch.SEA, 5, "The Great Lighthouse", "Wonder: fishers +10%, more fish, sails in rain",
            "the Great Lighthouse's plans are drawn",
            "A wonder: a tower of stone with a lantern room of glass, seen for miles. While it stands and is the town's, "
                + "the fishers work 10% quicker, one haul in three lands a fish more, and the fleet sails in any rain "
                + "short of a storm. Only one town in the world may raise it."),
        // [perks] The Arcane: the enchanter, the brewer and the Nether.
        HERBALS(Branch.ARCANE, 1, "Herbals", "Brewer and enchanter +6%",
            "the brewer and the enchanter work 6% quicker",
            "Herbals and grimoires: the brewer and the enchanter work 6% quicker."),
        BLAZE_WARDENS(Branch.ARCANE, 2, "Blaze Wardens", "Nether runners take half the fire",
            "the Nether runners go warded",
            "Wards against the fire: a Nether runner (and anybody of the town in the Nether) takes half the harm of fire "
                + "and lava."),
        NETHER_CHARTS(Branch.ARCANE, 3, "Nether Charts", "Nether runs every day; a quarter less walking",
            "the Nether is charted",
            "The Nether's ways charted: the runners go through the gateway every day, not every other, and a run's "
                + "walking is reckoned a quarter shorter, so more of it goes on the work."),
        ALCHEMISTS_GUILD(Branch.ARCANE, 4, "Alchemists' Guild", "Brewer +8%; three powders to a rod",
            "the alchemists grind finer",
            "The alchemists' secrets: the brewer works 8% quicker, and grinds three blaze powders from a rod, not two."),
        OBSERVATORY(Branch.ARCANE, 5, "The Observatory", "Wonder: research +25%, enchanter and brewer +15%, luck",
            "the Observatory's plans are drawn",
            "A wonder: a round tower and a dome, the stars charted and the arcane read in them. While it stands and is "
                + "the town's, its research comes a quarter faster, the enchanter and the brewer work 15% quicker, and "
                + "one ore in ten a miner digs gives one more. Only one town in the world may raise it.");

        public final Branch branch;
        public final int tier;
        public final String title;
        /** Short, for the tree's nodes: "+3% work pace, every trade". */
        public final String effect;
        /** As news has it, once done: "every trade works 3% quicker". */
        public final String result;
        /** The whole of it, for the tooltip and the books. */
        public final String about;

        Civic(Branch branch, int tier, String title, String effect, String result, String about) {
            this.branch = branch;
            this.tier = tier;
            this.title = title;
            this.effect = effect;
            this.result = result;
            this.about = about;
        }

        /** Is it a wonder: the top of its branch, a building of its own, one in the world? */
        public boolean wonder() {
            return WONDERS.contains(this);
        }

        public int cost() {
            return wonder() ? WONDER_COST : COST[Math.min(COST.length, tier) - 1];
        }

        /** For commands and the Ledger: "common_tools". */
        public String key() {
            return name().toLowerCase(Locale.ROOT);
        }

        /** The other of its pair, which choosing it closes; null for a civic with none. */
        @Nullable
        public Civic rival() {
            return RIVALS.get(this);
        }

        /**
         * The civic that must be done first (the tier below in its branch), or null for a first tier.
         * Where the tier below is a pair, the first of the two (either will do: {@link #befores}).
         */
        @Nullable
        public Civic before() {
            if (tier == 1) return null;
            for (Civic c : values()) if (c.branch == branch && c.tier == tier - 1) return c;
            return null;
        }

        /** Every civic of the tier below in its branch: any one of them done opens this one. */
        public List<Civic> befores() {
            List<Civic> out = new ArrayList<>();
            if (tier == 1) return out;
            for (Civic c : values()) if (c.branch == branch && c.tier == tier - 1) out.add(c);
            return out;
        }

        /**
         * How much each of the seven things a folk can care about leans to this civic, in the hundred:
         * the whole of a branch leans to its own (the Land to a full larder, Homes to homes, Industry
         * to the next age, Trade to wages and trade, Wellbeing to rest and merriment, Defence to safe
         * streets, Lore and the Arcane to the next age, Faith to the old ways, the Sea to the larder
         * and to trade), the old ways to the Land and to Wellbeing a little, and safe streets to the
         * Watch Drills and the Healers. Each side of a pair leans its own way, so the choice between
         * them is the leader's character.
         */
        int affinity(Values.Value v) {
            if (this == WATCH_DRILLS) return v == Values.Value.SAFETY ? 100 : v == Values.Value.WEALTH ? 20 : 0;
            if (this == HEALERS) return v == Values.Value.SAFETY ? 80 : v == Values.Value.LEISURE ? 60 : v == Values.Value.TRADITION ? 30 : 0;
            if (this == PAVED_ROADS && v == Values.Value.PROGRESS) return 40;
            if (this == GUILD_CHARTERS && v == Values.Value.WEALTH) return 40;
            if (this == APPRENTICE_HALLS && v == Values.Value.TRADITION) return 30;
            if ((this == HOME_LOANS || this == HOUSING_FUND) && v == Values.Value.WEALTH) return 30;
            // [perks] Each side of a pair, by its own lights.
            int[] own = switch (this) {
                case GUILD_MONOPOLIES -> lean(v, Values.Value.TRADITION, 60, Values.Value.WEALTH, 50, Values.Value.PROGRESS, 30);
                case FREE_MARKET -> lean(v, Values.Value.WEALTH, 70, Values.Value.PROGRESS, 60, null, 0);
                case OPEN_GRANARY -> lean(v, Values.Value.FOOD, 100, Values.Value.LEISURE, 30, Values.Value.HOMES, 20);
                case PRIVATE_LARDERS -> lean(v, Values.Value.WEALTH, 80, Values.Value.FOOD, 40, null, 0);
                case OBSERVER_PATTERN_BOOKS -> lean(v, Values.Value.PROGRESS, 100, Values.Value.HOMES, 30, null, 0);
                case TURNPIKES -> lean(v, Values.Value.WEALTH, 60, Values.Value.HOMES, 60, null, 0);
                case CLOCKWORK_GATE -> lean(v, Values.Value.PROGRESS, 90, Values.Value.HOMES, 50, null, 0);
                case PATRONAGE -> lean(v, Values.Value.LEISURE, 100, Values.Value.TRADITION, 30, null, 0);
                case PLAIN_LIVING -> lean(v, Values.Value.TRADITION, 70, Values.Value.WEALTH, 50, Values.Value.PROGRESS, 30);
                case OPEN_BORDERS -> lean(v, Values.Value.WEALTH, 90, Values.Value.LEISURE, 30, null, 0);
                case TARIFFS -> lean(v, Values.Value.WEALTH, 90, Values.Value.SAFETY, 40, null, 0);
                case STANDING_ARMY -> lean(v, Values.Value.SAFETY, 100, Values.Value.WEALTH, 10, null, 0);
                case MILITIA -> lean(v, Values.Value.SAFETY, 70, Values.Value.TRADITION, 50, null, 0);
                case STONE_WALLS -> lean(v, Values.Value.SAFETY, 100, Values.Value.PROGRESS, 20, null, 0);
                case EARTHWORKS -> lean(v, Values.Value.SAFETY, 70, Values.Value.FOOD, 40, Values.Value.TRADITION, 30);
                case SCHOLARS_ENDOWMENT -> lean(v, Values.Value.PROGRESS, 100, null, 0, null, 0);
                case CRAFTSMENS_ENDOWMENT -> lean(v, Values.Value.PROGRESS, 60, Values.Value.WEALTH, 60, Values.Value.TRADITION, 20);
                case REMEMBRANCE -> lean(v, Values.Value.TRADITION, 100, Values.Value.LEISURE, 30, null, 0);
                case ALMSHOUSE -> lean(v, Values.Value.TRADITION, 70, Values.Value.HOMES, 40, Values.Value.FOOD, 30);
                default -> null;
            };
            if (own != null) return own[0];
            return switch (branch) {
                case INDUSTRY -> v == Values.Value.PROGRESS ? 100 : 0;
                case LAND -> v == Values.Value.FOOD ? 100 : v == Values.Value.TRADITION ? 40 : 0;
                case HOMES -> v == Values.Value.HOMES ? 100 : 0;
                case WELLBEING -> v == Values.Value.LEISURE ? 100 : v == Values.Value.TRADITION ? 30 : 0;
                case TRADE -> v == Values.Value.WEALTH ? 100 : 0;
                case DEFENCE -> v == Values.Value.SAFETY ? 100 : v == Values.Value.TRADITION ? 20 : 0;
                case LORE -> v == Values.Value.PROGRESS ? 90 : v == Values.Value.TRADITION ? 20 : 0;
                case FAITH -> v == Values.Value.TRADITION ? 100 : v == Values.Value.LEISURE ? 20 : v == Values.Value.SAFETY ? 10 : 0;
                case SEA -> v == Values.Value.FOOD ? 60 : v == Values.Value.WEALTH ? 50 : 0;
                case ARCANE -> v == Values.Value.PROGRESS ? 70 : v == Values.Value.WEALTH ? 20 : 0;
            };
        }

        /** One of up to three cares, each with its lean: {lean} for {@code v}. */
        private static int[] lean(Values.Value v, @Nullable Values.Value a, int na, @Nullable Values.Value b, int nb,
                                  @Nullable Values.Value c, int nc) {
            return new int[]{ v == a ? na : v == b ? nb : v == c ? nc : 0 };
        }
    }

    /** The pairs: each civic and the other of its pair. */
    private static final Map<Civic, Civic> RIVALS = new EnumMap<>(Civic.class);
    /** The wonders: the top of each branch. */
    private static final EnumSet<Civic> WONDERS = EnumSet.of(Civic.GREAT_FORGE, Civic.SKY_GARDEN, Civic.CLOCKWORK_GATE,
        Civic.FOUNDERS_COLOSSUS, Civic.GRAND_BAZAAR, Civic.ARENA, Civic.GRAND_LIBRARY, Civic.CATHEDRAL, Civic.GREAT_LIGHTHOUSE,
        Civic.OBSERVATORY);

    static {
        pair(Civic.GUILD_MONOPOLIES, Civic.FREE_MARKET);
        pair(Civic.OPEN_GRANARY, Civic.PRIVATE_LARDERS);
        pair(Civic.OBSERVER_PATTERN_BOOKS, Civic.TURNPIKES);
        pair(Civic.PATRONAGE, Civic.PLAIN_LIVING);
        pair(Civic.OPEN_BORDERS, Civic.TARIFFS);
        pair(Civic.STANDING_ARMY, Civic.MILITIA);
        pair(Civic.STONE_WALLS, Civic.EARTHWORKS);
        pair(Civic.SCHOLARS_ENDOWMENT, Civic.CRAFTSMENS_ENDOWMENT);
    }

    private static void pair(Civic a, Civic b) {
        RIVALS.put(a, b);
        RIVALS.put(b, a);
    }

    /** Every pair, once each (the first of the two as the tree lists it). */
    public static List<Civic[]> pairs() {
        List<Civic[]> out = new ArrayList<>();
        for (Civic c : Civic.values()) {
            Civic r = c.rival();
            if (r != null && c.ordinal() < r.ordinal()) out.add(new Civic[]{ c, r });
        }
        return out;
    }

    @Nullable
    public static Civic byKey(@Nullable String key) {
        if (key == null || key.isBlank()) return null;
        String k = key.trim().toUpperCase(Locale.ROOT).replace(' ', '_').replace("'", "").replace('-', '_');
        for (Civic c : Civic.values()) if (c.name().equals(k)) return c;
        for (Civic c : Civic.values()) {
            String t = c.title.toUpperCase(Locale.ROOT).replace(' ', '_').replace("'", "").replace('-', '_');
            if (t.equals(k) || t.equals("THE_" + k)) return c;
        }
        return null;
    }

    public static final int ALL = Civic.values().length;

    /**
     * [perks] The town's ethos (Ethos, its identity) leaning the research: what a branch is worth to this town's ways,
     * in points on the leader's scales. Perks.joinIdentity sets it to Perks.ethosBranch; a test may lean it its own way.
     */
    public static volatile java.util.function.ToIntBiFunction<UUID, Branch> ETHOS = (v, b) -> 0;

    // ------------------------------------------------------------------ the numbers (each slight)

    /** Common Tools: every trade's pace, in percent. */
    static final int TOOLS_PACE = 3;
    /** Guild Charters, Crop Rotation, Herd Books: their trades' pace, in percent. */
    static final int TRADE_PACE = 5;
    /** Builders' Guild: the builders' pace, in percent. */
    static final int BUILD_PACE = 10;
    /** Apprentice Halls: more experience, in percent. */
    static final int MORE_XP = 10;
    /** Master Workshops: one use of a tool in this many is not charged to it (a fifth slower wear). */
    static final int SPARED_ONE_IN = 5;
    /** Seed Exchange: one harvested crop in this many gives one more. */
    static final int SEED_ONE_IN = 10;
    /** Granaries: the time between meals, in percent of the usual (a tenth less eaten). */
    static final int MEALS = 111;
    /** Home Loans: a house's price, in percent. */
    static final int PRICE = 90;
    /** Housing Fund: the treasury adds one coin for every this many put by. */
    static final int FUND_ONE_IN = 10;
    /** Feast Days: contentment, in points. */
    static final int FEAST = 3;
    /** Tavern Songs and the Rest Day Charter: mood, in points. */
    static final int SONGS = 3, RESTED = 2;
    /** Rest Day Charter: a break's length, in percent. */
    static final int REST = 90;
    /** Market Charter: the takings, in percent. */
    static final int TAKINGS = 105;
    /** Paved Roads: walking speed, a share more. */
    static final double ROADS = 0.05;
    /** Watch Drills: a guard's armour and its blow, in points. */
    static final double DRILL_ARMOUR = 1.0, DRILL_HIT = 1.0;
    /** Counting House: one coin of every this many paid in wages comes back. */
    static final int COUNTING_ONE_IN = 20;

    // [perks] The new civics' numbers.
    /** Guild Monopolies, the Free Market: a crafted good's price at the counter, in percent. */
    static final int MONOPOLY_PRICE = 110, FREE_PRICE = 90;
    /** Guild Monopolies: one use of a tool in this many spared besides; the Great Forge, one in this many. */
    static final int MONOPOLY_SPARED = 10, FORGE_SPARED = 4;
    /** The Free Market, Plain Living, the Craftsmen's Endowment: pace, in percent. */
    static final int FREE_PACE = 6, PLAIN_PACE = 3, ENDOWED_PACE = 3;
    /** The Great Forge: every trade, and the smith and the smelter besides. */
    static final int FORGE_PACE = 4, FORGE_SMITHS = 10;
    /** The Open Granary's contentment; Private Larders' wage and food price (percent), and the poor's spirits. */
    static final int GRANARY_CONTENT = 2, LARDER_WAGE = 5, LARDER_FOOD = 120, LARDER_POOR = 4;
    /** The Sky Garden: farmers' pace, one harvest in this many, and mood. */
    static final int GARDEN_PACE = 8, GARDEN_ONE_IN = 5, GARDEN_MOOD = 3;
    /** The Observer Pattern Books: machines and rails, in percent quicker. Turnpikes: walking, and roads and bridges. */
    static final int PATTERN_PERCENT = 25, PIKE_WORKS = 50;
    static final double PIKE_WALK = 0.04, GATE_WALK = 0.03;
    /** The Clockwork Gate: building, in percent; the works twice as fast. */
    static final int GATE_BUILD = 15, GATE_WORKS = 100;
    /** Patronage of the Arts: contentment, and a busker's chance of a coin. Plain Living: contentment, breaks. */
    static final int PATRON_CONTENT = 3, PLAIN_CONTENT = -2, PLAIN_REST = 95;
    static final double PATRON_TIPS = 0.12;
    /** The Founders' Colossus: contentment and mood. */
    static final int COLOSSUS_CONTENT = 5, COLOSSUS_MOOD = 2;
    /** Open Borders and Tariffs: takings, in percent; a caravan's lots; the neighbours' feeling, a day. */
    static final int BORDERS_TAKINGS = 103, TARIFF_TAKINGS = 106, BORDERS_LOTS = 2, BAZAAR_LOTS = 4, BAZAAR_TAKINGS = 110;
    /** The Watch House: a guard's health. The Standing Army: its blow and its pay. The Militia and the Stone Walls: armour. */
    static final double WATCH_HEALTH = 4.0, ARMY_HIT = 1.0, MILITIA_ARMOUR = 1.0, WALLS_ARMOUR = 2.0, ARENA_HIT = 2.0, ARENA_ARMOUR = 2.0;
    static final int ARMY_PAY = 1;
    /** The Fletchers' Charter: arrows more in a quiver. Earthworks: sight from the wall, the hunters' pace. */
    static final int QUIVER = 16, EARTHWORK_HUNT = 5;
    static final double EARTHWORK_SIGHT = 6.0;
    /** The Arena: contentment. */
    static final int ARENA_CONTENT = 3;
    /** Primers and the Scholars' Endowment: a school lesson, in percent more. */
    static final int PRIMERS = 25, SCHOLARS_SCHOOL = 20;
    /** The Scholars' Endowment and the Surveyors' Office: research points a morning; the scouts' range, in percent. */
    static final int SCHOLARS_POINTS = 2, SURVEY_POINTS = 1, SURVEY_RANGE = 25;
    /** The Grand Library and the Observatory: research, in percent; the Library's experience. */
    static final int LIBRARY_RESEARCH = 50, OBSERVATORY_RESEARCH = 25, LIBRARY_XP = 10, ENDOWED_XP = 10;
    /** Vespers, the Almshouse, the Cathedral: spirits. Remembrance: grief's weight, in percent. */
    static final int VESPERS = 3, ALMS = 4, CATHEDRAL_FLOOR = 30, CATHEDRAL_CONTENT = 4, REMEMBRANCE = 50;
    /** The Fishwives, the Great Lighthouse: the fishers' pace. */
    static final int FISHWIVES = 6, LIGHTHOUSE_PACE = 10;
    /** Diving Bells: breath (the oxygen bonus: one more is twice as long under water). */
    static final double BELLS_BREATH = 1.0;
    /** Herbals, the Alchemists, the Observatory: the brewer's and enchanter's pace. */
    static final int HERBALS = 6, ALCHEMY = 8, OBSERVATORY_ARCANE = 15;

    private static final ResourceLocation ROADS_ID = ResourceLocation.fromNamespaceAndPath("mc_assistant", "civic_paved_roads");
    private static final ResourceLocation DRILL_ARMOUR_ID = ResourceLocation.fromNamespaceAndPath("mc_assistant", "civic_watch_drills_armour");
    private static final ResourceLocation DRILL_HIT_ID = ResourceLocation.fromNamespaceAndPath("mc_assistant", "civic_watch_drills_hit");
    // [perks] The new civics' marks on the folk: the walk, armour, blow, health and breath they add.
    private static final ResourceLocation WALK_ID = ResourceLocation.fromNamespaceAndPath("mc_assistant", "civic_works_walk");
    private static final ResourceLocation ARMOUR_ID = ResourceLocation.fromNamespaceAndPath("mc_assistant", "civic_defence_armour");
    private static final ResourceLocation HIT_ID = ResourceLocation.fromNamespaceAndPath("mc_assistant", "civic_defence_hit");
    private static final ResourceLocation HEALTH_ID = ResourceLocation.fromNamespaceAndPath("mc_assistant", "civic_watch_house");
    private static final ResourceLocation BREATH_ID = ResourceLocation.fromNamespaceAndPath("mc_assistant", "civic_diving_bells");

    // ------------------------------------------------------------------ where it is kept

    static final String DONE = "civic.done", NOW = "civic.now", POINTS = "civic.points", WHY = "civic.why",
        BY = "civic.by", SINCE = "civic.since", DAY = "civic.day", FUND = "civic.fund", COUNTING = "civic.counting",
        CARRY = "civic.carry";

    /** The civics done, as last read from the Ledger: kept with the note it was read from, so a change is seen at once. */
    private record Read(String raw, Map<Civic, Long> done) {}

    private static final Map<UUID, Read> READ = new ConcurrentHashMap<>();

    public static void resetForTests() {
        READ.clear();
    }

    /**
     * The civics this village has done, in the order it did them, with the day each was done.
     * Asked on every pace sum of every worker, so it is read once and kept until the note changes.
     */
    public static Map<Civic, Long> done(@Nullable UUID village) {
        if (village == null) return Map.of();
        String raw = Ledger.note(village, DONE);
        if (raw == null || raw.isEmpty()) return Map.of();
        Read r = READ.get(village);
        if (r != null && r.raw().equals(raw)) return r.done();
        Map<Civic, Long> m = new LinkedHashMap<>();
        for (String part : raw.split(",")) {
            String[] kv = part.split(":");
            Civic c = byKey(kv[0]);
            if (c == null) continue;
            long day = -1L;
            try { if (kv.length > 1) day = Long.parseLong(kv[1]); } catch (NumberFormatException ignored) { }
            m.put(c, day);
        }
        Map<Civic, Long> kept = Collections.unmodifiableMap(m);
        READ.put(village, new Read(raw, kept));
        return kept;
    }

    private static void saveDone(UUID village, Map<Civic, Long> done) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<Civic, Long> e : done.entrySet()) {
            if (sb.length() > 0) sb.append(',');
            sb.append(e.getKey().name()).append(':').append(e.getValue());
        }
        if (sb.length() == 0) Ledger.forget(village, DONE);
        else Ledger.note(village, DONE, sb.toString());
    }

    /** Has the village done this civic? */
    public static boolean has(@Nullable UUID village, Civic c) {
        return village != null && done(village).containsKey(c);
    }

    /** What the village is studying now, or null. */
    @Nullable
    public static Civic current(@Nullable UUID village) {
        return village == null ? null : byKey(Ledger.note(village, NOW));
    }

    /** Research points in hand toward the one being studied. */
    public static int points(@Nullable UUID village) {
        return village == null ? 0 : parse(Ledger.note(village, POINTS));
    }

    /**
     * [perks] Is this civic closed to the village for good, or for now: the other of its pair done
     * (or being studied), or, for a wonder, raised already by another town?
     */
    public static boolean locked(@Nullable UUID village, Civic c) {
        if (village == null) return true;
        Civic r = c.rival();
        if (r != null && (has(village, r) || current(village) == r)) return true;
        return c.wonder() && !has(village, c) && Wonders.raisedElsewhere(village, c);
    }

    /** Why it is closed, in a few words ("Free Market chosen instead", "raised by Ashford first"), or "". */
    public static String lockedWhy(@Nullable UUID village, Civic c) {
        if (village == null) return "";
        Civic r = c.rival();
        if (r != null && has(village, r)) return r.title + " chosen instead";
        if (r != null && current(village) == r) return r.title + " being studied instead";
        if (c.wonder() && !has(village, c) && Wonders.raisedElsewhere(village, c)) return "raised by " + Wonders.ownerName(c) + " first";
        return "";
    }

    /** Is this civic open to study: not done, not closed, and one of the tier before it in its branch done? */
    public static boolean open(@Nullable UUID village, Civic c) {
        if (village == null || has(village, c) || locked(village, c)) return false;
        List<Civic> b = c.befores();
        if (b.isEmpty()) return true;
        for (Civic x : b) if (has(village, x)) return true;
        return false;
    }

    /** Every civic open to study now, in the tree's order. */
    public static List<Civic> openNow(@Nullable UUID village) {
        List<Civic> out = new ArrayList<>();
        for (Civic c : Civic.values()) if (open(village, c)) out.add(c);
        return out;
    }

    /** Is there nothing left it could ever study: everything done, or closed by a choice or another town's wonder? */
    public static boolean finished(@Nullable UUID village) {
        if (village == null) return false;
        for (Civic c : Civic.values()) if (!has(village, c) && !locked(village, c)) return false;
        return true;
    }

    /** "the Guild Monopolies, the Open Granary and the Militia": the ways it chose where it had to choose. */
    public static List<Civic> ways(@Nullable UUID village) {
        List<Civic> out = new ArrayList<>();
        for (Civic c : done(village).keySet()) if (c.rival() != null) out.add(c);
        return out;
    }

    /** The prerequisite in words: "Common Tools", or "Guild Monopolies or the Free Market". */
    static String needsWords(Civic c) {
        List<String> names = new ArrayList<>();
        for (Civic b : c.befores()) names.add(b.title);
        return String.join(" or ", names);
    }

    // ------------------------------------------------------------------ the effects, at their hooks

    /** Is the wonder of this civic standing, and this town's? */
    static boolean wonderOf(@Nullable UUID village, Civic c) {
        return village != null && Wonders.owns(village, c);
    }

    /**
     * What the city's research adds to the pace of this trade's work, in percent (VillageFolkEntity
     * .skillWorkPercent, into every pace sum): Common Tools 3 for every trade; Guild Charters 5 for the
     * crafts; Crop Rotation 5 for farmers; Herd Books 5 for ranchers, hunters, fishers and beekeepers.
     * [perks] And the new: the Free Market 6 for the crafts, Plain Living 3 for everybody, the
     * Craftsmen's Endowment 3 for the crafts, Earthworks 5 for the hunters, the Fishwives 6 for the
     * fishers, Herbals 6 for the brewer and the enchanter, the Alchemists 8 for the brewer; and a wonder
     * the town has raised (the Great Forge, the Sky Garden, the Great Lighthouse, the Observatory).
     */
    public static int workPercent(@Nullable UUID village, StationTask trade) {
        if (village == null || trade == null || trade == StationTask.NONE) return 0;
        Map<Civic, Long> d = done(village);
        if (d.isEmpty()) return 0;
        int p = 0;
        if (d.containsKey(Civic.COMMON_TOOLS)) p += TOOLS_PACE;
        if (d.containsKey(Civic.GUILD_CHARTERS) && crafts(trade)) p += TRADE_PACE;
        if (d.containsKey(Civic.CROP_ROTATION) && trade == StationTask.FARM) p += TRADE_PACE;
        if (d.containsKey(Civic.HERD_BOOKS) && herds(trade)) p += TRADE_PACE;
        // [perks]
        if (d.containsKey(Civic.FREE_MARKET) && crafts(trade)) p += FREE_PACE;
        if (d.containsKey(Civic.PLAIN_LIVING)) p += PLAIN_PACE;
        if (d.containsKey(Civic.CRAFTSMENS_ENDOWMENT) && crafts(trade)) p += ENDOWED_PACE;
        if (d.containsKey(Civic.EARTHWORKS) && trade == StationTask.HUNT) p += EARTHWORK_HUNT;
        if (d.containsKey(Civic.FISHWIVES_GUILD) && trade == StationTask.FISH) p += FISHWIVES;
        if (d.containsKey(Civic.HERBALS) && (trade == StationTask.BREW || trade == StationTask.ENCHANT)) p += HERBALS;
        if (d.containsKey(Civic.ALCHEMISTS_GUILD) && trade == StationTask.BREW) p += ALCHEMY;
        if (d.containsKey(Civic.OBSERVER_PATTERN_BOOKS) && machines(trade)) p += PATTERN_PERCENT;
        if (d.containsKey(Civic.GREAT_FORGE) && wonderOf(village, Civic.GREAT_FORGE)) {
            p += FORGE_PACE;
            if (trade == StationTask.SMITH || trade == StationTask.SMELT) p += FORGE_SMITHS;
        }
        if (trade == StationTask.FARM && d.containsKey(Civic.SKY_GARDEN) && wonderOf(village, Civic.SKY_GARDEN)) p += GARDEN_PACE;
        if (trade == StationTask.FISH && d.containsKey(Civic.GREAT_LIGHTHOUSE) && wonderOf(village, Civic.GREAT_LIGHTHOUSE)) p += LIGHTHOUSE_PACE;
        if ((trade == StationTask.BREW || trade == StationTask.ENCHANT) && d.containsKey(Civic.OBSERVATORY)
                && wonderOf(village, Civic.OBSERVATORY)) p += OBSERVATORY_ARCANE;
        return p;
    }

    /** The crafts the Guild Charters cover: the smith, tailor, cook, shopkeeper, brewer, enchanter and smelter. */
    static boolean crafts(StationTask t) {
        return switch (t) {
            case SMITH, TAILOR, COOK, SHOP, BREW, ENCHANT, SMELT -> true;
            default -> false;
        };
    }

    /** The trades the Herd Books cover: ranchers, hunters, fishers and beekeepers. */
    static boolean herds(StationTask t) {
        return switch (t) {
            case RANCH, HUNT, FISH, BEEKEEP -> true;
            default -> false;
        };
    }

    /** Builders' Guild: what it adds to the pace a builder lays its blocks at, in percent (AssistantEntity.buildPaceTicks). */
    public static int buildPercent(@Nullable UUID village) {
        return has(village, Civic.BUILDERS_GUILD) ? BUILD_PACE : 0;
    }

    /**
     * [perks] The Clockwork Gate's building pace, in percent: the wonder's, kept apart from the
     * Builders' Guild's so each is seen for itself (Perks.buildPercent adds it in).
     */
    static int gateBuildPercent(@Nullable UUID village) {
        return has(village, Civic.CLOCKWORK_GATE) && wonderOf(village, Civic.CLOCKWORK_GATE) ? GATE_BUILD : 0;
    }

    /**
     * Apprentice Halls: the experience a piece of work earns, in hundredths of a point, with a tenth
     * more (AssistantEntity.note). A block of stone earns a few hundredths, so the odd hundredth is
     * a matter of chance in proportion: over a shift it comes to a tenth more, neither more nor less.
     * [perks] The Craftsmen's Endowment adds a tenth more again, and the Grand Library a tenth.
     */
    public static int moreXp(@Nullable UUID village, int cents, RandomSource random) {
        if (cents <= 0 || village == null) return cents;
        Map<Civic, Long> d = done(village);
        int pct = (d.containsKey(Civic.APPRENTICE_HALLS) ? MORE_XP : 0) + (d.containsKey(Civic.CRAFTSMENS_ENDOWMENT) ? ENDOWED_XP : 0)
            + (d.containsKey(Civic.GRAND_LIBRARY) && wonderOf(village, Civic.GRAND_LIBRARY) ? LIBRARY_XP : 0);
        if (pct <= 0) return cents;
        int extra = cents * pct;                           // in hundredths of a hundredth
        return cents + extra / 100 + (random.nextInt(100) < extra % 100 ? 1 : 0);
    }

    /**
     * Master Workshops: is this use of a tool spared the wear (AssistantEntity.damageHeldTool)? One in
     * five. [perks] Guild Monopolies spare one in ten besides, and the Great Forge one in four.
     */
    public static boolean sparesTool(@Nullable UUID village, RandomSource random) {
        if (village == null) return false;
        Map<Civic, Long> d = done(village);
        if (d.containsKey(Civic.MASTER_WORKSHOPS) && random.nextInt(SPARED_ONE_IN) == 0) return true;
        if (d.containsKey(Civic.GUILD_MONOPOLIES) && random.nextInt(MONOPOLY_SPARED) == 0) return true;
        return d.containsKey(Civic.GREAT_FORGE) && wonderOf(village, Civic.GREAT_FORGE) && random.nextInt(FORGE_SPARED) == 0;
    }

    /**
     * Seed Exchange: a crop just harvested (FarmGoal.doHarvest), and one in ten gives one more of
     * what it grows (wheat, a carrot, a potato, a beetroot), into the farmer's pack — counted as its
     * making, as the rest of the harvest is. [perks] The Sky Garden: one in five gives one more again.
     */
    public static void seedExchange(AssistantEntity a, Block crop) {
        if (!(a instanceof VillageFolkEntity f)) return;
        UUID v = f.ownerId();
        int more = 0;
        if (has(v, Civic.SEED_EXCHANGE) && a.getRandom().nextInt(SEED_ONE_IN) == 0) more++;
        if (has(v, Civic.SKY_GARDEN) && wonderOf(v, Civic.SKY_GARDEN) && a.getRandom().nextInt(GARDEN_ONE_IN) == 0) more++;
        if (more == 0) return;
        Item produce = produceOf(crop);
        if (produce == null) return;
        ItemStack one = new ItemStack(produce, more);
        ItemStack left = a.insertItem(one.copy());
        Economy.gathered(a, one, more - left.getCount());
        if (!left.isEmpty()) a.spawnAtLocation(left);
    }

    /** What a crop gives at harvest: wheat, a carrot, a potato, a beetroot; null for anything else. */
    @Nullable
    static Item produceOf(Block crop) {
        return crop == Blocks.WHEAT ? Items.WHEAT : crop == Blocks.CARROTS ? Items.CARROT
            : crop == Blocks.POTATOES ? Items.POTATO : crop == Blocks.BEETROOTS ? Items.BEETROOT : null;
    }

    /** Granaries: the time between a folk's meals, in percent of the usual (VillageFolkEntity.traitUpkeepPercent).
     *  [perks] And a Provider in office or a legacy of granaries (Reigns), on top. */
    public static int mealPercent(@Nullable UUID village) {
        int base = has(village, Civic.GRANARIES) ? MEALS : 100;
        return base * Reigns.mealPercent(village) / 100;
    }

    /**
     * Cheap Homes: the rent the village asks for a house (Homes.rent), given what it would ask
     * without it: a fifth off. Most rents are a coin or two a day, and a fifth off a coin is nothing,
     * so a rent of one or two coins stays as it is and is let off one payday in five instead
     * ({@link #rentFreeToday}); a rent of three or more (a manor, a two-storey house in a city) is
     * four-fifths of itself, rounded. Either way a tenant pays a fifth less over the week.
     */
    public static int rent(@Nullable UUID village, int rent) {
        if (rent <= 2 || !has(village, Civic.CHEAP_HOMES)) return rent;
        return Math.max(2, Math.round(rent * 0.8F));
    }

    /** Cheap Homes: is a rent of one or two coins (before the cut) let off today, one payday in five (Homes.tenants)? */
    public static boolean rentFreeToday(@Nullable UUID village, int baseRent, long day) {
        return baseRent <= 2 && Math.floorMod(day, 5L) == 0 && has(village, Civic.CHEAP_HOMES);
    }

    /** Home Loans: what a house sells for, in percent (Homes.price). */
    public static int pricePercent(@Nullable UUID village) {
        return has(village, Civic.HOME_LOANS) ? PRICE : 100;
    }

    /**
     * Housing Fund: a renting household put {@code put} coins by toward buying its house today
     * (Homes.tenants); the treasury adds a coin for every ten, as far as {@code room} (what is
     * still wanted of the price) and its own purse go. The odd coins are carried over from one
     * household's saving to the next, so a village of small savers is matched as fully as a rich one.
     * Returns what the treasury added.
     */
    public static int housingFund(@Nullable UUID village, int put, int room) {
        if (village == null || put <= 0 || room <= 0 || !has(village, Civic.HOUSING_FUND)) return 0;
        int carry = parse(Ledger.note(village, FUND)) + put;
        Ledger.note(village, FUND, Integer.toString(carry % FUND_ONE_IN));
        int match = Math.min(room, carry / FUND_ONE_IN);
        if (match <= 0) return 0;
        int paid = Ledger.takeCoins(village, match);
        Economy.spent(village, paid);
        return paid;
    }

    /** Feast Days: what it adds to the village's contentment (Contentment.compute). [perks] The rest is Perks.contentment. */
    public static int contentment(@Nullable UUID village) {
        return has(village, Civic.FEAST_DAYS) ? FEAST : 0;
    }

    /**
     * [perks] The new civics' and the wonders' part of the town's contentment, with the words for it
     * (Perks.contentment, into Contentment.compute): the Open Granary +2, Patronage +3, Plain Living -2,
     * the Colossus +5, the Arena +3, the Cathedral +4.
     */
    static int contentment(@Nullable UUID village, List<String> good, List<String> bad) {
        if (village == null) return 0;
        Map<Civic, Long> d = done(village);
        if (d.isEmpty()) return 0;
        int c = 0;
        if (d.containsKey(Civic.OPEN_GRANARY)) { c += GRANARY_CONTENT; good.add("the granary open to all"); }
        if (d.containsKey(Civic.PATRONAGE)) { c += PATRON_CONTENT; good.add("the arts well kept"); }
        if (d.containsKey(Civic.PLAIN_LIVING)) { c += PLAIN_CONTENT; bad.add("plain living"); }
        if (wonderOf(village, Civic.FOUNDERS_COLOSSUS) && d.containsKey(Civic.FOUNDERS_COLOSSUS)) { c += COLOSSUS_CONTENT; good.add("the Colossus over the square"); }
        if (wonderOf(village, Civic.ARENA) && d.containsKey(Civic.ARENA)) { c += ARENA_CONTENT; good.add("the games at the Arena"); }
        if (wonderOf(village, Civic.CATHEDRAL) && d.containsKey(Civic.CATHEDRAL)) { c += CATHEDRAL_CONTENT; good.add("the Cathedral"); }
        return c;
    }

    /** Tavern Songs and the Rest Day Charter: what they add to every folk's mood (VillageFolkEntity.refreshMood).
     *  [perks] And the Sky Garden and the Colossus, while they stand. */
    public static int moodBonus(@Nullable UUID village) {
        if (village == null) return 0;
        Map<Civic, Long> d = done(village);
        if (d.isEmpty()) return 0;
        int m = (d.containsKey(Civic.TAVERN_SONGS) ? SONGS : 0) + (d.containsKey(Civic.REST_DAY_CHARTER) ? RESTED : 0);
        if (d.containsKey(Civic.SKY_GARDEN) && wonderOf(village, Civic.SKY_GARDEN)) m += GARDEN_MOOD;
        if (d.containsKey(Civic.FOUNDERS_COLOSSUS) && wonderOf(village, Civic.FOUNDERS_COLOSSUS)) m += COLOSSUS_MOOD;
        return m;
    }

    /** What a folk says of it, asked how it is (FolkTalk.reason, "civic"). */
    public static String moodWords(VillageFolkEntity f) {
        UUID v = f.ownerId();
        boolean songs = has(v, Civic.TAVERN_SONGS), rest = has(v, Civic.REST_DAY_CHARTER);
        RandomSource r = f.getRandom();
        if (wonderOf(v, Civic.SKY_GARDEN) && r.nextInt(3) == 0) return "Have you been up the Sky Garden? The flowers up there!";
        if (wonderOf(v, Civic.FOUNDERS_COLOSSUS) && r.nextInt(3) == 0) return "Every time I pass the Colossus I stand a little taller.";
        if (songs && rest) return FolkTalk.pick(r, "Songs of an evening and a proper rest by the charter — I'm the better for both.",
            "Between the tavern songs and the charter's rest, life's good here.");
        if (songs) return FolkTalk.pick(r, "There's singing at the tavern of an evening. Lifts the heart.",
            "I know all the tavern songs now. Can't help humming them.");
        if (rest) return "The charter's rest does me good: rested, not idle.";
        return "";
    }

    /** Healers: how long a folk lives, given the years it would have (VillageFolkEntity.lifespan): a tenth more.
     *  [perks] And the Cathedral a tenth more again. */
    public static int lifespan(@Nullable UUID village, int years) {
        int y = has(village, Civic.HEALERS) ? years + years / 10 : years;
        if (has(village, Civic.CATHEDRAL) && wonderOf(village, Civic.CATHEDRAL)) y += years / 10;
        return y;
    }

    /** Rest Day Charter: a break's length, in percent of what it would be (Leader.restScale). [perks] Plain Living a twentieth off. */
    public static int restPercent(@Nullable UUID village) {
        int p = has(village, Civic.REST_DAY_CHARTER) ? REST : 100;
        if (has(village, Civic.PLAIN_LIVING)) p = p * PLAIN_REST / 100;
        return p;
    }

    /**
     * Market Charter: the village's takings and the market-day traders' prices, in percent (Market.takings,
     * Market.sellSurplus). [perks] Open Borders 3% more, Tariffs 6%, the Grand Bazaar 10%; and a Merchant
     * in office, its skills, its legacies and a Silver Tongue at the counter (Perks.takingsPercent).
     */
    public static int takingsPercent(@Nullable UUID village) {
        if (village == null) return 100;
        Map<Civic, Long> d = done(village);
        int p = d.containsKey(Civic.MARKET_CHARTER) ? TAKINGS : 100;
        if (d.containsKey(Civic.OPEN_BORDERS)) p = p * BORDERS_TAKINGS / 100;
        if (d.containsKey(Civic.TARIFFS)) p = p * TARIFF_TAKINGS / 100;
        if (d.containsKey(Civic.GRAND_BAZAAR) && wonderOf(village, Civic.GRAND_BAZAAR)) p = p * BAZAAR_TAKINGS / 100;
        return p * Perks.takingsPercent(village) / 100;
    }

    /**
     * Counting House: this payday paid {@code paid} coins in wages (Market.payWages); a twentieth of
     * it comes back to the treasury (the folk keep every coin of theirs). The odd coins are carried to
     * the next payday. Returns what comes back. [perks] The Grand Bazaar and a Steward in office
     * (Reigns) bring back another twentieth each.
     */
    public static int countingHouse(@Nullable UUID village, int paid) {
        if (village == null || paid <= 0) return 0;
        int twentieths = (has(village, Civic.COUNTING_HOUSE) ? 1 : 0)
            + (has(village, Civic.GRAND_BAZAAR) && wonderOf(village, Civic.GRAND_BAZAAR) ? 1 : 0) + Reigns.countingTwentieths(village);
        if (twentieths <= 0) return 0;
        int carry = parse(Ledger.note(village, COUNTING)) + paid * twentieths;
        Ledger.note(village, COUNTING, Integer.toString(carry % COUNTING_ONE_IN));
        return carry / COUNTING_ONE_IN;
    }

    // ------------------------------------------------------------------ [perks] the new civics' own hooks

    /** Guild Monopolies and the Free Market: a crafted good's price at the counter, in percent (Purchases.priceEach). */
    public static int craftPricePercent(@Nullable UUID village, ItemStack one) {
        if (village == null || !craftedGood(one)) return 100;
        if (has(village, Civic.GUILD_MONOPOLIES)) return MONOPOLY_PRICE;
        if (has(village, Civic.FREE_MARKET)) return FREE_PRICE;
        return 100;
    }

    /** What the guilds make: a tool, a blade, a piece of armour, a bow (anything that wears), a potion, an enchanted book. */
    static boolean craftedGood(ItemStack s) {
        if (s.isEmpty()) return false;
        if (s.isDamageableItem()) return true;
        return s.is(Items.POTION) || s.is(Items.SPLASH_POTION) || s.is(Items.LINGERING_POTION) || s.is(Items.ENCHANTED_BOOK);
    }

    /** The Open Granary: is a meal out of the stores free to anybody, wherever it stands (Purchases.pays, Meals)? */
    public static boolean openGranary(@Nullable UUID village) {
        return has(village, Civic.OPEN_GRANARY);
    }

    /** Private Larders: food bought at the counter, in percent of its price (Purchases.priceEach). */
    public static int foodPricePercent(@Nullable UUID village) {
        return has(village, Civic.PRIVATE_LARDERS) ? LARDER_FOOD : 100;
    }

    /** Private Larders: a wage, in percent (Wealth.wage, through Perks.wageExtra). */
    static int wagePercent(@Nullable UUID village) {
        return has(village, Civic.PRIVATE_LARDERS) ? 100 + LARDER_WAGE : 100;
    }

    /** The Standing Army: a guard's extra pay, a day. */
    static int guardPay(@Nullable UUID village) {
        return has(village, Civic.STANDING_ARMY) ? ARMY_PAY : 0;
    }

    /** The Standing Army and the Militia: guards more (or fewer) on the town's books (Villages.target, through Perks.share). */
    static int guardsMore(@Nullable UUID village) {
        if (has(village, Civic.STANDING_ARMY)) return 1;
        if (has(village, Civic.MILITIA)) return -1;
        return 0;
    }

    /** The Fletchers' Charter: arrows more in a guard's quiver (WatchKit.fit, through Perks.quiver). */
    static int quiver(@Nullable UUID village) {
        return has(village, Civic.FLETCHERS_CHARTER) ? QUIVER : 0;
    }

    /** Earthworks and Hedges: blocks further a guard on the wall picks its mark (FolkSkills.sightBonus, through Perks). */
    static double earthworksSight(@Nullable UUID village) {
        return has(village, Civic.EARTHWORKS) ? EARTHWORK_SIGHT : 0.0;
    }

    /** Patronage of the Arts: a busker's better chance of a coin from a listener (Buskers.tip, through Perks). */
    static double patronTips(@Nullable UUID village) {
        return has(village, Civic.PATRONAGE) ? PATRON_TIPS : 0.0;
    }

    /** The Founders' Colossus: does nobody leave this town for another (Contentment, through Perks)? */
    public static boolean nobodyLeaves(@Nullable UUID village) {
        return has(village, Civic.FOUNDERS_COLOSSUS) && wonderOf(village, Civic.FOUNDERS_COLOSSUS);
    }

    /** Open Borders and the Grand Bazaar: lots more a caravan carries (Caravans.load, through Perks.caravanLots). */
    static int caravanLots(@Nullable UUID village) {
        int n = has(village, Civic.OPEN_BORDERS) ? BORDERS_LOTS : 0;
        if (has(village, Civic.GRAND_BAZAAR) && wonderOf(village, Civic.GRAND_BAZAAR)) n += BAZAAR_LOTS;
        return n;
    }

    /** Open Borders and Tariffs: how this town's ways sit with a neighbour today (Diplomacy.daily, through Perks.warmth). */
    static int borderWarmth(@Nullable UUID village, long day) {
        if (has(village, Civic.OPEN_BORDERS)) return 1;
        if (has(village, Civic.TARIFFS) && Math.floorMod(day, 2L) == 1) return -1;
        return 0;
    }

    /**
     * The Observer Pattern Books, the Turnpikes and the Clockwork Gate: how many more steps of the works a
     * hand lays a visit, in percent ({@code kind}: "rails", "roads", "bridges"; Railways, Roads, Bridges).
     */
    public static int worksPercent(@Nullable UUID village, String kind) {
        if (village == null) return 0;
        int p = 0;
        if (kind.equals("rails") && has(village, Civic.OBSERVER_PATTERN_BOOKS)) p += PATTERN_PERCENT;
        if (kind.equals("rails") && FolkSkills.atWork(village, FolkSkills.Knack.TINKERER)) p += FolkSkills.TINKER_RAILS;
        if (!kind.equals("rails") && has(village, Civic.TURNPIKES)) p += PIKE_WORKS;
        if (has(village, Civic.CLOCKWORK_GATE) && wonderOf(village, Civic.CLOCKWORK_GATE)) p += GATE_WORKS;
        return p;
    }

    /** So many steps a visit, the works' percent on top (never fewer than it was). */
    public static int worksSteps(@Nullable UUID village, String kind, int steps) {
        return steps + steps * worksPercent(village, kind) / 100;
    }

    /**
     * The trade that builds the town's machines: the redstone engineer, by its name, if the game has the trade (the
     * Observer Pattern Books quicken it a quarter, in {@link #workPercent}; the railway's rails take the same through
     * {@link #worksPercent} whether it has or not).
     */
    static boolean machines(StationTask t) {
        return t.name().contains("REDSTONE");
    }

    /** Primers and the Scholars' Endowment: a school lesson, in percent more (School). */
    public static int schoolPercent(@Nullable UUID village) {
        return (has(village, Civic.PRIMERS) ? PRIMERS : 0) + (has(village, Civic.SCHOLARS_ENDOWMENT) ? SCHOLARS_SCHOOL : 0);
    }

    /** The Surveyors' Office: the scouts' range, in percent more (Scouts). */
    public static int scoutPercent(@Nullable UUID village) {
        return has(village, Civic.SURVEYORS_OFFICE) ? SURVEY_RANGE : 0;
    }

    /** The Printing Press: is each new library book printed once more (Library.finish, through Perks)? */
    static boolean printingPress(@Nullable UUID village) {
        return has(village, Civic.PRINTING_PRESS);
    }

    /** Saints' Days: is today a saint's feast (the second day of each season; Gatherings.tonight)? */
    public static boolean saintsDay(@Nullable UUID village, long day) {
        return day > 0 && has(village, Civic.SAINTS_DAYS) && Seasons.dayInSeason(village, day) == 2 && day % 7 != 6;
    }

    /** The Navigator's Guild and the Great Lighthouse: does the fleet sail in the rain (Fleet.keptIn)? */
    public static boolean sailsInRain(@Nullable UUID village) {
        return has(village, Civic.NAVIGATORS_GUILD) || has(village, Civic.GREAT_LIGHTHOUSE) && wonderOf(village, Civic.GREAT_LIGHTHOUSE);
    }

    /** Shipwrights: boats more in the fleet (Fleet.boatsWanted). */
    public static int extraBoats(@Nullable UUID village) {
        return has(village, Civic.SHIPWRIGHTS) ? 1 : 0;
    }

    /** The Great Lighthouse: does this haul land a fish more (one in three; Fleet.catchOne, through Perks)? */
    static boolean lighthouseFish(@Nullable UUID village, RandomSource r) {
        return has(village, Civic.GREAT_LIGHTHOUSE) && wonderOf(village, Civic.GREAT_LIGHTHOUSE) && r.nextInt(3) == 0;
    }

    /** Blaze Wardens: does fire do half its harm to a Nether-goer of this town (PerkEvents, Nether)? */
    public static boolean blazeWarded(@Nullable UUID village) {
        return has(village, Civic.BLAZE_WARDENS);
    }

    /** Nether Charts: days between the runners' runs (NetherRunners.rested): one with the charts, two without. */
    public static int netherGap(@Nullable UUID village) {
        return has(village, Civic.NETHER_CHARTS) ? 1 : 2;
    }

    /** Nether Charts: the run's walking reckoned, in percent (NetherPlan.plan): three quarters with the charts. */
    public static int netherWalkPercent(@Nullable UUID village) {
        return has(village, Civic.NETHER_CHARTS) ? 75 : 100;
    }

    /** The Alchemists' Guild: blaze powders ground from a rod (Crafts.brew). */
    public static int powderPerRod(@Nullable UUID village) {
        return has(village, Civic.ALCHEMISTS_GUILD) ? 3 : 2;
    }

    /** The Observatory: does this ore give one more (one in ten; FolkSkills.oreLuck)? */
    static boolean observatoryLuck(@Nullable UUID village, RandomSource r) {
        return has(village, Civic.OBSERVATORY) && wonderOf(village, Civic.OBSERVATORY) && r.nextInt(10) == 0;
    }

    /** Vespers: spirits in hard times; the Almshouse: the poor's; the Cathedral: the floor; Remembrance: grief's weight in percent. */
    static int vespers(@Nullable UUID village) {
        return has(village, Civic.VESPERS) ? VESPERS : 0;
    }

    static int alms(@Nullable UUID village) {
        return has(village, Civic.ALMSHOUSE) ? ALMS : 0;
    }

    static int moodFloor(@Nullable UUID village) {
        return has(village, Civic.CATHEDRAL) && wonderOf(village, Civic.CATHEDRAL) ? CATHEDRAL_FLOOR : 0;
    }

    static int griefPercent(@Nullable UUID village) {
        return has(village, Civic.REMEMBRANCE) ? REMEMBRANCE : 100;
    }

    /** Private Larders: the poor's spirits, lower. */
    static int larderPoor(@Nullable UUID village) {
        return has(village, Civic.PRIVATE_LARDERS) ? LARDER_POOR : 0;
    }

    /**
     * How much the town's ways keep crime down, as a factor on the chance (Mischief.prevention, through
     * Perks): the Standing Army and the Almshouse a little, Private Larders the other way.
     */
    static double crimeFactor(@Nullable UUID village) {
        double x = 1.0;
        if (has(village, Civic.STANDING_ARMY)) x *= 0.85;
        if (has(village, Civic.ALMSHOUSE)) x *= 0.85;
        if (has(village, Civic.PRIVATE_LARDERS)) x *= 1.2;
        return x;
    }

    /**
     * Every eight seconds, for every folk (VillageFolkEntity.aiStep): Paved Roads in its step and the
     * Watch Drills on a guard (kept up to date as it changes trade), and the Healers' care: the hurt
     * get back half a heart, as much again as the slow mending a folk with nothing to eat gets every
     * eight seconds (AssistantEntity), so it mends twice as fast between meals. [perks] And whatever its
     * own quirks, its leader and its town's legacies put on it (Perks.tend).
     */
    public static void tend(VillageFolkEntity f) {
        UUID v = f.ownerId();
        if (v == null) return;
        dress(f, v);
        if (has(v, Civic.HEALERS) && f.isAlive() && f.getHealth() < f.getMaxHealth() && f.hurtTime == 0
                && f.tickCount - f.getLastHurtByMobTimestamp() > 100) {
            f.heal(1.0F);
        }
        Perks.tend(f);
    }

    /**
     * Paved Roads and the Watch Drills, as attribute modifiers (transient: put back after a load by the next
     * tend). [perks] And the works' walking (the Turnpikes, the Clockwork Gate), the watch's armour, blow and
     * health (the Watch House, the Standing Army, the Militia, the Stone Walls under the bell, the Arena) and
     * the Diving Bells' breath.
     */
    static void dress(VillageFolkEntity f, @Nullable UUID v) {
        Map<Civic, Long> d = done(v);
        modifier(f, Attributes.MOVEMENT_SPEED, ROADS_ID, d.containsKey(Civic.PAVED_ROADS) ? ROADS : 0.0,
            AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL);
        boolean guard = f.stationTask() == StationTask.GUARD && !f.isBaby();
        boolean drilled = d.containsKey(Civic.WATCH_DRILLS) && guard;
        modifier(f, Attributes.ARMOR, DRILL_ARMOUR_ID, drilled ? DRILL_ARMOUR : 0.0, AttributeModifier.Operation.ADD_VALUE);
        modifier(f, Attributes.ATTACK_DAMAGE, DRILL_HIT_ID, drilled ? DRILL_HIT : 0.0, AttributeModifier.Operation.ADD_VALUE);
        // [perks]
        double walk = (d.containsKey(Civic.TURNPIKES) ? PIKE_WALK : 0.0)
            + (d.containsKey(Civic.CLOCKWORK_GATE) && wonderOf(v, Civic.CLOCKWORK_GATE) ? GATE_WALK : 0.0);
        modifier(f, Attributes.MOVEMENT_SPEED, WALK_ID, walk, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL);
        boolean arena = d.containsKey(Civic.ARENA) && wonderOf(v, Civic.ARENA);
        double armour = 0.0, hit = 0.0, health = 0.0;
        if (!f.isBaby()) {
            if (d.containsKey(Civic.MILITIA)) armour += MILITIA_ARMOUR;
            if (d.containsKey(Civic.STONE_WALLS) && Raids.underAlarm(v)) armour += WALLS_ARMOUR;
        }
        if (guard) {
            if (d.containsKey(Civic.WATCH_HOUSE)) health += WATCH_HEALTH;
            if (d.containsKey(Civic.STANDING_ARMY)) hit += ARMY_HIT;
            if (arena) { hit += ARENA_HIT; armour += ARENA_ARMOUR; }
        }
        modifier(f, Attributes.ARMOR, ARMOUR_ID, armour, AttributeModifier.Operation.ADD_VALUE);
        modifier(f, Attributes.ATTACK_DAMAGE, HIT_ID, hit, AttributeModifier.Operation.ADD_VALUE);
        modifier(f, Attributes.MAX_HEALTH, HEALTH_ID, health, AttributeModifier.Operation.ADD_VALUE);
        modifier(f, Attributes.OXYGEN_BONUS, BREATH_ID, d.containsKey(Civic.DIVING_BELLS) ? BELLS_BREATH : 0.0,
            AttributeModifier.Operation.ADD_VALUE);
    }

    static void modifier(LivingEntity e, Holder<Attribute> attribute, ResourceLocation id, double amount,
                         AttributeModifier.Operation op) {
        AttributeInstance a = e.getAttribute(attribute);
        if (a == null) return;
        if (amount == 0.0) {
            if (a.hasModifier(id)) a.removeModifier(id);
            return;
        }
        AttributeModifier m = a.getModifier(id);
        if (m != null && m.amount() == amount) return;
        a.addOrUpdateTransientModifier(new AttributeModifier(id, amount, op));
    }

    // ------------------------------------------------------------------ the points

    /**
     * The points the village earns a morning, and what from: {total} and the words ("12 grown folk 3, the
     * meeting hall 1"), and [perks] the share more it comes to (the Grand Library, the Observatory, a
     * Visionary in office), in percent.
     */
    public record Rate(int points, String why, int percent) {
        public Rate(int points, String why) {
            this(points, why, 0);
        }

        /** What a morning brings, in hundredths of a point. */
        public int hundredths() {
            return points * (100 + percent);
        }

        /** In words, a point a day: "3", "3.45". */
        public String perDay() {
            int h = hundredths();
            return h % 100 == 0 ? Integer.toString(h / 100) : String.format(Locale.ROOT, "%.2f", h / 100.0);
        }
    }

    public static Rate rate(@Nullable UUID village) {
        if (village == null) return new Rate(0, "");
        int grown = 0;
        List<AssistantEntity> folk = Villages.folkOf(village);
        for (AssistantEntity a : folk) if (a instanceof VillageFolkEntity f && !f.isBaby() && !f.isShowcase()) grown++;
        if (folk.isEmpty()) grown = Villages.headcount(village);       // nobody loaded: go by the books
        int people = Math.min(12, Math.min(grown, 16) / 4 + Math.max(0, grown - 16) / 12);
        List<String> why = new ArrayList<>();
        why.add(grown + " grown folk " + people);
        int total = people;
        if (Villages.hasBuilt(village, "hall")) { total++; why.add("the meeting hall 1"); }
        if (Villages.hasBuilt(village, "library")) { total++; why.add("the library 1"); }
        if (Villages.hasBuilt(village, "chapel")) { total++; why.add("the chapel 1"); }
        if (Elections.mandate(village) == Values.Value.PROGRESS) { total++; why.add("a leader elected for the next age 1"); }
        // [perks] The scholars, the surveyors, the leader's skill and the town's legacies of learning; then the
        // wonders and a Visionary in office, as a share more.
        Map<Civic, Long> d = done(village);
        if (d.containsKey(Civic.SCHOLARS_ENDOWMENT)) { total += SCHOLARS_POINTS; why.add("the Scholars' Endowment " + SCHOLARS_POINTS); }
        if (d.containsKey(Civic.SURVEYORS_OFFICE)) { total += SURVEY_POINTS; why.add("the Surveyors' Office " + SURVEY_POINTS); }
        total += Reigns.researchPoints(village, why);
        total += Ethos.research(village, why);                          // [identity] a learned, forward-looking or bookish town
        if (total < 1) { why.add("never less than 1"); total = 1; }
        int pct = 0;
        if (d.containsKey(Civic.GRAND_LIBRARY) && wonderOf(village, Civic.GRAND_LIBRARY)) { pct += LIBRARY_RESEARCH; why.add("the Grand Library +" + LIBRARY_RESEARCH + "%"); }
        if (d.containsKey(Civic.OBSERVATORY) && wonderOf(village, Civic.OBSERVATORY)) { pct += OBSERVATORY_RESEARCH; why.add("the Observatory +" + OBSERVATORY_RESEARCH + "%"); }
        pct += Reigns.researchPercent(village, why);
        return new Rate(total, String.join(", ", why), pct);
    }

    /** Mornings still to go at this rate: 0 when it is paid for. */
    static int daysLeft(UUID village, Civic c) {
        int need = c.cost() - points(village);
        int r = Math.max(100, rate(village).hundredths());
        return need <= 0 ? 0 : (need * 100 + r - 1) / r;
    }

    // ------------------------------------------------------------------ the morning

    /**
     * The village's morning, with its books (Market.tick, after the leader's own morning): the day's
     * points earned; a civic chosen if none is being studied; and once enough points are in hand, the
     * civic is done (said in the chronicle, by the leader and at the assembly) and the next is chosen.
     * The points over go toward the next. Once a day, whatever calls it. [perks] The wonders' and the
     * leaders' own mornings come with it (Perks.morning).
     */
    public static void morning(ServerLevel level, Villages.Village v, long day) {
        UUID id = v.id();
        if (Long.toString(day).equals(Ledger.note(id, DAY))) return;
        Ledger.note(id, DAY, Long.toString(day));
        Perks.morning(level, v, day);
        if (finished(id)) return;
        Civic now = current(id);
        if (now == null || !open(id, now)) now = choose(level, id, day);
        // A morning's points, the fifteenths and fifths carried from one to the next.
        int hundredths = rate(id).hundredths() + parse(Ledger.note(id, CARRY));
        Ledger.note(id, CARRY, Integer.toString(hundredths % 100));
        int points = points(id) + hundredths / 100;
        if (now != null && points >= now.cost()) {
            points -= now.cost();
            complete(level, id, now, day);
            now = choose(level, id, day);
        }
        Ledger.note(id, POINTS, Integer.toString(now == null ? 0 : points));
    }

    /** It is done: on the books with its day, and said. */
    static void complete(ServerLevel level, UUID village, Civic c, long day) {
        Map<Civic, Long> d = new LinkedHashMap<>(done(village));
        d.put(c, day);
        saveDone(village, d);
        // How long it took, if it was studied (not granted out of turn).
        String took = "";
        if (c == current(village)) {
            Ledger.forget(village, NOW);
            String sinceNote = Ledger.note(village, SINCE);
            long since = sinceNote == null ? day : parse(sinceNote);
            if (day > since) took = " after " + (day - since) + (day - since == 1 ? " day's" : " days'") + " study";
        }
        Villages.tell(village, day, "the town finished " + c.title + took + ": " + c.result);
        Market.assemblyNews(village, "We've finished " + c.title + ": " + c.result + ". Well done, all of you.");
        VillageFolkEntity elder = Orders.elderOf(village);
        if (elder != null) {
            FolkTalk.speak(elder, FolkTalk.pick(level.getRandom(), c.title + "'s done! " + capital(c.result) + ".",
                "We've finished " + c.title + " — " + c.result + ".", c.title + ", done at last. You'll feel the difference."));
            elder.persona().remember(day, "I saw " + c.title + " through for the town on day " + day, 4);
        }
        // [perks] A choice made closes the other of its pair, and the town hears it; a wonder's plans drawn
        // are the start of a building (Wonders); the leader's good decision is to its credit (Reigns).
        Civic r = c.rival();
        if (r != null) Villages.tell(village, day, "with " + c.title + " chosen, " + r.title + " is closed to the town for good");
        if (c.wonder()) Wonders.planned(level, village, c, day);
        Reigns.deed(village, Reigns.Deed.CIVIC, 1);
        // What it changes about the folk themselves, at once rather than at their next turn.
        for (AssistantEntity a : Villages.folkOf(village)) if (a instanceof VillageFolkEntity f) dress(f, village);
    }

    // ------------------------------------------------------------------ choosing

    /** What the village is short of now, in the leader's eyes, with the words for each. */
    record Needs(int food, String foodWhy, int homes, String homesWhy, int mood, String moodWhy, int safety, int money) {

        /** How much this civic answers the village's needs, and the words. */
        Object[] of(Civic c) {
            switch (c) {
                case HEALERS -> {
                    return safety / 2 >= mood / 2 ? new Object[]{ safety / 2, "the raiders were at the gate" } : new Object[]{ mood / 2, moodWhy };
                }
                case WATCH_DRILLS -> { return new Object[]{ safety, "the raiders were at the gate" }; }
                case PAVED_ROADS -> { return new Object[]{ money / 2, "the treasury's thin" }; }
                case OPEN_GRANARY -> { return new Object[]{ food + mood / 2, food > 0 ? foodWhy : moodWhy }; }
                case PRIVATE_LARDERS, OPEN_BORDERS, TARIFFS -> { return new Object[]{ money, "the treasury's thin" }; }
                case OBSERVER_PATTERN_BOOKS, TURNPIKES, CLOCKWORK_GATE -> { return new Object[]{ homes / 2, homesWhy }; }
                case PLAIN_LIVING -> { return new Object[]{ money / 2, "the treasury's thin" }; }
                default -> { }
            }
            return switch (c.branch) {
                case INDUSTRY, LORE, ARCANE -> new Object[]{ 0, "" };
                case LAND -> new Object[]{ food, foodWhy };
                case HOMES -> new Object[]{ homes, homesWhy };
                case WELLBEING -> new Object[]{ mood, moodWhy };
                case TRADE -> new Object[]{ money, "the treasury's thin" };
                case DEFENCE -> new Object[]{ safety, "the raiders were at the gate" };
                case FAITH -> new Object[]{ mood * 3 / 4, "times are hard, and folk need comfort" };
                case SEA -> food / 2 >= money / 2 ? new Object[]{ food / 2, foodWhy } : new Object[]{ money / 2, "the treasury's thin" };
            };
        }
    }

    static Needs needs(ServerLevel level, UUID village) {
        int food = 0;
        String foodWhy = "";
        Leader.Plan plan = Leader.plan(village);
        if (plan == Leader.Plan.FAMINE) { food = 40; foodWhy = "we're near out of food"; }
        else if (plan == Leader.Plan.SHORT) { food = 25; foodWhy = "our fields need it"; }
        else if (Market.hungry(village)) { food = 15; foodWhy = "the larder's low"; }
        int homes = 0;
        String homesWhy = "";
        int head = Villages.headcount(village);
        int[] counts = Homes.counts(level, village);
        if (Villages.housing(village) < head || (counts.length > 1 && counts[1] > 0)) { homes += 20; homesWhy = "families are waiting for homes"; }
        if (counts.length > 11 && counts[11] > 0) { homes += 10; if (homesWhy.isEmpty()) homesWhy = "rent is owed all over town"; }
        int content = Contentment.score(village);
        int mood = content < 40 ? 25 : content < 55 ? 10 : 0;
        String moodWhy = content < 40 ? "folk are low, and deserve better" : "folk could be happier";
        long day = level.getDayTime() / 24000L;
        int safety = Raids.underAlarm(village) || day - Raids.raidedOn(village) <= 5 ? 30 : 0;
        int money = Ledger.coins(village) < 2 * Math.max(1, Market.wageBill(village)) ? 20 : 0;
        return new Needs(food, foodWhy, homes, homesWhy, mood, moodWhy, safety, money);
    }

    /** One civic's pull on whoever chooses: its parts summed, and the words (chronicle, and its own mouth) for the biggest. */
    static final class Appeal {
        double total, best;
        String why = "", said = "";

        void add(double v, String why, String said) {
            if (v <= 0) return;
            total += v;
            if (v > best) {
                best = v;
                this.why = why;
                this.said = said;
            }
        }
    }

    /** How much a leader of this nature leans to this civic, by its traits. */
    static int traitPull(Civic c, Social.Trait t) {
        return switch (t) {
            case HARDWORKING -> c.branch == Branch.INDUSTRY ? 10 : c == Civic.PLAIN_LIVING ? 6 : 0;
            case CURIOUS -> c.branch == Branch.INDUSTRY ? 6 : c.branch == Branch.TRADE ? 4 : c.branch == Branch.LORE ? 8
                : c.branch == Branch.ARCANE ? 6 : 0;
            case CHEERFUL -> c.branch == Branch.WELLBEING ? 10 + (c == Civic.PATRONAGE ? 4 : c == Civic.PLAIN_LIVING ? -10 : 0) : 0;
            case SOCIABLE -> c.branch == Branch.WELLBEING ? 6 : c.branch == Branch.TRADE ? 4 + (c == Civic.OPEN_BORDERS ? 4 : 0) : 0;
            case EASYGOING -> c.branch == Branch.WELLBEING ? 6 + (c == Civic.REST_DAY_CHARTER ? 4 : 0) + (c == Civic.PLAIN_LIVING ? -6 : 0)
                : c == Civic.MILITIA ? 4 : 0;
            case GENEROUS -> c.branch == Branch.HOMES ? 6 : c.branch == Branch.LAND ? 4 + (c == Civic.OPEN_GRANARY ? 6 : 0)
                : c.branch == Branch.FAITH ? 3 : 0;
            case GRUMPY -> c == Civic.WATCH_DRILLS ? 10 : c.branch == Branch.DEFENCE ? 6 + (c == Civic.STANDING_ARMY ? 4 : 0)
                : c == Civic.TARIFFS ? 6 : 0;
            case SHY -> c.branch == Branch.LAND ? 6 : c == Civic.HEALERS ? 4 : c.branch == Branch.FAITH ? 4 : c.branch == Branch.LORE ? 3 : 0;
        };
    }

    /** {chronicle, its own words} for a leader choosing out of its nature. */
    static String[] traitWords(Social.Trait t) {
        return switch (t) {
            case HARDWORKING -> new String[]{ "a hardworking leader's choice", "good tools make good work" };
            case CURIOUS -> new String[]{ "a curious leader's choice", "I want to see what it can do for us" };
            case CHEERFUL -> new String[]{ "a cheerful leader's choice", "a happy town's a good town" };
            case SOCIABLE -> new String[]{ "a sociable leader's choice", "folk should have time for each other" };
            case EASYGOING -> new String[]{ "an easygoing leader's choice", "nobody should be worked to the bone" };
            case GENEROUS -> new String[]{ "a generous leader's choice", "I'd see everybody looked after" };
            case GRUMPY -> new String[]{ "a grumpy leader's choice", "somebody has to keep the riff-raff out" };
            case SHY -> new String[]{ "a quiet leader's choice", "the old ways keep us fed" };
        };
    }

    /** [perks] How the land leans the town's study, in points: the coast to the Sea, the hills to the forge and the walls. */
    static int landLean(Branch b, Homeland.Land l) {
        return switch (l) {
            case COAST -> b == Branch.SEA ? 14 : b == Branch.TRADE ? 4 : 0;
            case RIVER -> b == Branch.SEA ? 8 : b == Branch.LAND ? 4 : 0;
            case FOREST, TAIGA -> b == Branch.LAND ? 4 : b == Branch.ARCANE || b == Branch.HOMES ? 4 : 0;
            case SNOW -> b == Branch.DEFENCE || b == Branch.FAITH ? 6 : 0;
            case MOUNTAIN -> b == Branch.INDUSTRY ? 8 : b == Branch.DEFENCE ? 6 : 0;
            case DESERT -> b == Branch.FAITH ? 8 : b == Branch.TRADE ? 4 : 0;
            case SAVANNA -> b == Branch.LAND ? 8 : 0;
            case JUNGLE -> b == Branch.ARCANE ? 6 : b == Branch.LAND ? 3 : 0;
            case SWAMP -> b == Branch.ARCANE ? 8 : b == Branch.FAITH ? 3 : 0;
            case BADLANDS -> b == Branch.INDUSTRY ? 8 : b == Branch.TRADE ? 3 : 0;
            case MEADOW -> b == Branch.WELLBEING ? 8 : 0;
            case PLAINS -> b == Branch.LAND ? 6 : 0;
        };
    }

    /** What pulls the chooser to this civic on its own account (not counting what it leads to). */
    static Appeal aim(Civic c, @Nullable VillageFolkEntity elder, @Nullable Values.Value mandate, Needs needs, UUID village) {
        Appeal a = new Appeal();
        if (elder != null) {
            int[] w = Values.of(elder);
            for (Values.Value v : Values.Value.values()) {
                int aff = c.affinity(v);
                if (aff > 0) a.add(w[v.ordinal()] * aff / 200.0, "for " + v.cares, v.cares + " matters most to me");
            }
            for (Social.Trait t : elder.life().traits()) {
                String[] words = traitWords(t);
                a.add(traitPull(c, t), words[0], words[1]);
            }
        }
        if (mandate != null && c.affinity(mandate) >= 80) a.add(30, "what it was elected for, " + mandate.cares,
            "it's what you elected me for: " + mandate.cares);
        Object[] need = needs.of(c);
        String nw = (String) need[1];
        a.add((Integer) need[0], nw, nw);
        // [perks] The land, the town's ways, and a wonder nobody has raised.
        Homeland.Land land = Homeland.of(village);
        int lean = landLean(c.branch, land);
        a.add(lean, "the way of " + land.kind, "we're " + land.kind + ", and it shows");
        int ethos = ETHOS.applyAsInt(village, c.branch) + Perks.ethosWays(village, c);   // its branch, and its side of a pair
        a.add(ethos, "the town's own ways", "it's our way");
        if (c.wonder() && !Wonders.raisedElsewhere(village, c)) a.add(12, "for the glory of it", "nobody in the world has one");
        return a;
    }

    /** A civic the chooser might take, its score and why. */
    public record Weighed(Civic civic, double score, String why, String said) {}

    /** [perks] Who weighs the town's choice: its leader, or a player leader's steward, or nobody (the council). */
    @Nullable
    static VillageFolkEntity chooserFolk(UUID village) {
        VillageFolkEntity elder = Orders.elderOf(village);
        if (elder == null && PlayerLeader.leaderId(village) != null) elder = PlayerLeader.steward(village);
        return elder != null && elder.isBaby() ? null : elder;
    }

    /**
     * Every open civic, weighed as the leader (or, with none, the council) would: what it cares about,
     * its nature, its mandate and the village's needs, plus half the pull of the best thing further up
     * the same branch, less a little for the dearer tiers. Best first; ties go to the tree's order.
     */
    public static List<Weighed> weigh(ServerLevel level, UUID village) {
        VillageFolkEntity elder = chooserFolk(village);
        Values.Value mandate = Elections.mandate(village);
        Needs needs = needs(level, village);
        Map<Civic, Long> done = done(village);
        Map<Civic, Appeal> aims = new LinkedHashMap<>();
        for (Civic c : Civic.values()) if (!done.containsKey(c) && !locked(village, c)) aims.put(c, aim(c, elder, mandate, needs, village));
        List<Weighed> out = new ArrayList<>();
        for (Civic c : Civic.values()) {
            if (!open(village, c)) continue;
            Appeal a = aims.get(c);
            double path = 0;
            Civic toward = null;
            for (Civic later : Civic.values()) {
                if (later.branch != c.branch || later.tier <= c.tier || !aims.containsKey(later)) continue;
                double p = aims.get(later).total * 0.5;
                if (p > path) { path = p; toward = later; }
            }
            double score = a.total + path - 2.0 * (c.tier - 1);
            String why, said;
            if (toward != null && path > a.best) {
                why = "the road to " + toward.title;
                said = "it's the road to " + toward.title;
            } else if (!a.why.isEmpty()) {
                why = a.why;
                said = a.said;
            } else {
                why = elder == null ? "the most pressing" : "the next step";
                said = elder == null ? "it's the most pressing" : "it's the next step";
            }
            out.add(new Weighed(c, score, why, said));
        }
        out.sort((x, y) -> Double.compare(y.score(), x.score()) != 0 ? Double.compare(y.score(), x.score())
            : Integer.compare(x.civic().ordinal(), y.civic().ordinal()));
        return out;
    }

    /** Who chooses: "Reeve Bramble", or the council with nobody leading. */
    static String chooser(UUID village) {
        VillageFolkEntity elder = Orders.elderOf(village);
        if (elder != null) return Leader.leaderName(village, elder);
        String name = Villages.elderName(village);
        if (!name.isEmpty()) return capital(Homeland.leaderTitle(village)) + " " + name;
        return "The council";
    }

    /**
     * The leader chooses the next civic and sets the town to work on it: in the chronicle, aloud
     * (when it is about), and to the morning assembly. Returns it, or null when everything is done.
     */
    @Nullable
    static Civic choose(ServerLevel level, UUID village, long day) {
        List<Weighed> all = weigh(level, village);
        if (all.isEmpty()) {
            Ledger.forget(village, NOW);
            return null;
        }
        Weighed w = all.get(0);
        String who = chooser(village);
        begin(village, w.civic(), who, w.why(), day);
        Villages.tell(village, day, who + " set the town to work on " + w.civic().title + ": " + w.why());
        Market.assemblyNews(village, "We'll study " + w.civic().title + " next — " + w.said() + ". When it's done, "
            + w.civic().result + ".");
        VillageFolkEntity elder = Orders.elderOf(village);
        if (elder != null) {
            FolkTalk.speak(elder, FolkTalk.pick(level.getRandom(), "Next we study " + w.civic().title + ": " + w.said() + ".",
                w.civic().title + " is next for this town — " + w.said() + ".", "I've set us to " + w.civic().title + ". " + capital(w.said()) + "."));
        }
        return w.civic();
    }

    private static void begin(UUID village, Civic c, String who, String why, long day) {
        Ledger.note(village, NOW, c.name());
        Ledger.note(village, BY, who);
        Ledger.note(village, WHY, why);
        Ledger.note(village, SINCE, Long.toString(day));
    }

    // ------------------------------------------------------------------ by command (ops and tests)

    /** Why it cannot be studied now, or null if it can. */
    @Nullable
    static String notOpen(UUID village, Civic c) {
        if (has(village, c)) return c.title + " is done already.";
        String lock = lockedWhy(village, c);
        if (!lock.isEmpty()) return "Closed: " + lock + ".";
        if (!open(village, c)) return "Not yet: " + c.title + " needs " + needsWords(c) + " first.";
        return null;
    }

    /**
     * Set the town to study this civic now (/village research pick): only one that is open (its
     * branch's tier before it done, and not closed by its pair or another town's wonder). The points in
     * hand go toward it. Returns what happened.
     */
    public static String pick(ServerLevel level, UUID village, Civic c, long day) {
        String no = notOpen(village, c);
        if (no != null) return no;
        begin(village, c, "By order", "set by command", day);
        Villages.tell(village, day, "the town turned its study to " + c.title + ", by order");
        return "The town now studies " + c.title + " (" + points(village) + " of " + c.cost() + " points).";
    }

    /**
     * [perks] A player who leads sets the town's study from its Leader's page: as {@link #pick}, in its own
     * name, and the town hears it.
     */
    public static String leaderPick(ServerLevel level, UUID village, Civic c, long day, String who) {
        String no = notOpen(village, c);
        if (no != null) return no;
        begin(village, c, who, "the leader's choice", day);
        Villages.tell(village, day, who + " set the town to work on " + c.title);
        Market.assemblyNews(village, "We'll study " + c.title + " next, by our leader's word. When it's done, " + c.result + ".");
        return "The town now studies " + c.title + " (" + points(village) + " of " + c.cost() + " points).";
    }

    /**
     * Done at once (/village research grant), as if studied: only one that is open. What it does to
     * the folk is put on them at once. Returns what happened.
     */
    public static String grant(ServerLevel level, UUID village, Civic c, long day) {
        String no = notOpen(village, c);
        if (no != null) return no;
        complete(level, village, c, day);
        return c.title + " is done: " + c.result + ".";
    }

    // ------------------------------------------------------------------ telling

    /** The research line for the village board, in "What we're working towards" (VillageBoards.compose). */
    public static List<String> board(@Nullable UUID village) {
        List<String> out = new ArrayList<>();
        if (village == null) return out;
        Map<Civic, Long> done = done(village);
        Civic now = current(village);
        String doneWords = doneWords(done);
        if (finished(village)) {
            out.add("FG|Research: the whole tree done, " + done.size() + " of " + ALL + lastDone(done) + ".");
        } else if (now == null) {
            out.add("FM|Research: nothing chosen yet; " + chooser(village) + " chooses at the morning's books ("
                + rate(village).perDay() + " points a day)." + (doneWords.isEmpty() ? "" : " Done: " + doneWords + "."));
        } else {
            int days = daysLeft(village, now);
            String by = Ledger.note(village, BY), why = Ledger.note(village, WHY);
            out.add("FG|Researching: " + now.title + " " + Math.min(points(village), now.cost()) + "/" + now.cost()
                + (days > 0 ? ", about " + plural(days, "day") : ", done tomorrow")
                + (by == null || why == null ? "" : " (" + by + ": " + why + ")")
                + (doneWords.isEmpty() ? "." : " · Done: " + doneWords + "."));
        }
        out.addAll(Perks.board(village));                           // [perks] the town's ways, its wonders, its leader's perks
        return out;
    }

    /** "Common Tools, Cheap Homes" — or, past three, "7 of 63, lately Herd Books and Cheap Homes". */
    static String doneWords(Map<Civic, Long> done) {
        if (done.isEmpty()) return "";
        List<String> names = new ArrayList<>();
        for (Civic c : done.keySet()) names.add(c.title);
        if (names.size() <= 3) return String.join(", ", names);
        return done.size() + " of " + ALL + ", lately " + names.get(names.size() - 2) + " and " + names.get(names.size() - 1);
    }

    private static String lastDone(Map<Civic, Long> done) {
        Civic last = null;
        for (Civic c : done.keySet()) last = c;
        return last == null ? "" : " — the last, " + last.title + ", on day " + done.get(last);
    }

    /** What a folk adds when asked about the elder's orders (Orders.talk): " We're studying Crop Rotation: our fields need it." */
    public static String talk(VillageFolkEntity f) {
        UUID village = f.ownerId();
        Civic now = current(village);
        if (now == null) return "";
        String why = Ledger.note(village, WHY);
        int days = daysLeft(village, now);
        return " We're studying " + now.title + (why == null || why.isEmpty() || why.equals("set by command") ? "" : " — " + why)
            + (days > 0 ? ", " + plural(days, "day") + " to go." : ", nearly done.");
    }

    /** /village research: the state of the tree, a line at a time: a head, and a line a branch. */
    public static List<String> lines(@Nullable UUID village) {
        List<String> out = new ArrayList<>();
        if (village == null) return out;
        Rate r = rate(village);
        Civic now = current(village);
        Map<Civic, Long> done = done(village);
        StringBuilder head = new StringBuilder("RESEARCH " + Villages.name(village) + ": " + done.size() + " of " + ALL + " done; "
            + plural(points(village), "point") + " in hand, +" + r.perDay() + " a day (" + r.why() + ")");
        if (now != null) {
            head.append("; researching ").append(now.title).append(' ').append(Math.min(points(village), now.cost())).append('/').append(now.cost())
                .append(", about ").append(plural(daysLeft(village, now), "day")).append(" — chosen by ")
                .append(Ledger.note(village, BY)).append(": ").append(Ledger.note(village, WHY));
        } else if (!finished(village)) {
            head.append("; nothing chosen yet");
        }
        out.add(head.toString());
        for (Branch b : Branch.values()) {
            StringBuilder sb = new StringBuilder(b.title + ":");
            int lastTier = 0;
            for (Civic c : Civic.values()) {
                if (c.branch != b) continue;
                sb.append(c.tier == 1 ? " " : c.tier == lastTier ? " | " : " > ").append(c.title).append(" [").append(c.key()).append(", ");
                lastTier = c.tier;
                if (done.containsKey(c)) sb.append("done day ").append(done.get(c));
                else if (c == now) sb.append("researching ").append(Math.min(points(village), c.cost())).append('/').append(c.cost());
                else if (locked(village, c)) sb.append("closed: ").append(lockedWhy(village, c));
                else if (open(village, c)) sb.append("open, ").append(c.cost());
                else sb.append("locked, ").append(c.cost());
                if (c.wonder()) sb.append(", wonder: ").append(Wonders.stateWords(village, c));
                sb.append("] ").append(c.effect);
            }
            out.add(sb.toString());
        }
        return out;
    }

    /**
     * The tree for the town's books' Research page (Annals.snapshot, "research"): the points and the
     * rate, what is being studied and why, every civic with its state, and the history. [perks] With each
     * civic's pair and why it is closed, each wonder's state, and each branch's count.
     */
    public static CompoundTag report(@Nullable UUID village) {
        CompoundTag t = new CompoundTag();
        if (village == null) return t;
        Rate r = rate(village);
        Map<Civic, Long> done = done(village);
        Civic now = current(village);
        t.putInt("points", points(village));
        t.putInt("rate", r.points());
        t.putString("rate_day", r.perDay());
        t.putString("rate_why", r.why());
        t.putInt("done", done.size());
        t.putInt("total", ALL);
        t.putBoolean("finished", finished(village));
        if (now != null) {
            t.putString("now", now.key());
            t.putString("now_title", now.title);
            t.putInt("now_cost", now.cost());
            t.putInt("days_left", daysLeft(village, now));
            t.putString("by", String.valueOf(Ledger.note(village, BY)));
            t.putString("why", String.valueOf(Ledger.note(village, WHY)));
            t.putLong("since", parse(Ledger.note(village, SINCE)));
        }
        t.putString("chooser", chooser(village));
        ListTag branches = new ListTag();
        for (Branch b : Branch.values()) {
            CompoundTag bt = new CompoundTag();
            bt.putString("title", b.title);
            bt.putString("about", b.about);
            int n = 0, of = 0;
            for (Civic c : Civic.values()) {
                if (c.branch != b) continue;
                of++;
                if (done.containsKey(c)) n++;
            }
            bt.putInt("done", n);
            bt.putInt("of", of);
            bt.putInt("tiers", b.tiers());
            branches.add(bt);
        }
        t.put("branches", branches);
        ListTag civics = new ListTag();
        for (Civic c : Civic.values()) {
            CompoundTag ct = new CompoundTag();
            ct.putString("key", c.key());
            ct.putString("title", c.title);
            ct.putInt("branch", c.branch.ordinal());
            ct.putInt("tier", c.tier);
            ct.putInt("cost", c.cost());
            ct.putString("effect", c.effect);
            ct.putString("about", c.about);
            ct.putString("needs", needsWords(c));
            Civic rival = c.rival();
            ct.putString("rival", rival == null ? "" : rival.title);
            boolean closed = !done.containsKey(c) && locked(village, c);
            String state = done.containsKey(c) ? "done" : c == now ? "now" : closed ? "closed" : open(village, c) ? "open" : "locked";
            ct.putString("state", state);
            if (closed) ct.putString("closed_why", lockedWhy(village, c));
            ct.putLong("day", done.getOrDefault(c, -1L));
            if (c.wonder()) {
                ct.putBoolean("wonder", true);
                ct.putString("wonder_state", Wonders.stateWords(village, c));
                ct.putBoolean("wonder_ours", Wonders.owns(village, c));
            }
            civics.add(ct);
        }
        t.put("civics", civics);
        ListTag history = new ListTag();
        for (Map.Entry<Civic, Long> e : done.entrySet()) {
            history.add(StringTag.valueOf(e.getKey().title + " — done on day " + e.getValue()));
        }
        t.put("history", history);
        return t;
    }

    // ------------------------------------------------------------------ tests

    /** Tests: the village's morning for this day, now. */
    public static void morningForTests(ServerLevel level, Villages.Village v, long day) {
        morning(level, v, day);
    }

    /** Tests: the leader chooses now (announced as ever). Returns its choice. */
    @Nullable
    public static Civic chooseForTests(ServerLevel level, UUID village, long day) {
        return choose(level, village, day);
    }

    /** Tests: this many points in hand. */
    public static void pointsForTests(UUID village, int points) {
        Ledger.note(village, POINTS, Integer.toString(points));
    }

    /** Tests: the civic marked done at once, whatever the tree says (to stand up a town's history quickly). */
    public static void doneForTests(UUID village, Civic c, long day) {
        Map<Civic, Long> d = new LinkedHashMap<>(done(village));
        d.put(c, day);
        saveDone(village, d);
    }

    /** Tests: the village's research forgotten, as if it had never begun. */
    public static void clearForTests(UUID village) {
        for (String k : new String[]{ DONE, NOW, POINTS, WHY, BY, SINCE, DAY, FUND, COUNTING, CARRY }) Ledger.forget(village, k);
        READ.remove(village);
    }

    // ------------------------------------------------------------------ helpers

    static int parse(@Nullable String s) {
        if (s == null || s.isEmpty()) return 0;
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String plural(int n, String one) {
        return n + " " + (n == 1 ? one : one + "s");
    }

    static String capital(String s) {
        return s == null || s.isEmpty() ? "" : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
