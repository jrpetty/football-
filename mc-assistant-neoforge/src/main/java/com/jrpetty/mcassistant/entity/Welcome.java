package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.village.Chronicle;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;

import java.util.List;
import java.util.UUID;

/**
 * What a village does for a player it has taken to its heart.
 *
 * <p>An honoured guest: the village resolves to build them a house of their own, as
 * it would for one of its own families, and when it stands the next folk they talk
 * to hands them its key. Nobody else will sleep in its bed. A hero: the village
 * holds a night of celebration in their name, fireworks and all, and gives them its
 * medal. Both go into the village's chronicle, for good.
 */
public final class Welcome {

    private Welcome() {}

    /** Called every couple of seconds for a player in a village: has their standing earned anything? */
    public static void check(ServerLevel level, Player p, UUID village) {
        Standing.View v = Standing.of(village, p.getUUID(), level.getGameTime());
        if (!v.title().atLeast(Standing.Title.HONOURED)) return;
        long day = level.getDayTime() / 24000L;
        String you = p.getName().getString();
        Chronicle.Guest g = Chronicle.guest(village, p.getUUID());
        if (g == null) {
            Chronicle.welcome(village, p.getUUID(), you);
            Villages.tell(village, day, "the village resolved to build " + you + " a house");
            say(level, p, "We've talked it over, " + you + " — the village is going to build you a house of your own!");
            return;
        }
        if (v.title() == Standing.Title.HERO && !g.hero) {
            g.hero = true;
            Chronicle.touch();
            Villages.tell(village, day, you + " was named the hero of " + Villages.name(village));
            Gatherings.honour(village, you, day);
            say(level, p, you + "! The whole village is talking — you're the hero of " + Villages.name(village)
                + "! Come to the heart tonight!");
        }
        // And a statue on the square, once there is room for it.
        if (v.title() == Standing.Title.HERO && !com.jrpetty.mcassistant.village.Ledger.statue(village, p.getUUID())
                && level.getGameTime() % 200L < 50L) {
            Villages.Village where = Villages.get(village);
            if (where != null) Citizens.statue(level, where, p);
        }
    }

    private static void say(ServerLevel level, Player p, String text) {
        List<VillageFolkEntity> near = level.getEntitiesOfClass(VillageFolkEntity.class, p.getBoundingBox().inflate(16.0),
            f -> f.isAlive() && !f.isSleeping() && !f.isBaby());
        if (near.isEmpty()) return;
        near.sort(java.util.Comparator.comparingDouble(f -> f.distanceToSqr(p)));
        FolkTalk.speak(near.get(0), text);
    }

    /** At the start of a talk: anything the village has been keeping for this player. Empty if nothing. */
    static String handOver(VillageFolkEntity f, Player p) {
        UUID village = f.ownerId();
        if (village == null) return "";
        Chronicle.Guest g = Chronicle.guest(village, p.getUUID());
        if (g == null) return "";
        String name = Villages.name(village);
        StringBuilder said = new StringBuilder();
        if (g.built && !g.keyGiven) {
            ItemStack key = new ItemStack(Items.TRIPWIRE_HOOK);
            key.set(DataComponents.CUSTOM_NAME, Component.literal("Key to your house in " + name));
            key.set(DataComponents.LORE, new ItemLore(List.of(
                Component.literal("It stands at " + g.x + ", " + g.z + ".").withStyle(net.minecraft.ChatFormatting.GRAY),
                Component.literal("The bed is yours: nobody here will take it.").withStyle(net.minecraft.ChatFormatting.GRAY))));
            if (!p.getInventory().add(key)) p.drop(key, false);
            g.keyGiven = true;
            Chronicle.touch();
            said.append("Your house is finished! Here's the key — it's at ").append(g.x).append(", ").append(g.z)
                .append(". The bed's yours; nobody here will sleep in it. ");
        }
        if (g.hero && !g.medalGiven) {
            ItemStack medal = new ItemStack(Items.GOLD_NUGGET);
            long day = f.level().getDayTime() / 24000L;
            medal.set(DataComponents.CUSTOM_NAME, Component.literal("Medal of " + name).withStyle(net.minecraft.ChatFormatting.GOLD));
            medal.set(DataComponents.LORE, new ItemLore(List.of(
                Component.literal("Awarded to " + p.getName().getString() + ",").withStyle(net.minecraft.ChatFormatting.GRAY),
                Component.literal("hero of " + name + ", on day " + day + ".").withStyle(net.minecraft.ChatFormatting.GRAY))));
            medal.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
            if (!p.getInventory().add(medal)) p.drop(medal, false);
            g.medalGiven = true;
            Chronicle.touch();
            said.append("And this is from all of us — the medal of ").append(name).append(". You've earned it. ");
        }
        return said.toString();
    }
}
