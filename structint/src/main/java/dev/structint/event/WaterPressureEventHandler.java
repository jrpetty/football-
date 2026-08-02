package dev.structint.event;

import dev.structint.world.WaterPressureScanner;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

/**
 * Drives the water-pressure sweep. Kept separate from {@link StructuralEventHandler} so the
 * pressure feature can be reasoned about, and disabled, on its own.
 */
@EventBusSubscriber(modid = "structint")
public final class WaterPressureEventHandler {

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        Level level = event.getLevel();
        if (level instanceof ServerLevel serverLevel) {
            WaterPressureScanner.tick(serverLevel);
        }
    }

    @SubscribeEvent
    public static void onLevelUnload(LevelEvent.Unload event) {
        LevelAccessor level = event.getLevel();
        if (level instanceof ServerLevel serverLevel) {
            WaterPressureScanner.forget(serverLevel);
        }
    }

    private WaterPressureEventHandler() {
    }
}
