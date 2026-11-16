package com.jrpetty.mcassistant.client;

import com.jrpetty.mcassistant.AnimConfig;
import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.entity.EmoteRules;
import com.jrpetty.mcassistant.entity.FolkAction;
import com.jrpetty.mcassistant.entity.FolkAction.Act;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.CameraType;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.EntityHitResult;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * [anim] The player's emotes on the client: the key that opens the emote wheel (V; free in the game and in this mod, which
 * has B for its plots), the emote sent to the server, and each player's emote played on its model (EmotePlayerModel) as
 * the server tells of it: the same clips the folk play (FolkAnimations), blended in and out the same way. An emote that
 * goes on (a dance, a sit, a prayer) ends when the player walks off; in first person the camera turns round to watch it
 * (AnimConfig.emoteCamera) and back when it is over.
 */
public final class PlayerEmotesClient {

    private PlayerEmotesClient() {}

    public static final KeyMapping WHEEL = new KeyMapping("key.mc_assistant.emote_wheel", InputConstants.Type.KEYSYM,
        GLFW.GLFW_KEY_V, "key.categories.mc_assistant");

    /** Each player's emote: the clip's clock and when it ends (its own tickCount). */
    static final class Playing {
        final FolkAction.Seen seen = new FolkAction.Seen();
        int until;
    }

    private static final Map<Integer, Playing> PLAYING = new HashMap<>();
    private static final FolkAnimations.Style STYLE = new FolkAnimations.Style();
    /** The camera as it was before an emote turned it round, or null. */
    private static CameraType before;

    /** From the server: a player's emote began (act 0: it stopped). */
    public static void heard(int playerId, int act, int ticks) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || !(mc.level.getEntity(playerId) instanceof Player p)) return;
        Playing pl = PLAYING.computeIfAbsent(playerId, k -> new Playing());
        pl.seen.begin(act, p.tickCount);
        pl.until = p.tickCount + ticks;
        if (p == mc.player && act != 0 && mc.screen == null && AnimConfig.emoteCamera() && mc.options.getCameraType() == CameraType.FIRST_PERSON) {
            before = CameraType.FIRST_PERSON;
            mc.options.setCameraType(CameraType.THIRD_PERSON_FRONT);
        }
    }

    /** The emote a player's model plays now (null: none, nor one fading out). */
    static FolkAction.Seen seen(AbstractClientPlayer p, float age) {
        Playing pl = PLAYING.get(p.getId());
        if (pl == null) return null;
        if (pl.seen.act == 0 && age - pl.seen.changed > 8) return null;
        return pl.seen;
    }

    static FolkAnimations.Style style(AbstractClientPlayer p) {
        STYLE.mirror = p.getMainArm() == net.minecraft.world.entity.HumanoidArm.LEFT;
        STYLE.speed = 1.0F;
        STYLE.phase = 0L;
        STYLE.lean = 1.0F;
        STYLE.bounce = 1.0F;
        STYLE.keepMain = false;
        return STYLE;
    }

    /** Asks the server for an emote, offered to the folk the player is looking at (for a handshake). */
    public static void send(Act a) {
        Minecraft mc = Minecraft.getInstance();
        int target = -1;
        if (mc.hitResult instanceof EntityHitResult eh && eh.getEntity() instanceof com.jrpetty.mcassistant.entity.VillageFolkEntity f) target = f.getId();
        PacketDistributor.sendToServer(new com.jrpetty.mcassistant.net.EmotePayload(a.ordinal(), target));
    }

    @EventBusSubscriber(modid = McAssistantMod.MODID, value = Dist.CLIENT)
    public static final class Ticks {
        private Ticks() {}

        @SubscribeEvent
        public static void onClientTick(ClientTickEvent.Post event) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.level == null) {
                PLAYING.clear();
                return;
            }
            while (WHEEL.consumeClick()) {
                if (mc.screen == null && mc.player != null) mc.setScreen(new EmoteWheelScreen());
            }
            Iterator<Map.Entry<Integer, Playing>> it = PLAYING.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<Integer, Playing> e = it.next();
                Entity ent = mc.level.getEntity(e.getKey());
                Playing pl = e.getValue();
                if (!(ent instanceof Player p)) {
                    it.remove();
                    continue;
                }
                if (pl.seen.act != 0) {
                    boolean moved = p.walkAnimation.speed() > 0.25F || p.isShiftKeyDown() && pl.seen.act != Act.KNEEL.ordinal()
                        || !p.onGround() && !p.isPassenger();
                    if (p.tickCount >= pl.until || moved && p.tickCount - pl.seen.start > 4) pl.seen.begin(0, p.tickCount);
                } else if (p.tickCount - pl.seen.changed > 10) {
                    it.remove();
                }
                if (p == mc.player && pl.seen.act == 0 && before != null && p.tickCount - pl.seen.changed > 6) {
                    mc.options.setCameraType(before);
                    before = null;
                }
            }
        }
    }

    @EventBusSubscriber(modid = McAssistantMod.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
    public static final class Setup {
        private Setup() {}

        @SubscribeEvent
        public static void onRegisterKeys(RegisterKeyMappingsEvent event) {
            event.register(WHEEL);
        }

        /**
         * The players' renderers given a model that plays emotes: the game's own player model, the same parts, with the
         * emote put on after its own pose (EmotePlayerModel). The renderer's model is swapped in once, here, with the
         * layers already added (they read the model through the renderer, so they follow it).
         */
        @SubscribeEvent
        public static void onAddLayers(EntityRenderersEvent.AddLayers event) {
            for (PlayerSkin.Model skin : event.getSkins()) {
                if (!(event.getSkin(skin) instanceof PlayerRenderer r)) continue;
                boolean slim = skin == PlayerSkin.Model.SLIM;
                EmotePlayerModel m = new EmotePlayerModel(event.getEntityModels().bakeLayer(slim ? ModelLayers.PLAYER_SLIM : ModelLayers.PLAYER), slim);
                try {
                    java.lang.reflect.Field f = LivingEntityRenderer.class.getDeclaredField("model");
                    f.setAccessible(true);
                    f.set(r, m);
                } catch (ReflectiveOperationException | RuntimeException e) {
                    com.mojang.logging.LogUtils.getLogger().warn("[anim] the player model could not be given its emotes: {}", e.toString());
                }
            }
        }
    }

    /** Every emote's words for the wheel ("Wave", "Shake hands"). */
    public static String words(Act a) {
        return switch (a) {
            case HANDSHAKE -> "Shake hands";
            case SIT_GROUND -> "Sit";
            case KNEEL -> "Kneel";
            case BECKON -> "Follow me";
            case THINK -> "Think";
            default -> a.word().substring(0, 1).toUpperCase(java.util.Locale.ROOT) + a.word().substring(1);
        };
    }

    static Act[] emotes() { return EmoteRules.EMOTES; }
}
