package com.jrpetty.mcassistant.pa;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.AbstractFurnaceBlock;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.BrewingStandBlock;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.ComposterBlock;
import net.minecraft.world.level.block.EnderChestBlock;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * [pa36] Containers as the assistant meets them: which blocks count (chests, double and trapped chests, barrels, shulker
 * boxes, its player's ender chest, hoppers, furnaces, smokers, blast furnaces, brewing stands, composters, dispensers and
 * droppers), whether it may open one, opening and closing it as a player does (the lid, the sound), and moving one
 * stack at a time in or out.
 *
 * <p>May it? Only its player's containers: ones its player placed, opened or pinged, or pointed it at; never one
 * another player placed, never a locked one without the key, never a town's stores or claimed ground (PaChests and
 * PaSafety), never inside spawn protection its player couldn't open, and never where a claims mod says its player may
 * not (asked the way those mods are asked: a right-click by its player, cancelled or not).
 */
public final class PaContainers {

    private PaContainers() {}

    /** Is this block a container the assistant works with? */
    public static boolean is(Level level, BlockPos p) {
        if (!level.isLoaded(p)) return false;
        BlockState s = level.getBlockState(p);
        if (s.getBlock() instanceof ComposterBlock || s.getBlock() instanceof EnderChestBlock) return true;
        return level.getBlockEntity(p) instanceof Container;
    }

    /** "chest", "barrel", "furnace", "smoker", "blast furnace", "brewing stand", "composter", "hopper", "dispenser", "dropper", "shulker", "ender chest". */
    public static String type(Level level, BlockPos p) {
        Block b = level.getBlockState(p).getBlock();
        if (b instanceof EnderChestBlock) return "ender chest";
        if (b instanceof ChestBlock) return "chest";
        if (b instanceof BarrelBlock) return "barrel";
        if (b instanceof ShulkerBoxBlock) return "shulker";
        if (b instanceof ComposterBlock) return "composter";
        if (b instanceof BrewingStandBlock) return "brewing stand";
        String path = BuiltInRegistries.BLOCK.getKey(b).getPath();
        if (path.equals("blast_furnace")) return "blast furnace";
        if (path.equals("smoker")) return "smoker";
        if (b instanceof AbstractFurnaceBlock) return "furnace";
        if (path.contains("hopper")) return "hopper";
        if (path.contains("dispenser")) return "dispenser";
        if (path.contains("dropper")) return "dropper";
        return "chest";
    }

    public static boolean furnace(String type) {
        return type.equals("furnace") || type.equals("smoker") || type.equals("blast furnace");
    }

    /** Does a container of this kind suit a place said as this type ("the furnace" fits a smoker too; "the chest" a barrel)? */
    public static boolean fits(String said, String actual) {
        if (said == null || said.isEmpty()) return true;
        if (said.equals(actual)) return true;
        if (said.equals("chest")) return actual.equals("chest") || actual.equals("barrel") || actual.equals("shulker");
        if (said.equals("furnace")) return furnace(actual);
        return false;
    }

    public static String blockId(Level level, BlockPos p) {
        return BuiltInRegistries.BLOCK.getKey(level.getBlockState(p).getBlock()).toString();
    }

    /** Its player, if on line and in reach of the server. */
    @Nullable
    static ServerPlayer owner(PlayerAssistantEntity a) {
        return a.getOwnerPlayer() instanceof ServerPlayer sp ? sp : null;
    }

    /** The container's inventory: a double chest as one, its player's ender chest, null for a composter or none. */
    @Nullable
    public static Container open(PlayerAssistantEntity a, BlockPos p) {
        Level level = a.level();
        if (!level.isLoaded(p)) return null;
        BlockState s = level.getBlockState(p);
        if (s.getBlock() instanceof EnderChestBlock) {
            ServerPlayer o = owner(a);
            return o == null ? null : o.getEnderChestInventory();
        }
        if (s.getBlock() instanceof ChestBlock cb) {
            Container c = ChestBlock.getContainer(cb, s, level, p, true);
            if (c != null) return c;
        }
        return level.getBlockEntity(p) instanceof Container c ? c : null;
    }

    // ------------------------------------------------------------------ may it?

    private static final ThreadLocal<Boolean> ASKING_CLAIMS = ThreadLocal.withInitial(() -> false);

    /** True while a claims check is being asked (the logistics' own listeners ignore that pretend right-click). */
    public static boolean askingClaims() {
        return ASKING_CLAIMS.get();
    }

