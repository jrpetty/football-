package com.jrpetty.mcassistant.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [econ32] Iron picks for the diamonds.
 *
 * <p>A diamond comes only to an iron pick, and the works send only a miner with one to the deep level (MineDemand.mayWork).
 * The rack counted its spares by the pick, whatever its metal: a rack full of stone picks was full, and the smith made no
 * iron one; a miner changed its pick only when it was two thirds worn; and while the town put iron by for its age (the
 * Diamond Age asks twice the Iron Age's iron) a pick was made of iron only past three dozen bars in the stores. So a town
 * came into the Diamond Age with every miner on a stone pick, and nobody was ever sent down for the diamonds the age asked
 * for. Now, while the age asks for diamonds:
 * <ul>
 * <li>The smith keeps an iron pick on the rack for every third miner (at least two), out of the stores' iron past the bars
 *     it keeps (Crafts.IRON_KEPT), before the age's iron: three bars in a pick are the diamonds' way in.</li>
 * <li>A miner setting out with no iron pick takes one off the rack (its stone one handed in), worn or not.</li>
 * </ul>
 */
public final class DeepPicks {

    private DeepPicks() {}

    private static final Map<UUID, long[]> WANTED = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> SMITH = new ConcurrentHashMap<>();

    public static void resetForTests() {
        WANTED.clear();
        SMITH.clear();
    }

    /** Is the town's age asking for diamonds now (looked at once a minute)? */
    public static boolean wanted(ServerLevel level, @Nullable UUID village) {
        if (village == null || Villages.get(village) == null) return false;
        long now = level.getGameTime();
        long[] k = WANTED.get(village);
        if (k != null && now >= k[0] && now - k[0] < 1200) return k[1] != 0;
        boolean on = Fuel.ageWants(level, village, Villages.Task.DIAMOND);
        WANTED.put(village, new long[]{ now, on ? 1 : 0 });
        return on;
    }

    /** Iron (or better) picks on the rack, half their wear left or more. */
    static int ironOnRack(ServerLevel level, UUID village) {
        int n = 0;
        for (BlockPos p : Villages.storeChests(level, village)) {
            if (!(level.getBlockEntity(p) instanceof Container c)) continue;
            for (int i = 0; i < c.getContainerSize(); i++) {
                ItemStack s = c.getItem(i);
                if (Toolrack.spare(Toolrack.Tool.PICKAXE, s) && Toolrack.metal(s) >= 3) n += s.getCount();
            }
        }
        return n;
    }

    /** The iron picks the rack keeps while the age asks for diamonds: one for every third miner, at least two. */
    static int keep(UUID village) {
        return Math.max(2, (Toolrack.users(village, Toolrack.Tool.PICKAXE) + 2) / 3);
    }

    /** The smith's iron pick for the diamonds, once in ten seconds a town. Returns what it made, or null. */
    @Nullable
    public static String smith(VillageFolkEntity f, ServerLevel level) {
        if (f.stationTask() != AssistantEntity.StationTask.SMITH || f.isBaby() || f.isSleeping()) return null;
        UUID id = f.ownerId();
        Villages.Village v = id == null ? null : Villages.get(id);
        if (v == null) return null;
        long now = level.getGameTime();
        Long last = SMITH.get(id);
        if (last != null && now - last < Toolrack.EVERY && now >= last) return null;
        SMITH.put(id, now);
        if (!wanted(level, id) || ironOnRack(level, id) >= keep(id)) return null;
        if (Market.stock(level, id, Toolrack.IRON) < Toolrack.Tool.PICKAXE.head + Crafts.IRON_KEPT) return null;
        String made = Toolrack.make(level, v, f, Toolrack.Tool.PICKAXE, Items.IRON_PICKAXE);
        if (made != null) f.brain("made " + made + " for the diamonds");
        return made;
    }

    /** A miner setting out with no iron pick while the age asks for diamonds: one off the rack, its own handed in. */
    public static boolean beforeShift(ServerLevel level, VillageFolkEntity f) {
        UUID id = f.ownerId();
        Villages.Village v = id == null ? null : Villages.get(id);
        if (v == null || f.pickTierCarried() >= 3 || !wanted(level, id) || ironOnRack(level, id) <= 0) return false;
        ItemStack got = Toolrack.issue(level, v, f, Toolrack.Tool.PICKAXE);
        if (got.isEmpty()) return false;
        if (Toolrack.metal(got) < 3) {                      // the best on the rack was not iron after all: back it goes
            f.removeMatching(s -> s.getItem() == got.getItem() && Toolrack.metal(s) < 3, 1);
            Crafts.store(level, v, got);
            return false;
        }
        // Its stone pick handed in for the rack.
        ItemStack held = f.getMainHandItem();
        if (Toolrack.is(Toolrack.Tool.PICKAXE, held) && Toolrack.metal(held) < 3) {
            ItemStack old = held.copy();
            f.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            Crafts.store(level, v, old);
        } else {
            ItemStack stone = ItemStack.EMPTY;
            for (ItemStack s : f.getInventoryItems()) {
                if (Toolrack.is(Toolrack.Tool.PICKAXE, s) && Toolrack.metal(s) < 3) { stone = s.copy(); break; }
            }
            if (!stone.isEmpty() && f.removeMatching(s -> Toolrack.is(Toolrack.Tool.PICKAXE, s) && Toolrack.metal(s) < 3, 1) > 0) {
                Crafts.store(level, v, stone);                // handed in from the pack: the rack has it back as a spare
            }
        }
        f.equipBestTool(net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
        f.brain("took an iron pick off the rack for the diamonds");
        return true;
    }
}
