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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * [fashion] The folk's own clothes, on a model of their own: a long coat open down the front, a leather jacket, a
 * shawl, a waistcoat; a felt hat, a flat cap, a top hat; a scarf, a brooch, the show's rosette and a feather in a hat
 * (entity/Fashion, item/Garment). It is posed limb for limb like the folk under it (follow), the way the folk's armour
 * is, so a sleeve swings with the arm in it and a hat turns with the head.
 *
 * <p>The boxes and where they are painted are not written by hand: tools/fashion_art.py lays them out and writes the
 * two GENERATED blocks below, with the three pictures (the cloth, tinted with the garment's dye; the trimmings, tinted
 * with the wearer's second colour; and what no dye touches). Change a box there, never here.
 */
public final class FashionModel {

    public static final ModelLayerLocation LAYER = new ModelLayerLocation(
        ResourceLocation.fromNamespaceAndPath(McAssistantMod.MODID, "village_folk_fashion"), "main");

    /**
     * Every garment's parts: {part, what it hangs from, the piece (item/Garment, or FEATHER), what it has painted on
     * the trimmings' and the fixed pictures besides its cloth: "t", "f", "c" for each picture it shows on}.
     */
    private static final String[][] PIECES = {
        // BEGIN GENERATED PIECES
        {"coat_body", "body", "LONG_COAT", "tfc"},
        {"coat_skirt", "body", "LONG_COAT", "tc"},
        {"coat_sleeve_right", "right_arm", "LONG_COAT", "tc"},
        {"coat_sleeve_left", "left_arm", "LONG_COAT", "tc"},
        {"jacket_body", "body", "LEATHER_JACKET", "tfc"},
        {"jacket_sleeve_right", "right_arm", "LEATHER_JACKET", "tc"},
        {"jacket_sleeve_left", "left_arm", "LEATHER_JACKET", "tc"},
        {"shawl_wrap", "body", "WOOL_SHAWL", "tc"},
        {"waistcoat_body", "body", "WAISTCOAT", "tfc"},
        {"felt_crown", "head", "FELT_HAT", "tc"},
        {"felt_brim", "head", "FELT_HAT", "c"},
        {"cap_top", "head", "FLAT_CAP", "tc"},
        {"cap_peak", "head", "FLAT_CAP", "c"},
        {"top_crown", "head", "TOP_HAT", "tc"},
        {"top_brim", "head", "TOP_HAT", "c"},
        {"scarf_wrap", "body", "WOOL_SCARF", "tc"},
        {"scarf_tail", "body", "WOOL_SCARF", "tc"},
        {"brooch", "body", "BROOCH", "f"},
        {"rosette", "body", "ROSETTE", "fc"},
        {"feather", "head", "FEATHER", "f"},
        // END GENERATED PIECES
    };

    /** One part of a piece: its name, the box, the part of the folk it follows, and which pictures it is drawn with. */
    public record Part(String name, ModelPart box, ModelPart from, boolean cloth, boolean trim, boolean fixed) {}

    private final ModelPart head, body, rightArm, leftArm;
    private final Map<String, List<Part>> pieces = new HashMap<>();

    public FashionModel(ModelPart root) {
        this.head = root.getChild("head");
        this.body = root.getChild("body");
        this.rightArm = root.getChild("right_arm");
        this.leftArm = root.getChild("left_arm");
        for (String[] p : PIECES) {
            ModelPart from = switch (p[1]) {
                case "head" -> head;
                case "right_arm" -> rightArm;
                case "left_arm" -> leftArm;
                default -> body;
            };
            pieces.computeIfAbsent(p[2], k -> new ArrayList<>())
                .add(new Part(p[0], from.getChild(p[0]), from, p[3].contains("c"), p[3].contains("t"), p[3].contains("f")));
        }
    }

    public static LayerDefinition createBodyLayer() {
        // BEGIN GENERATED GEOMETRY
        MeshDefinition mesh = new MeshDefinition();
        PartDefinition root = mesh.getRoot();
        PartDefinition head = root.addOrReplaceChild("head", CubeListBuilder.create(), PartPose.ZERO);
        PartDefinition body = root.addOrReplaceChild("body", CubeListBuilder.create(), PartPose.ZERO);
        PartDefinition rightArm = root.addOrReplaceChild("right_arm", CubeListBuilder.create(), PartPose.offset(-5.0F, 2.0F, 0.0F));
        PartDefinition leftArm = root.addOrReplaceChild("left_arm", CubeListBuilder.create(), PartPose.offset(5.0F, 2.0F, 0.0F));
        body.addOrReplaceChild("coat_body", CubeListBuilder.create().texOffs(0, 0).addBox(-4.0F, 0.0F, -3.0F, 8.0F, 12.0F, 6.0F, new CubeDeformation(0.8F)), PartPose.ZERO);
        body.addOrReplaceChild("coat_skirt", CubeListBuilder.create().texOffs(0, 47).addBox(-4.0F, 12.0F, -3.0F, 8.0F, 7.0F, 6.0F, new CubeDeformation(0.8F)), PartPose.ZERO);
        rightArm.addOrReplaceChild("coat_sleeve_right", CubeListBuilder.create().texOffs(84, 18).addBox(-3.0F, -2.0F, -2.0F, 4.0F, 10.0F, 4.0F, new CubeDeformation(0.6F)), PartPose.ZERO);
        leftArm.addOrReplaceChild("coat_sleeve_left", CubeListBuilder.create().texOffs(100, 18).addBox(-1.0F, -2.0F, -2.0F, 4.0F, 10.0F, 4.0F, new CubeDeformation(0.6F)), PartPose.ZERO);
        body.addOrReplaceChild("jacket_body", CubeListBuilder.create().texOffs(28, 0).addBox(-4.0F, 0.0F, -3.0F, 8.0F, 12.0F, 6.0F, new CubeDeformation(0.7F)), PartPose.ZERO);
        rightArm.addOrReplaceChild("jacket_sleeve_right", CubeListBuilder.create().texOffs(0, 33).addBox(-3.0F, -2.0F, -2.0F, 4.0F, 10.0F, 4.0F, new CubeDeformation(0.55F)), PartPose.ZERO);
        leftArm.addOrReplaceChild("jacket_sleeve_left", CubeListBuilder.create().texOffs(16, 33).addBox(-1.0F, -2.0F, -2.0F, 4.0F, 10.0F, 4.0F, new CubeDeformation(0.55F)), PartPose.ZERO);
        body.addOrReplaceChild("shawl_wrap", CubeListBuilder.create().texOffs(80, 33).addBox(-5.0F, -1.0F, -4.0F, 10.0F, 5.0F, 8.0F, new CubeDeformation(0.25F)), PartPose.ZERO);
        body.addOrReplaceChild("waistcoat_body", CubeListBuilder.create().texOffs(56, 0).addBox(-4.0F, 0.0F, -3.0F, 8.0F, 11.0F, 6.0F, new CubeDeformation(0.65F)), PartPose.ZERO);
        head.addOrReplaceChild("felt_crown", CubeListBuilder.create().texOffs(28, 47).addBox(-4.0F, -12.0F, -4.0F, 8.0F, 4.0F, 8.0F, new CubeDeformation(0.6F)), PartPose.ZERO);
        head.addOrReplaceChild("felt_brim", CubeListBuilder.create().texOffs(0, 18).addBox(-7.0F, -8.2F, -7.0F, 14.0F, 1.0F, 14.0F), PartPose.ZERO);
        head.addOrReplaceChild("cap_top", CubeListBuilder.create().texOffs(0, 60).addBox(-4.0F, -10.5F, -4.5F, 8.0F, 2.0F, 9.0F, new CubeDeformation(0.6F)), PartPose.ZERO);
        head.addOrReplaceChild("cap_peak", CubeListBuilder.create().texOffs(46, 60).addBox(-4.0F, -8.4F, -7.6F, 8.0F, 1.0F, 3.0F), PartPose.ZERO);
        head.addOrReplaceChild("top_crown", CubeListBuilder.create().texOffs(56, 18).addBox(-3.5F, -17.0F, -3.5F, 7.0F, 7.0F, 7.0F, new CubeDeformation(0.1F)), PartPose.ZERO);
        head.addOrReplaceChild("top_brim", CubeListBuilder.create().texOffs(32, 33).addBox(-6.0F, -10.6F, -6.0F, 12.0F, 1.0F, 12.0F), PartPose.ZERO);
        body.addOrReplaceChild("scarf_wrap", CubeListBuilder.create().texOffs(60, 47).addBox(-4.5F, -1.0F, -4.5F, 9.0F, 2.0F, 9.0F, new CubeDeformation(0.2F)), PartPose.ZERO);
        body.addOrReplaceChild("scarf_tail", CubeListBuilder.create().texOffs(34, 60).addBox(1.0F, 0.5F, -5.6F, 3.0F, 7.0F, 1.0F), PartPose.ZERO);
        body.addOrReplaceChild("brooch", CubeListBuilder.create().texOffs(76, 60).addBox(-3.0F, 0.3F, -4.7F, 2.0F, 2.0F, 1.0F), PartPose.ZERO);
        body.addOrReplaceChild("rosette", CubeListBuilder.create().texOffs(68, 60).addBox(1.0F, -0.2F, -4.9F, 3.0F, 3.0F, 1.0F).texOffs(82, 60).addBox(1.5F, 2.6F, -4.7F, 2.0F, 2.0F, 1.0F), PartPose.ZERO);
        head.addOrReplaceChild("feather", CubeListBuilder.create().texOffs(42, 60).addBox(-0.5F, -5.0F, -0.5F, 1.0F, 5.0F, 1.0F), PartPose.offsetAndRotation(4.4F, -9.6F, 1.5F, 0.0F, 0.0F, 0.45F));
        return LayerDefinition.create(mesh, 128, 128);
        // END GENERATED GEOMETRY
    }

    /** The parts of a piece ("LONG_COAT", "FEATHER"...), or none. */
    public List<Part> parts(String piece) {
        return pieces.getOrDefault(piece, List.of());
    }

    /** Posed as the folk is: its head, body and arms, where they are and how they are turned, this frame. */
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

    /** One part drawn: moved to the limb it hangs from, then drawn there in the colour given. */
    public static void draw(Part p, PoseStack pose, VertexConsumer to, int light, int overlay, int colour) {
        pose.pushPose();
        p.from().translateAndRotate(pose);
        p.box().render(pose, to, light, overlay, colour);
        pose.popPose();
    }
}
