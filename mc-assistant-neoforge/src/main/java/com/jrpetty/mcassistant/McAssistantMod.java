package com.jrpetty.mcassistant;

import com.jrpetty.mcassistant.entity.AssistantEntity;
import com.jrpetty.mcassistant.menu.AssistantMenu;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.inventory.MenuType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * MC Assistant — an in-game companion entity you command through chat
 * ("!follow", "!gather logs 16") or the /assistant command. This is the
 * NeoForge port of the mc-assistant project: the logic runs server-side on
 * the entity itself, so it works in single player and on dedicated servers.
 */
@Mod(McAssistantMod.MODID)
public final class McAssistantMod {
    public static final String MODID = "mc_assistant";

    private static final DeferredRegister<EntityType<?>> ENTITY_TYPES =
        DeferredRegister.create(Registries.ENTITY_TYPE, MODID);
    private static final DeferredRegister<MenuType<?>> MENU_TYPES =
        DeferredRegister.create(Registries.MENU, MODID);

    public static final DeferredHolder<EntityType<?>, EntityType<AssistantEntity>> ASSISTANT =
        ENTITY_TYPES.register("assistant", () -> EntityType.Builder
            .of(AssistantEntity::new, MobCategory.CREATURE)
            .sized(0.6F, 1.95F)
            .clientTrackingRange(10)
            .build("assistant"));

    // Extended menu type: the entity id travels to the client in the buffer.
    public static final DeferredHolder<MenuType<?>, MenuType<AssistantMenu>> ASSISTANT_MENU =
        MENU_TYPES.register("assistant", () -> IMenuTypeExtension.create(AssistantMenu::new));

    public McAssistantMod(IEventBus modBus) {
        ENTITY_TYPES.register(modBus);
        MENU_TYPES.register(modBus);
        modBus.addListener(this::onEntityAttributes);

        NeoForge.EVENT_BUS.register(AssistantCommands.class);
        NeoForge.EVENT_BUS.register(ChatControl.class);
    }

    private void onEntityAttributes(EntityAttributeCreationEvent event) {
        event.put(ASSISTANT.get(), AssistantEntity.createAttributes().build());
    }
}
