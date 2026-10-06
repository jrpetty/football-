package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.village.Chronicle;
import com.jrpetty.mcassistant.village.Ledger;
import com.jrpetty.mcassistant.village.TownPlan;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.StandingSignBlock;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.RotationSegment;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The welcome sign at the edge of town. [townlife]
 *
 * <p>Where the main road leaves the town's ground — the avenue the most roads go out along (to its
 * colonies and its mother town, else toward the nearest other town, else away from its fields) — a
 * post stands beside the road with a sign on it, its face to whoever is coming in: "Welcome to", the
 * town's name, its population and its age; on the back, for whoever is leaving, when it was founded
 * and how long ago. It is written up again every day, so the population on it is today's.
 *
 * <p>The post and the sign come out of the stores: a length of fence and a sign put by, or two planks
 * for each. A hand on the town's works puts it up (TownJobs, "signs"). As the town grows and its edge
 * moves out, the sign is taken down — the post and the sign back into the stores — and put up again at
 * the new edge.
 */
public final class WelcomeSign {

    private WelcomeSign() {}

    /** Across the avenue from the roads' signposts (Roads, on the right going out): this one on the left. */
    static final int ACROSS = TownPlan.AVENUE + 1;
    private static final String NOTE = "welcome.sign";

    /** The day each town's sign was last written up. */
    private static final Map<UUID, Long> UPDATED = new ConcurrentHashMap<>();

    public static void resetForTests() {
        UPDATED.clear();
    }

    /** The town's look at its sign (TownLife.tick, every ten seconds): once a day, by day. */
    static void tick(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        long dt = level.getDayTime(), day = Math.floorDiv(dt, 24000L), t = Math.floorMod(dt, 24000L);
        if (t >= 12500L || Villages.headcount(id) < TownJobs.SETTLED) return;   // a young town has no hand to spare for it yet
        Long done = UPDATED.get(id);
        if (done != null && done == day) return;
        if (put(level, v) != null) UPDATED.put(id, day);
    }

    // ------------------------------------------------------------------ where it stands

    /** The way out of town the most roads take: to its colonies and its mother town, else toward its nearest neighbour, else away from its fields. */
    static Direction way(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        BlockPos heart = v.centre();
        int[] votes = new int[4];
        boolean any = false;
        for (Map.Entry<UUID, UUID> link : Ledger.links().entrySet()) {
            UUID other = id.equals(link.getKey()) ? link.getValue() : id.equals(link.getValue()) ? link.getKey() : null;
            Villages.Village o = other == null ? null : Villages.get(other);
            if (o == null || !o.dim().equals(v.dim())) continue;
            votes[toward(heart, o.centre()).get2DDataValue()] += 2;
            any = true;
        }
        if (!any) {
            Villages.Village nearest = null;
            double best = 1024.0 * 1024.0;
            for (Villages.Village o : Villages.every()) {
                if (o.id().equals(id) || !o.dim().equals(v.dim())) continue;
                double d = o.centre().distSqr(heart);
                if (d < best) { best = d; nearest = o; }
            }
            if (nearest != null) {
                votes[toward(heart, nearest.centre()).get2DDataValue()]++;
                any = true;
            }
        }
        if (!any) {
            int fields = Villages.fieldsSide(id);
            return fields < 0 ? Direction.SOUTH : side(fields).getOpposite();
        }
        Direction best = Direction.SOUTH;
        int most = -1;
        for (Direction d : Direction.Plane.HORIZONTAL) {
            if (votes[d.get2DDataValue()] > most) { most = votes[d.get2DDataValue()]; best = d; }
        }
        return best;
    }

    /** The avenue that points that way (as Roads.exit picks it). */
    private static Direction toward(BlockPos heart, BlockPos there) {
        int dx = there.getX() - heart.getX(), dz = there.getZ() - heart.getZ();
        if (Math.abs(dx) >= Math.abs(dz)) return dx >= 0 ? Direction.EAST : Direction.WEST;
        return dz >= 0 ? Direction.SOUTH : Direction.NORTH;
    }

