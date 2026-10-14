package com.jrpetty.mcassistant.client;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.entity.WagonKind;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;

/**
 * [wagons] The three wagons' models, drawn in pixels (sixteen to a block) with the ground at y 0 and up negative (the
 * model way), the front towards +z, the axles a block and a half each side of the middle (TradeWagon.HALF_LENGTH):
 * <ul>
 * <li>the handcart: a small bed on one axle and two big wheels, low sides, two long handles with a grip;</li>
 * <li>the wagon: a bed with side boards, a tailboard, a raised driver's bench and footboard, two shafts out to the
 *     animal, four spoked wooden wheels, a lamp on a post at the front corner, a painted stripe in the town's second
 *     colour;</li>
 * <li>the covered wagon: longer, wider and higher, big iron-tyred wheels behind and smaller ones in front, four hoops
 *     and a canvas over them (stepped at the shoulders so it reads round), two lanterns hung from the front hoop.</li>
 * </ul>
 * Each is cut into the parts drawn apart: the frame (wood and iron), the trim (tinted), the canvas (tinted), each wheel
 * (turned), and a sack for the load. The sheet is tools/wagon_art.py's, a band to a stuff (see there).
 */
public final class TradeWagonModel {

    private TradeWagonModel() {}

    public static final ResourceLocation TEXTURE = ResourceLocation.fromNamespaceAndPath(McAssistantMod.MODID, "textures/entity/trade_wagon.png");
    public static final ModelLayerLocation HANDCART = layer("handcart");
    public static final ModelLayerLocation WAGON = layer("wagon");
    public static final ModelLayerLocation COVERED = layer("covered_wagon");

    /** The sheet's bands (tools/wagon_art.py). */
    private static final int OAK = 0, DARK = 80, CANVAS = 128, IRON = 208;

    private static ModelLayerLocation layer(String name) {
        return new ModelLayerLocation(ResourceLocation.fromNamespaceAndPath(McAssistantMod.MODID, "trade_wagon"), name);
    }

    public static ModelLayerLocation layerFor(WagonKind k) {
        return switch (k) {
            case HANDCART -> HANDCART;
            case WAGON -> WAGON;
            case COVERED -> COVERED;
        };
    }

    // ------------------------------------------------------------------ where things are, for the renderer (blocks)

    /** The top of the bed, where the load stands. */
    public static float bedTop(WagonKind k) {
        return switch (k) {
            case HANDCART -> 15 / 16F;
            case WAGON -> 14 / 16F;
            case COVERED -> 16 / 16F;
        };
    }

    /** The stretch of the bed the load fills: z from, z to, and its half width. */
    public static float[] loadZone(WagonKind k) {
        return switch (k) {
            case HANDCART -> new float[]{ -0.85F, 0.85F, 0.48F };
            case WAGON -> new float[]{ -1.65F, 1.05F, 0.66F };
            case COVERED -> new float[]{ -1.9F, 1.2F, 0.72F };
        };
    }

    /** Rows of the load along the bed (two across, two high). */
    public static int loadRows(WagonKind k) {
        return switch (k) {
            case HANDCART -> 2;
            case WAGON -> 3;
            case COVERED -> 4;
        };
    }

    /** Where its lanterns hang (x, y, z in blocks, model x mirrored as drawn), none for the handcart. */
    public static float[][] lamps(WagonKind k) {
        return switch (k) {
            case HANDCART -> new float[0][];
            case WAGON -> new float[][]{ { 12.25F / 16, 31.0F / 16, 27.75F / 16 } };
            case COVERED -> new float[][]{ { 11.5F / 16, 35.0F / 16, 19.0F / 16 }, { -11.5F / 16, 35.0F / 16, 19.0F / 16 } };
        };
    }

    /** Its wheels: the parts' names and their radii in pixels. */
    public static String[] wheels(WagonKind k) {
        return k == WagonKind.HANDCART ? new String[]{ "wheel_l", "wheel_r" } : new String[]{ "wheel_fl", "wheel_fr", "wheel_bl", "wheel_br" };
    }

    public static float[] wheelRadii(WagonKind k) {
        return switch (k) {
            case HANDCART -> new float[]{ 8, 8 };
            case WAGON -> new float[]{ 7, 7, 7, 7 };
            case COVERED -> new float[]{ 6.5F, 6.5F, 8, 8 };
        };
    }

    // ------------------------------------------------------------------ the meshes

    private static CubeListBuilder box(int v) {
        return CubeListBuilder.create().texOffs(0, v);
    }

