package com.jrpetty.mcassistant.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * The chests linked to a specialist: every container inside the box its
 * checklist accepts one in. This is the bot's stores — what it checks before
 * asking the player for anything, and what it helps itself from.
 *
 * <p>Found through each chunk's block-entity map rather than by asking the world
 * "is there a container here?" for every position in the box. The box a
 * checklist uses is 25x11x25 — 6,875 positions — and a single requirement scan
 * used to walk it up to seven times, which is roughly 48,000 block-entity
 * lookups every three seconds, per specialist. The same box spans at most nine
 * chunks, and a chunk already keeps a map of exactly which block entities it
 * holds, normally a handful. Same containers, same order, about four orders of
 * magnitude less work.
 */
public final class ZoneChests {

    private ZoneChests() {}

    /**
     * The name a container carries when it belongs to a village. Folk put it on
     * every chest and furnace they place, and — the point — use ONLY containers
     * that carry it. A village founded beside somebody's base used to read every
     * unmarked chest within fifty blocks as its own stores: it drew its building
     * timber out of the player's chests, fed itself from them, and stocked its
     * smelter from them. Anything a player wants the village to use, they can
     * name in an anvil.
     */
    public static final String MARK = "Village Store";

    /**
     * True while a village folk is thinking. Set for the whole of each folk's
     * tick, so every chest question it asks — a dozen call sites, most of them
     * many layers down — gets the village's answer without each one having to
     * carry the fact with it. Never set for a hired assistant, whose owner's
     * chests are exactly what it should use.
     */
    private static final ThreadLocal<Boolean> ASKING = ThreadLocal.withInitial(() -> Boolean.FALSE);

    /** Is the thread asking a chest question doing so as a village? A thread
     *  local, because in single player the client and the server tick the same
     *  entity classes at the same time on different threads. */
    public static boolean settlerAsking() { return ASKING.get(); }

    /** Set who is asking; returns who was asking before, to restore. */
    public static boolean askAs(boolean settler) {
        boolean before = ASKING.get();
        ASKING.set(settler);
        return before;
    }

    /** Does this container belong to a village? */
    public static boolean isVillageStore(BlockEntity be) {
        if (!(be instanceof net.minecraft.world.Nameable named)) return false;
        net.minecraft.network.chat.Component name = named.getCustomName();
        return name != null && name.getString().toLowerCase().startsWith("village store");
    }

