package com.jrpetty.mcassistant.client;

import com.jrpetty.mcassistant.entity.TalkTopic;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;

/**
 * What a player can say to a folk, organised: six categories (Chat, About, Ask, Trade, Town, World), each a short
 * list of its best options, the rest one level down in a group; and a few suggestions worked out from how things
 * stand (a quest's step, its request of you, a fine owed, its birthday). Data only, and no client classes, so a game
 * test can check that nothing the game offers fell out of it: the talk screen draws it.
 *
 * <p>Each option's label and tooltip are language keys, {@code talk.mc_assistant.<id>} and {@code ….tip}. What an
 * option sends is the same {@link TalkTopic} and text the screen always sent, so the answers still come from
 * FolkTalk; typed words still reach everything the folk understands.
 */
public final class TalkMenu {

    private TalkMenu() {}

    /** What picking an option does. */
    public enum Kind {
        /** Send its topic (and its text, if any). */
        ASK,
        /** Say its text out (TalkTopic.SAY): the folk understands it as if typed. */
        SAY,
        /** Put its text in the box to finish yourself ("Could I have 8 bread?"). */
        PROMPT,
        /** Open its children, one level down. */
        GROUP,
        /** The places it can walk you to (Guide). */
        PLACES,
        /** Show its page: the profile card or the skills. */
        PAGE,
        /** The village board (VillageAskPayload). */
        BOARD,
        /** Its work record (RecordScreen). */
        RECORD,
        /** Its pack. */
        PACK
    }

    /** A page that takes the place of the conversation. */
    public enum Page { TALK, PROFILE, SKILLS }

    /**
     * One option. {@code label} and {@code tip}, when set, are literal (a quest's buttons, a place) in place of the
     * language keys; {@code text} is what is sent or prompted (the folk understands English, so it is not translated).
     */
    public record Entry(String id, Item icon, Kind kind, TalkTopic topic, String text, Predicate<Context> when,
                        List<Entry> children, String label, String tip) {

        public String labelKey() { return "talk.mc_assistant." + id; }

        public String tipKey() { return "talk.mc_assistant." + id + ".tip"; }

        public boolean shows(Context c) { return when.test(c); }

        public boolean isGroup() { return kind == Kind.GROUP; }
    }

    /** The six categories, in their order down the side of the screen. */
    public enum Category {
        CHAT("chat", Items.WRITABLE_BOOK),
        ABOUT("about", Items.NAME_TAG),
        ASK("ask", Items.MAP),
        TRADE("trade", Items.EMERALD),
        TOWN("town", Items.BELL),
        WORLD("world", Items.SPYGLASS);

        public final String id;
        public final Item icon;

        Category(String id, Item icon) {
            this.id = id;
            this.icon = icon;
        }

        public String labelKey() { return "talk.mc_assistant.cat." + id; }

        public String tipKey() { return "talk.mc_assistant.cat." + id + ".tip"; }

        public List<Entry> entries() { return MENU.get(ordinal()); }

        /** The category called {@code id} ("trade"), or null. */
        public static Category of(String id) {
            for (Category c : values()) if (c.id.equals(id)) return c;
            return null;
        }
    }

    // ------------------------------------------------------------------ how things stand

    /**
     * What the folk's last reply says of how things stand (FolkReplyPayload.context, and its other fields). An empty
     * context (an older server) is {@code known == false}: every option then shows, as none can be ruled out.
     */
    public record Context(boolean known, String trade, Set<String> flags, String standing, int affinity,
                          boolean following, boolean errand, boolean canDeliver) {

        public static final Context NONE = new Context(false, "", Set.of(), "", 0, false, false, false);

        /** Read from a reply's context line ("v1 trade=FARM child standing=FRIEND fine") and its other fields. */
        public static Context of(String context, int affinity, boolean following, boolean errand, boolean canDeliver) {
            if (context == null || context.isBlank()) return new Context(false, "", Set.of(), "", affinity, following, errand, canDeliver);
            Set<String> flags = new HashSet<>();
            String trade = "", standing = "";
            for (String f : context.trim().split("\\s+")) {
                int eq = f.indexOf('=');
                if (eq < 0) { flags.add(f); continue; }
                String k = f.substring(0, eq), v = f.substring(eq + 1);
                if (k.equals("trade")) trade = v;
                else if (k.equals("standing")) standing = v;
                else flags.add(k + "=" + v);
            }
            return new Context(true, trade, Set.copyOf(flags), standing, affinity, following, errand, canDeliver);
        }

        public boolean has(String flag) { return flags.contains(flag); }

        /** A {@code key=value} flag's value, or "". */
        public String value(String key) {
            String p = key + "=";
            for (String f : flags) if (f.startsWith(p)) return f.substring(p.length());
            return "";
        }

        public boolean tradeIs(String... names) {
            for (String n : names) if (n.equals(trade)) return true;
            return false;
        }

        /** Out of favour with the town or with it: an apology is the thing to say. */
        public boolean inDisgrace() {
            return affinity < -10 || standing.equals("OUTCAST") || standing.equals("UNWELCOME");
        }

        /** The town has turned against you: only a gift or paying what you owe is heard (FolkTalk.answer). */
        public boolean shunned() {
            return affinity < 0 && standing.equals("OUTCAST");
        }
    }

