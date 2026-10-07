package com.jrpetty.mcassistant.gametest;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.Showcase;
import com.jrpetty.mcassistant.block.StocksBlock;
import com.jrpetty.mcassistant.block.VillageBoardBlock;
import com.jrpetty.mcassistant.block.VillageFolkSpawnerBlock;
import com.jrpetty.mcassistant.entity.AssistantEntity;
import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.entity.Arms;
import com.jrpetty.mcassistant.entity.Bench;
import com.jrpetty.mcassistant.entity.BigWorks;
import com.jrpetty.mcassistant.entity.Budget;
import com.jrpetty.mcassistant.entity.Crafts;
import com.jrpetty.mcassistant.entity.Craftsmanship;
import com.jrpetty.mcassistant.entity.Crime;
import com.jrpetty.mcassistant.entity.Fashion;
import com.jrpetty.mcassistant.entity.Fleet;
import com.jrpetty.mcassistant.entity.Heraldry;
import com.jrpetty.mcassistant.entity.Homeland;
import com.jrpetty.mcassistant.entity.Leader;
import com.jrpetty.mcassistant.entity.Makers;
import com.jrpetty.mcassistant.entity.Market;
import com.jrpetty.mcassistant.entity.Meals;
import com.jrpetty.mcassistant.entity.PlayerCivic;
import com.jrpetty.mcassistant.entity.Prices;
import com.jrpetty.mcassistant.entity.RecipeBook;
import com.jrpetty.mcassistant.entity.Tailoring;
import com.jrpetty.mcassistant.entity.Tiers;
import com.jrpetty.mcassistant.entity.TradeGoods;
import com.jrpetty.mcassistant.entity.VillageBoards;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import com.jrpetty.mcassistant.entity.Villages;
import com.jrpetty.mcassistant.entity.Waterfront;
import com.jrpetty.mcassistant.entity.Workshop;
import com.jrpetty.mcassistant.entity.ZoneChests;
import com.jrpetty.mcassistant.entity.goal.BuildGoal;
import com.jrpetty.mcassistant.item.CivicItems;
import com.jrpetty.mcassistant.item.Garment;
import com.jrpetty.mcassistant.village.Chronicle;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BannerPatternLayers;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * [itemaudit] Every thing the mod has, held to the bar the new things are built to: a real recipe of what the town
 * gathers, an age, a worth, a maker that really makes it out of the stores when the town wants one, and a real use.
 *
 * <ul>
 * <li><b>ia01</b>: every item registered under mc_assistant (the thirty new ones too, once they land): a recipe (or a
 *     documented reason it has none), its age (the ages the audit settled checked by name), a worth on the price list,
 *     a maker named in Makers, and the stores' makings run to it at the bench; and the pets' things on the market's
 *     board.</li>
 * <li><b>ia02</b>: the coin, minted honestly: a gold ingot fired makes nine (the mint's own rate), a coin is worth a
 *     coin, the Iron Age town's mint turns the stores' gold into its treasury's coin; and the memory core, made by
 *     nobody and sold to nobody.</li>
 * <li><b>ia03</b>: the fishing net: the tailor knots one of five of the stores' string for each of the fleet's boats,
 *     a net's haul is two to four fish to a line's one or two, and a player's cast over open water brings fish up and
 *     wears the net.</li>
 * <li><b>ia04</b>: the loom's patterns: a river town granted a fish for its arms has the tailor make the fish pattern
 *     of the stores' paper and cod, keeps it, and weaves the fish on its festival tabard.</li>
 * <li><b>ia05</b>: the forged coin and the stocks: a forger casts three of the stores' copper ingot and passes one at
 *     the stores; handed in, they are melted back into the copper; and stocks a player puts up on the square are the
 *     town's for its sentences.</li>
 * <li><b>ia06</b>: the masters' goods: the master brewer's stout, drunk at the tavern for the Haste in it; the master
 *     cook's farmhouse pies, eaten at a meal before plainer food; the tailor's apprentice's journal, taken by the young
 *     apprentice and written up day by day.</li>
 * <li><b>ia07</b>: the village board taken down: the town makes another of its stores' timber and a book, and puts it
 *     up on the square.</li>
 * <li><b>ia08</b>: the opening ribbon: a player's shears cut its own ribbon, and the folk about cheer.</li>
 * <li><b>ia09</b>: every garment made on the tailor's book out of the stores, by its recipe, dyed; each put on.</li>
 * <li><b>ia10</b>: the player's own tools made on a player's order at the shop's workshop, out of the stores.</li>
 * <li><b>ia11</b>: no master yet: the town's best cook (not its greenest) bakes the pies one turn in three until a
 *     master comes up, then the master at every turn; the best smith rivets the miners' picks, an apprentice's work
 *     with its name on it.</li>
 * </ul>
 *
 * <p>Each on its own ground, x 1,280,000 to 1,299,000 on z 66,000, a batch of its own, the logic called directly.
 */
@GameTestHolder("mc_assistant")
@PrefixGameTestTemplate(false)
public class ItemAuditGameTests {

    private static final String EMPTY = "empty";
    private static final int Z = 66000;
    private static final long DAY = 24000L * 3;

    /** The ages the audit settled for the mod's own things (by the rule in Tiers, off each recipe). */
    private static final Map<String, Villages.Age> AGES = ages();

    private static Map<String, Villages.Age> ages() {
        Map<String, Villages.Age> m = new LinkedHashMap<>();
        Villages.Age W = Villages.Age.WOOD, S = Villages.Age.STONE, I = Villages.Age.IRON, D = Villages.Age.DIAMOND;
        for (String n : List.of("pet_bowl", "dog_bed", "cat_bed", "pet_treat", "tabard", "fish_banner_pattern", "pick_banner_pattern",
                "sheaf_banner_pattern", "stocks", "opening_ribbon", "farmhouse_pie", "sealed_letter", "parcel", "peace_terms", "spy_report",
                "smugglers_ledger", "wooden_toy", "childs_drawing", "fishing_net", "storehouse_unit", "place_marker", "memory_core")) m.put(n, W);
        for (String n : List.of("collar", "forged_coin", "brewers_stout", "apprentice_journal", "quest_journal", "miners_journal",
                "ferry_bell", "village_board", "job_board")) m.put(n, S);
        for (String n : List.of("reinforced_pickaxe", "heirloom_ring", "heirloom_locket", "town_medal", "town_key", "village_coin",
                "village_charter", "village_folk_spawner", "zone_marker")) m.put(n, I);
        m.put("assistant_spawner", D);
        for (Garment g : Garment.values()) m.put(g.id, g.age);
        return m;
    }

    // ------------------------------------------------------------------ the ground and the town

    private record Town(UUID village, Villages.Village v, BlockPos heart, List<Container> stores, List<VillageFolkEntity> folk) {}

    /** Flat grass about the heart, clear air above it: a board, a stall or a pond can go anywhere on it. */
    private static BlockPos flat(ServerLevel level, int cx, int cz, int r) {
        BlockPos mid = Kit.surface(level, cx, cz);
        int y = mid.getY();
        for (int x = cx - r; x <= cx + r; x++) {
            for (int z = cz - r; z <= cz + r; z++) {
                for (int dy = -3; dy < 0; dy++) {
                    level.setBlock(new BlockPos(x, y + dy, z), dy == -1 ? Blocks.GRASS_BLOCK.defaultBlockState() : Blocks.DIRT.defaultBlockState(), 2 | 16);
                }
                for (int dy = 0; dy < 20; dy++) {
                    BlockPos p = new BlockPos(x, y + dy, z);
                    if (!level.getBlockState(p).isAir()) level.setBlock(p, Blocks.AIR.defaultBlockState(), 2 | 16);
                }
            }
        }
        return new BlockPos(cx, y, cz);
    }

    /** A town of so many folk at x, in this age, at this time of day: its founders' chest emptied, four marked chests of
     *  stores by the heart, empty for the test to fill. */
    private static Town town(GameTestHelper helper, int x, int n, Villages.Age age, long time) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        Kit.noLeftoverPlayers(level);
        level.setDayTime(time);
        level.updateSkyBrightness();
        level.setWeatherParameters(24000, 0, false, false);
        Kit.hold(level, x, Z, 64);
        Kit.prepare(level, x, Z, 64);
        BlockPos heart = flat(level, x, Z, 36);
        List<VillageFolkEntity> folk = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            VillageFolkEntity f = VillageFolkSpawnerBlock.raise(level, i == 0 ? heart : heart.south(2 + i), 0.0F);
            helper.assertTrue(f != null && (folk.isEmpty() || folk.get(0).ownerId().equals(f.ownerId())), "folk " + i + " of the town");
            f.ensurePersona();
            folk.add(f);
        }
        UUID id = folk.get(0).ownerId();
        for (BlockPos p : Villages.storeChests(level, id)) {
            if (level.getBlockEntity(p) instanceof Container c) {
                c.clearContent();
                c.setChanged();
            }
        }
        List<Container> stores = new ArrayList<>();
        for (int k = 0; k < 4; k++) {
            BlockPos at = new BlockPos(heart.getX() + 4 + 2 * k, heart.getY(), heart.getZ() - 4);
            level.setBlock(at, Blocks.CHEST.defaultBlockState(), 3);
            ZoneChests.mark(level, at);
            stores.add((Container) level.getBlockEntity(at));
        }
        Villages.forgetStores(id);
        Villages.forgetStock();
        Villages.ageForTests(id, age);
        return new Town(id, Villages.get(id), heart, stores, folk);
    }

    /** These into the town's stores, as a player would bring them. */
    private static void stock(Town t, ItemStack... goods) {
        int g = 0;
        for (Container c : t.stores()) {
            for (int i = 0; i < c.getContainerSize() && g < goods.length; i++) {
                if (!c.getItem(i).isEmpty()) continue;
                c.setItem(i, goods[g++].copy());
            }
            c.setChanged();
        }
        if (g < goods.length) throw new IllegalStateException("the test's stores are full: " + (goods.length - g) + " over");
        Villages.forgetStock();
        Budget.forget(t.village());
    }

    private static int stores(ServerLevel level, Town t, Predicate<ItemStack> what) {
        return Market.stock(level, t.village(), what);
    }

    private static int stores(ServerLevel level, Town t, Item it) {
        return stores(level, t, s -> s.is(it));
    }

    /** One of these taken out of the town's stores, wherever in them it was put (a folk fetching it). */
    private static ItemStack fetch(ServerLevel level, Town t, Predicate<ItemStack> what) {
        for (BlockPos p : Villages.storeChests(level, t.village())) {
            if (!(level.getBlockEntity(p) instanceof Container c)) continue;
            for (int i = 0; i < c.getContainerSize(); i++) {
                ItemStack s = c.getItem(i);
                if (s.isEmpty() || !what.test(s)) continue;
                ItemStack one = s.split(1);
                c.setChanged();
                Villages.forgetStock();
                return one;
            }
        }
        return ItemStack.EMPTY;
    }

    private static boolean chronicled(UUID village, String words) {
        for (Chronicle.Entry e : Chronicle.of(village)) if (e.text().toLowerCase(Locale.ROOT).contains(words)) return true;
        return false;
    }

    private static ItemStack[] many(Item it, int n) {
        List<ItemStack> out = new ArrayList<>();
        while (n > 0) {
            int k = Math.min(n, it.getDefaultMaxStackSize());
            out.add(new ItemStack(it, k));
            n -= k;
        }
        return out.toArray(new ItemStack[0]);
    }

    private static ItemStack[] join(ItemStack[]... parts) {
        List<ItemStack> out = new ArrayList<>();
        for (ItemStack[] p : parts) out.addAll(List.of(p));
        return out.toArray(new ItemStack[0]);
    }

    // ============================================================ ia01: every item has a maker

    /**
     * Every item the mod registers, one by one: a recipe of the game's (or the one thing the audit lets go without,
     * and why); its age by Tiers' rule (the audit's ages checked by name); a worth the price list knows, not guessed;
     * a maker named (Makers: the trade, and the moment the town wants one); and the town's stores, stocked with what
     * a grown town gathers, run to it at the bench by its recipe (Bench.plan: the whole way, logs to planks to the
     * thing). A new item with no maker named fails here, by name. And the pets' things are on the market's board.
     */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "ia01_every_item_has_a_maker")
    public static void ia01_every_item_has_a_maker(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        // Late in the day (no morning's business to run under the test), the last age (the age lets everything be made).
        Town t = town(helper, 1280000, 3, Villages.Age.NETHER, DAY + 7000);
        stock(t, join(
            many(Items.OAK_LOG, 64), many(Items.OAK_PLANKS, 160), many(Items.STICK, 64), many(Items.COBBLESTONE, 64),
            many(Items.WHITE_WOOL, 64), many(Items.RED_WOOL, 8), many(Items.BLUE_WOOL, 8), many(Items.YELLOW_WOOL, 8),
            many(Items.STRING, 64), many(Items.LEATHER, 32), many(Items.PAPER, 64), many(Items.BOOK, 8), many(Items.FEATHER, 16),
            many(Items.INK_SAC, 16), many(Items.GOLD_INGOT, 48), many(Items.GOLD_NUGGET, 32), many(Items.IRON_INGOT, 48),
            many(Items.IRON_NUGGET, 16), many(Items.COPPER_INGOT, 32), many(Items.COAL, 32), many(Items.CHARCOAL, 16),
            many(Items.WHEAT, 64), many(Items.WHEAT_SEEDS, 16), many(Items.BREAD, 32), many(Items.SUGAR, 16),
            many(Items.GLASS_BOTTLE, 8), many(Items.EGG, 16), many(Items.CARROT, 32), many(Items.POTATO, 32), many(Items.PUMPKIN, 8),
            many(Items.APPLE, 16), many(Items.HONEY_BOTTLE, 8), many(Items.MILK_BUCKET, 3), many(Items.COOKED_BEEF, 16),
            many(Items.BEEF, 16), many(Items.COD, 16), many(Items.SALMON, 8), many(Items.SWEET_BERRIES, 16), many(Items.POPPY, 16),
            many(Items.DANDELION, 16), many(Items.RED_DYE, 16), many(Items.YELLOW_DYE, 8), many(Items.BLUE_DYE, 8),
            many(Items.BLACK_DYE, 8), many(Items.WHITE_DYE, 8), many(Items.BONE_MEAL, 16), many(Items.LAPIS_LAZULI, 16),
            many(Items.EMERALD, 4), many(Items.DIAMOND, 24), many(Items.OBSIDIAN, 12), many(Items.ROTTEN_FLESH, 16),
            many(Items.REDSTONE, 16), many(Items.HONEYCOMB, 8), many(Items.AMETHYST_SHARD, 8), many(Items.SAND, 32),
            many(Items.GLASS, 16), many(Items.SPIDER_EYE, 8), many(Items.TORCH, 16), many(Items.DIRT, 16), many(Items.SMOOTH_STONE, 16),
            many(Items.OAK_SIGN, 8), many(Items.CHEST, 2), many(Items.BOWL, 8), many(Items.BRICK, 16), many(Items.CLAY_BALL, 16),
            many(Items.CRAFTING_TABLE, 1), many(Items.FURNACE, 1), many(Items.OAK_SLAB, 8),
            // [culture2] What the towns' own dishes are made of, besides the above (Cuisine).
            many(Items.BROWN_MUSHROOM, 8), many(Items.RED_MUSHROOM, 8), many(Items.MUTTON, 8), many(Items.RABBIT, 8),
            many(Items.BEETROOT, 24),   // [itemaudit] over the twelve the bench keeps back for seed
            // [workitems] A milestone's five cobblestone over the builders' sixty-four the stores keep back.
            many(Items.COBBLESTONE, 64),
            many(Items.CACTUS, 8), many(Items.COCOA_BEANS, 8),   // [leisure] the green and brown dyes for the lanterns
            many(Items.MAGMA_CREAM, 2)));   // [nether] the runner's satchel is waxed with it
        // A master's hands, with every bench and fire to hand: what is asked is whether the stores run to it.
        Bench.Hand hand = new Bench.Hand(40, "", true, true, true, true, true);
        List<String> wanting = new ArrayList<>();
        int items = 0, made = 0;
        for (Item item : BuiltInRegistries.ITEM) {
            ResourceLocation key = BuiltInRegistries.ITEM.getKey(item);
            if (!McAssistantMod.MODID.equals(key.getNamespace())) continue;
            items++;
            String id = key.getPath();
            List<RecipeBook.Way> ways = RecipeBook.waysFor(level, item);
            String unmade = Makers.unmade(item);
            List<Makers.Maker> makers = Makers.of(item);
            Villages.Age age = Tiers.of(level, item);
            double worth = Prices.each(item);
            boolean known = Prices.known(item);
            String plan = "";
            if (ways.isEmpty() && unmade == null) wanting.add(id + ": no recipe");
            Villages.Age want = AGES.get(id);
            if (want != null && age != want) wanting.add(id + ": " + age.label + ", not " + want.label);
            if (!known) wanting.add(id + ": no worth on the price list (only guessed at " + worth + ")");
            else if (worth <= 0 && unmade == null) wanting.add(id + ": worth nothing");
            if (makers.isEmpty() && unmade == null) {
                wanting.add(id + ": nobody makes it (declare its maker with Makers.declare beside the hook that makes it)");
            }
            if (!ways.isEmpty() && unmade == null) {
                Bench.Plan p = Bench.plan(level, t.v(), item, 1, hand);
                plan = p.chain();
                if (p.ok()) made++;
                else wanting.add(id + ": the stores' makings do not run to it: " + p.chain());
            }
            Kit.log("ia01 " + id + ": " + ways.size() + " recipe(s), " + age.label + ", worth " + String.format(Locale.ROOT, "%.2f", worth)
                + (known ? "" : " (guessed)") + "; made by " + Makers.words(item) + (plan.isEmpty() ? "" : "; at the bench: " + plan));
        }
        // Folk buy the pets' things at the shop: they are on the market's board, at their worth.
        for (Item it : List.of(McAssistantMod.PET_BOWL_ITEM.get(), McAssistantMod.DOG_BED_ITEM.get(), McAssistantMod.CAT_BED_ITEM.get(),
                McAssistantMod.COLLAR.get(), McAssistantMod.PET_TREAT.get(), CivicItems.BREWERS_STOUT.get(), CivicItems.FARMHOUSE_PIE.get(),
                CivicItems.REINFORCED_PICKAXE.get())) {
            Market.Good g = Market.goodFor(new ItemStack(it));
            if (g == null) wanting.add(BuiltInRegistries.ITEM.getKey(it).getPath() + ": folk buy it, and it is not on the market's board");
            else if (Math.abs(g.value() - Prices.each(it)) > 1e-6) wanting.add(g.name() + ": the board's " + g.value() + " is not its worth " + Prices.each(it));
        }
        Kit.log("ia01 " + items + " items of the mod's: " + made + " made at the bench out of the stores; " + wanting.size() + " wanting: " + wanting);
        helper.assertTrue(items >= 51, "the mod's things are all registered: " + items);
        helper.assertTrue(AGES.size() == 51 && AGES.keySet().stream().allMatch(n -> BuiltInRegistries.ITEM.containsKey(
            ResourceLocation.fromNamespaceAndPath(McAssistantMod.MODID, n))), "every thing the audit settled is registered: " + AGES.size());
        helper.assertTrue(wanting.isEmpty(), wanting.size() + " wanting: " + String.join("; ", wanting));
        helper.succeed();
    }

    // ============================================================ ia02: the coin, and the memory core

    /**
     * The coin, honestly made: a gold ingot fired in a furnace comes out as nine coins (the game's own recipe), the
     * mint's rate (Market.COINS_PER_GOLD); a coin is worth a coin; an Iron Age town short of coin mints the stores'
     * gold into its treasury, the gold gone from the stores and the coin in, bar for bar. The memory core: no recipe,
     * the reason given, worth nothing to anybody.
     */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "ia02_the_coin")
    public static void ia02_the_coin(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Town t = town(helper, 1282000, 3, Villages.Age.IRON, DAY + 7000);
        Item coin = McAssistantMod.VILLAGE_COIN.get();
        SingleRecipeInput bar = new SingleRecipeInput(new ItemStack(Items.GOLD_INGOT));
        ItemStack fired = level.getRecipeManager().getRecipeFor(RecipeType.SMELTING, bar, level)
            .map(h -> h.value().assemble(bar, level.registryAccess())).orElse(ItemStack.EMPTY);
        List<RecipeBook.Way> ways = RecipeBook.waysFor(level, coin);
        Kit.log("ia02 a gold ingot in the furnace: " + fired + "; the coin's ways: " + ways.size() + (ways.isEmpty() ? "" : " (" + ways.get(0).fire()
            + ", " + ways.get(0).yield() + " a firing)") + "; the " + Tiers.of(level, coin).label + "; worth " + Prices.each(coin));
        helper.assertTrue(fired.is(coin) && fired.getCount() == Market.COINS_PER_GOLD, "a bar of gold fired makes nine coins: " + fired);
        helper.assertTrue(ways.size() == 1 && ways.get(0).fire() == RecipeBook.Fire.FURNACE && ways.get(0).yield() == Market.COINS_PER_GOLD,
            "the town's makers know it as the furnace's: " + ways.size());
        helper.assertTrue(Tiers.of(level, coin) == Villages.Age.IRON, "minted from the Iron Age's gold");
        helper.assertTrue(Math.abs(Prices.each(coin) - 1.0) < 1e-9 && Prices.known(coin), "a coin is worth a coin: " + Prices.each(coin));
        // The mint: the treasury empty, three bars in the stores.
        UUID id = t.village();
        Ledger.takeCoins(id, Ledger.coins(id));
        stock(t, new ItemStack(Items.GOLD_INGOT, 3));
        int coins0 = Ledger.coins(id), gold0 = stores(level, t, Items.GOLD_INGOT);
        int minted = Market.mint(level, t.v());
        int coins1 = Ledger.coins(id), gold1 = stores(level, t, Items.GOLD_INGOT);
        Kit.log("ia02 the mint: " + minted + " coin; treasury " + coins0 + " -> " + coins1 + ", the stores' gold " + gold0 + " -> " + gold1);
        helper.assertTrue(minted == 3 * Market.COINS_PER_GOLD && coins1 - coins0 == minted && gold0 - gold1 == 3,
            "three bars of the stores' gold minted into twenty-seven coin: " + minted);
        // The memory core.
        Item core = McAssistantMod.MEMORY_CORE.get();
        String why = Makers.unmade(core);
        Kit.log("ia02 the memory core: " + RecipeBook.waysFor(level, core).size() + " recipes; worth " + Prices.each(core) + "; " + why);
        helper.assertTrue(RecipeBook.waysFor(level, core).isEmpty() && why != null && why.contains("companion falls"),
            "no recipe for a memory core, and why: " + why);
        helper.assertTrue(Prices.known(core) && Prices.each(core) == 0 && Budget.goodFor(new ItemStack(core)) == null,
            "a friend is not for sale");
        helper.succeed();
    }

    // ============================================================ ia03: the fishing net

    /**
     * A fishing town's fleet wants a net for each of its two boats: the tailor knots each of five of the stores' string,
     * and no third. A net's haul is two to four fish to a line's one or two (four hundred casts of each). And a player
     * standing over a pond casts the net: fish come up into its pack, and the net is a haul worse for wear.
     */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "ia03_the_net")
    public static void ia03_the_net(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Town t = town(helper, 1284000, 3, Villages.Age.WOOD, DAY + 7000);
        Fleet.resetForTests();
        Fleet.fromForTests(0);
        VillageFolkEntity tailor = t.folk().get(1);
        tailor.setJob(StationTask.TAILOR);
        Fleet.quayForTests(t.v(), new Waterfront.Dock(t.heart().east(20), Direction.EAST, 8));
        stock(t, new ItemStack(Items.STRING, 12));
        Item net = McAssistantMod.FISHING_NET.get();
        String one = Fleet.makeNetForTests(level, t.v(), tailor), two = Fleet.makeNetForTests(level, t.v(), tailor),
            three = Fleet.makeNetForTests(level, t.v(), tailor);
        int nets = stores(level, t, net), string = stores(level, t, Items.STRING);
        Fleet.fromForTests(-1);
        Kit.log("ia03 the tailor: " + one + " / " + two + " / " + three + "; nets in the stores " + nets + ", string left " + string);
        helper.assertTrue(one != null && two != null && three == null, "a net for each boat, and no more: " + one + ", " + two + ", " + three);
        helper.assertTrue(nets == 2 && string == 2, "two nets of ten of the stores' string: " + nets + ", " + string + " left");
        // A net's haul and a line's.
        RandomSource r = RandomSource.create(42L);
        int netFish = 0, lineFish = 0;
        for (int i = 0; i < 400; i++) {
            for (ItemStack s : Fleet.rollForTests(level, r, true)) if (raw(s)) netFish += s.getCount();
            for (ItemStack s : Fleet.rollForTests(level, r, false)) if (raw(s)) lineFish += s.getCount();
        }
        Kit.log("ia03 four hundred hauls: the net " + netFish + " fish, the line " + lineFish);
        helper.assertTrue(netFish >= 2 * 400 && netFish <= 4 * 400 && lineFish < 600 && netFish > 2 * lineFish,
            "the net brings up two to four a haul, the line one or two: " + netFish + " to " + lineFish);
        // A player's cast over a pond.
        BlockPos pondAt = new BlockPos(t.heart().getX() - 12, t.heart().getY(), t.heart().getZ() + 12);
        Kit.pond(level, pondAt.getX(), pondAt.getZ(), 3);
        BlockPos water = Kit.surface(level, pondAt.getX(), pondAt.getZ());
        Player p = helper.makeMockPlayer(GameType.SURVIVAL);
        p.moveTo(pondAt.getX() + 0.5, water.getY() + 0.1, pondAt.getZ() + 0.5, 0.0F, 90.0F);
        ItemStack held = new ItemStack(net);
        p.setItemInHand(InteractionHand.MAIN_HAND, held);
        int fish0 = carried(p, ItemAuditGameTests::raw);
        var res = net.use(level, p, InteractionHand.MAIN_HAND);
        int fish1 = carried(p, ItemAuditGameTests::raw);
        boolean cooling = p.getCooldowns().isOnCooldown(net);
        var again = net.use(level, p, InteractionHand.MAIN_HAND);
        Kit.log("ia03 the player's cast at " + water.toShortString() + " (" + level.getBlockState(water.below()) + "): " + res.getResult() + ", fish "
            + fish0 + " -> " + fish1 + ", the net " + held.getDamageValue() + "/" + held.getMaxDamage() + ", cooling " + cooling);
        helper.assertTrue(res.getResult().consumesAction() && fish1 - fish0 >= 2, "the net comes up with two fish or more: " + (fish1 - fish0));
        helper.assertTrue(held.getDamageValue() == 1 && cooling && !again.getResult().consumesAction(),
            "a haul's wear on the net, and a while before the next cast: wear " + held.getDamageValue() + ", the second cast " + again.getResult());
        // Over dry ground it does nothing.
        Player q = helper.makeMockPlayer(GameType.SURVIVAL);
        q.moveTo(t.heart().getX() + 0.5, t.heart().getY() + 0.1, t.heart().getZ() + 8.5, 0.0F, 90.0F);
        ItemStack dry = new ItemStack(net);
        q.setItemInHand(InteractionHand.MAIN_HAND, dry);
        var none = net.use(level, q, InteractionHand.MAIN_HAND);
        helper.assertTrue(!none.getResult().consumesAction() && dry.getDamageValue() == 0 && carried(q, ItemAuditGameTests::raw) == 0,
            "no haul off dry land: " + none.getResult());
        Kit.log("ia03 the second cast while it dries: " + again.getResult() + "; on dry land: " + none.getResult());
        helper.succeed();
    }

    private static boolean raw(ItemStack s) {
        return s.is(Items.COD) || s.is(Items.SALMON) || s.is(Items.PUFFERFISH) || s.is(Items.TROPICAL_FISH);
    }

    private static int carried(Player p, Predicate<ItemStack> what) {
        int n = 0;
        for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
            ItemStack s = p.getInventory().getItem(i);
            if (what.test(s)) n += s.getCount();
        }
        return n;
    }

    // ============================================================ ia04: the loom's patterns

    /**
     * A river town (its fisher, and no farmer nor miner) comes into the Stone Age and its arms are granted a fish. The
     * festival tabard it then makes wants the fish woven, and the loom the fish pattern: the tailor makes it of a sheet
     * of the stores' paper and a cod, and it stays in the stores for next time; the tabard comes out bearing the arms,
     * fish and all.
     */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "ia04_the_patterns")
    public static void ia04_the_patterns(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Town t = town(helper, 1286000, 3, Villages.Age.WOOD, DAY + 2000);
        UUID id = t.village();
        long day = level.getDayTime() / 24000L;
        for (int k = 0; k < 40 && Arms.festivalForTests(id, day); k++) day++;
        level.setDayTime(day * 24000L + 2000L);
        level.updateSkyBrightness();
        Homeland.setForTests(id, Homeland.Land.RIVER);
        Heraldry.chooseForTests(level, t.v());
        t.folk().get(0).setJob(StationTask.FISH);
        t.folk().get(1).setJob(StationTask.WOOD);
        t.folk().get(2).setJob(StationTask.TAILOR);
        t.folk().get(2).tradeXpForTests(StationTask.TAILOR, AssistantEntity.xpForLevel(12));   // banners are level-ten work
        Villages.ageForTests(id, Villages.Age.STONE);
        boolean granted = Arms.newAgeForTests(level, t.v());
        Heraldry.Design d = Heraldry.design(id);
        helper.assertTrue(granted && d != null && !d.layers().isEmpty() && d.layers().get(d.layers().size() - 1).charge() == Heraldry.Charge.FISH,
            "a fish granted the river town: " + (d == null ? "no arms" : d.blazon()));
        List<ItemStack> goods = new ArrayList<>(List.of(new ItemStack(Items.PAPER, 4), new ItemStack(Items.COD, 4), new ItemStack(Items.WHITE_WOOL, 48),
            new ItemStack(Items.STICK, 8)));
        for (DyeColor c : DyeColor.values()) goods.add(new ItemStack(net.minecraft.world.item.DyeItem.byColor(c), 12));
        stock(t, goods.toArray(new ItemStack[0]));
        Item fish = McAssistantMod.FISH_PATTERN.get();
        int paper0 = stores(level, t, Items.PAPER), cod0 = stores(level, t, Items.COD);
        boolean made = Arms.tabardForTests(level, t.v(), false);
        int patterns = stores(level, t, fish), paper1 = stores(level, t, Items.PAPER), cod1 = stores(level, t, Items.COD);
        int tabards = stores(level, t, s -> s.is(McAssistantMod.TABARD.get()) && Arms.bears(s, d));
        ItemStack tabard = fetch(level, t, s -> s.is(McAssistantMod.TABARD.get()));
        BannerPatternLayers on = tabard.getOrDefault(DataComponents.BANNER_PATTERNS, BannerPatternLayers.EMPTY);
        boolean fishOn = false;
        for (BannerPatternLayers.Layer l : on.layers()) if (l.pattern().is(Heraldry.Charge.FISH.key)) fishOn = true;
        Kit.log("ia04 the arms: " + d.blazon() + "; the tabard made " + made + " (waiting on " + Arms.shortForTests(id) + "); fish patterns in the stores "
            + patterns + ", paper " + paper0 + " -> " + paper1 + ", cod " + cod0 + " -> " + cod1 + "; tabards of the arms " + tabards
            + ", its layers " + on.layers().size() + ", the fish on it " + fishOn);
        helper.assertTrue(made && patterns == 1 && paper0 - paper1 == 1 && cod0 - cod1 == 1,
            "the fish pattern made of the stores' paper and cod, and kept: " + patterns);
        helper.assertTrue(tabards == 1 && fishOn, "the festival tabard bears the arms, the fish woven: " + tabards + ", " + fishOn);
        // A second tabard: the pattern is to hand, and no more paper or fish goes on another.
        Arms.tabardForTests(level, t.v(), false);
        helper.assertTrue(stores(level, t, fish) == 1 && stores(level, t, Items.PAPER) == paper1 && stores(level, t, Items.COD) == cod1,
            "the pattern kept and used again, not made again");
        helper.succeed();
    }

    // ============================================================ ia05: the forger's coin, and the stocks

    /**
     * A forger casts a copper ingot of the stores into three coins, as the recipe has it: one passed at the stores for
     * a treat, two in its pack. Handed in, the three are melted down into the copper they were cast of (the recipe the
     * other way about, the town's and a player's). And stocks a player has put up on the square are the town's own.
     */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "ia05_forgery_and_the_stocks")
    public static void ia05_forgery_and_the_stocks(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Town t = town(helper, 1288000, 3, Villages.Age.STONE, DAY + 2000);
        VillageFolkEntity forger = t.folk().get(1);
        forger.removeMatching(s -> s.is(Items.COPPER_INGOT), 64);
        stock(t, new ItemStack(Items.COPPER_INGOT), new ItemStack(Items.COOKIE, 2));
        Item forged = McAssistantMod.FORGED_COIN.get();
        Crime.Case c = Crime.commitForTests(level, forger, Crime.Kind.FORGERY, null, null);
        int copper = stores(level, t, Items.COPPER_INGOT), passed = stores(level, t, forged), kept = forger.countCarried(s -> s.is(forged));
        Kit.log("ia05 the forgery: " + (c == null ? "none" : c.place + ", " + c.goods()) + "; copper in the stores " + copper + ", forged coins passed " + passed + ", kept " + kept);
        helper.assertTrue(c != null && copper == 0 && passed == 1 && kept == 2, "a copper ingot of the stores cast into three: one passed, two kept");
        // Handed in, and melted down.
        int took = forger.removeMatching(s -> s.is(forged), 2);
        stock(t, new ItemStack(forged, took));
        int bars = Crime.meltForgedForTests(level, t.v());
        int left = stores(level, t, forged), back = stores(level, t, Items.COPPER_INGOT);
        Kit.log("ia05 melted: " + bars + " bar(s); forged coins left " + left + ", copper " + back);
        helper.assertTrue(took == 2 && bars == 1 && left == 0 && back == 1, "the three melted back into the copper they were cast of");
        net.minecraft.world.item.crafting.CraftingInput three = CraftingInput.of(3, 1,
            List.of(new ItemStack(forged), new ItemStack(forged), new ItemStack(forged)));
        ItemStack bench = level.getRecipeManager().getRecipeFor(RecipeType.CRAFTING, three, level)
            .map(h -> h.value().assemble(three, level.registryAccess())).orElse(ItemStack.EMPTY);
        helper.assertTrue(bench.is(Items.COPPER_INGOT), "and at the crafting table: " + bench);
        // A player's stocks on the square.
        BlockPos at = Kit.surface(level, t.heart().getX() + 6, t.heart().getZ() + 9);
        level.setBlockAndUpdate(at, McAssistantMod.STOCKS.get().defaultBlockState().setValue(StocksBlock.FACING, Direction.NORTH));
        BlockPos adopted = Crime.adoptStocksForTests(level, t.v());
        BlockPos known = Crime.stocksForTests(level, t.v());
        Kit.log("ia05 the player's stocks at " + at.toShortString() + ": adopted " + adopted + ", the town's " + known);
        helper.assertTrue(at.equals(adopted) && at.equals(known), "the stocks a player put up are the town's for its sentences");
        helper.assertTrue(chronicled(t.village(), "taken for the town's own"), "and the chronicle says so");
        helper.succeed();
    }

    // ============================================================ ia06: the masters' goods

    /**
     * A town with a tavern, fed: the master brewer brews a stout of a bottle, two wheat and sugar out of the stores; a
     * miner at the bar buys it out of its purse (the coin into the treasury) and digs the quicker for it, the bottle
     * back. The master cook bakes two farmhouse pies of a pumpkin, an egg, a carrot and three wheat; a hungry folk at
     * its meal eats one, a better meal than bread. The tailor binds an apprentice's journal of a book, a feather, an ink
     * sac and leather; the young apprentice takes it out of the stores, and from the next day writes up its day in it
     * (its trade's experience, put by).
     */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "ia06_the_masters_goods")
    public static void ia06_the_masters_goods(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Town t = town(helper, 1290000, 5, Villages.Age.IRON, DAY + 2000);
        UUID id = t.village();
        long day = level.getDayTime() / 24000L;
        VillageFolkEntity brewer = t.folk().get(0), cook = t.folk().get(1), tailor = t.folk().get(2), miner = t.folk().get(3), child = t.folk().get(4);
        brewer.setJob(StationTask.BREW);
        brewer.tradeXpForTests(StationTask.BREW, AssistantEntity.xpForLevel(30));
        cook.setJob(StationTask.COOK);
        cook.tradeXpForTests(StationTask.COOK, AssistantEntity.xpForLevel(30));
        tailor.setJob(StationTask.TAILOR);
        miner.setJob(StationTask.MINE);
        child.setStation(null, StationTask.NONE);
        child.setChild(true);
        child.bornDaysAgo(1);
        child.apprenticeForTests(StationTask.TAILOR);
        Ledger.built(id, "tavern", t.heart().offset(-20, 0, 20), Direction.NORTH);
        Leader.booksForTests(id, new Leader.Books(400, 40, 20, 40, 20, 20, Leader.Plan.PLENTY, day));
        stock(t, new ItemStack(Items.GLASS_BOTTLE), new ItemStack(Items.WHEAT, 5), new ItemStack(Items.SUGAR), new ItemStack(Items.PUMPKIN),
            new ItemStack(Items.EGG), new ItemStack(Items.CARROT), new ItemStack(Items.BOOK), new ItemStack(Items.FEATHER),
            new ItemStack(Items.INK_SAC), new ItemStack(Items.LEATHER));
        Item stout = CivicItems.BREWERS_STOUT.get(), pie = CivicItems.FARMHOUSE_PIE.get(), journal = CivicItems.APPRENTICE_JOURNAL.get();

        // The stout, brewed and drunk.
        String brewed = TradeGoods.craftForTests(level, t.v(), brewer);
        int stouts = stores(level, t, stout), bottles0 = stores(level, t, Items.GLASS_BOTTLE);
        Kit.log("ia06 the brewer: " + brewed + "; stouts " + stouts + ", bottles " + bottles0 + ", wheat " + stores(level, t, Items.WHEAT));
        helper.assertTrue(brewed != null && stouts == 1 && bottles0 == 0 && stores(level, t, Items.SUGAR) == 0,
            "the master brewer brews a stout of the stores' bottle, wheat and sugar: " + brewed);
        miner.earn(20);
        int purse0 = miner.purse(), treasury0 = Ledger.coins(id);
        boolean drank = false;
        for (int i = 0; i < 64 && !drank; i++) drank = TradeGoods.stoutForTests(level, t.v(), miner, day);
        Kit.log("ia06 at the bar: " + drank + "; the miner's purse " + purse0 + " -> " + miner.purse() + ", the treasury " + treasury0 + " -> "
            + Ledger.coins(id) + "; haste " + miner.hasEffect(MobEffects.DIG_SPEED));
        helper.assertTrue(drank && stores(level, t, stout) == 0 && stores(level, t, Items.GLASS_BOTTLE) == 1,
            "the miner drinks the stout, the bottle back in the stores");
        helper.assertTrue(purse0 - miner.purse() == Ledger.coins(id) - treasury0 && miner.purse() < purse0, "paid for out of its purse, into the treasury");
        helper.assertTrue(miner.hasEffect(MobEffects.DIG_SPEED), "and it digs the quicker for it");

        // The pies, baked and eaten.
        String baked = TradeGoods.craftForTests(level, t.v(), cook);
        int pies = stores(level, t, pie);
        Kit.log("ia06 the cook: " + baked + "; pies " + pies + ", wheat " + stores(level, t, Items.WHEAT) + ", pumpkins " + stores(level, t, Items.PUMPKIN));
        helper.assertTrue(baked != null && pies == 2 && stores(level, t, Items.WHEAT) == 0 && stores(level, t, Items.PUMPKIN) == 0
            && stores(level, t, Items.EGG) == 0 && stores(level, t, Items.CARROT) == 0, "two pies of a pumpkin, an egg, a carrot and three wheat");
        miner.removeMatching(s -> s.get(DataComponents.FOOD) != null, 256);
        boolean ate = Meals.eatForTests(level, miner);
        Kit.log("ia06 the miner's meal: " + ate + ", " + miner.lastMeal() + " (" + miner.dietPercent() + "); pies left " + stores(level, t, pie));
        helper.assertTrue(ate && miner.lastMeal().toLowerCase(Locale.ROOT).contains("farmhouse pie") && stores(level, t, pie) == 1,
            "a hungry folk eats a pie out of the stores at its meal: " + miner.lastMeal());
        helper.assertTrue(miner.dietPercent() > AssistantEntity.foodQuality(new ItemStack(Items.BREAD)), "a better meal than bread");

        // The apprentice's journal, bound, taken and written up.
        String bound = TradeGoods.craftForTests(level, t.v(), tailor);
        int journals = stores(level, t, journal);
        Kit.log("ia06 the tailor: " + bound + "; journals " + journals + ", books " + stores(level, t, Items.BOOK));
        helper.assertTrue(bound != null && journals == 1 && stores(level, t, Items.BOOK) == 0 && stores(level, t, Items.LEATHER) == 0,
            "the tailor binds a journal of a book, a feather, an ink sac and leather");
        PlayerCivic.resetForTests();
        PlayerCivic.tick(level, t.v());
        int carried = child.countCarried(s -> s.is(journal));
        helper.assertTrue(carried == 1 && stores(level, t, journal) == 0, "the young apprentice takes it out of the stores: " + carried);
        level.setDayTime((day + 1) * 24000L + 2000L);
        PlayerCivic.resetForTests();
        int xp0 = child.tradeXpOfForTests(StationTask.TAILOR);
        PlayerCivic.tick(level, t.v());
        int xp1 = child.tradeXpOfForTests(StationTask.TAILOR);
        Kit.log("ia06 the apprentice writes up its day: tailoring experience " + xp0 + " -> " + xp1);
        helper.assertTrue(xp1 > xp0, "a day's learning written up in it: " + xp0 + " -> " + xp1);
        helper.succeed();
    }

    // ============================================================ ia07: the village board, put back up

    /**
     * The town's board is taken down, every panel of it. At its look the town makes another at the bench of its stores'
     * planks, sticks and a book (the board's recipe: signs, planks and a book) and puts it up on the square; the
     * chronicle has it; and with the board standing, the next look does nothing.
     */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "ia07_the_board")
    public static void ia07_the_board(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Town t = town(helper, 1294000, 3, Villages.Age.STONE, DAY + 7000);
        UUID id = t.village();
        BlockPos anchor = VillageBoards.boardOf(id);
        helper.assertTrue(anchor != null && level.getBlockState(anchor).getBlock() instanceof VillageBoardBlock, "the founders' board stands");
        Direction facing = level.getBlockState(anchor).getValue(VillageBoardBlock.FACING);
        for (int c = 0; c < VillageBoardBlock.WIDE; c++) {
            for (int r = 0; r < VillageBoardBlock.HIGH; r++) level.setBlock(VillageBoardBlock.cell(anchor, facing, c, r), Blocks.AIR.defaultBlockState(), 3);
        }
        helper.assertTrue(VillageBoards.boardOf(id) == null, "the board is down");
        stock(t, join(many(Items.OAK_PLANKS, 96), many(Items.STICK, 8), many(Items.BOOK, 1), many(Items.CRAFTING_TABLE, 1)));
        int planks0 = stores(level, t, Items.OAK_PLANKS), books0 = stores(level, t, Items.BOOK);
        String did = VillageBoards.keepNow(level, t.v());
        BlockPos now = VillageBoards.boardOf(id);
        int planks1 = stores(level, t, Items.OAK_PLANKS), books1 = stores(level, t, Items.BOOK);
        Kit.log("ia07 the board: " + did + " at " + now + "; planks " + planks0 + " -> " + planks1 + ", books " + books0 + " -> " + books1
            + "; boards left in the stores " + stores(level, t, McAssistantMod.VILLAGE_BOARD_ITEM.get()));
        helper.assertTrue(did != null && now != null && level.getBlockState(now).getBlock() instanceof VillageBoardBlock, "a new board up: " + did);
        helper.assertTrue(books0 - books1 == 1 && planks0 - planks1 >= 12, "made of the stores' book and planks");
        helper.assertTrue(stores(level, t, McAssistantMod.VILLAGE_BOARD_ITEM.get()) == 0, "and out of the stores onto the square");
        helper.assertTrue(chronicled(id, "a new board was made"), "the chronicle has it");
        helper.assertTrue(VillageBoards.keepNow(level, t.v()) == null, "a standing board is left be");
        helper.succeed();
    }

    // ============================================================ ia08: the ribbon, cut

    /**
     * A player strings ribbons across its new doorway and cuts one with shears: snipped, gone, and the folk about cheer.
     * Cut through the block's own use, the shears take the wear.
     */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "ia08_the_ribbon")
    public static void ia08_the_ribbon(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Town t = town(helper, 1296000, 3, Villages.Age.WOOD, DAY + 7000);
        BlockPos one = Kit.surface(level, t.heart().getX() + 3, t.heart().getZ() + 3);
        BlockPos two = one.east();
        BlockState ribbon = McAssistantMod.RIBBON.get().defaultBlockState();
        level.setBlockAndUpdate(one, ribbon);
        level.setBlockAndUpdate(two, ribbon);
        Player p = helper.makeMockPlayer(GameType.SURVIVAL);
        p.moveTo(one.getX() + 0.5, one.getY(), one.getZ() - 1.5, 0.0F, 0.0F);
        boolean[] cut = { false };
        String said = BigWorks.cutByPlayer(level, one, p, cut);
        Kit.log("ia08 the cut: " + said + "; the block " + level.getBlockState(one));
        helper.assertTrue(cut[0] && level.getBlockState(one).isAir(), "snipped and gone");
        helper.assertTrue(said.contains("cheer"), "the folk about cheer: " + said);
        ItemStack shears = new ItemStack(Items.SHEARS);
        p.setItemInHand(InteractionHand.MAIN_HAND, shears);
        ItemInteractionResult r = level.getBlockState(two).useItemOn(shears, level, p, InteractionHand.MAIN_HAND,
            new BlockHitResult(Vec3.atCenterOf(two), Direction.NORTH, two, false));
        Kit.log("ia08 with the shears: " + r + "; the block " + level.getBlockState(two) + "; the shears " + shears.getDamageValue());
        helper.assertTrue(r.consumesAction() && level.getBlockState(two).isAir() && shears.getDamageValue() == 1, "cut with the shears, which wear");
        helper.succeed();
    }

    // ============================================================ ia09: every garment

    /**
     * An Iron Age town's tailor, with the stores' wool, string, leather, gold, lapis, paper and red dye: every garment
     * put on its book is made by its recipe out of the stores, dyed crimson where a dye takes (the brooch is gold), and
     * each, fetched out of the stores, is put on by a folk in its place.
     */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "ia09_every_garment")
    public static void ia09_every_garment(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Town t = town(helper, 1298000, 3, Villages.Age.IRON, DAY + 7000);
        UUID id = t.village();
        long day = level.getDayTime() / 24000L;
        VillageFolkEntity tailor = t.folk().get(0), wearer = t.folk().get(1);
        tailor.setJob(StationTask.TAILOR);
        stock(t, join(many(Items.WHITE_WOOL, 64), many(Items.STRING, 16), many(Items.LEATHER, 16), many(Items.GOLD_NUGGET, 8),
            many(Items.LAPIS_LAZULI, 4), many(Items.PAPER, 4), many(Items.RED_DYE, 16)));
        int red = DyeColor.RED.getId();
        List<String> wanting = new ArrayList<>();
        for (Garment g : Garment.values()) {
            int wool0 = stores(level, t, s -> s.is(Items.WHITE_WOOL));
            Tailoring.orderForTests(id, g, g.dyeable() ? red : Garment.NATURAL, day);
            Tailoring.resetRetryForTests(id);
            String made = Tailoring.workForTests(level, tailor);
            Predicate<ItemStack> it = s -> Garment.of(s) == g;
            int have = stores(level, t, it);
            ItemStack got = fetch(level, t, it);
            boolean dyed = !g.dyeable() || Garment.colourOf(got) == red;
            if (!got.isEmpty()) Fashion.wearForTests(level, wearer, got);
            Garment on = wearer.style().garment(g.slot);
            Kit.log("ia09 " + g.id + ": " + made + "; in the stores " + have + ", wool " + wool0 + " -> " + stores(level, t, s -> s.is(Items.WHITE_WOOL))
                + "; dyed " + dyed + "; worn " + on);
            if (made == null || have != 1) wanting.add(g.id + " not made: " + Tailoring.bookForTests(id));
            else if (!dyed) wanting.add(g.id + " not dyed");
            else if (on != g) wanting.add(g.id + " not put on (" + on + ")");
        }
        helper.assertTrue(wanting.isEmpty(), "every garment made of the stores and worn: " + wanting);
        helper.assertTrue(stores(level, t, s -> s.is(Items.GOLD_NUGGET)) == 4 && stores(level, t, s -> s.is(Items.LAPIS_LAZULI)) == 3,
            "the waistcoat's, the top hat's and the brooch's gold, and the brooch's lapis, out of the stores");
        helper.succeed();
    }

    // ============================================================ ia10: the player's tools, to order

    /**
     * The player's own tools, which no folk wants: put on the shop's workshop's book by a player's order, a hand makes
     * each out of the stores by its recipe (a place marker of paper and a stick, a zone marker of redstone and sticks).
     */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "ia10_the_players_tools")
    public static void ia10_the_players_tools(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Town t = town(helper, 1292000, 2, Villages.Age.IRON, DAY + 7000);
        UUID id = t.village();
        VillageFolkEntity keeper = t.folk().get(0), hand = t.folk().get(1);
        BlockPos shopAt = Kit.surface(level, t.heart().getX() - 42, t.heart().getZ() - 20);
        BuildGoal.stamp(level, "shop", shopAt, Direction.NORTH, 13, Showcase.painter(Showcase.OAK));
        Ledger.built(id, "shop", shopAt, Direction.NORTH);
        keeper.setJob(StationTask.SHOP);
        Workshop.appointForTests(hand);
        stock(t, new ItemStack(Items.PAPER, 4), new ItemStack(Items.STICK, 16), new ItemStack(Items.REDSTONE, 4), new ItemStack(Items.CRAFTING_TABLE));
        Item place = McAssistantMod.PLACE_MARKER.get(), zone = McAssistantMod.ZONE_MARKER.get();
        String a = Workshop.order(level, t.v(), place, 1, "Tester"), b = Workshop.order(level, t.v(), zone, 1, "Tester");
        Workshop.lookForTests(level, t.v());
        for (int i = 0; i < 10 && (stores(level, t, place) < 1 || stores(level, t, zone) < 1); i++) Crafts.now(hand, level, t.v());
        Kit.log("ia10 orders: " + a + " / " + b + "; made: place markers " + stores(level, t, place) + ", zone markers " + stores(level, t, zone)
            + "; paper " + stores(level, t, Items.PAPER) + ", redstone " + stores(level, t, Items.REDSTONE) + "; the shop's day " + Workshop.logForTests(id));
        helper.assertTrue(a.startsWith("On the workshop's book") && b.startsWith("On the workshop's book"), "both on the book: " + a + " / " + b);
        // A stackable thing comes off the workshop's bench by the batch (Stockroom: four at a making), a paper each.
        int placed = stores(level, t, place);
        helper.assertTrue(placed >= 1 && placed <= 4 && 4 - stores(level, t, Items.PAPER) == placed,
            "place markers of the stores' paper and sticks, a paper each: " + placed + ", paper left " + stores(level, t, Items.PAPER));
        helper.assertTrue(stores(level, t, zone) == 1 && stores(level, t, Items.REDSTONE) == 3, "a zone marker of the stores' redstone and sticks");
        helper.succeed();
    }

    // ============================================================ ia11: no master yet

    /**
     * A young town with no master cook and no master smith, as every town is for its first months. Its best cook, not
     * its greenest, bakes the farmhouse pies out of the stores, one turn at the bench in three; once a master cook comes
     * up the pies are the master's at every turn, and the other cook's no longer. Its best smith is the hand for the
     * miners' reinforced picks, and a pick of its riveting is as good as its hand: an apprentice's work, wearing through
     * sooner, with its name on it.
     */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "ia11_no_master_yet")
    public static void ia11_no_master_yet(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Town t = town(helper, 1299000, 5, Villages.Age.IRON, DAY + 2000);
        TradeGoods.resetForTests();
        VillageFolkEntity good = t.folk().get(0), green = t.folk().get(1), smith = t.folk().get(2), learner = t.folk().get(3), miner = t.folk().get(4);
        good.setJob(StationTask.COOK);
        good.tradeXpForTests(StationTask.COOK, AssistantEntity.xpForLevel(8));
        green.setJob(StationTask.COOK);
        green.tradeXpForTests(StationTask.COOK, AssistantEntity.xpForLevel(2));
        smith.setJob(StationTask.SMITH);
        smith.tradeXpForTests(StationTask.SMITH, AssistantEntity.xpForLevel(3));
        learner.setJob(StationTask.SMITH);
        learner.tradeXpForTests(StationTask.SMITH, AssistantEntity.xpForLevel(1));
        miner.setJob(StationTask.MINE);
        Item pie = CivicItems.FARMHOUSE_PIE.get(), pick = CivicItems.REINFORCED_PICKAXE.get();
        stock(t, new ItemStack(Items.PUMPKIN, 3), new ItemStack(Items.EGG, 3), new ItemStack(Items.CARROT, 3), new ItemStack(Items.WHEAT, 9),
            new ItemStack(Items.IRON_PICKAXE), new ItemStack(Items.IRON_INGOT, 3), new ItemStack(Items.COPPER_INGOT));

        // The greenest cook never; the best, one turn in three.
        String greenSays = null;
        for (int i = 0; i < 3; i++) greenSays = greenSays != null ? greenSays : TradeGoods.craftForTests(level, t.v(), green);
        helper.assertTrue(greenSays == null && stores(level, t, pie) == 0, "the town's greenest cook leaves the pies to its best: " + greenSays);
        String[] turns = new String[3];
        for (int i = 0; i < 3; i++) turns[i] = TradeGoods.craftForTests(level, t.v(), good);
        Kit.log("ia11 the best cook's turns: " + turns[0] + " / " + turns[1] + " / " + turns[2] + "; pies " + stores(level, t, pie)
            + ", pumpkins " + stores(level, t, Items.PUMPKIN) + ", wheat " + stores(level, t, Items.WHEAT));
        helper.assertTrue(turns[0] == null && turns[1] == null && turns[2] != null, "one turn at the bench in three: " + String.join(" / ", String.valueOf(turns[0]),
            String.valueOf(turns[1]), String.valueOf(turns[2])));
        helper.assertTrue(stores(level, t, pie) == 2 && stores(level, t, Items.PUMPKIN) == 2 && stores(level, t, Items.WHEAT) == 6,
            "two pies of the stores' pumpkin, egg, carrot and three wheat");

        // A master cook comes up: the pies are the master's, at every turn.
        green.tradeXpForTests(StationTask.COOK, AssistantEntity.xpForLevel(30));
        String after = null;
        for (int i = 0; i < 3; i++) after = after != null ? after : TradeGoods.craftForTests(level, t.v(), good);
        String master = TradeGoods.craftForTests(level, t.v(), green);
        Kit.log("ia11 with a master: the old best hand " + after + "; the master " + master + "; pies " + stores(level, t, pie));
        helper.assertTrue(after == null && !TradeGoods.handForTests(t.v(), good), "with a master in the town the best hand leaves them to it");
        helper.assertTrue(master != null && stores(level, t, pie) == 4, "the master bakes at its first turn: " + master);

        // The best smith rivets the miners' picks, as good as its hand.
        helper.assertTrue(TradeGoods.handForTests(t.v(), smith) && !TradeGoods.handForTests(t.v(), learner), "the best smith is the hand for the picks, not the learner");
        boolean riveted = TradeGoods.pickByForTests(level, t.v(), smith);
        ItemStack made = fetch(level, t, s -> s.is(pick));
        Craftsmanship.Grade grade = Craftsmanship.gradeOf(made);
        boolean named = made.getOrDefault(DataComponents.LORE, ItemLore.EMPTY).lines().stream().anyMatch(c -> c.getString().contains(smith.displayNameCap()));
        Kit.log("ia11 the smith's pick: " + riveted + ", " + grade + ", lasts " + made.getMaxDamage() + " of " + new ItemStack(pick).getMaxDamage()
            + ", named " + named + "; iron " + stores(level, t, Items.IRON_INGOT) + ", copper " + stores(level, t, Items.COPPER_INGOT));
        helper.assertTrue(riveted && made.is(pick) && stores(level, t, Items.IRON_PICKAXE) == 0 && stores(level, t, Items.IRON_INGOT) == 0
            && stores(level, t, Items.COPPER_INGOT) == 0, "an iron pick, three bars and a copper strap out of the stores");
        helper.assertTrue(grade == Craftsmanship.Grade.ROUGH && made.getMaxDamage() < new ItemStack(pick).getMaxDamage() && named,
            "an apprentice's work, wearing through sooner, with the smith's name on it: " + grade);
        helper.succeed();
    }
}
