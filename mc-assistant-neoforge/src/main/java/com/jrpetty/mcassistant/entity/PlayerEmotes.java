package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.entity.FolkAction.Act;
import com.jrpetty.mcassistant.net.PlayerEmotePayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import javax.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [anim] The player's emotes, on the server: a wave, a bow, a cheer, a clap, a point, a beckon, a shrug, a salute, a
 * handshake, a dance, a sit, a kneel, a prayer, a think (EmoteRules.EMOTES). The client asks (net/EmotePayload, from the
 * emote wheel or the talk screen); here it is checked (an emote, a living player in the world, one a second and five in
 * ten seconds at most) and sent to everybody who can see the player, who pose its model from it (client/PlayerEmotesClient).
 *
 * <p>And the folk see it. Those within twelve blocks who can see the player answer in kind, by what they think of it
 * (EmoteRules.answer): a wave back from those who like it, a bow back to their leader or a knight of their town, a cheer
 * along at a gathering, a dance joined at a feast, a salute from the watch. A beckon is followed by its hired folk and
 * close friends; its hired folk go where it points. A handshake is offered to the folk it is looking at, which takes it
 * (both play it together) and thinks a little better of the player for it, once a day; one that cannot stand the player
 * shrugs it off. Each answers a moment after the emote, at its own pace, turned to the player.
 */
@EventBusSubscriber(modid = McAssistantMod.MODID)
public final class PlayerEmotes {

    private PlayerEmotes() {}

    public enum Result { OK, NOT_AN_EMOTE, TOO_SOON, NOT_NOW }

    /** Each player's emotes lately (game time), for the rate limit. */
    private static final Map<UUID, Deque<Long>> RECENT = new ConcurrentHashMap<>();
    /** Each player's emote now, and when it began (for the tests and /village emote). */
    private static final Map<UUID, long[]> NOW = new ConcurrentHashMap<>();
    /** A folk and a player who shook hands, and the day (the opinion goes up once a day). */
    private static final Map<String, Long> SHOOK = new ConcurrentHashMap<>();
    /** Folk answers waiting their moment. */
    private record Answer(ServerLevel level, UUID folk, UUID player, Act act, long at) {}
    private static final List<Answer> WAITING = new ArrayList<>();

    /** How far off a folk sees an emote. */
    static final double SEEN = 12.0;
    /** How many folk answer one at most (the nearest). */
    static final int ANSWERING = 8;

    public static void resetForTests() {
        RECENT.clear();
        NOW.clear();
        SHOOK.clear();
        synchronized (WAITING) { WAITING.clear(); }
    }

    /** From the client (or /village emote): an emote, checked, shown to everybody near, and answered by the folk. */
    public static Result emote(ServerPlayer p, int actId, int target) {
        Act a = Act.of(actId);
        if (!EmoteRules.isEmote(a)) return Result.NOT_AN_EMOTE;
        if (!p.isAlive() || p.isSpectator() || p.isSleeping() || !(p.level() instanceof ServerLevel level)) return Result.NOT_NOW;
        long now = level.getGameTime();
        Deque<Long> recent = RECENT.computeIfAbsent(p.getUUID(), k -> new ArrayDeque<>());
        while (!recent.isEmpty() && now - recent.peekFirst() > EmoteRules.BURST_WINDOW) recent.pollFirst();
        if (!recent.isEmpty() && now - recent.peekLast() < EmoteRules.GAP || recent.size() >= EmoteRules.BURST) return Result.TOO_SOON;
        recent.addLast(now);
        show(p, a);
        answer(level, p, a, target);
        return Result.OK;
    }

    /** The player's model posed for it, on every client that can see the player and its own. */
    static void show(ServerPlayer p, Act a) {
        NOW.put(p.getUUID(), new long[]{a.ordinal(), p.level().getGameTime()});
        try {
            PacketDistributor.sendToPlayersTrackingEntityAndSelf(p, new PlayerEmotePayload(p.getId(), a.ordinal(), EmoteRules.ticks(a)));
        } catch (RuntimeException e) {
            com.mojang.logging.LogUtils.getLogger().warn("[anim] an emote not sent: {}", e.toString());   // a client without the mod
        }
    }

    /** A handshake on a bargain struck (a folk hired, a deal closed): the player and the folk play it together. */
    public static void shake(ServerPlayer p, VillageFolkEntity f) {
        show(p, Act.HANDSHAKE);
        face(f, p);
        FolkAction.play(f, Act.HANDSHAKE, 40);
        FolkAction.mark(f, FolkAction.Mark.HANDSHAKE);
        remember(f, p);
    }

    /** The player's emote now, if it is still playing (for the tests): NONE otherwise. */
    public static Act now(ServerPlayer p) {
        long[] n = NOW.get(p.getUUID());
        if (n == null) return Act.NONE;
        Act a = Act.of((int) n[0]);
        return p.level().getGameTime() - n[1] <= EmoteRules.ticks(a) ? a : Act.NONE;
    }

    // ------------------------------------------------------------------ the folk answer

    static EmoteRules.Who who(VillageFolkEntity f, ServerPlayer p, boolean faced) {
        UUID town = f.ownerId(), me = p.getUUID();
        boolean honoured = town != null && (PlayerLeader.leads(town, me) || Knights.isKnight(town, me));
        return new EmoteRules.Who(f.persona().affinity(me), me.equals(f.hiredBy()), honoured,
            f.stationTask() == AssistantEntity.StationTask.GUARD, Assemblies.attending(f), faced);
    }

