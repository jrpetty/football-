package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.entity.Shifts.Shift;
import com.jrpetty.mcassistant.entity.Shifts.Task;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.animal.Sheep;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockDropsEvent;
import net.neoforged.neoforge.event.level.BlockEvent;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * [roles-d] The outdoor trades' paid shifts (ShiftRole.WOOD and SHEAR), done with the town's own mechanics as the farm
 * hand's are (ShiftTasks):
 * <ul>
 * <li><b>Woodcutter's hand</b>: a grown tree in a woodcutter's wood (a trunk on soil, leaves on it, not a building's
 *     post) is marked. Every log of it the player fells goes into the stores, nothing to the ground; with the trunk down
 *     the player is handed a sapling from the stores (if they have one) and plants a sapling where it stood: the task
 *     done. Left unplanted, it costs stars; a tree felled meanwhile by the woodcutter is no fault.</li>
 * <li><b>Shearer</b>: a sheep of the town's flock (Drover's herd) in wool is marked; shorn with the player's own shears
 *     (they wear as they do), its wool goes into the stores. Shearing one of the flock not marked is a mistake, and the
 *     town's sheep keep their wool.</li>
 * </ul>
 * Cost: nothing without a shift on; a task's tree is looked for in the woods' few hundred columns once, when one is
 * wanted.
 */
@EventBusSubscriber(modid = McAssistantMod.MODID)
public final class OutdoorShifts {

    private OutdoorShifts() {}

    /** How far round a woodcutter's ground a tree is looked for, and round the pen a sheep. */
    static final int WOODS_REACH = 12, PEN_REACH = 10;
    /** A tree's trunk: from three logs to this many. */
    static final int TRUNK_MOST = 14;
    /** How long after the trunk is down the sapling must be in (ticks). */
    static final long REPLANT = 600L;

    /** The sapling that grows each log's tree. */
    static final Map<Item, Item> SAPLINGS = Map.of(Items.OAK_LOG, Items.OAK_SAPLING, Items.SPRUCE_LOG, Items.SPRUCE_SAPLING,
        Items.BIRCH_LOG, Items.BIRCH_SAPLING, Items.JUNGLE_LOG, Items.JUNGLE_SAPLING, Items.ACACIA_LOG, Items.ACACIA_SAPLING,
        Items.DARK_OAK_LOG, Items.DARK_OAK_SAPLING, Items.CHERRY_LOG, Items.CHERRY_SAPLING, Items.MANGROVE_LOG, Items.MANGROVE_PROPAGULE);

    // ------------------------------------------------------------------ where, and whether there is work

    @Nullable
    static ShiftTasks.Post post(ServerLevel level, Villages.Village v, ShiftRole r) {
        if (r == ShiftRole.WOOD) {
            List<BlockPos> woods = grounds(v, StationTask.WOOD);
            if (woods.isEmpty()) return null;
            boolean ready = tree(level, woods, null) != null;
            return new ShiftTasks.Post(woods.get(0), null, ready, "No grown trees in the woods just now.");
        }
        Drover.Pen pen = Drover.pen(v.id());
        if (pen == null) return null;
        return new ShiftTasks.Post(pen.centre(), null, sheep(level, v, null) != null, "The flock's all shorn just now.");
    }

    /** The middles of the town's folk's grounds of this trade. */
    private static List<BlockPos> grounds(Villages.Village v, StationTask trade) {
        List<BlockPos> out = new ArrayList<>();
        for (AssistantEntity a : Villages.folkOf(v.id())) {
            if (a.stationTask() == trade && a.workZone() != null && out.size() < 4) out.add(a.workZone().center());
        }
        return out;
    }

    @Nullable
    static Task task(ServerLevel level, ServerPlayer p, Shift s, Villages.Village v, long now) {
        if (s.role == ShiftRole.WOOD) {
            BlockPos base = tree(level, grounds(v, StationTask.WOOD), p.blockPosition());
            if (base == null) return null;
            Task t = new Task("felled and replanted", "Fell the marked tree, every log of it; then plant a sapling where it stood.", base, now,
                walk(p.blockPosition(), base) + 60L * trunk(level, base) + 300L);
            t.item = level.getBlockState(base).getBlock().asItem();
            t.count = trunk(level, base);
            t.marks.add(base);
            return t;
        }
        Sheep sh = sheep(level, v, p.blockPosition());
        if (sh == null) return null;
        Task t = new Task("sheared", "Shear the marked sheep with your shears.", sh.blockPosition(), now, walk(p.blockPosition(), sh.blockPosition()) + 120L);
        t.entity = sh.getId();
        t.marks.add(sh.blockPosition());
        return t;
    }

    private static long walk(BlockPos a, BlockPos b) {
        return (long) (Math.sqrt(a.distSqr(b)) / ShiftTasks.WALK * 20);
    }

    // ------------------------------------------------------------------ the trees

