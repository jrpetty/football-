package com.jrpetty.mcassistant.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.item.BannerItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BannerBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.StandingSignBlock;
import net.minecraft.world.level.block.entity.BannerBlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.RotationSegment;
import net.minecraft.world.level.levelgen.Heightmap;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * [war] The ground a war is fought on, and what an army puts on it: the battlefield its Captains choose, the
 * camps, the ram before a gate, and the cairn left when it is over.
 *
 * <p><b>The field.</b> Open, fairly flat ground between the two towns, off anybody's fields and buildings and
 * near the road: the stretch of the road from a third to two thirds of the way, and the ground a dozen blocks
 * either side of it, looked over once (a few hundred looks at the height of the ground, once a war) and the
 * flattest, clearest patch of it taken. Its name is the nearer town's ("Ashby Field"). On it each side may find
 * a rise behind its own line (for its archers), cover on a flank (trees: for a hidden reserve), and off to one
 * side a hill to watch from, which the players are told of. With the ground unknown (nobody near, its chunks
 * asleep) it is the middle of the road, and the battle is reckoned on paper anyway.
 *
 * <p><b>The camp.</b> Behind its line: a tent to every four (nine wool on four fence poles, out of the army's
 * supply), a campfire, the war banner planted by it. Struck at dawn, every block of it back in the supply.
 *
 * <p><b>The ram.</b> Three logs out of the supply, a beam laid on the road before the gate.
 *
 * <p><b>The cairn.</b> Where the lines met: five cobblestones out of the winner's stores, and a sign
 * ("The Battle of / Ashby Field / day 140 / Ashby v Brook").
 */
public final class Battlefields {

    private Battlefields() {}

    /** The wool and the poles of a tent. */
    static final int TENT_WOOL = 9, TENT_POLES = 4;
    /** The logs of a ram. */
    static final int RAM_LOGS = 3;
    /** How far apart the two lines draw up, centre to centre. */
    static final int GAP = 24;
    /** The field's size, to look over: so long (along the road between the towns) and so wide. */
    static final int LONG = 40, WIDE = 28;
    /** Steeper than this (the spread of the ground's height over the field) and it is no field for a battle. */
    static final int ROUGH = 6;

    /** The ground chosen: its middle, the way from the first town's side to the second's, what is on it. */
    public record Ground(BlockPos centre, double ax, double az, @Nullable BlockPos riseA, @Nullable BlockPos riseB,
                         @Nullable BlockPos coverA, @Nullable BlockPos coverB, @Nullable BlockPos view, String name, boolean known) {

        /** Perpendicular to the way between the lines (to the first side's right as it faces the second). */
        public double px() { return -az; }
        public double pz() { return ax; }

        /** A spot on the field: {@code along} towards the second side, {@code across} to the first side's right. */
        public BlockPos at(double along, double across) {
            return BlockPos.containing(centre.getX() + 0.5 + ax * along + px() * across, centre.getY(), centre.getZ() + 0.5 + az * along + pz() * across);
        }
    }

    /**
     * The battlefield between these towns: the flattest open patch near the road from a third to two thirds of
     * the way (from {@code a}'s side to {@code b}'s). With none of it to look at, the road's middle, unknown.
     */
    static Ground choose(ServerLevel level, Villages.Village a, Villages.Village b) {
        BlockPos ea = Roads.exit(a.centre(), b.centre()), eb = Roads.exit(b.centre(), a.centre());
        double dx = eb.getX() - ea.getX(), dz = eb.getZ() - ea.getZ(), len = Math.max(1.0, Math.sqrt(dx * dx + dz * dz));
        double ax = dx / len, az = dz / len;
        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;
        for (int i = 0; i <= 6; i++) {
            double t = 0.35 + i * 0.05;
            for (int side = -1; side <= 1; side++) {
                double cx = ea.getX() + dx * t - az * side * 14.0, cz = ea.getZ() + dz * t + ax * side * 14.0;
                double score = roughness(level, cx, cz, ax, az);
                if (score < 0) continue;
                score += Math.abs(t - 0.5) * 20.0 + Math.abs(side) * 2.0;          // the middle and the road preferred
                if (score < bestScore) {
                    bestScore = score;
                    best = BlockPos.containing(cx, 0, cz);
                }
            }
        }
        boolean known = best != null;
        if (best == null) best = BlockPos.containing(ea.getX() + dx * 0.5, a.centre().getY(), ea.getZ() + dz * 0.5);
        BlockPos centre = known ? surface(level, best) : best;
        Villages.Village nearer = centre.distSqr(a.centre()) <= centre.distSqr(b.centre()) ? a : b;
        String name = Villages.name(nearer.id()) + " Field";
        if (!known) return new Ground(centre, ax, az, null, null, null, null, null, name, false);
        return new Ground(centre, ax, az, rise(level, centre, ax, az, -1), rise(level, centre, ax, az, 1), cover(level, centre, ax, az, -1),
            cover(level, centre, ax, az, 1), view(level, centre, ax, az), name, true);
    }

