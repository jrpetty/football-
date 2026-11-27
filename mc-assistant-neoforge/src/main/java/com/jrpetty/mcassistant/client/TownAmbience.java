package com.jrpetty.mcassistant.client;

import com.jrpetty.mcassistant.AnimConfig;
import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * [vision27] A town sounds lived in: birds in the trees by day, crickets in the grass at night, heard round wherever the
 * player's eyes are (or the camera's) while they are out of doors in a town of the folk (a few of them in sight), and
 * not in the rain. The client's own, out of its own sounds (assets/mc_assistant/sounds), so nothing is sent; a bird
 * every few seconds somewhere in the trees about, a cricket or two at a time. AnimConfig's "ambience" turns it off.
 */
@EventBusSubscriber(modid = McAssistantMod.MODID, value = Dist.CLIENT)
public final class TownAmbience {

    private TownAmbience() {}

    private static final SoundEvent BIRDS = SoundEvent.createVariableRangeEvent(ResourceLocation.fromNamespaceAndPath(McAssistantMod.MODID, "ambient.birds"));
    private static final SoundEvent CRICKETS = SoundEvent.createVariableRangeEvent(ResourceLocation.fromNamespaceAndPath(McAssistantMod.MODID, "ambient.crickets"));

    private static int every, next = 40;
    private static boolean inTown;
    private static final RandomSource RNG = RandomSource.create();

    @SubscribeEvent
    public static void onTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        Level level = mc.level;
        if (level == null || mc.player == null || mc.isPaused() || !AnimConfig.ambience()) return;
        if (level.dimension() != Level.OVERWORLD) return;
        Vec3 eye = mc.gameRenderer.getMainCamera().getPosition();
        if (++every % 40 == 0) {
            int n = level.getEntitiesOfClass(VillageFolkEntity.class, new AABB(eye, eye).inflate(48.0, 24.0, 48.0)).size();
            inTown = n >= 4;
        }
        if (!inTown || --next > 0) return;
        next = 20;
        if (level.isRaining()) return;
        BlockPos at = BlockPos.containing(eye);
        // Out of doors: the open sky over the eyes (or near enough above the ground about them).
        if (eye.y < level.getHeight(Heightmap.Types.MOTION_BLOCKING, at.getX(), at.getZ()) - 1.0) return;
        long t = level.getDayTime() % 24000L;
        boolean day = t < 11500L || t > 23200L, night = t > 13200L && t < 22600L;
        if (!day && !night) return;
        double a = RNG.nextDouble() * Math.PI * 2.0, r = (day ? 8.0 : 4.0) + RNG.nextDouble() * (day ? 14.0 : 10.0);
        double x = eye.x + Math.cos(a) * r, z = eye.z + Math.sin(a) * r;
        int ground = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, (int) Math.floor(x), (int) Math.floor(z));
        double y = day ? level.getHeight(Heightmap.Types.MOTION_BLOCKING, (int) Math.floor(x), (int) Math.floor(z)) + 0.5 : ground + 0.2;
        if (Math.abs(y - eye.y) > 40.0) y = eye.y;                    // high in the air over the town: about the eyes
        SoundEvent s = day ? BIRDS : CRICKETS;
        float vol = day ? 0.30F + RNG.nextFloat() * 0.15F : 0.22F + RNG.nextFloat() * 0.1F;
        mc.getSoundManager().play(new SimpleSoundInstance(s, SoundSource.AMBIENT, vol, 0.9F + RNG.nextFloat() * 0.2F, RNG, x, y, z));
        next = day ? 60 + RNG.nextInt(100) : 70 + RNG.nextInt(60);
    }
}
