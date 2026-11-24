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
import net.minecraft.world.InteractionHand;
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
public class PaRenderer extends MobRenderer<PlayerAssistantEntity, HumanoidModel<PlayerAssistantEntity>> {

    /** The outfits, in look order (textures/entity/assistant/look_N.png; the last three wear the game's own mob skins). */
    public static final String[] LOOK_NAMES = {"Explorer", "Engineer", "Ranger", "Scholar", "Knight", "Farmer", "Miner", "Pirate",
        "Butler", "Wizard", "Chef", "Lumberjack", "Scientist", "Plain", "Skeleton", "Zombie", "Creeper", "Enderman"};
    /** [pa33b] One line on each, for the Look tab. */
    public static final String[] LOOK_BLURBS = {"Goggles and a satchel; no sense of direction", "Overalls and a spanner in every pocket",
        "Hooded, green, often mistaken for a hedge", "Spectacles and purple robes", "Shining armour, creaky knees", "Straw hat and overalls",
        "Hard hat and lamp", "Stripes and a hat bigger than its plans", "Waistcoat and impeccable standards", "Robe and a very pointy hat",
        "Whites and a tall hat", "Flannel, lots of flannel", "Lab coat and goggles", "Just itself", "Built like a chopstick",
        "Not rotting: aged", "A hoodie. It promises not to explode", "Very tall, a bit nervous of water"};
    private static final ResourceLocation[] LOOKS = new ResourceLocation[LOOK_NAMES.length];
    static final int SKELETON = 14, ZOMBIE = 15, ENDERMAN = 17;

