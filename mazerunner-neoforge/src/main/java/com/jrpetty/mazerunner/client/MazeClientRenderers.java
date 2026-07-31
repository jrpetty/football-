package com.jrpetty.mazerunner.client;

import com.jrpetty.mazerunner.ModEntities;

import net.minecraft.client.renderer.entity.SpiderRenderer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;

/**
 * Client-side entity renderers. The Griever borrows the vanilla spider
 * renderer/model as a mechanical-arachnid stand-in, so no custom model or
 * texture assets are needed.
 */
public final class MazeClientRenderers {

    private MazeClientRenderers() {}

    @SubscribeEvent
    public static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(ModEntities.GRIEVER.get(), SpiderRenderer::new);
    }
}
