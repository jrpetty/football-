package com.jrpetty.mcassistant.client;

import com.jrpetty.mcassistant.entity.AssistantEntity;
import com.jrpetty.mcassistant.menu.AssistantInventoryContainer;
import com.jrpetty.mcassistant.menu.AssistantMenu;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

/**
 * The right-click management GUI. Draws a simple panel (no texture asset
 * needed), the assistant's backpack + equipment slots, the player's inventory,
 * and a row of control buttons — Stop, Follow, Stay, Guard, Deposit, Come —
 * that fire server-side actions on the entity via the menu.
 */
public class AssistantScreen extends AbstractContainerScreen<AssistantMenu> {

    private static final int PANEL = 0xFFC6C6C6;   // vanilla-ish grey
    private static final int PANEL_HI = 0xFFFFFFFF; // top/left highlight
    private static final int PANEL_LO = 0xFF555555; // bottom/right shadow
    private static final int SLOT_BG = 0xFF8B8B8B;
    private static final int SLOT_HI = 0xFF373737;

    private int shownJob = -1;

    public AssistantScreen(AssistantMenu menu, Inventory playerInv, Component title) {
        super(menu, playerInv, title);
        this.imageWidth = 176;
        this.imageHeight = 260;
        this.inventoryLabelY = this.imageHeight - 94; // unused label, kept sane
    }

