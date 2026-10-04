package com.jrpetty.mcassistant.client;

import com.jrpetty.mcassistant.block.StorehouseBlockEntity;
import com.jrpetty.mcassistant.menu.StorehouseMenu;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;

/**
 * The Village Storehouse: a chest's six rows, with a scroll bar down the side for the
 * other seventy-five, a count of how full it is, and a Sort button.
 */
public class StorehouseScreen extends AbstractContainerScreen<StorehouseMenu> {

    private static final ResourceLocation CHEST = ResourceLocation.withDefaultNamespace("textures/gui/container/generic_54.png");
    private static final int BAR_X = 176, BAR_Y = 18, BAR_W = 12, BAR_H = StorehouseMenu.SHOWN_ROWS * 18, THUMB = 15;

    private boolean dragging;

    public StorehouseScreen(StorehouseMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        this.imageWidth = 176 + 18;
        this.imageHeight = 114 + StorehouseMenu.SHOWN_ROWS * 18;
        this.inventoryLabelY = this.imageHeight - 94;
    }

    @Override
    protected void init() {
        super.init();
        Button sort = Button.builder(Component.literal("Sort"),
                b -> click(StorehouseMenu.BUTTON_SORT))
            .bounds(leftPos + 176 - 7 - 32, topPos + 4, 32, 12).build();
        sort.setTooltip(Tooltip.create(Component.literal("Like with like, stacks topped up, in order")));
        addRenderableWidget(sort);
    }

    private void click(int id) {
        if (minecraft != null && minecraft.gameMode != null) {
            minecraft.gameMode.handleInventoryButtonClick(menu.containerId, id);
        }
    }

    private void scrollTo(int row) {
        int to = Math.max(0, Math.min(StorehouseMenu.LAST_ROW, row));
        if (to == menu.row()) return;
        menu.scrollTo(to);
        click(to);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (scrollY == 0) return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
        int step = hasShiftDown() ? StorehouseMenu.SHOWN_ROWS : 1;
        scrollTo(menu.row() - (int) Math.signum(scrollY) * step);
        return true;
    }

    private boolean onBar(double mouseX, double mouseY) {
        double x = mouseX - leftPos, y = mouseY - topPos;
        return x >= BAR_X && x < BAR_X + BAR_W && y >= BAR_Y && y < BAR_Y + BAR_H;
    }

    private void dragTo(double mouseY) {
        double f = (mouseY - topPos - BAR_Y - THUMB / 2.0) / (BAR_H - THUMB);
        scrollTo((int) Math.round(Math.max(0, Math.min(1, f)) * StorehouseMenu.LAST_ROW));
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && onBar(mouseX, mouseY)) {
            dragging = true;
            dragTo(mouseY);
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dx, double dy) {
        if (dragging) {
            dragTo(mouseY);
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dx, dy);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        dragging = false;
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    protected void renderBg(GuiGraphics g, float partialTick, int mouseX, int mouseY) {
        int x = leftPos, y = topPos;
        int top = StorehouseMenu.SHOWN_ROWS * 18 + 17;
        g.blit(CHEST, x, y, 0, 0, 176, top);
        g.blit(CHEST, x, y + top, 0, 126, 176, 96);
        // The scroll bar's channel, in the chest's own greys.
        int bx = x + BAR_X - 3, by = y + BAR_Y - 4;
        g.fill(bx, by, bx + BAR_W + 6, by + BAR_H + 8, 0xFFC6C6C6);
        g.fill(bx, by, bx + BAR_W + 6, by + 1, 0xFFFFFFFF);
        g.fill(bx + BAR_W + 5, by, bx + BAR_W + 6, by + BAR_H + 8, 0xFF555555);
        g.fill(bx, by + BAR_H + 7, bx + BAR_W + 6, by + BAR_H + 8, 0xFF555555);
        g.fill(x + BAR_X, y + BAR_Y, x + BAR_X + BAR_W, y + BAR_Y + BAR_H, 0xFF8B8B8B);
        g.fill(x + BAR_X, y + BAR_Y, x + BAR_X + BAR_W, y + BAR_Y + 1, 0xFF373737);
        int t = y + BAR_Y + (int) Math.round((BAR_H - THUMB) * (menu.row() / (double) StorehouseMenu.LAST_ROW));
        g.fill(x + BAR_X + 1, t, x + BAR_X + BAR_W - 1, t + THUMB, 0xFFF0F0F0);
        g.fill(x + BAR_X + 1, t + THUMB - 1, x + BAR_X + BAR_W - 1, t + THUMB, 0xFF8B8B8B);
        g.fill(x + BAR_X + BAR_W - 2, t, x + BAR_X + BAR_W - 1, t + THUMB, 0xFF8B8B8B);
    }

    @Override
    protected void renderLabels(GuiGraphics g, int mouseX, int mouseY) {
        String title = this.title.getString();
        int room = 176 - 8 - 7 - 32 - 6;
        if (font.width(title) > room) title = font.plainSubstrByWidth(title, room - font.width("...")) + "...";
        g.drawString(font, title, titleLabelX, titleLabelY, 0x404040, false);
        g.drawString(font, playerInventoryTitle, inventoryLabelX, inventoryLabelY, 0x404040, false);
        String full = menu.used() + " / " + StorehouseBlockEntity.SIZE + " used";
        g.drawString(font, full, 176 - 8 - font.width(full), inventoryLabelY, 0x404040, false);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        renderTooltip(g, mouseX, mouseY);
        if (onBar(mouseX, mouseY) && !dragging) {
            g.renderTooltip(font, Component.literal("Rows " + (menu.row() + 1) + "–"
                + (menu.row() + StorehouseMenu.SHOWN_ROWS) + " of " + StorehouseBlockEntity.ROWS
                + " (scroll, or drag; shift scrolls a page)"), mouseX, mouseY);
        }
    }
}
