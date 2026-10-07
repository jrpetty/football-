package com.jrpetty.mcassistant.client;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BannerRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.ModelBakery;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BannerPatternLayers;

import javax.annotation.Nullable;

/**
 * [arms] The town's arms drawn on its board, a banner hung either side of its name in the header: the board's line
 * "AN|field|pattern=dye,pattern=dye..." (entity/Arms.boardLine) woven into a banner's layers out of the client's own
 * registry of patterns, and drawn with the game's banner cloth, the way a banner on a wall is.
 */
public final class BoardArms {

    private final ModelPart flag;

    public BoardArms(BlockEntityRendererProvider.Context context) {
        this.flag = context.bakeLayer(ModelLayers.BANNER).getChild("flag");
    }

    /** The arms as a field and its charges; null for a line that is not the arms'. */
    record Arms(DyeColor field, BannerPatternLayers layers) {}

    @Nullable
    static Arms parse(String words, @Nullable Level level) {
        String[] p = words.split("\\|", -1);
        DyeColor field = DyeColor.byName(p[0], null);
        if (field == null) return null;
        BannerPatternLayers.Builder layers = new BannerPatternLayers.Builder();
        if (level != null && p.length > 1 && !p[1].isEmpty()) {
            var reg = level.registryAccess().registryOrThrow(Registries.BANNER_PATTERN);
            for (String c : p[1].split(",")) {
                String[] kv = c.split("=");
                if (kv.length != 2) continue;
                ResourceLocation id = ResourceLocation.tryParse(kv[0]);
                DyeColor colour = DyeColor.byName(kv[1], null);
                if (id == null || colour == null) continue;
                reg.getHolder(ResourceKey.create(Registries.BANNER_PATTERN, id)).ifPresent(h -> layers.add(h, colour));
            }
        }
        return new Arms(field, layers.build());
    }

    /** No arms (the line is not the arms'), as kept in PARSED. */
    private static final Arms NONE = new Arms(DyeColor.WHITE, BannerPatternLayers.EMPTY);
    /** The arms read from a board's line, kept: read twice a frame a board (a banner each side), they are the same
     *  arms for the same line in the same world (its registry of patterns does not change while it is open). */
    private final java.util.Map<String, Arms> parsed = new java.util.HashMap<>();
    /** The world they were read in (held weakly, so a world left is not kept for it); null if read with none. */
    @Nullable private java.lang.ref.WeakReference<Level> parsedIn;

    @Nullable
    private Arms parsed(String words, @Nullable Level level) {
        boolean same = level == null ? parsedIn == null : parsedIn != null && parsedIn.get() == level;
        if (!same || parsed.size() > 64) {
            parsed.clear();
            parsedIn = level == null ? null : new java.lang.ref.WeakReference<>(level);
        }
        Arms got = parsed.get(words);
        if (got == null) {
            got = parse(words, level);
            parsed.put(words, got == null ? NONE : got);
        }
        return got == NONE ? null : got;
    }

    /**
     * The arms hung at x, y of the board's writing (its pixels: x to the reader's right, y down, z out of its face
     * towards the reader), {@code wide} pixels across and twice that down, lit as the writing is.
     */
    public void draw(String words, @Nullable Level level, PoseStack pose, MultiBufferSource buffers, float x, float y, float wide, int light) {
        Arms a = parsed(words, level);
        if (a == null) return;
        pose.pushPose();
        pose.translate(x + wide / 2.0F, y, 0.0F);
        // The flag is twenty of the model's sixteenths across: so many of them to a pixel. Out of the board towards the
        // reader (the model's cloth faces its own -z, as on a banner).
        float s = wide * 16.0F / 20.0F;
        pose.scale(s, s, -s);
        flag.xRot = 0.0F;
        flag.y = 0.0F;
        BannerRenderer.renderPatterns(pose, buffers, light, OverlayTexture.NO_OVERLAY, flag, ModelBakery.BANNER_BASE, true, a.field(), a.layers());
        pose.popPose();
    }
}