    private static void answer(ServerLevel level, ServerPlayer p, Act a, int targetId) {
        VillageFolkEntity target = target(level, p, targetId);
        List<VillageFolkEntity> near = level.getEntitiesOfClass(VillageFolkEntity.class, p.getBoundingBox().inflate(SEEN),
            f -> f.isAlive() && !f.isSleeping() && !(f.isShowcase() && FolkAction.now(f) != Act.NONE) && f.getTarget() == null
                && f.hasLineOfSight(p));
        near.sort(Comparator.comparingDouble(f -> f.distanceToSqr(p)));
        int answered = 0;
        for (VillageFolkEntity f : near) {
            if (answered >= ANSWERING) break;
            boolean faced = f == target;
            EmoteRules.Who w = who(f, p, faced);
            Act back = EmoteRules.answer(a, w);
            if (EmoteRules.follows(a, w)) {
                f.pointedTo = null;                            // called back from where it was sent
                if (!f.isFollowing(p)) f.startFollowing(p);
            }
            if (EmoteRules.goesWherePointed(a, w)) walkToPointed(level, p, f);
            if (back == Act.NONE) {
                if ((a == Act.WAVE || a == Act.BOW) && w.affinity() <= -40) FolkAction.mark(f, FolkAction.Mark.ANGER);
                continue;
            }
            if (back == Act.HANDSHAKE) {
                face(f, p);
                FolkAction.play(f, Act.HANDSHAKE, 40);         // hand in hand with the player's, at once
                FolkAction.mark(f, FolkAction.Mark.HANDSHAKE);
                remember(f, p);
            } else {
                long delay = 4 + f.getRandom().nextInt(10) + answered * 2L;
                synchronized (WAITING) { WAITING.add(new Answer(level, f.getUUID(), p.getUUID(), back, level.getGameTime() + delay)); }
            }
            answered++;
        }
    }

    /** The folk the player means: the one it named (looked at, within four blocks), or the nearest one in front of it. */
    @Nullable
    static VillageFolkEntity target(ServerLevel level, ServerPlayer p, int targetId) {
        if (targetId >= 0 && level.getEntity(targetId) instanceof VillageFolkEntity f && f.distanceToSqr(p) < 4.5 * 4.5) return f;
        Vec3 look = p.getLookAngle();
        VillageFolkEntity best = null;
        double near = 4.0 * 4.0;
        for (VillageFolkEntity f : level.getEntitiesOfClass(VillageFolkEntity.class, p.getBoundingBox().inflate(4.0))) {
            Vec3 to = f.position().add(0, f.getBbHeight() * 0.6, 0).subtract(p.getEyePosition());
            double d = to.lengthSqr();
            if (d < near && to.normalize().dot(look) > 0.75) { near = d; best = f; }
        }
        return best;
    }

    /** Thinks a little better of the player for the handshake: once a day. */
    private static void remember(VillageFolkEntity f, ServerPlayer p) {
        long day = f.level().getDayTime() / 24000L;
        String key = f.getUUID() + "/" + p.getUUID();
        Long last = SHOOK.get(key);
        if (last != null && last == day) return;
        SHOOK.put(key, day);
        if (SHOOK.size() > 8192) SHOOK.clear();
        f.persona().feelFor(p.getUUID(), p.getName().getString(), 2);
        f.persona().remember(day, "I shook hands with " + p.getName().getString(), 1);
    }

    private static void face(VillageFolkEntity f, ServerPlayer p) {
        double dx = p.getX() - f.getX(), dz = p.getZ() - f.getZ();
        float yaw = (float) (Math.atan2(dz, dx) * 180.0 / Math.PI) - 90.0F;
        f.setYBodyRot(yaw);
        f.setYHeadRot(yaw);
        f.getLookControl().setLookAt(p, 30.0F, 30.0F);
        f.getNavigation().stop();
    }

    /** Its hired folk to the spot the player points at (a block it is looking at, within thirty-two). */
    private static void walkToPointed(ServerLevel level, ServerPlayer p, VillageFolkEntity f) {
        Vec3 eye = p.getEyePosition();
        Vec3 end = eye.add(p.getLookAngle().scale(32.0));
        BlockHitResult hit = level.clip(new ClipContext(eye, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p));
        if (hit.getType() != HitResult.Type.BLOCK) return;
        net.minecraft.core.BlockPos to = hit.getBlockPos().relative(hit.getDirection());
        f.pointedTo = to.immutable();
        f.pointedUntil = f.tickCount + 600;
        f.getNavigation().moveTo(to.getX() + 0.5, to.getY(), to.getZ() + 0.5, 0.7);
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        synchronized (WAITING) {
            if (WAITING.isEmpty()) return;
            Iterator<Answer> it = WAITING.iterator();
            while (it.hasNext()) {
                Answer w = it.next();
                if (w.level().getGameTime() < w.at()) continue;
                it.remove();
                if (!(w.level().getEntity(w.folk()) instanceof VillageFolkEntity f) || !f.isAlive()) continue;
                net.minecraft.world.entity.player.Player p = w.level().getPlayerByUUID(w.player());
                if (p instanceof ServerPlayer sp) face(f, sp);
                FolkAction.play(f, w.act(), Math.min(120, EmoteRules.ticks(w.act())));   // an answer, not the rest of its day
            }
        }
    }

    /** For the tests: the folk answers waiting their moment. */
    public static int waitingForTests() {
        synchronized (WAITING) { return WAITING.size(); }
    }
}
