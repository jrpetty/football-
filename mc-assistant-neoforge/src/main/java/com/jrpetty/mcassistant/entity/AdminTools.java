package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.block.VillageFolkSpawnerBlock;
import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.entity.goal.BuildGoal;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.NonNullList;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [admin] The admin toolkit's service: every admin action as a change to the real state, made the way play would make
 * it, so the town stays consistent afterwards and runs on by itself. An age set is the age the town's own rules read
 * (trades, research, the rules on what it may build and use), its buildings made over through the ages' own make-over
 * and the renovation engine, its stores and its folk's tools stocked for the age. Folk added are raised the way the
 * founding party is (named, kitted, joining the town, the town's own pick of trade), with camp beds pitched and the
 * houses they want left to the town's builders. A war is declared, a phase staged and a peace made through the war's
 * own code; coin goes into the treasury the Ledger keeps; a market day is the market's own.
 *
 * <p>Each call does one bounded thing: the big actions (five hundred folk, every building) are cut into units here and
 * spread over ticks by admin/AdminJobs. Nothing here runs unless an admin asks: no tick, no cost.
 */
public final class AdminTools {

    private AdminTools() {}

    /** What an action came to: whether it worked, its one line, and the chronicle's "By decree" words (null: nothing changed). */
    public record Done(boolean ok, String text, @Nullable String decree) {
        public static Done ok(String text, String decree) { return new Done(true, text, decree); }
        public static Done said(String text) { return new Done(true, text, null); }
        public static Done no(String text) { return new Done(false, text, null); }
    }

    static long day(ServerLevel level) {
        return level.getDayTime() / 24000L;
    }

    // ------------------------------------------------------------------ hooks the game reads (Market, Raids)

    /** Market days made by decree, by town: the day it holds. */
    private static final Map<UUID, Long> MARKET_DAY = new ConcurrentHashMap<>();
    /** A raiding band's size set by decree, taken by the next band. */
    private static final Map<UUID, Integer> RAID_SIZE = new ConcurrentHashMap<>();

    /** Is today a market day by decree (Market.marketDay)? */
    public static boolean marketDecreed(UUID village, long day) {
        if (MARKET_DAY.isEmpty()) return false;
        Long d = MARKET_DAY.get(village);
        return d != null && d == day;
    }

    /** The next band's size: one set by decree, else the band's own (Raids.band). */
    public static int raidSize(UUID village, int usual) {
        if (RAID_SIZE.isEmpty()) return usual;
        Integer n = RAID_SIZE.remove(village);
        return n == null ? usual : n;
    }

    public static void resetForTests() {
        MARKET_DAY.clear();
        RAID_SIZE.clear();
    }

    // ------------------------------------------------------------------ finding things

    /** The town's folk, loaded and alive (the showcase's and the hired left out). */
    public static List<VillageFolkEntity> folk(UUID village) {
        List<VillageFolkEntity> out = new ArrayList<>();
        for (AssistantEntity a : Villages.folkOf(village)) {
            if (a instanceof VillageFolkEntity f && f.isAlive() && !f.isShowcase()) out.add(f);
        }
        return out;
    }

    /** A trade by its word ("smith", "Blacksmith", "smithing", "SMITH"), or null. */
    @Nullable
    public static StationTask trade(String word) {
        String w = word.trim().toLowerCase(Locale.ROOT).replace('_', ' ');
        for (StationTask t : StationTask.values()) {
            if (t == StationTask.NONE) continue;
            if (t.name().toLowerCase(Locale.ROOT).replace('_', ' ').equals(w) || t.title.toLowerCase(Locale.ROOT).equals(w)) return t;
        }
        for (StationTask t : StationTask.values()) {
            if (t != StationTask.NONE && (t.title.toLowerCase(Locale.ROOT).startsWith(w) || t.name().toLowerCase(Locale.ROOT).startsWith(w))) return t;
        }
        return null;
    }

    /** The trades' words, for tab completion. */
    public static List<String> tradeWords() {
        List<String> out = new ArrayList<>();
        for (StationTask t : StationTask.values()) if (t != StationTask.NONE) out.add(t.name().toLowerCase(Locale.ROOT));
        return out;
    }

    @Nullable
    public static Villages.Age age(String word) {
        for (Villages.Age a : Villages.Age.values()) if (a.name().equalsIgnoreCase(word.trim())) return a;
        return null;
    }

    @Nullable
    static Item item(String word) {
        ResourceLocation id = ResourceLocation.tryParse(word.contains(":") ? word : "minecraft:" + word);
        if (id == null || !BuiltInRegistries.ITEM.containsKey(id)) return null;
        Item it = BuiltInRegistries.ITEM.get(id);
        return it == Items.AIR ? null : it;
    }

    // ------------------------------------------------------------------ ages

    /** The goods a town of each age keeps as a matter of course: what its stores are stocked with when the age is set. */
    private static final Map<Villages.Age, Object[][]> AGE_STOCK = new EnumMap<>(Villages.Age.class);

    static {
        AGE_STOCK.put(Villages.Age.WOOD, new Object[][]{ { Items.OAK_LOG, 128 }, { Items.OAK_PLANKS, 128 }, { Items.BREAD, 64 },
            { Items.WHEAT_SEEDS, 32 }, { Items.TORCH, 32 }, { Items.COBBLESTONE, 64 } });
        AGE_STOCK.put(Villages.Age.STONE, new Object[][]{ { Items.OAK_LOG, 192 }, { Items.OAK_PLANKS, 128 }, { Items.COBBLESTONE, 256 },
            { Items.STONE_BRICKS, 128 }, { Items.COAL, 64 }, { Items.BREAD, 128 }, { Items.TORCH, 64 } });
        AGE_STOCK.put(Villages.Age.IRON, new Object[][]{ { Items.OAK_LOG, 192 }, { Items.OAK_PLANKS, 192 }, { Items.COBBLESTONE, 256 },
            { Items.STONE_BRICKS, 256 }, { Items.COAL, 128 }, { Items.IRON_INGOT, 128 }, { Items.GLASS, 64 }, { Items.BREAD, 192 },
            { Items.LANTERN, 16 } });
        AGE_STOCK.put(Villages.Age.DIAMOND, new Object[][]{ { Items.OAK_LOG, 192 }, { Items.STONE_BRICKS, 256 }, { Items.COAL, 128 },
            { Items.IRON_INGOT, 256 }, { Items.GOLD_INGOT, 32 }, { Items.COPPER_INGOT, 64 }, { Items.DIAMOND, 32 }, { Items.GLASS, 64 },
            { Items.BREAD, 256 } });
        AGE_STOCK.put(Villages.Age.NETHER, new Object[][]{ { Items.OAK_LOG, 192 }, { Items.STONE_BRICKS, 256 }, { Items.IRON_INGOT, 320 },
            { Items.DIAMOND, 64 }, { Items.OBSIDIAN, 32 }, { Items.QUARTZ, 64 }, { Items.GOLD_INGOT, 64 }, { Items.BREAD, 320 } });
    }

    /** The categories the stores can be filled by. */
    private static final Map<String, Object[][]> CATEGORY = new LinkedHashMap<>();

    static {
        CATEGORY.put("food", new Object[][]{ { Items.BREAD, 128 }, { Items.COOKED_BEEF, 64 }, { Items.CARROT, 64 }, { Items.POTATO, 64 },
            { Items.WHEAT, 64 } });
        CATEGORY.put("wood", new Object[][]{ { Items.OAK_LOG, 192 }, { Items.OAK_PLANKS, 192 }, { Items.STICK, 64 } });
        CATEGORY.put("stone", new Object[][]{ { Items.COBBLESTONE, 256 }, { Items.STONE_BRICKS, 128 }, { Items.GLASS, 64 } });
        CATEGORY.put("metal", new Object[][]{ { Items.IRON_INGOT, 128 }, { Items.GOLD_INGOT, 32 }, { Items.COPPER_INGOT, 64 }, { Items.COAL, 128 } });
        CATEGORY.put("tools", new Object[][]{ { Items.IRON_AXE, 4 }, { Items.IRON_PICKAXE, 4 }, { Items.IRON_HOE, 4 }, { Items.IRON_SHOVEL, 4 },
            { Items.SHEARS, 2 }, { Items.FISHING_ROD, 2 } });
        CATEGORY.put("cloth", new Object[][]{ { Items.WHITE_WOOL, 64 }, { Items.LEATHER, 64 }, { Items.STRING, 32 } });
    }

    /** What filling the stores with this ("age", "all" or a category) would put in, as item and count. */
    public static Map<Item, Integer> fillList(UUID village, String what) {
        Map<Item, Integer> out = new LinkedHashMap<>();
        List<Object[][]> parts = new ArrayList<>();
        switch (what) {
            case "age" -> parts.add(AGE_STOCK.get(Villages.ageOf(village)));
            case "all" -> parts.addAll(CATEGORY.values());
            default -> {
                Object[][] c = CATEGORY.get(what);
                if (c != null) parts.add(c);
            }
        }
        for (Object[][] rows : parts) for (Object[] r : rows) out.merge((Item) r[0], (Integer) r[1], Integer::sum);
        return out;
    }

    /** The tool material folk carry in each age. */
    private static String toolMaterial(Villages.Age age) {
        return switch (age) {
            case WOOD, STONE -> "stone";
            case IRON -> "iron";
            case DIAMOND -> "diamond";
            case NETHER -> "netherite";
        };
    }

    private static final List<String> MATERIALS = List.of("wooden", "stone", "golden", "iron", "diamond", "netherite");

    /**
     * Set the town's age: kept and told (Villages.decreeAge), so the trades, research and building rules that read the
     * age open; the stores stocked with the age's goods; the folk's tools brought up to it. Renovation is a separate
     * step (instant or left to the builders) because it is the big part.
     */
    public static Done setAge(ServerLevel level, Villages.Village v, Villages.Age to) {
        Villages.Age was = Villages.ageOf(v.id());
        Villages.decreeAge(level, v.id(), to);
        int put = fill(level, v, fillList(v.id(), "age"));
        int tools = toolsForAge(level, v, to);
        return Done.ok(Villages.name(v.id()) + ": " + was.label + " -> " + to.label + "; " + put + " goods into the stores, " + tools
            + " tools brought up to " + toolMaterial(to), "the town was brought into " + to.label);
    }

    /** Every grown folk's axe, pickaxe and sword (and a farmer's hoe) brought up to the age's material, the old ones to the stores. */
    static int toolsForAge(ServerLevel level, Villages.Village v, Villages.Age age) {
        String mat = toolMaterial(age);
        int given = 0;
        for (VillageFolkEntity f : folk(v.id())) {
            if (f.isBaby()) continue;
            List<String> kinds = new ArrayList<>(List.of("axe", "pickaxe", "sword"));
            if (f.stationTask() == StationTask.FARM) kinds.add("hoe");
            for (String kind : kinds) {
                Item want = item(mat + "_" + kind);
                if (want == null || has(f, want)) continue;
                retire(level, v, f, kind, mat);
                f.insertGiven(new ItemStack(want));
                given++;
            }
        }
        return given;
    }

    private static boolean has(VillageFolkEntity f, Item it) {
        for (ItemStack s : f.getInventoryItems()) if (s.is(it)) return true;
        return f.getMainHandItem().is(it);
    }

    /** A folk's lesser tools of this kind taken off it and put back in the stores. */
    private static void retire(ServerLevel level, Villages.Village v, VillageFolkEntity f, String kind, String better) {
        int rank = MATERIALS.indexOf(better);
        NonNullList<ItemStack> inv = f.getInventoryItems();
        for (int i = 0; i < inv.size(); i++) {
            ItemStack s = inv.get(i);
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(s.getItem());
            if (s.isEmpty() || !id.getPath().endsWith("_" + kind)) continue;
            int r = MATERIALS.indexOf(id.getPath().substring(0, id.getPath().length() - kind.length() - 1));
            if (r < 0 || r >= rank) continue;
            ItemStack off = s.copy();
            inv.set(i, ItemStack.EMPTY);
            TownWork.give(level, v, off);
        }
    }

    /** The age after this one, or null at the last. */
    @Nullable
    public static Villages.Age next(Villages.Age a) {
        Villages.Age n = a.next();
        return n == a ? null : n;
    }

    /** What the town still needs before its next age, a line each. */
    public static List<String> needs(ServerLevel level, Villages.Village v) {
        List<String> out = new ArrayList<>();
        for (Villages.Need n : Villages.needs(level, v.id())) out.add(n.what() + (n.task() == Villages.Task.BUILD ? "" : " (" + n.amount() + " more)"));
        return out;
    }

    /** The goods an age's need of this kind is met with. */
    private static Item goodsFor(Villages.Task t) {
        return switch (t) {
            case FOOD -> Items.BREAD;
            case LOGS -> Items.OAK_LOG;
            case STONE -> Items.COBBLESTONE;
            case COAL -> Items.COAL;
            case IRON -> Items.IRON_INGOT;
            case DIAMOND -> Items.DIAMOND;
            case OBSIDIAN -> Items.OBSIDIAN;
            default -> Items.AIR;
        };
    }

    /** Meet the goods the next age needs, into the stores; returns the buildings it still wants (built as a job). */
    public static List<String> meetGoods(ServerLevel level, Villages.Village v, List<String> said) {
        List<String> builds = new ArrayList<>();
        Map<Item, Integer> goods = new LinkedHashMap<>();
        for (Villages.Need n : Villages.needs(level, v.id())) {
            if (n.task() == Villages.Task.BUILD) {
                String p = projectFor(n.what());
                if (p != null) builds.add(p);
            } else if (goodsFor(n.task()) != Items.AIR) {
                goods.merge(goodsFor(n.task()), n.amount(), Integer::sum);
            }
        }
        int put = fill(level, v, goods);
        Villages.forgetStock();
        said.add(put + " goods into the stores");
        return builds;
    }

    /** The project a need's words mean ("a meeting hall" -> hall), from the town's own list of wants. */
    @Nullable
    private static String projectFor(String what) {
        Map<String, String> words = Map.of("somewhere to store things", "storage", "a shelter", "shelter", "houses", "house",
            "more houses", "house", "a well", "well", "a wall around the village", "fortify", "a meeting hall", "hall",
            "a smeltery", "smeltery", "a gateway", "gateway");
        String p = words.get(what);
        if (p != null) return p;
        String w = what.startsWith("a ") ? what.substring(2) : what;
        return BuildGoal.footprint(w) != null && com.jrpetty.mcassistant.entity.goal.Blueprints.has(w) ? w : null;
    }

    // ------------------------------------------------------------------ the stores

    /** Put these goods into the stores (a marked chest set down by the heart when they run out of room). How many went in. */
    public static int fill(ServerLevel level, Villages.Village v, Map<Item, Integer> goods) {
        int put = 0, extraChests = 0;
        for (Map.Entry<Item, Integer> e : goods.entrySet()) {
            int left = e.getValue();
            int max = e.getKey().getDefaultMaxStackSize();
            while (left > 0) {
                ItemStack s = new ItemStack(e.getKey(), Math.min(max, left));
                int n = s.getCount();
                TownWork.give(level, v, s);
                if (!s.isEmpty()) {
                    if (extraChests >= MOST_CHESTS || !storeChest(level, v)) return put + (n - s.getCount());
                    extraChests++;
                    TownWork.give(level, v, s);
                    if (!s.isEmpty()) return put + (n - s.getCount());
                }
                put += n;
                left -= n;
            }
        }
        Villages.forgetStock();
        return put;
    }

    /** At most this many chests set down by one fill (the rest is refused, not dropped). */
    private static final int MOST_CHESTS = 12;

    /** A marked store chest set down on free ground near the heart; false when there is none. */
    private static boolean storeChest(ServerLevel level, Villages.Village v) {
        BlockPos c = v.centre();
        for (int r = 2; r <= 10; r++) {
            for (int i = 0; i < 8 * r; i++) {
                double a = i * Math.PI * 2 / (8 * r);
                int x = c.getX() + (int) Math.round(Math.cos(a) * r), z = c.getZ() + (int) Math.round(Math.sin(a) * r);
                BlockPos at = new BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z);
                if (!level.isLoaded(at) || !level.getBlockState(at).isAir() || !level.getBlockState(at.below()).isSolidRender(level, at.below())) continue;
                if (BuildGoal.isWildPlant(level.getBlockState(at))) continue;
                level.setBlock(at, Blocks.CHEST.defaultBlockState(), 3);
                ZoneChests.mark(level, at);
                return true;
            }
        }
        return false;
    }

    /** Every store chest emptied; how many items went. */
    public static int emptyStores(ServerLevel level, Villages.Village v) {
        int n = 0;
        for (BlockPos p : Villages.storeChests(level, v.id())) {
            if (level.getBlockEntity(p) instanceof Container c) {
                for (int i = 0; i < c.getContainerSize(); i++) n += c.getItem(i).getCount();
                c.clearContent();
                c.setChanged();
            }
        }
        Villages.forgetStock();
        return n;
    }

    /** How many items the store chests hold now. */
    public static int storesCount(ServerLevel level, Villages.Village v) {
        int n = 0;
        for (BlockPos p : Villages.storeChests(level, v.id())) {
            if (level.getBlockEntity(p) instanceof Container c) for (int i = 0; i < c.getContainerSize(); i++) n += c.getItem(i).getCount();
        }
        return n;
    }

    // ------------------------------------------------------------------ people

    /** Who a new folk is: an ordinary grown hand, a child, an elder. */
    public enum Kind { ADULT, CHILD, ELDER }

    private static final Map<UUID, Integer> SPIRAL = new ConcurrentHashMap<>();

    /**
     * Raise one folk into the town, as the founding party is raised (named, kitted, joining, its rent-free start if the
     * town is a day old): stood on its own ground on a spiral round the heart. A grown one takes the trade the town most
     * wants ({@code trade} null) or the one given; a child is a child of years; an elder past sixty.
     */
    @Nullable
    public static VillageFolkEntity raise(ServerLevel level, Villages.Village v, Kind kind, @Nullable StationTask trade, Random rng) {
        int i = SPIRAL.merge(v.id(), 1, Integer::sum);
        double angle = i * 2.399963229728653;
        double reach = 3.0 + 1.2 * Math.sqrt(i % 400);
        int x = v.centre().getX() + (int) Math.round(Math.cos(angle) * reach);
        int z = v.centre().getZ() + (int) Math.round(Math.sin(angle) * reach);
        if (!level.isLoaded(new BlockPos(x, 0, z))) { x = v.centre().getX(); z = v.centre().getZ(); }
        BlockPos at = new BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z);
        VillageFolkEntity f = VillageFolkSpawnerBlock.raise(level, at, rng.nextFloat() * 360F, Integer.MAX_VALUE);
        if (f == null || !v.id().equals(f.ownerId())) return f;
        switch (kind) {
            case CHILD -> {
                f.setChild(true);
                f.bornDaysAgo(rng.nextInt(3));
            }
            case ELDER -> f.setAgeForTests(VillageFolkEntity.OLD_AT + rng.nextInt(16));
            default -> f.setAgeForTests(18 + rng.nextInt(38));
        }
        if (kind != Kind.CHILD) f.setJob(trade != null ? trade : Villages.needed(v.id()));
        return f;
    }

    /** Two grown singles pledged to each other, as courting does it: the town holds the wedding (Gatherings). */
    public static void pledge(ServerLevel level, UUID village, VillageFolkEntity a, VillageFolkEntity b) {
        long day = day(level);
        a.life().partnerWith(b.getUUID(), b.displayNameCap());
        b.life().partnerWith(a.getUUID(), a.displayNameCap());
        Villages.tell(village, day, a.displayNameCap() + " and " + b.displayNameCap() + " are to be wed");
        Gatherings.pledged(village, a, b, day);
        a.persona().remember(day, "I asked " + b.displayNameCap() + " to marry me", 9);
        b.persona().remember(day, a.displayNameCap() + " asked me to marry them", 9);
    }

    /** Grown folk with no partner, in the town now. */
    public static List<VillageFolkEntity> singles(UUID village) {
        List<VillageFolkEntity> out = new ArrayList<>();
        for (VillageFolkEntity f : folk(village)) if (!f.isBaby() && f.life().partner() == null && !f.isHired()) out.add(f);
        return out;
    }

    /** Couples in the town now, each once. */
    public static List<VillageFolkEntity[]> couples(ServerLevel level, UUID village) {
        List<VillageFolkEntity[]> out = new ArrayList<>();
        for (VillageFolkEntity f : folk(village)) {
            UUID p = f.life().partner();
            if (f.isBaby() || p == null || f.getUUID().compareTo(p) > 0) continue;
            if (level.getEntity(p) instanceof VillageFolkEntity q && q.isAlive() && !q.isBaby()) out.add(new VillageFolkEntity[]{ f, q });
        }
        return out;
    }

    /** Camp beds for those with none, round the heart; how many went up. */
    public static int beds(ServerLevel level, Villages.Village v, int wanted) {
        int room = Math.max(0, wanted);
        return room == 0 ? 0 : com.jrpetty.mcassistant.VillageSpawner.pitchCamp(level, v.centre(), room);
    }

    /** Send one folk away, as one who gives up on a town leaves it: its home, its savings and its place on the roll go with it. */
    public static boolean sendAway(ServerLevel level, Villages.Village v) {
        VillageFolkEntity who = Contentment.leaver(v.id());
        if (who == null) {
            for (VillageFolkEntity f : folk(v.id())) if (!f.isBaby() && !f.getUUID().equals(Villages.elder(v.id()))) { who = f; break; }
        }
        if (who == null) return false;
        Villages.recordDeath(v.id());
        Homes.left(v.id(), who.getUUID());
        Bank.left(v.id(), who, false);
        Villages.tell(v.id(), day(level), who.displayNameCap() + " left the town");
        who.walkOut();
        return true;
    }

    /** Heal a folk: full health, its cold and its wound gone, up out of bed. */
    public static void heal(VillageFolkEntity f) {
        f.setHealth(f.getMaxHealth());
        Health.State h = f.health();
        h.cold = 0;
        h.laidUntil = 0L;
        h.laidFor = "";
        h.wounded = false;
        h.lying = false;
        h.infirmary = false;
    }

    /** Needs met as a day well lived meets them: fed now, slept in a bed last night, company now; mood worked out afresh. */
    public static void fillNeeds(VillageFolkEntity f) {
        long now = f.level().getGameTime();
        FolkNeeds.historyForTests(f, f.persona().since, day((ServerLevel) f.level()), 0, now, now);
        heal(f);
        f.refreshMood();
    }

    /** Its level at its own trade (or its best) set, as though worked for. */
    public static void skill(VillageFolkEntity f, int level) {
        StationTask t = f.stationTask() == StationTask.NONE ? FolkSkills.bestTrade(f) : f.stationTask();
        if (t == null || t == StationTask.NONE) return;
        f.tradeXpForTests(t, AssistantEntity.xpForLevel(Math.max(0, Math.min(50, level))));
    }

    /** A child grown up now (its years run on to eighteen). */
    public static boolean growUp(VillageFolkEntity f) {
        if (!f.isBaby()) return false;
        f.childhoodForTests(VillageFolkEntity.CHILD_YEARS_A_DAY > 0 ? 18 / VillageFolkEntity.CHILD_YEARS_A_DAY + 1 : 4);
        return !f.isBaby();
    }

    // ------------------------------------------------------------------ army

    /** The armour, weapon and extras of each tier. */
    private static final Map<String, Item[]> TIER = new LinkedHashMap<>();

    static {
        TIER.put("leather", new Item[]{ Items.LEATHER_HELMET, Items.LEATHER_CHESTPLATE, Items.LEATHER_LEGGINGS, Items.LEATHER_BOOTS, Items.STONE_SWORD });
        TIER.put("chain", new Item[]{ Items.CHAINMAIL_HELMET, Items.CHAINMAIL_CHESTPLATE, Items.CHAINMAIL_LEGGINGS, Items.CHAINMAIL_BOOTS, Items.IRON_SWORD });
        TIER.put("iron", new Item[]{ Items.IRON_HELMET, Items.IRON_CHESTPLATE, Items.IRON_LEGGINGS, Items.IRON_BOOTS, Items.IRON_SWORD });
        TIER.put("diamond", new Item[]{ Items.DIAMOND_HELMET, Items.DIAMOND_CHESTPLATE, Items.DIAMOND_LEGGINGS, Items.DIAMOND_BOOTS, Items.DIAMOND_SWORD });
        TIER.put("netherite", new Item[]{ Items.NETHERITE_HELMET, Items.NETHERITE_CHESTPLATE, Items.NETHERITE_LEGGINGS, Items.NETHERITE_BOOTS,
            Items.NETHERITE_SWORD });
    }

    private static final EquipmentSlot[] KIT_SLOTS = { EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET, EquipmentSlot.MAINHAND };

    /** Every n-th fighter carries a missile (a bow, or from iron up a crossbow as the town's Captain likes, with arrows), a trident from diamond up. */
    private static final int BOW_EVERY = 4, TRIDENT_EVERY = 10, KIT_ARROWS = 32;

    /** How a kit went: pieces out of the stores, and pieces made by decree. */
    public record Kit(int fighters, int fromStores, int byDecree) {
        /** "3 pieces from the stores, 17 by decree supply". */
        public String words() {
            return fromStores + (fromStores == 1 ? " piece" : " pieces") + " from the stores, " + byDecree + " by decree supply";
        }
    }

    /** The town's watch. */
    public static List<VillageFolkEntity> guards(UUID village) {
        List<VillageFolkEntity> out = new ArrayList<>();
        for (VillageFolkEntity f : folk(village)) if (!f.isBaby() && f.stationTask() == StationTask.GUARD) out.add(f);
        return out;
    }

    /**
     * Arm these fighters at a tier: each piece out of the stores where they hold one (stores), else made by decree
     * (decree, or what the stores could not find). Shields with the town's arms, bows, crossbows, tridents and a
     * healing potion each from the iron tier.
     */
    public static Kit kit(ServerLevel level, Villages.Village v, List<VillageFolkEntity> fighters, String tier, boolean storesFirst) {
        Item[] set = TIER.getOrDefault(tier, TIER.get("iron"));
        int rank = new ArrayList<>(TIER.keySet()).indexOf(tier);
        int stores = 0, decree = 0, n = 0, missiles = 0;
        com.jrpetty.mcassistant.village.BattleModel.Ranged ranged = Armies.rangedFor(v.id(), null);
        for (VillageFolkEntity f : fighters) {
            for (int s = 0; s < KIT_SLOTS.length; s++) {
                Item piece = set[s];
                ItemStack got = storesFirst ? Crafts.takeOne(level, v, st -> st.is(piece)) : ItemStack.EMPTY;
                if (got.isEmpty()) { got = new ItemStack(piece); decree++; } else stores++;
                ItemStack old = f.getItemBySlot(KIT_SLOTS[s]);
                if (!old.isEmpty() && !old.is(got.getItem())) TownWork.give(level, v, old.copy());
                f.setItemSlot(KIT_SLOTS[s], got);
            }
            n++;
            if (n % BOW_EVERY == 0) {
                // [war-bows] The missile arm: bows, and from iron up crossbows as the town's Captain likes; arrows with them.
                Item missile = rank >= 2 && ranged.crossbowFor(missiles++) ? Items.CROSSBOW : Items.BOW;
                giveKit(level, v, f, missile, 1, storesFirst);             // (the pieces counted are the armour and blades)
                giveKit(level, v, f, Items.ARROW, KIT_ARROWS, storesFirst);
            } else {
                ItemStack shield = new ItemStack(Items.SHIELD);
                Heraldry.Design d = Heraldry.design(v.id());
                if (d != null) Arms.emblazon(shield, Heraldry.item(level, v.id(), d));
                f.setItemSlot(EquipmentSlot.OFFHAND, shield);
            }
            if (rank >= 3 && n % TRIDENT_EVERY == 0) f.insertGiven(new ItemStack(Items.TRIDENT));
            if (rank >= 2) f.insertGiven(PotionContents.createItemStack(Items.POTION, Potions.HEALING));
            f.setHealth(f.getMaxHealth());
        }
        return new Kit(n, stores, decree);
    }

    /** [war-bows] So many of a thing into a fighter's pack: out of the stores where they hold them, else by decree. Whether the stores gave them. */
    private static boolean giveKit(ServerLevel level, Villages.Village v, VillageFolkEntity f, Item what, int n, boolean storesFirst) {
        boolean stored = storesFirst && TownWork.take(level, v, s -> s.is(what), n);
        ItemStack left = f.insertGiven(new ItemStack(what, n));
        if (!left.isEmpty()) TownWork.give(level, v, left);
        return stored;
    }

    /** [war-bows] The town's missile arm by decree: bows, crossbows or a mix (Ledger "war.ranged"; Armies.rangedFor). */
    public static Done ranged(Villages.Village v, String word) {
        com.jrpetty.mcassistant.village.BattleModel.Ranged r = com.jrpetty.mcassistant.village.BattleModel.Ranged.parse(word);
        if (r == null) return Done.no("Bows, crossbows or mix.");
        Ledger.note(v.id(), "war.ranged", r.name());
        return Done.ok(Villages.name(v.id()) + "'s army will carry " + r.words, "the army was ordered to carry " + r.words);
    }

    /** Put a grown folk on the watch (one not already on it, not the leader); null when the town has none to spare. */
    @Nullable
    public static VillageFolkEntity enlist(Villages.Village v) {
        UUID elder = Villages.elder(v.id());
        for (VillageFolkEntity f : folk(v.id())) {
            if (f.isBaby() || f.isHired() || f.stationTask() == StationTask.GUARD || f.getUUID().equals(elder)) continue;
            f.setJob(StationTask.GUARD);
            return f;
        }
        return null;
    }

    // ------------------------------------------------------------------ [field] open ground for a scenario's towns

    /** The rival founded for a town (Ledger note on each, the other's id): who "army war rival" means. */
    static final String RIVAL = "admin.rival";
    /** The biomes a battle scenario founds its towns in: open grassland (and the savannas, BiomeTags.IS_SAVANNA). */
    static final java.util.Set<net.minecraft.resources.ResourceKey<net.minecraft.world.level.biome.Biome>> OPEN_BIOMES = java.util.Set.of(
        net.minecraft.world.level.biome.Biomes.PLAINS, net.minecraft.world.level.biome.Biomes.SUNFLOWER_PLAINS, net.minecraft.world.level.biome.Biomes.MEADOW);
    /** How far apart the rings and bearings an open site is looked for on stand (blocks, and sixteen bearings). */
    static final int SITE_RING = 32, SITE_BEARINGS = 16;
    /** At most so many spots in open country have their ground made and looked over (each a few chunks). */
    static final int SITE_CHECKS = 8;
    /** A town's heart wants ground this level: its height within so many blocks over 32 by 32. */
    static final int SITE_ROUGH = 4;

    /** The rival an admin founded for this town, if it still stands in the same world. */
    @Nullable
    static Villages.Village rivalOf(Villages.Village v) {
        String id = Ledger.note(v.id(), RIVAL);
        if (id == null || id.isEmpty()) return null;
        try {
            Villages.Village o = Villages.get(UUID.fromString(id));
            return o != null && o.dim().equals(v.dim()) ? o : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Two towns paired as rivals by an admin (a scenario's): each the other's foe for "army war rival". */
    public static void pairRivals(UUID a, UUID b) {
        Ledger.note(a, RIVAL, b.toString());
        Ledger.note(b, RIVAL, a.toString());
    }

    /**
     * Open ground to found a town on, between {@code nearest} and {@code farthest} blocks of {@code from}: open grassland
     * or savanna by its biome (read without making the ground), clear of every town by two towns' range, level enough
     * for a heart; nearest {@code nearest} first, east first (the way a rival is raised). Null if none is found.
     */
    @Nullable
    public static BlockPos openSite(ServerLevel level, BlockPos from, int nearest, int farthest) {
        List<BlockPos> open = new ArrayList<>();
        for (int r = nearest; r <= farthest; r += SITE_RING) {
            for (int k = 0; k < SITE_BEARINGS; k++) {
                if (r == 0 && k > 0) break;                                                // the spot itself, once
                int turn = (k + 1) / 2 * (k % 2 == 0 ? 1 : -1);                           // 0, -1, 1, -2, 2...: east, then either side of it
                double ang = Math.PI * 2 * turn / SITE_BEARINGS;
                BlockPos p = BlockPos.containing(from.getX() + Math.cos(ang) * r, from.getY(), from.getZ() + Math.sin(ang) * r);
                var biome = level.getBiome(p);
                if (!(biome.is(net.minecraft.tags.BiomeTags.IS_SAVANNA) || OPEN_BIOMES.stream().anyMatch(biome::is))) continue;
                if (Villages.nearest(level, p, Villages.VILLAGE_RANGE * 2) != null) continue;
                open.add(p);
            }
        }
        int checks = 0;
        for (BlockPos p : open) {
            if (checks++ >= SITE_CHECKS) break;
            if (level(level, p)) return p;
        }
        return null;
    }

    /** Is the ground round this spot level enough for a town's heart (its chunks made to look)? Water at the heart will not do. */
    private static boolean level(ServerLevel level, BlockPos p) {
        int lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE;
        for (int dx = -16; dx <= 16; dx += 8) {
            for (int dz = -16; dz <= 16; dz += 8) {
                int x = p.getX() + dx, z = p.getZ() + dz;
                level.getChunk(x >> 4, z >> 4);
                int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                if (!level.getBlockState(new BlockPos(x, y - 1, z)).getFluidState().isEmpty()) return false;
                lo = Math.min(lo, y);
                hi = Math.max(hi, y);
            }
        }
        return hi - lo <= SITE_ROUGH;
    }

    /** Declare war between the two, a quarrel over the land first (as the war's own pictures do). */
    public static Done war(ServerLevel level, Villages.Village a, Villages.Village b) {
        if (a.id().equals(b.id())) return Done.no("A town cannot go to war with itself.");
        if (Wars.atWar(a.id(), b.id())) return Done.said(Villages.name(a.id()) + " and " + Villages.name(b.id()) + " are at war already.");
        long day = day(level);
        int r = Ledger.relation(a.id(), b.id());
        if (r > -70) Ledger.relate(a.id(), b.id(), -70 - r);
        WarAndPeace.wrongForTests(a.id(), b.id(), day, WarAndPeace.Wrong.LAND, "a decree laid claim to their land");
        boolean done = WarAndPeace.declare(level, a.id(), b.id(), day, WarAndPeace.Goal.BORDER, 0, "a border where we say it runs, by decree");
        return done ? Done.ok(Villages.name(a.id()) + " declared war on " + Villages.name(b.id()), "war was declared on " + Villages.name(b.id()))
            : Done.no("No war: wars are switched off on this server (villageWars), or the two cannot fight.");
    }

    /** The other town a war action means: its enemy, else the rival founded for it (world rival), else its nearest neighbour in the same world. */
    @Nullable
    public static Villages.Village foeOf(Villages.Village v) {
        for (UUID e : Wars.enemies(v.id())) {
            Villages.Village o = Villages.get(e);
            if (o != null) return o;
        }
        Villages.Village rival = rivalOf(v);
        if (rival != null) return rival;
        Villages.Village best = null;
        double d = Double.MAX_VALUE;
        for (Villages.Village o : Villages.every()) {
            if (o.id().equals(v.id()) || !o.dim().equals(v.dim())) continue;
            double x = o.centre().distSqr(v.centre());
            if (x < d) { d = x; best = o; }
        }
        return best;
    }

    /** A battle staged at a phase (the war's own stage), or a surrender or a peace made. */
    public static Done phase(ServerLevel level, Villages.Village v, String phase) {
        Villages.Village o = foeOf(v);
        long day = day(level);
        if (phase.equals("peace") || phase.equals("surrender")) {
            if (o == null || !Wars.atWar(v.id(), o.id())) return Done.no(Villages.name(v.id()) + " is at war with nobody.");
            Campaigns.unstage(level, v.id());
            UUID winner = phase.equals("surrender") ? o.id() : v.id(), loser = phase.equals("surrender") ? v.id() : o.id();
            String text = WarAndPeace.makePeace(level, winner, loser, day, WarAndPeace.terms(level, winner, loser),
                phase.equals("surrender") ? "by surrender, by decree" : "by decree", null);
            return Done.ok("Peace between " + Villages.name(v.id()) + " and " + Villages.name(o.id()) + ": " + text,
                phase.equals("surrender") ? "the town surrendered to " + Villages.name(o.id()) : "peace was made with " + Villages.name(o.id()));
        }
        List<String> out = Campaigns.stage(level, v, null, phase);               // [field] on the field its Captains choose
        boolean ok = !out.isEmpty() && !out.get(0).startsWith("NO-");
        return ok ? new Done(true, String.join("\n", out), "the armies were brought to the " + phase) : Done.no(out.isEmpty() ? "No battle." : out.get(0));
    }

    public static Done vassal(ServerLevel level, Villages.Village v, Villages.Village overlord) {
        if (v.id().equals(overlord.id())) return Done.no("A town cannot be its own overlord.");
        String said = Vassals.make(level, overlord.id(), v.id(), day(level), "by decree");
        return Vassals.isVassal(v.id()) ? Done.ok(said, "the town became a vassal of " + Villages.name(overlord.id())) : Done.no(said);
    }

    public static Done free(ServerLevel level, Villages.Village v) {
        if (!Vassals.isVassal(v.id())) return Done.no(Villages.name(v.id()) + " is nobody's vassal.");
        Vassals.free(level, v.id(), day(level), "by decree");
        return Done.ok(Villages.name(v.id()) + " is free", "the town was freed of its overlord");
    }

    public static Done raid(ServerLevel level, Villages.Village v, int size) {
        RAID_SIZE.put(v.id(), size);
        Raids.Alarm a = Raids.raidNow(level, v);
        RAID_SIZE.remove(v.id());
        return a == null ? Done.no("No band could be raised (the town's edge not loaded?).")
            : Done.ok("A band of " + Raids.bandSize(v.id()) + " at " + Villages.name(v.id()) + "'s gate", "raiders were sent against the town");
    }

    /** Every war ended, the battle struck, the raid's alarm called off. */
    public static Done peace(ServerLevel level, Villages.Village v) {
        long day = day(level);
        int wars = 0;
        Campaigns.unstage(level, v.id());
        for (UUID e : new ArrayList<>(Wars.enemies(v.id()))) {
            WarAndPeace.makePeace(level, v.id(), e, day, WarAndPeace.terms(level, v.id(), e), "by decree", null);
            wars++;
        }
        boolean raid = Raids.underAlarm(v.id());
        if (raid) {
            for (UUID m : Raids.band(v.id())) if (level.getEntity(m) != null) level.getEntity(m).discard();
            Raids.allClear(level, v);
        }
        if (wars == 0 && !raid) return Done.said(Villages.name(v.id()) + " was not fighting.");
        return Done.ok(Villages.name(v.id()) + ": " + wars + " war(s) ended" + (raid ? ", the raid called off" : ""), "all fighting was ended");
    }

    // ------------------------------------------------------------------ economy

    public static Done marketDay(ServerLevel level, Villages.Village v) {
        long day = day(level);
        MARKET_DAY.put(v.id(), day);
        int coin = Market.sellSurplus(level, v, day);
        return Done.ok("Market day in " + Villages.name(v.id()) + ": the traders paid " + coin + " coin for the surplus",
            "today was made market day");
    }

    /** A price shock: an item at so many percent of its usual price, as the morning's reckoning would set it. */
    public static Done price(ServerLevel level, Villages.Village v, Item item, int percent) {
        PriceIndex.setForTests(v.id(), new ItemStack(item), percent / 100.0);
        String word = percent < 100 ? "a glut" : percent > 100 ? "a shortage" : "its usual price";
        return Done.ok(BuiltInRegistries.ITEM.getKey(item).getPath() + " at " + percent + "% (" + word + ") in " + Villages.name(v.id())
            + " until the morning's prices", BuiltInRegistries.ITEM.getKey(item).getPath() + " was priced at " + percent + "% (" + word + ")");
    }

    /** A wagon of the town's commonest surplus (up to three stacks) to its nearest neighbour. */
    public static Done wagon(ServerLevel level, Villages.Village v) {
        Villages.Village to = null;
        double best = Double.MAX_VALUE;
        for (Villages.Village o : Villages.every()) {
            if (o.id().equals(v.id()) || !o.dim().equals(v.dim())) continue;
            double d = o.centre().distSqr(v.centre());
            if (d < best) { best = d; to = o; }
        }
        if (to == null) return Done.no(Villages.name(v.id()) + " has no neighbour to send to.");
        if (!Shipments.hasCarrier()) return Done.no("No wagons on this server to carry it.");
        List<ItemStack> load = new ArrayList<>();
        for (Item it : new Item[]{ Items.OAK_LOG, Items.COBBLESTONE, Items.WHEAT, Items.BREAD, Items.IRON_INGOT }) {
            if (load.size() >= 3) break;
            if (TownWork.take(level, v, s -> s.is(it), 64)) load.add(new ItemStack(it, 64));
        }
        if (load.isEmpty()) return Done.no(Villages.name(v.id()) + "'s stores have no stack of surplus to send.");
        Shipments.Shipment s = Shipments.dispatch(level, v.id(), to.id(), "by decree", load, 0);
        if (s == null) {
            for (ItemStack back : load) TownWork.give(level, v, back);
            return Done.no("The shipment could not set out.");
        }
        return Done.ok("A wagon left " + Villages.name(v.id()) + " for " + Villages.name(to.id()) + ": " + Shipments.words(s),
            "a wagon was sent to " + Villages.name(to.id()));
    }

    /** The shops' and the café's counters dressed afresh from the stores (their tills keep their books). */
    public static Done restock(ServerLevel level, Villages.Village v) {
        Cafe.dress(level, v, "shop");
        Cafe.dress(level, v, "cafe");
        return Done.ok("The shop's and the café's counters restocked from the stores of " + Villages.name(v.id()), "the shops were restocked");
    }

    // ------------------------------------------------------------------ build

    /**
     * Put a building up now on its own site, to the town's current design and look (the renovation engine lays it out
     * against what stands there): for nothing ({@code paid} false), or out of the stores block by block. Noted as built
     * the way a finished project is (the register, the news, the evening's opening). Returns the blocks laid.
     */
    public static Done buildProject(ServerLevel level, Villages.Village v, String project, boolean paid) {
        if (!com.jrpetty.mcassistant.entity.goal.Blueprints.has(project) && !project.equals("fortify")) return Done.no("No design called " + project + ".");
        Villages.Site site = Villages.siteFor(level, v.id(), project);
        if (site == null) return Done.no("No site for " + project + " in " + Villages.name(v.id()) + " yet (no free lot).");
        int n = raise(level, v, project, site.anchor(), site.facing(), paid);
        Villages.noteProject(v.id(), project, level.getGameTime());
        return Done.ok(Villages.spoken(project) + " put up in " + Villages.name(v.id()) + " (" + n + " blocks" + (paid ? ", out of the stores" : "") + ")",
            Villages.spoken(project) + " was put up");
    }

    /** A building on the register at this spot, laid out now; how many blocks. */
    static int raise(ServerLevel level, Villages.Village v, String structure, BlockPos anchor, Direction facing, boolean paid) {
        int radius = Math.max(4, Math.min(16, com.jrpetty.mcassistant.entity.goal.Blueprints.has(structure) ? BuildGoal.footprint(structure)[0] + 2 : 8));
        BuildGoal.clearWildPlants(level, structure, anchor, facing, radius);
        Ledger.built(v.id(), structure, anchor, facing);
        Ledger.Building b = null;
        for (Ledger.Building x : Ledger.buildings(v.id())) if (x.anchor().equals(anchor) && x.structure().equals(structure)) b = x;
        int n = 0;
        if (b != null) {
            if (!paid) n = Renovation.renovateNow(level, v, b);
            else {
                boolean was = TownJobs.instantNow();
                TownJobs.instantForTests(true);
                try {
                    for (int i = 0; i < 64; i++) {
                        int k = Renovation.renovate(level, v, b, 256, false);
                        if (k <= 0) break;
                        n += k;
                    }
                } finally {
                    TownJobs.instantForTests(was);
                }
            }
        }
        if (n == 0 && !paid) n = BuildGoal.stamp(level, structure, anchor, facing, radius, com.jrpetty.mcassistant.Showcase.painter(com.jrpetty.mcassistant.Showcase.OAK));
        return n;
    }

    /** The projects under way now (their sites chosen, not yet standing). */
    public static List<String> underWay(UUID village) {
        return new ArrayList<>(Villages.sitesOf(village).keySet());
    }

    /** The next project the town wants, or null. */
    @Nullable
    public static String nextProject(UUID village) {
        return Villages.nextProject(village);
    }

    /** A building put up where an admin looks, joining the town's register. */
    public static Done place(ServerLevel level, Villages.Village v, String structure, BlockPos anchor, Direction facing) {
        if (!com.jrpetty.mcassistant.entity.goal.Blueprints.has(structure)) return Done.no("No design called " + structure + ".");
        int n = raise(level, v, structure, anchor, facing, false);
        Villages.tell(v.id(), day(level), Villages.spoken(structure) + " went up");
        return Done.ok(Villages.spoken(structure) + " placed at " + anchor.toShortString() + " (" + n + " blocks)", Villages.spoken(structure) + " was placed");
    }

    public static Done walls(ServerLevel level, Villages.Village v) {
        int n = CastleWall.buildNow(level, v, true);
        return n > 0 ? Done.ok("The wall and gates of " + Villages.name(v.id()) + ": " + n + " blocks", "the town wall was built")
            : Done.said("No wall to build in " + Villages.name(v.id()) + " (walls off, or it stands already).");
    }

    /** One building renovated to the town's current design now, for nothing; how many blocks. */
    public static int renovate(ServerLevel level, Villages.Village v, Ledger.Building b) {
        int n = Renovation.renovateNow(level, v, b);
        Ages.now(level, v, b, Villages.ageOf(v.id()));
        return n;
    }

    /** The builders' crews reset and every folk with nowhere to go sent back to the square. */
    public static Done unstick(ServerLevel level, Villages.Village v) {
        int sent = 0;
        for (VillageFolkEntity f : folk(v.id())) {
            if (f.getNavigation().isDone() || !f.getNavigation().isStuck()) continue;
            f.getNavigation().stop();
            BlockPos c = v.centre();
            f.teleportTo(c.getX() + 0.5, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, c.getX(), c.getZ()), c.getZ() + 0.5);
            sent++;
        }
        for (String p : underWay(v.id())) Villages.crewDone(v.id(), p);
        return Done.ok(sent + " stuck folk sent back to the square, the crews of " + underWay(v.id()).size() + " project(s) reset",
            "the builders were set to rights");
    }

    // ------------------------------------------------------------------ events

    public static Done event(ServerLevel level, Villages.Village v, String what, String detail, Random rng) {
        long day = day(level);
        String name = Villages.name(v.id());
        switch (what) {
            case "festival" -> {
                Festivals.Feast f = Festivals.Feast.BONFIRE;
                for (Festivals.Feast x : Festivals.Feast.values()) if (x.name().equalsIgnoreCase(detail)) f = x;
                boolean on = Festivals.callNow(level, v, f);
                return on ? Done.ok(f.words + " called in " + name, f.words + " was called") : Done.no(f.words + " could not be called now.");
            }
            case "wedding" -> {
                List<VillageFolkEntity> s = singles(v.id());
                if (s.size() < 2) return Done.no(name + " has no two singles to wed.");
                pledge(level, v.id(), s.get(0), s.get(1));
                return Done.ok(s.get(0).displayNameCap() + " and " + s.get(1).displayNameCap() + " are to be wed", "a wedding was arranged");
            }
            case "birth" -> {
                List<VillageFolkEntity[]> c = couples(level, v.id());
                if (c.isEmpty()) return Done.no(name + " has no couple.");
                VillageFolkEntity child = c.get(0)[0].raiseChildWith(c.get(0)[1]);
                return child == null ? Done.no("No child could be born.") : Done.ok(child.displayNameCap() + " was born", "a child was born");
            }
            case "funeral" -> {
                List<Ledger.Grave> g = Ledger.graves(v.id());
                if (g.isEmpty()) return Done.no(name + " has nobody to mourn (people kill first).");
                String who = g.get(g.size() - 1).name();
                Gatherings.mourn(v.id(), who, day);
                return Done.ok("The town gathers to mourn " + who + " tonight", "a funeral was held for " + who);
            }
            case "election" -> {
                int n = Elections.callForTests(level, v, day);
                return Done.ok("An election called in " + name + ", " + n + " standing, the vote today", "an election was called");
            }
            case "fire" -> { return disaster(DisasterStage.fireNow(level, v), "a fire broke out"); }
            case "flood" -> { return disaster(DisasterStage.floodNow(level, v, 1), "the river flooded"); }
            case "drought" -> { return disaster(DisasterStage.droughtNow(level, v, true), "a drought set in"); }
            case "illness" -> {
                Health.outbreakForTests(level, v.id());
                return Done.ok("Colds going round " + name, "an illness went round the town");
            }
            case "envoy", "feud", "caravan" -> {
                Villages.Village o = foeOf(v);
                if (o == null) return Done.no(name + " has no neighbour.");
                if (what.equals("feud")) {
                    int r = Ledger.relation(v.id(), o.id());
                    if (r > -40) Ledger.relate(v.id(), o.id(), -40 - r);
                    WarAndPeace.wrongForTests(v.id(), o.id(), day, WarAndPeace.Wrong.LAND, "a boundary stone was moved, by decree");
                    return Done.ok(name + " and " + Villages.name(o.id()) + " are at odds", "a feud began with " + Villages.name(o.id()));
                }
                if (what.equals("caravan")) return wagon(level, v);
                Envoys.Errand e = Envoys.choose(level, v, o, day, new java.util.Random(rng.nextLong()));
                if (e == null) e = Envoys.Errand.GREETING;
                boolean sent = Envoys.send(level, v, o, e, day);
                return sent ? Done.ok("An envoy set out for " + Villages.name(o.id()) + " " + e.purpose, "an envoy was sent to " + Villages.name(o.id()))
                    : Done.no("No envoy could be sent.");
            }
            case "visitor" -> {
                String kind = detail.isEmpty() ? "merchant" : detail;
                List<String> out = Visitors.command(level, v, kind);
                return Done.ok(out.isEmpty() ? "nobody came" : out.get(0), "a visitor came (" + kind + ")");
            }
            case "refugees" -> {
                int n = 2;
                try { if (!detail.isEmpty()) n = Math.max(1, Math.min(3, Integer.parseInt(detail))); } catch (NumberFormatException ignored) { }
                String party = Newcomers.outsideForTests(level, v, n);
                return party == null ? Done.no("No party could come.") : Done.ok("A party of " + n + " household(s) is on its way to " + name + " (" + party + ")",
                    "refugees were sent to the town");
            }
            default -> { return Done.no("No event called " + what + "."); }
        }
    }

    private static Done disaster(String said, String decree) {
        boolean ok = said != null && !said.toLowerCase(Locale.ROOT).startsWith("no");
        return ok ? Done.ok(said, decree) : Done.no(said == null ? "Nothing happened." : said);
    }

    // ------------------------------------------------------------------ world

    /** The town's calendar turned to the first day of a season. */
    public static Done season(ServerLevel level, Villages.Village v, String season) {
        Seasons.Season s = Seasons.Season.valueOf(season.toUpperCase(Locale.ROOT));
        Festivals.turnTo(v.id(), day(level), s.ordinal() * Seasons.DAYS);
        return Done.ok(Villages.name(v.id()) + "'s calendar turned to " + s.word, "the calendar was turned to " + s.word);
    }

    /**
     * One day of the time-warp: the clock moved to the next morning, and every town's morning round (the books, prices,
     * the leader's plan, research, wages, rent, the bank, the market) and its afternoon's business run for it, as the
     * town's own round would on that day. Folk catch up on their own years and needs from the clock as they tick.
     */
    public static void warpDay(net.minecraft.server.MinecraftServer server) {
        ServerLevel over = server.overworld();
        long next = (over.getDayTime() / 24000L + 1) * 24000L;
        for (ServerLevel l : server.getAllLevels()) l.setDayTime(next + MORNING);
        for (Villages.Village v : Villages.every()) {
            ServerLevel l = server.getLevel(v.dim());
            if (l == null) continue;
            com.jrpetty.mcassistant.Guard.run("admin-warp-morning", () -> Market.tick(l, v));
        }
        for (ServerLevel l : server.getAllLevels()) l.setDayTime(next + AFTERNOON);
        for (Villages.Village v : Villages.every()) {
            ServerLevel l = server.getLevel(v.dim());
            if (l == null) continue;
            com.jrpetty.mcassistant.Guard.run("admin-warp-day", () -> {
                Commerce.daily(l, v);
                Contentment.daily(l, v);
            });
        }
    }

    /** The morning (the market's round runs from 500 to 6000) and the afternoon (the business, 6000 to 11000) of a warped day. */
    private static final long MORNING = 1000L, AFTERNOON = 7000L;

    /** The chests the town's workers keep as their own (as positions), for the debug overlay. */
    public static java.util.Set<Long> workChests(UUID village) {
        return VillageFolkEntity.chestsInUse(village);
    }

    // ------------------------------------------------------------------ inspect

    /** The town at a glance, a line each. */
    public static List<String> inspect(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        List<String> out = new ArrayList<>();
        Villages.Age age = Villages.ageOf(id);
        List<String> needs = needs(level, v);
        out.add("TOWN " + Villages.name(id) + " (" + id.toString().substring(0, 8) + ") at " + v.centre().toShortString() + ", " + age.label
            + (next(age) == null ? "" : "; for " + next(age).label + " it needs " + needs.size() + " thing(s)"));
        if (!needs.isEmpty()) out.add("NEEDS " + String.join("; ", needs.subList(0, Math.min(6, needs.size()))));
        List<VillageFolkEntity> all = folk(id);
        Map<String, Integer> trades = new java.util.TreeMap<>();
        int children = 0, elders = 0, couples = 0;
        for (VillageFolkEntity f : all) {
            if (f.isBaby()) { children++; continue; }
            if (f.ageYears() >= VillageFolkEntity.OLD_AT) elders++;
            if (f.life().partner() != null) couples++;
            trades.merge(f.stationTask().title, 1, Integer::sum);
        }
        out.add("PEOPLE " + Villages.headcount(id) + " on the roll, " + all.size() + " loaded; " + children + " children, " + elders + " elders, "
            + couples / 2 + " couples; leader " + (Villages.elderName(id).isEmpty() ? "none" : Villages.elderName(id)));
        StringBuilder tr = new StringBuilder();
        for (Map.Entry<String, Integer> e : trades.entrySet()) tr.append(tr.length() == 0 ? "" : ", ").append(e.getKey()).append(' ').append(e.getValue());
        out.add("TRADES " + (tr.length() == 0 ? "none" : tr));
        out.add("HOMES " + Ledger.buildings(id).size() + " buildings, beds for " + Villages.housing(id) + (Villages.headcount(id) > Villages.housing(id)
            ? " (" + (Villages.headcount(id) - Villages.housing(id)) + " short)" : ""));
        out.add("STORES food " + Villages.stock(level, v.centre(), Villages.Task.FOOD, Villages.storesRadius(id)) + ", logs "
            + Villages.stock(level, v.centre(), Villages.Task.LOGS, Villages.storesRadius(id)) + ", stone "
            + Villages.stock(level, v.centre(), Villages.Task.STONE, Villages.storesRadius(id)) + ", iron "
            + Villages.stock(level, v.centre(), Villages.Task.IRON, Villages.storesRadius(id)) + "; " + Villages.storeChests(level, id).size() + " store chest(s)");
        out.add("TREASURY " + Ledger.coins(id) + " coin" + (Market.marketDay(id, day(level)) ? "; market day today" : ""));
        List<String> wars = new ArrayList<>();
        for (UUID e : Wars.enemies(id)) wars.add(Villages.name(e));
        List<String> vass = new ArrayList<>();
        for (UUID e : Vassals.vassals(id)) vass.add(Villages.name(e));
        out.add("WAR " + (wars.isEmpty() ? "at peace" : "at war with " + String.join(", ", wars)) + "; guards " + guards(id).size()
            + (Vassals.isVassal(id) ? "; vassal of " + Villages.name(Vassals.overlord(id)) : "") + (vass.isEmpty() ? "" : "; vassals " + String.join(", ", vass))
            + (Raids.underAlarm(id) ? "; UNDER ALARM" : ""));
        double ms = com.jrpetty.mcassistant.admin.AdminProbe.msPerSecond(id);
        out.add("COST " + (ms < 0 ? "measuring (ask again in a few seconds)" : String.format(Locale.ROOT, "%.2f ms a second", ms)) + " of the town's own rounds");
        List<String> problems = new ArrayList<>();
        if (Villages.headcount(id) > Villages.housing(id)) problems.add("not everyone has a bed");
        String stuck = StuckSpots.line(id);
        if (stuck != null && !stuck.isBlank()) problems.add(stuck);
        if (all.isEmpty()) problems.add("no folk loaded (is anybody near?)");
        out.add("PROBLEMS " + (problems.isEmpty() ? "none found" : String.join("; ", problems)));
        return out;
    }

    /** One folk, a few lines. */
    public static List<String> inspect(VillageFolkEntity f) {
        List<String> out = new ArrayList<>();
        int[] needs = FolkNeeds.of(f);
        out.add("FOLK " + f.displayNameCap() + " (" + f.getUUID().toString().substring(0, 8) + ") of " + (f.ownerId() == null ? "no town" : Villages.name(f.ownerId()))
            + ", " + (f.isBaby() ? "a child" : f.ageYears() + " years") + ", " + f.stationTask().title + " level " + f.tradeLevel(f.stationTask()));
        StringBuilder n = new StringBuilder();
        for (int i = 0; i < FolkNeeds.COUNT; i++) n.append(i == 0 ? "" : ", ").append(FolkNeeds.NAMES[i]).append(' ').append(needs[i]);
        out.add("NEEDS " + n + "; health " + (int) f.getHealth() + "/" + (int) f.getMaxHealth() + (f.health().ill() ? ", ill" : ""));
        BlockPos home = Homes.homeOf(f);
        out.add("HOME " + (home == null ? "none" : home.toShortString()) + "; partner " + (f.life().partner() == null ? "none" : f.life().partnerName())
            + "; at " + f.blockPosition().toShortString());
        out.add("TRADES " + (f.tradeLevels().isEmpty() ? "none yet" : f.tradeLevels()));
        return out;
    }

    /** The building nearest this spot, a few lines. */
    public static List<String> inspectBuilding(UUID village, BlockPos at) {
        Ledger.Building best = null;
        double d = Double.MAX_VALUE;
        for (Ledger.Building b : Ledger.buildings(village)) {
            double x = b.anchor().distSqr(at);
            if (x < d) { d = x; best = b; }
        }
        if (best == null) return List.of("No buildings on the register yet.");
        Renovation.Stamp s = Renovation.stamp(village, best.anchor());
        return List.of("BUILDING " + best.structure() + " at " + best.anchor().toShortString() + " facing " + best.facing().getName()
            + " (" + (int) Math.sqrt(d) + " blocks from you)", "STAMP " + (s == null ? "none" : s.drawing() + " v" + s.version() + ", " + s.theme() + ", " + s.age()),
            "RENOVATING " + Renovation.renovating(village, best.anchor()));
    }
}
