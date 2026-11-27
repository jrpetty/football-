package com.jrpetty.mcassistant.client;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.entity.Regalia;
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
import java.util.List;

/**
 * [regalia19] A ruler's regalia on a model of its own (entity/Regalia): the crown and its cap and orb, the capes and the
 * ermine collar, the sceptre, the coronet, the chain of office and its medallion, the magistrate's hat, the silver
 * circlet and the staffs, the chief's plumed band and mantle. Posed limb for limb like the folk under it (follow), as its
 * own clothes are (FashionModel), so a crown turns with the head and a sceptre goes with the hand.
 *
 * <p>The boxes and where they are painted are tools/regalia_art.py's, which writes the two GENERATED blocks below with
 * the pictures. Change a box there, never here.
 */
public final class RegaliaModel {

    public static final ModelLayerLocation LAYER = new ModelLayerLocation(
        ResourceLocation.fromNamespaceAndPath(McAssistantMod.MODID, "village_folk_regalia"), "main");

    /** Every part: {part, what it hangs from, who wears it (Regalia.Kind by letter), the pictures it is painted on}. */
    private static final String[][] PIECES = {
        // BEGIN GENERATED PIECES
        {"crown_band", "head", "m", "f"},
        {"crown_cap", "head", "m", "cf"},
        {"crown_orb", "head", "m", "f"},
        {"cape_long", "body", "m", "caf"},
        {"cape_short", "body", "r", "caf"},
        {"ermine_collar", "body", "mr", "f"},
        {"sceptre_right", "right_arm", "m", "f"},
        {"sceptre_left", "left_arm", "m", "f"},
        {"coronet", "head", "r", "f"},
        {"chain", "body", "e", "f"},
        {"medallion", "body", "e", "af"},
        {"hat_crown", "head", "e", "cf"},
        {"hat_brim", "head", "e", "f"},
        {"circlet", "head", "dc", "f"},
        {"staff_right", "right_arm", "d", "f"},
        {"staff_left", "left_arm", "d", "f"},
        {"plume_band", "head", "h", "cf"},
        {"plumes", "head", "h", "caf"},
        {"mantle", "body", "h", "cf"},
        {"tall_staff_right", "right_arm", "h", "af"},
        {"tall_staff_left", "left_arm", "h", "af"},
        // END GENERATED PIECES
    };

    /** One part: its box, the limb it follows, who wears it (a bit a Regalia.Kind), its pictures, and the hand it is in. */
    public record Part(String name, ModelPart box, ModelPart from, int worn, boolean cloth, boolean accent, boolean fixed, int hand) {}

    /** Held in no hand, the right, the left. */
    public static final int NO_HAND = 0, RIGHT = 1, LEFT = 2;

    private final ModelPart head, body, rightArm, leftArm;
    private final List<Part> parts = new ArrayList<>();
    /** The capes, which flare behind as it walks. */
    private final List<ModelPart> capes = new ArrayList<>();
    private final float capeRest;

    public RegaliaModel(ModelPart root) {
        this.head = root.getChild("head");
        this.body = root.getChild("body");
        this.rightArm = root.getChild("right_arm");
        this.leftArm = root.getChild("left_arm");
        float rest = 0.0F;
        for (String[] p : PIECES) {
            ModelPart from = switch (p[1]) {
                case "head" -> head;
                case "right_arm" -> rightArm;
                case "left_arm" -> leftArm;
                default -> body;
            };
            int worn = 0;
            for (char c : p[2].toCharArray()) worn |= 1 << kindOf(c).ordinal();
            int hand = p[0].endsWith("_right") ? RIGHT : p[0].endsWith("_left") ? LEFT : NO_HAND;
            ModelPart box = from.getChild(p[0]);
            parts.add(new Part(p[0], box, from, worn, p[3].contains("c"), p[3].contains("a"), p[3].contains("f"), hand));
            if (p[0].startsWith("cape_")) {
                capes.add(box);
                rest = box.xRot;
            }
        }
        this.capeRest = rest;
    }

    private static Regalia.Kind kindOf(char c) {
        return switch (c) {
            case 'm' -> Regalia.Kind.MONARCH;
            case 'r' -> Regalia.Kind.ROYAL;
            case 'e' -> Regalia.Kind.ELECTED;
            case 'd' -> Regalia.Kind.ELDER;
            case 'c' -> Regalia.Kind.COUNCILLOR;
            case 'h' -> Regalia.Kind.CHIEF;
            default -> Regalia.Kind.NONE;
        };
    }

