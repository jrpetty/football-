package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.entity.goal.BuildGoal;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * Every trade, and how it actually gets done: the tools it works with, what it takes from the
 * village's stores, where its work goes, and which other trade supplies what it needs. Folk
 * can explain their own trade ("How does your trade work?") and say what the village is short
 * of; the starter kits (what a trade is given when nobody could make it, see {@link #kit}) live
 * here too.
 */
public final class Trades {

    private Trades() {}

    /** Something a trade uses: in words, what counts, how many it wants on hand, and who makes it. */
    public record Need(String words, Predicate<ItemStack> what, int want, String from) {}

    /** A trade: what the work is, its tools, what it takes from the stores, and where its work goes. */
    public record Trade(String work, List<Need> tools, List<Need> inputs, String output) {}

    static Need need(String words, Predicate<ItemStack> what, int want, String from) {
        return new Need(words, what, want, from);
    }

    public static Trade of(StationTask t) {
        return switch (t) {
            case FARM -> new Trade("I till a field by the water, sow it, and bring the harvest in; there's sugar cane on the ditch and a melon or two",
                List.of(need("a hoe", s -> s.is(ItemTags.HOES), 1, "the smith")),
                List.of(need("seed", s -> s.is(Items.WHEAT_SEEDS) || s.is(Items.CARROT) || s.is(Items.POTATO), 8, "last harvest")),
                "wheat, carrots, potatoes, sugar cane and melons go to the stores, for the larder, the café and the brewer");
            case WOOD -> new Trade("I fell trees in my wood and plant saplings where they stood",
                List.of(need("an axe", s -> s.is(ItemTags.AXES), 1, "the smith")),
                List.of(need("saplings", s -> s.is(ItemTags.SAPLINGS), 4, "the trees themselves")),
                "logs for the builders and the smelter's charcoal, and apples for the café");
            case MINE -> new Trade("I dig a mine: a shaft down, then tunnels along the seams",
                List.of(need("a pickaxe", s -> s.is(ItemTags.PICKAXES), 1, "the smith"),
                    need("torches", s -> s.is(Items.TORCH), 8, "coal and sticks")),
                List.of(),
                "stone for the builders; coal, iron and gold for the smelter; lapis for the enchanter; diamonds and obsidian for the ages");
            case RANCH -> new Trade("I keep the animals in the pen: feed them, breed them, shear the sheep and milk the cows."
                    + " If the pen has no pair, I take a lead out and walk a wild one home",
                List.of(need("shears", s -> s.is(Items.SHEARS), 1, "the smith"), need("a bucket", s -> s.is(Items.BUCKET), 1, "the smith"),
                    need("a lead", s -> s.is(Items.LEAD), 0, "slime and string")),
                List.of(need("wheat to feed them", s -> s.is(Items.WHEAT), 8, "the farmers")),
                "wool for the tailor, leather for the enchanter's books, feathers for the arrows, eggs and milk for the café, and meat");
            case GUARD -> new Trade("I keep watch: walk the streets by day, stand on the wall when the bell rings",
                List.of(need("a sword", s -> s.is(ItemTags.SWORDS), 1, "the smith"), need("a bow", s -> s.is(Items.BOW), 1, "the smith"),
                    need("arrows", s -> s.is(Items.ARROW), 16, "the smith")),
                List.of(),
                "a village that sleeps safe");
            case SMELT -> new Trade("I keep the furnaces going in the smeltery",
                List.of(),
                List.of(need("fuel", s -> s.is(Items.COAL) || s.is(Items.CHARCOAL) || s.is(ItemTags.LOGS), 8, "the miners and the woodcutters"),
                    need("raw iron", s -> s.is(Items.RAW_IRON), 3, "the miners"),
                    need("sand", s -> s.is(Items.SAND) || s.is(Items.RED_SAND) || s.is(Items.GLASS), 4, "the river bed — I dig it myself")),
                "iron for the smith, gold for the coin and the brewer, glass for the windows and the brewer's bottles");
            case FISH -> new Trade("I fish off the jetty, or the bank till there's a jetty",
                List.of(need("a fishing rod", s -> s.is(Items.FISHING_ROD), 1, "string and sticks")),
                List.of(),
                "fish for the larder and the café, pufferfish for the brewer, and now and then string and leather");
            case STORE -> new Trade("I keep the stores: sort them, and serve whoever asks",
                List.of(), List.of(), "a storehouse where everybody can find what they need");
            case HAUL -> new Trade("I carry: from the fields, the mines and the furnaces to the storehouse",
                List.of(), List.of(), "full stores and empty field chests");
            case SMITH -> new Trade("I work iron at the smithy's anvil, and fletch for the watch",
                List.of(),
                List.of(need("iron", s -> s.is(Items.IRON_INGOT), 8, "the smelter"),
                    need("string", s -> s.is(Items.STRING), 3, "the tailor"),
                    need("feathers", s -> s.is(Items.FEATHER), 4, "the rancher's hens"),
                    need("flint, or gravel to knap it from", s -> s.is(Items.FLINT) || s.is(Items.GRAVEL), 3, "the miners")),
                "picks, axes, hoes, shears and buckets for the trades, blades, bows, arrows and armour for the watch");
            case TAILOR -> new Trade("I weave at the loom in the workshop",
                List.of(need("a loom", s -> s.is(Items.LOOM), 0, "string and planks")),
                List.of(need("wool", s -> s.is(ItemTags.WOOL), 6, "the rancher")),
                "beds for the houses, string for the smith and the fishers, rugs and banners");
            case BEEKEEP -> new Trade("I keep hives on a meadow among the flowers. A full hive gives three comb to the shears or"
                    + " a bottle of honey; three comb and six planks make a new hive, and two bees fed a flower each make a third",
                List.of(need("shears", s -> s.is(Items.SHEARS), 1, "the smith")),
                List.of(need("glass bottles", s -> s.is(Items.GLASS_BOTTLE) || s.is(Items.GLASS), 3, "the smelter's glass"),
                    need("flowers, or bones for bone meal", s -> s.is(ItemTags.SMALL_FLOWERS) || s.is(Items.BONE) || s.is(Items.BONE_MEAL), 1,
                        "the meadow, the watch's bones, or you")),
                "honey for the café and the shop, and honeycomb for new hives");
            case BREW -> new Trade("I brew at the brewing stand, fired with blaze powder: three bottles of water and a nether wart"
                    + " from my own patch make awkward potions, then a glistering melon makes healing, sugar swiftness, a pufferfish"
                    + " water breathing",
                List.of(),
                List.of(need("blaze powder for the stand", s -> s.is(Items.BLAZE_POWDER), 1, "the Nether — I brought a pouch"),
                    need("nether wart", s -> s.is(Items.NETHER_WART), 1, "my wart patch"),
                    need("glass bottles", s -> s.is(Items.GLASS_BOTTLE) || s.is(Items.GLASS), 3, "the smelter's glass"),
                    need("melon and gold, sugar cane, or a pufferfish", s -> s.is(Items.MELON_SLICE) || s.is(Items.GLISTERING_MELON_SLICE)
                        || s.is(Items.SUGAR) || s.is(Items.SUGAR_CANE) || s.is(Items.PUFFERFISH) || s.is(Items.GOLDEN_CARROT), 1,
                        "the farmers and the fishers")),
                "healing for the watch (they carry one on the wall), and swiftness, night vision and water breathing for the shop");
            case ENCHANT -> new Trade("I bind books and enchant the village's best tools at the enchanting table; the more"
                    + " bookshelves round it, the stronger the enchantment",
                List.of(),
                List.of(need("lapis", s -> s.is(Items.LAPIS_LAZULI), 3, "the miners"),
                    need("sugar cane or paper", s -> s.is(Items.SUGAR_CANE) || s.is(Items.PAPER), 3, "the farmers' cane"),
                    need("leather", s -> s.is(Items.LEATHER), 1, "the rancher and the fishers")),
                "picks that dig faster, blades that cut deeper, armour that holds");
            case COOK -> new Trade("I cook at the café: cider, honey tea, pies, cakes and bread for the counter, and the"
                    + " tavern's drinks; I keep more of what sells, and make it the whole way from what the stores hold",
                List.of(),
                List.of(need("apples", s -> s.is(Items.APPLE), 2, "the woodcutters"),
                    need("wheat or bread", s -> s.is(Items.WHEAT) || s.is(Items.BREAD), 3, "the farmers"),
                    need("sugar, honey or eggs", s -> s.is(Items.SUGAR) || s.is(Items.SUGAR_CANE) || s.is(Items.HONEY_BOTTLE)
                        || s.is(Items.EGG), 1, "the farmers, the beekeeper and the rancher")),
                "the café's counter, and the folk's breaks");
            case SHOP -> new Trade("I keep the shop: the crafts' best work on the counter for anybody with coin, and what a"
                    + " house wants made up at my bench the whole way from the stores (logs to planks to sticks to a pick), more of"
                    + " what sells and less of what doesn't. With my hands at the bench we make whatever the town wears out — the"
                    + " watch's blades and armour, the rack's spare picks and axes — by any recipe there is, as far as our age has come",
                List.of(),
                List.of(need("timber", s -> s.is(net.minecraft.tags.ItemTags.LOGS) || s.is(net.minecraft.tags.ItemTags.PLANKS), 8,
                    "the woodcutters")),
                "coin for the treasury, the crafts' work into players' hands, and a house's comforts");
            case SCOUT -> new Trade("I scout the land round the town: other towns, old ruins, peaks and lakes, iron in the rock,"
                    + " good ground for a new village — and I come home along my own trail and put it all in the atlas",
                List.of(),
                List.of(need("food for the road", s -> s.get(net.minecraft.core.component.DataComponents.FOOD) != null, 4, "the stores"),
                    need("torches to mark the way", s -> s.is(Items.TORCH), 2, "the stores")),
                "what lies beyond the fields: neighbours for the envoys, ore for the miners, a place for the next village");
            case HUNT -> new Trade("I hunt out past the fields: cows, pigs, sheep, chickens and rabbits gone wild — never the"
                    + " village's own herd, never the young, and never the last pair of a kind, so there's always game next year",
                List.of(),
                List.of(need("arrows", s -> s.is(Items.ARROW), 8, "the smith and the fletcher's feathers"),
                    need("a bow or a sword", s -> s.is(Items.BOW) || s.is(net.minecraft.tags.ItemTags.SWORDS), 1, "the smith")),
                "meat for the larder and the café, leather and wool for the tailor, feathers for arrows");
            // The banker (Bank): it makes nothing; its work is the village's savings and its loans.
            case BANK -> new Trade("I keep the bank: folk put by with me what they don't need this week, I lend it out to"
                    + " households buying their houses, and I keep the ledger on the lectern and the coin behind the bars",
                List.of(),
                List.of(need("a book, an ink sac and a feather for the ledger", s -> s.is(Items.BOOK) || s.is(Items.INK_SAC)
                    || s.is(Items.FEATHER), 1, "the stores")),
                "interest for the savers, houses for the borrowers, and a share of what the bank earns for the treasury");
            // [caves] The cave dweller (CaveDwellers): the town's kit, its torches and its rations; the ore and the old chests' finds home.
            case CAVE -> new Trade("I go down the caves round the town, armed and in the town's armour: I mine every ore my pick"
                    + " will take (diamond with iron, obsidian only with diamond), look in the old chests the world left in its"
                    + " mineshafts, dungeons and temples, fight what comes at me, and light my way home with torches",
                List.of(need("a pickaxe", s -> s.getItem() instanceof net.minecraft.world.item.PickaxeItem, 1, "the smith (the town's, issued free)"),
                    need("a sword", s -> s.is(net.minecraft.tags.ItemTags.SWORDS), 1, "the smith (the town's, issued free)")),
                List.of(need("torches", s -> s.is(Items.TORCH), 16, "the stores, or the stores' coal and sticks"),
                    need("food for the day", s -> s.get(DataComponents.FOOD) != null, 4, "the stores")),
                "ore and the old chests' treasure for the stores, and the caves' report: veins for the miners, spawners and lava to keep clear of");
            // [transport] The ferryman (Ferries): the town's boat between the two landings, a coin a crossing.
            case FERRY -> new Trade("I row the town's boat between the two landings, carrying whoever needs to cross the water: the"
                    + " farmers to their fields, the miners to the mine, a traveller to the neighbours. A coin a crossing, and"
                    + " nobody goes out in a storm",
                List.of(need("a boat", s -> s.is(net.minecraft.tags.ItemTags.BOATS), 1, "the town's, moored at the landing")),
                List.of(),
                "folk across the water, and a coin a crossing into my purse");
            case NONE -> new Trade("I'm between trades just now", List.of(), List.of(), "whatever the village needs a hand with");
        };
    }

    /** What of a trade's this is, in words (a tool, a makings, its kit), or null if the trade has no use for it. */
    @Nullable
    public static String wants(StationTask t, ItemStack s) {
        if (s.isEmpty() || t == StationTask.NONE) return null;
        Trade trade = of(t);
        for (Need n : trade.tools()) if (n.what().test(s)) return n.words();
        for (Need n : trade.inputs()) if (n.what().test(s)) return n.words();
        if (keeps(t, s) > 0) return "my work";
        return null;
    }

    // ------------------------------------------------------------------ talk

    /** "How does your trade work?" — in the folk's own words, with what it has and what it lacks. */
    public static String explain(VillageFolkEntity f) {
        StationTask t = f.stationTask();
        Trade trade = of(t);
        StringBuilder sb = new StringBuilder();
        sb.append(t == StationTask.NONE ? "" : "I'm the village's " + t.title.toLowerCase(Locale.ROOT) + ". ")
            .append(trade.work()).append(". ");
        List<String> lacking = new ArrayList<>();
        for (Need n : trade.tools()) {
            if (n.want() <= 0) continue;
            if (f.countCarried(n.what()) < n.want()) lacking.add(n.words() + " (" + n.from() + " makes them)");
        }
        UUID village = f.ownerId();
        if (village != null && f.level() instanceof ServerLevel level) {
            for (Need n : trade.inputs()) {
                int have = Market.stock(level, village, n.what()) + f.countCarried(n.what());
                if (have < n.want()) lacking.add(n.words() + " (from " + n.from() + ")");
            }
            String building = VillageFolkEntity.buildingFor(t);
            if (building != null && !Villages.hasBuilt(village, building)) {
                lacking.add(Villages.spoken(building) + " to work in");
            }
            Villages.Village v = Villages.get(village);
            if (v != null) {
                if (t == StationTask.BREW && !has(level, v, t, Items.BREWING_STAND, Blocks.BREWING_STAND)) lacking.add(0, "a brewing stand (its blaze rod is from the Nether)");
                if (t == StationTask.ENCHANT && !has(level, v, t, Items.ENCHANTING_TABLE, Blocks.ENCHANTING_TABLE)) lacking.add(0, "an enchanting table");
                if (t == StationTask.BEEKEEP && !has(level, v, t, Items.BEEHIVE, Blocks.BEEHIVE)) lacking.add(0, "a hive");
                if (t == StationTask.RANCH && f.workZone() != null) {
                    boolean pair = false;
                    for (int n : Drover.herd(level, f.workZone().center(), 12).values()) if (n >= 2) pair = true;
                    if (!pair) lacking.add(0, "a pair of animals to breed (I'll fetch wild ones on a lead)");
                }
            }
        }
        sb.append("What I make: ").append(trade.output()).append(". ");
        // The watch's beat (Patrols), and what a maker's years at the trade let it make, and how well (Craftsmanship).
        String own = t == StationTask.GUARD ? Patrols.line(f) : Craftsmanship.line(f);
        if (!own.isEmpty()) sb.append(own).append(' ');
        // A seller's shelves: what sells, what is low, what is marked down, what it is short of (Stockroom).
        String shelves = Stockroom.talk(f);
        if (!shelves.isEmpty()) sb.append(shelves).append(' ');
        if (lacking.isEmpty()) sb.append("I've everything I need, thank you.");
        else sb.append("I'm short of ").append(String.join(", ", lacking.subList(0, Math.min(3, lacking.size())))).append('.');
        return sb.toString();
    }

    /** "What is the village short of?" — the next age's wants, and the trades' empty shelves. */
    public static String shortages(VillageFolkEntity f) {
        UUID village = f.ownerId();
        if (village == null || !(f.level() instanceof ServerLevel level)) return "Short of? A village, mostly.";
        List<String> out = new ArrayList<>();
        for (Villages.Need n : Villages.needs(level, village)) {
            out.add(n.what());
            if (out.size() >= 3) break;
        }
        List<String> trades = new ArrayList<>();
        java.util.Set<StationTask> seen = java.util.EnumSet.noneOf(StationTask.class);
        for (AssistantEntity a : Villages.folkOf(village)) {
            StationTask t = a.stationTask();
            if (!seen.add(t)) continue;
            for (Need n : of(t).inputs()) {
                if (Market.stock(level, village, n.what()) + a.countCarried(n.what()) < n.want()) {
                    trades.add("the " + t.title.toLowerCase(Locale.ROOT) + " needs " + n.words());
                    break;
                }
            }
            if (trades.size() >= 3) break;
        }
        // And the sellers that cannot make what sells for want of something (Stockroom).
        for (String s : Stockroom.shortages(level, village)) if (trades.size() < 4) trades.add(s);
        StringBuilder sb = new StringBuilder();
        if (out.isEmpty() && trades.isEmpty()) return "Nothing much, for once. The stores are full and every trade has what it needs.";
        if (!out.isEmpty()) sb.append("For the village: ").append(String.join(", ", out)).append(". ");
        if (!trades.isEmpty()) sb.append("And ").append(String.join("; ", trades)).append(". ");
        sb.append("Bring any of it to the storehouse stalls and the village will pay for it — or look at the quest board.");
        return sb.toString();
    }

    // ------------------------------------------------------------------ starter kits

    /** The tag on the hive a beekeeper brings: there is a swarm in it, let out when it is set down. */
    public static final String SWARM = "mca_swarm";

    /** When each village was last given each trade's kit (game day), in memory; the ledger keeps it too. */
    private static final Map<UUID, Map<String, Long>> GIVEN = new ConcurrentHashMap<>();

    public static void resetForTests() { GIVEN.clear(); }

    /**
     * What the first of a trade brings with it, because nobody in a young village could come by
     * it any other way. Only the things that are truly hard to get:
     * <ul>
     * <li>the beekeeper a hive with a swarm in it (a hive is made of honeycomb, and honeycomb
     *     only comes out of a hive; wild nests are rare and need shears or silk touch);</li>
     * <li>the brewer a brewing stand, blaze powder to fire it and nether wart with soul sand to
     *     grow more (the blaze rod, the wart and the sand are all from the Nether);</li>
     * <li>the enchanter an enchanting table (diamonds, obsidian and a book) and some lapis;</li>
     * <li>the blacksmith an old anvil (thirty-one iron is more than a young smithy has);</li>
     * <li>the tailor a loom, if the village has no string to make one;</li>
     * <li>the rancher two leads, to bring wild animals home (slime is hard to come by);</li>
     * <li>the first farmer sugar cane cuttings and melon and pumpkin seed, if nobody has any.</li>
     * </ul>
     * Everything else a trade needs, the village makes: see {@link #of}.
     */
    public static List<ItemStack> kitFor(ServerLevel level, Villages.Village v, StationTask t) {
        List<ItemStack> kit = new ArrayList<>();
        UUID id = v.id();
        switch (t) {
            case FARM -> {
                if (!anywhere(level, id, s -> s.is(Items.SUGAR_CANE))) kit.add(new ItemStack(Items.SUGAR_CANE, 3));
                if (!anywhere(level, id, s -> s.is(Items.MELON_SEEDS) || s.is(Items.MELON_SLICE))) kit.add(new ItemStack(Items.MELON_SEEDS, 2));
                if (!anywhere(level, id, s -> s.is(Items.PUMPKIN_SEEDS) || s.is(Items.PUMPKIN))) kit.add(new ItemStack(Items.PUMPKIN_SEEDS, 2));
            }
            case RANCH -> {
                if (!anywhere(level, id, s -> s.is(Items.LEAD))) kit.add(new ItemStack(Items.LEAD, 2));
                // Shears are two iron, and a young village's first iron goes to picks: without them
                // there is no wool, and without wool no beds (the long game: four beds for 48 folk).
                if (!anywhere(level, id, s -> s.is(Items.SHEARS)) && Crafts.stock(level, v, s -> s.is(Items.IRON_INGOT)) < 2) {
                    kit.add(new ItemStack(Items.SHEARS));
                }
            }
            case BEEKEEP -> {
                if (!has(level, v, t, Items.BEEHIVE, Blocks.BEEHIVE)) kit.add(swarm());
            }
            case BREW -> {
                if (!has(level, v, t, Items.BREWING_STAND, Blocks.BREWING_STAND)) kit.add(new ItemStack(Items.BREWING_STAND));
                kit.add(new ItemStack(Items.BLAZE_POWDER, 8));
                kit.add(new ItemStack(Items.NETHER_WART, 4));
                kit.add(new ItemStack(Items.SOUL_SAND, 4));
            }
            case ENCHANT -> {
                if (!has(level, v, t, Items.ENCHANTING_TABLE, Blocks.ENCHANTING_TABLE)) kit.add(new ItemStack(Items.ENCHANTING_TABLE));
                kit.add(new ItemStack(Items.LAPIS_LAZULI, 6));
            }
            case SMITH -> {
                if (!has(level, v, t, Items.ANVIL, Blocks.ANVIL) && !has(level, v, t, Items.CHIPPED_ANVIL, Blocks.CHIPPED_ANVIL)
                        && !has(level, v, t, Items.DAMAGED_ANVIL, Blocks.DAMAGED_ANVIL)
                        && Crafts.stock(level, v, s -> s.is(Items.IRON_INGOT)) < 31) {
                    kit.add(new ItemStack(Items.CHIPPED_ANVIL));
                }
            }
            case TAILOR -> {
                if (!has(level, v, t, Items.LOOM, Blocks.LOOM) && Crafts.stock(level, v, s -> s.is(Items.STRING)) < 2) {
                    kit.add(new ItemStack(Items.LOOM));
                }
            }
            // The first fisher's rod: a rod is string, and a young village has none.
            case FISH -> {
                if (!anywhere(level, id, s -> s.is(Items.FISHING_ROD)) && Crafts.stock(level, v, s -> s.is(Items.STRING)) < 2) {
                    kit.add(new ItemStack(Items.FISHING_ROD));
                }
            }
            // The first hunter's bow and a quiver of arrows: string is spiders' or wool's, and a
            // young village has neither to spare.
            case HUNT -> {
                if (!anywhere(level, id, s -> s.is(Items.BOW)) && Crafts.stock(level, v, s -> s.is(Items.STRING)) < 3) kit.add(new ItemStack(Items.BOW));
                if (Crafts.stock(level, v, s -> s.is(Items.ARROW)) < 16) kit.add(new ItemStack(Items.ARROW, 16));
                // Two leads: game the pens are short of comes home alive, for the rancher.
                if (Crafts.stock(level, v, s -> s.is(Items.LEAD)) < 2) kit.add(new ItemStack(Items.LEAD, 2));
            }
            default -> { }
        }
        return kit;
    }

    /**
     * A farmer's buckets: ten, filled at the well, so it can set water through its field as it
     * grows (FarmGoal) — a crop on wet farmland grows three times as fast as on dry. Each farmer
     * has its own, bought for the village from a pedlar (a coin a bucket); short of the coin, it
     * is put by for. Returns whether it got them.
     */
    public static boolean buckets(VillageFolkEntity f) {
        UUID village = f.ownerId();
        if (village == null || f.stationTask() != StationTask.FARM || !(f.level() instanceof ServerLevel level)) return false;
        String key = "buckets." + f.getUUID();
        if (Ledger.note(village, key) != null) return false;
        if (f.countCarried(s -> s.is(Items.BUCKET) || s.is(Items.WATER_BUCKET)) >= 10) return false;
        int price = BUCKETS;
        if (Ledger.coins(village) < price) {
            Market.saveFor(village, key, price, level.getGameTime());
            return false;
        }
        Market.bought(village, key);
        Ledger.takeCoins(village, price);
        Economy.spent(village, price);
        Ledger.note(village, key, Long.toString(level.getGameTime() / 24000L));
        for (int i = 0; i < BUCKETS; i++) {
            ItemStack left = f.insertItem(new ItemStack(Items.WATER_BUCKET));
            if (!left.isEmpty()) { Villages.Village v = Villages.get(village); if (v != null) Crafts.store(level, v, left); }
        }
        f.brain("ten buckets of water for the field");
        FolkTalk.speak(f, "Ten buckets, filled at the well. A field wants water through it.");
        Villages.tell(village, level.getGameTime() / 24000L, "a pedlar sold " + f.displayNameCap() + " ten buckets for the fields, for "
            + price + " coin");
        return true;
    }

    /** How many buckets a farmer gets. */
    public static final int BUCKETS = 10;

    /** What a pedlar asks for a piece of a kit, in the village's coin. */
    static int priceOf(ItemStack s) {
        int each;
        if (s.is(Items.ENCHANTING_TABLE)) each = 36;
        else if (s.is(Items.BREWING_STAND)) each = 18;
        else if (s.is(Items.CHIPPED_ANVIL)) each = 20;
        else if (isSwarm(s)) each = 10;
        else if (s.is(Items.LOOM)) each = 3;
        else if (s.is(Items.SHEARS)) each = 3;
        else if (s.is(Items.BOW)) each = 4;
        else if (s.is(Items.FISHING_ROD)) each = 2;
        else if (s.is(Items.ARROW)) return Math.max(1, s.getCount() / 4);
        else if (s.is(Items.LEAD)) return 2 * s.getCount();
        else if (s.is(Items.BLAZE_POWDER)) return s.getCount();
        else if (s.is(Items.LAPIS_LAZULI) || s.is(Items.NETHER_WART) || s.is(Items.SOUL_SAND)) return (s.getCount() + 1) / 2;
        else return Math.max(1, s.getCount() / 3);
        return each * s.getCount();
    }

    /** The hive the beekeeper brings, with its swarm. */
    public static ItemStack swarm() {
        ItemStack hive = new ItemStack(Items.BEEHIVE);
        CompoundTag tag = new CompoundTag();
        tag.putInt(SWARM, 2);
        hive.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        return hive;
    }

    public static boolean isSwarm(ItemStack s) {
        return s.is(Items.BEEHIVE) && s.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag().contains(SWARM);
    }

    /**
     * Give this folk its trade's kit, if its village has not had it yet (or has lost what it
     * was given: nobody carries it, the stores have none and it stands nowhere, three days on).
     * Returns whether anything was given.
     */
    public static boolean kit(VillageFolkEntity f) {
        UUID village = f.ownerId();
        StationTask t = f.stationTask();
        if (village == null || t == StationTask.NONE || !(f.level() instanceof ServerLevel level)) return false;
        Villages.Village v = Villages.get(village);
        if (v == null) return false;
        // Game time, not the clock: /time set turns the clock back and would stall a lost kit.
        long today = level.getGameTime() / 24000L;
        Long given = given(village, t);
        if (given != null && (Math.abs(today - given) < 3 || !lost(level, v, t))) return false;
        List<ItemStack> kit = kitFor(level, v, t);
        // Bought, not conjured: a pedlar sells the village what it could not make, for coin out of
        // its treasury (which only fills by selling what it makes). No coin, no kit — yet.
        int price = 0;
        for (ItemStack s : kit) price += priceOf(s);
        if (!kit.isEmpty() && Ledger.coins(village) < price) {
            Market.saveFor(village, "kit." + t.name(), price, level.getGameTime());   // the wages leave it put by
            return false;
        }
        Market.bought(village, "kit." + t.name());
        GIVEN.computeIfAbsent(village, k -> new ConcurrentHashMap<>()).put(t.name(), today);
        Ledger.note(village, "kit." + t.name(), Long.toString(today));
        if (kit.isEmpty()) return false;
        Ledger.takeCoins(village, price);
        Economy.spent(village, price);
        List<String> words = new ArrayList<>();
        for (ItemStack s : kit) {
            words.add(isSwarm(s) ? "a hive with a swarm in it" : Crafts.named(s));
            ItemStack left = f.insertGiven(s.copy());
            if (!left.isEmpty()) Crafts.store(level, v, left);
        }
        String what = String.join(", ", words);
        f.brain("brought " + what);
        Villages.tell(village, today, "a pedlar sold the village " + what + " for the " + t.title.toLowerCase(Locale.ROOT)
            + "'s work, for " + price + " coin: things it could not have made");
        FolkTalk.speak(f, broughtLine(t));
        return true;
    }

    @Nullable
    private static Long given(UUID village, StationTask t) {
        Long day = GIVEN.getOrDefault(village, Map.of()).get(t.name());
        if (day != null) return day;
        String note = Ledger.note(village, "kit." + t.name());
        if (note == null || note.isEmpty()) return null;
        try {
            day = Long.parseLong(note);
        } catch (NumberFormatException e) {
            return null;
        }
        GIVEN.computeIfAbsent(village, k -> new ConcurrentHashMap<>()).put(t.name(), day);
        return day;
    }

    /** Has the village lost the one thing it could not make again (the hive, the stand, the table...)?
     *  Only if every place it could stand is in sight: ground out of view is not ground without it. */
    private static boolean lost(ServerLevel level, Villages.Village v, StationTask t) {
        String building = VillageFolkEntity.buildingFor(t);
        BlockPos at = building == null ? null : Villages.builtAt(v.id(), building);
        if (at != null && !Land.areaLoaded(level, at, 9)) return false;
        for (AssistantEntity a : Villages.folkOf(v.id())) {
            if (a.stationTask() != t) continue;
            BlockPos c = a.workZone() != null ? a.workZone().center() : a.blockPosition();
            if (!Land.areaLoaded(level, c, 9)) return false;
        }
        return switch (t) {
            case BEEKEEP -> !has(level, v, t, Items.BEEHIVE, Blocks.BEEHIVE);
            case BREW -> !has(level, v, t, Items.BREWING_STAND, Blocks.BREWING_STAND);
            case ENCHANT -> !has(level, v, t, Items.ENCHANTING_TABLE, Blocks.ENCHANTING_TABLE);
            default -> false;
        };
    }

    private static String broughtLine(StationTask t) {
        return switch (t) {
            case BEEKEEP -> "I've brought a hive with me, swarm and all. Mind the bees!";
            case BREW -> "A brewing stand, a pouch of blaze powder and a few warts to plant. That's a brewer's whole fortune.";
            case ENCHANT -> "I brought my old enchanting table. It took three of us to carry it.";
            case SMITH -> "Grandfather's anvil. Chipped, but it rings true.";
            case TAILOR -> "My loom came with me. I couldn't work without it.";
            case RANCH -> "Two good leads, and shears for the wool. Now to find us some animals.";
            case FARM -> "A few cane cuttings and melon and pumpkin seed, from the old country.";
            case HUNT -> "A bow, a quiver of arrows and a couple of leads, from a pedlar on the road. Now for the game.";
            case FISH -> "A good rod, bought off a pedlar. Now, where's the water?";
            default -> "I've brought what I need.";
        };
    }

    /** Anybody in the village carrying it, or the stores holding it? */
    static boolean anywhere(ServerLevel level, UUID village, Predicate<ItemStack> what) {
        if (Market.stock(level, village, what) > 0) return true;
        for (AssistantEntity a : Villages.folkOf(village)) if (a.countCarried(what) > 0) return true;
        return false;
    }

    /** Does the village have one of these: carried, in the stores, or standing where a hand of
     *  this trade works (by its post, or in its building)? */
    static boolean has(ServerLevel level, Villages.Village v, StationTask t, Item item, Block block) {
        if (anywhere(level, v.id(), s -> s.is(item))) return true;
        String building = VillageFolkEntity.buildingFor(t);
        if (building != null) {
            BlockPos at = Villages.builtAt(v.id(), building);
            if (at != null && find(level, at, 8, block) != null) return true;
        }
        for (AssistantEntity a : Villages.folkOf(v.id())) {
            if (a.stationTask() != t) continue;
            BlockPos c = a.workZone() != null ? a.workZone().center() : a.blockPosition();
            if (find(level, c, 8, block) != null) return true;
        }
        return false;
    }

    /** The nearest block of a kind within r of here (and a few up and down), on loaded ground only. */
    @Nullable
    static BlockPos find(ServerLevel level, BlockPos c, int r, Block block) {
        if (!Land.areaLoaded(level, c, r)) return null;
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (BlockPos p : BlockPos.betweenClosed(c.offset(-r, -3, -r), c.offset(r, 4, r))) {
            net.minecraft.world.level.block.state.BlockState st = level.getBlockState(p);
            if (!st.is(block) && !(block == Blocks.ANVIL && st.is(net.minecraft.tags.BlockTags.ANVIL))) continue;
            double d = p.distSqr(c);
            if (d < bestD) { bestD = d; best = p.immutable(); }
        }
        return best;
    }

    /** What a hand of this trade keeps in its pack and never banks: its kit, and its workstation
     *  until it is set down. */
    public static int keeps(StationTask t, ItemStack s) {
        return switch (t) {
            case BEEKEEP -> s.is(Items.BEEHIVE) ? 4 : (s.is(Items.SHEARS) ? 1 : 0);
            case BREW -> s.is(Items.BREWING_STAND) || s.is(Items.BLAZE_POWDER) || s.is(Items.NETHER_WART)
                || s.is(Items.SOUL_SAND) ? 64 : 0;
            case ENCHANT -> s.is(Items.ENCHANTING_TABLE) ? 1 : (s.is(Items.LAPIS_LAZULI) ? 64 : 0);
            case SMITH -> s.is(ItemTags.ANVIL) ? 1 : 0;
            case TAILOR -> s.is(Items.LOOM) ? 1 : 0;
            case RANCH -> s.is(Items.LEAD) ? 4 : ((s.is(Items.BUCKET) || s.is(Items.SHEARS)) ? 1 : 0);
            case FARM -> s.is(Items.SUGAR_CANE) ? 6 : ((s.is(Items.MELON_SEEDS) || s.is(Items.PUMPKIN_SEEDS)) ? 4 : 0);
            case GUARD -> Links.healing(s) ? 1 : 0;
            default -> 0;
        };
    }

    // ------------------------------------------------------------------ workstations

    /**
     * Where a craft's workstation stands: in its building, on the very spot the drawing has it,
     * or by its post while there is no building yet. If it stands nowhere, the hand sets down
     * the one it carries (or one from the stores). Null if there is none to be had.
     */
    @Nullable
    public static BlockPos workstation(VillageFolkEntity f, ServerLevel level, Villages.Village v,
                                       Block block, Predicate<ItemStack> item, BuildGoal.Part part) {
        String building = f.stationTask() == StationTask.SHOP ? Store.buildingForShop(v.id())   // [econ-store]
            : VillageFolkEntity.buildingFor(f.stationTask());
        Ledger.Building b = null;
        if (building != null) {
            for (Ledger.Building k : Ledger.buildings(v.id())) if (k.structure().equals(building)) { b = k; break; }
        }
        BlockPos around = b != null ? b.anchor() : (f.workZone() != null ? f.workZone().center() : f.blockPosition());
        if (!Land.areaLoaded(level, around, 10)) return null;
        BlockPos found = find(level, around, 8, block);
        if (found != null) return found;
        // A craft with a building of its own keeps its workstation in its pack till the building
        // stands: set down by its post in the square, it was left there when the building went up,
        // the craft stood idle, and the village was given another.
        if (building != null && b == null) return null;
        ItemStack carried = ItemStack.EMPTY;
        for (ItemStack s : f.getInventoryItems()) if (!s.isEmpty() && item.test(s)) { carried = s; break; }
        Block placing = carried.isEmpty() ? block : Block.byItem(carried.getItem());
        if (placing == Blocks.AIR) placing = block;
        BlockPos at = null;
        if (b != null) {
            for (BuildGoal.Placement p : BuildGoal.plan(b.structure(), b.anchor(), b.facing(), 13)) {
                if (p.part() == part && level.getBlockState(p.pos()).isAir()) { at = p.pos(); break; }
            }
        }
        if (at == null) at = floorSpot(level, around, 3);
        if (at == null) return null;
        if (!carried.isEmpty()) carried.shrink(1);
        else if (!Crafts.take(level, v, item, 1)) return null;
        level.setBlock(at, placing.defaultBlockState(), 3);
        f.swing(InteractionHand.MAIN_HAND);
        level.playSound(null, at, SoundEvents.WOOD_PLACE, SoundSource.BLOCKS, 1.0F, 1.0F);
        f.brain("set the " + placing.getName().getString().toLowerCase(Locale.ROOT) + " down");
        return at;
    }

    /** A free spot on the floor near here: air with room above and something solid under it. */
    @Nullable
    static BlockPos floorSpot(ServerLevel level, BlockPos c, int r) {
        for (int dy : new int[]{ 0, -1, 1 }) {
            for (int d = 1; d <= r; d++) {
                for (int dx = -d; dx <= d; dx++) {
                    for (int dz = -d; dz <= d; dz++) {
                        if (Math.max(Math.abs(dx), Math.abs(dz)) != d) continue;
                        BlockPos p = c.offset(dx, dy, dz);
                        if (level.getBlockState(p).isAir() && level.getBlockState(p.above()).isAir()
                                && level.getBlockState(p.below()).isFaceSturdy(level, p.below(), net.minecraft.core.Direction.UP)) {
                            return p;
                        }
                    }
                }
            }
        }
        return null;
    }
}
