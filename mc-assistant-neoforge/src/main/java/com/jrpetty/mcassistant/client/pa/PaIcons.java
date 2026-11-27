package com.jrpetty.mcassistant.client.pa;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.HashMap;
import java.util.Map;

/** [pa33] The small picture for what the assistant is doing (its plate, the HUD, the jobs list): the tool of the work. */
public final class PaIcons {

    private PaIcons() {}

    private static final Map<String, ItemStack> KEPT = new HashMap<>();

    public static ItemStack of(String key) {
        return KEPT.computeIfAbsent(key == null ? "" : key, k -> new ItemStack(item(k)));
    }

    static Item item(String k) {
        return switch (k) {
            case "gather" -> Items.IRON_AXE;
            case "mine_ore", "mine_down" -> Items.IRON_PICKAXE;
            case "build" -> Items.BRICKS;
            case "farm", "breed" -> Items.WHEAT;
            case "craft" -> Items.CRAFTING_TABLE;
            case "smelt" -> Items.FURNACE;
            case "deposit", "fetch", "sort" -> Items.CHEST;
            case "give", "bring_food" -> Items.BREAD;
            case "fight" -> Items.IRON_SWORD;
            case "hunt" -> Items.BOW;
            case "fish" -> Items.FISHING_ROD;
            case "shear" -> Items.SHEARS;
            case "light_area" -> Items.TORCH;
            case "clear_area" -> Items.IRON_SHOVEL;
            case "fuel" -> Items.COAL;
            case "trade_buy", "trade_sell" -> Items.EMERALD;
            case "go_home" -> Items.RED_BED;
            case "explore" -> Items.COMPASS;
            case "guard", "guard_me", "guard_here" -> Items.SHIELD;
            case "follow" -> Items.LEAD;
            case "stay" -> Items.OAK_SIGN;
            case "paused" -> Items.CLOCK;
            case "dead" -> Items.POPPY;
            default -> Items.BOOK;
        };
    }

    /** Draw it 12 pixels square at x, y. */
    public static void draw(GuiGraphics g, String key, int x, int y) {
        g.pose().pushPose();
        g.pose().translate(x, y, 0);
        g.pose().scale(0.75F, 0.75F, 1.0F);
        g.renderItem(of(key), 0, 0);
        g.pose().popPose();
    }
}
