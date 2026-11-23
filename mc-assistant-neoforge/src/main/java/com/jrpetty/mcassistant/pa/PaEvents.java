package com.jrpetty.mcassistant.pa;

import com.jrpetty.mcassistant.McAssistantMod;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerContainerEvent;
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
            if (p.level().dimension() == net.minecraft.world.level.Level.NETHER) {
                PlayerAssistantEntity there = PaLife.find(p);
                if (there != null) PaBanter.quip(there, "quip.nether", 1, null);   // [pa33b]
            }
        }
    }

    // ------------------------------------------------------------------ [pa33b] its player's moments

    @SubscribeEvent
    public static void onOwnerDeath(net.neoforged.neoforge.event.entity.living.LivingDeathEvent event) {
        if (event.getEntity() instanceof ServerPlayer p) PaBanter.ownerDied(p, event.getSource());
    }

    @SubscribeEvent
    public static void onRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer p) || event.isEndConquered()) return;
        PlayerAssistantEntity a = PaLife.find(p);
        if (a != null) a.mind.respawnQuipAt = a.tickCount + 60;
    }

    /** A fall survived, or a hard knock: a remark (rate-limited). */
    @SubscribeEvent
    public static void onOwnerHurt(net.neoforged.neoforge.event.entity.living.LivingDamageEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer p) || p.isDeadOrDying()) return;
        if (!event.getSource().is(net.minecraft.world.damagesource.DamageTypes.FALL) || event.getNewDamage() < 4) return;
        PlayerAssistantEntity a = PaLife.find(p);
        if (a != null && a.distanceToSqr(p) < 32 * 32) PaBanter.quip(a, "quip.fell", 1, null);
    }

    private static void did(net.minecraft.world.entity.player.Player player, String what) {
        if (!(player instanceof ServerPlayer p)) return;
        PlayerAssistantEntity a = PaLife.find(p);
        if (a != null && a.distanceToSqr(p) < 32 * 32) PaBrain.ownerDid(a, what);
    }

    @SubscribeEvent
    public static void onBreak(net.neoforged.neoforge.event.level.BlockEvent.BreakEvent event) {
        did(event.getPlayer(), "jm.mine");
    }

    @SubscribeEvent
    public static void onPlace(net.neoforged.neoforge.event.level.BlockEvent.EntityPlaceEvent event) {
        if (event.getEntity() instanceof net.minecraft.world.entity.player.Player pl) did(pl, "jm.place");
    }

    @SubscribeEvent
    public static void onJump(net.neoforged.neoforge.event.entity.living.LivingEvent.LivingJumpEvent event) {
        if (event.getEntity() instanceof net.minecraft.world.entity.player.Player pl && !pl.level().isClientSide) did(pl, "jm.jump");
    }

    @SubscribeEvent
    public static void onEat(net.neoforged.neoforge.event.entity.living.LivingEntityUseItemEvent.Finish event) {
        if (event.getEntity() instanceof net.minecraft.world.entity.player.Player pl && event.getItem().has(net.minecraft.core.component.DataComponents.FOOD)) did(pl, "jm.eat");
    }

    @SubscribeEvent
    public static void onOpen(PlayerContainerEvent.Open event) {
        if (event.getContainer() instanceof net.minecraft.world.inventory.ChestMenu) did(event.getEntity(), "jm.chest");
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
