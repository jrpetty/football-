package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.item.PropertyDeeds;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

import java.util.UUID;

/**
 * [wpc] Deeds (item/DeedItem): written for a title when it is bought or passed on, and read against the register when used.
 * Kinds: "land" (LandPlots), "house" (a house on the homes' books, by its anchor), "premises" (Premises).
 */
public final class PropertyItems {

    private PropertyItems() {}

    /** A deed for this title into the player's pack (an old copy of the same title in it is replaced). */
    public static void deed(ServerPlayer p, UUID village, String kind, String key, String name) {
        for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
            ItemStack s = p.getInventory().getItem(i);
            if (!s.is(PropertyDeeds.DEED.get())) continue;
            CompoundTag t = s.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
            if (t.getString("key").equals(key) && t.getString("kind").equals(kind)) p.getInventory().setItem(i, ItemStack.EMPTY);
        }
        ItemStack s = new ItemStack(PropertyDeeds.DEED.get());
        CompoundTag t = new CompoundTag();
        t.putUUID("town", village);
        t.putString("townName", Villages.name(village));
        t.putString("kind", kind);
        t.putString("key", key);
        t.putString("name", name);
        t.putUUID("holderId", p.getUUID());
        t.putString("holder", Belonging.called(p));
        t.putLong("issued", p.level().getDayTime() / 24000L);
        s.set(DataComponents.CUSTOM_DATA, CustomData.of(t));
        s.set(DataComponents.CUSTOM_NAME, Component.literal("Deed: " + name));
        if (!p.getInventory().add(s)) p.drop(s, false);
    }

    /** Does the register still have this title as the player's? */
    public static boolean good(ServerPlayer p, CompoundTag t) {
        if (!t.hasUUID("town")) return false;
        UUID village = t.getUUID("town");
        String key = t.getString("key");
        return switch (t.getString("kind")) {
            case "land" -> {
                LandPlots.Title x = LandPlots.get(village, key);
                yield x != null && x.owner().equals(p.getUUID());
            }
            case "house" -> {
                try {
                    Homes.Home h = Homes.homes(village).get(Long.parseLong(key));
                    yield h != null && h.tenure == Homes.Tenure.PLAYER && p.getUUID().equals(h.landlord);
                } catch (NumberFormatException e) {
                    yield false;
                }
            }
            case "premises" -> Premises.holder(village, key) != null && p.getUUID().equals(Premises.holder(village, key));
            default -> false;
        };
    }

    /** A deed used: its page, or why it is only paper now. */
    public static void useDeed(ServerPlayer p, ItemStack held) {
        CompoundTag t = held.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        if (!t.contains("kind")) { p.sendSystemMessage(Component.literal("A blank deed: the register writes them.")); return; }
        if (!good(p, t)) {
            p.sendSystemMessage(Component.literal("This deed is void: the register no longer has " + t.getString("name") + " as yours. "
                + "The register is what counts; the paper is only its copy."));
            return;
        }
        switch (t.getString("kind")) {
            case "land" -> PlayerLife.open(p, "land", LifePage.args("tab", "mine"));
            case "premises" -> PlayerLife.open(p, "premises", LifePage.args("tab", "mine"));
            default -> PlayerLife.open(p, "home", null);
        }
    }
}
