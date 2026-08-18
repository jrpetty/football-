package com.jrpetty.mcassistant.client;

import com.jrpetty.mcassistant.entity.AssistantEntity;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * The whole operation from above: every patch you've marked, every specialist
 * standing on it, and you. Answers the two questions a list can't — is anything
 * overlapping, and is anybody working somewhere they shouldn't be.
 */
public class MapScreen extends Screen {

    private static final int W = 300, H = 236;
    private static final int PAD = 10;

    private final List<AssistantEntity> crew = new ArrayList<>();
    private int left, top, mapX, mapY, mapW, mapH;
    private int hovered = -1;

    // World bounds the map covers, in blocks.
    private int minX, minZ, maxX, maxZ;

    public MapScreen() {
        super(Component.literal("Map"));
    }

    @Override
    protected void init() {
        this.left = (this.width - W) / 2;
        this.top = (this.height - H) / 2;
        this.mapX = left + PAD;
        this.mapY = top + 30;
        this.mapW = W - PAD * 2;
        this.mapH = H - 30 - PAD - 22 - 14;

        crew.clear();
        if (this.minecraft != null && this.minecraft.player != null) {
            crew.addAll(this.minecraft.player.level().getEntitiesOfClass(
                AssistantEntity.class,
                this.minecraft.player.getBoundingBox().inflate(256.0),
                AssistantEntity::isAlive));
            crew.sort((a, b) -> a.clientName().compareToIgnoreCase(b.clientName()));
        }
        fitBounds();

        this.addRenderableWidget(Button.builder(Component.literal("Crew"),
                b -> this.minecraft.setScreen(new CrewScreen()))
            .bounds(left + PAD, top + H - PAD - 18, (mapW - 4) / 2, 18).build());
        this.addRenderableWidget(Button.builder(Component.literal("Close"), b -> this.onClose())
            .bounds(left + PAD + (mapW - 4) / 2 + 4, top + H - PAD - 18, (mapW - 4) / 2, 18).build());
    }

    /** Frame everything worth seeing: you, every bot, and every patch, with a
     *  margin so nothing sits on the edge, and a floor on the span so a lone
     *  bot standing next to you doesn't zoom in to a single pixel. */
    private void fitBounds() {
        int loX = Integer.MAX_VALUE, loZ = Integer.MAX_VALUE;
        int hiX = Integer.MIN_VALUE, hiZ = Integer.MIN_VALUE;

        if (this.minecraft != null && this.minecraft.player != null) {
            loX = hiX = (int) this.minecraft.player.getX();
            loZ = hiZ = (int) this.minecraft.player.getZ();
        }
        for (AssistantEntity a : crew) {
            loX = Math.min(loX, (int) a.getX()); hiX = Math.max(hiX, (int) a.getX());
            loZ = Math.min(loZ, (int) a.getZ()); hiZ = Math.max(hiZ, (int) a.getZ());
            int[] z = BotInfo.of(a).zone();
            if (z != null) {
                loX = Math.min(loX, z[0]); hiX = Math.max(hiX, z[2]);
                loZ = Math.min(loZ, z[1]); hiZ = Math.max(hiZ, z[3]);
            }
        }
        if (loX > hiX) { loX = hiX = 0; loZ = hiZ = 0; }

        int cx = (loX + hiX) / 2, cz = (loZ + hiZ) / 2;
        // Square the view so the world isn't stretched, and never below 64
        // blocks across — a map you can't read is worse than no map.
        int span = Math.max(64, Math.max(hiX - loX, hiZ - loZ) + 24);
        this.minX = cx - span / 2; this.maxX = cx + span / 2;
        this.minZ = cz - span / 2; this.maxZ = cz + span / 2;
    }

    /** Keep the view framed as the crew (and you) move about. */
    @Override
    public void tick() {
        super.tick();
        crew.removeIf(a -> !a.isAlive());
        fitBounds();
    }

    private int px(double worldX) {
        return mapX + (int) ((worldX - minX) * mapW / (double) Math.max(1, maxX - minX));
    }

