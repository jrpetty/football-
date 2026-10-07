package com.jrpetty.mcassistant.client;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.entity.AssistantEntity;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.ArmedModel;
import net.minecraft.client.model.HeadedModel;
import net.minecraft.client.model.HierarchicalModel;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.HumanoidArm;

/**
 * A village folk's body: a villager's head and nose on a person who can hold
 * things.
 *
 * <p>A villager's arms are folded for good, which is right for somebody who
 * stands at a lectern and wrong for somebody who digs a mine. These have arms
 * of their own that swing as they walk and swing a tool when they work, with
 * the tool in their hand. Everything else a trade wears is a box of its own —
 * the farmer's straw hat, the miner's helmet and lamp, the smelter's apron and
 * goggles, the guard's shield, the hauler's pack — shown only on the folk of
 * that trade.
 *
 * <p>The shape is not written by hand: tools/folk_art.py holds every box and
 * the paint that goes on it, and writes the two GENERATED blocks below. Change
 * a box there, never here.
 */
public class FolkModel extends HierarchicalModel<VillageFolkEntity> implements ArmedModel, HeadedModel {

    public static final ModelLayerLocation LAYER = new ModelLayerLocation(
        ResourceLocation.fromNamespaceAndPath(McAssistantMod.MODID, "village_folk"), "main");

    /** The trades, in StationTask order, by the names the pictures use. */
    public static final String[] TRADES = {
        "none", "farmer", "lumberjack", "miner", "rancher",
        "guard", "smelter", "fisher", "storekeeper", "hauler",
        "blacksmith", "tailor", "beekeeper", "brewer", "enchanter", "cook", "shopkeeper", "scout", "hunter",
        "cavedweller",                                         // [caves] not in StationTask's order: see outfit()
        "emerald",                                             // [emerald] the emerald trader's: see outfit()
    };

    /** [caves] The cave dweller's outfit, after the trades drawn in StationTask's order (those before it). */
    public static final int CAVE_OUTFIT = 19;
    /** [emerald] The emerald trader's outfit: the merchant's green coat, the wide hat, the pack (tools/folk_art.py). */
    public static final int EMERALD_OUTFIT = 20;

    /** Which part each trade wears: {part, the part it hangs from, the trade}. */
    private static final String[][] WEARERS = {
        // BEGIN GENERATED WEARERS
        {"beard", "head", "beard"},
        {"none_cloak", "body", "none"},
        {"farmer_crown", "head", "farmer"},
        {"farmer_brim", "head", "farmer"},
        {"farmer_pouch", "body", "farmer"},
        {"lumberjack_cap", "head", "lumberjack"},
        {"lumberjack_cuff", "head", "lumberjack"},
        {"lumberjack_bobble", "head", "lumberjack"},
        {"lumberjack_rack", "body", "lumberjack"},
        {"lumberjack_log_top", "body", "lumberjack"},
        {"lumberjack_log_low", "body", "lumberjack"},
        {"miner_shell", "head", "miner"},
        {"miner_brim", "head", "miner"},
        {"miner_lamp", "head", "miner"},
        {"miner_lantern", "body", "miner"},
        {"rancher_crown", "head", "rancher"},
        {"rancher_brim", "head", "rancher"},
        {"rancher_curl_right", "head", "rancher"},
        {"rancher_curl_left", "head", "rancher"},
        {"rancher_shawl", "body", "rancher"},
        {"rancher_rope", "body", "rancher"},
        {"guard_shell", "head", "guard"},
        {"guard_brim", "head", "guard"},
        {"guard_shield", "body", "guard"},
        {"guard_scabbard", "body", "guard"},
        {"smelter_apron", "body", "smelter"},
        {"smelter_band", "head", "smelter"},
        {"smelter_lens_right", "head", "smelter"},
        {"smelter_lens_left", "head", "smelter"},
        {"fisher_crown", "head", "fisher"},
        {"fisher_brim", "head", "fisher"},
        {"fisher_flap", "head", "fisher"},
        {"fisher_creel", "body", "fisher"},
        {"fisher_lid", "body", "fisher"},
        {"storekeeper_crown", "head", "storekeeper"},
        {"storekeeper_brim", "head", "storekeeper"},
        {"storekeeper_ledger", "body", "storekeeper"},
        {"storekeeper_quill", "head", "storekeeper"},
        {"hauler_cap", "head", "hauler"},
        {"hauler_visor", "head", "hauler"},
        {"hauler_pack", "body", "hauler"},
        {"hauler_roll", "body", "hauler"},
        {"hauler_pan", "body", "hauler"},
        {"blacksmith_apron", "body", "blacksmith"},
        {"blacksmith_scarf", "head", "blacksmith"},
        {"blacksmith_knot", "head", "blacksmith"},
        {"blacksmith_hammer", "body", "blacksmith"},
        {"tailor_beret", "head", "tailor"},
        {"tailor_tape", "body", "tailor"},
        {"tailor_spool", "body", "tailor"},
        {"beekeeper_crown", "head", "beekeeper"},
        {"beekeeper_brim", "head", "beekeeper"},
        {"beekeeper_veil", "head", "beekeeper"},
        {"beekeeper_smoker", "body", "beekeeper"},
        {"brewer_cap", "head", "brewer"},
        {"brewer_apron", "body", "brewer"},
        {"brewer_vial_a", "body", "brewer"},
        {"brewer_vial_b", "body", "brewer"},
        {"brewer_vial_c", "body", "brewer"},
        {"enchanter_hat_base", "head", "enchanter"},
        {"enchanter_hat_mid", "head", "enchanter"},
        {"enchanter_hat_tip", "head", "enchanter"},
        {"enchanter_book", "body", "enchanter"},
        {"cook_band", "head", "cook"},
        {"cook_puff", "head", "cook"},
        {"cook_apron", "body", "cook"},
        {"cook_spoon", "body", "cook"},
        {"shopkeeper_cap", "head", "shopkeeper"},
        {"shopkeeper_visor", "head", "shopkeeper"},
        {"shopkeeper_apron", "body", "shopkeeper"},
        {"shopkeeper_pouch", "body", "shopkeeper"},
        {"scout_hood", "head", "scout"},
        {"scout_feather", "head", "scout"},
        {"scout_cape", "body", "scout"},
        {"scout_satchel", "body", "scout"},
        {"scout_spyglass", "body", "scout"},
        {"hunter_hood", "head", "hunter"},
        {"hunter_mantle", "body", "hunter"},
        {"hunter_quiver", "body", "hunter"},
        {"hunter_fletch", "body", "hunter"},
        {"hunter_knife", "body", "hunter"},
        {"cavedweller_shell", "head", "cavedweller"},
        {"cavedweller_brim", "head", "cavedweller"},
        {"cavedweller_lamp", "head", "cavedweller"},
        {"cavedweller_haft", "body", "cavedweller"},
        {"cavedweller_pickhead", "body", "cavedweller"},
        {"cavedweller_rope", "body", "cavedweller"},
        {"emerald_crown", "head", "emerald"},
        {"emerald_brim", "head", "emerald"},
        {"emerald_pack", "body", "emerald"},
        {"emerald_roll", "body", "emerald"},
        {"emerald_lamp", "body", "emerald"},
        {"emerald_purse", "body", "emerald"},
        // END GENERATED WEARERS
    };

