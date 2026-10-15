package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.village.InventionTree.Invention;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.BellBlock;
import net.minecraft.world.level.block.BlastFurnaceBlock;
import net.minecraft.world.level.block.FurnaceBlock;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * [tech-tree] What the town's inventions do in the world: the buildings they let it raise, and the slight but real
 * difference each makes where the town does its work (each at one hook in the code it touches, named below).
 *
 * <h2>The buildings</h2>
 * Each wanted only once the invention behind it is known, among the amenities (after everything the age asks for and the
 * homes: Villages.projectsWantedInOrder), built by the builders out of the stores like any other:
 * <ul>
 * <li><b>The academy</b> (no invention: the scholars' own): a stone hall with two lecterns, the shelves, the bench and a
 *     brewing stand for the experiments; wanted by a Stone Age town of ten once it keeps a scholar. Its desks are worth
 *     half again (Scholars).</li>
 * <li><b>The windmill</b> (The Windmill's Millstones): the town's windmill (Windmill, TownLook) wanted from eight folk
 *     rather than sixteen, and its stones dressed: with a mill the bakery bakes three times its batch, not twice.</li>
 * <li><b>The watermill</b> (The Watermill): a mill on its own race, its wheel turning. It grinds for the bakery as the
 *     windmill does (three times the batch, with or without a windmill), and drives a trip-hammer for the smith and the
 *     smelter (8% quicker).</li>
 * <li><b>The aqueduct</b> (The Aqueduct): arches carrying a channel of water to a basin. While it stands: clean water,
 *     the town 3 more content and the hurt mending faster; and water for the fields, one crop in ten giving one more.</li>
 * <li><b>The clocktower</b> (The Clocktower): a tall tower with the town's clock and its bell. While it stands the town
 *     keeps the hours: every trade 3% quicker, and the bell chimes the hour, a stroke for each, from six in the morning
 *     to nine at night.</li>
 * <li><b>The foundry</b> (Steel): blast furnaces under a brick chimney, where the smith blasts its steel (InventionArms);
 *     smoke and sparks at its chimney while it works.</li>
 * </ul>
 *
 * <h2>The rest, by invention</h2>
 * The Heavy Plough one harvested crop in eight gives one more (the Seed Drill one in five instead); the Horse Collar the
 * farmers 6% and the ranchers 4%; the Seed Drill the farmers 8%; the Treadwheel Crane the builders 12%; Tempered Tools
 * every trade 2% and one use of a tool in six spared its wear; Black Powder the miners 10%; Double-Entry Ledgers the
 * takings 4%; Physic the hurt mend faster and the old live a tenth longer; Lenses the scouts a fifth further; Navigation
 * the fleet sails in the rain and fits out a boat more.
 */
@EventBusSubscriber(modid = McAssistantMod.MODID)
public final class InventionWorks {

    private InventionWorks() {}

    public static final String ACADEMY = "academy", WATERMILL = "watermill", AQUEDUCT = "aqueduct", CLOCKTOWER = "clocktower",
        FOUNDRY = "foundry";
    public static final Set<String> BUILDINGS = Set.of(ACADEMY, WATERMILL, AQUEDUCT, CLOCKTOWER, FOUNDRY);

    /** The academy: wanted by a town of this many in the Stone Age that keeps a scholar. The aqueduct from this many. */
    static final int ACADEMY_FROM = 10, AQUEDUCT_FROM = 12;
    /** The windmill, once its stones are known: wanted from this many (Windmill.FROM otherwise). */
    static final int MILL_FROM = 8;
    /** The paces, in percent. */
    static final int COLLAR_FARM = 6, COLLAR_RANCH = 4, DRILL_FARM = 8, TOOLS = 2, CLOCK = 3, HAMMER = 8, POWDER = 10, CRANE = 12;
    /** One in so many: a crop more (the plough, the drill, the aqueduct); a tool's use spared (tempered). */
    static final int PLOUGH_ONE_IN = 8, DRILL_ONE_IN = 5, WATER_ONE_IN = 10, SPARED_ONE_IN = 6;
    /** The aqueduct's contentment; the takings with double entry; the scouts with lenses; the old with physic. */
    static final int WATER_CONTENT = 3, LEDGERS = 104, LENS_SCOUTS = 20;
    /** The physic's and the aqueduct's mending, every eight seconds (CityTree.tend). */
    static final float PHYSIC_HEAL = 1.0F, WATER_HEAL = 0.5F;

    static void resetForTests() {
        BELLS.clear();
        CHIMES.clear();
    }

    /** Does this town have one of these standing? */
    public static boolean stands(@Nullable UUID village, String structure) {
        return village != null && (Villages.hasBuilt(village, structure) || Villages.builtStructure(village, structure) != null);
    }

    // ------------------------------------------------------------------ the buildings wanted

    /**
     * The town's inventions' buildings it would raise (Villages.projectsWantedInOrder, with the amenities): the academy
     * once it keeps a scholar, the rest once the invention behind each is known.
     */
    public static void wanted(UUID village, int folk, Villages.Age at, List<String> extras, Predicate<String> notBuilt) {
        if (at.ordinal() >= Villages.Age.STONE.ordinal() && folk >= ACADEMY_FROM && !Scholars.appointed(village).isEmpty() && notBuilt.test(ACADEMY)) {
            extras.add(ACADEMY);
        }
        if (Inventions.known(village, Invention.WATERMILL) && notBuilt.test(WATERMILL)) extras.add(WATERMILL);
        if (Inventions.known(village, Invention.STEEL) && notBuilt.test(FOUNDRY)) extras.add(FOUNDRY);
        if (Inventions.known(village, Invention.CLOCKTOWER) && notBuilt.test(CLOCKTOWER)) extras.add(CLOCKTOWER);
        if (Inventions.known(village, Invention.AQUEDUCT) && folk >= AQUEDUCT_FROM && notBuilt.test(AQUEDUCT)) extras.add(AQUEDUCT);
    }

    /** "the academy", as folk speak of it (Villages.spoken), or null for any other building. */
    @Nullable
    public static String spoken(String structure) {
        return switch (structure) {
            case ACADEMY -> "the academy";
            case WATERMILL -> "the watermill";
            case AQUEDUCT -> "the aqueduct";
            case CLOCKTOWER -> "the clocktower";
            case FOUNDRY -> "the foundry";
            default -> null;
        };
    }

    /** Why the town would build it (Villages.whyBuild), or null for any other building. */
    @Nullable
    public static String why(UUID village, String structure) {
        return switch (structure) {
            case ACADEMY -> "an academy for the town's " + Scholars.appointed(village).size() + " scholars: desks, shelves and a bench for the experiments";
            case WATERMILL -> "a watermill, now the scholars have worked it out: flour for the bakery and a trip-hammer for the smith";
            case AQUEDUCT -> "an aqueduct, now the scholars have worked it out: clean water for the town and its fields";
            case CLOCKTOWER -> "a clocktower, now the scholars have worked it out: the town will keep the hours";
            case FOUNDRY -> "a foundry, now the town knows steel: blast furnaces for the smith's steel";
            default -> null;
        };
    }

    /** The windmill's headcount: eight once its stones are known (TownLook.wanted), else the usual. */
    public static int millFrom(@Nullable UUID village, int usual) {
        return Inventions.known(village, Invention.WINDMILL) ? Math.min(usual, MILL_FROM) : usual;
    }

    /** Does the town grind its own flour: the windmill standing, or the watermill (Bakery)? */
    public static boolean milled(@Nullable UUID village) {
        return village != null && (Windmill.mill(village) != null || Inventions.known(village, Invention.WATERMILL) && stands(village, WATERMILL));
    }

    /** The bakery's batch, as a share: twice with a mill, three times with dressed stones or a watermill (Bakery). */
    public static int batchTimes(@Nullable UUID village, boolean milled) {
        if (village == null) return milled ? 2 : 1;
        boolean water = Inventions.known(village, Invention.WATERMILL) && stands(village, WATERMILL);
        if (water) return 3;
        if (!milled) return 1;
        return Inventions.known(village, Invention.WINDMILL) ? 3 : 2;
    }

    // ------------------------------------------------------------------ the paces and the rest

    /** What the town's inventions add to this trade's pace, in percent (CityTree.workPercent). */
    public static int workPercent(@Nullable UUID village, StationTask trade) {
        if (village == null || trade == null || trade == StationTask.NONE || Inventions.knownSet(village).isEmpty()) return 0;
        int p = 0;
        if (Inventions.known(village, Invention.TEMPERED_TOOLS)) p += TOOLS;
        if (Inventions.known(village, Invention.HORSE_COLLAR)) p += trade == StationTask.FARM ? COLLAR_FARM : trade == StationTask.RANCH ? COLLAR_RANCH : 0;
        if (Inventions.known(village, Invention.SEED_DRILL) && trade == StationTask.FARM) p += DRILL_FARM;
        if (Inventions.known(village, Invention.BLACK_POWDER) && trade == StationTask.MINE) p += POWDER;
        if (Inventions.known(village, Invention.CLOCKTOWER) && stands(village, CLOCKTOWER)) p += CLOCK;
        if ((trade == StationTask.SMITH || trade == StationTask.SMELT) && Inventions.known(village, Invention.WATERMILL) && stands(village, WATERMILL)) p += HAMMER;
        return p;
    }

    /** The Treadwheel Crane: the builders' pace, in percent (CityTree.buildPercent). */
    public static int buildPercent(@Nullable UUID village) {
        return Inventions.known(village, Invention.TREADWHEEL_CRANE) ? CRANE : 0;
    }

    /** Tempered Tools: is this use of a tool spared its wear (CityTree.sparesTool)? One in six. */
    public static boolean sparesTool(@Nullable UUID village, RandomSource random) {
        return Inventions.known(village, Invention.TEMPERED_TOOLS) && random.nextInt(SPARED_ONE_IN) == 0;
    }

    /** The plough (or the drill) and the aqueduct's water: crops more off this harvest (CityTree.seedExchange). */
    public static int moreHarvest(@Nullable UUID village, RandomSource random) {
        if (village == null) return 0;
        int more = 0;
        if (Inventions.known(village, Invention.SEED_DRILL)) {
            if (random.nextInt(DRILL_ONE_IN) == 0) more++;
        } else if (Inventions.known(village, Invention.HEAVY_PLOUGH) && random.nextInt(PLOUGH_ONE_IN) == 0) more++;
        if (Inventions.known(village, Invention.AQUEDUCT) && stands(village, AQUEDUCT) && random.nextInt(WATER_ONE_IN) == 0) more++;
        return more;
    }

    /** The aqueduct's clean water, in the town's contentment (CityTree.contentment through Perks). */
    public static int contentment(@Nullable UUID village, List<String> good) {
        if (Inventions.known(village, Invention.AQUEDUCT) && stands(village, AQUEDUCT)) {
            good.add("clean water from the aqueduct");
            return WATER_CONTENT;
        }
        return 0;
    }

    /** Physic: how long a folk lives, a tenth more (CityTree.lifespan). */
    public static int lifespan(@Nullable UUID village, int years, int base) {
        return Inventions.known(village, Invention.MEDICINE) ? years + base / 10 : years;
    }

    /** Every eight seconds for every folk (CityTree.tend): the physician's care and the aqueduct's clean water mend the hurt. */
    public static void tend(VillageFolkEntity f) {
        UUID v = f.ownerId();
        if (v == null || !f.isAlive() || f.getHealth() >= f.getMaxHealth() || f.hurtTime != 0 || f.tickCount - f.getLastHurtByMobTimestamp() <= 100) return;
        float heal = 0;
        if (Inventions.known(v, Invention.MEDICINE)) heal += PHYSIC_HEAL;
        if (Inventions.known(v, Invention.AQUEDUCT) && stands(v, AQUEDUCT)) heal += WATER_HEAL;
        if (heal > 0) f.heal(heal);
    }

    /** Double-Entry Ledgers: the takings, in percent (CityTree.takingsPercent). */
    public static int takingsPercent(@Nullable UUID village) {
        return Inventions.known(village, Invention.DOUBLE_ENTRY) ? LEDGERS : 100;
    }

    /** Lenses: the scouts' range, in percent more (CityTree.scoutPercent). */
    public static int scoutPercent(@Nullable UUID village) {
        return Inventions.known(village, Invention.LENSES) ? LENS_SCOUTS : 0;
    }

    /** Navigation: the fleet sails in the rain (CityTree.sailsInRain). */
    public static boolean sailsInRain(@Nullable UUID village) {
        return Inventions.known(village, Invention.NAVIGATION);
    }

    /** Navigation: a boat more in the fleet (CityTree.extraBoats). */
    public static int extraBoats(@Nullable UUID village) {
        return Inventions.known(village, Invention.NAVIGATION) ? 1 : 0;
    }

    /** For a harbour (another helper's): may its ships sail the open sea? Navigation known. */
    public static boolean openSea(@Nullable UUID village) {
        return Inventions.known(village, Invention.NAVIGATION);
    }

    // ------------------------------------------------------------------ known

    /** An invention just known (Inventions.complete): its building's news, what the smith can make now. */
    static void known(ServerLevel level, UUID village, Invention i, long day) {
        if (i.building != null && !"windmill".equals(i.building) && !stands(village, i.building)) {
            Villages.tell(village, day, "the builders will raise " + Villages.spoken(i.building) + " when they can");
        }
        if (i == Invention.WINDMILL && Windmill.mill(village) == null) {
            Villages.tell(village, day, "with the millstones worked out, the town will build its windmill the sooner");
        }
        if (InventionArms.ofArms(i) && i != Invention.SIEGE_ENGINES) {
            Villages.tell(village, day, "the smith and the fletcher can make " + i.unlocks.replace(" from the smith", "").replace(" from the fletcher", "") + " now");
        }
    }

    // ------------------------------------------------------------------ the academy fitted out

    /**
     * The academy's desks put in out of the stores, one a morning (Inventions.morning), where the builders had no lectern
     * for one: a lectern put by, or three books and eight planks (the game's own count: its slabs and a bookshelf). Nothing
     * out of nothing: what the stores cannot pay for waits. Whether one went in.
     */
    static boolean fitOut(ServerLevel level, Villages.Village v) {
        Ledger.Building b = Villages.builtStructure(v.id(), ACADEMY);
        if (b == null || !Land.areaLoaded(level, b.anchor(), 7)) return false;
        for (com.jrpetty.mcassistant.entity.goal.BuildGoal.Placement p
                : com.jrpetty.mcassistant.entity.goal.BuildGoal.plan(ACADEMY, b.anchor(), b.facing(), 13)) {
            if (p.part() != com.jrpetty.mcassistant.entity.goal.BuildGoal.Part.LECTERN || !level.getBlockState(p.pos()).isAir()) continue;
            boolean paid = Crafts.take(level, v, s -> s.is(net.minecraft.world.item.Items.LECTERN), 1);
            if (!paid && Crafts.stock(level, v, s -> s.is(net.minecraft.world.item.Items.BOOK)) >= 3
                    && Crafts.take(level, v, s -> s.is(net.minecraft.world.item.Items.BOOK), 3)) {
                paid = Crafts.usePlanks(level, v, 8);
                if (!paid) Crafts.store(level, v, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.BOOK, 3));
            }
            if (!paid) return false;
            level.setBlock(p.pos(), net.minecraft.world.level.block.Blocks.LECTERN.defaultBlockState()
                .setValue(net.minecraft.world.level.block.LecternBlock.FACING, b.facing().getOpposite()), 3);
            Villages.tell(v.id(), level.getDayTime() / 24000L, "a scholar's desk was put in at the academy");
            return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ the clocktower's chime, the foundry's fire

    /** The clocktower's bell, by the tower's anchor, as last looked for. */
    private static final Map<Long, BlockPos> BELLS = new ConcurrentHashMap<>();
    /** By town: {the hour last struck, the strokes still to strike}. */
    private static final Map<UUID, int[]> CHIMES = new ConcurrentHashMap<>();
    /** Ticks between strokes; the hours struck: six in the morning to nine at night. */
    static final int STROKE = 30;

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        int tick = event.getServer().getTickCount();
        if (tick % 10 != 3) return;
        com.jrpetty.mcassistant.Guard.run("the inventions' works", () -> {
            for (ServerLevel level : event.getServer().getAllLevels()) {
                for (Villages.Village v : Villages.every()) {
                    if (!v.dim().equals(level.dimension()) || !level.isLoaded(v.centre())) continue;
                    if (Inventions.knownSet(v.id()).isEmpty()) continue;
                    chime(level, v, tick);
                    if (tick % 40 == 3 && Inventions.known(v.id(), Invention.STEEL)) foundrySmoke(level, v);
                }
            }
        });
    }

    /** The clocktower's bell, striking the hour: a stroke for each (one to twelve), a stroke every second and a half. */
    static void chime(ServerLevel level, Villages.Village v, int tick) {
        if (!Inventions.known(v.id(), Invention.CLOCKTOWER)) return;
        Ledger.Building b = Villages.builtStructure(v.id(), CLOCKTOWER);
        if (b == null || !level.isLoaded(b.anchor())) return;
        long t = level.getDayTime() % 24000L;
        int hour = (int) (t / 1000L);                                    // 0 is six in the morning
        int[] c = CHIMES.computeIfAbsent(v.id(), k -> new int[]{ hour, 0 });
        if (c[0] != hour) {
            c[0] = hour;
            if (hour <= 15) c[1] = (hour + 6 - 1) % 12 + 1;              // six to nine at night, the hour's strokes
        }
        if (c[1] <= 0 || tick % STROKE != 3) return;
        BlockPos bell = bell(level, b);
        level.playSound(null, bell, SoundEvents.BELL_BLOCK, SoundSource.BLOCKS, 3.0F, 0.8F);
        c[1]--;
    }

    /** Tests: the strokes due at this town's clocktower now (after the hour turns). */
    public static int strokesForTests(UUID village) {
        int[] c = CHIMES.get(village);
        return c == null ? -1 : c[1];
    }

    /** Tests: one look of the clock, now. */
    public static void chimeForTests(ServerLevel level, Villages.Village v) {
        chime(level, v, 3);
    }

    /** The tower's bell, found once round its top (else its anchor raised). */
    static BlockPos bell(ServerLevel level, Ledger.Building b) {
        BlockPos got = BELLS.get(b.anchor().asLong());
        if (got != null) return got;
        BlockPos found = b.anchor().above(10);
        for (BlockPos p : BlockPos.betweenClosed(b.anchor().offset(-3, 0, -3), b.anchor().offset(3, 16, 3))) {
            if (level.getBlockState(p).getBlock() instanceof BellBlock) { found = p.immutable(); break; }
        }
        BELLS.put(b.anchor().asLong(), found);
        return found;
    }

    /** The foundry's chimney smoking while the town has steel in hand to make. */
    static void foundrySmoke(ServerLevel level, Villages.Village v) {
        Ledger.Building b = Villages.builtStructure(v.id(), FOUNDRY);
        if (b == null || !level.isLoaded(b.anchor())) return;
        BlockPos top = b.anchor().above(7);
        level.sendParticles(ParticleTypes.CAMPFIRE_COSY_SMOKE, top.getX() + 0.5, top.getY(), top.getZ() + 0.5, 1, 0.1, 0.1, 0.1, 0.02);
    }

    /** A heat of steel blasted: sparks and the roar at the foundry's furnaces. */
    static void foundryFire(ServerLevel level, Villages.Village v) {
        Ledger.Building b = Villages.builtStructure(v.id(), FOUNDRY);
        if (b == null || !level.isLoaded(b.anchor())) return;
        BlockPos at = b.anchor();
        for (BlockPos p : BlockPos.betweenClosed(at.offset(-3, 0, -3), at.offset(3, 2, 3))) {
            var st = level.getBlockState(p);
            if (st.getBlock() instanceof BlastFurnaceBlock || st.getBlock() instanceof FurnaceBlock) {
                level.sendParticles(ParticleTypes.LAVA, p.getX() + 0.5, p.getY() + 1.0, p.getZ() + 0.5, 4, 0.2, 0.1, 0.2, 0.0);
                level.playSound(null, p, SoundEvents.BLASTFURNACE_FIRE_CRACKLE, SoundSource.BLOCKS, 1.0F, 1.0F);
                return;
            }
        }
    }
}
