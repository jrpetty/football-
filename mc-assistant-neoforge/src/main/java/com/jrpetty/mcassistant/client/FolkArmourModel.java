package com.jrpetty.mcassistant.client;

import com.jrpetty.mcassistant.McAssistantMod;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.resources.ResourceLocation;

/**
 * [guard-kit] Armour cut to a village folk, not to a player: the game's own armour pictures (the same boxes and
 * the same places on the 64-by-32 armour textures as the player's armour), each box let out to sit just
 * outside the folk part it covers. A folk's head is ten tall where a player's is eight, and its body six deep
 * where a player's is four; the player's armour, let out by a scale, was drawn besides as a child's (the
 * renderer never told the model it was grown: FolkRenderer.Armour), and a guard in a full suit of iron
 * showed only a patch of grey at its collar.
 *
 * <p>The folk, in its parts' own units (FolkModel): the head -4..4 across, -10..0 up, -4..4 deep, its hair
 * half a unit more all round; the body -4..4 across, 0..12 down, -3..3 deep (the coat, hidden under a
 * breastplate or leggings, half a unit more and eighteen down); each arm -3..1 across, -2..10 down, -2..2
 * deep about its shoulder; each leg -2..2 across, 0..12 down, -2..2 deep about its hip. So:
 * <ul>
 * <li>the helmet 5.1 out each side and before and behind (the hair at 4.5), from 11.1 above the neck to
 *     0.6 below it (the hair from 10.5 to 0.5), its overlay half a unit more;</li>
 * <li>the breastplate 4.6 out each side, 3.55 before and behind (the body at 3, the guard's shield slung at
 *     3.6 on the back), from 0.6 above the shoulders to 12.6 down; the sleeves 0.6 out all round;</li>
 * <li>the leggings' waist inside the breastplate (4.3, 3.3), their legs 0.4 out all round;</li>
 * <li>the boots 0.7 out all round, over the leggings.</li>
 * </ul>
 * FolkRenderer.Armour poses each part where the folk's own is, and draws only the pieces it wears.
 */
public final class FolkArmourModel {

    private FolkArmourModel() {}

    /** The helmet, the breastplate and the boots (the armour's first picture). */
    public static final ModelLayerLocation OUTER = new ModelLayerLocation(
        ResourceLocation.fromNamespaceAndPath(McAssistantMod.MODID, "village_folk"), "armour_outer");
    /** The leggings (the armour's second picture). */
    public static final ModelLayerLocation INNER = new ModelLayerLocation(
        ResourceLocation.fromNamespaceAndPath(McAssistantMod.MODID, "village_folk"), "armour_inner");

    /** The helmet, the breastplate, the sleeves and the boots. */
    public static LayerDefinition outer() {
        MeshDefinition mesh = new MeshDefinition();
        PartDefinition root = mesh.getRoot();
        // The head box is the player's (8 by 8 by 8, for the picture), raised and stretched over the folk's taller head.
        root.addOrReplaceChild("head", CubeListBuilder.create().texOffs(0, 0)
            .addBox(-4.0F, -9.25F, -4.0F, 8.0F, 8.0F, 8.0F, new CubeDeformation(1.1F, 1.85F, 1.1F)), PartPose.ZERO);
        root.addOrReplaceChild("hat", CubeListBuilder.create().texOffs(32, 0)
            .addBox(-4.0F, -9.25F, -4.0F, 8.0F, 8.0F, 8.0F, new CubeDeformation(1.6F, 2.35F, 1.6F)), PartPose.ZERO);
        root.addOrReplaceChild("body", CubeListBuilder.create().texOffs(16, 16)
            .addBox(-4.0F, 0.0F, -2.0F, 8.0F, 12.0F, 4.0F, new CubeDeformation(0.6F, 0.6F, 1.55F)), PartPose.ZERO);
        root.addOrReplaceChild("right_arm", CubeListBuilder.create().texOffs(40, 16)
            .addBox(-3.0F, -2.0F, -2.0F, 4.0F, 12.0F, 4.0F, new CubeDeformation(0.6F)), PartPose.offset(-5.0F, 2.0F, 0.0F));
        root.addOrReplaceChild("left_arm", CubeListBuilder.create().texOffs(40, 16).mirror()
            .addBox(-1.0F, -2.0F, -2.0F, 4.0F, 12.0F, 4.0F, new CubeDeformation(0.6F)), PartPose.offset(5.0F, 2.0F, 0.0F));
        root.addOrReplaceChild("right_leg", CubeListBuilder.create().texOffs(0, 16)
            .addBox(-2.0F, 0.0F, -2.0F, 4.0F, 12.0F, 4.0F, new CubeDeformation(0.7F)), PartPose.offset(-2.0F, 12.0F, 0.0F));
        root.addOrReplaceChild("left_leg", CubeListBuilder.create().texOffs(0, 16).mirror()
            .addBox(-2.0F, 0.0F, -2.0F, 4.0F, 12.0F, 4.0F, new CubeDeformation(0.7F)), PartPose.offset(2.0F, 12.0F, 0.0F));
        return LayerDefinition.create(mesh, 64, 32);
    }

    /** The leggings: a waist inside the breastplate, and the legs. */
    public static LayerDefinition inner() {
        MeshDefinition mesh = new MeshDefinition();
        PartDefinition root = mesh.getRoot();
        // Parts the leggings never show, there for the model's sake.
        root.addOrReplaceChild("head", CubeListBuilder.create().texOffs(0, 0)
            .addBox(-4.0F, -8.0F, -4.0F, 8.0F, 8.0F, 8.0F), PartPose.ZERO);
        root.addOrReplaceChild("hat", CubeListBuilder.create().texOffs(32, 0)
            .addBox(-4.0F, -8.0F, -4.0F, 8.0F, 8.0F, 8.0F), PartPose.ZERO);
        root.addOrReplaceChild("right_arm", CubeListBuilder.create().texOffs(40, 16)
            .addBox(-3.0F, -2.0F, -2.0F, 4.0F, 12.0F, 4.0F), PartPose.offset(-5.0F, 2.0F, 0.0F));
        root.addOrReplaceChild("left_arm", CubeListBuilder.create().texOffs(40, 16).mirror()
            .addBox(-1.0F, -2.0F, -2.0F, 4.0F, 12.0F, 4.0F), PartPose.offset(5.0F, 2.0F, 0.0F));
        root.addOrReplaceChild("body", CubeListBuilder.create().texOffs(16, 16)
            .addBox(-4.0F, 0.0F, -2.0F, 8.0F, 12.0F, 4.0F, new CubeDeformation(0.3F, 0.3F, 1.3F)), PartPose.ZERO);
        root.addOrReplaceChild("right_leg", CubeListBuilder.create().texOffs(0, 16)
            .addBox(-2.0F, 0.0F, -2.0F, 4.0F, 12.0F, 4.0F, new CubeDeformation(0.4F)), PartPose.offset(-2.0F, 12.0F, 0.0F));
        root.addOrReplaceChild("left_leg", CubeListBuilder.create().texOffs(0, 16).mirror()
            .addBox(-2.0F, 0.0F, -2.0F, 4.0F, 12.0F, 4.0F, new CubeDeformation(0.4F)), PartPose.offset(2.0F, 12.0F, 0.0F));
        return LayerDefinition.create(mesh, 64, 32);
    }
}