    private final ModelPart root;
    private final ModelPart head;
    private final ModelPart body;
    private final ModelPart rightArm;
    private final ModelPart leftArm;
    private final ModelPart rightLeg;
    private final ModelPart leftLeg;
    private final ModelPart[] worn;
    private final String[] wornBy;
    private final boolean[] onHead;
    /** [guard-kit] The folk's hair and coat, put away under a helmet and a breastplate (FolkArmourModel). */
    private final ModelPart hair;
    private final ModelPart coat;
    /** [guard-kit] The trade's things worn round the body, put away under a breastplate: aprons, a shawl, a mantle. */
    private final boolean[] wraps;
    /** [caves] The things worn on the head that stay on over a helmet: the cave dweller's lamp, strapped to it. */
    private final boolean[] overHelmet;

    public FolkModel(ModelPart root) {
        this.root = root;
        this.head = root.getChild("head");
        this.body = root.getChild("body");
        this.rightArm = root.getChild("right_arm");
        this.leftArm = root.getChild("left_arm");
        this.rightLeg = root.getChild("right_leg");
        this.leftLeg = root.getChild("left_leg");
        this.worn = new ModelPart[WEARERS.length];
        this.wornBy = new String[WEARERS.length];
        this.onHead = new boolean[WEARERS.length];
        this.hair = head.getChild("hair");
        this.coat = body.getChild("coat");
        this.wraps = new boolean[WEARERS.length];
        this.overHelmet = new boolean[WEARERS.length];
        for (int i = 0; i < WEARERS.length; i++) {
            ModelPart parent = switch (WEARERS[i][1]) {
                case "head" -> head;
                case "body" -> body;
                default -> root;
            };
            worn[i] = parent.getChild(WEARERS[i][0]);
            wornBy[i] = WEARERS[i][2];
            onHead[i] = "head".equals(WEARERS[i][1]);
            String name = WEARERS[i][0];
            wraps[i] = !onHead[i] && (name.contains("apron") || name.contains("shawl") || name.contains("mantle")
                || name.contains("tape") || name.contains("cloak") || name.contains("cape"));
            overHelmet[i] = "cavedweller_lamp".equals(name);
        }
    }

