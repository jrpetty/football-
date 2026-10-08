package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.McAssistantMod;
import com.mojang.authlib.GameProfile;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.event.entity.ProjectileImpactEvent;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.phys.EntityHitResult;

import java.util.UUID;

/**
 * [nether] What a Nether runner strikes in the Nether is struck as a player strikes it, for its drops.
 *
 * <p>The game's own loot tables give a blaze's rods, and a wither skeleton's skull, only to a kill a player made (their
 * "killed by player" rule): a blaze shot down by anything else drops nothing worth the fight. The runners do in the
 * Nether exactly the work a player would, with a player's bow and arrows, so a monster a runner hurts there is marked as
 * hurt by a player (the game's own stand-in for one: a FakePlayer named for the runners) for as long as the game keeps
 * that mark (five seconds). What then drops is the monster's own loot, rolled by its own table, never made up: half the
 * blazes still give no rod, as for a player.
 */
@EventBusSubscriber(modid = McAssistantMod.MODID)
public final class NetherEvents {

    private NetherEvents() {}

    /** The runners' stand-in, as the game names a fake player: one for every town (it is never in the world). */
    static final GameProfile RUNNERS = new GameProfile(UUID.nameUUIDFromBytes("mca-nether-runners".getBytes()), "[Nether runners]");

    /**
     * A runner's arrow passes by one of its own town (a teammate between it and the blaze, a guest along): the team
     * shoots past each other at the blazes and the ghasts, as players who know where the others stand.
     */
    @SubscribeEvent
    public static void onImpact(ProjectileImpactEvent event) {
        if (!(event.getRayTraceResult() instanceof EntityHitResult hit)) return;
        if (!(event.getProjectile() instanceof AbstractArrow arrow) || !(arrow.getOwner() instanceof VillageFolkEntity f)) return;
        if (f.stationTask() != AssistantEntity.StationTask.NETHER && !NetherRuns.on(f)) return;
        Entity struck = hit.getEntity();
        boolean ours = struck instanceof VillageFolkEntity o && o.ownerId() != null && o.ownerId().equals(f.ownerId())
            || struck instanceof Player p && f.level() instanceof ServerLevel sl && NetherRuns.runOf(f) != null
            && NetherGuests.with(sl, NetherRuns.runOf(f)) == p;
        if (ours) event.setCanceled(true);
    }

    @SubscribeEvent
    public static void onDamage(LivingDamageEvent.Post event) {
        LivingEntity hurt = event.getEntity();
        if (!(hurt.level() instanceof ServerLevel level) || level.dimension() != Level.NETHER || !(hurt instanceof Enemy)) return;
        if (!(event.getSource().getEntity() instanceof VillageFolkEntity f)) return;
        if (f.stationTask() != AssistantEntity.StationTask.NETHER && !NetherRuns.on(f)) return;
        hurt.setLastHurtByPlayer(FakePlayerFactory.get(level, RUNNERS));
    }
}