    /** A spoked wheel at (x, z) of this radius: hub, eight spokes, eight rim pieces (iron-tyred or wooden). */
    private static void wheel(PartDefinition root, String name, float x, float z, float r, boolean iron) {
        PartDefinition w = root.addOrReplaceChild(name, box(DARK).addBox(-1.5F, -1.5F, -1.5F, 3, 3, 3), PartPose.offset(x, -r, z));
        for (int k = 0; k < 4; k++) {
            w.addOrReplaceChild("spoke" + k, box(DARK).addBox(-0.5F, -r + 1.0F, -0.5F, 1, 2 * r - 2.0F, 1),
                PartPose.rotation(k * Mth.PI / 4, 0, 0));
        }
        float len = 2 * r * (float) Math.tan(Math.PI / 8) + 0.6F;
        for (int k = 0; k < 8; k++) {
            w.addOrReplaceChild("rim" + k, box(iron ? IRON : DARK).addBox(-1.0F, -r, -len / 2, 2, 1.5F, len),
                PartPose.rotation(k * Mth.PI / 4, 0, 0));
        }
    }

    private static void sack(PartDefinition root) {
        root.addOrReplaceChild("sack", box(CANVAS).addBox(-3.5F, -6.0F, -3.5F, 7, 6, 7).addBox(-2.5F, -7.0F, -2.5F, 5, 1, 5)
            .addBox(-1.0F, -8.0F, -1.0F, 2, 1, 2), PartPose.ZERO);
    }

    public static LayerDefinition handcart() {
        MeshDefinition mesh = new MeshDefinition();
        PartDefinition root = mesh.getRoot();
        root.addOrReplaceChild("frame", box(OAK)
                .addBox(-9, -15, -15, 18, 2, 30)                 // the bed
                .addBox(-10, -20, -15, 1, 5, 30)                 // its sides
                .addBox(9, -20, -15, 1, 5, 30)
                .addBox(-9, -19, -16, 18, 4, 1)                  // back and front boards
                .addBox(-9, -19, 15, 18, 4, 1)
                .texOffs(0, DARK)
                .addBox(-8, -14.5F, 15, 1.5F, 1.5F, 22)          // the handles
                .addBox(6.5F, -14.5F, 15, 1.5F, 1.5F, 22)
                .addBox(-8, -14.5F, 35.5F, 16, 1.5F, 1.5F)       // the grip
                .addBox(-11, -9, -1, 22, 2, 2)                   // the axle
                .addBox(-7, -13, 12, 1, 13, 1)                   // the legs it rests on
                .addBox(6, -13, 12, 1, 13, 1),
            PartPose.ZERO);
        root.addOrReplaceChild("trim", box(CANVAS).addBox(-10.4F, -18.5F, -14, 0.4F, 1, 28).addBox(10, -18.5F, -14, 0.4F, 1, 28), PartPose.ZERO);
        root.addOrReplaceChild("canvas", CubeListBuilder.create(), PartPose.ZERO);
        wheel(root, "wheel_l", -11.5F, 0, 8, false);
        wheel(root, "wheel_r", 11.5F, 0, 8, false);
        sack(root);
        return LayerDefinition.create(mesh, 256, 256);
    }

    public static LayerDefinition wagon() {
        MeshDefinition mesh = new MeshDefinition();
        PartDefinition root = mesh.getRoot();
        root.addOrReplaceChild("frame", box(OAK)
                .addBox(-12, -14, -28, 24, 2, 56)                // the bed
                .addBox(-13, -22, -28, 1, 8, 56)                 // side boards
                .addBox(12, -22, -28, 1, 8, 56)
                .addBox(-12, -20, -29, 24, 6, 1)                 // the tailboard
                .addBox(-12, -21, 27, 24, 7, 1)                  // the front board
                .addBox(-11, -19, 19, 22, 2, 6)                  // the driver's bench
                .texOffs(0, DARK)
                .addBox(-10, -17, 20, 2, 3, 4)                   // its legs
                .addBox(8, -17, 20, 2, 3, 4)
                .addBox(-11, -15, 28, 22, 1, 4)                  // the footboard
                .addBox(-8, -13, 27, 2, 2, 38)                   // the shafts
                .addBox(6, -13, 27, 2, 2, 38)
                .addBox(-9, -13, 28, 18, 2, 2)                   // the swingletree
                .addBox(-14, -8, 23, 28, 2, 2)                   // the axles
                .addBox(-14, -8, -25, 28, 2, 2)
                .addBox(-13.75F, -34, 27, 1.5F, 13, 1.5F)        // the lamp post
                .texOffs(0, IRON)
                .addBox(-13.75F, -35, 26.5F, 1.5F, 1, 3)         // its hook
                .addBox(-12.5F, -22.5F, -28.5F, 25, 0.5F, 0.5F)  // iron corner straps
                .addBox(-12.5F, -22.5F, 27.5F, 25, 0.5F, 0.5F),
            PartPose.ZERO);
        root.addOrReplaceChild("trim", box(CANVAS).addBox(-13.4F, -20.5F, -26, 0.4F, 1.5F, 52).addBox(13, -20.5F, -26, 0.4F, 1.5F, 52),
            PartPose.ZERO);
        root.addOrReplaceChild("canvas", CubeListBuilder.create(), PartPose.ZERO);
        wheel(root, "wheel_fl", -14, 24, 7, false);
        wheel(root, "wheel_fr", 14, 24, 7, false);
        wheel(root, "wheel_bl", -14, -24, 7, false);
        wheel(root, "wheel_br", 14, -24, 7, false);
        sack(root);
        return LayerDefinition.create(mesh, 256, 256);
    }

