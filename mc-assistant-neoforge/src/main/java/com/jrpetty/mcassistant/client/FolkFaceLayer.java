package com.jrpetty.mcassistant.client;

import com.jrpetty.mcassistant.AnimConfig;
import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.entity.FolkAction;
import com.jrpetty.mcassistant.entity.FolkAction.Act;
import com.jrpetty.mcassistant.entity.Looks;
import com.jrpetty.mcassistant.entity.Manner;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;

/**
 * [anim] A folk's face, alive: it blinks (each at its own pace, now and then twice), its eyes shut asleep, at prayer and
 * at a graveside and when it laughs; its mouth moves as it talks and opens to cheer, sing, laugh and yawn; it smiles when
 * it is happy and its mouth turns down when it is miserable; its brows knit in a quarrel and lift in grief; it blushes
 * by its sweetheart. Drawn over its own face (FolkFaces) as a skin of a box a hair bigger than its head, following the head
 * as the armour does (the folk's own picture is never redrawn: a blink costs one box). The lids are tinted to its own skin
 * and matched to the shape of its eyes (tools/anim_art.py paints them from folk_looks' eyes). Within the effects' range
 * only (AnimConfig), and not at all with faces turned off.
 */
public final class FolkFaceLayer extends RenderLayer<VillageFolkEntity, FolkModel> {

    public static final ModelLayerLocation LAYER = new ModelLayerLocation(
        ResourceLocation.fromNamespaceAndPath(McAssistantMod.MODID, "village_folk"), "face");

    private static final ResourceLocation[] SHUT = new ResourceLocation[6];
    private static final ResourceLocation SMILE = tex("smile"), FROWN = tex("frown"), OPEN = tex("open"), CROSS = tex("cross"),
        SORRY = tex("sorry"), BLUSH = tex("blush");

    static {
        for (int i = 0; i < SHUT.length; i++) SHUT[i] = tex("shut_" + i);
    }

    private static ResourceLocation tex(String name) {
        return ResourceLocation.fromNamespaceAndPath(McAssistantMod.MODID, "textures/entity/folk/face/" + name + ".png");
    }

    public static LayerDefinition create() {
        MeshDefinition mesh = new MeshDefinition();
        mesh.getRoot().addOrReplaceChild("face", CubeListBuilder.create().texOffs(0, 0)
            .addBox(-4.0F, -10.0F, -4.0F, 8.0F, 10.0F, 8.0F, new CubeDeformation(0.02F)), PartPose.ZERO);
        return LayerDefinition.create(mesh, 64, 32);
    }

    private final ModelPart face;

    public FolkFaceLayer(RenderLayerParent<VillageFolkEntity, FolkModel> parent, ModelPart root) {
        super(parent);
        this.face = root.getChild("face");
    }

    @Override
    public void render(PoseStack pose, MultiBufferSource buffer, int light, VillageFolkEntity folk, float limbSwing,
                       float limbSwingAmount, float partialTick, float ageInTicks, float netHeadYaw, float headPitch) {
        if (folk.isInvisible() || !AnimConfig.faces()) return;
        long look = folk.clientLook();
        if (!Looks.known(look)) return;                    // the old faces' eyes are not where these lids go
        int range = AnimConfig.range();
        if (net.minecraft.client.Minecraft.getInstance().getEntityRenderDispatcher().distanceToSqr(folk) > (double) range * range) return;
        if (!getParentModel().getHead().visible) return;
        FolkAction.Seen s = folk.animSeen();
        Act act = Act.of(s.act);
        int manner = folk.clientManner();
        int idle = Manner.idleOf(manner), posture = Manner.postureOf(manner);
        int t = folk.tickCount, id = folk.getId();
        // The eyes: a blink every three to five seconds, each folk at its own pace, now and then a double.
        int period = 64 + (folk.getUUID().hashCode() >>> 4 & 63);
        int phase = (t + id * 37) % period;
        boolean blink = phase < 3 || (id & 3) == 0 && phase >= 6 && phase < 8;
        boolean shut = folk.isSleeping() || blink || act == Act.PRAY || act == Act.SLEEP || idle == Manner.LAUGH
            || act == Act.MOURN && (t / 40 + id) % 3 != 0 || act == Act.DANCE && (t / 30 + id) % 4 == 0;
        // The mouth: moving with its words, open in a cheer, a song, a laugh, a yawn.
        boolean talking = act == Act.TALK || FolkTalkClient.saying(id, folk.level().getGameTime()) != null;
        boolean open = act == Act.CHEER || act == Act.PREACH || act == Act.LUTE || act == Act.PROTEST || idle == Manner.LAUGH
            || idle == Manner.YAWN || talking && ((t + id) / 3) % 3 != 0 || act == Act.QUARREL && (t / 4) % 2 == 0;
        boolean cross = act == Act.QUARREL || act == Act.PROTEST || idle == Manner.CROSSED;
        boolean sorry = act == Act.MOURN || act == Act.LOST || posture == Manner.SAD && !cross;
        boolean frown = !open && (cross || sorry);
        boolean smile = !open && !frown && (posture == Manner.HAPPY || act == Act.DANCE || act == Act.CLAP || act == Act.WAVE
            || act == Act.HANDSHAKE || act == Act.EAT && (t / 50) % 2 == 0);
        boolean blush = s.mark == FolkAction.Mark.HEART.ordinal() && ageInTicks - s.markAt < 100;
        if (!shut && !open && !cross && !sorry && !frown && !smile && !blush) return;
        ModelPart head = getParentModel().getHead();
        face.x = head.x;
        face.y = head.y;
        face.z = head.z;
        face.xRot = head.xRot;
        face.yRot = head.yRot;
        face.zRot = head.zRot;
        face.xScale = head.xScale;
        face.yScale = head.yScale;
        face.zScale = head.zScale;
        int skin = 0xFF000000 | FolkFaces.TONES[Math.max(0, Math.min(FolkFaces.TONES.length - 1, Looks.tone(look)))];
        if (shut) draw(pose, buffer, light, SHUT[Math.max(0, Math.min(5, Looks.eyeShape(look)))], skin);
        if (open) draw(pose, buffer, light, OPEN, -1);
        else if (frown) draw(pose, buffer, light, FROWN, -1);
        else if (smile) draw(pose, buffer, light, SMILE, -1);
        if (cross) draw(pose, buffer, light, CROSS, -1);
        else if (sorry) draw(pose, buffer, light, SORRY, -1);
        if (blush) draw(pose, buffer, light, BLUSH, -1);
    }

    private void draw(PoseStack pose, MultiBufferSource buffer, int light, ResourceLocation tex, int colour) {
        face.render(pose, buffer.getBuffer(RenderType.entityTranslucent(tex)), light, OverlayTexture.NO_OVERLAY, colour);
    }
}
