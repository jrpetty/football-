package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.block.VillageBoardBlock;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.AbstractBannerBlock;
import net.minecraft.world.level.block.BannerBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.WallBannerBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The war in the town's own blocks: the war banner hung over its gate (or on the front of its hall, or
 * by its board) the day war is declared, and taken down the day peace is made.
 *
 * <p>Nothing from nothing. The banner is one of the stores' own (red if there is one, else whatever
 * colour there is), or made of six of the stores' wool and a stick (or a plank for the stick); with
 * neither there is no banner, and the town says so. Taken down, it goes back into the stores. Where it
 * hangs is written down, so a restart does not leave a banner up after the peace.
 */
final class WarBanner {

    private WarBanner() {}

    /** The colours a war banner is looked for in, the fiercest first. */
    private static final DyeColor[] COLOURS = { DyeColor.RED, DyeColor.BLACK, DyeColor.ORANGE, DyeColor.YELLOW, DyeColor.PURPLE,
        DyeColor.BROWN, DyeColor.GRAY, DyeColor.BLUE, DyeColor.GREEN, DyeColor.CYAN, DyeColor.LIGHT_BLUE, DyeColor.LIME,
        DyeColor.MAGENTA, DyeColor.PINK, DyeColor.LIGHT_GRAY, DyeColor.WHITE };

    private static Block wallBanner(DyeColor c) {
        return BuiltInRegistries.BLOCK.get(ResourceLocation.withDefaultNamespace(c.getName() + "_wall_banner"));
    }

    private static net.minecraft.world.item.Item wool(DyeColor c) {
        return BuiltInRegistries.ITEM.get(ResourceLocation.withDefaultNamespace(c.getName() + "_wool"));
    }

    /**
     * Cloth for a banner, out of the stores: a banner as it is, else six wool of one colour and a stick
     * (or a plank, for the stick). The colour, or null if the stores have neither.
     */
    @Nullable
    static DyeColor cloth(ServerLevel level, Villages.Village v) {
        for (DyeColor c : COLOURS) {
            net.minecraft.world.item.Item banner = BannerBlock.byColor(c).asItem();
            if (TownWork.take(level, v, s -> s.is(banner), 1)) return c;
        }
        for (DyeColor c : COLOURS) {
            net.minecraft.world.item.Item w = wool(c);
            if (!TownWork.take(level, v, s -> s.is(w), 6)) continue;
            if (TownWork.take(level, v, s -> s.is(Items.STICK), 1) || TownWork.take(level, v, s -> s.is(ItemTags.PLANKS), 1)) return c;
            TownWork.give(level, v, new ItemStack(w, 6));               // no stick: the wool back where it came from
            return null;
        }
        return null;
    }

    /**
     * The war banner hung for the war with {@code enemy}: over the gate that faces the enemy, else on the
     * front of the leader's hall or the meeting hall, else on a pole by the board. Where it went, or null
     * (no cloth, or nowhere to hang it — the cloth goes back into the stores).
     */
    @Nullable
    static BlockPos hang(ServerLevel level, Villages.Village v, UUID enemy) {
        if (!level.isLoaded(v.centre())) return null;
        String was = Ledger.note(v.id(), "wp.banner/" + enemy);
        if (was != null && !was.isEmpty()) return BlockPos.of(WarBooks.num(was.split(",")[0], 0));
        DyeColor c = cloth(level, v);
        if (c == null) {
            Ledger.note(v.id(), "wp.nocloth", "1");
            return null;
        }
        Ledger.forget(v.id(), "wp.nocloth");
        Villages.Village foe = Villages.get(enemy);
        BlockPos at = overGate(level, v, foe, c);
        if (at == null) at = onHall(level, v, c);
        if (at == null) at = byBoard(level, v, c);
        if (at == null) {
            TownWork.give(level, v, new ItemStack(BannerBlock.byColor(c).asItem()));
            return null;
        }
        Ledger.note(v.id(), "wp.banner/" + enemy, Long.toString(at.asLong()));
        Arms.warArms(level, v, at);                                  // [arms] the town's charges woven on its cloth
        return at;
    }