    private static final Predicate<Context> ALWAYS = c -> true;

    /** Shown when the flag is set, or when nothing is known (an older server). */
    private static Predicate<Context> flag(String f) {
        return c -> !c.known() || c.has(f);
    }

    /** Shown to a folk of one of these trades, or when nothing is known. */
    private static Predicate<Context> trade(String... t) {
        return c -> !c.known() || c.tradeIs(t);
    }

    /** [roles-c] Shown to a folk of this trade holding this post in it ("post=BAKER"), or when nothing is known. */
    private static Predicate<Context> post(String trade, String post) {
        return c -> !c.known() || c.tradeIs(trade) && c.value("post").equals(post);
    }

    /** [roles-c] Shown to a folk of one of these trades with this flag ("inn", "miller"), or when nothing is known. */
    private static Predicate<Context> keeps(String flag, String... trades) {
        return c -> !c.known() || c.tradeIs(trades) && c.has(flag);
    }

    // ------------------------------------------------------------------ the catalogue

    private static Entry ask(String id, Item icon, TalkTopic t) {
        return new Entry(id, icon, Kind.ASK, t, "", ALWAYS, List.of(), null, null);
    }

    private static Entry ask(String id, Item icon, TalkTopic t, Predicate<Context> when) {
        return new Entry(id, icon, Kind.ASK, t, "", when, List.of(), null, null);
    }

    private static Entry say(String id, Item icon, String text) {
        return new Entry(id, icon, Kind.SAY, TalkTopic.SAY, text, ALWAYS, List.of(), null, null);
    }

    private static Entry say(String id, Item icon, String text, Predicate<Context> when) {
        return new Entry(id, icon, Kind.SAY, TalkTopic.SAY, text, when, List.of(), null, null);
    }

    private static Entry prompt(String id, Item icon, TalkTopic t, String text) {
        return new Entry(id, icon, Kind.PROMPT, t, text, ALWAYS, List.of(), null, null);
    }

    private static Entry prompt(String id, Item icon, TalkTopic t, String text, Predicate<Context> when) {
        return new Entry(id, icon, Kind.PROMPT, t, text, when, List.of(), null, null);
    }

    private static Entry group(String id, Item icon, Entry... children) {
        return new Entry(id, icon, Kind.GROUP, TalkTopic.OPEN, "", ALWAYS, List.of(children), null, null);
    }

    private static Entry special(String id, Item icon, Kind kind) {
        return new Entry(id, icon, kind, TalkTopic.OPEN, "", ALWAYS, List.of(), null, null);
    }

