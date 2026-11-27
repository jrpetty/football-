package com.jrpetty.mcassistant.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.animal.SnowGolem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CarvedPumpkinBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [batchB] Winter in town: the cold, and in a snowy town the children's snowmen.
 *
 * <p>In a town where snow falls (Sweepers.snowy: a snowy biome, or high in the mountains), on a winter
 * afternoon one of the children at play goes off to build a snowman by the playground (the park, or the
 * square), two in a winter at the most. It gathers the snow lying round about with a shovel borrowed from the
 * stores (the snowballs as the game gives them; by hand, snow gives nothing), makes up what that falls short
 * of with the snowballs the sweeper banked in the stores, and packs four snowballs to a block of snow, as
 * the crafting grid does: two blocks for the body, a third for the head, or, if the stores have one, a
 * carved pumpkin for a face. And as in the game, two blocks of snow under a carved pumpkin come alive: the
 * snowman walks off a snow golem, and the children are beside themselves. A snowman of snow stands by the
 * playground till the thaw (the first day of spring), when it goes, as snow does.
 *
 * <p>Short of snow, there is no snowman (the child says so, once a winter). Everywhere, folk say it is cold
 * in winter, and talk of snow where it snows and of the frost where it does not (Seasons.talk).
 */
public final class Winter {

    private Winter() {}

    /** Snowmen in a winter at most; snowballs to a block of snow. */
    static final int MOST = 2, BALLS = 4;
    /** How far round the snowman's spot a child gathers the lying snow. */
    static final int GATHER = 6;

    /** A child off to build a snowman: who, where, since when (game time). */
    record Build(UUID child, BlockPos at, long since) {}

    private static final Map<UUID, Build> BUILDS = new ConcurrentHashMap<>();
    /** The winter a town's children last found too little snow (year * 4 + 3): said once. */
    private static final Map<UUID, Integer> SHORT = new ConcurrentHashMap<>();

    public static void resetForTests() {
        BUILDS.clear();
        SHORT.clear();
    }

    /** Does snow fall on this town (Sweepers.snowy)? */
    static boolean snowy(ServerLevel level, Villages.Village v) {
        return Sweepers.snowy(level, v);
    }

    /** The playground: the park's middle, else the square (as the children's games have it: Families). */
    static BlockPos playground(Villages.Village v) {
        com.jrpetty.mcassistant.village.Ledger.Building park = Park.nearest(v.id(), v.centre(), 80);
        return park != null ? Park.layout(park).centre() : v.centre();
    }

    /** Once a second (Festivals.tick): on a snowy winter's afternoon, a child sent off to build a snowman. */
    static void tick(ServerLevel level, Villages.Village v, Festivals.Town town, long day, long t) {
        UUID id = v.id();
        if (Seasons.season(id, day) != Seasons.Season.WINTER) return;
        int winter = Seasons.year(id, day) * 4 + 3;
        if (town.snowmenWinter != winter) {
            town.snowmenWinter = winter;
            town.snowmen = 0;
            Festivals.dirty();
        }
        if (town.snowmen >= MOST || !snowy(level, v) || level.isRaining() && level.isThundering()) return;
        if (t < Families.PLAY_FROM || t >= Families.PLAY_TO || SHORT.getOrDefault(id, -1) == winter) return;
        Build b = BUILDS.get(id);
        if (b != null && level.getGameTime() - b.since() < 1600L) return;
        BlockPos ground = playground(v);
        VillageFolkEntity child = null;
        for (AssistantEntity a : Villages.folkOf(id)) {
            if (!(a instanceof VillageFolkEntity k) || !Families.childFree(k) || k.isShowcase() || School.doing(k) != null) continue;
            if (k.blockPosition().distSqr(ground) > 48 * 48) continue;
            child = k;
            break;
        }
        if (child == null) return;
        BlockPos at = spot(level, v, ground);
        if (at == null) return;
        BUILDS.put(id, new Build(child.getUUID(), at, level.getGameTime()));
    }

