package com.jrpetty.mcassistant.client;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.entity.AssistantEntity;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.lwjgl.glfw.GLFW;

import javax.annotation.Nullable;
import java.util.List;

/**
 * Look at one of your assistants and press the orders key (R by default) to
 * take command of it. Look at nothing in particular and it picks the nearest
 * one you own, so the key always does something sensible.
 *
 * Deliberately NOT chat: orders are given by looking and pressing a key.
 */
@EventBusSubscriber(modid = McAssistantMod.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.GAME)
public final class AssistantTargeting {

    private AssistantTargeting() {}

    /** How far you can pick a bot out of the world by looking at it. */
    private static final double PICK_RANGE = 32.0;
    /** Fallback: nearest owned bot within this if you're not looking at one. */
    private static final double NEAREST_RANGE = 24.0;

    public static final KeyMapping ORDERS = new KeyMapping(
        "key.mc_assistant.orders", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_R,
        "key.categories.mc_assistant");

    public static final KeyMapping ROSTER = new KeyMapping(
        "key.mc_assistant.roster", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_G,
        "key.categories.mc_assistant");

    /** Manage the whole crew from anywhere: names, jobs, and orders. */
    public static final KeyMapping CREW = new KeyMapping(
        "key.mc_assistant.crew", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_SEMICOLON,
        "key.categories.mc_assistant");

    /** The plot book: every piece of staked ground, crewed by clicking. */
    public static final KeyMapping PLOTS = new KeyMapping(
        "key.mc_assistant.plots", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_B,
        "key.categories.mc_assistant");

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;

        while (ORDERS.consumeClick()) {
            AssistantEntity target = pick(mc.player);
            if (target == null) {
                mc.player.displayClientMessage(net.minecraft.network.chat.Component.literal(
                    "No assistant in sight — look at one, or stand nearer."), true);
            } else {
                mc.setScreen(new OrdersScreen(target));
            }
        }
        while (CREW.consumeClick()) {
            mc.setScreen(new CrewScreen());
        }
        while (PLOTS.consumeClick()) {
            // The screen opens when the server answers with the book — one
            // round trip, so it always opens showing the truth.
            PlotsClient.requestOpen();
        }
        while (ROSTER.consumeClick()) {
            // Whole-crew readout without walking to a Job Board.
            com.jrpetty.mcassistant.client.OrdersScreen.sendOrder(
                nearestOwned(mc.player, 512.0), com.jrpetty.mcassistant.AssistantActions.ROSTER);
        }
    }

    /** The assistant under the crosshair, else the nearest one you own. */
    @Nullable
    public static AssistantEntity pick(LocalPlayer player) {
        Vec3 eye = player.getEyePosition(1.0F);
        Vec3 look = player.getViewVector(1.0F);
        Vec3 end = eye.add(look.scale(PICK_RANGE));
        AABB sweep = player.getBoundingBox().expandTowards(look.scale(PICK_RANGE)).inflate(1.0D);
        EntityHitResult hit = ProjectileUtil.getEntityHitResult(
            player, eye, end, sweep,
            e -> e instanceof AssistantEntity,
            PICK_RANGE * PICK_RANGE);
        if (hit != null && hit.getEntity() instanceof AssistantEntity looked) return looked;
        return nearestOwned(player, NEAREST_RANGE);
    }

    @Nullable
    public static AssistantEntity nearestOwned(LocalPlayer player, double range) {
        // NB: no owner filter here. Ownership lives on the server, so on the
        // client isOwner() is always false and this returned nothing at all -
        // which is exactly why the orders key appeared to do nothing. The
        // server re-checks ownership before acting on any order.
        List<AssistantEntity> near = player.level().getEntitiesOfClass(
            AssistantEntity.class, player.getBoundingBox().inflate(range),
            a -> a.isAlive());
        AssistantEntity best = null;
        double bestDist = Double.MAX_VALUE;
        for (AssistantEntity a : near) {
            double d = a.distanceToSqr(player);
            if (d < bestDist) {
                bestDist = d;
                best = a;
            }
        }
        return best;
    }
}
