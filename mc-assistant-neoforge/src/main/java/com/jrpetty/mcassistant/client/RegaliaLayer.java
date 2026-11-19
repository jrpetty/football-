package com.jrpetty.mcassistant.client;

import com.jrpetty.mcassistant.AnimConfig;
import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.entity.FolkAction;
import com.jrpetty.mcassistant.entity.Regalia;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.DyeColor;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import org.joml.Matrix4f;

import javax.annotation.Nullable;

/**
 * [regalia19] A town's ruler, dressed for it (entity/Regalia): the crown, the cape and the sceptre of a monarch, the
 * coronet of its house, the chain of office of an elected leader, the elder's circlet and staff, the chief's plumes —
 * on a model of their own (RegaliaModel) in the town's two colours, in three passes over every ruler in sight (its cloth,
 * its second colour, the gold), each one batch for all of them (FolkBatch). The robe, the coat, the hose under them are
 * baked into the folk's own picture (FolkFaces.dressed); drawn here only while that picture waits its turn.
 *
 * <p>The sceptre and the staff are carried in the hand it does not work with, brought forward (FolkModel), whenever that
 * hand is free and it is standing about, talking, listening or leading; put away while it works, sits or rides. And a
 * little sign over its head says what it is from across the square (mark), with the other marks (AnimConfig.marks).
 */
public class RegaliaLayer extends RenderLayer<VillageFolkEntity, FolkModel> {

    private static final ResourceLocation CLOTH = texture("regalia_cloth");
    private static final ResourceLocation ACCENT = texture("regalia_accent");
    private static final ResourceLocation FIXED = texture("regalia_fixed");
    private static final ResourceLocation MARKS = texture("regalia_marks");
    private static final Regalia.Kind[] KINDS = Regalia.Kind.values();
    private static final ResourceLocation[] DRESS = new ResourceLocation[KINDS.length];
    private static final ResourceLocation[] DRESS_DYE = new ResourceLocation[KINDS.length];

    static {
        for (Regalia.Kind k : KINDS) {
            String d = dressOf(k);
            if (d == null) continue;
            DRESS[k.ordinal()] = texture("regalia_dress_" + d);
            DRESS_DYE[k.ordinal()] = texture("regalia_dress_" + d + "_dye");
        }
    }

    private final RegaliaModel model;

    public RegaliaLayer(RenderLayerParent<VillageFolkEntity, FolkModel> parent, RegaliaModel model) {
        super(parent);
        this.model = model;
    }

    private static ResourceLocation texture(String name) {
        return ResourceLocation.fromNamespaceAndPath(McAssistantMod.MODID, "textures/entity/folk/" + name + ".png");
    }

    /** The robe each kind wears under its regalia (regalia_dress_<name>), or null: a councillor wears its own clothes. */
    @Nullable
    public static String dressOf(Regalia.Kind k) {
        return switch (k) {
            case MONARCH -> "monarch";
            case ROYAL -> "royal";
            case ELECTED -> "elected";
            case ELDER -> "elder";
            case CHIEF -> "chief";
            default -> null;
        };
    }

    /** A dye's colour for the cloth (a black one lifted a little, so its folds still show). */
    public static int rgb(int dye) {
        int c = DyeColor.byId(Math.max(0, Math.min(15, dye))).getTextureDiffuseColor() & 0xFFFFFF;
        return dye == DyeColor.BLACK.getId() ? 0x34323E : c;
    }

    /** What its robe adds to its baked picture (FolkFaces.dressed): the kind and its colour, or 0 for none. */
    public static int baked(VillageFolkEntity folk) {
        int p = folk.clientRegalia();
        Regalia.Kind k = Regalia.kindOf(p);
        return dressOf(k) == null ? 0 : k.ordinal() | Regalia.mainOf(p) << 4;
    }

    /**
     * The hand its sceptre or staff is in just now (RegaliaModel.RIGHT or LEFT), or RegaliaModel.NO_HAND: the hand it does
     * not work with, while that hand is empty and it stands about, talks, listens, leads or preaches.
     */
    public static int holds(VillageFolkEntity folk) {
        Regalia.Kind k = Regalia.kindOf(folk.clientRegalia());
        if (k != Regalia.Kind.MONARCH && k != Regalia.Kind.ELDER && k != Regalia.Kind.CHIEF) return RegaliaModel.NO_HAND;
        if (folk.isBaby() || folk.isPassenger() || folk.getPose() != Pose.STANDING || !folk.getOffhandItem().isEmpty()) return RegaliaModel.NO_HAND;
        FolkAction.Act act = FolkAction.Act.of(folk.animSeen().act);
        boolean free = switch (act) {
            case NONE, TALK, LISTEN, WATCH, LEAD, PREACH, TEACH, THINK, WAVE, POINT, BECKON, SALUTE, BOW, LOST -> true;
            default -> false;
        };
        if (!free) return RegaliaModel.NO_HAND;
        return folk.getMainArm() == HumanoidArm.RIGHT ? RegaliaModel.LEFT : RegaliaModel.RIGHT;
    }