    @Override
    protected void init() {
        super.init();
        int x = this.leftPos;
        int y = this.topPos;

        // The pack screen is the PACK now: its slots, its readouts, and two
        // buttons — everything it used to duplicate (job cycling, handling,
        // area sizing, dig depth) lives on the orders sheet, once.
        int bh = 18;
        AssistantEntity a = this.menu.getAssistant();
        this.shownJob = a == null ? -1 : a.clientJobOrdinal();
        // A village's folk take no orders and stash nothing for you: their pack is to
        // look at, and the way back is to the conversation it was opened from.
        if (a instanceof com.jrpetty.mcassistant.entity.VillageFolkEntity) {
            this.addRenderableWidget(Button.builder(Component.literal("\u2039 Talk"), b -> {
                    if (this.minecraft != null && this.minecraft.player != null) {
                        this.minecraft.player.closeContainer();
                        net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                            new com.jrpetty.mcassistant.net.FolkTalkPayload(a.getId(),
                                com.jrpetty.mcassistant.entity.TalkTopic.OPEN.ordinal(), ""));
                    }
                }).bounds(x + 8, y + 114, 78, bh).build());
            // Everything it has done in its life: blocks mined, trees felled, crops in, fish caught.
            this.addRenderableWidget(Button.builder(Component.literal("Work done"), b -> {
                    if (this.minecraft != null && this.minecraft.player != null) {
                        this.minecraft.player.closeContainer();
                        this.minecraft.setScreen(new RecordScreen(a));
                    }
                }).bounds(x + 90, y + 114, 78, bh)
                .tooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal(
                    "What it has done: blocks mined, trees felled, crops harvested, things made")))
                .build());
            return;
        }
        this.addRenderableWidget(Button.builder(Component.literal("Orders \u203a"), b -> {
                if (this.minecraft != null && this.minecraft.player != null && a != null) {
                    this.minecraft.player.closeContainer();
                    this.minecraft.setScreen(new OrdersScreen(a));
                }
            }).bounds(x + 8, y + 114, 100, bh).build());
        addButton(x + 112, y + 114, 56, bh, "Stash", AssistantMenu.BTN_DEPOSIT);
    }

    private Button addButton(int x, int y, int w, int h, String label, int buttonId) {
        return this.addRenderableWidget(Button.builder(Component.literal(label), b -> {
            if (this.minecraft != null && this.minecraft.gameMode != null) {
                this.minecraft.gameMode.handleInventoryButtonClick(this.menu.containerId, buttonId);
            }
        }).bounds(x, y, w, h).build());
    }


    @Override
    protected void renderBg(GuiGraphics g, float partialTick, int mouseX, int mouseY) {
        int x = this.leftPos;
        int y = this.topPos;

        // Panel with a beveled border.
        g.fill(x, y, x + imageWidth, y + imageHeight, PANEL);
        g.fill(x, y, x + imageWidth, y + 1, PANEL_HI);
        g.fill(x, y, x + 1, y + imageHeight, PANEL_HI);
        g.fill(x, y + imageHeight - 1, x + imageWidth, y + imageHeight, PANEL_LO);
        g.fill(x + imageWidth - 1, y, x + imageWidth, y + imageHeight, PANEL_LO);

        // Slot cells (18x18) matching the menu's slot positions.
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) drawSlot(g, x + 8 + col * 18, y + 18 + row * 18);
        }
        for (int i = 0; i < AssistantInventoryContainer.EQUIP; i++) {
            drawSlot(g, x + 8 + i * 18, y + 78);
        }
        int invY = 178;
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) drawSlot(g, x + 8 + col * 18, y + invY + row * 18);
        }
        for (int col = 0; col < 9; col++) drawSlot(g, x + 8 + col * 18, y + invY + 58);
    }

    private void drawSlot(GuiGraphics g, int x, int y) {
        // 18x18 cell drawn one pixel out so the 16x16 item sits centered at +1.
        g.fill(x - 1, y - 1, x + 17, y + 17, SLOT_HI);
        g.fill(x - 1, y - 1, x + 17, y, SLOT_HI);
        g.fill(x, y, x + 16, y + 16, SLOT_BG);
    }

    @Override
    protected void renderLabels(GuiGraphics g, int mouseX, int mouseY) {
        // One palette, not two: these used to be hand-picked greys and greens
        // that drifted from Ui's every time Ui was retuned. 0x404040 stays,
        // because that is vanilla's own container-title grey.
        g.drawString(this.font, this.title, 8, 6, 0x404040, false);
        AssistantEntity a = this.menu.getAssistant();

        // Mode sits on the title line, right-aligned. It used to be drawn at
        // y=68 — on top of the third row of backpack slots — and the equipment
        // caption ran off the right-hand edge of the panel entirely.
        String mode = a != null ? a.getMode().name() : "?";
        // A settler takes no orders, so it has no mode worth showing: what shows is
        // who it is (hover the title for its partner, friends and family).
        if (a instanceof com.jrpetty.mcassistant.entity.VillageFolkEntity folk) {
            String traits = folk.clientSocial().split("\\|", -1)[0];
            mode = traits.isEmpty() ? "" : traits;
        }
        int modeMax = imageWidth - 16 - this.font.width(this.title) - 8;
        mode = clipTo(mode, Math.max(20, modeMax));
        g.drawString(this.font, Component.literal(mode),
            imageWidth - 8 - this.font.width(mode), 6, Ui.FAINT, false);
        g.drawString(this.font, Component.literal("gear"), 8 + 6 * 18 + 4, 82,
            Ui.FAINT, false);

        // --- Specialisation readout (synced from the entity, so it's live) ---
        if (a == null) return;
        AssistantEntity.StationTask job = AssistantEntity.StationTask.byOrdinal(a.clientJobOrdinal());
        g.drawString(this.font, Component.literal(trim(job.title + " · " + a.clientZone())),
            8, 154, Ui.MUTED, false);

        // What it is running on. Worked out first because the status line below
        // shares its row and has to be clipped to whatever space this leaves.
        BotInfo info = BotInfo.of(a);
        int diet = info.diet();
        int barW = imageWidth - 16;
        int fill = Math.max(1, barW * Math.max(0, Math.min(100, diet)) / 100);
        int dietColour = diet >= 95 ? Ui.GOOD : diet >= 70 ? Ui.ACCENT
            : diet >= 45 ? Ui.WARN : Ui.BAD;
        String pace = AssistantEntity.foodLabel(diet) + " " + diet + "%";

        String status = a.clientStatus();
        int colour = Ui.statusColour(status);
        g.drawString(this.font, Component.literal(
            clipTo(status, imageWidth - 16 - this.font.width(pace) - 6)),
            8, 165, colour, false);
        // This screen is packed: slots 18-96, three button rows 96-150, and the
        // player's own inventory from 178. The only free band is 150-178, which
        // already carries the job line and the status line. So the diet shares
        // the status line — right-aligned, in its own colour — and the bar is a
        // hairline in the last few pixels before the player's slots.
        //
        // The b61 version put this at y=132 and y=143, which is squarely on top
        // of the Set Area / - / + / Show row. That is what was drawn through
        // the buttons.
        g.drawString(this.font, Component.literal(pace),
            imageWidth - 8 - this.font.width(pace), 165, dietColour, false);
        g.fill(8, 174, 8 + barW, 177, 0x30000000);
        g.fill(8, 174, 8 + fill, 177, 0xFF000000 | dietColour);
    }

    /** Clip to an explicit width — used where two readouts share a line. */
    private String clipTo(String text, int max) {
        if (this.font.width(text) <= max) return text;
        String cut = text;
        while (cut.length() > 3 && this.font.width(cut + "...") > max) {
            cut = cut.substring(0, cut.length() - 1);
        }
        return cut + "...";
    }

    /** Clip a readout to the panel so nothing spills onto the world behind it. */
    private String trim(String text) {
        int max = imageWidth - 16;
        if (this.font.width(text) <= max) return text;
        String cut = text;
        while (cut.length() > 3 && this.font.width(cut + "...") > max) {
            cut = cut.substring(0, cut.length() - 1);
        }
        return cut + "...";
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        this.renderTooltip(g, mouseX, mouseY);
        // A settler's life, on the title line: personality, partner, friends, family.
        AssistantEntity a = this.menu.getAssistant();
        if (a instanceof com.jrpetty.mcassistant.entity.VillageFolkEntity folk
                && mouseX >= leftPos && mouseX < leftPos + imageWidth
                && mouseY >= topPos + 2 && mouseY < topPos + 16) {
            String[] f = folk.clientSocial().split("\\|", -1);
            java.util.List<Component> lines = new java.util.ArrayList<>();
            lines.add(Component.literal(a.displayNameCap()));
            if (f.length > 0 && !f[0].isEmpty()) lines.add(Component.literal("Personality: " + f[0]));
            if (f.length > 1 && !f[1].isEmpty()) lines.add(Component.literal("Partner: " + f[1]));
            lines.add(Component.literal("Friends: " + (f.length > 2 && !f[2].isEmpty() ? f[2] : "none yet")));
            if (f.length > 3 && !f[3].isEmpty()) lines.add(Component.literal("Does not get on with: " + f[3]));
            if (f.length > 4 && !f[4].isEmpty()) lines.add(Component.literal("Family: " + f[4]));
            if (f.length > 5 && !f[5].isEmpty()) lines.add(Component.literal("Feeling: " + f[5]));
            if (f.length > 6 && !f[6].isEmpty()) lines.add(Component.literal("Loves: " + f[6]));
            if (f.length > 7 && !f[7].isEmpty()) lines.add(Component.literal("Hopes: " + f[7]));
            lines.add(Component.literal("Right-click to talk (its Pack button opens this); sneak and right-click comes straight here."));
            g.renderComponentTooltip(this.font, lines, mouseX, mouseY);
        }
    }
}
