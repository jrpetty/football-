package com.jrpetty.mcassistant.entity;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.List;

/**
 * A light to walk home by. [townlife]
 *
 * <p>A grown folk out of doors after dark carries a light in its free hand: a lantern if it has one in
 * its pack, else a torch — its own, out of its own pack, and only if it has one. Nothing is lit or
 * placed: it is only what it holds. It is put back in the pack when the folk goes indoors (a roof over
 * its head), lies down to sleep, has a fight on its hands, or the day breaks. A hand already full (a
 * guard's shield, a pastime's prop) stays as it is, and a guard with a shield in its pack keeps the hand
 * free for it.
 */
public final class NightLight {

    private NightLight() {}

    /** The lights a folk carries, the best first. */
    static final List<Item> LIGHTS = List.of(Items.LANTERN, Items.SOUL_LANTERN, Items.TORCH, Items.SOUL_TORCH);

    /** From dusk (the street lamps' hour) to daybreak. */
    static boolean dark(long dayTime) {
        long t = Math.floorMod(dayTime, 24000L);
        return t >= 12800L && t < 23200L;
    }

    public static boolean isLight(ItemStack s) {
        if (s.isEmpty()) return false;
        for (Item i : LIGHTS) if (s.is(i)) return true;
        return false;
    }

    /** Every second (VillageFolkEntity.aiStep): a light out of the pack after dark, out of doors; back in it otherwise. */
    static void tick(VillageFolkEntity f) {
        if (!(f.level() instanceof ServerLevel level) || f.isShowcase()) return;
        ItemStack off = f.getItemBySlot(EquipmentSlot.OFFHAND);
        boolean holding = isLight(off);
        boolean wants = !f.isBaby() && !f.isSleeping() && f.getTarget() == null && !f.isPassenger()
            && dark(level.getDayTime()) && underSky(level, f)
            && !(f.stationTask() == AssistantEntity.StationTask.GUARD && f.countMatching(s -> s.is(Items.SHIELD)) > 0);   // the watch's hand is for its shield
        if (holding && !wants) {
            putAway(f, off);
        } else if (!holding && wants && off.isEmpty()) {
            takeOut(f);
        }
    }

    /**
     * Nothing over its head: read off the heightmap, which knows of a roof the moment it is laid (the sky
     * light under a new roof is worked out a little later, so a folk once kept its lantern out indoors).
     */
    static boolean underSky(ServerLevel level, VillageFolkEntity f) {
        return level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, f.getBlockX(), f.getBlockZ()) <= f.getBlockY() + 1;
    }

    /** The best light in its pack into its free hand. False if it carries none. */
    static boolean takeOut(VillageFolkEntity f) {
        for (Item light : LIGHTS) {
            for (ItemStack s : f.getInventoryItems()) {
                if (s.isEmpty() || !s.is(light)) continue;
                ItemStack one = s.copyWithCount(1);
                if (f.removeMatching(x -> ItemStack.isSameItemSameComponents(x, one), 1) < 1) return false;
                f.setItemSlot(EquipmentSlot.OFFHAND, one);
                return true;
            }
        }
        return false;
    }

    /** Back in the pack; what will not fit (a full pack) stays in its hand. */
    static void putAway(VillageFolkEntity f, ItemStack held) {
        ItemStack left = f.insertItem(held.copy());
        f.setItemSlot(EquipmentSlot.OFFHAND, left);
    }

    /** For the tests: one look now, whatever the tick. */
    public static void tickForTests(VillageFolkEntity f) {
        tick(f);
    }
}