    // Entries a suggestion also points at, kept by name.
    static final Entry SORRY = say("sorry", Items.WHITE_TULIP, "I'm sorry");
    static final Entry FOLLOW = ask("follow", Items.LEAD, TalkTopic.FOLLOW, c -> !c.following());
    static final Entry STAY = ask("stay", Items.OAK_DOOR, TalkTopic.STAY, Context::following);
    static final Entry GIFT = ask("gift", Items.POPPY, TalkTopic.GIFT);
    static final Entry DELIVER = ask("deliver", Items.HOPPER, TalkTopic.DELIVER, Context::errand);
    static final Entry FINE = ask("fine", Items.GOLD_NUGGET, TalkTopic.FINE, flag("fine"));
    static final Entry INTERVIEWS = say("interviews", Items.ENCHANTED_BOOK, "Any interviews coming up?");
    static final Entry LEADER = say("leader", Items.GOLDEN_HELMET, "Show me the leader's page");
    static final Entry GODPARENT = ask("godparent", Items.TOTEM_OF_UNDYING, TalkTopic.GODPARENT, flag("child"));
    static final Entry MAPS = ask("maps", Items.RECOVERY_COMPASS, TalkTopic.MAPS);
    static final Entry NETHER = ask("nether_report", Items.BLAZE_POWDER, TalkTopic.NETHER);
    static final Entry CAVES = ask("caves_report", Items.RAW_IRON, TalkTopic.CAVES);
    static final Entry STORES = prompt("stores", Items.HAY_BLOCK, TalkTopic.STORES, "Could I have 8 bread?");
    static final Entry REPAIR = ask("repair", Items.GRINDSTONE, TalkTopic.REPAIR);
    static final Entry BANK = ask("bank", Items.GOLD_BLOCK, TalkTopic.BANK);
    static final Entry ROCKETS = say("rockets", Items.FIREWORK_ROCKET, "Could I buy some rockets for my elytra?");
    static final Entry WATCH = ask("watch", Items.ENDER_EYE, TalkTopic.WATCH);
    static final Entry FOR_SALE = ask("for_sale", Items.WHEAT, TalkTopic.FOR_SALE);
    static final Entry HAGGLE = ask("haggle", Items.COPPER_INGOT, TalkTopic.HAGGLE, trade("STORE", "SHOP", "COOK"));
    static final Entry MEAL = ask("meal", Items.BREAD, TalkTopic.MEAL);
    static final Entry LESSON = say("lesson", Items.WRITTEN_BOOK, "What's my next lesson?");
    static final Entry SEE_TO = ask("see_to", Items.GLISTERING_MELON_SLICE, TalkTopic.SEE_TO);   // [roles-a] the healer
    // [roles-b] The crafts' own: the tailor's orders, the brewer's potions, the enchanter's table, the beekeeper's honey.
    static final Entry TAILOR = ask("tailor_order", Items.WHITE_BANNER, TalkTopic.TAILOR, trade("TAILOR"));
    static final Entry POTION = ask("potion", Items.POTION, TalkTopic.POTION, trade("BREW"));
    static final Entry ENCHANT = ask("enchant", Items.ENCHANTED_BOOK, TalkTopic.ENCHANT, trade("ENCHANT"));
    static final Entry HONEY = ask("honey", Items.HONEY_BOTTLE, TalkTopic.HONEY, trade("BEEKEEP"));
    // [roles-d] The outdoor and resource trades' own: their goods out of the stores, and what they will take you along on.
    static final Entry GROWING = say("farm_growing", Items.WHEAT_SEEDS, "What's growing in your fields?", trade("FARM"));
    static final Entry PRODUCE = say("farm_produce", Items.CARROT, "Could I buy some of your produce?", trade("FARM"));
    static final Entry TIMBER = say("wood_timber", Items.OAK_LOG, "I'd like a timber order", trade("WOOD"));
    static final Entry FRESH_FISH = say("fish_buy", Items.COD, "Could I buy some fresh fish?", trade("FISH"));
    static final Entry BOAT = say("fish_boat", Items.OAK_BOAT, "Can I come out on the boat with you?", trade("FISH"));
    static final Entry HUNTING = say("hunt_go", Items.BOW, "Can I come hunting with you?", trade("HUNT"));
    static final Entry HIDES = say("hunt_hides", Items.LEATHER, "Could I buy leather and feathers?", trade("HUNT"));
    static final Entry WOOL = say("ranch_wool", Items.WHITE_WOOL, "Could I buy some wool?", trade("RANCH"));
    static final Entry HORSE = say("ranch_horse", Items.SADDLE, "Could I buy a horse?", trade("RANCH"));
    static final Entry ACROSS = say("ferry_across", Items.SPRUCE_BOAT, "Take me across, please", trade("FERRY"));
    static final Entry GOLEM = say("golem_order", Items.IRON_BLOCK, "Could you build a golem for my base?", trade("GOLEMS"));
    static final Entry LAMPS = say("lamp_street", Items.REDSTONE_LAMP, "Could you light my street?", trade("REDSTONE"));
    static final Entry VILLAGERS = say("emerald_sells", Items.EMERALD, "What do the villagers sell?", trade("EMERALD"));
    static final Entry EMERALD_ASK = prompt("emerald_ask", Items.ENCHANTED_BOOK, TalkTopic.SAY, "Find us a Mending book", trade("EMERALD"));
    static final Entry DIVE = prompt("diver_clay", Items.CLAY_BALL, TalkTopic.SAY, "Could you dive for 16 clay?", trade("DIVER"));
    static final Entry DIVE_READY = say("diver_ready", Items.SEA_PICKLE, "Have you got my clay yet?", trade("DIVER"));
    static final Entry ORE = say("mine_ore", Items.RAW_IRON, "Could I buy some coal or ore?", trade("MINE"));
    static final Entry ARROWS = say("fletcher_arrows", Items.ARROW, "Could I buy some arrows?", trade("FLETCHER"));
    // [roles-c] The hospitality posts' own (HospitalityTalk hears the words): the inn's room, the barkeep's pint, round and
    // bottles, the baker's bread, the miller's sack, and the rancher's meat from its butcher's block.
    static final Entry ROOM = say("inn_room", Items.RED_BED, "A room for the night, please", keeps("inn", "COOK", "SHOP"));
    static final Entry PINT = say("bar_pint", Items.HONEY_BOTTLE, "A pint, please", post("COOK", "BARKEEP"));
    static final Entry ROUND = say("bar_round", Items.CAULDRON, "A round for the house!", post("COOK", "BARKEEP"));
    static final Entry BOTTLES = say("bar_bottles", Items.GLASS_BOTTLE, "Here are my empty bottles", post("COOK", "BARKEEP"));
    static final Entry BREAD = say("bakery_bread", Items.BREAD, "Some fresh bread, please", post("COOK", "BAKER"));
    static final Entry SACK = say("mill_sack", Items.WHEAT, "Could I buy a sack of wheat?", keeps("miller", "FARM"));
    static final Entry MEAT = say("ranch_meat", Items.BEEF, "Could I buy some meat?", trade("RANCH"));
    static final Entry ATLAS = ask("atlas", Items.SPYGLASS, TalkTopic.ATLAS);                     // the scout's own, by name
    static final Entry LETTER = ask("letter", Items.FEATHER, TalkTopic.LETTER);                  // the courier's (the post) own