    /** Ground for a snowman a few blocks off the playground's middle: firm, and three blocks of open air over it. */
    @Nullable
    static BlockPos spot(ServerLevel level, Villages.Village v, BlockPos ground) {
        Festivals.Town town = Festivals.town(v.id());
        for (int r = 4; r <= 8; r++) {
            for (Direction d : new Direction[]{ Direction.EAST, Direction.WEST, Direction.NORTH, Direction.SOUTH }) {
                for (int side = -1; side <= 1; side++) {
                    BlockPos col = ground.relative(d, r).relative(d.getClockWise(), side * 2);
                    BlockPos at = Festivals.groundAt(level, col.getX(), col.getZ());
                    if (at == null || Math.abs(at.getY() - ground.getY()) > 3) continue;
                    BlockState floor = level.getBlockState(at.below());
                    if (!floor.isFaceSturdy(level, at.below(), Direction.UP) || !floor.getFluidState().isEmpty()) continue;
                    if (!Festivals.open(level, at) || !Festivals.open(level, at.above()) || !Festivals.open(level, at.above(2))) continue;
                    boolean taken = false;
                    for (Festivals.Placed p : town.placed) if (p.pos().distManhattan(at) < 3) taken = true;
                    if (!taken) return at;
                }
            }
        }
        return null;
    }

    /** The builder's part, from its own tick (Festivals.hold): to the spot, and the snowman built. True while it is about it. */
    static boolean hold(VillageFolkEntity f, ServerLevel level) {
        if (!f.isBaby() || f.ownerId() == null) return false;
        Build b = BUILDS.get(f.ownerId());
        if (b == null || !b.child().equals(f.getUUID())) return false;
        Villages.Village v = Villages.get(f.ownerId());
        if (v == null || level.getGameTime() - b.since() > 1600L || !Families.childFree(f)) {
            BUILDS.remove(f.ownerId());
            return false;
        }
        f.hobbyNow = "building a snowman by the playground";
        f.lastLeisureTick = f.tickCount;
        if (f.blockPosition().distSqr(b.at()) > 2.5 * 2.5) {
            if (f.getNavigation().isDone() || f.tickCount % 40 == 0) f.walkTo(b.at(), 1.0D);
            return true;
        }
        f.getNavigation().stop();
        build(level, v, f, b.at());
        BUILDS.remove(f.ownerId());
        return true;
    }

