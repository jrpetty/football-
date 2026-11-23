package com.jrpetty.mcassistant.pa;

import com.jrpetty.mcassistant.McAssistantMod;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * [pa33] The Player AI Assistant goes where its player goes: it leaves with them when they log out and is back
 * beside them when they log in; it crosses to another dimension with them (unless it is busy, or told to stay);
 * a fallen one comes back on time; and a copy is kept now and then so one lost with an unloaded chunk can catch up.
 */
@EventBusSubscriber(modid = McAssistantMod.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class PaEvents {

    private PaEvents() {}

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer p)) return;
        PaLife.restore(p);
        PaNet.refresh(p, PaLife.find(p));
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer p)) return;
        for (PaData.Rec r : PaData.of(p.getUUID())) {
            PlayerAssistantEntity a = PaLife.entity(p.server, r);
            if (a != null) PaLife.stash(a);
            else if (!r.dead(p.serverLevel().getGameTime()) && r.snapshot != null) { r.away = true; PaData.touch(); }
        }
    }

    @SubscribeEvent
    public static void onDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer p)) return;
        for (PaData.Rec r : PaData.of(p.getUUID())) {
            PlayerAssistantEntity a = PaLife.entity(p.server, r);
            if (a == null || a.level() == p.level()) continue;
            boolean busy = !a.plan.idle() || a.mind.post != null || a.getMode() == com.jrpetty.mcassistant.entity.AssistantEntity.Mode.STAY;
            if (busy) continue;
            PaLife.bringTo(a, p);
        }
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        var server = event.getServer();
        if (server.getTickCount() % 20 != 0) return;
        PaLife.watch(server);
        if (server.getTickCount() % 600 == 0) {
            for (ServerLevel level : server.getAllLevels()) {
                for (Entity e : level.getAllEntities()) {
                    if (e instanceof PlayerAssistantEntity a && a.isAlive()) PaLife.keepCopy(a);
                }
            }
        }
    }
}