    /** The profile and skills pages, the work record and the pack: the header's links. */
    public static final Entry PROFILE = special("profile", Items.PAPER, Kind.PAGE);
    public static final Entry SKILLS = special("skills", Items.EXPERIENCE_BOTTLE, Kind.PAGE);
    public static final Entry RECORD = special("record", Items.CLOCK, Kind.RECORD);
    public static final Entry PACK = special("pack", Items.BUNDLE, Kind.PACK);
    public static final List<Entry> LINKS = List.of(PROFILE, SKILLS, RECORD, PACK);

    /** The categories' entries, by Category.ordinal(). */
    private static final List<List<Entry>> MENU = List.of(
        // Chat: passing the time of day.
        List.of(
            ask("how", Items.SUNFLOWER, TalkTopic.HOW),
            ask("news", Items.PAPER, TalkTopic.VILLAGE),
            ask("gossip", Items.FEATHER, TalkTopic.GOSSIP),
            ask("joke", Items.PUFFERFISH, TalkTopic.JOKE),
            ask("praise", Items.SWEET_BERRIES, TalkTopic.PRAISE),
            group("chat_more", Items.BOOK,
                ask("doing", Items.CLOCK, TalkTopic.DOING),
                ask("hobby", Items.FISHING_ROD, TalkTopic.HOBBY),
                ask("fashion", Items.LEATHER_CHESTPLATE, TalkTopic.FASHION),
                MEAL,
                ask("dice", Items.RABBIT_FOOT, TalkTopic.DICE),
                SORRY)),
        // About: its life.
        List.of(
            ask("about", Items.NAME_TAG, TalkTopic.ABOUT),
            ask("people", Items.CAKE, TalkTopic.PEOPLE),
            ask("dreams", Items.AMETHYST_SHARD, TalkTopic.DREAMS),
            ask("memory", Items.PAINTING, TalkTopic.MEMORY),
            group("home", Items.LANTERN,
                ask("house", Items.RED_BED, TalkTopic.HOUSE),
                ask("pet", Items.BONE, TalkTopic.PET),
                ask("keepsake", Items.HEART_OF_THE_SEA, TalkTopic.KEEPSAKE),
                GODPARENT),
            group("work", Items.IRON_PICKAXE,
                ask("workings", Items.CRAFTING_TABLE, TalkTopic.WORKINGS),
                ask("knack", Items.EXPERIENCE_BOTTLE, TalkTopic.KNACK),
                ask("worth", Items.GOLD_NUGGET, TalkTopic.WORTH),
                ask("teach", Items.STONE_PICKAXE, TalkTopic.TEACH, c -> !c.has("child")),
                say("apprentice", Items.SMITHING_TABLE, "Will you take me as your apprentice?",
                    c -> !c.known() || c.has("master") && !c.has("apprentice")),
                LESSON)),
        // Ask: favours, errands and quests; its company.
        List.of(
            ask("jobs", Items.MAP, TalkTopic.JOBS),
            ask("help", Items.CHEST, TalkTopic.HELP),
            FOLLOW,
            STAY,
            special("guide", Items.COMPASS, Kind.PLACES),
            ask("hire", Items.IRON_SWORD, TalkTopic.HIRE, c -> !c.has("child")),
            group("favours", Items.APPLE,
                ask("shifts", Items.CLOCK, TalkTopic.SHIFTS),                 // [earning] a paid shift, from the boss
                ask("favour", Items.APPLE, TalkTopic.FAVOUR),
                ask("short", Items.COMPOSTER, TalkTopic.SHORT),
                ask("lost", Items.BRUSH, TalkTopic.LOST),
                ask("orders", Items.GOAT_HORN, TalkTopic.ORDERS),
                ask("quests", Items.OAK_SIGN, TalkTopic.QUESTS))),
        // Trade: gifts, buying and selling, money.
        List.of(
            GIFT,
            DELIVER,
            ask("trade", Items.EMERALD, TalkTopic.TRADE),
            group("shop", Items.BARREL,
                FOR_SALE,
                STORES,
                prompt("bulk", Items.CHEST_MINECART, TalkTopic.BULK, "I'd like to order 64 "),
                prompt("make", Items.ANVIL, TalkTopic.ORDER, "Make me an iron sword"),
                REPAIR,
                HAGGLE,
                ROCKETS,
                TAILOR, POTION, ENCHANT, HONEY,                              // [roles-b] each only at its own craft
                GROWING, PRODUCE, TIMBER, FRESH_FISH, HIDES, WOOL, HORSE, ORE, ARROWS,   // [roles-d] the outdoor trades' goods
                VILLAGERS, EMERALD_ASK, DIVE, DIVE_READY,
                ROOM, PINT, ROUND, BOTTLES, BREAD, SACK, MEAT),                      // [roles-c] the hospitality posts' own
            group("money", Items.GOLD_INGOT,
                BANK,
                prompt("invest", Items.RAW_GOLD, TalkTopic.INVEST, "I'd like to invest 50 coins"),
                ask("contract", Items.INK_SAC, TalkTopic.CONTRACT),
                ask("stall", Items.OAK_FENCE, TalkTopic.STALL),
                ask("auction", Items.NOTE_BLOCK, TalkTopic.AUCTION),
                ask("prices", Items.COMPARATOR, TalkTopic.PRICES)),
            group("services", Items.SCAFFOLDING,
                ask("housing", Items.BRICKS, TalkTopic.HOUSING),
                ask("commission", Items.OAK_PLANKS, TalkTopic.COMMISSION),
                ask("sponsor", Items.PUMPKIN_PIE, TalkTopic.SPONSOR),
                ask("escort", Items.SHIELD, TalkTopic.ESCORT),
                ask("charter", Items.MINECART, TalkTopic.CHARTER),
                SEE_TO,
                BOAT, HUNTING, ACROSS, GOLEM, LAMPS)),                          // [roles-d] out with them, across, a golem, a lamp                                                    // [roles-a] the healer sees to you
        // Town: its ways, its people, its government, its law, its records.
        List.of(
            say("ways", Items.WHITE_BANNER, "What's this town like?"),
            ask("census", Items.ARMOR_STAND, TalkTopic.CENSUS),
            ask("repute", Items.PLAYER_HEAD, TalkTopic.REPUTE),
            group("council_group", Items.LECTERN,
                ask("council", Items.BOOKSHELF, TalkTopic.COUNCIL),
                prompt("build", Items.BRICK, TalkTopic.BUILD, "You should build a "),
                ask("citizen", Items.BLUE_BANNER, TalkTopic.CITIZEN, c -> !c.has("citizen")),
                INTERVIEWS,
                say("stand", Items.TARGET, "I'd like to stand for election"),
                say("vote", Items.PAPER, "Will you vote for me?"),
                LEADER),
            group("law", Items.IRON_BARS,
                WATCH,
                say("report", Items.REDSTONE_TORCH, "I want to report a crime"),
                say("roster", Items.WRITABLE_BOOK, "Who's on the roster today?"),
                say("swear", Items.IRON_HELMET, "Swear me in as a special constable"),
                FINE),
            group("records", Items.CHISELED_BOOKSHELF,
                special("board", Items.OAK_HANGING_SIGN, Kind.BOARD),
                ask("ledger", Items.WRITABLE_BOOK, TalkTopic.LEDGER),
                ask("chronicle", Items.WRITTEN_BOOK, TalkTopic.CHRONICLE),
                say("fireworks", Items.FIREWORK_STAR, "When are the next fireworks?"))),
        // World: the neighbours, the scouts, the caves, the Nether, maps.
        List.of(
            ask("rivals", Items.RED_BANNER, TalkTopic.RIVALS),
            LETTER,
            ATLAS,
            group("caves", Items.COBBLED_DEEPSLATE,
                CAVES,
                prompt("caves_ask", Items.TORCH, TalkTopic.CAVES, "Cave team, look east", trade("CAVE")),
                say("caves_go", Items.LANTERN, "Can I come along with the cave team?", trade("CAVE")),
                say("caves_map", Items.MAP, "Could I buy a copy of the cave map?", trade("CAVE"))),
            group("nether", Items.NETHERRACK,
                NETHER,
                prompt("nether_ask", Items.BLAZE_ROD, TalkTopic.NETHER, "Runners, bring us blaze rods", trade("NETHER")),
                say("nether_go", Items.FLINT_AND_STEEL, "Can I come through with you?", trade("NETHER")),
                say("nether_chart", Items.FILLED_MAP, "Could I buy a copy of your chart?", trade("NETHER"))),
            group("maps_group", Items.CARTOGRAPHY_TABLE,
                MAPS,
                ask("town_map", Items.FILLED_MAP, TalkTopic.TOWN_MAP),
                say("map_ocean", Items.PRISMARINE_SHARD, "I'd like an ocean explorer map", trade("CARTOGRAPHER")),
                say("map_woodland", Items.DARK_OAK_SAPLING, "I'd like a woodland explorer map", trade("CARTOGRAPHER")),
                say("map_treasure", Items.SUSPICIOUS_SAND, "I'd like a treasure map", trade("CARTOGRAPHER")),
                say("map_copy", Items.ITEM_FRAME, "A copy of the town's map, please", trade("CARTOGRAPHER")),
                prompt("map_commission", Items.WRITABLE_BOOK, TalkTopic.MAPS, "Map me the land to the east", trade("CARTOGRAPHER"))))
    );