    private static Direction side(int townPlanSide) {
        return switch (townPlanSide) {
            case TownPlan.EAST -> Direction.EAST;
            case TownPlan.SOUTH -> Direction.SOUTH;
            case TownPlan.WEST -> Direction.WEST;
            default -> Direction.NORTH;
        };
    }

    /**
     * The post's spot: at the town's edge along the avenue going out, on its left (the roads' signposts
     * take the right); a step or two in if the edge itself is water or no ground at all. Null if none will do.
     */
    @Nullable
    static BlockPos spot(ServerLevel level, Villages.Village v) {
        Direction out = way(level, v);
        Direction left = out.getCounterClockWise();
        BlockPos heart = v.centre();
        int reach = Villages.townReach(v.id());
        for (int in = 0; in <= 6; in++) {
            BlockPos col = heart.relative(out, reach - in).relative(left, ACROSS);
            if (!level.isLoaded(col)) return null;
            Roads.Ground g = Roads.ground(level, col.getX(), col.getZ());
            if (g == null || g.water() || Math.abs(g.y() - heart.getY()) > 12) continue;
            BlockPos post = new BlockPos(col.getX(), g.y() + 1, col.getZ());
            BlockState p = level.getBlockState(post), s = level.getBlockState(post.above());
            boolean ours = p.getBlock() instanceof FenceBlock && (s.getBlock() instanceof StandingSignBlock || s.canBeReplaced());
            if (ours || p.canBeReplaced() && p.getFluidState().isEmpty() && s.canBeReplaced() && s.getFluidState().isEmpty()) return post;
        }
        return null;
    }

    // ------------------------------------------------------------------ putting it up

    /** Up (out of the stores, by a hand on the town's works) and written up for today. What was done, or null if it waits. */
    @Nullable
    static String put(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        BlockPos post = spot(level, v);
        if (post == null) return null;
        Direction out = way(level, v);
        BlockPos sign = post.above();
        BlockState ps = level.getBlockState(post), ss = level.getBlockState(sign);
        boolean posted = ps.getBlock() instanceof FenceBlock;
        boolean up = posted && ss.getBlock() instanceof StandingSignBlock;
        String did = "written up";
        if (!up) {
            if (!toHand(level, v, posted)) return null;               // nobody is sent till the stores can pay for it
            if (!TownJobs.atWork(level, v, "signs", post, "putting up the welcome sign")) return null;
            if (!posted && !Crafts.fence(level, v)) return null;
            if (!Crafts.sign(level, v)) {
                if (!posted) Crafts.store(level, v, new ItemStack(Items.SPRUCE_FENCE));
                return null;
            }
            BlockPos old = remembered(id);
            if (old != null && !old.equals(post)) takeDown(level, v, old);
            if (!posted) level.setBlock(post, Blocks.SPRUCE_FENCE.defaultBlockState(), 3);
            level.setBlock(sign, Blocks.SPRUCE_SIGN.defaultBlockState()
                .setValue(StandingSignBlock.ROTATION, RotationSegment.convertToSegment(out)), 3);
            Ledger.note(id, NOTE, post.getX() + "," + post.getY() + "," + post.getZ());
            did = "put up at " + post.toShortString();
            Villages.tell(id, Math.floorDiv(level.getDayTime(), 24000L), "a sign welcoming travellers went up at the edge of town");
        }
        if (level.getBlockEntity(sign) instanceof SignBlockEntity s) write(level, v, s);
        return did;
    }

