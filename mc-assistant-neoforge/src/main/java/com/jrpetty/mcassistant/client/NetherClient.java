package com.jrpetty.mcassistant.client;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import com.jrpetty.mcassistant.item.NetherItems;
import com.jrpetty.mcassistant.item.RunnersSatchelItem;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.CustomHeadLayer;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.item.ItemProperties;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;

/**
 * [nether] The client's side of the Nether runners' kit: the Runner's Satchel drawn full when there is something in it
 * (the "filled" property its model switches on), and the Gold Charm drawn on a folk's brow (CharmLayer) the way the
 * game draws it on a player's (its model's "head" view: a gold band round the head, the medallion at the front).
 */
@EventBusSubscriber(modid = McAssistantMod.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public final class NetherClient {

    private NetherClient() {}

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> ItemProperties.register(NetherItems.RUNNERS_SATCHEL.get(),
            ResourceLocation.fromNamespaceAndPath(McAssistantMod.MODID, "filled"),
            (stack, level, entity, seed) -> RunnersSatchelItem.count(stack) > 0 ? 1.0F : 0.0F));
    }

    /** The Gold Charm on a folk's brow: its model's head view, on the folk's head as it turns and nods. */
    static final class CharmLayer extends RenderLayer<VillageFolkEntity, FolkModel> {
        private final ItemInHandRenderer items;

        CharmLayer(RenderLayerParent<VillageFolkEntity, FolkModel> parent, ItemInHandRenderer items) {
            super(parent);
            this.items = items;
        }

        @Override
        public void render(PoseStack pose, MultiBufferSource buffer, int light, VillageFolkEntity folk, float limbSwing, float limbSwingAmount,
                           float partialTick, float ageInTicks, float netHeadYaw, float headPitch) {
            if (folk.isInvisible()) return;
            ItemStack head = folk.getItemBySlot(EquipmentSlot.HEAD);
            if (head.isEmpty() || !head.is(NetherItems.GOLD_CHARM.get())) return;
            pose.pushPose();
            getParentModel().getHead().translateAndRotate(pose);
            CustomHeadLayer.translateToHead(pose, false);
            items.renderItem(folk, head, ItemDisplayContext.HEAD, false, pose, buffer, light);
            pose.popPose();
        }
    }
}
