package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.village.WorkMath;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * [wpb] Every trade in the towns, as a job a player can hold: its card. One plain sentence of what the role is; the shift
 * it is worked at (ShiftRole, so the same tasks, HUD and checks as any shift); "what the job involves" (the day step by
 * step, the tools and whether they are provided, where, the conditions, who you report to, the skills it trains, the
 * promotion path); the unit its output is counted in and the target per shift, calibrated against a competent hand
 * (WorkMath.competent over the shift's par times); how output turns into stars; what earns a warning and what a dismissal;
 * and what is excused (no work to be had, rain, short makings).
 *
 * <p>Every StationTask has a card, plus the work that is not a folk trade of its own: the builder's hand, the baker's hand,
 * the waiter, the wheelwright's hand, the courier, the hall clerk, the teacher's assistant and the quarryman (feat-build's
 * quarry). JobCardsTest and PlayerWorkGameTests.pw14 check that none is missing and every target is reachable.
 */
public final class JobCards {

    private JobCards() {}

    /** How the work on a role is counted: the unit, a task's worth of it, and a task's time (ticks, with the walking). */
    public record Measure(String one, String many, double perTask, long taskTicks, boolean produce, boolean outdoors, @Nullable Item good,
                          double costShare) {}

    /** The units each shift counts in. Produce on commission is handed in (Provenance); the rest is paid by the task. */
    static final Map<ShiftRole, Measure> MEASURES = new LinkedHashMap<>();

    static {
        MEASURES.put(ShiftRole.FARM, new Measure("plot", "plots harvested and replanted", 1, 300, true, true, Items.WHEAT, 0.25));
        MEASURES.put(ShiftRole.WOOD, new Measure("tree", "trees felled and replanted", 1, 900, true, true, Items.OAK_LOG, 0.2));
        MEASURES.put(ShiftRole.SHEAR, new Measure("sheep", "sheep shorn", 1, 300, true, true, Items.WHITE_WOOL, 0.3));
        MEASURES.put(ShiftRole.MINE, new Measure("block", "blocks cut (ore counts three)", 7, 600, true, false, Items.COAL, 0.35));
        MEASURES.put(ShiftRole.FISH, new Measure("fish", "fish caught", 3, 1200, true, true, Items.COD, 0.15));
        MEASURES.put(ShiftRole.SMELT, new Measure("ingot", "ingots smelted", 12, 1800, false, false, Items.IRON_INGOT, 0.7));
        MEASURES.put(ShiftRole.CRAFT, new Measure("piece", "pieces made", 10, 900, false, false, null, 0.6));
        MEASURES.put(ShiftRole.HAUL, new Measure("load", "loads delivered", 1, 900, false, false, null, 0));
        MEASURES.put(ShiftRole.COUNTER, new Measure("customer", "customers served", 1, 240, false, false, null, 0));
        MEASURES.put(ShiftRole.WAITER, new Measure("plate", "plates to the right table", 1, 400, false, false, null, 0));
        MEASURES.put(ShiftRole.COOK, new Measure("dish", "dishes cooked, none burnt", 1, 500, false, false, null, 0));
        MEASURES.put(ShiftRole.BAKE, new Measure("batch", "batches baked, none burnt", 1, 600, false, false, Items.BREAD, 0.5));
        MEASURES.put(ShiftRole.BUILD, new Measure("block", "blocks laid true", 2.5, 600, false, true, null, 0));
        MEASURES.put(ShiftRole.HEALER, new Measure("patient", "patients seen to", 1, 500, false, false, null, 0));
        MEASURES.put(ShiftRole.WAGON, new Measure("repair", "repairs to the wagon", 1, 600, false, true, null, 0));
        MEASURES.put(ShiftRole.WATCH, new Measure("post", "posts walked (and monsters seen off)", 1, 400, false, true, null, 0));
    }

    /** A job's card. */
    public record Card(String key, String title, ShiftRole shift, String role, List<String> day, String tools, boolean provided,
                       String where, String conditions, String reportTo, String skills, String senior, String head,
                       @Nullable StationTask task) {

        public Measure measure() {
            return MEASURES.get(shift);
        }

        /** A competent hand's output in a shift, in units. */
        public double competent() {
            Measure m = measure();
            return WorkMath.competent(m.taskTicks(), m.perTask(), WorkMath.SHIFT);
        }

        /** The three-star target for a new hand. */
        public int target() {
            return WorkMath.target(competent());
        }

        /** "Harvest and replant 7 plots": the target in words. */
        public String targetLine(int n) {
            return n + " " + (n == 1 ? measure().one() : measure().many());
        }

        public String promotion() {
            return title + " → " + senior + " → " + head;
        }
    }

    static final Map<String, Card> CARDS = new LinkedHashMap<>();

    private static void card(String key, String title, ShiftRole shift, String role, String[] day, String tools, boolean provided,
                             String where, String conditions, String reportTo, String skills, String senior, String head) {
        StationTask t = null;
        try { t = StationTask.valueOf(key); } catch (IllegalArgumentException ignored) { }
        CARDS.put(key, new Card(key, title, shift, role, List.of(day), tools, provided, where, conditions, reportTo, skills, senior, head, t));
    }

    static {
        card("FARM", "Farm hand", ShiftRole.FARM, "You bring in the ripe crops and plant each plot straight back.",
            new String[]{ "Arrive at the fields at the start of the day.", "Go to the plot the arrow marks; it is ripe.",
                "Break the crop: on a salary it goes to the stores, on commission it is yours to hand in.",
                "Plant the seed back on the same plot before moving on.", "Keep at it till the shift's hours are up." },
            "Your hands. Seed is handed back from each harvest.", true, "The town's fields (or the employer's).",
            "Outdoors in all weather; rain lowers the target by a fifth.", "The farm foreman",
            "Farming: quicker harvests, better yields later.", "Farm foreman", "Head of the fields");
        card("WOOD", "Woodcutter's hand", ShiftRole.WOOD, "You fell the town's grown trees and set a sapling where each stood.",
            new String[]{ "Go to the marked tree in the town's wood.", "Fell it, every log of it.",
                "Plant a sapling where it stood (one is handed you if the stores have one).", "On to the next marked tree." },
            "An axe of your own.", false, "The town's wood, out past the houses.", "Outdoors; rain lowers the target by a fifth.",
            "The head woodsman", "Forestry: faster felling.", "Senior woodsman", "Head woodsman");
        card("MINE", "Miner", ShiftRole.MINE, "You cut back the mine's face and send the stone and ore up.",
            new String[]{ "Walk to the mine head; the arrow shows it.", "Go down the stair (or the cage) to the level the arrow names.",
                "Cut every marked block of the face.", "Ore counts three; leave nothing marked uncut.", "Come up at the end of the shift." },
            "A pick of your own, and torches.", false, "The town's mine, at the level the card names.",
            "Underground, dark, and not without danger. A face with no ore is no fault of yours.", "The shift boss",
            "Mining: speed and a nose for ore.", "Shift boss", "Mine captain");
        card("CAVE", "Cave hand", ShiftRole.MINE, "You go with the cave dwellers' work: cutting where the caves meet the mine.",
            new String[]{ "Meet at the mine head.", "Follow the arrow down to the marked face.", "Cut the marked blocks; ore counts three.",
                "Mind the dark: light your way." },
            "A pick, a sword and torches of your own.", false, "The mine's deep levels and the caves off them.",
            "Underground and dangerous; nobody counts a bare face against you.", "The cave dweller",
            "Mining and keeping your head.", "Senior cave hand", "Head of the deep works");
        card("QUARRY", "Quarryman", ShiftRole.MINE, "You cut stone at the town's quarry for its walls and buildings.",
            new String[]{ "Go to the quarry; the arrow shows the face.", "Cut the marked blocks cleanly.",
                "The stone goes to the stores (or to you to hand in, on commission).", "On to the next face." },
            "A pick of your own.", false, "The town's quarry.", "Outdoors and dusty.", "The quarry master",
            "Mining: speed.", "Senior quarryman", "Quarry master");
        card("RANCH", "Ranch hand", ShiftRole.SHEAR, "You shear the town's flock and send the wool to the stores.",
            new String[]{ "Go to the pen.", "Shear the sheep the arrow marks with your own shears.",
                "Leave the unmarked ones be.", "On to the next." },
            "Shears of your own.", false, "The town's pen.", "Outdoors.", "The head shepherd", "Ranching: a quick, clean job.",
            "Senior shepherd", "Head of the flock");
        card("GUARD", "Night watch", ShiftRole.WATCH, "You walk the town's watch round at night and see off what comes.",
            new String[]{ "Report at the town's heart at dusk.", "Walk to each marked post in turn.",
                "Kill any monster inside the town as you go: each one counts.", "The watch ends at dawn." },
            "A sword and armour of your own.", false, "The town's four posts.", "Night work, outdoors, and dangerous.",
            "The captain of the watch", "Guarding: a faster round.", "Corporal of the watch", "Captain of the watch");
        card("SCOUT", "Scout's runner", ShiftRole.WATCH, "You run the town's round for the scouts and report what's out there.",
            new String[]{ "Report at the town's heart.", "Run to each marked post in turn.", "See off monsters on the way.",
                "Back at the heart by the end of the shift." },
            "Boots and a sword of your own.", false, "The town's edge.", "Outdoors, at night.", "The head scout",
            "Scouting: covering ground.", "Senior runner", "Head scout");
        card("HUNT", "Hunter's hand", ShiftRole.WATCH, "You keep the edge of town clear of what preys on it, on the watch's round.",
            new String[]{ "Report at dusk.", "Walk the marked posts.", "Kill what comes near the houses.", "Done at dawn." },
            "A bow or sword of your own.", false, "The town's edge.", "Night work, outdoors, dangerous.", "The hunter",
            "Hunting: a sure aim.", "Senior hunter", "Master of the hunt");
        card("GOLEMS", "Golem keeper's hand", ShiftRole.WATCH, "You walk the gates and posts with the golems at night.",
            new String[]{ "Report at the gates at dusk.", "Walk the marked posts.", "Deal with what the golems miss.", "Done at dawn." },
            "A sword of your own.", false, "The gates and posts.", "Night work, outdoors.", "The golem keeper",
            "Guarding.", "Senior hand", "Golem keeper");
        card("SMELT", "Smelter", ShiftRole.SMELT, "You smelt the stores' raw ore into ingots for the smiths.",
            new String[]{ "Collect the raw ore at the storehouse (a deposit is held).", "Smelt it in any furnace, with your own fuel.",
                "Bring every ingot to the marked chest.", "The deposit comes back with the delivery." },
            "Fuel of your own; any furnace.", false, "The storehouse and the smithy.", "Hot work indoors. Short ore is excused.",
            "The master smelter", "Smelting.", "Senior smelter", "Master smelter");
        card("SMITH", "Smith's hand", ShiftRole.CRAFT, "You work the smithy's iron into bars and fittings.",
            new String[]{ "Collect the iron at the storehouse (a deposit is held).", "Make iron bars at a crafting table.",
                "Bring the bars to the smithy's chest.", "The deposit comes back." },
            "A crafting table (any).", true, "The smithy.", "Indoors. Short makings are excused.", "The blacksmith",
            "Smithing.", "Journeyman smith", "Master smith");
        card("TAILOR", "Tailor's hand", ShiftRole.CRAFT, "You make up the tailor's cloth goods from the stores' wool.",
            new String[]{ "Collect the wool at the storehouse.", "Make carpets at a crafting table.", "Bring them to the tailor's chest." },
            "A crafting table (any).", true, "The tailor's shop.", "Indoors.", "The tailor", "Tailoring.", "Cutter", "Master tailor");
        card("FLETCHER", "Fletcher's hand", ShiftRole.CRAFT, "You make arrows for the watch from the stores' flint and sticks.",
            new String[]{ "Collect flint and sticks at the storehouse.", "Make arrows at a crafting table (feathers from the stores or your own).",
                "Bring them to the fletcher's chest." },
            "A crafting table; feathers if the stores are out.", true, "The fletcher's.", "Indoors.", "The fletcher", "Fletching.",
            "Senior fletcher", "Master fletcher");
        card("ENCHANT", "Enchanter's clerk", ShiftRole.CRAFT, "You bind the books the enchanter works from.",
            new String[]{ "Collect paper and leather at the storehouse.", "Bind books at a crafting table.", "Bring them to the enchanter's chest." },
            "A crafting table.", true, "The enchanter's.", "Indoors, quiet.", "The enchanter", "Bookbinding and letters.",
            "Senior clerk", "Enchanter's deputy");
        card("BREW", "Brewer's hand", ShiftRole.CRAFT, "You make the bottles the brewery fills.",
            new String[]{ "Collect glass at the storehouse.", "Make bottles at a crafting table.", "Bring them to the brewer's chest." },
            "A crafting table.", true, "The brewery.", "Indoors.", "The brewer", "Brewing.", "Senior hand", "Head brewer");
        card("BEEKEEP", "Beekeeper's hand", ShiftRole.CRAFT, "You turn the hives' comb into candles.",
            new String[]{ "Collect comb and string at the storehouse.", "Make candles at a crafting table.", "Bring them to the beekeeper's chest." },
            "A crafting table.", true, "The apiary.", "Indoors; the bees are outside.", "The beekeeper", "Beekeeping.",
            "Senior hand", "Head beekeeper");
        card("FIREWORKS", "Fireworks hand", ShiftRole.CRAFT, "You roll rockets for the town's shows and the players' shop.",
            new String[]{ "Collect paper and gunpowder at the storehouse.", "Make rockets at a crafting table.", "Bring them to the fireworks chest." },
            "A crafting table.", true, "The fireworks shed.", "Indoors; gunpowder, so no flames.", "The fireworks maker",
            "Fireworks.", "Senior hand", "Master of fireworks");
        card("CARTOGRAPHER", "Cartographer's clerk", ShiftRole.CRAFT, "You make the paper the map room draws on.",
            new String[]{ "Collect cane at the storehouse.", "Make paper at a crafting table.", "Bring it to the map room's chest." },
            "A crafting table.", true, "The map room.", "Indoors.", "The cartographer", "Map-making.", "Senior clerk", "Cartographer");
        card("TEACHER", "Teacher's assistant", ShiftRole.CRAFT, "You make up the school's books and help the teacher.",
            new String[]{ "Collect paper and leather at the storehouse.", "Bind books at a crafting table.", "Bring them to the storehouse for the school." },
            "A crafting table.", true, "The school.", "Indoors, mornings.", "The teacher", "Letters.", "Senior assistant", "Teacher");
        card("FISH", "Fisher", ShiftRole.FISH, "You fish the town's water and bring the catch in.",
            new String[]{ "Go to the marked water.", "Fish from the bank or the jetty (a rod is lent if you have none).",
                "Each fish caught near the mark counts.", "On a salary it goes to the stores; on commission you hand it in." },
            "A rod (lent if you have none).", true, "The town's water.", "Outdoors in all weather.", "The head fisher",
            "Fishing.", "Senior fisher", "Head fisher");
        card("DIVER", "Diver's hand", ShiftRole.FISH, "You fish the beds the diver tends and bring the catch in.",
            new String[]{ "Go to the marked water.", "Fish there (a rod is lent).", "Each fish counts." },
            "A rod (lent).", true, "The town's water.", "Outdoors, wet.", "The diver", "Fishing and the water.", "Senior hand", "Head diver");
        card("STORE", "Storehand", ShiftRole.HAUL, "You move goods from the storehouse to the workshops that are short.",
            new String[]{ "Your card names the goods and the workshop.", "Load up at the storehouse (a small deposit is held).",
                "Put the load in the marked chest.", "The deposit comes back with your pay." },
            "Nothing: a strong back.", true, "The storehouse.", "Indoors and out.", "The storekeeper", "Hauling.",
            "Senior storehand", "Storekeeper");
        card("HAUL", "Hauler", ShiftRole.HAUL, "You carry loads from the stores to where the town needs them.",
            new String[]{ "Load up at the storehouse (a deposit is held).", "Follow the arrow to the chest that's short.",
                "Put the whole load in.", "On to the next." },
            "Nothing.", true, "The storehouse and the workshops.", "Out in all weather.", "The head hauler", "Hauling.",
            "Senior hauler", "Head hauler");
        card("COURIER", "Courier", ShiftRole.HAUL, "You carry the town's loads and letters about the place.",
            new String[]{ "Collect at the storehouse.", "Deliver to the marked chest.", "Back for the next." },
            "Nothing.", true, "About the town.", "Out in all weather.", "The postmaster", "Hauling.", "Senior courier", "Postmaster");
        card("FERRY", "Ferry hand", ShiftRole.HAUL, "You load the ferry's cargo and carry it to the landing's chests.",
            new String[]{ "Collect at the storehouse.", "Deliver to the marked chest.", "Back for the next." },
            "Nothing.", true, "The landing.", "Outdoors, by the water.", "The ferryman", "Hauling.", "Senior hand", "Ferryman");
        card("EMERALD", "Trader's porter", ShiftRole.HAUL, "You carry the emerald trader's goods in and out of the stores.",
            new String[]{ "Collect at the storehouse.", "Deliver to the marked chest.", "Back for the next." },
            "Nothing.", true, "The storehouse.", "Indoors and out.", "The emerald trader", "Hauling and trade.", "Senior porter", "Trader");
        card("NETHER", "Nether runner's porter", ShiftRole.HAUL, "You carry what the Nether runners bring back to where it is used.",
            new String[]{ "Collect at the storehouse.", "Deliver to the marked chest.", "Back for the next." },
            "Nothing.", true, "The storehouse.", "Indoors and out.", "The Nether runner", "Hauling.", "Senior porter", "Head runner");
        card("SHOP", "Counter hand", ShiftRole.COUNTER, "You serve the customers at the counter, first in line first.",
            new String[]{ "Stand behind the counter at opening.", "Right-click the counter to open its screen.",
                "Serve the one first in the queue from the Work tab.", "Keep at it till closing." },
            "Nothing.", true, "The shop counter.", "Indoors, on your feet. A quiet day is no fault of yours.", "The shopkeeper",
            "Service.", "Head of the counter", "Shop manager");
        card("BANK", "Bank clerk", ShiftRole.COUNTER, "You serve at the town's counters as the bank's clerk.",
            new String[]{ "Stand behind the counter at opening.", "Serve the queue in order.", "Keep at it till closing." },
            "Nothing.", true, "The counter.", "Indoors.", "The banker", "Service and figures.", "Senior clerk", "Bank manager");
        card("CLERK", "Hall clerk", ShiftRole.COUNTER, "You serve the public at the town's counters for the hall.",
            new String[]{ "Stand behind the counter at opening.", "Serve the queue in order.", "Keep at it till closing." },
            "Nothing.", true, "The counter.", "Indoors.", "The town clerk", "Service.", "Senior clerk", "Town clerk");
        card("COOK", "Cook", ShiftRole.COOK, "You cook the tickets as they come in, none burnt.",
            new String[]{ "Arrive at the kitchen at opening.", "A ticket shows the dish and its station.",
                "Put it on; take it off when it says READY.", "The makings come from the stores." },
            "Nothing: the kitchen's.", true, "The kitchen.", "Hot, on your feet. A quiet night is no fault of yours.", "The head cook",
            "Cooking.", "Head cook", "Kitchen manager");
        card("WAITER", "Waiter", ShiftRole.WAITER, "You carry each plate from the pass to its table.",
            new String[]{ "Arrive at the dining room at opening.", "Pick up the plate on the pass.", "Set it down at its table.",
                "The wrong table is a mistake." },
            "Nothing.", true, "The dining room.", "Indoors, on your feet.", "The head waiter", "Service.", "Head waiter", "Front of house manager");
        card("BAKE", "Baker's hand", ShiftRole.BAKE, "You bake what the stores are short of and get it out before it burns.",
            new String[]{ "Arrive at the bakery at six.", "The card names the batch the stores are short of.",
                "Put it in the oven; take it out at READY.", "The bake goes into the stores; a burnt batch pays nothing." },
            "Nothing: the bakery's ovens.", true, "The bakery.", "Hot, early. Short flour is excused.", "The master baker",
            "Baking.", "Senior baker", "Master baker");
        card("HEAL", "Healer's assistant", ShiftRole.HEALER, "You see to the laid-up with the stores' remedies.",
            new String[]{ "Report at the infirmary.", "Go to the patient the card names.", "Stand by the bed: the best remedy the stores have is given.",
                "On to the next." },
            "Nothing: the stores' remedies.", true, "The infirmary and the patients' homes.", "Indoors.", "The healer",
            "Healing.", "Infirmary nurse", "Head of the infirmary");
        card("BUILD", "Builder's hand", ShiftRole.BUILD, "You lay the missing blocks in the town's buildings.",
            new String[]{ "Collect the blocks at the storehouse (a deposit is held).", "Lay each on its marked spot.",
                "A block in the wrong place is refused.", "The deposit comes back when all are in." },
            "Nothing: the blocks are the town's.", true, "The building the card names.", "Outdoors, at height.", "The foreman",
            "Building.", "Foreman", "Master builder");
        card("REDSTONE", "Engineer's hand", ShiftRole.BUILD, "You lay the blocks of the engineer's works.",
            new String[]{ "Collect the blocks at the storehouse.", "Lay each on its marked spot.", "The deposit comes back when done." },
            "Nothing.", true, "The works the card names.", "Indoors and out.", "The redstone engineer", "Building.", "Senior hand", "Engineer");
        card("WAGON", "Wheelwright's hand", ShiftRole.WAGON, "You mend the town's battered wagon with planks.",
            new String[]{ "Go to the wagon yard.", "Right-click the wagon holding planks of your own.", "Two planks mend a step; keep at it till sound." },
            "Planks of your own.", false, "The wagon yard.", "Outdoors.", "The wheelwright", "Woodwork.", "Senior hand", "Master wheelwright");
    }

    /** Every card, in order. */
    public static List<Card> all() {
        return new ArrayList<>(CARDS.values());
    }

    /** The card for a trade (a StationTask name, or BUILD, BAKE, WAITER, WAGON, COURIER, CLERK, TEACHER, QUARRY). */
    @Nullable
    public static Card of(@Nullable String trade) {
        if (trade == null) return null;
        Card c = CARDS.get(trade.toUpperCase(Locale.ROOT));
        if (c != null) return c;
        StationTask t = JobMarket.named(trade);
        return t == null ? null : CARDS.get(t.name());
    }

    /** The card for a shift taken off the board as a day's work (its first trade). */
    public static Card forRole(ShiftRole r) {
        for (Card c : CARDS.values()) if (c.shift() == r) return c;
        throw new IllegalStateException("no card for " + r);
    }

    /** The shift a trade is worked at by a player, or null. */
    @Nullable
    public static ShiftRole shiftFor(@Nullable String trade) {
        Card c = of(trade);
        return c == null ? null : c.shift();
    }

    // ------------------------------------------------------------------ the lines on the card

    /** How output becomes stars, in words. */
    public static String starsLine(Card c, int target) {
        int two = (int) Math.ceil(target * WorkMath.STAR_AT[0]), four = (int) Math.ceil(target * WorkMath.STAR_AT[2]),
            five = (int) Math.ceil(target * WorkMath.STAR_AT[3]);
        return "★ any work · ★★ " + two + " · ★★★ " + target + " · ★★★★ " + four + " · ★★★★★ " + five + " " + c.measure().many()
            + ". Each task is also rated for speed against par and mistakes.";
    }

    /** What earns a warning, and what a dismissal, on each pay. */
    public static List<String> rules(boolean commission) {
        if (commission) {
            return List.of("No firing for low output: you simply earn less.",
                "A warning for misconduct: theft, damage, walking off a shift, or two days missed without leave while in town.",
                "Dismissal after a second warning for misconduct, or for theft from the employer outright.");
        }
        return List.of("Missing the goal now and then is fine: the stars show how you're doing.",
            "Only a pattern does harm: four poor or missed days in a week brings a talk, then a warning.",
            "Dismissal only after a warning, with a reason, and a day's notice.");
    }

    /** What is excused, in a line. */
    public static String excused(Card c) {
        String s = "Hours with no work to be had come off the target";
        if (c.measure().outdoors()) s += "; rain takes a fifth off";
        if (c.shift() == ShiftRole.CRAFT || c.shift() == ShiftRole.SMELT || c.shift() == ShiftRole.BAKE) s += "; short makings are the stores' fault, not yours";
        if (c.shift() == ShiftRole.MINE) s += "; a face with no ore is bad luck, not bad work";
        return s + ". Being offline is never absence.";
    }
}