    // ------------------------------------------------------------------ what shows

    /** A category's options that apply just now. */
    public static List<Entry> visible(List<Entry> entries, Context c) {
        List<Entry> out = new ArrayList<>(entries.size());
        for (Entry e : entries) {
            if (!e.shows(c)) continue;
            if (e.isGroup() && visible(e.children(), c).isEmpty()) continue;   // nothing in it applies: no dead end
            out.add(e);
        }
        return out;
    }

    /** The trade's own option, for the suggestions: what this folk is the one to ask about. */
    private static Entry forTrade(String trade) {
        return switch (trade) {
            case "CARTOGRAPHER" -> MAPS;
            case "NETHER" -> NETHER;
            case "CAVE" -> CAVES;
            case "STORE" -> STORES;
            case "SMITH" -> REPAIR;
            case "BANK" -> BANK;
            case "FIREWORKS" -> ROCKETS;
            case "GUARD" -> WATCH;
            case "SHOP" -> FOR_SALE;
            case "COOK" -> MEAL;
            case "HEAL" -> SEE_TO;                                           // [roles-a]
            case "TAILOR" -> TAILOR;                                         // [roles-b]
            case "BREW" -> POTION;                                           // [roles-b]
            case "ENCHANT" -> ENCHANT;                                       // [roles-b]
            case "BEEKEEP" -> HONEY;                                         // [roles-b]
            case "FARM" -> GROWING;                                          // [roles-d] the outdoor trades' own
            case "WOOD" -> TIMBER;
            case "FISH" -> FRESH_FISH;
            case "HUNT" -> HUNTING;
            case "RANCH" -> HORSE;
            case "FERRY" -> ACROSS;
            case "GOLEMS" -> GOLEM;
            case "REDSTONE" -> LAMPS;
            case "EMERALD" -> VILLAGERS;
            case "DIVER" -> DIVE;
            case "MINE" -> ORE;
            case "FLETCHER" -> ARROWS;
            case "SCOUT" -> ATLAS;
            case "HAUL" -> LETTER;                                           // the courier carries the post (Post)
            case "SMELT" -> REPAIR;                                          // mends at its forge where there is no smith
            default -> null;
        };
    }