    /** Null when it may use the container; else why not, as it would say it. */
    @Nullable
    public static String refuse(PlayerAssistantEntity a, BlockPos p) {
        Level level = a.level();
        if (!level.isLoaded(p)) return "I can't see that chest from here.";
        if (!is(level, p)) return "That isn't a container.";
        String dim = level.dimension().location().toString();
        UUID owner = a.getOwnerId();
        UUID placer = PaStore.placedBy(dim, p);
        if (placer != null && owner != null && !placer.equals(owner)) {
            String who = level.getServer() == null ? null : level.getServer().getProfileCache() == null ? null
                : level.getServer().getProfileCache().get(placer).map(com.mojang.authlib.GameProfile::getName).orElse(null);
            return PaLogiTalk.line(a, "logi.refuse", "who", who == null ? "someone else" : who);
        }
        ServerPlayer o = owner(a);
        BlockEntity be = level.getBlockEntity(p);
        if (be instanceof BaseContainerBlockEntity bc && o != null && !BaseContainerBlockEntity.canUnlock(o, lock(bc), bc.getDisplayName())) {
            return "That one's locked, and you've not given me the key.";
        }
        if (be instanceof BaseContainerBlockEntity bc && o == null && !lock(bc).equals(net.minecraft.world.LockCode.NO_LOCK)) return "That one's locked.";
        if (!PaChests.allowed(a, level, p)) {
            if (PaSafety.offLimits(a, p)) return "That's " + PaSafety.whose(a, p) + "'s. I don't take from other people's stores.";
            return "You've told me to leave your chests alone. Point me at one and I'll use that.";
        }
        if (o != null && level instanceof ServerLevel sl && sl.getServer().isUnderSpawnProtection(sl, p, o) && !o.hasPermissions(2)) {
            return "That's inside spawn protection. You couldn't open it, so I won't.";
        }
        if (o != null && !claimsAllow(o, p)) return "That's claimed ground, and you haven't the right to open it. Nor have I.";
        if (!known(a, p)) return "I don't know that chest is yours. Ping it or open it once and I'll use it.";
        return null;
    }

    private static net.minecraft.world.LockCode lock(BaseContainerBlockEntity bc) {
        try {
            java.lang.reflect.Field f = BaseContainerBlockEntity.class.getDeclaredField("lockKey");
            f.setAccessible(true);
            return (net.minecraft.world.LockCode) f.get(bc);
        } catch (ReflectiveOperationException | RuntimeException e) {
            // obfuscated name in production: read it from the saved tag instead
            net.minecraft.nbt.CompoundTag t = bc.saveWithoutMetadata(bc.getLevel().registryAccess());
            return net.minecraft.world.LockCode.fromTag(t);
        }
    }

    /** Is the container its player's: placed, opened or pinged by them, pointed at, or one it already knows? */
    public static boolean known(PlayerAssistantEntity a, BlockPos p) {
        if (a.pointedChest(p)) return true;
        UUID owner = a.getOwnerId();
        if (owner == null) return false;
        String dim = a.level().dimension().location().toString();
        if (owner.equals(PaStore.placedBy(dim, p))) return true;
        if (PaStore.of(owner).at(p, dim) != null) return true;
        // the other half of a double chest it knows
        BlockState s = a.level().getBlockState(p);
        if (s.getBlock() instanceof ChestBlock && s.getValue(ChestBlock.TYPE) != net.minecraft.world.level.block.state.properties.ChestType.SINGLE) {
            BlockPos other = p.relative(ChestBlock.getConnectedDirection(s));
            return a.pointedChest(other) || PaStore.of(owner).at(other, dim) != null || owner.equals(PaStore.placedBy(dim, other));
        }
        return false;
    }

