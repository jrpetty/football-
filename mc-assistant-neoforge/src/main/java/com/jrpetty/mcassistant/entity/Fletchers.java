package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.village.Ledger;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import javax.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * [fletcher] The fletcher: the watch's arrows, made the way a player makes them, out of what the town really has.
 *
 * <p><b>The trade.</b> A Stone Age town whose watch carries bows (or that has built the range) takes up a fletcher: one,
 * and a second at sixty folk (Villages' slot; appoint fills the place from the town's own when nobody takes it up of
 * themselves). It works at a fletching table (two flint and four planks, out of the stores) in the fletcher's hut, a
 * small timber house the builders put up once the trade has opened (blueprints/fletcher.txt); before the hut stands,
 * the table is set down by its post on the square, and carried into the hut when it is built.
 *
 * <p><b>Its makings, all real.</b>
 * <ul>
 * <li><b>Flint</b>, out of gravel. The miners and the diggers bank their gravel in the stores; the fletcher sets it
 *     down on its sifting floor in front of the hut and breaks it, a block at a time, with the stores' shovel if there
 *     is one: what the game's own loot gives for it (a flint one time in ten; the gravel itself otherwise) goes back
 *     into the stores, and the gravel is set down again.</li>
 * <li><b>Feathers</b>, from the coop. The rancher culls an old hen when the pen has more than it keeps, and the town
 *     with a fletcher culls the hens first while the stores are short of feathers (Drover.surplus); a pen with no hens
 *     at all fetches a wild pair home. The feathers come home in the rancher's pack with the meat.</li>
 * <li><b>Sticks</b> from the stores' planks (two planks, four sticks), <b>string</b> off the spiders the watch and
 *     the hunters kill, and the smelter's <b>iron</b>.</li>
 * </ul>
 *
 * <p><b>What it makes</b>, a piece of work at a time, as the crafts do (Crafts): arrows (a flint, a stick and a feather
 * make four); bows (three sticks and three string) for every guard without one and a spare; crossbows (three sticks,
 * two string, an ingot and a tripwire hook of an ingot, a stick and a plank) for the town's best archers once the
 * town is in the Iron Age; spectral arrows (four glowstone dust round an arrow make two) once the Nether's glowstone is
 * in the stores; targets for the range's butts (a bale of hay and four redstone), and a redstone lamp over each butt,
 * which the target's pulse lights when an arrow strikes home. Everything it makes goes into the stores, booked as its
 * work (Economy: the Production page, and the fletcher's book).
 *
 * <p><b>The watch, kept stocked.</b> Every guard with a bow goes up with a quiver of thirty-two arrows; one down to
 * sixteen is topped up out of the stores (WatchKit.fit, and the fletcher's own round). The stores keep a reserve of
 * thirty-two arrows a guard for a raid (Budget does not sell them), which is what the fletcher makes to first. After a
 * raid every quiver is filled at once, and the fletcher sets to making the reserve good. A guard with an empty quiver
 * and an empty store is on the board for everybody to see.
 *
 * <p><b>Practice.</b> On a quiet working afternoon the fletcher runs practice at the range: the guards who need it most
 * take their turn at a butt, ten real arrows each (Archery), while the fletcher calls the shots; the arrows are pulled
 * and go back into the stores, and the fletcher sweeps up any that went astray. Every session at the butts (the
 * morning's too) steadies a guard's aim, for good: its arrows fly truer at the butts, and in a fight the spread of its
 * shots is less (spread). Its best (8 of 10) is on its card.
 *
 * <p>The town's books: the board, the chronicle (its first arrows, a record at the butts, a raid's restock), the card,
 * the trade's book, /village fletcher, and the smoke stage's pictures.
 */
public final class Fletchers {

    private Fletchers() {}

    private static final org.slf4j.Logger LOG = com.mojang.logging.LogUtils.getLogger();

    /** The fletcher's hut. */
    public static final String STRUCTURE = "fletcher";
    /** A guard's quiver once the town keeps a fletcher, and how low it runs before it is filled again. */
    public static final int QUIVER = 32, QUIVER_LOW = 16;
    /** The raid's reserve the stores keep, arrows to a guard. */
    public static final int RESERVE_EACH = 32;
    /** Arrows made past the reserve, for the shop to sell. */
    static final int FOR_THE_SHOP = 48;
    /** Spectral arrows the stores keep, for the night the raiders come. */
    static final int SPECTRAL_KEPT = 16;
    /** A turn at the butts at the fletcher's practice, and how many of the watch have a turn. */
    public static final int PRACTICE_ARROWS = 10, PRACTICE_GUARDS = 3;
    /** The quiet of a working afternoon, for practice. */
    static final long PRACTICE_FROM = 6000L, PRACTICE_TILL = 10500L;
    /** A piece of work this often (ticks), for a new hand, as the crafts have it (Crafts.EVERY). */
    static final int EVERY = 300;
    /** The town takes up a second fletcher at this many folk. */
    public static final int TWO_AT = 60;
    /** Crafts at the table in one piece of work (four arrows each), and gravel broken in one sitting. */
    static final int CRAFTS_A_PIECE = 4, SIFTS_A_PIECE = 6;
    /** In the hut's drawing (across, toward the back): the fletching table's place, and the sifting floor out front. */
    static final int TABLE_DX = 0, TABLE_DZ = 1, SIFT_DX = 2, SIFT_DZ = -3;

    // ------------------------------------------------------------------ the town's books, kept with the world

    /** One guard's eye: how far practice has steadied it (nought to one), its best at the butts, its sessions. */
    static final class Aim {
        double aim;
        int bestHits, bestShot, sessions;
    }

    /** What a town's fletching has come to, all told, and where its table stands. */
    static final class Town {
        int flint, gravel, arrows, bows, crossbows, spectral, targets, lamps, hooks, restocks, quivered, practices, strays;
        long firstArrows = -1, practisedDay = -1;
        @Nullable BlockPos table;
        final Map<UUID, Aim> aims = new HashMap<>();
        final Map<UUID, String> names = new HashMap<>();
    }

    private static final Map<UUID, Town> TOWNS = new ConcurrentHashMap<>();
    /** Each fletcher's last piece of work (its tickCount). */
    private static final Map<UUID, Integer> LAST = new ConcurrentHashMap<>();
    /** What each town's fletchers made today, for the card and the books. */
    private static final Map<UUID, Deque<String>> TODAY = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> TODAY_DAY = new ConcurrentHashMap<>();
    /** The towns' per-village looks (game time), and the day a town last asked for a fletcher. */
    private static final Map<UUID, Long> LOOKED = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> APPOINTED = new ConcurrentHashMap<>();
    /** Guards with an empty quiver and nothing in the stores to fill it: the board's word, by village. */
    private static final Map<UUID, List<String>> EMPTY = new ConcurrentHashMap<>();

    public static void resetForTests() {
        TOWNS.clear();
        LAST.clear();
        TODAY.clear();
        TODAY_DAY.clear();
        LOOKED.clear();
        APPOINTED.clear();
        EMPTY.clear();
        PRACTICE.clear();
    }

    static Town town(UUID village) {
        return TOWNS.computeIfAbsent(village, Fletchers::load);
    }

    private static final String BOOKS = "fletcher.books", AIMS = "fletcher.aims", TABLE = "fletcher.table";

    private static Town load(UUID village) {
        Town t = new Town();
        Map<String, String> m = parse(Ledger.note(village, BOOKS));
        t.flint = num(m, "flint");
        t.gravel = num(m, "gravel");
        t.arrows = num(m, "arrows");
        t.bows = num(m, "bows");
        t.crossbows = num(m, "crossbows");
        t.spectral = num(m, "spectral");
        t.targets = num(m, "targets");
        t.lamps = num(m, "lamps");
        t.hooks = num(m, "hooks");
        t.restocks = num(m, "restocks");
        t.quivered = num(m, "quivered");
        t.practices = num(m, "practices");
        t.strays = num(m, "strays");
        t.firstArrows = m.containsKey("first") ? num(m, "first") : -1;
        t.practisedDay = m.containsKey("practised") ? num(m, "practised") : -1;
        String table = Ledger.note(village, TABLE);
        if (table != null && !table.isEmpty()) {
            String[] p = table.split(",");
            try {
                t.table = new BlockPos(Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2]));
            } catch (RuntimeException ignored) { }
        }
        String aims = Ledger.note(village, AIMS);
        if (aims != null && !aims.isEmpty()) {
            for (String one : aims.split(";")) {
                String[] f = one.split(":");
                if (f.length < 5) continue;
                try {
                    Aim a = new Aim();
                    a.aim = Integer.parseInt(f[1]) / 1000.0;
                    a.bestHits = Integer.parseInt(f[2]);
                    a.bestShot = Integer.parseInt(f[3]);
                    a.sessions = Integer.parseInt(f[4]);
                    UUID g = UUID.fromString(f[0]);
                    t.aims.put(g, a);
                    if (f.length > 5) t.names.put(g, f[5]);
                } catch (RuntimeException ignored) { }
            }
        }
        return t;
    }

    static void save(UUID village, Town t) {
        Ledger.note(village, BOOKS, "flint=" + t.flint + ";gravel=" + t.gravel + ";arrows=" + t.arrows + ";bows=" + t.bows
            + ";crossbows=" + t.crossbows + ";spectral=" + t.spectral + ";targets=" + t.targets + ";lamps=" + t.lamps + ";hooks=" + t.hooks
            + ";restocks=" + t.restocks + ";quivered=" + t.quivered + ";practices=" + t.practices + ";strays=" + t.strays
            + ";first=" + t.firstArrows + ";practised=" + t.practisedDay);
        if (t.table != null) Ledger.note(village, TABLE, t.table.getX() + "," + t.table.getY() + "," + t.table.getZ());
        List<String> aims = new ArrayList<>();
        for (Map.Entry<UUID, Aim> e : t.aims.entrySet()) {
            Aim a = e.getValue();
            String name = t.names.getOrDefault(e.getKey(), "").replace(":", " ").replace(";", " ");
            aims.add(e.getKey() + ":" + Math.round(a.aim * 1000) + ":" + a.bestHits + ":" + a.bestShot + ":" + a.sessions + ":" + name);
        }
        Ledger.note(village, AIMS, String.join(";", aims));
    }

    private static Map<String, String> parse(@Nullable String s) {
        Map<String, String> out = new HashMap<>();
        if (s == null || s.isEmpty()) return out;
        for (String part : s.split(";")) {
            int eq = part.indexOf('=');
            if (eq > 0) out.put(part.substring(0, eq), part.substring(eq + 1));
        }
        return out;
    }

    private static int num(Map<String, String> m, String k) {
        try {
            return Integer.parseInt(m.getOrDefault(k, "0").trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // ------------------------------------------------------------------ the trade, and who takes it

    /** Grown, alive and no showcase: the town's guards with a bow or a crossbow to hand. */
    static int bowmen(UUID village) {
        int n = 0;
        for (VillageFolkEntity g : WatchKit.watch(village)) if (g.countCarried(AssistantEntity.RANGED_WEAPON) > 0) n++;
        return n;
    }

    /**
     * Does the town want a fletcher: a Stone Age town (or later) whose watch carries bows, or that has built the range.
     * One already at it keeps the trade open (a guard's bow broke this morning; the town still wants its arrows).
     */
    public static boolean wanted(@Nullable UUID village) {
        if (village == null || Villages.ageOf(village).ordinal() < Villages.Age.STONE.ordinal()) return false;
        if (Archery.of(village) != null || keeps(village)) return true;
        return bowmen(village) > 0;
    }

    /** How many fletchers the town wants: one, and a second at sixty folk. */
    public static int hands(@Nullable UUID village) {
        return village != null && Villages.headcount(village) >= TWO_AT ? 2 : 1;
    }

    /** Does the town keep a fletcher? */
    public static boolean keeps(@Nullable UUID village) {
        return !fletchers(village).isEmpty();
    }

    /** The town's fletchers, the best at it first. */
    static List<VillageFolkEntity> fletchers(@Nullable UUID village) {
        List<VillageFolkEntity> out = new ArrayList<>();
        if (village == null) return out;
        for (AssistantEntity a : Villages.folkOf(village)) {
            if (a instanceof VillageFolkEntity f && f.stationTask() == StationTask.FLETCHER && !f.isBaby() && f.isAlive() && !f.isShowcase()) out.add(f);
        }
        out.sort(Comparator.comparingInt((VillageFolkEntity f) -> f.tradeLevel(StationTask.FLETCHER)).reversed());
        return out;
    }

    /**
     * How well a folk would do as the town's fletcher, for appoint: its years at the trade first, then a hunter (it
     * knows a bow and an arrow), a hand with nothing to do, and a quiet, careful nature. Never one of a trade the town
     * is short of, a craft's own hand, the watch itself, the storehouse's, a cave dweller, a scout or the ferryman.
     * Integer.MIN_VALUE: not a candidate.
     */
    static int fitness(VillageFolkEntity f, UUID village) {
        if (f.isBaby() || f.isShowcase() || f.isHired() || !f.isAlive() || f.trip() != null || f.expedition() != null) return Integer.MIN_VALUE;
        StationTask t = f.stationTask();
        if (t == StationTask.FLETCHER) return 1000 + f.tradeLevel(t);
        if (t.isCraft() || t == StationTask.GUARD || t == StationTask.STORE || t == StationTask.HAUL || t == StationTask.CAVE
            || t == StationTask.SCOUT || t == StationTask.FERRY) return Integer.MIN_VALUE;
        if (t != StationTask.NONE && Villages.share(village, t) < 0.5) return Integer.MIN_VALUE;
        return f.tradeLevel(StationTask.FLETCHER) * 5 + (t == StationTask.NONE ? 30 : t == StationTask.HUNT ? 24 : 0)
            + f.tradeLevel(StationTask.HUNT) * 2 + (f.life().has(Social.Trait.SHY) ? 8 : 0)
            + (f.life().has(Social.Trait.HARDWORKING) ? 4 : 0) + (f.life().has(Social.Trait.EASYGOING) ? -3 : 0);
    }

    /**
     * The place filled from the town's own folk, if the trade is open and short of its hands: the best on paper (fitness)
     * takes it up, a hunter for choice. Returns who, or null.
     *
     * <p>[interviews] The seam: the shortlist is the few best by fitness. Once the town holds interviews for its places
     * (Interviews), the place goes to whoever wins the interview from this shortlist; till then the best on paper has it.
     */
    @Nullable
    public static VillageFolkEntity appoint(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        if (!wanted(id) || fletchers(id).size() >= hands(id)) return null;
        List<VillageFolkEntity> shortlist = shortlist(id);
        if (shortlist.isEmpty()) return null;
        VillageFolkEntity best = shortlist.get(0);
        StationTask was = best.stationTask();
        if (!best.takeUpTrade(StationTask.FLETCHER)) best.setJob(StationTask.FLETCHER);
        if (best.stationTask() != StationTask.FLETCHER) return null;
        long day = level.getDayTime() / 24000L;
        Villages.tell(id, day, best.displayNameCap() + " " + (was == StationTask.NONE ? "took up" : "gave up " + was.label + " for")
            + " fletching: the watch's arrows are to be made in the town");
        best.persona().remember(day, "I became the town's fletcher", 4);
        FolkTalk.speak(best, FolkTalk.pick(level.getRandom(), "Fletcher, me! Flint, stick and feather: there's an art to it.",
            "The watch's arrows are mine to make now. Every quiver full, that's the rule.",
            "I've always had a feel for a good shaft. Right: where's the gravel?"));
        LOG.info("[MCA-FLETCHER] {} of {} took up fletching (was {})", best.displayNameCap(), Villages.name(id), was);
        return best;
    }

    /** The few best for the place, best first ([interviews] the seam's shortlist). */
    public static List<VillageFolkEntity> shortlist(UUID village) {
        List<VillageFolkEntity> out = new ArrayList<>();
        Map<VillageFolkEntity, Integer> score = new HashMap<>();
        for (AssistantEntity a : Villages.folkOf(village)) {
            if (!(a instanceof VillageFolkEntity f) || f.stationTask() == StationTask.FLETCHER) continue;
            int s = fitness(f, village);
            if (s == Integer.MIN_VALUE) continue;
            score.put(f, s + Interviews.preferred(village, "fletcher", f));     // [interviews] the panel's choice first
            out.add(f);
        }
        out.sort((a, b) -> score.get(b) - score.get(a));
        return out.size() > 4 ? new ArrayList<>(out.subList(0, 4)) : out;
    }

    // ------------------------------------------------------------------ the hut, the table, the sifting floor

    @Nullable
    public static Ledger.Building hut(@Nullable UUID village) {
        if (village == null) return null;
        for (Ledger.Building b : Ledger.buildings(village)) if (b.structure().equals(STRUCTURE)) return b;
        return null;
    }

    /** Does the town want its fletcher's hut built: a fletcher at work and none yet (Villages' list of amenities). */
    public static boolean hutWanted(@Nullable UUID village) {
        return village != null && keeps(village) && !Villages.hasBuilt(village, STRUCTURE) && hut(village) == null;
    }

    public static String why(UUID village) {
        VillageFolkEntity f = fletchers(village).isEmpty() ? null : fletchers(village).get(0);
        return "a fletcher's hut, a fletching table and a sifting floor for the gravel: "
            + (f == null ? "the town's fletcher" : f.displayNameCap()) + " makes the watch's arrows at a table on the square till it stands";
    }

    static BlockPos at(Ledger.Building b, int dx, int dz) {
        return b.anchor().relative(b.facing().getClockWise(), dx).relative(b.facing(), dz);
    }

    /** Where the fletching table belongs: in the hut, on the drawing's place for it, once the hut stands. */
    @Nullable
    static BlockPos tableSpot(UUID village) {
        Ledger.Building b = hut(village);
        return b == null ? null : at(b, TABLE_DX, TABLE_DZ);
    }

    /** Where the fletcher works: at its table if it has one, else its post. */
    static BlockPos bench(VillageFolkEntity f, UUID village) {
        Town t = town(village);
        if (t.table != null) return t.table;
        return f.workZone() != null ? f.workZone().center() : f.blockPosition();
    }

    /**
     * The fletching table: made of two of the stores' flint and four of their planks (the game's recipe), and set down
     * in the hut on the drawing's place; before the hut stands, on the floor by the fletcher's post. A table set down
     * by the post is carried into the hut when it goes up. Returns what was done, or null.
     */
    @Nullable
    static String table(ServerLevel level, Villages.Village v, VillageFolkEntity f) {
        UUID id = v.id();
        Town t = town(id);
        BlockPos home = tableSpot(id);
        if (t.table != null && level.isLoaded(t.table) && !level.getBlockState(t.table).is(Blocks.FLETCHING_TABLE)) {
            t.table = null;                                                 // broken, or taken: another made
            save(id, t);
        }
        if (t.table != null && (home == null || t.table.equals(home))) return null;
        if (t.table != null) {
            // The hut stands: the table off the square and into it.
            if (!level.isLoaded(home) || !level.getBlockState(home).canBeReplaced()) return null;
            level.removeBlock(t.table, false);
            level.setBlock(home, Blocks.FLETCHING_TABLE.defaultBlockState(), 3);
            level.playSound(null, home, SoundEvents.WOOD_PLACE, SoundSource.BLOCKS, 1.0F, 1.0F);
            t.table = home;
            save(id, t);
            return "carried the fletching table into the hut";
        }
        BlockPos at = home != null && level.isLoaded(home) && level.getBlockState(home).canBeReplaced() ? home
            : Trades.floorSpot(level, f.workZone() != null ? f.workZone().center() : f.blockPosition(), 3);
        if (at == null) return null;
        // The stores' own table, else two flint and four planks.
        boolean ready = Crafts.take(level, v, s -> s.is(Items.FLETCHING_TABLE), 1);
        if (!ready) {
            if (Crafts.stock(level, v, s -> s.is(Items.FLINT)) < 2 || !Crafts.planks(level, v, 4)) return null;
            if (!Crafts.take(level, v, s -> s.is(Items.FLINT), 2)) return null;
            if (!Crafts.take(level, v, s -> s.is(ItemTags.PLANKS), 4)) {
                Crafts.store(level, v, new ItemStack(Items.FLINT, 2));
                return null;
            }
        }
        level.setBlock(at, Blocks.FLETCHING_TABLE.defaultBlockState(), 3);
        level.playSound(null, at, SoundEvents.WOOD_PLACE, SoundSource.BLOCKS, 1.0F, 1.0F);
        t.table = at.immutable();
        save(id, t);
        return ready ? "set the fletching table down" : "made a fletching table of two flint and four planks";
    }

    /**
     * The sifting floor: in front of the hut, beside its door; before the hut stands, a free spot on the floor by the
     * table. Somewhere solid to set the gravel down, with air above it.
     */
    @Nullable
    static BlockPos siftSpot(ServerLevel level, UUID village, BlockPos near) {
        Ledger.Building b = hut(village);
        if (b != null) {
            BlockPos p = at(b, SIFT_DX, SIFT_DZ);
            for (int dy : new int[]{ 0, 1, -1 }) {
                BlockPos q = p.above(dy);
                if (level.isLoaded(q) && level.getBlockState(q).isAir()
                    && level.getBlockState(q.below()).isFaceSturdy(level, q.below(), Direction.UP)) return q;
            }
        }
        return Trades.floorSpot(level, near, 3);
    }

    // ------------------------------------------------------------------ the fletcher's day

    /**
     * The fletcher's day (its station's work, AssistantEntity): the practice at the range, if it is running one; else at
     * its table, a piece of work every so often (a little slower without its hut). True while it is about its work.
     */
    public static boolean duty(VillageFolkEntity f, ServerLevel level) {
        UUID id = f.ownerId();
        Villages.Village v = id == null ? null : Villages.get(id);
        if (v == null || f.isBaby()) return false;
        Practice p = PRACTICE.get(id);
        if (p != null && p.fletcher.equals(f.getUUID())) return runPractice(f, level, v, p);
        int every = f.pacedTicks(EVERY, 100);
        if (hut(id) == null) every = every * 3 / 2;
        int last = LAST.getOrDefault(f.getUUID(), -100000);
        if (f.tickCount - last < every && f.tickCount >= last) return toBench(f, level, id);
        LAST.put(f.getUUID(), f.tickCount);
        if (toBench(f, level, id) && town(id).table != null) return true;   // still walking to it: the work waits for it
        return work(f, level, v) != null;
    }

    /** To its table (or its post), if it is not there. True while it walks. */
    static boolean toBench(VillageFolkEntity f, ServerLevel level, UUID village) {
        BlockPos b = bench(f, village);
        if (f.blockPosition().distSqr(b) <= 3 * 3) return false;
        if (f.getNavigation().isDone() || f.tickCount % 60 == 0) f.walkTo(b, 0.9D);
        return true;
    }

    /** One piece of the fletcher's work, now: what it made, or null. Booked as its making (Economy). */
    @Nullable
    public static String work(VillageFolkEntity f, ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        String made;
        Economy.openCraft(id, StationTask.FLETCHER);
        try {
            made = piece(level, v, f);
        } finally {
            Economy.closeCraft();
        }
        int handed = fillQuivers(level, v);
        if (handed > 0) f.brain("filled the watch's quivers: " + handed + " arrows");
        issueCrossbows(level, v);
        if (made == null) return handed > 0 ? "the watch's quivers filled" : null;
        f.swing(InteractionHand.MAIN_HAND);
        level.playSound(null, f.blockPosition(), made.contains("flint") && made.contains("gravel") ? SoundEvents.GRAVEL_BREAK
            : SoundEvents.VILLAGER_WORK_FLETCHER, SoundSource.NEUTRAL, 0.8F, 1.0F);
        f.note(AssistantEntity.Deed.THINGS_MADE, 1);
        f.brain("made " + made);
        noteToday(level, id, made);
        if (f.getRandom().nextInt(5) == 0) FolkTalk.speak(f, said(f.getRandom(), made));
        return made;
    }

    private static String said(RandomSource r, String made) {
        if (made.contains("gravel")) return FolkTalk.pick(r, "Gravel, gravel... flint! There's one.", "One in ten, if the gravel's kind.");
        if (made.contains("arrows")) return FolkTalk.pick(r, "Flint, stick, feather. Four more for the watch.", "Straight and true, these.");
        if (made.contains("crossbow")) return "A crossbow for the best eye on the wall.";
        if (made.contains("bow")) return FolkTalk.pick(r, "A good bow, this. It'll pull sweet.", "Strung and ready.");
        return "Done: " + made + ".";
    }

    /** What the fletcher sees to first: its table, the raid's reserve, the bows, then the rest. */
    @Nullable
    static String piece(ServerLevel level, Villages.Village v, VillageFolkEntity f) {
        UUID id = v.id();
        Town t = town(id);
        String done;
        // The table first, and its two flint sifted out of the gravel if the stores have none.
        if (t.table == null && Crafts.stock(level, v, s -> s.is(Items.FLINT)) < 2) {
            done = siftIfShort(level, v, f, t);
            if (done != null) return done;
        }
        done = table(level, v, f);
        if (done != null) return done;
        int arrows = Crafts.stock(level, v, s -> s.is(Items.ARROW));
        int reserve = reserve(id);
        // The raid's reserve first: arrows while there are makings, flint sifted out of the gravel when it is short.
        if (arrows < reserve) {
            done = arrows(level, v, t, CRAFTS_A_PIECE);
            if (done != null) return done;
            done = siftIfShort(level, v, f, t);
            if (done != null) return done;
        }
        done = bow(level, v, t);
        if (done != null) return done;
        done = crossbow(level, v, t);
        if (done != null) return done;
        done = target(level, v, t);
        if (done != null) return done;
        done = lamp(level, v, f, t);
        if (done != null) return done;
        done = spectral(level, v, t);
        if (done != null) return done;
        // The reserve made: arrows for the shop to sell, while the makings are plentiful.
        if (arrows < reserve + FOR_THE_SHOP && Crafts.stock(level, v, s -> s.is(Items.FEATHER)) > 4) {
            done = arrows(level, v, t, 2);
            if (done != null) return done + " for the shop";
            done = siftIfShort(level, v, f, t);
            if (done != null) return done;
        }
        return null;
    }

    /** The raid's reserve the stores keep: thirty-two arrows a guard, and a turn at the butts besides. */
    public static int reserve(UUID village) {
        return Math.max(1, WatchKit.watch(village).size()) * RESERVE_EACH + (Archery.of(village) != null ? PRACTICE_ARROWS : 0);
    }

    /** The arrows the stores keep back from the shop's counter (Budget): the raid's reserve, where the town keeps a fletcher. */
    public static int arrowsKept(UUID village, int fallback) {
        return keeps(village) ? reserve(village) : fallback;
    }

    /** Flint short, and gravel to sift: a sitting at the sifting floor. */
    @Nullable
    private static String siftIfShort(ServerLevel level, Villages.Village v, VillageFolkEntity f, Town t) {
        if (Crafts.stock(level, v, s -> s.is(Items.FLINT)) >= 2 || Crafts.stock(level, v, s -> s.is(Items.GRAVEL)) < 1) return null;
        BlockPos spot = siftSpot(level, v.id(), bench(f, v.id()));
        if (spot == null) return null;
        int[] got = sift(level, v, f, spot, SIFTS_A_PIECE);
        if (got[0] == 0) return null;
        return got[1] == 0 ? "sifted " + got[0] + " gravel, and not a flint in it"
            : "sifted " + got[0] + " gravel for " + got[1] + (got[1] == 1 ? " flint" : " flint");
    }

    /**
     * Gravel sifted for flint as the game gives it: a block of the stores' gravel set down on the floor here and broken,
     * with the stores' shovel if there is one (lent, and back a little the worse for it), by hand if not; what the
     * game's own loot gives for it (a flint one time in ten, the gravel itself otherwise) goes back into the stores,
     * and the next is set down. Stops when the stores' gravel does. Returns {gravel broken, flint got}.
     */
    static int[] sift(ServerLevel level, Villages.Village v, @Nullable VillageFolkEntity by, BlockPos spot, int most) {
        if (!level.isLoaded(spot) || !level.getBlockState(spot).isAir()) return new int[]{ 0, 0 };
        ItemStack shovel = Crafts.takeOne(level, v, s -> s.is(ItemTags.SHOVELS));
        int broke = 0, flint = 0;
        try {
            for (int i = 0; i < most; i++) {
                if (!Crafts.take(level, v, s -> s.is(Items.GRAVEL), 1)) break;
                BlockState gravel = Blocks.GRAVEL.defaultBlockState();
                level.setBlock(spot, gravel, 3);
                level.playSound(null, spot, SoundEvents.GRAVEL_PLACE, SoundSource.BLOCKS, 0.7F, 1.0F);
                List<ItemStack> drops = Block.getDrops(gravel, level, spot, null, by, shovel);
                level.levelEvent(2001, spot, Block.getId(gravel));
                level.removeBlock(spot, false);
                broke++;
                for (ItemStack d : drops) {
                    if (d.is(Items.FLINT)) flint += d.getCount();
                    Crafts.store(level, v, d);
                }
                if (!shovel.isEmpty() && by != null) shovel.hurtAndBreak(1, level, by, item -> { });
                if (by != null) by.swing(InteractionHand.MAIN_HAND);
            }
        } finally {
            if (!shovel.isEmpty()) Crafts.store(level, v, shovel);
        }
        if (broke > 0) {
            Town t = town(v.id());
            t.gravel += broke;
            t.flint += flint;
            save(v.id(), t);
        }
        return new int[]{ broke, flint };
    }

    /**
     * [fletcher] The smith's flint, where the town has no fletcher (Crafts.fletch): sifted out of the stores' gravel the
     * same way, on the floor by the smithy (or the square). What it came to, or null with no gravel to sift.
     */
    @Nullable
    public static String siftFor(ServerLevel level, Villages.Village v, int most) {
        BlockPos near = Villages.builtAt(v.id(), "smithy");
        if (near == null) near = v.centre();
        BlockPos spot = Trades.floorSpot(level, near, 4);
        if (spot == null) return null;
        int[] got = sift(level, v, null, spot, most);
        if (got[0] == 0) return null;
        return "sifted " + got[0] + " gravel for " + got[1] + " flint";
    }

    /** So many sticks used out of the stores: its sticks, else planks sawn into them (two planks, four sticks; the rest kept). */
    static boolean sticks(ServerLevel level, Villages.Village v, int n) {
        if (n <= 0) return true;
        int have = Crafts.stock(level, v, s -> s.is(Items.STICK));
        if (have < n) {
            int pairs = (n - have + 3) / 4;
            if (!Crafts.planks(level, v, pairs * 2) || !Crafts.take(level, v, s -> s.is(ItemTags.PLANKS), pairs * 2)) return false;
            Crafts.store(level, v, new ItemStack(Items.STICK, pairs * 4));
        }
        return Crafts.take(level, v, s -> s.is(Items.STICK), n);
    }

    /** Arrows, up to so many crafts of them: a flint, a stick and a feather to four. What was made, or null. */
    @Nullable
    static String arrows(ServerLevel level, Villages.Village v, Town t, int most) {
        int made = 0;
        for (int i = 0; i < most; i++) {
            if (Crafts.stock(level, v, s -> s.is(Items.FLINT)) < 1 || Crafts.stock(level, v, s -> s.is(Items.FEATHER)) < 1) break;
            if (!sticks(level, v, 1)) break;
            if (!Crafts.take(level, v, s -> s.is(Items.FLINT), 1)) { Crafts.store(level, v, new ItemStack(Items.STICK)); break; }
            if (!Crafts.take(level, v, s -> s.is(Items.FEATHER), 1)) {
                Crafts.store(level, v, new ItemStack(Items.STICK));
                Crafts.store(level, v, new ItemStack(Items.FLINT));
                break;
            }
            Crafts.store(level, v, new ItemStack(Items.ARROW, 4));
            made += 4;
        }
        if (made == 0) return null;
        t.arrows += made;
        long day = level.getDayTime() / 24000L;
        if (t.firstArrows < 0) {
            t.firstArrows = day;
            VillageFolkEntity f = fletchers(v.id()).isEmpty() ? null : fletchers(v.id()).get(0);
            Villages.tell(v.id(), day, (f == null ? "the fletcher" : f.displayNameCap()) + " made the town's first arrows at the fletching table:"
                + " flint from the miners' gravel and feathers from the coop");
        }
        save(v.id(), t);
        return made + " arrows";
    }

    /** Bows wanted: a guard with nothing to shoot with, and a spare in the stores; past that, one for the shop. */
    static int bowsWanted(ServerLevel level, Villages.Village v) {
        int without = 0;
        for (VillageFolkEntity g : WatchKit.watch(v.id())) if (g.countCarried(AssistantEntity.RANGED_WEAPON) == 0) without++;
        int stores = Crafts.stock(level, v, s -> s.getItem() instanceof BowItem);
        int want = without + 1 - stores;
        if (want <= 0 && Crafts.stock(level, v, s -> s.is(Items.STRING)) >= 6
            && stores <= Budget.keep(level, v.id(), new ItemStack(Items.BOW))) want = 1;          // one to sell
        return want;
    }

    /** A bow: three sticks and three string. */
    @Nullable
    static String bow(ServerLevel level, Villages.Village v, Town t) {
        if (bowsWanted(level, v) <= 0 || Crafts.stock(level, v, s -> s.is(Items.STRING)) < 3) return null;
        if (!sticks(level, v, 3)) return null;
        if (!Crafts.take(level, v, s -> s.is(Items.STRING), 3)) {
            Crafts.store(level, v, new ItemStack(Items.STICK, 3));
            return null;
        }
        Crafts.store(level, v, new ItemStack(Items.BOW));
        t.bows++;
        save(v.id(), t);
        return "a bow";
    }

    /** The town's best archers, by their eye at the butts (and their years at the watch on a tie): a third of the watch. */
    static List<VillageFolkEntity> bestArchers(UUID village) {
        Town t = town(village);
        List<VillageFolkEntity> watch = WatchKit.watch(village);
        watch.sort(Comparator.comparingDouble((VillageFolkEntity g) -> aimOf(t, g.getUUID()))
            .thenComparingInt(g -> g.tradeLevel(StationTask.GUARD)).reversed());
        int n = Math.max(1, (watch.size() + 2) / 3);
        return watch.size() > n ? new ArrayList<>(watch.subList(0, n)) : watch;
    }

    /** Crossbows wanted: one for each of the best archers who has none, in the Iron Age. */
    static int crossbowsWanted(ServerLevel level, Villages.Village v) {
        if (Villages.ageOf(v.id()).ordinal() < Villages.Age.IRON.ordinal()) return 0;
        int want = 0;
        for (VillageFolkEntity g : bestArchers(v.id())) if (g.countCarried(s -> s.getItem() instanceof CrossbowItem) == 0) want++;
        return want - Crafts.stock(level, v, s -> s.getItem() instanceof CrossbowItem);
    }

    /** A crossbow: three sticks, two string, an iron ingot and a tripwire hook (an ingot, a stick and a plank make two). */
    @Nullable
    static String crossbow(ServerLevel level, Villages.Village v, Town t) {
        if (crossbowsWanted(level, v) <= 0) return null;
        if (!Tiers.allows(level, Villages.ageOf(v.id()), Items.CROSSBOW)) return null;
        boolean hook = Crafts.stock(level, v, s -> s.is(Items.TRIPWIRE_HOOK)) > 0;
        int iron = hook ? 1 : 2;
        if (Crafts.stock(level, v, s -> s.is(Items.STRING)) < 2 || Crafts.stock(level, v, s -> s.is(Items.IRON_INGOT)) < iron) return null;
        if (!hook) {
            // The hook first, at the table: an ingot over a stick over a plank.
            if (!Crafts.planks(level, v, 1) || !sticks(level, v, 1)) return null;
            if (!Crafts.take(level, v, s -> s.is(Items.IRON_INGOT), 1) || !Crafts.take(level, v, s -> s.is(ItemTags.PLANKS), 1)) {
                Crafts.store(level, v, new ItemStack(Items.STICK));
                return null;
            }
            Crafts.store(level, v, new ItemStack(Items.TRIPWIRE_HOOK, 2));
            t.hooks += 2;
        }
        if (!sticks(level, v, 3)) return null;
        if (!Crafts.take(level, v, s -> s.is(Items.STRING), 2) || !Crafts.take(level, v, s -> s.is(Items.IRON_INGOT), 1)
            || !Crafts.take(level, v, s -> s.is(Items.TRIPWIRE_HOOK), 1)) {
            Crafts.store(level, v, new ItemStack(Items.STICK, 3));
            return null;
        }
        Crafts.store(level, v, new ItemStack(Items.CROSSBOW));
        t.crossbows++;
        save(v.id(), t);
        if (t.crossbows == 1) Villages.tell(v.id(), level.getDayTime() / 24000L, "the town's first crossbow was made, for the best eye on the wall");
        return "a crossbow";
    }

    /** Targets for the range's butts that have none (Archery puts them up): a bale of hay and four redstone each. */
    @Nullable
    static String target(ServerLevel level, Villages.Village v, Town t) {
        Ledger.Building range = Archery.of(v.id());
        if (range == null || !Land.areaLoaded(level, range.anchor(), 8)) return null;
        int bare = 0;
        for (Archery.Lane l : Archery.lanes(range)) if (!level.getBlockState(l.target()).is(Blocks.TARGET)) bare++;
        if (bare - Crafts.stock(level, v, s -> s.is(Items.TARGET)) <= 0) return null;
        if (Crafts.stock(level, v, s -> s.is(Items.REDSTONE)) < 4) return null;
        boolean bale = Crafts.stock(level, v, s -> s.is(Items.HAY_BLOCK)) > 0;
        if (!bale && Crafts.stock(level, v, s -> s.is(Items.WHEAT)) < 9) return null;
        if (bale ? !Crafts.take(level, v, s -> s.is(Items.HAY_BLOCK), 1) : !Crafts.take(level, v, s -> s.is(Items.WHEAT), 9)) return null;
        if (!Crafts.take(level, v, s -> s.is(Items.REDSTONE), 4)) {
            Crafts.store(level, v, bale ? new ItemStack(Items.HAY_BLOCK) : new ItemStack(Items.WHEAT, 9));
            return null;
        }
        Crafts.store(level, v, new ItemStack(Items.TARGET));
        t.targets++;
        save(v.id(), t);
        return "a target for the butts";
    }

    /**
     * A redstone lamp over a butt's target (four redstone round a block of glowstone, packed of four dust): the target
     * gives a pulse when an arrow strikes it, and the lamp over it lights. Set on the target by the fletcher at the range.
     */
    @Nullable
    static String lamp(ServerLevel level, Villages.Village v, VillageFolkEntity f, Town t) {
        Ledger.Building range = Archery.of(v.id());
        if (range == null || !Land.areaLoaded(level, range.anchor(), 8)) return null;
        BlockPos spot = null;
        for (Archery.Lane l : Archery.lanes(range)) {
            if (level.getBlockState(l.target()).is(Blocks.TARGET) && level.getBlockState(l.target().above()).isAir()) { spot = l.target().above(); break; }
        }
        if (spot == null) return null;
        boolean ready = Crafts.stock(level, v, s -> s.is(Items.REDSTONE_LAMP)) > 0;
        if (!ready) {
            boolean block = Crafts.stock(level, v, s -> s.is(Items.GLOWSTONE)) > 0;
            if (Crafts.stock(level, v, s -> s.is(Items.REDSTONE)) < 4 + 4) return null;          // four kept for a target
            if (!block && Crafts.stock(level, v, s -> s.is(Items.GLOWSTONE_DUST)) < 4) return null;
        }
        if (!TownJobs.atWork(level, v, "range", spot, "setting a lamp over the butts", StationTask.FLETCHER)) return null;
        if (!ready) {
            boolean block = Crafts.take(level, v, s -> s.is(Items.GLOWSTONE), 1);
            if (!block && !Crafts.take(level, v, s -> s.is(Items.GLOWSTONE_DUST), 4)) return null;
            if (!Crafts.take(level, v, s -> s.is(Items.REDSTONE), 4)) {
                Crafts.store(level, v, block ? new ItemStack(Items.GLOWSTONE) : new ItemStack(Items.GLOWSTONE_DUST, 4));
                return null;
            }
        } else if (!Crafts.take(level, v, s -> s.is(Items.REDSTONE_LAMP), 1)) {
            return null;
        }
        level.setBlock(spot, Blocks.REDSTONE_LAMP.defaultBlockState(), 3);
        level.playSound(null, spot, SoundEvents.GLASS_PLACE, SoundSource.BLOCKS, 1.0F, 1.0F);
        t.lamps++;
        save(v.id(), t);
        return "a redstone lamp over a butt, to light when the target is struck";
    }

    /** Spectral arrows, once the Nether's glowstone is in the stores: four dust round an arrow make two. */
    @Nullable
    static String spectral(ServerLevel level, Villages.Village v, Town t) {
        if (Crafts.stock(level, v, s -> s.is(Items.SPECTRAL_ARROW)) >= SPECTRAL_KEPT) return null;
        if (Crafts.stock(level, v, s -> s.is(Items.GLOWSTONE_DUST)) < 4 || Crafts.stock(level, v, s -> s.is(Items.ARROW)) < 1) return null;
        if (!Crafts.take(level, v, s -> s.is(Items.GLOWSTONE_DUST), 4)) return null;
        if (!Crafts.take(level, v, s -> s.is(Items.ARROW), 1)) {
            Crafts.store(level, v, new ItemStack(Items.GLOWSTONE_DUST, 4));
            return null;
        }
        Crafts.store(level, v, new ItemStack(Items.SPECTRAL_ARROW, 2));
        t.spectral += 2;
        save(v.id(), t);
        return "2 spectral arrows";
    }

    // ------------------------------------------------------------------ the watch, kept stocked

    /** A guard's quiver: thirty-two once the town keeps a fletcher, else the watch's old sixteen. */
    public static int quiver(@Nullable UUID village, int fallback) {
        return keeps(village) ? QUIVER : fallback;
    }

    /** How low a quiver runs before it is filled. */
    public static int quiverLow(@Nullable UUID village, int fallback) {
        return keeps(village) ? QUIVER_LOW : fallback;
    }

    /**
     * Every guard with a bow and a quiver run low, filled up to the full quiver out of the stores (the town's mark on the
     * arrows, as on all the watch's kit). One the stores cannot fill is named on the board. Returns the arrows handed out.
     */
    public static int fillQuivers(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        int full = quiver(id, WatchKit.ARROWS), low = quiverLow(id, WatchKit.ARROWS_LOW);
        int handed = 0;
        List<String> empty = new ArrayList<>();
        for (VillageFolkEntity g : WatchKit.watch(id)) {
            if (g.countCarried(AssistantEntity.RANGED_WEAPON) == 0) continue;
            int have = g.countCarried(s -> s.is(Items.ARROW));
            if (have >= low) continue;
            int n = Math.min(full - have, Crafts.stock(level, v, s -> s.is(Items.ARROW)));
            if (n <= 0 || !Crafts.take(level, v, s -> s.is(Items.ARROW), n)) {
                if (have < 4) empty.add(g.displayNameCap());
                continue;
            }
            ItemStack left = g.insertGiven(WatchKit.mark(new ItemStack(Items.ARROW, n)));
            if (!left.isEmpty()) Crafts.store(level, v, WatchKit.unmarked(left));
            int in = n - left.getCount();
            handed += in;
            if (in > 0) g.brain("its quiver filled out of the stores: " + in + " arrows");
            if (g.countCarried(s -> s.is(Items.ARROW)) < 4) empty.add(g.displayNameCap());
        }
        if (empty.isEmpty()) EMPTY.remove(id);
        else EMPTY.put(id, empty);
        if (handed > 0) {
            Town t = town(id);
            t.quivered += handed;
            save(id, t);
        }
        return handed;
    }

    /** Feathers the fletcher likes to have in hand before the rancher's cull goes back to whatever the pen has most of. */
    static final int FEATHERS_WANTED = 16;

    /** The rancher's cull takes an old hen first while the fletcher is short of feathers (VillageFolkEntity.cullNow). */
    @Nullable
    public static net.minecraft.world.entity.EntityType<?> henFirst(ServerLevel level, @Nullable UUID village) {
        if (village == null || !keeps(village)) return null;
        return Market.stock(level, village, s -> s.is(Items.FEATHER)) < FEATHERS_WANTED ? net.minecraft.world.entity.EntityType.CHICKEN : null;
    }

    /** A guard's quiver, empty: it goes to the stores for more at once (VillageFolkEntity, its kit from the stores). */
    public static boolean emptyQuiver(VillageFolkEntity g) {
        return g.stationTask() == StationTask.GUARD && g.countCarried(AssistantEntity.RANGED_WEAPON) > 0
            && g.countCarried(s -> s.is(Items.ARROW)) < 4;
    }

    /**
     * A guard come to the stores with an empty quiver (VillageFolkEntity.guardKitFromTheStores): if the stores cannot
     * fill it, the board says so.
     */
    public static void cameForArrows(ServerLevel level, VillageFolkEntity g) {
        UUID id = g.ownerId();
        if (id == null || g.countCarried(s -> s.is(Items.ARROW)) >= 4) return;
        List<String> empty = new ArrayList<>(EMPTY.getOrDefault(id, List.of()));
        if (!empty.contains(g.displayNameCap())) empty.add(g.displayNameCap());
        EMPTY.put(id, empty);
        if (g.getRandom().nextInt(3) == 0) FolkTalk.speak(g, FolkTalk.pick(g.getRandom(), "Not an arrow in the stores. What am I to shoot, harsh words?",
            "Empty quiver, empty stores. Somebody tell the fletcher."));
    }

    /** The town's best archers carry the crossbows: one to each that has none, its bow back into the stores. */
    static int issueCrossbows(ServerLevel level, Villages.Village v) {
        if (Villages.ageOf(v.id()).ordinal() < Villages.Age.IRON.ordinal()) return 0;
        int n = 0;
        for (VillageFolkEntity g : bestArchers(v.id())) {
            if (g.countCarried(s -> s.getItem() instanceof CrossbowItem) > 0) continue;
            ItemStack cb = Crafts.takeOne(level, v, s -> s.getItem() instanceof CrossbowItem);
            if (cb.isEmpty()) break;
            // Its bow back into the stores for another guard: the crossbow in its hand is its weapon now.
            ItemStack main = g.getMainHandItem();
            if (main.getItem() instanceof BowItem) {
                Crafts.store(level, v, WatchKit.unmarked(main.copy()));
                g.setItemSlot(EquipmentSlot.MAINHAND, WatchKit.mark(cb));
            } else {
                g.removeMatching(s -> s.getItem() instanceof BowItem && WatchKit.issued(s), 1);
                ItemStack left = g.insertGiven(WatchKit.mark(cb));
                if (!left.isEmpty()) { Crafts.store(level, v, WatchKit.unmarked(left)); continue; }
            }
            n++;
            g.brain("issued a crossbow by the town: among its best archers");
            FolkTalk.speak(g, FolkTalk.pick(g.getRandom(), "A crossbow! The fletcher says I've the best eye on the wall.", "Now this is a weapon."));
        }
        return n;
    }

    /** After a raid (Raids.end): every quiver filled at once out of the reserve, and the fletcher to make it good again. */
    public static void raidOver(ServerLevel level, Villages.Village v, boolean raid) {
        if (!raid) return;
        int handed = fillQuivers(level, v);
        UUID id = v.id();
        Town t = town(id);
        t.restocks++;
        save(id, t);
        long day = level.getDayTime() / 24000L;
        List<VillageFolkEntity> fl = fletchers(id);
        if (handed > 0 && !fl.isEmpty()) {
            VillageFolkEntity f = fl.get(0);
            Villages.tell(id, day, f.displayNameCap() + " restocked the watch after the raid: " + handed + " arrows into the guards' quivers, "
                + Crafts.stock(level, v, s -> s.is(Items.ARROW)) + " left in the stores toward a reserve of " + reserve(id));
            LAST.remove(f.getUUID());                                       // to the table at once: the reserve to make good
        }
    }

    // ------------------------------------------------------------------ aim

    static double aimOf(Town t, UUID guard) {
        Aim a = t.aims.get(guard);
        return a == null ? 0.0 : a.aim;
    }

    /** How steady a guard's eye is from practice: nought (never practised) to one. */
    public static double aim(VillageFolkEntity g) {
        UUID id = g.ownerId();
        return id == null ? 0.0 : aimOf(town(id), g.getUUID());
    }

    /** What practice does to a guard's spread at the butts (Archery.loose): down to six-tenths of it. */
    public static float aimFactor(VillageFolkEntity g) {
        return (float) (1.0 - 0.4 * aim(g));
    }

    /**
     * The spread of a guard's shot in a fight (AssistantEntity.performRangedAttack, the crossbow's in BowAttackGoal):
     * half as wide again for the guard that has never practised as for the best-practised.
     */
    public static float spread(AssistantEntity shooter, float base) {
        if (!(shooter instanceof VillageFolkEntity g) || g.stationTask() != StationTask.GUARD) return base;
        return (float) (base * (1.0 - 0.5 * aim(g)));
    }

    /**
     * A session at the butts finished (Archery.finish): the guard's eye the steadier for it (the more for a good score,
     * a little less each time: the first sessions teach the most), its best kept, and a new best in the chronicle.
     */
    public static void practised(ServerLevel level, VillageFolkEntity g, int shot, int hits, int points, boolean contest) {
        practised(level, g, shot, hits, true);
    }

    /**
     * A session at the butts stopped before it was done (Archery.stop: the bell, a fight): the arrows it did shoot
     * steadied its eye all the same, in proportion to a whole session's, but a broken session is no best and no record.
     */
    public static void stopped(ServerLevel level, @Nullable VillageFolkEntity g, int shot, int hits) {
        if (g != null && shot > 0) practised(level, g, shot, hits, false);
    }

    private static void practised(ServerLevel level, VillageFolkEntity g, int shot, int hits, boolean whole) {
        UUID id = g.ownerId();
        if (id == null || shot <= 0) return;
        Town t = town(id);
        Aim a = t.aims.computeIfAbsent(g.getUUID(), k -> new Aim());
        double before = a.aim;
        double share = whole ? 1.0 : Math.min(1.0, shot / (double) PRACTICE_ARROWS);
        a.aim = Math.min(1.0, a.aim + share * (1.0 - a.aim) * (0.06 + 0.10 * hits / (double) shot));
        if (!whole) {
            save(id, t);
            g.brain(String.format(Locale.ROOT, "its aim a little steadier for %d arrows at the butts: %.0f%% to %.0f%%", shot, before * 100, a.aim * 100));
            return;
        }
        a.sessions++;
        boolean best = a.bestShot == 0 || hits * a.bestShot > a.bestHits * shot || hits * a.bestShot == a.bestHits * shot && shot > a.bestShot;
        if (best) {
            a.bestHits = hits;
            a.bestShot = shot;
        }
        t.names.put(g.getUUID(), g.displayNameCap());
        // The town's record at the butts: the best score of ten any guard has had.
        if (shot >= PRACTICE_ARROWS && best && hits >= 7) {
            int record = 0;
            for (Map.Entry<UUID, Aim> e : t.aims.entrySet()) {
                if (e.getKey().equals(g.getUUID())) continue;
                Aim o = e.getValue();
                if (o.bestShot >= PRACTICE_ARROWS) record = Math.max(record, o.bestHits);
            }
            if (hits > record) {
                Villages.tell(id, level.getDayTime() / 24000L, g.displayNameCap() + " put " + hits + " of " + shot
                    + " in the butts at the fletcher's practice, the best in the watch");
                g.persona().remember(level.getDayTime() / 24000L, "I put " + hits + " of " + shot + " in the butts, the best in the watch", 4);
            }
        }
        save(id, t);
        g.brain(String.format(Locale.ROOT, "its aim steadier for the butts: %.0f%% to %.0f%%", before * 100, a.aim * 100));
    }

    // ------------------------------------------------------------------ practice at the range

    /** The fletcher's practice, running: its fletcher, the guards still to shoot, whose turn it is, how it went. */
    static final class Practice {
        final UUID village, fletcher;
        final Deque<UUID> queue = new ArrayDeque<>();
        @Nullable UUID current;
        final long started;
        final Map<String, int[]> results = new LinkedHashMap<>();
        /** When each guard was first found busy at its turn (a word with somebody, a monster seen off). */
        final Map<UUID, Long> waiting = new HashMap<>();
        long turnSince;

        Practice(UUID village, UUID fletcher, long started) {
            this.village = village;
            this.fletcher = fletcher;
            this.started = started;
        }
    }

    private static final Map<UUID, Practice> PRACTICE = new ConcurrentHashMap<>();

    /** Is the fletcher's practice on in this town? */
    public static boolean practising(UUID village) {
        return PRACTICE.containsKey(village);
    }

    /**
     * A quiet working afternoon, a range with a target up, the watch at peace and three of it free: practice called (from
     * the town's look round, tick), once a day. The guards whose eye is least steady go first. Returns why not, or null.
     */
    @Nullable
    static String callPractice(ServerLevel level, Villages.Village v, boolean anyHour) {
        UUID id = v.id();
        if (PRACTICE.containsKey(id)) return "already on";
        List<VillageFolkEntity> fl = fletchers(id);
        if (fl.isEmpty()) return "no fletcher";
        Ledger.Building range = Archery.of(id);
        if (range == null) return "no range";
        long day = level.getDayTime() / 24000L, t = Math.floorMod(level.getDayTime(), 24000L);
        Town town = town(id);
        if (!anyHour) {
            if (t < PRACTICE_FROM || t >= PRACTICE_TILL || RestDay.today(id, day) || town.practisedDay >= day) return "not now";
            if (Raids.underAlarm(id) || Weather.stormy(level) || level.isRaining()) return "not in this";
        }
        if (Crafts.stock(level, v, s -> s.is(Items.ARROW)) < PRACTICE_ARROWS) return "too few arrows in the stores";
        VillageFolkEntity f = fl.get(0);
        if (f.isSleeping() || !f.isAlive()) return "the fletcher is not about";
        List<VillageFolkEntity> free = new ArrayList<>();
        for (VillageFolkEntity g : WatchKit.watch(id)) if (Archery.free(g, level) && !Archery.busy(g)) free.add(g);
        if (free.isEmpty()) return "nobody of the watch free";
        free.sort(Comparator.comparingDouble((VillageFolkEntity g) -> aimOf(town, g.getUUID())));
        Practice p = new Practice(id, f.getUUID(), level.getGameTime());
        for (int i = 0; i < Math.min(PRACTICE_GUARDS, free.size()); i++) p.queue.add(free.get(i).getUUID());
        PRACTICE.put(id, p);
        town.practisedDay = day;
        save(id, town);
        f.clearQueue();
        FolkTalk.speak(f, FolkTalk.pick(level.getRandom(), "Practice at the butts! Ten arrows each, and mind your elbows.",
            "Right, the watch: to the range. Let's see those eyes."));
        return null;
    }

    /**
     * The fletcher at its practice: by the line, each guard's turn begun when the last is done (ten arrows, Archery), the
     * shots called; at the end the range swept of strays and the arrows back into the stores. True while it runs.
     */
    static boolean runPractice(VillageFolkEntity f, ServerLevel level, Villages.Village v, Practice p) {
        Ledger.Building range = Archery.of(v.id());
        if (range == null || Raids.underAlarm(v.id()) || f.isSleeping() || level.getGameTime() - p.started > 6000L) {
            endPractice(level, v, p, f);
            return false;
        }
        f.hobbyNow = null;
        // Stood behind the line, to one side of the middle lane, calling the shots.
        List<Archery.Lane> lanes = Archery.lanes(range);
        BlockPos stand = lanes.get(1).stand().relative(range.facing().getOpposite(), 2).relative(range.facing().getClockWise(), 2);
        if (f.blockPosition().distSqr(stand) > 2 * 2) {
            if (f.getNavigation().isDone() || f.tickCount % 40 == 0) f.walkTo(stand, 1.0D);
        } else {
            f.getNavigation().stop();
            BlockPos look = lanes.get(1).target();
            f.getLookControl().setLookAt(look.getX() + 0.5, look.getY() + 0.5, look.getZ() + 0.5);
        }
        if (p.current != null && Archery.sessionForTests(p.current) && level.getGameTime() - p.turnSince < 1600L) {
            if (f.tickCount % 200 == 0 && f.getRandom().nextInt(3) == 0) {
                FolkTalk.speak(f, FolkTalk.pick(f.getRandom(), "Breathe out, then loose.", "Elbow up!", "Don't snatch at it.", "Good! Again."));
            }
            return true;
        }
        if (p.current != null) {
            VillageFolkEntity g = Football.live(level, p.current);
            int[] last = Archery.lastForTests(p.current);
            if (g != null && last != null) {
                p.results.put(g.displayNameCap(), new int[]{ last[0], last[1] });
                if (f.getRandom().nextInt(2) == 0) FolkTalk.speak(f, last[1] >= last[0] * 7 / 10 ? g.displayNameCap() + ": " + last[1] + " of " + last[0] + ". Well shot!"
                    : g.displayNameCap() + ": " + last[1] + " of " + last[0] + ". We'll make an archer of you yet.");
            }
            p.current = null;
        }
        while (!p.queue.isEmpty()) {
            UUID next = p.queue.peek();
            VillageFolkEntity g = Football.live(level, next);
            if (g == null) {
                p.queue.poll();
                continue;
            }
            if (!Archery.free(g, level) || Archery.busy(g)) {
                // Busy a moment: its turn waits for it half a minute, and then the next goes.
                long since = p.waiting.computeIfAbsent(next, k -> level.getGameTime());
                if (level.getGameTime() - since < 600L) return true;
                p.queue.poll();
                continue;
            }
            p.queue.poll();
            if (Archery.turn(g, level, PRACTICE_ARROWS)) {
                p.current = next;
                p.turnSince = level.getGameTime();
                return true;
            }
        }
        endPractice(level, v, p, f);
        return false;
    }

    /** The practice over: the range swept of strays (arrows of the watch's lying about it), and what came of it. */
    static void endPractice(ServerLevel level, Villages.Village v, Practice p, @Nullable VillageFolkEntity f) {
        PRACTICE.remove(v.id(), p);
        int swept = sweep(level, v);
        Town t = town(v.id());
        t.practices++;
        t.strays += swept;
        save(v.id(), t);
        if (f != null && !p.results.isEmpty()) {
            List<String> how = new ArrayList<>();
            for (Map.Entry<String, int[]> e : p.results.entrySet()) how.add(e.getKey() + " " + e.getValue()[1] + " of " + e.getValue()[0]);
            f.brain("ran practice at the butts: " + String.join(", ", how) + (swept > 0 ? "; swept " + swept + " strays" : ""));
            f.persona().remember(level.getDayTime() / 24000L, "I ran practice at the butts: " + String.join(", ", how), 1);
            FolkTalk.speak(f, "That's practice. " + String.join(", ", how) + ".");
        }
        LAST_PRACTICE.put(v.id(), p);
    }

    /** Arrows of the town's watch lying about the range (gone into the boards, the grass), pulled and into the stores. */
    static int sweep(ServerLevel level, Villages.Village v) {
        Ledger.Building range = Archery.of(v.id());
        if (range == null || !level.isLoaded(range.anchor())) return 0;
        List<UUID> watch = new ArrayList<>();
        for (VillageFolkEntity g : WatchKit.watch(v.id())) watch.add(g.getUUID());
        int n = 0;
        for (Arrow a : level.getEntitiesOfClass(Arrow.class, new AABB(range.anchor()).inflate(9, 6, 9), a -> a.isAlive()
                && a.pickup == AbstractArrow.Pickup.DISALLOWED)) {
            Entity owner = a.getOwner();
            if (owner == null || !watch.contains(owner.getUUID())) continue;
            a.discard();
            n++;
        }
        if (n > 0) Crafts.store(level, v, new ItemStack(Items.ARROW, n));
        return n;
    }

    private static final Map<UUID, Practice> LAST_PRACTICE = new ConcurrentHashMap<>();

    // ------------------------------------------------------------------ the town's look round

    /**
     * Once in ten seconds a town (VillageFolkEntity's town polls): its place filled if it has been open a day with nobody
     * at it, the afternoon's practice called.
     */
    public static void tick(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        long now = level.getGameTime();
        if (now - LOOKED.getOrDefault(id, -100000L) < 200L) return;
        LOOKED.put(id, now);
        if (!wanted(id)) return;
        long day = level.getDayTime() / 24000L;
        // Open a day and nobody has taken it up of themselves (the town's sums go by the newcomers and the trades over
        // their share): the town asks one of its own, once a day at most.
        long t = Math.floorMod(level.getDayTime(), 24000L);
        if (fletchers(id).size() < hands(id) && t >= 2000L && t < 11000L && APPOINTED.getOrDefault(id, -1L) < day) {
            String opened = Ledger.note(id, "fletcher.opened");
            if (opened == null || opened.isEmpty()) {
                Ledger.note(id, "fletcher.opened", Long.toString(day));
            } else {
                long since;
                try { since = Long.parseLong(opened); } catch (NumberFormatException e) { since = day; }
                if (day - since >= 1) {
                    APPOINTED.put(id, day);
                    // [interviews] Two or more who want the place: it is held open for its interview, and given after it.
                    List<VillageFolkEntity> few = shortlist(id);
                    if (few.isEmpty() || !Interviews.vacancy(level, id, "fletcher", few.get(0))) appoint(level, v);
                }
            }
        }
        if (keeps(id)) callPractice(level, v, false);
    }

    // ------------------------------------------------------------------ what the town sees

    private static void noteToday(ServerLevel level, UUID village, String what) {
        long day = level.getDayTime() / 24000L;
        Long was = TODAY_DAY.put(village, day);
        Deque<String> d = TODAY.computeIfAbsent(village, k -> new ArrayDeque<>());
        if (was != null && was != day) d.clear();
        d.addLast(what);
        while (d.size() > 10) d.pollFirst();
    }

    /** What the fletcher is doing, in its own words ("What do you do?", FolkTalk.doing). */
    static String doing(VillageFolkEntity f, RandomSource r) {
        UUID id = f.ownerId();
        if (id == null) return "Fletching, when I've a town to fletch for.";
        Practice p = PRACTICE.get(id);
        if (p != null && p.fletcher.equals(f.getUUID())) {
            VillageFolkEntity g = p.current == null || !(f.level() instanceof ServerLevel level) ? null : Football.live(level, p.current);
            return "Running practice at the butts" + (g == null ? "" : ": " + g.displayNameCap() + "'s turn") + ". Ten arrows each, and keep your elbow up!";
        }
        int arrows = f.level() instanceof ServerLevel level ? Market.stock(level, id, s -> s.is(Items.ARROW)) : 0;
        int reserve = reserve(id);
        return FolkTalk.pick(r,
            "At the fletching table: a flint, a stick and a feather make four arrows.",
            "Sifting the miners' gravel for flint. One in ten, if the gravel's kind.",
            "Keeping the watch's quivers full. " + arrows + " arrows in the stores" + (arrows < reserve ? ", and I want " + reserve + " for a raid." : ", a raid's worth and more."),
            "Stringing bows for the watch. Every guard on the wall with a bow and thirty-two arrows: that's the rule.");
    }

    /** The fletcher's card: what it has made, the stores' arrows against the raid's reserve, today's work. Null for anybody else. */
    @Nullable
    public static String cardLine(VillageFolkEntity f) {
        if (f.stationTask() != StationTask.FLETCHER || f.isBaby() || f.ownerId() == null) return null;
        UUID id = f.ownerId();
        Town t = town(id);
        int arrows = f.level() instanceof ServerLevel level ? Market.stock(level, id, s -> s.is(Items.ARROW)) : 0;
        StringBuilder sb = new StringBuilder();
        sb.append(arrows).append(" arrows in the stores (a raid's reserve is ").append(reserve(id)).append("); made all told ")
            .append(t.arrows).append(" arrows, ").append(t.bows).append(t.bows == 1 ? " bow" : " bows");
        if (t.crossbows > 0) sb.append(", ").append(t.crossbows).append(t.crossbows == 1 ? " crossbow" : " crossbows");
        if (t.spectral > 0) sb.append(", ").append(t.spectral).append(" spectral arrows");
        sb.append("; sifted ").append(t.flint).append(" flint out of ").append(t.gravel).append(" gravel");
        if (t.practices > 0) sb.append("; ").append(t.practices).append(t.practices == 1 ? " practice" : " practices").append(" run at the butts");
        Deque<String> today = TODAY.get(id);
        if (today != null && !today.isEmpty() && f.level() instanceof ServerLevel level
            && TODAY_DAY.getOrDefault(id, -1L) == level.getDayTime() / 24000L) sb.append(". Today: ").append(String.join(", ", today));
        return sb.toString();
    }

    /** A guard's card: its best at the butts and how far practice has steadied it ("best at the butts: 8 of 10"). */
    @Nullable
    public static String aimLine(VillageFolkEntity g) {
        if (g.stationTask() != StationTask.GUARD || g.isBaby() || g.ownerId() == null) return null;
        Aim a = town(g.ownerId()).aims.get(g.getUUID());
        if (a == null || a.sessions == 0) return "never practised at the butts yet; a fight's spread " + String.format(Locale.ROOT, "%.1f", 6.0);
        return "best at the butts: " + a.bestHits + " of " + a.bestShot + "; " + a.sessions + (a.sessions == 1 ? " session" : " sessions")
            + String.format(Locale.ROOT, ", its aim %.0f%% steadied (a fight's spread %.1f, against 6.0 unpractised)", a.aim * 100, 6.0 * (1.0 - 0.5 * a.aim));
    }

    /** The trade's book (TradeBooks.notes): the fletching's real numbers, in the master's own words. */
    static List<String> bookNotes(ServerLevel level, Villages.Village v) {
        Town t = town(v.id());
        List<String> out = new ArrayList<>();
        if (t.arrows > 0) out.add("Between us we've made " + com.jrpetty.mcassistant.village.Quill.number(t.arrows) + " arrows, "
            + com.jrpetty.mcassistant.village.Quill.count(t.bows, "bow", "bows") + (t.crossbows > 0 ? " and " + com.jrpetty.mcassistant.village.Quill.count(t.crossbows, "crossbow", "crossbows") : "") + ".");
        if (t.gravel > 0) out.add(String.format(Locale.ROOT, "We've sifted %d gravel for %d flint: one in %.1f. The game's own odds are one in ten, and they hold.",
            t.gravel, t.flint, t.flint == 0 ? 0.0 : t.gravel / (double) t.flint));
        out.add("The stores keep " + reserve(v.id()) + " arrows for a raid, thirty-two to a guard; we've refilled the watch's quivers after "
            + com.jrpetty.mcassistant.village.Quill.count(t.restocks, "raid", "raids") + ".");
        Aim best = null;
        String who = "";
        for (Map.Entry<UUID, Aim> e : t.aims.entrySet()) {
            Aim a = e.getValue();
            if (a.bestShot >= PRACTICE_ARROWS && (best == null || a.bestHits > best.bestHits)) { best = a; who = t.names.getOrDefault(e.getKey(), "a guard"); }
        }
        if (t.practices > 0) out.add(com.jrpetty.mcassistant.village.Quill.count(t.practices, "practice", "practices") + " at the butts so far"
            + (best == null ? "." : "; the best score is " + who + "'s, " + best.bestHits + " of " + best.bestShot + "."));
        return out;
    }

    /** The board's word: the watch out of arrows, or the fletcher's stores and its practice. Null for nothing to say. */
    @Nullable
    public static String boardLine(ServerLevel level, UUID village) {
        List<String> empty = EMPTY.get(village);
        if (empty != null && !empty.isEmpty() && Market.stock(level, village, s -> s.is(Items.ARROW)) == 0) {
            return "The watch is out of arrows: " + String.join(", ", empty.subList(0, Math.min(3, empty.size())))
                + (empty.size() > 3 ? " and " + (empty.size() - 3) + " more" : "") + " with empty quivers, and none in the stores"
                + (keeps(village) ? ". The fletcher wants flint (gravel), feathers and sticks." : ". The town has no fletcher.");
        }
        if (!keeps(village)) return null;
        int arrows = Market.stock(level, village, s -> s.is(Items.ARROW));
        String line = "The fletcher: " + arrows + " arrows in the stores against a raid's reserve of " + reserve(village);
        if (PRACTICE.containsKey(village)) line += "; practice at the range now";
        return line + ".";
    }

    /** The whole of it, for /village fletcher. */
    public static List<String> report(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        Town t = town(id);
        List<String> out = new ArrayList<>();
        List<VillageFolkEntity> fl = fletchers(id);
        out.add("FLETCHER of " + Villages.name(id) + ": " + (fl.isEmpty() ? (wanted(id) ? "wanted, nobody at it yet" : "not wanted yet (a watch with bows, or a range)")
            : fl.size() + " (" + String.join(", ", fl.stream().map(f -> f.displayNameCap() + " lv " + f.tradeLevel(StationTask.FLETCHER)).toList()) + ")"));
        Ledger.Building hut = hut(id);
        out.add("The hut: " + (hut == null ? (hutWanted(id) ? "on the builders' list" : "none") : "at " + hut.anchor().toShortString())
            + "; the table: " + (t.table == null ? "not set down" : t.table.toShortString()));
        out.add("The stores: " + Market.stock(level, id, s -> s.is(Items.ARROW)) + " arrows (reserve " + reserve(id) + "), "
            + Market.stock(level, id, s -> s.is(Items.SPECTRAL_ARROW)) + " spectral, " + Market.stock(level, id, s -> s.getItem() instanceof BowItem) + " bows, "
            + Market.stock(level, id, s -> s.getItem() instanceof CrossbowItem) + " crossbows; makings: " + Market.stock(level, id, s -> s.is(Items.FLINT)) + " flint, "
            + Market.stock(level, id, s -> s.is(Items.GRAVEL)) + " gravel, " + Market.stock(level, id, s -> s.is(Items.FEATHER)) + " feathers, "
            + Market.stock(level, id, s -> s.is(Items.STICK)) + " sticks, " + Market.stock(level, id, s -> s.is(Items.STRING)) + " string.");
        out.add("Made all told: " + t.arrows + " arrows, " + t.bows + " bows, " + t.crossbows + " crossbows, " + t.spectral + " spectral arrows, "
            + t.targets + " targets, " + t.lamps + " lamps; " + t.flint + " flint sifted from " + t.gravel + " gravel; " + t.quivered
            + " arrows into quivers, " + t.restocks + " restocks after a raid; " + t.practices + " practices, " + t.strays + " strays swept.");
        List<VillageFolkEntity> watch = WatchKit.watch(id);
        for (VillageFolkEntity g : watch) {
            String aim = aimLine(g);
            out.add("  " + g.displayNameCap() + ": " + g.countCarried(s -> s.is(Items.ARROW)) + " arrows" + (g.countCarried(s -> s.getItem() instanceof CrossbowItem) > 0
                ? ", a crossbow" : g.countCarried(AssistantEntity.RANGED_WEAPON) > 0 ? ", a bow" : ", nothing to shoot with") + "; " + aim);
        }
        String board = boardLine(level, id);
        if (board != null) out.add(board);
        return out;
    }

    // ------------------------------------------------------------------ the operator's commands, and the stage

    public static LiteralArgumentBuilder<CommandSourceStack> command() {
        return Commands.literal("fletcher")
            .executes(Fletchers::cmdReport)
            .then(Commands.literal("practice").requires(src -> src.hasPermission(2)).executes(ctx -> {
                Villages.Village v = here(ctx);
                if (v == null) return 0;
                String why = callPractice(ctx.getSource().getLevel(), v, true);
                ctx.getSource().sendSuccess(() -> Component.literal(why == null ? "Practice called at the range." : "No practice: " + why), false);
                return why == null ? 1 : 0;
            }))
            .then(Commands.literal("stage").requires(src -> src.hasPermission(2)).executes(ctx -> {
                Villages.Village v = here(ctx);
                if (v == null) return 0;
                List<String> said = stage(ctx.getSource().getLevel(), v);
                ctx.getSource().sendSuccess(() -> Component.literal(String.join("\n", said)), false);
                return 1;
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

    private static int cmdReport(CommandContext<CommandSourceStack> ctx) {
        Villages.Village v = here(ctx);
        if (v == null) return 0;
        List<String> lines = report(ctx.getSource().getLevel(), v);
        ctx.getSource().sendSuccess(() -> Component.literal(String.join("\n", lines)), false);
        return lines.size();
    }

    /**
     * The pictures' stage (/village fletcher stage): the town's fletcher (one taken up if it has none), its hut put up on
     * a lot of its own by the square if it has none (stamped, as the stages do), its table in it and the makings in the
     * stores (for the stage: a showcase's, put by from nothing and said so); a piece of its work done at once, and
     * practice called at the range if there is one. Says where the camera should stand: "VIEW name x y z ax ay az".
     */
    static List<String> stage(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        List<String> out = new ArrayList<>();
        VillageFolkEntity f = fletchers(id).isEmpty() ? appoint(level, v) : fletchers(id).get(0);
        if (f == null) {
            for (AssistantEntity a : Villages.folkOf(id)) {
                if (a instanceof VillageFolkEntity k && !k.isBaby() && k.stationTask() != StationTask.GUARD && !k.isShowcase()) {
                    if (!k.takeUpTrade(StationTask.FLETCHER)) k.setJob(StationTask.FLETCHER);
                    f = k;
                    break;
                }
            }
        }
        if (f == null) return List.of("no folk to be the fletcher");
        Ledger.Building hut = hut(id);
        if (hut == null) {
            Villages.Site site = Villages.siteFor(level, id, STRUCTURE);
            if (site == null) return List.of("no lot for the hut");
            com.jrpetty.mcassistant.entity.goal.BuildGoal.stamp(level, STRUCTURE, site.anchor(), site.facing(), 0,
                com.jrpetty.mcassistant.Showcase.painter(com.jrpetty.mcassistant.Showcase.OAK));
            Ledger.built(id, STRUCTURE, site.anchor(), site.facing());
            hut = hut(id);
            out.add("the hut stamped at " + site.anchor().toShortString());
        }
        // The makings, for the pictures: put by in the stores as a showcase's (from nothing, and said so).
        Crafts.store(level, v, new ItemStack(Items.GRAVEL, 24));
        Crafts.store(level, v, new ItemStack(Items.FEATHER, 12));
        Crafts.store(level, v, new ItemStack(Items.OAK_PLANKS, 16));
        Crafts.store(level, v, new ItemStack(Items.FLINT, 4));
        Crafts.store(level, v, new ItemStack(Items.STRING, 6));
        out.add("the stage's makings put in the stores (a showcase's): 24 gravel, 12 feathers, 16 planks, 4 flint, 6 string");
        BlockPos home = tableSpot(id);
        if (home != null) {
            BlockPos stand = at(hut, TABLE_DX, TABLE_DZ - 1);
            f.moveTo(stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5, f.getYRot(), 0.0F);
        }
        for (int i = 0; i < 4; i++) {
            String made = work(f, level, v);
            if (made != null) out.add("made: " + made);
        }
        String why = callPractice(level, v, true);
        out.add(why == null ? "practice called at the range" : "no practice: " + why);
        BlockPos door = at(hut, 0, -4);
        BlockPos look = at(hut, 0, 0);
        out.add("VIEW fletcher-hut " + door.getX() + " " + (door.getY() + 2) + " " + door.getZ() + " " + look.getX() + " " + (look.getY() + 1) + " " + look.getZ());
        BlockPos in = at(hut, -1, -1);
        BlockPos table = tableSpot(id);
        if (table != null) out.add("VIEW fletcher-table " + in.getX() + " " + (in.getY() + 1) + " " + in.getZ() + " " + table.getX() + " " + table.getY() + " " + table.getZ());
        Ledger.Building range = Archery.of(id);
        if (range != null) {
            BlockPos side = at(range, 6, -2);
            BlockPos mid = at(range, 0, 2);
            out.add("VIEW fletcher-range " + side.getX() + " " + (side.getY() + 3) + " " + side.getZ() + " " + mid.getX() + " " + (mid.getY() + 1) + " " + mid.getZ());
        }
        return out;
    }

    // ------------------------------------------------------------------ for the tests

    public static String workForTests(VillageFolkEntity f, ServerLevel level, Villages.Village v) {
        String made = work(f, level, v);
        return made == null ? "nothing" : made;
    }

    public static int[] siftForTests(ServerLevel level, Villages.Village v, VillageFolkEntity f, BlockPos spot, int most) {
        return sift(level, v, f, spot, most);
    }

    @Nullable
    public static String callPracticeForTests(ServerLevel level, Villages.Village v) {
        return callPractice(level, v, true);
    }

    /** Tests: {sessions, best hits, best shot} of a guard's eye, and its aim in thousandths. */
    public static int[] aimForTests(VillageFolkEntity g) {
        Aim a = g.ownerId() == null ? null : town(g.ownerId()).aims.get(g.getUUID());
        return a == null ? new int[]{ 0, 0, 0, 0 } : new int[]{ a.sessions, a.bestHits, a.bestShot, (int) Math.round(a.aim * 1000) };
    }

    /** Tests: what the last practice came to, by guard ({shot, hits}), or empty. */
    public static Map<String, int[]> lastPracticeForTests(UUID village) {
        Practice p = LAST_PRACTICE.get(village);
        return p == null ? Map.of() : p.results;
    }

    /** Tests: the sifting floor (in front of the hut, else by this spot). */
    @Nullable
    public static BlockPos siftSpotForTests(ServerLevel level, UUID village, BlockPos near) {
        return siftSpot(level, village, near);
    }

    /** Tests: where the hut keeps its table (null without a hut). */
    @Nullable
    public static BlockPos tableSpotForTests(UUID village) {
        return tableSpot(village);
    }

    @Nullable
    public static BlockPos tableForTests(UUID village) {
        return town(village).table;
    }

    public static void raidOverForTests(ServerLevel level, Villages.Village v) {
        raidOver(level, v, true);
    }
}