    public static LayerDefinition createBodyLayer() {
        // BEGIN GENERATED GEOMETRY
        MeshDefinition mesh = new MeshDefinition();
        PartDefinition root = mesh.getRoot();
        PartDefinition head = root.addOrReplaceChild("head", CubeListBuilder.create().texOffs(0, 0).addBox(-4.0F, -10.0F, -4.0F, 8.0F, 10.0F, 8.0F), PartPose.ZERO);
        head.addOrReplaceChild("hair", CubeListBuilder.create().texOffs(32, 0).addBox(-4.0F, -10.0F, -4.0F, 8.0F, 10.0F, 8.0F, new CubeDeformation(0.5F)), PartPose.ZERO);
        head.addOrReplaceChild("nose", CubeListBuilder.create().texOffs(24, 0).addBox(-1.0F, -1.0F, -6.0F, 2.0F, 4.0F, 2.0F), PartPose.offset(0.0F, -2.0F, 0.0F));
        head.addOrReplaceChild("beard", CubeListBuilder.create().texOffs(44, 50).addBox(-3.0F, -3.0F, -5.0F, 6.0F, 5.0F, 1.0F), PartPose.ZERO);
        PartDefinition body = root.addOrReplaceChild("body", CubeListBuilder.create().texOffs(0, 18).addBox(-4.0F, 0.0F, -3.0F, 8.0F, 12.0F, 6.0F), PartPose.ZERO);
        body.addOrReplaceChild("coat", CubeListBuilder.create().texOffs(0, 36).addBox(-4.0F, 0.0F, -3.0F, 8.0F, 18.0F, 6.0F, new CubeDeformation(0.5F)), PartPose.ZERO);
        root.addOrReplaceChild("right_arm", CubeListBuilder.create().texOffs(28, 18).addBox(-3.0F, -2.0F, -2.0F, 4.0F, 12.0F, 4.0F), PartPose.offset(-5.0F, 2.0F, 0.0F));
        root.addOrReplaceChild("left_arm", CubeListBuilder.create().texOffs(44, 18).addBox(-1.0F, -2.0F, -2.0F, 4.0F, 12.0F, 4.0F), PartPose.offset(5.0F, 2.0F, 0.0F));
        root.addOrReplaceChild("right_leg", CubeListBuilder.create().texOffs(28, 34).addBox(-2.0F, 0.0F, -2.0F, 4.0F, 12.0F, 4.0F), PartPose.offset(-2.0F, 12.0F, 0.0F));
        root.addOrReplaceChild("left_leg", CubeListBuilder.create().texOffs(44, 34).addBox(-2.0F, 0.0F, -2.0F, 4.0F, 12.0F, 4.0F), PartPose.offset(2.0F, 12.0F, 0.0F));
        body.addOrReplaceChild("none_cloak", CubeListBuilder.create().texOffs(64, 0).addBox(-4.5F, -0.6F, 3.6F, 9.0F, 14.0F, 1.0F), PartPose.ZERO);
        head.addOrReplaceChild("farmer_crown", CubeListBuilder.create().texOffs(64, 17).addBox(-4.0F, -12.0F, -4.0F, 8.0F, 4.0F, 8.0F, new CubeDeformation(0.6F)), PartPose.ZERO);
        head.addOrReplaceChild("farmer_brim", CubeListBuilder.create().texOffs(64, 0).addBox(-8.0F, -8.0F, -8.0F, 16.0F, 1.0F, 16.0F), PartPose.ZERO);
        body.addOrReplaceChild("farmer_pouch", CubeListBuilder.create().texOffs(96, 17).addBox(1.0F, 9.5F, -4.5F, 3.0F, 3.0F, 1.0F), PartPose.ZERO);
        head.addOrReplaceChild("lumberjack_cap", CubeListBuilder.create().texOffs(64, 0).addBox(-4.0F, -11.0F, -4.0F, 8.0F, 3.0F, 8.0F, new CubeDeformation(0.6F)), PartPose.ZERO);
        head.addOrReplaceChild("lumberjack_cuff", CubeListBuilder.create().texOffs(64, 11).addBox(-4.0F, -8.0F, -4.0F, 8.0F, 1.0F, 8.0F, new CubeDeformation(0.85F)), PartPose.ZERO);
        head.addOrReplaceChild("lumberjack_bobble", CubeListBuilder.create().texOffs(96, 0).addBox(-1.0F, -13.5F, -1.0F, 2.0F, 2.0F, 2.0F), PartPose.ZERO);
        body.addOrReplaceChild("lumberjack_rack", CubeListBuilder.create().texOffs(64, 20).addBox(-4.0F, 1.0F, 3.6F, 8.0F, 10.0F, 1.0F), PartPose.ZERO);
        body.addOrReplaceChild("lumberjack_log_top", CubeListBuilder.create().texOffs(64, 31).addBox(-5.0F, 1.5F, 4.6F, 10.0F, 3.0F, 3.0F), PartPose.ZERO);
        body.addOrReplaceChild("lumberjack_log_low", CubeListBuilder.create().texOffs(90, 31).addBox(-5.0F, 5.0F, 4.6F, 10.0F, 3.0F, 3.0F), PartPose.ZERO);
        head.addOrReplaceChild("miner_shell", CubeListBuilder.create().texOffs(64, 0).addBox(-4.0F, -11.0F, -4.0F, 8.0F, 4.0F, 8.0F, new CubeDeformation(0.6F)), PartPose.ZERO);
        head.addOrReplaceChild("miner_brim", CubeListBuilder.create().texOffs(64, 12).addBox(-5.0F, -7.0F, -5.0F, 10.0F, 1.0F, 10.0F), PartPose.ZERO);
        head.addOrReplaceChild("miner_lamp", CubeListBuilder.create().texOffs(96, 0).addBox(-1.5F, -10.0F, -5.6F, 3.0F, 2.0F, 1.0F), PartPose.ZERO);
        body.addOrReplaceChild("miner_lantern", CubeListBuilder.create().texOffs(104, 0).addBox(1.0F, 9.5F, -5.5F, 3.0F, 4.0F, 2.0F), PartPose.ZERO);
        head.addOrReplaceChild("rancher_crown", CubeListBuilder.create().texOffs(64, 0).addBox(-4.0F, -12.0F, -4.0F, 8.0F, 3.0F, 8.0F, new CubeDeformation(0.6F)), PartPose.ZERO);
        head.addOrReplaceChild("rancher_brim", CubeListBuilder.create().texOffs(64, 11).addBox(-7.0F, -9.0F, -7.0F, 14.0F, 1.0F, 14.0F), PartPose.ZERO);
        head.addOrReplaceChild("rancher_curl_right", CubeListBuilder.create().texOffs(64, 26).addBox(-8.0F, -10.0F, -7.0F, 1.0F, 1.0F, 14.0F), PartPose.ZERO);
        head.addOrReplaceChild("rancher_curl_left", CubeListBuilder.create().texOffs(64, 26).addBox(7.0F, -10.0F, -7.0F, 1.0F, 1.0F, 14.0F), PartPose.ZERO);
        body.addOrReplaceChild("rancher_shawl", CubeListBuilder.create().texOffs(64, 41).addBox(-5.0F, -0.8F, -4.0F, 10.0F, 5.0F, 8.0F), PartPose.ZERO);
        body.addOrReplaceChild("rancher_rope", CubeListBuilder.create().texOffs(100, 41).addBox(-4.0F, 9.0F, -4.5F, 3.0F, 3.0F, 1.0F), PartPose.ZERO);
        head.addOrReplaceChild("guard_shell", CubeListBuilder.create().texOffs(64, 0).addBox(-4.0F, -11.0F, -4.0F, 8.0F, 3.0F, 8.0F, new CubeDeformation(0.6F)), PartPose.ZERO);
        head.addOrReplaceChild("guard_brim", CubeListBuilder.create().texOffs(64, 11).addBox(-6.0F, -8.0F, -6.0F, 12.0F, 1.0F, 12.0F), PartPose.ZERO);
        body.addOrReplaceChild("guard_shield", CubeListBuilder.create().texOffs(64, 24).addBox(-4.0F, 1.0F, 3.6F, 8.0F, 10.0F, 1.0F), PartPose.ZERO);
        body.addOrReplaceChild("guard_scabbard", CubeListBuilder.create().texOffs(82, 24).addBox(-0.5F, 0.0F, -1.0F, 1.0F, 8.0F, 2.0F), PartPose.offsetAndRotation(4.7F, 9.0F, 3.0F, 0.28F, 0.0F, -0.08F));
        body.addOrReplaceChild("smelter_apron", CubeListBuilder.create().texOffs(64, 0).addBox(-4.0F, 1.5F, -4.5F, 8.0F, 15.0F, 1.0F), PartPose.ZERO);
        head.addOrReplaceChild("smelter_band", CubeListBuilder.create().texOffs(82, 0).addBox(-4.0F, -9.0F, -4.0F, 8.0F, 2.0F, 8.0F, new CubeDeformation(0.6F)), PartPose.ZERO);
        head.addOrReplaceChild("smelter_lens_right", CubeListBuilder.create().texOffs(114, 0).addBox(-3.0F, -9.0F, -5.6F, 2.0F, 2.0F, 1.0F), PartPose.ZERO);
        head.addOrReplaceChild("smelter_lens_left", CubeListBuilder.create().texOffs(114, 0).addBox(1.0F, -9.0F, -5.6F, 2.0F, 2.0F, 1.0F), PartPose.ZERO);
        head.addOrReplaceChild("fisher_crown", CubeListBuilder.create().texOffs(64, 0).addBox(-4.0F, -11.0F, -4.0F, 8.0F, 3.0F, 8.0F, new CubeDeformation(0.6F)), PartPose.ZERO);
        head.addOrReplaceChild("fisher_brim", CubeListBuilder.create().texOffs(64, 11).addBox(-5.0F, -8.0F, -5.0F, 10.0F, 1.0F, 10.0F), PartPose.ZERO);
        head.addOrReplaceChild("fisher_flap", CubeListBuilder.create().texOffs(64, 22).addBox(-5.0F, 0.0F, 0.0F, 10.0F, 1.0F, 4.0F), PartPose.offsetAndRotation(0.0F, -8.0F, 4.6F, -0.5F, 0.0F, 0.0F));
        body.addOrReplaceChild("fisher_creel", CubeListBuilder.create().texOffs(64, 27).addBox(-3.0F, 5.0F, 3.6F, 6.0F, 5.0F, 3.0F), PartPose.ZERO);
        body.addOrReplaceChild("fisher_lid", CubeListBuilder.create().texOffs(82, 27).addBox(-3.5F, 4.0F, 3.4F, 7.0F, 1.0F, 4.0F), PartPose.ZERO);
        head.addOrReplaceChild("storekeeper_crown", CubeListBuilder.create().texOffs(64, 0).addBox(-4.0F, -12.0F, -4.0F, 8.0F, 3.0F, 8.0F, new CubeDeformation(0.6F)), PartPose.ZERO);
        head.addOrReplaceChild("storekeeper_brim", CubeListBuilder.create().texOffs(64, 11).addBox(-5.0F, -9.0F, -5.0F, 10.0F, 1.0F, 10.0F), PartPose.ZERO);
        body.addOrReplaceChild("storekeeper_ledger", CubeListBuilder.create().texOffs(104, 11).addBox(-4.5F, 9.0F, -4.5F, 3.0F, 4.0F, 1.0F), PartPose.ZERO);
        head.addOrReplaceChild("storekeeper_quill", CubeListBuilder.create().texOffs(112, 11).addBox(-0.5F, -4.0F, -0.5F, 1.0F, 5.0F, 1.0F), PartPose.offsetAndRotation(4.4F, -5.0F, 0.5F, -0.35F, 0.0F, 0.12F));
        head.addOrReplaceChild("hauler_cap", CubeListBuilder.create().texOffs(64, 0).addBox(-4.0F, -10.0F, -4.0F, 8.0F, 2.0F, 8.0F, new CubeDeformation(0.6F)), PartPose.ZERO);
        head.addOrReplaceChild("hauler_visor", CubeListBuilder.create().texOffs(96, 0).addBox(-3.5F, -8.0F, -7.0F, 7.0F, 1.0F, 3.0F), PartPose.ZERO);
        body.addOrReplaceChild("hauler_pack", CubeListBuilder.create().texOffs(64, 10).addBox(-4.0F, 0.5F, 3.6F, 8.0F, 10.0F, 4.0F), PartPose.ZERO);
        body.addOrReplaceChild("hauler_roll", CubeListBuilder.create().texOffs(88, 10).addBox(-5.0F, -2.5F, 4.1F, 10.0F, 3.0F, 3.0F), PartPose.ZERO);
        body.addOrReplaceChild("hauler_pan", CubeListBuilder.create().texOffs(114, 10).addBox(4.0F, 3.0F, 5.0F, 1.0F, 4.0F, 3.0F), PartPose.ZERO);
        body.addOrReplaceChild("blacksmith_apron", CubeListBuilder.create().texOffs(64, 0).addBox(-4.0F, 1.5F, -4.5F, 8.0F, 15.0F, 1.0F), PartPose.ZERO);
        head.addOrReplaceChild("blacksmith_scarf", CubeListBuilder.create().texOffs(64, 17).addBox(-4.0F, -10.0F, -4.0F, 8.0F, 3.0F, 8.0F, new CubeDeformation(0.6F)), PartPose.ZERO);
        head.addOrReplaceChild("blacksmith_knot", CubeListBuilder.create().texOffs(96, 17).addBox(-1.0F, -9.0F, 4.4F, 2.0F, 2.0F, 1.0F), PartPose.ZERO);
        body.addOrReplaceChild("blacksmith_hammer", CubeListBuilder.create().texOffs(100, 0).addBox(4.2F, 8.0F, -1.0F, 1.0F, 6.0F, 1.0F).texOffs(104, 0).addBox(3.7F, 6.5F, -2.0F, 2.0F, 2.0F, 3.0F), PartPose.ZERO);
        head.addOrReplaceChild("tailor_beret", CubeListBuilder.create().texOffs(64, 0).addBox(-4.5F, -11.0F, -4.5F, 9.0F, 2.0F, 9.0F), PartPose.ZERO);
        body.addOrReplaceChild("tailor_tape", CubeListBuilder.create().texOffs(64, 12).addBox(-4.5F, -0.6F, -3.5F, 9.0F, 1.0F, 7.0F), PartPose.ZERO);
        body.addOrReplaceChild("tailor_spool", CubeListBuilder.create().texOffs(100, 0).addBox(1.0F, 9.5F, -4.5F, 2.0F, 3.0F, 1.0F), PartPose.ZERO);
        head.addOrReplaceChild("beekeeper_crown", CubeListBuilder.create().texOffs(64, 0).addBox(-4.0F, -12.0F, -4.0F, 8.0F, 3.0F, 8.0F, new CubeDeformation(0.6F)), PartPose.ZERO);
        head.addOrReplaceChild("beekeeper_brim", CubeListBuilder.create().texOffs(64, 11).addBox(-7.0F, -9.0F, -7.0F, 14.0F, 1.0F, 14.0F), PartPose.ZERO);
        head.addOrReplaceChild("beekeeper_veil", CubeListBuilder.create().texOffs(64, 26).addBox(-6.0F, -8.5F, -6.0F, 12.0F, 9.0F, 12.0F), PartPose.ZERO);
        body.addOrReplaceChild("beekeeper_smoker", CubeListBuilder.create().texOffs(104, 0).addBox(1.5F, 8.5F, -5.0F, 2.0F, 4.0F, 2.0F), PartPose.ZERO);
        head.addOrReplaceChild("brewer_cap", CubeListBuilder.create().texOffs(64, 0).addBox(-4.0F, -11.0F, -4.0F, 8.0F, 2.0F, 8.0F, new CubeDeformation(0.6F)), PartPose.ZERO);
        body.addOrReplaceChild("brewer_apron", CubeListBuilder.create().texOffs(64, 16).addBox(-4.0F, 2.0F, -4.5F, 8.0F, 13.0F, 1.0F), PartPose.ZERO);
        body.addOrReplaceChild("brewer_vial_a", CubeListBuilder.create().texOffs(100, 12).addBox(-3.0F, 7.5F, -5.6F, 1.0F, 2.0F, 1.0F), PartPose.ZERO);
        body.addOrReplaceChild("brewer_vial_b", CubeListBuilder.create().texOffs(104, 12).addBox(-1.0F, 7.5F, -5.6F, 1.0F, 2.0F, 1.0F), PartPose.ZERO);
        body.addOrReplaceChild("brewer_vial_c", CubeListBuilder.create().texOffs(108, 12).addBox(1.0F, 7.5F, -5.6F, 1.0F, 2.0F, 1.0F), PartPose.ZERO);
        head.addOrReplaceChild("enchanter_hat_base", CubeListBuilder.create().texOffs(64, 0).addBox(-5.0F, -11.0F, -5.0F, 10.0F, 2.0F, 10.0F, new CubeDeformation(0.3F)), PartPose.ZERO);
        head.addOrReplaceChild("enchanter_hat_mid", CubeListBuilder.create().texOffs(64, 12).addBox(-3.0F, -14.0F, -3.0F, 6.0F, 3.0F, 6.0F), PartPose.ZERO);
        head.addOrReplaceChild("enchanter_hat_tip", CubeListBuilder.create().texOffs(88, 12).addBox(-1.5F, -17.0F, -1.5F, 3.0F, 3.0F, 3.0F), PartPose.ZERO);
        body.addOrReplaceChild("enchanter_book", CubeListBuilder.create().texOffs(104, 0).addBox(-4.5F, 9.0F, -4.5F, 3.0F, 4.0F, 1.0F), PartPose.ZERO);
        head.addOrReplaceChild("cook_band", CubeListBuilder.create().texOffs(64, 0).addBox(-4.0F, -11.0F, -4.0F, 8.0F, 2.0F, 8.0F, new CubeDeformation(0.6F)), PartPose.ZERO);
        head.addOrReplaceChild("cook_puff", CubeListBuilder.create().texOffs(64, 12).addBox(-4.5F, -15.0F, -4.5F, 9.0F, 4.0F, 9.0F), PartPose.ZERO);
        body.addOrReplaceChild("cook_apron", CubeListBuilder.create().texOffs(64, 26).addBox(-4.0F, 2.0F, -4.5F, 8.0F, 13.0F, 1.0F), PartPose.ZERO);
        body.addOrReplaceChild("cook_spoon", CubeListBuilder.create().texOffs(100, 0).addBox(3.5F, 7.5F, -4.5F, 1.0F, 5.0F, 1.0F), PartPose.ZERO);
        head.addOrReplaceChild("shopkeeper_cap", CubeListBuilder.create().texOffs(64, 0).addBox(-4.0F, -10.0F, -4.0F, 8.0F, 2.0F, 8.0F, new CubeDeformation(0.6F)), PartPose.ZERO);
        head.addOrReplaceChild("shopkeeper_visor", CubeListBuilder.create().texOffs(96, 0).addBox(-3.5F, -8.5F, -7.0F, 7.0F, 1.0F, 3.0F), PartPose.ZERO);
        body.addOrReplaceChild("shopkeeper_apron", CubeListBuilder.create().texOffs(64, 12).addBox(-4.0F, 3.0F, -4.5F, 8.0F, 12.0F, 1.0F), PartPose.ZERO);
        body.addOrReplaceChild("shopkeeper_pouch", CubeListBuilder.create().texOffs(100, 12).addBox(1.0F, 9.0F, -5.0F, 3.0F, 3.0F, 1.0F), PartPose.ZERO);
        head.addOrReplaceChild("scout_hood", CubeListBuilder.create().texOffs(64, 0).addBox(-4.0F, -10.0F, -4.0F, 8.0F, 10.0F, 8.0F, new CubeDeformation(0.75F)), PartPose.ZERO);
        head.addOrReplaceChild("scout_feather", CubeListBuilder.create().texOffs(100, 12).addBox(-0.5F, -4.0F, -0.5F, 1.0F, 4.0F, 1.0F), PartPose.offsetAndRotation(4.2F, -9.0F, 1.0F, 0.0F, 0.0F, 0.45F));
        body.addOrReplaceChild("scout_cape", CubeListBuilder.create().texOffs(64, 20).addBox(-4.5F, 0.0F, 3.3F, 9.0F, 16.0F, 1.0F), PartPose.ZERO);
        body.addOrReplaceChild("scout_satchel", CubeListBuilder.create().texOffs(100, 0).addBox(3.8F, 7.0F, -2.0F, 2.0F, 5.0F, 4.0F), PartPose.ZERO);
        body.addOrReplaceChild("scout_spyglass", CubeListBuilder.create().texOffs(114, 0).addBox(-5.0F, 7.0F, -1.0F, 1.0F, 4.0F, 1.0F), PartPose.ZERO);
        head.addOrReplaceChild("hunter_hood", CubeListBuilder.create().texOffs(64, 0).addBox(-4.0F, -10.0F, -4.0F, 8.0F, 10.0F, 8.0F, new CubeDeformation(0.75F)), PartPose.ZERO);
        body.addOrReplaceChild("hunter_mantle", CubeListBuilder.create().texOffs(64, 20).addBox(-4.5F, -0.5F, -3.5F, 9.0F, 4.0F, 7.0F, new CubeDeformation(0.3F)), PartPose.ZERO);
        body.addOrReplaceChild("hunter_quiver", CubeListBuilder.create().texOffs(100, 0).addBox(-1.5F, -1.0F, 0.0F, 3.0F, 10.0F, 2.0F), PartPose.offsetAndRotation(0.0F, 2.0F, 3.4F, 0.0F, 0.0F, 0.35F));
        body.addOrReplaceChild("hunter_fletch", CubeListBuilder.create().texOffs(112, 0).addBox(-1.0F, -4.0F, 0.5F, 2.0F, 3.0F, 1.0F), PartPose.offsetAndRotation(0.0F, 2.0F, 3.4F, 0.0F, 0.0F, 0.35F));
        body.addOrReplaceChild("hunter_knife", CubeListBuilder.create().texOffs(100, 14).addBox(-5.0F, 8.0F, -1.0F, 1.0F, 4.0F, 1.0F), PartPose.ZERO);
        head.addOrReplaceChild("cavedweller_shell", CubeListBuilder.create().texOffs(64, 0).addBox(-4.0F, -11.0F, -4.0F, 8.0F, 4.0F, 8.0F, new CubeDeformation(0.6F)), PartPose.ZERO);
        head.addOrReplaceChild("cavedweller_brim", CubeListBuilder.create().texOffs(64, 12).addBox(-5.0F, -7.0F, -5.0F, 10.0F, 1.0F, 10.0F), PartPose.ZERO);
        head.addOrReplaceChild("cavedweller_lamp", CubeListBuilder.create().texOffs(104, 0).addBox(-2.0F, -10.5F, -6.8F, 4.0F, 3.0F, 2.0F), PartPose.ZERO);
        body.addOrReplaceChild("cavedweller_haft", CubeListBuilder.create().texOffs(64, 24).addBox(-0.5F, -6.0F, 0.0F, 1.0F, 12.0F, 1.0F), PartPose.offsetAndRotation(0.0F, 6.0F, 4.4F, 0.0F, 0.0F, 0.7F));
        body.addOrReplaceChild("cavedweller_pickhead", CubeListBuilder.create().texOffs(68, 24).addBox(-3.5F, -7.0F, 0.0F, 7.0F, 1.0F, 1.0F), PartPose.offsetAndRotation(0.0F, 6.0F, 4.4F, 0.0F, 0.0F, 0.7F));
        body.addOrReplaceChild("cavedweller_rope", CubeListBuilder.create().texOffs(84, 24).addBox(-6.2F, 7.0F, -2.0F, 2.0F, 4.0F, 4.0F), PartPose.ZERO);
        head.addOrReplaceChild("emerald_crown", CubeListBuilder.create().texOffs(64, 0).addBox(-4.0F, -12.0F, -4.0F, 8.0F, 3.0F, 8.0F, new CubeDeformation(0.6F)), PartPose.ZERO);
        head.addOrReplaceChild("emerald_brim", CubeListBuilder.create().texOffs(64, 11).addBox(-8.0F, -9.0F, -8.0F, 16.0F, 1.0F, 16.0F), PartPose.ZERO);
        body.addOrReplaceChild("emerald_pack", CubeListBuilder.create().texOffs(64, 28).addBox(-4.0F, 0.5F, 3.6F, 8.0F, 10.0F, 4.0F), PartPose.ZERO);
        body.addOrReplaceChild("emerald_roll", CubeListBuilder.create().texOffs(88, 28).addBox(-5.0F, -2.5F, 4.1F, 10.0F, 3.0F, 3.0F), PartPose.ZERO);
        body.addOrReplaceChild("emerald_lamp", CubeListBuilder.create().texOffs(100, 36).addBox(4.2F, 3.0F, 5.0F, 1.0F, 3.0F, 2.0F), PartPose.ZERO);
        body.addOrReplaceChild("emerald_purse", CubeListBuilder.create().texOffs(114, 28).addBox(2.5F, 8.5F, -4.6F, 2.0F, 3.0F, 1.0F), PartPose.ZERO);
        return LayerDefinition.create(mesh, 128, 128);
        // END GENERATED GEOMETRY
    }