    /** What claims mods say: a right-click on the block by its player, asked and not done. */
    static boolean claimsAllow(ServerPlayer o, BlockPos p) {
        if (o.level() != null && o.level().getServer() != null && o.connection == null) return true;   // a test's mock player
        ASKING_CLAIMS.set(true);
        try {
            BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(p), net.minecraft.core.Direction.UP, p, false);
            net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.RightClickBlock ev =
                new net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.RightClickBlock(o, InteractionHand.MAIN_HAND, p, hit);
            net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(ev);
            return !ev.isCanceled() && ev.getUseBlock() != net.neoforged.neoforge.common.util.TriState.FALSE;
        } catch (RuntimeException e) {
            return true;
        } finally {
            ASKING_CLAIMS.set(false);
        }
    }

    // ------------------------------------------------------------------ looking in

    /** What's in it, item id -> count (largest first). */
    public static Map<String, Integer> contents(Container c) {
        Map<String, Integer> m = new LinkedHashMap<>();
        if (c == null) return m;
        for (int i = 0; i < c.getContainerSize(); i++) {
            ItemStack s = c.getItem(i);
            if (!s.isEmpty()) m.merge(BuiltInRegistries.ITEM.getKey(s.getItem()).toString(), s.getCount(), Integer::sum);
        }
        Map<String, Integer> sorted = new LinkedHashMap<>();
        m.entrySet().stream().sorted((x, y) -> y.getValue() - x.getValue()).forEach(e -> sorted.put(e.getKey(), e.getValue()));
        return sorted;
    }

    public static int count(Container c, Predicate<ItemStack> what) {
        int n = 0;
        if (c == null) return 0;
        for (int i = 0; i < c.getContainerSize(); i++) {
            ItemStack s = c.getItem(i);
            if (!s.isEmpty() && what.test(s)) n += s.getCount();
        }
        return n;
    }

    /** How many of this stack would still fit (in slots that take it). */
    public static int room(Container c, ItemStack like) {
        if (c == null || like.isEmpty()) return 0;
        int n = 0;
        for (int i = 0; i < inputSlots(c); i++) {
            ItemStack s = c.getItem(i);
            if (s.isEmpty()) { if (c.canPlaceItem(i, like)) n += Math.min(c.getMaxStackSize(), like.getMaxStackSize()); }
            else if (ItemStack.isSameItemSameComponents(s, like)) n += Math.max(0, Math.min(c.getMaxStackSize(), s.getMaxStackSize()) - s.getCount());
        }
        return n;
    }

    public static int freeSlots(Container c) {
        int n = 0;
        for (int i = 0; i < c.getContainerSize(); i++) if (c.getItem(i).isEmpty()) n++;
        return n;
    }

    /** Slots a player would put things in (a furnace's output and a brewing stand's fuel are not for "put in"). */
    static int inputSlots(Container c) {
        return c.getContainerSize();
    }

    /** Snapshot it into the storage map (and learn it). */
    public static void snapshot(PlayerAssistantEntity a, BlockPos p, @Nullable Container c) {
        UUID owner = a.getOwnerId();
        if (owner == null) return;
        String dim = a.level().dimension().location().toString();
        PaStore.Known k = PaStore.learn(owner, dim, p, blockId(a.level(), p), a.level().getGameTime());
        if (c == null) return;
        k.snap.clear();
        int i = 0;
        for (Map.Entry<String, Integer> e : contents(c).entrySet()) { if (i++ >= 12) break; k.snap.put(e.getKey(), e.getValue()); }
        k.size = c.getContainerSize();
        k.used = c.getContainerSize() - freeSlots(c);
        PaStore.touch();
    }

    // ------------------------------------------------------------------ the lid and the sound

    /** Open it as a player does: the lid lifts, the barrel opens, the sound plays. */
    public static void lid(PlayerAssistantEntity a, BlockPos p, boolean open) {
        Level level = a.level();
        BlockState s = level.getBlockState(p);
        Block b = s.getBlock();
        try {
            if (b instanceof ChestBlock || b instanceof EnderChestBlock) {
                level.blockEvent(p, b, 1, open ? 1 : 0);
                if (s.getBlock() instanceof ChestBlock && s.getValue(ChestBlock.TYPE) != net.minecraft.world.level.block.state.properties.ChestType.SINGLE)
                    level.blockEvent(p.relative(ChestBlock.getConnectedDirection(s)), b, 1, open ? 1 : 0);
                level.playSound(null, p, b instanceof EnderChestBlock ? (open ? SoundEvents.ENDER_CHEST_OPEN : SoundEvents.ENDER_CHEST_CLOSE)
                    : (open ? SoundEvents.CHEST_OPEN : SoundEvents.CHEST_CLOSE), SoundSource.BLOCKS, 0.5F, 0.95F + level.random.nextFloat() * 0.1F);
            } else if (b instanceof BarrelBlock && s.hasProperty(BarrelBlock.OPEN)) {
                level.setBlock(p, s.setValue(BarrelBlock.OPEN, open), 3);
                level.playSound(null, p, open ? SoundEvents.BARREL_OPEN : SoundEvents.BARREL_CLOSE, SoundSource.BLOCKS, 0.5F, 0.95F + level.random.nextFloat() * 0.1F);
            } else if (b instanceof ShulkerBoxBlock) {
                level.blockEvent(p, b, 1, open ? 1 : 0);
                level.playSound(null, p, open ? SoundEvents.SHULKER_BOX_OPEN : SoundEvents.SHULKER_BOX_CLOSE, SoundSource.BLOCKS, 0.5F, 1.0F);
            }
        } catch (RuntimeException ignored) {
            // a lid is for show: a block that won't animate changes nothing
        }
    }

    // ------------------------------------------------------------------ one stack at a time

    /** Put up to {@code most} of one kind (the first matching stack in its pack, and its like) in: one hand action. Returns how many went in. */
    public static int putOne(PlayerAssistantEntity a, Container c, Predicate<ItemStack> what, int most, boolean fuelSlot) {
        var inv = a.getInventoryItems();
        for (int i = 0; i < inv.size(); i++) {
            ItemStack s = inv.get(i);
            if (s.isEmpty() || !what.test(s)) continue;
            int want = Math.min(most, s.getCount());
            int moved = insert(c, s, want, fuelSlot);
            if (moved > 0) {
                s.shrink(moved);
                if (s.isEmpty()) inv.set(i, ItemStack.EMPTY);
                c.setChanged();
                return moved;
            }
        }
        return 0;
    }

    /** Insert up to n of a stack's kind into the container (merging first); returns how many went. Furnaces: input or fuel slot. */
    public static int insert(Container c, ItemStack from, int n, boolean fuelSlot) {
        int left = Math.min(n, from.getCount());
        int moved = 0;
        if (c instanceof net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity) {
            int slot = fuelSlot ? 1 : 0;
            ItemStack in = c.getItem(slot);
            if (in.isEmpty()) {
                int go = Math.min(left, from.getMaxStackSize());
                ItemStack put = from.copyWithCount(go);
                c.setItem(slot, put);
                return go;
            }
            if (ItemStack.isSameItemSameComponents(in, from)) {
                int go = Math.min(left, in.getMaxStackSize() - in.getCount());
                if (go > 0) in.grow(go);
                return Math.max(0, go);
            }
            return 0;
        }
        for (int i = 0; i < c.getContainerSize() && left > 0; i++) {
            ItemStack s = c.getItem(i);
            if (!s.isEmpty() && ItemStack.isSameItemSameComponents(s, from) && c.canPlaceItem(i, from)) {
                int go = Math.min(left, Math.min(c.getMaxStackSize(), s.getMaxStackSize()) - s.getCount());
                if (go > 0) { s.grow(go); left -= go; moved += go; }
            }
        }
        for (int i = 0; i < c.getContainerSize() && left > 0; i++) {
            if (c.getItem(i).isEmpty() && c.canPlaceItem(i, from)) {
                int go = Math.min(left, Math.min(c.getMaxStackSize(), from.getMaxStackSize()));
                c.setItem(i, from.copyWithCount(go));
                left -= go;
                moved += go;
                break;                                                         // one new slot per action: a stack, as a shift-click moves it
            }
        }
        return moved;
    }

    /** Take up to {@code most} of one kind out (one slot, as a shift-click): into its pack. Returns how many came out. */
    public static int takeOne(PlayerAssistantEntity a, Container c, Predicate<ItemStack> what, int most, int fromSlot, int toSlot) {
        for (int i = Math.max(0, fromSlot); i < Math.min(c.getContainerSize(), toSlot); i++) {
            ItemStack s = c.getItem(i);
            if (s.isEmpty() || !what.test(s)) continue;
            int want = Math.min(most, s.getCount());
            ItemStack part = s.copyWithCount(want);
            ItemStack left = a.insertItem(part);
            int got = want - left.getCount();
            if (got > 0) {
                s.shrink(got);
                if (s.isEmpty()) c.setItem(i, ItemStack.EMPTY);
                c.setChanged();
                return got;
            }
            return 0;                                                          // its pack is full
        }
        return 0;
    }

    /** How many of this kind its pack could still take, in its open slots. */
    public static int packRoom(PlayerAssistantEntity a, ItemStack like) {
        var inv = a.getInventoryItems();
        int open = Math.min(inv.size(), PaPack.open(a.veteranLevel()));
        int n = 0;
        for (int i = 0; i < open; i++) {
            ItemStack s = inv.get(i);
            if (s.isEmpty()) n += like.getMaxStackSize();
            else if (ItemStack.isSameItemSameComponents(s, like)) n += s.getMaxStackSize() - s.getCount();
        }
        return n;
    }

    public static int packFreeSlots(PlayerAssistantEntity a) {
        var inv = a.getInventoryItems();
        int open = Math.min(inv.size(), PaPack.open(a.veteranLevel()));
        int n = 0;
        for (int i = 0; i < open; i++) if (inv.get(i).isEmpty()) n++;
        return n;
    }
}