    /** Put the village's name on the container at this spot, if it has one to
     *  put it on. Done straight after placing it, before anything goes in. */
    public static void mark(Level level, BlockPos pos) {
        BlockEntity be = level.getBlockEntity(pos);
        if (be == null) return;
        be.applyComponents(
            net.minecraft.core.component.DataComponentMap.builder()
                .set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,
                    net.minecraft.network.chat.Component.literal(MARK))
                .build(),
            net.minecraft.core.component.DataComponentPatch.EMPTY);
        be.setChanged();
    }

    /** A container and where it is. Holds the block entity so a stale entry
     *  (its chest broken since the scan) can be recognised and skipped. */
    public record Found(BlockPos pos, BlockEntity blockEntity) {

        public Container container() {
            return (Container) blockEntity;
        }

        public boolean stillThere() {
            return !blockEntity.isRemoved();
        }

        /** Does this chest hold anything matching? */
        public boolean holds(Predicate<ItemStack> what) {
            if (!stillThere()) return false;
            Container c = container();
            for (int i = 0; i < c.getContainerSize(); i++) {
                ItemStack s = c.getItem(i);
                if (!s.isEmpty() && what.test(s)) return true;
            }
            return false;
        }
    }

    /**
     * Every container in the box centred on {@code origin}, ordered by packed
     * position so two calls with the same world always answer in the same order
     * — a chunk's block-entity map has no order of its own, and callers pick a
     * "nearest" chest out of this.
     */
    public static List<Found> around(Level level, BlockPos origin, int radius, int height) {
        BlockPos min = origin.offset(-radius, -height, -radius);
        BlockPos max = origin.offset(radius, height, radius);
        List<Found> out = new ArrayList<>(4);

        for (int cx = min.getX() >> 4; cx <= (max.getX() >> 4); cx++) {
            for (int cz = min.getZ() >> 4; cz <= (max.getZ() >> 4); cz++) {
                if (!level.hasChunk(cx, cz)) {
                    // Never happens around a ticking entity, but probing the
                    // slice keeps the answer identical if it ever does.
                    probe(level, min, max, cx, cz, out);
                    continue;
                }
                LevelChunk chunk = level.getChunk(cx, cz);
                // Copy out of the live map: a consumer may empty a chest while
                // walking this list, and we will not hold the chunk's iterator.
                for (var entry : chunk.getBlockEntities().entrySet()) {
                    BlockPos pos = entry.getKey();
                    BlockEntity be = entry.getValue();
                    if (be instanceof Container && inBox(pos, min, max)
                        && (!settlerAsking() || isVillageStore(be))
                        && !isPrivate(level, pos)) {
                        out.add(new Found(pos.immutable(), be));
                    }
                }
            }
        }
        out.sort((a, b) -> Long.compare(a.pos().asLong(), b.pos().asLong()));
        return out;
    }

    private static void probe(Level level, BlockPos min, BlockPos max,
                              int cx, int cz, List<Found> out) {
        int x0 = Math.max(min.getX(), cx << 4), x1 = Math.min(max.getX(), (cx << 4) + 15);
        int z0 = Math.max(min.getZ(), cz << 4), z1 = Math.min(max.getZ(), (cz << 4) + 15);
        for (BlockPos pos : BlockPos.betweenClosed(
                new BlockPos(x0, min.getY(), z0), new BlockPos(x1, max.getY(), z1))) {
            BlockEntity be = level.getBlockEntity(pos);
            if (be instanceof Container && (!settlerAsking() || isVillageStore(be))
                && !isPrivate(level, pos)) {
                out.add(new Found(pos.immutable(), be));
            }
        }
    }

    /**
     * Yours alone: a container with a SIGN on it — any of its four sides or
     * sitting on its lid — is invisible to every assistant. Nothing is taken
     * from it, nothing is stashed into it, it never counts toward a
     * checklist, and no hauler routes to it.
     *
     * <p>This is the one chokepoint every chest question in the mod passes
     * through, so hanging one blank sign is the whole feature: no typing, no
     * menu, no per-bot setting. The sign does not have to say anything —
     * a sign is the mark.
     */
    public static boolean isPrivate(Level level, BlockPos chest) {
        for (net.minecraft.core.Direction d : net.minecraft.core.Direction.values()) {
            if (d == net.minecraft.core.Direction.DOWN) continue;   // under a chest: not a mark
            if (level.getBlockState(chest.relative(d)).getBlock()
                    instanceof net.minecraft.world.level.block.SignBlock) {
                return true;
            }
        }
        return false;
    }

    private static boolean inBox(BlockPos p, BlockPos min, BlockPos max) {
        return p.getX() >= min.getX() && p.getX() <= max.getX()
            && p.getY() >= min.getY() && p.getY() <= max.getY()
            && p.getZ() >= min.getZ() && p.getZ() <= max.getZ();
    }

    /** Is any of these chests holding this? */
    public static boolean anyHolds(List<Found> chests, Predicate<ItemStack> what) {
        for (Found f : chests) {
            if (f.holds(what)) return true;
        }
        return false;
    }

    /** How many of this do these chests hold between them? */
    public static int countIn(List<Found> chests, Predicate<ItemStack> what) {
        int n = 0;
        for (Found f : chests) {
            if (!f.stillThere()) continue;
            Container c = f.container();
            for (int i = 0; i < c.getContainerSize(); i++) {
                ItemStack s = c.getItem(i);
                if (!s.isEmpty() && what.test(s)) n += s.getCount();
            }
        }
        return n;
    }

    /**
     * Is this container somewhere a LOAD may be put? A furnace, hopper,
     * dropper, dispenser and brewing stand are all Containers, and stashing
     * into one jams the machine and swallows the goods — a smelter once
     * posted its finished ingots straight back into the furnace they came
     * out of. Every chest picker asks this, so none of them can forget.
     */
    public static boolean isStashable(Found found) {
        BlockEntity be = found.blockEntity();
        return !(be instanceof net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity)
            && !(be instanceof net.minecraft.world.level.block.entity.HopperBlockEntity)
            && !(be instanceof net.minecraft.world.level.block.entity.DispenserBlockEntity)
            && !(be instanceof net.minecraft.world.level.block.entity.BrewingStandBlockEntity);
    }
}
