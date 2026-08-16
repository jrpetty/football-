package com.jrpetty.mcassistant.client;

import com.jrpetty.mcassistant.AssistantActions;
import com.jrpetty.mcassistant.entity.AssistantEntity;
import com.jrpetty.mcassistant.net.OrderPayload;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

import javax.annotation.Nullable;

/**
 * One specialist's orders, on one page: who it is, how it's doing, what it's
 * working towards, and every instruction you can give it. Opened by looking at
 * a bot and pressing the orders key.
 *
 * Laid out in labelled groups — the job, its patch, handling, duty — so it
 * reads as a control panel rather than a wall of identical buttons.
 */
public class OrdersScreen extends Screen {

    private static final int W = 288, H = 236;
    private static final int PAD = 10;

    private final AssistantEntity bot;
    private int left, top;
    @Nullable private Button jobButton, shiftButton;
    @Nullable private Button depthDown, depthUp, dropOff;
    private int shownJob = -1;

    public OrdersScreen(AssistantEntity bot) {
        super(Component.literal("Orders"));
        this.bot = bot;
    }

    @Override
    protected void init() {
        this.left = (this.width - W) / 2;
        this.top = (this.height - H) / 2;
        int x = left + PAD;
        int inner = W - PAD * 2;
        int h = 18, gap = 4;
        int w4 = (inner - gap * 3) / 4;

        // ---- the job itself: arrows either side of a work/pause toggle ----
        int y = top + 60;
        add(x, y, 18, h, "‹", AssistantActions.JOB_PREV, "Previous job");
        jobButton = add(x + 22, y, inner - 44, h, jobTitle(), AssistantActions.WORK,
            "Click to start or pause this job");
        add(x + inner - 18, y, 18, h, "›", AssistantActions.JOB_NEXT, "Next job");
        shownJob = bot.clientJobOrdinal();

        // ---- its patch ----
        y += h + 16;
        add(x, y, w4, h, "Set Area", AssistantActions.ZONE_HERE,
            "Claim a patch centred where you're standing");
        add(x + (w4 + gap), y, w4, h, "Smaller", AssistantActions.ZONE_SHRINK, "Shrink the patch by 4 blocks");
        add(x + 2 * (w4 + gap), y, w4, h, "Bigger", AssistantActions.ZONE_GROW, "Grow the patch by 4 blocks");
        add(x + 3 * (w4 + gap), y, w4, h, "Show", AssistantActions.ZONE_SHOW, "Outline the patch in particles");

        // ---- handling ----
        y += h + 16;
        add(x, y, w4, h, "Follow", AssistantActions.FOLLOW, "Come with me (stops work)");
        add(x + (w4 + gap), y, w4, h, "Stay", AssistantActions.STAY, "Wait here (stops work)");
        add(x + 2 * (w4 + gap), y, w4, h, "Come", AssistantActions.COME, "Walk to me now");
        add(x + 3 * (w4 + gap), y, w4, h, "Guard", AssistantActions.GUARD, "Defend me");

        y += h + gap;
        add(x, y, w4, h, "Stash", AssistantActions.STASH, "Empty its pack into a chest");
        add(x + (w4 + gap), y, w4, h, "Go Home", AssistantActions.GO_HOME, "Return to its home point");
        add(x + 2 * (w4 + gap), y, w4, h, "Pack", AssistantActions.OPEN_PACK, "Open its inventory");
        add(x + 3 * (w4 + gap), y, w4, h, "Stop", AssistantActions.STOP, "Drop whatever it's doing");

        // ---- duty, bed, and the job-specific control ----
        y += h + 16;
        shiftButton = add(x, y, w4 * 2 + gap, h, dutyLabel(), AssistantActions.CYCLE_SHIFT,
            "When this one works. Off duty it sleeps in its bed.");
        add(x + 2 * (w4 + gap), y, w4, h, "Claim Bed", AssistantActions.CLAIM_BED,
            "Give it the nearest bed to you");
        depthDown = add(x + 3 * (w4 + gap), y, w4, h, "Dig ▼", AssistantActions.DEPTH_DOWN,
            "Mine 8 blocks deeper");
        depthUp = add(x + 3 * (w4 + gap), y, w4, h, "Dig ▲", AssistantActions.DEPTH_UP,
            "Mine 8 blocks shallower");
        dropOff = add(x + 3 * (w4 + gap), y, w4, h, "Drop-off", AssistantActions.SET_DROPOFF,
            "Deliver loads to where you're standing");

        // ---- footer ----
        y = top + H - PAD - h;
        add(x, y, w4, h, "Crew", AssistantActions.ROSTER, "Report on every assistant");
        this.addRenderableWidget(Button.builder(Component.literal("Record"),
                b -> this.minecraft.setScreen(new RecordScreen(bot)))
            .bounds(x + (w4 + gap), y, w4, h)
            .tooltip(Tooltip.create(Component.literal(
                "Everything it has done in its career, counted")))
            .build());
        add(x + 2 * (w4 + gap), y, w4, h, "Specialise", AssistantActions.CYCLE_BRANCH,
            "At level 20 a specialist can pick a branch to deepen its trade");
        this.addRenderableWidget(Button.builder(Component.literal("Close"), b -> this.onClose())
            .bounds(x + 3 * (w4 + gap), y, w4, h).build());

        refreshJobButtons();
    }