    @Override
    public void render(PoseStack pose, MultiBufferSource buffer, int light, VillageFolkEntity folk, float limbSwing, float limbSwingAmount,
                       float partialTick, float ageInTicks, float netHeadYaw, float headPitch) {
        int p = folk.clientRegalia();
        if (p == 0 || folk.isInvisible() || folk.isBaby()) return;
        Regalia.Kind k = Regalia.kindOf(p);
        if (k == Regalia.Kind.NONE) return;
        int main = 0xFF000000 | rgb(Regalia.mainOf(p)), accent = 0xFF000000 | rgb(Regalia.accentOf(p));
        // The robe, while the folk's picture with it baked in is still to be made.
        if (!folk.animSeen().baked && DRESS[k.ordinal()] != null) {
            renderColoredCutoutModel(getParentModel(), DRESS_DYE[k.ordinal()], pose, buffer, light, folk, main);
            renderColoredCutoutModel(getParentModel(), DRESS[k.ordinal()], pose, buffer, light, folk, -1);
        }
        // A helmet goes on instead of a crown; a breastplate over a collar and a chain (a cape still hangs behind it).
        boolean helmet = folk.getItemBySlot(EquipmentSlot.HEAD).getItem() instanceof ArmorItem;
        boolean breastplate = folk.getItemBySlot(EquipmentSlot.CHEST).getItem() instanceof ArmorItem;
        int hand = holds(folk);
        int bit = 1 << k.ordinal();
        model.follow(getParentModel(), limbSwingAmount, ageInTicks);
        int overlay = LivingEntityRenderer.getOverlayCoords(folk, 0.0F);
        for (int pass = 0; pass < 3; pass++) {
            VertexConsumer to = null;
            for (RegaliaModel.Part part : model.parts()) {
                if ((part.worn() & bit) == 0 || !(pass == 0 ? part.cloth() : pass == 1 ? part.accent() : part.fixed())) continue;
                if (part.hand() != RegaliaModel.NO_HAND && part.hand() != hand) continue;
                String from = part.name();
                if (helmet && part.from() == model.head()) continue;
                if (breastplate && (from.equals("ermine_collar") || from.equals("chain") || from.equals("medallion") || from.equals("mantle"))) continue;
                if (to == null) to = buffer.getBuffer(RenderType.entityCutoutNoCull(pass == 0 ? CLOTH : pass == 1 ? ACCENT : FIXED));
                RegaliaModel.draw(part, pose, to, light, overlay, pass == 0 ? main : pass == 1 ? accent : -1);
            }
        }
    }

    /** Which sign over the head (regalia_marks.png, left to right), by kind. */
    private static int markOf(Regalia.Kind k) {
        return switch (k) {
            case MONARCH -> 0;
            case ROYAL -> 1;
            case ELECTED -> 2;
            case ELDER, COUNCILLOR -> 3;
            case CHIEF -> 4;
            default -> -1;
        };
    }

    /**
     * The ruler's sign over its head, over its nameplate, from as far off as a nameplate can be read: a crown, a coronet,
     * a chain of office, a silver circlet, a plumed band. Put aside while one of its other marks (FolkMarks) shows, and
     * while it sleeps; never with the marks turned off (AnimConfig.marks).
     */
    public static void mark(VillageFolkEntity folk, PoseStack pose, MultiBufferSource buffer, EntityRenderDispatcher camera,
                            float partial, double far) {
        int p = folk.clientRegalia();
        if (p == 0 || far > 64 * 64 || folk.isInvisible() || folk.isBaby() || !AnimConfig.marks()) return;
        int cell = markOf(Regalia.kindOf(p));
        if (cell < 0 || folk.isSleeping() || folk.getPose() == Pose.SLEEPING) return;
        FolkAction.Seen s = folk.animSeen();
        float age = folk.tickCount + partial;
        float t = age - s.markAt;
        if (s.mark > 0 && t >= 0 && t <= FolkAction.MARK_TICKS) return;          // its other mark has the place just now
        boolean named = folk.isCustomNameVisible() && far < 64 * 64;
        float size = 0.2F + (float) Math.min(0.18, Math.max(0.0, Math.sqrt(far) - 8.0) * 0.006);   // it grows a little with distance
        boolean quest = far <= 48 * 48 && QuestClient.mark(folk.getId()) != 0;     // its "!" or "?" stands just over the name
        float y = folk.getBbHeight() + (named ? 0.78F : 0.5F) + (quest ? 0.14F : 0.0F) + (far >= 14 * 14 ? 0.62F : 0.0F)
            + Mth.sin(age * 0.06F) * 0.02F;
        pose.pushPose();
        pose.translate(0.0F, y, 0.0F);
        pose.mulPose(camera.cameraOrientation());
        Matrix4f m = pose.last().pose();
        VertexConsumer vc = buffer.getBuffer(RenderType.text(MARKS));
        float u0 = cell / 5.0F, u1 = (cell + 1) / 5.0F;
        int lit = LightTexture.FULL_BRIGHT;
        vc.addVertex(m, -size, 2 * size, 0.0F).setColor(255, 255, 255, 255).setUv(u1, 0.0F).setLight(lit);
        vc.addVertex(m, -size, 0.0F, 0.0F).setColor(255, 255, 255, 255).setUv(u1, 1.0F).setLight(lit);
        vc.addVertex(m, size, 0.0F, 0.0F).setColor(255, 255, 255, 255).setUv(u0, 1.0F).setLight(lit);
        vc.addVertex(m, size, 2 * size, 0.0F).setColor(255, 255, 255, 255).setUv(u0, 0.0F).setLight(lit);
        pose.popPose();
    }

    /** The model's place among the game's models. */
    @EventBusSubscriber(modid = McAssistantMod.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registrar {
        private Registrar() {}

        @SubscribeEvent
        public static void onRegisterLayers(EntityRenderersEvent.RegisterLayerDefinitions event) {
            event.registerLayerDefinition(RegaliaModel.LAYER, RegaliaModel::createBodyLayer);
        }
    }
}