    /**
     * A grown tree in the woods, nearest {@code near} (or the first found): the base log of a trunk of three to fourteen logs
     * standing on soil, with leaves at its crown, not a player's task already. Null if none.
     */
    @Nullable
    static BlockPos tree(ServerLevel level, List<BlockPos> woods, @Nullable BlockPos near) {
        BlockPos best = null;
        for (BlockPos c : woods) {
            for (int dx = -WOODS_REACH; dx <= WOODS_REACH; dx++) {
                for (int dz = -WOODS_REACH; dz <= WOODS_REACH; dz++) {
                    int x = c.getX() + dx, z = c.getZ() + dz;
                    if (!level.isLoaded(new BlockPos(x, c.getY(), z))) continue;
                    BlockPos base = trunkBase(level, x, z);
                    if (base == null || claimed(base)) continue;
                    if (near == null) return base;
                    if (best == null || base.distSqr(near) < best.distSqr(near)) best = base;
                }
            }
        }
        return best;
    }

    /** The bottom log of a natural trunk in this column, or null. */
    @Nullable
    static BlockPos trunkBase(ServerLevel level, int x, int z) {
        int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
        BlockPos p = new BlockPos(x, top, z);
        if (!level.getBlockState(p).is(BlockTags.LOGS)) return null;
        // The crown: leaves over it or beside its top.
        if (!level.getBlockState(p.above()).is(BlockTags.LEAVES) && !level.getBlockState(p.north()).is(BlockTags.LEAVES)
                && !level.getBlockState(p.south()).is(BlockTags.LEAVES)) return null;
        int n = 0;
        while (level.getBlockState(p.below()).is(BlockTags.LOGS) && n < TRUNK_MOST) { p = p.below(); n++; }
        if (n + 1 < 3 || n + 1 > TRUNK_MOST || !SAPLINGS.containsKey(level.getBlockState(p).getBlock().asItem())) return null;
        return level.getBlockState(p.below()).is(BlockTags.DIRT) ? p : null;
    }

    /** The logs of the trunk standing on this base. */
    static int trunk(ServerLevel level, BlockPos base) {
        int n = 0;
        for (BlockPos p = base; n < TRUNK_MOST + 2 && level.getBlockState(p).is(BlockTags.LOGS); p = p.above()) n++;
        return n;
    }

    private static boolean claimed(BlockPos base) {
        for (Shift s : Shifts.ON.values()) if (s.task != null && s.task.marks.contains(base)) return true;
        return false;
    }

    /** Is this block one of the logs of the shift's marked trunk (its column, at or over its base)? */
    private static boolean inTrunk(Task t, BlockPos at) {
        return t.target != null && at.getX() == t.target.getX() && at.getZ() == t.target.getZ() && at.getY() >= t.target.getY()
            && at.getY() <= t.target.getY() + TRUNK_MOST;
    }

    // ------------------------------------------------------------------ the flock

    /** A sheep of the town's flock in wool, grown, near the pen; nearest {@code near}; not a player's task already. */
    @Nullable
    static Sheep sheep(ServerLevel level, Villages.Village v, @Nullable BlockPos near) {
        Drover.Pen pen = Drover.pen(v.id());
        if (pen == null || !level.isLoaded(pen.centre())) return null;
        Sheep best = null;
        for (Sheep sh : level.getEntitiesOfClass(Sheep.class, new AABB(pen.centre()).inflate(PEN_REACH, 6, PEN_REACH),
                x -> x.isAlive() && !x.isBaby() && x.readyForShearing() && x.getTags().contains(Drover.HERD))) {
            if (taken(sh)) continue;
            if (near == null) return sh;
            if (best == null || sh.distanceToSqr(near.getX(), near.getY(), near.getZ()) < best.distanceToSqr(near.getX(), near.getY(), near.getZ())) best = sh;
        }
        return best;
    }

    private static boolean taken(Sheep sh) {
        for (Shift s : Shifts.ON.values()) if (s.role == ShiftRole.SHEAR && s.task != null && s.task.entity == sh.getId()) return true;
        return false;
    }

    // ------------------------------------------------------------------ every second, while a task is on

    static void watch(ServerLevel level, ServerPlayer p, Shift s, Task t) {
        long now = level.getGameTime();
        if (s.role == ShiftRole.SHEAR) {
            if (!(level.getEntity(t.entity) instanceof Sheep sh) || !sh.isAlive() || !sh.readyForShearing()) {
                s.task = null;                                                      // shorn by the rancher, or gone: no fault
                return;
            }
            if (t.target != null && sh.blockPosition().distManhattan(t.target) > 1) {
                t.target = sh.blockPosition();
                t.marks.clear();
                t.marks.add(t.target);
            }
            return;
        }
        if (t.target == null) return;
        if (t.leg == 0) {
            if (level.getBlockState(t.target).is(BlockTags.LOGS)) return;
            // The trunk is down (the player's work, or the woodcutter's meanwhile: then there is nothing to plant for).
            if (t.laid == 0) {
                s.task = null;
                return;
            }
            t.leg = 1;
            t.since = now;
            Item sapling = SAPLINGS.getOrDefault(t.item, Items.OAK_SAPLING);
            Villages.Village v = Villages.get(s.village);
            ItemStack one = v == null ? ItemStack.EMPTY : Crafts.takeOne(level, v, st -> st.is(sapling));
            boolean handed = !one.isEmpty() && give(p, one);
            t.text = handed ? "Now plant the sapling you were handed where the tree stood."
                : "Now plant a sapling where the tree stood (the stores have none to hand you: one of your own).";
            return;
        }
        if (now - t.since > REPLANT) Shifts.done(level, p, s, t, 2, 1, "felling a tree, not replanted", null);
    }

