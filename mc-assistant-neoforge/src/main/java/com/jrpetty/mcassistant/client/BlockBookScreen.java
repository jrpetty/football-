package com.jrpetty.mcassistant.client;

import com.jrpetty.mcassistant.BlockLore;
import com.jrpetty.mcassistant.entity.RecipeBook;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The block book: what every block in the game is FOR, why you would put one
 * down, and when in the life of a base it starts to matter — with the recipe
 * that makes it, read from the game's own recipe list rather than written out
 * here.
 *
 * <p>This is the same knowledge the crew builds from. A builder that knows
 * only cobblestone and planks cannot use the deepslate it just dug; every one
 * of these categories is a question the build code actually asks, so what the
 * book says is what they do.
 */
public class BlockBookScreen extends Screen {

    private static final int W = 340, H = 236;
    private static final int PAD = 10;
    private static final int LIST_TOP = 44;
    private static final int USE_W = 104;
    private static final int USE_ROW = 15;
    private static final int USE_VIS = 10;
    private static final int CELL = 18;
    private static final int GRID_ROWS = 4;

    private final BlockLore.Use[] uses = BlockLore.Use.values();
    private Map<BlockLore.Use, Integer> census = Map.of();
    private List<Block> shown = new ArrayList<>();
    private int picked;
    private int useScroll;
    private int gridScroll;
    private int left, top;
    private int gridCols = 11;

    public BlockBookScreen() {
        super(Component.literal("Block book"));
    }

