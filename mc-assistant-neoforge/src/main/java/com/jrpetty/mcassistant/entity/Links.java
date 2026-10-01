package com.jrpetty.mcassistant.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The links between the trades that nothing else made: where the things the crafts need come
 * from, so that every one has a maker in the village and not only in a player's pack.
 * <ul>
 * <li>the farmers plant the sugar cane they brought along the field's water, and cut it (the
 *     enchanter's paper, the brewer's sugar, the café's pies);</li>
 * <li>the rancher milks the cows with a bucket (the café's cakes) and goes out with a lead for
 *     wild animals when the pen has no pair ({@link Drover});</li>
 * <li>the smelter digs sand off the river bed or the beach and fires it into glass (the
 *     bottles of the brewer, the beekeeper and the café; the windows);</li>
 * <li>the watch drinks the brewer's healing when a fight goes badly.</li>
 * </ul>
 */
public final class Links {

    private Links() {}

    private static final Map<UUID, Integer> LAST = new ConcurrentHashMap<>();

    public static void resetForTests() { LAST.clear(); }

    /** The links a hand of each trade keeps up, every couple of minutes. Returns whether it is busy with one now. */
    public static boolean tend(VillageFolkEntity f, ServerLevel level) {
        int last = LAST.getOrDefault(f.getUUID(), -100000);
        if (f.tickCount - last < 2400 && f.tickCount >= last) return false;
        LAST.put(f.getUUID(), f.tickCount);
        return switch (f.stationTask()) {
            case FARM -> cane(f, level) != null;
            case RANCH -> milk(f, level) != null || Drover.consider(f, level);
            case SMELT -> sand(f, level) != null;
            default -> false;
        };
    }

    // ------------------------------------------------------------------ cane

    /** The most sugar cane a farm grows: a row along its ditch. */
    static final int CANE = 6;

    /**
     * A farmer's sugar cane: the tall cane on the field's water cut (the bottom left to grow again),
     * and the cuttings it carries planted on the water's edge, up to a row of six. Returns what it
     * did, or null.
     */
    @Nullable
    public static String cane(VillageFolkEntity f, ServerLevel level) {
        WorkZone z = f.workZone();
        if (z == null) return null;
        BlockPos c = z.center();
        int r = Math.min(8, z.radius());
        if (!Land.areaLoaded(level, c, r + 1)) return null;
        int bases = 0, cut = 0;
        java.util.List<BlockPos> edge = new java.util.ArrayList<>();
        for (BlockPos p : BlockPos.betweenClosed(c.offset(-r, -2, -r), c.offset(r, 2, r))) {
            BlockState st = level.getBlockState(p);
            if (st.is(Blocks.SUGAR_CANE)) {
                if (level.getBlockState(p.below()).is(Blocks.SUGAR_CANE)) continue;
                bases++;
                // Everything above the bottom is cut, from the top down.
                int tall = 1;
                while (tall < 4 && level.getBlockState(p.above(tall)).is(Blocks.SUGAR_CANE)) tall++;
                for (int k = tall - 1; k >= 1; k--) {
                    level.setBlock(p.above(k), Blocks.AIR.defaultBlockState(), 2 | 16);
                    cut++;
                }
                continue;
            }
            if (!level.getFluidState(p).is(net.minecraft.tags.FluidTags.WATER)) continue;
            for (Direction d : Direction.Plane.HORIZONTAL) {
                BlockPos g = p.relative(d);
                BlockState gs = level.getBlockState(g);
                boolean ground = gs.is(Blocks.GRASS_BLOCK) || gs.is(Blocks.DIRT) || gs.is(Blocks.COARSE_DIRT)
                    || gs.is(Blocks.PODZOL) || gs.is(Blocks.SAND) || gs.is(Blocks.RED_SAND)
                    || (gs.is(Blocks.FARMLAND) && level.getBlockState(g.above()).isAir());
                if (ground && level.getBlockState(g.above()).isAir()) edge.add(g.immutable());
            }
        }
        if (cut > 0) {
            ItemStack cane = new ItemStack(Items.SUGAR_CANE, cut);
            ItemStack left = f.insertItem(cane);
            if (!left.isEmpty()) f.spawnAtLocation(left);
            f.swing(InteractionHand.MAIN_HAND);
            f.brain("cut " + cut + " sugar cane");
            return "cut " + cut + " sugar cane";
        }
        if (bases >= CANE || edge.isEmpty() || f.countCarried(s -> s.is(Items.SUGAR_CANE)) < 1) return null;
        BlockPos g = edge.get(level.getRandom().nextInt(edge.size()));
        if (level.getBlockState(g).is(Blocks.FARMLAND)) level.setBlock(g, Blocks.DIRT.defaultBlockState(), 3);
        if (!Blocks.SUGAR_CANE.defaultBlockState().canSurvive(level, g.above())) return null;
        f.removeMatching(s -> s.is(Items.SUGAR_CANE), 1);
        level.setBlock(g.above(), Blocks.SUGAR_CANE.defaultBlockState(), 3);
        level.playSound(null, g.above(), SoundEvents.CROP_PLANTED, SoundSource.BLOCKS, 1.0F, 1.0F);
        f.swing(InteractionHand.MAIN_HAND);
        f.brain("planted sugar cane by the water");
        return "planted sugar cane by the water";
    }

    // ------------------------------------------------------------------ milk

    /**
     * The rancher's milking: a bucket (its own, or one from the stores the café sent back) to a
     * cow in the pen, when the stores are short of milk. Returns what it did, or null.
     */
    @Nullable
    public static String milk(VillageFolkEntity f, ServerLevel level) {
        UUID village = f.ownerId();
        Villages.Village v = village == null ? null : Villages.get(village);
        if (v == null) return null;
        if (Market.stock(level, village, s -> s.is(Items.MILK_BUCKET)) + f.countCarried(s -> s.is(Items.MILK_BUCKET)) >= 3) return null;
        java.util.List<Cow> cows = level.getEntitiesOfClass(Cow.class, f.getBoundingBox().inflate(12), c -> c.isAlive() && !c.isBaby());
        if (cows.isEmpty()) return null;
        if (f.countCarried(s -> s.is(Items.BUCKET)) < 1) {
            if (!Crafts.take(level, v, s -> s.is(Items.BUCKET), 1)) return null;
            f.insertItem(new ItemStack(Items.BUCKET));
        }
        if (f.removeMatching(s -> s.is(Items.BUCKET), 1) != 1) return null;
        ItemStack left = f.insertItem(new ItemStack(Items.MILK_BUCKET));
        if (!left.isEmpty()) Crafts.store(level, v, left);
        Cow cow = cows.get(0);
        f.getLookControl().setLookAt(cow);
        f.swing(InteractionHand.MAIN_HAND);
        level.playSound(null, cow.blockPosition(), SoundEvents.COW_MILK, SoundSource.NEUTRAL, 1.0F, 1.0F);
        f.brain("milked a cow");
        return "milked a cow";
    }

    // ------------------------------------------------------------------ sand

    /**
     * The smelter's sand, for glass: when the stores are short of glass and there is no sand to
     * fire, it digs some, off the river bed or the shore (the water closes over the hole) or out
     * of open sand away from the town. Returns what it did, or null.
     */
    @Nullable
    public static String sand(VillageFolkEntity f, ServerLevel level) {
        UUID village = f.ownerId();
        Villages.Village v = village == null ? null : Villages.get(village);
        if (v == null) return null;
        java.util.function.Predicate<ItemStack> sand = s -> s.is(Items.SAND) || s.is(Items.RED_SAND);
        if (Market.stock(level, village, s -> s.is(Items.GLASS) || s.is(Items.GLASS_BOTTLE)) >= 16) return null;
        if (f.countCarried(sand) + Market.stock(level, village, sand) >= 4) return null;
        BlockPos heart = v.centre();
        int dug = 0;
        for (int ring = 4; ring <= 40 && dug < 8; ring += 2) {
            if (!Land.areaLoaded(level, heart, ring)) break;
            for (int dx = -ring; dx <= ring && dug < 8; dx += 2) {
                for (int dz = -ring; dz <= ring && dug < 8; dz += 2) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) continue;
                    int x = heart.getX() + dx, z = heart.getZ() + dz;
                    BlockPos p = new BlockPos(x, level.getHeight(Heightmap.Types.OCEAN_FLOOR, x, z) - 1, z);
                    BlockState st = level.getBlockState(p);
                    if (!st.is(Blocks.SAND) && !st.is(Blocks.RED_SAND)) continue;
                    boolean wet = level.getFluidState(p.above()).is(net.minecraft.tags.FluidTags.WATER);
                    if (!wet) {
                        // Dry sand only well away from the houses, and never from under a building.
                        if (ring < 16 || Land.inABuilding(village, p)) continue;
                        if (!level.getBlockState(p.below()).isSolid()) continue;
                    }
                    level.setBlock(p, wet ? Blocks.WATER.defaultBlockState() : Blocks.AIR.defaultBlockState(), 3);
                    ItemStack got = new ItemStack(st.getBlock().asItem());
                    ItemStack left = f.insertItem(got);
                    if (!left.isEmpty()) Crafts.store(level, v, left);
                    dug++;
                }
            }
        }
        if (dug == 0) return null;
        f.swing(InteractionHand.MAIN_HAND);
        f.brain("dug " + dug + " sand for glass");
        return "dug " + dug + " sand for glass";
    }

    // ------------------------------------------------------------------ the watch's potions

    /** Is this one of the brewer's healing potions (or regeneration)? */
    public static boolean healing(ItemStack s) {
        if (!s.is(Items.POTION)) return false;
        PotionContents pc = s.getOrDefault(DataComponents.POTION_CONTENTS, PotionContents.EMPTY);
        return pc.is(Potions.HEALING) || pc.is(Potions.STRONG_HEALING) || pc.is(Potions.REGENERATION)
            || pc.is(Potions.LONG_REGENERATION) || pc.is(Potions.STRONG_REGENERATION);
    }

    /** A guard badly hurt drinks a healing potion it carries (Raids.arm gives it one from the stores). */
    public static boolean drinkIfHurt(VillageFolkEntity f) {
        if (f.getHealth() > f.getMaxHealth() * 0.4F) return false;
        for (ItemStack s : f.getInventoryItems()) {
            if (!healing(s)) continue;
            PotionContents pc = s.getOrDefault(DataComponents.POTION_CONTENTS, PotionContents.EMPTY);
            if (pc.is(Potions.HEALING)) f.heal(4.0F);
            else if (pc.is(Potions.STRONG_HEALING)) f.heal(8.0F);
            else f.addEffect(new MobEffectInstance(MobEffects.REGENERATION, pc.is(Potions.STRONG_REGENERATION) ? 440 : 900,
                pc.is(Potions.STRONG_REGENERATION) ? 1 : 0));
            s.shrink(1);
            ItemStack left = f.insertItem(new ItemStack(Items.GLASS_BOTTLE));
            if (!left.isEmpty()) f.spawnAtLocation(left);
            f.level().playSound(null, f.blockPosition(), SoundEvents.GENERIC_DRINK, SoundSource.NEUTRAL, 1.0F, 1.0F);
            f.brain("drank a healing potion");
            return true;
        }
        return false;
    }

}