    @Override
    public ModelPart root() {
        return root;
    }

    @Override
    public ModelPart getHead() {
        return head;
    }

    public ModelPart body() { return body; }
    public ModelPart rightArm() { return rightArm; }
    public ModelPart leftArm() { return leftArm; }
    public ModelPart rightLeg() { return rightLeg; }
    public ModelPart leftLeg() { return leftLeg; }

    /** The trade's name, as the pictures and the parts know it. */
    public static String tradeOf(AssistantEntity folk) {
        return TRADES[outfit(folk)];
    }

    /**
     * [caves] Which trade's outfit a folk wears: its own, by StationTask's order (a trade after the drawn ones, the
     * banker, wraps round as before). The cave dweller has its own: the delver's helm and lamp (lit in the dark, and
     * left on over an iron helmet), the oilskin coat, the rope and the spare pick.
     */
    public static int outfit(AssistantEntity folk) {
        int job = folk.clientJobOrdinal();
        if (job == AssistantEntity.StationTask.CAVE.ordinal()) return CAVE_OUTFIT;  // "cavedweller"
        if (job == AssistantEntity.StationTask.FERRY.ordinal()) return 7;           // [transport] "fisher": a waterman's clothes
        if (job == AssistantEntity.StationTask.EMERALD.ordinal()) return EMERALD_OUTFIT;   // [emerald] "emerald": the merchant's coat
        return Math.floorMod(job, CAVE_OUTFIT);
    }

