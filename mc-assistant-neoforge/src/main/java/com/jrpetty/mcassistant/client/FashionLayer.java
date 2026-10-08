package com.jrpetty.mcassistant.client;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.entity.Style;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import com.jrpetty.mcassistant.item.Garment;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.DyeColor;

import java.util.ArrayList;
import java.util.List;

/**
 * [fashion] A folk's own clothes, over its trade's (entity/Fashion, entity/Style): its coat, jacket, shawl or
 * waistcoat in the garment's own dye; off work its hat instead of its trade's (FolkModel hides the trade's); its scarf,
 * brooch, rosette, and a feather in a felt hat. The trimmings of all of it (cuffs, lapels, a hat's band, a scarf's
 * stripes, a waistcoat's back) are in its second colour, and the buttons, buckles and the brooch's gold are as they
 * are. Drawn in three passes over one model posed like the folk (FashionModel): the cloth, the trimmings, the fixed.
 *
 * <p>As the folk's armour is drawn over its clothes, so its clothes go under its armour ([guard-kit]): a breastplate
 * puts away its coat, its scarf and what is pinned on it; leggings a long coat's skirt; a helmet its hat.
 */
public class FashionLayer extends RenderLayer<VillageFolkEntity, FolkModel> {

    private static final ResourceLocation CLOTH = texture("fashion_cloth");
    private static final ResourceLocation TRIM = texture("fashion_trim");
    private static final ResourceLocation FIXED = texture("fashion_fixed");

    private final FashionModel model;

    public FashionLayer(RenderLayerParent<VillageFolkEntity, FolkModel> parent, FashionModel model) {
        super(parent);
        this.model = model;
    }

    private static ResourceLocation texture(String name) {
        return ResourceLocation.fromNamespaceAndPath(McAssistantMod.MODID, "textures/entity/folk/" + name + ".png");
    }

    /** Its own main colour, for its trade's dyed cloth (FolkRenderer.Colours); the fallback until it has chosen. */
    public static int mainColour(VillageFolkEntity folk, int fallback) {
        long p = folk.clientStyle();
        return Style.hasColours(p) ? dye(Style.mainOf(p)) : fallback;
    }

    /** Is its own hat on just now (off work, no helmet)? FolkModel puts its trade's head kit away under it. */
    public static boolean hatOn(VillageFolkEntity folk) {
        long p = folk.clientStyle();
        return p != 0 && Style.hatOn(p) && Style.hatShape(p) > 0 && !folk.isBaby() && folk.getItemBySlot(EquipmentSlot.HEAD).isEmpty();
    }

    private static int dye(int id) {
        return DyeColor.byId(Math.max(0, Math.min(15, id))).getTextureDiffuseColor() & 0xFFFFFF;
    }

    /**
     * The pieces to draw for the folk being drawn: their parts and the colour of their cloth, six at most (a coat, a
     * hat, its feather, a scarf, a brooch, a rosette). Kept in the layer and filled afresh for each folk, rather than a
     * new list of new pieces for every folk every frame: the layer draws one folk at a time, on the render thread.
     */
    private final List<?>[] pieceParts = new List<?>[6];
    private final int[] pieceRgb = new int[6];
    private int pieces;
    /** A body garment's parts without its long skirt (put away under leggings), by garment: worked out once each. */
    private final java.util.Map<String, List<FashionModel.Part>> skirtless = new java.util.HashMap<>();

    private void piece(List<FashionModel.Part> parts, int rgb) {
        pieceParts[pieces] = parts;
        pieceRgb[pieces] = rgb;
        pieces++;
    }

    @SuppressWarnings("unchecked")
    private List<FashionModel.Part> partsOf(int i) {
        return (List<FashionModel.Part>) pieceParts[i];
    }

    @Override
    public void render(PoseStack pose, MultiBufferSource buffer, int light, VillageFolkEntity folk,
                       float limbSwing, float limbSwingAmount, float partialTick, float ageInTicks,
                       float netHeadYaw, float headPitch) {
        if (folk.isInvisible() || folk.isBaby()) return;
        long p = folk.clientStyle();
        if (p == 0) return;
        boolean breastplate = folk.getItemBySlot(EquipmentSlot.CHEST).getItem() instanceof ArmorItem;
        boolean leggings = folk.getItemBySlot(EquipmentSlot.LEGS).getItem() instanceof ArmorItem;
        pieces = 0;
        Garment body = Garment.byShape(Garment.Slot.BODY, Style.bodyShape(p));
        if (body != null && !breastplate) {
            List<FashionModel.Part> parts = model.parts(body.name());
            if (leggings) {
                parts = skirtless.computeIfAbsent(body.name(), k -> {
                    List<FashionModel.Part> left = new ArrayList<>(model.parts(k));
                    left.removeIf(part -> part.name().endsWith("_skirt"));
                    return left;
                });
            }
            piece(parts, body.rgb(Style.bodyColour(p)));
        }
        Garment hat = hatOn(folk) ? Garment.byShape(Garment.Slot.HEAD, Style.hatShape(p)) : null;
        if (hat != null) {
            piece(model.parts(hat.name()), hat.rgb(Style.hatColour(p)));
            if (hat == Garment.FELT_HAT && Style.feather(p)) piece(model.parts("FEATHER"), 0xFFFFFF);
        }
        if (!breastplate) {
            if (!Style.none(Style.scarfColour(p))) piece(model.parts(Garment.WOOL_SCARF.name()), Garment.WOOL_SCARF.rgb(Style.scarfColour(p)));
            if (Style.brooch(p)) piece(model.parts(Garment.BROOCH.name()), 0xFFFFFF);
            if (!Style.none(Style.rosetteColour(p))) piece(model.parts(Garment.ROSETTE.name()), Garment.ROSETTE.rgb(Style.rosetteColour(p)));
        }
        if (pieces == 0) return;
        model.follow(getParentModel());
        int overlay = LivingEntityRenderer.getOverlayCoords(folk, 0.0F);
        // A pass a picture: every buffer is finished before the next is asked for.
        VertexConsumer cloth = buffer.getBuffer(RenderType.entityCutoutNoCull(CLOTH));
        for (int i = 0; i < pieces; i++) {
            int rgb = pieceRgb[i];
            for (FashionModel.Part part : partsOf(i)) {
                if (part.cloth()) FashionModel.draw(part, pose, cloth, light, overlay, 0xFF000000 | rgb);
            }
        }
        int accent = 0xFF000000 | dye(Style.accentOf(p));
        VertexConsumer trim = buffer.getBuffer(RenderType.entityCutoutNoCull(TRIM));
        for (int i = 0; i < pieces; i++) {
            for (FashionModel.Part part : partsOf(i)) {
                if (part.trim()) FashionModel.draw(part, pose, trim, light, overlay, accent);
            }
        }
        VertexConsumer fixed = buffer.getBuffer(RenderType.entityCutoutNoCull(FIXED));
        for (int i = 0; i < pieces; i++) {
            for (FashionModel.Part part : partsOf(i)) {
                if (part.fixed()) FashionModel.draw(part, pose, fixed, light, overlay, -1);
            }
        }
    }
}