    public static LayerDefinition covered() {
        MeshDefinition mesh = new MeshDefinition();
        PartDefinition root = mesh.getRoot();
        CubeListBuilder frame = box(OAK)
            .addBox(-13, -16, -32, 26, 2, 64)                    // the bed
            .addBox(-14, -24, -32, 1, 8, 64)                     // side boards
            .addBox(13, -24, -32, 1, 8, 64)
            .addBox(-13, -22, -33, 26, 6, 1)                     // the tailboard
            .addBox(-13, -23, 31, 26, 7, 1)                      // the front board
            .addBox(-12, -20, 22, 24, 2, 6)                      // the driver's bench
            .texOffs(0, DARK)
            .addBox(-11, -18, 23, 2, 2, 4)
            .addBox(9, -18, 23, 2, 2, 4)
            .addBox(-12, -17, 32, 24, 1, 4)                      // the footboard
            .addBox(-8, -14, 31, 2, 2, 36)                       // the shafts
            .addBox(6, -14, 31, 2, 2, 36)
            .addBox(-9, -14, 32, 18, 2, 2)                       // the swingletree
            .addBox(-15, -7.5F, 23, 30, 2, 2)                    // the axles
            .addBox(-15, -9, -25, 30, 2, 2);
        for (float z : new float[]{ -30, -14, 2, 18 }) {        // four hoops
            frame.addBox(-14.5F, -39, z, 1, 15, 1.5F)
                .addBox(13.5F, -39, z, 1, 15, 1.5F)
                .addBox(-13.5F, -41.5F, z, 27, 1.5F, 1.5F);
        }
        frame.texOffs(0, IRON)
            .addBox(-12.25F, -36.5F, 18.5F, 1.5F, 1.5F, 2.5F)     // the lanterns' hooks
            .addBox(10.75F, -36.5F, 18.5F, 1.5F, 1.5F, 2.5F)
            .addBox(-13.5F, -24.5F, -32.5F, 27, 0.5F, 0.5F)      // iron straps
            .addBox(-13.5F, -24.5F, 31.5F, 27, 0.5F, 0.5F);
        root.addOrReplaceChild("frame", frame, PartPose.ZERO);
        root.addOrReplaceChild("trim", box(CANVAS).addBox(-14.4F, -22.5F, -30, 0.4F, 1.5F, 60).addBox(14, -22.5F, -30, 0.4F, 1.5F, 60),
            PartPose.ZERO);
        // The canvas over the hoops: the sides, stepped in at the shoulders, and the top.
        root.addOrReplaceChild("canvas", box(CANVAS)
                .addBox(-15.1F, -38, -32, 0.6F, 14, 52)
                .addBox(14.5F, -38, -32, 0.6F, 14, 52)
                .addBox(-14.4F, -40.5F, -32, 0.9F, 2.5F, 52)
                .addBox(13.5F, -40.5F, -32, 0.9F, 2.5F, 52)
                .addBox(-13.5F, -42.1F, -32, 27, 0.6F, 52),
            PartPose.ZERO);
        wheel(root, "wheel_fl", -15.5F, 24, 6.5F, true);
        wheel(root, "wheel_fr", 15.5F, 24, 6.5F, true);
        wheel(root, "wheel_bl", -15.5F, -24, 8, true);
        wheel(root, "wheel_br", 15.5F, -24, 8, true);
        sack(root);
        return LayerDefinition.create(mesh, 256, 256);
    }

    /** One kind's parts, baked. */
    public record Parts(ModelPart frame, ModelPart trim, ModelPart canvas, ModelPart[] wheels, float[] radii, ModelPart sack) {
        public static Parts of(ModelPart root, WagonKind k) {
            String[] names = TradeWagonModel.wheels(k);
            ModelPart[] ws = new ModelPart[names.length];
            for (int i = 0; i < names.length; i++) ws[i] = root.getChild(names[i]);
            return new Parts(root.getChild("frame"), root.getChild("trim"), root.getChild("canvas"), ws, wheelRadii(k), root.getChild("sack"));
        }
    }
}