    /** Can the stores run to it: a fence put by (or two planks) unless the post stands, and a sign (or two planks)? */
    private static boolean toHand(ServerLevel level, Villages.Village v, boolean posted) {
        int planks = 0;
        if (!posted && Crafts.stock(level, v, s -> s.is(net.minecraft.tags.ItemTags.WOODEN_FENCES)) == 0) planks += 2;
        if (Crafts.stock(level, v, s -> s.is(net.minecraft.tags.ItemTags.SIGNS)) == 0) planks += 2;
        if (planks == 0) return true;
        int have = Crafts.stock(level, v, s -> s.is(net.minecraft.tags.ItemTags.PLANKS));
        return have >= planks || have + 4 * Crafts.stock(level, v, s -> s.is(net.minecraft.tags.ItemTags.LOGS)) >= planks;
    }

    /** Today's words: on its face the welcome, the name, the population and the age; on its back, its years. */
    static void write(ServerLevel level, Villages.Village v, SignBlockEntity s) {
        UUID id = v.id();
        long day = Math.floorDiv(level.getDayTime(), 24000L);
        long founded = Chronicle.foundedOn(id);
        String age = Villages.ageOf(id).label.replaceFirst("^the ", "");
        String[] front = { "Welcome to", Villages.name(id), "Population " + Villages.headcount(id), Character.toUpperCase(age.charAt(0)) + age.substring(1) };
        long old = founded < 0 ? -1 : day - founded;
        String[] back = { Villages.name(id), founded < 0 ? "" : "Founded day " + founded,
            old < 0 ? "" : old == 0 ? "founded today" : old == 1 ? "a day old" : old + " days old", "Safe travels!" };
        boolean same = true;
        for (int i = 0; i < 4; i++) {
            if (!s.getFrontText().getMessage(i, false).getString().equals(front[i])
                    || !s.getBackText().getMessage(i, false).getString().equals(back[i])) { same = false; break; }
        }
        if (same) return;
        SignText f = new SignText(), b = new SignText();
        for (int i = 0; i < 4; i++) {
            f = f.setMessage(i, Component.literal(front[i]));
            b = b.setMessage(i, Component.literal(back[i]));
        }
        s.setText(f, true);
        s.setText(b, false);
        s.setWaxed(true);
    }

    /** Where the town's sign was put up last, or null. */
    @Nullable
    private static BlockPos remembered(UUID village) {
        String n = Ledger.note(village, NOTE);
        if (n == null || n.isEmpty()) return null;
        String[] p = n.split(",");
        try {
            return new BlockPos(Integer.parseInt(p[0].trim()), Integer.parseInt(p[1].trim()), Integer.parseInt(p[2].trim()));
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** The old sign taken down as the town outgrew it: the post and the sign back into the stores. */
    private static void takeDown(ServerLevel level, Villages.Village v, BlockPos post) {
        if (!level.isLoaded(post)) return;
        BlockState ps = level.getBlockState(post), ss = level.getBlockState(post.above());
        if (!(ps.getBlock() instanceof FenceBlock) || !(ss.getBlock() instanceof StandingSignBlock)) return;
        level.setBlock(post.above(), Blocks.AIR.defaultBlockState(), 3);
        level.setBlock(post, Blocks.AIR.defaultBlockState(), 3);
        Crafts.store(level, v, new ItemStack(ss.getBlock().asItem()));
        Crafts.store(level, v, new ItemStack(ps.getBlock().asItem()));
    }

    // ------------------------------------------------------------------ the tests

    /** For the tests: the sign put up (or written up) now, whatever the day. What was done, or null. */
    @Nullable
    public static String putForTests(ServerLevel level, Villages.Village v) {
        UPDATED.remove(v.id());
        String did = put(level, v);
        if (did != null) UPDATED.put(v.id(), Math.floorDiv(level.getDayTime(), 24000L));
        return did;
    }

    /** For the tests: the column the post goes in (the edge of town, by the main road), ground or none. */
    public static BlockPos columnForTests(ServerLevel level, Villages.Village v) {
        Direction out = way(level, v);
        return v.centre().relative(out, Villages.townReach(v.id())).relative(out.getCounterClockWise(), ACROSS);
    }

    /** For the tests: the way out the sign is put up on. */
    public static Direction wayForTests(ServerLevel level, Villages.Village v) {
        return way(level, v);
    }
}