    @Override
    protected void init() {
        this.left = (this.width - W) / 2;
        this.top = (this.height - H) / 2;
        this.census = BlockLore.census();
        reload();

        int inner = W - PAD * 2;
        int gap = 4;
        int w4 = (inner - gap * 3) / 4;
        int y = top + H - PAD - 18;
        int x = left + PAD;
        this.addRenderableWidget(Button.builder(Component.literal("‹ Back"),
                b -> { this.onClose(); PlotsClient.requestOpen(); })
            .bounds(x, y, w4, 18).build());
        this.addRenderableWidget(Button.builder(Component.literal("More ›"),
                b -> pageBlocks())
            .bounds(x + w4 + gap, y, w4, 18).build());
        // The classification is rules read over the whole registry, and rules
        // over a thousand blocks are wrong somewhere. This writes down every
        // verdict so it can be checked against the game rather than believed.
        this.addRenderableWidget(Button.builder(Component.literal("Export"),
                b -> exportAudit())
            .bounds(x + 2 * (w4 + gap), y, w4, 18)
            .tooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal(
                "Write every block and what this build thinks it is for to "
                + "mcassistant-blocklore.txt in your game folder")))
            .build());
        this.addRenderableWidget(Button.builder(Component.literal("Close"), b -> this.onClose())
            .bounds(x + 3 * (w4 + gap), y, inner - 3 * (w4 + gap), 18).build());
    }

    /** The picked category's blocks, in registry order. */
    private void reload() {
        this.shown = BlockLore.byUse(uses[picked]);
        this.gridScroll = 0;
    }

    /** Round-robin through the pages of a big category rather than clamping at
     *  the end — one button, and it always does something. */
    private void pageBlocks() {
        int perPage = gridCols * GRID_ROWS;
        gridScroll += perPage;
        if (gridScroll >= shown.size()) gridScroll = 0;
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(g, mouseX, mouseY, partialTick);
        Ui.panel(g, left, top, W, H, 26);

        int x = left + PAD;
        g.drawString(this.font, "Block book", x, top + 8, Ui.INK, false);
        Ui.right(g, this.font, BuiltInRegistries.BLOCK.size() + " blocks known",
            left + W - PAD, top + 8, Ui.MUTED);
        g.drawString(this.font, exported.isEmpty()
                ? "What it does, why you'd build it, and when."
                : exported,
            x, top + 30, exported.isEmpty() ? Ui.FAINT : Ui.GOOD, false);

        renderUses(g, mouseX, mouseY);
        renderDetail(g, mouseX, mouseY);
    }

    private void renderUses(GuiGraphics g, int mouseX, int mouseY) {
        int x = left + PAD;
        int y = top + LIST_TOP;
        Ui.section(g, this.font, "Used for", x, y - 11, USE_W);
        int max = Math.min(uses.length, useScroll + USE_VIS);
        for (int i = useScroll; i < max; i++) {
            int ry = y + (i - useScroll) * USE_ROW;
            boolean on = i == picked;
            boolean hot = mouseX >= x && mouseX < x + USE_W && mouseY >= ry && mouseY < ry + USE_ROW;
            g.fill(x, ry, x + USE_W, ry + USE_ROW - 1,
                on ? Ui.ROW_PICK : hot ? Ui.ROW_ALT : Ui.ROW);
            int count = census.getOrDefault(uses[i], 0);
            String tally = String.valueOf(count);
            int tallyW = this.font.width(tally);
            g.drawString(this.font, Ui.clip(this.font, uses[i].label, USE_W - tallyW - 12),
                x + 4, ry + 4, Ui.INK, false);
            Ui.right(g, this.font, tally, x + USE_W - 4, ry + 4, Ui.FAINT);
        }
        if (uses.length > USE_VIS) {
            g.drawString(this.font, "scroll for more", x + 2, y + USE_VIS * USE_ROW + 3,
                Ui.FAINT, false);
        }
    }

    private void renderDetail(GuiGraphics g, int mouseX, int mouseY) {
        BlockLore.Use use = uses[picked];
        int rx = left + PAD + USE_W + 8;
        int rw = left + W - PAD - rx;
        this.gridCols = Math.max(1, rw / CELL);
        int y = top + LIST_TOP;

        g.drawString(this.font, use.label, rx, y, Ui.ACCENT, false);
        Ui.right(g, this.font, use.age, left + W - PAD, y, Ui.MUTED);
        y += 12;
        y = paragraph(g, "Does", use.does, rx, y, rw);
        y = paragraph(g, "Why", use.why, rx, y, rw);
        y = paragraph(g, "When", use.when, rx, y, rw);

        int gridTop = top + LIST_TOP + 90;
        int shownCount = Math.min(shown.size() - gridScroll, gridCols * GRID_ROWS);
        g.drawString(this.font, shown.isEmpty() ? "nothing in this world"
                : "showing " + (gridScroll + 1) + "–" + (gridScroll + Math.max(0, shownCount))
                  + " of " + shown.size(),
            rx, gridTop - 11, Ui.FAINT, false);

        Block hovered = null;
        for (int i = 0; i < gridCols * GRID_ROWS; i++) {
            int idx = gridScroll + i;
            if (idx >= shown.size()) break;
            int cx = rx + (i % gridCols) * CELL;
            int cy = gridTop + (i / gridCols) * CELL;
            ItemStack stack = new ItemStack(shown.get(idx));
            if (stack.isEmpty()) stack = new ItemStack(Items.BARRIER);
            g.renderItem(stack, cx, cy);
            if (mouseX >= cx && mouseX < cx + 16 && mouseY >= cy && mouseY < cy + 16) {
                hovered = shown.get(idx);
            }
        }
        if (hovered != null) tooltipFor(g, hovered, mouseX, mouseY);
    }

    /**
     * A labelled paragraph wrapped to the whole pane. The caption rides inside
     * the text rather than beside it, so the wrap gets the full width and a
     * long line has somewhere to go instead of being cut off mid-word.
     */
    private int paragraph(GuiGraphics g, String caption, String body, int x, int y, int w) {
        List<net.minecraft.util.FormattedCharSequence> lines =
            this.font.split(Component.literal(caption + " — " + body), w);
        int drawn = Math.min(2, lines.size());
        for (int i = 0; i < drawn; i++) {
            g.drawString(this.font, lines.get(i), x, y + i * 9, i == 0 ? Ui.INK : Ui.MUTED, false);
        }
        return y + Math.max(1, drawn) * 9 + 3;
    }

    /** Name, use, and the blueprint — straight out of the game's recipe list,
     *  so it is the real recipe and not something written down here. */
    private void tooltipFor(GuiGraphics g, Block block, int mouseX, int mouseY) {
        List<Component> lines = new ArrayList<>();
        lines.add(block.getName());
        BlockLore.Use use = BlockLore.of(block);
        lines.add(Component.literal(use.label + "  ·  " + use.age));
        Item item = block.asItem();
        RecipeBook.Craft craft = this.minecraft != null && this.minecraft.level != null
            ? RecipeBook.craftFor(this.minecraft.level, item) : null;
        if (craft == null) {
            lines.add(Component.literal("Blueprint: none — mined or found."));
        } else {
            StringBuilder sb = new StringBuilder("Blueprint: ");
            for (int i = 0; i < craft.parts().size(); i++) {
                if (i > 0) sb.append(" + ");
                sb.append(craft.parts().get(i).count()).append(' ')
                  .append(craft.parts().get(i).label());
            }
            sb.append(" → ").append(craft.yield());
            if (craft.needsTable()) sb.append(" (bench)");
            lines.add(Component.literal(sb.toString()));
        }
        g.renderComponentTooltip(this.font, lines, mouseX, mouseY);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        int x = left + PAD;
        int y = top + LIST_TOP;
        if (mouseX >= x && mouseX < x + USE_W && mouseY >= y && mouseY < y + USE_VIS * USE_ROW) {
            int row = (int) ((mouseY - y) / USE_ROW) + useScroll;
            if (row >= 0 && row < uses.length) {
                picked = row;
                reload();
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double dx, double dy) {
        int step = dy > 0 ? -1 : 1;
        if (mouseX < left + PAD + USE_W) {
            useScroll = Math.max(0, Math.min(Math.max(0, uses.length - USE_VIS), useScroll + step));
        } else {
            int perPage = gridCols * GRID_ROWS;
            gridScroll = Math.max(0, Math.min(Math.max(0, shown.size() - perPage),
                gridScroll + step * gridCols));
        }
        return true;
    }

    /** Dump the whole classification next to the game's own files. */
    private void exportAudit() {
        if (this.minecraft == null) return;
        try {
            java.nio.file.Path wrote = BlockLore.audit(this.minecraft.gameDirectory.toPath());
            this.exported = "Written to " + wrote.getFileName();
        } catch (Exception e) {
            this.exported = "Could not write the file: " + e.getMessage();
        }
    }

    private String exported = "";

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
