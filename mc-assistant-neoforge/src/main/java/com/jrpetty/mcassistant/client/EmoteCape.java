package com.jrpetty.mcassistant.client;

import com.jrpetty.mcassistant.McAssistantMod;
import com.mojang.brigadier.arguments.BoolArgumentType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;

import java.lang.reflect.Field;
import java.util.function.Supplier;

/**
 * [anim] A test cape on every player this client sees, to look at how a cape hangs and swings through the emotes
 * (EmotePlayerModel): an offline player, a dev's own, has no cape of its own, and the cape had never been seen following
 * a bow, a kneel, a sit or a dance. The game's own cape layer draws it, from the player's skin with a cape put to it, so
 * what is seen is what a player with a real cape gets. Off unless asked for: {@code /emotecape true} (an op's, on its own
 * client only), or AnimDev's file ("cape").
 */
@EventBusSubscriber(modid = McAssistantMod.MODID, value = Dist.CLIENT)
public final class EmoteCape {

    private EmoteCape() {}

    public static final ResourceLocation TEST_CAPE = ResourceLocation.fromNamespaceAndPath(McAssistantMod.MODID, "textures/entity/test_cape.png");

    private static boolean on;

    /** A player's skin, with the test cape put to it while it is on (and the player has none of its own). */
    private record Caped(Supplier<PlayerSkin> own) implements Supplier<PlayerSkin> {
        @Override
        public PlayerSkin get() {
            PlayerSkin k = own.get();
            if (!on || k.capeTexture() != null) return k;
            return new PlayerSkin(k.texture(), k.textureUrl(), TEST_CAPE, k.elytraTexture(), k.model(), k.secure());
        }
    }

    /** The test cape on (or off) for every player this client knows of now. */
    public static int set(boolean wanted) {
        on = wanted;
        Minecraft mc = Minecraft.getInstance();
        if (mc.getConnection() == null) return 0;
        int n = 0;
        try {
            Field f = PlayerInfo.class.getDeclaredField("skinLookup");
            f.setAccessible(true);
            for (PlayerInfo info : mc.getConnection().getOnlinePlayers()) {
                Object was = f.get(info);
                if (was instanceof Caped) { n++; continue; }
                @SuppressWarnings("unchecked") Supplier<PlayerSkin> own = (Supplier<PlayerSkin>) was;
                f.set(info, new Caped(own));
                n++;
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            com.mojang.logging.LogUtils.getLogger().warn("[anim] the test cape could not be put on: {}", e.toString());
        }
        return n;
    }

    public static boolean on() { return on; }

    @SubscribeEvent
    public static void onClientCommands(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("emotecape").requires(src -> src.hasPermission(2))
            .executes(ctx -> {
                ctx.getSource().sendSuccess(() -> Component.literal("Test cape " + (on ? "on" : "off")), false);
                return on ? 1 : 0;
            })
            .then(Commands.argument("on", BoolArgumentType.bool()).executes(ctx -> {
                boolean b = BoolArgumentType.getBool(ctx, "on");
                int n = set(b);
                ctx.getSource().sendSuccess(() -> Component.literal("Test cape " + (b ? "on" : "off") + " (" + n + " players)"), false);
                return n;
            })));
    }
}