    /**
     * The snowman itself, at this spot, by this child: the snow gathered and made up from the stores, packed
     * into blocks, set up, and its head (a pumpkin face, if the stores have one). Returns what came of it.
     */
    static String build(ServerLevel level, Villages.Village v, VillageFolkEntity child, BlockPos at) {
        UUID id = v.id();
        long day = level.getDayTime() / 24000L;
        boolean face = Market.stock(level, id, s -> s.is(Items.CARVED_PUMPKIN)) > 0;
        int want = (face ? 2 : 3) * BALLS;
        int balls = gather(level, v, child, at, want);
        if (balls < want) {
            int short_ = want - balls;
            int banked = Math.min(short_, Market.stock(level, id, s -> s.is(Items.SNOWBALL)));
            if (banked > 0 && TownWork.take(level, v, s -> s.is(Items.SNOWBALL), banked)) balls += banked;
        }
        if (balls < want) {
            if (balls > 0) Crafts.store(level, v, new ItemStack(Items.SNOWBALL, balls));
            SHORT.put(id, Seasons.year(id, day) * 4 + 3);
            FolkTalk.speak(child, FolkTalk.pick(child.getRandom(), "Not enough snow for a snowman yet!", "Aww — the snow's too thin to roll."));
            return "not enough snow (" + balls + " snowballs of " + want + ")";
        }
        Festivals.Town town = Festivals.town(id);
        // Four snowballs to a block, as the crafting grid packs them.
        Festivals.put(level, town, at, Blocks.SNOW_BLOCK.defaultBlockState(), "snowman", day, ItemStack.EMPTY);
        Festivals.put(level, town, at.above(), Blocks.SNOW_BLOCK.defaultBlockState(), "snowman", day, ItemStack.EMPTY);
        child.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
        level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, Blocks.SNOW_BLOCK.defaultBlockState()),
            at.getX() + 0.5, at.getY() + 1.2, at.getZ() + 0.5, 20, 0.4, 0.6, 0.4, 0.05);
        level.playSound(null, at, SoundEvents.SNOW_PLACE, SoundSource.BLOCKS, 1.0F, 1.0F);
        town.snowmen++;
        Festivals.dirty();
        child.persona().remember(day, "I built a snowman by the playground", 3);
        if (face) {
            ItemStack pumpkin = Crafts.takeOne(level, v, s -> s.is(Items.CARVED_PUMPKIN));
            if (!pumpkin.isEmpty()) {
                // Set on its shoulders facing the playground, as a player sets one: and as in the game it wakes.
                Direction look = Direction.getNearest(playground(v).getX() - at.getX(), 0, playground(v).getZ() - at.getZ());
                if (look.getAxis() == Direction.Axis.Y) look = Direction.SOUTH;
                level.setBlockAndUpdate(at.above(2), Blocks.CARVED_PUMPKIN.defaultBlockState().setValue(CarvedPumpkinBlock.FACING, look));
                List<SnowGolem> woke = level.getEntitiesOfClass(SnowGolem.class, new AABB(at).inflate(3));
                // The blocks it was made of are the golem now: nothing of the snowman to take down at the thaw.
                town.placed.removeIf(p -> p.feast().equals("snowman") && (p.pos().equals(at) || p.pos().equals(at.above()))
                    && !level.getBlockState(p.pos()).is(Blocks.SNOW_BLOCK));
                if (!level.getBlockState(at.above(2)).is(Blocks.CARVED_PUMPKIN)) {
                    FolkTalk.speak(child, FolkTalk.pick(child.getRandom(), "It's alive! Our snowman's walking!", "Look, look — it moved!"));
                    Villages.tell(id, day, child.displayNameCap() + " built a snowman by the playground, and it came to life");
                    return "a snowman with a pumpkin face, and it came to life" + (woke.isEmpty() ? "" : " (" + woke.size() + " snow golem)");
                }
                town.placed.add(new Festivals.Placed(at.above(2), "snowman", day, Blocks.CARVED_PUMPKIN, pumpkin.copyWithCount(1)));
                Festivals.dirty();
                return "a snowman with a pumpkin face";
            }
        }
        Festivals.put(level, town, at.above(2), Blocks.SNOW_BLOCK.defaultBlockState(), "snowman", day, ItemStack.EMPTY);
        FolkTalk.speak(child, FolkTalk.pick(child.getRandom(), "Look! A snowman!", "Our snowman's finished — isn't he grand?",
            "I made a snowman! Come and see!"));
        Villages.tell(id, day, child.displayNameCap() + " built a snowman by the playground");
        return "a snowman of snow";
    }

    /**
     * The snow lying round the spot, gathered with a shovel out of the stores (lent and put back, a little the
     * worse for it): the snowballs as the game gives them for each layer. Nothing without a shovel.
     */
    static int gather(ServerLevel level, Villages.Village v, VillageFolkEntity child, BlockPos at, int want) {
        ItemStack spade = Crafts.takeOne(level, v, s -> s.is(ItemTags.SHOVELS));
        if (spade.isEmpty()) return 0;
        int balls = 0;
        try {
            for (BlockPos p : BlockPos.betweenClosed(at.offset(-GATHER, -2, -GATHER), at.offset(GATHER, 2, GATHER))) {
                if (balls >= want || spade.isEmpty()) break;
                BlockState st = level.getBlockState(p);
                if (!st.is(Blocks.SNOW) || !level.isLoaded(p)) continue;
                List<ItemStack> drops = spade.isCorrectToolForDrops(st) ? Block.getDrops(st, level, p.immutable(), null, child, spade) : List.of();
                level.levelEvent(2001, p, Block.getId(st));
                level.removeBlock(p, false);
                for (ItemStack d : drops) if (d.is(Items.SNOWBALL)) balls += d.getCount();
                spade.hurtAndBreak(1, level, child, item -> { });
            }
        } finally {
            if (!spade.isEmpty()) Crafts.store(level, v, spade);
        }
        if (balls > want) {
            Crafts.store(level, v, new ItemStack(Items.SNOWBALL, balls - want));
            balls = want;
        }
        return balls;
    }

    // ------------------------------------------------------------------ for the tests

    /** A snowman built now by this child at the playground's nearest open ground (whatever the season). What came of it. */
    public static String buildForTests(ServerLevel level, Villages.Village v, VillageFolkEntity child) {
        BlockPos at = spot(level, v, playground(v));
        if (at == null) return "no ground for it";
        return build(level, v, child, at) + " at " + at.toShortString();
    }

    /** Who is off to build a snowman just now, or null. */
    @Nullable
    public static UUID builderForTests(UUID village) {
        Build b = BUILDS.get(village);
        return b == null ? null : b.child();
    }
}
