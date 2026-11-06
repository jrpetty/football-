package com.jrpetty.mcassistant.client;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.net.EarnActionPayload;
import com.jrpetty.mcassistant.net.EarnPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import javax.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * [earning] Earning on the player's side: what the server last said (the Earnings page, the shift HUD, the toasts of
 * each payment) and the marker the player set on the page. The HUD and the toasts are drawn by ShiftHud; the sparks
 * on a shift's target and on the marker are spawned here, a few times a second, nothing when there is none.
 */
@EventBusSubscriber(modid = McAssistantMod.MODID, value = Dist.CLIENT)
public final class EarnClient {

    private EarnClient() {}

    /** The Earnings page as last sent, and when it came (client ticks). */
    static CompoundTag page = new CompoundTag();
    static long pageAt = -1000;
    /** The shift HUD as last sent, and the client tick it came on (the clock runs on from there). */
    @Nullable static CompoundTag hud;
    static long hudAt;
    /** The end of a shift's summary card, until when (client ticks). */
    @Nullable static CompoundTag summary;
    static long summaryUntil;

    /** A payment told in the corner: its words, and until when. */
    record Toast(String text, int coins, long until) {}

    static final Deque<Toast> TOASTS = new ArrayDeque<>();
    static final int TOAST_TICKS = 80, MOST_TOASTS = 3, SUMMARY_TICKS = 300;

    /** The marker set from the Earnings page: where, and what it is. */
    @Nullable static int[] marker;
    static String markerLabel = "";

    static long ticks;

    /** From the server. */
    public static void heard(int kind, CompoundTag t) {
        switch (kind) {
            case EarnPayload.PAGE -> {
                page = t;
                pageAt = ticks;
            }
            case EarnPayload.TOAST -> {
                TOASTS.addFirst(new Toast(t.getString("text"), t.getInt("coins"), ticks + TOAST_TICKS));
                while (TOASTS.size() > MOST_TOASTS) TOASTS.removeLast();
            }
            case EarnPayload.HUD -> {
                if (t.getBoolean("active")) {
                    hud = t;
                    hudAt = ticks;
                } else {
                    hud = null;
                    if (t.contains("summary")) {
                        summary = t.getCompound("summary");
                        summaryUntil = ticks + SUMMARY_TICKS;
                    }
                }
                ShiftHud.dirty();
            }
            default -> { }
        }
    }

    /** A button on the page (or the HUD) to the server. */
    static void ask(String action, CompoundTag args) {
        if (Minecraft.getInstance().getConnection() == null) return;
        PacketDistributor.sendToServer(new EarnActionPayload(action, args));
    }

    /** The page asked for again (when it is opened, or after a button). */
    static void askPage() {
        ask("page", new CompoundTag());
    }

    /** Is the page older than a few seconds? */
    static boolean stale() {
        return ticks - pageAt > 100;
    }

    /** A marker on this place (null clears it). */
    static void mark(@Nullable int[] at, String label) {
        marker = at == null || at.length < 3 ? null : at.clone();
        markerLabel = label;
    }

    /** The server's game time now, reckoned from the HUD's last word. */
    static long serverNow() {
        return hud == null ? 0 : hud.getLong("now") + (ticks - hudAt);
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        ticks++;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;
        while (!TOASTS.isEmpty() && TOASTS.peekLast().until() < ticks) TOASTS.removeLast();
        if (summary != null && ticks > summaryUntil) summary = null;
        if (ticks % 5 != 0) return;
        if (marker != null) {
            double dx = marker[0] + 0.5 - mc.player.getX(), dz = marker[2] + 0.5 - mc.player.getZ();
            if (dx * dx + dz * dz < 16) {
                marker = null;                                     // arrived
            } else {
                for (int i = 0; i < 4; i++) mc.level.addParticle(ParticleTypes.END_ROD, marker[0] + 0.5, marker[1] + 1.2 + i * 0.8, marker[2] + 0.5, 0, 0.01, 0);
            }
        }
        CompoundTag h = hud;
        if (h == null) return;
        if (h.contains("target")) {
            int[] t = h.getIntArray("target");
            if (t.length == 3) mc.level.addParticle(ParticleTypes.HAPPY_VILLAGER, t[0] + 0.5, t[1] + 1.15, t[2] + 0.5, 0, 0.02, 0);
        }
        if (h.contains("marks")) {
            int[] m = h.getIntArray("marks");
            for (int i = 0; i + 2 < m.length; i += 3) mc.level.addParticle(ParticleTypes.COMPOSTER, m[i] + 0.5, m[i + 1] + 0.6, m[i + 2] + 0.5, 0, 0.02, 0);
        }
        if (h.contains("entity")) {
            Entity e = mc.level.getEntity(h.getInt("entity"));
            if (e != null) mc.level.addParticle(ParticleTypes.GLOW, e.getX(), e.getY() + e.getBbHeight() + 0.4, e.getZ(), 0, 0.02, 0);
        }
    }

    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        page = new CompoundTag();
        hud = null;
        summary = null;
        marker = null;
        TOASTS.clear();
    }
}
