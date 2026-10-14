package com.jrpetty.mcassistant.client;

import com.jrpetty.mcassistant.entity.TradeWagon;
import com.jrpetty.mcassistant.entity.WagonKind;
import com.jrpetty.mcassistant.item.WorkItems;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.Mth;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LanternBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * [wagons] The trade wagon drawn (TradeWagonModel): its frame, its canvas and trim in the town's colours, its wheels
 * turning with the ground it covers, its lanterns lit at dusk, and its load: what it carries, shown as what it is. Logs
 * lie stacked, wheat goes as bales, ore, coal and ingots in open barrels with a piece on top, grain and roots in
 * sacks, stone and other blocks as blocks, everything else in open shipping crates with the goods showing; the load
 * stands two rows across and two high along the bed, and rises and falls with how full the wagon is.
 *
 * <p>The load's layout is worked out only when the wagon's few words for its load change, and kept on the wagon
 * (TradeWagon.clientLoad): nothing is allocated per frame.
 */
public class TradeWagonRenderer extends EntityRenderer<TradeWagon> {

    private final TradeWagonModel.Parts[] parts = new TradeWagonModel.Parts[WagonKind.values().length];
    private final BlockRenderDispatcher blocks;
    private final ItemRenderer items;

    public TradeWagonRenderer(EntityRendererProvider.Context ctx) {
        super(ctx);
        for (WagonKind k : WagonKind.values()) parts[k.ordinal()] = TradeWagonModel.Parts.of(ctx.bakeLayer(TradeWagonModel.layerFor(k)), k);
        this.blocks = ctx.getBlockRenderDispatcher();
        this.items = ctx.getItemRenderer();
        this.shadowRadius = 1.2F;
        this.shadowStrength = 0.6F;
    }

    @Override
    public ResourceLocation getTextureLocation(TradeWagon w) {
        return TradeWagonModel.TEXTURE;
    }

    @Override
    public void render(TradeWagon w, float entityYaw, float pt, PoseStack pose, MultiBufferSource buffers, int light) {
        WagonKind k = w.kind();
        TradeWagonModel.Parts p = parts[k.ordinal()];
        float yaw = Mth.rotLerp(pt, w.yRotO, w.getYRot());
        float pitch = Mth.lerp(pt, w.xRotO, w.getXRot());
        pose.pushPose();
        pose.mulPose(Axis.YP.rotationDegrees(-yaw));
        pose.mulPose(Axis.XP.rotationDegrees(pitch));
        renderLoad(w, k, p, pose, buffers, light);
        renderLamps(w, k, pose, buffers, light);
        pose.scale(-1.0F, -1.0F, 1.0F);
        VertexConsumer vc = buffers.getBuffer(RenderType.entityCutoutNoCull(TradeWagonModel.TEXTURE));
        int overlay = OverlayTexture.NO_OVERLAY;
        p.frame().render(pose, vc, light, overlay);
        p.trim().render(pose, vc, light, overlay, 0xFF000000 | w.trimColour());
        if (k.covered) p.canvas().render(pose, vc, light, overlay, dyed(w.canvasColour()));
        float turn = Mth.lerp(pt, w.wheelRotO, w.wheelRot);
        for (int i = 0; i < p.wheels().length; i++) {
            p.wheels()[i].xRot = -turn * (7.2F / p.radii()[i]);             // a smaller wheel turns faster
            p.wheels()[i].render(pose, vc, light, overlay);
        }
        pose.popPose();
        super.render(w, entityYaw, pt, pose, buffers, light);
    }

    /** A canvas dyed in this colour: the colour, softened a quarter towards the cloth's own, so a black town's still reads. */
    private static int dyed(int rgb) {
        int r = (rgb >> 16) & 255, g = (rgb >> 8) & 255, b = rgb & 255;
        r = (r * 3 + 236) / 4;
        g = (g * 3 + 228) / 4;
        b = (b * 3 + 210) / 4;
        return 0xFF000000 | r << 16 | g << 8 | b;
    }

