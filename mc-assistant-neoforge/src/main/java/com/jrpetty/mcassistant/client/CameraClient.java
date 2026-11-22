package com.jrpetty.mcassistant.client;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.net.CameraAskPayload;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

import javax.annotation.Nullable;

/**
 * [vision27] The way into the camera (CameraScreen): its key (K, rebindable) and the button on the U page, and the
 * server's answers to what the screen asks (CameraNetwork): the town's heart and who to follow, somewhere something is
 * happening, a time-lapse begun.
 */
public final class CameraClient {

    private CameraClient() {}

    public static final KeyMapping OPEN = new KeyMapping("key.mc_assistant.camera", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_K,
        "key.categories.mc_assistant");

    /** The last word from the server about the town (CameraNetwork's "info"), or null before the first. */
    @Nullable static CompoundTag info;

    @EventBusSubscriber(modid = McAssistantMod.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
    public static final class Keys {
        private Keys() {}

        @SubscribeEvent
        public static void onRegisterKeys(RegisterKeyMappingsEvent event) {
            event.register(OPEN);
        }
    }

    @EventBusSubscriber(modid = McAssistantMod.MODID, value = Dist.CLIENT)
    public static final class Events {
        private Events() {}

        @SubscribeEvent
        public static void onClientTick(ClientTickEvent.Post event) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null || mc.getConnection() == null) return;
            while (OPEN.consumeClick()) if (mc.screen == null) CameraScreen.open();
        }
    }

    public static void ask(String action, int a, int b) {
        try {
            PacketDistributor.sendToServer(new CameraAskPayload(action, a, b));
        } catch (RuntimeException e) {
            CinemaCam.say("This server does not have the camera's other half.");
        }
    }

    /** What the server said. */
    public static void receive(CompoundTag t) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        String kind = t.getString("kind");
        CameraScreen open = mc.screen instanceof CameraScreen s ? s : null;
        switch (kind) {
            case "info" -> {
                info = t;
                if (open != null) open.refresh();
            }
            case "happening" -> {
                if (!t.getBoolean("ok")) {
                    if (open != null) open.message(t.getString("msg"), false); else CinemaCam.say(t.getString("msg"));
                    return;
                }
                if (open != null) mc.setScreen(null);
                CinemaCam.radius = 13.0F;
                CinemaCam.height = 6.0F;
                CinemaCam.speed = 6.0F;
                CinemaCam.orbit(new Vec3(t.getDouble("x"), t.getDouble("y"), t.getDouble("z")), t.getInt("focus"), t.getString("label"));
            }
            case "timelapse" -> {
                if (!t.getBoolean("ok")) {
                    if (open != null) open.message(t.getString("msg"), false); else CinemaCam.say(t.getString("msg"));
                    return;
                }
                if (open != null) mc.setScreen(null);
                Vec3 at = null;
                int focus = -1;
                String label = t.getString("msg");
                if (t.contains("site")) {
                    CompoundTag s = t.getCompound("site");
                    at = new Vec3(s.getDouble("x"), s.getDouble("y"), s.getDouble("z"));
                    focus = s.getInt("focus");
                    label = s.getString("label") + ": " + label;
                } else if (heart() != null) {
                    at = heart();
                }
                if (at != null) {
                    CinemaCam.radius = 18.0F;
                    CinemaCam.height = 10.0F;
                    CinemaCam.speed = 4.0F;
                    // The builders come and go: keep to the building, not to one of them.
                    CinemaCam.orbit(at, focus >= 0 && t.contains("site") ? -1 : focus, label);
                } else {
                    CinemaCam.say(label);
                }
            }
            default -> { }
        }
    }

    /** The town's heart, from the last info, or null. */
    @Nullable
    static Vec3 heart() {
        if (info == null || !info.contains("heart")) return null;
        int[] h = info.getIntArray("heart");
        return h.length < 3 ? null : new Vec3(h[0] + 0.5, h[1], h[2] + 0.5);
    }
}