    static {
        for (int i = 0; i < LOOKS.length; i++) {
            LOOKS[i] = ResourceLocation.fromNamespaceAndPath(McAssistantMod.MODID, "textures/entity/assistant/look_" + i + ".png");
        }
        LOOKS[SKELETON] = ResourceLocation.withDefaultNamespace("textures/entity/skeleton/skeleton.png");
        LOOKS[ZOMBIE] = ResourceLocation.withDefaultNamespace("textures/entity/zombie/zombie.png");
        LOOKS[ENDERMAN] = ResourceLocation.withDefaultNamespace("textures/entity/enderman/enderman.png");
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
    /** [pa33b] The bodies: a player's shape for the outfits, the game's own for the skeleton, zombie and enderman. */
    private final HumanoidModel<PlayerAssistantEntity> playerBody, skeletonBody, zombieBody, endermanBody;

    public PaRenderer(EntityRendererProvider.Context ctx) {
        super(ctx, new PaAnims.Player(ctx.bakeLayer(ModelLayers.PLAYER)), 0.5F);   // [pa33c-2b] with gestures
        this.items = ctx.getItemRenderer();
        this.playerBody = this.model;
        this.skeletonBody = new PaAnims.Body(ctx.bakeLayer(ModelLayers.SKELETON));
        this.zombieBody = new PaAnims.Body(ctx.bakeLayer(ModelLayers.ZOMBIE));
        this.endermanBody = new PaAnims.Body(ctx.bakeLayer(ModelLayers.ENDERMAN));
        this.addLayer(new HumanoidArmorLayer<>(this,
            new HumanoidArmorModel<>(ctx.bakeLayer(ModelLayers.PLAYER_INNER_ARMOR)),
            new HumanoidArmorModel<>(ctx.bakeLayer(ModelLayers.PLAYER_OUTER_ARMOR)),
            ctx.getModelManager()));
        this.addLayer(new ItemInHandLayer<>(this, ctx.getItemInHandRenderer()));
        this.addLayer(new net.minecraft.client.renderer.entity.layers.ElytraLayer<>(this, ctx.getModelSet()));   // [pa33g] its wings, worn and spread
    }

    @Override
    public ResourceLocation getTextureLocation(PlayerAssistantEntity a) {
        return look(a.look());
    }

    @Override
    public void render(PlayerAssistantEntity a, float yaw, float partialTick, PoseStack pose, MultiBufferSource buffer, int light) {
        int lk = Math.floorMod(a.look(), LOOKS.length);
        this.model = lk == SKELETON ? skeletonBody : lk == ZOMBIE ? zombieBody : lk == ENDERMAN ? endermanBody : playerBody;
        HumanoidModel<PlayerAssistantEntity> m = getModel();
        ItemStack main = a.getMainHandItem(), off = a.getOffhandItem();
        HumanoidModel.ArmPose mainPose = pose(a, main, InteractionHand.MAIN_HAND), offPose = pose(a, off, InteractionHand.OFF_HAND);
        // [pa33g] two-handed poses (the bow drawn, a crossbow) take the other arm too, as the player's model does
        if (mainPose.isTwoHanded()) offPose = off.isEmpty() ? HumanoidModel.ArmPose.EMPTY : HumanoidModel.ArmPose.ITEM;
        boolean right = a.getMainArm() == net.minecraft.world.entity.HumanoidArm.RIGHT;
        m.rightArmPose = right ? mainPose : offPose;
        m.leftArmPose = right ? offPose : mainPose;
        m.crouching = a.isCrouching();
        m.setAllVisible(true);
        super.render(a, yaw, partialTick, pose, buffer, light);
        Minecraft mc = Minecraft.getInstance();
        if (mc.options.hideGui || a.isInvisible() || PaScreen.drawingPortrait) return;
        double d2 = this.entityRenderDispatcher.distanceToSqr(a);
        String said = PaClient.bubble(a.getId(), a.level().getGameTime());
        // its words are in the open panel already: no bubble filling the view behind it
        // [pa33c-2b] its words are drawn on the screen (PaBubbles), kept inside it; the plate stands aside meanwhile
        if (said != null && d2 < 32 * 32 && !(mc.screen instanceof PaScreen)) return;
        if (d2 < 24 * 24) plate(a, pose, buffer, light, d2);
    }

    /** [pa33g] How an arm holds what's in that hand, as the player's renderer decides it: a bow being drawn (both arms up,
     *  the bow bending as the item is used), a crossbow spanned or loaded, a raised shield, or just held. */
    static HumanoidModel.ArmPose pose(PlayerAssistantEntity a, ItemStack held, InteractionHand hand) {
        if (held.isEmpty()) return HumanoidModel.ArmPose.EMPTY;
        boolean using = a.isUsingItem() && a.getUsedItemHand() == hand && a.getUseItemRemainingTicks() > 0;
        if (using) {
            net.minecraft.world.item.UseAnim anim = held.getUseAnimation();
            if (anim == net.minecraft.world.item.UseAnim.BOW) return HumanoidModel.ArmPose.BOW_AND_ARROW;
            if (anim == net.minecraft.world.item.UseAnim.CROSSBOW) return HumanoidModel.ArmPose.CROSSBOW_CHARGE;
            if (anim == net.minecraft.world.item.UseAnim.BLOCK) return HumanoidModel.ArmPose.BLOCK;
            if (anim == net.minecraft.world.item.UseAnim.SPYGLASS) return HumanoidModel.ArmPose.SPYGLASS;
            if (anim == net.minecraft.world.item.UseAnim.SPEAR) return HumanoidModel.ArmPose.THROW_SPEAR;   // [pa35] a trident raised to throw
        }
        if (held.getItem() instanceof net.minecraft.world.item.CrossbowItem && net.minecraft.world.item.CrossbowItem.isCharged(held)) return HumanoidModel.ArmPose.CROSSBOW_HOLD;
        if (held.getItem() instanceof BowItem && a.isAggressive() && hand == InteractionHand.MAIN_HAND) return HumanoidModel.ArmPose.BOW_AND_ARROW;
        return HumanoidModel.ArmPose.ITEM;
    }

    /** [pa33g] Gliding on an elytra: the body laid out along its flight and rolled into its turns, as a player's is. */
    @Override
    protected void setupRotations(PlayerAssistantEntity a, PoseStack pose, float bob, float yaw, float partialTick, float scale) {
        super.setupRotations(a, pose, bob, yaw, partialTick, scale);
        if (!a.isFallFlying()) return;
        float t = a.getFallFlyingTicks() + partialTick;
        float lay = net.minecraft.util.Mth.clamp(t * t / 100.0F, 0.0F, 1.0F);
        pose.mulPose(com.mojang.math.Axis.XP.rotationDegrees(lay * (-90.0F - a.getViewXRot(partialTick))));
        net.minecraft.world.phys.Vec3 look = a.getViewVector(partialTick), move = a.getDeltaMovement();
        double mm = move.horizontalDistanceSqr(), ll = look.horizontalDistanceSqr();
        if (mm > 0.0 && ll > 0.0) {
            double cos = (move.x * look.x + move.z * look.z) / Math.sqrt(mm * ll);
            double side = move.x * look.z - move.z * look.x;
            pose.mulPose(com.mojang.math.Axis.YP.rotation((float) (Math.signum(side) * Math.acos(Math.min(1.0, Math.max(-1.0, cos))))));
        }
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
        if (a.mute() > 0) owner += a.mute() == 2 ? "  \u00B7 quiet" : "  \u00B7 no jokes";      // [pa33b] hushed, on its plate
        String[] st = a.state().split("\\|", -1);
        String icon = st.length > 0 ? st[0] : "";
        pose.pushPose();
        pose.translate(0.0F, a.getBbHeight() + 0.55F, 0.0F);
        pose.mulPose(this.entityRenderDispatcher.cameraOrientation());
        float near = (float) Math.max(0.5, Math.min(1.0, Math.sqrt(d2) / 5.0));   // smaller right up close, never filling the view
        pose.scale(0.025F * near, -0.025F * near, 0.025F * near);
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

}