    /**
     * How rough a field centred here is: the spread of the ground's height over it, worse for water, a crop, a
     * building's stone or a tree on it; -1 if it will not do at all (its ground not to hand, in a town, too steep).
     */
    static double roughness(ServerLevel level, double cx, double cz, double ax, double az) {
        int lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE, bad = 0;
        for (int i = -4; i <= 4; i++) {
            for (int j = -2; j <= 2; j++) {
                int x = Mth.floor(cx + ax * i * (LONG / 8.0) - az * j * (WIDE / 4.0)), z = Mth.floor(cz + az * i * (LONG / 8.0) + ax * j * (WIDE / 4.0));
                if (!level.hasChunk(x >> 4, z >> 4)) return -1;
                int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                lo = Math.min(lo, y);
                hi = Math.max(hi, y);
                BlockState st = level.getBlockState(new BlockPos(x, y - 1, z));
                if (!st.getFluidState().isEmpty() || st.is(Blocks.FARMLAND) || st.is(BlockTags.LOGS) || st.is(BlockTags.PLANKS)
                    || st.is(Blocks.COBBLESTONE) || st.is(Blocks.STONE_BRICKS) || st.is(BlockTags.CROPS)) bad++;
                if (level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) > y + 1) bad++;          // under a tree
            }
        }
        BlockPos mid = BlockPos.containing(cx, lo, cz);
        for (Villages.Village v : Villages.every()) {
            if (v.dim().equals(level.dimension()) && v.centre().distSqr(mid) < (double) Villages.VILLAGE_RANGE * Villages.VILLAGE_RANGE) return -1;
        }
        if (hi - lo > ROUGH || bad > 8) return -1;
        return (hi - lo) * 2.0 + bad * 3.0;
    }

    static BlockPos surface(ServerLevel level, BlockPos p) {
        if (!level.hasChunk(p.getX() >> 4, p.getZ() >> 4)) return p;
        return new BlockPos(p.getX(), level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, p.getX(), p.getZ()), p.getZ());
    }

    /** A rise behind one side's line ({@code side} -1 the first town's, 1 the second's), three blocks or more over the field. */
    @Nullable
    static BlockPos rise(ServerLevel level, BlockPos centre, double ax, double az, int side) {
        BlockPos best = null;
        int top = centre.getY() + 2;
        for (int back = 16; back <= 28; back += 4) {
            for (int across = -16; across <= 16; across += 8) {
                BlockPos p = BlockPos.containing(centre.getX() + ax * side * back - az * across, 0, centre.getZ() + az * side * back + ax * across);
                if (!level.hasChunk(p.getX() >> 4, p.getZ() >> 4)) continue;
                BlockPos s = surface(level, p);
                if (s.getY() > top) {
                    top = s.getY();
                    best = s;
                }
            }
        }
        return best;
    }

    /** Trees on one side's flank, near enough to hide a reserve behind. */
    @Nullable
    static BlockPos cover(ServerLevel level, BlockPos centre, double ax, double az, int side) {
        for (int across : new int[]{ 22, -22, 28, -28 }) {
            for (int back = 4; back <= 12; back += 4) {
                int x = Mth.floor(centre.getX() + ax * side * back - az * across), z = Mth.floor(centre.getZ() + az * side * back + ax * across);
                if (!level.hasChunk(x >> 4, z >> 4)) continue;
                int ground = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                if (level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) > ground + 2) {
                    // Behind the trees, on the field's side of them.
                    double in = across > 0 ? -5 : 5;
                    return surface(level, BlockPos.containing(x - az * in, 0, z + ax * in));
                }
            }
        }
        return null;
    }

    /** A hill off to one side to watch the field from: five blocks or more over it, thirty to fifty out. */
    @Nullable
    static BlockPos view(ServerLevel level, BlockPos centre, double ax, double az) {
        BlockPos best = null;
        int top = centre.getY() + 4;
        for (int across = -50; across <= 50; across += 10) {
            if (Math.abs(across) < 30) continue;
            for (int along = -16; along <= 16; along += 8) {
                int x = Mth.floor(centre.getX() + ax * along - az * across), z = Mth.floor(centre.getZ() + az * along + ax * across);
                if (!level.hasChunk(x >> 4, z >> 4)) continue;
                int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                if (y > top) {
                    top = y;
                    best = new BlockPos(x, y, z);
                }
            }
        }
        return best;
    }

    // ------------------------------------------------------------------ the camp

    /** A camp pitched: every block it put down (to take up again exactly), and its banner's cloth. */
    public static final class Camp {
        final List<BlockPos> wool = new ArrayList<>(), poles = new ArrayList<>();
        @Nullable BlockPos fire, banner;
        ItemStack bannerItem = ItemStack.EMPTY;
        final BlockPos centre;

        Camp(BlockPos centre) {
            this.centre = centre;
        }

        public BlockPos centre() { return centre; }
        public int tents() { return poles.size() / 2; }
        public boolean fire() { return fire != null; }
    }

    /**
     * Pitch the army's camp here, its tents in a ring round the fire, their doors to it, facing {@code ax, az}
     * (towards the enemy): out of its supply, as far as it goes; on open ground only.
     */
    static Camp pitch(ServerLevel level, Armies.Army army, BlockPos centre, double ax, double az, @Nullable ItemStack banner) {
        Camp c = new Camp(centre);
        if (!level.isLoaded(centre)) return c;
        BlockPos fire = surface(level, centre);
        if (army.carried(Items.CAMPFIRE) > 0 && open(level, fire, 0) && army.use(Items.CAMPFIRE, 1)) {
            level.setBlock(fire, Blocks.CAMPFIRE.defaultBlockState().setValue(CampfireBlock.LIT, true), 3);
            c.fire = fire;
        }
        if (banner != null && banner.getItem() instanceof BannerItem bi) {
            BlockPos at = surface(level, fire.offset(Mth.floor(ax * 2.0), 0, Mth.floor(az * 2.0)));
            if (open(level, at, 1)) {
                int rot = RotationSegment.convertToSegment(Armies.yawTo(ax, az) + 180.0F);
                level.setBlock(at, BannerBlock.byColor(bi.getColor()).defaultBlockState().setValue(BannerBlock.ROTATION, rot), 3);
                if (level.getBlockEntity(at) instanceof BannerBlockEntity be) {
                    be.applyComponentsFromItemStack(banner);
                    be.setChanged();
                }
                c.banner = at;
                c.bannerItem = banner.copy();
            }
        }
        int tents = Math.min(army.carried(Items.WHITE_WOOL) / TENT_WOOL, army.carried(Items.OAK_FENCE) / TENT_POLES);
        tents = Math.min(tents, Math.max(1, (army.fighters.size() + 3) / 4));
        for (int i = 0; i < tents; i++) {
            double ang = Math.PI * 2 * i / Math.max(1, tents) + 0.4;
            double ox = Math.cos(ang) * 7.0, oz = Math.sin(ang) * 7.0;
            BlockPos at = surface(level, centre.offset(Mth.floor(ox), 0, Mth.floor(oz)));
            Direction door = Direction.getNearest(-ox, 0, -oz);
            if (tent(level, at, door, c)) {
                army.use(Items.WHITE_WOOL, TENT_WOOL);
                army.use(Items.OAK_FENCE, TENT_POLES);
            }
        }
        return c;
    }

    /** Air over solid, dry ground for {@code height} blocks above this spot (and the spot itself). */
    static boolean open(ServerLevel level, BlockPos at, int height) {
        BlockState under = level.getBlockState(at.below());
        if (!under.isFaceSturdy(level, at.below(), Direction.UP) || !under.getFluidState().isEmpty()) return false;
        for (int y = 0; y <= height; y++) if (!level.getBlockState(at.above(y)).canBeReplaced()) return false;
        return true;
    }

    /** One tent, its ridge running in the door's line: two poles each end, the ridge and the sloping sides. */
    static boolean tent(ServerLevel level, BlockPos at, Direction door, Camp c) {
        Direction side = door.getClockWise();
        List<BlockPos> poles = new ArrayList<>(), wool = new ArrayList<>();
        for (int k = -1; k <= 1; k++) {
            BlockPos mid = at.relative(door, k);
            if (k != 0) {
                poles.add(mid);
                poles.add(mid.above());
            }
            wool.add(mid.above(2));
            wool.add(mid.relative(side).above());
            wool.add(mid.relative(side.getOpposite()).above());
        }
        for (int k = -1; k <= 1; k++) {
            for (int s = -1; s <= 1; s++) if (!open(level, at.relative(door, k).relative(side, s), 2)) return false;
        }
        BlockState pole = Blocks.OAK_FENCE.defaultBlockState(), cloth = Blocks.WHITE_WOOL.defaultBlockState();
        for (BlockPos p : poles) level.setBlock(p, pole, 3);
        for (BlockPos p : wool) level.setBlock(p, cloth, 3);
        c.poles.addAll(poles);
        c.wool.addAll(wool);
        return true;
    }

    /** Strike the camp: every block of it taken up (if it is still as it was put) and back into the supply; its banner back. */
    static ItemStack strike(ServerLevel level, Armies.Army army, @Nullable Camp c) {
        if (c == null) return ItemStack.EMPTY;
        for (BlockPos p : c.wool) if (takeUp(level, p, Blocks.WHITE_WOOL)) army.supply.merge(Items.WHITE_WOOL, 1, Integer::sum);
        for (BlockPos p : c.poles) if (takeUp(level, p, Blocks.OAK_FENCE)) army.supply.merge(Items.OAK_FENCE, 1, Integer::sum);
        if (c.fire != null && takeUp(level, c.fire, Blocks.CAMPFIRE)) army.supply.merge(Items.CAMPFIRE, 1, Integer::sum);
        ItemStack banner = ItemStack.EMPTY;
        if (c.banner != null && level.isLoaded(c.banner) && level.getBlockState(c.banner).getBlock() instanceof BannerBlock) {
            level.setBlock(c.banner, Blocks.AIR.defaultBlockState(), 3);
            banner = c.bannerItem;
        }
        c.wool.clear();
        c.poles.clear();
        c.fire = null;
        c.banner = null;
        return banner;
    }

    static boolean takeUp(ServerLevel level, BlockPos p, net.minecraft.world.level.block.Block was) {
        if (!level.isLoaded(p) || !level.getBlockState(p).is(was)) return false;
        level.setBlock(p, Blocks.AIR.defaultBlockState(), 3);
        return true;
    }

    // ------------------------------------------------------------------ the ram

    /** The ram, three logs laid end to end on the road, pointing at the gate. Where its head lies, or null (no logs). */
    @Nullable
    static List<BlockPos> ram(ServerLevel level, Armies.Army army, BlockPos gate, Direction out) {
        if (army.carried(Items.OAK_LOG) < RAM_LOGS || !level.isLoaded(gate)) return null;
        List<BlockPos> beam = new ArrayList<>();
        for (int k = 2; k < 2 + RAM_LOGS; k++) {
            BlockPos p = surface(level, gate.relative(out, k));
            if (!level.getBlockState(p).canBeReplaced()) return null;
            beam.add(p);
        }
        army.use(Items.OAK_LOG, RAM_LOGS);
        BlockState log = Blocks.OAK_LOG.defaultBlockState().setValue(RotatedPillarBlock.AXIS, out.getAxis());
        for (BlockPos p : beam) level.setBlock(p, log, 3);
        return beam;
    }

    /** The ram taken apart: its logs back into the supply. */
    static void unram(ServerLevel level, Armies.Army army, @Nullable List<BlockPos> beam) {
        if (beam == null) return;
        for (BlockPos p : beam) if (takeUp(level, p, Blocks.OAK_LOG)) army.supply.merge(Items.OAK_LOG, 1, Integer::sum);
        beam.clear();
    }

    // ------------------------------------------------------------------ the cairn

    /**
     * The battle marked where the lines met, off the road: a cairn of five cobblestones and a sign, out of the
     * winner's stores. Where its sign stands, or null (nothing to build it of, or no open ground).
     */
    @Nullable
    static BlockPos cairn(ServerLevel level, Villages.Village winner, Ground g, String[] lines) {
        BlockPos at = surface(level, g.at(0, 9));
        if (!level.isLoaded(at) || !open(level, at, 2) || !open(level, at.relative(Direction.EAST), 1)) return null;
        if (!TownWork.take(level, winner, s -> s.is(Items.COBBLESTONE), 5)) return null;
        boolean sign = TownWork.take(level, winner, s -> s.is(net.minecraft.tags.ItemTags.SIGNS), 1)
            || TownWork.take(level, winner, s -> s.is(net.minecraft.tags.ItemTags.PLANKS), 2);
        if (!sign) {
            TownWork.give(level, winner, new ItemStack(Items.COBBLESTONE, 5));
            return null;
        }
        BlockState cobble = Blocks.COBBLESTONE.defaultBlockState();
        level.setBlock(at, cobble, 3);
        level.setBlock(at.above(), cobble, 3);
        level.setBlock(at.relative(Direction.EAST), cobble, 3);
        level.setBlock(at.relative(Direction.WEST), Blocks.COBBLESTONE_WALL.defaultBlockState(), 3);
        level.setBlock(at.relative(Direction.NORTH), Blocks.COBBLESTONE_SLAB.defaultBlockState(), 3);
        BlockPos post = at.above(2);
        int rot = RotationSegment.convertToSegment(Armies.yawTo(-g.px(), -g.pz()) + 180.0F);
        level.setBlock(post, Blocks.OAK_SIGN.defaultBlockState().setValue(StandingSignBlock.ROTATION, rot), 3);
        if (level.getBlockEntity(post) instanceof SignBlockEntity s) {
            SignText text = new SignText();
            for (int i = 0; i < Math.min(4, lines.length); i++) text = text.setMessage(i, Component.literal(lines[i]));
            s.setText(text, true);
            s.setText(text, false);
            s.setWaxed(true);
        }
        return post;
    }
}
