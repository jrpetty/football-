package com.jrpetty.mcassistant.client;

import com.jrpetty.mcassistant.McAssistantMod;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.resources.ResourceLocation;

/**
 * [police] The watch's police kit, on a model of its own posed limb for limb like the folk under it (as the fashion's
 * and the armour's are): the constable's long coat (body, skirt, sleeves and a standing collar) in the watch's navy with
 * brass buttons; a guard on police duty's sash, worn over the shoulder in the town's colours, and an armband to match; a
 * belt with its brass buckle; and the badge on the left breast. Each a little proud of the folk's armour, so it shows
 * over a chestplate. One picture (textures/entity/folk/watch_kit.png, drawn by tools/watch_art.py): the sash and the
 * armband drawn pale to be tinted, the rest in their own colours.
 */
public final class WatchModel {

    public static final ModelLayerLocation LAYER = new ModelLayerLocation(
        ResourceLocation.fromNamespaceAndPath(McAssistantMod.MODID, "village_folk_watch"), "main");

    final ModelPart head, body, rightArm, leftArm;
    final ModelPart coatBody, coatSkirt, collar, sleeveRight, sleeveLeft, sash, belt, badge, armband;

    public WatchModel(ModelPart root) {
        this.head = root.getChild("head");
        this.body = root.getChild("body");
        this.rightArm = root.getChild("right_arm");
        this.leftArm = root.getChild("left_arm");
        this.coatBody = body.getChild("coat_body");
        this.coatSkirt = body.getChild("coat_skirt");
        this.collar = body.getChild("collar");
        this.sash = body.getChild("sash");
        this.belt = body.getChild("belt");
        this.badge = body.getChild("badge");
        this.sleeveRight = rightArm.getChild("coat_sleeve_right");
        this.sleeveLeft = leftArm.getChild("coat_sleeve_left");
        this.armband = leftArm.getChild("armband");
    }

    public static LayerDefinition createBodyLayer() {
        MeshDefinition mesh = new MeshDefinition();
        PartDefinition root = mesh.getRoot();
        root.addOrReplaceChild("head", CubeListBuilder.create(), PartPose.ZERO);
        PartDefinition body = root.addOrReplaceChild("body", CubeListBuilder.create(), PartPose.ZERO);
        PartDefinition rightArm = root.addOrReplaceChild("right_arm", CubeListBuilder.create(), PartPose.offset(-5.0F, 2.0F, 0.0F));
        PartDefinition leftArm = root.addOrReplaceChild("left_arm", CubeListBuilder.create(), PartPose.offset(5.0F, 2.0F, 0.0F));
        body.addOrReplaceChild("coat_body", CubeListBuilder.create().texOffs(0, 0)
            .addBox(-4.0F, 0.0F, -3.0F, 8.0F, 12.0F, 6.0F, new CubeDeformation(1.1F)), PartPose.ZERO);
        body.addOrReplaceChild("coat_skirt", CubeListBuilder.create().texOffs(28, 0)
            .addBox(-4.0F, 12.0F, -3.0F, 8.0F, 7.0F, 6.0F, new CubeDeformation(1.1F)), PartPose.ZERO);
        body.addOrReplaceChild("collar", CubeListBuilder.create().texOffs(88, 0)
            .addBox(-4.5F, -1.0F, -3.5F, 9.0F, 2.0F, 7.0F, new CubeDeformation(0.7F)), PartPose.ZERO);
        body.addOrReplaceChild("sash", CubeListBuilder.create().texOffs(0, 20)
            .addBox(-1.0F, -7.0F, -3.5F, 2.0F, 14.0F, 7.0F, new CubeDeformation(0.75F)), PartPose.offsetAndRotation(0.0F, 6.0F, 0.0F, 0.0F, 0.0F, 0.62F));
        body.addOrReplaceChild("belt", CubeListBuilder.create().texOffs(20, 20)
            .addBox(-4.0F, 10.0F, -3.0F, 8.0F, 2.0F, 6.0F, new CubeDeformation(1.2F)), PartPose.ZERO);
        body.addOrReplaceChild("badge", CubeListBuilder.create().texOffs(54, 20)
            .addBox(0.6F, 1.6F, -4.6F, 3.0F, 3.0F, 1.0F), PartPose.ZERO);
        rightArm.addOrReplaceChild("coat_sleeve_right", CubeListBuilder.create().texOffs(56, 0)
            .addBox(-3.0F, -2.0F, -2.0F, 4.0F, 10.0F, 4.0F, new CubeDeformation(0.85F)), PartPose.ZERO);
        leftArm.addOrReplaceChild("coat_sleeve_left", CubeListBuilder.create().texOffs(72, 0)
            .addBox(-1.0F, -2.0F, -2.0F, 4.0F, 10.0F, 4.0F, new CubeDeformation(0.85F)), PartPose.ZERO);
        leftArm.addOrReplaceChild("armband", CubeListBuilder.create().texOffs(64, 20)
            .addBox(-1.0F, 1.0F, -2.0F, 4.0F, 2.0F, 4.0F, new CubeDeformation(0.95F)), PartPose.ZERO);
        return LayerDefinition.create(mesh, 128, 64);
    }

    /** Posed as the folk is, this frame. */
    public void follow(FolkModel folk) {
        copy(folk.getHead(), head);
        copy(folk.body(), body);
        copy(folk.rightArm(), rightArm);
        copy(folk.leftArm(), leftArm);
    }

    private static void copy(ModelPart from, ModelPart to) {
        to.x = from.x;
        to.y = from.y;
        to.z = from.z;
        to.xRot = from.xRot;
        to.yRot = from.yRot;
        to.zRot = from.zRot;
        to.xScale = from.xScale;
        to.yScale = from.yScale;
        to.zScale = from.zScale;
    }

    /** One part drawn where the limb it hangs from is, in the colour given. */
    static void draw(ModelPart from, ModelPart part, PoseStack pose, VertexConsumer to, int light, int overlay, int colour) {
        pose.pushPose();
        from.translateAndRotate(pose);
        part.render(pose, to, light, overlay, colour);
        pose.popPose();
    }
}
