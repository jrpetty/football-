package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
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
            case FARM -> new Trade("I till a field by the water, sow it, and bring the harvest in",
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
            case RANCH -> new Trade("I keep the animals in the pen: feed them, breed them, shear the sheep and milk the cows",
                List.of(need("shears", s -> s.is(Items.SHEARS), 1, "the smith"), need("a bucket", s -> s.is(Items.BUCKET), 1, "the smith")),
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
                    need("raw iron", s -> s.is(Items.RAW_IRON), 3, "the miners"), need("sand", s -> s.is(Items.SAND), 4, "the diggers")),
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
                    need("feathers or flint", s -> s.is(Items.FEATHER) || s.is(Items.FLINT), 4, "the rancher and the diggers")),
                "picks, axes, hoes, shears and buckets for the trades, blades, bows, arrows and armour for the watch");
            case TAILOR -> new Trade("I weave at the loom in the workshop",
                List.of(need("a loom", s -> s.is(Items.LOOM), 0, "string and planks")),
                List.of(need("wool", s -> s.is(ItemTags.WOOL), 6, "the rancher")),
                "beds for the houses, string for the smith and the fishers, rugs and banners");
            case BEEKEEP -> new Trade("I keep hives on a meadow by the flower beds, and take the honey when they're full",
                List.of(need("shears", s -> s.is(Items.SHEARS), 1, "the smith")),
                List.of(need("glass bottles", s -> s.is(Items.GLASS_BOTTLE) || s.is(Items.GLASS), 3, "the smelter's glass")),
                "honey for the café and the shop, and honeycomb for new hives and candles");
            case BREW -> new Trade("I brew at the brewing stand: nether wart from my own patch, then whatever makes the potion",
                List.of(),
                List.of(need("blaze powder for the stand", s -> s.is(Items.BLAZE_POWDER), 1, "my own stock"),
                    need("nether wart", s -> s.is(Items.NETHER_WART), 3, "my wart patch"),
                    need("glass bottles", s -> s.is(Items.GLASS_BOTTLE) || s.is(Items.GLASS), 3, "the smelter's glass"),
                    need("melon and gold, or sugar, or a pufferfish", s -> s.is(Items.MELON_SLICE) || s.is(Items.SUGAR)
                        || s.is(Items.SUGAR_CANE) || s.is(Items.PUFFERFISH) || s.is(Items.GOLDEN_CARROT), 1, "the farmers and the fishers")),
                "healing for the watch, and swiftness, night vision and water breathing for the shop");
            case ENCHANT -> new Trade("I bind books and enchant the village's best tools at the library's table",
                List.of(),
                List.of(need("lapis", s -> s.is(Items.LAPIS_LAZULI), 3, "the miners"),
                    need("sugar cane or paper", s -> s.is(Items.SUGAR_CANE) || s.is(Items.PAPER), 3, "the farmers"),
                    need("leather", s -> s.is(Items.LEATHER), 1, "the rancher and the fishers")),
                "picks that dig faster, blades that cut deeper, armour that holds");
            case COOK -> new Trade("I cook at the café: cider, honey tea, pies and bread for the counter",
                List.of(),
                List.of(need("apples", s -> s.is(Items.APPLE), 2, "the woodcutters"),
                    need("wheat or bread", s -> s.is(Items.WHEAT) || s.is(Items.BREAD), 3, "the farmers"),
                    need("sugar, honey or eggs", s -> s.is(Items.SUGAR) || s.is(Items.SUGAR_CANE) || s.is(Items.HONEY_BOTTLE)
                        || s.is(Items.EGG), 1, "the farmers, the beekeeper and the rancher")),
                "the café's counter, and the folk's breaks");
            case SHOP -> new Trade("I keep the shop: the crafts' best work on the counter for anybody with coin",
                List.of(), List.of(), "coin for the treasury, and the crafts' work into players' hands");
            case NONE -> new Trade("I'm between trades just now", List.of(), List.of(), "whatever the village needs a hand with");
        };
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
        }
        sb.append("What I make: ").append(trade.output()).append(". ");
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
        StringBuilder sb = new StringBuilder();
        if (out.isEmpty() && trades.isEmpty()) return "Nothing much, for once. The stores are full and every trade has what it needs.";
        if (!out.isEmpty()) sb.append("For the village: ").append(String.join(", ", out)).append(". ");
        if (!trades.isEmpty()) sb.append("And ").append(String.join("; ", trades)).append(". ");
        sb.append("Bring any of it to the storehouse stalls and the village will pay for it — or look at the quest board.");
        return sb.toString();
    }
}
