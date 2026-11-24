package com.jrpetty.mcassistant.pa;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;

/**
 * [pa36b] Land-claim mods asked directly, when they're installed: FTB Chunks and Open Parties and Claims, through
 * their own APIs by reflection (no dependency: absent, or a version whose API has moved, simply gives no verdict).
 * Asked as its player (who must be online); PaContainers also posts a pretend right-click for any other claims mod.
 */
public final class PaClaims {

    private PaClaims() {}

    private static final Logger LOG = LoggerFactory.getLogger("mc_assistant/pa36");
    private static boolean ftbTried, opacTried;
    private static Object ftbManager, ftbInteract;
    private static Method ftbPrevent;
    private static Method opacGet, opacProtection, opacInteract;

    /** False when a claims mod says its player may not open this block; true when none objects (or none is installed). */
    public static boolean allow(ServerPlayer player, BlockPos pos) {
        if (player == null || !(player.level() instanceof ServerLevel level)) return true;
        Boolean f = ftb(player, pos);
        if (f != null && !f) return false;
        Boolean o = opac(player, level, pos);
        return o == null || o;
    }

    /** Which claims mods it can ask (for the guide's "what it checks" and the log). */
    public static String installed() {
        StringBuilder b = new StringBuilder();
        if (present("dev.ftb.mods.ftbchunks.api.FTBChunksAPI")) b.append("FTB Chunks");
        if (present("xaero.pac.common.server.api.OpenPACServerAPI")) b.append(b.length() > 0 ? ", " : "").append("Open Parties and Claims");
        return b.toString();
    }

    private static boolean present(String cls) {
        try { Class.forName(cls); return true; } catch (Throwable t) { return false; }
    }

    // FTB Chunks: FTBChunksAPI.api().getManager().shouldPreventInteraction(actor, hand, pos, Protection.INTERACT_BLOCK, null)
    private static Boolean ftb(ServerPlayer player, BlockPos pos) {
        try {
            if (!ftbTried) {
                ftbTried = true;
                Class<?> api = Class.forName("dev.ftb.mods.ftbchunks.api.FTBChunksAPI");
                Object inst = api.getMethod("api").invoke(null);
                ftbManager = inst.getClass().getMethod("getManager").invoke(inst);
                Class<?> prot = Class.forName("dev.ftb.mods.ftbchunks.api.Protection");
                ftbInteract = prot.getField("INTERACT_BLOCK").get(null);
                for (Method m : ftbManager.getClass().getMethods()) if (m.getName().equals("shouldPreventInteraction") && m.getParameterCount() == 5) ftbPrevent = m;
                if (ftbPrevent != null) LOG.info("[pa36] asking FTB Chunks about claims");
            }
            if (ftbPrevent == null) return null;
            Object r = ftbPrevent.invoke(ftbManager, player, InteractionHand.MAIN_HAND, pos, ftbInteract, null);
            return r instanceof Boolean b ? !b : null;
        } catch (Throwable t) {
            ftbPrevent = null;
            return null;
        }
    }

    // Open Parties and Claims: OpenPACServerAPI.get(server).getChunkProtection().onBlockInteraction(entity, hand, held, level, pos, dir, breaking, messages)
    private static Boolean opac(ServerPlayer player, ServerLevel level, BlockPos pos) {
        try {
            if (!opacTried) {
                opacTried = true;
                Class<?> api = Class.forName("xaero.pac.common.server.api.OpenPACServerAPI");
                for (Method m : api.getMethods()) if (m.getName().equals("get") && m.getParameterCount() == 1) opacGet = m;
                if (opacGet != null) {
                    opacProtection = opacGet.getReturnType().getMethod("getChunkProtection");
                    for (Method m : opacProtection.getReturnType().getMethods()) if (m.getName().equals("onBlockInteraction") && m.getParameterCount() == 8) opacInteract = m;
                }
                if (opacInteract != null) LOG.info("[pa36] asking Open Parties and Claims about claims");
            }
            if (opacInteract == null) return null;
            Object api = opacGet.invoke(null, level.getServer());
            Object prot = opacProtection.invoke(api);
            Object r = opacInteract.invoke(prot, player, InteractionHand.MAIN_HAND, ItemStack.EMPTY, level, pos, Direction.UP, false, false);
            return r instanceof Boolean b ? !b : null;
        } catch (Throwable t) {
            opacInteract = null;
            return null;
        }
    }
}