    private int pz(double worldZ) {
        return mapY + (int) ((worldZ - minZ) * mapH / (double) Math.max(1, maxZ - minZ));
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(g, mouseX, mouseY, partialTick);
        Ui.panel(g, left, top, W, H, 26);

        int x = left + PAD;
        g.drawString(this.font, "The operation", x, top + 8, Ui.INK, false);
        Ui.right(g, this.font, (maxX - minX) + " blocks across", left + W - PAD, top + 8, Ui.MUTED);

        // The ground — recessed into the panel the way a vanilla slot is:
        // shadow on top and left, light underneath, so it reads as a window
        // onto the world rather than a grey rectangle on a grey panel.
        g.fill(mapX, mapY, mapX + mapW, mapY + mapH, Ui.ROW);
        g.fill(mapX, mapY, mapX + mapW, mapY + 1, Ui.LO);
        g.fill(mapX, mapY, mapX + 1, mapY + mapH, Ui.LO);
        g.fill(mapX, mapY + mapH - 1, mapX + mapW, mapY + mapH, Ui.HI);
        g.fill(mapX + mapW - 1, mapY, mapX + mapW, mapY + mapH, Ui.HI);
        g.renderOutline(mapX, mapY, mapW, mapH, Ui.EDGE_SOFT);
        grid(g);

        hovered = -1;
        // Everything below is plotted from live positions, so clip it to the
        // map rather than let a bot that has wandered draw over the chrome.
        g.enableScissor(mapX, mapY, mapX + mapW, mapY + mapH);

        // Patches first, so the bots sit on top of their own ground.
        for (int i = 0; i < crew.size(); i++) {
            AssistantEntity a = crew.get(i);
            int[] z = BotInfo.of(a).zone();
            if (z == null) continue;
            int colour = jobColour(a);
            int x0 = px(z[0]), z0 = pz(z[1]), x1 = px(z[2]), z1 = pz(z[3]);
            if (x1 - x0 < 2) x1 = x0 + 2;
            if (z1 - z0 < 2) z1 = z0 + 2;
            g.fill(x0, z0, x1, z1, (colour & 0x00FFFFFF) | 0x33000000);
            g.renderOutline(x0, z0, x1 - x0, z1 - z0, colour);
            if (mouseX >= x0 && mouseX <= x1 && mouseY >= z0 && mouseY <= z1) hovered = i;
        }

        // You.
        if (this.minecraft != null && this.minecraft.player != null) {
            int myX = px(this.minecraft.player.getX());
            int myZ = pz(this.minecraft.player.getZ());
            // Ink, not white: the ground under it is light grey, and a white
            // cross on it is a cross you cannot find.
            g.fill(myX - 4, myZ - 2, myX + 5, myZ + 3, 0xFFE9E9E9);   // halo
            g.fill(myX - 2, myZ - 4, myX + 3, myZ + 5, 0xFFE9E9E9);
            g.fill(myX - 3, myZ - 1, myX + 4, myZ + 2, Ui.INK);
            g.fill(myX - 1, myZ - 3, myX + 2, myZ + 4, Ui.INK);
        }

        // The crew, each a chip in its trade's colour.
        for (int i = 0; i < crew.size(); i++) {
            AssistantEntity a = crew.get(i);
            int bx = px(a.getX()), bz = pz(a.getZ());
            int colour = jobColour(a);
            boolean stuck = Ui.statusColour(a.clientStatus()) == Ui.BAD;
            g.fill(bx - 3, bz - 3, bx + 4, bz + 4, stuck ? Ui.BAD : 0xFF000000);
            g.fill(bx - 2, bz - 2, bx + 3, bz + 3, colour);
            if (Math.abs(mouseX - bx) <= 4 && Math.abs(mouseY - bz) <= 4) hovered = i;
        }
        g.disableScissor();
        // North is up; say so — after the scissored world, so a patch that
        // happens to sit in the corner can't paint over the compass.
        g.drawString(this.font, "N", mapX + 4, mapY + 4, Ui.FAINT, false);

        // What you're pointing at, spelled out under the map.
        int ly = mapY + mapH + 5;
        if (hovered >= 0 && hovered < crew.size()) {
            AssistantEntity a = crew.get(hovered);
            String job = AssistantEntity.StationTask.byOrdinal(a.clientJobOrdinal()).title;
            g.drawString(this.font, Ui.clip(this.font,
                a.clientName() + "  ·  " + job + "  ·  " + a.clientStatus(), mapW),
                mapX, ly, Ui.INK, false);
        } else if (crew.isEmpty()) {
            g.drawString(this.font, "No assistants nearby.", mapX, ly, Ui.MUTED, false);
        } else {
            g.drawString(this.font, "Point at a patch or a chip to name it.",
                mapX, ly, Ui.FAINT, false);
        }

        super.render(g, mouseX, mouseY, partialTick);
    }

    /** A 32-block grid, so distances on the map mean something. */
    private void grid(GuiGraphics g) {
        int step = 32;
        for (int wx = Math.floorDiv(minX, step) * step; wx <= maxX; wx += step) {
            int sx = px(wx);
            if (sx > mapX && sx < mapX + mapW) g.fill(sx, mapY, sx + 1, mapY + mapH, Ui.EDGE_SOFT);
        }
        for (int wz = Math.floorDiv(minZ, step) * step; wz <= maxZ; wz += step) {
            int sz = pz(wz);
            if (sz > mapY && sz < mapY + mapH) g.fill(mapX, sz, mapX + mapW, sz + 1, Ui.EDGE_SOFT);
        }
    }

    private static int jobColour(AssistantEntity a) {
        return Ui.job(a.clientJobOrdinal());   // one palette for map, list and chips
    }

    /** Click a chip or a patch to open that specialist's orders. */
    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (hovered >= 0 && hovered < crew.size() && this.minecraft != null) {
            this.minecraft.setScreen(new OrdersScreen(crew.get(hovered)));
            return true;
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
