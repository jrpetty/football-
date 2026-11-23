package com.jrpetty.mcassistant.pa;

import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TieredItem;

import java.util.ArrayList;
import java.util.List;

/**
 * [pa33d] Better gear from what's in its pack: diamonds first, then iron, the pickaxe and sword before the armour, only
 * what beats what it has, and only as much as the materials go round. What to craft, by name, for the crafting planner.
 */
public final class PaGear {

    private PaGear() {}

    /** The order it spends materials in, with what each costs. */
    private static final String[][] PLAN = {
        {"pickaxe", "3"}, {"sword", "2"}, {"chestplate", "8"}, {"helmet", "5"}, {"leggings", "7"}, {"boots", "4"}, {"axe", "3"}};

    public static List<String> upgrades(PlayerAssistantEntity a) {
        int diamonds = a.countMatching(s -> s.is(Items.DIAMOND)), iron = a.countMatching(s -> s.is(Items.IRON_INGOT));
        List<String> out = new ArrayList<>();
        for (String[] p : PLAN) {
            int cost = Integer.parseInt(p[1]);
            int have = tier(a, p[0]);
            if (have < 3 && diamonds >= cost) { out.add("diamond " + p[0]); diamonds -= cost; }
            else if (have < 2 && iron >= cost) { out.add("iron " + p[0]); iron -= cost; }
        }
        return out;
    }

    /** What it has of a kind now: 0 none or leather/wood/gold, 1 stone/chain, 2 iron, 3 diamond or better. */
    static int tier(PlayerAssistantEntity a, String kind) {
        int best = 0;
        List<ItemStack> all = new ArrayList<>(a.getInventoryItems());
        for (EquipmentSlot e : EquipmentSlot.values()) all.add(a.getItemBySlot(e));
        for (ItemStack s : all) {
            if (s.isEmpty()) continue;
            String id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(s.getItem()).getPath();
            if (!id.endsWith("_" + kind)) continue;
            int t = id.startsWith("netherite") || id.startsWith("diamond") ? 3 : id.startsWith("iron") ? 2 : id.startsWith("stone") || id.startsWith("chainmail") ? 1 : 0;
            best = Math.max(best, t);
        }
        return best;
    }

    static boolean isGear(ItemStack s) {
        return s.getItem() instanceof TieredItem || s.getItem() instanceof ArmorItem;
    }
}
