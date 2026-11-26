package com.jrpetty.mcassistant.item;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.entity.Belonging;
import com.jrpetty.mcassistant.entity.Citizens;
import com.jrpetty.mcassistant.entity.Villages;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.UUID;

/**
 * [wpa] The Town Hall's own item, registered here and joined to the mod with one line (McAssistantMod): a player's
 * citizen papers, its ID card (CitizenPapersItem). Issued at the hall's desk, never crafted: the first free, a
 * replacement for a small fee.
 */
public final class TownHallItems {

    private TownHallItems() {}

    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(McAssistantMod.MODID);

    public static final DeferredItem<CitizenPapersItem> CITIZEN_PAPERS = ITEMS.register("citizen_papers",
        () -> new CitizenPapersItem(new Item.Properties().stacksTo(1)));

    public static void register(IEventBus modBus) {
        ITEMS.register(modBus);
        modBus.addListener(TownHallItems::onTabs);
        com.jrpetty.mcassistant.entity.Makers.unmade("citizen_papers",
            "the Town Hall issues them at its desk, one set to a player, with the player's own name on them: a made set would be a forgery");
    }

    private static void onTabs(BuildCreativeModeTabContentsEvent event) {
        if (McAssistantMod.TAB.getKey().equals(event.getTabKey())) event.accept(CITIZEN_PAPERS);
    }

    /** Papers for this player in this town, into its pack (the register written down as of today). */
    public static ItemStack issue(ServerPlayer p, Villages.Village v) {
        ItemStack s = new ItemStack(CITIZEN_PAPERS.get());
        CompoundTag t = new CompoundTag();
        t.putUUID("owner", p.getUUID());
        t.putUUID("town", v.id());
        t.putString("townName", Villages.name(v.id()));
        t.putString("name", Belonging.called(p));
        long day = p.level().getDayTime() / 24000L;
        t.putLong("issued", day);
        t.putBoolean("citizen", Citizens.is(v.id(), p.getUUID()));
        s.set(DataComponents.CUSTOM_DATA, CustomData.of(t));
        if (!p.getInventory().add(s.copy())) p.drop(s.copy(), false);
        Belonging.papersIssued(v.id(), p.getUUID(), day);
        return s;
    }

    /** Does the player carry its own papers for this town? */
    public static boolean carriesPapers(Player p, UUID town) {
        for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
            ItemStack s = p.getInventory().getItem(i);
            if (!s.is(CITIZEN_PAPERS.get())) continue;
            CompoundTag t = s.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
            if (t.hasUUID("owner") && t.getUUID("owner").equals(p.getUUID()) && t.hasUUID("town") && t.getUUID("town").equals(town)) return true;
        }
        return false;
    }
}
