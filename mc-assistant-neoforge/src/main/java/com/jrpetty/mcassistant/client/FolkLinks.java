package com.jrpetty.mcassistant.client;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.entity.TalkTopic;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import com.jrpetty.mcassistant.net.FolkTalkPayload;
import com.jrpetty.mcassistant.net.FolkTeleportPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.world.entity.Entity;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * [teleport] A folk's name made something to click: in the town's books (the Folk page, the Society page's richest,
 * best liked and best hands, the Leader page) and on the folk's own card. Under the mouse the name is underlined
 * and the pointer becomes a hand; a click opens a little card of buttons under it:
 * <ul>
 *   <li><b>Teleport to (name)</b>, for a player in creative (and an operator, on a server: FolkTeleport says why).
 *       The screen shuts, and the server, which checks it all again, says in chat how it went. On the folk's own
 *       card it is offered only when you are not already beside the folk, in sight of it;</li>
 *   <li><b>Show card</b>, in the books, when the folk is near enough to talk to (the card is the server's to give,
 *       and it gives it only within talking range, as a right-click would).</li>
 * </ul>
 * A name with nothing to offer is no link at all: a survival player far from everybody sees the books as before.
 *
 * <p>A screen keeps one of these: {@link #begin} at the start of its frame, {@link #add} where it draws a name,
 * {@link #draw} last of all, and {@link #click} first thing in its mouseClicked.
 */
@EventBusSubscriber(modid = McAssistantMod.MODID, value = Dist.CLIENT)
public final class FolkLinks {

    /** How near a folk must be for its card to be asked for: the server talks within eight. */
    private static final double CARD_RANGE = 7.5;
    /** On the card: this near, and in sight of it, you are beside the folk already (a creative hand reaches five). */
    private static final double BESIDE = 5.0;

    private record Link(int x0, int y0, int x1, int y1, UUID id, String name) {
        boolean has(double x, double y) {
            return x >= x0 && x < x1 && y >= y0 && y < y1 + 2;
        }
    }

    private record Action(String label, Runnable run) {}

    /** On a folk's card (Teleport only, and not when beside it), or in the books. */
    private final boolean card;
    private final List<Link> links = new ArrayList<>();
    /** The name whose buttons are open, and the buttons as last drawn: the box, each button's box, what each does. */
    @Nullable private Link open;
    private int px, py, pw, ph;
    private final List<int[]> boxes = new ArrayList<>();
    private List<Action> shown = List.of();
    private int mx, my;
    /** The folk about the player this frame, by id (made when first asked for). */
    @Nullable private Map<UUID, Entity> nearby;

    /** The hand pointer, made once; and whether it is up. */
    private static long hand;
    private static boolean handShown;

    public FolkLinks(boolean card) {
        this.card = card;
    }

    /** The start of a frame: the names are drawn afresh. */
    public void begin(int mouseX, int mouseY) {
        links.clear();
        nearby = null;
        mx = mouseX;
        my = mouseY;
    }

    /**
     * A folk's name just drawn at x, y, this wide and high: a link, if there is anything to do with this folk;
     * underlined (in {@code colour}) while the mouse is on it or its buttons are open.
     */
    public void add(GuiGraphics g, @Nullable UUID id, String name, int x, int y, int w, int h, int colour) {
        if (id == null || w <= 0 || actions(id, name).isEmpty()) return;
        Link l = new Link(x, y, x + w, y + h, id, name);
        links.add(l);
        if ((l.has(mx, my) && !overPopup(mx, my)) || same(l, open)) g.fill(x, y + h, x + w, y + h + 1, colour);
    }

    /** As {@link #add}, the id as the books write it in a line ("name|...|id"); nothing if it is not one. */
    public void add(GuiGraphics g, String id, String name, int x, int y, int w, int h, int colour) {
        UUID u;
        try {
            u = UUID.fromString(id.trim());
        } catch (IllegalArgumentException e) {
            return;
        }
        add(g, u, name, x, y, w, h, colour);
    }

    /** The name on a folk's card, by the folk's entity id there. */
    public void addCard(GuiGraphics g, int entityId, String name, int x, int y, int w, int h, int colour) {
        Minecraft mc = Minecraft.getInstance();
        Entity e = mc.level == null ? null : mc.level.getEntity(entityId);
        if (e != null) add(g, e.getUUID(), name, x, y, w, h, colour);
    }

    /** What can be done with this folk from here, now. */
    private List<Action> actions(UUID id, String name) {
        List<Action> out = new ArrayList<>(2);
        Entity near = nearby().get(id);
        if (mayTeleport() && !(card && beside(near))) out.add(new Action("Teleport to " + name, () -> teleport(id)));
        Minecraft mc = Minecraft.getInstance();
        if (!card && near != null && mc.player != null && near.distanceToSqr(mc.player) <= CARD_RANGE * CARD_RANGE) {
            out.add(new Action("Show card", () -> showCard(near)));
        }
        return out;
    }

    private Map<UUID, Entity> nearby() {
        if (nearby != null) return nearby;
        nearby = new HashMap<>();
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return nearby;
        for (Entity e : mc.level.entitiesForRendering()) {
            if (e instanceof VillageFolkEntity && e.isAlive() && e.distanceToSqr(mc.player) <= 16.0 * 16.0) nearby.put(e.getUUID(), e);
        }
        return nearby;
    }

    private static boolean beside(@Nullable Entity folk) {
        Minecraft mc = Minecraft.getInstance();
        return folk != null && mc.player != null && folk.distanceToSqr(mc.player) <= BESIDE * BESIDE && mc.player.hasLineOfSight(folk);
    }

    /**
     * Would the server let this player go straight to a folk? Creative, and on a server not its own (nor a LAN game),
     * an operator: FolkTeleport.allowed asks the same, and is the one that counts.
     */
    public static boolean mayTeleport() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || !mc.player.isCreative()) return false;
        ServerData server = mc.getCurrentServer();
        return mc.hasSingleplayerServer() || (server != null && server.isLan()) || mc.player.hasPermissions(2);
    }

    private static void teleport(UUID id) {
        PacketDistributor.sendToServer(new FolkTeleportPayload(id));
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen != null) mc.screen.onClose();
    }

    /** Ask for its card, as a right-click would: the server answers with it, and it takes the place of the books. */
    private static void showCard(Entity folk) {
        PacketDistributor.sendToServer(new FolkTalkPayload(folk.getId(), TalkTopic.OPEN.ordinal(), ""));
    }

    // ------------------------------------------------------------------ the buttons

    /**
     * The open name's buttons, over everything else, and the pointer a hand over a name or a button. True while the
     * mouse is on the buttons' card (so the screen leaves its own tooltip off).
     */
    public boolean draw(GuiGraphics g, Font font) {
        boxes.clear();
        if (open != null) {
            // Still on the page where it was (the page turned, or the rows scrolled under it: shut).
            Link again = null;
            for (Link l : links) if (same(l, open)) again = l;
            open = again;
        }
        if (open != null) {
            shown = actions(open.id(), open.name());
            if (shown.isEmpty()) open = null;
        }
        boolean overButton = false;
        if (open != null) {
            int widest = font.width(open.name());
            for (Action a : shown) widest = Math.max(widest, font.width(a.label()) + 8);
            pw = widest + 10;
            ph = 17 + shown.size() * 14 + 1;
            Minecraft mc = Minecraft.getInstance();
            int sw = mc.getWindow().getGuiScaledWidth(), sh = mc.getWindow().getGuiScaledHeight();
            px = Math.max(2, Math.min(open.x0(), sw - pw - 2));
            py = open.y1() + 3;
            if (py + ph > sh - 2) py = Math.max(2, open.y0() - ph - 3);
            g.pose().pushPose();
            g.pose().translate(0.0F, 0.0F, 400.0F);
            Ui.panel(g, px, py, pw, ph, 13);
            g.drawString(font, Ui.clip(font, open.name(), pw - 10), px + 5, py + 4, Ui.FAINT, false);
            int by = py + 17;
            for (Action a : shown) {
                int x0 = px + 4, x1 = px + pw - 4, y1 = by + 12;
                boolean over = mx >= x0 && mx < x1 && my >= by && my < y1;
                overButton |= over;
                g.fill(x0, by, x1, y1, over ? Ui.ROW_PICK : Ui.ROW_ALT);
                g.renderOutline(x0, by, x1 - x0, y1 - by, over ? Ui.EDGE : Ui.EDGE_SOFT);
                g.drawString(font, a.label(), x0 + 4, by + 2, Ui.INK, false);
                boxes.add(new int[]{ x0, by, x1, y1 });
                by += 14;
            }
            g.pose().popPose();
        }
        boolean overBox = overPopup(mx, my);
        boolean overLink = false;
        if (!overBox) for (Link l : links) overLink |= l.has(mx, my);
        cursor(overLink || overButton);
        return overBox;
    }

    /**
     * A click, before the screen's own: a button does its work; a name opens its buttons; anywhere else shuts them
     * and the click goes on to the screen. True if it was taken here.
     */
    public boolean click(double x, double y, int button) {
        if (button != 0) return false;
        if (open != null) {
            for (int i = 0; i < boxes.size() && i < shown.size(); i++) {
                int[] b = boxes.get(i);
                if (x >= b[0] && x < b[2] && y >= b[1] && y < b[3]) {
                    Action a = shown.get(i);
                    open = null;
                    a.run().run();
                    return true;
                }
            }
            if (overPopup(x, y)) return true;
            open = null;
        }
        for (Link l : links) {
            if (l.has(x, y)) {
                open = l;
                return true;
            }
        }
        return false;
    }

    private boolean overPopup(double x, double y) {
        return open != null && pw > 0 && x >= px - 1 && x < px + pw + 1 && y >= py - 1 && y < py + ph + 1;
    }

    private static boolean same(@Nullable Link a, @Nullable Link b) {
        return a != null && b != null && a.id().equals(b.id()) && a.x0() == b.x0() && a.y0() == b.y0();
    }

    // ------------------------------------------------------------------ the pointer

    /** The hand over something to click, the arrow otherwise (GLFW's own pointers: the game has none of its own). */
    private static void cursor(boolean on) {
        if (on == handShown) return;
        long window = Minecraft.getInstance().getWindow().getWindow();
        if (on && hand == 0L) hand = GLFW.glfwCreateStandardCursor(GLFW.GLFW_HAND_CURSOR);
        GLFW.glfwSetCursor(window, on ? hand : 0L);
        handShown = on;
    }

    /** A screen shutting never leaves the hand up behind it. */
    @SubscribeEvent
    public static void onClosing(ScreenEvent.Closing event) {
        cursor(false);
    }
}