    /** Where the war banner hangs for this war, or null. */
    @Nullable
    static BlockPos where(UUID village, UUID enemy) {
        String was = Ledger.note(village, "wp.banner/" + enemy);
        return was == null || was.isEmpty() ? null : BlockPos.of(WarBooks.num(was.split(",")[0], 0));
    }

    /** Peace: the banner taken down and put back in the stores. Whether one came down. */
    static boolean takeDown(ServerLevel level, Villages.Village v, UUID enemy) {
        BlockPos at = where(v.id(), enemy);
        Ledger.forget(v.id(), "wp.banner/" + enemy);
        if (at == null || !level.isLoaded(at)) return false;
        BlockState st = level.getBlockState(at);
        if (!(st.getBlock() instanceof AbstractBannerBlock banner)) return false;
        DyeColor c = banner.getColor();
        // [arms] Woven with the town's arms, it comes down as it is, for the next war.
        ItemStack back = level.getBlockEntity(at) instanceof net.minecraft.world.level.block.entity.BannerBlockEntity be ? be.getItem()
            : new ItemStack(BannerBlock.byColor(c).asItem());
        level.setBlock(at, Blocks.AIR.defaultBlockState(), 3);
        TownWork.give(level, v, back);
        return true;
    }

    /** Over a gate of the wall: the one that faces the enemy, on the outside of the wall above its doors. */
    @Nullable
    private static BlockPos overGate(ServerLevel level, Villages.Village v, @Nullable Villages.Village foe, DyeColor c) {
        List<Watch.Gate> gates = new ArrayList<>(Watch.gates(level, v.id()));
        if (gates.isEmpty()) return null;
        if (foe != null) {
            double dx = foe.centre().getX() - v.centre().getX(), dz = foe.centre().getZ() - v.centre().getZ();
            gates.sort((a, b) -> Double.compare(-(a.out().getStepX() * dx + a.out().getStepZ() * dz),
                -(b.out().getStepX() * dx + b.out().getStepZ() * dz)));
        }
        for (Watch.Gate g : gates) {
            for (BlockPos door : g.doors()) {
                for (int up = 2; up <= 3; up++) {
                    BlockPos wall = door.above(up), spot = wall.relative(g.out());
                    if (hangOn(level, wall, spot, g.out(), c)) return spot;
                }
            }
        }
        return null;
    }

    /** On the front of the leader's hall, else the meeting hall: high on the wall over its door. */
    @Nullable
    private static BlockPos onHall(ServerLevel level, Villages.Village v, DyeColor c) {
        for (String s : new String[]{ "townhall", "hall", "court" }) {
            for (Ledger.Building b : Ledger.buildings(v.id())) {
                if (!b.structure().equals(s) || !level.isLoaded(b.anchor())) continue;
                Direction f = b.facing();
                for (int side : new int[]{ 0, 1, -1, 2, -2 }) {
                    for (int up = 3; up <= 5; up++) {
                        BlockPos from = b.anchor().relative(f.getClockWise(), side).above(up);
                        // Out from the middle of the building to the first wall with open air before it.
                        for (int k = 1; k <= 16; k++) {
                            BlockPos wall = from.relative(f, k), spot = wall.relative(f);
                            if (!level.getBlockState(wall).isAir() && level.getBlockState(spot).isAir()) {
                                if (hangOn(level, wall, spot, f, c)) return spot;
                                break;
                            }
                        }
                    }
                }
            }
        }
        return null;
    }

