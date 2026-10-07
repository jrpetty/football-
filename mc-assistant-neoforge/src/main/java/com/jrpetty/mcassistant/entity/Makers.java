package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.McAssistantMod;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * [itemaudit] Who makes each of the mod's own things, and when the town wants one: the one place that says it for
 * every item, so the books, the docs and the tests (ItemAuditGameTests) can ask.
 *
 * <p>Nothing is from nothing (the house rule), so every thing the mod adds is made by somebody out of the stores'
 * real makings, at the moment the town wants one: the tailor's beds and garments, the cook's treats, the smith's
 * gold, the shop's workshop for the rest. Each line here names the trade and the moment, and the class that does
 * it; the making itself is that class's. The player's own tools (the spawners, the markers, the charter, the job
 * board) no folk wants, so the shop's workshop makes them on a player's order (Workshop.order: "/village workshop
 * order"). One thing only is made by nobody: a companion's memory core, which forms when the companion falls.
 *
 * <p>A new thing is added here in the same change that hooks its maker, one line ({@link #declare}); the game test
 * ia01 goes through every item the mod registers and fails for one that has no maker here, so a thing nobody makes
 * cannot slip in.
 */
public final class Makers {

    private Makers() {}

    /** Who makes it ("the tailor"), when the town wants one, and where in the code that is. */
    public record Maker(String who, String when, String where) {}

    private static final Map<String, List<Maker>> BY_NAME = new LinkedHashMap<>();
    private static final Map<String, String> UNMADE = new LinkedHashMap<>();

    /** One of the mod's things ({@code name}: its id under mc_assistant) made by this trade at this moment. */
    public static synchronized void declare(String name, String who, String when, String where) {
        BY_NAME.computeIfAbsent(name, k -> new ArrayList<>()).add(new Maker(who, when, where));
    }

    /** One of the mod's things that nobody makes, and why (a short list: see the class comment). */
    public static synchronized void unmade(String name, String why) {
        UNMADE.put(name, why);
    }

    /** Who makes this thing, or an empty list if nobody is said to. */
    public static synchronized List<Maker> of(Item item) {
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
        if (!McAssistantMod.MODID.equals(id.getNamespace())) return List.of();
        return List.copyOf(BY_NAME.getOrDefault(id.getPath(), List.of()));
    }

    /** Why nobody makes this thing, or null if somebody does (or nothing is said). */
    @Nullable
    public static synchronized String unmade(Item item) {
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
        return McAssistantMod.MODID.equals(id.getNamespace()) ? UNMADE.get(id.getPath()) : null;
    }

    /** Everything said, by name, in the order it was said (the books and the docs). */
    public static synchronized Map<String, List<Maker>> all() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(BY_NAME));
    }

    /** "the tailor, when ...; the cook, when ...": a thing's makers in a line. */
    public static String words(Item item) {
        List<String> out = new ArrayList<>();
        for (Maker m : of(item)) out.add(m.who() + ", " + m.when());
        String no = unmade(item);
        if (out.isEmpty() && no != null) return "nobody: " + no;
        return out.isEmpty() ? "nobody yet" : String.join("; ", out);
    }

    // ------------------------------------------------------------------ the mod's things, and their makers

    private static final String PLAYERS = "only a player wants one: made on a player's order";

    static {
        // [kitchen] The kitchen and the cellar (entity/Kitchen, item/KitchenItems): each by its own trade, else the shop's workshop.
        declare("packed_lunch", "the cook", "for hands working far off at midday, the cave team, the fleet, caravans and envoys", "Kitchen.pack");
        declare("cheese_wheel", "the cook", "while the stores keep fewer than two wheels (three in autumn), from the rancher's milk", "Kitchen.cook");
        declare("cheese_slice", "the cook", "a wheel cut for meals when food runs short, or on the café's and tavern's tables", "Kitchen.cut");
        declare("honey_cake", "the cook", "a day ahead of a birthday, a wedding, Founding Day or a festival", "Kitchen.cook");
        declare("mead", "the brewer (the café with no brewer)", "while the tavern's bar runs short", "Kitchen.brew");
        declare("cider", "the brewer (the café with no brewer)", "in autumn and for the harvest festival", "Kitchen.brew");
        declare("fish_pie", "the cook", "after a glut at the fish market", "Kitchen.cook");
        declare("herbal_tea", "the healer (the café with no healer)", "for a folk with a cold, and the café on cold days", "Kitchen.brew");
        declare("bandage", "the healer (the tailor with no healer)", "to keep the watch and the cave team at three each, and for the healer's round", "Kitchen.bind");
        // [culture2] The towns' own dishes (Cuisine.cook, from Crafts.now: the cook's first care; a hand at the town's works
        // on a feast day in a town with no cook).
        for (String d : List.of("fish_stew", "game_pie", "berry_tart", "harvest_loaf", "hotpot", "spiced_mutton", "cocoa_cake", "fen_broth")) {
            declare(d, "the cook (a hand at the town's works on a feast day, with no cook)",
                "while the stores keep fewer of the town's own dish than four and one for every four folk, and on feast days",
                "Cuisine.cook / Cuisine.tick");
        }
        // [pets] The pets' things (Pets.craft, from Crafts.now; the shop's book through Workshop.demand).
        declare("pet_bowl", "the shop's workshop", "when a household with a pet has no bowl", "Pets.craft / Workshop.demand");
        declare("dog_bed", "the tailor", "when a household's dog has no bed of its own", "Pets.craft");
        declare("cat_bed", "the tailor", "when a household's cat has no basket", "Pets.craft");
        declare("collar", "the tailor", "when a household's pet has no collar", "Pets.craft");
        declare("pet_treat", "the cook", "while a household with children keeps a pet (two kept)", "Pets.craft");
        // [fashion] The tailor's garments, made on its book (Tailoring.work, from Crafts.tailor).
        for (String g : List.of("long_coat", "leather_jacket", "wool_shawl", "waistcoat", "felt_hat", "flat_cap", "top_hat", "wool_scarf",
                "brooch")) {
            declare(g, "the tailor", "when a folk wants one for the season's look and the town's stock has none", "Tailoring.work");
        }
        declare("rosette", "the tailor", "the day before a fashion show, the show's prize", "Tailoring.work / FashionShow");
        // [arms] The festival tabards and the loom's patterns (Arms).
        declare("tabard", "the tailor", "while the town keeps fewer festival tabards than its grown folk off the watch (six at most)",
            "Arms.makeTabard");
        for (String c : List.of("fish", "pick", "sheaf")) {
            declare(c + "_banner_pattern", "the tailor", "the first time the town's arms carry the " + c + " (it is kept in the stores)",
                "Arms.patternsToHand");
        }
        // [crime] The stocks, and the forger's coin.
        declare("stocks", "the town's hands", "the first time a sentence calls for them (a player's stocks on the square will do)",
            "Trial.putUp");
        declare("forged_coin", "a forger", "never wanted: cast of a copper ingot by a folk tempted to pass it at the stores", "Mischief");
        // [civic] The ribbon for a great work's opening.
        declare("opening_ribbon", "the tailor", "while a great work is under way and the stores have not ribbon enough for its end",
            "BigWorks.tailorRibbon / stringRibbon");
        // [player-civic] The masters' own goods (TradeGoods.craft, from Crafts.now); [itemaudit] till the town has a
        // master of the trade, its best hand at it, one turn in three (TradeGoods.hand).
        declare("reinforced_pickaxe", "a master smith, or the best smith till there is one",
            "while the town has miners or cave dwellers and fewer than two in the stores", "TradeGoods.craft");
        declare("brewers_stout", "a master brewer, or the best brewer till there is one",
            "while the town has a tavern, is fed, and has fewer than six in the stores", "TradeGoods.craft");
        declare("farmhouse_pie", "a master cook, or the best cook till there is one", "while the stores hold fewer than eight",
            "TradeGoods.craft");
        declare("apprentice_journal", "the tailor", "for every young apprentice without one, and one over for a player taken on",
            "TradeGoods.craft");
        // [quests] The quests' things, made at the bench by whoever the quest has make them (QuestItems.make).
        declare("quest_journal", "the shop's workshop", "once quests are going in the town (two kept), and the first quest's giver",
            "QuestRun / Workshop.demand");
        for (String q : List.of("sealed_letter", "parcel", "peace_terms", "spy_report", "smugglers_ledger", "miners_journal", "wooden_toy",
                "childs_drawing")) {
            declare(q, "the quest's own folk, at the bench", "when a quest wants one", "QuestItems.make");
        }
        for (String q : List.of("heirloom_ring", "heirloom_locket", "town_medal", "town_key")) {
            declare(q, "the smith (else the shop)", "when a story or the town's honours want one", "QuestItems.make / QuestRewards");
        }
        // [fleet] [transport]
        declare("fishing_net", "the tailor", "while the fishing fleet has fewer nets than boats", "Fleet.makeNet");
        declare("ferry_bell", "the ferry's builders", "for each landing as a ferry is put in", "Ferries / Railways.takeOrMake");
        // The village's own pieces.
        declare("storehouse_unit", "the builders", "as the storehouse goes up: twenty-seven to a store", "VillageFolkEntity.madeFromStores");
        declare("village_board", "the town's hands", "whenever the town's board has been taken down (the founders bring the first)",
            "VillageBoards.keep");
        declare("village_coin", "the mint", "from the Iron Age, while the treasury runs low: nine coins to a bar of the stores' gold",
            "Market.mint");
        // The player's own tools: the shop's workshop makes them to a player's order, out of the stores.
        for (String t : List.of("job_board", "village_charter", "assistant_spawner", "village_folk_spawner", "place_marker", "zone_marker")) {
            declare(t, "the shop's workshop", PLAYERS, "Workshop.order");
        }
        unmade("memory_core", "it forms only when a companion falls, holding all it was; a made one would hold nobody");
    }
}
