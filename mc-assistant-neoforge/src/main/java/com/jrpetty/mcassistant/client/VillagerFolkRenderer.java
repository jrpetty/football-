package com.jrpetty.mcassistant.client;

import com.jrpetty.mcassistant.entity.AssistantEntity;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.VillagerModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Village folk ARE villagers.
 *
 * <p>They were rendered as the assistant is — a person in a coloured uniform —
 * which is right for a crew you hired and wrong for a settlement that grew on
 * its own. A village of ten in matching overalls reads as a work detail; a
 * village of ten villagers reads as a village. Same body, same walk, same
 * robes and same nose as anything else living in a settlement, with the
 * profession smock its trade actually calls for.
 *
 * <p>What the swap costs, said plainly: a villager's model has no arms that
 * hold things and no armour layer, so a folk's tool and its iron no longer
 * show on its body. The floating trade icon over its head stays, which was
 * always the thing you could actually read from across a field.
 */
public class VillagerFolkRenderer extends MobRenderer<VillageFolkEntity, VillagerModel<VillageFolkEntity>> {

    /** The body under everything: the plains villager's own robes. */
    private static final ResourceLocation BODY =
        ResourceLocation.withDefaultNamespace("textures/entity/villager/type/plains.png");

    /**
     * A vanilla profession smock per trade, in StationTask order. Chosen for
     * what the job IS rather than for the trades vanilla attaches to them: the
     * smelter wears the armourer's apron because that is who stands at a
     * furnace, the carrier wears the cartographer's because that is who knows
     * the routes, and an idle folk is a nitwit until it picks a trade.
     */
    private static final ResourceLocation[] PROFESSIONS = {
        profession("nitwit"),        // NONE
        profession("farmer"),        // FARM
        profession("fletcher"),      // WOOD
        profession("mason"),         // MINE
        profession("shepherd"),      // RANCH
        profession("weaponsmith"),   // GUARD
        profession("armorer"),       // SMELT
        profession("fisherman"),     // FISH
        profession("librarian"),     // STORE
        profession("cartographer"),  // HAUL
    };

    private static ResourceLocation profession(String name) {
        return ResourceLocation.withDefaultNamespace(
            "textures/entity/villager/profession/" + name + ".png");
    }

    private final ItemRenderer itemRenderer;

    public VillagerFolkRenderer(EntityRendererProvider.Context context) {
        super(context, new VillagerModel<>(context.bakeLayer(ModelLayers.VILLAGER)), 0.5F);
        this.itemRenderer = context.getItemRenderer();
        this.addLayer(new TradeSmockLayer(this));
    }

    @Override
    public ResourceLocation getTextureLocation(VillageFolkEntity entity) {
        return BODY;
    }

    /** The smock, drawn over the robes. */
    private static class TradeSmockLayer
            extends RenderLayer<VillageFolkEntity, VillagerModel<VillageFolkEntity>> {

        TradeSmockLayer(VillagerFolkRenderer parent) {
            super(parent);
        }

        @Override
        public void render(PoseStack pose, MultiBufferSource buffer, int packedLight,
                           VillageFolkEntity entity, float limbSwing, float limbSwingAmount,
                           float partialTick, float ageInTicks, float netHeadYaw, float headPitch) {
            if (entity.isInvisible()) return;
            int job = Math.floorMod(entity.clientJobOrdinal(), PROFESSIONS.length);
            renderColoredCutoutModel(getParentModel(), PROFESSIONS[job], pose, buffer,
                packedLight, entity, -1);
        }
    }

    /**
     * The trade, floating over its head. A villager cannot hold its tool up
     * for you, so this is the whole of what a settlement tells you at a
     * glance — and a barrier over somebody's head is the fastest way to spot
     * the one folk that has stopped.
     */
    @Override
    public void render(VillageFolkEntity entity, float entityYaw, float partialTick,
                       PoseStack pose, MultiBufferSource buffer, int packedLight) {
        super.render(entity, entityYaw, partialTick, pose, buffer, packedLight);

        ItemStack icon = jobIcon(entity);
        if (icon.isEmpty()) return;
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