    /**
     * On its own pole by the board (a standing banner), when the town has no wall and no hall: out on the
     * square before the board's face, just past one end of the board (else a little further along, else in
     * front of the board near an end), its cloth turned the way the board faces. Whoever looks at the board
     * from the square sees the banner beside it, never a pole behind it (it once went anywhere round the
     * board's foot, and as often as not stood behind the board, out of sight). With no board, somewhere
     * round the middle of the town.
     */
    @Nullable
    private static BlockPos byBoard(ServerLevel level, Villages.Village v, DyeColor c) {
        BlockPos foot = VillageBoards.lectern(v.id());
        Direction f = VillageBoards.facingOf(v.id());
        if (foot != null && f != null && f.getAxis().isHorizontal()) {
            Direction right = VillageBoardBlock.right(f);
            int first = -VillageBoardBlock.WIDE / 2, last = first + VillageBoardBlock.WIDE - 1;   // the board's ends, along from its foot
            int[] along = { first - 1, last + 1, first - 2, last + 2, first + 1, last - 1, first - 3, last + 3 };
            // The foot is two out from the board's face: two out first, then three, one, four, five.
            for (boolean road : new boolean[]{ false, true }) {
                for (int out : new int[]{ 0, 1, -1, 2, 3 }) {
                    for (int a : along) {
                        BlockPos spot = poleSpot(level, foot.relative(right, a).relative(f, out), foot.getY(), road);
                        if (spot == null) continue;
                        plant(level, spot, c, f.get2DDataValue() * 4);
                        return spot;
                    }
                }
            }
            return null;
        }
        BlockPos near = v.centre();
        for (int r = 2; r <= 5; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                    BlockPos spot = poleSpot(level, near.offset(dx, 0, dz), near.getY() + 1, false);
                    if (spot == null) continue;
                    plant(level, spot, c, Math.floorMod(Math.round((float) (Math.toDegrees(Math.atan2(-dx, dz)) / 22.5)), 16));
                    return spot;
                }
            }
        }
        return null;
    }

    /**
     * Ground for a pole on this column, near the board's foot in height ({@code y}): open (or only grass
     * and flowers, which are cleared), firm underfoot, and not in the road unless {@code road}. Null if not.
     */
    @Nullable
    private static BlockPos poleSpot(ServerLevel level, BlockPos col, int y, boolean road) {
        if (!level.isLoaded(col)) return null;
        BlockPos spot = new BlockPos(col.getX(), level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, col.getX(), col.getZ()), col.getZ());
        if (spot.getY() < y - 4 || spot.getY() > y + 1) return null;                 // not on a roof, not down a bank
        for (BlockPos p : new BlockPos[]{ spot, spot.above() }) {
            BlockState st = level.getBlockState(p);
            if (!st.isAir() && (!st.canBeReplaced() || !level.getFluidState(p).isEmpty())) return null;
        }
        BlockPos below = spot.below();
        if (!level.getBlockState(below).isFaceSturdy(level, below, Direction.UP)) return null;
        if (!road && level.getBlockState(below).is(Blocks.DIRT_PATH)) return null;    // not in the road
        return spot;
    }

    /** The banner set up on its pole, turned to {@code rotation}; the grass where it stands cleared first. */
    private static void plant(ServerLevel level, BlockPos spot, DyeColor c, int rotation) {
        if (!level.getBlockState(spot.above()).isAir()) level.setBlock(spot.above(), Blocks.AIR.defaultBlockState(), 3);
        level.setBlock(spot, BannerBlock.byColor(c).defaultBlockState().setValue(BannerBlock.ROTATION, rotation), 3);
    }

    private static boolean hangOn(ServerLevel level, BlockPos wall, BlockPos spot, Direction out, DyeColor c) {
        if (!level.isLoaded(spot) || !level.getBlockState(spot).isAir()) return false;
        if (!level.getBlockState(wall).isFaceSturdy(level, wall, out)) return false;
        BlockState banner = wallBanner(c).defaultBlockState();
        if (!banner.hasProperty(WallBannerBlock.FACING)) return false;
        level.setBlock(spot, banner.setValue(WallBannerBlock.FACING, out), 3);
        return true;
    }
}
