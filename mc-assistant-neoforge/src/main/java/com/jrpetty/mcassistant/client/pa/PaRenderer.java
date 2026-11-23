package com.jrpetty.mcassistant.client.pa;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.client.TextCache;
import com.jrpetty.mcassistant.pa.PlayerAssistantEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.model.HumanoidArmorModel;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.client.renderer.entity.layers.ItemInHandLayer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import org.joml.Matrix4f;

import java.util.List;

/**
 * [pa33] How a Player AI Assistant looks: a player's shape in one of its own outfits (chosen in its panel), wearing
 * the armour and holding the tools it really has, with the player's own walk, swing and draw. Over its head, a plate
 * of its own (its name with the tool of what it is doing, its player's name, a health line) and, when it speaks, a
 * bubble that reads well on camera. Not the folk's model, plates or bubbles: it is not one of them.
 */
public class PaRenderer extends MobRenderer<PlayerAssistantEntity, PlayerModel<PlayerAssistantEntity>> {

    /** The outfits, in look order (textures/entity/assistant/look_N.png). */
    public static final String[] LOOK_NAMES = {"Explorer", "Engineer", "Ranger", "Scholar", "Knight", "Gardener", "Miner", "Sailor"};
    private static final ResourceLocation[] LOOKS = new ResourceLocation[LOOK_NAMES.length];

    static {
        for (int i = 0; i < LOOKS.length; i++) {
            LOOKS[i] = ResourceLocation.fromNamespaceAndPath(McAssistantMod.MODID, "textures/entity/assistant/look_" + i + ".png");
        }
    }

    public static ResourceLocation look(int i) {
        return LOOKS[Math.floorMod(i, LOOKS.length)];
    }

