package com.jrpetty.mcassistant.client;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.resources.ResourceLocation;

/**
 * [police] A guard as the town's police (entity/Police.flags, sent as VillageFolkEntity.clientPolice): on police duty
 * (the beat, the desk, the cases, an escort, an event, or at an incident) its sash over the shoulder and its armband in
 * the town's colours, and its belt; on the walls, the watch's kit alone. The constable in its long navy coat with the
 * standing collar and brass buttons, belted; and the badge on its breast once it wears one. The lantern on the night
 * beat is a real one, in its hand (Beats.lantern).
 */
public class WatchLayer extends RenderLayer<VillageFolkEntity, FolkModel> {

    private static final ResourceLocation KIT = ResourceLocation.fromNamespaceAndPath(McAssistantMod.MODID, "textures/entity/folk/watch_kit.png");

    /** The towns' colours, as their watch's tabards have them (FolkRenderer's banners), a touch brighter for a sash. */
    private static final int[] SASH = { 0x3F5DB8, 0xB8353A, 0x388A54, 0x7048A0, 0x46464F, 0x2A9098, 0xC26A28 };

    private final WatchModel model;

    public WatchLayer(RenderLayerParent<VillageFolkEntity, FolkModel> parent, WatchModel model) {
        super(parent);
        this.model = model;
    }

    @Override
    public void render(PoseStack pose, MultiBufferSource buffer, int light, VillageFolkEntity folk, float limbSwing, float limbSwingAmount,
                       float partialTick, float ageInTicks, float netHeadYaw, float headPitch) {
        if (folk.isInvisible() || folk.isBaby()) return;
        int flags = folk.clientPolice();
        if (flags == 0) return;
        boolean constable = (flags & (1 << 4)) != 0, badge = (flags & (1 << 6)) != 0, police = (flags & (1 << 7)) != 0;
        if (!constable && !police && !badge) return;
        model.follow(getParentModel());
        int overlay = LivingEntityRenderer.getOverlayCoords(folk, 0.0F);
        VertexConsumer kit = buffer.getBuffer(RenderType.entityCutoutNoCull(KIT));
        int colour = 0xFF000000 | SASH[Math.floorMod(Math.max(0, folk.clientBanner()), SASH.length)];
        if (constable) {
            WatchModel.draw(model.body, model.coatBody, pose, kit, light, overlay, -1);
            WatchModel.draw(model.body, model.coatSkirt, pose, kit, light, overlay, -1);
            WatchModel.draw(model.body, model.collar, pose, kit, light, overlay, -1);
            WatchModel.draw(model.rightArm, model.sleeveRight, pose, kit, light, overlay, -1);
            WatchModel.draw(model.leftArm, model.sleeveLeft, pose, kit, light, overlay, -1);
        } else if (police) {
            WatchModel.draw(model.body, model.sash, pose, kit, light, overlay, colour);
            WatchModel.draw(model.leftArm, model.armband, pose, kit, light, overlay, colour);
        }
        if (constable || police) WatchModel.draw(model.body, model.belt, pose, kit, light, overlay, -1);
        if (badge) WatchModel.draw(model.body, model.badge, pose, kit, light, overlay, -1);
    }
}