    private Button add(int x, int y, int w, int h, String label, int action, String tip) {
        Button b = Button.builder(Component.literal(label), btn -> sendOrder(bot, action))
            .bounds(x, y, w, h)
            .tooltip(Tooltip.create(Component.literal(tip)))
            .build();
        return this.addRenderableWidget(b);
    }

    /** Fire an order at a bot. Safe with null — the key may find nobody. */
    public static void sendOrder(@Nullable AssistantEntity bot, int action) {
        if (bot == null) return;
        PacketDistributor.sendToServer(new OrderPayload(bot.getId(), action));
    }

    private String jobTitle() {
        AssistantEntity.StationTask job =
            AssistantEntity.StationTask.byOrdinal(bot.clientJobOrdinal());
        return job == AssistantEntity.StationTask.NONE ? "Pick a job" : job.title;
    }

    private String dutyLabel() {
        return "Works " + bot.clientShift().label;
    }

    /** Only ever show the control that means something for this job. */
    private void refreshJobButtons() {
        if (depthDown == null || depthUp == null || dropOff == null) return;
        AssistantEntity.StationTask job =
            AssistantEntity.StationTask.byOrdinal(bot.clientJobOrdinal());
        boolean miner = job == AssistantEntity.StationTask.MINE;
        boolean hauler = job == AssistantEntity.StationTask.HAUL;
        depthDown.visible = miner;
        depthUp.visible = false;          // one slot: deeper is the common case
        dropOff.visible = hauler;
    }

    @Override
    public void tick() {
        super.tick();
        if (!bot.isAlive()) {
            this.onClose();
            return;
        }
        if (shiftButton != null) shiftButton.setMessage(Component.literal(dutyLabel()));
        if (bot.clientJobOrdinal() != shownJob) {
            shownJob = bot.clientJobOrdinal();
            if (jobButton != null) jobButton.setMessage(Component.literal(jobTitle()));
            refreshJobButtons();
        }
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(g, mouseX, mouseY, partialTick);
        Ui.panel(g, left, top, W, H, 32);

        int x = left + PAD;
        int inner = W - PAD * 2;
        int lvl = bot.clientLevel();

        // Header: who, what level, and the one status line that matters.
        String name = bot.clientName();
        g.drawString(this.font, name, x, top + 8, Ui.INK, false);
        if (lvl >= 1) {
            g.drawString(this.font, "✦" + lvl, x + this.font.width(name) + 6, top + 8, Ui.ACCENT, false);
        }
        String status = bot.clientStatus();
        int sw = this.font.width(status) + 8;
        Ui.pill(g, this.font, status, left + W - PAD - sw, top + 8, Ui.statusColour(status));

        // Level progress, so "how close is my veteran" is answerable at a glance.
        if (lvl < 50) {
            Ui.bar(g, x, top + 22, inner - 46, 4, Ui.levelProgress(lvl, bot.clientLifetimeXp()), Ui.ACCENT);
            Ui.right(g, this.font, "lv " + (lvl + 1), left + W - PAD, top + 20, Ui.FAINT);
        }

        // Section headings.
        Ui.section(g, this.font, "Job", x, top + 48, inner);
        Ui.section(g, this.font, "Its patch", x, top + 60 + 18 + 4, inner);
        Ui.section(g, this.font, "Handling", x, top + 60 + 2 * (18 + 16) - 12, inner);
        Ui.section(g, this.font, "Duty", x, top + 60 + 3 * (18 + 16) + 18 + 4 - 12, inner);

        // Patch and perks, above the footer.
        int infoY = top + H - PAD - 18 - 24;
        BotInfo info = BotInfo.of(bot);
        String career = Ui.clip(this.font,
            bot.clientZone() + "  ·  " + info.daysServed() + "d served"
            + (info.hasBranch() ? "  ·  " + info.branch() : "")
            + (info.topDeed().isEmpty() ? "" : "  ·  " + info.topDeed()), inner);
        g.drawString(this.font, career, x, infoY, Ui.MUTED, false);
        g.drawString(this.font, Ui.clip(this.font, perkLine(lvl), inner), x, infoY + 11,
            lvl >= 10 ? Ui.GOOD : Ui.FAINT, false);

        super.render(g, mouseX, mouseY, partialTick);
    }

    /** What it has earned, and what it earns next. */
    private String perkLine(int lvl) {
        StringBuilder have = new StringBuilder();
        if (lvl >= 10) have.append("+10% work");
        if (lvl >= 20) have.setLength(0);
        if (lvl >= 20) have.append("+2♥ +20% work");
        if (lvl >= 30) have.append("  +20% speed");
        if (lvl >= 35) { have.setLength(0); have.append("+2♥ +30% work +20% speed"); }
        String next = lvl < 10 ? "next lv10: +10% work"
            : lvl < 20 ? "next lv20: +2♥, +20% work"
            : lvl < 30 ? "next lv30: +20% speed"
            : lvl < 35 ? "next lv35: +30% work"
            : "fully trained";
        return (have.length() == 0 ? "No bonuses yet" : have.toString()) + "  ·  " + next;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
