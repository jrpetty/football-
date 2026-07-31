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
        this.imageHeight = 242;
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
        int invY = 160;
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
        g.drawString(this.font, this.title, 8, 6, 0x404040, false);
        AssistantEntity a = this.menu.getAssistant();
        String mode = a != null ? a.getMode().name() : "?";
        g.drawString(this.font, Component.literal("Mode: " + mode), 8, 68, 0x404040, false);
        g.drawString(this.font, Component.literal("armor + hands →"), 8 + 6 * 18 + 2, 82, 0x606060, false);

        // --- Specialisation readout (synced from the entity, so it's live) ---
        if (a == null) return;
        AssistantEntity.StationTask job = AssistantEntity.StationTask.byOrdinal(a.clientJobOrdinal());
        g.drawString(this.font, Component.literal(job.title + " · " + a.clientZone()),
            8, 136, 0x303030, false);

        String status = a.clientStatus();
        int colour = status.startsWith("Working") ? 0x1F7A34   // green: earning its keep
            : status.startsWith("Needs") || status.startsWith("Out of") ? 0x9C2B18  // red: blocked
            : 0x7A5A10;                                                            // amber: still setting up
        g.drawString(this.font, Component.literal(status), 8, 148, colour, false);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        this.renderTooltip(g, mouseX, mouseY);
    }
}