    @EventBusSubscriber(modid = McAssistantMod.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
    public static final class Setup {
        private Setup() {}

        @SubscribeEvent
        public static void onRenderers(EntityRenderersEvent.RegisterRenderers event) {
            event.registerEntityRenderer(McAssistantMod.PLAYER_ASSISTANT.get(), PaRenderer::new);
        }
    }

    private static final int PLATE_BG = 0x90102A2E, BUBBLE_BG = 0xD8143238, INK = 0xFFFFFFFF, OWNER_INK = 0xFF8FE3D3;

    private final net.minecraft.client.renderer.entity.ItemRenderer items;

    public PaRenderer(EntityRendererProvider.Context ctx) {
        super(ctx, new PlayerModel<>(ctx.bakeLayer(ModelLayers.PLAYER), false), 0.5F);
        this.items = ctx.getItemRenderer();
        this.addLayer(new HumanoidArmorLayer<>(this,
            new HumanoidArmorModel<>(ctx.bakeLayer(ModelLayers.PLAYER_INNER_ARMOR)),
            new HumanoidArmorModel<>(ctx.bakeLayer(ModelLayers.PLAYER_OUTER_ARMOR)),
            ctx.getModelManager()));
        this.addLayer(new ItemInHandLayer<>(this, ctx.getItemInHandRenderer()));
    }

    @Override
    public ResourceLocation getTextureLocation(PlayerAssistantEntity a) {
        return look(a.look());
    }

    @Override
    public void render(PlayerAssistantEntity a, float yaw, float partialTick, PoseStack pose, MultiBufferSource buffer, int light) {
        PlayerModel<PlayerAssistantEntity> m = getModel();
        ItemStack main = a.getMainHandItem(), off = a.getOffhandItem();
        m.rightArmPose = main.isEmpty() ? HumanoidModel.ArmPose.EMPTY
            : main.getItem() instanceof BowItem && a.isAggressive() ? HumanoidModel.ArmPose.BOW_AND_ARROW : HumanoidModel.ArmPose.ITEM;
        m.leftArmPose = off.isEmpty() ? HumanoidModel.ArmPose.EMPTY
            : off.is(net.minecraft.world.item.Items.SHIELD) && a.isAggressive() ? HumanoidModel.ArmPose.BLOCK : HumanoidModel.ArmPose.ITEM;
        m.crouching = a.isCrouching();
        m.setAllVisible(true);
        super.render(a, yaw, partialTick, pose, buffer, light);
        Minecraft mc = Minecraft.getInstance();
        if (mc.options.hideGui || a.isInvisible() || PaScreen.drawingPortrait) return;
        double d2 = this.entityRenderDispatcher.distanceToSqr(a);
        String said = PaClient.bubble(a.getId(), a.level().getGameTime());
        // its words are in the open panel already: no bubble filling the view behind it
        if (said != null && d2 < 32 * 32 && !(mc.screen instanceof PaScreen)) bubble(a, said, pose, buffer, d2);
        else if (d2 < 24 * 24) plate(a, pose, buffer, light, d2);
    }

    @Override
    protected boolean shouldShowName(PlayerAssistantEntity a) {
        return false;                                  // its own plate, below
    }

    @Override
    protected void scale(PlayerAssistantEntity a, PoseStack pose, float partialTick) {
        pose.scale(0.9375F, 0.9375F, 0.9375F);        // a player's own size on screen
    }

    /** Its name (with what it is doing beside it), its player's, and a health line. */
    private void plate(PlayerAssistantEntity a, PoseStack pose, MultiBufferSource buffer, int light, double d2) {
        Font font = getFont();
        String name = a.shownName();
        String owner = a.ownerName().isEmpty() ? "Player AI Assistant" : a.ownerName() + "'s assistant";
        String[] st = a.state().split("\\|", -1);
        String icon = st.length > 0 ? st[0] : "";
        pose.pushPose();
        pose.translate(0.0F, a.getBbHeight() + 0.55F, 0.0F);
        pose.mulPose(this.entityRenderDispatcher.cameraOrientation());
        pose.scale(0.025F, -0.025F, 0.025F);
        Matrix4f mx = pose.last().pose();
        float nw = font.width(name);
        float x = -nw / 2.0F + 6;
        // drawn as the game draws a name tag: the panel with a ghost of the words first, then the words over it
        font.drawInBatch(name, x, 0, 0x20FFFFFF, false, mx, buffer, Font.DisplayMode.SEE_THROUGH, PLATE_BG, LightTexture.FULL_BRIGHT);
        font.drawInBatch(name, x, 0, INK, false, mx, buffer, Font.DisplayMode.NORMAL, 0, LightTexture.FULL_BRIGHT);
        pose.pushPose();
        pose.scale(0.75F, 0.75F, 0.75F);
        float ow = font.width(owner);
        Matrix4f om = pose.last().pose();
        font.drawInBatch(owner, -ow / 2.0F, 17, 0x20FFFFFF, false, om, buffer, Font.DisplayMode.SEE_THROUGH, PLATE_BG, LightTexture.FULL_BRIGHT);
        font.drawInBatch(owner, -ow / 2.0F, 17, OWNER_INK, false, om, buffer, Font.DisplayMode.NORMAL, 0, LightTexture.FULL_BRIGHT);
        pose.popPose();
        // health: a thin bar under the name
        float frac = Math.max(0, Math.min(1, a.getHealth() / a.getMaxHealth()));
        int col = frac > 0.6F ? 0xFF5FD068 : frac > 0.3F ? 0xFFE8C04A : 0xFFE0523C;
        bar(pose, buffer, -nw / 2.0F, 9.5F, nw + 12, frac, col);
        // what it is doing, as the tool of it, left of the name
        if (!icon.isEmpty() && !icon.equals("follow") && !icon.equals("stay")) {
            pose.pushPose();
            pose.translate(-nw / 2.0F - 1, 4, -0.1F);
            pose.scale(9.0F, -9.0F, 9.0F);
            pose.mulPose(com.mojang.math.Axis.YP.rotationDegrees(180.0F));
            items.renderStatic(PaIcons.of(icon), ItemDisplayContext.GUI, LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY, pose, buffer, a.level(), a.getId());
            pose.popPose();
        }
        pose.popPose();
    }

    private static void bar(PoseStack pose, MultiBufferSource buffer, float x, float y, float w, float frac, int col) {
        var vc = buffer.getBuffer(net.minecraft.client.renderer.RenderType.textBackground());
        Matrix4f m = pose.last().pose();
        quad(vc, m, x, y, x + w, y + 2, 0xC0101010);
        quad(vc, m, x, y, x + w * frac, y + 2, col);
    }

    private static void quad(com.mojang.blaze3d.vertex.VertexConsumer vc, Matrix4f m, float x0, float y0, float x1, float y1, int argb) {
        int l = LightTexture.FULL_BRIGHT;
        vc.addVertex(m, x0, y0, -0.01F).setColor(argb).setLight(l);
        vc.addVertex(m, x0, y1, -0.01F).setColor(argb).setLight(l);
        vc.addVertex(m, x1, y1, -0.01F).setColor(argb).setLight(l);
        vc.addVertex(m, x1, y0, -0.01F).setColor(argb).setLight(l);
    }

    /** What it said, over its head: dark glass, white words, wrapped, its name first. */
    private void bubble(PlayerAssistantEntity a, String text, PoseStack pose, MultiBufferSource buffer, double d2) {
        Font font = getFont();
        List<FormattedCharSequence> lines = TextCache.splitPlain(font, text, 170);
        if (lines.size() > 5) lines = lines.subList(0, 5);
        pose.pushPose();
        pose.translate(0.0F, a.getBbHeight() + 0.6F + lines.size() * 0.25F, 0.0F);
        pose.mulPose(this.entityRenderDispatcher.cameraOrientation());
        // right up close it shrinks a little, so it never fills the camera
        float near = (float) Math.max(0.55, Math.min(1.0, Math.sqrt(d2) / 5.0));
        pose.scale(0.025F * near, -0.025F * near, 0.025F * near);
        Matrix4f mx = pose.last().pose();
        int widest = 0;
        for (FormattedCharSequence l : lines) widest = Math.max(widest, font.width(l));
        var vc = buffer.getBuffer(net.minecraft.client.renderer.RenderType.textBackground());
        float half = widest / 2.0F + 4;
        quad(vc, mx, -half, -13, half, lines.size() * 10 + 2, BUBBLE_BG);
        quad(vc, mx, -half, -13, -half + 2, lines.size() * 10 + 2, 0xFF2BB5A0);
        String name = a.shownName();
        font.drawInBatch(Component.literal(name), -font.width(name) / 2.0F, -11, 0xFF8FE3D3, false, mx, buffer, Font.DisplayMode.NORMAL, 0, LightTexture.FULL_BRIGHT);
        for (int i = 0; i < lines.size(); i++) {
            FormattedCharSequence line = lines.get(i);
            float lx = -font.width(line) / 2.0F;
            font.drawInBatch(line, lx, i * 10.0F + 1, INK, false, mx, buffer, Font.DisplayMode.NORMAL, 0, LightTexture.FULL_BRIGHT);
        }
        pose.popPose();
    }
}