    @Override
    public void setupAnim(VillageFolkEntity folk, float limbSwing, float limbSwingAmount,
                          float ageInTicks, float netHeadYaw, float headPitch) {
        root.getAllParts().forEach(ModelPart::resetPose);

        // Dress for the trade. A helmet goes on instead of the trade's hat, not on top of it.
        String trade = tradeOf(folk);
        boolean helmet = !folk.getItemBySlot(EquipmentSlot.HEAD).isEmpty();
        boolean ownHat = FashionLayer.hatOn(folk);          // [fashion] off work, its own hat instead of its trade's
        for (int i = 0; i < worn.length; i++) {
            String by = wornBy[i];
            boolean show = by.equals("beard")
                ? FolkLooks.bearded(folk) || "lumberjack".equals(trade)
                : by.equals(trade);
            worn[i].visible = show && !(onHead[i] && !by.equals("beard") && ((helmet && !overHelmet[i]) || ownHat));
        }
        // [guard-kit] Armour over the clothes, not under them: the hair under a helmet, the coat under a breastplate or
        // leggings, and what is worn round the body (an apron, a shawl, a mantle, a cape) under a breastplate.
        boolean breastplate = folk.getItemBySlot(EquipmentSlot.CHEST).getItem() instanceof net.minecraft.world.item.ArmorItem;
        boolean leggings = folk.getItemBySlot(EquipmentSlot.LEGS).getItem() instanceof net.minecraft.world.item.ArmorItem;
        hair.visible = !(folk.getItemBySlot(EquipmentSlot.HEAD).getItem() instanceof net.minecraft.world.item.ArmorItem);
        coat.visible = !(breastplate || leggings);
        if (breastplate) for (int i = 0; i < worn.length; i++) if (wraps[i]) worn[i].visible = false;

        head.yRot = netHeadYaw * Mth.DEG_TO_RAD;
        head.xRot = headPitch * Mth.DEG_TO_RAD;
        // A child: a big head on a small body, and no trade's clothes yet.
        float headSize = young ? 1.3F : 1.0F;
        head.xScale = headSize;
        head.yScale = headSize;
        head.zScale = headSize;
        if (young) {
            for (int i = 0; i < worn.length; i++) worn[i].visible = false;
        }

        // Walking: legs and arms in step, as a player walks.
        float step = limbSwing * 0.6662F;
        rightLeg.xRot = Mth.cos(step) * 1.4F * limbSwingAmount;
        leftLeg.xRot = Mth.cos(step + Mth.PI) * 1.4F * limbSwingAmount;
        rightArm.xRot = Mth.cos(step + Mth.PI) * limbSwingAmount;
        leftArm.xRot = Mth.cos(step) * limbSwingAmount;
        // Sat down (on a park bench: entity/Park): legs out in front, hands in the lap.
        if (folk.getPose() == net.minecraft.world.entity.Pose.SITTING) {
            rightLeg.xRot = -1.4137167F;
            rightLeg.yRot = Mth.PI / 10.0F;
            rightLeg.zRot = 0.07853982F;
            leftLeg.xRot = -1.4137167F;
            leftLeg.yRot = -Mth.PI / 10.0F;
            leftLeg.zRot = -0.07853982F;
            rightArm.xRot = -Mth.PI / 5.0F;
            leftArm.xRot = -Mth.PI / 5.0F;
        }
        // Crouched out of sight (hide-and-seek: entity/Families): knees up, hands on them, the whole of it lower.
        if (folk.getPose() == net.minecraft.world.entity.Pose.CROUCHING) {
            rightLeg.xRot = -0.9F;
            leftLeg.xRot = -0.9F;
            rightArm.xRot = -0.7F;
            leftArm.xRot = -0.7F;
        }

        // A hand with something in it is carried a little forward.
        boolean rightMain = folk.getMainArm() == HumanoidArm.RIGHT;
        if (!folk.getMainHandItem().isEmpty()) {
            ModelPart arm = rightMain ? rightArm : leftArm;
            arm.xRot = arm.xRot * 0.5F - Mth.PI / 10.0F;
        }
        if (!folk.getOffhandItem().isEmpty()) {
            ModelPart arm = rightMain ? leftArm : rightArm;
            arm.xRot = arm.xRot * 0.5F - Mth.PI / 10.0F;
        }

        // Breathing: the arms never hang dead still.
        float breath = Mth.cos(ageInTicks * 0.09F) * 0.05F + 0.05F;
        rightArm.zRot += breath;
        leftArm.zRot -= breath;
        rightArm.xRot += Mth.sin(ageInTicks * 0.067F) * 0.05F;
        leftArm.xRot -= Mth.sin(ageInTicks * 0.067F) * 0.05F;

        // A hauler leans into its load when it walks.
        if ("hauler".equals(trade)) {
            body.xRot = 0.12F * Math.min(1.0F, limbSwingAmount * 2.0F);
        }

        swingTool(folk, rightMain ? HumanoidArm.RIGHT : HumanoidArm.LEFT);
    }