    public static LayerDefinition createBodyLayer() {
        // BEGIN GENERATED GEOMETRY
        MeshDefinition mesh = new MeshDefinition();
        PartDefinition root = mesh.getRoot();
        PartDefinition head = root.addOrReplaceChild("head", CubeListBuilder.create(), PartPose.ZERO);
        PartDefinition body = root.addOrReplaceChild("body", CubeListBuilder.create(), PartPose.ZERO);
        PartDefinition rightArm = root.addOrReplaceChild("right_arm", CubeListBuilder.create(), PartPose.offset(-5.0F, 2.0F, 0.0F));
        PartDefinition leftArm = root.addOrReplaceChild("left_arm", CubeListBuilder.create(), PartPose.offset(5.0F, 2.0F, 0.0F));
        head.addOrReplaceChild("crown_band", CubeListBuilder.create().texOffs(92, 35).addBox(-4.0F, -13.0F, -4.0F, 8.0F, 4.0F, 8.0F, new CubeDeformation(0.6F)), PartPose.ZERO);
        head.addOrReplaceChild("crown_cap", CubeListBuilder.create().texOffs(64, 60).addBox(-3.5F, -12.0F, -3.5F, 7.0F, 2.0F, 7.0F), PartPose.ZERO);
        head.addOrReplaceChild("crown_orb", CubeListBuilder.create().texOffs(108, 60).addBox(-1.0F, -14.0F, -1.0F, 2.0F, 2.0F, 2.0F).texOffs(32, 70).addBox(-0.5F, -15.0F, -0.5F, 1.0F, 1.0F, 1.0F), PartPose.ZERO);
        body.addOrReplaceChild("cape_long", CubeListBuilder.create().texOffs(16, 0).addBox(-4.5F, 0.0F, 0.0F, 9.0F, 21.0F, 1.0F), PartPose.offsetAndRotation(0.0F, 0.2F, 3.4F, 0.14F, 0.0F, 0.0F));
        body.addOrReplaceChild("cape_short", CubeListBuilder.create().texOffs(64, 35).addBox(-4.5F, 0.0F, 0.0F, 9.0F, 12.0F, 1.0F), PartPose.offsetAndRotation(0.0F, 0.2F, 3.4F, 0.14F, 0.0F, 0.0F));
        body.addOrReplaceChild("ermine_collar", CubeListBuilder.create().texOffs(66, 49).addBox(-5.0F, -0.8F, -3.5F, 10.0F, 3.0F, 7.0F, new CubeDeformation(0.3F)), PartPose.ZERO);
        rightArm.addOrReplaceChild("sceptre_right", CubeListBuilder.create().texOffs(84, 35).addBox(-0.5F, -11.0F, -0.5F, 1.0F, 12.0F, 1.0F).texOffs(116, 60).addBox(-1.0F, -13.0F, -1.0F, 2.0F, 2.0F, 2.0F), PartPose.offsetAndRotation(-1.0F, 9.5F, -0.5F, 0.5F, 0.0F, 0.0F));
        leftArm.addOrReplaceChild("sceptre_left", CubeListBuilder.create().texOffs(88, 35).addBox(-0.5F, -11.0F, -0.5F, 1.0F, 12.0F, 1.0F).texOffs(0, 70).addBox(-1.0F, -13.0F, -1.0F, 2.0F, 2.0F, 2.0F), PartPose.offsetAndRotation(1.0F, 9.5F, -0.5F, 0.5F, 0.0F, 0.0F));
        head.addOrReplaceChild("coronet", CubeListBuilder.create().texOffs(34, 49).addBox(-4.0F, -11.5F, -4.0F, 8.0F, 3.0F, 8.0F, new CubeDeformation(0.6F)), PartPose.ZERO);
        body.addOrReplaceChild("chain", CubeListBuilder.create().texOffs(36, 0).addBox(-4.0F, 0.0F, -3.0F, 8.0F, 12.0F, 6.0F, new CubeDeformation(0.66F)), PartPose.ZERO);
        body.addOrReplaceChild("medallion", CubeListBuilder.create().texOffs(8, 70).addBox(-1.5F, 4.5F, -4.45F, 3.0F, 3.0F, 1.0F), PartPose.ZERO);
        head.addOrReplaceChild("hat_crown", CubeListBuilder.create().texOffs(32, 35).addBox(-4.0F, -14.0F, -4.0F, 8.0F, 5.0F, 8.0F, new CubeDeformation(0.6F)), PartPose.ZERO);
        head.addOrReplaceChild("hat_brim", CubeListBuilder.create().texOffs(64, 0).addBox(-6.5F, -9.3F, -6.5F, 13.0F, 1.0F, 13.0F), PartPose.ZERO);
        head.addOrReplaceChild("circlet", CubeListBuilder.create().texOffs(0, 60).addBox(-4.0F, -10.0F, -4.0F, 8.0F, 2.0F, 8.0F, new CubeDeformation(0.62F)), PartPose.ZERO);
        rightArm.addOrReplaceChild("staff_right", CubeListBuilder.create().texOffs(0, 0).addBox(-0.5F, -21.0F, -0.5F, 1.0F, 34.0F, 1.0F).texOffs(16, 70).addBox(-1.0F, -23.0F, -1.0F, 2.0F, 2.0F, 2.0F), PartPose.offsetAndRotation(-1.0F, 10.0F, -0.5F, 0.45F, 0.0F, 0.0F));
        leftArm.addOrReplaceChild("staff_left", CubeListBuilder.create().texOffs(4, 0).addBox(-0.5F, -21.0F, -0.5F, 1.0F, 34.0F, 1.0F).texOffs(24, 70).addBox(-1.0F, -23.0F, -1.0F, 2.0F, 2.0F, 2.0F), PartPose.offsetAndRotation(1.0F, 10.0F, -0.5F, 0.45F, 0.0F, 0.0F));
        head.addOrReplaceChild("plume_band", CubeListBuilder.create().texOffs(32, 60).addBox(-4.0F, -11.0F, -4.0F, 8.0F, 2.0F, 8.0F, new CubeDeformation(0.62F)), PartPose.ZERO);
        head.addOrReplaceChild("plumes", CubeListBuilder.create().texOffs(0, 35).addBox(-4.0F, -16.0F, -4.0F, 8.0F, 6.0F, 8.0F, new CubeDeformation(0.9F)), PartPose.ZERO);
        body.addOrReplaceChild("mantle", CubeListBuilder.create().texOffs(0, 49).addBox(-5.0F, -0.6F, -3.5F, 10.0F, 4.0F, 7.0F, new CubeDeformation(0.35F)), PartPose.ZERO);
        rightArm.addOrReplaceChild("tall_staff_right", CubeListBuilder.create().texOffs(8, 0).addBox(-0.5F, -21.0F, -0.5F, 1.0F, 34.0F, 1.0F).texOffs(92, 60).addBox(-1.0F, -24.0F, -1.0F, 2.0F, 3.0F, 2.0F), PartPose.offsetAndRotation(-1.0F, 10.0F, -0.5F, 0.45F, 0.0F, 0.0F));
        leftArm.addOrReplaceChild("tall_staff_left", CubeListBuilder.create().texOffs(12, 0).addBox(-0.5F, -21.0F, -0.5F, 1.0F, 34.0F, 1.0F).texOffs(100, 60).addBox(-1.0F, -24.0F, -1.0F, 2.0F, 3.0F, 2.0F), PartPose.offsetAndRotation(1.0F, 10.0F, -0.5F, 0.45F, 0.0F, 0.0F));
        return LayerDefinition.create(mesh, 128, 128);
        // END GENERATED GEOMETRY
    }

    public ModelPart head() {
        return head;
    }

    public List<Part> parts() {
        return parts;
    }

    /** Posed as the folk is this frame; its capes swung out behind it as it walks, and stirring as it stands. */
    public void follow(FolkModel folk, float walk, float age) {
        copy(folk.getHead(), head);
        copy(folk.body(), body);
        copy(folk.rightArm(), rightArm);
        copy(folk.leftArm(), leftArm);
        float flare = capeRest + Math.min(0.55F, walk * 0.6F) + net.minecraft.util.Mth.sin(age * 0.05F) * 0.015F;
        for (ModelPart cape : capes) cape.xRot = flare;
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
