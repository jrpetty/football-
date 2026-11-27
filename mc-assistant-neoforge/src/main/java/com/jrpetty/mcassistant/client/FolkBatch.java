package com.jrpetty.mcassistant.client;

import com.jrpetty.mcassistant.McAssistantMod;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * [anim] The folk drawn in a few batches, not a few batches each: measured with a hundred and forty-four folk in sight,
 * nine tenths of a frame went to drawing them, and nearly all of that to the draws themselves. A folk is drawn in passes,
 * each with its own picture (its own face and skin, its trade's outfit, the outfit's dyed parts, its finery, its own
 * clothes' cloth and trim, its face's blink, the tool in its hand), and the game draws a pass's batch the moment the next
 * pass asks for a different picture: seven draws or more for every folk, a thousand a frame for a town in sight.
 *
 * <p>Here each picture keeps its own batch through the whole of the game's drawing of the entities, every folk's outfit of
 * a trade going into the one batch, and all of them are drawn once at the end of it (RenderLevelStageEvent AFTER_ENTITIES):
 * a draw a picture, not a draw a pass. Only the plain entity pictures (solid, cut out, the translucent face overlays, a
 * lamp's glow, armour), only in the world's own drawing of its entities (never a folk drawn in a screen, nor its glowing
 * outline), and the depth test puts them right whatever order they are drawn in.
 */
@EventBusSubscriber(modid = McAssistantMod.MODID, value = Dist.CLIENT)
public final class FolkBatch {

    private FolkBatch() {}

    /** Off: every folk drawn as the game draws it (AnimDev's "nobatch", to measure the difference). */
    static boolean enabled = true;
    private static boolean open;

    /** The batches: a buffer for each picture, kept from frame to frame (they grow to what a frame needs). */
    private static final java.util.SequencedMap<RenderType, ByteBufferBuilder> BUFFERS = new LinkedHashMap<>();
    private static final Batches BATCHES = new Batches();
    private static final Map<RenderType, Boolean> PLAIN = new IdentityHashMap<>();
    private static int frames;

    private static final class Batches extends MultiBufferSource.BufferSource {
        Batches() {
            super(new ByteBufferBuilder(256), BUFFERS);
        }

        @Override
        public VertexConsumer getBuffer(RenderType type) {
            if (!BUFFERS.containsKey(type)) BUFFERS.put(type, new ByteBufferBuilder(Math.max(type.bufferSize(), 4096)));
            return super.getBuffer(type);
        }

        boolean any() { return !startedBuilders.isEmpty(); }

        /** Every batch drawn in the order of the passes that fill it: a folk's skin before its outfit, its outfit before
         *  its dye, armour before its trim (a pass drawn over another at the same depth has to come after it). */
        void drawInOrder() {
            if (reorder) {
                ORDERED.clear();
                ORDERED.addAll(BUFFERS.keySet());
                ORDERED.sort(java.util.Comparator.comparingInt(t -> ORDER.getOrDefault(t, 0)));
                reorder = false;
            }
            for (RenderType t : ORDERED) if (startedBuilders.containsKey(t)) endBatch(t);
            if (any()) endBatch();
        }
    }

    /** Each batch's place among a folk's passes (the latest it has come in any folk's drawing), and the batches in that order. */
    private static final Map<RenderType, Integer> ORDER = new IdentityHashMap<>();
    private static final java.util.List<RenderType> ORDERED = new java.util.ArrayList<>();
    private static boolean reorder;
    /** The pass a folk being drawn is on, and the picture it last asked for. */
    private static int pass;
    private static RenderType lastType;

    /** A folk about to be drawn: its passes counted from the first. */
    public static void folk() {
        pass = 0;
        lastType = null;
    }

    private static VertexConsumer batched(RenderType type) {
        if (type != lastType) {
            lastType = type;
            pass++;
            Integer was = ORDER.get(type);
            if (was == null || was < pass) {
                ORDER.put(type, pass);
                reorder = true;
            }
        }
        if (!BUFFERS.containsKey(type)) reorder = true;
        return BATCHES.getBuffer(type);
    }

    /** What a folk's passes draw into: the batches while the world's entities are being drawn, else the game's own. */
    public static MultiBufferSource around(MultiBufferSource buffer) {
        if (!open || !enabled || buffer != Minecraft.getInstance().renderBuffers().bufferSource()) return buffer;
        if (wrapped != buffer) {
            wrapped = buffer;
            wrapper = type -> plain(type) ? batched(type) : buffer.getBuffer(type);
        }
        return wrapper;
    }

    private static MultiBufferSource wrapped, wrapper;

    /** A plain entity picture (by the render type's name, worked out once for each). */
    private static boolean plain(RenderType type) {
        Boolean known = PLAIN.get(type);
        if (known != null) return known;
        String s = type.toString();
        int a = s.indexOf('['), b = s.indexOf(':', a + 1);
        String name = a >= 0 && b > a ? s.substring(a + 1, b) : s;
        boolean ok = switch (name) {
            case "entity_solid", "entity_cutout", "entity_cutout_no_cull", "entity_cutout_no_cull_z_offset", "entity_smooth_cutout",
                 "entity_translucent", "entity_translucent_cull", "item_entity_translucent_cull", "armor_cutout_no_cull", "eyes" -> true;
            default -> false;
        };
        PLAIN.put(type, ok);
        return ok;
    }

    @SubscribeEvent
    public static void onStage(RenderLevelStageEvent event) {
        if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_CUTOUT_BLOCKS) {
            open = true;
        } else if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_ENTITIES) {
            open = false;
            long t0 = AnimPerf.now();
            if (BATCHES.any()) BATCHES.drawInOrder();
            if (AnimPerf.on) AnimPerf.batchNs += System.nanoTime() - t0;
        }
    }

    @SubscribeEvent
    public static void onFrame(RenderFrameEvent.Pre event) {
        open = false;
        if (BATCHES.any()) BATCHES.drawInOrder();                 // never left over into another frame
        // Pictures no longer drawn (a face let go of) give their buffers back now and then.
        if (++frames % 1200 == 0 && BUFFERS.size() > 400) {
            for (ByteBufferBuilder b : BUFFERS.values()) b.close();
            BUFFERS.clear();
            ORDER.clear();
            reorder = true;
        }
    }
}
