package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.McAssistantMod;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingEntityUseItemEvent;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [wpe] Restraint: handcuffs (the watch's) or a rope (a town with no smith's irons yet), and the stocks. A restrained
 * player is slowed to a shuffle, cannot sprint, strike, use an item, break or place a block; it can still talk to the folk
 * (to give itself up, to ask for a lawyer). Led, it is walked on a lead behind whoever holds it, pulled along if it lags,
 * and visibly so: every client draws the irons on its wrists, its arms held before it, and the lead to the guard's hand
 * (client/JusticeClient). A folk prisoner on the watch's lead (WatchHouse) wears the irons too. Taken off at the cell door,
 * at release, or when the court frees it. Kept while the server runs; a prisoner's custody (Jail) puts it back on a
 * restart.
 */
@EventBusSubscriber(modid = McAssistantMod.MODID)
public final class Restraint {

    private Restraint() {}

    public enum Kind { CUFFS, ROPE, STOCKS }

    /** Who is held: its entity, who leads it (null: nobody), how, and a spot it is pulled to instead (a cell, the dock). */
    static final class Hold {
        final UUID who;
        int entity;
        @Nullable UUID leader;
        Kind kind;
        @Nullable BlockPos pullTo;
        long since;
        /** Where its leader has walked lately: the prisoner follows the leader's own path, round corners and through doors. */
        final java.util.ArrayDeque<Vec3> trail = new java.util.ArrayDeque<>();

        Hold(UUID who, int entity, @Nullable UUID leader, Kind kind, long since) {
            this.who = who;
            this.entity = entity;
            this.leader = leader;
            this.kind = kind;
            this.since = since;
        }
    }

    private static final Map<UUID, Hold> HELD = new ConcurrentHashMap<>();
    static final ResourceLocation SLOW = ResourceLocation.fromNamespaceAndPath(McAssistantMod.MODID, "restrained");
    /** How far behind its leader a led prisoner walks before the lead pulls, and past which it has broken away. */
    static final double SLACK = 2.4, SNAP = 14.0;
    private static volatile boolean dirty;

    public static void resetForTests() {
        HELD.clear();
        dirty = true;
    }

    // ------------------------------------------------------------------ on and off

    /** Restrain: the irons (or rope, or the stocks) on, led by {@code leader} if given. */
    public static void on(LivingEntity e, @Nullable Entity leader, Kind kind) {
        Hold h = HELD.get(e.getUUID());
        if (h == null) {
            h = new Hold(e.getUUID(), e.getId(), leader == null ? null : leader.getUUID(), kind, e.level().getGameTime());
            HELD.put(e.getUUID(), h);
        } else {
            h.entity = e.getId();
            h.leader = leader == null ? null : leader.getUUID();
            h.kind = kind;
        }
        h.pullTo = null;
        h.trail.clear();
        if (e instanceof Player p) {
            slow(p, true);
            p.setSprinting(false);
        }
        dirty = true;
    }

    /** Off: the irons taken off, the lead let go. */
    public static void off(LivingEntity e) {
        if (HELD.remove(e.getUUID()) == null) return;
        if (e instanceof Player p) slow(p, false);
        dirty = true;
    }

    public static boolean restrained(@Nullable Entity e) {
        return e != null && HELD.containsKey(e.getUUID());
    }

    public static boolean restrained(UUID id) {
        return HELD.containsKey(id);
    }

    @Nullable
    public static Kind kind(UUID id) {
        Hold h = HELD.get(id);
        return h == null ? null : h.kind;
    }

    /** Who leads it, or null. */
    @Nullable
    public static UUID leader(UUID id) {
        Hold h = HELD.get(id);
        return h == null ? null : h.leader;
    }

    /** Led by another now (or nobody, with null). */
    public static void lead(LivingEntity e, @Nullable Entity leader) {
        Hold h = HELD.get(e.getUUID());
        if (h == null) return;
        h.leader = leader == null ? null : leader.getUUID();
        h.pullTo = null;
        h.trail.clear();
        dirty = true;
    }