    // ------------------------------------------------------------------ the lanterns

    private static final BlockState LANTERN = Blocks.LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, true);

    /** Its lanterns, hung from the post or the hoop, lit (full bright) from dusk to dawn and in a storm. */
    private void renderLamps(TradeWagon w, WagonKind k, PoseStack pose, MultiBufferSource buffers, int light) {
        float[][] at = TradeWagonModel.lamps(k);
        if (at.length == 0) return;
        long t = w.level().getDayTime() % 24000L;
        boolean lit = t > 12600 && t < 23400 || w.level().isThundering();
        int l = lit ? LightTexture.FULL_BRIGHT : light;
        for (float[] a : at) {
            pose.pushPose();
            pose.translate(a[0], a[1] - 0.45F, a[2]);
            pose.scale(0.5F, 0.5F, 0.5F);
            pose.translate(-0.5F, 0.0F, -0.5F);
            blocks.renderSingleBlock(LANTERN, pose, buffers, l, OverlayTexture.NO_OVERLAY);
            pose.popPose();
        }
    }

    // ------------------------------------------------------------------ the load

    /** How a good is carried, and so drawn. */
    enum Style { CRATE, BARREL, BALE, LOGS, BLOCK, SACK }

    /** The rules, in order: the first that fits a good says how it goes. */
    private record Rule(Predicate<ItemStack> fits, Style style) {}

    private static final List<Rule> RULES = List.of(
        new Rule(s -> s.is(WorkItems.SHIPPING_CRATE_ITEM.get()), Style.CRATE),
        new Rule(s -> s.is(ItemTags.LOGS), Style.LOGS),
        new Rule(s -> s.is(Items.WHEAT) || s.is(Items.HAY_BLOCK), Style.BALE),
        new Rule(TradeWagonRenderer::bulk, Style.BARREL),
        new Rule(TradeWagonRenderer::grain, Style.SACK),
        new Rule(TradeWagonRenderer::solidBlock, Style.BLOCK));

    private static boolean bulk(ItemStack s) {
        String p = BuiltInRegistries.ITEM.getKey(s.getItem()).getPath();
        return p.contains("ingot") || p.startsWith("raw_") || p.endsWith("_ore") || p.contains("coal") || p.contains("nugget")
            || p.equals("redstone") || p.equals("lapis_lazuli") || p.equals("diamond") || p.equals("emerald") || p.equals("quartz")
            || p.equals("flint") || p.equals("clay_ball") || p.equals("gravel") || p.equals("sand");
    }

    private static boolean grain(ItemStack s) {
        String p = BuiltInRegistries.ITEM.getKey(s.getItem()).getPath();
        return p.contains("seeds") || s.is(Items.POTATO) || s.is(Items.CARROT) || s.is(Items.BEETROOT) || s.is(Items.SUGAR)
            || s.is(Items.WHITE_WOOL) || p.contains("flour") || s.is(Items.COCOA_BEANS) || s.is(Items.BONE_MEAL);
    }

    private static boolean solidBlock(ItemStack s) {
        return s.getItem() instanceof BlockItem bi && bi.getBlock().defaultBlockState().isSolidRender(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
    }

    /** The load laid out: what each place in the bed holds, how it is drawn, where; the first {@code shown} are drawn. */
    static final class Load {
        final WagonKind kind;
        final String summary;
        final int places;
        final Style[] style;
        final BlockState[] block;
        final ItemStack[] top;
        final float[] x, y, z;

        Load(WagonKind kind, String summary, int places) {
            this.kind = kind;
            this.summary = summary;
            this.places = places;
            this.style = new Style[places];
            this.block = new BlockState[places];
            this.top = new ItemStack[places];
            this.x = new float[places];
            this.y = new float[places];
            this.z = new float[places];
        }
    }

    private static final BlockState CRATE_BLOCK_FALLBACK = Blocks.BARREL.defaultBlockState();
    private static final BlockState BARREL = Blocks.BARREL.defaultBlockState().setValue(BarrelBlock.FACING, Direction.UP).setValue(BarrelBlock.OPEN, true);
    private static final BlockState HAY = Blocks.HAY_BLOCK.defaultBlockState().setValue(RotatedPillarBlock.AXIS, Direction.Axis.Z);

    /** The wagon's load as laid out for its kind and its words for the load, worked out again only when they change. */
    private static Load load(TradeWagon w, WagonKind k) {
        String summary = w.cargoSummary();
        // The same words object until the server sends new ones: compared by reference, so nothing is made per frame.
        if (w.clientLoad instanceof Load l && l.kind == k && l.summary == summary) return l;
        Load l = layout(k, summary);
        w.clientLoad = l;
        return l;
    }

    private static Load layout(WagonKind k, String summary) {
        int rows = TradeWagonModel.loadRows(k);
        int places = rows * 2 * 2;
        Load l = new Load(k, summary, places);
        // The goods, and their shares of the load.
        List<ItemStack> goods = new ArrayList<>();
        List<Integer> counts = new ArrayList<>();
        int total = 0;
        if (!summary.isEmpty()) {
            for (String part : summary.split(";")) {
                String[] kv = part.split("\\*");
                if (kv.length != 2) continue;
                Item it = BuiltInRegistries.ITEM.get(ResourceLocation.tryParse(kv[0]));
                if (it == Items.AIR) continue;
                int n;
                try { n = Integer.parseInt(kv[1]); } catch (NumberFormatException e) { continue; }
                goods.add(new ItemStack(it));
                counts.add(n);
                total += n;
            }
        }
        float[] zone = TradeWagonModel.loadZone(k);
        float bed = TradeWagonModel.bedTop(k);
        float step = (zone[1] - zone[0]) / rows;
        int[] dealt = new int[goods.size()];
        for (int i = 0; i < places; i++) {
            // Bottom layer first, back to front, then the top layer: so a load half gone is gone from the top.
            int layer = i / (rows * 2), idx = i % (rows * 2), row = idx / 2, col = idx % 2;
            l.x[i] = (col == 0 ? -1 : 1) * zone[2] * 0.5F;
            l.z[i] = zone[0] + step * (row + 0.5F);
            l.y[i] = bed + layer * 0.42F;
            // Dealt out so any first so many are in the goods' shares.
            int pick = -1;
            double best = -1e9;
            for (int g = 0; g < goods.size(); g++) {
                double owed = (double) counts.get(g) / Math.max(1, total) * (i + 1) - dealt[g];
                if (owed > best) { best = owed; pick = g; }
            }
            ItemStack s = pick < 0 ? new ItemStack(Items.BARREL) : goods.get(pick);
            if (pick >= 0) dealt[pick]++;
            Style st = Style.CRATE;
            for (Rule r : RULES) {
                if (r.fits().test(s)) { st = r.style(); break; }
            }
            l.style[i] = st;
            l.top[i] = st == Style.CRATE && s.is(WorkItems.SHIPPING_CRATE_ITEM.get()) ? ItemStack.EMPTY : s;
            l.block[i] = switch (st) {
                case CRATE -> crateBlock();
                case BARREL -> BARREL;
                case BALE -> HAY;
                case LOGS -> logOf(s);
                case BLOCK -> ((BlockItem) s.getItem()).getBlock().defaultBlockState();
                case SACK -> null;
            };
        }
        return l;
    }

    private static BlockState crateBlock() {
        try {
            return WorkItems.SHIPPING_CRATE.get().defaultBlockState();
        } catch (RuntimeException e) {
            return CRATE_BLOCK_FALLBACK;
        }
    }

    private static BlockState logOf(ItemStack s) {
        BlockState b = s.getItem() instanceof BlockItem bi ? bi.getBlock().defaultBlockState() : Blocks.OAK_LOG.defaultBlockState();
        return b.hasProperty(RotatedPillarBlock.AXIS) ? b.setValue(RotatedPillarBlock.AXIS, Direction.Axis.Z) : b;
    }

    /** The load, as full as the wagon looks (rising as it is loaded, falling as it is unloaded). */
    private void renderLoad(TradeWagon w, WagonKind k, TradeWagonModel.Parts p, PoseStack pose, MultiBufferSource buffers, int light) {
        int fill = w.shownFill();
        if (fill <= 0 || w.cargoSummary().isEmpty()) return;
        Load l = load(w, k);
        int shown = Math.max(1, Math.min(l.places, Math.round(l.places * fill / 100.0F)));
        for (int i = 0; i < shown; i++) {
            pose.pushPose();
            pose.translate(l.x[i], l.y[i], l.z[i]);
            switch (l.style[i]) {
                case LOGS -> logs(l.block[i], pose, buffers, light);
                case SACK -> sack(p, pose, buffers, light);
                case BALE -> block(l.block[i], 0.46F, 0.36F, 0.56F, pose, buffers, light);
                case BARREL -> {
                    block(l.block[i], 0.4F, 0.44F, 0.4F, pose, buffers, light);
                    top(l.top[i], 0.4F, pose, buffers, light);
                }
                case BLOCK -> block(l.block[i], 0.4F, 0.4F, 0.4F, pose, buffers, light);
                case CRATE -> {
                    block(l.block[i], 0.44F, 0.4F, 0.48F, pose, buffers, light);
                    if (!l.top[i].isEmpty()) top(l.top[i], 0.41F, pose, buffers, light);
                }
            }
            pose.popPose();
        }
    }

    /** A block drawn this size, standing on the spot. */
    private void block(BlockState b, float sx, float sy, float sz, PoseStack pose, MultiBufferSource buffers, int light) {
        pose.pushPose();
        pose.scale(sx, sy, sz);
        pose.translate(-0.5F, 0.0F, -0.5F);
        blocks.renderSingleBlock(b, pose, buffers, light, OverlayTexture.NO_OVERLAY);
        pose.popPose();
    }

    /** Three logs lying lengthways, two below and one in the crook. */
    private void logs(BlockState b, PoseStack pose, MultiBufferSource buffers, int light) {
        for (int i = 0; i < 3; i++) {
            pose.pushPose();
            float x = i == 2 ? 0.0F : (i == 0 ? -0.11F : 0.11F);
            pose.translate(x, i == 2 ? 0.19F : 0.0F, 0.0F);
            block(b, 0.21F, 0.21F, 0.52F, pose, buffers, light);
            pose.popPose();
        }
    }

    /** A burlap sack, tied at the neck. */
    private void sack(TradeWagonModel.Parts p, PoseStack pose, MultiBufferSource buffers, int light) {
        pose.pushPose();
        pose.scale(-1.1F, -1.1F, 1.1F);
        p.sack().render(pose, buffers.getBuffer(RenderType.entityCutoutNoCull(TradeWagonModel.TEXTURE)), light, OverlayTexture.NO_OVERLAY,
            0xFFB8A27A);
        pose.popPose();
    }

    /** One of the goods showing at the top of its open barrel or crate, lying flat. */
    private void top(ItemStack s, float h, PoseStack pose, MultiBufferSource buffers, int light) {
        if (s.isEmpty()) return;
        pose.pushPose();
        pose.translate(0.0F, h - 0.02F, 0.0F);
        pose.mulPose(Axis.XP.rotationDegrees(-90.0F));
        pose.scale(0.34F, 0.34F, 0.34F);
        items.renderStatic(s, ItemDisplayContext.FIXED, light, OverlayTexture.NO_OVERLAY, pose, buffers, null, 0);
        pose.popPose();
    }

}
