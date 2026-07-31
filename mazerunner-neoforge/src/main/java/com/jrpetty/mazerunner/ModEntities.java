package com.jrpetty.mazerunner;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Custom entities. The headline one is the {@link GrieverEntity} — the Maze's
 * nocturnal hunter. It reuses the vanilla spider hitbox dimensions so it paths
 * cleanly through the 2-wide minigame corridors.
 */
public final class ModEntities {

    public static final DeferredRegister<EntityType<?>> ENTITIES =
        DeferredRegister.create(Registries.ENTITY_TYPE, MazeRunnerMod.MODID);

    public static final DeferredHolder<EntityType<?>, EntityType<GrieverEntity>> GRIEVER =
        ENTITIES.register("griever", () -> EntityType.Builder
            .of(GrieverEntity::new, MobCategory.MONSTER)
            .sized(1.4F, 0.9F)          // matches vanilla spider — fits a 2-wide corridor
            .clientTrackingRange(10)
            .build("griever"));

    /** Mod-event-bus listener: give the Griever its attributes. */
    public static void onAttributeCreation(EntityAttributeCreationEvent event) {
        event.put(GRIEVER.get(), GrieverEntity.createAttributes().build());
    }

    private ModEntities() {}
}