    /** Walked to a spot (into a cell, into the dock) rather than after its leader; null to follow the leader again. */
    public static void pullTo(LivingEntity e, @Nullable BlockPos at) {
        Hold h = HELD.get(e.getUUID());
        if (h != null) h.pullTo = at == null ? null : at.immutable();
    }

    private static void slow(Player p, boolean on) {
        AttributeInstance a = p.getAttribute(Attributes.MOVEMENT_SPEED);
        if (a == null) return;
        a.removeModifier(SLOW);
        if (on) a.addTransientModifier(new AttributeModifier(SLOW, -0.55, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
    }

    // ------------------------------------------------------------------ what a restrained player cannot do

    private static boolean held(@Nullable Player p) {
        return p != null && (!p.isCreative() || Justice.creativeUnderLawForTests) && !p.isSpectator() && HELD.containsKey(p.getUUID());
    }

    private static void refuse(Player p, String why) {
        if (p instanceof ServerPlayer sp && sp.tickCount % 20 == 0) sp.displayClientMessage(Component.literal(why), true);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onAttack(AttackEntityEvent e) {
        if (!held(e.getEntity())) return;
        e.setCanceled(true);
        refuse(e.getEntity(), "Your hands are bound: you can't strike anyone.");
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onUseItem(PlayerInteractEvent.RightClickItem e) {
        if (!held(e.getEntity())) return;
        e.setCanceled(true);
        refuse(e.getEntity(), "Your hands are bound: you can't use that.");
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onUseBlock(PlayerInteractEvent.RightClickBlock e) {
        if (!held(e.getEntity())) return;
        e.setCanceled(true);
        refuse(e.getEntity(), "Your hands are bound.");
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onHit(PlayerInteractEvent.LeftClickBlock e) {
        if (!held(e.getEntity())) return;
        e.setCanceled(true);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onInteract(PlayerInteractEvent.EntityInteract e) {
        if (!held(e.getEntity()) || e.getTarget() instanceof VillageFolkEntity) return;   // the folk it may still talk to
        e.setCanceled(true);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onBreak(BlockEvent.BreakEvent e) {
        if (held(e.getPlayer())) e.setCanceled(true);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onPlace(BlockEvent.EntityPlaceEvent e) {
        if (e.getEntity() instanceof Player p && held(p)) e.setCanceled(true);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onStartUsing(LivingEntityUseItemEvent.Start e) {
        if (e.getEntity() instanceof Player p && held(p)) e.setCanceled(true);
    }

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent e) {
        dirty = true;
        if (e.getEntity() instanceof Player p) {
            Hold h = HELD.get(p.getUUID());
            if (h != null) {
                h.entity = p.getId();
                slow(p, true);
            } else slow(p, false);                                         // a modifier left over from before a restart
        }
    }

    @SubscribeEvent
    public static void onRespawn(PlayerEvent.PlayerRespawnEvent e) {
        Hold h = HELD.get(e.getEntity().getUUID());
        if (h != null) {
            h.entity = e.getEntity().getId();
            slow(e.getEntity(), true);
            dirty = true;
        }
    }

    // ------------------------------------------------------------------ the walk on the lead

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post e) {
        if (HELD.isEmpty() && !dirty && e.getServer().getTickCount() % 60 != 0) return;
        com.jrpetty.mcassistant.Guard.run("the restrained", () -> tick(e.getServer()));
    }

    private static void tick(MinecraftServer server) {
        long t = server.getTickCount();
        for (Hold h : HELD.values()) {
            ServerPlayer p = server.getPlayerList().getPlayer(h.who);
            if (p == null) continue;
            if (p.isSprinting()) p.setSprinting(false);
            if (h.entity != p.getId()) { h.entity = p.getId(); dirty = true; }
            ServerLevel level = p.serverLevel();
            Vec3 to = null;
            if (h.pullTo != null) to = Vec3.atBottomCenterOf(h.pullTo);
            else if (h.leader != null) {
                Entity l = level.getEntity(h.leader);
                if (l == null) {
                    ServerPlayer lp = server.getPlayerList().getPlayer(h.leader);
                    l = lp != null && lp.level() == level ? lp : null;
                }
                if (l != null) {
                    double d = l.distanceTo(p);
                    if (d > SNAP) {
                        com.jrpetty.mcassistant.Guard.run("a prisoner broke away", () -> Justice.brokeAway(level, p, h.leader));
                        continue;
                    }
                    Vec3 lp = l.position();
                    if (h.trail.isEmpty() || h.trail.peekLast().distanceToSqr(lp) > 0.8 * 0.8) h.trail.addLast(lp);
                    while (h.trail.size() > 48) h.trail.pollFirst();
                    // The leader's path, from the step nearest the prisoner on: the crumbs it has reached dropped.
                    while (h.trail.size() > 1 && h.trail.peekFirst().distanceToSqr(p.position()) < 1.1 * 1.1) h.trail.pollFirst();
                    if (d > SLACK) to = h.trail.isEmpty() ? lp : h.trail.peekFirst();
                }
            } else h.trail.clear();
            if (to == null || t % 2 != 0) continue;
            Vec3 at = p.position();
            Vec3 dir = new Vec3(to.x - at.x, 0, to.z - at.z);
            double len = dir.length();
            if (len < (h.pullTo != null ? 0.35 : 0.01)) continue;
            double behind = h.pullTo != null ? len : (h.leader == null ? len : Math.max(len, distTo(level, server, h.leader, p) - SLACK + 0.5));
            double speed = h.pullTo != null ? Math.min(0.22, 0.06 + len * 0.08) : Math.min(0.42, 0.12 + behind * 0.09);
            Vec3 v = dir.scale(speed / len);
            double y = p.getDeltaMovement().y;
            if (p.horizontalCollision && p.onGround()) y = 0.42;                 // a step up: over it
            p.setDeltaMovement(v.x, y, v.z);
            p.hurtMarked = true;
        }
        if (dirty || t % 60 == 0) sync(server);
    }

    private static double distTo(ServerLevel level, MinecraftServer server, UUID leader, ServerPlayer p) {
        Entity l = level.getEntity(leader);
        if (l == null) l = server.getPlayerList().getPlayer(leader);
        return l == null || l.level() != level ? 0 : l.distanceTo(p);
    }

    // ------------------------------------------------------------------ to the clients

    /** Every client told who is restrained now, how, and who leads them. */
    static void sync(MinecraftServer server) {
        dirty = false;
        for (ServerLevel level : server.getAllLevels()) {
            if (level.players().isEmpty()) continue;
            ListTag list = new ListTag();
            for (Hold h : HELD.values()) {
                Entity e = level.getEntity(h.who);
                if (e == null) {
                    ServerPlayer p = server.getPlayerList().getPlayer(h.who);
                    if (p == null || p.level() != level) continue;
                    e = p;
                }
                CompoundTag c = new CompoundTag();
                c.putInt("e", e.getId());
                int lead = -1;
                if (h.leader != null && h.pullTo == null) {
                    Entity l = level.getEntity(h.leader);
                    if (l == null) {
                        ServerPlayer lp = server.getPlayerList().getPlayer(h.leader);
                        if (lp != null && lp.level() == level) l = lp;
                    }
                    if (l != null) lead = l.getId();
                }
                c.putInt("l", lead);
                c.putInt("k", h.kind.ordinal());
                list.add(c);
            }
            CompoundTag t = new CompoundTag();
            t.putString("kind", "r");
            t.put("list", list);
            for (ServerPlayer p : level.players()) com.jrpetty.mcassistant.net.JusticeNetwork.send(p, t);
        }
    }
}
