package com.jrpetty.mcassistant.net;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.TimeLapse;
import com.jrpetty.mcassistant.TimeSpeed;
import com.jrpetty.mcassistant.entity.Favourites;
import com.jrpetty.mcassistant.entity.Happenings;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import com.jrpetty.mcassistant.entity.Villages;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * [vision27] The wire for the camera screen (client/CameraScreen): it asks, the server answers that player alone. What
 * it asks is cheap (a look round for folk within a hundred blocks, at most), and only when the player presses a button.
 */
@EventBusSubscriber(modid = McAssistantMod.MODID, bus = EventBusSubscriber.Bus.MOD)
public final class CameraNetwork {

    private CameraNetwork() {}

    /** How many folk near the player the follow list offers (the player's favourites in reach come first). */
    static final int NEAR = 10;

    @SubscribeEvent
    public static void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar("1");
        registrar.playToServer(CameraAskPayload.TYPE, CameraAskPayload.STREAM_CODEC, CameraNetwork::handleAsk);
        registrar.playToClient(CameraPayload.TYPE, CameraPayload.STREAM_CODEC, CameraNetwork::handleAnswer);
    }

    private static void handleAsk(CameraAskPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            CompoundTag out = answer(player, payload);
            if (out != null) send(player, out);
        });
    }

    private static void send(ServerPlayer player, CompoundTag t) {
        try {
            if (player.connection != null && player.connection.hasChannel(CameraPayload.TYPE)) PacketDistributor.sendToPlayer(player, new CameraPayload(t));
        } catch (RuntimeException ignored) {
            // A connection going away mid-send.
        }
    }

    /** The answer to an ask (public for the tests), or null for nothing to say. */
    @Nullable
    public static CompoundTag answer(ServerPlayer player, CameraAskPayload p) {
        if (!(player.level() instanceof ServerLevel level)) return null;
        CompoundTag t = new CompoundTag();
        t.putString("kind", p.action());
        switch (p.action()) {
            case "info" -> info(level, player, t);
            case "happening" -> {
                Happenings.Happening h = Happenings.pick(level, player.blockPosition(), player.getUUID());
                t.putBoolean("ok", h != null);
                if (h == null) {
                    t.putString("msg", "Nothing much going on near here just now: try again in a little while, or by day.");
                } else {
                    put(t, h);
                }
            }
            case "timelapse" -> {
                String no = TimeLapse.start(player, p.a(), p.b());
                t.putBoolean("ok", no == null);
                t.putString("msg", no == null ? "Time-lapse: " + TimeSpeed.factor(player.server) + "× for " + TimeLapse.secondsLeft() + " seconds." : no);
                if (no == null) {
                    // The camera goes to a building going up, if there is one near: [life28] the one the time-lapse raises first.
                    net.minecraft.core.BlockPos lapse = com.jrpetty.mcassistant.entity.LapseBuild.site();
                    if (lapse != null) {
                        CompoundTag at = new CompoundTag();
                        at.putDouble("x", lapse.getX() + 0.5);
                        at.putDouble("y", lapse.getY());
                        at.putDouble("z", lapse.getZ() + 0.5);
                        at.putInt("focus", -1);
                        at.putString("label", com.jrpetty.mcassistant.entity.LapseBuild.label());
                        t.put("site", at);
                    }
                    if (lapse == null) for (Happenings.Happening h : Happenings.around(level, player.blockPosition(), Happenings.RANGE, null)) {
                        if (h.kind().equals("build")) { CompoundTag at = new CompoundTag(); put(at, h); t.put("site", at); break; }
                    }
                }
            }
            case "stop" -> {
                if (TimeLapse.running() && TimeSpeed.mayChange(player)) TimeLapse.stop(player.server);
                t.putBoolean("ok", true);
            }
            default -> { return null; }
        }
        return t;
    }

    private static void put(CompoundTag t, Happenings.Happening h) {
        t.putString("what", h.kind());
        t.putString("label", h.label());
        t.putDouble("x", h.at().x);
        t.putDouble("y", h.at().y);
        t.putDouble("z", h.at().z);
        t.putInt("focus", h.focus());
        t.putInt("folk", h.folk());
    }

    private static void info(ServerLevel level, ServerPlayer player, CompoundTag t) {
        Villages.Village v = Villages.nearest(level, player.blockPosition(), 256);
        if (v != null) {
            BlockPos c = v.centre();
            t.putIntArray("heart", new int[] {c.getX(), c.getY(), c.getZ()});
            t.putString("town", Villages.name(v.id()));
        }
        t.putBoolean("mayLapse", TimeSpeed.mayChange(player));
        t.putBoolean("lapsing", TimeLapse.running());
        // Who to follow: the player's favourites in reach first, then the folk nearest it.
        ListTag who = new ListTag();
        List<UUID> favs = Favourites.list(player.getUUID());
        List<Integer> seen = new ArrayList<>();
        for (UUID id : favs) {
            Entity e = level.getEntity(id);
            if (e instanceof VillageFolkEntity f && f.isAlive() && f.distanceToSqr(player) < 160 * 160) {
                who.add(folk(f, true));
                seen.add(f.getId());
            }
        }
        List<VillageFolkEntity> near = level.getEntitiesOfClass(VillageFolkEntity.class, new AABB(player.blockPosition()).inflate(64, 32, 64),
            f -> f.isAlive() && !f.isInvisible());
        near.sort(Comparator.comparingDouble(f -> f.distanceToSqr(player)));
        int n = 0;
        for (VillageFolkEntity f : near) {
            if (n >= NEAR) break;
            if (seen.contains(f.getId())) continue;
            who.add(folk(f, false));
            n++;
        }
        t.put("folk", who);
    }

    private static CompoundTag folk(VillageFolkEntity f, boolean fav) {
        CompoundTag c = new CompoundTag();
        c.putInt("id", f.getId());
        c.putString("name", f.displayNameCap());
        c.putString("job", f.stationTask().label);
        c.putBoolean("fav", fav);
        return c;
    }

    private static void handleAnswer(CameraPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> com.jrpetty.mcassistant.client.CameraClient.receive(payload.data()));
    }
}