    /** The swing of a pick, an axe or a sword — the player's own, move for move. */
    private void swingTool(VillageFolkEntity folk, HumanoidArm side) {
        if (attackTime <= 0.0F) return;
        ModelPart arm = side == HumanoidArm.RIGHT ? rightArm : leftArm;
        float turn = Mth.sin(Mth.sqrt(attackTime) * Mth.TWO_PI) * 0.2F;
        if (side == HumanoidArm.LEFT) turn = -turn;
        body.yRot = turn;
        rightArm.z = Mth.sin(turn) * 5.0F;
        rightArm.x = -Mth.cos(turn) * 5.0F;
        leftArm.z = -Mth.sin(turn) * 5.0F;
        leftArm.x = Mth.cos(turn) * 5.0F;
        rightArm.yRot += turn;
        leftArm.yRot += turn;
        leftArm.xRot += turn;
        float f = 1.0F - attackTime;
        f *= f;
        f *= f;
        f = 1.0F - f;
        float lift = Mth.sin(f * Mth.PI);
        float follow = Mth.sin(attackTime * Mth.PI) * -(head.xRot - 0.7F) * 0.75F;
        arm.xRot -= lift * 1.2F + follow;
        arm.yRot += turn * 2.0F;
        arm.zRot += Mth.sin(attackTime * Mth.PI) * -0.4F;
    }

    @Override
    public void translateToHand(HumanoidArm side, PoseStack pose) {
        (side == HumanoidArm.RIGHT ? rightArm : leftArm).translateAndRotate(pose);
    }
}
