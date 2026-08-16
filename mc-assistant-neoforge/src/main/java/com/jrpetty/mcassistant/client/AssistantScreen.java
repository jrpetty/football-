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

    private Button jobButton;   // its label is the current job, kept live
    private Button depthDown, depthUp, setHome; // job-specific controls
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

        // Job row: the middle button NAMES the current job (so you always see
        // what you're choosing) and clicking it starts or pauses that work.
        int bh = 18;
        AssistantEntity a = this.menu.getAssistant();
        String jobName = a == null ? "..."
            : AssistantEntity.StationTask.byOrdinal(a.clientJobOrdinal()).title;
        addButton(x + 8, y + 96, 18, bh, "<", AssistantMenu.BTN_JOB_PREV);
        this.jobButton = addButton(x + 28, y + 96, 120, bh, jobName, AssistantMenu.BTN_WORK);
        this.shownJob = a == null ? -1 : a.clientJobOrdinal();
        addButton(x + 150, y + 96, 18, bh, ">", AssistantMenu.BTN_JOB_NEXT);

        // Handling row: call it over, park it, stash its pack, set dig depth.
        addButton(x + 8, y + 114, 40, bh, "Follow", AssistantMenu.BTN_FOLLOW);
        addButton(x + 50, y + 114, 32, bh, "Stay", AssistantMenu.BTN_STAY);
        addButton(x + 84, y + 114, 44, bh, "Stash", AssistantMenu.BTN_DEPOSIT);
        // The last slot on this row is job-specific: dig depth for a miner, the
        // drop-off point for a hauler, nothing for everyone else.
        this.depthDown = addButton(x + 130, y + 114, 18, bh, "-", AssistantMenu.BTN_DEPTH_DOWN);
        this.depthUp = addButton(x + 150, y + 114, 18, bh, "+", AssistantMenu.BTN_DEPTH_UP);
        this.setHome = addButton(x + 130, y + 114, 38, bh, "Drop", AssistantMenu.BTN_SET_HOME);

        // Area row: set and size the patch without crafting anything. "Area"
        // claims a zone centred on where YOU are standing — walk to the middle
        // of the field, click, done — and -/+ resize it in place.
        addButton(x + 8, y + 132, 62, bh, "Set Area", AssistantMenu.BTN_ZONE_HERE);
        addButton(x + 72, y + 132, 30, bh, "-", AssistantMenu.BTN_ZONE_SHRINK);
        addButton(x + 104, y + 132, 30, bh, "+", AssistantMenu.BTN_ZONE_GROW);
        addButton(x + 136, y + 132, 32, bh, "Show", AssistantMenu.BTN_ZONE_SHOW);
        refreshJobButtons(a == null ? 0 : a.clientJobOrdinal());
    }

    private Button addButton(int x, int y, int w, int h, String label, int buttonId) {
        return this.addRenderableWidget(Button.builder(Component.literal(label), b -> {
            if (this.minecraft != null && this.minecraft.gameMode != null) {
                this.minecraft.gameMode.handleInventoryButtonClick(this.menu.containerId, buttonId);
            }
        }).bounds(x, y, w, h).build());
    }

    /** Keep the job button's label in step with the entity as it's cycled. */
    @Override
    protected void containerTick() {
        super.containerTick();
        AssistantEntity a = this.menu.getAssistant();
        if (a == null || jobButton == null) return;
        int job = a.clientJobOrdinal();
        if (job != shownJob) {
            shownJob = job;
            jobButton.setMessage(Component.literal(AssistantEntity.StationTask.byOrdinal(job).title));
            refreshJobButtons(job);
        }
    }

    /** Show only the controls that mean anything for the current job. */
    private void refreshJobButtons(int jobOrdinal) {
        if (depthDown == null || depthUp == null || setHome == null) return;
        AssistantEntity.StationTask job = AssistantEntity.StationTask.byOrdinal(jobOrdinal);
        boolean miner = job == AssistantEntity.StationTask.MINE;
        boolean hauler = job == AssistantEntity.StationTask.HAUL;
        depthDown.visible = miner;
        depthUp.visible = miner;
        setHome.visible = hauler;
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
    }
}