    private static boolean give(ServerPlayer p, ItemStack s) {
        if (!p.getInventory().add(s)) p.drop(s, false);
        return true;
    }

    // ------------------------------------------------------------------ the player's hands at work

    /** A log of the marked trunk felled: counted (its drops go to the stores: onDrops). */
    @SubscribeEvent
    public static void onBreak(BlockEvent.BreakEvent e) {
        if (Shifts.ON.isEmpty() || !(e.getLevel() instanceof ServerLevel) || !(e.getPlayer() instanceof ServerPlayer p)) return;
        Shift s = Shifts.of(p);
        if (s == null || s.role != ShiftRole.WOOD || s.task == null || s.task.leg != 0) return;
        if (e.getState().is(BlockTags.LOGS) && inTrunk(s.task, e.getPos())) s.task.laid++;
    }

    /** The marked trunk's logs: into the town's stores, not onto the ground (what will not fit, to the player). */
    @SubscribeEvent
    public static void onDrops(BlockDropsEvent e) {
        if (Shifts.ON.isEmpty() || !(e.getBreaker() instanceof ServerPlayer p)) return;
        Shift s = Shifts.of(p);
        if (s == null || s.role != ShiftRole.WOOD || s.task == null || !e.getState().is(BlockTags.LOGS) || !inTrunk(s.task, e.getPos())) return;
        for (ItemEntity ie : e.getDrops()) {
            ItemStack left = Market.intoStores(e.getLevel(), s.village, ie.getItem().copy());
            if (!left.isEmpty()) give(p, left);
        }
        e.getDrops().clear();
    }

    /** A sapling set where the trunk stood: the task done. */
    @SubscribeEvent
    public static void onPlace(BlockEvent.EntityPlaceEvent e) {
        if (Shifts.ON.isEmpty() || !(e.getLevel() instanceof ServerLevel level) || !(e.getEntity() instanceof ServerPlayer p)) return;
        Shift s = Shifts.of(p);
        if (s == null || s.role != ShiftRole.WOOD || s.task == null || s.task.leg != 1 || s.task.target == null) return;
        BlockState placed = e.getPlacedBlock();
        if (!placed.is(BlockTags.SAPLINGS) || e.getPos().distManhattan(s.task.target) > 2) return;
        Shifts.done(level, p, s, s.task, 0, 1, "felling a tree of " + s.task.laid + " logs and planting it again", null);
    }

    /**
     * Shears on a sheep: the marked one shorn the town's way (its wool into the stores, the player's shears worn); one of the
     * flock not marked, refused and a mistake. Ahead of the sheep's own shearing.
     */
    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onShear(PlayerInteractEvent.EntityInteract e) {
        if (Shifts.ON.isEmpty() || !(e.getLevel() instanceof ServerLevel level) || !(e.getEntity() instanceof ServerPlayer p)) return;
        if (!(e.getTarget() instanceof Sheep sh) || !e.getItemStack().is(Items.SHEARS)) return;
        Shift s = Shifts.of(p);
        if (s == null || s.role != ShiftRole.SHEAR || s.task == null) return;
        e.setCanceled(true);
        e.setCancellationResult(InteractionResult.SUCCESS);
        if (sh.getId() != s.task.entity) {
            if (sh.getTags().contains(Drover.HERD)) {
                s.task.mistakes++;
                p.displayClientMessage(Component.literal("Not that one: shear the marked sheep.").withStyle(ChatFormatting.RED), true);
            }
            return;
        }
        if (!sh.readyForShearing()) return;
        int wool = shear(level, s.village, sh);
        e.getItemStack().hurtAndBreak(1, p, e.getHand() == net.minecraft.world.InteractionHand.MAIN_HAND ? EquipmentSlot.MAINHAND : EquipmentSlot.OFFHAND);
        Shifts.done(level, p, s, s.task, 0, 1, "shearing a sheep (" + wool + " wool to the stores)", null);
    }

    /** A sheep shorn as the shears would (one to three of its colour's wool), the wool into the stores; how many. */
    static int shear(ServerLevel level, UUID village, Sheep sh) {
        level.playSound(null, sh, SoundEvents.SHEEP_SHEAR, SoundSource.PLAYERS, 1.0F, 1.0F);
        sh.setSheared(true);
        int n = 1 + sh.getRandom().nextInt(3);
        Item wool = BuiltInRegistries.ITEM.get(ResourceLocation.withDefaultNamespace(sh.getColor().getName() + "_wool"));
        ItemStack left = Market.intoStores(level, village, new ItemStack(wool == Items.AIR ? Items.WHITE_WOOL : wool, n));
        if (!left.isEmpty()) sh.spawnAtLocation(left);
        return n;
    }
}
