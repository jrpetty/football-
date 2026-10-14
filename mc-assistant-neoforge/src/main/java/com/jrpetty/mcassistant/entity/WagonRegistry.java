package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.McAssistantMod;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/** [wagons] The trade wagon's entity type (TradeWagon): two and a half blocks square to pick, tracked out to ten chunks. */
public final class WagonRegistry {

    private WagonRegistry() {}

    private static final DeferredRegister<EntityType<?>> ENTITIES = DeferredRegister.create(Registries.ENTITY_TYPE, McAssistantMod.MODID);

    public static final DeferredHolder<EntityType<?>, EntityType<TradeWagon>> TRADE_WAGON = ENTITIES.register("trade_wagon",
        () -> EntityType.Builder.<TradeWagon>of(TradeWagon::new, MobCategory.MISC).sized(2.2F, 1.6F).clientTrackingRange(10)
            .updateInterval(2).noSummon().build("trade_wagon"));

    public static void register(IEventBus modBus) {
        ENTITIES.register(modBus);
    }
}
