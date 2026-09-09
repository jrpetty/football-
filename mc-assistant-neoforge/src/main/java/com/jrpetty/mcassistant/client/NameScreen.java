package com.jrpetty.mcassistant.client;

import com.jrpetty.mcassistant.AssistantActions;
import com.jrpetty.mcassistant.entity.AssistantEntity;
import com.jrpetty.mcassistant.entity.Names;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Naming a specialist, by pointing at a name. No anvil, no name tag, and no
 * typing — you get a page of names that nobody on the crew is using, and you
 * click one.
 *
 * <p>Only an index into the shared pool crosses the wire, so the screen cannot
 * ask the server for a name that does not exist.
 */
public class NameScreen extends Screen {

    private static final int W = 268, H = 214;
    private static final int PAD = 10;
    private static final int COLS = 3, ROWS = 6;
    private static final int PER_PAGE = COLS * ROWS;

    private final AssistantEntity bot;
    private final List<Integer> offered = new ArrayList<>();
    private int left, top, page;

    public NameScreen(AssistantEntity bot) {
        super(Component.literal("Name"));
        this.bot = bot;
    }

    @Override
    protected void init() {
        this.left = (this.width - W) / 2;
        this.top = (this.height - H) / 2;

        // Only names the crew isn't already using: offering a duplicate the
        // server will refuse is a button that does nothing.
        offered.clear();
        List<String> taken = crewNames();
        for (int i = 0; i < Names.POOL.size(); i++) {
            if (!containsIgnoreCase(taken, Names.POOL.get(i))) offered.add(i);
        }
        int pages = Math.max(1, (offered.size() + PER_PAGE - 1) / PER_PAGE);
        if (page >= pages) page = 0;

        int inner = W - PAD * 2;
        int gap = 4;
        int bw = (inner - gap * (COLS - 1)) / COLS;
        int bh = 18;

        int start = page * PER_PAGE;
        for (int i = 0; i < PER_PAGE && start + i < offered.size(); i++) {
            int poolIndex = offered.get(start + i);
            String name = Names.POOL.get(poolIndex);
            int x = left + PAD + (i % COLS) * (bw + gap);
            int y = top + 44 + (i / COLS) * (bh + gap);
            this.addRenderableWidget(Button.builder(Component.literal(name), b -> {
                OrdersScreen.sendOrder(bot, AssistantActions.NAME_BASE + poolIndex);
                this.onClose();
            }).bounds(x, y, bw, bh).build());
        }

        int fy = top + H - PAD - bh;
        int fw = (inner - gap * 2) / 3;
        int pageCount = pages;
        Button more = Button.builder(Component.literal("More names"), b -> {
            page = (page + 1) % pageCount;
            this.rebuildWidgets();
        }).bounds(left + PAD, fy, fw, bh).build();
        more.active = pageCount > 1;
        this.addRenderableWidget(more);
        this.addRenderableWidget(Button.builder(Component.literal("Orders"),
                b -> this.minecraft.setScreen(new OrdersScreen(bot)))
            .bounds(left + PAD + fw + gap, fy, fw, bh).build());
        this.addRenderableWidget(Button.builder(Component.literal("Close"), b -> this.onClose())
            .bounds(left + PAD + 2 * (fw + gap), fy, fw, bh).build());
    }

    private List<String> crewNames() {
        List<String> out = new ArrayList<>();
        if (this.minecraft == null || this.minecraft.player == null) return out;
        for (AssistantEntity a : this.minecraft.player.level().getEntitiesOfClass(
                AssistantEntity.class,
                this.minecraft.player.getBoundingBox().inflate(256.0),
                AssistantEntity::isAlive)) {
            out.add(a.clientName());
        }
        return out;
    }

    private static boolean containsIgnoreCase(List<String> haystack, String needle) {
        for (String s : haystack) {
            if (s.equalsIgnoreCase(needle)) return true;
        }
        return false;
    }

    @Override
    public void tick() {
        super.tick();
        if (!bot.isAlive()) this.onClose();
    }

    /**
     * ALL of this screen's own painting happens here, layered between the
     * background and the widgets, because since 1.21 the vanilla render loop
     * runs the menu BLUR SHADER inside renderBackground — and super.render()
     * calls renderBackground itself. The old pattern (paint the panel, then
     * call super.render) ran the blur a SECOND time over everything already
     * drawn: every panel and every line of text got gaussian-smeared, and the
     * vanilla buttons were drawn crisp on top afterwards. That is the blur
     * that survived four palette overhauls — it was never contrast.
     */
    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(g, mouseX, mouseY, partialTick);
        Ui.panel(g, left, top, W, H, 30);
        int x = left + PAD;
        int inner = W - PAD * 2;

        g.drawString(this.font, "Name this one", x, top + 8, Ui.INK, false);
        Ui.right(g, this.font, "currently " + bot.clientName(), left + W - PAD, top + 8, Ui.MUTED);
        g.drawString(this.font,
            offered.isEmpty() ? "Every name is taken — dismiss someone first."
                              : "Pick a name. Nobody on your crew has these.",
            x, top + 22, Ui.FAINT, false);
        Ui.section(g, this.font, "Names", x, top + 34, inner);

    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
