package com.jrpetty.mcassistant.client;

import com.jrpetty.mcassistant.McAssistantMod;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.RenderPlayerEvent;
import org.joml.Matrix4f;

import javax.annotation.Nullable;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [wpe] What the justice system looks like on the client: the irons (or rope, or the stocks' board) on a restrained
 * one's wrists, its arms held before it; the lead from its wrists to the hand of whoever walks it; and the jail HUD:
 * where, why, the days left and the court's time. Its own clicks to strike or use are not even sent while it is bound.
 */
@EventBusSubscriber(modid = McAssistantMod.MODID, value = Dist.CLIENT)
public final class JusticeClient {

    private JusticeClient() {}

    /** By entity id: {leader id or -1, kind}. */
    private static final Map<Integer, int[]> HELD = new ConcurrentHashMap<>();
    @Nullable private static CompoundTag hud;

    /** From the wire (JusticeNetwork). */
    public static void heard(CompoundTag t) {
        String kind = t.getString("kind");
        if (kind.equals("r")) {
            HELD.clear();
            for (Tag x : t.getList("list", Tag.TAG_COMPOUND)) {
                CompoundTag c = (CompoundTag) x;
                HELD.put(c.getInt("e"), new int[]{ c.getInt("l"), c.getInt("k") });
            }
        } else if (kind.equals("hud")) {
            hud = t.getBoolean("on") ? t.copy() : null;
        }
    }

    public static boolean restrained(int entityId) {
        return HELD.containsKey(entityId);
    }

    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut e) {
        HELD.clear();
        hud = null;
    }

    // ------------------------------------------------------------------ the arms held before it

    @SubscribeEvent
    public static void onRenderPlayer(RenderPlayerEvent.Pre e) {
        if (!HELD.containsKey(e.getEntity().getId())) return;
        HumanoidModel<?> m = e.getRenderer().getModel();
        m.rightArmPose = HumanoidModel.ArmPose.CROSSBOW_HOLD;
        m.leftArmPose = HumanoidModel.ArmPose.CROSSBOW_HOLD;
    }

    /** Bound: the attack and use keys do nothing (the server refuses them anyway). */
    @SubscribeEvent
    public static void onClick(InputEvent.InteractionKeyMappingTriggered e) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || !HELD.containsKey(mc.player.getId()) || mc.player.isCreative()) return;
        if (e.isAttack() || e.isUseItem() && !(mc.crosshairPickEntity instanceof com.jrpetty.mcassistant.entity.VillageFolkEntity)) {
            e.setCanceled(true);
            e.setSwingHand(false);
        }
    }

    // ------------------------------------------------------------------ the irons on the wrists

    /** Added to the players' renderers and the folk's (ClientSetup's bus): the irons drawn on both wrists. */
    @EventBusSubscriber(modid = McAssistantMod.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
    public static final class Layers {
        private Layers() {}

        @SubscribeEvent
        @SuppressWarnings({ "unchecked", "rawtypes" })
        public static void onAddLayers(EntityRenderersEvent.AddLayers e) {
            for (PlayerSkin.Model skin : e.getSkins()) {
                PlayerRenderer r = e.getSkin(skin);
                if (r != null) r.addLayer(new Cuffs<>(r));
            }
            var folk = e.getRenderer(McAssistantMod.VILLAGE_FOLK.get());
            if (folk instanceof LivingEntityRenderer lr && lr.getModel() instanceof HumanoidModel) lr.addLayer(new Cuffs(lr));
        }
    }

    static final class Cuffs<T extends LivingEntity, M extends EntityModel<T>> extends RenderLayer<T, M> {
        Cuffs(RenderLayerParent<T, M> parent) {
            super(parent);
        }

        @Override
        public void render(PoseStack ps, MultiBufferSource buf, int light, T e, float limb, float limbAmount, float partial, float age, float yaw, float pitch) {
            int[] h = HELD.get(e.getId());
            if (h == null || !(getParentModel() instanceof HumanoidModel<?> m) || e.isInvisible()) return;
            BlockState band = h[1] == 1 ? Blocks.BROWN_WOOL.defaultBlockState() : h[1] == 2 ? Blocks.OAK_PLANKS.defaultBlockState() : Blocks.IRON_BLOCK.defaultBlockState();
            cuff(ps, buf, light, m.rightArm, -1.0F / 16.0F, band, h[1]);
            cuff(ps, buf, light, m.leftArm, 1.0F / 16.0F, band, h[1]);
        }

        private static void cuff(PoseStack ps, MultiBufferSource buf, int light, ModelPart arm, float dx, BlockState band, int kind) {
            ps.pushPose();
            arm.translateAndRotate(ps);
            ps.translate(dx, 8.6F / 16.0F, 0.0F);
            float wide = kind == 2 ? 7.0F / 16.0F : 5.2F / 16.0F, tall = kind == 2 ? 3.0F / 16.0F : 2.2F / 16.0F;
            ps.scale(wide, tall, wide);
            ps.translate(-0.5F, -0.5F, -0.5F);
            Minecraft.getInstance().getBlockRenderer().renderSingleBlock(band, ps, buf, light, OverlayTexture.NO_OVERLAY);
            ps.popPose();
        }
    }

    // ------------------------------------------------------------------ the lead

    @SubscribeEvent
    public static void onStage(RenderLevelStageEvent e) {
        if (e.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES || HELD.isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        float pt = e.getPartialTick().getGameTimeDeltaPartialTick(false);
        Vec3 cam = e.getCamera().getPosition();
        PoseStack ps = e.getPoseStack();
        MultiBufferSource.BufferSource buf = mc.renderBuffers().bufferSource();
        boolean drew = false;
        for (Map.Entry<Integer, int[]> h : HELD.entrySet()) {
            int lead = h.getValue()[0];
            if (lead < 0) continue;
            Entity held = mc.level.getEntity(h.getKey()), by = mc.level.getEntity(lead);
            if (held == null || by == null) continue;
            if (held instanceof net.minecraft.world.entity.Mob mob && mob.isLeashed()) continue;   // the vanilla lead is drawn already
            Vec3 from = wrists(held, pt), to = hand(by, pt);
            if (from.distanceToSqr(to) > 24 * 24) continue;
            leash(ps, buf.getBuffer(RenderType.leash()), from.subtract(cam), to.subtract(from), light(mc, from), light(mc, to));
            drew = true;
        }
        if (drew) buf.endBatch(RenderType.leash());
    }

    private static Vec3 wrists(Entity e, float pt) {
        float yaw = (e instanceof LivingEntity l ? Mth.rotLerp(pt, l.yBodyRotO, l.yBodyRot) : e.getYRot()) * Mth.DEG_TO_RAD;
        Vec3 at = e.getPosition(pt);
        return at.add(-Mth.sin(yaw) * 0.45, e.getBbHeight() * 0.62, Mth.cos(yaw) * 0.45);
    }

    private static Vec3 hand(Entity e, float pt) {
        float yaw = (e instanceof LivingEntity l ? Mth.rotLerp(pt, l.yBodyRotO, l.yBodyRot) : e.getYRot()) * Mth.DEG_TO_RAD;
        Vec3 at = e.getPosition(pt);
        // the right hand: a little to its right and forward, at the waist
        return at.add(-Mth.cos(yaw) * 0.38 - Mth.sin(yaw) * 0.15, e.getBbHeight() * 0.48, -Mth.sin(yaw) * 0.38 + Mth.cos(yaw) * 0.15);
    }

    private static int[] light(Minecraft mc, Vec3 p) {
        BlockPos b = BlockPos.containing(p);
        return new int[]{ mc.level.getBrightness(LightLayer.BLOCK, b), mc.level.getBrightness(LightLayer.SKY, b) };
    }

    /** The lead as vanilla draws one: two twisted strips sagging between the ends. */
    private static void leash(PoseStack ps, VertexConsumer vc, Vec3 start, Vec3 d, int[] la, int[] lb) {
        ps.pushPose();
        ps.translate(start.x, start.y, start.z);
        Matrix4f m = ps.last().pose();
        float dx = (float) d.x, dy = (float) d.y, dz = (float) d.z;
        float inv = Mth.invSqrt(dx * dx + dz * dz) * 0.025F / 2.0F;
        float ox = dz * inv, oz = dx * inv;
        for (int i = 0; i <= 24; i++) pair(vc, m, dx, dy, dz, la, lb, 0.025F, 0.025F, ox, oz, i, false);
        for (int i = 24; i >= 0; i--) pair(vc, m, dx, dy, dz, la, lb, 0.025F, 0.0F, ox, oz, i, true);
        ps.popPose();
    }

    private static void pair(VertexConsumer vc, Matrix4f m, float x, float y, float z, int[] la, int[] lb, float yOff, float wy, float wx, float wz, int i, boolean rev) {
        float f = i / 24.0F;
        int light = LightTexture.pack((int) Mth.lerp(f, la[0], lb[0]), (int) Mth.lerp(f, la[1], lb[1]));
        float shade = i % 2 == (rev ? 1 : 0) ? 0.7F : 1.0F;
        float r = 0.5F * shade, g = 0.4F * shade, b = 0.3F * shade;
        float px = x * f;
        float py = y > 0.0F ? y * f * f : y - y * (1.0F - f) * (1.0F - f);
        py -= Mth.sin(f * Mth.PI) * 0.35F;                                   // a little more sag, for a slack lead
        float pz = z * f;
        vc.addVertex(m, px - wx, py + wy, pz + wz).setColor(r, g, b, 1.0F).setLight(light);
        vc.addVertex(m, px + wx, py + yOff - wy, pz - wz).setColor(r, g, b, 1.0F).setLight(light);
    }

    // ------------------------------------------------------------------ the jail HUD

    @SubscribeEvent
    public static void onHud(RenderGuiEvent.Post e) {
        CompoundTag h = hud;
        Minecraft mc = Minecraft.getInstance();
        if (h == null || mc.options.hideGui || mc.player == null) return;
        GuiGraphics g = e.getGuiGraphics();
        var font = mc.font;
        boolean remand = h.getBoolean("remand"), court = h.getBoolean("incourt");
        String title = court ? "IN COURT" : remand ? "HELD FOR TRIAL" : "IN JAIL";
        double day = Math.max(1, h.getDouble("day")), left = h.getDouble("left");
        String time = remand ? (h.getLong("court") > 0 ? "Court sits in " + clock(h.getLong("court")) : "Court sits soon")
            : String.format(Locale.ROOT, "%.1f days left  (%s)", left / day, clock((long) Math.ceil(left)));
        String reason = h.getString("reason");
        String where = h.getString("where") + (h.getBoolean("visiting") ? "  ·  visiting hours" : "");
        int w = Math.max(Math.max(font.width(title) + 20, font.width(time)), Math.max(font.width(reason), font.width(where))) + UiStyle.S3 * 2;
        w = Math.min(w, g.guiWidth() - 16);
        int x = 8, y = 8, hh = UiStyle.S2 * 2 + UiStyle.LINE * 4 + (remand ? 0 : 6);
        g.fill(x, y, x + w, y + hh, 0xD0101014);
        g.fill(x, y, x + 3, y + hh, remand ? UiStyle.WARNING_FILL : UiStyle.DANGER_FILL);
        int tx = x + UiStyle.S3, ty = y + UiStyle.S2;
        g.drawString(font, title, tx, ty, remand ? 0xFFFFD27A : 0xFFFF9C8A, true);
        g.drawString(font, time, tx, ty + UiStyle.LINE, UiStyle.TEXT_ON_DARK, true);
        g.drawString(font, font.plainSubstrByWidth(reason, w - UiStyle.S3 * 2), tx, ty + UiStyle.LINE * 2, 0xFFC9CED6, true);
        g.drawString(font, font.plainSubstrByWidth(where, w - UiStyle.S3 * 2), tx, ty + UiStyle.LINE * 3, 0xFF9AA3B2, true);
        if (!remand) {
            double frac = 1.0 - Math.min(1.0, left / Math.max(1, h.getDouble("total")));
            int bx = tx, by = ty + UiStyle.LINE * 4 + 1, bw = w - UiStyle.S3 * 2;
            g.fill(bx, by, bx + bw, by + 3, 0xFF3A3F47);
            g.fill(bx, by, bx + (int) (bw * frac), by + 3, UiStyle.SUCCESS_FILL);
        }
    }

    static String clock(long secs) {
        secs = Math.max(0, secs);
        return secs >= 60 ? (secs / 60) + "m " + String.format(Locale.ROOT, "%02d", secs % 60) + "s" : secs + "s";
    }

    // ------------------------------------------------------------------ tests (the screenshot rig)

    public static void setForTests(int entity, int leader, int kind) {
        HELD.put(entity, new int[]{ leader, kind });
    }

}
