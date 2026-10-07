package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.ChunkLoad;
import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.village.Ledger;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.monster.warden.Warden;
import net.minecraft.world.entity.vehicle.AbstractMinecartContainer;
import net.minecraft.world.entity.vehicle.ContainerEntity;
import net.minecraft.world.entity.vehicle.MinecartChest;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.PickaxeItem;
import net.minecraft.world.item.ShieldItem;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.block.entity.SpawnerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.level.storage.loot.BuiltInLootTables;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * [caves] The cave dwellers: an Iron Age trade that is half miner and half guard. A town of twenty-five or more in
 * the Iron Age, with miners at its mine and a watch on its walls, sends one of its folk into the caves round it (one
 * for every twenty-seven folk, four at the most), armed and armoured by the town as the watch is.
 *
 * <p><b>Who.</b> The town takes a hand up for it (tick, appoint) only from a trade it is not short of: an idle
 * hand first, then a miner or a guard over the town's share, the curious and the hardy before the sociable. A
 * newcomer may take it up as it does any other trade (Villages' share for it).
 *
 * <p><b>The kit.</b> Each morning it goes to the stores (kitUp): the armour, blade and shield the town gives its
 * watch (WatchKit.fit), the best pickaxe the stores hold (iron in the Iron Age, diamond once there are diamonds), a
 * stack of torches (made of the stores' coal and sticks if the stores have none), food for the day and a little
 * cobblestone to wall off lava. The town's pieces carry the watch's mark and go back into the stores when it leaves
 * the trade (handBack); nothing of it comes out of its own purse.
 *
 * <p><b>The day out.</b> It goes the way of a scout (Scouts.Expedition, with a Delve for the cave's part of it), out
 * at first light and home by dusk, the ground round it kept awake as it goes. It makes for a cave the report knows
 * and has not worked out, or, knowing none, walks out on the bearing least looked at, out to a range that grows with
 * the age (a hundred blocks in the Iron Age, a hundred and fifty in the Diamond, two hundred in the Nether), looking
 * all the while for a way down under the rock that it can walk. In the cave:
 * <ul>
 * <li>It lights the way with torches where it is dark, which marks the way home, and drops a mark every few blocks
 *     to walk back by.</li>
 * <li>It mines every ore it sees in the walls that its pick allows, the whole vein (coal, copper, iron, gold,
 *     redstone, lapis, emerald, amethyst; diamond with iron or better; obsidian only with diamond), never the
 *     ground under its own feet, never a block with lava or water behind it, never a step of the miners' stairs,
 *     never in the town.</li>
 * <li>It opens the chests the world left: a mineshaft's carts, a dungeon's chests, the chests of the old temples,
 *     ruins, strongholds and the ancient cities. A chest the world filled has its loot table still; an opened one
 *     counts only inside a structure the world built; a named chest, one in a village of villagers, or anything
 *     near a town is never touched. It takes the valuable things (ore, ingots, gems, enchanted books, golden
 *     apples, saddles, name tags, music discs) and leaves the rest.</li>
 * <li>It notes the spawners (and leaves them), the mineshafts and structures, and the lava as a danger to the miners.</li>
 * <li>It fights what comes at it and clears the way (the guards' melee), backs off from a creeper, never goes near
 *     a warden, eats when hurt, and turns for home badly hurt, with a full pack, out of torches, or at the hour.</li>
 * </ul>
 * Home, the finds go into the stores, booked as its work (Economy), and what it found goes into the town's report.
 *
 * <p><b>Never trapped.</b> It comes home along its own marks. A mark it cannot get back to, it goes on to the next;
 * underground with no way it can walk, it cuts its own stairs up as a lost miner does (MineStairs, MineGoal "out");
 * and if that fails too it calls for help, and the town's search party goes out for it (SearchParties). It is never
 * lifted out.
 *
 * <p><b>The report.</b> Kept with the town (the Ledger's "caves" note): the caves (where, how deep, how big), the
 * ravines, the ore veins seen and mined, the mineshafts, the dungeons and spawners, the structures and what was
 * found in their chests, and the lava. Shown on the Caves page of the town's books (CityScreen), by /village caves,
 * on the board, on each cave dweller's card, in the chronicle and the gazette for the big finds (diamonds, a
 * mineshaft, a dungeon, a temple's treasure), at the morning assembly, and by anybody asked about the caves.
 */
public final class CaveDwellers {

    private static final org.slf4j.Logger LOG = com.mojang.logging.LogUtils.getLogger();

    private CaveDwellers() {}

    /** A town takes up the trade from this many folk, in the Iron Age, with miners and a watch. */
    public static final int FROM = 25;
    /** One cave dweller for so many folk. */
    public static final int PER = 27;
    /** And never more than this many, however big the town. */
    public static final int MOST = 4;
    /** The miners and the guards a town has before it sends anybody into the caves. */
    static final int MINERS = 2, GUARDS = 1;
    /** Torches it takes out of the stores; food for the day; cobblestone to wall off lava. */
    public static final int TORCHES = 32;
    static final int FOOD = 6, COBBLE = 8;
    /** How many things the report keeps. */
    static final int KEEP = 160;
    /** The most of one vein it goes after (a vein of coal can run to forty). */
    static final int VEIN_MOST = 32;
    /** Turn for home at this hour of the day (dusk is at twelve thousand), or after so long out. */
    static final long TURN = 7600, LONGEST = 9000;
    /** Never down past here: every open cave below Y-54 is full of lava (VillageFolkEntity's deepest mine). */
    static final int ABOVE_BOTTOM = 14;
    /** How far into a cave system it goes from where it went in. */
    static final int DEEPEST = 80;

    // ------------------------------------------------------------------ what it finds

    /** What a cave dweller can find. */
    public enum Kind {
        CAVE("a cave"), RAVINE("a ravine"), VEIN("ore"), MINESHAFT("a mineshaft"), DUNGEON("a dungeon"), SPAWNER("a spawner"),
        STRUCTURE("ruins"), CHEST("a chest"), LAVA("lava");

        public final String words;

        Kind(String words) { this.words = words; }
    }

    /**
     * One thing in the report: what, where, when, by whom, and two numbers that depend on what it is (a cave: how
     * deep under the surface and how big, in open blocks; a vein: how many blocks seen and how many mined; a
     * mineshaft or a structure: how many of its chests were looked in; a chest: how many things were taken out of it;
     * lava: how deep).
     */
    public record Find(Kind kind, String label, BlockPos at, long day, String by, int a, int b) {
        String encode() {
            return kind.name() + "|" + clean(label) + "|" + at.getX() + "|" + at.getY() + "|" + at.getZ() + "|" + day + "|" + clean(by)
                + "|" + a + "|" + b;
        }

        @Nullable
        static Find decode(String s) {
            String[] p = s.split("\\|", -1);
            if (p.length < 9) return null;
            try {
                return new Find(Kind.valueOf(p[0]), p[1], new BlockPos(Integer.parseInt(p[2]), Integer.parseInt(p[3]), Integer.parseInt(p[4])),
                    Long.parseLong(p[5]), p[6], Integer.parseInt(p[7]), Integer.parseInt(p[8]));
            } catch (IllegalArgumentException e) {
                return null;
            }
        }

        Find with(int na, int nb) {
            return new Find(kind, label, at, day, by, na, nb);
        }

        private static String clean(String s) {
            return s.replace('|', '/').replace('\n', ' ');
        }
    }

    // ------------------------------------------------------------------ the day out

    /** The cave's part of a cave dweller's day out (on its Scouts.Expedition). */
    public static final class Delve {
        enum Phase { OUT, IN }

        Phase phase = Phase.OUT;
        /** The spot under the rock it is making for, and the spot it went in at. */
        @Nullable BlockPos cave, mouth;
        /** Where in the cave it is walking to now, how near it has got, and when it last got nearer. */
        @Nullable BlockPos target;
        double targetBest = Double.MAX_VALUE;
        int targetTick, noWay;
        /** The cave's cells it has stood in, four blocks a side; cells it could not get to. */
        final Set<Long> visited = new HashSet<>(), passedCells = new HashSet<>();
        /** Ores and chests passed over (out of reach, its pick too poor, lava behind them). */
        final Set<Long> passed = new HashSet<>();
        /** The vein it is working: what ore, where it was first seen, and the blocks of it still to dig. */
        final ArrayDeque<BlockPos> vein = new ArrayDeque<>();
        @Nullable String veinOf;
        @Nullable BlockPos veinAt;
        int veinDug;
        @Nullable BlockPos digging;
        int dug, digNeeded, digTick;
        double reachBest = Double.MAX_VALUE;
        /** The chest or the cart it is making for. */
        @Nullable BlockPos chest;
        @Nullable UUID cart;
        int chestTick;
        double chestBest = Double.MAX_VALUE;
        /** The one it is fighting, and since when. */
        @Nullable UUID foe;
        int foeTick;
        int frontierFails, failTick = -1000, lookTick, torches, mined, opened, slain, climbs, ateTick = -1000, torchTick = -1000, spokeTick = -1000;
        /** What it brought out, by name; and what it found, for the report when it gets home. */
        final Map<String, Integer> haul = new LinkedHashMap<>();
        final List<Find> found = new ArrayList<>();
        /** Things it would not mine, said once each ("obsidian, and my iron pick won't take it"). */
        final Set<String> left = new HashSet<>();
        boolean explored;
        final long day;

        Delve(long day) {
            this.day = day;
        }

        public String phase() { return phase.name().toLowerCase(Locale.ROOT); }
        public int mined() { return mined; }
        public int opened() { return opened; }
        public int slain() { return slain; }
        public int torches() { return torches; }
        public boolean explored() { return explored; }
        @Nullable public BlockPos mouth() { return mouth; }
        public Map<String, Integer> haul() { return haul; }
    }

    /** The day each cave dweller last went out. */
    private static final Map<UUID, Long> WENT = new ConcurrentHashMap<>();
    /** Cave dwellers that could not find the way out, and called for help (game time). */
    private static final Map<UUID, Long> LOST = new ConcurrentHashMap<>();
    /** What the cave dwellers came home with, for the next morning assembly. */
    private static final Map<UUID, List<String>> REPORTS = new ConcurrentHashMap<>();
    /** When each town was last looked at for a cave dweller to take up the trade. */
    private static final Map<UUID, Long> TICKED = new ConcurrentHashMap<>();
    /** Tests: a block dug in two seconds, whatever the pick. */
    private static boolean quick;

    public static void resetForTests() {
        WENT.clear();
        LOST.clear();
        REPORTS.clear();
        TICKED.clear();
        quick = false;
    }

    /** Tests: digging done quickly, so a test need not wait on the pace of an iron pick through a dozen ores. */
    public static void quickForTests(boolean on) {
        quick = on;
    }

    // ------------------------------------------------------------------ the trade: who, and how many

    /** The town's cave dwellers: grown, alive, and no showcase. */
    public static List<VillageFolkEntity> dwellers(@Nullable UUID village) {
        List<VillageFolkEntity> out = new ArrayList<>();
        for (AssistantEntity a : Villages.folkOf(village)) {
            if (a instanceof VillageFolkEntity f && f.stationTask() == StationTask.CAVE && !f.isBaby() && f.isAlive() && !f.isShowcase()) out.add(f);
        }
        return out;
    }

    /** Has the town what it takes to send anybody into the caves: a few miners and a watch (or a cave dweller already)? */
    public static boolean ready(@Nullable UUID village) {
        if (village == null) return false;
        int miners = 0, guards = 0;
        for (AssistantEntity a : Villages.folkOf(village)) {
            if (a.isBaby() || !a.isAlive()) continue;
            StationTask t = a.stationTask();
            if (t == StationTask.CAVE) return true;
            if (t == StationTask.MINE) miners++;
            else if (t == StationTask.GUARD) guards++;
        }
        return miners >= MINERS && guards >= GUARDS;
    }

    /** How many cave dwellers the town wants: none before the Iron Age or twenty-five folk; one for every twenty-seven, four at most. */
    public static int wanted(@Nullable UUID village) {
        if (village == null || Villages.ageOf(village).ordinal() < Villages.Age.IRON.ordinal()) return 0;
        int n = Villages.headcount(village);
        if (n < FROM || !ready(village)) return 0;
        return Math.min(MOST, Math.max(1, n / PER));
    }

    /** May a hand be spared from this trade for the caves? Never from a trade the town is short of, a building's hands,
     *  the storehouse's or the scouts'. */
    static boolean spare(UUID village, StationTask t) {
        if (t == StationTask.NONE) return true;
        if (t == StationTask.CAVE || t.isCraft() || t == StationTask.STORE || t == StationTask.BANK || t == StationTask.SCOUT
            || t == StationTask.HAUL) return false;
        return Villages.share(village, t) >= 0.5;
    }

    /** How well a folk would do in the caves: a miner's eye and a guard's arm, a curious nature and a hardy one. */
    static int fitness(VillageFolkEntity f) {
        int s = f.tradeLevel(StationTask.CAVE) * 5 + f.tradeLevel(StationTask.MINE) * 2 + f.tradeLevel(StationTask.GUARD) * 2;
        if (f.stationTask() == StationTask.NONE) s += 40;
        if (f.life().has(Social.Trait.CURIOUS)) s += 12;
        if (f.life().has(Social.Trait.HARDWORKING)) s += 6;
        if (f.life().has(Social.Trait.SOCIABLE)) s -= 8;
        return s;
    }

    /**
     * Once in a while for each town (VillageFolkEntity, beside the bank's look): a town that wants another cave dweller
     * than it has takes one up, one a day at most.
     */
    public static void tick(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        long now = level.getGameTime();
        Long last = TICKED.get(id);
        if (last != null && now - last < 600L && now >= last) return;
        TICKED.put(id, now);
        if (dwellers(id).size() >= wanted(id)) return;
        long day = level.getDayTime() / 24000L;
        String was = Ledger.note(id, "caves.appointed");
        if (was != null && was.equals(Long.toString(day))) return;
        appoint(level, v, day);
    }

    /** A hand taken up for the caves now, if the town wants one and can spare one. Returns who, or null. */
    @Nullable
    public static VillageFolkEntity appoint(ServerLevel level, Villages.Village v, long day) {
        UUID id = v.id();
        if (dwellers(id).size() >= wanted(id)) return null;
        VillageFolkEntity best = null;
        int bestScore = Integer.MIN_VALUE;
        for (AssistantEntity a : Villages.folkOf(id)) {
            if (!(a instanceof VillageFolkEntity f) || f.isBaby() || f.isShowcase() || f.isHired() || !f.isAlive() || f.isElder()) continue;
            if (f.trip() != null || f.expedition() != null) continue;
            if (!spare(id, f.stationTask())) continue;
            int age = f.ageYears();
            if (age < 18 || age > 50) continue;                       // fit for a day underground (JobMarket.ages)
            int score = fitness(f);
            if (score > bestScore) { bestScore = score; best = f; }
        }
        if (best == null) return null;
        StationTask was = best.stationTask();
        BlockPos post = post(v);
        best.setStation(post, StationTask.CAVE);
        best.assignPlot(WorkZone.around(post, 4, WorkZone.DEFAULT_DEPTH), "The Caves");
        Ledger.note(id, "caves.appointed", Long.toString(day));
        int n = dwellers(id).size();
        Villages.tell(id, day, best.displayNameCap() + " " + (was == StationTask.NONE ? "took up" : "gave up " + was.label + " for")
            + " the caves: the town's " + (n <= 1 ? "first" : JobMarket.words(n)) + " cave dweller");
        best.persona().remember(day, "I became one of the town's cave dwellers", 5);
        FolkTalk.speak(best, FolkTalk.pick(level.getRandom(), "The caves! A pick in one hand and a blade in the other. When do I start?",
            "Somebody has to see what's down there. It may as well be me.", "Into the dark for the town, then. I'll want plenty of torches."));
        LOG.info("[MCA-CAVES] {} of {} took up the caves (was {}), {} of {} wanted", best.displayNameCap(), Villages.name(id), was, n, wanted(id));
        return best;
    }

    /** Where a cave dweller stands of a day at home: by the board, or the square. */
    static BlockPos post(Villages.Village v) {
        BlockPos board = VillageBoards.lectern(v.id());
        BlockPos at = board != null ? board : v.centre();
        return at.relative(Direction.EAST, 3);
    }

    // ------------------------------------------------------------------ the kit

    public static boolean isPickaxe(ItemStack s) {
        return !s.isEmpty() && s.getItem() instanceof PickaxeItem;
    }

    /** A pickaxe's worth to a cave dweller: its metal first, how worn second. */
    static int pickScore(ItemStack s) {
        if (!isPickaxe(s)) return -1;
        String path = BuiltInRegistries.ITEM.getKey(s.getItem()).getPath();
        int metal = path.startsWith("netherite") ? 6 : path.startsWith("diamond") ? 5 : path.startsWith("iron") ? 4
            : path.startsWith("stone") ? 3 : path.startsWith("golden") ? 2 : 1;
        int left = s.getMaxDamage() - s.getDamageValue();
        return metal * 10000 + Math.min(9999, Math.max(0, left)) - (left < 16 ? 50000 : 0);
    }

    /** The best pick it carries, in hand or in its pack; empty if none. */
    static ItemStack bestPick(VillageFolkEntity f) {
        ItemStack best = ItemStack.EMPTY;
        if (isPickaxe(f.getMainHandItem())) best = f.getMainHandItem();
        for (ItemStack s : f.getInventoryItems()) if (pickScore(s) > pickScore(best)) best = s;
        return best;
    }

    /** Its best pick into its hand. */
    static void equipPick(VillageFolkEntity f) {
        ItemStack best = bestPick(f);
        if (best.isEmpty() || best == f.getMainHandItem()) return;
        var pack = f.getInventoryItems();
        for (int i = 0; i < pack.size(); i++) {
            if (pack.get(i) != best) continue;
            ItemStack old = f.getMainHandItem();
            f.setItemSlot(EquipmentSlot.MAINHAND, best);
            pack.set(i, old);
            return;
        }
    }

    private static boolean food(ItemStack s) {
        return s.get(DataComponents.FOOD) != null && !s.is(Items.ROTTEN_FLESH) && !s.is(Items.SPIDER_EYE) && !s.is(Items.POISONOUS_POTATO)
            && !valuable(s);
    }

    /** Its kit, kept when it banks its finds: what it wears and wields, its torches, its rations, its cobble. */
    static boolean kit(ItemStack s) {
        return s.isDamageableItem() || s.is(Items.TORCH) || food(s) || WatchKit.issued(s);
    }

    /**
     * Fitted out of the stores, free, as the watch is (WatchKit.fit: armour, a blade, a shield), with the best pick
     * the stores hold, torches, food for the day and a little cobble. Returns what it was given, in words.
     */
    public static List<String> kitUp(ServerLevel level, Villages.Village v, VillageFolkEntity f) {
        List<String> got = new ArrayList<>();
        UUID id = v.id();
        String who = f.displayNameCap();
        // Travelling light: what is not its kit (a farmer's seed, a sapling, a bench) waits in the stores, so the pack
        // has room for what the caves give up. Its keepsakes stay with it.
        var pack = f.getInventoryItems();
        for (int i = 0; i < pack.size(); i++) {
            ItemStack s = pack.get(i);
            if (s.isEmpty() || kit(s) || Homes.isKeepsake(s) || s.is(Items.COBBLESTONE) || s.is(Items.COBBLED_DEEPSLATE)) continue;
            Crafts.store(level, v, s.copy());
            pack.set(i, ItemStack.EMPTY);
        }
        for (ItemStack s : WatchKit.fit(level, v, f)) got.add(Bench.words(s.getItem(), s.getCount()));
        // The best pickaxe the stores hold, if it beats its own; the town's old one back into the stores.
        ItemStack mine = bestPick(f);
        Workshop.Found better = Workshop.bestInStores(level, id, CaveDwellers::isPickaxe, CaveDwellers::pickScore, pickScore(mine));
        if (better != null) {
            ItemStack pick = WatchKit.mark(Workshop.takeOut(level, id, better, who));
            if (!mine.isEmpty() && WatchKit.issued(mine)) {
                ItemStack back = mine.copy();
                mine.setCount(0);
                Workshop.backIntoStores(level, v, WatchKit.unmarked(back), who);
            }
            ItemStack left = f.insertGiven(pick);
            if (!left.isEmpty()) Workshop.backIntoStores(level, v, WatchKit.unmarked(left), who);
            else got.add(Bench.words(pick.getItem(), 1));
        } else if (mine.isEmpty() && Crafts.stock(level, v, s -> s.is(Items.COBBLESTONE)) >= 3
                && Crafts.stock(level, v, s -> s.is(Items.STICK)) >= 2) {
            // None in the stores either: a stone one, of the stores' cobble and sticks, as a player makes it.
            if (Crafts.take(level, v, s -> s.is(Items.COBBLESTONE), 3) && Crafts.take(level, v, s -> s.is(Items.STICK), 2)) {
                f.insertGiven(WatchKit.mark(new ItemStack(Items.STONE_PICKAXE)));
                got.add("a stone pickaxe, of the stores' cobble");
            }
        }
        // Torches: what the stores have, up to a stack and a half; short of them, made of the stores' coal and sticks.
        int want = TORCHES - f.countMatching(s -> s.is(Items.TORCH));
        if (want > 0) {
            int n = Math.min(want, Crafts.stock(level, v, s -> s.is(Items.TORCH)));
            if (n > 0 && Crafts.take(level, v, s -> s.is(Items.TORCH), n)) {
                f.insertGiven(new ItemStack(Items.TORCH, n));
                got.add(n + " torches");
                want -= n;
            }
        }
        int made = 0;
        Predicate<ItemStack> coal = s -> s.is(Items.COAL) || s.is(Items.CHARCOAL);
        while (want >= 4 && made < 16 && Crafts.stock(level, v, coal) >= 1 && Crafts.stock(level, v, s -> s.is(Items.STICK)) >= 1) {
            if (!Crafts.take(level, v, coal, 1)) break;
            if (!Crafts.take(level, v, s -> s.is(Items.STICK), 1)) {
                Crafts.store(level, v, new ItemStack(Items.COAL));
                break;
            }
            f.insertGiven(new ItemStack(Items.TORCH, 4));
            made += 4;
            want -= 4;
        }
        if (made > 0) got.add(made + " torches, made of the stores' coal and sticks");
        // Food for the day.
        int meals = 0;
        for (int i = 0; i < FOOD && f.countMatching(CaveDwellers::food) < FOOD; i++) {
            ItemStack s = Crafts.takeOne(level, v, CaveDwellers::food);
            if (s.isEmpty()) break;
            ItemStack left = f.insertGiven(s);
            if (!left.isEmpty()) Crafts.store(level, v, left);
            meals++;
        }
        if (meals > 0) got.add(meals + " things to eat");
        // A little cobble, to wall off lava.
        int cobble = COBBLE - f.countMatching(s -> s.is(Items.COBBLESTONE) || s.is(Items.COBBLED_DEEPSLATE));
        if (cobble > 0 && Crafts.stock(level, v, s -> s.is(Items.COBBLESTONE)) >= cobble + 16
                && Crafts.take(level, v, s -> s.is(Items.COBBLESTONE), cobble)) {
            f.insertGiven(new ItemStack(Items.COBBLESTONE, cobble));
        }
        if (!got.isEmpty()) f.brain("fitted out for the caves by the town: " + String.join(", ", got));
        return got;
    }

    /** Tests: fitted out of the stores now. */
    public static List<String> kitUpForTests(ServerLevel level, Villages.Village v, VillageFolkEntity f) {
        return kitUp(level, v, f);
    }

    /**
     * A cave dweller leaving the trade (another trade, another town) hands back what the town issued it, its pick
     * with the armour and the blade, into the stores. Not to the watch: the watch's kit is the same kit.
     */
    public static int handBack(VillageFolkEntity f, String why) {
        UUID id = f.ownerId();
        Villages.Village v = id == null ? null : Villages.get(id);
        if (v == null || !(f.level() instanceof ServerLevel level)) return 0;
        String who = f.displayNameCap();
        List<String> back = new ArrayList<>();
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            ItemStack s = f.getItemBySlot(slot);
            if (!WatchKit.issued(s)) continue;
            back.add(Bench.words(s.getItem(), s.getCount()));
            Workshop.backIntoStores(level, v, WatchKit.unmarked(s.copy()), who);
            f.setItemSlot(slot, ItemStack.EMPTY);
        }
        var pack = f.getInventoryItems();
        for (int i = 0; i < pack.size(); i++) {
            ItemStack s = pack.get(i);
            if (!WatchKit.issued(s)) continue;
            back.add(Bench.words(s.getItem(), s.getCount()));
            Workshop.backIntoStores(level, v, WatchKit.unmarked(s.copy()), who);
            pack.set(i, ItemStack.EMPTY);
        }
        if (back.isEmpty()) return 0;
        f.brain("handed the caves' kit back into the stores (" + why + "): " + String.join(", ", back));
        FolkTalk.speak(f, FolkTalk.pick(f.getRandom(), "The town's kit goes back to the stores. The next one into the caves will want it.",
            "Back it goes: " + String.join(", ", back) + ". It was never mine to keep."));
        return back.size();
    }

    // ------------------------------------------------------------------ at home

    /** How far out its day takes it: further as the town's age climbs. */
    public static int range(@Nullable UUID village) {
        if (village == null) return 100;
        return switch (Villages.ageOf(village)) {
            case WOOD, STONE, IRON -> 100;
            case DIAMOND -> 150;
            case NETHER -> 200;
        };
    }

    /** Is this folk down in the caves on its day out? */
    public static boolean caving(VillageFolkEntity f) {
        Scouts.Expedition e = f.expedition();
        return e != null && e.delve != null;
    }

    /**
     * A cave dweller's day at home (its station brain): in the morning, kitted out and away; the rest of the day at the
     * board with the report. Returns whether it did something.
     */
    public static boolean work(VillageFolkEntity f, ServerLevel level) {
        UUID id = f.ownerId();
        Villages.Village v = id == null ? null : Villages.get(id);
        if (v == null) return false;
        if (f.expedition() != null) return true;
        long time = level.getDayTime() % 24000L, day = level.getDayTime() / 24000L;
        if (lost(f, level, v)) return true;
        // Somebody left out past the town (a restart, a day cut short): home first.
        int reach = Villages.townReach(id) + 40;
        if (Scouts.flat(f.blockPosition(), v.centre()) > (double) reach * reach) {
            comeHome(level, f, v, "came home");
            return true;
        }
        boolean fit = f.getHealth() >= f.getMaxHealth() * 0.7F && !level.isThundering() && !Raids.underAlarm(id);
        boolean morning = time >= 1200 && time < 4200;
        if (morning && fit && WENT.getOrDefault(f.getUUID(), -1L) < day && !Assemblies.attending(f)) {
            if (setOut(f, level, v, day, null)) return true;
        }
        BlockPos spot = post(v);
        if (f.blockPosition().distSqr(spot) > 9) {
            if (f.getNavigation().isDone()) f.walkTo(spot, 0.8D);
        } else {
            BlockPos board = VillageBoards.lectern(id);
            BlockPos at = board != null ? board : v.centre();
            f.getLookControl().setLookAt(at.getX() + 0.5, at.getY() + 1.5, at.getZ() + 0.5);
            f.hobbyNow = "going over what the caves gave up";
        }
        return true;
    }

    /** Lost underground after calling for help: the search party's to find it, and the miners' stairs to get it up. */
    private static boolean lost(VillageFolkEntity f, ServerLevel level, Villages.Village v) {
        Long since = LOST.get(f.getUUID());
        if (since == null) return false;
        int reach = Villages.townReach(v.id()) + 16;
        if (Scouts.flat(f.blockPosition(), v.centre()) <= (double) reach * reach || level.getGameTime() - since > 4 * 24000L) {
            LOST.remove(f.getUUID());
            return false;
        }
        // Up out of the ground on its own and nobody out looking for it: home over the land.
        if (!MineStairs.underground(level, f.blockPosition()) && !SearchParties.searchedFor(f.getUUID())) {
            LOST.remove(f.getUUID());
            comeHome(level, f, v, "got out on its own");
            return true;
        }
        f.hobbyNow = "lost in the caves, and waiting to be found";
        return true;
    }

    /** Home from wherever it is: a day out that is all way home. */
    static void comeHome(ServerLevel level, VillageFolkEntity f, Villages.Village v, String why) {
        Scouts.Expedition e = new Scouts.Expedition(v.id(), v.centre(), 0, f.blockPosition());
        Delve d = new Delve(level.getDayTime() / 24000L);
        e.delve = d;
        e.returning = true;
        e.crumb = -1;
        e.why = why;
        e.startedTick = e.gainedTick = e.surveyTick = f.tickCount;
        f.clearQueue();
        f.expedition(e);
    }

    /** Fitted out, the day's way chosen, and off. A cave given (the report's, or a test's) is made for; else it looks. */
    static boolean setOut(VillageFolkEntity f, ServerLevel level, Villages.Village v, long day, @Nullable BlockPos cave) {
        UUID id = v.id();
        BlockPos home = v.centre();
        kitUp(level, v, f);
        if (f.countMatching(CaveDwellers::food) == 0) {
            FolkTalk.speak(f, "Nothing in the stores to take down with me. Nobody goes into the dark hungry.");
            WENT.put(f.getUUID(), day);
            return false;
        }
        if (bestPick(f).isEmpty() && f.countMatching(s -> s.getItem() instanceof SwordItem) == 0) {
            FolkTalk.speak(f, "No pick and no blade. I'm not going down there with my bare hands.");
            WENT.put(f.getUUID(), day);
            return false;
        }
        int range = range(id);
        BlockPos dest = cave != null ? cave : knownCave(level, f, v, range, day);
        int bearing;
        BlockPos target;
        if (dest != null) {
            bearing = Scouts.bearingOf(dest.getX() - home.getX(), dest.getZ() - home.getZ());
            target = dest;
        } else {
            bearing = leastLooked(id, f, day);
            double ang = bearing * (2 * Math.PI / Scouts.BEARINGS);
            target = new BlockPos(home.getX() + (int) Math.round(Math.cos(ang) * range), home.getY(),
                home.getZ() + (int) Math.round(Math.sin(ang) * range));
        }
        f.clearQueue();
        f.getNavigation().stop();
        Scouts.Expedition e = new Scouts.Expedition(id, home, bearing, target);
        Delve d = new Delve(day);
        d.cave = dest;
        e.delve = d;
        e.startedTick = e.gainedTick = e.surveyTick = f.tickCount;
        e.trail.add(f.blockPosition().immutable());
        f.expedition(e);
        WENT.put(f.getUUID(), day);
        markLooked(id, bearing, day);
        String where = dest != null ? "the cave " + e.heading + ", " + (int) Math.sqrt(Scouts.flat(home, dest)) / 10 * 10 + " blocks out"
            : "the caves " + e.heading;
        FolkTalk.speak(f, FolkTalk.pick(f.getRandom(), "Off into " + where + ". Back by dusk!", "Down into " + where + " today. Torches, pick, blade — all here.",
            "Into the dark " + e.heading + ". Keep the fire lit for me!"));
        LOG.info("[MCA-CAVES] {} of {} sets out for {} (range {}, bearing {})", f.displayNameCap(), Villages.name(id), where, range, bearing);
        return true;
    }

    /** Tests and the stage: into this cave now, whatever the hour (fitted out of the stores first). */
    public static boolean sendForTests(VillageFolkEntity f, ServerLevel level, BlockPos cave) {
        UUID id = f.ownerId();
        Villages.Village v = id == null ? null : Villages.get(id);
        if (v == null) return false;
        Scouts.Expedition was = f.expedition();
        if (was != null) {
            Scouts.release(level, f, was);
            f.expedition(null);
        }
        return setOut(f, level, v, level.getDayTime() / 24000L, cave);
    }

    /** The nearest cave the report knows, within range, not worked out, not gone to in the last two days, and not
     *  another cave dweller's today. */
    @Nullable
    static BlockPos knownCave(ServerLevel level, VillageFolkEntity f, Villages.Village v, int range, long day) {
        Set<Long> taken = new HashSet<>();
        for (VillageFolkEntity o : dwellers(v.id())) {
            if (o != f && o.expedition() != null && o.expedition().delve != null && o.expedition().delve.cave != null) {
                taken.add(key(o.expedition().delve.cave));
            }
        }
        Find best = null;
        for (Find x : report(v.id())) {
            if (x.kind() != Kind.CAVE && x.kind() != Kind.RAVINE) continue;
            if (Scouts.flat(x.at(), v.centre()) > (double) range * range) continue;
            long k = key(x.at());
            if (taken.contains(k) || done(v.id(), x.at())) continue;
            String went = Ledger.note(v.id(), "caves.went/" + k);
            if (went != null) {
                try {
                    if (day - Long.parseLong(went) < 2) continue;
                } catch (NumberFormatException ignored) {
                    // an unreadable day: gone to long ago
                }
            }
            if (best == null || x.at().distSqr(v.centre()) < best.at().distSqr(v.centre())) best = x;
        }
        return best == null ? null : best.at();
    }

    private static long key(BlockPos p) {
        return BlockPos.asLong(p.getX() >> 3, 0, p.getZ() >> 3);
    }

    /** Has a cave been worked out (gone all through, every ore its pick allowed taken)? */
    static boolean done(UUID village, BlockPos cave) {
        String s = Ledger.note(village, "caves.done");
        return s != null && s.contains("," + key(cave) + ",");
    }

    private static void markDone(UUID village, BlockPos cave) {
        String s = Ledger.note(village, "caves.done");
        if (s == null || s.isEmpty()) s = ",";
        if (s.contains("," + key(cave) + ",")) return;
        if (s.length() > 4000) s = "," + s.substring(s.indexOf(',', s.length() / 2) + 1);
        Ledger.note(village, "caves.done", s + key(cave) + ",");
    }

    /** The bearing the cave dwellers have looked along least lately (not one another of them is out on today). */
    static int leastLooked(UUID village, VillageFolkEntity f, long day) {
        long[] last = looked(village);
        Set<Integer> taken = new HashSet<>();
        for (VillageFolkEntity o : dwellers(village)) if (o != f && o.expedition() != null) taken.add(o.expedition().bearing);
        java.util.Random rng = new java.util.Random(f.getUUID().getLeastSignificantBits() ^ day * 31L);
        int start = rng.nextInt(Scouts.BEARINGS), best = start;
        long oldest = Long.MAX_VALUE;
        for (int k = 0; k < Scouts.BEARINGS; k++) {
            int b = (start + k) % Scouts.BEARINGS;
            if (taken.contains(b)) continue;
            if (last[b] < oldest) { oldest = last[b]; best = b; }
        }
        return best;
    }

    private static long[] looked(UUID village) {
        long[] out = new long[Scouts.BEARINGS];
        java.util.Arrays.fill(out, -1L);
        String s = Ledger.note(village, "caves.looked");
        if (s == null || s.isEmpty()) return out;
        String[] p = s.split(",");
        for (int i = 0; i < Math.min(p.length, out.length); i++) {
            try {
                out[i] = Long.parseLong(p[i]);
            } catch (NumberFormatException ignored) {
                // never looked
            }
        }
        return out;
    }

    private static void markLooked(UUID village, int bearing, long day) {
        long[] last = looked(village);
        last[Math.floorMod(bearing, last.length)] = day;
        StringBuilder sb = new StringBuilder();
        for (long l : last) sb.append(sb.length() == 0 ? "" : ",").append(l);
        Ledger.note(village, "caves.looked", sb.toString());
    }

    // ------------------------------------------------------------------ out: a step at a time (Scouts.drive, every five ticks)

    /** A cave dweller out on its day: walked, fought, dug and lit a step at a time. True while it is out. */
    static boolean drive(ServerLevel level, VillageFolkEntity f, Scouts.Expedition e) {
        Delve d = e.delve;
        if (d == null) return false;
        keepAwake(level, f, e);
        long time = level.getDayTime() % 24000L;
        BlockPos feet = f.blockPosition();
        boolean below = MineStairs.underground(level, feet);
        if (d.phase == Delve.Phase.IN || below) d.visited.add(cell(feet));
        // Cutting its own stairs up out of the ground (MineGoal's "out", as a lost miner does): that is the work now.
        Job j = f.peekJob();
        if (j != null && j.type() == Job.Type.MINE) {
            e.gainedTick = f.tickCount;
            f.hobbyNow = "cutting its way up out of the caves";
            return true;
        }
        if (fight(level, f, e, d)) return true;
        // Hurt: something to eat, and badly hurt, home.
        if (f.getHealth() < f.getMaxHealth() * 0.65F && f.tickCount - d.ateTick > 120 && f.eatFromPack()) d.ateTick = f.tickCount;
        if (!e.returning) {
            if (f.stationTask() != StationTask.CAVE) turnBack(level, f, e, "I'd other work to go to");
            else if (f.getHealth() < f.getMaxHealth() * 0.4F) turnBack(level, f, e, "I got hurt");
            else if (time >= TURN && time < 23000) turnBack(level, f, e, "it was time to head home");
            else if (f.tickCount - e.startedTick > LONGEST) turnBack(level, f, e, "it was time to head home");
            else if (freeSlots(f) < 2) turnBack(level, f, e, "my pack was full");
            else if (d.phase == Delve.Phase.IN && f.countMatching(s -> s.is(Items.TORCH)) == 0 && dark(level, feet)) {
                turnBack(level, f, e, "my torches ran out");
            }
        }
        // A look about every two seconds.
        if (f.tickCount - e.surveyTick >= 40) {
            e.surveyTick = f.tickCount;
            survey(level, f, e, d);
            if (f.expedition() != e) return true;
        }
        if (d.phase == Delve.Phase.IN || below) light(level, f, d);
        crumbs(level, f, e, d, below);
        if (!e.returning && atWork(level, f, e, d)) return true;
        if (e.returning) return walkHome(level, f, e, d);
        return d.phase == Delve.Phase.OUT ? walkOut(level, f, e, d) : explore(level, f, e, d);
    }

    /** The cave's cell a spot is in: four blocks a side. */
    static long cell(BlockPos p) {
        return BlockPos.asLong(p.getX() >> 2, p.getY() >> 2, p.getZ() >> 2);
    }

    private static int freeSlots(VillageFolkEntity f) {
        int n = 0;
        for (ItemStack s : f.getInventoryItems()) if (s.isEmpty()) n++;
        return n;
    }

    private static boolean dark(ServerLevel level, BlockPos at) {
        return level.getBrightness(LightLayer.BLOCK, at) < 6 && level.getBrightness(LightLayer.SKY, at) < 8;
    }

    /** A mark to walk home by: every sixteen blocks on the land, every six in a cave. */
    private static void crumbs(ServerLevel level, VillageFolkEntity f, Scouts.Expedition e, Delve d, boolean below) {
        if (e.returning || !f.onGround() || e.trail.isEmpty()) return;
        BlockPos last = e.trail.get(e.trail.size() - 1);
        double step = below || d.phase == Delve.Phase.IN ? 6 : 16;
        if (f.blockPosition().distSqr(last) > step * step) e.trail.add(f.blockPosition().immutable());
    }

    /** The ground round it kept awake as it goes, as a scout's is (and let go by Scouts.release with it). */
    private static void keepAwake(ServerLevel level, VillageFolkEntity f, Scouts.Expedition e) {
        BlockPos here = f.blockPosition();
        if (e.window != null && e.window.distSqr(here) < 16 * 16) return;
        UUID owner = UUID.nameUUIDFromBytes(("mca-scout-" + f.getUUID()).getBytes());     // Scouts.owner's
        if (e.window != null) ChunkLoad.setLoaded(level, owner, e.window, 2, false);
        ChunkLoad.setLoaded(level, owner, here, 2, true);
        e.window = here.immutable();
    }

    // ------------------------------------------------------------------ fighting

    /**
     * Something hostile: what it is fighting is left to the guards' melee (MeleeAttackGoal), its sword in hand; what
     * comes near it is taken on, to clear the way (not a creeper: it backs off from that, nor anything while it is
     * too hurt to fight). A warden is never fought: it turns for home at once. True while the fight is its work.
     */
    static boolean fight(ServerLevel level, VillageFolkEntity f, Scouts.Expedition e, Delve d) {
        LivingEntity t = f.getTarget();
        if (t != null && t.isAlive() && t.distanceToSqr(f) < 24 * 24) {
            if (d.foe == null || !d.foe.equals(t.getUUID())) {
                d.foe = t.getUUID();
                d.foeTick = f.tickCount;
            } else if (f.tickCount - d.foeTick > 600) {
                // Half a minute and it cannot get at it (a skeleton up on a ledge): it gives it up.
                if (!e.returning) {
                    turnBack(level, f, e, "I couldn't get at what was down there");
                    d.foeTick = f.tickCount;
                } else {
                    f.setTarget(null);
                    d.foe = null;
                    return false;
                }
            }
            f.equipBestWeapon();
            e.gainedTick = f.tickCount;
            return true;
        }
        if (d.foe != null) {
            Entity was = level.getEntity(d.foe);
            if (was == null || !was.isAlive()) {
                d.slain++;
                if (f.tickCount - d.spokeTick > 200) {
                    d.spokeTick = f.tickCount;
                    FolkTalk.speak(f, FolkTalk.pick(f.getRandom(), "That's one less down here.", "Down it goes. On we go.", "Cleared."));
                }
            }
            d.foe = null;
            if (t != null && !t.isAlive()) f.setTarget(null);
        }
        if (f.isRetreating()) return true;
        for (Warden w : level.getEntitiesOfClass(Warden.class, new AABB(f.blockPosition()).inflate(24), Warden::isAlive)) {
            if (!e.returning) {
                d.found.add(new Find(Kind.LAVA, "a warden in the deep dark", w.blockPosition(), d.day, f.displayNameCap(), depth(level, w.blockPosition()), 0));
                turnBack(level, f, e, "there was a warden down there");
            }
            break;
        }
        Mob m = foe(level, f, 8);
        if (m == null) return false;
        if (m instanceof Creeper || f.shouldDisengage()) {
            Vec3 away = f.position().subtract(m.position()).normalize().scale(6);
            BlockPos back = e.trail.size() > 1 ? e.trail.get(e.trail.size() - 2) : BlockPos.containing(f.position().add(away));
            f.getNavigation().moveTo(back.getX() + 0.5, back.getY(), back.getZ() + 0.5, 1.3D);
            if (f.tickCount - d.spokeTick > 200) {
                d.spokeTick = f.tickCount;
                FolkTalk.speak(f, m instanceof Creeper ? "Creeper! Back, back!" : "Too many of them. Back the way I came!");
            }
            return true;
        }
        f.setTarget(m);
        f.equipBestWeapon();
        d.foe = m.getUUID();
        if (f.tickCount - d.spokeTick > 200) {
            d.spokeTick = f.tickCount;
            FolkTalk.speak(f, FolkTalk.pick(f.getRandom(), "Something down here. Stand and fight!", "Not past me, you don't.", "Come on, then!"));
        }
        return true;
    }

    /** The nearest hostile thing it can see, within so many blocks. */
    @Nullable
    private static Mob foe(ServerLevel level, VillageFolkEntity f, int r) {
        Mob best = null;
        double near = (double) r * r;
        for (Mob m : level.getEntitiesOfClass(Mob.class, new AABB(f.blockPosition()).inflate(r), x -> x instanceof Enemy && x.isAlive())) {
            if (m instanceof Warden || m instanceof net.minecraft.world.entity.monster.EnderMan) continue;
            if (m.isInWater() && !f.isInWater()) continue;
            double dd = m.distanceToSqr(f);
            if (dd < near && f.hasLineOfSight(m)) { near = dd; best = m; }
        }
        return best;
    }

    // ------------------------------------------------------------------ out over the land, and in

    /** Out to the caves: in stages over the land as a scout goes, then by the path-finder into the cave it saw. */
    static boolean walkOut(ServerLevel level, VillageFolkEntity f, Scouts.Expedition e, Delve d) {
        BlockPos feet = f.blockPosition();
        int reach = Villages.townReach(e.village);
        if (MineStairs.underground(level, feet) && depth(level, feet) >= 4 && Scouts.flat(feet, e.home) > (double) reach * reach) {
            enter(level, f, e, d);
            return true;
        }
        if (d.cave != null && feet.distSqr(d.cave) <= 40 * 40) {
            // Near enough to see the way in: the path-finder's way, all the way down.
            if (feet.distSqr(d.cave) <= 2.5 * 2.5) {
                enter(level, f, e, d);
                return true;
            }
            if (f.getNavigation().isDone() || f.tickCount - e.walkTick > 100) {
                Path p = f.getNavigation().createPath(d.cave, 1);
                e.walkTick = f.tickCount;
                if (p != null && p.canReach()) {
                    f.getNavigation().moveTo(p, 1.0D);
                    d.noWay = 0;
                } else if (++d.noWay > 4) {
                    d.passedCells.add(cell(d.cave));
                    f.brain("no way down to the cave from here");
                    d.cave = null;
                    d.noWay = 0;
                }
            }
            f.hobbyNow = "making for a way into the rock";
            return true;
        }
        BlockPos dest = d.cave != null ? d.cave : e.target;
        double flat = Scouts.flat(feet, dest);
        if (d.cave == null && flat <= 10 * 10) {
            turnBack(level, f, e, "there was no way under the rock out " + e.heading);
            return true;
        }
        if (flat < e.best - 2.0) {
            e.best = flat;
            e.gainedTick = f.tickCount;
        } else if (f.tickCount - e.gainedTick > 300) {
            e.detour++;
            e.waypoint = null;
            e.gainedTick = f.tickCount;
            if (e.detour > 5) {
                turnBack(level, f, e, "the way " + e.heading + " was blocked");
                return true;
            }
        }
        if (e.waypoint == null || Scouts.flat(feet, e.waypoint) <= 3 * 3) {
            e.waypoint = Scouts.chooseWaypoint(level, f, dest, e.detour);
            e.walkTick = -1000;
        }
        if (e.waypoint != null && (f.getNavigation().isDone() || f.tickCount - e.walkTick > 80)) {
            f.walkTo(e.waypoint, 1.05D);
            e.walkTick = f.tickCount;
        }
        f.hobbyNow = "off to the caves " + e.heading;
        return true;
    }

    /** In under the rock: the cave measured (how deep, how big, a ravine or a cave) and noted. */
    static void enter(ServerLevel level, VillageFolkEntity f, Scouts.Expedition e, Delve d) {
        d.phase = Delve.Phase.IN;
        BlockPos at = f.blockPosition();
        d.mouth = at.immutable();
        d.target = null;
        e.waypoint = null;
        if (e.trail.isEmpty() || e.trail.get(e.trail.size() - 1).distSqr(at) > 4) e.trail.add(at.immutable());
        int[] size = measure(level, at);
        boolean ravine = size[1] >= 12;
        int depth = depth(level, at);
        String label = ravine ? "a ravine" : size[0] >= 600 ? "a great cave" : size[0] >= 200 ? "a big cave" : "a cave";
        note(f, d, new Find(ravine ? Kind.RAVINE : Kind.CAVE, label, at, d.day, f.displayNameCap(), depth, size[0]),
            ravine ? "A ravine! Look how far down it goes." : FolkTalk.pick(f.getRandom(), "In we go. Mind your heads.", "Dark as a cellar. Torches out."));
        Ledger.note(e.village, "caves.went/" + key(d.cave != null ? d.cave : at), Long.toString(d.day));
        if (d.cave == null) d.cave = at.immutable();
    }

    /** How deep under the surface a spot is. */
    static int depth(ServerLevel level, BlockPos at) {
        return Math.max(0, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, at.getX(), at.getZ()) - at.getY());
    }

    /** The open blocks round a spot (a box twelve across, nine high) and the tallest run of open air over it. */
    static int[] measure(ServerLevel level, BlockPos at) {
        int open = 0;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int dx = -6; dx <= 6; dx++) {
            for (int dz = -6; dz <= 6; dz++) {
                if (!loaded(level, at.getX() + dx, at.getZ() + dz)) continue;
                for (int dy = -2; dy <= 6; dy++) {
                    m.set(at.getX() + dx, at.getY() + dy, at.getZ() + dz);
                    if (level.getBlockState(m).isAir()) open++;
                }
            }
        }
        int tall = 0;
        for (int dy = 0; dy < 40 && level.getBlockState(m.set(at.getX(), at.getY() + dy, at.getZ())).isAir(); dy++) tall++;
        return new int[]{ open, tall };
    }

    private static boolean loaded(ServerLevel level, int x, int z) {
        return level.getChunkSource().getChunkNow(x >> 4, z >> 4) != null;
    }

    /** Somewhere to stand: two open blocks over a solid floor, dry. */
    static boolean standable(ServerLevel level, BlockPos p) {
        BlockState at = level.getBlockState(p), head = level.getBlockState(p.above()), floor = level.getBlockState(p.below());
        if (!at.isAir() && !at.canBeReplaced() || !head.isAir() && !head.canBeReplaced()) return false;
        if (!level.getFluidState(p).isEmpty() || !level.getFluidState(p.above()).isEmpty()) return false;
        if (at.is(Blocks.POWDER_SNOW) || floor.is(Blocks.MAGMA_BLOCK) || floor.is(Blocks.POINTED_DRIPSTONE)) return false;
        return floor.isFaceSturdy(level, p.below(), Direction.UP);
    }

    private static boolean lavaNear(ServerLevel level, BlockPos p, int r) {
        for (BlockPos q : BlockPos.betweenClosed(p.offset(-r, -1, -r), p.offset(r, 1, r))) {
            if (level.getFluidState(q).is(net.minecraft.tags.FluidTags.LAVA)) return true;
        }
        return false;
    }

    /**
     * A way under the rock near it, that it can walk to: open ground with rock over it, the deepest and most open
     * of what it sees first, and only where the path-finder says it can get to. Null for none.
     */
    @Nullable
    static BlockPos cavern(ServerLevel level, VillageFolkEntity f, Delve d, int r) {
        BlockPos feet = f.blockPosition();
        net.minecraft.util.RandomSource rnd = f.getRandom();
        int floor = level.getMinBuildHeight() + ABOVE_BOTTOM;
        List<BlockPos> found = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            int x = feet.getX() + rnd.nextInt(2 * r + 1) - r, z = feet.getZ() + rnd.nextInt(2 * r + 1) - r;
            if (!loaded(level, x, z)) continue;
            int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            for (int y = Math.min(top - 4, feet.getY() + 4); y >= Math.max(floor, feet.getY() - 28); y--) {
                BlockPos p = new BlockPos(x, y, z);
                if (!standable(level, p)) continue;
                if (!MineStairs.underground(level, p) || d.passedCells.contains(cell(p)) || lavaNear(level, p, 2)) continue;
                found.add(p);
                break;
            }
        }
        found.sort(Comparator.comparingDouble(p -> (double) p.getY() - open(level, p) * 2.0));
        int tries = 0;
        for (BlockPos p : found) {
            if (tries++ >= 3) break;
            Path path = f.getNavigation().createPath(p, 1);
            if (path != null && path.canReach()) return p;
            d.passedCells.add(cell(p));
        }
        return null;
    }

    private static int open(ServerLevel level, BlockPos p) {
        int n = 0;
        for (BlockPos q : BlockPos.betweenClosed(p.offset(-2, 0, -2), p.offset(2, 2, 2))) if (level.getBlockState(q).isAir()) n++;
        return n;
    }

    /** In the cave with nothing in hand: on to somewhere in it not yet seen, further in; all seen, home. */
    static boolean explore(ServerLevel level, VillageFolkEntity f, Scouts.Expedition e, Delve d) {
        BlockPos feet = f.blockPosition();
        if (d.target != null) {
            double dist = feet.distSqr(d.target);
            if (dist <= 2.5 * 2.5) {
                d.target = null;
            } else {
                if (dist < d.targetBest - 1.0) {
                    d.targetBest = dist;
                    d.targetTick = f.tickCount;
                } else if (f.tickCount - d.targetTick > 160) {
                    d.passedCells.add(cell(d.target));
                    d.target = null;
                    return true;
                }
                if (f.getNavigation().isDone() || f.tickCount - e.walkTick > 80) {
                    f.getNavigation().moveTo(d.target.getX() + 0.5, d.target.getY(), d.target.getZ() + 0.5, 1.0D);
                    e.walkTick = f.tickCount;
                }
                f.hobbyNow = "exploring the caves " + e.heading;
                return true;
            }
        }
        if (f.tickCount < d.lookTick) return true;               // (a look takes the path-finder a while: not every step)
        BlockPos next = frontier(level, f, e, d);
        if (next == null) {
            d.lookTick = f.tickCount + 20;
            // Nowhere new it can get to, three looks running with a look about between each: it has been all through it.
            if (f.tickCount - d.failTick >= 40) {
                d.failTick = f.tickCount;
                if (++d.frontierFails >= 3) {
                    d.explored = true;
                    turnBack(level, f, e, "I'd been all through it");
                }
            }
            return true;
        }
        d.frontierFails = 0;
        d.target = next;
        d.targetBest = Double.MAX_VALUE;
        d.targetTick = f.tickCount;
        return true;
    }

    /** Somewhere in the cave it has not been, that it can walk to: further from the way in, and a little deeper, first. */
    @Nullable
    static BlockPos frontier(ServerLevel level, VillageFolkEntity f, Scouts.Expedition e, Delve d) {
        BlockPos feet = f.blockPosition();
        net.minecraft.util.RandomSource rnd = f.getRandom();
        int floor = level.getMinBuildHeight() + ABOVE_BOTTOM;
        BlockPos mouth = d.mouth != null ? d.mouth : feet;
        List<BlockPos> found = new ArrayList<>();
        for (int i = 0; i < 48; i++) {
            int dx = rnd.nextInt(29) - 14, dz = rnd.nextInt(29) - 14;
            if (dx * dx + dz * dz < 9) continue;
            int x = feet.getX() + dx, z = feet.getZ() + dz;
            if (!loaded(level, x, z)) continue;
            for (int y = feet.getY() + 5; y >= feet.getY() - 8 && y > floor; y--) {
                BlockPos p = new BlockPos(x, y, z);
                if (!standable(level, p)) continue;
                long c = cell(p);
                if (!MineStairs.underground(level, p) || d.visited.contains(c) || d.passedCells.contains(c)) break;
                if (p.distSqr(mouth) > (double) DEEPEST * DEEPEST || lavaNear(level, p, 2)) break;
                found.add(p);
                break;
            }
        }
        found.sort(Comparator.comparingDouble(p -> -(p.distSqr(mouth) + (feet.getY() - p.getY()) * 6.0)));
        int tries = 0;
        for (BlockPos p : found) {
            if (tries++ >= 4) break;
            Path path = f.getNavigation().createPath(p, 1);
            if (path != null && path.canReach()) {
                f.getNavigation().moveTo(path, 1.0D);
                e.walkTick = f.tickCount;
                return p;
            }
            d.passedCells.add(cell(p));
        }
        return null;
    }

    /** A torch where it is dark, to see by and to mark the way home: the stores' torch, put to use. */
    static void light(ServerLevel level, VillageFolkEntity f, Delve d) {
        if (f.tickCount - d.torchTick < 40 || !f.onGround()) return;
        BlockPos at = f.blockPosition();
        if (!dark(level, at)) return;
        if (!level.getBlockState(at).isAir() || !level.getFluidState(at).isEmpty()) return;
        if (!level.getBlockState(at.below()).isFaceSturdy(level, at.below(), Direction.UP)) return;
        if (f.removeMatching(s -> s.is(Items.TORCH), 1) < 1) return;
        level.setBlockAndUpdate(at, Blocks.TORCH.defaultBlockState());
        f.placeSound(at);
        f.swing(InteractionHand.MAIN_HAND);
        Economy.usedGiven(f, new ItemStack(Items.TORCH), 1);
        d.torches++;
        d.torchTick = f.tickCount;
    }

    // ------------------------------------------------------------------ looking about

    /** What it can see from here: ore in the walls, old chests and carts, spawners, what was built down here, lava. */
    static void survey(ServerLevel level, VillageFolkEntity f, Scouts.Expedition e, Delve d) {
        BlockPos at = f.blockPosition();
        int reach = Villages.townReach(e.village);
        boolean out = Scouts.flat(at, e.home) > (double) (reach + 8) * (reach + 8);
        if (d.phase == Delve.Phase.OUT && d.cave == null && !e.returning && out) {
            BlockPos c = cavern(level, f, d, 20);
            if (c != null) {
                d.cave = c;
                FolkTalk.speak(f, FolkTalk.pick(f.getRandom(), "A way down into the rock! In we go.", "There — a cave mouth. Torches ready."));
                LOG.info("[MCA-CAVES] {} found a way in at {}", f.displayNameCap(), c.toShortString());
            }
        }
        if (!out) return;
        boolean below = d.phase == Delve.Phase.IN || MineStairs.underground(level, at);
        if (below) ores(level, f, e, d);
        containers(level, f, e, d);
        structures(level, f, d);
        if (below) lava(level, f, d);
    }

    /** The ore it can name, by its block; null for anything else. */
    @Nullable
    public static String oreOf(BlockState st) {
        if (st.is(BlockTags.DIAMOND_ORES)) return "diamond";
        if (st.is(BlockTags.EMERALD_ORES)) return "emerald";
        if (st.is(BlockTags.GOLD_ORES)) return "gold";
        if (st.is(BlockTags.IRON_ORES)) return "iron";
        if (st.is(BlockTags.REDSTONE_ORES)) return "redstone";
        if (st.is(BlockTags.LAPIS_ORES)) return "lapis";
        if (st.is(BlockTags.COPPER_ORES)) return "copper";
        if (st.is(BlockTags.COAL_ORES)) return "coal";
        if (st.is(Blocks.OBSIDIAN)) return "obsidian";
        if (st.is(Blocks.AMETHYST_CLUSTER)) return "amethyst";
        return null;
    }

    private static boolean exposed(ServerLevel level, BlockPos p) {
        for (Direction dir : Direction.values()) if (level.getBlockState(p.relative(dir)).isAir()) return true;
        return false;
    }

    /** Is there lava or water against this block (that would pour into the hole)? */
    static boolean wetBeside(ServerLevel level, BlockPos p) {
        for (Direction dir : Direction.values()) if (!level.getFluidState(p.relative(dir)).isEmpty()) return true;
        return false;
    }

    /** Why it will not mine this, in a few words; null if it will. */
    @Nullable
    static String refuse(ServerLevel level, VillageFolkEntity f, BlockPos p, BlockState st, ItemStack pick, UUID village) {
        if (pick.isEmpty()) return "I've no pick";
        if (!pick.isCorrectToolForDrops(st)) return "my " + BuiltInRegistries.ITEM.getKey(pick.getItem()).getPath().replace('_', ' ') + " won't take it";
        if (wetBeside(level, p)) return "there's lava or water behind it";
        int reach = Villages.townReach(village);
        Villages.Village v = Villages.get(village);
        if (v != null && Scouts.flat(p, v.centre()) <= (double) reach * reach) return "it's in the town";
        if (MineStairs.isFloor(level, p)) return "it's a step of the miners' stairs";
        if (st.is(Blocks.OBSIDIAN)) {
            for (Direction dir : Direction.values()) {
                if (level.getBlockState(p.relative(dir)).is(Blocks.NETHER_PORTAL)) return "it's a portal's frame";
            }
            if (!MineStairs.underground(level, p)) return "it's somebody's, out in the open";
        }
        return null;
    }

    /** Ore in the walls round it: each vein noted, and the first it may mine taken in hand. */
    static void ores(ServerLevel level, VillageFolkEntity f, Scouts.Expedition e, Delve d) {
        BlockPos at = f.blockPosition();
        ItemStack pick = bestPick(f);
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        Set<Long> seen = new HashSet<>();
        for (int dy = -3; dy <= 4; dy++) {
            for (int dx = -6; dx <= 6; dx++) {
                for (int dz = -6; dz <= 6; dz++) {
                    m.set(at.getX() + dx, at.getY() + dy, at.getZ() + dz);
                    if (seen.contains(m.asLong()) || d.passed.contains(m.asLong())) continue;
                    BlockState st = level.getBlockState(m);
                    String ore = oreOf(st);
                    if (ore == null || !exposed(level, m)) continue;
                    BlockPos p = m.immutable();
                    List<BlockPos> vein = flood(level, p, ore);
                    for (BlockPos q : vein) seen.add(q.asLong());
                    if (d.vein.contains(p)) continue;
                    note(f, d, new Find(Kind.VEIN, ore, p, d.day, f.displayNameCap(), vein.size(), 0),
                        "diamond".equals(ore) ? "Diamonds! Down here, in the wall!" : null);
                    if (e.returning || d.veinOf != null || d.chest != null || d.cart != null) continue;
                    String no = refuse(level, f, p, st, pick, e.village);
                    if (no != null) {
                        for (BlockPos q : vein) d.passed.add(q.asLong());
                        if (d.left.add(ore + ":" + no)) {
                            f.brain("left the " + ore + " at " + p.toShortString() + ": " + no);
                            if ("obsidian".equals(ore) || "diamond".equals(ore)) FolkTalk.speak(f, capital(ore) + " — but " + no + ".");
                        }
                        continue;
                    }
                    d.veinOf = ore;
                    d.veinAt = p;
                    d.veinDug = 0;
                    List<BlockPos> open = new ArrayList<>();
                    for (BlockPos q : vein) if (exposed(level, q)) open.add(q);
                    open.sort(Comparator.comparingDouble(q -> q.distSqr(at)));
                    d.vein.addAll(open);
                }
            }
        }
    }

    /** The vein: this ore and every block of the same joined to it, as far as VEIN_MOST and eight blocks out. */
    static List<BlockPos> flood(ServerLevel level, BlockPos start, String ore) {
        List<BlockPos> out = new ArrayList<>();
        ArrayDeque<BlockPos> q = new ArrayDeque<>();
        Set<Long> seen = new HashSet<>();
        q.add(start);
        seen.add(start.asLong());
        while (!q.isEmpty() && out.size() < VEIN_MOST) {
            BlockPos p = q.poll();
            out.add(p);
            for (Direction dir : Direction.values()) {
                BlockPos n = p.relative(dir);
                if (!seen.add(n.asLong()) || n.distSqr(start) > 64) continue;
                if (ore.equals(oreOf(level.getBlockState(n)))) q.add(n);
            }
        }
        return out;
    }

    /** At work: a chest it is making for, or the vein in hand. True while there is such work. */
    static boolean atWork(ServerLevel level, VillageFolkEntity f, Scouts.Expedition e, Delve d) {
        if (d.chest != null || d.cart != null) return loot(level, f, e, d);
        if (!d.vein.isEmpty()) return dig(level, f, e, d);
        if (d.veinOf != null) {
            if (d.veinDug > 0) f.brain("the " + d.veinOf + " vein dug out: " + d.veinDug + " blocks");
            d.veinOf = null;
            d.veinAt = null;
        }
        return false;
    }

    /** The next block of the vein: to it, then dug, a block at its pick's pace. */
    static boolean dig(ServerLevel level, VillageFolkEntity f, Scouts.Expedition e, Delve d) {
        BlockPos p = d.vein.peekFirst();
        BlockState st = level.getBlockState(p);
        String ore = oreOf(st);
        if (ore == null || !ore.equals(d.veinOf)) {
            d.vein.pollFirst();
            d.digging = null;
            return true;
        }
        ItemStack pick = bestPick(f);
        String no = refuse(level, f, p, st, pick, e.village);
        if (no != null) {
            d.vein.pollFirst();
            d.passed.add(p.asLong());
            d.digging = null;
            f.brain("left a block of " + ore + ": " + no);
            return true;
        }
        BlockPos feet = f.blockPosition();
        if (p.getX() == feet.getX() && p.getZ() == feet.getZ() && p.getY() < feet.getY()) {
            // Never the ground it stands on: a step to one side first, and the block dug from there.
            BlockPos aside = aside(level, f, p);
            if (aside == null) {
                d.vein.pollFirst();
                d.passed.add(p.asLong());
                return true;
            }
            f.getNavigation().moveTo(aside.getX() + 0.5, aside.getY(), aside.getZ() + 0.5, 1.0D);
            return true;
        }
        double reach = f.getEyePosition().distanceToSqr(Vec3.atCenterOf(p));
        if (d.digging == null || !d.digging.equals(p)) {
            d.digging = p;
            d.digTick = f.tickCount;
            d.dug = 0;
            d.digNeeded = 0;
            d.reachBest = Double.MAX_VALUE;
        }
        if (reach > 4.3 * 4.3) {
            if (reach < d.reachBest - 0.5) {
                d.reachBest = reach;
                d.digTick = f.tickCount;
            } else if (f.tickCount - d.digTick > 160) {
                d.vein.pollFirst();
                d.passed.add(p.asLong());
                d.digging = null;
                f.brain("couldn't get at a block of " + ore + " at " + p.toShortString());
                return true;
            }
            if (f.getNavigation().isDone() || f.tickCount - e.walkTick > 60) {
                f.getNavigation().moveTo(p.getX() + 0.5, p.getY(), p.getZ() + 0.5, 1.0D);
                e.walkTick = f.tickCount;
            }
            f.hobbyNow = "going after the " + ore;
            return true;
        }
        f.getNavigation().stop();
        if (d.digNeeded <= 0) {
            equipPick(f);
            d.digNeeded = quick ? 10 : Math.max(10, f.workTicksFor(st));
        }
        f.getLookControl().setLookAt(p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5);
        d.dug += 5;
        if (d.dug % 10 == 0) {
            f.swing(InteractionHand.MAIN_HAND);
            f.workHit(p);
        }
        f.hobbyNow = "mining " + ore + " in the caves";
        if (d.dug < d.digNeeded) return true;
        breakOre(level, f, e, d, p, st, ore);
        d.vein.pollFirst();
        d.digging = null;
        d.digNeeded = 0;
        // What of the vein the break laid open.
        for (Direction dir : Direction.values()) {
            BlockPos n = p.relative(dir);
            if (d.vein.contains(n) || d.passed.contains(n.asLong()) || d.veinDug >= VEIN_MOST) continue;
            if (ore.equals(oreOf(level.getBlockState(n)))) d.vein.addLast(n);
        }
        return true;
    }

    /** A spot beside an ore under its feet to dig it from: open, with a floor, the same height. */
    @Nullable
    private static BlockPos aside(ServerLevel level, VillageFolkEntity f, BlockPos ore) {
        BlockPos feet = f.blockPosition();
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            for (int k = 1; k <= 2; k++) {
                BlockPos p = feet.relative(dir, k);
                if (p.getX() == ore.getX() && p.getZ() == ore.getZ()) continue;
                if (standable(level, p) && !p.below().equals(ore)) return p;
            }
        }
        return null;
    }

    /** The block out: what it drops for this pick, into its pack, booked as its work (Economy.gathered). */
    static void breakOre(ServerLevel level, VillageFolkEntity f, Scouts.Expedition e, Delve d, BlockPos p, BlockState st, String ore) {
        if (!isPickaxe(f.getMainHandItem())) equipPick(f);
        ItemStack tool = f.getMainHandItem();
        BlockEntity be = level.getBlockEntity(p);
        List<ItemStack> drops = Block.getDrops(st, level, p, be, f, tool);
        level.destroyBlock(p, false, f);
        for (ItemStack drop : drops) {
            if (drop.isEmpty()) continue;
            ItemStack lot = drop.copy();
            ItemStack left = f.insertItem(drop);
            int in = lot.getCount() - left.getCount();
            if (in > 0) {
                Economy.gathered(f, lot, in);
                haul(d, lot, in);
            }
            if (!left.isEmpty()) Block.popResource(level, p, left);
        }
        f.damageHeldTool();
        f.note(AssistantEntity.Deed.BLOCKS_MINED, 1);
        f.note(AssistantEntity.Deed.ORE_FOUND, 1);
        d.mined++;
        d.veinDug++;
        BlockPos at = d.veinAt != null ? d.veinAt : p;
        merge(d.found, new Find(Kind.VEIN, ore, at, d.day, f.displayNameCap(), 0, 1));
        if (d.mined == 1 || "diamond".equals(ore) || "emerald".equals(ore)) {
            if (f.tickCount - d.spokeTick > 100) {
                d.spokeTick = f.tickCount;
                FolkTalk.speak(f, "diamond".equals(ore) ? "Diamonds! The smith will want these." : "emerald".equals(ore) ? "An emerald! That's a find."
                    : FolkTalk.pick(f.getRandom(), capital(ore) + ", and plenty of it.", "Good " + ore + " here. Into the pack it goes."));
            }
        }
    }

    // ------------------------------------------------------------------ old chests

    /** Is this a container the world left (its loot table not yet opened, or inside a structure the world built), and
     *  nobody's: not named, not a village of villagers', not near any town? */
    static boolean worldChest(ServerLevel level, BlockEntity be) {
        if (!(be instanceof ChestBlockEntity) && !(be instanceof BarrelBlockEntity)) return false;
        RandomizableContainerBlockEntity box = (RandomizableContainerBlockEntity) be;
        BlockPos p = be.getBlockPos();
        if (box.hasCustomName() || nearAnyTown(level, p)) return false;
        String built = structureAt(level, p);
        if (!lootable(level, built, p)) return false;
        return box.getLootTable() != null || built != null && structureWords(built) != null;
    }

    /**
     * May a chest in this structure be looked into? Never a village of villagers' (theirs), an outpost's or a mansion's
     * (the pillagers'), the deep dark's or the trial chambers' (a warden, the breezes: no cave dweller comes home from
     * those), nor buried treasure (it would have to dig for it). A desert temple's only once its trap is gone: its
     * chests sit round a pressure plate over a cellar of TNT.
     */
    static boolean lootable(ServerLevel level, @Nullable String built, BlockPos at) {
        if (built == null) return true;
        if (built.startsWith("village") || built.equals("pillager_outpost") || built.equals("mansion") || built.equals("ancient_city")
            || built.equals("trial_chambers") || built.equals("buried_treasure")) return false;
        if (built.equals("desert_pyramid")) {
            for (BlockPos q : BlockPos.betweenClosed(at.offset(-5, -3, -5), at.offset(5, 3, 5))) {
                if (level.getBlockState(q).is(Blocks.STONE_PRESSURE_PLATE)) return false;
            }
        }
        return true;
    }

    /** A mineshaft's cart (or another the world left): a chest cart, its loot unopened or down in the structure, nobody's. */
    static boolean worldCart(ServerLevel level, AbstractMinecartContainer cart) {
        if (!(cart instanceof MinecartChest) || cart.hasCustomName() || nearAnyTown(level, cart.blockPosition())
            || !lootable(level, structureAt(level, cart.blockPosition()), cart.blockPosition())) return false;
        if (cart.getLootTable() != null) return true;
        String built = structureAt(level, cart.blockPosition());
        return built != null && built.startsWith("mineshaft");
    }

    private static boolean nearAnyTown(ServerLevel level, BlockPos p) {
        for (Villages.Village v : Villages.every()) {
            if (!v.dim().equals(level.dimension())) continue;
            int r = Villages.townReach(v.id()) + 16;
            if (Scouts.flat(p, v.centre()) <= (double) r * r) return true;
        }
        return false;
    }

    /** Has the town's report a chest looked into here already? */
    static boolean looted(UUID village, Delve d, BlockPos p) {
        for (Find x : d.found) if (x.kind() == Kind.CHEST && x.at().equals(p)) return true;
        for (Find x : report(village)) if (x.kind() == Kind.CHEST && x.at().equals(p)) return true;
        return false;
    }

    /** Old chests and carts near it (one taken in hand when it has nothing else to do), and the spawners. */
    static void containers(ServerLevel level, VillageFolkEntity f, Scouts.Expedition e, Delve d) {
        BlockPos at = f.blockPosition();
        boolean free = !e.returning && d.chest == null && d.cart == null && d.vein.isEmpty();
        for (int cx = (at.getX() - 16) >> 4; cx <= (at.getX() + 16) >> 4; cx++) {
            for (int cz = (at.getZ() - 16) >> 4; cz <= (at.getZ() + 16) >> 4; cz++) {
                LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
                if (chunk == null) continue;
                for (BlockEntity be : new ArrayList<>(chunk.getBlockEntities().values())) {
                    BlockPos p = be.getBlockPos();
                    if (p.distSqr(at) > 16 * 16) continue;
                    if (be instanceof SpawnerBlockEntity sp) {
                        spawner(level, f, d, p, sp);
                        continue;
                    }
                    if (!free || d.passed.contains(p.asLong()) || !worldChest(level, be) || looted(e.village, d, p)) continue;
                    d.chest = p;
                    d.chestTick = f.tickCount;
                    d.chestBest = Double.MAX_VALUE;
                    free = false;
                    FolkTalk.speak(f, FolkTalk.pick(f.getRandom(), "An old chest! Let's see what's in it.", "A chest, left down here. Nobody's — not for years."));
                }
            }
        }
        if (!free) return;
        for (AbstractMinecartContainer cart : level.getEntitiesOfClass(AbstractMinecartContainer.class, new AABB(at).inflate(16), Entity::isAlive)) {
            if (d.passed.contains(cart.blockPosition().asLong()) || !worldCart(level, cart) || looted(e.village, d, cart.blockPosition())) continue;
            d.cart = cart.getUUID();
            d.chestTick = f.tickCount;
            d.chestBest = Double.MAX_VALUE;
            FolkTalk.speak(f, "An old cart, still loaded. The miners that left it won't be back for it.");
            return;
        }
    }

    /** To the chest or the cart, opened (the world's loot rolled as it would be for a player), and the valuables taken. */
    static boolean loot(ServerLevel level, VillageFolkEntity f, Scouts.Expedition e, Delve d) {
        Container box;
        BlockPos at;
        boolean cart = d.cart != null;
        if (cart) {
            Entity c = level.getEntity(d.cart);
            if (!(c instanceof AbstractMinecartContainer m) || !c.isAlive()) {
                d.cart = null;
                return true;
            }
            box = m;
            at = c.blockPosition();
        } else {
            BlockEntity be = level.getBlockEntity(d.chest);
            if (!(be instanceof Container c)) {
                d.chest = null;
                return true;
            }
            box = c;
            at = d.chest;
        }
        double reach = f.getEyePosition().distanceToSqr(Vec3.atCenterOf(at));
        if (reach > 4.0 * 4.0) {
            if (reach < d.chestBest - 0.5) {
                d.chestBest = reach;
                d.chestTick = f.tickCount;
            } else if (f.tickCount - d.chestTick > 200) {
                d.passed.add(at.asLong());
                d.chest = null;
                d.cart = null;
                f.brain("couldn't get to an old chest at " + at.toShortString());
                return true;
            }
            if (f.getNavigation().isDone() || f.tickCount - e.walkTick > 60) {
                f.getNavigation().moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 1.0D);
                e.walkTick = f.tickCount;
            }
            f.hobbyNow = "making for an old chest";
            return true;
        }
        f.getNavigation().stop();
        f.getLookControl().setLookAt(at.getX() + 0.5, at.getY() + 0.5, at.getZ() + 0.5);
        if (box instanceof RandomizableContainerBlockEntity r) r.unpackLootTable(null);
        if (box instanceof ContainerEntity ce) ce.unpackChestVehicleLootTable(null);
        level.playSound(null, at, cart ? SoundEvents.CHEST_OPEN : level.getBlockState(at).getBlock() instanceof ChestBlock
            ? SoundEvents.CHEST_OPEN : SoundEvents.BARREL_OPEN, SoundSource.BLOCKS, 0.5F, 1.0F);
        f.swing(InteractionHand.MAIN_HAND);
        List<String> took = new ArrayList<>();
        int n = 0;
        for (int i = 0; i < box.getContainerSize(); i++) {
            ItemStack s = box.getItem(i);
            if (s.isEmpty() || !valuable(s)) continue;
            ItemStack lot = s.copy();
            ItemStack left = f.insertItem(lot.copy());
            int in = lot.getCount() - left.getCount();
            if (in <= 0) continue;
            ItemStack rest = s.copy();
            rest.shrink(in);
            box.setItem(i, rest.isEmpty() ? ItemStack.EMPTY : rest);
            Economy.gathered(f, lot, in);
            haul(d, lot, in);
            took.add(Bench.words(lot.getItem(), in));
            n += in;
        }
        box.setChanged();
        String built = structureAt(level, at);
        String[] words = built == null ? null : structureWords(built);
        String label = cart ? "a mineshaft's cart" : words != null ? words[0] + "'s chest" : spawnerNear(level, at) ? "a dungeon's chest" : "an old chest";
        merge(d.found, new Find(Kind.CHEST, label, at.immutable(), d.day, f.displayNameCap(), n, 0));
        if (words != null) merge(d.found, new Find(words[1].equals("mineshaft") ? Kind.MINESHAFT : Kind.STRUCTURE, words[0], at.immutable(),
            d.day, f.displayNameCap(), 1, 0));
        d.opened++;
        d.chest = null;
        d.cart = null;
        FolkTalk.speak(f, n > 0 ? "In " + label + ": " + String.join(", ", took.subList(0, Math.min(3, took.size()))) + (took.size() > 3 ? " and more" : "") + "!"
            : "Nothing in " + label + " worth carrying home.");
        LOG.info("[MCA-CAVES] {} opened {} at {}: took {}", f.displayNameCap(), label, at.toShortString(), took);
        return true;
    }

    /** What is worth carrying home out of an old chest: ore and metal, gems, books and gold apples, saddles, name tags,
     *  music discs, the smith's templates and the like. Not the bones, the string or the bread. */
    public static boolean valuable(ItemStack s) {
        if (s.isEmpty()) return false;
        if (s.is(Items.DIAMOND) || s.is(Items.EMERALD) || s.is(Items.LAPIS_LAZULI) || s.is(Items.REDSTONE) || s.is(Items.COAL)
            || s.is(Items.RAW_IRON) || s.is(Items.RAW_GOLD) || s.is(Items.RAW_COPPER) || s.is(Items.IRON_INGOT) || s.is(Items.GOLD_INGOT)
            || s.is(Items.COPPER_INGOT) || s.is(Items.NETHERITE_INGOT) || s.is(Items.NETHERITE_SCRAP) || s.is(Items.IRON_NUGGET)
            || s.is(Items.GOLD_NUGGET) || s.is(Items.AMETHYST_SHARD) || s.is(Items.OBSIDIAN) || s.is(Items.QUARTZ)) return true;
        if (s.is(Items.ENCHANTED_BOOK) || s.is(Items.GOLDEN_APPLE) || s.is(Items.ENCHANTED_GOLDEN_APPLE) || s.is(Items.GOLDEN_CARROT)
            || s.is(Items.SADDLE) || s.is(Items.NAME_TAG) || s.is(Items.IRON_HORSE_ARMOR) || s.is(Items.GOLDEN_HORSE_ARMOR)
            || s.is(Items.DIAMOND_HORSE_ARMOR) || s.is(Items.HEART_OF_THE_SEA) || s.is(Items.ECHO_SHARD) || s.is(Items.ENDER_PEARL)
            || s.is(Items.EXPERIENCE_BOTTLE) || s.is(Items.TOTEM_OF_UNDYING) || s.is(Items.DISC_FRAGMENT_5)) return true;
        if (s.get(DataComponents.JUKEBOX_PLAYABLE) != null) return true;                       // a music disc
        if (s.getItem() instanceof net.minecraft.world.item.SmithingTemplateItem) return true;
        String path = BuiltInRegistries.ITEM.getKey(s.getItem()).getPath();
        return path.startsWith("diamond_") || path.startsWith("netherite_") || s.isEnchanted();
    }

    // ------------------------------------------------------------------ spawners, structures, lava

    /** A spawner: noted (a dungeon's, or a structure's), and left be. */
    static void spawner(ServerLevel level, VillageFolkEntity f, Delve d, BlockPos p, SpawnerBlockEntity sp) {
        String mob = "monster";
        try {
            CompoundTag tag = sp.getSpawner().save(new CompoundTag());
            String id = tag.getCompound("SpawnData").getCompound("entity").getString("id");
            if (!id.isEmpty()) mob = id.substring(id.indexOf(':') + 1).replace('_', ' ');
        } catch (RuntimeException ex) {
            LOG.debug("[MCA-CAVES] spawner read failed: {}", ex.toString());
        }
        String built = structureAt(level, p);
        boolean dungeon = built == null && mossyNear(level, p);
        String label = dungeon ? "a dungeon with " + JobMarket.a(mob) + " spawner"
            : JobMarket.a(mob) + " spawner" + (built != null && built.startsWith("mineshaft") ? " in a mineshaft" : "");
        note(f, d, new Find(dungeon ? Kind.DUNGEON : Kind.SPAWNER, label, p.immutable(), d.day, f.displayNameCap(), depth(level, p), 0),
            "A " + mob + " spawner! I'll leave it be — but everybody had better know it's here.");
    }

    private static boolean mossyNear(ServerLevel level, BlockPos p) {
        for (BlockPos q : BlockPos.betweenClosed(p.offset(-4, -1, -4), p.offset(4, 0, 4))) {
            if (level.getBlockState(q).is(Blocks.MOSSY_COBBLESTONE)) return true;
        }
        return false;
    }

    private static boolean spawnerNear(ServerLevel level, BlockPos p) {
        for (BlockPos q : BlockPos.betweenClosed(p.offset(-6, -2, -6), p.offset(6, 2, 6))) {
            if (level.getBlockState(q).is(Blocks.SPAWNER)) return true;
        }
        return false;
    }

    /** The structure the world built that this spot is inside a piece of (its id's path), or null. */
    @Nullable
    static String structureAt(ServerLevel level, BlockPos p) {
        try {
            var registry = level.registryAccess().registryOrThrow(Registries.STRUCTURE);
            for (Structure s : level.structureManager().getAllStructuresAt(p).keySet()) {
                StructureStart start = level.structureManager().getStructureWithPieceAt(p, s);
                if (start == null || !start.isValid()) continue;
                ResourceLocation key = registry.getKey(s);
                if (key != null) return key.getPath();
            }
        } catch (RuntimeException ex) {
            LOG.debug("[MCA-CAVES] structure look failed: {}", ex.toString());
        }
        return null;
    }

    /** What a structure is called, and whether it is a mineshaft or another of "the monuments": {words, "mineshaft" or
     *  "structure"}; null for one whose chests are not the caves' (a village's, an outpost's, a mansion's). */
    @Nullable
    static String[] structureWords(String path) {
        if (path.startsWith("mineshaft")) return new String[]{ "a mineshaft", "mineshaft" };
        if (path.startsWith("ruined_portal")) return new String[]{ "a ruined portal", "structure" };
        if (path.startsWith("ocean_ruin")) return new String[]{ "an ocean ruin", "structure" };
        if (path.startsWith("shipwreck")) return new String[]{ "a shipwreck", "structure" };
        return switch (path) {
            case "stronghold" -> new String[]{ "a stronghold", "structure" };
            case "ancient_city" -> new String[]{ "an ancient city", "structure" };
            case "desert_pyramid" -> new String[]{ "a desert temple", "structure" };
            case "jungle_pyramid" -> new String[]{ "a jungle temple", "structure" };
            case "trail_ruins" -> new String[]{ "old ruins", "structure" };
            case "buried_treasure" -> new String[]{ "buried treasure", "structure" };
            case "igloo" -> new String[]{ "an igloo", "structure" };
            case "trial_chambers" -> new String[]{ "the trial chambers", "structure" };
            default -> null;
        };
    }

    /** What was built down here: a mineshaft, a stronghold, a temple, an ancient city. */
    static void structures(ServerLevel level, VillageFolkEntity f, Delve d) {
        String built = structureAt(level, f.blockPosition());
        if (built == null) return;
        String[] words = structureWords(built);
        if (words == null) return;
        boolean shaft = words[1].equals("mineshaft");
        note(f, d, new Find(shaft ? Kind.MINESHAFT : Kind.STRUCTURE, words[0], f.blockPosition(), d.day, f.displayNameCap(), 0, 0),
            shaft ? "A mineshaft! Old timbers, rails — somebody dug here long ago." : "Look at this — " + words[0] + "! There'll be treasure about.");
    }

    /** Lava round it: noted as a danger to the miners. */
    static void lava(ServerLevel level, VillageFolkEntity f, Delve d) {
        BlockPos at = f.blockPosition();
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int dx = -8; dx <= 8; dx += 2) {
            for (int dz = -8; dz <= 8; dz += 2) {
                for (int dy = -4; dy <= 3; dy++) {
                    m.set(at.getX() + dx, at.getY() + dy, at.getZ() + dz);
                    FluidState fl = level.getFluidState(m);
                    if (!fl.is(Fluids.LAVA) || !fl.isSource()) continue;
                    note(f, d, new Find(Kind.LAVA, "a pool of lava", m.immutable(), d.day, f.displayNameCap(), depth(level, m), 0),
                        "Lava! Keep well back from it, everybody.");
                    return;
                }
            }
        }
    }

    // ------------------------------------------------------------------ noting it

    /** Noted for the report, if new to this trip and to the town's report; said out loud if it is. */
    static void note(VillageFolkEntity f, Delve d, Find x, @Nullable String say) {
        UUID village = f.ownerId();
        boolean fresh = merge(d.found, x);
        if (!fresh) return;
        if (village != null && x.kind() != Kind.VEIN && known(report(village), x)) return;
        if (say != null && f.tickCount - d.spokeTick > 60) {
            d.spokeTick = f.tickCount;
            FolkTalk.speak(f, say);
        }
        LOG.info("[MCA-CAVES] {} found {} ({}) at {}", f.displayNameCap(), x.label(), x.kind(), x.at().toShortString());
    }

    private static int near(Kind k) {
        return switch (k) {
            case CAVE, RAVINE -> 20;
            case VEIN -> 6;
            case MINESHAFT, STRUCTURE -> 64;
            case DUNGEON, SPAWNER -> 3;
            case CHEST -> 0;
            case LAVA -> 12;
        };
    }

    private static boolean same(Find x, Find y) {
        if (x.kind() != y.kind()) return false;
        int n = near(x.kind());
        if (x.at().distSqr(y.at()) > (double) n * n) return false;
        return switch (x.kind()) {
            case VEIN, MINESHAFT, STRUCTURE, DUNGEON, SPAWNER -> x.label().equals(y.label());
            default -> true;
        };
    }

    private static boolean known(List<Find> all, Find x) {
        for (Find y : all) if (same(y, x)) return true;
        return false;
    }

    /** Into a list of finds: new, or folded into the one already there. Returns whether it was new. */
    static boolean merge(List<Find> all, Find x) {
        for (int i = 0; i < all.size(); i++) {
            Find y = all.get(i);
            if (!same(y, x)) continue;
            Find m = switch (x.kind()) {
                case VEIN -> y.with(Math.max(y.a(), x.a()), y.b() + x.b());
                case CAVE, RAVINE -> y.with(Math.max(y.a(), x.a()), Math.max(y.b(), x.b()));
                case MINESHAFT, STRUCTURE, CHEST -> y.with(y.a() + x.a(), y.b() + x.b());
                default -> y;
            };
            all.set(i, m);
            return false;
        }
        all.add(x);
        return true;
    }

    private static void haul(Delve d, ItemStack s, int n) {
        d.haul.merge(s.getHoverName().getString().toLowerCase(Locale.ROOT), n, Integer::sum);
    }

    // ------------------------------------------------------------------ home

    /** Turn for home, along its own marks. */
    static void turnBack(ServerLevel level, VillageFolkEntity f, Scouts.Expedition e, String why) {
        if (e.returning) return;
        e.returning = true;
        e.why = why;
        e.crumb = e.trail.size() - 1;
        e.waypoint = null;
        e.detour = 0;
        e.best = Double.MAX_VALUE;
        e.gainedTick = f.tickCount;
        Delve d = e.delve;
        if (d != null) {
            d.vein.clear();
            d.chest = null;
            d.cart = null;
            d.target = null;
        }
        f.getNavigation().stop();
        FolkTalk.speak(f, why.startsWith("I got hurt") ? "That's enough for one day. Home, and a bandage."
            : d != null && d.mined + d.opened > 0 ? "A good day down there. Home, with a full pack!"
            : FolkTalk.pick(f.getRandom(), "Home, then. The dark will keep.", "Time to head back up."));
        LOG.info("[MCA-CAVES] {} turns for home: {}", f.displayNameCap(), why);
    }

    /** Back along its marks (on to the next when one cannot be got to); out of the ground by the miners' stairs when
     *  there is no walking out; a call for help when there is no getting out at all; then over the land to the town. */
    static boolean walkHome(ServerLevel level, VillageFolkEntity f, Scouts.Expedition e, Delve d) {
        BlockPos feet = f.blockPosition();
        long time = level.getDayTime() % 24000L;
        if (e.crumb < 0 && Scouts.flat(feet, e.home) <= 14 * 14) {
            home(level, f, e, d);
            return false;
        }
        BlockPos dest = e.crumb >= 0 && e.crumb < e.trail.size() ? e.trail.get(e.crumb) : e.home;
        double dist = e.crumb >= 0 ? feet.distSqr(dest) : Scouts.flat(feet, dest);
        if (e.crumb >= 0 && dist <= 3 * 3) {
            e.crumb--;
            e.best = Double.MAX_VALUE;
            e.gainedTick = f.tickCount;
            e.waypoint = null;
            e.walkTick = -1000;
            return true;
        }
        if (dist < e.best - 1.5) {
            e.best = dist;
            e.gainedTick = f.tickCount;
        } else if (f.tickCount - e.gainedTick > 240) {
            e.gainedTick = f.tickCount;
            e.detour++;
            e.best = Double.MAX_VALUE;
            if (e.crumb >= 0 && e.detour <= 3) {
                e.crumb--;                                           // the next mark along
                return true;
            }
            if (MineStairs.underground(level, feet)) {
                if (d.climbs < 3) {
                    d.climbs++;
                    e.crumb = -1;
                    f.getNavigation().stop();
                    f.enqueueFront(Job.mine(feet.getY(), MineStairs.OUT));
                    FolkTalk.speak(f, "No way back the way I came. I'll cut my own way up.");
                    LOG.info("[MCA-CAVES] {} climbs out by its own stairs from {} ({})", f.displayNameCap(), feet.toShortString(), d.climbs);
                    return true;
                }
                callForHelp(level, f, e, d);
                return true;
            }
            e.crumb = -1;
            e.waypoint = null;
            if (e.detour > 14) {
                callForHelp(level, f, e, d);
                return true;
            }
        }
        if (e.crumb >= 0) {
            if (f.getNavigation().isDone() || f.tickCount - e.walkTick > 60) {
                f.getNavigation().moveTo(dest.getX() + 0.5, dest.getY(), dest.getZ() + 0.5, time >= 11000 ? 1.2D : 1.0D);
                e.walkTick = f.tickCount;
            }
        } else {
            if (e.waypoint == null || Scouts.flat(feet, e.waypoint) <= 3 * 3) {
                e.waypoint = Scouts.chooseWaypoint(level, f, e.home, Math.min(6, e.detour));
                e.walkTick = -1000;
            }
            if (e.waypoint != null && (f.getNavigation().isDone() || f.tickCount - e.walkTick > 80)) {
                f.walkTo(e.waypoint, time >= 11000 ? 1.2D : 1.05D);
                e.walkTick = f.tickCount;
            }
        }
        f.hobbyNow = "on the way home from the caves";
        return true;
    }

    /** No way out it can find: what it found is told, the town's search party goes out for it, and it waits, cutting
     *  its way up as it can (MineStairs). Never lifted out. */
    static void callForHelp(ServerLevel level, VillageFolkEntity f, Scouts.Expedition e, Delve d) {
        UUID id = e.village;
        long day = level.getDayTime() / 24000L;
        for (Find x : d.found) record(id, x);
        Scouts.release(level, f, e);
        f.expedition(null);
        LOST.put(f.getUUID(), level.getGameTime());
        Villages.tell(id, day, f.displayNameCap() + " could not find the way out of the caves " + e.heading + " and called for help");
        FolkTalk.speak(f, "Help! Down here! I can't find the way out!");
        f.enqueueFront(Job.mine(f.blockPosition().getY(), MineStairs.OUT));
        Villages.Village v = Villages.get(id);
        boolean party = v != null && SearchParties.start(level, v, f, level.getGameTime());
        LOG.info("[MCA-CAVES] {} lost at {}, called for help (search party: {})", f.displayNameCap(), f.blockPosition().toShortString(), party);
    }

    /** Home: the finds into the stores, booked as its work; what it found into the report, and told. */
    static void home(ServerLevel level, VillageFolkEntity f, Scouts.Expedition e, Delve d) {
        Scouts.release(level, f, e);
        f.expedition(null);
        UUID id = e.village;
        Villages.Village v = Villages.get(id);
        long day = level.getDayTime() / 24000L;
        String who = f.displayNameCap();
        Map<String, Integer> stored = new LinkedHashMap<>();
        if (v != null) {
            var pack = f.getInventoryItems();
            int cobble = 0;
            for (int i = 0; i < pack.size(); i++) {
                ItemStack s = pack.get(i);
                if (s.isEmpty() || kit(s) || Homes.isKeepsake(s)) continue;
                if ((s.is(Items.COBBLESTONE) || s.is(Items.COBBLED_DEEPSLATE)) && cobble + s.getCount() <= 16) {
                    cobble += s.getCount();
                    continue;
                }
                ItemStack lot = s.copy();
                Economy.produced(f, lot.copy());
                Crafts.store(level, v, lot.copy());
                stored.merge(lot.getHoverName().getString().toLowerCase(Locale.ROOT), lot.getCount(), Integer::sum);
                pack.set(i, ItemStack.EMPTY);
            }
        }
        // The report, and what is news.
        List<String> big = new ArrayList<>();
        int fresh = 0;
        for (Find x : d.found) {
            boolean isNew = record(id, x);
            if (isNew) fresh++;
            if (!isNew) continue;
            switch (x.kind()) {
                case MINESHAFT, DUNGEON -> big.add(x.label() + " " + away(e.home, x.at()));
                case SPAWNER -> big.add(x.label() + " " + away(e.home, x.at()));
                case STRUCTURE -> big.add(x.label() + " " + away(e.home, x.at()));
                case RAVINE -> big.add("a ravine " + away(e.home, x.at()));
                default -> { }
            }
        }
        for (Find x : d.found) {
            if (x.kind() == Kind.CHEST && x.a() > 0 && !x.label().startsWith("an old") && !x.label().startsWith("a mineshaft")
                    && !x.label().startsWith("a dungeon")) big.add(x.label().replace("'s chest", "'s treasure"));
        }
        if (stored.getOrDefault("diamond", 0) > 0) big.add(0, "diamonds");
        if (d.explored && d.cave != null) markDone(id, d.cave);
        String haulWords = words(stored, 4);
        bookHaul(id, day, who, stored);
        String line = who + " came back from the caves " + e.heading + (stored.isEmpty() ? " empty-handed" : " with " + haulWords)
            + (big.isEmpty() ? "" : "; found " + String.join(", ", big.subList(0, Math.min(3, big.size()))));
        Villages.tell(id, day, line);
        for (String b : big.subList(0, Math.min(2, big.size()))) {
            if (b.equals("diamonds") || b.contains("mineshaft") || b.contains("dungeon") || b.contains("treasure") || b.contains("spawner")) {
                Villages.tell(id, day, "the cave dweller " + who + " found " + b);
            }
        }
        String last = "day " + (day + 1) + ": the caves " + e.heading + " — " + (stored.isEmpty() ? "nothing to bring home" : haulWords)
            + (big.isEmpty() ? "" : "; found " + String.join(", ", big.subList(0, Math.min(2, big.size()))))
            + (d.slain > 0 ? "; " + d.slain + (d.slain == 1 ? " monster" : " monsters") + " fought off" : "")
            + (d.torches > 0 ? "; " + d.torches + " torches set" : "") + " (" + e.why + ")";
        Ledger.note(id, "caves.last/" + f.getUUID(), last);
        if (!big.isEmpty() || !stored.isEmpty()) {
            REPORTS.computeIfAbsent(id, k -> new ArrayList<>()).add("Our cave dweller " + who + " brought up " + (stored.isEmpty() ? "nothing" : haulWords)
                + (big.isEmpty() ? "" : " and found " + big.get(0)) + ".");
        }
        if (!big.isEmpty()) f.persona().remember(day, "I went into the caves " + e.heading + " and found " + big.get(0), 4);
        FolkTalk.speak(f, stored.isEmpty() ? "Home! Nothing worth the carrying today, but the report's the longer for it."
            : big.isEmpty() ? "Home, with " + haulWords + ". Into the stores with it." : "Home! I found " + big.get(0) + "!");
        LOG.info("[MCA-CAVES] {} home from the caves {} ({}): mined {}, chests {}, slain {}, torches {}, {} finds ({} new); stored {}",
            who, e.heading, e.why, d.mined, d.opened, d.slain, d.torches, d.found.size(), fresh, stored);
    }

    private static String away(BlockPos home, BlockPos at) {
        return (int) Math.sqrt(Scouts.flat(home, at)) / 10 * 10 + " blocks " + Guide.direction(home, at);
    }

    /** "6 raw iron, 3 coal, a diamond": the most of a haul first. */
    static String words(Map<String, Integer> what, int most) {
        List<Map.Entry<String, Integer>> all = new ArrayList<>(what.entrySet());
        all.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, Integer> en : all) {
            if (out.size() >= most) break;
            out.add(en.getValue() == 1 ? JobMarket.a(en.getKey()) : en.getValue() + " " + en.getKey());
        }
        if (all.size() > most) out.add("more");
        return String.join(", ", out);
    }

    /** The day's haul, kept with the town (the last ten trips). */
    private static void bookHaul(UUID village, long day, String who, Map<String, Integer> stored) {
        String s = Ledger.note(village, "caves.haul");
        List<String> lines = new ArrayList<>();
        if (s != null && !s.isEmpty()) lines.addAll(List.of(s.split("\n")));
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Integer> en : stored.entrySet()) sb.append(sb.length() == 0 ? "" : ",").append(en.getKey().replace(',', ' ')).append('*').append(en.getValue());
        lines.add(day + "|" + who.replace('|', '/') + "|" + sb);
        while (lines.size() > 10) lines.remove(0);
        Ledger.note(village, "caves.haul", String.join("\n", lines));
    }

    /** The hauls the town keeps, newest first: "Day 12, Bram: 6 raw iron, 3 coal". */
    public static List<String> hauls(UUID village) {
        List<String> out = new ArrayList<>();
        String s = Ledger.note(village, "caves.haul");
        if (s == null || s.isEmpty()) return out;
        String[] lines = s.split("\n");
        for (int i = lines.length - 1; i >= 0; i--) {
            String[] p = lines[i].split("\\|", -1);
            if (p.length < 3) continue;
            Map<String, Integer> m = new LinkedHashMap<>();
            for (String item : p[2].split(",")) {
                int star = item.lastIndexOf('*');
                if (star <= 0) continue;
                try {
                    m.merge(item.substring(0, star), Integer.parseInt(item.substring(star + 1)), Integer::sum);
                } catch (NumberFormatException ignored) {
                    // an unreadable count: left out
                }
            }
            long day;
            try {
                day = Long.parseLong(p[0]);
            } catch (NumberFormatException ex) {
                continue;
            }
            out.add("Day " + (day + 1) + ", " + p[1] + ": " + (m.isEmpty() ? "nothing worth the carrying" : words(m, 6)));
        }
        return out;
    }

    /** For the morning assembly: what the cave dwellers brought up (said once). */
    public static List<String> reports(UUID village) {
        List<String> list = REPORTS.remove(village);
        return list == null ? List.of() : list;
    }

    // ------------------------------------------------------------------ the report

    /** Everything the town's cave dwellers have found, oldest first. */
    public static List<Find> report(UUID village) {
        List<Find> out = new ArrayList<>();
        String s = Ledger.note(village, "caves");
        if (s == null || s.isEmpty()) return out;
        for (String line : s.split("\n")) {
            Find f = Find.decode(line);
            if (f != null) out.add(f);
        }
        return out;
    }

    static void save(UUID village, List<Find> finds) {
        while (finds.size() > KEEP) {
            // Forget the least of it first: lava, then the chests looked into, then the oldest veins.
            int drop = 0;
            for (Kind k : new Kind[]{ Kind.LAVA, Kind.CHEST, Kind.VEIN }) {
                int at = -1;
                for (int i = 0; i < finds.size(); i++) if (finds.get(i).kind() == k) { at = i; break; }
                if (at >= 0) { drop = at; break; }
            }
            finds.remove(drop);
        }
        StringBuilder sb = new StringBuilder();
        for (Find f : finds) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(f.encode());
        }
        Ledger.note(village, "caves", sb.toString());
    }

    /** Into the town's report: new, or folded into what it holds there already. Returns whether it was new. */
    static boolean record(UUID village, Find f) {
        List<Find> all = report(village);
        boolean fresh = merge(all, f);
        save(village, all);
        return fresh;
    }

    /** Counts by kind: {caves, ravines, veins, ore mined, mineshafts, dungeons, spawners, structures, chests, lava}. */
    static int[] counts(List<Find> all) {
        int[] n = new int[10];
        for (Find x : all) {
            switch (x.kind()) {
                case CAVE -> n[0]++;
                case RAVINE -> n[1]++;
                case VEIN -> { n[2]++; n[3] += x.b(); }
                case MINESHAFT -> n[4]++;
                case DUNGEON -> n[5]++;
                case SPAWNER -> n[6]++;
                case STRUCTURE -> n[7]++;
                case CHEST -> n[8]++;
                case LAVA -> n[9]++;
            }
        }
        return n;
    }

    /** "4 caves, 6 veins (31 ore mined), a mineshaft, 2 spawners": the report in a line. */
    static String summary(List<Find> all) {
        int[] n = counts(all);
        List<String> out = new ArrayList<>();
        if (n[0] > 0) out.add(n[0] + (n[0] == 1 ? " cave" : " caves"));
        if (n[1] > 0) out.add(n[1] + (n[1] == 1 ? " ravine" : " ravines"));
        if (n[2] > 0) out.add(n[2] + (n[2] == 1 ? " vein" : " veins") + " (" + n[3] + " ore mined)");
        if (n[4] > 0) out.add(n[4] + (n[4] == 1 ? " mineshaft" : " mineshafts"));
        if (n[5] > 0) out.add(n[5] + (n[5] == 1 ? " dungeon" : " dungeons"));
        if (n[6] > 0) out.add(n[6] + (n[6] == 1 ? " spawner" : " spawners"));
        if (n[7] > 0) out.add(n[7] + " old " + (n[7] == 1 ? "structure" : "structures"));
        if (n[8] > 0) out.add(n[8] + (n[8] == 1 ? " chest" : " chests") + " looked into");
        if (n[9] > 0) out.add(n[9] + " lava " + (n[9] == 1 ? "pool" : "pools"));
        return out.isEmpty() ? "nothing yet" : String.join(", ", out);
    }

    /** For the board: the report in a line, and the latest big find. Null for a town with no cave dwellers and no report. */
    @Nullable
    public static String boardLine(UUID village) {
        List<Find> all = report(village);
        List<VillageFolkEntity> out = dwellers(village);
        if (all.isEmpty() && out.isEmpty()) return null;
        if (all.isEmpty()) return "Caves: " + out.size() + (out.size() == 1 ? " cave dweller" : " cave dwellers") + " out exploring — nothing in the report yet.";
        Villages.Village v = Villages.get(village);
        BlockPos home = v == null ? BlockPos.ZERO : v.centre();
        String latest = null;
        for (int i = all.size() - 1; i >= 0 && latest == null; i--) {
            Find x = all.get(i);
            if (x.kind() == Kind.MINESHAFT || x.kind() == Kind.DUNGEON || x.kind() == Kind.STRUCTURE || x.kind() == Kind.SPAWNER
                || x.kind() == Kind.VEIN && ("diamond".equals(x.label()) || "emerald".equals(x.label()))) {
                latest = (x.kind() == Kind.VEIN ? x.label() + "s" : x.label()) + " " + away(home, x.at());
            }
        }
        return "Caves: " + summary(all) + (latest == null ? "" : "; lately " + latest) + ".";
    }

    /** "What have the cave dwellers found?": anybody can say; a cave dweller gives the very spot. */
    public static String tell(VillageFolkEntity f) {
        UUID village = f.ownerId();
        if (village == null) return "I've no town to keep a report for.";
        List<Find> all = report(village);
        boolean me = f.stationTask() == StationTask.CAVE;
        if (all.isEmpty()) {
            if (wanted(village) == 0 && dwellers(village).isEmpty()) {
                return "Nobody goes down the caves for us yet: that's for an Iron Age town of " + FROM + " or more, with miners and a watch.";
            }
            return me ? "Nothing in the report yet. Give me a day or two down there." : "The cave dwellers haven't come back with anything yet.";
        }
        BlockPos from = f.blockPosition();
        List<Find> worth = new ArrayList<>();
        for (Find x : all) if (x.kind() != Kind.CHEST) worth.add(x);
        worth.sort(Comparator.comparingInt((Find x) -> rank(x)).thenComparingDouble(x -> x.at().distSqr(from)));
        StringBuilder sb = new StringBuilder(me ? "From the caves' report: " : "The cave dwellers have found: ");
        int n = 0;
        for (Find x : worth) {
            if (n == 5) break;
            if (n > 0) sb.append("; ");
            String what = x.kind() == Kind.VEIN ? x.label() + " (" + x.b() + " of " + x.a() + " mined)" : x.label();
            sb.append(what).append(", ").append((int) Math.sqrt(x.at().distSqr(from)) / 10 * 10).append(" blocks ").append(Guide.direction(from, x.at()));
            if (me) sb.append(" (").append(x.at().getX()).append(", ").append(x.at().getY()).append(", ").append(x.at().getZ()).append(")");
            n++;
        }
        sb.append(". All told: ").append(summary(all)).append('.');
        Scouts.Expedition e = f.expedition();
        if (e != null && e.delve != null) sb.append(" I'm down in the caves ").append(e.heading).append(" right now.");
        return sb.toString();
    }

    private static int rank(Find x) {
        return switch (x.kind()) {
            case MINESHAFT, DUNGEON, STRUCTURE -> 0;
            case VEIN -> "diamond".equals(x.label()) || "emerald".equals(x.label()) ? 0 : 2;
            case SPAWNER, RAVINE, CAVE -> 1;
            case LAVA -> 3;
            case CHEST -> 4;
        };
    }

    /** What it is doing, for "What are you up to?". */
    public static String doing(VillageFolkEntity f, net.minecraft.util.RandomSource r) {
        Scouts.Expedition e = f.expedition();
        if (e != null && e.delve != null) {
            Delve d = e.delve;
            if (e.returning) return "On my way home from the caves " + e.heading + (d.mined > 0 ? ", " + d.mined + " ore in the pack." : ".");
            if (d.phase == Delve.Phase.OUT) return "Off to the caves " + e.heading + ". Torches, pick and blade — all here.";
            if (d.veinOf != null) return "Mining " + d.veinOf + " down here. Stand back from the wall.";
            return FolkTalk.pick(r, "Down in the caves " + e.heading + ". Mind your head — and keep your voice down.",
                "Exploring. " + (d.torches > 0 ? d.torches + " torches set so far: that's the way home." : "It's dark as a cellar down here."));
        }
        if (LOST.containsKey(f.getUUID())) return "Lost! I couldn't find the way out of the caves. Somebody's coming for me — I hope.";
        String last = Ledger.note(f.ownerId(), "caves.last/" + f.getUUID());
        return last != null ? "Resting my legs. Last time down: " + last.substring(last.indexOf(':') + 1).trim() + "."
            : FolkTalk.pick(r, "Getting my kit ready for the caves. Out at first light.", "Going over the report. There's a lot of dark under this town.");
    }

    /** The cave dweller's card: where it went today and what it found, and its kit. Null for anybody else. */
    @Nullable
    public static String cardLine(VillageFolkEntity f) {
        if (f.stationTask() != StationTask.CAVE || f.isBaby()) return null;
        StringBuilder sb = new StringBuilder();
        Scouts.Expedition e = f.expedition();
        if (e != null && e.delve != null) {
            Delve d = e.delve;
            sb.append(e.returning ? "on the way home from the caves " : "out in the caves ").append(e.heading).append(" (").append(d.phase()).append(")");
            if (d.mined + d.opened + d.slain > 0) sb.append(": ").append(d.mined).append(" ore mined, ").append(d.opened).append(" chests, ").append(d.slain).append(" fought off");
            if (!d.haul.isEmpty()) sb.append("; carrying ").append(words(d.haul, 3));
        } else {
            String last = f.ownerId() == null ? null : Ledger.note(f.ownerId(), "caves.last/" + f.getUUID());
            sb.append(last != null ? "last out " + last : "not been down yet");
        }
        String kit = kitWords(f);
        if (!kit.isEmpty()) sb.append(". Kit: ").append(kit).append(", the town's");
        return sb.toString();
    }

    /** What it has of its kit, in a line: "iron chestplate, iron sword, iron pickaxe, 24 torches". */
    static String kitWords(VillageFolkEntity f) {
        List<String> out = new ArrayList<>();
        int armour = 0;
        String metal = null;
        for (EquipmentSlot slot : new EquipmentSlot[]{ EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET }) {
            ItemStack s = f.getItemBySlot(slot);
            if (s.getItem() instanceof ArmorItem) {
                armour++;
                if (metal == null) metal = BuiltInRegistries.ITEM.getKey(s.getItem()).getPath().split("_")[0];
            }
        }
        if (armour > 0) out.add(armour + (armour == 1 ? " piece" : " pieces") + " of " + metal + " armour");
        ItemStack blade = ItemStack.EMPTY;
        for (ItemStack s : f.getInventoryItems()) if (s.getItem() instanceof SwordItem && Workshop.blade(s) > Workshop.blade(blade)) blade = s;
        if (f.getMainHandItem().getItem() instanceof SwordItem && Workshop.blade(f.getMainHandItem()) > Workshop.blade(blade)) blade = f.getMainHandItem();
        if (!blade.isEmpty()) out.add(blade.getHoverName().getString().toLowerCase(Locale.ROOT));
        ItemStack pick = bestPick(f);
        if (!pick.isEmpty()) out.add(pick.getHoverName().getString().toLowerCase(Locale.ROOT));
        if (f.countCarried(s -> s.getItem() instanceof ShieldItem) > 0 || f.getOffhandItem().getItem() instanceof ShieldItem) out.add("a shield");
        int torches = f.countMatching(s -> s.is(Items.TORCH));
        if (torches > 0) out.add(torches + " torches");
        return String.join(", ", out);
    }

    /** The Caves page of the town's books (CityScreen's "Caves", client/CavesPage). */
    public static CompoundTag report(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        CompoundTag out = new CompoundTag();
        List<Find> all = report(id);
        out.putInt("range", range(id));
        out.putInt("wanted", wanted(id));
        out.putInt("from", FROM);
        out.putString("age", Villages.ageOf(id).label);
        out.putString("summary", summary(all));
        ListTag finds = new ListTag();
        for (Find x : all) {
            CompoundTag t = new CompoundTag();
            t.putString("kind", x.kind().name());
            t.putString("label", x.label());
            t.putInt("x", x.at().getX());
            t.putInt("y", x.at().getY());
            t.putInt("z", x.at().getZ());
            t.putInt("dx", x.at().getX() - v.centre().getX());
            t.putInt("dz", x.at().getZ() - v.centre().getZ());
            t.putInt("a", x.a());
            t.putInt("b", x.b());
            t.putLong("day", x.day());
            t.putString("by", x.by());
            finds.add(t);
        }
        out.put("finds", finds);
        ListTag folk = new ListTag();
        for (VillageFolkEntity f : dwellers(id)) {
            CompoundTag t = new CompoundTag();
            t.putString("name", f.displayNameCap());
            String card = cardLine(f);
            t.putString("card", card == null ? "" : card);
            Scouts.Expedition e = f.expedition();
            t.putBoolean("out", e != null && e.delve != null);
            if (e != null && e.delve != null) {
                t.putInt("dx", f.blockPosition().getX() - v.centre().getX());
                t.putInt("dz", f.blockPosition().getZ() - v.centre().getZ());
            }
            folk.add(t);
        }
        out.put("dwellers", folk);
        ListTag hauls = new ListTag();
        for (String h : hauls(id)) hauls.add(StringTag.valueOf(h));
        out.put("hauls", hauls);
        return out;
    }

    /** The whole of it, for /village caves: who goes down, the finds with their spots, the hauls. */
    public static List<String> page(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        List<String> out = new ArrayList<>();
        List<VillageFolkEntity> folk = dwellers(id);
        int want = wanted(id);
        out.add("The caves of " + Villages.name(id) + " (" + Villages.ageOf(id).label + ", " + Villages.headcount(id) + " folk): "
            + folk.size() + (folk.size() == 1 ? " cave dweller" : " cave dwellers") + ", " + want + " wanted"
            + (want == 0 ? " (from " + FROM + " folk in the Iron Age, with " + MINERS + " miners and a guard)" : "")
            + "; out to " + range(id) + " blocks.");
        for (VillageFolkEntity f : folk) out.add("  " + f.displayNameCap() + ": " + cardLine(f));
        List<Find> all = report(id);
        out.add("The report: " + summary(all) + ".");
        List<Find> sorted = new ArrayList<>(all);
        sorted.sort(Comparator.comparingInt(CaveDwellers::rank).thenComparingDouble(x -> x.at().distSqr(v.centre())));
        int n = 0;
        for (Find x : sorted) {
            if (n++ >= 24) { out.add("  ... and " + (sorted.size() - 24) + " more"); break; }
            String extra = switch (x.kind()) {
                case CAVE, RAVINE -> ", " + x.a() + " deep, " + x.b() + " open blocks";
                case VEIN -> ": " + x.a() + " seen, " + x.b() + " mined";
                case CHEST -> ": " + x.a() + " things taken";
                case MINESHAFT, STRUCTURE -> x.a() > 0 ? ": " + x.a() + " chests looked into" : "";
                case LAVA -> ", " + x.a() + " deep";
                default -> "";
            };
            out.add("  " + x.kind().name().toLowerCase(Locale.ROOT) + " " + x.label() + extra + " at " + x.at().getX() + " " + x.at().getY() + " "
                + x.at().getZ() + " (" + away(v.centre(), x.at()) + ", day " + (x.day() + 1) + ", " + x.by() + ")");
        }
        List<String> h = hauls(id);
        if (!h.isEmpty()) {
            out.add("The hauls:");
            for (String s : h.subList(0, Math.min(5, h.size()))) out.add("  " + s);
        }
        return out;
    }

    /** Tests and /village: the report in a line. */
    public static String debug(UUID village) {
        StringBuilder sb = new StringBuilder("caves report " + report(village).size());
        for (Find x : report(village)) sb.append("; ").append(x.kind().name().toLowerCase(Locale.ROOT)).append(' ').append(x.label())
            .append(" (").append(x.a()).append('/').append(x.b()).append(')');
        return sb.toString();
    }

    private static String capital(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    // ------------------------------------------------------------------ the command

    /**
     * /village caves: the town's cave dwellers, the report with its spots, the hauls. {@code books} opens the town's
     * books at the Caves page. Operators: {@code now} sends every cave dweller at home into the caves now (or takes
     * one up, if the town wants one and has none); {@code stage} cuts a small cave out beside where you stand, with
     * ore in its walls and an old chest, and sends the town's cave dweller into it, for the pictures.
     */
    public static LiteralArgumentBuilder<CommandSourceStack> command() {
        return Commands.literal("caves")
            .executes(CaveDwellers::cmdPage)
            .then(Commands.literal("books").executes(CaveDwellers::cmdBooks))
            .then(Commands.literal("now").requires(src -> src.hasPermission(2)).executes(ctx -> {
                Villages.Village v = here(ctx);
                if (v == null) return 0;
                ServerLevel level = ctx.getSource().getLevel();
                List<String> said = new ArrayList<>();
                if (dwellers(v.id()).isEmpty()) {
                    VillageFolkEntity f = appoint(level, v, level.getDayTime() / 24000L);
                    said.add(f == null ? "No cave dweller, and none taken up (" + wanted(v.id()) + " wanted)." : f.displayNameCap() + " took up the caves.");
                }
                for (VillageFolkEntity f : dwellers(v.id())) {
                    if (f.expedition() != null) { said.add(f.displayNameCap() + " is out already."); continue; }
                    boolean went = setOut(f, level, v, level.getDayTime() / 24000L, null);
                    said.add(f.displayNameCap() + (went ? " set out for the caves." : " did not go."));
                }
                ctx.getSource().sendSuccess(() -> Component.literal("CAVES " + String.join(" ", said)), false);
                return said.size();
            }))
            .then(Commands.literal("stage").requires(src -> src.hasPermission(2)).executes(ctx -> {
                List<String> out = stage(ctx.getSource().getLevel(), BlockPos.containing(ctx.getSource().getPosition()));
                ctx.getSource().sendSuccess(() -> Component.literal(String.join("\n", out)), false);
                return out.size();
            }));
    }

    @Nullable
    private static Villages.Village here(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level = ctx.getSource().getLevel();
        Villages.Village v = Villages.nearest(level, BlockPos.containing(ctx.getSource().getPosition()), Villages.VILLAGE_RANGE * 4);
        if (v == null && !Villages.every().isEmpty()) v = Villages.every().get(0);
        if (v == null) ctx.getSource().sendFailure(Component.literal("No village within reach."));
        return v;
    }

    private static int cmdPage(CommandContext<CommandSourceStack> ctx) {
        Villages.Village v = here(ctx);
        if (v == null) return 0;
        List<String> lines = page(ctx.getSource().getLevel(), v);
        String text = String.join("\n", lines);
        ctx.getSource().sendSuccess(() -> Component.literal(text), false);
        return lines.size();
    }

    private static int cmdBooks(CommandContext<CommandSourceStack> ctx) {
        Villages.Village v = here(ctx);
        if (v == null) return 0;
        if (!(ctx.getSource().getEntity() instanceof ServerPlayer p)) return cmdPage(ctx);
        CompoundTag books = Annals.snapshot(ctx.getSource().getLevel(), v);
        books.putString("page", "Caves");
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(p, new com.jrpetty.mcassistant.net.CityStatsPayload(books));
        return 1;
    }

    // ------------------------------------------------------------------ the stage

    /** The tag on the stage's cave dweller in its kit at the cave mouth (a showcase's kit, for nothing). */
    static final String LINEUP = "caves_lineup";

    /**
     * The pictures' stage: a small cave cut into a block of stone twelve blocks along from where it is run (east), a
     * ramp down into it from the west, ore in its walls (iron, coal, copper, a diamond, a little obsidian) and an old
     * chest with the world's dungeon loot in it; a cave dweller in the watch's iron kit stood at its mouth (a
     * showcase's, for nothing); and the town's own cave dweller (taken up if it has none) sent into it. Returns the
     * views to photograph ("VIEW name x y z lookx looky lookz") and where the cave is.
     */
    static List<String> stage(ServerLevel level, BlockPos at) {
        List<Entity> old = new ArrayList<>();
        for (Entity en : level.getAllEntities()) if (en.getTags().contains(LINEUP)) old.add(en);
        for (Entity en : old) en.discard();
        int x0 = at.getX() + 12, z0 = at.getZ();
        int g = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x0 + 12, z0);
        // The rock: stone from the surface down, twenty-five along and seventeen across.
        for (int x = x0; x <= x0 + 24; x++) {
            for (int z = z0 - 8; z <= z0 + 8; z++) {
                for (int y = g - 13; y <= g - 1; y++) level.setBlock(new BlockPos(x, y, z), Blocks.STONE.defaultBlockState(), 2);
                for (int y = g; y <= g + 6; y++) level.setBlock(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState(), 2);
            }
        }
        cut(level, x0, z0, g);
        BlockPos mouth = new BlockPos(x0 - 1, g, z0);
        BlockPos chamber = new BlockPos(x0 + 14, g - 7, z0);
        List<String> out = new ArrayList<>();
        out.add("CAVE " + chamber.getX() + " " + chamber.getY() + " " + chamber.getZ() + " mouth " + mouth.getX() + " " + mouth.getY() + " " + mouth.getZ());
        // The cave dweller in its kit at the mouth, for the first picture.
        VillageFolkEntity show = com.jrpetty.mcassistant.McAssistantMod.VILLAGE_FOLK.get().create(level);
        if (show != null) {
            show.moveTo(mouth.getX() - 1.5, mouth.getY(), mouth.getZ() + 1.5, -90.0F, 0.0F);
            show.setYHeadRot(-90.0F);
            show.setYBodyRot(-90.0F);
            show.makeShowcase(StationTask.CAVE);
            show.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
            show.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.IRON_CHESTPLATE));
            show.setItemSlot(EquipmentSlot.LEGS, new ItemStack(Items.IRON_LEGGINGS));
            show.setItemSlot(EquipmentSlot.FEET, new ItemStack(Items.IRON_BOOTS));
            show.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_PICKAXE));
            show.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.TORCH));
            show.rename("A cave dweller");
            show.addTag(LINEUP);
            level.addFreshEntity(show);
        }
        out.add("VIEW caves-1-mouth " + (mouth.getX() - 6) + " " + (mouth.getY() + 2) + " " + (mouth.getZ() + 5) + " "
            + (mouth.getX() - 1) + " " + (mouth.getY() + 1) + " " + (mouth.getZ() + 1));
        out.add("VIEW caves-2-vein " + (x0 + 14) + " " + (g - 6) + " " + (z0 + 3) + " " + (x0 + 21) + " " + (g - 6) + " " + z0);
        out.add("VIEW caves-3-chest " + (x0 + 12) + " " + (g - 6) + " " + (z0 + 1) + " " + (x0 + 10) + " " + (g - 7) + " " + (z0 - 5));
        // The town's cave dweller, into it.
        Villages.Village v = Villages.nearest(level, at, Villages.VILLAGE_RANGE * 4);
        if (v != null) {
            List<VillageFolkEntity> folk = dwellers(v.id());
            VillageFolkEntity f = folk.isEmpty() ? appoint(level, v, level.getDayTime() / 24000L) : folk.get(0);
            if (f == null) {
                // A town too small to want one: the nearest grown, idle-enough hand goes, for the pictures.
                for (AssistantEntity a : Villages.folkOf(v.id())) {
                    if (a instanceof VillageFolkEntity c && !c.isBaby() && !c.isShowcase() && c.stationTask() != StationTask.GUARD && c.expedition() == null) {
                        f = c;
                        break;
                    }
                }
                if (f != null) {
                    BlockPos post = post(v);
                    f.setStation(post, StationTask.CAVE);
                    f.assignPlot(WorkZone.around(post, 4, WorkZone.DEFAULT_DEPTH), "The Caves");
                }
            }
            if (f != null) {
                boolean sent = sendForTests(f, level, chamber);
                out.add("DWELLER " + f.displayNameCap() + (sent ? " sent into the cave" : " could not go") + " from "
                    + f.blockPosition().getX() + " " + f.blockPosition().getY() + " " + f.blockPosition().getZ());
            }
        }
        return out;
    }

    /** The cave itself: a ramp down from the west, a chamber thirteen by eleven and four high, ore in its walls, an old
     *  chest by its north wall. */
    private static void cut(ServerLevel level, int x0, int z0, int g) {
        BlockState air = Blocks.AIR.defaultBlockState();
        // A level way up to the mouth from the west, whatever the ground does there.
        for (int x = x0 - 6; x <= x0 - 1; x++) {
            for (int z = z0 - 2; z <= z0 + 1; z++) {
                level.setBlock(new BlockPos(x, g - 1, z), Blocks.STONE.defaultBlockState(), 2);
                for (int y = g; y <= g + 3; y++) level.setBlock(new BlockPos(x, y, z), air, 2);
            }
        }
        // The ramp: seven steps down, two wide, three high.
        for (int k = 1; k <= 7; k++) {
            int x = x0 - 1 + k;
            for (int z = z0 - 1; z <= z0; z++) {
                for (int y = g - k; y <= g - k + 2; y++) level.setBlock(new BlockPos(x, y, z), air, 2);
            }
        }
        // The chamber.
        for (int x = x0 + 7; x <= x0 + 19; x++) {
            for (int z = z0 - 5; z <= z0 + 5; z++) {
                for (int y = g - 7; y <= g - 4; y++) level.setBlock(new BlockPos(x, y, z), air, 2);
            }
        }
        // Ore in the walls: iron on the east, coal and copper on the south, a diamond and obsidian on the north.
        int e = x0 + 20;
        for (int[] o : new int[][]{ { e, g - 6, z0 - 1 }, { e, g - 6, z0 }, { e, g - 5, z0 }, { e, g - 5, z0 + 1 }, { e + 1, g - 6, z0 } }) {
            level.setBlock(new BlockPos(o[0], o[1], o[2]), Blocks.IRON_ORE.defaultBlockState(), 2);
        }
        for (int x = x0 + 9; x <= x0 + 11; x++) level.setBlock(new BlockPos(x, g - 6, z0 + 6), Blocks.COAL_ORE.defaultBlockState(), 2);
        level.setBlock(new BlockPos(x0 + 14, g - 5, z0 + 6), Blocks.COPPER_ORE.defaultBlockState(), 2);
        level.setBlock(new BlockPos(x0 + 16, g - 6, z0 - 6), Blocks.DIAMOND_ORE.defaultBlockState(), 2);
        level.setBlock(new BlockPos(x0 + 13, g - 6, z0 - 6), Blocks.OBSIDIAN.defaultBlockState(), 2);
        level.setBlock(new BlockPos(x0 + 14, g - 6, z0 - 6), Blocks.OBSIDIAN.defaultBlockState(), 2);
        // The old chest, with the world's dungeon loot in it still to be rolled.
        BlockPos chest = new BlockPos(x0 + 10, g - 7, z0 - 5);
        level.setBlock(chest, Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.SOUTH), 2);
        if (level.getBlockEntity(chest) instanceof ChestBlockEntity c) c.setLootTable(BuiltInLootTables.SIMPLE_DUNGEON, level.getRandom().nextLong());
    }

    /** Tests: the stage's cave cut at this spot (its ramp from the west at x0, the surface at g). */
    public static void cutForTests(ServerLevel level, int x0, int z0, int g) {
        cut(level, x0, z0, g);
    }
}
