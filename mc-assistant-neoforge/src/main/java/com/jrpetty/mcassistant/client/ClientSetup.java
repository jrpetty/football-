package com.jrpetty.mcassistant.client;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.entity.AssistantEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.client.renderer.entity.layers.ItemInHandLayer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;

/**
 * Client wiring: render the assistant in the uniform of whatever trade it
 * works, WITH its worn armor and held items visible, float the tool of its
 * trade over its head while it's on the job, and hook up the screens.
 */
@EventBusSubscriber(modid = McAssistantMod.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public final class ClientSetup {

    private ClientSetup() {}

    @SubscribeEvent
    public static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(McAssistantMod.ASSISTANT.get(), AssistantRenderer::new);
    }

    @SubscribeEvent
    public static void onRegisterScreens(RegisterMenuScreensEvent event) {
        event.register(McAssistantMod.ASSISTANT_MENU.get(), AssistantScreen::new);
    }

    @SubscribeEvent
    public static void onRegisterKeys(net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent event) {
        event.register(com.jrpetty.mcassistant.client.voice.VoiceInput.TALK);
        event.register(AssistantTargeting.ORDERS);
        event.register(AssistantTargeting.ROSTER);
        event.register(AssistantTargeting.CREW);
    }

    public static class AssistantRenderer extends MobRenderer<AssistantEntity, HumanoidModel<AssistantEntity>> {

        /** One uniform per job, in StationTask order. A crew of ten should be
         *  readable from across a field without clicking anything. */
        private static final ResourceLocation[] UNIFORMS = {
            uniform("unassigned"), uniform("farmer"), uniform("lumberjack"),
            uniform("miner"), uniform("rancher"), uniform("guard"),
            uniform("smelter"), uniform("fisher"), uniform("storekeeper"),
            uniform("hauler"),
        };

        private static ResourceLocation uniform(String name) {
            return ResourceLocation.fromNamespaceAndPath(
                McAssistantMod.MODID, "textures/entity/uniform/" + name + ".png");
        }

        private final ItemRenderer itemRenderer;

        public AssistantRenderer(EntityRendererProvider.Context context) {
            super(context, new HumanoidModel<>(context.bakeLayer(ModelLayers.PLAYER)), 0.5F);
            this.itemRenderer = context.getItemRenderer();
            // Worn armor.
            this.addLayer(new HumanoidArmorLayer<>(
                this,
                new HumanoidModel<>(context.bakeLayer(ModelLayers.PLAYER_INNER_ARMOR)),
                new HumanoidModel<>(context.bakeLayer(ModelLayers.PLAYER_OUTER_ARMOR)),
                context.getModelManager()));
            // Held item(s).
            this.addLayer(new ItemInHandLayer<>(this, context.getItemInHandRenderer()));
        }

        @Override
        public ResourceLocation getTextureLocation(AssistantEntity entity) {
            return UNIFORMS[Math.floorMod(entity.clientJobOrdinal(), UNIFORMS.length)];
        }

        @Override
        public void render(AssistantEntity entity, float entityYaw, float partialTick,
                           PoseStack pose, MultiBufferSource buffer, int packedLight) {
            super.render(entity, entityYaw, partialTick, pose, buffer, packedLight);

            ItemStack icon = jobIcon(entity);
            if (icon.isEmpty()) return;
            // Close enough to be worth reading; far enough to spot a stuck bot
            // on the other side of the base.
            if (this.entityRenderDispatcher.distanceToSqr(entity) > 48 * 48) return;

            float bob = Mth.sin((entity.tickCount + partialTick) * 0.07F) * 0.045F;
            pose.pushPose();
            pose.translate(0.0F, entity.getBbHeight() + 0.95F + bob, 0.0F);
            pose.mulPose(this.entityRenderDispatcher.cameraOrientation());
            pose.mulPose(com.mojang.math.Axis.YP.rotationDegrees(180.0F));
            pose.scale(0.55F, 0.55F, 0.55F);
            this.itemRenderer.renderStatic(icon, ItemDisplayContext.FIXED,
                packedLight, OverlayTexture.NO_OVERLAY, pose, buffer, entity.level(), entity.getId());
            pose.popPose();
        }

        /** The tool of its trade while it works — or a barrier when it's stuck,
         *  which is the one thing you actually want to see from a distance. */
        private static ItemStack jobIcon(AssistantEntity entity) {
            AssistantEntity.StationTask job =
                AssistantEntity.StationTask.byOrdinal(entity.clientJobOrdinal());
            if (job == AssistantEntity.StationTask.NONE) return ItemStack.EMPTY;
            String status = entity.clientStatus();
            if (status.startsWith("Needs") || status.startsWith("Out of")) {
                return new ItemStack(Items.BARRIER);
            }
            return new ItemStack(switch (job) {
                case FARM -> Items.WHEAT;
                case WOOD -> Items.IRON_AXE;
                case MINE -> Items.IRON_PICKAXE;
                case RANCH -> Items.SHEARS;
                case GUARD -> Items.IRON_SWORD;
                case SMELT -> Items.FURNACE;
                case FISH -> Items.FISHING_ROD;
                case STORE -> Items.CHEST;
                case HAUL -> Items.HOPPER;
                case NONE -> Items.AIR;
            });
        }
    }
}