    /** [roles-c] What this folk is the one to ask about: its post's own (the baker's bread, the barkeep's pint, the inn's
     *  room, the mill's sack) before its trade's. */
    private static Entry ownOf(Context c) {
        Entry post = switch (c.value("post")) {
            case "BAKER" -> BREAD;
            case "BARKEEP" -> PINT;
            default -> null;
        };
        if (post == null && c.has("inn")) post = ROOM;
        if (post == null && c.has("miller")) post = SACK;
        return post != null ? post : forTrade(c.trade());
    }

    /** The most suggestions the screen shows besides a quest's own buttons. */
    public static final int MAX_SUGGESTED = 3;

    /**
     * The few things most worth saying just now, most pressing first: what it asked of you, coming home with you, a
     * fine owed, an apology when out of favour (a gift when the town has shut you out), its birthday, an interview, the leader's page; then what this folk is
     * the one to ask about, by its trade. (A quest's buttons come before these: the screen adds them.)
     */
    public static List<Entry> suggested(Context c) {
        List<Entry> out = new ArrayList<>(MAX_SUGGESTED);
        if (c.errand()) out.add(DELIVER);
        if (c.following()) out.add(STAY);
        if (c.known()) {
            if (c.has("fine")) out.add(FINE);
            if (c.shunned()) out.add(GIFT);                     // shunned, only a gift or a fine paid is heard (FolkTalk.answer)
            else if (c.inDisgrace()) out.add(SORRY);
            if (c.has("birthday")) out.add(BIRTHDAY);
            if (c.has("interview")) out.add(INTERVIEWS);
            if (c.has("apprentice")) out.add(LESSON);
            if (c.has("leader")) out.add(LEADER);
            Entry own = c.has("child") ? GODPARENT : ownOf(c);
            if (own != null && own.shows(c) && !out.contains(own)) out.add(own);
        }
        return out.size() > MAX_SUGGESTED ? List.copyOf(out.subList(0, MAX_SUGGESTED)) : out;
    }

