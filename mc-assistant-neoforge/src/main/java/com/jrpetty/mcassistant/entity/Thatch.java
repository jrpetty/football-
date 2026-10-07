package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.block.ThatchBlock;
import com.jrpetty.mcassistant.entity.goal.Blueprints;
import com.jrpetty.mcassistant.entity.goal.BuildGoal;
import com.jrpetty.mcassistant.item.WorkItems;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [workitems] Thatch on the Wood Age's roofs.
 * <ul>
 * <li><b>Roofed in thatch.</b> A Wood Age town with wheat to spare (fed, and four stacks of wheat over the seed) roofs in
 *     thatch: it heads the roof's list in the town's look (Palettes.ranked), the builders stock thatch stairs, slabs and
 *     blocks for a roof out of the stores, making them there of the stores' thatch, or its wheat (Bench: six wheat to four
 *     thatch, six thatch to four stairs), and the farmers bale the spare wheat into thatch for them (WorkTools).</li>
 * <li><b>It burns.</b> Thatch catches as hay does. Of an evening, with the hearths lit, a spark from a chimney now and then
 *     lands on a thatched roof (chimneySpark: about one a week in a town of a dozen thatched houses, more in dry weather,
 *     none while the fire watch is near); the bell rings and the fire brigade answers it as any fire.</li>
 * <li><b>Re-roofed.</b> From the Stone Age the town's builders take the thatch off as they make each building over for
 *     the age (Ages.look): tiles (brick stairs and slabs, the smeltery's) in the Stone Age, slate in the Iron Age, or stone
 *     bricks where the stores have no brick; the thatch back into the stores.</li>
 * </ul>
 */
public final class Thatch {

    private Thatch() {}

    /** Thatch the stores keep while the town roofs in it; wheat over the seed it must have to spare for it. */
    static final int KEPT = 24, WHEAT_SPARE = 64;
    /** How often a town is looked at (ticks): its thatching, its thatched roofs. */
    static final long LOOK = 600L, ROOFS_LOOK = 6000L;

    /** Towns roofing in thatch just now (worked out on the town's rounds; read by Palettes on every block a builder lays). */
    private static final Set<UUID> THATCHING = ConcurrentHashMap.newKeySet();
    private static final Map<UUID, Long> LOOKED = new ConcurrentHashMap<>();
    /** Each town's thatched roofs: {when looked, and the thatched cells of each house} */
    private static final Map<UUID, Object[]> ROOFS = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> SPARK_LOOKED = new ConcurrentHashMap<>();
    @Nullable private static volatile Boolean sparkForTests;

    static void resetForTests() {
        THATCHING.clear();
        LOOKED.clear();
        ROOFS.clear();
        SPARK_LOOKED.clear();
        sparkForTests = null;
    }

    /** Tests: the chimney's spark always flies (true), never (false), or as the dice fall (null). */
    public static void sparkForTests(@Nullable Boolean on) {
        sparkForTests = on;
    }

    public static boolean isThatch(BlockState s) {
        return s.getBlock() instanceof ThatchBlock || s.getBlock() instanceof ThatchBlock.Stairs || s.getBlock() instanceof ThatchBlock.Slab;
    }

    public static boolean isThatch(ItemStack s) {
        return s.is(WorkItems.THATCH_ITEM.get()) || s.is(WorkItems.THATCH_STAIRS_ITEM.get()) || s.is(WorkItems.THATCH_SLAB_ITEM.get());
    }

    // ------------------------------------------------------------------ roofed in thatch

    /** Has the town wheat to spare for its roofs: fed, and four stacks of wheat over the seed? */
    public static boolean wheatToSpare(ServerLevel level, Villages.Village v) {
        Leader.Plan plan = Leader.plan(v.id());
        if (plan == Leader.Plan.FAMINE || plan == Leader.Plan.SHORT) return false;
        return Market.stock(level, v.id(), s -> s.is(Items.WHEAT)) >= WHEAT_SPARE + 12
            || Market.stock(level, v.id(), s -> s.is(WorkItems.THATCH_ITEM.get())) >= 8;
    }

    /** Does the town roof in thatch now: the Wood Age, and wheat (or thatch) to spare? */
    public static boolean roofing(ServerLevel level, Villages.Village v) {
        return Villages.ageOf(v.id()) == Villages.Age.WOOD && wheatToSpare(level, v);
    }

    /** As the town's rounds last found it (Palettes, every block laid: no look at the stores). */
    public static boolean thatching(@Nullable UUID village) {
        return village != null && THATCHING.contains(village);
    }

    /** Tests: the town's thatching worked out now. */
    public static boolean thatchingForTests(ServerLevel level, Villages.Village v) {
        LOOKED.remove(v.id());
        rounds(level, v);
        return thatching(v.id());
    }

    /** The town's rounds (WorkTools.rounds): is it thatching, looked at every half minute. */
    static void rounds(ServerLevel level, Villages.Village v) {
        long now = level.getGameTime();
        Long last = LOOKED.get(v.id());
        if (last != null && now - last < LOOK && now >= last) return;
        LOOKED.put(v.id(), now);
        boolean was = THATCHING.contains(v.id());
        boolean is = roofing(level, v);
        if (is) THATCHING.add(v.id());
        else THATCHING.remove(v.id());
        if (is && !was && Ledger.note(v.id(), "work.thatching") == null) {
            Ledger.note(v.id(), "work.thatching", String.valueOf(level.getDayTime() / 24000L));
            Villages.tell(v.id(), level.getDayTime() / 24000L, "with wheat to spare, the town began to roof its houses in thatch");
        }
    }

    /**
     * The roof's kinds of a town's look, thatch first while it roofs in thatch (Palettes.ranked). The list as it was
     * otherwise.
     */
    public static List<Item> palette(@Nullable UUID village, Blueprints.Style style, List<Item> ranked) {
        if (!thatching(village)) return ranked;
        Item first = switch (style) {
            case ROOF_STAIR, ROOF_STAIR_TOP -> WorkItems.THATCH_STAIRS_ITEM.get();
            case ROOF_SLAB, ROOF_SLAB_TOP -> WorkItems.THATCH_SLAB_ITEM.get();
            case ROOF_BLOCK -> WorkItems.THATCH_ITEM.get();
            default -> null;
        };
        if (first == null) return ranked;
        List<Item> out = new ArrayList<>(ranked.size() + 1);
        out.add(first);
        out.addAll(ranked);
        return out;
    }

    /**
     * A builder stocking for a drawn building (VillageFolkEntity.stockStyles), in a town roofing in thatch: the roof's
     * thatch stairs, slabs and blocks into its pack out of the stores, made there out of the stores' thatch or wheat if
     * they are short (Bench, by the recipes, the builder at the bench). Best effort: what it cannot have, it roofs in wood.
     */
    public static void stock(VillageFolkEntity builder, BlockPos heart, int stairs, int slabs, int blocks, int r) {
        UUID id = builder.ownerId();
        Villages.Village v = id == null ? null : Villages.get(id);
        if (v == null || !thatching(id) || !(builder.level() instanceof ServerLevel level)) return;
        Item[] kinds = { WorkItems.THATCH_STAIRS_ITEM.get(), WorkItems.THATCH_SLAB_ITEM.get(), WorkItems.THATCH_ITEM.get() };
        int[] want = { stairs, slabs, blocks };
        int made = 0;
        Bench.Hand hand = null;
        for (int i = 0; i < kinds.length; i++) {
            Item it = kinds[i];
            int need = want[i] - builder.countCarried(s -> s.is(it));
            if (need <= 0) continue;
            int inStores = Market.stock(level, id, s -> s.is(it));
            if (inStores < need) {
                if (hand == null) hand = Bench.handOf(level, v, builder, null);
                Bench.Plan plan = Bench.plan(level, v, it, need - inStores, hand);
                if (plan.ok()) {
                    ItemStack out = Bench.make(level, v, plan, builder, hand);
                    made += out.getCount();
                }
            }
            builder.drawFrom(heart, s -> s.is(it), need, r);
        }
        if (made > 0) {
            builder.brain("cut " + made + " pieces of thatch for the roof out of the stores' straw");
            LOG.info("[MCA-WORK] {} of {} made {} pieces of thatch for a roof", builder.displayNameCap(), Villages.name(id), made);
        }
    }

    private static final org.slf4j.Logger LOG = com.mojang.logging.LogUtils.getLogger();

    // ------------------------------------------------------------------ re-roofed with the ages

    /**
     * What a thatched roof's piece becomes as the town comes into an age (Ages.look): from the Stone Age, tiles (brick),
     * from the Iron Age slate, set as the thatch was. Null when it is no thatch, or the town is still in the Wood Age.
     */
    @Nullable
    public static BlockState reroof(Villages.Age age, Blueprints.Style style, BlockState now) {
        if (!isThatch(now) || age.ordinal() < Villages.Age.STONE.ordinal() || !BuildGoal.isRoof(style)) return null;
        boolean iron = age.ordinal() >= Villages.Age.IRON.ordinal();
        Block to;
        if (now.getBlock() instanceof StairBlock) to = iron ? Blocks.DEEPSLATE_TILE_STAIRS : Blocks.BRICK_STAIRS;
        else if (now.getBlock() instanceof SlabBlock) to = iron ? Blocks.DEEPSLATE_TILE_SLAB : Blocks.BRICK_SLAB;
        else to = iron ? Blocks.DEEPSLATE_TILES : Blocks.BRICKS;
        return Ages.like(now, to);
    }

    /**
     * What the stores can pay for in place of a thatched piece (Ages.affordable): the tiles or slate wanted, else stone
     * bricks (cobble at a pinch), else nothing yet (the thatch stays till they can).
     */
    @Nullable
    public static BlockState affordable(ServerLevel level, Villages.Village v, BlockState want, BlockState now) {
        if (Masonry.can(level, v, want.getBlock())) return want;
        Block stone = Masonry.stoneRoof(level, v, want.getBlock());
        if (stone == null) return null;
        return stone == Blocks.STONE_BRICKS ? stone.defaultBlockState() : Ages.like(now, stone);
    }

    // ------------------------------------------------------------------ fire

    /** The thatched cells of each of the town's houses (and other buildings), looked at every five minutes. */
    @SuppressWarnings("unchecked")
    static Map<BlockPos, List<BlockPos>> roofs(ServerLevel level, UUID village) {
        long now = level.getGameTime();
        Object[] c = ROOFS.get(village);
        if (c != null && now - (Long) c[0] < ROOFS_LOOK && now >= (Long) c[0]) return (Map<BlockPos, List<BlockPos>>) c[1];
        Map<BlockPos, List<BlockPos>> out = new java.util.LinkedHashMap<>();
        for (Ledger.Building b : Ledger.buildings(village)) {
            if (!level.isLoaded(b.anchor())) continue;
            String plan = Ages.drawing(village, b);
            if (!Blueprints.has(plan)) continue;
            List<BlockPos> cells = new ArrayList<>();
            for (BuildGoal.Placement p : BuildGoal.plan(plan, b.anchor(), b.facing(), 13)) {
                if (p.part() != BuildGoal.Part.BLOCK || !BuildGoal.isRoof(p.style())) continue;
                if (isThatch(level.getBlockState(p.pos()))) cells.add(p.pos());
            }
            if (!cells.isEmpty()) out.put(b.anchor(), cells);
        }
        ROOFS.put(village, new Object[]{ now, out });
        return out;
    }

    /** Forget a town's roofs (a fire, a re-roofing): looked at afresh. */
    static void forgetRoofs(UUID village) {
        ROOFS.remove(village);
    }

    /**
     * A spark from a chimney onto a thatched roof, perhaps (FireSafety.tick, once a minute): of an evening or at a meal,
     * with the hearths lit, the more thatched houses the likelier, three times as likely in a drought and twice in dry
     * weather, half as likely in the rain; the fire watch near stamps it out. Where it caught, or null.
     */
    @Nullable
    public static BlockPos chimneySpark(ServerLevel level, Villages.Village v, Disasters.Town t) {
        UUID id = v.id();
        long now = level.getGameTime();
        if (!Boolean.TRUE.equals(sparkForTests)) {
            if (Boolean.FALSE.equals(sparkForTests) || !Disasters.on()) return null;
            if (now - SPARK_LOOKED.getOrDefault(id, -100000L) < FireSafety.SPARK_LOOK) return null;
            SPARK_LOOKED.put(id, now);
            long tod = level.getDayTime() % 24000L;
            boolean hearths = tod >= 11000L && tod < 14500L || tod >= 23000L || tod < 1500L || tod >= 5500L && tod < 6500L;
            if (!hearths) return null;
        }
        Map<BlockPos, List<BlockPos>> roofs = roofs(level, id);
        if (roofs.isEmpty()) return null;
        if (!Boolean.TRUE.equals(sparkForTests)) {
            double weather = t.drought ? 3.0 : t.dryDays >= 3 ? 2.0 : Disasters.raining(level, v) ? 0.5 : 1.0;
            double many = Math.min(3.0, roofs.size() / 4.0);
            double p = weather * many / (com.jrpetty.mcassistant.AssistantConfig.villageSparkDays() * (24000.0 / FireSafety.SPARK_LOOK) / 3.0);
            if (level.getRandom().nextDouble() >= p) return null;
        }
        List<BlockPos> houses = new ArrayList<>(roofs.keySet());
        BlockPos house = houses.get(level.getRandom().nextInt(houses.size()));
        List<BlockPos> cells = roofs.get(house);
        BlockPos at = null;
        for (int tries = 0; tries < 8 && at == null; tries++) {
            BlockPos c = cells.get(level.getRandom().nextInt(cells.size()));
            if (isThatch(level.getBlockState(c)) && level.getBlockState(c.above()).isAir() && BaseFireBlock.canBePlacedAt(level, c.above(), net.minecraft.core.Direction.UP)) {
                at = c.above();
            }
        }
        if (at == null) return null;
        long day = level.getDayTime() / 24000L;
        VillageFolkEntity watch = FireSafety.watcher(level, id);
        if (watch != null && watch.blockPosition().distSqr(at) <= 16 * 16 && !Boolean.TRUE.equals(sparkForTests)) {
            watch.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
            FolkTalk.speak(watch, FolkTalk.pick(level.getRandom(), "A spark on the thatch! Got it.", "Off the roof with you — there.",
                "That chimney wants sweeping. Stamped it out."));
            Disasters.record(id, day, "a spark from a chimney on a thatched roof was put out by " + watch.displayNameCap() + " on the fire watch");
            return null;
        }
        level.setBlockAndUpdate(at, BaseFireBlock.getState(level, at));
        level.playSound(null, at, SoundEvents.FIRECHARGE_USE, SoundSource.BLOCKS, 0.6F, 1.2F);
        String what = "the house";
        for (Ledger.Building b : Ledger.buildings(id)) if (b.anchor().equals(house)) { what = FireBrigade.named(b.structure()); break; }
        FireSafety.sparked(id, at, now, "a chimney onto the thatch of " + what);
        t.sparks++;
        Disasters.dirty();
        WorkTools.bump(id, "work.thatch.sparks");
        forgetRoofs(id);
        LOG.info("[MCA-WORK] {}: a chimney's spark caught the thatch at {}", Villages.name(id), at.toShortString());
        return at;
    }

    /** Tests: a spark from a chimney onto the town's thatch now. Where it caught, or null. */
    @Nullable
    public static BlockPos sparkNowForTests(ServerLevel level, UUID village) {
        Villages.Village v = Villages.get(village);
        if (v == null) return null;
        Boolean was = sparkForTests;
        sparkForTests = true;
        forgetRoofs(village);
        try {
            return chimneySpark(level, v, Disasters.town(village));
        } finally {
            sparkForTests = was;
        }
    }

    /** The thatch's lines for /village items work. */
    static List<String> report(ServerLevel level, Villages.Village v) {
        List<String> out = new ArrayList<>();
        Map<BlockPos, List<BlockPos>> roofs = roofs(level, v.id());
        int sparks = WorkTools.count(v.id(), "work.thatch.sparks");
        if (!roofs.isEmpty() || thatching(v.id()) || sparks > 0) {
            out.add("Thatch: " + roofs.size() + (roofs.size() == 1 ? " building" : " buildings") + " roofed in it"
                + (thatching(v.id()) ? ", the town roofing in it while its wheat holds out" : Villages.ageOf(v.id()).ordinal() >= Villages.Age.STONE.ordinal()
                    ? ", being re-roofed in tiles as the age makes its buildings over" : "")
                + (sparks > 0 ? "; " + sparks + (sparks == 1 ? " spark from a chimney has" : " sparks from chimneys have") + " set it alight" : "") + ".");
        }
        return out;
    }
}
