package com.jrpetty.mcassistant.client;

import com.jrpetty.mcassistant.McAssistantMod;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;

/**
 * Who a folk looks like: its skin, eyes and hair, and whether it wears a beard.
 *
 * <p>Chosen from its id, so the same folk always looks the same — after a
 * restart, on every client, in every village it is ever seen in — and a village
 * of forty is a crowd of different people rather than forty copies of one.
 * The faces themselves are painted by tools/folk_art.py, which also writes the
 * table below.
 */
public final class FolkLooks {

    private FolkLooks() {}

    // BEGIN GENERATED LOOKS
    private static final boolean[] BEARDED = {false, false, true, false, false, true, false, true, false, false};
    // END GENERATED LOOKS

    private static final ResourceLocation[] SKIN = new ResourceLocation[BEARDED.length];

    static {
        for (int i = 0; i < SKIN.length; i++) {
            SKIN[i] = ResourceLocation.fromNamespaceAndPath(McAssistantMod.MODID,
                "textures/entity/folk/skin_" + i + ".png");
        }
    }

    /** Which of the faces this folk has. */
    public static int skin(Entity folk) {
        long bits = folk.getUUID().getLeastSignificantBits() ^ (folk.getUUID().getMostSignificantBits() >>> 7);
        return (int) Math.floorMod(bits ^ (bits >>> 31), (long) SKIN.length);
    }

    public static ResourceLocation skinTexture(Entity folk) {
        return SKIN[skin(folk)];
    }

    public static boolean bearded(Entity folk) {
        return BEARDED[skin(folk)];
    }
}