    /** Its birthday: a present (what you hold) means the more today. */
    static final Entry BIRTHDAY = ask("birthday", Items.CAKE, TalkTopic.GIFT);

    // ------------------------------------------------------------------ for the tests

    /** Every entry anywhere in the menu, groups and their children, the links and the suggestions-only ones. */
    public static List<Entry> all() {
        List<Entry> out = new ArrayList<>();
        for (Category cat : Category.values()) collect(cat.entries(), out);
        out.addAll(LINKS);
        out.add(BIRTHDAY);
        return out;
    }

    private static void collect(List<Entry> in, List<Entry> out) {
        for (Entry e : in) {
            out.add(e);
            if (e.isGroup()) collect(e.children(), out);
        }
    }

    /** Every topic an entry anywhere in the menu can send. */
    public static Set<TalkTopic> topics() {
        Set<TalkTopic> out = new HashSet<>();
        for (Entry e : all()) if (!e.isGroup() && e.kind() != Kind.PAGE && e.kind() != Kind.RECORD) out.add(e.topic());
        return out;
    }

    /** Every phrase said out (Kind.SAY) or prompted, lower case. */
    public static Set<String> phrases() {
        Set<String> out = new HashSet<>();
        for (Entry e : all()) if (!e.text().isEmpty()) out.add(e.text().toLowerCase(Locale.ROOT));
        return out;
    }

    /** The ids of every entry: unique, for the language keys. */
    public static List<String> ids() {
        List<String> out = new ArrayList<>();
        for (Entry e : all()) out.add(e.id());
        return out;
    }

    static List<String> categoryIds() {
        return Arrays.stream(Category.values()).map(c -> c.id).toList();
    }
}
